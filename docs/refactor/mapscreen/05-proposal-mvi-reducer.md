# Proposal 5 — MVI / reducer state machine

**Target:** `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`, 3193 lines.
**Pattern championed:** immutable state + sealed actions + a pure `reduce(state, action) -> (state, effects)` + an impure effect runner.

Everything below cites `MapScreen.kt:NNN` (and neighbours by path) so every claim is checkable
against the tree at commit `07fe490`.

---

## Cross-cutting concerns I identify in MapScreen.kt

These are the things that are *not* localisable to one composable — the reason the file resists a
plain "cut it into UI chunks" split. Ordered by how much damage they do.

### C1 — The GPS fix is fanned out to ten independent consumers

`TripTrackingService.lastFix` is a 1 Hz `StateFlow<Fix?>`. MapScreen consumes it **five times as a
raw `.collect`** and **five more times as a Compose key or `rememberUpdatedState`**:

| site | lines | what it does with the fix |
|---|---|---|
| `LaunchedEffect(liveFix)` | 555–559 | accuracy filter → `myLocation` |
| `.collect` (camera resume) | 700–712 | speed + quiet-period → un-park camera |
| `.collect` (ambient limit) | 1024–1056 | prefetch throttle + snap + 3-miss hysteresis |
| `.collect` (camera prefetch) | 1062–1083 | Overpass prefetch for cameras/sections |
| `.collect` (chime) | 1145–1167 | wedge test + over-limit test + one-shot latch |
| `.collect` (trajectcontrole) | 1174–1230 | enter / integrate / exit state machine |
| `LaunchedEffect(liveFix, defaultZoom)` | 1236–1245 | camera targets |
| `rememberUpdatedState` | 1250 | speedometer ease target |
| `LaunchedEffect(navigating, liveFix)` | 1338–1342 | BLE speed push |
| `LaunchedEffect(navigating, liveFix, route)` | 1345–1390 | nav progress / arrival / reroute |

Each of the five `.collect` blocks carries its **own private mutable locals** that nothing outside
can see or assert on: `warnedAt` (1146), `center` / `lastFetchMs` (1063–1064), and
`active` / `exitGate` / `entryMs` / `accMeters` / `last` (1175–1179). That state is invisible to the
debugger's variables pane, to any test, and to the next reader.

Worse, three of these are keyed effects that **tear down and re-subscribe** the flow when an
unrelated flag flips — e.g. 700 re-subscribes on every change of `camSuspended`, `spinning`,
`candidates.isEmpty()` and `spinOffer == null`.

### C2 — Camera authority: three booleans and a timestamp, written from nine places

`followMe` (521), `camSuspended` (522), `lastGestureMs` (523), collapsed into two derived
predicates at 551 (`cameraActive`) and 553 (`following`). Written at:

- 674–689 — touch listener parks on drag/pinch, refreshes the quiet clock on up
- 700–712 — auto-resume when driving again, gated on four unrelated flags
- 810–813 — `choose()` parks and buys a grace period
- 837–838 — `commitSpinCandidate()` does the same, duplicated
- 987 — `startNavigation()` un-parks
- 1403 — `spin()` parks
- 1586–1589 — the follow button toggles, with an asymmetric "on also clears suspension"
- 1678–1679 — a saved-place chip parks
- 1829–1830 — a search result parks

Nine sites, three of them byte-identical pairs. There is no single place to read "when does the map
follow me?"

### C3 — Destination / route / candidates written from thirteen places

`destination`, `destinationName`, `route`, `candidates` (454–464) are mutated at 467–469 (mirror to
the holder), 804–808, 831–834, 952–954, 1005, 1379, 1462–1464, 1490, 1520–1523, 1675–1677, 1718,
1826–1828. Two of those are inside async catch/finally blocks. The invariant "`candidates`
non-empty ⇒ no committed destination" is maintained by convention only.

### C4 — The convoy group-spin protocol

Wire conversion at 329–355; deterministic tie-break at 361–367; display selection at 823; the two
commit paths at 803–840; the round-resolution rule at 858–870, preceded by **sixteen lines of prose
(842–857)** explaining why naive per-device tallying is wrong. UI half at 1713–1739. Mode-switch
invalidation at 1526. This is a distributed consensus rule with a written correctness argument and
**zero tests**.

### C5 — "Prefetch when near the edge of what I hold, throttled" — written three times

1030–1042 (speed-limit ways), 1066–1082 (cameras/sections), 1106–1119 (circle fixes). Same shape,
three different throttle constants (10 s / 15 s / 120 s), three different failure policies.

### C6 — Overlay push

872–902 (the big render), 907–909 (icon), 913–915 (route colour), 921–932 (fog, split in two for a
documented perf reason), 1087–1089 (cameras), 1094–1096 (friends), 1120–1122 (circle members),
1128–1132 (fog peer holes). Eight effects, all of the form "when X changes, push X at MapOverlays".

### C7 — One `error: String?` slot, twelve writers, one reader

Written at 728, 731, 778, 984, 991, 998, 1011, 1394, 1459, 1498–1504, 1508. Read once, at 1771,
inside `SpinSheet`. So an error raised while the dock is collapsed is invisible, and two errors race.

### C8 — Permission + lifecycle plumbing

601–625 (UI-visible hint + PTT safety), 714–734, 738–801, 1798–1808. Genuinely Android; will not
move into any reducer.

### C9 — Duplication into `car/`, in the same Gradle module

- `CarMapRenderer.kt:53–69` copies `MapScreen.kt:236–255` verbatim (six tuning constants).
- `CarMapRenderer.kt:383–431` is a re-implementation of `MapScreen.kt:1294–1332`.
- `CarMapRenderer.kt:470–475` is a copy of `MapScreen.kt:224–230` (`smoothBearing`), the doc comment
  even says "Same as the phone map's".
- `CarMapRenderer.kt:78` copies `CIRCLE_FIX_POLL_MS` and says so.
- `NavScreen.kt:378–415` re-implements the camera warn latch of `MapScreen.kt:1145–1167`.

### C10 — Process-scoped handoff to another screen

`SpinResult` / `SpinResultHolder` / `seedRouteNavigation` (369–414), hydrated at 453, mirrored at
467–469, and written from outside by `RoutesScreen.kt:202`. Any state refactor has to preserve this
seam exactly.

### C11 — And, off the critical path but worth naming

208 lines of imports (1–208); a 1419-line composable body (419–1837) of which ~309 lines are the UI
tree (1529–1837); `val TravelMode.icon` (213–219) is public API used by `RoutesScreen.kt:303` and
`HistoryScreen.kt:317` and cannot simply be made private during a split.

---

## The implicit state machines already present in the code

This is the evidence base. MapScreen already contains state machines; they are just written as
loose booleans and closure-local `var`s instead of as types.

### SM1 — Camera authority

**States** (encoded as `followMe` × `camSuspended`, collapsed by 551/553):

| state | predicate | camera behaviour |
|---|---|---|
| `Following` | `followMe && !camSuspended` | frame loop eases to the fix (1271–1333) |
| `Parked` | `camSuspended` | loop exits, levels to north-up (1273–1278) |
| `Free` | `!followMe && !navigating` | user drives the camera |
| `NavDriven` | `navigating && !camSuspended` | loop runs even with `followMe == false` (551) |

**Transitions**

