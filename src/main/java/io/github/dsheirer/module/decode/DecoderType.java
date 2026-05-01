/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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
package io.github.dsheirer.module.decode;

import io.github.dsheirer.protocol.Protocol;
import java.util.EnumSet;

/**
 * Enumeration of decoder types
 */
public enum DecoderType
{
    //Primary Decoders
    AM("AM", "AM", Protocol.UNKNOWN, "AM.svg"),
    DMR("DMR","DMR", Protocol.DMR, "DMR.svg"),
    LTR("LTR", "LTR", Protocol.LTR, "LTR.svg"),
    LTR_NET("LTR-Net", "LTR-Net", Protocol.LTR_NET, "ltr-net.svg"),
    MPT1327("MPT1327", "MPT1327", Protocol.MPT1327, "mpt1327.svg"),
    NBFM("NBFM", "NBFM", Protocol.NBFM, "NBFM.svg"),
    PASSPORT("Passport", "Passport", Protocol.PASSPORT, "Passport.svg"),
    P25_PHASE1("P25 Phase 1", "P25-1", Protocol.APCO25, "P25-Phase1.svg"),
    P25_PHASE2("P25 Phase 2", "P25-2", Protocol.APCO25_PHASE2, "P25-Phase2.svg"),

    //Auxiliary Decoders
    CTCSS("Continuous Tone-Coded Squelch (CTCSS)", "CTCSS", Protocol.CTCSS),
    DCS("Digital Coded Squelch (DCS)", "DCS", Protocol.DCS),
    FLEETSYNC2("Fleetsync II", "Fleetsync2", Protocol.FLEETSYNC),
    LJ_1200("LJ1200 173.075", "LJ1200", Protocol.LOJACK),
    MDC1200("MDC1200", "MDC1200", Protocol.MDC1200),
    TAIT_1200("Tait 1200", "Tait 1200", Protocol.TAIT1200);

    private String mDisplayString;
    private String mShortDisplayString;
    private Protocol mProtocol;
    private String mIconName;

    DecoderType(String displayString, String shortDisplayString, Protocol protocol)
    {
        this(displayString, shortDisplayString, protocol, null);
    }

    DecoderType(String displayString, String shortDisplayString, Protocol protocol, String iconName)
    {
        mDisplayString = displayString;
        mShortDisplayString = shortDisplayString;
        mProtocol = protocol;
        mIconName = iconName;
    }

    /**
     * Primary decoders that operate on I/Q sample streams
     */
    public static EnumSet<DecoderType> PRIMARY_DECODERS =
        EnumSet.of(DecoderType.AM,
        DecoderType.DMR,
        DecoderType.LTR,
        DecoderType.LTR_NET,
        DecoderType.MPT1327,
        DecoderType.NBFM,
        DecoderType.P25_PHASE1,
        DecoderType.P25_PHASE2,
        DecoderType.PASSPORT);

    /**
     * Auxiliary decoders that operate on in-band signalling in the decoded audio channel
     */
    public static final EnumSet<DecoderType> AUX_DECODERS =
        EnumSet.of(DecoderType.CTCSS,
        DecoderType.DCS,
        DecoderType.FLEETSYNC2,
        DecoderType.LJ_1200,
        DecoderType.MDC1200,
        DecoderType.TAIT_1200);

    /**
     * Decoders that produce a (recordable) bitstream
     */
    public static final EnumSet<DecoderType> BITSTREAM_DECODERS = EnumSet.of(DecoderType.DMR,
        DecoderType.MPT1327, DecoderType.P25_PHASE1, DecoderType.P25_PHASE2);

    /**
     * Decoders that produce (recordable) MBE audio codec frames
     */
    public static final EnumSet<DecoderType> MBE_AUDIO_CODEC_DECODERS =
        EnumSet.of(DecoderType.DMR, DecoderType.P25_PHASE1, DecoderType.P25_PHASE2);

    public Protocol getProtocol()
    {
        return mProtocol;
    }

    public String getDisplayString()
    {
        return mDisplayString;
    }

    public String getShortDisplayString()
    {
        return mShortDisplayString;
    }

    public String getIconName()
    {
        return mIconName;
    }

    @Override
    public String toString()
    {
        return mDisplayString;
    }

    /**
     * Indicates if the decoder type produces a (recordable) bitstream
     */
    public boolean providesBitstream()
    {
        return BITSTREAM_DECODERS.contains(this);
    }

    /**
     * Indicates if the decoder type produces (recordable) MBE audio codec frames
     */
    public boolean providesMBEAudioFrames()
    {
        return MBE_AUDIO_CODEC_DECODERS.contains(this);
    }
}
