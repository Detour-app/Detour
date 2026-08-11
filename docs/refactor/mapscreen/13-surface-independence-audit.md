# Surface independence audit — how much of Detour is actually shared

Companion to `10-eval-fact-audit.md`. Same constraints: read-only, no build, every
number re-derived from the tree at `07fe490`. The question is whether a
hexagonal / ports-and-adapters split would be a small step or a rewrite, so the
audit is about **where the seam is today**, not where it could be.

---

## 1. Module and surface map

### Real dependency edges

`settings.gradle.kts:15–19` declares three Gradle modules: `:app`, `:wear`, `:shared`.
`tools/mocklocation` is **not** in `settings.gradle.kts` — it is an orphan source tree.

| Edge | Declared at | Verdict |
|---|---|---|
| `:app` → `:shared` | `app/build.gradle.kts:135` `implementation(project(":shared"))` | real |
| `:wear` → `:shared` | **absent** — `wear/build.gradle.kts:62–69` lists six androidx/GMS artifacts and nothing else | **no edge** |
| `iosApp` → `:shared` | `iosApp/project.yml` `FRAMEWORK_SEARCH_PATHS: $(SRCROOT)/../shared/build/xcode-frameworks` + `OTHER_LDFLAGS: -framework DetourShared`, built by the `packForXcode` preBuildScript | real, via a static framework (`shared/build.gradle.kts:29` `isStatic = true`) |
| `:app` → `:wear` | deliberately absent — `app/build.gradle.kts:164–170` documents why (`No wearApp(project(":wear")) here on purpose`) | none |
| `app/.../car/` → `app/.../ui/` | same Gradle module, same package root | **not a module boundary at all** |
| any client → `backend/` | none (HTTP only, base URL baked at `app/build.gradle.kts:60–75`) | runtime only |

`:shared` therefore has **two** consumers, not three. `wear/` shares nothing with anything.

### Size

| Surface | Path | Files | LOC | Language |
|---|---|---:|---:|---|
| Phone app | `app/src/main/java/com/jellemax/detour/` | 45 | **16,705** | Kotlin |
| — of which `ui/` | `…/ui/` | 19 | 10,508 | Compose |
| — of which `car/` (Android Auto) | `…/car/` | 7 | 1,985 | `androidx.car.app` |
| — of which services | `tracking` 1,360 · `net` 625 · `ble` 587 · `notif` 512 · `data` 260 · `convoy` 229 · `media` 182 · `audio` 153 · `wear` 45 | 17 | 3,953 | Kotlin |
| — of which root | `MainActivity.kt` 226 · `DetourApplication.kt` 33 | 2 | 259 | Kotlin |
| Wear OS | `wear/src/main/…` | 2 | **185** | Kotlin |
| iOS | `iosApp/Detour/` | 25 | **5,065** | Swift |
| Shared core | `shared/src/commonMain/` | 36 | **4,921** | Kotlin |
| — commonTest | `shared/src/commonTest/` | 3 | 839 | 60 `@Test` |
| — androidMain | `Platform.android.kt` | 1 | 50 | |
| — iosMain | `FlowWatcher.kt` 196 · `Platform.ios.kt` 69 | 2 | 265 | |
| — androidUnitTest | `RouteStoreLoadOrderTest.kt` | 1 | 53 | 1 `@Test` |
| Backend (.NET) | `backend/` | 151 `.cs`, 18 `.csproj` | **14,445** | C# |
| Legacy server | `server/sync/sync_server.py` + test | 2 | 7,663 | Python — **superseded**, `backend/README.md:3` calls the .NET service "The .NET replacement for `server/sync/sync_server.py`" |
| Orphan tool | `tools/mocklocation/` | 1 | 170 | not in `settings.gradle.kts` |

**Client-side Kotlin + Swift total: 26,876 lines.**

### What consumes `:shared` inside `app/`

**29 of 45** files in `app/src/main` import `com.jellemax.detour.data`. The 16 that
do not are: `audio/PushToTalk.kt`, `car/DetourCarAppService.kt`, `car/NavVoice.kt`,
`convoy/ConvoyLiveService.kt`, `data/AndroidSync.kt`, `data/ConfigFile.kt`,
`data/Gpx.kt`, `data/RouteFiles.kt`, `media/MediaListenerService.kt`,
`notif/TripEndedNotification.kt`, `notif/TripOpen.kt`, `tracking/BootReceiver.kt`,
`ui/AppBar.kt`, `ui/Format.kt`, `ui/GlassSurface.kt`, `ui/GraphiteTheme.kt` — i.e.
pure Android plumbing and pure theming. **Every screen and every service that
carries domain meaning already reaches into `:shared`.** That is the good news.

