/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.preference;

import io.github.dsheirer.module.decode.event.ClearableHistoryModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.Preferences;

/**
 * Persists Now Playing panel settings: history slider values for the Events and
 * Messages tabs, and filter enabled/disabled states for the Events tab.
 */
public class NowPlayingPreference
{
    private static final Logger mLog = LoggerFactory.getLogger(NowPlayingPreference.class);
    private static final Preferences PREFS = Preferences.userNodeForPackage(NowPlayingPreference.class);

    // Keys
    private static final String KEY_EVENT_HISTORY_SIZE    = "now.playing.event.history.size";
    private static final String KEY_MESSAGE_HISTORY_SIZE  = "now.playing.message.history.size";

    // Filter keys — one per top-level filter name stored as a boolean (enabled/disabled).
    // We persist a map of "filter name → enabled" as individual preference entries.
    private static final String KEY_FILTER_PREFIX = "now.playing.event.filter.";

    // -------------------------------------------------------------------------
    // History sizes
    // -------------------------------------------------------------------------

    /**
     * Returns the persisted history size for the Events tab slider, or the
     * model default if nothing has been saved yet.
     */
    public int getEventHistorySize()
    {
        return PREFS.getInt(KEY_EVENT_HISTORY_SIZE, ClearableHistoryModel.DEFAULT_HISTORY_SIZE);
    }

    /**
     * Persists the history size for the Events tab slider.
     */
    public void setEventHistorySize(int size)
    {
        PREFS.putInt(KEY_EVENT_HISTORY_SIZE, size);
    }

    /**
     * Returns the persisted history size for the Messages tab slider, or the
     * model default if nothing has been saved yet.
     */
    public int getMessageHistorySize()
    {
        return PREFS.getInt(KEY_MESSAGE_HISTORY_SIZE, ClearableHistoryModel.DEFAULT_HISTORY_SIZE);
    }

    /**
     * Persists the history size for the Messages tab slider.
     */
    public void setMessageHistorySize(int size)
    {
        PREFS.putInt(KEY_MESSAGE_HISTORY_SIZE, size);
    }

    // -------------------------------------------------------------------------
    // Per-filter enabled state (keyed by filter name)
    // -------------------------------------------------------------------------

    /**
     * Returns the persisted enabled state for a named filter, or {@code true}
     * (enabled) if nothing has been saved yet.
     *
     * @param filterName unique name of the filter node (e.g. "Voice Call")
     */
    public boolean isFilterEnabled(String filterName)
    {
        return PREFS.getBoolean(filterKey(filterName), true);
    }

    /**
     * Persists the enabled state for a named filter.
     *
     * @param filterName unique name of the filter node
     * @param enabled    true to enable, false to disable
     */
    public void setFilterEnabled(String filterName, boolean enabled)
    {
        PREFS.putBoolean(filterKey(filterName), enabled);
    }

    /**
     * Builds a safe preference key from the filter name.
     */
    private static String filterKey(String filterName)
    {
        // Replace characters not valid in Preferences keys
        return KEY_FILTER_PREFIX + filterName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
