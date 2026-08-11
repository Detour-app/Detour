# Stage 0 — Verification Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make it possible to prove that a later refactor of `MapScreen.kt` did not change behaviour, and fix three verified defects while the code is still original.

**Architecture:** Three independent workstreams. (1) Turn on the two verification gates the repo already has but does not use — Kotlin unit tests in CI, and the GPS replay harness in `tools/mocklocation/`. (2) Record the current behaviour of four canonical drives, which is only capturable before any behaviour-touching commit. (3) Fix three isolated defects, each in its own commit, so a later bisect never has to separate a refactor from a fix.

**Tech Stack:** Kotlin, Jetpack Compose, MapLibre, Kotlin Multiplatform (`:shared`), Swift/SwiftUI (`iosApp/`), GitHub Actions, Gradle.

**Spec:** [`../specs/stage-0-verification-baseline.md`](../specs/stage-0-verification-baseline.md) — preconditions verified passing at commit `fa6a638`.

## Global Constraints

- **All Gradle commands run inside the devcontainer.** The host JDK is 26 and has no Android SDK; a bare `./gradlew` on the host cannot build this project. Container name `recursing_volhard`, workdir `/workspaces/Detour`. Always pass `-u 1000:1000` — a username pins the uid but not the gid, and root-owned build output breaks the interactive shell afterwards.
  ```bash
  docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew <task>
  ```
  If the container is not running, ask the user to start it. Never install Android tooling on the host.
