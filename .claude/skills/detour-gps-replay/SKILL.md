---
name: detour-gps-replay
description: >-
  Replay a recorded GPS route into the Detour app so location-driven behaviour can be tested
  at a desk instead of by driving. Use this whenever a task needs the app to move — trip
  auto-detection, auto-stop, mode classification, fog of war, the speed HUD, camera
  follow/park, speed-limit signs, average-speed sections, reroute, arrival, the Android Auto
  screens, the Wear relay, convoy live location — or asks for a before/after comparison of
  anything that reads a GPS fix. Also read it before claiming a GPS-dependent change cannot
  be verified without driving; most of it can. Covers building tools/mocklocation, the
  designated-mock-app dance, the route file format, converting a real trace or GPX into a
  route (including reconstructing standstills), and the A/B protocol.
---

# Replaying a drive into Detour

## Preconditions

```sh
.claude/skills/detour-gps-replay/scripts/check-preconditions.sh
```

Five assertions, `PASS`/`FAIL` per line, non-zero exit if any failed: the harness still reads
a `route` extra and still reports `accuracy = 4f`, the app still decimates at 25 m and still
gates movement at 2.0 m/s, and `tools/mocklocation` is still **not** a module of the root
build.

That last one must stay 0: `tools/mocklocation/` is a **standalone Gradle build** with its own
wrapper and its own `settings.gradle.kts` (`rootProject.name = "DetourMockLocation"`), and its
settings file says why — "this is a test harness, not part of Detour, and nothing in the app
should ever depend on it". It is not a module of the root build, so its APK is
`tools/mocklocation/build/outputs/apk/debug/DetourMockLocation-debug.apk`, **not** anything
under `app/build/`. If a doc or plan tells you to look under `app/build/outputs` after
`cd tools/mocklocation`, the doc is wrong.

If either of the two `TripTrackingService` assertions fails — the 25 m decimation or the
2.0 m/s moving gate — the standstill and speed-gate arithmetic below is stale, and so are
`gpx2route.py`'s defaults. Re-derive both from `TripTrackingService.kt` before relying on
them.

## What replay actually reaches

Every GPS-fed surface, not just the map. Fixes enter through `TripTrackingService` and are
published as one `StateFlow` (`_lastFix` / `lastFix`, `TripTrackingService.kt:233-234`), whose
consumers are `ui/MapScreen.kt`, `car/NavScreen.kt`, `car/SpinScreen.kt` and
`net/ConvoyLiveClient.kt`; the watch is fed from the same pipeline via
`wear/NavRelay.send(context, progress, currentSpeedKmh)`. So one replay exercises the phone
map, the Android Auto screens, the Wear relay and convoy live location simultaneously — and
a regression in the shared pipeline shows up on all of them, which is useful signal about
where a bug lives.

What replay cannot reach: anything needing a second device or radio — convoy with a real
peer, BLE board telemetry (`freshBoardTelemetry()` overrides the replayed speed when a board
is paired, `TripTrackingService.kt:1091-1094`), a paired watch's own sensors, real battery
behaviour, and real GPS accuracy degradation (the harness reports a constant 4 m).

## One-time setup

The recipe is `docs/PLAY_LOCATION_DECLARATION.md:149-190`. Verified form:

```sh
# from tools/mocklocation/
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/DetourMockLocation-debug.apk
adb shell appops set com.jellemax.mocklocation android:mock_location allow
```

**Why the harness exists at all**, and why you cannot skip it: Detour reads Play Services'
fused provider, and fused only honours mocks that come from **the app the system has
designated** as the mock-location provider, and only when the `Location` carries a fresh
`elapsedRealtimeNanos`. `adb shell cmd location providers set-test-provider-location` reaches
`LocationManager` but never reaches Detour, and granting `android:mock_location` to the shell
does not help either — the fix has to originate in a designated *app*
(`docs/PLAY_LOCATION_DECLARATION.md:155-160`, and `MockService.kt:20-37`). `tools/mocklocation`
is that app: one exported foreground service that pushes fixes through
`setTestProviderLocation`.

## The route file

One `lon lat` pair per line — **longitude first**. `readRoute` parses `parts[0]` as `lon` and
`parts[1]` as `lat` (`MockService.kt:128-139`); the separator regex is `[ ,\t]+`, so spaces,
commas or tabs all work, and unparseable lines are silently skipped. Fewer than 2 usable
points and the service logs an error and stops (`MockService.kt:63-66`).

