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
package io.github.dsheirer.record.wave;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.ConversionUtils;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;

/**
 * Activity-triggered baseband recorder. Monitors RF signal energy on channelized I/Q samples
 * and records WAV files only when signal activity exceeds the squelch threshold. Maintains a
 * circular pre-trigger buffer and a post-activity hold timer.
 *
 * This module is completely independent of the decode pipeline and works with any decoder type.
 */
public class ActivityTriggeredWaveRecorder extends Module implements IComplexSamplesListener,
        Listener<ComplexSamples>, ISourceEventListener
{
    private static final Logger mLog = LoggerFactory.getLogger(ActivityTriggeredWaveRecorder.class);

    private static final int PRE_BUFFER_DURATION_MS = 2000;
    private static final int HOLD_DURATION_MS = 10000;
    private static final float POWER_ALPHA = 0.1f;

    private enum RecordingState { IDLE, RECORDING, HOLDING }

    private final String mChannelName;
    private final long mChannelFrequency;
    private final float mSquelchThresholdDb;
    private final Path mRecordingBaseDir;

    private float mSampleRate;
    private AudioFormat mAudioFormat;
    private CircularSampleBuffer mCircularBuffer;
    private WaveWriter mWaveWriter;
    private RecordingState mRecordingState = RecordingState.IDLE;
    private float mSmoothedPowerDb = -100.0f;
    private long mHoldSamplesRemaining;
    private boolean mRunning;
    private boolean mErrorState;
    private EventBus mEventBus;
    private Path mRecordingDirectory;

    /**
     * Constructs an instance.
     * @param sampleRate initial sample rate
     * @param channelName for subdirectory and file naming
     * @param channelFrequency for file naming
     * @param squelchThresholdDb power threshold in dB for activity detection
     * @param recordingBaseDir base recording directory from user preferences
     */
    public ActivityTriggeredWaveRecorder(float sampleRate, String channelName, long channelFrequency,
                                         float squelchThresholdDb, Path recordingBaseDir)
    {
        mSampleRate = sampleRate;
        mChannelName = channelName;
        mChannelFrequency = channelFrequency;
        mSquelchThresholdDb = squelchThresholdDb;
        mRecordingBaseDir = recordingBaseDir;
        mAudioFormat = new AudioFormat(sampleRate, 16, 2, true, false);

        // Create per-channel recording subdirectory path
        String sanitized = sanitizeChannelName(channelName);
        mRecordingDirectory = recordingBaseDir.resolve(sanitized);

        initCircularBuffer();
    }

    /**
     * Sanitizes a channel name for use as a filesystem directory name.
     */
    private static String sanitizeChannelName(String name)
    {
        return StringUtils.replaceIllegalCharacters(name);
    }

    /**
     * Initializes or re-initializes the circular buffer based on current sample rate.
     */
    private void initCircularBuffer()
    {
        // At 25kHz with ~2048 sample buffers, 2 seconds needs ~24 entries.
        // Use a generous estimate: sampleRate * durationSec / typical buffer size
        int estimatedBuffers = Math.max(10, (int)(mSampleRate * PRE_BUFFER_DURATION_MS / 1000.0 / 1024.0) + 2);
        mCircularBuffer = new CircularSampleBuffer(estimatedBuffers);
    }

    @Override
    public void setInterModuleEventBus(EventBus eventBus)
    {
        mEventBus = eventBus;
    }

    @Override
    public void start()
    {
        mRunning = true;
        mErrorState = false;
        mRecordingState = RecordingState.IDLE;
        mSmoothedPowerDb = -100.0f;
        initCircularBuffer();
    }

    @Override
    public void stop()
    {
        mRunning = false;
        closeActiveRecording();
    }

    @Override
    public void reset()
    {
        closeActiveRecording();
        mRecordingState = RecordingState.IDLE;
        mSmoothedPowerDb = -100.0f;

        if(mCircularBuffer != null)
        {
            mCircularBuffer.clear();
        }
    }

    @Override
    public void receive(ComplexSamples complexSamples)
    {
        if(!mRunning || mErrorState)
        {
            return;
        }

        float powerDb = calculatePowerDb(complexSamples);
        mSmoothedPowerDb = (POWER_ALPHA * powerDb) + ((1.0f - POWER_ALPHA) * mSmoothedPowerDb);

        boolean active = mSmoothedPowerDb > mSquelchThresholdDb;

        switch(mRecordingState)
        {
            case IDLE:
                mCircularBuffer.add(complexSamples);

                if(active)
                {
                    startRecording();
                    writePreTriggerBuffer();
                    writeSamples(complexSamples);
                }
                break;

            case RECORDING:
                writeSamples(complexSamples);

                if(!active)
                {
                    mRecordingState = RecordingState.HOLDING;
                    mHoldSamplesRemaining = (long)(mSampleRate * HOLD_DURATION_MS / 1000.0);
                }
                break;

            case HOLDING:
                writeSamples(complexSamples);
                mHoldSamplesRemaining -= complexSamples.length();

                if(active)
                {
                    mRecordingState = RecordingState.RECORDING;
                }
                else if(mHoldSamplesRemaining <= 0)
                {
                    closeActiveRecording();
                    mRecordingState = RecordingState.IDLE;
                }
                break;
        }
    }

    /**
     * Calculates the RMS power in dB for a ComplexSamples buffer.
     */
    private float calculatePowerDb(ComplexSamples samples)
    {
        float[] iSamples = samples.i();
        float[] qSamples = samples.q();
        int length = samples.length();

        double sumSquares = 0.0;

        for(int i = 0; i < length; i++)
        {
            float iVal = iSamples[i];
            float qVal = qSamples[i];
            sumSquares += (iVal * iVal) + (qVal * qVal);
        }

        double rms = Math.sqrt(sumSquares / length);

        if(rms < 1e-20)
        {
            return -200.0f;
        }

        return (float)(20.0 * Math.log10(rms));
    }

    /**
     * Opens a new WAV file for recording.
     */
    private void startRecording()
    {
        try
        {
            // Create directory if needed
            if(!Files.exists(mRecordingDirectory))
            {
                Files.createDirectories(mRecordingDirectory);
            }

            // Build filename: timestamp_frequency_baseband.wav
            String filename = TimeStamp.getTimeStamp("_") + "_" + mChannelFrequency + "_baseband.wav";
            Path filePath = mRecordingDirectory.resolve(filename);

            mWaveWriter = new WaveWriter(mAudioFormat, filePath);
            mRecordingState = RecordingState.RECORDING;

            postRecordingEvent(true);

            mLog.info("Activity-triggered recording started: {}", filePath);
        }
        catch(IOException e)
        {
            mLog.error("Failed to start activity-triggered recording", e);
            mErrorState = true;
            mRecordingState = RecordingState.IDLE;
        }
    }

    /**
     * Writes the contents of the pre-trigger buffer to the active WAV file.
     */
    private void writePreTriggerBuffer()
    {
        List<ComplexSamples> buffered = mCircularBuffer.drain();

        for(ComplexSamples samples : buffered)
        {
            writeSamples(samples);
        }
    }

    /**
     * Writes a ComplexSamples buffer to the active WAV file.
     */
    private void writeSamples(ComplexSamples samples)
    {
        if(mWaveWriter == null)
        {
            return;
        }

        try
        {
            mWaveWriter.writeData(ConversionUtils.convertToSigned16BitSamples(samples));
        }
        catch(IOException e)
        {
            mLog.error("IOException writing activity-triggered recording — closing file", e);
            closeActiveRecording();
            mErrorState = true;
            mRecordingState = RecordingState.IDLE;
        }
    }

    /**
     * Closes the active WAV file and posts a recording-stopped event.
     */
    private void closeActiveRecording()
    {
        if(mWaveWriter != null)
        {
            try
            {
                mWaveWriter.close();
                mLog.info("Activity-triggered recording closed");
            }
            catch(IOException e)
            {
                mLog.error("Error closing activity-triggered recording", e);
            }

            mWaveWriter = null;
            postRecordingEvent(false);
        }
    }

    /**
     * Posts an ActivityRecordingEvent on the inter-module EventBus.
     */
    private void postRecordingEvent(boolean recording)
    {
        if(mEventBus != null)
        {
            mEventBus.post(new ActivityRecordingEvent(recording));
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return sourceEvent ->
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                float newRate = sourceEvent.getValue().floatValue();

                if(newRate != mSampleRate)
                {
                    mSampleRate = newRate;
                    mAudioFormat = new AudioFormat(newRate, 16, 2, true, false);

                    // If recording, close and restart with new format
                    if(mRecordingState != RecordingState.IDLE)
                    {
                        closeActiveRecording();
                        mRecordingState = RecordingState.IDLE;
                    }

                    initCircularBuffer();
                }
            }
        };
    }
}
