# Proposal 2 — Compose state holders (rememberXxxState)

Split `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (3,193 lines) using Google's
documented *plain state holder* pattern: one ordinary Kotlin class per concern, holding its own
`mutableStateOf` fields and the suspend bodies of the effects that drive them, created by a
`@Composable fun rememberXxxState(...): XxxState` factory that also owns the `LaunchedEffect` /
`DisposableEffect` keys.

No ViewModel. No DI. No new Gradle dependency. Nothing in the module graph changes.

**What the file actually is right now** (measured over the `MapScreen` composable body,
`MapScreen.kt:419-1837` — 1,419 lines):

| | count |
|---|---|
| `LaunchedEffect(` declarations | 32 |
| `DisposableEffect(` declarations | 4 |
| top-level `remember*` declarations | 59 |
| of which `rememberUpdatedState` back-refs | 8 |
| of which `rememberLauncherForActivityResult` | 3 |
| `collectAsStateWithLifecycle()` calls | 21 |
| local `fun` declarations inside the composable | 8 |

Plus 24 private `@Composable`s and 8 private helper functions below it (`MapScreen.kt:1839-3193`,
1,354 lines) which are already stateless and just need moving.

---

## Cross-cutting concerns I identify in MapScreen.kt

My own reading, with line ranges. Ordered roughly by how tangled they are.

**A. Map surface + imperative MapLibre glue.**
`MapScreen.kt:429-434` (attribution/logo margin math), `592-595` (`mapView`, `fogView`,
`mapLibreMap`, `mapOverlays`), `629-648` (MapView lifecycle `DisposableEffect`), `652-663` (style
(re)load → new `MapOverlays` → attach `FogView` as a child view), `940-946` (camera-move/idle
listeners feeding `fogView.invalidate()`), and the six push-to-overlay effects at `872-902`,
`904-909`, `911-915`, `1085-1089`, `1091-1096`, `1120-1122`. ~150 lines. Everything else in the
file that "draws" goes through here.

**B. Camera.**
`236-238` + `253-255` + `259` + `272-274` (tau/epsilon/padding/resume constants), `221-230`
(`smoothBearing`), `521-523` (`followMe`, `camSuspended`, `lastGestureMs`), `547-554` (`camTarget`,
`camTargetBearing`, `camTargetZoom`, `cameraActive`, `following`), `669-694` (touch listener that
parks the camera), `700-712` (resume-on-driving-off), `1232-1245` (fix → targets), `1265-1333` (the
per-frame ease loop). Plus five call sites that yank the camera from *other* concerns: `814`, `839`,
`1468`, `1493`, `1680`, `1831`, and `987` (`camSuspended = false` on nav start). ~190 lines.

**C. Trip target (destination / route / candidates).**
`440` (`savePinTarget`), `453-469` (`savedSpin` seed, `candidates`, `destination`, `route`,
`destinationName`, and the `SpinResultHolder` write-back effect), `369-414` (`SpinResult`,
`SpinResultHolder`, `seedRouteNavigation` — the cross-screen contract with `RoutesScreen.kt:202`).
This is the hub: it is written by the spin, by a long-press pin drop (`949-956`), by a search hit
(`1826-1829`), by a shortcut chip (`1675-1677`), by a convoy commit (`831-834`), and by navigation's
reroute (`1379`). ~70 lines but touched from six places.

**D. Spin.**
`267` (`CURVY_CANDIDATES`), `316-319` (`CANDIDATE_COLORS`), `449-450` + `462-463` (`radiusKm`,
`minRadiusKm`, `poiKind`, `directionDeg` — all `rememberSaveable`), `458-461` (`spinning`,
`spinJob`, `error`, `serverConfig`), `803-815` (`choose`), `1392-1513` (`spin()` — 122 lines, the
single largest function in the file), `1515-1527` (`selectMode`). ~200 lines.

**E. Convoy group spin.**
`322-343` (`GroupSpin.asRouteCandidates`), `345-355` (`asSpinCandidates`), `357-367`
(`leadingSpinIndex`), `491-512` (convoy flow collection + `convoyName` resolution), `823`
(`displayCandidates`), `825-840` (`commitSpinCandidate`), `842-870` (the vote-resolution effect and
its 20-line comment explaining why tallying independently is wrong). ~130 lines.

**F. Turn-by-turn navigation.**
`514-517` (`navigating`, `navProgress`, `rerouting`, `lastRerouteMs`), `970-980` (`stopNavigation`),
`982-1016` (`startNavigation`), `1335-1342` (BLE speed push when *not* navigating), `1344-1390`
(progress / driven fraction / relay push / arrival / reroute). ~120 lines.

**G. Road hazards — speed limits, cameras, trajectcontrole.**
`276-288` (`CIRCLE_FIX_POLL_MS` sits here too, plus `SECTION_GATE_METERS`, `SECTION_WEDGE_DEG`),
`290-314` (`sectionExitGate`), `527-539` (nine state vars), `1018-1056` (ambient speed-limit
prefetch + snap), `1058-1083` (camera/section prefetch), `1134-1167` (the over-the-limit chime,
`ToneGenerator`, three `rememberUpdatedState` refs), `1169-1230` (section average state machine).
~200 lines. **The most self-contained concern in the file** — nothing outside it reads
`speedLimitWays`, `speedLimitMisses`, `sectionAvgKmh` except the HUD.

**H. Fog of war feed.**
`479-485` (`storeVersion` → `traces`, `shareFog`, `friendTraces`), `917-927` + `928-932` (two
effects, deliberately split — see the comment at `917-920`), `1124-1132` (peer holes). ~40 lines.
Rides on top of concern A.

**I. Circle member fixes.** `1105-1119` (poll loop), `1120-1122` (push to overlays). ~20 lines.

**J. Location + permissions bootstrap.**
`444` (`showBgLocationDisclosure`), `455` (`myLocation`), `555-559` (fix → `myLocation`), `714-734`
(`fetchLocation`), `738-758` (bg-location + mic launchers, mic-on-convoy effect), `760-801`
(`onLocationGranted`, the multi-permission launcher, the launch-time check), `1798-1808` (the
disclosure dialog call site). ~110 lines.

**K. Speed readout easing.** `240-247` (`SPEED_TAU`, `SPEED_EPS_KMH`), `550` (`displaySpeedKmh`),
`1247-1263` (its own `withFrameNanos` loop). ~25 lines. Shares machinery with B but nothing else.

**L. Trip tracking + process lifecycle.** `486` (`stats`), `601-625` (the `ON_START`/`ON_STOP`
observer that toggles GPS cadence and force-stops push-to-talk), and the four
`TripTrackingService.start/stop` call sites at `989`, `1636`, `1756`, `1785`, `1789`. ~40 lines.

**M. Ephemeral chrome state.** `417` (`BottomCard`), `472` (`searchOpen`), `475` (`layersOpen`),
`526` (`settingsCollapsed`), `1654-1655` and `1697-1698` (the two "keep the last value so the
exiting card animates out with content" latches). ~20 lines. **Deliberately not a holder** — see
below.

**N. Sync-on-launch + friend-fog refresh.** `568-578`, `582-585`. ~15 lines. Two fire-and-forget
effects with no state at all.

**O. Stateless presentation.** `1839-3193`. 24 composables, 4 intent helpers, 3 pure nav-app
decision functions, `TravelMode.icon` (`213-219`), `DIRECTION_NAMES` (`210-211`). 1,354 lines.
Zero coupling to anything above except through parameters.

---

## The pattern, stated precisely

A state holder here is:

```kotlin
@Stable
class XxxState(
    private val scope: CoroutineScope,      // from rememberCoroutineScope()
    private val deps: …,                    // plain values or lambdas, never sibling holders
) {
    var somethingUiReads by mutableStateOf(initial)
        private set
    private var somethingOnlyIRead = 0L     // plain field — NOT mutableStateOf

    fun imperativeCommand(…) { … }          // called from click handlers
    suspend fun loop(…) { … }               // the body of what is a LaunchedEffect today
}

@Composable
fun rememberXxxState(paramsThatCanChange: …): XxxState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { XxxState(scope, …) }
    LaunchedEffect(state, paramsThatCanChange) { state.loop(paramsThatCanChange) }
    DisposableEffect(state) { … ; onDispose { … } }
    return state
}
```

**Three rules that make this work, stated so they can be checked in review:**

1. **The class holds state and suspend bodies. The factory holds the effect *keys*.**
   `LaunchedEffect` is not just "run a coroutine" — its cancel-and-restart-on-key-change is load
   bearing all over this file. `MapScreen.kt:1271` restarts the camera loop when `cameraActive`,
   `haveFix` or `mapLibreMap` change; `MapScreen.kt:1024` restarts the ambient-limit collector when
   `navigating` flips. That semantics must stay declarative and visible, so the `LaunchedEffect`
   stays in the factory and only its *block* moves into the class as a `suspend fun`. Moving the
   restart logic into the class (`scope.launch` + manual `job?.cancel()`) would be a rewrite, not a
   move, and is exactly how this kind of refactor introduces regressions.

2. **Coroutine scope = `rememberCoroutineScope()`, which is the lifetime we already have.**
   Every one of the 32 `LaunchedEffect`s in `MapScreen` today is cancelled when `MapScreen` leaves
   composition, and so is the `scope` at `MapScreen.kt:435` used by `fetchLocation` (`718`),
   `startNavigation` (`1003`), `spin` (`1398`) and the reroute (`1377`). Passing
   `rememberCoroutineScope()` into a holder reproduces that lifetime *exactly*. Nothing gets longer
   or shorter lived. That is the single biggest safety property of this proposal, and it is also
   its biggest limitation (see Cons).

3. **Android lifecycle stays where it is.** The `ON_START`/`ON_STOP` observer at
   `MapScreen.kt:601-625` is a `DisposableEffect(lifecycleOwner)` and stays one — it is tied to the
   *activity* lifecycle, not the composition, and the comment at `597-600` explains why that
   distinction matters (a backgrounded app keeps the map composed; a 1 Hz GPS request must not stay
   open). Same for the MapView lifecycle at `629-648`. These move into
   `rememberMapSurfaceState()`'s factory body verbatim; the holder gets `onCreate()` /
   `onDispose()` methods and the factory calls them from the `DisposableEffect`.

**`withFrameNanos` inside a holder.** Both frame loops (`1251-1263`, `1271-1333`) call
`withFrameNanos`, which needs a `MonotonicFrameClock` in the coroutine context. `LaunchedEffect`
supplies it (the composition's frame clock), so as long as the loop bodies are `suspend fun`s
*invoked from* a `LaunchedEffect` in the factory, they work unchanged. Do **not** move them to
`scope.launch` in an `init {}` block — `rememberCoroutineScope()`'s context does carry the frame
clock on Android, but relying on that is a trap the next person will not know about.

**What does not become a holder.** Concern M (ephemeral chrome: `searchOpen`, `layersOpen`,
`settingsCollapsed`, `savePinTarget`, the two animation latches) stays as plain `remember` /
`rememberSaveable` in `MapScreen`. Wrapping four booleans in a class is ceremony, and
`settingsCollapsed` at `MapScreen.kt:526` is `rememberSaveable` today — keeping it hoisted keeps it
saved for free. Concern N (two fire-and-forget effects, `568-585`) stays as two `LaunchedEffect`s in
`MapScreen`. Concern I (circle fixes, `1105-1122`) becomes a 20-line
`@Composable fun rememberCircleFixes(username: String): List<MemberFix>`, not a class — there is one
value and no commands. Concern K becomes
`@Composable fun rememberEasedSpeedKmh(targetKmh: Double): Double`, same reasoning.

Knowing where to *stop* applying the pattern is most of the value of the pattern.

---

## Proposed file layout

**Package stays `com.jellemax.detour.ui` for every new file, in the same directory.** This is
deliberate and it is the reason the migration is cheap: same-package Kotlin files need no imports of
each other, so every move below is a *pure cut-and-paste with zero edits to the moved code and zero
edits to any other file*. `git log -C` finds them as moves. A `com.jellemax.detour.ui.map`
subpackage is available as an optional last step (cost listed at the end of the migration plan) but
it is not needed to get the benefit.

| # | file | symbols moved in | source lines from MapScreen.kt | ≈ lines |
|---|---|---|---|---|
| 1 | `ui/MapScreen.kt` (rewritten) | `MapScreen` only — holder construction, the 21 singleton-flow collectors, the wiring effects, the Scaffold skeleton, the three dialog call sites | `419-448`, `470-501`, `526`, `540-546`, `560-590`, `601-625`, `872-915`, `1529-1612`, `1798-1836` | **300** |
| 2 | `ui/MapBottomStack.kt` | `BottomCard`, new `MapBottomStack(…)` extracted from the Scaffold's bottom `Column`, `ShortcutChips` | `416-417`, `1613-1794`, `1960-1996` | 265 |
| 3 | `ui/MapTargetState.kt` | `MapTargetState`, `rememberMapTargetState`, `SpinResult`, `SpinResultHolder`, `seedRouteNavigation` | `369-414`, `453-457`, `464`, `467-469` | 95 |
| 4 | `ui/MapSurfaceState.kt` | `MapSurfaceState`, `rememberMapSurfaceState` | `429-434`, `592-595`, `629-663`, `917-932`, `940-946`, `1124-1132` | 200 |
| 5 | `ui/MapCameraState.kt` | `MapCameraState`, `rememberMapCameraState`, `smoothBearing`, `rememberEasedSpeedKmh`, CAM_*/SPEED_*/FIT_PADDING_PX consts | `221-259`, `272-274`, `521-523`, `547-554`, `669-712`, `1232-1263`, `1265-1333` | 290 |
| 6 | `ui/MapSpinState.kt` | `MapSpinState`, `rememberMapSpinState`, `MapSpinStateSaver`, `CANDIDATE_COLORS`, `CURVY_CANDIDATES` | `267`, `316-319`, `449-450`, `458-463`, `803-815`, `1392-1527` | 305 |
| 7 | `ui/MapNavigationState.kt` | `MapNavigationState`, `rememberMapNavigationState` | `514-517`, `970-1016`, `1335-1390` | 160 |
| 8 | `ui/MapRoadHazardState.kt` | `MapRoadHazardState`, `rememberMapRoadHazardState`, `sectionExitGate`, SECTION_* consts | `276-314`, `527-539`, `1018-1083`, `1134-1230` | 255 |
| 9 | `ui/MapConvoySpinState.kt` | `MapConvoySpinState`, `rememberMapConvoySpinState`, `asRouteCandidates`, `asSpinCandidates`, `leadingSpinIndex` | `322-367`, `502-512`, `823`, `825-870` | 145 |
| 10 | `ui/MapLocationState.kt` | `MapLocationState`, `rememberMapLocationState`, `rememberCircleFixes`, `BackgroundLocationDisclosure` | `444`, `455`, `555-559`, `714-801`, `1105-1119`, `1998-2028` | 175 |
| 11 | `ui/MapSearchDialog.kt` | `SearchDialog`, `SavePinDialog` | `1839-1958`, `2030-2055` | 155 |
| 12 | `ui/SpinCards.kt` | `SpinDock`, `SpinSheet`, `CandidatesCard`, `Pill`, `SegmentedPillRow`, `ScrollingPillRow`, `DIRECTION_NAMES` | `210-211`, `2057-2121`, `2281-2681` | 470 |
| 13 | `ui/MapChrome.kt` | `ModeBar`, `MapTopChrome`, `SearchPill`, `ConvoyPill`, `GlassRailButton`, `EndTripButton`, `PushToTalkButton` | `2683-2954` | 290 |
| 14 | `ui/SpeedHud.kt` | `SpeedHud`, `SectionAverageChip`, `ActiveTripCard`, `StatItem` | `2956-3046`, `3101-3148` | 160 |
| 15 | `ui/NavApps.kt` | `launchNav`, `navAppUsableDirectly`, `handleGoTap`, `NavMenuItems`, `NavButton`, `NavIconButton`, `navigateRoundTrip`, `navigateGoogleMaps`, `navigateWaze`, `navigateGeo` | `2123-2279`, `3048-3099`, `3150-3193` | 255 |
| 16 | `ui/TravelModeIcon.kt` | `TravelMode.icon` | `213-219` | 15 |

**Totals: 3,193 → ~3,535 across 16 files (+11%), largest file 470 lines, `MapScreen.kt` 300.**

The +11% is entirely duplicated `import` blocks (16 headers instead of 1) plus class/factory
boilerplate. Anyone who claims a refactor like this reduces line count is not counting imports.

Note `TravelMode.icon` gets its own 15-line file on purpose: it is used by `RoutesScreen.kt:303`,
`HistoryScreen.kt:317` and `RouteEditorScreen.kt:386` and has nothing to do with the map. Isolating
it is what makes the optional subpackage move later cost 2 import lines instead of 5.

---

## The state holders themselves

Real field names, taken from the current code. Bodies elided to `…` with the source line range they
come from.

### `MapTargetState` — the hub (concern C)

```kotlin
/** Destination, route and candidate list: written by the spin, a dropped pin, a search hit,
 *  a shortcut chip, a convoy commit and a reroute. One owner, so the SpinResultHolder
 *  write-back (MapScreen.kt:467-469) is one place instead of an effect over four vars. */
@Stable
class MapTargetState(seed: SpinResult) {
    var destination      by mutableStateOf(seed.destination)
    var destinationName  by mutableStateOf(seed.destinationName)
    var route            by mutableStateOf(seed.route)
    var candidates       by mutableStateOf(seed.candidates)

    fun setPin(at: LatLon, name: String) { destination = at; destinationName = name; route = null }
    fun clear() { destination = null; destinationName = null; route = null; candidates = emptyList() }
    fun publish() {                                            // MapScreen.kt:468
        SpinResultHolder.state.value = SpinResult(destination, destinationName, route, candidates)
    }
}

@Composable
fun rememberMapTargetState(): MapTargetState {
    val state = remember { MapTargetState(SpinResultHolder.state.value) }   // MapScreen.kt:453
    LaunchedEffect(state.destination, state.destinationName, state.route, state.candidates) {
        state.publish()
    }
    return state
}
```

### `MapCameraState` (concern B)

```kotlin
@Stable
class MapCameraState {
    var followMe        by mutableStateOf(true)     // MapScreen.kt:521
    var camSuspended    by mutableStateOf(false)    // MapScreen.kt:522
    var camTarget        by mutableStateOf<LatLon?>(null); private set   // :547
    var camTargetBearing by mutableStateOf<Float?>(null);  private set   // :548
    var camTargetZoom    by mutableDoubleStateOf(0.0);     private set   // :549
    private var lastGestureMs = 0L                  // was mutableLongStateOf at :523

    val following: Boolean get() = followMe && !camSuspended             // :553
    fun isActive(navigating: Boolean) = (followMe || navigating) && !camSuspended  // :551

    fun park() { camSuspended = true; lastGestureMs = System.currentTimeMillis() }  // :675-676
    fun endGesture() { if (camSuspended) lastGestureMs = System.currentTimeMillis() }  // :689
    fun toggleFollow() { … }                        // :1586-1589
    fun clearBearing() { camTargetBearing = null }  // :973, called by nav stop
    fun onFix(fix: Fix, defaultZoom: Float, distanceToTurnMeters: Double?) { … }    // :1236-1244
    /** Park and frame a set of points — the "a result landed, let me look at it" move. */
    fun frameResult(map: MapLibreMap?, points: List<LatLon>, bottomPaddingPx: Int) { … } // :810-814
    fun levelNorth(map: MapLibreMap) { … }          // :1274-1278
    suspend fun awaitDrivingResume() { … }          // :704-711
    suspend fun runFollowLoop(map: MapLibreMap, startFallback: LatLon?) { … }       // :1280-1332
}

@Composable
fun rememberMapCameraState(
    mapView: MapView,
    map: MapLibreMap?,
    myLocation: LatLon?,
    liveFix: Fix?,
    defaultZoom: Float,
    navigating: Boolean,
    distanceToTurnMeters: Double?,
    /** True while a spin result or convoy offer is on screen: suppresses the
     *  drive-off resume, MapScreen.kt:696-703. */
    holdParked: Boolean,
): MapCameraState
```

Factory body: the touch `DisposableEffect(mapView)` (`669-694`) calling `state.park()` /
`state.endGesture()`; `LaunchedEffect(state.camSuspended, holdParked) { … state.awaitDrivingResume() }`
(`700-712`); `LaunchedEffect(liveFix, defaultZoom) { … state.onFix(…) }` (`1236-1245`); and
`LaunchedEffect(active, haveFix, map) { … }` with the same three keys as `1271`.

### `MapSpinState` (concern D)

```kotlin
@Stable
class MapSpinState(
    private val scope: CoroutineScope,
    private val target: MapTargetState,
    val serverConfig: ServerConfig,                 // MapScreen.kt:461
    initialRadiusKm: Float,
    initialMinRadiusKm: Float,
    initialPoiKind: PoiKind,
    initialDirectionDeg: Float?,
) {
    var radiusKm     by mutableFloatStateOf(initialRadiusKm)      // :449
    var minRadiusKm  by mutableFloatStateOf(initialMinRadiusKm)   // :450
    var poiKind      by mutableStateOf(initialPoiKind)            // :462
    var directionDeg by mutableStateOf(initialDirectionDeg)       // :463
    var spinning     by mutableStateOf(false); private set        // :458
    var error        by mutableStateOf<String?>(null)             // :460
    private var spinJob: Job? = null                              // was mutableStateOf at :459

    val inAppAvailable: Boolean get() = serverConfig.usable &&    // :1748-1750, :1777-1779
        (target.destination != null || target.route?.instructions?.isNotEmpty() == true)

    fun choose(c: RouteCandidate, origin: LatLon?, onFramed: (List<LatLon>) -> Unit) { … }  // :804-815
    fun resetForMode(m: TravelMode) { … }                         // :1516-1523
    fun toggle(mode: TravelMode, origin: LatLon?, onLanded: (List<LatLon>) -> Unit,
               onHaptic: () -> Unit, onNoLocation: () -> Unit) { … }   // :1751 / :1780
    private fun spin(…) { spinJob = scope.launch { … } }          // :1392-1513, verbatim
}

@Composable
fun rememberMapSpinState(target: MapTargetState): MapSpinState
```

The factory uses `rememberSaveable(saver = MapSpinStateSaver(scope, target)) { … }` so the four
values that are `rememberSaveable` today (`449`, `450`, `462`, `463`) keep surviving activity
recreation — see Cons #1, this is not optional.

```kotlin
private fun MapSpinStateSaver(scope: CoroutineScope, target: MapTargetState) =
    listSaver<MapSpinState, Any?>(
        save  = { listOf(it.radiusKm, it.minRadiusKm, it.poiKind.name, it.directionDeg) },
        restore = { v -> MapSpinState(scope, target, RoutingServer.load(),
            v[0] as Float, v[1] as Float, PoiKind.valueOf(v[2] as String), v[3] as Float?) },
    )
```

### `MapNavigationState` (concern F)

```kotlin
@Stable
class MapNavigationState(
    private val scope: CoroutineScope,
    private val target: MapTargetState,
    private val serverConfig: ServerConfig,
) {
    var navigating     by mutableStateOf(false);                    private set  // :514
    var navProgress    by mutableStateOf<NavEngine.Progress?>(null); private set // :515
    var rerouting      by mutableStateOf(false);                    private set  // :516
    /** Read by MapScreen and pushed to MapOverlays — see "how the holders talk". */
    var drivenFraction by mutableStateOf<Double?>(null);            private set  // :1355, :977
    private var lastRerouteMs = 0L                                   // was mutableLongStateOf at :517

    fun start(context: Context, origin: LatLon?, mode: TravelMode,
              hasTrip: Boolean, onError: (String?) -> Unit) { … }   // :982-1016
    fun stop(context: Context) { … }                                // :970-980 minus line 973/977
    suspend fun onFix(context: Context, fix: Fix, mode: TravelMode) { … }        // :1345-1389
}

@Composable
fun rememberMapNavigationState(target: MapTargetState, serverConfig: ServerConfig,
                               liveFix: Fix?, mode: TravelMode): MapNavigationState
```

### `MapRoadHazardState` (concern G) — the cleanest holder in the set

```kotlin
@Stable
class MapRoadHazardState {
    var ambientSpeedLimitKmh by mutableStateOf<Double?>(null);  private set  // :527
    var speedCameras  by mutableStateOf<List<SpeedCameras.Camera>>(emptyList());  private set // :534
    var speedSections by mutableStateOf<List<SpeedCameras.Section>>(emptyList()); private set // :535
    var sectionAvgKmh   by mutableStateOf<Double?>(null); private set        // :538
    var sectionLimitKmh by mutableStateOf<Double?>(null); private set        // :539

    // All four were mutableStateOf at :528-533 only because a local var in a
    // composable has to be. Nothing reads them in composition. Plain fields now.
    private var speedLimitWays: List<RoadRoulette.SpeedLimitWay> = emptyList()
    private var speedLimitWaysCenter: LatLon? = null
    private var speedLimitFetchMs = 0L
    private var speedLimitMisses = 0

    suspend fun trackAmbientLimits() { … }                    // :1026-1055
    suspend fun prefetchCamerasAndSections() { … }            // :1062-1082
    suspend fun warnOnCameras(tone: ToneGenerator?, navLimitKmh: () -> Double?) { … }  // :1145-1166
    suspend fun trackSectionAverage() { … }                   // :1174-1229
}

@Composable
fun rememberMapRoadHazardState(navigating: Boolean, navProgress: NavEngine.Progress?): MapRoadHazardState
```

### `MapSurfaceState` (concerns A + H)

```kotlin
@Stable
class MapSurfaceState(context: Context, private val attributionBottomMarginPx: Int) {
    val mapView = MapView(context)                              // :592
    val fogView = FogView(context)                              // :593
    var map      by mutableStateOf<MapLibreMap?>(null); private set  // :594
    var overlays by mutableStateOf<MapOverlays?>(null); private set  // :595

    fun attach() { … }                                          // :630-641
    fun detach() { … }                                          // :643-647
    suspend fun loadStyle(context: Context, darkTheme: Boolean) { … }   // :653-662
    fun bindMapListeners(onLongPress: (LatLon) -> Boolean, onTap: (LatLon) -> Boolean) { … } // :940-967
    fun setStoredFog(enabled: Boolean, radiusM: Float, traces: List<List<LatLon>>,
                     friendTraces: List<List<LatLon>>, darkTheme: Boolean) { … }  // :922-926
    fun setLiveFog(liveTrace: List<LatLon>, currentLocation: LatLon?) { … }       // :929-931
    fun setFogPeers(points: List<LatLon>) { … }                                   // :1129-1131
}

@Composable
fun rememberMapSurfaceState(darkTheme: Boolean): MapSurfaceState
```

### `MapConvoySpinState` (concern E)

```kotlin
@Stable
class MapConvoySpinState(private val target: MapTargetState) {
    var convoyName by mutableStateOf<String?>(null)             // :502

    /** MapScreen.kt:823 — everyone shows the offer's three, not their own. */
    fun displayCandidates(offer: GroupSpin?): List<RouteCandidate> =
        offer?.asRouteCandidates() ?: target.candidates

    fun commit(offer: GroupSpin, index: Int, onCommitted: (LatLon) -> Unit) { … }  // :828-840
    suspend fun resolveConvoyName(id: Int?) { … }                                  // :503-512
    fun resolveVoteRound(offer: GroupSpin?, votes: Map<String, Int>,
                         peers: Set<String>, me: String, onCommit: (Int) -> Unit) { … } // :858-870
}

@Composable
fun rememberMapConvoySpinState(target: MapTargetState, …): MapConvoySpinState
```

### `MapLocationState` (concern J)

```kotlin
@Stable
class MapLocationState(private val scope: CoroutineScope, private val context: Context) {
    var myLocation by mutableStateOf<LatLon?>(null)                     // :455
    var showBgLocationDisclosure by mutableStateOf(false)               // :444

    fun onFix(fix: Fix?) { … }                                          // :556-558
    fun fetchLocation(map: MapLibreMap?, onError: (String) -> Unit) { … }  // :714-734
    fun onLocationGranted(map: MapLibreMap?, onError: (String) -> Unit) { … }  // :760-770
}

@Composable
fun rememberMapLocationState(map: MapLibreMap?, liveFix: Fix?, onError: (String) -> Unit): MapLocationState
```

The three `rememberLauncherForActivityResult` calls (`738`, `744`, `772`) **cannot** move into the
class — they are composable functions. They stay in the factory and are invoked from it. This is the
holder in the set with the least payoff; it is mostly a code move.

---

## How the holders talk to each other

**The rule: a holder never holds a reference to a sibling holder. `MapScreen` is the only wiring
point.** Cross-holder work happens in exactly two shapes:

- **Command → lambda.** A holder that must reach outward takes a `(…) -> Unit` at the call site, and
  `MapScreen` supplies a lambda that calls the other holder. Direction is explicit at the call.
- **State → `LaunchedEffect`.** A holder that must *push* something to another concern instead
  publishes a state field, and `MapScreen` keys one small effect on it.

There is exactly **one** shared dependency: `MapTargetState`, which `MapSpinState`,
`MapNavigationState` and `MapConvoySpinState` all take in their constructor. That is a diamond, not
a cycle — `MapTargetState` depends on nothing. It exists because `destination`/`route` are genuinely
co-owned today: `spin()` writes `route` at `MapScreen.kt:1462`, `startNavigation()` writes it at
`1005`, and the reroute writes it at `1379`.

Concrete wirings, all in `MapScreen`:

**1. A spin result must suspend the camera** (`MapScreen.kt:1403`, `1468`, `1493`):

```kotlin
onSpin = {
    spin.toggle(mode, location.myLocation,
        onLanded = { pts -> camera.frameResult(surface.map, pts, fitBottomPaddingPx) },
        onHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        onNoLocation = { location.fetchLocation(surface.map) { spin.error = it } })
}
```
`MapSpinState` never sees `MapCameraState`, `MapLibreMap` or `HapticFeedback`. That is what keeps it
the closest thing to a testable class in the set.

**2. `choose()` and the convoy commit do the same park-and-frame** (`810-814`, `837-839`):
identical `onFramed` / `onCommitted` lambdas calling `camera.frameResult(…)`. Today this is copied
twice; after, it is one method with two callers.

**3. Navigation must clear the driven-fraction overlay** (`MapScreen.kt:977`, `1355`):

```kotlin
LaunchedEffect(surface.overlays, nav.drivenFraction) {
    surface.overlays?.setDrivenFraction(nav.drivenFraction)
}
```
`MapNavigationState` publishes `drivenFraction` (null on stop, `progress.drivenFraction` on each
fix) and never touches `MapOverlays`. **Honest cost:** today `setDrivenFraction` is called
synchronously inside the fix effect at `1355`; after, it lands one recomposition later. At 1 Hz
fixes and 12 m of `DRIVEN_STEP_METERS` quantisation (`MapLibreMap.kt:102`) that is invisible, but it
*is* a behaviour change and should be called out in the commit message rather than discovered later.

**4. Navigation stop must also clear the camera bearing** (`MapScreen.kt:973`):
`onExit = { nav.stop(context); camera.clearBearing() }`. Two calls at one site, in `MapScreen`.
Neither holder knows the other exists.

**5. The camera needs `navigating`, and the resume needs "is a spin on screen"** (`551`, `700-701`):
both are parameters to `rememberMapCameraState(…)`, computed in `MapScreen`:

```kotlin
val displayCandidates = convoy.displayCandidates(spinOffer)          // :823
val camera = rememberMapCameraState(
    mapView = surface.mapView, map = surface.map,
    myLocation = location.myLocation, liveFix = liveFix,
    defaultZoom = defaultZoom, navigating = nav.navigating,
    distanceToTurnMeters = nav.navProgress?.distanceToTurnMeters,     // :1243
    holdParked = spin.spinning || displayCandidates.isNotEmpty() || spinOffer != null,  // :701
)
```
One-way: nav → camera, spin → camera, convoy → camera. No holder calls back.

**6. `selectMode` must clear a convoy offer** (`1516-1527`):
`{ m -> Settings.setTripMode(m); spin.resetForMode(m); if (spinOffer != null) ConvoyLiveClient.clearSpinOffer() }`.
`MapScreen` sequences it; `MapSpinState` does not import `ConvoyLiveClient`.

**7. Overlay render** (`872-902`) stays one `LaunchedEffect` in `MapScreen`, because it is precisely
the place where five holders' state meets the map:

```kotlin
LaunchedEffect(surface.overlays, location.myLocation, target.destination, target.route,
               spin.radiusKm, mode, spin.directionDeg, nav.navigating, displayCandidates) {
    surface.overlays?.render(…, positionBearingDeg = camera.camTargetBearing?.toDouble())
}
```
Trying to push this into a holder is what would create a cycle. Leaving it in `MapScreen` is the
correct answer, and it is why `MapScreen` stays ~300 lines rather than ~80.

**Dependency graph (acyclic, by construction):**

```
MapTargetState ──┬── MapSpinState ──────┐
                 ├── MapNavigationState ┼──► MapScreen ──► MapCameraState ──► MapSurfaceState
                 └── MapConvoySpinState ┘         │
                        MapRoadHazardState ───────┤
                        MapLocationState ─────────┘
```

---

## Concrete changes required, including the imperative MapLibre glue

**`mapView` / `MapLibreMap` / `MapOverlays` / `FogView`.** These four stay exactly as they are —
imperative objects mutated from effects. `MapSurfaceState` owns them and is the *only* holder that
touches MapLibre types. `MapView` and `FogView` are constructed in the holder's constructor (they
are constructed in `remember {}` today at `MapScreen.kt:592-593`, so this is the same thing);
`mapLibreMap` and `mapOverlays` remain `mutableStateOf` because effects key on them arriving
asynchronously (`652`, `874`, `907`, `913`, `940`, `1087`, `1094`, `1120`).

`AndroidView(factory = { mapView })` at `MapScreen.kt:1547` becomes
`AndroidView(factory = { surface.mapView })`. Unchanged otherwise.

The `attributionBottomMarginPx` computation at `MapScreen.kt:434` uses `LocalDensity` and so must be
computed in the factory, then passed to the constructor. `fitBottomPaddingPx` (`429`) uses
`context.resources.displayMetrics` and stays in `MapScreen`, passed into `camera.frameResult(…)` per
call — it is a layout constant, not camera state.

**The 8 `rememberUpdatedState` back-refs disappear.** Today the map click listeners are registered
once in `LaunchedEffect(mapLibreMap)` (`MapScreen.kt:940`) and therefore capture stale values, which
is why `MapScreen.kt:937-939` wraps `displayCandidates`, `spinOffer` and `navigating` in
`rememberUpdatedState`. After the split, the listener closes over *holder instances*, which are
stable for the life of the composition, and reads their fields at callback time:

```kotlin
LaunchedEffect(surface.map) {
    surface.bindMapListeners(
        onLongPress = { at ->
            layersOpen = false                                   // :950
            if (nav.navigating) false                            // :951 — was navigatingRef.value
            else { target.setPin(at, "Dropped pin"); true }      // :952-955
        },
        onTap = { at ->
            layersOpen = false                                   // :958
            val idx = surface.candidateIndexAt(at) ?: return@bindMapListeners false  // :959-964
            val cs = convoy.displayCandidates(spinOffer)         // was candidatesRef.value
            if (idx >= cs.size) return@bindMapListeners false
            if (spinOffer != null) ConvoyLiveClient.sendSpinVote(idx)   // was spinOfferRef.value
            else spin.choose(cs[idx], location.myLocation) { pts -> camera.frameResult(…) }
            true
        },
    )
}
```

Caveat, stated so nobody trips on it: `spinOffer` here is a `collectAsStateWithLifecycle` value read
by the *lambda closure*, not by a holder field, so it would still be captured at effect-restart
time. Either it moves into `MapConvoySpinState` as a real field (collected in the factory via
`LaunchedEffect` + `.collect`, not `collectAsStateWithLifecycle`), or that one `rememberUpdatedState`
stays. **I would keep it** — one surviving ref is cheaper and more honest than converting a
lifecycle-aware collector into a hand-rolled one. Realistic score: **7 of 8 `rememberUpdatedState`
wrappers removed** (`937`, `939`, `1138`, `1139`, `1140`, `1173`, `1250`), one kept (`938`).

Note `speedTarget` at `MapScreen.kt:1250` disappears entirely because
`rememberEasedSpeedKmh(targetKmh)` takes the target as a parameter and the loop reads it through the
factory's own `rememberUpdatedState` — same mechanism, but hidden inside a 25-line helper instead of
sitting in the middle of a 1,400-line function.

**Seven snapshot states downgrade to plain fields**, because nothing reads them in composition:
`speedLimitWays` (`528`), `speedLimitWaysCenter` (`531`), `speedLimitFetchMs` (`532`),
`speedLimitMisses` (`533`), `lastRerouteMs` (`517`), `lastGestureMs` (`523`), `spinJob` (`459`).
Seven fewer `SnapshotMutableState` objects and seven fewer paths that can accidentally trigger a
recomposition of the whole screen. This is only possible *because* they moved into a class — inside
a composable, a mutable local has to be a snapshot state.

**Two behaviour deltas to declare up front, both benign but real:**
1. `setDrivenFraction` moves from a synchronous call to a one-frame-later effect (see wiring #3).
2. `MapCameraState.isActive()` is a function of a *parameter* (`navigating`) rather than a captured
   var, so the follow loop's restart key is now recomputed in `MapScreen`'s scope rather than read
   at `551`. Same value, same restart points — but worth a careful read in review.

---

## Migration plan

Every step is one commit, compiles on its own, and changes no behaviour unless noted. Steps 0a–0f
are pure `git mv`-shaped cut-and-paste inside the same package: **no import edits anywhere, in the
moved code or in any other file.** Do them first — they take `MapScreen.kt` from 3,193 to ~1,530
lines before a single line of logic is touched, so all the risky steps happen in a file you can read
in one sitting.

| step | what | MapScreen.kt after | risk |
|---|---|---|---|
| **0a** | Cut `2123-2279`, `3048-3099`, `3150-3193` → `ui/NavApps.kt` | 2,942 | none |
| **0b** | Cut `2956-3046`, `3101-3148` → `ui/SpeedHud.kt` | 2,802 | none |
| **0c** | Cut `2683-2954` → `ui/MapChrome.kt` | 2,530 | none |
| **0d** | Cut `210-211`, `2057-2121`, `2281-2681` → `ui/SpinCards.kt` | 2,062 | none |
| **0e** | Cut `1839-1958`, `2030-2055` → `ui/MapSearchDialog.kt` | 1,916 | none |
| **0f** | Cut `213-219` → `ui/TravelModeIcon.kt`; cut `1960-1996`, `1998-2028` → `ui/MapBottomStack.kt` | 1,833 | none |
| **1** | `MapTargetState` + `SpinResult`/`SpinResultHolder`/`seedRouteNavigation` → `ui/MapTargetState.kt`. Replace `destination`/`destinationName`/`route`/`candidates` with `target.*` throughout. | ~1,770 | low — mechanical rename, ~35 call sites |
| **2** | `MapRoadHazardState` → `ui/MapRoadHazardState.kt`. Four suspend loops, no other holder involved. | ~1,540 | low — the most isolated concern |
| **3** | `MapSurfaceState` → `ui/MapSurfaceState.kt`. Includes the fog feed and the six overlay-push effects that only need `overlays`. | ~1,350 | **medium** — MapView lifecycle, style reload and FogView attach must move verbatim; a mistake here shows up as a black map or a leaked GL renderer |
| **4** | `MapCameraState` + `rememberEasedSpeedKmh` → `ui/MapCameraState.kt` | ~1,090 | **medium** — the ease loop's `applied*` skip logic (`1289-1331`) is subtle; move it byte-for-byte |
| **5** | `MapNavigationState` → `ui/MapNavigationState.kt`. Introduces `drivenFraction` + its effect (wiring #3). | ~945 | low-medium — one declared timing change |
| **6** | `MapSpinState` + `MapSpinStateSaver` → `ui/MapSpinState.kt`. **Verify rotation keeps `radiusKm`/`poiKind`/`directionDeg` before merging.** | ~660 | medium — the `rememberSaveable` regression lives here |
| **7** | `MapConvoySpinState` → `ui/MapConvoySpinState.kt` | ~530 | low |
| **8** | `MapLocationState` + `rememberCircleFixes` → `ui/MapLocationState.kt` | ~400 | low |
| **9** | Extract the Scaffold's bottom `Column` (`1613-1794`) into `MapBottomStack(…)` in `ui/MapBottomStack.kt` | **~300** | low |
| **10** *(optional)* | Lift the pure cores out of the holders into Compose-free classes for tests and for `car/` reuse — see Effect-on section | ~300 | separate project |
| **11** *(optional)* | Move files 2–11 to package `com.jellemax.detour.ui.map` | ~300 | costs exactly 2 import lines: `MainActivity.kt` (`MapScreen`) and `RoutesScreen.kt` (`seedRouteNavigation`). `TravelMode.icon` stays in `ui`, so `HistoryScreen`/`RouteEditorScreen` are untouched. |

Do the step-0 commits with no reformatting whatsoever, so `git log -C --find-copies-harder` detects
them as moves and blame survives.

Verification per step is manual — the project has no Robolectric and no instrumentation tests (see
`app/build.gradle.kts`: `testImplementation("junit:junit:4.13.2")` and nothing else). The realistic
smoke list after steps 3–6: launch → follow works → pan parks the camera → drive off resumes it →
spin lands and frames → tap a pin → Go → reroute → arrive → rotate the device.

---

## Pros

1. **Zero new dependencies, zero module-graph change.** The project deliberately has no DI and no
   ViewModel; app state already lives in `object` singletons exposing `StateFlow`. A plain class
   holding `mutableStateOf` is the same shape of thing, one scope down. Nothing new to learn.
2. **Lowest behavioural risk of any split.** `rememberCoroutineScope()` has *exactly* the lifetime
   the 32 existing `LaunchedEffect`s have. Nothing gets longer-lived, so no new leak surface, no new
   "why is this still running in the background" class of bug. On an app that holds a 1 Hz GPS
   request and a WebSocket, that matters.
3. **Removes 7 of 8 `rememberUpdatedState` back-refs** (`937`, `939`, `1138-1140`, `1173`, `1250`).
   These exist purely because a long-lived callback closes over a short-lived composable's locals; a
   stable holder makes them unnecessary. This is a real reduction in a genuinely confusing idiom.
4. **Downgrades 7 snapshot states to plain fields** (`459`, `517`, `523`, `528`, `531`, `532`,
   `533`) — impossible while they are locals in a composable.
5. **Same-package placement means every step is cut-and-paste.** No import churn in the 5 files that
   consume MapScreen symbols. That is what makes the 12-step plan realistic rather than aspirational.
6. **Incremental and abortable.** Stop after step 0f and you have already halved the file with zero
   risk. Stop after step 2 and the hardest-to-read concern (road hazards) is out. Nothing is
   all-or-nothing.
7. **Creates the seams that any future improvement needs.** `MapRoadHazardState` and
   `MapNavigationState` are one refactor away from Compose-free logic classes that `car/` could
   share — see the Android Auto section for what that would actually take.
8. **It is a strict subset of the ViewModel design.** If you later want survival across
   configuration change, each holder becomes a ViewModel by changing `remember {}` → `viewModel()`
   and `rememberCoroutineScope()` → `viewModelScope`. The class bodies do not change. Nothing here
   has to be undone.

---

## Cons and risks

1. **`rememberSaveable` regression — the sharpest edge.** `radiusKm` (`449`), `minRadiusKm` (`450`),
   `poiKind` (`462`) and `directionDeg` (`463`) are `rememberSaveable` today. `MainActivity` has no
   `android:configChanges` and no `screenOrientation` in `app/src/main/AndroidManifest.xml:60`, so
   the activity *is* recreated on rotation and this is live behaviour, not theory. Moving them into
   a `remember { MapSpinState(...) }` silently loses it. The fix (a `listSaver`) is 15 lines and is
   in the design above, but it is exactly the kind of thing that gets skipped, and the failure is
   silent — a slider that resets. **Step 6 must not merge without a rotation check.**
2. **Does not survive activity recreation any better than today.** An in-flight `spinJob` (`459`)
   is cancelled by rotation now and will still be cancelled after. A ViewModel proposal genuinely
   fixes that and this one cannot. `SpinResultHolder` (`369-385`) already patches the *result* case
   with a process-scoped singleton; the *in-flight* case stays broken. If the panel's priority is
   configuration-change robustness, this proposal loses on the merits.
3. **`collectAsStateWithLifecycle()` inside a factory does not isolate recomposition.** A
   `@Composable` function returning a value is non-restartable, so its state reads are attributed to
   the *caller's* recompose scope. Moving the 21 collectors at `MapScreen.kt:438-499` into holder
   factories relocates the source lines and changes nothing about what recomposes. Getting real
   isolation would mean collecting into holder fields from a `LaunchedEffect`, which trades away
   `collectAsStateWithLifecycle`'s lifecycle-awareness. I am not proposing that trade, and anyone
   selling this refactor as a recomposition win is wrong.
4. **Roughly half the holders are untestable in this project.** See the next section for the
   line-by-line breakdown. `MapSurfaceState` constructs `MapView`/`FogView`; `MapCameraState` calls
   `cameraForPoints`/`setCamera` on a `MapLibreMap` and uses `withFrameNanos`; `MapLocationState`
   wraps `ActivityResultLauncher`s. No Robolectric means no test for any of it.
5. **Compose stability is now a thing reviewers must watch.** A holder passed as a parameter to a
   child composable defeats skipping unless the class is `@Stable`. The current leaf composables all
   take primitives (`SpinDock` at `2285-2298`, `SpinSheet` at `2372-2395`, `CandidatesCard` at
   `2553-2564`) and must keep doing so. Nothing enforces this; it is a review convention.
6. **The `MapTargetState` diamond preserves a latent bug rather than fixing it.** `route` is written
   by `spin()` (`1462`), `startNavigation()` (`1005`) and the reroute (`1379`). Last writer wins, and
   a reroute landing while a spin is in flight can still clobber. Centralising the field makes the
   race *visible* in one class, which is progress, but the refactor does not fix it and should not
   claim to.
7. **+11% total lines and 16 files in a flat `ui/` directory**, taking `ui/` from 19 to 34 `.kt` files.
   Some of that is genuinely worse — finding "the map code" now means knowing which six of those 36
   files are map files. The `Map*` prefix is the only navigation aid, and it is weaker than a
   package.
8. **Nothing enforces the split.** The next feature can add `var` #60 to `MapScreen` and no build
   step complains. Six months of that and you are back where you started, only now spread over 16
   files. A ViewModel at least gives a natural place people default to.
9. **Stale-capture footgun moves rather than disappears.** `remember { XxxState(param) }` with a
   changing `param` captures the first value forever. The design above avoids it by passing
   changing values to *methods* and to `LaunchedEffect` keys, never to constructors — but that is a
   discipline, and a plausible-looking wrong version compiles fine.
10. **Two declared behaviour changes** (one-frame `setDrivenFraction` delay; `cameraActive` computed
    from a parameter). Both should be benign. "Should be" is doing work in that sentence, and there
    are no tests to check it.

---

## Effect on…

### Unit testability (plain JUnit4, Android-free logic only, no Robolectric)

The honest headline: **this pattern creates test *seams*; it does not by itself create testable
code, and most of what becomes testable was already extractable without it.**

**Already pure, testable today, no refactor needed** — these are functions, not holders:
`smoothBearing` (`224-230`), `sectionExitGate` (`300-314`), `leadingSpinIndex` (`361-367`),
`GroupSpin.asRouteCandidates` (`329-343`), `List<RouteCandidate>.asSpinCandidates` (`347-355`),
`navAppUsableDirectly` (`2154-2164`). Six functions, ~60 lines. The existing
`app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt` proves this style works in this
project. **The split makes them easier to find; it is not what makes them testable.**

**Extractable to pure functions during the split, and worth doing** (this is where step 10 earns
its keep): the camera ease step (`1300-1324` → `fun easeStep(state, targets, dt): CameraPose`), the
section-average state machine (`1174-1229` → a `SectionTracker` with `fun onFix(pos, speed, bearing,
now): SectionReading?`), the speed-limit miss counter (`1045-1054`), and the camera-warning arm/
disarm rule (`1151-1165`). ~150 lines that become genuinely test-worthy — the section tracker in
particular has four exit conditions (`1217-1221`) and a documented past bug (`290-299`) and has never
been tested.

**Holders testable as classes:**
- `MapTargetState` — yes, if `mutableStateOf` works under plain JUnit. It touches only
  `SpinResultHolder`, a JVM `object` with a `MutableStateFlow`.
- `MapRoadHazardState` — only with a seam. It calls `RoadRoulette.speedLimitWays`,
  `RoadRoulette.snapSpeedLimitKmh` and `SpeedCameras.near` directly (`1037`, `1045`, `1075`), all
  `object` singletons doing network IO. Injecting them as lambdas is a further change I am not
  proposing here.
- `MapSpinState` — no. `spin()` calls `ExploredArea.load()`, `RoutingServer.roundTrip`,
  `pickCandidate`, `RoundTripPlanner.plan`, `Settings.avoidHighways.value` — five singletons.

**Holders untestable without Robolectric, full stop:** `MapSurfaceState` (constructs `MapView`,
`FogView`), `MapCameraState` (`MapLibreMap` parameters, `withFrameNanos`), `MapNavigationState`
(`Context`, `NavRelay`, `BleNavServer`, `TripTrackingService`), `MapLocationState`
(`ActivityResultLauncher`, `LocationServices`), `MapConvoySpinState` (`ConvoyLiveClient`,
`Groups.list` over the network).

**Score: 1 of 8 holders testable as written, 1 more with an injected seam, 6 not at all.**

**Unverified assumption I am flagging rather than hiding:** whether `mutableStateOf` works in a
plain `testDebugUnitTest` JVM run in this project. Compose runtime is on the unit-test classpath
(`implementation` deps of a module are visible to that module's unit tests) and
`SnapshotMutableStateImpl` is pure JVM, so it *should* work — but this repo has never done it, and
I have not built anything to check (the task forbade running Gradle, correctly). If it does not
work, holder tests would need the state to live in plain fields with the `mutableStateOf` surface
layered on top, which is more work. **Treat this as a 30-minute spike, not a promise.**

### Recomposition / performance

**Expect a wash, with one small measurable improvement and one small measurable risk.**

- *Improvement:* 7 fewer `SnapshotMutableState` allocations and 7 fewer possible recomposition
  triggers (Cons list, item in Pros #4). 7 fewer `rememberUpdatedState` wrappers, each of which is
  itself a `MutableState` plus a `SideEffect`.
- *No change:* the two `withFrameNanos` loops (`1251-1263`, `1271-1333`) run identically. The
  `applied*` epsilon skip at `1320-1331` — the thing that actually keeps a cruising phone from
  burning its frame budget on GL redraws and fog invalidates — moves byte-for-byte.
- *No change:* the Scaffold body's inner lambdas (`1542`, `1613`, `1624`, `1699-1706`) are already
  separate recompose scopes, so the fine-grained scoping people expect from "splitting a big
  composable" is *already present today*. `displaySpeedKmh` read at `1643` already only recomposes
  the bottom `Column`'s content lambda, not the whole screen.
- *No change:* the 21 `collectAsStateWithLifecycle` reads stay in `MapScreen`'s scope (Cons #3).
- *Risk:* a holder passed into a leaf composable without `@Stable` turns a skippable composable into
  a non-skippable one. Mitigation: `@Stable` on all eight classes, and keep leaf composables taking
  primitives.

If the panel's goal is frame time on a mid-range phone at 100 km/h, this proposal is not the lever.
The lever is already pulled — it is the `applied*` skip logic at `MapScreen.kt:1320-1331` and
`FogView`'s trace decimation and `RenderEffect` blur in `MapLibreMap.kt:492-548`.

### Android Auto code reuse (`app/src/main/java/com/jellemax/detour/car/`)

**Net effect today: zero. And I want to be blunt about that, because it is the weakest part of this
proposal.**

The duplication is real and substantial:

| phone | car | what |
|---|---|---|
| `MapScreen.kt:236-238`, `253-255` | `car/CarMapRenderer.kt:53-55`, `67-69` | identical `CAM_POS_TAU`/`CAM_BEARING_TAU`/`CAM_ZOOM_TAU` and the three epsilons |
| `MapScreen.kt:279` | `car/CarMapRenderer.kt:78` | `CIRCLE_FIX_POLL_MS = 120_000L`, with a comment pointing at MapScreen |
| `MapScreen.kt:1106-1119` | `car/CarMapRenderer.kt:139-150` | the circle-fix poll loop |
| `MapScreen.kt:1024-1055` | `car/SpinScreen.kt:265-296` | ambient speed-limit prefetch + snap + miss counter |
| `MapScreen.kt:1345-1389` | `car/NavScreen.kt:225-274` | progress / arrival / reroute — `NavScreen.kt:242` literally says *"Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect."* |
| `MapScreen.kt:1392-1512` | `car/SpinScreen.kt:312-346` | the spin |

**A `remember*State` holder cannot be reused by any of it.** A car screen has no composition, so it
cannot call `remember`; `MapCameraState` uses `withFrameNanos`, which
`car/CarMapRenderer.kt:56-62` explicitly cannot use (the VirtualDisplay keeps rendering with the
phone's screen off, and vsync callbacks do not fire — hence its 33 ms timer); and `mutableStateOf`
is useless to a car template that invalidates manually.

What *would* fix it is extracting Compose-free cores — a `CameraEase`, a `SpeedLimitTracker`, a
`SectionTracker`, a `RouteFollower` — into `:shared` or `app/.../data`, and having both
`MapRoadHazardState` and `car/SpinScreen` wrap the same object. This proposal makes that easier by
gathering each concern's logic into one class in one file where the Compose-only parts are visibly
separable. **It makes the extraction obvious; it does not perform it.** That is step 10, and it is
honestly a *different* proposal wearing this one as a prerequisite.

There is also a small *negative*: after the split the phone's copy lives in
`ui/MapRoadHazardState.kt` while the car's lives in `car/SpinScreen.kt`, one more directory apart
than today. Drift gets slightly easier.

### Reviewability of future diffs

**This is the clearest win, and probably the real reason to do it.**

- Today, changing the reroute cooldown at `MapScreen.kt:1373` produces a diff whose surrounding
  context is a 1,419-line function. `git log -L :MapScreen:MapScreen.kt` is unusable at that size,
  and `git blame` on the file returns a wall of unrelated commits.
- After: `ui/MapNavigationState.kt` is ~160 lines. Its git history is *navigation* history. A PR
  touching only the follow camera cannot silently also touch the spin.
- The pattern also makes "what state does this screen have" answerable: eight class declarations
  instead of 59 scattered `remember` lines between `MapScreen.kt:437` and `1250`.
- Caveat: do the step-0 moves with no reformatting, or `git log -C` will not follow the code and you
  will have *destroyed* the history rather than reorganised it. This is a real risk and the reason
  the plan separates pure moves (0a–0f) from logic changes (1–9).

---

## What this proposal explicitly does NOT solve

1. **Process death and configuration change.** Behaviour is byte-for-byte what it is today: the
   `object` singletons and `SpinResultHolder` (`369-385`) survive activity recreation; everything
   else does not. In-flight spins still die on rotation.
2. **The singleton coupling.** `Settings`, `SavedPlaces`, `TraceStore`, `TripTrackingService`,
   `ConvoyLiveClient`, `FriendFog`, `CircleFixes`, `Account`, `RoutingServer`, `Groups`,
   `SpeedCameras`, `RoadRoulette`, `ExploredArea`, `SyncClient`, `NavRelay`, `BleNavServer`,
   `PushToTalk` are still reached directly from inside the holders. Without DI there is no seam, and
   this is the direct cause of "6 of 8 holders untestable".
3. **Test coverage.** Adds none. Creates the opportunity for ~150 lines' worth (step 10).
4. **The Android Auto duplication.** See above — enables, does not perform.
5. **The `route` write race** between spin, nav-start and reroute (`1462` / `1005` / `1379`).
6. **`SpinResultHolder` as a process-global mutable singleton** reachable from `RoutesScreen.kt:202`
   via `seedRouteNavigation`. It moves file; its design is untouched.
7. **The imperative MapLibre boundary.** `MapOverlays` and `FogView` remain objects mutated from
   effects with hand-written keys. Six push-effects (`872`, `904`, `911`, `1085`, `1091`, `1120`) and
   three fog-feed effects (`921`, `928`, `1128`) survive as effects, just relocated.
8. **Total code size.** Grows ~11%.
9. **Enforcement.** No lint rule, no architectural test, nothing stopping regression.
10. **The other big files.** `MapLibreMap.kt` (764), `SettingsScreen.kt` (1,199),
    `FriendsScreen.kt` (939) are untouched.
11. **`MapScreen`'s remaining ~300 lines.** The overlay-render effect (`872-902`) still reads five
    holders, because that is genuinely where they meet. There is no version of this that ends with a
    50-line `MapScreen`.

---

## Honest verdict

**This is the right choice when:**

- You want the 3,193-line file split *this month*, with the lowest possible chance of introducing a
  regression in code that runs while someone is riding a motorbike. Identical coroutine lifetimes is
  not a small property — it is the property.
- You value reviewable diffs and meaningful `git blame` over architectural purity. That is the
  actual day-to-day pain of a 3,193-line file, and this fixes it completely.
- You want to keep the project's existing character: no DI, no framework, plain Kotlin classes and
  `StateFlow` singletons. A `class MapCameraState` is the same kind of object as `object Settings`.
  Adding `lifecycle-viewmodel-compose` would be the first framework-shaped decision this codebase
  has made.
- You want optionality. Steps 0a–0f alone halve the file at zero risk, and every later step is
  independently abortable. If the ViewModel argument wins in six months, every holder converts
  mechanically and nothing here is wasted.

**This is the wrong choice when:**

- **Configuration-change survival is the actual goal.** If losing an in-flight spin on rotation is a
  real complaint, a ViewModel is the correct answer and this proposal cannot substitute for it. One
  dependency, `viewModelScope` instead of `rememberCoroutineScope()`, done. I would not argue
  against that on the merits — I would argue that it is a different problem from "the file is 3,193
  lines".
- **Test coverage is the actual goal.** Then the highest-value work is extracting Compose-free logic
  classes into `:shared` (where `GroupsTest`, `ParsingTest` and `RoutesTest` already live and run),
  and the holder split is at best a prerequisite and at worst a detour. Ask for the tests directly.
- **De-duplicating phone and Android Auto is the actual goal.** Same answer: Compose-free cores
  first. This proposal helps you see them; it does not deliver them, and pretending otherwise would
  be the easiest way to lose credibility with this panel.
- **You want the split to be permanent.** Nothing enforces it. If the team is more than one person
  and nobody owns the convention, a ViewModel's gravity is genuinely worth the dependency.

**My summary claim, stated so it can be attacked precisely:** this is the *cheapest correct* split
of `MapScreen.kt` and a strict prerequisite for every more ambitious version of the work. It buys
reviewability, locality and eight named seams, for one week of mechanical work and roughly zero
behavioural risk. It buys almost nothing in testability, performance or Android Auto reuse, and it
loses outright to a ViewModel on configuration-change survival. If the panel's ranking criterion is
"which proposal most improves the codebase's architecture", I expect to place second. If it is
"which proposal will actually ship, uncorrupted, and leave the door open to the others", I think
this one wins.
