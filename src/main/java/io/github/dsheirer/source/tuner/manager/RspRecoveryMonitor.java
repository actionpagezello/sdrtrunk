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

package io.github.dsheirer.source.tuner.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (edacs-14): re-attaches an SDRplay RSP that the SDRplay API reported as removed, and restarts the playlist
 * channels that the removal stopped.
 *
 * RSP tuners are discovered once, at startup, so until this a removed RSP stayed gone until SDRTrunk was restarted.
 * Daly 2026-11-11 18:05:53 and 2026-11-12 09:20:53: RSP1B SER#24050F5260 was reported removed by the SDRplay API
 * while carrying 16 channels - Windows still listed the device as present and working, logged no USB or power event,
 * and the second RSP1B on the same API service kept running.  Each time a restart of SDRTrunk found the device at
 * once.
 *
 * The 16.x tuner code releases a removed device back to the API itself (ControlRsp.deviceRemoved), so recovery only
 * has to ask the API for its device list and, when the serial number is listed again, add the tuner the same way as
 * at startup.  The channels to restart are the playlist channels that were running before the removal and were not
 * running after it: removing the tuner stops them (PolyphaseChannelManager.stopAllChannels).  A channel that hunts
 * across several frequencies is not stopped by the removal - it keeps asking for a tuner and recovers by itself.
 *
 * This class holds the decisions only.  Everything that touches the SDRplay API, the tuner model or the channels is
 * behind {@link Host}, which is why it can be tested without a radio.  It has not been proven on a real removal yet.
 *
 * @param <C> channel type
 */
public class RspRecoveryMonitor<C>
{
    private static final Logger mLog = LoggerFactory.getLogger(RspRecoveryMonitor.class);
    /** First check after a removal - the device has just been released back to the API */
    static final long FIRST_CHECK_SECONDS = 10;
    /** Interval between checks while a removed RSP is still missing */
    static final long CHECK_INTERVAL_SECONDS = 30;
    /** Time for the re-attached tuner to apply its saved settings before its channels are started */
    static final long CHANNEL_RESTART_DELAY_SECONDS = 3;
    /** Channels that couldn't be started are tried once more after this */
    static final long CHANNEL_RETRY_DELAY_SECONDS = 15;
    /** A progress line is logged on the first check and then every this many checks (10 minutes) */
    static final int LOG_EVERY_N_CHECKS = 20;

    /**
     * What the monitor needs from the application.
     */
    public interface Host<C>
    {
        /**
         * Serial numbers of the RSP devices that the SDRplay API currently lists.  The API lists only devices that
         * no program has selected.
         * @return serial numbers, empty when there are none or the API can't be asked
         */
        List<String> getAvailableSerialNumbers();

        /**
         * Adds and starts the tuner(s) for the device, the same way as at startup.
         * @return true if a tuner for the device is now started
         */
        boolean attach(String serialNumber);

        /**
         * Playlist (standard) channels that are running now.
         */
        Collection<C> getRunningChannels();

        /**
         * Starts the channel.
         * @return true if it was started, false if there was nothing to do (already running, or deleted)
         * @throws Exception if it couldn't be started
         */
        boolean start(C channel) throws Exception;

        /**
         * Name of the channel for the log.
         */
        String getName(C channel);
    }

    /**
     * Runs tasks later.  ThreadPool.SCHEDULED in the application; tests run the tasks themselves.
     */
    public interface Scheduler
    {
        ScheduledFuture<?> repeat(Runnable task, long firstDelaySeconds, long intervalSeconds);
        void later(Runnable task, long delaySeconds);
    }

    private final Host<C> mHost;
    private final Scheduler mScheduler;
    private final Map<String,Pending<C>> mPending = new LinkedHashMap<>();
    private ScheduledFuture<?> mCheckFuture;
    private boolean mStopped;

