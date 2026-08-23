# Driving-Behavior Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record per-trip driving-behavior stats (hard brake/accel/corner counts, time-over-limit,
road-type mix, twistiness, stops/idle) for car and moto trips, surfaced on the live HUD and the
trip-history/detail screens.

**Architecture:** Six independent shared-math units (mostly clock-free state machines mirroring
`SpeedLimitTracker`'s shape) feed one existing pipeline: `TripTrackingService.onTripLocation`
ticks five of them per GPS fix; `endTrip` finalizes into a new `DrivingStats` on `Trip`, computing
the sixth (twistiness) post-hoc from the already-flushed trace. UI reads the same `Trip`/`TripStats`
it already reads today, extended with the new fields.

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain`), Android (`app/`), Jetpack Compose,
`kotlin.test` in `commonTest`, plain JUnit4 in `app/src/test`.

**Spec:** `docs/superpowers/specs/2026-08-23-driving-behavior-stats-design.md`

## Global Constraints

- Android only this pass. No iOS/wear UI. Math still lands in `shared/` so iOS gets it free later.
- All new thresholds are **provisional defaults**, not calibrated against real trips — name them
  as constants with a `// provisional` comment, never inline literals.
- Old trips must decode with an all-zero `DrivingStats` — no migration, no crash, matching how
  `maxLeanAngleDeg`/`maxGForce` already default.
- No `Badges.kt` change of any kind.
- New shared logic follows `detour-shared-core`: clock-free, timestamps as parameters, no
  `Dispatchers.*` in `commonMain`, `commonTest` has no file access.
- Touching `shared/` requires, before each shared-touching commit is considered done:
  `./gradlew :shared:compileCommonMainKotlinMetadata` and `./gradlew :shared:testDebugUnitTest`.
- Full gate before the plan is considered finished:
  `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` then
  `./gradlew :app:assembleDebug :app:assembleRelease`.
- `TripTrackingService` wiring steps have no unit test available (no Robolectric, no
  instrumented source set in this repo) — verified by on-device GPS replay instead, same
  precedent as `docs/superpowers/specs/2026-08-16-monotonic-fix-age-and-marker-heading-design.md`.
- `versionName` in `app/build.gradle.kts` bumps **minor** (`1.76.0` → `1.77.0`) — new feature,
  backward compatible — as the last step of the last task, per `CONTRIBUTING.md`'s Versioning
  section.

---

## File Structure

New files:
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/HighwayClass.kt` — 3-bucket road-class enum.
- `shared/src/commonMain/kotlin/com/jellemax/detour/drive/HardEventDetector.kt` — brake/accel/corner detection.
- `shared/src/commonMain/kotlin/com/jellemax/detour/drive/StopDetector.kt` — mid-trip stop/idle detection.
- `shared/src/commonMain/kotlin/com/jellemax/detour/drive/RoadTypeTracker.kt` — OSM highway-class fetch+snap+accumulate.
- `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripStoreTest.kt`
- `shared/src/commonTest/kotlin/com/jellemax/detour/data/CurvinessTest.kt`
- `shared/src/commonTest/kotlin/com/jellemax/detour/drive/HardEventDetectorTest.kt`
- `shared/src/commonTest/kotlin/com/jellemax/detour/drive/StopDetectorTest.kt`
- `shared/src/commonTest/kotlin/com/jellemax/detour/drive/RoadTypeTrackerTest.kt`

Modified files:
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt` — `DrivingStats`, `Trip.drivingStats`, encode/decode.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt` — promote 4 private members to `internal` for reuse.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoundTripPlanner.kt` — `Curviness.traceScore`.
- `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — `TripStats` fields, per-fix wiring, finalize in `endTrip`.
- `app/src/main/java/com/jellemax/detour/ui/MapHud.kt` — live counters + disclaimer.
- `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt` — `tripStatLine` extended (feeds both the history row and `TripDetailScreen`).
- `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt` — disclaimer text.
- `app/build.gradle.kts` — version bump.

---

### Task 1: `DrivingStats` + `HighwayClass` + `TripStore` encode/decode

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/HighwayClass.kt`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripStoreTest.kt`

**Interfaces:**
- Produces: `data class DrivingStats(hardBrakeCount: Int, hardAccelCount: Int, hardCornerCount: Int, secondsOverLimit: Long, pctOverLimit: Double, roadTypeMeters: Map<HighwayClass, Double>, twistinessScore: Double, stopCount: Int, idleMs: Long)`, all defaulting to zero/empty. `Trip.drivingStats: DrivingStats`. `enum class HighwayClass { MOTORWAY, ARTERIAL, LOCAL }` with `HighwayClass.of(tag: String): HighwayClass?`. `internal fun TripStore.encode(t: Trip): JsonObject` (promoted from `private`) and new `internal fun TripStore.decodeTrip(o: JsonObject): Trip`, both consumed directly by later tasks' tests and by Task 6/7's finalize code.

- [ ] **Step 1: Write `HighwayClass`**

```kotlin
package com.jellemax.detour.data

/** Three road-class buckets for the driving-behavior road-type-mix stat
 *  (maxke24/Detour#61) — coarser than [TravelMode.highwayRegex]'s per-mode
 *  regexes, matched instead against `RoadRoulette.DRIVABLE_HIGHWAYS`'s set. */
enum class HighwayClass {
    MOTORWAY, ARTERIAL, LOCAL;

    companion object {
        fun of(highwayTag: String): HighwayClass? = when (highwayTag) {
            "motorway", "trunk", "motorway_link", "trunk_link" -> MOTORWAY
            "primary", "secondary", "primary_link", "secondary_link" -> ARTERIAL
            "tertiary", "unclassified", "residential", "living_street", "tertiary_link" -> LOCAL
            else -> null
        }
    }
}
```

- [ ] **Step 2: Run the shared metadata check to confirm it compiles**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL (nothing consumes the new file yet, but it must compile standalone).

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/HighwayClass.kt
git commit -m "feat(shared): add HighwayClass road-mix bucket enum"
```

- [ ] **Step 4: Add `DrivingStats` and `Trip.drivingStats` in `TripStore.kt`**

Modify `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt` — add after the
`Trip` class closes (after line 26, before `object TripStore`):

```kotlin
/** Per-trip driving-behavior stats (maxke24/Detour#61). All thresholds that feed
 *  these are provisional — not yet calibrated against real recorded trips. A trip
 *  saved before this existed decodes with every field at its zero/empty default,
 *  indistinguishable from "recorded and found nothing" — same caveat [Trip.maxGForce]
 *  already carries. */
data class DrivingStats(
    val hardBrakeCount: Int = 0,
    val hardAccelCount: Int = 0,
    val hardCornerCount: Int = 0,
    val secondsOverLimit: Long = 0,
    val pctOverLimit: Double = 0.0,
    val roadTypeMeters: Map<HighwayClass, Double> = emptyMap(),
    val twistinessScore: Double = 0.0,
    val stopCount: Int = 0,
    val idleMs: Long = 0,
)
```

Modify the `Trip` class (lines 11-26) to add one field after `mode`:

```kotlin
data class Trip(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceMeters: Double,
    val topSpeedMps: Double,
    val maxLeanAngleDeg: Double = 0.0,
    val maxGForce: Double = 0.0,
    val destinationLat: Double?,
    val destinationLon: Double?,
    /** Which vehicle this was. Trips saved before modes existed read as CAR. */
    val mode: TravelMode = TravelMode.CAR,
    val drivingStats: DrivingStats = DrivingStats(),
) {
```

- [ ] **Step 5: Extend `encode`, extract `decodeTrip`, add `DrivingStats` encode/decode**

Replace the existing `private fun encode` (lines 77-87) with:

```kotlin
    internal fun encode(t: Trip): JsonObject = buildJsonObject {
        put("startTimeMs", t.startTimeMs)
        put("endTimeMs", t.endTimeMs)
        put("distanceMeters", t.distanceMeters)
        put("topSpeedMps", t.topSpeedMps)
        put("maxLeanAngleDeg", t.maxLeanAngleDeg)
        put("maxGForce", t.maxGForce)
        put("destinationLat", t.destinationLat?.let { JsonPrimitive(it) } ?: JsonNull)
        put("destinationLon", t.destinationLon?.let { JsonPrimitive(it) } ?: JsonNull)
        put("mode", t.mode.name)
        put("drivingStats", encodeDrivingStats(t.drivingStats))
    }

    private fun encodeDrivingStats(d: DrivingStats): JsonObject = buildJsonObject {
        put("hardBrakeCount", d.hardBrakeCount)
        put("hardAccelCount", d.hardAccelCount)
        put("hardCornerCount", d.hardCornerCount)
        put("secondsOverLimit", d.secondsOverLimit)
        put("pctOverLimit", d.pctOverLimit)
        put("roadTypeMeters", buildJsonObject { d.roadTypeMeters.forEach { (k, v) -> put(k.name, v) } })
        put("twistinessScore", d.twistinessScore)
        put("stopCount", d.stopCount)
        put("idleMs", d.idleMs)
    }

    private fun decodeDrivingStats(o: JsonObject?): DrivingStats {
        if (o == null) return DrivingStats()
        return DrivingStats(
            hardBrakeCount = o.optLong("hardBrakeCount").toInt(),
            hardAccelCount = o.optLong("hardAccelCount").toInt(),
            hardCornerCount = o.optLong("hardCornerCount").toInt(),
            secondsOverLimit = o.optLong("secondsOverLimit"),
            pctOverLimit = o.optDouble("pctOverLimit", 0.0),
            roadTypeMeters = o.optObject("roadTypeMeters")?.let { rt ->
                HighwayClass.entries.mapNotNull { cls ->
                    val v = rt.optDouble(cls.name, Double.NaN)
                    if (v.isNaN()) null else cls to v
                }.toMap()
            } ?: emptyMap(),
            twistinessScore = o.optDouble("twistinessScore", 0.0),
            stopCount = o.optLong("stopCount").toInt(),
            idleMs = o.optLong("idleMs"),
        )
    }
```

Replace the body of `load()` (lines 89-111) — extract the per-object construction into a new
`internal fun decodeTrip`, keep `load()` calling it:

```kotlin
    fun load(): List<Trip> {
        val f = appFile(FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            jsonArrayOf(f.readText()).objects().map { decodeTrip(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun decodeTrip(o: JsonObject): Trip = Trip(
        startTimeMs = o.optLong("startTimeMs"),
        endTimeMs = o.optLong("endTimeMs"),
        distanceMeters = o.optDouble("distanceMeters", 0.0),
        topSpeedMps = o.optDouble("topSpeedMps", 0.0),
        maxLeanAngleDeg = o.optDouble("maxLeanAngleDeg", 0.0),
        maxGForce = o.optDouble("maxGForce", 0.0),
        destinationLat = if (!o.has("destinationLat")) null
            else o.optDouble("destinationLat").takeIf { !it.isNaN() },
        destinationLon = if (!o.has("destinationLon")) null
            else o.optDouble("destinationLon").takeIf { !it.isNaN() },
        mode = TravelMode.of(o.optString("mode")),
        drivingStats = decodeDrivingStats(o.optObject("drivingStats")),
    )
```

This is a pure extraction of existing behavior (same field reads, same defaults) plus the new
field — no other line in `load()` changes.

- [ ] **Step 6: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripStoreTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterises [TripStore]'s encode/decode round trip for [DrivingStats] —
 *  pure JSON building, no file access, so these run in commonTest per
 *  detour-shared-core §8 (file I/O needs androidUnitTest instead). */
class TripStoreTest {

    private fun trip(drivingStats: DrivingStats = DrivingStats()) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_060_000L,
        distanceMeters = 1200.0,
        topSpeedMps = 30.0,
        destinationLat = null,
        destinationLon = null,
        mode = TravelMode.CAR,
        drivingStats = drivingStats,
    )

    @Test
    fun drivingStatsRoundTripsThroughEncodeAndDecode() {
        val stats = DrivingStats(
            hardBrakeCount = 2, hardAccelCount = 1, hardCornerCount = 3,
            secondsOverLimit = 45, pctOverLimit = 12.5,
            roadTypeMeters = mapOf(HighwayClass.MOTORWAY to 500.0, HighwayClass.LOCAL to 300.0),
            twistinessScore = 0.42, stopCount = 1, idleMs = 90_000L,
        )
        val decoded = TripStore.decodeTrip(TripStore.encode(trip(stats)))
        assertEquals(stats, decoded.drivingStats)
    }

    @Test
    fun aTripSavedBeforeDrivingStatsExistedDecodesWithAllZeroDefaults() {
        // Simulates an old trips.json entry: no "drivingStats" key at all.
        val oldTripJson = """
            {"startTimeMs":1700000000000,"endTimeMs":1700000060000,
             "distanceMeters":1200.0,"topSpeedMps":30.0,"mode":"CAR"}
        """.trimIndent()
        val decoded = TripStore.decodeTrip(jsonObjectOf(oldTripJson))
        assertEquals(DrivingStats(), decoded.drivingStats)
    }

    @Test
    fun roadTypeMetersOnlyKeepsClassesActuallyPresent() {
        val stats = DrivingStats(roadTypeMeters = mapOf(HighwayClass.ARTERIAL to 1_000.0))
        val decoded = TripStore.decodeTrip(TripStore.encode(trip(stats)))
        assertEquals(mapOf(HighwayClass.ARTERIAL to 1_000.0), decoded.drivingStats.roadTypeMeters)
        assertTrue(HighwayClass.MOTORWAY !in decoded.drivingStats.roadTypeMeters)
    }
}
```

- [ ] **Step 7: Run the test to verify it currently passes (this task adds both code and test together, per the extraction shape above)**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripStoreTest"`
Expected: PASS, 3 tests green. (If `decodeTrip`/`encode` are not yet `internal`, this fails with
an access error — confirm Step 5's visibility change landed.)

- [ ] **Step 8: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL — this is the check that catches a stray JVM-only type leaking into
`commonMain`, per `detour-shared-core` §7.

- [ ] **Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/TripStoreTest.kt
git commit -m "feat(shared): add DrivingStats to Trip, extract TripStore.decodeTrip"
```

---

### Task 2: `HardEventDetector` (shared) + `TripTrackingService` wiring

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/HardEventDetector.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/HardEventDetectorTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `HardEventDetector.SpeedState`, `HardEventDetector.SpeedResult(state, hardBrake: Boolean, hardAccel: Boolean)`, `fun onSpeedFix(state: SpeedState, speedMps: Double, fixMs: Long): SpeedResult`. `HardEventDetector.HeadingState`, `fun onHeadingFix(state: HeadingState, headingDeg: Double, speedMps: Double, fixMs: Long): Pair<HeadingState, Boolean>` (Boolean = a new corner event fired). `fun onLeanSample(corneringNow: Boolean, leanDeg: Double): Pair<Boolean, Boolean>` (first = now cornering, second = new event fired). Consumed by Task 3-5's finalize step and by later tasks' `TripStats` field additions.

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/HardEventDetectorTest.kt`:

```kotlin
package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Characterises [HardEventDetector] — GPS Δv/Δt brake/accel bands and
 *  heading-rate/lean-angle corner bands, all clock-free (timestamps passed
 *  in, per detour-shared-core's rule for path-dependent logic). */
class HardEventDetectorTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun firstFixNeverTriggersEitherEvent() {
        val result = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0)
        assertFalse(result.hardBrake)
        assertFalse(result.hardAccel)
    }

    @Test
    fun aSuddenSpeedDropOverOneSecondIsAHardBrake() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        // 20 -> 15 m/s in 1s = -5 m/s^2, past HARD_BRAKE_MPS2 (-3.4).
        val result = HardEventDetector.onSpeedFix(state, 15.0, t0 + 1000)
        assertTrue(result.hardBrake)
        assertFalse(result.hardAccel)
    }

    @Test
    fun aSuddenSpeedGainOverOneSecondIsAHardAccel() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 10.0, t0).state
        // 10 -> 14 m/s in 1s = +4 m/s^2, past HARD_ACCEL_MPS2 (2.9).
        val result = HardEventDetector.onSpeedFix(state, 14.0, t0 + 1000)
        assertFalse(result.hardBrake)
        assertTrue(result.hardAccel)
    }

    @Test
    fun aGentleSpeedChangeTriggersNeither() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        val result = HardEventDetector.onSpeedFix(state, 19.0, t0 + 1000)
        assertFalse(result.hardBrake)
        assertFalse(result.hardAccel)
    }

    @Test
    fun aFixPairFasterThanTheDtFloorIsIgnoredEvenThoughTheRateWouldFire() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 20.0, t0).state
        // 20 -> 0 m/s over 0.1s is -200 m/s^2 — would fire many times over on rate
        // alone, but 0.1s is under MIN_DT_SEC (0.2), so the pair is rejected.
        val result = HardEventDetector.onSpeedFix(state, 0.0, t0 + 100)
        assertFalse(result.hardBrake)
    }

    @Test
    fun aBatchedFixPairSlowerThanTheDtCeilingIsIgnoredEvenThoughTheRateWouldFire() {
        val state = HardEventDetector.onSpeedFix(HardEventDetector.SpeedState(), 60.0, t0).state
        // 60 -> 0 m/s over 16s is -3.75 m/s^2, past HARD_BRAKE_MPS2 (-3.4) on rate
        // alone, but 16s is over MAX_DT_SEC (15) — a batched idle fix pair, not a
        // real brake.
        val result = HardEventDetector.onSpeedFix(state, 0.0, t0 + 16_000)
        assertFalse(result.hardBrake)
    }

    @Test
    fun sustainedCorneringCountsOneEventNotOnePerFix() {
        var state = HardEventDetector.HeadingState()
        val (s1, fired1) = HardEventDetector.onHeadingFix(state, 0.0, 10.0, t0)
        state = s1
        assertFalse(fired1) // no prior heading yet
        // 0 -> 30 deg in 1s = 30 deg/s, past HARD_CORNER_DEG_PER_SEC (25).
        val (s2, fired2) = HardEventDetector.onHeadingFix(state, 30.0, 10.0, t0 + 1000)
        state = s2
        assertTrue(fired2)
        // Still turning fast the very next fix: same corner, not a new event.
        val (s3, fired3) = HardEventDetector.onHeadingFix(state, 60.0, 10.0, t0 + 2000)
        assertFalse(fired3)
    }

    @Test
    fun corneringBelowMinSpeedNeverFiresEvenWithABigHeadingSwing() {
        val state = HardEventDetector.HeadingState(lastHeadingDeg = 0.0, lastFixMs = t0)
        // A 90 deg swing at 2 m/s (parking maneuver), below MIN_CORNER_SPEED_MPS (5.0).
        val (_, fired) = HardEventDetector.onHeadingFix(state, 90.0, 2.0, t0 + 1000)
        assertFalse(fired)
    }

    @Test
    fun anUnmeasurableFixMidCornerDoesNotResetTheLatchToDoubleCount() {
        // Speed dips below MIN_CORNER_SPEED_MPS for one fix in the middle of a
        // sustained corner (e.g. flapping around the gate), then recovers. The
        // dip must NOT clear corneringNow, or the recovery re-fires as a "new"
        // corner that is really the same one.
        var state = HardEventDetector.HeadingState()
        val (s1, _) = HardEventDetector.onHeadingFix(state, 0.0, 10.0, t0)
        state = s1
        val (s2, fired2) = HardEventDetector.onHeadingFix(state, 30.0, 10.0, t0 + 1000) // fires
        state = s2
        assertTrue(fired2)
        // Slow fix: below MIN_CORNER_SPEED_MPS, unmeasurable — must not clear the latch.
        val (s3, fired3) = HardEventDetector.onHeadingFix(state, 45.0, 2.0, t0 + 1500)
        state = s3
        assertFalse(fired3)
        // Back above speed, still turning fast: same corner, must not re-fire.
        val (_, fired4) = HardEventDetector.onHeadingFix(state, 75.0, 10.0, t0 + 2000)
        assertFalse(fired4)
    }

    @Test
    fun headingWraparoundIsTheShortWayRoundNotTheLongWay() {
        // 359 -> 5 deg is a 6 deg swing the short way, not 354 the long way.
        // Over 0.1s that is 60 deg/s either way, so use 1s: 6 deg/s (below
        // threshold) versus 354 deg/s (grossly above) — the two readings this
        // bug would conflate.
        val state = HardEventDetector.HeadingState(lastHeadingDeg = 359.0, lastFixMs = t0)
        val (_, fired) = HardEventDetector.onHeadingFix(state, 5.0, 10.0, t0 + 1000)
        assertFalse(fired) // 6 deg/s, well under HARD_CORNER_DEG_PER_SEC (25)
    }

    @Test
    fun leanBandingFiresOnceUntilItDropsBelowThreshold() {
        val (cornering1, fired1) = HardEventDetector.onLeanSample(false, 45.0)
        assertTrue(cornering1); assertTrue(fired1)
        val (cornering2, fired2) = HardEventDetector.onLeanSample(cornering1, 50.0)
        assertTrue(cornering2); assertFalse(fired2) // still in the same corner
        val (cornering3, _) = HardEventDetector.onLeanSample(cornering2, 10.0)
        assertFalse(cornering3) // upright again
        val (_, fired4) = HardEventDetector.onLeanSample(cornering3, 42.0)
        assertTrue(fired4) // a second, distinct corner
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.HardEventDetectorTest"`
Expected: FAIL — `HardEventDetector` does not exist yet.

- [ ] **Step 3: Write `HardEventDetector`**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/drive/HardEventDetector.kt`:

```kotlin
package com.jellemax.detour.drive

import kotlin.math.abs

/**
 * GPS-speed-delta brake/accel detection and heading-rate/lean-angle corner
 * detection for maxke24/Detour#61. Orientation-independent by design — the
 * car IMU isn't trusted (phone slides in a cradle, see `TravelMode.kt`'s
 * `tracksGForce` KDoc), so brake/accel comes from consecutive GPS speeds
 * rather than the accelerometer.
 *
 * All thresholds are provisional defaults (#61's own open question — no
 * recorded-trip data exists yet to calibrate against).
 *
 * Clock-free: every function takes its timestamps as parameters, so it is
 * testable without a fake clock and portable to `commonTest`'s JVM/Native
 * targets.
 */
object HardEventDetector {
    const val HARD_BRAKE_MPS2 = -3.4 // ~0.35g, provisional
    const val HARD_ACCEL_MPS2 = 2.9  // ~0.30g, provisional
    const val HARD_CORNER_DEG_PER_SEC = 25.0 // car heading-rate, provisional
    const val HARD_CORNER_LEAN_DEG = 40.0    // moto, provisional
    const val MIN_CORNER_SPEED_MPS = 5.0     // provisional — below this a heading
                                              // swing is a parking maneuver, not a
                                              // corner
    private const val MIN_DT_SEC = 0.2
    private const val MAX_DT_SEC = 15.0 // a batched/stale fix pair, not a real delta

    data class SpeedState(val lastSpeedMps: Double? = null, val lastFixMs: Long = 0L)
    data class SpeedResult(val state: SpeedState, val hardBrake: Boolean, val hardAccel: Boolean)

    /** GPS Δv/Δt between consecutive fixes. */
    fun onSpeedFix(state: SpeedState, speedMps: Double, fixMs: Long): SpeedResult {
        val prevSpeed = state.lastSpeedMps
        val next = SpeedState(speedMps, fixMs)
        if (prevSpeed == null) return SpeedResult(next, false, false)
        val dtSec = (fixMs - state.lastFixMs) / 1000.0
        if (dtSec < MIN_DT_SEC || dtSec > MAX_DT_SEC) return SpeedResult(next, false, false)
        val accelMps2 = (speedMps - prevSpeed) / dtSec
        return SpeedResult(next, accelMps2 <= HARD_BRAKE_MPS2, accelMps2 >= HARD_ACCEL_MPS2)
    }

    data class HeadingState(
        val lastHeadingDeg: Double? = null,
        val lastFixMs: Long = 0L,
        val corneringNow: Boolean = false,
    )

    /** Heading-rate corner detection (car). [corneringNow] gives hysteresis so a
     *  sustained turn counts as one corner, not one event per fix inside it. An
     *  unmeasurable fix (too slow, no prior heading, or a dt outside the guard
     *  band) must NOT clear the latch — only update [HeadingState.lastHeadingDeg]/
     *  [HeadingState.lastFixMs] and leave [HeadingState.corneringNow] as it was,
     *  otherwise a corner that dips through the guard mid-turn (e.g. speed
     *  flapping around [MIN_CORNER_SPEED_MPS]) re-fires as a second event. */
    fun onHeadingFix(
        state: HeadingState,
        headingDeg: Double,
        speedMps: Double,
        fixMs: Long,
    ): Pair<HeadingState, Boolean> {
        val prevHeading = state.lastHeadingDeg
        if (speedMps < MIN_CORNER_SPEED_MPS || prevHeading == null) {
            return state.copy(lastHeadingDeg = headingDeg, lastFixMs = fixMs) to false
        }
        val dtSec = (fixMs - state.lastFixMs) / 1000.0
        if (dtSec < MIN_DT_SEC || dtSec > MAX_DT_SEC) {
            return state.copy(lastHeadingDeg = headingDeg, lastFixMs = fixMs) to false
        }
        var diff = abs(headingDeg - prevHeading) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        val above = (diff / dtSec) >= HARD_CORNER_DEG_PER_SEC
        val newEvent = above && !state.corneringNow
        return HeadingState(headingDeg, fixMs, above) to newEvent
    }

    /** Moto: bands the existing per-sample lean stream
     *  (`TripTrackingService.recordLean`'s `deg`). Same hysteresis shape as
     *  [onHeadingFix]; the caller threads [corneringNow] itself since the lean
     *  pipeline already holds its own per-trip mutable state. */
    fun onLeanSample(corneringNow: Boolean, leanDeg: Double): Pair<Boolean, Boolean> {
        val above = abs(leanDeg) >= HARD_CORNER_LEAN_DEG
        val newEvent = above && !corneringNow
        return above to newEvent
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.HardEventDetectorTest"`
Expected: PASS, 8 tests green.

- [ ] **Step 5: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit the shared unit alone**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/HardEventDetector.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/HardEventDetectorTest.kt
git commit -m "feat(shared): add HardEventDetector for brake/accel/corner stats"
```

- [ ] **Step 7: Wire into `TripTrackingService` — new state fields**

Modify `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`. Add import:

```kotlin
import com.jellemax.detour.drive.HardEventDetector
```

Add new private fields after `leanOffsetDeg` (line 400), before `freshBoardTelemetry` (line 414).
`@Volatile`, matching every neighboring field this file already marks that way
(`currentLeanDeg`, `maxLeanDeg`, `currentG`, `maxG`, `segmentPeakLeanDeg`, `leanTracked`) because
they cross the same sensor-thread/location-thread boundary these do:

```kotlin
    @Volatile private var speedEventState = HardEventDetector.SpeedState()
    @Volatile private var headingEventState = HardEventDetector.HeadingState()
    /** Threaded into [HardEventDetector.onLeanSample] from [recordLean] — a
     *  car trip never calls it, so it only ever moves for a moto trip. */
    @Volatile private var leanCorneringNow = false
```

- [ ] **Step 8: Reset the new state in `beginTrip`**

Modify `beginTrip` (around line 782-784) — add after `currentG = 1.0; maxG = 0.0`:

```kotlin
        speedEventState = HardEventDetector.SpeedState()
        headingEventState = HardEventDetector.HeadingState()
        leanCorneringNow = false
```

- [ ] **Step 9: Count moto corner events in `recordLean`**

Modify `recordLean` (lines 432-439) to also count corner events:

```kotlin
    private fun recordLean(deg: Double) {
        if (abs(deg) > MAX_PLAUSIBLE_LEAN_DEG) return
        // Below riding speed, "lean" is steering-head rake, not the bike
        // actually leaning — see MIN_LEAN_SPEED_MPS.
        if ((_stats.value?.currentSpeedMps ?: 0.0) < MIN_LEAN_SPEED_MPS) return
        maxLeanDeg = maxOf(maxLeanDeg, abs(deg))
        if (abs(deg) > abs(segmentPeakLeanDeg)) segmentPeakLeanDeg = deg
        val (cornering, newEvent) = HardEventDetector.onLeanSample(leanCorneringNow, deg)
        leanCorneringNow = cornering
        if (newEvent) hardCornerCount++
    }
```

Add all three counters as new private fields next to the ones from Step 7 — declare them here,
once, even though `hardBrakeCount`/`hardAccelCount` aren't incremented until Step 10, so there is
exactly one declaration site for each. `@Volatile` for the same cross-thread reason as Step 7's
fields — `hardCornerCount` is incremented from both the sensor thread (via `recordLean`, this
step) and the location thread (via `onTripLocation`, Step 10):

```kotlin
    @Volatile private var hardCornerCount = 0
    @Volatile private var hardBrakeCount = 0
    @Volatile private var hardAccelCount = 0
```

Reset all three in `beginTrip` alongside Step 8's additions:

```kotlin
        hardCornerCount = 0
        hardBrakeCount = 0
        hardAccelCount = 0
```

All three are finalized into `DrivingStats` in `endTrip` — see Task 6, Step 7.

- [ ] **Step 10: Count brake/accel events and car corner events in `onTripLocation`**

Modify `onTripLocation` (`:1048-1116`) — insert after `effectiveSpeedMps` is computed
(after line 1103, before the `_stats.update { ... }` block).

**Use `location.time`, not `now`, as the timestamp passed to `HardEventDetector`.** `now =
System.currentTimeMillis()` (declared at the top of this function) is when this callback was
*delivered*; `location.time` is when the fix was actually *taken*. A Δv/Δt calculation is a
physical rate over the interval between two fixes, so it must use fix time — delivery jitter
between two fixes (which is real: fused-provider callbacks don't arrive at perfectly even
intervals) directly scales the computed acceleration and can push a gentle real deceleration over
the hard-brake threshold or vice versa. This matches the house pattern already in this function:
the distance-accumulation gate a few lines above uses `location.time - last.time`, not a wall-clock
delta, for exactly this reason:

```kotlin
        val speedResult = HardEventDetector.onSpeedFix(speedEventState, effectiveSpeedMps, location.time)
        speedEventState = speedResult.state
        if (speedResult.hardBrake) hardBrakeCount++
        if (speedResult.hardAccel) hardAccelCount++
        if (stats.mode == TravelMode.CAR && location.hasBearing()) {
            val (nextHeadingState, cornerEvent) = HardEventDetector.onHeadingFix(
                headingEventState, location.bearing.toDouble(), effectiveSpeedMps, location.time)
            headingEventState = nextHeadingState
            if (cornerEvent) hardCornerCount++
        }
```

`hardBrakeCount`/`hardAccelCount` are already declared in Step 9 — no new field here.

Extend the `_stats.update { }` block (lines 1106-1113) to publish the running counts:

```kotlin
        _stats.update {
            it?.copy(
                durationMs = now - it.startTimeMs,
                distanceMeters = distance,
                currentSpeedMps = effectiveSpeedMps,
                topSpeedMps = maxOf(it.topSpeedMps, effectiveSpeedMps),
                hardBrakeCount = hardBrakeCount,
                hardAccelCount = hardAccelCount,
                hardCornerCount = hardCornerCount,
            )
        }
```

- [ ] **Step 11: Add the three live fields to `TripStats`**

Modify the `TripStats` data class (`:78-91`) — add after `maxGForce`:

```kotlin
    val hardBrakeCount: Int = 0,
    val hardAccelCount: Int = 0,
    val hardCornerCount: Int = 0,
```

- [ ] **Step 12: Compile and confirm no regression**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests still pass (this wiring has no new unit test —
see Global Constraints).

- [ ] **Step 13: Commit the wiring**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): wire HardEventDetector into the live trip pipeline"
```

---

### Task 3: `StopDetector` (shared) + wiring

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/StopDetector.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/StopDetectorTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Produces: `StopDetector.State(candidateSince: Long?, stopCount: Int, idleMs: Long, hasMoved: Boolean)`,
  `fun onFix(state: State, speedMps: Double, fixMs: Long): State`.

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/StopDetectorTest.kt`:

```kotlin
package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals

/** Characterises [StopDetector] — a mid-trip pause/resume the existing
 *  auto-stop machinery in TripTrackingService never computes, since that
 *  only detects a stop long enough to *end* the trip. */
class StopDetectorTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun aDwellBeforeTheTripHasEverMovedIsNeverCountedNoMatterHowLong() {
        // A manually-started trip begun while parked (rider taps Go, sits for a
        // minute, then rides off) must not count that pre-departure dwell as a
        // stop — the trip hasn't gone anywhere yet, so there is nothing to have
        // paused. `beginTrip` resets to a fresh State() every trip, so this is
        // the state every trip actually starts in.
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 0.0, t0) // parked at trip start
        state = StopDetector.onFix(state, 10.0, t0 + 60_000) // finally pulls away
        assertEquals(0, state.stopCount)
        assertEquals(0L, state.idleMs)
    }

    @Test
    fun briefDwellUnderTheMinimumIsNotCountedAsAStop() {
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 10.0, t0) // trip is already moving
        state = StopDetector.onFix(state, 0.0, t0 + 5_000) // stopped
        // Resumes after 10s, under MIN_STOP_DWELL_MS (20s) — a traffic light.
        state = StopDetector.onFix(state, 10.0, t0 + 15_000)
        assertEquals(0, state.stopCount)
        assertEquals(0L, state.idleMs)
    }

    @Test
    fun dwellPastTheMinimumCountsAsAStopAndAccumulatesIdleTime() {
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 10.0, t0) // trip is already moving
        state = StopDetector.onFix(state, 0.0, t0 + 5_000)
        // Resumes after 45s, past MIN_STOP_DWELL_MS (20s) — a real stop.
        state = StopDetector.onFix(state, 10.0, t0 + 50_000)
        assertEquals(1, state.stopCount)
        assertEquals(45_000L, state.idleMs)
    }

    @Test
    fun aStopThatNeverResumesWithinTheTripIsNotCounted() {
        // The trip ends while still stopped (engine off) — endTrip's own logic
        // owns that boundary, not this detector. Documented limitation: a stop
        // is only counted once motion resumes within the same trip.
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 10.0, t0) // trip is already moving
        state = StopDetector.onFix(state, 0.0, t0 + 5_000)
        state = StopDetector.onFix(state, 0.0, t0 + 65_000)
        assertEquals(0, state.stopCount)
        assertEquals(0L, state.idleMs)
    }

    @Test
    fun multipleStopsAccumulateIndependently() {
        var state = StopDetector.State()
        state = StopDetector.onFix(state, 10.0, t0) // trip is already moving
        state = StopDetector.onFix(state, 0.0, t0 + 5_000)
        state = StopDetector.onFix(state, 10.0, t0 + 35_000) // stop 1: 30s
        state = StopDetector.onFix(state, 0.0, t0 + 45_000)
        state = StopDetector.onFix(state, 10.0, t0 + 105_000) // stop 2: 60s
        assertEquals(2, state.stopCount)
        assertEquals(90_000L, state.idleMs)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.StopDetectorTest"`
Expected: FAIL — `StopDetector` does not exist yet.

- [ ] **Step 3: Write `StopDetector`**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/drive/StopDetector.kt`:

```kotlin
package com.jellemax.detour.drive

/**
 * Mid-trip pause/resume detection for maxke24/Detour#61. The existing
 * `pendingStopAtMs`/`STATIONARY_END_MS` machinery in `TripTrackingService`
 * only detects a stop long enough to *end* the trip; this detects a stop
 * that resumes within the same trip (a fuel stop on a manually-tracked
 * drive). Clock-free: timestamps are parameters.
 */
object StopDetector {
    const val STOP_SPEED_FLOOR_MPS = 2.0   // matches onTripLocation's own moving floor
                                            // (note: that floor tests raw `speed`, not
                                            // effectiveSpeedMps, and is a `>` gate where
                                            // this is `<`, so exactly 2.0 classifies
                                            // oppositely in the two places — harmless)
    const val MIN_STOP_DWELL_MS = 20_000L  // provisional — filters a traffic light

    data class State(
        val candidateSince: Long? = null,
        val stopCount: Int = 0,
        val idleMs: Long = 0,
        /** True once this trip has recorded at least one above-floor fix. A
         *  manually-started trip begins parked (`beginTrip` resets to a fresh
         *  [State]), and the pre-departure dwell before the rider pulls away
         *  is not a stop — there is nothing to have paused yet. Without this
         *  guard the very first fix (parked, speed 0) opens a candidate and
         *  the first fix on pulling away resolves it as a real stop. */
        val hasMoved: Boolean = false,
    )

    fun onFix(state: State, speedMps: Double, fixMs: Long): State {
        if (speedMps < STOP_SPEED_FLOOR_MPS) {
            if (!state.hasMoved) return state // parked before the trip has moved at all
            return state.copy(candidateSince = state.candidateSince ?: fixMs)
        }
        val moved = if (state.hasMoved) state else state.copy(hasMoved = true)
        val since = moved.candidateSince ?: return moved
        val dwell = fixMs - since
        return if (dwell >= MIN_STOP_DWELL_MS) {
            moved.copy(candidateSince = null, stopCount = moved.stopCount + 1, idleMs = moved.idleMs + dwell)
        } else {
            moved.copy(candidateSince = null)
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.StopDetectorTest"`
Expected: PASS, 5 tests green.

- [ ] **Step 5: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit the shared unit alone**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/StopDetector.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/StopDetectorTest.kt
git commit -m "feat(shared): add StopDetector for mid-trip stop/idle stats"
```

- [ ] **Step 7: Wire into `TripTrackingService`**

Add import:

```kotlin
import com.jellemax.detour.drive.StopDetector
```

Add a new private field alongside Task 2's additions (after line 400). `@Volatile`, same
cross-thread reasoning as Task 2's fields — read from the location thread in `onTripLocation` and
published into `_stats` from there, but this file's convention marks every field crossing that
boundary regardless of which specific threads are provably involved:

```kotlin
    @Volatile private var stopState = StopDetector.State()
```

Reset in `beginTrip`, alongside the other resets:

```kotlin
        stopState = StopDetector.State()
```

Tick it in `onTripLocation`, right after the hard-event block from Task 2 Step 10. Use
`location.time`, not `now` — same reasoning as Task 2's `HardEventDetector` calls: a dwell
*duration* is a physical time interval, not a delivery-time bookkeeping value:

```kotlin
        stopState = StopDetector.onFix(stopState, effectiveSpeedMps, location.time)
```

Extend the `_stats.update { }` block (already touched in Task 2 Step 10) with one more field:

```kotlin
                stopCount = stopState.stopCount,
```

Add `stopCount: Int = 0` to `TripStats` (`:78-91`, next to `hardCornerCount` from Task 2 Step 11).

- [ ] **Step 8: Compile and confirm no regression**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit the wiring**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): wire StopDetector into the live trip pipeline"
```

---

### Task 4: Trip-scoped `SpeedLimitTracker` + time-over-limit accumulation

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

No new shared code — `SpeedLimitTracker` (`shared/.../drive/SpeedLimitTracker.kt`) already exists
and is reused as-is. This task only adds a **second, trip-scoped instance** of its `State`,
independent of `MapScreen`'s and `SpinScreen`'s UI copies.

**Interfaces:**
- Consumes: `SpeedLimitTracker.State`, `.needsWays`, `.fetchStarted`, `.withWays`, `.onFix` (all
  pre-existing, unchanged). `RoadRoulette.speedLimitWays(center: LatLon): List<SpeedLimitWay>` (suspend, pre-existing).
- Produces: `secondsOverLimit: Long` and `pctOverLimit: Double`, finalized into `DrivingStats` in Task 6.

- [ ] **Step 1: Add imports**

```kotlin
import com.jellemax.detour.drive.SpeedLimitTracker
```

(`RoadRoulette` is already imported at line 57.)

- [ ] **Step 2: Add trip-scoped state, a fetch job, and the accumulator**

Add after `stopState` (from Task 3 Step 7). `@Volatile` on the data fields, matching this file's
convention for anything read from the location thread and published into `_stats` — the `Job`
reference doesn't need it (coroutine `Job` cancellation/`isActive` already handle their own
visibility):

```kotlin
    @Volatile private var tripLimitState = SpeedLimitTracker.State()
    private var tripLimitFetchJob: kotlinx.coroutines.Job? = null
    @Volatile private var secondsOverLimit = 0.0
    @Volatile private var lastLimitFixMs = 0L
```

`OVER_LIMIT_MARGIN` goes with the other tuning constants in the companion object
(after `MUNICIPALITY_LOOKUP_COOLDOWN_MS`, line 218):

```kotlin
        /** 10% over the posted limit, provisional — a floor against GPS/rounding
         *  noise reading a steady legal speed as a violation. */
        private const val OVER_LIMIT_MARGIN = 1.10
```

- [ ] **Step 3: Reset in `beginTrip`**

```kotlin
        tripLimitState = SpeedLimitTracker.State()
        tripLimitFetchJob?.cancel()
        tripLimitFetchJob = null
        secondsOverLimit = 0.0
        lastLimitFixMs = startTimeMs
```

- [ ] **Step 4: Tick it in `onTripLocation`**

Insert after the `StopDetector` line from Task 3 Step 7, following the same "split in five"
ordering `SpeedLimitTracker.kt`'s KDoc prescribes and the shape `SpinScreen.kt:263-273` already
uses (fetch off the collector, in-flight guard on the `Job`):

```kotlin
        val here = LatLon(location.latitude, location.longitude)
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
        if (effectiveSpeedMps >= SpeedLimitTracker.MIN_MPS &&
            SpeedLimitTracker.needsWays(tripLimitState, here, now) &&
            tripLimitFetchJob?.isActive != true
        ) {
            tripLimitState = SpeedLimitTracker.fetchStarted(tripLimitState, now)
            // serviceScope is already Dispatchers.IO (`:1266`), so no withContext needed here.
            tripLimitFetchJob = serviceScope.launch {
                val ways = runCatching { RoadRoulette.speedLimitWays(here) }.getOrDefault(emptyList())
                tripLimitState = SpeedLimitTracker.withWays(tripLimitState, ways, here)
            }
        }
        tripLimitState = SpeedLimitTracker.onFix(tripLimitState, here, bearing, effectiveSpeedMps)
        val limitKmh = tripLimitState.limitKmh
        if (limitKmh != null && effectiveSpeedMps * 3.6 > limitKmh * OVER_LIMIT_MARGIN) {
            secondsOverLimit += (now - lastLimitFixMs) / 1000.0
        }
        lastLimitFixMs = now
```

`here` may already exist as a local in this function under a different name — if so, reuse the
existing `LatLon` local instead of re-declaring it; check the surrounding code for a
`LatLon(location.latitude, location.longitude)` construction already in scope (there is one at
`:1061`, inside the `if (location.accuracy <= 50f)` block, which is not guaranteed to run every
fix) and prefer this task's own unconditional `here` if that one is conditionally scoped.

Confirm `serviceScope` (a `CoroutineScope(SupervisorJob() + Dispatchers.Main)` or similar) already
exists as a field on this class — the imports at lines 65-70 (`CoroutineScope`, `SupervisorJob`,
`Dispatchers`, `launch`) indicate one does; grep for its declaration
(`grep -n "CoroutineScope(" app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`)
and reuse it rather than creating a second one.

- [ ] **Step 5: Cancel the job in `endTrip`**

Modify `endTrip` (`:798`) — add right after `stopMotionSensors()`:

```kotlin
        tripLimitFetchJob?.cancel()
        tripLimitFetchJob = null
```

- [ ] **Step 6: Compile and confirm no regression**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): accumulate time-over-limit via a trip-scoped SpeedLimitTracker"
```

---

### Task 5: `RoadTypeTracker` (shared) + wiring

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt` (visibility only)
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/RoadTypeTracker.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/RoadTypeTrackerTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `RoadRoulette.distanceMeters`, `.distanceToSegmentMeters` (already public), and
  `.DRIVABLE_HIGHWAYS`, `.MAX_SNAP_METERS`, `.HEADING_TOLERANCE_DEG`, `.alignsWith` (promoted to
  `internal` in Step 1 below). `HighwayClass.of` (Task 1).
- Produces: `RoadTypeTracker.State(ways, waysCenter, lastFetchMs, meters: Map<HighwayClass, Double>)`,
  `.needsWays`, `.fetchStarted`, `suspend fun fetchWays(center: LatLon): List<ClassifiedWay>`,
  `.withWays`, `fun onFix(state, at, headingDeg, distanceSinceLastFixMeters): State`.

- [ ] **Step 1: Promote four `RoadRoulette` members to `internal` (pure visibility, no behavior change)**

Modify `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt`:

Line 181: `private const val DRIVABLE_HIGHWAYS` → `internal const val DRIVABLE_HIGHWAYS`
Line 186: `private const val MAX_SNAP_METERS` → `internal const val MAX_SNAP_METERS`
Line 190: `private const val HEADING_TOLERANCE_DEG` → `internal const val HEADING_TOLERANCE_DEG`
Line 340: `private fun alignsWith` → `internal fun alignsWith`

This is the `detour-shared-core` §2 "written more than once earns the core" case: `RoadTypeTracker`
needs the identical drivable-highway filter and heading-alignment check `SpeedLimitTracker`'s fetch
already uses, so the visibility widens rather than duplicating the logic.

- [ ] **Step 2: Confirm nothing else changed**

Run: `git diff shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt`
Expected: exactly four `-private` / `+internal` line pairs, nothing else.

Run: `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests (including `SpeedLimitTrackerTest`, `ParsingTest`)
still pass unchanged.

- [ ] **Step 3: Commit the visibility change alone**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt
git commit -m "refactor(shared): widen RoadRoulette snap helpers to internal for reuse"
```

- [ ] **Step 4: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/RoadTypeTrackerTest.kt`, mirroring
`SpeedLimitTrackerTest.kt`'s fixture-building style (`RoadRoulette.offset` for placing points at a
bearing/distance from a center):

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterises [RoadTypeTracker]'s snap-to-class — the road-type-mix stat
 *  (maxke24/Detour#61), a sibling of [SpeedLimitTracker] querying `highway`
 *  tags instead of `maxspeed`, so untagged residential streets aren't
 *  undercounted (see the design doc's §4 for why the two fetches must stay
 *  separate). */
class RoadTypeTrackerTest {

    private val here = LatLon(50.85, 4.35)

    private fun at(meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(here, meters, bearingDeg * PI / 180.0)

    private val motorway = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.MOTORWAY,
        points = listOf(at(400.0, 180.0), at(400.0, 0.0)), // north-south through `here`
    )
    private val local = RoadTypeTracker.ClassifiedWay(
        highwayClass = HighwayClass.LOCAL,
        points = listOf(at(1000.0, 90.0), at(1000.0, 270.0)), // east-west, far away
    )

    @Test
    fun snapsToTheAlignedNearbyWayOverAFarAwayOne() {
        val state = RoadTypeTracker.State(ways = listOf(motorway, local))
        val next = RoadTypeTracker.onFix(state, here, headingDeg = 0.0, distanceSinceLastFixMeters = 25.0)
        assertEquals(25.0, next.meters[HighwayClass.MOTORWAY])
        assertNull(next.meters[HighwayClass.LOCAL])
    }

    @Test
    fun distanceAccumulatesAcrossMultipleFixesOnTheSameClass() {
        var state = RoadTypeTracker.State(ways = listOf(motorway))
        state = RoadTypeTracker.onFix(state, here, 0.0, 25.0)
        state = RoadTypeTracker.onFix(state, here, 0.0, 30.0)
        assertEquals(55.0, state.meters[HighwayClass.MOTORWAY])
    }

    @Test
    fun aFixWithNoNearbyWayLeavesMetersUnchanged() {
        val state = RoadTypeTracker.State(ways = emptyList())
        val next = RoadTypeTracker.onFix(state, here, 0.0, 25.0)
        assertEquals(emptyMap(), next.meters)
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.RoadTypeTrackerTest"`
Expected: FAIL — `RoadTypeTracker` does not exist yet.

- [ ] **Step 6: Write `RoadTypeTracker`**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/drive/RoadTypeTracker.kt`:

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.jsonObjectOf
import com.jellemax.detour.data.objects
import com.jellemax.detour.data.optArray
import com.jellemax.detour.data.optDouble
import com.jellemax.detour.data.optObject
import com.jellemax.detour.data.optString
import okio.IOException

/**
 * Road-type-mix accumulation for maxke24/Detour#61 — a sibling of
 * `SpeedLimitTracker`, not a reuse of its fetch: that one's Overpass query
 * filters on `["maxspeed"]`, which would undercount every untagged
 * residential street. This queries `["highway"]` alone.
 *
 * Same `State`/`needsWays`/`fetchStarted`/`withWays`/`onFix` shape as
 * `SpeedLimitTracker`, clock-free.
 */
object RoadTypeTracker {
    const val FETCH_RADIUS_M = 1500.0
    const val FETCH_MARGIN_M = 500.0
    const val FETCH_THROTTLE_MS = 10_000L

    data class ClassifiedWay(val highwayClass: HighwayClass, val points: List<LatLon>)

    data class State(
        val ways: List<ClassifiedWay> = emptyList(),
        val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L,
        val meters: Map<HighwayClass, Double> = emptyMap(),
    )

    fun needsWays(state: State, at: LatLon, nowMs: Long): Boolean {
        val fromCenter = state.waysCenter?.let { RoadRoulette.distanceMeters(it, at) } ?: Double.MAX_VALUE
        return fromCenter > FETCH_RADIUS_M - FETCH_MARGIN_M && nowMs - state.lastFetchMs > FETCH_THROTTLE_MS
    }

    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    suspend fun fetchWays(center: LatLon, radiusMeters: Double = FETCH_RADIUS_M): List<ClassifiedWay> {
        val query = "[out:json][timeout:15];" +
            "way(around:${radiusMeters.toInt()},${center.lat},${center.lon})" +
            "[\"highway\"~\"^(${RoadRoulette.DRIVABLE_HIGHWAYS})$\"];" +
            "out tags geom;"
        val json = try {
            RoadRoulette.rawQuery(query)
        } catch (e: IOException) {
            return emptyList()
        }
        val elements = jsonObjectOf(json).optArray("elements") ?: return emptyList()
        val ways = ArrayList<ClassifiedWay>(elements.size)
        for (el in elements.objects()) {
            val tag = el.optObject("tags")?.optString("highway")?.takeIf { it.isNotBlank() } ?: continue
            val cls = HighwayClass.of(tag) ?: continue
            val geometry = el.optArray("geometry") ?: continue
            val pts = geometry.objects().map { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
            if (pts.size >= 2) ways.add(ClassifiedWay(cls, pts))
        }
        return ways
    }

    fun withWays(state: State, ways: List<ClassifiedWay>, center: LatLon): State =
        if (ways.isEmpty()) state else state.copy(ways = ways, waysCenter = center)

    /** Snaps [at] to the nearest/aligned classified way (same two-pass logic as
     *  `RoadRoulette.snapSpeedLimitKmh`) and attributes [distanceSinceLastFixMeters]
     *  to its class. A fix that matches nothing leaves [State.meters] unchanged. */
    fun onFix(
        state: State,
        at: LatLon,
        headingDeg: Double?,
        distanceSinceLastFixMeters: Double,
    ): State {
        var aligned: HighwayClass? = null
        var alignedDist = Double.MAX_VALUE
        var nearest: HighwayClass? = null
        var nearestDist = Double.MAX_VALUE
        for (way in state.ways) {
            for (j in 0 until way.points.size - 1) {
                val a = way.points[j]
                val b = way.points[j + 1]
                val d = RoadRoulette.distanceToSegmentMeters(at, a, b)
                if (d > RoadRoulette.MAX_SNAP_METERS) continue
                if (d < nearestDist) { nearestDist = d; nearest = way.highwayClass }
                if (headingDeg != null && d < alignedDist && RoadRoulette.alignsWith(a, b, headingDeg)) {
                    alignedDist = d; aligned = way.highwayClass
                }
            }
        }
        val cls = aligned ?: nearest ?: return state
        val updated = state.meters.toMutableMap()
        updated[cls] = (updated[cls] ?: 0.0) + distanceSinceLastFixMeters
        return state.copy(meters = updated)
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.drive.RoadTypeTrackerTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 8: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL — this is the check most likely to catch a leaked JVM-only import
(e.g. `java.util` instead of `okio`/`kotlinx`), since `RoadTypeTracker` compiles fine against the
Android target either way.

- [ ] **Step 9: Commit the shared unit alone**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/RoadTypeTracker.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/RoadTypeTrackerTest.kt
git commit -m "feat(shared): add RoadTypeTracker for road-type-mix stats"
```

- [ ] **Step 10: Wire into `TripTrackingService`**

Add import:

```kotlin
import com.jellemax.detour.drive.RoadTypeTracker
```

Add fields alongside `tripLimitState` (Task 4 Step 2), same `@Volatile` reasoning:

```kotlin
    @Volatile private var roadTypeState = RoadTypeTracker.State()
    private var roadTypeFetchJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastRoadTypeFixLocation: Location? = null
```

Reset in `beginTrip`, alongside Task 4 Step 3:

```kotlin
        roadTypeState = RoadTypeTracker.State()
        roadTypeFetchJob?.cancel()
        roadTypeFetchJob = null
        lastRoadTypeFixLocation = null
```

Tick it in `onTripLocation`, right after Task 4 Step 4's block (reusing the same `here`/`now` locals):

```kotlin
        if (RoadTypeTracker.needsWays(roadTypeState, here, now) &&
            roadTypeFetchJob?.isActive != true
        ) {
            roadTypeState = RoadTypeTracker.fetchStarted(roadTypeState, now)
            // serviceScope is already Dispatchers.IO (`:1266`), so no withContext needed here.
            roadTypeFetchJob = serviceScope.launch {
                val ways = runCatching { RoadTypeTracker.fetchWays(here) }.getOrDefault(emptyList())
                roadTypeState = RoadTypeTracker.withWays(roadTypeState, ways, here)
            }
        }
        val prevRoadTypeLoc = lastRoadTypeFixLocation
        if (prevRoadTypeLoc != null && location.accuracy <= 50f) {
            val hop = prevRoadTypeLoc.distanceTo(location).toDouble()
            roadTypeState = RoadTypeTracker.onFix(roadTypeState, here, bearing, hop)
        }
        lastRoadTypeFixLocation = location
```

Cancel the job in `endTrip`, alongside Task 4 Step 5:

```kotlin
        roadTypeFetchJob?.cancel()
        roadTypeFetchJob = null
```

- [ ] **Step 11: Compile and confirm no regression**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): wire RoadTypeTracker into the live trip pipeline"
```

---

### Task 6: `Curviness.traceScore` + post-trip finalize into `DrivingStats`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoundTripPlanner.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/CurvinessTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `TripStore.decodeTrip`/`encode` are not needed here — `endTrip` builds a `Trip(...)`
  literal directly. `loadTripPoints(context: Context, trip: Trip): List<TraceStore.TracePoint>`
  (pre-existing, public, `HistoryScreen.kt:165`).
- Produces: `Curviness.traceScore(points: List<LatLon>): Double`. This task also assembles the
  final `DrivingStats(...)` from every field Tasks 1-5 have been accumulating, and passes it into
  the `Trip(...)` constructor in `endTrip` — this is where all six sub-stats meet.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/CurvinessTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterises [Curviness.traceScore] — the same 25-300m circumradius
 *  window [Curviness.score] and [Curviness.routeScore] already use, applied
 *  to a driven trace instead of an OSM way or a routed polyline. Fixtures
 *  are literal coordinates (Ghent, arbitrarily), never a real recording —
 *  per detour-trip-data §6. */
class CurvinessTest {

    private val origin = LatLon(51.05, 3.72)

    private fun at(meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(origin, meters, bearingDeg * PI / 180.0)

    @Test
    fun aStraightLineScoresZero() {
        val points = (0..20).map { at(it * 25.0, 90.0) } // due east, 25m apart
        assertEquals(0.0, Curviness.traceScore(points), absoluteTolerance = 1e-9)
    }

    @Test
    fun aTightSCurveScoresAboveZero() {
        // Alternating bearing every point traces a tight zig-zag within the
        // 25-300m radius window.
        val points = (0..20).map { i ->
            val bearing = if (i % 2 == 0) 80.0 else 100.0
            at(i * 25.0, bearing)
        }
        assertTrue(Curviness.traceScore(points) > 0.0)
    }

    @Test
    fun fewerThanThreePointsScoresZero() {
        assertEquals(0.0, Curviness.traceScore(listOf(origin, at(25.0, 90.0))))
    }

    @Test
    fun aShortTraceUnderFiveHundredMetersScoresZero() {
        val points = (0..5).map { at(it * 25.0, 90.0) } // 125m total
        assertEquals(0.0, Curviness.traceScore(points))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.CurvinessTest"`
Expected: FAIL — `Curviness.traceScore` does not exist yet.

- [ ] **Step 3: Write `Curviness.traceScore`**

Modify `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoundTripPlanner.kt` — add inside
`object Curviness` (after `routeScore`, before `markAround`, i.e. after line 97):

```kotlin
    /**
     * Same 25-300m circumradius window as [score]/[routeScore], applied to a
     * driven trace instead of an OSM way or a routed polyline (maxke24/Detour#61's
     * twistiness stat). No junction filtering: a driven trace carries neither OSM
     * node ids ([score]'s junction set) nor turn instructions ([routeScore]'s), so
     * real intersections are not excluded — this over-counts town-driving corners
     * as "twisty" relative to the other two callers. Acceptable for a
     * same-trip-to-same-trip comparison; documented rather than solved.
     */
    fun traceScore(points: List<LatLon>): Double {
        if (points.size < 3) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) total += RoadRoulette.distanceMeters(points[i], points[i + 1])
        if (total < 500) return 0.0 // too short to judge, same floor as score()

        var curvy = 0.0
        for (i in 1 until points.size - 1) {
            val r = circumradiusMeters(points[i - 1], points[i], points[i + 1])
            if (r in 25.0..300.0) {
                curvy += (RoadRoulette.distanceMeters(points[i - 1], points[i]) +
                    RoadRoulette.distanceMeters(points[i], points[i + 1])) / 2
            }
        }
        return curvy / total
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.CurvinessTest"`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit the shared unit alone**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/RoundTripPlanner.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/CurvinessTest.kt
git commit -m "feat(shared): add Curviness.traceScore for the twistiness stat"
```

- [ ] **Step 7: Assemble `DrivingStats` and finalize in `endTrip`**

Add imports:

```kotlin
import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.ui.loadTripPoints
```

Modify `endTrip` (`:798-831`). The existing body:

```kotlin
    private fun endTrip() {
        val stats = _stats.value ?: return
        val wasAuto = autoStarted
        stopMotionSensors()
        flushTrace()
        val worthSaving =
            if (wasAuto) stats.distanceMeters >= MIN_AUTO_TRIP_METERS
            else stats.durationMs > 0
        if (worthSaving) {
            TripStore.save(Trip(
                    startTimeMs = stats.startTimeMs,
                    endTimeMs = System.currentTimeMillis(),
                    distanceMeters = stats.distanceMeters,
                    topSpeedMps = stats.topSpeedMps,
                    maxLeanAngleDeg = maxLeanDeg,
                    maxGForce = maxG,
                    destinationLat = destLat,
                    destinationLon = destLon,
                    mode = stats.mode,
                ),
            )
            SyncClient.syncQuietly()
            checkBadges()
            if (wasAuto) TripEndedNotification.show(this, stats.startTimeMs)
        }
        _stats.value = null
        destLat = null
        destLon = null
        autoStarted = false
        pendingStopAtMs = null
        ensureLocationUpdates()
        updateNotification()
    }
```

Becomes:

```kotlin
    private fun endTrip() {
        val stats = _stats.value ?: return
        val wasAuto = autoStarted
        stopMotionSensors()
        tripLimitFetchJob?.cancel(); tripLimitFetchJob = null
        roadTypeFetchJob?.cancel(); roadTypeFetchJob = null
        flushTrace()
        val worthSaving =
            if (wasAuto) stats.distanceMeters >= MIN_AUTO_TRIP_METERS
            else stats.durationMs > 0
        if (worthSaving) {
            val durationSec = stats.durationMs / 1000.0
            val trip = Trip(
                startTimeMs = stats.startTimeMs,
                endTimeMs = System.currentTimeMillis(),
                distanceMeters = stats.distanceMeters,
                topSpeedMps = stats.topSpeedMps,
                maxLeanAngleDeg = maxLeanDeg,
                maxGForce = maxG,
                destinationLat = destLat,
                destinationLon = destLon,
                mode = stats.mode,
                drivingStats = DrivingStats(
                    hardBrakeCount = hardBrakeCount,
                    hardAccelCount = hardAccelCount,
                    hardCornerCount = hardCornerCount,
                    secondsOverLimit = secondsOverLimit.toLong(),
                    pctOverLimit = if (durationSec > 0) secondsOverLimit / durationSec * 100.0 else 0.0,
                    roadTypeMeters = roadTypeState.meters,
                    // Post-hoc, over the trace this trip just flushed above — see
                    // Curviness.traceScore's KDoc for why this can't run live.
                    twistinessScore = 0.0, // placeholder, replaced two lines below
                    stopCount = stopState.stopCount,
                    idleMs = stopState.idleMs,
                ),
            )
            val twistiness = runCatching {
                Curviness.traceScore(loadTripPoints(this, trip).map { it.at })
            }.getOrDefault(0.0)
            TripStore.save(trip.copy(drivingStats = trip.drivingStats.copy(twistinessScore = twistiness)))
            SyncClient.syncQuietly()
            checkBadges()
            if (wasAuto) TripEndedNotification.show(this, stats.startTimeMs)
        }
        _stats.value = null
        destLat = null
        destLon = null
        autoStarted = false
        pendingStopAtMs = null
        ensureLocationUpdates()
        updateNotification()
    }
```

The `runCatching` around `loadTripPoints`/`traceScore` is deliberate: this is the one place in the
file that reads back its own just-flushed write, and a malformed or missing trace line must not
prevent the trip itself from saving — a `0.0` twistiness reads identically to "not enough trace to
judge," which `traceScore` already returns for a normal short trip.

- [ ] **Step 8: Compile and confirm no regression**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(trip): finalize DrivingStats in endTrip, including post-hoc twistiness"
```

---

### Task 7: `MapHud.kt` live counters + disclaimer

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapHud.kt`

**Interfaces:**
- Consumes: `TripStats.hardBrakeCount`, `.hardAccelCount`, `.hardCornerCount`, `.stopCount` (Tasks 2-3).

- [ ] **Step 1: Add live counters to `ActiveTripCard`**

Modify `app/src/main/java/com/jellemax/detour/ui/MapHud.kt` — extend the `Row` inside
`ActiveTripCard` (`:284-300`):

```kotlin
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem("Time", formatDuration(now - stats.startTimeMs))
            StatItem("Distance", formatDistanceKm(stats.distanceMeters))
            StatItem("Top", formatSpeedKmh(stats.topSpeedMps))
            if (stats.mode.tracksLean) {
                StatItem("Lean", formatLeanAngle(stats.currentLeanAngleDeg))
                StatItem("Max lean", formatLeanAngle(stats.maxLeanAngleDeg))
            }
            if (stats.mode.tracksGForce) {
                StatItem("Max G", formatGForce(stats.maxGForce))
            }
        }
        val hardEvents = stats.hardBrakeCount + stats.hardAccelCount + stats.hardCornerCount
        if (hardEvents > 0 || stats.stopCount > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (stats.hardBrakeCount > 0) StatItem("Hard brakes", "${stats.hardBrakeCount}")
                if (stats.hardAccelCount > 0) StatItem("Hard accel", "${stats.hardAccelCount}")
                if (stats.hardCornerCount > 0) StatItem("Hard corners", "${stats.hardCornerCount}")
                if (stats.stopCount > 0) StatItem("Stops", "${stats.stopCount}")
            }
            Text(
                "Not a score to chase — these numbers are informational only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
```

- [ ] **Step 2: Build and visually confirm on-device (no unit test — this is a `@Composable`, same
  reasoning the monotonic-fix-age spec already gives for this file: no `compose-ui-test` in this
  repo)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Install on the connected phone, start a manual trip (Go/Track), and confirm:
- the new row is absent while all counts are zero
- it appears the moment a hard-brake/accel/corner/stop is recorded, with the disclaimer text
  beneath it

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapHud.kt
git commit -m "feat(ui): show live hard-event/stop counters on the trip HUD"
```

---

### Task 8: `tripStatLine` extended + `TripDetailScreen` disclaimer

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt`
- Test: `app/src/test/java/com/jellemax/detour/ui/TripStatLineTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt`

**Interfaces:**
- Consumes: `Trip.drivingStats` (Task 1). `tripStatLine(trip: Trip): String` is already called by
  both `HistoryScreen.kt` (the row) and `TripDetailScreen.kt:507` — extending it once surfaces the
  new stats in both places.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jellemax/detour/ui/TripStatLineTest.kt` (plain JUnit4, no Android
APIs — matching `TripTraceMatchingTest.kt`'s shape):

```kotlin
package com.jellemax.detour.ui

import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.Trip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatLineTest {

    private fun trip(drivingStats: DrivingStats = DrivingStats()) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_600_000L,
        distanceMeters = 5_000.0,
        topSpeedMps = 30.0,
        destinationLat = null,
        destinationLon = null,
        drivingStats = drivingStats,
    )

    @Test
    fun aTripWithNoHardEventsOmitsTheHardEventSegment() {
        val line = tripStatLine(trip())
        assertFalse(line.contains("hard"))
    }

    @Test
    fun hardEventCountsAppearWhenNonZero() {
        val line = tripStatLine(trip(DrivingStats(hardBrakeCount = 2, hardCornerCount = 1)))
        assertTrue(line.contains("2 hard brakes"))
        assertTrue(line.contains("1 hard corner"))
    }

    @Test
    fun stopsAppearWhenNonZero() {
        val line = tripStatLine(trip(DrivingStats(stopCount = 3)))
        assertTrue(line.contains("3 stops"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.ui.TripStatLineTest"`
Expected: FAIL — current `tripStatLine` never mentions "hard" or "stops".

- [ ] **Step 3: Extend `tripStatLine`**

Modify `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt` (`:412-422`):

```kotlin
fun tripStatLine(trip: Trip): String {
    val parts = mutableListOf(
        formatDurationHistory(trip.durationMs),
        formatDistanceKm(trip.distanceMeters),
        "avg " + formatSpeedKmh(trip.avgSpeedMps),
        "top " + formatSpeedKmh(trip.topSpeedMps),
    )
    if (trip.mode.tracksLean) parts += "lean " + formatLeanAngle(trip.maxLeanAngleDeg)
    if (trip.mode.tracksGForce) parts += "max " + formatGForce(trip.maxGForce)
    val ds = trip.drivingStats
    if (ds.hardBrakeCount > 0) parts += "${ds.hardBrakeCount} hard brake" + if (ds.hardBrakeCount == 1) "" else "s"
    if (ds.hardAccelCount > 0) parts += "${ds.hardAccelCount} hard accel" + if (ds.hardAccelCount == 1) "" else "s"
    if (ds.hardCornerCount > 0) parts += "${ds.hardCornerCount} hard corner" + if (ds.hardCornerCount == 1) "" else "s"
    if (ds.stopCount > 0) parts += "${ds.stopCount} stop" + if (ds.stopCount == 1) "" else "s"
    return parts.joinToString(" · ")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.ui.TripStatLineTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Add the disclaimer to `TripDetailScreen`**

Modify `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt` — find the `Text(... else
tripStatLine(trip) ...)` call at `:507` and add, immediately after that `Text` composable closes,
a conditional disclaimer:

```kotlin
                    if (trip.drivingStats.hardBrakeCount + trip.drivingStats.hardAccelCount +
                        trip.drivingStats.hardCornerCount > 0
                    ) {
                        Text(
                            "Not a score to chase — informational only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
```

Read the exact surrounding braces/indentation at `TripDetailScreen.kt:500-515` before inserting —
this file's line numbers may have drifted since this plan was written; locate the `tripStatLine(trip)`
call with `grep -n "tripStatLine" app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt`
and confirm before editing.

- [ ] **Step 6: Build and visually confirm on-device**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Open a trip with recorded hard events in `HistoryScreen` and in `TripDetailScreen`; confirm the
line reads e.g. "12m · 5.0 km · avg 45 km/h · top 62 km/h · 2 hard brakes · 1 stop" and the
disclaimer shows beneath it on the detail screen only when hard events are present.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt \
        app/src/test/java/com/jellemax/detour/ui/TripStatLineTest.kt
git commit -m "feat(ui): surface driving-behavior stats in the trip stat line"
```

---

### Task 9: Full verification pass + version bump

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Full gate**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

Expected: all four green. If `:shared:iosSimulatorArm64Test` is runnable in this environment
(the `ios.yml` workflow's path-gated job — see `detour-shared-core` §7), run it too:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

- [ ] **Step 2: On-device GPS replay verification**

Per the design doc's Verification section and `detour-gps-replay`: install `:app:assembleDebug`
on the connected phone, run (or record) a fixture route containing at least one hard brake, one
sustained corner, one signed-limit stretch driven over the limit, and one real stop lasting past
`StopDetector.MIN_STOP_DWELL_MS`. Confirm:

- HUD counters tick during the trip and roughly match what the fixture contains.
- The finished trip's history row and detail screen show non-zero, plausible values for all six
  stats (hard events, time-over-limit %, road-mix — check `roadTypeMeters` isn't empty by adding
  a temporary log if the UI doesn't yet expose it directly, twistiness, stop count).
- An old trip recorded before this change still opens in both `HistoryScreen` and
  `TripDetailScreen` without a crash, and its line has no hard-event/stop segment.

- [ ] **Step 3: Bump `versionName`**

Modify `app/build.gradle.kts:76`:

```kotlin
        versionName = "1.77.0"
```

New feature, backward compatible (old trips still decode) → minor bump, per `CONTRIBUTING.md`'s
Versioning section.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump versionName to 1.77.0 for driving-behavior stats"
```

---

## Self-Review Notes

- **Spec coverage:** all six sub-stats (Tasks 2-6, twistiness folded into Task 6's finalize step),
  the data model (Task 1), UI (Tasks 7-8), tests (every shared unit + `TripStoreTest` +
  `TripStatLineTest`), commit structure (matches the spec's 9-item list exactly), gates and
  version bump (Task 9) are each covered by a task above.
- **No Badges.kt touch** anywhere in this plan, matching the spec's guardrail.
- **Type consistency checked:** `HardEventDetector.SpeedResult`/`HeadingState` field names match
  between Task 2's shared code and Task 2's wiring steps; `RoadTypeTracker.State.meters` (a
  `Map<HighwayClass, Double>`) matches `DrivingStats.roadTypeMeters`'s type exactly, so Task 6's
  `roadTypeMeters = roadTypeState.meters` needs no conversion; `StopDetector.State.stopCount`/
  `.idleMs` match the `DrivingStats` fields they're assigned to in Task 6.
- **Known drift risk, flagged rather than hidden:** several line numbers cited (`TripTrackingService.kt`
  wiring points, `TripDetailScreen.kt:507`) are exact as of this plan's writing but this file is
  1347 lines and under active development elsewhere in the repo — Task 8 Step 5 explicitly calls
  out re-confirming with `grep -n` before editing, and the same caution applies to every
  `TripTrackingService.kt` line reference in Tasks 2-6.
