# MapScreen.kt — factual inventory

Target: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`, 3193 lines, package `com.jellemax.detour.ui`.
Snapshot: branch `main`, HEAD `07fe490`.

This document is descriptive only. It contains no refactor proposals — it is the
shared ground truth other agents are judged against. Every claim cites a line.

Conventions:
- `MapScreen.kt:NNN` = line NNN of the target file.
- "MapScreen body" = the `MapScreen` composable, `MapScreen.kt:419`–`MapScreen.kt:1837`.
- Imports occupy `MapScreen.kt:3`–`MapScreen.kt:208`; the first declaration is at 210.

---

## 1. Top-level declaration table

Line ranges are inclusive and include the KDoc/comment block that immediately
precedes the declaration where one exists (noted as `doc N–M`).

### 1.1 Constants and values

| Lines | Symbol | Visibility | Purpose |
|---|---|---|---|
| 210–211 | `DIRECTION_NAMES: List<String>` | `private val` | The 8 compass names for the direction pill row and the dock's subtitle. |
| 236 | `CAM_POS_TAU = 0.35` | `private const` | Camera position ease time constant (seconds) for the per-frame follow loop. |
| 237 | `CAM_BEARING_TAU = 0.5` | `private const` | Camera bearing ease time constant. |
| 238 | `CAM_ZOOM_TAU = 1.2` | `private const` | Camera zoom ease time constant. |
| 244 | `SPEED_TAU = 0.30` | `private const` | Speedometer readout ease time constant. |
| 247 | `SPEED_EPS_KMH = 0.15` | `private const` | Snap threshold below which the speed ease stops recomposing. |
| 253 | `CAM_POS_EPS_DEG = 2e-6` | `private const` | Min lat/lon delta worth pushing to `setCamera`. |
| 254 | `CAM_ZOOM_EPS = 2e-3` | `private const` | Min zoom delta worth pushing to `setCamera`. |
| 255 | `CAM_BEARING_EPS_DEG = 0.1f` | `private const` | Min bearing delta worth pushing to `setCamera`. |
| 259 | `FIT_PADDING_PX = 140` | `private const` | Padding around a fitted route/candidate spread. |
| 267 | `CURVY_CANDIDATES = 3` | `private const` | Round-trip rolls to race before keeping the curviest. |
| 273 | `CAM_RESUME_SPEED_MPS = 3.0` | `private const` | Speed above which a parked camera un-parks. |
| 274 | `CAM_RESUME_QUIET_MS = 8_000L` | `private const` | Quiet period after last gesture before un-parking. |
| 279 | `CIRCLE_FIX_POLL_MS = 120_000L` | `private const` | Circle-member fix poll interval. |
| 283 | `SECTION_GATE_METERS = 60.0` | `private const` | Radius counting as "at a trajectcontrole end node". |
| 288 | `SECTION_WEDGE_DEG = 75.0` | `private const` | Heading wedge for "driving into this section". |
| 316–320 | `CANDIDATE_COLORS: List<Int>` | `private val` | 3 ARGB colours shared by candidate map pins and card rows. |

### 1.2 Functions and types

| Lines | Symbol | Visibility | Purpose |
|---|---|---|---|
| 213–219 | `val TravelMode.icon: ImageVector` | **public** ext property | Maps a `TravelMode` to its Material icon. Used by 3 files outside MapScreen.kt (§6). |
| 221–230 | `smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float` (doc 221–223) | `private fun` | Exponential bearing smoothing across the 0/360 wrap. |
| 290–314 | `sectionExitGate(section, pos, headingDeg): List<LatLon>?` (doc 290–299) | `private fun` | Returns the far end of a speed-camera section if this fix is entering it. |
| 322–343 | `GroupSpin.asRouteCandidates(): List<RouteCandidate>` (doc 322–328) | `private fun` ext | Reshapes a convoy spin offer's wire candidates into the local `RouteCandidate` shape; `route` is a placeholder with no polyline. |
| 345–355 | `List<RouteCandidate>.asSpinCandidates(): List<SpinCandidate>` (doc 345–346) | `private fun` ext | The inverse wire shape, for `ConvoyLiveClient.sendSpinOffer`. |
| 357–367 | `leadingSpinIndex(votes: Map<String,Int>, candidateCount: Int): Int` (doc 357–360) | `private fun` | Deterministic vote tally; ties go to lowest index. |
| 369–381 | `data class SpinResult` (doc 369–375) | **internal** | Process-scoped spin outcome: `destination`, `destinationName`, `route`, `candidates`. |
| 383–385 | `object SpinResultHolder` | **internal** | `MutableStateFlow<SpinResult>` living outside composition, so a spin survives activity recreation. |
| 387–414 | `seedRouteNavigation(route: SavedRoute)` (doc 387–399) | **internal fun** | Writes a saved route's last stop into `SpinResultHolder` so MapScreen picks it up on next composition. Also calls `Settings.setTripMode`. |
| 416–417 | `enum class BottomCard { NAV, CANDIDATES, COLLAPSED, EXPANDED }` | `private` | Which of the four occupants holds the bottom-card slot. |
| 419–1837 | `@Composable fun MapScreen(onOpenHub: () -> Unit)` | **public** | The screen. 1419 lines. Broken down in §2–§5. |
| 1839–1958 | `@Composable SearchDialog(near, onPick, onDismiss)` (doc 1839–1841) | `private` | Full-screen geocode search with debounced Photon lookups and recents merge. Self-contained: owns `query`/`results`/`searching`/`error`/`recents`/`focusRequester` (1849–1855) and its own `LaunchedEffect`s at 1863, 1869. |
| 1960–1996 | `@Composable ShortcutChips(places, canSavePin, onPick, onSavePin)` | `private` | Horizontal saved-place chip row plus a "Save pin" chip. Stateless. |
| 1998–2028 | `@Composable BackgroundLocationDisclosure(onAllow, onDismiss)` (doc 1998–2001) | `private` | Play-policy prominent disclosure dialog. Stateless. |
| 2030–2055 | `@Composable SavePinDialog(suggestedName, onSave, onDismiss)` | `private` | Name-and-save dialog; owns `name` (2037). |
| 2057–2085 | `@Composable Pill(label, selected, onClick, modifier)` | `private` | Single rounded pill. Stateless. |
| 2087–2100 | `@Composable SegmentedPillRow(options, selectedIndex, onSelect, modifier)` | `private` | Equal-width pill row (destination-type control). Stateless. |
| 2102–2121 | `@Composable ScrollingPillRow(options, selectedIndex, onSelect, modifier)` | `private` | Horizontally scrolling pill row (direction picker). Stateless. |
| 2123–2149 | `launchNav(context, app, destination, route, origin, mode, onNavigateInApp, onNavigate)` | `private fun` | Single dispatch point for the nav-app choice; calls `Settings.setPreferredNavApp` (2148). |
| 2151–2164 | `navAppUsableDirectly(app, inAppAvailable, route, origin): Boolean` | `private fun` | Whether the remembered app can launch without opening the menu. Pure. |
| 2166–2186 | `handleGoTap(...)` | `private fun` | Tap policy: direct-launch or fall back to opening the menu. Pure dispatch. |
| 2188–2232 | `@Composable NavMenuItems(...)` | `private` | Shared dropdown items behind `NavButton` and `NavIconButton`. |
| 2234–2279 | `@Composable NavIconButton(...)` | `private` | 40dp circular "Go"; owns `menuOpen` (2248) and collects `Settings.preferredNavApp` (2250). |
| 2281–2366 | `@Composable SpinDock(mode, radiusKm, directionDeg, spinning, destination, route, origin, inAppAvailable, onSpin, onExpand, onNavigateInApp, onNavigate, modifier)` | `private` | The collapsed glass bar. 13 parameters. Stateless. |
| 2368–2547 | `@Composable SpinSheet(... 19 parameters ...)` | `private` | The expanded spin sheet: radius, min distance, POI kind, direction, spin, Go, Track. Stateless. |
| 2549–2681 | `@Composable CandidatesCard(candidates, onPick, onReroll, onCancel, convoyVotes, onShare, onGoWithLead, modifier)` | `private` | Spin results / convoy vote card. Stateless. |
| 2683–2697 | `@Composable ModeBar(selected, onSelect)` | `private` | Bottom NavigationBar of `TravelMode`s. |
| 2699–2775 | `@Composable MapTopChrome(followMe, fogEnabled, username, convoyName, layersOpen, onLayersOpenChange, onToggleFollow, onSearch, onToggleFog, onOpenHub, modifier)` | `private` | Search pill + convoy pill + right rail (follow, layers) + inline layers panel. |
| 2777–2825 | `@Composable SearchPill(username, onSearch, onAvatarClick, modifier)` | `private` | Glass search pill with avatar. |
| 2827–2851 | `@Composable ConvoyPill(name, modifier)` | `private` | Small pill naming the live convoy. |
| 2853–2879 | `@Composable GlassRailButton(icon, contentDescription, onClick, tinted, modifier)` | `private` | One 40dp glass rail button. |
| 2881–2897 | `@Composable EndTripButton(onClick, modifier)` | `private` | Red "End trip" button. |
| 2899–2954 | `@Composable PushToTalkButton(talking, modifier)` (doc 2899–2902) | `private` | Hold-to-talk. Owns `pressed` (2907), a `rememberCoroutineScope` (2906), and calls `PushToTalk.startTalking`/`stopTalking` (2930, 2935). Checks `RECORD_AUDIO` itself (2919). |
| 2956–3012 | `@Composable SpeedHud(speedKmh, limitKmh, averageKmh, averageLimitKmh, modifier)` | `private` | Speed dial + `SpeedLimitSign` (from `Navigation.kt`) + section-average chip. |
| 3014–3046 | `@Composable SectionAverageChip(averageKmh, limitKmh, modifier)` | `private` | Trajectcontrole running-average chip. |
| 3048–3099 | `@Composable NavButton(...)` | `private` | Full-width "Go"; owns `menuOpen` (3061), collects `Settings.preferredNavApp` (3063). |
| 3101–3139 | `@Composable ActiveTripCard(stats: TripStats)` | `private` | Live trip numbers. Owns `now` (3106) plus its own 1 Hz `LaunchedEffect(stats.startTimeMs)` (3107–3112). |
| 3141–3148 | `@Composable StatItem(label, value)` | `private` | Label/value column. |
| 3150–3162 | `navigateRoundTrip(context, origin, waypoints)` | `private fun` | Google Maps directions URL with up to 9 via points. |
| 3164–3172 | `navigateGoogleMaps(context, dest, mode)` | `private fun` | `google.navigation:` intent, falls back to `navigateGeo`. |
| 3174–3186 | `navigateWaze(context, dest)` | `private fun` | `waze://` intent, falls back to the universal link. |
| 3188–3193 | `navigateGeo(context, dest)` | `private fun` | `geo:` intent. |

Totals: 17 constants/values, 12 top-level functions (4 of them extension/pure
helpers), 1 data class, 1 object, 1 enum, 28 composables (1 public, 27 private).

---

## 2. State inventory of the `MapScreen` composable

All lines in `MapScreen.kt:419`–`MapScreen.kt:1837`. "Concern" refers to the
clusters named in §5. Writers/readers are exhaustive within the file.

### 2.1 Non-state locals declared before any state (context)

| Line | Name | Type | Note |
|---|---|---|---|
| 423 | `context` | `Context` | `LocalContext.current`. Read by nearly everything. |
| 429 | `fitBottomPaddingPx` | `Int` | 40% of screen height; read at 814, 839, 1468, 1493 (all `cameraForPoints` calls). |
| 434 | `attributionBottomMarginPx` | `Int` | Read at 638, 639 only. |
| 435 | `scope` | `CoroutineScope` | `rememberCoroutineScope()`. Used by `fetchLocation` (718), `startNavigation` (1003), reroute (1377), `spin` (1398). |
| 436 | `haptics` | `HapticFeedback` | Written nowhere; read at 1467, 1491 (spin landed). |
| 601 | `lifecycleOwner` | `LifecycleOwner` | Key of the `DisposableEffect` at 602. |

### 2.2 `remember` / `rememberSaveable` / `collectAsStateWithLifecycle` inventory

