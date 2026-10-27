# SDRTrunk AP Features - Session Status

> NOTE: CLAUDE.md (repo root, gitignored) is the primary session context file and is kept
> more current than this file. This file tracks build/release state at a glance.

## Current Release: ap-15.9.31 (2026-10-25) — state as of 2026-10-26

- Zip: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-15.9.31\sdr-trunk-windows-x86_64-v0.6.2-ap-15.9.31.zip`
- Tree `f2fd30d33454b3d832f196ebb98b280b49aee4e0` (guard on the tree hash; commit hashes differ between machines)
- **ap-15.9.32 delivered 2026-10-26 as `ap-15.9.32.patch` (tree `3bbe70d6`): fixes the silent P25 channels - see below. Not yet built.**
- **Never deploy 15.9.28** (loses upstream-type streams such as Rdio Scanner on the 2nd start) **or 15.9.29**
  (cannot read a playlist with a multi-frequency source). Use 15.9.31.

### Fleet versions (last known)
| Machine | Version | Notes |
|---|---|---|
| Salisbury (SDR-9) | 15.9.31 | from ap-15.7 on 2026-10-25 22:45 |
| Daly (DESKTOP) | 15.9.31 | "Methuen TEST" channel on 482.4625 running alongside Methuen Police |
| Beauport (SDR-13) | 15.9.31 | |
| Baker | 15.9.29 | ingest streams restored from history 2026-10-23; move to 15.9.31 |
| Somerville | 15.9.28 | move to 15.9.31 |
| Audubon | 15.9.24 | |
| Bailey VHF (SDR-3) | ap-15.5 | RTL/Nooelec only |
| Bailey UHF (SDR-4) | ap-15.7 | RTL/Nooelec only |
| Metro-25 (friend's PC, Windows user "User") | 15.9.30 installed; recommended to run the 0.6.1 final | see Metro-25 below |

Deploy recipe: back up the playlist folder first
(`$p="$env:USERPROFILE\SDRTrunk\playlist"; Copy-Item $p "$p-backup-$(Get-Date -Format yyyyMMdd-HHmm)" -Recurse`),
install, start, close, start again; log must say `Playlist read - last saved by this fork`.

### Releases this cycle (all patches delivered as `ap-<ver>.patch`, applied with `git am --keep-cr --3way`)
| Version | Tree | What |
|---|---|---|
| 15.9.24 | `ab88399b` | JMBE 2.x: skip the legacy 5x P25P1/NXDN gain under library AGC (Audubon −16.8 dBFS, 0% clipped) |
| 15.9.25 | (built inside 15.9.26) | P25P1 idle close after 2 s without audio; NBFM stuck-call watchdog timed per call; playlist never saved over an unreadable file, atomic save, `playlist\history` (hourly, newest 20) |
| 15.9.26 | `02539dd2` | One playlist for fork and upstream: `default.xml` upstream-readable, `default.ap.xml` companion with fork-only streams/alias ids/settings, merged on load |
| 15.9.27 | `99bfc0c4` | Renamed channels (decoder+frequency) and aliases (list+ids) keep fork settings; settings turned off upstream stay off |
| 15.9.28 | `f964996a` | 0.6.1 release no longer disables NBFM tone filters (tested against 0.6.1 as well as master) |
| 15.9.29 | `85e6708d` | Same-named elements made contiguous before reading (Baker lost its Rdio ingest streams on 15.9.28) |
| 15.9.30 | `d503ddbc` | `type` attribute written first again (Metro-25 multi-frequency source unreadable on 15.9.29); fall back to the file and block saving if the merge cannot be read |
| 15.9.31 | `f2fd30d3` | Measurement only: every 10 min each P25 Phase 1 channel logs `P25 Phase 1 health on [channel] ...` |

Compatibility test harness (sandbox, recreate if needed): fork and upstream save/load code compiled together;
`multiround.py` two-sided 5-round edit test (19/19 against upstream master b0428a1d and 0.6.1); `run.sh`
scenarios A–E. Baseband replay harness `RP`/`RPC` (LSM/C4FM) feeds a 50 kHz I/Q wav through the fork and the
0.6.1 decoders.

### FIX DELIVERED — conventional P25 channels silent for hours/days: ap-15.9.32 (patch `ap-15.9.32.patch`, tree `3bbe70d69e78e23132cc7a1880211feba0f98d68`, not yet built or deployed)
Armen confirmed the agencies are heard elsewhere and **nothing changed in how they transmit, frequencies or
sites — do not raise that again.** Affected: Salisbury Troop A / Seabrook / Hampton, Bailey VHF Troop B, Daly
Methuen Police, Beauport Gloucester Police / Rockport Fire.

Root cause, measured 2026-10-26 by replaying channel `Baseband I/Q` recordings through the 15.9.31 C4FM decoder
with its state logged (Salisbury Troop A, Bailey VHF Troop B x2, Daly Methuen Police and Methuen Police - Test):
1. Noise produces sync patterns whose NIDs BCH-decode. Their random NACs entered `NACTracker` (3 slots, oldest
   evicted), which within minutes of silence held three count-1 random NACs and had evicted the channel's NAC ->
   `getTrackedNAC()` = 0 -> the NAC check in `validateNID` was off.
2. A noise sync with a random NAC was then accepted; its PLL correction > PI/8 re-initialized the equalizer and was
   applied in full -> PLL at/near the +/-PI/3 (800 Hz) clamp (Troop B 09:54:00 and 11:14:15, Methuen Test 09:02:22,
   all at the noise floor).
3. The equalizer only moves on a valid sync; parked there, no real transmission validates -> dead until restart.
   Methuen Police's recording sat 510 Hz further off (+585 Hz) than Methuen Police - Test's (+75 Hz), same tuner.
   CORRECTED 2026-10-26 after the first 15.9.32 run: both mixers then settled at -611 Hz (the RSP1B's own error) and
   both recordings sat at +130 Hz, so Police's mixer had never finished converging because the channel was locked
   out; false syncs walking it away was not measured. Gating the mixer to trusted syncs stays as a precaution.
- Evidence that it is decoder state, not RF: Troop A 08:54-11:13 live 0 messages, replay 5,154; same channel after
  the 14:06 restart live 4,607 / replay 4,605. Resetting only the equalizer at 10 min revived the Methuen Test
  replay (0 -> 1,283 messages in 20 min); resetting only the NAC tracker did nothing. Right-click mute is unrelated
  (audio modules only; the lockout reproduces in the replay tool, which has no audio modules).

15.9.32 changes: `NACTracker` 8 slots, evicts least-seen then oldest, `isEstablished()`; `validateNID` accepts any
established NAC (seen 3x) and rejects others, rejects undefined DUIDs, unrolls the NAC-assist retry to flag it;
only a **trusted** NID (no NAC assist, <= 15 bit errors over sync+NID) trains the NAC tracker, moves the equalizer
or steers the mixer (threshold: 5,775 syncs in carrier needed <= 13, 99% none; 6 in noise needed 19-26). New INFO
lines: `P25 Phase 1 sync health on [channel] ...` every 10 min (trusted / withheld / rejected NAC / rejected DUID /
recalibrations, equalizer signal offset and gain, established NACs, channel mixer correction) and
`P25 Phase 1 equalizer on [channel] recalibrated by a trusted sync ...`. No recovery timer and no mixer cap, on
purpose: with noise unable to move either, the logs will show whether anything else still parks a channel.

Replay, 15.9.31 -> 15.9.32 (four 30-min REPRO files in `C:\Users\Admin\projects\logs`): Troop B 09:37 1,233 ->
2,758 messages; Troop B 11:01 809 -> 1,359; Methuen Test 135 -> 1,419; Methuen Police (+585 Hz) 135 -> 1,414.
Per-window counts match the live channels while they worked (Troop B 10:00 326/326, 11:10 891/891; Methuen Test
09:10 985/987). Voice-frame counts identical before each original stall; messages in noise 9 -> 3.

Next:
- Build 15.9.32 (tree above), deploy first to Salisbury, Daly, Bailey VHF (needs 15.5 -> 15.9.32) and Beauport.
- Success = the affected channels keep decoding past a day; sync health lines show `withheld`/`rejected` counts in
  quiet periods and `equalizer recalibrations` near 0; Methuen Police and Test show the same `channel mixer
  correction` (about -611 Hz on Daly's RSP1B).
- Daly, first 31 min on 15.9.32 (22:43-23:14 2026-10-26): Methuen Police and Methuen Police - Test decoded the
  same messages minute by minute; every carrier second in both channel recordings (63-64 s) had a decode within 1 s;
  both mixers -611 Hz, equalizer within 6 Hz; 0 recalibrations. A day-long run is still the real test.
- New observation, Daly Derry Police (busy, marginal): 46-58 recalibrations per 10 min, in pairs - a trusted TDU
  with 10-15 bit errors throws the equalizer 300-460 Hz, the next sync (0-15 errors) brings it back within a
  second. Not a lockout, and probably not new (15.9.31 did not log it). Candidate: apply a correction above PI/8
  only when the next trusted sync confirms it. Measure on a Derry Police channel recording first.
- Not yet tested: a channel that runs well off-centre (Lowell Police at -748 Hz: a fresh 15.9.31 decoder never
  acquired it, the live channel did) and weak signals near the 15-bit-error threshold. Armen to record 30 min of
  Lowell Police channel Baseband I/Q while it decodes normally.
- Seabrook, Hampton, Gloucester, Rockport were not recorded; same mechanism expected, unverified.

Replay toolkit (device VM, recreate if needed): `pip install jdk4py==25.0.2.1` (Java 25 runtime); javac from
`build\jdks\...\jdk-25.0.1-full\jmods\jdk.compiler.jmod` + `jdk.internal.opt.jmod` extracted to a directory,
`module-info.class` removed, run from the classpath; `jdk.incubator.vector` classes from its jmod on the classpath
with `--add-exports java.base/jdk.internal.vm.vector=ALL-UNNAMED` (and misc/annotation/util); compile changed
classes into an overlay directory ahead of `build\install\sdr-trunk\lib\*`; a reflection harness feeds a 50 kHz
wav through `ComplexWaveSource` -> `P25P1DecoderC4FM`. Baseband WAVs cap at 4 GiB (5.97 h at 50 kHz).

### OPEN — other items
- **Metro-25 (P25 Phase 2, LSM, Zone 3, control 769.90625):** replaying the same recordings, the 0.6.1 LSM
  decoder decodes 10–25x more messages than the fork/nightly LSM decoder (CPU ~3.5% vs ~1.2% of a core), and
  the 0.6.1 recording path gave ~6x cleaner baseband for unknown reasons (same RTL, 2.4 MSPS, polyphase). Hold
  the decoder port until a single wideband recording is replayed through both versions. Keep "max traffic
  channels" at 10: 0.6.1 refused every grant 10:14–10:27 (channels not returned), not a capacity limit. Two
  SDRTrunk instances were seen running at once on that PC.
- **Whole calls muted on a mis-decoded encryption ID** (Salisbury Rockingham Sheriff East/West: scattered IDs
  0x76, 0xB8, 0x3F, 0x0E, 0xED ...). Candidate: mute only when the same unknown ID repeats or the HDU agrees.
  Measure on a recording first.
- **Stream delete matches by name+type** (`BroadcastModel.getConfiguredBroadcast` via `equals`), not by the
  selected row; Armen saw a deleted Rockingham stream reappear. Fix: delete by identity, log stream add/edit/
  delete. (Salisbury's disk was full at the time — see below — so retest first.)
- **Salisbury disk filled 2026-10-26 02:07** by a tuner recording left running (RSP1B 10 MSPS ≈ 140 GB/h).
  Tuner recordings: 10–15 s only. Files deleted.
- **ingest.actionpagemedia.com** call-upload connection tests failing from Salisbury from 06:53 on 2026-10-26.
- **Baker Action Ambulance (461.200, 107.2):** TDMA veto cut speech on ap-15.9.x < 15.9.17 (100 Hz hum = H3);
  confirm with a 15.9.29+ log that `TDMA veto refused ... no fundamental` replaces `Closing tone gate`.
- **Beauport Rockport Fire:** no call 45 h on 10-23/24; check channel running/tuner at startup.
- JMBE 2.x per-channel settings (AGC/tone/noise gain) are preserved but not applied; UI not ported — deferred
  until a channel needs it.

### Working rules (from Armen)
- Git runs on the Windows machine only, never through the device bridge (CRLF). Deliver `git format-patch`;
  Armen applies with `git am --keep-cr --3way ap-<ver>.patch` and checks the tree hash. Stage explicit files,
  never `git add -A`. Armen's build is the official compile; Armen pushes.
- Measure before diagnosing; no fix built on an unchecked assumption. Auto-PPM is unreliable — don't propose it.
- Mute affects local speakers only. Paxton is not Armen's machine: read-only commands there.
- Don't propose PRs to DSheirer/sdrtrunk; pulling from upstream is fine.
- Playlists contain Zello credentials: read structure only, never print credential attributes.


## Earlier release: ap-15.9.16 (2026-10-06)
- Zip: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-15.9.16\sdr-trunk-windows-x86_64-v0.6.2-ap-15.9.16.zip`
- Built 2026-10-06, JDK 25 Bellsoft / Gradle 9.2
- Tree `99010c848b4dca71cade899b8b6824bb5d233702`
- **Deployed to Summit VHF/UHF only.** Measurement release — changes no behaviour.

