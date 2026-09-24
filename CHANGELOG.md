# Changelog

All notable changes in the `actionpagezello/sdrtrunk` fork are documented here. Upstream
DSheirer/sdrtrunk changes are not repeated; only the `ap-` fork deltas are recorded.

Versioning follows `0.6.2-ap-<n>` where `<n>` increments for each fork release.

## [0.6.2-ap-15.9.6] - 2026-09-24

Four changes, each measured before it was written. Two correct a silent failure, one corrects a filter that
was attenuating the wrong part of the spectrum, and one reduces a heap ceiling that was never protection.

### Fixed
- **A squelch close could be swallowed, stranding the stuck-call timer.** `NoiseSquelch` broadcast
  `SquelchState.SQUELCH` only from inside the guard around emitting a trailing audio segment.
  `mSquelchOpenIndex` is set by `findTransition()`, which indexes the delay buffer and can therefore exceed
  `squelchCloseIndex`; when it did, the internal state went squelched with no listener told. Downstream that
  meant `NBFMDecoder` never saw the close, so `mCallStartTimeMs` was never cleared, the squelch tail remover
  never closed and the tone holdover never started. A stale `mCallStartTimeMs` then makes the *next* squelch
  opening trip the 180-second watchdog immediately.

  All eight watchdog trips on Baker and Audubon on 2026-09-22 carry that signature: the channel was silent
  for the whole "call" — zero CTCSS survey lines where roughly fifty were due — with the last real activity
  160 seconds to 29 minutes earlier. `UNSQUELCH` was already broadcast unconditionally; `SQUELCH` is now
  symmetric with it.

- **DCS rumble was being notched at the one frequency where it is not present.** ap-15.9.4 notched 134.4 Hz,
  the DCS bit rate, reasoning that a 134.4 bit/second bitstream puts its energy there. That is backwards. For
  an NRZ bitstream the bit rate is the *first null* of the sinc envelope — the quietest part of the spectrum.
  DCS-125 sends a 23-bit codeword at 134.4 bit/s, so the word repeats at 134.4/23 = 5.843 Hz and the energy
  appears as a comb of 5.843 Hz harmonics concentrated well below the bit rate.

  Measured against ten Lynn Fire FG 3 (DCS-125) recordings on 2026-09-24: in the pauses, 13 of 13 measurable
  peaks between 10 and 320 Hz landed on a 5.843 Hz harmonic, the strongest being the 10th through 13th at
  58.6, 64.5, 70.3 and 76.2 Hz. The notch band (118–151 Hz at Q=4) measured −89 dBFS while 50–90 Hz measured
  −57 dBFS. The notch was removing a slice that was already 30 dB down and leaving the actual rumble intact.

  A high-pass is the right shape for spread energy — twenty harmonics cannot be notched. Filter choice was
  measured against the same recordings:

  | filter | DCS 20–300 Hz (pause) | 50–90 Hz (pause) | voice 300–3k (talk) |
  |---|---|---|---|
  | as shipped (notch only) | −51.5 | −57.2 | −16.5 |
  | + 4-pole high-pass @ 250 Hz | −56.8 | **−102.9** | −16.6 (−0.1 dB) |
  | + 4-pole high-pass @ 300 Hz | −60.2 | −109.3 | −16.7 (−0.2 dB) |

  250 Hz was chosen: 45.7 dB of rumble removal for 0.1 dB of voice-band cost, and it stays further from the
  voice band than 300 Hz. Implemented as two cascaded biquads (Butterworth section Q 0.54119610 and
  1.30656296) in double precision — at 250 Hz on an 8 kHz stream the poles sit close enough to the unit
  circle that float rounding in the feedback path shifts the corner. CTCSS channels are unaffected; this
  path runs only when a channel is configured for DCS and no CTCSS tone is set.

### Added
- **P25 Phase 1 now warns on an unknown encryption algorithm ID.** `Encryption.fromValue()` collapses every
  unrecognised 8-bit algorithm ID to `Encryption.UNKNOWN`, `isEncryptedAudio()` treats UNKNOWN as encrypted,
  and `P25P1AudioModule.processAudio()` then drops every IMBE frame for the call. Muting on an ID that cannot
  be identified is the correct default — decoding an actually-encrypted call produces noise — but the mute
  left no trace at all. A channel went silent with nothing in the log saying why, and there was no way to
  separate a genuinely encrypted talkgroup from a mis-decoded header.

  A WARN now names the raw algorithm ID, the message type it came from (HDU or LDU2) and the talkgroup, once
  per distinct ID per audio module so a fully encrypted talkgroup costs one line rather than one per call.
  The two cases read differently: a real encrypted system repeats one ID call after call on the same
  talkgroups, while a marginal signal produces scattered one-off values, and a talkgroup reporting several
  different unknown IDs is almost certainly decode error rather than encryption.

### Changed
- **Heap ceiling reduced from 10 GB to 4 GB.** Fleet steady state peaks at about 1 GB — Somerville, the
  busiest box, at 1005 MB. A 10 GB ceiling was runway, not protection: it lets a leak or a runaway queue
  grow for minutes before anything fails, and the 2026-09-05 Paxton event spent 6.5 minutes frozen filling
  it. 4 GB leaves roughly four times headroom for a busy system and still fails fast enough to be diagnosed.
  Applied to both the Windows and Linux argument lists.

### Diagnostics
- The stuck-call watchdog WARN now reports the call age alongside the time since audio last reached the
  resampler, and says `STALE CALL TIMER, not a stuck carrier` when the two disagree. A genuinely stuck
  carrier delivers audio continuously, so the idle figure stays near zero. This is what would distinguish
  the two cases if the squelch fix above ever proves incomplete.

## [0.6.2-ap-15.9.5] - 2026-09-21

Two silent-failure fixes plus one new warning. The theme is the same as ap-15.9: each of these failed with
no log line at any level, which is why the Zello wedge below ran for four days across two machines before
anyone could point at it.

### Fixed
- **Zello feed could wedge silently on an orphaned stream.** `AudioStreamingManager` stops a real-time
  stream only when the audio segment that requested it completes. When a segment completed while its
  requested start was still queued — as `mPendingStreamStart` behind a pending stop, or as a guard-delayed
  `beginStreamInternal()` — `stopRealTimeStream()` returned early because no stream was active yet, and the
  queued start then fired and opened a stream that no segment owned. Nothing ever stopped it, so
  `isRealTimeReady()` stayed false and every later call on that channel was skipped **with no log line**;
  recording and ThinLine were unaffected, so it presented as "Zello only".

  Measured on Daly across five separate events on ap-15.9.4, every one with the same signature — a final
  `Zello stream started` almost exactly 500 ms (`PENDING_STOP_TIMEOUT_MS`) after the preceding stop, on a
  channel that had been streaming 1–5 times a minute:

  | Feed | Wedged | Recovered | Dark |
  |---|---|---|---|
  | Lawrence MA Fire | 09-17 19:24:40 | 09-18 13:12 restart | ~17.8 h |
  | Test Channel 1 | 09-17 22:53:00 | 09-18 13:12 restart | ~14.3 h |
  | Andover MA Police | 09-19 17:18:21 | 09-20 02:52:57 | 9 h 34 m |
  | Lawrence MA Fire | 09-20 13:23:25 | 09-21 02:54:39 | 13 h 31 m |
  | Londonderry NH Fire | 09-19 10:48:27 | 09-19 11:40:40 | 52 m |

  **A wedge is not permanent** — an earlier note in this project claimed it was, and the 9/19–9/21 logs
  disprove it. It clears whenever the broadcaster's WebSocket next drops and reconnects, because the
  reconnect path resets `mStreamActive`. Both September 19–21 cases recovered that way, unprompted, at
  02:52 and 02:54 — Zello appears to cycle idle connections in the early morning. The Londonderry case
  cleared differently again, when the Zello server itself ended the stream after 52 minutes
  (`Zello server stopped our stream`). So the failure is bounded by luck rather than by anything in this
  code, which is the reason to fix it rather than wait for the reconnect.

  Two changes: `stopRealTimeStream()` now cancels a queued start when the stream never became active
  (DEBUG line when it does), and the broadcaster watchdog — which since ap-15.1 only checked for
  *disconnected* — now force-stops a stream that has been active with no audio for 15 s
  (`STALE_STREAM_TIMEOUT_MS`; every real call delivers buffers continuously and the relaxation hold-over is
  700 ms), logging a WARN with stream age, stream id and pending flags so the next occurrence explains
  itself. The stale check runs in pooled mode too; the disconnect check remains direct-mode only.
  Detection latency is 15 s plus up to the 60 s watchdog tick, against the 52 m to 17.8 h above.
  **Watch for `Watchdog: stream active for` in fleet logs — each one is a feed that would otherwise have
  been dark until the next reconnect.**
