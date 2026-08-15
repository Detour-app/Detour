# Smooth map motion — design

Closes [#21](https://github.com/maxke24/Detour/issues/21).

The map does not move smoothly while driving. #21 diagnoses three stacked causes; all three
are still present. A fourth defect — pre-existing, unrelated to smoothness, but living in the
same twenty lines — is included because the work rewrites the code it lives in.

## Preconditions

Run before writing the plan. If any fails, the spec is **stale** — establish whether the
assertion or the code is wrong before adapting. Every value was produced by running the
command.

```sh
# --- inverted: these assert the defects are still open, and flip when the work lands ---

# 1. The marker is pushed at the GPS rate: myLocation is a key on the overlay render -> 1
sed -n '614,615p' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt | grep -c 'myLocation'

# 2. The ease target is the raw fix, unpredicted                                       -> 1
grep -c 'camTarget = LatLon(fix.lat, fix.lon)' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# 3. The gate compares against the last *pushed* value, not the target                 -> 1
grep -c 'abs(lat - appliedLat) > CAM_POS_EPS_DEG' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# 4. No snap guard: nothing bounds how far a single ease may travel                    -> 0
grep -c 'CAM_SNAP_METERS' app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt

# 5. MapMotion does not exist yet                                                      -> 0
git grep -l 'MapMotion' -- '*.kt' | wc -l

# --- stable: these must hold before and after ---

# 6. The frame loop's key list, which must not gain or lose a key                      -> 1
grep -c 'LaunchedEffect(cameraActive, haveFix, mapLibreMap)' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# 7. The cheap marker path exists                                                      -> 1
grep -c 'fun setPosition(at: LatLon?, bearingDeg: Double? = null)' app/src/main/java/com/jellemax/detour/ui/MapLibreMap.kt

# 8. The hazards skill's five assertions still hold                                    -> exit 0
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

## Where the code actually is

Every line reference in #21 has drifted — it was written before #35 merged. Re-derived on
`7904319`:

| #21 says | Actually | What it is |
|---|---|---|
| `MapScreen.kt:583` | **`:614`** | overlay render effect, keyed on `myLocation` |
| `MapScreen.kt:604-605` | **`:635`** | the "Marker updates per fix (~1 Hz)" comment |
| `MapScreen.kt:947-949` | **`:1047-1049`** | `camTarget = LatLon(fix.lat, fix.lon)` |
| `MapScreen.kt:1009-1013` | **`:1109-1118`** | the ease |
| `MapScreen.kt:1030-1040` | **`:1129-1136`** | the epsilon gate |
| `MapCameraTuning.kt:39` | **`:35`** | `CAM_POS_EPS_DEG = 2e-6` |
| `CarMapRenderer.kt:53`, `:396-401` | **`:54`**, **`:396-430`** | the car's duplicate |
| `service/TripTrackingService.kt:94-101` | `tracking/…` **:94-101** | `Fix` — lines right, path wrong |

## Two facts that shape the fix

**The marker path is already cheap.** `MapOverlays.setPosition(at, bearingDeg)`
(`MapLibreMap.kt:354`) writes only `SRC_POSITION` — one point. `render()` calls it last as
one of eight sources. So per-frame marker updates need no new rendering machinery, contrary
to what #21 assumes. Its own KDoc frames the tradeoff it was written against:

> a following map only needs *this* once a second — and rewriting the route line's GeoJSON at
> that rate to move one point is what makes a car head unit crawl

That reasoning is about `render()`, and it is why the marker must use `setPosition` rather
than a `render()` call.

**The frame loop is not `LaunchedEffect(Unit)`.** It is
`LaunchedEffect(cameraActive, haveFix, mapLibreMap)` (`:1080`), and
`cameraActive = camAuthority.cameraActive(navigating)` (`:283`) flips on every pan, park and
resume. So the loop's coroutine-local accumulators — `lat`, `lon`, `bearing`, `zoom`,
`appliedLat` — **already** reset on each of those.

`.claude/skills/detour-compose-state-hazards/` §3 states these locals "sit in
`LaunchedEffect(Unit)` or in a key list that cannot currently change". That is no longer
accurate for this loop, and the skill should be corrected as part of this work.

## Why tuning cannot fix this

A first-order lag driven at constant velocity settles a fixed distance behind its input:

```
ẋ = (target − x) / τ,  target = p₀ + v·t   ⟹   steady state  x = target − v·τ
```

So `CAM_POS_TAU = 0.35` costs 4.9 m at 50 km/h and 11.7 m at 120 km/h, on top of the fix's
own age. Lowering τ reduces the lag and reintroduces the per-fix jerk τ exists to smooth. The
two cannot both be fixed by tuning — which is #21's central point and the reason this is a
structural change rather than a constant change.

## Design

### 1. `app/map/MapMotion.kt` — the pure unit

Not `shared/`. `iosApp/Detour/MapView.swift` has no easing loop — MapLibre iOS animates its
own camera — so a `commonMain` home would be KMP ceremony with no consumer. The consumers are
`MapScreen` now and `CarMapRenderer` later, both under `app/`. This belongs beside
`FollowCamera` and `CameraAuthority`, which are the same kind of thing and are tested the
same way.

```kotlin
/** Where the vehicle is *now*, given a fix that is already old. */
fun predict(
    at: LatLon,
    bearingDeg: Float?,
    speedMps: Double,
    fixTimeMs: Long,
    nowMs: Long,
    leadSeconds: Double,
): LatLon

/** True when the gap is too large to be continuous motion — teleport instead of easing. */
fun shouldSnap(from: LatLon, to: LatLon): Boolean

/** True while the camera still has work to do: not converged, or the target itself moved. */
fun shouldPush(
    camLat: Double, camLon: Double, camZoom: Double, camBearing: Float,
    tgtLat: Double, tgtLon: Double, tgtZoom: Double, tgtBearing: Float,
    targetMoved: Boolean,
    neverPushed: Boolean,
): Boolean
```

### 2. Dead reckoning, with lead compensation

Predicting merely *to now* fixes the fix-latency half of #21 §3 and leaves the easing half —
the camera would still sit `v·τ` back. The target must lead by the ease constant as well:

```
target = fix + bearing_unit · speed · (age + CAM_POS_TAU)
```

Substituting into the steady state above: the camera settles at `target − v·τ = fix + v·age`,
which is the true current position. Both lags cancel exactly at constant velocity. At 50 km/h
the lead is ~11.8 m — the same figure #21 computes as the total error, which is the
cross-check that the algebra is right.

**Clamping.** `ageMs.coerceIn(0, MAX_PREDICT_MS)` with `MAX_PREDICT_MS = 1500`. `Fix.timeMs`
is `location.time` (`TripTrackingService.kt:975`) — wall-clock provider time, not monotonic —
so a skewed device clock would otherwise scale the prediction without bound. The clamp caps
the worst case at 1.5 s of travel: 50 m at 120 km/h, self-corrected by the next fix. It
covers three failure modes with one mechanism: a stale fix in a tunnel, a GPS dropout, and
clock skew.

`System.currentTimeMillis() - fix.timeMs` matches existing in-repo precedent at
`TripTrackingService.kt:1207`, and is exact under replay because the mock harness sets
`time = System.currentTimeMillis()` (`MockService.kt:116`).

**No prediction below 2 m/s or without a bearing.** `predict` returns `at` unchanged. The
code already knows the reported bearing is noise there (`MapScreen.kt:1048`).

### 3. A rate-based gate

Today `moved` compares this frame's position against the last *pushed* one
(`:1129-1133`), so a slowly-moving camera is indistinguishable from a settled one. At
`CAM_POS_EPS_DEG = 2e-6` — 0.22 m of latitude, 0.14 m of longitude at 51°N — and 60 fps:

| Speed | Per-frame displacement | Frames per push |
|---|---|---|
| 100 km/h | 0.46 m | every frame |
| 50 km/h | 0.23 m | every 1–2 |
| 30 km/h | 0.14 m | every 2 |
| 20 km/h | 0.09 m | every 3 |

So below roughly 35 km/h the camera is pushed at a fraction of the frame rate and steps
visibly. The replacement asks a different question: **push while the ease has not converged on
its target, or while the target itself moved since the last frame.** Stationary means a static
target and a converged camera, which still skips — preserving the idle-map optimisation the
comment at `:1094-1097` defends.

### 4. Per-frame marker, in its own loop

`LaunchedEffect(mapOverlays, haveFix)` calling `overlays.setPosition(...)` each frame,
**independent of `cameraActive`**. That independence is the point: #21 observes the stepping
is worst when the camera is parked, and the camera loop early-returns in exactly that case
(`:1082-1088`).

Folding this into the camera loop was considered and rejected: it would mean deleting that
early return and making the "level back to north-up" one-shot edge-triggered, which perturbs
accumulators whose resets are silent and compiler-approved (§3). A second `withFrameNanos`
costs one coroutine resume per frame, not a second GL redraw.

The live fix is read through `rememberUpdatedState` (§2), never captured.

### 5. The snap guard — pre-existing, found here

`camTarget`'s only writer is `LaunchedEffect(liveFix, defaultZoom)` (`:1046-1048`), and
`liveFix` is `TripTrackingService.lastFix.collectAsStateWithLifecycle()` (`:209`) —
lifecycle-aware, not a raw collector, so it stops collecting below `STARTED`. While the app is
backgrounded, collection halts and `camTarget` freezes; Compose's frame clock pauses too, so
the frame loop's `lat`/`lon` freeze right alongside it. Nothing tracks anything while the app
is away.

The gap opens on **resume**, not during the absence. The collector re-subscribes, and the
underlying `StateFlow` conflates: every fix that arrived while suspended is dropped except the
last, so `camTarget` jumps straight to wherever the vehicle now is — potentially 100 km away —
in one assignment, right as the frame clock restarts and the loop's frozen `lat`/`lon` see that
target for the first time. The frame loop's `lat`/`lon` are coroutine locals, and none of its
three keys change across a background/foreground cycle — so **it never re-anchors** on its own.

Travel 100 km with the app backgrounded, then resume: the loop's `lat`/`lon` are still parked
at the pre-background position, and the ease now chases a `camTarget` 100 km away. `dt` is
clamped to 0.1 s, giving `a = 1 − exp(−0.1/0.35) ≈ 0.25`, so it closes ~25 % of the gap per
frame: the camera sweeps the whole distance in roughly 15 frames, requesting tiles the entire
way.

```kotlin
if (MapMotion.shouldSnap(LatLon(lat, lon), target)) {
    lat = target.lat; lon = target.lon      // teleport
} else {
    lat += (target.lat - lat) * a           // ease, as before
}
```

`CAM_SNAP_METERS = 250.0`, in `MapCameraTuning.kt` with the others. Above any plausible
single-frame movement (120 km/h × the 0.1 s dt clamp = 3.3 m) and above normal GPS scatter,
below the distance at which a slide becomes absurd. It covers resume-from-background, tunnel
exit, and the first fix after a long outage.

**One thing to measure, not assume.** Whether Compose's frame clock keeps ticking while the
Activity is stopped decides how large the gap actually gets in practice — `Recomposer` is
expected to pause below `STARTED`, but that is a belief, not an observation. Record the
answer during the device pass. It changes the symptom's severity, not whether the guard is
needed.

## Hazards, and how each is handled

From `.claude/skills/detour-compose-state-hazards/`:

| Hazard | Handling |
|---|---|
| §1 key lists are behaviour | The camera loop's key list is unchanged. Precondition 6 asserts it. The new marker loop's keys are stated and justified. |
| §2 stale capture | The marker loop reads the fix through `rememberUpdatedState`; nothing is passed as a plain `T` across a function boundary. |
| §3 coroutine-local accumulators | No key added to the camera loop, so `lat`/`lon`/`appliedLat` keep their current lifetime. The marker loop's own locals are listed in the plan. |
| §4 conflating `StateFlow` | No `lastFix` collector is added, removed, or given an `await`. `setPosition` is a synchronous call. |
| §6 per-frame snapshot writes | **Nothing new is written to snapshot state per frame.** The marker loop calls into MapLibre directly, so nothing enters the `Scaffold` (`:1355`) content lambda's invalidation set — which already recomposes per frame from `displaySpeedKmh`, written at `:1068-1070` and read at `:1473-1475`, and must not gain a second source. |
| §6 no `derivedStateOf`/`snapshotFlow` | Neither is introduced. The app still contains zero of each. |

## Verification

**Unit.** `app/src/test/…/map/MapMotionTest.kt`, plain JUnit4 beside the four existing
`map/` test classes. `predict` is pure arithmetic; `shouldSnap` and `shouldPush` are pure
predicates. Cases: lead cancels the steady-state lag at constant velocity; the clamp bounds a
stale fix; no prediction below 2 m/s; no prediction without a bearing; snap fires above the
threshold and not below; the gate pushes while unconverged and skips when settled.

**Replay.** Unlike #31 this is not blocked: the camera path touches no Overpass, so a dead
mirror cannot stop it. The harness is installed on the attached device and
`appops get com.jellemax.mocklocation android:mock_location` reports `allow`.

Quantities named **before** the run, compared on both sides of the same route file
(`tools/mocklocation/routes/urban-limits.txt`, which has the city speeds where the gate
stalls):

| Quantity | Before | After |
|---|---|---|
| `setCamera` calls/sec at ~30 km/h | expected well under 60 | ~60 |
| marker source writes/sec | ~1 | ~60 |
| camera-to-fix distance at ~50 km/h | expected ≈ 11.8 m | bounded by prediction error |

"Smoother" is not a result. Those three numbers on both sides are.

**Instrumentation is temporary.** The counters are added in their own commit and removed in
their own commit, so the measurement exists in history and not in the shipped APK.

## Out of scope

- **`CarMapRenderer`'s duplicate loop** (`:54`, `:396-430`). #21's Notes ask for it, and it
  should be done — but it cannot be verified without a head unit, and shipping an unverifiable
  copy of a change is how the phone and car drifted apart in the first place. Follow-up issue,
  filed with this spec's numbers so the work is already specified.
- **iOS.** No easing loop exists there to fix.
- **Retuning `CAM_POS_TAU` / `CAM_BEARING_TAU`.** With prediction in place the lag no longer
  argues for a smaller τ. Leave the constants alone so the replay measures one change.
