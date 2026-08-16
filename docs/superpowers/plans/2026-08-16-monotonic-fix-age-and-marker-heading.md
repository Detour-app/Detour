# Monotonic Fix Age and Marker Heading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure a GPS fix's age on a monotonic clock instead of a wall clock (maxke24/Detour#39), and ease the position marker's heading per frame so its nose stops stepping at 1 Hz while its position glides (maxke24/Detour#38).

**Architecture:** `Fix` gains an `elapsedRealtimeMs` field beside its existing wall-clock `timeMs`; the three sites that ask "how old is this fix" move onto it, while the one site that asks "when did this happen, absolutely" — the convoy wire stamp — stays on the wall clock. Separately, `MapScreen`'s position-marker frame loop gains its own eased-bearing accumulator and a `dt`, easing toward `camTargetBearing` through the existing `smoothBearing` helper at `CAM_BEARING_TAU`.

**Tech Stack:** Kotlin, Jetpack Compose, MapLibre Android, Gradle (in devcontainer), JUnit unit tests, `tools/mocklocation` GPS replay harness driving a physically connected phone over adb.

**Spec:** `docs/superpowers/specs/2026-08-16-monotonic-fix-age-and-marker-heading-design.md`

**Branch:** `fix/map-motion-clock-and-marker-heading` (already created, spec already committed).

---

## Environment facts, verified before this plan was written

Do not re-derive these; do re-check them if a step behaves unexpectedly.

- **Devcontainer is running.** Container `dreamy_greider`, label `devcontainer.local_folder=/home/andre/Projects/Detour`. All Gradle commands go through `devcontainer-exec`. **Never** run `devcontainer up` / `docker start` — a `PreToolUse` hook refuses them; if the container has stopped, ask the user to start it.
- **Phone is connected.** `adb devices` shows serial `50043ff9`, model `CPH2449`. `adb` lives on the **host** at `/opt/android-sdk/platform-tools/adb`; the devcontainer runs `--network=host` to reach the host's adb server, but every adb command in this plan runs on the host.
- **Debug applicationId is `io.github.maxke24.detour.debug`** — not the Kotlin package `com.jellemax.detour`. Replay must target the debug variant so a mock stream never lands in real trip history.
- **`minSdk = 26`**, so `Location.getElapsedRealtimeNanos()` (API 17) needs no version guard.
- **`TripTrackingService.kt` already imports `android.os.SystemClock`** (line 26). **`MapScreen.kt` does not** — it imports `android.os.Build` at line 10 and will need `android.os.SystemClock` added.
- **`MapMotionTest.kt` passes `predict`'s timestamps positionally** (17 tests, zero named `fixTimeMs`/`nowMs` arguments), so Task 6's rename does not touch the test file.
- **Chosen replay fixture: `tools/mocklocation/routes/stop-start.txt`** — 762 points (~12.7 min at the default 1000 ms interval), 9.69 km, 337°/km of turning, longest run above 7.0 m/s is 212 s (the auto-start gate needs ≥ 8 s and ≥ 120 m), and 681 of 761 steps exceed 2.0 m/s so `camTargetBearing` is actually being updated for most of the run. `urban-limits.txt` is the fallback if standstills dominate the recording; `public-stop-start.txt` looks turnier (2742°/km) but that is bearing noise across held standstill points, not real cornering.

---

## Commit structure, and why it is six commits rather than the spec's three

The spec proposed three commits. Writing the steps out surfaced a problem with that split: renaming `MapMotion.predict`'s parameters to `fixElapsedMs`/`nowElapsedMs` while the call sites still pass `f.timeMs` produces a commit whose code actively lies about itself. Reordering fixes it — **the rename lands last, after every call site is already on the monotonic field** — at the cost of splitting the middle commit per call site.

That split is required anyway. The compose-state-hazards skill §4: *"Never change two `lastFix` consumers in one commit"* — `MapScreen.kt` holds six independent subscriptions on `TripTrackingService.lastFix`, so six independent blast radii, and a revert should only ever undo one.

