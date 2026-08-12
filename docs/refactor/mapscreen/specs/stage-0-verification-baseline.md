# Stage 0 — Verification baseline

## Status

| | |
|---|---|
| **Detail level** | Full — file-level work items |
| **Prerequisite** | None. This is the first stage. |
| **State** | **partially done** — tasks 1, 5, 6, 7 landed (CI test gate, error snackbar, iOS maneuver arrows, the CONTRIBUTING shared-core rule). Tasks 2–4 deferred: blocked on device route recordings — two of the four canonical routes exist, route (i) needs an unbroken trajectcontrole run end to end, and route (iii) is dropped for want of a routing server to reroute against. |
| **Preconditions captured** | 2026-08-11 |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · next: [stage 1](stage-1-mechanical-split.md) |

## Preconditions

Run before writing this stage's plan. Any mismatch means the spec is stale — re-brainstorm,
do not adapt.

**Five of the eight assertions below are inverted on purpose.** Every other stage's
preconditions assert that the code a stage is *about* to touch is still shaped the way the
stage expects, so a FAIL means the code moved out from under the spec. This stage is
different: two of its own work items (0a, 0d, 0f) are to close gaps and fix live defects, so
their preconditions assert the *pre-fix* state — no CI test step yet, three defects still
present and unfixed — because that was the only way, before any work existed, to confirm there
was work to do. That is what the "The three defects are still present and unfixed" comment and
the `gradlew`/`testDebugUnitTest` pair above it are checking.

Once a work item lands, its assertion **fails**, and that failure is the stage succeeding, not
drifting. 0a and 0f have both landed (see Status above), so the `testDebugUnitTest` line and
the iOS `arrow.uturn.left` line now correctly FAIL; 0d is deferred, so the two Overpass
`withContext` lines still correctly PASS. Do not read a FAIL on any of those five lines as
staleness — read the Status row instead to see which work item caused it. The final assertion
(`wc -l` on `MapScreen.kt`) is an ordinary one and is not part of this inversion: it now fails
for an unrelated reason, because stage 1 has since landed and mechanically split that file.

```sh
# CI runs no Kotlin test: only assemble/bundle appear on the gradlew line.
grep -c 'gradlew' .github/workflows/build.yml                         # expect 1
grep -c 'testDebugUnitTest\|:app:test' .github/workflows/build.yml     # expect 0

# The shared module's tests DO run, path-gated on shared/** and iosApp/**.
grep -c 'gradlew :shared:testDebugUnitTest' .github/workflows/ios.yml  # expect 1

# The replay harness exists as its own Gradle build.
test -f tools/mocklocation/settings.gradle.kts && echo present         # expect: present

# The three defects are still present and unfixed.
grep -c 'withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays' \
  app/src/main/java/com/jellemax/detour/ui/MapScreen.kt                # expect 1
grep -c 'withContext(Dispatchers.IO) { SpeedCameras.near' \
  app/src/main/java/com/jellemax/detour/ui/MapScreen.kt                # expect 1
grep -c 'case -3: return "arrow.uturn.left"' iosApp/Detour/NavScreen.swift  # expect 1

# MapScreen.kt untouched.
wc -l < app/src/main/java/com/jellemax/detour/ui/MapScreen.kt          # expect 3193
```

## Why this stage

Every later stage is verified by one of three things: the compiler, a unit test, or a GPS
replay against recorded behaviour. Today only the compiler works — CI runs no Kotlin test
(`build.yml:123`), and the replay harness at `tools/mocklocation/` exists but has never been
used for regression capture. Stage 0 turns both on **before** the first line of MapScreen
moves, because the "before" recording is only capturable while the code is still original.

The three defect fixes are bundled here for the same reason: each one changes observable
behaviour, and mixing a behaviour fix into a refactor commit destroys the only signal a
bisect would have.

## Scope

- Wire Kotlin tests into CI.
- Stand up the replay harness and record the behavioural baseline.
- Fix three verified, independent defects, each in its own commit.
- Record the standing architectural rule in `CONTRIBUTING.md` so later stages can cite it.

