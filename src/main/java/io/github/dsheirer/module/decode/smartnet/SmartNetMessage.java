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

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.module.decode.smartnet.identifier.SmartNetTalkgroup;
import io.github.dsheirer.module.decode.smartnet.identifier.SmartNetRadioId;
import io.github.dsheirer.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Motorola SmartNet/SmartZone Outbound Signaling Word (OSW) message.
 *
 * An OSW contains 27 information bits:
 *   - 16-bit address (talkgroup or radio ID)
 *   - 1-bit group flag (1=group, 0=individual)
 *   - 10-bit command (channel number or status command)
 */
public class SmartNetMessage extends Message
{
    // Raw OSW fields
    private int mAddress;
    private boolean mGroup;
    private int mCommand;

    // Parsed fields
    private SmartNetMessageType mMessageType;
    private long mFrequency;
    private int mSourceAddress;
    private int mSystemId;
    private int mSiteId;
    private boolean mEncrypted;
    private boolean mEmergency;

    // Identifiers
    private SmartNetTalkgroup mTalkgroup;
    private SmartNetRadioId mRadioId;
    private List<Identifier> mIdentifiers;

    // Raw binary message (optional, for display)
    private CorrectedBinaryMessage mBinaryMessage;

    /**
     * Constructs a SmartNet message from raw OSW fields.
     *
     * @param address 16-bit address field
     * @param group group flag
     * @param command 10-bit command field
     * @param messageType parsed message type
     */
    public SmartNetMessage(int address, boolean group, int command, SmartNetMessageType messageType)
    {
        mAddress = address;
        mGroup = group;
        mCommand = command;
        mMessageType = messageType;
    }

    /**
     * Constructs a SmartNet message from a CorrectedBinaryMessage.
     */
    public SmartNetMessage(CorrectedBinaryMessage message, SmartNetMessageType messageType)
    {
        mBinaryMessage = message;
        // Extract fields from the binary message
        // Bits 0-15: address, Bit 16: group, Bits 17-26: command
        mAddress = message.getInt(0, 15);
        mGroup = message.get(16);
        mCommand = message.getInt(17, 26);
        mMessageType = messageType;
    }

    public int getAddress()
    {
        return mAddress;
    }

    public boolean isGroup()
    {
        return mGroup;
    }

    public int getCommand()
    {
        return mCommand;
    }

    public SmartNetMessageType getMessageType()
    {
        return mMessageType;
    }

    public void setMessageType(SmartNetMessageType type)
    {
        mMessageType = type;
    }

    /**
     * Gets the voice channel frequency in Hz (for channel grant/update messages).
     */
    public long getFrequency()
    {
        return mFrequency;
    }

    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    /**
     * Gets the talkgroup address with status bits stripped.
     * SmartNet talkgroups use the upper 12 bits of the 16-bit address.
     * Lower 4 bits are status: bit3=encrypted, bits2-0=options
     */
    public int getTalkgroupId()
    {
        return mAddress & 0xFFF0;
    }

    /**
     * Gets the raw talkgroup address including status bits (as displayed by RadioReference DEC column).
     */
    public int getTalkgroupRaw()
    {
        return mAddress;
    }

    /**
     * Gets the talkgroup status bits (lower 4 bits of address).
     */
    public int getStatusBits()
    {
        return mAddress & 0x000F;
    }

    /**
     * Determines if the talkgroup is encrypted based on status bit 3.
     */
    public boolean isEncrypted()
    {
        return mEncrypted || ((mAddress & 0x8) != 0);
    }

    public void setEncrypted(boolean encrypted)
    {
        mEncrypted = encrypted;
    }

    /**
     * Determines if this is an emergency call based on status options.
     * Emergency options: 2, 4, 5
     */
    public boolean isEmergency()
    {
        if(mEmergency)
        {
            return true;
        }

        int options = mAddress & 0x7;
        return options == 2 || options == 4 || options == 5;
    }

    public void setEmergency(boolean emergency)
    {
        mEmergency = emergency;
    }

    /**
     * Determines if this is a patch group (options 3 or 4).
     */
    public boolean isPatchGroup()
    {
        int options = mAddress & 0x7;
        return options == 3 || options == 4;
    }

    /**
     * Determines if this is a multiselect group (options 5 or 7).
     */
    public boolean isMultiselectGroup()
    {
        int options = mAddress & 0x7;
        return options == 5 || options == 7;
    }

    public int getSourceAddress()
    {
        return mSourceAddress;
    }

    public void setSourceAddress(int sourceAddress)
    {
        mSourceAddress = sourceAddress;
    }

    public int getSystemId()
    {
        return mSystemId;
    }

    public void setSystemId(int systemId)
    {
        mSystemId = systemId;
    }

    public int getSiteId()
    {
        return mSiteId;
    }

    public void setSiteId(int siteId)
    {
        mSiteId = siteId;
    }

    @Override
    public boolean isValid()
    {
        return mMessageType != SmartNetMessageType.UNKNOWN;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.SMARTNET;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();

            if(mGroup && mAddress != 0 &&
               mMessageType != SmartNetMessageType.IDLE &&
               mMessageType != SmartNetMessageType.UNKNOWN)
            {
                mTalkgroup = SmartNetTalkgroup.create(getTalkgroupRaw());
                mIdentifiers.add(mTalkgroup);
            }

            if(mSourceAddress != 0)
            {
                mRadioId = SmartNetRadioId.create(mSourceAddress);
                mIdentifiers.add(mRadioId);
            }
        }

        return mIdentifiers;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SMARTNET ");
        sb.append(mMessageType.getLabel());

        if(mGroup && mAddress != 0 && mMessageType != SmartNetMessageType.IDLE)
        {
            sb.append(" TG:").append(getTalkgroupRaw());
        }
        else if(!mGroup && mAddress != 0 && mMessageType != SmartNetMessageType.IDLE)
        {
            sb.append(" ID:").append(mAddress);
        }

        if(mSourceAddress != 0)
        {
            sb.append(" SRC:").append(mSourceAddress);
        }

        if(mFrequency != 0)
        {
            sb.append(" FREQ:").append(String.format("%.4f", mFrequency / 1_000_000.0));
        }

        if(mSystemId != 0)
        {
            sb.append(" SYS:").append(String.format("%04X", mSystemId));
        }

        if(mSiteId != 0)
        {
            sb.append(" SITE:").append(mSiteId);
        }

        if(isEncrypted())
        {
            sb.append(" [ENCRYPTED]");
        }

        if(isEmergency())
        {
            sb.append(" [EMERGENCY]");
        }

        sb.append(" CMD:").append(String.format("0x%03X", mCommand));
        sb.append(" ADDR:").append(String.format("0x%04X", mAddress));
        sb.append(mGroup ? " G" : " I");

        return sb.toString();
    }
}
