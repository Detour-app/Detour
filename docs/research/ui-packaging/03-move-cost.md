# UI packaging: cross-surface reuse and move-cost — research

Date: 2026-09-09
Commit: `efa6c9ad` (`git rev-parse --short HEAD`)
Scope: `app/src/main/java/com/jellemax/detour/ui/` (59 flat `.kt` files) and every
other surface that touches it — `car/`, `map/`, `nav/`, `convoy/`, `tracking/`,
`data/`, `shared/src/`, test source sets, build/lint/CI config, docs.

This is a facts document: what already reaches across the `ui/` boundary today,
and what a move into nested subpackages would cost. It does not propose a
target layout. Every count below has the exact command next to it so it can be
re-run against a later commit.

---

## 1. Composables, theme, and format helpers outside `ui/`

### 1.1 `@Composable` functions outside `ui/`

Command:
```
grep -rln "@Composable" app/src/main/java/com/jellemax/detour --include="*.kt" | grep -v "/ui/"
grep -rln "@Composable" shared/src --include="*.kt"
```

Result: exactly one file outside `ui/` in the whole `app/` tree, and none in
`shared/`.

| File | Count | What |
|---|---|---|
| `app/src/main/java/com/jellemax/detour/MainActivity.kt` | 3 | `AppRoot` (`MainActivity.kt:210-211`), `TripDetailEntry` (`MainActivity.kt:546-547`), `RouteEditorEntry` (`MainActivity.kt:565-566`) — the nav-host entry points, not screen content |
| `shared/src/{commonMain,androidMain,iosMain,commonTest,androidUnitTest}` | 0 | Compose does not appear in `shared/` at all |
| `car/`, `map/`, `nav/`, `convoy/`, `tracking/`, `data/`, `net/`, `notif/`, `obd2/`, `ble/`, `audio/`, `media/`, `perf/`, `update/`, `auth/` | 0 each | confirmed via the same grep with `-l` per directory; car/ (Android Auto) uses the car-app-library template API, not Compose, matching the question's premise |

### 1.2 Theme / color / dimension / typography definitions

Command:
```
find app/src/main/java/com/jellemax/detour -iname "*theme*" -o -iname "*color*" -o -iname "*typography*" -o -iname "*dimens*"
grep -rn "object.*Dimens\|Typography(" app/src/main/java/com/jellemax/detour --include="*.kt"
```

All of it lives in `ui/`. No `Dimens` or custom `Typography` object exists
anywhere in the repo — the app rides Material3's defaults for those two axes.

| Symbol | Location |
|---|---|
| `val GraphiteDark = darkColorScheme(...)` | `app/src/main/java/com/jellemax/detour/ui/GraphiteTheme.kt:15` |
| `val GraphiteLight = lightColorScheme(...)` | `app/src/main/java/com/jellemax/detour/ui/GraphiteTheme.kt:53` |
| `fun isAppDarkTheme(theme: Settings.Theme): Boolean` | `app/src/main/java/com/jellemax/detour/ui/Theme.kt:19` |
| `private fun isNightNow(): Boolean` (sun-based day/night, used only by `isAppDarkTheme`) | `app/src/main/java/com/jellemax/detour/ui/Theme.kt:32` |
| Applied at | `app/src/main/java/com/jellemax/detour/MainActivity.kt:141` — `MaterialTheme(colorScheme = if (dark) GraphiteDark else GraphiteLight)` |

### 1.3 Formatting/display helpers UI calls

Command:
```
grep -rn "fun format\|fun.*Format(" app/src/main/java/com/jellemax/detour shared/src --include="*.kt" | grep -i "speed\|distance\|duration\|eta\|time"
```

The app-facing names all live in one file, `app/src/main/java/com/jellemax/detour/ui/Format.kt`
(70 lines). Some of them delegate to `shared/` so the wording can't drift
between the Compose HUD and the KMP presentation layer; some are Android-only
and have no shared counterpart; one pattern is duplicated rather than shared.

