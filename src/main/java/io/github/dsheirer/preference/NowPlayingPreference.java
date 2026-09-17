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

    // Muted channel keys — one per channel identity stored as a boolean.  Stored as individual
    // entries rather than a single delimited value so the number of muted channels is not bounded
    // by Preferences.MAX_VALUE_LENGTH.
    private static final String KEY_MUTED_CHANNEL_PREFIX = "now.playing.channel.muted.";

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

    // -------------------------------------------------------------------------
    // Per-channel mute state (keyed by channel identity)
    // -------------------------------------------------------------------------

    /**
     * Indicates if the channel identified by the supplied key is muted.
     *
     * @param channelKey stable identity for the channel
     * @return true if the channel has been muted, false if it has not (the default)
     */
    public boolean isChannelMuted(String channelKey)
    {
        return PREFS.getBoolean(mutedChannelKey(channelKey), false);
    }

    /**
     * Persists the mute state for the channel identified by the supplied key.
     *
     * Unmuting removes the entry rather than storing false, so the preference node does not
     * accumulate one dead entry for every channel that has ever been muted and unmuted.
     *
     * @param channelKey stable identity for the channel
     * @param muted true to mute
     */
    public void setChannelMuted(String channelKey, boolean muted)
    {
        String key = mutedChannelKey(channelKey);

        if(muted)
        {
            PREFS.putBoolean(key, true);
        }
        else
        {
            PREFS.remove(key);
        }
    }

    /**
     * Builds a safe preference key from a channel identity.
     *
     * Preferences keys are limited to {@link Preferences#MAX_KEY_LENGTH} characters, which channel
     * identities built from system, site and name can exceed.  Over-long keys are truncated with a
     * hash of the full identity appended, so two channels whose identities share a long prefix
     * cannot collapse onto the same key.
     */
    private static String mutedChannelKey(String channelKey)
    {
        String sanitized = channelKey.replaceAll("[^a-zA-Z0-9._\\-]", "_");

        //Reserve 9 characters for a separator plus up to 8 hex digits of hash
        int budget = Preferences.MAX_KEY_LENGTH - KEY_MUTED_CHANNEL_PREFIX.length() - 9;

        if(sanitized.length() > budget)
        {
            sanitized = sanitized.substring(0, budget) + "_" + Integer.toHexString(channelKey.hashCode());
        }

        return KEY_MUTED_CHANNEL_PREFIX + sanitized;
    }
}
