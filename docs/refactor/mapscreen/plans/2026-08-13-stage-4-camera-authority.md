# Stage 4 — Wire CameraAuthority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the map camera's follow/park/resume state a single owner, by wiring the `CameraAuthority` reducer stage 2 built and left deliberately unwired.

**Architecture:** Three `remember`ed vars — `followMe`, `camSuspended`, `lastGestureMs` — become one `CameraAuthority.State`. Every write site becomes a `reduce(state, action)` dispatch. No behaviour changes: the reducer already encodes today's rules, including the asymmetry where a spin parks the camera without stamping `lastGestureMs`.

**Tech Stack:** Kotlin, Jetpack Compose.

**Spec:** [`../specs/stage-4-state-ownership.md`](../specs/stage-4-state-ownership.md) — its *Evidence gathered 2026-08-13* section makes this decision; read it before starting.

## Why this and not Compose state holders

The spec offered two mutually exclusive patterns and refused to choose in advance. The evidence chose:

- **`CameraAuthority` already exists**, with 11 passing tests, zero callers, untouched since stage 2. It was built precisely so this decision could be made against real code.
- **The `rememberSaveable` cost falls on the other option.** `MapScreen.kt` has six `rememberSaveable` values and `AndroidManifest.xml` declares **no** `configChanges`, so rotation destroys the activity and those six are load-bearing. A Compose holder must reimplement all six correctly or silently lose them. The reducer touches none of them — the three variables it owns are plain `remember`.
- **`car/` can use a reducer and cannot use a holder**, which is the tie-breaker the spec named.

## Why this does not collide with maxke24/Detour#21

The spec warned they collide head-on. Verified false for this reducer:

- `CameraAuthority` owns `followMe`, `camSuspended`, `lastGestureMs` — *whether* to follow.
- #21 is about `camTarget`, the `applied*` quartet and the `withFrameNanos` loop — *how* to follow.
- The frame loop body contains **no reference** to any of the three. It reads only the derived `cameraActive` (`MapScreen.kt:277`) as an effect key.

So both can proceed independently. Do not touch `camTarget`, `camTargetBearing`, the `applied*` variables or the frame loop in this stage — that is #21's territory and mixing them is what would create the collision the spec feared.

## Global Constraints

- Conventional Commits. **No `Co-Authored-By` trailer, no `Claude-Session` trailer, no trailers of any kind.**
- One work item, one commit.
- **Behaviour must not change.** This is an owner change, not a behaviour change. `detour-staged-refactor` forbids combining the two in one commit.
- Only `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` and, in task 4, `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt` may change.
- Run `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:compileDebugKotlin`, plus `.claude/skills/detour-shared-core/scripts/check-preconditions.sh` (7/7) and `.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh`.
- **No GPS replay is available** — both Overpass mirrors refuse this host and the one healthy public mirror is a Switzerland-only extract. Unit tests and the compile gate are the whole verification. Say so; do not imply coverage that does not exist.

## File Structure

| File | Change |
|---|---|
| `app/.../ui/MapScreen.kt` | Replace three vars with one `CameraAuthority.State`; convert 10 write sites to dispatches |
| `app/.../map/CameraAuthority.kt` | **No change.** It is already correct and tested. |
| `app/src/test/.../CameraAuthorityTest.kt` | Task 4 only: resolve the `lastGestureMs` asymmetry test |

## The site map

Every write site, and the action it becomes. Verify each line number with `grep -n` first — they have moved every time anything landed.

| Site | Today | Action |
|---|---|---|
| `:401-402` | touch listener parks, stamps | `Gesture(atMs)` |
| `:415` | `ACTION_UP` restamps if suspended | `GestureEnd(atMs)` |
| `:443` | drive-off resume clears | `DriveOffResumed` |
| `:544,:547` | `choose()` frames a destination | `DestinationFramed(atMs)` |
| `:571,:572` | `commitSpinCandidate()` | `DestinationFramed(atMs)` |
| `:758` | `startNavigation()` clears | `NavigationStarted` |
| `:1201` | `spin()` parks, **does not stamp** | `SpinStarted` |
| `:1386-1387` | follow toggle | `FollowToggled` |
| `:1480-1481` | shortcut chip pick | `DestinationFramed(atMs)` |
| `:1632-1633` | search pick | `DestinationFramed(atMs)` |

Read sites — `cameraActive` (`:277`), `following` (`:279`), the `FollowCamera` guard (`:427`, `:440`), `MapTopChrome(followMe = …)` (`:1379`) — become reads of the state object. `following` is already a derived property on `CameraAuthority.State`; use it rather than recomputing.

---

## Task 1: Introduce the state object alongside the existing vars

**Files:** Modify `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`

Add the state without removing anything, so this commit is provably inert.

- [ ] **Step 1: Add the state next to the three vars it will replace**

```kotlin
var camAuthority by remember { mutableStateOf(CameraAuthority.State()) }
```

