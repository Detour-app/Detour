# Smooth Map Motion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the map move smoothly while driving by predicting the vehicle's current position, pushing the camera at frame rate rather than by displacement threshold, and interpolating the position marker.

**Architecture:** Four pure functions in `app/map/MapMotion.kt`, unit-tested under plain
JUnit4, called from `MapScreen`'s existing `withFrameNanos` loop plus one new marker loop.
No new snapshot state, no new effect keys on the existing loop, no new dependency.

**Tech Stack:** Kotlin, Jetpack Compose, MapLibre GL, JUnit4, the `tools/mocklocation`
replay harness.

Spec: [`../specs/2026-08-15-smooth-map-motion-design.md`](../specs/2026-08-15-smooth-map-motion-design.md).
Closes [#21](https://github.com/maxke24/Detour/issues/21).

## Global Constraints

- **All Gradle runs happen in the devcontainer.** Never on the host — the host JDK is 26 with
  no Android SDK. Prefix: `docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard`.
  Always the numeric uid:gid, never `-u dev`.
- **Never run a bare `./gradlew build`.** Name the task.
- **Never uninstall, `pm clear`, or `pm revoke` on the device.** A previous session destroyed
  a user's login and four saved traces doing that. `adb install -r` only.
- **Always start a replay with `start-replay.sh`, never by hand.** It force-stops the
  *release* app first. Skipping that records a fabricated ride into the user's real trip
  history and there is no undo short of deleting the trip by hand.
- **No new dependencies.**
- **No `Co-Authored-By` and no `Claude-Session` trailer** on any commit.
- **The existing camera loop's key list must stay exactly
  `LaunchedEffect(cameraActive, haveFix, mapLibreMap)`.** Its coroutine-local accumulators
  (`lat`, `lon`, `bearing`, `zoom`, `appliedLat`) reset whenever it re-keys, silently and with
  compiler approval. Adding or removing a key is a behaviour change.
- **Do not introduce `derivedStateOf` or `snapshotFlow`.** The app contains zero of each and
  the hazards skill asserts that.
- **Do not write new snapshot state per frame.** The `Scaffold` content lambda (`:1355`)
  already recomposes per frame from `displaySpeedKmh`; it must not gain a second source.
- **Reuse `RoadRoulette.offset` and `RoadRoulette.distanceMeters`.** Do not re-derive the
  `111_320.0` metres-per-degree constant.
- **`CAM_POS_TAU` and `CAM_BEARING_TAU` are not to be retuned** — the replay must measure one
  change.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/jellemax/detour/map/MapMotion.kt` | **Create.** The four pure decisions: predict, snap, push, and the constants' home is `MapCameraTuning`. |
| `app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt` | **Create.** Plain JUnit4, beside the four existing `map/` test classes. |
| `app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt` | **Modify.** Two new constants beside the existing camera tuning. |
| `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` | **Modify.** Frame loop (`:1080-1143`) and one new marker loop. |
| `.claude/skills/detour-compose-state-hazards/SKILL.md` | **Modify.** §3's claim about the loop's key list is wrong. |

---

### Task 1: MapMotion — the pure decisions

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/map/MapMotion.kt`
- Create: `app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt`

**Interfaces:**
- Consumes: `RoadRoulette.offset(center: LatLon, distanceMeters: Double, bearingRad: Double): LatLon` and `RoadRoulette.distanceMeters(a: LatLon, b: LatLon): Double`, both already in `shared/commonMain`.
- Produces, used by Tasks 3-6:
  - `MapMotion.predict(at: LatLon, bearingDeg: Float?, speedMps: Double, fixTimeMs: Long, nowMs: Long, leadSeconds: Double): LatLon`
  - `MapMotion.shouldSnap(from: LatLon, to: LatLon): Boolean`
  - `MapMotion.shouldPush(camLat: Double, camLon: Double, camZoom: Double, camBearing: Float, tgtLat: Double, tgtLon: Double, tgtZoom: Double, tgtBearing: Float, targetMoved: Boolean, neverPushed: Boolean): Boolean`
  - `MIN_PREDICT_MPS = 2.0`, `MAX_PREDICT_MS = 1500L` (in `MapMotion`)
  - `CAM_SNAP_METERS = 250.0` (in `MapCameraTuning.kt`)

- [ ] **Step 1: Add the constant**

Append to `app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt`, after the
`CAM_BEARING_EPS_DEG` block:

```kotlin
// Past this, a jump is not continuous motion and easing toward it would sweep the
// camera — and MapLibre's tile requests — across everything in between. A frame can
// legitimately move 3.3 m at most (120 km/h against the 0.1 s dt clamp), and GPS
// scatter stays well under this, so anything above it is a resume from background, a
// tunnel exit, or a first fix after an outage. Those all want a teleport.
internal const val CAM_SNAP_METERS = 250.0
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.ui.CAM_SNAP_METERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [MapMotion] — where the vehicle is *now* given a fix that is already old, and
 * when the camera should be pushed at all. Pure arithmetic and pure predicates, so no
 * Android APIs and no emulator; the frame loop that calls them is checked by a GPS
 * replay instead, because nothing in this module can assert on a composable.
 */
class MapMotionTest {

    private val brussels = LatLon(50.8503, 4.3517)
    private val t0 = 1_700_000_000_000L

    /** 100 km/h due north. */
    private val fast = 27.78

    @Test
    fun `a fix with no age and no lead is its own prediction`() {
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0, leadSeconds = 0.0)
        assertEquals(brussels.lat, p.lat, 1e-9)
        assertEquals(brussels.lon, p.lon, 1e-9)
    }

    @Test
    fun `prediction advances along the bearing by speed times elapsed`() {
        // 1.0 s old, no lead: 27.78 m due north.
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 + 1000, leadSeconds = 0.0)
        assertEquals(27.78, RoadRoulette.distanceMeters(brussels, p), 0.5)
        assertTrue("due north must increase latitude", p.lat > brussels.lat)
        assertEquals("due north must not move longitude", brussels.lon, p.lon, 1e-9)
    }

    @Test
    fun `the lead adds to the age rather than replacing it`() {
        // 0.5 s old + 0.35 s lead = 0.85 s of travel.
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 + 500, leadSeconds = 0.35)
        assertEquals(fast * 0.85, RoadRoulette.distanceMeters(brussels, p), 0.5)
    }

    @Test
    fun `bearing 90 moves east`() {
        val p = MapMotion.predict(brussels, 90f, fast, t0, t0 + 1000, leadSeconds = 0.0)
        assertTrue("due east must increase longitude", p.lon > brussels.lon)
        assertEquals("due east must not move latitude", brussels.lat, p.lat, 1e-9)
    }

    @Test
    fun `a stale fix is clamped rather than extrapolated without bound`() {
        // 60 s old. Unclamped that is 1.6 km; clamped it is 1.5 s of travel.
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 + 60_000, leadSeconds = 0.0)
        assertEquals(fast * 1.5, RoadRoulette.distanceMeters(brussels, p), 0.5)
    }

    @Test
    fun `a clock running behind the fix does not predict backwards`() {
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 - 5_000, leadSeconds = 0.0)
        assertEquals(brussels.lat, p.lat, 1e-9)
        assertEquals(brussels.lon, p.lon, 1e-9)
    }

    @Test
    fun `below two metres per second the reported bearing is noise, so nothing is predicted`() {
        val p = MapMotion.predict(brussels, 0f, 1.9, t0, t0 + 1000, leadSeconds = 0.35)
        assertSame(brussels, p)
    }

    @Test
    fun `without a bearing there is no direction to predict along`() {
        val p = MapMotion.predict(brussels, null, fast, t0, t0 + 1000, leadSeconds = 0.35)
        assertSame(brussels, p)
    }

    @Test
    fun `a jump beyond the snap threshold is not continuous motion`() {
        val far = RoadRoulette.offset(brussels, CAM_SNAP_METERS + 50.0, 0.0)
        assertTrue(MapMotion.shouldSnap(brussels, far))
    }

    @Test
    fun `a jump inside the snap threshold is eased, not teleported`() {
        val near = RoadRoulette.offset(brussels, CAM_SNAP_METERS - 50.0, 0.0)
        assertFalse(MapMotion.shouldSnap(brussels, near))
    }

    @Test
    fun `one frame of motorway travel never trips the snap`() {
        // 120 km/h against the loop's 0.1 s dt clamp is 3.3 m.
        val oneFrame = RoadRoulette.offset(brussels, 3.34, 0.0)
        assertFalse(MapMotion.shouldSnap(brussels, oneFrame))
    }

    @Test
    fun `a camera that has never been pushed always pushes`() {
        assertTrue(MapMotion.shouldPush(
            camLat = 0.0, camLon = 0.0, camZoom = 0.0, camBearing = 0f,
            tgtLat = 0.0, tgtLon = 0.0, tgtZoom = 0.0, tgtBearing = 0f,
            targetMoved = false, neverPushed = true))
    }

    @Test
    fun `a converged camera with a still target does no work`() {
        assertFalse(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 90f,
            tgtLat = brussels.lat, tgtLon = brussels.lon, tgtZoom = 16.0, tgtBearing = 90f,
            targetMoved = false, neverPushed = false))
    }

    @Test
    fun `a moving target pushes even when the camera has caught up to it`() {
        // This is the whole point: the old gate compared the frame's step against the
        // last pushed value, so a slow camera looked settled and stepped instead of
        // gliding. Same position, but the target moved this frame.
        assertTrue(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 90f,
            tgtLat = brussels.lat, tgtLon = brussels.lon, tgtZoom = 16.0, tgtBearing = 90f,
            targetMoved = true, neverPushed = false))
    }

    @Test
    fun `an unconverged camera pushes even when the target is still`() {
        assertTrue(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 90f,
            tgtLat = brussels.lat + 0.001, tgtLon = brussels.lon, tgtZoom = 16.0,
            tgtBearing = 90f, targetMoved = false, neverPushed = false))
    }

    @Test
    fun `bearing convergence wraps across north`() {
        // 359.95 vs 0.05 is 0.1 apart, not 359.9 — inside CAM_BEARING_EPS_DEG.
        assertFalse(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 359.95f,
            tgtLat = brussels.lat, tgtLon = brussels.lon, tgtZoom = 16.0, tgtBearing = 0.05f,
            targetMoved = false, neverPushed = false))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.map.MapMotionTest"
```

Expected: **FAIL** at compilation — `Unresolved reference: MapMotion`. A compile failure is
the correct red here.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/jellemax/detour/map/MapMotion.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.ui.CAM_BEARING_EPS_DEG
import com.jellemax.detour.ui.CAM_POS_EPS_DEG
import com.jellemax.detour.ui.CAM_SNAP_METERS
import com.jellemax.detour.ui.CAM_ZOOM_EPS
import kotlin.math.PI
import kotlin.math.abs

/**
 * Where the vehicle is *now*, and whether the camera has any work to do this frame.
 *
 * A fix is old before it is ever drawn — it takes time to acquire and arrives about once a
 * second — and a first-order ease chasing it settles a further `v * tau` behind. Easing
 * toward the raw fix therefore guarantees a lag that no value of tau removes: lowering it
 * trades the lag back for the per-fix jerk the ease exists to smooth. Predicting forward
 * converts a guaranteed lag into a bounded error that each new fix re-anchors.
 *
 * Kept here rather than in `shared/` deliberately: iOS has no easing loop — MapLibre iOS
 * animates its own camera — so the consumers are MapScreen and, later, CarMapRenderer, both
 * under `app/`. This belongs beside [FollowCamera] and [CameraAuthority].
 */
object MapMotion {

    /** Below this the reported bearing is noise, so there is no direction to predict along. */
    const val MIN_PREDICT_MPS = 2.0

    /**
     * Longest gap worth extrapolating across. [com.jellemax.detour.tracking.Fix.timeMs] is
     * `location.time` — wall-clock provider time, not monotonic — so without a ceiling a
     * device clock a minute off would scale the prediction without bound. Also bounds a
     * tunnel and a GPS dropout, with the same one mechanism: 1.5 s is 50 m at 120 km/h.
     */
    const val MAX_PREDICT_MS = 1500L

    /**
     * [at] carried forward along [bearingDeg] by however far [speedMps] covers in the fix's
     * age plus [leadSeconds].
     *
     * The lead is what cancels the ease's own lag. A first-order lag driven at constant
     * velocity settles `v * tau` behind its input, so a target that leads by exactly tau
     * puts the *camera* on the true position rather than the target. Pass 0.0 for anything
     * drawn directly, such as the position marker.
     *
     * Returns [at] unchanged when there is nothing to predict from.
     */
    fun predict(
        at: LatLon,
        bearingDeg: Float?,
        speedMps: Double,
        fixTimeMs: Long,
        nowMs: Long,
        leadSeconds: Double,
    ): LatLon {
        if (bearingDeg == null || speedMps < MIN_PREDICT_MPS) return at
        val ageMs = (nowMs - fixTimeMs).coerceIn(0L, MAX_PREDICT_MS)
        val seconds = ageMs / 1000.0 + leadSeconds
        if (seconds <= 0.0) return at
        return RoadRoulette.offset(at, speedMps * seconds, bearingDeg * PI / 180.0)
    }

    /**
     * True when [to] is too far from [from] to be continuous motion, so the camera should be
     * placed there rather than eased toward it.
     *
     * Without this, returning to the app after driving away with it backgrounded eases the
     * camera across the whole distance: `camTarget` keeps tracking from a raw collector on a
     * foreground service, while the frame loop's position is a coroutine local that never
     * re-anchors, and the 0.1 s dt clamp still closes ~25% of the gap per frame.
     */
    fun shouldSnap(from: LatLon, to: LatLon): Boolean =
        RoadRoulette.distanceMeters(from, to) > CAM_SNAP_METERS

    /**
     * True while the camera still has work: it has not converged on its target, or the
     * target itself moved since the last frame.
     *
     * The question this replaces was "did *this frame* move enough to be worth a redraw",
     * which cannot tell a slow camera from a settled one. At CAM_POS_EPS_DEG — 0.14 m of
     * longitude at 51N — a 20 km/h camera moves 0.09 m per frame and so was pushed only
     * every third frame, which is the visible stepping. Asking instead whether the camera is
     * *at rest* keeps the idle-map optimisation (a parked map still does no work) without
     * quantising a moving one.
     */
    fun shouldPush(
        camLat: Double,
        camLon: Double,
        camZoom: Double,
        camBearing: Float,
        tgtLat: Double,
        tgtLon: Double,
        tgtZoom: Double,
        tgtBearing: Float,
        targetMoved: Boolean,
        neverPushed: Boolean,
    ): Boolean {
        if (neverPushed || targetMoved) return true
        var dBearing = (camBearing - tgtBearing) % 360f
        if (dBearing > 180f) dBearing -= 360f
        if (dBearing < -180f) dBearing += 360f
        val converged = abs(camLat - tgtLat) <= CAM_POS_EPS_DEG &&
            abs(camLon - tgtLon) <= CAM_POS_EPS_DEG &&
            abs(camZoom - tgtZoom) <= CAM_ZOOM_EPS &&
            abs(dBearing) <= CAM_BEARING_EPS_DEG
        return !converged
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.map.MapMotionTest"
```

Expected: `BUILD SUCCESSFUL`. Confirm the count rather than trusting the exit code:

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-com.jellemax.detour.map.MapMotionTest.xml
```

Expected: `tests="16" skipped="0" failures="0" errors="0"`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/map/MapMotion.kt \
        app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt \
        app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt
git commit -m "feat(map): add MapMotion — prediction, snap and push decisions

Pure functions so the decisions behind a frame loop are testable at all: this
module has plain JUnit4 and no Robolectric, so nothing can assert on the
composable that will call them.

predict takes a lead as well as the fix's age, because easing toward the
vehicle's *current* position still settles v*tau behind it. Leading by tau
cancels that term exactly at constant velocity."
```

---

### Task 2: Instrumentation, and the BEFORE baseline

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (frame loop, `:1080-1143`)

**Interfaces:**
- Consumes: nothing.
- Produces: logcat tag `DetourMapMotion`, one line per second, consumed by Task 7.

This task changes no behaviour. It exists so the "before" numbers are measured on the same
build and the same route as the "after" ones. The counters are removed in Task 7.

- [ ] **Step 1: Add the counters to the camera loop**

In `MapScreen.kt`, inside the `LaunchedEffect(cameraActive, haveFix, mapLibreMap)` body,
after the line `var lastNs = withFrameNanos { it }` (currently `:1102`), add:

```kotlin
        // TEMPORARY (#21 measurement) — removed before merge.
        var instFrames = 0
        var instPushes = 0
        var instLastLogNs = lastNs
```

Then, immediately after the `if (moved) { … }` block closes (currently `:1142`), still inside
the `while (true)` loop, add:

```kotlin
            // TEMPORARY (#21 measurement) — removed before merge.
            instFrames++
            if (moved) instPushes++
            if (ns - instLastLogNs >= 1_000_000_000L) {
                val f = liveFix
                val gapM = if (f != null)
                    RoadRoulette.distanceMeters(LatLon(lat, lon), LatLon(f.lat, f.lon))
                else Double.NaN
                android.util.Log.d(
                    "DetourMapMotion",
                    "cam frames=$instFrames pushes=$instPushes " +
                        "speedKmh=${"%.1f".format((f?.speedMps ?: 0.0) * 3.6)} " +
                        "gapM=${"%.1f".format(gapM)}")
                instFrames = 0
                instPushes = 0
                instLastLogNs = ns
            }
```

`RoadRoulette` and `LatLon` are already imported in this file; confirm with
`grep -n "import com.jellemax.detour.data.RoadRoulette\|import com.jellemax.detour.data.LatLon" app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`
and add whichever is missing.

- [ ] **Step 2: Build and install**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`, then `Success`. **Never `adb uninstall`.**

- [ ] **Step 3: Capture the BEFORE baseline**

Open the app to the map screen and leave it foregrounded. Then:

```bash
adb logcat -c
.claude/skills/detour-gps-replay/scripts/start-replay.sh \
  tools/mocklocation/routes/urban-limits.txt RFCT42HS9WY 1000
```

Let it run for **three minutes**, capturing:

```bash
timeout 180 adb logcat -s DetourMapMotion > /tmp/claude-1000/-home-andre-Projects-Detour/*/scratchpad/before.log
.claude/skills/detour-gps-replay/scripts/stop-replay.sh
```

Then summarise, bucketed by speed, because the epsilon stall is speed-dependent and an
average over the whole route would hide it:

```bash
awk '/cam frames=/ {
  for (i=1;i<=NF;i++) {
    if ($i ~ /^frames=/)   { split($i,a,"="); fr=a[2] }
    if ($i ~ /^pushes=/)   { split($i,a,"="); pu=a[2] }
    if ($i ~ /^speedKmh=/) { split($i,a,"="); sp=a[2] }
    if ($i ~ /^gapM=/)     { split($i,a,"="); gp=a[2] }
  }
  b = (sp<15) ? "00-15" : (sp<35) ? "15-35" : (sp<60) ? "35-60" : "60+"
  n[b]++; F[b]+=fr; P[b]+=pu; G[b]+=gp
}
END { printf "%-8s %6s %8s %8s %8s\n","speed","n","frames/s","pushes/s","gap_m"
      for (b in n) printf "%-8s %6d %8.1f %8.1f %8.1f\n", b, n[b], F[b]/n[b], P[b]/n[b], G[b]/n[b] }' \
  before.log | sort
```

Record the table verbatim in the task report. **The prediction this is testing:** in the
`15-35` bucket `pushes/s` should be well below `frames/s`, and `gap_m` in the `35-60` bucket
should be near 11.8. If `pushes/s ≈ frames/s` everywhere, #21 §2 does not reproduce on this
device and that is a finding to report before continuing.

- [ ] **Step 4: Commit the instrumentation**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "test(map): instrument the camera loop for #21 measurement

Temporary. Counts frames, camera pushes and camera-to-fix distance once a
second so the before/after replay compares numbers rather than impressions.
Removed in the commit that reports the results."
```

---

### Task 3: Dead reckoning

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (frame loop ease block)

**Interfaces:**
- Consumes: `MapMotion.predict(...)` from Task 1.
- Produces: a `camTargetNow` local inside the loop, used by Tasks 4 and 5.

**The prediction runs per frame, not per fix.** Setting `camTarget` once per fix would make
the ease chase a staircase that jumps forward once a second — the camera would still
micro-stutter at 1 Hz. The collector at `:1045` is therefore left alone; the loop predicts
from the live fix on every frame so the target advances continuously.

`liveFix` may be read directly inside the loop without `rememberUpdatedState`: it is a
delegated property from `collectAsStateWithLifecycle`, so a closure capturing it reads live
values. This is the mechanism the hazards skill §2 describes; adding a redundant guard here
would misrepresent why the direct read is safe.

- [ ] **Step 1: Replace the ease's target**

In the `while (true)` loop, replace this block (currently `:1109-1113`):

```kotlin
            camTarget?.let { target ->
                val a = 1.0 - exp(-dt / CAM_POS_TAU)
                lat += (target.lat - lat) * a
                lon += (target.lon - lon) * a
            }
```

with:

```kotlin
            // Where the vehicle is now, plus CAM_POS_TAU of lead. The lead is what
            // cancels the ease's own steady-state error: a first-order lag driven at
            // constant velocity settles v*tau behind its input, so aiming tau ahead
            // leaves the camera on the true position instead of behind it.
            val f = liveFix
            val camTargetNow = if (f != null) MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixTimeMs = f.timeMs,
                nowMs = System.currentTimeMillis(),
                leadSeconds = CAM_POS_TAU,
            ) else camTarget
            camTargetNow?.let { target ->
                val a = 1.0 - exp(-dt / CAM_POS_TAU)
                lat += (target.lat - lat) * a
                lon += (target.lon - lon) * a
            }
```

- [ ] **Step 2: Add the import**

Add to `MapScreen.kt`'s import block, in alphabetical position:

```kotlin
import com.jellemax.detour.map.MapMotion
```

- [ ] **Step 3: Verify it compiles and the key list is untouched**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:compileDebugKotlin
grep -c 'LaunchedEffect(cameraActive, haveFix, mapLibreMap)' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
```

Expected: `BUILD SUCCESSFUL`, then `1`. If the second is not 1, the loop's key list changed —
revert, because that resets accumulators silently.

- [ ] **Step 4: Run the Tier 0 greps**

```bash
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh HEAD~1 \
  app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
```

Expected: no drop in the `rememberUpdatedState` count, and the effect declaration lines it
prints should show the camera loop's keys unchanged. Read its output; do not just check the
exit code.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): ease toward where the vehicle is, not where it was

Closes the third cause in #21. A fix is already old when it arrives, and a
first-order ease settles a further v*tau behind it, so the camera trails by
about 11.8 m at 50 km/h and 28 m at 120. Neither term is fixable by tuning
tau: lowering it trades the lag back for the per-fix jerk the ease exists to
smooth.

Predicted per frame rather than per fix on purpose. Setting the target once
per fix advances it as a staircase, and the camera chasing a staircase still
stutters at 1 Hz; predicting each frame gives it a continuously moving target.

liveFix is read directly rather than through rememberUpdatedState because it
is a delegated collectAsStateWithLifecycle property — a closure capturing it
already reads live values."
```

---

### Task 4: The snap guard

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (frame loop ease block)

**Interfaces:**
- Consumes: `MapMotion.shouldSnap(from, to)` from Task 1; `camTargetNow` from Task 3.
- Produces: nothing.

This fixes a **pre-existing** defect, unrelated to smoothness, that lives in the lines Task 3
just rewrote. `camTarget` keeps tracking while the app is backgrounded — the fix collector is
a raw `.collect` and `TripTrackingService` is a foreground service — while the loop's `lat` /
`lon` are coroutine locals, and none of its three keys change across a background/foreground
cycle. Returning after 100 km therefore eases across the whole distance at ~25% of the gap
per frame, requesting tiles the entire way.

- [ ] **Step 1: Wrap the ease in the snap test**

Replace the `camTargetNow?.let { … }` block added in Task 3 with:

```kotlin
            camTargetNow?.let { target ->
                if (MapMotion.shouldSnap(LatLon(lat, lon), target)) {
                    // Too far to be continuous motion — a resume from background, a
                    // tunnel exit, a first fix after an outage. Easing across it would
                    // sweep the camera, and MapLibre's tile requests, over everything
                    // in between.
                    lat = target.lat
                    lon = target.lon
                } else {
                    val a = 1.0 - exp(-dt / CAM_POS_TAU)
                    lat += (target.lat - lat) * a
                    lon += (target.lon - lon) * a
                }
            }
```

- [ ] **Step 2: Verify**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:compileDebugKotlin
grep -c 'LaunchedEffect(cameraActive, haveFix, mapLibreMap)' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
```

Expected: `BUILD SUCCESSFUL`, then `1`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): teleport the camera across a jump too large to be motion

