/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.edacs;

/**
 * AP-fork (EDACS): one EDACS Extended Addressing outbound control channel message - 28 data bits after the BCH check.
 *
 * Field layout worked out from MBTA (Extended Addressing, ProVoice) on 2026-11-09 and checked against the voice
 * channels: a group voice grant names the logical channel (LCN) and talkgroup, and is followed by a message carrying
 * the calling unit.  LCN numbering matches the Radio Reference channel list (LCN 2 = 851.225 ... LCN 14 = 851.9625).
 * <pre>
 *   bits 27-23  message type 1 (MT1)
 *   MT1 = 3, bits 27-16 != 0x1A0:  group voice grant   - LCN bits 21-17, flags bits 16-15?, talkgroup bits 15-0
 *   MT1 = 3, bits 27-16 == 0x1A0:  calling unit        - unit (LID) bits 19-0
 *   bits 27-20 == 0xFC:            idle / filler
 *   bits 27-20 == 0xFD:            system / site information
 * </pre>
 * Anything else is kept as OTHER with its raw value so later work can name it.
 */
public class EDACSMessage
{
    public enum Type {GROUP_VOICE_GRANT, CALLING_UNIT, IDLE, SYSTEM_INFORMATION, OTHER}

    private final int mData;
    private final long mTimestamp;
    private final boolean mCorrected;

    /**
     * Constructs an instance
     * @param data 28 data bits
     * @param timestamp of the message
     * @param corrected true when the three copies disagreed and the majority vote was needed
     */
    public EDACSMessage(int data, long timestamp, boolean corrected)
    {
        mData = data & 0xFFFFFFF;
        mTimestamp = timestamp;
        mCorrected = corrected;
    }

    public int getData()
    {
        return mData;
    }

    public long getTimestamp()
    {
        return mTimestamp;
    }

    public boolean isCorrected()
    {
        return mCorrected;
    }

    public int getMT1()
    {
        return (mData >> 23) & 0x1F;
    }

    public Type getType()
    {
        int top8 = (mData >> 20) & 0xFF;

        if(top8 == 0xFC)
        {
            return Type.IDLE;
        }
        if(top8 == 0xFD)
        {
            return Type.SYSTEM_INFORMATION;
        }
        if(getMT1() == 3)
        {
            return ((mData >> 16) & 0xFFF) == 0x1A0 ? Type.CALLING_UNIT : Type.GROUP_VOICE_GRANT;
        }
        return Type.OTHER;
    }

    /**
     * Logical channel number of a group voice grant.
     */
    public int getLCN()
    {
        return (mData >> 17) & 0x1F;
    }

    /**
     * Talkgroup of a group voice grant.
     */
    public int getTalkgroup()
    {
        return mData & 0xFFFF;
    }

    /**
     * Unit (logical ID) of a calling unit message.
     */
    public int getUnit()
    {
        return mData & 0xFFFFF;
    }

    @Override
    public String toString()
    {
        return switch(getType())
        {
            case GROUP_VOICE_GRANT -> "EDACS EA GROUP GRANT LCN:" + getLCN() + " TG:" + getTalkgroup();
            case CALLING_UNIT -> "EDACS EA CALLING UNIT:" + getUnit();
            case IDLE -> "EDACS IDLE";
            case SYSTEM_INFORMATION -> "EDACS SYSTEM " + String.format("%07X", mData);
            case OTHER -> "EDACS MSG " + String.format("%07X", mData);
        };
    }
}
