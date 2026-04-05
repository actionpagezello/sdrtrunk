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
package io.github.dsheirer.spectrum;

import io.github.dsheirer.settings.ColorSetting;
import io.github.dsheirer.settings.ColorSetting.ColorSettingName;
import io.github.dsheirer.settings.Setting;
import io.github.dsheirer.settings.SettingChangeListener;
import io.github.dsheirer.settings.SettingsManager;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.text.DecimalFormat;

/**
 * Hardware-accelerated Waterfall display using JavaFX Canvas.
 * Optimized for Windows 11 Direct3D rendering.
 */
public class WaterfallPanel extends JPanel implements DFTResultsListener,
    Pausable,
    SettingChangeListener
{
    private static final long serialVersionUID = 1L;
    private final static Logger mLog = LoggerFactory.getLogger(WaterfallPanel.class);

    private static DecimalFormat CURSOR_FORMAT = new DecimalFormat("0.00000");
    private static final String PAUSED = "PAUSED - Right Click to Unpause";
    private static final String DISABLED = "DISABLED - Right Click to Select a Tuner";

    // Swing/JavaFX Bridge
    private JFXPanel mFxPanel;
    private Canvas mCanvas;
    private GraphicsContext mGc;
    private WritableImage mWaterfallImage;
    private PixelWriter mPixelWriter;

    private int mDFTSize = 4096;
    private int mImageHeight = 800;
    private java.awt.Color mColorSpectrumCursor;
    
    private Point mCursorLocation = new Point(0, 0);
    private boolean mCursorVisible = false;
    private long mCursorFrequency = 0;
    private boolean mPaused = false;
    private boolean mDisabled = true;
    private int mZoom = 0;
    private int mDFTZoomWindowOffset = 0;

    private SettingsManager mSettingsManager;

    public WaterfallPanel(SettingsManager settingsManager)
    {
        super(new BorderLayout());
        mSettingsManager = settingsManager;
        mSettingsManager.addListener(this);
        mColorSpectrumCursor = getColor(ColorSettingName.SPECTR_CURSOR);

        // Initialize the JavaFX Bridge
        mFxPanel = new JFXPanel();
        add(mFxPanel, BorderLayout.CENTER);

        Platform.runLater(this::initFx);
    }

    /**
     * Initializes the JavaFX hardware-accelerated scene
     */
    private void initFx() {
        mCanvas = new Canvas(mDFTSize, mImageHeight);
        mGc = mCanvas.getGraphicsContext2D();
        mWaterfallImage = new WritableImage(mDFTSize, mImageHeight);
        mPixelWriter = mWaterfallImage.getPixelWriter();

        StackPane root = new StackPane(mCanvas);
        Scene scene = new Scene(root, javafx.scene.paint.Color.BLACK);
        mFxPanel.setScene(scene);

        // Bind canvas size to the panel
        mCanvas.widthProperty().bind(mFxPanel.widthProperty());
        mCanvas.heightProperty().bind(mFxPanel.heightProperty());
    }

    public void dispose()
    {
        if(mSettingsManager != null)
        {
            mSettingsManager.removeListener(this);
        }
        mSettingsManager = null;
    }

    public void setPaused(boolean paused)
    {
        mPaused = paused;
        repaint();
    }

    public boolean isPaused()
    {
        return mPaused;
    }

    public boolean isDisabled()
    {
        return mDisabled;
    }

    public void setZoom(int zoom)
    {
        mZoom = zoom;
    }

    public void setZoomWindowOffset(int offset)
    {
        mDFTZoomWindowOffset = offset;
    }

    private java.awt.Color getColor(ColorSettingName name)
    {
        ColorSetting setting = mSettingsManager.getColorSetting(name);
        return setting.getColor();
    }

    @Override
    public void settingChanged(Setting setting)
    {
        if(setting instanceof ColorSetting colorSetting)
        {
            if(colorSetting.getColorSettingName() == ColorSettingName.SPECTRUM_CURSOR)
            {
                mColorSpectrumCursor = colorSetting.getColor();
            }
        }
    }

    @Override public void settingDeleted(Setting setting) {}

    public void setCursorLocation(Point point)
    {
        mCursorLocation = point;
        repaint();
    }

    public void setCursorFrequency(long frequency)
    {
        mCursorFrequency = frequency;
    }

    public void setCursorVisible(boolean visible)
    {
        mCursorVisible = visible;
        repaint();
    }

    /**
     * Handles the heavy rendering offloaded to the GPU
     */
    @Override
    public void receive(float[] update)
    {
        if (mPaused) return;
        mDisabled = false;

        // Ensure JavaFX components are ready
        if (mPixelWriter == null) return;

        Platform.runLater(() -> {
            // 1. Shift the existing waterfall image down by 1 pixel (GPU operation)
            mGc.drawImage(mCanvas.snapshot(null, null), 0, 1);

            // 2. Convert FFT data to colors and write a new row at the top
            double sum = 0.0d;
            for(float val : update) sum += val;
            float average = (float)(sum / update.length);
            float scale = 256.0f / (average == 0 ? 1.0f : average);

            for (int x = 0; x < update.length; x++) {
                float intensity = (average - update[x]) * scale;
                int colorIdx = Math.min(255, Math.max(0, (int)intensity));
                
                // Get ARGB from the shared WaterfallColorModel
                int argb = WaterfallColorModel.getNativeColorModel().getRGB(colorIdx);
                mPixelWriter.setArgb(x, 0, argb);
            }

            // 3. Draw the newly updated row from the buffer to the canvas
            mGc.drawImage(mWaterfallImage, 0, 0, mCanvas.getWidth(), 1, 0, 0, mCanvas.getWidth(), 1);
        });
    }

    /**
     * Overlays Swing elements (Cursor, Text) on top of the JavaFX waterfall
     */
    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        if (mCursorVisible) {
            g2.setColor(mColorSpectrumCursor);
            g2.drawLine(mCursorLocation.x, 0, mCursorLocation.x, getHeight());
            String frequency = CURSOR_FORMAT.format(mCursorFrequency / 1000000.0D) + " MHz";
            g2.drawString(frequency, mCursorLocation.x + 5, mCursorLocation.y > 20 ? mCursorLocation.y : 20);
        }

        if (mDisabled) {
            g2.setColor(java.awt.Color.YELLOW);
            g2.drawString(DISABLED, 20, 30);
        } else if (mPaused) {
            g2.setColor(java.awt.Color.RED);
            g2.drawString(PAUSED, 20, 30);
        }
    }

    public void clearWaterfall()
    {
        mDisabled = true;
        Platform.runLater(() -> {
            if (mGc != null) {
                mGc.setFill(javafx.scene.paint.Color.BLACK);
                mGc.fillRect(0, 0, mCanvas.getWidth(), mCanvas.getHeight());
            }
        });
        repaint();
    }
}