Pre-existing, found while rewriting these lines for #21. camTarget keeps
tracking from a raw collector on a foreground service while the app is
backgrounded, but the frame loop's position is a coroutine local and none of
the loop's keys change across a background/foreground cycle, so it never
re-anchors. Come back after 100 km and the ease closes ~25% of the gap per
frame against the 0.1 s dt clamp — the camera sweeps the whole distance in
about fifteen frames and pulls tiles the entire way."
```

---

### Task 5: The rate-based gate

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (frame loop gate, `:1129-1142`)

**Interfaces:**
- Consumes: `MapMotion.shouldPush(...)` from Task 1; `camTargetNow` from Task 3.
- Produces: nothing.

- [ ] **Step 1: Track the previous frame's target**

Beside the other `applied…` locals (currently `:1098-1101`), add:

```kotlin
        var lastTargetLat = Double.NaN
        var lastTargetLon = Double.NaN
```

- [ ] **Step 2: Replace the gate**

Replace the three-line comment at `:1123-1125` (it describes the old test), the `dBearing`
computation at `:1126-1128`, the `moved` expression at `:1129-1133`, and the
`if (moved) { … }` block at `:1134-1141` — i.e. everything from
`// along on its own. Only pushed when…` through that block's closing brace — with:

```kotlin
            // Push while the ease has not converged, or while the target itself is
            // moving. The old test compared this frame's step against the last pushed
            // value, which cannot tell a slow camera from a settled one: at 20 km/h a
            // frame moves 0.09 m against a 0.14 m threshold, so the camera was pushed
            // every third frame and stepped visibly. A parked map still does no work,
            // because then the target is still and the camera has converged on it.
            val targetMoved = camTargetNow != null &&
                (camTargetNow.lat != lastTargetLat || camTargetNow.lon != lastTargetLon)
            if (camTargetNow != null) {
                lastTargetLat = camTargetNow.lat
                lastTargetLon = camTargetNow.lon
            }
            val moved = MapMotion.shouldPush(
                camLat = lat, camLon = lon, camZoom = zoom, camBearing = bearing,
                tgtLat = camTargetNow?.lat ?: lat, tgtLon = camTargetNow?.lon ?: lon,
                tgtZoom = camTargetZoom, tgtBearing = camTargetBearing ?: bearing,
                targetMoved = targetMoved,
                neverPushed = appliedLat.isNaN(),
            )
            if (moved) {
                setCamera(map, lat, lon, zoom, bearing)
                appliedLat = lat
                appliedLon = lon
                appliedZoom = zoom
                appliedBearing = bearing
            }
```

