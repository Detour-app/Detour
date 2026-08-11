# Proposal 4 — Domain-service extraction

**Thesis.** MapScreen.kt is not 3,193 lines of UI. It is ~1,545 lines of UI with a
navigation app hidden inside it. Roughly 934 lines are domain logic — GPS state
machines, prefetch/throttle policy, easing math, vote resolution, arrival and
reroute rules — that happens to be spelled as `LaunchedEffect` and `var … by
remember`. Move those downward into plain Kotlin driven by `Flow<Fix>` in and
`StateFlow` out, and MapScreen keeps Compose glue and MapLibre wiring, the logic
becomes unit-testable, and Android Auto stops carrying copies of it.

**Headline number, stated up front so nobody has to catch me at it:** this
proposal removes ~800 lines of body plus ~38 now-dead imports, ≈ **838 lines
(26%)**. MapScreen.kt lands at **≈ 2,355 lines** and is still the largest file in
the app. Domain extraction is necessary and it is not sufficient. The remaining
1,545 UI lines need a separate component split (§7) that this proposal does not
do.

One piece of context the brief did not include, and which changes the shape of
the answer: **this repo already has a platform-free core module**,
`:shared` (`shared/src/commonMain/kotlin/com/jellemax/detour/data/`, 38 files),
consumed by the Android app *and* by a SwiftUI app in `iosApp/`. `Settings`,
`RoadRoulette`, `SpeedCameras`, `NavEngine`, `RoutingServer`, `TraceStore`,
`FriendFog`, `CircleFixes` all live there, not in `app/`. So the destination for
extracted logic is not a new `app/` package — it is `:shared`, where the
convention, the test source set (`commonTest`), and a second consumer already
exist.

---

## 1. Cross-cutting concerns I identify in MapScreen.kt

These are the threads that run *through* the file rather than sitting in one
place. Each one is a candidate service.

| # | Concern | Where it lives today (MapScreen.kt) | Why it is cross-cutting |
|---|---|---|---|
| 1 | **Ambient speed-limit snapping + prefetch** | consts none; `1018–1057` | Prefetch throttle, edge-of-area refetch, heading-aware snap, and a 3-miss clear rule, all inside one `collect`. Duplicated at `car/SpinScreen.kt:93–99, 255–295`. |
| 2 | **Speed-camera prefetch** | `1058–1084`, push at `1085–1090` | Same prefetch/throttle shape as #1, different radius. Duplicated at `car/NavScreen.kt:131–137, 378–392`. |
| 3 | **Camera-warning arming** | `1134–1168` | Path-dependent `warnedAt` latch + wedge test + over-limit test. Duplicated at `car/NavScreen.kt:396–417`. |
| 4 | **Trajectcontrole section state machine** | `281–315` (gate) + `1169–1231` (machine) | Enter/integrate/exit with four exit conditions. **Not present on the car at all** — the car cannot have it without copying 63 lines. |
| 5 | **Camera easing math** | `221–255` (consts + `smoothBearing`), `1232–1246` (targets), `1265–1334` (loop) | Duplicated near-verbatim at `car/CarMapRenderer.kt:53–69, 380–420, 468–475`, including a second copy of `smoothBearing`. |
| 6 | **Speedometer easing** | `240–247`, `1247–1263` | Same exponential ease, different tau; sits next to #5 and is written twice in one file. |
| 7 | **Spin orchestration** | `261–267`, `1392–1514` | Curvy round-trip roll-and-pick, three-candidate parallel roll, Overpass fallback, error precedence. **`1472–1489` is an inline re-implementation of `pickThreeCandidates`, which already exists in `shared/…/data/SpinPicker.kt:27` and is already called from `iosApp/Detour/SpinModel.swift:129`.** Divergence has already happened. |
| 8 | **Group-spin vote resolution** | `321–367` (wire mapping + tie-break), `817–824`, `842–871` | Distributed-consensus rule with a deliberately asymmetric sharer/receiver split. Pure, subtle, entirely untested. |
| 9 | **Navigation session** (progress → arrival → reroute) | `970–1017`, `1344–1391` | `car/NavScreen.kt:242` literally says *"Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect"* — a comment standing in for a shared function. |
| 10 | **Follow-camera park/resume policy** | `269–275`, `665–695` (park), `696–713` (resume) | Speed + quiet-period + "not while candidates are up" rule, spread across a touch listener and an effect. |
| 11 | **Circle-fix polling** | `276–279`, `1098–1119` | Duplicated at `car/CarMapRenderer.kt:74–78, 139–151`, comment included (*"see MapScreen's CIRCLE_FIX_POLL_MS"*). |
| 12 | **Spin-result process-scoped holder** | `369–415` | A `MutableStateFlow` singleton and a cross-screen seeding function living in a UI file, imported by `ui/RoutesScreen.kt`. |
| 13 | **Nav-app dispatch policy** | `2151–2165` (`navAppUsableDirectly`) | Already pure; already extracted from its callers; just in the wrong file. |
| 14 | **Search debounce + recents merge** | `1865–1893` | Debounce, dedupe, recents-on-top ordering, inside `SearchDialog`. |
| 15 | **Permission choreography** | `443–445`, `736–802`, `1798–1808` | Android-only, stays. Listed so the classification is complete. |
| 16 | **MapLibre imperative wiring** | `587–596`, `627–664`, `872–933`, `934–969`, `1085–1097`, `1120–1133` | Android-only, stays. |

Two smaller ones worth naming because they are bugs the extraction would
surface, not just tidy:

