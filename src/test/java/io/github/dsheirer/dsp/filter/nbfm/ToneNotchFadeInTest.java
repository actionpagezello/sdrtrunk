package io.github.dsheirer.dsp.filter.nbfm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ap-19: the tone notch settling at the start of a transmission is faded in, so the newly keyed CTCSS tone doesn't
 * blip through (Denmark, Bridgton Police 225.7 Hz).
 */
public class ToneNotchFadeInTest
{
    @Test
    public void notchSettlingAtTheStartIsFadedIn()
    {
        NBFMAudioFilters filters = new NBFMAudioFilters(8000);
        filters.setToneNotch(new double[]{225.7}, 8000, NBFMAudioFilters.DEFAULT_TONE_NOTCH_Q);
        filters.reset();
        double faded = 0;

        for(int x = 0; x < 800; x++)
        {
            float out = filters.process((float)(0.3 * Math.sin(2 * Math.PI * 225.7 * x / 8000.0)));
            faded += out * out;
        }

        //The same tone onset without the fade: process with the fade already used up on silence
        NBFMAudioFilters cold = new NBFMAudioFilters(8000);
        cold.setToneNotch(new double[]{225.7}, 8000, NBFMAudioFilters.DEFAULT_TONE_NOTCH_Q);
        cold.reset();

        for(int x = 0; x < 2000; x++)
        {
            cold.process(0.0f);
        }

        double unfaded = 0;

        for(int x = 0; x < 800; x++)
        {
            float out = cold.process((float)(0.3 * Math.sin(2 * Math.PI * 225.7 * x / 8000.0)));
            unfaded += out * out;
        }

        assertTrue(faded < unfaded / 10, "faded " + faded + " unfaded " + unfaded);
    }
}
