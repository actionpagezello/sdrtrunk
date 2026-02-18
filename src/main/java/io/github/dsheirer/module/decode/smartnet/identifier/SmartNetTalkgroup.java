/*
 * *****************************************************************************
 * Copyright (C) 2024 SDR Trunk Contributors
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
package io.github.dsheirer.module.decode.smartnet.identifier;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * SmartNet talkgroup identifier.
 *
 * SmartNet talkgroup addresses include 4 status bits in the lower nibble.
 * The actual talkgroup number is the upper 12 bits (address & 0xFFF0).
 * RadioReference displays both DEC (full 16-bit value) and HEX (upper 12 bits >> 4).
 */
public class SmartNetTalkgroup extends TalkgroupIdentifier
{
    /**
     * Constructs a SmartNet talkgroup identifier.
     *
     * @param talkgroup the raw talkgroup value including status bits
     */
    public SmartNetTalkgroup(int talkgroup)
    {
        super(talkgroup, Role.TO);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.SMARTNET;
    }

    /**
     * Creates a SmartNet talkgroup identifier.
     *
     * @param talkgroup the raw talkgroup value including status bits
     */
    public static SmartNetTalkgroup create(int talkgroup)
    {
        return new SmartNetTalkgroup(talkgroup);
    }
}
