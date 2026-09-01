# OBD2 HUD speed + per-trip OBD2-used indicator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the driving HUD (and camera, speed-limit lookup, relays) show OBD2 speed when an adapter is feeding it, and record per trip what fraction of the drive OBD2 supplied the speed — surfaced in the history list and trip detail.

**Architecture:** One private helper in `TripTrackingService` resolves OBD2 → board telemetry → phone GPS once, in `onLocation`, before the `_lastFix` StateFlow is published; every display/relay consumer of `lastFix.speedMps` picks it up unchanged. A separate pair of counters tallies OBD2-sourced speed fixes during a trip and finalises a `DrivingStats.obd2SpeedPct` at `endTrip`. Auto-start/stop and the fog trace keep using raw GPS.

**Tech Stack:** Kotlin, Kotlin Multiplatform (`shared/` commonMain + commonTest), Android (`app/`), Jetpack Compose, Gradle, `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-08-28-obd2-hud-speed-and-trip-indicator-design.md`

## Global Constraints

- `versionName` in `app/build.gradle.kts` bumps `1.79.0` → `1.80.0` (mixed fix + backward-compatible feature → minor, per `CONTRIBUTING.md`), in Task 3 — the commit that adds the feature surface. Do not touch `versionCode` (CI-stamped).
- `shared/` code touched here is `commonMain` / `commonTest` — pure JSON/decode, no file I/O, no platform APIs (per `detour-shared-core`).
- `TripTrackingService` has no unit-test path in this repo (no Robolectric, no instrumented source set) — service changes are gated by compile + `assembleDebug`/`assembleRelease` and a manual GPS-replay A/B, never a unit test. Same carve-out the base OBD2 plan used.
- `DrivingStats` fields follow the existing zero-default convention: a trip recorded before the field existed decodes at `0.0`, indistinguishable from "measured, found nothing".
- OBD2 speed is only trusted when the resolved travel mode `tracksGForce` (car/moto) — an always-hot dongle in a parked car must not leak a "stopped" reading into a bike/walk trip. This predicate is `mode.tracksGForce && it.hasSpeed` and must stay identical everywhere it appears.
- Read `detour-compose-state-hazards` before editing `TripTrackingService` (its trigger covers any change near a `StateFlow` collector / `_lastFix`), even though this change does not alter `_lastFix`'s type or cadence.

---

### Task 1: `DrivingStats.obd2SpeedPct` field + JSON codec

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt` (`DrivingStats` at `:34-44`, `encodeDrivingStats` at `:126-136`, `decodeDrivingStats` at `:138-156`)
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripStoreTest.kt` (`drivingStatsRoundTripsThroughEncodeAndDecode` at `:24-33`)

**Interfaces:**
- Consumes: nothing.
- Produces: `DrivingStats.obd2SpeedPct: Double` (default `0.0`) — a fraction in `0.0..100.0`. Read by Task 3's `endTrip` writer and the two display sites.

- [ ] **Step 1: Extend the existing round-trip test**

In `TripStoreTest.kt`, add `obd2SpeedPct` to the `stats` value in `drivingStatsRoundTripsThroughEncodeAndDecode`:

```kotlin
    @Test
    fun drivingStatsRoundTripsThroughEncodeAndDecode() {
        val stats = DrivingStats(
            hardBrakeCount = 2, hardAccelCount = 1, hardCornerCount = 3,
            secondsOverLimit = 45, pctOverLimit = 12.5,
            roadTypeMeters = mapOf(HighwayClass.MOTORWAY to 500.0, HighwayClass.LOCAL to 300.0),
            twistinessScore = 0.42, stopCount = 1, idleMs = 90_000L,
            obd2SpeedPct = 87.5,
        )
        val decoded = TripStore.decodeTrip(TripStore.encode(trip(stats)))
        assertEquals(stats, decoded.drivingStats)
    }
```

The `aTripSavedBeforeDrivingStatsExistedDecodesWithAllZeroDefaults` test (`:35-44`) already covers the absent-key path — it asserts equality against a full `DrivingStats()`, so once the field defaults to `0.0` that test keeps passing with no edit.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripStoreTest.drivingStatsRoundTripsThroughEncodeAndDecode"`
Expected: FAIL — compile error, `DrivingStats` has no parameter `obd2SpeedPct`.

