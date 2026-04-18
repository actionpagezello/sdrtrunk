package io.github.dsheirer.gui.widget;

import io.github.dsheirer.preference.NowPlayingPreference;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WidgetContainer extends JPanel {

    private final NowPlayingPreference mPreference;
    private final List<Widget> mWidgets = new ArrayList<>();

    private Widget mDraggingWidget = null;
    private int mDragStartY;
    private int mDropIndex = -1;

    public WidgetContainer(NowPlayingPreference preference) {
        mPreference = preference;
        setLayout(new MigLayout("wrap 1, insets 0, fillx, gapy 2", "[grow,fill]"));
    }

    public void removeAll() {
        super.removeAll();
        mWidgets.clear();
    }

    public void addWidget(Widget widget, boolean pinned) {
        mWidgets.add(widget);

        if (!pinned) {
            setupDragAndDrop(widget);
        } else {
            widget.setHeaderButtonsVisible(true);
        }

        // Setup initial visibility and minimized states from prefs
        boolean isVisible = mPreference.isWidgetVisible(widget.getId(), true);
        widget.setVisible(isVisible);

        boolean isMinimized = mPreference.isWidgetMinimized(widget.getId(), false);
        widget.setMinimized(isMinimized);
    }

    // Allows the widget container to sort based on preferences once all are added
    public void layoutWidgets(String pinnedWidgetId) {
        // Sort widgets by order preference
        mWidgets.sort((w1, w2) -> {
            if (w1.getId().equals(pinnedWidgetId)) return 1;
            if (w2.getId().equals(pinnedWidgetId)) return -1;

            int order1 = mPreference.getWidgetOrder(w1.getId(), 999);
            int order2 = mPreference.getWidgetOrder(w2.getId(), 999);
            return Integer.compare(order1, order2);
        });

        rebuildLayout();
    }

    private void rebuildLayout() {
        removeAll();
        for (int i = 0; i < mWidgets.size(); i++) {
            Widget w = mWidgets.get(i);

            if (mDraggingWidget != null && mDropIndex == i) {
                // Add a drop indicator placeholder
                JPanel indicator = new JPanel();
                indicator.setBackground(UIManager.getColor("Component.focusColor"));
                indicator.setPreferredSize(new Dimension(0, 4));
                add(indicator, "growx, wrap");
            }

            if (w.isVisible() && w != mDraggingWidget) {
                add(w, "growx");
            }
        }

        if (mDraggingWidget != null && mDropIndex == mWidgets.size()) {
            JPanel indicator = new JPanel();
            indicator.setBackground(UIManager.getColor("Component.focusColor"));
            indicator.setPreferredSize(new Dimension(0, 4));
            add(indicator, "growx, wrap");
        }

        revalidate();
        repaint();
    }

    public void setWidgetVisible(String id, boolean visible) {
        for (Widget w : mWidgets) {
            if (w.getId().equals(id)) {
                w.setVisible(visible);
                mPreference.setWidgetVisible(id, visible);
                rebuildLayout();
                break;
            }
        }
    }

    public void hideWidget(String id) {
        setWidgetVisible(id, false);
    }

    public void onWidgetStateChanged(Widget widget) {
        mPreference.setWidgetMinimized(widget.getId(), widget.isMinimized());
    }

    private void setupDragAndDrop(Widget widget) {
        JPanel header = widget.getHeaderPanel();

        MouseAdapter dragAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                mDraggingWidget = widget;
                mDragStartY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (mDraggingWidget == null) return;

                // Convert mouse position to container coordinates
                Point pt = SwingUtilities.convertPoint(header, e.getPoint(), WidgetContainer.this);

                // Find where we are dropping
                mDropIndex = -1;
                for (int i = 0; i < mWidgets.size(); i++) {
                    Widget w = mWidgets.get(i);
                    // Prevent dropping after the pinned widget (assumed to be last)
                    if (i == mWidgets.size() - 1) { // Assume last is pinned Resource Status
                        if (pt.y < w.getY()) {
                           mDropIndex = i;
                           break;
                        }
                    } else if (w.isVisible() && pt.y < w.getY() + w.getHeight() / 2) {
                        mDropIndex = i;
                        break;
                    }
                }

                if (mDropIndex == -1) {
                    mDropIndex = mWidgets.size() - 1; // Before the pinned widget
                }

                rebuildLayout();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (mDraggingWidget != null && mDropIndex != -1) {
                    // Reorder
                    int originalIndex = mWidgets.indexOf(mDraggingWidget);
                    mWidgets.remove(originalIndex);

                    // Adjust drop index if we removed from before the drop point
                    int insertIndex = mDropIndex;
                    if (originalIndex < insertIndex) {
                        insertIndex--;
                    }

                    if (insertIndex > mWidgets.size() - 1) {
                         insertIndex = mWidgets.size() - 1; // Keep it before the pinned widget
                    }

                    mWidgets.add(insertIndex, mDraggingWidget);

                    // Save new order
                    for (int i = 0; i < mWidgets.size(); i++) {
                        mPreference.setWidgetOrder(mWidgets.get(i).getId(), i);
                    }
                }

                mDraggingWidget = null;
                mDropIndex = -1;
                rebuildLayout();
            }
        };

        header.addMouseListener(dragAdapter);
        header.addMouseMotionListener(dragAdapter);
    }
}
