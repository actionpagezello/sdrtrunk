/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/package io.github.dsheirer.module.decode.nbfm;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * AP-fork (ap-15.9.26): one CTCSS or DCS squelch code in upstream SDRTrunk's playlist form
 * (&lt;squelchDecoder squelchType="CTCSS" value="..."/&gt;, upstream #2425).  The fork's own tone filter is written in this
 * form as well, so upstream applies the same tone squelch when it opens a fork playlist, and a playlist made by
 * upstream brings its tone squelch with it.  Plain strings, so neither program's enum names constrain the other.
 */
public class SquelchDecoderEntry
{
    private String mSquelchType = "";
    private String mValue = "";

    public SquelchDecoderEntry()
    {
    }

    public SquelchDecoderEntry(String squelchType, String value)
    {
        mSquelchType = squelchType;
        mValue = value;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "squelchType")
    public String getSquelchType()
    {
        return mSquelchType;
    }

    public void setSquelchType(String squelchType)
    {
        mSquelchType = squelchType != null ? squelchType : "";
    }

    @JacksonXmlProperty(isAttribute = true, localName = "value")
    public String getValue()
    {
        return mValue;
    }

    public void setValue(String value)
    {
        mValue = value != null ? value : "";
    }
}