- **Commit messages:** Conventional Commits (`fix:`, `ci:`, `docs:`, `test:`). **No `Co-Authored-By` trailer. No `Claude-Session` trailer. No trailers at all.**
- **One work item, one commit.** Never combine a behaviour fix with a refactor, and never combine two of the tasks below into one commit.
- **Branch:** `refactor/mapscreen-split`. Already checked out.
- **No changes to `MapScreen.kt`'s structure.** Tasks 4 and 5 make surgical edits inside existing effects and inside the existing `Scaffold` call. Moving, renaming or extracting anything is stage 1's job and is out of scope here.
- **Tasks 2 and 3 require a physical Android device or an emulator with Play Services.** They cannot be completed headlessly. See the note on each.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `.github/workflows/build.yml` | Modify | Add a unit-test gate before the release build |
| `tools/mocklocation/routes/*.txt` | Create | Four canonical replay routes, one `lon lat` pair per line |
| `tools/mocklocation/routes/README.md` | Create | How to run a route and what each one exercises |
| `tools/mocklocation/gpx-to-route.sh` | Create | Convert a GPX trace to the harness's route format |
| `tools/mocklocation/baseline/` | Create | Recorded pre-refactor behaviour, per route |
| `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` | Modify | Move two Overpass fetches off the fix collector; surface `error` |
| `iosApp/Detour/NavScreen.swift` | Modify | Correct the maneuver-arrow table |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt` | Modify | Widen the sign-code KDoc that the wrong table was derived from |
| `CONTRIBUTING.md` | Modify | State the operational test for what earns the shared core |

---

## Task 1: Run Kotlin unit tests in CI

**Files:**
- Modify: `.github/workflows/build.yml:117-124`

**Interfaces:**
- Consumes: nothing.
- Produces: a CI gate that every later task and every later stage relies on. No code interface.

**Why:** `build.yml` only ever runs `:app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease`. The six test files under `app/src/test/` have never executed in CI. `ios.yml:64-68` does run `:shared` tests, but it is path-gated on `shared/**` and `iosApp/**`, so a change under `app/` gets no test coverage at all.

- [ ] **Step 1: Confirm the gap is real**

Run:
```bash
grep -n 'gradlew' .github/workflows/build.yml
```
Expected: exactly one hit, at line 123, running only `assembleRelease`/`bundleRelease` tasks. If there is already a test task, this task is done — stop and report.

- [ ] **Step 2: Add the test step**

In `.github/workflows/build.yml`, insert this immediately **before** the `- name: Build release artifacts` step (currently line 117):

```yaml
      # app/src/test and shared/ both hold unit tests that had never run in CI:
      # this job only ever assembled, and ios.yml — which does run the shared
      # tests — is path-gated on shared/** and iosApp/**, so a change under app/
      # was tested by nothing at all. Before the assemble step, so a red test
      # fails the build in seconds rather than after a full release build.
      - name: Run unit tests
        run: ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```

- [ ] **Step 3: Run the tests locally to see whether they currently pass**

Run:
```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`.

**If any test fails:** that failure is pre-existing — these tests have never run in CI, so nothing in this task caused it. Do **not** fix it in this commit. Record the failing test names, finish this task, then fix or `@Ignore` them in a separate follow-up commit whose message states that the failure predates the CI gate.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: run Kotlin unit tests on every build

app/src/test has six test files that have never executed in CI — the build
job only assembled, and ios.yml (which does run the shared tests) is path-gated
on shared/** and iosApp/**. Every later refactor step leans on these tests, so
they need to be a gate rather than decoration."
```

---

## Task 2: Check in the canonical replay routes

**Files:**
- Create: `tools/mocklocation/gpx-to-route.sh`
- Create: `tools/mocklocation/routes/trajectcontrole.txt`
- Create: `tools/mocklocation/routes/urban-limits.txt`
- Create: `tools/mocklocation/routes/off-route.txt`
- Create: `tools/mocklocation/routes/stop-start.txt`
- Create: `tools/mocklocation/routes/README.md`

**Interfaces:**
- Consumes: `tools/mocklocation/src/main/java/com/jellemax/mocklocation/MockService.kt`, which reads a route as **one `lon lat` pair per line, one point per second**.
- Produces: four route files and a documented invocation, consumed by Task 3 and by every replay in stages 2–4.

> **Human-in-the-loop.** Choosing four real roads that actually contain a trajectcontrole, a sequence of posted limit changes, and so on, is judgement a subagent cannot exercise from the repo alone. A subagent can write the script, the README and the directory structure; the route coordinates must come from the user (recorded drives, or traced in a GPX editor). If routes are not available, complete steps 1–3 and 6, and leave the four `.txt` files for the user with the README stating exactly what each must contain.

**Note on file extension:** the spec names these `.gpx`. `MockService` does not parse GPX — it reads plain `lon lat` lines. The plan uses `.txt` for the route files and supplies a GPX converter, because that is what the harness actually consumes.

- [ ] **Step 1: Read the harness contract**

Run:
```bash
sed -n '20,40p' tools/mocklocation/src/main/java/com/jellemax/mocklocation/MockService.kt
sed -n '155,180p' docs/PLAY_LOCATION_DECLARATION.md
```
Expected: confirms the `lon lat` per-line format, the `appops set … android:mock_location allow` designation step, and the `am start-foreground-service` invocation.

- [ ] **Step 2: Write the GPX converter**

Create `tools/mocklocation/gpx-to-route.sh`:

```bash
#!/usr/bin/env bash
# Convert a GPX track into MockService's route format: one "lon lat" pair per
# line, one point per second of replay.
#
# GPX trackpoints are not evenly spaced in time, and MockService derives speed
# from the gap between consecutive points at a fixed interval — so a raw export
# replays at whatever speed the spacing implies. Check the result: at the
# default 1000 ms interval, consecutive points ~14 m apart replay as ~50 km/h.
#
#   ./gpx-to-route.sh drive.gpx > routes/urban-limits.txt
set -euo pipefail

test $# -eq 1 || { echo "usage: $0 <file.gpx>" >&2; exit 1; }
test -f "$1" || { echo "no such file: $1" >&2; exit 1; }

grep -o 'lat="[^"]*" lon="[^"]*"' "$1" \
  | sed -E 's/lat="([^"]*)" lon="([^"]*)"/\2 \1/'
```

Make it executable:
```bash
chmod +x tools/mocklocation/gpx-to-route.sh
```

- [ ] **Step 3: Create the routes directory and its README**

Create `tools/mocklocation/routes/README.md`:

````markdown
# Canonical replay routes

Four drives that between them exercise every GPS-driven behaviour in
`MapScreen.kt`. They exist so a refactor can be A/B'd against the recorded
baseline in `../baseline/` instead of against someone's memory of a drive.

Format: one `lon lat` pair per line, one point per second of replay. Produce
one from a GPX trace with `../gpx-to-route.sh`.

| File | Must contain | Exercises |
|---|---|---|
| `trajectcontrole.txt` | a real average-speed section, entered at one end and driven to the other | section entry gating, running average, exit detection, camera chime |
| `urban-limits.txt` | several posted-limit changes, at least one cross street and one frontage road | ambient limit snapping, the 3-miss clear hysteresis, prefetch refresh at the edge of the held set |
| `off-route.txt` | a deliberate deviation of more than 60 m from a routed line, then a rejoin | reroute trigger, the 15 s cooldown, driven-fraction fade |
| `stop-start.txt` | traffic-light stops, a map pan mid-drive, then driving off again | camera park and resume, speed-HUD easing, bearing hold below 2 m/s |

## Running one

Once, to designate the mock app:

```
cd tools/mocklocation && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/DetourMockLocation-debug.apk
adb shell appops set com.jellemax.mocklocation android:mock_location allow
```

Then per route — scoped storage blocks `/sdcard`, so the file goes into the
app's own data directory:

```
adb shell "run-as com.jellemax.mocklocation sh -c 'cat > files/route.txt'" \
    < routes/urban-limits.txt
adb shell am start-foreground-service -n com.jellemax.mocklocation/.MockService \
    --es route /data/data/com.jellemax.mocklocation/files/route.txt --ei intervalMs 1000
```

Stop it with:

```
adb shell am stopservice -n com.jellemax.mocklocation/.MockService
```

Turn off any real location source first. `MockService` registers mocks on the
gps, fused, network and passive providers precisely because the fused provider
blends whatever is enabled — leave a real one live and the device appears to
teleport between the route and its actual position.
````

- [ ] **Step 4: Source the four route files**

Each file must satisfy its row in the table above. Obtain them from recorded drives (export GPX, convert with the script from step 2) or trace them in a GPX editor.

Sanity-check each one:
```bash
for f in tools/mocklocation/routes/*.txt; do
  echo "$f: $(wc -l < "$f") points"
  head -1 "$f"
done
```
Expected: each has at least 120 points (two minutes of replay), and each first line is two decimal numbers, longitude first, in the range you expect for the region.

- [ ] **Step 5: Verify one route actually drives the app**

Install the mock app and run `urban-limits.txt` per the README while Detour is open on the map. Expected: the position marker follows the route, the speed HUD reads a plausible speed, and the map camera follows.

If the marker does not move, the `appops` designation did not take — re-run it and confirm with `adb shell appops get com.jellemax.mocklocation android:mock_location`.

- [ ] **Step 6: Commit**

```bash
git add tools/mocklocation/gpx-to-route.sh tools/mocklocation/routes/
git commit -m "test(tools): check in canonical replay routes for GPS regression testing

Four drives covering every GPS-driven behaviour on the map screen, plus a GPX
converter and the run instructions. MockService already existed; nothing was
using it for regression capture, which left the whole fix-driven half of
MapScreen.kt verifiable only by actually driving."
```

---

## Task 3: Record the pre-refactor behavioural baseline

**Files:**
- Create: `tools/mocklocation/baseline/README.md`
- Create: `tools/mocklocation/baseline/<route>-<sha>.log` (four files)
- Create: `tools/mocklocation/baseline/<route>-<sha>.mp4` (four files)

**Interfaces:**
- Consumes: the four routes from Task 2.
- Produces: the reference recording that every A/B comparison in Tasks 4 and in stages 2–4 is measured against.

> **Sequential — must follow Task 2, and must precede Task 4.** This is the one item in the whole chain that cannot be redone later. Once any behaviour-touching commit lands, the "before" recording is gone. A subagent cannot do this; it needs a device.

- [ ] **Step 1: Record the SHA being captured**

Run:
```bash
git rev-parse --short HEAD
```
Note the value — every filename in this task carries it.

- [ ] **Step 2: Build and install the current app**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`.

- [ ] **Step 3: Record each route**

For each of the four routes, with Detour open on the map screen:

```bash
SHA=$(git rev-parse --short HEAD)
ROUTE=urban-limits   # repeat for trajectcontrole, off-route, stop-start

adb logcat -c
adb shell screenrecord --time-limit 180 /sdcard/$ROUTE.mp4 &
adb shell "run-as com.jellemax.mocklocation sh -c 'cat > files/route.txt'" \
    < tools/mocklocation/routes/$ROUTE.txt
adb shell am start-foreground-service -n com.jellemax.mocklocation/.MockService \
    --es route /data/data/com.jellemax.mocklocation/files/route.txt --ei intervalMs 1000

# wait for the route to finish, then:
adb shell am stopservice -n com.jellemax.mocklocation/.MockService
adb logcat -d > tools/mocklocation/baseline/$ROUTE-$SHA.log
adb pull /sdcard/$ROUTE.mp4 tools/mocklocation/baseline/$ROUTE-$SHA.mp4
```

- [ ] **Step 4: Note the quantities that Task 4 will compare**

Task 4 changes the fix stream feeding the speed-limit sign. Watch `urban-limits` specifically and write down, in the README from step 5:

- how many seconds elapse between leaving a posted-limit road and the sign clearing;
- whether the sign ever shows a value from a cross street or frontage road;
- whether the camera or speed HUD ever visibly freezes for more than a second.

These three observations are the acceptance criteria for Task 4. Without them written down, "it looks the same" is the only available comparison, and it is not good enough.

- [ ] **Step 5: Write the baseline README**

Create `tools/mocklocation/baseline/README.md` recording: the SHA, the device model and Android version, the date, and the three observed quantities from step 4 for `urban-limits`, plus anything notable seen on the other three routes.

State plainly at the top that these recordings are the pre-refactor reference and must not be regenerated after a behaviour-touching commit.

- [ ] **Step 6: Commit**

```bash
git add tools/mocklocation/baseline/
git commit -m "test(tools): record pre-refactor behavioural baseline

Four replay routes captured at <SHA> on <device>, with the observed
speed-limit-sign timings that the Overpass fix is measured against. This
recording is only capturable before any behaviour-touching commit."
```

Replace `<SHA>` and `<device>` with the real values.

---

## Task 4: Keep GPS fixes flowing while Overpass prefetch is in flight

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:528-533` (add a job holder)
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:1018-1056` (ambient speed limit)
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:1058-1083` (speed cameras)

**Interfaces:**
- Consumes: the baseline observations from Task 3, step 4.
- Produces: no new API. The two effects keep their existing behaviour except that the fix collector no longer suspends on a network call.

> **Sequential — must follow Task 3.** This is the highest-risk item in the stage relative to its size.

**Why:** Both effects `await` an Overpass round-trip **inside** a `TripTrackingService.lastFix` collector. `lastFix` is a `StateFlow`, and a `StateFlow` conflates while its collector is suspended — so every fix that lands during a slow request is dropped, and the camera target, the speed HUD and the turn card all freeze behind the network call. The car surface hit this twice and fixed it twice, with the reasoning written out at `car/NavScreen.kt:365-377` and `car/SpinScreen.kt:254-264`. Port that pattern; do not invent a third.

- [ ] **Step 1: Read the pattern being ported**

Run:
```bash
sed -n '365,400p' app/src/main/java/com/jellemax/detour/car/NavScreen.kt
```
Expected: shows the `cameraFetchJob?.isActive != true` guard and the fetch running in its own `lifecycleScope.launch` rather than inline.

- [ ] **Step 2: Add a job holder for the speed-limit fetch**

`MapScreen.kt:528-533` currently reads:

```kotlin
    var speedLimitWays by remember {
        mutableStateOf<List<RoadRoulette.SpeedLimitWay>>(emptyList())
    }
    var speedLimitWaysCenter by remember { mutableStateOf<LatLon?>(null) }
    var speedLimitFetchMs by remember { mutableLongStateOf(0L) }
    var speedLimitMisses by remember { mutableIntStateOf(0) }
```

Add one line after `speedLimitFetchMs`:

```kotlin
    var speedLimitWays by remember {
        mutableStateOf<List<RoadRoulette.SpeedLimitWay>>(emptyList())
    }
    var speedLimitWaysCenter by remember { mutableStateOf<LatLon?>(null) }
    var speedLimitFetchMs by remember { mutableLongStateOf(0L) }
    // Held here rather than inside the effect: the effect is keyed on
    // `navigating` and restarts, and a fetch in flight must not be forgotten
    // (or re-issued) just because navigation started.
    var speedLimitFetchJob by remember { mutableStateOf<Job?>(null) }
    var speedLimitMisses by remember { mutableIntStateOf(0) }
```

`Job` is already imported at `MapScreen.kt:190`. No new import.

- [ ] **Step 3: Move the speed-limit fetch off the collector**

In the effect at `MapScreen.kt:1024`, replace this block:

```kotlin
            if (fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - 500.0 &&
                now - speedLimitFetchMs > 10_000
            ) {
                speedLimitFetchMs = now
                val ways = withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                if (ways.isNotEmpty()) {
                    speedLimitWays = ways
                    speedLimitWaysCenter = pos
                }
            }
```

with:

```kotlin
            if (fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - 500.0 &&
                now - speedLimitFetchMs > 10_000 &&
                speedLimitFetchJob?.isActive != true
            ) {
                speedLimitFetchMs = now
                // In its own coroutine, not inline. lastFix is a StateFlow and
                // its collector is sequential, so awaiting Overpass *here*
                // suspended the collector itself — and with it the camera
                // target, the HUD and the turn card — for however long the
                // mirror took, conflating away every fix that landed meanwhile.
                // Same fix the car surface already carries; see
                // car/NavScreen.kt's checkCameras().
                speedLimitFetchJob = scope.launch {
                    val ways = withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                    if (ways.isNotEmpty()) {
                        speedLimitWays = ways
                        speedLimitWaysCenter = pos
                    }
                }
            }
```

`scope` is the `rememberCoroutineScope()` at `MapScreen.kt:435`. Using it rather than the effect's own scope is deliberate: a fetch survives the effect being re-keyed, exactly as the car's `lifecycleScope` does.

- [ ] **Step 4: Move the speed-camera fetch off the collector**

In the effect at `MapScreen.kt:1062`, replace:

```kotlin
    LaunchedEffect(Unit) {
        var center: LatLon? = null
        var lastFetchMs = 0L
        TripTrackingService.lastFix.collect { fix ->
```

with:

```kotlin
    LaunchedEffect(Unit) {
        var center: LatLon? = null
        var lastFetchMs = 0L
        // Coroutine-local is safe here where it was not for the speed limit
        // above: this effect is keyed on Unit, so it never restarts.
        var fetchJob: Job? = null
        TripTrackingService.lastFix.collect { fix ->
```

and replace:

```kotlin
            if (fromCenter > SpeedCameras.PREFETCH_RADIUS_M - 1000.0 &&
                now - lastFetchMs > 15_000
            ) {
                lastFetchMs = now
                val result = withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                if (result != null) {
                    speedCameras = result.cameras
                    speedSections = result.sections
                    center = pos
                }
            }
```

with:

```kotlin
            if (fromCenter > SpeedCameras.PREFETCH_RADIUS_M - 1000.0 &&
                now - lastFetchMs > 15_000 &&
                fetchJob?.isActive != true
            ) {
                lastFetchMs = now
                // Off the collector, same reasoning as the speed-limit fetch
                // above. `center` is written from inside the launch and read
                // from the collector, but both run on the main dispatcher, so
                // there is no race to guard.
                fetchJob = scope.launch {
                    val result = withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                    if (result != null) {
                        speedCameras = result.cameras
                        speedSections = result.sections
                        center = pos
                    }
                }
            }
```

- [ ] **Step 5: Compile**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Replay route (ii) and compare against the baseline**

Install the new build and replay `urban-limits.txt` exactly as Task 3 did. Compare against the three quantities written down in `tools/mocklocation/baseline/README.md`:

1. **Seconds from leaving a posted-limit road to the sign clearing.** This is the one that matters. The 3-miss hysteresis at `MapScreen.kt:1050` was only ever tuned against a fix stream that *had* these drops. With the drops gone, three misses now arrive sooner, so the sign may clear noticeably faster.
2. Whether the sign shows a cross-street or frontage-road value it did not show before.
3. Whether the camera or HUD still freezes during a fetch. It should no longer.

**If (1) changed materially:** retune the `3` at `MapScreen.kt:1050` **in this same commit**, and say in the commit message what it was changed from, to, and why. Do not leave it to be found in the field. If you cannot get the timing back to something defensible, revert the task and report.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): keep GPS fixes flowing while Overpass prefetch is in flight

Both the ambient speed-limit and the speed-camera prefetch awaited an Overpass
round-trip inside the lastFix collector. lastFix is a StateFlow, so every fix
landing during a slow request was conflated away — freezing the camera, the
speed HUD and the turn card behind a network call. The car surface hit this
twice and fixed it twice (car/NavScreen.kt:365-377, car/SpinScreen.kt:254-264);
this ports the same Job + isActive guard to the phone."
```

Add a paragraph to that message if the hysteresis constant was retuned.

---

## Task 5: Surface errors when the spin sheet is collapsed

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:1529-1541` (Scaffold)
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` imports

**Interfaces:**
- Consumes: the existing `error` state at `MapScreen.kt:460`.
- Produces: no new API.

**Why:** `error` is written from twelve sites and read from exactly one — `MapScreen.kt:1771`, inside `SpinSheet`. The sheet is collapsed by default (`settingsCollapsed` starts `true` at `:526`), so "Location permission is required", "Waiting for your location…" and "Navigation failed: …" are all invisible in the screen's resting state.

**Decision, to be stated in the commit message:** the `SpinSheet` copy is **kept**. The snackbar is added alongside it, and `error` is not cleared after showing, so the sheet still displays the last error when expanded. This is the smallest change that fixes the visibility gap without removing existing behaviour.

- [ ] **Step 1: Add the imports**

In the material3 import block (alphabetically, after `Scaffold` at `MapScreen.kt:102`):

```kotlin
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
```

- [ ] **Step 2: Add the host state and the effect that drives it**

Immediately after the `error` declaration at `MapScreen.kt:460`:

```kotlin
    var error by remember { mutableStateOf<String?>(null) }
    // `error` has a dozen writers and, until now, one reader — inside SpinSheet,
    // which is collapsed by default. A denied location permission therefore
    // reported itself to nobody. The snackbar shows it whatever the bottom card
    // is doing; the sheet keeps its own copy for when it is open.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it) }
    }
```

- [ ] **Step 3: Attach the host to the Scaffold**

At `MapScreen.kt:1529`, the `Scaffold` call currently opens:

```kotlin
    Scaffold(
        // Modes are the app's top-level places, so they live in the one bar that
```

Add the `snackbarHost` parameter alongside the existing `contentWindowInsets`, immediately before it:

```kotlin
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
```

- [ ] **Step 4: Compile**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify on device**

Install, then revoke location permission:
```bash
adb shell pm revoke com.jellemax.detour android.permission.ACCESS_FINE_LOCATION
```
Open the app, deny the permission prompt, and leave the spin sheet collapsed.

Expected: a snackbar reading "Location permission is required" appears over the map. Before this change, nothing appeared at all.

Restore the permission afterwards:
```bash
adb shell pm grant com.jellemax.detour android.permission.ACCESS_FINE_LOCATION
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "fix(map): surface errors when the spin sheet is collapsed

error had twelve writers and one reader, inside SpinSheet — which is collapsed
by default, so permission denials and navigation failures reported themselves
to nobody. Adds a snackbar on the existing Scaffold. The SpinSheet copy is kept
and error is not cleared after showing, so expanding the sheet still shows the
last error."
```

---

## Task 6: Correct the iOS maneuver arrows

**Files:**
- Modify: `iosApp/Detour/NavScreen.swift:221-236`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt:35`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Independent of every other task in this plan.

**Why:** `maneuverIcon` renders six GraphHopper sign codes wrongly. `±3` (sharp turns) draw as U-turns; `-98`, `-8`, `8` (actual U-turns) and `±7` (keep left/right) all fall through to `default` and draw as "carry straight on". The correct table exists three times over: `app/.../ui/Navigation.kt:57-71`, `wear/.../MainActivity.kt:53-67`, `app/.../car/NavScreen.kt:575-593`.

Reported upstream as **maxke24/Detour#20**.

- [ ] **Step 1: Confirm the defect is still present**

Run:
```bash
sed -n '221,236p' iosApp/Detour/NavScreen.swift
```
Expected: `case -3: return "arrow.uturn.left"` is present and there is no case for `-98`, `-8`, `8`, `-7` or `7`.

- [ ] **Step 2: Replace the table**

Replace `iosApp/Detour/NavScreen.swift:221-236` with:

```swift
/// GraphHopper sign codes. The full set, not the -3…3 the doc comment on
/// RouteInstruction used to imply: ±7 are the motorway keep-left/keep-right
/// forks and -98/±8 are U-turns, and every one of them used to fall through to
/// "carry on" here — while a sharp turn drew as a U-turn. SF Symbols has no
/// distinct sharp-turn glyph, so sharp and normal share an arrow; that is a
/// cosmetic loss, drawing a sharp left as a U-turn was not.
private func maneuverIcon(_ instruction: NavInstruction?) -> String {
    guard let instruction else { return "arrow.up" }
    switch instruction.sign {
    case -98, -8: return "arrow.uturn.left"
    case 8: return "arrow.uturn.right"
    case -7: return "arrow.triangle.branch"
    case 7: return "arrow.triangle.branch"
    case -3: return "arrow.turn.up.left"
    case -2: return "arrow.turn.up.left"
    case -1: return "arrow.up.left"
    case 1: return "arrow.up.right"
    case 2: return "arrow.turn.up.right"
    case 3: return "arrow.turn.up.right"
    case 4, 5: return "flag.checkered"
    case 6: return "arrow.triangle.turn.up.right.circle"
    default: return "arrow.up"
    }
}
```

- [ ] **Step 3: Widen the KDoc the wrong table was derived from**

`shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt:35` currently reads:

```kotlin
    /** GraphHopper sign code: -3..3 turns, 0 straight, 4 finish, 6 roundabout… */