| # | Task | What it touches | Behaviour change? |
|---|---|---|---|
| 1 | Task 2 | `Fix` + its one constructor | No — nothing reads the new field |
| 2 | Task 3 | camera loop's `predict` call | **Yes** |
| 3 | Task 4 | marker loop's `predict` call | **Yes** |
| 4 | Task 5 | `circleSyncLoop`'s `fixAgeMs` | **Yes** |
| 5 | Task 6 | `MapMotion.predict` signature + KDoc | No — pure rename |
| 6 | Task 7 | marker heading easing + push gate + guards | **Yes** |

---

## File structure

| File | Change | Responsibility after the change |
|---|---|---|
| `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` | Modify `:94-101` (`Fix`), `:969-976` (construction), `:1207` (`fixAgeMs`) | Publishes both clocks on every fix; measures its own staleness gate monotonically |
| `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` | Modify `:10` (import), `:1118-1125` (camera loop), `:1179-1209` (marker loop) | Both frame loops predict from the monotonic clock; the marker loop owns an eased bearing |
| `app/src/main/java/com/jellemax/detour/map/MapMotion.kt` | Modify `:30-36` (KDoc), `:38-62` (`predict`) | Parameter names state which clock they expect |
| `.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh` | Modify `:45-46` | Asserts the marker loop's new shape |
| `app/src/main/java/com/jellemax/detour/net/ConvoyLiveClient.kt` | **Unchanged** — `:431` `put("ts", fix.timeMs)` stays on the wall clock | |
| `app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt` | **Unchanged** — positional arguments survive the rename | |

**No new tests.** This is deliberate and belongs in the PR body rather than being quietly skipped:

- `MapMotion.predict` only ever subtracts one argument from the other. It is clock-agnostic by construction and was already correct — the defect is entirely in *what the callers pass it*, and the guard against a future caller passing a wall clock is the parameter rename plus KDoc, not a test that cannot distinguish the two.
- The marker loop lives inside a composable. This app has no Robolectric, no `compose-ui-test` and no `androidTest` source set (`.github/workflows/build.yml` runs only `:app:testDebugUnitTest` and `:shared:testDebugUnitTest`), so nothing in the repo can reach it. `smoothBearing` is a pure top-level function already exercised through the camera path — the testable piece is already tested.

---

## Task 1: Baseline, and the #38 repro gate

**#38 has never been observed on a device.** Its own issue says so and asks that it be looked at on hardware before anyone spends effort on it. This task decides whether Task 7 happens at all.

**Files:** none — measurement only.

- [ ] **Step 1: Confirm the harness is installed and designated**

Run on the host:

```bash
adb devices -l
adb shell pm list packages | grep -E 'mocklocation|maxke24'
adb shell appops get com.jellemax.mocklocation android:mock_location
```

Expected: serial `50043ff9` listed as `device`; `com.jellemax.mocklocation` and `io.github.maxke24.detour.debug` both present; mock_location `allow`.

If the harness is missing, build and install it (it is a **standalone Gradle build**, not a module of the root project, so its APK is under `tools/mocklocation/build/`, never `app/build/`):

```bash
devcontainer-exec bash -c 'cd tools/mocklocation && ./gradlew assembleDebug'
adb install -r tools/mocklocation/build/outputs/apk/debug/DetourMockLocation-debug.apk
adb shell appops set com.jellemax.mocklocation android:mock_location allow
```

