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
package io.github.dsheirer.module.decode.smartnet;

import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.message.IMessage;

import java.util.function.Function;

/**
 * Message filter for SmartNet messages in the message activity display.
 */
public class SmartNetMessageFilter extends Filter<IMessage, SmartNetMessageType>
{
    public SmartNetMessageFilter()
    {
        super("SmartNet Messages");

        for(SmartNetMessageType type : SmartNetMessageType.values())
        {
            add(new FilterElement<>(type));
        }
    }

    @Override
    public Function<IMessage, SmartNetMessageType> getKeyExtractor()
    {
        return message -> {
            if(message instanceof SmartNetMessage smartNetMessage)
            {
                return smartNetMessage.getMessageType();
            }
            return null;
        };
    }

    @Override
    public boolean canProcess(IMessage message)
    {
        return message instanceof SmartNetMessage;
    }
}