## Out of scope

- **Any change to `MapScreen.kt`'s structure.** The two Overpass fixes are surgical
  behaviour fixes inside existing effects, not a step toward the split. Stage 1 owns structure.
- **Extracting anything into `:shared`.** Stage 3 owns that.
- **Writing tests for MapScreen logic.** There is nothing testable in it yet; stage 2 creates
  the first testable units.

## Work items

### 0a — Run Kotlin tests in CI · *parallel*

Add a test step to `.github/workflows/build.yml` running `:app:testDebugUnitTest` and
`:shared:allTests` (or `:shared:testDebugUnitTest`, matching what `ios.yml:65` already uses).
Place it before the assemble step so a failing test blocks the build.

Note in the commit message that `app/src/test` has six test files that have never run in CI;
if any of them fail on first execution, fix or quarantine them **in a separate commit** —
their failure is pre-existing and must not be attributed to this change.

*Commit:* `ci: run Kotlin unit tests on every build`

### 0b — Stand up the GPS replay harness · *parallel with 0a*

`tools/mocklocation/MockService.kt` replays a route file as mock fused-location fixes with
real speed and bearing at `accuracy = 4f`, which clears every accuracy gate in `MapScreen.kt`
(`:556`, `:706`, `:1028`, `:1189`, `:1239`, `:1641`). The setup recipe is at
`docs/PLAY_LOCATION_DECLARATION.md:159-178`.

Check in four canonical routes under `tools/mocklocation/routes/` with a short README
explaining how to run each:

| Route | Must contain | Exercises |
|---|---|---|
| (i) `trajectcontrole.gpx` | a real average-speed section, driven end to end | section entry/exit, running average, camera chime |
| (ii) `urban-limits.gpx` | several posted-limit changes, cross streets, a frontage road | ambient limit snap, the 3-miss clear hysteresis, prefetch refresh |
| (iii) `off-route.gpx` | a deliberate deviation past 60 m, then rejoin | reroute trigger, cooldown, driven-fraction fade |
| (iv) `stop-start.gpx` | traffic-light stops, a pan mid-drive, then driving off | camera park/resume, speed-HUD easing, bearing hold below 2 m/s |

*Commit:* `test(tools): check in canonical replay routes for GPS regression testing`

### 0c — Record the behavioural baseline · *sequential, after 0b*

Run all four routes on the current build and record what happens: screen capture plus a
logcat trace per route, stored under `tools/mocklocation/baseline/` with the commit SHA they
were captured at.

**This is the item that cannot be done later.** Every A/B comparison in stages 2–4 is against
this recording.

*Commit:* `test(tools): record pre-refactor behavioural baseline`

### 0d — Fix the Overpass stall · *sequential, after 0c*

`MapScreen.kt:1037` and `:1075` each `await` an Overpass round-trip **inside** a
`TripTrackingService.lastFix` collector. A `StateFlow` conflates while a collector is
suspended, so every fix arriving during a slow request is dropped — freezing the camera, the
speed HUD and the turn card behind a network call.

The car surface hit this twice and fixed it twice, with written rationale both times:
`car/NavScreen.kt:365-377` and `car/SpinScreen.kt:254-264`. **Port the car's pattern** (a
`Job` plus an `isActive` guard so a fetch runs alongside the collector rather than inside it).
Do not invent a third approach.

**Known trap, and the reason this item is sequential:** the 3-miss hysteresis at
`MapScreen.kt:1050` was only ever tuned against a fix stream that *had* these drops. Removing
them changes how fast the speed-limit sign clears. Replay route (ii) and **count fixes to the
sign clearing**, against the 0c baseline. If it changed, retune the `3` in this same commit or
revert — do not leave it to be discovered in the field.

*Commit:* `fix(map): keep GPS fixes flowing while Overpass prefetch is in flight`

### 0e — Make error messages reachable · *parallel with 0d*