- [ ] **Step 3: Add the field**

In `TripStore.kt`, add to `DrivingStats` after `idleMs`:

```kotlin
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
    /** Fraction (0-100) of this trip's speed fixes where fresh OBD2 telemetry
     *  supplied the speed. 0.0 for trips recorded before OBD2 and for trips
     *  where no adapter ever fed a reading — the two are not distinguished,
     *  same as every other field here. */
    val obd2SpeedPct: Double = 0.0,
)
```

- [ ] **Step 4: Add encode + decode**

In `encodeDrivingStats` (`:126`), add after the `idleMs` line:

```kotlin
        put("idleMs", d.idleMs)
        put("obd2SpeedPct", d.obd2SpeedPct)
    }
```

In `decodeDrivingStats` (`:138`), add after the `idleMs` line:

```kotlin
            stopCount = o.optLong("stopCount").toInt(),
            idleMs = o.optLong("idleMs"),
            obd2SpeedPct = o.optDouble("obd2SpeedPct", 0.0),
        )
```

(`optDouble(key, default)` is the same helper `pctOverLimit` and `twistinessScore` already use two lines up.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripStoreTest"`
Expected: PASS — all `TripStoreTest` cases green.

- [ ] **Step 6: Run the shared metadata check**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/TripStore.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/TripStoreTest.kt
git commit -m "feat(shared): add DrivingStats.obd2SpeedPct, decoding old trips at 0.0"
```

---

### Task 2: Resolve OBD2/board/GPS speed at the fix fan-out (piece A)

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — `Fix` KDoc (`:111`), new helper near `freshObdTelemetry` (`:466`), `onLocation` (`:1124-1142`), `effectiveSpeedMps` in `onTripLocation` (`:1245-1264`)

**Interfaces:**
- Consumes: `freshObdTelemetry(): ObdTelemetry?` (`:466`), `freshBoardTelemetry(): BoardTelemetry?` (`:457`), `resolvedMode(): TravelMode` (`:754`) — all already present.
- Produces: `resolveDisplaySpeedMps(gpsSpeedMps: Double, mode: TravelMode): Double` — private. Used by `onLocation` and `onTripLocation` in this task; Task 3 does **not** call it (it re-checks the OBD2 predicate directly).

- [ ] **Step 1: Read the hazards skill**

Read `detour-compose-state-hazards` in full. Confirm this change does not touch a `LaunchedEffect`/`DisposableEffect` key list, a `rememberUpdatedState`, or a `withFrameNanos` loop, and does not change `_lastFix`'s type, nullability, `Fix` field set, or one-emit-per-`onLocation` cadence — only the numeric value of `speedMps`.

- [ ] **Step 2: Add the helper**

In `TripTrackingService.kt`, immediately after `freshObdTelemetry()` (ends `:470`):

```kotlin
    /** OBD2 -> board telemetry -> phone GPS, whichever is fresh, highest first.
     *  Single definition of the priority chain that onTripLocation's
     *  effectiveSpeedMps and _lastFix both need. `mode` gates OBD2 trust: an
     *  always-hot ELM327 dongle can stay linked to a parked car while the rider
     *  starts a bike/walk trip in Bluetooth range, and its "stopped" reading
     *  must not leak in. */
    private fun resolveDisplaySpeedMps(gpsSpeedMps: Double, mode: TravelMode): Double =
        freshObdTelemetry()
            ?.takeIf { mode.tracksGForce && it.hasSpeed }
            ?.let { it.speedKmh / 3.6 }
            ?: freshBoardTelemetry()
                ?.takeIf { it.hasSpeed }
                ?.let { it.speedKmh / 3.6 }
            ?: gpsSpeedMps
```

- [ ] **Step 3: Use it in `onLocation`**

Replace the top of `onLocation` (`:1124-1134`):

```kotlin
    private fun onLocation(location: Location) {
        val speed = speedOf(location)
        _lastFix.value = Fix(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = resolveDisplaySpeedMps(speed, resolvedMode()),
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            accuracyMeters = location.accuracy,
            timeMs = location.time,
            elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
        )
        val stats = _stats.value
        if (stats == null) {
            onIdleLocation(location, speed)
        } else {
            onTripLocation(location, speed, stats)
        }
        lastLocation = location
    }
```

`onIdleLocation` and `onTripLocation` still receive the raw `speed` — do not change their argument.

- [ ] **Step 4: Collapse `effectiveSpeedMps` onto the helper**

In `onTripLocation`, replace the inline chain (`:1258-1264`):

```kotlin
        val effectiveSpeedMps = resolveDisplaySpeedMps(speed, stats.mode)
```

Keep the explanatory comment block above it (`:1245-1257`). Leave `speedIsReal` just below (`:1272-1274`) **unchanged** — it needs its own per-arm `hasSpeed` checks and the `location.hasSpeed()` term.

- [ ] **Step 5: Fix the `Fix` KDoc**

Replace `:111`:

```kotlin
/** Latest location fix, published live for the map (fog, navigation) and the
 *  HUD. `speedMps` is the best available source — fresh OBD2, else board
 *  telemetry, else the phone's GPS — not necessarily GPS. Auto-start/stop and
 *  the fog trace deliberately stay on raw GPS; see onLocation. */
data class Fix(
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Build both variants**

Run: `./gradlew :app:assembleDebug :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "fix(trip): resolve OBD2/board speed at the fix fan-out so the HUD, camera and relays use it"
```

---

### Task 3: Count OBD2 speed fixes, persist, and display (piece E)

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — counter fields near `:435`, reset in `beginTrip` (`:859-861`), OBD2 check in `onTripLocation` (right after `effectiveSpeedMps`, ~`:1264`), `DrivingStats(...)` in `endTrip` (`:925-936`)
- Modify: `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt` — `tripStatLine` (`:431-446`), imports
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt` — stat block (~`:547`)
- Modify: `app/build.gradle.kts` — `versionName`

**Interfaces:**
- Consumes: `DrivingStats.obd2SpeedPct` (Task 1); `freshObdTelemetry()` (`:466`); `effectiveSpeedMps` / `stats.mode` in `onTripLocation` (Task 2).
- Produces: nothing downstream.

- [ ] **Step 1: Add the counters**

In `TripTrackingService.kt`, next to `hardBrakeCount` (`:435`):

```kotlin
    @Volatile private var hardBrakeCount = 0
    @Volatile private var hardAccelCount = 0
    @Volatile private var obd2SpeedFixes = 0
    @Volatile private var speedFixesTotal = 0
```

- [ ] **Step 2: Reset them per trip**

In `beginTrip`, in the reset block alongside `hardBrakeCount = 0` (`:860-861`):

```kotlin
        hardBrakeCount = 0
        hardAccelCount = 0
        obd2SpeedFixes = 0
        speedFixesTotal = 0
```

- [ ] **Step 3: Count on each trip fix**

In `onTripLocation`, immediately after the `effectiveSpeedMps` line from Task 2 (~`:1264`), before the `speedIsReal` line:

```kotlin
        val effectiveSpeedMps = resolveDisplaySpeedMps(speed, stats.mode)

        // Which source actually drove that number, for the per-trip
        // obd2SpeedPct. Same predicate resolveDisplaySpeedMps uses for its OBD2
        // arm — board telemetry winning does not count, GPS fallback does not
        // count.
        speedFixesTotal++
        if (freshObdTelemetry()?.takeIf { stats.mode.tracksGForce && it.hasSpeed } != null) {
            obd2SpeedFixes++
        }
```

- [ ] **Step 4: Finalise in `endTrip`**

In the `DrivingStats(...)` constructor in `endTrip`, the last two lines are currently:

```kotlin
                    stopCount = stopState.stopCount,
                    idleMs = stopState.idleMs,
                ),
```

Add `obd2SpeedPct` as the final argument:

```kotlin
                    stopCount = stopState.stopCount,
                    idleMs = stopState.idleMs,
                    obd2SpeedPct = if (speedFixesTotal > 0)
                        obd2SpeedFixes * 100.0 / speedFixesTotal else 0.0,
                ),
