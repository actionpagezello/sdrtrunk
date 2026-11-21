# Changelog

All notable changes to the SDRTrunk AP fork, 1.0.0 series (branch `ap-1.0`).

Versions are `<upstream version>-ap-<n>`: the upstream SDRTrunk release the build is based on, then the fork's own
counter. The 0.6.2 series (ap-15.x, ap-16.x and the `edacs` test builds 1-25) is in `CHANGELOG-0.6.2.md`.

## [1.0.0-ap-15] - 2026-11-21

Fix for HydraSDR RFOne tuners that wouldn't start (Denmark).  Builds on ap-14.

### Fixed in the fork
- **Earlier HydraSDR RFOne units are started as HydraSDR tuners.**  These units use the Airspy USB ids (1d50:60a1)
  and only differ from an Airspy in their USB serial string (`HYDRASDR SN:...`).  The 0.6.2 builds drove them with
  the old Airspy code, which didn't check the serial string, so they ran as Airspy tuners.  The 1.0.0 builds use
  libairspy, which only opens devices whose serial starts with `AIRSPY SN:`, so they failed with
  `airspy_open_sn failed: AIRSPY_ERROR_NOT_FOUND (-5)`.  Tuner discovery now reads the serial string of each
  Airspy-id device and starts an RFOne as a HydraSDR tuner; libhydrasdr supports the RFOne on both its own
  (38af:0001) and the Airspy USB ids.  The log names each RFOne found this way.  An RFOne set up under the old
  build as an Airspy keeps its channels, but its tuner settings (gain, sample rate, PPM) start from the HydraSDR
  defaults and need to be set again in the Tuners tab.

## [1.0.0-ap-14] - 2026-11-20

Upstream's six changes of 20 November, including the new JavaFX Tuners tab, with the fork's gain tools moved onto
it.  Builds on ap-13.