`CameraAuthority.State()`'s defaults are `followMe = true`, `camSuspended = false`, `lastGestureMs = 0L` — identical to the three vars' current initialisers. Confirm that by reading the data class; if any default differs, stop and report rather than adjusting it.

- [ ] **Step 2: Compile and run the suites**

`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` — expect `BUILD SUCCESSFUL`. The new value is unread, so a warning about that is expected.

- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(map): introduce CameraAuthority.State alongside the camera vars"
```

## Task 2: Convert the ten write sites to dispatches

**Files:** Modify `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`

- [ ] **Step 1: Convert each site in the table above**

The shape at every site is the same — replace the assignments with one dispatch:

```kotlin
// was: camSuspended = true; lastGestureMs = System.currentTimeMillis()
camAuthority = CameraAuthority.reduce(camAuthority, CameraAuthority.Action.Gesture(System.currentTimeMillis()))
```

**`:1201` is the one that is not like the others.** `spin()` parks without stamping `lastGestureMs`, and `SpinStarted` encodes exactly that. Dispatch `SpinStarted` and **do not** add a timestamp to make it symmetric — two earlier proposals quietly "fixed" this and it is a behaviour change belonging to whoever decides it, not to this stage.

- [ ] **Step 2: Convert the read sites**

`cameraActive` becomes `(camAuthority.followMe || navigating) && !camAuthority.camSuspended`; `following` becomes `camAuthority.following`, the derived property already on the state.

- [ ] **Step 3: Delete the three now-unused vars**

Only after every site is converted. `grep -n 'camSuspended\|followMe\|lastGestureMs' MapScreen.kt` must show no bare variable, only `camAuthority.` accesses.

- [ ] **Step 4: Verify no effect key list changed**

`.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh` — `rememberUpdatedState` count unchanged, no effect-key line altered. An effect's key list is behaviour; if `cameraActive` now recomputes differently the effect restarts differently.

- [ ] **Step 5: Run everything**

`./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:compileDebugKotlin` plus both precondition scripts.

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(map): give the camera's follow and park state one owner"
```

## Task 3: Desk-verify on device

**No GPS replay is possible.** This is a hand check of the camera's authority transitions, which need no Overpass — only movement, which a replay route supplies without any network.

- [ ] **Step 1: Install and exercise each transition**

Device `RFCT42HS9WY` (Galaxy Z Fold 3 — `screencap` needs `-d 4630947232161729154`), `.debug` variant. Replay `tools/mocklocation/routes/stop-start.txt` — it needs no Overpass — and confirm:

1. Map follows at rest.
2. Drag the map → follow parks (the follow button untints).
3. Keep driving → follow resumes on its own after the quiet period.
4. Tap the follow button off, then on → parks and resumes.
5. Long-press a pin, tap a shortcut chip → camera frames it and parks.
6. Spin → parks, and the candidates stay on screen rather than being re-centred.

**Forbidden:** `adb uninstall`, `pm clear`, `pm revoke`, factory reset, touching the release variant beyond the script's force-stop.

- [ ] **Step 2: Record what you saw** in `.superpowers/sdd/stage4-desk-check.md`. If a transition misbehaves, that is a real regression — report it rather than adjusting the reducer to match.

## Task 4: Resolve the `lastGestureMs` asymmetry test

**Files:** Modify `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt`

`CameraAuthorityTest` carries a test naming the asymmetry — a spin park is immediately eligible to resume where a pan is not, because `SpinStarted` does not stamp `lastGestureMs`. Stage 2 deliberately encoded current behaviour and left the decision open.

- [ ] **Step 1: Confirm the behaviour is now wired and the test still passes.** It should, since task 2 preserved it.

- [ ] **Step 2: Rename the test so it reads as a decision, not an open question**, and extend its comment to record that the asymmetry survived stage 4 deliberately: a spin result is framed for you to look at, and a passenger spinning at speed should not have the camera snatched back before the quiet period — but the same argument would apply to a pan, so this is a real inconsistency someone may still want to close.

- [ ] **Step 3: Commit**

```bash
git commit -m "test(map): record the spin-park asymmetry as a decision, not an open question"
```

## Done Criteria

- [ ] `grep -c 'var camSuspended\|var followMe\|var lastGestureMs' MapScreen.kt` → `0`
- [ ] `grep -c 'CameraAuthority' MapScreen.kt` → `>= 10`
- [ ] `:app:testDebugUnitTest` and `:shared:testDebugUnitTest` green; shared-core 7/7; `tier0-greps.sh` unchanged
- [ ] `MapScreen.kt` line count reported, not targeted — this stage removes three declarations and adds one, so it will barely move. The win is one owner, not fewer lines.
- [ ] The six desk-check transitions confirmed on device, or the failure reported

## What stays unverified

No GPS replay. The desk check exercises the authority transitions by hand, which is the right tier for a change that touches no network path — but it is a human watching a screen, not a measured A/B. State that in the PR description.

## Next

Stage 4 is the last stage. When it closes, update [`../DECISION.md`](../DECISION.md) with the final state of `MapScreen.kt` and what the chain did and did not achieve, then the branch is ready for its PR.