Legend for **Kind**: `R` = `remember`, `RS` = `rememberSaveable`,
`C` = `collectAsStateWithLifecycle`, `RU` = `rememberUpdatedState`,
`RL` = `rememberLauncherForActivityResult`, `D` = plain derived `val`.

| Line | Name | Type | Kind | Concern | Written by | Read by |
|---|---|---|---|---|---|---|
| 438 | `savedPlaces` | `List<SavedPlace>` | C (`SavedPlaces.places`) | Places | `SavedPlaces` singleton (via 1814 `SavedPlaces.add`) | 1667, 1672 |
| 440 | `savePinTarget` | `LatLon?` | R | Places | 1683 (`onSavePin`), 1815, 1817 | 1810 |
| 444 | `showBgLocationDisclosure` | `Boolean` | R | Permissions | 768 (`onLocationGranted`), 1801, 1806 | 1798 |
| 448 | `mode` | `TravelMode` | C (`Settings.tripMode`) | Spin **+** Nav **+** Overlay | `Settings.setTripMode` at 401 (`seedRouteNavigation`) and 1517 (`selectMode`) | 874/882 (overlay), 1006 (route), 1380 (reroute), 1408 (spin), 1451 (spin), 1479 (spin), 1500, 1516, 1538, 1741, 1761 |
| 449 | `radiusKm` | `Float` | **RS** | Spin | 563 (clamp effect), 1518 (`selectMode`), 1763 (slider) | 562, 874/882–884 (overlay), 1412 (spin), 1478 (spin), 1742, 1762, 1765 |
| 450 | `minRadiusKm` | `Float` | **RS** | Spin | 563, 1519, 1765 | 563, 1471, 1764 |
| 453 | `savedSpin` | `SpinResult` | R (`SpinResultHolder.state.value`) | Spin persistence | never | 454, 456, 457, 464 |
| 454 | `candidates` | `List<RouteCandidate>` | R | Spin **+** Camera **+** Convoy | 808 (`choose`), 834 (`commitSpinCandidate`), 1490 (`spin`), 1523 (`selectMode`), 1718, 1720 | 467/468 (holder sync), 700/701 (camera resume), 823 (`displayCandidates`), 1726, 1727 |
| 455 | `myLocation` | `LatLon?` | R | Location **+** Spin **+** Nav **+** Overlay **+** Camera | 557 (`liveFix` effect), 724 (`fetchLocation`) | 809, 836, 874/879, 928/930 (fog), 983 (`startNavigation`), 1270 (`haveFix`), 1280 (camera loop), 1393 (`spin`), 1747, 1775, 1823 |
| 456 | `destination` | `LatLon?` | R | Spin **+** Nav **+** Overlay **+** Places **+** Search | 805 (`choose`), 831 (`commitSpinCandidate`), 952 (map long-press), 1463 (`spin`, round trip → null), 1520 (`selectMode` → null), 1675 (chip pick), 1826 (search pick) | 467, 874/888, 989, 992, 1360, 1370, 1667, 1683, 1745, 1749, 1756, 1774, 1778, 1785, 1789 |
| 457 | `route` | `RouteResult?` | R | Spin **+** Nav **+** Overlay | 807 (`choose`), 833 (`commitSpinCandidate` → null), 954 (long-press → null), 1005 (`startNavigation`), 1379 (reroute), 1462 (`spin`), 1522 (`selectMode` → null), 1677 (chip pick → null), 1828 (search pick → null) | 467, 874/889, 995, 1345/1348, 1746, 1750, 1772, 1779 |
| 458 | `spinning` | `Boolean` | R | Spin | 1399, 1510 (`spin`) | 700/701 (camera resume), 1744, 1751, 1770, 1780 |
| 459 | `spinJob` | `Job?` | R | Spin | 1398 | 1751, 1780 (cancel) |
| 460 | `error` | `String?` | R | Spin **+** Nav **+** Permissions **+** Location | 728, 731 (`fetchLocation`), 778 (permission denied), 984, 991, 998, 1011 (`startNavigation`), 1394, 1400, 1459, 1498, 1508 (`spin`) | 1771 (`SpinSheet` only) |
| 461 | `serverConfig` | `ServerConfig` | R (`RoutingServer.load()`) | Routing | never (snapshot at composition) | 1006, 1380, 1414, 1420, 1478, 1500, 1748, 1777 |
| 462 | `poiKind` | `PoiKind` | **RS** | Spin | 1767 | 1479, 1766 |
| 463 | `directionDeg` | `Float?` | **RS** | Spin **+** Overlay | 1769 | 874/891 (overlay wedge), 1422, 1452, 1470, 1743, 1768 |
| 464 | `destinationName` | `String?` | R | Spin **+** Nav **+** Places **+** Search | 806 (`choose`), 832 (`commitSpinCandidate`), 953 (long-press, `"Dropped pin"`), 1464 (`spin` → null), 1521 (`selectMode` → null), 1676 (chip pick), 1827 (search pick) | 467, 1773 (`SpinSheet`), 1812 (`SavePinDialog` suggested name) |
| 470 | `fogEnabled` | `Boolean` | C (`Settings.fogEnabled`) | Fog | `Settings.setFogEnabled` at 1591 | 921/922, 1581, 1591 |
| 471 | `accountUsername` | `String` | C (`Account.username`) | Convoy **+** Circles **+** Chrome | `Account` singleton | 858/865 (convoy commit), 1106/1107/1113 (circle poll), 1582 (chrome) |
| 472 | `searchOpen` | `Boolean` | R | Search | 1590, 1825, 1834 | 1821 |
| 475 | `layersOpen` | `Boolean` | R | Chrome **+** Map SDK glue | 950, 958 (map click listeners), 1585 | 1584 |
| 479 | `storeVersion` | `Int` | C (`TraceStore.version`) | Fog | `TraceStore` singleton | 480 |
| 480 | `traces` | `List<List<LatLon>>` | R(storeVersion) | Fog | recomputed on `storeVersion` | 921/923 |
| 483 | `shareFog` | `Boolean` | C (`Settings.shareFog`) | Fog | `Settings` singleton | 582/583 |
| 484 | `friendTraceSource` | `List<List<LatLon>>` | C (`FriendFog.traces`) | Fog | `FriendFog` singleton | 485 |
| 485 | `friendTraces` | `List<List<LatLon>>` | D (alias of 484) | Fog | — | 921/923 |
| 486 | `stats` | `TripStats?` | C (`TripTrackingService.stats`) | Trip tracking | `TripTrackingService` (via `start`/`stop` at 989, 1636, 1756, 1785, 1789) | 988 (`startNavigation` gate), 1632, 1654, 1655, 1657, 1661, 1755, 1776, 1784 |
| 487 | `liveFix` | `Fix?` | C (`TripTrackingService.lastFix`) | Location **+** Camera **+** Nav **+** HUD | `TripTrackingService` | 555/556, 1236/1237, 1250, 1338/1340, 1345/1347, 1641 |
| 488 | `liveTrace` | `List<LatLon>` | C (`TripTrackingService.liveTrace`) | Fog | `TripTrackingService` | 928/929 |
| 491 | `convoyConnected` | `Boolean` | C (`ConvoyLiveClient.connected`) | Convoy | `ConvoyLiveClient` | 747/752, 1583, 1605 |
| 492 | `convoyTalking` | `Set<String>` | C (`ConvoyLiveClient.talking`) | Convoy | `ConvoyLiveClient` | 1610 |
| 493 | `activeConvoyId` | `Int?` | C (`ConvoyLiveClient.activeConvoyId`) | Convoy | `ConvoyLiveClient` | 503/504, 747/752, 1605, 1726 |
| 497 | `convoyPeers` | `Map<String, FriendPosition>` | C (`ConvoyLiveClient.peers`) | Convoy **+** Overlay **+** Fog | `ConvoyLiveClient` | 858/865 (commit rule), 1094/1095 (markers), 1128/1130 (fog holes) |
| 498 | `spinOffer` | `GroupSpin?` | C (`ConvoyLiveClient.spinOffer`) | Convoy **+** Spin **+** Camera | `ConvoyLiveClient` (written via `sendSpinOffer`/`clearSpinOffer` at 835, 867, 1526, 1721, 1727, 1734) | 700/701, 823, 829, 858/859, 938, 965, 1716, 1721, 1725, 1726, 1732 |
| 499 | `spinVotes` | `Map<String, Int>` | C (`ConvoyLiveClient.spinVotes`) | Convoy | `ConvoyLiveClient` (via `sendSpinVote`) | 858/866/868, 1725, 1736 |
| 502 | `convoyName` | `String?` | R | Convoy | 505 (effect on `activeConvoyId`) | 1583 |
| 514 | `navigating` | `Boolean` | R | Nav **+** Camera **+** Overlay **+** Map SDK **+** UI layout | 971 (`stopNavigation`), 996, 1009 (`startNavigation`) | 874/882 (overlay), 939 (`navigatingRef`), 1024/1025 (ambient limit gate), 1338/1339 (BLE gate), 1345/1346, 1534, 1552, 1572, 1617, 1644, 1667, 1690 |
| 515 | `navProgress` | `NavEngine.Progress?` | R | Nav | 972 (`stopNavigation`), 1351 | 1140 (`navProgressRef`), 1243 (camera zoom!), 1566, 1568, 1644, 1709, 1710 |
| 516 | `rerouting` | `Boolean` | R | Nav | 1002, 1013 (`startNavigation`), 1375, 1386 (reroute) | 1373, 1566 |
| 517 | `lastRerouteMs` | `Long` | R | Nav | 1376 | 1373 |
| 521 | `followMe` | `Boolean` | R | Camera | 1587, 1588 | 551 (`cameraActive`), 553 (`following`) |
| 522 | `camSuspended` | `Boolean` | R | Camera **+** Spin **+** Nav **+** Places **+** Search **+** Map SDK | 675 (touch `park()`), 709 (auto-resume effect → false), 810 (`choose`), 837 (`commitSpinCandidate`), 987 (`startNavigation` → false), 1403 (`spin`), 1588 (follow toggle → false), 1678 (chip pick), 1829 (search pick) | 551, 553, 689, 700/701 |
| 523 | `lastGestureMs` | `Long` | R | Camera **+** Spin **+** Places **+** Search | 676, 689 (touch), 813 (`choose`), 838 (`commitSpinCandidate`), 1679 (chip), 1830 (search) | 707 (auto-resume) |
| 526 | `settingsCollapsed` | `Boolean` | **RS** | Spin UI | 1752, 1781 | 1692 |
| 527 | `ambientSpeedLimitKmh` | `Double?` | R | Speed limits | 1048, 1053 | 1139 (`ambientLimitRef`), 1645 |
| 528–530 | `speedLimitWays` | `List<RoadRoulette.SpeedLimitWay>` | R | Speed limits | 1039 | 1046 |
| 531 | `speedLimitWaysCenter` | `LatLon?` | R | Speed limits | 1040 | 1030 |
| 532 | `speedLimitFetchMs` | `Long` | R | Speed limits | 1036 | 1034 |
| 533 | `speedLimitMisses` | `Int` | R | Speed limits | 1049, 1050 (`++`) | 1050 |
| 534 | `speedCameras` | `List<SpeedCameras.Camera>` | R | Cameras | 1077 | 1087/1088 (markers), 1138 (`speedCamerasRef`) |
| 535 | `speedSections` | `List<SpeedCameras.Section>` | R | Cameras | 1078 | 1173 (`speedSectionsRef`) |
| 538 | `sectionAvgKmh` | `Double?` | R | Cameras | 1204, 1212, 1225 | 1646 |
| 539 | `sectionLimitKmh` | `Double?` | R | Cameras | 1205, 1226 | 1647 |
| 544 | `defaultZoom` | `Float` | C (`Settings.defaultZoom`) | Camera | `Settings` | 549 (init), 1236 (key), 1241 |
| 545 | `mapIcon` | `Settings.MapIcon` | C (`Settings.mapIcon`) | Overlay | `Settings` | 907/908 |
| 546 | `routeColor` | (`Settings.routeColor`) | C | Overlay | `Settings` | 913/914 |
| 547 | `camTarget` | `LatLon?` | R | Camera | 1238 | 1270 (`haveFix`), 1280, 1300 |
| 548 | `camTargetBearing` | `Float?` | R | Camera **+** Overlay | 973 (`stopNavigation`!), 1239 | 900 (overlay position bearing), 1283, 1305 |
| 549 | `camTargetZoom` | `Double` | R | Camera | 1240 | 1284, 1309 |
| 550 | `displaySpeedKmh` | `Double` | R | HUD | 1258, 1259 (frame loop) | 1258, 1641, 1643 |
| 551 | `cameraActive` | `Boolean` | D | Camera | — | 1271 (effect key), 1273 |
| 553 | `following` | `Boolean` | D | Camera / Chrome | — | 1580, 1587 |
| 588 | `themePref` | `Settings.Theme` | C (`Settings.theme`) | Theme | `Settings` | 589 |
| 589 | `darkTheme` | `Boolean` | D (`isAppDarkTheme`) | Theme **+** Map SDK **+** Fog | — | 652 (style key), 654, 655, 921/925 |
| 590 | `fogRadius` | `Double` | C (`Settings.fogRadiusMeters`) | Fog | `Settings` | 921/924 |
| 592 | `mapView` | `MapView` | R | Map SDK | — | 629–648, 657–660, 669–693, 1547 (`AndroidView`) |
| 593 | `fogView` | `FogView` | R | Fog + Map SDK | — | 643, 656–660, 921–932, 945, 946, 1129–1131 |
| 594 | `mapLibreMap` | `MapLibreMap?` | R | Map SDK | 640 (inside `getMapAsync`) | 652/653, 725, 814, 839, 940/941, 1271/1272, 1468, 1492, 1680, 1831 |
| 595 | `mapOverlays` | `MapOverlays?` | R | Overlay | 655 (inside `setStyle` callback) | 874/876, 907, 913, 977, 1087, 1094, 1120, 1355 |
| 738 | `bgLocationLauncher` | `ManagedActivityResultLauncher` | RL | Permissions | — | 1803 |
| 744 | `micPermissionLauncher` | `ManagedActivityResultLauncher` | RL | Permissions/Convoy | — | 756 |
| 772 | `permissionLauncher` | `ManagedActivityResultLauncher` | RL | Permissions | — | 799 |
| 823 | `displayCandidates` | `List<RouteCandidate>` | D (`spinOffer?.asRouteCandidates() ?: candidates`) | Spin **+** Convoy **+** Overlay | — | 874/892, 937, 1691, 1697, 1698 |
| 937 | `candidatesRef` | `State<List<RouteCandidate>>` | **RU** | Map SDK glue | — | 963 (inside a listener registered once) |
| 938 | `spinOfferRef` | `State<GroupSpin?>` | **RU** | Map SDK glue | — | 965 |
| 939 | `navigatingRef` | `State<Boolean>` | **RU** | Map SDK glue | — | 951 |
| 1105 | `circleFixes` | `List<MemberFix>` | R | Circles | 1108, 1112, 1115 | 1120/1121 (markers), 1128/1129 (fog holes) |
| 1138 | `speedCamerasRef` | `State<List<Camera>>` | **RU** | Cameras | — | 1151 |
| 1139 | `ambientLimitRef` | `State<Double?>` | **RU** | Cameras | — | 1160 |
| 1140 | `navProgressRef` | `State<Progress?>` | **RU** | Cameras | — | 1160 |
| 1141–1143 | `toneGen` | `ToneGenerator?` | R | Cameras | — | 1144 (dispose), 1163 |
| 1173 | `speedSectionsRef` | `State<List<Section>>` | **RU** | Cameras | — | 1193 |
| 1250 | `speedTarget` | `State<Double>` | **RU** | HUD | — | 1257 |
| 1270 | `haveFix` | `Boolean` | D | Camera | — | 1271 (effect key) |
| 1654 | `shownStats` | `MutableState<TripStats?>` | R (nested, inside the bottom `Column`) | Trip tracking UI | 1655 | 1661 |
| 1697 | `shownCandidates` | `MutableState<List<RouteCandidate>>` | R (nested) | Spin UI | 1698 | 1714 |