```

Replace with:

```kotlin
    /**
     * GraphHopper sign code. The full set, because a comment saying "-3..3" is
     * what the iOS arrow table was once written against:
     * -98/-8 U-turn (left), 8 U-turn right, -7/7 keep left/right,
     * -3..3 sharp-left through sharp-right with 0 straight on,
     * 4 finish, 5 via reached, 6 roundabout.
     */
```

- [ ] **Step 4: Verify the shared module still compiles**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :shared:compileCommonMainKotlinMetadata
```
Expected: `BUILD SUCCESSFUL`. (Step 3 is a comment change; this confirms nothing else was disturbed.)

The Swift change cannot be compiled without macOS. It is a `switch` over `Int` returning `String` with no new symbols, and CI's `ios.yml` will build it on the next push touching `iosApp/**`.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Detour/NavScreen.swift \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt
git commit -m "fix(ios): correct maneuver arrows for sharp turns, U-turns and forks

maneuverIcon drew sharp turns (±3) as U-turns, and drew actual U-turns
(-98/-8/8) and motorway keep-left/right (±7) as carry-straight-on. The phone,
wear and car tables all have the full code set; only iOS was written against
the '-3..3' the RouteInstruction KDoc implied, so that KDoc is widened too.

Reported upstream as maxke24/Detour#20."
```

---

## Task 7: State when logic earns the shared core

**Files:**
- Modify: `CONTRIBUTING.md:23-32`

**Interfaces:**
- Consumes: nothing.
- Produces: the rule that stages 2 and 3 cite when deciding what moves into `:shared`.

**Why:** `CONTRIBUTING.md` already states the architecture — *"the core is handed things, it never reaches for them"* and *"New logic goes in `shared/` unless it genuinely cannot"*. What it does not state is the operational test for existing code, which is what the later stages need in order to stop re-arguing the question per extraction.

- [ ] **Step 1: Read the section being extended**

Run:
```bash
sed -n '20,34p' CONTRIBUTING.md
```
Expected: the "the core is handed things" paragraph and the "New logic goes in `shared/`" paragraph.

- [ ] **Step 2: Add the operational test**

Immediately after the paragraph ending *"a change that lands only in `app/` silently makes iOS diverge."*, add:

```markdown
For code that already exists, two tests decide where it belongs:

