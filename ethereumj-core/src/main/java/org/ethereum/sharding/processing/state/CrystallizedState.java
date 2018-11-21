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
package org.ethereum.sharding.processing.state;

import org.ethereum.datasource.Serializer;
import org.ethereum.sharding.processing.db.ValidatorSet;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPList;

import static org.ethereum.crypto.HashUtil.blake2b;
import static org.ethereum.util.ByteUtil.ZERO_BYTE_ARRAY;
import static org.ethereum.util.ByteUtil.byteArrayToLong;
import static org.ethereum.util.ByteUtil.isSingleZero;
import static org.ethereum.util.ByteUtil.longToBytesNoLeadZeroes;

/**
 * @author Mikhail Kalinin
 * @since 06.09.2018
 */
public class CrystallizedState {

    /* Slot number that the state was calculated at */
    private final long lastStateRecalc;
    /* Current validator state */
    private final ValidatorState validatorState;
    /* Current finality state */
    private final Finality finality;
    /* The most recent crosslinks for each shard */
    private final Crosslink[] crosslinks;

    public CrystallizedState(long lastStateRecalc, ValidatorState validatorState, Finality finality, Crosslink[] crosslinks) {
        this.lastStateRecalc = lastStateRecalc;
        this.validatorState = validatorState;
        this.finality = finality;
        this.crosslinks = crosslinks;
    }

    public Flattened flatten() {
        return new Flattened(validatorState.getValidatorSet().getHash(), lastStateRecalc, validatorState.getCommittees(),
                finality.getLastJustifiedSlot(), finality.getJustifiedStreak(), finality.getLastFinalizedSlot(),
                crosslinks, validatorState.getNextShufflingSeed(), validatorState.getValidatorSetChangeSlot());
    }

    public byte[] getHash() {
        return flatten().getHash();
    }

    public long getLastStateRecalc() {
        return lastStateRecalc;
    }

    public ValidatorState getValidatorState() {
        return validatorState;
    }

    public Finality getFinality() {
        return finality;
    }

    public Crosslink[] getCrosslinks() {
        return crosslinks;
    }

    public CrystallizedState withValidatorState(ValidatorState validatorState) {
        return new CrystallizedState(lastStateRecalc, validatorState, finality, crosslinks);
    }

    public CrystallizedState withLastStateRecalc(long lastStateRecalc) {
        return new CrystallizedState(lastStateRecalc, validatorState, finality, crosslinks);
    }

    public CrystallizedState withLastStateRecalcIncrement(long addition) {
        return new CrystallizedState(lastStateRecalc + addition, validatorState, finality, crosslinks);
    }

    public CrystallizedState withCrosslinks(Crosslink[] crosslinks) {
        return new CrystallizedState(lastStateRecalc, validatorState, finality, crosslinks);
    }

    public CrystallizedState withFinality(Finality finality) {
        return new CrystallizedState(lastStateRecalc, validatorState, finality, crosslinks);
    }

    public static class Flattened {
        /* Hash of the validator set */
        private final byte[] validatorSetHash;
        /* Slot number that the state was calculated at */
        private final long lastStateRecalc;
        /** Committee members and their assigned shard, per slot */
        private final Committee[][] committees;
        /* The last justified slot */
        private final long lastJustifiedSlot;
        /* Number of consecutive justified slots ending at this one */
        private final long justifiedStreak;
        /* The last finalized slot */
        private final long lastFinalizedSlot;
        /* The most recent crosslinks for each shard */
        private final Crosslink[] crosslinks;
        /* Randao seed used that will be used for next shuffling */
        private final byte[] nextShufflingSeed;
        /* Slot of last validator set change */
        private final long validatorSetChangeSlot;

        public Flattened(byte[] validatorSetHash, long lastStateRecalc, Committee[][] committees, long lastJustifiedSlot,
                         long justifiedStreak, long lastFinalizedSlot, Crosslink[] crosslinks,
                         byte[] nextShufflingSeed, long validatorSetChangeSlot) {
            this.validatorSetHash = validatorSetHash;
            this.lastStateRecalc = lastStateRecalc;
            this.committees = committees;
            this.lastJustifiedSlot = lastJustifiedSlot;
            this.justifiedStreak = justifiedStreak;
            this.lastFinalizedSlot = lastFinalizedSlot;
            this.crosslinks = crosslinks;
            this.nextShufflingSeed = nextShufflingSeed;
            this.validatorSetChangeSlot = validatorSetChangeSlot;
        }

