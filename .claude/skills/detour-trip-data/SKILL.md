---
name: detour-trip-data
description: >-
  Read, analyse, export or reason about Detour's recorded drive data without drawing wrong
  conclusions from it. Use this whenever a task touches traces.jsonl, trips.json, a GPX export,
  the fog-of-war overlay, coverage percentages, badges, the history or trip-detail screens, or
  any statistic derived from a recorded ride — distance, duration, average or top speed, lean,
  G-force, a gap in a route, a stop, a trip that looks too short or too long. Use it before
  writing an analysis script, before comparing a replayed drive to a real one, and before
  quoting any number computed from a trace. The stored trace is decimated to 25 m, so
  point-counting and naive timing produce confident wrong answers.
---

# Reading Detour's recorded data

Every number on the history screen, every badge, every coverage percentage and every GPX
export comes from two files written by one service. Both files are lossy in a specific,
documented way. **§1 is the reason this skill exists — read it before anything else.**

## Preconditions

```sh
.claude/skills/detour-trip-data/scripts/check-preconditions.sh
```

Three assertions — the 25 m decimation on Android, `traces.jsonl` still written by
`TraceStore`, and the same 25 m spacing on iOS — printed `PASS`/`FAIL` with a non-zero exit if
any failed. If any fails, the decimation contract has been retuned and every threshold below
is stale.

## 1. The decimation contract

`TripTrackingService.addTracePoint` — `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt:1132`:

```kotlin
val gap = RoadRoulette.distanceMeters(lastTrace, p)
if (gap < 25.0) return                       // :1136 — the whole contract
if (gap > 500.0) flushTrace()                // :1138 — big jump closes the segment
```

**A fix closer than 25 m to the previously *stored* point is discarded outright.** Not
averaged, not merged — dropped. The threshold is on distance from the last kept point, so it
is a spatial filter, not a temporal one.

iOS does the same thing with the same numbers:
`iosApp/Detour/TripRecorder.swift:64-65` declares `traceSpacingMeters = 25.0` and
`traceBreakMeters = 500.0`. **The contract holds on both platforms**, so an iOS-recorded trace
is decimated identically and this whole section applies to it too.

### The three inferences it invalidates

1. **A standstill produces no points at all.** The vehicle stops; every subsequent fix is
   within 25 m of the last stored point; nothing is written until it moves 25 m again. A
   ten-minute wait at a level crossing appears in the file as **one segment whose two
   endpoints are ten minutes apart**. It does not appear as a cluster of points, and it does
   not appear as a run of low-speed samples — **there are no low-speed samples, because low
   speed is exactly the condition under which points stop being recorded.**

2. **`speedKmh` on a kept point is the speed *after* the gap, not during it.** The value
   written is `speedMps * 3.6` from the fix that finally cleared 25 m
   (`TripTrackingService.kt:1144`), i.e. the instantaneous speed at the far end of the
   interval. It says nothing about the interval it closes. Averaging the stored `speedKmh`
   values across a trace is not the trip's average speed and will read high, because every
   sample was taken at a moment the vehicle was moving enough to trigger a write.

3. **Point count is not fix count, and point spacing is not time spacing.** 200 points is
   ~5 km, not a fixed duration. Any statistic that treats consecutive points as evenly spaced
   in time is wrong. `TripDetailScreen.kt`'s replay is the in-repo example of doing this
   right: `buildReplayTimeline` (`:127`) accumulates the *real* inter-point deltas, and
   `sampleReplay` (`:143`) derives speed from each segment's own recorded distance ÷ time
   rather than reading the stored `speedKmh` field — with a comment (`:156-161`) saying
   exactly why.

**This has produced wrong conclusions twice in this project's history.** Both times from
reading a trace as if it were a fix log.

### How to detect a stop, correctly

Do **not** look for a run of low-speed samples — there is none. Look for a **large time delta
across a short distance** between two consecutive points:

```
stop between points i and i+1  ⟺  (t[i+1] - t[i]) is large
                                   AND distance(p[i], p[i+1]) is ~25-30 m
```

25 m at 50 km/h is under two seconds. Any inter-point delta of tens of seconds or more, over
a ~25 m hop, is standing still. Longer hops with long deltas are a different thing — a GPS
outage — and the segment break at `gap > 500.0` (`:1138`) is the marker for that: a stored
polyline that simply ends and a new one that begins is the tracker saying it lost the plot,
not the vehicle teleporting.

```sh
.claude/skills/detour-trip-data/scripts/profile-trace.py <file.jsonl|file.gpx>... \
    [--min-seconds 12] [--max-kmh 8]
```

