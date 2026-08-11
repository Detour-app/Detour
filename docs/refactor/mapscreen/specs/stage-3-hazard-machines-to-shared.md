# Stage 3 — Road-hazard machines into the shared core

## Status

| | |
|---|---|
| **Detail level** | Intent + constraints. **The Work items section requires a rewrite before use** — see below. |
| **Prerequisite** | [Stage 2](stage-2-pure-extractions.md) complete |
| **State** | not started |
| **Preconditions captured** | 2026-08-11 |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · prev: [stage 2](stage-2-pure-extractions.md) · next: [stage 4](stage-4-state-ownership.md) |

> **Scheduled rewrite.** This spec fixes the goal, the destination and the constraints, which
> are settled. It deliberately does **not** fix the internals, because how these machines
> should be shaped depends on what stage 2's extractions teach us about pulling decisions out
> of this screen. Rewrite the Work items section via `superpowers:brainstorming` after stage 2
> lands, then write the plan. This marker is a scheduled decision, not an unfinished section.

## Preconditions

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# Stage 2 landed.
test -d app/src/main/java/com/jellemax/detour/map && echo stage2-done
test -f app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt && echo tests-exist

# The three machines are still inline, still stateful, still keeping their state in
# coroutine-local vars that no test can reach.
grep -c 'var warnedAt' $M                        # expect 1  (camera-warn latch)
grep -c 'var active: SpeedCameras.Section' $M    # expect 1  (section tracker)
grep -c 'speedLimitMisses' $M                    # expect >= 2 (ambient-limit hysteresis)

# The core already holds the stateless half of all three, unused by iOS.
grep -c 'fun speedLimitWays' shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt   # expect 1
grep -c 'fun snapSpeedLimitKmh' shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt # expect 1
grep -c 'fun near' shared/src/commonMain/kotlin/com/jellemax/detour/data/SpeedCameras.kt              # expect 1

# commonMain's constraints are unchanged.
grep -rc 'Dispatchers\.' shared/src/commonMain/kotlin/ | grep -v ':0' | wc -l   # expect 0
grep -c 'fun nowMs' shared/src/commonMain/kotlin/com/jellemax/detour/data/Angles.kt  # expect 1
grep -c '^expect ' shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt      # expect 4

