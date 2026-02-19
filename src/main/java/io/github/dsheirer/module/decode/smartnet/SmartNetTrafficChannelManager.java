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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.IChannelEventListener;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.decode.smartnet.channel.SmartNetChannel;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SmartNet/SmartZone traffic channel manager. Allocates traffic channels when voice grants
 * are detected on the control channel, and reclaims them when calls end.
 */
public class SmartNetTrafficChannelManager extends TrafficChannelManager implements IDecodeEventProvider,
    IChannelEventListener, IChannelEventProvider
{
    private final static Logger mLog = LoggerFactory.getLogger(SmartNetTrafficChannelManager.class);

    public static final String CHANNEL_START_REJECTED = "CHANNEL START REJECTED";
    public static final String MAX_TRAFFIC_CHANNELS_EXCEEDED = "MAX TRAFFIC CHANNELS EXCEEDED";

    private Queue<Channel> mAvailableTrafficChannelQueue = new ConcurrentLinkedQueue<>();
    private List<Channel> mManagedTrafficChannels;
    private Map<Long,Channel> mAllocatedTrafficChannelMap = new ConcurrentHashMap<>();
    private Map<Long,SmartNetChannelGrantEvent> mChannelGrantEventMap = new ConcurrentHashMap<>();
    private TrafficChannelTeardownMonitor mTrafficChannelTeardownMonitor = new TrafficChannelTeardownMonitor();
    private Listener<ChannelEvent> mChannelEventListener;
    private Listener<IDecodeEvent> mDecodeEventListener;
    private int mMaxTrafficChannels;

    /**
     * Constructs a SmartNet traffic channel manager.
     *
     * @param parentChannel containing configuration items for traffic channels to inherit
     */
    public SmartNetTrafficChannelManager(Channel parentChannel)
    {
        mMaxTrafficChannels = 3; // Default; can be made configurable later

        DecodeConfiguration decodeConfig = parentChannel.getDecodeConfiguration();
        if(decodeConfig instanceof DecodeConfigSmartNet smartNetConfig)
        {
            mMaxTrafficChannels = smartNetConfig.getTrafficChannelPoolSize();
        }

        createTrafficChannels(parentChannel);
    }

    @Override
    protected void processControlFrequencyUpdate(long previous, long current, Channel channel)
    {
        Long toRemove = null;

        for(Long freq : mAllocatedTrafficChannelMap.keySet())
        {
            if(freq == current)
            {
                toRemove = freq;
                break;
            }
        }

        if(toRemove != null)
        {
            broadcast(new ChannelEvent(mAllocatedTrafficChannelMap.get(toRemove), ChannelEvent.Event.REQUEST_DISABLE));
        }
    }

    /**
     * Processes a voice channel grant from the SmartNet control channel. Allocates a traffic channel
     * to the granted frequency and starts an NBFM decoder on it.
     *
     * @param message the SmartNet message containing the voice grant
     */
    public void processChannelGrant(SmartNetMessage message)
    {
        if(message.getFrequency() == 0)
        {
            return;
        }

        long frequency = message.getFrequency();
        IdentifierCollection identifierCollection = new IdentifierCollection(message.getIdentifiers());

        SmartNetChannelGrantEvent existingEvent = mChannelGrantEventMap.get(frequency);

        if(existingEvent != null)
        {
            if(isSameTalkgroup(identifierCollection, existingEvent.getIdentifierCollection()))
            {
                // Same talkgroup on same frequency - just update the timestamp
                existingEvent.end(System.currentTimeMillis());
                return;
            }
            else if(mAllocatedTrafficChannelMap.containsKey(frequency))
            {
                // Different talkgroup on same frequency - tear down old channel
                Channel trafficChannel = mAllocatedTrafficChannelMap.get(frequency);
                broadcast(new ChannelEvent(trafficChannel, ChannelEvent.Event.REQUEST_DISABLE));
            }
        }

        SmartNetChannel smartNetChannel = new SmartNetChannel(message.getCommand(), frequency);

        SmartNetChannelGrantEvent channelGrantEvent = SmartNetChannelGrantEvent
            .smartNetBuilder(DecodeEventType.CALL_GROUP, System.currentTimeMillis())
            .channel(smartNetChannel)
            .details("SmartNet Voice Grant" + getStatusFlags(message))
            .identifiers(identifierCollection)
            .protocol(Protocol.SMARTNET)
            .build();

        mChannelGrantEventMap.put(frequency, channelGrantEvent);

        Channel trafficChannel = mAvailableTrafficChannelQueue.poll();

        if(trafficChannel == null)
        {
            channelGrantEvent.setDetails(MAX_TRAFFIC_CHANNELS_EXCEEDED);
            broadcast(channelGrantEvent);
            return;
        }

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(frequency);
        trafficChannel.setSourceConfiguration(sourceConfig);
        mAllocatedTrafficChannelMap.put(frequency, trafficChannel);

        getInterModuleEventBus().post(new ChannelStartProcessingRequest(trafficChannel, smartNetChannel,
            identifierCollection));

        broadcast(channelGrantEvent);
    }

    /**
     * Returns status flag string for display in decode events.
     */
    private String getStatusFlags(SmartNetMessage message)
    {
        StringBuilder sb = new StringBuilder();
        if(message.isEncrypted()) sb.append(" [ENCRYPTED]");
        if(message.isEmergency()) sb.append(" [EMERGENCY]");
        return sb.toString();
    }

    /**
     * Creates traffic channels for voice channel following.
     */
    private void createTrafficChannels(Channel parentChannel)
    {
        List<Channel> trafficChannelList = new ArrayList<>();

        for(int x = 0; x < mMaxTrafficChannels; x++)
        {
            Channel trafficChannel = new Channel("T-" + parentChannel.getName(), ChannelType.TRAFFIC);
            trafficChannel.setAliasListName(parentChannel.getAliasListName());
            trafficChannel.setSystem(parentChannel.getSystem());
            trafficChannel.setSite(parentChannel.getSite());
            trafficChannel.setDecodeConfiguration(new DecodeConfigNBFM());
            trafficChannel.setEventLogConfiguration(parentChannel.getEventLogConfiguration());
            trafficChannel.setRecordConfiguration(parentChannel.getRecordConfiguration());
            trafficChannelList.add(trafficChannel);
        }

        mAvailableTrafficChannelQueue.addAll(trafficChannelList);
        mManagedTrafficChannels = Collections.unmodifiableList(trafficChannelList);
    }

    /**
     * Broadcasts a decode event to registered listener.
     */
    public void broadcast(DecodeEvent decodeEvent)
    {
        if(mDecodeEventListener != null)
        {
            mDecodeEventListener.receive(decodeEvent);
        }
    }

    /**
     * Broadcasts a channel event to registered listener.
     */
    private void broadcast(ChannelEvent channelEvent)
    {
        if(mChannelEventListener != null)
        {
            mChannelEventListener.receive(channelEvent);
        }
    }

    @Override
    public Listener<ChannelEvent> getChannelEventListener()
    {
        return mTrafficChannelTeardownMonitor;
    }

    @Override
    public void setChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventListener = listener;
    }

    @Override
    public void removeChannelEventListener()
    {
        mChannelEventListener = null;
    }

    @Override
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
    }

    @Override
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = null;
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
        mAvailableTrafficChannelQueue.clear();
        List<Channel> channels = new ArrayList<>(mAllocatedTrafficChannelMap.values());

        for(Channel channel : channels)
        {
            mLog.debug("Stopping SmartNet traffic channel: " + channel);
            broadcast(new ChannelEvent(channel, ChannelEvent.Event.REQUEST_DISABLE));
        }
    }

    /**
     * Compares TO identifiers between two collections.
     */
    private boolean isSameTalkgroup(IdentifierCollection collection1, IdentifierCollection collection2)
    {
        Identifier to1 = getToIdentifier(collection1);
        Identifier to2 = getToIdentifier(collection2);
        return Objects.equals(to1, to2);
    }

    private Identifier getToIdentifier(IdentifierCollection collection)
    {
        List<Identifier> identifiers = collection.getIdentifiers(Role.TO);
        if(identifiers.size() >= 1)
        {
            return identifiers.get(0);
        }
        return null;
    }

    /**
     * Monitors channel teardown events to reclaim traffic channels.
     */
    public class TrafficChannelTeardownMonitor implements Listener<ChannelEvent>
    {
        @Override
        public synchronized void receive(ChannelEvent channelEvent)
        {
            Channel channel = channelEvent.getChannel();

            if(channel.isTrafficChannel() && mManagedTrafficChannels.contains(channel))
            {
                switch(channelEvent.getEvent())
                {
                    case NOTIFICATION_PROCESSING_STOP:
                        Long toRemove = frequencyForChannel(channel);

                        if(toRemove != null)
                        {
                            mAllocatedTrafficChannelMap.remove(toRemove);
                            mAvailableTrafficChannelQueue.add(channel);

                            SmartNetChannelGrantEvent event = mChannelGrantEventMap.remove(toRemove);

                            if(event != null)
                            {
                                event.end(System.currentTimeMillis());
                                broadcast(event);
                            }
                        }
                        break;
                    case NOTIFICATION_PROCESSING_START_REJECTED:
                        Long rejected = frequencyForChannel(channel);

                        if(rejected != null)
                        {
                            mAllocatedTrafficChannelMap.remove(rejected);
                            mAvailableTrafficChannelQueue.add(channel);

                            SmartNetChannelGrantEvent event = mChannelGrantEventMap.remove(rejected);

                            if(event != null)
                            {
                                if(channelEvent.getDescription() != null)
                                {
                                    event.setDetails(channelEvent.getDescription() + " - " + event.getDetails());
                                }
                                else
                                {
                                    event.setDetails(CHANNEL_START_REJECTED + " - " + event.getDetails());
                                }
                                broadcast(event);
                            }
                        }
                        break;
                }
            }
        }

        private Long frequencyForChannel(Channel channel)
        {
            for(Map.Entry<Long,Channel> entry : mAllocatedTrafficChannelMap.entrySet())
            {
                if(entry.getValue() == channel)
                {
                    return entry.getKey();
                }
            }
            return null;
        }
    }

    /**
     * SmartNet channel grant event that extends DecodeEvent for tracking grant state.
     */
    public static class SmartNetChannelGrantEvent extends DecodeEvent
    {
        protected SmartNetChannelGrantEvent(DecodeEventType decodeEventType, long timestamp)
        {
            super(decodeEventType, timestamp);
        }

        public static DecodeEventBuilder smartNetBuilder(DecodeEventType decodeEventType, long timestamp)
        {
            return new DecodeEventBuilder(decodeEventType, timestamp);
        }

        public static class DecodeEventBuilder
        {
            private DecodeEventType mDecodeEventType;
            private long mTimeStart;
            private long mDuration;
            private IdentifierCollection mIdentifierCollection;
            private SmartNetChannel mChannel;
            private String mDetails;
            private Protocol mProtocol;

            public DecodeEventBuilder(DecodeEventType decodeEventType, long timestamp)
            {
                mDecodeEventType = decodeEventType;
                mTimeStart = timestamp;
            }

            public DecodeEventBuilder channel(SmartNetChannel channel)
            {
                mChannel = channel;
                return this;
            }

            public DecodeEventBuilder details(String details)
            {
                mDetails = details;
                return this;
            }

            public DecodeEventBuilder identifiers(IdentifierCollection identifierCollection)
            {
                mIdentifierCollection = identifierCollection;
                return this;
            }

            public DecodeEventBuilder protocol(Protocol protocol)
            {
                mProtocol = protocol;
                return this;
            }

            public SmartNetChannelGrantEvent build()
            {
                SmartNetChannelGrantEvent event = new SmartNetChannelGrantEvent(mDecodeEventType, mTimeStart);
                event.setDetails(mDetails);
                event.setIdentifierCollection(mIdentifierCollection);
                if(mChannel != null) event.setChannelDescriptor(mChannel);
                if(mProtocol != null) event.setProtocol(mProtocol);
                return event;
            }
        }
    }
}
