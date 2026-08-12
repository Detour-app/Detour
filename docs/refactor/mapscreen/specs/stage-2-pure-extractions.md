# Stage 2 — Pure-logic extractions

## Status

| | |
|---|---|
| **Detail level** | Function level — named extractions, no line ranges (stage 1 invalidates them) |
| **Prerequisite** | [Stage 1](stage-1-mechanical-split.md) — **complete** 2026-08-12, `MapScreen.kt` at 1549 lines |
| **State** | **done** 2026-08-12 — five commits (`2452dfc`…`d451fe5`), 32 new tests (suite 18 → 50, all passing). Two carve-outs recorded in [`../DECISION.md`](../DECISION.md): `GroupSpinRules`' call site is unchanged (needs two devices) and no GPS replay was run (stage 0's routes and baseline were deferred, so there is no recorded before). Plan: [`../plans/2026-08-12-stage-2-pure-extractions.md`](../plans/2026-08-12-stage-2-pure-extractions.md) |
| **Preconditions captured** | 2026-08-11, describing the state stage 1 was expected to leave. Re-checked 2026-08-12 against the real post-stage-1 file: the size range holds (1549 is inside 1500–1700), and the `leadingSpinIndex` count was corrected from 1 to 2 — it has always had two call sites, so the original figure would have tripped a false staleness alarm on its first run. |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · prev: [stage 1](stage-1-mechanical-split.md) · next: [stage 3](stage-3-hazard-machines-to-shared.md) |

## Preconditions

Run before writing this stage's plan. Any mismatch means the spec is stale — re-brainstorm,
do not adapt.

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# Stage 1 landed.
test -f app/src/main/java/com/jellemax/detour/ui/SpinShare.kt && echo split-done
[ "$(wc -l < $M)" -ge 1500 ] && [ "$(wc -l < $M)" -le 1700 ] && echo size-ok

# The three policies this stage extracts are still inline and still shaped as described.
grep -c 'progress.remainingMeters < 40' $M                    # expect 1  (arrival test)
grep -c 'progress.offRouteMeters > 60' $M                     # expect 1  (reroute test)
grep -c 'now - lastRerouteMs > 15_000' $M                     # expect 1  (reroute cooldown)
grep -c 'CAM_RESUME_SPEED_MPS' $M                             # expect 1  (resume rule)
grep -c 'leadingSpinIndex' $M                                 # expect 2  (two call sites)

# The car's copy of the arrival/reroute policy is still there to be deduplicated.
grep -c 'Same arrival/reroute policy' app/src/main/java/com/jellemax/detour/car/NavScreen.kt  # expect 1