# The baseline recordings from stage 0 exist.
test -d tools/mocklocation/baseline && echo baseline-present
```

## Why this stage

The surface-independence audit found that **parity is decided by statefulness, not domain
relevance**. Every feature that reached iOS has its logic in `shared/`. Every feature that did
not is welded into a composable or an Android Service.

The three road-hazard features are the proof: `RoadRoulette.speedLimitWays`,
`RoadRoulette.snapSpeedLimitKmh` and `SpeedCameras.near` **already sit in commonMain, unused**.
Only the stateful wrapper around them — the prefetch throttle, the 3-miss clear hysteresis,
the one-chime-per-camera latch, the section entry/exit tracker — never left `MapScreen.kt`.
That wrapper is roughly 190 lines holding five coroutine-local `var`s that no test can reach.

Moving it is what gives iOS three features it does not have, gives the car a 3-line addition
instead of a 63-line copy, and puts the code that produces the field bugs under the one CI
gate that already runs (`ios.yml:64-68` runs `:shared` tests on both JVM and Kotlin/Native).

## Scope

Three stateful machines move into `shared/src/commonMain/.../drive/`, with tests in
`commonTest`:

1. **`SectionAverageTracker`** — trajectcontrole entry/exit gating and running average.
   Includes `sectionExitGate`, which stage 1 moved to `MapCameraTuning.kt`.
2. **`CameraWarner`** — the one-chime-per-camera latch and its re-arm rule.
3. **`SpeedLimitTracker`** — prefetch throttling, local snapping, and the 3-miss clear
   hysteresis.

Then the car's copies are deleted and pointed at the core, one commit behind each extraction.

## Constraints — these are settled and not up for rediscovery

- **Destination is `:shared` commonMain.** Not `app/…/map/`. It is the only destination
  reachable by the car *and* iOS, and the only test source set CI gates today. This was
  decided against the alternative in DECISION.md; do not relitigate it in the plan.
- **This is a rewrite, not a move.** commonMain has no `Dispatchers.*` at all, so I/O must be
  handed in by the caller. It *does* have a wall clock — `nowMs()` at `Angles.kt:15`, backed by
  `kotlinx.datetime.Clock` — so `System.currentTimeMillis()` has a direct replacement. Inject
  time anyway: these machines are path-dependent over timestamps, and a machine that reads the
  clock itself cannot be tested deterministically. Use `nowMs()` at the call site and pass the
  value in. That constraint is what makes the results testable, so it is a feature of this
  stage, not a tax on it.
- **Do not add an `expect`.** `Platform.kt` has four, and `CONTRIBUTING.md:23-32` states that
  wanting a fifth is the signal to push the dependency in from the platform instead. If a
  machine seems to need one, the shape is wrong.
- **Extract from the car's copy, not the phone's, wherever they have diverged.** The car
  version is strictly better on the prefetch path: the fetch is already off the fix collector
  and there is a re-entry guard. Extracting the phone version would port a stall into shared
  code. (Stage 0d fixes the phone side, so by the time this runs they should agree — verify
  that they do before choosing a source.)
- **Shape follows `CircleEvents.kt`**, the in-repo precedent: decision and wording in the
  core, delivery per platform, called from both sides. Read it before designing anything.
- **No service owns a `CoroutineScope`.** Callers drive them. A machine that starts its own
  polling will keep an Overpass request alive with the screen off, and that failure is only
  detectable in the field, days later.

## Ordering — strictly sequential

Unlike stages 1 and 2, **this stage must not be parallelised.** Each machine lands, is
replayed against the stage-0 baseline, and is confirmed before the next begins. Two machines
in flight at once makes the A/B replay uninterpretable, and the replay is the only real
verification this code has.

Order, cheapest and most isolated first:

1. `SectionAverageTracker` — one input, two outputs, the clearest boundary.
2. `CameraWarner` — latch semantics.
3. `SpeedLimitTracker` — the most entangled, and the one with the known hysteresis trap.

For each: **characterisation tests against the current behaviour first, then the move.**
Constants copied byte-for-byte. Replay route (i) for machines 1 and 2, route (ii) for 3.

## Out of scope

- Anything in `MapScreen.kt`'s state layer. Stage 4 owns that.
- The convoy protocol, trip auto-detection, the nav vocabulary and the voice policy. All four
  are duplicated across Kotlin and Swift and all four qualify under the 0g rule — but they are
  a separate programme, not this stage. Note them; do not start them.
- Adding `:shared` as a dependency of `wear/`. It is currently absent and should stay absent:
  it would pull ktor, okio, serialization and datetime into a 185-line watch APK to dedupe a
  16-line `when`.

## Work items

> **Rewrite this section before use.** After stage 2 lands, run
> `superpowers:brainstorming` for this stage with its extractions in hand, and replace this
> section with concrete work items. The constraints above are inputs to that conversation and
> are not to be revisited; the machine shapes, signatures and commit boundaries are its output.

What the rewrite must produce, per machine: the signature, how time is injected, who drives it
on each surface, the characterisation tests written before the move, the replay route and the
specific quantity being compared against baseline, and the follow-up commit that deletes the
car's copy.

## Done criteria and verification

- [ ] Three machines in `shared/src/commonMain/.../drive/`, with `commonTest` tests.
- [ ] `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` green.
- [ ] `Platform.kt` still has exactly four `expect` declarations.
- [ ] No `Dispatchers.*` anywhere in commonMain, and no machine reads the clock itself.
- [ ] Car copies deleted, each in its own commit, one behind its extraction.
- [ ] Each machine replayed against the stage-0 baseline with the comparison recorded.
- [ ] `MapScreen.kt` roughly 1300-1400 lines.

Verification tier: **full manual checklist plus per-machine replay A/B.** This is the stage
the baseline was recorded for.

## Stop-point C — the default stop

**Stop here unless there is a specific reason not to.** The cost-to-value curve turns sharply
down after this point.

At this stop-point: the code that produces field bugs has tests; iOS can gain three features
it has never had; the car's substantive duplication is down to one or two items; the file is
under 1400 lines; and every remaining option is still open.

If halting here, record in DECISION.md that stage 4 was considered and deferred, and why.

## Risks

- **The hysteresis trap, again.** Stage 0d already touched the fix stream feeding the ambient
  limit. Machine 3 touches it a second time. Compare fix-counts-to-sign-clear against
  baseline, not screenshots.
- **Path dependence.** These machines are not pure; the same fix sequence in a different order
  gives a different result. Characterisation tests capture behaviour, not correctness — a test
  that passes proves you preserved what was there, including any bug.
- **Scope pull.** Once three machines are in the core, the convoy protocol and trip detection
  will look easy. They are each larger than all of stage 3. They are out of scope by name.
- **One-way door.** Code in commonMain is consumed by iOS; backing it out later is a
  cross-language revert. This is the first genuinely hard-to-reverse stage in the chain, which
  is another reason it is sequential.

## Next stage

→ [`stage-4-state-ownership.md`](stage-4-state-ownership.md)

**Before writing stage 4's plan:**

1. Run stage 4's **Preconditions** block.
2. Stage 4 is a **decision gate**, not a work plan. It has no work items by design: the choice
   it makes — Compose state holders versus a targeted reducer — is not decidable until this
   stage has finished, and stage 2's unwired `CameraAuthority` exists precisely so that the
   choice can be made against real code rather than two proposals.
3. Invoke `superpowers:brainstorming` against
   [`stage-4-state-ownership.md`](stage-4-state-ownership.md) to make the decision and produce
   its work items, then `superpowers:writing-plans`, then
   `superpowers:subagent-driven-development`.

Answering "should we even do stage 4" is a valid outcome of that brainstorm, and stop-point C
above is what makes "no" a defensible answer.
