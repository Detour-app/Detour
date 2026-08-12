#!/usr/bin/env python3
"""Profile a traces.jsonl or a GPX export: distance, duration, speed range, and stops.

Why this exists: every naive reading of this data is wrong in the same way. The stored trace
is decimated to 25 m (TripTrackingService.addTracePoint: `if (gap < 25.0) return`), so

  * a standstill produces NO points at all — while the vehicle creeps under 25 m nothing is
    written, so a ten-minute wait is one segment whose two endpoints are ten minutes apart;
  * `speedKmh` on a kept point is the speed AFTER the gap, sampled at the moment the vehicle
    was moving enough to trigger a write, so averaging it reads high;
  * consecutive points are not evenly spaced in time, so any per-point statistic is wrong.

Therefore stops are detected here by a LARGE TIME DELTA ACROSS A SHORT DISTANCE, never by
looking for low-speed samples. The script also runs the naive test alongside and prints its
result, because seeing it return ~0 is the fastest way to believe the contract.

Thresholds and their justification (both overridable — they are heuristics, not constants in
the codebase): a decimated hop is at least 25 m, and 25 m at 8 km/h (2.2 m/s) takes ~11 s,
which is just above the app's own 2.0 m/s moving gate. So an inter-point interval longer than
12 s whose implied speed is under 8 km/h contained a standstill. A hop over 500 m is not a
stop at all — that is the `gap > 500.0` segment break, i.e. the tracker losing the plot, and
it is reported separately as an outage.

These thresholds assume the 25 m decimation. They do not apply to a raw fix log from another
recorder; check the decimation with check-preconditions.sh first.

Reads files. Writes nothing. Prints statistics and never coordinates — a trace is where the
user actually went, and its endpoints are the home and work addresses.
"""

import argparse
import datetime
import json
import math
import re
import sys

TRKSEG_RE = re.compile(r"<trkseg>(.*?)</trkseg>", re.S)
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


def load_jsonl(path):
    """Yield (label, points) where a point is (lat, lon, timeMs, speedKmh|None).

    One line = one flushed segment, NOT one trip: flushTrace fires every 200 points, on a
    >500 m jump, on a STILL transition, at trip end and on service destroy. A trace line is
    also not evidence of a trip at all — onIdleLocation writes points too.
    """
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as exc:
                print(f"{path}:{n}: unparseable line ({exc})", file=sys.stderr)
                continue
            pts = []
            for p in raw:
                if len(p) < 2:
                    continue
                # A point written before the tail existed is two elements long and reads back
                # as timeMs == -1: unknown, not epoch. Filter it out of any timing analysis.
                t = p[2] if len(p) > 2 else -1
                s = p[3] if len(p) > 3 else None
                pts.append((float(p[0]), float(p[1]), int(t), s))
            yield f"segment {n}", pts


def load_gpx(path):
    """Yield (label, points) per <trkseg>. GPX carries no speed field at all."""
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    segs = TRKSEG_RE.findall(text) or [text]
    for n, seg in enumerate(segs, 1):
        pts = []
        for m in TRKPT_RE.finditer(seg):
            attrs = dict(ATTR_RE.findall(m.group(1) or m.group(3) or ""))
            if "lat" not in attrs or "lon" not in attrs:
                continue
            tm = TIME_RE.search(m.group(2) or "")
            ms = -1
            if tm:
                try:
                    stamp = datetime.datetime.fromisoformat(
                        tm.group(1).strip().replace("Z", "+00:00"))
                    ms = int(stamp.timestamp() * 1000)
                except ValueError:
                    pass
            pts.append((float(attrs["lat"]), float(attrs["lon"]), ms, None))
        yield f"trkseg {n}", pts


