# Canonical replay routes

Drives that between them exercise the GPS-driven behaviour of the map screen,
the tracking service and the Android Auto surface. They exist so a change can be
A/B'd against a recorded baseline in [`../baseline/`](../baseline/) instead of
against someone's memory of a drive.

There are two families, and the filename says which:

- **No prefix** — `trajectcontrole.txt`, `urban-limits.txt`, `stop-start.txt`.
  The maintainer's own recorded drives. These are the ones with baselines in
  `../baseline/` today.
- **`public-` prefix** — derived from OpenStreetMap: public GPS traces, or an
  OSRM route over OSM geometry. Provenance and licence per file in
  [`ATTRIBUTION.md`](ATTRIBUTION.md) — **read it before redistributing any of
  them**, they are ODbL and the unprefixed three are not. Added 2026-08-13 to
  prove the personal drives can be retired; see "Coverage: public vs personal".

The unprefixed three are derived from **real recorded drives**, exported from
the app as GPX and converted with
[`.claude/skills/detour-gps-replay/scripts/gpx2route.py`](../../../.claude/skills/detour-gps-replay/scripts/gpx2route.py).
That script, and the `start-replay.sh` / `stop-replay.sh` helpers beside it, are
the only supported way to build and run these — see
[`.claude/skills/detour-gps-replay/SKILL.md`](../../../.claude/skills/detour-gps-replay/SKILL.md)
for the designated-mock-app setup, the `run-as` route push, and why the release
variant must be force-stopped first. Commands are deliberately **not** duplicated
here; a second copy is a second thing to go stale.

> The stage 0 plan proposed a `gpx-to-route.sh` alongside these files. It was
> never written and should not be: a shell `grep`/`sed` over `<trkpt>` emits the
> source track's own uneven spacing, which `MockService` then reports as speed
> (see "Why they are resampled"). `gpx2route.py` resamples against the track's
> timestamps and reconstructs standstills, which a one-liner cannot.

## Format

One `lon lat` pair per line — **longitude first** — and **one line per replay
interval**, 1000 ms by default. The file is a timeline, not just a shape:
`MockService` derives both speed and bearing from the gap to the next line, so
line count equals seconds of replay and spacing ÷ interval *is* the speed. There
is no speed column and no timestamp column.

Standstills are therefore reconstructed as **runs of identical points**, which
`MockService` reports as speed 0. See "Why they are resampled" below — this is
the part that is easy to get wrong, and getting it wrong invalidates a whole
comparison silently.

## The three personal routes

Measured from the files themselves, 2026-08-12:

| File | Length | Standstills | What it exercises |
|---|---|---|---|
| `trajectcontrole.txt` | 1466 s (24m26s), 27.28 km, mean 67 km/h, max 134 km/h | one, 8 s (fixes 1124–1131) | **One average-speed section driven gantry to gantry, in the direction the measurement runs.** Section entry gating, the running average, its settled value, exit detection at the far gantry. |
| `urban-limits.txt` | 1442 s (24m02s), 23.49 km, mean 59 km/h, max 129 km/h | one, 19 s (fixes 1075–1093) | Posted-limit changes between motorway and urban roads, cross streets. Ambient limit snapping, the three-miss clear hysteresis, prefetch refresh at the edge of the held set. |
| `stop-start.txt` | 762 s (12m42s), 9.70 km, mean 46 km/h, max 132 km/h | **four: 12 s, 42 s, 15 s, 11 s** (fixes 359–370, 395–436, 672–686, 696–706) | Camera park and resume, speed-HUD easing back to zero and returning, bearing hold below 2 m/s. The four stops are spread mid-drive, not bunched at one end. |

All three clear the auto-start gate comfortably (3 fixes ≥ 7.0 m/s sustained
≥ 8 s and ≥ 120 m — `TripTrackingService.kt`): the longest continuous run above
7.0 m/s is 474 s / 11.3 km on `trajectcontrole`, 577 s / 14.3 km on
`urban-limits`, 180 s / 4.5 km on `stop-start`.

