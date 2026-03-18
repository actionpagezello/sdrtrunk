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

    // Squelch tail/head removal
    private final boolean mSquelchTailRemovalEnabled;
    private final int mSquelchTailRemovalMs;
    private final int mSquelchHeadRemovalMs;
    private SquelchTailRemover mSquelchTailRemover;

    /**
     * Constructs an instance
     *
     * @param config to setup the NBFM decoder and noise squelch control.
     */
    public NBFMDecoder(DecodeConfigNBFM config)
    {
        super(config);

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
                mLog.info("CTCSS tone filtering enabled with {} target code(s)", mTargetCTCSSCodes.size());
            }
            else if(!mTargetDCSCodes.isEmpty())
            {
                mToneFilterType = ChannelToneFilter.ToneType.DCS;
                mLog.info("DCS code filtering enabled with {} target code(s)", mTargetDCSCodes.size());
            }
            else
            {
                mToneFilterType = null;
                mLog.warn("Tone filtering enabled but no valid CTCSS or DCS codes configured");
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
        mNoiseSquelch.setAudioListener(audio -> {
            mResampler.resample(audio);

            // Only signal call activity if tone matches (or no tone filter configured)
            if(!mToneFilterEnabled || mToneMatch)
            {
                notifyCallContinuation();
            }
        });

        //Notify the decoder state of call starts and ends, and manage tail remover + tone reset.
        //When tone filtering is enabled, we defer the call start until the correct tone is confirmed.
        //The channel stays idle until the CTCSS detector confirms the right tone — just like a real radio.
        mNoiseSquelch.setSquelchStateListener(squelchState -> {
            if(squelchState == SquelchState.SQUELCH)
            {
                // Squelch closed (end of transmission)
                if(mSquelchTailRemover != null)
                {
                    mSquelchTailRemover.squelchClose();
                }

                // Reset tone match — next transmission must re-confirm
                boolean wasMatched = mToneMatch;
                mToneMatch = false;

                // Reset the tone detectors for the next transmission
                if(mCTCSSDetector != null)
                {
                    mCTCSSDetector.reset();
                }
                if(mDCSDetector != null)
                {
                    mDCSDetector.reset();
                }

                // Only send call end if a call was actually active (tone was matched or no filter)
                if(!mToneFilterEnabled || wasMatched)
                {
                    notifyCallEnd();
                }
            }
            else
            {
                // Squelch opened (start of transmission)
                if(mSquelchTailRemover != null)
                {
                    mSquelchTailRemover.squelchOpen();
                }

                // Tone match starts as false; detector must confirm before channel activates
                mToneMatch = false;

                // If no tone filter, start call immediately (original behavior).
                // If tone filter is active, defer — channel stays idle until tone confirmed.
                if(!mToneFilterEnabled)
                {
                    notifyCallStart();
                }
            }
        });
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
        // Step 1: Feed audio to the active tone/code detector for analysis
        if(mCTCSSDetector != null)
        {
            mCTCSSDetector.process(resampledAudio);
        }
        if(mDCSDetector != null)
        {
            mDCSDetector.process(resampledAudio);
        }

        // Step 2: Gate audio based on tone/code match
        if(mToneFilterEnabled && !mToneMatch)
        {
            // Tone/code not confirmed yet — block audio
            return;
        }

        // Step 3: Pass through squelch tail remover if enabled, otherwise broadcast directly
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
    }

    /**
     * Broadcasts a call start state event
     */
    private void notifyCallStart()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.START, State.CALL, 0));
    }

    /**
     * Broadcasts a call continuation state event
     */
    private void notifyCallContinuation()
    {
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

                    // If we were previously blocked, fire a call start now
                    if(wasBlocked)
                    {
                        notifyCallStart();
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
                    boolean wasActive = mToneMatch;
                    mToneMatch = false;

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
                    boolean wasActive = mToneMatch;
                    mToneMatch = false;

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

            mLog.info("CTCSSDetector initialized at {} Hz sample rate", DEMODULATED_AUDIO_SAMPLE_RATE);
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

                    if(wasBlocked)
                    {
                        notifyCallStart();
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

            mLog.info("DCSDetector initialized for channel-level DCS filtering");
        }

        // Initialize squelch tail remover if enabled
        if(mSquelchTailRemovalEnabled)
        {
            mSquelchTailRemover = new SquelchTailRemover(mSquelchTailRemovalMs, mSquelchHeadRemovalMs);
            mSquelchTailRemover.setOutputListener(NBFMDecoder.this::broadcast);
            mLog.info("SquelchTailRemover initialized: tail={}ms, head={}ms", mSquelchTailRemovalMs, mSquelchHeadRemovalMs);
        }
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