- **MDC-1200 activity summary looped forever and OOM'd the GUI thread.** `MDCDecoderState.getActivitySummary()`
  iterated `mEmergencyIdents` with `while(it.hasNext())` and never called `it.next()`, so once a channel with
  the MDC-1200 decoder enabled had decoded one emergency ident, selecting that channel in the channel table
  appended the ident list to a `StringBuilder` until it exceeded 2 GB and threw
  `OutOfMemoryError: Required array length 2147483644 + 5 is too large` on `AWT-EventQueue-0`. Seen twice on
  Daly 2026-09-18 13:08 via `ChannelMetadataPanel.valueChanged → ChannelDetailPanel.receive`. Decoding and
  streaming were unaffected; only the click handler unwound. The loop also printed every ident instead of the
  emergency ones. Present in upstream master — PR candidate.

### Added
- **A tuner leaving the bus is now a WARN that names it and the channels it took with it.** Device removal
  was logged only as `INFO Tuner removal detected - stopping and removing: <tuner>`, followed by a burst of
  `Stopping traffic channel` INFO lines. Daly 2026-09-21 08:05:21: the RSP1B (`SER#24050F5260`) dropped out
  carrying **13 channels**, and eleven Zello feeds — every NH system plus Lawrence MA — produced **zero**
  streams until the 10:04 restart, one hour 59 minutes later. The four RTL-served MA feeds kept running
  normally throughout, which is exactly what makes this hard to spot from the outside. Nothing anywhere in
  the log was above INFO. `DiscoveredTunerModel` now logs
  `TUNER REMOVED FROM SYSTEM [<tuner>] while carrying [n] channel(s)` at WARN, stating that it is a device
  or USB level disconnect rather than a decoder fault. The channel count is read best-effort and never
  suppresses the warning.

## [0.6.2-ap-15.9.4] - 2026-09-17

Released: https://github.com/actionpagezello/sdrtrunk/releases/tag/v0.6.2-ap-15.9.4 — deployed to Daly and
Baker on 2026-09-17. Release notes for that tag cover the whole ap-15.9.1 → ap-15.9.4 arc; ap-15.9.1/.2/.3
were same-day iterations and are superseded by this build.

Field confirmation on both machines: Now Playing mute works as intended, and ap-15.9.1's RSP1B fix is
confirmed by measurement rather than inference (see that entry). The CTCSS notch below is validated against a
real recording but has not yet been confirmed by ear in production, and the DCS path remains unmeasured.

### Added
- **CTCSS/DCS tone notch in the NBFM audio path.** Fixes the reported "60 Hz hum", which measurement
  showed is not hum and not 60 Hz: it is the channel's own CTCSS squelch tone leaking into the audio.

  Measured from a recording of District 5/15 (configured CTCSS 131.8 Hz), 9.8 seconds, 48 analysis
  blocks at 8 kHz:

  | | 50 Hz | 60 Hz | 100 Hz | 120 Hz | 180 Hz | 240 Hz | **131.8 Hz** |
  |---|---|---|---|---|---|---|---|
  | mean dBFS | −93.4 | −84.9 | −74.6 | −69.8 | −78.6 | −75.4 | **−49.1** |

  Voice sat at −19.4 dBFS, so every mains frequency was 50–75 dB down and inaudible. The USB hub and
  the SDR were not involved. A 60 Hz notch, which is what was originally asked for, would have done
  nothing at all.

  The reason it reads as hum during pauses is that the tone level does not change while everything
  else does. Measured pause-versus-speech by band:

  | band | pause | speech | change |
  |---|---|---|---|
  | 100–200 Hz (holds the tone) | −46.6 | −46.0 | **−0.6** |
  | 200–300 Hz | −66.6 | −47.1 | −19.5 |
  | 300–600 Hz | −49.2 | −17.3 | −31.9 |
  | 1–2 kHz | −47.2 | −17.7 | −29.5 |

  CTCSS is transmitted continuously for the whole transmission, so when the talking stops the tone
  becomes the loudest thing left in the audio. The `AudioModule` 200/300 Hz high-pass is enabled on
  every channel — the ap-15.9.1 startup line confirms it — but its stop band gives only about 32 dB,
  which is not enough against a strongly deviated tone.

  A notch places a zero on the tone instead. One cascaded biquad section per configured CTCSS tone,
  Q = 12 (11 Hz bandwidth), derived from the channel's own `ChannelToneFilter` so there is nothing to
  detect and nothing to guess. It engages only on channels that actually filter on a tone.

  **Verified by running the filter over the real recording**, not by trusting the design math:

  | | before | after | change |
  |---|---|---|---|
  | 131.8 Hz tone, during pauses | −48.4 | **−82.1** | **−33.7 dB** |
  | 131.8 Hz tone, all blocks | −48.2 | −73.4 | −25.1 dB |
  | voice at 300 / 500 / 800 / 1500 / 2500 Hz | — | — | **0.0 dB** |
  | voice-band RMS above 300 Hz | −19.4 | −19.4 | **0.0 dB** |

  After the notch the loudest thing in the pauses is ordinary residual noise at 395 Hz and −60 dBFS,
  so the tone is no longer the dominant artifact. The smaller 22.7 dB figure measured during speech
  is a measurement floor rather than a filter limit — speech energy leaks into the 131.8 Hz analysis
  bin, so the "after" number there reflects voice, not tone.

  Safe for tone squelch: the CTCSS and DCS detectors are fed from the resampler output ahead of the
  audio filter chain, so removing the tone from what the listener hears cannot affect detection. The
  notch runs as stage 0 of `NBFMAudioFilters`, before any gain or shelving stage can lift the tone
  back up.

  Coefficients and filter state are `double` rather than the `float` used elsewhere in that class. At
  131.8 Hz on an 8 kHz stream the poles sit very close to the unit circle, where `float` rounding in
  the feedback path is enough to shift the notch off the tone and defeat the filter.

- **DCS channels get a wider notch at the 134.4 Hz symbol rate, and the log says the reduction is
  partial.** DCS transmits a continuous 23-bit NRZ codeword rather than a steady tone, so its energy
  is spread across a band and a notch removes the middle of it rather than the whole thing. Q = 4.
  Whether this is enough on a DCS channel has not been measured — no DCS recording was available.

### Note
- The `HumAnalyzer` diagnostic from ap-15.9.1 stays in place. It did its job here by ruling mains out
  rather than confirming it, which is why the 50 Hz family is measured alongside the 60 Hz family.

## [0.6.2-ap-15.9.3] - 2026-09-17

### Fixed
- **Now Playing mute now works on a channel with no alias, which is the case it was asked for.**
  ap-15.9.2 made the alias the only store for mute state, so a channel with no alias and no talkgroup
  had nowhere to keep it and the menu greyed out. That is exactly the situation the feature was
  wanted for: a channel added with default info and monitored for quality before being made
  permanent, which needs silencing while other channels are talking.

  Mute is now backed by **two stores with one authority rule** — for any given channel exactly one is
  written, so they cannot disagree, which is the defect that broke ap-15.9 and ap-15.9.1 in opposite
  directions:

  - **An identifiable alias governs the channel** → that alias's playback priority is the state, the
    same state the alias editor's Listen toggle writes. Aliases here are keyed by talkgroup, so on a
    trunked channel carrying a call the live TO identifier resolves to exactly one alias.
  - **No alias can be identified** → a per-channel entry in `NowPlayingPreference`, applied through
    the audio modules and re-applied when the channel's processing chain is rebuilt.

  Reads take the **union** of the two. A channel can be muted while no alias is identifiable — a
  trunked channel between calls — and acquire one when the next call arrives. Reading the alias alone
  at that moment would report the channel unmuted while its audio modules were still silenced, and
  the menu would offer "Mute" on a channel the user cannot hear. Unmute clears both stores and
  releases any sticky module-level mute, so the state cannot get stuck. Simulated across four
  transition sequences, including both directions of a channel gaining and losing an identifiable
  alias; in every case the menu label matches what is actually audible.

  The menu now names what it changes: `Mute alias: Camb PD 12` versus `Mute channel: 483.7000 NBFM`.
  ap-15.9.2's greyed-out "Mute unavailable" item is gone, since there is no longer a case where mute
  cannot be applied.

  One asymmetry worth recording: unmuting while an alias resolves clears that alias's Listen
  setting, but unmuting while no alias resolves clears only the per-channel entry. An alias mute is a
  deliberate, persisted playlist setting, so a channel-level unmute does not silently discard it — it
  takes effect again when the alias next resolves. The menu stays truthful throughout because reads
  are the union.

### Changed
- `AbstractAudioModule.flushAudioSegment()`, added in ap-15.9.2, is removed. `setMuted(false)`
  already ends the segment in progress, so the alias path uses that and clears any sticky module
  mute in the same call.
