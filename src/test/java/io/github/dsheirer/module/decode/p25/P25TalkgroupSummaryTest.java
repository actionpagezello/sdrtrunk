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

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests (edacs-24): the P25 activity summary lists the calls by talkgroup with the alias name.
 */
public class P25TalkgroupSummaryTest
{
    private static P25TrafficChannelManager manager(AliasList aliasList)
    {
        Channel channel = new Channel("P25 test");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        channel.setSourceConfiguration(source);
        return new P25TrafficChannelManager(channel, aliasList);
    }

    private static AliasList aliases()
    {
        AliasList aliasList = new AliasList("test");
        Alias alias = new Alias("Boston Fire Dispatch");
        alias.addAliasID(new Talkgroup(Protocol.APCO25, 100));
        aliasList.addAlias(alias);
        return aliasList;
    }

    private static MutableIdentifierCollection call(int talkgroup, int radio)
    {
        MutableIdentifierCollection ic = new MutableIdentifierCollection();
        ic.update(APCO25Talkgroup.create(talkgroup));
        ic.update(APCO25RadioIdentifier.createFrom(radio));
        return ic;
    }

    private static String summary(P25TrafficChannelManager manager)
    {
        io.github.dsheirer.module.decode.event.ActivitySummary summary =
            new io.github.dsheirer.module.decode.event.ActivitySummary("Test");
        manager.addTalkgroupSummary(summary);
        return summary.toString();
    }

    /**
     * Table rows after the header and its underline in the talkgroup section.
     */
    private static List<String> rows(String summary)
    {
        List<String> lines = List.of(summary.split("\\R"));
        int header = -1;

        for(int x = 0; x < lines.size(); x++)
        {
            if(lines.get(x).trim().startsWith("Talkgroup"))
            {
                header = x;
                break;
            }
        }

        return lines.subList(header + 2, lines.size()).stream().filter(line -> !line.isBlank()).toList();
    }

    @Test
    void testCountsAndAliasNames()
    {
        P25TrafficChannelManager manager = manager(aliases());
        assertTrue(summary(manager).contains("None"), summary(manager));

        manager.countCall(call(2000, 1));
        manager.countCall(call(100, 2));
        manager.countCall(call(100, 3));
        manager.countCall(call(30, 4));

        String summary = summary(manager);
        List<String> rows = rows(summary);
        assertTrue(summary.contains("Calls by Talkgroup"), summary);
        assertTrue(rows.get(0).matches("\\s*30\\s+1"), summary);
        assertTrue(rows.get(1).matches("\\s*100\\s+Boston Fire Dispatch\\s+2"), summary);
        assertTrue(rows.get(2).matches("\\s*2000\\s+1"), summary);
    }

    /**
     * A Phase 1 voice channel grant from the control channel counts once, however often the grant is repeated.
     */
    @Test
    void testGrantCountsOnce()
    {
        P25TrafficChannelManager manager = manager(aliases());
        IFrequencyBand band = (IFrequencyBand)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{IFrequencyBand.class}, (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                return type == long.class ? (Object)853_500_000L : type == int.class ? (Object)1 :
                    type == boolean.class ? (Object)false : type == double.class ? (Object)0.0 : null;
            });
        APCO25Channel channel = APCO25Channel.create(1, 400);
        channel.setFrequencyBand(band);

        for(int x = 0; x < 3; x++)
        {
            manager.processP1ControlDirectedChannelGrant(channel, null, call(100, 2),
                Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000_000L + x * 100, "test");
        }

        String summary = summary(manager);
        assertTrue(rows(summary).get(0).matches("\\s*100\\s+Boston Fire Dispatch\\s+1"), summary);
    }
}
