# OBD2 HUD speed + per-trip "OBD2 was used" indicator

Two coupled pieces on top of the shipped OBD2 connectivity work (`worktree-obd2-connectivity`,
spec `2026-08-23-obd2-connectivity-design.md`). Both hook the same point: where a location fix's
speed is resolved from the best available source.

Piece **A** — the driving HUD, camera-follow, client-side speed-limit lookup, the average-speed
section and the BLE/Wear nav relay all read `TripTrackingService.lastFix.speedMps`, which today is
raw phone GPS. The shipped OBD2 work made *trip recording* prefer OBD2 speed (`effectiveSpeedMps`
inside `onTripLocation`), but that resolved value never reaches `_lastFix`, so everything
display-side still shows GPS even with an adapter connected.

Piece **E** — nothing records, per trip, whether OBD2 actually supplied any speed. The only way to
know the adapter worked on a given drive is to watch the pairing screen's live readout while
driving. A persisted per-trip fraction, surfaced in the history list and trip detail, makes it a
after-the-fact check.

This is part of a larger OBD2 extension arc (A → E → B live HUD readout → D reconnect/diagnostics
UX → C persisted throttle/RPM). B, C and D get their own specs; they still have open design
questions. A and E share one implementation because E's counter lives at the exact line A changes.

## Scope

- **A:** resolve OBD2 → board telemetry → phone GPS speed *once*, in `onLocation`, before
  `_lastFix` is built. Every `lastFix` consumer picks it up with no per-consumer edit.
- **A:** `onIdleLocation` and `onTripLocation` keep receiving the **raw** GPS `speed` — auto-start,
  auto-stop and the fog trace stay on the phone's own GPS pipeline, unchanged (the deliberate
  design documented in `TripTrackingService.kt:1245-1257`).
