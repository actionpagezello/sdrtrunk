# SDRTrunk AP Features - Session Status

> NOTE: CLAUDE.md (repo root, gitignored) is the primary session context file and is kept
> more current than this file. This file tracks build/release state at a glance.

## In Progress: ap-15.9 (committed, NOT yet built)
Three fixes, all in the tuner sample path:
1. **Polyphase buffer queue bound starved high-rate tuners** — `getBufferDuration()` truncates to 0 for any
   tuner above 1 kHz buffer rate. An RSP1B at 10 MSPS delivers 78,125 buffers/sec, so the bound collapsed to
   its ceiling: 25.6 ms of queue against a 10 ms dispatch interval, where 2 seconds was intended. Discarded
   ~2,700 buffers/sec continuously on Paxton (12.6M over 80 minutes) and prevented the RSP1B holding a lock.
   RTL-2832 was unaffected. Bound now derived in floating point; dispatchers named per tuner.
2. **Frequency correction cancelled permanently and silently** — `requestedChangeHz /= count` divides by zero
   during any quiet interval, and the uncaught throwable cancels the `scheduleAtFixedRate` task with nothing
   logged. Also present upstream — clean PR candidate.
3. **PPM baseline latch** (ap-fork) — the sanity clamp never updates the baseline on rejection, so a baseline
   that starts wrong stays wrong. The first measurement is latched unconditionally, so one taken while the tuner
   is still settling disables correction for the whole session (gradual drift is fine — the EMA follows it).
   Baseline now re-acquired after 12 consecutive rejections, with a WARN naming the tuner.

Items 2 and 3 predate ap-15.8.1 and match reports of occasional tuner lock difficulty on ap-15.7 and earlier.
Both fail silently, so they are code-reading findings with no log evidence either way.

> **ap-15.8.1 must not run with an RSP1B fitted** — see item 1. Use ap-15.9, or ap-15.7.

## Previous Release: ap-15.8.1 (2026-09-07)
- GitHub release: https://github.com/actionpagezello/sdrtrunk/releases/tag/v0.6.2-ap-15.8.1
- Zip: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-15.8.1\sdr-trunk-windows-x86_64-v0.6.2-ap-15.8.1.zip`
- Built 2026-09-07 with JDK 25 Bellsoft / Gradle 9.2, `.\gradlew clean runtimeZipWindows`. Not yet
  deployed to any site.

> `ap-15.8` is a *separate, already-deployed* build: Somerville only, 2026-08-22, carrying the
> DMR/TDMA work and nothing else. A machine reporting `0.6.2-ap-15.8` is running that, not 15.8.1.

## Previous Release: ap-15.7 (2026-08-01)
- GitHub release: https://github.com/actionpagezello/sdrtrunk/releases/tag/v0.6.2-ap-15.7
- Zip: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-15.7\sdr-trunk-windows-x86_64-v0.6.2-ap-15.7.zip`

## Contents of ap-15.8.1

1. **GUI freeze fixed** — new `SafeTableRowSorter` (`gui/control/`). The channel metadata table's
   row sorter was killing the Swing EDT when channel churn re-sorted rows whose values decoder
   threads were changing mid-sort. Decoding continued for 5+ hours with a dead GUI on Monson.
2. **`Dispatcher` queue bounded** — root cause of the Paxton 2026-09-05 `OutOfMemoryError`
   (10 GB heap exhausted in under 60 seconds after a whole-JVM stall). Unbounded
   `LinkedTransferQueue` plus unbounded `drainTo` turned a consumer stall into unbounded live-object
   growth that the collector could not reclaim. Now bounded with drop-oldest, derived per-callsite
   caps (20x–209x headroom over normal arrival rates), rate-limited overflow WARN and recovery INFO.
   Clean upstream PR candidate — the file was byte-identical to upstream.
3. **Broadcastify Calls / OpenMHz null-response NPE** — same defect already fixed for ThinLine and
   Rdio Scanner. Also a clean upstream PR candidate.
4. **Zello `HttpClient` closed on dispose** — selector/worker threads previously leaked on every
   broadcaster reconnect.
