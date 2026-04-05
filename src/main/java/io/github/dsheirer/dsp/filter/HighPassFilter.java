package io.github.dsheirer.dsp.filter;

/**
 * Infinite Impulse Response (IIR) High-Pass Biquad Filter.
 * Useful for removing sub-audible tones (CTCSS/DCS) or low-frequency rumble.
 */
public class HighPassFilter {
    private float mB0, mB1, mB2, mA1, mA2;
    private float mX1 = 0, mX2 = 0;
    private float mY1 = 0, mY2 = 0;

    /**
     * Creates a new High Pass Filter
     * @param sampleRate audio sample rate in Hz (e.g. 48000)
     * @param cutoffFrequency cutoff frequency in Hz (e.g. 300)
     * @param q filter Q-factor (0.707 is typically flat/Butterworth)
     */
    public HighPassFilter(float sampleRate, float cutoffFrequency, float q) {
        float omega = (float) (2.0 * Math.PI * cutoffFrequency / sampleRate);
        float alpha = (float) (Math.sin(omega) / (2.0 * q));
        float cosOmega = (float) Math.cos(omega);

        float a0 = 1.0f + alpha;
        
        // Calculate and normalize coefficients
        mB0 = ((1.0f + cosOmega) / 2.0f) / a0;
        mB1 = (-(1.0f + cosOmega)) / a0;
        mB2 = ((1.0f + cosOmega) / 2.0f) / a0;
        mA1 = (-2.0f * cosOmega) / a0;
        mA2 = (1.0f - alpha) / a0;
    }

    /**
     * Applies the filter in-place to an array of audio samples
     */
    public void filter(float[] samples) {
        for (int i = 0; i < samples.length; i++) {
            float x = samples[i];
            float y = mB0 * x + mB1 * mX1 + mB2 * mX2 - mA1 * mY1 - mA2 * mY2;

            mX2 = mX1;
            mX1 = x;
            mY2 = mY1;
            mY1 = y;

            samples[i] = y;
        }
    }
    
    public void reset() {
        mX1 = 0; mX2 = 0;
        mY1 = 0; mY2 = 0;
    }
}
