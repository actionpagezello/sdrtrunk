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

package io.github.dsheirer.module.decode.edacs.provoice;

import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.edacs.EDACSDemodulator;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * AP-fork (EDACS ProVoice): decodes a ProVoice voice channel - 9600 baud FSK - into voice frames for the audio module.
 *
 * Each 80 ms frame starts with a 32-bit sync; both the conventional/standard and the Extended Addressing sync are
 * searched, in either polarity (MBTA transmits it inverted).
 */
public class ProVoiceDecoder extends Decoder implements IComplexSamplesListener, Listener<ComplexSamples>,
    ISourceEventListener
{
    /** Sync patterns from DSD-FME (symbol 3 = 1, symbol 1 = 0) */
    public static final long SYNC = pattern("13131333111311311133113311331133");
    public static final long SYNC_EA = pattern("31131311331331111133131311311133");
    private static final int MAX_SYNC_ERRORS = 2;

    private final Listener<SourceEvent> mSourceEventListener = this::process;
    private final int[] mFrame = new int[ProVoiceFrameDecoder.FRAME_BITS];
    private EDACSDemodulator mDemodulator;
    private double mSampleRate;
    private long mShift;
    private long mBitCount;
    private int mFrameIndex = -1;
    private boolean mInverted;
    private long mTimestamp;
    private long mFrameCount;
    private long mErrorCount;

    private static long pattern(String symbols)
    {
        long value = 0;

        for(char c : symbols.toCharArray())
        {
            value = (value << 1) | (c == '3' ? 1 : 0);
        }

        return value;
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventListener;
    }

    /**
     * Sets the channel sample rate.  Public for offline testing.
     */
    public void setSampleRate(double sampleRate)
    {
        if(mDemodulator == null || Math.abs(mSampleRate - sampleRate) > 1.0)
        {
            mSampleRate = sampleRate;
            mDemodulator = new EDACSDemodulator(sampleRate, this::process);
        }
    }

    private void process(SourceEvent sourceEvent)
    {
        if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
        {
            setSampleRate(sourceEvent.getValue().doubleValue());
        }
    }

    @Override
    public void receive(ComplexSamples samples)
    {
        EDACSDemodulator demodulator = mDemodulator;

        if(demodulator != null)
        {
            mTimestamp = samples.timestamp();
            demodulator.process(samples.i(), samples.q(), samples.i().length);
        }
    }

    /**
     * Processes one demodulated bit.  Public for testing.
     */
    public void process(boolean bit)
    {
        mBitCount++;

        if(mFrameIndex >= 0)
        {
            mFrame[mFrameIndex++] = (bit ^ mInverted) ? 1 : 0;

            if(mFrameIndex == mFrame.length)
            {
                mFrameIndex = -1;
                mShift = 0;
                dispatch();
            }
            return;
        }

        mShift = ((mShift << 1) | (bit ? 1 : 0)) & 0xFFFFFFFFL;

        if(mBitCount < 32)
        {
            return;
        }

        for(long sync : new long[]{SYNC_EA, SYNC})
        {
            int errors = Long.bitCount(mShift ^ sync);

            if(errors <= MAX_SYNC_ERRORS || 32 - errors <= MAX_SYNC_ERRORS)
            {
                mInverted = errors > MAX_SYNC_ERRORS;
                mFrameIndex = 0;
                return;
            }
        }
    }

    private void dispatch()
    {
        ProVoiceFrameDecoder.VoiceFrame[] voiceFrames = ProVoiceFrameDecoder.decode(mFrame);
        List<byte[]> imbeFrames = new ArrayList<>(4);
        int errors = 0;

        for(ProVoiceFrameDecoder.VoiceFrame voiceFrame : voiceFrames)
        {
            imbeFrames.add(IMBEFrameEncoder.encode(voiceFrame.parameters()));
            errors += voiceFrame.errors();
        }

        mFrameCount++;
        mErrorCount += errors;
        getMessageListener().receive(new ProVoiceMessage(imbeFrames, errors, mTimestamp));
    }

    public long getFrameCount()
    {
        return mFrameCount;
    }

    public long getErrorCount()
    {
        return mErrorCount;
    }
}
