# Changelog

All notable changes to the SDRTrunk AP fork, 1.0.0 series (branch `ap-1.0`).

Versions are `<upstream version>-ap-<n>`: the upstream SDRTrunk release the build is based on, then the fork's own
counter. The 0.6.2 series (ap-15.x, ap-16.x and the `edacs` test builds 1-25) is in `CHANGELOG-0.6.2.md`.

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
