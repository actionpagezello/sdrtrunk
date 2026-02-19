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

import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
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
import io.github.dsheirer.sample.real.IRealBufferProvider;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NBFM decoder with integrated noise squelch, channel-level tone filtering, FM de-emphasis,
 * and squelch tail removal.
 *
 * Audio chain:
 *   ComplexSamples → Decimate → Baseband Filter → FM Demod → NoiseSquelch
 *   → [De-emphasis] → [ToneGate] → [SquelchTailRemover] → Resample(8kHz) → [PostProcess] → Output
 *
 * De-emphasis is applied after squelch (not before) so that the squelch noise detector
 * sees the unfiltered high-frequency noise content and can close correctly.
 *
 * When tone filtering is enabled, audio only passes when noise squelch is open AND a
 * matching CTCSS/DCS tone is detected.
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

    // FM de-emphasis
    private float mDeemphasisAlpha = 0;
    private float mPreviousDeemphasis = 0;
    private boolean mDeemphasisEnabled = false;

    // Squelch tail remover
    private SquelchTailRemover mSquelchTailRemover;
    private boolean mSquelchTailRemovalEnabled = false;

    // Tone gating
    private boolean mToneFilterEnabled = false;
    private Set<CTCSSCode> mAllowedCTCSSCodes = EnumSet.noneOf(CTCSSCode.class);
    private Set<DCSCode> mAllowedDCSCodes = new HashSet<>();
    private volatile CTCSSCode mDetectedCTCSS = null;
    private volatile DCSCode mDetectedDCS = null;
    private volatile boolean mToneMatched = false;
    private int mSquelchClosedSamples = 0;
    private int mSquelchHoldoverSamples = 0; // Set in setSampleRate()
    private CTCSSDetector mCTCSSDetector = null;
    private DCSDetector mDCSDetector = null;

    /**
     * Constructs an instance
     *
     * @param config to setup the NBFM decoder and noise squelch control.
     */
    public NBFMDecoder(DecodeConfigNBFM config)
    {
        super(config);

        mChannelBandwidth = config.getBandwidth().getValue();
        mNoiseSquelch = new NoiseSquelch(config.getSquelchNoiseOpenThreshold(), config.getSquelchNoiseCloseThreshold(),
                config.getSquelchHysteresisOpenThreshold(), config.getSquelchHysteresisCloseThreshold());

        // Configure de-emphasis
        configureDeemphasis(config.getDeemphasis());

        // Configure squelch tail removal
        if(config.isSquelchTailRemovalEnabled())
        {
            mSquelchTailRemovalEnabled = true;
            mSquelchTailRemover = new SquelchTailRemover(
                    config.getSquelchTailRemovalMs(),
                    config.getSquelchHeadRemovalMs()
            );
        }

        // Configure tone filtering
        configureToneFilters(config);

        // Audio pipeline (when squelch is open): [De-emphasis] → [ToneGate] → [TailRemover] → Resampler → [PostProcess] → Output
        mNoiseSquelch.setAudioListener(audio -> {
            if(mToneFilterEnabled && !mToneMatched)
            {
                // Tone filtering enabled but no match — block audio
                return;
            }

            // Apply de-emphasis AFTER squelch (de-emphasis before squelch would
            // attenuate high-frequency noise and prevent squelch from closing)
            if(mDeemphasisEnabled)
            {
                audio = applyDeemphasis(audio);
            }

            if(mSquelchTailRemovalEnabled && mSquelchTailRemover != null)
            {
                mSquelchTailRemover.process(audio);
            }
            else
            {
                mResampler.resample(audio);
            }

            notifyCallContinuation();
        });

        // Squelch state changes → notify decoder state + tail remover
        mNoiseSquelch.setSquelchStateListener(squelchState -> {
            if(squelchState == SquelchState.SQUELCH)
            {
                if(mSquelchTailRemovalEnabled && mSquelchTailRemover != null)
                {
                    mSquelchTailRemover.squelchClose();
                }
                // Only send END if a call was actually started. With tone filtering, a
                // squelch open that never matched a tone never fires notifyCallStart(),
                // so sending END here without a prior START would confuse the state machine.
                if(!mToneFilterEnabled || mToneMatched)
                {
                    notifyCallEnd();
                }
            }
            else
            {
                if(mSquelchTailRemovalEnabled && mSquelchTailRemover != null)
                {
                    mSquelchTailRemover.squelchOpen();
                }
                // Reset filter states on squelch open to prevent artifacts carrying over
                // from background noise into the new transmission.
                mPreviousDeemphasis = 0f;
                // Reset noise gate gain so new call doesn't start with attenuated audio
                // (gate may have been partially closed on noise just before squelch opened).
                mNoiseGateCurrentGain = 1.0f;
                mNoiseGateHoldCounter = 0;
                // Reset squelch closed sample counter — transmission is starting
                mSquelchClosedSamples = 0;
                // When tone filtering is enabled, delay call start until tone is matched.
                // Two paths: (a) tone already locked before squelch opened (mToneMatched=true) —
                // fire start now since ctcssDetected/dcsDetected already saw isSquelched()=true
                // and deferred; (b) tone not yet matched — defer start to ctcssDetected/dcsDetected.
                if(!mToneFilterEnabled || mToneMatched)
                {
                    notifyCallStart();
                }
            }
        });
    }

    /**
     * Sets the decoder state reference so the decoder can push detected tone updates.
     * @param decoderState the NBFM decoder state to receive tone notifications
     */
    public void setDecoderState(NBFMDecoderState decoderState)
    {
        mDecoderState = decoderState;
    }

    /**
     * Configures FM de-emphasis filter parameters based on the selected mode
     */
    private void configureDeemphasis(DecodeConfigNBFM.DeemphasisMode mode)
    {
        if(mode != null && mode != DecodeConfigNBFM.DeemphasisMode.NONE && mode.getMicroseconds() > 0)
        {
            mDeemphasisEnabled = true;
            // Alpha will be recalculated when sample rate is known
            // For now, store the time constant
            mDeemphasisAlpha = 0; // Will be set in setSampleRate()
        }
        else
        {
            mDeemphasisEnabled = false;
        }
    }

    /**
     * Applies single-pole IIR de-emphasis filter to demodulated audio.
     * This restores flat frequency response from pre-emphasized FM transmission.
     */
    private float[] applyDeemphasis(float[] samples)
    {
        if(!mDeemphasisEnabled || mDeemphasisAlpha <= 0)
        {
            return samples;
        }

        // Process in-place — avoids a new float[] heap allocation on every buffer (~43/sec per channel).
        // IIR de-emphasis: y[n] = alpha * x[n] + (1 - alpha) * y[n-1]
        final float alpha = mDeemphasisAlpha;
        final float oneMinusAlpha = 1.0f - alpha;
        float prev = mPreviousDeemphasis;

        for(int i = 0; i < samples.length; i++)
        {
            prev = alpha * samples[i] + oneMinusAlpha * prev;
            samples[i] = prev;
        }

        mPreviousDeemphasis = prev;
        return samples;
    }

    /**
     * Configures the set of allowed tones from the channel decode configuration
     */
    private void configureToneFilters(DecodeConfigNBFM config)
    {
        mToneFilterEnabled = config.isToneFilterEnabled();

        if(mToneFilterEnabled)
        {
            List<ChannelToneFilter> filters = config.getToneFilters();
            if(filters == null)
            {
                // Defensive: Jackson XML deserialization may bypass the setter for missing
                // elements, returning null instead of the initialized empty ArrayList.
                mLog.warn("NBFM tone filter list is null — disabling tone filter");
                mToneFilterEnabled = false;
                return;
            }
            for(ChannelToneFilter filter : filters)
            {
                if(!filter.isValid())
                {
                    continue;
                }

                switch(filter.getToneType())
                {
                    case CTCSS:
                        CTCSSCode ctcss = filter.getCTCSSCode();
                        if(ctcss != null && ctcss != CTCSSCode.UNKNOWN)
                        {
                            mAllowedCTCSSCodes.add(ctcss);
                        }
                        break;
                    case DCS:
                        DCSCode dcs = filter.getDCSCode();
                        if(dcs != null)
                        {
                            mAllowedDCSCodes.add(dcs);
                        }
                        break;
                    case NAC:
                        // NAC filters are for P25, not NBFM — ignore here
                        break;
                }
            }

            // If we configured tone filtering but have no valid tones, disable it
            if(mAllowedCTCSSCodes.isEmpty() && mAllowedDCSCodes.isEmpty())
            {
                mLog.warn("Tone filtering enabled but no valid CTCSS/DCS codes configured — disabling tone filter");
                mToneFilterEnabled = false;
            }
            else
            {
                mLog.info("NBFM tone filtering enabled: {} CTCSS codes, {} DCS codes",
                        mAllowedCTCSSCodes.size(), mAllowedDCSCodes.size());

                // Create CTCSS detector if we have CTCSS codes to detect
                // Note: detector is initialized with 8000 Hz sample rate; it will be
                // recreated in setSampleRate() if the actual rate differs
                if(!mAllowedCTCSSCodes.isEmpty())
                {
                    createCTCSSDetector(8000.0f);
                }

                // Create DCS detector if we have DCS codes to detect
                if(!mAllowedDCSCodes.isEmpty())
                {
                    createDCSDetector(8000.0f);
                }
            }
        }
    }

    /**
     * Creates the CTCSS Goertzel detector at the specified sample rate.
     * @param sampleRate of the demodulated audio
     */
    private void createCTCSSDetector(float sampleRate)
    {
        mCTCSSDetector = new CTCSSDetector(mAllowedCTCSSCodes, sampleRate);
        mCTCSSDetector.setListener(new CTCSSDetector.CTCSSDetectorListener()
        {
            @Override
            public void ctcssDetected(CTCSSCode code)
            {
                NBFMDecoder.this.ctcssDetected(code);
            }

            @Override
            public void ctcssRejected(CTCSSCode code)
            {
                if(mDecoderState != null && code != null)
                {
                    mDecoderState.setRejectedCTCSS(code);
                }
            }

            @Override
            public void ctcssLost()
            {
                NBFMDecoder.this.toneLost();
            }
        });
    }

    /**
     * Creates the DCS detector at the specified sample rate.
     * @param sampleRate of the demodulated audio
     */
    private void createDCSDetector(float sampleRate)
    {
        mDCSDetector = new DCSDetector(mAllowedDCSCodes, sampleRate);
        mDCSDetector.setListener(new DCSDetector.DCSDetectorListener()
        {
            @Override
            public void dcsDetected(DCSCode code)
            {
                NBFMDecoder.this.dcsDetected(code);
            }

            @Override
            public void dcsLost()
            {
                NBFMDecoder.this.toneLost();
            }
        });
    }

    /**
     * Called by CTCSS decoder when a tone is detected. If tone filtering is enabled,
     * this updates the tone match state.
     * @param code the detected CTCSS tone code
     */
    public void ctcssDetected(CTCSSCode code)
    {
        mDetectedCTCSS = code;
        if(mToneFilterEnabled && code != null && mAllowedCTCSSCodes.contains(code))
        {
            if(!mToneMatched)
            {
                mToneMatched = true;
                // Only fire call start if squelch is actually open. The tone detector runs
                // unconditionally (even during squelch-closed periods) so it can lock before
                // the carrier opens the squelch. If squelch hasn't opened yet, the squelch
                // state listener will fire notifyCallStart when it does.
                if(!mNoiseSquelch.isSquelched())
                {
                    notifyCallStart();
                }
            }
        }

        // Push to decoder state for activity summary display
        if(mDecoderState != null && code != null)
        {
            mDecoderState.setDetectedCTCSS(code);
        }
    }

    /**
     * Called by DCS decoder when a code is detected. If tone filtering is enabled,
     * this updates the tone match state.
     * @param code the detected DCS code
     */
    public void dcsDetected(DCSCode code)
    {
        mDetectedDCS = code;
        if(mToneFilterEnabled && code != null && mAllowedDCSCodes.contains(code))
        {
            if(!mToneMatched)
            {
                mToneMatched = true;
                // Same guard as ctcssDetected: only fire call start if squelch is open.
                if(!mNoiseSquelch.isSquelched())
                {
                    notifyCallStart();
                }
            }
        }

        // Push to decoder state for activity summary display
        if(mDecoderState != null && code != null)
        {
            mDecoderState.setDetectedDCS(code);
        }
    }

    /**
     * Called when tone is lost (no longer detected by the CTCSS or DCS detector).
     *
     * Note: we do NOT immediately clear mToneMatched here. The squelch holdover logic
     * in receive() already handles tone-match clearing after ~500ms of sustained squelch.
     * Clearing immediately here would bypass the holdover and cut audio during momentary
     * signal fades that recover within the holdover window. We only update the UI status.
     *
     * In mixed CTCSS+DCS configurations, both detectors run independently. We only update
     * the UI as "lost" if NEITHER detector currently has a matched code, to avoid falsely
     * showing no-tone when one type is still active.
     */
    public void toneLost()
    {
        // Only clear UI tone status if both CTCSS and DCS are currently unmatched.
        // In a mixed config, losing CTCSS while DCS is still active should not show "lost".
        boolean ctcssActive = mDetectedCTCSS != null && mAllowedCTCSSCodes.contains(mDetectedCTCSS);
        boolean dcsActive = mDetectedDCS != null && mAllowedDCSCodes.contains(mDetectedDCS);
        if(!ctcssActive && !dcsActive)
        {
            if(mDecoderState != null)
            {
                mDecoderState.setToneLost();
            }
        }
        // mToneMatched intentionally NOT cleared here — see holdover logic in receive()
    }

    /**
     * Indicates if a matching tone is currently detected
     */
    public boolean isToneMatched()
    {
        return !mToneFilterEnabled || mToneMatched;
    }

    /**
     * Returns the currently detected CTCSS code, or null
     */
    public CTCSSCode getDetectedCTCSS()
    {
        return mDetectedCTCSS;
    }

    /**
     * Returns the currently detected DCS code, or null
     */
    public DCSCode getDetectedDCS()
    {
        return mDetectedDCS;
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    @Override
    public DecodeConfigNBFM getDecodeConfiguration()
    {
        return (DecodeConfigNBFM)super.getDecodeConfiguration();
    }

    @Override
    public void setNoiseSquelchStateListener(Listener<NoiseSquelchState> listener)
    {
        mNoiseSquelch.setNoiseSquelchStateListener(listener);
    }

    @Override
    public void setNoiseThreshold(float open, float close)
    {
        mNoiseSquelch.setNoiseThreshold(open, close);
        getDecodeConfiguration().setSquelchNoiseOpenThreshold(open);
        getDecodeConfiguration().setSquelchNoiseCloseThreshold(close);
    }

    @Override
    public void setHysteresisThreshold(int open, int close)
    {
        mNoiseSquelch.setHysteresisThreshold(open, close);
        getDecodeConfiguration().setSquelchHysteresisOpenThreshold(open);
        getDecodeConfiguration().setSquelchHysteresisCloseThreshold(close);
    }

    @Override
    public void setSquelchOverride(boolean override)
    {
        mNoiseSquelch.setSquelchOverride(override);
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventProcessor;
    }

    @Override
    public void reset() {}

    @Override
    public void start() {}

    @Override
    public void stop()
    {
        if(mSquelchTailRemover != null)
        {
            mSquelchTailRemover.reset();
        }
        // Reset audio post-processing state so stale IIR filter history doesn't
        // cause artifacts (clicks, pops) if the channel is restarted.
        mBassBoostPrev = 0f;
        mPreviousDeemphasis = 0f;
        mVoiceEnhanceX1 = mVoiceEnhanceX2 = mVoiceEnhanceY1 = mVoiceEnhanceY2 = 0f;
        mNoiseGateRMSSquared = 0f;
        mNoiseGateCurrentGain = 1f;
        mNoiseGateHoldCounter = 0;
        mCachedOutputGain = 1.0f;
        // Reset tone state
        mToneMatched = false;
        mDetectedCTCSS = null;
        mDetectedDCS = null;
        mSquelchClosedSamples = 0;
    }

    protected void broadcast(float[] demodulatedSamples)
    {
        if(mResampledBufferListener != null)
        {
            mResampledBufferListener.receive(demodulatedSamples);
        }
    }

    @Override
    public void setBufferListener(Listener<float[]> listener)
    {
        mResampledBufferListener = listener;
    }

    @Override
    public void removeBufferListener()
    {
        mResampledBufferListener = null;
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    @Override
    public void receive(ComplexSamples samples)
    {
        if(mIDecimationFilter == null || mQDecimationFilter == null)
        {
            throw new IllegalStateException("NBFM demodulator module must receive a sample rate change source event " +
                    "before it can process complex sample buffers");
        }

        float[] decimatedI = mIDecimationFilter.decimateReal(samples.i());
        float[] decimatedQ = mQDecimationFilter.decimateReal(samples.q());

        float[] filteredI = mIBasebandFilter.filter(decimatedI);
        float[] filteredQ = mQBasebandFilter.filter(decimatedQ);

        float[] demodulated = mDemodulator.demodulate(filteredI, filteredQ);

        // Run tone detectors on raw demodulated audio BEFORE noise squelch processing.
        // The noise squelch high-pass filter removes sub-audible CTCSS tones (67-254 Hz),
        // so we must detect on the unfiltered stream. We always run detectors unconditionally
        // here — the old isSquelched() guard used a stale value (state from the previous buffer
        // before this buffer is evaluated). Detectors have internal state machines and
        // self-suppress on noise.
        if(mCTCSSDetector != null)
        {
            mCTCSSDetector.process(demodulated);
        }
        if(mDCSDetector != null)
        {
            mDCSDetector.process(demodulated);
        }

        mNoiseSquelch.process(demodulated);

        if(mNoiseSquelch.isSquelched())
        {
            // Don't immediately clear tone match — squelch may briefly close during
            // a transmission (noise spikes, fading). Track how long squelch has been
            // closed and only clear after sustained silence (~500ms holdover).
            if(mToneFilterEnabled)
            {
                mSquelchClosedSamples += demodulated.length;

                if(mSquelchClosedSamples > mSquelchHoldoverSamples)
                {
                    // Clamp counter so this block only executes once after holdover expires,
                    // not on every subsequent buffer while squelch remains closed.
                    mSquelchClosedSamples = mSquelchHoldoverSamples + 1;
                    if(mToneMatched)
                    {
                        mToneMatched = false;
                        mDetectedCTCSS = null;
                        mDetectedDCS = null;
                        // Sync UI — holdover expired means tone is definitively gone
                        if(mDecoderState != null)
                        {
                            mDecoderState.setToneLost();
                        }
                    }
                }
            }
            notifyIdle();
        }
        else
        {
            // Squelch is open — reset the closed counter
            mSquelchClosedSamples = 0;
        }
    }

    private void notifyCallStart()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.START, State.CALL, 0));
    }

    private void notifyCallContinuation()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.CALL, 0));
    }

    private void notifyCallEnd()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.CALL, 0));
    }

    private void notifyIdle()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.IDLE, 0));
    }

    private void broadcast(DecoderStateEvent event)
    {
        if(mDecoderStateEventListener != null)
        {
            mDecoderStateEventListener.receive(event);
        }
    }

    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mDecoderStateEventListener = listener;
    }

    @Override
    public void removeDecoderStateListener()
    {
        mDecoderStateEventListener = null;
    }

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

        // Calculate de-emphasis alpha for this sample rate
        if(mDeemphasisEnabled)
        {
            DecodeConfigNBFM.DeemphasisMode mode = getDecodeConfiguration().getDeemphasis();
            if(mode != null && mode.getMicroseconds() > 0)
            {
                double tau = mode.getMicroseconds() / 1_000_000.0; // Convert µs to seconds
                double dt = 1.0 / decimatedSampleRate;
                mDeemphasisAlpha = (float)(dt / (tau + dt));
                mLog.info("FM de-emphasis configured: τ={}µs, α={}, sample rate={}",
                        mode.getMicroseconds(), String.format("%.6f", mDeemphasisAlpha), decimatedSampleRate);
            }
        }

        // Recreate CTCSS detector at the actual decimated sample rate
        if(mToneFilterEnabled && !mAllowedCTCSSCodes.isEmpty())
        {
            createCTCSSDetector((float) decimatedSampleRate);
        }

        // Recreate DCS detector at the actual decimated sample rate
        if(mToneFilterEnabled && !mAllowedDCSCodes.isEmpty())
        {
            createDCSDetector((float) decimatedSampleRate);
        }

        // Tone match holdover: 500ms of sustained squelch before clearing tone match
        mSquelchHoldoverSamples = (int)(decimatedSampleRate * 0.5);

        int passBandStop = (int) (mChannelBandwidth * .8);
        int stopBandStart = (int) mChannelBandwidth;

        float[] coefficients = null;

        FIRFilterSpecification specification = FIRFilterSpecification.lowPassBuilder().sampleRate(decimatedSampleRate * 2).gridDensity(16).oddLength(true).passBandCutoff(passBandStop).passBandAmplitude(1.0).passBandRipple(0.01).stopBandStart(stopBandStart).stopBandAmplitude(0.0).stopBandRipple(0.005)
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

        mResampler = new RealResampler(decimatedSampleRate, DEMODULATED_AUDIO_SAMPLE_RATE, 4192, 512);

        // Wire resampler output through the audio post-processing chain.
        // Order: Resampler → [BassBoost] → [NoiseGate] → [VoiceEnhance] → [OutputGain] → broadcast
        mResampler.setListener(audio -> {
            audio = applyAudioPostProcessing(audio);
            NBFMDecoder.this.broadcast(audio);
        });

        // Connect squelch tail remover to resampler
        if(mSquelchTailRemovalEnabled && mSquelchTailRemover != null)
        {
            mSquelchTailRemover.setOutputListener(audio -> mResampler.resample(audio));
        }

        // (Re)initialize audio post-processing state when sample rate changes
        initAudioPostProcessing();
    }

    // === Audio post-processing state ===
    // Bass boost: single-pole IIR low-shelf implementation
    private float mBassBoostPrev = 0f;
    private float mBassBoostAlpha = 0f;
    private float mBassBoostGain = 0f;
    // Noise gate: tracks signal RMS² (squared; sqrt deferred to threshold compare) and applies gain reduction
    private boolean mNoiseGateActive = false;        // explicit enable flag — don't rely on threshold > 0
    private int mNoiseGateHoldCounter = 0;           // int, not float — it's a sample counter
    private float mNoiseGateRMSSquared = 0f;         // stored as RMS² to avoid per-sample sqrt
    private float mNoiseGateThresholdSquared = 0f;   // cached threshold² for comparison
    private float mNoiseGateReductionTarget = 0f;    // cached (1.0 - noiseGateReduction)
    private int mNoiseGateHoldSamples = 0;           // cached hold sample count
    private float mNoiseGateCurrentGain = 1f;
    // Voice enhance (midrange boost): biquad peaking EQ state
    private float mVoiceEnhanceX1 = 0, mVoiceEnhanceX2 = 0, mVoiceEnhanceY1 = 0, mVoiceEnhanceY2 = 0;
    private float mVoiceEnhanceB0 = 0, mVoiceEnhanceB1 = 0, mVoiceEnhanceB2 = 0;
    private float mVoiceEnhanceA1 = 0, mVoiceEnhanceA2 = 0;
    private boolean mVoiceEnhanceActive = false;     // true only when coefficients are valid and enabled
    private float mCachedOutputGain = 1.0f;          // cached in initAudioPostProcessing — no per-buffer config lookup

    // Noise gate time constants — static so JIT can treat them as compile-time constants
    private static final float NOISE_GATE_RMS_ALPHA = 0.01f;
    private static final float NOISE_GATE_ONE_MINUS_RMS_ALPHA = 1.0f - NOISE_GATE_RMS_ALPHA;
    private static final float NOISE_GATE_RAMP_RATE = 0.05f;

    /**
     * Initializes or re-initializes audio post-processing filter coefficients.
     * Called when sample rate changes. All processing occurs at the output (8kHz) sample rate.
     */
    private void initAudioPostProcessing()
    {
        final double sr = DEMODULATED_AUDIO_SAMPLE_RATE; // Always 8kHz output — constant
        DecodeConfigNBFM cfg = getDecodeConfiguration();

        // --- Bass boost coefficients ---
        // Low-shelf IIR; corner at ~300 Hz. Alpha controls shelf frequency.
        if(cfg.isBassBoostEnabled() && cfg.getBassBoostDb() > 0)
        {
            double boostLinear = Math.pow(10.0, cfg.getBassBoostDb() / 20.0);
            double fc = 300.0;
            double w0 = 2.0 * Math.PI * fc / sr;
            mBassBoostAlpha = (float)(w0 / (w0 + 1.0));
            mBassBoostGain = (float)(boostLinear - 1.0); // extra gain on low-freq component
        }
        else
        {
            mBassBoostAlpha = 0f;
            mBassBoostGain = 0f;
        }

        // --- Noise gate cached parameters ---
        // Use mNoiseGateActive as the explicit enable flag so applyAudioPostProcessing()
        // has a clear boolean to branch on, rather than inferring enabled state from
        // mNoiseGateThresholdSquared > 0 (which silently fails at threshold == 0%).
        mNoiseGateActive = cfg.isNoiseGateEnabled();
        if(mNoiseGateActive)
        {
            float threshold = cfg.getNoiseGateThreshold() / 100.0f;
            mNoiseGateThresholdSquared = threshold * threshold; // compare RMS² to threshold²
            mNoiseGateReductionTarget = 1.0f - cfg.getNoiseGateReduction();
            mNoiseGateHoldSamples = (int)(cfg.getNoiseGateHoldTime() / 1000.0 * sr);
        }
        else
        {
            // Explicitly zero cached values so a previously enabled gate doesn't linger
            // if the user disables it and the channel is reconfigured without a full restart.
            mNoiseGateThresholdSquared = 0f;
            mNoiseGateReductionTarget = 0f;
            mNoiseGateHoldSamples = 0;
        }

        // --- Voice enhance: peaking biquad EQ centered at ~2 kHz ---
        // Covers the primary speech intelligibility range with a gentle Q.
        if(cfg.isVoiceEnhanceEnabled() && cfg.getVoiceEnhanceAmount() > 0)
        {
            double fc = 2000.0;
            double Q = 1.2;
            double amount = cfg.getVoiceEnhanceAmount() / 100.0; // 0..1
            double w0 = 2.0 * Math.PI * fc / sr;
            double sinW0 = Math.sin(w0);
            double cosW0 = Math.cos(w0);
            double alpha = sinW0 / (2.0 * Q);
            double A = 1.0 + amount; // Peak gain at center
            double a0 = 1.0 + alpha / A;
            mVoiceEnhanceB0 = (float)((1.0 + alpha * A) / a0);
            mVoiceEnhanceB1 = (float)((-2.0 * cosW0) / a0);
            mVoiceEnhanceB2 = (float)((1.0 - alpha * A) / a0);
            mVoiceEnhanceA1 = (float)((-2.0 * cosW0) / a0);
            mVoiceEnhanceA2 = (float)((1.0 - alpha / A) / a0);
            mVoiceEnhanceActive = true;
        }
        else
        {
            // Explicitly zero coefficients so the biquad is truly bypassed — not just skipped.
            mVoiceEnhanceB0 = mVoiceEnhanceB1 = mVoiceEnhanceB2 = 0f;
            mVoiceEnhanceA1 = mVoiceEnhanceA2 = 0f;
            mVoiceEnhanceActive = false;
        }

        // --- Output gain ---
        // Cache here so applyAudioPostProcessing() needs no config lookup per buffer.
        mCachedOutputGain = cfg.getOutputGain();

        // Reset all filter state variables (including de-emphasis — sample rate may have changed
        // so the previous output value is invalid at the new rate's time constant)
        mBassBoostPrev = 0f;
        mPreviousDeemphasis = 0f;
        mVoiceEnhanceX1 = mVoiceEnhanceX2 = mVoiceEnhanceY1 = mVoiceEnhanceY2 = 0f;
        mNoiseGateRMSSquared = 0f;
        mNoiseGateCurrentGain = 1f;
        mNoiseGateHoldCounter = 0;
    }

    /**
     * Applies the configured audio post-processing chain to the resampled 8kHz audio.
     * Chain: [BassBoost] → [NoiseGate] → [VoiceEnhance] → [OutputGain]
     * @param samples input audio at 8kHz
     * @return processed audio
     */
    private float[] applyAudioPostProcessing(float[] samples)
    {
        // NOTE: All coefficient/threshold values are pre-cached in initAudioPostProcessing().
        // Do NOT call getDecodeConfiguration() here — it's a virtual method call + cast
        // on every audio buffer (every ~23ms at 8kHz) and the values don't change mid-stream.

        // --- Bass Boost ---
        // Low-shelf IIR: extract bass component via lowpass, then mix back with extra gain.
        if(mBassBoostAlpha > 0 && mBassBoostGain > 0)
        {
            final float alpha = mBassBoostAlpha;
            final float oneMinusAlpha = 1f - alpha;   // hoisted — avoids recomputing per sample
            final float gain = mBassBoostGain;
            float prev = mBassBoostPrev;
            for(int i = 0; i < samples.length; i++)
            {
                float bass = alpha * samples[i] + oneMinusAlpha * prev;
                samples[i] = samples[i] + bass * gain;
                prev = bass;
            }
            mBassBoostPrev = prev;
        }

        // --- Noise Gate ---
        // Uses leaky RMS² (no per-sample sqrt) compared against cached threshold².
        // Applies smooth gain reduction below threshold with hold period to prevent
        // chattering on signals near the threshold boundary.
        if(mNoiseGateActive)
        {
            float rmsSquared = mNoiseGateRMSSquared;
            float currentGain = mNoiseGateCurrentGain;
            int holdCounter = mNoiseGateHoldCounter;

            for(int i = 0; i < samples.length; i++)
            {
                // Leaky RMS² — avoids sqrt in the inner loop; constants are static finals
                rmsSquared = NOISE_GATE_RMS_ALPHA * samples[i] * samples[i] + NOISE_GATE_ONE_MINUS_RMS_ALPHA * rmsSquared;

                if(rmsSquared >= mNoiseGateThresholdSquared)
                {
                    currentGain = 1.0f;
                    holdCounter = mNoiseGateHoldSamples;
                }
                else if(holdCounter > 0)
                {
                    holdCounter--;
                    currentGain = 1.0f; // Hold gate open during hold period
                }
                else
                {
                    // Ramp gain toward reduction target (avoids abrupt cuts)
                    currentGain += (mNoiseGateReductionTarget - currentGain) * NOISE_GATE_RAMP_RATE;
                }

                samples[i] *= currentGain;
            }

            mNoiseGateRMSSquared = rmsSquared;
            mNoiseGateCurrentGain = currentGain;
            mNoiseGateHoldCounter = holdCounter;
        }

        // --- Voice Enhance (peaking biquad EQ at ~2kHz) ---
        // mVoiceEnhanceActive is set false when disabled so we skip without any reflection/cast.
        if(mVoiceEnhanceActive)
        {
            float x1 = mVoiceEnhanceX1, x2 = mVoiceEnhanceX2;
            float y1 = mVoiceEnhanceY1, y2 = mVoiceEnhanceY2;
            float b0 = mVoiceEnhanceB0, b1 = mVoiceEnhanceB1, b2 = mVoiceEnhanceB2;
            float a1 = mVoiceEnhanceA1, a2 = mVoiceEnhanceA2;

            for(int i = 0; i < samples.length; i++)
            {
                float x0 = samples[i];
                float y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x0;
                y2 = y1; y1 = y0;
                samples[i] = y0;
            }

            mVoiceEnhanceX1 = x1; mVoiceEnhanceX2 = x2;
            mVoiceEnhanceY1 = y1; mVoiceEnhanceY2 = y2;
        }

        // --- Output Gain with soft clipping ---
        // Soft clip is applied whenever any upstream stage (bass boost, voice enhance, or
        // explicit gain) may have pushed samples above ±1.0. We skip the loop only when
        // gain is exactly unity AND no upstream processing is active — otherwise we always
        // run, because bass boost or voice enhance alone can clip without any gain change.
        boolean upstreamCanClip = (mBassBoostAlpha > 0 && mBassBoostGain > 0) || mVoiceEnhanceActive;
        float gain = mCachedOutputGain; // pre-cached in initAudioPostProcessing() — no config lookup per buffer
        if(gain != 1.0f || upstreamCanClip)
        {
            for(int i = 0; i < samples.length; i++)
            {
                float s = samples[i] * gain;
                if(s > 1.0f)
                {
                    s = 1.0f - (float)Math.exp(-(s - 1.0f));
                }
                else if(s < -1.0f)
                {
                    s = -1.0f + (float)Math.exp((s + 1.0f));
                }
                samples[i] = s;
            }
        }

        return samples;
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
        }
    }
}