**One line = one interval.** The replay thread walks the list emitting one fix per
`intervalMs` (default 1000, `MockService.kt:61,82-91`), so the file is a *timeline*, not just
a shape. That is the whole design:

```kotlin
val speed = (distanceMeters(here, next) / (intervalMs / 1000.0)).toFloat()   // MockService.kt:87
```

Speed and bearing are **derived from the gap to the next line**, both reported on the fix.
There is no speed column and no timestamp column — spacing ÷ interval *is* the speed. A 1000 ms
interval and 12.5 m between lines is 45 km/h. The last line has no successor
(`points[(i+1).coerceAtMost(size-1)]`), so the final fix reports speed 0.

Push it into the harness's own data directory, not `/sdcard` — `start-replay.sh` does exactly
this, and this is the command it runs:

```sh
adb shell "run-as com.jellemax.mocklocation sh -c 'cat > files/route.txt'" < route.txt
```

`MockService` opens the path with a plain `File` (`MockService.kt:62`) and
`tools/mocklocation/src/main/AndroidManifest.xml` requests **no storage permission**, so under
scoped storage it cannot read a file you pushed to shared storage. Note the conflict:
`MockService`'s own KDoc (`MockService.kt:32`) tells you to
`adb push route.txt /sdcard/Download/route.txt`. **The KDoc is wrong and
`docs/PLAY_LOCATION_DECLARATION.md:171-178` is right** — use the `run-as` push above. If you
touch that file for other reasons, fixing the KDoc is a welcome one-line change.

## Running it

```sh
.claude/skills/detour-gps-replay/scripts/start-replay.sh <route.txt> [serial] [interval-ms]
.claude/skills/detour-gps-replay/scripts/stop-replay.sh  [serial] [--recover]
```

`start-replay.sh` validates the route file locally first — line count, distance, mean speed,
and a warning if column 1 looks like a latitude — then force-stops the release app, pushes the
file with `run-as`, verifies the pushed line count, and starts the service. It refuses to
start if the harness is missing or is not the designated mock-location app, printing the
setup commands instead of running them. It installs nothing and clears nothing.

**Force-stopping the release app first is not optional**, which is why it is inside the script
rather than a step to remember. The release app monitors for trips whenever it is installed,
and a mock stream will otherwise record a fabricated ride into the user's real trip history
(`docs/PLAY_LOCATION_DECLARATION.md:184-186`). Test against the `.debug` variant, which has
its own applicationId and its own data.

Stopping cleanly matters because `onDestroy` is what calls `removeTestProvider` on all four
providers (`MockService.kt:98-103`). `am stopservice` runs it; force-stopping the harness's
process does not, which can leave the device pinned to a stale mock position. That is what
`--recover` is for: `onStartCommand` calls `removeTestProvider` before re-adding each provider
(`MockService.kt:69-79`), so starting the service and stopping it properly clears the stale
state.

Watch it with `adb logcat -s MockLocation` while it runs.

## Turn off every real location source first

Wi-Fi scanning, cell positioning, a paired GPS accessory — all of it. `MockService` registers
test providers on **gps, fused, network and passive** (`MockService.kt:44-49`), and its comment
says exactly why: "fused blends whatever is enabled, so leaving a real provider live makes it
alternate between the mock route and the phone's actual position — which reads as a device
teleporting hundreds of kilometres between fixes."

That teleport is the symptom to recognise. If the map jumps between your route and your desk,
or the trip distance comes out absurd, you have a live real provider — not a bug in the code
you were testing.

## Why the fixes clear the app's accuracy gate

`MockService` reports `accuracy = 4f` on every fix (`MockService.kt:113`, plus vertical/speed/
bearing accuracies at `:118-122`). That is deliberate: `MAX_START_ACCURACY_M = 25f`
(`TripTrackingService.kt:147`) discards looser fixes from any start decision, and a fix with no
accuracy at all is worthless downstream. It also means replay never exercises the
degraded-accuracy paths — those still need a real device in a bad spot.

For reference, the auto-start gates a replay has to clear
(`TripTrackingService.kt:141-147,1031-1033`): 3 fixes at ≥ `FAST_SPEED_MPS = 7.0` m/s
(~25 km/h), sustained ≥ 8 s and ≥ 120 m — or ≥ `PROBE_SPEED_MPS = 4.0` m/s if activity
recognition has recently seen IN_VEHICLE, which a replay cannot fake. Build routes that clear
the 7.0 m/s bar unless you are specifically testing the probe path.

## Converting a real drive into a route

