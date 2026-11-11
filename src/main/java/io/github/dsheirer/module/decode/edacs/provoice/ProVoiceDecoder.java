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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (EDACS ProVoice): decodes a ProVoice voice channel - 9600 baud FSK - into voice frames for the audio module.
 *
 * Each 80 ms frame starts with a 32-bit sync; both the conventional/standard and the Extended Addressing sync are
 * searched, in either polarity (MBTA transmits it inverted).
 *
 * Damaged voice frames are concealed the way mbelib does it: a voice frame with more than MAX_ERRORS corrected bit
 * errors is replaced by the last good one, and after MAX_REPEATS replacements in a row by silence.  Without this the
 * damaged frames reach JMBE looking error free (they are re-encoded cleanly) and play as warbles (MBTA 2026-11-09:
 * about 9% of voice frames on LCN 5 recorded in Somerville).
 */
public class ProVoiceDecoder extends Decoder implements IComplexSamplesListener, Listener<ComplexSamples>,
    ISourceEventListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ProVoiceDecoder.class);

    /** Sync patterns from DSD-FME (symbol 3 = 1, symbol 1 = 0) */
    public static final long SYNC = pattern("13131333111311311133113311331133");
    public static final long SYNC_EA = pattern("31131311331331111133131311311133");
    private static final int MAX_SYNC_ERRORS = 2;
    /** mbelib's threshold: more corrected errors than this and the frame is not trusted */
    public static final int MAX_ERRORS = 5;
    public static final int MAX_REPEATS = 3;
    /** Bits without a frame after which the next frame starts a new transmission (about 0.25 s) */
    private static final long NEW_TRANSMISSION_BITS = 2400;

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
    private long mConcealedCount;
    private byte[] mLastGood;
    private int mRepeats;
    private long mLastFrameBit = -NEW_TRANSMISSION_BITS;

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
        if(mBitCount - mLastFrameBit > NEW_TRANSMISSION_BITS)
        {
            logTransmission();
            mLastGood = null; //new transmission - nothing to repeat
            mRepeats = 0;
        }
        mLastFrameBit = mBitCount;

        ProVoiceFrameDecoder.VoiceFrame[] voiceFrames = ProVoiceFrameDecoder.decode(mFrame);
        List<byte[]> imbeFrames = new ArrayList<>(4);
        int errors = 0;
        long concealedBefore = mConcealedCount;

        for(ProVoiceFrameDecoder.VoiceFrame voiceFrame : voiceFrames)
        {
            imbeFrames.add(conceal(IMBEFrameEncoder.encode(voiceFrame.parameters()), voiceFrame.errors()));
            errors += voiceFrame.errors();
        }

        int concealed = (int)(mConcealedCount - concealedBefore);
        mFrameCount++;
        mErrorCount += errors;
        //Position of the damage within the transmission (edacs-11 measurement): the first EDGE_FRAMES frames are counted
        //as the start, and the most recent EDGE_FRAMES frames after those are held in a ring so that they can be counted
        //as the end when the transmission is logged.
        if(mTransmissionFrames < EDGE_FRAMES)
        {
            mTransmissionConcealedStart += concealed;
        }
        else
        {
            int index = (int)((mTransmissionFrames - EDGE_FRAMES) % EDGE_FRAMES);
            mTransmissionConcealedRecent[index] = concealed;
        }

        mTransmissionFrames++;
        mTransmissionErrors += errors;
        mTransmissionConcealed += concealed;
        getMessageListener().receive(new ProVoiceMessage(imbeFrames, errors, concealed, mTimestamp));
    }

    //Frames (80 ms, four voice frames each) that count as the start and as the end of a transmission: 0.48 s each
    static final int EDGE_FRAMES = 6;
    private long mTransmissionFrames;
    private long mTransmissionErrors;
    private long mTransmissionConcealed;
    private long mTransmissionConcealedStart;
    private final int[] mTransmissionConcealedRecent = new int[EDGE_FRAMES];

    /**
     * Damaged voice frames in the last EDGE_FRAMES frames of the current transmission, not counting any of the first
     * EDGE_FRAMES frames, so that a short transmission is never counted twice.  Package-private for testing.
     */
    long getTransmissionConcealedEnd()
    {
        long endFrames = Math.min(EDGE_FRAMES, Math.max(0, mTransmissionFrames - EDGE_FRAMES));
        long total = 0;

        for(int x = 0; x < endFrames; x++)
        {
            total += mTransmissionConcealedRecent[x];
        }

        return total;
    }

    /**
     * Diagnostics (DEBUG): one line per transmission - frames, corrected bit errors and damaged voice frames, and
     * where in the transmission the damaged voice frames fall (start, middle, end).
     */
    private void logTransmission()
    {
        if(mTransmissionFrames > 0 && LOGGER.isDebugEnabled())
        {
            LOGGER.debug(String.format("ProVoice transmission: %.1f s, %.2f bit errors per voice frame, %d of %d " +
                    "voice frames damaged (%.1f%%), sync %s", mTransmissionFrames * 0.08,
                mTransmissionErrors / (4.0 * mTransmissionFrames), mTransmissionConcealed, 4 * mTransmissionFrames,
                100.0 * mTransmissionConcealed / (4.0 * mTransmissionFrames), mInverted ? "inverted" : "normal"));

            long startFrames = Math.min(EDGE_FRAMES, mTransmissionFrames);
            long endFrames = Math.min(EDGE_FRAMES, mTransmissionFrames - startFrames);
            long middleFrames = mTransmissionFrames - startFrames - endFrames;
            long end = getTransmissionConcealedEnd();
            long middle = mTransmissionConcealed - mTransmissionConcealedStart - end;
            LOGGER.debug(String.format("ProVoice damage by position: first %.2f s %d of %d, middle %d of %d, " +
                    "last %.2f s %d of %d voice frames", startFrames * 0.08, mTransmissionConcealedStart,
                4 * startFrames, middle, 4 * middleFrames, endFrames * 0.08, end, 4 * endFrames));
        }

        mTransmissionFrames = 0;
        mTransmissionErrors = 0;
        mTransmissionConcealed = 0;
        mTransmissionConcealedStart = 0;
        java.util.Arrays.fill(mTransmissionConcealedRecent, 0);
    }

    @Override
    public void stop()
    {
        logTransmission();
        super.stop();
    }

    /**
     * Frame concealment: returns the frame to play - this one, the last good one repeated, or null for silence.
     * Package-private for testing.
     */
    byte[] conceal(byte[] frame, int errors)
    {
        if(errors <= MAX_ERRORS)
        {
            mLastGood = frame;
            mRepeats = 0;
            return frame;
        }

        mConcealedCount++;

        if(mLastGood != null && mRepeats < MAX_REPEATS)
        {
            mRepeats++;
            return mLastGood;
        }

        mLastGood = null;
        return null;
    }

    /**
     * Voice frames (20 ms) replaced by a repeat or silence
     */
    public long getConcealedCount()
    {
        return mConcealedCount;
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
