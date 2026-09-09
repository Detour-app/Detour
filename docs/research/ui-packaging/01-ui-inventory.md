# UI Directory Factual Inventory

## Header

- **Scope A:** `app/src/main/java/com/jellemax/detour/ui/` — 59 `.kt` files, completely flat (no subdirectories), 18,033 total lines (`wc -l *.kt` sum, verified 2026-09-09).
- **Scope B (glanced):** `app/src/test/java/com/jellemax/detour/ui/` — 6 `.kt` files.
- **Date:** 2026-09-09.
- **Commit:** `efa6c9ad` (`git rev-parse --short HEAD`).
- **Repo root:** `/home/andre/Projects/Detour`.

### Method for call-site counts (re-runnable)

This is a factual inventory, not a design proposal. Every "shared vs screen-local" classification below is derived mechanically, not judged by eye:

1. Enumerate every non-private top-level declaration in each file with:
   `grep -nE '^(fun |private fun |internal fun |class |object |data class |sealed class |enum class )' app/src/main/java/com/jellemax/detour/ui/*.kt`
   (private declarations are excluded from cross-file candidacy since the compiler cannot let another file call them.)
2. For each candidate name, search actual invocation syntax across the whole app module — not just a name mention — with:
   `grep -rnE "([^a-zA-Z0-9_.]|^)<Name>[[:space:]]*[({]" app/src/main/java`
   This catches both `Name(` and Compose's parens-free trailing-lambda form `Name {`.
3. **Extension functions/properties need a second pass** because their call sites are dot-prefixed (`modifier.glassBorder(...)`), which the character class above deliberately excludes to avoid false positives on qualified calls of unrelated classes. For every extension (`Modifier.glassBorder`, `ColumnScope.HomeSheet`, `BoxScope.MapBottomSlot`, `TravelMode.icon`, `GroupSpin.asRouteCandidates`, `List<RouteCandidate>.asSpinCandidates`), re-grep with `grep -rn "<name>(" app/src/main/java` (or, for the property, `grep -rn "\.icon\b"`) and manually confirm each hit is a real call, not a KDoc `[Name]` cross-reference.
4. Exclude the line(s) inside the file that declares the symbol (the `fun`/`class`/`object`/`val` line itself) from the caller count.
5. Count **distinct files**, not distinct call sites, per name. A name called 6 times from the same file is 1 caller-file, not 6.
6. Where a file has a `private fun` of the **same name** as a public one from another file in the same package, check imports and Kotlin's same-file-priority shadowing rule before crediting a call site to the public symbol (this caught the `SectionLabel` collision documented under Anomalies).
7. `app/src/androidTest/` was also searched for every candidate name; it contributed zero additional call sites for any name in this inventory (confirmed empty result set for all 117 candidate names checked).
8. Route/screen status (as opposed to "just named `*Screen`") was verified against the navigation graph, not inferred from the name: `app/src/main/java/com/jellemax/detour/nav/Destination.kt` (the sealed `Destination` interface) and `app/src/main/java/com/jellemax/detour/MainActivity.kt` (the `entry<Destination.X> { ... }` table, lines ~372-499) were read in full and cross-checked against every `*Screen`-named composable.

All file:line citations below were captured directly from `grep -n`/`sed -n` output during this session against commit `efa6c9ad`; they were not reconstructed from memory.

---

## Full per-file inventory (all 59 files, no elisions)

Each entry: **File (LoC)** — top-level declarations (with defining line numbers) — bucket — evidence.

### 1. AppBar.kt (45)
- `fun SubScreenTopBar` (AppBar.kt:25)
- **Bucket:** SHARED COMPONENT.
- **Evidence:** 12 distinct calling files, all confirmed by direct invocation grep (`SubScreenTopBar[({]`): RoutesScreen.kt:258, SocialScreen.kt:88, HubScreen.kt:107, BadgesScreen.kt:66, SavedPlacesScreen.kt:94, HistoryScreen.kt:222, RouteEditorScreen.kt:309, TripDetailScreen.kt:445, ProfileScreen.kt:71, SettingsScreen.kt:151, FriendsScreen.kt:103, CirclesScreen.kt:134.

