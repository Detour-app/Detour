# Proposal 3 — ViewModel + UiState

Target: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`, 3193 lines, one
1419-line `@Composable` (`MapScreen.kt:419-1837`) plus 25 private composables and
11 private top-level helpers.

Pattern championed here: **one or more `androidx.lifecycle.ViewModel`s owning
immutable state exposed as `StateFlow`, with the composable reduced to a
renderer that reads state and emits events.** This is what Android's
architecture guidance recommends, and this document argues for it — but the
project has deliberately shipped without a ViewModel, and the honest case is
narrower than the pattern's reputation suggests. Both are below.

Every claim cites `MapScreen.kt:NNN` (or another file) so it can be checked.

---

## Cross-cutting concerns I identify in MapScreen.kt

Twelve, in rough order of how much of the file each one touches. These are the
seams; any split that cuts across one of them pays for it later.

### 1. Destination identity — the spine of the file

`destination` / `destinationName` / `route` / `candidates` are four `remember`ed
vars (`MapScreen.kt:454-457`, `464`) written from **nine** call sites:

| writer | line |
|---|---|
| seeded from the process-scoped holder | `453-457`, `464` |
| `choose()` — commit a local spin candidate | `804-808` |
| `commitSpinCandidate()` — commit a convoy spin | `831-834` |
| map long-press ("Dropped pin") | `952-955` |
| `startNavigation()` re-fetch | `1005` |
| reroute while navigating | `1379` |
| `spin()` round-trip branch | `1462-1464` |
| `spin()` POI branch | `1490` |
| `selectMode()` reset | `1520-1523` |
| saved-place chip pick | `1675-1677` |
| search-result pick | `1826-1828` |

…and mirrored back out to a process-global on every change (`467-469`). It is
read by the overlay render (`886-901`), the nav loop (`1360-1372`), both bottom
cards (`1745-1750`, `1773-1779`), the shortcut chips (`1667`), and the
save-pin dialog (`1810-1818`).

**This is one connected state graph.** It is the single most important fact for
this proposal: it rules out "one ViewModel per feature" as a clean split, because
spin, navigation, search, saved places and the convoy vote all write the same
four fields.

### 2. Camera authority

`followMe` (`521`), `camSuspended` (`522`), `lastGestureMs` (`523`), `camTarget`
/ `camTargetBearing` / `camTargetZoom` (`547-549`), and the two derived flags
`cameraActive` / `following` (`551-553`).

Written by: the raw touch listener (`669-694`), the resume-on-driving rule
(`696-712`), `choose()` (`810-813`), `commitSpinCandidate()` (`837-838`),
`startNavigation()` (`987`), `spin()` (`1403`), the fix→target effect
(`1236-1245`), the follow toggle (`1586-1589`), the shortcut pick (`1678-1679`),
the search pick (`1829-1830`). Consumed by exactly one place: the frame loop at
`1265-1333`.

### 3. The imperative MapLibre surface

`mapView` (`592`), `fogView` (`593`), `mapLibreMap` (`594`), `mapOverlays`
(`595`). Lifecycle at `629-648`, style/overlay rebuild at `650-663`, touch
listener at `669-694`, listeners at `940-968`, overlay pushes at `872-932` and
`1085-1132`, direct camera calls at `725-726`, `814`, `839`, `1468`, `1492-1494`,
`1680`, `1831`, `1275-1277`, `1326`, driven-fraction at `977` and `1355`, and
the `AndroidView` host at `1547`.

None of this can live in a ViewModel. Section "How the imperative MapLibre
surface is handled" below is where this proposal earns or loses.

### 4. Fog of war — a sibling `View`, not Compose

`FogView` is added as a child of `MapView` (`656-661`) and torn down at `643`.
Fed from four separate effects (`917-932`, `1124-1132`), invalidated from the
map's camera callbacks (`945-946`). It reads `traces` (`480`), `friendTraces`
(`485`), `liveTrace` (`488`), `myLocation` (`455`), `circleFixes` (`1105`),
`convoyPeers` (`497`) — i.e. it cuts across almost every other concern.

### 5. The permission ladder

Fine/coarse/activity-recognition/notifications (`782-801`) → `onLocationGranted`
(`760-770`) → background-location disclosure dialog (`444`, `768`, `1798-1808`)
→ background permission (`738-740`, `1802-1803`) → microphone, but only once a
convoy is joined (`744-758`). Plus a fourth, independent check inside
`PushToTalkButton` (`2919-2922`).

### 6. Context-bound side effects

Everything that needs an Android `Context` and therefore cannot move into a
plain ViewModel: the lifecycle observer driving `TripTrackingService.setUiVisible`
and `PushToTalk.stopTalking` (`597-625`), `startMonitoring` (`762`),
`TripTrackingService.start/stop` (`989`, `1636`, `1756`, `1785`, `1789`),
`NavRelay.send/clear` (`978`, `1356`), `BleNavServer.send/sendStats/clear`
(`979`, `1341`, `1357`), the fused-location client (`720-722`), `ToneGenerator`
(`1141-1144`, `1163`), and the four `startActivity` handoffs (`3150-3193`).

### 7. Road-hazard sensing — three independent state machines

Ambient speed limit with prefetch, snap and 3-miss clear (`1018-1056`, state at
`527-533`); speed-camera/section prefetch (`1058-1083`, state at `534-535`);
the chime with per-camera re-arm (`1134-1167`); the trajectcontrole average
(`1169-1230`, state at `538-539`, gate helper at `290-314`, constants at
`283-288`).

All four are `TripTrackingService.lastFix.collect { }` loops holding
loop-local mutable state. **They are the most separable thing in the file** —
their only coupling to the rest is `navigating` gating the ambient fetch
(`1025`), `navProgress?.speedLimitKmh` winning for the chime (`1160`), and
`speedCameras` going to the overlay (`1087-1089`).

### 8. Convoy / group spin

Collectors at `491-499`, name resolution at `502-512`, mic permission at
`747-758`, `displayCandidates` substitution at `823`, `commitSpinCandidate` at
`825-840`, the vote-resolution rule at `842-870`, peer markers at `1094-1096`,
mode-switch clear at `1526`, the PTT button (`1604-1611`), and the whole
share/vote/lead branch of the candidates card (`1713-1739`). Helpers at
`322-367`.

### 9. One `error: String?` for everything

Declared at `460`, written from ten places (`728`, `731`, `778`, `984`, `991`,
`998`, `1011`, `1400`, `1459`, `1498-1508`), read exactly once — inside
`SpinSheet` (`1771`). A location-permission failure is therefore invisible
unless the user happens to have the spin sheet expanded.

### 10. Per-frame easing loops

Speed readout (`1247-1263`) and camera (`1265-1333`), with their tuning
constants at `232-255`. Both are `withFrameNanos` loops. `displaySpeedKmh`
(`550`) is written **every frame** and read at `1641`/`1643` — inside the
`Scaffold` content lambda, which is a recomposition scope. So today the entire
bottom half of the map tree recomposes at display refresh rate whenever the
vehicle is moving. Remember this for the perf section.

### 11. Bottom-card arbitration

The `BottomCard` enum (`417`), the `when` that picks one (`1689-1694`), and two
copies of the same "hold the last non-empty value so the exit animation has
content" trick (`1654-1655` for `stats`, `1697-1698` for `displayCandidates`).

### 12. Composition lifetime — the concern nobody declared

`MainActivity.kt`'s `AppRoot` switches screens with an `AnimatedContent` over a
`Screen` enum, with `MapScreen(...)` as one `when` branch. There is no
navigation-compose and no `SaveableStateHolder`. **So walking to the Hub and back
disposes the whole MapScreen composition**, which means:

- every `remember` here resets (`myLocation`, `navigating`, `navProgress`,
  `speedCameras`, `circleFixes`, `speedLimitWays`, `sectionAvgKmh`…);
- every `rememberSaveable` here also resets — `rememberSaveable` only survives
  *activity* recreation, and only while the subtree is composed;
  `radiusKm`/`minRadiusKm`/`poiKind`/`directionDeg`/`settingsCollapsed`
  (`449`, `450`, `462`, `463`, `526`) go back to defaults;
- every `LaunchedEffect(Unit)` re-runs: the full sync (`568-578`), the
  permission check (`782-801`), the speed-camera prefetch with its loop-local
  `center`/`lastFetchMs` (`1062-1083`), the chime's `warnedAt` (`1145-1167`),
  and the trajectcontrole machine's `active`/`accMeters`/`entryMs`
  (`1174-1230`) — so glancing at the Hub mid-section loses the running average
  and re-hits Overpass;
- the `MapView` is recreated (`592`) and the style reloaded (`652-663`).

`SpinResultHolder` (`369-385`) is a hand-rolled workaround for exactly this, for
exactly one field group, with a comment saying so.

**This is the strongest single argument for this proposal**, and it is the one
the other proposals in this folder cannot address by moving code between files.

---

## The pattern, stated precisely

### How many ViewModels

Concern 1 rules out a clean feature split of the core. My recommendation:

**Two ViewModels, plus deliberately no ViewModel for several things.**

1. **`MapViewModel`** — the connected core: destination identity (concern 1),
   spin, navigation, camera intent (concern 2), error surface (concern 9),
   convoy commit/vote resolution (concern 8). ~430 lines. This is a big class
   and I am not going to pretend otherwise; it is big because the state it owns
   is genuinely one graph.

2. **`MapHazardsViewModel`** — concern 7 only. It shares *nothing* with
   `MapViewModel` except two scalar inputs pushed in by the composable
   (`setNavigating(Boolean)`, `setNavSpeedLimitKmh(Double?)`) and one scalar
   output the composable forwards to the overlay. ~150 lines, and it is a thin
   lifetime/scope wrapper over three plain classes (below) that hold all the
   logic.

3. **No ViewModel** for: `SearchDialog` (`1839-1958`) — its state is
   dialog-lifetime by definition and a VM would only make it outlive the dialog,
   which is wrong; `SavePinDialog` (`2030-2055`); `NavButton`/`NavIconButton`
   menu state (`2248`, `3061`); `PushToTalkButton`'s `pressed` (`2907`);
   `ActiveTripCard`'s clock (`3106`). Reaching for a ViewModel for these is the
   failure mode this pattern is famous for.

4. **No ViewModel** for the ~19 values that are already `StateFlow` on an
   `object` singleton and already read with `collectAsStateWithLifecycle()`:
   `Settings.tripMode` (`448`), `fogEnabled` (`470`), `shareFog` (`483`),
   `theme` (`588`), `fogRadiusMeters` (`590`), `defaultZoom` (`544`), `mapIcon`
   (`545`), `routeColor` (`546`), `Account.username` (`471`), `SavedPlaces.places`
   (`438`), `TraceStore.version` (`479`), `FriendFog.traces` (`484`),
   `TripTrackingService.stats/lastFix/liveTrace` (`486-488`), and the six
   `ConvoyLiveClient` flows (`491-499`). **These stay collected in the composable
   that needs them.** Funnelling them through the ViewModel would be pure
   pass-through — see the Cons section, where this risk gets its own heading.

### Scope

`viewModel()` with no explicit owner resolves `LocalViewModelStoreOwner`, which
`ComponentActivity.setContent` provides as the **Activity**. Since `AppRoot`
provides no other owner, both ViewModels are **Activity-scoped**: they survive
configuration change *and* the Hub round-trip that currently wipes everything
(concern 12), and they are cleared when the Activity finishes.

That scope is the whole point. There is no finer scope available without adding
navigation-compose (a separate, larger decision).

A useful consequence: `RoutesScreen` composes inside the same Activity, so
`viewModel<MapViewModel>()` there returns the *same instance*. That is how
`seedRouteNavigation` (`400-414`) and `SpinResultHolder` get deleted.

### How Android-context work stays out

Three mechanisms, in order of preference:

**(a) Keep it in the composable, behind an event callback.** Permission
launchers (`738-780`) cannot leave the composable at all —
`rememberLauncherForActivityResult` is a Compose API. So the VM exposes
`onLocationGranted()` / `onLocationDenied()` and the launcher calls them
(`775-779`). Same for the lifecycle observer at `597-625` and the `AndroidView`
at `1547`.

**(b) A one-shot effect channel** for the handful of Context calls the VM's own
logic needs to trigger:

```kotlin
private val _effects = Channel<MapEffect>(Channel.BUFFERED)
val effects = _effects.receiveAsFlow()
```

collected once in the composable:

```kotlin
LaunchedEffect(Unit) {
    vm.effects.collect { fx ->
        when (fx) {
            is MapEffect.StartTracking ->
                TripTrackingService.start(context, fx.destLat, fx.destLon)
            MapEffect.StopTracking -> TripTrackingService.stop(context)
            MapEffect.StartMonitoring -> TripTrackingService.startMonitoring(context)
            is MapEffect.SendNavRelay -> {
                NavRelay.send(context, fx.progress, fx.currentSpeedKmh)
                BleNavServer.send(context, fx.progress, fx.currentSpeedKmh)
            }
            MapEffect.ClearNavRelay -> { NavRelay.clear(context); BleNavServer.clear(context) }
            MapEffect.Haptic -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            is MapEffect.FitCamera -> surface.fit(fx.points, FIT_PADDING_PX, fitBottomPaddingPx)
            is MapEffect.FlyTo -> surface.flyTo(fx.at, fx.zoom, fx.durationMs)
            MapEffect.AskBackgroundLocation -> bgLocationLauncher.launch(...)
            MapEffect.RequestCurrentLocation -> fetchLocation()
        }
    }
}
```

Ten effect types replacing ten one-line calls. That is the honest cost of this
mechanism and it is charged in the Cons section.

**(c) Never `AndroidViewModel(application)`.** It is the obvious shortcut and it
would destroy the main claimed benefit: a ViewModel holding an `Application` is
no longer constructible in a plain JVM JUnit4 test, and this project has no
Robolectric (see `app/build.gradle.kts:163`, `junit:junit:4.13.2` only).

---

## New dependencies required and their cost

The project is at Compose BOM `2024.09.02`, Kotlin `2.0.20`, AGP `8.5.2`,
lifecycle `2.8.6` (`app/build.gradle.kts:136`, `142-143`, root
`build.gradle.kts:4-5`), and already has `androidx.activity:activity-compose:1.9.2`
(`app/build.gradle.kts:140`), which is what supplies `LocalViewModelStoreOwner`
and the Activity's `SavedStateRegistry`.

```kotlin
// app/build.gradle.kts, dependencies { }
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.6")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

