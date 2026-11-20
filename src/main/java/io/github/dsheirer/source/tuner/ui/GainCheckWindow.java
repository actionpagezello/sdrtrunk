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
import io.github.dsheirer.source.tuner.TunerSignalMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-11, JavaFX in ap-14): Gain Check window.  Runs a GainSweep, shows each setting's measurements and
 * recommends one.
 */
public class GainCheckWindow extends Stage
{
    private static final Logger LOG = LoggerFactory.getLogger(GainCheckWindow.class);

    private final GainSteps mSteps;
    private final TunerController mController;
    private final String mTunerName;
    private final Supplier<List<GainCheckReference.Candidate>> mCandidates;
    private final ObservableList<GainSweep.StepResult> mResults = FXCollections.observableArrayList();
    private final Label mProgressLabel = new Label(" ");
    private final TextArea mRecommendationText = new TextArea();
    private final Button mStartButton = new Button("Start");
    private final Button mStopButton = new Button("Stop");
    private final Button mApplyButton = new Button("Apply Recommended");
    private final TableView<GainSweep.StepResult> mTable = new TableView<>(mResults);
    private GainSweep mSweep;
    private int mRecommendedIndex = -1;
    private int mOriginalIndex = -1;
    private TunerSignalMonitor.History mHistory;
    private String mReferenceName;

    public GainCheckWindow(Window owner, String tunerName, GainSteps steps, TunerController controller,
                           Supplier<List<GainCheckReference.Candidate>> candidates)
    {
        mSteps = steps;
        mController = controller;
        mTunerName = tunerName;
        mCandidates = candidates;

        if(owner != null)
        {
            initOwner(owner);
        }

        setTitle("Gain Check - " + tunerName);

        Label intro = new Label("Steps " + steps.getControlName().toLowerCase() + " through all " + steps.count() +
            " settings, about 3 seconds each, and measures the noise floor, overload and - when this tuner carries a " +
            "control channel that is on the air - the control channel's signal-to-noise ratio at each.  With a " +
            "control channel the recommendation follows it; without one, the noise floor.  It also uses how the " +
            "tuner did at its own setting over the last 30 minutes, so run it after the tuner has been at one " +
            "setting for at least 5 minutes.  The tuner's channels hear the gain changes while it runs.  Your " +
            "setting (" + steps.label(steps.current()) + ") is put back at the end unless you apply the " +
            "recommendation.");
        intro.setWrapText(true);

        HBox buttons = new HBox(8, mStartButton, mStopButton, mProgressLabel);

        addColumn("Gain", r -> r.label() + (r.index() == mOriginalIndex ? " (yours)" : ""));
        addColumn("Noise Floor", r -> format(r.noiseFloorDb(), "%.1f dBFS"));
        addColumn("Control Channel", r -> format(r.referenceSnrDb(), "%.1f dB SNR"));
        addColumn("Strongest Signals", r -> format(r.signalDb(), "+%.1f dB"));
        addColumn("Peak", r -> format(r.peakDbfs(), "%.1f dBFS"));
        addColumn("At Full Scale", r -> String.format("%.3f%%", r.clippedPercent()));
        addColumn("Result", r -> r.isOverloaded() ? "Overload" : (r.index() == mRecommendedIndex ? "Recommended" : ""));
        mTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        mTable.setPrefHeight(320);
        mTable.setRowFactory(table -> new TableRow<>()
        {
            @Override
            protected void updateItem(GainSweep.StepResult item, boolean empty)
            {
                super.updateItem(item, empty);

                if(empty || item == null)
                {
                    setStyle("");
                }
                else if(item.index() == mRecommendedIndex)
                {
                    setStyle("-fx-background-color: #c8ebc8;");
                }
                else if(item.isOverloaded())
                {
                    setStyle("-fx-background-color: #f5cdcd;");
                }
                else
                {
                    setStyle("");
                }
            }
        });
        VBox.setVgrow(mTable, Priority.ALWAYS);

        mRecommendationText.setEditable(false);
        mRecommendationText.setWrapText(true);
        mRecommendationText.setPrefRowCount(4);

        Button close = new Button("Close");
        close.setOnAction(e -> close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, spacer, mApplyButton, close);

        mStopButton.setDisable(true);
        mApplyButton.setDisable(true);
        mStartButton.setOnAction(e -> start());
        mStopButton.setOnAction(e -> {
            if(mSweep != null)
            {
                mSweep.cancel();
                mProgressLabel.setText("Stopping - putting your setting back...");
            }
        });
        mApplyButton.setOnAction(e -> {
            if(mRecommendedIndex >= 0)
            {
                mSteps.apply(mRecommendedIndex);
                mProgressLabel.setText("Applied " + mSteps.label(mRecommendedIndex) + " and saved.");
                LOG.info("Gain check [" + mTunerName + "]: applied recommended " + mSteps.getControlName() + " " +
                    mSteps.label(mRecommendedIndex));
                mApplyButton.setDisable(true);
            }
        });
        setOnHidden(e -> {
            if(mSweep != null)
            {
                mSweep.cancel();
            }
        });

        VBox content = new VBox(10, intro, buttons, mTable, mRecommendationText, bottom);
        content.setPadding(new Insets(10));
        setScene(new Scene(content, 760, 620));
    }

    private void addColumn(String title, Function<GainSweep.StepResult,String> value)
    {
        TableColumn<GainSweep.StepResult,String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setSortable(false);
        mTable.getColumns().add(column);
    }

    private static String format(double value, String format)
    {
        return Double.isNaN(value) ? "-" : String.format(format, value);
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
        mResults.clear();
        mRecommendedIndex = -1;
        mReferenceName = null;
        mRecommendationText.setText("");
        mStartButton.setDisable(true);
        mStopButton.setDisable(false);
        mApplyButton.setDisable(true);
        mProgressLabel.setText("Looking for a control channel...");
        mSweep = new GainSweep(mSteps, mController.getNoiseFloorEstimator(), mController.getSignalMonitor(),
            mCandidates.get());
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
                        Platform.runLater(() -> mProgressLabel.setText("Measuring " + label + " (" + step + " of " +
                            of + ")" + (mReferenceName != null ? " on " + mReferenceName : "") + "..."));
                    }

                    @Override
                    public void stepMeasured(GainSweep.StepResult result)
                    {
                        Platform.runLater(() -> mResults.add(result));
                    }

                    @Override
                    public void referenceChosen(GainCheckReference.Candidate reference)
                    {
                        mReferenceName = reference != null ? reference.name() : null;
                        LOG.info("Gain check [" + mTunerName + "]: " + (reference != null ? "measuring on control " +
                            "channel " + reference.name() + " (" + reference.frequency() + " Hz)" :
                            "no control channel on the air on this tuner - using the noise floor"));
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
            Platform.runLater(() -> finished(finalResults, finalError, cancelled));
        }, "sdrtrunk gain check");
        thread.setDaemon(true);
        thread.start();
    }

    private void finished(List<GainSweep.StepResult> results, String error, boolean cancelled)
    {
        mStartButton.setDisable(false);
        mStopButton.setDisable(true);
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

        GainSweep.Recommendation recommendation = GainSweep.recommend(results, mOriginalIndex, mHistory,
            mReferenceName);
        mRecommendedIndex = recommendation.index();
        mTable.refresh();
        mRecommendationText.setText(recommendation.explanation());
        mApplyButton.setDisable(recommendation.index() < 0 || recommendation.index() == mSteps.current());
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

            if(!Double.isNaN(result.referenceSnrDb()))
            {
                sb.append(", control channel ").append(String.format("%.1f", result.referenceSnrDb())).append(" dB");
            }

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
}
