package io.github.dsheirer.module.decode.nbfm;

import java.util.Random;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ap-17: steady paging tones no longer trip the TDMA interference detector (Denmark 2026-11-22, Oxford County Fire:
 * a 768 Hz A tone closed the tone gate and split a page), while TDMA-like bursts still do, as often as before.
 */
public class TdmaToneRejectionTest
{
    /**
     * @return buffers with interference detected, of the buffers processed
     */
    private static int detected(IntToDoubleFunction signal, double seconds)
    {
        TdmaInterferenceDetector detector = new TdmaInterferenceDetector(8000f);
        int count = 0;
        float[] buffer = new float[160];

        for(int start = 0; start < (int)(8000 * seconds); start += buffer.length)
        {
            for(int k = 0; k < buffer.length; k++)
            {
                buffer[k] = (float)signal.applyAsDouble(start + k);
            }

            detector.process(buffer);

            if(detector.isInterferenceDetected() || detector.isSlotCadenceSuspected())
            {
                count++;
            }
        }

        return count;
    }

    @Test
    public void steadyTonesWithCtcssAreNotTdma()
    {
        Random random = new Random(11);

        //Includes the tones that tripped ap-16 (483.3, 768, 766.7) and a sweep across the paging tone range
        for(double frequency = 280.0; frequency <= 2200.0; frequency += 3.7)
        {
            double f = frequency;
            int count = detected(i -> 0.5 * Math.sin(2 * Math.PI * f * i / 8000.0) +
                0.1 * Math.sin(2 * Math.PI * 110.9 * i / 8000.0) + 0.005 * random.nextGaussian(), 1.5);
            assertEquals(0, count, "tone " + String.format("%.1f", f) + " Hz");
        }

        for(double f : new double[]{483.3, 516.7, 733.3, 766.7, 768.0, 1016.7, 1266.7})
        {
            assertEquals(0, detected(i -> 0.5 * Math.sin(2 * Math.PI * f * i / 8000.0) +
                0.1 * Math.sin(2 * Math.PI * 110.9 * i / 8000.0), 4.0), "tone " + f + " Hz");
        }
    }

    @Test
    public void tdmaLikeBurstsAreStillDetected()
    {
        for(double duty : new double[]{0.5, 0.8, 0.92})
        {
            Random random = new Random(3);
            int count = detected(i -> {
                double slot = (i / 8000.0 % 0.030) / 0.030;
                return random.nextGaussian() * (slot < duty ? 0.5 : 0.08);
            }, 4.0);
            //ap-16 detected 186, 186 and 148 of 200 buffers
            assertTrue(count >= (duty > 0.9 ? 140 : 180), "duty " + duty + ": " + count + " of 200");
        }
    }
}
