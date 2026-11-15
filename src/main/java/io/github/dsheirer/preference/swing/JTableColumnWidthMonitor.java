/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2019 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.preference.swing;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors a JTable column model and persists column width, order, and sort state changes to the
 * user preferences.  Restores previous column widths, positions, and sort keys on application restart.
 */
public class JTableColumnWidthMonitor
{
    private static final Logger mLog = LoggerFactory.getLogger(JTableColumnWidthMonitor.class);
    private UserPreferences mUserPreferences;
    private JTable mTable;
    private String mKey;
    private ColumnModelListener mColumnModelListener = new ColumnModelListener();
    private SortListener mSortListener = new SortListener();
    private AtomicBoolean mSaveInProgress = new AtomicBoolean();
    private boolean mRestoring = false;

    /**
     * Constructs a column width monitor.
     *
     * @param userPreferences to store column widths
     * @param table to monitor for column width changes
     * @param key that uniquely identifies the table to monitor
     */
    public JTableColumnWidthMonitor(UserPreferences userPreferences, JTable table, String key)
    {
        mUserPreferences = userPreferences;
        mTable = table;
        mKey = key;

        mTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);

        // Wait until the UI is realized to restore column widths, order, and sort state
        EventQueue.invokeLater(this::restoreColumnState);

        // Listen for drag-resizes and column moves
        mTable.getColumnModel().addColumnModelListener(mColumnModelListener);

