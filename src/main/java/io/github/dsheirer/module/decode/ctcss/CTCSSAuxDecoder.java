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

package io.github.dsheirer.module.decode.ctcss;

import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nbfm.CTCSSDetector;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.real.IRealBufferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuous Tone-Coded Squelch System (CTCSS) auxiliary decoder.
 * Wraps the Goertzel-based CTCSSDetector for use as a standalone auxiliary decoder module.
 * Operates on 8 kHz demodulated audio and produces CTCSSMessage objects when tones are detected.
 */
public class CTCSSAuxDecoder extends Decoder implements IRealBufferListener, Listener<float[]>
{
    private static final Logger mLog = LoggerFactory.getLogger(CTCSSAuxDecoder.class);
    private static final float SAMPLE_RATE = 8000.0f;

    private final CTCSSDetector mDetector;
    private CTCSSCode mLastReportedCode = null;

    /**
     * Constructs an instance that scans all standard CTCSS tones
     */
    public CTCSSAuxDecoder()
    {
        mDetector = new CTCSSDetector(CTCSSCode.STANDARD_CODES, SAMPLE_RATE);
        mDetector.setListener(new CTCSSDetector.CTCSSDetectorListener()
        {
            @Override
            public void ctcssDetected(CTCSSCode code)
            {
                if(getMessageListener() != null && code != null)
                {
                    // Only emit a new message when the code changes or on initial detection
                    if(mLastReportedCode != code)
                    {
                        mLastReportedCode = code;
                        getMessageListener().receive(new CTCSSMessage(code, System.currentTimeMillis()));
                    }
                }
            }

            @Override
            public void ctcssRejected(CTCSSCode code)
            {
                // Auxiliary decoder reports all detected tones, no filtering
                // This callback is for the channel-level filter; not used here
            }

            @Override
            public void ctcssLost()
            {
                mLastReportedCode = null;
            }
        });
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.CTCSS;
    }

    @Override
    public Listener<float[]> getBufferListener()
    {
        return this::receive;
    }

    /**
     * Processes demodulated 8 kHz audio samples to detect CTCSS tones.
     * @param samples to analyze
     */
    public void receive(float[] samples)
    {
        if(getMessageListener() != null)
        {
            mDetector.process(samples);
        }
    }
}