Per segment (or per `<trkseg>`): point count, distance, duration, the speed range **derived
from each hop's own distance ÷ its own time**, the median hop length, and every stop found by
the test above — each with its duration, its distance and how far along the segment it fell,
which is what separates a traffic light from the driver parking at the end. Segment breaks
over 500 m are reported separately as outages, not as stops.

The two thresholds are `--min-seconds 12` and `--max-kmh 8`, and they follow from the
decimation: a hop is at least 25 m, and 25 m at 8 km/h (2.2 m/s) takes ~11 s, just above the
app's own 2.0 m/s moving gate. **They assume the 25 m decimation** — they are meaningless
against a raw fix log from another recorder, which is what the precondition check above is
for. The script also runs the naive low-speed test alongside and prints its result, because
watching it return ~0 on a trace full of stops is the fastest way to believe this section.

It prints statistics and never coordinates — see §6.

### The other flush triggers, which shape the file

`flushTrace` writes the buffer out as one JSONL line. It fires:

- every **200 points** — `if (tracePoints.size >= 200) flushTrace(keepLast = true)` (`:1149`)
- on a **>500 m jump** (`:1138`)
- on a **STILL activity transition** (`:927`)
- when a **trip ends** (`endTrip`, `:794`) and on **service destroy** (`:1266`)

So one ride routinely spans several lines, and there is no line-per-trip relationship. Note
`keepLast = true` (`:1246`): the boundary point is repeated as the first point of the next
line, so a naive concatenation double-counts it.

## 2. The files on disk

All in the app-private `filesDir`, all written through `shared/`.

| File | Defined at | Shape |
|---|---|---|
| `traces.jsonl` | `shared/src/commonMain/kotlin/com/jellemax/detour/data/TraceStore.kt:27` | one JSON array per line; each element is a point array |
| `trips.json` | `shared/.../data/TripStore.kt:31` | a single JSON array of trip objects |
| `deleted_trips.json` | `TripStore.kt:32` | tombstoned `startTimeMs` values |
| `edited_modes.json` | `TripStore.kt:33` | local vehicle-mode overrides awaiting server echo |
| `municipalities.json` | `Coverage.kt:116` | discovered municipality polygons |
| `badges.json` | `Badges.kt:61` | earned badges |
| `routes.json` | `Routes.kt:106` | saved routes |
| `saved_places.json` | `SavedPlaces.kt:24` | named pins |
| `recent_searches.json` | `RecentSearchStore.kt:10` | recent geocoder hits |

### A trace point

`TraceStore.kt:16` states the format: **`[lat, lon, timeMs, speedKmh, leanDeg]`**.

Written by `TraceStore.append` (`:43-56`), which **skips any segment shorter than 2 points**
(`:44`) — so a flush holding a single point writes nothing at all, and that point is lost.
`speedKmh` and `leanDeg` are rounded to one decimal on write (`:60-63`).

Nullability, and why:

- **`leanDeg` is `null`** when the vehicle does not measure lean (`:29-31`). It is *signed*
  (positive = leaning right) and it is the **peak since the previous point, not the reading at
  this instant** — `TripTrackingService.kt:1123-1131` explains that 25 m is a whole corner at
  town speed, so the deepest lean through it is the interesting number. Do not read `leanDeg`
  as a sample.
- **The tail may be missing entirely.** Points written before the tail existed are **two
  elements long** (`TraceStore.kt:19,92-93`). `TraceStore.parsePoints` reads those back as
  `timeMs = -1L`, `speedKmh = 0.0`, `leanDeg = null` (`:94-96`). **`timeMs == -1` means
  unknown, not epoch.** Filter those out before any timing analysis;
  `HistoryScreen.kt:88` (`if (p.timeMs < 0) continue`) and
  `TripDetailScreen.kt:129` both do.

Two readers, and picking the wrong one is a common error:

- **`TraceStore.loadAll()` / `parseLines()`** (`:65`, `:74`) return `List<List<LatLon>>` —
  **coordinates only, the whole tail thrown away.** This is the fog-of-war path, and it is
  what `Coverage.kt:287` and `ExploredArea.kt:50` consume.
- **`TraceStore.parsePoints(line)`** (`:88`) keeps the tail. **Anything that needs time, speed
  or lean must use this one.** `HistoryScreen.kt:78-81` says so in its own comment.

### A trip

`TripStore.kt:11-26`. Fields: `startTimeMs`, `endTimeMs`, `distanceMeters`, `topSpeedMps`,
`maxLeanAngleDeg`, `maxGForce`, `destinationLat?`, `destinationLon?`, `mode`.