```

- [ ] **Step 5: Compile the service change**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: History stat line**

In `HistoryScreen.kt`, add the import (alphabetical, with the other `kotlin.*` imports):

```kotlin
import kotlin.math.roundToInt
```

In `tripStatLine`, after the `stopCount` line (`:444`):

```kotlin
    if (ds.stopCount > 0) parts += "${ds.stopCount} stop" + if (ds.stopCount == 1) "" else "s"
    if (ds.obd2SpeedPct > 0.0) parts += "OBD2 ${ds.obd2SpeedPct.roundToInt()}%"
    return parts.joinToString(" · ")
```

Shown for any non-zero fraction on purpose: `OBD2 3%` is the signal that the adapter connected then dropped almost immediately.

- [ ] **Step 7: Trip-detail line**

In `TripDetailScreen.kt`, after the `pctOverLimit` block (ends `:554`), before the hard-event block:

```kotlin
                    if (trip.drivingStats.obd2SpeedPct > 0.0) {
                        Text(
                            "OBD2 speed: ${trip.drivingStats.obd2SpeedPct.roundToInt()}% of the drive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
```

`roundToInt` is already imported here (used at `:531`).

- [ ] **Step 8: Bump `versionName`**

In `app/build.gradle.kts`, change `versionName = "1.79.0"` to `versionName = "1.80.0"`.

- [ ] **Step 9: Full verification gate**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green (Task 1's extended test included).

Run: `./gradlew :app:assembleDebug :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt \
        app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt \
        app/build.gradle.kts
git commit -m "feat(trip): record and show per-trip OBD2 speed coverage, bump versionName to 1.80.0"
```

---

## Manual verification (user's follow-up, after the branch builds)

Per `detour-gps-replay`, run the **same recorded route** through the app twice — once on the commit before Task 2, once on the branch tip — and compare:

- **HUD speed trace identical.** Replay produces no OBD2 telemetry, so `freshObdTelemetry()` is always null and `resolveDisplaySpeedMps` returns exactly `gpsSpeedMps`. Any divergence is a regression.
- **Trip finishes with `obd2SpeedPct == 0.0`** — no `OBD2 …%` part in the history line, no OBD2 line in trip detail.
- Camera follow, speed-limit signs, and the average-speed section behave as before.

The non-zero OBD2 path (adapter connected → HUD shows vehicle speed → history shows `OBD2 90-something%`) needs a physical ELM327 adapter and is part of the on-device pass the base OBD2 spec already flagged as the user's own step.

## Self-Review

**Spec coverage:**
- A — resolve in `onLocation` before `_lastFix`: Task 2 Steps 2-3. ✓
- A — idle/trip paths keep raw GPS: Task 2 Step 3 (note not to change the args). ✓
- A — silent, no HUD label: nothing added to `MapHud` — correct by omission. ✓
- A — `effectiveSpeedMps` collapse: Task 2 Step 4. ✓
- A — consumers change with no edit: verified by the table in the spec; nothing to implement. ✓
- A — compose-state hazards read: Task 2 Step 1. ✓
- E — `DrivingStats.obd2SpeedPct` + codec + old-trip default: Task 1. ✓
- E — counters + reset + endTrip: Task 3 Steps 1-4. ✓
- E — history `OBD2 94%`: Task 3 Step 6. ✓
- E — trip-detail line: Task 3 Step 7. ✓
- Versioning `1.80.0`: Task 3 Step 8. ✓
- Testing (shared decode test; service via replay): Task 1 Step 5, Manual verification section. ✓
- Out of scope (iOS, backend, B/C/D): untouched. ✓

**Placeholder scan:** Step 4 of Task 3 says "check the exact existing field names/order" — that is a real instruction (the executor must read `:925-936`), not a placeholder; the code to add is given verbatim. No TBD/TODO/"handle edge cases".

**Type consistency:** `resolveDisplaySpeedMps(Double, TravelMode): Double` — same name and signature in Task 2 Steps 2, 3, 4 and referenced in Task 3 Step 3. `obd2SpeedPct: Double` — same name in Task 1 (definition), Task 3 Step 4 (writer), Steps 6-7 (readers). `obd2SpeedFixes` / `speedFixesTotal` — same names in Task 3 Steps 1, 2, 3, 4. Predicate `mode.tracksGForce && it.hasSpeed` identical in Task 2 Step 2 and Task 3 Step 3. ✓