**Logs the TDMA detector's per-window score, winning rate and harmonic ratios.** Like ap-15.9.11 this
deliberately fixes nothing. `DETECTION_THRESHOLD` stays 20.0, `CONFIRMATION_WINDOWS` stays 1,
`RELEASE_WINDOWS` stays 3, and the veto keeps closing gates exactly as before — Bolton included.

Bolton Fire 453.600 is analog NBFM whose only neighbour, PrideStar/Trinity 453.6125, is also analog. There
is no digital signal near it. `TdmaInterferenceDetector` latched **1,072 times** on it on 2026-10-05 and cut
one transmission into five files — the recordings hold 11.7 s of audio across a 14.9 s span, so roughly
3 seconds was discarded.

Two fixes were proposed and both fail against the log:

| proposal | why it fails |
|---|---|
| raise `DETECTION_THRESHOLD` | Bolton's analog comb scores reach **127.3**, p90 40.9. The class note claims real DMR peaks at 64+. No threshold separates them. |
| raise `CONFIRMATION_WINDOWS` | No latch on any channel holds under 300 ms. Bolton's minimum is 350 ms, median 450 ms. All 955 of its latches would satisfy a 3-window rule and still cut the call. |

A per-channel opt-out would work but is a standing maintenance burden with two silent failure modes, and was
arrived at by declaring the problem unsolvable after testing two knobs.

