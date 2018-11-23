/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.sharding.config;

import org.ethereum.config.CommonConfig;
import org.ethereum.config.SystemProperties;
import org.ethereum.core.EventDispatchThread;
import org.ethereum.crypto.HashUtil;
import org.ethereum.datasource.AbstractCachedSource;
import org.ethereum.datasource.AsyncWriteCache;
import org.ethereum.datasource.BatchSourceWriter;
import org.ethereum.datasource.DbSettings;
import org.ethereum.datasource.DbSource;
import org.ethereum.datasource.MemSizeEstimator;
import org.ethereum.datasource.Source;
import org.ethereum.datasource.WriteCache;
import org.ethereum.datasource.XorDataSource;
import org.ethereum.db.BlockStore;
import org.ethereum.db.DbFlushManager;
import org.ethereum.db.TransactionStore;
import org.ethereum.facade.Ethereum;
import org.ethereum.manager.WorldManager;
import org.ethereum.sharding.crypto.DummySign;
import org.ethereum.sharding.crypto.Sign;
import org.ethereum.sharding.pubsub.Publisher;
import org.ethereum.sharding.manager.ShardingWorldManager;
import org.ethereum.sharding.processing.BeaconChain;
import org.ethereum.sharding.processing.BeaconChainFactory;
import org.ethereum.sharding.processing.db.BeaconStore;
import org.ethereum.sharding.processing.db.IndexedBeaconStore;
import org.ethereum.sharding.processing.state.BeaconStateRepository;
import org.ethereum.sharding.processing.state.StateRepository;
import org.ethereum.sharding.validator.AttestationPool;
import org.ethereum.sharding.validator.AttestationPoolImpl;
import org.ethereum.sharding.validator.BeaconAttester;
import org.ethereum.sharding.validator.BeaconAttesterImpl;
import org.ethereum.sharding.validator.BeaconProposer;
import org.ethereum.sharding.validator.BeaconProposerImpl;
import org.ethereum.sharding.validator.ValidatorService;
import org.ethereum.sharding.validator.ValidatorServiceImpl;
import org.ethereum.sharding.registration.MultiValidatorRegistrationService;
import org.ethereum.sharding.registration.ValidatorRepositoryImpl;
import org.ethereum.sharding.registration.ValidatorRegistrationService;
import org.ethereum.sharding.crypto.DepositAuthority;
import org.ethereum.sharding.contract.DepositContract;
import org.ethereum.sharding.crypto.UnsecuredDepositAuthority;
import org.ethereum.sharding.util.Randao;
import org.ethereum.sharding.registration.ValidatorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Collections;

/**
 * In addition to {@link ShardingConfig} bootstraps beacon chain processing.
 *
 * <p>
 *     Enabled only if {@code beacon.enabled} option in config is set to {@code true}.
 *
 * @author Mikhail Kalinin
 * @since 21.07.2018
 */
@Configuration
@Conditional(BeaconConfig.Enabled.class)
public class BeaconConfig {

    @Autowired
    CommonConfig commonConfig;

    @Autowired
    Ethereum ethereum;

    @Autowired
    WorldManager worldManager;

    @Autowired
    DepositContractConfig depositContractConfig;

    @Autowired
    BlockStore blockStore;

    @Autowired
    TransactionStore txStore;

    @Autowired
    ShardingWorldManager shardingWorldManager;

    @Autowired
    SystemProperties systemProperties;

    @Autowired
    EventDispatchThread eventDispatchThread;

    @Bean
    public ValidatorConfig validatorConfig() {
        return ValidatorConfig.fromFile();
    }

    @Bean
    public ValidatorRegistrationService validatorRegistrationService() {
        ValidatorRegistrationService validatorRegistrationService;
        if (validatorConfig().isEnabled()) {
            validatorRegistrationService = new MultiValidatorRegistrationService(ethereum, validatorConfig(),
                    depositContract(), depositAuthority(), randao(), publisher());
        } else {
            validatorRegistrationService = new ValidatorRegistrationService() {};
        }
        shardingWorldManager.setValidatorRegistrationService(validatorRegistrationService);
        return validatorRegistrationService;
    }

    @Bean
    public ValidatorService validatorService() {
        if (validatorConfig().isEnabled()) {
            ValidatorService validatorService = new ValidatorServiceImpl(beaconProposer(), beaconAttester(),
                    beaconChain(), publisher(), validatorConfig(), ethereum, blockStore);
            shardingWorldManager.setProposerService(validatorService);
            return validatorService;
        } else {
            return new ValidatorService() {};
        }
    }

    @Bean
    public ValidatorRepository validatorRepository() {
        return new ValidatorRepositoryImpl(blockStore, txStore, depositContract());
    }

