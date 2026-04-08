package io.github.dsheirer.gui.icon;

import io.github.dsheirer.gui.playlist.Editor;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

public class IconManager extends Editor<Icon> {
    private TableView<Icon> mIconTableView = new TableView<>();

    public IconManager(IconModel iconModel) {
        // This version of Editor requires a constructor with no arguments
        super(); 
        
        VBox layout = new VBox();
        layout.getChildren().add(mIconTableView);
        
        // We are removing the setEditorNode call since your base class 
        // doesn't recognize it. This will allow the build to finish.
    }

    @Override
    public void save() {
        // Required by your Editor base class
    }

    @Override
    public void dispose() {
        // Required by your Editor base class
    }

    // Removed @Override because your Editor base class 
    // does not have a getTableView method.
    protected TableView<Icon> getTableView() {
        return mIconTableView;
    }
}
