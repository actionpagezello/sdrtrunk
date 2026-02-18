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

/**
 * Motorola SmartNet/SmartZone band plan for converting channel numbers to frequencies.
 *
 * Based on trunk-recorder's smartnet_parser.cc get_freq() and is_chan() implementations.
 *
 * 800 MHz Standard:  freq = 851.0125 + (0.025 * channel) for channels 0 through 0x2CF
 * 800 MHz Rebanded:  channels <= 0x1B7 use standard formula
 *                    channels 0x1B8 to 0x22F use 851.0250 + (0.025 * (channel - 0x1B8))
 * Extended ranges:   0x2D0-0x2F7: 866.0000 + (0.025 * (channel - 0x2D0))
 *                    0x32F-0x33F: 867.0000 + (0.025 * (channel - 0x32F))
 *                    0x3C1-0x3FE: 867.4250 + (0.025 * (channel - 0x3C1))
 *                    0x3BE:       868.9750
 * 900 MHz:           freq = 935.0125 + (0.0125 * channel)
 */
public class SmartNetBandPlan
{
    /**
     * Supported band plan types
     */
    public enum BandPlanType
    {
        BAND_800_STANDARD("800 Standard"),
        BAND_800_REBANDED("800 Rebanded"),
        BAND_800_SPLINTER("800 Splinter"),
        BAND_900("900 MHz"),
        BAND_CUSTOM("Custom (VHF/UHF)");

        private String mLabel;

        BandPlanType(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    private BandPlanType mBandPlanType;

    // Custom band plan parameters (for VHF/UHF OBT systems)
    private double mCustomBase = 0;
    private double mCustomHigh = 0;
    private double mCustomSpacing = 0.025;
    private int mCustomOffset = 0;

    /**
     * Constructs a SmartNet band plan with the specified type.
     */
    public SmartNetBandPlan(BandPlanType type)
    {
        mBandPlanType = type;
    }

    /**
     * Constructs a custom band plan.
     *
     * @param baseMHz base frequency in MHz
     * @param highMHz highest frequency in MHz
     * @param spacingMHz channel spacing in MHz (typically 0.025)
     * @param offset base channel offset
     */
    public SmartNetBandPlan(double baseMHz, double highMHz, double spacingMHz, int offset)
    {
        mBandPlanType = BandPlanType.BAND_CUSTOM;
        mCustomBase = baseMHz;
        mCustomHigh = highMHz;
        mCustomSpacing = spacingMHz;
        mCustomOffset = offset;
    }

    public BandPlanType getBandPlanType()
    {
        return mBandPlanType;
    }

    /**
     * Determines if the given command value represents a valid channel number.
     *
     * @param channel the 10-bit command/channel value from the OSW
     * @return true if this is a valid channel number (not a status command)
     */
    public boolean isChannel(int channel)
    {
        if(channel < 0)
        {
            return false;
        }

        switch(mBandPlanType)
        {
            case BAND_800_STANDARD:
                return (channel <= 0x2CF) ||
                       (channel >= 0x2D0 && channel <= 0x2F7) ||
                       (channel >= 0x32F && channel <= 0x33F) ||
                       (channel >= 0x3C1 && channel <= 0x3FE) ||
                       (channel == 0x3BE);

            case BAND_800_REBANDED:
                return (channel <= 0x22F) ||
                       (channel >= 0x2D0 && channel <= 0x2F7) ||
                       (channel >= 0x32F && channel <= 0x33F) ||
                       (channel >= 0x3C1 && channel <= 0x3FE) ||
                       (channel == 0x3BE);

            case BAND_800_SPLINTER:
                return (channel <= 0x257) ||
                       (channel >= 0x258 && channel <= 0x2CF) ||
                       (channel >= 0x2D0 && channel <= 0x2F7) ||
                       (channel >= 0x32F && channel <= 0x33F) ||
                       (channel >= 0x3C1 && channel <= 0x3FE) ||
                       (channel == 0x3BE);

            case BAND_900:
                return channel <= 0x1DE;

            case BAND_CUSTOM:
                double highCmd = mCustomOffset + (mCustomHigh - mCustomBase) / mCustomSpacing;
                return channel >= mCustomOffset && channel < highCmd;

            default:
                return false;
        }
    }

    /**
     * Converts a channel number to a receive frequency in Hz.
     *
     * @param channel the channel number from the OSW
     * @return frequency in Hz, or 0 if channel is invalid
     */
    public long getFrequency(int channel)
    {
        double freqMHz = getFrequencyMHz(channel);

        if(freqMHz == 0.0)
        {
            return 0;
        }

        return Math.round(freqMHz * 1_000_000.0);
    }

    /**
     * Converts a channel number to a receive frequency in MHz.
     *
     * @param channel the channel number from the OSW
     * @return frequency in MHz, or 0.0 if channel is invalid
     */
    public double getFrequencyMHz(int channel)
    {
        double freq = 0.0;

        switch(mBandPlanType)
        {
            case BAND_800_STANDARD:
                if(channel <= 0x2CF)
                {
                    freq = 851.0125 + (0.025 * channel);
                }
                break;

            case BAND_800_REBANDED:
                if(channel <= 0x1B7)
                {
                    freq = 851.0125 + (0.025 * channel);
                }
                else if(channel >= 0x1B8 && channel <= 0x22F)
                {
                    freq = 851.0250 + (0.025 * (channel - 0x1B8));
                }
                break;

            case BAND_800_SPLINTER:
                if(channel <= 0x257)
                {
                    freq = 851.0000 + (0.025 * channel);
                }
                else if(channel >= 0x258 && channel <= 0x2CF)
                {
                    freq = 866.0125 + (0.025 * (channel - 0x258));
                }
                break;

            case BAND_900:
                if(channel <= 0x1DE)
                {
                    freq = 935.0125 + (0.0125 * channel);
                }
                return Math.round(freq * 100000.0) / 100000.0;

            case BAND_CUSTOM:
                if(channel >= mCustomOffset)
                {
                    freq = mCustomBase + (mCustomSpacing * (channel - mCustomOffset));
                    if(freq > mCustomHigh)
                    {
                        freq = 0.0;
                    }
                }
                return Math.round(freq * 100000.0) / 100000.0;

            default:
                return 0.0;
        }

        // Extended 800 MHz ranges shared by standard, rebanded, and splinter
        if(freq == 0.0 && mBandPlanType != BandPlanType.BAND_900 && mBandPlanType != BandPlanType.BAND_CUSTOM)
        {
            if(channel >= 0x2D0 && channel <= 0x2F7)
            {
                freq = 866.0000 + (0.025 * (channel - 0x2D0));
            }
            else if(channel >= 0x32F && channel <= 0x33F)
            {
                freq = 867.0000 + (0.025 * (channel - 0x32F));
            }
            else if(channel >= 0x3C1 && channel <= 0x3FE)
            {
                freq = 867.4250 + (0.025 * (channel - 0x3C1));
            }
            else if(channel == 0x3BE)
            {
                freq = 868.9750;
            }
        }

        return Math.round(freq * 100000.0) / 100000.0;
    }

    /**
     * Gets the transmit frequency offset for this band (subtract from receive).
     * 800 MHz: 45 MHz offset, 900 MHz: 39 MHz offset
     */
    public double getTxOffsetMHz()
    {
        switch(mBandPlanType)
        {
            case BAND_800_STANDARD:
            case BAND_800_REBANDED:
            case BAND_800_SPLINTER:
                return 45.0;
            case BAND_900:
                return 39.0;
            default:
                return 0.0;
        }
    }

    @Override
    public String toString()
    {
        return mBandPlanType.toString();
    }
}