- **A:** no source label on the HUD — silent best-source (user's explicit choice). The source
  *identity* is what piece E persists.
- **E:** one new `DrivingStats` field, `obd2SpeedPct: Double` — the fraction (0–100) of this trip's
  speed fixes where fresh OBD2 telemetry supplied the number.
- **E:** shown in the history stat line (`OBD2 94%`) whenever `obd2SpeedPct > 0`, and as one line
  in trip detail's stat block.

## Explicitly out of scope

- Any HUD source label / indicator (A is silent by choice).
- Live OBD2 RPM/throttle/coolant on the HUD — that is piece B.
- Persisting throttle/RPM or any per-fix OBD2 history — that is piece C. E stores one aggregate
  number, nothing else.
- iOS — no OBD2 on iOS (out of scope of the base feature). iOS's `Trip` struct already ignores
  unknown `drivingStats` keys, so the new field round-trips through sync untouched.
- Backend — trips sync as opaque JSON (`SyncClient` uploads `TripStore.rawJson()` and only
  augments each object with a derived `topSpeedKmh`); no backend `DrivingStats` schema exists to
  change.
- Recalculating `obd2SpeedPct` for trips recorded before this lands — they decode at `0.0`,
  indistinguishable from "recorded, adapter never fed speed", the same caveat every other
  `DrivingStats` field already carries.

## Piece A — resolve speed at the fix fan-out

### The helper

New private function in `TripTrackingService`, collapsing the priority chain that
`onTripLocation`'s `effectiveSpeedMps` currently spells out inline:

```kotlin
/** OBD2 -> board telemetry -> phone GPS, when each is fresh. `mode` gates OBD2
 *  trust exactly as onTripLocation does: an always-hot ELM327 dongle can stay
 *  linked to a parked car while the rider starts a bike/walk trip within
 *  Bluetooth range, and its "stopped" reading must not leak in. */
private fun resolveDisplaySpeedMps(gpsSpeedMps: Double, mode: TravelMode): Double =
    freshObdTelemetry()
        ?.takeIf { mode.tracksGForce && it.hasSpeed }
        ?.let { it.speedKmh / 3.6 }
        ?: freshBoardTelemetry()
            ?.takeIf { it.hasSpeed }
            ?.let { it.speedKmh / 3.6 }
        ?: gpsSpeedMps
```

`freshObdTelemetry()` / `freshBoardTelemetry()` (`TripTrackingService.kt:457-469`) already apply
the 3 s staleness gate, so a disconnected adapter falls through to the next source on its own.

### Call site — `onLocation` (`TripTrackingService.kt:1124`)

```kotlin
private fun onLocation(location: Location) {
    val speed = speedOf(location)
    val displaySpeed = resolveDisplaySpeedMps(speed, resolvedMode())
    _lastFix.value = Fix(
        lat = location.latitude,
        lon = location.longitude,
        speedMps = displaySpeed,          // was: speed
        bearingDeg = if (location.hasBearing()) location.bearing else null,
        accuracyMeters = location.accuracy,
        timeMs = location.time,
        elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
    )
    val stats = _stats.value
    if (stats == null) onIdleLocation(location, speed)     // unchanged: raw GPS
    else onTripLocation(location, speed, stats)            // unchanged: raw GPS
    lastLocation = location
}
```

`resolvedMode()` (`TripTrackingService.kt:754`) returns the current vehicle mode with or without
an active trip — a connected mapped Classic device decides, else `Settings.tripMode.value`. So
OBD2 speed can reach the HUD in the window after the adapter connects but before the trip
auto-starts, provided the resolved mode tracks g-force.

### `onTripLocation`'s `effectiveSpeedMps` (`TripTrackingService.kt:1258-1264`)

Replace the inline elvis chain with `resolveDisplaySpeedMps(speed, stats.mode)`. Identical result
(`stats.mode.tracksGForce` is what both use); one definition of the priority chain; ~6 fewer
lines. `speedIsReal` just below it (`:1272-1274`) is **unchanged** — it needs the per-arm
`hasSpeed` checks and the `location.hasSpeed()` term, which the helper doesn't expose.

### Consumers that change behaviour, all with no code edit

| Consumer | File | Was | Now |
| --- | --- | --- | --- |
| HUD speed number | `MapScreen.kt:1095` -> `MapHud.kt:212` | GPS | best source |
| Camera-follow bearing/zoom gate | `MapScreen.kt:1084-1087` | GPS | best source |
| Client-side speed-limit fetch trigger | `MapScreen.kt:853-899` | GPS | best source |
| Average-speed section | `MapScreen.kt:1010-1022` | GPS | best source |
| BLE + Wear nav relay `currentSpeedKmh` | `MapScreen.kt:1297-1313` | GPS | best source |
| Circles live-location sink | `TripTrackingService.kt:1452-1473` | GPS | best source |

Every one of these *should* use vehicle speed when it exists — this is a correctness improvement,
not only a cosmetic one for the HUD.

### Compose-state hazards

`_lastFix` is a hot `StateFlow<Fix?>` with many `collect` / `collectAsStateWithLifecycle` sinks.
This change does not touch its type, its nullability, the `Fix` field set, or the emission cadence
(still exactly one emit per `onLocation`). Only the numeric value of `speedMps` differs. No
`LaunchedEffect` / `DisposableEffect` key list, `rememberUpdatedState`, or `withFrameNanos` loop is
involved. `detour-compose-state-hazards` is to be read during implementation regardless, per its
trigger, but the assessed risk is low.

## Piece E — per-trip OBD2-used fraction

### Data model — `shared/.../data/TripStore.kt`

```kotlin
data class DrivingStats(
    // ... existing fields unchanged ...
    val idleMs: Long = 0,
    /** Fraction (0-100) of this trip's speed fixes where fresh OBD2 telemetry
     *  supplied the speed. 0.0 for trips recorded before OBD2, and for trips
     *  where no adapter ever fed a reading -- the two are not distinguished,
     *  same as every other field here. */
    val obd2SpeedPct: Double = 0.0,
)
```

`encodeDrivingStats` gains `put("obd2SpeedPct", d.obd2SpeedPct)`; `decodeDrivingStats` gains
`obd2SpeedPct = o.optDouble("obd2SpeedPct", 0.0)`. Old trips: key absent -> `0.0`.

### Counting — `TripTrackingService`

Two `@Volatile` counters alongside the existing `hardBrakeCount` / `hardAccelCount`
(`TripTrackingService.kt:435-436`):

```kotlin
@Volatile private var obd2SpeedFixes = 0
@Volatile private var speedFixesTotal = 0
```

In `onTripLocation`, right after `effectiveSpeedMps` is resolved:

```kotlin
speedFixesTotal++
if (freshObdTelemetry()?.takeIf { stats.mode.tracksGForce && it.hasSpeed } != null) {
    obd2SpeedFixes++
}
```

The `takeIf` predicate is the *same* one `resolveDisplaySpeedMps` uses for its OBD2 arm, so the
count matches what actually drove the speed value — board telemetry winning does **not** count as
OBD2, GPS fallback does not count.

Both reset to `0` in the counter-reset path (`TripTrackingService.kt:860-861`, the block that
zeroes `hardBrakeCount` etc. in `beginTrip`).

In `endTrip`, in the `DrivingStats(...)` constructor (`TripTrackingService.kt:925`):

```kotlin
obd2SpeedPct = if (speedFixesTotal > 0) obd2SpeedFixes * 100.0 / speedFixesTotal else 0.0,
```

Not folded into the post-hoc `updateDrivingStats` twistiness pass — it is known synchronously at
`endTrip`, unlike twistiness.

### Display — history list

`tripStatLine` (`HistoryScreen.kt:431`) gains one part, after the `stopCount` line:

```kotlin
if (ds.obd2SpeedPct > 0.0) parts += "OBD2 ${ds.obd2SpeedPct.roundToInt()}%"
```

Shown for *any* non-zero fraction on purpose: a trip reading `OBD2 3%` is the signal that the
adapter connected but dropped almost immediately — exactly the flakiness the user wants to see
without a live check.

### Display — trip detail

One conditional line in the stat block (`TripDetailScreen.kt:527-556`, beside the twistiness /
pct-over-limit lines), same style:

```kotlin
if (trip.drivingStats.obd2SpeedPct > 0.0) {
    // "OBD2 speed: 94% of the drive"
}
```

## Testing

- **A, service wiring:** no unit test — the established no-Robolectric carve-out for
  `TripTrackingService` (see the base OBD2 spec's Testing section and #61's). Verified by
  `detour-gps-replay` A/B: the same recorded route replayed on the branch tip before and after
  this change. Replay produces no OBD2 telemetry, so `freshObdTelemetry()` is always null,
  `resolveDisplaySpeedMps` returns exactly `gpsSpeedMps`, and the HUD speed trace, camera
  behaviour and relay output must be identical. This is the regression gate.
- **A, helper:** the three-branch elvis is trivial and mirrors the already-shipped
  `effectiveSpeedMps` logic; no separate test beyond the replay pass.
- **E, decode:** extend `TripStoreTest` (or the existing `DrivingStats` round-trip test) with one
  case: a `drivingStats` JSON object with no `obd2SpeedPct` key decodes to `0.0`; a value
  round-trips. Pure `shared` test, no hardware.
- **E, counting:** covered by the same replay pass as A — a replay with no adapter must finish
  with `obd2SpeedPct == 0.0`. The non-zero path needs a physical adapter and is part of the
  user's on-device follow-up, flagged the same way the base OBD2 spec flagged its pairing+drive
  verification.

## Commit structure

Independently revertible, same convention as the base OBD2 branch:

1. `shared`: add `DrivingStats.obd2SpeedPct` + encode/decode + the decode test.
2. `app`: `resolveDisplaySpeedMps` helper, `onLocation` call site, `effectiveSpeedMps` collapse
   (piece A, standalone and compilable — `_lastFix` now carries resolved speed).
3. `app`: the two counters, `endTrip` wiring, history + trip-detail display (piece E).

## Verification

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

Then the `detour-gps-replay` A/B pass described under Testing.

## Versioning

Piece A fixes display and relay code that ignored a speed source it should have used — a bug fix,
no API or data-format break. Piece E is a new backward-compatible feature: a new persisted
`DrivingStats` field (old trips decode at the zero default, same as #61's fields) plus new UI in
the history list and trip detail. `CONTRIBUTING.md`'s "mixed feature + fix bumps for the higher of
the two (minor)" applies -> **minor** bump. `app/build.gradle.kts` `versionName` `1.79.0` ->
`1.80.0`, in the piece-E commit (commit 3), which is the one that adds the feature surface.
