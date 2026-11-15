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
 * AP-fork: the geometric mean does NOT require all three harmonics - one or two strong ones carry a
 * window whose fundamental is absent. Since ap-15.9.17 the fundamental must also clear
 * FUNDAMENTAL_FLOOR on its own and must not be outweighed by H2 or H3 by more than HARMONIC_EXCESS
 * before a window can latch. See those constants for the measurement.
 *
 * AP-fork: since ap-15.9.18 the audio has its DC removed before step 1. See DC_BLOCK_HZ.
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

    /**
     * AP-fork: ratio to the noise floor that the winning candidate's FUNDAMENTAL (H1) must reach on its own
     * before a window over DETECTION_THRESHOLD may latch the detector. Paired with HARMONIC_EXCESS below.
     *
     * The comb score is the geometric mean of H1, H2 and H3, so strong 2nd/3rd harmonics carry a window with
     * no energy at the slot rate at all. Measured on Summit 2026-10-06 (ap-15.9.16 survey, 13:36-21:33):
     *
     *   Bolton Fire 453.600     31 windows latched while the gate was open and cut a call.
     *                           H1 of those windows: median 3.8, p90 9.0, max 16.2. H1 was the weakest
     *                           harmonic in 111 of all 147 latches. Winning rate wandered 32.0-35.0,
     *                           33.33 in 25 of 147.
     *   Littleton Fire 460.2625 69 call-cutting windows. H1 median 1.5, p90 3.6, max 11.6.
     *
     * A floor of 20 removes all 100 of those cuts. Bolton's 34 latches with H1 >= 20 (23 of them at 33.33)
     * all fell while its gate was closed, cut nothing, and still latch under this rule.
     *
     * Why the fundamental and not every harmonic individually: in any rectangular pulse train harmonic n has
     * amplitude |sin(n*pi*d)|/n, which never exceeds the fundamental's |sin(pi*d)| at any duty cycle d. A real
     * slot cadence therefore cannot lack its fundamental, but it can lack H2 (it vanishes at d = 0.5), so
     * requiring H2 individually would reject cadences this detector exists to catch.
     *
     * Not yet verified against a real DMR burst - none was in the measuring log. Windows refused by this rule
     * are logged at DEBUG ("TDMA veto refused") so the next log shows what it let through.
     */
    private static final float FUNDAMENTAL_FLOOR = 20.0f;

    /**
     * AP-fork: largest factor by which H2 or H3 may exceed H1 (power ratios) in a window that latches. Same
     * pulse-train bound as above - no harmonic of a real cadence exceeds its fundamental - with a 10x allowance
     * for noise and non-rectangular slot edges.
     *
     * It exists because the floor alone has thin margin: the worst real cut had H1=16.2 against a floor of 20,
     * and a noise-floor dip inflates all three ratios together. A synthetic envelope modulated at 66.7 Hz only
     * (no slot-rate energy) reached H1=23.5 with H2=2204 in one window and latched on the floor alone; this
     * refuses it (94x). On the real 2026-10-06 cuts, max(H2,H3)/H1 was min 11.3, median 1489, and on every
     * channel it removes at most one latch beyond what the floor already removes.
     */
    private static final float HARMONIC_EXCESS = 10.0f;

    /**
     * AP-fork: corner (Hz) of the one-pole DC blocker applied to the AUDIO before it is squared into the envelope.
     *
     * Squaring audio that carries a DC offset plus a steady tone produces a component at the tone's own frequency:
     * (dc + a*sin(wt))^2 contains 2*dc*a*sin(wt). FM demodulation turns any carrier frequency error into exactly such
     * an offset, and every tone-filtered channel carries a steady CTCSS tone - so the envelope inherits the channel's
     * own tone. Three CTCSS tones land on or near the comb: 100.0 Hz is 3 x 33.33, 67.0 Hz is 2 x 33.5, and 74.4 Hz
     * leaks into the 2nd harmonic of the 34.0-35.0 candidates.
     *
     * Measured (ap-15.9.17 survey): Lawrence Fire and Littleton Fire are the only channels in the Daly and Summit
     * logs on 100.0 Hz, and the only ones where H3 dominates - on Lawrence, H3 was the strongest harmonic in all
     * 3,493 windows scoring 20 or more. Bolton Fire (74.4 Hz) carries a steady H2 of 30-80 at rates 33.0-35.0 with H1
     * near 1.5. Reproduced against this class: a 100 Hz tone plus a DC offset of 0.2 scored H3=1811, H1=0.5; the same
     * tone with no offset scored nothing. The field offset itself has not been measured.
     *
     * Removing DC removes the cross term at its source. A steady tone alone squares to twice its frequency (200,
     * 134 and 149 Hz for those three), all off the comb. 5 Hz passes 67 Hz at 0.997 and settles in about 30 ms. The
     * TDMA cadence the detector looks for is a modulation of the noise POWER, which DC removal does not touch.
     */
    private static final float DC_BLOCK_HZ = 5.0f;

    /** High-pass corner (Hz) applied to the envelope to remove syllabic speech modulation. */
    private static final float ENVELOPE_HIGHPASS_HZ = 15.0f;

    private final float mSampleRate;
    private final float mEnvelopeRate;
    private final int mSubBlockSamples;
    private final int mWindowLength;

    private final float[] mEnvelope;
    private int mEnvelopeIndex = 0;
    private boolean mEnvelopeFilled = false;

    //AP-fork: one-pole DC blocker state for the audio. See DC_BLOCK_HZ.
    private final float mDcBlockR;
    private float mDcPrevInput = 0.0f;
    private float mDcPrevOutput = 0.0f;

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
     * AP-fork (ap-15.9.20): lowest and highest winning rate, in Hz, that counts as the TDMA slot cadence for
     * SUSPECT_FUNDAMENTAL. Covers 32.5, 33.0, 33.33 and 34.0 - the candidates real DMR has won in the surveys.
     */
    private static final float SUSPECT_RATE_MIN = 32.5f;
    private static final float SUSPECT_RATE_MAX = 34.0f;

    /**
     * AP-fork (ap-15.9.20): fundamental (H1) at the slot cadence that marks a window as suspected TDMA even though
     * its comb score is below DETECTION_THRESHOLD. NBFMDecoder uses this only to decide whether to release audio
     * that a WEAK tone confirmation has buffered - it never cuts a call that is already flowing.
     *
     * Somerville 2026-10-14 07:49:30, Somerville Fire Ch 2: a DMR burst confirmed 131.8 at 12.6 dB and went to
     * Zello. The detector never latched - scores 5.9, 11.5, 8.2 - because the burst carried almost all of its
     * energy in the fundamental (H1 21.4, 58.7, 38.6) and next to none in H2, which the geometric-mean score
     * punishes. Across 2026-10-13/14, matched against 1,179 recordings, requiring H1 >= 20 at these rates (in a
     * window that reached SURVEY_FLOOR) alongside a weak confirmation stops 226 of 315 bursts and 9 of 864
     * voice calls.
     */
    private static final float SUSPECT_FUNDAMENTAL = 20.0f;

    /** AP-fork (ap-15.9.20): strongest slot-cadence fundamental since reset() or clearSuspicion(), and its rate. */
    private volatile float mSuspectFundamental = 0.0f;
    private volatile float mSuspectRate = 0.0f;

    /**
     * AP-fork (ap-15.9.22): MEASUREMENT ONLY. Windows since reset() whose winner sits at the slot cadence
     * (SUSPECT_RATE_MIN-MAX) with a comb score of at least PERSIST_SCORE, latched or not. Audubon 2026-10-16: Swampscott
     * Police bursts produce a steady run of such windows at scores 6-19 that never reach DETECTION_THRESHOLD, while
     * its voice rarely does - a candidate rule that needs a day of logs and recordings to validate.
     */
    private static final float PERSIST_SCORE = 6.0f;
    private int mPersistWindows = 0;
    private int mWindows = 0;

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

        //AP-fork: y[n] = x[n] - x[n-1] + R*y[n-1]
        mDcBlockR = 1.0f - (float)(2.0 * Math.PI * DC_BLOCK_HZ / sampleRate);
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
        mDcPrevInput = 0.0f;
        mDcPrevOutput = 0.0f;
        mAboveCount = 0;
        mBelowCount = 0;
        mInterferenceDetected = false;
        mLastScore = 0.0f;
        clearSuspicion();
        mPersistWindows = 0;
        mWindows = 0;
    }

    /**
     * AP-fork (ap-15.9.22): measurement only - see PERSIST_SCORE.
     *
     * @return description of the windows evaluated since reset()
     */
    public String describePersistence()
    {
        return mPersistWindows + " of " + mWindows + " windows at the slot cadence with score >= " +
                String.format("%.0f", PERSIST_SCORE) + " (strongest H1 " + String.format("%.1f", mSuspectFundamental) + ")";
    }

    /**
     * AP-fork (ap-15.9.20): true when any window since reset() or clearSuspicion() showed a strong fundamental at the
     * TDMA slot cadence, whether or not it latched. See SUSPECT_FUNDAMENTAL.
     */
    public boolean isSlotCadenceSuspected()
    {
        return mSuspectFundamental >= SUSPECT_FUNDAMENTAL;
    }

    /** AP-fork (ap-15.9.20): strongest slot-cadence fundamental behind isSlotCadenceSuspected(), for logging. */
    public float getSuspectFundamental()
    {
        return mSuspectFundamental;
    }

    /** AP-fork (ap-15.9.20): winning rate of that window, for logging. */
    public float getSuspectRate()
    {
        return mSuspectRate;
    }

    /**
     * AP-fork (ap-15.9.20): forgets slot-cadence suspicion without touching the envelope or the latch. Called after
     * held audio has been discarded, so a real call that keeps transmitting is judged on what follows rather than on
     * the burst that was thrown away.
     */
    public void clearSuspicion()
    {
        mSuspectFundamental = 0.0f;
        mSuspectRate = 0.0f;
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

        for(float input : audio)
        {
            //AP-fork: remove DC before squaring. See DC_BLOCK_HZ.
            float sample = input - mDcPrevInput + mDcBlockR * mDcPrevOutput;
            mDcPrevInput = input;
            mDcPrevOutput = sample;

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
            updateState(0.0f, false);
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

        //AP-fork: SURVEY. Measurement only - the survey line itself changes no decision. The fundamental gate
        //that follows it does; see FUNDAMENTAL_FLOOR.
        //
        //The class note says requiring three harmonics "is what separates a genuine TDMA cadence from
        //broad modulation that merely happens to have energy near 33 Hz". It does not. The score is the
        //geometric mean of the three ratios, so one strong harmonic carries a window in which the
        //fundamental is absent: on Summit 2026-10-06 a Bolton window scored 23.4 at rate 33.33 from
        //H1=2.4 H2=208.5 H3=26.1 and cut a call. Reimplementing this exact algorithm against six Bolton Fire recordings on
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

        //AP-fork: FUNDAMENTAL GATE (ap-15.9.17). A window over DETECTION_THRESHOLD may only latch if the winning
        //candidate's fundamental clears FUNDAMENTAL_FLOOR on its own and neither H2 nor H3 outweighs it by more
        //than HARMONIC_EXCESS. Decided on the same winner the survey logs, which is exactly what the 2026-10-06
        //measurement simulated. A refused window counts as below threshold, so it also releases a latch like
        //any other quiet window.
        //AP-fork (ap-15.9.22): measurement only - see PERSIST_SCORE.
        mWindows++;
        if(best >= PERSIST_SCORE && bestRate >= SUSPECT_RATE_MIN && bestRate <= SUSPECT_RATE_MAX)
        {
            mPersistWindows++;
        }

        //AP-fork (ap-15.9.20): remember the strongest slot-cadence fundamental, using the same windows the survey
        //logs (score at or above SURVEY_FLOOR), which is what the 2026-10-13/14 measurement was taken from.
        if(best >= SURVEY_FLOOR && bestRate >= SUSPECT_RATE_MIN && bestRate <= SUSPECT_RATE_MAX &&
                bestH1 > mSuspectFundamental)
        {
            mSuspectFundamental = bestH1;
            mSuspectRate = bestRate;
        }

        boolean fundamentalPresent = bestH1 >= FUNDAMENTAL_FLOOR &&
                Math.max(bestH2, bestH3) <= bestH1 * HARMONIC_EXCESS;

        if(best >= DETECTION_THRESHOLD && !fundamentalPresent && !mInterferenceDetected && LOGGER.isDebugEnabled())
        {
            LOGGER.debug("[{}] TDMA veto refused: comb score={} rate={} has no fundamental - H1={} H2={} H3={} " +
                    "(needs H1 >= {} and H2,H3 <= {}x H1)", mChannelLabel, String.format("%.1f", best),
                    String.format("%.2f", bestRate), String.format("%.1f", bestH1), String.format("%.1f", bestH2),
                    String.format("%.1f", bestH3), String.format("%.1f", FUNDAMENTAL_FLOOR),
                    String.format("%.0f", HARMONIC_EXCESS));
        }

        updateState(best, fundamentalPresent);
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
     *
     * @param score comb score of the window
     * @param fundamentalPresent AP-fork: true when the winning candidate's H1 cleared FUNDAMENTAL_FLOOR and was not
     * outweighed by more than HARMONIC_EXCESS. A window without it is treated as below threshold however high its
     * score.
     */
    private void updateState(float score, boolean fundamentalPresent)
    {
        mLastScore = score;

        if(score >= DETECTION_THRESHOLD && fundamentalPresent)
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