- [ ] **Step 2: Install the current (pre-change) debug build**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
ls app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`, then `Success` from `adb install`. If the APK filename differs from `app-debug.apk`, use whatever `ls` printed.

- [ ] **Step 3: Record the pre-change baseline**

Open the app on the map screen, then:

```bash
.claude/skills/detour-gps-replay/scripts/start-replay.sh tools/mocklocation/routes/stop-start.txt 50043ff9
```

Let it run to completion (~12.7 min). Then:

```bash
.claude/skills/detour-gps-replay/scripts/stop-replay.sh 50043ff9
adb shell run-as io.github.maxke24.detour.debug cat files/trips.json > /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/trips-before.json
adb shell run-as io.github.maxke24.detour.debug cat files/traces.jsonl > /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/traces-before.jsonl
python3 .claude/skills/detour-trip-data/scripts/profile-trace.py /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/traces-before.jsonl
```

**Write down, before looking at anything else** — these are the named quantities for the A/B, and they must be counted the same way on both runs:

1. the newest trip's `distanceMeters`
2. the newest trip's `topSpeedMps`
3. total trace points (`profile-trace.py`'s per-segment count, summed)
4. how many trips the replay produced, and the `mode` tag on each

Expected shape: exactly one trip, tagged `CAR`, roughly 9.7 km, ~390 trace points (9690 m ÷ 25 m decimation).

- [ ] **Step 4: The #38 repro — camera parked, screen recorded**

Restart the replay. Once the route is moving, **pan the map once** to park the camera (follow off — the state #38 says is worst, because nothing is gliding underneath to mask the step). Then, during a stretch of continuous cornering:

```bash
adb shell screenrecord --time-limit 30 /sdcard/marker-before.mp4
adb pull /sdcard/marker-before.mp4 /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/marker-before.mp4
adb shell rm /sdcard/marker-before.mp4
```

Stop the replay with `stop-replay.sh` afterwards.

Watch the recording frame by frame. **The named question: does the dot's nose visibly jerk once per second while its position glides smoothly?**

- [ ] **Step 5: Decide, and record the decision**

- **Nose steps visibly** → #38 is real. Task 7 goes ahead, and `marker-before.mp4` is the "before" half of its evidence.
- **No visible step** → **skip Task 7 entirely.** Do not implement it. The PR then closes #39 only, and its body states plainly that #38 was checked on a `CPH2449` against `stop-start.txt` with the camera parked and the step was not observable, so the fix was not worth its accumulator. Post that observation as a comment on maxke24/Detour#38 as well.

Either way, write the decision and the reasoning into the task notes before continuing. Do not let this become an unrecorded judgement call.

**Measurement hygiene, applies to every replay in this plan** (maxke24/Detour#47): the phone's own GNSS interleaves with the mock provider and no radio setting stops it — 56 of 198 samples were affected in a measured run. If the dot flickers between the route and this desk, that is the bleed, not a bug in the code under test. Drop samples reporting `speedKmh = 0.0` while the route is moving, and report a **median** gap rather than a mean.

---

## Task 2: `Fix` carries a monotonic timestamp

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt:93-101` and `:967-976`

Pure addition. Nothing reads the new field yet, so this commit cannot change behaviour.

- [ ] **Step 1: Add the field to `Fix`**

Replace `TripTrackingService.kt:93-101` with:

```kotlin
/** Latest GPS fix, published live for the map (fog, navigation). */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Float?,
    val accuracyMeters: Float,
    /** Provider wall-clock UTC ([android.location.Location.getTime]). For anything that
     *  leaves this device: a peer reading a convoy or circle position has no way to
     *  interpret our uptime. */
    val timeMs: Long,
    /** Monotonic, on [android.os.SystemClock.elapsedRealtime]'s basis. For measuring this
     *  fix's *own age*, which is the only thing [timeMs] was ever wrong for: subtracting
     *  a provider wall clock from ours compares two clocks that only usually agree, so a
     *  device clock running persistently fast biases every answer in one direction. */
    val elapsedRealtimeMs: Long,
)
```

- [ ] **Step 2: Populate it at the one construction site**

Replace `TripTrackingService.kt:969-976` with:

```kotlin
        _lastFix.value = Fix(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = speed,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            accuracyMeters = location.accuracy,
            timeMs = location.time,
            elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
        )
```

`android.os.SystemClock` is already imported at `:26`; `elapsedRealtimeNanos` is a `Location` property needing no import.

- [ ] **Step 3: Verify it compiles and nothing regressed**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. `Fix` has exactly one construction site, so no other call site can break.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "feat(tracking): publish a monotonic timestamp alongside the fix's wall clock