        // Listen for sort changes if the table has a row sorter
        if(mTable.getRowSorter() != null)
        {
            mTable.getRowSorter().addRowSorterListener(mSortListener);
        }
    }

    /**
     * Prepares this monitor for disposal by unregistering as a listener to the table column model.
     */
    public void dispose()
    {
        if(mTable != null)
        {
            if(mColumnModelListener != null)
            {
                mTable.getColumnModel().removeColumnModelListener(mColumnModelListener);
            }

            if(mTable.getRowSorter() != null && mSortListener != null)
            {
                mTable.getRowSorter().removeRowSorterListener(mSortListener);
            }
        }

        mTable = null;
        mUserPreferences = null;
    }

    /**
     * Restores column widths, order, and sort state from persisted settings
     */
    private void restoreColumnState()
    {
        mRestoring = true;

        try
        {
            TableColumnModel model = mTable.getColumnModel();
            int columnCount = model.getColumnCount();

            // Restore column order first
            restoreColumnOrder(model, columnCount);

            // Then restore column widths
            for(int x = 0; x < columnCount; x++)
            {
                int width = mUserPreferences.getSwingPreference().getInt(getWidthKey(x), Integer.MAX_VALUE);

                if(width != Integer.MAX_VALUE)
                {
                    model.getColumn(x).setPreferredWidth(width);
                }
            }

            // Restore sort state
            restoreSortState();
        }
        finally
        {
            mRestoring = false;
        }
    }

    /**
     * Restores column order from persisted model index positions.
     * Each view position stores the model index of the column that should be at that position.
     */
    private void restoreColumnOrder(TableColumnModel model, int columnCount)
    {
        // Check if we have saved order data
        int firstOrder = mUserPreferences.getSwingPreference().getInt(getOrderKey(0), Integer.MAX_VALUE);
        if(firstOrder == Integer.MAX_VALUE)
        {
            return; // No saved order
        }

        // Read saved model indices for each view position
        int[] savedOrder = new int[columnCount];
        for(int i = 0; i < columnCount; i++)
        {
            savedOrder[i] = mUserPreferences.getSwingPreference().getInt(getOrderKey(i), i);
        }

        // Move columns to their saved positions
        for(int targetViewIndex = 0; targetViewIndex < columnCount; targetViewIndex++)
        {
            int targetModelIndex = savedOrder[targetViewIndex];

            // Find the column with this model index in the current view
            int currentViewIndex = -1;
            for(int j = targetViewIndex; j < columnCount; j++)
            {
                if(model.getColumn(j).getModelIndex() == targetModelIndex)
                {
                    currentViewIndex = j;
                    break;
                }
            }

            if(currentViewIndex >= 0 && currentViewIndex != targetViewIndex)
            {
                model.moveColumn(currentViewIndex, targetViewIndex);
            }
        }
    }

    /**
     * Stores the current column widths, order, and sort state to the user preferences
     */
    private void storeColumnState()
    {
        TableColumnModel model = mTable.getColumnModel();

        for(int x = 0; x < model.getColumnCount(); x++)
        {
            TableColumn column = model.getColumn(x);
            mUserPreferences.getSwingPreference().setInt(getWidthKey(x), column.getWidth());
            mUserPreferences.getSwingPreference().setInt(getOrderKey(x), column.getModelIndex());
        }

        storeSortState();
    }

    /**
     * Constructs a preference key for column width at a view position
     */
    private String getWidthKey(int column)
    {
        return mKey + ".column." + column;
    }

    /**
     * Constructs a preference key for column order (model index at a view position)
     */
    private String getOrderKey(int viewPosition)
    {
        return mKey + ".order." + viewPosition;
    }

    /**
     * Constructs a preference key for the number of sort keys
     */
    private String getSortCountKey()
    {
        return mKey + ".sort.count";
    }

    /**
     * Constructs a preference key for a sort key's column index
     */
    private String getSortColumnKey(int index)
    {
        return mKey + ".sort." + index + ".column";
    }

    /**
     * Constructs a preference key for a sort key's sort order
     */
    private String getSortOrderKey(int index)
    {
        return mKey + ".sort." + index + ".order";
    }

    /**
     * Stores the current sort state (sort keys) to user preferences
     */
    private void storeSortState()
    {
        if(mTable.getRowSorter() == null)
        {
            return;
        }

        List<? extends RowSorter.SortKey> sortKeys = mTable.getRowSorter().getSortKeys();
        mUserPreferences.getSwingPreference().setInt(getSortCountKey(), sortKeys.size());

        for(int i = 0; i < sortKeys.size(); i++)
        {
            RowSorter.SortKey key = sortKeys.get(i);
            mUserPreferences.getSwingPreference().setInt(getSortColumnKey(i), key.getColumn());
            mUserPreferences.getSwingPreference().setInt(getSortOrderKey(i), key.getSortOrder().ordinal());
        }
    }

    /**
     * Restores the sort state (sort keys) from user preferences
     */
    private void restoreSortState()
    {
        if(mTable.getRowSorter() == null)
        {
            return;
        }

        int sortCount = mUserPreferences.getSwingPreference().getInt(getSortCountKey(), 0);

        if(sortCount > 0)
        {
            List<RowSorter.SortKey> sortKeys = new ArrayList<>();

            for(int i = 0; i < sortCount; i++)
            {
                int column = mUserPreferences.getSwingPreference().getInt(getSortColumnKey(i), -1);
                int orderOrdinal = mUserPreferences.getSwingPreference().getInt(getSortOrderKey(i), SortOrder.UNSORTED.ordinal());

                if(column >= 0 && column < mTable.getColumnCount())
                {
                    SortOrder order = SortOrder.values()[orderOrdinal];
                    sortKeys.add(new RowSorter.SortKey(column, order));
                }
            }

            if(!sortKeys.isEmpty())
            {
                try
                {
                    mTable.getRowSorter().setSortKeys(sortKeys);
                }
                catch(Exception e)
                {
                    mLog.error("Error restoring sort state", e);
                }
            }
        }
    }

    /**
     * Listener for row sorter changes (when user clicks column headers to sort)
     */
    class SortListener implements RowSorterListener
    {
        @Override
        public void sorterChanged(RowSorterEvent e)
        {
            if(!mRestoring && e.getType() == RowSorterEvent.Type.SORT_ORDER_CHANGED)
            {
                scheduleSave();
            }
        }

        private void scheduleSave()
        {
            if(mSaveInProgress.compareAndSet(false, true))
            {
                ThreadPool.SCHEDULED.schedule(new ColumnStateSaveTask(), 2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Table column model listener that tracks both resize and move events.
     */
    class ColumnModelListener implements TableColumnModelListener
    {
        @Override
        public void columnMarginChanged(ChangeEvent e)
        {
            scheduleSave();
        }

        @Override
        public void columnMoved(TableColumnModelEvent e)
        {
            // Only save when the column actually moved to a new position (not during drag)
            if(!mRestoring && e.getFromIndex() != e.getToIndex())
            {
                scheduleSave();
            }
        }

        @Override
        public void columnAdded(TableColumnModelEvent e){}
        @Override
        public void columnRemoved(TableColumnModelEvent e){}
        @Override
        public void columnSelectionChanged(ListSelectionEvent e){}

        private void scheduleSave()
        {
            if(mSaveInProgress.compareAndSet(false, true))
            {
                ThreadPool.SCHEDULED.schedule(new ColumnStateSaveTask(), 2, TimeUnit.SECONDS);
            }
        }
    }

    public class ColumnStateSaveTask implements Runnable
    {
        @Override
        public void run()
        {
            storeColumnState();
            mSaveInProgress.set(false);
        }
    }
}
