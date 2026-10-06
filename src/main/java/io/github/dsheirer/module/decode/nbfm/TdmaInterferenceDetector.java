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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects 2-slot TDMA digital transmissions (DMR, P25 Phase 2) bleeding into an analog NBFM
 * channel, so the tone squelch can refuse to open on them.
 *
 * Why this exists: a digital carrier demodulated as FM produces broadband noise that randomly
 * lights up CTCSS bins — including the target tone's bin. On channels where the interferer is
 * strong this repeatedly satisfies the CTCSS detector and holds the tone gate open for the whole
 * burst, recording seconds of data buzz as a "call". Frequency-domain CTCSS analysis alone
 * cannot reject it, because the energy genuinely lands on the target frequency.
 *
 * How it works: 2-slot TDMA has a hard 30 ms slot cadence (33.3 Hz), which survives FM
 * demodulation as a strong periodic amplitude modulation of the audio envelope. Human speech has
 * no such component. The detector:
 *
 *   1. Builds an RMS envelope of the audio at 500 Hz (2 ms sub-blocks).
 *   2. High-pass filters that envelope at 15 Hz to strip syllabic speech rates, which would
 *      otherwise dominate and produce false positives.
 *   3. Runs Goertzel at a candidate slot rate f0 and its 2nd and 3rd harmonics, and scores the
 *      geometric mean of the three relative to an off-comb noise reference.
 *
 * Requiring all three harmonics is what separates a genuine TDMA cadence from broad modulation
 * that merely happens to have energy near 33 Hz.
 *
 * Calibration (2026-08) was done against four DMR bursts captured off Somerville Fire 483.3875,
 * measured with this exact implementation:
 *
 *   DMR bursts        per-window peak 64, 76, 182, 354   (every clip vetoed)
 *   speech-like AM    peak 13                            (never vetoed)
 *   white noise       peak  8
 *   CTCSS tone+speech peak  9
 *   60 Hz mains hum   peak  5
 *   squelch flutter   peak  1
 *
 * The threshold of 20 sits in that gap: 1.5x above the worst negative and 3.2x below the weakest
 * real burst. Note the negatives are synthetic — if a legitimate transmission is ever cut off,
 * the veto logs its score at DEBUG, so raise DETECTION_THRESHOLD toward the observed value
 * rather than disabling the detector.
 */