Fix.timeMs is location.time — provider wall-clock UTC — and every consumer
asking 'how old is this fix' subtracts it from System.currentTimeMillis().
Two clocks that only usually agree.

Adds Fix.elapsedRealtimeMs from location.elapsedRealtimeNanos, which Android
documents for exactly this and which shares a basis with SystemClock
.elapsedRealtime(). Nothing reads it yet; the consumers move over one at a
time so each is its own revert unit.

Refs maxke24/Detour#39"
```

---

## Task 3: The camera loop predicts from the monotonic clock

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:10` (import), `:1117-1125`

One `lastFix` consumer, on its own, per compose-state-hazards §4.

- [ ] **Step 1: Add the `SystemClock` import**

`MapScreen.kt:10` is `import android.os.Build`. Add directly beneath it:

```kotlin
import android.os.SystemClock
```

- [ ] **Step 2: Switch the camera loop's `predict` inputs**

Replace `MapScreen.kt:1117-1125` with:

```kotlin
            val f = liveFix
            val camTargetNow = if (f != null) MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixTimeMs = f.elapsedRealtimeMs,
                nowMs = SystemClock.elapsedRealtime(),
                leadSeconds = CAM_POS_TAU,
            ) else camTarget
```

The parameter names still read `fixTimeMs`/`nowMs` — Task 6 renames them, once every call site is across.

- [ ] **Step 3: Verify**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): age the camera's prediction on a monotonic clock

The camera predicts the vehicle forward by speedMps * (age + lead). The age
was a provider wall clock subtracted from ours, so a device clock running
persistently fast pushed the camera ahead by a constant amount — bounded by
MAX_PREDICT_MS at 1.5 s (50 m at 120 km/h), but a steady bias rather than
noise, so it does not average out and each new fix re-establishes it.

Alone in its own commit: MapScreen holds six independent lastFix
subscriptions, so a revert should undo one.

Refs maxke24/Detour#39"
```

---

## Task 4: The marker loop predicts from the monotonic clock

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:1194-1202`

The second `lastFix` consumer, separately.

- [ ] **Step 1: Switch the marker loop's `predict` inputs**

Replace `MapScreen.kt:1194-1202` with:

```kotlin
            val f = liveFix ?: continue
            val here = MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixTimeMs = f.elapsedRealtimeMs,
                nowMs = SystemClock.elapsedRealtime(),
                leadSeconds = 0.0,
            )
```

- [ ] **Step 2: Verify**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): age the position marker's prediction on a monotonic clock

Same wall-clock-against-wall-clock age as the camera loop, on the marker's
own predict call. Separate commit from the camera's for the same reason:
one lastFix consumer per revert.

Refs maxke24/Detour#39"
```

---

## Task 5: The circle-sync staleness gate stops trusting the wall clock

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt:1203-1212`

Same defect class, third consumer, different file. `CircleFixes.postFix`'s stamp on `:1211` is a **wire** value read by other circle members and stays on the wall clock.

- [ ] **Step 1: Age the gate monotonically**

Replace `TripTrackingService.kt:1203-1207` with:

```kotlin
            // In SLEEP mode the fused request is PRIORITY_PASSIVE, so a parked
            // phone can go a long time between fixes. That is fine for a
            // position nobody has moved, but a fix old enough that the phone
            // could be anywhere by now must not drive a geofence decision.
            // Monotonic, not wall clock: this asks how old the fix is, and a
            // device clock that drifts or is corrected mid-drive would answer
            // it wrong in whichever direction the correction went. The stamp
            // posted below is the opposite question and stays on location.time.
            val fixAgeMs = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs
```

Leave `:1208-1212` alone — `CircleFixes.postFix(circle.id, fix.lat, fix.lon, fix.accuracyMeters.toDouble(), fix.timeMs)` is unchanged.

- [ ] **Step 2: Verify**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
git commit -m "fix(tracking): age the circle-sync trust gate on a monotonic clock

