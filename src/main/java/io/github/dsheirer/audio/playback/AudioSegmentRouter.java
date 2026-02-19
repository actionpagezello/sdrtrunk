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
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Routes audio segments to specific Virtual Audio Cable (VAC) outputs based on alias configuration.
 * Monitors segments continuously and routes all audio buffers to the appropriate device.
 */
public class AudioSegmentRouter
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioSegmentRouter.class);
    
    private Map<String, SourceDataLine> mAudioOutputLines = new ConcurrentHashMap<>();
    private Map<AudioSegment, SegmentRouter> mActiveSegments = new ConcurrentHashMap<>();
    private Map<String, Long> mRecentlyActiveLines = new ConcurrentHashMap<>(); // Track lines that recently ended
    private static final long SILENCE_FEED_DURATION = 3000; // Feed silence for 3 seconds after segment ends
    private ScheduledExecutorService mExecutor;
    private boolean mEnabled = true;
    
    public AudioSegmentRouter()
    {
        // Start background thread to route audio continuously
        mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AudioSegmentRouter");
            t.setDaemon(true);
            return t;
        });
        
        // Process active segments every 20ms
        mExecutor.scheduleAtFixedRate(this::processActiveSegments, 0, 20, TimeUnit.MILLISECONDS);
        
        // Keep all audio lines fed with silence to prevent clicking
        mExecutor.scheduleAtFixedRate(this::feedSilenceToIdleLines, 0, 50, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Writes silence to recently active audio lines to prevent clicking
     * Only feeds lines that ended within the last 1 second
     */
    private void feedSilenceToIdleLines()
    {
        if(!mEnabled || mRecentlyActiveLines.isEmpty())
        {
            return;
        }
        
        long now = System.currentTimeMillis();
        
        // Remove expired entries and feed silence to recent ones
        mRecentlyActiveLines.entrySet().removeIf(entry -> {
            String deviceName = entry.getKey();
            long endTime = entry.getValue();
            long elapsed = now - endTime;
            
            // Remove if silence period has elapsed
            if(elapsed > SILENCE_FEED_DURATION)
            {
                return true;
            }
            
            // Still within silence period - feed silence
            SourceDataLine line = mAudioOutputLines.get(deviceName);
            if(line != null && line.isOpen())
            {
                try
                {
                    float[] silence = new float[400]; // 50ms of silence
                    byte[] silenceBytes = convertFloatsToBytes(silence, line.getFormat().getChannels());
                    line.write(silenceBytes, 0, silenceBytes.length);
                }
                catch(Exception e)
                {
                    // Ignore - line might have been closed
                }
            }
            
            return false; // Keep in map
        });
    }
    
    /**
     * Registers an audio segment for routing if it has a custom device configured
     */
    public void route(AudioSegment audioSegment)
    {
        if(!mEnabled || audioSegment == null)
        {
            return;
        }
        
        // Check if already routing this segment
        if(mActiveSegments.containsKey(audioSegment))
        {
            return;
        }
        
        // Get the alias for this audio segment
        Alias alias = getAlias(audioSegment);
        
        if(alias == null || !alias.hasAudioOutputDevice())
        {
            // No custom device - let main system handle it
            return;
        }
        
        String deviceName = alias.getAudioOutputDevice();
        mLog.info("Starting VAC routing for alias [" + alias.getName() + "] to device [" + deviceName + "]");
        
        // Remove from recently active list if present - new audio is coming
        mRecentlyActiveLines.remove(deviceName);
        
        // Suppress main playback
        audioSegment.monitorPriorityProperty().set(Priority.DO_NOT_MONITOR);
        
        // Create a router for this segment
        SegmentRouter router = new SegmentRouter(audioSegment, deviceName, alias.getName());
        mActiveSegments.put(audioSegment, router);
    }
    
    /**
     * Processes all active segments and routes their audio buffers
     */
    private void processActiveSegments()
    {
        if(mActiveSegments.isEmpty())
        {
            return;
        }
        
        // Process each active segment
        mActiveSegments.entrySet().removeIf(entry -> {
            AudioSegment segment = entry.getKey();
            SegmentRouter router = entry.getValue();
            
            // Route any new buffers
            router.routeNewBuffers();
            
            // Remove if segment is complete and all buffers routed
            if(segment.isComplete() && router.isComplete())
            {
                // Mark this device as recently active so we keep feeding it silence
                mRecentlyActiveLines.put(router.deviceName, System.currentTimeMillis());
                mLog.debug("Completed routing for segment - routed " + router.getTotalBuffersRouted() + " buffers");
                return true; // Remove from active list
            }
            
            return false; // Keep in active list
        });
    }
    
    /**
     * Routes audio from a single segment to its designated device
     */
    private class SegmentRouter
    {
        private final AudioSegment segment;
        final String deviceName; // Package-private so outer class can access
        private final String aliasName;
        private int lastRoutedBufferIndex = -1;
        private int totalBuffersRouted = 0;
        private long completionTime = 0;
        private static final long SILENCE_DURATION_MS = 800; // Write silence for 800ms after completion
        
        public SegmentRouter(AudioSegment segment, String deviceName, String aliasName)
        {
            this.segment = segment;
            this.deviceName = deviceName;
            this.aliasName = aliasName;
        }
        
        /**
         * Routes any new buffers that have arrived since last check
         */
        public void routeNewBuffers()
        {
            int currentBufferCount = segment.getAudioBufferCount();
            
            SourceDataLine outputLine = getOrCreateOutputLine(deviceName);
            
            if(outputLine == null)
            {
                mLog.warn("Cannot route - output line not available for: " + deviceName);
                return;
            }
            
            // If we've routed all buffers, write silence to keep line fed
            if(currentBufferCount <= lastRoutedBufferIndex + 1)
            {
                if(segment.isComplete() && lastRoutedBufferIndex >= segment.getAudioBufferCount() - 1)
                {
                    if(completionTime == 0)
                    {
                        completionTime = System.currentTimeMillis();
                        
                        // Immediately write a large burst of silence to prevent clicking
                        try
                        {
                            float[] largeSilence = new float[1600]; // 200ms of silence in one go
                            byte[] silenceBytes = convertFloatsToBytes(largeSilence, outputLine.getFormat().getChannels());
                            outputLine.write(silenceBytes, 0, silenceBytes.length);
                        }
                        catch(Exception e)
                        {
                            mLog.debug("Error writing initial silence burst", e);
                        }
                        
                        mLog.debug("Marked completion time for segment with " + totalBuffersRouted + " buffers");
                    }
                    
                    // Continue writing silence buffers during cooldown period
                    if((System.currentTimeMillis() - completionTime) < SILENCE_DURATION_MS)
                    {
                        try
                        {
                            float[] silence = new float[160]; // One buffer of silence (20ms)
                            byte[] silenceBytes = convertFloatsToBytes(silence, outputLine.getFormat().getChannels());
                            outputLine.write(silenceBytes, 0, silenceBytes.length);
                        }
                        catch(Exception e)
                        {
                            mLog.debug("Error writing cooldown silence", e);
                        }
                    }
                }
                return;
            }
            
            try
            {
                // Route all new buffers
                for(int i = lastRoutedBufferIndex + 1; i < currentBufferCount; i++)
                {
                    float[] audioBuffer = segment.getAudioBuffer(i);
                    if(audioBuffer != null)
                    {
                        byte[] bytes = convertFloatsToBytes(audioBuffer, outputLine.getFormat().getChannels());
                        outputLine.write(bytes, 0, bytes.length);
                        lastRoutedBufferIndex = i;
                        totalBuffersRouted++;
                    }
                }
                
                if(totalBuffersRouted % 10 == 0 && totalBuffersRouted > 0)
                {
                    mLog.debug("Routed " + totalBuffersRouted + " buffers for [" + aliasName + "]");
                }
            }
            catch(Exception e)
            {
                mLog.error("Error routing buffers for " + aliasName, e);
            }
        }
        
        public boolean isComplete()
        {
            // Not complete until we've routed all buffers AND waited for silence duration
            boolean allBuffersRouted = lastRoutedBufferIndex >= segment.getAudioBufferCount() - 1;
            
            if(!allBuffersRouted)
            {
                return false;
            }
            
            // If completion time is set, check if silence duration has elapsed
            if(completionTime > 0)
            {
                return (System.currentTimeMillis() - completionTime) >= SILENCE_DURATION_MS;
            }
            
            return false;
        }
        
        public int getTotalBuffersRouted()
        {
            return totalBuffersRouted;
        }
    }
    
    /**
     * Gets the primary alias for an audio segment - checks ALL identifiers
     */
    private Alias getAlias(AudioSegment audioSegment)
    {
        if(audioSegment.getAliasList() == null)
        {
            return null;
        }
        
        List<Identifier> identifiers = audioSegment.getIdentifierCollection().getIdentifiers();
        
        if(identifiers != null && !identifiers.isEmpty())
        {
            // Check ALL identifiers to find one with an alias
            for(Identifier identifier : identifiers)
            {
                List<Alias> aliases = audioSegment.getAliasList().getAliases(identifier);
                
                if(aliases != null && !aliases.isEmpty())
                {
                    return aliases.get(0);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Gets or creates a SourceDataLine for the specified device
     */
    private SourceDataLine getOrCreateOutputLine(String deviceName)
    {
        // Check cache first
        if(mAudioOutputLines.containsKey(deviceName))
        {
            SourceDataLine line = mAudioOutputLines.get(deviceName);
            if(line != null && line.isOpen())
            {
                return line;
            }
        }
        
        // Create new line
        try
        {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            
            for(Mixer.Info mixerInfo : mixers)
            {
                String mixerName = mixerInfo.getName();
                boolean matches = false;
                
                // Try multiple matching strategies
                if(mixerName.equals(deviceName) || mixerName.contains(deviceName) || deviceName.contains(mixerName))
                {
                    matches = true;
                }
                else if(mixerName.startsWith("DirectSound Playback("))
                {
                    int start = mixerName.indexOf('(');
                    int end = mixerName.lastIndexOf(')');
                    if(start > 0 && end > start)
                    {
                        String innerName = mixerName.substring(start + 1, end);
                        if(innerName.equals(deviceName) || innerName.contains(deviceName) || deviceName.contains(innerName))
                        {
                            matches = true;
                        }
                    }
                }
                
                if(matches)
                {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    AudioFormat selectedFormat = null;
                    
                    // Try stereo first
                    AudioFormat stereoFormat = new AudioFormat(8000.0f, 16, 2, true, false);
                    DataLine.Info stereoInfo = new DataLine.Info(SourceDataLine.class, stereoFormat);
                    
                    if(mixer.isLineSupported(stereoInfo))
                    {
                        selectedFormat = stereoFormat;
                        mLog.info("Using STEREO for: " + deviceName);
                    }
                    else
                    {
                        AudioFormat monoFormat = new AudioFormat(8000.0f, 16, 1, true, false);
                        DataLine.Info monoInfo = new DataLine.Info(SourceDataLine.class, monoFormat);
                        
                        if(mixer.isLineSupported(monoInfo))
                        {
                            selectedFormat = monoFormat;
                            mLog.info("Using MONO for: " + deviceName);
                        }
                    }
                    
                    if(selectedFormat != null)
                    {
                        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, selectedFormat);
                        SourceDataLine line = (SourceDataLine) mixer.getLine(lineInfo);
                        line.open(selectedFormat, 8192);
                        line.start();
                        
                        mAudioOutputLines.put(deviceName, line);
                        mLog.info("Opened audio line for: " + deviceName);
                        
                        return line;
                    }
                }
            }
            
            mLog.warn("Could not find device: " + deviceName);
        }
        catch(Exception e)
        {
            mLog.error("Error opening device: " + deviceName, e);
        }
        
        return null;
    }
    
    /**
     * Converts float samples to bytes
     */
    private byte[] convertFloatsToBytes(float[] samples, int channels)
    {
        ByteBuffer buffer;
        
        if(channels == 2)
        {
            buffer = ByteBuffer.allocate(samples.length * 4);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            for(float sample : samples)
            {
                short pcmValue = (short) (sample * 32767.0f);
                buffer.putShort(pcmValue);
                buffer.putShort(pcmValue);
            }
        }
        else
        {
            buffer = ByteBuffer.allocate(samples.length * 2);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            for(float sample : samples)
            {
                short pcmValue = (short) (sample * 32767.0f);
                buffer.putShort(pcmValue);
            }
        }
        
        return buffer.array();
    }
    
    public void setEnabled(boolean enabled)
    {
        mEnabled = enabled;
    }
    
    public void dispose()
    {
        if(mExecutor != null)
        {
            mExecutor.shutdown();
        }
        
        mActiveSegments.clear();
        
        for(SourceDataLine line : mAudioOutputLines.values())
        {
            try
            {
                if(line != null && line.isOpen())
                {
                    line.drain();
                    line.stop();
                    line.close();
                }
            }
            catch(Exception e)
            {
                mLog.error("Error closing line", e);
            }
        }
        
        mAudioOutputLines.clear();
    }
    
    public static String[] getAvailableDevices()
    {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        return java.util.Arrays.stream(mixers)
            .map(Mixer.Info::getName)
            .toArray(String[]::new);
    }
}