The `applied…` locals are kept: `appliedLat.isNaN()` is still the never-pushed sentinel, and
they document what was last sent. `dBearing` moves inside `MapMotion.shouldPush`, so its
local declaration is deleted here.

- [ ] **Step 3: Verify**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
grep -c 'CAM_POS_EPS_DEG' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
```

Expected: `BUILD SUCCESSFUL`, then `0` — the epsilon comparison now lives in `MapMotion`, so
`MapScreen` no longer names those constants. If it is not 0, a comparison was left behind.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): gate the camera push on rest, not on this frame's step

Closes the second cause in #21. CAM_POS_EPS_DEG is 0.14 m of longitude at
51N; at 20 km/h a frame moves 0.09 m, so the camera was pushed only every
third frame and stepped. The test asked whether this frame moved enough to be
worth a redraw, which cannot distinguish a slow camera from a stopped one.

Asking instead whether the camera is at rest — converged on a target that
itself is not moving — keeps the idle-map optimisation intact while letting a
slowly moving one glide."
```

---

### Task 6: The per-frame marker

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (new effect, and the comment at `:635`)

**Interfaces:**
- Consumes: `MapMotion.predict(...)` from Task 1; `MapOverlays.setPosition(at, bearingDeg)`.
- Produces: nothing.

The marker gets its **own** loop, independent of `cameraActive`. #21 observes the stepping is
worst when the camera is parked, and the camera loop early-returns in exactly that case.
Folding this into the camera loop would mean deleting that early return and making its
"level back to north-up" one-shot edge-triggered, perturbing accumulators whose resets are
silent.

