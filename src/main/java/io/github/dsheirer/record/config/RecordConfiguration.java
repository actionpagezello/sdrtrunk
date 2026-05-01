/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.record.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.controller.config.Configuration;
import io.github.dsheirer.record.RecorderType;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains the types of recordings specified for a channel
 */
public class RecordConfiguration extends Configuration
{
    public static final float DEFAULT_ACTIVITY_SQUELCH_THRESHOLD = -70.0f;

    /**
     * Recording types requested for this configuration
     */
    private List<RecorderType> mRecorders = new ArrayList<>();
    private boolean mActivityTriggeredRecording = false;
    private float mActivitySquelchThreshold = DEFAULT_ACTIVITY_SQUELCH_THRESHOLD;

    /**
     * Constructs a recording configuration instance
     */
    public RecordConfiguration()
    {
        //Empty constructor required for deserialization
    }

    /**
     * List of recorder types specified in this configuration
     */
    @JacksonXmlProperty(isAttribute = false, localName = "recorder")
    public List<RecorderType> getRecorders()
    {
        return mRecorders;
    }

    /**
     * Sets the (complete) list of recorder types for this configuration, erasing any existing recording types.
     */
    public void setRecorders(List<RecorderType> recorders)
    {
        mRecorders = recorders;
    }

    /**
     * Adds the recorder type to the configuration
     */
    public void addRecorder(RecorderType recorder)
    {
        mRecorders.add(recorder);
    }

    /**
     * Clears all recorder types from this configuration
     */
    public void clearRecorders()
    {
        mRecorders.clear();
    }

    /**
     * Indicates if this configuration has the specified recorder type
     * @param recorderType to check
     * @return true if this configuration contains the specified recorder type
     */
    public boolean contains(RecorderType recorderType)
    {
        return mRecorders.contains(recorderType);
    }

    /**
     * Indicates if activity-triggered baseband recording is enabled.
     * @return true if enabled
     */
    @JacksonXmlProperty(isAttribute = true, localName = "activityTriggeredRecording")
    public boolean isActivityTriggeredRecording()
    {
        return mActivityTriggeredRecording;
    }

    /**
     * Sets activity-triggered baseband recording enabled state.
     * @param enabled true to enable
     */
    public void setActivityTriggeredRecording(boolean enabled)
    {
        mActivityTriggeredRecording = enabled;
    }

    /**
     * Gets the squelch threshold in dB for activity-triggered recording.
     * @return threshold in dB (default -70.0)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "activitySquelchThreshold")
    public float getActivitySquelchThreshold()
    {
        return mActivitySquelchThreshold;
    }

    /**
     * Sets the squelch threshold in dB for activity-triggered recording.
     * @param threshold in dB
     */
    public void setActivitySquelchThreshold(float threshold)
    {
        mActivitySquelchThreshold = Math.max(-100.0f, Math.min(-30.0f, threshold));
    }

    /**
     * Indicates if activity-triggered recording has a non-default squelch threshold.
     */
    @JsonIgnore
    public boolean hasCustomSquelchThreshold()
    {
        return mActivitySquelchThreshold != DEFAULT_ACTIVITY_SQUELCH_THRESHOLD;
    }
}
