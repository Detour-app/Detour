# Stage 4 — State ownership (decision gate)

## Status

| | |
|---|---|
| **Detail level** | Decision gate. **No work items by design** — they are the output of this stage's brainstorm, not its input. |
| **Prerequisite** | [Stage 3](stage-3-hazard-machines-to-shared.md) complete |
| **State** | not started · **optional** |
| **Preconditions captured** | 2026-08-11 |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · prev: [stage 3](stage-3-hazard-machines-to-shared.md) · next: none — this is the last stage |

> **This spec does not tell you what to build.** It tells you what question to answer, what
> evidence to answer it with, and what you are forbidden from doing whichever way it goes.
> Its work items come from running `superpowers:brainstorming` against it once stage 3 is done.

## Preconditions

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# Stage 3 landed.
test -d shared/src/commonMain/kotlin/com/jellemax/detour/drive && echo stage3-done
[ "$(wc -l < $M)" -le 1450 ] && echo size-ok

# Stage 2's experiment is still available and still unwired.
test -f app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt && echo experiment-exists
grep -rc 'CameraAuthority' $M                              # expect 0 — must still be unwired

# The state layer is genuinely still the problem.
grep -c 'remember {\|rememberSaveable\|mutableStateOf' $M  # expect >= 40
grep -c 'camSuspended' $M                                  # expect >= 8

# The baseline and the CI gates are both live.
test -d tools/mocklocation/baseline && echo baseline-present
grep -c 'testDebugUnitTest\|:app:test' .github/workflows/build.yml   # expect >= 1
```

## The question

`MapScreen.kt` still owns its state as ~40 `remember` declarations in one composable, with the
camera's three variables written from nine sites. Two patterns were proposed for fixing that,
and DECISION.md deliberately refused to choose between them this far out:

- **Compose state holders** — plain `class MapCameraState` + `rememberMapCameraState()`. No new
  dependency, cheapest correct split, largest headline shrink. But most holders are untestable,
  and `car/` cannot use any of them.
- **Targeted MVI** — pure reducers for the camera authority and the section tracker only.
  Testable, reusable by `car/`, and stage 2 already built `CameraAuthority` unwired so it can
  be evaluated as code rather than as a proposal.

**They are mutually exclusive for the camera.** `MapCameraState` and `CameraAuthority` would be
competing owners of the same three variables; running both leaves the camera with two sources
of truth, which is strictly worse than doing nothing.

A third answer — **do neither** — is legitimate and is the default. Stop-point C exists to make
"no" defensible.

## Evidence to decide with

Gather these before the brainstorm; they did not exist when this spec was written:

1. **How much state is actually left.** Stage 3 removes the hazard machines' state. If what
   remains is mostly the destination spine plus camera authority, the targeted option covers it
   and the holder option is over-engineering.
2. **Whether `CameraAuthority` proved pleasant.** It has been sitting unwired since stage 2
   with its tests. Read it. Did its tests catch anything during stages 3's replays? Is its
   action vocabulary honest about the nine write sites, or did it flatten them?
3. **Whether `car/` still wants any of it.** After stages 2 and 3, the car's duplication is
   nearly gone. If nothing in the car needs the camera rules, MVI loses its main advantage over
   holders.
4. **Whether the `rememberSaveable` rotation risk is real.** `radiusKm`, `minRadiusKm`,
   `poiKind` and `directionDeg` are `rememberSaveable` today and `MainActivity` declares no
   `configChanges`. A naive holder loses them on rotation. Test the current behaviour on device
   first; the answer changes how much the holder option costs.
5. **The composition-lifetime defect.** `AppRoot` swaps screens with a bare `AnimatedContent`,
   so Map→Hub→Map disposes the whole composition. This is a real, separate defect. Decide
   whether it is in scope here or gets its own ticket — DECISION.md's position is that
   `rememberSaveableStateHolder()` in `AppRoot` fixes one of its five symptoms in about four
   lines and the rest is a separate decision.

## Forbidden, whichever way it goes

- **Never both patterns for the camera.** Named above; it is the one hard rule of this stage.
- **No ViewModel.** Rejected in DECISION.md on evidence, not taste: `androidx.car.app.Session`
  is not a `ViewModelStoreOwner` and the car runs in the same process, so state moved into a VM
  becomes unreachable from the car, the tracking service and the BLE/Wear relays. If this stage
  wants to reopen that, it needs new evidence, not a new preference.
- **No full MVI.** ~430 lines of new declarations, +11% repo LOC, and a pattern no other screen
  in the app uses. The targeted variant is the only one on the table.
- **No collapsing state into one `UiState` object.** `displaySpeedKmh` is written every frame;
  putting it in a shared state object costs a full-tree recomposition per frame on the app's
  only 60fps screen. Three proposals converged on this independently.
- **Do not combine a state-owner change with a lifetime change in one commit.** Two independent
  failure surfaces, one revert.
- **Do not resolve the `lastGestureMs` asymmetry inside another commit.** The spin path parks
  the camera without stamping it; every other park stamps both. Whichever way that is decided,
  it is decided alone, verified on replay route (iv), and only then does anything get wired.

## What the brainstorm must produce

- A decision, in writing, with its reasoning: holders, targeted reducer, or neither.
- If "neither": the sentence recorded in DECISION.md explaining what remains unaddressed, so
  the chain closes honestly rather than trailing off.
- If either of the other two: work items sized to one subagent and one commit, with the
  camera's nine write sites enumerated and each assigned to exactly one owner; a replay plan
  against the stage-0 baseline for routes (i) and (iv); and an explicit statement of what
  happens to `CameraAuthority` if it loses — deleted, not left to rot unwired.

## Done criteria

There is no fixed done criterion for this stage, because it may correctly produce no code.
It is done when the decision is written into DECISION.md with its reasoning, and either the
work is complete or the reason for declining it is on the record.

## Next stage

None. This is the end of the chain.

When this stage closes — by completion or by a recorded decision not to proceed — update
DECISION.md's status with the final state of `MapScreen.kt` and a short note on what the chain
did and did not achieve. The investigation reports in `../` stay as they are: they are the
record of what was believed at the start, and their value now is that they can be compared
against what actually happened.
