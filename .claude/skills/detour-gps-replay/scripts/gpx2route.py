#!/usr/bin/env python3
"""Convert a GPX track into MockService's route format, reconstructing standstills.

Why this exists rather than a one-liner: MockService reads one "lon lat" pair per line and
emits one fix per intervalMs, deriving speed from the gap to the *next* line. So the route
file is a timeline, not a shape, and the source GPX's own spacing is an artefact of whatever
recorder produced it. Feeding raw trackpoints in at a fixed 1 Hz reports whatever speed the
spacing implies — a 3-second gap replayed as 1 second reports triple speed. This resamples
against the track's own timestamps so the replay reports the speed actually driven.

The non-obvious half is the standstills. Detour's stored trace is decimated to 25 m
(TripTrackingService.addTracePoint: `if (gap < 25.0) return`), so a wait at a traffic light
is not a run of identical points — while the vehicle creeps under 25 m nothing is stored at
all, and the stop survives only as one segment tens of seconds long covering barely more than
25 m. Interpolating that segment linearly replays a stop as a steady crawl, which fails in
two opposite directions: a crawl in the 2.0-2.5 m/s band never lets `lastMovingMs` go stale
so the trip never auto-ends, while dragging the average pace toward WALK_AVG_MAX_MPS and
possibly retagging a drive as a walk. So a segment that looks like a stop is replayed as a
held position — MockService then computes a zero-distance gap and reports speed 0, which is
what a real stopped vehicle reports.

Detection heuristic, and why the numbers are what they are: a decimated segment is at least
25 m long, and 25 m at 8 km/h (2.2 m/s) takes ~11 s, which is just above the app's own 2.0
m/s moving gate. So a segment whose implied average speed is under 8 km/h across more than
12 s contained a stop. Neither number is a constant in the codebase; both are tunable here.
They do not apply to a track that was NOT decimated at 25 m (a raw fix log from another
recorder) — pass --stop-span 0 to disable the reconstruction entirely in that case.

The excess time is attributed to a held position and the remaining distance is covered at a
plausible pull-away speed (--pull-away-kmh), rather than teleporting the whole 25 m into the
final interval: a 25 m jump in one 1000 ms interval would report 90 km/h on that fix and can
by itself trip the auto-start speed gate.

Reads a GPX, writes a route file. Touches no device and no repo file.
"""

import argparse
import bisect
import datetime
import math
import re
import sys

TRKPT_RE = re.compile(r"<trkpt\b([^>]*)>(.*?)</trkpt>|<trkpt\b([^>]*)/>", re.S)
ATTR_RE = re.compile(r'(lat|lon)\s*=\s*"([^"]+)"')
TIME_RE = re.compile(r"<time>([^<]+)</time>")


def haversine(a, b):
    """Metres between two (lat, lon) pairs."""
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(x))


def parse_time(text):
    try:
        return datetime.datetime.fromisoformat(text.strip().replace("Z", "+00:00"))
    except ValueError:
        return None


def load_track(path):
    """Return [(lat, lon, seconds_from_start)] for trackpoints that carry a time.

    Parsed per <trkpt> rather than by collecting all lat/lon and all <time> separately:
    Detour writes <time> only when timeMs > 0 (Gpx.kt), and the document also carries a
    <metadata><time> of its own, so two independent lists do not line up.
    """
    with open(path, encoding="utf-8") as fh:
        text = fh.read()

    pts, untimed = [], 0
    for m in TRKPT_RE.finditer(text):
        attrs = dict(ATTR_RE.findall(m.group(1) or m.group(3) or ""))
        if "lat" not in attrs or "lon" not in attrs:
            continue
        body = m.group(2) or ""
        tm = TIME_RE.search(body)
        stamp = parse_time(tm.group(1)) if tm else None
        if stamp is None:
            untimed += 1
            continue
        pts.append((float(attrs["lat"]), float(attrs["lon"]), stamp))

    if len(pts) < 2:
        sys.exit(f"{path}: need at least 2 timestamped trackpoints, found {len(pts)}")
    if untimed:
        print(f"note: skipped {untimed} trackpoint(s) with no <time> — a pre-tail point "
              f"has timeMs == -1 and cannot be placed on a timeline", file=sys.stderr)

    t0 = pts[0][2]
    return [(lat, lon, (t - t0).total_seconds()) for lat, lon, t in pts]


def trim_ends(track, metres):
    """Drop `metres` from each end. A trace's endpoints are the identifying addresses."""
    cum = [0.0]
    for i in range(1, len(track)):
        cum.append(cum[-1] + haversine(track[i - 1][:2], track[i][:2]))
    lo = bisect.bisect_left(cum, metres)
    hi = bisect.bisect_left(cum, cum[-1] - metres)
    if hi - lo < 2:
        sys.exit(f"trim of {metres:.0f} m leaves under 2 points; reduce --trim")
    t0 = track[lo][2]
    return [(lat, lon, t - t0) for lat, lon, t in track[lo:hi]]