- `ChannelMetadataPanel.getAliasListSize()` removed with the "Mute unavailable" item it explained.

### Note
- ap-15.9.2's changelog claimed `NowPlayingPreference`'s per-channel mute entries had been removed.
  They had not — an earlier revert restored the file, the removal never reached the commit, and the
  code shipped as unused. It is in use again now, deliberately, for the no-alias case.
- Mute remains **local speaker audio only**; Zello and ThinLine keep streaming a muted channel.
  `AudioStreamingManager` never consults monitor priority, gating only on whether the alias carries
  broadcast channels and on duplicate suppression. This is the intended behaviour, not a gap.

## [0.6.2-ap-15.9.2] - 2026-09-17

### Changed
- **Now Playing mute is now a shortcut to the alias editor's Listen toggle, which is what it was
  always meant to be.** ap-15.9.1 fixed the data corruption by decoupling channel mute from alias
  priority entirely, and stored mute state separately. That was the wrong call: it removed the
  linkage the feature exists to provide. Toggling mute from the Now Playing window left the alias
  editor's Listen switch unmoved and vice versa, so the two disagreed by construction — the same
  class of defect as before, in the opposite direction.

  Mute now reads and writes exactly one thing: the governing alias's playback priority, the same
  state the Listen toggle writes. There is no second copy of the mute state anywhere. Consequences
  worth noting:

  - `NowPlayingPreference`'s per-channel mute entries are gone, as is the channel identity built
    from system, site and name. The playlist is the only store.
  - `ChannelAddListener` no longer re-applies mute to new processing chains. It does not need to:
    `AudioSegment` resolves monitor priority from its aliases every time a segment is created, so
    new chains, traffic channels spun up for the next call, and restarts all pick the state up on
    their own. The re-apply block only existed because earlier versions kept state outside the
    playlist.
  - `AliasPriorityChangedEvent` has a publisher again, so `AliasItemEditor` updates its Listen
    switch live when mute is used from the Now Playing window.

  The original defect is still fixed, because the fix was never "stop touching aliases" — it was
  "stop touching the *wrong* aliases." The governing alias is resolved most-specific-first: the live
  TO identifier's alias when it resolves to exactly one, then the live FROM identifier's alias on the
  same condition, then the sole alias in the channel's configured alias list when that list holds
  exactly one. Anything else is ambiguous and resolves to nothing.

  The FROM rule matters more than it looks: `CTCSSIdentifier` carries `Role.FROM` and `AliasList`
  keeps a 1:1 map from CTCSS code to alias, so a conventional channel whose aliases are keyed by
  tone resolves precisely even when its alias list holds several — which is the case that previously
  caused the damage. That resolution needs a detected tone, so between transmissions such a channel
  falls through to the ambiguous case.

  When no single alias can be identified the menu shows a **disabled** item naming the reason and the
  alias count, e.g. `Mute unavailable - 3 aliases in list "Salem", use the Aliases tab`. It never
  guesses, and it never silently does something other than what the label says.

  The menu item is now labelled with the **alias** name rather than the channel's, since the alias is
  what is being changed. An alias priority change only affects segments created afterwards, so every
  running processing chain governed by that alias has its current audio segment flushed, making the
  change audible immediately rather than at the end of the call in progress.
  `AbstractAudioModule.flushAudioSegment()` was added for this — it ends the segment in progress
  without setting any sticky mute flag on the module.

### Note
- **Mute stops local speaker audio only. It does not stop Zello or ThinLine streaming.** This was
  true before these changes and is unchanged, but it was never written down. `AudioPlaybackManager`
  skips segments flagged do-not-monitor; `AudioStreamingManager` accepts every segment it is given
  and never consults that flag, gating only on whether the alias carries broadcast channels and on
  duplicate suppression. Taking a channel off a feed is a different operation — removing the
  broadcast channel from the alias — and no shortcut for it exists yet.

## [0.6.2-ap-15.9.1] - 2026-09-17

### Fixed
- **Buffer queue bound was derived before the tuner reported a sample rate, starving high-rate tuners.**
  This is the real cause of the RSP1B starvation that ap-15.9 was supposed to fix, and ap-15.9 did not fix it.

  `PolyphaseChannelSourceManager` constructs `PolyphaseChannelManager` at tuner-discovery time, before the tuner
  is started and applies a sample rate, so `TunerController.getSampleRate()` returns **zero**. Both ap-15.8.1 and
  ap-15.9 derived the queue bound once, in the constructor, from that zero — and both collapsed onto the
  32-element floor for **every tuner**, by different arithmetic:

  - ap-15.8.1: `getBufferDuration()` = `1000.0/(0/128)` = infinity, truncated to `Long.MAX_VALUE`;
    `2000/Long.MAX_VALUE` = 0; floored to 32.
  - ap-15.9: `buffersPerSecond` = `0/128` = 0; `0 × 2.0` = 0; floored to 32.

  At an RSP1B's 78,125 buffers/second a 32-element queue is **0.41 ms**. Measured on Daly under ap-15.9:
  **262,638,170 buffers discarded on 2026-09-16 alone** across 30,452 overflow warnings, all of them the RSP1B —
  the RTL-2832 tuners never overflowed once, because 32 buffers is 0.44 s at their 73/second.

  The ap-15.9 claim that 15.8.1 gave the RSP1B a 25.6 ms queue was wrong; the truncation was real but incidental,
  and the actual bound was 0.41 ms in both releases. The `getBufferDuration()` analysis pointed at the right line
  for the wrong reason.

  Fixed properly: `Dispatcher.setMaxQueueSize()` is added so the bound can be re-derived, and
  `PolyphaseChannelManager.updateBufferQueueBound()` is now called from the `NOTIFICATION_SAMPLE_RATE_CHANGE`
  handler alongside the existing `mChannelCalculator.setRates()` call. Until the rate arrives the dispatcher runs
  with a deliberately generous provisional bound of 160,000 elements rather than a tight one — an over-sized bound
  costs memory for a moment, an under-sized one discards the sample stream. Resulting bounds:

  | Tuner | Buffers/sec | Bound | Backlog |
  |---|---|---|---|
  | RTL-2832 @ 2.4 MSPS | 73 | 146 | 1.99 s |
  | RSP1B @ 10 MSPS | 78,125 | 156,250 | 2.00 s |

  A WARN now fires if any dispatcher ends up pinned to its minimum bound, stating explicitly that this indicates a
  sizing defect rather than an overload condition. The ap-15.9 per-tuner startup line — which is the only reason
  this was found at all, since it printed `at [0.00000] MSPS ... bounded at [32] elements (Infinity seconds)` —
  now also reports the buffer sample count.

- **Now Playing channel mute wrote into alias playback priority, silencing unrelated channels.**
  Reported as mute "not working at times, more noticeable when channels and aliases are created."

  `ChannelMetadataPanel` stored mute state in two places and read it from a third. Three separate defects:

  1. **Mute mutated shared alias objects.** `setChannelMuted()` resolved a channel's "aliases" via
     `getChannelAliases()`, whose third fallback returns *every alias in the channel's configured alias list* —
     not the aliases of that channel. It then wrote `Priority.DO_NOT_MONITOR` into all of them and saved the
     playlist. Muting one conventional channel therefore muted **every channel sharing that alias list**, and
     unmuting reset them all to `DEFAULT_PRIORITY`, destroying whatever priorities the user had configured. An
     alias is a global, persisted, per-talkgroup object; channel mute is local and per-channel. Conflating them
     was the root defect.

  2. **The read path could disagree with the write path.** Mute on a channel with no aliases was tracked in an
     in-memory `mMutedChannelIds` set. Creating an alias afterwards switched `isChannelMuted()` onto the alias
     branch, which saw `DEFAULT_PRIORITY` and reported *unmuted* while the channel ID was still in the set — so
     the menu offered "Mute" on an already-muted channel. This is why the symptom tracked alias creation.

  3. **Re-apply asked a different question again.** The `ChannelAddListener` re-apply block muted a channel if
     **any** alias anywhere in its alias list carried `DO_NOT_MONITOR`, which is neither what `isChannelMuted()`
     checked nor what the user asked for.

  Mute is now single-sourced and never touches aliases. State is keyed on a channel identity built from system,
  site and name, persisted in `NowPlayingPreference` as one entry per channel. `Channel.getChannelID()` was
  unusable for this — it is assigned from an incrementing counter at construction, so it changes every run and
  every traffic channel receives a fresh one.

  Because traffic channels are created as `"T-"` plus the parent channel's name and carry the parent's system and
  site, stripping that prefix folds a trunked channel and all of its traffic channels onto one identity. Muting a
  trunked channel now actually silences it — its audio is produced by the traffic channels, not by the control
  channel whose row the user clicks — and the mute survives traffic channels being torn down between calls, as
  well as application restarts. A conventional channel whose name genuinely begins with `T-` is unaffected, since
  the prefix is only stripped from channels of type `TRAFFIC`.

  `ChannelProcessingManager.getProcessingChains()` is added so the mute can be applied to every running chain
  sharing an identity rather than only the one clicked. `AliasItemEditor`'s `AliasPriorityChangedEvent`
  subscriber is left in place but now has no publisher, since mute no longer changes alias priority.

