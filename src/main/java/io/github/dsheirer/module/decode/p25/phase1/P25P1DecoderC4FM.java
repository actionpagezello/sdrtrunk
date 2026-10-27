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

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFactory;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloat;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.dmr.message.DMRBurst;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessageWithLinkControl;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.FullLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ShortLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ChannelFrequencyCorrectionStatusNotification;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APCO25 Phase 1 decoder.  Decimates incoming sample buffers to as close as possible to 25 kHz for ~5 samples per
 * symbol.  Employs baseband and pulse shaping filters.  Demodulates the complex baseband (I/Q) sample stream using
 * SIMD differential demodulation of the entire sample stream.  The C4FM demodulator (mSymbolProcessor) performs sync
 * detection, timing correction and PLL signal mistune correction.  Message framer performs message framing and message
 * creation.  A registered message listener receives the detected and framed messages.
 *
 * As a child of the FeedbackDecoder, this decoder provides periodic PLL measurements to the tuner for automatic PPM
 * correction.  It also provides a stream of demodulated soft symbols (in radians) for display to the user.
 */
public class P25P1DecoderC4FM extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener,
        ISourceEventListener, Listener<ComplexSamples>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25P1DecoderC4FM.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##");
    private static final int SYMBOL_RATE = 4800;
    private static final Map<Double,float[]> BASEBAND_FILTERS = new HashMap<>();

    private final P25P1DemodulatorC4FM mSymbolProcessor;
    private final P25P1MessageFramer mMessageFramer = new P25P1MessageFramer();
    private final P25P1MessageProcessor mMessageProcessor = new P25P1MessageProcessor();
    private DifferentialDemodulatorFloat mDemodulator;
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private IRealFilter mBasebandFilterI;
    private IRealFilter mBasebandFilterQ;
    private IRealFilter mPulseShapingFilterI;
    private IRealFilter mPulseShapingFilterQ;

    //AP-fork (ap-15.9.32): 10-minute sync health line per channel
    private static final long SYNC_HEALTH_INTERVAL_MINUTES = 10;
    private ScheduledFuture<?> mSyncHealthLog;
    private String mLogLabel = "P25 Phase 1 channel";
    private volatile long mChannelMixerCorrection;
    private volatile boolean mChannelMixerCorrectionReported;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    public P25P1DecoderC4FM()
    {
        mMessageProcessor.setMessageListener(getMessageListener());
        mSymbolProcessor = new P25P1DemodulatorC4FM(mMessageFramer, this);
    }

    @Override
    public String getProtocolDescription()
    {
        return "P25 Phase 1 C4FM";
    }

    /**
     * Sets the sample rate and configures internal decoder components.
     * @param sampleRate of the channel to decode
     */
    public void setSampleRate(double sampleRate)
    {
        if(sampleRate <= SYMBOL_RATE * 2)
        {
            throw new IllegalArgumentException("Sample rate [" + sampleRate + "] must be >9600 (2 * " +
                    SYMBOL_RATE + " symbol rate)");
        }

        int decimation = 1;

        //Identify decimation that gets us as close to 4.0 Samples Per Symbol as possible (19.2 kHz)
        while((sampleRate / decimation) >= 38400)
        {
            decimation *= 2;
        }

        mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);

        float decimatedSampleRate = (float)sampleRate / decimation;

        //Set the decimated sample rate to use for PLL error reporting.
        setDecimatedSampleRate(decimatedSampleRate);

        //AP-fork, adopted from upstream PR #2461 (unmerged at the time of writing): FilterFactory
        //.getRootRaisedCosine() halves its samples-per-symbol argument on its first line, so the previous call with
        //the actual value (4.0) designed a 9600 baud, alpha 0.2 filter rather than the intended ~4800 baud one -
        //admitting roughly twice the noise bandwidth of the C4FM signal.  Passing 1.5x the actual value with
        //alpha 0.5 designs at 6400 baud.  Upstream measured a sweep of design rate and roll-off against an
        //independent IQ oracle and by control-channel content corroboration: a plateau at 6400 baud with alpha
        //0.5-0.7 recovered +8.7 to 9.4% corroborated TSBKs on the weakest control channel in their set, with no
        //change on the stronger one, marginal and clean voice recall unchanged, and refuted claims unchanged.  A true
        //4800 baud alpha 0.2 design is too narrow for C4FM and costs ~4 points of marginal voice recall.
        //
        //Fleet expectation to verify: the ap-15.9.6 unknown-ALG-ID WARN showed scattered one-off algorithm IDs on
        //marginal talkgroups (Somerville Arlington Police 10 distinct IDs, Watertown 9, Winthrop 5; Daly North
        //Andover 7).  Those are corrupt encryption sync fields from bad symbol decisions, so if this filter is
        //correct those counts should fall.  tools/fleetcheck.py reports them.
        //
        //NOT applied to P25P1DecoderLSM or DMRDecoder, which call the same method the same way and have the same
        //mis-design: LSM is not C4FM and DMR is 4FSK, so these constants do not transfer and each needs its own
        //measurement.  See the upstream-PR section in CLAUDE.md.
        int symbolLength = 16;
        float rrcAlpha = 0.5f;

        float[] taps = FilterFactory.getRootRaisedCosine(1.5 * decimatedSampleRate / SYMBOL_RATE,
                symbolLength, rrcAlpha);
        mPulseShapingFilterI = new RealFIRFilter(taps);
        mPulseShapingFilterQ = new RealFIRFilter(taps);

        mBasebandFilterI = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mBasebandFilterQ = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mDemodulator = DifferentialDemodulatorFactory.getFloatDemodulator(decimatedSampleRate, SYMBOL_RATE);
        mSymbolProcessor.setSamplesPerSymbol(mDemodulator.getSamplesPerSymbol());
        mMessageFramer.setListener(mMessageProcessor);
        mMessageProcessor.setMessageListener(getMessageListener());
    }

    /**
     * Primary method for processing incoming complex sample buffers
     * @param samples containing channelized complex samples
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        //Update the message framer with the timestamp from the incoming sample buffer.
        mMessageFramer.setTimestamp(samples.timestamp());

        float[] i = mDecimationFilterI.decimateReal(samples.i());
        float[] q = mDecimationFilterQ.decimateReal(samples.q());

        i = mBasebandFilterI.filter(i);
        q = mBasebandFilterQ.filter(q);

        i = mPulseShapingFilterI.filter(i);
        q = mPulseShapingFilterQ.filter(q);

        // PI/4 DQPSK differential demodulation
        float[] demodulated = mDemodulator.demodulate(i, q);

        //Process demodulated samples into symbols and apply message sync detection and framing.
        mSymbolProcessor.process(demodulated);
    }

    /**
     * Constructs a baseband filter for this decoder using the current sample rate
     */
    private float[] getBasebandFilter(double sampleRate)
    {
        if(BASEBAND_FILTERS.containsKey(sampleRate))
        {
            return BASEBAND_FILTERS.get(sampleRate);
        }

        FIRFilterSpecification specification = FIRFilterSpecification
                .lowPassBuilder()
                .sampleRate(sampleRate)
                .passBandCutoff(5200)
                .passBandAmplitude(1.0).passBandRipple(0.01) //.01
                .stopBandAmplitude(0.0).stopBandStart(6500) //6500
                .stopBandRipple(0.01).build();

        float[] coefficients = null;

        try
        {
            coefficients = FilterFactory.getTaps(specification);
            BASEBAND_FILTERS.put(sampleRate, coefficients);
        }
        catch(Exception fde) //FilterDesignException
        {
            System.out.println("Error");
        }

        if(coefficients == null)
        {
            throw new IllegalStateException("Unable to design low pass filter for sample rate [" + sampleRate + "]");
        }

        return coefficients;
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(listener);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(null);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public boolean hasBufferListeners()
    {
        return mSymbolProcessor.hasBufferListener();
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return this::process;
    }

    /**
     * AP-fork (ap-15.9.32): label used to attribute this decoder's log lines to its channel.
     */
    public void setLogLabel(String label)
    {
        if(label != null && !label.isBlank())
        {
            mLogLabel = label;
            mSymbolProcessor.setLogLabel(label);
        }
    }

    @Override
    public void start()
    {
        super.start();
        mMessageFramer.start();

        if(mSyncHealthLog == null)
        {
            mSyncHealthLog = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::logSyncHealth,
                SYNC_HEALTH_INTERVAL_MINUTES, SYNC_HEALTH_INTERVAL_MINUTES, TimeUnit.MINUTES);
        }
    }

    @Override
    public void stop()
    {
        ScheduledFuture<?> syncHealthLog = mSyncHealthLog;

        if(syncHealthLog != null)
        {
            syncHealthLog.cancel(false);
            mSyncHealthLog = null;
        }

        super.stop();
        mMessageFramer.stop();
    }

    /**
     * AP-fork (ap-15.9.32): one line per interval describing the syncs this channel accepted and withheld, where the
     * equalizer sits and how far the channel mixer has been moved.  The P25 Phase 1 audio module logs its own health
     * line for the same channel.
     */
    private void logSyncHealth()
    {
        try
        {
            LOGGER.info("P25 Phase 1 sync health on [{}] last {} min: {}, channel mixer correction {}", mLogLabel,
                SYNC_HEALTH_INTERVAL_MINUTES, mSymbolProcessor.getSyncHealth(),
                mChannelMixerCorrectionReported ? mChannelMixerCorrection + " Hz" : "not reported");
        }
        catch(Exception e)
        {
            LOGGER.debug("P25 sync health log failed", e);
        }
    }

    /**
     * Process source events
     */
    private void process(SourceEvent sourceEvent)
    {
        switch(sourceEvent.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
            case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                mSymbolProcessor.resetPLL();
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                setSampleRate(sourceEvent.getValue().doubleValue());
                break;
            case NOTIFICATION_CHANNEL_FREQUENCY_CORRECTION_STATUS:
                //AP-fork (ap-15.9.32): remembered for the sync health line
                if(sourceEvent instanceof ChannelFrequencyCorrectionStatusNotification status)
                {
                    mChannelMixerCorrection = status.getChannelCorrection();
                    mChannelMixerCorrectionReported = true;
                }
                break;
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    public static void main(String[] args)
    {
        LOGGER.info("Starting ...");

//        String directory = "D:\\DQPSK Equalizer Research - P25\\"; //Windows
        String directory = "/media/denny/T9/DQPSK Equalizer Research - P25/"; //Linux

        String file = directory + "P25-S1-Conventional-repeater-20241115_212221_469325000_QPS_Digital_Kynoch_Kynoch_Digital_59_baseband.wav";
//        String file = directory + "P25-S3-C4FM-20241225_040459_152517500_NYSEG_Onondaga_Control_30_baseband.wav";

        boolean autoReplay = false;

        P25P1DecoderC4FM decoder = new P25P1DecoderC4FM();
        decoder.start();

        UserPreferences userPreferences = new UserPreferences();
        P25P1AudioModule audio1 = new P25P1AudioModule(userPreferences, new AliasList(""));

        decoder.setMessageListener(new Listener<>()
        {
            private long mBitCounter = 1;
            private int mBitErrorCounter;
            private int mValidMessageCounter;
            private int mTotalMessageCounter;

            @Override
            public void receive(IMessage iMessage)
            {
                int errors = 0;

                audio1.receive(iMessage);

                if(iMessage instanceof P25P1Message message)
                {
                    mBitCounter += 288;

                    if(message.getMessage() != null)
                    {
                        errors = message.getMessage().getCorrectedBitCount();
                    }

                    mTotalMessageCounter++;

                    if(mTotalMessageCounter == 492)
                    {
                        int a = 0;
                    }
                    if(message.isValid())
                    {
                        mBitErrorCounter += Math.max(errors, 0);
                        mValidMessageCounter++;
                    }
                }
                else if(iMessage instanceof LCMessage lcw)
                {
                    mTotalMessageCounter++;
                    errors = lcw.getMessage().getCorrectedBitCount();
                    if(lcw.isValid())
                    {
                        mBitErrorCounter += errors;
                        mValidMessageCounter++;
                    }
                }

                double bitErrorRate = (double)mBitErrorCounter / (double)mBitCounter * 100.0;

                boolean logEverything = true;
                boolean logFLC = true;
                boolean logSLC = true;
                boolean logCACH = true;
                boolean logIdles = true;

                if(logEverything)
                {
                    if(iMessage.toString().contains("PLACEHOLDER"))
                    {
                        int a = 0;
                    }
                    System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                }
                else
                {
                    if (logFLC)
                    {
                        if(iMessage instanceof FullLCMessage)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                        else if(iMessage instanceof Terminator terminator)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                        else if(iMessage instanceof DataMessageWithLinkControl)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                    }

                    if(logCACH && iMessage instanceof DMRBurst burst && burst.hasCACH())
                    {
                        System.out.println("CACH:" + burst.getCACH());
                    }

                    if(logSLC && iMessage instanceof ShortLCMessage)
                    {
                        System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                    }

                    if(logIdles && iMessage instanceof SyncLossMessage)
                    {
                        System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                    }
                }
            }
        });

        try(ComplexWaveSource source = new ComplexWaveSource(new File(file), autoReplay))
        {
            source.setListener(iNativeBuffer -> {
                Iterator<ComplexSamples> it = iNativeBuffer.iterator();

                while(it.hasNext())
                {
                    decoder.receive(it.next());
                }
            });
            source.start();
            decoder.setSampleRate(source.getSampleRate());

            while(true)
            {
                source.next(2048, true);
            }
        }
        catch(IOException ioe)
        {
            LOGGER.error("Error", ioe);
        }

        LOGGER.info("Finished");
    }
}
