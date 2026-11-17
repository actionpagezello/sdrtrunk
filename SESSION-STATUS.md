# SDRTrunk AP Features - Session Status (1.0.0 series)

> `CLAUDE.md` (repo root, gitignored) is the primary session context file. This file tracks build state at a glance.
> The 0.6.2 series status (ap-15.x, ap-16.x, `edacs` builds 1-25) is in `SESSION-STATUS-0.6.2.md`.

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
