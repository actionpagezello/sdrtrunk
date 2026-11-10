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

package io.github.dsheirer.module.decode.smartnet;

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
import io.github.dsheirer.module.decode.smartnet.identifier.SmartNetRadio;
import io.github.dsheirer.module.decode.smartnet.identifier.SmartNetTalkgroup;
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
 * AP-fork (SmartNet): Motorola Type II decoder state.
 *
 * Control channel: pairs outbound signalling words into channel grants and passes them to the traffic channel manager.
 * A grant is two OSWs - the calling radio with command 0x308, then the talkgroup (group flag set) with the channel number
 * as its command.  A channel OSW without the 0x308 before it is an update for a call already in progress.  A channel
 * OSW without the group flag after a 0x308 is a private call to that radio.
 *
 * Voice (traffic) channel: there is nothing to decode - the audio is analog FM.  The state opens the audio when the
 * channel starts and keeps the call going until the traffic channel manager tears the channel down (the control
 * channel stopped announcing the call), or the configured call time limit passes.
 */
public class SmartNetDecoderState extends DecoderState implements IHeartbeatListener, IRealBufferListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartNetDecoderState.class);
    private static final double AUDIO_SAMPLE_RATE = 8000.0;
    private final ChannelType mChannelType;
    private final SmartNetTrafficChannelManager mTrafficChannelManager;
    private final SmartNetBandPlan mBandPlan;
    private final long mCallLimitMs;
    private final Listener<Heartbeat> mHeartbeatListener = heartbeat -> heartbeat();
    private LongSupplier mClock = System::currentTimeMillis;
    private SmartNetOSW mPrevious;
    private long mFrequency;
    private long mTrafficStart;
    private long mLastContinuation;
    private boolean mTrafficEnded;
    private boolean mTrafficStarted;
    private long mAudioSamples;
    private long mLoggedControlFrequency;
    private final Listener<float[]> mAudioListener = audio -> mAudioSamples += audio.length;

    //Activity summary
    private long mOSWCount;
    private long mGrantCount;
    private final Map<Integer,Integer> mTalkgroupGrants = new TreeMap<>();
    private final Map<Integer,Integer> mChannelGrants = new TreeMap<>();

    /**
     * @param channelType STANDARD for the control channel, TRAFFIC for a voice channel it started
     * @param trafficChannelManager for the control channel, null for a voice channel
     * @param config for the channel
     */
    public SmartNetDecoderState(ChannelType channelType, SmartNetTrafficChannelManager trafficChannelManager,
                                DecodeConfigSmartNet config)
    {
        mChannelType = channelType;
        mTrafficChannelManager = trafficChannelManager;
        mBandPlan = config.getBandPlan();
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
        return DecoderType.SMARTNET;
    }

    @Override
    public void receive(IMessage message)
    {
        if(message instanceof SmartNetOSW osw)
        {
            mOSWCount++;
            logCommands(osw);

            if(mChannelType != ChannelType.TRAFFIC && mFrequency != 0 && mFrequency != mLoggedControlFrequency)
            {
                mLoggedControlFrequency = mFrequency;
                LOGGER.info("Motorola Type II control channel decoding on " + format(mFrequency));
            }

            SmartNetOSW previous = mPrevious;
            mPrevious = osw;
            broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL));

            if(mTrafficChannelManager == null || !mBandPlan.isChannel(osw.getCommand()))
            {
                return;
            }

            long frequency = mBandPlan.getFrequency(osw.getCommand());

            if(frequency == mFrequency || frequency == getCurrentFrequency())
            {
                return; //announcements of the control channel itself
            }

            boolean grant = previous != null && previous.getCommand() == SmartNetOSW.CMD_UNIT_ID;
            MutableIdentifierCollection ic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
            ic.remove(IdentifierClass.USER);
            DecodeEventType type;
            String details = null;
            boolean follow = true;

            if(osw.isGroup())
            {
                ic.update(SmartNetTalkgroup.createTo(osw.getTalkgroup()));

                if(grant)
                {
                    ic.update(SmartNetRadio.createFrom(previous.getAddress()));
                }

                if(osw.isEncrypted())
                {
                    type = DecodeEventType.CALL_GROUP_ENCRYPTED;
                    details = "ENCRYPTED - NOT FOLLOWED";
                    follow = false;
                }
                else
                {
                    type = DecodeEventType.CALL_GROUP;
                }

                if(osw.isEmergency())
                {
                    details = details == null ? "EMERGENCY" : "EMERGENCY " + details;
                }
            }
            else if(grant)
            {
                ic.update(SmartNetRadio.createTo(osw.getAddress()));
                ic.update(SmartNetRadio.createFrom(previous.getAddress()));
                type = DecodeEventType.CALL_UNIT_TO_UNIT;
            }
            else
            {
                return; //individual channel OSW that is not a grant
            }

            if(grant && LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Motorola Type II grant: radio " + previous.getAddress() + " -> " +
                    (osw.isGroup() ? "talkgroup " + osw.getTalkgroup() + " status " + osw.getStatus() : "radio " +
                        osw.getAddress()) + " on channel " + osw.getCommand() + " " + format(frequency));
            }

            if(grant)
            {
                mGrantCount++;
                mChannelGrants.merge(osw.getCommand(), 1, Integer::sum);

                if(osw.isGroup())
                {
                    mTalkgroupGrants.merge(osw.getTalkgroup(), 1, Integer::sum);
                }
            }

            mTrafficChannelManager.processGrant(SmartNetChannel.create(osw.getCommand(), mBandPlan), ic, type,
                details, follow, osw.getTimestamp());
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
            LOGGER.info(String.format("Motorola Type II call TO:%s FROM:%s on %s - voice channel open %.1f s, " +
                    "audio %.1f s%s", first(Role.TO), first(Role.FROM), format(frequency),
                (mClock.getAsLong() - mTrafficStart) / 1000.0, mAudioSamples / AUDIO_SAMPLE_RATE,
                mTrafficEnded ? " - stopped at the call limit" : ""));
        }
    }

    private final Map<Integer,Integer> mMinuteCommands = new TreeMap<>();
    private long mMinuteStart;

    /**
     * Diagnostics (DEBUG): the commands seen each minute, most frequent first
     */
    private void logCommands(SmartNetOSW osw)
    {
        if(!LOGGER.isDebugEnabled())
        {
            return;
        }

        mMinuteCommands.merge(osw.getCommand(), 1, Integer::sum);

        if(osw.getTimestamp() - mMinuteStart >= 60_000)
        {
            if(mMinuteStart > 0)
            {
                StringBuilder sb = new StringBuilder();
                mMinuteCommands.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                    .forEach(e -> sb.append(String.format(" %03X:%d", e.getKey(), e.getValue())));
                LOGGER.debug("Motorola Type II OSW commands last minute (hex:count):" + sb);
            }

            mMinuteCommands.clear();
            mMinuteStart = osw.getTimestamp();
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
        sb.append("Activity Summary - Decoder: Motorola Type II\n");
        sb.append(DIVIDER1);
        sb.append("Band plan: ").append(mBandPlan).append("\n");
        sb.append("OSWs decoded: ").append(mOSWCount).append("\n");
        sb.append("Channel grants: ").append(mGrantCount).append("\n");
        sb.append(DIVIDER2).append("Grants by channel:\n");
        mChannelGrants.forEach((channel, count) -> sb.append(String.format("  %4d  %.4f MHz  %d%n", channel,
            mBandPlan.getFrequency(channel) / 1e6, count)));
        sb.append(DIVIDER2).append("Grants by talkgroup:\n");
        mTalkgroupGrants.forEach((talkgroup, count) -> sb.append(String.format("  %5d  %d%n", talkgroup, count)));
        return sb.toString();
    }
}
