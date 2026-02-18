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

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Motorola SmartNet/SmartZone OSW (Outbound Signaling Word) parser.
 *
 * SmartNet messages can span 1, 2, or 3 OSWs. This parser queues incoming OSWs
 * and processes them when enough context is available to determine the message type.
 *
 * Based on trunk-recorder's smartnet_parser.cc logic.
 *
 * Key protocol behaviors:
 * - One-OSW messages: IDLE (0x2F8), voice update (channel grant with group flag),
 *   group busy (0x300), emergency busy (0x303)
 * - Two-OSW messages: System ID (0x308) + channel, voice grant (0x308 + channel grant),
 *   dynamic regroup, extended functions
 * - Three-OSW messages: System ID + site info + control channel
 * - IDLE messages (0x2F8) can be interleaved between other multi-OSW sequences
 */
public class SmartNetOSWParser
{
    private static final Logger mLog = LoggerFactory.getLogger(SmartNetOSWParser.class);

    // OSW queue - we need up to 6 OSWs to handle 3-OSW messages plus interleaved IDLEs
    private static final int OSW_QUEUE_SIZE = 6;
    private Deque<OSW> mOSWQueue = new ArrayDeque<>();

    // Band plan for frequency lookups
    private SmartNetBandPlan mBandPlan;

    // Parsed system information
    private int mSystemId = 0;
    private int mSiteId = 0;
    private long mControlChannelFrequency = 0;

    // Statistics
    private long mOswCount = 0;
    private long mGrantCount = 0;
    private long mIdleCount = 0;
    private long mUnknownCount = 0;

    // Message listener
    private Listener<IMessage> mMessageListener;

    /**
     * Internal OSW data holder
     */
    private static class OSW
    {
        int addr;
        boolean grp;
        int cmd;
        boolean isChannel;
        long frequency;

        OSW(int addr, boolean grp, int cmd, boolean isChannel, long frequency)
        {
            this.addr = addr;
            this.grp = grp;
            this.cmd = cmd;
            this.isChannel = isChannel;
            this.frequency = frequency;
        }
    }

    public SmartNetOSWParser(SmartNetBandPlan bandPlan)
    {
        mBandPlan = bandPlan;
    }

    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    public int getSystemId()
    {
        return mSystemId;
    }

    public int getSiteId()
    {
        return mSiteId;
    }

    public long getControlChannelFrequency()
    {
        return mControlChannelFrequency;
    }

    /**
     * Processes a single decoded OSW from the FSK demodulator.
     *
     * @param address 16-bit address field
     * @param group group flag
     * @param command 10-bit command field
     */
    public void processOSW(int address, boolean group, int command)
    {
        mOswCount++;

        boolean isChannel = mBandPlan.isChannel(command);
        long frequency = isChannel ? mBandPlan.getFrequency(command) : 0;

        OSW osw = new OSW(address, group, command, isChannel, frequency);

        // Add to queue
        if(mOSWQueue.size() >= OSW_QUEUE_SIZE)
        {
            mOSWQueue.pollFirst();
        }
        mOSWQueue.addLast(osw);

        // Try to parse when we have enough queued
        if(mOSWQueue.size() >= 3)
        {
            parseQueuedOSWs();
        }
    }

    /**
     * Attempts to parse the oldest OSW(s) in the queue as a complete message.
     */
    private void parseQueuedOSWs()
    {
        if(mOSWQueue.size() < 1)
        {
            return;
        }

        // Peek at the oldest OSW without removing
        OSW osw = mOSWQueue.peekFirst();

        // === ONE-OSW MESSAGES ===

        // Voice channel update: channel grant with group flag
        if(osw.isChannel && osw.grp)
        {
            mOSWQueue.pollFirst();
            SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                SmartNetMessageType.VOICE_UPDATE);
            msg.setFrequency(osw.frequency);
            mGrantCount++;
            dispatch(msg);
            return;
        }