    /**
     * Constructs an instance
     * @param host that lists devices, attaches tuners and starts channels
     * @param scheduler for the periodic check and the delayed channel start
     */
    public RspRecoveryMonitor(Host<C> host, Scheduler scheduler)
    {
        mHost = host;
        mScheduler = scheduler;
    }

    /**
     * Call before an RSP that the API reported as removed is stopped and taken out of the tuner model.
     * @param serialNumber of the device
     * @param id of the tuner, for the log
     * @return removal to complete once the tuner has been removed
     */
    public Removal removing(String serialNumber, String id)
    {
        Set<C> before = new LinkedHashSet<>();

        try
        {
            before.addAll(mHost.getRunningChannels());
        }
        catch(Exception e)
        {
            mLog.warn("RSP recovery: couldn't list the running channels before removing [" + id + "] - its channels " +
                "will not be restarted - " + e);
        }

        return new Removal(serialNumber, id, before);
    }

    /**
     * An RSP removal in progress.
     */
    public class Removal
    {
        private final String mSerialNumber;
        private final String mId;
        private final Set<C> mBefore;

        private Removal(String serialNumber, String id, Set<C> before)
        {
            mSerialNumber = serialNumber;
            mId = id;
            mBefore = before;
        }

        /**
         * Call once the tuner has been stopped and removed.  Starts checking for the device to return.
         */
        public void complete()
        {
            Set<C> stopped = new LinkedHashSet<>(mBefore);

            try
            {
                stopped.removeAll(mHost.getRunningChannels());
            }
            catch(Exception e)
            {
                mLog.warn("RSP recovery: couldn't list the running channels after removing [" + mId + "] - " + e);
                stopped.clear();
            }

            removed(mSerialNumber, mId, stopped);
        }
    }

    /**
     * Records the removal and starts checking for the device to return.
     */
    private synchronized void removed(String serialNumber, String id, Set<C> stopped)
    {
        if(mStopped)
        {
            return;
        }

        Pending<C> pending = mPending.computeIfAbsent(serialNumber, s -> new Pending<>(s, id));
        pending.mChannels.addAll(stopped);

        mLog.warn("RSP recovery: [" + id + "] was removed - checking the SDRplay API for it in " + FIRST_CHECK_SECONDS +
            " seconds and then every " + CHECK_INTERVAL_SECONDS + " seconds; [" + pending.mChannels.size() +
            "] channel(s) will be restarted when it returns: " + names(pending.mChannels));

        if(mCheckFuture == null)
        {
            mCheckFuture = mScheduler.repeat(this::check, FIRST_CHECK_SECONDS, CHECK_INTERVAL_SECONDS);
        }
    }

    /**
     * Number of removed RSP devices that have not returned yet.
     */
    public synchronized int getPendingCount()
    {
        return mPending.size();
    }

    /**
     * Stops checking, for application shutdown.
     */
    public synchronized void stop()
    {
        mStopped = true;
        mPending.clear();
        cancel();
    }

    private void cancel()
    {
        if(mCheckFuture != null)
        {
            mCheckFuture.cancel(false);
            mCheckFuture = null;
        }
    }

