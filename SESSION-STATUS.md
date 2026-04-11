# SDRTrunk AP Features - Session Status

## Current Build: ap-09
Location: C:\Users\Admin\projects\sdrtrunk-ap\build\image\sdr-trunk-windows-x86_64-v0.6.2-ap-09.zip

## GitHub
- Fork: https://github.com/actionpagezello/sdrtrunk
- 15 feature branches pushed
- Master branch has all features integrated

## Build Environment
- JDK 25 (Bellsoft Liberica), Gradle 9.2, JavaFX, Windows 11
- Repo path: C:\Users\Admin\projects\sdrtrunk-ap
- Features path: C:\Users\Admin\projects\sdrtrunk-ap-features
- Build command: .\gradlew runtimeZipCurrent
- Version property: gradle.properties -> projectVersion=0.6.2-ap-09
- 6GB heap (-Xmx6g in build.gradle jvmArgsWindows and jvmArgsLinux)

## Completed Features
1. CTCSS channel-level filtering (full squelch, Goertzel detector)
2. DCS channel-level filtering (full squelch, 134.4 bps slope decoder via DCSDetector.java wrapper)
3. NAC channel-level filtering (P25 built-in, already existed)
4. CTCSS aux decoder toggle in Additional Decoders (added to DecoderType.AUX_DECODERS)
5. CTCSS/DCS/NAC alias identifiers (AliasItemEditor + IdentifierEditorFactory)
6. Squelch tail/head removal (SquelchTailRemover wired into NBFMDecoder)
7. Tone Filter UI pane in NBFMConfigurationEditor
8. Mute/Unmute right-click + Show in Waterfall (16x zoom)
9. Live alias editor refresh via AliasPriorityChangedEvent
10. Zello Work streaming (auth token removed, console logging suppressed)
11. Column width persistence (JTableColumnWidthMonitor)
12. Column order persistence (added to JTableColumnWidthMonitor)
13. Alias list alphabetical sorting (FXCollections.sort in AliasModel)
14. Debug logging cleanup (ChannelMetadataPanel)

## Bug Fixes in ap-09
1. **ZelloBroadcaster "invalid stream id" fix** - Added session epoch tracking (mSessionEpoch) that
   increments on every WebSocket reconnect. Stream operations capture the epoch at start and abort if
   it changes, preventing start_stream commands and audio packets from being sent on a new connection
   using stale session state. Also fixed onClose() and onError() to unconditionally reset mStreamActive
   and mCurrentStreamId on any disconnect, eliminating stale stream IDs surviving across sessions.
   Affected channels: Chelsea MA Police, Winthrop MA Police (and any other ZelloBroadcaster channel
   with high traffic volume during WebSocket reconnects).
   File: ZelloBroadcaster.java (mSessionEpoch, mStreamSessionEpoch, sendStartStream, encodeAndSendFrame,
   onClose, onError, connectWebSocket)

## Bug Fixes in ap-08
1. **Mute now sticks across restarts** - ChannelAddListener now checks alias DO_NOT_MONITOR priority
   at channel startup, not just mMutedChannelIds. Both alias-based and non-alias mute re-applied.
   File: ChannelMetadataPanel.java (ChannelAddListener.receive)

2. **Column sorting now works** - Added TableRowSorter to the Now Playing JTable with case-insensitive
   comparators for string columns. Click column headers to sort ascending/descending.
   File: ChannelMetadataPanel.java (init method)

3. **Column sort state now saves** - JTableColumnWidthMonitor now persists and restores sort keys
   (column + sort order) alongside column widths and order. Added RowSorterListener for change detection.
   File: JTableColumnWidthMonitor.java (storeSortState, restoreSortState, SortListener)

4. **Details tab shows channel configuration** - ChannelDetailPanel now shows a "Channel Configuration"
   header with decoder type, frequency, and alias list name before the decoder state activity summary.
   Provides useful context even when P25 network data hasn't been received yet.
   File: ChannelDetailPanel.java (receive method)

5. **P25 channels now show alias names** - Fixed AliasList.TalkgroupAliasList.getAlias() and
   RadioAliasList.getAlias() to fall through from fully qualified map lookup to simple value and
   range matching when the FQ map returns null. Previously returned null immediately for
   FullyQualifiedTalkgroupIdentifier/FullyQualifiedRadioIdentifier without trying simpler matches.
   File: AliasList.java (TalkgroupAliasList.getAlias, RadioAliasList.getAlias)

6. **Listen toggle sync fixed** - Added mSuppressModification flag to AliasItemEditor that prevents
   the toggle/combo change listeners from firing during @Subscribe AliasPriorityChangedEvent handling.
   Eliminates race condition between programmatic UI updates and user-initiated changes.
   File: AliasItemEditor.java (aliasPriorityChanged, getMonitorAudioToggleSwitch, getMonitorPriorityComboBox)

## Key File Paths (replacement files -> source tree)
- NBFMDecoder.java -> module/decode/nbfm/
- DCSDetector.java -> module/decode/nbfm/ (NEW file)
- NBFMConfigurationEditor.java -> gui/playlist/channel/
- DecoderType.java -> module/decode/
- AliasItemEditor.java -> gui/playlist/alias/
- IdentifierEditorFactory.java -> gui/playlist/alias/identifier/
- logback.xml -> src/main/resources/
- AbstractAudioModule.java -> audio/
- ChannelMetadataPanel.java -> channel/metadata/ (NOT gui/channel/)
- AliasPriorityChangedEvent.java -> channel/metadata/ (NEW file)
- TunerEvent.java -> source/tuner/
- TunerSpectralDisplayManager.java -> source/tuner/ui/
- ZelloBroadcaster.java -> audio/broadcast/zello/
- ZelloConfiguration.java -> audio/broadcast/zello/
- JTableColumnWidthMonitor.java -> preference/swing/
- AliasModel.java -> alias/
- AliasList.java -> alias/
- ChannelDetailPanel.java -> channel/details/

## IMPORTANT: ChannelMetadataPanel path
The correct path is channel/metadata/ChannelMetadataPanel.java (package io.github.dsheirer.channel.metadata).
It was incorrectly copied to gui/channel/ in ap-06 which caused mute/unmute and channel names to not work.
Fixed in ap-07. The wrong file at gui/channel/ was deleted.

## Friend's Features (already in source tree from previous commits)
All 12 features from the friend are integrated in the source tree via previous git commits on master.
They also have individual feature branches on the fork for PR submission.
