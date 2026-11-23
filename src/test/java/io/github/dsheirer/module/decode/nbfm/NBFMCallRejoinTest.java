package io.github.dsheirer.module.decode.nbfm;

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.module.decode.config.ChannelToneFilter;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ap-17: a tone-filtered NBFM transmission whose CTCSS tone drops out briefly while the carrier stays up (Denmark,
 * Oxford County Fire 2026-11-22: a two-tone page split into two calls) stays one call, and the audio in between is
 * released into it.
 */
public class NBFMCallRejoinTest
{
    private static final double SAMPLE_RATE = 25_000.0;

    /**
     * Runs a transmission: noise, carrier with CTCSS and a 768 Hz tone, a CTCSS dropout of the given length with the
     * carrier and 768 Hz tone still up, then the carrier drops.
     * @return the call START and END events, in order, and the audio sample count delivered
     */
    private static Result run(double dropoutSeconds)
    {
        return run(dropoutSeconds, false);
    }

    /**
     * @param carrierGap true: the carrier itself drops for the dropout (ap-18, 0.5 s window), false: only the CTCSS
     * tone drops while the carrier stays up (1.5 s window)
     */
    private static Result run(double dropoutSeconds, boolean carrierGap)
    {
        DecodeConfigNBFM config = new DecodeConfigNBFM();
        config.setToneFilterEnabled(true);
        config.addToneFilter(new ChannelToneFilter(ChannelToneFilter.ToneType.CTCSS, "TONE_2Z", "110.9"));
        NBFMDecoder decoder = new NBFMDecoder(config);
        List<String> events = new ArrayList<>();
        int[] audio = {0};
        decoder.setDecoderStateListener(event -> {
            if(event.getState() == State.CALL && (event.getEvent() == DecoderStateEvent.Event.START ||
                event.getEvent() == DecoderStateEvent.Event.END))
            {
                events.add(event.getEvent().name());
            }
        });
        decoder.setBufferListener(buffer -> audio[0] += buffer.length);
        decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(SAMPLE_RATE));

        Random random = new Random(7);
        double phase = 0;
        double dropStart = 2.5;
        double dropEnd = dropStart + dropoutSeconds;
        double carrierOn = 1.0;
        double carrierOff = dropEnd + 2.5;
        double total = carrierOff + 2.0;
        int block = 2500;

        for(int start = 0; start < (int)(total * SAMPLE_RATE); start += block)
        {
            float[] i = new float[block];
            float[] q = new float[block];

            for(int k = 0; k < block; k++)
            {
                double t = (start + k) / SAMPLE_RATE;

                boolean gap = carrierGap && t >= dropStart && t < dropEnd;

                if(t >= carrierOn && t < carrierOff && !gap)
                {
                    boolean ctcss = carrierGap || t < dropStart || t >= dropEnd;
                    double deviation = (ctcss ? 500 * Math.sin(2 * Math.PI * 110.9 * t) : 0) +
                        2500 * Math.sin(2 * Math.PI * 768 * t);
                    phase += 2 * Math.PI * deviation / SAMPLE_RATE;
                    i[k] = (float)(0.5 * Math.cos(phase) + 0.002 * random.nextGaussian());
                    q[k] = (float)(0.5 * Math.sin(phase) + 0.002 * random.nextGaussian());
                }
                else
                {
                    i[k] = (float)(0.05 * random.nextGaussian());
                    q[k] = (float)(0.05 * random.nextGaussian());
                }
            }

            decoder.receive(new ComplexSamples(i, q, 0));
        }

        return new Result(events, audio[0]);
    }

    record Result(List<String> events, int audioSamples) {}

    @Test
    public void shortCarrierDropStaysOneCall()
    {
        Result result = run(0.3, true);
        assertEquals(List.of("START", "END"), result.events(), "events " + result.events());
    }

    @Test
    public void carrierDropLongerThanHalfASecondEndsTheCall()
    {
        //ap-18: a carrier drop waits 0.5 s, not the 1.5 s of a tone loss with the carrier up
        Result result = run(0.9, true);
        assertEquals(List.of("START", "END", "START", "END"), result.events(), "events " + result.events());
    }

    @Test
    public void briefToneDropoutWithTheCarrierUpStaysOneCall()
    {
        Result result = run(0.8);
        assertEquals(List.of("START", "END"), result.events(), "events " + result.events());
    }

    @Test
    public void toneDropoutLongerThanTheWindowEndsTheCall()
    {
        Result result = run(4.0);
        assertTrue(result.events().size() >= 4 && result.events().get(1).equals("END"),
            "events " + result.events());
    }

    @Test
    public void resumedCallKeepsTheAudioInBetween()
    {
        //No dropout: the whole carrier with tone; the dropout run should deliver nearly the same amount of audio
        Result clean = run(0.0);
        Result dropout = run(0.8);
        double cleanSeconds = clean.audioSamples() / 8000.0;
        double dropoutSeconds = dropout.audioSamples() / 8000.0;
        //The dropout run's carrier is 0.8 s longer
        assertEquals(cleanSeconds + 0.8, dropoutSeconds, 0.1, "clean " + cleanSeconds + "s, dropout " +
            dropoutSeconds + "s");
    }
}
