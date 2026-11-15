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

package io.github.dsheirer.module.decode.ctcss;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.ctcss.CTCSSIdentifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.protocol.Protocol;
import java.util.Collections;
import java.util.List;

/**
 * Continuous Tone-Coded Squelch System (CTCSS) tone detected message
 */
public class CTCSSMessage extends Message
{
    private final CTCSSCode mCTCSSCode;

    /**
     * Constructs an instance
     * @param code that was detected
     * @param timestamp when the code was detected
     */
    public CTCSSMessage(CTCSSCode code, long timestamp)
    {
        super(timestamp);
        mCTCSSCode = code;
    }

    @Override
    public String toString()
    {
        return "Continuous Tone-Coded Squelch (CTCSS) Detected: " + mCTCSSCode.getDisplayString();
    }

    /**
     * The CTCSS code that was detected.
     * @return code
     */
    public CTCSSCode getCTCSSCode()
    {
        return mCTCSSCode;
    }

    @Override
    public boolean isValid()
    {
        return true;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.CTCSS;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return Collections.singletonList(new CTCSSIdentifier(mCTCSSCode));
    }
}
