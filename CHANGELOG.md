# Changelog

All notable changes in the `actionpagezello/sdrtrunk` fork are documented here. Upstream
DSheirer/sdrtrunk changes are not repeated; only the `ap-` fork deltas are recorded.

Versioning follows `0.6.2-ap-<n>` where `<n>` increments for each fork release.

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
