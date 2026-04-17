/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.gui.preference.application;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.controlsfx.control.ToggleSwitch;


/**
 * Preference settings for application
 */
public class ApplicationPreferenceEditor extends HBox
{
    private ApplicationPreference mApplicationPreference;
    private GridPane mEditorPane;
    private Label mAutoStartTimeoutLabel;
    private Spinner<Integer> mTimeoutSpinner;
    private Label mMemoryLimitLabel;
    private ComboBox<MemoryOption> mMemoryComboBox;
    private Label mMemoryWarningLabel;
    private ToggleSwitch mAutomaticDiagnosticMonitoringToggle;

    /**
     * Constructs an instance
     * @param userPreferences for obtaining reference to preference.
     */
    public ApplicationPreferenceEditor(UserPreferences userPreferences)
    {
        mApplicationPreference = userPreferences.getApplicationPreference();
        setMaxWidth(Double.MAX_VALUE);

        VBox vbox = new VBox();
        vbox.setMaxHeight(Double.MAX_VALUE);
        vbox.setMaxWidth(Double.MAX_VALUE);
        vbox.getChildren().add(getEditorPane());
        HBox.setHgrow(vbox, Priority.ALWAYS);
        getChildren().add(vbox);
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            int row = 0;
            mEditorPane = new GridPane();
            mEditorPane.setMaxWidth(Double.MAX_VALUE);
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(3);
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));

            Label monitoringLabel = new Label("Application Health and Diagnostic Monitoring.");
            mEditorPane.add(monitoringLabel, 0, row, 2, 1);
            GridPane.setHalignment(getAutomaticDiagnosticMonitoringToggle(), HPos.RIGHT);
            mEditorPane.add(getAutomaticDiagnosticMonitoringToggle(), 0, ++row);
            mEditorPane.add(new Label("Enable Diagnostic Monitoring"), 1, row, 2, 1);

            Separator separator = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(separator, Priority.ALWAYS);
            mEditorPane.add(separator, 0, ++row, 3, 1);

            mEditorPane.add(getAutoStartTimeoutLabel(), 0, ++row, 2, 1);
            GridPane.setHalignment(getTimeoutSpinner(), HPos.RIGHT);
            mEditorPane.add(getTimeoutSpinner(), 0, ++row);
            mEditorPane.add(new Label("seconds"), 1, row);

            Separator separator2 = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(separator2, Priority.ALWAYS);
            mEditorPane.add(separator2, 0, ++row, 3, 1);

            mEditorPane.add(getMemoryLimitLabel(), 0, ++row, 2, 1);
            GridPane.setHalignment(getMemoryComboBox(), HPos.RIGHT);
            mEditorPane.add(getMemoryComboBox(), 0, ++row, 2, 1);

            GridPane.setHalignment(getMemoryWarningLabel(), HPos.RIGHT);
            mEditorPane.add(getMemoryWarningLabel(), 0, ++row, 3, 1);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setPercentWidth(30);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
        }

        return mEditorPane;
    }

    private Label getAutoStartTimeoutLabel()
    {
        if(mAutoStartTimeoutLabel == null)
        {
            mAutoStartTimeoutLabel = new Label("Channel Auto-Start Timeout");
        }

        return mAutoStartTimeoutLabel;
    }

    /**
     * Spinner to select channel auto-start timeout value in range 0-30 seconds.
     * @return spinner
     */
    private Spinner<Integer> getTimeoutSpinner()
    {
        if(mTimeoutSpinner == null)
        {
            mTimeoutSpinner = new Spinner<>(0, 30, mApplicationPreference.getChannelAutoStartTimeout(), 1);
            mTimeoutSpinner.valueProperty().addListener((observable, oldValue, newValue) -> mApplicationPreference.setChannelAutoStartTimeout(newValue));
        }

        return mTimeoutSpinner;
    }

    /**
     * Toggle switch to enable/disable automatic diagnostic monitoring.
     */
    private ToggleSwitch getAutomaticDiagnosticMonitoringToggle()
    {
        if(mAutomaticDiagnosticMonitoringToggle == null)
        {
            mAutomaticDiagnosticMonitoringToggle = new ToggleSwitch();
            mAutomaticDiagnosticMonitoringToggle.setSelected(mApplicationPreference.isAutomaticDiagnosticMonitoring());
            mAutomaticDiagnosticMonitoringToggle.selectedProperty().addListener((observable, oldValue, enabled) ->
                    mApplicationPreference.setAutomaticDiagnosticMonitoring(enabled));
        }

        return mAutomaticDiagnosticMonitoringToggle;
    }
    private Label getMemoryLimitLabel()
    {
        if(mMemoryLimitLabel == null)
        {
            mMemoryLimitLabel = new Label("Allocated Memory");
        }

        return mMemoryLimitLabel;
    }


    public static class MemoryOption {
        private final int value;
        private final String label;

        public MemoryOption(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public int getValue() {
            return value;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            MemoryOption that = (MemoryOption) obj;
            return value == that.value;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }
    }

    private ComboBox<MemoryOption> getMemoryComboBox()
    {
        if(mMemoryComboBox == null)
        {
            mMemoryComboBox = new ComboBox<>();
            mMemoryComboBox.setMinWidth(Region.USE_PREF_SIZE);

            long maxMemoryBytes = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
            long systemMaxMemoryGb = Math.round(maxMemoryBytes / (1024.0 * 1024.0 * 1024.0));
            long maxAllowed = Math.min(systemMaxMemoryGb, 32);

            List<MemoryOption> options = new ArrayList<>();
            int[] powersOfTwo = {2, 4, 8, 16, 32};

            for (int gb : powersOfTwo) {
                if (gb <= maxAllowed) {
                    String label = gb + " GB";
                    if (gb == 2) {
                        label += " - Recommended for 1 tuner";
                    } else if (gb == 4) {
                        label += " - Recommended for 2-3 tuners";
                    } else if (gb >= 8) {
                        label += " - Recommended for 4+ tuners";
                    }
                    options.add(new MemoryOption(gb, label));
                }
            }

            // In case maxAllowed is less than 2GB or not a power of 2, add it if missing and sensible
            if (options.isEmpty() || maxAllowed < 2) {
                 options.add(new MemoryOption((int) Math.max(1, maxAllowed), Math.max(1, maxAllowed) + " GB"));
            } else if (!options.stream().anyMatch(opt -> opt.getValue() == maxAllowed) && maxAllowed > options.get(options.size() - 1).getValue()) {
                 options.add(new MemoryOption((int) maxAllowed, maxAllowed + " GB"));
            }

            mMemoryComboBox.getItems().addAll(options);

            int currentMemory = mApplicationPreference.getAllocatedMemory();
            MemoryOption currentOption = new MemoryOption(currentMemory, currentMemory + " GB");

            if (!mMemoryComboBox.getItems().contains(currentOption)) {
                // Insert it in sorted order
                int index = 0;
                for (int i = 0; i < options.size(); i++) {
                    if (currentMemory < options.get(i).getValue()) {
                        break;
                    }
                    index++;
                }
                mMemoryComboBox.getItems().add(index, currentOption);
            }

            mMemoryComboBox.getSelectionModel().select(currentOption);

            mMemoryComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue != null) {
                    mApplicationPreference.setAllocatedMemory(newValue.getValue());
                }
            });
        }

        return mMemoryComboBox;
    }

    private Label getMemoryWarningLabel()
    {
        if(mMemoryWarningLabel == null)
        {
            mMemoryWarningLabel = new Label("A restart of SDRTrunk is required for this setting to take effect.");
            mMemoryWarningLabel.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
        }

        return mMemoryWarningLabel;
    }
}
