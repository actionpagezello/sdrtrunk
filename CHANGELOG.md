# Changelog

All notable changes to the SDRTrunk AP fork, 1.0.0 series (branch `ap-1.0`).

Versions are `<upstream version>-ap-<n>`: the upstream SDRTrunk release the build is based on, then the fork's own
counter. The 0.6.2 series (ap-15.x, ap-16.x and the `edacs` test builds 1-25) is in `CHANGELOG-0.6.2.md`.

## [1.0.0-ap-10] - 2026-11-19

Upstream's four changes of 19 November.  Builds on ap-9.

### Added from upstream
- **Copy rows with Ctrl-C** (#2691): in the Now Playing Events and Messages tabs several rows can be selected and
  copied, one line per row with the visible columns separated by tabs, as the old Swing tables allowed.
- **Talker aliases** (#2697): when a system sends both a radio's short ID and its fully qualified ID, the automatic
  talker-alias feature creates one alias for both instead of two.
- **"Group" renamed "Category"** in the alias editors (#2696), so it isn't read as a talkgroup.  Screen labels only;
  playlists are unchanged.
- **No misleading warning before SIMD calibration** (#2698): the signal-processing factories treat "not calibrated
  yet" as a normal state and use the standard code without logging an "unrecognized implementation" warning.

### Fixed in the fork
- **Error when a channel stops during a tuner sample rate change** (upstream race, `ConcurrentModificationException`
  in FrequencyController, seen once each on Paxton and Monson on 18 November and on Paxton on ap-2).  Stopping a
  channel removed it from the list being notified of the change; the other channels on that tuner could miss the
  notification.  The list is now copy-on-write, so each notification walks a snapshot.
- **Dropped-sample warnings only for sustained loss.**  Since ap-9 counts RSP samples correctly, Daly's two RSP1Bs
  warned about single 20-40 ms gaps every 15-90 minutes (0.00% of the stream).  A drop summary is now a warning only
  when at least 0.01% of the stream was lost over the summarized period (Monson's 1.3-1.8% and Paxton's 0.04% still
  warn); isolated short gaps go to the debug log.  Detection itself is unchanged.

### Checks
- Tests: 611, all pass, including upstream's new history-table copy and talker-alias tests, a test that a channel
  removing itself during a broadcast no longer fails (fails on ap-9), and the warning threshold checked against the
  18 November drop summaries.

## [1.0.0-ap-9] - 2026-11-18

Upstream's channelizer buffer change (#2672) of 18 November, on its own because it affects how samples flow from
every tuner.  Builds on ap-8.

### Changed from upstream, merged with the fork's own queue fix
- **The queue between a tuner and its channels is sized by time, not by buffer count.**  It held 500 buffers, which
  is about 85 ms at an RSP's buffer size but about 6.8 s at an RTL-SDR's, so a short processing stall dropped
  samples on RSP tuners.  The queue now holds 2 seconds of samples on every tuner (upstream uses 1 second; the fork
  keeps the 2 seconds it has run with since ap-15.9.1).
- **Until a tuner reports its sample rate, the queue starts with a generous provisional size** (2 seconds at
  10 MSPS), as the fork did since ap-15.9.1.  Upstream sizes it from the rate when it is still zero, which would
  start the queue at a single sample - the cause of the RSP1B sample starvation fixed in ap-15.9.1.
- **RSP tuners reported twice the samples they delivered** (upstream fix).  This corrects upstream's sample drop
  monitor on RSP1B machines (Daly, Somerville, Audubon) and the spectral display's buffer handling.
- **Channels copy only their own samples** out of the channelizer's output (upstream), so a slow channel queues only
  its own data and the shared sample arrays go back to the channelizer straight away.
- Queue overflow warnings still name the tuner and report the bound, now in samples.

### Checks
- Tests: 600, all pass, including upstream's queue and channel batch tests and a fork test for changing the queue
  bound while running (provisional bound to real bound).
- The replays feed decoders directly, so they don't exercise this change; it needs a trial on tuners - an RSP1B
  machine (Daly or Somerville) and Cambridge (RTL-SDR and RSP).

## [1.0.0-ap-8] - 2026-11-18

Upstream's four changes of 18 November (02:12-03:47) other than the channelizer change, which is ap-9.

### Added from upstream
- **New Map tab** (#2681): rebuilt in JavaFX like the other new screens, replacing the old Swing map viewer.
  OpenStreetMap tiles with a memory and disk cache (up to 512 MB, filled only when the Map tab is used), radio tracks,
  the Radio Reference tower pins with their hover card and right-click menu, your location, and the map controls.
  Map settings are kept in Java preferences, not the playlist, so build 25 and ap-7 still load the playlist.
- **NXDN Type-D audio** (#2688): audio of new and late-joined calls handled correctly; call responses read correctly
  on multi-system networks.
- **Talker aliases** (#2689): the automatic talker-alias renaming (ap-5) only touches aliases in its own Talker Alias
  group; aliases you made yourself are left alone, without duplicates.
- **Display theme** (#2673): the font size preference works together with the display theme.

### Checks
- Tests: 595, all pass.
- Replays identical to ap-5: DMR (Salem State Police, OneVoice Boston) and NXDN (Georgetown).  These recordings hold
  no NXDN call responses, the message #2688 changes.

## [1.0.0-ap-7] - 2026-11-17

### Status
- Applied, tested (`.\gradlew test`), built (`sdrtrunk-ap-versions\v1.0.0-ap-7`) and pushed 2026-11-17 22:05.
  Running on Somerville, Summit 800, Cambridge and Audubon from about 22:20, with every Diagnostics box off.
  Rollout to Daly (first), Baker, SDR22, Monson and Paxton once the overnight logs are checked; build 25 stays the
  fallback for a week.

### Not added
- **Noise reduction for Motorola Type II and EDACS analog voice** (the Boston SmartZone background).  A noise gate
  (`1.0.0-ap-8`, never applied) left the noise audible at the start of each call and pumping with the voice.  Also
  tried on three Boston calls and rejected by ear: spectral noise reduction (removes 15-30 dB between words but sounds
  tinny and "gargles" under the voice), fixed filters like the NBFM chain (low-pass, hiss shelf, notch: smooth but no
  better), and RNNoise (erases narrowband speech).  The noise is broadband hiss across the voice band about 25 dB
  under speech; it is left as is.  The analog voice path keeps only its high-pass filter.  The gate is on the
  workspace branch `ap-8-noise-gate-rejected` only.
- **NBFM channel filter:** upstream's filter (#2556) and the fork's give the same trade-off once their thresholds are
  matched (Somerville Police, 24.2 s of tone: fork at 0.16 passes 15.5 s with 7.0 s of other audio; upstream at 0.22
  passes 15.0 s with 9.0 s).  The fork's filter stays; raising Somerville Police to 0.16/0.16 and Everett Police to
  0.16/0.25 on Now Playing > Channel is an option.

### Changed
- **The log keeps only what is needed; per-call detail moves behind the Diagnostics tick boxes.**  Now DEBUG, written
  when the category is on:
  - Zello stream started / stopped (Zello streaming, on by default): ~17,000 lines in 5 hours on Somerville.
  - EDACS call summary, "voice channel open ... audio ... damaged frames" (EDACS / ProVoice decoder).
  - Motorola Type II call summary (Motorola Type II decoder).
  - ProVoice "silence throughout - not recorded" (EDACS / ProVoice decoder).
  - NBFM "Tone coverage (measurement only)", one per call (CTCSS / DCS squelch codes).
  - P25 Phase 1 "call closed after ... without audio" (P25 decoder).
  Still INFO: call lines (who, when, streams), warnings and errors, the 10-minute P25 health and sync summaries,
  tuner frequency error, start-up and shutdown.  `tools/fleetcheck.py` needs Zello streaming on to check for wedged
  Zello streams (it is on unless turned off).
- **The log lists the Diagnostics categories that are on and off** after the storage directories at start-up.  They
  are applied before the log file opens, so the log didn't show them.

## [1.0.0-ap-6] - 2026-11-17

### Fixed
- **False "dropping samples" warnings on RTL-SDR tuners that only carry traffic channels** (Cambridge, RTL-2832/R828D
  20260003: 141 warnings in 4.8 hours, 36 ms to 2.9 s each).  The fork keeps an RTL tuner's stream running while it
  has no channels and discards those samples on arrival (edacs-10, the Monson crash fix).  Upstream's sample drop
  monitor (#2662) counts only the samples passed on to channels, so each idle gap of under 2 seconds between traffic
  channels was reported as samples lost on the USB bus.  The discarded samples are now counted too.  A real USB
  drop still shows, as a shortfall in the samples arriving.

### Trial of ap-5
- Somerville, Summit 800, Cambridge and Audubon from about 10:45 on 2026-11-17, 4.7-4.9 hours each: no uncaught
  exceptions, no decoder errors; Audubon closed twice on ap-5 (10:55, 15:10) with no errors.  Audubon DMR (OneVoice
  Haverhill, Salem State) and NXDN (Georgetown) calls decode with audio; Georgetown recordings at normal level.

## [1.0.0-ap-5] - 2026-11-17

Upstream's four changes of 17 November (02:25-06:51) and two shutdown fixes.

### Added from upstream
- **Waterfall right-click on a signal** (#2671): Radio Reference options; the pop-up card lists matching channels,
  playlist channels first (enabled ones first), then Radio Reference ones by distance from your location.
- **Alias list tracks a Radio Reference system** (#2676), optional, with automatic talkgroup alias updates and a new
  preferences page.  Alias list names stay sorted, as in the fork.  Stored in the playlist as `alias_list`; older
  builds ignore it, so build 25 still loads the playlist.
- **Talker aliases added to the alias list automatically** (#2680), per channel, for P25, DMR and NXDN.  Channels
  already in a playlist keep it off; new channels have it on.  Nothing changes in an alias list until it is
  turned on for a channel.
- **Alias List column in Now Playing** (#2683); sorts like the other text columns.
- README.

### Fixed
- **Error in Now Playing when channels close** (Somerville and Audubon, 2026-11-16): the table's selection could land
  on a row whose channel had already gone; also wrong in upstream.
- **Error stopping a multi-frequency channel while it hunts** (OneVoice Confirmed LCN, Audubon): a frequency rotation
  and the stop could both take the same tuner channel source.  The swap is now locked, and a source that arrives after
  the stop is handed back to the tuner.

### Checks
- Tests: 579, all pass.
- Replays identical to ap-4: P25 Phase 1 (5 Summit Central recordings), EDACS (MBTA), Motorola Type II (Monson),
  ProVoice, DMR (Salem State Police, OneVoice Boston) and NXDN (Georgetown).

### Trial of ap-3 and ap-4
- Somerville and Summit 800 on ap-4 from 22:15 on 2026-11-16; Cambridge on ap-2.  Overnight to 07:31: no uncaught
  exceptions, no decoder errors, memory flat; Summit 800 Airspy 0 dropped samples in 451 minutes; three Zello
  disconnects (server side), each back on the first attempt.  Second night for ap-2: Watertown 56 -> 135, Cambridge
  Fire Ch 5 EMS 53 -> 128, Pro EMS 120 -> 231 Zello transmissions, so the first night's lower counts were traffic.

## [1.0.0-ap-4] - 2026-11-16

### Fixed
- **Right-click on a Now Playing channel did nothing** (Somerville and Summit 800, first minutes of ap-3).  Upstream's
  new channel table (#2660) builds the menu as it opens, but JavaFX doesn't open a menu that is empty, so the menu
  never opened and was never built.  The menu is now built when the row is right-clicked, before it opens.  Also wrong
  in upstream.  Checked under a virtual display: the old way opens nothing, the new way opens the menu.

## [1.0.0-ap-3] - 2026-11-16

Upstream's 15 changes after the 1.0.0 release (13-16 November), with the fork carried over where they meet, and the
two editor fixes from the ap-2 trial.

### Added from upstream
- **P25 "Ignore Encrypted Calls"** (#1045): Phase 1 and Phase 2 editors.  A control channel doesn't take a traffic
  channel for a grant marked encrypted.  On the Phase 2 editor it sits where upstream put it; the fork's "Ignore
  Unaliased TGs" moves one place right.
- **New spectrum and waterfall** (#736), JavaFX, for tuners and the Now Playing channel tab.  The fork's "Show in
  Waterfall" still zooms 16x on the channel.
- **Radio Reference on the map and spectrum** (#2641), with a premium subscription.  The fork's EDACS site
  configuration and the tone/NAC filter from a frequency's tone field are kept.
- **DMR:** voice frames need at least one CRC-valid audio frame (#2652); Hamming and BPTC error correction (#2656,
  #2658); corrected EMB QR(16,7,6) table (#2645).  The fork's own EMB decoder rule (edacs builds) is replaced by
  upstream's: it made up for EMB words that were always two bits from a valid word (Salem State Police), and the wrong
  table was the cause.  Not replayed: no DMR recording in the workspace.
- **NXDN audio** uses the codec's gain control only (#2654).  With JMBE 2.1.1 the fork already skipped the old 5x gain,
  so audio is unchanged.  NonClippingGain is kept for P25 Phase 1 and ProVoice with an older JMBE library.
- **Network tuners, rtl_tcp** (#2471).
- **Now Playing rebuilt in JavaFX** (#2660): Talker column, column chooser; Tuner column (#315); System and Site
  columns, hidden by default (#2669).  Talker alias handling reworked in the P25, DMR and NXDN decoders.
- **Sample drop monitor** (#2662): logs a tuner that delivers fewer samples than its sample rate.  Off for Airspy and
  HydraSDR, whose drivers count dropped samples exactly and whose count the fork already logs once a minute
  (WidebandStreamMonitor).  RTL-SDR and SDRplay tuners gain it; the fork had nothing for them.
- Headless start fix (#2620); README.

### Carried over to the new Now Playing
- Right-click **Mute / Unmute** (alias Listen toggle, or this channel only when it has no alias; tooltip says which),
  the alias of the channel's last call, and the mute of a channel without an alias re-applied when it restarts.
- Right-click **Show in Waterfall**.
- **Sortable columns**, text without regard to case; the sort is kept between sessions for every Now Playing table.
- **Details tab:** channel configuration header (decoder, frequencies, alias list) and calls by talkgroup.
- **Events and Messages:** filter choices and history size kept between sessions (message history size is new).
- P25 Phase 1 "Talkgroup To Assign" still replaces the talkgroup on calls.

### Changed
- **P25 Phase 1 editor: the "Talkgroup To Assign" box is the same width as the NAC box** (120).  Upstream's voice
  codec controls make its grid column grow, so the box stretched across the editor.
- **Voice codec sliders (comfort noise and tone volume) have a fixed width** (200) on the DMR, NXDN and P25 Phase 1
  and Phase 2 editors.  Upstream lets them stretch; the value and reset button now sit beside the slider.

### Checks
- Tests: 558, all pass (in the workspace under a virtual display; upstream's JavaFX table tests need one).
- Replays against ap-2, identical output: P25 Phase 1, 5 Summit Central recordings, 820 messages; EDACS (MBTA),
  30,651 messages; Motorola Type II (Monson), 5,033 messages; ProVoice, 40 recordings, 1,621 messages.
- Not seen on screen: the new Now Playing, spectrum, map and editors.

### Trial of ap-2
- `1.0.0-ap-2` on Somerville, Cambridge, Monson, Summit 800 and Paxton (from 10:18 on 2026-11-16): no uncaught
  exceptions, memory steady, Code 3 Stream uploads all accepted (Paxton 1,699 of 1,699).  Paxton and Monson both
  sign in to the Zello accounts for C-PTL-1 and C-PTL-3 and kick each other off; a playlist matter, left as it is.
  Somerville and Cambridge Zello counts lower on the first night: night traffic.

## [1.0.0-ap-2] - 2026-11-15

### Fixed
- **Background noise on digital Motorola Type II and ProVoice calls** (Cambridge, first hour of 1.0.0-ap-1: "slight
  background rumble in the SmartZone channels"). Build 25 gave every voice codec its settings when the codec was
  loaded - gain control on, comfort noise at the default 0.05. Upstream's codec module, taken in ap-1, only sets them
  from a channel's own JMBE settings, and these two kinds of voice channel have none, so they ran on the library's
  defaults. The build 25 defaults are applied again when the codec loads; a channel's own settings still replace them.
  Not confirmed by replay: the workspace has no JMBE library, so the codec's audio cannot be compared there.
- Windows package made from the installed JDK (the pinned download no longer matched it).
- The versions folder only receives the zip of the version being built (v1.0.0-ap-2 had also received the ap-1 zip,
  left in the build folder by the earlier build). Build script only; the program is unchanged.

### Trial
- `1.0.0-ap-1` ran on Somerville from 18:13 and Cambridge from 18:09 on 2026-11-15; `1.0.0-ap-2` replaced it on both
  at about 19:35. First-hour logs of ap-1: no uncaught exceptions, playlists complete, Zello 29 of 29 logged on at
  Somerville (722 transmissions started in 46 min against 710 in the 46 min before on build 25), Cambridge 43 Type
  II and 159 EDACS calls with none lost at start, no tuner unavailable.
- The background heard on the Boston SmartZone analog calls at Cambridge is in the transmissions themselves (see
  SESSION-STATUS.md); not changed by this build.

## [1.0.0-ap-1] - 2026-11-15

The fork moved onto upstream SDRTrunk 1.0.0 (`v1.0.0`, `73d32386`). Everything the fork had in `edacs` build 25 is
carried across; `MIGRATION-1.0.md` has the inventory, the departures from it and the checks.

### New to the fork (comes with 1.0.0)
- Dark theme and the Appearance preference page; accessibility labels on the editors.
- LimeSDR tuner support; MotoTRBO LRRP additions; duplicate-library check at start-up.
- Upstream's JMBE controls on the DMR, NXDN and P25 channel editors (comfort noise, tone gain, AGC, legacy tone
  levels). They replace the fork's own JMBE pane.
- Java 27 (was 25).

### Changed
- **Code 3 Stream.** The stream type the fork called "ThinLine Radio" is now "Code 3 Stream". The upload format is
  unchanged. A stream saved under the old name loads as a Code 3 Stream with its settings. The Diagnostics tick box
  keeps its saved state under the new label.
- **Thinline Radio.** Upstream's own stream type (#2626, added after the 1.0.0 release) is included beside it. It is
  an Rdio Scanner upload under its own name and gets the fork's upload logging and no-response fix. Upload log lines
  now start with the service name (Rdio Scanner, Thinline Radio).
- `tools/fleetcheck.py` recognises the upload log lines of all three services.
- Version comes from `gradle.properties` as before (`projectVersion`), overriding upstream's git-tag version.

### Fixed
- C4FM symbol processor: the balance and deviation calculations keep their range inside the sample buffer. An
  optimized sync offset close to the start of the buffer gave index -1 (Monson 2026-11-15, a DMR channel, once in an
  hour; the buffer was lost). The fault is upstream's and is in stock 1.0.0.

### Carried unchanged from `edacs` build 25
Zello Work and Friends & Family; NBFM tone filtering, bleed rejection, audio options, tail removal and watchdog;
P25 NAC filter, talkgroup override and decoder fixes; DMR colour code filter and call-start confirmation; RSP
recovery, rate-sized sample queue, frequency error handling; EDACS Extended Addressing and ProVoice; Motorola
Type II / SmartZone; ignore unaliased talkgroups; silent transmissions not saved or streamed; Details tab call
lists; Now Playing mute; one playlist for both programs; Diagnostics preferences; 4 GB heap.

### Not taken from upstream
- The NBFM channel filter and noise correction of #2556. Replayed on Somerville recordings they lost weak calls at
  the fleet's thresholds (see `MIGRATION-1.0.md` section 9). A later build, with a threshold conversion.
- Upstream's CTCSS/DCS classes: its tone names differ from the names the fork's playlists store.
- The stop-streaming step in the RTL tuner controller (removed on purpose in edacs build 10).
- The nightly and release GitHub workflows.

### Checked before delivery
NBFM, P25, Type II control, EDACS control and ProVoice recordings decode identically to build 25; the Somerville and
Cambridge playlists load to identical settings; 430 tests, 429 pass (one needs a display). Nothing on screen has been
seen, and the Windows package has not been built.
