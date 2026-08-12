# Stage 2 — Pure-Logic Extractions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn four decisions that today live inside coroutines and touch listeners into four
pure units under a new `com.jellemax.detour.map` package, each landing with its JUnit4 tests in
the same commit, and delete the car surface's duplicate of the one it shares. Behaviour is
unchanged; what is gained is an executable specification behind the CI gate.

**Architecture:** Values in, one decision out. No I/O, no coroutines, no Android types, no
clock of their own — `System.currentTimeMillis()` stays at the call site and arrives as a `Long`
parameter. Three of the four are wired in; `CameraAuthority` is written, tested and
deliberately left with zero callers for stage 4 to judge. `MapScreen.kt` keeps every one of its
`remember` declarations and every effect key list exactly as they are: state ownership is stage
4's problem and an effect's key list is behaviour, not formatting.

**Tech Stack:** Kotlin, JUnit4 (`junit:junit:4.13.2`, `app/build.gradle.kts:163`).

**Spec:** [`../specs/stage-2-pure-extractions.md`](../specs/stage-2-pure-extractions.md) — its
Scope, Out of scope and Work items are binding. Preconditions re-verified against the working
tree at base commit `4cf2977` on `refactor/mapscreen-split`: `SpinShare.kt` exists,
`MapScreen.kt` is 1549 lines (inside the 1500–1700 range), the three inline policies are still
shaped as described, `leadingSpinIndex` still has two call sites, the car's
`Same arrival/reroute policy` comment is still there, and `.github/workflows/build.yml:118`
still runs `:app:testDebugUnitTest :shared:testDebugUnitTest`.

**Every line number in this plan was derived with `grep -n` against the tree at `4cf2977`.**
The spec's own citations predate stage 1 and are wrong; re-derive before trusting any number,
including these, if anything has landed since.

## Global Constraints

- **Commit messages:** Conventional Commits. **No `Co-Authored-By` trailer. No
  `Claude-Session` trailer. No trailers of any kind.**
- **One work item, one commit.** There are five work items here, not four: the car-side
  deletion is its own item (2a′) precisely so it gets its own commit.
- **Each unit lands with its tests in the same commit.** A pure function merged without its
  test has gained nothing, and the CI gate is the whole reason this stage is worth doing.
- **Gradle commands are written plainly:** `./gradlew :app:testDebugUnitTest`,
  `./gradlew :app:compileDebugKotlin`, `./gradlew :app:assembleDebug :app:assembleRelease`.
- **Read `detour-staged-refactor` before the first commit and `detour-compose-state-hazards`
  before touching either call site.** This plan does not restate them. Two rules from the first
  one are load-bearing here and are called out where they bite: car-side deletions trail their
  extraction by exactly one commit, and `camSuspended` and `lastGestureMs` never change in the
  same commit. From the second: reads inside a `LaunchedEffect` body are live snapshot reads,
  which is why 2c is two functions rather than one.
- **Every new file is `package com.jellemax.detour.map`**, not `ui.map`. These are not UI, and
  stage 3 may promote some of them to `:shared` — a non-`ui` package makes that a move rather
  than a rename.
- **`internal`, not public.** Consumers are `ui/` and `car/`, both in `:app`, and `internal`
  reaches `app/src/test`. In-repo precedent for internal-for-test: `HistoryScreen.kt:72,120`,
  exercised by `app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt`.
- **Do not change `MapScreen.kt`'s state declarations** (`:228`, `:232-234`) or any effect key
  list. The resume effect's keys at `:411` stay exactly
  `(camSuspended, spinning, candidates.isEmpty(), spinOffer == null)`.
- **2b does not change its call site.** See the constraint block below it.
- **Move a comment, do not copy it.** The rationale comments are the design record
  (`CONTRIBUTING.md:177-189`). Where a comment moves into a new file, the call site gets a
  one-line pointer, not a second copy that can drift.

### The test idiom

Match the two existing test files —
`app/src/test/java/com/jellemax/detour/notif/PlaceNotificationsTest.kt` and
`app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt`. Do not invent a different
one.

- Plain JUnit4: `org.junit.Test`, `org.junit.Assert.assertEquals` / `assertTrue` /
  `assertFalse`. **No Robolectric, no `compose-ui-test`, no `androidTest`** — none exist in this
  repo and this stage does not add any.
- A KDoc on the test class naming the function under test, what failure it guards against, and
  the sentence both existing files carry: *"No Android APIs involved, so no
  emulator/Robolectric needed."*
- `private fun` factory helpers at the top of the class for the fixture shapes.
- Test names are lowerCamelCase sentences about behaviour
  (`tiesGoToTheLowestIndex`), not `testX`.
- A KDoc on any test whose *reason* is not obvious from its name.

### Sequencing

Items 2a, 2b and 2c are independent and may run in parallel. Two orderings are not free:

- **2a′ runs after 2a, in its own commit.** A car regression and a phone regression must never
  share a bisect.
- **2d runs after 2c.** The spec calls all four parallel; this plan narrows that for one
  reason: 2d's asymmetry test asserts its consequence *through* `FollowCamera.shouldResume`,
  which is what makes the asymmetry a behaviour question rather than an abstract note. Without
  2c that test cannot be written.

## File Structure

| # | New file | Contents | Call sites changed |
|---|---|---|---|
| 2a | `app/src/main/java/com/jellemax/detour/map/NavPolicy.kt` | `NavPolicy` object: `ARRIVE_METERS`, `OFF_ROUTE_METERS`, `REROUTE_COOLDOWN_MS`, `Decision`, `decide()` | `ui/MapScreen.kt:1070-1100` |
| 2a | `app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt` | 10 tests | — |
| 2a′ | — | — | `car/NavScreen.kt:53-55` (deleted), `:242-277` |
| 2b | `app/src/main/java/com/jellemax/detour/map/GroupSpinRules.kt` | `leadingSpinIndex` (moved from `ui/SpinShare.kt:50-60`), `expectedSpinVoters`, `SpinRoundOutcome`, `resolveSpinRound` + the 16-line rationale moved from `ui/MapScreen.kt:553-568` | **none** — one import added to `MapScreen.kt`, the two `leadingSpinIndex` calls at `:579` and `:1448` keep a zero-line diff |
| 2b | `app/src/test/java/com/jellemax/detour/map/GroupSpinRulesTest.kt` | 11 tests | — |
| 2c | `app/src/main/java/com/jellemax/detour/map/FollowCamera.kt` | `FollowCamera` object: `shouldWatch()`, `shouldResume()` | `ui/MapScreen.kt:407-423` |
| 2c | `app/src/test/java/com/jellemax/detour/map/FollowCameraTest.kt` | 10 tests | — |
| 2d | `app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt` | `CameraAuthority` object: `State`, `Action`, `reduce()` | **none, and that is a done criterion** |
| 2d | `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt` | 11 tests | — |

`com.jellemax.detour.map` does not exist yet (`grep -rn 'package com.jellemax.detour.map' app/`
→ no hits at `4cf2977`). Nothing that is really UI may accumulate in it.

---

## Task 2a: `NavPolicy` — arrival and reroute

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/map/NavPolicy.kt`
- Create: `app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`

**Interfaces:**
- Produces: `internal object NavPolicy` with
  `fun decide(progress: NavEngine.Progress, hasDestination: Boolean, rerouting: Boolean, lastRerouteMs: Long, nowMs: Long): Decision`.
- Consumes: `NavEngine.Progress`
  (`shared/src/commonMain/kotlin/com/jellemax/detour/data/NavEngine.kt:13-45`) — pure Kotlin in
  commonMain, no Android.

The two decisions being extracted are `MapScreen.kt:1071-1073` (arrival) and `:1083-1085`
(reroute), inside the navigating effect at `:1056-1101`. The car's copy is `NavScreen.kt:243`
and `:249` under the comment at `:242`; it is **not** touched in this commit.

- [ ] **Step 1: Write the failing test first**

Create `app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.data.NavEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [NavPolicy.decide] - the arrival and reroute rules the phone map and
 * the Android Auto screen share. Getting `<` versus `<=` wrong here means
 * either arriving a street early or never arriving at all, and until this file
 * neither surface had a test: both carried their own copy of the arithmetic.
 * No Android APIs involved, so no emulator/Robolectric needed.
 */
