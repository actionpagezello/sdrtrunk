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
package io.github.dsheirer.source.tuner.ui;

import io.github.dsheirer.source.tuner.TunerController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-12): Gain Check window.  Runs a GainSweep, shows each setting's measurements and recommends one.
 */
public class GainCheckDialog extends JDialog
{
    private static final Logger LOG = LoggerFactory.getLogger(GainCheckDialog.class);
    private static final Color RECOMMENDED = new Color(200, 235, 200);
    private static final Color OVERLOADED = new Color(245, 205, 205);

    private final GainSteps mSteps;
    private final TunerController mController;
    private final String mTunerName;
    private final ResultsModel mModel = new ResultsModel();
    private final JLabel mProgressLabel = new JLabel(" ");
    private final JTextArea mRecommendationText = new JTextArea(3, 50);
    private final JButton mStartButton = new JButton("Start");
    private final JButton mStopButton = new JButton("Stop");
    private final JButton mApplyButton = new JButton("Apply Recommended");
    private GainSweep mSweep;
    private int mRecommendedIndex = -1;
    private int mOriginalIndex = -1;
    private io.github.dsheirer.source.tuner.TunerSignalMonitor.History mHistory;

    public GainCheckDialog(Window owner, String tunerName, GainSteps steps, TunerController controller)
    {
        super(owner, "Gain Check - " + tunerName, ModalityType.MODELESS);
        mSteps = steps;
        mController = controller;
        mTunerName = tunerName;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new MigLayout("wrap 1,fill", "[grow,fill]", "[][][grow,fill][][]"));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTextArea intro = new JTextArea("Steps " + steps.getControlName().toLowerCase() + " through all " +
            steps.count() + " settings, about 3 seconds each, and measures the noise floor, the strongest signals and " +
            "overload at each.  The tuner's channels hear the gain changes while it runs.  Your setting (" +
            steps.label(steps.current()) + ") is put back at the end unless you apply the recommendation.  For the " +
            "best result, run it while a busy channel (a control channel is best) is on this tuner, after the tuner " +
            "has run at its setting for at least 5 minutes: the recommendation also uses how the tuner did at its " +
            "setting over the last 30 minutes.");
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setOpaque(false);
        content.add(intro);

        JPanel buttons = new JPanel(new MigLayout("insets 0", "[][][grow,fill]", ""));
        buttons.add(mStartButton);
        buttons.add(mStopButton);
        buttons.add(mProgressLabel);
        content.add(buttons);