- `lifecycle-viewmodel-compose:2.8.6` — pinned to the version already used by
  `lifecycle-runtime-compose`/`-ktx` (`142-143`), so no version skew and no BOM
  change. Brings `viewModel()`, and transitively `lifecycle-viewmodel` (which is
  where `viewModelScope` lives since 2.8; the old `-viewmodel-ktx` artifact is a
  shim and is not needed).
- `lifecycle-viewmodel-savedstate:2.8.6` — arrives transitively anyway; declared
  explicitly because `SavedStateHandle` is used directly and an implicit
  transitive is the kind of thing that breaks on the next BOM bump.
- `kotlinx-coroutines-test:1.8.1` — matches
  `kotlinx-coroutines-play-services:1.8.1` (`149`) and the version `:shared`
  uses (`shared/build.gradle.kts:37`). **Required, not optional**: `viewModelScope`
  dispatches on `Dispatchers.Main.immediate`, which throws in a plain JVM test
  without `Dispatchers.setMain`. Any test that exercises a VM method that
  `launch`es needs it.

**Size cost:** all three lifecycle-viewmodel artifacts together are on the order
of 100 KB of classes pre-R8, and R8 is on for release
(`app/build.gradle.kts:107`). Negligible.

**Real cost:** none of these is the expensive part. The expensive part is that
this is the project's first architectural framework dependency in the UI layer,
and it establishes a convention that the other 18 screens will be expected to
follow (`SettingsScreen.kt` 1199 lines, `FriendsScreen.kt` 939,
`CirclesScreen.kt` 716 are the next candidates). Adopting it for one screen and
not the rest is worse than either extreme.

