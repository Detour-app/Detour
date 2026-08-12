# Canonical replay routes

Three drives that between them exercise the GPS-driven behaviour of the map
screen, the tracking service and the Android Auto surface. They exist so a
change can be A/B'd against a recorded baseline in [`../baseline/`](../baseline/)
instead of against someone's memory of a drive.

All three are derived from **real recorded drives**, exported from the app as
GPX and converted with
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

## The three routes

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

These are real drives of the maintainer's, and that is why they are usable — a
synthetic line does not produce a 42-second traffic-light stop or a congested
section average. Each file is converted with `gpx2route.py --trim 1000`, which
drops a 1 km radius from each end, so **no endpoint is a home or a workplace**.
Measured along the track that is 1.02 km / 1.03 km trimmed from `trajectcontrole`,
1.01 km / 1.03 km from `urban-limits`, and 0.80 km / 0.82 km from `stop-start`
(less, because `--trim` is a straight-line radius and those ends are curved).

**Never commit a raw GPX export.** Keep it outside the repo — the scratchpad, not
the working tree — and commit only the trimmed, converted route. A raw export
carries the untrimmed endpoints and per-point timestamps, which is precisely what
the trim exists to remove. `gpx2route.py` prints statistics only and never
coordinates, so its output is safe to paste into a report.
