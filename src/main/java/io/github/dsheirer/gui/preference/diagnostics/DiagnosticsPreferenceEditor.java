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

package io.github.dsheirer.gui.preference.diagnostics;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.diagnostics.DiagnosticsCategory;
import io.github.dsheirer.preference.diagnostics.DiagnosticsPreference;
import io.github.dsheirer.preference.diagnostics.LogLevelController;
import java.util.EnumMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Runtime diagnostics preference panel. Lets the user toggle DEBUG logging for selected
 * subsystems without editing logback.xml and restarting.
 *
 * Introduced in ap-14.6.
 */
public class DiagnosticsPreferenceEditor extends HBox
{
    private final DiagnosticsPreference mDiagnosticsPreference;
    private final Map<DiagnosticsCategory, CheckBox> mCategoryCheckBoxes = new EnumMap<>(DiagnosticsCategory.class);
    private VBox mEditorPane;
    private CheckBox mMasterAllToggle;

    public DiagnosticsPreferenceEditor(UserPreferences userPreferences)
    {
        mDiagnosticsPreference = userPreferences.getDiagnosticsPreference();
        setMaxWidth(Double.MAX_VALUE);

        VBox vbox = new VBox();
        vbox.setMaxHeight(Double.MAX_VALUE);
        vbox.setMaxWidth(Double.MAX_VALUE);
        vbox.getChildren().add(getEditorPane());
        HBox.setHgrow(vbox, Priority.ALWAYS);
        getChildren().add(vbox);
    }

    private VBox getEditorPane()
    {
        if(mEditorPane == null)
        {
            mEditorPane = new VBox();
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));
            mEditorPane.setSpacing(8);
            mEditorPane.setMaxWidth(Double.MAX_VALUE);

            Label header = new Label("Runtime Diagnostics");
            header.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
            mEditorPane.getChildren().add(header);

            Label description = new Label(
                "Enable DEBUG logging for individual subsystems without editing logback.xml. "
              + "Changes take effect immediately.");
            description.setWrapText(true);
            mEditorPane.getChildren().add(description);

            Label warning = new Label(
                "Warning: enabling everything at once generates extremely large log files "
              + "(hundreds of megabytes per day with P25 traffic). Only turn on the categories "
              + "you are actively debugging.");
            warning.setWrapText(true);
            warning.setTextFill(Color.DARKRED);
            mEditorPane.getChildren().add(warning);

            mEditorPane.getChildren().add(new Separator(Orientation.HORIZONTAL));

            mMasterAllToggle = new CheckBox("Enable ALL diagnostics categories");
            mMasterAllToggle.setOnAction(event -> {
                boolean enable = mMasterAllToggle.isSelected();
                for(DiagnosticsCategory category : DiagnosticsCategory.values())
                {
                    CheckBox box = mCategoryCheckBoxes.get(category);
                    if(box != null)
                    {
                        box.setSelected(enable);
                    }
                    mDiagnosticsPreference.setEnabled(category, enable);
                    LogLevelController.apply(category, enable);
                }
            });
            mEditorPane.getChildren().add(mMasterAllToggle);

            mEditorPane.getChildren().add(new Separator(Orientation.HORIZONTAL));

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(6);
            grid.setPadding(new Insets(4, 0, 4, 10));

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(40);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(c1, c2);

            int row = 0;
            for(DiagnosticsCategory category : DiagnosticsCategory.values())
            {
                CheckBox box = new CheckBox(category.getDisplayName());
                box.setSelected(mDiagnosticsPreference.isEnabled(category));
                box.selectedProperty().addListener((obs, oldVal, newVal) -> {
                    mDiagnosticsPreference.setEnabled(category, newVal);
                    LogLevelController.apply(category, newVal);
                    syncMasterToggle();
                });
                mCategoryCheckBoxes.put(category, box);
                grid.add(box, 0, row);
                Label loggerLabel = new Label(category.getLoggerName());
                loggerLabel.setStyle("-fx-text-fill: gray; -fx-font-family: monospace;");
                grid.add(loggerLabel, 1, row);
                row++;
            }

            mEditorPane.getChildren().add(grid);
            syncMasterToggle();
        }

        return mEditorPane;
    }

    private void syncMasterToggle()
    {
        if(mMasterAllToggle == null)
        {
            return;
        }
        boolean allOn = true;
        for(DiagnosticsCategory category : DiagnosticsCategory.values())
        {
            if(!mDiagnosticsPreference.isEnabled(category))
            {
                allOn = false;
                break;
            }
        }
        mMasterAllToggle.setSelected(allOn);
    }
}
