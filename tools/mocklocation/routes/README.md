# Canonical replay routes

Three drives that between them exercise the GPS-driven behaviour of the map
screen, the tracking service and the Android Auto surface. They exist so a
change can be A/B'd against a recorded baseline instead of against someone's
memory of a drive.

All three are derived from **real recorded drives**, exported from the app as
GPX and converted with
`.claude/skills/detour-gps-replay/scripts/gpx2route.py`. See
`.claude/skills/detour-gps-replay/SKILL.md` for how to run one.

Format: one `lon lat` pair per line, one point per replay interval (1000 ms by
default). Standstills are reconstructed as repeated identical points — see
"Why they are resampled" below, because this is the part that is easy to get
wrong.

| File | Length | What it exercises |
|---|---|---|
| `trajectcontrole.txt` | 17.0 km, 16m24s, mean 62 km/h | **Two average-speed sections driven gantry to gantry, back to back.** Section entry gating, the running average, exit detection, and the exit-then-immediately-re-enter transition. Nine standstills inside the sections, so the average is measured through congestion rather than at a constant cruise. |
| `urban-limits.txt` | 23.5 km, 24m02s, mean 59 km/h | Posted-limit changes between motorway and urban roads, cross streets, and 68 speed cameras in the bounding box. Ambient limit snapping, the three-miss clear hysteresis, prefetch refresh at the edge of the held set. |
| `stop-start.txt` | ~9.7 km, 12m42s | Four real traffic-light stops, one of 43 s, spread through the drive. Camera park and resume, speed-HUD easing back to zero, bearing hold below 2 m/s. |

## Route (iii), off-route, is deliberately absent

The fourth route named in
`docs/refactor/mapscreen/specs/stage-0-verification-baseline.md` was to be a
deliberate deviation from a routed line, exercising the reroute trigger and its
cooldown. It cannot be built: in-app navigation requires a reachable routing
server (`ServerConfig.usable`), and there is none configured in this setup, so
there is no computed route to deviate *from*.

Consequence, stated so nobody assumes otherwise: `NavPolicy`'s arrival and
reroute decisions have **unit tests but no recorded-drive coverage**. See
`docs/refactor/mapscreen/DECISION.md`.

## Verifying `trajectcontrole.txt` still does its job

The section test is not "did the track pass near a trajectcontrole". The app
gates on `SECTION_GATE_METERS` (60 m) against a relation's `device` member
nodes, plus a heading test toward the far end — so the route must come within
60 m of **two** device nodes of the same relation, in sequence. A relation's
centroid can sit 400 m from a track that never enters the section, and can sit
on top of a track that only clips one end.

The two sections this route drives are OSM relations `15685856` (entered
2.47 km in, exited at 6.36 km) and `15682532` (entered 6.36 km, exited
14.35 km). They share the device node at 6.36 km, which is what makes the
back-to-back transition testable.

If OSM changes — a gantry moved, a relation retagged, a section decommissioned
— this route stops testing what it claims to. Re-check with an Overpass query
for `relation["type"="enforcement"]["enforcement"="average_speed"]` over the
route's bounding box, and confirm two device nodes of one relation still fall
within 60 m of the track.

## Why they are resampled rather than replayed verbatim

Two reasons, both of which produce a silently wrong replay if ignored.

**Speed.** A GPX trackpoint carries no speed. `MockService` derives it from the
gap between consecutive points at a fixed interval, so replaying an unevenly
spaced export reports the wrong speed on every fix. The converter resamples to
exactly one point per interval against the track's own timestamps.

**Standstills.** The app decimates its stored trace to 25 m spacing
(`addTracePoint` in `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`),
so a wait at a traffic light is **not** recorded as a run of identical fixes —
it is one segment tens of seconds long covering barely more than 25 m.
Interpolating that linearly replays a 40-second stop as a 2 km/h crawl, which
never crosses the app's own low-speed gates, so the very behaviour route (iv)
exists to test would be absent. The converter reconstructs the stop by holding
position instead.

## Privacy

These are real drives. Each file is sliced to remove its start and end, so no
endpoint is a home or a workplace — `trajectcontrole.txt` is a mid-motorway
stretch cut out of a 149 km recording. **Do not commit a raw GPX export**; keep
it outside the repo and commit only the trimmed, converted route.
