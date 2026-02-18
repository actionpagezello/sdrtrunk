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
package io.github.dsheirer.module.decode.smartnet.channel;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;

/**
 * SmartNet channel descriptor - maps a SmartNet channel number to a frequency.
 */
public class SmartNetChannel implements IChannelDescriptor
{
    private int mChannelNumber;
    private long mDownlinkFrequency;
    private long mUplinkFrequency;

    public SmartNetChannel(int channelNumber, long downlinkFrequency, long uplinkFrequency)
    {
        mChannelNumber = channelNumber;
        mDownlinkFrequency = downlinkFrequency;
        mUplinkFrequency = uplinkFrequency;
    }

    public SmartNetChannel(int channelNumber, long downlinkFrequency)
    {
        this(channelNumber, downlinkFrequency, 0);
    }

    public int getChannelNumber()
    {
        return mChannelNumber;
    }

    @Override
    public long getDownlinkFrequency()
    {
        return mDownlinkFrequency;
    }

    @Override
    public long getUplinkFrequency()
    {
        return mUplinkFrequency;
    }

    @Override
    public int[] getFrequencyBandIdentifiers()
    {
        return new int[0];
    }

    @Override
    public void setFrequencyBand(IFrequencyBand bandIdentifier)
    {
        // Not used - SmartNet uses its own band plan calculation
    }

    @Override
    public boolean isTDMAChannel()
    {
        return false;
    }

    @Override
    public int getTimeslotCount()
    {
        return 1;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.SMARTNET;
    }

    @Override
    public String toString()
    {
        return "CH:" + mChannelNumber + " " + String.format("%.4f", mDownlinkFrequency / 1_000_000.0) + " MHz";
    }
}