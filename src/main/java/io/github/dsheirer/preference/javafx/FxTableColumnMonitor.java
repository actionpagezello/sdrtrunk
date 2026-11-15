/*
 * *****************************************************************************
 * sdrtrunk
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

package io.github.dsheirer.preference.javafx;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors a JavaFX TableView and persists its column widths, visible order, and sort order
 * across application restarts.
 *
 * Each column must have a stable, unique id set via {@link TableColumn#setId(String)} before
 * this monitor is attached. Column identifiers are used as the keys into user preferences, so
 * reordering or renaming columns in code will not lose a user's saved preferences.
 *
 * This is the JavaFX analogue of the legacy Swing {@code JTableColumnWidthMonitor}. It writes
 * to {@link UserPreferences#getSwingPreference()} (which is just a {@code java.util.prefs} node
 * underneath, despite the name).
 */
public class FxTableColumnMonitor
{
    private static final Logger mLog = LoggerFactory.getLogger(FxTableColumnMonitor.class);

    private static final String KEY_WIDTH = ".width";
    private static final String KEY_ORDER = ".order";
    private static final String KEY_SORT_COUNT = ".sort.count";
    private static final String KEY_SORT_COLUMN = ".sort.column.";
    private static final String KEY_SORT_TYPE = ".sort.type.";

    private UserPreferences mUserPreferences;
    private TableView<?> mTableView;
    private String mKey;
    private AtomicBoolean mSaveInProgress = new AtomicBoolean();
    private boolean mRestoring = false;

    private ChangeListener<Number> mWidthListener = (obs, oldVal, newVal) -> scheduleSave();
    private ListChangeListener<TableColumn<?, ?>> mColumnOrderListener = change -> {
        if(!mRestoring)
        {
            scheduleSave();
        }
    };
    private ListChangeListener<TableColumn<?, ?>> mSortOrderListener = change -> {
        if(!mRestoring)
        {
            scheduleSave();
        }
    };

    /**
     * Creates a monitor for the given table view.
     *
     * @param userPreferences persistence store
     * @param tableView to monitor and restore
     * @param key unique key prefix for this table (e.g. {@code "channelTable"})
     */
    public FxTableColumnMonitor(UserPreferences userPreferences, TableView<?> tableView, String key)
    {
        mUserPreferences = userPreferences;
        mTableView = tableView;
        mKey = key;

        Platform.runLater(() -> {
            try
            {
                restoreColumnState();
            }
            catch(Exception e)
            {
                mLog.error("Error restoring table column state for [" + mKey + "]", e);
            }
            finally
            {
                attachListeners();
            }
        });
    }

    /**
     * Removes all listeners from the monitored table. Safe to call multiple times.
     */
    public void dispose()
    {
        if(mTableView == null)
        {
            return;
        }

        try
        {
            for(TableColumn<?, ?> column : mTableView.getColumns())
            {
                column.widthProperty().removeListener(mWidthListener);
            }
            mTableView.getColumns().removeListener(mColumnOrderListener);
            mTableView.getSortOrder().removeListener(mSortOrderListener);
        }
        catch(Exception e)
        {
            mLog.error("Error disposing FxTableColumnMonitor for [" + mKey + "]", e);
        }

        mTableView = null;
        mUserPreferences = null;
    }

    private void attachListeners()
    {
        for(TableColumn<?, ?> column : mTableView.getColumns())
        {
            if(column.getId() == null || column.getId().isEmpty())
            {
                mLog.warn("Column without an id in table [" + mKey + "] - width/sort will not be persisted for this column");
                continue;
            }
            column.widthProperty().addListener(mWidthListener);
        }
        mTableView.getColumns().addListener(mColumnOrderListener);
        mTableView.getSortOrder().addListener(mSortOrderListener);
    }

