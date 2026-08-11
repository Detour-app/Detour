# Proposal 6 — Hexagonal / ports and adapters

**Thesis.** `MapScreen.kt` being 3,193 lines is not the disease. It is the fourth-largest
symptom of one mechanism: **Detour has four client surfaces and a core that is only allowed
to hold data logic, so every behavioural policy gets written once per surface.** The GraphHopper
sign→maneuver table exists **four** times, in four toolkits, and the fourth copy is already
wrong. The voice-announcement policy exists twice, in two languages, with identical constants.
The convoy relay protocol exists twice, in two languages, with a comment on the Swift side
saying the two *have* to agree. The trip auto-detection thresholds exist twice. `MapScreen.kt`
is where this happens to hurt most, because the phone map is the surface that got the features
first — but splitting that one file changes nothing about the mechanism.

The hexagonal answer: the driving/navigation/spin **domain** lives in `shared/` commonMain as
framework-free logic that owns no I/O and no scope; everything platform-specific is either a
**port** the core declares (a small, closed set) or an **adapter** that pushes data in and
renders results out. `MapScreen.kt`, `car/NavScreen.kt`, `iosApp/Detour/MapScreen.swift` and
`wear/MainActivity.kt` all become adapters over the same core.

**Stated up front, so nobody has to catch me at it:** the repo has *already adopted* this
pattern, in writing, and has already drawn the boundary in a specific place
(`CONTRIBUTING.md:23-32`, `docs/IOS_PORT.md:17-21`). This proposal is not "adopt hexagonal".
It is "you are 60% hexagonal, the remaining 40% is exactly where all the duplication is, and
here is the price of finishing". Where I want to add ports the project's own written rule says
*don't* (`CONTRIBUTING.md:26-29`), I say so and argue the case rather than pretending the rule
isn't there. And §"Honest verdict" says plainly that on the question this folder was convened
to answer — how to split one file — this proposal does not beat `04`. It supersets it.

---

## Where the repo already is

Counted, not assumed. Everything below is `wc -l` / `find` / `grep` against the working tree
at `07fe490`.

### Module sizes

| Module | What it is | Size |
|---|---|---:|
| `app/` | Android phone Compose **+ Android Auto** (`car/`) in one Gradle module | 16,705 lines of Kotlin under `src/main/java` |
| `shared/` | KMP core, `commonMain` compiles for Android + 3 iOS targets | 6,128 lines total; **4,921 in commonMain** |
| `wear/` | Wear OS app | **185 lines**, 2 files |
| `iosApp/` | SwiftUI app | 5,065 lines of Swift, 25 files |
| `backend/` | ASP.NET backend | 151 `.cs` files, ~14,445 lines |
| `server/` | Python sync server + GraphHopper/Photon install scripts | `server/sync/sync_server.py` + scripts |

Largest files in `app/`: `ui/MapScreen.kt` 3,193 · `tracking/TripTrackingService.kt` 1,333 ·
`ui/SettingsScreen.kt` 1,199 · `ui/FriendsScreen.kt` 939 · `ui/MapLibreMap.kt` 764 ·
`car/CarMapRenderer.kt` 663. The `car/` package is **1,985 lines** across 7 files.

### What `shared/` actually is

43 `.kt` files: **36 in commonMain** (4,921 lines), 3 in commonTest (**60 `@Test`** —
`ParsingTest` 24, `GroupsTest` 22, `RoutesTest` 14), 1 androidMain, 2 iosMain,
1 androidUnitTest. Consumed by **`app/`** (`app/build.gradle.kts:135`,
`implementation(project(":shared"))`) and **`iosApp/`** (`import DetourShared` across the
Swift files). **Not by `wear/`** — `wear/build.gradle.kts` declares six dependencies and none
of them is `project(":shared")`. Two consumers, not three; the brief was wrong and the fact
audit already caught it (`10-eval-fact-audit.md` 4.6).

### The port surface that already exists

The brief asked me to count `expect`/`actual`. The answer is small and it is the single most
important fact in this document:

```
shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt:25  expect class Prefs
shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt:41  expect fun prefs(name: String): Prefs
shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt:44  expect fun appFilesDir(): Path
shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt:47  expect val fileSystem: FileSystem
```

**Four `expect` declarations in the whole module**, satisfied by `actual`s in exactly two files
(`shared/src/androidMain/.../Platform.android.kt:30-50`,
`shared/src/iosMain/.../Platform.ios.kt:17-69`). There is a fifth, implicit port: Ktor, whose
engine is selected off the classpath per target (`shared/build.gradle.kts:48`
`ktor-client-okhttp`, `:51` `ktor-client-darwin`) — a textbook driven adapter that costs the
core zero declarations because the library ships the seam.

So: **the port abstraction already exists, it is deliberately tiny, and the reason is written
down.** `Platform.kt:11-14`:

> *"Deliberately not a general 'platform services' interface. Anything bigger than this —
> location, audio, Bluetooth — stays on the platform side of the boundary and is pushed
> **into** the core, rather than the core reaching out for it. That is what keeps this file
> from growing into a second app."*

`CONTRIBUTING.md:23-29` says the same thing normatively, and adds the enforcement rule:
*"`Platform.kt` deliberately expects only three things … so wanting to add a fourth is the
signal to push the dependency in from the platform instead."* `docs/IOS_PORT.md:17-21` repeats
it. `iosApp/Detour/LocationProvider.swift:11-12` repeats it a third time, at the call site:
*"Nothing here belongs in the shared core — the core is handed fixes, it never reaches for
them."*

That is **hexagonal with a deliberate variant**: no driving-side ports at all (adapters call
in), and only three driven ports (plus Ktor). It is a defensible, coherent choice and it has
worked — `commonMain` is 4,921 lines with no `java.*`, no `Dispatchers.*` and no
`withContext` anywhere in it.

### What is *not* in the core, and how many times it is therefore written

This is the evidence no earlier proposal collected, because all five were scoped to one file.

| # | Concern | Copies | Where | Divergence |
|---|---|---:|---|---|
| X1 | **GraphHopper sign → maneuver** | **4** | `ui/Navigation.kt:57-71` (13 branches → Compose `ImageVector`) · `car/NavScreen.kt:575-593` (**14** → `androidx.car.app.Maneuver`) · `wear/MainActivity.kt:53-67` (13 → Wear `ImageVector`, comment: *"mirrors phone app's ui/Navigation.kt"*) · `iosApp/Detour/NavScreen.swift:223-236` (**9** → SF Symbol) | **Yes, live, and three-way.** The phone and watch tables are identical (13 branches). The **car adds a `-6` case** (`NavScreen.kt:580`, roundabout-exit-CCW) that neither of them has. The **Swift copy has no case for `-98`, `-8`, `8`, `-7`, `7`** — they fall through to `arrow.up`, "carry straight on" — and maps `-3` to `arrow.uturn.left`, so on iOS a **sharp left renders as a U-turn** and a **U-turn renders as straight ahead**. |
| X2 | **Voice announcement policy** | 2 | `car/NavScreen.kt:64-66` (`VOICE_FAR_M=800`, `VOICE_NEAR_M=300`, `VOICE_NOW_M=80`) + `:287-313` (`announce`, phase latch, first-prompt override) + `:524-529` (`spokenDistance`) ≈ 38 lines · `iosApp/Detour/NavScreen.swift:142-144` + `:167-195` + `:203-210` ≈ 40 lines | None yet — byte-for-byte the same rules in two languages. `FUTURE.md:27` asks for it on the phone map, which today has **no TTS at all** (no `TextToSpeech` import in `MapScreen.kt`). Building that makes **copy 3**. |
| X3 | **Convoy relay protocol + group-spin vote rule** | 2 | `app/.../net/ConvoyLiveClient.kt` (625) · `iosApp/Detour/ConvoyLiveClient.swift` (473). Method-for-method parallel: `join`/`leave`/`sendPttStart`/`sendPttEnd`/`sendAudioChunk`/`sendSpinOffer`/`sendSpinVote`/`clearSpinOffer`/`forwardLocation`/`prune…`/`connectAndAwaitClose`. Wire types `FriendPosition`, `SpinCandidate`, `GroupSpin` declared twice. | The Swift KDoc at `ConvoyLiveClient.swift:35-36` states the risk itself: *"Identical rule to the Android client's, deliberately: the two have to agree or a convoy splits across two destinations."* |
| X4 | **Trip auto-detection thresholds** | 2 | `TripTrackingService.kt:139-…` companion block · `iosApp/Detour/TripRecorder.swift:41-68` — **23** `private static let`s under a header that says *"Auto-detection thresholds (identical to the Android service)"* (`:39`) | I spot-checked eight (`FAST_SPEED_MPS`/`fastSpeedMps` 7.0, `PROBE_SPEED_MPS` 4.0, `FAST_FIXES_TO_START` 3, `MIN_FAST_RUN_MS` 8 000, `MIN_FAST_RUN_METERS` 120.0, `MAX_START_ACCURACY_M` 25, `STATIONARY_END_MS` 5×60 000, `MIN_AUTO_TRIP_METERS` 500.0) — all eight match today. |
| X5 | **PTT wire format** | 2 | `audio/PushToTalk.kt:27-32` (16 kHz, `SAMPLE_RATE/25` = 40 ms, 16-bit PCM) · `iosApp/Detour/PttAudio.swift:15-16` | None yet. Six lines, but a silent mismatch is unintelligible audio. |
| X6 | **`nowMs()`** | 2 | `shared/.../data/Angles.kt:16` (kotlinx-datetime) · `iosApp/Detour/TripRecorder.swift:442` | None. |
| X7 | **`MapScreen.kt` ↔ `car/`** | 2 | 11 verified items, **≈199 lines phone-side / ≈186 car-side** | **Five of eleven have drifted, and the car copy is the better one on D6/D7/D9.** Established in `10-eval-fact-audit.md` Group 5; I re-verified nothing here and take it as given. |