## The `public-` routes

Built and measured 2026-08-13 from the sources in [`ATTRIBUTION.md`](ATTRIBUTION.md).
Every figure below was measured from the committed file, not carried over from the
research note that proposed them.

| File | Length | Standstills | Verified assertion |
|---|---|---|---|
| `public-trajectcontrole.txt` | 288 s (4m48s), 7.97 km, mean 99.7 km/h, max 100.3 km/h | none — synthetic | **Full transit of relation `15682532`, west → east.** West device node (50.86929, 4.49257) **0 m** away at line 0; east node (50.86183, 4.60503) **13 m** away at line 287. Both inside `SECTION_GATE_METERS` (60 m), in the measured order. Transit **7.97 km in 287 s, mean 100.0 km/h** — that is the value a correct section average must settle at, and it is an *input*, not a measurement (see below). |
| `public-stop-start.txt` | 3005 s (50m05s), 18.45 km, mean 22.1 km/h, max 193 km/h | **20 runs of ≥ 8 s below 0.6 m/s, totalling 1288 s** — longest 439 s, then 134, 112, 103, 97, 90, 60, 46, 30, 30 s. Of those, **548 fixes report speed exactly 0** and 127 runs are byte-identical consecutive lines (longest 8 s). | Real standstills survive resampling: 15 runs of ≥ 8 s sit below the app's own 2.0 m/s moving gate. Camera park and resume, HUD easing to zero, bearing hold below 2 m/s. Clears auto-start with 353 s / 10.4 km above 7.0 m/s. Also intended to cover posted-limit variety — 8 distinct `maxspeed` values in its footprint (5/10/15/20/30/50/70/120), **carried from the research note and not re-verified here**, because Overpass 504'd. |
| `public-trajectcontrole-reverse.txt` | 600 s (10m00s), 19.40 km, mean 116.4 km/h, max 135 km/h | none | **Transits relation `15682532`'s two device nodes east → west, against the measured direction.** East node **19 m** at line 326, west node **23 m** at line 567; transit 8.00 km in 241 s = **119.5 km/h**. Read the caveat below — this fixture documents behaviour, it does not assert a refusal. |

### What `public-trajectcontrole.txt` does and does not prove

Its whole point is that the expected answer is an input. It routes *from one
device node to the other*, so the two gates are the endpoints by construction,
and it is densified at a fixed 27.78 m per 1000 ms line, so a correct running
average has one value it can settle at: **100.0 km/h**. 100 km/h was chosen
because it is a plausible motorway cruise below the 120 posted on both device
nodes; regenerating at 120 km/h instead is a one-number change and yields 240
lines with the east node 19 m away, if an over-the-limit transit is ever wanted.

Three limits, none of which the number hides:

- **It has no GPS noise, no real standstills and perfectly regular sampling.**
  Every line is exactly 27.78 m from the last. It exercises the geometry and the
  gate logic; it does not exercise the messiness. A bug that only appears when
  consecutive fixes disagree cannot show up here.
- **`Section.maxspeedKmh` is null for this relation**, so no fixture of it can
  test the over/under-limit comparison. `SpeedCameras.parseSection` reads
  `maxspeed` from the *relation's* tags and relation `15682532` carries none —
  the `maxspeed=120` is on the two device nodes, which the app does not read.
  This is equally true of the existing `trajectcontrole.txt`.
- **The west gantry is line 0, and the section prefetch may not have arrived by
  then.** `speedSections` is populated by an Overpass round trip fired on the
  first fix (`MapScreen.kt`, the `SpeedCameras.near` effect), and at 100 km/h the
  60 m gate is 2.2 s wide. If the fetch is slower than that — it usually is — the
  fixture drives past its own entry gate with an empty section list and no
  measurement arms. Nothing is wrong with the geometry; the race is
  environmental. Give it a lead-in when this matters: re-run the §7.1 recipe with
  a third waypoint a few km west of the west node, which pushes the gate several
  minutes into the file. `public-trajectcontrole-reverse.txt` does not have this
  problem — its first gate is at line 326.

