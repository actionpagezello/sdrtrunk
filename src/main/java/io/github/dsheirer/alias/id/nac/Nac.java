/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.alias.id.nac;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;

/**
 * P25 Network Access Code (NAC) identifier for alias matching.
 *
 * NAC is a 12-bit value (0-4095 / 0x000-0xFFF) transmitted in the NID of every P25 Phase 1 message.
 */
public class Nac extends AliasID implements Comparable<Nac>
{
    private int mNacValue;

    /**
     * Constructs an instance
     */
    public Nac()
    {
    }

    @Override
    public AliasIDType getType()
    {
        return AliasIDType.NAC;
    }

    @Override
    public boolean matches(AliasID id)
    {
        if(isValid() && id instanceof Nac other)
        {
            return other.isValid() && getNacValue() == other.getNacValue();
        }

        return false;
    }

    /**
     * NAC value (0-4095)
     * @return NAC integer value
     */
    @JacksonXmlProperty(isAttribute = true, localName = "value")
    public int getNacValue()
    {
        return mNacValue;
    }

    /**
     * Sets the NAC value.
     * @param nacValue to set (0-4095)
     */
    public void setNacValue(int nacValue)
    {
        mNacValue = nacValue;
        updateValueProperty();
    }

    @Override
    public boolean isValid()
    {
        return mNacValue > 0 && mNacValue <= 0xFFF;
    }

    @Override
    public boolean isAudioIdentifier()
    {
        return false;
    }

    @Override
    public String toString()
    {
        if(isValid())
        {
            return mNacValue + "/x" + String.format("%03X", mNacValue);
        }

        return "NAC-Invalid - No Value Selected";
    }

    @Override
    public int compareTo(Nac o)
    {
        if(isValid())
        {
            if(o.isValid())
            {
                return Integer.compare(getNacValue(), o.getNacValue());
            }
            else
            {
                return 1;
            }
        }

        return -1;
    }
}