**Not required:** no Hilt/Koin. There is no DI framework today and this proposal
does not add one — see "What this does NOT solve".

---

## Proposed file layout

Everything stays in package `com.jellemax.detour.ui` (and one new
`com.jellemax.detour.nav`). Keeping the package means each move is a *pure* move:
no import churn for the ~15 helpers these composables share (`glassCardColors`,
`glassBorder`, `formatDistanceKm`, `isAppDarkTheme`, `MapOverlays`,
`CandidatePin`, `cameraForPoints`, `setCamera`, `FogView`, `openFreeMapStyleUrl`,
`LAYER_CANDIDATES`, `NavigationBanner`, `ThenPill`, `NavigationBottomBar`,
`SpeedLimitSign`). A `ui/map/` subpackage is possible but adds ~15 imports per
file and forces more `internal` promotions for no benefit.

Kotlin's `private` is *file*-private, so every symbol used across a split must be
promoted to `internal`. That is unavoidable in any proposal that splits this file.

| new file | symbols moved | source lines | ≈ lines |
|---|---|---|---|
| `ui/MapScreen.kt` *(rewritten)* | `MapScreen`, effect collection, permission launchers, lifecycle observer, dialog hosting | rewritten from `419-1837` | 240 |
| `ui/MapViewModel.kt` | new: destination/spin/nav/camera-intent state, `choose`, `commitSpinCandidate`, `selectMode`, `spin`, `startNavigation`, `stopNavigation`, vote resolution, `seedRouteNavigation` | logic from `440-585`, `803-870`, `970-1016`, `1236-1245`, `1344-1390`, `1392-1527` | 430 |
| `ui/MapUiState.kt` | `SpinUiState`, `SpinSettingsUiState`, `DestinationUiState`, `NavUiState`, `CameraUiState`, `HazardUiState`, `MapEvent`, `MapEffect` | new | 130 |
| `ui/MapSurface.kt` | `rememberMapSurface`, `MapSurface`, `MapRenderState`, `smoothBearing`, `CAM_*`/`FIT_PADDING_PX` constants | `221-259`, `592-595`, `627-694`, `872-968`, `1085-1096`, `1120-1132`, `1247-1333`, `1547` | 320 |
| `ui/MapChrome.kt` | `MapTopChrome`, `SearchPill`, `ConvoyPill`, `GlassRailButton`, `ModeBar`, `EndTripButton` | `2683-2697`, `2699-2879`, `2881-2897` | 210 |
| `ui/MapSpinCards.kt` | `SpinDock`, `SpinSheet`, `BottomCard` | `416-417`, `2281-2547` | 300 |
| `ui/MapCandidatesCard.kt` | `CandidatesCard`, `CANDIDATE_COLORS`, `GroupSpin.asRouteCandidates`, `List<RouteCandidate>.asSpinCandidates`, `leadingSpinIndex` | `316-367`, `2549-2681` | 200 |
| `ui/MapDialogs.kt` | `SearchDialog`, `BackgroundLocationDisclosure`, `SavePinDialog` | `1839-1958`, `1998-2055` | 240 |
| `ui/MapPills.kt` | `Pill`, `SegmentedPillRow`, `ScrollingPillRow`, `ShortcutChips`, `DIRECTION_NAMES` | `210-211`, `1960-1996`, `2057-2121` | 130 |
| `ui/MapNavApps.kt` | `launchNav`, `navAppUsableDirectly`, `handleGoTap`, `NavMenuItems`, `NavIconButton`, `NavButton`, `navigateRoundTrip`, `navigateGoogleMaps`, `navigateWaze`, `navigateGeo` | `2123-2279`, `3048-3099`, `3150-3193` | 250 |
| `ui/MapSpeedHud.kt` | `SpeedHud`, `SectionAverageChip` | `2956-3046` | 120 |
| `ui/MapTripCards.kt` | `ActiveTripCard`, `StatItem`, `PushToTalkButton` | `2899-2954`, `3101-3148` | 140 |
| `ui/TravelModeIcon.kt` | `TravelMode.icon` | `213-219` | 20 |
| `ui/MapHazardsViewModel.kt` | new: wires the three machines below to `TripTrackingService.lastFix`, exposes `HazardUiState` | `527-539`, `1018-1089`, `1134-1230` | 150 |
| `nav/AmbientSpeedLimit.kt` | new plain class | logic of `1024-1056` | 110 |
| `nav/SpeedCameraWatch.kt` | new plain class | logic of `1062-1083`, `1145-1167` | 110 |
| `nav/SectionAverage.kt` | new plain class, `sectionExitGate`, `SECTION_*` | `283-314`, `1174-1230` | 130 |