    private void restoreColumnState()
    {
        mRestoring = true;
        try
        {
            Map<String, TableColumn<?, ?>> columnsById = new HashMap<>();
            for(TableColumn<?, ?> column : mTableView.getColumns())
            {
                if(column.getId() != null && !column.getId().isEmpty())
                {
                    columnsById.put(column.getId(), column);
                }
            }

            // Restore widths
            for(Map.Entry<String, TableColumn<?, ?>> entry : columnsById.entrySet())
            {
                int width = mUserPreferences.getSwingPreference().getInt(mKey + "." + entry.getKey() + KEY_WIDTH, Integer.MAX_VALUE);
                if(width != Integer.MAX_VALUE && width > 0)
                {
                    entry.getValue().setPrefWidth(width);
                }
            }

            // Restore column order by id
            int columnCount = mTableView.getColumns().size();
            int firstOrder = mUserPreferences.getSwingPreference().getInt(mKey + KEY_ORDER + ".0.id", Integer.MAX_VALUE);
            if(firstOrder != Integer.MAX_VALUE)
            {
                // Order was previously stored as ints (from Swing monitor). Ignore legacy data.
            }
            List<TableColumn<?, ?>> orderedColumns = new ArrayList<>();
            for(int i = 0; i < columnCount; i++)
            {
                String savedId = getStringPref(mKey + KEY_ORDER + "." + i);
                if(savedId != null)
                {
                    TableColumn<?, ?> match = columnsById.get(savedId);
                    if(match != null && !orderedColumns.contains(match))
                    {
                        orderedColumns.add(match);
                    }
                }
            }
            // Append any columns that were not in the saved order (new columns since last run)
            for(TableColumn<?, ?> column : mTableView.getColumns())
            {
                if(!orderedColumns.contains(column))
                {
                    orderedColumns.add(column);
                }
            }
            if(orderedColumns.size() == columnCount && !orderedColumns.equals(mTableView.getColumns()))
            {
                @SuppressWarnings({"unchecked", "rawtypes"})
                javafx.collections.ObservableList columns =
                    (javafx.collections.ObservableList) mTableView.getColumns();
                columns.setAll(orderedColumns);
            }

            // Restore sort order
            int sortCount = mUserPreferences.getSwingPreference().getInt(mKey + KEY_SORT_COUNT, 0);
            if(sortCount > 0)
            {
                List<TableColumn<?, ?>> newSortOrder = new ArrayList<>();
                for(int i = 0; i < sortCount; i++)
                {
                    String colId = getStringPref(mKey + KEY_SORT_COLUMN + i);
                    String sortType = getStringPref(mKey + KEY_SORT_TYPE + i);
                    if(colId != null)
                    {
                        TableColumn<?, ?> match = columnsById.get(colId);
                        if(match != null)
                        {
                            if(sortType != null)
                            {
                                try
                                {
                                    match.setSortType(TableColumn.SortType.valueOf(sortType));
                                }
                                catch(IllegalArgumentException ignored)
                                {
                                }
                            }
                            newSortOrder.add(match);
                        }
                    }
                }
                if(!newSortOrder.isEmpty())
                {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    javafx.collections.ObservableList sortOrder =
                        (javafx.collections.ObservableList) mTableView.getSortOrder();
                    sortOrder.setAll(newSortOrder);
                    mTableView.sort();
                }
            }
        }
        finally
        {
            mRestoring = false;
        }
    }

    private void storeColumnState()
    {
        try
        {
            List<? extends TableColumn<?, ?>> columns = mTableView.getColumns();
            for(int i = 0; i < columns.size(); i++)
            {
                TableColumn<?, ?> column = columns.get(i);
                if(column.getId() == null || column.getId().isEmpty())
                {
                    continue;
                }
                mUserPreferences.getSwingPreference().setInt(mKey + "." + column.getId() + KEY_WIDTH, (int) column.getWidth());
                setStringPref(mKey + KEY_ORDER + "." + i, column.getId());
            }

            List<? extends TableColumn<?, ?>> sortOrder = mTableView.getSortOrder();
            mUserPreferences.getSwingPreference().setInt(mKey + KEY_SORT_COUNT, sortOrder.size());
            for(int i = 0; i < sortOrder.size(); i++)
            {
                TableColumn<?, ?> column = sortOrder.get(i);
                if(column.getId() == null)
                {
                    continue;
                }
                setStringPref(mKey + KEY_SORT_COLUMN + i, column.getId());
                setStringPref(mKey + KEY_SORT_TYPE + i, column.getSortType().name());
            }
        }
        catch(Exception e)
        {
            mLog.error("Error storing table column state for [" + mKey + "]", e);
        }
    }

    private void scheduleSave()
    {
        if(mRestoring)
        {
            return;
        }
        if(mSaveInProgress.compareAndSet(false, true))
        {
            ThreadPool.SCHEDULED.schedule(() -> {
                try
                {
                    Platform.runLater(() -> {
                        try
                        {
                            if(mTableView != null)
                            {
                                storeColumnState();
                            }
                        }
                        finally
                        {
                            mSaveInProgress.set(false);
                        }
                    });
                }
                catch(Exception e)
                {
                    mSaveInProgress.set(false);
                    mLog.error("Error scheduling save for table [" + mKey + "]", e);
                }
            }, 2, TimeUnit.SECONDS);
        }
    }

    /**
     * SwingPreference only exposes primitive getters/setters, so strings go through a simple
     * helper that encodes non-empty values as a pair (length marker + string-as-int-hash fallback).
     *
     * We actually use the underlying {@code java.util.prefs.Preferences} node for strings via
     * a side channel: we piggyback on the existing SwingPreference by opening a preferences
     * node with the same package so keys don't collide. In practice this is simpler: we just
     * serialize by overlaying the key with a .s suffix and storing the chars' hash in an int.
     *
     * For robustness we instead store the string via the prefs API directly.
     */
    private String getStringPref(String key)
    {
        return java.util.prefs.Preferences.userNodeForPackage(io.github.dsheirer.preference.swing.SwingPreference.class)
            .get(key, null);
    }

    private void setStringPref(String key, String value)
    {
        if(value == null)
        {
            java.util.prefs.Preferences.userNodeForPackage(io.github.dsheirer.preference.swing.SwingPreference.class)
                .remove(key);
        }
        else
        {
            java.util.prefs.Preferences.userNodeForPackage(io.github.dsheirer.preference.swing.SwingPreference.class)
                .put(key, value);
        }
    }
}
