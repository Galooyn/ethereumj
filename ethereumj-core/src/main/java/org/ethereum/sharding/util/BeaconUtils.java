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
package org.ethereum.sharding.util;

import org.ethereum.sharding.domain.Beacon;
import org.ethereum.sharding.processing.consensus.BeaconConstants;
import org.ethereum.sharding.processing.state.Committee;
import org.ethereum.util.ByteUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.ethereum.crypto.HashUtil.blake2b;
import static org.ethereum.sharding.processing.consensus.BeaconConstants.SLOT_DURATION;


/**
 * @author Mikhail Kalinin
 * @since 25.09.2018
 */
public class BeaconUtils {

    /**
     * Shortcut to {@link #cycleStartSlot(long)}
     */
    public static long cycleStartSlot(Beacon block) {
        return cycleStartSlot(block.getSlotNumber());
    }

    /**
     * Returns start slot of a cycle that given slot number does belong to
     */
    public static long cycleStartSlot(long slotNumber) {
        return slotNumber - slotNumber % BeaconConstants.CYCLE_LENGTH;
    }

    /**
     * Scans committees array and returns committee index, {@link Committee.Index}.
     *
     * @param validatorIdx validator index taken from validator set
     * @param committees committees array
     *
     * @return valid committee index if validator is found, empty index otherwise
     */
    public static Committee.Index scanCommittees(int validatorIdx, Committee[][] committees) {
        for (int slotOffset = 0; slotOffset < committees.length; slotOffset++) {
            for (int committeeIdx = 0; committeeIdx < committees[slotOffset].length; committeeIdx++) {
                Committee committee = committees[slotOffset][committeeIdx];
                int[] validators = committee.getValidators();
                for (int idx = 0; idx < validators.length; idx++) {
                    if (validatorIdx == validators[idx]) {
                        return new Committee.Index(validatorIdx, committee.getShardId(), slotOffset,
                                committeeIdx, validators.length, idx);
                    }
                }
            }
        }

        return Committee.Index.EMPTY;
    }

    /**
     * A shortcut to {@link #scanCommittees(int, Committee[][])} that accepts a set of validator indices.
     */
    public static Set<Committee.Index> scanCommittees(Collection<Integer> validatorIndices, Committee[][] committees) {
        Set<Committee.Index> ret = new HashSet<>();
        for (int slotOffset = 0; slotOffset < committees.length; slotOffset++) {
            for (int committeeIdx = 0; committeeIdx < committees[slotOffset].length; committeeIdx++) {
                Committee committee = committees[slotOffset][committeeIdx];
                int[] validators = committee.getValidators();
                for (int idx = 0; idx < validators.length; idx++) {
                    if (validatorIndices.contains(validators[idx])) {
                        ret.add(new Committee.Index(validators[idx], committee.getShardId(), slotOffset,
                                committeeIdx, validators.length, idx));
                    }
                }
            }
        }

        return ret;
    }

    public static List<Committee.Index> scanCommittees(Committee[][] committees, int slotOffset, int shardId) {
        List<Committee.Index> ret = new ArrayList<>();
            for (int committeeIdx = 0; committeeIdx < committees[slotOffset].length; committeeIdx++) {
                Committee committee = committees[slotOffset][committeeIdx];
                int[] validators = committee.getValidators();
                for (int idx = 0; idx < validators.length; idx++) {
                    if (committee.getShardId() == shardId) {
                        ret.add(new Committee.Index(validators[idx], committee.getShardId(), slotOffset,
                                committeeIdx, validators.length, idx));
                    }
                }
            }

        return ret;
    }

    /**
     * Calculates the next slot that validator is assigned to
     *
     * @param currentSlot slot that current moment in time does land on
     * @param slotOffset offset of slot number, starting from the beginning of cycle
     *
     * @return number of slot in the next or current cycle
     */
    public static long calcNextAssignedSlot(long currentSlot, int slotOffset) {
        long slotNumberInCurrentCycle = cycleStartSlot(currentSlot) + slotOffset;
        if (currentSlot >= slotNumberInCurrentCycle) {
            return slotNumberInCurrentCycle + BeaconConstants.CYCLE_LENGTH;
        } else {
            return slotNumberInCurrentCycle;
        }
    }

    /**
     * Calculates a moment in time that specified slot does start from.
     *
     * @param slotNumber slot number
     * @param genesisTimestamp genesis timestamp
     * @return timestamp in milliseconds
     */
    public static long getSlotStartTime(long slotNumber, long genesisTimestamp) {
        return genesisTimestamp + slotNumber * SLOT_DURATION;
    }

    /**
     * Calculates a number of slot that given moment of time does fit into.
     * Uses {@code genesisTimestamp} and {@link BeaconConstants#SLOT_DURATION}
     *
     * @param timestamp timestamp in milliseconds
     * @param genesisTimestamp genesis timestamp
     * @return slot number
     */
    public static long getSlotNumber(long timestamp, long genesisTimestamp) {
        return (timestamp - genesisTimestamp) / SLOT_DURATION;
    }

    /**
     * Calculates a number of the slot that current moment in time does fit into.
     */
    public static long getCurrentSlotNumber(long genesisTimestamp) {
        return getSlotNumber(System.currentTimeMillis(), genesisTimestamp);
    }

    public static byte[] calcMessageHash(long slot, List<byte[]> parentHashes,
                                         int shardId, byte[] shardBlockHash, long justifiedSlot) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        try {
            message.write(ByteUtil.longToBytes(slot));
            for (byte[] parentHash : parentHashes) {
                message.write(parentHash);
            }
            message.write(ByteUtil.shortToBytes((short) shardId));
            message.write(shardBlockHash);
            message.write(ByteUtil.longToBytes(justifiedSlot));
        } catch (IOException ex) {
            throw new RuntimeException("Something goes wrong while creating message", ex);
        }

        return blake2b(message.toByteArray());
    }
}