Seven duplicated policies. **Six of the seven cross a language or toolkit boundary**, which
means no amount of file-splitting inside `app/` reaches them. That is the case for this
proposal, and it is the whole case.

### One subsystem already does it right — use it as the template

`shared/.../data/CircleEvents.kt` (210 lines) owns the *decision and the wording*:
`placeEventFromRelayFrame`, `notificationText`, `catchUpSummaryText`. Both platforms call it —
Android via `notif/PlaceNotifications.kt` + `notif/CircleNotifyService.kt`, iOS via
`CircleEventsKt.placeEventFromRelayFrame` / `.notificationText` / `.catchUpSummaryText`
(three call sites in `iosApp/Detour/`). The platform files own only channels, permissions and
delivery. There is even a unit test for the planning half
(`app/src/test/java/com/jellemax/detour/notif/PlaceNotificationsTest.kt`).

Notifications were the hardest candidate on paper — a notification is about as
platform-specific as software gets — and the split works. That is the strongest in-repo
evidence that the pattern generalises, and it is a stronger argument than any diagram.

---

## The pattern, stated precisely for THIS repo

### The rule, restated so it survives contact with the existing one

The repo's rule is *"the core is handed things, it never reaches for them."* I am **not**
proposing to overturn it. I am proposing to split it, because it currently conflates two
different directions:

- **Inbound (driving) — keep push, add nothing.** Location fixes, microphone frames, BLE
  telemetry, sensor data, lifecycle. The core must never own a `LocationPort`. Push is not a
  compromise here, it is strictly better: a `Flow<Fix>` from `TripTrackingService.lastFix`
  (`TripTrackingService.kt:233`) and a `[CLLocation]` from `LocationProvider.swift` both
  reduce to "call `onFix(...)` with these values", and a test feeds a `List<Fix>`. **No port,
  no fake, no interface.** This is the part `CONTRIBUTING.md:26-29` is right about.
- **Outbound (driven) — the core must be able to *say* things.** Speak this. Chime. Open a
  socket to this URL. Ask Overpass. Read a preference. Tell me the time. These are the ones
  where "push it in" degenerates: you cannot push a *chime* in, because the core is what
  decides when to chime, and today that decision therefore lives in the adapter
  (`MapScreen.kt:1161-1164`) — which is exactly why the car's copy diverged
  (`car/NavScreen.kt:405` uses `progress?.speedLimitKmh` only; the phone falls back to the
  ambient limit).

Every port below is on the **outbound** side. That is the honest boundary of my disagreement
with the written rule, and it is a narrower disagreement than "let's add ports".

### The ports

Real Kotlin, in `shared/src/commonMain/kotlin/com/jellemax/detour/`. Nine, of which **four
already exist in substance** and two are optional.

```kotlin
// ── 1. Clock ──────────────────────────────────────────── NEW (trivial)
// port: com/jellemax/detour/core/Clock.kt
package com.jellemax.detour.core

/** Wall clock in epoch millis. The only reason this is an interface and not
 *  Angles.kt's nowMs() is that four GPS state machines are path-dependent on
 *  it and a test has to be able to advance it by hand. */
fun interface Clock {
    fun nowMs(): Long
    companion object {
        /** kotlinx-datetime, i.e. the existing internal nowMs() (Angles.kt:16). */
        val System: Clock
    }
}
```
Wraps: `shared/.../data/Angles.kt:16` (`internal fun nowMs()`). Today's callers in the code
that would move use `System.currentTimeMillis()` directly — `MapScreen.kt:1032`, `:1070`,
`:1152`, `:1189` — which is precisely why they are untestable.

```kotlin
// ── 2. RoadData (Overpass/OSM) ─────────────────── EXISTS as free functions
// port: com/jellemax/detour/core/RoadData.kt
interface RoadData {
    suspend fun speedLimitWays(center: LatLon, radiusMeters: Double): List<RoadRoulette.SpeedLimitWay>
    suspend fun speedCamerasNear(center: LatLon, radiusMeters: Double): SpeedCameras.Result?
}
```
Wraps: `shared/.../data/RoadRoulette.kt:263 speedLimitWays(...)` and
`shared/.../data/SpeedCameras.kt:56 near(...)`. Both are **already** `suspend`, already in
commonMain, already Ktor-backed. The interface buys exactly one thing — a stub in
`commonTest`, which does not exist today for any network path. If nobody writes those tests,
do not add this port; call the objects directly.

```kotlin
// ── 3. Router (GraphHopper) ────────────────────── EXISTS as an object
interface Router {
    suspend fun route(config: ServerConfig, from: LatLon, to: LatLon, mode: TravelMode): RouteResult?
    suspend fun roundTrip(config: ServerConfig, from: LatLon, meters: Double, seed: Int, mode: TravelMode): RouteResult?
}
```
Wraps: `shared/.../data/RoutingServer.kt` (396 lines, `object RoutingServer`, already common,
already Ktor). Same caveat as `RoadData`: the interface exists only to be faked.

```kotlin
// ── 4. PlaceSearch (Photon geocoder) ───────────── EXISTS as an object
interface PlaceSearch {
    suspend fun search(query: String, near: LatLon?, limit: Int = 8): List<GeocodeResult>
}
```
Wraps: `shared/.../data/Geocoder.kt:32`. Note the Android app *also* has a second geocoder
path — `android.location.Geocoder` is imported in `MapScreen.kt` and used in the search
debounce at `:1883`. Two geocoders behind one port is a small, real win.

```kotlin
// ── 5. KeyValueStore + FileSystem ───────────────────────── ALREADY A PORT
expect class Prefs { … }            // Platform.kt:25
expect fun prefs(name: String): Prefs        // :41
expect fun appFilesDir(): Path               // :44
expect val fileSystem: FileSystem            // :47
```
Adapters: `Platform.android.kt` (SharedPreferences, `FileSystem.SYSTEM`),
`Platform.ios.kt` (NSUserDefaults, `FileSystem.SYSTEM`). **No work.**

```kotlin
// ── 6. Http ───────────────────────────── ALREADY A PORT (Ktor's, not ours)
```
`shared/.../data/Http.kt:37 internal object Http` over `io.ktor.client.HttpClient`; engine per
target. **No work.** `Http.kt:27-31` documents the one structural consequence: everything
touching the network is `suspend`, so callers do their own dispatching.

```kotlin
// ── 7. Announcer (TTS) ─────────────────────────────────────────── NEW
/** "Say this out loud." Nothing more: the core decides *what* and *when*;
 *  audio focus, ducking, engine init and language selection are the
 *  adapter's problem and are genuinely different per platform. */
fun interface Announcer {
    fun say(text: String)
}
```
Adapters that already exist and would implement it unchanged:
`app/.../car/NavVoice.kt` (150 lines: `TextToSpeech`, `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`,
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, per-utterance focus) and
`iosApp/Detour/NavVoice.swift` (53 lines: `AVSpeechSynthesizer`, `.voicePrompt`,
`.duckOthers`). The *policy* that calls them — thresholds, phase latch, phrasing — is X2 and
moves into the core as `GuidanceAnnouncer`.