**The "set a destination" tuple.** Seven code paths write the same four
variables in the same order — `destination`, `destinationName`, `route`, and
(five of them) `camSuspended` + `lastGestureMs`:

| Path | destination | destinationName | route | camSuspended | lastGestureMs |
|---|---|---|---|---|---|
| `choose` 803–815 | 805 | 806 | 807 (`c.route`) | 810 | 813 |
| `commitSpinCandidate` 828–840 | 831 | 832 | 833 (`null`) | 837 | 838 |
| map long-press 949–956 | 952 | 953 | 954 (`null`) | — | — |
| `spin` round trip 1462–1464 | 1463 (`null`) | 1464 (`null`) | 1462 (result) | 1403 (earlier) | — |
| `selectMode` 1515–1527 | 1520 (`null`) | 1521 (`null`) | 1522 (`null`) | — | — |
| chip pick 1674–1682 | 1675 | 1676 | 1677 (`null`) | 1678 | 1679 |
| search pick 1824–1832 | 1826 | 1827 | 1828 (`null`) | 1829 | 1830 |

`spin` sets `camSuspended` (1403) but **never** `lastGestureMs` — the one
asymmetry in the table. See H8.

### 2.3 Multi-concern state — the real coupling points

These are read **or** written by two or more concerns. Ranked by blast radius.

