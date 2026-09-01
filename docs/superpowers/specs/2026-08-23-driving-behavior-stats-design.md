# Driving-behavior stats: hard events, speeding exposure, road mix, twistiness, stops

Closes maxke24/Detour#61. Android only this pass (#61's own open question — iOS/wear parity is
a follow-up, once `shared/` math exists it is a UI-only port). #62 (OBD2) is a separate epic
that plugs into this one's brake/accel derivation later; not touched here.

## Scope

All six sub-stats from #61, added to the existing per-trip pipeline (`TripTrackingService.kt`,
`TripStore.kt`) rather than a new subsystem:

1. Hard brake / hard accel — GPS Δv/Δt, both modes.
2. Hard cornering — car: heading-rate; moto: existing lean stream, banded.
3. Time-over-limit — reuse `SpeedLimitTracker`'s snap, a new service-owned instance.
4. Road-type mix — new OSM way-fetch (broader than `SpeedLimitTracker`'s), bucketed by class.
5. Twistiness score — new `Curviness.traceScore`, computed once post-trip over the reassembled
   trace, not live.
6. Stops / idle time — new small state machine on the existing per-fix speed signal.

Out of scope, per #61 itself: OBD2 (#62), driver scoring/leaderboards, a calibrated-mount
requirement, elevation. No `Badges.kt` change — none of this earns a badge.

Thresholds below are **provisional defaults**, named constants, not calibrated against real
trips (#61's own open question — no recorded data exists yet to calibrate against). Marked
`// provisional` at each declaration so a future calibration pass greps to every one.

## Data model

`Trip` (`shared/.../data/TripStore.kt:11`) gains one nested field, following the same
default-zero-on-decode pattern `maxLeanAngleDeg`/`maxGForce` already use — old trips decode with
an all-zero `DrivingStats`, indistinguishable from "recorded and found nothing," same caveat the
`detour-trip-data` skill already documents for `maxGForce == 0.0`:

```kotlin
data class DrivingStats(
    val hardBrakeCount: Int = 0,
    val hardAccelCount: Int = 0,
    val hardCornerCount: Int = 0,
    val secondsOverLimit: Long = 0,
    val pctOverLimit: Double = 0.0,
    val roadTypeMeters: Map<String, Double> = emptyMap(), // key: HighwayClass.name
    val twistinessScore: Double = 0.0,
    val stopCount: Int = 0,
    val idleMs: Long = 0,
)
```

Encoded as a nested `JsonObject` in `TripStore.encode`/`load`, same shape convention as the rest
of the file (`buildJsonObject { put(...) }`, `optLong`/`optDouble` with defaults on decode).

`TripStats` (`app/.../tracking/TripTrackingService.kt:78`) gains the live-updating subset — the
five that update per-fix (`hardBrakeCount`, `hardAccelCount`, `hardCornerCount`,
`secondsOverLimit`, `stopCount`) — for the HUD to tick. `roadTypeMeters` and `twistinessScore`
are finalize-only (see below) and only ever appear on the persisted `Trip`, never on live
`TripStats`.

`HighwayClass` — new enum in `shared/.../data/TravelMode.kt` or its own file, three buckets
matching #61's own bucket list and `TravelMode.highwayRegex`'s vocabulary:

```kotlin
enum class HighwayClass(val regex: String) {
    MOTORWAY("^(motorway|trunk|motorway_link|trunk_link)$"),
    ARTERIAL("^(primary|secondary|primary_link|secondary_link)$"),
    LOCAL("^(tertiary|unclassified|residential|tertiary_link)$"),
}
```

## 1–2. Hard brake / accel / corner — `shared/drive/HardEventDetector.kt`

New file, clock-free like `SpeedLimitTracker` — every function takes its timestamps as
parameters, per the `detour-shared-core` skill's rule (§4, wall clock: "prefer a timestamp
parameter anyway" for anything path-dependent).

```kotlin
object HardEventDetector {
    const val HARD_BRAKE_MPS2 = -3.4   // ~0.35g, provisional
    const val HARD_ACCEL_MPS2 = 2.9    // ~0.30g, provisional
    const val HARD_CORNER_DEG_PER_SEC = 25.0  // car heading-rate, provisional
    const val HARD_CORNER_LEAN_DEG = 40.0     // moto, provisional — independent of
                                               // MAX_PLAUSIBLE_LEAN_DEG's 65° ceiling
    const val MIN_CORNER_SPEED_MPS = 5.0  // below this a heading swing is a parking
                                           // maneuver, not cornering — mirrors
                                           // MIN_LEAN_SPEED_MPS's reasoning

    data class State(
        val lastSpeedMps: Double? = null, val lastFixMs: Long = 0L,
        val lastHeadingDeg: Double? = null,
        val corneringNow: Boolean = false, // hysteresis: one corner, not one per fix
    )

    data class Result(val state: State, val hardBrake: Boolean, val hardAccel: Boolean)

    /** GPS Δv/Δt between consecutive fixes. Orientation-independent — see #61's own
     *  rationale for not trusting the car IMU (phone slides in a cradle). */
    fun onSpeedFix(state: State, speedMps: Double, fixMs: Long): Result { ... }

    /** Heading-rate corner detection (car). Counts once per corner via [State.corneringNow]
     *  hysteresis, not once per fix inside the corner. */
    fun onHeadingFix(state: State, headingDeg: Double, speedMps: Double, fixMs: Long): Pair<State, Boolean> { ... }

    /** Moto: bands the existing lean stream. Same hysteresis shape as [onHeadingFix]. */
    fun onLeanSample(corneringNow: Boolean, leanDeg: Double): Pair<Boolean, Boolean> { ... }
}
```

Both Δv/Δt and heading-rate need a `dt` floor/ceiling like `speedOf`'s existing
`dtSec !in 1.0..120.0` guard (`TripTrackingService.kt:1128`) — a batched or stale fix pair
must not produce a spurious spike from a huge or a near-zero `dt`.

## 3. Time-over-limit — a second `SpeedLimitTracker.State`

`SpeedLimitTracker` (`shared/drive/SpeedLimitTracker.kt`) is reused **as-is**, no changes to the
object itself. `TripTrackingService` gets its own `State` field, separate from `MapScreen`'s and
`SpinScreen`'s UI copies — this one exists to accumulate over the trip, not to drive a sign.
Ticked in `onTripLocation` following the same "split in five" ordering the KDoc at
`SpeedLimitTracker.kt:14-25` prescribes: `needsWays`/`fetchStarted`/`withWays` own the fetch (a
new `Job` field, same in-flight-guard shape as `SpinScreen.kt:272`'s `limitFetchJob`), `onFix`
runs every fix regardless.

Accumulation, each fix once `onFix` has snapped a limit:

```kotlin
const val OVER_LIMIT_MARGIN = 1.10 // 10% over posted, provisional — GPS/rounding noise floor
if (limitKmh != null && effectiveSpeedMps * 3.6 > limitKmh * OVER_LIMIT_MARGIN) {
    secondsOverLimit += (nowMs - lastFixMs) / 1000.0
}
```

`pctOverLimit` is `secondsOverLimit / (durationMs / 1000.0)` at finalize, guarding
division-by-zero the way `Trip.avgSpeedMps` already does.

## 4. Road-type mix — new tracker, not a reuse of `SpeedLimitTracker`'s fetch

`SpeedLimitTracker`'s Overpass query filters on `["maxspeed"]` (`RoadRoulette.kt:269`) —
reusing it for road-mix would undercount every untagged residential street, which is common in
OSM. Road-mix needs *every* driven way's `highway` tag regardless of whether it carries a
`maxspeed`, so this is a sibling fetch, same shape as `SpeedLimitTracker` (`State`/`needsWays`/
`onFix`, clock-free), querying `["highway"]` alone and returning `HighwayClass` instead of
`kmh`:

```kotlin
object RoadTypeTracker {
    data class ClassifiedWay(val highwayClass: HighwayClass, val points: List<LatLon>)
    data class State(val ways: List<ClassifiedWay> = emptyList(), val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L, val meters: Map<HighwayClass, Double> = emptyMap())
    // needsWays / fetchStarted / withWays: same shape as SpeedLimitTracker
    fun onFix(state: State, at: LatLon, headingDeg: Double?, distanceSinceLastFix: Double): State
}
```

Snapping reuses `RoadRoulette`'s existing nearest-way logic (the same snap `snapSpeedLimitKmh`
does), returning a class instead of a speed. Distance since the last fix is attributed to
whichever class the snap resolves, same accumulation shape as `SpeedLimitTracker`'s three-miss
clear — except unmatched fixes here just attribute to nothing rather than clearing state,
because there is no "sign" to blank.

One more Overpass round-trip per trip than today (this data was never fetched before), same
throttle/margin constants as `SpeedLimitTracker` (`FETCH_THROTTLE_MS`, `FETCH_MARGIN_M`) copied
rather than shared, since the two trackers' `State`s are independent instances with independent
fetch cadences — sharing a throttle constant is fine, sharing the fetch itself is not (different
Overpass query, different response shape).

## 5. Twistiness — post-trip, not live

**Not computed per-fix.** #61 says "applied post-hoc to the trace actually driven," and the
`detour-trip-data` skill's decimation contract explains why live is the wrong place: raw 1 Hz
fixes at low speed sit well under 25 m apart, so a circumradius fit on consecutive *raw* fixes
is dominated by GPS noise, not road geometry. `Curviness`'s existing 25–300 m radius window
(`RoundTripPlanner.kt:27-65`) assumes ~25 m-spaced points, which is exactly what the decimated
trace already gives for free.

`endTrip()` (`TripTrackingService.kt:798`) already calls `flushTrace()` before `TripStore.save`,
so every point belonging to this trip is on disk in `traces.jsonl` by the time the `Trip` is
built. `HistoryScreen.kt:165`'s `loadTripPoints(context, trip)` is `public` and takes only a
`Context` and a `Trip` — `TripTrackingService` *is* a `Context` (it's a `Service`), so it is
called directly, no extraction needed:

```kotlin
val points = loadTripPoints(this, trip).map { it.at }
val twistiness = Curviness.traceScore(points)
```

New function, `RoundTripPlanner.kt`, alongside `score`/`routeScore`:

```kotlin
/** Same 25-300m circumradius window as [score]/[routeScore], applied to a driven trace
 *  instead of an OSM way or a routed polyline. No junction filtering: a driven trace carries
 *  neither OSM node ids ([score]'s junction set) nor turn instructions ([routeScore]'s), so
 *  real intersections are not excluded — this over-counts town-driving corners as "twisty"
 *  relative to the other two callers. Acceptable for a same-trip-to-same-trip comparison;
 *  documented rather than solved. */
fun traceScore(points: List<LatLon>): Double { ... same body as score(), minus junction skip ... }
```

This one extra `loadTripPoints` call is the only place `endTrip()` reads back its own just-flushed
write — worth flagging in review since nothing else in the file does that today.

## 6. Stops / idle time — new small state machine, `shared/drive/StopDetector.kt`

The existing `pendingStopAtMs`/`STATIONARY_END_MS` machinery only detects a stop long enough to
**end** the trip. A fuel stop mid-manually-tracked-trip needs a *within-trip* pause/resume,
which nothing today computes. Clock-free, timestamps as parameters (same rule as
`HardEventDetector`):

```kotlin
object StopDetector {
    const val STOP_SPEED_FLOOR_MPS = 2.0        // same floor onTripLocation already uses
    const val MIN_STOP_DWELL_MS = 20_000L       // provisional — filters a traffic light

    data class State(val candidateSince: Long? = null, val stopCount: Int = 0, val idleMs: Long = 0)

    fun onFix(state: State, speedMps: Double, fixMs: Long): State {
        if (speedMps < STOP_SPEED_FLOOR_MPS) {
            val since = state.candidateSince ?: fixMs
            return state.copy(candidateSince = since)
        }
        val since = state.candidateSince ?: return state
        val dwell = fixMs - since
        return if (dwell >= MIN_STOP_DWELL_MS)
            state.copy(candidateSince = null, stopCount = state.stopCount + 1, idleMs = state.idleMs + dwell)
        else state.copy(candidateSince = null)
    }
}
```

Ticked in `onTripLocation` off the same `speed`/`location.time` already computed there — no new
signal.

## `TripTrackingService` integration points

All five new per-fix hooks (everything except twistiness) live in `onTripLocation`
(`:1048`), next to the existing `pendingStopAtMs`/`lastMovingMs` bookkeeping they parallel. New
`State` fields alongside `lastLocation`/`origin` (`:339-344`), reset in `beginTrip` alongside
`currentLeanDeg = 0.0; maxLeanDeg = 0.0` (`:782`), finalized into `DrivingStats` in `endTrip`
right before `TripStore.save` (`:807`) — twistiness computed there too, after `flushTrace()`,
per §5.

The two new OSM fetches (`SpeedLimitTracker`'s new trip-scoped instance, `RoadTypeTracker`) each
need their own in-flight `Job`, following `SpinScreen.kt:272`'s guard shape rather than
`MapScreen.kt`'s inline-`Dispatchers.IO` shape — `detour-shared-core` §6 already documents the
car copy as the one to copy, the phone copy as the one with the known stall bug. `endTrip` should
cancel both jobs, same as `stopMotionSensors()` tears down the sensor listener.

## UI

`MapHud.kt` — live counters during a trip: hard-brake/accel/corner counts, current
over-limit indicator. Disclaimer text near the hard-event counters, per #61's guardrail: this
is not a number to chase.

`HistoryScreen.kt` (trip-detail view) — road-mix bar (three `HighwayClass` buckets), twistiness
score, time-over-limit %, stop count + idle duration. Same disclaimer as the HUD.

No `Badges.kt` touch.

## Commit structure

Following `detour-staged-refactor`'s independently-revertible-unit rule, each sub-stat is its
own commit (shared math + its one wiring point), in dependency order:

1. `DrivingStats` data model + `TripStore` encode/decode (old trips decode with defaults) —
   foundation, nothing reads or writes it yet.
2. `HighwayClass` enum.
3. `HardEventDetector` (shared) + wiring in `onTripLocation` + `TripStats`/`DrivingStats` fields.
4. `StopDetector` (shared) + wiring.
5. Trip-scoped `SpeedLimitTracker.State` + time-over-limit accumulation + wiring.
6. `RoadTypeTracker` (shared) + wiring.
7. `Curviness.traceScore` + the post-trip `loadTripPoints` call in `endTrip`.
8. `MapHud.kt` live counters + disclaimer.
9. `HistoryScreen.kt` trip-detail summary + disclaimer.

## Tests

`shared/src/commonTest/kotlin/com/jellemax/detour/drive/`, matching the house style in
`SpeedLimitTrackerTest.kt` (plain `kotlin.test`, a builder function per fixture, timestamps as
literal arguments):

- `HardEventDetectorTest` — brake/accel threshold crossing, the `dt` floor/ceiling rejects a
  stale or batched pair, corner hysteresis counts one event per corner not one per fix.
- `StopDetectorTest` — dwell under `MIN_STOP_DWELL_MS` is not a stop, a dwell that never resumes
  (trip ends mid-stop) is exercised as its own case.
- `CurvinessTest` (extends the existing file, doesn't create a new one) — `traceScore` against a
  synthetic bent polyline (literal `LatLon`s, per the privacy rule in `detour-trip-data` §6 —
  never a real trace).
- `RoadTypeTrackerTest` — snap-to-class, matching `SpeedLimitTrackerTest`'s shape for `onFix`.

No test for the `TripTrackingService` wiring itself, same reasoning
`2026-08-16-monotonic-fix-age-and-marker-heading-design.md` already gives for this file: no
Robolectric, no instrumented test source set. Verified by GPS replay instead (below).

## Verification

Per `detour-gps-replay` (device-in-the-loop, since the user asked to test this on their own
phone): record or use an existing fixture route with at least one hard brake, one sustained
corner, one signed-limit stretch driven over it, and one real stop. Confirm on-device:

- HUD counters tick during the trip and match what the route fixture actually contains.
- The finished trip's `HistoryScreen` summary shows non-zero, plausible values for all six.
- An old trip (recorded before this change) still opens and displays zeroes, not a crash.

**Gates before considering this done**, per `detour-shared-core` §7 (this touches `shared/`, so
the iOS workflow's path gate fires even though no iOS UI is wired) and `CONTRIBUTING.md`:

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

`versionName` bump: new feature, backward compatible (old trips still decode) → minor bump, per
`CONTRIBUTING.md`'s Versioning section.
