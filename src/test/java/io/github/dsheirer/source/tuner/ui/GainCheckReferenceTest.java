package io.github.dsheirer.source.tuner.ui;

import io.github.dsheirer.module.decode.DecoderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ap-16: only trunking control channels are used as the Gain Check reference.
 */
public class GainCheckReferenceTest
{
    @Test
    public void trunkingOnlyDecodersAreControlChannels()
    {
        assertTrue(GainCheckReference.isControlChannel(DecoderType.EDACS, null));
        assertTrue(GainCheckReference.isControlChannel(DecoderType.SMARTNET, null));
    }

    @Test
    public void conventionalCapableDecodersNeedEvidence()
    {
        //A P25 channel without a decoded control channel broadcast (Salem Police on Daly) isn't a reference
        assertFalse(GainCheckReference.isControlChannel(DecoderType.P25_PHASE1, null));
        assertFalse(GainCheckReference.isControlChannel(DecoderType.DMR, null));
        assertFalse(GainCheckReference.isControlChannel(DecoderType.NXDN, null));
        assertFalse(GainCheckReference.isControlChannel(DecoderType.NBFM, null));
    }
}
