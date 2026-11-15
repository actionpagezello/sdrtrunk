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

package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests (edacs-25): the general "calls by talkgroup" list of the Details tab, here with DMR talkgroups.
 */
public class CallSummaryTest
{
    private static DecodeEvent event(DecodeEventType type, int talkgroup, long start)
    {
        MutableIdentifierCollection ic = new MutableIdentifierCollection();
        ic.update(DMRTalkgroup.create(talkgroup));
        return DecodeEvent.builder(type, start).identifiers(ic).build();
    }

    @Test
    void testCountsCallsOncePerStartWithAliasNames()
    {
        AliasList aliasList = new AliasList("test");
        Alias alias = new Alias("Public Works");
        alias.addAliasID(new Talkgroup(Protocol.DMR, 9));
        aliasList.addAlias(alias);

        CallSummary summary = new CallSummary();
        assertTrue(summary.format(aliasList).contains("none yet"));

        DecodeEvent call = event(DecodeEventType.CALL_GROUP, 9, 1000);
        summary.receive(call);
        summary.receive(call);                                          //the same call, updated
        summary.receive(event(DecodeEventType.CALL_GROUP, 9, 1000));    //and announced again
        summary.receive(event(DecodeEventType.CALL_GROUP, 9, 9000));    //a second call
        summary.receive(event(DecodeEventType.CALL_GROUP, 3100, 2000));
        summary.receive(event(DecodeEventType.REGISTER, 77, 3000));     //not a voice call
        summary.receive(null);

        String[] lines = summary.format(aliasList).split("\\R");
        assertTrue(lines[1].matches("\\s+9\\s+Public Works\\s+2"), summary.format(aliasList));
        assertTrue(lines[2].matches("\\s+3100\\s+1"), summary.format(aliasList));
        assertTrue(lines.length == 3, summary.format(aliasList));
        assertTrue(summary.format(null).contains("3100"), "works without an alias list");
    }
}