def profile(label, pts, args):
    if len(pts) < 2:
        print(f"  {label}: {len(pts)} point(s) — too short to profile")
        return

    coords = [(p[0], p[1]) for p in pts]
    dist = sum(haversine(coords[i], coords[i + 1]) for i in range(len(coords) - 1))
    timed = [p[2] for p in pts if p[2] > 0]
    span = (max(timed) - min(timed)) / 1000.0 if len(timed) > 1 else 0.0
    untimed = sum(1 for p in pts if p[2] <= 0)

    # Speed derived from each hop's own distance / its own time, exactly as
    # TripDetailScreen.sampleReplay does — never averaged from the stored speedKmh field.
    hops, stops, outages = [], [], []
    for i in range(len(pts) - 1):
        d = haversine(coords[i], coords[i + 1])
        dt = (pts[i + 1][2] - pts[i][2]) / 1000.0 if pts[i][2] > 0 and pts[i + 1][2] > 0 else 0.0
        if dt <= 0:
            continue
        kmh = d / dt * 3.6
        hops.append(kmh)
        if d > 500.0:
            outages.append((i, dt, d))
        elif dt >= args.min_seconds and kmh < args.max_kmh:
            stops.append((i, dt, d, kmh))

    gap_m = [haversine(coords[i], coords[i + 1]) for i in range(len(coords) - 1)]
    med_gap = sorted(gap_m)[len(gap_m) // 2]
    slow = sum(1 for s in hops if s < 30.0)
    fast = sum(1 for s in hops if s > 90.0)
    start = (datetime.datetime.fromtimestamp(min(timed) / 1000).strftime("%Y-%m-%d %H:%M")
             if timed else "no timestamps")

    print(f"  {label}: {len(pts):4d} pts  {dist / 1000:6.2f} km  {span:6.0f} s  {start}")
    if hops:
        print(f"      derived speed {min(hops):.0f}-{max(hops):.0f} km/h "
              f"(median hop {med_gap:.0f} m; {100 * slow // len(hops)}% of hops < 30 km/h, "
              f"{100 * fast // len(hops)}% > 90 km/h)")
    if untimed:
        print(f"      {untimed} point(s) with timeMs <= 0 — pre-tail, excluded from timing")

    # The naive test, run only to show that it finds nothing. Stored speedKmh is sampled at
    # the far end of each hop, i.e. at a moment the vehicle was demonstrably moving.
    stored = [p[3] for p in pts if p[3] is not None]
    if stored:
        low = sum(1 for s in stored if s < 2.0)
        print(f"      naive low-speed test: {low} of {len(stored)} stored speedKmh samples "
              f"below 2 km/h (expect ~0 even for a trace full of stops)")

    if stops:
        total_stopped = sum(s[1] for s in stops)
        print(f"      {len(stops)} stop(s), {total_stopped:.0f} s standing still:")
        for i, dt, d, kmh in stops:
            along = 100 * sum(gap_m[:i]) / dist if dist else 0
            print(f"        point {i:4d} ({along:3.0f}% along): {dt:5.0f} s over "
                  f"{d:5.0f} m = {kmh:.1f} km/h implied")
    else:
        print("      no stops by the time-delta test")
    for i, dt, d in outages:
        print(f"      OUTAGE at point {i}: {d / 1000:.1f} km in {dt:.0f} s — this is the "
              f"gap > 500 m segment break, not a stop")


def main():
    ap = argparse.ArgumentParser(
        description="Profile Detour traces.jsonl / GPX exports: distance, duration, "
                    "speed range and stops detected by time-delta across short distance.")
    ap.add_argument("files", nargs="+", help=".jsonl or .gpx files")
    ap.add_argument("--min-seconds", type=float, default=12.0,
                    help="an inter-point interval must last at least this long to count as "
                         "a stop (default 12; 25 m at 8 km/h takes ~11 s)")
    ap.add_argument("--max-kmh", type=float, default=8.0,
                    help="implied speed below which such an interval was a standstill "
                         "(default 8, just above the app's 2.0 m/s moving gate)")
    args = ap.parse_args()

    if args.min_seconds < 0 or args.max_kmh <= 0:
        ap.error("--min-seconds must be >= 0 and --max-kmh > 0")

    seen = 0
    for path in args.files:
        try:
            loader = load_gpx if path.lower().endswith(".gpx") else load_jsonl
            print(path)
            for label, pts in loader(path):
                profile(label, pts, args)
                seen += 1
        except OSError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1
    if seen == 0:
        print("no segments found — is this a traces.jsonl or a GPX file?", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
