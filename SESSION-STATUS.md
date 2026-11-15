# SDRTrunk AP Features - Session Status (1.0.0 series)

> `CLAUDE.md` (repo root, gitignored) is the primary session context file. This file tracks build state at a glance.
> The 0.6.2 series status (ap-15.x, ap-16.x, `edacs` builds 1-25) is in `SESSION-STATUS-0.6.2.md`.

## Current state 2026-11-15: `1.0.0-ap-1` built in the workspace, delivered as a patch set, not yet on a machine

**Branches:** `ap-1.0` = upstream `v1.0.0` (`73d32386`) + the fork, version `1.0.0-ap-1`. `edacs` = build 25
(`15d2d2a2`), frozen as the fallback; Cambridge, Summit 800, Paxton and Monson run it. `master` = ap-16.1.

**Commits on `ap-1.0` (on top of `v1.0.0`):**
1. Base: version, build settings, exception logging, Diagnostics preferences.
2. NBFM: the fork's decoder, tone filtering, audio options, editor.
3. Streaming: Zello, Code 3 Stream, upstream's Thinline Radio, upload fixes.
4. NBFM: build 25 channel filter and noise measurement kept (after the replay).
5. P25, DMR, NXDN, tuners, EDACS, Type II, playlist compatibility, screens.
6. Documents.

**Trial (Armen):** Somerville (P25, NBFM, Zello) and Cambridge (P25 trunking, EDACS, SmartZone) together. Every other
machine stays on build 25 until both are clean. Before loading: copy each machine's `default.xml` and
`default.ap.xml` (a playlist saved by `1.0.0-ap-1` that has a Code 3 Stream or Thinline Radio stream may not open in
build 25).

**To look at first on the trial machines**
- The window opens and the playlist is complete (channel, alias and stream counts as before).
- P25 Phase 1 channel editor: the NAC filter / talkgroup / ignore-unaliased row sits above the new AMBE/IMBE audio
  row (it was moved; not seen on screen).
- DMR, NXDN, P25 Phase 2 editors: one set of JMBE controls, not two.
- User Preferences: Appearance and Diagnostics (Logging) both listed under Application.
- Streaming editor: Code 3 Stream and Thinline Radio both offered as new stream types.
- Log: `SDRTrunk Version  : 1.0.0-ap-1`; no `Uncaught exception` lines.

**Open**
- Upstream's NBFM channel filter (#2556) as `1.0.0-ap-2`: work out the conversion for the three threshold settings in
  use at Somerville (0.1/0.19 default, 0.1/0.1, 0.1/0.25) from the recordings, apply on first load.
- Everett Police data bursts: P25 decoder found no P25 in a 5.5 min recording; enable the MDC1200 aux decoder on that
  channel to identify them. A temporary P25 channel on 470.8125 would show NAC 508 if Seekonk reaches Somerville.
- Build 25 delivers about a third of the tone-present seconds on Somerville Police and Everett Police (weak signals).
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
