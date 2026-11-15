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

package io.github.dsheirer.module.decode.p25.phase1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the dominant NAC value for a channel to aid in NID error detection and verification.
 */
public class NACTracker
{
    private static final Comparator<Tracker> COUNT_DESCENDING_COMPARATOR = new CountDescendingSorter();
    private static final Comparator<Tracker> OBSERVATION_ASCENDING_COMPARATOR = new ObservationAscendingSorter();
    //AP-fork (ap-15.9.32): evict the lowest count first, then the oldest last observation
    private static final Comparator<Tracker> COUNT_ASCENDING_THEN_OBSERVATION_ASCENDING_COMPARATOR =
        Comparator.comparingInt(Tracker::count).thenComparing(OBSERVATION_ASCENDING_COMPARATOR);
    private final Map<Integer, Tracker> mTrackerMap = new HashMap<>();
    //AP-fork (ap-15.9.32): was 3.  Noise produces BCH-decodable NIDs with random NAC values; with only three slots and
    //oldest-first eviction, a few minutes of silence filled the slots with count-1 random NACs and evicted the
    //channel's own NAC, which turned the NAC check off entirely.  More slots plus lowest-count eviction (below) keeps
    //every NAC the channel actually uses while still leaving room for the random ones to churn.
    private static final int MAX_TRACKER_COUNT = 8;
    private static final int MIN_OBSERVATION_THRESHOLD = 3;
    //Observation sequence number, used instead of the system clock to order the trackers by their last observation.
    //Recordings decode faster than real time, so many observations share the same millisecond, and the clock would
    //make the eviction choice (and the decoded output) depend on the processing speed.
    private long mObservationCount = 0;

    /**
     * Removes all tracked NAC values.  Invoke this method after an extended loss of sync.
     */
    public void reset()
    {
        mTrackerMap.clear();
    }

    /**
     * Tracks the NAC value.  Each time the NAC value is observed, the observation count is incremented so that the
     * dominant NAC value will have the highest observation count.  Additionally, the tracker's last observation is
     * updated to this observation.  When the tracker count exceeds the max tracker threshold, the tracker with the
     * lowest observation count is thrown out, and of those with equal counts, the one with the oldest last
     * observation.
     *
     * AP-fork (ap-15.9.32): eviction was oldest-first regardless of count, so a NAC observed thousands of times was
     * thrown out by three single random NACs decoded from noise.  Measured on Troop B and Methuen recordings: at
     * every false sync that knocked the equalizer to its limit, the tracker held only three count-1 random NACs.
     * @param nac to track
     */
    public void track(int nac)
    {
        mObservationCount++;
        Tracker tracker = mTrackerMap.get(nac);

        if(tracker == null)
        {
            mTrackerMap.put(nac, new Tracker(nac));

            if(mTrackerMap.size() > MAX_TRACKER_COUNT)
            {
                List<Tracker> trackerList = new ArrayList<>(mTrackerMap.values());
                trackerList.sort(COUNT_ASCENDING_THEN_OBSERVATION_ASCENDING_COMPARATOR);
                Tracker weakest = trackerList.getFirst();
                mTrackerMap.remove(weakest.nac());
            }
        }
        else
        {
            tracker.increment();
        }
    }

    /**
     * AP-fork (ap-15.9.32): indicates if the NAC value has been observed often enough to be one the channel uses.
     * @param nac to check
     * @return true if the NAC has at least the minimum number of observations
     */
    public boolean isEstablished(int nac)
    {
        Tracker tracker = mTrackerMap.get(nac);
        return tracker != null && tracker.count() >= MIN_OBSERVATION_THRESHOLD;
    }

    /**
     * AP-fork (ap-15.9.32): indicates if any NAC value has been observed often enough to be one the channel uses.
     */
    public boolean hasEstablishedNAC()
    {
        for(Tracker tracker: mTrackerMap.values())
        {
            if(tracker.count() >= MIN_OBSERVATION_THRESHOLD)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * AP-fork (ap-15.9.32): lists the established NAC values in hex, for logging.
     */
    public String getEstablishedNACs()
    {
        List<String> nacs = new ArrayList<>();

        for(Tracker tracker: mTrackerMap.values())
        {
            if(tracker.count() >= MIN_OBSERVATION_THRESHOLD)
            {
                nacs.add(String.format("0x%03X", tracker.nac()));
            }
        }

        return nacs.isEmpty() ? "none" : String.join(",", nacs);
    }

    /**
     * Identifies the dominant NAC value by sorting the tracked NAC values in count order with the highest count NAC
     * value identified as the dominant NAC.  The dominant NAC must have at least 3 observations to be the dominant NAC.
     * @return dominant tracked NAC value.
     */
    public int getTrackedNAC()
    {
        if(mTrackerMap.isEmpty())
        {
            return 0;
        }

        List<Tracker> trackers = new ArrayList<>(mTrackerMap.values());
        trackers.sort(COUNT_DESCENDING_COMPARATOR);
        Tracker highestCount = trackers.getFirst();

        if(highestCount.count() >= MIN_OBSERVATION_THRESHOLD)
        {
            return highestCount.nac();
        }

        return 0;
    }

    /**
     * Tracked NAC value class.
     */
    private class Tracker
    {
        private int mNAC;
        private long mLastObservation = mObservationCount;
        private int mCount = 1;

        /**
         * Constructs an instance to track the NAC.  Note: count is set to 1 and the last observation is set to the
         * current observation.
         * @param nac to track.
         */
        private Tracker(int nac)
        {
            mNAC = nac;
        }

        /**
         * Tracked NAC value for this tracker.
         * @return nac value.
         */
        private int nac()
        {
            return mNAC;
        }

        /**
         * Increments the observation count for this tracker and updates the last observation.
         */
        public void increment()
        {
            mCount++;

            //Don't let the count value rollover to negative
            if(mCount < 0)
            {
                mCount = Integer.MAX_VALUE;
            }

            mLastObservation = mObservationCount;
        }

        /**
         * Observation sequence number of the last observation of this tracker's NAC.
         * @return last observation.
         */
        public long lastObservation()
        {
            return mLastObservation;
        }

        /**
         * Observation count for this tracker.
         * @return count
         */
        public int count()
        {
            return mCount;
        }

        /**
         * Pretty print.
         */
        @Override
        public String toString()
        {
            return "NAC [" + mNAC + "] COUNT [" + mCount + "] LAST OBSERVATION [" + mLastObservation + "]";
        }
    }

    /**
     * Sorts the tracked NACs in count order where index 0 is the highest tracked value.
     */
    public static class CountDescendingSorter implements Comparator<Tracker>
    {
        @Override
        public int compare(Tracker o1, Tracker o2)
        {
            //Simply swap the order for comparison to get the reverse count sort
            return Integer.compare(o2.count(), o1.count());
        }
    }

    /**
     * Sorts the tracked NACs in last observation order where index 0 is the least recently observed value.
     */
    public static class ObservationAscendingSorter implements Comparator<Tracker>
    {
        @Override
        public int compare(Tracker o1, Tracker o2)
        {
            return Long.compare(o1.lastObservation(), o2.lastObservation());
        }
    }
}