### 2. BadgesScreen.kt (234)
- `fun BadgesScreen` (BadgesScreen.kt:54)
- `private fun CoverageSummaryCard` (BadgesScreen.kt:121)
- `private fun SectionLabel` (BadgesScreen.kt:179)
- `private fun BadgeTileCell` (BadgesScreen.kt:195)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.Badges`; MainActivity.kt:406-409 (`entry<Destination.Badges> { BadgesScreen(...) }`). The three private functions are screen-local sub-composables. See Anomalies for the `SectionLabel` name collision.

### 3. CandidatesCard.kt (182)
- `internal fun CandidatesCard` (CandidatesCard.kt:44)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapBottom.kt:199 (`HomeBottomCard.CANDIDATES -> CandidatesCard(`), part of MapScreen's bottom-slot dispatch.

### 4. Cards.kt (75)
- `fun ListCard` (Cards.kt:27)
- `fun CardDivider` (Cards.kt:53)
- `fun SectionLabel` (Cards.kt:66)
- **Bucket:** SHARED COMPONENT file (the busiest generic-component file in the directory).
- **Evidence:**
  - `ListCard`: 12 distinct callers — SocialScreen.kt:131, SettingsScreen.kt:1219, RoutesScreen.kt:415, BadgesScreen.kt:122, SettingsHub.kt:70/89/108/127/142 (5 sites), HubScreen.kt:199/247/349 (3 sites; plus HubScreen.kt:146/181 which are actually the *file's own* `ListCard {` calls inside `YouProfileCard`/`YouGuestCard` — all within HubScreen.kt, still one file), SettingsServers.kt:131, SavedPlacesScreen.kt:123, ProfileScreen.kt:81, SearchIsland.kt:211, FriendsScreen.kt:306/365, CirclesScreen.kt:381/418/562/571/657/697 (6 sites, one file).
  - `CardDivider`: 8 distinct callers — SocialScreen.kt:139, SettingsHub.kt:78/97/116, SearchIsland.kt:228/248, HubScreen.kt:153/160/167, SavedPlacesScreen.kt:134, FriendsScreen.kt:308/367, CirclesScreen.kt:383/420/564/590/659/699, SettingsServers.kt:136/141.
  - `SectionLabel`: 4 distinct callers — SettingsScreen.kt:1218, HubScreen.kt:144, SettingsHub.kt:69/88/107/126, SettingsServers.kt:130. (BadgesScreen.kt:100's call resolves to its own private shadow — see Anomalies — and is excluded from this count.)

### 5. CirclesScreen.kt (896)
- `private fun CirclesScaffold` (CirclesScreen.kt:109)
- `fun CirclesScreen` (CirclesScreen.kt:190)
- `fun CircleDetailScreen` (CirclesScreen.kt:278)
- `private fun CircleListSection` (CirclesScreen.kt:362)
- `private fun CircleInviteRow` (CirclesScreen.kt:434)
- `private fun CircleDetailSection` (CirclesScreen.kt:469)
- `private fun BatteryOptimizationDialog` (CirclesScreen.kt:755)
- `private fun CircleMemberListRow` (CirclesScreen.kt:781)
- `private fun CreateCircleDialog` (CirclesScreen.kt:800)
- `private fun InviteToCircleDialog` (CirclesScreen.kt:823)
- `private fun SharePlaceDialog` (CirclesScreen.kt:847)
- **Bucket:** SCREEN (two routes in one file).
- **Evidence:** `CirclesScreen` routed at `Destination.Circles`, `CircleDetailScreen` routed at `Destination.CircleDetail` — MainActivity.kt:442-449. Seven private dialogs/rows are screen-local. Over the 500 LoC soft threshold.

### 6. ConfirmDialog.kt (39)
- `internal fun ConfirmDialog` (ConfirmDialog.kt:21)
- **Bucket:** SHARED COMPONENT.
- **Evidence:** 9 distinct callers — Obd2PairingScreen.kt:241, RoutesScreen.kt:369, SettingsScreen.kt:736/997, HistoryScreen.kt:475, ProfileScreen.kt:139, SavedPlacesScreen.kt:159, CirclesScreen.kt:394/721/732, FriendsScreen.kt:319/384, SettingsServers.kt:268/453.

### 7. CoverageMapScreen.kt (431)
- `fun CoverageMapScreen` (CoverageMapScreen.kt:90)
- `private fun SelectedMunicipalityPill` (CoverageMapScreen.kt:253)
- `private fun SelectedMunicipalityCard` (CoverageMapScreen.kt:272)
- `private fun CoverageBottomSheet` (CoverageMapScreen.kt:296)
- `private fun coverageFillExpression` (CoverageMapScreen.kt:357)
- `private fun buildFeatureCollection` (CoverageMapScreen.kt:364)
- `private fun Municipality.toGeometry` (CoverageMapScreen.kt:390)
- `private fun ringContains` (CoverageMapScreen.kt:407)
- `private fun ringArea` (CoverageMapScreen.kt:423)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.CoverageMap`, MainActivity.kt:412-413. Geometry helpers (`ringContains`/`ringArea`/`buildFeatureCollection`/`toGeometry`) are private screen-local utilities, not shared.

### 8. DisabledFeature.kt (48)
- `fun DisabledFeatureNotice` (DisabledFeature.kt:25)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, FriendsScreen.kt:702 (`DisabledFeatureNotice(Features.liveRelayReason)`), despite the generic name suggesting reuse.

### 9. DriveClockLocal.kt (52)
- `val LocalDriveClock` (DriveClockLocal.kt:52)
- **Bucket:** STATE HOLDER (CompositionLocal).
- **Evidence:** A `staticCompositionLocalOf<DriveClock>` wired to the process-scoped `DriveClocks.current`. The file's own KDoc (lines 16-51) documents that `MapCameraLoops`, `MapPositionMarker`, `MapNavigationSession`, `MapHazardAlerts`, and `rememberActiveTripCardState` all read it via the CompositionLocal rather than a parameter, to keep those composables' signatures under a 7-parameter guideline. No `CompositionLocalProvider` call exists anywhere for it (confirmed absent by grep) — the default value is the only wiring, by design per its own comment.

### 10. FogView.kt (614)
- `class FogView(context: Context) : View(context)` (FogView.kt:43)
- **Bucket:** OTHER (custom Android `View`, not a Compose file).
- **Evidence:** Not a composable at all — a canvas-drawn fog-of-war overlay `View` held/referenced by MapScreen.kt, MapCircleMembers.kt, and RetainedMap.kt. Also referenced by `perf/PerfLog.kt`.

### 11. Format.kt (69)
- `fun formatDuration` (Format.kt:17)
- `fun formatDurationHistory` (Format.kt:28)
- `fun formatSpeedKmh` (Format.kt:30)
- `fun formatDistanceKm` (Format.kt:42)
- `fun formatDate` (Format.kt:51)
- `fun formatTimeOfDay` (Format.kt:55)
- `fun formatLeanAngle` (Format.kt:57)
- `fun formatGForce` (Format.kt:61)
- `fun formatFuelPer100Km` (Format.kt:68)
- `private val tripDateFormat` (Format.kt:49), `private val timeOfDayFormat` (Format.kt:53)
- **Bucket:** UTILITY (pure formatters).
- **Evidence:** Callers span HistoryScreen.kt, TripCardRenderer.kt, TripDetailScreen.kt, NavigationDock.kt, RouteEditorScreen.kt, RideSheet.kt, SpinCards.kt, and `car/SpinScreen.kt` (the Android Auto surface, for `formatDistanceKm`). Directly unit-tested by `FormatTest.kt` (see Part D).

### 12. FriendsScreen.kt (883)
- `fun FriendsScreen` (FriendsScreen.kt:87)
- `private fun SignInSection` (FriendsScreen.kt:171)
- `private fun FriendsSection` (FriendsScreen.kt:256)
- `private fun RequestRow` (FriendsScreen.kt:396)
- `private fun DeclinedRow` (FriendsScreen.kt:428)
- `private fun LeaderboardRowItem` (FriendsScreen.kt:455)
- `private fun FamilyButton` (FriendsScreen.kt:544)
- `private fun AddFriendDialog` (FriendsScreen.kt:573)
- `private fun ConvoysSection` (FriendsScreen.kt:637)
- `private fun ConvoyRow` (FriendsScreen.kt:763)
- `private fun CreateConvoyDialog` (FriendsScreen.kt:842)
- `private fun InviteToConvoyDialog` (FriendsScreen.kt:865)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.Friends`, MainActivity.kt:441. 10 private screen-local sub-composables (rows, dialogs, sections). Over the 500 LoC soft threshold.

### 13. GlassSurface.kt (44)
- `fun glassCardColors` (GlassSurface.kt:26)
- `fun glassContainerColor` (GlassSurface.kt:34)
- `fun Modifier.glassBorder` (GlassSurface.kt:40)
- **Bucket:** SHARED COMPONENT (visual-style helper file), the second-most reused file in the directory.
- **Evidence:**
  - `glassCardColors()`: 11 distinct callers — CandidatesCard.kt:70, MapHud.kt:131, Navigation.kt:152, CoverageMapScreen.kt:257/279, RideSheet.kt:254, SpinCards.kt:136, RiderCard.kt:42, NavigationDock.kt:67, TripDetailScreen.kt:502, SearchIsland.kt:213, MapChrome.kt:114.
  - `glassContainerColor()`: 3 distinct callers — CoverageMapScreen.kt:217, SearchIsland.kt:256, MapChrome.kt:150/188.
  - `Modifier.glassBorder()` (extension — required the dot-prefixed re-grep): 11 distinct callers — Navigation.kt:150, CandidatesCard.kt:68, CoverageMapScreen.kt:218/255/277, RideSheet.kt:248, MapHud.kt:127, RiderCard.kt:40, TripDetailScreen.kt:500, NavigationDock.kt:65, SearchIsland.kt:254, SpinCards.kt:134, MapChrome.kt:112/148/186.

### 14. GraphiteTheme.kt (88)
- `val GraphiteDark` (GraphiteTheme.kt:15)
- `val GraphiteLight` (GraphiteTheme.kt:53)
- **Bucket:** THEME/DESIGN TOKENS.
- **Evidence:** Both are `ColorScheme` (Material3) constants; consumed only by MainActivity.kt (2 references), which builds the top-level `MaterialTheme`. Not consumed by any other ui/ file.

### 15. HistoryScreen.kt (586)
- `private fun readTraceSegments` (HistoryScreen.kt:85)
- `internal fun matchTripPoints` (HistoryScreen.kt:132)
- `private fun matchThumbnails` (HistoryScreen.kt:149)
- `fun loadTripTrace` (HistoryScreen.kt:171)
- `fun loadTripPoints` (HistoryScreen.kt:177)
- `private fun monthKey` (HistoryScreen.kt:181)
- `fun HistoryScreen` (HistoryScreen.kt:185)
- `private fun NoTripsYet` (HistoryScreen.kt:311)
- `private fun HistoryLoadFailed` (HistoryScreen.kt:335)
- `private fun TripCard` (HistoryScreen.kt:356)
- `fun tripStatLine` (HistoryScreen.kt:502)
- `fun tripBehaviorLine` (HistoryScreen.kt:518)
- `fun tripFuelEconomyLper100Km` (HistoryScreen.kt:539)
- `private fun TraceThumbnail` (HistoryScreen.kt:555)
- `private val monthFormat` (HistoryScreen.kt:180)
- **Bucket:** SCREEN, but see Anomalies — this file also functions as a shared trip-formatting UTILITY module.
- **Evidence:** Routed at `Destination.History`, MainActivity.kt:395-401. `tripStatLine`/`tripBehaviorLine`/`tripFuelEconomyLper100Km`/`loadTripTrace`/`loadTripPoints`/`matchTripPoints` are all called from **outside** this file, by TripDetailScreen.kt (confirmed: `tripStatLine`/`tripBehaviorLine`/`tripFuelEconomyLper100Km` each called from TripDetailScreen.kt; `loadTripTrace`/`loadTripPoints` likewise; `matchTripPoints` is HistoryScreen-internal only — 1 caller, itself). Also directly unit-tested (`TripStatLineTest.kt`, `TripTraceMatchingTest.kt` — Part D). Over the 500 LoC soft threshold.

### 16. HomeSheet.kt (422)
- `internal fun ColumnScope.HomeSheet` (HomeSheet.kt:120)
- `internal fun DragHandle` (HomeSheet.kt:210)
- `private fun ShortcutChipRow` (HomeSheet.kt:249)
- `private fun PlaceChip` (HomeSheet.kt:366)
- `private fun DestinationCard` (HomeSheet.kt:392)
- `internal val HOME_SHEET_HEIGHT` (HomeSheet.kt:74), `internal val HOME_SHEET_FONT_SCALE_GROWTH` (HomeSheet.kt:90), `private val PLACE_CHIP_MAX_WIDTH` (HomeSheet.kt:352)
- **Bucket:** SCREEN-LOCAL COMPONENT (`HomeSheet` itself); `DragHandle` is a narrow SHARED COMPONENT.
- **Evidence:** `HomeSheet` (the `ColumnScope` extension): single caller, MapBottom.kt:258. `DragHandle`: called from HomeSheet.kt:156 (self) and RideSheet.kt:111/183 — 2 distinct files, both still internal to the MapScreen bottom-sheet system.

### 17. HubScreen.kt (486)
- `fun HubScreen` (HubScreen.kt:81)
- `private fun YouProfileCard` (HubScreen.kt:196)
- `private fun YouGuestCard` (HubScreen.kt:241)
- `private fun YouStatsRow` (HubScreen.kt:348)
- `private fun StatCell` (HubScreen.kt:378)
- `fun HubRow` (HubScreen.kt:407)
- **Bucket:** SCREEN (`HubScreen`); `HubRow` is SHARED COMPONENT.
- **Evidence:** `HubScreen` routed at `Destination.Hub`, MainActivity.kt:383-384. `HubRow`: 4 distinct external callers — SocialScreen.kt:132/140, SettingsHub.kt:71/79/90/98/109/117/128/143 (8 sites), SettingsServers.kt:132/137/142, CirclesScreen.kt:421 — plus 5 in-file uses (HubScreen.kt:147/154/161/168/182).

### 18. MapBottom.kt (275)
- `internal fun BoxScope.MapBottomSlot` (MapBottom.kt:56)
- **Bucket:** SCREEN-LOCAL COMPONENT / bottom-slot router.
- **Evidence:** Single caller, MapScreen.kt:1206. Internally it `when`-dispatches on a `HomeBottomCard` enum to `CandidatesCard` (line 199), `DriveSheet` (179), `NavSheet` (170), `NavigationDock` (193), `SpinSheet` (223), and `HomeSheet` (258) — i.e., it is the composition-level router for MapScreen's single bottom slot, not a general-purpose component.

### 19. MapCamera.kt (456)
- `internal fun MapSpeedEase` (MapCamera.kt:52)
- `internal fun MapCameraLoops` (MapCamera.kt:78)
- `internal fun MapPositionMarker` (MapCamera.kt:259)
- `internal fun levelToNorthUp` (MapCamera.kt:452)
- **Bucket:** SCREEN-LOCAL COMPONENT/logic.
- **Evidence:** `MapSpeedEase` single caller MapScreen.kt:900; `MapCameraLoops` single caller MapScreen.kt:902; `MapPositionMarker` single caller MapScreen.kt:903; `levelToNorthUp` called from MapCamera.kt itself and MapScreen.kt.

### 20. MapCameraTuning.kt (125)
- `internal fun driveFrameDelta` (MapCameraTuning.kt:123)
- **Bucket:** UTILITY (pure math/tuning).
- **Evidence:** Called from MapCamera.kt and from `car/CarMapRenderer.kt` (the Android Auto surface) — a cross-surface pure function, not cross-screen within ui/.

### 21. MapChrome.kt (200)
- `internal fun MapTopChrome` (MapChrome.kt:52)
- `private fun ConvoyPill` (MapChrome.kt:146)
- `private fun GlassRailButton` (MapChrome.kt:177)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** `MapTopChrome`'s only caller is MapScreen.kt:1156. `ConvoyPill`/`GlassRailButton` are private, file-internal.

### 22. MapCircleMembers.kt (62)
- `internal fun MapCircleMemberMarkers` (MapCircleMembers.kt:27)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapScreen.kt:862.

### 23. MapDialogs.kt (118)
- `internal fun BackgroundLocationDisclosure` (MapDialogs.kt:28)
- `internal fun SavePinDialog` (MapDialogs.kt:57)
- `internal fun MapScreenDialogs` (MapDialogs.kt:92)
- **Bucket:** Mixed — `MapScreenDialogs` is SCREEN-LOCAL COMPONENT; `SavePinDialog` is file-internal only; `BackgroundLocationDisclosure` is SHARED COMPONENT.
- **Evidence:** `MapScreenDialogs`'s only caller is MapScreen.kt:1351. `SavePinDialog` is called only from within this file, MapDialogs.kt:109 (inside `MapScreenDialogs`) — zero external callers. `BackgroundLocationDisclosure` is called from MapDialogs.kt:97 (internally) **and** SettingsScreen.kt:393 — 2 distinct files, a genuine cross-screen share (map's first-run disclosure reused by Settings' tracking section).

### 24. MapHazardAlerts.kt (141)
- `internal fun MapHazardAlerts` (MapHazardAlerts.kt:34)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapScreen.kt:871 (`MapHazardAlerts(s = s, retained = retained, announceAloud = { announceAloud(it) })`).

### 25. MapHazardPrefetch.kt (184)
- `internal fun MapHazardPrefetch` (MapHazardPrefetch.kt:37)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapScreen.kt:839.

### 26. MapHud.kt (219)
- `internal fun PushToTalkButton` (MapHud.kt:48)
- `internal fun SpeedHud` (MapHud.kt:121)
- `internal fun Obd2SignalLostLabel` (MapHud.kt:211)
- `private val ISLAND_WIDTH` (MapHud.kt:108)
- **Bucket:** SCREEN-LOCAL COMPONENT (HUD cluster).
- **Evidence:** All three composables have exactly one caller, MapScreen.kt (`PushToTalkButton` at line 1203, `SpeedHud` at line 1125, `Obd2SignalLostLabel` at line 1142). Checked `car/CarMapRenderer.kt` specifically for `SpeedHud`: the only hit is a comment, `CarMapRenderer.kt:915: // order as the phone's SpeedHud, where the chip is leftmost.` — not a call.

### 27. MapLibreMap.kt (622)
- `fun openFreeMapStyleUrl` (MapLibreMap.kt:41)
- `private val GLYPH_FONT` (MapLibreMap.kt:79)
- `data class NamedFriendPosition` (MapLibreMap.kt:115)
- `class MapOverlays` (MapLibreMap.kt:123)
- `data class CandidatePin` (MapLibreMap.kt:536)
- `enum class PositionMarker` (MapLibreMap.kt:547)
- `fun mapIconDrawable` (MapLibreMap.kt:561)
- `fun mapIconLabel` (MapLibreMap.kt:571)
- `private fun circle` (MapLibreMap.kt:581)
- `private fun wedge` (MapLibreMap.kt:587)
- `private fun offset` (MapLibreMap.kt:594)
- `fun cameraForPoints` (MapLibreMap.kt:605)
- `fun setCamera` (MapLibreMap.kt:619)
- **Bucket:** OTHER (map rendering infrastructure — the foundation the rest of the map feature is built on).
- **Evidence:** `MapOverlays` is instantiated in `car/CarMapRenderer.kt:405` (`val fresh = MapOverlays(style, carContext, darkTheme)`), imported at `CarMapRenderer.kt:40` — genuinely shared across the phone MapScreen and the Android Auto surface, not merely across ui/ files. `openFreeMapStyleUrl` and `setCamera` are also called from `car/CarMapRenderer.kt`. `CandidatePin`/`PositionMarker`/`NamedFriendPosition` are used by MapScreen.kt, RouteEditorScreen.kt, TripDetailScreen.kt, and `car/CarMapRenderer.kt`.

### 28. MapNavigation.kt (141)
- `internal fun MapNavigationSession` (MapNavigation.kt:45)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapScreen.kt:906.

### 29. MapPermissions.kt (105)
- `internal fun rememberMapPermissions` (MapPermissions.kt:36)
- **Bucket:** STATE HOLDER (remember factory), screen-local.
- **Evidence:** Single caller, MapScreen.kt:503 (`val bgLocationLauncher = rememberMapPermissions(`).

### 30. MapScreen.kt (1353)
- `fun MapScreen` (MapScreen.kt:144)
- **Bucket:** SCREEN.
- **Evidence:** The app's start destination; routed at `Destination.Map`, MainActivity.kt:372-373 (`entry<Destination.Map> { MapScreen(...) }`). Over both the 500 and 1000 LoC thresholds — the single largest file in the directory.

### 31. MapScreenState.kt (116)
- `internal data class MapLayers` (MapScreenState.kt:55)
- `internal class MapScreenState` (MapScreenState.kt:62)
- **Bucket:** STATE HOLDER.
- **Evidence:** `MapScreenState` is `remember`ed once per MapScreen composition (per its own KDoc, lines 16-44) and threaded as a parameter through MapCamera.kt, MapCircleMembers.kt, MapDialogs.kt, MapHazardAlerts.kt, MapHazardPrefetch.kt, MapNavigation.kt, MapPermissions.kt — 7 other files take it as a parameter, confirmed by the earlier broad grep (10 files reference the type name total, including its own file and MapScreen.kt). `MapLayers` is used by MapChrome.kt and MapScreen.kt. The file's own KDoc states the composition tree references 56 of `MapScreenState`'s locals directly.

### 32. NavAppLaunch.kt (248)
- `private fun launchNav` (NavAppLaunch.kt:44)
- `private fun navAppUsableDirectly` (NavAppLaunch.kt:72)
- `private fun handleGoTap` (NavAppLaunch.kt:87)
- `private fun NavMenuItems` (NavAppLaunch.kt:110)
- `internal fun NavButton` (NavAppLaunch.kt:155)
- `private fun navigateRoundTrip` (NavAppLaunch.kt:205)
- `private fun navigateGoogleMaps` (NavAppLaunch.kt:219)
- `private fun navigateWaze` (NavAppLaunch.kt:229)
- `private fun navigateGeo` (NavAppLaunch.kt:243)
- **Bucket:** Mixed — `NavButton` is SHARED COMPONENT; the rest are private UTILITY (intent-launching helpers).
- **Evidence:** `NavButton`: 3 distinct callers — SpinCards.kt:342, RideSheet.kt:146, NavigationDock.kt:153. `launchNav`/`navigate*`/`handleGoTap`/`navAppUsableDirectly`/`NavMenuItems` are all `private`, used only within this file.

### 33. NavigationDock.kt (166)
- `internal fun NavigationDock` (NavigationDock.kt:57)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapBottom.kt:193 (`HomeBottomCard.DESTINATION -> NavigationDock(`). Internally composes `ResultCallout` (line 105), `ChoiceRow` (117), `NavButton` (153) — all shared components consumed elsewhere too.

### 34. Navigation.kt (266)
- `private fun signIcon` (Navigation.kt:58)
- `private fun ManeuverGlyph` (Navigation.kt:76)
- `private fun RoundaboutGlyph` (Navigation.kt:101)
- `fun NavigationBanner` (Navigation.kt:148)
- `private fun ThenChip` (Navigation.kt:189)
- `internal fun RouteProgressTrack` (Navigation.kt:214)
- `fun SpeedLimitSign` (Navigation.kt:246)
- **Bucket:** Mixed / holding pen (see Anomalies) — each export is single-caller, but to three different, unrelated files.
- **Evidence:** `NavigationBanner`: only caller MapScreen.kt:1102. `RouteProgressTrack`: only caller RideSheet.kt:182 — note this is defined in Navigation.kt but never called from Navigation.kt itself or from MapScreen.kt. `SpeedLimitSign`: only caller MapHud.kt:201.

### 35. Obd2PairingScreen.kt (331)
- `fun Obd2PairingScreen` (Obd2PairingScreen.kt:47)
- `private fun adjustCalibration` (Obd2PairingScreen.kt:311)
- `internal fun obd2FailureText` (Obd2PairingScreen.kt:324)
- **Bucket:** SCREEN-LOCAL COMPONENT, despite the "Screen" suffix — see Anomalies.
- **Evidence:** Its only caller is SettingsScreen.kt:200, inside `SettingsSpokeScreen`'s `when` block (`Destination.SettingsObd2 -> Obd2PairingScreen()`) — the same role as `NavigationSection()`/`FogSection()`, which are unambiguously private section-content functions in the same `when`. No `Destination` entry routes to it directly.

### 36. Pills.kt (213)
- `internal fun choiceRowMetrics` (Pills.kt:88)
- `private fun ChoiceItem` (Pills.kt:123)
- `internal fun ChoiceRow` (Pills.kt:184)
- **Bucket:** SHARED COMPONENT.
- **Evidence:** `ChoiceRow`: 5 distinct callers — TripCardRenderer.kt:314/320, Obd2PairingScreen.kt:258, SpinCards.kt:192/234/298, NavigationDock.kt:117, SettingsScreen.kt:212/228. `choiceRowMetrics` is pure math, used only within Pills.kt itself, and is directly unit-tested by `ChoiceRowMetricsTest.kt` (Part D).

### 37. ProfileScreen.kt (172)
- `fun ProfileScreen` (ProfileScreen.kt:61)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.Profile`, MainActivity.kt:435-436.

### 38. RetainedMap.kt (268)
- `class RetainedMap(context: Context)` (RetainedMap.kt:61)
- `fun rememberRetainedMap` (RetainedMap.kt:181)
- **Bucket:** STATE HOLDER.
- **Evidence:** Constructed once in MainActivity.kt:217 (`val retainedMap = rememberRetainedMap(darkTheme = isAppDarkTheme(themePref))`) — an Activity-scoped object (per its own naming and MainActivity's single construction site) threaded through MapScreen.kt, MapCamera.kt, MapHazardAlerts.kt, MapHazardPrefetch.kt, MapLibreMap.kt, MapScreenState.kt.

### 39. RiderCard.kt (95)
- `internal fun RiderCard` (RiderCard.kt:34)
- `private fun RiderStat` (RiderCard.kt:82)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, MapScreen.kt:1343 (`RiderCard(state = card, onDismiss = { s.tappedRider = null })`).

### 40. RideSheet.kt (457)
- `internal fun DriveSheet` (RideSheet.kt:100)
- `internal fun NavSheet` (RideSheet.kt:172)
- `private fun RideSheetCard` (RideSheet.kt:239)
- `private fun RideSheetHeader` (RideSheet.kt:265)
- `private fun EndButton` (RideSheet.kt:314)
- `internal fun rememberActiveTripCardState` (RideSheet.kt:350)
- `internal fun TripStatsRows` (RideSheet.kt:383)
- `private fun StatItem` (RideSheet.kt:450)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** `DriveSheet` single caller MapBottom.kt:179; `NavSheet` single caller MapBottom.kt:170. `rememberActiveTripCardState` and `TripStatsRows` are `internal` (not `private`) but are, in fact, called only from within this same file (RideSheet.kt:107/209 and RideSheet.kt:120/209) — non-private visibility with zero external callers. Over the 500 LoC soft threshold.

### 41. RouteEditorScreen.kt (473)
- `fun RouteEditorScreen` (RouteEditorScreen.kt:94)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.RouteEditor`, MainActivity.kt (push sites at lines 485-486; entry registered further down the same `when`).

### 42. RoutesScreen.kt (585)
- `private fun shareRouteGpxIntent` (RoutesScreen.kt:90)
- `private fun navigateStopsExternally` (RoutesScreen.kt:111)
- `fun RoutesScreen` (RoutesScreen.kt:140)
- `private fun RouteCardItem` (RoutesScreen.kt:402)
- `private fun RouteActionPill` (RoutesScreen.kt:462)
- `private fun RouteThumbnail` (RoutesScreen.kt:495)
- `private fun RenameRouteDialog` (RoutesScreen.kt:523)
- `private fun ShareRouteToFriendDialog` (RoutesScreen.kt:547)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.Routes`, MainActivity.kt:482-486. Five private screen-local sub-composables, plus intent-building utility helpers (`shareRouteGpxIntent`, `navigateStopsExternally`). Over the 500 LoC soft threshold. Also calls `seedRouteNavigation` (SpinResultHolder.kt:583 call site) for the two-stop-route case.

### 43. SavedPlacesScreen.kt (414)
- `fun SavedPlacesScreen` (SavedPlacesScreen.kt:66)
- `private fun NoPlacesYet` (SavedPlacesScreen.kt:171)
- `private fun PlaceListRow` (SavedPlacesScreen.kt:202)
- `private fun kindIcon` (SavedPlacesScreen.kt:276)
- `private fun AddPlaceDialog` (SavedPlacesScreen.kt:285)
- `private fun RenameDialog` (SavedPlacesScreen.kt:396)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.SavedPlaces`, MainActivity.kt:481.

### 44. SearchIsland.kt (370)
- `fun SearchIsland` (SearchIsland.kt:103)
- `private val ISLAND_MAX_HEIGHT` (SearchIsland.kt:68)
- **Bucket:** SHARED COMPONENT.
- **Evidence:** 2 distinct callers — HomeSheet.kt:157, RideSheet.kt:125.

### 45. SecureFields.kt (181)
- `internal fun credentialKeyboardOptions` (SecureFields.kt:60)
- `internal fun secretKeyboardOptions` (SecureFields.kt:67)
- `internal fun secretMask` (SecureFields.kt:74)
- `private fun Modifier.autofillPassword` (SecureFields.kt:92)
- `fun SecretTextField` (SecureFields.kt:123)
- `fun CredentialTextField` (SecureFields.kt:164)
- **Bucket:** Mixed — see Anomalies for `SecretTextField`. `CredentialTextField` is SCREEN-LOCAL COMPONENT; the 3 free functions are UTILITY.
- **Evidence:** `SecretTextField`: zero callers found in `app/src/main/java` or `app/src/androidTest` (verified: `grep -rn "SecretTextField" app/src/main/java app/src/androidTest` returns only its own declaration line). `CredentialTextField`: exactly one caller file, SettingsServers.kt, 5 call sites — lines 322, 384, 391, 398, 406. `credentialKeyboardOptions`/`secretKeyboardOptions`/`secretMask` are directly unit-tested by `SecureFieldsTest.kt` (Part D).

### 46. SettingsDiagnostics.kt (113)
- `fun DiagnosticsSection` (SettingsDiagnostics.kt:43)
- `private fun shareTimingsIntent` (SettingsDiagnostics.kt:109)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, SettingsScreen.kt:201.

### 47. SettingsHub.kt (193)
- `fun SettingsScreen` (SettingsHub.kt:45)
- **Bucket:** SCREEN — see Anomalies for the confusing file/symbol name pairing with SettingsScreen.kt.
- **Evidence:** Routed at `Destination.Settings`, MainActivity.kt:451-452 (`entry<Destination.Settings> { SettingsScreen(...) }`).

### 48. SettingsScreen.kt (1228)
- `internal fun spokeTitle` (SettingsScreen.kt:108)
- `internal fun SettingsScaffold` (SettingsScreen.kt:138)
- `fun SettingsSpokeScreen` (SettingsScreen.kt:173)
- `private fun AppearanceSection` (SettingsScreen.kt:209)
- `private fun TrackingSection` (SettingsScreen.kt:252)
- `private fun granted` (SettingsScreen.kt:279)
- `private fun currentBlocker` (SettingsScreen.kt:284)
- `private fun openAppSettings` (SettingsScreen.kt:294)
- `private fun ParkedDormancyNotice` (SettingsScreen.kt:313)
- `private fun navAppLabel` (SettingsScreen.kt:411)
- `private fun NavigationSection` (SettingsScreen.kt:420)
- `private fun MapIconSection` (SettingsScreen.kt:511)
- `private fun RouteColorSection` (SettingsScreen.kt:576)
- `private fun hexColor` (SettingsScreen.kt:648)
- `private fun MapSection` (SettingsScreen.kt:651)
- `private fun FogSection` (SettingsScreen.kt:680)
- `private fun ExternalDisplaySection` (SettingsScreen.kt:753)
- `private fun NowPlayingSection` (SettingsScreen.kt:825)
- `private fun VehicleSection` (SettingsScreen.kt:882)
- `private fun VehicleNameDialog` (SettingsScreen.kt:1089)
- `private fun LeanCalibrationSection` (SettingsScreen.kt:1132)
- `internal fun SettingsSection` (SettingsScreen.kt:1212)
- **Bucket:** SCREEN (`SettingsSpokeScreen`, routed at all 6 `Destination.SettingsXxx` spokes), plus 2 SHARED COMPONENTs exported from the same file.
- **Evidence:** `SettingsSpokeScreen` routed at `Destination.SettingsAppearanceMap`/`SettingsTrackingVehicles`/`SettingsNavigation`/`SettingsFog`/`SettingsDisplaysMedia`/`SettingsObd2` — MainActivity.kt:460-479, each `entry<Destination.SettingsXxx> { key -> SettingsSpokeScreen(key, onBack = ...) }`. `SettingsScaffold` is also called from SettingsHub.kt:68 (1 external file + internal use at SettingsScreen.kt:180). `SettingsSection` is also called from SettingsDiagnostics.kt:49 and SettingsServers.kt:163/314/467/491 (2 external files + 12 internal uses within this file). This is the largest file in the directory besides MapScreen.kt — over both the 500 and 1000 LoC thresholds.

### 49. SettingsServers.kt (537)
- `internal fun ServersSyncSpoke` (SettingsServers.kt:62)
- `private fun ServersStatusCard` (SettingsServers.kt:122)
- `private fun ServersActionsCard` (SettingsServers.kt:156)
- `private fun SyncNowButton` (SettingsServers.kt:174)
- `private fun ConfigFileButtons` (SettingsServers.kt:208)
- `private fun ServerSection` (SettingsServers.kt:288)
- `private fun ServerAdvanced` (SettingsServers.kt:368)
- `private fun RemoveCustomServerButton` (SettingsServers.kt:449)
- `private fun SyncSection` (SettingsServers.kt:466)
- `private fun ConfigFileSection` (SettingsServers.kt:490)
- `private fun LearnMore` (SettingsServers.kt:511)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** `ServersSyncSpoke`'s only caller is SettingsScreen.kt:200 (`ServersSyncSpoke(scrollState)`), itself inside the `SettingsServersSync` branch of `SettingsSpokeScreen`'s `when`. Over the 500 LoC soft threshold.

### 50. SocialScreen.kt (150)
- `fun SocialScreen` (SocialScreen.kt:51)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.Social`, MainActivity.kt:415-416.

### 51. SpinCards.kt (362)
- `internal fun ResultCallout` (SpinCards.kt:63)
- `internal fun SpinSheet` (SpinCards.kt:107)
- `private val DESTINATION_ORANGE` (SpinCards.kt:55)
- **Bucket:** Mixed — `ResultCallout` is SHARED COMPONENT; `SpinSheet` is SCREEN-LOCAL COMPONENT.
- **Evidence:** `ResultCallout`: 2 external callers, RideSheet.kt:141 and NavigationDock.kt:105 (plus an internal use at SpinCards.kt:315). `SpinSheet`: single caller, MapBottom.kt:223 (`HomeBottomCard.EXPANDED -> SpinSheet(`).

### 52. SpinResultHolder.kt (99)
- `internal data class SpinResult` (SpinResultHolder.kt:19)
- `internal object SpinResultHolder` (SpinResultHolder.kt:37)
- `internal fun seedRouteNavigation` (SpinResultHolder.kt:86)
- **Bucket:** STATE HOLDER.
- **Evidence:** A process-scoped `MutableStateFlow<SpinResult>` holder (its own KDoc: kept outside `remember` so it survives Activity recreation). Read/written by MapScreen.kt and MapScreenState.kt (`MapScreenState(seed: SpinResult)` constructor parameter, MapScreenState.kt:62). `seedRouteNavigation` is also called from RoutesScreen.kt.

### 53. SpinShare.kt (48)
- `internal fun GroupSpin.asRouteCandidates` (SpinShare.kt:22)
- `internal fun List<RouteCandidate>.asSpinCandidates` (SpinShare.kt:40)
- **Bucket:** UTILITY (extension-function mappers).
- **Evidence:** Both are extension functions; both are called only from MapScreen.kt (confirmed via extension-aware re-grep).

### 54. Theme.kt (55)
- `fun isAppDarkTheme` (Theme.kt:19)
- `private fun isNightNow` (Theme.kt:32)
- **Bucket:** UTILITY / THEME.
- **Evidence:** 7 distinct external callers — MainActivity.kt, CoverageMapScreen.kt, MapScreen.kt, RouteEditorScreen.kt, SettingsScreen.kt, TripCardRenderer.kt, TripDetailScreen.kt — effectively the app-wide dark-mode predicate.

### 55. TravelModeIcon.kt (13)
- `val TravelMode.icon` (TravelModeIcon.kt:9, extension property)
- **Bucket:** UTILITY.
- **Evidence:** 3 distinct callers (extension-aware re-grep on `\.icon\b`) — HistoryScreen.kt:398, HomeSheet.kt:286, RouteEditorScreen.kt:418.

### 56. TripCardRenderer.kt (771)
- `enum class CardLayout` (TripCardRenderer.kt:96)
- `data class TripCardMapSnapshot` (TripCardRenderer.kt:102)
- `private fun plainAttribution` (TripCardRenderer.kt:104)
- `fun rememberTripCardMapSnapshot` (TripCardRenderer.kt:123)
- `fun rememberTripCardBitmap` (TripCardRenderer.kt:176)
- `fun TripCardShareDialog` (TripCardRenderer.kt:277)
- `private fun TripCardPreview` (TripCardRenderer.kt:367)
- `private fun TripCardContent` (TripCardRenderer.kt:400)
- `private fun StandardCardContent` (TripCardRenderer.kt:444)
- `private fun MinimalCardContent` (TripCardRenderer.kt:484)
- `private fun PosterCardContent` (TripCardRenderer.kt:531)
- `private fun MapSnapshotContent` (TripCardRenderer.kt:576)
- `private fun MapSnapshotRoute` (TripCardRenderer.kt:633)
- `private fun MapSnapshotDestinationDot` (TripCardRenderer.kt:671)
- `private fun CardEyebrow` (TripCardRenderer.kt:693)
- `private fun TrimmedCaption` (TripCardRenderer.kt:711)
- `private fun SecondaryStatsRow` (TripCardRenderer.kt:719)
- `private fun CardFooter` (TripCardRenderer.kt:734)
- `private fun heroStat` (TripCardRenderer.kt:756)
- `private fun secondaryStat` (TripCardRenderer.kt:765)
- `private val MAP_FILTER_LIGHT`/`MAP_FILTER_DARK` (TripCardRenderer.kt:109/112), `private val CARD_BACKGROUND_DARK`/`CARD_BACKGROUND_DARK_BOTTOM`/`CARD_BACKGROUND_LIGHT`/`CARD_BACKGROUND_LIGHT_BOTTOM`/`CARD_TEXT_DARK`/`CARD_TEXT_LIGHT` (TripCardRenderer.kt:392-397)
- **Bucket:** Mixed — `TripCardShareDialog` is SHARED COMPONENT; `rememberTripCardMapSnapshot`/`rememberTripCardBitmap` are internal-only `remember` plumbing; the ~14 private composables are a single-purpose card-image renderer, screen-local to this file.
- **Evidence:** `TripCardShareDialog`: 2 distinct callers — HistoryScreen.kt:491, TripDetailScreen.kt:667. `rememberTripCardMapSnapshot`/`rememberTripCardBitmap`: called only within TripCardRenderer.kt itself (lines 188 and 297). Over the 500 LoC soft threshold.

### 57. TripDetailScreen.kt (669)
- `private fun buildReplayTimeline` (TripDetailScreen.kt:130)
- `private fun sampleReplay` (TripDetailScreen.kt:146)
- `private fun replayMarkerBitmap` (TripDetailScreen.kt:176)
- `private fun shareGpxIntent` (TripDetailScreen.kt:200)
- `fun TripDetailScreen` (TripDetailScreen.kt:214)
- `private val REPLAY_SPEEDS` (TripDetailScreen.kt:106)
- **Bucket:** SCREEN.
- **Evidence:** Routed at `Destination.TripDetail`, MainActivity.kt:403-404. Over the 500 LoC soft threshold. This is the file that consumes HistoryScreen.kt's `tripStatLine`/`tripBehaviorLine`/`tripFuelEconomyLper100Km`/`loadTripTrace`/`loadTripPoints` (see Anomalies).

### 58. UpdateBanner.kt (80)
- `fun UpdateBanner` (UpdateBanner.kt:36)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, HubScreen.kt:134 (`UpdateBanner(status = updateStatus, onOpenSettings = onOpenSettings)`).

### 59. UpdateProgressButton.kt (165)
- `fun UpdateProgressButton` (UpdateProgressButton.kt:70)
- `private fun BoxScope.ProgressFill` (UpdateProgressButton.kt:105)
- `private fun determinateEdge` (UpdateProgressButton.kt:128)
- `private fun sweepWindow` (UpdateProgressButton.kt:141)
- `private fun ButtonLabel` (UpdateProgressButton.kt:155)
- **Bucket:** SCREEN-LOCAL COMPONENT.
- **Evidence:** Single caller, SettingsHub.kt:167.

---

## A) Shared components — call counts and calling filenames

Only symbols with 2 or more distinct external calling files are listed as SHARED here (this is the same set as reported inline above, gathered in one table for reference). Counts are distinct calling files, not call sites.

| Composable/function | Defined at | Call-site count (files) | Calling files |
|---|---|---|---|
| `ListCard` | Cards.kt:27 | 12 | SocialScreen.kt, SettingsScreen.kt, RoutesScreen.kt, BadgesScreen.kt, SettingsHub.kt, HubScreen.kt, SettingsServers.kt, SavedPlacesScreen.kt, ProfileScreen.kt, SearchIsland.kt, FriendsScreen.kt, CirclesScreen.kt |
| `SubScreenTopBar` | AppBar.kt:25 | 12 | RoutesScreen.kt, SocialScreen.kt, HubScreen.kt, BadgesScreen.kt, SavedPlacesScreen.kt, HistoryScreen.kt, RouteEditorScreen.kt, TripDetailScreen.kt, ProfileScreen.kt, SettingsScreen.kt, FriendsScreen.kt, CirclesScreen.kt |
| `glassCardColors()` | GlassSurface.kt:26 | 11 | CandidatesCard.kt, MapHud.kt, Navigation.kt, CoverageMapScreen.kt, RideSheet.kt, SpinCards.kt, RiderCard.kt, NavigationDock.kt, TripDetailScreen.kt, SearchIsland.kt, MapChrome.kt |
| `Modifier.glassBorder()` | GlassSurface.kt:40 | 11 | Navigation.kt, CandidatesCard.kt, CoverageMapScreen.kt, RideSheet.kt, MapHud.kt, RiderCard.kt, TripDetailScreen.kt, NavigationDock.kt, SearchIsland.kt, SpinCards.kt, MapChrome.kt |
| `ConfirmDialog` | ConfirmDialog.kt:21 | 9 | Obd2PairingScreen.kt, RoutesScreen.kt, SettingsScreen.kt, HistoryScreen.kt, ProfileScreen.kt, SavedPlacesScreen.kt, CirclesScreen.kt, FriendsScreen.kt, SettingsServers.kt |
| `isAppDarkTheme` | Theme.kt:19 | 7 | MainActivity.kt, CoverageMapScreen.kt, MapScreen.kt, RouteEditorScreen.kt, SettingsScreen.kt, TripCardRenderer.kt, TripDetailScreen.kt |
| `CardDivider` | Cards.kt:53 | 8 | SocialScreen.kt, SettingsHub.kt, SearchIsland.kt, HubScreen.kt, SavedPlacesScreen.kt, FriendsScreen.kt, CirclesScreen.kt, SettingsServers.kt |
| `ChoiceRow` | Pills.kt:184 | 5 | TripCardRenderer.kt, Obd2PairingScreen.kt, SpinCards.kt, NavigationDock.kt, SettingsScreen.kt |
| `HubRow` | HubScreen.kt:407 | 4 | SocialScreen.kt, SettingsHub.kt, SettingsServers.kt, CirclesScreen.kt |
| `SectionLabel` | Cards.kt:66 | 4 | SettingsScreen.kt, HubScreen.kt, SettingsHub.kt, SettingsServers.kt |
| `glassContainerColor()` | GlassSurface.kt:34 | 3 | CoverageMapScreen.kt, SearchIsland.kt, MapChrome.kt |
| `NavButton` | NavAppLaunch.kt:155 | 3 | SpinCards.kt, RideSheet.kt, NavigationDock.kt |
| `TravelMode.icon` | TravelModeIcon.kt:9 | 3 | HistoryScreen.kt, HomeSheet.kt, RouteEditorScreen.kt |
| `SettingsSection` | SettingsScreen.kt:1212 | 2 (+ internal) | SettingsDiagnostics.kt, SettingsServers.kt |
| `SearchIsland` | SearchIsland.kt:103 | 2 | HomeSheet.kt, RideSheet.kt |
| `ResultCallout` | SpinCards.kt:63 | 2 | RideSheet.kt, NavigationDock.kt |
| `TripCardShareDialog` | TripCardRenderer.kt:277 | 2 | HistoryScreen.kt, TripDetailScreen.kt |
| `BackgroundLocationDisclosure` | MapDialogs.kt:28 | 2 (1 external + internal) | SettingsScreen.kt (+ MapDialogs.kt itself) |
| `DragHandle` | HomeSheet.kt:210 | 2 (1 external + internal) | RideSheet.kt (+ HomeSheet.kt itself) |
| `SettingsScaffold` | SettingsScreen.kt:138 | 2 (1 external + internal) | SettingsHub.kt (+ SettingsScreen.kt itself) |

Every other non-private composable/function checked in this session (`CandidatesCard`, `HomeSheet`, `MapBottomSlot`, `MapTopChrome`, `PushToTalkButton`, `SpeedHud`, `Obd2SignalLostLabel`, `MapNavigationSession`, `rememberMapPermissions`, `MapScreenDialogs`, `NavigationDock`, `MapSpeedEase`, `MapCameraLoops`, `MapPositionMarker`, `ServersSyncSpoke`, `MapCircleMemberMarkers`, `DriveSheet`, `NavSheet`, `RiderCard`, `NavigationBanner`, `RouteProgressTrack`, `SpeedLimitSign`, `SpinSheet`, `MapHazardAlerts`, `DiagnosticsSection`, `UpdateBanner`, `UpdateProgressButton`, `DisabledFeatureNotice`, `CredentialTextField`, `rememberActiveTripCardState`, `TripStatsRows`, `SavePinDialog`, `rememberRetainedMap`, `rememberTripCardMapSnapshot`, `rememberTripCardBitmap`, `choiceRowMetrics`, `SecretTextField`) has exactly one caller file (or, for `SecretTextField`, zero) and is therefore SCREEN-LOCAL COMPONENT, not shared.

---

## B) Family groupings — what actually exists in the code today

- **Buttons.** Only one genuinely shared button exists: `NavButton` (NavAppLaunch.kt:155, 3 callers). `UpdateProgressButton` (UpdateProgressButton.kt:70, 1 caller), `PushToTalkButton` (MapHud.kt:48, 1 caller), and `GlassRailButton` (MapChrome.kt:177, `private`, 0 external callers) are each single-caller or private. **No generic reusable `Button` wrapper family exists** — verified there is no file or symbol named anything like `DetourButton`/`AppButton`/`PrimaryButton` anywhere in `grep -rn "fun.*Button" app/src/main/java/com/jellemax/detour/ui/*.kt` beyond the four named above.

- **Modals/dialogs/sheets.** Real shared members: `ConfirmDialog` (9 callers) and `TripCardShareDialog` (2 callers). Everything else is a private, single-screen, one-off dialog: `BatteryOptimizationDialog`, `CreateCircleDialog`, `InviteToCircleDialog`, `SharePlaceDialog` (all CirclesScreen.kt, all `private`); `AddFriendDialog`, `CreateConvoyDialog`, `InviteToConvoyDialog` (all FriendsScreen.kt, `private`); `RenameRouteDialog`, `ShareRouteToFriendDialog` (RoutesScreen.kt, `private`); `AddPlaceDialog`, `RenameDialog` (SavedPlacesScreen.kt, `private`); `VehicleNameDialog` (SettingsScreen.kt, `private`); `SavePinDialog` (MapDialogs.kt, 0 external callers); `BackgroundLocationDisclosure` (MapDialogs.kt, shared 2x, see Table A). The bottom sheets — `HomeSheet`, `DriveSheet`, `NavSheet`, `SpinSheet` — are all single-caller from MapBottom.kt, i.e. screen-local to MapScreen's bottom-slot router, not a reusable sheet family across screens.

- **Cards.** `ListCard` (Cards.kt:27) is the one real generic card wrapper — 12 callers, the single most-reused component in the directory. `CandidatesCard` and `RiderCard` are each single-caller. The ~14 private composables inside TripCardRenderer.kt (`StandardCardContent`, `MinimalCardContent`, `PosterCardContent`, `MapSnapshotContent`, etc.) and HistoryScreen.kt's private `TripCard` are each single-purpose, single-file renderers for one specific product surface (the shareable trip card image, the history list row) — not members of a shared "cards" family despite the naming resemblance to `ListCard`.

- **HUD elements (speed / average speed / position / recording indicator).** `SpeedHud`, `PushToTalkButton`, `Obd2SignalLostLabel` (all MapHud.kt) and `MapPositionMarker` (MapCamera.kt) exist and form a real visual cluster, but every one is single-caller from MapScreen.kt — there is no cross-screen "HUD family," only MapScreen's own HUD, assembled from 4 files. **No "average speed" HUD composable exists anywhere in ui/** — verified with `grep -inE "average|recording" app/src/main/java/com/jellemax/detour/ui/*.kt` restricted to declaration lines: zero hits. Average-speed logic lives outside ui/, in `tracking/SectionAverageLog.kt` / `drive/SectionAverageTracker`, and is voice-announced through `MapHazardAlerts` (MapHazardAlerts.kt) rather than drawn as a HUD widget — confirmed by reading `SectionAverageLog.kt`'s own KDoc, which states "the phone (`ui/MapHazardAlerts.kt`) and [the car surface]" both consume it as a spoken string, not a rendered indicator. **No "recording indicator" composable exists** — same grep, zero hits for "recording" in any declaration.

- **Rows/list items.** Two real shared rows exist: `HubRow` (HubScreen.kt:407, 4 external callers) and `ChoiceRow` (Pills.kt:184, 5 callers — though it is more precisely a segmented-picker/pill-row than a list row; see its sub-composable `ChoiceItem`, Pills.kt:123, which draws each segment as a filled rounded pill). Every other `*Row`/`*RowItem` — `CircleInviteRow` (CirclesScreen.kt:434), `CircleMemberListRow` (CirclesScreen.kt:781), `RequestRow` (FriendsScreen.kt:396), `DeclinedRow` (FriendsScreen.kt:428), `LeaderboardRowItem` (FriendsScreen.kt:455), `ConvoyRow` (FriendsScreen.kt:763), `PlaceListRow` (SavedPlacesScreen.kt:202), `RouteCardItem` (RoutesScreen.kt:402) — is `private` and single-screen. **No generic `ListRow` abstraction exists**, despite the repeated shape (icon + two lines of text + trailing action) recurring across at least 5 of these.

- **Chips/badges.** None are shared. `ShortcutChipRow`/`PlaceChip` (HomeSheet.kt:249/366, both `private`), `ThenChip` (Navigation.kt:189, `private`), `ConvoyPill` (MapChrome.kt:146, `private`), `BadgeTileCell` (BadgesScreen.kt:195, `private`), `RouteActionPill` (RoutesScreen.kt:462, `private`), `SelectedMunicipalityPill` (CoverageMapScreen.kt:253, `private`) are each single-file, single-caller, and `private`. **This family does not exist as a reusable component** — every "chip/pill" in the codebase is a private one-off scoped to its own screen. Confirmed by grepping every `private fun.*Chip|private fun.*Pill` declaration in ui/ and checking each for external callers (none has one, by construction — they are `private`).

- **Inputs/fields.** `SecureFields.kt` holds the only two: `SecretTextField` (SecureFields.kt:123, zero callers — dead code, see Anomalies) and `CredentialTextField` (SecureFields.kt:164, 1 caller file, SettingsServers.kt, 5 sites). Neither is actually shared across screens. **Fewer than 2 real members — this is not a shared family.** It is one file whose two exports are used, respectively, by nothing and by exactly one other screen.

- **Loading/progress.** `UpdateProgressButton` (UpdateProgressButton.kt:70, single caller SettingsHub.kt:167) is the only custom progress composable in the directory; its private helpers `ProgressFill`/`determinateEdge`/`sweepWindow`/`ButtonLabel` are file-local. **No shared loading/progress family exists** — 1 member, 1 caller. (Ordinary Material3 `CircularProgressIndicator`/`LinearProgressIndicator` calls exist inline in several screens but are not wrapped in any custom ui/ component, so they are out of scope for a "family that exists in this codebase.")

- **Empty/error states.** `NoTripsYet` (HistoryScreen.kt:311, `private`), `HistoryLoadFailed` (HistoryScreen.kt:335, `private`), `NoPlacesYet` (SavedPlacesScreen.kt:171, `private`), `YouGuestCard` (HubScreen.kt:241, `private`), `DisabledFeatureNotice` (DisabledFeature.kt:25, 1 caller) are all private-or-single-caller, one per screen. **No shared `EmptyState`/`ErrorState` component exists**, despite the same visual pattern (icon + caption, sometimes a retry action) recurring in at least these 5 places across 4 different files.

---

## C) Files over the length thresholds

**Over 1000 LoC (hard threshold) — 2 files:**

| File | LoC |
|---|---|
| MapScreen.kt | 1353 |
| SettingsScreen.kt | 1228 |

**Over 500 LoC (soft threshold) — 11 files, including the two above:**

| File | LoC |
|---|---|
| MapScreen.kt | 1353 |
| SettingsScreen.kt | 1228 |
| CirclesScreen.kt | 896 |
| FriendsScreen.kt | 883 |
| TripCardRenderer.kt | 771 |
| TripDetailScreen.kt | 669 |
| MapLibreMap.kt | 622 |
| FogView.kt | 614 |
| HistoryScreen.kt | 586 |
| RoutesScreen.kt | 585 |
| SettingsServers.kt | 537 |

11 of 59 files (18.6% of the directory) exceed the soft threshold; 2 of 59 (3.4%) exceed the hard threshold. Full per-file line counts for all 59 files (`wc -l app/src/main/java/com/jellemax/detour/ui/*.kt`, alphabetical):

```
    45 AppBar.kt
   234 BadgesScreen.kt
   182 CandidatesCard.kt
    75 Cards.kt
   896 CirclesScreen.kt
    39 ConfirmDialog.kt
   431 CoverageMapScreen.kt
    48 DisabledFeature.kt
    52 DriveClockLocal.kt
   614 FogView.kt
    69 Format.kt
   883 FriendsScreen.kt
    44 GlassSurface.kt
    88 GraphiteTheme.kt
   586 HistoryScreen.kt
   422 HomeSheet.kt
   486 HubScreen.kt
   275 MapBottom.kt
   456 MapCamera.kt
   125 MapCameraTuning.kt
   200 MapChrome.kt
    62 MapCircleMembers.kt
   118 MapDialogs.kt
   141 MapHazardAlerts.kt
   184 MapHazardPrefetch.kt
   219 MapHud.kt
   622 MapLibreMap.kt
   141 MapNavigation.kt
   105 MapPermissions.kt
  1353 MapScreen.kt
   116 MapScreenState.kt
   248 NavAppLaunch.kt
   166 NavigationDock.kt
   266 Navigation.kt
   331 Obd2PairingScreen.kt
   213 Pills.kt
   172 ProfileScreen.kt
   268 RetainedMap.kt
    95 RiderCard.kt
   457 RideSheet.kt
   473 RouteEditorScreen.kt
   585 RoutesScreen.kt
   414 SavedPlacesScreen.kt
   370 SearchIsland.kt
   181 SecureFields.kt
   113 SettingsDiagnostics.kt
   193 SettingsHub.kt
  1228 SettingsScreen.kt
   537 SettingsServers.kt
   150 SocialScreen.kt
   362 SpinCards.kt
    99 SpinResultHolder.kt
    48 SpinShare.kt
    55 Theme.kt
    13 TravelModeIcon.kt
   771 TripCardRenderer.kt
   669 TripDetailScreen.kt
    80 UpdateBanner.kt
   165 UpdateProgressButton.kt
 18033 total
```

---

## D) The 6 test files

| Test file (LoC) | Production symbol(s) called | Production file | In ui/? |
|---|---|---|---|
| `ChoiceRowMetricsTest.kt` (169) | `choiceRowMetrics`, `ChoiceRow` | Pills.kt | Yes |
| `FormatTest.kt` (162) | `formatDuration`, `formatDistanceKm`, `formatSpeedKmh`, `formatGForce`, `formatLeanAngle` | Format.kt | Yes |
| `SecureFieldsTest.kt` (79) | `credentialKeyboardOptions`, `secretKeyboardOptions`, `secretMask` | SecureFields.kt | Yes |
| `SpinResultHolderTest.kt` (107) | `SpinResultHolder`, `SpinResult(...)` | SpinResultHolder.kt | Yes |
| `TripStatLineTest.kt` (102) | `tripStatLine`, `tripBehaviorLine`, `tripFuelEconomyLper100Km` | HistoryScreen.kt (HistoryScreen.kt:502-553) | Yes |
| `TripTraceMatchingTest.kt` (88) | `matchTripPoints` | HistoryScreen.kt (HistoryScreen.kt:132) | Yes |

All 6 test files test production code that lives inside `ui/`; none test anything outside the directory. All 6 are plain JUnit tests with no Robolectric/Compose-UI-test dependency (confirmed no such import in any of the 6 files) — they test pure functions extracted from, or living beside, the composables, never the composables themselves.

---

## Anomalies

Specific, file:line-cited defects and naming hazards found during this inventory:

1. **`SectionLabel` shadowing in BadgesScreen.kt.** Cards.kt:66 declares a public `fun SectionLabel(text: String, modifier: Modifier = Modifier)`. BadgesScreen.kt independently declares `private fun SectionLabel(text: String)` at BadgesScreen.kt:179, and calls `SectionLabel(group.label)` at BadgesScreen.kt:100. Both are visible at that call site (same package, `com.jellemax.detour.ui`, so no import is needed for either), and Kotlin's same-file-priority rule for top-level declarations means BadgesScreen.kt:100 resolves to its own private BadgesScreen.kt:179 function, not to Cards.kt's shared one. Net effect: BadgesScreen.kt looks like a consumer of the shared `SectionLabel` component (same name, same call syntax) but is not — it silently maintains its own divergent copy. Confirmed by inspecting BadgesScreen.kt's imports (BadgesScreen.kt:3-20): no import of `SectionLabel` from anywhere, consistent with same-package resolution to the local private declaration.

2. **`SecretTextField` has zero callers.** SecureFields.kt:123 declares `fun SecretTextField(...)`, public, no `internal`/`private` modifier. `grep -rn "SecretTextField" app/src/main/java app/src/androidTest` returns exactly one line: the declaration itself. It is not referenced anywhere else in the app module, nor in the androidTest source set. Its sibling, `CredentialTextField` (SecureFields.kt:164), is used (by SettingsServers.kt only). `SecretTextField` is dead public code.

3. **`Obd2PairingScreen.kt` is not a route despite the "Screen" suffix.** Obd2PairingScreen.kt:47 declares `fun Obd2PairingScreen()`. Its only caller is SettingsScreen.kt:200, inside `SettingsSpokeScreen`'s `when (spoke)` block: `Destination.SettingsObd2 -> Obd2PairingScreen()`. This is structurally identical to the other branches in that same `when` — `Destination.SettingsNavigation -> NavigationSection()` (SettingsScreen.kt:191), `Destination.SettingsFog -> FogSection(context)` (SettingsScreen.kt:192) — all of which are unambiguously private, spoke-content sections, not screens. `Obd2PairingScreen` is the one section given a "Screen"-suffixed name and public (not `private`) visibility, despite never being registered against a `Destination` in MainActivity.kt's `entry<...>` table. Confirmed by reading the full `entry<Destination.SettingsObd2>` registration at MainActivity.kt:478-479, which routes to `SettingsSpokeScreen(key, ...)`, not to `Obd2PairingScreen` directly.

4. **The SettingsHub.kt / SettingsScreen.kt name swap.** SettingsHub.kt:45 declares `fun SettingsScreen(onBack: () -> Unit, onOpenSpoke: (Destination.SettingsSpoke) -> Unit)` — the settings **hub** screen (the six-row menu), routed at `Destination.Settings` (MainActivity.kt:451-452). SettingsScreen.kt, a completely different and much larger file (1228 lines), does **not** declare `SettingsScreen` at all — it declares `SettingsSpokeScreen` (SettingsScreen.kt:173), `SettingsScaffold` (SettingsScreen.kt:138), and `SettingsSection` (SettingsScreen.kt:1212), i.e. the machinery for the six **spoke** detail screens. A reader guessing "the file with the settings hub screen is SettingsScreen.kt" would land in the wrong file; the hub composable is in SettingsHub.kt and the file called SettingsScreen.kt contains no composable named `SettingsScreen`.

5. **Navigation.kt as a holding pen.** Navigation.kt (266 lines) exports three public/internal composables — `NavigationBanner` (Navigation.kt:148), `RouteProgressTrack` (Navigation.kt:214), `SpeedLimitSign` (Navigation.kt:246) — each with exactly one caller, and each caller is a *different* file: `NavigationBanner` → only MapScreen.kt:1102; `RouteProgressTrack` → only RideSheet.kt:182; `SpeedLimitSign` → only MapHud.kt:201. None of the three is ever called from Navigation.kt itself, nor from each other. The file groups three unrelated single-caller declarations under a common "navigation UI" theme rather than under any shared caller or shared lifecycle — it is a topic-named holding pen, not a cohesive component or a screen.

6. **HistoryScreen.kt carries tested, externally-consumed utilities inside a screen file.** HistoryScreen.kt (586 lines) declares the `HistoryScreen` route composable (HistoryScreen.kt:185) but also `tripStatLine` (HistoryScreen.kt:502), `tripBehaviorLine` (HistoryScreen.kt:518), `tripFuelEconomyLper100Km` (HistoryScreen.kt:539), `loadTripTrace` (HistoryScreen.kt:171), `loadTripPoints` (HistoryScreen.kt:177), and `matchTripPoints` (HistoryScreen.kt:132, `internal`). The first five of these are called from **TripDetailScreen.kt**, a different route entirely (confirmed call sites: `tripStatLine`/`tripBehaviorLine`/`tripFuelEconomyLper100Km` and `loadTripTrace`/`loadTripPoints` are all invoked from TripDetailScreen.kt). Two of the six production-test-file pairs in Part D — `TripStatLineTest.kt` and `TripTraceMatchingTest.kt` — exist specifically to test these functions, meaning a screen file (with a `@Composable` route, private sub-composables, and Compose imports) is also the module under test for pure trip-formatting logic consumed by a sibling screen.
