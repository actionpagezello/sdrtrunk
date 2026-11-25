package io.github.dsheirer.module.decode.nbfm;

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.decode.config.ChannelToneFilter;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ap-22: the TDMA veto ends a call that is already open only when the latch sees a second detection window, and a
 * closed gate is not reopened against a held veto.
 *
 * Fleet 2026-11-24: Somerville 22, Daly 17 and Denmark 4 voice calls were ended in mid-sentence as "DMR/TDMA
 * interference", each followed by five or six empty calls 50-100 ms apart. The Denmark (Oxford County Fire 154.070)
 * and Daly (Lawrence Fire 154.445) basebands of 2026-11-25 show clean speech at the cut: speech meets the detection
 * rule for a single 300 ms window about once in 20 to 50 minutes of talk.
 */
public class TdmaOpenCallVetoTest
{
    private static final double SAMPLE_RATE = 25_000.0;

    /**
     * Slot-gated noise, the test signal of TdmaToneRejectionTest
     */
    private static float tdma(Random random, int index, double duty)
    {
        double slot = (index / 8000.0 % 0.030) / 0.030;
        return (float)(random.nextGaussian() * (slot < duty ? 0.5 : 0.08));
    }

    @Test
    public void continuousSlotCadenceBecomesSustained()
    {
        for(double duty : new double[]{0.5, 0.8})
        {
            Random random = new Random(3);
            TdmaInterferenceDetector detector = new TdmaInterferenceDetector(8000f);
            float[] buffer = new float[160];
            int firstSustained = -1;

            for(int start = 0; start < 8000 * 3; start += buffer.length)
            {
                for(int k = 0; k < buffer.length; k++)
                {
                    buffer[k] = tdma(random, start + k, duty);
                }

                detector.process(buffer);

                if(firstSustained < 0 && detector.isInterferenceSustained())
                {
                    firstSustained = start + buffer.length;
                }
            }

            assertTrue(firstSustained > 0 && firstSustained <= 8000, "duty " + duty + ": sustained after " +
                firstSustained + " samples");
        }
    }

    @Test
    public void oneWindowOfSlotCadenceIsNotSustained()
    {
        //A burst too short to fill a second window, at every alignment against the detector's 150 ms hop
        for(int offset = 0; offset < 1200; offset += 40)
        {
            Random random = new Random(3);
            TdmaInterferenceDetector detector = new TdmaInterferenceDetector(8000f);
            float[] buffer = new float[16];
            int burstStart = 16000 + offset;
            int burstEnd = burstStart + 1600;

            for(int start = 0; start < 8000 * 4; start += buffer.length)
            {
                for(int k = 0; k < buffer.length; k++)
                {
                    int i = start + k;
                    buffer[k] = i >= burstStart && i < burstEnd ? tdma(random, i, 0.5) :
                        (float)(0.5 * Math.sin(2 * Math.PI * 768 * i / 8000.0) +
                            0.1 * Math.sin(2 * Math.PI * 110.9 * i / 8000.0) + 0.005 * random.nextGaussian());
                }

                detector.process(buffer);
                assertFalse(detector.isInterferenceSustained(), "offset " + offset + " at sample " + start);
            }
        }
    }

    /**
     * A transmission with CTCSS 110.9 and a 768 Hz tone from 1.0 s to 9.0 s. Slot-gated noise replaces the 768 Hz
     * tone for the burst; the CTCSS tone stays, as it does when a digital signal lands on a call in progress.
     *
     * @return call START and END events in order
     */
    private static List<String> run(double burstStart, double burstSeconds)
    {
        DecodeConfigNBFM config = new DecodeConfigNBFM();
        config.setToneFilterEnabled(true);
        config.addToneFilter(new ChannelToneFilter(ChannelToneFilter.ToneType.CTCSS, "TONE_2Z", "110.9"));
        NBFMDecoder decoder = new NBFMDecoder(config);
        List<String> events = new ArrayList<>();
        decoder.setDecoderStateListener(event -> {
            if(event.getState() == State.CALL && (event.getEvent() == DecoderStateEvent.Event.START ||
                event.getEvent() == DecoderStateEvent.Event.END))
            {
                events.add(event.getEvent().name());
            }
        });
        decoder.setBufferListener(buffer -> {});
        decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(SAMPLE_RATE));

        Random random = new Random(5);
        double phase = 0;
        int block = 2500;

        for(int start = 0; start < (int)(10.5 * SAMPLE_RATE); start += block)
        {
            float[] i = new float[block];
            float[] q = new float[block];

            for(int k = 0; k < block; k++)
            {
                double t = (start + k) / SAMPLE_RATE;

                if(t >= 1.0 && t < 9.0)
                {
                    double deviation = 500 * Math.sin(2 * Math.PI * 110.9 * t);

                    if(t >= burstStart && t < burstStart + burstSeconds)
                    {
                        deviation += random.nextGaussian() * ((t % 0.030) / 0.030 < 0.5 ? 2500 : 300);
                    }
                    else
                    {
                        deviation += 2500 * Math.sin(2 * Math.PI * 768 * t);
                    }

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

        return events;
    }

    @Test
    public void shortBurstDoesNotEndTheOpenCall()
    {
        //ap-21 ended the call for the 0.2 s burst at 4.1 s and the 0.45 s bursts at 4.05 and 4.1 s
        for(double seconds : new double[]{0.2, 0.3, 0.45})
        {
            for(double start : new double[]{4.0, 4.05, 4.1})
            {
                List<String> events = run(start, seconds);
                assertEquals(List.of("START", "END"), events, seconds + " s burst at " + start + " s: " + events);
            }
        }
    }

    @Test
    public void lastingBurstEndsTheCallOnceAndTheCallResumesOnce()
    {
        //ap-21: START, END, then 6 to 25 empty START/END pairs while the latch was held, then the resumed call
        for(double start : new double[]{4.0, 4.05, 4.1})
        {
            List<String> events = run(start, 2.0);
            assertEquals(List.of("START", "END", "START", "END"), events, "2 s burst at " + start + " s: " + events);
        }
    }
}
