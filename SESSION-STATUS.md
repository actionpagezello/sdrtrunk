# SDRTrunk AP Features - Session Status

## Current Build: ap-14.9.13
Location: `C:\Users\Admin\projects\sdrtrunk-ap\build\image\sdr-trunk-windows-x86_64-v0.6.2-ap-14.9.13.zip`

Archive copy: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-14.9.13\`

## GitHub
- Fork: https://github.com/actionpagezello/sdrtrunk
- Master branch has all features integrated

## Build Environment
- JDK 25 (Bellsoft Liberica), Gradle 9.2, JavaFX, Windows 11
- Repo path: C:\Users\Admin\projects\sdrtrunk-ap
- Build command: `.\gradlew runtimeZipCurrent`
- Version property: `gradle.properties` -> `projectVersion=0.6.2-ap-14.9.13`
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

## Changes in ap-14.9.13
1. **Manual Reconnect fix** — Reconnect no longer reuses the cold-start `mStartupSlot` counter.
   `DelayedBroadcasterStartup` passes a reconnect flag so the broadcaster connects immediately
   after its reconnect delay (slot 0 = 0ms), instead of waiting ~34s after a full startup.
2. **Zello startup rate limiting** — Cold-start connections batched at 9 per minute (1s apart),
   then a 60s pause before the next batch, staying under Zello's documented 10 new WebSockets/min/IP.
   ~33 broadcasters take ~3 minutes to fully connect instead of ~33 seconds.
3. **Ghost stream fix** — Ghost detection only when `stream_id` is still pending (-1). Explicit
   `start_stream` failures (-2, including `channel busy`) no longer increment the ghost counter
   or force a session reconnect after 3 strikes.
4. **`channel busy` handling** — Added to transient errors in `ZelloProtocolUtil`. Failed starts
   schedule cooldown with 750ms minimum backoff plus configured pause/guard times instead of
   disconnecting. `handleStartStreamFailure()` centralizes this path.
5. **Reconnect stagger** — Manual reconnects spaced 2s apart (`RECONNECT_STAGGER_MS`), separate
   from cold-start batch timing.
6. **Tests** — `ZelloProtocolUtilTest` extended for `channel busy` transient and backoff helpers.

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
- AbstractZelloBroadcaster.java -> audio/broadcast/zello/ (shared base)
- ZelloProtocolUtil.java -> audio/broadcast/zello/ (constants + error mapping)
- ZelloChannelConfiguration.java -> audio/broadcast/zello/ (shared config interface)
- ZelloBroadcaster.java -> audio/broadcast/zello/ (Work — thin subclass)
- ZelloConsumerBroadcaster.java -> audio/broadcast/zello/ (Consumer — thin subclass)
- ZelloConfiguration.java -> audio/broadcast/zello/
- ZelloConsumerConfiguration.java -> audio/broadcast/zello/
- BroadcastModel.java -> audio/broadcast/ (staggered broadcaster startup + reconnect)

## Test Commands
```
.\gradlew test --tests "io.github.dsheirer.audio.broadcast.zello.*"
.\gradlew runtimeZipCurrent
```

## IMPORTANT: ChannelMetadataPanel path
The correct path is `channel/metadata/ChannelMetadataPanel.java` (package `io.github.dsheirer.channel.metadata`).
It was incorrectly copied to `gui/channel/` in ap-06 which caused mute/unmute and channel names to not work.
Fixed in ap-07. The wrong file at `gui/channel/` was deleted.