```kotlin
// ── 8. Alerter (non-speech cues) ───────────────────────────────── NEW
enum class Cue { SPEED_CAMERA_AHEAD, OFF_ROUTE, ARRIVED }
fun interface Alerter { fun cue(cue: Cue) }
```
Adapters: `MapScreen.kt:1141-1143`+`:1163` (`ToneGenerator(STREAM_NOTIFICATION, 90)`,
`TONE_PROP_BEEP2`), `car/NavScreen.kt:412-414` (speaks **and** toasts), iOS today has none.
Merging these forces the user-visible decision `DECISION.md` already flags (`README.md:383-385`
claims parity that does not exist) — so `Alerter` is the port, and *which* cue policy is
correct is a separate, deliberate, release-noted commit.

```kotlin
// ── 9. LiveTransport (convoy WebSocket) ────────────────────────── NEW
/** A duplex text+binary channel. Everything above it — join/location/ptt_*/
 *  spin_* frame vocabulary, peer pruning, vote resolution — is core. */
interface LiveTransport {
    suspend fun connect(url: String, headers: Map<String, String>): Session
    interface Session {
        suspend fun send(text: String)
        suspend fun send(bytes: ByteArray)
        suspend fun receive(): Frame?      // null = closed
        fun close()
    }
    sealed interface Frame { … }
}
```
Adapters: `okhttp3.WebSocket` (`net/ConvoyLiveClient.kt:123-129`, with `pingInterval`) and
`URLSessionWebSocketTask` (`iosApp/Detour/ConvoyLiveClient.swift:101`, with a hand-scheduled
ping because URLSession has no `pingInterval` — `docs/IOS_PORT.md` records that difference as
deliberate). **This is the largest single port and the largest single prize (X3).**

### Ports I deliberately do **not** propose

- **`LocationSource`.** Push stays push. `CONTRIBUTING.md:23-25` and
  `LocationProvider.swift:11-12` are right.
- **`MapRenderer`.** Three map stacks with genuinely different capabilities: MapLibre Android
  (`ui/MapLibreMap.kt`, 764 lines, GeoJSON sources + a custom `FogView`),
  `androidx.car.app` `SurfaceCallback` (`car/CarMapRenderer.kt`, 663 lines, which also owns
  its own render loop on a `delay(CAM_FRAME_MS)` timer — 33 ms, `CarMapRenderer.kt:61,392` — rather than `withFrameNanos`, and says why at `:57-60`), and `iosApp/Detour/MapView.swift` (213 lines). An
  interface wide enough to cover all three is a second map SDK. The core should emit a
  **scene description** (data classes: route polyline, driven prefix, markers, fog circles)
  and let each adapter draw it. Data crosses the boundary; behaviour does not.
- **`Dispatcher`.** Cannot exist usefully — see the next section.
- **`Notifier`.** `CircleEvents.kt` already proves the split works with plain data + a text
  builder. Adding an interface would be ceremony over a solved problem.

---

## The core: what moves into it

### From `MapScreen.kt`

I adopt proposal 04's classification wholesale rather than redo it: the fact audit parsed all
101 rows programmatically, confirmed contiguity, non-overlap and a sum of exactly 3,193, and
rated it *"the single most rigorously checkable number in the folder, and it checks out"*
(`10-eval-fact-audit.md` 1.5). The numbers below are 04's, audited.

| Lines | Concern | Destination |
|---|---|---|
| `221-231`, `232-255` | `smoothBearing` + easing tau/eps constants | `core/CameraEase.kt` (math only; the loop stays per-platform) |
| `261-268` | `CURVY_CANDIDATES` | `core/SpinSession.kt` |
| `269-275`, `696-713` | follow-camera park/resume policy | `core/FollowCamera.kt` |
| `276-280`, `1098-1119` | circle-fix poll cadence + loop | `core/CircleFixFeed.kt` |
| `281-315`, `1169-1231` | trajectcontrole gate + section state machine (**63 lines, 4 exit conditions, 5 closure-local vars**) | `core/SectionAverageTracker.kt` |
| `321-368` | group-spin wire mapping + tie-break + `leadingSpinIndex` | `core/GroupSpinRules.kt` — **blocked** until X3, see below |
| `369-415` | `SpinResult`/`SpinResultHolder`/`seedRouteNavigation` | `core/SpinResults.kt` |
| `446-466`, `514-554` | spin/nav/hazard/camera state declarations (62 lines) | absorbed by the session objects; ~21 lines return as collectors |
| `803-871` | `choose` / `displayCandidates` / `commitSpinCandidate` / vote resolution | `core/SpinSession.kt` + `core/GroupSpinRules.kt` |
| `970-1017`, `1344-1391` | start/stop navigation, progress → arrival → reroute | `core/NavSession.kt` + `core/NavPolicy.kt` |
| `1018-1057` | ambient speed limit: prefetch throttle, heading snap, 3-miss clear | `core/SpeedLimitService.kt` |
| `1058-1084` | speed-camera prefetch | `core/SpeedCameraFeed.kt` |
| `1134-1168` | camera-warning latch + over-limit test | `core/CameraWarner.kt` (emits `Cue`, does not beep) |
| `1232-1264` | camera + speedometer easing targets | `core/CameraEase.kt` / `core/SpeedEase.kt` |
| `1392-1528` | `spin()` (123 lines) + `selectMode` | `core/SpinSession.kt`; `1472-1489` **deleted** in favour of the existing `SpinPicker.kt:27 pickThreeCandidates` |
| `1865-1894` | search debounce + recents merge | `core/PlaceSearchSession.kt` |
| `2151-2165` | `navAppUsableDirectly` | `core/NavAppPolicy.kt` |

Net out of `MapScreen.kt`: **≈800 code lines + ~38 orphaned imports = ≈838 (26%)**, landing at
**≈2,355 lines** — 04's number, audited TRUE (`10-eval-fact-audit.md` 2.4). This proposal does
**not** improve on that figure, because it is the same extraction.

### From `car/` (1,985 lines)

- `CarMapRenderer.kt:53-69` (6 tuning constants, byte-identical to `MapScreen.kt:236-238,253-255`),
  `:398-415` (ease step), `:468-475` (second `smoothBearing`), `:74-78`+`:139-151` (circle poll):
  **→ core, ~61 lines deleted.**
- `NavScreen.kt:131-137`, `:378-392`, `:396-410`, `:242-252`: **→ core, ~51 lines deleted.**
  Extract from the **car** copy, not the phone's — the car fetches off the collector with an
  `isActive` guard and names its constants; the phone does neither
  (`10-eval-fact-audit.md` D6/D7/D9, and `DECISION.md` contradiction 3 already resolved this).
- `SpinScreen.kt:93-99`, `:264-295`, `:120-121`: **→ core, ~42 lines deleted.**
- `NavScreen.kt:64-66`, `:287-313`, `:524-529` (X2 voice policy) → `core/GuidanceAnnouncer.kt`;
  `car/NavVoice.kt` stays whole as the `Announcer` adapter.
- `NavScreen.kt:575-593` (X1) → the *classification* becomes a core `Maneuver` enum; the
  `Maneuver.TYPE_*` mapping stays in `car/` because it is `androidx.car.app` vocabulary. Note
  `docs/ANDROID_AUTO.md:92-100`: `Maneuver.Builder(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW).build()`
  throws without an exit number, so the adapter has real, car-specific work to do. Good — that
  is what an adapter is for.

### From `tracking/TripTrackingService.kt` (1,333 lines)

Only the **decision core** moves: the start/stop detector (the sustained-run + probe-window
rules around `:1003`, `:1031-1032`) and the 23 tuning constants from `:139` onward (X4).
Everything else — foreground service, `ServiceCompat.startForeground`, notification channel,
activity-recognition transitions, `FusedLocationProviderClient`, `SensorManager`,
`BluetoothProfile`, Play Services — stays and is the adapter. Honest estimate: **~150-200 of
1,333 lines** are core. The service does not shrink dramatically; the *thresholds stop being
written twice*.

### From `net/ConvoyLiveClient.kt` (625 lines)

The frame vocabulary (`join`, `location`, `ptt_start`/`ptt_audio`/`ptt_end`,
`spin_offer`/`spin_vote`, `place_event`, `joined`, `error`), peer pruning (`:399-409`), the
group-spin resolution rule, and the `FriendPosition`/`SpinCandidate`/`GroupSpin` types move to
core. OkHttp, `Base64`, `Context`, `BuildConfig` and the reconnect/backoff scheduling stay
behind `LiveTransport`. **I have not attempted a line-level protocol/transport split**, so I
quote the pair total (625 + 473 = 1,098 lines implementing one protocol) as the size of the
problem, not as the extractable amount. Anyone costing this step must do that split first.