**`ui/TravelModeIcon.kt` must keep package `com.jellemax.detour.ui`** —
`RoutesScreen.kt:303`, `HistoryScreen.kt:317` and `RouteEditorScreen.kt:386` use
`mode.icon` unqualified from the same package. Moving it into a subpackage
breaks three files.

**Deleted:** `SpinResult` (`376-381`), `SpinResultHolder` (`383-385`),
`seedRouteNavigation` (`400-414`). `RoutesScreen.kt:202` changes from
`seedRouteNavigation(route)` to `mapViewModel.seedRouteNavigation(route)`.

**Line-count honesty:** 3193 → ≈ 2960 of moved/rewritten code across 17 files,
plus roughly 550-700 lines of per-file import blocks and the event/effect
plumbing that does not exist today. **Total lines in the repo go up by ~15%.**
The win is file size (largest file 430 instead of 3193), not total volume.

---

## The UiState / event model

Narrow states, not one `MapUiState`. Justified in the perf section; here is the
shape, with the real field names from the current code.

```kotlin
// ui/MapUiState.kt

/** Persisted spin controls — exactly what is rememberSaveable today
 *  (MapScreen.kt:449, 450, 462, 463, 526). */
data class SpinSettingsUiState(
    val radiusKm: Float = TravelMode.CAR.defaultKm,
    val minRadiusKm: Float = 0f,
    val poiKind: PoiKind = PoiKind.ROAD,
    val directionDeg: Float? = null,
    val settingsCollapsed: Boolean = true,
)

/** The destination spine — MapScreen.kt:454-457, 464. */
data class DestinationUiState(
    val destination: LatLon? = null,
    val destinationName: String? = null,
    val route: RouteResult? = null,
    val candidates: List<RouteCandidate> = emptyList(),
)

/** MapScreen.kt:458-460. */
data class SpinUiState(
    val spinning: Boolean = false,
    val error: String? = null,
)

/** MapScreen.kt:514-517. */
data class NavUiState(
    val navigating: Boolean = false,
    val progress: NavEngine.Progress? = null,
    val rerouting: Boolean = false,
)

/** MapScreen.kt:519-553. `cameraActive` folds in `navigating`, which is why
 *  this carries it rather than the composable recombining two flows. */
data class CameraUiState(
    val followMe: Boolean = true,
    val camSuspended: Boolean = false,
    val target: LatLon? = null,
    val targetBearingDeg: Float? = null,
    val targetZoom: Double = 14.0,
    val navigating: Boolean = false,
) {
    val active: Boolean get() = (followMe || navigating) && !camSuspended
    val following: Boolean get() = followMe && !camSuspended
}

/** MapScreen.kt:527-539. Owned by MapHazardsViewModel. */
data class HazardUiState(
    val ambientSpeedLimitKmh: Double? = null,
    val speedCameras: List<SpeedCameras.Camera> = emptyList(),
    val speedSections: List<SpeedCameras.Section> = emptyList(),
    val sectionAvgKmh: Double? = null,
    val sectionLimitKmh: Double? = null,
)
```

Events. Named after what the user did, not after which field to set — that is
what lets `selectMode`'s five-field reset (`1515-1527`) stay in one place:

```kotlin
sealed interface MapEvent {
    // spin controls
    data class SelectMode(val mode: TravelMode) : MapEvent          // 1515-1527
    data class RadiusChanged(val km: Float) : MapEvent              // 1763
    data class MinRadiusChanged(val km: Float) : MapEvent           // 1765
    data class PoiKindChanged(val kind: PoiKind) : MapEvent         // 1767
    data class DirectionChanged(val deg: Float?) : MapEvent         // 1769
    data class SettingsCollapsedChanged(val collapsed: Boolean) : MapEvent // 1752, 1781

    // spin lifecycle
    data object SpinPressed : MapEvent                              // 1751, 1780
    data object Reroll : MapEvent                                   // 1718
    data object CancelCandidates : MapEvent                         // 1719-1722

    // committing a destination
    data class CandidatePicked(val index: Int) : MapEvent           // 804-815, 1715-1717
    data class CandidateVoted(val index: Int) : MapEvent            // 965, 1716
    data object ShareSpinWithConvoy : MapEvent                      // 1726-1728
    data object GoWithLead : MapEvent                               // 1732-1738
    data class PinDropped(val at: LatLon) : MapEvent                // 949-956
    data class SavedPlacePicked(val place: SavedPlace) : MapEvent   // 1674-1682
    data class SearchResultPicked(val result: GeocodeResult) : MapEvent // 1824-1833

    // navigation
    data object StartNavigation : MapEvent                          // 982-1016
    data object StopNavigation : MapEvent                           // 970-980

    // camera
    data object ToggleFollow : MapEvent                             // 1586-1589
    data object MapGestureStarted : MapEvent                        // 674-677
    data object MapGestureEnded : MapEvent                          // 689

    // fixes and permissions, pushed in by the composable
    data class FixReceived(val fix: TripTrackingService.Fix) : MapEvent // 555-559, 1236-1245
    data object LocationGranted : MapEvent                          // 760-770
    data object LocationDenied : MapEvent                           // 777-779
}

/** One-shots the VM cannot perform: they need a Context or the MapLibreMap. */
sealed interface MapEffect {
    data class FitCamera(val points: List<LatLon>) : MapEffect      // 814, 839, 1468, 1492-1494
    data class FlyTo(val at: LatLon, val zoom: Double, val durationMs: Int) : MapEffect // 1680, 1831
    data class StartTracking(val destLat: Double?, val destLon: Double?) : MapEffect // 989, 1756, 1785
    data object StopTracking : MapEffect                            // 1636
    data object StartMonitoring : MapEffect                         // 762
    data object RequestCurrentLocation : MapEffect                  // 714-734
    data object AskBackgroundLocation : MapEffect                   // 763-769
    data object Haptic : MapEffect                                  // 1467, 1491
    data class SendNavRelay(val progress: NavEngine.Progress, val currentSpeedKmh: Double) : MapEffect // 1356-1357
    data object ClearNavRelay : MapEffect                           // 978-979
}
```

The composable's signature becomes:

```kotlin
@Composable
fun MapScreen(onOpenHub: () -> Unit) {
    val vm: MapViewModel = viewModel()
    val hazards: MapHazardsViewModel = viewModel()
    val dest by vm.destination.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    // …
    MapScreenContent(dest, nav, /* … */, onEvent = vm::onEvent, onOpenHub = onOpenHub)
}
```

`MapScreenContent` is `@Preview`-able without a ViewModel. That is a real benefit
this project cannot currently get for any part of the map.

---

## How the imperative MapLibre surface is handled

It does not go in a ViewModel. It goes in a `remember`ed plain class owned by the
composition, driven by two things: a **declarative snapshot** it diffs, and a
**command channel** for one-shots.

