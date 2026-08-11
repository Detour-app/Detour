# Proposal 1 — Mechanical file split

**Target:** `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (3193 lines)
**Position championed:** pure mechanical file split, zero architecture change.

All line numbers below are `MapScreen.kt:NNN` unless another file is named.

---

## Cross-cutting concerns I identify in MapScreen.kt

This is my own reading of the file, not a restatement of the brief. Sixteen concerns,
with the line ranges each actually occupies. Note how many of them are *interleaved*
rather than contiguous — that interleaving is the real problem, and it is what
determines what a file split can and cannot fix.

| # | Concern | Line ranges | Notes |
|---|---|---|---|
| 1 | **Imports** | 3–208 | 206 lines. One import block currently serves 31 top-level declarations. |
| 2 | **Travel-mode iconography** | 213–219 | `val TravelMode.icon`. Public, and consumed by three other screens (`RoutesScreen.kt:303`, `HistoryScreen.kt:317`, `RouteEditorScreen.kt:386`) plus two callers in this file (2322, 2692). It has nothing to do with the map. |
| 3 | **Camera easing maths + tuning constants** | 221–230, 232–255 | `smoothBearing`, `CAM_POS_TAU`/`CAM_BEARING_TAU`/`CAM_ZOOM_TAU`, `SPEED_TAU`, `SPEED_EPS_KMH`, `CAM_*_EPS*`. Pure arithmetic + numbers. Consumed only by the two frame loops (1247–1263, 1265–1333). |
| 4 | **Trajectcontrole (average-speed section) geometry** | 281–288, 290–314 | `SECTION_GATE_METERS`, `SECTION_WEDGE_DEG`, `sectionExitGate`. Pure function over `LatLon` + `SpeedCameras.Section`. |
| 5 | **Spin-result process-scoped handoff** | 316–320, 322–343, 345–355, 357–367, 369–381, 383–385, 387–414 | `CANDIDATE_COLORS`, `GroupSpin.asRouteCandidates`, `List<RouteCandidate>.asSpinCandidates`, `leadingSpinIndex`, `SpinResult`, `SpinResultHolder`, `seedRouteNavigation`. This is a small module of its own that happens to live in a UI file; `RoutesScreen.kt:202` already reaches into it. |
| 6 | **State ownership** (the monolith's core) | 423–553, 592–595, 1105, 937–939, 1138–1143, 1173, 1250 | ~64 `remember` / `rememberSaveable` / `collectAsStateWithLifecycle` / `rememberUpdatedState` declarations. This is what makes the composable a monolith. |
| 7 | **Android lifecycle + MapView plumbing** | 598–625, 627–648, 650–663 | UI-visibility → GPS cadence, `MapView.onCreate/onStart/onResume/onDispose`, style (re)load + `FogView` attach. |
| 8 | **Permission choreography** | 714–734, 736–758, 760–770, 772–780, 782–801 | Three `rememberLauncherForActivityResult`s, a fine/coarse/activity-recognition/notifications bootstrap, a background-location disclosure gate, a deferred mic request. |
| 9 | **Camera control (parking, resuming, targeting, easing)** | 518–523, 541–553, 665–694, 696–712, 1232–1245, 1265–1333 | A per-frame `withFrameNanos` loop, a touch-listener parking rule, a speed+quiet-period resume rule, and a per-fix target setter. |
| 10 | **Map overlay rendering** | 872–902, 904–909, 911–915, 1085–1089, 1091–1096, 1120–1122 | Six separate `LaunchedEffect`s pushing into `MapOverlays`, deliberately split by update cadence. |
| 11 | **Fog of war** | 917–927, 928–932, 1124–1132, 941–946 | Three effects writing `FogView` fields + camera-move invalidation. |
| 12 | **Speed limits / speed cameras / section averages** | 527–539, 1018–1056, 1058–1083, 1134–1167, 1169–1230 | Four long `TripTrackingService.lastFix.collect` loops, a `ToneGenerator`, and 7 pieces of state, 4 of which never leave these effects. |
| 13 | **Convoy + circles** | 489–512, 736–758, 803–870, 1098–1119, 1128–1132 | Group-spin vote resolution, convoy name lookup, mic permission, circle-fix polling. |
| 14 | **Navigation session** | 514–517, 970–980, 982–1016, 1335–1342, 1344–1390 | Start/stop, progress, arrival, reroute, BLE/Wear relay. |
| 15 | **Spin orchestration** | 261–267, 803–815, 825–840, 1392–1513, 1515–1527 | `spin()` alone is 122 lines (1392–1513) with two entirely different code paths (round-trip vs POI) and its own error taxonomy. |
| 16 | **The view tree** | 1529–1836 | `Scaffold` + `Box` + top chrome + PTT + bottom `Column` + the four-way `AnimatedContent` bottom card + three trailing dialogs. |
| 17 | **Presentational child composables + intent launchers** | 1839–3193 | 27 `private` composables and 7 `private` helper functions. 1355 lines, ~42% of the file, and **none of them touch MapScreen's state**. |

The single most important fact for this proposal: **concern 17 is 1355 lines that already
have clean, hoisted, parameter-only interfaces.** They were written correctly. They are
just in the wrong file.

---

## The pattern, stated precisely

1. Every new file stays in `package com.jellemax.detour.ui`. Kotlin resolves top-level
   declarations in the same package with **no import statement**, so every existing
   call site — inside `MapScreen.kt` and in `RoutesScreen.kt` / `HistoryScreen.kt` /
   `RouteEditorScreen.kt` — compiles unchanged. There is no "update the imports"
   ripple.
2. `private` at Kotlin top level means *file*-private. Any moved declaration that
   still has a caller in another file becomes `internal`. `internal` in an AGP module
   is visible to `app/src/test` (the unit-test compilation is a friend module of
   `main`), which is exactly the trick `HistoryScreen.kt:72` and `HistoryScreen.kt:120`
   already use so `app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt`
   can reach `TraceSegment` and `matchTripPoints`. **This proposal is a second
   application of a convention the repo already established.**
3. `MapScreen` keeps its signature (`fun MapScreen(onOpenHub: () -> Unit)`), keeps
   every `remember`, keeps every `by` delegate, keeps its local functions. No
   ViewModel, no state holder, no data class of parameters, no DI. Nothing is
   introduced that the project doesn't already have.
4. **Optional phase 3:** `LaunchedEffect` / `DisposableEffect` blocks move into
   `@Composable internal fun XxxEffect(...)` functions that take current values as
   parameters and writers as lambdas. These are still *not* state holders: they own
   only state that provably never escapes the effect. Where state escapes, it stays
   in `MapScreen`.
5. New files follow the neighbours' conventions verbatim: file-level KDoc on the
   non-obvious ones (`GlassSurface.kt` header comment, `MapLibreMap.kt:78–86`,
   `Format.kt:26–33`), one-liner KDoc per public composable (`Navigation.kt:73`,
   `AppBar.kt:16–23`), constants declared at the top with the *why* in a comment,
   not the *what*. **Every comment moves with the code it explains, verbatim.**
   This file's comments are its best asset — 1836 lines of it are as much rationale
   as code — and a split that strands a comment away from its subject destroys more
   value than it creates.

What the pattern explicitly refuses to do: hoist state, invent a `MapUiState`, invent
a `rememberMapScreenState()`, add a ViewModel, or change *any* runtime behaviour. If a
step cannot be shown correct by "these bytes moved, and only their visibility keyword
changed", the step is out of scope.

---

## Proposed file layout

Twelve new files. Phases 1–2 (the pure-move part) are files 1–9. Phase 3 (effect
functions) is files 10–13.

Line counts are body lines + a per-file import block; they are estimates within about
±15%, since an import block's real size only settles once the compiler tells you what
each file needs.

### Phase 1 — helpers and shared vocabulary

**`ui/TravelModeIcon.kt`** — ~22 lines

| Symbol | From | Visibility |
|---|---|---|
| `val TravelMode.icon: ImageVector` | 213–219 | stays `public` |

Rationale: it has four consumers outside `MapScreen.kt` and zero connection to the
map. Matches `Format.kt` / `GlassSurface.kt`: a tiny file that exists so a
cross-screen primitive has an obvious home. Consumer diff: **zero lines** — same
package, already public.

**`ui/MapCameraTuning.kt`** — ~105 lines

| Symbol | From | Visibility |
|---|---|---|
| `smoothBearing` | 221–230 | `private` → `internal` |
| `CAM_POS_TAU`, `CAM_BEARING_TAU`, `CAM_ZOOM_TAU` | 232–238 | `private const` → `internal const` |
| `SPEED_TAU`, `SPEED_EPS_KMH` | 240–247 | `private const` → `internal const` |
| `CAM_POS_EPS_DEG`, `CAM_ZOOM_EPS`, `CAM_BEARING_EPS_DEG` | 249–255 | `private const` → `internal const` |
| `FIT_PADDING_PX` | 257–259 | `private const` → `internal const` |
| `CAM_RESUME_SPEED_MPS`, `CAM_RESUME_QUIET_MS` | 269–274 | `private const` → `internal const` |
| `CIRCLE_FIX_POLL_MS` | 276–279 | `private const` → `internal const` |
| `SECTION_GATE_METERS`, `SECTION_WEDGE_DEG` | 281–288 | `private const` → `internal const` |
| `sectionExitGate` | 290–314 | `private` → `internal` |

`CURVY_CANDIDATES` (261–267) deliberately does **not** move: its only reader is
`spin()` at 1417, which stays in `MapScreen.kt`, so it stays `private` there.

**`ui/SpinShare.kt`** — ~112 lines

| Symbol | From | Visibility |
|---|---|---|
| `CANDIDATE_COLORS` | 316–320 | `private` → `internal` |
| `GroupSpin.asRouteCandidates()` | 322–343 | `private` → `internal` |
| `List<RouteCandidate>.asSpinCandidates()` | 345–355 | `private` → `internal` |
| `leadingSpinIndex` | 357–367 | `private` → `internal` |
| `SpinResult` | 369–381 | already `internal` |
| `SpinResultHolder` | 383–385 | already `internal` |
| `seedRouteNavigation` | 387–414 | already `internal` |

The comment at 374–375 ("Not private: seedRouteNavigation() below (and RoutesScreen.kt,
which calls it) need to write into this holder from outside MapScreen's own
composition") becomes redundant once the holder lives in its own file, and should be
rewritten rather than carried over verbatim — the one exception to rule 5 above.
`RoutesScreen.kt:202` diff: **zero lines**.

### Phase 2 — the presentational tail (1839–3193, plus the pill row)

**`ui/MapDialogs.kt`** — ~260 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `SearchDialog` | 1839–1958 | 120 | `private` → `internal` |
| `ShortcutChips` | 1960–1996 | 37 | `private` → `internal` |
| `BackgroundLocationDisclosure` | 1998–2028 | 31 | `private` → `internal` |
| `SavePinDialog` | 2030–2055 | 26 | `private` → `internal` |

**`ui/MapChrome.kt`** — ~235 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `ModeBar` | 2683–2697 | 15 | `private` → `internal` |
| `MapTopChrome` | 2699–2775 | 77 | `private` → `internal` |
| `SearchPill` | 2777–2825 | 49 | **stays `private`** |
| `ConvoyPill` | 2827–2851 | 25 | **stays `private`** |
| `GlassRailButton` | 2853–2879 | 27 | **stays `private`** |

**`ui/MapHud.kt`** — ~250 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `EndTripButton` | 2881–2897 | 17 | `private` → `internal` |
| `PushToTalkButton` | 2899–2954 | 56 | `private` → `internal` |
| `SpeedHud` | 2956–3012 | 57 | `private` → `internal` |
| `SectionAverageChip` | 3014–3046 | 33 | **stays `private`** |
| `ActiveTripCard` | 3101–3139 | 39 | `private` → `internal` |
| `StatItem` | 3141–3148 | 8 | **stays `private`** |

**`ui/Pills.kt`** — ~90 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `Pill` | 2057–2085 | 29 | `private` → `internal` |
| `SegmentedPillRow` | 2087–2100 | 14 | `private` → `internal` |
| `ScrollingPillRow` | 2102–2121 | 20 | `private` → `internal` |

**`ui/SpinCards.kt`** — ~310 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `DIRECTION_NAMES` | 210–211 | 2 | **stays `private`** (both readers, 2330 and 2502, land in this file) |
| `SpinDock` | 2281–2366 | 86 | `private` → `internal` |
| `SpinSheet` | 2368–2547 | 180 | `private` → `internal` |

**`ui/CandidatesCard.kt`** — ~165 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `CandidatesCard` | 2549–2681 | 133 | `private` → `internal` |

**`ui/NavAppLaunch.kt`** — ~280 lines

| Symbol | From | Lines | Visibility |
|---|---|---|---|
| `launchNav` | 2123–2149 | 27 | **stays `private`** |
| `navAppUsableDirectly` | 2151–2164 | 14 | `private` → `internal` (for the test, see below) |
| `handleGoTap` | 2166–2186 | 21 | **stays `private`** |
| `NavMenuItems` | 2188–2232 | 45 | **stays `private`** |
| `NavIconButton` | 2234–2279 | 46 | `private` → `internal` (called from `SpinCards.kt`) |
| `NavButton` | 3048–3099 | 52 | `private` → `internal` (called from `SpinCards.kt`) |
| `navigateRoundTrip` | 3150–3162 | 13 | **stays `private`** |
| `navigateGoogleMaps` | 3164–3172 | 9 | **stays `private`** |
| `navigateWaze` | 3174–3186 | 13 | **stays `private`** |
| `navigateGeo` | 3188–3193 | 6 | **stays `private`** |

Worth pausing on: seven of ten symbols here **stay `private`**, and after the move
that word finally means something. Today `private fun navigateGeo` sits in a file with
30 other declarations; tomorrow it is private to a 280-line file about exactly one
subject. The same is true of `SearchPill`/`ConvoyPill`/`GlassRailButton` and
`SectionAverageChip`/`StatItem`. **The split turns 12 nominally-private helpers into
genuinely-private ones.** Only 4 declarations get *weaker* encapsulation
(`smoothBearing`, `sectionExitGate`, `CANDIDATE_COLORS`, and the constants block) and
of those, three are exactly the ones I want tests to reach.

### Result after phases 1–2

| File | ~lines |
|---|---|
| `ui/MapScreen.kt` | **~1,565** |
| `ui/SpinCards.kt` | ~310 |
| `ui/NavAppLaunch.kt` | ~280 |
| `ui/MapDialogs.kt` | ~260 |
| `ui/MapHud.kt` | ~250 |
| `ui/MapChrome.kt` | ~235 |
| `ui/CandidatesCard.kt` | ~165 |
| `ui/SpinShare.kt` | ~112 |
| `ui/MapCameraTuning.kt` | ~105 |
| `ui/Pills.kt` | ~90 |
| `ui/TravelModeIcon.kt` | ~22 |
| **total** | **~3,394** (+~200 from duplicated import blocks) |

State that region plainly: **the biggest file goes 3193 → ~1565, and the whole thing
gets ~6% bigger in aggregate.** A 51% cut of the worst file for zero behaviour risk is
a real result; anyone claiming it is a "solution" is overselling it.

### Phase 3 — effect functions (optional, and genuinely optional)

**`ui/MapCameraEffects.kt`** — ~155 lines

| New symbol | Source lines |
|---|---|
| `CameraParkingEffect` | 665–694 |
| `CameraResumeEffect` | 696–712 |
| `CameraTargetEffect` | 1232–1245 |
| `SpeedReadoutEffect` | 1247–1263 |
| `CameraFollowEffect` | 1265–1333 |

**`ui/MapSpeedEffects.kt`** — ~185 lines

| New symbol | Source lines | State absorbed |
|---|---|---|
| `AmbientSpeedLimitEffect` | 1018–1056 | `speedLimitWays` (528–530), `speedLimitWaysCenter` (531), `speedLimitFetchMs` (532), `speedLimitMisses` (533) |
| `SpeedCameraPrefetchEffect` | 1058–1083 | — |
| `SpeedCameraChimeEffect` | 1134–1167 | `toneGen` (1141–1143) + its `DisposableEffect` (1144), `speedCamerasRef`/`ambientLimitRef`/`navProgressRef` (1138–1140) |
| `SectionAverageEffect` | 1169–1230 | `speedSectionsRef` (1173) |

**`ui/MapOverlayEffects.kt`** — ~130 lines

| New symbol | Source lines |
|---|---|
| `MapOverlayRenderEffect` | 872–915 (render + icon + route colour) |
| `FogEffects` | 917–932, 1124–1132 |
| `MapMarkerEffects` | 1085–1096, 1120–1122 |
| `CircleFixPollEffect` | 1098–1119 (absorbs `circleFixes`? **no** — see below) |

**`ui/MapSessionEffects.kt`** — ~175 lines

| New symbol | Source lines | State absorbed |
|---|---|---|
| `StartupSyncEffect` | 566–578 | — |
| `FriendFogShareEffect` | 580–585 | — |
| `UiVisibilityEffect` | 598–625 | — |
| `MapViewLifecycleEffect` | 627–648 | — |
| `MapStyleEffect` | 650–663 | — |
| `LocationPermissionEffect` | 772–801 | `permissionLauncher` (772–780) |
| `MicPermissionEffect` | 736–758 | `micPermissionLauncher` (744–746) |

### Result after phase 3

`MapScreen.kt` lands at roughly **1,050–1,200 lines**: ~610 lines of effect bodies
leave, ~250 come back as call sites, ~24 lines of effect-private state leave with
their effects, and the import block shrinks by maybe 40 lines as the coroutine and
MapLibre imports follow their users out. Total across all 15 files: ~3,600.

Note the shape of that number honestly. Phase 3 removes 610 lines of code but only
~380 net lines of *file*, at the cost of 15 new function signatures, four of which
are wide (see below). **Phase 3 is where this proposal's cost/benefit turns
marginal.** Phases 1–2 are close to free; phase 3 is a judgement call.

---

## What CANNOT move under this pattern, and why

**1. `MapScreen`'s ~64 state declarations (423–553, 592–595, 937–939, 1105, 1138–1140, 1173, 1250).**
This is definitional. Moving a `remember` out of `MapScreen`'s composition and into
something reachable by another file *is* introducing a state holder, which is the one
thing this pattern refuses. Everything else on this list is downstream of it.

**2. The eight local functions that close over `var` state.**

| Function | Lines | Reads | Writes |
|---|---|---|---|
| `fetchLocation` | 714–734 | `context`, `scope`, `mapLibreMap` | `myLocation`, `error` |
| `onLocationGranted` | 760–770 | `context` | `showBgLocationDisclosure` |
| `choose` | 803–815 | `myLocation`, `mapLibreMap`, `fitBottomPaddingPx` | `destination`, `destinationName`, `route`, `candidates`, `camSuspended`, `lastGestureMs` |
| `commitSpinCandidate` | 825–840 | `spinOffer`, `myLocation`, `mapLibreMap` | same six as `choose` |
| `stopNavigation` | 970–980 | `context`, `mapOverlays` | `navigating`, `navProgress`, `camTargetBearing` |
| `startNavigation` | 982–1016 | `myLocation`, `stats`, `destination`, `route`, `serverConfig`, `mode`, `scope` | `camSuspended`, `error`, `navigating`, `rerouting`, `route` |
| `spin` | 1392–1513 | 10 values | `spinJob`, `spinning`, `error`, `camSuspended`, `route`, `destination`, `destinationName`, `candidates` |
| `selectMode` | 1515–1527 | `mode`, `spinOffer` | `radiusKm`, `minRadiusKm`, `destination`, `destinationName`, `route`, `candidates` |

`spin()` is the worst of these: 122 lines, two unrelated code paths (round-trip
1408–1468 vs POI 1469–1495), its own four-branch error taxonomy (1496–1508), and eight
writes. To move it as a top-level function it would need eight setter parameters, i.e.
a parameter object, i.e. a state holder. **It stays, and it is 8% of the file on its
own.** This is the single largest thing this proposal fails to address.

**3. The view tree (1529–1836).**
Mechanically it *can* move — `@Composable internal fun MapScreenContent(...)` — but its
parameter list would be roughly 30 values plus 20 lambdas. That is the honest arithmetic
of the four bottom-card call sites alone: `SpinSheet` (1760–1791) takes 20 arguments,
`SpinDock` (1740–1759) takes 12, `CandidatesCard` (1713–1739) takes 7, plus
`MapTopChrome` (1579–1596) 10, plus the three trailing dialogs. A 50-parameter
composable is not more readable than the block it replaced; it is the same code with a
lossy index in front of it. **I do not propose it.** The view tree stays in
`MapScreen.kt` and is the main reason the file stays over 1,000 lines.

**4. The map click/long-click listener registration (940–968).**
It writes `layersOpen`, `destination`, `destinationName`, `route`, and calls `choose`
and `ConvoyLiveClient.sendSpinVote`. Extractable with 4 lambdas, but see the
`rememberUpdatedState` hazard below — this effect registers listeners that outlive the
recomposition that created them, and it already needs three `rememberUpdatedState`
wrappers (937–939) to be correct. Adding parameters to that is adding a footgun.
**Borderline; I would leave it.**

**5. State that phase 3 cannot absorb, and therefore keeps `MapScreen` wide.**

- `circleFixes` (1105) is written by the poll effect (1112) but read by *two* other
  effects (1121, 1129), so it stays in `MapScreen` and the poll effect gets an
  `onFixes` writer lambda.
- `speedCameras`/`speedSections` (534–535) are written by the prefetch effect and read
  by the chime and section effects respectively — cross-effect, so they stay.
- `sectionAvgKmh`/`sectionLimitKmh` (538–539) and `ambientSpeedLimitKmh` (527) are
  consumed by `SpeedHud` at 1644–1647, so they stay.
- `camTarget`/`camTargetBearing`/`camTargetZoom` (547–549) have three writers
  (1236–1244, 973 in `stopNavigation`) and two readers (900 in the overlay render,
  1270–1283 in the follow loop), so they stay.
- `displaySpeedKmh` (550) is written per frame (1259) and read at 1641/1643, so it
  stays. This one matters for performance — see below.
- `bgLocationLauncher` (738–740) cannot move into a permission effect because
  `BackgroundLocationDisclosure`'s `onAllow` at 1803 launches it from inside the view
  tree.

Only **five** of the ~64 state declarations are provably effect-private:
`speedLimitWays`, `speedLimitWaysCenter`, `speedLimitFetchMs`, `speedLimitMisses`
(528–533) and `toneGen` (1141). That is the real ceiling on phase 3.

**6. Anything the Android Auto code could share.** `car/` cannot call a `@Composable`.
Everything phase 3 produces is invisible to it. See the Android Auto section.

---

## Concrete changes required

### Visibility

Twenty-one declarations go `private` → `internal`; three are already `internal`; one is
already `public`. Twelve stay `private` and become meaningfully so. No declaration
becomes `public` that wasn't.

### Signatures — phases 1–2

**Unchanged. Every one of them.** This is the pattern's whole selling point. `SpinSheet`'s
20-parameter signature (2372–2395) is identical before and after; only the `private`
keyword and the file path change. A reviewer can verify a phase-1/2 commit with
`git show -M -C --stat` plus a read of the visibility keywords.

### Signatures — phase 3

These are new, and they are the honest cost. Representative examples:

Cleanest possible case — writes no Compose state at all, so it is a pure consumer:

```kotlin
// ui/MapCameraEffects.kt — body verbatim from MapScreen.kt:1271-1333
@Composable
internal fun CameraFollowEffect(
    map: MapLibreMap?,
    active: Boolean,
    target: LatLon?,
    fallbackTarget: LatLon?,        // myLocation, per MapScreen.kt:1280
    targetBearingDeg: Float?,
    targetZoom: Double,
)
```

Clean case — absorbs four state declarations that never escape:

```kotlin
// ui/MapSpeedEffects.kt — body verbatim from MapScreen.kt:1024-1056
@Composable
internal fun AmbientSpeedLimitEffect(
    navigating: Boolean,
    onLimitKmh: (Double?) -> Unit,
) {
    // These four move here from MapScreen.kt:528-533; nothing else ever read them.
    var ways by remember { mutableStateOf<List<RoadRoulette.SpeedLimitWay>>(emptyList()) }
    var waysCenter by remember { mutableStateOf<LatLon?>(null) }
    var fetchMs by remember { mutableLongStateOf(0L) }
    var misses by remember { mutableIntStateOf(0) }
    LaunchedEffect(navigating) { /* … */ }
}
```

Clean case — absorbs `toneGen` and its disposal:

```kotlin
// ui/MapSpeedEffects.kt — bodies from MapScreen.kt:1141-1144 and 1145-1167
@Composable
internal fun SpeedCameraChimeEffect(
    cameras: List<SpeedCameras.Camera>,
    limitKmh: Double?,              // navProgress?.speedLimitKmh ?: ambientSpeedLimitKmh
)
```

Two-out writer — the section pair must be written together or the HUD flickers:

```kotlin
// ui/MapSpeedEffects.kt — body verbatim from MapScreen.kt:1174-1230
@Composable
internal fun SectionAverageEffect(
    sections: List<SpeedCameras.Section>,
    onSection: (averageKmh: Double?, limitKmh: Double?) -> Unit,
)
```

Ugly case #1 — a lambda contract that encodes a conditional the old code stated in
Kotlin. `MapScreen.kt:1239` only overwrites the bearing when
`fix.bearingDeg != null && fix.speedMps > 2.0`, otherwise the previous bearing is
deliberately held. A `(LatLon, Float?, Double) -> Unit` writer has to mean "null bearing
= keep the old one", which is a rule that now lives in a doc comment instead of in an
`if`:

```kotlin
// ui/MapCameraEffects.kt — body from MapScreen.kt:1236-1245
@Composable
internal fun CameraTargetEffect(
    fix: Fix?,
    defaultZoom: Float,
    distanceToTurnMeters: Double?,
    /** A null [bearingDeg] means "hold the previous bearing" — the fix was too slow
     *  for its bearing to be trustworthy. See MapScreen.kt's original 1239. */
    onTarget: (target: LatLon, bearingDeg: Float?, zoom: Double) -> Unit,
)
```

Ugly case #2 — parameter-list explosion. Fifteen parameters, versus the zero the
current effects need because they close over the state directly:

```kotlin
// ui/MapOverlayEffects.kt — bodies from MapScreen.kt:874-902, 907-909, 913-915
@Composable
internal fun MapOverlayRenderEffect(
    overlays: MapOverlays?,
    myLocation: LatLon?,
    destination: LatLon?,
    route: RouteResult?,
    radiusKm: Float,
    mode: TravelMode,
    directionDeg: Float?,
    navigating: Boolean,
    candidates: List<RouteCandidate>,
    positionBearingDeg: Float?,
    mapIcon: Settings.MapIcon,
    routeColor: Settings.RouteColor,
    cameras: List<SpeedCameras.Camera>,
    peers: Collection<FriendPosition>,
    circleMembers: Collection<MemberFix>,
)
```

Ugly case #3 — the listener-staleness hazard. `CameraParkingEffect` registers a touch
listener once (`DisposableEffect(mapView)`, 669) and the listener reads `camSuspended`
at 689. Today that read goes straight to a `MutableState` and is always current. As a
parameter it is captured at registration time and goes stale forever:

```kotlin
// ui/MapCameraEffects.kt — body from MapScreen.kt:669-694
@Composable
internal fun CameraParkingEffect(
    mapView: MapView,
    parked: Boolean,
    onPark: () -> Unit,
    onGestureEnd: () -> Unit,
) {
    // REQUIRED. Without these the listener registered in DisposableEffect(mapView)
    // closes over the first composition's values and never sees another.
    val parkedRef = rememberUpdatedState(parked)
    val onParkRef = rememberUpdatedState(onPark)
    val onGestureEndRef = rememberUpdatedState(onGestureEnd)
    DisposableEffect(mapView) { /* … reads parkedRef.value … */ }
}
```

**This is the single most dangerous thing in the whole proposal.** It is a silent,
compiles-fine, ships-fine bug class that phases 1–2 cannot produce and phase 3 can.
The affected sites are 669–694, 940–968, and 1145–1167 / 1174–1230 (all four
`LaunchedEffect(Unit)` fix collectors, which already use `rememberUpdatedState` at
1138–1140 and 1173 precisely because of this). A reviewer must check every
phase-3 effect that keys on `Unit` or on a stable object.

---

## Migration plan

Fourteen commits. Every one compiles, every one is independently revertable, every one
is reviewable in under ten minutes. Verify each with a debug build **inside the
devcontainer** (`docker exec -u 1000:1000 …`; the host has JDK 26 and no Android SDK,
and a bare `./gradlew build` is off limits) plus `testDebugUnitTest`.

Each move commit should be a **pure cut-and-paste with no reformatting**, so
`git log -C -C --follow` and `git blame -C` can follow the code across the split. If a
comment needs rewording, that is a *separate* follow-up commit.

**Phase 1 — vocabulary (low risk, immediate value)**

1. `refactor(ui): move TravelMode.icon to its own file` — 213–219 → `TravelModeIcon.kt`.
   Zero visibility change, zero call-site change. The smallest possible proof that the
   same-package trick works, before betting anything on it.
2. `refactor(ui): extract camera easing constants and helpers` — 221–314 minus
   `CURVY_CANDIDATES` → `MapCameraTuning.kt`. `private` → `internal` on 12 declarations.
3. `refactor(ui): extract the spin-result holder` — 316–414 → `SpinShare.kt`. Confirms
   `RoutesScreen.kt` still compiles untouched.
4. `test(ui): cover sectionExitGate, leadingSpinIndex and navAppUsableDirectly` — new
   `app/src/test/java/com/jellemax/detour/ui/…Test.kt`. **Do this before the UI moves**,
   so the rest of the migration has something to fail against. (Requires step 8's
   `internal` on `navAppUsableDirectly`, so either reorder or split this commit.)

**Phase 2 — the presentational tail (mechanical, bulk of the line count)**

5. `refactor(ui): move the map dialogs out of MapScreen` — 1839–2055 → `MapDialogs.kt`.
6. `refactor(ui): move the pill row primitives out of MapScreen` — 2057–2121 → `Pills.kt`.
7. `refactor(ui): move the map top chrome out of MapScreen` — 2683–2879 → `MapChrome.kt`.
8. `refactor(ui): move nav-app launching out of MapScreen` — 2123–2279 + 3048–3099 +
   3150–3193 → `NavAppLaunch.kt`. Largest single move; four non-contiguous ranges.
9. `refactor(ui): move the speed/trip HUD out of MapScreen` — 2881–2954 + 2956–3046 +
   3101–3148 → `MapHud.kt`.
10. `refactor(ui): move the candidates card out of MapScreen` — 2549–2681 →
    `CandidatesCard.kt`.
11. `refactor(ui): move the spin dock and sheet out of MapScreen` — 210–211 + 2281–2547
    → `SpinCards.kt`.

**Stop here and reassess.** After step 11, `MapScreen.kt` is ~1,565 lines, all of it
one composable plus its state. Phase 3's value is much smaller and its risk much
higher; deciding to stop is a legitimate outcome and should be an explicit checkpoint,
not a thing that happens by attrition.

**Phase 3 — effect functions (optional, higher risk)**

12. `refactor(ui): extract the speed-limit and camera-warning effects` — 1018–1083,
    1134–1230, and the five state declarations at 528–533/1141 → `MapSpeedEffects.kt`.
    Best-value phase-3 commit: it is the only one that removes state from `MapScreen`.
    Needs a manual drive-test (or a mocked `TripTrackingService.lastFix`) — the section
    logic at 1174–1230 has no test coverage and four exit conditions.
13. `refactor(ui): extract the camera parking, target and follow effects` — 665–712,
    1232–1333 → `MapCameraEffects.kt`. **The `rememberUpdatedState` commit.** Review
    line-by-line for stale captures.
14. `refactor(ui): extract the session and overlay effects` — 566–585, 598–663,
    736–801, 872–932, 1085–1132 → `MapSessionEffects.kt` + `MapOverlayEffects.kt`.
    Two commits if the diff is unpleasant.

Steps 12–14 each need manual verification the test suite cannot give: the project's
own memory note is explicit that framework-level work here is verified by manual repro,
because there is no Robolectric.

---

## Pros

1. **Behaviour-preservation is provable by inspection.** For steps 1–11, the diff is a
   cut, a paste, and a keyword. There is no argument to have about whether recomposition
   timing changed, because nothing that determines recomposition timing moved. On a file
   that contains a per-frame camera loop (1265–1333), a 1 Hz fix pipeline, and a convoy
   consensus protocol whose failure mode is "splits a convoy across two destinations"
   (856–857) — that guarantee is worth a great deal.
2. **It is the only proposal that can be shipped in pieces and abandoned halfway
   without leaving a mess.** Stopping after step 3, or step 11, leaves a coherent
   codebase. A ViewModel migration stopped halfway leaves two sources of truth.
3. **Zero diff at every cross-file call site.** `RoutesScreen.kt`, `HistoryScreen.kt`,
   `RouteEditorScreen.kt`, `MainActivity.kt:223`, and `car/` are untouched, because
   Kotlin resolves same-package top-level declarations without imports. Nothing outside
   `ui/` needs to be re-reviewed or re-tested.
4. **It matches conventions the repo already chose**, rather than importing new ones:
   `MapLibreMap.kt`, `Navigation.kt`, `GlassSurface.kt`, `Format.kt`, `AppBar.kt`,
   `Theme.kt` are exactly this — small topic files in one package, `internal` where a
   sibling needs them, `private` otherwise. `HistoryScreen.kt:72`/`:120` already use
   `internal`-for-testability with a matching `app/src/test/java/com/jellemax/detour/ui/`
   test. This proposal introduces no idea the codebase has not already committed to.
5. **It unlocks five real unit tests today** (see the testability section).
6. **It sharpens `private`.** Twelve helpers become private to a small topical file
   instead of nominally private to a 3,193-line one.
7. **Effectively zero learning cost.** No new concept enters the project. A contributor
   who can read the current file can read the split one on day one.
8. **It is a strict prerequisite for every other proposal.** Any state-holder or
   ViewModel refactor still has to move these 1,355 presentational lines somewhere.
   Doing that move separately, with a provable diff, makes the subsequent
   state refactor's diff small enough to actually review. Even a reviewer who wants
   proposal 2 or 3 should want phases 1–2 first.

---

## Cons and risks

1. **It does not reduce complexity — it relocates it.** `MapScreen` still owns ~64
   pieces of state, 36 effects, and 8 closures over `var`s. Every coupling in the
   dependency table above survives. **The hardest thing in the file (the state graph)
   is untouched by design.** If the reviewer's actual complaint is "I cannot reason
   about this composable", this proposal does not answer it.
2. **The biggest file stays big.** ~1,565 after phase 2, ~1,050–1,200 after phase 3.
   Still the largest file in `ui/` by a wide margin (`SettingsScreen.kt` is 1,199).
   "We split MapScreen" would be a misleading summary of the outcome.
3. **~200 lines of net growth** from duplicated import blocks across 11–15 files. Real,
   though trivial.
4. **`git blame` degradation.** Mitigated by pure-move commits and `blame -C`, but any
   future reader running plain `git blame` on `SpinSheet` will see one refactor commit
   instead of the history that shaped a 180-line composable. That history is genuinely
   valuable on this file.
5. **A three-week merge-conflict window.** Fourteen commits touching the same file
   serially. Any in-flight feature branch that edits `MapScreen.kt` will conflict with
   most of them, and cut-and-paste conflicts resolve badly. Mitigation: land phases 1–2
   in one short burst, not spread over sprints.
6. **Phase 3's `rememberUpdatedState` hazard** (see above) is a silent-bug class with no
   compiler help and no test coverage in this project. This is my proposal's most
   serious defect and I will not dress it up: three of the phase-3 effect extractions
   (669–694, 940–968, and the `LaunchedEffect(Unit)` collectors) can be shipped broken
   and pass every check the repo has.
7. **Phase 3's parameter-list explosion.** `MapOverlayRenderEffect` at 15 parameters
   is worse to read than the closure it replaced. `CameraTargetEffect`'s
   "null-bearing-means-keep" contract is a genuine regression in expressiveness. A
   reviewer is entitled to say phase 3 makes the code worse, and on those two functions
   specifically, they would be right.
8. **It can be mistaken for a fix.** The most likely real-world failure of this proposal
   is not technical: it is that the file drops to 1,565 lines, everyone feels better,
   and the actual problem — `spin()`, the effect graph, the untestable state — never
   gets addressed. If phases 1–2 land, someone must write down explicitly that the job
   is not done.

---

## Effect on…

### Unit testability (plain JUnit4, Android-free logic only, no Robolectric)

The `data` layer lives in a KMP module (`shared/src/commonMain/kotlin/…`), so `LatLon`,
`TravelMode`, `Settings.NavApp` (`Settings.kt:21`), `SpeedCameras.Section`
(`SpeedCameras.kt:39`), `RoadRoulette.distanceMeters` (`RoadRoulette.kt:424`) and
`RoadRoulette.withinWedge` (`RoadRoulette.kt:101`) are all Android-free. That makes the
following newly testable in `app/src/test/java/com/jellemax/detour/ui/`, using the same
JUnit4 setup as `TripTraceMatchingTest.kt`:

| Function | Source | Why it's worth testing |
|---|---|---|
| `sectionExitGate` | 290–314 | Directional gating. The KDoc at 295–298 records a shipped bug ("used to start a measurement as you left a section") that a table-driven test would have caught. Highest-value test in the list. |
| `leadingSpinIndex` | 357–367 | Pure. Its whole point (357–360) is that every device must resolve identical votes to an identical leader — a determinism property, which is exactly what a test asserts well. |
| `navAppUsableDirectly` | 2151–2164 | Pure. 5 enum values × route/origin nullability = 20 cases, currently zero covered. |
| `GroupSpin.asRouteCandidates` | 322–343 | Pure wire→domain mapping, including the null-`distanceM` branch at 333. |
| `asSpinCandidates` | 345–355 | Pure domain→wire mapping; round-trips with the above. |
| `smoothBearing` | 221–230 | Pure. The 0/360 wrap at 226–228 is exactly the kind of thing that is wrong once and then never again. |

Six pure functions, ~90 lines of production code, currently unreachable from a test
purely because they are `private` in a file that also contains a `MapView`.

**Honest counterweight:** that is the *entire* testability gain. `MapScreen` itself
remains 100% untestable. `spin()` (1392–1513) — the function most likely to be wrong,
with the most branches — remains untestable, because it is a closure. The section-average
state machine at 1174–1230 (four exit conditions: `reachedEnd`, `overshot`, `timedOut`,
plus the entry gate) remains untestable even after `sectionExitGate` is extracted,
because the accumulator lives in a coroutine inside a composable. Phase 3 does not
change that: an effect function is still a `@Composable`, so it still needs a Compose
test runtime the project does not have. **This proposal moves testability from 0% to
about 3% of the file.**

### Recomposition / performance

**Phases 1–2: exactly zero effect.** Every moved composable was already a separate
`@Composable` function with its own restart scope; a function's recomposition
behaviour does not depend on which file it is declared in. Same call sites, same
parameters, same skippability, same stability inference.

**Phase 3: also essentially zero, in both directions.** Worth being precise, because
this is where a reviewer might expect a win that isn't there:

- The four effect-private speed-limit vars (528–533) are only read inside a coroutine
  body, which is not a composition-scope read, so moving them changes no invalidation.
- The state that *does* drive recomposition stays put. `displaySpeedKmh` (550) is
  written every frame at 1259 and read at 1641/1643 inside the `Scaffold` content
  lambda — a non-inline lambda, hence its own restart scope — so **the entire view tree
  from 1541 to 1795 recomposes at display refresh rate whenever the vehicle is moving,
  and this proposal does not change that.** Likewise `camTarget` (read at 1270 in
  `MapScreen`'s own body) invalidates `MapScreen`'s root scope once per GPS fix.
- Those two are the file's real recomposition costs, and fixing them means moving
  `displaySpeedKmh` into whatever composable draws the HUD and `camTarget` out of the
  root body — both of which are state relocations, i.e. out of scope here.

There is one phase-3 move that *would* help and stays within the letter of the pattern:
wrapping `SpeedHud` in a `@Composable internal fun SpeedHudSlot(liveFix, …)` that owns
`displaySpeedKmh` itself. I am flagging it rather than proposing it, because it is the
first step where "no behaviour change" stops being provable by inspection.

### Android Auto code reuse (`app/src/main/java/com/jellemax/detour/car/`)

**No benefit whatsoever, and this is the proposal's clearest structural failure.**

`car/` is `androidx.car.app` `Screen`s and a `SurfaceCallback` renderer — no Compose.
It can only consume plain functions, objects and constants. Today it already imports
three things from `ui/`: `MapOverlays`, `openFreeMapStyleUrl`, `setCamera`
(`car/CarMapRenderer.kt:27–29`) and `formatDistanceKm` (`car/SpinScreen.kt:39`) — all
from the already-split `MapLibreMap.kt` and `Format.kt`. So the mechanism works; there
is simply almost nothing in `MapScreen.kt` for it to reach.

The duplication is measurable and this proposal removes essentially none of it:

| Logic | MapScreen.kt | Duplicated in car/ | Fixed by this proposal? |
|---|---|---|---|
| `smoothBearing` | 221–230 | `car/CarMapRenderer.kt:470` | **Yes** — after step 2 it is `internal` in `MapCameraTuning.kt`; `car/`'s copy can be deleted. Note the signatures differ (`Float?` vs `Float`), so it is a small real edit, not a pure delete. |
| `CAM_POS_TAU = 0.35` | 236 | `car/CarMapRenderer.kt:53` | **Yes** — same. |
| Camera-ahead warning + `ToneGenerator` chime | 1134–1167 | `car/NavScreen.kt:378–410` | **No.** Both are stateful loops in incompatible hosts. |
| Speed-camera prefetch throttle | 1058–1083 | `car/NavScreen.kt:378–392` | **No.** |
| Speed-limit prefetch + snap | 1024–1056 | `car/SpinScreen.kt:279–289` | **No.** |
| Arrival + reroute policy | 1345–1390 | `car/NavScreen.kt:242–280` (its own comment says "Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect") | **No.** |
| Spin candidate selection | 1469–1495 | `car/SpinScreen.kt:332` | Partly — both already call the shared `pickCandidate`. |

So: two constants and one function deduplicated, five substantive duplications left
standing. The reason is structural and worth naming, because it argues for a different
proposal: **the phone's copies of that logic are welded to `remember`ed state inside a
composable, and the car's copies are welded to mutable fields inside a `Screen`.**
Sharing them requires a plain, host-agnostic object holding that state — which is
precisely the state holder this proposal declines to build. If Android Auto
deduplication is the primary goal, **this is the wrong proposal**, and I would rather
say so than pretend otherwise.

### Reviewability of future diffs

Clear win, and probably the most durable one.

- **Today:** any change to the spin sheet's slider produces a diff whose header says
  `MapScreen.kt`. Reviewers cannot tell from the file list whether a PR touches
  navigation, the camera loop, or a button's padding. GitHub collapses the file by
  default at this size.
- **After:** a padding change is `SpinCards.kt`, a nav-app change is `NavAppLaunch.kt`,
  a camera-tuning change is `MapCameraTuning.kt`. `CODEOWNERS`-style routing, blame,
  and "does this PR touch anything risky?" all become answerable from the file list.
- **Merge conflicts drop permanently** once the migration window closes: two feature
  branches that both "touch the map" currently collide in one file, and after the
  split usually will not.
- **Caveat:** any change that adds a new piece of screen state still lands in
  `MapScreen.kt` and still produces a diff against a 1,000+ line composable. The
  highest-risk changes remain the least reviewable ones. The split improves the
  reviewability of the *easy* changes.

---

## What this proposal explicitly does NOT solve

1. **The state graph.** ~64 state declarations in one composable, unchanged. Adding a
   65th is exactly as easy after as before, and nothing in the codebase resists it.
2. **`spin()`** — 122 lines, 8 writes, two code paths, untestable. Untouched.
3. **The 36-effect dependency web.** Which effect writes `speedCameras`, and which two
   read it, is still something you learn by grepping. Phase 3 makes the *writes*
   visible in signatures but does not reduce their number.
4. **Testability of anything stateful.** 0% before, 0% after. Six pure helpers is the
   entire gain.
5. **Android Auto duplication.** Five substantive duplications survive; see above.
6. **The per-frame recomposition of the whole view tree** driven by `displaySpeedKmh`
   (550/1259/1641).
7. **Configuration-change fragility.** `SpinResultHolder` (383–385) exists purely
   because `remember` does not survive activity recreation, and only 5 of ~64 state
   values use `rememberSaveable` (449, 450, 462, 463, 526). Rotating the device still
   drops the route, the nav session, and the convoy vote. This proposal moves that
   workaround into its own file and changes nothing about it.
8. **The screen's testable seam.** There is still no way to drive `MapScreen` from a
   test, a debug menu, or the car, because there is no object representing "the map's
   state" that isn't a composition.
9. **The file count / navigation trade.** 11–15 files where there was 1. For a
   contributor who currently finds things with one `Ctrl-F`, this is a real if minor
   regression until they learn the layout.

---

## Honest verdict

**This is the right choice when:**

- The immediate goal is *reviewability and blast-radius control*, not testability or
  reuse. It delivers that fully and cheaply.
- The team wants to land something this week with essentially no regression risk. The
  app has a per-frame camera loop, a live GPS pipeline, a distributed convoy consensus
  rule, and a Play-policy-mandated permission flow (1998–2001) — all in this file, none
  of it covered by tests, none of it verifiable without a phone. A refactor whose
  correctness argument is "these bytes moved" is worth a lot in that environment.
- It is being used as **step one of a larger plan.** This is its strongest framing. Any
  state-holder or ViewModel proposal must relocate the same 1,355 presentational lines;
  doing that first, as a provable move, shrinks the subsequent state refactor's diff
  from ~3,200 lines to ~1,500 and makes it genuinely reviewable. I would argue for
  phases 1–2 even in a world where proposal 2 or 3 wins outright.
- The team is small and Compose-experienced, and the cost of a new abstraction
  (a state-holder convention nobody else in the codebase uses) is judged higher than
  the cost of a long composable.

**This is the wrong choice when:**

- The goal is **testability**. A 0% → 3% move does not justify a 14-commit migration on
  its own. If someone wants `spin()`'s error taxonomy or the section state machine under
  test, they need state extracted from the composition, and this proposal explicitly
  refuses to do that.
- The goal is **Android Auto deduplication**. Two constants and one function is not a
  result. `car/NavScreen.kt:242`'s comment ("Same arrival/reroute policy as
  MapScreen.kt's navigating LaunchedEffect") is a standing invitation to a shared
  plain-Kotlin nav-session object, and this proposal declines it.
- The complaint is "I can't reason about `MapScreen`". After phase 2 it is one
  1,565-line composable instead of one 3,193-line file — genuinely better, but the part
  that is hard to reason about is 100% intact.
- The team is willing to do phase 3 but not willing to review it line-by-line for stale
  `rememberUpdatedState` captures. **Phase 3 without that discipline is net-negative**
  and should not be attempted; take phases 1–2 and stop.

**My own recommendation, stated plainly:** take phases 1–2 (steps 1–11) unconditionally
— they are close to free and they are a prerequisite for every alternative. Take step 12
(`MapSpeedEffects.kt`) because it is the one phase-3 commit that removes state rather
than just relocating code. Treat steps 13–14 as optional and hold them until someone
has a concrete reason. And do not let the resulting 1,565-line file be recorded as
"MapScreen refactored" — record it as "MapScreen's presentational layer separated; its
state layer is unchanged and is the next problem."
