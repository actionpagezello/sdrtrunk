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

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (SmartNet): grant pairing, call following and voice channel teardown, with values seen on Massachusetts
 * State Police Metro Boston Simulcast: control channel 854.5625 MHz (channel 142), talkgroup 33200 granted to radio
 * 52612 on channel 228 (856.7125 MHz).
 */
public class SmartNetCallFollowingTest
{
    private static final long CONTROL = 854_562_500L;
    private static final int CHANNEL = 228;
    private static final long VOICE = 856_712_500L;
    private static final int TALKGROUP = 33200;
    private static final int RADIO = 52612;

    private final long[] mNow = {1_000_000L};
    private final List<ChannelStartProcessingRequest> mStarts = new ArrayList<>();
    private final List<ChannelEvent> mChannelEvents = new ArrayList<>();
    private final List<IDecodeEvent> mEvents = new ArrayList<>();
    private SmartNetTrafficChannelManager mManager;
    private SmartNetDecoderState mState;

    private void setup(int poolSize)
    {
        DecodeConfigSmartNet config = new DecodeConfigSmartNet();
        config.setTrafficChannelPoolSize(poolSize);
        Channel control = new Channel("MSP", Channel.ChannelType.STANDARD);
        control.setDecodeConfiguration(config);

        mManager = new SmartNetTrafficChannelManager(control);
        mManager.setClock(() -> mNow[0]);
        EventBus bus = new EventBus();
        bus.register(new Object()
        {
            @Subscribe
            public void start(ChannelStartProcessingRequest request)
            {
                mStarts.add(request);
            }
        });
        mManager.setInterModuleEventBus(bus);
        mManager.setChannelEventListener(mChannelEvents::add);
        mManager.addDecodeEventListener(mEvents::add);

        mState = new SmartNetDecoderState(Channel.ChannelType.STANDARD, mManager, config);
        mState.receiveDecoderStateEvent(new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_SOURCE_FREQUENCY,
            State.CONTROL, CONTROL));
    }

    private void osw(int address, boolean group, int command)
    {
        mState.receive(new SmartNetOSW(address, group, command, mNow[0]));
    }

    private void grant(int radio, int talkgroupAddress, int channel)
    {
        osw(radio, false, SmartNetOSW.CMD_UNIT_ID);
        osw(talkgroupAddress, true, channel);
    }

    private void advance(long ms)
    {
        mNow[0] += ms;
        mManager.getHeartbeatListener().receive(new Heartbeat());
    }

    private static Identifier get(IdentifierCollection collection, Role role)
    {
        List<Identifier> identifiers = collection.getIdentifiers(role);
        return identifiers.isEmpty() ? null : identifiers.get(0);
    }

    /**
     * Test: a grant starts one voice channel on the band plan frequency with talkgroup and radio, updates keep it,
     * and it is torn down once the updates stop.
     */
    @Test
    void testGrantUpdatesAndTeardown()
    {
        setup(4);
        osw(0x2F8, true, SmartNetOSW.CMD_IDLE);
        grant(RADIO, TALKGROUP, CHANNEL);

        assertEquals(1, mStarts.size());
        ChannelStartProcessingRequest start = mStarts.get(0);
        assertEquals(VOICE, ((SourceConfigTuner)start.getChannel().getSourceConfiguration()).getFrequency());
        assertEquals(VOICE, start.getChannelDescriptor().getDownlinkFrequency());
        Identifier to = get(start.getIdentifierCollection(), Role.TO);
        Identifier from = get(start.getIdentifierCollection(), Role.FROM);
        assertEquals(TALKGROUP, to.getValue());
        assertEquals(Protocol.SMARTNET, to.getProtocol());
        assertEquals(RADIO, from.getValue());
        assertEquals(DecodeEventType.CALL_GROUP, mEvents.get(0).getEventType());

        //Updates (channel OSW without the 0x308 before it) and a repeat grant keep the same call
        for(int x = 0; x < 10; x++)
        {
            advance(400);
            osw(TALKGROUP, true, CHANNEL);
        }
        grant(RADIO, TALKGROUP, CHANNEL);
        assertEquals(1, mStarts.size());
        assertEquals(1, mManager.getCallCount());
        assertTrue(mChannelEvents.isEmpty());

        //Updates stop - torn down after CALL_END_MS
        advance(SmartNetTrafficChannelManager.CALL_END_MS - 500);
        assertTrue(mChannelEvents.isEmpty());
        advance(1000);
        assertEquals(1, mChannelEvents.size());
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, mChannelEvents.get(0).getEvent());
        assertEquals(start.getChannel(), mChannelEvents.get(0).getChannel());
        assertEquals(0, mManager.getCallCount());
        assertTrue(mEvents.get(mEvents.size() - 1).getDuration() > 0);
    }

    /**
     * Test: the voice channel returns to the pool only once it reports that processing stopped.
     */
    @Test
    void testPool()
    {
        setup(1);
        grant(RADIO, TALKGROUP, CHANNEL);
        grant(1234, 33168, 238);
        assertEquals(1, mStarts.size());
        assertTrue(mEvents.get(mEvents.size() - 1).getDetails().contains(
            SmartNetTrafficChannelManager.MAX_TRAFFIC_CHANNELS_EXCEEDED));

        advance(SmartNetTrafficChannelManager.CALL_END_MS + 500);
        Channel voice = mStarts.get(0).getChannel();
        grant(RADIO, TALKGROUP, CHANNEL);
        assertEquals(1, mStarts.size(), "not reused before it has stopped");

        mManager.getChannelEventListener().receive(new ChannelEvent(voice,
            ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
        advance(SmartNetTrafficChannelManager.CALL_END_MS + 500);
        grant(RADIO, TALKGROUP, CHANNEL);
        assertEquals(2, mStarts.size());
        assertEquals(voice, mStarts.get(1).getChannel());
    }

    /**
     * Test: another talkgroup granted on a channel ends the previous call there and starts the new one.
     */
    @Test
    void testChannelReassigned()
    {
        setup(4);
        grant(RADIO, TALKGROUP, CHANNEL);
        grant(1234, 33168, CHANNEL);
        assertEquals(1, mChannelEvents.size());
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, mChannelEvents.get(0).getEvent());
        assertEquals(2, mStarts.size());
        assertEquals(33168, get(mStarts.get(1).getIdentifierCollection(), Role.TO).getValue());
    }

    /**
     * Test: the talkgroup's next transmission granted on another channel closes the previous voice channel at once, so
     * the two calls never overlap (overlap made duplicate call detection suppress the new one).
     */
    @Test
    void testReplyOnAnotherChannel()
    {
        setup(4);
        grant(RADIO, TALKGROUP, CHANNEL);
        advance(1000);
        grant(28366, TALKGROUP, 268);
        assertEquals(2, mStarts.size());
        assertEquals(1, mChannelEvents.size());
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, mChannelEvents.get(0).getEvent());
        assertEquals(mStarts.get(0).getChannel(), mChannelEvents.get(0).getChannel());
        assertEquals(1, mManager.getCallCount());

        //Another talkgroup on another channel is left alone
        grant(1234, 33168, 238);
        assertEquals(1, mChannelEvents.size());
        assertEquals(2, mManager.getCallCount());
    }

    /**
     * Test: encrypted calls are listed but not followed; control channel announcements are ignored; a private call is
     * radio to radio.
     */
    @Test
    void testNotFollowed()
    {
        setup(4);
        grant(RADIO, TALKGROUP | 0x8, CHANNEL);
        assertEquals(0, mStarts.size());
        assertEquals(DecodeEventType.CALL_GROUP_ENCRYPTED, mEvents.get(0).getEventType());

        //Seen on MSP: individual OSWs with the control channel number, and a grant for the control channel
        osw(0x1FD4, false, 142);
        grant(RADIO, 33168, 142);
        assertEquals(0, mStarts.size());
        assertEquals(1, mEvents.size());

        //Individual channel OSW without the 0x308 is not a call
        osw(4321, false, 238);
        assertEquals(1, mEvents.size());

        osw(RADIO, false, SmartNetOSW.CMD_UNIT_ID);
        osw(4321, false, 238);
        assertEquals(1, mStarts.size());
        assertEquals(DecodeEventType.CALL_UNIT_TO_UNIT, mEvents.get(1).getEventType());
        Identifier to = get(mStarts.get(0).getIdentifierCollection(), Role.TO);
        assertEquals(4321, to.getValue());
        assertEquals(io.github.dsheirer.identifier.Form.RADIO, to.getForm());
    }

    /**
     * Test: a call joined from its updates gets its calling radio from a later grant, without a second channel.
     */
    @Test
    void testLateJoin()
    {
        setup(4);
        osw(TALKGROUP, true, CHANNEL);
        assertEquals(1, mStarts.size());
        assertNull(get(mStarts.get(0).getIdentifierCollection(), Role.FROM));
        grant(RADIO, TALKGROUP, CHANNEL);
        assertEquals(1, mStarts.size());
        Identifier from = get(mEvents.get(mEvents.size() - 1).getIdentifierCollection(), Role.FROM);
        assertNotNull(from);
        assertEquals(RADIO, from.getValue());
    }

    /**
     * Test: a voice channel opens its audio on start, keeps the call going, and ends it at the call limit.
     */
    @Test
    void testVoiceChannelState()
    {
        DecodeConfigSmartNet config = new DecodeConfigSmartNet();
        config.setCallTimeoutSeconds(10);
        SmartNetDecoderState state = new SmartNetDecoderState(Channel.ChannelType.TRAFFIC, null,
            config.copyForTrafficChannel());
        state.setClock(() -> mNow[0]);
        List<DecoderStateEvent> events = new ArrayList<>();
        state.setDecoderStateListener(events::add);
        state.start();
        assertEquals(DecoderStateEvent.Event.START, events.get(0).getEvent());
        assertEquals(State.CALL, events.get(0).getState());

        for(int x = 0; x < 9; x++)
        {
            mNow[0] += 1000;
            state.getHeartbeatListener().receive(new Heartbeat());
        }
        assertEquals(DecoderStateEvent.Event.CONTINUATION, events.get(events.size() - 1).getEvent());

        mNow[0] += 2000;
        state.getHeartbeatListener().receive(new Heartbeat());
        assertEquals(DecoderStateEvent.Event.END, events.get(events.size() - 1).getEvent());
        int count = events.size();
        mNow[0] += 2000;
        state.getHeartbeatListener().receive(new Heartbeat());
        assertEquals(count, events.size(), "ends once");

        //Logs one line on stop, with the audio that got past the squelch
        state.getBufferListener().receive(new float[8000]);
        state.stop();
    }

    /**
     * Test: voice channels use the 25 kHz analog filter, the control channel the narrower one.
     */
    @Test
    void testChannelSpecification()
    {
        DecodeConfigSmartNet config = new DecodeConfigSmartNet();
        assertEquals(12500, config.getChannelSpecification().getBandwidth());
        assertEquals(25000, config.copyForTrafficChannel().getChannelSpecification().getBandwidth());

        //Voice channels get the equalizer settings
        config.setGraphicEQEnabled(true);
        config.setGraphicEQBandGains(new double[]{-6, 0, 0, 0, 0, 0, 0, 0, 0, 3});
        DecodeConfigSmartNet voice = config.copyForTrafficChannel();
        assertTrue(voice.isGraphicEQEnabled());
        assertEquals(-6.0, voice.getGraphicEQBandGains()[0]);
        assertEquals(3.0, voice.getGraphicEQBandGains()[9]);
    }

    /**
     * Test: channel events for other systems' channels never wait on the manager's lock.  Cambridge 2026-11-10 08:45:
     * the EDACS control channel thread held the manager's lock while asking the channel processing manager to start a
     * voice channel, while a P25 channel thread held the channel processing manager's lock and was delivering a P25
     * channel event to this manager - a deadlock that stopped both systems.
     */
    @Test
    void testNoDeadlockWithChannelProcessingManager() throws Exception
    {
        setup(2);
        grant(RADIO, TALKGROUP, CHANNEL);
        assertEquals(1, mStarts.size());
        Channel voice = mStarts.get(0).getChannel();
        advance(SmartNetTrafficChannelManager.CALL_END_MS + 500);
        assertEquals(ChannelEvent.Event.REQUEST_DISABLE, mChannelEvents.get(mChannelEvents.size() - 1).getEvent());
        mManager.getChannelEventListener().receive(new ChannelEvent(voice, ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));

        //Another thread holds the manager's lock - an event for another system's channel must not wait for it
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized(mManager)
            {
                locked.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException ie)
                {
                    //done
                }
            }
        });
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        Channel other = new Channel("P25", Channel.ChannelType.TRAFFIC);
        CountDownLatch delivered = new CountDownLatch(1);
        Thread p25 = new Thread(() -> {
            mManager.getChannelEventListener().receive(new ChannelEvent(other, ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
            delivered.countDown();
        });
        p25.start();
        boolean done = delivered.await(2, TimeUnit.SECONDS);
        release.countDown();
        holder.join(5000);
        p25.join(5000);
        assertTrue(done, "event for another channel waited on the manager's lock");
    }
}