    /**
     * Periodic check: re-attaches each removed RSP that the SDRplay API lists again.
     */
    void check()
    {
        List<Pending<C>> toCheck;

        synchronized(this)
        {
            if(mStopped || mPending.isEmpty())
            {
                cancel();
                return;
            }

            toCheck = new ArrayList<>(mPending.values());
        }

        List<String> listed;

        try
        {
            listed = mHost.getAvailableSerialNumbers();
        }
        catch(Exception e)
        {
            listed = List.of();
            mLog.warn("RSP recovery: couldn't get the device list from the SDRplay API - " + e);
        }

        for(Pending<C> pending: toCheck)
        {
            pending.mChecks++;
            boolean progress = pending.mChecks == 1 || pending.mChecks % LOG_EVERY_N_CHECKS == 0;

            if(!listed.contains(pending.mSerialNumber))
            {
                if(progress)
                {
                    mLog.info("RSP recovery: [" + pending.mId + "] is not in the SDRplay API device list " +
                        pending.elapsed() + " after it was removed (the API lists " + (listed.isEmpty() ?
                        "no free devices" : listed) + ") - still checking every " + CHECK_INTERVAL_SECONDS + " seconds");
                }

                continue;
            }

            boolean attached = false;

            try
            {
                attached = mHost.attach(pending.mSerialNumber);
            }
            catch(Exception e)
            {
                mLog.error("RSP recovery: [" + pending.mId + "] is listed by the SDRplay API again but adding it failed", e);
            }

            if(!attached)
            {
                if(progress)
                {
                    mLog.warn("RSP recovery: [" + pending.mId + "] is listed by the SDRplay API again but could not be " +
                        "started " + pending.elapsed() + " after it was removed - still trying every " +
                        CHECK_INTERVAL_SECONDS + " seconds");
                }

                continue;
            }

            synchronized(this)
            {
                if(mStopped)
                {
                    return;
                }

                mPending.remove(pending.mSerialNumber);
            }

            List<C> channels = new ArrayList<>(pending.mChannels);

            mLog.warn("RSP recovery: [" + pending.mId + "] is BACK " + pending.elapsed() + " after it was removed - " +
                "starting its [" + channels.size() + "] channel(s) in " + CHANNEL_RESTART_DELAY_SECONDS + " seconds");

            mScheduler.later(() -> startChannels(pending.mId, channels, true), CHANNEL_RESTART_DELAY_SECONDS);
        }
    }

    /**
     * Starts the channels that the removal stopped.
     * @param id of the tuner that returned
     * @param channels to start
     * @param retry true to try the channels that fail once more, later
     */
    void startChannels(String id, List<C> channels, boolean retry)
    {
        int started = 0;
        int skipped = 0;
        List<C> failed = new ArrayList<>();

        for(C channel: channels)
        {
            try
            {
                if(mHost.start(channel))
                {
                    started++;
                }
                else
                {
                    skipped++;
                }
            }
            catch(Exception e)
            {
                failed.add(channel);

                if(!retry)
                {
                    mLog.error("RSP recovery: couldn't start channel [" + mHost.getName(channel) + "] after [" + id +
                        "] returned - " + e.getMessage() + " - start it by hand");
                }
            }
        }

        mLog.info("RSP recovery: [" + id + "] - started [" + started + "] of [" + channels.size() + "] channel(s)" +
            (skipped > 0 ? ", [" + skipped + "] already running or deleted" : "") +
            (failed.isEmpty() ? "" : ", [" + failed.size() + "] could not be started" +
                (retry ? " - trying those again in " + CHANNEL_RETRY_DELAY_SECONDS + " seconds: " + names(failed) : "")));

        if(retry && !failed.isEmpty())
        {
            mScheduler.later(() -> startChannels(id, failed, false), CHANNEL_RETRY_DELAY_SECONDS);
        }
    }

    private List<String> names(Collection<C> channels)
    {
        List<String> names = new ArrayList<>();

        for(C channel: channels)
        {
            names.add(mHost.getName(channel));
        }

        return names;
    }

    /**
     * A removed RSP that has not returned yet.
     */
    private static final class Pending<C>
    {
        private final String mSerialNumber;
        private final String mId;
        private final Set<C> mChannels = new LinkedHashSet<>();
        private final long mRemoved = System.currentTimeMillis();
        private int mChecks;

        private Pending(String serialNumber, String id)
        {
            mSerialNumber = serialNumber;
            mId = id;
        }

        /**
         * Time since the removal, for the log.
         */
        private String elapsed()
        {
            long seconds = Math.max(0, (System.currentTimeMillis() - mRemoved) / 1000);
            return seconds < 120 ? seconds + " seconds" : (seconds / 60) + " minutes";
        }
    }
}
