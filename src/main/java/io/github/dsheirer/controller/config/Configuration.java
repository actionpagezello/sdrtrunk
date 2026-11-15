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
 ******************************************************************************/
package io.github.dsheirer.controller.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.LinkedHashMap;
import java.util.Map;

@JacksonXmlRootElement(localName = "configuration")
public abstract class Configuration
{
    /**
     * AP-fork (ap-15.9.26): playlist settings this version does not recognise - written by upstream SDRTrunk or a newer
     * fork - kept as read and written back on save, so opening another program's playlist here never drops its
     * settings.
     */
    private final Map<String,Object> mUnrecognisedSettings = new LinkedHashMap<>();

    public Configuration()
    {
    }

    @JsonAnySetter
    public void setUnrecognisedSetting(String name, Object value)
    {
        mUnrecognisedSettings.put(name, value);
    }

    @JsonAnyGetter
    public Map<String,Object> getUnrecognisedSettings()
    {
        return mUnrecognisedSettings;
    }
}
