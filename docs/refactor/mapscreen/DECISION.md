# The MapScreen refactor — what was done, and what is left

`MapScreen.kt` was 3204 lines: one 1419-line composable holding 59 `remember` declarations,
35 effects, six independent collectors on one `StateFlow`, and eight closures over mutable
state that no test could reach. None of it was tested.

It is now **1666 lines**, and the reduction is the least interesting part.

| | Before | After |
|---|---|---|
| `MapScreen.kt` | 3204 lines | 1666 |
| `ui/` files | 1 monolith | 31 |
| Decisions with tests | 0 | 7 units, 166 tests |
| Road-hazard features reachable by iOS | none | all three |
| `car/` duplication | arrival/reroute, camera-warn, speed-limit | none |

**The iOS result is the one worth remembering.** `RoadRoulette.speedLimitWays`,
`RoadRoulette.snapSpeedLimitKmh` and `SpeedCameras.near` were already in commonMain, unused,
because only the *stateful wrapper* around them was welded into a composable. An audit of the
four surfaces found that parity is decided by statefulness, not by domain relevance: every
feature that reached iOS had its logic in `shared/`, and every one that did not was welded into
a composable or a Service. Moving those wrappers is what made the average-speed readout, the
posted-limit sign and the camera chime available to iOS at all.

## What each stage did

1. **Mechanical split** — 1650 lines of presentational composables and pure helpers into eleven
   same-package files. Zero lines were added to `MapScreen.kt` across all eleven moves, and the
   three external call sites have zero-line diffs.
2. **Pure decisions** → `com.jellemax.detour.map`: `NavPolicy`, `GroupSpinRules`,
   `FollowCamera`, `CameraAuthority`. The car's duplicate arrival/reroute policy deleted.
3. **Road-hazard machines** → `shared/…/drive/`: `SectionAverageTracker`, `CameraWarner`,
   `SpeedLimitTracker`, with `commonTest` coverage that `ios.yml` gates on JVM and
   Kotlin/Native. Rewrites rather than moves — commonMain has no `Dispatchers.*`, so I/O is
   handed in and timestamps are injected.
4. **One owner for the camera** — `followMe`, `camSuspended` and `lastGestureMs`, written from
   ten sites, became one `CameraAuthority.State`.

The full investigation — six architecture proposals, three independent evaluations, the staged
specs and every implementation plan — is in git history at **`b7f4c6f`**, the last commit
before this file was cut down. Nothing was purged; `git show b7f4c6f:docs/refactor/mapscreen/`
lists it.

## What is not verified

- **No GPS replay ran for stages 3 or 4.** Both mirrors the app uses refused this host, and the
  one healthy public mirror is a Switzerland-only extract with no Belgian data. That leaves the
  posted-limit ladder, the 3-fix clear latency and the camera chime unmeasured. Stage 4 was
  desk-checked on device instead; stage 3 was not.
- **The camera chime cannot be observed at all.** `NavVoice.speak` logs only on audio-focus
  *failure*, so a successful chime writes nothing. Observing it needs a call-site log line.
- **`GroupSpinRules` is extracted and tested but its call site is unchanged** — verifying a
  convoy vote needs two devices transmitting to each other.
- **The phone-audio convergence items landed without their device session**, so register
  entries 12 and 15 are resolved in code and open on hardware.
- **Stage 4 costs recomposition during a drag.** The three camera variables were independent
  snapshot states and nothing in composition read `lastGestureMs`, so stamping it invalidated
  nothing. As one object, every `ACTION_MOVE` past the touch slop recomposes MapScreen's scope
  for the duration of a drag. No derived value changes and no effect re-keys, so it is
  composition cost rather than behaviour, and it is inherent to having one owner. Unmeasured
  rather than cleared. `derivedStateOf` around the two reads is the fix if it shows.

## What is left

- **[Convergence 2 — section readouts](specs/convergence-2-section-readouts.md).** The only
  unstarted spec. It was blocked on stage 3's `SectionAverageTracker`, which now exists, so it
  is unblocked.
- **[The divergence register](15-divergence-register.md).** Its §A entries needing a product
  answer — the watch's discarded instruction text, the HUD at a standstill, distance
  quantisation, catch-up order — and its remaining §B bugs.
- **maxke24/Detour#21** (map choppy while driving) is untouched and unblocked. The collision
  this chain feared does not exist: the reducer owns *whether* to follow, #21 owns *how*, and
  the frame loop references none of the reducer's three variables.
- **maxke24/Detour#22** should be narrowed rather than closed. Its premise is refuted — `out
  geom` was measured not to clip relation members, and clipping a relation to its entry end
  makes both E40 relations *rejected*, not shortened. The only shape shown to shorten
  `spanMeters` is a relation with a member node more than `MIN_SPAN_M` inside its own span.

## How the remaining work runs

[`specs/00-chain-design.md`](specs/00-chain-design.md) explains the spec chain and its
staleness contract. `.claude/skills/detour-staged-refactor/` carries the procedure, and its
`chain-status.sh` reports where things stand.
