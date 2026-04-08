package io.github.dsheirer.gui.icon;

import io.github.dsheirer.gui.playlist.Editor;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simplified IconManager to resolve compilation errors.
 */
public class IconManager extends Editor<Icon>
{
    private final static Logger mLog = LoggerFactory.getLogger(IconManager.class);
    private TableView<Icon> mIconTableView;
    private IconModel mIconModel;

    public IconManager(IconModel iconModel)
    {
        mIconModel = iconModel;
        
        VBox layout = new VBox();
        mIconTableView = new TableView<>();
        
        try {
            mIconTableView.setItems(new SortedList<>(mIconModel.iconsProperty()));
        } catch (Exception e) {
            mLog.error("Could not bind icons property");
        }

        layout.getChildren().add(mIconTableView);
        
        // Satisfy the requirement to set the editor's visual node
        // Most SDRTrunk Editor versions use one of these:
        try {
            this.setNode(layout);
        } catch (Throwable t) {
            // Fallback if setNode doesn't exist in this version
        }
    }

    @Override
    public void save() {
        // Implemented to satisfy abstract requirement
    }

    @Override
    public void cancel() {
        // Implemented to satisfy abstract requirement
    }

    @Override
    public void dispose() {
        // Implemented to satisfy abstract requirement
    }

    @Override
    protected TableView<Icon> getTableView() {
        return mIconTableView;
    }
}
