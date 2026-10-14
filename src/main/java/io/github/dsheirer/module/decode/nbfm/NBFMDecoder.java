/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.dsp.audio.HumAnalyzer;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.nbfm.NBFMAudioFilters;
import io.github.dsheirer.dsp.filter.resample.RealResampler;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.squelch.INoiseSquelchController;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.dsp.squelch.NoiseSquelchState;
import io.github.dsheirer.dsp.squelch.SquelchTailRemover;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.SquelchControlDecoder;
import io.github.dsheirer.module.decode.config.ChannelToneFilter;
import io.github.dsheirer.module.decode.ctcss.CTCSSCode;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.sample.complex.NoiseBlanker;
import io.github.dsheirer.sample.real.IRealBufferProvider;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * NBFM decoder with integrated noise squelch, CTCSS tone filtering, and squelch tail removal.
 *
 * Demodulates complex sample buffers and feeds unfiltered, demodulated audio to Noise Squelch.
 * Squelch operates on the noise level with open and close thresholds to pass low-noise audio
 * and block high-noise audio.  Audio is filtered and resampled to 8 kHz for downstream consumers.
 *
 * When CTCSS tone filtering is enabled, the resampled audio is analyzed by a CTCSSDetector.
 * When DCS code filtering is enabled, the resampled audio is analyzed by a DCSDetector.
 * Audio is only passed downstream when the configured tone/code is confirmed present.
 * This prevents hearing distant/interfering signals on the same frequency that use a
 * different CTCSS tone or DCS code. The channel goes fully idle when the wrong tone/code
 * is detected — just like a real radio with tone squelch.
 *
 * When squelch tail removal is enabled, a SquelchTailRemover buffers audio and discards
 * the trailing noise burst that occurs when a transmitter drops carrier.
 */