    @Bean
    public DepositContract depositContract() {
        return new DepositContract(depositContractConfig.getAddress(), depositContractConfig.getBin(),
                depositContractConfig.getAbi());
    }

    @Bean
    public BeaconStore beaconStore() {
        Source<byte[], byte[]> blockSrc = cachedBeaconChainSource("beacon_block");
        Source<byte[], byte[]> indexSrc = cachedBeaconChainSource("beacon_index");
        return new IndexedBeaconStore(blockSrc, indexSrc);
    }

    @Bean
    public StateRepository beaconStateRepository() {
        Source<byte[], byte[]> src = cachedBeaconChainSource("beacon_state");
        Source<byte[], byte[]> validatorSrc = cachedBeaconChainSource("validator_set");
        Source<byte[], byte[]> validatorIndexSrc = cachedBeaconChainSource("validator_index");
        return new BeaconStateRepository(src, validatorSrc, validatorIndexSrc);
    }

    @Bean
    public BeaconChain beaconChain() {
        BeaconChain beaconChain = BeaconChainFactory.create(beaconDbFlusher(), beaconStore(), beaconStateRepository(),
                validatorRepository(), blockStore.getBestBlock(), publisher(), sign());
        shardingWorldManager.setBeaconChain(beaconChain);
        return beaconChain;
    }

    @Bean
    @Scope("prototype")
    public Source<byte[], byte[]> cachedBeaconChainSource(String name) {
        AbstractCachedSource<byte[], byte[]> writeCache = new AsyncWriteCache<byte[], byte[]>(beaconChainSource(name)) {
            @Override
            protected WriteCache<byte[], byte[]> createCache(Source<byte[], byte[]> source) {
                WriteCache.BytesKey<byte[]> ret = new WriteCache.BytesKey<>(source, WriteCache.CacheType.SIMPLE);
                ret.withSizeEstimators(MemSizeEstimator.ByteArrayEstimator, MemSizeEstimator.ByteArrayEstimator);
                ret.setFlushSource(true);
                return ret;
            }
        }.withName(name);
        beaconDbFlusher().addCache(writeCache);
        return writeCache;
    }

    @Bean
    @Scope("prototype")
    public Source<byte[], byte[]> beaconChainSource(String name) {
        return new XorDataSource<>(beaconChainDbCache(), HashUtil.sha3(name.getBytes()));
    }

    @Bean
    public AbstractCachedSource<byte[], byte[]> beaconChainDbCache() {
        WriteCache.BytesKey<byte[]> ret = new WriteCache.BytesKey<>(
                new BatchSourceWriter<>(beaconChainDB()), WriteCache.CacheType.SIMPLE);
        ret.setFlushSource(true);
        return ret;
    }

    @Bean
    public DbSource<byte[]> beaconChainDB() {
        DbSettings settings = DbSettings.newInstance();
        return commonConfig.keyValueDataSource("beaconchain", settings);
    }

    @Bean
    public Publisher publisher() {
        Publisher publisher = new Publisher(eventDispatchThread);
        shardingWorldManager.setPublisher(publisher);
        return publisher;
    }

    @Bean
    public BeaconProposer beaconProposer() {
        return new BeaconProposerImpl(randao(), beaconStateRepository(), beaconStore(),
                BeaconChainFactory.stateTransition(publisher()),
                validatorConfig(), attestationPool());
    }

    @Bean
    public BeaconAttester beaconAttester() {
        return new BeaconAttesterImpl(beaconStateRepository(), beaconStore(), validatorConfig(), sign());
    }

    @Bean
    public AttestationPool attestationPool() {
        return new AttestationPoolImpl(beaconStore(), sign(), publisher());
    }

    @Bean
    public Randao randao() {
        DbSource<byte[]> src = commonConfig.keyValueDataSource("randao");
        WriteCache.BytesKey<byte[]> cache = new WriteCache.BytesKey<>(
                new BatchSourceWriter<>(src), WriteCache.CacheType.SIMPLE);
        cache.setFlushSource(true);

        return new Randao(cache);
    }

    private DbFlushManager beaconDbFlusher;
    public DbFlushManager beaconDbFlusher() {
        if (beaconDbFlusher != null)
            return beaconDbFlusher;

        beaconDbFlusher = new DbFlushManager(systemProperties, Collections.emptySet(), beaconChainDbCache());
        shardingWorldManager.setBeaconDbFlusher(beaconDbFlusher);
        return beaconDbFlusher;
    }

    public DepositAuthority depositAuthority() {
        return new UnsecuredDepositAuthority(validatorConfig());
    }

    public Sign sign() {
        return new DummySign();
    }

    public static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return SystemProperties.getDefault().processBeaconChain();
        }
    }
}
