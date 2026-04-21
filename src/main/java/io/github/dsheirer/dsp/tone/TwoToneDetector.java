package io.github.dsheirer.dsp.tone;

import io.github.dsheirer.dsp.filter.GoertzelFilter;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.playlist.PlaylistV2;
import io.github.dsheirer.alias.Alias;
import java.util.Collection;
import io.github.dsheirer.playlist.TwoToneConfiguration;
import io.github.dsheirer.playlist.TwoToneDiscoveryLog;
import io.github.dsheirer.audio.broadcast.zello.ZelloBroadcaster;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.zello.ZelloConfiguration;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.audio.broadcast.mqtt.MqttService;

/**
 * Detects A/B two-tone sequences in a background thread to prevent audio playback stuttering.
 */
public class TwoToneDetector
{
    private static final Logger mLog = LoggerFactory.getLogger(TwoToneDetector.class);

    private static final int SAMPLE_RATE = 8000;
    // 160 samples @ 8kHz is 20ms block size
    private static final int BLOCK_SIZE = 160;

    // A tone has to be present for a minimum duration to be recognized
    private static final int MIN_TONE_DURATION_MS = 300;
    private static final int MIN_TONE_BLOCKS = MIN_TONE_DURATION_MS / 20;
    private static final int LONG_A_MIN_TONE_DURATION_MS = 2000;
    private static final int LONG_A_MIN_TONE_BLOCKS = LONG_A_MIN_TONE_DURATION_MS / 20;

    private static final int POWER_THRESHOLD_DB = 10; // Simple threshold, tune as needed

    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private final LinkedTransferQueue<AudioBufferWrapper> mAudioQueue = new LinkedTransferQueue<>();
    private final AtomicBoolean mRunning = new AtomicBoolean(true);

    private final PlaylistManager mPlaylistManager;
    private final List<ZelloBroadcaster> mZelloBroadcasters;

    // Discovery tracking
    public static final List<TwoToneDiscoveryLog> DISCOVERY_LOG = new ArrayList<>();

    // State machine for Tone A -> Tone B
    private double mCurrentToneA = 0.0;
    private int mCurrentToneABlocks = 0;
    private double mCurrentToneB = 0.0;
    private int mCurrentToneBBlocks = 0;