| `ui/Format.kt` function | Line | Delegates to `shared/.../presentation/DisplayFormat.kt` | Shared line |
|---|---|---|---|
| `formatDuration(ms)` | 17 | `formatDurationClock` | `DisplayFormat.kt:171` |
| `formatDurationHistory(ms)` | 28 | `formatDurationHistory` | `DisplayFormat.kt:68` |
| `formatDistanceKm(meters, sep)` | 42-43 | `formatDistanceKm` | `DisplayFormat.kt:116` |
| `formatGForce(g, sep)` | 61-62 | `formatGForce` (via `presentation.formatGForce`) | — |
| (shared-only, no `ui/Format.kt` wrapper) | — | `formatEta(epochMs, zone)` | `DisplayFormat.kt:150` |

Android-only, no shared counterpart (all in `ui/Format.kt`): `formatSpeedKmh` (line 30,
`"%.0f km/h".format(mps * 3.6)`), `formatDate` (line 51, `SimpleDateFormat`),
`formatTimeOfDay` (line 55), `formatLeanAngle` (line 57), `formatFuelPer100Km`
(line 68-69, built from shared `formatFixed`).

Comments at `Format.kt:13-16`, `:32-41`, `:59-61` explicitly document *why* the
delegation exists (a past bug: nl-BE riders saw `20,5 km` next to `33.3 km` on
one screen because one formatter followed `Locale.getDefault()` and the other
didn't) — this is a maintained boundary, not incidental.

**Duplication found**: the mps→km/h conversion constant (`* 3.6`) is hand-rolled
a second and third time, bypassing `formatSpeedKmh` entirely:

- `app/src/main/java/com/jellemax/detour/car/NavScreen.kt:255` — `currentSpeedKmh = speedMps * 3.6`
- `app/src/main/java/com/jellemax/detour/car/SpinScreen.kt:139` — `renderer.updateHud(fix.speedMps * 3.6, limitState.limitKmh)`

Command used: `grep -rn "3.6" app/src/main/java/com/jellemax/detour/car app/src/main/java/com/jellemax/detour/map --include="*.kt"`.
Not a strict duplicate of the *formatter* — `car/` needs the raw `Double` for
canvas drawing, not a formatted `String` — but the constant itself is copied
rather than reused, so a rename/refactor of the conversion would need to touch
three sites, only one of which is discoverable from `ui/`.

---

## 2. `car/` (Android Auto) → `ui/` imports — complete

Command:
```
grep -rn "import com.jellemax.detour.ui" app/src/main/java/com/jellemax/detour/car --include="*.kt"
```

Result — every hit, 8 symbols across 2 files:

| File:line | Imported symbol | Declared at | Visibility |
|---|---|---|---|
| `car/CarMapRenderer.kt:31` | `MAX_FRAME_WALL_S` | `ui/MapCameraTuning.kt:107` | `internal` |
| `car/CarMapRenderer.kt:32` | `driveFrameDelta` | `ui/MapCameraTuning.kt:123` | `internal` |
| `car/CarMapRenderer.kt:40` | `MapOverlays` | `ui/MapLibreMap.kt:123` | public |
| `car/CarMapRenderer.kt:41` | `NamedFriendPosition` | `ui/MapLibreMap.kt:115` | public |
| `car/CarMapRenderer.kt:42` | `PositionMarker` | `ui/MapLibreMap.kt:547` | public |
| `car/CarMapRenderer.kt:43` | `openFreeMapStyleUrl` | `ui/MapLibreMap.kt:41` | public |
| `car/CarMapRenderer.kt:44` | `setCamera` | `ui/MapLibreMap.kt:619` | public |
| `car/SpinScreen.kt:40` | `formatDistanceKm` | `ui/Format.kt:42` | public |

So Android Auto — which renders through the car-app-library template API, not
Compose — still depends on `ui/` for: two pieces of the internal camera-math
vocabulary (`MAX_FRAME_WALL_S`, `driveFrameDelta`), three map-overlay/position
types (`MapOverlays`, `NamedFriendPosition`, `PositionMarker`), one map helper
(`openFreeMapStyleUrl`), one camera-set function (`setCamera`), and one
formatter (`formatDistanceKm`). 6 of the 8 are public; 2 (`MAX_FRAME_WALL_S`,
`driveFrameDelta`) are `internal` — meaning `car/` already crosses a package
boundary today by relying on Kotlin's module-scoped (not package-scoped)
`internal` visibility. See §4 for why that matters for a nested move.

---

## 3. Other out-of-`ui/` consumers

### 3.1 Production code (non-`car/`, non-`MainActivity`)

Command:
```
grep -rln "import com.jellemax.detour.ui" app/src/main/java/com/jellemax/detour --include="*.kt" | grep -v "^app/src/main/java/com/jellemax/detour/ui/"
```

| File:line | Imported symbol | Declared at | Visibility |
|---|---|---|---|
| `app/src/main/java/com/jellemax/detour/map/FollowCamera.kt:3` | `CAM_RESUME_QUIET_MS` | `ui/MapCameraTuning.kt:93` | `internal` |
| `map/FollowCamera.kt:4` | `CAM_RESUME_SPEED_MPS` | `ui/MapCameraTuning.kt:92` | `internal` |
| `map/MapMotion.kt:5` | `CAM_POS_EPS_DEG` | `ui/MapCameraTuning.kt:36` | `internal` |
| `map/MapMotion.kt:6` | `CAM_SNAP_METERS` | `ui/MapCameraTuning.kt:44` | `internal` |
| `map/MapMotion.kt:7` | `CAM_ZOOM_EPS` | `ui/MapCameraTuning.kt:37` | `internal` |
| `map/SpinRun.kt:15` | `CURVY_CANDIDATES` | `ui/MapCameraTuning.kt:86` | `internal` |
| `tracking/TripSession.kt:16` | `loadTripPoints` | `ui/HistoryScreen.kt:177` | public |

`MainActivity.kt:68-87` imports 16 screen composables plus `GraphiteDark`,
`GraphiteLight`, `isAppDarkTheme`, `rememberRetainedMap` — this is the expected
nav-host wiring (every screen must be reachable from the activity that hosts
`NavHost`), not incidental reuse, and is listed here for completeness rather
than as a surprise:
`BadgesScreen`, `CircleDetailScreen`, `CirclesScreen`, `CoverageMapScreen`,
`FriendsScreen`, `HistoryScreen`, `HubScreen`, `MapScreen`, `GraphiteDark`,
`GraphiteLight`, `ProfileScreen`, `RouteEditorScreen`, `RoutesScreen`,
`SavedPlacesScreen`, `SettingsScreen`, `SettingsSpokeScreen`, `SocialScreen`,
`TripDetailScreen`, `isAppDarkTheme`, `rememberRetainedMap` (`MainActivity.kt:68-87`).

### 3.2 Test source-set consumers

Command:
```
grep -rln "import com.jellemax.detour.ui" app/src/test app/src/androidTest 2>/dev/null
```

`app/src/androidTest`: no hits. `app/src/test` (unit-test source set, a
*separate* Kotlin compilation from `main`): 3 files, all importing `internal`
constants from `ui/MapCameraTuning.kt`:

| File:line | Imported symbol |
|---|---|
| `app/src/test/java/com/jellemax/detour/map/MapMotionTest.kt:5` | `CAM_SNAP_METERS` |
| `app/src/test/java/com/jellemax/detour/map/FollowCameraTest.kt:3` | `CAM_RESUME_QUIET_MS` |
| `app/src/test/java/com/jellemax/detour/map/FollowCameraTest.kt:4` | `CAM_RESUME_SPEED_MPS` |
| `app/src/test/java/com/jellemax/detour/map/CameraAuthorityTest.kt:8` | `CAM_RESUME_QUIET_MS` |

This confirms `internal` declarations in `ui/` are consumed from *another
source set*, not merely another package within `main` — the `test` source set
compiles with friend access to `main`'s `internal` API, and three test files
already rely on that. A package rename inside `ui/` would need these import
lines updated too, even though the visibility itself keeps working.

Also checked and empty: `app/src/automotive` (only `AndroidManifest.xml`
matched the grep, no Kotlin).

---

## 4. Visibility / move-cost analysis

### 4.1 `private` — file-scoped, so "private but used cross-file" is an empty set by construction

In Kotlin, a top-level `private` declaration is scoped to the **file**, not the
package. Unlike Java's package-private default, another file in the *same
package* cannot see it — the compiler enforces this today, at the current flat
layout. Command used to confirm no counter-example exists:
```
grep -rn "^private " app/src/main/java/com/jellemax/detour/ui --include="*.kt"
```
Every hit is a declaration used only within its own file (spot-checked
`Theme.kt:32` `private fun isNightNow()`, used only by `isAppDarkTheme` in the
same file). **Conclusion: there are zero private-but-cross-file-referenced
declarations in `ui/`, and there cannot be any — the hazard the proposal might
be worried about on this axis doesn't exist under the current flat layout, and
nesting can't make it worse.**

### 4.2 `internal` — module-scoped, so today's package (flat or nested) buys zero encapsulation

Kotlin's `internal` visibility is scoped to the **Gradle module's
compilation**, not to the package. That means every `internal` declaration in
`ui/` is already visible to `car/`, `map/`, `tracking/`, and `app/src/test` —
all of which are proven consumers today (§2, §3). Splitting `ui/` into nested
subpackages changes **zero** visibility outcomes for `internal` declarations
(they'd still be visible everywhere in the `app` module and its test source
sets) — but it still breaks every existing import statement, because the
fully-qualified name changes. Visibility is not the cost here; the import
churn is.

Command: `grep -rn "^internal " app/src/main/java/com/jellemax/detour/ui --include="*.kt" | wc -l` → **72** top-level `internal` declarations (all top-level; a separate check for indented/class-member `internal` returned 0, so this is the complete set). Full enumeration, since this list is the actual work order for a move:

| File | Line | Declaration |
|---|---|---|
| CandidatesCard.kt | 44 | `internal fun CandidatesCard(` |
| ConfirmDialog.kt | 21 | `internal fun ConfirmDialog(` |
| HistoryScreen.kt | 75 | `internal data class TraceSegment(` |
| HistoryScreen.kt | 132 | `internal fun matchTripPoints(segments: List<TraceSegment>, trip: Trip): List<TraceStore.TracePoint> {` |
| HomeSheet.kt | 74 | `internal val HOME_SHEET_HEIGHT = 232.dp` |
| HomeSheet.kt | 90 | `internal val HOME_SHEET_FONT_SCALE_GROWTH = 64.dp` |
| HomeSheet.kt | 120 | `internal fun ColumnScope.HomeSheet(` |
| HomeSheet.kt | 210 | `internal fun DragHandle() {` |
| MapBottom.kt | 56 | `internal fun BoxScope.MapBottomSlot(` |
| MapCamera.kt | 52 | `internal fun MapSpeedEase(retained: RetainedMap) {` |
| MapCamera.kt | 78 | `internal fun MapCameraLoops(s: MapScreenState, retained: RetainedMap) {` |
| MapCamera.kt | 259 | `internal fun MapPositionMarker(s: MapScreenState, retained: RetainedMap) {` |
| MapCamera.kt | 452 | `internal fun levelToNorthUp(map: MapLibreMap) {` |
| MapCameraTuning.kt | 13 | `internal const val CAM_POS_TAU = 0.35` |
| MapCameraTuning.kt | 14 | `internal const val CAM_ZOOM_TAU = 1.2` |
| MapCameraTuning.kt | 23 | `internal const val SPEED_TAU = 0.20` |
| MapCameraTuning.kt | 26 | `internal const val SPEED_EPS_KMH = 0.15` |
| MapCameraTuning.kt | 36 | `internal const val CAM_POS_EPS_DEG = 2e-6` |
| MapCameraTuning.kt | 37 | `internal const val CAM_ZOOM_EPS = 2e-3` |
| MapCameraTuning.kt | 44 | `internal const val CAM_SNAP_METERS = 250.0` |
| MapCameraTuning.kt | 48 | `internal const val FIT_PADDING_PX = 140` |
| MapCameraTuning.kt | 78 | `internal const val MAP_FIT_BOTTOM_PADDING_DP = 390` |
| MapCameraTuning.kt | 86 | `internal const val CURVY_CANDIDATES = 3` |
| MapCameraTuning.kt | 92 | `internal const val CAM_RESUME_SPEED_MPS = 3.0` |
| MapCameraTuning.kt | 93 | `internal const val CAM_RESUME_QUIET_MS = 8_000L` |
| MapCameraTuning.kt | 98 | `internal const val CIRCLE_FIX_POLL_MS = CirclePresence.ACTIVE_INTERVAL_MS` |
| MapCameraTuning.kt | 107 | `internal const val MAX_FRAME_WALL_S = 0.1` |
| MapCameraTuning.kt | 123 | `internal fun driveFrameDelta(wallSeconds: Double, driveSeconds: Double): Double =` |
| MapChrome.kt | 52 | `internal fun MapTopChrome(` |
| MapCircleMembers.kt | 27 | `internal fun MapCircleMemberMarkers(` |
| MapDialogs.kt | 28 | `internal fun BackgroundLocationDisclosure(` |
| MapDialogs.kt | 57 | `internal fun SavePinDialog(` |
| MapDialogs.kt | 92 | `internal fun MapScreenDialogs(` |
| MapHazardAlerts.kt | 34 | `internal fun MapHazardAlerts(` |
| MapHazardPrefetch.kt | 37 | `internal fun MapHazardPrefetch(` |
| MapHud.kt | 48 | `internal fun PushToTalkButton(talking: Boolean, modifier: Modifier = Modifier) {` |
| MapHud.kt | 121 | `internal fun SpeedHud(state: SpeedHudState, modifier: Modifier = Modifier) {` |
| MapHud.kt | 211 | `internal fun Obd2SignalLostLabel(lost: Boolean) {` |
| MapNavigation.kt | 45 | `internal fun MapNavigationSession(` |
| MapPermissions.kt | 36 | `internal fun rememberMapPermissions(` |
| MapScreenState.kt | 55 | `internal data class MapLayers(` |
| MapScreenState.kt | 62 | `internal class MapScreenState(seed: SpinResult) {` |
| NavAppLaunch.kt | 155 | `internal fun NavButton(` |
| NavigationDock.kt | 57 | `internal fun NavigationDock(` |
| Navigation.kt | 214 | `internal fun RouteProgressTrack(fraction: Float, modifier: Modifier = Modifier) {` |
| Obd2PairingScreen.kt | 324 | `internal fun obd2FailureText(failure: Obd2Failure): String? = when (failure) {` |
| Pills.kt | 69 | `internal data class ChoiceRowMetrics(` |
| Pills.kt | 88 | `internal fun choiceRowMetrics(` |
| Pills.kt | 184 | `internal fun ChoiceRow(` |
| RiderCard.kt | 34 | `internal fun RiderCard(` |
| RideSheet.kt | 67 | `internal data class SheetToggle(` |
| RideSheet.kt | 75 | `internal data class WhereTo(` |
| RideSheet.kt | 85 | `internal data class GoTarget(` |
| RideSheet.kt | 100 | `internal fun DriveSheet(` |
| RideSheet.kt | 172 | `internal fun NavSheet(` |
| RideSheet.kt | 350 | `internal fun rememberActiveTripCardState(stats: TripStats): ActiveTripCardState {` |
| RideSheet.kt | 383 | `internal fun TripStatsRows(stats: TripStats, state: ActiveTripCardState) {` |
| SecureFields.kt | 60 | `internal fun credentialKeyboardOptions(keyboardType: KeyboardType) = KeyboardOptions(` |
| SecureFields.kt | 67 | `internal fun secretKeyboardOptions() = credentialKeyboardOptions(KeyboardType.Password)` |
| SecureFields.kt | 74 | `internal fun secretMask(revealed: Boolean): VisualTransformation =` |
| SettingsScreen.kt | 108 | `internal fun spokeTitle(spoke: Destination.SettingsSpoke): String = when (spoke) {` |
| SettingsScreen.kt | 138 | `internal fun SettingsScaffold(` |
| SettingsScreen.kt | 1212 | `internal fun SettingsSection(` |
| SettingsServers.kt | 62 | `internal fun ServersSyncSpoke(scrollState: ScrollState) {` |
| SpinCards.kt | 63 | `internal fun ResultCallout(` |
| SpinCards.kt | 107 | `internal fun SpinSheet(` |
| SpinResultHolder.kt | 19 | `internal data class SpinResult(` |
| SpinResultHolder.kt | 37 | `internal object SpinResultHolder {` |
| SpinResultHolder.kt | 86 | `internal fun seedRouteNavigation(route: SavedRoute) {` |
| SpinShare.kt | 12 | `internal val CANDIDATE_COLORS = listOf(0xFF7E57C2, 0xFF00897B, 0xFFF4511E)` |
| SpinShare.kt | 22 | `internal fun GroupSpin.asRouteCandidates(): List<RouteCandidates> = candidates.map { sc ->` |
| SpinShare.kt | 40 | `internal fun List<RouteCandidate>.asSpinCandidates(): List<SpinCandidate> = map { c ->` |

(All paths above are under `app/src/main/java/com/jellemax/detour/ui/`.) Of
these 72, at least 7 are proven to be consumed from outside `ui/` today: the 6
`MapCameraTuning.kt` constants listed in §3.1 (`CAM_RESUME_QUIET_MS`,
`CAM_RESUME_SPEED_MPS`, `CAM_POS_EPS_DEG`, `CAM_SNAP_METERS`, `CAM_ZOOM_EPS`,
`CURVY_CANDIDATES`) plus `MAX_FRAME_WALL_S`/`driveFrameDelta` from §2 — 8
distinct symbols, all from one file, `MapCameraTuning.kt`.

**No `internal` declaration in `ui/` is used from `shared/` or `iosApp/`** —
expected, since `shared/` is a separate Gradle module that `app/` depends on,
not the reverse, so it structurally cannot see `app`-module `internal`
symbols. Checked by confirming `shared/src/` has zero `@Composable` and zero
imports of `com.jellemax.detour.ui` (§1.1, and a direct grep for the package
string across `shared/src` returned nothing).

---

## 5. Import-line cost estimate for a full nesting

**Method**: for each of the 59 files in `ui/`, extract top-level
`fun`/`val`/`var`/`class`/`object`/`interface`/`data class`/`enum class`/
`typealias` declarations that are not explicitly `private` (default-public or
`internal` — these need no import today because every file shares one
package). Then, for each declared symbol name, run `grep -lw <symbol> *.kt`
across all 59 files, excluding the declaring file itself, and count how many
*other* files contain a plain-text word match. Each such match is a file that
would need one new `import` line the day the declaring file moves to a
different subpackage under a nested `ui/` layout.

Commands (script, run from `app/src/main/java/com/jellemax/detour/ui/`):
```
for f in *.kt; do
  grep -oE '^(internal |public )?(fun|val|var|class|object|interface|enum class|data class|sealed class|sealed interface|typealias) +[A-Za-z_][A-Za-z0-9_]*' "$f" \
    | awk -v file="$f" '{print $NF, file}'
done > symbols.txt   # 128 lines

while read -r sym declfile; do
  n=$(grep -lw "$sym" *.kt | grep -v "^${declfile}$" | wc -l)
  [ "$n" -gt 0 ] && echo "$sym ($declfile): used in $n other files"
done < symbols.txt > cross_refs.txt   # 107 lines

awk -F': used in ' '{sum+=$2}' cross_refs.txt   # sums the "N other files" column
```

**Intermediate numbers**: 128 top-level public/internal symbols extracted;
107 of those are referenced from at least one other file in `ui/`; the sum of
per-symbol cross-file hit counts is **366**.

**Why 366 is an upper bound, not a clean estimate** — two effects, pulling in
opposite directions, both confirmed by inspection of the raw symbol list:

1. *Over-count from comment/doc mentions.* `grep -lw` matches plain text,
   including a symbol name mentioned in a doc comment rather than actually
   referenced in code. Not separately quantified, but every code reference is
   also a plain-text match, so this can only inflate 366, never deflate it.
2. *Over-count from a parser artifact on extension functions.* For an
   extension function `fun Receiver.name(...)`, the extraction regex grabs the
   receiver type (`Receiver`) as if it were the declared name, not the real
   function name. Four such artifacts were found and traced back to their real
   declarations:

   | Artifact "symbol" | File | Real declaration | Hits attributed to the artifact |
   |---|---|---|---|
   | `Modifier` | GlassSurface.kt | `fun Modifier.glassBorder(shape: Shape)` at `GlassSurface.kt:40` | 38 |
   | `List` | SpinShare.kt | `internal fun List<RouteCandidate>.asSpinCandidates()` at `SpinShare.kt:40` | 21 |
   | `ColumnScope` | HomeSheet.kt | `internal fun ColumnScope.HomeSheet(...)` at `HomeSheet.kt:120` | 4 |
   | `BoxScope` | MapBottom.kt | `internal fun BoxScope.MapBottomSlot(...)` at `MapBottom.kt:56` | 1 |

   `Modifier` and `List` are common Compose/Kotlin stdlib type names that
   appear in many unrelated function signatures across `ui/`, so counting
   "files that mention `Modifier`" wildly overstates how many files actually
   call `glassBorder`. Excluding these 4 artifacts (38+21+4+1 = 64 hits)
   brings the total to **302**. At the same time, the real extension-function
   names (`glassBorder`, `asSpinCandidates`, `HomeSheet`, `MapBottomSlot`)
   never got their own row in `cross_refs.txt`, so their true cross-file usage
   is *not* counted anywhere in either the 366 or the 302 figure — a genuine
   undercount affecting exactly these 4 of the 128 symbols.

**Bottom line**: treat **~300–370 new import lines** across the 59 files as
the right order of magnitude for a full nesting of `ui/`, with 302 (raw total
minus the 4 known parser artifacts) as the better point estimate. This is a
method-and-magnitude number, not an exact count — an exact count requires a
Kotlin-aware reference index (e.g. running the compiler or an IDE find-usages
pass), not a text-based script.

**Highest-fan-out real symbols** (excludes the 4 artifacts above), i.e. the
declarations whose file would ripple into the most other files on a move:

| Symbol | Declared in | Used in N other `ui/` files |
|---|---|---|
| `MapScreen` | MapScreen.kt | 20 |
| `TravelMode` | TravelModeIcon.kt | 12 |
| `SubScreenTopBar` | AppBar.kt | 12 |
| `ListCard` | Cards.kt | 12 |
| `glassCardColors` | GlassSurface.kt | 12 |
| `MapScreenState` | MapScreenState.kt | 9 |
| `ConfirmDialog` | ConfirmDialog.kt | 9 |
| `CardDivider` | Cards.kt | 8 |
| `TripDetailScreen` | TripDetailScreen.kt | 7 |
| `RetainedMap` | RetainedMap.kt | 7 |
| `MapOverlays` | MapLibreMap.kt | 6 |
| `isAppDarkTheme` | Theme.kt | 6 |
| `formatDistanceKm` | Format.kt | 6 |

Full 107-row `cross_refs.txt` and full 128-row `symbols.txt` are reproducible
verbatim with the commands above; not reproduced in full here since the table
above already surfaces the entries that matter for sequencing a move (highest
fan-out first).

---

## 6. Build/config coupling with the literal `com.jellemax.detour.ui` string

Searched, each with the command used:

```
find . -iname "*proguard*" -o -iname "*r8*rules*" | grep -v /build/
  -> ./app/proguard-rules.pro
grep -n "ui\b|detour" app/proguard-rules.pro
  -> no hits

find . -iname "lint-baseline*.xml" -o -iname "lint.xml" | grep -v /build/
  -> no files exist in this repo at all (not "no hits in the file" — the file itself is absent)

find . -iname "detekt*.yml" -o -iname ".editorconfig" -o -iname "ktlint*" | grep -v /build/
  -> ./config/detekt/detekt.yml
grep -n "ui\b|detour|exclude|Glob|path" config/detekt/detekt.yml
  -> two incidental hits, neither a package-path rule: line 70 "excludeCommentStatements: false"
     (unrelated detekt option), line 141 a prose comment mentioning a skill name
     ("detour-compose-state-hazards"), not a path

find .github -type f
  -> .github/workflows/build.yml, backend.yml, ios.yml
grep -rn "detour\.ui|/ui/|ui/\*\*" .github/workflows
  -> no hits

grep -n "packages|sourceSets|compose_compiler|metricsDestination|reportsDestination|composeCompiler" app/build.gradle.kts
  -> only "sourceSets {" at line 202, which configures githubRelease/automotive
     source directories (src/release/java, src/debug/java) — no package-string
     filtering, no Compose-compiler-metrics destination keyed by package
```

**Result: zero.** Nothing in Proguard/R8 rules, detekt config, lint baseline
(no such file exists in the repo), `build.gradle.kts` (no source-set filtering
or Compose-compiler-metrics path keyed by package), or `.github/workflows/`
hardcodes the `ui` package path. Bounds on this negative: the search covered
every `*.pro`/`*R8*rules*` file, every `detekt*.yml`/`.editorconfig`/`ktlint*`
file, all three workflow files under `.github/workflows/`, and the full
`app/build.gradle.kts`. It does not cover files outside the repo (e.g. a CI
runner's own cache config) or generated `build/` output, which was explicitly
excluded as derived, not source.

The literal path *does* appear in documentation and this repository's own
Claude Code skill files — these are not executed by the build, but would need
manual updates on a restructure since they point at specific files as worked
examples:

| File:line |
|---|
| `docs/PLAY_LOCATION_DECLARATION.md:31` |
| `docs/guidelines/architecture.md:121` |
| `docs/refactor/mapscreen/specs/convergence-2-section-readouts.md:23` |
| `docs/refactor/mapscreen/specs/convergence-2-section-readouts.md:41` |
| `docs/refactor/mapscreen/15-divergence-register.md:55` |
| `docs/refactor/mapscreen/15-divergence-register.md:2440` |
| `docs/refactor/mapscreen/15-divergence-register.md:2441` |
| `.claude/skills/detour-file-split/SKILL.md:21` |
| `.claude/skills/detour-file-split/SKILL.md:62` |
| `.claude/skills/detour-file-split/SKILL.md:164` |
| `.claude/skills/detour-trip-data/SKILL.md:208` |
| `.claude/skills/detour-shared-core/SKILL.md:208` |
| `.claude/skills/detour-shared-core/SKILL.md:254` |
| `.claude/skills/detour-adb/SKILL.md:212` |
| `.claude/skills/detour-compose-state-hazards/SKILL.md:10` |
| `.claude/skills/detour-compose-state-hazards/SKILL.md:381` |

Notably, `.claude/skills/detour-file-split/SKILL.md:62` currently states the
opposite convention outright: *"`package com.jellemax.detour.ui`. No new
subpackage, no `ui.map`, no `ui.settings`."* — i.e. the existing house skill
that governs file-splitting already codifies the flat-package convention as a
rule, not just an observed pattern (see §7).

---

## 7. Precedent inside this repo for nested packages

Command:
```
find app/src shared/src iosApp -type d -path "*com/jellemax/detour/*/*" 2>/dev/null
```
Result: **empty**. No Kotlin package anywhere in this repository — in any
source set, in `app/` or `shared/` — has a subpackage. Every package is one
flat directory of `.kt` files.

Per-package file counts, `app/src/main/java/com/jellemax/detour/`:

| Package | File count |
|---|---|
| ui | 59 |
| tracking | 20 |
| update | 11 |
| map | 10 |
| notif | 10 |
| car | 6 |
| data | 5 |
| nav | 2 |
| net | 2 |
| audio | 2 |
| perf | 2 |
| auth | 2 |
| convoy | 1 |
| obd2 | 1 |
| ble | 1 |
| media | 1 |

Per-package file counts, `shared/src/commonMain/kotlin/com/jellemax/detour/`
(only 3 packages exist here, plus `drive` which wasn't sized separately):

| Package | File count |
|---|---|
| data | 64 |
| presentation | 25 |

`iosApp/` is Swift, organized as `iosApp/Detour` and `iosApp/DetourTests` —
not a Kotlin package tree, checked but not comparable.

**Finding**: `shared/.../data` (64 files) is already larger than `ui/` (59
files) and remains a single flat package. `tracking/` (20) and `update/` (11)
are the largest flat packages in `app/`. No package in this codebase, at any
file count, is currently organized into subpackages — the deepest any package
goes, anywhere in the repo, in any source set, is
`com/jellemax/detour/<name>/*.kt`. The strongest same-repo precedent is
therefore that flat packages — including large ones — are this project's
established convention, and a nested `ui/` restructure would be the first
instance of subpackaging anywhere in the codebase, not an extension of an
existing pattern.

