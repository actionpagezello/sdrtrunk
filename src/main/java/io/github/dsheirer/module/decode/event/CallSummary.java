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
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AP-fork (edacs-25): calls by talkgroup for the Details tab, for any kind of channel.  Listens to the decode events
 * of a processing chain and counts each voice call once against the talkgroup (or radio, or patch group) it is to.
 */
public class CallSummary implements Listener<IDecodeEvent>
{
    public static final int LIMIT = 2000;
    private final Map<String,Integer> mCounts = new HashMap<>();
    private final Map<String,Identifier> mIdentifiers = new HashMap<>();
    private final Map<String,Long> mLastStart = new HashMap<>();

    /**
     * Counts a voice call event.  An event is broadcast again each time it is updated; the start time tells a new call
     * from an update of the one already counted.
     */
    @Override
    public void receive(IDecodeEvent event)
    {
        if(event == null || event.getEventType() == null || !event.getEventType().isVoiceCallEvent())
        {
            return;
        }

        IdentifierCollection identifiers = event.getIdentifierCollection();
        Identifier to = identifiers != null ? identifiers.getToIdentifier() : null;

        if(to == null)
        {
            return;
        }

        String key = to.toString();

        synchronized(this)
        {
            Long last = mLastStart.get(key);

            if(last != null && last == event.getTimeStart())
            {
                return;
            }

            if(last == null && mCounts.size() >= LIMIT)
            {
                return;
            }

            mLastStart.put(key, event.getTimeStart());
            mCounts.merge(key, 1, Integer::sum);
            mIdentifiers.putIfAbsent(key, to);
        }
    }

    /**
     * True when no call has been counted
     */
    public synchronized boolean isEmpty()
    {
        return mCounts.isEmpty();
    }

    /**
     * ap-18: adds the list as a "Calls by Talkgroup" section with a Talkgroup / Alias / Calls table, in the same layout
     * as the decoders' own activity summaries.
     * @param summary to add the section to
     * @param aliasList of the channel, can be null
     */
    public void addTo(ActivitySummary summary, AliasList aliasList)
    {
        Map<String,Integer> counts;
        Map<String,Identifier> identifiers;

        synchronized(this)
        {
            counts = new HashMap<>(mCounts);
            identifiers = new HashMap<>(mIdentifiers);
        }

        summary.section("Calls by Talkgroup");
        ActivitySummary.Table table = summary.table("Talkgroup", "Alias", "Calls");

        for(String key : sortedKeys(counts))
        {
            table.row(key, aliasName(aliasList, identifiers.get(key)), counts.get(key));
        }
    }

    private static List<String> sortedKeys(Map<String,Integer> counts)
    {
        List<String> keys = new ArrayList<>(counts.keySet());
        keys.sort(Comparator.comparingLong((String key) -> {
            try
            {
                return Long.parseLong(key);
            }
            catch(NumberFormatException e)
            {
                return Long.MAX_VALUE;
            }
        }).thenComparing(Comparator.naturalOrder()));
        return keys;
    }

    private static String aliasName(AliasList aliasList, Identifier identifier)
    {
        if(aliasList != null && identifier != null)
        {
            List<Alias> aliases = aliasList.getAliases(identifier);

            if(aliases != null && !aliases.isEmpty())
            {
                return aliases.get(0).getName();
            }
        }

        return "";
    }

    /**
     * The list: talkgroup, alias name from the alias list (blank when it has none), calls.  Numbers in numeric order,
     * anything else after them.
     * @param aliasList of the channel, can be null
     */
    public String format(AliasList aliasList)
    {
        Map<String,Integer> counts;
        Map<String,Identifier> identifiers;

        synchronized(this)
        {
            counts = new HashMap<>(mCounts);
            identifiers = new HashMap<>(mIdentifiers);
        }

        List<String> keys = new ArrayList<>(counts.keySet());
        keys.sort(Comparator.comparingLong((String key) -> {
            try
            {
                return Long.parseLong(key);
            }
            catch(NumberFormatException e)
            {
                return Long.MAX_VALUE;
            }
        }).thenComparing(Comparator.naturalOrder()));

        StringBuilder sb = new StringBuilder();
        sb.append("Calls by talkgroup (talkgroup, alias, calls):\n");

        if(keys.isEmpty())
        {
            sb.append("  none yet\n");
        }

        for(String key : keys)
        {
            String name = "";

            if(aliasList != null)
            {
                List<Alias> aliases = aliasList.getAliases(identifiers.get(key));

                if(aliases != null && !aliases.isEmpty())
                {
                    name = aliases.get(0).getName();
                }
            }

            sb.append(String.format("  %8s  %-32s %d%n", key, name, counts.get(key)));
        }

        return sb.toString();
    }
}