- **`startTimeMs` is the identity.** There is no id field; `updateMode` and `delete` key on it
  (`:46`, `:63`), and so does the "open this trip" deep link.
- `destinationLat` / `destinationLon` are nullable — absent for a drive with no set
  destination, and read back as `null` when the key is missing (`:101-104`).
- `maxLeanAngleDeg` and `maxGForce` default to `0.0`, both in the class (`:16-17`) and on
  decode (`:99-100`), so **`0.0` is indistinguishable from "this build/vehicle did not record
  it"**. Do not report a 0.0 lean as a flat ride.
- `mode` defaults to `CAR`: *"Trips saved before modes existed read as CAR"* (`:20`).
- **`distanceMeters` and `topSpeedMps` come from the live fix stream, not from the trace.**
  They are accumulated in `onTripLocation` (`TripTrackingService.kt:1042-1049`) over accurate,
  recent fixes. **They are therefore not reproducible from `traces.jsonl`** — a distance you
  compute by summing the decimated polyline will be close but not equal, and a `topSpeedMps`
  has no counterpart in the trace at all. If the two disagree, that is expected, not a bug.

### Matching a trip to its points

There is no trip id in the trace file. `HistoryScreen.kt:120` `matchTripPoints` is the
canonical reassembly and its KDoc (`:100-119`) is worth reading in full. In summary: pool
every trace segment whose `[startMs, endMs]` **overlaps** the trip's window with
`TRIP_MATCH_SLACK_MS = 10_000L` slack on both ends (`:98`), sort, flatten, re-filter each
point against the window (the opening line also carries idle points recorded before the trip
began), then drop exact duplicates from the `keepLast = true` seam.

**Use `loadTripPoints` / `matchTripPoints` rather than re-deriving this.** It is covered by
`app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt` (4 tests, plain JUnit4, no
Android APIs), which exists precisely because matching a trip to a single nearest line
truncated every ride past ~5 km.

### Trace points exist outside trips

`onIdleLocation` calls `addTracePoint` too (`TripTrackingService.kt:989`), so
`traces.jsonl` accumulates territory while no trip is running. **A trace line is not evidence
of a trip.** Both call sites gate on `location.accuracy <= 50f`, so loose fixes are absent
from the trace even when they exist in the live stream.

## 3. Decimated versus live — know which one your question is about

| Stream | What it is | Rate |
|---|---|---|
| `TripTrackingService.lastFix` (`:234`) | `StateFlow<Fix?>`, set at the top of `onLocation` (`:969`) **before** any decimation | **full rate** — every fix the fused provider delivers |
| `TripTrackingService.liveTrace` (`:238`) | `StateFlow<List<LatLon>>`, mirrors the in-memory `tracePoints` buffer | **decimated** — the same 25 m points, pre-flush |
| `traces.jsonl` | flushed `tracePoints` | **decimated** |
| `TripTrackingService.stats` (`:231`) | running `TripStats`, accumulated per fix | full rate |

So: a question about what the HUD showed, what the camera followed, or what a convoy peer
received is a question about **`lastFix`, full-rate**. A question about the drawn route, the
fog, coverage, badges or a GPX file is a question about the **decimated** trace. Answering one
with the other is the specific mistake this skill exists to prevent.

`lastFix` is also **conflating** (it is a `StateFlow`): a slow collector does not see every
value. That is a real behaviour, not an artifact — see the KDoc at
`app/src/main/java/com/jellemax/detour/car/NavScreen.kt:365-377`.

## 4. GPX export

`app/src/main/java/com/jellemax/detour/data/Gpx.kt`.

- **`Gpx.build(trip, points)`** (`:30`) emits GPX 1.1: one `<trk>`, one `<trkseg>`, one
  `<trkpt lat lon>` per point, with a `<time>` child **only when `p.timeMs > 0`** (`:49`) —
  so pre-tail points silently lose their timestamps. Coordinates are written with `%.7f`
  fixed decimals, because `Double.toString` renders a longitude near the prime meridian as
  `1.0E-5`, which no GPX reader parses (`:44-46`). Track name and `<type>` carry the trip's
  mode.
- **The points come from the trace**, via `loadTripPoints` →
  `TripDetailScreen.kt:220`, then `Gpx.writeForShare(context, trip, points)` at `:444`.
  **The export therefore inherits the 25 m decimation in full.** A GPX from Detour is a ~25 m
  polyline, not a fix log; a standstill in it is one long-duration trackpoint interval; and
  its per-point speed does not exist as a field at all (GPX carries only lat/lon/time here).
  Anything computed downstream from the GPX inherits every caveat in §1.