```kotlin
// ui/MapSurface.kt
@Stable
class MapSurfaceState(context: Context) {
    val mapView = MapView(context)                 // MapScreen.kt:592
    val fogView = FogView(context)                 // 593
    var map: MapLibreMap? by mutableStateOf(null)  // 594
    var overlays: MapOverlays? by mutableStateOf(null) // 595

    fun fit(points: List<LatLon>, pad: Int, bottomPad: Int) =
        map?.let { cameraForPoints(it, points, pad, bottomPad) }   // 814, 839, 1468
    fun flyTo(at: LatLon, zoom: Double, ms: Int) =
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(at.lat, at.lon), zoom), ms)
}

@Composable
fun rememberMapSurface(): MapSurfaceState { /* 627-694: lifecycle, style, listeners */ }
```

and a plain data snapshot of everything drawable:

```kotlin
/** Exactly the arguments MapOverlays.render already takes (MapScreen.kt:886-901). */
data class MapRenderState(
    val myLocation: LatLon?,
    val destination: LatLon?,
    val routePolyline: List<LatLon>?,
    val reachMeters: Double?,
    val directionDeg: Int?,
    val candidates: List<CandidatePin>,
    val positionBearingDeg: Double?,
)
```

Division of labour:

| piece | where it lives | why |
|---|---|---|
| `MapView`/`FogView`/`MapLibreMap`/`MapOverlays` handles | `MapSurfaceState`, `remember`ed | View objects must die with the composition; a ViewModel outliving the Activity holding a `MapView` is a guaranteed context leak |
| `MapView` lifecycle (`629-648`) | `DisposableEffect` in `rememberMapSurface` | tied to composition, not to VM lifetime |
| Style reload on theme flip (`650-663`) | `LaunchedEffect(darkTheme, map)` in `MapSurface` | reads `Settings.theme` directly |
| Overlay pushes (`872-932`, `1085-1096`, `1120-1132`) | `LaunchedEffect(renderState)` in `MapSurface` | pure `MapRenderState` → `overlays.render(…)`, keeping the four-effect split (`904-915`, `917-932`) that exists to avoid re-serialising sources for a colour change |
| Touch listener (`669-694`) | `DisposableEffect` in `MapSurface`; emits `MapEvent.MapGestureStarted/Ended` | needs `MotionEvent` and `ViewConfiguration`; the *decision* it feeds (park the camera) is VM state |
| Click/long-click listeners (`940-968`) | `LaunchedEffect(map)` in `MapSurface`; emit `PinDropped` / `CandidatePicked` / `CandidateVoted` | **`rememberUpdatedState` at `937-939` disappears**: the listener reads `vm.destination.value.candidates` and `vm.convoy.value.spinOffer` off a `StateFlow`, which is always current by construction. Same for `1138-1140` and `1173`. Seven `rememberUpdatedState` declarations removed |
| Camera easing loop (`1265-1333`) | stays in `MapSurface`'s composable | **`withFrameNanos` requires a `MonotonicFrameClock`, which `viewModelScope` does not have.** This is a hard technical constraint, not a preference |
| Speed easing loop (`1247-1263`) | stays in the composable, writing a local `mutableDoubleStateOf` | same constraint, plus the perf reason below |
| `setDrivenFraction` (`977`, `1355`) | `LaunchedEffect(navProgress?.drivenFraction)` in `MapSurface` | derives from `NavUiState`, no new channel needed |
| `cameraForPoints` / `animateCamera` one-shots | `MapEffect.FitCamera` / `FlyTo` | these are events in time, not state; a `StateFlow` would replay them on config change |

The `fitBottomPaddingPx` and `attributionBottomMarginPx` computations
(`429`, `434`) read `context.resources.displayMetrics` and `LocalDensity` — they
stay in the composable and are passed into `MapSurfaceState`, never into a VM.

---

## `SpinResultHolder` and `rememberSaveable` → `SavedStateHandle`

### The five `rememberSaveable` values map cleanly

| current | line | replacement |
|---|---|---|
| `radiusKm: Float` | `449` | `handle.getStateFlow("radiusKm", Settings.tripMode.value.defaultKm)` |
| `minRadiusKm: Float` | `450` | `handle.getStateFlow("minRadiusKm", 0f)` |
| `settingsCollapsed: Boolean` | `526` | `handle.getStateFlow("settingsCollapsed", true)` |
| `directionDeg: Float?` | `463` | `handle.getStateFlow<Float?>("directionDeg", null)` |
| `poiKind: PoiKind` | `462` | store the **name**: `handle.getStateFlow("poiKind", PoiKind.ROAD.name).map { PoiKind.valueOf(it) }`. An enum is `Serializable` so the direct form also works, but a `String` key is the form that survives an enum-constant rename without an `IllegalArgumentException` at restore |

All five also become far more durable than today: an Activity-scoped VM keeps
them across the Hub round-trip that currently resets them (concern 12), and
`SavedStateHandle` keeps them across process death, which `rememberSaveable` in
this app effectively never does (the subtree is disposed on navigation, so
nothing is registered to save).

### `SpinResultHolder` mostly disappears — but not into `SavedStateHandle`

The holder (`369-385`) exists for one reason, stated in its own doc comment:
surviving activity recreation. An Activity-scoped `MapViewModel` covers that,
and covers navigation-away as well, which the holder also happens to cover and
`rememberSaveable` does not. So:

- **Delete `SpinResultHolder`, `SpinResult`, and the mirror effect at `467-469`.**
- `seedRouteNavigation(route)` (`400-414`) becomes
  `MapViewModel.seedRouteNavigation(route: SavedRoute)`, called from
  `RoutesScreen.kt:202` via `viewModel<MapViewModel>()`.

**What cannot go into `SavedStateHandle`, and why:** `SavedStateHandle` is
`Bundle`-backed, so values must be `Parcelable`, `Serializable`, or a primitive.
`LatLon` (`shared/.../RoadRoulette.kt:15`), `RouteResult`
(`shared/.../RoutingServer.kt:13`) and `RouteCandidate`
(`shared/.../SpinPicker.kt:11`) are plain Kotlin data classes in a **KMP
commonMain** source set — not `Parcelable` (impossible in commonMain without
expect/actual), not `@Serializable`, not `java.io.Serializable`. Making them
`Parcelable` would mean either an `expect`/`actual` split of shared model types
or an Android-side wrapper.

And even if they were: a round-trip `RouteResult.polyline` is thousands of
`LatLon`s. `onSaveInstanceState` goes through a Binder transaction with a ~1 MB
process-wide limit; a `TransactionTooLargeException` there is a crash on
backgrounding.

**The honest resolution:** persist only three primitives across process death —
`destinationLat`, `destinationLon`, `destinationName` — and let the polyline go.
This is not a compromise invented for this proposal: `commitSpinCandidate`
already does exactly that at `MapScreen.kt:833` (`route = null`, with the comment
"startNavigation() fetches a real route once tapped"). Candidates and the drawn
route are *not* restored after process death, same as today.

---

## Migration plan

Eleven steps. Each is a separate commit, each leaves the app compiling, and the
first three add **no dependency and change no behaviour**.

