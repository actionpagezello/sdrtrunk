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

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rsp1b.DiscoveredRsp1bTuner;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AP-fork (ap-15.11.1): RSP recovery after the SDRplay API reports a tuner removed.
 */
public class RspRecoveryMonitorTest
{
    private static final String SERIAL = "24050F5260";

    /**
     * Test: the removed RSP is re-added once, only after the SDRplay API lists its serial number again, and the
     * monitor then stops checking for it.
     */
    @Test
    void testReattachesWhenSerialReturns()
    {
        List<DeviceInfo> available = new ArrayList<>();
        List<String> present = new ArrayList<>();
        AtomicInteger adds = new AtomicInteger();

        RspRecoveryMonitor monitor = new RspRecoveryMonitor(() -> new ArrayList<>(available), present::contains,
            deviceInfo -> { adds.incrementAndGet(); return 1; });

        DiscoveredRsp1bTuner tuner = new DiscoveredRsp1bTuner(new DeviceInfo(DeviceType.RSP1B, SERIAL),
            ChannelizerType.POLYPHASE);
        Set<TunerChannel> channels = new TreeSet<>();
        channels.add(new TunerChannel(155032500, 12500));

        monitor.rspRemoved(tuner, channels);
        assertEquals(1, monitor.getPendingCount());

        //Not listed by the API yet
        monitor.retry();
        monitor.retry();
        assertEquals(0, adds.get());
        assertEquals(1, monitor.getPendingCount());

        //Another RSP is listed - still not ours
        available.add(new DeviceInfo(DeviceType.RSP1B, "24040A4B60"));
        monitor.retry();
        assertEquals(0, adds.get());

        //Ours returns
        available.add(new DeviceInfo(DeviceType.RSP1B, SERIAL));
        monitor.retry();
        assertEquals(1, adds.get(), "Re-added once");
        assertEquals(0, monitor.getPendingCount(), "No longer pending");

        monitor.retry();
        assertEquals(1, adds.get(), "Not re-added again");
    }

    /**
     * Test: when the tuner is already back in the tuner model (re-added another way), it isn't added a second time
     * but the pending recovery completes.
     */
    @Test
    void testDoesNotDuplicateTuner()
    {
        DiscoveredRsp1bTuner tuner = new DiscoveredRsp1bTuner(new DeviceInfo(DeviceType.RSP1B, SERIAL),
            ChannelizerType.POLYPHASE);
        AtomicInteger adds = new AtomicInteger();
        RspRecoveryMonitor monitor = new RspRecoveryMonitor(() -> List.of(new DeviceInfo(DeviceType.RSP1B, SERIAL)),
            id -> id.equals(tuner.getId()), deviceInfo -> { adds.incrementAndGet(); return 1; });

        monitor.rspRemoved(tuner, new TreeSet<>());
        monitor.retry();
        assertEquals(0, adds.get());
        assertEquals(0, monitor.getPendingCount());
    }

    /**
     * Test (ap-15.11.4): the removed device is released once, before the first listing, and the API - which doesn't
     * list a device this program still has selected (Daly 2026-11-04) - lists it again afterwards.
     */
    @Test
    void testReleasesRemovedDeviceBeforeListing()
    {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger adds = new AtomicInteger();
        List<String> order = new ArrayList<>();
        DiscoveredRsp1bTuner tuner = new DiscoveredRsp1bTuner(new DeviceInfo(DeviceType.RSP1B, SERIAL),
            ChannelizerType.POLYPHASE);

        RspRecoveryMonitor monitor = new RspRecoveryMonitor(() -> {
                order.add("list");
                return releases.get() > 0 ? List.of(new DeviceInfo(DeviceType.RSP1B, SERIAL)) : List.of();
            }, id -> false, deviceInfo -> { adds.incrementAndGet(); return 1; });

        monitor.rspRemoved(tuner, new TreeSet<>(), () -> { order.add("release"); releases.incrementAndGet(); return true; });
        monitor.retry();

        assertEquals(List.of("release", "list"), order, "Released before the first listing");
        assertEquals(1, adds.get(), "Listed again after the release and re-added");
        assertEquals(0, monitor.getPendingCount());
    }

    /**
     * Test (ap-15.11.4): the release is attempted only once even if the device doesn't come back, and an error in it
     * doesn't stop the checks.
     */
    @Test
    void testReleaseAttemptedOnce()
    {
        AtomicInteger releases = new AtomicInteger();
        DiscoveredRsp1bTuner tuner = new DiscoveredRsp1bTuner(new DeviceInfo(DeviceType.RSP1B, SERIAL),
            ChannelizerType.POLYPHASE);
        RspRecoveryMonitor monitor = new RspRecoveryMonitor(List::of, id -> false, deviceInfo -> 1);

        monitor.rspRemoved(tuner, new TreeSet<>(), () -> { releases.incrementAndGet(); throw new IllegalStateException("api"); });
        monitor.retry();
        monitor.retry();
        monitor.retry();

        assertEquals(1, releases.get());
        assertEquals(1, monitor.getPendingCount(), "Still checking");
    }

    /**
     * Test: a failed re-add is retried on the next check.
     */
    @Test
    void testRetriesFailedReadd()
    {
        DiscoveredRsp1bTuner tuner = new DiscoveredRsp1bTuner(new DeviceInfo(DeviceType.RSP1B, SERIAL),
            ChannelizerType.POLYPHASE);
        AtomicInteger calls = new AtomicInteger();
        RspRecoveryMonitor monitor = new RspRecoveryMonitor(() -> List.of(new DeviceInfo(DeviceType.RSP1B, SERIAL)),
            id -> false, deviceInfo -> {
                if(calls.incrementAndGet() == 1)
                {
                    throw new IllegalStateException("device busy");
                }
                return 1;
            });

        monitor.rspRemoved(tuner, new TreeSet<>());
        monitor.retry();
        assertEquals(1, monitor.getPendingCount(), "Still pending after a failed re-add");
        monitor.retry();
        assertEquals(0, monitor.getPendingCount());
        assertEquals(2, calls.get());
    }
}
