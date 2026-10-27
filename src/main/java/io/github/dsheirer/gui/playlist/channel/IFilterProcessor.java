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

package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.controller.channel.Channel;

/**
 * Interface for a processor that implements a filtering mechanism and can be commanded by an external controller to
 * temporarily clear a filter and then restore the filter.
 */
public interface IFilterProcessor
{
    void clearFilter();
    void restoreFilter();

    /**
     * AP-fork (ap-15.9.33): selects the channel again after the editor has applied its changes.  Saving rebuilds the
     * filtered and sorted table, and the table keeps its selection by row rather than by channel, so the selection
     * could land on a neighbouring channel.
     * @param channel that was being edited
     */
    default void reselect(Channel channel)
    {
    }
}