Lead is `0.0`, not `CAM_POS_TAU`: the marker is drawn directly rather than eased, so it wants
the vehicle's actual current position. The camera's lead exists to cancel the *ease*, which
the marker does not have.

- [ ] **Step 1: Add the marker loop**

Insert immediately after the camera loop's closing `}` (after what is currently `:1143`):

```kotlin
    // The dot, interpolated per frame. It used to be re-placed only when a fix arrived,
    // about once a second, at the raw fix position — so it stepped forward and the camera
    // slid after it. Worst when the camera is parked (after a pan, with follow off, or
    // with a spin result up), because then nothing is gliding underneath to mask it, which
    // is why this loop is deliberately independent of cameraActive.
    //
    // setPosition writes one point into SRC_POSITION. render() rewrites eight sources
    // including the route line, and doing *that* per frame is what makes a head unit
    // crawl — see MapOverlays.setPosition's own note.
    LaunchedEffect(mapOverlays, haveFix) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        while (true) {
            withFrameNanos { it }
            val f = liveFix ?: continue
            val here = MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixTimeMs = f.timeMs,
                nowMs = System.currentTimeMillis(),
                leadSeconds = 0.0,
            )
            if (here.lat != lastLat || here.lon != lastLon) {
                overlays.setPosition(here, camTargetBearing?.toDouble())
                lastLat = here.lat
                lastLon = here.lon
                // TEMPORARY (#21 measurement) — removed before merge.
                android.util.Log.d("DetourMapMotion", "marker push")
            }
        }
    }
```