class NavPolicyTest {

    /** Only the two fields [NavPolicy.decide] reads carry meaning here; the rest
     *  are whatever a Progress needs in order to exist. */
    private fun progress(remainingMeters: Double, offRouteMeters: Double) = NavEngine.Progress(
        offRouteMeters = offRouteMeters,
        nextInstruction = null,
        distanceToTurnMeters = remainingMeters,
        remainingMeters = remainingMeters,
        routeMeters = 10_000.0,
        remainingTimeMs = null,
        speedLimitKmh = null,
    )

    private fun decide(
        remainingMeters: Double,
        offRouteMeters: Double,
        hasDestination: Boolean = true,
        rerouting: Boolean = false,
        lastRerouteMs: Long = 0L,
        nowMs: Long = 1_000_000L,
    ) = NavPolicy.decide(
        progress = progress(remainingMeters, offRouteMeters),
        hasDestination = hasDestination,
        rerouting = rerouting,
        lastRerouteMs = lastRerouteMs,
        nowMs = nowMs,
    )

    @Test
    fun arrivesWhenCloseToTheEndAndStillOnTheLine() {
        assertEquals(NavPolicy.Decision.Arrived, decide(remainingMeters = 10.0, offRouteMeters = 5.0))
    }

    /** Arrival needs *both* bounds. Ten metres of route left while 200 m off the
     *  line is a parallel road, not a destination - and it is a reroute. */
    @Test
    fun doesNotArriveWhileOffTheLine() {
        assertEquals(NavPolicy.Decision.Reroute, decide(remainingMeters = 10.0, offRouteMeters = 200.0))
    }

    @Test
    fun doesNotArriveWhileStillFarFromTheEnd() {
        assertEquals(NavPolicy.Decision.Continue, decide(remainingMeters = 500.0, offRouteMeters = 5.0))
    }