**Step 1 — promote shared symbols to `internal`.** `private` in Kotlin is
file-scoped, so every symbol crossing a future file boundary needs promoting.
One mechanical commit, no moves, no behaviour change.

**Step 2 — move the leaf composables out (5 commits).**
`ui/MapPills.kt` (`210-211`, `1960-1996`, `2057-2121`) ·
`ui/MapDialogs.kt` (`1839-1958`, `1998-2055`) ·
`ui/MapNavApps.kt` (`2123-2279`, `3048-3099`, `3150-3193`) ·
`ui/MapSpeedHud.kt` + `ui/MapTripCards.kt` (`2899-2954`, `2956-3046`, `3101-3148`) ·
`ui/MapChrome.kt` + `ui/MapSpinCards.kt` + `ui/MapCandidatesCard.kt` +
`ui/TravelModeIcon.kt` (`213-219`, `316-367`, `416-417`, `2281-2697`, `2699-2897`).
Pure moves. **MapScreen.kt drops from 3193 to ≈ 1840 lines with zero risk.**

**Step 3 — extract the three hazard state machines as plain classes.**
`nav/AmbientSpeedLimit.kt`, `nav/SpeedCameraWatch.kt`, `nav/SectionAverage.kt`
(from `283-314`, `1018-1083`, `1134-1167`, `1169-1230`). The existing effects
shrink to `TripTrackingService.lastFix.collect { machine.onFix(it) }`. **Add the
JUnit4 tests here** — this is the step that buys the testability, and it needs no
ViewModel and no dependency.

**Step 4 — point the car screens at the same classes.** `car/SpinScreen.kt:265-295`
and `car/NavScreen.kt:375-405` are near-duplicates of what step 3 just extracted;
delete ~60 lines of duplication. Still no dependency.

*(If the project stops here, it has an ≈1840-line MapScreen, 12 focused files,
new unit tests, and no new dependency. This is the point at which to re-read the
verdict below before continuing.)*

**Step 5 — add the dependency and an empty ViewModel.** The three gradle lines,
plus `class MapViewModel(private val handle: SavedStateHandle) : ViewModel()`
and `val vm: MapViewModel = viewModel()` in `MapScreen`, read by nothing.
Compiles, changes nothing, isolates the "does the build still work" risk in one
tiny commit.

**Step 6 — move the five saveable scalars.** `449`, `450`, `462`, `463`, `526` →
`SpinSettingsUiState` on `SavedStateHandle`. **First behaviour change:** these
now survive the Hub round-trip. Ship this one alone and look at it.

**Step 7 — move the destination spine.** `destination`/`destinationName`/`route`/
`candidates`, `choose` (`803-815`), `commitSpinCandidate` (`825-840`),
`selectMode` (`1515-1527`), the vote-resolution rule (`842-870`). **Delete
`SpinResultHolder`/`SpinResult`/`seedRouteNavigation` and the mirror at
`467-469`; update `RoutesScreen.kt:202`.** This is the widest-blast-radius commit
in the plan and the one to review hardest.

**Step 8 — move `spin()`** (`1392-1513`) to `viewModelScope`, introducing the
`MapEffect` channel with `FitCamera` and `Haptic`. **Behaviour change to decide
explicitly:** today `spin()` runs on `rememberCoroutineScope()` and is cancelled
when the map leaves the composition; on `viewModelScope` it keeps three
GraphHopper/Overpass requests in flight while the user is on another screen and
lands its result on return. Defensible either way — but it must be a decision,
not an accident.

**Step 9 — move navigation.** `navigating`/`navProgress`/`rerouting`/
`lastRerouteMs`, `startNavigation` (`982-1016`), `stopNavigation` (`970-980`),
the progress/arrival/reroute effect (`1344-1390`), with `MapEffect.StartTracking`
/ `SendNavRelay` / `ClearNavRelay`.

**Step 10 — move camera intent.** `followMe`/`camSuspended`/`lastGestureMs`/
`camTarget*` (`519-553`, `696-712`, `1236-1245`) into `CameraUiState`. The frame
loops (`1247-1333`) and the touch listener (`669-694`) stay in the composable.

**Step 11 — extract `ui/MapSurface.kt`** and rewrite `MapScreen.kt` as the
renderer. `MapScreenContent(state…, onEvent)` becomes previewable.

---

## Pros

1. **It fixes concern 12, which nothing else in this refactor space can.**
   Activity scope means the map's state survives the Hub round-trip. Concretely:
   the trajectcontrole running average (`1174-1230`) no longer resets, the
   Overpass camera prefetch (`1062-1083`) no longer re-fires, the full
   `SyncClient.sync()` (`568-578`) no longer re-runs on every return to the map,
   the permission re-check (`782-801`) runs once per Activity, and
   `radiusKm`/`poiKind`/`directionDeg` stop silently reverting to defaults. These
   are user-visible bugs today.

2. **`SpinResultHolder` is deleted, not moved.** A process-global mutable object
   that four files touch (`369-414`, `RoutesScreen.kt:202`) becomes a method on
   a scoped, clearable owner.

3. **Seven `rememberUpdatedState` declarations disappear** (`937-939`,
   `1138-1140`, `1173`). They exist purely because a Compose `State` read from a
   non-composable callback goes stale; a `StateFlow.value` never does. This is
   the one place where the ViewModel is *simpler*, not just differently
   organised.

4. **Real `@Preview` for the map's UI.** `MapScreenContent(state, onEvent = {})`
   renders with no `MapLibreMap`, no permissions and no GPS. No screen in this
   app is previewable today.

5. **The largest file goes from 3193 to ~430 lines**, and the giant composable
   from 1419 to ~240.

6. **`SavedStateHandle` gives real process-death survival** for the spin controls,
   which `rememberSaveable` in this app does not actually deliver (concern 12).

7. **Conventional.** A new contributor who has written an Android app knows where
   to look. `ViewModel` + `StateFlow` + `collectAsStateWithLifecycle` is the
   documented default, and `lifecycle-runtime-compose` is already a dependency.

8. **It makes the coordination logic reachable from a test** — `selectMode`'s
   five-field reset, `choose`'s camera-suspension, `startNavigation`'s four error
   branches. Not the sensing logic (step 3 does that without a VM), but the
   coordination, which is where the field-forgetting bugs actually live.

---

## Cons and risks

### The thin pass-through risk — the main attack, taken seriously

Nineteen of the values `MapScreen` reads are already `StateFlow`s on `object`
singletons, already collected with `collectAsStateWithLifecycle()`
(`438`, `448`, `470-471`, `479`, `483-488`, `491-499`, `544-546`, `588`, `590`).
A ViewModel has three options for each, all bad in different ways:

- **Re-expose verbatim** (`val mode = Settings.tripMode`) — a second layer that
  adds a name and nothing else. If most of the VM looks like this, the VM *is*
  the anti-pattern its critics describe.