### From `ui/Navigation.kt`, `wear/`, `iosApp/`

X1: one core `Maneuver` classification (`fun maneuver(sign: Int, exitNumber: Int): Maneuver`),
four adapters mapping the enum to their own icon vocabulary. ~66 lines of table become ~20
lines of core + four `when`s that a reviewer can eyeball for completeness because the enum is
exhaustive. **`wear/` is the awkward one** — see the cost analysis in Cons.

### What the core must **never** own

`android.content.Context` · `Activity`/`Service`/`Lifecycle` · any `androidx.*` ·
`org.maplibre.*` · `androidx.car.app.*` · `CoreLocation`/`AVFoundation`/`UIKit` ·
`CoroutineScope` (no service constructs one — the caller drives `suspend fun run(...)`) ·
`Dispatchers.*` · the Compose `withFrameNanos` loop · `ToneGenerator`/`TextToSpeech`/
`AudioManager` · `NotificationManager` · permissions · `R` resources · `BuildConfig` (already
solved: `BuildDefaults.kt`, pushed in at startup) · `okhttp3`/`URLSession` directly ·
**and — per the repo's own rule — it must never *reach for* location, audio, Bluetooth or
sensors.**

---

## Adapters per surface

### `app/` — Android phone Compose

**Becomes adapter:** `MapScreen.kt`'s remaining ≈2,355 lines are ~1,545 genuine UI + ~327
Android glue + ~178 MapLibre glue + imports + ~134 domain residue (04 §2.2/§2.3, audited).
`ui/MapLibreMap.kt` (764) is already pure adapter and does not change.
`tracking/TripTrackingService.kt` keeps ~1,100 of 1,333 lines. `notif/` is already the
template shape.

**Disappears:** the ~800 domain lines from `MapScreen.kt`, plus (only after X2/X1 land) the
duplicate sign table in `ui/Navigation.kt:57-71` collapses to a `when` over the core enum.

**New adapter code:** `Announcer`/`Alerter` implementations (~40 lines, mostly moved from
`MapScreen.kt:1141-1163`), a `LiveTransport` over OkHttp (~120 lines, extracted from
`ConvoyLiveClient.kt`), `Clock.System` binding (0 — commonMain provides it).

**Note that `car/` is in this same module** (`app/src/main/java/com/jellemax/detour/car/`).
The fact audit's 7.9 matters here: *for the phone↔car duplication, `:shared` is not required
at all* — a plain `app/`-level extraction would do. The KMP hop is mandatory only for iOS and
(if we take it) wear. Do not let a hexagonal frame obscure that.

### `app/.../car/` — Android Auto

**Becomes adapter:** `CarMapRenderer.kt` keeps its `SurfaceCallback`, its 33 ms timer render
loop and its own camera application (~590 of 663 lines). `NavScreen.kt` keeps template
building, `NavigationManager.updateTrip`, the `Maneuver` mapping and toasts (~480 of 593).
`SpinScreen.kt` keeps templates and `fetchLocation` (~306 of 352). `NavVoice.kt` (150) becomes
the `Announcer` adapter, unchanged.

**Disappears:** ~154 lines of duplicated policy (61 + 51 + 42) and, after X2, ~38 more.

**Gains:** the trajectcontrole tracker, which the car does not have today and which would
otherwise cost a 63-line copy; the round-trip curvy spin (`SpinScreen.kt` currently does a
single `pickCandidate` roll). 04 §9 makes this argument and it is the strongest one in the
folder: *the car is not missing these features because nobody wanted them, it is missing them
because they are welded to a Compose function.*

### `wear/` — 185 lines, 2 files

**Today:** `MainActivity.kt` (150) receives a JSON blob over the Wearable Message API from
`app/.../wear/NavRelay.kt` and re-implements `signIcon` (`:53-68`); `NavListenerService.kt`
(35) is the receiver.

**Under this proposal:** it *could* take `implementation(project(":shared"))` and use the core
`Maneuver` enum, deleting 16 lines. **I do not recommend it.** `wear/` is
`com.android.application` + Kotlin Android with `isMinifyEnabled = false`
(`wear/build.gradle.kts:41`); adding `:shared` pulls ktor-core, ktor-encoding, okio,
kotlinx-serialization and kotlinx-datetime into an unminified watch APK to deduplicate a
16-line `when`. That is a bad trade and I am not going to pretend otherwise. The honest
hexagonal answer for `wear/` is: **the phone is the adapter, the watch is a dumb display**,
and the right fix is for `NavRelay.kt:23-29` to send the core `Maneuver` enum name rather than
the raw GraphHopper `sign`, so the watch's `when` covers an exhaustive enum instead of 13 magic
integers. ~10 lines, no new dependency. `docs/IOS_PORT.md:87` notes a watchOS app would reuse
nothing from `wear/` either.

### `iosApp/` — SwiftUI

**Becomes adapter:** `MapView.swift` (213), `LocationProvider.swift` (79),
`NavVoice.swift` (53) → `Announcer`, `PttAudio.swift` (127), `CircleNotifications.swift`
(248), the SwiftUI screens.

**Disappears / shrinks:** `ConvoyLiveClient.swift` 473 → roughly the `URLSessionWebSocketTask`
plumbing plus the ping scheduler (X3). `NavScreen.swift`'s `NavModel.announce` + thresholds +
`spokenDistance` (~40 lines, X2) and `maneuverIcon` (16 lines, X1 — **and the bug goes with
it**). `TripRecorder.swift`'s 23 threshold constants (X4).

**Gains, for free, features it does not have today:** `grep -rn 'SpeedCameras|snapSpeedLimit|
SectionAverage' iosApp/` returns nothing — the ambient limit sign, camera warnings and
trajectcontrole exist only inside `MapScreen.kt`'s composable body. 04 verified this and the
fit evaluator re-verified it (`11-eval-fit-maintainability.md:462-467`).

**New adapter code, and this is the real cost:** every new `StateFlow` the core exposes needs a
`Watcher` subclass in `shared/src/iosMain/.../FlowWatcher.kt`. See the next section.

### `backend/` + `server/`