`MapScreen.kt:460` declares `error`, written from **12 sites** and read from exactly **one**
(`:1771`, inside `SpinSheet`). The sheet is collapsed by default (`:526`), so permission
denials, "Waiting for your location…", and navigation failures are invisible in the resting
state of the screen.

Surface it where it is visible regardless of card state. A snackbar on the existing
`Scaffold` is the smallest change that works; keep the `SpinSheet` copy or remove it, but
say which in the commit message.

*Commit:* `fix(map): surface errors when the spin sheet is collapsed`

### 0f — Fix the iOS maneuver arrows · *parallel; independent of everything else*

`iosApp/Detour/NavScreen.swift:223-236` renders six GraphHopper sign codes wrongly: `±3`
(sharp turns) as U-turns, and `-98`/`-8`/`8` (U-turns) plus `±7` (keep left/right) as
straight ahead. The correct table exists three times over —
`app/.../ui/Navigation.kt:57-71`, `wear/.../MainActivity.kt:53-67`,
`app/.../car/NavScreen.kt:575-593`.

Reported upstream as **maxke24/Detour#20**; the suggested Swift table is in that issue.
Also widen the KDoc at `RoutingServer.kt:35` to the full code list — that comment's
`-3..3` range is what the wrong table was derived from.

Unrelated to the MapScreen split. Included because it is a live user-facing defect found by
the same investigation, and because stage 3 will later move this table into the core, which
should not be done while it is wrong.

*Commit:* `fix(ios): correct maneuver arrows for sharp turns, U-turns and forks`

### 0g — Record the standing rule · *parallel*

`CONTRIBUTING.md:23-32` already states the architecture — *"the core is handed things, it
never reaches for them"*, and *"New logic goes in `shared/` unless it genuinely cannot"*. Add
the operational test that later stages apply, so it is citable rather than re-argued:

> A policy earns the core when it is written more than once.
> A port earns an interface when it has more than one implementation.

*Commit:* `docs(contributing): state when logic earns the shared core`

## Done criteria and verification

- [ ] A red unit test fails the CI build.
- [ ] All four replay routes run end to end on a device or emulator, and the baseline is
      committed with its SHA.
- [ ] Route (ii) replays identically to baseline after 0d, or the hysteresis retune is in the
      same commit with its reasoning.
- [ ] A denied location permission shows a visible message with the spin sheet collapsed.
- [ ] iOS renders a sharp left as a left turn and a U-turn as a U-turn.
- [ ] `wc -l < app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` is still within a few
      lines of 3193 — this stage barely touches it.

Verification tier: **full manual checklist** from
[`../12-eval-risk-sequencing.md`](../12-eval-risk-sequencing.md), once, at the end of the
stage. It is the only stage where the checklist itself is being validated as fit for purpose,
so note anything it misses.

## Risks

- **0d is the highest-risk item in the whole chain relative to its size.** It looks like a
  three-line change and it retunes a hysteresis constant nobody remembers choosing.
- **0c is unrepeatable.** If the baseline is recorded after any behaviour-touching commit, it
  is worthless and nobody will notice.
- 0a may expose pre-existing test failures. That is information, not a setback; keep the fix
  in its own commit.

## Next stage

→ [`stage-1-mechanical-split.md`](stage-1-mechanical-split.md)

**Before writing stage 1's plan:**

1. Run stage 1's **Preconditions** block. Record the result in its Status table.
2. If any assertion fails, invoke `superpowers:brainstorming` for stage 1 and rewrite that
   spec against the code as it now stands. Do not adapt the plan to the drift.
3. If they pass, invoke `superpowers:writing-plans` against
   [`stage-1-mechanical-split.md`](stage-1-mechanical-split.md) to produce its implementation
   plan, then execute the plan with `superpowers:subagent-driven-development`.

Stage 1 is the last stage that carries no behavioural risk at all. It is also the stage whose
line numbers this one must not have disturbed — if 0d or 0e moved anything in `MapScreen.kt`,
stage 1's line ranges need re-deriving, and its preconditions are written to catch exactly that.