- [ ] **Step 2: Correct the stale comment**

The comment at `:635` inside the overlay render effect now states the opposite of what
happens. Replace:

```kotlin
            // Marker updates per fix (~1 Hz); the eased camera glides the map
            // under it, so it stays smooth without a per-frame source rewrite.
```

with:

```kotlin
            // The marker is interpolated per frame by its own loop below; this render
            // only needs to place it when the overlay set is rebuilt.
```

- [ ] **Step 3: Verify**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
grep -c 'withFrameNanos' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
```

Expected: `BUILD SUCCESSFUL`, then `6`. It was 5 — one import, plus two loops that each call
`withFrameNanos` twice (once to seed `lastNs`, once per iteration). The marker loop needs no
`dt`, so it calls it once and adds exactly one line. This intentionally breaks the hazards
skill's "5 `withFrameNanos` lines" precondition, which Task 8 updates.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): interpolate the position marker per frame

Closes the first cause in #21. The dot was re-placed only when a fix arrived,
about once a second, at the raw fix position — so it stepped ahead of a camera
that was itself lagging, then the camera slid after it. That is the visible
lag-and-catch-up.

Its own loop rather than the camera's, because the stepping is worst exactly
when the camera loop early-returns: parked after a pan, follow off, or a spin
result on screen, with nothing gliding underneath to mask it.

Lead is zero here. The camera leads by tau to cancel its ease; the marker is
drawn directly and wants the vehicle's actual position."
```

