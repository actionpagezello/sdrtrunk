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

package io.github.dsheirer.gui.preference;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.ViewPlaylistRequest;
import io.github.dsheirer.preference.UserPreferences;
import java.util.EnumMap;
import java.util.Map;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.util.Callback;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.layout.Region;

import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preferences editor dialog
 */
public class UserPreferencesEditor extends BorderPane
{
    private final static Logger mLog = LoggerFactory.getLogger(UserPreferencesEditor.class);

    private Map<PreferenceEditorType,Node> mEditors = new EnumMap<>(PreferenceEditorType.class);
    private UserPreferences mUserPreferences;
    private MenuBar mMenuBar;
    private Object mEditorSelectionTreeView;
    private VBox mEditorAndButtonsBox;
    private Node mEditor;
    private HBox mButtonsBox;

    /**
     * Constructs an instance
     *
     * @param userPreferences to edit
     */
    private io.github.dsheirer.icon.IconModel mIconModel;

    /**
     * Constructs an instance
     *
     * @param userPreferences to edit
     */
    public UserPreferencesEditor(UserPreferences userPreferences, io.github.dsheirer.icon.IconModel iconModel)
    {
        mUserPreferences = userPreferences;
        mIconModel = iconModel;

        getStylesheets().add(getClass().getResource("/io/github/dsheirer/gui/sdrtrunk_style.css").toExternalForm());
        getStyleClass().add("preferences-main-area");

        HBox contentBox = new HBox();
        HBox.setHgrow(getEditorAndButtonsBox(), Priority.ALWAYS);

        Node sidebar = getEditorSelectionTreeView(); // We will rename the inner method but keep variable reference
        contentBox.getChildren().addAll(sidebar, getEditorAndButtonsBox());
        setCenter(contentBox);
    }

    private UserPreferences getUserPreferences()
    {
        if(mUserPreferences == null)
        {
            mUserPreferences = new UserPreferences();
        }

        return mUserPreferences;
    }

    /**
     * Shows the editor specified in the request by scrolling the editor view tree to the selected item.
     */
    public void process(ViewUserPreferenceEditorRequest request)
    {
        if(request.getPreferenceType() != null)
        {
            for(Object item : ((ListView)mEditorSelectionTreeView).getItems()) {
                if(item instanceof PreferenceEditorType && item.equals(request.getPreferenceType())) {
                    ((ListView)mEditorSelectionTreeView).getSelectionModel().select(item);
                    break;
                }
            }
        }
    }

    private VBox getEditorAndButtonsBox()
    {
        if(mEditorAndButtonsBox == null)
        {
            mEditorAndButtonsBox = new VBox();
            mEditorAndButtonsBox.getStyleClass().add("preferences-main-area");
            mEditorAndButtonsBox.setPadding(new Insets(20, 20, 20, 20));
            mEditor = getDefaultEditor();
            VBox.setVgrow(getDefaultEditor(), Priority.ALWAYS);
            mEditorAndButtonsBox.getChildren().addAll(getDefaultEditor());
        }

        return mEditorAndButtonsBox;
    }

    private Node getDefaultEditor()
    {
        Node editor = mEditors.get(PreferenceEditorType.DEFAULT);

        if(editor == null)
        {
            VBox defaultEditor = new VBox();
            defaultEditor.setPadding(new Insets(10, 10, 10, 10));
            Label label = new Label("Please select a preference ...");
            defaultEditor.getChildren().add(label);
            mEditors.put(PreferenceEditorType.DEFAULT, defaultEditor);
            editor = defaultEditor;
        }

        return editor;
    }

