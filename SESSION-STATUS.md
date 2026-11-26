# SDRTrunk AP Features - Session Status (1.0.0 series)

> `CLAUDE.md` (repo root, gitignored) is the primary session context file. This file tracks build state at a glance.
> The 0.6.2 series status (ap-15.x, ap-16.x, `edacs` builds 1-25) is in `SESSION-STATUS-0.6.2.md`.

## 2026-11-26 11:30 - `1.0.0-ap-23` delivered as `ap-1.0.0-ap-23.patch` (not yet applied when written)

- Eleven commits on ap-22 (`5e05e840` on the fork, tree `f12640b3`): upstream #2761, #2801, #2803, #2807, #2808,
  #2815, #2810, #2818, #2770 (`133f7c73..86de0ff2`, all of upstream master on 2026-11-26); the streaming status
  fix; version and docs.  842 tests.
- **#2770 merge.**  Upstream's `ShowInWaterfallAction`, `TunerEvent` centre frequency and `MainSpectralDisplay.zoomTo`
  replace the fork's copies (those files now equal upstream's).  `ChannelAudioActions` keeps only Mute/Unmute and no
  longer takes a `TunerManager`; `ChannelMetadataView` still takes the `PlaylistManager` (Mute needs the alias
  model), which is the one line `NowPlayingView` differs from upstream by.
- **Streaming status fix is not reproduced by a test**: `BroadcastModel` starts a scheduled monitor and touches the
  recordings folder in its constructor.  The fix catches the exception at the list copy and schedules the update
  again.
- **ap-22 overnight 11-25 19:30 to 11-26 09:30** (CTCSS / DCS box on): calls ended as TDMA - Daly 0 (ap-21: 7),
  Denmark 0 (10), Somerville 1 (16); one-window triggers left alone 6 / 11 / 18; no empty calls.
- **The one Somerville cut was not DMR.**  Brookline Fire 483.4375, 23:14:24, slice kept as
  `logs\reference\somerville_BrooklineFire_2314_slice.wav` (cut about 120 s in).  One narrow FM carrier, 4-15 dB
  above the noise, amplitude swinging 10-15 dB at a steady 32.5 Hz for about 1.5 s; no wide digital signal on or
  beside the channel.  The noise burst in each dip gave the detector two windows (28.6, 29.5).  Lost: about 0.5 s
  at the end of a 2.6 s call and about 1 s at the start of the next.  Cause of the flutter not established.  No
  detector change on one case; if it repeats, require DMR's hard slot edges, with this slice as the test.
- **Open:** Denmark RTL 97350833 (Cornish Control 172.5) and 43819678 report overload - Gain Check each.  Daly
  RSP1B gaps every 15 min unidentified.  "CTCSS holdover" measurement lines are at WARN with the Diagnostics boxes
  off (Somerville, 56 on 11-25).  Cambridge is on ap-21.  PR #2771 (Mute) declined as written; redesign and reply
  to Dennis under discussion.

## 2026-11-25 15:30 - `1.0.0-ap-22` delivered as `ap-1.0.0-ap-22.patch` (not yet applied when written)

- Nine commits on `4168ebf3` (ap-21 + the 11-24 docs commit): upstream #2702, #2791, #2760, #2793, #2796, #2798
  (`71116f8f..133f7c73`, all of upstream master on 2026-11-25); the TDMA open-call fix; the two Gain Check fixes;
  version and docs.  831 tests.
- **TDMA cuts, what was measured.** ap-21 logs 11-24: calls ended "DMR/TDMA interference" after 1 s or more -
  Somerville 22, Daly 17, Denmark 4.  Basebands 11-25 (Denmark Oxford County Fire cut 06:27:13, Daly Lawrence Fire
  cut 07:21:31): clean speech, 64 dB and 36 dB above the noise.  Detector alone over the speech in both, every
  alignment: 10 one-window latches in 16,380 s, none with a second window.  Somerville log 11-25 10:21-14:33 with
  the CTCSS / DCS box on: 9 calls cut, the window after each scored 1-6; 118 triggers on Winthrop EMA/Harbormaster
  453.8625, all with the gate closed.  Whole decoder on the Denmark baseband, 400 alignments: ap-21 8 cuts,
  ap-22 none.
