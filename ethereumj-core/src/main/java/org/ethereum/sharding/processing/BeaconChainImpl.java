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
package org.ethereum.sharding.processing;

import org.ethereum.core.Block;
import org.ethereum.db.DbFlushManager;
import org.ethereum.sharding.processing.consensus.InitialTransition;
import org.ethereum.sharding.pubsub.Event;
import org.ethereum.sharding.pubsub.Publisher;
import org.ethereum.sharding.processing.consensus.ScoreFunction;
import org.ethereum.sharding.processing.consensus.StateTransition;
import org.ethereum.sharding.processing.state.BeaconState;
import org.ethereum.sharding.processing.state.StateRepository;
import org.ethereum.sharding.processing.validation.BeaconValidator;
import org.ethereum.sharding.processing.validation.StateValidator;
import org.ethereum.sharding.processing.db.BeaconStore;
import org.ethereum.sharding.domain.Beacon;
import org.ethereum.sharding.processing.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigInteger;

import static org.ethereum.sharding.pubsub.Events.onBeaconBlock;
import static org.ethereum.sharding.pubsub.Events.onBeaconChainLoaded;
import static org.ethereum.sharding.pubsub.Events.onBeaconChainSynced;

/**
 * This is default and likely the only implementation of {@link BeaconChain}
 *
 * @author Mikhail Kalinin
 * @since 14.08.2018
 */
public class BeaconChainImpl implements BeaconChain {

    private static final Logger logger = LoggerFactory.getLogger("beacon");

    DbFlushManager beaconDbFlusher;
    BeaconStore store;

    StateTransition<BeaconState> transitionFunction;
    StateTransition<BeaconState> initialStateTransition;
    BeaconValidator beaconValidator;
    StateValidator stateValidator;
    StateRepository repository;
    ScoreFunction scoreFunction;

    Publisher publisher;

    private ScoredChainHead canonicalHead;

    public BeaconChainImpl(DbFlushManager beaconDbFlusher, BeaconStore store,
                           StateTransition<BeaconState> transitionFunction, StateRepository repository,
                           BeaconValidator beaconValidator, StateValidator stateValidator,
                           ScoreFunction scoreFunction, StateTransition<BeaconState> initialStateTransition) {
        this.beaconDbFlusher = beaconDbFlusher;
        this.store = store;
        this.transitionFunction = transitionFunction;
        this.repository = repository;
        this.beaconValidator = beaconValidator;
        this.stateValidator = stateValidator;
        this.scoreFunction = scoreFunction;
        this.initialStateTransition = initialStateTransition;
    }

    @Override
    public void init() {
        if (store.getCanonicalHead() == null) {
            canonicalHead = initialChainHead();
        } else {
            canonicalHead = new ScoredChainHead(store.getCanonicalHead(), store.getCanonicalHeadScore(),
                    repository.get(store.getCanonicalHead().getStateHash()));
        }

        publish(onBeaconChainLoaded(canonicalHead.block, canonicalHead.state));
        publish(onBeaconChainSynced(canonicalHead.block, canonicalHead.state));

        logger.info("Chain loaded with head: {}", canonicalHead.block);
    }

    ScoredChainHead initialChainHead() {
        return new ScoredChainHead(Beacon.GENESIS, BigInteger.ZERO, initialState());
    }

    BeaconState initialState() {
        return initialStateTransition.applyBlock(Beacon.GENESIS, repository.getEmpty());
    }

    @Override
    public Beacon getCanonicalHead() {
        return canonicalHead.block;
    }

    @Override
    public synchronized ProcessingResult insert(Beacon block) {
        ValidationResult vRes;
        if ((vRes = beaconValidator.validateAndLog(block)) != ValidationResult.Success)
            return ProcessingResult.fromValidation(vRes);

        // apply state transition
        Beacon parent = pullParent(block);
        BeaconState currentState = pullState(parent);
        BeaconState newState = transitionFunction.applyBlock(block, currentState);

        if ((vRes = stateValidator.validateAndLog(block, newState)) != ValidationResult.Success)
            return ProcessingResult.fromValidation(vRes);

        // calculate chain score
        BigInteger chainScore = scoreFunction.apply(block, newState);

        ScoredChainHead newHead = new ScoredChainHead(block, chainScore, newState);

        beaconDbFlusher.commit(() -> {

            // save state
            repository.insert(newHead.state);
            repository.commit();

            // store block
            store.save(newHead.block, newHead.score, canonicalHead.isParentOf(newHead));

            // do reorg if canonical chain is beaten
            if (canonicalHead.shouldReorgTo(newHead)) {
                store.reorgTo(newHead.block);
            }

            // update head
            if (canonicalHead.isParentOf(newHead) || canonicalHead.shouldReorgTo(newHead)) {
                canonicalHead = newHead;
            }
        });

        ProcessingResult res = canonicalHead.equals(newHead) ? ProcessingResult.Best : ProcessingResult.NotBest;

        // publish beacon block event
        publish(onBeaconBlock(block, newState, res == ProcessingResult.Best));

        logger.info("Process block {}, status: {}", block.toString(), res);

        return res;
    }

    @Override
    public void setBestBlock(Block block) {
        ((InitialTransition) initialStateTransition).withMainChainRef(block.getHash());
    }

    private Beacon pullParent(Beacon block) {
        if (block.isParentEmpty())
            return Beacon.GENESIS;

        if (canonicalHead.block.isParentOf(block))
            return canonicalHead.block;

        return store.getByHash(block.getParentHash());
    }

    private BeaconState pullState(Beacon block) {
        if (block.isGenesis())
            return initialState();

        if (canonicalHead.block.equals(block))
            return canonicalHead.state;

        return repository.get(block.getStateHash());
    }

    void publish(Event e) {
        if (publisher != null)
            publisher.publish(e);
    }

    @Autowired
    public void setPublisher(Publisher publisher) {
        this.publisher = publisher;
    }
}