**23 of 25** iOS files `import DetourShared`. The two that do not are
`Format.swift` and `LocationProvider.swift` / `LocationBroadcast.swift` /
`NavVoice.swift` / `PttAudio.swift` (5 total by grep — `Format.swift`,
`LocationBroadcast.swift`, `LocationProvider.swift`, `NavVoice.swift`,
`PttAudio.swift`).

---

## 2. What is actually shared today

All 36 commonMain files, with class and consumer. Consumers: **A** = Android app,
**C** = Android Auto (`app/.../car/`), **i** = iOS, **W** = Wear (none).

Class key: **(a)** pure domain logic · **(b)** data/serialization model or store ·
**(c)** network client · **(d)** platform abstraction (`expect`/`actual`).

| File | LOC | Class | Purpose | Consumed by |
|---|---:|---|---|---|
| `RoadRoulette.kt` | 433 | a+c | Random road point in a wedge, Overpass speed-limit ways, `snapSpeedLimitKmh`, distance/wedge geometry | A, C |
| `RoutingServer.kt` | 396 | c | GraphHopper: `route`, `roundTrip`, `randomRoadDestination`, `ServerConfig` | A, C, i |
| `Settings.kt` | 331 | b+d | Every user preference as a `StateFlow`, backed by `Prefs` | A, C, i |
| `Coverage.kt` | 299 | a+b | Municipality polygons, per-municipality coverage %, `MunicipalityStore` | A, i |
| `RoundTripPlanner.kt` | 235 | a | `Curviness.routeScore`, waypoint loop planning | A only |
| `CircleEvents.kt` | 210 | a+b | `PlaceEvent`, `placeEventFromRelayFrame`, `GeofenceEvaluator` (the only tested state machine in the repo) | A, i |
| `RouteGpx.kt` | 198 | b | GPX/route-file parse and emit | A, i |
| `TripStore.kt` | 193 | b | Trip records on disk | A, i |
| `Social.kt` | 188 | b+c | `Account`, `Friends`, `PendingReset` | A, i |
| `NavEngine.kt` | 184 | a | `progress`, `cameraZoom`, `prefix` — the whole turn-by-turn maths | A, C, i |
| `Routes.kt` | 181 | b | `SavedRoute`, `RouteStore` | A, i |
| `Badges.kt` | 178 | a+b | `RiderStats`, tier thresholds, `BadgeStore.refresh` | A, i |
| `Geocoder.kt` | 144 | c | Photon search | A, C, i |
| `SpeedCameras.kt` | 138 | a+c | Overpass camera/`Section` fetch, `WARN_METERS`, `PREFETCH_RADIUS_M` | A, C |
| `TraceStore.kt` | 117 | b | `TracePoint`, `traces.jsonl` read/write/parse | A, i |
| `Json.kt` | 114 | b | A lenient `org.json`-shaped shim over kotlinx-serialization | internal |
| `RouteShare.kt` | 113 | c | Route inbox/share over the sync server | A, i |
| `SavedPlaces.kt` | 104 | b | Named pins | A, C, i |
| `SpinPicker.kt` | 103 | a | `pickThreeCandidates`, `pickCandidate`, `RouteCandidate` | C, i (**not A** — see §3) |
| `Http.kt` | 96 | c | The single Ktor client | internal |
| `Groups.kt` | 92 | c | Convoys and circles CRUD | A, i |
| `RouteColors.kt` | 90 | b | Route colour palette | A, i |
| `PoiRoulette.kt` | 84 | a+c | Random POI of a kind, explored-area weighting | A |
| `CircleFixes.kt` | 83 | c | `MemberFix`, `othersFixes` | A, C, i |
| `CirclePlaces.kt` | 76 | b+c | `CirclePlace` share/list/delete | A, i |
| `TravelMode.kt` | 73 | b | The four modes + `ghProfile`/`highwayRegex`/`defaultKm` | A, C, i |
| `Api.kt` | 73 | c | Auth headers, base URL assembly | internal |
| `SyncClient.kt` | 59 | c | Whole-account sync | A, i |
| `ExploredArea.kt` | 52 | a | Fog-derived bias for the roulette | A, C, i |
| `SunTimes.kt` | 51 | a | Sunrise/sunset for the auto theme | A, i |
| `BuildDefaults.kt` | 49 | b | Baked-in server URLs | A, i |
| **`Platform.kt`** | **47** | **d** | **`expect class Prefs`, `expect fun prefs`, `expect fun appFilesDir`, `expect val fileSystem`** | A, i |
| `RecentSearchStore.kt` | 41 | b | Recent geocoder hits | A, i |
| `FriendFog.kt` | 40 | b+c | Friends' unioned traces | A, i |
| `Files.kt` | 40 | d | okio whole-file helpers over `fileSystem` | internal |
| `Angles.kt` | 16 | a+d | `toRadians`/`toDegrees` and **`nowMs()`** | internal |

