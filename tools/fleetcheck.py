#!/usr/bin/env python3
"""
fleetcheck.py - one pass over SDRTrunk application logs, reporting every failure mode this fork
has found in the field so far.

    python fleetcheck.py <log files or directories> [--days N] [--verbose]

Reads the standard SDRTrunk log line format and needs NO extra logging configuration: every marker
below is emitted at INFO or WARN by a stock ap-15.9.x build.  Turning on DEBUG adds detector
internals that this script does not use, and costs a large amount of disk on a busy machine.

What it reports, and why each one matters:

  version / uptime   Which build actually ran, and every start and shutdown in the window.  Several
                     past disagreements about what a machine was running came down to an upgrade
                     landing after the log excerpt ended.

  heap               Peak heap from the per-line [used/committed pct%] stamp.  Fleet steady state
                     is around 1 GB; anything climbing steadily across a session is worth a second
                     look.

  zello wedge        A 'Zello stream started' with no 'stream stopped' before the next start or the
                     end of the log.  This is the ap-15.9.5 orphaned-stream failure: the feed goes
                     silent with no error line at all, and clears only on the next WebSocket
                     reconnect.  Anything over ten minutes dark is flagged.

  zello ghost        A start immediately followed by a stop with no audio in between - self-
                     recovering, expected at a low single-digit percentage of starts.  A sharp rise
                     is a signal; a steady 1-4% is not.

  zello errors       Channel-busy (3008), denied, and connection failures, counted per feed.

  watchdog           Stuck-call watchdog trips.  ap-15.9.6 annotates these with the time since
                     audio last reached the resampler: 'STALE CALL TIMER' means the squelch closed
                     without the decoder being told, not that a carrier was actually stuck.

  tuner              Tuner removal / no-tuner-available / invalid-frequency events.  A tuner that
                     disappears takes every channel assigned to it dark, silently, until restart.

  p25 alg            Unknown P25 encryption algorithm IDs (ap-15.9.6 and later).  One ID repeating
                     on one talkgroup is a real encrypted system.  Scattered one-off IDs, or
                     several different IDs on one talkgroup, mean a marginal signal and a
                     mis-decoded header.

  ctcss              Confirmed tone detections grouped by channel, with any detection landing on a
                     tone other than the configured one.  The detector's 666-sample block gives
                     12 Hz bins at 8 kHz, so tone pairs closer than that - 127.3/131.8 above all -
                     can be confused.

Exit status is 1 if anything in the wedge, watchdog or tuner sections fired, so this can be run
from a scheduled task.
"""

import argparse
import collections
import os
import re
import sys

# ---------------------------------------------------------------------------------------------
# Line format: 20260924 081537.123 INFO  ... [1005MB/4096MB 24%] message
# ---------------------------------------------------------------------------------------------
STAMP = re.compile(r'^(\d{8}) (\d{2})(\d{2})(\d{2})\.(\d{3})')
HEAP = re.compile(r'\[(\d+)(MB|GB)/(\d+)(MB|GB) (\d+)%\]')

# Everything after the logger name is the message, and a channel or feed name is the first
# bracketed token inside it.  Splitting there matters: the thread name is ALSO bracketed, earlier
# on the line, and reading it instead gives you "HttpClient-3-Worker-22" where you wanted
# "Boston MA EMS".
HEAP_SUFFIX = re.compile(r'\s*\[\d+(?:MB|GB)/\d+(?:MB|GB) \d+%\]\s*$')
SUBJECT = re.compile(r'^\[((?:[^\[\]]|\[[^\]]*\])*)\]\s+(.*)$')

VERSION = re.compile(r'SDRTrunk Version\s*:?\s*(\S+)')
SHUTDOWN = re.compile(r'Application shutdown started')