### `public-trajectcontrole-reverse.txt` documents a limitation, it does not assert a refusal

It was built to be the negative case the suite lacks — a real section transited
against the direction the measurement runs, which the tracker should decline to
start on. Measured against the code, **it does not decline.** `sectionExitGate`
arms whenever the fix is within 60 m of one end and the *other* end lies inside
`SECTION_WEDGE_DEG` (75°) of the heading, and at this file's entry gate the far
end bears 276° against a heading of 284° — **8° off, deep inside the wedge**.
Confirmed by one Overpass query on 2026-08-13: the reverse carriageway is not
separately tagged (the only other relation at that location, `15685856`
"Bertem-Leuven", continues *further east*), so there is no direction-specific
relation for the app to prefer and the wedge test cannot distinguish carriageways
that are metres apart.

So the file is still worth keeping, as the thing it actually is: a pinned record
that a reverse transit currently starts a measurement, and the fixture that would
prove a future direction test works. Do not describe it as a passing negative
case.

### Coverage: public vs personal

The four scenarios from
[`../../../docs/refactor/mapscreen/17-public-trace-datasets.md`](../../../docs/refactor/mapscreen/17-public-trace-datasets.md):

| Scenario | Personal fixture | Public fixture | Status |
|---|---|---|---|
| 1. Average-speed section, gantry to gantry | `trajectcontrole.txt` (75.4 km/h transit) | `public-trajectcontrole.txt` (100.0 km/h transit) | **Covered by public data**, and better: the expected average is asserted rather than derived, and the file is regenerable when OSM moves a gantry. Subject to the prefetch-race caveat above. |
| 2. Urban stop-start | `stop-start.txt` (4 stops: 12/42/15/11 s) | `public-stop-start.txt` (20 stops, longest 439 s) | **Covered, but not a drop-in swap** — see the two behavioural differences below. |
| 3. Posted-limit variety | `urban-limits.txt` | `public-stop-start.txt` (8 distinct `maxspeed` values in its footprint) | **Probably covered, unconfirmed.** One trace covers scenarios 2 and 3 together, as one drive should — but the 8 `maxspeed` values come from the research note and could not be re-measured (Overpass 504). Keep `urban-limits.txt` until that query runs. |
| 4. Deviation, for reroute | does not exist | does not exist | **Blocked on infrastructure, not on data** — see below. No dataset fixes it. |
| Bonus: wrong-direction transit | none | `public-trajectcontrole-reverse.txt` | New coverage, but as a documented limitation rather than an assertion. |

**Two ways `public-stop-start.txt` is not a drop-in replacement for
`stop-start.txt`**, both measured from the committed file:

1. **It opens with 668 s below 2.0 m/s covering 183 m** — the vehicle is parked
   or manoeuvring for the first 11 minutes. The trip does not auto-start until
   that clears, so 11 of the file's 50 minutes are dead replay time. The personal
   fixture is 762 s in total.
2. **The 319 s stop at line 1767 (113 m) crosses `STATIONARY_END_MS` (5 min),
   so the trip auto-ends mid-file** and a second trip starts afterwards. Under
   the A/B protocol's "how many trips did the replay produce", this file answers
   **two**, where `stop-start.txt` answers one. That is a different test, not a
   worse one, but a baseline copied across would be wrong.

Both are fixable by clipping the trace to the window under test, which
[`ATTRIBUTION.md`](ATTRIBUTION.md) argues for on courtesy grounds anyway. Left
unclipped here so the committed file is exactly what the recorded recipe
produces.

**Residual outlier.** `public-stop-start.txt` peaks at **193 km/h** despite the
new `gpx2route.py --max-kmh` clamp, because the default 200 km/h is deliberately
loose enough not to truncate a genuine motorcycle burst. The 341.2 km/h spike in
the source *is* gone (`--max-kmh 0` reproduces it). If a clean `topSpeedMps`
baseline matters more than headroom, `--max-kmh 150` drops 5 samples instead of 2
and caps the file at 142 km/h. The clamp is a no-op for the three personal
fixtures, which peak at 133.9 / 128.8 / 132.2 km/h.