- **`combine()` into a UiState** — costs a coroutine, and collapses 19
  independent recomposition scopes into one that invalidates when any of them
  changes. Strictly worse for perf than today.
- **Leave them in the composable** — which is what this proposal recommends, and
  which means the ViewModel is *not* the single source of truth for the screen.
  The composable reads some state from the VM and some from singletons. That is a
  weaker, less teachable architecture than the pattern promises.

I am recommending the third. It is the least-bad option and it is honest, but it
means **this proposal does not deliver "the ViewModel owns the screen's state"**.
It delivers "the ViewModel owns the state that was previously composition-local".
That is a smaller claim.

Counting it precisely: of the ~50 state reads in `MapScreen`, roughly 19 stay on
singletons, ~24 move to the VM, and ~7 (frame-loop locals, dialog-local state)
stay in the composition.

### The ViewModel does not compose with the rest of the app

`DetourCarSession` (`car/DetourCarSession.kt:11`) runs in the **same process** —
there is no `android:process` anywhere in `app/src/main/AndroidManifest.xml` — and
today it reaches app state through the singletons: `TripTrackingService.lastFix`
(`car/SpinScreen.kt:131`, `car/NavScreen.kt:205`), `Settings.defaultZoom`
(`car/SpinScreen.kt:137`), `Settings.init()` (`car/DetourCarSession.kt:27`).
`androidx.car.app.Session` is a `LifecycleOwner`, not a `ViewModelStoreOwner`,
so `viewModel()` is not available there; and even hand-rolling a `ViewModelStore`
in the Session would produce a *second, unrelated* instance. The foreground
service (`TripTrackingService`), the wear relay (`wear/NavRelay.kt`) and
`BleNavServer` have the same problem.

**So any state that moves from a singleton into the ViewModel becomes
unreachable from the car, the service and the relays.** This proposal is careful
not to move any (see the previous section) — but that carefulness is precisely
why the pass-through risk is real. The two problems are the same problem seen
from two sides.

### Ceremony cost

- 10 `MapEffect` types replace 10 one-line `Context` calls. `TripTrackingService.stop(context)`
  at `1636` becomes an event → a `when` branch in the VM → a channel send → a
  `when` branch in the composable → the same call. Four hops for one line.
- ~24 `MapEvent` types replace ~24 lambdas that today read as
  `onRadiusChange = { radiusKm = it }` (`1763`).
- Total repo lines go **up** ~15% (import blocks × 17 files, plus the event and
  effect declarations).

### Behaviour changes that must be decided, not discovered

- Step 8: `spin()` on `viewModelScope` outlives navigation-away.
- Step 6/7: state surviving the Hub round-trip is mostly a fix, but it changes
  what the user sees on return, including a stale `error` string (`460`).
- `viewModelScope` is `Dispatchers.Main.immediate`; today `rememberCoroutineScope()`
  is also main-dispatched, so no change there — but the `withContext(Dispatchers.IO)`
  wrappers at `570`, `583`, `1005`, `1037`, `1075`, `1113`, `1379`, `1407` must
  survive the move intact. Dropping one puts an Overpass call on the main thread.

### Risk of a half-migration

Steps 1-4 are cheap and safe; steps 5-11 are where the value and the risk both
are. A migration that stops at step 6 leaves the app with *both* a ViewModel and
composition-local state for the same screen — strictly worse than either
endpoint. This plan must be finished or reverted, not parked.

### It is the first of eighteen

`SettingsScreen.kt` (1199), `FriendsScreen.kt` (939), `CirclesScreen.kt` (716),
`TripDetailScreen.kt` (560) all use the same `remember` + singleton pattern.
Introducing ViewModels for one screen creates an inconsistency that the next
contributor has to resolve.

---

## Effect on…

### Unit testability (plain JUnit4, no Robolectric)

**Newly testable, and genuinely valuable:**

| logic | source | what a test pins down |
|---|---|---|
| `SectionAverage` | `290-314`, `1174-1230` | entering only when heading into a section; the 150 m floor stopping the entry gate counting as the exit (`1217`); overshoot and 30-min timeout (`1219-1220`) |
| `AmbientSpeedLimit` | `1024-1056` | clear only after 3 consecutive misses (`1050`); 10 s failure throttle (`1034`); refetch at prefetch-radius − 500 m (`1033`) |
| `SpeedCameraWatch` | `1145-1167` | one chime per camera and re-arm once behind (`1156-1165`); silence when the limit is unknown (`1160-1161`) |
| `leadingSpinIndex` | `361-367` | lowest-index tie-break, including the all-zeroes case |
| `sectionExitGate` | `300-314` | the heading test that stopped a measurement starting on the way *out* |
| `smoothBearing` | `224-230` | the 0/360 wrap |
| `asRouteCandidates` / `asSpinCandidates` | `329-355` | the wire round-trip |
| camera-resume rule | `700-712` | resumes above 3 m/s after 8 s quiet, never while a spin is on screen |
| group-spin resolution | `858-870` | one-candidate offer auto-commits; sharer waits for `convoyPeers + self` |
| `selectMode` | `1515-1527` | resets radius, min-radius, destination, name, route, candidates, and clears a convoy offer — six fields, currently only verifiable by hand |

**The uncomfortable part: the first eight of those need no ViewModel.** They
become testable at **step 3**, by extraction into plain classes, with zero new
dependencies. The ViewModel adds testability for the last two only — the
*coordination* logic. That is worth something, but anyone claiming "a ViewModel
makes this testable" is overselling: extraction makes it testable; the ViewModel
gives it a lifetime.

**Costs and caveats a test will actually hit:**
- `viewModelScope` uses `Dispatchers.Main.immediate` → any test of a method that
  `launch`es needs `kotlinx-coroutines-test:1.8.1` and `Dispatchers.setMain`.
  Not optional.
- `SavedStateHandle` is on the JVM unit-test classpath but leans on
  `android.os.Bundle` for some paths; the `get`/`set`/`getStateFlow` paths do not
  touch it, but this needs verifying on the first test and may need
  `testOptions.unitTests.isReturnDefaultValues = true`. **Design the logic
  classes to take plain values, not `SavedStateHandle`**, so the risk never
  arises.
- `spin()` (`1392-1513`) stays **untestable** either way: it calls
  `RoutingServer.roundTrip`, `pickCandidate`, `ExploredArea.load`,
  `RoundTripPlanner.plan` and `Settings.avoidSmallRoads.value` as global
  singletons with no seam. Making it testable needs interfaces + injection —
  which this proposal does not add.
- `TripTrackingService.Fix` (`tracking/TripTrackingService.kt:93`) is a plain
  six-field data class with no Android types, so tests can construct fixes
  freely. Good news, and it is what makes the state-machine tests practical.

**Still needs instrumentation (or nothing):** everything MapLibre, `FogView`,
both frame loops, the permission ladder, the foreground service, `AndroidView`,
and all Compose UI (there is no `compose-ui-test` dependency and this proposal
does not add one).

### Recomposition and performance

