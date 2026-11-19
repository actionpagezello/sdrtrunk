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

package io.github.dsheirer.source.tuner.frequency;

import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.ISourceEventProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AP-fork (1.0.0-ap-10): a sample rate change can stop a channel, which removes its processor from the frequency
 * controller during the same broadcast, on the same thread.  That threw ConcurrentModificationException (seen on
 * Paxton and Monson) and the remaining processors missed the event.
 */
class FrequencyControllerTest
{
    @Test
    void processorRemovedDuringBroadcastDoesNotStopTheBroadcast()
    {
        ReentrantLock lock = new ReentrantLock();
        FrequencyController controller = new FrequencyController(new FrequencyController.Tunable()
        {
            @Override public ReentrantLock getLock() { return lock; }
            @Override public long getTunedFrequency() { return 0; }
            @Override public void setTunedFrequency(long frequency) { }
            @Override public double getCurrentSampleRate() { return 2_400_000; }
            @Override public boolean canTune(long frequency) { return true; }
        });

        List<String> received = new ArrayList<>();
        ISourceEventProcessor[] first = new ISourceEventProcessor[1];
        first[0] = event -> {
            received.add("first");
            controller.removeSourceEventProcessor(first[0]);
        };
        controller.addSourceEventProcessor(first[0]);
        controller.addSourceEventProcessor(event -> received.add("second"));
        controller.addSourceEventProcessor(event -> received.add("third"));

        assertDoesNotThrow(() -> controller.broadcast(SourceEvent.sampleRateChange(2_400_000)));
        assertEquals(List.of("first", "second", "third"), received);

        received.clear();
        assertDoesNotThrow(() -> controller.broadcast(SourceEvent.sampleRateChange(2_400_000)));
        assertEquals(List.of("second", "third"), received);
    }
}