### Added
- **Mains hum diagnostic for NBFM audio** (`io.github.dsheirer.dsp.audio.HumAnalyzer`), for the reported 60 Hz hum
  that is most noticeable during pauses in speech. This measures; it does not filter. No filter is designed until
  there is a measurement to design it against.

  Runs a Goertzel evaluation at 50, 60, 100, 120, 180 and 240 Hz over Hann-windowed 1600-sample blocks — 200 ms at
  8 kHz, a 5 Hz bin spacing chosen so that **every** analyzed frequency lands on an exact bin centre, leaving no
  scalloping loss to correct for and putting 50 and 60 Hz two bins apart rather than in one bin. Amplitude recovery
  validated numerically: a 0.01-amplitude 60 Hz tone reads −40.01 dBFS against a −40.00 dBFS expectation, a
  0.02-amplitude 120 Hz tone reads −33.98 against −33.98, mixed tones are separated correctly, and a 59.9 Hz tone
  still reads −40.01 so a slightly off-nominal mains frequency is not missed.

  At the end of each transmission it reports, per channel, three things chosen because each one changes the
  remedy:

  - **Which mains family the energy is in.** The 50 Hz set is measured alongside the 60 Hz set specifically so the
    result can come back negative. Energy in the 50 Hz family on a 60 Hz mains region would mean this is not mains
    hum and the filter would be the wrong fix.
  - **Fundamental versus second harmonic.** A dominant 120 Hz is the signature of full-wave rectifier ripple,
    consistent with a power supply or USB hub, and is the case in which a 60 Hz notch alone disappoints. A
    dominant 60 Hz points instead at a coupled magnetic field or a ground loop.
  - **Hum level during speech versus during pauses**, which tests the reported symptom directly. Blocks are
    classified against the transmission's own peak voice level. A hum level that is *constant* and merely more
    audible in the gaps is additive hum that a filter removes; one that genuinely *rises* in the gaps is a gain
    stage lifting the noise floor, where filtering treats a symptom of the gain control.

  Hum is reported against a voice-band reference taken through three cascaded 300 Hz high-pass sections, measured
  at 84 dB of rejection at 60 Hz so the reference is not itself mostly hum.

  The measurement is taken after the tone/squelch gate has decided to pass the audio but before any audio filter
  touches it, so it reflects what the demodulator delivered and never measures squelched noise. Output is at DEBUG
  and every measurement is skipped when that logger is not at DEBUG, so the cost when switched off is one boolean
  check per audio buffer. The analyzer is created on demand, so enabling it affects channels that are already
  running. Toggle it from the Diagnostics preferences panel under "Mains hum analysis (audio)"; a new
  `DiagnosticsCategory.HUM_ANALYSIS` was added for it.

- **Startup report of each NBFM channel's audio high-pass setting.** The 200/300 Hz Remez high-pass that
  attenuates hum and rumble lives in `AudioModule` and is driven by the per-channel "Audio Filter" checkbox, which
  is easy to leave unticked without noticing. An enabled channel now logs at INFO and a disabled one at **WARN**
  naming the setting, so a channel with no hum protection is visible in the log rather than only audible. This
  costs nothing and may on its own explain hum on specific channels.

  Note for context: the digital (P25/DMR) audio path has **no** high-pass at any point — `JmbeAudioModule` extends
  `AbstractAudioModule` directly and never reaches `AudioModule`'s filter. P25 adds only gain and a 10-band
  peaking equalizer. Nothing was changed there, but any hum on a digital channel is unfiltered by design, and
  since vocoded audio is reconstructed from bits rather than carried as a waveform, supply noise in the receiver
  cannot put hum into it — that would produce bit errors and garble instead. Hum on a digital channel therefore
  points upstream, at the radio system or dispatch console, not at local hardware.

### Changed
- `NowPlayingPreference` gained persisted per-channel mute state. Keys are sanitized and, when a channel identity
  exceeds `Preferences.MAX_KEY_LENGTH`, truncated with a hash of the full identity appended so two channels with
  a long shared prefix cannot collapse onto one entry. Unmuting removes the entry rather than storing `false`.

## [0.6.2-ap-15.9] - 2026-09-13

### Fixed
- **Polyphase buffer queue bound starved high-rate tuners (RSP1B)**
  > **CORRECTION (ap-15.9.1):** the diagnosis below is wrong and this change did not fix the problem. The bound
  > was collapsing to the 32-element floor, not the 2000-element ceiling, because the tuner reports a sample rate
  > of zero at construction time — and ap-15.9 reproduced that defect. The truncation described below is real but
  > incidental. See ap-15.9.1. Figures below (25.6 ms, 2,700 buffers/second, 12.6 M over 80 minutes) are
  > understated: the actual bound was 0.41 ms.

  The ap-15.8.1 queue bound was derived
  from `TunerController.getBufferDuration()`, which returns a `long` and truncates to zero for any tuner
  delivering buffers faster than 1 kHz. `RspTunerController.getBufferSampleCount()` returns 128, so an RSP1B
  at 10 MSPS produces **78,125 buffers/second** — a buffer duration of 0.0128 ms. That truncated to 0, the
  `Math.max(1, ...)` guard turned it into 1, and the bound collapsed onto its 2000-element ceiling:
  **25.6 ms of queue against a 10 ms dispatch interval**, where two seconds was intended.

  With only 2.5 dispatch intervals of headroom, ordinary scheduling jitter overflowed the queue continuously
  rather than at startup. On Paxton the dispatcher discarded roughly 2,700 buffers/second indefinitely — about
  3% of the sample stream, removed at arbitrary points — and the RSP1B could not hold a frequency lock.
  Measured 2026-09-12 across a single session: 12.6 million buffers discarded over 80 minutes with the RSP1B
  fitted, and zero overflow in every run without it.

  RTL-2832 tuners were never affected: 32,768-sample buffers at 2.4 MSPS give 13.65 ms, which truncates to 13
  and yields the intended ~2 seconds.

  The bound is now derived from sample rate and buffer sample count in floating point, with explicit floor and
  ceiling constants: RTL-2832 at 2.4 MSPS gets 146 elements (1.99 s, ~10 MB); RSP1B at 10 MSPS gets 156,250
  (2.00 s, ~80 MB). Each buffer dispatcher is also now named after its tuner — all tuners previously shared the
  thread name `sdrtrunk polyphase buffer processor`, so overflow warnings identified the stage but not the
  device — and the computed bound and buffer rate are logged at startup.

- **Frequency error correction could be cancelled permanently and silently** — `TunerFrequencyErrorManager.process()`
  averages channel measurements with `requestedChangeHz /= count`. The guard above it is
  `if(!mChannelManagers.isEmpty())`, which checks that managers *exist*, not that any reported a measurement
  during the interval, so `count` is zero whenever the tuner has a quiet five-second window and the division
  throws `ArithmeticException`.

  `process()` had a `try`/`finally` for the tuner lock but no `catch`, and it runs via
  `ThreadPool.SCHEDULED.scheduleAtFixedRate()` — where an uncaught throwable cancels all future executions. The
  exception is captured in the `Future` and never read, so frequency error correction stopped for that tuner for
  the rest of the session with nothing logged at any level.

  Now guarded explicitly, and the scheduled entry point catches everything and logs at ERROR so a future failure
  announces itself instead of silently disabling correction. **This defect is also present upstream** and is a
  clean PR candidate.

- **PPM baseline latch disabled correction permanently after drift (ap-fork)** — the ap-fork sanity clamp rejects
  any proposed PPM more than `SANITY_CLAMP_PPM` (10) from the baseline, but the baseline is only updated when a
  measurement is *accepted*, so a wrong baseline can never correct itself.

  Gradual drift is not the problem — the EMA follows it. The failure mode is a baseline that starts wrong or is
  displaced in one step: the first measurement is latched **unconditionally** (`isNaN` → accept), so a reading
  taken while the tuner was still settling poisons the baseline for the entire session and every subsequent
  correction is rejected forever. Simulated with a poisoned first measurement of 40 ppm against a true error of
  2 ppm: before the fix the baseline stays pinned at 40.000 and no correction is ever applied again; after it,
  the baseline re-acquires to 2.115 and tracking resumes. Rejections logged at DEBUG in a package no Diagnostics
  category covers, so this was invisible in practice.

  After `BASELINE_REACQUIRE_AFTER_REJECTIONS` (12, one minute at the 5-second interval) consecutive rejections,
  the baseline is now discarded and re-acquired from the next measurement, with a WARN naming the tuner and the
  stale baseline.

  Note: these two correction defects predate ap-15.8.1 and match reports of occasional tuner lock difficulty on
  ap-15.7 and earlier. They are code-reading findings — both fail silently, so no log evidence exists either way.