---

### Task 7: The AFTER measurement, and removing the instrumentation

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (delete the counters)

**Interfaces:**
- Consumes: the `before.log` summary from Task 2.
- Produces: the before/after table for the PR description.

- [ ] **Step 1: Build, install, and capture AFTER on the same route**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app to the map screen, foregrounded. Then exactly as in Task 2:

```bash
adb logcat -c
.claude/skills/detour-gps-replay/scripts/start-replay.sh \
  tools/mocklocation/routes/urban-limits.txt RFCT42HS9WY 1000
timeout 180 adb logcat -s DetourMapMotion > after.log
.claude/skills/detour-gps-replay/scripts/stop-replay.sh
```

Summarise with the **same** awk from Task 2 Step 3, and additionally count marker pushes:

```bash
grep -c 'marker push' after.log   # divide by 180 for pushes/sec
```

- [ ] **Step 2: Answer the open question about the frame clock**

The spec commits to measuring this rather than asserting it. With the replay running,
background the app for 30 s, then foreground it:

```bash
adb logcat -c
adb shell input keyevent KEYCODE_HOME
sleep 30
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
sleep 5
adb logcat -d -s DetourMapMotion | head -20
```

If `DetourMapMotion` lines appear during the 30 s gap, Compose's frame clock keeps ticking
while the Activity is stopped and the camera tracks continuously in background. If they stop
and resume, it pauses and the snap guard is load-bearing. **Record which, verbatim** — the
spec explicitly flags this as a belief rather than an observation.