**By class:** (a) pure domain ≈ 1,700 · (b) models/stores ≈ 1,540 · (c) network
≈ 1,580 · (d) platform abstraction **103 lines total** (`Platform.kt` 47 + `Files.kt`
40 + `Angles.kt` 16).

### `expect`/`actual` count — the port-abstraction test

```
expect declarations:  4     (Platform.kt:25, :41, :44, :47)
actual members:      28     (13 androidMain + 15 iosMain)
interfaces in commonMain:  0
object singletons in commonMain: 33
```

**Four `expect` declarations for a 4,921-line core.** They cover exactly three
things: a key-value bag (`Prefs`), an app-private directory (`appFilesDir`), and
an okio `FileSystem`. Everything else is a concrete `object`.

`Platform.kt:8–15` states the rule outright:

> *"Deliberately not a general 'platform services' interface. Anything bigger than
> this — location, audio, Bluetooth — stays on the platform side of the boundary
> and is pushed **into** the core, rather than the core reaching out for it."*

That is the architectural decision this whole audit is about. It is a real,
documented, consistently-followed rule — and §3 is its bill.

---

## 3. What is duplicated instead of shared

### 3.1 Phone ↔ Android Auto — established in `10-eval-fact-audit.md`

**≈199 lines phone-side / ≈186 car-side**, 11 items (D1–D11 there). Summary:
6 camera tuning constants (`ui/MapScreen.kt:236–238, 253–255` ↔ `car/CarMapRenderer.kt:53–55, 67–69`),
`smoothBearing` (`:224–230` ↔ `:470–475`), the camera ease loop (`:1294–1332` ↔
`:391–431`), `CIRCLE_FIX_POLL_MS` (`:279` ↔ `:78`), the circle poll
(`:1105–1119` ↔ `:139–150`), the ambient speed-limit machine (`:1026–1054` ↔
`car/SpinScreen.kt:265–296`), speed-camera prefetch (`:1062–1083` ↔
`car/NavScreen.kt:378–396`), the camera-warning latch (`:1145–1167` ↔ `:397–416`),
arrival/reroute policy (`:1359–1389` ↔ `:243–277`), `fetchLocation` (`:714–734` ↔
`car/SpinScreen.kt:298–310` ↔ `car/SearchScreen.kt:164–173`), and the `geo:` handoff.

### 3.2 Phone ↔ iOS — the large one

The iOS service layer is a **function-for-function parallel implementation** of the
Android service layer, in Swift. Same names, same order, same thresholds.

**`TripRecorder.swift` (442) ↔ `tracking/TripTrackingService.kt` (1,333)** — twelve
identically-named private functions: `onLocation` (`:202` ↔ `:966`), `onIdleLocation`
(`:217` ↔ `:986`), `onTripLocation` (`:257` ↔ `:1038`), `resetStartDetector`
(`:287` ↔ `:961`), `addTracePoint` (`:297` ↔ `:1131`), `flushTrace` (`:322` ↔ `:1240`),
`maybeDiscoverMunicipality` (`:335` ↔ `:1158`), `startMotionSensors` (`:350` ↔ `:517`),
`stopMotionSensors` (`:364` ↔ `:535`), `resolvedMode` (`:415` ↔ `:669`), plus
`startTrip`/`beginTrip` and `endTrip`. **Nineteen tuning constants are copied
verbatim** — `TripRecorder.swift:41–60` against `TripTrackingService.kt:140–201`
(`fastSpeedMps 7.0`, `probeSpeedMps 4.0`, `fastFixesToStart 3`, `minFastRunMs 8_000`,
`minFastRunMeters 120.0`, `maxStartAccuracyM 25`, `probeWindowMs 3*60_000`,
`stationaryEndMs 5*60_000`, `minAutoTripMeters 500.0`, `walkAvgMaxMps 2.5`,
`walkMinJudgeMs 90_000`, `walkTopMaxMps 6.0`, `maxPlausibleLeanDeg 65.0`,
`leanEmaAlpha 0.3`, `maxLeanSlewDeg 20.0`, `minLeanSpeedMps 3.0`, `gEmaAlpha 0.15`,
`maxGSlew 0.5`, `maxPlausibleG 2.0`). The Swift header comment at
`TripRecorder.swift:39` says so: *"Auto-detection thresholds (identical to the Android
service)"*. **≈270 Swift lines of duplicated decision logic.**