ZELLO = re.compile(r'^Zello stream (started|stopped)')
ZELLO_SERVER_STOP = re.compile(r'Zello server stopped our stream')
ZELLO_BUSY = re.compile(r'\[3008\]|Zello 3008')
ZELLO_DENIED = re.compile(r'denied', re.IGNORECASE)
ZELLO_CONN = re.compile(r'(?:not connected|connect\w* fail\w*|websocket clos|reconnect)', re.IGNORECASE)

WATCHDOG = re.compile(r'^Stuck timer watchdog: (.*)')
STALE = re.compile(r'STALE CALL TIMER')
AUDIO_AGE = re.compile(r'audio last seen (\d+|never)s? ago')

TUNER_REMOVED = re.compile(r'(?:Tuner removed|tuner was removed|USB tuner.*(?:removed|disconnect))', re.IGNORECASE)
NO_TUNER = re.compile(r'No Tuner Available', re.IGNORECASE)
BAD_FREQ = re.compile(r'InvalidFrequencyException|frequency .* outside .* range', re.IGNORECASE)
TUNER_ID = re.compile(r'(SER#\w+|\b(?:RSP1B|RSP1A|RSPdx|RSPduo|RTL-?2832\S*)\b)')

P25_ALG = re.compile(r'unknown encryption algorithm ID (\d+) \(0x([0-9A-Fa-f]+)\).*?Talkgroup\(s\): (.*)$')

# CTCSS detector internals are the one thing here that DEBUG gates.  A stock INFO log carries the
# per-channel configuration line but not the per-detection line, so the tone-confusion section
# below simply stays quiet unless DEBUG was on for i.g.d.m.d.nbfm.CTCSSDetector.
CTCSS_DETECT = re.compile(r'^CTCSS tone ([\d.]+) Hz .*?confirm=(\d)/3 target=(YES|NO)')
CTCSS_CONFIG = re.compile(r'^CTCSS tone notch ENABLED at \[([^\]]*)\]')
DCS_CONFIG = re.compile(r'^DCS (?:tone notch|rumble filter) ENABLED')

# Standard CTCSS tone set, used to name whichever tone a detection actually landed on.
CHANNEL_FREQ_SUFFIX = re.compile(r'\s*\[[\d.]+\]\s*$')

CTCSS_TONES = [67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0,
               103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2,
               151.4, 156.7, 159.8, 162.2, 165.5, 167.9, 171.3, 173.8, 177.3, 179.9, 183.5,
               186.2, 189.9, 192.8, 196.6, 199.5, 203.5, 206.5, 210.7, 213.8, 218.1, 221.3,
               225.7, 229.1, 233.6, 237.1, 241.8, 246.0, 250.3, 254.1]

DETECTOR_BIN_HZ = 12.0   # 8000 / 666, the detector's Goertzel block


def seconds(m):
    """Absolute-ish seconds for ordering within one log, plus a printable label."""
    d, h, mi, s, ms = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
    t = (int(d[4:6]) * 31 + int(d[6:8])) * 86400 + int(h) * 3600 + int(mi) * 60 + int(s) + int(ms) / 1000
    return t, f"{d[4:6]}/{d[6:8]} {h}:{mi}:{s}"


def to_mb(value, unit):
    return int(value) * (1024 if unit == 'GB' else 1)


def nearest_tone(hz):
    return min(CTCSS_TONES, key=lambda t: abs(t - hz))


class Findings:
    def __init__(self, path):
        self.path = path
        self.versions = []
        self.shutdowns = []
        self.first = self.last = None
        self.heap_peak = 0
        self.heap_peak_at = ''
        self.heap_committed = 0
        self.zello = collections.defaultdict(list)     # feed -> [(t, label, event)]
        self.zello_errors = collections.Counter()
        self.server_stops = collections.Counter()
        self.watchdog = []
        self.tuner = []
        self.p25 = collections.defaultdict(collections.Counter)   # talkgroup -> {alg id: count}
        self.ctcss_config = {}
        self.ctcss_seen = collections.defaultdict(collections.Counter)
        self.dcs_channels = set()
        self.have_ctcss_debug = False
        self.lines = 0


