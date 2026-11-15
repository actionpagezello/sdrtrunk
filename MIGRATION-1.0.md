# Migration to SDRTrunk 1.0.0 - inventory (approved 2026-11-15) and outcome

> The outcome, and where the work departed from this inventory, is in section 9 at the end.

Written 2026-11-15. Nothing has been changed yet. This is the list to approve before any code is moved.

- **From:** `edacs` branch, build 25 (`4adda97e`, version `0.6.2-ap-16.1-edacs-25`). Frozen as the fallback.
- **Onto:** upstream SDRTrunk `v1.0.0` (`73d32386`).
- **New branch:** `ap-1.0`. Versions `1.0.0-ap-1`, `1.0.0-ap-2`, ...
- **Trial (Armen, 2026-11-15):** one build with everything, tested at the same time on Somerville (P25, NBFM,
  Zello) and Cambridge (P25 trunking, EDACS, SmartZone). Every other machine stays on build 25 until both are clean.

## 1. The size of the job (measured, not estimated)

The fork and 1.0.0 last shared code on 2026-07-11 (`b385a42b`). Since then upstream changed 991 files and the
fork changed 1,019. 831 of those are the same files - and **724 of the 831 are already identical**, because the
fork has been taking upstream's work all along (15.10, 15.11, 16.0).

What is left is 447 files that differ between build 25 and 1.0.0:

| Files | What they are | What happens |
|---|---|---|
| 153 | Upstream's work the fork never touched (36 of them are files the fork does not have) | Arrive with the base. No work. |
| 162 | Files only the fork has | Copied across, then fitted to 1.0.0 where they call into it. |
| 25 | Files the fork changed and upstream left alone since July | Changes carried straight over. |
| 107 | Files both sides changed and that still differ | Read side by side and merged by hand. This is the real work. |

The 107 are mostly in: P25 (13), alias editor (9), channel editors (8), DMR (7), tuners (about 12 across several
folders), NBFM (3), streaming (5), recording (3). Several of the largest (P25 Phase 1 state, DMR state, the P25
traffic manager) look far bigger than they are because the fork's copy has Windows line endings; the true
differences there are the fork's fixes listed in section 3.

## 2. Comes with 1.0.0 - new to you, no work

You get these simply by moving the base:

- Dark theme and the Appearance preference page; accessibility changes to the controls.
- LimeSDR tuner support.
- MotoTRBO LRRP (location) decoding additions.
- Duplicate-library check at start-up.
- Upstream's NBFM de-emphasis setting and high-pass audio filter classes.
- Upstream's own CTCSS/DCS classes (`module/decode/squelch`). They stay in the code so a stock upstream playlist
  loads, but **your CTCSS/DCS detector is the one that runs** (section 3).
- The two Golay fixes taken early in build 25 (`74dc3253`, `7fcf0353`) - I checked: both are inside 1.0.0, so
  there is nothing to carry.
- Everything already taken from upstream: channelizer, P25 Phase 1 C4FM and LSM decoders, P25 Phase 2, NXDN, the
  DMR rework, AM, per-channel JMBE audio settings, the tuner driver libraries, two-tier frequency error handling.
- Java 27 (was 25). Version number now comes from the git tag instead of `gradle.properties`.

Not taken: upstream's nightly and release GitHub workflows (you do not publish releases of the fork).

## 3. Both have it - which is better (revised 2026-11-15 after comparing the code on both sides)

The first version of this section said "keep the fork's" for everything. That was written before the two sides
were compared file by file. Compared, it splits three ways.

**Yours is better - keep the fork's**

| Feature | What was found |
|---|---|
| NBFM CTCSS/DCS tone filtering | Upstream's is a basic gate (decoder 558 lines; yours 2,057). Yours adds bleed rejection, tone notch, guard tones, confirmation rules, head/tail removal, the stuck-call watchdog. |
| NBFM audio options | Upstream has a filter switch, ALC and de-emphasis. Yours has AGC, bass boost, hiss reduction, low-pass, noise gate. |
| Upload failures (Rdio Scanner, Broadcastify Calls, OpenMHz) | Upstream still reads the server's reply when there was none. Yours handles it and logs it. |
| Baseband recording past 4 GiB | Upstream still has the file-name fault. |
| RSP recovery, tuner-removed warning, frequency error clamp | Upstream has nothing equivalent. |
| Tuner sample queue size | Upstream uses a fixed 500 buffers for every tuner. Yours sizes it from the sample rate (the RSP1B fix). Yours is proven on your machines; upstream's is not. |
| DMR colour code filter | Not in upstream. Discards bursts decoded from noise that carry the wrong colour code. |
| DMR false call starts | Not in upstream. The first voice frame is held until the next one confirms it. |
| DMR channel numbers learned over the air | Not in upstream. Your playlist entries take priority. |
| Ignore Unaliased Talkgroups for DMR | Not in upstream. |
| P25 NAC filter and talkgroup override | Not in upstream. (Missing from the first version of this list.) |
| Now Playing mute, Details lists, table fixes | Not in upstream. |
| One playlist for both programs | Not in upstream. |