**`ConvoyLiveClient.swift` (473) ↔ `net/ConvoyLiveClient.kt` (625)** — fourteen
matching functions and **the entire relay wire protocol written twice**: send side
`swift:281–357` ↔ `kt:318–394` (`ptt_start`, `ptt_end`, `ptt_audio`, `spin_offer`,
`spin_vote`, `location`, `join`), receive dispatch `swift:363–468` ↔ `kt:525–620`
(the same nine `case`/`when` branches), peer pruning `swift:469` ↔ `kt` with
`stalePeerMs 20_000` ↔ `STALE_PEER_MS 20_000L` and `locationSendIntervalMs 2_000`
↔ `LOCATION_SEND_INTERVAL_MS 2_000L`, backoff `1 s`/`30 s` on both sides. Only
one frame is parsed by shared code (`CircleEvents.kt:87 placeEventFromRelayFrame`).
**≈300 Swift lines.**

**`CircleNotifications.swift` (248) ↔ `notif/CircleNotifyService.kt` (229)** —
catch-up sweep, cap, max-age, live-event handling. Shared code supplies
`PlaceEvent`, `notificationText()`, `catchUpSummaryText()` and `GeofenceEvaluator`
(`CircleEvents.kt:114–180`); the sweep policy around them is written twice.
**≈110 Swift lines.**

**`CircleSync.swift` (135) ↔ `TripTrackingService.circleSyncLoop` (`:1183`)** — the
Swift doc at `:7–8` says *"Mirrors `TripTrackingService.circleSyncLoop` on Android"*
and `:31` / `:37` say the intervals *"Match Android's `CIRCLE_SYNC_INTERVAL_MS`"* /
`CIRCLE_IDLE_INTERVAL_MS` (`TripTrackingService.kt:217`, `:222`).
**≈100 Swift lines, whole file bar the CoreLocation sampling.**

**`Format.swift` (59) ↔ `ui/Format.kt` (45)** — all eight functions
(`formatDuration`, `formatDurationHistory`, `formatSpeedKmh`, `formatDistanceKm`,
`formatLeanAngle`, `formatGForce`, `formatDate`, `formatTimeOfDay`), including a
**verbatim-translated four-line comment** about "1:12:36" next to "7:19".
`Format.swift:3` states the reason: *"Not in `:shared`: these are presentation."*
**59 Swift lines, 100% of the file.**

**`NavVoice.swift` (53) + `NavScreen.swift:167–233` ↔ `car/NavVoice.kt` (150) +
`car/NavScreen.kt:287–313, 524–529, 556–593`** — the three-phase announce ladder,
`spokenDistance`, and the maneuver-icon table. **≈90 Swift lines.**

**`MapScreen.swift:296–347` ↔ `ui/MapScreen.kt:361–367, 823, 858–870`** —
`leadingSpinIndex` is a **third hand-written copy** (`swift:339–347` ↔ `kt:361–367`)
and `resolveGroupSpin` (`swift:316–335`) duplicates the vote-resolution rule at
`kt:858–870`. The Swift comment at `:307–315` and the Kotlin one at `:842–857` are
the same sixteen-line correctness argument, written twice. **≈50 Swift lines, on a
distributed-consensus rule with zero tests on either side.**

**`PttAudio.swift` (127) ↔ `audio/PushToTalk.kt` (153)** — the wire format
(16 kHz mono S16LE, `SAMPLE_RATE / 25` chunks) is fixed by the relay and stated
independently in both. **≈35 Swift lines.**

**`SpinModel.swift:17–24` ↔ the implicit spin machine in `ui/MapScreen.kt` and
`car/SpinScreen.kt:312–346`** — a hand-written Swift `enum State { idle, spinning,
choosing, found, failed }`. **≈35 Swift lines.**