> A policy earns the core when it is written more than once.
> A port earns an interface when it has more than one implementation.

The first is why the arrival/reroute rule and the convoy vote rule belong in
`shared/` — each is written twice or three times today, and the copies have
already drifted. The second is why `Platform.kt` still expects only the three
things named above: an interface with one implementation is indirection, not a
boundary.
```

- [ ] **Step 3: Verify the file still reads coherently**

Run:
```bash
sed -n '20,48p' CONTRIBUTING.md
```
Expected: the new block follows the existing rule without contradicting it, and the "wanting to add a fourth is the signal to push the dependency in" sentence above still stands.

- [ ] **Step 4: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs(contributing): state when logic earns the shared core

The architecture rule was already written down; the test for applying it to
existing code was not, so every extraction re-argued it from scratch."
```

---

## Execution Order

| Task | Depends on | Parallel with |
|---|---|---|
| 1 — CI test gate | — | 2, 5, 6, 7 |
| 2 — replay routes | — | 1, 5, 6, 7 |
| 3 — baseline recording | **2** | 1, 5, 6, 7 |
| 4 — Overpass fix | **3** | 6, 7 |
| 5 — error snackbar | — | 1, 2, 6, 7 |
| 6 — iOS arrows | — | everything |
| 7 — CONTRIBUTING rule | — | everything |

