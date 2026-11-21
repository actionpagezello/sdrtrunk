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

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerNoiseFloorEstimator;
import io.github.dsheirer.source.tuner.TunerSignalMonitor;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * AP-fork (ap-11, JavaFX in ap-14): Signal Level section of a tuner editor - a meter with the Gain Check button.
 *
 * Shows, over the last few seconds: a status (OK, little headroom, overload), the peak sample level, the share of
 * samples at full scale, the noise floor and how far the strongest signals are above it.  It updates once a second
 * while it is on screen; the noise floor measurement runs only then.
 */
public class GainCheckSection extends TunerSection
{
    private static final String GREEN = "-fx-text-fill: #008c00; -fx-font-weight: bold;";
    private static final String AMBER = "-fx-text-fill: #c87800; -fx-font-weight: bold;";
    private static final String RED = "-fx-text-fill: #c80000; -fx-font-weight: bold;";
    private static final String PLAIN = "-fx-font-weight: bold;";

    private final Supplier<Tuner> mTuner;
    private final Supplier<GainSteps> mGainSteps;
    private final Supplier<ChannelProcessingManager> mChannels;
    private final Label mStatusLabel = new Label("-");
    private final Label mPeakLabel = new Label("-");
    private final Label mClippedLabel = new Label("-");
    private final Label mNoiseLabel = new Label("-");
    private final Label mSignalLabel = new Label("-");
    private final Button mGainCheckButton = new Button("Gain Check...");
    private final Timeline mTimeline;
    private GainCheckWindow mWindow;
    private int mLastSetting = Integer.MIN_VALUE;

    /**
     * Constructs an instance
     * @param tuner supplies the editor's tuner, or null when there is none
     * @param gainSteps supplies the editor's gain control for the gain check, or null when not supported
     * @param channels supplies the channel processing manager, to find the tuner's control channel
     */
    public GainCheckSection(Supplier<Tuner> tuner, Supplier<GainSteps> gainSteps,
                            Supplier<ChannelProcessingManager> channels)
    {
        super("Signal Level");
        mTuner = tuner;
        mGainSteps = gainSteps;
        mChannels = channels;

        mStatusLabel.setStyle(PLAIN);
        mStatusLabel.setTooltip(new Tooltip("Overload: samples at the top of the tuner's range - strong signals then " +
            "create false signals and raise the noise floor.  Reduce the gain."));
        mPeakLabel.setTooltip(new Tooltip("Highest sample level over the last 3 seconds.  0 dBFS is the top of the " +
            "tuner's range; above -3 dBFS there is little headroom left."));
        mClippedLabel.setTooltip(new Tooltip("Share of samples at the top of the tuner's range (clipping) over the " +
            "last 3 seconds.  " + TunerSignalMonitor.REPORT_PERCENT + "% or more is overload."));
        mNoiseLabel.setTooltip(new Tooltip("Median power across the tuner's bandwidth, per 25 kHz.  When raising the " +
            "gain raises this by the same amount, more gain no longer helps."));
        mSignalLabel.setTooltip(new Tooltip("How far the strongest signals in the tuner's bandwidth are above the " +
            "noise floor."));
        mGainCheckButton.setOnAction(e -> openGainCheck());

        addRow("Status", mStatusLabel, mGainCheckButton);
        addRow("Peak", mPeakLabel);
        addRow("At Full Scale", mClippedLabel);
        addRow("Noise Floor", mNoiseLabel);
        addRow("Strongest Signals", mSignalLabel);

        mTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> update()));
        mTimeline.setCycleCount(Timeline.INDEFINITE);

        //Runs only while the section is in a window
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if(newScene != null)
            {
                update();
                mTimeline.play();
            }
            else
            {
                mTimeline.stop();
            }
        });
    }

    /**
     * Stops the updates
     */
    public void dispose()
    {
        mTimeline.stop();
    }

    private TunerController getController()
    {
        Tuner tuner = mTuner.get();
        return tuner != null ? tuner.getTunerController() : null;
    }

    private void update()
    {
        if(getScene() == null || !isVisible())
        {
            return;
        }

        TunerController controller = getController();
        GainSteps steps = mGainSteps.get();
        mGainCheckButton.setDisable(controller == null || steps == null);
        mGainCheckButton.setTooltip(new Tooltip(steps == null ? "Gain Check isn't available for this tuner" :
            "Step through the gain settings, measure each and recommend one (1-2 minutes; the tuner's channels hear " +
                "the gain changes)"));

        if(controller == null)
        {
            setStatus("-", PLAIN);
            mPeakLabel.setText("-");
            mClippedLabel.setText("-");
            mNoiseLabel.setText("-");
            mSignalLabel.setText("-");
            return;
        }

        //ap-12: a changed gain setting starts a new history (not while a gain check is stepping through settings)
        if(steps != null && !controller.getSignalMonitor().isIntensive())
        {
            int current = steps.current();

            if(mLastSetting != Integer.MIN_VALUE && current != mLastSetting)
            {
                controller.getSignalMonitor().markSettingChange();
            }

            mLastSetting = current;
        }

        TunerSignalMonitor.Reading reading = controller.getSignalMonitor().getRecentReading();

        if(reading.samplesChecked() == 0)
        {
            //ap-16: a tuner with no channels isn't streaming, so there is nothing to measure
            setStatus(isIdle() ? "Not receiving - the tuner only runs while it has a channel" : "No samples", PLAIN);
            mPeakLabel.setText("-");
            mClippedLabel.setText("-");
            mGainCheckButton.setDisable(true);
            mGainCheckButton.setTooltip(new Tooltip("Gain Check needs the tuner to be receiving: start a channel on " +
                "this tuner first"));
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

    /**
     * Indicates if the tuner has no channels, so it isn't streaming.
     */
    private boolean isIdle()
    {
        Tuner tuner = mTuner.get();
        return tuner != null && tuner.getChannelSourceManager() != null &&
            tuner.getChannelSourceManager().getTunerChannelCount() == 0;
    }

    private void setStatus(String text, String style)
    {
        mStatusLabel.setText(text);
        mStatusLabel.setStyle(style);
    }

    private void openGainCheck()
    {
        TunerController controller = getController();
        GainSteps steps = mGainSteps.get();

        if(controller == null || steps == null)
        {
            return;
        }

        if(mWindow != null && mWindow.isShowing())
        {
            mWindow.toFront();
            return;
        }

        Tuner tuner = mTuner.get();
        mWindow = new GainCheckWindow(getScene() != null ? getScene().getWindow() : null,
            tuner != null ? tuner.getPreferredName() : "Tuner", steps, controller,
            () -> GainCheckReference.find(mChannels.get(), controller.getNoiseFloorEstimator()));
        mWindow.show();
    }
}
