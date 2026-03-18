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

package io.github.dsheirer.module.decode.nbfm;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.dcs.DCSDecoder;
import io.github.dsheirer.module.decode.dcs.DCSMessage;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Channel-level DCS (Digital-Coded Squelch) detector for use in NBFMDecoder.
 *
 * Wraps the existing DCSDecoder (which decodes 134.4 bps signalling from 8 kHz audio)
 * and adds channel-level filtering logic: accept/reject/lost callbacks based on a
 * configured set of allowed DCS codes.
 *
 * Operates identically to CTCSSDetector in concept:
 * - Feeds 8 kHz resampled audio to the underlying DCSDecoder
 * - When a DCS code is detected, checks if it's in the allowed set
 * - Reports detected (allowed), rejected (wrong code), or lost (no code for a period)
 *
 * DCS codes repeat at ~5.84 Hz (every ~171ms). We require CONFIRMATION_COUNT consecutive
 * detections of the same code before reporting, and LOSS_COUNT consecutive decode cycles
 * with no detection before reporting lost.
 */
public class DCSDetector
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DCSDetector.class);

    /**
     * Number of consecutive detections of the same code required before reporting.
     */
    private static final int CONFIRMATION_COUNT = 2;

    /**
     * Number of audio blocks processed without a DCS detection before declaring lost.
     * At 8 kHz with typical buffer sizes, this gives roughly 500ms-1s of silence.
     */
    private static final int LOSS_COUNT = 6;

    /**
     * Minimum interval between loss counter increments, in samples processed.
     * DCS repeats every ~1369 samples at 8 kHz. We check every ~1400 samples.
     */
    private static final int LOSS_CHECK_INTERVAL_SAMPLES = 1400;

    private final Set<DCSCode> mTargetCodes;
    private final DCSDecoder mDCSDecoder;

    // Detection state
    private DCSCode mDetectedCode = null;
    private int mConfirmationCounter = 0;
    private int mLossCounter = 0;
    private int mSamplesSinceLastDetection = 0;
    private int mTotalSamplesProcessed = 0;

    // Callback
    private DCSDetectorListener mListener;

    /**
     * Listener interface for DCS detection events
     */
    public interface DCSDetectorListener
    {
        void dcsDetected(DCSCode code);
        void dcsRejected(DCSCode code);
        void dcsLost();
    }

    /**
     * Constructs a DCS detector for channel-level filtering.
     *
     * @param targetCodes the set of DCS codes to accept. Audio is only passed when one of these is detected.
     */
    public DCSDetector(Set<DCSCode> targetCodes)
    {
        mTargetCodes = targetCodes;

        // Create the underlying DCS decoder and register our message listener
        mDCSDecoder = new DCSDecoder();
        mDCSDecoder.setMessageListener(new Listener<IMessage>()
        {
            @Override
            public void receive(IMessage message)
            {
                if(message instanceof DCSMessage dcsMessage)
                {
                    handleDetection(dcsMessage.getDCSCode());
                }
            }
        });

        LOGGER.debug("DCSDetector initialized with {} target code(s)", targetCodes.size());
    }

    /**
     * Sets the listener for detection events.
     */
    public void setListener(DCSDetectorListener listener)
    {
        mListener = listener;
    }

    /**
     * Processes a buffer of 8 kHz demodulated FM audio samples.
     * Feeds them to the underlying DCSDecoder for 134.4 bps decoding.
     *
     * @param samples demodulated audio samples at 8 kHz
     */
    public void process(float[] samples)
    {
        if(samples == null || samples.length == 0)
        {
            return;
        }

        // Feed audio to the DCS decoder — it will call our message listener when a code is found
        mDCSDecoder.receive(samples);

        // Track samples since last detection for loss detection
        mSamplesSinceLastDetection += samples.length;
        mTotalSamplesProcessed += samples.length;

        // Check for loss: if we've processed enough samples without a detection, increment loss counter
        if(mSamplesSinceLastDetection >= LOSS_CHECK_INTERVAL_SAMPLES)
        {
            // No DCS code detected in this interval
            if(mDetectedCode != null)
            {
                mLossCounter++;

                if(mLossCounter >= LOSS_COUNT)
                {
                    mDetectedCode = null;
                    mConfirmationCounter = 0;

                    if(mListener != null)
                    {
                        mListener.dcsLost();
                    }
                }
            }

            mSamplesSinceLastDetection = 0;
        }
    }

    /**
     * Handles detection of a DCS code from the underlying decoder.
     */
    private void handleDetection(DCSCode code)
    {
        if(code == null || code == DCSCode.UNKNOWN)
        {
            return;
        }

        // Reset loss tracking — we just got a detection
        mLossCounter = 0;
        mSamplesSinceLastDetection = 0;

        // Check if this code is in our allowed set
        if(!mTargetCodes.contains(code))
        {
            // Wrong code — track for confirmed rejection
            if(mDetectedCode == code)
            {
                if(mConfirmationCounter < CONFIRMATION_COUNT)
                {
                    mConfirmationCounter++;

                    if(mConfirmationCounter >= CONFIRMATION_COUNT && mListener != null)
                    {
                        mListener.dcsRejected(code);
                    }
                }
                // Already confirmed rejected
            }
            else
            {
                mDetectedCode = code;
                mConfirmationCounter = 1;
            }
            return;
        }

        // Code is in our allowed set
        if(mDetectedCode == code)
        {
            // Same code again — increment confirmation
            if(mConfirmationCounter < CONFIRMATION_COUNT)
            {
                mConfirmationCounter++;

                if(mConfirmationCounter >= CONFIRMATION_COUNT && mListener != null)
                {
                    mListener.dcsDetected(code);
                }
            }
            // Already confirmed — keep reporting
            else if(mListener != null)
            {
                mListener.dcsDetected(code);
            }
        }
        else
        {
            // Different code — restart confirmation
            mDetectedCode = code;
            mConfirmationCounter = 1;
        }
    }

    /**
     * Resets the detector state.
     */
    public void reset()
    {
        mDetectedCode = null;
        mConfirmationCounter = 0;
        mLossCounter = 0;
        mSamplesSinceLastDetection = 0;
    }

    /**
     * Returns the currently detected DCS code, or null if none confirmed.
     */
    public DCSCode getDetectedCode()
    {
        return (mConfirmationCounter >= CONFIRMATION_COUNT) ? mDetectedCode : null;
    }
}
