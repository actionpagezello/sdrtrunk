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

package io.github.dsheirer.module.decode.smartnet;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.protocol.Protocol;
import java.util.Collections;
import java.util.List;

/**
 * AP-fork (SmartNet): one Motorola Type II outbound signalling word (OSW) - 16-bit address, group flag and 10-bit
 * command, after error correction and the CRC check.
 *
 * Command values up to 0x2F7 (and a few ranges above, see {@link SmartNetBandPlan}) are channel numbers: a group OSW
 * with a channel command is a voice grant or call update for the talkgroup in its address.  The low 4 bits of a
 * talkgroup address are status bits (bit 3 encrypted/digital, bits 2-0 options such as emergency or patch), so the
 * talkgroup itself is address & 0xFFF0 - Radio Reference lists MSP talkgroups this way (Troop A North 33168 = 0x8190).
 */
public class SmartNetOSW implements IMessage
{
    public static final int CMD_UNIT_ID = 0x308;        //first OSW of a two-OSW grant: calling unit / system ID
    public static final int CMD_IDLE = 0x2F8;
    public static final int CMD_GROUP_BUSY = 0x300;

    private final int mAddress;
    private final boolean mGroup;
    private final int mCommand;
    private final long mTimestamp;

    public SmartNetOSW(int address, boolean group, int command, long timestamp)
    {
        mAddress = address & 0xFFFF;
        mGroup = group;
        mCommand = command & 0x3FF;
        mTimestamp = timestamp;
    }

    public int getAddress()
    {
        return mAddress;
    }

    public boolean isGroup()
    {
        return mGroup;
    }

    public int getCommand()
    {
        return mCommand;
    }

    public long getTimestamp()
    {
        return mTimestamp;
    }

    /**
     * Talkgroup for a group address (status bits removed).
     */
    public int getTalkgroup()
    {
        return mAddress & 0xFFF0;
    }

    /**
     * Status bits of a group address.
     */
    public int getStatus()
    {
        return mAddress & 0xF;
    }

    public boolean isEncrypted()
    {
        return (mAddress & 0x8) != 0;
    }

    public boolean isEmergency()
    {
        int options = mAddress & 0x7;
        return options == 2 || options == 4 || options == 5;
    }

    @Override
    public boolean isValid()
    {
        return true; //only CRC-checked OSWs are created
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.SMARTNET;
    }

    @Override
    public int getTimeslot()
    {
        return 0;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.emptyList();
    }

    @Override
    public String toString()
    {
        return String.format("OSW ADDR:%04X(%d) %s CMD:%03X", mAddress, mAddress, mGroup ? "G" : "I", mCommand);
    }
}