| trigger | from → to | line |
|---|---|---|
| drag past touch slop | Following → Parked | 686 |
| second finger down | Following → Parked | 684 |
| pointer up while parked | Parked → Parked (reset quiet clock) | 689 |
| fix with `speed ≥ 3 m/s` and `now − lastGestureMs > 8 s` | Parked → Following | 706–710 |
| …but blocked while spinning / candidates shown / offer open | Parked → Parked | 701 |
| spin starts | Following → Parked | 1403 |
| candidate chosen (local) | → Parked + fit-to-points | 810–814 |
| candidate committed (convoy) | → Parked + fit-to-points | 837–839 |
| `startNavigation()` | Parked → NavDriven | 987 |
| `stopNavigation()` | NavDriven → (bearing target cleared) | 973 |
| follow button, currently following | Following → Free | 1587 |
| follow button, not following | Free/Parked → Following | 1588 |
| saved-place chip picked | → Parked + animate | 1678–1681 |
| search result picked | → Parked + animate | 1829–1832 |

Fourteen documented transitions over three variables and nine files-worth-of-scattered write sites.
**This is the single strongest MVI case in the file.**

### SM2 — Spin lifecycle

**States:** `Idle` → `Spinning` (1399) → `Choosing` (`candidates` non-empty, 1490) → `Committed`
(804–808) | `RoundTripFound` (`route` set, `destination` null, 1462–1464) | `Failed` (`error` set,
1496–1508). Cancellation is a fourth exit (1751 → `spinJob?.cancel()` → `finally` at 1509–1511).

**Transitions:** spin (1392–1398 guard on `myLocation`), server-loop success (1440), server-loop
total failure → Overpass fallback with a preserved `serverError` (1449–1461), POI success (1485–1494),
timeout (1496–1504), reroll (1718 — clears then re-enters `Spinning`), cancel (1719–1722), travel-mode
switch resets everything (1515–1527).

Note the **fallback state is a real state, not an error**: 1449–1461 produces a usable route *and* an
error string simultaneously. That combination is expressible in a sealed state and inexpressible in
the current `spinning: Boolean` + `error: String?` pair without ambiguity.

### SM3 — Group-spin vote round (distributed)

**States:** `None` → `LocalOnly` (`candidates` set, `spinOffer == null`) → `SharedVoting`
(`spinOffer.candidates.size > 1`) → `Resolved` (`spinOffer.candidates.size == 1`) → committed.

**Transitions:**

| trigger | line |
|---|---|
| share local candidates | 1726–1728 |
| receive an offer (peer) | `ConvoyLiveClient.kt:611` |
| vote by pin tap | 965 |
| vote by row tap | 1716 |
| sharer sees all live peers voted → broadcasts leader as a 1-candidate offer | 858–869 |
| sharer forces "Go with the lead" | 1732–1738 |
| any device sees a 1-candidate offer → commits | 860–863 |
| cancel | 1719–1722 |
| travel-mode switch invalidates the round | 1526 |

**Tie-break rule** (361–367): counts, strict `>`, lowest index wins — chosen specifically so every
device resolves identically. The comment at 842–857 documents *why* independent tallying is wrong:
`convoyPeers` prunes at 20 s (`ConvoyLiveClient.kt:403`), so two phones can disagree on the expected
voter set. **That is a pure function of `(votes, candidateCount, peers, myUsername, fromMe)` and it
is currently untested and untestable.**

### SM4 — Navigation session

**States:** `Off` → `Fetching` (`rerouting = true`, 1002) → `Active` (`navigating = true`, 1009) →
`Rerouting` (1375–1388) → `Arrived` (1360–1364) → `Off`.
Round trips take a different entry path with no fetch at all (994–1000).
Guards: 40 m remaining + 60 m on-route for arrival (1360–1361); 60 m off-route + 15 s cooldown for
reroute (1372–1373). Exit side effects at 970–980.

### SM5 — Trajectcontrole section tracking

**States:** `Outside` / `Inside(section, exitGate, entryMs, accMeters, last)` — all five held as
closure locals at 1175–1179, so **nothing outside the effect can observe them**; only the two derived
outputs `sectionAvgKmh` / `sectionLimitKmh` (538–539) escape.

**Transitions:**

| trigger | line |
|---|---|
| fix with `speed > 2 m/s`, at a gate, far end within a 75° wedge | 1188–1206 (helper 300–314) |
| nearest match wins when two sections share a gate | 1195–1197 |
| accumulate distance, recompute average once `> 20 m` | 1208–1213 |
| exit: reached far gate after `> 150 m` | 1217–1218 |
| exit: overshot `span × 1.4 + 400 m` | 1219 |
| exit: 30 minutes elapsed | 1220 |