Out of scope, and I want to be explicit rather than vague. `backend/` is 151 C# files;
`server/sync/sync_server.py` is Python. Neither can consume a Kotlin core. The contract
between them and the clients is the **wire protocol**, not a port — which is exactly why X3
matters: the protocol is currently specified by two Kotlin/Swift implementations and a Python
server, with the spec living in prose comments that point at each other
(`ConvoyLiveClient.swift:44-47` — *"Speaks the same protocol as the Android client verbatim …
because the relay on the other end is one server serving both"*). Putting the frame vocabulary
in one Kotlin file does not make the Python server share it, but it does reduce three
implementations to two.

---

## Honest constraints of commonMain in this repo

The complete list of what the moved code uses today and cannot have, checked against
`shared/build.gradle.kts` and — as the brief asked — `gradle/libs.versions.toml`, **which does
not exist**. `gradle/` contains only `wrapper/`; every version in this repo is an inline string
literal. The fact audit noted the same (6.8). Consequence: "align the core's dependency
versions" is a manual string edit, and `app/build.gradle.kts:152-159` already carries a comment
explaining that it re-declares `kotlinx-serialization-json:1.7.1` by hand to match `shared`'s.

| Needed by the moved code | Available in commonMain? | How the port resolves it |
|---|---|---|
| `System.currentTimeMillis()` — `MapScreen.kt:1032`, `:1070`, `:1152`, `:1189` | **No** | `Clock` port (#1), backed by `Angles.kt:16 nowMs()` → kotlinx-datetime `0.6.0` (`shared/build.gradle.kts:45`). **Solved, and it is the change that makes the state machines testable.** |
| `Dispatchers.IO` / `withContext` — 12 sites in `MapScreen.kt`, incl. `:1037`, `:1075`, `:1113`, `:1379`, `:1407`, `:1477`, `:1883` | **No.** `Dispatchers.IO` is not published in coroutines' common metadata, and this module's hierarchy (`androidTarget` + 3 iOS targets, `kotlin.mpp.applyDefaultHierarchyTemplate=true`, `gradle.properties:6`) has **no jvm∩native intermediate source set**. Verified consequence: zero `Dispatchers.*` and zero `withContext` in commonMain today | **Partly.** Network sites: dropped — Ktor suspends, and `Http.kt:27-31` states this is the intended shape. File sites (`ExploredArea.load()` at `:1407` is **not** suspend; `SyncClient.sync()` at `:570`): must be **hoisted to the platform caller**, which the ports do *not* solve. ~12 per-site judgements; one wrong call on a file read puts blocking I/O on the main thread. `12-eval-risk-sequencing.md` 4.5 rates this an ANR risk and it is right. |
| `kotlinx.coroutines` — `async`, `awaitAll`, `coroutineScope`, `withTimeout`, `delay`, `Flow`, `StateFlow`, `SharedFlow`, `CancellationException` | **Yes**, all in common. `kotlinx-coroutines-core:1.8.1` (`shared/build.gradle.kts:36`) | No port needed. |
| Ktor client | **Yes**, `ktor-client-core:2.3.12` (`:38`) + `ktor-client-encoding` (`:41`); engines okhttp (`:48`) / darwin (`:51`) | Already the HTTP adapter. |
| JSON | **Yes**, `kotlinx-serialization-json:1.7.1` (`:37`), with `Json.kt`'s `opt*` helpers replacing `org.json` | `net/ConvoyLiveClient.kt` uses `org.json.JSONObject`/`JSONArray` today — X3 requires porting the frame parsing to kotlinx first. Real work, ~a day. |
| File I/O | **Yes**, `okio:3.9.0` (`:42`) + the three `expect`s | Already a port. |
| `java.util.Locale`, `SimpleDateFormat` (`ui/Navigation.kt:52-54`) | **No** | **Not solved.** Date/time *formatting* has no commonMain answer in this dependency set. Formatting stays in the adapters — correct anyway, since it is locale-facing presentation. |
| `android.util.Log` | **No**, and shared has no logging at all today | **Not solved and not proposed.** Adding a `Logger` port would be a fifth `expect` and I decline it; the core returns values, the adapter logs them. |
| `kotlin.math`, `kotlin.random.Random` | **Yes** | No port. |
| `expect class` still Beta | Needs `-Xexpect-actual-classes` (`shared/build.gradle.kts:17`) | Already on. Every extra `expect class` keeps that flag load-bearing; the ports above are `interface`s precisely to avoid adding more. |

### The Swift boundary — the constraint nobody costed

`shared/src/iosMain/.../FlowWatcher.kt` is **196 lines of hand-written boilerplate that exists
solely because Kotlin flows are not directly consumable from Swift.** Its own KDoc (`:10-25`)
says why: *"Collecting a flow at all needs a coroutine, which Swift cannot start; and
Kotlin/Native erases a generic's type argument on the way to Objective-C."* The result is
**one `Watcher` subclass per element type** — `BoolWatcher`, `FloatWatcher`, `IntWatcher`,
`StringWatcher`, `TravelModeWatcher`, `RouteColorWatcher`, `SavedPlacesWatcher`,
`SavedRoutesWatcher`, `TracesWatcher` — plus `SettingsFlows`/`StoreFlows` factory objects,
plus a `SettingsValues` object for one-shot reads, plus an `Enums` object because
`enum.entries` has **no ObjC representation at all** and `const val` name mangling is
compiler-version-dependent.

Concretely, for this proposal: **every new `StateFlow<T>` the core exposes costs a new Watcher
subclass (~8 lines) and a factory line before any SwiftUI screen can bind to it.** A
`NavSession` exposing `route`, `progress`, `rerouting` and `events` costs four. That is the
per-port tax on the iOS side and it is not optional.

Two more Swift facts, verified in the tree:

- **Nullable primitives box.** `iosApp/Detour/NavScreen.swift:97` reads
  `progress?.remainingTimeMs?.int64Value` and `:100` reads
  `progress?.speedLimitKmh?.doubleValue` — `Long?`/`Double?` arrive as `KotlinLong?`/
  `KotlinDouble?`. Every optional numeric field in a core type costs an unwrap at the call site.
- **`suspend` is genuinely ergonomic.** `iosApp/Detour/SpinModel.swift:129` is
  `try await SpinPickerKt.pickThreeCandidates(...)` — Kotlin `suspend` maps to Swift `async`
  cleanly. So a **suspend-function-shaped core is much cheaper for iOS than a Flow-shaped
  one**, which is a real design constraint on the port signatures above and an argument for
  04's "Shape A: pure decision object" over "Shape B: flow-driven feed" wherever there is a
  choice.

---

## Interaction with the existing five proposals

### Versus 04 — superset, not rival, and 04 is the first ~60%

I want to be precise, because the temptation to overclaim here is obvious.

**What 04 already is.** 04 §3.1 defines two shapes: *Shape A*, a pure decision object with an
injected clock and no coroutines; *Shape B*, a flow-driven feed that **owns no
`CoroutineScope`** and is driven by `suspend fun run(fixes: Flow<Fix?>)` from a caller's scope.
It injects `fetchWays: suspend (LatLon) -> List<…>` and `clock: () -> Long` as constructor
parameters with production defaults. **That is dependency inversion with function-typed ports.**
04 §3.1's load-bearing rule — *"No service owns a `CoroutineScope`… enforceable by grep in
review"* — is the hexagonal purity constraint, stated in the local dialect. 04 lands 800 lines
in `shared/…/drive/`, deletes ~154 lines of `car/` duplication, and reaches iOS.

**So: if 04 ships, roughly 60% of this proposal has shipped.** Anyone reading this document as
"04 but with interfaces" is not being unfair.

**What 04 explicitly does not do**, from its own §13 and §4.2:

1. **`GroupSpin`/`SpinCandidate` stay in `app/…/net/`** (04 §4.2, and 04 says so plainly:
   *"this is the one service that is not iOS-reusable"*). That leaves X3 — the 1,098-line
   two-language protocol — entirely untouched, and it is the largest duplication in the repo.
2. **The nav presentation vocabulary (X1)** is not in scope. The four-way sign table and its
   live iOS divergence survive 04 intact.
3. **The voice policy (X2)** is not in scope. `FUTURE.md:27` then creates copy 3.
4. **Trip detection (X4)** is not in scope.
5. **`wear/`** is not considered at all.
6. **The ports are per-service constructor defaults**, not a named, closed set. That is
   cheaper — and it means there is nothing to stop the eleventh service inventing a twelfth
   convention. 04's own §12 concedes this: *"a newcomer now has to learn one more concept …
   which is not written down anywhere except a doc like this one. That convention needs a KDoc
   header on the package, or it will decay."*

**The honest summary:** for the next ten commits, 04 and 06 are the same work. The difference
is what commit twenty is. 04 answers *"how do I split this file?"*. 06 answers *"why does this
repo write the same policy once per surface, and what is the closed set of things the core is
allowed to ask the platform for?"* — and the second question is the one the evidence in
§"Where the repo already is" actually raises.

### Versus the other four

| | Verdict under this proposal |
|---|---|
| **01 Mechanical split** | **Survives entirely, and should still go first.** Near-disjoint from the domain move (`10-eval-fact-audit.md` 2.6: 01+04 lands `MapScreen.kt` at ~865 lines). It is 1,355 lines of presentational tail with two externally-referenced symbols, same-package so zero import churn, an in-repo `internal`-to-test precedent (`HistoryScreen.kt:72,120`). Nothing here conflicts. Do it. |
| **02 Compose state holders** | **Partly unnecessary, partly complementary.** Six of its eight holders wrap logic this proposal moves to the core, where `car/` and `iosApp/` can reach it — a `rememberXxxState()` is reachable by neither. What survives is the genuinely Compose-lifetime residue (dialog visibility, sheet expansion, chip selection), which after the core exists is small and cheap. `DECISION.md` phase 4's "pick exactly one of 02 or 05" still binds. |
| **03 ViewModel + UiState** | **Conflicts, and stays rejected.** `androidx.lifecycle.ViewModel` is Android-only, so state moved there is unreachable from `car/` (not a `ViewModelStoreOwner`, same process), from `iosApp/`, and from the tracking service. It is the exact inverse of this proposal: it moves state *into* a framework type instead of out of one. Its one real finding — composition-lifetime loss via `MainActivity.kt:172`'s bare `AnimatedContent` — is real, is unowned, and is not solved by anything here either (see §"does NOT solve"). |
| **05 MVI / reducer** | **The targeted variant is compatible and arguably native.** A pure reducer over an explicit state machine *is* a core-side decision object — `05`'s `CameraAuthority` and this proposal's `FollowCamera` are the same idea with different names. Full MVI (+11% LOC, ~430 new declarations) is not required and the same "never both 02 and 05" constraint applies: `MapCameraState` and `CameraAuthority` would be two owners of `MapScreen.kt:521-523`. |

---

## Migration plan

Ordered so that **every step leaves `app/` and `iosApp/` compiling**, each step is one
reviewable commit or a short named series, and each stop-point is a legitimate place to stop
forever. Effort is in *focused developer-days* for someone who knows this codebase.

Calibration note, because it changes what "realistic" means here: `git rev-list --count HEAD`
is **237 commits**, the first dated **2026-07-06**, with **214 of them by one author**. This is
a five-week-old, very high-velocity solo project that already has four surfaces. Days below are
therefore plausible as calendar days rather than the quarters the same programme would take
elsewhere — but **review capacity is one person**, and that, not typing speed, is the binding
constraint on everything after Stage 4.

### Stage 0 — make verification exist (0.5–1 day) · touches no product code

- Add `./gradlew :app:testDebugUnitTest :shared:allTests` to `.github/workflows/build.yml`.
  One line. Without it, `app/src/test` is never executed by anything.
- Record baselines with `tools/mocklocation/MockService.kt` on four canonical routes
  (trajectcontrole, changing urban limits, deliberate off-route, stop-start city loop). Only
  capturable *before* the first behaviour-touching commit.
- **Important, and under-stated everywhere else in this folder:** `.github/workflows/ios.yml:65,68`
  already runs `:shared:testDebugUnitTest` **and** `:shared:iosSimulatorArm64Test`, on two
  runtimes, path-gated to `shared/**`, `iosApp/**`, `*.gradle.kts`, `gradle.properties`
  (`ios.yml:11-19`). So **anything that lands in the core is CI-gated today, with no YAML
  change at all.** That is a property no other destination in this repo has.

### Stage 1 — the mechanical split (2–3 days) · proposal 01, unchanged

Move the presentational tail and pure helpers into ~11 same-package files, `private`→`internal`,
no reformatting. Not hexagonal, but it makes every later state diff reviewable against ~1,700
lines instead of 3,193, and it is zero-behaviour-risk. **Stop-point A.**

### Stage 2 — the `Clock` port and the `Fix` type (1 day)

`core/Clock.kt` + move `data class Fix` (`TripTrackingService.kt:92-100`) into
`shared/.../data/Fix.kt`. The `Fix` move is a two-file change — the type is named only inside
`TripTrackingService.kt`; every other consumer reaches it through `TripTrackingService.lastFix`
by inference (04 step 0, verified). **One-way door: `Fix` becomes iOS-visible ObjC API. Cheap
now, expensive once Swift depends on it.** Do it early or not at all.

### Stage 3 — the pure policies (3–4 days) · `NavPolicy`, `FollowCamera`, `NavAppPolicy`, `CameraEase`/`SpeedEase`

All pure, all clock-injected or stateless, all with tests written **before** the move. Point
`car/NavScreen.kt:242-252` and `car/CarMapRenderer.kt:398-415,468-475` at them and delete the
copies **one commit behind** each extraction, never in the same one. Deletes 04's comment-as-a-
contract at `car/NavScreen.kt:242`. ~18 tests. **Stop-point B.**

### Stage 4 — the three hazard machines (5–8 days) · the highest-value, highest-risk unit

`SectionAverageTracker` → `CameraWarner` → `SpeedLimitService` + `SpeedCameraFeed`, in that
order (cheapest and most isolated first). Extract from the **car** copies. Characterisation
tests against current behaviour first, constants copied byte-for-byte, A/B replay per machine
with the Stage 0 baselines, then delete the car copies one commit later.

`CameraWarner` gains the `Alerter` port here; the phone keeps `ToneGenerator`, the car keeps
toast+voice, and the fact that they disagree (`MapScreen.kt:1161` falls back to the ambient
limit, `car/NavScreen.kt:405` does not) becomes a **separate, release-noted commit** —
`README.md:383-385` currently claims a parity that does not exist.

Watch the trap `12-eval-risk-sequencing.md` found: moving the Overpass fetch off the collector
retunes the 3-miss hysteresis at `MapScreen.kt:1050`, because that constant was only ever tuned
against a stream *with* the dropped fixes. It is the safest-looking change in the whole
programme. **Stop-point C — this is the default stop, and everything above is 04's plan.**

### Stage 5 — nav presentation vocabulary, X1 (2 days) · **first genuinely new step**

`core/Maneuver.kt`: `fun maneuver(sign: Int, exitNumber: Int): Maneuver` with an exhaustive
enum. Four adapters map the enum to their icon vocabulary:
`ui/Navigation.kt:57-71`, `car/NavScreen.kt:575-593` (keeping the roundabout-exit-number care
`docs/ANDROID_AUTO.md:92-100` documents), `iosApp/Detour/NavScreen.swift:223-238`, and
`wear/MainActivity.kt:53-67` **via the relay** (`NavRelay.kt:23-29` sends the enum name instead
of the raw sign; no `:shared` dependency added to `wear/`). **Fixes a live iOS rendering bug and
the car/phone `-6` mismatch as a byproduct**, and is the cheapest possible demonstration that
the pattern pays.

### Stage 6 — guidance policy, X2 (2–3 days)

`core/GuidanceAnnouncer.kt` owns thresholds, phase latch, first-prompt override and phrasing;
`Announcer` is the port; `car/NavVoice.kt` and `iosApp/Detour/NavVoice.swift` become its two
adapters unchanged. Delete `car/NavScreen.kt:64-66,287-313,524-529` and
`NavScreen.swift:142-144,167-195,203-210`. Then `FUTURE.md:27` (phone voice guidance) becomes
an adapter and ~15 lines of wiring instead of a third copy of the policy. ~8 tests
(`announcesOnceAtEightHundredMetres`, `doesNotRepeatWithinAPhase`, …).

### Stage 7 — the convoy protocol, X3 (8–12 days) · the biggest prize and the biggest risk

Sub-steps, each shippable:
7a. Port `ConvoyLiveClient.kt`'s `org.json` frame parsing to kotlinx-serialization (1 day, no
behaviour change, Android only).
7b. Move the wire types (`FriendPosition`, `SpinCandidate`, `GroupSpin`) into
`shared/.../core/convoy/`. Android compiles against the new package; **iOS still compiles
because it keeps its Swift structs** — this step does not force the Swift side to change.
7c. Move the frame vocabulary + peer pruning + `GroupSpinRules` (which unblocks
`MapScreen.kt:321-368, 842-871`, the 30-line vote rule with a 16-line prose correctness
argument and zero tests) into the core, behind `LiveTransport`. Android adopts. ~15 tests.
7d. iOS adopts: `ConvoyLiveClient.swift` reduces to a `URLSessionWebSocketTask` adapter plus
the hand-scheduled ping. **This is the step that needs two physical devices and a running
relay to verify** (`12-eval-risk-sequencing.md` verification class **T**).
**Do not start 7 until Stages 3–6 have shipped and been ridden.**

### Stage 8 — trip-detection core, X4 (5–8 days)

Pure `TripDetector` with the 23 thresholds and the sustained-run/probe/stop decisions;
Android and iOS recorders become adapters feeding it fixes. Verification is class **F**
(field only — a false auto-start is a phantom trip in someone's history, days to notice), so
this is the step with the worst test-to-risk ratio despite being conceptually easy.

### Stage 9 — orchestration, optional (10+ days)

04's steps 9–10: `NavSession` and `SpinSession`. Nine pieces of composable state change owner;
04 rates both **high** risk with the least test leverage, and `12-eval-risk-sequencing.md`
agrees. Defensible to stop at Stage 8 forever.

**Total, Stages 0–8: ~30-40 focused developer-days.** Stages 0–4 (~12-16 days) are 04's plan
and deliver most of the value. Stages 5–8 (~18-25 days) are what this proposal adds.

---

## Test story

### What CI actually does today

- `.github/workflows/build.yml` runs **no Kotlin test whatsoever** — it assembles and bundles
  (`:app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease`) and
  publishes. The two files in `app/src/test/` are never executed by CI.
- `.github/workflows/ios.yml` runs `:shared:compileCommonMainKotlinMetadata` (`:59`),
  `:shared:testDebugUnitTest` (`:65`) and `:shared:iosSimulatorArm64Test` (`:68`) — **on two
  runtimes** — but is path-gated (`:11-19`) to `shared/**`, `iosApp/**`, `*.gradle.kts`,
  `gradle.properties`.
- `CONTRIBUTING.md:144-147` documents exactly this and makes the iOS workflow a required gate
  for any `shared/` change.

**Therefore: `commonTest` is not merely "the source set CI could realistically gate" — it is
the source set CI already gates, today, by construction, on JVM and Kotlin/Native.** Code that
moves into the core acquires a working gate with zero YAML changes. Code that stays in `app/`
acquires nothing until someone adds the one line in Stage 0.

Today `commonTest` is 3 files / 839 lines / **60 `@Test`**, all over parsing and route
math — the best-tested surface in the repo, and none of it touches a state machine.

### What becomes testable, with names

`shared/src/commonTest/kotlin/com/jellemax/detour/core/`:

**`SectionAverageTrackerTest`** (the single best target: 63 lines, 4 exit conditions, 5
closure-local vars no test can reach today, verified only by driving through a Belgian
trajectcontrole)
```
entersOnlyWhenHeadingTowardsTheOppositeEnd
doesNotEnterASectionWhenPassingItsFarEndOnTheWayOut
picksTheNearestSectionWhenAShortOneSitsInsideALongOne
endsOnlyAtTheEndItWasDrivenTowards
doesNotExitThroughTheGateItEnteredBeforeOneHundredAndFiftyMetres
clearsAfterOvershootingTheSpan
clearsAfterThirtyMinutesWithoutReachingTheEnd
```
**`CameraWarnerTest`**
```
cuesOncePerCameraAndRearmsOnlyOnceItIsBehindYou
staysSilentWhenTheLimitIsUnknown
ignoresCamerasOutsideTheWedge
picksTheNearestOfSeveralCamerasAhead
```
**`SpeedLimitServiceTest`** (pure half, `RoadData` stubbed)
```
clearsTheSignOnlyAfterThreeConsecutiveMisses
throttlesRefetchToOncePerTenSeconds
keepsThePreviousWaysWhenAFetchReturnsEmpty
```
**`NavPolicyTest`**
```
neverArrivesOnALoopWithNoDestination
reroutesOnceThenWaitsOutTheCooldown
doesNotRerouteWhileARerouteIsAlreadyInFlight
```
**`ManeuverTest`** — X1, and the test that would have caught the iOS bug
```
mapsEveryGraphHopperSignInTheRouterOutputRange
uTurnSignsAreNotClassifiedAsSharpTurns
roundaboutWithoutAnExitNumberIsEnterOnly
unknownSignFallsBackToStraight
```
**`GuidanceAnnouncerTest`** — X2
```
announcesOnceAtEightHundredThreeHundredAndAtTheTurn
neverRepeatsAPhaseForTheSameInstruction
firstPromptOfADriveIgnoresTheThresholds
resetsWhenTheInstructionChanges
```
**`GroupSpinRulesTest`** — X3, replacing a 16-line prose correctness argument
(`MapScreen.kt:842-857`) that has no test
```
tiesIncludingNoVotesYetGoToTheLowestIndex
aOneCandidateOfferCommitsOnEveryDeviceIncludingReceivers
onlyTheSharerClosesTheRound
waitsUntilEveryLivePeerAndMyselfHaveVoted
```
**`ConvoyFramesTest`** — X3
```
parsesEveryFrameTypeTheRelayCanSend
ignoresAFrameForAGroupWeAreNotIn
prunesAPeerAfterTheStaleWindow
roundTripsASpinOfferThroughTheWire
```
**`TripDetectorTest`** — X4
```
doesNotStartOnOneFreakFix
startsOnASustainedRunOfTightFixes
aProbeWindowLowersTheSpeedBar
anAveragePaceUnderWalkingSpeedIsNotADrive
```

Realistic: **~55-70 new tests over ~700 lines of logic that has zero tests today**, all in the
one source set already gated on two runtimes.

### What stays untested, and I will not pretend otherwise

Overpass/GraphHopper/Photon transports (no transport-level seam exists, and adding one is its
own project) · the Compose `withFrameNanos` loop and `setCamera` · `MapOverlays` pushes ·
`ToneGenerator`/`TextToSpeech`/`AVSpeechSynthesizer` · permissions · `AndroidView` · the
foreground service's mode tiering · battery, thermal and drift (class **F**, days to weeks) ·
the whole 1,545-line UI mass.

---

## Pros

1. **It attacks the mechanism, not one instance.** Seven duplicated policies, six of them
   across a language or toolkit boundary. `MapScreen.kt` is one of them. Every other proposal
   in this folder addresses at most X7.
2. **One of the seven is already wrong, verifiably, today.**
   `iosApp/Detour/NavScreen.swift:223-238` renders a sharp left as a U-turn and a U-turn as
   "carry straight on", because the table it disagrees with lives in `ui/Navigation.kt:57-71`
   and nothing connects them. Stage 5 is two days and fixes it structurally, not by patching
   the Swift `switch`.
3. **The pattern is already adopted, proven in-repo, and documented.** `Platform.kt`'s three
   `expect`s, Ktor's engine seam, and `CircleEvents.kt`'s decision/delivery split are three
   working examples. `CONTRIBUTING.md:31-32` already makes "new logic goes in `shared/`" a
   written rule with a named failure mode. This is "finish the thing that worked", not "adopt
   an architecture".
4. **The core is the only CI-gated destination in the repo, today, with no YAML change**
   (`ios.yml:65,68`), and it is gated on **two** runtimes. Four of the five earlier proposals
   sell testability into a gate that does not exist.
5. **It unblocks features that are blocked purely by location.** The car has no
   trajectcontrole; iOS has no ambient limit, no camera warnings, no trajectcontrole; the
   phone map has no voice guidance (`FUTURE.md:27`). None of these is a product decision —
   they are all "the code is welded to a Compose function" or "welded to a car Screen".
6. **It makes the next surface cheap.** `docs/IOS_PORT.md:87` lists a watchOS app as "not done,
   small, but nothing reuses from `wear/`". At 237 commits in five weeks with four surfaces
   already, "the next surface" is a live question, and today it costs a fifth copy of
   everything.
7. **It gives the port set a name and a boundary.** Nine ports, closed, reviewable by grep. 04's
   per-service constructor defaults are the same idea without a rule, and 04's own §12 predicts
   the convention will decay without one.
8. **Stages compose with proposal 01 rather than competing.** 01 + Stages 0–4 lands
   `MapScreen.kt` at roughly 865 lines (`10-eval-fact-audit.md` 2.6) with no ViewModel, no
   reducer, no holder and no new dependency.

---

## Cons and risks

### 1. The port indirection tax, for a team of one

Every port is minimum three artefacts: the interface, the production adapter, and a fake for
tests — times two platforms for anything the iOS app must also implement. Nine ports is
plausibly **~25-30 new files**. The repo today has **four `expect` declarations and a written
policy against a fifth**. A reviewer opening a PR to add `Alerter` is entitled to ask whether a
one-method interface with one non-test implementation is worth a file, and on `Alerter`
specifically the honest answer is *"only because a second implementation already exists in
`car/`"*. **Where there is one adapter, do not build a port.** I would enforce that as a
review rule, and it disqualifies `RoadData`, `Router` and `PlaceSearch` from §"The ports"
unless and until someone actually writes the stubbed tests they exist for.

### 2. The repo's own written rule says no

`CONTRIBUTING.md:26-29` — *"wanting to add a fourth is the signal to push the dependency in
from the platform instead"* — is not ambiguous, and this proposal wants to add up to five. My
defence (inbound vs outbound, §"The rule, restated") is coherent, but it is **my** distinction,
not the project's, and the project's version has the advantage of being one sentence that a
tired maintainer can apply at midnight. If this proposal is adopted, that rule has to be
*rewritten*, in `CONTRIBUTING.md` and `docs/IOS_PORT.md`, in the same PR series — and a rule
with an exception clause is a weaker rule than the one it replaces.

### 3. KMP toolchain friction is real and is paid on every commit

Touching `shared/` recompiles the KMP metadata plus the Android target, and on macOS three
Kotlin/Native targets. `gradle.properties:7-12` documents that the `ios*` targets are declared
unconditionally but **cannot be built off macOS** (`kotlin.native.ignoreDisabledTargets=true`),
so a Linux developer's confidence in a core change stops at
`:shared:compileCommonMainKotlinMetadata`. CI's iOS job pulls a ~1 GB `~/.konan` toolchain
(cached), runs on `macos-15` with a **45-minute timeout**, generates the Xcode project with
XcodeGen and builds twice (simulator + unsigned device archive). Every core commit is gated on
that job being green (`CONTRIBUTING.md:144-147`). Moving more code into the core moves more
commits onto the slow, Mac-only, occasionally-flaky path.

### 4. Swift consumption of Kotlin flows is genuinely not ergonomic

Measured, not asserted: `FlowWatcher.kt` is **196 lines of pure boilerplate** whose own KDoc
explains that Swift cannot start a coroutine and Kotlin/Native erases generics to ObjC. Nine
Watcher subclasses exist today; every new core `StateFlow<T>` needs another. Nullable
primitives box (`NavScreen.swift:97,100`). `enum.entries` has no ObjC representation, so the
`Enums` object exists to hand Swift its lists by hand. **Design consequence:** prefer `suspend`
functions and plain value returns over `Flow` in every core API where there is a choice —
`SpinModel.swift:129`'s `try await SpinPickerKt.pickThreeCandidates(...)` is the shape that
costs nothing. A flow-heavy core is a Swift tax multiplier.

### 5. Half-migration is worse than no migration

If the guidance policy lands in the core and only the car adopts it, there are now **three**
copies, not one. The mitigation is mechanical and must be non-negotiable: **no core version
lands without every copy deleted in the same PR series, one commit apart, with the deletion
commit naming the extraction commit.** `12-eval-risk-sequencing.md` calls this out for 04's
car deletions and it applies with more force here, because X2/X3 deletions are in a different
language and will not fail to compile if forgotten.

### 6. One-way doors

Everything in `shared/` becomes iOS-visible Objective-C API. Renaming `Fix`, `Maneuver` or a
`NavSession` field after Swift depends on it is a Swift compile break in a repo whose iOS build
only runs on a path-gated macOS job. `12-eval-risk-sequencing.md` flags this for 04's `Fix`
move; this proposal has ~8 more such types. **Name them once, carefully, at extraction time.**

### 7. `wear/` is a bad fit and I am not going to force it

Adding `implementation(project(":shared"))` to a 185-line, unminified watch app
(`wear/build.gradle.kts:41`) to deduplicate a 16-line `when` pulls in ktor, okio,
kotlinx-serialization and kotlinx-datetime. The right answer for `wear/` is a richer relay
payload (`NavRelay.kt:23-29`), not a shared module. A "four surfaces, one core" framing that
quietly excludes one of the four surfaces should say so out loud, and this is me saying so.

### 8. `backend/` and `server/` are outside the hexagon entirely

151 C# files and a Python sync server cannot consume a Kotlin core. The framing "whole-repo
scale" is therefore only true of the client half. Reducing three protocol implementations to
two is a real win; calling it "one source of truth" would not be.

### 9. Is this architecture astronautics for an app this size?

**Partly, yes.** Honest scale check: 4,921 lines of core, 16,705 of Android, 5,065 of Swift.
That is a large hobby app, not a system. A nine-port hexagon has a fixed cost — the naming, the
fakes, the review rule, the `CONTRIBUTING.md` rewrite — that does not amortise well at this
size, especially when `car/` (the surface with the most duplication, X7) is **in the same
Gradle module as `ui/`** and needs no KMP hop at all (`10-eval-fact-audit.md` 7.9).

The counter-evidence is the velocity: 237 commits in 36 days, four surfaces, and X1–X6 all
appeared inside those 36 days. The duplication is not legacy; it is being **generated, now, at
speed**. That is the strongest argument that the mechanism is worth fixing and the strongest
argument against a heavyweight fix — because ceremony is what would slow the velocity that is
this project's actual asset.

### 10. Regression risk in path-dependent GPS state machines is unchanged from 04

Stages 3–4 carry exactly the risk 04 §11.1 and `12-eval-risk-sequencing.md` document: the four
machines are path-dependent, correctness is a property of the fix *sequence*, no Robolectric,
no instrumented tests, and characterisation tests lock in current behaviour including current
bugs. Stage 7 adds a risk 04 does not have: a convoy protocol bug is only visible with two
devices and a live relay.

---

## What this proposal explicitly does NOT solve

1. **The UI mass.** ~1,545 lines of composables in `MapScreen.kt` are untouched by the core
   move; the file lands at ~2,355 on its own, ~865 if proposal 01 also lands. Nothing here
   makes a `Scaffold` tree smaller.
2. **The composition-lifetime defect.** `MainActivity.kt:172-225` swaps screens with a bare
   `AnimatedContent`, so Map→Hub→Map disposes the subtree, resets every `rememberSaveable`,
   rebuilds the `MapView` and re-runs `SyncClient.sync()`. Moving logic to the core does not
   fix it and arguably hides it. It remains an unowned defect and needs its own decision.
3. **`error`'s 12 writers and 1 reader** (`MapScreen.kt:460` → `:1771`, behind a card that is
   collapsed by default). A live product defect; fix it separately, before anything else.
4. **MapLibre imperative wiring** (~178 lines) and the Compose frame loop. Adapter code,
   staying adapter code.
5. **Permission choreography** (`MapScreen.kt:736-802`). Android-only, stays.
6. **Network-level testability.** Even with `Router`/`RoadData` ports, nothing stubs the
   transport, so Overpass/GraphHopper/Photon paths stay unverified.
7. **Background and battery behaviour.** `MapScreen.kt:598-601` documents that the composition
   survives backgrounding, so these loops already run in the background. Extraction preserves
   that; the "no service owns a scope" rule only prevents making it worse.
8. **`backend/` and `server/`.** Different languages. The wire protocol is the contract.
9. **`wear/`.** Deliberately excluded, see Cons #7.
10. **Any user-visible behaviour** — with two deliberate, separately-committed, separately-
    release-noted exceptions: the Overpass fetch moving off the fix collector, and the
    phone/car camera-warning divergence that `README.md:383-385` currently mis-describes.

---

## Honest verdict

**Does this beat proposal 04?** On the question this folder was convened to answer — *how do we
split `MapScreen.kt`?* — **no.** It produces the same ≈838 lines out and the same ≈2,355-line
file, because Stages 0–4 *are* 04's plan with different nouns. If a reader's takeaway is "06 is
04 with interfaces", that reader is 60% right, and I would rather concede it than have an
auditor find it.

**Where it earns its place** is that 04 was scoped to one file and therefore could not see
that the same failure has happened six more times, five of them across a language boundary, and
that one of those six is already producing a wrong turn arrow on iOS. 04's own §4.2 declines
the largest instance (the 1,098-line two-language convoy protocol) on the grounds that moving
the wire types is "a separate decision with its own blast radius". This proposal is that
separate decision, taken.

**Is it right for a repo of this size and team?** As a **whole-repo programme, no.** One
maintainer, 43k lines across six modules, four `expect` declarations and a written policy
against a fifth. Declaring "we are hexagonal now" and building nine ports would cost more in
ceremony and review capacity than it returns, and it would slow down the thing that is
obviously working — 237 commits in 36 days.

**As applied to the subset that already hurts, yes, and the subset is identifiable by
evidence rather than taste.** The rule I would actually adopt: *a policy earns a place in the
core when it is written more than once, and a port earns an interface when it has more than
one implementation.* By that rule, today: `Clock` (yes — four call sites need determinism),
`Announcer` (yes — two implementations exist), `Alerter` (yes — two, and they disagree),
`LiveTransport` (yes — two), and the `Maneuver` classification (yes — four copies, one wrong).
`RoadData`, `Router`, `PlaceSearch`, `MapRenderer`, `LocationSource`: **no** — one
implementation, or the push model already covers it. That is **four ports, not nine**, and it
is a proposal a solo maintainer can actually hold in their head.

**Concretely, what I would do.** Land proposal 01 (Stage 1), then 04's plan in full (Stages
2–4, stop-point C). Then judge Stages 5 and 6 on their own merits — they are two and three days
respectively, each fixes a demonstrated defect or a demonstrated near-miss, and together they
are the cheapest possible proof that a cross-language core pays. Only if those two land clean
should Stage 7 (convoy) be opened, because it is the one step that is large, needs two physical
devices to verify, and cannot be reverted cheaply once the Swift side has adopted it. Stage 8
(trip detection) has the worst test-to-risk ratio in the programme and should wait for a
reason, not a schedule.

And whatever is adopted, `CONTRIBUTING.md:23-32` must be updated to say what the boundary now
is. A repo whose documented architecture and actual architecture disagree is worse off than
one with a simpler architecture that is written down — and right now, that documentation is
this repo's best feature.
