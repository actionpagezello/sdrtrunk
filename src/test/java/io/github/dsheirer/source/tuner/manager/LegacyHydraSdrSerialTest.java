package io.github.dsheirer.source.tuner.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HydraSDR RFOne units that use the Airspy USB ids are recognized by their USB serial string.
 */
public class LegacyHydraSdrSerialTest
{
    @Test
    public void hydraSdrSerialIsRecognized()
    {
        assertTrue(TunerManager.isLegacyHydraSdrSerial("HYDRASDR SN:A12B34C56D78E9F0"));
        assertTrue(TunerManager.isLegacyHydraSdrSerial(" hydrasdr sn:0000000000000001 "));
    }

    @Test
    public void airspySerialAndMissingSerialStayAirspy()
    {
        assertFalse(TunerManager.isLegacyHydraSdrSerial("AIRSPY SN:637862DC2F2031D7"));
        assertFalse(TunerManager.isLegacyHydraSdrSerial(null));
        assertFalse(TunerManager.isLegacyHydraSdrSerial(""));
    }
}
