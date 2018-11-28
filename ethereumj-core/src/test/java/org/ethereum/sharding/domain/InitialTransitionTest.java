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
package org.ethereum.sharding.domain;

import org.ethereum.crypto.HashUtil;
import org.ethereum.datasource.inmem.HashMapDB;
import org.ethereum.sharding.processing.consensus.InitialTransition;
import org.ethereum.sharding.processing.db.ValidatorSet;
import org.ethereum.sharding.processing.state.BeaconState;
import org.ethereum.sharding.processing.state.BeaconStateRepository;
import org.ethereum.sharding.processing.state.Committee;
import org.ethereum.sharding.processing.state.StateRepository;
import org.ethereum.sharding.registration.ValidatorRepository;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.ethereum.crypto.HashUtil.randomHash;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Mikhail Kalinin
 * @since 05.09.2018
 */
public class InitialTransitionTest {

    @Test
    public void testInitialValidatorSet() {

        Validator v1 = getRandomValidator();
        Validator v2 = getRandomValidator();
        Validator v3 = getRandomValidator();
        Validator v4 = getRandomValidator();

        StateRepository stateRepository = new BeaconStateRepository(new HashMapDB<>(), new HashMapDB<>(),
                new HashMapDB<>());
        ValidatorRepository validatorRepository = new PredefinedValidatorRepository(v1, v2, v3, v4);

        InitialTransition transition = new InitialTransition(validatorRepository);
        BeaconState newState = transition.applyBlock(Beacon.GENESIS, stateRepository.getEmpty());

        checkValidatorSet(newState.getValidatorSet(), v1, v2, v3, v4);

        // check committees
        int cnt = 0;
        for (Committee[] slot : newState.getCommittees()) {
            if (slot[0].getValidators().length > 0) {
                cnt += 1;
                assertEquals(0L, slot[0].getShardId());
                assertTrue(slot[0].getValidators()[0] >= 0 && slot[0].getValidators()[0] <= 3);
            }
        }
        assertEquals(cnt, 4);
    }

    static class PredefinedValidatorRepository implements ValidatorRepository {

        List<Validator> validators = new ArrayList<>();

        public PredefinedValidatorRepository(Validator... validators) {
            this.validators = Arrays.asList(validators);
        }

        @Override
        public List<Validator> query(byte[] fromBlock, byte[] toBlock) {
            return validators;
        }

        @Override
        public List<Validator> query(byte[] toBlock) {
            return validators;
        }
    }

    void checkValidatorSet(ValidatorSet set, Validator... validators) {
        assertEquals(validators.length, set.size());
        for (int i = 0; i < validators.length; i++) {
            assertValidatorEquals(validators[i], set.get(i));
        }
    }

    void assertValidatorEquals(Validator expected, Validator actual) {
        assertArrayEquals(expected.getPubKey(), actual.getPubKey());
        assertArrayEquals(expected.getWithdrawalAddress(), actual.getWithdrawalAddress());
        assertEquals(expected.getWithdrawalShard(), actual.getWithdrawalShard());
        assertArrayEquals(expected.getRandao(), actual.getRandao());
    }

    Validator getRandomValidator() {
        long shardId = new Random().nextInt();
        shardId = (shardId < 0 ? (-shardId) : shardId) % 1024;
        return new Validator(randomHash(), shardId,
                HashUtil.sha3omit12(randomHash()), randomHash());
    }
}