def scan(path):
    f = Findings(path)
    for line in open(path, errors='replace'):
        m = STAMP.match(line)
        if not m:
            continue
        f.lines += 1
        t, label = seconds(m)
        if f.first is None:
            f.first = label
        f.last = label

        h = HEAP.search(line)
        if h:
            used = to_mb(h.group(1), h.group(2))
            if used > f.heap_peak:
                f.heap_peak, f.heap_peak_at = used, label
                f.heap_committed = to_mb(h.group(3), h.group(4))

        #Split at the first ' - ', which separates the logger name from the message.  Matching the
        #whole prefix with one pattern does not work: the thread name contains nested brackets
        #('[sdrtrunk channel [15/NBFM] 471012500 thread 1]') and defeats a simple bracket match.
        parts = line.rstrip('\n').split(' - ', 1)
        if len(parts) < 2:
            continue
        body = HEAP_SUFFIX.sub('', parts[1])
        sub = SUBJECT.match(body)
        subject, msg = (sub.group(1), sub.group(2)) if sub else ('', body)

        v = VERSION.search(msg)
        if v:
            f.versions.append((label, v.group(1)))
            continue
        if SHUTDOWN.search(msg):
            f.shutdowns.append(label)
            continue

        if 'Zello' in msg:
            z = ZELLO.match(msg)
            if z:
                f.zello[subject].append((t, label, z.group(1)))
            elif ZELLO_SERVER_STOP.search(msg):
                f.server_stops[subject] += 1
            elif ZELLO_BUSY.search(msg):
                f.zello_errors[(subject, 'channel busy (3008)')] += 1
            elif ZELLO_DENIED.search(msg):
                f.zello_errors[(subject, 'denied')] += 1
            elif ZELLO_CONN.search(msg):
                f.zello_errors[(subject, 'connection')] += 1
            continue

        w = WATCHDOG.match(msg)
        if w:
            age = AUDIO_AGE.search(w.group(1))
            f.watchdog.append((label, subject, bool(STALE.search(w.group(1))),
                               age.group(1) if age else '?'))
            continue

        if TUNER_REMOVED.search(msg) or NO_TUNER.search(msg) or BAD_FREQ.search(msg):
            kind = ('removed' if TUNER_REMOVED.search(msg)
                    else 'no tuner available' if NO_TUNER.search(msg) else 'invalid frequency')
            ident = TUNER_ID.search(line)
            f.tuner.append((label, kind, ident.group(1) if ident else subject[:28]))
            continue

        p = P25_ALG.search(msg)
        if p:
            f.p25[p.group(3).strip()[:60]][f"{p.group(1)} (0x{p.group(2).upper()})"] += 1
            continue

        cc = CTCSS_CONFIG.match(msg)
        if cc:
            f.ctcss_config[subject] = [float(x) for x in re.findall(r'[\d.]+', cc.group(1))]
            continue
        if DCS_CONFIG.match(msg):
            f.dcs_channels.add(subject)
            continue
        cd = CTCSS_DETECT.match(msg)
        if cd:
            #The detector labels itself "Medford Fire [482.1375]" while the configuration line says
            #just "Medford Fire".  Strip the trailing frequency so the two key together.
            subject = CHANNEL_FREQ_SUFFIX.sub('', subject)
            f.have_ctcss_debug = True
            if cd.group(2) == '3':
                #target=NO is the detector's own verdict that this is not a configured tone for the
                #channel, which is more reliable than comparing against the configuration line.
                f.ctcss_seen[subject][(nearest_tone(float(cd.group(1))), cd.group(3) == 'YES')] += 1

    return f


