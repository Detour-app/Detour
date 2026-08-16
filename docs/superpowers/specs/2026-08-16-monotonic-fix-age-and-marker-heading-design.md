# Monotonic fix age, and a marker heading that glides

Closes maxke24/Detour#39 and maxke24/Detour#38. Both were found while reviewing #21
(`docs/superpowers/specs/2026-08-15-smooth-map-motion-design.md`), both live in the two
`withFrameNanos` loops that PR created, and both are small enough that splitting them across
two pull requests would cost more review than it saves.

## Scope

In scope:

- **#39** — `MapMotion.predict` measures a fix's age by subtracting a provider wall clock from
  `System.currentTimeMillis()`. Two clocks that only usually agree, so a persistently skewed
  device clock biases every prediction in one direction.
- **#38** — the position marker's *position* is interpolated per frame, but its *heading* is
  still written once per GPS fix, so the dot glides while its nose steps at ~1 Hz.

Out of scope, with reasons:

- **#37** (`CarMapRenderer` still carries all four defects #21 fixed for the phone) — a
  different surface that cannot be verified without a head unit, and one that needs its own
  push-rate decision rather than a copy of the phone's.
- **#33** (one camera owner costs recomposition during a drag) — the camera's *authority*, not
  its motion. Unmeasured, and its own issue asks for a measurement before the fix.

## #39 — measure a fix's age on a monotonic clock

### The defect

`MapMotion.predict` carries a fix forward by `speedMps * (age + lead)`:

```kotlin
val ageMs = (nowMs - fixTimeMs).coerceIn(0L, MAX_PREDICT_MS)
```

`fixTimeMs` is `Fix.timeMs`, which is `location.time` (`TripTrackingService.kt:975`) — provider
wall-clock UTC. `nowMs` is `System.currentTimeMillis()`. A device clock running persistently
fast produces a constant forward offset in the prediction, for both the camera and the marker.
`MAX_PREDICT_MS = 1500` bounds it at 1.5 s of travel — 50 m at 120 km/h — but it is a *steady*
bias rather than noise, so it does not average out, and each new fix re-establishes it rather
than correcting it.

### The change

`Fix` gains one field beside the existing wall clock rather than replacing it:

```kotlin
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Float?,
    val accuracyMeters: Float,
    /** Provider wall-clock UTC. For anything that leaves this device. */
    val timeMs: Long,
    /** SystemClock.elapsedRealtime() basis. For measuring this fix's own age. */
    val elapsedRealtimeMs: Long,
)
```

Populated from `location.elapsedRealtimeNanos / 1_000_000` at `TripTrackingService.kt:975`.
Android documents `elapsedRealtimeNanos` as the field to use for a fix's age, and it shares a
basis with `SystemClock.elapsedRealtime()`, which this file already uses elsewhere.

`Fix` has exactly one construction site (`TripTrackingService.kt:969`), so the field addition
touches one constructor.

`Fix.timeMs` has exactly four read sites. Each is routed by the question it is actually asking:

| Site | Question it asks | Clock |
|---|---|---|
| `MapScreen.kt:1122` (camera loop) | how old is this fix | **monotonic** — change |
| `MapScreen.kt:1199` (marker loop) | how old is this fix | **monotonic** — change |
| `TripTrackingService.kt:1207` (`fixAgeMs`, circle sync) | how old is this fix | **monotonic** — change |
| `ConvoyLiveClient.kt:431` (`put("ts", …)`) | when did this happen, absolutely | wall clock — unchanged |

The convoy wire stamp stays on the wall clock deliberately: it is read by *other devices*, which
have no way to interpret this device's uptime.

`MapMotion.predict` renames its two time parameters `fixTimeMs`/`nowMs` → `fixElapsedMs`/
`nowElapsedMs`, so a future caller cannot pass a wall clock without noticing. Its KDoc on
`MAX_PREDICT_MS` loses the "a device clock a minute off would scale the prediction without
bound" justification — **the clamp itself stays**, because it also bounds a tunnel and a GPS
dropout, which a monotonic clock does not.

Note that #39's own cost estimate is wider than the tree supports: it names GPX export and
`HistoryScreen` as consumers, but both read `TracePoint.timeMs`, not `Fix.timeMs`. They are
untouched.

## #38 — ease the marker's heading

### The defect

The marker loop calls `overlays.setPosition(here, camTargetBearing?.toDouble())`.
`camTargetBearing` is assigned only in the per-fix effect (`MapScreen.kt:1052`), so it steps at
~1 Hz, while the camera loop eases the map's own bearing every frame. The icon is drawn with
`iconRotate(get("bearing"))` under `ICON_ROTATION_ALIGNMENT_MAP`, so what a rider sees is
**icon bearing minus map bearing** — one stepping at 1 Hz against one gliding at frame rate.

### The change

The marker loop gains its own eased bearing, rather than reading the camera loop's:

```kotlin
LaunchedEffect(mapOverlays, haveFix) {
    val overlays = mapOverlays ?: return@LaunchedEffect
    var lastLat = Double.NaN
    var lastLon = Double.NaN
    var markerBearing: Float? = null
    var lastNs = withFrameNanos { it }
    while (true) {
        val ns = withFrameNanos { it }
        val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
        lastNs = ns
        …
    }
}
```

easing toward `camTargetBearing` at `CAM_BEARING_TAU` through the existing `smoothBearing`
(`MapCameraTuning.kt:6`), which already accepts a null `current` and returns the target — so
`null` is the seed and the first frame does not swing.