- **Not tested:** real DMR on a call that is already open (no recording of one).  Synthetic 50% duty burst over a
  CTCSS call: 1 s and 2 s bursts end the call 0.8-1.0 s into the burst (ap-21: 0.5-0.9 s); 0.2-0.6 s bursts no
  longer end it.  "Two windows in a row" was tried first and took up to 1.8 s, so the rule is a second window
  within the latch.
- **Gate-closed single windows are unchanged**: a one-window latch at the start of a transmission still holds the
  gate for about 0.45 s and that audio is dropped.  Not measured how often; look at it if call starts are clipped.
- The Diagnostics box for the detector's lines is **CTCSS / DCS squelch codes** ("NBFM / audio output" is the
  audio path only).
- Workspace tools (not in the repo): `/home/claude/replay/Replay.java` + `sweep.sh` feed a 50 kHz I/Q baseband
  WAV through `NBFMDecoder` with a tone filter and count call ends by reason; start the replay inside the
  transmission and step the start by 20 samples to vary the detector's window alignment.  `cut-baseband.ps1` in
  the logs folder cuts a few minutes out of a multi-GB baseband recording (staging limit 400 MB a file).
- Seen on a virtual display: the program starts with upstream's toolbar and status bar, no exception.  Not seen:
  any fork screen with a tuner or channel running.

## 2026-11-24 12:50 - HANDOFF: `1.0.0-ap-21` pushed and running fleet-wide; start the next session here

**State of play**
- `ap-1.0` on GitHub = `81db2214`, tree `d28a751b5843cd772aa082b2c8009b44bd74facc`, version `1.0.0-ap-21`, 809 tests.
  Running on Cambridge, Somerville, Denmark, Daly, Summit 800 (installed 2026-11-24 ~09:30). Builds ap-11..ap-21 are
  summarised in `CHANGELOG.md` and `SESSION-STATUS.md`.
- Upstream (`DSheirer/sdrtrunk` master) fully taken through `71116f8f` (2026-11-24 05:51). Not taken on purpose:
  #2741 (JMBE 3 API check). #2701 (channel SNR) was already in the fork, identical.