# CI actually runs app tests now (stage 0a).
grep -c 'testDebugUnitTest\|:app:test' .github/workflows/build.yml   # expect >= 1
```

## Why this stage

Three decisions in this screen are pure functions wearing coroutine clothing: when you have
arrived, when to reroute, when a parked camera resumes following, and how a convoy's vote
round resolves. Two of them carry written correctness arguments in comments — the group-spin
rule has sixteen lines of prose at what was `MapScreen.kt:842-857` — and **none of them has a
test**. One is duplicated verbatim in `car/NavScreen.kt`, whose own comment says so.

Extracting them costs nothing structurally: they take values in and return a decision. What
they gain is an executable specification, protected by the CI gate stage 0 turned on.

This is also the cheapest possible proof that the later stages' approach works, because the
extracted units can be tested and merged **without being wired in**.

## Scope

Extract four pure decision units into `app/src/main/java/com/jellemax/detour/map/`, each with
JUnit4 tests in `app/src/test/java/com/jellemax/detour/map/`, and delete the car's duplicate
of the one it shares.

Package choice: `com.jellemax.detour.map`, not `ui.map`. These are not UI, and stage 3 may
promote some of them to `:shared` — a non-`ui` package makes that a move rather than a
rename. Stage 1's flat-`ui` rule applied to composables; it does not apply here.

## Out of scope

- **`:shared`/commonMain.** These four need `System.currentTimeMillis()` and are consumed
  only by Android surfaces today. Stage 3 decides what earns the core, under the rule from
  0g: *a policy earns the core when it is written more than once*. `NavPolicy` will qualify;
  it is deliberately left in `app/` here so that stage 3's move is a single reviewable step.
- **The GPS state machines** — ambient speed limit, camera warning, section average. Those
  are stateful, path-dependent, and owned by stage 3.
- **Wiring `CameraAuthority`.** See work item 2d.
- **Any change to state ownership in `MapScreen.kt`.** Stage 4 owns that.

## Work items

Four extractions, mutually independent, **parallelisable**. Each is one commit; each lands
with its tests in the same commit, because a pure function merged without its test has gained
nothing.

### 2a — `NavPolicy` · *parallel*

Extract the arrival and reroute decisions from `MapScreen.kt`'s navigating effect and from
`car/NavScreen.kt`, which implements the same policy with a comment admitting it.

Shape: a pure function taking the current `NavEngine.Progress`, whether a destination exists,
the rerouting flag, the last-reroute timestamp and now-in-millis; returning a sealed decision
(`Continue` / `Arrived` / `Reroute`). No I/O, no coroutines, no Android.

Then delete the car's copy and call the shared function — **in a separate commit** from the
extraction, so a car regression and a phone regression never share a bisect.

Tests to write, at minimum: arrival requires both the distance and the off-route bound;
arrival never fires for a round trip with a null destination; reroute respects the cooldown;
reroute does not fire while one is already in flight; a fix exactly on a boundary resolves
one way and the test says which.

### 2b — `GroupSpinRules` · *parallel*

Extract `leadingSpinIndex` (already moved to `SpinShare.kt` by stage 1) together with the
vote-round resolution rule from what was `MapScreen.kt:842-870` — the logic deciding when a
round is complete and who commits it.

This is the highest-value item in the stage. Its correctness argument exists only as a
comment, and the failure it guards against — a convoy splitting across two destinations — is
the exact thing the feature exists to prevent. iOS has a third, hand-written copy at
`iosApp/Detour/MapScreen.swift:296-347`, which is why stage 3 will look at promoting this to
the core.

**Move the sixteen-line rationale comment with the code.** It is the specification.

Tests: ties go to the lowest index; an empty vote map yields index 0; a one-candidate offer
commits regardless of votes; the round completes only when every expected voter has voted;
a peer pruned mid-round does not complete the round early.

### 2c — `FollowCamera.shouldResume` · *parallel*

Extract the camera-resume rule: above `CAM_RESUME_SPEED_MPS`, and more than
`CAM_RESUME_QUIET_MS` since the last gesture, a parked camera resumes — unless a spin result
or a convoy offer is on screen.

Tests: below the speed threshold never resumes; inside the quiet window never resumes; a
pending spin offer blocks resume even when both thresholds are met; the boundary values.

### 2d — `CameraAuthority`, unwired · *parallel*

Write the reducer for the camera's follow/park/resume state machine — the three variables
that today have nine write sites — as a pure `reduce(state, action) -> state` with its tests.
**Do not wire it into `MapScreen.kt`.**

This is deliberate. It compiles, its tests pass, the app is byte-identical, and it is the
cheapest possible experiment: stage 4 can adopt it, discard it, or take the Compose
state-holder route instead, with real code to compare rather than two proposals.

**Known asymmetry to decide, and to document in the tests rather than silently resolve:** the
spin path parks the camera without stamping `lastGestureMs`, while every other park stamps
both. Two proposals' sketches quietly unified these. Encode the *current* behaviour, add a
failing-or-skipped test naming the asymmetry, and leave the decision to whoever wires it.

## Done criteria and verification

- [ ] Four units under `com.jellemax.detour.map`, each with tests under the matching test
      package.
- [ ] `./gradlew :app:testDebugUnitTest` green, and CI runs it.
- [ ] `car/NavScreen.kt` calls `NavPolicy` and its duplicate is gone — in its own commit.
- [ ] `CameraAuthority` has no callers.
- [ ] `MapScreen.kt` behaviour unchanged: the extracted call sites pass the same values and
      act on the same results.

Verification tier: **desk checklist + one replay pass** on routes (iii) and (iv) against the
stage-0 baseline. 2a and 2c change the code path that decides arrival and camera resume;
everything else is inert.

## Stop-point B

If work halts here: roughly 170 lines of previously undocumented decisions now have
executable specifications, the car has lost a duplicated policy, and nothing about the
screen's behaviour has changed. Record in DECISION.md that stages 3 and 4 remain open.

## Risks

- **2a's boundary conditions are the arrival test.** Getting `<` versus `<=` wrong means
  either arriving early or never arriving. The tests must pin the boundary explicitly, and
  the replay on route (iii) is not optional.
- **2b touching the convoy path.** It needs two devices to verify properly. If two devices
  are not available, extract and test but do not change the call site until they are.
- **Package drift.** `com.jellemax.detour.map` is a new package; make sure it does not
  accumulate anything that is really UI.

## Next stage

→ [`stage-3-hazard-machines-to-shared.md`](stage-3-hazard-machines-to-shared.md)

**Before writing stage 3's plan:**

1. Run stage 3's **Preconditions** block. Record the result in its Status table.
2. **Stage 3's spec is written at intent-and-constraint level and expects to be rewritten.**
   Its "Work items" section is marked as requiring a rewrite once this stage's extractions
   exist — because what stage 2 learns about extracting decisions from this screen is exactly
   what stage 3 needs in order to be specified properly. Treat the rewrite as scheduled, not
   as a failure.
3. Invoke `superpowers:brainstorming` for stage 3 with this stage's outcome in hand, rewrite
   the spec, then `superpowers:writing-plans`, then
   `superpowers:subagent-driven-development`.

Stage 3 is the first stage that moves stateful, path-dependent GPS logic. It is strictly
sequential, it is where the replay baseline earns its keep, and it is the default place to
stop.