Reimplementing the algorithm in Python against the six Bolton recordings gave a maximum score of **11.1**,
and 1.1–4.7 on the five that were cut. That settles nothing: the detector taps at `NBFMDecoder:646`, ahead of
the gate at 653, so the recordings hold only audio that *passed* while the windows scoring 24–127 are in the
gaps the veto cut and were never written to disk. The per-window numbers cannot be recovered from the
recordings, hence the survey.

What the exercise did establish: clean analog Bolton voice reaches 11.1 against a threshold of 20, where the
calibration note puts synthetic "speech-like AM" at 13 — far thinner margin on real audio than claimed, and
the note admits its negatives were synthetic. And the winning candidate rate wanders (32.5, 33.33, 34.0,
35.0) with lopsided harmonic ratios, one window scoring H1=0.3 H2=7.2 H3=6.1. The note says requiring three
harmonics separates a real cadence from "broad modulation that merely happens to have energy near 33 Hz";
because the score is their geometric mean, one strong harmonic carries a window where the fundamental is
absent. It averages the missing harmonic away instead of rejecting it.

New log line, gated on `SURVEY_FLOOR = 5.0` or the detector already being latched:

```
TDMA survey: score=27.4 rate=33.33 H1=41.2 H2=18.9 H3=12.6 noiseFloor=3.21e-07 latched=false
```