Tasks 3 and 4 form the only hard chain, and it is the reason Task 2 should be started first even though it is not blocking on paper.

Tasks 4 and 5 both edit `MapScreen.kt`. They touch disjoint regions (the effects at 1018-1083 versus the declaration at 460 and the Scaffold at 1529), but if run in parallel by separate agents they will conflict on the file. Serialise them, or have one agent do both — as **two commits**, never one.

## Stage Done Criteria

From the spec:

- [ ] A red unit test fails the CI build.
- [ ] All four replay routes run end to end, and the baseline is committed with its SHA.
- [ ] Route (ii) replays equivalently after Task 4, or the hysteresis retune is in the same commit with its reasoning.
- [ ] A denied location permission shows a visible message with the spin sheet collapsed.
- [ ] iOS renders a sharp left as a left turn and a U-turn as a U-turn.
- [ ] `wc -l < app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` is within a few lines of 3193.

Then run the full manual checklist in [`../12-eval-risk-sequencing.md`](../12-eval-risk-sequencing.md) once, noting anything it misses — stage 0 is where that checklist is itself validated as fit for purpose.

## Next

When this plan is complete, return to [`../specs/stage-0-verification-baseline.md`](../specs/stage-0-verification-baseline.md) and follow its **Next stage** footer: run stage 1's preconditions, record the result, and either re-brainstorm stage 1 or write its plan.
