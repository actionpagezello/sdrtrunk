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
package io.github.dsheirer.source.tuner.ui;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.tuner.TunerNoiseFloorEstimator;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * AP-fork (ap-13): the channels running on a tuner that the gain check can measure against.  The gain check measures
 * the signal-to-noise ratio of the tuner's control channel at each gain setting, when the tuner carries one: it is on
 * the air continuously, so every setting is measured against the same signal, and it is a signal that is decoded.
 */
public class GainCheckReference
{
    /**
     * Decoders whose standard (non-traffic) channel can be a trunking control channel
     */
    static final EnumSet<DecoderType> CONTROL_DECODERS = EnumSet.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2,
        DecoderType.DMR, DecoderType.NXDN, DecoderType.EDACS, DecoderType.SMARTNET, DecoderType.MPT1327,
        DecoderType.LTR_NET, DecoderType.PASSPORT);

    /**
     * A channel running on the tuner
     * @param name of the channel
     * @param frequency the channel is on now
     * @param bandwidth of the channel
     */
    public record Candidate(String name, long frequency, int bandwidth) {}

    private GainCheckReference()
    {
    }

    /**
     * Finds the possible control channels running on the tuner with the estimator: standard channels (not traffic
     * channels) with a trunking decoder.  The gain check then keeps only one that is on the air continuously - a P25
     * or DMR conventional channel is in the list but drops out there.
     *
     * @param manager for the running channels, or null
     * @param estimator of the tuner
     * @return candidates, possibly empty
     */
    public static List<Candidate> find(ChannelProcessingManager manager, TunerNoiseFloorEstimator estimator)
    {
        List<Candidate> candidates = new ArrayList<>();

        if(manager == null || estimator == null)
        {
            return candidates;
        }

        for(Map.Entry<Channel,ProcessingChain> entry : manager.getProcessingChains().entrySet())
        {
            Channel channel = entry.getKey();

            if(!channel.isStandardChannel() || channel.getDecodeConfiguration() == null ||
                !CONTROL_DECODERS.contains(channel.getDecodeConfiguration().getDecoderType()))
            {
                continue;
            }

            Source source = entry.getValue().getSource();

            if(source instanceof TunerChannelSource tunerChannelSource &&
                tunerChannelSource.getNoiseFloorEstimator() == estimator)
            {
                //A multi-frequency control channel (EDACS, Type II, P25 with alternates) is on its current frequency
                TunerChannel active = tunerChannelSource.getActiveTunerChannel();

                if(active != null)
                {
                    candidates.add(new Candidate(channel.getName(), active.getFrequency(), active.getBandwidth()));
                }
            }
        }

        return candidates;
    }
}
