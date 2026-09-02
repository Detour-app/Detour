# Bring CarMapRenderer's camera up to the phone's

Closes maxke24/Detour#37, and ports maxke24/Detour#38's marker-heading fix to the
same surface.

`app/src/main/java/com/jellemax/detour/car/CarMapRenderer.kt` still runs the camera
loop the phone had before #21. Every defect #21 fixed is present, and #38's is too.
This is wiring, not new design: `MapMotion` already lives in `app/` rather than
`shared/` *because `CarMapRenderer` was the anticipated second consumer* — `iosApp`
has no easing loop at all — and the three functions it exposes are the three this
needs.

## Why it was excluded twice, and what changed

#21 left the car out deliberately and said so: the car half could not be verified
without a head unit, and shipping an unverifiable copy of a change is how the phone
and car drifted apart to begin with. #50 left it out for the same reason.

That constraint is now gone. The `automotive` build type merged in #51 runs
`DetourCarAppService` on an Android Automotive OS emulator, with `CarMapRenderer`
drawing to a live `SurfaceContainer` — verified before that PR merged. So the loop
under change here can be watched running, on a desk, driven by the same GPS replay
harness that measured #38.

## The defects

Measured against `CarMapRenderer.kt` as of `e75666f`:

| # | Defect | Where |
|---|---|---|
| 1 | No dead reckoning — eases toward the raw fix, settling `v·τ` behind it *plus* the fix's own age | `startCameraLoop`, `targetPos?.let` |
| 2 | Displacement-based push gate — compares this tick's values against the last *pushed* ones | the `appliedLat`/`appliedLon`/`appliedZoom`/`appliedBearing` block |
| 3 | No per-frame marker interpolation — `setPosition` is a per-fix call from `NavScreen` | `CarMapRenderer.setPosition`, `NavScreen.onFix` |
| 4 | No snap guard — nothing bounds how far one ease may travel | `startCameraLoop` |
| 5 | Marker heading written per fix, not eased — #38, on the car | `setPosition(pos, bearingDeg)` |

Plus a looser `dt` clamp than the phone's: `0.25` against `0.1`, so a large jump
closes faster per tick.

Defect 2 is worth naming precisely because it is the subtle one. Comparing against
the last pushed value cannot distinguish a slow camera from a settled one. On the
phone that suppressed 10–43% of pushes depending on speed, and it is what made the
map step visibly at low speed. `MapMotion.shouldPush` replaces the question with
"has the camera converged, and did the target move", which keeps the standstill
optimisation without quantising a moving camera.

## The change

`MapMotion.predict`, `shouldSnap` and `shouldPush` are used exactly as `MapScreen`
uses them. No new functions, no signature changes.

1. **Dead reckoning.** `MapMotion.predict(at, bearingDeg, speedMps, fixElapsedMs,
   nowElapsedMs, leadSeconds = CAM_POS_TAU)` for the camera target.

   This needs the fix's speed, bearing and monotonic timestamp. **Two of the three
   are already there**: `follow(pos, bearingDeg, speedMps, zoom)` takes speed and
   bearing today (`CarMapRenderer.kt:209`), and uses `speedMps` for the
   `BEARING_HOLD_MPS` gate. Only the fix's **monotonic timestamp** is missing, so
   the plumbing is one parameter rather than three.

   > Corrected 2026-09-02. This section previously read "which the renderer does
   > not currently receive — `follow()` gives it a position and a bearing only",
   > which was wrong when written: `speedMps` has been a parameter since the
   > renderer was introduced. The conclusion is unchanged and the change is
   > smaller than the original text implies.

   `NavScreen` already collects `TripTrackingService.lastFix` (`NavScreen.kt:207`),
   so it passes the timestamp through rather than the renderer opening a second
   subscription on a conflating `StateFlow`. That choice matters: `MapScreen` holds
   six independent subscriptions and the compose-state-hazards skill's §4 exists
   because of it. `SpinScreen.kt:116` holds the car's other one — §4's "never change
   two `lastFix` consumers in one commit" means this work touches `NavScreen`'s and
   leaves `SpinScreen`'s alone.

2. **Push gate.** `MapMotion.shouldPush(...)`, with `neverPushed` replacing the
   `appliedLat.isNaN()` sentinel and `targetMoved` tracked as the phone does.