- [ ] **Step 3: Record the comparison**

Write both tables into the task report. The claims being tested:

| Quantity | Expected before | Expected after |
|---|---|---|
| `pushes/s` in the 15-35 km/h bucket | well below `frames/s` | ≈ `frames/s` |
| marker pushes/s | ~1 | ≈ frame rate |
| `gap_m` at 35-60 km/h | ≈ 11.8 | substantially lower |

If any of the three does **not** move in the expected direction, say so plainly and stop —
that is a finding, not a rounding error. A result that contradicts the diagnosis is worth
more than a green tick.

- [ ] **Step 4: Remove the instrumentation**

Delete every block marked `// TEMPORARY (#21 measurement)` — the three declarations and the
logging block in the camera loop, and the one `Log.d` in the marker loop. Then:

```bash
grep -c 'TEMPORARY (#21 measurement)' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
grep -c 'DetourMapMotion' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleRelease
```

Expected: `0`, `0`, then `BUILD SUCCESSFUL`. `assembleRelease` is included because R8 catches
what debug does not.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "test(map): remove the #21 measurement instrumentation

The numbers are in the PR description and the commit history; the counters
do not belong in a shipped APK."
```

---

### Task 8: Correct the hazards skill

**Files:**
- Modify: `.claude/skills/detour-compose-state-hazards/SKILL.md`
- Modify: `.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