    public TwoToneDetector(PlaylistManager playlistManager, List<ZelloBroadcaster> zelloBroadcasters)
    {
        mPlaylistManager = playlistManager;
        mZelloBroadcasters = zelloBroadcasters;

        mExecutorService.submit(() -> {
            while (mRunning.get())
            {
                try
                {
                    AudioBufferWrapper wrapper = mAudioQueue.take();
                    processBuffer(wrapper.buffer, wrapper.segment);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                catch (Exception e)
                {
                    mLog.error("Error in TwoToneDetector", e);
                }
            }
        });
    }

    public void processAudio(float[] buffer, AudioSegment segment)
    {
        if(buffer != null && buffer.length == BLOCK_SIZE)
        {
            mAudioQueue.offer(new AudioBufferWrapper(buffer.clone(), segment));
        }
    }

    private void processBuffer(float[] buffer, AudioSegment segment)
    {
        PlaylistV2 playlist = mPlaylistManager.getCurrentPlaylist();
        if(playlist == null) return;

        List<TwoToneConfiguration> configs = playlist.getTwoToneConfigurations();
        boolean discoveryEnabled = playlist.isToneDiscoveryEnabled();

        if (configs.isEmpty() && !discoveryEnabled)
        {
            return; // Nothing to do
        }

        // To make this fully optimized, we would typically run an FFT or a bank of Goertzel filters
        // For simplicity and since we want a "magical" experience, we'll scan known configurations

        boolean matchedToneAThisBlock = false;
        boolean matchedToneBThisBlock = false;

        for(TwoToneConfiguration config : configs)
        {
            long freqA = Math.round(config.getToneA());
            long freqB = Math.round(config.getToneB());

            if (freqA <= 0) continue;
            if (!config.isLongATone() && freqB <= 0) continue;

            GoertzelFilter filterA = new GoertzelFilter(SAMPLE_RATE, freqA, BLOCK_SIZE, WindowType.BLACKMAN);
            int powerA = filterA.getPower(buffer.clone());

            if (config.isLongATone())
            {
                if (powerA > POWER_THRESHOLD_DB)
                {
                    matchedToneAThisBlock = true;
                    if(mCurrentToneA == config.getToneA())
                    {
                        mCurrentToneABlocks++;
                    }
                    else
                    {
                        mCurrentToneA = config.getToneA();
                        mCurrentToneABlocks = 1;
                    }

                    if(mCurrentToneABlocks == LONG_A_MIN_TONE_BLOCKS)
                    {
                        triggerAlertIfMatched(config, segment);
                        // Do not reset mCurrentToneABlocks to 0 here.
                        // Allow it to keep incrementing as long as the tone is present
                        // to prevent multiple triggers for the same continuous long tone.
                    }
                }
                else if (mCurrentToneA == config.getToneA())
                {
                    mCurrentToneA = 0;
                    mCurrentToneABlocks = 0;
                }
                continue;
            }

            GoertzelFilter filterB = new GoertzelFilter(SAMPLE_RATE, freqB, BLOCK_SIZE, WindowType.BLACKMAN);
            int powerB = filterB.getPower(buffer.clone());

            // Tone A detection
            if (powerA > POWER_THRESHOLD_DB)
            {
                matchedToneAThisBlock = true;
                if(mCurrentToneA == config.getToneA())
                {
                    mCurrentToneABlocks++;
                }
                else
                {
                    mCurrentToneA = config.getToneA();
                    mCurrentToneABlocks = 1;
                    mCurrentToneB = 0;
                    mCurrentToneBBlocks = 0;
                }
            }
            // Tone B detection (only valid if Tone A was previously detected and held)
            else if (powerB > POWER_THRESHOLD_DB && mCurrentToneABlocks >= MIN_TONE_BLOCKS && mCurrentToneA == config.getToneA())
            {
                matchedToneBThisBlock = true;
                if(mCurrentToneB == config.getToneB())
                {
                    mCurrentToneBBlocks++;
                }
                else
                {
                    mCurrentToneB = config.getToneB();
                    mCurrentToneBBlocks = 1;
                }


                // If B is held long enough, it's a confirmed sequence
                if(mCurrentToneBBlocks >= MIN_TONE_BLOCKS)
                {
                    triggerAlertIfMatched(config, segment);

                    // Reset to avoid multiple triggers for the same continuous tone
                    mCurrentToneA = 0;
                    mCurrentToneABlocks = 0;
                    mCurrentToneB = 0;
                    mCurrentToneBBlocks = 0;
                }

            }
        }

        // If in discovery mode and we found strong unknown tones (simulated logic for unknown frequencies)
        // A true discovery mode would require an FFT to find the strongest peak frequency
        if (discoveryEnabled && !matchedToneAThisBlock && !matchedToneBThisBlock)
        {
            // Placeholder for FFT unknown peak detection logic
            // E.g. finding a 600Hz tone that isn't mapped
            // logDiscovery(600.0, 700.0, segment);
        }
    }


    private void triggerAlertIfMatched(TwoToneConfiguration config, AudioSegment segment)
    {
        boolean shouldTrigger = true;
        // Check alias associations
        if (segment != null) {
            io.github.dsheirer.alias.AliasList aliasList = mPlaylistManager.getAliasModel().getAliasList(segment.getIdentifierCollection());
            boolean foundMapping = false;
            if (aliasList != null) {
                for (io.github.dsheirer.identifier.Identifier identifier : segment.getIdentifierCollection().getIdentifiers()) {
                    java.util.List<io.github.dsheirer.alias.Alias> aliases = aliasList.getAliases(identifier);
                    if (aliases != null) {
                        for (io.github.dsheirer.alias.Alias alias : aliases) {
                            if (alias.hasTwoToneDetector(config.getAlias())) {
                                foundMapping = true;
                                break;
                            }
                        }
                    }
                    if (foundMapping) break;
                }
            }

            // If we didn't find a mapping on the segment's aliases, we need to see if the config has ANY aliases at all.
            // If it has aliases configured but they didn't match, we skip.
            // If it has NO aliases configured, we trigger (global mode).
            if (!foundMapping) {
                boolean hasAnyMapping = false;
                for (io.github.dsheirer.alias.Alias alias : mPlaylistManager.getAliasModel().aliasList()) {
                    if (alias.hasTwoToneDetector(config.getAlias())) {
                        hasAnyMapping = true;
                        break;
                    }
                }
                if (hasAnyMapping) {
                    shouldTrigger = false;
                }
            }
        }

        if (shouldTrigger) {
            mLog.info("Two Tone Detected: {} (A:{} B:{})", config.getAlias(), config.getToneA(), config.getToneB());
            triggerAlert(config, segment);

            String channel = "Unknown";
            if(segment != null) {
                io.github.dsheirer.identifier.Identifier id = segment.getIdentifierCollection().getIdentifier(io.github.dsheirer.identifier.IdentifierClass.CONFIGURATION, io.github.dsheirer.identifier.Form.CHANNEL, io.github.dsheirer.identifier.Role.ANY);
                if (id instanceof io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier) {
                    channel = ((io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier)id).getValue();
                }
            }
            org.slf4j.LoggerFactory.getLogger(io.github.dsheirer.log.TwoToneLog.LOGGER_NAME).info("[Channel: {}] [Alias: {}] - [{}]", channel, config.getAlias(), config.getAlias());
        }
    }

    private void triggerAlert(TwoToneConfiguration config, AudioSegment segment)
    {
        String template = (config.getTemplate() != null && !config.getTemplate().isEmpty()) ? config.getTemplate() : "Dispatch Received: {Alias}";

        String alias = config.getAlias() != null ? config.getAlias() : "Unknown";
        String channel = "Unknown";
        String frequency = "Unknown";

        if (segment != null) {
            Identifier chId = segment.getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY);
            if (chId instanceof io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier) {
                channel = ((io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier)chId).getValue();
            }
            Identifier freqId = segment.getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL_FREQUENCY, Role.ANY);
            if (freqId instanceof FrequencyConfigurationIdentifier) {
                frequency = String.valueOf(((FrequencyConfigurationIdentifier)freqId).getValue());
            }
        }

