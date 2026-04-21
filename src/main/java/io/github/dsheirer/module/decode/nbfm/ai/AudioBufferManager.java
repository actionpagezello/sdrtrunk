package io.github.dsheirer.module.decode.nbfm.ai;

import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.ByteOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.dsheirer.preference.UserPreferences;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public class AudioBufferManager {
    private static final Logger mLog = LoggerFactory.getLogger(AudioBufferManager.class);
    private static final int MAX_EVENTS = 5;
    private final LinkedList<List<float[]>> mAudioEvents = new LinkedList<>();
    private List<float[]> mCurrentEvent = null;
    private Path mBufferDir;
    private String mChannelName;
    private FileOutputStream mCurrentOutputStream;
    private Path mCurrentFilePath;

    public AudioBufferManager(UserPreferences preferences, String channelName) {
        mChannelName = channelName != null ? channelName.replaceAll("[^a-zA-Z0-9.-]", "_") : "unknown";
        mBufferDir = preferences.getDirectoryPreference().getDirectoryConfiguration().resolve("ai_buffers").resolve(mChannelName);
        try {
            Files.createDirectories(mBufferDir);
        } catch (IOException e) {
            mLog.error("Error creating AI buffer directory: " + mBufferDir, e);
        }
    }

    public void startEvent() {
        synchronized (mAudioEvents) {
            mCurrentEvent = new ArrayList<>();
            mCurrentFilePath = mBufferDir.resolve(System.currentTimeMillis() + ".raw");
            try {
                mCurrentOutputStream = new FileOutputStream(mCurrentFilePath.toFile());
            } catch (IOException e) {
                mLog.error("Error opening output stream for AI buffer: " + mCurrentFilePath, e);
                mCurrentOutputStream = null;
            }
        }
    }

    public void addAudioSamples(float[] audioSamples) {
        synchronized (mAudioEvents) {
            if (mCurrentOutputStream != null) {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(audioSamples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for(float f : audioSamples) buffer.putFloat(f);
                    mCurrentOutputStream.write(buffer.array());
                } catch (IOException e) {
                    mLog.error("Error writing audio samples to AI buffer", e);
                }
            }
        }
    }

    public void endEvent() {
        synchronized (mAudioEvents) {
            if (mCurrentOutputStream != null) {
                try {
                    mCurrentOutputStream.close();
                } catch (IOException e) {
                    mLog.error("Error closing AI buffer output stream", e);
                }
                mCurrentOutputStream = null;
            }
            cleanUpOldEvents();
        }
    }

    private void cleanUpOldEvents() {
        try (Stream<Path> stream = Files.list(mBufferDir)) {
            List<Path> files = stream.filter(path -> path.toString().endsWith(".raw"))
                                     .sorted()
                                     .collect(Collectors.toList());
            while (files.size() > MAX_EVENTS) {
                Files.deleteIfExists(files.get(0));
                files.remove(0);
            }
        } catch (IOException e) {
            mLog.error("Error cleaning up old AI buffer events", e);
        }
    }

    public List<List<float[]>> getBufferedEvents() {
        List<List<float[]>> events = new ArrayList<>();
        try (Stream<Path> stream = Files.list(mBufferDir)) {
            List<Path> files = stream.filter(path -> path.toString().endsWith(".raw"))
                                     .sorted()
                                     .collect(Collectors.toList());
            for (Path file : files) {
                byte[] bytes = Files.readAllBytes(file);
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                List<float[]> event = new ArrayList<>();
                // Read in chunks of 512 floats (same as Resampler)
                int chunkSize = 512;
                float[] floats = new float[bytes.length / 4];
                buffer.asFloatBuffer().get(floats);
                for(int i = 0; i < floats.length; i += chunkSize) {
                    int length = Math.min(chunkSize, floats.length - i);
                    float[] chunk = new float[length];
                    System.arraycopy(floats, i, chunk, 0, length);
                    event.add(chunk);
                }
                events.add(event);
            }
        } catch (IOException e) {
            mLog.error("Error reading AI buffered events", e);
        }
        return events;
    }

    public static int getBufferedEventCount(UserPreferences preferences, String channelName) {
        if (preferences == null || channelName == null) return 0;
        String safeName = channelName.replaceAll("[^a-zA-Z0-9.-]", "_");
        Path dir = preferences.getDirectoryPreference().getDirectoryConfiguration().resolve("ai_buffers").resolve(safeName);
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(path -> path.toString().endsWith(".raw")).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
