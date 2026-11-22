package io.github.dsheirer.source.tuner.manager;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ap-17: the strongest signal is matched to the tuner's own channel within half a 25 kHz channel.
 */
public class TunerChannelLogTest
{
    private static final List<TunerChannelLog.Entry> DENMARK = List.of(
        new TunerChannelLog.Entry("Oxford County Fire", 154_070_000),
        new TunerChannelLog.Entry("Bridgton FD", 154_190_000),
        new TunerChannelLog.Entry("Oxford County Sheriff", 155_070_000));

    @Test
    public void strongestSignalNamesTheChannel()
    {
        //The spectrum bin put Oxford County Fire at 154.0625 MHz on the RF One
        assertEquals("Oxford County Fire", TunerChannelLog.channelAt(DENMARK, 154_062_500));
        assertEquals("Oxford County Sheriff", TunerChannelLog.channelAt(DENMARK, 155_070_000));
    }

    @Test
    public void signalOffTheTunersChannelsIsNotNamed()
    {
        assertNull(TunerChannelLog.channelAt(DENMARK, 152_610_000));
        assertNull(TunerChannelLog.channelAt(List.of(), 154_070_000));
    }

    @Test
    public void listIsFormattedInFrequencyOrder()
    {
        assertEquals("Oxford County Fire (154.0700), Bridgton FD (154.1900), Oxford County Sheriff (155.0700)",
            TunerChannelLog.describe(DENMARK));
    }
}