        String timestamp = String.valueOf(System.currentTimeMillis());

        String text = template.replace("%ALIAS%", alias)
                              .replace("{Alias}", alias)
                              .replace("{Channel Name}", channel)
                              .replace("{Frequency}", frequency)
                              .replace("{Timestamp}", timestamp);


        if (config.isEnableMqttPublish()) {
            String payload = config.getMqttPayload() != null ? config.getMqttPayload() : "";
            payload = payload.replace("[DetectorName]", config.getAlias() != null ? config.getAlias() : "Unknown");
            payload = payload.replace("[Timestamp]", String.valueOf(System.currentTimeMillis()));
            payload = payload.replace("[Frequency]", frequency);

            MqttService.getInstance().publish(config.getMqttTopic(), payload);
        }
        for(ZelloBroadcaster broadcaster : mZelloBroadcasters)
        {
            BroadcastConfiguration bc = broadcaster.getBroadcastConfiguration();
            if(bc instanceof ZelloConfiguration)
            {
                ZelloConfiguration zc = (ZelloConfiguration) bc;
                if(config.getZelloChannel() != null && config.getZelloChannel().equals(zc.getChannel()))
                {
                    mLog.info("Sending Zello Alert to {}: {}", zc.getChannel(), text);
                    if (config.isEnableZelloTextMessage()) {
                        broadcaster.sendTextMessage(text);
                    }
                    if (config.isEnableZelloAlert() && config.getZelloAlertFile() != null && !config.getZelloAlertFile().isEmpty()) {
                        broadcaster.playAlertTone("/audio/" + config.getZelloAlertFile());
                    }
                }
            }
        }
    }

    private void logDiscovery(double toneA, double toneB, AudioSegment segment)
    {
        DISCOVERY_LOG.add(new TwoToneDiscoveryLog(toneA, toneB));
        String channel = "Unknown";
        if(segment != null) {
            io.github.dsheirer.identifier.Identifier id = segment.getIdentifierCollection().getIdentifier(io.github.dsheirer.identifier.IdentifierClass.CONFIGURATION, io.github.dsheirer.identifier.Form.CHANNEL, io.github.dsheirer.identifier.Role.ANY);
            if (id instanceof io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier) {
                channel = ((io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier)id).getValue();
            }
        }
        org.slf4j.LoggerFactory.getLogger(io.github.dsheirer.log.TwoToneLog.LOGGER_NAME).info("[Channel: {}] - [Unknown]", channel);
    }

    public void dispose()
    {
        mRunning.set(false);
        mExecutorService.shutdownNow();
    }
    private static class AudioBufferWrapper {
        float[] buffer;
        AudioSegment segment;
        AudioBufferWrapper(float[] buffer, AudioSegment segment) {
            this.buffer = buffer;
            this.segment = segment;
        }
    }
}