```sh
.claude/skills/detour-gps-replay/scripts/gpx2route.py <in.gpx> <out.txt> \
    [--interval-ms 1000] [--stop-span 12] [--stop-kmh 8] [--pull-away-kmh 20] [--trim M] \
    [--max-kmh 200]
```

This implements everything in the two sections below: it resamples against the track's own
timestamps, reconstructs standstills as held positions, and reports what it produced —
kilometres, mean and max speed, how many fixes are held at speed 0, each reconstructed
standstill, and whether the route contains a run long enough to clear the auto-start gate
(≥ 8 s and ≥ 120 m above 7.0 m/s). It prints statistics and never coordinates. `--trim` drops
metres from each end, so a route can be kept without publishing where the drive started and
finished. Read the two sections anyway — the defaults are heuristics, and `--stop-span 0`
turns the reconstruction off for a source that was never decimated at 25 m.

### A raw GPX cannot be replayed verbatim

GPX trackpoints are not evenly spaced in time — the recorder emits them on its own schedule,
and Detour's own export is decimated by distance (see below). But `MockService` reports speed
as *spacing ÷ interval*, at one point per interval. So feeding raw trackpoints in at a fixed
1 Hz reports whatever speed the *spacing* implies, which has nothing to do with the speed the
vehicle was actually doing. A 3-second gap replayed as 1 second reports triple speed.

**Resample to the interval you intend to use.** Decide the interval first (1000 ms is the
default and is plenty), then produce one line per interval by walking the source track's
*timestamps* and emitting the interpolated position at each interval boundary. Discard the
source spacing entirely; it is an artefact of the recorder.

`docs/PLAY_LOCATION_DECLARATION.md:181-182` gives the synthetic alternative when you do not
need a real trace: an OSRM route densified to one point per second at ~45 km/h.

### The standstill problem

This is the non-obvious one, and getting it wrong quietly invalidates a whole comparison.

Detour's stored trace is **decimated to 25 m spacing**. `addTracePoint` returns early for any
fix within 25 m of the last stored point, and starts a new segment past a 500 m jump
(`app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt:1132-1139`):

```kotlin
val gap = RoadRoulette.distanceMeters(lastTrace, p)
if (gap < 25.0) return
if (gap > 500.0) flushTrace()
```

So **a wait at a traffic light is not a run of identical points.** While the vehicle creeps
under 25 m, no point is stored at all. The stop survives only as one segment tens of seconds
long covering barely more than 25 m — the duration is in the `timeMs` of the two points that
bracket it, and the stop's position within that segment is not recorded anywhere.

Interpolate that segment linearly and you replay a stop as a steady crawl. Two ways that
misleads, both worth knowing because they fail in opposite directions:

- A crawl in the **2.0–2.5 m/s** band (7.2–9 km/h) is the worst case. `TripTrackingService.kt:1069`
  is `if (speed > 2.0) lastMovingMs = now`, feeding `now - lastMovingMs > STATIONARY_END_MS`
  (5 min, `:153`) — so anything above 2.0 m/s keeps resetting the moving timestamp and the
  trip *never* auto-ends, however long the "stop" lasted. Meanwhile it is low enough to drag
  the trip's average pace toward `WALK_AVG_MAX_MPS = 2.5` (`:159`, applied at `:679-681`),
  which can retag a drive as a walk.
- A long stop spread thin (a 40 s light over 26 m is 0.65 m/s) lands *below* the 2.0 m/s gate
  but keeps the position moving, so the app accumulates stationary time while still creeping
  forward — a state a real vehicle never produces.

**Reconstruct the standstill by holding position.** Emit the same coordinate on consecutive
lines for `round(stop_seconds / interval_seconds)` lines. `MockService` then computes a
zero-distance gap and reports speed 0 (`MockService.kt:87`), which is what a real stopped
vehicle reports.

**Heuristic for finding the stops** (a heuristic, not a constant in the codebase): a segment
whose implied average speed is under **~8 km/h** over more than **~12 s** contained a stop.
The numbers are not arbitrary — a decimated segment is at least 25 m long, and 25 m at 8 km/h
(2.2 m/s) takes ~11 s, which is just above the app's own 2.0 m/s moving gate. Below that pace
for that long, a vehicle in traffic was stationary for part of the segment. Attribute the
excess time to a held position at one end of the segment and spread the remainder at a
plausible approach speed, rather than smearing the whole duration across the distance.