### Added from upstream
- **New Tuners tab** (#2720): a tuner list with status pills and configurable columns, an Add Tuner menu, and a
  sectioned editor - header with status, enable switch, spectrum/record/restart; Tuning; Gain; Options; Frequency
  Correction; collapsible Frequency Limits.  Every tuner editor rebuilt in JavaFX.  **New frequency control**: hover
  a digit and scroll, or click its upper or lower half, to step it; or type a frequency (e.g. 851.0125) and press
  Enter.  Upstream fixes in the same change: RSPdx saved the FM notch as the DAB notch and never applied the HDR
  bandwidth; RSP2 external reference output and network tuner PPM weren't saved; FCD Pro gain/phase correction loaded
  swapped; Airspy/HydraSDR gain mode changes now apply the mode's gains.
- **Waterfall contrast for weak signals** (#2712): the waterfall's colours follow the noise floor, so weak signals
  stand out the same at any gain, sample rate or FFT size.
- **App branding, icons and a startup splash screen** (#2718, #2721).  The splash shows the fork's version.
- Test and README fixes (#2713, #2715).

### Changed in the fork
- **Signal level meter and Gain Check moved to the new Tuners tab.**  They are now a "Signal Level" section after the
  Gain section of the RTL-SDR, Airspy, HydraSDR and RSP editors, and the Gain Check window is a JavaFX window.  How
  they measure and recommend is unchanged from ap-13 (control channel or noise floor, 30-minute overload history).
- The fork's RSP recovery (re-finding an RSP the SDRplay API reported as removed and restarting its channels) and the
  logging of uncaught exceptions carried over unchanged.

### Checks
- Tests: 673, all pass, including upstream's new frequency control and tuner info tests and the gain check on a
  simulated tuner (its gain settings now applied on the JavaFX thread).

## [1.0.0-ap-13] - 2026-11-19

Gain Check measures against the tuner's control channel.  Builds on ap-12.

### Changed in the fork
- **Gain Check uses the control channel when the tuner has one.**  It finds the control channels running on the
  tuner (P25, DMR, NXDN, EDACS, Type II and other trunking decoders; a multi-frequency one on its current frequency)
  and, before changing anything, keeps the one that is on the air continuously: at least 10 dB above the noise in
  each of 4 measurements and steady to within 3 dB.  At each gain setting it measures that channel's
  signal-to-noise ratio, and recommends the lowest setting within 1 dB of the best.  A control channel is on the air
  the whole time and is a signal you decode, so every setting is measured against the same thing.
- **Without a control channel, the noise floor decides** (conventional channels): the setting where the noise floor
  has risen 3 dB above the tuner's own noise.  The "strongest signals in the band" reading is still shown but no
  longer decides: on a busy band it measured whatever happened to be transmitting.
- The 30-minute overload history from ap-12 still limits how high either rule can go.
- The window has a Control Channel column and says which rule it used; the log names the control channel.

### Checks
- Tests: 649, all pass, including a full Gain Check on a simulated tuner with a control channel (picked, measured at
  each setting, recommendation before overload) and the noise floor rule ignoring a passing transmission.

## [1.0.0-ap-12] - 2026-11-19

Gain Check fixes after the first day of ap-11, and the EDACS System Key option.  Builds on ap-11.

### Fixed in the fork
- **Gain Check no longer recommends a gain that overloads a while later.**  On Somerville, ap-11 sent an RTL from
  32.8 to 42.1 dB, where it then overloaded most of the time: a strong transmitter that keys up now and then happened
  to be off during that setting's 2-second measurement.  Gain Check now also uses how the tuner did at its own setting
  over the last 30 minutes (kept for each tuner; changing the gain starts a new history):
  - overload at your setting in that time: only lower settings are recommended;
  - no overload: a higher setting only as far as the highest peak in that time leaves room for, keeping 3 dB of
    headroom (RTL-SDR); one setting higher at most, and only with peaks at or below -6 dBFS (Airspy, HydraSDR, RSP);
  - less than 5 minutes at your setting: nothing higher.  Run it after the tuner has been at one setting a while.
- **When traffic comes and goes, the noise floor decides.**  Each setting's strongest signals are now measured twice.
  When they were steady (a control channel), the recommendation is as before: the lowest setting within 1 dB of the
  best signals.  When they weren't, it is the setting where the noise floor has risen 3 dB above the tuner's own noise
  - past that, more gain mostly amplifies noise.  The window and the log say which rule was used.
- **The 5-minute overload warning ignores Gain Check's own test settings.**  ap-11 logged one false overload warning
  right after every check.

Replayed on ap-11's three Gain Check results: Summit 800's Airspy stays at 10; the Somerville RTL that went to 42.1 dB
gets 28.0 dB; the one that overloaded at 15.7 dB gets a lower setting.

### Added in the fork
- **EDACS System Key (ESK)** switch in the EDACS channel editor (off by default): for systems that XOR the control
  channel messages with 0xA0.  Not tested on the air (MBTA doesn't use it).  Also added to the upstream EDACS pull
  request (#2706) after a reviewer asked for it.

### Checked, no change needed
- **EDACS encryption:** 4,088 MBTA ProVoice transmissions recorded on Cambridge (2026-11-19 15:21-20:08, 24
  talkgroups) all decode as speech, and no header carries an unexplained flag - MBTA doesn't appear to use encryption,
  so no encrypted-call handling was added.  2% of calls had more than 30% of their voice frames damaged, spread evenly
  over all 13 voice channels: reception, not one channel.

### Checks
- Tests: 647, all pass, including the three ap-11 Gain Check tables replayed against the new recommendation, history
  kept per setting, and test settings kept out of the history and the log.

## [1.0.0-ap-11] - 2026-11-19

Upstream's channel signal meter (taken from its branch before it is merged), a 20-band graphic equalizer, and new
tools for setting tuner gain: overload warnings in the log, a signal level meter in each tuner panel and a Gain Check
that recommends a setting.  Builds on ap-10.

### Added from upstream (branch `2646-channel-snr`, not yet in upstream master)
- **Channel signal strength and SNR readout** (#2646): the channel spectrum view (Now Playing, Channel Spectrum tab)
  shows the selected channel's power in dBFS and its signal-to-noise ratio, below the channel bandwidth label.  The
  noise floor is measured from the tuner's own samples near the channel, so it works on every tuner type.  The
  measurement runs only while a channel spectrum is open and stops a few seconds after it closes; otherwise it costs
  nothing.  Useful for setting tuner gain: raise gain while the SNR improves, back off when it stops improving or the
  noise floor rises with it.
- Because it comes from an unmerged branch, upstream may still change it before release; the fork will follow.

### Changed in the fork
- **Graphic equalizer: 20 bands instead of 10** (P25, EDACS and Type II channels), 170 Hz to 3.5 kHz about a quarter
  octave apart, for finer control.  The ten earlier frequencies are still there as every other band.  The editor now
  shows one vertical slider per band, like a hardware equalizer, with a Flat button; double-click a slider for 0 dB.
- **Existing settings carry over.**  Each earlier band keeps its gain, the new band between two earlier ones gets their
  average, and the new 170 Hz band starts flat.  The sound is close to before but not identical (narrower bands), so
  listen to equalized channels once.  The playlist still saves the 10-band settings as well, so going back to ap-10
  keeps them (approximately).

### Added in the fork: setting tuner gain
- **Overload warnings in the log.**  Every tuner's samples are checked four times a second for samples at the top of
  the tuner's range (clipping), where strong signals create false signals and raise the noise floor.  When 0.01% or
  more were at full scale over 5 minutes, or an RSP reported an overload itself, the log says so: `Tuner [name] overload
  over the last 5 minutes: 0.120% of samples at full scale, peak 0.0 dBFS - reduce the gain`.  Nothing is logged
  otherwise.  Costs a fraction of a millisecond a second per tuner.
- **Signal level meter in the tuner panel** (RTL-SDR, Airspy, HydraSDR, RSP; Tuners tab, below the gain controls):
  green OK, amber "Little headroom" (peak above -3 dBFS), red "Overload - reduce gain"; peak level, share of samples
  at full scale, noise floor, and how far the strongest signals are above it.  Updates once a second while shown.
- **Gain Check button** next to the meter: steps the gain through every setting (RTL gain list, Airspy/HydraSDR
  Linearity or Sensitivity gain, RSP LNA state), about 3 seconds each, measures each one, shows a table, puts your
  setting back and recommends one: the lowest gain without overload whose strongest signals are within 1 dB of the
  best.  Apply Recommended sets and saves it.  The tuner's channels hear the gain changes while it runs (1-2 minutes;
  an RTL-SDR has about 29 settings, an Airspy 22, an RSP about 10).  Run it while a busy channel, best a control channel, is on the tuner.  Results go to the
  log too.  Not available with AGC on (RTL) or the Custom gain mode (Airspy/HydraSDR).

### Checks
- Tests: 639, all pass, including upstream's new noise floor estimator and signal meter tests, new tests for the
  equalizer bands, the conversion of 10-band settings and reading and writing both forms in a playlist, and for
  overload detection, the gain check's measurements and recommendation, and a full gain check on a simulated tuner.

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
