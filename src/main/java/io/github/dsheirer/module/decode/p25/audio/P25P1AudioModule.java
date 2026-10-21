/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.module.decode.p25.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.filter.equalizer.GraphicEqualizer;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.module.decode.p25.reference.Encryption;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P25P1AudioModule extends ImbeAudioModule
{
    private final static Logger mLog = LoggerFactory.getLogger(P25P1AudioModule.class);
    private boolean mEncryptedCall = false;
    private boolean mEncryptedCallStateEstablished = false;

    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private volatile GraphicEqualizer mGraphicEQ;
    private List<LDUMessage> mCachedLDUMessages = new ArrayList<>();

    /**
     * AP-fork: algorithm IDs already reported by warnIfUnknownAlgorithm(), so a fully encrypted talkgroup
     * logs one line rather than one per call.  Touched only from the message-processing thread.
     */
    private final Set<Integer> mReportedUnknownAlgorithms = new HashSet<>();

    /**
     * AP-fork: milliseconds without IMBE audio after which a call is closed here rather than waiting for the channel
     * state to end it.  A P25 Phase 1 call ends when the channel state leaves CALL, which needs a decoded HDU, LDU1
     * link control or LDU2 encryption sync to have put it there.  This module decodes voice from LDUs regardless, so
     * a weak transmission whose voice survives but whose signalling does not opens a segment that nothing closes
     * until a later call on the channel ends - and that later call's audio goes into it.
     *
     * Somerville 2026-10-19..21: 58 calls closed 20 s to 9.4 h after their audio ended (Medford, Watertown, Chelsea,
     * Winthrop, Revere, Arlington, Malden Police - all P25 Phase 1 conventional).  Audubon 2026-10-20: 14, including
     * Woburn Police open from 22:34 to 06:51 with every transmission of the night merged into one 321 s recording.
     * Medford 10-20 11:32:45: a 0.5 s fragment held its Zello stream open until the watchdog stopped it, then took the
     * first second of the next transmission, which the Zello vault does not have.
     *
     * Voice arrives every 180 ms while a radio is keyed, and a normal call is closed by the 1.2 s channel fade, so
     * 2 s without audio (plus any configured audio hangtime) only fires on calls the channel state did not end.
     */
    private static final long IDLE_CLOSE_MS = 2000;

    /**
     * AP-fork: true while the channel state has this call open (UNSQUELCH seen, SQUELCH not yet), for the idle-close
     * log line.  Written by the squelch listener, read by the idle close.
     */
    private volatile boolean mCallStateOpen = false;

    /**
     * AP-fork: set by the idle close, consumed on the message-processing thread.  The call-end that normally resets
     * the encryption state and the LDU cache never came, so the next transmission must establish its own.
     */
    private volatile boolean mResetCallStatePending = false;

    public P25P1AudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList);
        setIdleCloseMs(IDLE_CLOSE_MS);
    }

    /**
     * AP-fork: logs a call closed for lack of audio and asks the message thread to reset the per-call state.
     */
    @Override
    protected void onIdleClose(AudioSegment segment, long openMs, long idleMs, long audioMs)
    {
        mResetCallStatePending = true;

        Identifier channel = null;
        Identifier to = null;

        try
        {
            channel = getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY);
            to = segment.getIdentifierCollection() != null ? segment.getIdentifierCollection().getToIdentifier() : null;
        }
        catch(Exception e)
        {
            //Identifiers are for the log line only
        }

        mLog.info("P25 Phase 1 call on [{}] to [{}] closed after {} ms without audio - {} - open {} ms with {} ms of " +
                "audio", channel, to, idleMs, mCallStateOpen ? "the channel had the call open but had not ended it" :
                "the channel never registered the call (no decoded header, link control or encryption sync)",
                openMs, audioMs);
    }

    /**
     * Configures the 10-band graphic equalizer for this audio module.
     *
     * @param enabled true to enable the EQ
     * @param bandGains array of 10 gain values in dB (-12 to +12)
     */
    public void setGraphicEQ(boolean enabled, double[] bandGains)
    {
        // IMBE audio is decoded at 8 kHz
        mGraphicEQ = new GraphicEqualizer(8000.0);
        mGraphicEQ.setEnabled(enabled);

        if(bandGains != null)
        {
            mGraphicEQ.setBandGains(bandGains);
        }

        mLog.debug("P25P1AudioModule graphic EQ configured: enabled={} gains={}",
            enabled, bandGains != null ? java.util.Arrays.toString(bandGains) : "null");
    }

    @Override
    protected int getTimeslot()
    {
        return 0;
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

    /**
     * Processes call header (HDU) and voice frame (LDU1/LDU2) messages to decode audio and to determine the
     * encrypted audio status of a call event. Only the HDU and LDU2 messages convey encrypted call status. If an
     * LDU1 message is received without a preceding HDU message, then the LDU1 message is cached until the first
     * LDU2 message is received and the encryption state can be determined. Both the LDU1 and the LDU2 message are
     * then processed for audio if the call is unencrypted.
     */
    public void receive(IMessage message)
    {
        if(mResetCallStatePending)
        {
            mResetCallStatePending = false;
            mEncryptedCallStateEstablished = false;
            mEncryptedCall = false;
            mCachedLDUMessages.clear();
        }

        if(hasAudioCodec())
        {
            if(mEncryptedCallStateEstablished)
            {
                if(message instanceof LDUMessage ldu)
                {
                    processAudio(ldu);
                }
            }
            else
            {
                if(message instanceof HDUMessage hdu && hdu.isValid())
                {
                    mEncryptedCallStateEstablished = true;
                    mEncryptedCall = hdu.getHeaderData().isEncryptedAudio();
                    warnIfUnknownAlgorithm(hdu.getHeaderData().getEncryption(),
                        hdu.getHeaderData().getAlgorithmId(), "HDU");
                }
                else if(message instanceof LDU1Message ldu1)
                {
                    //When we receive an LDU1 message without first receiving the HDU message, cache the LDU1 Message
                    //until we can determine the encrypted call state from the next LDU2 message
                    mCachedLDUMessages.add(ldu1);
                }
                else if(message instanceof LDU2Message ldu2)
                {
                    if(ldu2.getEncryptionSyncParameters().isValid())
                    {
                        mEncryptedCallStateEstablished = true;
                        mEncryptedCall = ldu2.getEncryptionSyncParameters().isEncryptedAudio();
                        warnIfUnknownAlgorithm(
                            Encryption.fromValue(ldu2.getEncryptionSyncParameters().getAlgorithmId()),
                            ldu2.getEncryptionSyncParameters().getAlgorithmId(), "LDU2");
                    }

                    if(mEncryptedCallStateEstablished)
                    {
                        for(LDUMessage cachedLdu : mCachedLDUMessages)
                        {
                            processAudio(cachedLdu);
                        }

                        mCachedLDUMessages.clear();
                        processAudio(ldu2);
                    }
                    else
                    {
                        mCachedLDUMessages.add(ldu2);
                    }
                }
            }
        }
    }

    /**
     * AP-fork: reports an algorithm ID that does not map onto a known {@link Encryption} entry.
     *
     * Encryption.fromValue() collapses every unrecognised 8-bit algorithm ID to Encryption.UNKNOWN, and
     * isEncryptedAudio() treats UNKNOWN as encrypted, so processAudio() drops every IMBE frame for the call.
     * Muting on an ID we cannot identify is the right default - decoding an actually-encrypted call would
     * produce noise - but until now the mute left no trace at all: a channel would simply go silent with
     * nothing in the log saying why, and there was no way to tell a genuinely encrypted talkgroup from a
     * mis-decoded header.
     *
     * The two cases look different in the log.  A real encrypted system repeats one ID, call after call, on
     * the same talkgroups.  A decode error produces scattered one-off values - and a talkgroup that reports
     * several different unknown IDs is almost certainly a marginal signal rather than an encrypted one.
     *
     * Logged at WARN once per distinct algorithm ID per audio module, so a fully encrypted talkgroup costs
     * one line rather than one per call.
     *
     * @param encryption as resolved from the algorithm ID
     * @param algorithmId raw 8-bit value carried in the message
     * @param source message type the value came from, for the log line
     */
    private void warnIfUnknownAlgorithm(Encryption encryption, int algorithmId, String source)
    {
        if(encryption == Encryption.UNKNOWN && mReportedUnknownAlgorithms.add(algorithmId))
        {
            mLog.warn("P25 Phase 1 {} carries unknown encryption algorithm ID {} (0x{}) - audio is muted for " +
                "this call because an unrecognised algorithm is assumed encrypted.  A repeating ID on the same " +
                "talkgroup is a real encrypted system; scattered one-off IDs indicate a marginal signal and a " +
                "mis-decoded header. Talkgroup(s): {}", source, algorithmId,
                String.format("%02X", algorithmId & 0xFF), getIdentifierCollection().getIdentifiers());
        }
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private void processAudio(LDUMessage ldu)
    {
        if(!mEncryptedCall)
        {
            for(byte[] frame : ldu.getIMBEFrames())
            {
                float[] audio = getAudioCodec().getAudio(frame);

                //JMBE 2.x applies its own AGC - the legacy 5x gain on top of it clips (upstream #2489)
                if(!hasLibraryGainControl())
                {
                    audio = mGain.apply(audio);
                }

                GraphicEqualizer eq = mGraphicEQ;
                if(eq != null && eq.isEnabled())
                {
                    eq.process(audio);
                }

                addAudio(audio);
            }
        }
        else
        {
            //Encrypted audio processing not implemented
        }
    }

    /**
     * Wrapper for squelch state to process end of call actions.  At call end the encrypted call state established
     * flag is reset so that the encrypted audio state for the next call can be properly detected and we send an
     * END audio packet so that downstream processors like the audio recorder can properly close out a call sequence.
     */
    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            if(event.getSquelchState() == SquelchState.UNSQUELCH)
            {
                mCallStateOpen = true;
            }
            else if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                mCallStateOpen = false;
                closeAudioSegment();
                mEncryptedCallStateEstablished = false;
                mEncryptedCall = false;
                mCachedLDUMessages.clear();
            }
        }
    }
}