`gpx2route.py --stop-span 12 --stop-kmh 8` are those two numbers, and `--pull-away-kmh 20` is
the "plausible approach speed" — at that pace a 25 m hop takes 4.5 s, so the rest of the
interval is held. Do not shorten it to a single interval: 25 m covered in one 1000 ms line
reports 90 km/h on that fix and can by itself trip the auto-start gate. **Both thresholds
assume the 25 m decimation**; a source that was not decimated (a raw fix log from another
recorder) needs `--stop-span 0` and its stops replayed from its own low-speed samples.

### The outlier clamp, for sources this app did not export

`--max-kmh` (default **200**) drops any sample whose implied speed from the last kept point
exceeds it, and lets the resampler interpolate across the hole. `--max-kmh 0` disables it.

It exists because the three thresholds above are not the only thing calibrated to Detour's own
exporter. The app discards fixes looser than `MAX_START_ACCURACY_M = 25f` and decimates the
stored trace to 25 m, so its GPX never contains a position spike. A third-party log has been
through neither gate: OSM public trace 1741287 carries a sample implying **341.2 km/h**, which
at 1 Hz is a 95 m jump between consecutive lines. That corrupts the recorded trip's
`topSpeedMps` — one of the two headline numbers in the A/B protocol below — and two compounding
spikes can trip the 500 m segment break and split the trace where nothing happened.

**200 km/h**, because the threshold has to sit above every speed a vehicle could plausibly have
been doing and below every speed only a fix error produces: posted limits top out at 130 km/h
in the geography these fixtures cover, the existing three fixtures peak at 134 km/h, and a real
motorcycle burst can reach 180. A tighter clamp would truncate a genuine top speed and falsify
the baseline it exists to protect.

Two more checks that go with it, when the source is somebody else's log rather than the app's
own export: **screen candidates by p90 speed**, because filtering on stop count alone selects
for pedestrians and a cycling trace never arms the 7.0 m/s auto-start gate; and watch for the
converter's `warning: N consecutive samples rejected` — a run of drops is a relocation, not a
spike, and interpolating across it fabricates a straight line at a speed nobody drove.

## The A/B protocol

The point of replay is not "it still works" — it is a number that changes or does not.
A general impression cannot survive being wrong, and this repo has already had to spend
commits undoing confident claims.

1. **Record the baseline before the change.** Same route file, same `intervalMs`, same
   variant, same starting state. Keep the route file — a baseline captured against a route
   you cannot reproduce is worth nothing.
2. **Name the quantity before you look.** Write down what you will count, and count it on
   both runs.
3. **Make the change, replay the identical file, compare the named quantity.**

Concrete quantities worth counting, in rough order of usefulness:

- **The recorded trip's `distanceMeters` and `topSpeedMps`** from
  `run-as io.github.maxke24.detour.debug cat files/trips.json`. Deterministic enough that a
  drift of more than a percent or two means the fix pipeline changed.
- **The number of trace points the replay stored.** The 25 m decimation makes this
  predictable — roughly route length ÷ 25 — so a change in it means the decimator's input
  changed. Count points, not lines: `traces.jsonl` is one JSON array *per segment*, each
  holding many `[lat, lon, timeMs, speedKmh, leanDeg]` points
  (`shared/src/commonMain/kotlin/com/jellemax/detour/data/TraceStore.kt:12-23`), and a new
  segment starts every 200 points or after a >500 m jump (`TripTrackingService.kt:1138,1149`).
  `.claude/skills/detour-trip-data/scripts/profile-trace.py <traces.jsonl>` counts them per segment and also
  reports the stops it can see, which is the quantity to compare across the two runs.
- **How many trips the replay produced, and the mode each was tagged with** — one route that
  auto-starts once and ends once should keep doing exactly that; two trips means auto-stop
  fired mid-route, and a `WALK` tag on a driving route means the average-pace classifier was
  reached (`TripTrackingService.kt:679-681`).
- **A screen recording of the same 30 s window** for camera follow/park and HUD behaviour,
  where the quantity is "how many times the camera re-centred", counted off the video.

State the two numbers and the route file in the report. "Before: 34.2 km / 78 points. After:
34.2 km / 78 points, same route file" is a result. "Behaviour looked unchanged" is not.

## Related

- `detour-adb` — package identity, reading `files/` and `shared_prefs/` on the debug variant,
  and the destructive-operation ban (never `pm clear` or uninstall to get a clean state).
- `docs/PLAY_LOCATION_DECLARATION.md:149-199` — the original recipe, plus the honest note that
  a store-listing video made this way is a simulated drive.
- `docs/DEBUG_INTENTS.md` — for behaviour that does *not* need movement; faking the outcome is
  cheaper when the outcome is all you are testing.
