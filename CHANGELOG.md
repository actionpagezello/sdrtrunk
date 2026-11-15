# Changelog

All notable changes to the SDRTrunk AP fork, 1.0.0 series (branch `ap-1.0`).

Versions are `<upstream version>-ap-<n>`: the upstream SDRTrunk release the build is based on, then the fork's own
counter. The 0.6.2 series (ap-15.x, ap-16.x and the `edacs` test builds 1-25) is in `CHANGELOG-0.6.2.md`.

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
