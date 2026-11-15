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
package io.github.dsheirer.dsp.audio;

import io.github.dsheirer.dsp.filter.IIRBiQuadraticFilter;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures mains hum in a demodulated audio stream and reports what it finds at the end of each
 * transmission.  This is a diagnostic, not a filter - it changes nothing about the audio.
 *
 * The point is to establish, before any filter is designed, four things that a filter choice
 * depends on and that listening cannot settle:
 *
 * 1. Whether the hum is actually at a mains frequency at all, and which one.  50 Hz energy on a US
 *    system is not mains and points somewhere else entirely.
 * 2. Which harmonic dominates.  Energy concentrated at the fundamental suggests direct coupling of
 *    a magnetic field or a ground loop; a dominant second harmonic (120 Hz on a 60 Hz system) is
 *    the signature of full-wave rectifier ripple in a power supply.  A notch at the fundamental
 *    alone is a common and disappointing fix precisely because the second harmonic is often the
 *    louder one.
 * 3. How loud the hum is relative to voice, which decides whether a filter is worth the phase
 *    distortion it costs.
 * 4. Whether the hum level is constant across the transmission or rises during speech pauses.  A
 *    constant absolute level that only becomes audible in the gaps is ordinary additive hum, and a
 *    filter removes it.  A level that genuinely rises in the gaps is a gain stage pulling the noise
 *    floor up when the signal drops, and filtering the hum treats a symptom of the gain control.
 *
 * Analysis is done with a Goertzel evaluation per candidate frequency over a Hann-windowed block of
 * {@value #BLOCK_SIZE} samples.  At 8 kHz that is 200 ms and a 5 Hz bin spacing, chosen so that
 * every frequency in {@link #HUM_FREQUENCIES} falls on an exact bin centre - there is no scalloping
 * loss to correct for, and 50 Hz and 60 Hz are two bins apart rather than sharing one.
 *
 * All output is at DEBUG on this class's logger, and every measurement is skipped when that logger
 * is not at DEBUG, so the cost when it is switched off is one boolean check per buffer.  Enable it
 * from the Diagnostics preferences panel.
 */
public class HumAnalyzer
{
    private static final Logger mLog = LoggerFactory.getLogger(HumAnalyzer.class);

    /**
     * Candidate hum frequencies.  Both mains fundamentals and the first harmonics of each: 100 and
     * 200 Hz belong to a 50 Hz system, 120 and 180 and 240 Hz to a 60 Hz system.  Measuring the
     * wrong-mains set as well is what makes the result falsifiable rather than confirmatory.
     */
    public static final int[] HUM_FREQUENCIES = {50, 60, 100, 120, 180, 240};

    /**
     * Samples per analysis block.  At 8 kHz this is 200 ms and a 5 Hz bin spacing, which places
     * every entry in {@link #HUM_FREQUENCIES} on an exact bin centre.  Changing this without
     * re-checking that property will introduce scalloping loss and understate the hum.
     */
    private static final int BLOCK_SIZE = 1600;

    /**
     * Corner frequency of the high-pass cascade used to derive a voice-band reference level.
     */
    private static final double VOICE_HIGH_PASS_HZ = 300.0;

    /**
     * Number of cascaded second-order sections in the voice-band high-pass.  Three measures out at
     * 84 dB of rejection at 60 Hz and 48 dB at 120 Hz, so the voice reference is not itself mostly
     * hum.  Rejection at 180 Hz is only 28 dB, so a transmission whose hum is dominated by the third
     * harmonic will have its hum-to-voice ratio slightly understated.
     */
    private static final int VOICE_HIGH_PASS_SECTIONS = 3;

    /**
     * Coherent gain of a Hann window, used to recover the amplitude of a sinusoid from its windowed
     * bin magnitude.
     */
    private static final double HANN_COHERENT_GAIN = 0.5;

    /**
     * Amplitude floor, about -180 dBFS, so silent blocks produce a very negative number rather than
     * negative infinity.
     */
    private static final double MIN_AMPLITUDE = 1.0e-9;

    /**
     * A block is treated as a speech pause when its voice-band level is this far below the loudest
     * voice-band level seen during the transmission.
     */
    private static final double PAUSE_THRESHOLD_DB = -25.0;

    /**
     * Hum this close to the voice level, or closer, is loud enough to hear.
     */
    private static final double AUDIBLE_HUM_TO_VOICE_DB = -20.0;

    /**
     * Transmissions shorter than this are not reported - too few blocks to say anything.
     */
    private static final int MINIMUM_BLOCKS = 3;

    /**
     * Cap on retained per-block measurements, two minutes at 200 ms per block.  Bounds memory on a
     * stuck-open squelch.
     */
    private static final int MAXIMUM_BLOCKS = 600;

    private volatile String mChannelLabel;
    private final double mSampleRate;
    private final double[] mGoertzelCoefficients;
    private final float[] mWindow;
    private final IIRBiQuadraticFilter[] mVoiceHighPass;

    private final float[] mBlock;
    private int mBlockIndex;
    private final List<BlockMeasurement> mMeasurements = new ArrayList<>();
    private boolean mOverflowWarned;

    /**
     * Constructs a hum analyzer for one channel's audio stream.
     *
     * @param channelLabel for log output
     * @param sampleRate of the audio stream, expected to be 8000
     */
    public HumAnalyzer(String channelLabel, double sampleRate)
    {
        mChannelLabel = channelLabel;
        mSampleRate = sampleRate;
        mBlock = new float[BLOCK_SIZE];
        mWindow = WindowFactory.getWindow(WindowType.HANN, BLOCK_SIZE);

        mGoertzelCoefficients = new double[HUM_FREQUENCIES.length];

        for(int x = 0; x < HUM_FREQUENCIES.length; x++)
        {
            mGoertzelCoefficients[x] = 2.0 * Math.cos(2.0 * Math.PI * HUM_FREQUENCIES[x] / sampleRate);
        }

        mVoiceHighPass = new IIRBiQuadraticFilter[VOICE_HIGH_PASS_SECTIONS];

        for(int x = 0; x < VOICE_HIGH_PASS_SECTIONS; x++)
        {
            mVoiceHighPass[x] = new IIRBiQuadraticFilter(IIRBiQuadraticFilter.Type.HIGHPASS, VOICE_HIGH_PASS_HZ,
                    sampleRate, 0.7071);
        }
    }

    /**
     * Indicates if analysis is switched on.  Callers should check this before doing any work on the
     * analyzer's behalf, including constructing one.
     */
    public static boolean isEnabled()
    {
        return mLog.isDebugEnabled();
    }

    /**
     * Updates the label used in log output.  A channel's name and frequency are often not known when
     * the decoder builds its audio chain, so the label arrives later.
     *
     * @param channelLabel to use
     */
    public void setChannelLabel(String channelLabel)
    {
        mChannelLabel = channelLabel;
    }

    /**
     * Accumulates audio for analysis.  The supplied array is not modified.
     *
     * This must be fed the audio as it arrives from the demodulator, ahead of any audio filtering,
     * so that what is measured is what is present rather than what survives the existing chain.
     *
     * @param samples audio samples
     */
    public void receive(float[] samples)
    {
        if(samples == null)
        {
            return;
        }

        for(float sample : samples)
        {
            mBlock[mBlockIndex++] = sample;

            if(mBlockIndex == BLOCK_SIZE)
            {
                mBlockIndex = 0;
                analyzeBlock();
            }
        }
    }

    /**
     * Evaluates one full block and retains the result for the end-of-transmission report.
     */
    private void analyzeBlock()
    {
        if(mMeasurements.size() >= MAXIMUM_BLOCKS)
        {
            if(!mOverflowWarned)
            {
                mLog.debug("[{}] hum analysis - retained block limit ({}) reached, later blocks in this " +
                        "transmission are not measured", mChannelLabel, MAXIMUM_BLOCKS);
                mOverflowWarned = true;
            }

            return;
        }

        //Window into a scratch copy: the caller's audio must not be touched, and the voice-band
        //high-pass below needs the unwindowed samples.
        float[] windowed = new float[BLOCK_SIZE];

        for(int x = 0; x < BLOCK_SIZE; x++)
        {
            windowed[x] = mBlock[x] * mWindow[x];
        }

        double[] amplitudes = new double[HUM_FREQUENCIES.length];

        for(int f = 0; f < HUM_FREQUENCIES.length; f++)
        {
            amplitudes[f] = goertzelAmplitude(windowed, mGoertzelCoefficients[f]);
        }

        mMeasurements.add(new BlockMeasurement(amplitudes, voiceBandRms()));
    }

    /**
     * Recovers the amplitude of a sinusoid at the frequency represented by the supplied coefficient.
     *
     * The Goertzel recurrence yields the magnitude of the corresponding DFT bin.  For a real
     * sinusoid of amplitude A on a bin centre, that magnitude is A x N x G / 2 where N is the block
     * size and G the window's coherent gain, so the amplitude is recovered by inverting that.
     *
     * @param windowed block, already multiplied by the window
     * @param coefficient 2 x cos(2 pi f / fs) for the frequency of interest
     * @return amplitude in the range 0 to 1 for full-scale audio
     */
    private static double goertzelAmplitude(float[] windowed, double coefficient)
    {
        double s = 0.0;
        double sPrevious = 0.0;
        double sPrevious2 = 0.0;

        for(float sample : windowed)
        {
            s = sample + (coefficient * sPrevious) - sPrevious2;
            sPrevious2 = sPrevious;
            sPrevious = s;
        }

        double magnitudeSquared = (sPrevious * sPrevious) + (sPrevious2 * sPrevious2)
                - (coefficient * sPrevious * sPrevious2);

        //Guard against a small negative from rounding before taking the root
        double magnitude = magnitudeSquared > 0 ? Math.sqrt(magnitudeSquared) : 0.0;

        return (2.0 * magnitude) / (BLOCK_SIZE * HANN_COHERENT_GAIN);
    }

    /**
     * Returns the RMS level of the current block above {@value #VOICE_HIGH_PASS_HZ} Hz, used as the
     * reference against which hum is judged.  Runs on the unwindowed samples.
     */
    private double voiceBandRms()
    {
        double sumOfSquares = 0.0;

        for(int x = 0; x < BLOCK_SIZE; x++)
        {
            double value = mBlock[x];

            for(IIRBiQuadraticFilter section : mVoiceHighPass)
            {
                value = section.filter(value);
            }

            sumOfSquares += value * value;
        }

        return Math.sqrt(sumOfSquares / BLOCK_SIZE);
    }

    /**
     * Reports what was measured across the transmission and resets for the next one.  Safe to call
     * when nothing was measured.
     */
    public void reportAndReset()
    {
        try
        {
            if(mMeasurements.size() >= MINIMUM_BLOCKS)
            {
                report();
            }
        }
        catch(Exception e)
        {
            //A diagnostic must never be able to break the audio path that hosts it
            mLog.error("[" + mChannelLabel + "] hum analysis failed", e);
        }
        finally
        {
            mMeasurements.clear();
            mBlockIndex = 0;
            mOverflowWarned = false;

            for(IIRBiQuadraticFilter section : mVoiceHighPass)
            {
                section.reset();
            }
        }
    }

    /**
     * Emits the end-of-transmission summary.
     */
    private void report()
    {
        int blocks = mMeasurements.size();
        double blockSeconds = BLOCK_SIZE / mSampleRate;

        double peakVoice = 0.0;

        for(BlockMeasurement measurement : mMeasurements)
        {
            peakVoice = Math.max(peakVoice, measurement.mVoiceRms);
        }

        double pauseCutoff = peakVoice * Math.pow(10.0, PAUSE_THRESHOLD_DB / 20.0);

        //Mean power per frequency across the whole transmission, plus the loudest single block
        double[] sumOfSquares = new double[HUM_FREQUENCIES.length];
        double[] peak = new double[HUM_FREQUENCIES.length];

        //The same, split by whether the block was speech or a pause, to test whether the hum level
        //is constant or rises when the signal drops
        double[] speechSumOfSquares = new double[HUM_FREQUENCIES.length];
        double[] pauseSumOfSquares = new double[HUM_FREQUENCIES.length];
        int speechBlocks = 0;
        int pauseBlocks = 0;

        double voiceSumOfSquares = 0.0;

        for(BlockMeasurement measurement : mMeasurements)
        {
            boolean pause = measurement.mVoiceRms < pauseCutoff;

            if(pause)
            {
                pauseBlocks++;
            }
            else
            {
                speechBlocks++;
                voiceSumOfSquares += measurement.mVoiceRms * measurement.mVoiceRms;
            }

            for(int f = 0; f < HUM_FREQUENCIES.length; f++)
            {
                double amplitude = measurement.mAmplitudes[f];
                sumOfSquares[f] += amplitude * amplitude;
                peak[f] = Math.max(peak[f], amplitude);

                if(pause)
                {
                    pauseSumOfSquares[f] += amplitude * amplitude;
                }
                else
                {
                    speechSumOfSquares[f] += amplitude * amplitude;
                }
            }
        }

        StringBuilder perFrequency = new StringBuilder();
        int loudest = 0;

        for(int f = 0; f < HUM_FREQUENCIES.length; f++)
        {
            if(sumOfSquares[f] > sumOfSquares[loudest])
            {
                loudest = f;
            }

            if(f > 0)
            {
                perFrequency.append(", ");
            }

            perFrequency.append(String.format("%dHz=%.1f(pk %.1f)", HUM_FREQUENCIES[f],
                    dbfs(rms(sumOfSquares[f], blocks)), dbfs(peak[f])));
        }

        double voiceDb = speechBlocks > 0 ? dbfs(rms(voiceSumOfSquares, speechBlocks)) : Double.NaN;
        double loudestHumDb = dbfs(rms(sumOfSquares[loudest], blocks));

        mLog.debug("[{}] hum analysis over {} ({} blocks): {} dBFS | voice={} dBFS | loudest hum {} Hz at {} dB " +
                        "relative to voice{}",
                mChannelLabel,
                String.format("%.1fs", blocks * blockSeconds),
                blocks,
                perFrequency,
                speechBlocks > 0 ? String.format("%.1f", voiceDb) : "n/a",
                HUM_FREQUENCIES[loudest],
                speechBlocks > 0 ? String.format("%.1f", loudestHumDb - voiceDb) : "n/a",
                speechBlocks > 0 && (loudestHumDb - voiceDb) > AUDIBLE_HUM_TO_VOICE_DB ? " - AUDIBLE" : "");

        //Fundamental against second harmonic, which distinguishes a coupled field or ground loop
        //from power supply ripple
        int sixtyIndex = indexOf(60);
        int oneTwentyIndex = indexOf(120);
        int fiftyIndex = indexOf(50);
        int hundredIndex = indexOf(100);

        double sixty = dbfs(rms(sumOfSquares[sixtyIndex], blocks));
        double oneTwenty = dbfs(rms(sumOfSquares[oneTwentyIndex], blocks));
        double fifty = dbfs(rms(sumOfSquares[fiftyIndex], blocks));
        double hundred = dbfs(rms(sumOfSquares[hundredIndex], blocks));

        mLog.debug("[{}] hum analysis - 60 Hz family {} dBFS fundamental / {} dBFS second harmonic; " +
                        "50 Hz family {} / {}. {}",
                mChannelLabel,
                String.format("%.1f", sixty), String.format("%.1f", oneTwenty),
                String.format("%.1f", fifty), String.format("%.1f", hundred),
                describeSignature(sixty, oneTwenty, fifty, hundred));

        //Speech against pause, which tests whether the level is constant or gain-dependent
        if(speechBlocks > 0 && pauseBlocks > 0)
        {
            double speechHum = dbfs(rms(speechSumOfSquares[loudest], speechBlocks));
            double pauseHum = dbfs(rms(pauseSumOfSquares[loudest], pauseBlocks));
            double difference = pauseHum - speechHum;

            mLog.debug("[{}] hum analysis - {} Hz during speech {} dBFS ({} blocks) vs during pauses {} dBFS " +
                            "({} blocks), difference {} dB. {}",
                    mChannelLabel, HUM_FREQUENCIES[loudest],
                    String.format("%.1f", speechHum), speechBlocks,
                    String.format("%.1f", pauseHum), pauseBlocks,
                    String.format("%+.1f", difference),
                    difference > 3.0
                            ? "Hum rises when speech stops - a gain stage is lifting the noise floor, so check gain "
                                    + "and AGC settings as well as filtering"
                            : "Hum level is constant - ordinary additive hum, which a filter will remove");
        }
        else
        {
            mLog.debug("[{}] hum analysis - transmission had no usable speech/pause split ({} speech, {} pause " +
                    "blocks), so the constant-versus-gain-dependent test was not run",
                    mChannelLabel, speechBlocks, pauseBlocks);
        }
    }

    /**
     * Characterizes the mains signature from the relative strength of fundamentals and second
     * harmonics.  Deliberately non-committal: this names what the numbers are consistent with, not
     * what is certainly true.
     */
    private static String describeSignature(double sixty, double oneTwenty, double fifty, double hundred)
    {
        double sixtyFamily = Math.max(sixty, oneTwenty);
        double fiftyFamily = Math.max(fifty, hundred);

        if(sixtyFamily < fiftyFamily - 3.0)
        {
            return "Energy sits in the 50 Hz family on a 60 Hz mains region - this is unlikely to be mains hum and "
                    + "warrants looking elsewhere before any filter is fitted.";
        }

        if(oneTwenty > sixty + 3.0)
        {
            return "Second harmonic dominates, which is the signature of full-wave rectifier ripple - consistent "
                    + "with a power supply or USB hub, and a notch at 60 Hz alone would not help.";
        }

        if(sixty > oneTwenty + 3.0)
        {
            return "Fundamental dominates, which is more consistent with a coupled magnetic field or a ground loop "
                    + "than with supply ripple.";
        }

        return "Fundamental and second harmonic are comparable.";
    }

    /**
     * Returns the index of a frequency within {@link #HUM_FREQUENCIES}.
     */
    private static int indexOf(int frequency)
    {
        for(int x = 0; x < HUM_FREQUENCIES.length; x++)
        {
            if(HUM_FREQUENCIES[x] == frequency)
            {
                return x;
            }
        }

        throw new IllegalArgumentException("Frequency [" + frequency + "] is not analyzed");
    }

    /**
     * Root mean square from a sum of squares and a count.
     */
    private static double rms(double sumOfSquares, int count)
    {
        return count > 0 ? Math.sqrt(sumOfSquares / count) : 0.0;
    }

    /**
     * Converts an amplitude to dB relative to full scale, floored so silence does not produce
     * negative infinity.
     */
    private static double dbfs(double amplitude)
    {
        return 20.0 * Math.log10(Math.max(amplitude, MIN_AMPLITUDE));
    }

    /**
     * One block's measurements.
     */
    private static class BlockMeasurement
    {
        private final double[] mAmplitudes;
        private final double mVoiceRms;

        BlockMeasurement(double[] amplitudes, double voiceRms)
        {
            mAmplitudes = amplitudes;
            mVoiceRms = voiceRms;
        }
    }
}