public class TdmaInterferenceDetector
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TdmaInterferenceDetector.class);

    /** Envelope sub-block length in milliseconds — yields a 500 Hz envelope sample rate. */
    private static final float SUB_BLOCK_MS = 2.0f;

    /** Analysis window in milliseconds. Must span several slot periods for a stable estimate. */
    private static final float WINDOW_MS = 300.0f;

    /** Candidate 2-slot TDMA cadences. DMR and P25 Phase 2 both use 30 ms slots (33.33 Hz). */
    private static final float[] CANDIDATE_RATES = {32.0f, 32.5f, 33.0f, 33.33f, 34.0f, 34.5f, 35.0f};

    /** Off-comb reference frequencies used to estimate the envelope noise floor. */
    private static final float[] REFERENCE_RATES = {17.0f, 23.0f, 41.0f, 47.0f, 53.0f, 71.0f, 83.0f};

    /** Comb score above which the audio is considered digital. See calibration note above. */
    private static final float DETECTION_THRESHOLD = 20.0f;

    /**
     * Windows over threshold required to declare interference. One, deliberately: DMR bursts are
     * short and intermittent, and a veto that arrives 300 ms late has already let the buzz
     * through. The margin between real DMR (peak 64 or above per burst) and the worst negative
     * (13) is wide enough that a single window is safe, and RELEASE_WINDOWS provides the
     * hysteresis instead.
     */
    private static final int CONFIRMATION_WINDOWS = 1;

    /** Consecutive windows below threshold required to clear it. */
    private static final int RELEASE_WINDOWS = 3;

    /**
     * AP-fork: comb score at or above which a window is written to the survey log. Measurement only;
     * this gates logging and nothing else. Clean Bolton Fire voice measured a median of 1.2-1.7 and a
     * peak of 11.1, so 5.0 keeps every window that is anywhere near DETECTION_THRESHOLD while skipping
     * the quiet majority. Windows latched by the detector are logged regardless of score, so the full
     * shape of an episode is captured rather than only its loud part.
     */
    private static final float SURVEY_FLOOR = 5.0f;

    /** High-pass corner (Hz) applied to the envelope to remove syllabic speech modulation. */
    private static final float ENVELOPE_HIGHPASS_HZ = 15.0f;

    private final float mSampleRate;
    private final float mEnvelopeRate;
    private final int mSubBlockSamples;
    private final int mWindowLength;

    private final float[] mEnvelope;
    private int mEnvelopeIndex = 0;
    private boolean mEnvelopeFilled = false;

    // Sub-block RMS accumulation
    private float mSumSquares = 0.0f;
    private int mSubBlockCount = 0;

    // One-pole high-pass state for the envelope
    private final float mHighPassAlpha;
    private float mHpPrevInput = 0.0f;
    private float mHpPrevOutput = 0.0f;

    private int mAboveCount = 0;
    private int mBelowCount = 0;
    private volatile boolean mInterferenceDetected = false;
    private volatile float mLastScore = 0.0f;

    /**
     * AP-fork: the comb score that latched mInterferenceDetected, retained for the duration of the latch.
     * mLastScore tracks the most recent window and decays while the latch is held, so it is misleading in any
     * message about why the gate is closed.  See updateState().
     */
    private volatile float mTriggerScore = 0.0f;

    private String mChannelLabel = "";

    /**
     * Constructs a detector for the given audio sample rate (typically 8 kHz post-resampler).
     *
     * @param sampleRate of the audio being fed to {@link #process(float[])}
     */
    public TdmaInterferenceDetector(float sampleRate)
    {
        mSampleRate = sampleRate;
        mSubBlockSamples = Math.max(1, (int)(sampleRate * SUB_BLOCK_MS / 1000.0f));
        mEnvelopeRate = sampleRate / mSubBlockSamples;
        mWindowLength = Math.max(32, (int)(mEnvelopeRate * WINDOW_MS / 1000.0f));
        mEnvelope = new float[mWindowLength];

        // One-pole high-pass: y[n] = a*(y[n-1] + x[n] - x[n-1])
        float rc = 1.0f / (2.0f * (float)Math.PI * ENVELOPE_HIGHPASS_HZ);
        float dt = 1.0f / mEnvelopeRate;
        mHighPassAlpha = rc / (rc + dt);
    }

    /**
     * Sets the channel label used in log messages.
     */
    public void setChannelLabel(String channelLabel)
    {
        mChannelLabel = channelLabel != null ? channelLabel : "";
    }

    /**
     * True when a 2-slot TDMA digital signal is currently present on this channel.
     */
    public boolean isInterferenceDetected()
    {
        return mInterferenceDetected;
    }

    /**
     * Most recent comb score, for diagnostics.
     */
    public float getLastScore()
    {
        return mLastScore;
    }

    /**
     * AP-fork: the comb score that latched the current interference state, or 0 when not latched.  Prefer this over
     * getLastScore() in any message explaining why the tone gate is closed - see the field note on mTriggerScore.
     *
     * @return triggering comb score.
     */
    public float getTriggerScore()
    {
        return mTriggerScore;
    }

    /**
     * AP-fork: the comb score at or above which interference is declared, exposed so log messages can state the
     * threshold alongside the measured score rather than leaving the reader to guess it.
     *
     * @return detection threshold.
     */
    public static float getDetectionThreshold()
    {
        return DETECTION_THRESHOLD;
    }

    /**
     * Clears all detector state. Called when squelch closes or the channel resets.
     */
    public void reset()
    {
        mEnvelopeIndex = 0;
        mEnvelopeFilled = false;
        mSumSquares = 0.0f;
        mSubBlockCount = 0;
        mHpPrevInput = 0.0f;
        mHpPrevOutput = 0.0f;
        mAboveCount = 0;
        mBelowCount = 0;
        mInterferenceDetected = false;
        mLastScore = 0.0f;
    }

    /**
     * Feeds demodulated audio to the detector.
     *
     * @param audio resampled audio samples
     */
    public void process(float[] audio)
    {
        if(audio == null || audio.length == 0)
        {
            return;
        }

        for(float sample : audio)
        {
            mSumSquares += sample * sample;
            mSubBlockCount++;

            if(mSubBlockCount >= mSubBlockSamples)
            {
                float rms = (float)Math.sqrt(mSumSquares / mSubBlockCount);
                mSumSquares = 0.0f;
                mSubBlockCount = 0;

                // High-pass the envelope to remove syllabic speech modulation
                float hp = mHighPassAlpha * (mHpPrevOutput + rms - mHpPrevInput);
                mHpPrevInput = rms;
                mHpPrevOutput = hp;

                mEnvelope[mEnvelopeIndex++] = hp;

                if(mEnvelopeIndex >= mWindowLength)
                {
                    mEnvelopeFilled = true;
                    evaluate();

                    // Slide the window by 50% rather than starting fresh. Overlapping windows
                    // double the evaluation rate (one per ~150ms instead of ~300ms) and stop a
                    // short burst from being missed because it straddled a window boundary.
                    // The buffer is kept in chronological order so the Goertzel pass sees no
                    // wrap discontinuity.
                    int half = mWindowLength / 2;
                    System.arraycopy(mEnvelope, half, mEnvelope, 0, mWindowLength - half);
                    mEnvelopeIndex = mWindowLength - half;
                }
            }
        }
    }

    /**
     * Scores the current envelope window and updates the detection state.
     */
    private void evaluate()
    {
        if(!mEnvelopeFilled)
        {
            return;
        }

        // Remove any residual DC so Goertzel bins reflect modulation only
        float mean = 0.0f;
        for(float v : mEnvelope)
        {
            mean += v;
        }
        mean /= mWindowLength;

        float variance = 0.0f;
        for(float v : mEnvelope)
        {
            float d = v - mean;
            variance += d * d;
        }

        if(variance <= 1e-12f)
        {
            updateState(0.0f);
            return;
        }

        // Noise floor: median power across off-comb reference frequencies
        float[] refPower = new float[REFERENCE_RATES.length];
        for(int i = 0; i < REFERENCE_RATES.length; i++)
        {
            refPower[i] = goertzelPower(REFERENCE_RATES[i], mean);
        }
        java.util.Arrays.sort(refPower);
        float noiseFloor = refPower[refPower.length / 2] + 1e-12f;

        float best = 0.0f;

        //AP-fork: retain which candidate rate won and the three harmonic ratios behind it. See the
        //SURVEY block below for why.
        float bestRate = 0.0f;
        float bestH1 = 0.0f;
        float bestH2 = 0.0f;
        float bestH3 = 0.0f;

        for(float f0 : CANDIDATE_RATES)
        {
            float product = 1.0f;
            boolean usable = true;
            float h1 = 0.0f;
            float h2 = 0.0f;
            float h3 = 0.0f;

            for(int harmonic = 1; harmonic <= 3; harmonic++)
            {
                float target = f0 * harmonic;

                // Stay well inside the envelope Nyquist limit
                if(target >= mEnvelopeRate / 2.0f - 5.0f)
                {
                    usable = false;
                    break;
                }

                float ratio = goertzelPower(target, mean) / noiseFloor;
                product *= ratio;

                if(harmonic == 1)
                {
                    h1 = ratio;
                }
                else if(harmonic == 2)
                {
                    h2 = ratio;
                }
                else
                {
                    h3 = ratio;
                }
            }

            if(usable)
            {
                // Geometric mean of the three harmonic ratios
                float score = (float)Math.cbrt(product);

                if(score > best)
                {
                    best = score;
                    bestRate = f0;
                    bestH1 = h1;
                    bestH2 = h2;
                    bestH3 = h3;
                }
            }
        }

        //AP-fork: SURVEY. Measurement only - nothing below changes a decision.
        //
        //The class note says requiring three harmonics "is what separates a genuine TDMA cadence from
        //broad modulation that merely happens to have energy near 33 Hz". It does not. The score is the
        //geometric mean of the three ratios, so one strong harmonic carries a window in which the
        //fundamental is absent: cbrt(0.3 * 7.2 * 6.1) still clears a window where there is no energy at
        //the slot rate at all. Reimplementing this exact algorithm against six Bolton Fire recordings on
        //2026-10-05 produced winning rates wandering across 32.5, 33.33, 34.0 and 35.0 with lopsided
        //ratios - H1=0.3, H2=7.2, H3=6.1 in one window. A real 2-slot cadence should win at 33.33
        //consistently with all three harmonics strong together.
        //
        //That analysis could not be conclusive, because this detector taps BEFORE the gate
        //(NBFMDecoder:646, ahead of the gate decisions at 653) while the recordings hold only audio that
        //passed. The windows that scored 24-127 on Bolton that evening are in the gaps the veto cut, and
        //were never written to disk. Hence this survey: the per-window numbers cannot be recovered from
        //the recordings and have to be logged where they are computed.
        //
        //Logged only at or above SURVEY_FLOOR, or while latched, so the quiet majority costs nothing -
        //measured median score on clean Bolton voice is 1.2-1.7, and clean voice peaked at 11.1 against
        //a DETECTION_THRESHOLD of 20.0. Windows evaluate about every 150 ms per channel.
        if(LOGGER.isDebugEnabled() && (best >= SURVEY_FLOOR || mInterferenceDetected))
        {
            LOGGER.debug("[{}] TDMA survey: score={} rate={} H1={} H2={} H3={} noiseFloor={} latched={}",
                    mChannelLabel, String.format("%.1f", best), String.format("%.2f", bestRate),
                    String.format("%.1f", bestH1), String.format("%.1f", bestH2),
                    String.format("%.1f", bestH3), String.format("%.3g", noiseFloor),
                    mInterferenceDetected);
        }

        updateState(best);
    }

    /**
     * Goertzel power at the given envelope-domain frequency.
     *
     * @param frequency in Hz within the envelope signal
     * @param mean of the envelope window, subtracted from each sample
     * @return power at that frequency
     */
    private float goertzelPower(float frequency, float mean)
    {
        float coefficient = 2.0f * (float)Math.cos(2.0 * Math.PI * frequency / mEnvelopeRate);
        float s1 = 0.0f;
        float s2 = 0.0f;

        for(float value : mEnvelope)
        {
            float s0 = (value - mean) + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return (s1 * s1) + (s2 * s2) - (coefficient * s1 * s2);
    }

    /**
     * Applies hysteresis to the raw score and logs state transitions.
     */
    private void updateState(float score)
    {
        mLastScore = score;

        if(score >= DETECTION_THRESHOLD)
        {
            mAboveCount++;
            mBelowCount = 0;

            if(!mInterferenceDetected && mAboveCount >= CONFIRMATION_WINDOWS)
            {
                mInterferenceDetected = true;
                //AP-fork: remember the score that actually latched the detector.  Consumers log getLastScore(),
                //which by then has decayed to whatever the current window measured - the 2026-09-23 Daly log shows
                //gate closures reporting scores as low as 0.4 against a DETECTION_THRESHOLD of 20.0, which reads as
                //a broken threshold and is in fact just a stale figure.  getTriggerScore() reports the real one.
                mTriggerScore = score;
                LOGGER.debug("[{}] TDMA digital interference detected (comb score={}) — tone gate vetoed",
                        mChannelLabel, String.format("%.1f", score));
            }
        }
        else
        {
            mBelowCount++;
            mAboveCount = 0;

            if(mInterferenceDetected && mBelowCount >= RELEASE_WINDOWS)
            {
                mInterferenceDetected = false;
                LOGGER.debug("[{}] TDMA digital interference cleared (comb score={})",
                        mChannelLabel, String.format("%.1f", score));
            }
        }
    }
}