        // Control channel broadcast: channel with individual flag and 0x1F00 prefix
        if(osw.isChannel && !osw.grp && (osw.addr & 0xFF00) == 0x1F00)
        {
            mOSWQueue.pollFirst();
            mControlChannelFrequency = osw.frequency;
            SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                SmartNetMessageType.CONTROL_CHANNEL);
            msg.setFrequency(osw.frequency);
            dispatch(msg);
            return;
        }

        // System IDLE
        if(osw.cmd == 0x2F8 && !osw.grp)
        {
            mOSWQueue.pollFirst();
            mIdleCount++;
            SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                SmartNetMessageType.IDLE);
            dispatch(msg);
            return;
        }

        // Group busy queued
        if(osw.cmd == 0x300 && osw.grp)
        {
            mOSWQueue.pollFirst();
            SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                SmartNetMessageType.GROUP_BUSY);
            dispatch(msg);
            return;
        }

        // Emergency busy queued
        if(osw.cmd == 0x303 && osw.grp)
        {
            mOSWQueue.pollFirst();
            SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                SmartNetMessageType.EMERGENCY_BUSY);
            dispatch(msg);
            return;
        }

        // === TWO-OSW MESSAGES ===

        if(mOSWQueue.size() < 2)
        {
            return;
        }

        // System ID (0x308): look at the NEXT OSW to determine what follows
        if(osw.cmd == 0x308)
        {
            // Get the second OSW
            OSW[] queued = mOSWQueue.toArray(new OSW[0]);
            OSW osw1 = queued.length > 1 ? queued[1] : null;

            if(osw1 == null)
            {
                return;
            }

            // System ID + control channel broadcast
            if(osw1.isChannel && !osw1.grp && (osw1.addr & 0xFF00) == 0x1F00)
            {
                mOSWQueue.pollFirst(); // consume osw (system ID)
                mOSWQueue.pollFirst(); // consume osw1 (CC)
                mSystemId = osw.addr;
                mControlChannelFrequency = osw1.frequency;
                SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                    SmartNetMessageType.SYSTEM_ID);
                msg.setSystemId(mSystemId);
                msg.setFrequency(osw1.frequency);
                dispatch(msg);
                return;
            }

            // System ID + group voice grant (analog)
            if(osw1.isChannel && osw1.grp && osw1.addr != 0 && osw.addr != 0)
            {
                mOSWQueue.pollFirst();
                mOSWQueue.pollFirst();

                SmartNetMessage msg = new SmartNetMessage(osw1.addr, osw1.grp, osw1.cmd,
                    SmartNetMessageType.VOICE_GRANT);
                msg.setFrequency(osw1.frequency);
                msg.setSourceAddress(osw.addr);
                msg.setSystemId(mSystemId);
                mGrantCount++;
                dispatch(msg);
                return;
            }

            // System ID + IDLE
            if(osw1.cmd == 0x2F8)
            {
                mOSWQueue.pollFirst();
                mOSWQueue.pollFirst();
                mSystemId = osw.addr;
                SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                    SmartNetMessageType.SYSTEM_ID);
                msg.setSystemId(mSystemId);
                dispatch(msg);
                return;
            }

            // System ID + group busy
            if(osw1.cmd == 0x300 && osw1.grp)
            {
                mOSWQueue.pollFirst();
                mOSWQueue.pollFirst();
                SmartNetMessage msg = new SmartNetMessage(osw1.addr, osw1.grp, osw1.cmd,
                    SmartNetMessageType.GROUP_BUSY);
                msg.setSourceAddress(osw.addr);
                dispatch(msg);
                return;
            }

            // System ID + private call busy
            if(osw1.cmd == 0x302 && !osw1.grp)
            {
                mOSWQueue.pollFirst();
                mOSWQueue.pollFirst();
                SmartNetMessage msg = new SmartNetMessage(osw1.addr, osw1.grp, osw1.cmd,
                    SmartNetMessageType.PRIVATE_CALL_BUSY);
                msg.setSourceAddress(osw.addr);
                dispatch(msg);
                return;
            }

            // System ID + extended function (0x30B)
            if(osw1.cmd == 0x30B)
            {
                mOSWQueue.pollFirst();
                mOSWQueue.pollFirst();
                parseExtendedFunction(osw, osw1);
                return;
            }

            // System ID + dynamic regroup (0x30A)
            if(osw1.cmd == 0x30A && !osw1.grp && !osw.grp)
            {
                mOSWQueue.pollFirst();
                mOSWQueue.pollFirst();
                SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
                    SmartNetMessageType.DYNAMIC_REGROUP);
                dispatch(msg);
                return;
            }

            // Unknown two-OSW with System ID
            // Don't consume - let it fall through to be consumed as unknown
        }

        // === FALLTHROUGH: consume as unknown ===
        mOSWQueue.pollFirst();
        mUnknownCount++;
        SmartNetMessage msg = new SmartNetMessage(osw.addr, osw.grp, osw.cmd,
            SmartNetMessageType.UNKNOWN);
        dispatch(msg);
    }

    /**
     * Parses extended function messages (0x30B second word).
     */
    private void parseExtendedFunction(OSW systemIdOsw, OSW extFuncOsw)
    {
        SmartNetMessageType type = SmartNetMessageType.EXTENDED_FUNCTION;
        int targetAddr = systemIdOsw.addr;

        // Decode specific extended functions based on the second OSW address
        if(extFuncOsw.grp)
        {
            // Extended functions on groups
            if(extFuncOsw.addr == 0x2021)
            {
                type = SmartNetMessageType.PATCH_CANCEL;
            }
        }
        else
        {
            // Extended functions on individuals
            if(extFuncOsw.addr == 0x261B)
            {
                type = SmartNetMessageType.RADIO_CHECK;
            }
            else if(extFuncOsw.addr == 0x261C)
            {
                type = SmartNetMessageType.DEAFFILIATION;
            }
            else if(extFuncOsw.addr >= 0x26E0 && extFuncOsw.addr <= 0x26E7)
            {
                type = SmartNetMessageType.STATUS_ACK;
            }
            else if(extFuncOsw.addr == 0x26E8)
            {
                type = SmartNetMessageType.EMERGENCY_ACK;
            }
            else if(extFuncOsw.addr >= 0x26F0 && extFuncOsw.addr <= 0x26FF)
            {
                type = SmartNetMessageType.MESSAGE_ACK;
            }
            else if((extFuncOsw.addr & 0xFF00) == 0x2C00)
            {
                type = SmartNetMessageType.DENIED;
            }
        }

        SmartNetMessage msg = new SmartNetMessage(systemIdOsw.addr, systemIdOsw.grp,
            systemIdOsw.cmd, type);
        msg.setSourceAddress(targetAddr);
        dispatch(msg);
    }

    /**
     * Dispatches a parsed message to the registered listener.
     */
    private void dispatch(SmartNetMessage message)
    {
        message.setSystemId(mSystemId);
        message.setSiteId(mSiteId);

        if(mMessageListener != null)
        {
            mMessageListener.receive(message);
        }
    }

    /**
     * Gets statistics string for display.
     */
    public String getStatistics()
    {
        return String.format("OSW:%d Grants:%d Idle:%d Unknown:%d SysID:%04X Site:%d",
            mOswCount, mGrantCount, mIdleCount, mUnknownCount, mSystemId, mSiteId);
    }
}