5. **Leftover `[SQUELCH DEBUG]` stdout printf removed** from `NBFMAudioFilters` (from `ae6dc745`).
6. **Eclipse build fix ported** (upstream #2434 `9dcebb49`) — `OpenMHzEditor` package declaration.
7. **"Show in Waterfall" tuner resolution fixed** — the menu action trusted the channel's
   configured preferred tuner without checking that it was actually carrying the channel, so
   channels relocated to another tuner displayed the wrong spectrum (wrong noise floor, no green
   channel column). The live processing chain source is now authoritative; the preferred tuner is
   a validated fallback. Confirmed by the reporter: setting the channel's tuner to None worked
   around it.

## GitHub
- Fork: https://github.com/actionpagezello/sdrtrunk
- Master branch has all features integrated
- Content-synced with upstream DSheirer/sdrtrunk master as of 2026-08-01 (through e2d9c4c)

## Build Environment
- JDK 25 (Bellsoft Liberica), Gradle 9.2, JavaFX, Windows 11
- Gradle now autoprovisions the JDK (foojay resolver + BELLSOFT vendor spec, upstream #2427)
- Repo path: C:\Users\Admin\projects\sdrtrunk-ap
- Build command: `.\gradlew clean runtimeZipWindows` (archiveVersion finalizer copies the zip to
  `sdrtrunk-ap-versions\v<version>\` automatically)
- Version property: `gradle.properties` -> `projectVersion=0.6.2-ap-15.9`
- 10GB heap (`-Xmx10g` in build.gradle jvmArgsWindows and jvmArgsLinux)

## Changes in ap-15.8 (deployed to Somerville 2026-08-22)
**DMR digital bleed rejected on tone-filtered NBFM** — new `TdmaInterferenceDetector` (30 ms
2-slot TDMA harmonic-comb detector) vetoes the tone gate; CTCSS loss counter now resets only on
confirmed detection; holdover bounded by a 600 ms confirmation deadline. First production day on
Somerville: 512 vetoes, no data bursts and no clipped voice. Nothing else is in this build.

## Changes in ap-15.7
See CHANGELOG.md for full details.
1. **Zello "audio data sent too fast"/"bad mid" → transient** — no longer wedge channels in
   terminal Configuration Error; stream state resets with backoff (root cause of manual
   restarts during ap-15.6 soak).
2. **Paced pending-frame flush** — 8-frame burst + 55ms/frame drain replaces the unpaced
   burst that drew server stream kills; bounded tail burst at stream stop.
3. **Pending-frame cap 15 → 30** — stops eviction of start-of-call audio (~11% of starts).
4. **Pending-stop timeout log WARN → DEBUG** — was ~53k log lines per 3 days.
5. **Upstream ports** — SampleNativeBuffer SIMD fix (#2398), 43 new DCS codes (#2424),
   NXDN radio reference editor (#2446, manual merge), NXDN talker alias log removal (#2442),
   playlist name sorting (#2342), Gradle JVM autoprovisioning (#2427).

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
11. Column width/order persistence (JTableColumnWidthMonitor)
12. Alias list alphabetical sorting (FXCollections.sort in AliasModel)
13. Diagnostics preferences panel with per-category DEBUG toggles
14. FxTableColumnMonitor for Channels editor column/sort persistence
15. CTCSS/DCS/NAC auto-import from Radio Reference tone field (FrequencyEditor)
16. NXDN decoder (upstream merge, ap-15) + NXDN radio reference import (ap-15.7)
17. TDMA (DMR / P25 Phase 2) interference rejection on tone-filtered NBFM channels (ap-15.8)
18. Fault-tolerant table row sorting — SafeTableRowSorter (ap-15.8.1)
19. Bounded Dispatcher queue with drop-oldest and overflow reporting (ap-15.8.1)

## Key Zello File Paths
- AbstractZelloBroadcaster.java -> audio/broadcast/zello/ (shared base, paced flush, watchdog)
- ZelloProtocolUtil.java -> audio/broadcast/zello/ (constants + transient error classification)
- ZelloChannelConfiguration.java -> audio/broadcast/zello/ (shared config interface)
- ZelloBroadcaster.java -> audio/broadcast/zello/ (Work — thin subclass)
- ZelloConsumerBroadcaster.java -> audio/broadcast/zello/ (Consumer — thin subclass)
- ZelloSharedConnection.java -> audio/broadcast/zello/ (shared WebSocket pool)
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

## Upstream sync (checked 2026-09-07)
11 commits behind `upstream/master`, but 8 are already content-present (hand-ported in ap-15.7;
compare with `git diff --ignore-cr-at-eol -w`, since some fork files are CRLF and upstream is LF).
Genuinely outstanding:
- `9dcebb49` eclipse build fix — **taken in ap-15.8.1**
- `af5dbcfc` dark mode (#2411) — deferred, cosmetic, 19 files / +1767 lines, touches the fork's
  customized preference plumbing
- `80360029` JDK 26 + Gradle 9.6.1 + library updates (#2450) — **hold for ap-15.9 as its own
  release.** Bumps JTransforms 3.1 -> 3.2 (the FFT in the IFFT dispatcher, the exact path implicated
  in the Paxton OOM) and changes Vector API codegen across a fleet with mixed AVX2 support. Taking
  it wholesale would also clobber `-Xmx10g`, `-Dprism.order=d3d,sw` and the ap-fork lazy platform
  configuration in `runtimeZipCurrent` — hand-merge required.

## Queued for ap-15.9
- **Audit every tuner's sample rate across the fleet** — an RTL-2832 above ~2.56 MSPS drops USB
  samples; see the RTL-2832 section in CLAUDE.md. On Daly, Methuen Police went from 1 call to 246
  in the same clock window by moving 2.88 -> 2.4 MSPS. Configuration change, not code, and the
  highest-value item outstanding. The failure mode is silence, not a logged error.
- **P25 unknown-ALG-ID should be visible, not silent** (revised — was "double-confirm").
  `Encryption.UNKNOWN` is treated as encrypted and mutes the call with no log line at any level.
  The Daly evidence shows the corruption came from sample loss at the tuner, not the decoder, so
  the double-confirm approach is the wrong shape — it would add latency to every genuinely
  encrypted call to compensate for a problem better fixed upstream. Keep the safe default, but log
  the raw ALG ID at WARN so the episode is diagnosable.
- **Warn at startup when a tuner is configured above its safe sample rate** — SDRTrunk knows the
  tuner class and the configured rate. One log line would have caught the Methuen problem months
  earlier.
- **Revisit `-Xmx10g`** — fleet steady state is under 1 GB. Needs a fleet-wide check before changing.
