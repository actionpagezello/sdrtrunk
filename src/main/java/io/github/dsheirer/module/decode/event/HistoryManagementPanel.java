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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.filter.FilterEditor;
import io.github.dsheirer.filter.FilterSet;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.function.Consumer;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

/**
 * History management panel with controls for managing item histories.
 */
public class HistoryManagementPanel<T> extends JPanel
{
    private ClearableHistoryModel mModel;
    private FilterSet<T> mFilterSet;
    private FilterEditor<T> mFilterEditor;
    private JButton mClearButton;
    private JButton mFilterButton;
    private JSlider mHistorySlider;
    private JLabel mHistoryTitleLabel;
    private JLabel mHistoryValueLabel;
    private String mFilterEditorTitle;

    /**
     * Optional callback invoked (on the slider's change listener) each time
     * the user moves the slider, so the caller can persist the new value.
     */
    private Consumer<Integer> mHistorySizeChangedCallback;

    /**
     * Constructs an instance using the model's current history size as the
     * initial slider position.  No persistence callback is installed.
     *
     * @param model             to manage
     * @param filterEditorTitle title for the filter editor dialog
     */
    public HistoryManagementPanel(ClearableHistoryModel model, String filterEditorTitle)
    {
        this(model, filterEditorTitle, model.getHistorySize(), null);
    }

    /**
     * Constructs an instance with a caller-supplied initial slider value and
     * an optional callback for persistence.
     *
     * @param model                      to manage
     * @param filterEditorTitle          title for the filter editor dialog
     * @param initialHistorySize         slider starting position (from saved prefs)
     * @param historySizeChangedCallback called with the new value every time
     *                                   the slider moves; may be {@code null}
     */
    public HistoryManagementPanel(ClearableHistoryModel model, String filterEditorTitle,
                                  int initialHistorySize, Consumer<Integer> historySizeChangedCallback)
    {
        mModel = model;
        mFilterEditorTitle = filterEditorTitle;
        mHistorySizeChangedCallback = historySizeChangedCallback;

        // Apply the persisted size to the model before the slider is built so
        // getHistorySlider() picks up the right initial value.
        if(initialHistorySize != model.getHistorySize())
        {
            model.setHistorySize(initialHistorySize);
        }

        setLayout(new MigLayout("insets 6 1 5 5", "[]5[]10[]5[]5[][grow]", ""));
        add(getFilterButton());
        add(getClearButton());
        add(getHistoryTitleLabel());
        add(getHistorySlider());
        add(getHistoryValueLabel());
        setEnabled(false);
    }

    /**
     * Updates the filter set
     * @param filterSet to use
     */
    public void updateFilterSet(FilterSet<T> filterSet)
    {
        mFilterSet = filterSet;

        if(mFilterEditor != null)
        {
            getFilterEditor().updateFilterSet(filterSet);
        }
    }

    /**
     * Overrides the panel method to also set the enabled state for the child controls.
     * @param enabled true if this component should be enabled, false otherwise
     */
    @Override
    public void setEnabled(boolean enabled)
    {
        super.setEnabled(enabled);
        getClearButton().setEnabled(enabled);
        getFilterButton().setEnabled(enabled);
        getHistoryValueLabel().setEnabled(enabled);
        getHistoryTitleLabel().setEnabled(enabled);
        getHistorySlider().setEnabled(enabled);
    }

    /**
     * Filter editor
     * @return editor lazily constructed
     */
    private FilterEditor<T> getFilterEditor()
    {
        if(mFilterEditor == null)
        {
            mFilterEditor = new FilterEditor<>(mFilterEditorTitle, getFilterButton(), mFilterSet);
        }

        return mFilterEditor;
    }

    /**
     * Filter button for accessing the filter editor
     * @return filter button
     */
    private JButton getFilterButton()
    {
        if(mFilterButton == null)
        {
            mFilterButton = new JButton("Filters");
            mFilterButton.setToolTipText("Edit filters");
            mFilterButton.addActionListener(arg0 -> EventQueue.invokeLater(() -> getFilterEditor().setVisible(true)));
        }

        return mFilterButton;
    }

    /**
     * Clear button
     * @return clear button
     */
    private JButton getClearButton()
    {
        if(mClearButton == null)
        {
            mClearButton = new JButton("Clear");
            mClearButton.addActionListener(e -> mModel.clear());
            mClearButton.setToolTipText("Clears the history");
        }

        return mClearButton;
    }

    /**
     * History value label.
     * @return label
     */
    private JLabel getHistoryValueLabel()
    {
        if(mHistoryValueLabel == null)
        {
            mHistoryValueLabel = new JLabel(String.valueOf(mModel.getHistorySize()));
        }

        return mHistoryValueLabel;
    }

    /**
     * History title label
     * @return label
     */
    private JLabel getHistoryTitleLabel()
    {
        if(mHistoryTitleLabel == null)
        {
            mHistoryTitleLabel = new JLabel("History:");
        }

        return mHistoryTitleLabel;
    }

    /**
     * History value slider control
     * @return slider
     */
    private JSlider getHistorySlider()
    {
        if(mHistorySlider == null)
        {
            mHistorySlider = new JSlider();
            mHistorySlider.setToolTipText("Adjust history size.  Double-click to reset to default 200");
            mHistorySlider.setMinimum(0);
            mHistorySlider.setMaximum(2000);
            mHistorySlider.setMinorTickSpacing(25);
            mHistorySlider.setMajorTickSpacing(500);
            mHistorySlider.setPaintTicks(false);
            mHistorySlider.setPaintLabels(false);
            mHistorySlider.addMouseListener(new MouseListener()
            {
                @Override
                public void mouseClicked(MouseEvent arg0)
                {
                    if(SwingUtilities.isLeftMouseButton(arg0) && arg0.getClickCount() == 2)
                    {
                        mHistorySlider.setValue(ClearableHistoryModel.DEFAULT_HISTORY_SIZE);
                    }
                }

                public void mouseEntered(MouseEvent arg0) {}
                public void mouseExited(MouseEvent arg0) {}
                public void mousePressed(MouseEvent arg0) {}
                public void mouseReleased(MouseEvent arg0) {}
            });

            // Initialise to whatever size the model currently holds (may have
            // already been set from persisted prefs in the constructor).
            mHistorySlider.setValue(mModel.getHistorySize());

            mHistorySlider.addChangeListener(e -> {
                int size = mHistorySlider.getValue();
                mModel.setHistorySize(size);
                getHistoryValueLabel().setText(String.valueOf(size));

                // Persist the new value if a callback was supplied.
                if(mHistorySizeChangedCallback != null)
                {
                    mHistorySizeChangedCallback.accept(size);
                }
            });
        }

        return mHistorySlider;
    }
}
