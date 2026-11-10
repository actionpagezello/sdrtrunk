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

package io.github.dsheirer.module.decode.edacs.provoice;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.filter.equalizer.GraphicEqualizer;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;

/**
 * AP-fork (EDACS ProVoice): plays ProVoice voice frames through the JMBE IMBE codec.  ProVoice uses the IMBE vocoder;
 * the decoder hands over each voice frame re-packed as a P25 IMBE frame.
 */
public class ProVoiceAudioModule extends ImbeAudioModule
{
    private final NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private final Listener<SquelchStateEvent> mSquelchStateListener = this::squelch;
    private volatile GraphicEqualizer mGraphicEQ;
    private long mFrameCount;

    public ProVoiceAudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList);
    }

    /**
     * Graphic equalizer for the decoded audio, or null for none
     */
    public void setGraphicEqualizer(GraphicEqualizer equalizer)
    {
        mGraphicEQ = equalizer;
    }

    @Override
    public void receive(IMessage message)
    {
        if(message instanceof ProVoiceMessage voice && hasAudioCodec())
        {
            for(byte[] frame : voice.getIMBEFrames())
            {
                float[] audio = getAudioCodec().getAudio(frame);

                //JMBE 2.x applies its own AGC - a fixed gain on top of it would clip
                if(!hasLibraryGainControl())
                {
                    audio = mGain.apply(audio);
                }

                GraphicEqualizer equalizer = mGraphicEQ;

                if(equalizer != null && equalizer.isEnabled())
                {
                    audio = equalizer.process(audio);
                }

                addAudio(audio);
                mFrameCount++;
            }
        }
    }

    /**
     * IMBE voice frames played (20 ms each)
     */
    public long getFrameCount()
    {
        return mFrameCount;
    }

    private void squelch(SquelchStateEvent event)
    {
        if(event.getSquelchState() == SquelchState.SQUELCH)
        {
            closeAudioSegment();
        }
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
    }
}