        public Flattened(byte[] encoded) {
            RLPList list = RLP.unwrapList(encoded);

            this.validatorSetHash = list.get(0).getRLPData();
            this.lastStateRecalc = byteArrayToLong(list.get(1).getRLPData());
            this.lastJustifiedSlot = byteArrayToLong(list.get(2).getRLPData());
            this.justifiedStreak = byteArrayToLong(list.get(3).getRLPData());
            this.lastFinalizedSlot = byteArrayToLong(list.get(4).getRLPData());
            this.validatorSetChangeSlot = byteArrayToLong(list.get(5).getRLPData());
            this.nextShufflingSeed = list.get(6).getRLPData();

            if (!isSingleZero(list.get(7).getRLPData())) {
                RLPList committeeList = RLP.unwrapList(list.get(7).getRLPData());
                this.committees = new Committee[committeeList.size()][];
                for (int i = 0; i < committeeList.size(); i++) {
                    if (!isSingleZero(committeeList.get(i).getRLPData())) {
                        RLPList slotList = RLP.unwrapList(committeeList.get(i).getRLPData());
                        Committee[] slotCommittees = new Committee[slotList.size()];
                        for (int j = 0; j < slotList.size(); j++) {
                            slotCommittees[j] = new Committee(slotList.get(j).getRLPData());
                        }
                        this.committees[i] = slotCommittees;
                    } else {
                        this.committees[i] = new Committee[0];
                    }
                }
            } else {
                this.committees = new Committee[0][];
            }

            if (!isSingleZero(list.get(8).getRLPData())) {
                RLPList crosslinkList = RLP.unwrapList(list.get(8).getRLPData());
                this.crosslinks = new Crosslink[crosslinkList.size()];
                for (int i = 0; i < crosslinkList.size(); i++)
                    this.crosslinks[i] = new Crosslink(crosslinkList.get(i).getRLPData());
            } else {
                this.crosslinks = new Crosslink[0];
            }
        }

        public byte[] encode() {
            byte[][] encodedCommittees = new byte[committees.length][];
            byte[][] encodedCrosslinks = new byte[crosslinks.length][];

            if (committees.length > 0) {
                for (int i = 0; i < committees.length; i++) {
                    Committee[] slotCommittees = committees[i];
                    byte[][] encodedSlot = new byte[slotCommittees.length][];
                    for (int j = 0; j < slotCommittees.length; j++) {
                        encodedSlot[j] = slotCommittees[j].getEncoded();
                    }
                    encodedCommittees[i] = slotCommittees.length > 0 ? RLP.wrapList(encodedSlot) : ZERO_BYTE_ARRAY;
                }
            }

            if (crosslinks.length > 0) {
                for (int i = 0; i < crosslinks.length; i++)
                    encodedCrosslinks[i] = crosslinks[i].getEncoded();
            }

            return RLP.wrapList(validatorSetHash, longToBytesNoLeadZeroes(lastStateRecalc),
                    longToBytesNoLeadZeroes(lastJustifiedSlot), longToBytesNoLeadZeroes(justifiedStreak),
                    longToBytesNoLeadZeroes(lastFinalizedSlot),
                    longToBytesNoLeadZeroes(validatorSetChangeSlot), nextShufflingSeed,
                    encodedCommittees.length > 0 ? RLP.wrapList(encodedCommittees) : ZERO_BYTE_ARRAY,
                    encodedCrosslinks.length > 0 ? RLP.wrapList(encodedCrosslinks) : ZERO_BYTE_ARRAY);
        }

        public byte[] getHash() {
            return blake2b(encode());
        }

        public byte[] getValidatorSetHash() {
            return validatorSetHash;
        }

        public long getLastStateRecalc() {
            return lastStateRecalc;
        }

        public Committee[][] getCommittees() {
            return committees;
        }

        public long getLastJustifiedSlot() {
            return lastJustifiedSlot;
        }

        public long getJustifiedStreak() {
            return justifiedStreak;
        }

        public long getLastFinalizedSlot() {
            return lastFinalizedSlot;
        }

        public Crosslink[] getCrosslinks() {
            return crosslinks;
        }

        public byte[] getNextShufflingSeed() {
            return nextShufflingSeed;
        }

        public long getValidatorSetChangeSlot() {
            return validatorSetChangeSlot;
        }

        public static org.ethereum.datasource.Serializer<Flattened, byte[]> getSerializer() {
            return Serializer;
        }

        public static Flattened empty() {
            return new Flattened(ValidatorSet.EMPTY_HASH, 0, new Committee[0][], 0L, 0L, 0L,
                    new Crosslink[0], new byte[32], 0L);
        }

        public static final org.ethereum.datasource.Serializer<Flattened, byte[]> Serializer = new Serializer<Flattened, byte[]>() {
            @Override
            public byte[] serialize(Flattened state) {
                return state == null ? null : state.encode();
            }

            @Override
            public Flattened deserialize(byte[] stream) {
                return stream == null ? null : new Flattened(stream);
            }
        };
    }
}