3. **Marker interpolation.** `setPosition` moves into the loop, at
   `predict(..., leadSeconds = 0.0)` — the marker is drawn directly, so it takes no
   lead. `NavScreen`'s per-fix call goes away.

4. **Snap guard.** `MapMotion.shouldSnap(from, to)`; on a snap, bearing and zoom
   re-anchor with the position so the whole camera teleports as one.

5. **Marker heading.** The loop owns an eased `markerBearing`, at `CAM_BEARING_TAU`
   through `smoothBearing`, and the marker push gate gains a bearing term against
   `CAM_BEARING_EPS_DEG` — the same shape #38 landed on the phone, including
   advancing the pushed-bearing reference on every push rather than only on a turn.

   > Corrected 2026-09-02. This section read as though those three were shared with
   > the phone. They are **duplicated**: `CarMapRenderer.kt` has its own private
   > `smoothBearing` (`:499`), `CAM_BEARING_TAU` (`:57`) and `CAM_BEARING_EPS_DEG`
   > (`:72`), while `ui/MapCameraTuning.kt` has `internal` copies (`:9`, `:31`,
   > `:56`) plus `bearingDelta` (`:19`) which the car has no equivalent of. Same
   > values, same arithmetic, two files. `MapMotion` already imports the `ui` copy
   > of `CAM_BEARING_EPS_DEG`, so calling `MapMotion.shouldPush` from the car means
   > the camera gate reads `ui`'s constant while the marker gate reads the car's —
   > identical values today, and a trap the moment one is retuned.
   >
   > **Collapsing them is deliberately NOT part of this work.** `CONTRIBUTING.md`'s
   > "a policy earns the core when it is written more than once" says they should
   > collapse, but doing it here would put a de-duplication refactor in the same
   > branch as a behaviour fix, which `DECISION.md`'s never-in-one-commit table
   > forbids for exactly the reason that the extraction and the bug it reveals stop
   > being separately revertable. Filed separately instead; this branch leaves both
   > copies in place and the values are identical, so nothing changes behaviourally.

6. **`dt` clamp** `0.25` → `0.1`, matching the phone.

## Push rate: every tick

Decided rather than inherited. The phone pushes whenever the target moved or the
ease has not converged, which on a 120 Hz panel measured ~91 → ~78 fps. The car
loop is a **33 ms timer**, so the same rule yields ~30 pushes/sec, not ~120 —
`CarMapRenderer`'s own comment already argues 30 fps is smooth for a camera that
only ever pans, and that argument is unchanged by this work.

So: one rule across both surfaces, and the cost on the car is a quarter of the
phone's. The standstill optimisation survives — `shouldPush` returns false once the
camera has converged on a target that is itself still, so a parked map still does no
work.

## Verification

`turn-circle.txt` — the constant-radius fixture added for #38 — is the instrument
again, because the defects are about motion and rotation and it isolates both.

**What can be measured:** the camera's behaviour on the AAOS emulator, before and
after, driven by the GPS replay harness. The quantities are the ones #38 used, since
the apparatus already exists: the marker's on-screen angle excursion from its resting
value, and its frame-to-frame delta distribution.

**What cannot be measured here, stated plainly:** performance on real head-unit
hardware. #37 asks what push rate suits a slower surface; an AAOS emulator on a
desktop GPU cannot answer that, and this spec does not pretend otherwise. The push
rate above is argued from the loop's own timer, not measured on a head unit.

**A second thing that cannot be measured:** AAOS is not the Android Auto projection
transport. It runs the same Car App Library code, so it exercises `CarMapRenderer`
faithfully, but a projection-specific regression would not appear.

## Commit structure

`NavScreen` and `CarMapRenderer` change together for the marker move — the per-fix
`setPosition` call and the loop that replaces it are one behavioural unit and
splitting them leaves a commit with no marker at all. Otherwise one commit per
defect, so each is its own revert unit, as #50 did for the phone.

## Tests

`MapMotionTest` covers `predict`, `shouldSnap` and `shouldPush` already — 17 tests,
and this change adds no new logic to any of them. `CarMapRenderer` is a renderer
bound to a `SurfaceContainer`; this repo has no Robolectric, no `compose-ui-test`
and no `androidTest` source set, so nothing can reach it. Verification is the AAOS
replay, and the PR should say so rather than implying the unit suite covers it.
