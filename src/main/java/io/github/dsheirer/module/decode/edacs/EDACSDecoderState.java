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

package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.edacs.identifier.EDACSRadio;
import io.github.dsheirer.module.decode.edacs.identifier.EDACSTalkgroup;
import io.github.dsheirer.module.decode.edacs.provoice.ProVoiceMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.real.IRealBufferListener;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.source.heartbeat.IHeartbeatListener;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (EDACS): EDACS Extended Addressing decoder state.
 *
 * Control channel: passes group call assignments and updates to the traffic channel manager with the group, the
 * calling unit and whether the call is ProVoice (digital) or analog.
 *
 * Voice (traffic) channel: opens the audio when the channel starts and keeps the call going until the traffic channel
 * manager tears the channel down (the control channel stopped announcing the call), or the configured call time limit
 * passes.  Logs one line per call with the seconds of audio received.
 */
public class EDACSDecoderState extends DecoderState implements IHeartbeatListener, IRealBufferListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EDACSDecoderState.class);
    private static final double AUDIO_SAMPLE_RATE = 8000.0;
    private final ChannelType mChannelType;
    private final EDACSTrafficChannelManager mTrafficChannelManager;
    private final DecodeConfigEDACS mConfig;
    private final long mCallLimitMs;
    private final Listener<Heartbeat> mHeartbeatListener = heartbeat -> heartbeat();
    private LongSupplier mClock = System::currentTimeMillis;
    private long mFrequency;
    private long mTrafficStart;
    private long mLastContinuation;
    private boolean mTrafficEnded;
    private boolean mTrafficStarted;
    private long mAudioSamples;
    private long mVoiceFrames;
    private long mConcealedFrames;
    private long mLoggedControlFrequency;
    private final Listener<float[]> mAudioListener = audio -> mAudioSamples += audio.length;

    //Activity summary
    private long mFrameCount;
    private long mGrantCount;
    private final Map<Integer,Integer> mTalkgroupGrants = new TreeMap<>();
    private final Map<Integer,Integer> mChannelGrants = new TreeMap<>();

    /**
     * @param channelType STANDARD for the control channel, TRAFFIC for a voice channel it started
     * @param trafficChannelManager for the control channel, null for a voice channel
     * @param config for the channel
     */
    public EDACSDecoderState(ChannelType channelType, EDACSTrafficChannelManager trafficChannelManager,
                             DecodeConfigEDACS config)
    {
        mChannelType = channelType;
        mTrafficChannelManager = trafficChannelManager;
        mConfig = config;
        mCallLimitMs = config.getCallTimeoutSeconds() * 1000L;
    }

    /**
     * Replaces the clock - for testing only
     */
    void setClock(LongSupplier clock)
    {
        mClock = clock;
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @Override
    public void receive(IMessage message)
    {
        if(message instanceof ProVoiceMessage voice)
        {
            mAudioSamples += 640; //80 ms of voice at 8 kHz
            mVoiceFrames += 4;
            mConcealedFrames += voice.getConcealed();
            return;
        }

        if(message instanceof EDACSMessage edacs)
        {
            mFrameCount++;
            logTypes(edacs);

            if(mChannelType != ChannelType.TRAFFIC && mFrequency != 0 && mFrequency != mLoggedControlFrequency)
            {
                mLoggedControlFrequency = mFrequency;
                LOGGER.info("EDACS control channel decoding on " + format(mFrequency));
            }

            broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));

            if(mTrafficChannelManager == null || !edacs.isGroupVoice())
            {
                return;
            }

            int lcn = edacs.getLCN();

            if(lcn == 0 || lcn > EDACSMessage.MAX_LCN)
            {
                return; //status values (busy, queued, denied)
            }

            long frequency = mConfig.getFrequency(lcn);

            if(frequency != 0 && (frequency == mFrequency || frequency == getCurrentFrequency()))
            {
                return;
            }

            MutableIdentifierCollection ic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
            ic.remove(IdentifierClass.USER);
            ic.update(EDACSTalkgroup.createTo(edacs.getGroup()));

            if(edacs.getSource() != 0)
            {
                ic.update(EDACSRadio.createFrom(edacs.getSource()));
            }

            String details = edacs.isDigital() ? "PROVOICE" : "ANALOG";

            if(edacs.isEmergency())
            {
                details = "EMERGENCY " + details;
            }

            if(!edacs.isUpdate() && LOGGER.isDebugEnabled())
            {
                LOGGER.debug("EDACS " + (edacs.isDigital() ? "ProVoice" : "analog") + " call: group " +
                    edacs.getGroup() + " unit " + edacs.getSource() + " on LCN " + lcn + " " + format(frequency) +
                    (edacs.isEmergency() ? " EMERGENCY" : ""));
            }

            if(!edacs.isUpdate())
            {
                mGrantCount++;
                mChannelGrants.merge(lcn, 1, Integer::sum);
                mTalkgroupGrants.merge(edacs.getGroup(), 1, Integer::sum);
            }

            mTrafficChannelManager.processGrant(new EDACSChannel(lcn, frequency), ic, DecodeEventType.CALL_GROUP,
                details, true, edacs.isDigital() ? DecodeConfigEDACS.VoiceMode.PROVOICE :
                    DecodeConfigEDACS.VoiceMode.ANALOG, edacs.getTimestamp());
        }
    }

    /**
     * Voice channel: keeps the call open while the channel runs, up to the call time limit.
     */
    private void heartbeat()
    {
        if(mChannelType != ChannelType.TRAFFIC || mTrafficEnded)
        {
            return;
        }

        long now = mClock.getAsLong();

        if(now - mTrafficStart > mCallLimitMs)
        {
            mTrafficEnded = true;
            broadcast(new DecoderStateEvent(this, Event.END, State.CALL));
        }
        else if(now - mLastContinuation >= 1000)
        {
            mLastContinuation = now;
            broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CALL));
        }
    }

    @Override
    public Listener<Heartbeat> getHeartbeatListener()
    {
        return mHeartbeatListener;
    }

    @Override
    public void start()
    {
        super.start();

        if(mChannelType == ChannelType.TRAFFIC)
        {
            mTrafficStart = mClock.getAsLong();
            mLastContinuation = mTrafficStart;
            mTrafficEnded = false;
            mTrafficStarted = true;
            mAudioSamples = 0;
            mVoiceFrames = 0;
            mConcealedFrames = 0;
            broadcast(new DecoderStateEvent(this, Event.START, State.CALL));
        }
    }

    /**
     * Voice channel: one log line per followed call - how long the voice channel was open and how much audio got past
     * the squelch (0 s means nothing was heard, so there was nothing to play or stream).
     */
    @Override
    public void stop()
    {
        super.stop();

        if(mChannelType == ChannelType.TRAFFIC && mTrafficStarted)
        {
            mTrafficStarted = false;
            long frequency = mFrequency != 0 ? mFrequency : getCurrentFrequency();
            LOGGER.info(String.format("EDACS call TO:%s FROM:%s on %s - voice channel open %.1f s, " +
                    "audio %.1f s%s", first(Role.TO), first(Role.FROM), format(frequency),
                (mClock.getAsLong() - mTrafficStart) / 1000.0, mAudioSamples / AUDIO_SAMPLE_RATE,
                (mVoiceFrames > 0 ? String.format(", damaged frames %d of %d (%.1f%%)", mConcealedFrames,
                    mVoiceFrames, 100.0 * mConcealedFrames / mVoiceFrames) : "") +
                (mTrafficEnded ? " - stopped at the call limit" : "")));
        }
    }

    private final Map<String,Integer> mMinuteTypes = new TreeMap<>();
    private long mMinuteStart;

    /**
     * Diagnostics (DEBUG): the message types seen each minute, most frequent first
     */
    private void logTypes(EDACSMessage message)
    {
        if(!LOGGER.isDebugEnabled())
        {
            return;
        }

        String type = message.getMT1() == 0x1F ? String.format("1F/%X", message.getMT2()) :
            String.format("%02X", message.getMT1());
        mMinuteTypes.merge(type, 1, Integer::sum);

        if(message.getTimestamp() - mMinuteStart >= 60_000)
        {
            if(mMinuteStart > 0)
            {
                StringBuilder sb = new StringBuilder();
                mMinuteTypes.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                    .forEach(e -> sb.append(" ").append(e.getKey()).append(":").append(e.getValue()));
                LOGGER.debug("EDACS message types last minute (MT1 or 1F/MT2:count):" + sb);
            }

            mMinuteTypes.clear();
            mMinuteStart = message.getTimestamp();
        }
    }

    private String first(Role role)
    {
        List<Identifier> identifiers = getIdentifierCollection().getIdentifiers(role);
        return identifiers.isEmpty() ? "-" : identifiers.get(0).toString();
    }

    private static String format(long frequency)
    {
        return frequency > 0 ? String.format("%.4f MHz", frequency / 1e6) : "unknown frequency";
    }

    @Override
    public Listener<float[]> getBufferListener()
    {
        return mAudioListener;
    }

    @Override
    public void init()
    {
    }

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_RESET ->
            {
                if(mChannelType != ChannelType.TRAFFIC)
                {
                    resetState();
                }
            }
            case NOTIFICATION_SOURCE_FREQUENCY ->
            {
                mFrequency = event.getFrequency();
            }
            default ->
            {
            }
        }
    }

    public long getGrantCount()
    {
        return mGrantCount;
    }

    @Override
    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Activity Summary - Decoder: EDACS Extended Addressing\n");
        sb.append(DIVIDER1);
        sb.append("LCNs configured: ").append(mConfig.getLcnFrequencies().size()).append("\n");
        sb.append("Frames decoded: ").append(mFrameCount).append("\n");
        sb.append("Call assignments: ").append(mGrantCount).append("\n");
        sb.append(DIVIDER2).append("Assignments by LCN:\n");
        mChannelGrants.forEach((lcn, count) -> sb.append(String.format("  %3d  %s  %d%n", lcn,
            mConfig.getFrequency(lcn) > 0 ? String.format("%.4f MHz", mConfig.getFrequency(lcn) / 1e6) :
                "NOT IN LCN LIST", count)));
        sb.append(DIVIDER2).append("Assignments by group:\n");
        mTalkgroupGrants.forEach((group, count) -> sb.append(String.format("  %5d  %d%n", group, count)));
        return sb.toString();
    }
}