## [0.6.2-ap-15.8.1] - 2026-09-07

### Fixed
- **GUI freezes while decoding continues (channel metadata table row sorter)** — Monson froze on
  2026-08-30 at 17:07 and kept decoding normally for more than five hours with a dead interface.
  Console capture showed two uncaught exceptions on the Swing event dispatch thread, both from
  `ChannelMetadataModel.remove()` at line 196:

  ```
  IllegalArgumentException: Comparison method violates its general contract!
    ... DefaultRowSorter.sort -> rowsDeleted -> fireTableRowsDeleted
  ArrayIndexOutOfBoundsException: Index 38 out of bounds for length 38
    ... DefaultRowSorter.setModelToViewFromViewToModel -> rowsDeleted
  ```

  Cause: the clickable column-header sorting added to the channel metadata table (fork feature,
  not upstream) means removing a channel triggers a full re-sort. Every column in that table is
  updated live by decoder threads, so values change underneath TimSort mid-sort; it detects the
  resulting inconsistency and throws. The aborted sort leaves the sorter's model-to-view index
  arrays inconsistent, so the next row change throws `ArrayIndexOutOfBoundsException`, and from
  there every table change kills the EDT. Sites running P25 trunked systems are most exposed,
  since traffic channels are created and destroyed constantly.

  Fix: new `SafeTableRowSorter` (`gui/control/`) guards every model-change notification and, on
  failure, clears the sort keys and rebuilds a clean identity mapping. The symptom degrades from
  "the GUI is frozen" to "the table stopped being sorted", recovery is logged at WARN, and the
  user can click a column header to re-apply sorting.

  Verified by reproduction: with a model whose values mutate during sorting, a stock
  `TableRowSorter` dies with the identical `IllegalArgumentException` while `SafeTableRowSorter`
  survives and recovers.

  Note this bug is unrelated to the DMR work in ap-15.8 — Monson was running ap-15.7 when it froze,
  and the two failures share no code. The ap-15.8 build carrying only the DMR changes
  was deployed to Somerville on 2026-08-22 and predates this fix.

