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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.sdrplay.DiscoveredRspTuner;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-15.11.1): re-attaches an SDRplay RSP that the SDRplay API reported as removed, and restarts the
 * channels it was carrying.
 *
 * RSP tuners are discovered once, at startup, and USB hotplug isn't available on Windows, so before this a removed
 * RSP stayed gone until SDRTrunk was restarted - and its channels kept 'running' on a source that no longer received
 * samples (Daly 2026-11-01 11:12: RSP1B removed while carrying 16 channels, down for 25 hours with nothing stopped).
 *
 * Every RETRY_INTERVAL_SECONDS this asks the SDRplay API for its devices.  When a removed serial number is listed
 * again, the tuner is re-added the same way as at startup, and each playlist channel that was being fed by the
 * removed tuner is stopped and started so that it gets a source from the returned tuner (or from any other tuner
 * that can carry it).  Traffic channels aren't restarted directly - their control channel recreates them.
 */
public class RspRecoveryMonitor
{
    private static final Logger mLog = LoggerFactory.getLogger(RspRecoveryMonitor.class);
    static final long RETRY_INTERVAL_SECONDS = 30;
    private static final long CHANNEL_RESTART_DELAY_SECONDS = 3;
    private static final int LOG_EVERY_N_ATTEMPTS = 20;

    private final Supplier<List<DeviceInfo>> mDeviceInfoSupplier;
    private final Predicate<String> mTunerPresent;
    private final Function<DeviceInfo,Integer> mTunerAdder;
    private final Map<String,PendingRecovery> mPending = new LinkedHashMap<>();
    private ChannelProcessingManager mChannelProcessingManager;
    private ScheduledFuture<?> mRetryFuture;

    /**
     * Constructs an instance.
     * @param deviceInfoSupplier lists the devices currently available from the SDRplay API (empty list on error)
     * @param tunerPresent tests whether a discovered tuner with the tuner id is already in the tuner model
     * @param tunerAdder creates, adds and starts the discovered tuner(s) for a returned device, returning the count
     */
    public RspRecoveryMonitor(Supplier<List<DeviceInfo>> deviceInfoSupplier, Predicate<String> tunerPresent,
                              Function<DeviceInfo,Integer> tunerAdder)
    {
        mDeviceInfoSupplier = deviceInfoSupplier;
        mTunerPresent = tunerPresent;
        mTunerAdder = tunerAdder;
    }

    /**
     * Sets the channel processing manager used to find and restart the affected channels.
     */
    public void setChannelProcessingManager(ChannelProcessingManager channelProcessingManager)
    {
        mChannelProcessingManager = channelProcessingManager;
    }

