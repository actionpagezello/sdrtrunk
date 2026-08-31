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

package io.github.dsheirer.gui.control;

import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link TableRowSorter} that survives sort failures caused by table data mutating while a sort
 * is in progress, instead of killing the Swing event dispatch thread.
 *
 * Why this exists: tables backed by live decoder data (the channel metadata table in particular)
 * have rows whose values are updated continuously by decoder threads. Removing a row makes Swing
 * re-sort the whole table, and TimSort compares values that can change underneath it mid-sort. When
 * it detects the resulting inconsistency it throws
 * {@code IllegalArgumentException: Comparison method violates its general contract!}.
 *
 * That first failure aborts the sort partway through, leaving the sorter's internal model-to-view
 * index arrays inconsistent with the model. Every subsequent row change then throws
 * {@link ArrayIndexOutOfBoundsException} out of {@code setModelToViewFromViewToModel}. Because all
 * of this runs on the event dispatch thread and the exceptions are uncaught, the EDT dies and the
 * entire GUI freezes while decoding continues normally in the background — an application that
 * looks hung but is still working.
 *
 * Observed in production on 2026-08-30: a site running several P25 trunked systems, where traffic
 * channels are created and destroyed constantly, froze at 17:07 and kept decoding for more than
 * five hours afterward with a dead interface.
 *
 * Rather than trying to prevent the race — the model would have to be snapshot-isolated from the
 * decoder threads, which is a much larger change — this makes it non-fatal. Each model-change
 * notification is guarded, and on failure the sorter clears its sort keys and rebuilds a clean
 * identity mapping. The visible symptom degrades from "the GUI is frozen" to "the table stopped
 * being sorted", and the user can click a column header to re-apply it.
 *
 * @param <M> table model type
 */
public class SafeTableRowSorter<M extends TableModel> extends TableRowSorter<M>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SafeTableRowSorter.class);

    /** Guards against re-entering recovery from a failure raised by recovery itself. */
    private boolean mRecovering = false;

    /** Number of failures recovered, for diagnostics. */
    private int mRecoveryCount = 0;

    /**
     * Constructs a fault-tolerant row sorter for the specified model.
     *
     * @param model to sort
     */
    public SafeTableRowSorter(M model)
    {
        super(model);
    }

    /**
     * Count of sort failures recovered since construction.
     */
    public int getRecoveryCount()
    {
        return mRecoveryCount;
    }

    @Override
    public void sort()
    {
        try
        {
            super.sort();
        }
        catch(Exception | Error e)
        {
            recover("sort", e);
        }
    }

    @Override
    public void rowsDeleted(int firstRow, int endRow)
    {
        try
        {
            super.rowsDeleted(firstRow, endRow);
        }
        catch(Exception | Error e)
        {
            recover("rowsDeleted", e);
        }
    }

    @Override
    public void rowsInserted(int firstRow, int endRow)
    {
        try
        {
            super.rowsInserted(firstRow, endRow);
        }
        catch(Exception | Error e)
        {
            recover("rowsInserted", e);
        }
    }

    @Override
    public void rowsUpdated(int firstRow, int endRow)
    {
        try
        {
            super.rowsUpdated(firstRow, endRow);
        }
        catch(Exception | Error e)
        {
            recover("rowsUpdated", e);
        }
    }

    @Override
    public void rowsUpdated(int firstRow, int endRow, int column)
    {
        try
        {
            super.rowsUpdated(firstRow, endRow, column);
        }
        catch(Exception | Error e)
        {
            recover("rowsUpdated(column)", e);
        }
    }

    @Override
    public void allRowsChanged()
    {
        try
        {
            super.allRowsChanged();
        }
        catch(Exception | Error e)
        {
            recover("allRowsChanged", e);
        }
    }

    @Override
    public void modelStructureChanged()
    {
        try
        {
            super.modelStructureChanged();
        }
        catch(Exception | Error e)
        {
            recover("modelStructureChanged", e);
        }
    }

    /**
     * Restores the sorter to a consistent state after a failed sort.
     *
     * Clearing the sort keys drops back to an identity model-to-view mapping, which cannot throw,
     * and rebuilding from there discards whatever partial index arrays the aborted sort left
     * behind. Sorting is not permanently disabled — the user can re-apply it by clicking a column
     * header, and if the race recurs this simply runs again.
     *
     * @param operation that failed, for logging
     * @param throwable that was raised
     */
    private void recover(String operation, Throwable throwable)
    {
        if(mRecovering)
        {
            // A failure raised while recovering. Nothing further to do — the sorter is already
            // being reset and re-entering would recurse.
            return;
        }

        mRecovering = true;
        mRecoveryCount++;

        try
        {
            setSortKeys(null);
            super.allRowsChanged();

            LOGGER.warn("Table row sorter failed during [" + operation + "] because row data changed " +
                    "while sorting - sort order has been cleared and the sorter rebuilt (recovery " +
                    mRecoveryCount + "). Click a column header to re-apply sorting. Cause: " +
                    throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
        catch(Exception | Error nested)
        {
            LOGGER.error("Unable to reset table row sorter after failure during [" + operation +
                    "] - table sorting is disabled until restart", nested);
        }
        finally
        {
            mRecovering = false;
        }
    }
}