**`LocationBroadcast.swift` (37)** — the "one collector, two sinks" rule, whose
Android counterpart is `ConvoyLiveClient.kt:382` collecting
`TripTrackingService.lastFix`. **≈20 Swift lines.**

> **Total Android ↔ iOS duplication: ≈1,070 Swift lines** against ≈1,150 Kotlin lines
> in `app/`. That is **21% of the entire iOS app** and **≈55% of its non-UI code**.

### 3.3 Phone ↔ Wear

`wear/` has **no `:shared` dependency**. In 185 lines it manages to duplicate two things:

- `wear/MainActivity.kt:53–70` `signIcon(sign: Int)` — the GraphHopper sign-code
  table, with the comment *"mirrors phone app's ui/Navigation.kt"*. ≈18 lines.
- `wear/MainActivity.kt:45–50` `data class NavState` — *"mirrors NavEngine.Progress
  + NavInstruction"*, plus the hand-rolled `org.json` frame parse in
  `MainActivity.kt` and `NavListenerService.kt:23–27`, against the writer in
  `app/.../wear/NavRelay.kt`. ≈27 lines.

**≈45 of 185 lines (24% of the module) duplicate `app/` or `:shared`.**

### 3.4 The four-copy table

| Logic | Copies | Locations |
|---|---:|---|
| GraphHopper sign → maneuver icon | **4** | `ui/Navigation.kt` · `car/NavScreen.kt:575–593` · `wear/MainActivity.kt:53–70` · `iosApp/NavScreen.swift:223–233` |
| `fetchLocation` (fused/CL provider) | **4** | `ui/MapScreen.kt:714–734` · `car/SpinScreen.kt:298–310` · `car/SearchScreen.kt:164–173` · `iosApp/LocationProvider.swift:39–48` |
| Spin lifecycle state machine | **3** | `ui/MapScreen.kt` (implicit) · `car/SpinScreen.kt:312–346` · `iosApp/SpinModel.swift:17–24` |
| Three-candidate roll | **3** | `shared/…/SpinPicker.kt:27` (used by iOS) · `ui/MapScreen.kt:1474–1489` (inline copy) · `car/SpinScreen.kt:332` (single-candidate variant) |
| `leadingSpinIndex` tie-break | **2** | `ui/MapScreen.kt:361–367` · `iosApp/MapScreen.swift:339–347` |
| `smoothBearing` | **2** | `ui/MapScreen.kt:224–230` · `car/CarMapRenderer.kt:470–475` |
| `geo:` external handoff | **3** | `ui/MapScreen.kt:3188–3192` · `car/SpinScreen.kt:348–351` · `car/SearchScreen.kt:159–162` |

### 3.5 Duplication totals per surface pair

| Pair | Duplicated logic | As % of the smaller surface's non-UI code |
|---|---:|---:|
| Phone ↔ Android Auto | **≈199 / ≈186 lines**, 11 items | ~10% of `car/` |
| Phone ↔ iOS | **≈1,150 Kotlin / ≈1,070 Swift**, 10 items | ~55% of iOS non-UI |
| Phone ↔ Wear | **≈45 lines**, 2 items | 24% of `wear/` |
| Android Auto ↔ iOS | 0 (no CarPlay target) | — |
| Wear ↔ iOS | 0 (no watchOS target) | — |
| **Total** | **≈1,300 duplicated lines across the client surfaces** | |

---

## 4. Feature parity gaps caused by non-sharing

Verified by grepping each surface for the feature's shared symbols. "Welded" means
the logic exists only inside one surface's UI framework, so the other surface would
have to rewrite it rather than call it.

