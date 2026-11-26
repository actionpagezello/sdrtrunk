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
package io.github.dsheirer.gui.nowplaying;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.channel.metadata.AliasPriorityChangedEvent;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.am.AMTalkgroup;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.settings.Settings;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork: the Now Playing channel table's right-click Mute/Unmute action, carried over from the fork's Swing
 * ChannelMetadataPanel when upstream rebuilt Now Playing in JavaFX (#2660).  Show in Waterfall is upstream's
 * ShowInWaterfallAction since #2770.
 */
public class ChannelAudioActions
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelAudioActions.class);

    /**
     * Prefix the traffic channel managers give a traffic channel's name, ahead of the parent channel's name.
     */
    private static final String TRAFFIC_CHANNEL_NAME_PREFIX = "T-";

    /**
     * AP-fork (ap-15.9.23): the alias each channel's most recent call was addressed to, keyed by getChannelMuteKey().
     * Fallback for resolveChannelAlias() between calls - see the note there. Kept in memory only: after a restart a
     * channel needs one call before its mute can find the alias again.
     */
    private final Map<String,Alias> mLastCallAlias = new ConcurrentHashMap<>();
    private final ChannelProcessingManager mChannelProcessingManager;
    private final PlaylistManager mPlaylistManager;
    private final Settings mSettings;

    /**
     * Constructs an instance
     * @param playlistManager for the alias model and processing chains
     * @param settings for the per-channel mute store
     */
    public ChannelAudioActions(PlaylistManager playlistManager, Settings settings)
    {
        mPlaylistManager = playlistManager;
        mChannelProcessingManager = playlistManager.getChannelProcessingManager();
        mSettings = settings;
    }

    /**
     * Resolves the single alias whose Listen setting governs a channel's audio, or null when no
     * single alias can be identified.
     *
     * Channel mute in the Now Playing window is a shortcut to the alias editor's Listen toggle, so
     * it must target exactly the alias the user would have toggled by hand.  Getting this wrong is
     * what broke ap-15.9 and earlier: the old code fell back to every alias in the channel's alias
     * list, so muting one channel wrote DO_NOT_MONITOR across all of them and silenced every other
     * channel sharing that list.
     *
     * Resolution, most specific first:
     *
     * 1. The live TO identifier's alias, when it resolves to exactly one.  On a trunked system this
     *    is the talkgroup actually producing the audio.
     * 2. The live FROM identifier's alias, when it resolves to exactly one.
     * 3. The only alias in the channel's configured alias list, when that list holds exactly one.
     *    This is the conventional-channel case and is unambiguous by definition.
     *
     * Anything else is ambiguous and returns null.  A channel with no live identifiers whose alias
     * list holds several aliases offers no way to tell which one the user means, and guessing is the
     * defect this replaces.
     *
     * @param metadata for the channel's live identifiers
     * @param channel to resolve for
     * @return the governing alias, or null when it cannot be identified unambiguously
     */
    Alias resolveChannelAlias(ChannelMetadata metadata, Channel channel)
    {
        List<Alias> toAliases = metadata.getToIdentifierAliases();

        if(toAliases != null && toAliases.size() == 1)
        {
            mLastCallAlias.put(getChannelMuteKey(channel), toAliases.get(0));
            return toAliases.get(0);
        }

        //AP-fork: for a conventional analog channel the governing alias is not something to be guessed
        //from the alias list - it is the channel's CONFIGURED talkgroup, which DecodeConfigAnalog holds
        //as static configuration and NBFMDecoderState/AMDecoderState publish on every call.
        //
        //The rules above consult only the LIVE TO identifier, which for NBFM exists solely during a
        //transmission - and since ap-15.9.7's squelch-close fix that identifier is cleared promptly at
        //the end of each one instead of lingering, so a right-click between transmissions reliably finds
        //nothing. Resolution then fell to the alias-list rule below, which requires the list to hold
        //exactly ONE alias. Stoneham's Wakefield list holds three (Wakefield MA Fire, Wakefield MA
        //Police, Atlantic Amb 4), so Atlantic 4 resolved to null and its mute went to the per-channel
        //store - which does not gate the audio, because AudioSegment takes the LOWEST playback priority
        //across every attached alias. The channel stayed silent with the right-click unable to change it,
        //while Littleton Fire on a single-alias list worked. That was the whole difference.
        //
        //Lookup is keyed by protocol first (AliasList.mTalkgroupProtocolMap) and returns at most one
        //alias per talkgroup, so an NBFM talkgroup can only ever match an NBFM alias in this channel's
        //own list - never a P25 or DMR alias at the same number, and never more than one.
        Alias configuredAlias = resolveConfiguredTalkgroupAlias(channel);

        if(configuredAlias != null)
        {
            return configuredAlias;
        }

        //AP-fork (ap-15.9.23): the alias this channel's last call actually went to. A digital conventional channel has
        //no configured talkgroup for the rule above - its talkgroup arrives in the call - and its live TO identifier
        //is cleared when the call ends, so between calls nothing above resolved and the mute fell to the per-channel
        //store, which does not gate audio whose segment carries an alias. Audubon 2026-10-17, Woburn Police (P25,
        //talkgroup 17250, alias Woburn Police): every right-click logged "(no alias)" and the audio stayed as it was
        //in both directions; only the alias editor's Listen toggle worked. The tooltip names the alias that will be
        //toggled, so on a trunked system - where the last call may have been any talkgroup - the choice is visible.
        Alias lastCallAlias = mLastCallAlias.get(getChannelMuteKey(channel));

        if(lastCallAlias != null && mPlaylistManager.getAliasModel().getAliases().contains(lastCallAlias))
        {
            return lastCallAlias;
        }

        List<Alias> fromAliases = metadata.getFromIdentifierAliases();

        if(fromAliases != null && fromAliases.size() == 1)
        {
            return fromAliases.get(0);
        }

        String aliasListName = channel.getAliasListName();

        if(aliasListName != null && !aliasListName.isEmpty())
        {
            Alias only = null;

            for(Alias alias : mPlaylistManager.getAliasModel().getAliases())
            {
                if(alias.hasList() && aliasListName.equalsIgnoreCase(alias.getAliasListName()))
                {
                    if(only != null)
                    {
                        //More than one alias in the list - ambiguous
                        return null;
                    }

                    only = alias;
                }
            }

            return only;
        }

        return null;
    }

    /**
     * AP-fork (ap-15.9.23): records the TO alias of a channel whose TO identifier changed, so that
     * resolveChannelAlias() can still find it after the call ends.  Called on decoder threads.
     *
     * @param metadata that changed
     */
    void rememberCallAlias(ChannelMetadata metadata)
    {
        Channel channel = mChannelProcessingManager.getChannelMetadataModel().getChannelFromMetadata(metadata);

        if(channel != null)
        {
            List<Alias> toAliases = metadata.getToIdentifierAliases();

            if(toAliases != null && toAliases.size() == 1)
            {
                mLastCallAlias.put(getChannelMuteKey(channel), toAliases.get(0));
            }
        }
    }

    /**
     * Re-applies the mute of a channel that has NO alias when its processing chain starts.  An alias-backed mute
     * needs nothing here, because AudioSegment resolves monitor priority from its aliases every time a segment is
     * created, so new chains and restarts pick it up unaided.  A channel with no alias has no such mechanism - its
     * mute lives at the audio module, which is new with this processing chain - so it has to be re-applied.
     *
     * @param channel that started
     */
    void reapplyChannelMute(Channel channel)
    {
        ChannelMetadata first = getFirstMetadata(channel);

        if(first != null && resolveChannelAlias(first, channel) == null
            && mSettings.getNowPlayingSettings().isChannelMuted(getChannelMuteKey(channel)))
        {
            setProcessingChainMuted(mChannelProcessingManager.getProcessingChain(channel), true);
        }
    }

    /**
     * AP-fork: resolves the alias for an analog channel's configured talkgroup, or null.
     *
     * Returns null for any channel whose decode configuration has no configured talkgroup - every
     * trunked system - so those keep resolving from their live TO identifier, which is correct: a
     * trunked channel's identity genuinely is whichever talkgroup is transmitting.
     *
     * Also returns null when no alias in the channel's list carries that talkgroup, which is the
     * un-aliased case: a channel being auditioned before an alias exists for it falls through to the
     * per-channel mute store and stays independent of everything else.
     *
     * @param channel to resolve for
     * @return the alias for the channel's configured talkgroup, or null
     */
    private Alias resolveConfiguredTalkgroupAlias(Channel channel)
    {
        if(!(channel.getDecodeConfiguration() instanceof DecodeConfigAnalog analogConfig))
        {
            return null;
        }

        String aliasListName = channel.getAliasListName();

        if(aliasListName == null || aliasListName.isEmpty())
        {
            return null;
        }

        AliasList aliasList = mPlaylistManager.getAliasModel().getAliasList(aliasListName);

        if(aliasList == null)
        {
            return null;
        }

        //The identifier must carry the same protocol the decoder publishes, because alias lookup is
        //keyed on protocol: NBFM channels produce NBFMTalkgroup, AM channels AMTalkgroup.
        Identifier talkgroup = switch(analogConfig.getDecoderType())
        {
            case NBFM -> new NBFMTalkgroup(analogConfig.getTalkgroup());
            case AM -> new AMTalkgroup(analogConfig.getTalkgroup());
            default -> null;
        };

        if(talkgroup == null)
        {
            return null;
        }

        List<Alias> aliases = aliasList.getAliases(talkgroup);

        return (aliases != null && aliases.size() == 1) ? aliases.get(0) : null;
    }

    /**
     * Builds the identity under which a channel's own mute state is stored, for channels that have
     * no alias to carry it.
     *
     * Channel.getChannelID() cannot serve: it is assigned from an incrementing counter at
     * construction, so it changes on every run and every traffic channel receives a fresh one.
     * System, site and name come from the playlist and do not.
     *
     * Traffic channels are created as "T-" plus the parent channel's name, carrying the parent's
     * system and site, so stripping that prefix folds a channel and its traffic channels onto one
     * identity.  The prefix is only stripped from channels of type TRAFFIC, so a conventional
     * channel whose name genuinely begins with "T-" is unaffected.
     *
     * @param channel to identify
     * @return mute identity for the channel, never null
     */
    static String getChannelMuteKey(Channel channel)
    {
        String name = channel.getName() != null ? channel.getName() : "";

        if(channel.isTrafficChannel() && name.startsWith(TRAFFIC_CHANNEL_NAME_PREFIX))
        {
            name = name.substring(TRAFFIC_CHANNEL_NAME_PREFIX.length());
        }

        String system = channel.getSystem() != null ? channel.getSystem() : "";
        String site = channel.getSite() != null ? channel.getSite() : "";

        return (system + "|" + site + "|" + name).toLowerCase();
    }

    /**
     * Indicates if a channel's audio is muted to the local speakers.
     *
     * Exactly one store is consulted, chosen by whether the channel has an identifiable alias.  When
     * it does, that alias's playback priority is the state - the same state the alias editor's
     * Listen toggle shows - and the per-channel store is not consulted at all.  When it does not,
     * the per-channel store is the state.  Because the two are never both authoritative for one
     * channel, they cannot disagree, which is the defect that broke every earlier version of this.
     *
     * @param metadata for the channel's live identifiers
     * @param channel to check
     * @return true if muted
     */
    boolean isChannelMuted(ChannelMetadata metadata, Channel channel)
    {
        Alias alias = resolveChannelAlias(metadata, channel);

        if(alias != null && alias.getPlaybackPriority() == Priority.DO_NOT_MONITOR)
        {
            return true;
        }

        //Also consult the per-channel store even when an alias resolved.  A channel can be muted
        //while no alias is identifiable - a trunked channel between calls, for instance - and then
        //acquire one when the next call arrives.  Reading only the alias in that moment would report
        //the channel as unmuted while its audio modules were still silenced, and the menu would
        //offer "Mute" on a channel the user cannot hear.  Reading the union keeps the menu honest;
        //setChannelMuted() clears both stores on unmute, so the state cannot get stuck.
        return mSettings.getNowPlayingSettings().isChannelMuted(getChannelMuteKey(channel));
    }

    /**
     * Mutes or unmutes a channel's audio to the local speakers.
     *
     * Note that this affects local playback only.  Zello and ThinLine streaming do not consult
     * monitor priority at all, so a muted channel continues to stream - which is the intended
     * behaviour here, not an oversight.
     *
     * @param metadata for the channel's live identifiers
     * @param channel to mute or unmute
     * @param mute true to mute
     */
    void setChannelMuted(ChannelMetadata metadata, Channel channel, boolean mute)
    {
        Alias alias = resolveChannelAlias(metadata, channel);

        if(alias != null)
        {
            //setAliasMuted() also clears any sticky module-level mute on every chain governed by
            //this alias, so the alias priority becomes the only thing gating the audio.
            setAliasMuted(alias, mute);

            //A channel may have been muted while no alias was identifiable and acquired one since -
            //a trunked channel muted between calls, for instance.  Migrate rather than leaving a
            //second copy behind: the alias holds the state now, so discard the per-channel entry.
            mSettings.getNowPlayingSettings().setChannelMuted(getChannelMuteKey(channel), false);
        }
        else
        {
            String muteKey = getChannelMuteKey(channel);
            mSettings.getNowPlayingSettings().setChannelMuted(muteKey, mute);

            for(Map.Entry<Channel,ProcessingChain> entry : mChannelProcessingManager.getProcessingChains().entrySet())
            {
                if(getChannelMuteKey(entry.getKey()).equals(muteKey))
                {
                    setProcessingChainMuted(entry.getValue(), mute);
                }
            }

            mLog.info("Channel [{}] (no alias) {} to local speakers from the Now Playing window",
                    muteKey, mute ? "muted" : "unmuted");
        }
    }

    /**
     * Toggles the Listen setting on the alias governing a channel.  This is the same state the alias
     * editor's Listen toggle writes.
     *
     * A segment's monitor priority is resolved from its aliases when the segment is created, so the
     * priority change alone would not affect a transmission already in progress.  Every running
     * processing chain governed by this same alias therefore has its audio modules cleared of any
     * sticky module-level mute and its current segment ended, which makes the change audible
     * immediately instead of at the end of the current call.
     *
     * @param alias governing the channel, as returned by resolveChannelAlias
     * @param mute true to mute (Listen off), false to unmute
     */
    private void setAliasMuted(Alias alias, boolean mute)
    {
        alias.setCallPriority(mute ? Priority.DO_NOT_MONITOR : Priority.DEFAULT_PRIORITY);
        mPlaylistManager.schedulePlaylistSave();

        //Keeps the alias editor's Listen toggle in step in real time
        MyEventBus.getGlobalEventBus().post(new AliasPriorityChangedEvent(alias));

        for(Map.Entry<Channel,ProcessingChain> entry : mChannelProcessingManager.getProcessingChains().entrySet())
        {
            ChannelMetadata chainMetadata = getFirstMetadata(entry.getKey());

            if(chainMetadata != null && resolveChannelAlias(chainMetadata, entry.getKey()) == alias)
            {
                //Clearing the module mute both releases any sticky mute left from a no-alias period
                //and ends the segment in progress, so the alias priority is the only thing gating
                //this channel's audio from here on.
                setProcessingChainMuted(entry.getValue(), false);
            }
        }

        mLog.info("Alias [{}] in list [{}] {} from the Now Playing window", alias.getName(),
                alias.getAliasListName(), mute ? "muted" : "unmuted");
    }

    /**
     * Applies a module-level mute to every audio module in a processing chain.  Used for channels
     * with no alias, where there is no alias priority to carry the state.  AbstractAudioModule ends
     * the current audio segment on both mute and unmute, so the change takes effect immediately.
     *
     * @param processingChain to apply to, may be null
     * @param mute true to mute
     */
    private static void setProcessingChainMuted(ProcessingChain processingChain, boolean mute)
    {
        if(processingChain == null)
        {
            return;
        }

        for(Module module : processingChain.getModules())
        {
            if(module instanceof AbstractAudioModule)
            {
                ((AbstractAudioModule)module).setMuted(mute);
            }
        }
    }

    /**
     * Returns any one channel metadata for a channel, used to resolve that channel's governing
     * alias while iterating running processing chains.
     *
     * @param channel to look up
     * @return one of the channel's metadata entries, or null if it has none
     */
    private ChannelMetadata getFirstMetadata(Channel channel)
    {
        ChannelMetadataModel model = mChannelProcessingManager.getChannelMetadataModel();

        for(ChannelMetadata candidate : model.getChannelMetadata())
        {
            if(channel.equals(model.getChannelFromMetadata(candidate)))
            {
                return candidate;
            }
        }

        return null;
    }
}
