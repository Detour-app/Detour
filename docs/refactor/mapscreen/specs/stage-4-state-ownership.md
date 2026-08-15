# Stage 4 — State ownership (decision gate)

## Status

| | |
|---|---|
| **Detail level** | Decision gate. **No work items by design** — they are the output of this stage's brainstorm, not its input. |
| **Prerequisite** | [Stage 3](stage-3-hazard-machines-to-shared.md) complete |
| **State** | **done** 2026-08-13 — `CameraAuthority` wired (`3bae9e1`, `81f44e0`, `07a2e52`). The three `remember`ed camera vars became one `CameraAuthority.State` and ten write sites became reducer dispatches; the reducer itself was not modified. Desk-checked on device: all six transitions pass, no crashes. The spin-park asymmetry is preserved and **measured** — a spin park released at t+3740 ms against a drag park held 8.16–8.63 s by `CAM_RESUME_QUIET_MS`, which symmetrising would have made impossible. Verification: 166 unit tests, compile, R8, three gate scripts, and the desk check; **no GPS replay**, by an explicit decision to trade behaviour verification for completion. |
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
6. **`maxke24/Detour#21` — and whether it goes before or after this stage.** The issue is
   *"Map is choppy while driving"*, and its three causes are all in the state this stage would
   take ownership of:
   - **The position marker updates at the GPS rate, not per frame.** The overlay is re-pushed
     from a `LaunchedEffect` keyed on `myLocation` among eight other keys
     (`MapScreen.kt:583-584`), so the dot is re-placed ~1 Hz at the raw fix while the camera
     eases toward that same point. The comment at `:604-605` states the intent — *"the eased
     camera glides the map under it"* — and the issue's argument is that this only holds if the
     camera is *at* the fix, which a first-order lag never is.
   - **The epsilon gate suppresses camera pushes at city speeds.** `:1030-1034` skips
     `setCamera` unless this frame moved more than `CAM_POS_EPS_DEG = 2e-6`
     (`MapCameraTuning.kt:39`) — ~0.22 m of latitude, ~0.14 m of longitude at 51°N. Per-frame
     displacement at 60 fps is `v·dt`, so the issue puts the stall threshold at roughly
     35–50 km/h: the same optimisation that correctly keeps a *stationary* map idle makes a
     *slowly moving* one step. Its suggested fix is to make the test rate-based — skip when the
     target is not moving, rather than when this frame's step was small.
   - **No prediction.** The ease target is the fix as received (`:947-948`), so the camera sits a
     steady-state `v·τ` behind it with `CAM_POS_TAU = 0.35`, and the issue's proposed remedy is
     dead reckoning off `Fix`'s existing `speedMps`, `bearingDeg` and `timeMs`.

   **This collides with stage 4 head-on.** Both touch `camTarget`, `camTargetBearing`, the
   `applied*` quartet, the `withFrameNanos` loop and `myLocation` — the exact variables an owner
   change would move. Whoever goes second rebases their work onto a rewritten version of the same
   nine write sites, and if both are in flight the A/B replay cannot attribute a difference to
   either. **One of them waits, and this stage's brainstorm decides which** (its `## Notes` also
   flags that `car/CarMapRenderer.kt` carries the same loop, so either order has a car-side
   follow-up). The register's entry 2 is the same issue seen from the fetch side — it is
   `#21`'s filed cause, scheduled as stage 0d — and entry 3 is the warning not to fold the two
   `dt` clamps together while you are in there.

   Evidence to bring to the brainstorm: whether `#21` has been fixed, started, or is still only
   filed. If it is untouched, "fix `#21` first and let it decide the camera's shape" is a
   legitimate answer — and it may be a better one, because `#21` is a defect a user reported and
   stage 4 is a structural preference.

## Evidence gathered 2026-08-13 — and the collision is narrower than this spec claimed

Measured against `7e8a7f2`, after stage 3's machines 1 and 2 landed:

| Question the spec asks | Answer |
|---|---|
| How much state is left | `MapScreen.kt` 1656 lines, 59 `remember*`, 34 `LaunchedEffect`, **10** `camSuspended` writes |
| Is `CameraAuthority` still available | Yes — zero callers, 11 passing tests, untouched since stage 2 |
| Does `car/` want it | Unresolved; `car/CarMapRenderer.kt` carries its own loop |
| Is the `rememberSaveable` rotation risk real | **Yes.** 6 uses in `MapScreen.kt`, and `AndroidManifest.xml` declares **no** `configChanges`, so rotation destroys the activity and those six are load-bearing. A naive Compose holder loses them. |
| Is #21 fixed, started, or only filed | **Open, zero comments** — untouched |

**The head-on collision this spec describes does not apply to wiring `CameraAuthority`.** The
spec says stage 4 and #21 "both touch `camTarget`, `camTargetBearing`, the `applied*` quartet,
the `withFrameNanos` loop and `myLocation`". That is true of a stage 4 that took over the whole
camera. It is not true of the reducer stage 2 actually built:

- `CameraAuthority.State` holds `followMe`, `camSuspended`, `lastGestureMs` and a derived
  `following` — the *authority*, meaning whether to follow.
- #21 is about the *easing* — the marker updating at the GPS rate, the epsilon gate stalling at
  city speeds, and the absence of dead reckoning. Those live in `camTarget`, the `applied*`
  quartet and the frame loop.

Verified disjoint: the `withFrameNanos` loop body contains **no reference** to `followMe`,
`camSuspended` or `lastGestureMs`. It reads only the derived `cameraActive` (`MapScreen.kt:277`)
as an effect key, and all ten `camSuspended` writes are authority transitions outside the loop.

**Consequence for the decision.** Wiring `CameraAuthority` and fixing #21 are separable, so
"one of them waits" is not required. The `rememberSaveable` finding also settles the choice
between the two candidate patterns on cost rather than taste: with no `configChanges`, the
Compose-holder option has to reimplement six saveable values correctly or lose them on
rotation, while the reducer touches none of them.

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
- **An order between this stage and `maxke24/Detour#21`**, with the loser's dependency recorded
  where its owner will see it. Not "we should coordinate" — which of the two lands first.
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