The doc comment at 294–298 records a **shipped bug** this machine already had ("used to start a
measurement as you left a section, which is what put an average on screen after the trajectcontrole
instead of during it"). A bug of exactly the class a reducer test catches, in a machine that has no
test.

### SM6 — Speed-camera warning latch

**States:** `Armed` (`warnedAt == null`) / `Warned(at)`. Arm on no camera ahead (1156–1159), fire +
latch when over limit (1162–1165), silent when the limit is unknown (1160–1161). Duplicated at
`NavScreen.kt:397–415`.

### SM7 — Ambient speed-limit snap with miss hysteresis

`speedLimitMisses` 0→3 before clearing the sign (1050–1054); prefetch throttle 1030–1042; centre
tracking 1039–1041. Three-state hysteresis wearing an `Int`.

### SM8 — Bottom-card slot

417 + 1689–1694. **This one is already right**: a pure `when` over four derived conditions, with the
result an enum. It is the shape the rest of the file should be, and it is 6 lines. Worth naming
because it shows the codebase is not hostile to the idea — it already does it where it is cheap.

### SM9 — Prefetch throttles

Three copies (C5). Each is a 2-state machine (`Fresh` / `Stale`) plus a cooldown clock.

---

## The pattern, stated precisely

### State

Not one god-object. **A record of independent slices**, each of which is the state of one machine
above:

```
MapState
├── camera : CameraAuthority   (SM1)
├── spin   : SpinRound         (SM2 + SM3, fused — see below)
├── nav    : NavSession        (SM4)
├── road   : RoadInfo          (SM5 + SM6 + SM7 + SM9)
└── trip   : TripParams        (mode, radiusKm, minRadiusKm, poiKind, directionDeg)
```

SM2 and SM3 are one machine, not two: `candidates`, `spinOffer`, `spinVotes`, `destination`,
`destinationName` and `route` all move together, and the code already treats them as one — see the
merged `displayCandidates` at 823 and the paired commit functions at 804–840.

Every slice is an immutable `data class`. No Compose types, no Android types, no `Context`, no
`MapLibreMap`, no `Job`.

### Actions

One sealed hierarchy per slice, plus a shared `FixArrived`. An action is a **fact that happened**
("the user dragged the map", "a fix arrived", "the routing server answered"), never a command
("park the camera").

### Reducer

```kotlin
fun reduce(state: MapState, action: MapAction): Transition
data class Transition(val state: MapState, val effects: List<MapEffect> = emptyList())
```

Total, pure, synchronous, Android-free. Same input → same output, always. No clock reads inside —
`now` arrives as a field on the action (this is what makes the 8 s quiet period at 707, the 15 s
reroute cooldown at 1373 and the 30-minute section timeout at 1220 testable without sleeping).

### Effects

A sealed type describing I/O to perform, not performed by the reducer:

```
MapEffect
├── FitCameraTo(points)            ← 814, 839, 1468, 1493
├── AnimateCameraTo(point, zoom)   ← 1680, 1831
├── FetchRoute(from, to, profile)  ← 1005, 1379
├── FetchSpeedLimitWays(at)        ← 1037
├── FetchCameras(at)               ← 1075
├── PollCircleFixes(username)      ← 1113
├── Chime                          ← 1163
├── Haptic                         ← 1467, 1491
├── PushNavRelay(progress, kmh)    ← 1356–1357
├── ShareSpinOffer(candidates)     ← 1727, 1734, 1867
├── ClearSpinOffer                 ← 835, 1526, 1721
└── StartTracking(dest) / StopTracking
```

The **effect runner** owns everything the reducer refuses to touch: `Context`, `CoroutineScope`,
`MapLibreMap`, `MapOverlays`, `ToneGenerator`, `TripTrackingService`, `NavRelay`, `BleNavServer`,
intents (3150–3193).

### What deliberately stays outside the loop

This is the part a reviewer should press hardest on, so it is stated first and plainly.

**1. Per-frame camera easing (1271–1333) does NOT go through the reducer.** Dispatching an action
per vsync would allocate a state object 60×/s and, if state is Compose-observed, invalidate the
screen 60×/s over a GL surface. The loop stays exactly as it is; the *only* change is that it reads
`state.camera.active` and `state.camera.target` (through `rememberUpdatedState`) instead of the local
`cameraActive` / `camTarget` / `camTargetBearing` / `camTargetZoom` vars. The eased values `lat`,
`lon`, `bearing`, `zoom` and the four `applied*` guards (1281–1292) remain loop-locals and never
enter state. Same for the speedometer ease (1251–1263).

**2. MapLibre imperative callbacks (940–968) translate, they do not decide.** The long-click handler
becomes `dispatch(MapLongPressed(LatLon(...), now))`. The click handler keeps its hit test —
`map.projection.toScreenLocation` and `queryRenderedFeatures` (959–962) need the live projection and
cannot be a pure function of state — and dispatches the *result*:
`dispatch(CandidatePinTapped(idx, now))`. The decision "vote or commit?" (965) moves into the
reducer, where it belongs, because that decision is SM3's.

**3. The GPS stream is collected once.** One `TripTrackingService.lastFix.collect` replaces the five
at 704, 1026, 1065, 1147, 1180, and dispatches one `FixArrived(fix, now)`. The fan-out to SM1/SM5/
SM6/SM7 happens inside the reducer, where the interactions between them are visible in one place
instead of five.

**4. `SpinResultHolder` (369–414) survives untouched.** It becomes the reducer's hydration source at
composition and the effect runner's mirror target, preserving `RoutesScreen.kt:202` exactly.

---

## Proposed file layout

New package `com.jellemax.detour.map` (**not** `ui.map` — the reducer is not UI) alongside a
`ui/map/` package for the composables. All in `:app`, which is what makes `car/` reuse free.

### The full-MVI layout

| path | symbols moved / created | source lines from MapScreen.kt | ~LOC |
|---|---|---|---|
| `app/src/main/java/com/jellemax/detour/map/MapTuning.kt` | `smoothBearing`, `sectionExitGate`, `leadingSpinIndex`, `CANDIDATE_COLORS`, `CAM_*`, `SPEED_*`, `SECTION_*`, `FIT_PADDING_PX`, `CURVY_CANDIDATES`, `CIRCLE_FIX_POLL_MS` | 224–230, 232–288, 300–314, 319, 361–367 | 110 |
| `app/src/main/java/com/jellemax/detour/map/MapState.kt` | `MapState`, `CameraAuthority`, `SpinRound`, `NavSession`, `RoadInfo`, `TripParams`, `BottomCard` | 417, 449–464, 514–553 (as fields), plus the closure locals at 1063–1064, 1146, 1175–1179 | 190 |
| `app/src/main/java/com/jellemax/detour/map/MapAction.kt` | sealed `MapAction` + nested groups | *new* — one per write site enumerated in C2/C3 | 150 |
| `app/src/main/java/com/jellemax/detour/map/MapEffect.kt` | sealed `MapEffect` | *new* | 90 |
| `app/src/main/java/com/jellemax/detour/map/MapReducer.kt` | `reduce`, `reduceCamera`, `reduceSpin`, `reduceNav`, `reduceRoad` | decision halves of 555–564, 700–712, 803–870, 982–1016, 1024–1056, 1062–1083, 1145–1167, 1169–1230, 1232–1245, 1345–1390, 1515–1527, 1689–1694 | 430 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapEffectRunner.kt` | `MapEffectRunner`, `rememberMapStore` | I/O halves of 714–734, 1003–1015, 1037, 1075, 1113, 1163, 1356–1357, 1377–1388, 1392–1513 | 330 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapScreen.kt` | `MapScreen` shell: store, flow→action wiring, the UI tree | 419–448, 555–559, 566–590, 1529–1837 | 400 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapCameraLoop.kt` | frame loop, speed ease, touch-park listener | 665–694, 1247–1333 | 130 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapOverlaySync.kt` | the eight overlay/fog push effects | 872–932, 1087–1096, 1120–1132 | 110 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapLifecycle.kt` | MapView lifecycle, style reload, permission launchers, disclosure | 592–663, 736–801, 1798–1808 | 155 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapChrome.kt` | `MapTopChrome`, `SearchPill`, `ConvoyPill`, `GlassRailButton`, `ModeBar`, `EndTripButton`, `PushToTalkButton` | 2683–2954 | 275 |
| `app/src/main/java/com/jellemax/detour/ui/map/SpinCards.kt` | `Pill`, `SegmentedPillRow`, `ScrollingPillRow`, `SpinDock`, `SpinSheet`, `CandidatesCard` | 2057–2121, 2281–2681 | 470 |
| `app/src/main/java/com/jellemax/detour/ui/map/NavChrome.kt` | `launchNav`, `navAppUsableDirectly`, `handleGoTap`, `NavMenuItems`, `NavIconButton`, `NavButton`, `navigateRoundTrip/GoogleMaps/Waze/Geo` | 2123–2279, 3048–3099, 3150–3193 | 265 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapHud.kt` | `SpeedHud`, `SectionAverageChip`, `ActiveTripCard`, `StatItem` | 2956–3046, 3101–3148 | 195 |
| `app/src/main/java/com/jellemax/detour/ui/map/MapDialogs.kt` | `SearchDialog`, `ShortcutChips`, `BackgroundLocationDisclosure`, `SavePinDialog` | 1839–2055 | 220 |
| `app/src/main/java/com/jellemax/detour/ui/SpinHandoff.kt` | `SpinResult`, `SpinResultHolder`, `seedRouteNavigation` — kept in `ui` and `internal` so `RoutesScreen.kt:202` compiles unchanged | 369–414 | 50 |
| `app/src/main/java/com/jellemax/detour/ui/TravelModeIcon.kt` | `val TravelMode.icon` — public, used by `RoutesScreen.kt:303` and `HistoryScreen.kt:317` | 210–219 | 15 |

**Deleted elsewhere:** `CarMapRenderer.kt:53–69` and `470–475` (→ `MapTuning.kt`),
`NavScreen.kt:397–415` (→ the SM6 reducer). ≈ 45 lines removed from `car/`.

**Arithmetic, stated honestly.** 3193 → **≈ 3585 lines across 17 files**, minus ~45 in `car/`.
Largest file 470. Median file ~190. `MapScreen.kt` itself: 3193 → ~400.
**Net LOC change: roughly +350, or +11%.** The `MapAction` + `MapEffect` + `MapState` trio (~430
lines) is code with no counterpart today. That is the bill.

### The targeted-MVI layout (what I actually recommend — see below)

Keep every `ui/map/*` row above (they are pure mechanical moves), and replace the four
`map/Map{State,Action,Effect,Reducer}.kt` rows with three self-contained machines:

| path | machine | ~LOC (impl + actions + state) |
|---|---|---|
| `app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt` | SM1 | 110 |
| `app/src/main/java/com/jellemax/detour/map/SpinRound.kt` | SM2 + SM3 | 190 |
| `app/src/main/java/com/jellemax/detour/map/SectionTracker.kt` | SM5 | 95 |
| `app/src/main/java/com/jellemax/detour/map/MapTuning.kt` | shared constants + pure helpers | 110 |

3193 → **≈ 3300 across 15 files**, minus 45 in `car/`. Net ≈ +60 lines. `MapScreen.kt` → ~640.

---

## Representative Kotlin declarations

Real names from the current file. Illustrative, not final.

### State

```kotlin
// app/src/main/java/com/jellemax/detour/map/CameraAuthority.kt

/** SM1. Replaces MapScreen.kt:521-523 (`followMe`, `camSuspended`, `lastGestureMs`)
 *  and the two derived predicates at 551/553. */
data class CameraAuthority(
    val followMe: Boolean = true,
    val suspended: Boolean = false,
    val lastGestureMs: Long = 0L,
    val navigating: Boolean = false,
    /** True while a spin is running or results are on screen — the resume block
     *  at MapScreen.kt:701, lifted out of the effect's key list. */
    val resultsOnScreen: Boolean = false,
) {
    /** MapScreen.kt:551 */
    val active: Boolean get() = (followMe || navigating) && !suspended
    /** MapScreen.kt:553 — what the follow button reflects. */
    val following: Boolean get() = followMe && !suspended
}

/** SM2 + SM3 fused. Replaces MapScreen.kt:454-464 plus the convoy fields at 498-499. */
data class SpinRound(
    val phase: Phase = Phase.Idle,
    val candidates: List<RouteCandidate> = emptyList(),
    val destination: LatLon? = null,
    val destinationName: String? = null,
    val route: RouteResult? = null,
    val offer: GroupSpin? = null,
    val votes: Map<String, Int> = emptyMap(),
) {
    sealed interface Phase {
        data object Idle : Phase
        data object Spinning : Phase
        /** MapScreen.kt:1490 — three rolls awaiting a pick. */
        data object Choosing : Phase
        /** MapScreen.kt:1449-1461: a usable loop *and* a reason the server one failed. */
        data class FellBackToOverpass(val serverError: String) : Phase
        data class Failed(val message: String) : Phase
    }

    /** MapScreen.kt:823 — the offer wins over local rolls on every device. */
    val displayCandidates: List<RouteCandidate>
        get() = offer?.asRouteCandidates() ?: candidates

    /** MapScreen.kt:1689-1694, already pure today; moved verbatim. */
    fun bottomCard(navigating: Boolean, settingsCollapsed: Boolean): BottomCard = when {
        navigating -> BottomCard.NAV
        displayCandidates.isNotEmpty() -> BottomCard.CANDIDATES
        settingsCollapsed -> BottomCard.COLLAPSED
        else -> BottomCard.EXPANDED
    }
}

/** SM5. Replaces the invisible closure locals at MapScreen.kt:1175-1179. */
sealed interface SectionTracker {
    data object Outside : SectionTracker
    data class Inside(
        val section: SpeedCameras.Section,
        val exitGate: List<LatLon>,
        val entryMs: Long,
        val accMeters: Double,
        val last: LatLon,
        val avgKmh: Double? = null,
    ) : SectionTracker
    val avgKmh: Double? get() = (this as? Inside)?.avgKmh
    val limitKmh: Double? get() = (this as? Inside)?.section?.maxspeedKmh
}
```

### Actions

```kotlin
// app/src/main/java/com/jellemax/detour/map/MapAction.kt

sealed interface MapAction {
    /** `now` is carried, never read from the clock inside reduce() — this is what
     *  makes CAM_RESUME_QUIET_MS (274), the 15s reroute cooldown (1373) and the
     *  30-minute section timeout (1220) testable without sleeping. */
    val nowMs: Long

    // --- SM1, from MapScreen.kt:669-694, 1586-1589, 987 ---
    data class MapDragged(override val nowMs: Long) : MapAction
    data class MapPinched(override val nowMs: Long) : MapAction
    data class GestureEnded(override val nowMs: Long) : MapAction
    data class FollowButtonTapped(override val nowMs: Long) : MapAction

    /** The one GPS entry point. Replaces the five `.collect`s at 704/1026/1065/1147/1180. */
    data class FixArrived(val fix: Fix, override val nowMs: Long) : MapAction

    // --- SM2/SM3, from MapScreen.kt:804, 828, 965, 1490, 1716-1738, 1515 ---
    data class SpinRequested(override val nowMs: Long) : MapAction
    data class SpinLanded(val candidates: List<RouteCandidate>, override val nowMs: Long) : MapAction
    data class LoopLanded(val route: RouteResult, val serverError: String?, override val nowMs: Long) : MapAction
    data class SpinFailed(val message: String, override val nowMs: Long) : MapAction
    data class CandidateChosen(val index: Int, override val nowMs: Long) : MapAction
    data class CandidatePinTapped(val index: Int, override val nowMs: Long) : MapAction
    data class OfferReceived(val offer: GroupSpin, override val nowMs: Long) : MapAction
    data class VotesChanged(val votes: Map<String, Int>, override val nowMs: Long) : MapAction
    data class PeersChanged(val peers: Set<String>, override val nowMs: Long) : MapAction
    data class TravelModeSelected(val mode: TravelMode, override val nowMs: Long) : MapAction

    // --- SM4, from MapScreen.kt:970-1016, 1345-1390 ---
    data class NavigationRequested(override val nowMs: Long) : MapAction
    data class RouteFetched(val route: RouteResult, override val nowMs: Long) : MapAction
    data class NavigationStopped(override val nowMs: Long) : MapAction
}
```

### Reducer

```kotlin
// app/src/main/java/com/jellemax/detour/map/MapReducer.kt

data class Transition(val state: MapState, val effects: List<MapEffect> = emptyList())

/** Total, pure, Android-free. */
fun reduce(state: MapState, action: MapAction): Transition

// Targeted variant: three independent entry points, no umbrella MapState.
fun reduceCamera(state: CameraAuthority, action: MapAction): CameraAuthority
fun reduceSpin(state: SpinRound, action: MapAction, me: String, peers: Set<String>): Transition
fun reduceSection(state: SectionTracker, fix: Fix, sections: List<SpeedCameras.Section>, nowMs: Long): SectionTracker
```

Sketch of the SM1 body, showing that the nine scattered write sites of C2 become one readable
`when` — including the resume rule that today lives in an effect's key list at 700–701:

```kotlin
fun reduceCamera(s: CameraAuthority, a: MapAction): CameraAuthority = when (a) {
    is MapAction.MapDragged, is MapAction.MapPinched ->
        s.copy(suspended = true, lastGestureMs = a.nowMs)                      // 684-686
    is MapAction.GestureEnded ->
        if (s.suspended) s.copy(lastGestureMs = a.nowMs) else s                // 689
    is MapAction.FollowButtonTapped ->
        if (s.following) s.copy(followMe = false)                              // 1587
        else s.copy(followMe = true, suspended = false)                        // 1588
    is MapAction.SpinRequested, is MapAction.CandidateChosen ->
        s.copy(suspended = true, lastGestureMs = a.nowMs)                      // 1403, 810-813, 837-838
    is MapAction.NavigationRequested -> s.copy(suspended = false)              // 987
    is MapAction.FixArrived ->
        if (s.suspended && !s.resultsOnScreen &&
            a.fix.speedMps >= CAM_RESUME_SPEED_MPS &&
            a.nowMs - s.lastGestureMs > CAM_RESUME_QUIET_MS
        ) s.copy(suspended = false) else s                                     // 700-711
    else -> s
}
```

### Effects

```kotlin
// app/src/main/java/com/jellemax/detour/map/MapEffect.kt

sealed interface MapEffect {
    data class FitCameraTo(val points: List<LatLon>) : MapEffect          // 814, 839, 1468, 1493
    data class AnimateCameraTo(val at: LatLon, val zoom: Double, val ms: Int) : MapEffect  // 1680, 1831
    data class FetchRoute(val from: LatLon, val to: LatLon, val profile: String) : MapEffect // 1005, 1379
    data class FetchSpeedLimitWays(val at: LatLon) : MapEffect            // 1037
    data class FetchCameras(val at: LatLon) : MapEffect                   // 1075
    data class ShareSpinOffer(val candidates: List<SpinCandidate>) : MapEffect // 1727, 1734, 867
    data object ClearSpinOffer : MapEffect                                // 835, 1526, 1721
    data object Chime : MapEffect                                         // 1163
    data object Haptic : MapEffect                                        // 1467, 1491
    data class PushNavRelay(val progress: NavEngine.Progress, val speedKmh: Double) : MapEffect // 1356-1357
    data class StartTracking(val destination: LatLon?) : MapEffect        // 989, 1756, 1785
    data object StopNavigationSideEffects : MapEffect                     // 977-979
}
```

---

## Full-MVI vs targeted-MVI

### Variant A — whole-screen MVI

One `MapState`, one `MapAction` hierarchy, one `reduce`, one effect runner. Every `var` in
449–553 becomes a state field; every write site in C2/C3 becomes a dispatch.

**Gets you:** the single GPS collector (C1 fully solved); a single mutation log you can print; every
one of SM1–SM9 testable; the `error` channel becomes a typed field with defined precedence.

**Costs you:** ~430 lines of new declarations; a recomposition problem (see below); a 430-line
reducer file that no other screen in the app resembles; and — the one that actually bites — a
*bigger* refactor blast radius, because every step touches everything.

### Variant B — targeted MVI on SM1, SM2+SM3, SM5

Three small reducers, three test files, everything else left exactly as it is: `LaunchedEffect`,
`remember { mutableStateOf(...) }`, house style.

**Chosen because those three, and only those three, meet all of:**

1. **Genuine multi-state machine**, not a boolean pair. SM1 has 14 transitions (above). SM2+SM3 has
   9 and is *distributed across devices*. SM5 has 3 exit conditions and an integrator.
2. **Scattered today.** SM1 is written at 9 sites; SM3's rule spans 361–367 + 803–870 + 1526 +
   1713–1739. SM5's entire state is unobservable (1175–1179).
3. **Has a bug history or a written correctness argument.** SM5: the comment at 294–298 records a
   shipped bug. SM3: sixteen lines of prose at 842–857 arguing why the obvious implementation
   splits a convoy across two destinations. SM1's resume rule at 696–699 explains a third
   consideration nobody would rediscover.
4. **Pure over Android-free inputs**, so plain JUnit4 reaches them today.

**Rejected for MVI, with reasons:**

- **SM4 (navigation)** — three booleans and two distance guards (1360, 1372). It reads fine. The
  arrival/reroute rule is worth a pure helper, not a reducer.
- **SM7 (limit hysteresis)** — 8 lines (1045–1054). A reducer around it is more code than the code.
- **SM8 (bottom card)** — already a pure `when` (1689–1694). Nothing to do.
- **SM6 (chime latch)** — borderline. Only 20 lines here, but it is *duplicated* at
  `NavScreen.kt:397–415`, so I would extract it as a **pure two-state class with a test**, which is
  MVI-shaped without being MVI. Include it if the panel wants the `car/` duplication gone.
- **SM9 (throttles)** — three copies of a 12-line shape. Wants one `PrefetchWindow` helper class
  (centre + radius + cooldown), not a reducer.

### What variant B honestly does *not* deliver

**It does not collapse the five `lastFix.collect` blocks.** SM6, SM7 and SM9 keep their own
collectors, so C1 is only ~60% solved. If that matters, add step 8 of the migration: a `FixRouter`
that collects once and calls all three reducers plus the two remaining effects — that recovers the
win without the umbrella state.

### Recommendation

**Variant B**, with the composable extraction (migration step 2) done *first and independently*.

The reason is not aesthetic. Step 2 alone takes `MapScreen.kt` from 3193 to ~1450 lines at
essentially zero risk and introduces no pattern anyone has to learn. Everything MVI adds on top of
that is paid for by *testability of three specific machines*, and only three of the nine are worth
the price. Charging the whole file 430 lines of ceremony to fix three of them is a bad trade in a
codebase where `CirclesScreen.kt:98–107`, `FriendsScreen.kt:387–392` and `SettingsScreen.kt:122–132`
all say plainly that the house style is `var` + `remember` + `LaunchedEffect`.

---

## Migration plan

Ordered. Every step leaves the app compiling and is independently committable and revertable.

**Step 1 — `map/MapTuning.kt`.** Move `smoothBearing` (224–230), `sectionExitGate` (300–314),
`leadingSpinIndex` (361–367), `CANDIDATE_COLORS` (319) and the constants at 236–288. Make them
`internal`. Delete `CarMapRenderer.kt:53–69` and `470–475`; point that file at the new module.
*No behaviour change. ~120 lines out of MapScreen, ~25 out of `car/`. Commit: `refactor(map): lift map tuning constants and pure helpers out of MapScreen`.*

**Step 2 — mechanical composable extraction.** Move 1839–2055 → `MapDialogs.kt`; 2057–2121 +
2281–2681 → `SpinCards.kt`; 2123–2279 + 3048–3099 + 3150–3193 → `NavChrome.kt`; 2683–2954 →
`MapChrome.kt`; 2956–3046 + 3101–3148 → `MapHud.kt`; 210–219 → `TravelModeIcon.kt`; 369–414 →
`SpinHandoff.kt`. `private` → `internal` where a moved composable is called from the shell. Prunes
~150 of the 208 import lines.
*`MapScreen.kt`: 3193 → ~1450. Zero behavioural risk; makes every later diff readable.*

**Step 3 — `CameraAuthority` + `CameraAuthorityTest`, unwired.** Add the state, the actions it
consumes, `reduceCamera`, and the ten tests below. Nothing in `MapScreen.kt` changes.
*Compiles; tests pass; the app is untouched. This is the step that proves the rule before adopting it.*

**Step 4 — wire `CameraAuthority`.** Replace 521–523 with one `var camAuth by remember {...}`.
Rewrite the nine write sites (674–689, 700–712, 810–813, 837–838, 987, 1587–1588, 1678–1679,
1829–1830) as `camAuth = reduceCamera(camAuth, ...)`. The frame loop key at 1271 becomes
`LaunchedEffect(camAuth.active, haveFix, mapLibreMap)`; 1273 reads `camAuth.active`; 1580 reads
`camAuth.following`. The auto-resume effect at 700–712 collapses into the single `FixArrived`
dispatch.
*First behavioural risk. Verify on a real ride: pan → parks; drive off → resumes after 8 s; spin →
stays parked; pinch at 80 km/h → not yanked mid-gesture.*

**Step 5 — `SectionTracker` + tests, then wire it.** Replace the locals at 1175–1179 with
`var section by remember { mutableStateOf<SectionTracker>(Outside) }`; the collector body becomes
one call to `reduceSection`. `sectionAvgKmh`/`sectionLimitKmh` (538–539) are deleted; 1646–1647 read
`section.avgKmh` / `section.limitKmh`.
*Nine tests land with this. Lowest-risk of the three wirings — the machine has one input and two outputs.*

**Step 6 — `SpinRound` + tests, then wire it.** The big one. Absorbs 454–464, 498–499, 803–870,
1490, 1515–1527, 1713–1739. Must keep the `SpinResultHolder` mirror at 467–469 working byte-for-byte
so `RoutesScreen.kt:202` is unaffected.
*Highest risk. Do it alone, on its own branch, with the convoy path exercised on two devices.*

**Step 7 — `car/` reuse.** Point `CarMapRenderer.follow()` (196–206) and its loop (383–431) at the
shared ease helper; replace `NavScreen.kt:397–415` with the SM6 latch class.
*Deletes ~45 lines of copy-paste from `car/`. Now the phone and the head unit cannot drift.*

**Step 8 (optional) — `FixRouter`.** One `lastFix.collect` dispatching to all three reducers plus the
two remaining prefetch effects. Replaces 704, 1026, 1065, 1147, 1180.
*Only worth doing after 4/5/6 have shipped and been driven with.*

Steps 1, 2, 3, 7 are risk-free. Steps 4, 5, 6 are the ones that need a real ride before merging.

---

## Test plan

Project constraint: `app/build.gradle.kts:163` gives `junit:junit:4.13.2`, `app/src/test/` currently
holds exactly two files (`PlaceNotificationsTest.kt`, `ui/TripTraceMatchingTest.kt`), and
`TripTraceMatchingTest.kt:16-17` states the rule plainly — "No Android APIs involved, so no
emulator/Robolectric needed." Everything below obeys that: the reducers take `Fix`
(`TripTrackingService.kt:93-100` — six primitives, no Android supertype, its own class file, so
constructing one on the JVM loads nothing Android), `LatLon`, `RouteCandidate`, `GroupSpin`,
`SpeedCameras.Section`, and a `Long` clock. `RoadRoulette` and `SpeedCameras` are in
`:shared/commonMain`, already platform-free.

### `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt`

```
@Test fun panParksTheCameraWithoutTurningFollowOff()
@Test fun tapInsideTouchSlopKeepsFollowing()
@Test fun secondFingerDownParksWithoutWaitingForSlop()
@Test fun drivingOffResumesFollowOnceTheQuietPeriodHasPassed()
@Test fun drivingOffDoesNotResumeBeforeTheQuietPeriod()
@Test fun belowResumeSpeedTheCameraStaysParkedIndefinitely()
@Test fun drivingOffDoesNotResumeWhileCandidatesAreOnScreen()
@Test fun drivingOffDoesNotResumeWhileASpinIsStillRunning()
@Test fun drivingOffDoesNotResumeWhileAConvoyOfferIsOpen()
@Test fun gestureEndRestartsTheQuietPeriodOnlyWhileParked()
@Test fun startingNavigationUnparksEvenRightAfterAGesture()
@Test fun followButtonOffThenOnClearsSuspension()
@Test fun navigationKeepsTheCameraActiveWithFollowMeOff()
@Test fun choosingACandidateParksAndBuysAFreshQuietPeriod()
```

Pins the resume rule of `MapScreen.kt:696-711` — the three-way block that today is expressed as an
effect key list and therefore cannot be asserted at all.

### `app/src/test/java/com/jellemax/detour/map/SpinRoundTest.kt`

```
@Test fun leadingCandidateIsLowestIndexWhenNobodyHasVoted()
@Test fun leadingCandidateIsLowestIndexOnATie()
@Test fun leadingCandidateIsTheStrictMajorityWhenThereIsOne()
@Test fun everyDeviceWithTheSameVotesResolvesToTheSameLeader()
@Test fun sharerResolvesOnlyOnceEveryLivePeerAndItselfHasVoted()
@Test fun sharerDoesNotResolveWhileOnePeerIsStillSilent()
@Test fun sharerWithNoLivePeersDoesNotResolveOnItsOwnVoteAlone()
@Test fun receiverNeverResolvesAMultiCandidateOfferItself()
@Test fun receiverCommitsAOneCandidateOfferImmediately()
@Test fun committingFromAnOfferLeavesRouteNullSoNavigationRefetchesIt()
@Test fun committingClearsTheCandidateListAndTheOffer()
@Test fun aVoteOnAPinAndAVoteOnARowProduceTheSameState()
@Test fun switchingTravelModeClearsAnOpenVoteRound()
@Test fun switchingTravelModeResetsRadiusAndClearsTheDestination()
@Test fun cancelClearsCandidatesAndOfferButKeepsTripParameters()
@Test fun rerollClearsCandidatesBeforeEnteringSpinning()
@Test fun anOfferOverridesLocalCandidatesInDisplayCandidates()
@Test fun aFallbackLoopIsAResultAndAnErrorAtTheSameTime()
```

`everyDeviceWithTheSameVotesResolvesToTheSameLeader` and `sharerDoesNotResolveWhileOnePeerIsStillSilent`
are the two that turn the prose at `MapScreen.kt:842-857` into something CI enforces. That prose
exists because the failure it prevents — "splitting a convoy across two destinations" — is the exact
failure the feature exists to prevent.

### `app/src/test/java/com/jellemax/detour/map/SectionTrackerTest.kt`

```
@Test fun entersOnlyWhenTheFarEndLiesAheadOfTheHeading()
@Test fun passingADeviceNodeOnTheWayOutDoesNotStartAMeasurement()
@Test fun aStoppedPhoneCannotEnterASectionOnANoisyBearing()
@Test fun picksTheNearestSectionWhenTwoShareAGate()
@Test fun averageIsAccumulatedDistanceOverElapsedTimeNotMeanFixSpeed()
@Test fun noAverageIsPublishedBeforeTwentyMetresAreAccumulated()
@Test fun theEntryGateDoesNotCountAsTheExitOnTheFollowingFix()
@Test fun reachingTheFarGateAfterOneHundredFiftyMetresEndsTheMeasurement()
@Test fun overshootingSpanTimesOnePointFourPlusFourHundredEndsIt()
@Test fun thirtyMinutesInsideASectionTimesOut()
@Test fun exitClearsBothTheAverageAndTheSectionLimit()
```

`passingADeviceNodeOnTheWayOutDoesNotStartAMeasurement` is a regression test for the bug named in the
comment at `MapScreen.kt:294-298`.

### `app/src/test/java/com/jellemax/detour/map/CameraWarnLatchTest.kt` (if SM6 is included)

```
@Test fun chimesOnceForACameraAheadWhileOverTheLimit()
@Test fun doesNotChimeWhenUnderThePostedLimitPlusThree()
@Test fun doesNotChimeWhenTheLimitIsUnknown()
@Test fun doesNotChimeForACameraBehindTheHeadingWedge()
@Test fun rearmsOnceThatCameraFallsOutOfWarnRange()
@Test fun navProgressLimitTakesPrecedenceOverTheAmbientLimit()
```

**Total: ~50 tests against ~400 lines of reducer.** The app currently has 2 test files and no
coverage of any of this.

---

## Pros

1. **Nine invisible mutable variables become observable.** `warnedAt` (1146),
   `center`/`lastFetchMs` (1063–1064) and the five section locals (1175–1179) currently exist only
   inside coroutine closures. After: fields you can log, breakpoint, and assert.
2. **The three rules with written correctness arguments get executable ones.** 842–857 (convoy
   consensus), 294–298 (section entry), 696–699 (resume grace period) are all prose today.
3. **Nine camera write sites collapse to one `when`.** C2 becomes one 20-line function.
4. **Time becomes an input.** `System.currentTimeMillis()` is called at 676, 689, 707, 813, 838,
   1032, 1070, 1183, 1371, 1679, 1830, 3106. Passing `nowMs` on the action makes the 8 s / 15 s /
   30 min rules testable without `Thread.sleep`.
5. **`car/` stops being a fork.** `car/` is in the same Gradle module, so the reducers are reusable
   on day one, with no module move. `CarMapRenderer.kt:53-69,383-431,470-475` and
   `NavScreen.kt:397-415` are deleted rather than kept in sync by hand.
6. **The "usable result *and* an error" state becomes expressible.** 1449–1461 today sets `route`
   and `error` together and hopes the reader notices; `Phase.FellBackToOverpass(serverError)` says it.
7. **Five GPS subscriptions become one** (variant A, or variant B + step 8), removing the
   re-subscription churn caused by the key list at 700.
8. **A future `:shared` promotion is a straight move.** Every reducer input except `Fix` already
   lives in `:shared/commonMain`. That matters because `iosApp/Detour/SpinModel.swift:17-24` is
   already a third, hand-written copy of SM2 as a Swift enum — see Cons for the caveat.

---

## Cons and risks

### Boilerplate volume — the real number

Variant A adds ~430 lines of `MapState`/`MapAction`/`MapEffect` declarations that describe code
rather than doing anything, on top of a 430-line reducer. Net file-total goes **+11%**. Variant B
adds ~110 lines of declarations for ~285 lines of reducer, net ≈ +2%. Anyone selling MVI as "it
makes the code smaller" is wrong; it makes the code *addressable*, at a price.

The ongoing tax is worse than the one-off. Today, adding "remember the last spin's POI kind" is one
`var` plus one write. After variant A it is: a field on `TripParams`, a case in `MapAction`, a branch
in `reduceTrip`, possibly an effect, and a test. Four files for a one-line feature. On a
solo-maintained hobby-scale app that friction is a real cost, not a theoretical one.

### Recomposition and perf — the sharpest technical objection

MapScreen currently holds ~40 independent `mutableStateOf` cells (449–553). Compose tracks each one
separately, so writing `displaySpeedKmh` at 1259 invalidates only `SpeedHud` (1642). A single
`MutableStateFlow<MapState>` collected at the top of the composable would invalidate **the entire
screen on every GPS fix** — over a GL surface with a fog view riding the camera-move callback
(945–946). Mitigations exist (`derivedStateOf` per slice, `distinctUntilChanged` per field, keeping
slices as separate `mutableStateOf` cells) but they are *work* and they must be got right, and
getting them wrong is a frame-rate regression on the app's only performance-sensitive screen.

Variant B sidesteps this entirely by keeping three separate `mutableStateOf` cells. That is the
single biggest reason I recommend it.

### Indirection cost when debugging a live GPS bug

This is the honest worst case. Today, a bug like "the camera resumed while I was reading candidates"
is debugged by putting a breakpoint at 706 and reading four locals. After: you set a breakpoint in
`reduceCamera`, discover the action was `FixArrived`, then have to walk *backwards* to find which
collector produced it and what `resultsOnScreen` was derived from — and you cannot step through a
`Transition`'s effects at the point they were decided, because they run later, in the runner.

Reducers trade "hard to reason about statically, easy to inspect live" for the reverse. On a screen
whose worst bugs are field-reported, intermittent, and only reproducible in a moving vehicle, that
trade is genuinely arguable, not obviously good. The mitigation — logging every
`(action, stateBefore, stateAfter)` and shipping it in the debug build — is itself a real
advantage over today (there is nothing to log today), but it has to actually be built.

### State explosion

`MapState` in variant A holds five slices with, conservatively, 4 × 3 × 5 × 6 × 4 reachable
combinations. Most are unreachable, but the type does not say which. `SpinRound.Phase == Spinning`
with `offer != null` is nonsense; nothing stops it. Sealed hierarchies help within a slice and do
nothing across slices. Expect to write invariant assertions or accept that the type system is
weaker here than the prose comments it replaces.

### No other screen in this app uses this pattern

`CirclesScreen.kt:98-107`, `FriendsScreen.kt:387-392`, `SettingsScreen.kt:122-132` are all
`var x by remember { mutableStateOf(...) }` + `LaunchedEffect` + a singleton `StateFlow`. There is no
ViewModel, no DI, no `Store`, no `Reducer` anywhere in the tree. A contributor who has read the rest
of this codebase will meet a genuinely foreign pattern.

Two honest counterweights: (a) `iosApp/Detour/SpinModel.swift:17-24` already models the spin as an
enum state machine, so the *product* is not innocent of the idea — only the Kotlin is; (b) an
unfamiliar pattern that arrives with 50 tests is much cheaper to learn than an unfamiliar pattern
that arrives bare. Neither counterweight removes the objection.

### Migration risk concentration

Step 6 (`SpinRound`) touches the convoy path, which is the hardest thing in this app to test — it
needs two devices, a running `ConvoyLiveService`, and a server. A regression there splits a convoy,
which is the loudest possible failure. That step is where this proposal can actually hurt someone.

### The reducer will leak

`reduceSpin` needs `me: String` and `peers: Set<String>` as parameters (858–866), and `reduceSection`
needs the current `sections` list (1193). Those are not state, they are ambient context, and passing
them explicitly on every call is exactly the kind of paper cut that makes people quietly reach for a
captured field six months later. Watch for it.

---

## Effect on

### Recomposition and perf

- **Variant A:** meaningful regression risk unless slice-level `derivedStateOf` is done carefully.
  One state object per action at 1 Hz is free; one per *frame* would not be, which is precisely why
  the frame loop (1271–1333) and the speed ease (1251–1263) stay outside the loop entirely.
- **Variant B:** neutral. Three `mutableStateOf` cells replace six (`followMe`, `camSuspended`,
  `lastGestureMs`, `sectionAvgKmh`, `sectionLimitKmh`, plus the spin group), so the invalidation
  graph gets marginally *coarser* but stays per-slice.
- **Both:** step 2 (composable extraction) is a small genuine win — smaller composable bodies are
  cheaper to skip, and the current 309-line inline UI tree (1529–1837) is one recomposition scope
  with a dozen readers in it.
- **Neither** changes the two real costs on this screen: the GL redraw + fog invalidate on every
  camera move (945–946, guarded by the epsilon check at 1320–1331), and the GeoJSON re-serialisation
  in `MapOverlays.render` (872–902).

### Android Auto reuse — `app/src/main/java/com/jellemax/detour/car/`

The strongest concrete win, and it is available at step 1, before any MVI at all.

- `CarMapRenderer.kt:53–69` and `470–475` are literal copies of `MapScreen.kt:236–255` and `224–230`.
  Step 1 deletes them.
- `CarMapRenderer.kt:383–431` re-derives the ease loop; after step 1 both call the same helper.
- `NavScreen.kt:397–415` re-implements SM6; the latch class replaces it.
- `CarMapRenderer.kt:78` re-declares `CIRCLE_FIX_POLL_MS` with a comment pointing at MapScreen.

**Honest limit:** `CameraAuthority` itself is *not* reusable by the car screen. There is no touch
input on an Android Auto surface, so there is no park/suspend/resume — `CarMapRenderer.follow()`
(196–206) just eases, always. The car reuses the *tuning and the ease math and SM5/SM6*, not SM1.
And `SpinScreen.kt` does not implement the convoy vote round at all, so SM3 gains nothing there
either. The reuse story is real but narrower than it first looks.

**iOS:** `iosApp/Detour/SpinModel.swift` is a 146-line hand-written Swift state machine duplicating
SM2. A `:shared/commonMain` reducer would collapse it — but that requires promoting `Fix`
(`TripTrackingService.kt:93-100`) out of `:app` first, and `SpinModel.swift` is a *Swift* type that
would have to be rewritten to consume a Kotlin reducer. That is a separate project, not a benefit
this proposal delivers.

### Reviewability

Better for *changes to rules*: a diff to the resume grace period becomes a diff to one `when` branch
plus one test name, instead of a diff spread over an effect key list (700), a touch listener (689)
and a constant (274).

Worse for *changes that add state*: a one-field feature becomes a 4-file diff. And the first few PRs
after adoption will be large and hard to review precisely because they are pattern-introducing.

Step 2 alone improves reviewability more than anything else here, and costs nothing.

### Onboarding a contributor who has only seen the rest of this codebase

Worst-affected axis. The rest of the app is `var` + `remember` + `LaunchedEffect` + a singleton
`StateFlow`, uniformly, across `CirclesScreen`, `FriendsScreen`, `SettingsScreen`, `RoutesScreen`,
`HistoryScreen`. There is no ViewModel to have prepared them.

Variant A hands them a 430-line reducer and a 150-line action hierarchy before they can change a
button. Variant B hands them three files of 95–190 lines, each named after a thing they already
understand ("the camera follow rules", "the group spin round", "the trajectcontrole tracker"), each
with a test file next to it that reads like documentation. That is a materially easier ask, and it is
the deciding argument for B over A as much as the perf one is.

---

## What this proposal explicitly does NOT solve

1. **File size, on its own.** The reducer removes decisions from `MapScreen`, not markup. The UI tree
   (1529–1837) and the ~20 `LaunchedEffect`s stay. Variant B without step 2 leaves `MapScreen.kt`
   above 2800 lines. **Step 2 does the size work; MVI does the correctness work.** They are
   independent, and step 2 is strictly cheaper.
2. **The 208 import lines (1–208).** Only the composable extraction touches those.
3. **`MapOverlays` (`MapLibreMap.kt:113–422`) and `FogView` (`MapLibreMap.kt:492+`).** Untouched.
   The eight overlay push effects (C6) get relocated, not simplified.
4. **The camera-move → fog-invalidate cost (945–946).** Unchanged.
5. **`TripTrackingService` (1200+ lines).** Out of scope entirely.
6. **The async-map null dance.** `mapLibreMap` arrives via `getMapAsync` (633–641), so `?.let` at
   814, 839, 1468, 1492, 1680, 1831 survives — it just moves into the effect runner.
7. **`SpinResultHolder` (369–414) stays a process-scoped global.** A reducer does not remove it; it
   becomes the hydration source. `RoutesScreen.kt:202` keeps working precisely because nothing
   changes for it.
8. **The single `error: String?` slot (C7).** It becomes a typed field, but two concurrent errors
   still race, and it is still only rendered by `SpinSheet` (1771).
9. **Total LOC.** It goes up (+11% for A, +2% for B).
10. **Compose-specific state.** `AnimatedContent` (1699–1706), and the `shownStats` / `shownCandidates`
    keep-last-value tricks (1654–1655, 1697–1698) are presentation concerns and stay in the composable.
11. **`Settings` / `SavedPlaces` / `TraceStore` and the other singletons.** They remain global
    `StateFlow` objects; the reducer reads them as inputs, it does not own them.
12. **It does not make the convoy path testable end-to-end.** It makes the *resolution rule*
    testable. The socket, `ConvoyLiveClient`'s 20 s peer pruning (`ConvoyLiveClient.kt:403`) and the
    server round-trip remain untested.

---

## Honest verdict

**This is the right choice when the question is "why did the camera/spin/section do that?"**
MapScreen contains at least nine implicit state machines (SM1–SM9), and three of them — camera
authority (14 transitions, 9 write sites), the group-spin vote round (a distributed consensus rule
with a 16-line written correctness argument at 842–857 and no test), and trajectcontrole tracking
(an integrator with three exit conditions living in unobservable closure locals at 1175–1179, with a
documented shipped bug at 294–298) — are past the size where booleans-and-comments is a defensible
encoding. For those three, a pure reducer plus ~40 JUnit4 tests turns comments into CI, and it does
so with tools this project already has: `junit:junit:4.13.2` at `app/build.gradle.kts:163`, no
Robolectric, no new dependency, no module move, and immediate reuse by `car/` because `car/` is in
the same module.

**It is the wrong choice as a whole-file strategy, and it is the wrong choice if the goal is a
smaller file.** Full MVI charges ~430 lines of `State`/`Action`/`Effect` ceremony and a
recomposition-granularity problem on the app's only 60 fps screen, to fix three machines out of
nine — the other six (SM4, SM6–SM9) are booleans, an already-pure `when` at 1689–1694, and three
copies of a 12-line throttle that wants a helper class, not a reducer. And it introduces a pattern
that appears nowhere else in a codebase that is otherwise perfectly uniform
(`CirclesScreen.kt:98-107`, `FriendsScreen.kt:387-392`, `SettingsScreen.kt:122-132`). If the
panel's success metric is "MapScreen.kt under 800 lines", the honest answer is that **migration
step 2 alone gets you to ~1450 with zero risk and no new concepts**, and no amount of MVI improves
on that per unit of risk.

**So: adopt variant B.** Do step 1 and step 2 first, on their own, because they are free and they
stand alone whichever proposal the panel picks. Then add `CameraAuthority`, `SectionTracker` and
`SpinRound` — in that order, cheapest and safest first — each with its tests, each behind its own
commit, and stop there. Nine state machines do not need nine reducers. Three of them have earned one.
