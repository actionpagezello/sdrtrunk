/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
 * along with this program.  If not, see [http://www.gnu.org/licenses/](http://www.gnu.org/licenses/)
 * ****************************************************************************
 */

package io.github.dsheirer.gui.icon;

import io.github.dsheirer.gui.playlist.Editor;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.util.OSType;
import javafx.beans.binding.Bindings;
import javafx.collections.transformation.SortedList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides an editable list of icons for adding, removing or modifying
 * user-provided icon images.
 *
 * @author Dennis Sheirer
 */
public class IconManager extends Editor<Icon>
{
    private final static Logger mLog = LoggerFactory.getLogger(IconManager.class);

    private TableView<Icon> mIconTableView;
    private IconModel mIconModel;
    private FileChooser mFileChooser;
    private TextField mName;
    private Button mPathButton;
    private SortedList<Icon> mIconSortedList;

    /**
     * Constructs an instance
     *
     * @param iconModel provider
     */
    public IconManager(IconModel iconModel)
    {
        super("Icon Editor", 400, 300);
        mIconModel = iconModel;

        VBox layout = new VBox();

        layout.getChildren().add(getIconTableView());

        VBox.setVgrow(getIconTableView(), Priority.ALWAYS);

        GridPane editPanel = new GridPane();
        editPanel.setHgap(5.0);
        editPanel.setVgap(5.0);

        Label nameLabel = new Label("Name");
        editPanel.add(nameLabel, 0, 0);

        mName = new TextField();
        mName.textProperty().addListener((observable, oldValue, newValue) -> {
            if(isSaving())
            {
                return;
            }

            if(newValue != null)
            {
                String name = newValue.trim();

                if(name.isEmpty())
                {
                    setEditorStatus("Name required", true);
                }
                else
                {
                    setEditorStatus(null, false);
                }
            }
            else
            {
                setEditorStatus("Name required", true);
            }
        });

        GridPane.setHgrow(mName, Priority.ALWAYS);
        editPanel.add(mName, 1, 0);

        Label fileLabel = new Label("Path");
        GridPane.setHalignment(fileLabel, HPos.RIGHT);
        editPanel.add(fileLabel, 0, 1);

        mPathButton = new Button("Select File ...");
        mPathButton.setOnAction(event -> {
            if(mFileChooser == null)
            {
                mFileChooser = new FileChooser();
                mFileChooser.setTitle("Select Icon Image File");

                mFileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Image Files", "*.png"));
            }

            File file = mFileChooser.showOpenDialog(IconManager.this.getScene().getWindow());

            if(file != null)
            {
                mPathButton.setText(file.getAbsolutePath());
                setEditorStatus(null, false);
            }
            else
            {
                mPathButton.setText("Select File ...");
                setEditorStatus("Icon image file required", true);
            }
        });
        mPathButton.setMinWidth(250.0);
        mPathButton.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(mPathButton, Priority.ALWAYS);
        editPanel.add(mPathButton, 1, 1);


        HBox buttonPanel = new HBox();
        buttonPanel.setSpacing(5.0);
        buttonPanel.setPadding(new Insets(10.0, 10.0, 10.0, 10.0));
        buttonPanel.setAlignment(Pos.CENTER);

        buttonPanel.getChildren().addAll(getNewButton(), getEditButton(), getSaveButton(),
                getDeleteButton(), getCancelButton());

        layout.getChildren().addAll(editPanel, buttonPanel);

        //Add margin to editor status label
        VBox.setMargin(getEditorStatusLabel(), new Insets(0, 10.0, 0, 10.0));
        layout.getChildren().add(getEditorStatusLabel());

        setSceneNode(layout);

        setEditing(false);
    }

    private SortedList<Icon> getIconSortedList()
    {
        if(mIconSortedList == null)
        {
            mIconSortedList = new SortedList<>(mIconModel.iconsProperty(), (o1, o2) -> {
                if(o1 != null && o2 != null)
                {
                    return o1.getName().compareTo(o2.getName());
                }

                if(o1 != null)
                {
                    return -1;
                }

                if(o2 != null)
                {
                    return 1;
                }

                return 0;
            });
        }

        return mIconSortedList;
    }

    private TableView<Icon> getIconTableView()
    {
        if(mIconTableView == null)
        {
            mIconTableView = new TableView<>(getIconSortedList());
            mIconTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

            TableColumn<Icon,String> iconColumn = new TableColumn("Icon");
            iconColumn.setCellValueFactory(new PropertyValueFactory<>("path"));
            iconColumn.setCellFactory(new IconTableCellFactory());
            iconColumn.setPrefWidth(50);

            TableColumn<Icon,String> nameColumn = new TableColumn("Name");
            nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
            nameColumn.setPrefWidth(200);

            TableColumn<Icon,Boolean> typeColumn = new TableColumn("Type");
            typeColumn.setPrefWidth(100);
            typeColumn.setCellValueFactory(new PropertyValueFactory<>("defaultIcon"));
            typeColumn.setCellFactory(param -> {
                TableCell<Icon, Boolean> tableCell = new TableCell<>()
                {
                    @Override
                    protected void updateItem(Boolean item, boolean empty)
                    {
                        if(item != null)
                        {
                            if(item)
                            {
                                setText("Default");
                            }
                            else
                            {
                                Icon icon = getIconTableView().getItems().get(getIndex());

                                if(icon.getStandardIcon())
                                {
                                    setText("Standard");
                                }
                                else
                                {
                                    setText("User");
                                }
                            }
                        }
                        else
                        {
                            setText("");
                        }
                    }
                };

                return tableCell;
            });

            mIconTableView.getColumns().addAll(typeColumn, iconColumn, nameColumn);
            mIconTableView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {

                getEditButton().setDisable(newValue == null || newValue.getDefaultIcon() || newValue.getStandardIcon());
                getDeleteButton().setDisable(newValue == null || newValue.getDefaultIcon() || newValue.getStandardIcon());

                if(isEditing())
                {
                    setEditing(false);
                }

                populateEditor(newValue);
            });

            getIconSortedList().comparatorProperty().bind(mIconTableView.comparatorProperty());

            mIconTableView.getSelectionModel().selectFirst();
        }

        return mIconTableView;
    }

    /**
     * Resets the editor interface when the cancel button is clicked
     */
    @Override
    protected void doCancel()
    {
        populateEditor(getIconTableView().getSelectionModel().getSelectedItem());
        setEditing(false);
    }

    /**
     * Executes when the new button is clicked.
     */
    @Override
    protected void doNew()
    {
        setEditedItem(null);
        populateEditor(null);
        mName.requestFocus();
        setEditing(true);
    }

    /**
     * Provides the icon tableview for binding disable properties to the edited item
     */
    @Override
    protected TableView<Icon> getTableView()
    {
        return getIconTableView();
    }

    /**
     * Executes when the save button is clicked.  Saves the new or modified item.
     */
    @Override
    protected void doSave()
    {
        if(isEditing())
        {
            setSaving(true);

            String name = mName.getText().trim();
            String path = mPathButton.getText();

            File file = new File(path);

            Path sourceFile = null;

            if(file.exists() && file.isFile())
            {
                sourceFile = file.toPath();
            }

            if(getEditedItem() == null)
            {
                if(sourceFile != null)
                {
                    try
                    {
                        mIconModel.createIcon(name, sourceFile);

                        //Force the table to resort so the newly added icon appears.
                        getIconTableView().sort();
                    }
                    catch (Exception e)
                    {
                        mLog.error("Error creating new icon", e);
                        setEditorStatus("Error creating new icon - see application log", true);
                    }
                }
                else
                {
                    setEditorStatus("Can't find source file", true);
                }
            }
            else
            {
                Icon editedIcon = getEditedItem();

                try
                {
                    editedIcon.setName(name);

                    if(!editedIcon.getPath().contentEquals(path) && sourceFile != null)
                    {
                        mIconModel.updateIconImage(editedIcon, sourceFile);
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Error updating icon", e);
                    setEditorStatus("Error updating icon - see application log", true);
                }
            }

            setEditing(false);
            setSaving(false);
        }
    }

    /**
     * Executes when the delete button is clicked
     */
    @Override
    protected void doDelete()
    {
        Icon icon = getIconTableView().getSelectionModel().getSelectedItem();

        if(icon != null)
        {
            try
            {
                mIconModel.deleteIcon(icon);
            }
            catch(Exception e)
            {
                mLog.error("Error deleting icon", e);
            }
        }
    }

    /**
     * Setup the editor panel controls
     *
     * @param editing state
     */
    @Override
    protected void setEditorControlsEditing(boolean editing)
    {
        mName.setDisable(!editing);
        mPathButton.setDisable(!editing);
    }

    /**
     * Setup bindings on the editor panel controls
     */
    @Override
    protected void setupEditorControlsBindings()
    {
        getDeleteButton().disableProperty().bind(Bindings.isNull(getIconTableView().getSelectionModel().selectedItemProperty()));

        getSaveButton().disableProperty().bind(Bindings.or(Bindings.isEmpty(mName.textProperty()),
                Bindings.equal("Select File ...", mPathButton.textProperty())));
    }

    /**
     * Populates the editor controls with the property values of the selected item
     * @param selectedItem to edit
     */
    private void populateEditor(Icon selectedItem)
    {
        if(selectedItem != null)
        {
            mName.setText(selectedItem.getName());

            if(selectedItem.getDefaultIcon())
            {
                mPathButton.setText("Default");
            }
            else
            {
                Path path = mIconModel.getIconPath(selectedItem);

                if(path != null && Files.exists(path))
                {
                    mPathButton.setText(path.toFile().getAbsolutePath());
                }
                else
                {
                    mPathButton.setText("Unknown");
                }
            }
        }
        else
        {
            mName.setText("");
            mPathButton.setText("Select File ...");
        }
    }

    /**
     * Produces an image view cell representing the icon file
     */
    public class IconTableCellFactory implements Callback<TableColumn<Icon, String>, TableCell<Icon, String>>
    {
        @Override
        public TableCell<Icon, String> call(TableColumn<Icon, String> param)
        {
            TableCell<Icon, String> cell = new TableCell<>()
            {
                ImageView mImageView = new ImageView();

                @Override
                protected void updateItem(String item, boolean empty)
                {
                    if(item != null && !empty)
                    {
                        Icon icon = getIconTableView().getItems().get(getIndex());

                        if(icon != null)
                        {
                            if(OSType.getOSType() == OSType.WINDOWS)
                            {
                                mImageView.setImage(mIconModel.getIconImageWindows(icon));
                            }
                            else
                            {
                                mImageView.setImage(mIconModel.getIconImage(icon));
                            }

                            setGraphic(mImageView);
                        }
                        else
                        {
                            setGraphic(null);
                        }
                    }
                    else
                    {
                        setGraphic(null);
                    }
                }
            };

            cell.setAlignment(Pos.CENTER);
            return cell;
        }
    }
}