**Its own accumulator, not the camera loop's eased value.** Sharing would guarantee the two
never diverge, but the camera loop returns early when `!cameraActive`, so a shared value would
freeze exactly when the issue says the stepping is most visible — the marker loop is
deliberately independent of `cameraActive` for that reason. Publishing the camera's bearing to
snapshot state per frame would also add a §6 hazard the app currently does not have.

Both loops ease the same target at the same tau on the same frame clock, so while the camera is
active they track each other and the visible difference stays near zero, which is the point.

### The push gate has to grow a bearing term

Not mentioned in the issue, and it must land in the same commit. The gate is currently
position-only:

```kotlin
if (here.lat != lastLat || here.lon != lastLon) { overlays.setPosition(…) }
```

With an eased bearing, a stationary vehicle mid-rotation would ease its nose and never push it.
The gate gains a bearing term against `CAM_BEARING_EPS_DEG` (already defined,
`MapCameraTuning.kt:41`), so a settled marker still goes quiet — the same standstill
optimisation the camera's `shouldPush` keeps.

### Guards that move with it

- `.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh` asserts 6
  `withFrameNanos` lines in `MapScreen.kt` and its message says the marker loop "needs no
  `dt`". Both become false: the count goes to 8 (the marker loop gains a `lastNs` seed and
  read), and the clause has to go.
- The marker loop's own comment block describes it as position-only interpolation.

## Commit structure

The compose-state-hazards skill's §4 rule — never change two `lastFix` consumers in one commit,
because six independent collectors means six independent blast radii — applies here: both loops
read `lastFix`. So three commits, each independently revertible:

1. **`Fix.elapsedRealtimeMs` + `MapMotion.predict` parameter rename.** Mechanical. Nothing
   outside `predict`'s own signature changes behaviour; the field is added and populated but the
   call sites still pass the wall-clock pair.
2. **Wire the two `predict` call sites and `circleSyncLoop` to the monotonic field.** This is
   the behaviour change, and it is one revert unit.
3. **Marker heading easing + push gate + precondition script + comments.**

## Verification

Per the compose-state-hazards skill, adding accumulators to a frame-loop effect (§3) and
changing what a `lastFix` consumer computes (§4) both earn a GPS replay A/B, not a compile.

**Repro gate for #38, first.** #38 was found by reading the diff and has never been seen on a
device; its own issue asks that it be looked at on hardware before anyone spends effort on it.
Replay a fixture with meaningful heading change on the connected phone, `.debug` variant,
follow **off** so the camera is parked — the state the issue says is worst, because nothing is
gliding underneath to mask the step. Screen-record 30 s. Named quantity: whether the dot's nose
visibly steps against a gliding position.

If it does not step, **#38 is dropped from this PR** and the observation is written into the PR
body. #39 carries the PR on its own.

**A/B for both changes.** Same route file, same `intervalMs`, same variant, before and after.
Named quantities, counted on both runs:

- the recorded trip's `distanceMeters` and `topSpeedMps` from `trips.json`;
- the trace point count, via `.claude/skills/detour-trip-data/scripts/profile-trace.py`.

Both are expected unchanged. A drift beyond a percent or two means the fix pipeline changed,
which neither change is supposed to do.

**Measurement hygiene.** maxke24/Detour#47: the phone's own GNSS interleaves with the mock
provider and no radio setting stops it — 56 of 198 samples were affected in a measured run.
Drop samples reporting `speedKmh = 0.0` while the route is moving, and report a **median** gap
rather than a mean, because a mean over kilometre-scale spikes is meaningless.

**What replay cannot show, stated rather than omitted.** #39's actual defect is clock skew, and
replay cannot exhibit it: `MockService.kt:116-117` stamps `time` and `elapsedRealtimeNanos` from
the same two clocks the app reads, in agreement, on every fix. The replay proves no regression;
the skew fix rests on the unit test and on Android's documented contract for
`elapsedRealtimeNanos`. This limitation belongs in the PR body.

**Gates before the PR.** `.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh` against
the merge base; then, in the devcontainer, `./gradlew :app:assembleDebug :app:assembleRelease`
(R8 catches what debug does not) and `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`.
`act` before pushing to a non-draft pull request, per the local-ci-act skill.

## Tests

- `app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt` — the existing 17 tests pass the
  two timestamps positionally, so the rename does not touch them and they keep asserting what
  they assert.

  **No new `predict` test is worth writing, and that is worth stating rather than papering
  over.** `predict` only ever subtracts one of its arguments from the other; it is clock-
  agnostic by construction and was already correct. The defect is entirely in *what the callers
  pass it*, and no unit test can reach `MapScreen`'s frame loops (this app has no Robolectric,
  no `compose-ui-test` and no `androidTest` source set). The guard against a future caller
  passing a wall clock is therefore the parameter rename and the KDoc, not a test. The existing
  clamp tests at `MapMotionTest.kt:59` and `:65` keep covering the tunnel/dropout ceiling and
  the negative-age floor, both of which survive the change unchanged.
- Nothing new for the marker loop either, for the same reason: it lives inside a composable and
  no test in this repo can reach it. `smoothBearing` is a pure top-level function already
  exercised through the camera path, so the piece that *can* be tested already is. The marker
  change is verified by replay and screen recording only, and the PR body should say so plainly
  rather than implying the unit suite covers it.