def report(f, verbose):
    hit = False
    print(f"\n{'=' * 96}\n{os.path.basename(f.path)}   {f.first} -> {f.last}   ({f.lines:,} stamped lines)\n{'=' * 96}")

    if f.versions:
        for label, v in f.versions:
            print(f"  version   {v}   started {label}")
    else:
        print("  version   not in this excerpt - the startup banner is above the window")
    if f.shutdowns:
        print(f"  shutdown  {', '.join(f.shutdowns)}")

    if f.heap_peak:
        pct = 100.0 * f.heap_peak / f.heap_committed if f.heap_committed else 0
        note = '' if f.heap_peak < 2048 else '   <<< well above the ~1GB fleet norm'
        print(f"  heap      peak {f.heap_peak} MB of {f.heap_committed} MB committed ({pct:.0f}%) at {f.heap_peak_at}{note}")

    # --- Zello -------------------------------------------------------------------------------
    end = None
    for evs in f.zello.values():
        for t, _, _ in evs:
            end = t if end is None else max(end, t)

    wedges, ghosts, starts_total = [], 0, 0
    for feed, evs in f.zello.items():
        evs.sort()
        starts = [i for i, e in enumerate(evs) if e[2] == 'started']
        starts_total += len(starts)
        for i in starts:
            nxt = evs[i + 1] if i + 1 < len(evs) else None
            if nxt is None or nxt[2] == 'started':
                dark = (nxt[0] if nxt else end) - evs[i][0]
                if dark > 600:
                    wedges.append((feed, evs[i][1], dark, nxt is None))
            elif nxt[0] - evs[i][0] < 0.5:
                ghosts += 1

    if starts_total:
        pct = 100.0 * ghosts / starts_total
        flag = '   <<< high, investigate' if pct > 8 else ''
        print(f"  zello     {starts_total:,} stream starts across {len(f.zello)} feeds; "
              f"{ghosts} ghost starts ({pct:.1f}%){flag}")
    if wedges:
        hit = True
        print(f"  WEDGE     {len(wedges)} orphaned stream(s) - feed dark with no error line:")
        for feed, label, dark, tail in sorted(wedges, key=lambda x: -x[2]):
            tailnote = ' (to end of log)' if tail else ''
            print(f"              {feed:28s} started {label}  dark {dark / 3600:5.1f} h{tailnote}")
    elif starts_total:
        print("  wedge     none")

    if f.server_stops:
        print("  zello     server-initiated stops: " +
              ", ".join(f"{k} x{v}" for k, v in f.server_stops.most_common(6)))
    if f.zello_errors:
        print("  zello     errors:")
        for (feed, kind), n in f.zello_errors.most_common(10 if not verbose else 200):
            print(f"              {feed:28s} {kind:22s} x{n}")

    # --- watchdog ----------------------------------------------------------------------------
    if f.watchdog:
        hit = True
        stale = [w for w in f.watchdog if w[2]]
        print(f"  WATCHDOG  {len(f.watchdog)} stuck-call trip(s), {len(stale)} flagged as a stale timer:")
        for label, ch, is_stale, age in f.watchdog[:40 if not verbose else 10000]:
            tag = 'STALE TIMER' if is_stale else 'carrier?'
            print(f"              {label}  {ch:28s} audio last seen {age}s ago   {tag}")
        if len(f.watchdog) > 40 and not verbose:
            print(f"              ... {len(f.watchdog) - 40} more (--verbose for all)")
        if stale:
            print("              A stale timer means the squelch closed without the decoder being told.")
            print("              Fixed in ap-15.9.6; trips on a later build mean the fix is incomplete.")
    else:
        print("  watchdog  no trips")

    # --- tuner -------------------------------------------------------------------------------
    if f.tuner:
        hit = True
        kinds = collections.Counter(k for _, k, _ in f.tuner)
        print("  TUNER     " + ", ".join(f"{k} x{v}" for k, v in kinds.most_common()))
        for label, kind, ident in f.tuner[:25 if not verbose else 10000]:
            print(f"              {label}  {kind:22s} {ident}")
        if len(f.tuner) > 25 and not verbose:
            print(f"              ... {len(f.tuner) - 25} more (--verbose for all)")
    else:
        print("  tuner     clean")

    # --- P25 ---------------------------------------------------------------------------------
    if f.p25:
        print("  p25 alg   unknown encryption algorithm IDs (audio muted for these calls):")
        for tg, ids in sorted(f.p25.items()):
            note = ''
            if len(ids) > 1:
                note = '   <<< several IDs on one talkgroup - marginal signal, not encryption'
            elif sum(ids.values()) > 5:
                note = '   (repeating - looks like a genuinely encrypted talkgroup)'
            print(f"              {tg:42s} {', '.join(f'{k} x{v}' for k, v in ids.most_common())}{note}")
    else:
        print("  p25 alg   none (or this build predates the warning)")

    # --- CTCSS -------------------------------------------------------------------------------
    if not f.have_ctcss_debug:
        print("  ctcss     skipped - per-detection lines are DEBUG only.  Everything else above is")
        print("            INFO/WARN and needs no logging change.  To audit tone confusion, raise")
        print("            i.g.d.m.d.nbfm.CTCSSDetector to DEBUG for a day, then re-run.")
    elif f.ctcss_seen:
        print("  ctcss     confirmed tone detections by channel.  The detector's 666-sample Goertzel block")
        print(f"            gives {DETECTOR_BIN_HZ:.0f} Hz bins at 8 kHz, so a tone pair closer than that - 127.3 and")
        print("            131.8 above all - can be confused.  The number that matters is the ADJACENT share:")
        print("            off-target confirmations landing within one bin of the channel's own tone, which is")
        print("            the detector mistaking the configured tone rather than hearing a real second one.")
        rows = []
        for ch, counts in f.ctcss_seen.items():
            configured = f.ctcss_config.get(ch, [])
            total = sum(counts.values())
            off = {tone: n for (tone, on_target), n in counts.items() if not on_target}
            adjacent = {tone: n for tone, n in off.items()
                        if any(0 < abs(tone - c) < DETECTOR_BIN_HZ for c in configured)}
            rows.append((ch, configured, total, off, adjacent))

        rows.sort(key=lambda r: -(sum(r[4].values()) / r[2] if r[2] else 0))
        for ch, configured, total, off, adjacent in rows:
            adj_pct = 100.0 * sum(adjacent.values()) / total if total else 0
            if not adjacent and not verbose:
                continue
            cfg = ', '.join(f"{c:.1f}" for c in configured) or '(not in this log)'
            flag = '   <<<' if adj_pct >= 10 else ''
            print(f"              {ch:28s} tone {cfg:>22s}  {total:6,} confirmed  "
                  f"{adj_pct:5.1f}% adjacent{flag}")
            for tone, n in sorted(off.items(), key=lambda x: -x[1])[:4]:
                tag = '  <- within one bin' if tone in adjacent else ''
                print(f"                  {tone:6.1f} Hz  x{n}{tag}")
        if not any(r[4] for r in rows):
            print("              no adjacent-tone confusion on any channel")

    if f.dcs_channels:
        print(f"  dcs       {len(f.dcs_channels)} channel(s) on DCS: "
              f"{', '.join(sorted(f.dcs_channels)[:6])}"
              f"{' ...' if len(f.dcs_channels) > 6 else ''}")

    return hit


def collect(paths):
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in os.walk(p):
                out += [os.path.join(root, n) for n in sorted(names) if n.endswith('.log')]
        else:
            out.append(p)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('paths', nargs='+', help='log files, or directories to search for *.log')
    ap.add_argument('--verbose', action='store_true',
                    help='print every occurrence rather than the first few, and every CTCSS channel')
    args = ap.parse_args()

    files = collect(args.paths)
    if not files:
        print("no log files found", file=sys.stderr)
        return 2

    anything = False
    for path in files:
        try:
            anything |= report(scan(path), args.verbose)
        except OSError as e:
            print(f"\n{path}: {e}", file=sys.stderr)

    print("\n" + "=" * 96)
    print("Nothing here needs DEBUG logging - every marker above is INFO or WARN in a stock build.")
    print("=" * 96)
    return 1 if anything else 0


if __name__ == '__main__':
    sys.exit(main())
