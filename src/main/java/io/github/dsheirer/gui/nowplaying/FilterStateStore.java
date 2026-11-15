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
package io.github.dsheirer.gui.nowplaying;

import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.filter.IFilter;
import io.github.dsheirer.preference.NowPlayingPreference;

/**
 * AP-fork: keeps the Now Playing event and message filter choices between sessions, as the fork's Swing panels did.
 * Each filter element is stored under "filter name.element name", enabled unless stored otherwise.
 */
public final class FilterStateStore
{
    private FilterStateStore()
    {
    }

    /**
     * Applies the stored enabled states to the filter set's elements.
     */
    public static <T> void restore(FilterSet<T> filterSet, NowPlayingPreference preference)
    {
        for(IFilter<T> iFilter: filterSet.getFilters())
        {
            if(iFilter instanceof Filter<?,?> filter)
            {
                for(FilterElement<?> element: filter.getFilterElements())
                {
                    element.setEnabled(preference.isFilterEnabled(getKey(filter, element)));
                }
            }
        }
    }

    /**
     * Stores the enabled states of the filter set's elements.
     */
    public static <T> void store(FilterSet<T> filterSet, NowPlayingPreference preference)
    {
        for(IFilter<T> iFilter: filterSet.getFilters())
        {
            if(iFilter instanceof Filter<?,?> filter)
            {
                for(FilterElement<?> element: filter.getFilterElements())
                {
                    preference.setFilterEnabled(getKey(filter, element), element.isEnabled());
                }
            }
        }
    }

    private static String getKey(Filter<?,?> filter, FilterElement<?> element)
    {
        return filter.getName() + "." + element.getName();
    }
}