| Feature | Phone | Car | iOS | Wear | Where the logic is welded |
|---|:-:|:-:|:-:|:-:|---|
| Ambient speed limit (prefetch + snap + 3-miss hysteresis) | ✅ | ✅ | ❌ | ❌ | `ui/MapScreen.kt:1018–1056` inside a `LaunchedEffect`; car re-implemented it at `car/SpinScreen.kt:265–296`. iOS has zero `snapSpeedLimitKmh` references |
| Speed cameras + warning chime | ✅ | ✅ | ❌ | ❌ | `ui/MapScreen.kt:1058–1083, 1134–1167`; car copy `car/NavScreen.kt:378–416`. iOS has zero `SpeedCameras` references — despite `shared/…/SpeedCameras.kt` being commonMain and callable |
| Trajectcontrole (average-speed section) | ✅ | ❌ | ❌ | ❌ | `ui/MapScreen.kt:1169–1230` + `sectionExitGate` at `:300–314`. `car/NavScreen.kt:391` fetches `SpeedCameras.near` and **discards `result.sections`** |
| Round trips (`Curviness`, `RoundTripPlanner`) | ✅ | ❌ | ❌ | ❌ | Orchestration at `ui/MapScreen.kt:1408–1468`; the *maths* is already shared (`RoundTripPlanner.kt`), only the driver is welded. `car/SpinScreen.kt:333` hardcodes `TravelMode.CAR`; iOS has zero `roundTrip` references |
| 3-candidate spin | ✅ | ❌ | ✅ | ❌ | iOS gets it **because it calls `shared/…/SpinPicker.kt:27`** (`SpinModel.swift:129`); car only calls `pickCandidate` once (`car/SpinScreen.kt:332`) |
| Direction / min-radius filters | ✅ | ❌ | ❌ | ❌ | `car/SpinScreen.kt:333` passes `bearing = null`, `:332` passes `0.0`; `SpinModel.swift:136` passes `bearing: nil` |
| Fog of war rendering | ✅ | ❌ | ❌ | ❌ | `ui/MapLibreMap.kt` `FogView` — a custom Android `View`. iOS *stores* traces (`TripRecorder.swift:307`) and *exposes the settings* (`SettingsScreen.swift:37–46, 148`) but `MapView.swift` never draws fog |
| Push-to-talk | ✅ | ❌ | ✅ | ❌ | both hand-written (`audio/PushToTalk.kt`, `PttAudio.swift`) |
| Convoy group spin (share/vote/commit) | ✅ | ❌ | ✅ | ❌ | protocol duplicated, not shared (§3.2) |
| Wear + BLE guidance relay | ✅ | ❌ | ❌ | n/a | `ui/MapScreen.kt:978–979, 1341, 1356–1357`. **Navigation driven from the head unit sends nothing to the watch** |
| Turn-by-turn navigation | ✅ | ✅ | ✅ | ✅ | the one feature on every surface — because `NavEngine.kt` is commonMain and stateless |
| Badges / coverage | ✅ | ❌ | ✅ | ❌ | `Badges.kt` + `Coverage.kt` are commonMain; both non-car surfaces get them free |

**The pattern is exact.** Every feature that reached iOS is one whose logic sits in
`shared/` (`NavEngine`, `SpinPicker`, `Badges`, `Coverage`, `Geocoder`, `RouteStore`,
`GeofenceEvaluator`). Every feature that did not is one whose logic sits inside a
`@Composable` or an Android `Service` (`ui/MapScreen.kt`'s road-hazard trio, `FogView`,
`NavRelay`, `BleNavServer`). Proposal 04's claim is **verified**: iOS ships without
the three road-hazard features precisely because they live inside a composable, and
their underlying shared primitives (`RoadRoulette.speedLimitWays`,
`SpeedCameras.near`, `snapSpeedLimitKmh`) are already sitting in commonMain, unused
by iOS.

---

## 5. Where the seam is, and whether it is a real port

For each cross-cutting concern, what commonMain actually does:

| Concern | commonMain mechanism | Is it a port? |
|---|---|---|
| **Key-value storage** | `expect class Prefs` (`Platform.kt:25`), 13 `actual` members on Android (`SharedPreferences`), 15 on iOS (`NSUserDefaults`, with a bag-name prefix at `Platform.ios.kt:21`) | **Yes** — a genuine expect/actual port |
| **File storage** | `expect fun appFilesDir(): Path` (`:44`) + `expect val fileSystem: FileSystem` (`:47`), wrapped by `Files.kt`'s 8 helpers. `Platform.kt:46` says *"a fake in tests"*, and okio's `FileSystem` is an abstract class, so that is achievable | **Yes** — the strongest seam in the repo |
| **HTTP** | `internal object Http` (`Http.kt:36`) — a concrete Ktor `HttpClient`. The engine is a *classpath* choice: `ktor-client-okhttp` (`shared/build.gradle.kts:46`), `ktor-client-darwin` (`:49`) | **Ktor's port, not Detour's.** Not injectable, not fakeable from `commonTest` without an engine plugin |
| **Time** | `internal fun nowMs()` (`Angles.kt:16`) over `kotlinx.datetime.Clock.System` — a top-level concrete function. Called directly at `Badges.kt:136`, `SavedPlaces.kt:38`, `RouteShare.kt:101` | **No.** A free function, not a `Clock` parameter. `GeofenceEvaluator` and `RouteGpx.parseGpx(text, nowMs)` take a time *argument* — the only two testable-by-clock pieces, and they got there by hand |
| **Coroutine dispatchers** | **Absent entirely.** Zero `Dispatchers.*` and zero `withContext` in commonMain. `Http.kt:27–30` documents the policy: *"Everything here is suspending… those wrappers become plain suspend calls"* | **N/A by design** — the concern was deleted rather than abstracted, which is better than a port |
| **Location** | **Absent entirely.** No `Fix` type, no location interface, no `expect`. `Platform.kt:12–14` explicitly excludes it | **No, deliberately.** Fixes are *pushed in* by each platform |
| **Logging** | **Absent entirely.** Zero `println`, zero logger in commonMain | No |
| **Dependency injection** | **Absent.** 33 `object` singletons, 0 interfaces in commonMain | No |