### Speed cameras in range: not measured for these files

The camera counts in the table further down were produced against
`overpass.private.coffee`. Both that mirror and `overpass.kumi.systems` returned
**504** for all but one request on 2026-08-13, so the equivalent columns for the
`public-` files are **not reported rather than guessed**. The bboxes to run them
over, when Overpass is answering: `public-trajectcontrole` 4.4926,50.8619 →
4.6048,50.8693; `public-stop-start` 4.6888,50.8739 → 4.7990,50.9531;
`public-trajectcontrole-reverse` 4.4783,50.8581 → 4.7159,50.9085. Method is the
one below — `WARN_METERS` (400 m) of the driven line, and
`SpeedCameras.PREFETCH_RADIUS_M` (4000 m) for prefetch reach.

### Speed cameras in range

Counted 2026-08-12 against `overpass.private.coffee` — 91
`highway=speed_camera` nodes in a box 4 km larger than the routes' own extent,
then filtered per route. The app fetches `node(around:4000, …)` at the current
position (`SpeedCameras.PREFETCH_RADIUS_M = 4000.0`), so the middle column is
the number the prefetch can actually reach; the last is how many come inside
`WARN_METERS = 400.0` of the driven line, i.e. how many can produce a warning.

| Route | In the route's own bbox | Within 4 km of the line | Within 400 m of the line |
|---|---|---|---|
| `trajectcontrole.txt` | 53 | 62 | **5** |
| `urban-limits.txt` | 45 | 66 | 1 |
| `stop-start.txt` | 16 | 47 | 1 |

An earlier revision of this file claimed "68 speed cameras in the bounding box"
for `urban-limits`. That number is not reproducible: it is 45 in the strict bbox
and 66 within prefetch reach. Corrected here rather than carried forward. Note
also that `urban-limits` and `stop-start` bring only **one** camera inside
warning range each — `trajectcontrole` is the route that exercises the camera
warning, which is not what its name suggests.

## Route (iii), `off-route.txt`, does not exist and is not coming

The stage 0 spec named a fourth route: a deliberate deviation of more than 60 m
from a routed line, then a rejoin, to exercise the reroute trigger, its 15 s
cooldown and the driven-fraction fade.

**It cannot be built in this setup and no attempt should be made to find it.**
Deviation is measured against a route the app computed, and in-app navigation
requires a reachable routing server (`ServerConfig.usable`). That means a
self-hosted GraphHopper; none is configured here, so there is no computed route
to deviate *from*. A GPX of a wrong turn is not a substitute — without a route
loaded, the app has nothing to compare the position against.

Consequence, stated so nobody assumes otherwise: `NavPolicy`'s arrival and
reroute decisions have **unit tests but no recorded-drive coverage**. See
[`../../../docs/refactor/mapscreen/DECISION.md`](../../../docs/refactor/mapscreen/DECISION.md).

## Verifying `trajectcontrole.txt` still does its job

The section test is not "did the track pass near a trajectcontrole". The app
gates on `SECTION_GATE_METERS` (60 m) against an enforcement relation's `device`
member nodes, plus a heading test toward the far end — so the route must come
within 60 m of **two** device nodes of the same relation, in sequence, in the
direction the measurement runs. A relation's centroid can sit 400 m from a track
that never enters the section, and can sit on top of a track that only clips one
end.

Measured against the file, 2026-08-12:

| Relation | Verdict |
|---|---|
| `15682532` "Trajectcontrole E40" | **full transit, west → east.** West device node (50.86929, 4.49257) is 13 m away at second 166 / 3.66 km; east node (50.86183, 4.60503) is 0 m away at second 548 / 11.66 km. Transit **8.00 km in 382 s, mean 75.4 km/h** — that is the value a correct section average should settle at. |
| `15685856` "Trajectcontrole E40" | **entry only.** Its west end is the *same node* as `15682532`'s east end, so it arms at second 548; its east end is 1201 m from the closest point of the route, so the route leaves the section without ever reaching the far gate. Useful as a back-to-back re-arm test, useless as an exit test. |