- **#1 and #2 suspend the fix collector.** `MapScreen.kt:1037` and `:1075` do
  `withContext(Dispatchers.IO) { … }` *inside* `TripTrackingService.lastFix.collect`.
  Because `lastFix` is a conflating `StateFlow` with a sequential collector, a
  slow Overpass mirror stalls the whole effect and every fix that lands
  meanwhile is dropped. The car surface already hit this and fixed it — see the
  KDoc at `car/NavScreen.kt:365–377` and `car/SpinScreen.kt:255–263` ("Same fix
  as `NavScreen.checkCameras`"). The phone never got the fix. One extracted
  service ends the divergence.
- **Vestigial `Dispatchers.IO` wrappers.** `shared/` uses zero `Dispatchers.*`
  (verified: `grep -rl Dispatchers shared/src/commonMain` → 0 files); everything
  is suspending Ktor since the HttpURLConnection port (`shared/…/data/Http.kt:26–31`).
  The purely network-bound wrappers in MapScreen (`:1037`, `:1075`, `:1113`,
  `:1379`, `:1477`, `:1883`) are therefore leftovers. The file-bound ones
  (`:1407 ExploredArea.load`, `:570 SyncClient.sync`) are not — okio file reads
  still block. Worth checking one by one during extraction, not sweeping.

---

## 2. Classification of every block

Method: every line of the file is assigned to exactly one bucket, ranges are
contiguous and non-overlapping, and they sum to 3,193 (arithmetic in
`/tmp/.../cls.py`, reproducible from the table). Where a block is genuinely
mixed I assign it to its **dominant** class and say so in the notes — I do not
get to count a 14-line function as 14 domain lines when 6 of them are
`mapLibreMap?.let { cameraForPoints(...) }`. §2.3 nets that out.

I use a fifth bucket **(e) imports/preamble**, because filing 209 lines of
`import androidx.compose.…` under "genuine UI" would inflate (d) and filing them
under (b) would inflate (b). They are neither; they are an artefact of file size.

### 2.1 Per-block table

| Lines | n | Class | Block |
|---|---:|---|---|
| 1–209 | 209 | e | package + imports |
| 210–212 | 3 | d | `DIRECTION_NAMES` |
| 213–220 | 8 | d | `TravelMode.icon` (used by 3 other screens) |
| 221–231 | 11 | **a** | `smoothBearing` |
| 232–255 | 24 | **a** | easing tau/eps constants |
| 256–260 | 5 | c | `FIT_PADDING_PX` |
| 261–268 | 8 | **a** | `CURVY_CANDIDATES` |
| 269–275 | 7 | **a** | `CAM_RESUME_*` |
| 276–280 | 5 | **a** | `CIRCLE_FIX_POLL_MS` |
| 281–289 | 9 | **a** | `SECTION_GATE_METERS` / `SECTION_WEDGE_DEG` |
| 290–315 | 26 | **a** | `sectionExitGate` |
| 316–320 | 5 | d | `CANDIDATE_COLORS` |
| 321–344 | 24 | **a** | `GroupSpin.asRouteCandidates` |
| 345–356 | 12 | **a** | `List<RouteCandidate>.asSpinCandidates` |
| 357–368 | 12 | **a** | `leadingSpinIndex` |
| 369–386 | 18 | **a** | `SpinResult` + `SpinResultHolder` |
| 387–415 | 29 | **a** | `seedRouteNavigation` |
| 416–418 | 3 | d | `BottomCard` enum |
| 419–436 | 18 | b | composable preamble (`LocalContext`, densities, scope, haptics) |
| 437–442 | 6 | b | `SavedPlaces.ensureLoaded` + `savePinTarget` |
| 443–445 | 3 | b | `showBgLocationDisclosure` |
| 446–466 | 21 | **a** | spin/mode/radius/candidate state |
| 467–469 | 3 | **a** | holder-sync effect |
| 470–502 | 33 | b | store collectors (`collectAsStateWithLifecycle` ×15) |
| 503–513 | 11 | **a** | convoy id → name resolution |
| 514–540 | 27 | **a** | nav / speed-limit / camera / section state |
| 541–554 | 14 | **a** | camera-target state + `cameraActive`/`following` |
| 555–560 | 6 | **a** | `myLocation` accuracy gate (≤100 m) |
| 561–565 | 5 | **a** | min-radius clamp |
| 566–579 | 14 | b | `SyncClient.sync()` on launch |
| 580–586 | 7 | **a** | FriendFog refresh/clear on `shareFog` |
| 587–596 | 10 | c | `MapView` / `FogView` / overlay `remember` |
| 597–626 | 30 | b | lifecycle observer → `setUiVisible` + PTT safety |
| 627–649 | 23 | c | MapView lifecycle + uiSettings |
| 650–664 | 15 | c | style reload, `MapOverlays`, fog attach |
| 665–695 | 31 | b | `MotionEvent` touch listener (park camera) |
| 696–713 | 18 | **a** | camera resume-from-speed policy |
| 714–735 | 22 | b | `fetchLocation` (FusedLocation) |
| 736–759 | 24 | b | bg-location + mic permission launchers |
| 760–802 | 43 | b | permission bootstrap |
| 803–816 | 14 | **a** | `choose(candidate)` |
| 817–824 | 8 | **a** | `displayCandidates` |
| 825–841 | 17 | **a** | `commitSpinCandidate` |
| 842–871 | 30 | **a** | group-spin vote resolution |
| 872–903 | 32 | c | overlay render push |
| 904–916 | 13 | c | icon / route-colour push |
| 917–933 | 17 | c | fog feed (2 effects) |
| 934–969 | 36 | c | map click / long-click / camera listeners |
| 970–981 | 12 | **a** | `stopNavigation` |
| 982–1017 | 36 | **a** | `startNavigation` |
| 1018–1057 | 40 | **a** | ambient speed limit |
| 1058–1084 | 27 | **a** | speed-camera prefetch |
| 1085–1090 | 6 | c | `setCameras` |
| 1091–1097 | 7 | c | `setFriends` |
| 1098–1119 | 22 | **a** | circle-fix poll |
| 1120–1123 | 4 | c | `setCircleMembers` |
| 1124–1133 | 10 | c | `fogView.peers` |
| 1134–1168 | 35 | **a** | camera-warning arming |
| 1169–1231 | 63 | **a** | trajectcontrole state machine |
| 1232–1246 | 15 | **a** | camera targets per fix |
| 1247–1264 | 18 | **a** | speedometer ease loop |
| 1265–1334 | 70 | **a** | camera ease loop |
| 1335–1343 | 9 | b | BLE `sendStats` |
| 1344–1391 | 48 | **a** | nav progress / arrival / reroute |
| 1392–1514 | 123 | **a** | `spin()` |
| 1515–1528 | 14 | **a** | `selectMode` |
| 1529–1797 | 269 | d | `Scaffold` tree |
| 1798–1837 | 40 | d | dialog hosts |
| 1838–1864 | 27 | d | `SearchDialog` head |
| 1865–1894 | 30 | **a** | search debounce + recents merge |
| 1895–1959 | 65 | d | `SearchDialog` UI |
| 1960–1997 | 38 | d | `ShortcutChips` |
| 1998–2029 | 32 | d | `BackgroundLocationDisclosure` |
| 2030–2056 | 27 | d | `SavePinDialog` |
| 2057–2086 | 30 | d | `Pill` |
| 2087–2101 | 15 | d | `SegmentedPillRow` |
| 2102–2122 | 21 | d | `ScrollingPillRow` |
| 2123–2150 | 28 | b | `launchNav` (Intent dispatch) |
| 2151–2165 | 15 | **a** | `navAppUsableDirectly` |
| 2166–2187 | 22 | b | `handleGoTap` (threads `Context`) |
| 2188–2233 | 46 | d | `NavMenuItems` |
| 2234–2280 | 47 | d | `NavIconButton` |
| 2281–2367 | 87 | d | `SpinDock` |
| 2368–2548 | 181 | d | `SpinSheet` |
| 2549–2682 | 134 | d | `CandidatesCard` |
| 2683–2698 | 16 | d | `ModeBar` |
| 2699–2776 | 78 | d | `MapTopChrome` |
| 2777–2826 | 50 | d | `SearchPill` |
| 2827–2852 | 26 | d | `ConvoyPill` |
| 2853–2880 | 28 | d | `GlassRailButton` |
| 2881–2898 | 18 | d | `EndTripButton` |
| 2899–2955 | 57 | d | `PushToTalkButton` |
| 2956–3013 | 58 | d | `SpeedHud` |
| 3014–3047 | 34 | d | `SectionAverageChip` |
| 3048–3100 | 53 | d | `NavButton` |
| 3101–3140 | 40 | d | `ActiveTripCard` |
| 3141–3149 | 9 | d | `StatItem` |
| 3150–3163 | 14 | b | `navigateRoundTrip` |
| 3164–3173 | 10 | b | `navigateGoogleMaps` |
| 3174–3187 | 14 | b | `navigateWaze` |
| 3188–3193 | 6 | b | `navigateGeo` |

### 2.2 Totals

| Class | Lines | Share |
|---|---:|---:|
| **(a) domain logic** | **934** | **29.3%** |
| (b) Android/platform glue | 327 | 10.2% |
| (c) MapLibre imperative glue | 178 | 5.6% |
| (d) genuine UI | 1,545 | 48.4% |
| (e) imports/preamble | 209 | 6.5% |
| **Total** | **3,193** | 100% |

### 2.3 What actually leaves — the honest net

(a) = 934 is the *upper bound*, not the deliverable. Several (a) blocks leave a
residue behind: a `collectAsStateWithLifecycle` line where a `var … by remember`
was, a `cameraForPoints` call the service must not make, a Compose
`withFrameNanos` loop that cannot move. Per-block estimate:

| Block | (a) lines | leaves | residue kept in UI |
|---|---:|---:|---|
| `spin()` `1392–1514` | 123 | 105 | 18 — haptics, `cameraForPoints`, error string binding |
| camera ease loop `1265–1334` | 70 | 40 | 30 — the `withFrameNanos` loop + `setCamera` |
| section machine `1169–1231` | 63 | 60 | 3 |
| nav progress `1344–1391` | 48 | 42 | 6 — `NavRelay`/`BleNavServer` pushes |
| ambient limit `1018–1057` | 40 | 38 | 2 |
| camera warning `1134–1168` | 35 | 30 | 5 — `ToneGenerator` binding |
| `startNavigation` `982–1017` | 36 | 30 | 6 |
| group vote `842–871` | 30 | 29 | 1 |
| search debounce `1865–1894` | 30 | 25 | 5 |
| `seedRouteNavigation` `387–415` | 29 | 29 | 0 |
| camera prefetch `1058–1084` | 27 | 25 | 2 |
| state decls `446–466`,`514–540`,`541–554` | 62 | 41 | 21 — collectors replace them |
| circle poll `1098–1119` | 22 | 20 | 2 |
| speed ease `1247–1264` | 18 | 10 | 8 — frame loop stays |
| everything else (25 blocks) | 301 | 276 | 25 |
| **Total** | **934** | **800** | **134** |

Plus ~38 imports that become unused (`ToneGenerator`, `AudioManager`,
`IOException`, `Geocoder`, `Groups`, `MemberFix`, `CircleFixes`, `ExploredArea`,
`RoundTripPlanner`, `Curviness`, `pickCandidate`, `SpeedCameras`, `RoadRoulette`,
`NavEngine`, `RoutingServer`, `SyncClient`, `RecentSearchStore`, `GroupSpin`,
`SpinCandidate`, `BleNavServer`, `NavRelay`, `MutableStateFlow`, `Random`,
`exp`, and 10 `kotlinx.coroutines.*` entries).

> **Net: −838 lines from MapScreen.kt → ≈ 2,355 lines.**
> Across the repo the line count *grows* by roughly +500, because ~1,340 lines
> of new files carry package declarations, imports, class scaffolding and the
> (excellent, and preserved) existing comments. This proposal trades total lines
> for testability and reuse. If the goal is "fewer lines in the repo", it fails.

---

## 3. The pattern, stated precisely

### 3.1 Shape

Every extracted service obeys one of two shapes.

**Shape A — pure decision object.** No coroutines, no clock of its own, no I/O.
State is either absent or an explicit field mutated only by `onFix(...)`.

```kotlin
class SectionAverageTracker(private val clock: () -> Long = ::nowMs) {
    private val _state = MutableStateFlow(State(null, null))
    val state: StateFlow<State> = _state
    fun onFix(pos: LatLon, headingDeg: Double?, speedMps: Double,
              sections: List<SpeedCameras.Section>)
    fun reset()
}
```

Deterministic under an injected clock, therefore fully unit-testable. This is
where the highest-risk code goes: the four GPS state machines (§1 #1, #3, #4,
#10).

**Shape B — flow-driven feed.** Owns network/throttle policy, exposes StateFlow,
and — critically — **does not own a scope**:

```kotlin
class SpeedCameraFeed(
    private val fetch: suspend (LatLon) -> SpeedCameras.Result? = SpeedCameras::near,
    private val clock: () -> Long = ::nowMs,
) {
    val cameras: StateFlow<List<SpeedCameras.Camera>>
    val sections: StateFlow<List<SpeedCameras.Section>>

    /** Collects [fixes] until the caller's scope is cancelled. Never returns. */
    suspend fun run(fixes: Flow<Fix?>)
}
```

`run(fixes)` is a `suspend fun` that never returns. It is *always* invoked from a
caller-owned scope. That single rule is what makes the lifecycle question below
answerable.

### 3.2 `object` singleton vs instantiable `class`

The project convention is `object` + `StateFlow` (`Settings`, `FriendFog`,
`TraceStore`, `SavedPlaces`, `ConvoyLiveClient`). I am deliberately **not**
following it for most services, and I want to be explicit that this is a
departure:

| Use `object` when | Use `class` when |
|---|---|
| The state is genuinely process-global and has exactly one correct value at a time (`SpinResultHolder`, `CircleFixFeed`) | The state belongs to a session or a surface (`NavSession`, `SpinSession`, `CameraEase`, `SectionAverageTracker`, `CameraWarner`, `SpeedLimitService`, `SpeedCameraFeed`) |
| Two surfaces must agree (phone + car showing the same circle members) | Two surfaces may legitimately disagree (car nav running while the phone map is parked — which `car/NavScreen.kt:74–79` explicitly documents as intended) |

Rationale: `object` state cannot be reset between tests without a `@Before`
teardown hook, cannot be instantiated twice for the phone-and-car case, and
cannot take an injected clock or fetcher without a mutable global. Three of the
four hardest-to-test blocks (`1134–1168`, `1169–1231`, `1018–1057`) need exactly
those injections. `class` it is. `SpinResultHolder` stays an `object` because it
already is one and `ui/RoutesScreen.kt` depends on that.

### 3.3 Who starts and stops them

Nobody "starts" a service. The caller collects it:

```kotlin
// MapScreen.kt, after extraction
val cameras = remember { SpeedCameraFeed() }
val warner  = remember { CameraWarner() }
LaunchedEffect(cameras) { cameras.run(TripTrackingService.lastFix) }
LaunchedEffect(warner)  { warner.run(TripTrackingService.lastFix, cameras.cameras, limit) }
```

`remember` ties the instance to the composition; `LaunchedEffect` ties the
coroutine to the composition. Leaving the screen cancels both. This is
**identical to today's lifetime** — the current `LaunchedEffect` blocks at
`1018`, `1062`, `1145`, `1174` already die with the composition — so the
extraction introduces no new lifetime behaviour.

For the two services that must be shared between the phone map and the car
surface (`CircleFixFeed`, and optionally `SpeedCameraFeed`), a refcount:

```kotlin
object DriveServices {
    val circleFixes = CircleFixFeed()
    /** Runs [circleFixes] for as long as at least one caller is attached. */
    suspend fun attachCircleFixes(): Nothing   // caller's scope owns it; refcounted internally
}
```

### 3.4 How they avoid running with no UI present

Three layers, in order of how much I trust them:

1. **No service owns a `CoroutineScope`.** There is no `CoroutineScope(…)`
   inside any extracted class. If nobody is collecting, nothing runs. This is
   the load-bearing rule and it is enforceable by grep in review.
2. **The fix source is already throttled by the OS-facing owner.**
   `TripTrackingService` drops to batched/passive fixes when
   `setUiVisible(context, false)` is called (`MapScreen.kt:597–626`,
   `car/SpinScreen.kt:110–120`). A service collecting `lastFix` with the screen
   off gets a trickle, not 1 Hz.
3. **Time-driven loops get an explicit gate.** `CircleFixFeed` polls on a
   `delay(120_000)` timer, not on fixes — that one *will* keep hitting the
   network if collected forever. It gets the refcount above, and its `run()`
   takes the visibility flag so it parks rather than polls.

Note honestly: MapScreen's composition **survives backgrounding** (see the
comment at `MapScreen.kt:598–601`). So today, and after extraction, these loops
keep collecting while the app is in the background. Extraction does not make
that worse; it also does not fix it, and layer 3 is the only place where a
careless implementation could make it worse.

---

## 4. Proposed file layout

New package `com.jellemax.detour.drive` inside `:shared`. It is a new package
rather than more files in `data/` because `data/` is already 38 files of stores
and clients, and these are *sessions and policies* — a different kind of thing.

### 4.1 New files in `:shared` (Android-free, iOS-reusable)

| Path | Moved from | ~lines |
|---|---|---:|
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Fix.kt` | `app/…/tracking/TripTrackingService.kt:92–100` (`data class Fix`) | 22 |
| `shared/…/drive/CameraEase.kt` | MapScreen `221–255`, `1232–1246`, `1265–1334` (math half) + `car/CarMapRenderer.kt:53–69, 398–415, 468–475` | 150 |
| `shared/…/drive/SpeedEase.kt` | MapScreen `240–247`, `1247–1263` (math half) | 45 |
| `shared/…/drive/SpeedLimitService.kt` | MapScreen `1018–1057` + `car/SpinScreen.kt:255–295` | 115 |
| `shared/…/drive/SpeedCameraFeed.kt` | MapScreen `1058–1084` + `car/NavScreen.kt:378–392` | 90 |
| `shared/…/drive/CameraWarner.kt` | MapScreen `1134–1168` + `car/NavScreen.kt:396–417` | 85 |
| `shared/…/drive/SectionAverageTracker.kt` | MapScreen `281–315`, `1169–1231` | 145 |
| `shared/…/drive/NavPolicy.kt` | MapScreen `1359–1389` + `car/NavScreen.kt:242–252` | 70 |
| `shared/…/drive/NavSession.kt` | MapScreen `970–1017`, `1344–1391` (policy half) | 150 |
| `shared/…/drive/SpinSession.kt` | MapScreen `261–267`, `369–415`, `803–841`, `1392–1514` | 210 |
| `shared/…/drive/FollowCamera.kt` | MapScreen `269–275`, `696–713` + policy half of `665–695` | 60 |
| `shared/…/drive/CircleFixFeed.kt` | MapScreen `276–280`, `1098–1119` + `car/CarMapRenderer.kt:74–78, 139–151` | 55 |
| `shared/…/drive/PlaceSearch.kt` | MapScreen `1865–1893` | 60 |
| `shared/…/drive/NavAppPolicy.kt` | MapScreen `2151–2165` | 40 |
| **modified** `shared/…/data/SpinPicker.kt` (103 lines today) | MapScreen `1414–1448` → new `curviestRoundTrip()`; MapScreen `1472–1489` **deleted** in favour of existing `pickThreeCandidates` (`:27`) | +45 |

### 4.2 New file in `app/`

| Path | Moved from | ~lines |
|---|---|---:|
| `app/…/net/GroupSpinRules.kt` | MapScreen `321–367`, `817–824`, `842–871` | 110 |

Why not `:shared`: `GroupSpin` and `SpinCandidate` are declared in
`app/…/net/ConvoyLiveClient.kt:51,72`, and iOS has its own
`iosApp/Detour/ConvoyLiveClient.swift`. Moving the wire types is a separate
decision with its own blast radius; this proposal does not take it. Consequence:
this is the one service that is **not** iOS-reusable, and its tests go in
`app/src/test/` under plain JUnit4 rather than `commonTest`.

### 4.3 Files deleted from / shrunk on the car side

| Path | Lines removed |
|---|---:|
| `app/…/car/CarMapRenderer.kt` (easing + `smoothBearing` + circle poll) | ~73 |
| `app/…/car/NavScreen.kt` (cameras, warning, arrival/reroute gate) | ~62 |
| `app/…/car/SpinScreen.kt` (ambient limit) | ~46 |

### 4.4 Resulting size

```
MapScreen.kt              3,193  →  ~2,355   (−838)
CarMapRenderer.kt           663  →    ~590   (−73)
NavScreen.kt                593  →    ~531   (−62)
SpinScreen.kt               352  →    ~306   (−46)
new :shared files              0  →  ~1,297
new app/net file               0  →    ~110
SpinPicker.kt                 ~140 →   ~185   (+45)
─────────────────────────────────────────────
repo net                              ≈ +510
```

---

## 5. Representative Kotlin signatures

Real names from the current code throughout.

```kotlin
// shared/src/commonMain/kotlin/com/jellemax/detour/data/Fix.kt
package com.jellemax.detour.data

/** Latest GPS fix. Moved out of TripTrackingService so the shared drive
 *  services can be driven by it without depending on :app. */
data class Fix(
    val lat: Double, val lon: Double,
    val speedMps: Double, val bearingDeg: Float?,
    val accuracyMeters: Float, val timeMs: Long,
) { val at: LatLon get() = LatLon(lat, lon) }
```

```kotlin
// shared/…/drive/SpeedLimitService.kt
package com.jellemax.detour.drive

/**
 * Ambient posted limit while just driving. Prefetches every tagged way in a
 * ~1.5 km circle once, then snaps locally on every fix, so the sign flips the
 * instant you cross onto a new road.
 *
 * The fetch runs on [scope], not on the collector — MapScreen.kt:1037 awaited
 * Overpass inline, which stalled the fix loop and conflated away every fix that
 * landed meanwhile. car/SpinScreen.kt already learned this; this is the fix,
 * once, for both.
 */
class SpeedLimitService(
    private val fetchWays: suspend (LatLon) -> List<RoadRoulette.SpeedLimitWay> =
        { RoadRoulette.speedLimitWays(it) },
    private val clock: () -> Long = ::nowMs,
) {
    private val _limitKmh = MutableStateFlow<Double?>(null)
    val limitKmh: StateFlow<Double?> = _limitKmh

    /** Pure half: the snap + 3-miss-clear rule. Testable with no coroutines. */
    fun onFix(pos: LatLon, headingDeg: Double?, speedMps: Double): FetchRequest?

    /** Collects until cancelled. Fetches on [scope] so a slow mirror can't stall it. */
    suspend fun run(fixes: Flow<Fix?>, scope: CoroutineScope)

    fun reset()   // car/SpinScreen.kt:118-120 does this on screen re-entry
}
```

```kotlin
// shared/…/drive/CameraWarner.kt
/**
 * One chime per camera: [warnedAt] holds the camera last sounded for and clears
 * once it is behind us. Silent when the limit is unknown — we can't judge
 * "too fast". Emits rather than plays: ToneGenerator/CarToast/NavVoice are the
 * caller's business (MapScreen.kt:1163, car/NavScreen.kt:408-414).
 */
class CameraWarner {
    private val _warnings = MutableSharedFlow<SpeedCameras.Camera>(extraBufferCapacity = 1)
    val warnings: SharedFlow<SpeedCameras.Camera> = _warnings

    /** True when this fix should chime. Pure; no suspension, no I/O. */
    fun onFix(
        pos: LatLon, headingDeg: Double?, speedMps: Double,
        cameras: List<SpeedCameras.Camera>, limitKmh: Double?,
    ): SpeedCameras.Camera?
}
```

```kotlin
// shared/…/drive/SectionAverageTracker.kt
/**
 * Average speed through a trajectcontrole. Enter at one end heading for the
 * other, integrate GPS distance over elapsed time, leave at that far end (or on
 * overshoot / timeout). Pure and clock-injected: the whole state machine from
 * MapScreen.kt:1169-1231, verbatim, with `now` as a parameter.
 */
class SectionAverageTracker(private val clock: () -> Long = ::nowMs) {
    data class State(val averageKmh: Double?, val limitKmh: Double?)

    private val _state = MutableStateFlow(State(null, null))
    val state: StateFlow<State> = _state

    fun onFix(
        pos: LatLon, headingDeg: Double?, speedMps: Double,
        sections: List<SpeedCameras.Section>,
    )

    companion object {
        const val GATE_METERS = 60.0        // was MapScreen SECTION_GATE_METERS
        const val WEDGE_DEG   = 75.0        // was MapScreen SECTION_WEDGE_DEG
        /** The far end of [section] if this fix is entering it. Was MapScreen.kt:300. */
        fun exitGate(section: SpeedCameras.Section, pos: LatLon, headingDeg: Double): List<LatLon>?
    }
}
```

```kotlin
// shared/…/drive/CameraEase.kt
/**
 * Eases a camera pose toward the last fix: each step closes the same fraction
 * of the gap, ~63% in one tau. Replaces two copies — MapScreen.kt:1265-1334
 * (Compose withFrameNanos) and car/CarMapRenderer.kt:380-420 (delay(33)) —
 * and two copies of smoothBearing (MapScreen.kt:224, CarMapRenderer.kt:470).
 *
 * The *loop* stays on each platform; only the math and the moved-enough test
 * move here. That is a real limit of this extraction, not an oversight.
 */
class CameraEase {
    data class Pose(val lat: Double, val lon: Double, val zoom: Double, val bearingDeg: Float)

    fun retarget(fix: Fix, baseZoom: Double, distanceToTurnMeters: Double)
    fun seed(pose: Pose)
    /** Advances by [dtSeconds]; null when the change is under all three epsilons. */
    fun step(dtSeconds: Double): Pose?

    companion object {
        const val POS_TAU = 0.35; const val BEARING_TAU = 0.5; const val ZOOM_TAU = 1.2
        const val POS_EPS_DEG = 2e-6; const val ZOOM_EPS = 2e-3; const val BEARING_EPS_DEG = 0.1f
        fun smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float
    }
}
```

```kotlin
// shared/…/drive/NavPolicy.kt
/**
 * What the next fix means for a running route. car/NavScreen.kt:242 says
 * "Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect" —
 * this is that sentence turned into a function both call.
 */
object NavPolicy {
    sealed interface Decision {
        data object Continue : Decision
        data object Arrived  : Decision
        data object Reroute  : Decision
    }
    const val ARRIVE_METERS = 40.0
    const val OFF_ROUTE_METERS = 60.0
    const val REROUTE_COOLDOWN_MS = 15_000L

    fun decide(
        progress: NavEngine.Progress,
        destination: LatLon?,          // null = round trip: never "arrives", never reroutes
        rerouting: Boolean,
        nowMs: Long,
        lastRerouteMs: Long,
    ): Decision
}
```

```kotlin
// shared/…/drive/NavSession.kt
class NavSession(
    private val config: ServerConfig,
    private val clock: () -> Long = ::nowMs,
) {
    sealed interface Event {
        data object Arrived : Event
        data object Rerouting : Event
        data class Failed(val message: String) : Event
    }
    val route: StateFlow<RouteResult?>
    val progress: StateFlow<NavEngine.Progress?>
    val rerouting: StateFlow<Boolean>
    val events: SharedFlow<Event>

    suspend fun start(origin: LatLon, destination: LatLon?, mode: TravelMode): Result<Unit>
    fun startWithLoop(loop: RouteResult): Boolean   // MapScreen.kt:993-1001
    fun stop()
    suspend fun run(fixes: Flow<Fix?>, scope: CoroutineScope)
}
```

```kotlin
// shared/…/drive/SpinSession.kt
class SpinSession(private val config: ServerConfig) {
    sealed interface Outcome {
        data class Loop(val route: RouteResult, val warning: String?) : Outcome
        data class Candidates(val candidates: List<RouteCandidate>) : Outcome
        data class Failed(val message: String) : Outcome
    }
    val spinning: StateFlow<Boolean>

    /** Round-trip rolls the curviest of [CURVY_CANDIDATES]; otherwise three
     *  candidates in parallel via the existing pickThreeCandidates(). */
    suspend fun spin(
        origin: LatLon, mode: TravelMode,
        radiusKm: Float, minRadiusKm: Float,
        poiKind: PoiKind, directionDeg: Float?,
    ): Outcome
}

// added to shared/…/data/SpinPicker.kt, next to pickThreeCandidates()
suspend fun curviestRoundTrip(
    config: ServerConfig, origin: LatLon, tripMeters: Double,
    headingDeg: Double?, avoidSmallRoads: Boolean, rolls: Int = 3,
): RouteResult
```

```kotlin
// app/…/net/GroupSpinRules.kt   (app, not shared — GroupSpin lives in net/)
object GroupSpinRules {
    sealed interface Resolution {
        data object Wait : Resolution
        /** Every device commits a one-candidate offer off the same frame. */
        data class Commit(val index: Int) : Resolution
        /** Sharer only: send the leader back out as a one-candidate offer. */
        data class Close(val index: Int) : Resolution
    }
    fun asRouteCandidates(spin: GroupSpin): List<RouteCandidate>
    fun asSpinCandidates(candidates: List<RouteCandidate>): List<SpinCandidate>
    /** Ties — including "nobody's voted yet" — go to the lowest index. */
    fun leadingIndex(votes: Map<String, Int>, candidateCount: Int): Int
    fun resolve(
        offer: GroupSpin?, votes: Map<String, Int>,
        livePeers: Set<String>, me: String?,
    ): Resolution
}
```

```kotlin
// shared/…/drive/FollowCamera.kt
object FollowCamera {
    const val RESUME_SPEED_MPS = 3.0
    const val RESUME_QUIET_MS = 8_000L
    /** Driving off takes the camera back — but not while a spin is on screen. */
    fun shouldResume(
        speedMps: Double, nowMs: Long, lastGestureMs: Long,
        spinning: Boolean, hasCandidates: Boolean, hasSpinOffer: Boolean,
    ): Boolean
}
```

```kotlin
// shared/…/drive/CircleFixFeed.kt
object CircleFixFeed {
    const val POLL_MS = 120_000L   // matches TripTrackingService.CIRCLE_SYNC_INTERVAL_MS
    val fixes: StateFlow<List<MemberFix>>
    /** Polls while at least one caller is running it; last known positions are
     *  kept on failure rather than blanking the map. */
    suspend fun run(username: StateFlow<String>)
}
```

```kotlin
// shared/…/drive/NavAppPolicy.kt
object NavAppPolicy {
    fun usableDirectly(
        app: Settings.NavApp, inAppAvailable: Boolean,
        route: List<LatLon>?, origin: LatLon?,
    ): Boolean
}
```

---

## 6. What remains in the UI layer

≈ 2,355 lines: 1,545 (d) + 327 (b) + 178 (c) + ~171 imports + ~134 domain
residue. Breakdown of the composable body after extraction: 844 lines (from
1,419), of which 309 are the `Scaffold` tree and dialog hosts and ~535 are
effect wiring, permissions and MapLibre listeners.

### 6.1 Proposed split of the remainder

Same package `com.jellemax.detour.ui`, new files. **Cost to note:** the moved
composables are all `private`; Kotlin has no package-private, so they become
`internal`. That is 24 visibility widenings — real, small, and worth stating.

| Path | Contents (source ranges) | ~lines |
|---|---|---:|
| `ui/MapScreen.kt` | signature, service instantiation, effect wiring, state collectors, dialog hosts (`419–554` res., `803–841` res., `1798–1837`) | 340 |
| `ui/MapContent.kt` | `Scaffold` + `Box` + top/bottom layout + `BottomCard` dispatch (`1529–1797`, `416–418`) | 300 |
| `ui/MapLibreWiring.kt` | MapView lifecycle, style reload, overlay pushes, click/touch listeners, the Compose camera loop shell (`256–260`, `587–596`, `627–695`, `872–969`, `1085–1133`, `1265–1334` res.) | 230 |
| `ui/MapPermissions.kt` | permission bootstrap, `fetchLocation`, launchers, `BackgroundLocationDisclosure` (`443–445`, `714–802`, `1998–2029`) | 135 |
| `ui/MapSpinCards.kt` | `SpinDock`, `SpinSheet`, `CandidatesCard` (`2281–2682`, `316–320`, `210–212`) | 410 |
| `ui/MapChrome.kt` | `MapTopChrome`, `SearchPill`, `ConvoyPill`, `GlassRailButton`, `EndTripButton`, `PushToTalkButton` (`2699–2955`) | 260 |
| `ui/MapDialogs.kt` | `SearchDialog` (UI half), `SavePinDialog`, `ShortcutChips` (`1838–1864`, `1895–1997`, `2030–2056`) | 195 |
| `ui/GoButton.kt` | `NavButton`, `NavIconButton`, `NavMenuItems`, `handleGoTap` (`2166–2280`, `3048–3100`) | 170 |
| `ui/NavAppIntents.kt` | `launchNav`, `navigateRoundTrip/GoogleMaps/Waze/Geo` (`2123–2150`, `3150–3193`) | 80 |
| `ui/SpeedHud.kt` | `SpeedHud`, `SectionAverageChip` (`2956–3047`) | 100 |
| `ui/TripCards.kt` | `ActiveTripCard`, `StatItem` (`3101–3149`) | 55 |
| `ui/Pills.kt` | `Pill`, `SegmentedPillRow`, `ScrollingPillRow` (`2057–2122`) | 70 |
| `ui/TravelModeIcons.kt` | `TravelMode.icon` (`213–220`) — used by `RoutesScreen`, `HistoryScreen`, `RouteEditorScreen` | 20 |
| `ui/ModeBar.kt` | `ModeBar` (`2683–2698`) | 25 |

That is 14 files with a median of ~170 lines. **This is a companion proposal's
work.** I list it because the brief asks how the remainder should be split, and
because a reviewer is entitled to see that the answer exists — not because
domain extraction delivers it.

---

## 7. Migration plan

Every step compiles and ships on its own. Ordered so the highest-value,
lowest-risk moves land first and the riskiest state machine lands only after the
harness that can test it exists.

| # | Step | Files touched | Δ MapScreen | Risk |
|---|---|---|---:|---|
| 0 | Move `data class Fix` to `shared/…/data/Fix.kt`; add the import in `TripTrackingService`. Cheaper than it looks: `grep -rn "Fix" app/src/main/java` shows the type is named **only inside `TripTrackingService.kt`** — every consumer reaches it through `TripTrackingService.lastFix` by inference. Pure move, no behaviour. | 2 | 0 | none |
| 1 | Add `curviestRoundTrip()` to `SpinPicker.kt`; point MapScreen `1414–1448` at it. Replace MapScreen `1472–1489` with the **already-existing** `pickThreeCandidates`. | 2 | −60 | low — the timeout wrapper must be preserved at the call site |
| 2 | `NavAppPolicy.kt`, `FollowCamera.kt`, `PlaceSearch.kt`, `CircleFixFeed.kt` — the four small pure ones. Point `car/CarMapRenderer.kt:139–151` at `CircleFixFeed`. | 6 | −60 | low |
| 3 | `CameraEase.kt` + `SpeedEase.kt`. Rewire MapScreen `1265–1334` and `car/CarMapRenderer.kt:380–420`; delete both `smoothBearing` copies. | 3 | −55 | **medium** — camera feel is subjective; verify by driving before/after |
| 4 | `NavPolicy.kt` + tests. Point MapScreen `1359–1389` **and** `car/NavScreen.kt:242–252` at it. Delete the comment that stood in for it. | 4 | −20 | low — pure, fully testable |
| 5 | `SectionAverageTracker.kt` + tests. MapScreen `281–315`, `1169–1231` → 4 lines of wiring. | 3 | −90 | **medium** — see §9.1 |
| 6 | `SpeedLimitService.kt` + tests. Rewire MapScreen `1018–1057` and `car/SpinScreen.kt:255–295`. **Separate follow-up commit** moves the fetch off the collector (behaviour change). | 4 | −38 | **medium** |
| 7 | `SpeedCameraFeed.kt` + `CameraWarner.kt` + tests. Rewire MapScreen `1058–1084`, `1134–1168` and `car/NavScreen.kt:365–417`. | 4 | −55 | **medium** |
| 8 | `GroupSpinRules.kt` + tests. MapScreen `321–367`, `842–871`. | 3 | −60 | low — pure |
| 9 | `NavSession.kt`. MapScreen `970–1017`, `1344–1391`. | 3 | −72 | **high** — most state, most call sites |
| 10 | `SpinSession.kt` + `SpinResultHolder` move. Update `ui/RoutesScreen.kt` import. | 4 | −165 | **high** — 6 pieces of composable state change owner |
| 11 | Delete unused imports; run the full unit suite. | 1 | −38 | none |

Steps 4, 5, 8 are the ones I would land first if the team wanted a small, safe
proof of the pattern: three pure objects, ~170 lines out, ~18 tests in, zero
coroutine restructuring.

---

## 8. Test plan

Two source sets, because the extraction spans two modules:

- `shared/src/commonTest/kotlin/com/jellemax/detour/drive/` — `kotlin.test`
  (`@Test`, `assertEquals`), matching `shared/…/commonTest/.../RoutesTest.kt`.
  Runs on JVM and iOS.
- `app/src/test/java/com/jellemax/detour/` — plain JUnit 4
  (`junit:junit:4.13.2`, `app/build.gradle.kts:163`), matching
  `TripTraceMatchingTest.kt` and `PlaceNotificationsTest.kt`. No Robolectric, no
  Android APIs.

### 8.1 `shared/…/commonTest/drive/SectionAverageTrackerTest.kt`

The single best target in the proposal: 63 lines of path-dependent logic with
four exit conditions, currently unverifiable without driving through a Dutch
trajectcontrole.

```
doesNotEnterASectionWhenPassingItsFarEndOnTheWayOut
entersOnlyWhenHeadingTowardsTheOppositeEnd
picksTheNearestSectionWhenAShortOneSitsInsideALongOne
ignoresBearingBelowTwoMetresPerSecond
publishesNoAverageUntilTwentyMetresHaveBeenCovered
carriesTheSectionsPostedLimitIntoTheState
endsOnlyAtTheEndItWasDrivenTowards
doesNotExitThroughTheGateItEnteredBefore150Metres
clearsAfterOvershootingTheSpanByFortyPercentPlus400m
clearsAfterThirtyMinutesWithoutReachingTheEnd
```

### 8.2 `shared/…/commonTest/drive/CameraWarnerTest.kt`

```
warnsOncePerCameraAndRearmsOnlyOnceItIsBehindYou
staysSilentWhenTheLimitIsUnknown
staysSilentWithinThreeKmhOverTheLimit
ignoresCamerasOutsideTheFortyFiveDegreeWedge
warnsWithNoHeadingBecauseAStoppedPhoneHasNoBearing
picksTheNearestOfSeveralCamerasAhead
```

### 8.3 `shared/…/commonTest/drive/NavPolicyTest.kt`

```
arrivesOnlyWhenWithinFortyMetresAndOnRoute
doesNotArriveWhenOffRouteInsideTheArrivalRadius
neverArrivesOnALoopWithNoDestination
neverReroutesALoop
reroutesOnceThenWaitsOutTheFifteenSecondCooldown
doesNotRerouteWhileARerouteIsAlreadyInFlight
```

### 8.4 `shared/…/commonTest/drive/CameraEaseTest.kt`

```
coversAboutSixtyThreePercentOfTheGapInOneTau
takesTheShortWayRoundTheThreeSixtyWrap
holdsBearingWhenTheTargetBearingIsNull
reportsNoStepOnceInsideAllThreeEpsilons
clampsDeltaTimeSoADroppedFrameCannotTeleportTheCamera
zoomEasesTowardsNavEngineCameraZoom
```

### 8.5 `shared/…/commonTest/drive/SpeedLimitServiceTest.kt`

Pure half only (`onFix`), with `fetchWays` stubbed:

```
clearsTheSignOnlyAfterThreeConsecutiveMisses
oneMissDoesNotClearAnEstablishedLimit
ignoresFixesBelowTwoMetresPerSecond
asksForARefetchOnlyWithinFiveHundredMetresOfThePrefetchEdge
throttlesRefetchToOncePerTenSeconds
keepsThePreviousWaysWhenAFetchReturnsEmpty
```

### 8.6 `shared/…/commonTest/drive/FollowCameraTest.kt`

```
resumesOnlyAboveThreeMetresPerSecond
waitsEightSecondsAfterTheLastGesture
staysParkedWhileOwnCandidatesAreOnScreen
staysParkedWhileAConvoySpinOfferIsOnScreen
```

### 8.7 `app/src/test/java/com/jellemax/detour/net/GroupSpinRulesTest.kt` (JUnit 4)

```
tiesGoToTheLowestIndex
noVotesYetLeadsWithTheFirstCandidate
ignoresVotesForIndicesOutsideTheCandidateList
aOneCandidateOfferCommitsOnEveryDeviceIncludingReceivers
onlyTheSharerClosesTheRound
waitsUntilEveryLivePeerAndMyselfHaveVoted
doesNotCloseWhenThePeerSetIsEmpty
mapsWireCandidatesWithNoDistanceToANullRoute
```

### 8.8 What stays untested, and I am not going to pretend otherwise

- Overpass and GraphHopper calls (`speedLimitWays`, `SpeedCameras.near`,
  `RoutingServer.route/roundTrip`) — network, not stubbed at the transport layer
  in this repo today.
- The Compose frame loop (`withFrameNanos`), `setCamera`, MapOverlays pushes,
  `ToneGenerator`, permissions, `AndroidView` — all still verified only by
  running the app. That is ~505 lines (b + c) plus the 1,545 UI lines.
- `SpinSession.spin()` end-to-end: its value is the error-precedence tree
  (`1496–1508`), which is reachable only by stubbing four suspend functions. A
  characterisation test is possible but expensive; I would defer it.

Estimated coverage delta: **~40 new tests over ~450 lines of logic that has zero
tests today**, in a repo whose entire unit suite is currently 3 test files.

---

## 9. Android Auto reuse

Concrete, per file, with what actually gets deleted:

### `app/src/main/java/com/jellemax/detour/car/CarMapRenderer.kt` (663 lines)

| Today | Replaced by | Lines |
|---|---|---:|
| `:53–69` — `CAM_POS_TAU`, `CAM_BEARING_TAU`, `CAM_ZOOM_TAU`, `CAM_POS_EPS_DEG`, `CAM_ZOOM_EPS`, `CAM_BEARING_EPS_DEG` (identical values to MapScreen `232–255`) | `CameraEase.Companion` | 17 |
| `:398–415` — the ease step + moved-enough test | `CameraEase.step(dt)` | 18 |
| `:468–475` — second copy of `smoothBearing` | `CameraEase.smoothBearing` | 8 |
| `:74–78`, `:139–151` — `CIRCLE_FIX_POLL_MS` and the poll loop, comment already pointing at MapScreen | `CircleFixFeed` | 18 |

**~61 lines deleted, and the phone and car camera become the same camera by
construction** — today they are two copies that happen to agree.

### `app/src/main/java/com/jellemax/detour/car/NavScreen.kt` (593 lines)

| Today | Replaced by | Lines |
|---|---|---:|
| `:131–137` — `speedCameras`, `camerasCenter`, `lastCameraFetchMs`, `warnedCameraAt`, `cameraFetchJob` | `SpeedCameraFeed` + `CameraWarner` instances | 7 |
| `:378–392` — prefetch/throttle | `SpeedCameraFeed.run(fixes, scope)` | 15 |
| `:396–410` — wedge filter, nearest, over-limit, `warnedAt` latch | `CameraWarner.onFix(...)`; the toast/voice/tone stay | 15 |
| `:242–252` + `:249–252` — arrival + reroute gate, with the comment *"Same arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect"* | `NavPolicy.decide(...)` | ~14 |

**~51 lines deleted.** More importantly the `:242` comment stops being the only
thing keeping two policies in sync.

### `app/src/main/java/com/jellemax/detour/car/SpinScreen.kt` (352 lines)

| Today | Replaced by | Lines |
|---|---|---:|
| `:93–99` — `limitWays`, `limitWaysCenter`, `lastLimitFetchMs`, `limitMisses`, `ambientLimitKmh`, `limitFetchJob` | one `SpeedLimitService` instance | 7 |
| `:264–295` — prefetch + snap + 3-miss clear | `SpeedLimitService.run(fixes, scope)` + `.limitKmh` | 32 |
| `:120–121` — reset on screen re-entry | `SpeedLimitService.reset()` | 2 |
| `:326–338` — single `pickCandidate` roll | could become `SpinSession.spin(...)`, gaining the three-candidate roll and round-trip mode the car does not have | (addition) |

**~42 lines deleted.**

### Capability the car gains rather than deletes

`SectionAverageTracker` **does not exist on the car at all**. Adding
trajectcontrole average speed to the head unit today means copying 63 lines of
state machine plus `sectionExitGate`; after extraction it is:

```kotlin
private val sections = SectionAverageTracker()
// in onFix:
sections.onFix(pos, bearingDeg?.toDouble(), speedMps, cameraFeed.sections.value)
renderer.updateSectionAverage(sections.state.value)
```

Same for the ambient limit on `NavScreen` and the curvy round-trip spin on
`SpinScreen`. **This is the strongest argument in the proposal**: the car is not
missing these features because nobody wanted them, it is missing them because
they are welded to a Compose function.

### iOS, as a bonus rather than a claim

`grep -rn 'SpeedCameras\|snapSpeedLimit\|SectionAverage' iosApp/` returns
nothing: the SwiftUI app ships without the ambient limit sign, camera warnings
and trajectcontrole — the three features whose only implementation is inside
MapScreen's composable body. Anything landing in `:shared/drive` is reachable
from Swift the same way `SpinPickerKt.pickThreeCandidates` already is
(`iosApp/Detour/SpinModel.swift:129`). I do not claim iOS will *use* it; I claim
the extraction is what makes it possible, and that the project has already made
this exact bet once and it paid.

---

## 10. Pros

1. **It removes the right 838 lines.** Splitting a 3,193-line file by cutting
   composables into other files leaves the hard part — four GPS state machines
   and a distributed vote rule — exactly as hard. This removes the part where
   the bugs live.
2. **~450 lines of logic go from untestable to unit-testable**, in a repo whose
   entire unit suite is 3 files. The section machine, the warning latch and the
   vote rule are all path-dependent and all currently verified only by driving.
3. **It ends live duplication, with receipts.** `smoothBearing` ×2,
   `CIRCLE_FIX_POLL_MS` ×2, the camera prefetch ×2, the speed-limit snap ×2, the
   arrival/reroute policy ×2 (with a comment admitting it), and
   `pickThreeCandidates` ×2 where one copy is already in `:shared` and already
   used by iOS. ~155 lines of car code deleted.
4. **It fixes a real bug as a side effect.** `MapScreen.kt:1037` and `:1075`
   stall the fix collector on Overpass. The car surface fixed this months ago
   (`car/NavScreen.kt:365–377`). One service means one fix.
5. **It follows a precedent this repo has already set and validated.** `:shared`
   exists, has 38 files, a `commonTest` source set, and a second consumer. This
   proposal is "keep doing the thing that worked", not "adopt an architecture".
6. **Fewer recompositions.** Fifteen `var … by remember` in MapScreen
   (`446–554`) currently invalidate the whole 1,419-line composable on every GPS
   fix. Behind a `StateFlow` collected where it is read, the invalidation scope
   narrows to the readers.
7. **It unblocks car and iOS features** that are currently blocked purely by
   where the code sits (§9).
8. **Reviewable in slices.** Steps 4, 5 and 8 are pure functions with tests —
   ~170 lines out, zero coroutine restructuring, trivially revertable.

---

## 11. Cons and risks

### 11.1 Behavioural regression in the GPS state machines — the big one

The four extracted state machines are **path-dependent**: `warnedAt`
(`1146`), `active`/`exitGate`/`entryMs`/`accMeters`/`last` (`1175–1179`),
`speedLimitMisses` (`533`), `center`/`lastFetchMs` (`1063–1064`). Their
correctness is a property of the *sequence* of fixes, not of any one fix. A
reordering that looks harmless — hoisting a guard, changing when `last` is
assigned, moving `sectionAvgKmh = null` — changes behaviour in a way that only
shows up on a specific road at a specific speed.

The project's own constraint makes this worse: no Robolectric, no instrumented
tests, so *today* the only verification is driving. Concretely:

- **The section machine's exit rule** (`1217–1220`) has three conditions and a
  150 m floor whose purpose is to stop the entry gate counting as the exit. Get
  the ordering wrong and the average shows *after* the trajectcontrole instead
  of during it — which the KDoc at `290–299` says was already a shipped bug once.
- **The camera latch** (`1156–1165`) is silent when `limit == null`. If
  extraction changes when `limitKmh` is `null` (e.g. because the ambient service
  now clears on a different fix), the chime changes.

**Mitigation, and it is not free:** write the characterisation tests *before*
moving the code, against a transcript of synthetic fixes, and assert they pass
identically on both sides of each commit. Steps 5–7 must each be a single-purpose
commit with constants copied byte-for-byte. The one deliberate behaviour change
(moving the Overpass fetch off the collector, §1) must be its own commit,
explicitly labelled, and driven before merge.

Residual risk after mitigation: **still non-zero.** A synthetic transcript
encodes what I *believe* the machine does, and a characterisation test locks in
current behaviour including current bugs. This is the honest cost.

### 11.2 Lifecycle and battery for services that outlive the screen

The pattern's own convention pulls toward the dangerous shape. The project's
singletons are `object`s; one of them (`ConvoyLiveClient.kt:311`) owns a
`CoroutineScope` and has to be explicitly `cancel()`led (`:292–293`). If any
extracted service copies that shape, it collects `lastFix` forever — and
`SpeedCameraFeed` and `SpeedLimitService` each issue Overpass requests, so
"forever" means an Overpass request every few kilometres for the whole drive
with the screen off, on top of the tracker's own duty cycle. `CircleFixFeed` is
worse: it polls on a `delay(120_000)` timer that no absence of fixes will stop.

My rule (§3.1: no service owns a scope; `run()` is a `suspend fun` the caller
drives) prevents this, but it is a *convention enforced by review*, not by the
compiler. One `CoroutineScope(Dispatchers.IO + SupervisorJob())` inside a service
and the battery regression ships silently. I would add a CI grep.

Also worth stating plainly: MapScreen's composition survives backgrounding
(`598–601`), so these loops already run in the background today. Extraction does
not fix that. It just must not make it worse.

### 11.3 Some call sites get *worse* to read

`var destination by remember { mutableStateOf(...) }` with `destination = x` at
six sites becomes `session.destination.collectAsStateWithLifecycle()` plus
`session.setDestination(x)`. That is more ceremony for genuinely local UI state.
`choose()` (`803–816`) is a good example: 14 lines of which 6 are camera framing
that must *stay* in the UI, so the extraction leaves a two-part function split
across two files. Not every one of the 934 lines is better off elsewhere, and
§2.3's 134-line residue is where that shows.

### 11.4 Cross-module and cross-file churn

- `data class Fix` moving out of `TripTrackingService` is a step-0 blocker for
  everything else — though a cheap one: the type is named only inside
  `TripTrackingService.kt`, so it is a two-file change, not the ten I first
  assumed.
- `SpinResultHolder` and `seedRouteNavigation` moving breaks `ui/RoutesScreen.kt`'s
  import (`internal` symbols, so it is an import change, not an API break).
- 24 `private` composables become `internal` in the §6 split.

### 11.5 The `:shared` module raises the bar

`commonMain` has no `System.currentTimeMillis()` (use the existing
`nowMs()`, `data/Angles.kt:16`), no `android.util.Log`, and — verified — **no
`Dispatchers.IO`**: `grep -rl Dispatchers shared/src/commonMain` returns zero
files, because the Ktor port made everything suspending. So every
`withContext(Dispatchers.IO)` in the moved code must be either dropped (network
calls: correct) or hoisted to the Android caller (okio file reads: still
blocking). Getting that wrong on a *file* read moves blocking I/O onto the main
thread. This is a per-call-site judgement, ~12 sites.

Build cost: touching `:shared` recompiles the KMP metadata + Android target,
and on macOS three Kotlin/Native targets. Slower inner loop than editing `app/`.

### 11.6 Line count grows; the file is still huge

+510 lines repo-wide (§4.4), and MapScreen.kt is still ~2,355 lines — still the
largest file in the app, still bigger than `TripTrackingService.kt` (1,333) and
`SettingsScreen.kt` (1,199). Anyone measuring success by "MapScreen is now a
reasonable size" will be disappointed by this proposal on its own.

### 11.7 Two of the eleven steps are genuinely hard

Steps 9 (`NavSession`) and 10 (`SpinSession`) move ~240 lines and change the
owner of nine pieces of composable state (`destination`, `destinationName`,
`route`, `candidates`, `navigating`, `navProgress`, `rerouting`, `spinning`,
`spinJob`). Those two steps carry most of the regression risk in the plan and
the least test leverage (their value is orchestration, which needs four stubbed
suspend functions to test). It would be defensible to land steps 0–8 and stop.

---

## 12. Effect on recomposition/perf, reviewability, onboarding

### Recomposition and performance

**Better, modestly, and for one specific reason.** MapScreen currently holds ~15
`mutableStateOf` values written from GPS-driven effects (`446–554`): `camTarget`,
`camTargetBearing`, `camTargetZoom`, `displaySpeedKmh`, `ambientSpeedLimitKmh`,
`sectionAvgKmh`, `sectionLimitKmh`, `navProgress`, `speedCameras`, `circleFixes`,
`myLocation`. Every write invalidates the enclosing 1,419-line composable. The
file already fights this by hand — `rememberUpdatedState` refs at `937–939`,
`1138–1140`, `1173`, `1250` exist precisely to keep effects from re-keying, and
the epsilon gates at `1320–1324` and `1259–1261` exist to stop the camera and
speedometer repainting when nothing visibly changed. Those are workarounds for
state living too high.

Behind services, `sectionAvgKmh` is collected in `SpeedHud`'s caller and nowhere
else, so a section-average change stops invalidating the map's whole subtree.
`displaySpeedKmh` likewise. The eight `rememberUpdatedState` refs can go.

**Honest limits:** the per-frame camera loop still runs at frame rate; the
`setCamera` call and the fog invalidate it triggers are unchanged; total
allocation goes slightly *up* (a `StateFlow` per service vs. a `MutableState`).
Do not expect a measurable frame-time win. Expect fewer needless recompositions,
which matters most on the bottom card.

### Reviewability

**Clearly better, and the review shape changes qualitatively.** Today, a PR
touching the trajectcontrole rule shows as a diff inside a 1,419-line composable,
where the reviewer cannot tell whether the surrounding effect keys still make
sense without reading 200 lines of context. After: a diff to a 145-line file with
a test file next to it, and the test names say what changed.

The migration itself is reviewable too — 11 commits, each with a stated Δ and a
single behaviour.

Counterpoint: reviewers now have to hold two files in their head for the split
functions (§11.3), and `git blame` on the moved lines points at the move commit
unless reviewers know to use `--follow` / `-C`.

### Onboarding

**Better for the domain, neutral-to-worse for the UI.** A new contributor asking
"where does the speed camera chime come from?" currently has to know it is
somewhere inside `MapScreen.kt`. After, `CameraWarner.kt` answers it in 85 lines
with tests that describe the rules. The `:shared/drive` package becomes a
readable table of contents for what the app actually does while driving.

But: the UI half is untouched, so "where is the spin sheet laid out?" still means
scrolling a 2,355-line file. And a newcomer now has to learn one more concept —
"drive services are `suspend fun run(flow)`, they never own a scope" — which is
not a standard Android pattern and is not written down anywhere except a doc like
this one. That convention needs a KDoc header on the package, or it will decay.

---

## 13. What this proposal explicitly does NOT solve

1. **The UI mass.** 1,545 lines of composables are untouched. MapScreen.kt stays
   ~2,355 lines and the largest file in the app. §6 sketches the fix; this
   proposal does not perform it.
2. **The state-hoisting question.** After extraction, MapScreen still holds ~20
   pieces of state and still has no ViewModel. Whether that state should live in
   a `ViewModel`, a Compose-scoped holder, or the services themselves is a
   separate argument this proposal deliberately does not make — there is no DI
   framework and no ViewModel dependency, and adding one is not in scope.
3. **MapLibre imperative wiring** (178 lines, class c). Still hand-rolled
   `LaunchedEffect`s pushing GeoJSON into `MapOverlays`. Not improved.
4. **Permission choreography** (~90 lines, `736–802`). Android-only, stays where
   it is.
5. **The network layer.** No transport-level test seam is added, so Overpass and
   GraphHopper paths remain untested. That is why `SpinSession.spin()`'s
   error-precedence tree stays uncovered.
6. **Background/battery behaviour.** MapScreen's effects already run while
   backgrounded; extraction preserves that rather than fixing it.
7. **The `GroupSpin` wire types.** They stay in `app/…/net/`, so group-spin logic
   stays Android-only and out of `:shared`.
8. **Any behaviour change the user would notice** — with one deliberate
   exception (moving the Overpass fetch off the fix collector, §1), which is
   scoped to its own commit.

---

## 14. Honest verdict

**This is the right choice when:**

- The pain being solved is *correctness and duplication*, not file size. If the
  motivating complaint is "the trajectcontrole average appeared after the
  section, again" or "the car camera drifted out of sync with the phone's", this
  is the proposal that addresses it. Cutting composables into new files does not.
- A second surface exists and is already carrying copies. It does:
  `car/` duplicates five of these concerns and says so in its own comments, and
  `iosApp/` is missing three features for want of shared code. That is not a
  hypothetical future consumer — the duplication has already happened, and one
  divergence (`pickThreeCandidates`) is already in the tree.
- The team will actually write the tests. The proposal's value is ~40 tests over
  ~450 lines of previously-untested state machines. Extract without testing and
  you have paid the regression risk for nothing but tidiness.
- It is done in slices. Steps 0–8 (~600 lines, all pure or nearly so) are a
  complete, defensible, individually-revertable piece of work. Steps 9–10 can be
  judged separately once the pattern has proven itself.

**This is the wrong choice when:**

- The goal is stated as "make MapScreen.kt a reasonable size". It gets you to
  2,355 lines and no further. A UI component split delivers more on that metric
  for less risk, and should probably land *first* — it is mechanical, it is
  verifiable by "the screen still looks the same", and it makes the domain code
  easier to see afterwards.
- The team cannot drive-test before merging. Steps 5–7 and 9–10 change
  GPS-driven state machines that no unit test written *after* the move can prove
  equivalent to what shipped. Without a real road, those steps are gambling.
- The work has to land as one PR. An 838-line extraction across two modules and
  four car files, reviewed in one go, is a rubber-stamp waiting to happen.
- Nobody will police "no service owns a `CoroutineScope`". That single rule is
  what stands between this proposal and a background battery regression, and the
  repo's own conventions push the other way.

**My recommendation, stated as a preference rather than a conclusion:** land the
UI component split first (it is lower-risk and makes the domain code visible),
then land steps 0–8 of this proposal with their tests, then decide about
`NavSession` and `SpinSession` with the evidence of how steps 0–8 went. The two
proposals are complements, and the honest ordering puts the other one first.
