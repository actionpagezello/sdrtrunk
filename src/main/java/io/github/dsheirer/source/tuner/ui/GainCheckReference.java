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
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
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
     * Decoders whose standard (non-traffic) channel is always a trunking control channel
     */
    static final EnumSet<DecoderType> CONTROL_DECODERS = EnumSet.of(DecoderType.EDACS, DecoderType.SMARTNET,
        DecoderType.MPT1327, DecoderType.LTR_NET, DecoderType.PASSPORT);

    /**
     * ap-16: a P25 Phase 1 channel counts as a control channel only after it decoded a network or RFSS status
     * broadcast within this time.  A conventional P25 channel (ap-15 used Salem Police on Daly and sent the tuner to
     * its lowest gain) never sends these.  P25 Phase 2, DMR and NXDN channels aren't used: their conventional channels
     * can't be told apart, so those tuners follow the noise floor.
     */
    static final long P25_CONTROL_BROADCAST_MAX_AGE_MS = 120_000;

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
     * Finds the control channels running on the tuner with the estimator: standard channels (not traffic channels)
     * with an EDACS, Type II, MPT-1327, LTR-Net or Passport decoder, and P25 Phase 1 channels that decoded a control
     * channel status broadcast in the last two minutes (ap-16).  The gain check then keeps only one that is on the air
     * continuously.
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
                !isControlChannel(channel.getDecodeConfiguration().getDecoderType(), entry.getValue()))
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

    /**
     * Indicates if a standard channel with the decoder is a trunking control channel.
     */
    static boolean isControlChannel(DecoderType decoderType, ProcessingChain chain)
    {
        if(CONTROL_DECODERS.contains(decoderType))
        {
            return true;
        }

        if(decoderType == DecoderType.P25_PHASE1 && chain != null)
        {
            for(Module module : chain.getModules())
            {
                if(module instanceof P25P1DecoderState state && state.isControlChannel(P25_CONTROL_BROADCAST_MAX_AGE_MS))
                {
                    return true;
                }
            }
        }

        return false;
    }
}