Two of the skill's statements are now wrong. A skill that misdescribes the code is worse than
no skill, because it is trusted.

- [ ] **Step 1: Fix §3's claim about the key list**

§3 ends with: "All of these sit in `LaunchedEffect(Unit)` or in a key list that cannot
currently change. That is the only thing protecting them."

That is untrue of the camera loop: it is keyed on `cameraActive`, which is
`camAuthority.cameraActive(navigating)` and flips on every pan, park and resume. Replace that
sentence with:

```markdown
Most of these sit in `LaunchedEffect(Unit)` or in a key list that cannot currently change.
**The camera loop is the exception, and it is worth knowing which way round it is:** it is
keyed `LaunchedEffect(cameraActive, haveFix, mapLibreMap)`, and `cameraActive` is
`camAuthority.cameraActive(navigating)`, so it re-keys on every pan, park and resume. Its
accumulators therefore reset routinely rather than never — the ease re-anchors at the current
target, which is what makes a resume snap to you rather than slide. Do not "fix" that by
removing the key.
```

- [ ] **Step 2: Update the `withFrameNanos` precondition**

`check-preconditions.sh` asserts 5 `withFrameNanos` lines in `MapScreen.kt`. There are now 6:
the import, two lines each for the speed and camera loops, and one for the marker loop, which
needs no `dt` and so does not seed a `lastNs`. Update the expected count and the message so
it names the third loop (the position marker).

- [ ] **Step 3: Verify the script passes**

```bash
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

Expected: `5 checks, 0 failed`, exit 0.

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/detour-compose-state-hazards/
git commit -m "docs(skill): the camera loop's keys do change, contrary to section 3

It is keyed on cameraActive, which flips on every pan, park and resume, so
its accumulators reset routinely rather than never. The skill said the
opposite, and a skill that misdescribes the code it documents is worse than
none because it is trusted.

Also updates the withFrameNanos count for the marker loop #21 added."
```

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| Preconditions 1-8 | Task 3 Step 3 and Task 5 Step 3 assert the inverted ones flip; Task 8 Step 3 re-runs the skill script |
| `MapMotion.predict` | Task 1 |
| `MapMotion.shouldSnap` | Task 1 |
| `MapMotion.shouldPush` | Task 1 |
| Lead compensation (`age + CAM_POS_TAU`) | Task 1 impl, Task 3 wiring |
| Clamp `MAX_PREDICT_MS` | Task 1, tested |
| No prediction below 2 m/s / without bearing | Task 1, tested |
| Rate-based gate | Task 5 |
| Per-frame marker, own loop, `cameraActive`-independent | Task 6 |
| Snap guard, own commit | Task 4 |
| `CAM_SNAP_METERS` in `MapCameraTuning.kt` | Task 1 Step 1 |
| Hazards §1/§3 — key list unchanged | Asserted in Tasks 3 and 4 Step 3 |
| Hazards §2 — no stale capture | Task 3's note on why the direct `liveFix` read is correct |
| Hazards §4 — no `lastFix` collector change | No task adds one; `setPosition` is synchronous |
| Hazards §6 — no new per-frame snapshot writes | Task 6 writes to MapLibre directly, not to state |
| Instrumented replay, named quantities | Tasks 2 and 7 |
| Instrumentation added and removed in its own commits | Tasks 2 and 7 |
| Frame-clock-in-background open question | Task 7 Step 2 |
| Skill correction | Task 8 |
| Out of scope: car, iOS, retuning tau | No task, correctly |

**Placeholder scan:** none. Every code step carries complete code; every command carries its
expected output.

**Type consistency:** `predict(at, bearingDeg, speedMps, fixTimeMs, nowMs, leadSeconds)`,
`shouldSnap(from, to)` and `shouldPush(camLat, camLon, camZoom, camBearing, tgtLat, tgtLon,
tgtZoom, tgtBearing, targetMoved, neverPushed)` are defined in Task 1 and called with those
exact parameter names in Tasks 3, 4, 5 and 6. `camTargetNow` is introduced in Task 3 and
consumed under that name in Tasks 4 and 5. `CAM_SNAP_METERS` is defined in Task 1 Step 1 and
used in Task 1 Step 4 and the Task 1 test.