circleSyncLoop refuses to drive a geofence decision from a fix older than
CIRCLE_FIX_TRUST_MS, and measured that age by subtracting a provider wall
clock from System.currentTimeMillis(). Same two-clock comparison as the map's
prediction, with a worse failure: a skew large enough to push the age past
the threshold silently stops arrival notifications firing.

The stamp posted to the server is left on location.time — that one is read by
other members' devices, which cannot interpret this phone's uptime.

Refs maxke24/Detour#39"
```

---

## Task 6: Name the clock in `MapMotion.predict`'s signature

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/map/MapMotion.kt:30-62`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (the two named arguments)

Pure rename, landing last so no intermediate commit claims a monotonic parameter while being handed a wall clock. This is the guard against a future caller reintroducing the bug — there is no test that could tell the two clocks apart.

- [ ] **Step 1: Rename the parameters and correct the KDoc**

Replace `MapMotion.kt:30-62` with:

```kotlin
    /**
     * Longest gap worth extrapolating across. Bounds a tunnel and a GPS dropout: past this
     * the last bearing and speed are no longer evidence of where the vehicle is, and
     * extrapolating further invents distance. 1.5 s is 50 m at 120 km/h.
     *
     * This used to also bound a skewed device clock, back when the age was a provider wall
     * clock subtracted from ours. It no longer has to — [predict] takes a monotonic pair —
     * but the tunnel and dropout cases are reason enough on their own.
     */
    const val MAX_PREDICT_MS = 1500L

    /**
     * [at] carried forward along [bearingDeg] by however far [speedMps] covers in the fix's
     * age plus [leadSeconds].
     *
     * Both timestamps must come from the **monotonic** clock —
     * [android.os.SystemClock.elapsedRealtime] and the fix's own
     * `Location.elapsedRealtimeNanos`, which is what
     * [com.jellemax.detour.tracking.Fix.elapsedRealtimeMs] carries. Passing a wall clock
     * compiles and is wrong: a device clock running persistently fast then biases every
     * prediction forward by a constant, which does not average out.
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
        fixElapsedMs: Long,
        nowElapsedMs: Long,
        leadSeconds: Double,
    ): LatLon {
        if (bearingDeg == null || speedMps < MIN_PREDICT_MPS) return at
        val ageMs = (nowElapsedMs - fixElapsedMs).coerceIn(0L, MAX_PREDICT_MS)
        val seconds = ageMs / 1000.0 + leadSeconds
        if (seconds <= 0.0) return at
        return RoadRoulette.offset(at, speedMps * seconds, bearingDeg * PI / 180.0)
    }
```

- [ ] **Step 2: Update the two named arguments in `MapScreen.kt`**

In the camera loop, `fixTimeMs = f.elapsedRealtimeMs,` becomes `fixElapsedMs = f.elapsedRealtimeMs,` and `nowMs = SystemClock.elapsedRealtime(),` becomes `nowElapsedMs = SystemClock.elapsedRealtime(),`. Make the identical two edits in the marker loop. Four lines total, two per loop.

- [ ] **Step 3: Verify — the tests must still pass untouched**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*MapMotionTest*'
```

Expected: PASS, 17 tests, `MapMotionTest.kt` unmodified. They pass the timestamps positionally, so a rename cannot reach them. If this fails to compile, a named argument was missed — grep for it:

```bash
grep -rn 'fixTimeMs\|nowMs' app/src/
```

Expected: no output.

- [ ] **Step 4: Full verification**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. The release assemble matters — R8 catches what debug does not.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/map/MapMotion.kt app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(map): name the clock predict() expects, and correct MAX_PREDICT_MS's KDoc

predict() only subtracts one timestamp from the other, so it is clock-agnostic
by construction and no test can tell a wall clock from a monotonic one being
passed in. The signature is the only place that guard can live: fixTimeMs and
nowMs become fixElapsedMs and nowElapsedMs, and the KDoc says outright that a
wall clock compiles and is wrong.

MAX_PREDICT_MS keeps its value and its clamp — it still bounds a tunnel and a
GPS dropout — but loses the skewed-clock justification, which no longer
applies.

The 17 tests in MapMotionTest pass these positionally and are untouched.

Closes maxke24/Detour#39"
```