| State | Line | Concerns touching it | Why it couples |
|---|---|---|---|
| `destination` | 456 | Spin, Nav, Overlay, Places, Search, Map-SDK long-press, Trip tracking | 9 writers across 7 code sites; read by the overlay effect, the nav effect, `startNavigation`, the trip-start calls, and 3 card composables. The single most-shared variable in the file. |
| `route` | 457 | Spin, Nav, Overlay, Routing | 10 writers (spin result, reroute, pin drop, mode switch, chip/search pick, `startNavigation`); read by the nav progress effect and the overlay effect. Two independent coroutines write it (`startNavigation`'s `scope.launch` at 1005 and the reroute `scope.launch` at 1379). |
| `myLocation` | 455 | Location, Spin, Nav, Overlay, Fog, Camera | Written by two different sources — the `liveFix` effect (557, accuracy-gated ≤100 m) and `fetchLocation`'s FusedLocationProvider result (724, ungated). Read by 11 sites. |
| `camSuspended` | 522 | Camera, Spin, Nav, Places, Search, Map-SDK touch | 9 writers. The touch listener (675) writes it from a raw Android `OnTouchListener` closure; `spin`, `choose`, `commitSpinCandidate`, the chip pick and the search pick all park it; the auto-resume effect (709) unparks it. |
| `candidates` | 454 | Spin, Convoy, Camera, Holder sync | Written by 6 sites; read by the camera-resume gate (701), the holder-sync effect (467), `displayCandidates` (823) and the share button (1727). Note `onShare` (1726) checks `candidates`, not `displayCandidates`. |
| `spinOffer` | 498 | Convoy, Spin, Camera, Map-SDK click, Bottom card | Externally owned (`ConvoyLiveClient`), but 6 code paths in MapScreen mutate it indirectly via `sendSpinOffer`/`clearSpinOffer`/`sendSpinVote`. |
| `navigating` | 514 | Nav, Camera, Overlay, Ambient speed limit, BLE, Layout | Gate for 3 separate effects (1024, 1338, 1345) and 8 layout branches. |
| `camTargetBearing` | 548 | Camera **and** Overlay **and** Nav | Written by the fix effect (1239) *and* by `stopNavigation` (973); read by the overlay render (900) to orient the position marker and by the camera loop (1283, 1305). A nav-lifecycle function reaching into camera state. |
| `navProgress` | 515 | Nav **and** Camera **and** Cameras(warning) | The camera-target effect reads `navProgress?.distanceToTurnMeters` at 1243 to pick zoom, but `navProgress` is **not** a key of that effect (keys are `liveFix, defaultZoom`) — a stale-read by design. Also read by the camera-warning effect via `navProgressRef` (1160). |
| `mode` | 448 | Spin, Nav, Overlay, Routing | Read by the overlay reach calculation (882), both routing calls (1006, 1380), the spin (1408, 1451, 1479), and 5 UI sites. |
| `error` | 460 | Spin, Nav, Permissions, Location | Written from 12 sites in 4 concerns, but read from exactly **one** place: `SpinSheet(error = error)` at 1771. Permission and navigation errors are therefore invisible whenever the sheet is collapsed. |
| `lastGestureMs` | 523 | Camera, Spin, Places, Search | Six writers across four concerns for one purpose: buying a grace period from the auto-resume effect. |
| `mapOverlays` | 595 | Overlay, Nav, Cameras, Circles, Convoy | Read by 8 sites in 5 concerns; `stopNavigation` (977) reaches into it. |
| `fogView` | 593 | Fog, Map SDK, Circles, Convoy | Mutated as a plain Android `View` from 4 effects (921, 928, 1128) plus the map listeners (945, 946) and the style effect (656). |
| `accountUsername` | 471 | Convoy, Circles, Chrome | Keys the circle poll loop (1106) and the convoy commit rule (858). |
| `convoyPeers` | 497 | Convoy, Overlay, Fog | Explicitly moved up from the marker section (comment 494–496) so the commit rule could see it. |

---

## 3. Effect inventory

`MapScreen`'s own scope contains **32 `LaunchedEffect`s** and
**4 `DisposableEffect`s** (36 effects total, verified by counting statements at
the composable's top indentation level between 419 and 1837), plus 3 more
`LaunchedEffect`s inside nested composables (`SearchDialog` 1863 and 1869,
`ActiveTripCard` 3107).

Classification: **(a)** pure UI/derived-state, **(b)** I/O (network/disk),
**(c)** sensor/GPS stream consumer, **(d)** map-SDK imperative glue.

| # | Line | Type | Keys | What it does | External singleton | Class |
|---|---|---|---|---|---|---|
| 1 | 437 | LE | `Unit` | `SavedPlaces.ensureLoaded()` | `SavedPlaces` | b |
| 2 | 467–469 | LE | `destination, destinationName, route, candidates` | Writes the 4-tuple back into `SpinResultHolder.state` | `SpinResultHolder` | a (process-scoped write) |
| 3 | 503–512 | LE | `activeConvoyId` | Resolves convoy id → name via `Groups.list("convoy")` on `Dispatchers.IO` | `Groups` | b |
| 4 | 555–559 | LE | `liveFix` | Sets `myLocation` from the fix if `accuracyMeters <= 100f` | `TripTrackingService` (indirect) | a |
| 5 | 562–564 | LE | `radiusKm` | Clamps `minRadiusKm <= radiusKm` | — | a |
| 6 | 568–578 | LE | `Unit` | `SyncClient.sync()` on IO if configured and signed in | `SyncClient`, `Account` | b |
| 7 | 582–585 | LE | `shareFog` | `FriendFog.refresh()` on IO, or `FriendFog.clear()` | `FriendFog` | b |
| 8 | 602–625 | **DE** | `lifecycleOwner` | Lifecycle observer: `TripTrackingService.setUiVisible(true/false)` on START/STOP, `PushToTalk.stopTalking()` on STOP and on dispose | `TripTrackingService`, `PushToTalk` | b (lifecycle-tied) |
| 9 | 629–648 | **DE** | `Unit` | MapView `onCreate/onStart/onResume`, `getMapAsync` → sets `mapLibreMap`, configures uiSettings/attribution margins; `onDispose` detaches `fogView.map` and tears the MapView down | MapLibre SDK | d |
| 10 | 652–663 | LE | `darkTheme, mapLibreMap` | `map.setStyle(openFreeMapStyleUrl(darkTheme))`, builds `MapOverlays`, attaches `fogView` as a child View of `mapView` | MapLibre SDK | d |
| 11 | 669–694 | **DE** | `mapView` | Raw `setOnTouchListener`: parks the camera on pan/pinch, stamps `lastGestureMs`. Returns `false`. | Android View | d |
| 12 | 700–712 | LE | `camSuspended, spinning, candidates.isEmpty(), spinOffer == null` | Collects `TripTrackingService.lastFix` forever; un-parks the camera above `CAM_RESUME_SPEED_MPS` after `CAM_RESUME_QUIET_MS` of quiet | `TripTrackingService` | c |
| 13 | 747–758 | LE | `convoyConnected, activeConvoyId` | Requests `RECORD_AUDIO` once a convoy is actually joined | Android permissions | a |
| 14 | 782–801 | LE | `Unit` | Builds the needed-permission list (fine, coarse, activity recognition, notifications) and either calls `onLocationGranted()` or launches the request | Android permissions | a/b |
| 15 | 858–870 | LE | `spinOffer, spinVotes, convoyPeers, accountUsername` | Group-spin resolution: 1-candidate offer → `commitSpinCandidate(0)`; else if `fromMe` and all expected voters in, `ConvoyLiveClient.sendSpinOffer(listOf(leader))` | `ConvoyLiveClient` | b (network send) |
| 16 | 874–902 | LE | `mapOverlays, myLocation, destination, route, radiusKm, mode, directionDeg, navigating, displayCandidates` | The main overlay push: `overlays.render(...)` with position, destination, route polyline, reach circle, direction wedge, candidate pins, position bearing | `MapOverlays` (MapLibre) | d |
| 17 | 907–909 | LE | `mapOverlays, mapIcon` | `setPositionIcon(mapIcon)` | `MapOverlays` | d |
| 18 | 913–915 | LE | `mapOverlays, routeColor` | `setRouteColor(routeColor)` | `MapOverlays` | d |
| 19 | 921–927 | LE | `fogEnabled, fogRadius, traces, friendTraces, darkTheme` | Pushes the expensive (re-decimated) fog inputs into `fogView` and invalidates | `FogView` (Android View) | d |
| 20 | 928–932 | LE | `liveTrace, myLocation` | Pushes the cheap per-fix fog inputs and invalidates | `FogView` | d |
| 21 | 940–968 | LE | `mapLibreMap` | Registers 4 map listeners once: camera-move/idle → `fogView.invalidate()`; long-click → drop destination pin (guarded by `navigatingRef`); click → hit-test `LAYER_CANDIDATES` and either `sendSpinVote` or `choose`. Both click handlers close `layersOpen`. | MapLibre SDK, `ConvoyLiveClient` | d |
| 22 | 1024–1056 | LE | `navigating` | Ambient speed limit: collects `lastFix` (skips <2 m/s), prefetches `RoadRoulette.speedLimitWays` on IO when near the edge of the held set (10 s throttle), then `snapSpeedLimitKmh` locally; 3-miss hysteresis before clearing | `TripTrackingService`, `RoadRoulette` | c + b |
| 23 | 1062–1083 | LE | `Unit` | Speed cameras + sections: collects `lastFix`, prefetches `SpeedCameras.near` on IO with 15 s throttle and a `PREFETCH_RADIUS_M - 1000` edge test. Holds `center`/`lastFetchMs` as **coroutine-local vars**, not Compose state. | `TripTrackingService`, `SpeedCameras` | c + b |
| 24 | 1087–1089 | LE | `mapOverlays, speedCameras` | `setCameras(speedCameras)` | `MapOverlays` | d |
| 25 | 1094–1096 | LE | `mapOverlays, convoyPeers` | `setFriends(convoyPeers.values)` | `MapOverlays` | d |
| 26 | 1106–1119 | LE | `accountUsername` | Infinite `while(true)` poll of `CircleFixes.othersFixes(username)` every `CIRCLE_FIX_POLL_MS`; keeps last known on failure | `CircleFixes` | b |
| 27 | 1120–1122 | LE | `mapOverlays, circleFixes` | `setCircleMembers(circleFixes)` | `MapOverlays` | d |
| 28 | 1128–1132 | LE | `circleFixes, convoyPeers` | Sets `fogView.peers` to the union of circle + convoy positions so the scrim doesn't hide their markers, then invalidates | `FogView` | d |
| 29 | 1144 | **DE** | `Unit` | `onDispose { toneGen?.release() }` | `ToneGenerator` | a (resource) |
| 30 | 1145–1167 | LE | `Unit` | Speed-camera chime: collects `lastFix`, finds the nearest camera within `WARN_METERS` inside a 45° wedge, beeps once per camera when over the limit + 3 km/h. Holds `warnedAt` as a coroutine-local var. Reads `speedCamerasRef`, `navProgressRef`, `ambientLimitRef`. | `TripTrackingService`, `RoadRoulette`, `SpeedCameras` | c |
| 31 | 1174–1230 | LE | `Unit` | Trajectcontrole average: collects `lastFix`, holds `active`/`exitGate`/`entryMs`/`accMeters`/`last` as coroutine-local vars, integrates distance/time, writes `sectionAvgKmh`/`sectionLimitKmh`. Exit on reached-end / overshoot (`spanMeters*1.4+400`) / 30-minute timeout. | `TripTrackingService`, `RoadRoulette` | c |
| 32 | 1236–1245 | LE | `liveFix, defaultZoom` | Moves the camera *targets* only: `camTarget`, `camTargetBearing` (only if speed > 2 m/s), `camTargetZoom` via `NavEngine.cameraZoom(...)`. Reads `navProgress` **without keying on it**. | `NavEngine` | a |
| 33 | 1251–1263 | LE | `Unit` | Speedometer frame loop: `withFrameNanos`, eases `displaySpeedKmh` toward `speedTarget` with `SPEED_TAU`, snapping under `SPEED_EPS_KMH`. Never returns. | — | a (frame loop) |
| 34 | 1271–1333 | LE | `cameraActive, haveFix, mapLibreMap` | **The camera loop.** If `!cameraActive`, levels the map to north-up once and returns. Otherwise an infinite `withFrameNanos` loop easing lat/lon/bearing/zoom toward the targets and calling `setCamera` only when past the EPS thresholds. Holds `lat/lon/bearing/zoom/applied*` as coroutine-local vars. | MapLibre SDK | d + a |
| 35 | 1338–1342 | LE | `navigating, liveFix` | Non-navigating case only: `BleNavServer.sendStats(currentSpeedKmh)` | `BleNavServer` | b (IPC/BLE) |
| 36 | 1345–1390 | LE | `navigating, liveFix, route` | **The nav loop.** `NavEngine.progress(route, pos)` → `navProgress`; `mapOverlays.setDrivenFraction`; `NavRelay.send`; `BleNavServer.send`; arrival test (`remainingMeters < 40 && offRouteMeters < 60`) → `stopNavigation()`; off-route test (`> 60 m`, not rerouting, 15 s cooldown) → `scope.launch` a fresh `RoutingServer.route` | `NavEngine`, `NavRelay`, `BleNavServer`, `RoutingServer`, `Settings`, `MapOverlays` | b + d + a |

Nested-composable effects (not in `MapScreen`'s own scope):

| Line | Owner | Keys | What it does | Class |
|---|---|---|---|---|
| 1863 | `SearchDialog` | `Unit` | `focusRequester.requestFocus()` | a |
| 1869–1893 | `SearchDialog` | `query` | 300 ms debounce, then `Geocoder.search(q, near)` on IO, merged with `RecentSearchStore` hits | b |
| 3107–3112 | `ActiveTripCard` | `stats.startTimeMs` | 1 Hz `now` tick so the duration counts up without GPS | a |

**Six separate collectors of `TripTrackingService.lastFix`** run concurrently
while the map is composed: effects 12, 22, 23, 30, 31 (each `.collect { }`
directly) plus the `collectAsStateWithLifecycle` at 487 that feeds effects 4, 32,
35, 36 and the HUD.

---

## 4. Local (nested) function inventory

Eight `fun`s are declared inside the `MapScreen` composable body. Each is a
closure over composable-scoped `var`s (Compose `MutableState` delegates), so any
extraction must carry both the reads and the writes listed here.

### 4.1 `fetchLocation()` — `MapScreen.kt:714`–`734`
- **Captures (read):** `context` (423), `scope` (435), `mapLibreMap` (594).
- **Captures (write):** `myLocation` (455), `error` (460).
- **External:** `ContextCompat.checkSelfPermission`, `LocationServices.getFusedLocationProviderClient`, `Settings.defaultZoom.value` (726, read from the singleton directly rather than the collected `defaultZoom` at 544), `CameraUpdateFactory.newLatLngZoom`.
- **Note:** it calls `mapLibreMap?.moveCamera` directly (725) — an imperative camera jump that bypasses the eased follow loop and does **not** set `camSuspended`.

### 4.2 `onLocationGranted()` — `MapScreen.kt:760`–`770`
- **Captures (read):** `context`.
- **Captures (write):** `showBgLocationDisclosure` (444).
- **Calls:** `fetchLocation()` (761) — so it transitively captures everything in §4.1.
- **External:** `TripTrackingService.startMonitoring(context)` (762), `ContextCompat.checkSelfPermission(ACCESS_BACKGROUND_LOCATION)`.
- **Declared after** `fetchLocation` and **before** `permissionLauncher` (772) — the launcher's callback closes over it, so declaration order here is load-bearing for Kotlin's local-function scoping.

### 4.3 `choose(c: RouteCandidate)` — `MapScreen.kt:803`–`815`
- **Captures (write):** `destination` (456), `destinationName` (464), `route` (457), `candidates` (454), `camSuspended` (522), `lastGestureMs` (523).
- **Captures (read):** `myLocation` (455), `mapLibreMap` (594), `fitBottomPaddingPx` (429).
- **External:** `cameraForPoints` (from `MapLibreMap.kt`), `FIT_PADDING_PX`.
- **Called from:** the map click listener (965) and `CandidatesCard.onPick` (1716).

### 4.4 `commitSpinCandidate(index: Int)` — `MapScreen.kt:828`–`840`
- **Captures (read):** `spinOffer` (498), `myLocation`, `mapLibreMap`, `fitBottomPaddingPx`.
- **Captures (write):** `destination`, `destinationName`, `route` (set to `null` deliberately, 833), `candidates`, `camSuspended`, `lastGestureMs`.
- **External:** `ConvoyLiveClient.clearSpinOffer()` (835), `cameraForPoints`, `FIT_PADDING_PX`.
- **Called from:** the group-spin resolution effect only (861).
- **Differs from `choose` only in:** source (`spinOffer` vs. a `RouteCandidate`), `route = null`, and the `clearSpinOffer()` call.

### 4.5 `stopNavigation()` — `MapScreen.kt:970`–`980`
- **Captures (write):** `navigating` (514), `navProgress` (515), `camTargetBearing` (548 — camera state written by a nav function).
- **Captures (read):** `mapOverlays` (595), `context`.
- **External:** `mapOverlays?.setDrivenFraction(null)` (977), `NavRelay.clear(context)` (978), `BleNavServer.clear(context)` (979).
- **Called from:** the nav loop's arrival branch (1363) and `NavigationBottomBar.onExit` (1711).
- **Does not** clear `destination`/`route` — the line stays drawn (comment 974–976).

### 4.6 `startNavigation()` — `MapScreen.kt:982`–`1016`
- **Captures (read):** `myLocation` (455), `stats` (486), `destination` (456), `route` (457), `mode` (448), `serverConfig` (461), `scope` (435), `context`.
- **Captures (write):** `error` (460), `camSuspended` (522), `navigating` (514), `rerouting` (516), `route` (457, inside `scope.launch` at 1005).
- **External:** `TripTrackingService.start(context, destination?.lat, destination?.lon)` (989), `RoutingServer.route(serverConfig, loc, dest, mode.ghProfile, Settings.avoidHighways.value, Settings.avoidSmallRoads.value)` (1006–1007 — reads two `Settings` flows via `.value`, not via collected state).
- **Two exit paths:** round trip (`destination == null`) needs `route?.instructions` non-empty (995) and returns without any I/O; point-to-point always re-fetches.
- **Called from:** `SpinDock.onNavigateInApp` (1753) and `SpinSheet.onNavigateInApp` (1782), both reached through `launchNav` → `Settings.NavApp.IN_APP`.

### 4.7 `spin()` — `MapScreen.kt:1392`–`1513` (121 lines, the largest local fun)
- **Captures (read):** `myLocation` (455), `scope` (435), `mode` (448), `radiusKm` (449), `minRadiusKm` (450), `directionDeg` (463), `poiKind` (462), `serverConfig` (461), `mapLibreMap` (594), `haptics` (436), `fitBottomPaddingPx` (429).
- **Captures (write):** `error` (460), `spinJob` (459), `spinning` (458), `camSuspended` (522), `route` (457), `destination` (456), `destinationName` (464), `candidates` (454).
- **Calls:** `fetchLocation()` (1395) when there is no location yet.
- **External:** `ExploredArea.load()` (1407), `RoutingServer.roundTrip` (1420), `Curviness.routeScore` (1426), `Settings.avoidSmallRoads.value` (1423), `RoundTripPlanner.plan` (1450), `pickCandidate` (1477), `cameraForPoints` (1468, 1493), `CURVY_CANDIDATES`, `FIT_PADDING_PX`.
- **Structure:** one `scope.launch` (1398) assigned to `spinJob`, holding a local `serverError: String?` (1404); a round-trip branch (1408–1468) racing `CURVY_CANDIDATES` parallel `async(Dispatchers.IO)` rolls and keeping the max curviness score; a point-to-point branch (1469–1495) racing 3 `pickCandidate` calls under a `withTimeout(30_000)`. `CancellationException` is rethrown (1436, 1442, 1505); `finally` always clears `spinning` (1510).
- **Called from:** `SpinDock.onSpin` (1751), `SpinSheet.onSpin` (1780), `CandidatesCard.onReroll` (1718).

### 4.8 `selectMode(m: TravelMode)` — `MapScreen.kt:1515`–`1527`
- **Captures (read):** `mode` (448), `spinOffer` (498).
- **Captures (write):** `radiusKm` (449), `minRadiusKm` (450), `destination` (456), `destinationName` (464), `route` (457), `candidates` (454).
- **External:** `Settings.setTripMode(m)` (1517), `ConvoyLiveClient.clearSpinOffer()` (1526).
- **Passed as a function reference** `::selectMode` to `ModeBar` at 1538 — the only local fun passed by reference rather than in a lambda.

### 4.9 Nested `fun`s inside other scopes (for completeness)
- `park()` — `MapScreen.kt:674`–`677`, inside the touch-listener lambda. Writes `camSuspended`, `lastGestureMs`.
- `atGate` / `ahead` — `MapScreen.kt:305`–`308`, inside top-level `sectionExitGate`. Pure.
- `pick(r: GeocodeResult)` — `MapScreen.kt:1857`–`1860`, inside `SearchDialog`. Calls `RecentSearchStore.save`.
- `pick(app: Settings.NavApp)` — `MapScreen.kt:2203`–`2206`, inside `NavMenuItems`.

---

## 5. Concern clusters

Naming is this document's own. Line counts are approximate contiguous spans plus
scattered members; they sum to more than 3193 because several regions serve two
concerns at once (that overlap *is* the coupling).

| # | Concern | Approx. lines | Members |
|---|---|---|---|
| C1 | **Spin / roulette** | ~330 | consts 259, 267; `CANDIDATE_COLORS` 316–320; state 449, 450, 453, 454, 456, 457, 458, 459, 460, 462, 463, 464, 526; effects 467, 562; funs `spin` 1392–1513, `choose` 803–815, `selectMode` 1515–1527; composables `SpinDock` 2281–2366, `SpinSheet` 2368–2547, `CandidatesCard` 2549–2681, `SegmentedPillRow`/`ScrollingPillRow`/`Pill` 2057–2121; `SpinResult`/`SpinResultHolder`/`seedRouteNavigation` 369–414; `BottomCard` 416–417 |
| C2 | **In-app navigation** | ~180 | state 514–517; funs `startNavigation` 982–1016, `stopNavigation` 970–980; effect 1345–1390; consts none of its own; UI 1551–1570, 1708–1712; `NavigationBanner`/`ThenPill`/`NavigationBottomBar` are already in `Navigation.kt` |
| C3 | **Camera / follow loop** | ~200 | consts 236–255, 273–274; `smoothBearing` 221–230; state 521–523, 547–551, 553, 1270; touch `DisposableEffect` 669–694; auto-resume effect 700–712; target effect 1236–1245; frame loop 1271–1333; follow toggle UI 1586–1589 |
| C4 | **Map SDK lifecycle & imperative glue** | ~120 | state 592–595; `DisposableEffect` 629–648; style effect 652–663; listener effect 940–968; `AndroidView` 1547; `rememberUpdatedState` refs 937–939 |
| C5 | **Overlay rendering** | ~70 | state 545, 546, 595; effects 874–902, 907–909, 913–915, 1087–1089, 1094–1096, 1120–1122; `CANDIDATE_COLORS` |
| C6 | **Fog of war** | ~55 | state 470, 479, 480, 483, 484, 485, 488, 590, 593; effects 582–585, 921–927, 928–932, 1128–1132; layers-panel toggle 1591 |
| C7 | **Speed limits (ambient)** | ~50 | state 527–533; effect 1024–1056; consumed by `SpeedHud` 1644–1645 |
| C8 | **Speed cameras & trajectcontrole** | ~150 | consts 283, 288; `sectionExitGate` 290–314; state 534–539, 1138–1143, 1173; effects 1062–1083, 1087–1089, 1144, 1145–1167, 1174–1230; `SectionAverageChip` 3014–3046 |
| C9 | **Convoy (live peers, PTT, group spin)** | ~150 | `asRouteCandidates` 322–343, `asSpinCandidates` 345–355, `leadingSpinIndex` 357–367; state 491–493, 497–499, 502; effects 503–512, 747–758, 858–870, 1094–1096; `commitSpinCandidate` 828–840; `displayCandidates` 823; UI 1604–1611, 1716–1738; `PushToTalkButton` 2899–2954, `ConvoyPill` 2827–2851 |
| C10 | **Circles (member fixes)** | ~35 | const 279; state 1105; effects 1106–1119, 1120–1122, 1128–1132 |
| C11 | **Location & permissions** | ~90 | state 444, 455, 738, 744, 772; `fetchLocation` 714–734, `onLocationGranted` 760–770; effects 555–559, 747–758, 782–801; `BackgroundLocationDisclosure` 1998–2028 |
| C12 | **Trip tracking (service surface)** | ~50 | state 486; `DisposableEffect` 602–625; `TripTrackingService.start/stop` calls 989, 1636, 1756, 1785, 1789; `ActiveTripCard` 3101–3139, `StatItem` 3141–3148, `EndTripButton` 2881–2897 |
| C13 | **Speed HUD** | ~90 | const 244, 247; state 550, 1250; frame loop 1251–1263; UI 1641–1649; `SpeedHud` 2956–3012 |
| C14 | **External nav-app dispatch** | ~180 | `launchNav` 2123–2149, `navAppUsableDirectly` 2151–2164, `handleGoTap` 2166–2186, `NavMenuItems` 2188–2232, `NavIconButton` 2234–2279, `NavButton` 3048–3099, `navigateRoundTrip`/`navigateGoogleMaps`/`navigateWaze`/`navigateGeo` 3150–3193 |
| C15 | **Search & saved places** | ~200 | state 438, 440, 472; UI 1666–1685, 1810–1836; `SearchDialog` 1839–1958, `ShortcutChips` 1960–1996, `SavePinDialog` 2030–2055 |
| C16 | **Top chrome & theme** | ~130 | state 475, 553, 588, 589; UI 1571–1597; `MapTopChrome` 2699–2775, `SearchPill` 2777–2825, `GlassRailButton` 2853–2879 |
| C17 | **Sync / bootstrap** | ~15 | `DIRECTION_NAMES` 210–211; `TravelMode.icon` 213–219; effects 437, 568–578; `ModeBar` 2683–2697 |
| C18 | **Wear / BLE relay** | ~10 | effects 1338–1342, and inside C2's nav loop at 1356–1357; `stopNavigation`'s clears 978–979 |

### 5.1 Coupling matrix

Only pairs that actually share state or a function are listed. Read as: "these
two concerns are welded together by X".

| Pair | Shared |
|---|---|
| C1 Spin ↔ C2 Nav | `destination` 456, `destinationName` 464, `route` 457, `mode` 448, `error` 460, `serverConfig` 461. `startNavigation` reads what `spin`/`choose` wrote; `stopNavigation` leaves them alone by design. |
| C1 Spin ↔ C3 Camera | `camSuspended` 522 and `lastGestureMs` 523 (written by `spin` 1403, `choose` 810/813, `commitSpinCandidate` 837/838); `candidates`/`spinning`/`spinOffer` are keys of the auto-resume effect at 700; `cameraForPoints` calls at 814, 839, 1468, 1493 with `fitBottomPaddingPx`. |
| C1 Spin ↔ C5 Overlay | `displayCandidates` 823 → `overlays.render(candidates=…)` 892; `radiusKm`/`mode`/`directionDeg` → the reach circle and wedge 879–891; `CANDIDATE_COLORS` shared between pin colour (893) and card swatch (2596). |
| C1 Spin ↔ C9 Convoy | `displayCandidates` 823 (`spinOffer ?: candidates`); `commitSpinCandidate` duplicates `choose`; `selectMode` calls `clearSpinOffer` 1526; `CandidatesCard` switches between pick and vote via `spinOffer != null` (1716, 1725, 1726, 1732). |
| C1 Spin ↔ C15 Places/Search | Both write `destination`/`destinationName`/`route` (chip pick 1675–1678, search pick 1826–1828) and both park the camera (1677–1679, 1829–1830). |
| C2 Nav ↔ C3 Camera | `stopNavigation` writes `camTargetBearing = null` (973); `startNavigation` writes `camSuspended = false` (987); `cameraActive` (551) is `(followMe || navigating) && !camSuspended`; the camera-zoom target reads `navProgress` (1243). |
| C2 Nav ↔ C4 Map SDK | `navigatingRef` (939) gates the long-press pin drop (951); `stopNavigation` and the nav loop both call `mapOverlays.setDrivenFraction` (977, 1355). |
| C2 Nav ↔ C7 Speed limits | `navigating` gates the ambient effect entirely (1024–1025); the HUD picks `navProgress?.speedLimitKmh` over `ambientSpeedLimitKmh` (1644–1645). |
| C2 Nav ↔ C8 Cameras | `navProgressRef` (1140) supplies the limit for the chime when navigating (1160). |
| C2 Nav ↔ C18 Relay | `NavRelay.send`/`BleNavServer.send` inside the nav loop (1356–1357); `NavRelay.clear`/`BleNavServer.clear` in `stopNavigation` (978–979); `BleNavServer.sendStats` in the *not*-navigating effect (1341) — the two halves of one characteristic split across two effects keyed differently. |
| C3 Camera ↔ C4 Map SDK | `mapLibreMap` (594) is the camera loop's key and target; the touch `DisposableEffect` (669) is registered on the same `mapView` the `AndroidView` hosts; the camera-move listener (945) drives fog invalidation. |
| C3 Camera ↔ C5 Overlay | `camTargetBearing` (548) is passed to `overlays.render(positionBearingDeg=…)` at 900 — the overlay orients the vehicle icon from a camera variable. |
| C3 Camera ↔ C11 Location | `myLocation` seeds the camera loop start (1280) and `haveFix` (1270); `fetchLocation` calls `moveCamera` directly (725). |
| C4 Map SDK ↔ C6 Fog | `fogView` is added as a child of `mapView` inside the style effect (656–661) and removed on dispose (643); camera-move/idle listeners invalidate it (945–946). |
| C4 Map SDK ↔ C16 Chrome | Both map click listeners set `layersOpen = false` (950, 958) — chrome state written from a MapLibre callback. |
| C5 Overlay ↔ C8 Cameras | `mapOverlays.setCameras` (1088). |
| C5 Overlay ↔ C9 Convoy | `mapOverlays.setFriends` (1095). |
| C5 Overlay ↔ C10 Circles | `mapOverlays.setCircleMembers` (1121). |
| C6 Fog ↔ C9/C10 | `fogView.peers` is the union of circle fixes and convoy peers (1129–1130). |
| C7 Speed limits ↔ C8 Cameras | `ambientLimitRef` (1139) feeds the chime's over-the-limit test (1160); `SpeedHud` renders both (1645–1647). |
| C8 Cameras ↔ C13 HUD | `sectionAvgKmh`/`sectionLimitKmh` (538–539) → `SpeedHud(averageKmh=…, averageLimitKmh=…)` (1646–1647). |
| C9 Convoy ↔ C10 Circles ↔ C16 Chrome | all three read `accountUsername` (471). |
| C11 Location ↔ C12 Trip tracking | `onLocationGranted` calls `TripTrackingService.startMonitoring` (762); the lifecycle `DisposableEffect` (602) toggles `setUiVisible`, which is what makes `lastFix` navigation-grade for every C3/C7/C8/C13 consumer. |
| C12 Trip tracking ↔ C1/C2 | `stats == null` gates the implicit `TripTrackingService.start` inside `startNavigation` (988) and the three `onNavigate` lambdas (1755, 1784, 1789). |
| C14 Nav dispatch ↔ C1/C2 | `inAppAvailable` is computed inline twice, identically, at 1748–1750 and 1777–1779 (`serverConfig.usable && (destination != null || route?.instructions?.isNotEmpty() == true)`). |

---

## 6. External coupling

### 6.1 Who depends on symbols declared in MapScreen.kt

MapScreen.kt's entire outward surface is **three symbols**, used from **five**
call sites in **three** files.

| Symbol | Consumer | Use |
|---|---|---|
| `MapScreen` (420) | `app/src/main/java/com/jellemax/detour/MainActivity.kt:43` | `import com.jellemax.detour.ui.MapScreen` |
| `MapScreen` (420) | `app/src/main/java/com/jellemax/detour/MainActivity.kt:223` | `Screen.MAP -> MapScreen(onOpenHub = { screen = Screen.HUB })` |
| `TravelMode.icon` (213) | `app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt:303` | `Icon(route.mode.icon, …)` in the saved-routes row |
| `TravelMode.icon` (213) | `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt:317` | `trip.mode.icon` as the trip-row leading icon |
| `TravelMode.icon` (213) | `app/src/main/java/com/jellemax/detour/ui/RouteEditorScreen.kt:386` | `Icon(m.icon, …)` as a `FilterChip` leading icon |
| `seedRouteNavigation` (400) | `app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt:202` | Called from `fun navigate(route: SavedRoute)` **only when `route.stops.size <= 2`**; longer routes go to `navigateStopsExternally` |

Everything else — all 15 consts, `DIRECTION_NAMES`, `smoothBearing`,
`sectionExitGate`, `CANDIDATE_COLORS`, `asRouteCandidates`, `asSpinCandidates`,
`leadingSpinIndex`, `BottomCard`, and all 27 private composables/helpers — has
**zero** references outside the file, in `app/src/main`, `app/src/test`, `wear/`
or `shared/`.

**`SpinResult` and `SpinResultHolder` are `internal` but have zero external
references.** Their only uses are `MapScreen.kt:403` (write, inside
`seedRouteNavigation`), `:453` (seed read) and `:468` (write-back). The comment
at `MapScreen.kt:374`–`375` ("RoutesScreen.kt, which calls it, need[s] to write
into this holder from outside") is **stale**: RoutesScreen.kt never touches the
holder, only `seedRouteNavigation`. Only `seedRouteNavigation` genuinely needs
non-private visibility.

**Not references, but near-identical re-declarations** (see §7): `CarMapRenderer.kt`
declares its own private `smoothBearing` and its own `CAM_POS_TAU`,
`CAM_BEARING_TAU`, `CAM_ZOOM_TAU`, `CAM_POS_EPS_DEG`, `CAM_ZOOM_EPS`,
`CAM_BEARING_EPS_DEG`, `CIRCLE_FIX_POLL_MS`.

Also unrelated despite the name collision: `BadgesScreen.kt:60` declares
`private val BadgeKind.icon`, and `iosApp/Detour/MapScreen.swift` is a parallel
Swift port with its own `leadingSpinIndex(of:)` at `:339` — no cross-language
linkage, but it will drift if the Kotlin rule at `MapScreen.kt:361` changes.

### 6.2 Docs that cite MapScreen.kt line numbers (will go stale on any edit)

- `docs/superpowers/specs/2026-08-11-map-layers-panel-toggle-design.md:18,22,26,38,40,42,46,76,78,106` — cites `MapScreen.kt:2708`, `:2256`, `:3082`, `:2723`, `:2720`, `:2848`.
- `audit.md:256,260,270,449,524` — names `MapScreen`, `SpeedHud`, `CandidatesCard`, and `MapToolbar` (which no longer exists).
- `guide.md:276,297,374,378,381,385,533`; `docs/PLAY_LOCATION_DECLARATION.md:19-20`; `FUTURE.md:15,22`.

### 6.3 Singletons MapScreen.kt depends on

Call counts are for `MapScreen.kt:210`–`3193`.

| Singleton | Home | Calls | Members touched |
|---|---|---|---|
| `Settings` | `shared/…/data/Settings.kt:14` | 37 | flows `tripMode` 448, `fogEnabled` 470, `shareFog` 483, `defaultZoom` 544, `mapIcon` 545, `routeColor` 546, `theme` 588, `fogRadiusMeters` 590, `preferredNavApp` 2250/3063; mutators `setTripMode` 401/1517, `setFogEnabled` 1591, `setPreferredNavApp` 2148; direct `.value` reads 449, 726, 1007, 1381, 1423; nested type `Settings.NavApp`, `Settings.MapIcon` |
| `TripTrackingService` | `app/…/tracking/TripTrackingService.kt` | 17 | `stats` 486, `lastFix` 487 + 5 direct `.collect` (704, 1026, 1065, 1147, 1180), `liveTrace` 488, `setUiVisible` 605/613/622, `startMonitoring` 762, `start` 989/1756/1785/1789, `stop` 1636 |
| `ConvoyLiveClient` | `app/…/net/ConvoyLiveClient.kt:111` | 18 | `connected` 491, `talking` 492, `activeConvoyId` 493, `peers` 497, `spinOffer` 498, `spinVotes` 499, `clearSpinOffer` 835/1526/1721, `sendSpinOffer` 867/1727/1734, `sendSpinVote` 965/1716 |
| `RoadRoulette` | `shared/…/data/RoadRoulette.kt:30` | 14 | `distanceMeters` 306/1030/1068/1152/1155/1196/1208/1218, `withinWedge` 308/1154, `speedLimitWays` 1037, `snapSpeedLimitKmh` 1045, `SPEED_PREFETCH_RADIUS_M` 1033, type `SpeedLimitWay` 529 |
| `SpeedCameras` | `shared/…/data/SpeedCameras.kt:25` | 7 | types `Section` 301/535/1175, `Camera` 534; `PREFETCH_RADIUS_M` 1071, `near` 1075, `WARN_METERS` 1152 |
| `RoutingServer` | `shared/…/data/RoutingServer.kt:57` | 4 | `load()` 461, `route()` 1006/1380, `roundTrip()` 1420 |
| `PushToTalk` | `app/…/audio/PushToTalk.kt:25` | 4 | `stopTalking` 614/623/2935, `startTalking` 2930 |
| `BleNavServer` | `app/…/ble/BleNavServer.kt:85` | 4 | `clear` 979, `sendStats` 1341, `send` 1357 |
| `NavEngine` | `shared/…/data/NavEngine.kt:11` | 3 | type `Progress` 515, `cameraZoom` 1240, `progress` 1350 |
| `SavedPlaces` | `shared/…/data/SavedPlaces.kt:22` | 3 | `ensureLoaded` 437, `places` 438, `add` 1814 |
| `FriendFog` | `shared/…/data/FriendFog.kt:15` | 3 | `traces` 484, `refresh` 583, `clear` 584 |
| `SpinResultHolder` | same file, 383 | 3 | `state` 403/453/468 |
| `NavRelay` | `app/…/wear/NavRelay.kt:11` | 2 | `clear` 978, `send` 1356 |
| `TraceStore` | `shared/…/data/TraceStore.kt:25` | 2 | `version` 479, `loadAll` 480 |
| `Account` | `shared/…/data/Social.kt:31` | 2 | `username` 471, `signedIn` 569 |
| `SyncClient` | `shared/…/data/SyncClient.kt:17` | 2 | `configured` 569, `sync` 572 |
| `RecentSearchStore` | `shared/…/data/RecentSearchStore.kt:8` | 2 | `load` 1853, `save` 1858 (in `SearchDialog`) |
| `CircleFixes` | `shared/…/data/CircleFixes.kt:23` | 1 | `othersFixes` 1113 |
| `Groups` | `shared/…/data/Groups.kt:29` | 1 | `list("convoy")` 507 |
| `Geocoder` | `shared/…/data/Geocoder.kt:21` | 1 | `search` 1883 (in `SearchDialog`) |
| `RoundTripPlanner` | `shared/…/data/RoundTripPlanner.kt:143` | 1 | `plan` 1450 |
| `Curviness` | `shared/…/data/RoundTripPlanner.kt:27` | 1 | `routeScore` 1426 |
| `ExploredArea` | `shared/…/data/ExploredArea.kt:13` (class, not object) | 1 | `ExploredArea.load()` 1407 |
| `pickCandidate` | `shared/…/data/SpinPicker.kt:59` (top-level suspend fun) | 1 | 1477 |

Same-package helpers from already-split neighbours: `MapOverlays`, `CandidatePin`,
`FogView`, `cameraForPoints`, `setCamera`, `openFreeMapStyleUrl`, `LAYER_CANDIDATES`
(all `ui/MapLibreMap.kt`); `NavigationBanner`, `ThenPill`, `NavigationBottomBar`,
`SpeedLimitSign` (`ui/Navigation.kt`); `glassCardColors`, `glassContainerColor`,
`glassBorder` (`ui/GlassSurface.kt`); `formatDistanceKm`, `formatDuration`,
`formatSpeedKmh`, `formatLeanAngle`, `formatGForce` (`ui/Format.kt`);
`isAppDarkTheme` (`ui/Theme.kt`).

---

## 7. Overlap with the Android Auto surface

The car package is `app/src/main/java/com/jellemax/detour/car/`: `NavScreen.kt`
(593), `CarMapRenderer.kt` (663), `SpinScreen.kt` (352), `SearchScreen.kt` (174),
`NavVoice.kt` (150), `DetourCarSession.kt` (40), `DetourCarAppService.kt` (13) —
1972 lines total, roughly 62% of MapScreen.kt on its own.

The car surface imports **nothing** from MapScreen.kt. It imports `MapOverlays`,
`openFreeMapStyleUrl` and `setCamera` from `ui/MapLibreMap.kt`
(`CarMapRenderer.kt:27-29`) and `formatDistanceKm` from `ui/Format.kt`
(`SpinScreen.kt:39`). Every overlap below is copy-paste, not reuse.

### 7.1 Constants duplicated verbatim

| Constant | Car | Phone | Value |
|---|---|---|---|
| `CAM_POS_TAU` | `CarMapRenderer.kt:53` | `MapScreen.kt:236` | `0.35` |
| `CAM_BEARING_TAU` | `CarMapRenderer.kt:54` | `MapScreen.kt:237` | `0.5` |
| `CAM_ZOOM_TAU` | `CarMapRenderer.kt:55` | `MapScreen.kt:238` | `1.2` |
| `CAM_POS_EPS_DEG` | `CarMapRenderer.kt:67` | `MapScreen.kt:253` | `2e-6` |
| `CAM_ZOOM_EPS` | `CarMapRenderer.kt:68` | `MapScreen.kt:254` | `2e-3` |
| `CAM_BEARING_EPS_DEG` | `CarMapRenderer.kt:69` | `MapScreen.kt:255` | `0.1f` |
| `CIRCLE_FIX_POLL_MS` | `CarMapRenderer.kt:78` | `MapScreen.kt:279` | `120_000L` |

All are `private const val` in their own file. `CarMapRenderer.kt:77` explicitly
cross-references "see MapScreen's CIRCLE_FIX_POLL_MS" in a comment — a
documented, deliberate copy with no shared source of truth.

Three more phone constants exist on the car under **different names**, same values:

| Concept | Car (named) | Phone (inline literal) |
|---|---|---|
| Arrival radius | `ARRIVE_METERS = 40.0` `NavScreen.kt:53` | `40` at `MapScreen.kt:1360` |
| Off-route threshold | `OFF_ROUTE_METERS = 60.0` `NavScreen.kt:54` | `60` at `MapScreen.kt:1361`, `1372`, and `1710` |
| Reroute cooldown | `REROUTE_COOLDOWN_MS = 15_000L` `NavScreen.kt:55` | `15_000` at `MapScreen.kt:1373` |
| Camera prefetch margin | `CAMERA_FETCH_MARGIN_M = 1000.0` `NavScreen.kt:56` | `1000.0` at `MapScreen.kt:1071` |
| Camera prefetch throttle | `CAMERA_FETCH_THROTTLE_MS = 15_000L` `NavScreen.kt:57` | `15_000` at `MapScreen.kt:1072` |
| Ambient-limit prefetch margin | `LIMIT_FETCH_MARGIN_M = 500.0` `SpinScreen.kt:52` | `500.0` at `MapScreen.kt:1033` |
| Ambient-limit throttle | `LIMIT_FETCH_THROTTLE_MS = 10_000L` `SpinScreen.kt:53` | `10_000` at `MapScreen.kt:1034` |
| Bearing-hold floor | `LIMIT_MIN_MPS = 2.0` `SpinScreen.kt:57`, `BEARING_HOLD_MPS = 2.0` `CarMapRenderer.kt:73` | `2.0` at `MapScreen.kt:1028`, `1189`, `1239` |
| Limit-miss hysteresis | `LIMIT_MISSES_TO_CLEAR = 3` `SpinScreen.kt:61` | `3` at `MapScreen.kt:1050` |

The phone is the side that inlines the magic numbers.

### 7.2 `smoothBearing` — duplicated function, identical body

`MapScreen.kt:224`:
```kotlin
private fun smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float {
    if (current == null) return target
    var delta = (target - current) % 360f
    ...
}
```
`CarMapRenderer.kt:470`:
```kotlin
private fun smoothBearing(current: Float, target: Float, alpha: Float): Float {
    var delta = (target - current) % 360f
    ...
}
```
Bodies from `var delta` onwards are character-identical. The car version drops
the nullable receiver and the `0.3f` default (it always passes an explicit
alpha). Even the KDoc is near-verbatim (`MapScreen.kt:221-223` vs
`CarMapRenderer.kt:467-469`, the latter ending "Same as the phone map's.").

### 7.3 The camera easing loop — duplicated body, divergent frame source

`MapScreen.kt:1294-1332` vs `CarMapRenderer.kt:383-427`. Identical structure:
ease pos with `1.0 - exp(-dt / CAM_POS_TAU)`, ease bearing through
`smoothBearing` with `(1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat()`, ease zoom
with `CAM_ZOOM_TAU`, wrap `dBearing` to ±180, then the same four-term `moved`
test against `applied*` sentinels seeded with `Double.NaN`.

Three divergences:
- Frame source: `withFrameNanos` (`MapScreen.kt:1295`) vs `delay(CAM_FRAME_MS)` with `CAM_FRAME_MS = 33L` (`CarMapRenderer.kt:61`, `:392`) — the car has no Compose frame clock.
- dt clamp: `coerceIn(0.0, 0.1)` (`MapScreen.kt:1297`) vs `coerceIn(0.0, 0.25)` (`CarMapRenderer.kt:396`).
- Push: `setCamera(map, lat, lon, zoom, bearing)` inline (`MapScreen.kt:1326`) vs `applyCamera(map)` (`CarMapRenderer.kt:420`, defined `:429-431`) — both ultimately call the shared `setCamera` from `ui/MapLibreMap.kt:481`.

The car also has a first-fix snap (`CarMapRenderer.kt:200-207`, "if `camLat.isNaN()`
jump straight there") that the phone handles differently, by seeding the loop from
`camTarget ?: myLocation` at `MapScreen.kt:1280`.

### 7.4 Bearing-hold rule — same rule, four spellings

`MapScreen.kt:1239` `if (fix.bearingDeg != null && fix.speedMps > 2.0)`;
`MapScreen.kt:1189` `?.takeIf { fix.speedMps > 2.0 }`;
`CarMapRenderer.kt:198` `speedMps > BEARING_HOLD_MPS`;
`NavScreen.kt:234` and `SpinScreen.kt:135` `bearingDeg?.takeIf { speedMps > 2.0 }`.

### 7.5 Circle-fix polling — duplicated loop

`MapScreen.kt:1111-1118` vs `CarMapRenderer.kt:139-150`. Same `while (true)` /
`CircleFixes.othersFixes(username)` on IO / `delay(CIRCLE_FIX_POLL_MS)` /
keep-last-on-failure. The phone catches into `circleFixes` state; the car uses
`runCatching { }.onSuccess { setCircleMembers(it) }`. The phone reads the
username from the collected `accountUsername` (471); the car reads
`Account.username.value` fresh on each iteration (`CarMapRenderer.kt:141`), so
the car picks up a sign-in without restarting the loop and the phone does not
(its effect is keyed on `accountUsername`, so it restarts — different mechanism,
same outcome).

### 7.6 Speed-camera prefetch and warning — duplicated

Prefetch: `MapScreen.kt:1062-1083` vs `NavScreen.kt:378-395`. Same
`SpeedCameras.PREFETCH_RADIUS_M - 1000` edge test and 15 s throttle. The car
additionally guards on `cameraFetchJob?.isActive != true` (`NavScreen.kt:383`)
and fetches in a separate job; the phone fetches inline in the collector.

Warn selection is near-verbatim — `MapScreen.kt:1151-1155` vs
`NavScreen.kt:397-400`, both filtering on `distanceMeters <= SpeedCameras.WARN_METERS`
and `RoadRoulette.withinWedge(pos, cam.at, heading, 45.0)` (the `45.0` is an
unnamed literal on both sides) then `minByOrNull` by distance.

Warn fire is near-verbatim — `MapScreen.kt:1156-1165` vs `NavScreen.kt:401-416`:
same `warnedAt` re-arm sentinel, same `limit + 3.0` tolerance, same
`toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)`.

**Behavioural divergence:** the phone falls back to the ambient limit —
`navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value` (`MapScreen.kt:1160`)
— while the car uses `progress?.speedLimitKmh` only (`NavScreen.kt:405`), so a
car camera warning cannot fire while free-driving. The car adds a spoken warning
and a `CarToast` (`NavScreen.kt:412-414`) that the phone does not have.

### 7.7 Ambient speed-limit snap — duplicated

`MapScreen.kt:1024-1056` vs `SpinScreen.kt:265-296`. Same 2 m/s floor, same
`SPEED_PREFETCH_RADIUS_M - 500` edge test, same 10 s throttle, same
`RoadRoulette.speedLimitWays` → `snapSpeedLimitKmh` pair, same 3-miss
hysteresis. `SpinScreen.kt:48-51` says so in a comment: "same policy as the
phone map's (MapScreen.kt)". Structural difference: the car fetches in its own
`limitFetchJob` (`SpinScreen.kt:100`, `:277-286`); the phone fetches inline
inside the `lastFix` collector (`MapScreen.kt:1037`).

Symmetry note: the phone runs this effect only when **not** navigating
(`MapScreen.kt:1024-1025`); the car runs it only on `SpinScreen`, which is the
not-navigating screen. Same gate, expressed through screen structure instead of
an effect key.

### 7.8 Arrival / reroute policy — duplicated

`NavScreen.kt:242` says outright: "Same arrival/reroute policy as MapScreen.kt's
navigating LaunchedEffect." Compare `MapScreen.kt:1359-1389` with
`NavScreen.kt:243-277`: same arrival test, same off-route + `!rerouting` +
cooldown gate, the same `RoutingServer.route(config, pos, dest, <profile>,
Settings.avoidHighways.value, Settings.avoidSmallRoads.value)` call differing
only in `mode.ghProfile` vs `TravelMode.CAR.ghProfile`, the same
`finally { rerouting = false }`, and the same "stay on the old line; retried
after the cooldown" comment (`MapScreen.kt:1384`, `NavScreen.kt:273`).

The phone's own state (`rerouting` 516, `lastRerouteMs` 517) is mirrored by
`NavScreen.kt:111-112` (`rerouting`, `lastRerouteMs`) — same names.

### 7.9 Overlay push — reduced copy

`CarMapRenderer.pushRoute` (`:361-376`) is `MapScreen.kt:886-901`'s
`overlays.render(...)` call with `reachMeters = null`, `directionDeg = null`,
`candidates = emptyList()`. `NavScreen.kt:235` `renderer.setDrivenFraction(p.drivenFraction)`
mirrors `MapScreen.kt:1355` `mapOverlays?.setDrivenFraction(progress.drivenFraction)`.

### 7.10 What the car has that the phone does not
- Voice guidance: `NavVoice.kt` (150 lines) and `NavScreen.announce` (`:287-313`) with three distance phases (`VOICE_FAR_M`/`VOICE_NEAR_M`/`VOICE_NOW_M`, `NavScreen.kt:64-66`). The phone has no TTS at all.
- `NavigationManager`/`Trip` host integration (`NavScreen.kt:104`, `:156-166`, `:323-347`) and a template-invalidate throttle (`refreshTemplate` `:352-363`).
- A canvas-drawn HUD (`CarMapRenderer.HudOverlay` `:506-663`) rather than a Compose `SpeedHud`. It dedupes on the rounded string (`:530`) instead of easing — the car has no `SPEED_TAU`/`SPEED_EPS_KMH` equivalent.
- `VirtualDisplay`/`Presentation` surface plumbing (`CarMapRenderer.kt:264-336`, `:443-464`).

### 7.11 What the phone has that the car does not

These MapScreen concerns have **zero** counterpart under `car/`:

| Phone feature | Phone lines | Car status |
|---|---|---|
| Fog of war (`FogView`, `TraceStore`, `FriendFog`) | 470, 479–488, 590, 593, 921–932, 1128–1132 | absent entirely |
| Trajectcontrole / section average | 283, 288, 290–314, 1174–1230, 538–539, `SectionAverageChip` 3014–3046 | absent. `NavScreen.kt:391` reads only `result.cameras` from `SpeedCameras.near` and discards `result.sections`. |
| Convoy group spin (share / vote / commit) | 322–367, 823, 828–840, 858–870, 1716–1738 | absent. `CarMapRenderer` collects `ConvoyLiveClient.peers` (`:137`) for markers only. |
| Push-to-talk | 2899–2954, 614/623 | absent |
| Round trips (`Curviness`, `RoundTripPlanner`, `CURVY_CANDIDATES`) | 267, 1408–1468 | absent. `SpinScreen.kt:333` hard-codes `TravelMode.CAR` + `PoiKind.ROAD`. |
| 3-candidate parallel spin + `CANDIDATE_COLORS` pins | 319, 1472–1494, 892–894 | `SpinScreen.kt:332` makes **one** `pickCandidate` call; `CarMapRenderer.kt:367` always passes `candidates = emptyList()`. |
| Direction filter (`DIRECTION_NAMES`, wedge) | 210–211, 463, 891, 2500–2505 | absent; `SpinScreen.kt:333` passes `bearing = null`. |
| Min-radius floor | 450, 1471, 2479–2497 | absent; `SpinScreen.kt:332` passes `0.0`. |
| Camera gesture park / auto-resume | 273–274, 669–712 | absent; rotation gestures disabled outright at `CarMapRenderer.kt:310`. |
| Fit-to-bounds (`FIT_PADDING_PX`, `cameraForPoints`) | 259, 814, 839, 1468, 1493 | absent |
| `NavRelay` (Wear) and `BleNavServer` pushes | 978–979, 1341, 1356–1357 | absent — **wear and BLE guidance go dead whenever navigation is driven from the head unit.** |
| Saved places / shortcut chips / save-pin | 438, 440, 1666–1685, 1810–1819 | absent |
| `SpinResultHolder` persistence | 369–414, 453, 467 | absent — the car's spin result dies with the screen |
| Adaptive free-drive zoom | `MapScreen.kt:1240` always calls `NavEngine.cameraZoom` | `SpinScreen.kt:136-137` passes raw `Settings.defaultZoom.value`; only `NavScreen.kt:230` uses `NavEngine.cameraZoom` |

### 7.12 Duplication inside the car package itself
- `SpinScreen.navigate` (`:348-351`) and `SearchScreen.handOff` (`:159-162`) have character-identical bodies (`geo:` URI + `startCarApp(ACTION_NAVIGATE)`).
- `SpinScreen.fetchLocation` (`:298-310`) and `SearchScreen.fetchLocation` (`:164-173`) are the same fused-provider `lastLocation` fetch; `MapScreen.fetchLocation` (`:714-734`) is a third variant that additionally tries `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` first.
- `* 3.6` speed conversion is inline at `NavScreen.kt:224`, `SpinScreen.kt:150`, `MapScreen.kt:1161`, `:1250`, `:1341`, `:1356`, `:1357`.

### 7.13 Summary of the duplication surface

Roughly **200–250 lines** of MapScreen.kt have a near-verbatim twin under
`car/`: the camera ease loop (~40), `smoothBearing` (~7), the camera-warning
pipeline (~25), the ambient speed-limit snap (~30), the arrival/reroute policy
(~35), the circle poll (~10), plus the seven duplicated constants and about
nine more that the car names and the phone inlines. None of it is currently
shareable, because on the phone all of it is welded to Compose state
(`MapScreen.kt:527-539`, `:547-550`) while on the car it is welded to plain
class fields (`CarMapRenderer.kt:185-192`, `NavScreen.kt:108-137`,
`SpinScreen.kt:87-100`).

---

## 8. Hazards — what a refactor could silently break

### H1. Effect declaration order is load-bearing in three places
- `fetchLocation` (714) must be declared before `onLocationGranted` (760), which must be declared before `permissionLauncher` (772), which must be declared before the permission sweep effect (782) — Kotlin local functions and `remember`ed launchers are only in scope after their declaration.
- `commitSpinCandidate` (828) must precede the group-spin resolution effect (858) that calls it.
- `displayCandidates` (823) must precede `candidatesRef` (937) and the overlay effect (874).
- `convoyPeers` was deliberately hoisted from the marker section to 497 so the commit rule at 858 could see it (comment 494–496). Moving it back down silently breaks the commit rule.

### H2. `rememberUpdatedState` refs read inside once-registered callbacks
`candidatesRef` (937), `spinOfferRef` (938), `navigatingRef` (939) exist because the map listeners in the `LaunchedEffect(mapLibreMap)` at 940 are registered **once** and then live for the map's lifetime. Reading `displayCandidates`/`spinOffer`/`navigating` directly inside those lambdas would capture the composition-time values forever. The same pattern guards the camera-warning effect (`speedCamerasRef` 1138, `ambientLimitRef` 1139, `navProgressRef` 1140), the section effect (`speedSectionsRef` 1173) and the speed frame loop (`speedTarget` 1250) — all of which are `LaunchedEffect(Unit)` and never restart.

Corollary hazard: the map listeners are **never removed**. There is no
`removeOnMapClickListener`/`removeOnCameraMoveListener` anywhere. If
`LaunchedEffect(mapLibreMap)` ever re-runs with a non-null map (it currently
cannot, because `mapLibreMap` is only written once at 640), listeners would
stack.

### H3. Effects that intentionally read state they are not keyed on
- `LaunchedEffect(liveFix, defaultZoom)` at 1236 reads `navProgress?.distanceToTurnMeters` (1243). A `navProgress` change alone does **not** recompute the zoom target; it only takes effect on the next fix. Adding `navProgress` as a key would change camera behaviour.
- `LaunchedEffect(camSuspended, spinning, candidates.isEmpty(), spinOffer == null)` at 700 keys on **booleans derived** from `candidates` and `spinOffer`, not the values themselves, so a candidate-list change that keeps emptiness constant does not restart the collector. Keying on the collections directly would restart the `lastFix` collector on every convoy vote.
- The nav loop `LaunchedEffect(navigating, liveFix, route)` at 1345 writes `route` (1379) from a `scope.launch`, i.e. it re-keys itself asynchronously. The `scope.launch` is deliberate (comment 1367–1369): a `LaunchedEffect`-scoped request would be cancelled by the next GPS fix.

### H4. Coroutine-local mutable state that is *not* Compose state
Five effects hold their entire working state in local `var`s inside the coroutine, so **any re-key silently resets them**:
- 1063–1064: `center`, `lastFetchMs` (camera prefetch) — `LaunchedEffect(Unit)`, safe today.
- 1146: `warnedAt` (chime re-arm) — `LaunchedEffect(Unit)`.
- 1175–1179: `active`, `exitGate`, `entryMs`, `accMeters`, `last` (trajectcontrole measurement) — `LaunchedEffect(Unit)`. Re-keying this effect abandons an in-progress section measurement mid-drive.
- 1252, 1289–1293: `lastNs`, `appliedLat/Lon/Zoom/Bearing` (camera de-dup). `appliedLat` starts `Double.NaN` as the "never pushed" sentinel (1289, tested at 1320).
- 1281–1284: `lat`, `lon`, `bearing`, `zoom` — the camera loop's actual position. Re-keying restarts the ease from the current `camTarget`/map zoom, which is visible as a jump.

Contrast: the ambient speed-limit effect (1024) *does* use Compose state (`speedLimitWays`, `speedLimitWaysCenter`, `speedLimitFetchMs`, `speedLimitMisses`) for the same job, so its prefetch survives the `navigating` re-key. The two adjacent effects use opposite strategies.

### H5. `SpinResultHolder` is process-scoped, not composition-scoped
`MapScreen.kt:383`–`385`. It is read once at composition (453) into `savedSpin`
and written back by the effect at 467 on every change to the 4-tuple. Consequences:
- Two simultaneous `MapScreen` compositions (split-screen, or a test) share it.
- `seedRouteNavigation` (400) writes it from **outside** any composition; MapScreen only picks that up on the **next** composition — RoutesScreen must navigate away and back for it to take.
- The write-back effect (467) fires on first composition too, immediately rewriting the holder with the values it just seeded from. Harmless today because the values are identical, but only because `savedSpin` is read before any of the four are mutated.
- It is **not** `rememberSaveable` — it survives activity recreation but not process death, unlike `radiusKm`/`minRadiusKm`/`poiKind`/`directionDeg`/`settingsCollapsed` (449, 450, 462, 463, 526), which are.

### H6. Lifecycle-tied side effects with cleanup obligations
- `DisposableEffect(lifecycleOwner)` 602–625: `TripTrackingService.setUiVisible(context, false)` and `PushToTalk.stopTalking()` run **both** on ON_STOP and on dispose. Dropping the dispose branch leaves a 1 Hz GPS request open or a hot mic. The comment at 606–611 is explicit that the ON_STOP `stopTalking()` is belt-and-braces for a press interrupted by backgrounding.
- `DisposableEffect(Unit)` 629–648: MapView `onCreate/onStart/onResume` are called unconditionally and `onPause/onStop/onDestroy` on dispose, and `fogView.map = null` **before** the teardown (643) — order matters, `FogView` holds a map reference for projection.
- `DisposableEffect(Unit)` 1144: `toneGen?.release()`. The `ToneGenerator` is constructed in a `runCatching` at 1141–1143 and may be null.
- `DisposableEffect(mapView)` 669–694: the touch listener must be nulled on dispose (693), and **must return `false`** (691) or MapView stops receiving gestures entirely.

### H7. Closures capturing mutable state from non-Compose callbacks
- The `OnTouchListener` at 673–692 writes `camSuspended` and `lastGestureMs` from an Android View callback, via the nested `park()` (674–677) and the `ACTION_UP` branch (689). It also holds `downX`/`downY` as `DisposableEffect`-scoped vars (671–672).
- `map.getMapAsync { map -> … mapLibreMap = map }` (633–641) writes Compose state from a MapLibre callback that may fire after the effect's scope has been cancelled.
- `map.setStyle(…) { style -> mapOverlays = MapOverlays(...); fogView.map = map; mapView.addView(fogView, …) }` (654–662) does the same and additionally mutates the View hierarchy from inside a style callback.
- The `permissionLauncher` result callback (774–780) calls `onLocationGranted()`, which calls `fetchLocation()`, which launches on `scope`.

### H8. Duplicated inline logic that must stay in step
- `inAppAvailable` is computed identically at 1748–1750 and 1777–1779.
- The `onNavigate` lambda `if (stats == null) TripTrackingService.start(context, destination?.lat, destination?.lon)` appears at 1755–1757 and 1784–1786, and unguarded at 1789.
- `choose` (803–815) and `commitSpinCandidate` (828–840) are the same 6 assignments plus a camera fit, differing only in source and `route = null`/`clearSpinOffer()`.
- The "park the camera and buy a grace period" pair (`camSuspended = true; lastGestureMs = System.currentTimeMillis()`) appears at 810/813, 837/838, 1677/1679, 1829/1830 — and **`spin()` at 1403 sets only `camSuspended`, not `lastGestureMs`**. That asymmetry is either a latent bug or deliberate; either way a refactor that "unifies" them changes behaviour.
- `leadingSpinIndex(spinVotes, offer.candidates.size)` is called from the auto-commit effect (868) and from the `onGoWithLead` button (1736). Both must agree.

### H9. `error` (460) has 12 writers and exactly 1 reader
`SpinSheet(error = error)` at 1771. Errors written by `fetchLocation` (728, 731),
the permission callback (778) and `startNavigation` (984, 991, 998, 1011) are
**not shown** unless the spin sheet happens to be expanded (`settingsCollapsed ==
false`, 1692). Any refactor that "fixes" this by surfacing errors elsewhere is a
behaviour change, not a refactor.

### H10. `myLocation` has two writers with different quality gates
The `liveFix` effect (555–559) accepts a fix only when `accuracyMeters <= 100f`.
`fetchLocation` (724) writes whatever FusedLocationProvider returns, including
`lastLocation` (722), with no accuracy gate at all. Unifying the two paths
changes what `spin`, `startNavigation` and the fog corridor see.

### H11. Six concurrent collectors on one StateFlow
`TripTrackingService.lastFix` is collected directly at 704, 1026, 1065, 1147,
1180 and via `collectAsStateWithLifecycle` at 487. Each `.collect` is inside a
`LaunchedEffect` with different keys, so they start and stop independently.
Merging them into one consumer would serialise work that currently runs
concurrently and would couple their re-key conditions.

### H12. `camTargetBearing` is written by `stopNavigation`
`MapScreen.kt:973` sets it to `null`, which both stops the camera rotating
(1305) **and** clears the overlay's position-marker bearing (900) — the vehicle
icon snaps to north-up when navigation ends. Extracting nav and camera into
separate units must preserve this cross-write.

### H13. `mapLibreMap`/`mapOverlays` nullability is the async-arrival contract
Both start null (594, 595) and are set from SDK callbacks. Eleven call sites use
`?.` or an early `?: return@LaunchedEffect` (653, 876, 941, 1272). Any extraction
that assumes non-null at construction will NPE on a cold start.

### H14. `serverConfig` is a composition-time snapshot
`remember { RoutingServer.load() }` at 461, never re-read. Changing the routing
server in SettingsScreen does not affect an already-composed MapScreen. Eight
sites depend on it (1006, 1380, 1414, 1420, 1478, 1500, 1748, 1777).

### H15. `spinJob` cancellation semantics
`spin()` assigns `scope.launch{}` to `spinJob` (1398); the dock/sheet spin button
cancels it when `spinning` (1751, 1780). The body rethrows `CancellationException`
at 1436, 1442 and 1505 specifically so `finally { spinning = false }` (1509–1511)
still runs. `withTimeout(30_000)` at 1472 throws `TimeoutCancellationException`,
which is caught *before* the `CancellationException` branch (1496 vs. 1505) —
catch-clause order is load-bearing.

### H16. Two frame loops run for the life of the composition
`LaunchedEffect(Unit)` at 1251 (speed) and `LaunchedEffect(cameraActive, haveFix,
mapLibreMap)` at 1271 (camera) both `while(true) { withFrameNanos { … } }`. They
only cost anything while the activity is resumed (comment 1266–1267). The camera
loop's `!cameraActive` early-return (1273–1279) performs a **one-shot** level-to-
north-up before returning — that side effect happens on every transition to
inactive, not only on the first.