    /**
     * Records the removal of an RSP tuner and starts retrying to re-attach it.
     * @param tuner that the SDRplay API reported as removed
     * @param tunerChannels the tuner was sourcing at the time of removal
     */
    public synchronized void rspRemoved(DiscoveredRspTuner<?> tuner, Set<TunerChannel> tunerChannels)
    {
        String serial = tuner.getDeviceInfo().getSerialNumber();
        List<Channel> affected = getAffectedChannels(tunerChannels);
        PendingRecovery pending = mPending.computeIfAbsent(serial, s -> new PendingRecovery(serial, tuner.getId()));
        pending.channels().addAll(affected);

        List<String> names = new ArrayList<>();

        for(Channel channel: pending.channels())
        {
            names.add(channel.getName());
        }

        mLog.warn("RSP recovery: [" + tuner.getId() + "] was removed - checking every " + RETRY_INTERVAL_SECONDS +
            " seconds for it to return; [" + names.size() + "] channel(s) will be restarted when it does: " + names);

        if(mRetryFuture == null)
        {
            mRetryFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::retry, RETRY_INTERVAL_SECONDS,
                RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Number of RSP serial numbers awaiting recovery.
     */
    public synchronized int getPendingCount()
    {
        return mPending.size();
    }

    /**
     * Standard (playlist) channels whose processing chain source is a tuner channel source for one of the tuner
     * channels.  Matched by identity: each tuner channel instance belongs to exactly one channel source.
     */
    List<Channel> getAffectedChannels(Set<TunerChannel> tunerChannels)
    {
        List<Channel> affected = new ArrayList<>();

        if(mChannelProcessingManager == null || tunerChannels == null || tunerChannels.isEmpty())
        {
            return affected;
        }

        Set<TunerChannel> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(tunerChannels);

        for(Map.Entry<Channel,ProcessingChain> entry: mChannelProcessingManager.getProcessingChains().entrySet())
        {
            Source source = entry.getValue().getSource();

            if(entry.getKey().getChannelType() == Channel.ChannelType.STANDARD &&
               source instanceof TunerChannelSource tcs && identities.contains(tcs.getTunerChannel()))
            {
                affected.add(entry.getKey());
            }
        }

        return affected;
    }

    /**
     * Retry task: re-attaches any removed RSP that the SDRplay API lists again.
     */
    void retry()
    {
        List<PendingRecovery> toCheck;

        synchronized(this)
        {
            if(mPending.isEmpty())
            {
                cancel();
                return;
            }

            toCheck = new ArrayList<>(mPending.values());
        }

        List<DeviceInfo> deviceInfos;

        try
        {
            deviceInfos = mDeviceInfoSupplier.get();
        }
        catch(Exception e)
        {
            deviceInfos = Collections.emptyList();
            mLog.debug("RSP recovery: unable to list SDRplay devices", e);
        }

        for(PendingRecovery pending: toCheck)
        {
            pending.attempt();
            DeviceInfo returned = null;

            for(DeviceInfo deviceInfo: deviceInfos)
            {
                if(pending.serial().equals(deviceInfo.getSerialNumber()))
                {
                    returned = deviceInfo;
                    break;
                }
            }

            if(returned == null)
            {
                if(pending.attempts() % LOG_EVERY_N_ATTEMPTS == 0)
                {
                    mLog.info("RSP recovery: [" + pending.id() + "] still not available from the SDRplay API after " +
                        (pending.attempts() * RETRY_INTERVAL_SECONDS / 60) + " minute(s) - still checking");
                }

                continue;
            }

            int added = 0;

            if(!mTunerPresent.test(pending.id()))
            {
                try
                {
                    added = mTunerAdder.apply(returned);
                }
                catch(Exception e)
                {
                    mLog.error("RSP recovery: [" + pending.id() + "] is listed again but couldn't be re-added - will retry", e);
                    continue;
                }

                if(added == 0)
                {
                    continue;
                }
            }

            synchronized(this)
            {
                mPending.remove(pending.serial());
            }

            mLog.warn("RSP recovery: [" + pending.id() + "] is back after " +
                (pending.attempts() * RETRY_INTERVAL_SECONDS) + " seconds - re-added [" + added +
                "] tuner(s); restarting [" + pending.channels().size() + "] channel(s) in " +
                CHANNEL_RESTART_DELAY_SECONDS + " seconds");

            List<Channel> channels = new ArrayList<>(pending.channels());
            ThreadPool.SCHEDULED.schedule(() -> restartChannels(pending.id(), channels), CHANNEL_RESTART_DELAY_SECONDS,
                TimeUnit.SECONDS);
        }
    }

    /**
     * Stops and starts each channel so that it obtains a new tuner channel source.
     */
    void restartChannels(String id, List<Channel> channels)
    {
        if(mChannelProcessingManager == null)
        {
            return;
        }

        int restarted = 0;

        for(Channel channel: channels)
        {
            try
            {
                if(mChannelProcessingManager.getProcessingChains().containsKey(channel))
                {
                    mChannelProcessingManager.stop(channel);
                }

                mChannelProcessingManager.start(channel);
                restarted++;
            }
            catch(ChannelException ce)
            {
                mLog.error("RSP recovery: couldn't restart channel [" + channel.getName() + "] after [" + id +
                    "] returned - " + ce.getMessage());
            }
            catch(Exception e)
            {
                mLog.error("RSP recovery: error restarting channel [" + channel.getName() + "]", e);
            }
        }

        mLog.info("RSP recovery: restarted [" + restarted + "] of [" + channels.size() + "] channel(s) after [" + id +
            "] returned");
    }

    private synchronized void cancel()
    {
        if(mRetryFuture != null)
        {
            mRetryFuture.cancel(false);
            mRetryFuture = null;
        }
    }

    /**
     * A removed RSP awaiting recovery.
     */
    static final class PendingRecovery
    {
        private final String mSerial;
        private final String mId;
        private final Set<Channel> mChannels = new LinkedHashSet<>();
        private int mAttempts;

        PendingRecovery(String serial, String id)
        {
            mSerial = serial;
            mId = id;
        }

        String serial()
        {
            return mSerial;
        }

        String id()
        {
            return mId;
        }

        Set<Channel> channels()
        {
            return mChannels;
        }

        void attempt()
        {
            mAttempts++;
        }

        int attempts()
        {
            return mAttempts;
        }
    }
}