---

## Task 7: The marker's heading glides too

**Run this task only if Task 1 Step 5 confirmed the step is visible on the device.** If it was not observable, skip to Task 8.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt` (add `bearingDelta`)
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:1179-1209`
- Modify: `.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh:45-46`

- [ ] **Step 1: Add a named wrap-safe bearing difference**

The marker's push gate needs to ask "has the heading moved more than an epsilon", and the 0/360 wrap makes that three lines of arithmetic that must not be written inline inside a boolean. `MapMotion.shouldPush` already carries a copy; rather than write a third, give it a name once.

Append to `app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt`, directly beneath `smoothBearing` (which ends at line 12):

```kotlin
/** Shortest angular distance between two bearings, 0..180 — so a 359 to 1 turn
 *  reads as 2 degrees rather than 358. */
internal fun bearingDelta(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return abs(d)
}
```

`MapCameraTuning.kt` currently imports nothing, so add `import kotlin.math.abs` above the `package` line's following blank — i.e. the file becomes:

```kotlin
package com.jellemax.detour.ui

import kotlin.math.abs
```

Leave `MapMotion.shouldPush`'s inline copy alone. Converging the two is a worthwhile tidy-up and it is not this PR's job.

- [ ] **Step 2: Rewrite the marker loop**

Replace `MapScreen.kt:1179-1209` with:

```kotlin
    // The dot, interpolated per frame. It used to be re-placed only when a fix arrived,
    // about once a second, at the raw fix position — so it stepped forward and the camera
    // slid after it. Worst when the camera is parked (after a pan, with follow off, or
    // with a spin result up), because then nothing is gliding underneath to mask it, which
    // is why this loop is deliberately independent of cameraActive.
    //
    // Its heading is eased here too, on its own accumulator rather than the camera loop's.
    // Sharing the camera's would look tempting — the two can then never diverge — but the
    // camera loop returns early when !cameraActive, so a shared bearing would freeze in
    // exactly the parked case above. Both ease the same target at the same tau on the same
    // frame clock, so while the camera is active they track each other and the difference
    // the rider actually sees (icon bearing minus map bearing, under
    // ICON_ROTATION_ALIGNMENT_MAP) stays near zero.
    //
    // setPosition writes one point into SRC_POSITION. render() rewrites eight sources
    // including the route line, and doing *that* per frame is what makes a head unit
    // crawl — see MapOverlays.setPosition's own note.
    LaunchedEffect(mapOverlays, haveFix) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var pushedBearing: Float? = null
        var markerBearing: Float? = null
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            // Same clamp as the camera loop: a dropped frame or a stalled render must not
            // let one frame close the whole gap.
            val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastNs = ns
            val f = liveFix ?: continue
            val here = MapMotion.predict(
                at = LatLon(f.lat, f.lon),
                bearingDeg = f.bearingDeg,
                speedMps = f.speedMps,
                fixElapsedMs = f.elapsedRealtimeMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                leadSeconds = 0.0,
            )
            camTargetBearing?.let { target ->
                markerBearing = smoothBearing(
                    markerBearing, target, (1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat())
            }
            // The gate covers the bearing as well as the position, or a vehicle stopped
            // mid-rotation would ease its nose and never push it. CAM_BEARING_EPS_DEG keeps
            // the standstill optimisation the position half already had: once the marker has
            // settled, this loop goes quiet again.
            val bearing = markerBearing
            val turned = bearing != null &&
                (pushedBearing == null || bearingDelta(pushedBearing, bearing) > CAM_BEARING_EPS_DEG)
            if (here.lat != lastLat || here.lon != lastLon || turned) {
                overlays.setPosition(here, bearing?.toDouble())
                lastLat = here.lat
                lastLon = here.lon
                if (turned) pushedBearing = bearing
            }
        }
    }
```

Three things worth understanding rather than pattern-matching:

- `smoothBearing(current, target, alpha)` returns `target` when `current` is null, so `markerBearing` needs no seed value and the first frame does not swing across the compass.
- `bearing` is a local copy of `markerBearing` so Kotlin can smart-cast it — a captured `var` cannot be smart-cast, and `!!` in a frame loop is a worse answer than one `val`.
- `pushedBearing` advances only when the gate actually fired on the bearing. Updating it on every push would let a position-driven push silently absorb an accumulating rotation, which is the same "compare against the last pushed value" mistake `MapMotion.shouldPush` was written to get away from.

`exp`, `smoothBearing`, `CAM_BEARING_TAU` and `CAM_BEARING_EPS_DEG` are already imported or in-package in `MapScreen.kt`; `bearingDelta` arrives from Step 1 in the same package. Confirm with `devcontainer-exec ./gradlew :app:assembleDebug` rather than by eye.

- [ ] **Step 3: Update the precondition guard**

`check-preconditions.sh:45-46` currently reads:

```sh
check 'MapScreen has 6 withFrameNanos lines (import + speed/camera lastNs pairs + marker loop) — §6' \
    6 "$(count 'withFrameNanos' "$M")"
```

Replace with:

```sh
check 'MapScreen has 8 withFrameNanos lines (import + speed/camera/marker lastNs pairs + marker read) — §6' \
    8 "$(count 'withFrameNanos' "$M")"
```

The marker loop now seeds a `lastNs` and reads one inside the loop, so it contributes 2 lines where it contributed 1, and the old message's claim that it "needs no `dt`" is no longer true.

- [ ] **Step 4: Run the guard**

```bash
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

Expected: `5 checks, 0 failed`, with the `withFrameNanos` line reading `PASS` at 8.

- [ ] **Step 5: Build and test**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt \
        .claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
git commit -m "fix(map): ease the position marker's heading per frame, not per fix

#21 made the dot's position interpolate per frame. Its heading did not — it
was written from camTargetBearing, which is assigned only in the per-fix
effect, so it stepped at ~1 Hz. The icon draws with ICON_ROTATION_ALIGNMENT_MAP,
so what a rider sees is icon bearing minus map bearing: one stepping against
one gliding. Smoother position arguably made it more noticeable, not less.

The marker loop now owns an eased bearing and a dt, at CAM_BEARING_TAU through
the existing smoothBearing. Its own accumulator rather than the camera loop's:
the camera loop returns early when !cameraActive, and the parked camera is
precisely when this is worst.

The push gate grows a bearing term against CAM_BEARING_EPS_DEG — position-only,
a vehicle stopped mid-rotation would have eased its nose and never pushed it —
so the standstill optimisation survives. The 0/360 wrap arithmetic it needs
becomes a named bearingDelta() in MapCameraTuning rather than a third inline
copy; MapMotion.shouldPush's copy is left alone, converging them is not this
change's job.

check-preconditions.sh moves 6 -> 8 withFrameNanos lines and drops its claim
that the marker loop needs no dt.

Closes maxke24/Detour#38"
```

---

## Task 8: The A/B replay

**Files:** none — measurement only.

- [ ] **Step 1: Install the post-change build**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Replay the identical route file**

```bash
.claude/skills/detour-gps-replay/scripts/start-replay.sh tools/mocklocation/routes/stop-start.txt 50043ff9
```

Same fixture, same default interval, same variant, same starting state as Task 1. Let it run to completion, then:

```bash
.claude/skills/detour-gps-replay/scripts/stop-replay.sh 50043ff9
adb shell run-as io.github.maxke24.detour.debug cat files/trips.json > /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/trips-after.json
adb shell run-as io.github.maxke24.detour.debug cat files/traces.jsonl > /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/traces-after.jsonl
python3 .claude/skills/detour-trip-data/scripts/profile-trace.py /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/traces-after.jsonl
```

- [ ] **Step 3: Compare the four named quantities**

Same four as Task 1 Step 3: newest trip `distanceMeters`, newest trip `topSpeedMps`, total trace points, and trip count with mode tags.

