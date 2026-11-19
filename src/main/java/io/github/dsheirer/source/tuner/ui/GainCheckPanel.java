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

import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerNoiseFloorEstimator;
import io.github.dsheirer.source.tuner.TunerSignalMonitor;
import java.awt.Color;
import java.awt.Font;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;

/**
 * AP-fork (ap-12): signal level meter for a tuner editor, with the Gain Check button.
 *
 * Shows, over the last few seconds: a status (OK, little headroom, overload), the peak sample level, the share of
 * samples at full scale, the noise floor and how far the strongest signals are above it.  It updates once a second
 * while it is on screen; the noise floor measurement runs only then.
 */
public class GainCheckPanel extends JPanel
{
    private static final Color GREEN = new Color(0, 140, 0);
    private static final Color AMBER = new Color(200, 120, 0);
    private static final Color RED = new Color(200, 0, 0);

    private final Supplier<Tuner> mTuner;
    private final Supplier<GainSteps> mGainSteps;
    private final JLabel mStatusLabel = new JLabel("-");
    private final JLabel mPeakLabel = new JLabel("-");
    private final JLabel mClippedLabel = new JLabel("-");
    private final JLabel mNoiseLabel = new JLabel("-");
    private final JLabel mSignalLabel = new JLabel("-");
    private final JButton mGainCheckButton = new JButton("Gain Check...");
    private final Timer mTimer;
    private GainCheckDialog mDialog;

    /**
     * Constructs an instance
     * @param tuner supplies the editor's tuner, or null when there is none
     * @param gainSteps supplies the editor's gain control for the gain check, or null when not supported
     */
    public GainCheckPanel(Supplier<Tuner> tuner, Supplier<GainSteps> gainSteps)
    {
        mTuner = tuner;
        mGainSteps = gainSteps;
        setLayout(new MigLayout("insets 0,wrap 4", "[right][grow,fill][right][grow,fill]", ""));

        mStatusLabel.setFont(mStatusLabel.getFont().deriveFont(Font.BOLD));
        mStatusLabel.setToolTipText("Overload: samples at the top of the tuner's range - strong signals then create " +
            "false signals and raise the noise floor.  Reduce the gain.");
        add(new JLabel("Signal Level:"));
        add(mStatusLabel, "span 2");
        add(mGainCheckButton);

        add(new JLabel("Peak:"));
        add(mPeakLabel);
        mPeakLabel.setToolTipText("Highest sample level over the last 3 seconds.  0 dBFS is the top of the tuner's " +
            "range; above -3 dBFS there is little headroom left.");
        add(new JLabel("At Full Scale:"));
        add(mClippedLabel);
        mClippedLabel.setToolTipText("Share of samples at the top of the tuner's range (clipping) over the last 3 " +
            "seconds.  " + TunerSignalMonitor.REPORT_PERCENT + "% or more is overload.");

        add(new JLabel("Noise Floor:"));
        add(mNoiseLabel);
        mNoiseLabel.setToolTipText("Median power across the tuner's bandwidth, per 25 kHz.  When raising the gain " +
            "raises this by the same amount, more gain no longer helps.");
        add(new JLabel("Strongest Signals:"));
        add(mSignalLabel);
        mSignalLabel.setToolTipText("How far the strongest signals in the tuner's bandwidth are above the noise floor.");

        mGainCheckButton.setToolTipText("Step through the gain settings, measure each and recommend one (1-2 " +
            "minutes; the tuner's channels hear the gain changes)");
        mGainCheckButton.addActionListener(e -> openGainCheck());

        mTimer = new Timer(1000, e -> update());
        mTimer.setInitialDelay(0);
    }

    @Override
    public void addNotify()
    {
        super.addNotify();
        mTimer.start();
    }

    @Override
    public void removeNotify()
    {
        mTimer.stop();
        super.removeNotify();
    }

    private TunerController getController()
    {
        Tuner tuner = mTuner.get();
        return tuner != null ? tuner.getTunerController() : null;
    }

    private void update()
    {
        if(!isShowing())
        {
            return;
        }

        TunerController controller = getController();
        GainSteps steps = mGainSteps.get();
        mGainCheckButton.setEnabled(controller != null && steps != null);
        mGainCheckButton.setToolTipText(steps == null ? "Gain Check isn't available for this tuner" :
            "Step through the gain settings, measure each and recommend one (1-2 minutes; the tuner's channels " +
                "hear the gain changes)");

        if(controller == null)
        {
            setStatus("-", null);
            mPeakLabel.setText("-");
            mClippedLabel.setText("-");
            mNoiseLabel.setText("-");
            mSignalLabel.setText("-");
            return;
        }

        TunerSignalMonitor.Reading reading = controller.getSignalMonitor().getRecentReading();

        if(reading.samplesChecked() == 0)
        {
            setStatus("No samples", null);
            mPeakLabel.setText("-");
            mClippedLabel.setText("-");
        }
        else
        {
            if(reading.isOverloaded())
            {
                setStatus(reading.overloadEvents() > 0 && !TunerSignalMonitor.isReportable(reading.clippedPercent()) ?
                    "Overload reported by tuner - reduce gain" : "Overload - reduce gain", RED);
            }
            else if(reading.isLowHeadroom())
            {
                setStatus("Little headroom", AMBER);
            }
            else
            {
                setStatus("OK", GREEN);
            }

            mPeakLabel.setText(String.format("%.1f dBFS", reading.peakDbfs()));
            mClippedLabel.setText(String.format("%.3f%%", reading.clippedPercent()));
        }

        TunerNoiseFloorEstimator.BandPowers band = controller.getNoiseFloorEstimator().getBandPowers();

        if(band != null && band.binPowersDb().length > 0)
        {
            double noise = GainSweep.noiseFloor(band.binPowersDb());
            double strongest = GainSweep.strongestSignals(band.binPowersDb());
            mNoiseLabel.setText(String.format("%.1f dBFS", noise));
            mSignalLabel.setText(String.format("%.1f dB above noise", strongest - noise));
        }
        else
        {
            mNoiseLabel.setText("measuring...");
            mSignalLabel.setText("measuring...");
        }
    }

    private void setStatus(String text, Color color)
    {
        mStatusLabel.setText(text);
        mStatusLabel.setForeground(color != null ? color : getForeground());
    }

    private void openGainCheck()
    {
        TunerController controller = getController();
        GainSteps steps = mGainSteps.get();

        if(controller == null || steps == null)
        {
            return;
        }

        if(mDialog != null && mDialog.isDisplayable())
        {
            mDialog.toFront();
            return;
        }

        Tuner tuner = mTuner.get();
        mDialog = new GainCheckDialog(SwingUtilities.getWindowAncestor(this),
            tuner != null ? tuner.getPreferredName() : "Tuner", steps, controller);
        mDialog.setVisible(true);
    }
}
