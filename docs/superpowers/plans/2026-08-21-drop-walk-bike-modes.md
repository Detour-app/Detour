# Drop Walk and Bike Travel Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `TravelMode.WALK` and `TravelMode.BIKE` and everything that exists only to serve them, on both Android and iOS. Supported modes become `CAR` and `MOTO` only.

**Architecture:** `TravelMode` lives in `shared/` (commonMain) and drives per-mode UI (`entries.forEach` loops that need no change) plus two platform-specific auto-detection copies (Android's `TripTrackingService`, iOS's `TripRecorder`) that independently guess "this is a walk" from GPS pace. Shrinking the enum removes the UI cases outright; the auto-detection guess is repurposed from "relabel as WALK" into "this isn't a real drive, drop the trip" using the mechanism each platform already has for discarding a too-short auto trip (`MIN_AUTO_TRIP_METERS` / `minAutoTripMeters`).

**Tech Stack:** Kotlin Multiplatform (`shared/`), Jetpack Compose (`app/`), Swift/SwiftUI (`iosApp/`).

**Spec:** GitHub issue [#27](../../../27) "Drop Walk and Bike travel modes" (detailed scope, file/line references). Issue [#58](../../../58) is a duplicate filed later — close it as a dupe of #27 when this lands, don't implement it separately.

## Global Constraints

- `TravelMode.entries` must end up exactly `[MOTO, CAR]` (declaration order in the enum stays MOTO-then-CAR, matching current source order).
- A slow trip (avg < 2.5 m/s / ~9 km/h, top speed < 6.0 m/s / ~22 km/h, sustained past 90s) with no mapped vehicle connected is **dropped entirely, not saved as CAR** — confirmed with the user, overriding the issue's "or CAR" fallback wording. Apply identically on Android and iOS.
- Existing trips already recorded as `WALK`/`BIKE` are left as-is — `TravelMode.of()` already falls back to `CAR` for unknown names, so they silently redraw as car trips. No migration code. Confirmed with the user.
- `DetectedActivity.WALKING` activity-recognition transition (Android) stays registered — it cancels a stray `IN_VEHICLE` probe when you're actually on foot. Only its mapping to a travel mode goes away; it never had one to begin with in the transition handler (see Task 2), so no change needed there beyond what the mode-classification removal already covers.
- `RoadRoulette.parseMaxSpeed("walk")` (OSM `maxspeed=walk` tag), "walks the polyline"/"walks every trace point" comments, and "bike" meaning motorcycle in lean/mount-calibration copy are **out of scope** — do not touch them.
- iOS code changes are edits only; this devcontainer has no macOS toolchain to build/run `iosApp/`. Verify Android end-to-end (build, tests, GPS replay); verify iOS by inspection and by keeping its logic a literal mirror of Android's.

---

### Task 1: Shrink `TravelMode` (shared)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/TravelMode.kt`

**Interfaces:**
- Produces: `TravelMode.entries == [MOTO, CAR]`. `TravelMode.of(name: String?): TravelMode` unchanged (already falls back to `CAR`).

- [ ] **Step 1: Delete the `WALK` and `BIKE` entries and fix the two doc comments that named them**

In `shared/src/commonMain/kotlin/com/jellemax/detour/data/TravelMode.kt`:

Replace:
```kotlin
    /** Google Maps navigation mode: w=walk, b=bike, d=drive. */
    val gmapsMode: String,
```
with:
```kotlin
    /** Google Maps navigation mode: d=drive. Both remaining modes route by
     *  car; kept as a field rather than inlined because [gmapsDirectionsTravelMode]
     *  and `navigateGoogleMaps` read it per-mode. */
    val gmapsMode: String,
```

Replace:
```kotlin
    WALK(
        label = "Walk",
        minKm = 1f, maxKm = 15f, defaultKm = 3f,
        highwayRegex = "^(footway|pedestrian|path|living_street|residential|" +
            "unclassified|track|steps)$",
        gmapsMode = "w",
        ghProfile = "foot",
    ),
    BIKE(
        label = "Bike",
        minKm = 1f, maxKm = 30f, defaultKm = 10f,
        highwayRegex = "^(cycleway|living_street|residential|unclassified|" +
            "tertiary|secondary|track|path)$",
        gmapsMode = "b",
        ghProfile = "bike",
    ),
    MOTO(
```
with:
```kotlin
    MOTO(
```

Replace:
```kotlin
    /** Any motion sensor at all — nothing to register for BIKE. */
    val tracksMotion: Boolean get() = tracksLean || tracksGForce
```
with:
```kotlin
    /** Any motion sensor at all: lean, g-force, or both. */
    val tracksMotion: Boolean get() = tracksLean || tracksGForce
```

- [ ] **Step 2: Compile-check shared**

Run: `./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL. (This alone will surface every `when` on `TravelMode` in `shared/` that was non-exhaustive without `WALK`/`BIKE` — there are none today, confirmed by grep, but the compiler is the real check.)

- [ ] **Step 3: Run shared tests**

Run: `./gradlew :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass unchanged (none reference `WALK`/`BIKE`).

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/TravelMode.kt
git commit -m "feat: drop WALK and BIKE travel modes from the shared enum"
```

---

### Task 2: Android auto-detection — repurpose the walk-judge into a drop-the-trip gate

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`

**Interfaces:**
- Consumes: `TravelMode.entries == [MOTO, CAR]` (Task 1).
- Produces: `resolvedMode(): TravelMode` no longer returns a walk guess (falls straight through to mapped vehicle or `Settings.tripMode.value`). `endTrip()`'s `worthSaving` gate additionally drops an auto-started trip that never showed a real vehicle and never left walking pace.

- [ ] **Step 1: Rename the walk-judge constants to describe what they gate now**

In `TripTrackingService.kt`, replace:
```kotlin
        private const val MIN_AUTO_TRIP_METERS = 500.0
        // A trip whose average pace stays under this, with no mapped vehicle
        // connected, is a walk. Judged on average (not top) speed so one GPS
        // spike can't upgrade a stroll, and only after enough of the trip to
        // tell a real walk from the first slow seconds of a drive.
        private const val WALK_AVG_MAX_MPS = 2.5           // ~9 km/h
        private const val WALK_MIN_JUDGE_MS = 90_000L
        /** ...but average pace alone calls a car stuck in town traffic a walk.
         *  Nothing that has ever hit this speed is one, whatever its average. */
        private const val WALK_TOP_MAX_MPS = 6.0           // ~22 km/h
        /** Which vehicle wins when several mapped devices are connected at
         *  once, weakest first. */
        private val MODE_PRIORITY =
            listOf(TravelMode.WALK, TravelMode.BIKE, TravelMode.CAR, TravelMode.MOTO)
```
with:
```kotlin
        private const val MIN_AUTO_TRIP_METERS = 500.0
        // A trip whose average pace stays under this, with no mapped vehicle
        // connected, was never a drive — a walk, a jog, pushing a bike. Judged
        // on average (not top) speed so one GPS spike can't rescue it, and
        // only after enough of the trip to tell a real walk from the first
        // slow seconds of a drive. Dropped at endTrip() rather than saved
        // under a mode that doesn't fit it.
        private const val SLOW_NO_VEHICLE_AVG_MAX_MPS = 2.5    // ~9 km/h
        private const val SLOW_NO_VEHICLE_MIN_JUDGE_MS = 90_000L
        /** ...but average pace alone calls a car stuck in town traffic slow.
         *  Nothing that has ever hit this speed gets dropped, whatever its average. */
        private const val SLOW_NO_VEHICLE_TOP_MAX_MPS = 6.0    // ~22 km/h
        /** Which vehicle wins when several mapped devices are connected at
         *  once, weakest first. */
        private val MODE_PRIORITY = listOf(TravelMode.CAR, TravelMode.MOTO)
```

- [ ] **Step 2: Strip the walk guess out of `resolvedMode()`**

Replace:
```kotlin
    /**
     * What this trip should be logged as. Priority: a connected mapped device
     * decides (Cardo → moto, infotainment → car, walking earbuds → walk); else,
     * once we have enough of the trip to judge, a sustained walking pace with
     * nothing mapped connected means a walk; else the spin tab's mode. The tab
     * itself is never changed here — classification is the trip's, not the UI's.
     */
    private fun resolvedMode(): TravelMode {
        val map = Settings.vehicleDevices.value
        // The heaviest vehicle connected wins, not the last to connect: earbuds
        // paired for a walk stay linked in the car, and the helmet intercom and
        // the car radio can both be up while the bike sits in the garage.
        connectedVehicles.mapNotNull { map[it]?.mode }
            .maxByOrNull { MODE_PRIORITY.indexOf(it) }
            ?.let { return it }
        val s = _stats.value
        if (s != null && s.durationMs > WALK_MIN_JUDGE_MS) {
            val avg = if (s.durationMs > 0) s.distanceMeters / (s.durationMs / 1000.0) else 0.0
            if (avg < WALK_AVG_MAX_MPS && s.topSpeedMps < WALK_TOP_MAX_MPS) return TravelMode.WALK
        }
        return Settings.tripMode.value
    }
```
with:
```kotlin
    /**
     * What this trip should be logged as. A connected mapped device decides
     * (Cardo → moto, infotainment → car); else the spin tab's mode. The tab
     * itself is never changed here — classification is the trip's, not the
     * UI's. Whether the trip is worth keeping at all is decided separately,
     * in [endTrip].
     */
    private fun resolvedMode(): TravelMode {
        val map = Settings.vehicleDevices.value
        // The heaviest vehicle connected wins, not the last to connect: the
        // helmet intercom and the car radio can both be up while the bike
        // sits in the garage.
        connectedVehicles.mapNotNull { map[it]?.mode }
            .maxByOrNull { MODE_PRIORITY.indexOf(it) }
            ?.let { return it }
        return Settings.tripMode.value
    }
```

- [ ] **Step 3: Add the drop condition to `endTrip()`'s `worthSaving` gate**

Replace:
```kotlin
    private fun endTrip() {
        val stats = _stats.value ?: return
        val wasAuto = autoStarted
        stopMotionSensors()
        flushTrace()
        val worthSaving =
            if (wasAuto) stats.distanceMeters >= MIN_AUTO_TRIP_METERS
            else stats.durationMs > 0
```
with:
```kotlin
    private fun endTrip() {
        val stats = _stats.value ?: return
        val wasAuto = autoStarted
        stopMotionSensors()
        flushTrace()
        // An auto trip with no mapped vehicle that never left walking pace
        // wasn't a drive; don't save it under whatever mode the tab happened
        // to have selected. Judged the same way MIN_AUTO_TRIP_METERS judges
        // "never went anywhere" — a second false-positive filter, not a
        // classification.
        val looksLikeAWalk = stats.durationMs > SLOW_NO_VEHICLE_MIN_JUDGE_MS &&
            connectedVehicles.mapNotNull { Settings.vehicleDevices.value[it]?.mode }.isEmpty() &&
            (stats.distanceMeters / (stats.durationMs / 1000.0)) < SLOW_NO_VEHICLE_AVG_MAX_MPS &&
            stats.topSpeedMps < SLOW_NO_VEHICLE_TOP_MAX_MPS
        val worthSaving =
            if (wasAuto) stats.distanceMeters >= MIN_AUTO_TRIP_METERS && !looksLikeAWalk
            else stats.durationMs > 0
```

- [ ] **Step 4: Compile-check and run app unit tests**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, `TripTraceMatchingTest` and all others still pass (none reference the renamed constants or `TravelMode.WALK`/`BIKE`).

- [ ] **Step 5: Verify the drop behavior with a GPS replay**

Use the `detour-gps-replay` skill to replay a slow (<9 km/h sustained, no Bluetooth device mapped) route through the running app with auto-detect on. Confirm:
- The trip starts (fast-enough initial fixes still trigger `onIdleLocation`'s start detector the same as before — unaffected by this change).
- Once past `SLOW_NO_VEHICLE_MIN_JUDGE_MS` (90s) of sustained slow pace, ending the trip (stationary timeout or manual) does **not** produce a new entry in trip history.
- A separate replay of a normal driving-pace route still saves a `CAR` trip as before.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat: drop slow no-vehicle auto trips instead of logging them as a walk"
```

---

### Task 3: Android UI — icons, Google Maps hand-off, settings copy

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/TravelModeIcon.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `TravelMode.entries == [MOTO, CAR]` (Task 1). `MapChrome.kt`'s `ModeBar`, `HistoryScreen.kt:368`, `RouteEditorScreen.kt:381`, `SettingsScreen.kt:931` all iterate `TravelMode.entries` already — no change needed there, they shrink to two chips automatically.

- [ ] **Step 1: Drop the WALK/BIKE icon cases and their now-unused imports**

In `app/src/main/java/com/jellemax/detour/ui/TravelModeIcon.kt`, replace the whole file body with:
```kotlin
package com.jellemax.detour.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.ui.graphics.vector.ImageVector
import com.jellemax.detour.data.TravelMode

val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.MOTO -> Icons.Outlined.TwoWheeler
        TravelMode.CAR -> Icons.Outlined.DirectionsCar
    }
```

- [ ] **Step 2: Drop the WALK/BIKE Google Maps mode mapping**

In `app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt`, replace:
```kotlin
private fun gmapsDirectionsTravelMode(mode: TravelMode): String = when (mode) {
    TravelMode.WALK -> "walking"
    TravelMode.BIKE -> "bicycling"
    TravelMode.MOTO -> "two-wheeler"
    TravelMode.CAR -> "driving"
}
```
with:
```kotlin
private fun gmapsDirectionsTravelMode(mode: TravelMode): String = when (mode) {
    TravelMode.MOTO -> "two-wheeler"
    TravelMode.CAR -> "driving"
}
```

- [ ] **Step 3: Reword the "avoid highways" helper text**

In `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt`, replace:
```kotlin
                Text(
                    "In-app navigation skips motorways (car mode; " +
                        "moto and bike never use them)",
```
with:
```kotlin
                Text(
                    "In-app navigation skips motorways (car mode; " +
                        "moto never uses them)",
```

- [ ] **Step 4: Rewrite the vehicle-mapping doc comment and helper text**

Replace:
```kotlin
/**
 * Map paired Bluetooth (Classic) devices to a vehicle. When one connects, the
 * tracking service logs the trip under that vehicle — a Cardo for the moto, the
 * car's infotainment for driving, walking earbuds for a walk. No scanning, so
 * it needs BLUETOOTH_CONNECT but never location.
 */
```
with:
```kotlin
/**
 * Map paired Bluetooth (Classic) devices to a vehicle. When one connects, the
 * tracking service logs the trip under that vehicle — a Cardo for the moto,
 * the car's infotainment for driving. No scanning, so it needs
 * BLUETOOTH_CONNECT but never location.
 */
```

Replace:
```kotlin
        Text(
            "Add a Bluetooth device to a vehicle. When it's connected, trips log " +
                "under that vehicle automatically — and a walking device (or no " +
                "connection at a walking pace) logs as a walk.",
```
with:
```kotlin
        Text(
            "Add a Bluetooth device to a vehicle. When it's connected, trips log " +
                "under that vehicle automatically. With nothing connected, a trip " +
                "that never picks up real driving pace is dropped rather than saved.",
```

- [ ] **Step 5: Compile-check and run app unit tests**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual check — mode bar and spin sheet with two modes**

Use the `run` skill (or `detour-adb`) to launch the app on the emulator/device. Open the map screen's mode bar and the spin sheet's mode picker (`HistoryScreen`, `RouteEditorScreen` also iterate the same list). Confirm both render sensibly with two items — Material3's `NavigationBar` and the picker's `forEach` auto-distribute width, so this is a look-only check, not expected to need layout changes.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TravelModeIcon.kt \
        app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt
git commit -m "feat: drop WALK/BIKE icons, Maps hand-off and settings copy"
```

---

### Task 4: iOS — mirror the auto-detection change and drop the icon cases

**Files:**
- Modify: `iosApp/Detour/TripRecorder.swift`
- Modify: `iosApp/Detour/HistoryScreen.swift`

**Interfaces:**
- Consumes: `TravelMode.entries == [MOTO, CAR]` (Task 1, via the `DetourShared` KMP framework — iOS has no local `TravelMode` declaration to edit).
- Produces: `resolvedMode() -> TravelMode` and `endTrip()`'s `worthKeeping` mirror Android's Task 2 exactly, per the file's own doc comment ("The thresholds below are the Android ones unchanged").

No macOS toolchain is available in this devcontainer — these edits cannot be built or run here. Match Android's logic literally; that is the verification available.

- [ ] **Step 1: Rename the walk-judge constants**

In `iosApp/Detour/TripRecorder.swift`, replace:
```swift
    private static let minAutoTripMeters = 500.0
    private static let walkAvgMaxMps = 2.5           // ~9 km/h
    private static let walkMinJudgeMs: Int64 = 90_000
    private static let walkTopMaxMps = 6.0           // ~22 km/h
```
with:
```swift
    private static let minAutoTripMeters = 500.0
    private static let slowNoVehicleAvgMaxMps = 2.5       // ~9 km/h
    private static let slowNoVehicleMinJudgeMs: Int64 = 90_000
    private static let slowNoVehicleTopMaxMps = 6.0       // ~22 km/h
```

- [ ] **Step 2: Strip the walk guess out of `resolvedMode()`**

Replace:
```swift
    /// Which vehicle this is. A walk gives itself away by pace, whatever tab
    /// the user left selected.
    private func resolvedMode() -> TravelMode {
        if let s = stats, s.durationMs > Self.walkMinJudgeMs {
            let avg = s.durationMs > 0 ? s.distanceMeters / (Double(s.durationMs) / 1000) : 0
            if avg < Self.walkAvgMaxMps && s.topSpeedMps < Self.walkTopMaxMps {
                return .walk
            }
        }
        return SettingsValues.shared.tripMode
    }
```
with:
```swift
    /// Which vehicle this is. Whether the trip is worth keeping at all is
    /// decided separately, in `endTrip()`.
    private func resolvedMode() -> TravelMode {
        return SettingsValues.shared.tripMode
    }
```

- [ ] **Step 3: Add the drop condition to `endTrip()`'s `worthKeeping` gate**

Replace:
```swift
    func endTrip() {
        guard let finished = stats else { return }
        stopMotionSensors()
        flushTrace()

        let elapsed = nowMs() - finished.startTimeMs
        // An auto-detected trip that never went anywhere was a false positive:
        // a bus ride past the house, or a loose fix drifting indoors.
        let worthKeeping = !startedAutomatically
            || finished.distanceMeters >= Self.minAutoTripMeters
```
with:
```swift
    func endTrip() {
        guard let finished = stats else { return }
        stopMotionSensors()
        flushTrace()

        let elapsed = nowMs() - finished.startTimeMs
        // A trip that never left walking pace wasn't a drive; don't save it
        // under whatever mode the tab happened to have selected. Mirrors
        // Android's TripTrackingService.endTrip().
        let avg = finished.durationMs > 0
            ? finished.distanceMeters / (Double(finished.durationMs) / 1000) : 0
        let looksLikeAWalk = finished.durationMs > Self.slowNoVehicleMinJudgeMs
            && avg < Self.slowNoVehicleAvgMaxMps
            && finished.topSpeedMps < Self.slowNoVehicleTopMaxMps
        // An auto-detected trip that never went anywhere was a false positive:
        // a bus ride past the house, or a loose fix drifting indoors.
        let worthKeeping = !startedAutomatically
            || (finished.distanceMeters >= Self.minAutoTripMeters && !looksLikeAWalk)
```

*(iOS has no Bluetooth vehicle-mapping equivalent in this file, so unlike Android's `endTrip()`, there is no "no mapped vehicle connected" condition to check here — `resolvedMode()` never had one either.)*

- [ ] **Step 4: Drop the WALK/BIKE icon cases**

In `iosApp/Detour/HistoryScreen.swift`, find the `case .walk:` / `case .bike:` lines (around `:110`–`:111`) inside the mode-to-SF-Symbol function and delete both, leaving only the `.moto` and `.car` cases.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Detour/TripRecorder.swift iosApp/Detour/HistoryScreen.swift
git commit -m "feat: mirror the WALK/BIKE removal and drop-slow-trip change on iOS"
```

---

### Task 5: Docs

**Files:**
- Modify: `README.md`
- Modify: `docs/DEBUG_INTENTS.md`
- Modify: `docs/STORE_LISTING.md`
- Modify: `docs/PLAY_LOCATION_DECLARATION.md`
- Modify: `docs/BACKEND_SPEC.md`

- [ ] **Step 1: README.md — first-run steps, map-screen mode bar, radius table, vehicle auto-detect**

Replace:
```
3. **Pick a mode** in the bar at the bottom — walk, bike, moto or car. It sets
```
with:
```
3. **Pick a mode** in the bar at the bottom — moto or car. It sets
```

Replace:
```
- **Mode bar** — walk, bike, moto, car.
```
with:
```
- **Mode bar** — moto, car.
```

Replace:
```
| Mode | Radius | Default | Roads it uses |
| --- | --- | --- | --- |
| Walk | 1–15 km | 3 km | Footways, paths, pedestrian and quiet residential streets |
| Bike | 1–30 km | 10 km | Cycleways and quiet roads |
| Moto | 30–400 km | 120 km | The rural network — see round trips below |
| Car | 5–100 km | 25 km | Everything up to and including motorways |
```
with:
```
| Mode | Radius | Default | Roads it uses |
| --- | --- | --- | --- |
| Moto | 30–400 km | 120 km | The rural network — see round trips below |
| Car | 5–100 km | 25 km | Everything up to and including motorways |
```

Replace:
```
- **Manually.** *Track walk / bike / moto / car* in the spin sheet starts one
  immediately. The red **End trip** button on the map ends whichever trip is
  running.

A live card shows elapsed time, distance, top speed and — depending on the
vehicle — max lean angle and cornering g. On a moto both are recorded; in a car
only g. A bike and a walk get neither: from a rigid mount a lean angle means
something, from a jacket pocket it's the phone sliding around.

**Vehicle auto-detect**: assign paired Bluetooth devices to a vehicle (an
intercom to the moto, the car's infotainment to the car, earbuds to walking) and
a trip logs under that vehicle whenever the device is connected. With nothing
connected, a sustained walking pace logs as a walk. These are Bluetooth Classic
bonds, so there's no scanning and no location permission involved — only
connect/disconnect.
```
with:
```
- **Manually.** *Track moto / car* in the spin sheet starts one immediately.
  The red **End trip** button on the map ends whichever trip is running.

A live card shows elapsed time, distance, top speed and — depending on the
vehicle — max lean angle and cornering g. On a moto both are recorded; in a car
only g.

**Vehicle auto-detect**: assign paired Bluetooth devices to a vehicle (an
intercom to the moto, the car's infotainment to the car) and a trip logs under
that vehicle whenever the device is connected. With nothing connected, a trip
that never picks up real driving pace is dropped rather than saved. These are
Bluetooth Classic bonds, so there's no scanning and no location permission
involved — only connect/disconnect.
```

- [ ] **Step 2: docs/DEBUG_INTENTS.md — seed trip mode values**

Replace:
```
The shape is whatever `TripStore.encode` writes; `mode` is one of `WALK`, `BIKE`,
`MOTO`, `CAR`. Seeded trips have no GPS trace, so a detail screen's map is empty
```
with:
```
The shape is whatever `TripStore.encode` writes; `mode` is one of `MOTO`, `CAR`.
Seeded trips have no GPS trace, so a detail screen's map is empty
```

- [ ] **Step 3: docs/STORE_LISTING.md — spin-a-destination bullet**

Replace:
```
• Walk, bike, moto and car modes, each with its own radius range and its own
  idea of which roads count
```
with:
```
• Moto and car modes, each with its own radius range and its own idea of
  which roads count
```

- [ ] **Step 4: docs/PLAY_LOCATION_DECLARATION.md — background-location form answer**

Replace:
```
Automatic trip tracking. Detour detects when the user starts driving, riding a
motorcycle, cycling or walking and records the route, distance, duration,
```
with:
```
Automatic trip tracking. Detour detects when the user starts driving or riding
a motorcycle and records the route, distance, duration,
```

- [ ] **Step 5: docs/BACKEND_SPEC.md — routing engine capability row**

Replace:
```
| Routing engine (GraphHopper) | Curvy motorcycle round trips, plus car and bike routes, from an offline map extract |
```
with:
```
| Routing engine (GraphHopper) | Curvy motorcycle round trips, plus car routes, from an offline map extract |
```

- [ ] **Step 6: Commit**

```bash
git add README.md docs/DEBUG_INTENTS.md docs/STORE_LISTING.md \
        docs/PLAY_LOCATION_DECLARATION.md docs/BACKEND_SPEC.md
git commit -m "docs: drop walk/bike as supported modes"
```

---

### Task 6: Sweep, version bump, close the issues

**Files:**
- Modify: `app/build.gradle.kts` (versionName)

- [ ] **Step 1: Run the issue's own completeness grep**

Run: `grep -rin "WALK\|BIKE" --include="*.kt" --include="*.swift" .`

Expected surviving hits, all out of scope per Global Constraints — confirm nothing else shows up:
- `RoadRoulette.parseMaxSpeed("walk")` and its test in `ParsingTest.kt:124`
- "walks every trace point" / "walks the polyline" / "walks millions of source pixels" comments (`HubScreen.kt`, `BadgesScreen.kt`, `CoverageMapScreen.kt`, `MapLibreMap.kt`, `MapScreen.kt`)
- "walking north" comment in `TripTraceMatchingTest.kt`
- "bike" meaning motorcycle in `SettingsScreen.kt`'s mount-calibration copy (`:1017`, `:1020`–`:1021`, `:1070`) and `TripRecorder.swift`'s lean/mount comments
- `DetectedActivity.WALKING` transition registration and handler in `TripTrackingService.kt`

If anything else turns up, fix it before moving on — the plan's file list came from a grep sweep at planning time, but code moves.

- [ ] **Step 2: Full Android build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (phone + watch debug APKs; watch doesn't reference `TravelMode` but shares the root build).

- [ ] **Step 3: Bump versionName**

Per `CLAUDE.md` / `CONTRIBUTING.md`'s Versioning section: this is a backward-compatible, user-visible behavior change (mode picker goes from four items to two; auto-detect no longer produces walk trips, it drops them) — not a bug fix, not docs/chore/refactor-only. Bump minor.

In `app/build.gradle.kts`, replace:
```kotlin
        versionName = "1.76.0"
```
with:
```kotlin
        versionName = "1.77.0"
```

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump versionName to 1.77.0 for the walk/bike mode removal"
```

- [ ] **Step 5: Close the duplicate issue and reference both from the PR**

After the branch merges (or when opening the PR — don't close before the work lands), run:
```bash
gh issue close 58 --comment "Duplicate of #27 — landed there."
```
Reference `Closes #27` and `Closes #58` in the PR description per `detour-pr-writing`.