        JTable table = new JTable(mModel);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, 300));
        content.add(scroll);

        mRecommendationText.setEditable(false);
        mRecommendationText.setLineWrap(true);
        mRecommendationText.setWrapStyleWord(true);
        content.add(new JScrollPane(mRecommendationText));

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new MigLayout("insets 0", "[grow][][]", ""));
        bottom.add(new JLabel(""));
        bottom.add(mApplyButton);
        bottom.add(close);
        content.add(bottom);

        mStopButton.setEnabled(false);
        mApplyButton.setEnabled(false);
        mStartButton.addActionListener(e -> start());
        mStopButton.addActionListener(e -> {
            if(mSweep != null)
            {
                mSweep.cancel();
                mProgressLabel.setText("Stopping - putting your setting back...");
            }
        });
        mApplyButton.addActionListener(e -> {
            if(mRecommendedIndex >= 0)
            {
                mSteps.apply(mRecommendedIndex);
                mProgressLabel.setText("Applied " + mSteps.label(mRecommendedIndex) + " and saved.");
                LOG.info("Gain check [" + mTunerName + "]: applied recommended " + mSteps.getControlName() + " " +
                    mSteps.label(mRecommendedIndex));
                mApplyButton.setEnabled(false);
            }
        });

        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
    }

    @Override
    public void dispose()
    {
        if(mSweep != null)
        {
            mSweep.cancel();
        }

        super.dispose();
    }

    private void start()
    {
        if(!mSteps.isAvailable())
        {
            mRecommendationText.setText(mSteps.getUnavailableReason());
            return;
        }

        mOriginalIndex = mSteps.current();
        //ap-12: the tuner's history at its own setting, from before the check changes it
        mHistory = mController.getSignalMonitor().getHistory();
        mModel.clear();
        mRecommendedIndex = -1;
        mRecommendationText.setText("");
        mStartButton.setEnabled(false);
        mStopButton.setEnabled(true);
        mApplyButton.setEnabled(false);
        mSweep = new GainSweep(mSteps, mController.getNoiseFloorEstimator(), mController.getSignalMonitor());
        GainSweep sweep = mSweep;
        LOG.info("Gain check [" + mTunerName + "]: started, " + mSteps.getControlName() + " " +
            mSteps.label(mSteps.current()));

        Thread thread = new Thread(() -> {
            List<GainSweep.StepResult> results = new ArrayList<>();
            String error = null;

            try
            {
                results = sweep.run(new GainSweep.Listener()
                {
                    @Override
                    public void stepStarted(int step, int of, String label)
                    {
                        SwingUtilities.invokeLater(() -> mProgressLabel.setText("Measuring " + label + " (" + step +
                            " of " + of + ")..."));
                    }

                    @Override
                    public void stepMeasured(GainSweep.StepResult result)
                    {
                        SwingUtilities.invokeLater(() -> mModel.add(result));
                    }
                });
            }
            catch(Exception e)
            {
                LOG.error("Gain check [" + mTunerName + "] failed", e);
                error = e.getMessage();
            }

            List<GainSweep.StepResult> finalResults = results;
            String finalError = error;
            boolean cancelled = sweep.isCancelled();

            SwingUtilities.invokeLater(() -> finished(finalResults, finalError, cancelled));
        }, "sdrtrunk gain check");
        thread.setDaemon(true);
        thread.start();
    }

    private void finished(List<GainSweep.StepResult> results, String error, boolean cancelled)
    {
        mStartButton.setEnabled(true);
        mStopButton.setEnabled(false);
        mProgressLabel.setText((cancelled ? "Stopped" : "Done") + " - your setting " +
            mSteps.label(mSteps.current()) + " is back.");

        if(error != null)
        {
            mRecommendationText.setText("The gain check failed: " + error);
            return;
        }

        if(cancelled)
        {
            mRecommendationText.setText("Stopped before the end.  Start it again for a recommendation.");
            return;
        }

        GainSweep.Recommendation recommendation = GainSweep.recommend(results, mOriginalIndex, mHistory);
        mRecommendedIndex = recommendation.index();
        mModel.setRecommended(recommendation.index());
        mRecommendationText.setText(recommendation.explanation());
        mApplyButton.setEnabled(recommendation.index() >= 0 && recommendation.index() != mSteps.current());
        LOG.info("Gain check [" + mTunerName + "]: " + recommendation.explanation() + summary(results));
    }

    private static String summary(List<GainSweep.StepResult> results)
    {
        StringBuilder sb = new StringBuilder();

        for(GainSweep.StepResult result : results)
        {
            sb.append("\n  ").append(result.label()).append(": noise ")
                .append(String.format("%.1f", result.noiseFloorDb())).append(" dBFS, signals +")
                .append(String.format("%.1f", result.signalDb())).append(" dB, peak ")
                .append(String.format("%.1f", result.peakDbfs())).append(" dBFS, full scale ")
                .append(String.format("%.3f", result.clippedPercent())).append('%');

            if(!Double.isNaN(result.signalSpreadDb()))
            {
                sb.append(", signals varied ").append(String.format("%.1f", result.signalSpreadDb())).append(" dB");
            }

            if(result.overloadEvents() > 0)
            {
                sb.append(", ").append(result.overloadEvents()).append(" tuner overload events");
            }
        }

        return sb.toString();
    }

    private class ResultsModel extends AbstractTableModel
    {
        private final String[] COLUMNS = {"Gain", "Noise Floor", "Strongest Signals", "Peak", "At Full Scale",
            "Result"};
        private final List<GainSweep.StepResult> mResults = new ArrayList<>();
        private int mRecommended = -1;

        void clear()
        {
            mResults.clear();
            mRecommended = -1;
            fireTableDataChanged();
        }

        void add(GainSweep.StepResult result)
        {
            mResults.add(result);
            fireTableRowsInserted(mResults.size() - 1, mResults.size() - 1);
        }

        void setRecommended(int index)
        {
            mRecommended = index;
            fireTableDataChanged();
        }

        GainSweep.StepResult get(int row)
        {
            return mResults.get(row);
        }

        boolean isRecommended(int row)
        {
            return mResults.get(row).index() == mRecommended;
        }

        @Override
        public int getRowCount()
        {
            return mResults.size();
        }

        @Override
        public int getColumnCount()
        {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column)
        {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column)
        {
            GainSweep.StepResult result = mResults.get(row);

            return switch(column)
            {
                case 0 -> result.label() + (result.index() == mOriginalIndex ? " (yours)" : "");
                case 1 -> format(result.noiseFloorDb(), "%.1f dBFS");
                case 2 -> format(result.signalDb(), "+%.1f dB");
                case 3 -> format(result.peakDbfs(), "%.1f dBFS");
                case 4 -> String.format("%.3f%%", result.clippedPercent());
                default -> result.isOverloaded() ? "Overload" : (isRecommended(row) ? "Recommended" : "");
            };
        }

        private String format(double value, String format)
        {
            return Double.isNaN(value) ? "-" : String.format(format, value);
        }
    }

    private class RowRenderer extends DefaultTableCellRenderer
    {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(!isSelected)
            {
                if(mModel.isRecommended(row))
                {
                    component.setBackground(RECOMMENDED);
                    component.setForeground(Color.BLACK);
                }
                else if(mModel.get(row).isOverloaded())
                {
                    component.setBackground(OVERLOADED);
                    component.setForeground(Color.BLACK);
                }
                else
                {
                    component.setBackground(table.getBackground());
                    component.setForeground(table.getForeground());
                }
            }

            return component;
        }
    }
}