public class NBFMDecoder extends SquelchControlDecoder implements ISourceEventListener, IComplexSamplesListener,
        Listener<ComplexSamples>, IRealBufferProvider, IDecoderStateEventProvider, INoiseSquelchController
{
    private final static Logger mLog = LoggerFactory.getLogger(NBFMDecoder.class);
    private NBFMDecoderState mDecoderState;
    private static final double DEMODULATED_AUDIO_SAMPLE_RATE = 8000.0;
    private final IDemodulator mDemodulator = FmDemodulatorFactory.getFmDemodulator();
    private final SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();
    private final NoiseSquelch mNoiseSquelch;
    private IRealFilter mIBasebandFilter;
    private IRealFilter mQBasebandFilter;
    private IRealDecimationFilter mIDecimationFilter;
    private IRealDecimationFilter mQDecimationFilter;
    private Listener<float[]> mResampledBufferListener;
    private Listener<DecoderStateEvent> mDecoderStateEventListener;
    private RealResampler mResampler;
    private final double mChannelBandwidth;

    // CTCSS/DCS tone filtering
    private final boolean mToneFilterEnabled;
    private final ChannelToneFilter.ToneType mToneFilterType;
    private final Set<CTCSSCode> mTargetCTCSSCodes;
    private final Set<DCSCode> mTargetDCSCodes;
    private CTCSSDetector mCTCSSDetector;
    private DCSDetector mDCSDetector;
    private volatile boolean mToneMatch = false;

    /**
     * Rejects 2-slot TDMA digital bleed (DMR / P25 Phase 2) that would otherwise satisfy the
     * CTCSS detector by lighting up the target bin with broadband noise. Only instantiated when
     * tone filtering is enabled, since it exists purely to protect the tone gate.
     */
    private TdmaInterferenceDetector mTdmaDetector;

    /**
     * Holdover period (ms) for tone match across brief squelch closures.
     * When noise squelch flutters closed and re-opens within this window,
     * the previously confirmed tone match is preserved — avoiding a full
     * re-detection delay (225ms+ for CTCSS, 340ms+ for DCS) on each flutter.
     * This mimics real radio behavior where tone squelch has a short holdover.
     */
    private static final long TONE_HOLDOVER_MS = 500;
    private volatile long mLastToneMatchTime = 0;

    /**
     * Maximum time (ms) a gate opened via holdover may continue passing audio without the
     * detector re-confirming the target tone. The holdover check at squelch-open only gates
     * ENTRY; once open the gate previously stayed open until the loss counter closed it, which
     * digital interference could prevent indefinitely. This is the hard backstop: if the tone
     * is not re-confirmed within this window the gate closes regardless.
     *
     * Sized to comfortably exceed a normal confirmation (3 blocks, ~250ms) while cutting off
     * the multi-second interference bursts seen on Somerville Fire.
     */
    private static final long HOLDOVER_CONFIRM_DEADLINE_MS = 600;

    /** Timestamp the gate was opened via holdover, or 0 when not in a holdover-opened state. */
    private volatile long mHoldoverOpenTime = 0;

    /**
     * AP-fork: when the holdover confirmation deadline was first exceeded in the current episode, or 0.
     * Measurement only - see the "WOULD CLOSE" block in processResampledAudio() and
     * {@link #noteHoldoverReconfirmed()}.
     */
    private volatile long mHoldoverDeadlineExceededTime = 0;

    /**
     * AP-fork: has one of the channel's target tones won the Goertzel bin contest outright at any
     * point in the current squelch episode?
     *
     * This is the evidence the holdover veto below stands on. 15.9.14 stopped the detector converting
     * an adjacent tone into the target, and the Summit 2026-10-05 log confirms it: the score pins at
     * -8 and the suppression line fires several hundred times an hour. But the gate was never opened
     * by the tone decision in the first place - all 977 holdover gate-opens on Bolton Fire that day
     * carried confirmed=null, so the holdover path was passing audio with nothing confirmed, and four
     * of the six recordings Armen identified were Trinity splashover admitted this way.
     *
     * The obvious fix - close the gate once the raw winner has been a non-target for N ms - does not
     * survive measurement. Real Bolton calls spend long stretches with a non-target raw winner: median
     * 1249 ms, max 2100 ms across the 16 confirmed-real episodes in that log. Any grace period tight
     * enough to catch the bleed (400-1000 ms) truncates 13 or 14 of those 16 calls. Timing cannot
     * separate the two populations.
     *
     * What does separate them is whether the target EVER wins a bin. Of 12 bleed episodes, 9 never saw
     * the target as raw winner at all - including every one of the four flagged recordings - while all
     * 16 real episodes did. So the flag latches on first sight and holds for the episode: 0 of 16 real
     * episodes affected, 9 of 12 bleed episodes closed. The 3 survivors each had the target genuinely
     * win a bin briefly, which is ambiguous, and leaving an ambiguous gate open is the safe direction.
     *
     * Reset in the full-reset branch at squelch-open, alongside CTCSSDetector.reset(). Deliberately
     * PRESERVED across a holdover-opened squelch episode: holdover means "trust the confirmation from
     * the call this is continuing", so it inherits that call's bin-contest evidence too. A keyup
     * arriving more than TONE_HOLDOVER_MS after the previous call takes the full-reset branch and
     * starts with no evidence, which is what makes an isolated splashover keyup close.
     */
    private volatile boolean mRawTargetWonThisEpisode = false;

    // Channel identification for log messages
    private volatile String mChannelLabel = "";
    private volatile long mChannelFrequencyHz = 0;

    // Squelch tail/head removal
    private final boolean mSquelchTailRemovalEnabled;
    private final int mSquelchTailRemovalMs;
    private final int mSquelchHeadRemovalMs;
    private SquelchTailRemover mSquelchTailRemover;

    /**
     * Mains hum diagnostic, or null when the diagnostic is switched off.  See HumAnalyzer.
     */
    private HumAnalyzer mHumAnalyzer;

    // VoxSend audio filter chain (low-pass, de-emphasis, bass boost, voice enhancement, noise gate)
    private NBFMAudioFilters mAudioFilters;
    private final DecodeConfigNBFM mNBFMConfig;

    // Noise blanker — IQ-domain impulse noise suppression (same approach as P25 decoders)
    private final NoiseBlanker mNoiseBlanker = new NoiseBlanker();
    private final boolean mNoiseBlankerEnabled;

    // CTCSS/DCS tone confirmation audio buffer — holds audio during the detection
    // window so it can be released retroactively when the tone is confirmed,
    // rather than being discarded and clipping the start of every call.
    private final List<float[]> mPendingToneAudio = new ArrayList<>();
    private int mPendingToneSampleCount = 0;

    // AP-fork (ap-15.9.20): a weak CTCSS confirmation is being held for one TDMA window. See WEAK_CONFIRM_SNR_DB.
    // While true, mToneMatch is true but no call has been started and the pending buffer is still accumulating.
    private boolean mConfirmHoldActive = false;
    private int mConfirmHoldSamples = 0;
    private static final int MAX_PENDING_TONE_SAMPLES = 4000; // 500ms at 8kHz

    /**
     * AP-fork (ap-15.9.20): a CTCSS confirmation whose strongest target block is below this SNR is treated as
     * possibly assembled by DMR noise, and its buffered audio is held for CONFIRM_HOLD_SAMPLES before release.
     *
     * Broadband TDMA bleed sprays chance hits across every CTCSS bin, and now and then three of them land on the
     * target. Those confirmations are weak. Somerville 2026-10-13/14, matched against 1,179 recordings: bursts that
     * confirmed 131.8 did so at a median 13.1 dB on MetroFire Red, 12.9 dB on MetroFire White and 12.6 dB on
     * Somerville Fire Ch 2; real calls at 16.4, 21.4 and 16.0 dB. Brookline Fire leaks confirmed at 9.3 dB against
     * 23.2 dB for its clean releases. A strong confirmation is released at once, exactly as before.
     */
    private static final float WEAK_CONFIRM_SNR_DB = 16.0f;

    /**
     * AP-fork (ap-15.9.20): how long a weak confirmation's audio is held for the TDMA detector to look at it - one
     * 300 ms window, about two evaluations at the detector's 150 ms hop. Measured on the same data, a 300 ms hold
     * stops 226 of 315 bursts (latch, or a slot-cadence fundamental - see
     * TdmaInterferenceDetector.isSlotCadenceSuspected()) and discards 9 of 864 voice calls; 1,064 of 21,854 call
     * starts on 2026-10-13 confirmed weakly and start up to 300 ms later. A discard drops only the held opening:
     * the detectors are re-armed, so a real call that keeps transmitting confirms again and goes through.
     */
    private static final int CONFIRM_HOLD_SAMPLES = 2400; // 300ms at 8kHz

    // Stuck timer watchdog — force-ends calls that exceed max duration (stuck carrier protection)
    private final boolean mMaxCallDurationEnabled;
    private final long mMaxCallDurationMs;
    private volatile long mCallStartTimeMs = 0;
    private volatile boolean mWatchdogTripped = false;

    /**
     * AP-fork: wall time that audio last reached processResampledAudio(), i.e. the last moment the squelch actually
     * passed audio downstream.  Used only by the stuck-call watchdog to separate a real stuck carrier from a stale
     * call timer.
     */
    private volatile long mLastResampledAudioTimeMs = 0;

    /**
     * AP-fork: wall time the noise squelch last opened, or 0 while closed.  Measurement only - see logSquelchOpen() and
     * logSquelchClose().  Two open questions both turn on what the squelch actually does, and the logs have never said:
     * the 1.4-2.5 s pause at the end of Bolton calls, and the ap-15.9.15 holdover veto, whose latch is only cleared in
     * the full-reset branch at squelch-open and has never been seen to clear.  Whether the squelch closes at all during
     * those episodes, and which open branch it takes when it reopens, is the missing evidence.
     */
    private volatile long mSquelchOpenTimeMs = 0;

    /**
     * Constructs an instance
     *
     * @param config to setup the NBFM decoder and noise squelch control.
     */
    public NBFMDecoder(DecodeConfigNBFM config)
    {
        super(config);

        //Save config reference for audio filter initialization (deferred until sample rate is known)
        mNBFMConfig = config;

        // Noise blanker and stuck timer watchdog configuration
        mNoiseBlankerEnabled = config.isNoiseBlankerEnabled();
        mMaxCallDurationEnabled = config.isMaxCallDurationEnabled();
        mMaxCallDurationMs = config.getMaxCallDurationSeconds() * 1000L;

        //Save channel bandwidth to setup channel baseband filter.
        mChannelBandwidth = config.getBandwidth().getValue();
        mNoiseSquelch = new NoiseSquelch(config.getSquelchNoiseOpenThreshold(), config.getSquelchNoiseCloseThreshold(),
                config.getSquelchHysteresisOpenThreshold(), config.getSquelchHysteresisCloseThreshold());

        // Extract CTCSS/DCS tone filter configuration
        mToneFilterEnabled = config.hasToneFiltering();
        if(mToneFilterEnabled)
        {
            mTargetCTCSSCodes = extractCTCSSCodes(config.getToneFilters());
            mTargetDCSCodes = extractDCSCodes(config.getToneFilters());

            // Determine which type of filter is configured
            if(!mTargetCTCSSCodes.isEmpty())
            {
                mToneFilterType = ChannelToneFilter.ToneType.CTCSS;
                mLog.info("[{}] CTCSS tone filtering enabled with {} target code(s)", mChannelLabel, mTargetCTCSSCodes.size());
            }
            else if(!mTargetDCSCodes.isEmpty())
            {
                mToneFilterType = ChannelToneFilter.ToneType.DCS;
                mLog.info("[{}] DCS code filtering enabled with {} target code(s)", mChannelLabel, mTargetDCSCodes.size());
            }
            else
            {
                mToneFilterType = null;
                mLog.warn("[{}] Tone filtering enabled but no valid CTCSS or DCS codes configured", mChannelLabel);
            }
        }
        else
        {
            mTargetCTCSSCodes = null;
            mTargetDCSCodes = null;
            mToneFilterType = null;
        }

        // Extract squelch tail removal configuration
        mSquelchTailRemovalEnabled = config.isSquelchTailRemovalEnabled();
        mSquelchTailRemovalMs = config.getSquelchTailRemovalMs();
        mSquelchHeadRemovalMs = config.getSquelchHeadRemovalMs();

        //Send squelch controlled audio to the resampler.
        //Only notify call continuation if tone filter is not active, or if tone matches.
        //This makes the channel behave like a real radio: wrong tone = fully squelched/idle.
        //PR #2384 fix: pass lastBatch=true when squelch is closing so the resampler
        //zero-pads and flushes its output buffer, preventing BufferOverflowException.
        mNoiseSquelch.setAudioListener(audio -> {
            if(mNoiseSquelch.isSquelched())
            {
                mResampler.resample(audio, true);
            }
            else
            {
                mResampler.resample(audio);

                // Only signal call activity if tone matches (or no tone filter configured)
                if(!mToneFilterEnabled || mToneMatch)
                {
                    notifyCallContinuation();
                }
            }
        });

        //Notify the decoder state of call starts and ends, and manage tail remover + tone reset.
        //When tone filtering is enabled, we defer the call start until the correct tone is confirmed.
        //The channel stays idle until the CTCSS detector confirms the right tone — just like a real radio.
        mNoiseSquelch.setSquelchStateListener(squelchState -> {
            if(squelchState == SquelchState.SQUELCH)
            {
                logSquelchClose();

                //AP-fork (ap-15.9.20): the carrier dropped while a weak confirmation was on hold. Decide now on
                //what the TDMA detector has seen so far, so a short real call is not lost to the hold.
                if(mConfirmHoldActive)
                {
                    if(mTdmaDetector != null && (mTdmaDetector.isInterferenceDetected() ||
                            mTdmaDetector.isSlotCadenceSuspected()))
                    {
                        discardConfirmHold(describeTdmaEvidence() + ", carrier dropped");
                    }
                    else
                    {
                        releaseConfirmHold("carrier dropped");
                    }
                }

                // Reset stuck timer watchdog — carrier dropped, allow fresh calls
                mWatchdogTripped = false;
                mCallStartTimeMs = 0;

                // Squelch closed (end of transmission)
                if(mSquelchTailRemover != null)
                {
                    mSquelchTailRemover.squelchClose();
                }

                // DON'T immediately reset tone match or detectors here.
                // Brief squelch closures (noise flutter) shouldn't force a full
                // re-detection cycle. Instead, we preserve the tone match for a
                // holdover period. The tone match will be cleared either:
                //   (a) when squelch re-opens and holdover has expired, or
                //   (b) when the detector reports tone lost or rejected.

                // Discard any buffered audio from an unconfirmed tone detection
                clearPendingToneAudio();

                // Only send call end if a call was actually active (tone was matched or no filter)
                if(!mToneFilterEnabled || mToneMatch)
                {
                    notifyCallEnd();
                }

                //AP-fork: the episode ended without the target ever being re-confirmed, so closing the
                //gate at the deadline would have been correct - this is the interference case the
                //backstop exists for. Counted here against the FALSE POSITIVE line so a day of logs
                //gives both populations. Measurement only; see the "WOULD CLOSE" block.
                long deadlineExceededAt = mHoldoverDeadlineExceededTime;

                if(deadlineExceededAt > 0)
                {
                    mHoldoverDeadlineExceededTime = 0;
                    mLog.warn("[{}] CTCSS holdover TRUE POSITIVE: gate passed {}ms of unconfirmed audio " +
                                    "past the deadline and the target was never re-confirmed",
                            mChannelLabel, System.currentTimeMillis() - deadlineExceededAt);
                }

                // End of transmission — report what the hum diagnostic measured, if it is running
                if(mHumAnalyzer != null)
                {
                    mHumAnalyzer.reportAndReset();
                }
            }
            else
            {
                // Squelch opened (start of transmission)
                if(mSquelchTailRemover != null)
                {
                    mSquelchTailRemover.squelchOpen();
                }

                if(!mToneFilterEnabled)
                {
                    // No tone filter — start call immediately (original behavior)
                    // Reset audio filter state to clear stale IIR values from previous transmission
                    if(mAudioFilters != null)
                    {
                        mAudioFilters.reset();
                    }
                    notifyCallStart();
                }
                else
                {
                    // Tone filter is active. Check if we have a recent tone match
                    // within the holdover window — if so, preserve it and resume
                    // audio immediately without waiting for re-detection.
                    long elapsed = System.currentTimeMillis() - mLastToneMatchTime;

                    logSquelchOpen(elapsed);

                    if(mToneMatch && elapsed < TONE_HOLDOVER_MS)
                    {
                        // Holdover active — continue as if tone is still confirmed.
                        // The detector keeps running and will reject/lost if tone changes.
                        // Arm the confirmation deadline: this gate is open on trust, and must
                        // earn a re-confirmation before HOLDOVER_CONFIRM_DEADLINE_MS elapses.
                        mHoldoverOpenTime = System.currentTimeMillis();
                        notifyCallStart();
                    }
                    else
                    {
                        // Holdover expired or no previous match — full reset.
                        // Channel stays idle until detector confirms the correct tone.
                        mToneMatch = false;
                        mHoldoverOpenTime = 0;

                        //AP-fork: new episode with no inherited trust, so no inherited bin-contest
                        //evidence either. See mRawTargetWonThisEpisode.
                        mRawTargetWonThisEpisode = false;

                        // Reset audio filter state to clear stale IIR values from previous transmission
                        if(mAudioFilters != null)
                        {
                            mAudioFilters.reset();
                        }

                        if(mCTCSSDetector != null)
                        {
                            mCTCSSDetector.reset();
                        }
                        if(mTdmaDetector != null)
                        {
                            mTdmaDetector.reset();
                        }
                        if(mDCSDetector != null)
                        {
                            mDCSDetector.reset();
                        }
                    }
                }
            }
        });
    }

    /**
     * AP-fork: logs a noise squelch opening on a tone-filtered channel, with the branch the gate is about to take.
     * Measurement only.  Called before the branch runs, so mToneMatch is the value the branch will test.
     *
     * @param sinceLastToneMatchMs elapsed time since the target tone was last confirmed.
     */
    private void logSquelchOpen(long sinceLastToneMatchMs)
    {
        mSquelchOpenTimeMs = System.currentTimeMillis();

        if(mLog.isDebugEnabled())
        {
            boolean holdover = mToneMatch && sinceLastToneMatchMs < TONE_HOLDOVER_MS;
            mLog.debug("[{}] Squelch OPEN - {} (toneMatch={}, last tone confirmation {}, holdover window {}ms, " +
                            "targetWon latch={})",
                    mChannelLabel, holdover ? "HOLDOVER branch, gate opens on trust" :
                            "FULL RESET branch, gate waits for confirmation", mToneMatch,
                    mLastToneMatchTime > 0 ? sinceLastToneMatchMs + "ms ago" : "never", TONE_HOLDOVER_MS,
                    mRawTargetWonThisEpisode);
        }
    }

    /**
     * AP-fork: logs a noise squelch closing on a tone-filtered channel - how long it was open and what state the tone
     * gate was in.  Measurement only.  Called before any state is changed.
     */
    private void logSquelchClose()
    {
        long now = System.currentTimeMillis();
        long openedAt = mSquelchOpenTimeMs;
        mSquelchOpenTimeMs = 0;

        if(mToneFilterEnabled && mLog.isDebugEnabled())
        {
            mLog.debug("[{}] Squelch CLOSE after {} (toneMatch={}, last tone confirmation {}, gate unconfirmed for {})",
                    mChannelLabel, openedAt > 0 ? (now - openedAt) + "ms open" : "unknown time open", mToneMatch,
                    mLastToneMatchTime > 0 ? (now - mLastToneMatchTime) + "ms ago" : "never",
                    mHoldoverOpenTime > 0 ? (now - mHoldoverOpenTime) + "ms" : "0ms");
        }
    }

    /**
     * Extracts CTCSSCode targets from the channel tone filter configuration.
     * @param filters list of configured tone filters
     * @return set of CTCSS codes to accept, or empty set if none configured
     */
    private Set<CTCSSCode> extractCTCSSCodes(List<ChannelToneFilter> filters)
    {
        EnumSet<CTCSSCode> codes = EnumSet.noneOf(CTCSSCode.class);

        if(filters != null)
        {
            for(ChannelToneFilter filter : filters)
            {
                if(filter.getToneType() == ChannelToneFilter.ToneType.CTCSS)
                {
                    CTCSSCode code = filter.getCTCSSCode();
                    if(code != null && code != CTCSSCode.UNKNOWN)
                    {
                        codes.add(code);
                        mLog.info("CTCSS target: {} ({})", code.getDisplayString(), code.name());
                    }
                }
            }
        }

        return codes;
    }

    /**
     * Extracts DCSCode targets from the channel tone filter configuration.
     * @param filters list of configured tone filters
     * @return set of DCS codes to accept, or empty set if none configured
     */
    private Set<DCSCode> extractDCSCodes(List<ChannelToneFilter> filters)
    {
        EnumSet<DCSCode> codes = EnumSet.noneOf(DCSCode.class);

        if(filters != null)
        {
            for(ChannelToneFilter filter : filters)
            {
                if(filter.getToneType() == ChannelToneFilter.ToneType.DCS)
                {
                    DCSCode code = filter.getDCSCode();
                    if(code != null && code != DCSCode.UNKNOWN)
                    {
                        codes.add(code);
                        mLog.info("DCS target: {}", code.toString());
                    }
                }
            }
        }

        return codes;
    }

    /**
     * Sets the decoder state reference for CTCSS/DCS detection integration.
     * @param decoderState the NBFM decoder state to receive tone notifications
     */
    public void setDecoderState(NBFMDecoderState decoderState)
    {
        mDecoderState = decoderState;
        updateChannelLabel();
    }

    /**
     * Returns the audio filter chain for this decoder, or null if not yet initialized.
     * Used by the configuration editor to perform real-time audio analysis.
     */
    public NBFMAudioFilters getAudioFilters()
    {
        return mAudioFilters;
    }

    /**
     * Decoder type.
     * @return type
     */
    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    /**
     * Decode configuration for this decoder.
     * @return configuration
     */
    @Override
    public DecodeConfigNBFM getDecodeConfiguration()
    {
        return (DecodeConfigNBFM)super.getDecodeConfiguration();
    }

    /**
     * Register the noise squelch state listener.  This will normally be a GUI noise squelch state view/controller.
     * @param listener to receive states or pass null to de-register a listener.
     */
    @Override
    public void setNoiseSquelchStateListener(Listener<NoiseSquelchState> listener)
    {
        mNoiseSquelch.setNoiseSquelchStateListener(listener);
    }

    /**
     * AP-fork: records the outcome of an episode in which the holdover confirmation deadline was
     * exceeded. Called whenever the target tone or code is confirmed again.
     *
     * <p>This is the false-positive counter for the measurement-only deadline. If the target confirms
     * after the deadline had already passed, then closing the gate at the deadline would have cut a
     * legitimate call short, and the margin printed here is how much longer the deadline needed to be
     * to avoid that. Episodes that end without ever reaching this are the true positives - interference
     * holding a gate open that should have shut.
     *
     * <p>Reported at WARN so the two populations are countable in a day's logs without DEBUG enabled.
     */
    private void noteHoldoverReconfirmed()
    {
        long exceededAt = mHoldoverDeadlineExceededTime;

        if(exceededAt > 0)
        {
            long overshoot = System.currentTimeMillis() - exceededAt;
            mHoldoverDeadlineExceededTime = 0;
            mLog.warn("[{}] CTCSS holdover FALSE POSITIVE: target re-confirmed {}ms after the deadline " +
                            "would have closed the gate - a deadline of {}ms or more would have kept this call",
                    mChannelLabel, overshoot, HOLDOVER_CONFIRM_DEADLINE_MS + overshoot);
        }
    }

    /**
     * Applies new open and close noise threshold values for the noise squelch.
     * @param open for the open noise variance calculation in range 0.1 - 0.5 where open <= close value.
     * @param close for the close noise variance calculation. in range 0.1 - 0.5 where close >= open.
     */
    @Override
    public void setNoiseThreshold(float open, float close)
    {
        mNoiseSquelch.setNoiseThreshold(open, close);

        //Update the channel configuration and schedule a playlist save.
        getDecodeConfiguration().setSquelchNoiseOpenThreshold(open);
        getDecodeConfiguration().setSquelchNoiseCloseThreshold(close);
    }

    /**
     * Sets the open and close hysteresis thresholds in units of 10 milliseconds.
     * @param open in range 1-10, recommend: 4 where open <= close
     * @param close in range 1-10, recommend: 6 where close >= open.
     */
    @Override
    public void setHysteresisThreshold(int open, int close)
    {
        mNoiseSquelch.setHysteresisThreshold(open, close);
        getDecodeConfiguration().setSquelchHysteresisOpenThreshold(open);
        getDecodeConfiguration().setSquelchHysteresisCloseThreshold(close);
    }

    /**
     * Sets the squelch override state to temporarily bypass/override squelch control and pass all audio.
     * @param override (true) or (false) to turn off squelch override.
     */
    @Override
    public void setSquelchOverride(boolean override)
    {
        mNoiseSquelch.setSquelchOverride(override);
    }

    /**
     * Implements the ISourceEventListener interface
     */
    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventProcessor;
    }

    /**
     * Module interface methods - unused.
     */
    @Override
    public void reset() {}

    @Override
    public void start() {}

    @Override
    public void stop() {}

    /**
     * Broadcasts the demodulated, resampled to 8 kHz audio samples to the registered listener.
     *
     * @param demodulatedSamples to broadcast
     */
    protected void broadcast(float[] demodulatedSamples)
    {
        if(mResampledBufferListener != null)
        {
            mResampledBufferListener.receive(demodulatedSamples);
        }
    }

    /**
     * Processes resampled 8 kHz audio through the CTCSS/DCS tone filter and squelch tail remover
     * before broadcasting to downstream consumers.
     *
     * Audio flow: resampler → this method → detector analysis → tone gate → tail remover → broadcast
     *
     * @param resampledAudio 8 kHz audio from the resampler
     */
    private void processResampledAudio(float[] resampledAudio)
    {
        mLastResampledAudioTimeMs = System.currentTimeMillis();

        // Step 1: Feed audio to the active tone/code detector for analysis
        if(mCTCSSDetector != null)
        {
            mCTCSSDetector.process(resampledAudio);
        }
        if(mDCSDetector != null)
        {
            mDCSDetector.process(resampledAudio);
        }
        if(mTdmaDetector != null)
        {
            mTdmaDetector.process(resampledAudio);
        }

        // Step 1b: Digital-bleed veto. A 2-slot TDMA carrier demodulated as FM sprays broadband
        // noise across the CTCSS bins — including the target tone's bin — which can satisfy the
        // tone detector and hold the gate open for the entire burst. The TDMA slot cadence is
        // unambiguous and speech never produces it, so it overrides any tone match.
        if(mToneFilterEnabled && mTdmaDetector != null && mTdmaDetector.isInterferenceDetected())
        {
            //AP-fork (ap-15.9.20): the latch came while a weak confirmation was on hold - exactly the burst the
            //hold exists for. Nothing was started, so discard quietly rather than ending a call.
            if(mConfirmHoldActive)
            {
                discardConfirmHold(describeTdmaEvidence());
                return;
            }

            if(mToneMatch)
            {
                //AP-fork: report the score that LATCHED the detector, plus the current one, and say the threshold.
                //This previously logged only getLastScore(), which decays while the latch is held - the 2026-09-23
                //Daly log shows closures reporting 0.4 against a threshold of 20.0, which reads as a broken
                //threshold and is only a stale figure.  Both numbers together say whether the interference is still
                //present or the latch is simply running out its RELEASE_WINDOWS.
                mLog.debug("[{}] Closing tone gate — TDMA digital interference (latched at comb score={}, " +
                                "currently {}, threshold {})",
                        mChannelLabel, String.format("%.1f", mTdmaDetector.getTriggerScore()),
                        String.format("%.1f", mTdmaDetector.getLastScore()),
                        String.format("%.1f", TdmaInterferenceDetector.getDetectionThreshold()));
                mToneMatch = false;
                mHoldoverOpenTime = 0;

                if(mAudioFilters != null)
                {
                    mAudioFilters.reset();
                }

                notifyCallEnd();
            }

            clearPendingToneAudio();
            return;
        }

        // Step 1c (AP-fork, ap-15.9.20): a weak CTCSS confirmation is on hold. Keep buffering until the TDMA
        // detector has had CONFIRM_HOLD_SAMPLES of audio to look at, then release - unless it latched (Step 1b) or
        // saw the slot cadence's fundamental, in which case the held audio is a burst and is discarded.
        if(mConfirmHoldActive)
        {
            bufferPendingToneAudio(resampledAudio, MAX_PENDING_TONE_SAMPLES + CONFIRM_HOLD_SAMPLES);
            mConfirmHoldSamples += resampledAudio.length;

            if(mTdmaDetector != null && mTdmaDetector.isSlotCadenceSuspected())
            {
                discardConfirmHold(describeTdmaEvidence());
            }
            else if(mConfirmHoldSamples >= CONFIRM_HOLD_SAMPLES)
            {
                releaseConfirmHold("no slot cadence");
            }

            return;
        }

        // Step 2: Gate audio based on tone/code match
        if(mToneFilterEnabled && !mToneMatch)
        {
            // Tone/code not confirmed yet — buffer audio for retroactive release
            // when the detector confirms the correct tone, instead of discarding it
            bufferPendingToneAudio(resampledAudio);
            return;
        }

        // Holdover backstop: audio is flowing on a gate that was opened on trust rather than on
        // a confirmed tone. If the detector has not re-confirmed the target within
        // HOLDOVER_CONFIRM_DEADLINE_MS, close the gate. Without this a gate opened by holdover
        // stayed open for as long as interference kept the loss counter from expiring.
        if(mToneFilterEnabled && (mCTCSSDetector != null || mDCSDetector != null))
        {
            CTCSSCode confirmed = mCTCSSDetector != null ? mCTCSSDetector.getDetectedCode() : null;
            boolean dcsConfirmed = mDCSDetector != null && mDCSDetector.getDetectedCode() != null;

            if(confirmed != null || dcsConfirmed)
            {
                // Re-confirmed — the gate is standing on its own again
                noteHoldoverReconfirmed();
                mHoldoverOpenTime = 0;

                //AP-fork: a confirmation of one of our own tones is the strongest possible form of
                //"the target won its bin", so latch the evidence here too rather than relying on the
                //unconfirmed branch below having seen it first.
                //
                //dcsConfirmed latches it as well. A DCS-coded channel never produces a CTCSS bin win,
                //so without this a DCS call would arrive at the veto below with no evidence and be cut
                //every time the code dropped for a block. The veto is additionally guarded on
                //mCTCSSDetector != null for the DCS-only case, but a channel carrying both wants the
                //DCS confirmation to count as evidence in its own right.
                if((mCTCSSDetector != null && mCTCSSDetector.isTargetCode(confirmed)) || dcsConfirmed)
                {
                    mRawTargetWonThisEpisode = true;
                }
            }
            else
            {
                // Audio passing via holdover or stale match — not actively confirmed
                long now = System.currentTimeMillis();

                //AP-fork: start the clock on ANY unconfirmed gate, not only one opened by the
                //holdover branch at squelch-open.
                //
                //mHoldoverOpenTime was armed in exactly one place - the squelch-open path - and was
                //zeroed by every genuine tone confirmation, including the one that made the gate
                //legitimate in the first place. So the common case had no clock at all: a real
                //transmission confirms the target, mToneMatch stays true after it ends, and when an
                //adjacent signal later opens the squelch during a CONTINUOUS transmission the
                //squelch-open path never re-runs. The gate then passed audio indefinitely on a stale
                //match while the deadline below sat permanently disarmed.
                //
                //Summit 2026-10-04, Bolton Fire 453.600 against Trinity 453.6125: 99 "gate OPEN via
                //holdover" lines with ages to 1248 ms, and ZERO "deadline exceeded" lines against a
                //600 ms deadline. The backstop could not see the case it was written for.
                if(mHoldoverOpenTime == 0)
                {
                    mHoldoverOpenTime = now;
                }

                long openFor = now - mHoldoverOpenTime;

                //AP-fork: MEASUREMENT ONLY for now - the gate is NOT closed here yet.
                //
                //Arming the clock above makes this deadline live on every tone-filtered NBFM channel
                //in the fleet for the first time, and HOLDOVER_CONFIRM_DEADLINE_MS has never run in
                //production, so its value has never been tested against real traffic. If 600 ms is
                //too tight the failure mode is audio clipped mid-call, which is worse than the
                //interference it suppresses.
                //
                //So this records what it WOULD have done. noteHoldoverReconfirmed() reports the
                //false positives directly: if the target tone is confirmed after this point in the
                //same episode, closing the gate here would have cut a legitimate call short, and the
                //log says so with the margin by which it missed. An episode that ends without
                //re-confirmation is a true positive. One day of both counts sets the deadline.
                if(openFor > HOLDOVER_CONFIRM_DEADLINE_MS && mHoldoverDeadlineExceededTime == 0)
                {
                    mHoldoverDeadlineExceededTime = now;
                    CTCSSCode rawAtDeadline = mCTCSSDetector != null ? mCTCSSDetector.getRawDetectedCode() : null;
                    mLog.warn("[{}] CTCSS holdover WOULD CLOSE gate: {}ms unconfirmed (deadline {}ms) raw={} " +
                                    "- measurement only, gate left open",
                            mChannelLabel, openFor, HOLDOVER_CONFIRM_DEADLINE_MS,
                            rawAtDeadline != null ? rawAtDeadline.getDisplayString() : "none");
                }

                if(mCTCSSDetector != null)
                {
                    CTCSSCode raw = mCTCSSDetector.getRawDetectedCode();
                    int lossCount = mCTCSSDetector.getLossCounter();

                    //AP-fork: latch the evidence. The raw winner is whatever actually took the bin
                    //contest this block, before any preference or confirmation logic - so if it is one
                    //of our tones, our tone is genuinely present in the passband.
                    if(mCTCSSDetector.isTargetCode(raw))
                    {
                        mRawTargetWonThisEpisode = true;
                    }

                    mLog.debug("[{}] CTCSS gate OPEN via holdover: confirmed=null raw={} lossCounter={} " +
                                    "holdoverAge={}ms targetWon={}",
                            mChannelLabel, raw != null ? raw.getDisplayString() : "none", lossCount,
                            now - mLastToneMatchTime, mRawTargetWonThisEpisode);
                }

                //AP-fork: THE VETO. Nothing is confirmed and no target tone has won a bin outright at
                //any point in this episode, so there is no evidence our tone is present at all. Close
                //the gate. This is the adjacent-channel splashover case: Trinity 453.6125 keys up, its
                //77.0 Hz arrives on Bolton Fire 453.600 strongly enough to open the squelch, and the
                //holdover lets it through on trust inherited from nothing.
                //
                //Replaces the 15.9.11 "WOULD CLOSE" measurement, whose 600 ms deadline the Summit
                //2026-10-05 log shows would have cut 14 of 16 real calls. The deadline logging above is
                //kept because the two populations it separates are still worth counting, but the
                //closure decision is now made here, on evidence rather than on elapsed time.
                //Guarded on mCTCSSDetector != null: on a DCS-only channel there are no CTCSS bins to
                //win, so the evidence this veto wants cannot exist and the veto must not run.
                if(mCTCSSDetector != null && !mRawTargetWonThisEpisode)
                {
                    CTCSSCode raw = mCTCSSDetector.getRawDetectedCode();
                    mLog.debug("[{}] Closing tone gate — holdover with no confirmation and no target bin " +
                                    "win this episode (raw={}, unconfirmed for {}ms)",
                            mChannelLabel, raw != null ? raw.getDisplayString() : "none", openFor);

                    mToneMatch = false;
                    mHoldoverOpenTime = 0;
                    mHoldoverDeadlineExceededTime = 0;

                    if(mAudioFilters != null)
                    {
                        mAudioFilters.reset();
                    }

                    notifyCallEnd();
                    clearPendingToneAudio();
                    return;
                }
            }
        }

        // Step 2b: Mains hum measurement. Taken here, after the gate has decided to pass this audio
        // but before any audio filter touches it, so it measures what the demodulator delivered
        // rather than what survives the chain — and does not measure squelched noise, which the
        // early returns above have already discarded.
        //
        // Created on demand rather than at startup so that ticking the box in the Diagnostics panel
        // takes effect on channels that are already running. When it is off the cost here is one
        // boolean check per buffer.
        if(HumAnalyzer.isEnabled())
        {
            if(mHumAnalyzer == null)
            {
                mHumAnalyzer = new HumAnalyzer(mChannelLabel, DEMODULATED_AUDIO_SAMPLE_RATE);
            }

            mHumAnalyzer.receive(resampledAudio);
        }
        else if(mHumAnalyzer != null)
        {
            mHumAnalyzer = null;
        }

        // Step 3: Apply VoxSend audio filter chain (low-pass, de-emphasis, bass boost,
        //         voice enhancement, noise gate) — processes samples in-place
        if(mAudioFilters != null)
        {
            mAudioFilters.process(resampledAudio);
        }

        // Step 4: Pass through squelch tail remover if enabled, otherwise broadcast directly
        if(mSquelchTailRemover != null)
        {
            mSquelchTailRemover.process(resampledAudio);
        }
        else
        {
            broadcast(resampledAudio);
        }
    }

    /**
     * Buffers audio during the CTCSS/DCS tone confirmation window. When the detector
     * confirms the correct tone, flushPendingToneAudio() releases the buffered audio
     * through the normal pipeline so the start of the call is not lost.
     */
    private void bufferPendingToneAudio(float[] audio)
    {
        bufferPendingToneAudio(audio, MAX_PENDING_TONE_SAMPLES);
    }

    /**
     * Buffers audio as above, evicting the oldest chunks beyond the given cap.
     *
     * @param audio to buffer
     * @param maxSamples cap; a confirmation hold raises it so the hold does not evict the call's opening
     */
    private void bufferPendingToneAudio(float[] audio, int maxSamples)
    {
        mPendingToneAudio.add(audio);
        mPendingToneSampleCount += audio.length;

        // Cap buffer to prevent unbounded growth — evict oldest chunks
        while(mPendingToneSampleCount > maxSamples && !mPendingToneAudio.isEmpty())
        {
            float[] removed = mPendingToneAudio.remove(0);
            mPendingToneSampleCount -= removed.length;
        }
    }

    /**
     * Flushes buffered audio through the audio filter and broadcast pipeline after
     * the CTCSS/DCS detector confirms the correct tone. This recovers the ~250ms of
     * audio that would otherwise be lost during tone confirmation.
     */
    private void flushPendingToneAudio()
    {
        if(mPendingToneAudio.isEmpty())
        {
            return;
        }

        int chunks = mPendingToneAudio.size();
        int samples = mPendingToneSampleCount;

        for(float[] audio : mPendingToneAudio)
        {
            if(mAudioFilters != null)
            {
                mAudioFilters.process(audio);
            }

            if(mSquelchTailRemover != null)
            {
                mSquelchTailRemover.process(audio);
            }
            else
            {
                broadcast(audio);
            }
        }

        mPendingToneAudio.clear();
        mPendingToneSampleCount = 0;

        mLog.debug("[{}] Flushed {} buffered audio chunks ({}ms) after tone confirmation",
            mChannelLabel, chunks, samples * 1000 / (int) DEMODULATED_AUDIO_SAMPLE_RATE);
    }

    /**
     * AP-fork (ap-15.9.20): true when the CTCSS target just confirmed weakly enough to be held for a TDMA check.
     * CTCSS only - a DCS confirmation has no SNR to judge, and the measurement covered CTCSS channels alone.
     */
    private boolean isWeakConfirmation()
    {
        return mTdmaDetector != null && mCTCSSDetector != null &&
                mCTCSSDetector.getMaxTargetSnrDb() < WEAK_CONFIRM_SNR_DB;
    }

    /**
     * AP-fork (ap-15.9.20): begins holding a weak confirmation. If the TDMA detector already suspects the slot cadence
     * from the audio buffered before confirmation, the audio is discarded at once.
     */
    private void startConfirmHold()
    {
        mConfirmHoldActive = true;
        mConfirmHoldSamples = 0;

        if(mTdmaDetector.isInterferenceDetected() || mTdmaDetector.isSlotCadenceSuspected())
        {
            discardConfirmHold(describeTdmaEvidence() + ", before confirmation");
            return;
        }

        mLog.debug("[{}] Holding confirmed audio for a TDMA check - target confirmed weakly (max target SNR={} dB, " +
                        "below {} dB), {}ms buffered", mChannelLabel,
                String.format("%.1f", mCTCSSDetector.getMaxTargetSnrDb()), String.format("%.1f", WEAK_CONFIRM_SNR_DB),
                mPendingToneSampleCount * 1000 / (int) DEMODULATED_AUDIO_SAMPLE_RATE);
    }

    /**
     * AP-fork (ap-15.9.20): ends a hold by starting the call and releasing everything buffered.
     *
     * @param reason for the log line
     */
    private void releaseConfirmHold(String reason)
    {
        mConfirmHoldActive = false;
        mLog.debug("[{}] Released held audio after TDMA check - {} (max target SNR={} dB, held {}ms)",
                mChannelLabel, reason, String.format("%.1f", mCTCSSDetector.getMaxTargetSnrDb()),
                mConfirmHoldSamples * 1000 / (int) DEMODULATED_AUDIO_SAMPLE_RATE);
        mConfirmHoldSamples = 0;
        notifyCallStart();
        flushPendingToneAudio();
    }

    /**
     * AP-fork (ap-15.9.20): ends a hold by throwing the buffered audio away. No call was started, so none is ended.
     * The CTCSS detector and the TDMA suspicion are re-armed so that a real call which keeps transmitting is judged
     * afresh on the audio that follows and can still confirm - losing its opening, not the call.
     *
     * @param reason for the log line
     */
    private void discardConfirmHold(String reason)
    {
        mLog.debug("[{}] Discarded {}ms of held audio - {} (max target SNR={} dB)", mChannelLabel,
                mPendingToneSampleCount * 1000 / (int) DEMODULATED_AUDIO_SAMPLE_RATE, reason,
                String.format("%.1f", mCTCSSDetector != null ? mCTCSSDetector.getMaxTargetSnrDb() : 0.0f));

        mConfirmHoldActive = false;
        mConfirmHoldSamples = 0;
        mToneMatch = false;
        clearPendingToneAudio();

        if(mCTCSSDetector != null)
        {
            mCTCSSDetector.reset();
        }

        if(mTdmaDetector != null)
        {
            mTdmaDetector.clearSuspicion();
        }
    }

    /**
     * AP-fork (ap-15.9.20): cancels a hold because the tone detector itself withdrew the confirmation.
     *
     * @param reason for the log line
     * @return true if a hold was active, i.e. no call was started that would need ending
     */
    private boolean abandonConfirmHold(String reason)
    {
        if(!mConfirmHoldActive)
        {
            return false;
        }

        mLog.debug("[{}] Abandoned held audio - {}", mChannelLabel, reason);
        mConfirmHoldActive = false;
        mConfirmHoldSamples = 0;
        return true;
    }

    /**
     * AP-fork (ap-15.9.20): what the TDMA detector saw, for the hold log lines.
     */
    private String describeTdmaEvidence()
    {
        if(mTdmaDetector == null)
        {
            return "no TDMA detector";
        }

        if(mTdmaDetector.isInterferenceDetected())
        {
            return "TDMA latched (comb score=" + String.format("%.1f", mTdmaDetector.getTriggerScore()) + ")";
        }

        return "slot cadence suspected (H1=" + String.format("%.1f", mTdmaDetector.getSuspectFundamental()) +
                " at " + String.format("%.2f", mTdmaDetector.getSuspectRate()) + " Hz)";
    }

    /**
     * Clears the pending tone audio buffer without broadcasting. Called when squelch
     * closes, or when the tone detector rejects/loses the tone.
     */
    private void clearPendingToneAudio()
    {
        mPendingToneAudio.clear();
        mPendingToneSampleCount = 0;
    }

    /**
     * Implements the IRealBufferProvider interface to register a listener for demodulated audio samples.
     *
     * @param listener to receive demodulated, resampled audio sample buffers.
     */
    @Override
    public void setBufferListener(Listener<float[]> listener)
    {
        mResampledBufferListener = listener;
    }

    /**
     * Implements the IRealBufferProvider interface to deregister a listener from receiving demodulated audio samples.
     */
    @Override
    public void removeBufferListener()
    {
        mResampledBufferListener = null;
    }

    /**
     * Implements the IComplexSampleListener interface to receive a stream of complex sample buffers.
     */
    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    /**
     * Implements the Listener<ComplexSample> interface to receive a stream of complex I/Q sample buffers
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        if(mIDecimationFilter == null || mQDecimationFilter == null)
        {
            throw new IllegalStateException("NBFM demodulator module must receive a sample rate change source event " +
                    "before it can process complex sample buffers");
        }

        // Impulse noise blanking — zero IQ spikes before decimation to prevent FIR smearing
        if(mNoiseBlankerEnabled)
        {
            mNoiseBlanker.process(samples.i(), samples.q());
        }

        float[] decimatedI = mIDecimationFilter.decimateReal(samples.i());
        float[] decimatedQ = mQDecimationFilter.decimateReal(samples.q());

        float[] filteredI = mIBasebandFilter.filter(decimatedI);
        float[] filteredQ = mQBasebandFilter.filter(decimatedQ);

        float[] demodulated = mDemodulator.demodulate(filteredI, filteredQ);

        mNoiseSquelch.process(demodulated);

        //Once we process the sample buffer, if the ending state is squelch closed, update the decoder state that we
        // are idle.
        if(mNoiseSquelch.isSquelched())
        {
            notifyIdle();
        }
        else
        {
            // Stuck timer watchdog — force-end calls that exceed max duration
            if(mMaxCallDurationEnabled && !mWatchdogTripped && mCallStartTimeMs > 0)
            {
                long now = System.currentTimeMillis();
                long elapsed = now - mCallStartTimeMs;
                if(elapsed > mMaxCallDurationMs)
                {
                    //AP-fork: report how long ago audio last reached the resampler alongside the call age.  A genuinely
                    //stuck carrier delivers audio continuously, so idleMs stays near zero; a large idleMs means the
                    //call timer is stale - the squelch closed without this decoder being told, so mCallStartTimeMs was
                    //never cleared and the next opening trips the watchdog at once.  The swallowed SquelchState.SQUELCH
                    //broadcast that caused it is fixed in NoiseSquelch; this line is what distinguishes the two cases
                    //if it ever happens again.
                    long idleMs = mLastResampledAudioTimeMs > 0 ? now - mLastResampledAudioTimeMs : -1;
                    mLog.warn("[{}] Stuck timer watchdog: call exceeded {}s max duration, forcing end " +
                        "(call age {}s, audio last seen {}s ago{})",
                        mChannelLabel, mMaxCallDurationMs / 1000, elapsed / 1000,
                        idleMs < 0 ? "never" : String.valueOf(idleMs / 1000),
                        idleMs > 5000 ? " - STALE CALL TIMER, not a stuck carrier" : "");
                    mWatchdogTripped = true;
                    broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.CALL, 0));
                }
            }
        }
    }

    /**
     * Broadcasts a call start state event.
     * Suppressed when the stuck timer watchdog has tripped (carrier still open but call force-ended).
     */
    private void notifyCallStart()
    {
        if(mWatchdogTripped)
        {
            return;
        }
        mCallStartTimeMs = System.currentTimeMillis();
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.START, State.CALL, 0));
    }

    /**
     * Broadcasts a call continuation state event.
     * Suppressed when the stuck timer watchdog has tripped.
     */
    private void notifyCallContinuation()
    {
        if(mWatchdogTripped)
        {
            return;
        }
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.CALL, 0));
    }

    /**
     * Broadcasts a call end state event
     */
    private void notifyCallEnd()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.CALL, 0));
    }

    /**
     * Broadcasts an idle notification
     */
    private void notifyIdle()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.IDLE, 0));
    }

    /**
     * Broadcasts the decoder state event to an optional registered listener
     */
    private void broadcast(DecoderStateEvent event)
    {
        if(mDecoderStateEventListener != null)
        {
            mDecoderStateEventListener.receive(event);
        }
    }

    /**
     * Sets the decoder state listener
     */
    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mDecoderStateEventListener = listener;
    }

    /**
     * Removes the decoder state event listener
     */
    @Override
    public void removeDecoderStateListener()
    {
        mDecoderStateEventListener = null;
    }

    /**
     * Updates the decoder to process complex sample buffers at the specified sample rate.
     * @param sampleRate of the incoming complex sample buffer stream.
     */
    private void setSampleRate(double sampleRate)
    {
        int decimationRate = 0;
        double decimatedSampleRate = sampleRate;

        if(sampleRate / 2 >= (mChannelBandwidth * 2))
        {
            decimationRate = 2;

            while(sampleRate / decimationRate / 2 >= (mChannelBandwidth * 2))
            {
                decimationRate *= 2;
            }
        }

        if(decimationRate > 0)
        {
            decimatedSampleRate /= decimationRate;
        }

        mIDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimationRate);
        mQDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimationRate);

        if((decimatedSampleRate < (2.0 * mChannelBandwidth)))
        {
            throw new IllegalStateException(getDecoderType().name() + " demodulator with channel bandwidth [" + mChannelBandwidth + "] requires a channel sample rate of [" + (2.0 * mChannelBandwidth + "] - sample rate of [" + decimatedSampleRate + "] is not supported"));
        }

        mNoiseSquelch.setSampleRate(decimatedSampleRate);

        int passBandStop = (int) (mChannelBandwidth * .8);
        int stopBandStart = (int) mChannelBandwidth;

        float[] coefficients = null;

        FIRFilterSpecification specification = FIRFilterSpecification.lowPassBuilder().sampleRate(decimatedSampleRate * 2).gridDensity(16).oddLength(true).passBandCutoff(passBandStop).passBandAmplitude(1.0).passBandRipple(0.01).stopBandStart(stopBandStart).stopBandAmplitude(0.0).stopBandRipple(0.005) //Approximately 90 dB attenuation
                .build();

        try
        {
            coefficients = FilterFactory.getTaps(specification);
        }
        catch(FilterDesignException fde)
        {
            mLog.error("Couldn't design demodulator remez filter for sample rate [" + sampleRate + "] pass frequency [" + passBandStop + "] and stop frequency [" + stopBandStart + "] - will proceed using sinc (low-pass) filter");
        }

        if(coefficients == null)
        {
            mLog.info("Unable to use remez filter designer for sample rate [" + decimatedSampleRate + "] pass band stop [" + passBandStop + "] and stop band start [" + stopBandStart + "] - will proceed using simple low pass filter design");
            coefficients = FilterFactory.getLowPass(decimatedSampleRate, passBandStop, stopBandStart, 60, WindowType.HAMMING, true);
        }

        mIBasebandFilter = FilterFactory.getRealFilter(coefficients);
        mQBasebandFilter = FilterFactory.getRealFilter(coefficients);

        // Create resampler — output goes through our processing chain instead of directly to broadcast
        mResampler = new RealResampler(decimatedSampleRate, DEMODULATED_AUDIO_SAMPLE_RATE, 4192, 512);
        mResampler.setListener(NBFMDecoder.this::processResampledAudio);

        // Digital-bleed veto — protects the tone gate on channels sharing spectrum with DMR or
        // P25 Phase 2 systems. Runs whenever tone filtering is active, for CTCSS and DCS alike.
        if(mToneFilterEnabled)
        {
            mTdmaDetector = new TdmaInterferenceDetector((float) DEMODULATED_AUDIO_SAMPLE_RATE);
            mTdmaDetector.setChannelLabel(mChannelLabel);
        }

        // Initialize CTCSS detector at 8 kHz (resampled audio rate) if tone filtering is enabled
        if(mToneFilterEnabled && mTargetCTCSSCodes != null && !mTargetCTCSSCodes.isEmpty())
        {
            mCTCSSDetector = new CTCSSDetector(mTargetCTCSSCodes, (float) DEMODULATED_AUDIO_SAMPLE_RATE);
            mCTCSSDetector.setListener(new CTCSSDetector.CTCSSDetectorListener()
            {
                @Override
                public void ctcssDetected(CTCSSCode code)
                {
                    // Matching tone confirmed — wake up the channel like a real radio unsquelching
                    boolean wasBlocked = !mToneMatch;
                    mToneMatch = true;
                    mLastToneMatchTime = System.currentTimeMillis();
                    // Confirmed detection — the gate no longer rests on holdover trust
                    noteHoldoverReconfirmed();
                    mHoldoverOpenTime = 0;

                    // If we were previously blocked, fire a call start and flush
                    // the audio that was buffered during tone confirmation
                    if(wasBlocked)
                    {
                        //AP-fork (ap-15.9.20): a weak confirmation waits one TDMA window first.
                        if(isWeakConfirmation())
                        {
                            startConfirmHold();
                        }
                        else
                        {
                            notifyCallStart();
                            flushPendingToneAudio();
                        }
                    }

                    if(mDecoderState != null)
                    {
                        mDecoderState.setDetectedCTCSS(code);
                    }
                }

                @Override
                public void ctcssRejected(CTCSSCode code)
                {
                    // Wrong tone confirmed — fully squelch the channel like a real radio
                    //AP-fork (ap-15.9.20): a held confirmation never started a call, so there is none to end.
                    boolean wasActive = mToneMatch && !abandonConfirmHold("wrong tone confirmed");
                    mToneMatch = false;
                    clearPendingToneAudio();

                    // If we had an active call, end it and go idle
                    if(wasActive)
                    {
                        notifyCallEnd();
                    }
                    notifyIdle();

                    if(mDecoderState != null)
                    {
                        mDecoderState.setRejectedCTCSS(code);
                    }
                }

                @Override
                public void ctcssLost()
                {
                    // Tone lost — squelch the channel until tone re-confirmed
                    //AP-fork (ap-15.9.20): a held confirmation never started a call, so there is none to end.
                    boolean wasActive = mToneMatch && !abandonConfirmHold("tone lost");
                    mToneMatch = false;
                    clearPendingToneAudio();

                    if(wasActive)
                    {
                        notifyCallEnd();
                    }
                    notifyIdle();

                    if(mDecoderState != null)
                    {
                        mDecoderState.setToneLost();
                    }
                }
            });

            updateChannelLabel();
            mLog.info("[{}] CTCSSDetector initialized at {} Hz sample rate", mChannelLabel, DEMODULATED_AUDIO_SAMPLE_RATE);
        }

        // Initialize DCS detector at 8 kHz if DCS filtering is enabled
        if(mToneFilterEnabled && mToneFilterType == ChannelToneFilter.ToneType.DCS
                && mTargetDCSCodes != null && !mTargetDCSCodes.isEmpty())
        {
            mDCSDetector = new DCSDetector(mTargetDCSCodes);
            mDCSDetector.setListener(new DCSDetector.DCSDetectorListener()
            {
                @Override
                public void dcsDetected(DCSCode code)
                {
                    // Matching code confirmed — wake up the channel
                    boolean wasBlocked = !mToneMatch;
                    mToneMatch = true;
                    mLastToneMatchTime = System.currentTimeMillis();
                    // Confirmed detection — the gate no longer rests on holdover trust
                    noteHoldoverReconfirmed();
                    mHoldoverOpenTime = 0;

                    if(wasBlocked)
                    {
                        notifyCallStart();
                        flushPendingToneAudio();
                    }

                    if(mDecoderState != null)
                    {
                        mDecoderState.setDetectedDCS(code);
                    }
                }

                @Override
                public void dcsRejected(DCSCode code)
                {
                    // Wrong code confirmed — fully squelch the channel
                    boolean wasActive = mToneMatch;
                    mToneMatch = false;
                    clearPendingToneAudio();

                    if(wasActive)
                    {
                        notifyCallEnd();
                    }
                    notifyIdle();

                    if(mDecoderState != null)
                    {
                        mDecoderState.setRejectedDCS(code);
                    }
                }

                @Override
                public void dcsLost()
                {
                    // Code lost — squelch until re-confirmed
                    boolean wasActive = mToneMatch;
                    mToneMatch = false;
                    clearPendingToneAudio();

                    if(wasActive)
                    {
                        notifyCallEnd();
                    }
                    notifyIdle();

                    if(mDecoderState != null)
                    {
                        mDecoderState.setToneLost();
                    }
                }
            });

            updateChannelLabel();
            mLog.info("[{}] DCSDetector initialized for channel-level DCS filtering", mChannelLabel);
        }

        // Initialize squelch tail remover if enabled
        if(mSquelchTailRemovalEnabled)
        {
            mSquelchTailRemover = new SquelchTailRemover(mSquelchTailRemovalMs, mSquelchHeadRemovalMs);
            mSquelchTailRemover.setOutputListener(NBFMDecoder.this::broadcast);
            mLog.info("SquelchTailRemover initialized: tail={}ms, head={}ms", mSquelchTailRemovalMs, mSquelchHeadRemovalMs);
        }

        // Initialize VoxSend audio filter chain at the resampled audio rate (8 kHz)
        initializeAudioFilters(DEMODULATED_AUDIO_SAMPLE_RATE);

        //The 200/300 Hz high-pass that removes hum and rumble lives in AudioModule and is driven by
        //this per-channel setting, which is easy to leave unticked without noticing.  Report it at
        //startup so a channel with the filter disabled is visible in the log rather than only
        //audible.
        if(mNBFMConfig != null)
        {
            if(mNBFMConfig.isAudioFilter())
            {
                mLog.info("[{}] audio high-pass filter ENABLED (200/300 Hz) - hum and rumble below 300 Hz are " +
                        "attenuated", mChannelLabel);
            }
            else
            {
                mLog.warn("[{}] audio high-pass filter DISABLED - nothing attenuates mains hum or rumble below " +
                        "300 Hz on this channel. Enable 'Audio Filter' in the channel's NBFM configuration if hum " +
                        "is audible.", mChannelLabel);
            }
        }
    }

    /**
     * Initializes the VoxSend audio filter chain from the channel configuration.
     * Applies settings for low-pass, de-emphasis, bass boost, voice enhancement, and noise gate.
     *
     * @param sampleRate the audio sample rate (typically 8000 Hz after resampling)
     */
    private void initializeAudioFilters(double sampleRate)
    {
        mAudioFilters = new NBFMAudioFilters(sampleRate);

        // Low-pass filter
        mAudioFilters.setLowPassEnabled(mNBFMConfig.isLowPassEnabled());
        mAudioFilters.setLowPassCutoff(mNBFMConfig.getLowPassCutoff());

        // FM de-emphasis
        mAudioFilters.setDeemphasisEnabled(mNBFMConfig.isDeemphasisEnabled());
        mAudioFilters.setDeemphasisTimeConstant(mNBFMConfig.getDeemphasisTimeConstant());

        // Bass boost
        mAudioFilters.setBassBoostEnabled(mNBFMConfig.isBassBoostEnabled());
        mAudioFilters.setBassBoost(mNBFMConfig.getBassBoostDb());

        // Voice enhancement (stored as AGC target level, mapped from -30...-6 dB to 0...1.0)
        mAudioFilters.setVoiceEnhanceEnabled(mNBFMConfig.isAgcEnabled());
        float voiceEnhanceAmount = mapAgcTargetToVoiceEnhancement(mNBFMConfig.getAgcTargetLevel());
        mAudioFilters.setVoiceEnhancement(voiceEnhanceAmount);

        // Input gain (stored as AGC max gain in dB, map to linear)
        float inputGainDb = mNBFMConfig.getAgcMaxGain();
        float inputGainLinear = (float)Math.pow(10.0, inputGainDb / 40.0); // half the dB for reasonable mapping
        mAudioFilters.setInputGain(inputGainLinear);

        // Noise gate
        mAudioFilters.setNoiseGateEnabled(mNBFMConfig.isNoiseGateEnabled());
        mAudioFilters.setSquelchThreshold(mNBFMConfig.getNoiseGateThreshold());
        mAudioFilters.setSquelchReduction(mNBFMConfig.getNoiseGateReduction());
        mAudioFilters.setHoldTime(mNBFMConfig.getNoiseGateHoldTime());

        // Hiss reduction (high-shelf cut above corner frequency)
        mAudioFilters.setHissReductionCornerHz(mNBFMConfig.getHissReductionCornerHz());
        mAudioFilters.setHissReductionDb(mNBFMConfig.getHissReductionDb());
        mAudioFilters.setHissReductionEnabled(mNBFMConfig.isHissReductionEnabled());

        // Sub-audible tone notch. Removes the CTCSS/DCS squelch tone from the audio, which is
        // otherwise audible as a low hum - most noticeably during pauses in speech, because the tone
        // level is constant while every other band drops 20-30 dB when the talking stops.
        //
        // Derived from the channel's own configured tone, so there is nothing to detect and nothing
        // to guess, and it engages only on channels that actually filter on a tone. Safe for tone
        // squelch: the CTCSS and DCS detectors are fed ahead of this filter chain.
        configureToneNotch(sampleRate);

        mLog.info("VoxSend audio filters initialized: lowPass={} ({}Hz), deemphasis={} ({}μs), " +
                "hissReduction={} ({}dB@{}Hz), bassBoost={} ({}dB), voiceEnhance={}, " +
                "noiseGate={} (threshold={})",
                mNBFMConfig.isLowPassEnabled(), mNBFMConfig.getLowPassCutoff(),
                mNBFMConfig.isDeemphasisEnabled(), mNBFMConfig.getDeemphasisTimeConstant(),
                mNBFMConfig.isHissReductionEnabled(), mNBFMConfig.getHissReductionDb(),
                mNBFMConfig.getHissReductionCornerHz(),
                mNBFMConfig.isBassBoostEnabled(), mNBFMConfig.getBassBoostDb(),
                mNBFMConfig.isAgcEnabled(),
                mNBFMConfig.isNoiseGateEnabled(), mNBFMConfig.getNoiseGateThreshold());
    }

    /**
     * Configures the audio-path notch from the channel's configured CTCSS or DCS tone.
     *
     * CTCSS tones are notched individually - one cascaded section per configured tone - because a
     * CTCSS tone is a steady sinusoid and a notch removes it cleanly.  DCS is a 134.4 bit/second
     * NRZ codeword whose energy is spread, so it gets one wide notch at the symbol rate and the log
     * says plainly that the reduction is partial.
     *
     * Does nothing on a channel with no tone filtering configured.
     *
     * @param sampleRate of the audio stream, 8 kHz
     */
    private void configureToneNotch(double sampleRate)
    {
        if(mNBFMConfig == null || mNBFMConfig.getToneFilters() == null)
        {
            return;
        }

        List<Double> ctcssFrequencies = new ArrayList<>();
        boolean hasDcs = false;

        for(ChannelToneFilter filter : mNBFMConfig.getToneFilters())
        {
            if(filter == null)
            {
                continue;
            }

            CTCSSCode ctcss = filter.getCTCSSCode();

            if(ctcss != null)
            {
                ctcssFrequencies.add((double)ctcss.getFrequency());
            }
            else if(filter.getDCSCode() != null)
            {
                hasDcs = true;
            }
        }

        if(!ctcssFrequencies.isEmpty())
        {
            double[] frequencies = new double[ctcssFrequencies.size()];

            for(int x = 0; x < frequencies.length; x++)
            {
                frequencies[x] = ctcssFrequencies.get(x);
            }

            mAudioFilters.setToneNotch(frequencies, sampleRate, NBFMAudioFilters.DEFAULT_TONE_NOTCH_Q);

            mLog.info("[{}] CTCSS tone notch ENABLED at {} Hz (Q={}) - the squelch tone is removed from " +
                    "the audio so it is not audible as a hum between transmissions", mChannelLabel,
                    Arrays.toString(frequencies), NBFMAudioFilters.DEFAULT_TONE_NOTCH_Q);
        }
        else if(hasDcs)
        {
            mAudioFilters.setDcsRumbleFilter(sampleRate);

            mLog.info("[{}] DCS rumble filter ENABLED - 4th order high-pass at {} Hz.  DCS sends a " +
                    "23 bit codeword at {} bit/second, so the word repeats at {} Hz and its energy is a " +
                    "comb of harmonics spread from a few Hz up toward the bit rate.  A high-pass removes " +
                    "the whole comb; the {} Hz notch it replaces sat on the sinc null where there was " +
                    "nothing to remove", mChannelLabel, NBFMAudioFilters.DCS_HIGH_PASS_HZ,
                    NBFMAudioFilters.DCS_SYMBOL_RATE_HZ,
                    String.format("%.3f", NBFMAudioFilters.DCS_SYMBOL_RATE_HZ / 23.0),
                    NBFMAudioFilters.DCS_SYMBOL_RATE_HZ);
        }
    }

    /**
     * Maps the AGC target level (stored as -30 to -6 dB) to voice enhancement amount (0.0 to 1.0).
     * The AGC target level field is repurposed to store voice enhancement strength.
     */
    private float mapAgcTargetToVoiceEnhancement(float agcTargetLevel)
    {
        // agcTargetLevel range is -30 to -6 dB, map to 0.0 to 1.0
        // -30 dB = 0.0 (no enhancement), -6 dB = 1.0 (max enhancement)
        float normalized = (agcTargetLevel - (-30.0f)) / ((-6.0f) - (-30.0f));
        return Math.max(0.0f, Math.min(1.0f, normalized));
    }

    /**
     * Monitors sample rate change source event(s) to set up the filters, decimation, and demodulator.
     */
    public class SourceEventProcessor implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                setSampleRate(sourceEvent.getValue().doubleValue());
            }
            else if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE)
            {
                mChannelFrequencyHz = sourceEvent.getValue().longValue();
                updateChannelLabel();
            }
        }
    }

    /**
     * Builds and propagates the channel label to detectors.
     * Called when either the decoder state (channel name) or frequency becomes available.
     * Format: "ChannelName [freq MHz]" e.g. "MetroFire - Red [483.3125]"
     */
    private void updateChannelLabel()
    {
        String channelName = (mDecoderState != null) ? mDecoderState.getChannelName() : null;

        StringBuilder sb = new StringBuilder();
        if(channelName != null && !channelName.isEmpty())
        {
            sb.append(channelName);
        }
        if(mChannelFrequencyHz > 0)
        {
            double freqMHz = mChannelFrequencyHz / 1_000_000.0;
            if(sb.length() > 0)
            {
                sb.append(" ");
            }
            sb.append(String.format("[%.4f]", freqMHz));
        }

        mChannelLabel = sb.toString();

        if(mCTCSSDetector != null)
        {
            mCTCSSDetector.setChannelLabel(mChannelLabel);
        }
        if(mDCSDetector != null)
        {
            mDCSDetector.setChannelLabel(mChannelLabel);
        }
        if(mTdmaDetector != null)
        {
            mTdmaDetector.setChannelLabel(mChannelLabel);
        }
        if(mHumAnalyzer != null)
        {
            mHumAnalyzer.setChannelLabel(mChannelLabel);
        }
    }
}