- **`writeForShare`** (`:63`) writes into `cacheDir/shared/` — the only path the FileProvider
  is scoped to — and returns a `content://` Uri. `TripDetailScreen.kt:446-447` hands that to
  an `ACTION_SEND` chooser. **Where the file finally lands is whatever the receiving app does
  with it**; nothing in this repo writes to `Download/`. The Share icon on the trip detail
  screen is the supported way to get a trip out of an install.
- Only the one trip being exported is written, never the history — `Gpx.kt:18-21` says that is
  deliberate, because traces are private.

`RouteGpx` in `shared/` is a **different** thing: planned-route import/export, not recorded
drives. Do not confuse the two.

## 5. Auto-detection thresholds that change how the data reads

`TripTrackingService.kt:140-202`. These are trip *boundaries*, which is why an analyst needs
them: they decide whether what you are looking at is one journey or two.

| Constant | Value | Line | Why it matters when reading data |
|---|---|---|---|
| `STATIONARY_END_MS` | **5 minutes** (`5 * 60_000L`) | `:153`, used at `:1082` | **Stationary for five minutes ends the trip.** Two consecutive trips separated by a gap of roughly five minutes or more are very likely *one journey with a stop in the middle* — a fuel stop, a coffee, a traffic jam that outlasted the timer. Check the end of trip N against the start of trip N+1: same place plus a ≥5 min gap means treat them as one ride, not two |
| `EXIT_GRACE_MS` | 2 minutes | `:152`, used at `:1075` | if the system reports leaving the vehicle and speed stays under 5 m/s, the trip ends after two minutes instead of five — so a gap can be as short as ~2 min |
| `MIN_AUTO_TRIP_METERS` | **500 m** | `:154`, used at `:796` | an auto-detected trip under 500 m is **discarded, never saved**. Short hops are missing from `trips.json` by design — their trace points still exist. A manually started trip is exempt (`:797`) |
| auto-stop at origin | >400 m away, then back within 120 m, after 5 min | `:1055-1066` | a loop that returns home ends itself. A long ride that passes its own start early can be cut short here |
| `MAX_START_ACCURACY_M` | 25 m | `:147` | fixes looser than this never start a trip, so a trip's recorded start can lag its real one |
| `WALK_AVG_MAX_MPS` / `WALK_TOP_MAX_MPS` | 2.5 / 6.0 m/s | `:159`, `:163` | a slow trip with no mapped vehicle is reclassified `WALK`, judged on *average* speed after `WALK_MIN_JUDGE_MS` (90 s). A car in heavy town traffic can be misfiled |
| `MAX_PLAUSIBLE_LEAN_DEG` / `MAX_PLAUSIBLE_G` | 65° / 2.0 g | `:172`, `:202` | recorded maxima are **clamped**. A trip showing exactly these values is at the ceiling, not necessarily at the truth |

The same nineteen thresholds are duplicated verbatim in Swift at
`iosApp/Detour/TripRecorder.swift:41-60`, so an iOS-recorded history segments the same way.

## 6. Privacy — this is real movement data

**A trace is where the user actually went.** Home, work, a partner's address, a doctor. There
is no anonymisation anywhere in the pipeline, `timeMs` makes every point a timestamped
presence record, and the endpoints of the first and last trip of a typical day *are* the home
and work addresses.

Rules, without exception:

- **Never commit a raw export.** No `traces.jsonl`, no `trips.json`, no `.gpx`, no screenshot
  of a map showing a real route, no test fixture cut from real data — not into the repo, not
  into `docs/`, not into a plan or a report.
- **Write intermediates to the session scratchpad, not the working tree.** If a file has to
  exist to be analysed, it lives outside the repo and is deleted afterwards.
- **If a derived artifact must be committed** — a regression fixture, a figure in a document —
  **trim the endpoints.** Drop points near the start and end of the trace, which is where the
  identifying addresses are, and prefer synthetic coordinates outright: the tests in this repo
  build their fixtures from literals (`LatLon(50.8, 3.2)`), not from recordings.
- **Quote statistics, not coordinates.** "The trace has a 14-minute gap at point 63" is a
  finding. Pasting the lat/lon is a disclosure.
- The app itself already draws this line: `Gpx.kt:18-21` exports one trip at a time, from an
  explicit share on that trip's own screen, and never batches the history — because traces are
  private. Analysis should be no looser than the product.
