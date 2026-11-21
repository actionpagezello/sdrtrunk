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
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-16): logs which channels each tuner carries, when the tuner first carries channels and whenever that
 * list changes, so a tuner's overload warnings can be matched to its channels.  Only standard channels are listed:
 * trunked traffic channels come and go with every call.
 */
public class TunerChannelLog
{
    private static final Logger LOG = LoggerFactory.getLogger(TunerChannelLog.class);
    static final long CHECK_INTERVAL_SECONDS = 60;

    private final Supplier<List<Tuner>> mTuners;
    private final Supplier<ChannelProcessingManager> mChannels;
    private final Map<String,String> mLogged = new HashMap<>();
    private ScheduledFuture<?> mTimer;

    /**
     * Constructs an instance
     * @param tuners supplies the running tuners
     * @param channels supplies the channel processing manager, or null until it is set
     */
    public TunerChannelLog(Supplier<List<Tuner>> tuners, Supplier<ChannelProcessingManager> channels)
    {
        mTuners = tuners;
        mChannels = channels;
    }

    /**
     * Starts checking once a minute
     */
    public synchronized void start()
    {
        if(mTimer == null)
        {
            mTimer = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::check, CHECK_INTERVAL_SECONDS,
                CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Stops checking
     */
    public synchronized void stop()
    {
        if(mTimer != null)
        {
            mTimer.cancel(false);
            mTimer = null;
        }
    }

    private void check()
    {
        try
        {
            ChannelProcessingManager manager = mChannels.get();

            if(manager == null)
            {
                return;
            }

            for(Tuner tuner : mTuners.get())
            {
                TunerController controller = tuner.getTunerController();

                if(controller == null)
                {
                    continue;
                }

                String list = describe(manager.getProcessingChains(), controller);
                String name = tuner.getPreferredName();
                String previous = mLogged.get(name);

                if(!list.equals(previous) && !(previous == null && list.isEmpty()))
                {
                    mLogged.put(name, list);
                    LOG.info("Tuner [" + name + "] at " + String.format(Locale.US, "%.4f MHz",
                        controller.getFrequency() / 1E6) + " carries " + (list.isEmpty() ? "no channels" : list));
                }
            }
        }
        catch(RuntimeException e)
        {
            LOG.debug("Couldn't list the channels on each tuner", e);
        }
    }

    /**
     * Lists the standard channels whose source is on the tuner, in frequency order: name (MHz).
     */
    static String describe(Map<Channel,ProcessingChain> chains, TunerController controller)
    {
        record Entry(String name, long frequency) {}
        List<Entry> entries = new ArrayList<>();

        for(Map.Entry<Channel,ProcessingChain> chain : chains.entrySet())
        {
            if(!chain.getKey().isStandardChannel())
            {
                continue;
            }

            Source source = chain.getValue().getSource();

            if(source instanceof TunerChannelSource tunerChannelSource &&
                tunerChannelSource.getNoiseFloorEstimator() == controller.getNoiseFloorEstimator())
            {
                TunerChannel active = tunerChannelSource.getActiveTunerChannel();
                entries.add(new Entry(chain.getKey().getName(), active != null ? active.getFrequency() : 0));
            }
        }

        entries.sort((a, b) -> Long.compare(a.frequency(), b.frequency()));
        List<String> parts = new ArrayList<>();

        for(Entry entry : entries)
        {
            parts.add(entry.name() + " (" + String.format(Locale.US, "%.4f", entry.frequency() / 1E6) + ")");
        }

        return String.join(", ", parts);
    }
}
