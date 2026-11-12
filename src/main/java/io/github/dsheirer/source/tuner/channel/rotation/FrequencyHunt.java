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

package io.github.dsheirer.source.tuner.channel.rotation;

import java.util.Locale;

/**
 * AP-fork (edacs-14): works out, from the frequency rotation requests alone, when a channel with several frequencies
 * starts hunting for its control channel and when it has found one, and words the log lines.
 *
 * A channel with a frequency list is asked to move to its next frequency whenever its decoder has not been on a
 * control channel for the rotation delay (0.2 to 2 seconds).  While it is locked no requests arrive.  So the first
 * request after a quiet spell is the start of a hunt, and a quiet spell after requests is the end of one.  Until
 * this nothing was logged at either point - MBTA 2026-11-12: the control channel went off the air for 50 minutes and
 * the log could not show whether Cambridge and Summit 800 were hunting, or where they locked when it returned.
 *
 * A hunt is only written to the log once it has lasted {@link #ANNOUNCE_MILLISECONDS}: a control channel that fades
 * for a second or two at a weak site would otherwise fill the log.  Those short hunts are counted and the count is
 * given with the next hunt that is logged.
 *
 * Not thread safe - the caller serializes access.
 */
public class FrequencyHunt
{
    /** No rotation request for this long means the channel is on a control channel again.  The longest rotation
     * delay is 2 seconds and requests are checked at half the delay. */
    public static final long SETTLED_MILLISECONDS = 5_000;
    /** A hunt is logged once it has lasted this long */
    public static final long ANNOUNCE_MILLISECONDS = 15_000;
    /** A hunt that goes on is reported at this interval */
    public static final long PROGRESS_MILLISECONDS = 600_000;

    private final String mChannel;
    private final int mFrequencyCount;
    private boolean mHunting;
    private long mStarted;
    private long mLastRequest;
    private long mNextProgress;
    private long mLostFrequency;
    private boolean mAnnounced;
    private int mShortHunts;

    /**
     * Constructs an instance
     * @param channel label for the log lines
     * @param frequencyCount number of frequencies in the channel's list
     */
    public FrequencyHunt(String channel, int frequencyCount)
    {
        mChannel = channel;
        mFrequencyCount = frequencyCount;
    }

    /**
     * Indicates if the channel is hunting, which is when {@link #check(long, long)} has to be called.
     */
    public boolean isHunting()
    {
        return mHunting;
    }

    /**
     * A request to move to the next frequency arrived.
     * @param now in milliseconds
     * @param frequency the channel is on as the request arrives, in Hertz
     */
    public void requested(long now, long frequency)
    {
        mLastRequest = now;

        if(!mHunting)
        {
            mHunting = true;
            mAnnounced = false;
            mStarted = now;
            mLostFrequency = frequency;
            mNextProgress = now + PROGRESS_MILLISECONDS;
        }
    }

    /**
     * Periodic check while hunting.
     * @param now in milliseconds
     * @param frequency the channel is on now, in Hertz
     * @return line to log at INFO, or null
     */
    public String check(long now, long frequency)
    {
        if(!mHunting)
        {
            return null;
        }

        if(now - mLastRequest >= SETTLED_MILLISECONDS)
        {
            mHunting = false;

            if(!mAnnounced)
            {
                mShortHunts++;
                return null;
            }

            //The lock happened when the requests stopped, not when it was noticed
            long hunted = mLastRequest - mStarted;

            return mChannel + " found a control channel on " + megahertz(frequency) + " MHz after hunting for " +
                duration(hunted) + (frequency == mLostFrequency ? " - the same frequency it lost" :
                " - it lost " + megahertz(mLostFrequency) + " MHz");
        }

        if(!mAnnounced)
        {
            if(now - mStarted < ANNOUNCE_MILLISECONDS)
            {
                return null;
            }

            mAnnounced = true;
            int shortHunts = mShortHunts;
            mShortHunts = 0;

            return mChannel + " has had no control channel for " + duration(now - mStarted) + " (lost on " +
                megahertz(mLostFrequency) + " MHz) - hunting through its [" + mFrequencyCount + "] frequencies" +
                (shortHunts > 0 ? "; [" + shortHunts + "] shorter hunts since the last one logged" : "");
        }

        if(now >= mNextProgress)
        {
            mNextProgress = now + PROGRESS_MILLISECONDS;

            return mChannel + " is still hunting for a control channel after " + duration(now - mStarted) +
                " - none found on any of its [" + mFrequencyCount + "] frequencies";
        }

        return null;
    }

    /**
     * Ends the hunt without a line, for when the channel is stopped.
     */
    public void reset()
    {
        mHunting = false;
    }

    private static String megahertz(long frequency)
    {
        return String.format(Locale.US, "%.4f", frequency / 1E6);
    }

    private static String duration(long milliseconds)
    {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds < 120 ? seconds + " seconds" : (seconds / 60) + " minutes";
    }
}