    /** A round trip has no destination: it ends back where it started, and
     *  rerouting one would change the whole ride. Neither rule may fire however
     *  close the end of the line is. */
    @Test
    fun neverArrivesOrReroutesWithoutADestination() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 1.0, offRouteMeters = 1.0, hasDestination = false),
        )
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 5_000.0, offRouteMeters = 500.0, hasDestination = false),
        )
    }

    /** The boundary, stated: exactly [NavPolicy.ARRIVE_METERS] remaining does
     *  not arrive, because the test is `<`. */
    @Test
    fun exactlyTheArrivalRadiusDoesNotArrive() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = NavPolicy.ARRIVE_METERS, offRouteMeters = 5.0),
        )
        assertEquals(
            NavPolicy.Decision.Arrived,
            decide(remainingMeters = NavPolicy.ARRIVE_METERS - 0.01, offRouteMeters = 5.0),
        )
    }

    /** Exactly on the off-route bound is a dead band by construction: arrival
     *  needs `offRouteMeters <` it and a reroute needs `>` it, so 60.0 exactly
     *  does neither. Pinned because collapsing the two comparisons into one
     *  would look like a tidy-up and would change what happens at the bound. */
    @Test
    fun exactlyTheOffRouteBoundNeitherArrivesNorReroutes() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 10.0, offRouteMeters = NavPolicy.OFF_ROUTE_METERS),
        )
    }

    @Test
    fun reroutesOncePastTheOffRouteBound() {
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(remainingMeters = 2_000.0, offRouteMeters = NavPolicy.OFF_ROUTE_METERS + 0.01),
        )
    }

    /** A request already in flight is not asked for again. Both call sites clear
     *  the flag in a `finally`, so a failed reroute re-arms on the next fix -
     *  gated from then on only by the cooldown. */
    @Test
    fun doesNotRerouteWhileOneIsInFlight() {
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(remainingMeters = 2_000.0, offRouteMeters = 300.0, rerouting = true),
        )
    }

    @Test
    fun respectsTheRerouteCooldown() {
        val last = 1_000_000L
        assertEquals(
            NavPolicy.Decision.Continue,
            decide(2_000.0, 300.0, lastRerouteMs = last, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS),
        )
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, lastRerouteMs = last, nowMs = last + NavPolicy.REROUTE_COOLDOWN_MS + 1),
        )
    }

    /** Both call sites start `lastRerouteMs` at 0 (`MapScreen.kt:228`,
     *  `car/NavScreen.kt:112`), so the first reroute of a session must not be
     *  held off by a cooldown measured from the epoch. */
    @Test
    fun theFirstRerouteOfASessionIsNotBlocked() {
        assertEquals(
            NavPolicy.Decision.Reroute,
            decide(2_000.0, 300.0, lastRerouteMs = 0L, nowMs = 1_700_000_000_000L),
        )
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.NavPolicyTest'
```
Expected: a compilation failure naming `NavPolicy`. That is the test running before the code
exists; anything else (a pass, a different error) means the file landed in the wrong place.

- [ ] **Step 3: Create `NavPolicy.kt`**

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.data.NavEngine

/**
 * When a navigation session has arrived, and when it should ask for a fresh
 * route. Pure: values in, one decision out, no clock of its own and no I/O.
 *
 * Two surfaces drive navigation off the same GPS pipeline - the phone map
 * (`ui/MapScreen.kt`'s navigating LaunchedEffect) and Android Auto
 * (`car/NavScreen.kt`'s onFix) - and each carried its own copy of these two
 * tests, the car's under a comment admitting it. Two copies is two chances to
 * get a bound wrong on one surface only.
 */
internal object NavPolicy {

    /** Inside this much remaining route, and still on it, the trip has arrived. */
    const val ARRIVE_METERS = 40.0

    /** How far off the drawn line counts as off route: arrival must be inside
     *  this bound, a reroute outside it. */
    const val OFF_ROUTE_METERS = 60.0

    /** Minimum gap between reroute requests. Both call sites stamp on request
     *  rather than on success, so a failed reroute is retried after the cooldown
     *  rather than immediately. */
    const val REROUTE_COOLDOWN_MS = 15_000L

    sealed interface Decision {
        /** Keep following the line that is already drawn. */
        data object Continue : Decision
        /** Arrived: end the session. */
        data object Arrived : Decision
        /** Off route and out of cooldown: fetch a fresh route to the destination. */
        data object Reroute : Decision
    }

    /**
     * [hasDestination] is false for a round trip, which has nothing to arrive at
     * and nothing to reroute to. The phone passes `destination != null`; the
     * car's destination is a constructor parameter and so always present.
     *
     * [nowMs] and [lastRerouteMs] are wall-clock millis - the caller owns the
     * clock, which is what keeps this testable.
     *
     * Arrival is tested first, matching both call sites. The order cannot change
     * an outcome: the two branches are mutually exclusive on [NavEngine.Progress.offRouteMeters],
     * arrival needing it under [OFF_ROUTE_METERS] and a reroute needing it over.
     */
    fun decide(
        progress: NavEngine.Progress,
        hasDestination: Boolean,
        rerouting: Boolean,
        lastRerouteMs: Long,
        nowMs: Long,
    ): Decision {
        if (!hasDestination) return Decision.Continue
        if (progress.remainingMeters < ARRIVE_METERS &&
            progress.offRouteMeters < OFF_ROUTE_METERS
        ) {
            return Decision.Arrived
        }
        if (progress.offRouteMeters > OFF_ROUTE_METERS &&
            !rerouting &&
            nowMs - lastRerouteMs > REROUTE_COOLDOWN_MS
        ) {
            return Decision.Reroute
        }
        return Decision.Continue
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.NavPolicyTest'
```
Expected: `BUILD SUCCESSFUL`, 10 tests. If `exactlyTheOffRouteBoundNeitherArrivesNorReroutes`
fails, a comparison was widened to `<=`/`>=` — fix the code, not the test.

- [ ] **Step 5: Rewrite the phone call site**

In `MapScreen.kt`, replace lines 1070–1100 (from the `// Arrived (point-to-point…` comment
through the closing brace of the reroute `if`) with:

```kotlin
        // Arrival and reroute are NavPolicy's call, shared with car/NavScreen.kt.
        val dest = destination
        val now = System.currentTimeMillis()
        when (NavPolicy.decide(
            progress = progress,
            hasDestination = dest != null,
            rerouting = rerouting,
            lastRerouteMs = lastRerouteMs,
            nowMs = now,
        )) {
            // Point-to-point only; loops end back at the start on their own.
            NavPolicy.Decision.Arrived -> {
                stopNavigation()
                return@LaunchedEffect
            }
            // Off route → fresh route to the destination. Launched on the screen
            // scope so the next GPS fix doesn't cancel the request; loops keep
            // their drawn line (rerouting a loop would change the whole trip).
            NavPolicy.Decision.Reroute -> {
                val target = dest ?: return@LaunchedEffect // Reroute implies a destination
                rerouting = true
                lastRerouteMs = now
                scope.launch {
                    try {
                        route = withContext(Dispatchers.IO) {
                            RoutingServer.route(serverConfig, pos, target, mode.ghProfile,
                                Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                        }
                    } catch (e: Exception) {
                        // stay on the old line; retried after the cooldown
                    } finally {
                        rerouting = false
                    }
                }
            }
            NavPolicy.Decision.Continue -> {}
        }
```

and add `import com.jellemax.detour.map.NavPolicy` to the import block.

Three equivalence points to hold on to, because they are the whole review of this step:

1. `val dest = destination` moves from `:1081` to above the `when`, and `val now` from `:1082`.
   There is no suspension point between the old and new positions, so no recomposition can
   intervene and both reads are identical.
2. `dest ?: return@LaunchedEffect` is unreachable — `Decision.Reroute` is only returned when
   `hasDestination` was true. It exists because `dest != null` was tested inside an argument
   list, so the compiler cannot smart-cast in the branch. `?: return@…` is this file's own
   idiom for exactly this shape (`:1051`, `:1058`, `:1059`).
3. The effect's key list at `:1056` is untouched. It still deliberately re-keys itself by
   writing `route` from `scope.launch` — comment at `:1078-1080`, and
   `detour-compose-state-hazards` §1 explains why.

- [ ] **Step 6: Compile and run the whole suite**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL` for both.

- [ ] **Step 7: Confirm the car is untouched, then commit**

```bash
git diff --stat
```
Expected: exactly three files — `map/NavPolicy.kt`, `map/NavPolicyTest.kt`, `ui/MapScreen.kt`.
**`car/NavScreen.kt` must not appear.** If it does, split the commit.

```bash
git add app/src/main/java/com/jellemax/detour/map/NavPolicy.kt \
        app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(nav): extract the arrival and reroute policy, with tests"
```

---

## Task 2a′: delete the car's copy of the policy

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/car/NavScreen.kt`

**One commit behind 2a, never sharing one with it.** A car regression and a phone regression in
a single revert is two bisects (`detour-staged-refactor` §4).

- [ ] **Step 1: Replace the duplicated policy at `NavScreen.kt:242-277`**

Replace from the `// Same arrival/reroute policy…` comment at `:242` through the closing brace
of the reroute `if` at `:277` with:

```kotlin
        // Arrival and reroute are NavPolicy's call, shared with MapScreen.kt's
        // navigating LaunchedEffect. `arrived` stays here: it is this screen's
        // own once-only latch on popping itself, not part of the policy.
        val now = System.currentTimeMillis()
        when (NavPolicy.decide(
            progress = p,
            hasDestination = true, // a constructor parameter on this screen
            rerouting = rerouting,
            lastRerouteMs = lastRerouteMs,
            nowMs = now,
        )) {
            NavPolicy.Decision.Arrived -> if (!arrived) {
                arrived = true
                screenManager.pop()
            }
            NavPolicy.Decision.Reroute -> {
                rerouting = true
                lastRerouteMs = now
                speak("Rerouting")
                lifecycleScope.launch {
                    try {
                        val fresh = withContext(Dispatchers.IO) {
                            RoutingServer.route(serverConfig, pos, destination, TravelMode.CAR.ghProfile,
                                Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                        }
                        route = fresh
                        // The line on the map is only pushed when it changes, so a
                        // reroute is the one moment it has to be pushed again.
                        renderer.setRoute(fresh.polyline, destination)
                        // Instruction indices belong to the old polyline; start the
                        // prompts for the new one from scratch, "Rerouting" followed
                        // by what the new line asks for next.
                        voiceStepKey = Int.MIN_VALUE
                        voicePhase = 0
                        startAnnounced = false
                        templateKey = null
                    } catch (e: Exception) {
                        // stay on the old line; retried after the cooldown
                        Log.w(TAG, "reroute failed", e)
                    } finally {
                        rerouting = false
                    }
                }
            }
            NavPolicy.Decision.Continue -> {}
        }
```

Add `import com.jellemax.detour.map.NavPolicy`.

Two differences from the original, both provably inert:

1. **The original's early `return` after popping is gone.** The `when` arms are mutually
   exclusive and the `when` is the last statement in `onFix` (the function closes at `:278`),
   so the only thing the `return` skipped was the reroute test — which cannot fire under
   `Arrived`, since arrival requires `offRouteMeters < 60` and a reroute requires `> 60`.
2. **`val now` moves above the arrival test** (it was computed at `:248`, after it). It is
   read from the clock a few microseconds earlier and is still only used by the reroute branch.

`arrived` (`:119`) keeps its guard at the call site: it latches *this screen popping itself*,
which no other surface has, and the original evaluated the reroute test on fixes where
`arrived` was already true. Under the `when` those fixes take the `Arrived` arm and do nothing
— identical, per point 1.

- [ ] **Step 2: Delete the three now-unused constants**

Delete `NavScreen.kt:53-55`:

```kotlin
private const val ARRIVE_METERS = 40.0
private const val OFF_ROUTE_METERS = 60.0
private const val REROUTE_COOLDOWN_MS = 15_000L
```

Leave `CAMERA_FETCH_MARGIN_M` and `CAMERA_FETCH_THROTTLE_MS` (`:56-57`) alone. Confirm nothing
else referenced the three:

```bash
grep -rn 'ARRIVE_METERS\|OFF_ROUTE_METERS\|REROUTE_COOLDOWN_MS' app/src/main/java/com/jellemax/detour/
```
Expected: hits only in `map/NavPolicy.kt` and the two `NavPolicy.` references in the new car
code. At `4cf2977` the only uses were `NavScreen.kt:243` and `:249`.

- [ ] **Step 3: Compile, test, commit**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
git diff --stat   # expected: car/NavScreen.kt only
git add app/src/main/java/com/jellemax/detour/car/NavScreen.kt
git commit -m "refactor(car): use the shared NavPolicy instead of a second copy"
```

---

## Task 2b: `GroupSpinRules` — the vote round

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/map/GroupSpinRules.kt`
- Create: `app/src/test/java/com/jellemax/detour/map/GroupSpinRulesTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/SpinShare.kt` (delete `:50-60`)
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (one import; the 16-line
  comment at `:553-568` replaced by a 3-line pointer)

> ### Constraint: the call site does not change
>
> The vote-round effect at `MapScreen.kt:569-581` is **left exactly as it is**, and
> `resolveSpinRound` ships with **no caller in production code**. Verifying the convoy vote path
> needs two devices transmitting to each other, which is not available; the spec says so in its
> Risks section (*"It needs two devices to verify properly. If two devices are not available,
> extract and test but do not change the call site until they are."*). Convoy is Tier 3
> (`detour-staged-refactor` §5) and there is no way to earn it from a desk.
>
> This is why `leadingSpinIndex` stays a **top-level** function rather than becoming a member of
> an object: the two existing calls at `MapScreen.kt:579` and `:1448` then keep a byte-identical
> diff and only an import line is added. Line `:579` is *inside* the effect this constraint
> protects, and requalifying it would edit that effect's body for no behavioural gain.

- [ ] **Step 1: Write the failing test first**

Create `app/src/test/java/com/jellemax/detour/map/GroupSpinRulesTest.kt`:

```kotlin
package com.jellemax.detour.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [leadingSpinIndex], [expectedSpinVoters] and [resolveSpinRound] - how
 * a convoy's shared spin resolves to one destination. The failure these rules
 * exist to prevent is a convoy splitting across two destinations, and until
 * this file the argument for why they prevent it existed only as a comment.
 * Read the comment above [resolveSpinRound] first; these tests pin what it
 * claims.
 *
 * Pure rules, plain JUnit4: no Android APIs, so no emulator/Robolectric needed.
 * Note that `MapScreen.kt`'s vote-round effect is deliberately *not* wired to
 * [resolveSpinRound] - verifying the convoy path needs two devices, so the
 * stage-2 plan extracts and tests the rule without moving the call site.
 */
class GroupSpinRulesTest {

    @Test
    fun anEmptyVoteMapLeadsWithTheFirstCandidate() {
        assertEquals(0, leadingSpinIndex(emptyMap(), candidateCount = 3))
    }

    @Test
    fun theMostVotedCandidateLeads() {
        assertEquals(2, leadingSpinIndex(mapOf("a" to 2, "b" to 2, "c" to 1), candidateCount = 3))
    }

    /** A tie goes to the lowest index, on every device, without anyone having to
     *  agree on who voted first. */
    @Test
    fun tiesGoToTheLowestIndex() {
        assertEquals(0, leadingSpinIndex(mapOf("a" to 0, "b" to 2), candidateCount = 3))
        assertEquals(1, leadingSpinIndex(mapOf("a" to 1, "b" to 2), candidateCount = 3))
    }

    /** A vote for a candidate this device does not have - a frame from a newer
     *  offer, an index past the end - is ignored rather than breaking the tally. */
    @Test
    fun votesOutsideTheCandidateRangeAreIgnored() {
        assertEquals(0, leadingSpinIndex(mapOf("a" to 7, "b" to -1, "c" to 0), candidateCount = 3))
    }

    /** A blank username means not signed in, and cannot appear in the vote map's
     *  keys, so the round must not wait for it. */
    @Test
    fun aSignedOutDeviceIsNotWaitedFor() {
        assertEquals(setOf("alice"), expectedSpinVoters(setOf("alice"), myUsername = ""))
        assertEquals(setOf("alice", "me"), expectedSpinVoters(setOf("alice"), myUsername = "me"))
    }

    /** A one-candidate offer is the sharer announcing the winner. Everyone
     *  commits it - votes or no votes, sharer or receiver. */
    @Test
    fun aOneCandidateOfferCommitsRegardlessOfVotes() {
        assertEquals(
            SpinRoundOutcome.CommitOnly,
            resolveSpinRound(candidateCount = 1, fromMe = false,
                expected = setOf("alice", "me"), votes = emptyMap()),
        )
        assertEquals(
            SpinRoundOutcome.CommitOnly,
            resolveSpinRound(candidateCount = 1, fromMe = true,
                expected = setOf("me"), votes = mapOf("me" to 0)),
        )
    }

    @Test
    fun theSharerClosesTheRoundOnceEveryoneHasVoted() {
        assertEquals(
            SpinRoundOutcome.CloseRound(leadIndex = 1),
            resolveSpinRound(
                candidateCount = 3,
                fromMe = true,
                expected = setOf("me", "alice"),
                votes = mapOf("me" to 1, "alice" to 1),
            ),
        )
    }

    @Test
    fun theSharerWaitsWhileAVoteIsStillOut() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = true, expected = setOf("me", "alice"), votes = mapOf("me" to 1)),
        )
    }

    /** The whole point of `fromMe`: a receiving device never resolves the votes
     *  itself, however complete its own view looks. */
    @Test
    fun aReceivingDeviceNeverClosesARound() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = false, expected = setOf("me", "alice"),
                votes = mapOf("me" to 2, "alice" to 2)),
        )
    }

    /**
     * A peer pruned mid-round - quiet for 20 s and dropped from
     * `ConvoyLiveClient.peers` - shrinks `expected` on whichever device noticed
     * first. That divergence is exactly what could close one round on two
     * different candidates, and it cannot, because only the sharer closes
     * anything and there is exactly one sharer. Both halves are pinned: the
     * receiver with the *shorter* list still waits, and the sharer still
     * expecting the pruned peer keeps the round open for everyone.
     */
    @Test
    fun aPrunedPeerCannotCompleteTheRoundEarly() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = false, expected = setOf("me", "alice"),
                votes = mapOf("me" to 0, "alice" to 2)),
        )
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = true, expected = setOf("me", "alice", "bob"),
                votes = mapOf("me" to 0, "alice" to 2)),
        )
    }

    /** No known live voter - not even this device's own username - is not a
     *  complete round. Closing on zero votes would commit an arbitrary candidate
     *  for the whole convoy. */
    @Test
    fun anEmptyExpectedSetNeverClosesARound() {
        assertEquals(
            SpinRoundOutcome.Wait,
            resolveSpinRound(3, fromMe = true, expected = emptySet(), votes = emptyMap()),
        )
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.GroupSpinRulesTest'
```
Expected: unresolved reference `resolveSpinRound`.

- [ ] **Step 3: Create `GroupSpinRules.kt`**

`leadingSpinIndex`'s body and KDoc are **copied byte for byte** from `SpinShare.kt:50-60`. The
16-line block comment above `resolveSpinRound` is **moved verbatim** from `MapScreen.kt:553-568`
— it is the specification, and it is why this is the highest-value item in the stage.

```kotlin
package com.jellemax.detour.map

/** Tie-break rule for a group spin's leader: ties (including "nobody's voted
 *  yet", every count 0) go to the lowest index. `>` rather than `>=` is what
 *  makes that deterministic - every device tallying the same votes lands on
 *  the same leader without needing to compare who voted when. */
internal fun leadingSpinIndex(votes: Map<String, Int>, candidateCount: Int): Int {
    val counts = IntArray(candidateCount)
    votes.values.forEach { if (it in counts.indices) counts[it]++ }
    var lead = 0
    for (i in 1 until candidateCount) if (counts[i] > counts[lead]) lead = i
    return lead
}

/** What a device should do about the offer currently on the table. */
internal sealed interface SpinRoundOutcome {
    /** Nothing: not this device's round to close, or votes still out. */
    data object Wait : SpinRoundOutcome
    /** This offer *is* the decision - commit its only candidate. */
    data object CommitOnly : SpinRoundOutcome
    /** Every expected voter has voted and this device opened the round: re-offer
     *  [leadIndex] on its own, which is what commits it everywhere. */
    data class CloseRound(val leadIndex: Int) : SpinRoundOutcome
}

/** Who a round waits for: everyone currently live in the convoy, plus this
 *  device, which votes too. A blank [myUsername] means not signed in and can
 *  never appear in the vote map's keys, so it is not waited for. */
internal fun expectedSpinVoters(peerUsernames: Set<String>, myUsername: String): Set<String> =
    peerUsernames + setOfNotNull(myUsername.takeIf { it.isNotBlank() })

// How a vote round ends. Two halves, and which one runs depends on
// whether this device opened the round (see GroupSpin.fromMe):
//
//  - Anyone receiving a one-candidate offer commits it. That offer *is*
//    the decision, so every member lands on the same destination off the
//    same frame instead of each resolving the votes themselves.
//  - The sharer, once everyone currently live (convoyPeers plus itself)
//    has voted, sends the leader back out as exactly that one-candidate
//    offer — which then commits here too, through the branch above.
//
// Tallying independently on each device would have been simpler and
// wrong: convoyPeers prunes a member who's been quiet for 20s, so one
// phone can consider the round complete on two votes while another is
// still waiting for a third, and the two can resolve to different
// candidates. Splitting a convoy across two destinations is the exact
// failure this feature exists to prevent.
internal fun resolveSpinRound(
    candidateCount: Int,
    fromMe: Boolean,
    expected: Set<String>,
    votes: Map<String, Int>,
): SpinRoundOutcome {
    if (candidateCount <= 0) return SpinRoundOutcome.Wait
    if (candidateCount == 1) return SpinRoundOutcome.CommitOnly
    if (!fromMe) return SpinRoundOutcome.Wait
    if (expected.isEmpty() || !votes.keys.containsAll(expected)) return SpinRoundOutcome.Wait
    return SpinRoundOutcome.CloseRound(leadingSpinIndex(votes, candidateCount))
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.GroupSpinRulesTest'
```
Expected: `BUILD SUCCESSFUL`, 11 tests.

- [ ] **Step 5: Delete `leadingSpinIndex` from `SpinShare.kt`**

Delete `SpinShare.kt:50-60` — the KDoc and the function. Nothing else in that file moves;
`CANDIDATE_COLORS`, `asRouteCandidates` and `asSpinCandidates` stay.

- [ ] **Step 6: Add the import and the call-site pointer in `MapScreen.kt`**

Add `import com.jellemax.detour.map.leadingSpinIndex` to the import block. The two call sites
at `:579` and `:1448` are **not edited** — verify with a diff read:

```bash
git diff -- app/src/main/java/com/jellemax/detour/ui/MapScreen.kt | grep '^[+-][^+-]'
```
Expected: one added `import` line, the 16 removed comment lines, and the 3 added pointer lines.
**No line containing `leadingSpinIndex(` may appear as either `+` or `-`.**

Then replace the comment block at `:553-568` with:

```kotlin
    // How a vote round ends: the rule and its correctness argument are
    // resolveSpinRound in map/GroupSpinRules.kt. Not wired to it yet -
    // verifying the convoy path needs two devices transmitting to each other.
```

The `LaunchedEffect(spinOffer, spinVotes, convoyPeers, accountUsername)` at `:569` and its whole
body through `:581` keep a zero-line diff.

- [ ] **Step 7: Compile, test, commit**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
git add app/src/main/java/com/jellemax/detour/map/GroupSpinRules.kt \
        app/src/test/java/com/jellemax/detour/map/GroupSpinRulesTest.kt \
        app/src/main/java/com/jellemax/detour/ui/SpinShare.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(convoy): extract the group-spin vote rules, with tests"
```

---

## Task 2c: `FollowCamera.shouldResume` — the camera-resume rule

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/map/FollowCamera.kt`
- Create: `app/src/test/java/com/jellemax/detour/map/FollowCameraTest.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (`:407-423`)

**Two functions, not one, and this is the design decision of the item.** The call site asks the
question in two places: the effect's guard at `:412` decides whether to watch at all — and its
key list at `:411` restarts the effect when that answer changes — while the per-fix test at
`:417-419` runs inside a `TripTrackingService.lastFix` collector. Folding both into one
predicate evaluated per fix would read the four blocker flags **live** instead of at restart
(`detour-compose-state-hazards` §1: snapshot reads inside a `LaunchedEffect` body are not
cached). That is a behaviour change in the window between a blocker appearing and the next
recomposition, however small, and this stage changes no behaviour.

- [ ] **Step 1: Write the failing test first**

Create `app/src/test/java/com/jellemax/detour/map/FollowCameraTest.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.ui.CAM_RESUME_QUIET_MS
import com.jellemax.detour.ui.CAM_RESUME_SPEED_MPS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [FollowCamera] - when a camera parked by a pan, a pinch or a spin goes
 * back to following. Both halves are threshold arithmetic read on every GPS
 * fix: cheap to test, and expensive to get wrong in a way nobody notices until
 * the map yanks itself out from under a two-finger zoom at 80 km/h. No Android
 * APIs involved, so no emulator/Robolectric needed.
 */
class FollowCameraTest {

    private val now = 1_700_000_000_000L

    /** Comfortably outside the quiet window. */
    private val longAgo = now - CAM_RESUME_QUIET_MS - 1

    @Test
    fun aParkedCameraWithNothingOnScreenWatchesForTheDriveOff() {
        assertTrue(FollowCamera.shouldWatch(
            camSuspended = true, spinning = false, hasCandidates = false, hasSpinOffer = false))
    }

    @Test
    fun anUnparkedCameraHasNothingToWatchFor() {
        assertFalse(FollowCamera.shouldWatch(
            camSuspended = false, spinning = false, hasCandidates = false, hasSpinOffer = false))
    }

    /** A spin in flight, its results on screen, or a convoy's offer still being
     *  voted on each hold the park: the map is parked *because* of them. */
    @Test
    fun aSpinOnScreenHoldsThePark() {
        assertFalse(FollowCamera.shouldWatch(true, spinning = true, hasCandidates = false, hasSpinOffer = false))
        assertFalse(FollowCamera.shouldWatch(true, spinning = false, hasCandidates = true, hasSpinOffer = false))
        assertFalse(FollowCamera.shouldWatch(true, spinning = false, hasCandidates = false, hasSpinOffer = true))
    }

    @Test
    fun resumesOnceMovingAndQuietForLongEnough() {
        assertTrue(FollowCamera.shouldResume(speedMps = 10.0, nowMs = now, lastGestureMs = longAgo))
    }

    /** Stopped at a light with the map panned stays panned, however long ago the
     *  pan was. */
    @Test
    fun neverResumesBelowTheSpeedThreshold() {
        assertFalse(FollowCamera.shouldResume(
            speedMps = CAM_RESUME_SPEED_MPS - 0.01, nowMs = now, lastGestureMs = longAgo))
    }

    /** The boundary, stated: exactly the threshold resumes, because the test is `>=`. */
    @Test
    fun exactlyTheSpeedThresholdResumes() {
        assertTrue(FollowCamera.shouldResume(
            speedMps = CAM_RESUME_SPEED_MPS, nowMs = now, lastGestureMs = longAgo))
    }

    @Test
    fun neverResumesInsideTheQuietWindow() {
        assertFalse(FollowCamera.shouldResume(
            speedMps = 25.0, nowMs = now, lastGestureMs = now - CAM_RESUME_QUIET_MS + 1))
    }

    /** The other boundary, stated: exactly the quiet window does *not* resume,
     *  because the test is `>`. It takes one more millisecond. */
    @Test
    fun exactlyTheQuietWindowDoesNotResume() {
        assertFalse(FollowCamera.shouldResume(
            speedMps = 25.0, nowMs = now, lastGestureMs = now - CAM_RESUME_QUIET_MS))
        assertTrue(FollowCamera.shouldResume(
            speedMps = 25.0, nowMs = now, lastGestureMs = now - CAM_RESUME_QUIET_MS - 1))
    }

    /** Both bounds, not either. */
    @Test
    fun needsBothTheSpeedAndTheQuietPeriod() {
        assertFalse(FollowCamera.shouldResume(speedMps = 1.0, nowMs = now, lastGestureMs = longAgo))
        assertFalse(FollowCamera.shouldResume(speedMps = 25.0, nowMs = now, lastGestureMs = now))
    }

    /** A pending convoy offer blocks the resume even when both thresholds are
     *  met - the two functions together, which is how the call site applies
     *  them: guard first, then the per-fix test. */
    @Test
    fun aPendingSpinOfferBlocksResumeEvenAtSpeedAndQuiet() {
        assertTrue(FollowCamera.shouldResume(speedMps = 25.0, nowMs = now, lastGestureMs = longAgo))
        assertFalse(FollowCamera.shouldWatch(
            camSuspended = true, spinning = false, hasCandidates = false, hasSpinOffer = true))
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.FollowCameraTest'
```
Expected: unresolved reference `FollowCamera`.

- [ ] **Step 3: Create `FollowCamera.kt`**

The constants stay in `ui/MapCameraTuning.kt:59-60` with the comment at `:55-58` that explains
their values; this file imports them rather than making a second copy. The "not while a spin is
on screen" reasoning is **moved** here from `MapScreen.kt:407-410`.

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.ui.CAM_RESUME_QUIET_MS
import com.jellemax.detour.ui.CAM_RESUME_SPEED_MPS

/**
 * When a parked camera goes back to following.
 *
 * Two functions rather than one predicate, because the call site asks the
 * question in two places: an effect guard whose key list restarts the effect
 * when the answer changes, and a per-fix test inside a `lastFix` collector.
 * Collapsing them would read the blocker flags live per fix instead of at
 * restart, which is a behaviour change - see `detour-compose-state-hazards` §1.
 */
internal object FollowCamera {

    /**
     * Whether a parked camera should be watching for a drive-off at all. Not
     * while a spin is on screen (own or a convoy's, still being voted on): the
     * candidates are the whole reason the map is parked where it is, and a
     * passenger spinning at speed would otherwise never get to read them.
     */
    fun shouldWatch(
        camSuspended: Boolean,
        spinning: Boolean,
        hasCandidates: Boolean,
        hasSpinOffer: Boolean,
    ): Boolean = camSuspended && !spinning && !hasCandidates && !hasSpinOffer

    /**
     * Whether this fix ends the park: moving, and long enough since the last
     * gesture. The quiet period is what stops a two-finger zoom at 80 km/h from
     * being yanked out from under you mid-gesture.
     */
    fun shouldResume(speedMps: Double, nowMs: Long, lastGestureMs: Long): Boolean =
        speedMps >= CAM_RESUME_SPEED_MPS && nowMs - lastGestureMs > CAM_RESUME_QUIET_MS
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.FollowCameraTest'
```
Expected: `BUILD SUCCESSFUL`, 10 tests.

- [ ] **Step 5: Rewrite the call site**

Replace `MapScreen.kt:407-423` with:

```kotlin
    // Driving off takes the camera back; the rule is FollowCamera's. The keys are
    // derived booleans on purpose - keying on the collections themselves would
    // restart this collector on every convoy vote.
    LaunchedEffect(camSuspended, spinning, candidates.isEmpty(), spinOffer == null) {
        if (!FollowCamera.shouldWatch(
                camSuspended = camSuspended,
                spinning = spinning,
                hasCandidates = candidates.isNotEmpty(),
                hasSpinOffer = spinOffer != null,
            )
        ) {
            return@LaunchedEffect
        }
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (FollowCamera.shouldResume(
                    speedMps = fix.speedMps,
                    nowMs = System.currentTimeMillis(),
                    lastGestureMs = lastGestureMs,
                )
            ) {
                camSuspended = false
            }
        }
    }
```

Add `import com.jellemax.detour.map.FollowCamera`.

Equivalence: `!shouldWatch(a, b, c, d)` is `!a || b || c || d` by De Morgan, which is the
original guard at `:412` exactly. The key list is unchanged. `lastGestureMs` is still read live
inside the collector, as it was at `:418` — deliberate, per the hazards skill's table entry for
`MapScreen.kt:411`. **`camSuspended` and `lastGestureMs` are the only two camera variables this
commit touches, and it changes neither's write sites** (`detour-staged-refactor` §4).

- [ ] **Step 6: Compile, test, commit**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
git add app/src/main/java/com/jellemax/detour/map/FollowCamera.kt \
        app/src/test/java/com/jellemax/detour/map/FollowCameraTest.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(map): extract the camera-resume rule, with tests"
```

---

## Task 2d: `CameraAuthority` — a reducer with zero callers

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt`
- Create: `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt`
- Modify: **nothing.**

This is the cheapest possible experiment: it compiles, its tests pass, the app is byte-identical,
and stage 4 gets real code to compare against the Compose state-holder alternative instead of two
proposals. Runs after 2c (see Sequencing).

**The nine write sites it models.** Derived with `grep -n 'camSuspended\|lastGestureMs\|followMe'`
at `4cf2977`; re-derive before writing the file.

| `MapScreen.kt` | What it is | `camSuspended` | `lastGestureMs` | `followMe` | Action |
|---|---|---|---|---|---|
| `:386-387` | `park()` in the touch listener | `true` | stamped | — | `Gesture` |
| `:400` | `ACTION_UP` after a gesture | — | stamped, if parked | — | `GestureEnd` |
| `:420` | the drive-off resume | `false` | — | — | `DriveOffResumed` |
| `:521,524` | `choose()`, a spin candidate | `true` | stamped | — | `DestinationFramed` |
| `:548-549` | `commitSpinCandidate()` | `true` | stamped | — | `DestinationFramed` |
| `:698` | `startNavigation()` | `false` | — | — | `NavigationStarted` |
| `:1114` | `spin()` | `true` | **not stamped** | — | `SpinStarted` |
| `:1299-1300` | the follow button | `false` (else branch) | — | `false`/`true` | `FollowToggled` |
| `:1390-1391` | a saved-place chip | `true` | stamped | — | `DestinationFramed` |
| `:1541-1542` | a search result | `true` | stamped | — | `DestinationFramed` |

Nine `camSuspended` writes, six `lastGestureMs` writes and two `followMe` writes, plus the three
`remember` initialisers at `:232-234`. The reducer does **not** model the `myLocation ?: return`
at `:520` and `:547`, which is why `choose()` can set a destination without parking at all: that
guard stays at the call site, and a wiring stage must keep it there.

- [ ] **Step 1: Write the failing test first**

Create `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.map.CameraAuthority.Action
import com.jellemax.detour.map.CameraAuthority.State
import com.jellemax.detour.map.CameraAuthority.reduce
import com.jellemax.detour.ui.CAM_RESUME_QUIET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CameraAuthority.reduce] - the follow/park/resume machine that
 * `MapScreen.kt` currently spreads across nine write sites.
 *
 * **Nothing in the app calls this reducer**, deliberately: stage 4 of the
 * MapScreen refactor decides whether to adopt it. These tests therefore pin
 * the behaviour the nine write sites have *today*, so that whoever wires it can
 * tell an intended change from an accident - including the `lastGestureMs`
 * asymmetry at the bottom of this file, which is encoded, not fixed.
 *
 * No Android APIs involved, so no emulator/Robolectric needed.
 */
class CameraAuthorityTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun startsFollowingAndUnparked() {
        val state = State()
        assertTrue(state.followMe)
        assertFalse(state.camSuspended)
        assertTrue(state.following)
    }

    @Test
    fun aGestureParksAndStampsTheQuietWindow() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        assertTrue(parked.camSuspended)
        assertEquals(t0, parked.lastGestureMs)
        assertFalse(parked.following)
    }

    /** A park suspends following; it does not switch it off. That distinction is
     *  what lets the drive-off resume put the camera back without anyone
     *  pressing the follow button. */
    @Test
    fun aParkKeepsTheFollowIntentAndTheResumeRestoresIt() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        assertTrue(parked.followMe)
        val resumed = reduce(parked, Action.DriveOffResumed)
        assertTrue(resumed.following)
        assertEquals(t0, resumed.lastGestureMs) // the resume does not restamp
    }

    /** The finger coming up re-stamps the quiet window, so it is measured from
     *  the end of the pan rather than its start. */
    @Test
    fun aGestureEndRestampsAParkedCamera() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        val released = reduce(parked, Action.GestureEnd(atMs = t0 + 400))
        assertEquals(t0 + 400, released.lastGestureMs)
        assertTrue(released.camSuspended)
    }

    /** A tap that never left the slop circle was a pin drop or a marker tap, not
     *  a pan: an unparked camera is left completely alone. */
    @Test
    fun aGestureEndOnAnUnparkedCameraChangesNothing() {
        val state = State(lastGestureMs = t0)
        assertEquals(state, reduce(state, Action.GestureEnd(atMs = t0 + 5_000)))
    }

    /** Four call sites - a spin candidate, a convoy commit, a saved-place chip
     *  and a search result - frame a destination, and all four park exactly as a
     *  pan does. One action, so they cannot drift apart. */
    @Test
    fun framingADestinationParksExactlyLikeAGesture() {
        assertEquals(
            reduce(State(), Action.Gesture(atMs = t0)),
            reduce(State(), Action.DestinationFramed(atMs = t0)),
        )
    }

    @Test
    fun startingNavigationClearsAPark() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        val navigating = reduce(parked, Action.NavigationStarted)
        assertFalse(navigating.camSuspended)
        assertTrue(navigating.following)
    }

    @Test
    fun theFollowButtonTurnsFollowingOffWhenItIsOn() {
        val off = reduce(State(), Action.FollowToggled)
        assertFalse(off.followMe)
        assertFalse(off.following)
    }

    /** Pressing follow while parked does both jobs in one tap: intent back on,
     *  park cleared. Without the second half the button would appear to do
     *  nothing. */
    @Test
    fun theFollowButtonClearsAParkAsWellAsSettingTheIntent() {
        val parked = reduce(State(), Action.Gesture(atMs = t0))
        val following = reduce(parked, Action.FollowToggled)
        assertTrue(following.followMe)
        assertFalse(following.camSuspended)
        assertTrue(following.following)
    }

    /** Navigation drives the camera whether or not you are following; a park
     *  still stops it. */
    @Test
    fun navigationKeepsTheCameraActiveWithoutTheFollowIntent() {
        val notFollowing = reduce(State(), Action.FollowToggled)
        assertTrue(notFollowing.cameraActive(navigating = true))
        assertFalse(notFollowing.cameraActive(navigating = false))
        assertFalse(reduce(State(), Action.Gesture(atMs = t0)).cameraActive(navigating = true))
    }

    /**
     * **The asymmetry, named rather than fixed.** `spin()` (`MapScreen.kt:1114`)
     * parks without stamping the quiet window, while all six other parks stamp
     * both. The consequence is measurable: a spin-parked camera is already
     * eligible to resume on the next fix above the speed threshold, where a
     * pan-parked one has eight seconds of grace. That matters as soon as the
     * candidates are dismissed (`:1234`, `:1432`), which is what unblocks
     * `FollowCamera.shouldWatch`.
     *
     * Two earlier refactor proposals quietly unified the two. Unifying them is a
     * behaviour change and belongs to whoever wires this reducer - this test
     * exists so that whoever does it has to delete an assertion on purpose.
     */
    @Test
    fun aSpinParkIsEligibleToResumeImmediatelyWhereAPanIsNot() {
        val before = State(lastGestureMs = t0 - CAM_RESUME_QUIET_MS - 1)
        val spinParked = reduce(before, Action.SpinStarted)
        val panParked = reduce(before, Action.Gesture(atMs = t0))

        assertTrue(spinParked.camSuspended)
        assertTrue(panParked.camSuspended)
        assertEquals(before.lastGestureMs, spinParked.lastGestureMs) // not stamped
        assertEquals(t0, panParked.lastGestureMs)                    // stamped

        assertTrue(FollowCamera.shouldResume(10.0, nowMs = t0, lastGestureMs = spinParked.lastGestureMs))
        assertFalse(FollowCamera.shouldResume(10.0, nowMs = t0, lastGestureMs = panParked.lastGestureMs))
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.CameraAuthorityTest'
```
Expected: unresolved reference `CameraAuthority`.

- [ ] **Step 3: Create `CameraAuthority.kt`**

```kotlin
package com.jellemax.detour.map

/**
 * The camera's follow/park/resume machine as a pure reducer.
 *
 * **Nothing calls this.** It is written, tested and deliberately unwired: stage
 * 4 of the MapScreen refactor decides whether to adopt it, take the Compose
 * state-holder route instead, or discard it, and that decision is cheaper to
 * make against real code than against two proposals. `MapScreen.kt` still owns
 * `followMe`, `camSuspended` and `lastGestureMs` as three `remember`s with nine
 * `camSuspended` write sites between them; this is what those sites would
 * collapse into, and `CameraAuthorityTest` pins the behaviour they have today.
 *
 * The actions are named after the call sites they came from so the mapping can
 * be checked by grep rather than by memory - the table is in the stage-2 plan.
 */
internal object CameraAuthority {

    data class State(
        /** The resting intent: follow me around the map. Only the follow button
         *  turns this off. */
        val followMe: Boolean = true,
        /** A park. Does not switch following off - it suspends it until you are
         *  moving again. */
        val camSuspended: Boolean = false,
        /** When the last gesture, or gesture-equivalent park, happened: the start
         *  of the quiet window [FollowCamera.shouldResume] measures. */
        val lastGestureMs: Long = 0L,
    ) {
        /** What the follow button reflects. */
        val following: Boolean get() = followMe && !camSuspended

        /** Whether the frame loop should be aiming the camera at all. Navigation
         *  drives it regardless of [followMe]; a park still stops it. */
        fun cameraActive(navigating: Boolean): Boolean = (followMe || navigating) && !camSuspended
    }

    sealed interface Action {
        /** A drag past the touch slop, or a second finger down. */
        data class Gesture(val atMs: Long) : Action

        /** The finger coming up after a gesture, re-stamping the quiet window so
         *  it runs from the end of the pan. Leaves an unparked camera alone: a
         *  tap inside the slop circle was a pin drop or a marker tap, not a pan. */
        data class GestureEnd(val atMs: Long) : Action

        /** A destination picked and framed - a spin candidate, a convoy commit, a
         *  saved-place chip, a search result. Parks exactly as a gesture does,
         *  stamp included, so a pick made at speed is not re-centered before you
         *  have seen the route you just chose. */
        data class DestinationFramed(val atMs: Long) : Action

        /** A spin starting. Parks so the result can be framed, and does *not*
         *  stamp the quiet window - see [reduce]. */
        data object SpinStarted : Action

        /** The drive-off test passed. */
        data object DriveOffResumed : Action

        /** Navigation started; the route drives the camera from here. */
        data object NavigationStarted : Action

        /** The follow button. Following → stop following; not following → follow,
         *  and clear any park in the same tap. */
        data object FollowToggled : Action
    }

    /**
     * **The `lastGestureMs` asymmetry is encoded here, not fixed.**
     * [Action.SpinStarted] parks without stamping the quiet window while every
     * other park stamps both, which is what `MapScreen.kt:1114` does today. The
     * consequence is that a spin-parked camera may resume on the next fix above
     * the speed threshold once the candidates are dismissed, where a pan-parked
     * one gets its eight seconds. Two earlier proposals quietly unified the two;
     * unifying them is a behaviour change, it belongs to whoever wires this
     * reducer, and it is a `detour-staged-refactor` §4 rule that `camSuspended`
     * and `lastGestureMs` never change in the same commit.
     */
    fun reduce(state: State, action: Action): State = when (action) {
        is Action.Gesture -> state.copy(camSuspended = true, lastGestureMs = action.atMs)
        is Action.GestureEnd ->
            if (state.camSuspended) state.copy(lastGestureMs = action.atMs) else state
        is Action.DestinationFramed -> state.copy(camSuspended = true, lastGestureMs = action.atMs)
        Action.SpinStarted -> state.copy(camSuspended = true)
        Action.DriveOffResumed -> state.copy(camSuspended = false)
        Action.NavigationStarted -> state.copy(camSuspended = false)
        Action.FollowToggled ->
            if (state.following) state.copy(followMe = false)
            else state.copy(followMe = true, camSuspended = false)
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.jellemax.detour.map.CameraAuthorityTest'
```
Expected: `BUILD SUCCESSFUL`, 11 tests.

- [ ] **Step 5: Prove it has zero callers**

```bash
grep -rn 'CameraAuthority' app/ wear/ shared/ iosApp/ tools/
```
Expected output: **only** `app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt` and
`app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt`. Any hit under
`app/src/main/java/com/jellemax/detour/ui/` or `/car/` means the item overran its scope; revert
that hunk.

```bash
git diff --stat
```
Expected: exactly two files, both new. `MapScreen.kt` must not appear in this commit at all.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt \
        app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt
git commit -m "refactor(map): add CameraAuthority as a tested reducer, no callers yet"
```

---

## Done Criteria

- [ ] Four units under `com.jellemax.detour.map`, each with tests under
      `app/src/test/java/com/jellemax/detour/map/`. 42 new tests across four files.
- [ ] Five commits, in order: 2a, 2a′, 2b, 2c, 2d. One work item each, **no trailers**.
- [ ] `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` green, and
      `.github/workflows/build.yml:118` runs it.
- [ ] `./gradlew :app:assembleDebug :app:assembleRelease` succeeds — R8 catches what debug does
      not.
- [ ] `car/NavScreen.kt` calls `NavPolicy`, its three private constants are gone, and the
      `Same arrival/reroute policy` comment is gone with them:
      `grep -c 'Same arrival/reroute policy' app/src/main/java/com/jellemax/detour/car/NavScreen.kt`
      → `0`. In its own commit, one behind 2a.
- [ ] `grep -rn 'CameraAuthority' app/src/main/java/com/jellemax/detour/ui app/src/main/java/com/jellemax/detour/car`
      → no hits.
- [ ] `MapScreen.kt`'s vote-round effect (`:569-581` at `4cf2977`) has a zero-line diff across
      the whole stage:
      `git diff 4cf2977..HEAD -- app/src/main/java/com/jellemax/detour/ui/MapScreen.kt | grep -c 'leadingSpinIndex\|containsAll'`
      → `0`.
- [ ] No `remember`, `rememberSaveable` or `rememberUpdatedState` declaration in `MapScreen.kt`
      changed, and no effect key list changed. `tier0-greps.sh 4cf2977` prints every effect
      declaration the range touched; read them.
- [ ] `leadingSpinIndex` has exactly two call sites still, both unedited, now resolved through
      an import.

## Verification

**Tier: desk checklist + one replay pass** (`detour-staged-refactor` §5). Run Tier 0 on every
commit:

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh 4cf2977
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```

**Desk checklist**, on the debug build after 2d: map loads and follows; pan the map and confirm
it stays parked while stationary; spin, and confirm the candidates stay framed; reroll; cancel;
pick a candidate and confirm the camera frames you-to-destination and does not snap back; press
the follow button parked and unparked; search for a place and confirm the same framing; a
saved-place chip likewise; start navigation and confirm the camera takes over; end the trip.
Then the car surface: start a navigation session, confirm the turn card, the cluster trip and
the voice all still work, and drive it to the destination so the screen pops itself.

**One replay pass — route (iv) `stop-start.gpx`, for 2c.** It is the route that exercises
camera park/resume, and 2c is the only item that changes a `lastFix` consumer's code path.
Follow `detour-gps-replay`'s A/B protocol. Name the quantity before looking: *how many seconds
after driving off does the camera resume, from a pan taken while stopped* — expected 8 s either
side, and it must not become 0 s or never.

**Honest note on the baseline.** Stage 0's tasks 0b and 0c never landed:
`tools/mocklocation/routes/` and `tools/mocklocation/baseline/` do not exist in the tree, so
"against the stage-0 baseline" as the spec words it is not available. Capture the before-run on
`4cf2977` yourself and A/B against that. Keep the route file — an A against a route you cannot
reproduce is worth nothing.

**`NavPolicy` ships without replay coverage, and that is a gap, not a pass.** Route (iii)
`off-route.gpx` is the one that would exercise arrival and reroute, and it is dropped for want
of a routing server to reroute against (`specs/stage-0-verification-baseline.md:9`). So 2a and
2a′ are verified by ten unit tests pinning both boundaries, the Kotlin compiler, and the desk
drive-to-destination on the car surface. **They are not verified against a recorded drive.**
Say that when reporting the stage; do not write "replayed routes (iii) and (iv)".

**2b is unverified at runtime by construction** — it is not wired in, so there is nothing to
verify. `resolveSpinRound` has tests and no callers, exactly like `CameraAuthority`. Wiring it
needs two devices transmitting to each other (Tier 3).

## Stop-point B

If work halts here, record in [`../DECISION.md`](../DECISION.md):

> Stage 2 complete. Four decisions — arrival/reroute, the group-spin vote round, camera resume
> and the camera authority reducer — now have executable specifications under
> `com.jellemax.detour.map`, and the car has lost its duplicated copy of the first. **Nothing
> about the screen's behaviour changed, and the state layer is still untouched**: `MapScreen.kt`
> keeps every `remember` and every effect. `resolveSpinRound` and `CameraAuthority` have no
> callers on purpose. Stages 3 and 4 remain open. Stage 3 is blocked on stage 0's tasks 2–4 —
> the replay routes and the behavioural baseline — which cannot be captured after the first
> behaviour-touching commit.

Also update `specs/stage-2-pure-extractions.md`'s Status block to `**done**` with the date and
the commit range, the moment the last commit lands, and then run stage 3's preconditions and
record the result in its Status block (`detour-staged-refactor` §6).

## Observations for later stages, deliberately out of scope here

Found while deriving this plan. None of them belongs in a stage-2 commit.

1. **A fourth copy of the off-route bound.** `MapScreen.kt:1422` —
   `offRoute = (navProgress?.offRouteMeters ?: 0.0) > 60` — decides whether the navigation
   bottom bar shows its off-route state. Folding it into `NavPolicy.OFF_ROUTE_METERS` would put
   a UI change inside a policy commit; it is a display threshold, not the reroute decision.
   Stage 3 or 4.
2. **`SpinResultHolder.kt`'s stale comment**, flagged by stage 1's plan (item 1d) and still
   unfixed: it claims `RoutesScreen.kt` needs `SpinResult`/`SpinResultHolder` from outside,
   and the fact audit established they have zero external references. Its own commit, whenever
   someone wants it.
3. **`iosApp/Detour/MapScreen.swift` still hand-writes the vote-round rule** (the spec cites
   `:296-347`, unverified against the current Swift file). That third copy is stage 3's argument
   for promoting `GroupSpinRules` to `:shared`.

## Next

Return to [`../specs/stage-2-pure-extractions.md`](../specs/stage-2-pure-extractions.md) and
follow its **Next stage** footer: run stage 3's preconditions, record the result, then
re-brainstorm and rewrite stage 3's Work items section — it is written at
intent-and-constraint level and says in place that it expects to be rewritten once these
extractions exist.