- Patch workflow unchanged: `git format-patch --binary` -> `C:\Users\Admin\projects\sdrtrunk-ap\logs\` -> he runs
  `git am --keep-cr --3way`, checks `git rev-parse "HEAD^{tree}"`, `.\gradlew test`, `.\gradlew runtimeZipWindows`,
  `git push origin ap-1.0`. git only on his PC. Workspace tests: xvfb-run + JDK 27.
- Logs folder `C:\Users\Admin\projects\logs` cleared 2026-11-24 (kept `reference\` and two ActionPage Media
  screenshots); `sdrtrunk-ap\logs\` emptied.

**Waiting on him (per machine, after ap-21 has run a while)**
- Logs from all five. Check: calibration dialog (ap-21 takes upstream #2782; first start may ask to calibrate),
  settings carried over, Gain Check results.
- Denmark: HydraSDR RF One 36B463DC-35933AC7 was clipping at Linearity 12 (ap-19 Gain Check bug, fixed in ap-20) -
  advised 9, then Gain Check; RTL 24711130 limits back from 800-900 MHz; West Paris Fire 154.980 tone filter on at
  CTCSS 156.7 (only the notch was on; Falmouth ME Fire 107.2 is the strong co-channel station); CTCSS/DCS auxiliary
  decoders being tried for "who is on this channel" (untested with the fork's NBFM audio path).
- Daly: RSP1B 24050F5260 at LNA 1 since 2026-11-23 19:45 (Gain Check), VHF healthy.
- Summit 800: Airspy kept at Linearity 8 by Gain Check (Metro Boston Control 47 dB above noise).
- Cambridge: version unknown in the 11-23/24 logs (no restart) - should now be ap-21.

**Upstream PRs and issues (his, on DSheirer/sdrtrunk)**
- #2706 EDACS, #2707 Type II: Dennis asked about overlap with #2648/#2650; his replies posted 11-21/22; no answer yet.
- #2770 Show in Waterfall, #2771 Mute/Unmute (opened 11-23 on master 8cd8d0c2; both start with the right-click menu
  fix commit); #2769 closed in favour of issue #2772 (right-click menu never opens). CI has not run on #2770/#2771
  (needs maintainer approval); the #2769 run failed on a workflow-file problem upstream, not the code.
- #2768 issue: RTL-SDR frequency limits can only be narrowed since #2590. Fork fix in ap-20
  (RTL2832TunerEditor uses the controller's tunable range). **When Dennis fixes #2768 or #2772, take his fix and
  remove the fork's.**
- Descriptions: keep them simple - what it does and how to use it, no implementation detail.

**Open / parked**
- NBFM: if Oxford County Fire (154.07, Denmark) is cut again as "DMR/TDMA interference", get a baseband and stop the
  rapid reopen after a TDMA end (5 x 0.0 s calls 50 ms apart on 11-23 05:58 during RF One clipping; none since).
- Longer CTCSS check (idea from upstream PR #2765) only if a tone-squelched channel sounds choppy.
- Automatic gain backoff: parked - GainSteps only exist in the tuner editors.
- CTCSS/DCS auxiliary decoder summary in the ActivitySummary table layout: after his test.
- Gain Check design (ap-19..ap-20): steady-signal SNR rule (>= 10 dB, spread <= 2, neighbour within 6 dB, lowest
  within 1.5 dB of best, floor = noise rule), overload reduction sized by history peak + 3 dB, keep a setting with
  history peaks <= -10 dBFS, `estimatedGainUp` = max of estimates >= 0, no higher setting with zero headroom.
  Bridgton Police (Denmark RF One) is the weak channel that showed the old noise-floor rule went too low.

## Current state 2026-11-24: `1.0.0-ap-21` built in the workspace

**ap-21** pushed (81db2214) and installed on Cambridge, Somerville, Denmark, Daly and Summit 800 on 2026-11-24.
ap-21 = upstream through 71116f8f (#2782, #2781, #2767,
#2651, #2685, #2695, #2785, #2710; #2701 already in), FMDemodulatorModule disposed-buffer guard, TunerView channel
count race.  Open: #2768, #2772 (fork fixes until upstream), #2706/#2707/#2770/#2771 awaiting review.

## Current state 2026-11-23 morning: `1.0.0-ap-20` built in the workspace

**ap-19** (947d54b6) on Denmark and Daly.  **ap-20** = RTL2832TunerEditor min/max tunable from the controller's
hardware range (frequency limits could only be narrowed); GainSweep.estimatedGainUp (max of estimates, >= 0) and
no higher setting with zero headroom; lower settings with no estimated reduction skipped.

## Current state 2026-11-23: `1.0.0-ap-19` built in the workspace

**ap-18** (b36b9261) on Daly; Denmark still ap-17.  **ap-19** = Gain Check signal-to-noise rule (steady signals,
within 1.5 dB of best, floor at the noise setting), overload reduction sized by the history peak, keep a quiet
setting, strong-signal note; NBFM 120 ms fade-in at each transmission start (tone notch settling); BroadcastStatusView
CME at start-up.  Deferred: automatic gain backoff (needs gain control outside the tuner editor).

## Current state 2026-11-22 evening: `1.0.0-ap-18` built in the workspace

**ap-17** (220975b5) on Denmark and Daly.  **ap-18** = upstream #2753 Settings dialog (preference -> settings
rename; fork Diagnostics page ported, fork-only settings keep their old preference nodes); NBFM resume windows split
(carrier drop 0.5 s, tone/DCS loss with carrier up 1.5 s); Gain Check: -200 dBFS steps ignored, own noise from
measured steps, noise rule from the quietest step, RSP/Airspy steps estimated from measurements so more than one
step up is possible; Calls by Talkgroup in the activity summary layout.  Upstream is fully merged except #2741 (JMBE 3).

## Current state 2026-11-22: `1.0.0-ap-17` built in the workspace

**ap-16** (f634a2cf) on Denmark.  **ap-17** = NBFM: TDMA veto gated on filtered-envelope modulation depth (paging
tones split calls), 1.5 s resume window incl. tone loss with held audio, call end reasons at INFO; own-channel
strongest-signal note; unknown encryption ID must repeat; stream field width 420 px; upstream #2748 #2749 #2751
#2647 #2755.  Upstream #2753 Settings redesign deferred.

## Current state 2026-11-21 evening: `1.0.0-ap-16` built in the workspace

**ap-15** (c9213b5b) running on Denmark (RFOnes as HydraSDR).  **ap-16** = upstream #2725 full JavaFX app plus
#2732/#2735/#2736/#2737/#2739/#2743 (not #2741 JMBE 3), Gain Check fixes (real control channels only, noise floor
setting is the minimum, mild overload two steps at most, Not receiving, Put Back), overload warning names the
strongest signal, channels-per-tuner log, hotplug and Tuners tab fixes.  Upstream PRs: Dennis asked on #2706/#2707
whether they can be synchronized with #2648/#2650; replies posted offering to close and contribute the differences.

## Current state 2026-11-21 10:00: `1.0.0-ap-15` built in the workspace

**ap-14** (d3c57c59) running on Cambridge, Daly, Somerville, Summit 800 and now Denmark.  Denmark's two HydraSDR
RFOne tuners use the Airspy USB ids and failed to start under 1.0.0 (libairspy NOT_FOUND).  **ap-15** = discovery
starts those as HydraSDR tuners.  Gain Check fixes from the 11-20 day review (Daly RSP1B sent to LNA 9 using a
conventional P25 channel as reference) are held for the next build.

## Current state 2026-11-20 07:00: `1.0.0-ap-14` built in the workspace

**ap-13** (73627f51) pushed.  **ap-14** = upstream #2712, #2714, #2716, #2719, #2722 and #2720 (JavaFX Tuners tab)
+ the fork's Signal Level section and Gain Check window rebuilt in JavaFX on the new editors (RTL, Airspy, HydraSDR,
RSP).  RSP recovery merged unchanged.  Next: install on Cambridge, Summit 800, Somerville; test the new Tuners tab
and Gain Check.

## Current state 2026-11-19 23:30: `1.0.0-ap-13` built in the workspace

**ap-12** (1405553a) built and pushed.  **ap-13** = Gain Check measures the tuner's control channel SNR (steady,
>=10 dB, picked before the sweep) when it has one, otherwise the noise floor rule; ap-12 history limits still apply.
Next: install ap-13 on Cambridge, Summit 800, Somerville and test Gain Check on each tuner type.

## Current state 2026-11-19 22:45: `1.0.0-ap-12` built in the workspace

**ap-11** (42871ed6) running on Cambridge, Summit 800, Somerville.  First-day findings: Summit 800 Airspy Gain Check
recommended 10 (correct); Somerville RTL 65089485 sent 32.8 -> 42.1 dB by Gain Check and then overloaded (set back);
Somerville RTLs 14095505, 70339484, 00000001, 65089485 overloaded most of the time (gain too high, pre-existing);
Cambridge RSP1B ~2,800 tuner overload events in 4.5 h.  **ap-12** = Gain Check with 30-minute history per setting and
noise-floor rule + ESK.  MBTA encryption scan: none in 4,088 transmissions.  Upstream PR #2706 has the ESK commit.

## Current state 2026-11-19 11:40: `1.0.0-ap-11` built in the workspace

**ap-10** (9ccbdb76) = upstream batch of 19 November + FrequencyController race fix + sustained-loss-only drop
warnings; cleared for the fleet.  **ap-11** = ap-10 + upstream branch `2646-channel-snr` (channel power and SNR in
the channel spectrum view, for setting gain), taken before upstream merges it, + 20-band graphic equalizer
(170 Hz - 3.5 kHz, Q 6, 10-band settings converted, both saved).  + gain tools: TunerSignalMonitor overload log (0.01% at
full scale / RSP events, 5 min), tuner panel signal meter, Gain Check sweep with recommendation.  Now Playing PR branches
(`pr-nowplaying-waterfall`, `pr-nowplaying-mute`) pushed to the fork, not opened.

## Current state 2026-11-19 07:20: `1.0.0-ap-10` built in the workspace

**ap-9 trial (to about 21:45 on 11-18):** no channelizer queue overflow on any machine; queue sizes correct
(2 s of samples per tuner, provisional limit first).  Summit 800 gain 15 -> 10 fixed the hunting (1-16 an hour).
Cambridge, Somerville clean.  Daly RSP1Bs: single 20-40 ms gaps every 15-90 minutes now reported (0.00%; hidden
before by the RSP double count).  Monson and Paxton went ap-2 -> ap-9, their first run with the sample drop monitor:
Monson RTL 00009103 and 16531039 lost 1.3-1.8% from 12:44 to about 19:40 (11 RTLs, mostly on hubs); Monson channel
queues overflow (also on ap-2 - CPU).  Paxton RISCON East Simulcast "No Tuner Available" since 13:00 on ap-2, an RTL
failed to start at 20:18 (EEPROM read).  Upstream race in FrequencyController (one error each, Paxton and Monson).

**ap-10** = upstream #2691 (Ctrl-C copy), #2697 (talker alias), #2696 (Category label), #2698 (SIMD warning), plus
fork fixes: FrequencyController race (copy-on-write processor list) and dropped-sample warnings only at 0.01% of the
stream or more (isolated RSP gaps to DEBUG).
Upstream branch `2646-channel-snr` (channel signal meter) not merged yet.

## Current state 2026-11-18 10:15: `1.0.0-ap-8` and `1.0.0-ap-9` built in the workspace

**ap-7 trial (overnight to 09:12, every Diagnostics box off from 22:30):** Cambridge sample-drop warnings 225 on ap-5
-> 0 on ap-7 (ap-6 fix confirmed); "Diagnostics on / off" logged at start-up and every box change logged, no debug
lines after unticking; overnight logs Somerville 4.4 MB (was 263 MB/day), Cambridge 5.3, Summit 800 3.9,
Audubon 0.4; no exceptions; clean closes.  Summit 800 control-channel hunting jumped to 50-150 an hour after its
22:19 restart (Nashua P25, EDACS, Type II on the one Airspy) - Armen had been adjusting the Airspy gain; Cambridge,
same build, had none.  To set the gain back (Linearity 10) and send an hour of log.  EDACS will run on Cambridge;
Nashua is the Summit 800 concern.

**ap-8** (4 upstream commits of 18 Nov + version): JavaFX map redesign (#2681), NXDN Type-D audio (#2688), talker
alias keeps user aliases (#2689), theme font scaling (#2673).  595 tests.
**ap-9** (#2672 channelizer queue sized by samples, merged with the fork's ap-15.9.1 provisional bound; 2 s backlog;
RSP sample count fix).  600 tests.  Needs a tuner trial: an RSP1B machine and Cambridge.

**Rollout:** ap-7 to the fleet was cleared (Summit 800 hunting was the gain, not the build).  ap-9 is the next trial.

**Open**
- Stoneham (older build): every county stream (Middlesex, Essex, Norfolk County) MISSING since at least 11-16 -
  aliases point to streams that don't exist; the 17XXX Middlesex talkgroups seen in Code 3 Stream match Stoneham.
  Startup report + cleanup button for dead stream names: parked by Armen.
- Stuck-call watchdog on tone-gated channels - recheck on 1.0.0 logs; DCS notch needs a DCS recording; Baker's 7
  muted aliases; optional NBFM thresholds Somerville Police 0.16/0.16, Everett 0.16/0.25.

## Current state 2026-11-17 22:40: `1.0.0-ap-7` running on four machines

**Running:** ap-7 on Somerville, Summit 800, Cambridge and Audubon from about 22:20, every Diagnostics box off (the
production setting).  Build 25 on Daly, Baker, SDR22; ap-2 on Monson and Paxton.

**Branch:** `ap-1.0` pushed at `fdb289a0` (ap-7, tree `1a157a56`) + this docs commit.  Zip:
`sdrtrunk-ap-versions\v1.0.0-ap-7\sdr-trunk-windows-x86_64-v1.0.0-ap-7.zip`.  Upstream master is `c46b80b0`, all
taken; nothing newer.

**Builds today:** ap-5 (upstream 17 Nov batch, shutdown fixes; trial clean on all four), ap-6 (RTL discarded samples
counted by the sample drop monitor: Cambridge false warnings), ap-7 (log trimmed, Diagnostics state logged at
start-up).  ap-8 (noise gate) withdrawn, never applied: every noise-reduction approach for Type II/EDACS analog
voice was rejected by ear (see CHANGELOG ap-7 "Not added"); the audio stays as is.  The next build is still ap-8.

**To check in the overnight logs**
- Cambridge: no sample-drop warnings for RTL-2832/R828D `20260003` (the ap-6 fix).
- All four: "Diagnostics on / off" after the storage directories; much shorter logs; no errors at a normal close.
- Somerville: NBFM "Tone coverage" lines only while the NBFM box is ticked.

**Rollout once ap-7 checks out:** back up `C:\Users\Admin\SDRTrunk` (playlist) on each machine first; Daly first
(RSP1B, never on 1.0.0), then Baker, SDR22, Monson, Paxton; one log from each after the first day.  Build 25 stays
the fallback for a week.

**Open**
- Stuck-call watchdog trips on tone-gated channels (Danvers Fire, Salem Fire Ch 2, Essex Police, Manchester BTS
  Fire) - recheck on 1.0.0 logs.
- DCS tone notch unmeasured - needs a DCS channel recording (Lynn Fire FG 3, Boxford Police).
- Baker's 7 muted aliases - Armen to review.
- Optional: NBFM thresholds Somerville Police 0.16/0.16, Everett Police 0.16/0.25 (Now Playing > Channel).
- Upstream branch `2672-dispatcher-time-adaptive-buffer-sizing` not merged yet; look at it when it is.

## Update 2026-11-17 09:30: `1.0.0-ap-5` built in the workspace: `1.0.0-ap-5` built in the workspace

**Running:** ap-4 on Somerville and Summit 800 (clean overnight); ap-2 on Cambridge, Monson, Paxton; build 25 on Daly,
Baker, SDR22.  Audubon runs ap-4 for DMR/NXDN tests (logs still to come; the first copy was Summit 800's).

**ap-5** = ap-4 + upstream `feeedb7c`..`c46b80b0` (17 November: waterfall Radio Reference right-click, alias list
Radio Reference tracking, talker alias auto-add, Now Playing Alias List column, README) + shutdown fixes (Now Playing
selection, multi-frequency source swap).  Delivered as `logs\ap-1.0.0-ap-5.patch` (7 commits on ap-4).

**Upstream not merged:** branch `2672-dispatcher-time-adaptive-buffer-sizing` (Dispatcher queue sized by sample
duration); the fork keeps its own Dispatcher - look at it when merged.

## Update 2026-11-16 22:10: `1.0.0-ap-4`

Right-click on Now Playing channels didn't open in ap-3 (upstream bug in the new JavaFX table: an empty ContextMenu
never shows, so its onShowing never ran).  ap-4 builds the menu from a right-click event filter on the row.

## Current state 2026-11-16 17:30: `1.0.0-ap-3` built in the workspace, ap-2 on five machines

**ap-3** = ap-2 + upstream's 15 changes after 1.0.0 (`73fc85aa`..`6dd06288`, cherry-picked in order; each commit
names its upstream hash and the fork's adjustments) + talkgroup box and codec slider widths + sample drop monitor
off for Airspy/HydraSDR + these documents.  Delivered as `logs\ap-1.0.0-ap-3.patch` (18 commits),
applied and pushed 2026-11-16 21:50 (`f91b64aa`).  Running on Somerville and Summit 800 from about 21:55.
Biggest change: upstream rebuilt Now Playing in JavaFX and deleted the Swing panels; the fork's Now Playing features
were ported to the new views (see CHANGELOG).  Tests 558 pass; P25/EDACS/Type II/ProVoice replays identical to ap-2.

**ap-2 running:** Somerville, Cambridge, Monson, Summit 800, Paxton.  Daly, Baker, SDR22 still on build 25.

**To check on screen in ap-3:** Now Playing (right-click Mute/Unmute with its tooltip, Show in Waterfall, sorting,
column chooser, Details header, event/message filters kept after restart); the new waterfall; P25 editors (talkgroup
box, sliders, Ignore Encrypted Calls).  DMR changes not replayed (no DMR recording).

## Current state 2026-11-15 19:40: `1.0.0-ap-2` running on Somerville and Cambridge

**Branches:** `ap-1.0` = upstream `v1.0.0` (`73d32386`) + the fork, version `1.0.0-ap-2`, pushed. `edacs` = build 25
(`15d2d2a2`), frozen as the fallback; Summit 800, Paxton and Monson run it. `master` = ap-16.1.

**Commits on `ap-1.0` (on top of `v1.0.0`):** base; NBFM; streaming; NBFM filter kept (after the replay); P25, DMR,
NXDN, tuners, EDACS, Type II, playlist, screens; documents; Windows package from the installed JDK; `1.0.0-ap-2`
(voice codec defaults); archive only the current version's zip; these documents. Armen applies each patch with
`git am --keep-cr --3way`, so his commit hashes differ from the workspace's - compare tree hashes.

**Build PC:** BellSoft Liberica Full JDK 27.0.1.0.3+5 (Windows MSI). Gradle 9.9.0 (wrapper). `.\gradlew test` passes on
Windows; `.\gradlew runtimeZipWindows` puts the zip in `sdrtrunk-ap-versions\v<version>`. The zip carries the
installed JDK, not upstream's pinned 27+36 (jlink refuses a different build).

**Trial so far (Somerville and Cambridge)**
- `1.0.0-ap-1` from 18:09 (Cambridge) and 18:13 (Somerville); `1.0.0-ap-2` from about 19:35 on both.
- Somerville, ap-1, 18:13-18:59, against build 25 17:27-18:11: Zello transmissions started 722 / 710; Zello
  reconnects 0 / 4; "audio data sent too fast" 0 / 0; digital-bleed vetoes 32 / 36; all 29 Zello connections logged on
  (25 Work, 4 Friends & Family); playlist 47 channels, 53 aliases, 34 streams (all 34 from the companion); 0 uncaught
  exceptions; 1 error (sound card has no master gain control - harmless, also on build 25).
- Cambridge, ap-1, 18:09-18:24: 43 Type II and 159 EDACS calls, none with "voice channel open 0.0 s"; tuner
  unavailable 0; 0 uncaught exceptions; JMBE 2.1.1 loaded.
- Not looked at on screen yet: P25 Phase 1 editor (NAC row moved above the AMBE/IMBE audio row), the JMBE controls
  on the DMR / NXDN / P25 Phase 2 editors, Appearance and Diagnostics pages, Code 3 Stream and Thinline Radio editors.

**Next:** overnight logs from both machines; then Summit 800, Paxton and Monson from build 25 to ap-2; then the rest.

**Findings this evening**
- **Boston SmartZone background (Cambridge, analog calls):** Armen heard a "slight background rumble". Measured on 9
  baseband recordings of `T-Boston-Control`: build 25 and ap-1 give identical audio; nothing below 250 Hz (-60 to -79
  dB); the background sits about 24 dB under speech and is broadband noise whose level flutters 6-12 times a second,
  plus a faint wandering component near 2,086 Hz. It is in the transmission: the same background (in FM deviation)
  is on the MSP analog calls recorded at Monson, where the carrier is 17 dB stronger. Boston talkers deviate about a
  quarter as much as Monson's, which is why it stands out there. Not filterable (it is in the voice band). A noise
  gate with head trim for Type II analog voice would silence the keyed-up pauses - parked by Armen.
- **Voice codec defaults (fixed in ap-2):** upstream's `JmbeAudioModule` sets codec features only from a channel's
  JMBE settings; digital Type II and ProVoice calls have none. Build 25 set AGC on, tone gain 1.0, comfort noise
  0.05 on every codec. Restored in ap-2. Not measurable in the workspace (no JMBE library).
- **Everett Police data in the background (Somerville):** no P25 found in a 5.5-minute recording; two tone-less
  signals 2.6 and 4.0 kHz off centre. Armen: sounds like a distant co-channel station bleeding in. Software cannot
  separate a co-channel signal under Everett's; it is an antenna matter.
- **NBFM squelch thresholds** are per channel, set on Now Playing > Channel tab ("Audio Squelch" panel), not in the
  channel editor. Somerville uses three settings: open/close 0.1/0.19, hysteresis 4/6 (19 channels - defaults);
  0.1/0.1, 5/10 (17); 0.1/0.25, 5/10 (1). The "Noise Floor" spinner on that tab only changes the spectrum display.
- **Archive step:** the versions folder received older zips too (fixed in the build script; delete stray zips from
  `v1.0.0-ap-2`).

**Open**
- Upstream's NBFM channel filter (#2556) as its own build, with the three threshold settings converted from the
  Somerville recordings (`logs\2026111516*baseband.wav`). Build 25's filter delivers about a third of the
  tone-present seconds on Somerville Police and Everett Police.
- Noise gate and head trim for Type II analog voice (parked).
- DMR has no reference recording.
- Deferred by Armen, unchanged: log trimming; minimum segment length before streaming.

**Workspace recipe (cloud session; nothing persists between chats)**
- `git clone --branch ap-1.0 https://github.com/actionpagezello/sdrtrunk.git` (and `edacs` for comparisons); upstream
  tag: `git fetch https://github.com/DSheirer/sdrtrunk.git tag v1.0.0`.