**Do not use one big `MapUiState`.** Two concrete reasons from this file:

1. `displaySpeedKmh` (`550`) is written **every frame** by the loop at
   `1251-1263`. In a monolithic `StateFlow<MapUiState>` that allocates a new
   state object 60×/s and invalidates every collector. It must stay a local
   `mutableDoubleStateOf` read only by `SpeedHud`.
2. `camTarget`/`camTargetBearing`/`camTargetZoom` (`547-549`) are written ~1×/s
   (`1236-1245`) and read **only** inside the frame loop at `1265-1333` — i.e.
   from a coroutine, not a recomposition scope, so today they cost nothing. Put
   them in a screen-wide `UiState` and every GPS fix recomposes the whole tree.

**What actually improves:** today `displaySpeedKmh` is read at `1641`/`1643`,
inside the `Scaffold` content lambda — a real recomposition scope. `Box`,
`Column` and `Row` content lambdas are `inline`, so they create no scope of their
own. **The entire bottom half of the map tree therefore recomposes at display
refresh rate whenever the vehicle is moving.** Hoisting `SpeedHud` into its own
composable with a narrow parameter fixes that — but note this is fixed by the
*composable split* (step 2), not by the ViewModel.

**Net:** narrow states are perf-neutral-to-slightly-better; one big state is
clearly worse; the biggest available win comes from the split, not the VM.

Small real cost: each `StateFlow` + `collectAsStateWithLifecycle()` is one more
coroutine and one more `Lifecycle` observer than a `mutableStateOf`. With 5-6
narrow states that is noise.

### Android Auto code reuse

**The ViewModel: no reuse, slightly negative.** `androidx.car.app.Session`
(`car/DetourCarSession.kt:11`) is not a `ViewModelStoreOwner`; the car screens
cannot obtain the Activity's `MapViewModel`, and creating their own store would
give them a second, desynchronised instance. Anything that migrates out of a
singleton into the VM loses car reach.

**The extraction (step 3-4): real, measurable reuse.** Two near-duplications
exist today and both are cured by plain classes:

- ambient speed limit: `MapScreen.kt:1024-1056` ≈ `car/SpinScreen.kt:265-295`
  (same prefetch radius margin, same 3-miss clear, same 2 m/s heading floor —
  `car/SpinScreen.kt:52-61` re-declares the constants);
- camera warning: `MapScreen.kt:1145-1167` ≈ `car/NavScreen.kt:375-405`
  (same 45° wedge, same nearest-ahead pick, same one-chime bookkeeping).

`car/NavScreen.kt:72-79` documents the choice not to share nav state with
MapScreen. That reasoning still holds for *session* state; it never applied to
these pure sensing loops.

So the reuse benefit belongs entirely to the extraction half of this proposal.
Worth saying plainly: **if Android Auto reuse is the goal, do steps 1-4 and stop.**

### Reviewability

Steps 1-4 are excellent: pure moves, `git log --follow` works, a reviewer can
diff by eye. Steps 6-11 are the hardest kind of review — state *ownership*
changes, so the diff is not a move and `git blame` on the moved logic points at
the migration commit. Step 7 in particular touches four files and deletes a
process-global; it deserves a review of its own.

Ongoing, after the fact: much better. "Where is the destination set?" goes from
eleven scattered `destination = …` assignments to one class with named methods.

---

## What this proposal explicitly does NOT solve

1. **The composable is still large.** The ViewModel absorbs state and effects
   (~700 of the 1419 lines of `419-1837`); the Scaffold tree (`1529-1796`) and
   the 25 private composables (`1839-3193`) are UI and stay UI. **The ViewModel
   is at most half the answer** — you still need the composable split, and the
   composable split is worth more per unit of risk.
2. **It does not remove the singleton architecture.** `Settings`, `SavedPlaces`,
   `TraceStore`, `TripTrackingService`, `ConvoyLiveClient`, `FriendFog`,
   `CircleFixes`, `Account`, `RoutingServer` all stay exactly as they are, and
   the composable keeps reading most of them directly.
3. **No DI.** The VM constructs/reaches its own collaborators, so `spin()`,
   `startNavigation()` and the reroute path remain untestable against fakes.
4. **Process death still loses the spin result's geometry** — the polyline
   cannot go in a `Bundle` (see the `SavedStateHandle` section).
5. **MapLibre stays imperative.** `MapSurface` is a tidier box around the same
   `getMapAsync`/`setStyle`/`addOnMapClickListener` code.
6. **`FogView`'s screen-space redraw coupling** to the map's camera callbacks
   (`945-946`) is untouched.
7. **The frame loops stay in the composition**, so the camera easing logic
   (`1265-1333`) remains as untestable as it is today.
8. **The car app gains nothing** from the ViewModel half.
9. **No behaviour is intentionally changed** beyond the lifetime changes listed
   in the migration plan — this is not an opportunity to fix the ten-writers,
   one-reader `error` field (`460`, read only at `1771`), though it makes that
   fix easy afterwards.

---

## Honest verdict

**Do steps 1-4 unconditionally.** They cost no dependency, change no behaviour,
take `MapScreen.kt` from 3193 to ~1840 lines, produce the unit tests, and
deduplicate the car app. Nothing in this document argues against them and no
competing proposal should either.

**Steps 5-11 — the ViewModel proper — are the right choice when:**

- The Hub-round-trip state loss (concern 12) is accepted as a bug worth fixing
  properly rather than papering over with a second `SpinResultHolder`. It is the
  one thing only this pattern fixes.
- The project intends to adopt navigation-compose, or to add ViewModels to the
  other large screens. Doing this for one screen and no others is the worst
  outcome.
- More than one person will work on this file. The convention's value is mostly
  social: it is what the next Android developer expects.
- `@Preview` for the map's UI is worth something to the workflow.

**They are the wrong choice when:**

- The goal is "3193 lines is too many" — steps 1-4 already deliver most of that,
  for a fraction of the risk, and steps 5-11 add ~15% total lines and ten
  `MapEffect` types on top.
- Android Auto parity is the priority — the ViewModel is unreachable from
  `DetourCarSession` and actively pulls state away from the one mechanism
  (singletons, same process) that reaches car, service and relays alike.
- Nobody is prepared to finish it. A stalled migration leaves the screen with two
  state-ownership models at once.
- The expectation is "a ViewModel will make this testable". It will not; the
  extraction will. Anyone selling this proposal on testability alone is selling
  step 3.

**Summary judgement:** this pattern is correct in the sense that Android's
guidance is correct, and it is the only proposal here that fixes a real
user-visible defect (concern 12). But in *this* codebase, whose state already
lives in process-scoped `StateFlow` singletons that a foreground service, a car
session and two relays all read, the ViewModel is not the missing source of
truth — it is a **second, narrower-scoped** source of truth beside the existing
one. It earns its place through lifetime, `SavedStateHandle` and previewability,
not through owning the screen. Adopt it with that framing, or adopt only steps
1-4 and keep the singletons as the honest architecture the project already chose.