If OSM changes — a gantry moved, a relation retagged, a section decommissioned —
this route stops testing what it claims to. Re-check with an Overpass query for
`relation["enforcement"="average_speed"]` over the route's bounding box
(4.4592, 50.8552 → 4.7730, 50.9083) and confirm two device nodes of one relation
still fall within 60 m of the track, in order. Both relations were still present
on 2026-08-12.

**Re-checked 2026-08-13, and both relations and both node coordinates are
unchanged.** `15682532` still carries 2 `device` members plus one `from`;
`15685856` still carries 3. Note the shortcut used, because Overpass 504'd on
every request but one that day: the relation's member refs came from one Overpass
query, and the coordinates from `api.openstreetmap.org/api/0.6/node/<id>.json`,
which is a different service and answered immediately. Device node
**6763749685** is the west gate at (50.86929, 4.49257) and **10784337380** is the
east gate at (50.86183, 4.60503) — those IDs are worth keeping, since they turn
this check into two API calls with no Overpass at all.

Both device nodes are tagged `maxspeed=120`, and **the relation is not**, so
`Section.maxspeedKmh` is null: `parseSection` reads `maxspeed` from the relation's
tags only. No fixture of this section can test the over/under-posted-limit
comparison — only the running average and the value it settles at.

## Why they are resampled rather than replayed verbatim

Two reasons, both of which produce a silently wrong replay if ignored.

**Speed.** A GPX trackpoint carries no speed. `MockService` derives it from the
gap between consecutive points at a fixed interval, so replaying an unevenly
spaced export reports the wrong speed on every fix — a 3-second source gap
replayed as one interval reports triple speed. The converter resamples to exactly
one point per interval against the track's own timestamps and discards the source
spacing entirely.

**Standstills.** The app decimates its stored trace to 25 m spacing
(`addTracePoint` in
`app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`), so a
wait at a traffic light is **not** recorded as a run of identical fixes — it is
one segment tens of seconds long covering barely more than 25 m, and the stop's
position inside that segment is not recorded anywhere. Interpolating it linearly
replays a 42-second stop as a ~2 km/h crawl, which never crosses the app's own
low-speed gates, so the very behaviour `stop-start.txt` exists to test would be
absent. Worse, a crawl in the 2.0–2.5 m/s band keeps resetting `lastMovingMs` so
the trip never auto-ends, while dragging the average pace toward the walk
threshold. The converter reconstructs the stop by holding position instead.

## Privacy

### The three unprefixed files

These are real drives of the maintainer's. Each file is converted with
`gpx2route.py --trim 1000`, which
drops a 1 km radius from each end, so **no endpoint is a home or a workplace**.
Measured along the track that is 1.02 km / 1.03 km trimmed from `trajectcontrole`,
1.01 km / 1.03 km from `urban-limits`, and 0.80 km / 0.82 km from `stop-start`
(less, because `--trim` is a straight-line radius and those ends are curved).

**Never commit a raw GPX export.** Keep it outside the repo — the scratchpad, not
the working tree — and commit only the trimmed, converted route. A raw export
carries the untrimmed endpoints and per-point timestamps, which is precisely what
the trim exists to remove. `gpx2route.py` prints statistics only and never
coordinates, so its output is safe to paste into a report.

### The `public-` files

`--trim` is not needed for a third-party trace — it exists to protect the
maintainer's own endpoints — and `public-trajectcontrole.txt` is synthetic, so it
is nobody's drive at all. But two of the three are still somebody's real driving,
and the "never commit a raw GPX" rule and the clip-to-the-window argument apply to
a stranger just as much. Both are set out in
[`ATTRIBUTION.md`](ATTRIBUTION.md#the-ethical-footnote), including the one place
they are not yet honoured.
