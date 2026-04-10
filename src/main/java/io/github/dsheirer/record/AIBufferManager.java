package io.github.dsheirer.record;

import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.preference.UserPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AIBufferManager {
    private static final Logger mLog = LoggerFactory.getLogger(AIBufferManager.class);
    private static final AIBufferManager INSTANCE = new AIBufferManager();

    private static final int MAX_BUFFER_SIZE = 5;
    private final Path mBaseBufferDir;

    private final Map<String, List<Path>> mChannelBuffers = new ConcurrentHashMap<>();

    private AIBufferManager() {
        // Create a hidden directory for AI audio buffer
        mBaseBufferDir = Paths.get(System.getProperty("user.home"), "SDRTrunk", ".ai_audio_buffer");
        try {
            if (!Files.exists(mBaseBufferDir)) {
                Files.createDirectories(mBaseBufferDir);
            }
            loadExistingBuffers();
        } catch (IOException e) {
            mLog.error("Could not create AI audio buffer directory", e);
        }
    }

    public static AIBufferManager getInstance() {
        return INSTANCE;
    }

    private void loadExistingBuffers() {
        try {
            Files.list(mBaseBufferDir).filter(Files::isDirectory).forEach(channelDir -> {
                String channelName = channelDir.getFileName().toString();
                try {
                    List<Path> recordings = Files.list(channelDir)
                            .filter(p -> p.toString().endsWith(".mp3"))
                            .sorted((p1, p2) -> Long.compare(p1.toFile().lastModified(), p2.toFile().lastModified()))
                            .collect(Collectors.toList());
                    mChannelBuffers.put(channelName, new ArrayList<>(recordings));
                } catch (IOException e) {
                    mLog.error("Error reading channel buffer dir", e);
                }
            });
        } catch (IOException e) {
            mLog.error("Error reading base buffer dir", e);
        }
    }

    public void bufferAudioSegment(AudioSegment segment, String channelName, UserPreferences preferences) {
        if (!segment.hasAudio()) return;

        Path channelDir = mBaseBufferDir.resolve(cleanFileName(channelName));
        try {
            if (!Files.exists(channelDir)) {
                Files.createDirectories(channelDir);
            }

            String fileName = "ai_buffer_" + System.currentTimeMillis() + ".mp3";
            Path filePath = channelDir.resolve(fileName);

            AudioSegmentRecorder.recordMP3(segment, filePath, preferences, segment.getIdentifierCollection());

            mChannelBuffers.compute(channelName, (k, list) -> {
                if (list == null) list = new ArrayList<>();
                list.add(filePath);

                // Keep only the most recent MAX_BUFFER_SIZE recordings
                while (list.size() > MAX_BUFFER_SIZE) {
                    Path oldest = list.remove(0);
                    try {
                        Files.deleteIfExists(oldest);
                    } catch (IOException e) {
                        mLog.error("Failed to delete old AI buffer file", e);
                    }
                }
                return list;
            });

        } catch (IOException e) {
            mLog.error("Error buffering audio segment for AI", e);
        }
    }

    public List<Path> getBufferedRecordings(String channelName) {
        List<Path> list = mChannelBuffers.get(channelName);
        if (list == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(list);
    }

    private String cleanFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
