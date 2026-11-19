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
package io.github.dsheirer.preference;

import java.util.prefs.Preferences;

/**
 * Stores the Now Playing mute state of channels that have no alias to carry it.  A channel with an alias is muted
 * through that alias's Listen (playback priority) setting instead, which is saved in the playlist.
 */
public class ChannelMutePreference
{
    private static final Preferences PREFS = Preferences.userNodeForPackage(ChannelMutePreference.class);

    /**
     * One boolean entry per muted channel, rather than one delimited value, so the number of muted channels is not
     * bounded by Preferences.MAX_VALUE_LENGTH.
     */
    private static final String KEY_MUTED_CHANNEL_PREFIX = "now.playing.channel.muted.";

    /**
     * Indicates if the channel identified by the key is muted.
     * @param channelKey stable identity for the channel
     * @return true if muted, false (the default) if not
     */
    public boolean isChannelMuted(String channelKey)
    {
        return PREFS.getBoolean(mutedChannelKey(channelKey), false);
    }

    /**
     * Stores the mute state for the channel identified by the key.  Unmuting removes the entry rather than storing
     * false, so entries do not accumulate for every channel that was ever muted and unmuted.
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
     * Builds a valid preference key from a channel identity.  Keys are limited to Preferences.MAX_KEY_LENGTH
     * characters, which identities built from system, site and name can exceed, so over-long keys are truncated with a
     * hash of the full identity appended so that two identities sharing a long prefix cannot collide.
     */
    static String mutedChannelKey(String channelKey)
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