    /**
     * Preference type selection list
     */
    private Node getEditorSelectionTreeView()
    {
        if(mEditorSelectionTreeView == null)
        {
            ListView<Object> listView = new ListView<>();
            listView.getStyleClass().add("preferences-sidebar");
            listView.setMinWidth(250);

            listView.getItems().addAll(
                "Application",
                PreferenceEditorType.APPLICATION,
                PreferenceEditorType.DIAGNOSTICS,
                PreferenceEditorType.MQTT,
                "Audio",
                PreferenceEditorType.AUDIO_CALL_MANAGEMENT,
                PreferenceEditorType.AUDIO_MP3,
                PreferenceEditorType.AUDIO_OUTPUT,
                PreferenceEditorType.AUDIO_RECORD,
                "CPU",
                PreferenceEditorType.VECTOR_CALIBRATION,
                "Decoder",
                PreferenceEditorType.JMBE_LIBRARY,
                "Display",
                PreferenceEditorType.CHANNEL_EVENT,
                PreferenceEditorType.TALKGROUP_FORMAT,
                "File Storage",
                PreferenceEditorType.DIRECTORY,
                "Source",
                PreferenceEditorType.SOURCE_TUNERS,
                "Icons",
                PreferenceEditorType.ICON_MANAGER
            );

            listView.setCellFactory(new Callback<ListView<Object>, ListCell<Object>>() {
                @Override
                public ListCell<Object> call(ListView<Object> param) {
                    return new ListCell<Object>() {
                        @Override
                        protected void updateItem(Object item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty || item == null) {
                                setText(null);
                                setGraphic(null);
                                getStyleClass().removeAll("preferences-section-header", "preferences-list-item");
                                setMouseTransparent(false);
                                setFocusTraversable(true);
                            } else if (item instanceof String) {
                                setText((String) item);
                                getStyleClass().remove("preferences-list-item");
                                if(!getStyleClass().contains("preferences-section-header")) {
                                    getStyleClass().add("preferences-section-header");
                                }
                                setMouseTransparent(true);
                                setFocusTraversable(false);
                            } else if (item instanceof PreferenceEditorType) {
                                setText(((PreferenceEditorType) item).toString());
                                getStyleClass().remove("preferences-section-header");
                                if(!getStyleClass().contains("preferences-list-item")) {
                                    getStyleClass().add("preferences-list-item");
                                }
                                setMouseTransparent(false);
                                setFocusTraversable(true);
                            }
                        }
                    };
                }
            });

            listView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
            listView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
                if(newValue instanceof PreferenceEditorType) {
                    setEditor((PreferenceEditorType)newValue);
                } else {
                    setEditor(PreferenceEditorType.DEFAULT);
                }
            });

            mEditorSelectionTreeView = (TreeView) (Object) listView; // Using mEditorSelectionTreeView but typing safely locally
        }

        return (Node)mEditorSelectionTreeView;
    }

    /**
     * Control box with OK button.
     */
    private HBox getButtonsBox()
    {
        if(mButtonsBox == null)
        {
            mButtonsBox = new HBox();
            mButtonsBox.setMaxWidth(Double.MAX_VALUE);
            Button okButton = new Button("Ok");
            okButton.setOnAction(event -> {
                Stage stage = (Stage)getButtonsBox().getScene().getWindow();
                stage.close();
            });
            HBox.setMargin(okButton, new Insets(5, 5, 5, 5));
            mButtonsBox.setAlignment(Pos.CENTER_RIGHT);
            mButtonsBox.getChildren().add(okButton);
        }

        return mButtonsBox;
    }

    private MenuBar getMenuBar()
    {
        if(mMenuBar == null)
        {
            mMenuBar = new MenuBar();

            //File Menu
            Menu fileMenu = new Menu("File");

            MenuItem closeItem = new MenuItem("Close");
            closeItem.setOnAction(event -> getMenuBar().getParent().getScene().getWindow().hide());
            fileMenu.getItems().add(closeItem);

            mMenuBar.getMenus().add(fileMenu);

            Menu viewMenu = new Menu("View");
            MenuItem playlistEditorItem = new MenuItem("Playlist Editor");
            playlistEditorItem.setOnAction(event -> MyEventBus.getGlobalEventBus().post(new ViewPlaylistRequest()));
            viewMenu.getItems().add(playlistEditorItem);
            mMenuBar.getMenus().add(viewMenu);
        }

        return mMenuBar;
    }

    private void setEditor(PreferenceEditorType type)
    {
        Node editor = mEditors.get(type);

        if(editor == null)
        {
            if(type == PreferenceEditorType.DEFAULT)
            {
                editor = getDefaultEditor();
            }
            else
            {
                editor = PreferenceEditorFactory.getEditor(type, getUserPreferences(), mIconModel);
                mEditors.put(type, editor);
            }
        }

        getEditorAndButtonsBox().getChildren().remove(mEditor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        mEditor = editor;
        getEditorAndButtonsBox().getChildren().add(0, mEditor);
    }

    /**
     * Listens for editor tree selection events and creates a preference editor instance for each type as needed.
     *
     * Constructed editors are cached for reuse.
     */

}
