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

import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.real.IRealBufferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Motorola SmartNet/SmartZone 3600 baud binary FSK control channel decoder.
 *
 * Signal processing chain:
 *   SDR Trunk channelizer provides NBFM-demodulated real samples at 8000 Hz
 *   -> Low-pass filter (300 Hz cutoff for 3600 baud data)
 *   -> Zero-crossing / slope-based symbol timing recovery
 *   -> Binary slicer (positive = 1, negative = 0)
 *   -> Preamble correlator (sync pattern: 10101100 = 0xAC)
 *   -> OSW frame assembler (76 bits per frame)
 *   -> Error correction and validation
 *   -> SmartNetOSWParser for message interpretation
 *
 * The SmartNet control channel is a continuous stream of OSW frames at 3600 baud.
 * Each frame is approximately 23.3ms (84 bits including sync).
 * The system transmits ~40 OSWs per second.
 */
public class SmartNetDecoder extends Decoder implements IRealBufferListener, Listener<float[]>
{
    private static final Logger mLog = LoggerFactory.getLogger(SmartNetDecoder.class);

    // SmartNet protocol constants
    public static final int SYMBOL_RATE = 3600;
    public static final int SAMPLE_RATE = 8000;
    public static final double SAMPLES_PER_SYMBOL = (double) SAMPLE_RATE / SYMBOL_RATE;

    // Sync pattern: 10101100 (0xAC) - 8 bits
    public static final byte[] SYNC_PATTERN = {1, 0, 1, 0, 1, 1, 0, 0};

    // OSW frame: 76 data bits after sync (16 addr + 1 group + 10 cmd + 49 ECC)
    public static final int OSW_BITS = 76;

    // Slope-based symbol timing (adapted from LTRDecoder approach)
    private static final int SLOPE_CALC_LENGTH = 5;
    private static final float SLOPE_THRESHOLD = 0.003f;

    // State
    private float mBaudCounter = 0;
    private boolean mCurrentSymbol = false;
    private float mMaxSlope = 0;
    private float[] mResidual;
    private static final int OVERLAP = 6;

    // Bit buffer for frame assembly
    private int[] mBitBuffer = new int[256];
    private int mBitBufferPointer = 0;

    // Sync detection state
    private boolean mSynced = false;
    private int mBitsAfterSync = 0;
    private int mSyncSearchPointer = 0;

    // Statistics
    private long mOswCount = 0;
    private long mSyncLossCount = 0;

    // OSW parser
    private SmartNetOSWParser mOSWParser;

    /**
     * Constructs a SmartNet decoder.
     *
     * @param bandPlan the band plan for channel-to-frequency conversion
     */
    public SmartNetDecoder(SmartNetBandPlan bandPlan)
    {
        mResidual = new float[OVERLAP];
        mOSWParser = new SmartNetOSWParser(bandPlan);
        mOSWParser.setMessageListener(getMessageListener());
        mLog.info("SmartNet decoder initialized: {} baud, {} Hz sample rate, {:.2f} samples/symbol",
            SYMBOL_RATE, SAMPLE_RATE, SAMPLES_PER_SYMBOL);
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.SMARTNET;
    }

    @Override
    public Listener<float[]> getBufferListener()
    {
        return this;
    }

    /**
     * Processes demodulated FM audio samples to extract 3600 baud SmartNet signaling.
     */
    @Override
    public void receive(float[] samples)
    {
        // Create work buffer with overlap from previous buffer
        float[] buffer = new float[samples.length + OVERLAP];
        System.arraycopy(mResidual, 0, buffer, 0, OVERLAP);
        System.arraycopy(samples, 0, buffer, OVERLAP, samples.length);

        // Save overlap for next buffer
        if(samples.length >= OVERLAP)
        {
            System.arraycopy(samples, samples.length - OVERLAP, mResidual, 0, OVERLAP);
        }

        // Process each sample - extract symbols using slope-based timing recovery
        for(int i = 0; i < samples.length; i++)
        {
            float slope = calculateSlope(buffer, i);

            // Symbol transition detection
            if(mCurrentSymbol)
            {
                // Currently high - look for negative slope (transition to low)
                if(slope > mMaxSlope && mMaxSlope < -SLOPE_THRESHOLD)
                {
                    mCurrentSymbol = false;
                    mMaxSlope = 0;
                }
                else if(slope < mMaxSlope)
                {
                    mMaxSlope = slope;
                }
            }
            else
            {
                // Currently low - look for positive slope (transition to high)
                if(slope < mMaxSlope && mMaxSlope > SLOPE_THRESHOLD)
                {
                    mCurrentSymbol = true;
                    mMaxSlope = 0;
                }
                else if(slope > mMaxSlope)
                {
                    mMaxSlope = slope;
                }
            }

            // Baud timing - emit a symbol at each baud boundary
            mBaudCounter++;
            if(mBaudCounter >= SAMPLES_PER_SYMBOL)
            {
                mBaudCounter -= SAMPLES_PER_SYMBOL;
                processBit(mCurrentSymbol ? 1 : 0);
            }
        }
    }

