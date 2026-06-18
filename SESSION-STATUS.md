# SDRTrunk AP Features - Session Status

## Current Build: ap-14.9.12
Location: `C:\Users\Admin\projects\sdrtrunk-ap\build\image\sdr-trunk-windows-x86_64-v0.6.2-ap-14.9.12.zip`

Archive copy: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-14.9.12\`

## GitHub
- Fork: https://github.com/actionpagezello/sdrtrunk
- Master branch has all features integrated

## Build Environment
- JDK 25 (Bellsoft Liberica), Gradle 9.2, JavaFX, Windows 11
- Repo path: C:\Users\Admin\projects\sdrtrunk-ap
- Build command: `.\gradlew runtimeZipCurrent`
- Version property: `gradle.properties` -> `projectVersion=0.6.2-ap-14.9.12`
- 6GB heap (`-Xmx6g` in build.gradle jvmArgsWindows and jvmArgsLinux)

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
10. Zello Work + Zello Consumer real-time streaming (Opus over WebSocket)
11. Column width persistence (JTableColumnWidthMonitor)
12. Column order persistence (added to JTableColumnWidthMonitor)
13. Alias list alphabetical sorting (FXCollections.sort in AliasModel)
14. Diagnostics preferences panel with per-category DEBUG toggles
15. FxTableColumnMonitor for Channels editor column/sort persistence

## Changes in ap-14.9.12
1. **AbstractZelloBroadcaster refactor** — Shared base class for Zello Work and Consumer
   broadcasters. Work/Consumer subclasses are thin hooks (~100 lines each). Shared protocol
   helpers in `ZelloProtocolUtil.java` and `ZelloChannelConfiguration.java` interface.
2. **Non-blocking stream guard and pause** — Removed `Thread.sleep` from real-time audio
   paths. Guard/pause use scheduled timers; `isRealTimeReady()` reflects pending delays.
3. **BroadcastModel startup stagger fix** — Separate reconnect slot counter resets between
   batches so manual reconnects are not delayed by prior startup slots. Startup batch resets
   on `addBroadcastConfigurations()`. Fixed missing `break` in aged-off table update switch.
4. **Zello Consumer parity** — Keepalive try/catch wrapper, channel-offline reconnect, and
   encoder shutdown timing aligned with Work broadcaster.
5. **Zello unit tests** — `ZelloProtocolUtilTest`, `ZelloBroadcasterTimingTest`,
   `ZelloSessionEpochTest` (10 tests, all passing).

## Key Zello File Paths
- AbstractZelloBroadcaster.java -> audio/broadcast/zello/ (NEW — shared base)
- ZelloProtocolUtil.java -> audio/broadcast/zello/ (NEW — constants + error mapping)
- ZelloChannelConfiguration.java -> audio/broadcast/zello/ (NEW — shared config interface)
- ZelloBroadcaster.java -> audio/broadcast/zello/ (Work — thin subclass)
- ZelloConsumerBroadcaster.java -> audio/broadcast/zello/ (Consumer — thin subclass)
- ZelloConfiguration.java -> audio/broadcast/zello/
- ZelloConsumerConfiguration.java -> audio/broadcast/zello/
- BroadcastModel.java -> audio/broadcast/ (staggered broadcaster startup)

## Test Commands
```
.\gradlew test --tests "io.github.dsheirer.audio.broadcast.zello.*"
.\gradlew runtimeZipCurrent
```

## IMPORTANT: ChannelMetadataPanel path
The correct path is `channel/metadata/ChannelMetadataPanel.java` (package `io.github.dsheirer.channel.metadata`).
It was incorrectly copied to `gui/channel/` in ap-06 which caused mute/unmute and channel names to not work.
Fixed in ap-07. The wrong file at `gui/channel/` was deleted.