- **"Show in Waterfall" displays the wrong tuner** — right-clicking a channel and choosing *Show
  in Waterfall* sometimes zoomed to a spectrum with a different noise floor and no green channel
  column. Reported for Lawrence Fire New P25 (453.5125) while the analog channel on the same
  frequency worked correctly, plus one other channel.

  Cause: `ChannelMetadataPanel.showChannelInWaterfall()` resolved the tuner from the channel's
  configured **preferred tuner** first, and accepted it merely for existing — no check that the
  tuner was carrying the channel or even tuned to cover its frequency. The preferred tuner is
  only a request; when it has no spare bandwidth or is tuned elsewhere, the tuner manager sources
  the channel from a different tuner and says so in the log (*"Unable to source channel [x] from
  preferred tuner [y] - searching for another tuner"*). The authoritative lookup — matching the
  channel frequency against each tuner's `center ± sampleRate/2` — only ran when no preferred
  tuner was set at all, which is why setting the channel's tuner to *None* made it work.

  Fix: resolution order reversed. The live processing chain's `TunerChannelSource` is now
  consulted first and is authoritative for a running channel, for both the serving tuner and the
  frequency to center on (a trunked traffic channel is often not on its configured frequency).
  The configured preferred tuner is used only as a fallback for a channel that isn't decoding,
  and only when its current tuned range actually covers the channel frequency; otherwise the
  frequency-range search runs. Rejections and outright failures now log at DEBUG instead of
  silently doing nothing.

- **ThinLine Radio and Rdio Scanner upload failures were silently swallowed** — the `whenComplete`
  callback on the audio upload tested `throwable1 != null || fileResponse.statusCode() != 200` and
  then dereferenced `fileResponse` in every branch. When the request fails at the network level
  (connection refused, timeout, socket reset) the throwable is non-null and the response is
  **null**, so the error handler itself threw a `NullPointerException` — inside a
  `CompletableFuture` callback, where it was discarded without ever reaching a log. The result was
  a feed that could fail indefinitely while producing no log output whatsoever.

  The throwable case is now handled separately from a non-200 response, and unwraps
  `CompletionException` to report the underlying cause. The same defect exists upstream in
  `BroadcastifyCallBroadcaster` and `OpenMHzBroadcaster` and is a candidate for an upstream PR.

- **Unbounded `Dispatcher` queue caused a 10 GB heap exhaustion (Paxton, 2026-09-05)** — SDRTrunk
  died at 09:44:46 with `java.lang.OutOfMemoryError: Java heap space` on eleven threads at once,
  taking down all sixteen Zello streams.

  The log shows the shape clearly. Heap sat between 455 and 832 MB inside a 912 MB committed heap
  for the preceding 56 minutes. At 09:37:11 logging stopped completely — not just DSP threads, but
  the scheduled pool and the HttpClient workers too, which had been writing several lines per
  second. Fifty-seven seconds later the next line appears already reading `[9GB/10GB 99%]`, then
  one line in the following six and a half minutes, then the OOM cascade. Measured from closely
  spaced heap samples, this machine allocates at roughly 1 GB/s (mean 1030 MB/s, peak 1429 MB/s
  between rising samples), so ten seconds of consumer stall is enough to fill a 10 GB heap.

  Cause: `Dispatcher.receive()` added to an unbounded `LinkedTransferQueue` and never blocked or
  dropped, while the producers are USB transfer callback threads that keep running regardless of
  consumer state. `process()` then drained unbounded into a freshly allocated `ArrayList` on every
  interval, copying a backlogged queue into a second structure of the same size before dispatching
  anything. Queued elements are strongly reachable, so they are live objects the collector cannot
  reclaim — which is why the process spent six minutes making no progress rather than recovering.
  The 10 GB maximum heap was not protection here; it was runway.

  Fix: the queue is now bounded and discards the oldest elements on overflow, which is the correct
  policy for real-time DSP where the newest samples are the useful ones. Occupancy is tracked in an
  `AtomicInteger` rather than by calling `LinkedTransferQueue.size()`, which is O(n) and would
  become a hotspot under exactly the backlog this bound exists to handle. `drainTo` is bounded and
  targets a reused list instead of allocating one per interval. Overflow logs a WARN with the
  discarded count, rate limited to one message per ten seconds, and recovery logs at INFO.

  Bounds are derived rather than guessed. The polyphase buffer dispatcher takes two seconds of
  tuner buffers, computed from the tuner's own buffer duration, so it scales across tuner types
  (~150 buffers for an RTL-2832 at 2.4 MSPS). The IFFT and per-channel channel-results dispatchers
  take 25 batches, which is about one second: batch rate is a constant ~24/second for any tuner
  because channel count scales with sample rate, so a fixed element bound is a fixed time bound.
  Recorders get a much larger bound since disk writes stall longer and dropping recorded audio is
  worse than a backlog. Every bound was checked against its callsite's real arrival rate and has
  between 20x and 209x headroom over normal per-interval traffic.

  Verified by a concurrency harness: under a fully stalled consumer with 500,000 elements produced,
  observed queue size never exceeded the bound, and consumed + dropped + queued accounted for every
  element with none lost or double counted. At realistic arrival rates the tightest bound dropped
  nothing.

- **Null response dereferenced in Broadcastify Calls and OpenMHz upload callbacks** — the same
  defect fixed for ThinLine and Rdio Scanner earlier in this release. Both wrote
  `if(throwable != null || response.statusCode() != 200)` and then dereferenced `response` inside
  that branch, but `whenComplete` passes a null response whenever the throwable is non-null, so any
  upload failure that was not an `IOException` or `CompletionException` threw a
  `NullPointerException` from inside the completion handler and lost the real error. OpenMHz was
  worse — both of its branches dereferenced the response, so its socket-reset path threw every
  time. The throwable case is now handled separately and logs the underlying cause.

- **Zello `HttpClient` never closed** — `AbstractZelloBroadcaster` builds an `HttpClient` per
  broadcaster and `BroadcastModel` destroys and recreates broadcasters on reconnect, so the
  client's selector and worker threads accumulated for the life of the application. Now closed on
  dispose.

- **Leftover squelch debug output** — a `System.out.printf` in `NBFMAudioFilters` printed squelch
  level, threshold, gate state and gain to stdout every 1000 samples. Left in from 2026-03-16
  (`ae6dc745`) and never removed.

- **Eclipse build (upstream #2434, `9dcebb49`)** — `OpenMHzEditor` sits in
  `audio/broadcast/openmhz/` but still declared `package io.github.dsheirer.gui.playlist.streaming`.
  Gradle's flat source set tolerates the mismatch; Eclipse does not. Package declaration corrected,
  `AbstractBroadcastEditor` imported, and `StreamEditorFactory` now imports `OpenMHzEditor`. The
  `BroadcastModel` half of that upstream commit is cosmetic and was not taken — the fork has two
  call sites there because of staggered broadcaster startup.

### Added
- **ThinLine Radio and Rdio Scanner diagnostics** — the `THINLINE` and `RDIO` categories already
  existed in the Diagnostics panel but had nothing behind them: `ThinLineRadioBroadcaster` carried
  seven `error` calls and no `debug`, `info` or `warn` statements at all, so switching either
  category to DEBUG produced no output.

  Both broadcasters now log the upload lifecycle at DEBUG — recording queued with queue depth,
  upload starting with talkgroup and byte count, upload accepted with elapsed milliseconds,
  duplicate rejected by the server, and aged-off recordings discarded without upload. Connection
  state changes log at INFO on (re)connect and WARN on a failed connection test, including the
  server's response text and the retry interval. Queue-depth and aged-off events were previously
  invisible at any log level.

## [0.6.2-ap-15.8] - 2026-08-22

Pre-release build deployed to Somerville only. Contains the DMR/TDMA work and nothing
else — no GUI freeze fix, no waterfall fix, none of the streaming or Dispatcher work
in 15.8.1. A machine reporting `0.6.2-ap-15.8` is running exactly this.

### Fixed

- **DMR digital bleed recorded as calls on CTCSS-filtered NBFM channels** — Somerville Fire
  (483.3875, target 131.8 Hz) was recording multi-second bursts of digital buzz. Spectral
  analysis of four captured bursts identified them as 2-slot TDMA (DMR): constant envelope,
  no voice, and a hard 29.9 ms slot cadence (33.4 Hz) in every clip. Three separate defects
  combined to let them through:

  1. **Loss counter reset on unconfirmed detection.** `CTCSSDetector.handleDetection()` reset
     `mLossCounter` on a single raw block-level match of the target tone. A DMR carrier
     demodulated as FM sprays broadband noise across the CTCSS bins and lights up the target
     bin often enough to keep resetting it, so the gate never closed. The counter now resets
     only once a detection is CONFIRMED (`CONFIRMATION_COUNT` consecutive blocks). Log evidence:
     3,206 holdover-carried gate opens against 791 confirmed opens on a single day.

  2. **Unbounded holdover.** The 500 ms holdover was only checked at squelch-open; once opened
     the gate stayed open until the loss counter expired, which defect 1 prevented. Added
     `HOLDOVER_CONFIRM_DEADLINE_MS` (600 ms): a gate opened on holdover that fails to re-confirm
     the tone within the deadline is force-closed. Applies to CTCSS and DCS channels alike.

  3. **No defence against digital carriers.** CTCSS analysis cannot reject interference whose
     energy genuinely lands on the target frequency — worsened by ~12 Hz Goertzel bin resolution,
     which cannot separate 127.3 Hz from 131.8 Hz (observed as `raw=127.3` in 710 holdover opens).

### Added

- **`TdmaInterferenceDetector`** — rejects DMR / P25 Phase 2 bleed on tone-filtered NBFM
  channels. Builds a 500 Hz RMS envelope, high-passes it at 15 Hz to strip syllabic speech
  rates, then scores a harmonic comb (f0, 2·f0, 3·f0) across candidate 30 ms slot cadences via
  Goertzel, against an off-comb noise reference. Requiring all three harmonics is what separates
  a true TDMA cadence from broad modulation that merely has energy near 33 Hz. While
  interference is present the tone gate is vetoed and any open call is ended.

  Validated against the four captured DMR bursts (per-window peaks 64, 76, 182, 354 — all
  vetoed) and synthetic negatives (speech-like AM 13, white noise 8, CTCSS tone + speech 9,
  60 Hz hum 5, squelch flutter 1 — none vetoed). Threshold 20 sits 1.5x above the worst
  negative and 3.2x below the weakest real burst. Vetoes log their score at DEBUG for auditing.

## [0.6.2-ap-15.7] - 2026-08-01

### Fixed
- **Zello "audio data sent too fast" wedges channel in Configuration Error** — The async
  stream-kill error (and "bad mid") was not classified as transient, so it fell through to the
  terminal error branch and set `CONFIGURATION_ERROR` — a state that blocks automatic
  reconnects. ap-15.6 soak logs showed this wedged Brookline MA Fire, Winchester MA Police,
  Watertown MA Police, Everett MA Police, Saugus MA Police, and Atlantic EMS 1 until a manual
  restart or a later disconnect let the watchdog recover them. Both errors are now in
  `TRANSIENT_STREAM_ERRORS`: stream state resets cleanly with a backoff (1s for
  "audio data sent too fast") and the channel keeps running.

- **Zello unpaced pending-frame flush triggers server stream kills** — Frames buffered while
  waiting for `stream_id` (inflated by the ap-15.6 CTCSS confirmation flush pushing ~250-500ms
  of audio through the encoder faster than real time) were sent in one unpaced burst when the
  stream opened. Soak logs: bursts of 13+ frames intermittently drew the server's
  "audio data sent too fast" [3008] stream kill (18 occurrences); bursts ≤12 were always
  accepted. Now `flushPendingFramesPaced()` sends 8 frames immediately (evidence-based safe
  burst) and drains the remainder at 55ms per frame. On stream stop, up to 8 undrained frames
  are sent as a final bounded burst so short calls keep their tail audio.

- **Zello pending-frame cap raised 15 → 30** — At the old cap, ~11% of stream starts hit the
  limit and silently evicted the oldest frames — the start-of-call audio the CTCSS buffering
  fix was meant to preserve. 30 frames (~1.8s) covers the CTCSS flush plus server latency
  without eviction; the paced drain makes the larger backlog safe to send.

- **Zello pending-stop timeout log spam** — The server never sends `on_stream_stop` for
  client-initiated stops, so the 500ms timeout fires on essentially every stop by design
  (~53,000 WARN lines per 3 days across two machines). Demoted to DEBUG.

### Fixed (upstream ports)
- **SampleNativeBuffer wrong SIMD implementation selection (upstream #2398)** — Ported upstream
  fix e2d9c4c. `getNonInterleaved()` switched on `mInterleavedImplementation` instead of
  `mNonInterleavedImplementation`, so the non-interleaved sample path could select the wrong
  vector implementation. Fields are now `final`, matching upstream.

### Added (upstream ports)
- **NXDN radio reference editor support (upstream #2446)** — Manually merged into the fork's
  customized `FrequencyEditor` (which adds CTCSS/DCS/NAC tone filter auto-import): the radio
  reference importer now creates NXDN repeater channel configurations from non-trunked county
  entries. `ModeDecoderType` NXDN split into NXDN48/NXDN96 mapping to 4800/9600 transmission
  modes.
- **Playlist file name sorting (upstream #2342)** — Playlist manager path column is now
  sortable (case-insensitive).
- **Gradle JVM autoprovisioning (upstream #2427)** — `foojay-resolver-convention` plugin in
  settings.gradle plus `JvmVendorSpec.BELLSOFT` in the toolchain spec: Gradle now downloads
  the correct Bellsoft Liberica JDK 25 automatically on machines that lack it. Also removed
  the redundant sourceSets block, matching upstream.
- **NXDN talker alias log spam removal (upstream #2442)** — Removed the "Unexpected NXDN
  Talker Alias Fragment sequence" INFO logging.
- **Extended DCS code list (upstream #2424)** — Ported upstream commit 3c01c64, which extends
  `DCSCode` beyond the original ETSI-spec list to cover the codes supported by most modern
  radios: 43 new entries (21 normal + 22 inverted, e.g. N036/I036, N053/I053, N122/I122).
  New codes are inserted within the existing `N023..N754` / `I023..I754` enum ranges, so they
  appear automatically in the NBFM tone filter DCS combo box and are recognized by the
  DCS detector's code map.

## [0.6.2-ap-15.6] - 2026-07-30

### Fixed
- **Noise blanker kills audio on weak NBFM signals** — The `NoiseBlanker` had a self-reinforcing
  feedback loop: only non-blanked samples updated the running average power, so on weak signals the
  average stayed artificially low and legitimate voice peaks exceeded the 225× blanking threshold.
  Once triggered, the blanker suppressed more and more of the signal, eventually gating all audio
  (e.g., Cataldo Ambulance). Fix: blanked samples now update the running average at 1/10th normal
  weight (`ALPHA_BLANKED`), allowing the average to gradually rise to match the actual signal level.
  A safety valve checks the blanking rate every 2000 samples and boosts the average by 50% if more
  than 5% of samples are being blanked.

- **CTCSS tone filter clips first ~250ms of NBFM calls** — The CTCSS detector requires 3
  confirmation blocks (~83ms each) before allowing audio to pass. During that window,
  `processResampledAudio()` discarded the audio entirely instead of buffering it. Fix: audio is now
  buffered (up to 500ms / 4000 samples at 8 kHz) during tone confirmation and flushed through the
  normal audio pipeline when the detector confirms the correct tone. The 500ms tone holdover for
  rapid squelch flutter is unchanged — calls that re-open within the holdover window still pass
  audio immediately.

- **Zello pending stop timeout too long (5s → 500ms)** — The Zello server does not send
  `on_stream_stop` in response to client-initiated `stop_stream`, so every stream hit the 5-second
  safety timeout before allowing the next stream to start. Log analysis showed 31 deferred stream
  starts on Stoneham and 9 on Somerville from this delay. Reduced `PENDING_STOP_TIMEOUT_MS` from
  5000 to 500ms.

## [0.6.2-ap-15.5] - 2026-07-28

### Fixed
- **Zello orphaned stream collisions (3008 "channel busy")** — Three changes to
  `AbstractZelloBroadcaster` stream lifecycle to eliminate orphaned streams that leave the Zello
  server in a "channel busy" state:
  1. **Server-acknowledged stream closure** — After sending `stop_stream`, the broadcaster now tracks
     the pending stop and blocks new `start_stream` requests until the server confirms with
     `on_stream_stop` (matched on stream_id). Previously, local state was cleared immediately after
     sending `stop_stream`, so the `on_stream_stop` response arrived with "not ours" and was ignored,
     allowing a new stream to race the server's cleanup. A 5-second safety timeout prevents permanent
     blocking if the server never responds.
  2. **Stop-before-retry on 3008** — When the server rejects `start_stream` with "channel busy", the
     broadcaster now sends `stop_stream` for the last known `stream_id` before scheduling the retry.
     This clears the orphaned server-side stream instead of blindly retrying into the same collision.
  3. **WebSocket reset on repeated 3008s** — After 3 consecutive "channel busy" failures on the same
     connection, the broadcaster disconnects and reconnects the WebSocket. Since Zello allows only one
     outbound stream per connection, a fresh socket guarantees the stuck stream is cleared.

## [0.6.2-ap-15.4] - 2026-07-26

### Fixed
- **Zello audio distortion when graphic EQ is enabled** — The float-to-short conversion in
  `AbstractZelloBroadcaster.processAudioBuffer()` had no clipping protection. When the P25 graphic
  equalizer boosted frequency bands, audio peaks could exceed the ±1.0f range after
  NonClippingGain + EQ, causing the raw `(short)(sample * 32767)` cast to overflow and wrap —
  producing hard clipping distortion audible on Zello streams but not on local speakers (which
  handle float samples natively). Added a `tanh` soft clipper that smoothly compresses peaks
  exceeding ±1.0f before the short conversion, preserving audio quality through the Opus encoder.

## [0.6.2-ap-15.3] - 2026-07-24

### Fixed
- **AliasFactory cannot copy CTCSS or NAC alias IDs** — `AliasFactory.copyOf()` switch was missing
  cases for `CTCSS` and `NAC` alias ID types, causing a "cannot make copy of instance" warning and
  silently dropping those IDs when the alias editor creates a working copy. This prevented adding or
  editing streaming actions on any alias that contained a CTCSS tone filter. Also added `INVERT` to
  the legacy/unsupported fall-through (no implementation class exists).

## [0.6.2-ap-15.2] - 2026-07-22

### Fixed
- **RealResampler buffer overflow crash** — `dispatchOutputBuffer()` contained a `mLastBatch`
  tail-flush block that attempted to copy all remaining output samples into a fixed-size
  `float[512]` array without checking the count. When the resampler produced a large output burst
  (e.g., 1040 samples), the `dispatchFullOutputBuffers` loop would extract 512, leaving 528 — and
  the tail-flush block would try `FloatBuffer.get(float[512], 0, 528)`, throwing
  `IndexOutOfBoundsException`. This fired on every NBFM squelch close (`lastBatch=true`) and was
  suppressed 4-of-5 by log suppression, creating continuous exception garbage that contributed to
  GC pressure and UI freezes. Fix: removed the redundant `mLastBatch` tail-flush from
  `dispatchOutputBuffer` (already handled by `flushPartialOutput()` after the dispatch loop) and
  changed `dispatchFullOutputBuffers` from `>` to `>=` to dispatch exact-512 buffers immediately.

## [0.6.2-ap-15.1] - 2026-07-18

Zello silent channel death fix and upstream channelizer performance optimization.

### Fixed
- **Zello silent channel death on code=1006 disconnect** — `onClose` and `onError` WebSocket
  callbacks now wrap `setBroadcastState()` in try-catch so `scheduleReconnect()` always executes,
  even if a listener exception propagates through the event bus and kills the callback. Previously,
  channels like Boston MA EMS and Massport Fire would silently die on code=1006 disconnects with no
  reconnect scheduled and no stack trace in the logs. Confirmed across 7 days of production logs
  (July 11-17) affecting 7 channels total.
- **Watchdog blind spot for disconnected channels** — `watchdogTick()` previously only caught
  channels in `TEMPORARY_BROADCAST_ERROR` state with no pending reconnect. Now catches any channel
  where `!mConnected && !mStopped && noReconnectPending`, regardless of broadcast state. This
  covers the edge case where the async `Platform.runLater` state update hasn't propagated yet,
  leaving the state stale as `CONNECTED` while the channel is actually dead.

### Changed
- **Reconnect scheduling logged at WARN** — `scheduleReconnect` in both `AbstractZelloBroadcaster`
  and `ZelloSharedConnection` now logs at WARN instead of DEBUG, making reconnect events visible in
  production logs without enabling debug output.

### Upstream
- **Fused multiply-accumulate in polyphase channelizer** — Ported upstream optimization to
  `ComplexPolyphaseChannelizerM2.process()`. The multiply and per-sub-channel accumulation are now
  fused into a single pass with a pre-allocated reusable accumulator buffer (`mFilterAccumulator`),
  eliminating two per-call array allocations (~194 MB/sec of GC garbage per tuner at 2.4 MSPS).
  Instance fields are cached into `final` locals to avoid virtual method dispatch in the inner loop.
  Output is bit-for-bit identical to the previous implementation.

## [0.6.2-ap-15] - 2026-07-11

Major upstream merge: NXDN decoder, frequency error management redesign, and fork customization port.

### Added
- **NXDN 4800/9600 decoder** — Complete NXDN protocol support from upstream PR #2431 including
  4FSK demodulation, Layer 1/2/3 message stack, AMBE audio decoding, trunk tracking with traffic
  channel management, channel map configuration, and GUI configuration editor.
- **NXDN Type-C/D support** — Includes conventional repeater (SCCH) decoding alongside trunked modes.
- **Two-tier frequency error management** — New `ChannelFrequencyErrorManager` (per-channel, 500ms
  loop, mixer adjustment) and `TunerFrequencyErrorManager` (per-tuner, 5s loop, multi-channel
  averaging, PPM adjustment) replace the old single-tier `FrequencyErrorCorrectionManager`.
- **P25 ChannelEventTracker refactoring** — `P25TrafficChannelEventTracker` replaced by generic
  `ChannelEventTracker<T>` base class with `P25ChannelEventTracker` and `NXDNChannelEventTracker`
  subclasses.
- **jdk.charsets module** — Added for NXDN BIG5 character encoding support in talker aliases.

### Changed
- **PPM sanity clamp ported to new architecture** — The AP-fork's `SANITY_CLAMP_PPM = 10.0` rejection
  threshold and baseline EMA tracking (0.8/0.2 weighting) have been ported from the deleted
  `FrequencyErrorCorrectionManager` to the new `TunerFrequencyErrorManager`. Wild PPM swings from
  PLL lock-on errors are still rejected; baseline is preserved across corrections.
- **FeedbackDecoder API** — `processPLLError(float, int)` replaced with `processPLLError(float)` using
  stored decimated sample rate. New `setDecimatedSampleRate(double)` method.
- **TunerChannelSource** — Constructor now takes `TunerFrequencyErrorManager` parameter. New abstract
  `setFrequencyCorrection(long)` method for per-channel mixer correction.

### Removed
- **FrequencyErrorCorrectionManager** — Replaced by two-tier system (see Added).
- **CarrierOffsetProcessor** — Removed by upstream.
- **TunerFrequencyErrorMonitor** — Replaced by `TunerFrequencyErrorManager`.

### Upstream
- Merged DSheirer/sdrtrunk PR #2431 (NXDN decoder + frequency error redesign, 30,854 additions,
  445 files) and PR #2433 (NXDN cleanup, jdk.charsets + tooltip fixes).

## [0.6.2-ap-14.10] - 2026-07-02

Zello WebSocket reliability, connection rate-limiting, and multi-channel cleanup release.

### Fixed
- **WebSocket Ping/Pong bug in shared pool mode** — `ZelloSharedConnection.onPing()` was overriding
  the default Java `WebSocket.Listener` without sending a Pong reply or requesting the next message.
  The Zello server sends a WebSocket Ping every 30 seconds and closes connections that don't Pong back
  within 30 seconds. This was likely the primary cause of shared-pool connections dropping after 30–60
  seconds. Now correctly calls `ws.sendPong(msg)` and `ws.request(1)`.
- **JavaFX playlist editor white screen on GPU failure** — Added `-Dprism.order=d3d,sw` to Windows JVM
  args so JavaFX falls back to software rendering when the Direct3D pipeline fails (e.g. after a GPU
  driver crash). Previously the playlist editor window stayed permanently white until reboot.

### Changed
- **Exponential backoff on reconnects** — Normal (non-kicked) reconnects in both
  `AbstractZelloBroadcaster` and `ZelloSharedConnection` now use exponential backoff: 15s, 30s, 60s,
  120s (capped), plus 0–5s random jitter. Previously used a flat 15–20s delay regardless of how many
  consecutive failures had occurred. With 20–30 channels dropping at once, the old fixed delay would
  exceed Zello's 10 connections/minute/IP limit. The backoff counter resets on successful logon.
- **Heap increased from 6GB to 10GB** — Both Windows and Linux JVM args in `build.gradle` now use
  `-Xmx10g` to prevent GC pressure from freezing the waterfall and playlist editor on machines with
  many decoders and streams.

### Removed
- **Zello Multi-Channel Configuration** — Removed the `ZelloMultiChannelConfiguration` parent/child
  auto-generation system (`expandMultiChannelConfig()`, `syncMultiChannelChildren()`,
  `persistToParent()`, `mAutoGenerated`, `mParentConfigName`). Each channel now requires its own
  individual `ZelloConfiguration` with explicit credentials. The `ZELLO_WORK_MULTI` broadcast type
  has been removed from `BroadcastServerType`, `BroadcastFactory`, `StreamEditorFactory`, and
  `@JsonSubTypes`. Stub classes remain for backward compatibility with existing playlist XML files.

### Added
- **CTCSS guard tones** — 65.0 Hz and 260.0 Hz guard frequencies added to `CTCSSDetector` to catch
  low-frequency interference and harmonic artifacts that could false-trigger tone detection.
- **Zello shared WebSocket pool** — `ZelloSharedConnection` allows multiple channels with the same
  credentials to share a single WebSocket connection, reducing connection count. Configurable per
  channel via the "Shared Connection" checkbox in the Zello editor.

## [0.6.2-ap-14.9.14] - 2026-06-18

Zello streaming table UX and configuration editor alignment.

### Fixed
- Stream-level Zello API errors (`channel busy`, server `on_stream_stop`, etc.) no longer linger in
  the streaming table error column while the broadcaster status is Connected.
- Zello Work and Consumer editor empty-form defaults aligned with configuration class defaults
  (`stream_guard_ms`, `pause_time_ms`, `relaxation_time_ms`).

### Changed
- Connection-level errors (handshake failure, timeout, kicked, channel offline) still display in the
  error column; stream-level issues are logged but cleared from the UI during healthy sessions.

## [0.6.2-ap-14.9.13] - 2026-06-18

Zello reconnect and rate-limit reliability release.

### Fixed
- Manual Reconnect no longer waits on the cold-start slot counter (~34s delay after startup).
- Rejected `start_stream` (`channel busy`, etc.) no longer increments ghost-stream counter or forces session reconnect.
- Ghost-stream detection limited to streams that never received a `stream_id` (pending id -1), not explicit failures (-2).

### Changed
- Cold-start connections batched at 9 per minute to stay under Zello's documented 10/min/IP WebSocket limit.
- `channel busy` treated as transient with 750ms minimum retry backoff plus configured pause/guard times.
- Manual reconnect stagger uses 2s spacing instead of reusing cold-start batch delays.

## [0.6.2-ap-14.9.12] - 2026-05-29

Zello architecture refactor and broadcast reliability release.

### Added
- `AbstractZelloBroadcaster` shared base class for Zello Work and Consumer real-time streaming.
- `ZelloProtocolUtil` for shared Opus constants and Bridge error-code mapping.
- `ZelloChannelConfiguration` interface for shared timing/channel settings.
- Zello unit tests: `ZelloProtocolUtilTest`, `ZelloBroadcasterTimingTest`, `ZelloSessionEpochTest`.

### Fixed
- `BroadcastModel` startup stagger counter no longer grows unbounded across manual reconnects.
- Separate reconnect-batch slot counter with 3s gap reset between reconnect waves.
- Missing `break` in `BroadcastModel` aged-off/error table update switch.
- Zello Consumer keepalive, channel-offline reconnect, and encoder shutdown aligned with Work.
- Stream guard and pause time no longer block real-time audio threads (`Thread.sleep` removed).

### Changed
- `ZelloBroadcaster` and `ZelloConsumerBroadcaster` reduced to thin subclasses (~100 lines each).
- Stream guard and pause use scheduled timers; `isRealTimeReady()` respects pending delays.
- Successful Zello streams now increment parent `incrementStreamedAudioCount()` correctly.

## [0.6.2-ap-14.6] - 2026-04-11

Runtime-diagnostics release. Adds per-category DEBUG toggles, persistent channel table sort,
and ThinLine Radio debug-by-default.

### Added
- Diagnostics preferences panel (Application -> Diagnostics (Logging)) with per-category
  DEBUG toggles for Zello, ThinLine Radio, Rdio Scanner, SDRPlay, RTL-SDR, the channelizer,
  the tuner manager, the P25 decoder, and NBFM/audio output.
- Master "Enable ALL diagnostics categories" checkbox with a warning about log volume.
- Runtime Logback level control (`LogLevelController`) that applies persisted preference
  state on startup and immediately when a checkbox is toggled, with no application restart.
- `FxTableColumnMonitor` helper that persists JavaFX TableView column widths, visible
  order, and sort order across restarts. Wired into the Channels editor with stable column
  ids (`channelTable.system`, `.site`, `.name`, `.frequency`, `.protocol`, `.playing`,
  `.autoStart`).
- ThinLine Radio logger defaults to DEBUG in `logback.xml` so live streaming sessions get
  full diagnostic output without user action.

### Fixed
- Channel table sort order and column widths are no longer lost when reopening the
  Channels menu. Previously the view reverted to the default ordering on every show.

### Changed
- `DiagnosticsPreference` is initialized by `UserPreferences` and immediately pushes its
  state into the Logback context via `LogLevelController.applyAll`, guaranteeing the user's
  last diagnostics selection is active from the first log line after startup.
- `.github/ISSUE_TEMPLATE/config.yml` now enables blank issues so fork issues can be filed
  without going through the upstream support wiki template.

## [0.6.2-ap-14.5] - 2026-04-10

- Treat "failed to start sending message" and "failed to stop sending message" as transient
  Zello server errors that trigger reconnect rather than broadcaster shutdown.
- Bumped `projectVersion` for the fix above.

## [0.6.2-ap-14.4] - 2026-04-09

- NBFM hiss reduction: wire new post-demod audio filters into the NBFM path.
- Default filter updates to reduce clipping on loud voice channels.
- Zello reconnect button on the broadcaster status panel for manual recovery.
- Verbose Zello diagnostic logging (session epoch, stream ids, opus state).
- Fix Opus encoder crash when frame size changed mid-session.
- Updated Zello broadcaster default configuration values.

## [0.6.2-ap-14.3] - 2026-04-07

- Earlier Zello reliability improvements (initial transient-error handling).
- Audio pipeline tuning for Cambridge COMIRS P25 trunking system.
- Rdio Scanner stream wiring and API-key reporting improvements.

[0.6.2-ap-14.10]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.10
[0.6.2-ap-14.9.14]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.9.14
[0.6.2-ap-14.9.13]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.9.13
[0.6.2-ap-14.9.12]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.9.12
[0.6.2-ap-14.6]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.6
[0.6.2-ap-14.5]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.5
[0.6.2-ap-14.4]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.4
[0.6.2-ap-14.3]: https://github.com/actionpagezello/sdrtrunk/releases/tag/v14.3
