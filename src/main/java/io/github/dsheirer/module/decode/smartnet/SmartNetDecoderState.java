/*
 * *****************************************************************************
 * Copyright (C) 2024 SDR Trunk Contributors
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
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.module.decode.smartnet.channel.SmartNetChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * SmartNet decoder state - manages channel state based on decoded SmartNet messages.
 */
public class SmartNetDecoderState extends DecoderState
{
    private static final Logger mLog = LoggerFactory.getLogger(SmartNetDecoderState.class);

    private Set<Integer> mAllowedTalkgroups = new HashSet<>();
    private boolean mFilterEnabled = false;
    private int mSystemId = 0;
    private int mSiteId = 0;

    public SmartNetDecoderState()
    {
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.SMARTNET;
    }

    @Override
    public void receive(IMessage message)
    {
        if(message instanceof SmartNetMessage smartNetMessage)
        {
            if(smartNetMessage.getSystemId() != 0)
            {
                mSystemId = smartNetMessage.getSystemId();
            }
            if(smartNetMessage.getSiteId() != 0)
            {
                mSiteId = smartNetMessage.getSiteId();
            }

            switch(smartNetMessage.getMessageType())
            {
                case VOICE_GRANT:
                    processVoiceGrant(smartNetMessage);
                    break;
                case VOICE_UPDATE:
                    processVoiceUpdate(smartNetMessage);
                    break;
                case SYSTEM_ID:
                    processSystemId(smartNetMessage);
                    break;
                case IDLE:
                    break;
                case GROUP_BUSY:
                    processGroupBusy(smartNetMessage);
                    break;
                case EMERGENCY_BUSY:
                    processEmergency(smartNetMessage);
                    break;
                default:
                    break;
            }
        }
    }

    private void processVoiceGrant(SmartNetMessage message)
    {
        int talkgroup = message.getTalkgroupRaw();

        if(mFilterEnabled && !mAllowedTalkgroups.isEmpty() && !mAllowedTalkgroups.contains(talkgroup))
        {
            return;
        }

        broadcast(new DecoderStateEvent(this, Event.DECODE, State.CALL));

        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, System.currentTimeMillis())
            .identifiers(new IdentifierCollection(message.getIdentifiers()))
            .protocol(Protocol.SMARTNET)
            .channel(new SmartNetChannel(message.getCommand(), message.getFrequency()))
            .details("SmartNet Voice Grant" +
                (message.isEncrypted() ? " [ENCRYPTED]" : "") +
                (message.isEmergency() ? " [EMERGENCY]" : ""))
            .build();

        broadcast(event);
    }

    private void processVoiceUpdate(SmartNetMessage message)
    {
        int talkgroup = message.getTalkgroupRaw();

        if(mFilterEnabled && !mAllowedTalkgroups.isEmpty() && !mAllowedTalkgroups.contains(talkgroup))
        {
            return;
        }

        broadcast(new DecoderStateEvent(this, Event.DECODE, State.CALL));

        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, System.currentTimeMillis())
            .identifiers(new IdentifierCollection(message.getIdentifiers()))
            .protocol(Protocol.SMARTNET)
            .channel(new SmartNetChannel(message.getCommand(), message.getFrequency()))
            .details("SmartNet Voice Update" +
                (message.isEncrypted() ? " [ENCRYPTED]" : "") +
                (message.isEmergency() ? " [EMERGENCY]" : ""))
            .build();

        broadcast(event);
    }

    private void processSystemId(SmartNetMessage message)
    {
        broadcast(new DecoderStateEvent(this, Event.DECODE, State.CONTROL));

        mLog.debug("SmartNet System ID: {} Site: {}",
            String.format("0x%04X", message.getSystemId()), message.getSiteId());
    }

    private void processGroupBusy(SmartNetMessage message)
    {
        broadcast(new DecoderStateEvent(this, Event.DECODE, State.CONTROL));
    }

    private void processEmergency(SmartNetMessage message)
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.EMERGENCY, System.currentTimeMillis())
            .identifiers(new IdentifierCollection(message.getIdentifiers()))
            .protocol(Protocol.SMARTNET)
            .details("SmartNet Emergency")
            .build();

        broadcast(event);
    }

    public void setAllowedTalkgroups(Set<Integer> talkgroups)
    {
        mAllowedTalkgroups = talkgroups != null ? talkgroups : new HashSet<>();
        mFilterEnabled = !mAllowedTalkgroups.isEmpty();
    }

    public void setFilterEnabled(boolean enabled)
    {
        mFilterEnabled = enabled;
    }

    @Override
    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("=============================\n");
        sb.append(" SmartNet Activity Summary\n");
        sb.append("=============================\n");
        sb.append(" System ID: ").append(String.format("0x%04X", mSystemId)).append("\n");
        sb.append(" Site ID: ").append(mSiteId).append("\n");
        if(mFilterEnabled)
        {
            sb.append(" Talkgroup Filter: ").append(mAllowedTalkgroups.size()).append(" groups\n");
        }
        return sb.toString();
    }

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_RESET:
                resetState();
                break;
            default:
                break;
        }
    }

    @Override
    public void init()
    {
    }

    @Override
    public void reset()
    {
        super.reset();
        resetState();
    }

    protected void resetState()
    {
        super.resetState();
    }
}