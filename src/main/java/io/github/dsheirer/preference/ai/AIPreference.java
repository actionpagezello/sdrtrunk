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

package io.github.dsheirer.preference.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;

@JsonRootName("aiPreference")
public class AIPreference
{
    private Listener<PreferenceType> mListener;
    private boolean mEnabled = false;
    private String mApiKey = "";

    public AIPreference()
    {
    }

    public AIPreference(Listener<PreferenceType> listener)
    {
        mListener = listener;
    }

    @JsonIgnore
    public void setListener(Listener<PreferenceType> listener)
    {
        mListener = listener;
    }

    @JsonProperty("enabled")
    public boolean isEnabled()
    {
        return mEnabled;
    }

    @JsonProperty("enabled")
    public void setEnabled(boolean enabled)
    {
        mEnabled = enabled;

        if (mListener != null) {
            mListener.receive(PreferenceType.AI_SETTINGS);
        }
    }

    @JsonProperty("apiKey")
    public String getApiKey()
    {
        return mApiKey;
    }

    @JsonProperty("apiKey")
    public void setApiKey(String apiKey)
    {
        mApiKey = apiKey;

        if (mListener != null) {
            mListener.receive(PreferenceType.AI_SETTINGS);
        }
    }
}