**Upstream's is better - take theirs, drop the fork's copy**

| Feature | What was found |
|---|---|
| Message queue limit (`Dispatcher`) | Upstream now has its own limit with a "dropped" warning. Yours was written before theirs existed. |
| NXDN call handling | Upstream's is newer; the fork's differences are older code. |
| JMBE audio settings on the channel editors | Upstream's is newer (comfort noise volume, codec features). Your loud/clipped fix is re-checked on top. |
| Channel editor layout | Upstream's, with its accessibility labels. Your extra controls are added to it. |
| MDC-1200 summary loop | Already fixed upstream; only a comment differs now. |

**Each has something the other lacks - merge both**

| Feature | Upstream has | You have |
|---|---|---|
| NBFM noise squelch | Noise measurement corrected for narrow channels | The fix for a squelch close being swallowed |
| P25 Phase 1 calls | Encryption decided from the algorithm ID (radios that flag "encrypted" on clear voice) | Call left open, silent-channel and bad-header fixes, health line |
| DMR calls | Newer traffic channel code, ignore data calls | Colour code filter, diagnostics switch |
| RTL tuner | A stop-streaming step when no channel is using it | USB runaway recovery, range and editor fixes |

Not yet compared line by line (decided when each is ported, and reported in that patch's notes): the alias
editor files, the Radio Reference editor, `AudioChannel`/playback, and the wave recorders.

## 4. Fork only - carried across whole

- **Zello** (Work, and Friends & Family): 13 files plus the real-time streaming hooks.
- **EDACS** Extended Addressing and ProVoice: 20 files, builds 1-25.
- **Motorola Type II / SmartZone**: 12 files, including digital calls as P25 and the site number.
- **Ignore Unaliased Talkgroups** (EDACS and Type II).
- **Details tab call list** for every channel type (build 25) and the heartbeat fix behind the lost calls.
- **Silent transmissions not saved or streamed** (build 23).
- **Diagnostics preferences** and the logging categories.
- **`tools/fleetcheck.py`**.
- The fork's tests (about 40 files) - they come across with the feature they test.

## 5. New in the migration

1. **Code 3 Stream.** The fork's "ThinLine Radio" stream type is renamed "Code 3 Stream". A playlist saved by
   any earlier build that has a ThinLine Radio stream **loads as a Code 3 Stream with every setting intact** -
   nothing to re-enter on any machine. The upload format does not change.
2. **Upstream's "Thinline Radio" type** (`aad971db`, added after the 1.0.0 release) is brought in next to it, under
   upstream's own name. The two had the same internal name; Code 3 Stream takes a new one and the old one is
   translated on load. A test loads a build-25 playlist and checks the stream comes back as Code 3 Stream.
3. **C4FM symbol processor bounds guard** - the index of -1 seen once at Monson on a DMR channel. It is an
   upstream fault and is still in 1.0.0.
4. **Fresh `CHANGELOG.md`** starting at `1.0.0-ap-1`; the present one kept as `CHANGELOG-0.6.2.md`.
   `SESSION-STATUS.md` restarted the same way.

## 6. Order of work

Each line is one or more commits; the full test suite runs after each one. You receive **one patch set at the
end**, as now.

1. Base: `v1.0.0`, fork name and version, build settings, heap, exception logging, diagnostics preferences.
2. Playlist compatibility, then a check that the playlists from Cambridge, Summit 800, Paxton and Monson load.
3. NBFM: tone filtering, bleed rejection, audio options, watchdog.
4. Streaming: Zello, Code 3 Stream, upstream Thinline Radio, upload logging.
5. P25, DMR, NXDN fixes that 1.0.0 lacks, plus the C4FM guard.
6. Tuner recovery, queue bounds, frequency error.
7. EDACS, Motorola Type II, Ignore Unaliased, silent transmissions.
8. Screens: Details lists, mute, table fixes. Tools and documents.

Then: replay of the kept recordings (213 rated Cambridge calls, the Summit P25 set, the Monson set) on both
build 25 and the new build, compared call for call, before it goes near a machine.

## 7. What it costs and what could go wrong

- **Time:** the largest single change this fork has had. Several working sessions; no machine is touched until
  the end.
- **Your build PC needs Java 27** before the first build. One install; I will give the exact steps with the
  first patch.
- **1.0.0 is days old.** A fault in upstream's own new code will show up on your machines as if it were ours.
  That is the reason for the one-machine trial and for keeping build 25 ready.
- **Playlists:** once `1.0.0-ap-1` has saved a playlist, build 25 may not open it (the Code 3 Stream entry is
  unknown to build 25). For the trial machine I will give you a one-line copy of its playlist first, so going
  back is a file copy.
- **Not verified until it runs:** anything on screen (I cannot see the windows), and reception on real signals.

## 8. To approve

- The lists in sections 2 to 5.
- The order in section 6, delivered as one patch set at the end.
- Needed before the build: the Somerville and Cambridge playlist files, to check they load.

## 9. Outcome (2026-11-15, `1.0.0-ap-1`)

Approved by Armen 2026-11-15 15:36. Done the same day on branch `ap-1.0` (five commits on `v1.0.0`).

**Departures from the inventory above**

| Inventory said | What was done | Why |
|---|---|---|
| Section 2: upstream's CTCSS/DCS classes stay in the code | Removed (`module/decode/squelch`, `DeemphasisMode`) | Upstream names its tones `TONE_670`, the fork `TONE_XZ`, and playlists store the fork's names. The two cannot share the identifier and alias classes. A stock upstream playlist still loads: `DecodeConfigNBFM` reads upstream's squelch decoder list. |
| Section 3: NBFM noise squelch - merge both | The fork's channel filter and noise measurement kept; upstream's `NoiseSquelch` class kept with the fork's close fix; its narrow-channel correction is not called | Replay on Somerville recordings: with upstream's filter and correction, Somerville Police delivered 4.0 s of 24.2 s of toned traffic (build 25: 8.5 s), Everett Police 6.5 s of 33.5 s (build 25: 12.2 s). Upstream's filter without the correction delivered more (18.8 s, 31.2 s) but left the squelch open on channels closing at 0.19. A later build with a threshold conversion. |
| Section 3: message queue limit (`Dispatcher`) - upstream's | The fork's | The rate-sized tuner sample queue (RSP1B fix) changes the limit after start-up; upstream's queue has a fixed limit. |
| Section 3: RTL tuner - merge upstream's stop-streaming step | The fork's controller whole, without that step | edacs build 10 removed the step on purpose (Monson crash, second layer). |
| Section 5.2: Code 3 Stream "takes a new internal name" | Saved as `code3StreamConfiguration`; `ThinLineRadioConfiguration` still loads as Code 3 Stream; upstream's is `ThinlineRadioConfiguration` | As planned. No machine has a ThinLine stream saved (Armen), so the conversion is covered by a test only. |
| Section 6: one or more commits per line | Base, NBFM and streaming are separate commits; P25, DMR, NXDN, tuners, EDACS, Type II, playlist and screens are one | Those areas depend on each other and do not compile apart. |
| Section 3: JMBE controls - upstream's | Upstream's; the fork's `JmbeSettingsPane` deleted | So the controls do not appear twice. The fork's fixed-gain skip (`hasLibraryGainControl`) is kept on upstream's codec module. |

Files upstream deleted since July stay deleted: the ThumbDV dongle code (36 files) and two LRRP token classes.

**Checked against build 25 in the workspace (recordings from `logs` and `logs\reference`)**

| Check | Result |
|---|---|
| NBFM: Somerville Police, Everett Police, Boston EMS 1 (50 kHz baseband) | audio and call events byte-identical |
| P25 Phase 1 C4FM: five Summit 800 Central MA voice recordings | every decoded message identical (820 messages) |
| Motorola Type II control: Monson MSP, 854.9375 | 5,033 messages, identical |
| EDACS control: MBTA at Cambridge, 851.175 | 30,651 messages, identical |
| ProVoice voice: 40 MBTA recordings | 1,621 messages, identical |
| Playlists: Somerville and Cambridge (`default.xml` + `default.ap.xml`) | every channel, alias and stream setting identical after load |
| Tests | 430, 429 pass; `ZelloBroadcasterTimingTest` needs a display |

**Not checked:** anything on screen; DMR (no recording); the analog voice path of Type II and the audio out of the
JMBE codec (the message streams are identical, the audio was not compared); the Windows build and package; live
reception. The C4FM bounds guard has no test of its own.