### Open after ap-15.9.16
- **The survey decides the next move.** High-scoring windows holding `rate=33.33` with all three harmonics
  strong together means real digital energy and an RF problem. A wandering rate with one harmonic carrying
  the score means the harmonic test must require each harmonic individually instead of multiplying them.
- **Bolton is still losing audio** until that is resolved — this release changes nothing about the veto.
- **ap-15.9.15's holdover veto is inert.** All 962 holdover opens after it went in logged `targetWon=true`
  and it never closed anything. The latch reset sits in the squelch-open full-reset branch, which needs
  `elapsed >= TONE_HOLDOVER_MS` since the last confirmation; Bolton's `holdoverAge` never exceeds 2,300 ms,
  so once set the flag never clears. This is the same structural trap that made the ap-15.9.11 deadline dead
  code — state anchored to a branch that does not run during continuous interference. Fixing it properly
  needs one instrumentation line logging actual squelch transitions, which the logs barely contain.

## Superseded: ap-15.9.15 (2026-10-05)
- Zip: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-15.9.15\sdr-trunk-windows-x86_64-v0.6.2-ap-15.9.15.zip`
- Built 2026-10-05, JDK 25 Bellsoft / Gradle 9.2, `.\gradlew clean build -x test` then `.\gradlew runtimeZipWindows`
- Tree `949657b53f2cf326ac2e0a7950fec4f773a4cef8`
- **Deployed to Summit VHF/UHF only.** Under observation — the Bolton/Trinity result is not yet confirmed.

**Closes the holdover gate when no target tone has won a bin in the episode.** ap-15.9.14 fixed the tone
decision and the Summit 2026-10-05 log confirms it — the win score pins at −8 and the suppression line fires
378–812 times an hour. But the gate was never opened by the tone decision: all 977 `gate OPEN via holdover`
lines on Bolton Fire that day carried `confirmed=null`, and four of the six recordings produced were Trinity
453.6125 splashover admitted by that path. Acton Police 154.8150 shows the same pattern with 745 unconfirmed
opens.

Closing on elapsed time does not work and the log says so: real calls spend long stretches with a non-target
raw winner (median 1249 ms, max 2100 ms across 16 confirmed-real episodes), so a 600 ms grace truncates 14 of
16 real calls and 1500 ms still truncates 5. The 600 ms deadline carried since ap-15.9.11 would have cut 14 of
16 had it ever fired.

What separates the populations is whether the target ever wins a bin at all. Of 12 bleed episodes, 9 never saw
it — including all four flagged recordings — while all 16 real episodes did. So `NBFMDecoder` latches
`mRawTargetWonThisEpisode` on first sight and the veto closes the gate when the holdover is passing
unconfirmed audio and that flag was never set. Replayed against the log: **0 of 16 real episodes affected, 9
of 12 bleed episodes closed.** The three survivors each had 74.4 genuinely win a bin briefly; ambiguous gates
are left open.

> **Baseline to beat, Summit 2026-10-05 (ap-15.9.14):** 16 real Bolton episodes, 12 bleed episodes, 4 of
> which produced recordings. Keep that log — it is the before-figure for this channel pair.

### Open after ap-15.9.15
- **Bolton/Trinity unconfirmed.** Watch for `Closing tone gate — holdover with no confirmation and no target
  bin win this episode` on Trinity keyups, and `targetWon=true` on real Bolton calls. Real calls getting
  clipped means the latch is resetting somewhere unaccounted for.
- **DCS path is reasoned, not measured.** The first draft of the veto would have cut every DCS call on each
  code dropout; a DCS confirmation now latches the flag and the veto is guarded on `mCTCSSDetector != null`.
  Needs confirming on a real DCS channel.
- **Somerville 131.8 and the data bursts are unmeasured.** ap-15.9.14 should help 127.3/131.8 partially — the
  pair is ~2.1 dB apart against the old 2.0 dB override, so it fired intermittently there rather than
  unconditionally as on Bolton's 74.4/77.0 at 0.7 dB. Whether the ap-15.9.15 veto helps the bursts is unknown:
  broadband energy may let the target win a bin by chance and latch the flag. `TdmaInterferenceDetector`
  remains the designed defence. A Somerville log covering bursts would settle both.
- **`tools/fleetcheck.py` parses none of the log lines added since ap-15.9.7** and its CTCSS section measures
  the wrong thing. Fix or remove.

## Fleet versions as reported
| Machine | Version | Reported |
|---|---|---|
| Summit VHF/UHF | ap-15.9.16 | 2026-10-06 |
| Stoneham | ap-15.9.9 | 2026-10-03 |
| Somerville | ap-15.9.8 | 2026-09-30 |
| Daly | ap-15.9.8 | 2026-09-30 |
| Baker | ap-15.9.4 | 2026-09-17 |
| Beauport | ap-15.9.1 | 2026-09-17 |
| Monson | ap-15.7 or earlier | — |

Anything not listed has not been reported this cycle. Do not assume a machine took a build it was not
confirmed to have taken.

## Releases ap-15.9.5 → ap-15.9.14 (see CHANGELOG.md for full detail)
- **ap-15.9.14** — gates the adjacent-tone leakage preference on a per-target win score. The preference
  overrode the Goertzel winner whenever a target sat within 2 dB, and 2 dB is wider than the separation
  between any adjacent CTCSS pair, so for adjacent tones it fired unconditionally. Root cause of the Bolton
  bleed and of the long-standing 127.3/131.8 limitation.
- **ap-15.9.13** — CTCSS confirmation becomes a sliding window (3 of the last 5 blocks) instead of a
  consecutive run. Cut `WOULD CLOSE` from 6.0/h to ~1.1/h and holdover gate-opens from 410/h to ~190/h. Real
  improvement, not the fix.
- **ap-15.9.12** — reverts the ap-15.9.10 `AnalogDecoderState` change; the "To" column no longer holds the
  configured talkgroup between transmissions. The file is byte-identical to its ap-15.9.9 state. It was
  bundled with the mute fix without depending on it, which was a mistake.
- **ap-15.9.11** — arms the dead holdover backstop and logs what it *would* have done, closing nothing.
  Measurement by design. This is what identified the ap-15.9.15 defect.
- **ap-15.9.10** — corrects two ap-15.9.9 mistakes (`START_STREAM_RETRY_MS` 750 → 3000 against measured
  latency; already-closed-stream errors dispatched on the error string, since the server sends them
  unsolicited with `seq=-1`), and fixes Now Playing mute on channels whose alias list holds more than one
  alias.
- **ap-15.9.9** — acts on Zello Support's second round of answers and the ap-15.9.8 field result. Four small
  Zello client changes.
- **ap-15.9.8** — acts on Zello Support's answers of 2026-09-30. Removes a 500 ms wait on an acknowledgement
  that is never sent. **Confirmed by measurement:** 92,774 stops across three machines produced one
  channel-busy event, against 72.0 per thousand on Stoneham's last ap-15.9.7 day; ghosts fell from 1,687/day
  to 6 in two days. Stoneham was an accidental control — files copied but never restarted.
- **ap-15.9.7** — ships the squelch-close fix lost from ap-15.9.6, one upstream P25 filter correction, two
  diagnostics.
- **ap-15.9.6** — four changes, each measured first: two silent failures, one filter attenuating the wrong
  part of the spectrum, one heap ceiling that was never protection.
- **ap-15.9.5** — two silent-failure fixes plus one new warning. The Zello wedge ran four days across two
  machines with no log line at any level.

## Superseded: ap-15.9.4 (2026-09-17)
- GitHub release: https://github.com/actionpagezello/sdrtrunk/releases/tag/v0.6.2-ap-15.9.4
- Zip: `C:\Users\Admin\projects\sdrtrunk-ap-versions\v0.6.2-ap-15.9.4\sdr-trunk-windows-x86_64-v0.6.2-ap-15.9.4.zip`
- Built and pushed 2026-09-17 with JDK 25 Bellsoft / Gradle 9.2, `.\gradlew clean runtimeZipWindows`
- Release notes cover the whole ap-15.9.1 → ap-15.9.4 arc; ap-15.9.1/.2/.3 were same-day iterations and are
  superseded. ap-15.9.1's RSP1B fix and ap-15.9.4's tone notch are both confirmed by measurement, not inference.
- Deployed to: **Daly** (`DESKTOP`) and **Baker** (`DESKTOP-3NNNO6F`), both 2026-09-17. Beauport is on
  ap-15.9.1 and has not taken .4 yet. Somerville/Stoneham/Monson still on ap-15.7 or earlier.
- Now Playing mute confirmed working on Daly and Baker after this build. The CTCSS tone notch is validated
  against a real recording but has not yet been confirmed by ear in production, and the DCS path is
  unmeasured — see "Open" below.

**Notches the CTCSS/DCS squelch tone out of NBFM audio.** The reported "60 Hz hum" was measured and is
neither hum nor 60 Hz — it is the channel's own CTCSS tone. In a District 5/15 recording (CTCSS 131.8) the
tone sat at −49 dBFS against voice at −19, while 60 Hz was at −85 dBFS and every mains frequency was 50–75 dB
down. The tone reads as hum in the gaps because its level is constant: the 100–200 Hz band drops 0.6 dB when
talking stops while every other band drops 20–32 dB.

One biquad notch per configured tone, Q=12, taken from the channel's own `ChannelToneFilter`. Verified on the
real recording: tone in pauses −48.4 → **−82.1 dBFS (−33.7 dB)**, voice unchanged to 0.0 dB at 300/500/800/
1500/2500 Hz. Safe for tone squelch — the detectors tap ahead of the audio filters. DCS gets a wider notch at
134.4 Hz, labelled partial in the log and unmeasured.

> **The USB hub and SDR were not the cause.** A 60 Hz notch, the original request, would have done nothing.

### Open after ap-15.9.4
- **DCS notch unmeasured.** The CTCSS notch is validated; the DCS path (134.4 Hz, Q=4) is reasoned only.
  Needs a clip from Lynn Fire FG 3 (DCS-125) or Boxford Police (DCS-411).
- **Stuck-call watchdog trips**, all on tone-gated channels: Danvers Fire (483.3375) and Salem Fire Ch 2
  (483.6375) on Baker; Essex Police (483.3000) and Manchester BTS Fire (483.7000) on Beauport. The tone
  detector is the suspect rather than the squelch. Count them per machine with:
  `Select-String -Path "$env:USERPROFILE\SDRTrunk\logs\*.log" -Pattern "Stuck timer watchdog"`
- **Baker's 7 muted aliases** — worth reviewing; Salem at 2 of 3 is the only pattern the old whole-list mute
  bug could plausibly have produced.

## Released: ap-15.9.3 (built and pushed 2026-09-17)
**Makes Now Playing mute work on a channel with no alias** — the case it was actually asked for, and the one
ap-15.9.2 could not serve because it made the alias the only store.

Two stores, one authority rule: an identifiable alias governs → that alias's Listen state (aliases are keyed by
talkgroup, so a trunked channel carrying a call resolves to exactly one); no alias identifiable → a per-channel
entry in `NowPlayingPreference`, applied at the audio modules and re-applied when the chain rebuilds. Exactly one
is ever written for a given channel, so they cannot disagree. Reads take the union so the menu label always
matches what is audible, and unmute clears both stores. Menu names its target: `Mute alias: X` vs
`Mute channel: Y`.

> **Mute is local speakers only — Zello and ThinLine keep streaming.** By design, unchanged in every version.

## Released: ap-15.9.2 (built and pushed 2026-09-17)
**Reworks Now Playing mute into a shortcut for the alias editor's Listen toggle.** ap-15.9.1 stopped the data
corruption by decoupling mute from alias priority and storing it separately — which removed the linkage the
feature exists for, so the right-click menu and the Listen switch no longer moved together. Mute now reads and
writes only the governing alias's playback priority; there is no second copy of the state, and no re-apply logic,
because `AudioSegment` resolves priority from its aliases on every segment.

The governing alias is resolved most-specific-first: live TO alias (exactly one), live FROM alias (exactly one —
this catches CTCSS-keyed conventional channels precisely, since `CTCSSIdentifier` is `Role.FROM` and CTCSS maps
1:1 to an alias), then the sole alias in the channel's alias list. Ambiguous cases show a **disabled** menu item
naming the reason and the alias count instead of guessing.

> **Mute stops local speaker audio only — it does NOT stop Zello or ThinLine streaming.** `AudioStreamingManager`
> never consults the do-not-monitor flag. True in every version; now documented. Taking a channel off a feed means
> removing the broadcast channel from the alias, and there is no shortcut for that yet.

## Released: ap-15.9.1 (built and pushed 2026-09-17) — RSP1B FIX CONFIRMED IN THE FIELD
> **Confirmed on Daly, same log, same day.** 15.9 ran to 13:40, 15.9.1 from 13:43.
> Overflow warnings 8,930 → **0**. Cumulative buffers discarded **402,708,445 → 0**. Startup line reads
> `Rsp1bTunerController at [10.00000] MSPS - [78125] buffers/second ([128] samples each) - queue bounded at
> [156250] elements (2.00 seconds)`. Every NH feed the RSP1B serves came back: Salem NH Police 0 → 255 stream
> starts, Salem NH Fire 0 → 61, Derry 0 → 299/46, Londonderry 0 → 281/212, Windham 0 → 122/109, Hudson 0 → 79,
> Lawrence MA 0 → 462/198. MA feeds on the RTLs were unaffected throughout (the control). No OOM, no tuner
> removals. Baker's RSP1B at 8 MSPS resolves to 125,000 elements, also correct.

**Fixes the RSP1B starvation that ap-15.9 claimed to fix and didn't.** `PolyphaseChannelManager` is constructed
before the tuner applies a sample rate, so `getSampleRate()` returns 0 and the queue bound — derived once, in the
constructor — collapsed to its 32-element floor for every tuner in BOTH ap-15.8.1 and ap-15.9. At an RSP1B's
78,125 buffers/second that is 0.41 ms of queue: Daly discarded **262,638,170 buffers on 2026-09-16 alone**, all of
them the RSP1B. The bound is now re-derived when `NOTIFICATION_SAMPLE_RATE_CHANGE` arrives, with a generous
provisional bound (160,000) until then, and a WARN if any dispatcher ends up pinned to its minimum.

Also carried the first **Now Playing mute fix**. Mute was writing `Priority.DO_NOT_MONITOR` into every alias in
the channel's alias list, so muting one channel muted every channel sharing that list and unmuting wiped the
user's configured priorities. 15.9.1 stopped that by decoupling mute from aliases entirely and keying it on the
channel's system/site/name in `NowPlayingPreference`. **Superseded by ap-15.9.2**, which keeps the fix but
restores the alias linkage — decoupling removed the behaviour the feature exists for.

> **Neither ap-15.8.1 nor ap-15.9 should run with an RSP1B (or any tuner above ~1 kHz buffer rate).** Use 15.9.1,
> or fall back to 15.7. RTL-2832-only machines are unaffected.

Also carries a **mains hum diagnostic** for the reported 60 Hz hum on analog channels — `HumAnalyzer`, Goertzel at
50/60/100/120/180/240 Hz, reporting per transmission which mains family the energy is in, whether the fundamental
or the second harmonic dominates (supply ripple versus coupled field), and whether the hum level is constant or
rises during speech pauses (additive hum versus a gain stage lifting the noise floor). It measures only; nothing is
filtered. Off unless enabled in Diagnostics → "Mains hum analysis (audio)". Each NBFM channel now also logs its
"Audio Filter" (200/300 Hz high-pass) setting at startup, at WARN when disabled.

> **To collect hum data:** tick "Mains hum analysis (audio)" in the Diagnostics preferences panel, let the affected
> channels run through a few transmissions, then send the application log. Turn it back off afterwards — it writes
> three DEBUG lines per transmission per channel.

> **Alias priorities may need checking after upgrading from ap-15.9 or earlier.** If mute was ever used on a
> channel, the old code may have left `DO_NOT_MONITOR` on aliases across a whole alias list. Neither 15.9.1 nor
> 15.9.2 repairs existing playlists — check the Listen toggles in the Aliases tab for any alias list where a
> channel was muted. Audit with:
>
> ```powershell
> $x = [xml](Get-Content "$env:USERPROFILE\SDRTrunk\playlist\default.xml")
> $x.playlist.alias | Group-Object list | ForEach-Object {
>   $m = @($_.Group | Where-Object { $_.id | Where-Object { $_.type -eq 'priority' -and $_.priority -eq '-1' } }).Count
>   [pscustomobject]@{ AliasList=$_.Name; Total=$_.Count; Muted=$m; Pct=[math]::Round(100*$m/$_.Count,1) }
> } | Sort-Object AliasList | Format-Table -AutoSize
> ```
>
> A list at or near 100% muted is the damage signature. Deliberate mutes of encrypted or tactical talkgroups look
> like a scattered subset, not a whole list.

## Superseded: ap-15.9 (built 2026-09-13, released)
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
- Version property: `gradle.properties` -> `projectVersion=0.6.2-ap-15.9.16`
- Patch workflow: work is delivered as `git format-patch` and applied on Windows with
  `git am --keep-cr --3way ap-<version>.patch`, then the tree hash is checked against the one recorded for
  that release. Git is never run against this repo through the device bridge — CRLF causes mass false-positive
  staging. The cloud sandbox cannot push (proxy 403), so every push happens from Windows.
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

## Carried open items (not release-specific)
- **Alias deletion sometimes requires a program restart** — deleted aliases do not always disappear from the
  list. Upstream `AliasConfigurationEditor.java`; the suspect is the mutated-in-place `AliasPredicate` in a
  `FilteredList`. Reported on ap-15.7 on a test system, selecting the alias from the dropdown rather than
  searching for it. Unconfirmed whether a filter or dropdown selection is required to reproduce.
- **Salisbury P25 talkgroup not displaying on ap-15.7** — not the analog path, and predates every fork panel
  change. Unexplained.
- **Daly RSP1B removal verification** — deferred by decision after three clean days following the 10-01
  33-callback burst.
- **Zello needs a correction** — the ghost figure reported to them was inflated; most of the silent-start
  count was an artefact of our own 15 ms measurement.
- **Missing stop acks concentrate on specific channels**, worst on Massport (consumer network). Not chased.
- **`tools/fleetcheck.py` parses none of the log lines added since ap-15.9.7** and its CTCSS section
  measures the wrong thing. Every analysis this week was hand-rolled Python against raw logs; that script
  should be doing it. Fix or remove.
- **`TdmaInterferenceDetector`'s calibration is not trustworthy as written.** The class note's negatives
  were synthetic — "speech-like AM peak 13" — and real analog voice measures 11.1 on a clean clip while
  Bolton's channel reaches 127.3. Any future change here should be set from measured per-window scores
  (the ap-15.9.16 survey), not from that note's figures. The three-harmonic requirement does not do what
  the note claims, because the score is a geometric mean.

## HydraSDR RF One
Supported in the fork already, pulled from upstream: `source/tuner/hydrasdr/`, VID `38af` PID `0001`,
`TunerType.HYDRASDR_R828D`. Usable bandwidth is enforced per sample rate in `HydraSdrTunerController`
(lines 289–305) and the same table applies to Airspy, which shares the controller shape:

| Rate | Usable % | Usable BW |
|---|---|---|
| 10 MSPS | 90% | 9.00 MHz |
| 6 MSPS | 83% | 4.98 MHz |
| 3 MSPS | 66% | 1.98 MHz |
| 2.5 MSPS | 60% | 1.50 MHz |

RTL R820T is 98% (`R8xEmbeddedTuner.USABLE_BANDWIDTH_PERCENT`), max listed rate 2.880 MHz.

**Leave sample packing off.** `setSamplePacking(false)` at init is correct: packing forces
`ScalarPackedSampleConverter` and there is no vectorized packed converter, so it trades the SIMD path for 25%
of USB bandwidth. Only reach for it if a 10 MSPS device actually starves — USB starvation logs
`USB transfer [...] submit attempt failed` and, at the limit, `Maximum USB transfer buffer errors reached`,
whereas scalar conversion costs CPU and logs nothing.

HydraSDR maintains its own sdrtrunk fork (`hydrasdr/sdrtrunk`) replacing the pure-Java USB path with a JNI
bridge to `libhydrasdr`, claiming ~25× lower streaming CPU. Unverified vendor claim; adopting it means a
native DLL in the build and runtime zip on every machine. Not done.

## Denmark, ME build plan (not yet installed)
Dell OptiPlex 9020, i7-4790, 32 GB. Antenna → 20 ft Belden 400 → ZX60-P103LN+ (+25 dB at VHF, NF 0.4–0.6 dB,
own 5 V supply — Bias-T is useless in this topology) → FM broadcast filter + SLP-550+ → Anzac DS-4 four-way.
Filters go **after** the LNA: their loss would land directly on a 1.1 dB system noise figure ahead of it, and
the ZX60's input P1dB near +0.9 dBm means it will not compress on anything that site presents. `LINEARITY`
mode, low steps, both AGCs off, no attenuator — keep a 3 and 6 dB pad on the shelf for a single leg only.

Coverage of the 100-frequency list, exhaustive over window placements at 9.0 MHz: 1 device 73%, 2 devices 84%,
3 devices 91%, 4 devices 98%.

| Device | Rate | Center | Ch |
|---|---|---|---|
| HydraSDR #1 | 10 MSPS | 154.9625 | 73 |
| HydraSDR #2 | 10 MSPS | 171.2562 | 11 |
| Airspy Mini | 3 MSPS | 160.2450 | 6 |
| Nooelec | 2.4 MSPS | 461.3375 | 5 |
| Nooelec | 2.4 MSPS | 453.5375 | 3 |

Five devices, 98 of 100. Skip a device for 163.5750 — Augusta RCC South is already covered at 169.6625.
Start with the two HydraSDRs (84%) and add the rest only where a survey shows traffic. The RF One is USB 2.0
and moves ~40 MB/s at 10 MSPS against a ~40 MB/s per-controller ceiling, so each 10 MSPS device wants its own
host controller; a StarTech PEXUSB3S44V (four independent Renesas µPD720202 controllers, PCIe x4, aux power
connected) solves that if a third or fourth device is added. It does nothing for CPU, which is the remaining
unknown.

## Queued
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
