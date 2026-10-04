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
package io.github.dsheirer.module.decode.analog;

import io.github.dsheirer.channel.IBandChannelDescriptor;
import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract analog decoder channel state - provides the minimum channel state functionality
 */
public abstract class AnalogDecoderState extends DecoderState implements ISourceEventListener
{
    private final static Logger mLog = LoggerFactory.getLogger(AnalogDecoderState.class);
    private Listener<SourceEvent> mSourceEventListener = new SourceEventListener();
    private DecodeEvent mDecodeEvent;
    private IBandChannelDescriptor mChannelDescriptor = null;

    public AnalogDecoderState()
    {
    }

    /**
     * Channel name identifier provided by the subclass implementation.
     */
    protected abstract Identifier getChannelNameIdentifier();

    protected abstract Identifier getTalkgroupIdentifier();

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_RESET ->
                    {
                        getIdentifierCollection().update(getChannelNameIdentifier());
                        //AP-fork: see endCallEvent() - the configured talkgroup is static configuration
                        //and belongs in the collection whether or not a call is in progress.
                        getIdentifierCollection().update(getTalkgroupIdentifier());
                    }
            case START ->
                    {
                        if(event.getState() == State.CALL)
                        {
                            startCallEvent();
                        }
                    }
            case END ->
                    {
                        if(event.getState() == State.CALL)
                        {
                            endCallEvent();
                        }
                    }
            case CONTINUATION ->
                    {
                        if(event.getState() == State.CALL)
                        {
                            continueCallEvent();
                        }
                        else
                        {
                            endCallEvent();
                        }
                    }
        }
    }

    /**
     * Creates/starts a decode call evnet.
     */
    private void startCallEvent()
    {
        getIdentifierCollection().update(getTalkgroupIdentifier());

        if(mDecodeEvent == null)
        {
            mDecodeEvent = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis())
                    .channel(mChannelDescriptor)
                    .details(getDecoderType().name())
                    .identifiers(new IdentifierCollection(getIdentifierCollection().getIdentifiers()))
                    .build();

            broadcast(mDecodeEvent);
        }
    }

    /**
     * Continues (or starts) the call decode event and updates the current timestamp
     */
    private void continueCallEvent()
    {
        if(mDecodeEvent == null)
        {
            startCallEvent();
        }

        getIdentifierCollection().update(getTalkgroupIdentifier());
        mDecodeEvent.update(System.currentTimeMillis());
        broadcast(mDecodeEvent);
    }

    /**
     * Ends the call decode event
     */
    private void endCallEvent()
    {
        if(mDecodeEvent != null)
        {
            mDecodeEvent.end(System.currentTimeMillis());
            broadcast(mDecodeEvent);
            mDecodeEvent = null;
        }

        //Remove any user identifiers from the identifier collection
        resetState();

        //AP-fork: put the configured talkgroup straight back. resetState() is
        //mIdentifierCollection.remove(IdentifierClass.USER), and a TalkgroupIdentifier is USER class, so
        //it takes the talkgroup with it - which blanks the "To" column in the Now Playing window the
        //instant a transmission ends, showing "-----" against a row that is still highlighted through
        //the CALL-to-FADE hang period.
        //
        //For a conventional analog channel that is wrong on its own terms: the talkgroup comes from
        //DecodeConfigAnalog.getTalkgroup(), so it is static configuration describing what the channel IS,
        //not a decoded identity that arrives and departs with each call. Clearing it is right for a
        //trunked system, where the live talkgroup genuinely stops being meaningful; it is not right
        //here.
        //
        //This also restores what ap-15.9.6 and earlier appeared to do. Back then the swallowed
        //SquelchState.SQUELCH meant notifyCallEnd() often never ran, so the talkgroup simply stayed on
        //screen - the display looked correct because the state machine was broken. ap-15.9.7 fixed the
        //squelch close and the column started blanking properly, which is when this surfaced.
        getIdentifierCollection().update(getTalkgroupIdentifier());
    }

    @Override
    public void start()
    {
        super.start();
        getIdentifierCollection().update(getChannelNameIdentifier());
        //AP-fork: present from the moment the channel starts, not only once a call has been decoded.
        getIdentifierCollection().update(getTalkgroupIdentifier());
    }

    @Override
    public void init() {}
    @Override
    public void receive(IMessage t) {}

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventListener;
    }

    /**
     * Monitors source events to capture the channel frequency for use in decode events.
     */
    private class SourceEventListener implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE)
            {
                mChannelDescriptor = new StandardChannel(sourceEvent.getValue().longValue());
            }
        }
    }
}