**Verdict on the seam:** Detour has a *storage* port (Prefs + FileSystem, 4 `expect`
declarations) and nothing else. Every other boundary is either a concrete singleton
(`Settings`, `RoutingServer`, `Geocoder`, `SyncClient` — 33 of them) or a concern
that was designed out (dispatchers) or fenced out (location, audio, Bluetooth).

The `Platform.kt:8–15` rule — *push data into the core, never let the core pull* —
is a real and consistently applied architectural principle. It is **the inverse of
hexagonal**: hexagonal defines driven ports (interfaces the core calls, adapters
implement); Detour defines almost none and instead requires each platform to write
its own driver. The 1,070 duplicated Swift lines in §3.2 are exactly what that rule
costs — each platform writes the pusher, so each platform writes the same pusher.

---

## 6. Constraints on moving more into commonMain

Complete list, with what `shared/` already does about each and what `MapScreen.kt`
would hit.

| Need | commonMain has | `shared/`'s existing answer | What MapScreen's logic uses today |
|---|---|---|---|
| **Wall clock** | no `System.currentTimeMillis()` | `Angles.kt:16 nowMs()` over `kotlinx-datetime`, declared `internal` | 12+ sites: `MapScreen.kt:676, 689, 707, 813, 838, 1032, 1070, 1152, 1189, 1371, 1679, 1830`. Every one becomes `nowMs()` — but `internal`, so it must be widened or the code must live in `:shared` |
| **Frame clock** | none, and none possible | — | `withFrameNanos` at `:1252` and `:1295`. **Cannot move at all.** This is the 88-line residue proposal 04 concedes at `04:215, 227` |
| **Coroutine dispatchers** | no `Dispatchers.IO` (not in coroutines' common metadata; this module has androidTarget + 3 ios targets and no jvm∩native intermediate source set) | zero uses; every API is `suspend` (`RoadRoulette.kt:263`, `SpeedCameras.kt:56`, `CircleFixes.kt:46`, `Geocoder.kt:32`) | 8 `withContext(Dispatchers.IO)` in `MapScreen.kt` — 6 vestigial (`:1037, 1075, 1113, 1379, 1477, 1883`), 2 genuine (`:570 SyncClient.sync`, `:1407 ExploredArea.load` — `ExploredArea.kt:50` is **not** `suspend`) |
| **Random** | `kotlin.random.Random` **is** in the common stdlib | used directly at `RoadRoulette.kt:85–90`, `PoiRoulette.kt:78–81`, `RoundTripPlanner.kt:159–220` | `MapScreen.kt:1421 Random.nextLong()` — **no obstacle** |
| **Geo maths** | `kotlin.math` — imported by 9 commonMain files | `Angles.kt`, `RoadRoulette.distanceMeters/withinWedge`, `NavEngine` | `MapScreen.kt:206–207` imports `abs`/`exp` only — **no obstacle** |
| **JSON** | kotlinx-serialization + `Json.kt`'s 114-line lenient `org.json` shim | works, and is what makes the wire formats parseable in common | `app/net/ConvoyLiveClient.kt` and `wear/` still use `org.json.JSONObject` directly — porting the relay client means rewriting ~150 lines of parsing onto `Json.kt` |
| **File IO** | okio + `Files.kt` + `expect val fileSystem` | works | `ExploredArea.load()` already shared |
| **HTTP** | Ktor via `Http.kt` | works | `RoutingServer.route`, `SpeedCameras.near`, `RoadRoulette.speedLimitWays` all already shared |
| **Logging** | nothing | nothing | `car/NavScreen.kt` uses `android.util.Log` at 8 sites; any move drops the logging or needs a port that does not exist |
| **Android/Apple types** | — | — | `ToneGenerator`, `AudioManager`, `MotionEvent`, `ViewConfiguration`, `LatLng`, `MapLibreMap`, `Context`, `Intent`, `ActivityResultContracts`. **Hard stop** |
| **Testing** | `kotlin("test")` (`shared/build.gradle.kts:52`), 60 `@Test` in `commonTest` | the best-tested surface in the repo | `app/` has 8 tests in 2 files; `iosApp/` has **zero** test target in `project.yml` |

**Net:** of the 800 net-movable domain lines in `MapScreen.kt` (per `10-eval-fact-audit.md`),
the blockers are **time** (mechanical — `nowMs()` exists), **`Dispatchers.IO`**
(mechanical — 6 of 8 are already vestigial), and **`withFrameNanos`** (~88 lines,
genuinely immovable). Everything else clears.

---

## 7. Verdict on the premise

**Partially shared, with a deliberate and clearly-drawn line — and the line is in
the wrong place for the iOS app.**

Not "independently developed": 29 of 45 `app/` files and 23 of 25 `iosApp/` files
consume `:shared`, and the routing, roulette, navigation, badge, coverage and
storage layers genuinely exist once. Not "built on a shared core" either: the entire
service tier — trip recording, the convoy relay protocol, circle sync, notification
policy, PTT, voice — is written twice, once in Kotlin and once in Swift, function
for function.

### The arithmetic

**Shared as a fraction of all client code:**

```
shared/commonMain                4,921
app/src/main                    16,705
iosApp/Detour                    5,065
wear/src                           185
                              ────────
total client Kotlin + Swift     26,876

4,921 / 26,876 = 18.3%
```

That number understates it, because ~10,500 of `app/`'s lines and ~3,100 of
`iosApp/`'s are Compose/SwiftUI view code that could never be shared.

**Shared as a fraction of logic (UI excluded):**

```
shared/commonMain                                        4,921
Android non-UI  (services 3,953 + root 259
                 + ~800 domain welded into ui/MapScreen.kt
                 + ~250 domain welded into car/)           5,262
iOS non-UI      (TripRecorder 442, ConvoyLiveClient 473,
                 CircleNotifications 248, CircleSync 135,
                 SpinModel 146, PttAudio 127, LocationProvider 79,
                 Format 59, NavVoice 53, LocationBroadcast 37,
                 + ~205 in MapScreen/NavScreen)            2,004
wear                                                         185
                                                       ─────────
total client logic                                      12,372

4,921 / 12,372 = 39.8%
```

> ### ≈40% of Detour's client logic is genuinely shared.
> ### ≈1,300 lines — ≈11% of client logic — is the same logic written two, three or four times.
> ### The remaining ≈49% is real, irreducible platform adapter code.

### What that means for hexagonal

**It is a medium step for `app/`↔`car/` and a rewrite for `iosApp/`.**

- The **storage** port already exists and is exemplary (4 `expect`, 28 `actual`,
  fakeable, documented at `Platform.kt:8–15`).
- There are **zero interfaces in commonMain** and **33 `object` singletons**. Every
  "driven port" hexagonal would want — a clock, a logger, an HTTP transport, a
  location source — is either a concrete singleton, a free function, or absent.
  Introducing them means touching 33 call-site families.
- The `app/`↔`car/` half needs no ports at all: both are in the same Gradle module
  (`app/src/main/java/com/jellemax/detour/{ui,car}`), so extracting the 11 duplicated
  items into plain classes under `app/` is a move, not a rewrite. **That recovers
  ≈199 lines with no architectural change whatsoever.**
- The `app/`↔`iosApp/` half is where hexagonal would pay, and where it costs most:
  moving the ≈1,070 duplicated Swift lines into commonMain means defining a location
  port and a clock port that `Platform.kt:12–14` explicitly refuses today. That is a
  reversal of a documented decision, not an incremental refactor — and it should be
  argued on its merits, because the decision as written is coherent.

### The one-line diagnosis

The repo does not lack a shared core — it has a good one, 4,921 lines with 60 tests.
What it lacks is a rule for **which side of the line stateful, fix-driven logic goes
on.** `NavEngine` is stateless, so it lives in `shared/` and every surface has
turn-by-turn. The road-hazard machines are stateful, so they live in a composable and
only Android has them. **Statefulness, not domain relevance, is what currently decides
whether a feature ships on iOS** — and that is the actual finding this audit produces.