    /**
     * Processes a single decoded bit through the frame assembler.
     */
    private void processBit(int bit)
    {
        if(mSynced)
        {
            // We're in a frame - collect bits
            mBitBuffer[mBitsAfterSync] = bit;
            mBitsAfterSync++;

            if(mBitsAfterSync >= OSW_BITS)
            {
                // Complete OSW frame received - process it
                processOSWFrame();
                mSynced = false;
                mBitsAfterSync = 0;
                mSyncSearchPointer = 0;
            }
        }
        else
        {
            // Searching for sync pattern - shift bit into search buffer
            mBitBuffer[mSyncSearchPointer % 256] = bit;
            mSyncSearchPointer++;

            // Check if last 8 bits match sync pattern
            if(mSyncSearchPointer >= SYNC_PATTERN.length)
            {
                boolean match = true;
                for(int s = 0; s < SYNC_PATTERN.length; s++)
                {
                    int idx = (mSyncSearchPointer - SYNC_PATTERN.length + s) % 256;
                    if(mBitBuffer[idx] != SYNC_PATTERN[s])
                    {
                        match = false;
                        break;
                    }
                }

                if(match)
                {
                    // Sync found - start collecting frame bits
                    mSynced = true;
                    mBitsAfterSync = 0;
                }
            }
        }
    }

    /**
     * Processes a complete 76-bit OSW frame.
     * Extracts the 27 information bits: 16-bit address + 1-bit group + 10-bit command.
     * The remaining 49 bits are BCH error correction (processed by the parser).
     */
    private void processOSWFrame()
    {
        // Extract address (bits 0-15), group (bit 16), command (bits 17-26)
        int address = 0;
        for(int i = 0; i < 16; i++)
        {
            address = (address << 1) | (mBitBuffer[i] & 1);
        }

        boolean group = (mBitBuffer[16] & 1) == 1;

        int command = 0;
        for(int i = 17; i < 27; i++)
        {
            command = (command << 1) | (mBitBuffer[i] & 1);
        }

        // TODO: BCH error correction on the full 76 bits
        // For now, pass the raw decoded values to the OSW parser

        mOswCount++;
        mOSWParser.processOSW(address, group, command);
    }

    /**
     * Calculates the slope of samples starting at the given offset.
     * Used for symbol timing recovery.
     */
    private float calculateSlope(float[] samples, int offset)
    {
        if(offset + SLOPE_CALC_LENGTH > samples.length)
        {
            return 0;
        }

        float sumXY = 0;
        float meanX = 0;
        float meanY = samples[offset];

        for(int x = 1; x < SLOPE_CALC_LENGTH; x++)
        {
            float dx = x - meanX;
            float dy = samples[offset + x] - meanY;
            sumXY += dx * dy * ((float) x / (1.0f + x));
            meanX += dx / (1.0f + x);
            meanY += dy / (1.0f + x);
        }

        // Normalize by sum of (x - xbar)^2
        float sumXX = 0;
        for(int x = 0; x < SLOPE_CALC_LENGTH; x++)
        {
            float dx = x - (SLOPE_CALC_LENGTH - 1) / 2.0f;
            sumXX += dx * dx;
        }

        return sumXY / (sumXX + 0.001f);
    }

    /**
     * Gets the number of OSWs successfully decoded.
     */
    public long getOSWCount()
    {
        return mOswCount;
    }

    /**
     * Gets the OSW parser for configuration access.
     */
    public SmartNetOSWParser getOSWParser()
    {
        return mOSWParser;
    }

    @Override
    public void reset()
    {
        mSynced = false;
        mBitsAfterSync = 0;
        mSyncSearchPointer = 0;
        mBaudCounter = 0;
        mCurrentSymbol = false;
        mMaxSlope = 0;
    }
}