def main():
    ap = argparse.ArgumentParser(
        description="GPX -> MockService route file, with standstills reconstructed.")
    ap.add_argument("gpx")
    ap.add_argument("out", help="route file to write (one 'lon lat' pair per line)")
    ap.add_argument("--interval-ms", type=int, default=1000,
                    help="replay interval; must match the --ei intervalMs you start the "
                         "service with, or every reported speed is wrong (default 1000)")
    ap.add_argument("--stop-span", type=float, default=12.0, metavar="S",
                    help="a segment longer than this many seconds may be a stop; "
                         "0 disables the reconstruction (default 12)")
    ap.add_argument("--stop-kmh", type=float, default=8.0,
                    help="implied speed below which such a segment was a standstill "
                         "(default 8, i.e. just above the app's 2.0 m/s moving gate)")
    ap.add_argument("--pull-away-kmh", type=float, default=20.0,
                    help="speed at which the held position rejoins the next point "
                         "(default 20)")
    ap.add_argument("--trim", type=float, default=0.0, metavar="M",
                    help="drop M metres from each end, so a route can be kept without "
                         "publishing where the drive started and finished")
    args = ap.parse_args()

    if args.interval_ms <= 0:
        ap.error("--interval-ms must be positive")
    if args.pull_away_kmh <= 0:
        ap.error("--pull-away-kmh must be positive")

    track = load_track(args.gpx)
    if args.trim > 0:
        track = trim_ends(track, args.trim)

    secs = [p[2] for p in track]
    pull_mps = args.pull_away_kmh / 3.6

    # Per source segment: (distance, duration, hold_seconds). hold_seconds > 0 marks a
    # reconstructed standstill: position is held for that long, then covers the segment's
    # distance at the pull-away speed.
    segs = []
    for i in range(len(track) - 1):
        dist = haversine(track[i][:2], track[i + 1][:2])
        span = secs[i + 1] - secs[i]
        implied_kmh = (dist / span * 3.6) if span > 0 else 0.0
        hold = 0.0
        if args.stop_span > 0 and span >= args.stop_span and implied_kmh < args.stop_kmh:
            hold = max(0.0, span - dist / pull_mps)
        segs.append((dist, span, hold))

    step = args.interval_ms / 1000.0
    out = []
    n_lines = int(secs[-1] / step) + 1
    for k in range(n_lines):
        t = k * step
        j = min(max(bisect.bisect_right(secs, t) - 1, 0), len(track) - 2)
        dist, span, hold = segs[j]
        local = t - secs[j]
        if span <= 0:
            f = 0.0
        elif hold > 0:
            moving = max(span - hold, 1e-9)
            f = 0.0 if local <= hold else min(1.0, (local - hold) / moving)
        else:
            f = min(1.0, local / span)
        lat = track[j][0] + (track[j + 1][0] - track[j][0]) * f
        lon = track[j][1] + (track[j + 1][1] - track[j][1]) * f
        out.append((lat, lon))

    with open(args.out, "w", encoding="utf-8") as fh:
        for lat, lon in out:
            fh.write("%.6f %.6f\n" % (lon, lat))  # longitude FIRST — MockService.readRoute

    # Report the replay as MockService will see it: speed is the gap to the next line.
    gaps = [haversine(out[i], out[i + 1]) for i in range(len(out) - 1)]
    speeds = [g / step for g in gaps]  # m/s, exactly what MockService computes
    total = sum(gaps)
    held = sum(1 for s in speeds if s < 0.1)
    stops = [(s[1], s[2]) for s in segs if s[2] > 0]

    print(f"{len(track)} timestamped trackpoints -> {len(out)} lines "
          f"@ {args.interval_ms} ms = {len(out) * step:.0f} s of replay")
    print(f"{total / 1000:.1f} km, mean {total / max(len(out) * step, 1) * 3.6:.0f} km/h, "
          f"max {max(speeds, default=0) * 3.6:.0f} km/h, {held} held fixes (speed 0)")
    if stops:
        print(f"{len(stops)} standstill(s) reconstructed: " +
              ", ".join(f"{span:.0f}s over {hold:.0f}s held" for span, hold in stops))
    else:
        print("no standstills detected — if the drive had traffic lights, check --stop-span")

    # The auto-start gate a replay has to clear: 3 fixes at >= 7.0 m/s, sustained >= 8 s and
    # >= 120 m (TripTrackingService FAST_SPEED_MPS). Report the best run so a route that
    # cannot possibly auto-start is caught here rather than after a 20-minute replay.
    best_s = best_m = run_s = run_m = 0.0
    for g, sp in zip(gaps, speeds):
        if sp >= 7.0:
            run_s += step
            run_m += g
            best_s, best_m = max(best_s, run_s), max(best_m, run_m)
        else:
            run_s = run_m = 0.0
    verdict = "clears" if (best_s >= 8.0 and best_m >= 120.0) else "DOES NOT clear"
    print(f"longest run at >= 7.0 m/s: {best_s:.0f} s / {best_m:.0f} m — {verdict} "
          f"the auto-start gate (needs >= 8 s and >= 120 m)")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
