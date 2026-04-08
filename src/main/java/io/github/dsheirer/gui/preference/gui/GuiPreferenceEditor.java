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

package io.github.dsheirer.gui.preference.gui;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.gui.FlatLafTheme;
import io.github.dsheirer.preference.gui.GuiPreference;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Preference editor for GUI / Look-and-Feel settings.
 *
 * The FlatLaf theme setting applies only when SDRTrunk is running on Windows 11.
 * A restart is required after changing the theme.
 */
public class GuiPreferenceEditor extends HBox
{
    private final GuiPreference mGuiPreference;
    private GridPane mEditorPane;
    private ChoiceBox<FlatLafTheme> mThemeChoiceBox;

    public GuiPreferenceEditor(UserPreferences userPreferences)
    {
        mGuiPreference = userPreferences.getGuiPreference();
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
            mEditorPane.setHgap(10);
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));

            Label sectionLabel = new Label("Windows 11 Interface Theme");
            sectionLabel.setStyle("-fx-font-weight: bold;");
            mEditorPane.add(sectionLabel, 0, row, 2, 1);

            Separator sep = new Separator(Orientation.HORIZONTAL);
            GridPane.setHgrow(sep, Priority.ALWAYS);
            mEditorPane.add(sep, 0, ++row, 2, 1);

            mEditorPane.add(new Label("Theme:"), 0, ++row);
            mEditorPane.add(getThemeChoiceBox(), 1, row);

            Label noteLabel = new Label("Note: this setting only applies on Windows 11. A restart is required after changing the theme.");
            noteLabel.setWrapText(true);
            noteLabel.setStyle("-fx-font-style: italic; -fx-text-fill: gray;");
            mEditorPane.add(noteLabel, 0, ++row, 2, 1);

            ColumnConstraints c1 = new ColumnConstraints();
            c1.setMinWidth(80);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            mEditorPane.getColumnConstraints().addAll(c1, c2);
        }

        return mEditorPane;
    }

    private ChoiceBox<FlatLafTheme> getThemeChoiceBox()
    {
        if(mThemeChoiceBox == null)
        {
            mThemeChoiceBox = new ChoiceBox<>();
            mThemeChoiceBox.getItems().addAll(FlatLafTheme.values());
            mThemeChoiceBox.getSelectionModel().select(mGuiPreference.getFlatLafTheme());
            mThemeChoiceBox.setOnAction(event -> {
                FlatLafTheme selected = mThemeChoiceBox.getSelectionModel().getSelectedItem();
                if(selected != null)
                {
                    mGuiPreference.setFlatLafTheme(selected);
                }
            });
        }

        return mThemeChoiceBox;
    }
}