- BellSoft Liberica **Full** JDK 27 (has JavaFX): get the URL from
  `https://api.bell-sw.com/v1/liberica/releases?version-feature=27&os=linux&arch=x86&bitness=64&package-type=tar.gz&bundle-type=jdk-full&version-modifier=latest`.
  Build 25 needs the Full JDK 25 (`https://download.bell-sw.com/java/25.0.1+11/bellsoft-jdk25.0.1+11-linux-amd64-full.tar.gz`).
- `./gradlew --no-daemon -Porg.gradle.java.installations.paths=<jdk> -Porg.gradle.java.installations.auto-download=false -Porg.gradle.java.installations.auto-detect=false test`
- Compare with the old series: `git diff -w --ignore-cr-at-eol edacs ap-1.0 -- <path>` (the fork has CRLF files).
- Replay: `installDist` both builds, then a small harness on the class path that feeds a 50 kHz 16-bit I/Q wav to a
  decoder (`NBFMDecoder` with the channel's `decode_configuration` from the playlist; `P25P1DecoderC4FM`,
  `EDACSDecoder`, `ProVoiceDecoder`, `SmartNetDecoder` with their no-argument constructors) and writes every message
  or the audio to a file; compare the two builds' files. NBFM call counts need real-time pacing; squelch events and
  audio do not.
- Ground truth for NBFM without either build: FM-demodulate the recording and measure the channel's CTCSS tone in
  0.5 s windows; count the tone-present seconds that fall inside a call.