Expected: all four unchanged within a percent or two. None of these changes is supposed to touch the fix pipeline, so a real drift means something did — investigate before proceeding, do not explain it away.

Record the result as two concrete lines, e.g. `Before: 9.68 km / 34.2 m/s / 389 points / 1 trip CAR. After: 9.69 km / 34.2 m/s / 390 points / 1 trip CAR, same route file.` **"Behaviour looked unchanged" is not a result** and will not survive review.

- [ ] **Step 4: Re-record the marker, if Task 7 ran**

Same conditions as Task 1 Step 4 — camera parked, cornering stretch, 30 s:

```bash
adb shell screenrecord --time-limit 30 /sdcard/marker-after.mp4
adb pull /sdcard/marker-after.mp4 /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/marker-after.mp4
adb shell rm /sdcard/marker-after.mp4
```

Compare against `marker-before.mp4`. Named question, same as before: does the nose still jerk once per second?

---

## Task 9: Gates, then the pull request

**Files:** none until the PR body.

- [ ] **Step 1: Tier 0 greps against the merge base**

```bash
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh main
```

It compares against `main` rather than reading the working tree, because every check is a delta. Read its output — in particular, it prints every `LaunchedEffect`/`DisposableEffect` declaration line the range touched, and this branch changed one effect's body without changing its key list. Confirm that is what it shows.

- [ ] **Step 2: The compose-state-hazards preconditions**

```bash
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

Expected: `5 checks, 0 failed`.

- [ ] **Step 3: Full build and test**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the pull-request workflows locally**

**REQUIRED SUB-SKILL: `c7-github-workflow:local-ci-act`.** A push to a branch with an open non-draft PR starts billed runs on GitHub, and there is no `.claude/c7/github-workflow.yml` in this repo yet, so which workflows those are and whether they bill is currently unrecorded. The skill discovers it and writes the manifest on first use. Run it before Step 6, not after a red run.

- [ ] **Step 5: Write the PR body**

**REQUIRED SUB-SKILL: `detour-pr-writing`.** It carries the shape a description takes in this repo — lead with the measured before/after, state what changed, keep the known limits, point at the follow-up issues.

The limits that must survive into the body, because they are the things a reviewer would otherwise have to discover:

1. **Replay cannot exhibit #39's actual defect.** `MockService.kt:116-117` stamps `time` and `elapsedRealtimeNanos` from the same two clocks the app reads, in agreement, on every fix. The A/B proves no regression; the skew fix rests on Android's documented contract for `elapsedRealtimeNanos` and on the rename that stops a future caller undoing it.
2. **Neither fix gains a test, and why.** `predict` is clock-agnostic by construction, so no unit test can distinguish the clocks; the marker loop is inside a composable and this repo has no Robolectric, no `compose-ui-test` and no `androidTest` source set.
3. **Whatever Task 1 Step 5 decided about #38**, including the negative case if that is what happened.
4. **`CarMapRenderer` is untouched** and still ages its own prediction — it has no prediction at all, which is maxke24/Detour#37, deliberately left for a change that can be checked on a head unit.

- [ ] **Step 6: Open the pull request**

Against `maxke24/Detour`'s default branch, from `Yimura/Detour:fix/map-motion-clock-and-marker-heading`. **Confirm with the user before pushing** — this is a fork-to-upstream PR and starts CI on someone else's repository.

Write the Step 5 body to `/tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/pr-body.md`, then:

```bash
git push -u origin fix/map-motion-clock-and-marker-heading
gh pr create --repo maxke24/Detour --draft \
  --title 'Age a GPS fix on a monotonic clock, and let the position marker glide its heading' \
  --body-file /tmp/claude-1000/-home-andre-Projects-Detour/70b3bc8b-b2d1-4d6e-acd9-34413ce6f4ff/scratchpad/pr-body.md
```

If Task 1 Step 5 dropped #38, the title is `Age a GPS fix on a monotonic clock` instead.

Open it as a **draft** first, then `gh pr ready` only once the local `act` run in Step 4 came back clean — marking a draft ready is itself a billed event.
