# Regression-risk and sequencing evaluation

Lens: **what can go silently wrong, and how much of it can be proved before it ships.**
Not "which architecture is nicest". Every claim cites `MapScreen.kt:NNN` or another
path. Snapshot: branch `main`, HEAD `07fe490`.

Read alongside `00-inventory.md` (facts) and `01`–`05` (the five proposals). Where I
disagree with a proposal's own risk assessment I say so and cite the line.

**Two findings up front that change the shape of the answer, and that none of the five
proposals mention:**

1. **CI does not run any Kotlin test.** `.github/workflows/build.yml` runs
   `./gradlew :app:assembleRelease :app:bundleRelease :wear:assembleRelease
   :wear:bundleRelease` and nothing else — no `test` task, no `lint` task. The six
   existing test files (`app/src/test/…/PlaceNotificationsTest.kt`,
   `app/src/test/…/ui/TripTraceMatchingTest.kt`, `shared/src/commonTest/…/{Groups,Parsing,Routes}Test.kt`,
   `shared/src/androidUnitTest/…/RouteStoreLoadOrderTest.kt`) are never executed by
   automation. Every proposal that sells testability (03 §step 3, 04 §8, 05 §test plan)
   is selling a gate that does not exist yet. It costs one line to create.
2. **The repo already contains a GPS replay harness.** `tools/mocklocation/` is a
   standalone one-service app that replays a `lon lat` route file as mock fused fixes at
   a configurable interval, computing speed and bearing from consecutive points
   (`MockService.kt:80-88`, `:139-152`) and stamping `accuracy = 4f` and a live
   `elapsedRealtimeNanos` (`MockService.kt:110-116`) so Play Services accepts them.
   Recipe at `docs/PLAY_LOCATION_DECLARATION.md:159-178`; referenced from
   `docs/DEBUG_INTENTS.md:129-132`. `accuracy = 4f` passes the ≤100 m gate at
   `MapScreen.kt:556`; a real `speed` clears the 3 m/s resume threshold (`:706`), the
   2 m/s bearing-hold floors (`:1028`, `:1189`, `:1239`) and the 1.4 m/s HUD gate
   (`:1641`); a real `bearing` drives the 75° section wedge (`:308`) and the 45° camera
   wedge (`:1154`).

   **The brief's premise — "verifying this means physically driving" — is about 70%
   true, not 100% true.** Most of the GPS-driven behaviour in this file is replayable at
   a desk, deterministically, repeatably, and therefore *A/B-able across a refactor
   commit*. Nobody is using it for that today. Doing so is the single highest-leverage
   action in this whole programme, and it is a prerequisite I would attach to four of
   the five proposals.

   What remains genuinely irreducible: convoy (two devices + a server), Wear/BLE
   (paired hardware), battery/thermal (days), and anything whose correctness depends on
   real Overpass data at a real location that the replay route must actually pass
   through (a real trajectcontrole, a real speed camera) — though that too becomes
   repeatable once you have a route file that passes one.

---

## Why silent regression dominates here (evidence from the code)

### 1. The automated gate is a compiler and nothing else

- `app/build.gradle.kts:163` — `testImplementation("junit:junit:4.13.2")`. No
  Robolectric, no `compose-ui-test`, no `androidTest` source set (`ls app/src/androidTest`
  → does not exist).
- `.github/workflows/build.yml` — assemble/bundle only. Even the JUnit tests that exist
  are not a gate.
- So the set of things that fail loudly is exactly: type errors, unresolved references,
  and R8. Everything else ships.

### 2. The file's dominant idiom is "state that is deliberately not observable"

Five effects hold their entire working state in coroutine-local `var`s that no test, no
debugger watch-expression outside the frame, and no reviewer's grep can see:

| Lines | Locals | What a silent reset costs |
|---|---|---|
| `1063-1064` | `center`, `lastFetchMs` | Overpass re-hit on the next fix; extra network, no visible symptom |
| `1146` | `warnedAt` | The camera chime re-fires for a camera you already passed, or never re-arms |
| `1175-1179` | `active`, `exitGate`, `entryMs`, `accMeters`, `last` | An in-progress trajectcontrole measurement is abandoned mid-section; the Ø chip vanishes or shows a wrong average against a real fine |
| `1252`, `1289-1292` | `lastNs`, `appliedLat/Lon/Zoom/Bearing` (`appliedLat` starts `Double.NaN` as the never-pushed sentinel, `:1289`, tested `:1320`) | The `moved` epsilon gate stops suppressing redraws; per-frame `setCamera` + fog invalidate returns |
| `1281-1284` | `lat`, `lon`, `bearing`, `zoom` | The ease restarts from `camTarget`, visible as a camera jump |

All five live inside `LaunchedEffect(Unit)` or a key list that cannot currently change.
**Any refactor that gives one of these effects a parameter it keys on turns a permanent
accumulator into one that resets** — and the compiler is delighted.

### 3. Three effects deliberately read state they are not keyed on

- `MapScreen.kt:1236` `LaunchedEffect(liveFix, defaultZoom)` reads
  `navProgress?.distanceToTurnMeters` at `:1243` without keying on `navProgress`. A
  `navProgress` change alone must **not** recompute the zoom target; it takes effect on
  the next fix. Adding the key is a one-word diff and a behaviour change.
- `MapScreen.kt:700` keys on `candidates.isEmpty()` and `spinOffer == null` — booleans
  *derived* from collections. Keying on the collections restarts a `lastFix` collector
  on every convoy vote.
- `MapScreen.kt:1345` `LaunchedEffect(navigating, liveFix, route)` writes `route` at
  `:1379` from a `scope.launch` — it re-keys itself asynchronously, deliberately
  (comment `:1367-1369`: a `LaunchedEffect`-scoped request would be cancelled by the
  next GPS fix).

Each of these looks like a mistake to a reader who has not read the comment. Each is
load-bearing.

### 4. Long-lived callbacks read short-lived state through `rememberUpdatedState`

`candidatesRef` (`:937`), `spinOfferRef` (`:938`), `navigatingRef` (`:939`) exist because
the four map listeners registered in `LaunchedEffect(mapLibreMap)` (`:940-968`) outlive
every recomposition. `speedCamerasRef`/`ambientLimitRef`/`navProgressRef` (`:1138-1140`),
`speedSectionsRef` (`:1173`) and `speedTarget` (`:1250`) do the same for
`LaunchedEffect(Unit)` collectors that never restart.

Convert any of these to a plain parameter and it is captured once, forever. It compiles.
It passes review unless the reviewer knows the idiom. It fails only in motion. Proposal 01
names this as its own worst defect (`01:507-513`) and it is correct to.

There is a second-order hazard here too: **the map listeners are never removed.** There
is no `removeOnMapClickListener` / `removeOnCameraMoveListener` anywhere. That is safe
today only because `mapLibreMap` is written exactly once (`:640`) so
`LaunchedEffect(mapLibreMap)` cannot re-run with a non-null map. Any refactor that makes
that effect re-runnable stacks listeners silently.

### 5. Six independent collectors on one conflating `StateFlow`

`TripTrackingService.lastFix` is collected directly at `:704`, `:1026`, `:1065`, `:1147`,
`:1180`, plus `collectAsStateWithLifecycle` at `:487`. Each sits in a `LaunchedEffect`
with different keys, so they start and stop independently. Two of them suspend inside the
collector on network I/O (`:1037`, `:1075`) — on a conflating flow that means those two
*drop* fixes for their own consumer while fetching, and only for them. That dropping is
currently load-bearing for the 3-miss hysteresis at `:1050`. Merging or reordering the
collectors changes which consumer sees which fixes, and there is no test anywhere that
would notice.

### 6. Ordering coupling is real and is not expressed in types

`00-inventory.md` H1: `fetchLocation` (`:714`) → `onLocationGranted` (`:760`) →
`permissionLauncher` (`:772`) → the permission sweep (`:782`); `commitSpinCandidate`
(`:828`) before the resolution effect (`:858`); `displayCandidates` (`:823`) before
`candidatesRef` (`:937`) and the overlay effect (`:874`); `convoyPeers` deliberately
hoisted to `:497` (comment `:494-496`) so the commit rule at `:858` can see it. Kotlin's
local-function scoping enforces some of this; nothing enforces the rest.

### 7. The failure modes are worse than "a button looks wrong"

- **Hot mic.** `PushToTalk.stopTalking()` runs on `ON_STOP` *and* on dispose
  (`:612-615`, `:620-624`), because `awaitRelease()` (`:2932`) may never fire when
  backgrounding interrupts a press — the comment at `:606-611` is explicit. Losing the
  dispose branch leaves a recording mic.
- **Stuck 1 Hz GPS.** Same `DisposableEffect`: `TripTrackingService.setUiVisible(context,
  false)` on `ON_STOP` and dispose. Losing it holds navigation-grade location open with
  the phone in a pocket.
- **A convoy split across two destinations.** The comment at `:842-857` is sixteen lines
  arguing why per-device tallying is wrong (`convoyPeers` prunes at 20 s, so two phones
  can consider the round complete at different times). This is a distributed consensus
  rule with a written correctness argument and zero tests.
- **A wrong number next to a speed camera.** `SectionAverageChip` (`:3018-3019`) turns red
  when the running average exceeds the section limit. Both numbers come from the
  unobservable machine at `:1174-1230`.

### 8. And the file is a moving target for line-number citations

`docs/superpowers/specs/2026-08-11-map-layers-panel-toggle-design.md`, `audit.md`,
`guide.md`, `FUTURE.md` and `docs/PLAY_LOCATION_DECLARATION.md` all cite `MapScreen.kt:NNN`
(inventory §6.2). Every step of every proposal invalidates them. Not a regression, but a
real, uncounted cost, and a reason to land the mechanical moves in one burst rather than
spread out.

**Conclusion for this evaluation:** the ranking criterion is not elegance and not line
count. It is *what fraction of a step's failure surface can be closed before a user is
on a motorway*. Everything below is scored on that.

---

## Detection ladder (used in every table below)

| Code | Detected by | Cost | Latency |
|---|---|---|---|
| **C** | Kotlin compiler / R8 | free | seconds |
| **U** | plain JUnit4 (`app/src/test`, `shared/src/commonTest`) — **only if written, and not run by CI today** | minutes | seconds |
| **D** | desk: debug build in hand, stationary | ~15 min | minutes |
| **M** | mock replay (`tools/mocklocation`, recipe `docs/PLAY_LOCATION_DECLARATION.md:159-178`) — deterministic and A/B-able | ~10 min/route once set up | minutes |
| **R** | real drive on a real road (trajectcontrole, camera, motorway, deliberate off-route) | hours | days |
| **T** | two devices + a running server (convoy) | ~30 min | hours |
| **F** | field only — battery, thermal, latency, drift | free but passive | **days to weeks** |

**F is the category that should terrify.** Anything whose only detector is F ships broken
and stays broken.

---

## Risk register

### Proposal 01 — Mechanical file split

Phases 1–2 (steps 1–11): move top-level declarations and the 1,355-line stateless tail
(`:1839-3193`) into same-package files, `private` → `internal`.

| # | Failure mode | Likelihood | Detected by | Blast radius |
|---|---|---|---|---|
| 1.1 | Wrong icon after an auto-import in a new file. `MapScreen.kt` imports **both** `androidx.compose.material.icons.filled.Place` (`:64`) and `androidx.compose.material.icons.outlined.Place` (`:76`), and both `Icons.Filled.Add` (`:62`) and the two `AutoMirrored` Directions families (`:60-61`). A split file that imports only one resolves the other to a compile error *or*, if the IDE picks the sibling, compiles with the wrong glyph. | medium | **D** (visual) | cosmetic, one chip/pin |
| 1.2 | A moved composable's default-argument expression silently re-resolves (e.g. `modifier: Modifier = Modifier`) — no real cases found in this file, listed for completeness | very low | C | none |
| 1.3 | `git blame` / `git log -C` broken by reformatting inside a move commit. The file's comments *are* the design record (`:232-235`, `:290-299`, `:842-857`, `:1265-1269`). | medium | never | permanent loss of rationale provenance |
| 1.4 | Merge conflict corruption: 14 sequential commits over one file, cut-and-paste conflicts resolve badly (proposal's own con #5, `01:630-633`) | medium if spread over sprints | C usually, **D** sometimes | up to a whole composable |

**Phase 1–2 verdict: the lowest-risk work in the entire exercise.** The correctness
argument is "these bytes moved and one keyword changed", and it holds.

Phase 3 (steps 12–14): effects become `@Composable internal fun XxxEffect(...)`.

| # | Failure mode | Likelihood | Detected by | Blast radius |
|---|---|---|---|---|
| 1.5 | **Stale capture in `CameraParkingEffect`.** The touch listener registered in `DisposableEffect(mapView)` (`:669`) reads `camSuspended` at `:689` to decide whether `ACTION_UP` refreshes `lastGestureMs`. As a parameter it is captured at registration and frozen. Symptom: the quiet clock stops being refreshed, so the camera un-parks 8 s after your *first* gesture instead of your last — i.e. it yanks itself back mid-pan at speed, which is the exact behaviour the comment at `:269-274` exists to prevent. | **high** if not reviewed line-by-line | **M** (route iv), else R | user-hostile at 100 km/h |
| 1.6 | Same class in the extracted map listeners (`:940-968`) — `candidatesRef`/`spinOfferRef`/`navigatingRef` must survive as refs | high | D (tap a pin after a reroll), T for the vote path | wrong candidate committed; vote cast on a stale offer |
| 1.7 | **Key-list merge in `MapOverlayRenderEffect`.** The proposal's signature (`01:463-481`) takes 15 parameters covering `:874-902`, `:907-909` and `:913-915` — three effects that are *deliberately* separate (comment `:904-906`: "which would re-push every overlay source to change one bitmap"). If the extraction produces one `LaunchedEffect` with the union of keys, changing the vehicle icon in Settings re-serialises every GeoJSON source. | medium (the doc is ambiguous) | **D** for the visible lag; **F** for the cost | frame hitch on every marker/colour change |
| 1.8 | `CameraTargetEffect`'s "null bearing means keep the old one" contract (`01:453-455`, from `:1239`) implemented as "null bearing means clear" | medium | **M** (route iv, stop-start) | map stops rotating below 2 m/s, or snaps to noisy bearings while stationary |
| 1.9 | An extracted effect gains a parameter and someone keys on it — resetting a coroutine-local accumulator (`:1146`, `:1175-1179`) | medium | **R** (needs a real section) / M with a section route | trajectcontrole average abandoned mid-measurement |
| 1.10 | Step 12 moves `speedLimitWays`/`Center`/`FetchMs`/`Misses` (`:528-533`) into `AmbientSpeedLimitEffect`. Today they are Compose state and therefore **survive the `navigating` re-key at `:1024`** (inventory H4 notes the two adjacent effects use opposite strategies). If they become effect-locals, starting navigation and stopping it re-fetches from Overpass. | medium | **F** (extra network) / M if you watch logcat | battery + Overpass load, no visible symptom |

**Phase 3 verdict:** proposal 01 is honest that this is where its value/cost turns
marginal (`01:288-290`) and that 1.5/1.6 are "a silent, compiles-fine, ships-fine bug
class" (`01:507`). I agree, and I would add 1.7 and 1.10, which it does not list.

---

### Proposal 02 — Compose state holders

Steps 0a–0f are identical work to 01 phases 1–2; risks 1.1–1.4 apply unchanged.

| # | Failure mode | Likelihood | Detected by | Blast radius |
|---|---|---|---|---|
| 2.1 | **`rememberSaveable` lost.** `radiusKm` (`:449`), `minRadiusKm` (`:450`), `poiKind` (`:462`), `directionDeg` (`:463`) are `rememberSaveable` today. `remember { MapSpinState(...) }` drops that silently. The proposal supplies a `listSaver` (`02:352-359`) and flags it as the sharpest edge (`02:720-726`). | medium (it is exactly the kind of thing that gets skipped) | **D** (rotate) | slider resets mid-planning |
| 2.2 | **`ServerConfig` divergence — nobody in any of the five proposals noticed this.** `serverConfig` is a composition-time snapshot, `remember { RoutingServer.load() }` at `:461`, read by eight sites (`:1006`, `:1380`, `:1414`, `:1420`, `:1478`, `:1500`, `:1748`, `:1777`); inventory H14. `MapSpinStateSaver.restore` calls `RoutingServer.load()` again (`02:356`). After a rotation, `MapSpinState.serverConfig` is a *fresh* load while `MapScreen`'s and `MapNavigationState`'s are the original snapshot. Change the routing server in Settings, rotate, and the spin and the navigation now disagree about which server exists — `inAppAvailable` (`02:334`) and `startNavigation` (`:1006`) read different configs. | medium-high once the saver exists | **D** but only if you think to try it | spin succeeds, "Go" is greyed out, or vice versa |
| 2.3 | `MapSurfaceState.detach()` reorders the teardown. `:643` sets `fogView.map = null` **before** `mapView.onPause/onStop/onDestroy` (`:644-646`) because `FogView` holds the map for projection. | medium | **D** (leave the map, return) | crash or a dead fog layer |
| 2.4 | The `mapView.indexOfChild(fogView) < 0` guard (`:657`) lost on style reload → `FogView` added twice on every theme flip | low-medium | **D** (flip theme 3×) | double-drawn scrim, leaked view |
| 2.5 | `rememberEasedSpeedKmh(targetKmh)` reads its parameter directly inside `LaunchedEffect(Unit)` instead of through an internal `rememberUpdatedState` (the proposal is aware, `02:631-634`) | medium | **M** (any route) | speed readout frozen at 0 |
| 2.6 | **`holdParked` equivalence.** The proposal collapses the four-key list at `:700` (`camSuspended, spinning, candidates.isEmpty(), spinOffer == null`) into one boolean parameter (`02:546`). I worked the equivalence through and it *does* hold — `displayCandidates.isNotEmpty() \|\| spinOffer != null` ≡ `candidates.isNotEmpty() \|\| spinOffer != null` given `:823`, and collapsing keys is safe because the body early-returns while the flag is true (`:701`). But that is a non-obvious argument a reviewer must actually make, not read. | low if argued, high if assumed | **M** (route iv) | camera resumes while you read candidates, or never resumes |
| 2.7 | Declared: `setDrivenFraction` moves from synchronous (`:1355`) to a one-frame-later effect (`02:519-530`). Mitigated in practice by the 12 m throttle at `MapLibreMap.kt:102`/`:282`. | certain (declared) | none needed | invisible |
| 2.8 | Declared: `cameraActive` computed from a parameter rather than read at `:551` | certain (declared) | M | none if the value is identical |
| 2.9 | Seven snapshot states downgraded to plain fields (`:459`, `:517`, `:523`, `:528`, `:531`, `:532`, `:533`). All readers are inside coroutines on the main dispatcher, so this is safe — **but only because of that**. Downgrading one whose reader is in composition (`camSuspended` `:522`, read at `:551`/`:553`) would silently stop the follow button updating. | low (the proposal picks correctly) | **D** if wrong | follow icon stops reflecting reality |
| 2.10 | Holder passed to a leaf composable without `@Stable` defeats skipping (`02:743-746`) | medium | F | frame cost |

**Verdict:** genuinely low-risk *per step*, and every step is abortable. Risk 2.2 is the
one I would attach to the merge checklist for step 6, because it is invisible, it is
introduced by the very mitigation the proposal recommends, and nobody caught it.

---

### Proposal 03 — ViewModel + UiState

Steps 1–4 are: `internal` promotion, the same mechanical moves, and extracting the hazard
machines as plain classes. Those carry risks 1.1–1.4 plus the P4 machine-transcription
risks below. The ViewModel proper is steps 5–11.

| # | Failure mode | Likelihood | Detected by | Blast radius |
|---|---|---|---|---|
| 3.1 | **The `MapEffect` channel replays on return.** `Channel(Channel.BUFFERED)` + `receiveAsFlow()` collected in a `LaunchedEffect(Unit)` inside the composable (`03:246-272`). While MapScreen is not composed — and it is disposed on every Hub round-trip, see 3.3 — effects buffer and are delivered when it returns. `Haptic`, `FitCamera`, `FlyTo`, `StartTracking`, `ClearNavRelay` all fire late. Today they are inline calls that simply do not happen. **The proposal does not enumerate this.** Symptom: you come back to the map and the camera jumps to a spin result from five minutes ago. | **high** | **D** (spin, navigate to Hub, return) | camera hijack, late haptics, late service start |
| 3.2 | **Ten `MapEffect` types replace ten one-line calls**, each with a `when` branch in the VM and another in the composable. Every added hop is a place to drop an argument that the compiler cannot check (e.g. `StartTracking(destLat, destLon)` losing the `stats == null` guard that exists at `:988`, `:1755`, `:1784` — and note `:1789` (`onTrack`) is deliberately *unguarded*). Unifying the guarded and unguarded call sites is a behaviour change. | medium | **D** (tap "Track" while a trip is already running) | a trip restarted, its stats reset |
| 3.3 | **The lifetime change removes today's re-centre-on-return.** MainActivity composes screens in an `AnimatedContent` over a `Screen` enum (`MainActivity.kt:172-224`) with no `SaveableStateHolder`, so leaving the map disposes the whole composition. Today that means `LaunchedEffect(Unit)` at `:782` re-runs on every return → `onLocationGranted()` (`:760`) → `fetchLocation()` (`:714`) → `mapLibreMap?.moveCamera(...)` at `:725` — an un-eased camera jump to your position that does **not** set `camSuspended`. An Activity-scoped VM that treats `LocationGranted` as once-per-Activity removes it. Users have been trained by it. Also removed: `SyncClient.sync()` on every return (`:568-578`) and `TripTrackingService.startMonitoring` (`:762`). None of these are in the proposal's list of behaviour changes. | **high** | **D** | the map stops re-centring when you come back |
| 3.4 | **Stale `error` survives the round-trip.** `error` (`:460`) has twelve writers and exactly one reader — `SpinSheet(error = error)` at `:1771` (inventory H9). Activity-scoped, a failure from twenty minutes ago is still there when you next expand the sheet. The proposal acknowledges it in one clause (`03:810-811`) and does not treat it as a defect. | certain | **D** | confusing but harmless |
| 3.5 | Step 8 moves `spin()` to `viewModelScope`: three GraphHopper/Overpass requests continue while the user is on another screen (`03:685-691`, correctly flagged as a decision). Combined with 3.1, the result lands as a buffered `FitCamera` on return. | certain if step 8 lands | **D** | see 3.1 |
| 3.6 | A `withContext(Dispatchers.IO)` wrapper dropped during the move (`:570`, `:583`, `:1005`, `:1037`, `:1075`, `:1113`, `:1379`, `:1407`). `viewModelScope` is `Dispatchers.Main.immediate`. | medium | **D** (ANR/jank) mostly, **F** for the marginal ones | main-thread network |
| 3.7 | Step 7 changes **ownership and lifetime in one commit** for the file's most-shared state (`destination`/`destinationName`/`route`/`candidates`, eleven writers per `03:26-40`), deletes `SpinResultHolder`/`SpinResult`/`seedRouteNavigation` (`:369-414`) and rewrites `RoutesScreen.kt:202`. Two orthogonal risks in one diff. | — | **D** + **T** | see riskiest-step section |
| 3.8 | **`withFrameNanos` in `viewModelScope` throws** — no `MonotonicFrameClock`. The proposal correctly keeps both loops in composition (`03:570-571`). If a later contributor "finishes the migration", it crashes. | low now, medium later | **C**-adjacent: an immediate crash | loud, therefore acceptable |
| 3.9 | **State that moves into the VM becomes unreachable from the car, the service and the relays.** `DetourCarSession` (`car/DetourCarSession.kt`) runs in the same process and reaches app state through the singletons; `androidx.car.app.Session` is not a `ViewModelStoreOwner`. The proposal is careful not to move any singleton state — which is precisely why it also admits it does not deliver "the ViewModel owns the screen's state" (`03:769-772`). | structural | design review | see one-way doors |
| 3.10 | Half-migration. The proposal says it itself: stopping between steps 6 and 11 leaves two state-ownership models on one screen (`03:818-822`). | medium (this is how migrations actually end) | code review | worse than either endpoint |

**Verdict:** steps 1–4 are excellent and are shared with the other proposals. Steps 5–11
have the lowest pre-ship verifiability of anything here, because **their value *is* a
lifetime change**, and a lifetime change's failure surface is "state that survived when it
should not have" and "an effect that fired later than it should have" — categories no
compiler, no JUnit4 test and no mock replay can enumerate. They can only be walked
through by hand, adversarially, and the walk is not written down anywhere.

---

### Proposal 04 — Domain-service extraction

| # | Failure mode | Likelihood | Detected by | Blast radius |
|---|---|---|---|---|
| 4.1 | **Path-dependent transcription error in `SectionAverageTracker`.** The machine at `:1174-1230` depends on: `sectionAvgKmh = null` *and* `sectionLimitKmh = maxspeedKmh` both set on entry (`:1204-1205`); `accMeters += dist(last, pos)` *before* `last = pos` (`:1208-1209`); average published only past 20 m (`:1211`); exit gate ignored below 150 m (`:1217`, comment `:1214-1216`); overshoot `span*1.4+400` (`:1219`); 30-min timeout (`:1220`). The KDoc at `:290-299` records that this machine has **already shipped one bug of exactly this class**. | medium-high | **U** if characterisation tests are written first; **R/M** otherwise | a wrong average shown against a real fine |
| 4.2 | **The bundled "fix" changes the hysteresis — the most under-rated risk in the whole exercise.** The proposal correctly identifies that `withContext(Dispatchers.IO)` inside the collector at `:1037` and `:1075` stalls the fix loop on a conflating `StateFlow`, dropping fixes (`04:57-64`). It proposes fixing it. But the ambient-limit consumer's 3-miss clear rule (`:1050`) has only ever run against a fix stream *with those drops in it*. Delivering every fix makes `speedLimitMisses` reach 3 sooner, so the sign clears faster on an untagged stretch. The constant `3` was tuned against the current behaviour. The proposal schedules this as "a separate follow-up commit" (`04:733`) but does not model the coupling. | **high** | **M** (route ii, three limit changes) A/B | the speed-limit sign flickers off on roads where it used to hold |
| 4.3 | `CameraEase` unification picks one dt clamp. Phone: `coerceIn(0.0, 0.1)` (`:1297`). Car: `coerceIn(0.0, 0.25)` (`car/CarMapRenderer.kt:396`). Also phone seeds from `camTarget ?: myLocation` (`:1280`) while the car snaps on a NaN sentinel (`car/CarMapRenderer.kt:200-207`). Whichever is chosen, one surface changes. | certain | **M** (phone), needs a head unit (car) | camera lurch after a dropped frame; first-frame jump |
| 4.4 | **A service owns a `CoroutineScope`.** The proposal's load-bearing rule is "no service owns a scope; `run()` is a `suspend fun` the caller drives" (`04:341-343`, `04:1008-1011`) — enforced by review and a suggested CI grep, nothing else. The repo's own convention pushes the other way (`ConvoyLiveClient.kt` owns one and must be explicitly cancelled). A violation in `SpeedCameraFeed` or `SpeedLimitService` means an Overpass request every few kilometres forever; in `CircleFixFeed` it is a `delay(120_000)` poll that no absence of fixes will stop. | medium | **F only** | battery regression, discovered in a week |
| 4.5 | `:shared/commonMain` has no `Dispatchers.*`. ~12 `withContext(Dispatchers.IO)` sites must each be judged: dropped for network (correct, Ktor suspends) or hoisted to the Android caller for file reads (`ExploredArea.load()` `:1407`, `SyncClient.sync()` `:570`). One wrong judgement on a file read puts blocking I/O on the main thread. | medium | **D** (jank/StrictMode) usually, **F** for the fast ones | ANR risk |
| 4.6 | `spin()`'s catch-clause order is load-bearing (inventory H15): `TimeoutCancellationException` at `:1496` is caught *before* `CancellationException` at `:1505`, and the former **is a** subtype of the latter. Reorder them in `SpinSession.spin()` and a 30 s timeout is silently reclassified as "user cancelled": rethrown, `finally { spinning = false }` (`:1509-1511`) runs, and the user sees the spinner stop with no error at all. | medium | **D** (airplane mode + a 30 s wait) | a spin that fails silently |
| 4.7 | `spin()`'s error precedence: `serverError` (`:1404`) is captured in the round-trip branch and read by the timeout handler at `:1498` *and* by the fallback message at `:1458-1460` — the fallback produces a usable route **and** an error simultaneously. An `Outcome` sealed type that models them as exclusive loses the "approximate loop instead" warning. | medium | **D** (point at a dead routing server) | user silently gets an Overpass approximation believing it is a real loop |
| 4.8 | `Fix` moved to `:shared` (step 0) becomes an iOS-visible API | certain | C | see one-way doors |
| 4.9 | `CircleFixFeed` refcount bug: poll never starts, or never stops | medium | **D** / **F** | stale friend markers, or battery |

**Verdict:** this proposal targets the code where the bugs actually are, and it is the
only one that ends live duplication with receipts. Its risk profile is also the most
*addressable*, because the mock harness plus characterisation tests can close most of
4.1, 4.2, 4.3, 4.6 and 4.7 before merge. That is a materially better position than the
proposal itself claims ("without a real road, those steps are gambling", `04:1179-1181`) —
it did not know about `tools/mocklocation`.

---

### Proposal 05 — MVI / reducer (variant B recommended by its author)

| # | Failure mode | Likelihood | Detected by | Blast radius |
|---|---|---|---|---|
| 5.1 | **A behaviour change is already present in the proposal's own reducer sketch.** `05:537-538`: `is MapAction.SpinRequested, is MapAction.CandidateChosen -> s.copy(suspended = true, lastGestureMs = a.nowMs)`. But `spin()` at `:1403` sets **only** `camSuspended`, not `lastGestureMs` — inventory H8 flags this as the one asymmetry in the seven-path "set a destination" table and says explicitly that unifying it changes behaviour. The sketch unifies it. Concretely: today, if you pan and then spin within 8 s, the camera can un-park as soon as the original quiet period expires; after the reducer, spinning restarts the quiet clock. This is the exact class of silent change the whole exercise is about, and it is in the example code. | **already present in the design** | **M** (route iv) | camera behaviour changes under a spin at speed |
| 5.2 | **Step 6 (`SpinRound`) makes a second copy of externally-owned state.** `spinOffer` (`:498`) and `spinVotes` (`:499`) are `collectAsStateWithLifecycle` views of `ConvoyLiveClient` flows, mutated by `sendSpinOffer`/`clearSpinOffer`/`sendSpinVote` from six sites (`:835`, `:867`, `:965`, `:1526`, `:1716`, `:1721`, `:1727`, `:1734`). Folding them into `SpinRound` means the reducer holds a copy that must be kept in sync by `OfferReceived`/`VotesChanged` actions. A dropped or reordered sync means the card shows votes for a cleared offer, or the auto-commit at `:858-863` fires twice, or two devices resolve to different candidates. | medium | **T only** (two devices + server) | *the* failure the feature exists to prevent (`:856-857`) |
| 5.3 | Step 4's `FixArrived` needs a collector. The auto-resume effect at `:700-712` "collapses into the single FixArrived dispatch" (`05:659-660`), but `FixRouter` is step 8. So step 4 adds a **seventh** `lastFix` collector that runs *always* rather than only while parked. | medium | **F** (marginal CPU) | small, but it is an unstated change |
| 5.4 | `resultsOnScreen` (`05:413`) must be derived to exactly `spinning \|\| candidates.isNotEmpty() \|\| spinOffer != null` (`:701`). Today the effect *restarts* on those flags; in the reducer the flag is read at dispatch time. Equivalent only if maintained on every path. | medium | **M** | camera resumes over the candidates card |
| 5.5 | Threading `nowMs` onto every action: 12 `System.currentTimeMillis()` sites (`:676`, `:689`, `:707`, `:813`, `:838`, `:1032`, `:1070`, `:1183`, `:1371`, `:1679`, `:1830`, `:3106`). Missing one is a compile error (good), but supplying the *wrong* clock (e.g. dispatch time vs fix time for the 8 s rule at `:707`) is not. | low-medium | **U** (this is what the pattern buys) | the quiet period measured from the wrong instant |
| 5.6 | Variant A only: a single `MutableStateFlow<MapState>` invalidates the whole screen on every GPS fix, over a GL surface with a fog view riding the camera-move callback (`:945-946`). The proposal rejects variant A for this reason (`05:822-832`). | n/a if B is chosen | **D** (visible frame drops) | frame rate on the only 60 fps screen |
| 5.7 | Reducer parameter leak: `reduceSpin` needs `me` and `peers` (`:858-866`), `reduceSection` needs `sections` (`:1193`). The proposal predicts someone will quietly capture them as fields later (`05:874-879`). | medium, over months | code review | the pattern's guarantee evaporates |
| 5.8 | Debuggability regression: today a "camera resumed while I was reading candidates" bug is a breakpoint at `:706` and four locals; after, you are walking backwards from `reduceCamera` to find which collector produced the action. The proposal states this honestly (`05:834-846`). | certain | n/a | slower diagnosis of the field bugs this file actually has |

**Verdict:** variant B on `SectionTracker` is the best-targeted single piece of work in
any of the five documents — a machine with a documented shipped bug, unobservable state,
and eleven test names that read like the spec. Variant B on `SpinRound` is the riskiest
single step anywhere in the five plans. Those two should never be treated as one
proposal's "yes".

---

## Verifiability ranking

Scored as: **what fraction of this proposal's changes can be proved not-broken before a
user is on a road?** Assuming phase 0 below is done (CI runs tests; mock replay routes
exist). Without phase 0, subtract roughly 30 points from everything except 01.

| Rank | Scope | C | U | D | M | Irreducible (R/T/F) | Score |
|---|---|---|---|---|---|---|---|
| 1 | **01 phases 1–2** (≡ 02 steps 0a–0f ≡ 03 step 2 ≡ 05 step 2) | ~95% | — | ~5% (icons, layout) | — | ~0% | **~100%** |
| 2 | **05 variant B, step 3** (`CameraAuthority` + tests, *unwired*) | 100% | 100% | — | — | 0% | **100%** (it changes nothing) |
| 3 | **04 steps 2, 4, 8** + 03 step 3's pure half (`NavPolicy`, `GroupSpinRules`, `FollowCamera`, `NavAppPolicy`) | ~40% | ~55% | ~5% | — | ~0% | **~95%** |
| 4 | **02 steps 1, 2, 7, 8, 9** (target, road-hazard, convoy, location holders) | ~50% | ~5% | ~25% | ~15% | ~5% | **~90%** |
| 5 | **04 steps 5–7 / 05 step 5** (the GPS machines, with characterisation tests) | ~20% | ~45% | ~5% | ~20% | ~10% (a real section, a real camera) | **~85%** |
| 6 | **02 steps 3–6** (surface, camera, nav, spin holders) | ~45% | ~5% | ~25% | ~20% | ~5% | **~85%** |
| 7 | **05 step 4** (wire `CameraAuthority`) | ~25% | ~45% | ~10% | ~15% | ~5% | **~85%** |
| 8 | **01 phase 3** (effect functions) | ~55% | 0% | ~15% | ~20% | ~10% | **~80%**, but the residual 20% is all silent-capture class |
| 9 | **04 steps 9–10** (`NavSession`, `SpinSession`) | ~35% | ~10% | ~25% | ~20% | ~10% | **~80%**, low test leverage (needs four stubbed suspend fns) |
| 10 | **05 step 6** (`SpinRound`) | ~30% | ~30% | ~10% | — | **~30% (T)** | **~65%** |
| 11 | **03 steps 5–11** (the ViewModel proper) | ~40% | ~5% | ~30% | ~5% | **~20% (adversarial-navigation + F)** | **~60%** |

**Ranking of the five proposals as wholes, by "how much can I prove I didn't break":**

**01 ≫ 05(variant B) > 02 > 04 > 03.**

Two qualifications that matter more than the order:

- **04 has the highest ceiling.** Once characterisation tests and replay routes exist, it
  is the only proposal whose *residual* risk keeps falling with every step, because each
  extracted machine arrives with tests that guard it forever. 01 and 02 have low risk and
  zero residual coverage — after they land, the next change to `spin()` is exactly as
  unverifiable as it is today.
- **03's floor is the lowest, and it is structural, not fixable by effort.** Its value is a
  lifetime change (`03:709-716`). You cannot write a test for "state that no longer
  resets", and you cannot replay it — you have to navigate adversarially and know what to
  look for. Its own risk list omits the three largest instances (3.1, 3.3, 3.4).

---

## Reversibility / one-way doors

| Proposal | Revert cost at 3 weeks | One-way doors |
|---|---|---|
| **01** | **Trivial.** `git revert` any of the 14 commits. No new concept, no dependency, no cross-module change. Cross-file call sites see a zero-line diff throughout (same package, `01:45-49`). | None functional. Two cosmetic-but-permanent: `git blame` provenance on 1,355 moved lines, and every doc citing `MapScreen.kt:NNN` (inventory §6.2) goes stale. |
| **02** | **Low per step.** Each holder is one file plus its call sites. Steps 0a–0f are free to revert. | Weak/social: once eight `remember*State` holders exist, new state goes into holders by default and nothing enforces it (`02:756-758`). The `MapSpinStateSaver` becomes load-bearing for rotation and is easy to forget when reverting. |
| **03** | **High, and it grows.** Adding `lifecycle-viewmodel-compose` is the project's first UI-layer framework dependency and establishes a convention the other 18 screens are then expected to follow (`03:320-324`). Step 7 deletes `SpinResultHolder`/`seedRouteNavigation` and rewrites `RoutesScreen.kt:202`. Activity scope becomes something later features rely on. | **The hardest door in the set, and it is a *reach* door, not a code door.** Every piece of state that moves from a singleton into the VM becomes unreachable from `DetourCarSession` (same process, not a `ViewModelStoreOwner`), `TripTrackingService`, `wear/NavRelay`, `BleNavServer` (`03:778-793`). The proposal avoids it by moving no singleton state — which is exactly why it cannot claim the VM owns the screen. Also: a half-migration is explicitly worse than either endpoint (`03:818-822`), so this door must be walked all the way through. |
| **04** | **Low for steps 0–8, high for 9–10.** Steps 2, 4, 8 are pure functions with tests: delete the file, restore the inline code. | **Two.** (a) `data class Fix` → `shared/…/data/Fix.kt` and the new `shared/…/drive/` package are iOS-visible API (`iosApp/` already consumes `SpinPickerKt.pickThreeCandidates`, `04:920-928`); cheap now (step 0 is a two-file change, `04:727`), expensive once Swift depends on it. (b) The "no service owns a `CoroutineScope`" rule (`04:341-343`) is reversible in code but its violation is only detectable in the field, so a broken version can live for weeks — a *latency* door rather than a code door. |
| **05 variant B** | **Low.** Three files (`CameraAuthority.kt`, `SectionTracker.kt`, `SpinRound.kt`), each individually revertable, plus `MapTuning.kt` which is a pure move. Step 3 (unwired reducer + tests) is revert-free by construction. | Minor: the `nowMs`-on-action convention is local to three files. Step 6 (`SpinRound`) is the exception — it takes over `spinOffer`/`spinVotes` ownership from `ConvoyLiveClient`, and reverting after the convoy path has been exercised in the field means re-deriving which side owned what. |
| **05 variant A** | **High.** A 430-line reducer plus 150-line action hierarchy that nothing else in the repo resembles (`05:856-861`). | The pattern itself. Every subsequent feature pays the four-file tax (`05:815-819`). |

**Ranking by reversibility: 01 > 05-B > 02 > 04 > 03.**

Note that this is nearly the same order as verifiability, and that is not a coincidence:
in a codebase whose only automated gate is a compiler, the things you can prove are the
things you can also cheaply undo, because both properties come from the same source —
*the change did not move a decision*.

---

## Riskiest single step in each proposal's plan

### 01 → **Step 13, `MapCameraEffects.kt`** (`:665-712`, `:1232-1333`)

Because it converts three separate silent-failure mechanisms in one commit: the touch
listener's live read of `camSuspended` at `:689` (risk 1.5), the follow loop's five
coroutine-locals (`:1281-1284`, `:1289-1292`), and `CameraTargetEffect`'s
"null-bearing-means-keep" contract (`:1239` → `01:453-455`). All three compile. All three
fail only in motion. The proposal names this step "the `rememberUpdatedState` commit" and
says to review it line-by-line (`01:567-569`) — correct, and the review is the only gate.

*Runner-up:* step 12 (`MapSpeedEffects.kt`), not for what it does but for what it tempts:
the moment those effects have parameters, keying on one resets the section accumulator
(`:1175-1179`) or re-triggers Overpass (`:528-533`, risk 1.10).

### 02 → **Step 6, `MapSpinState` + `MapSpinStateSaver`**

Three independent hazards in one commit: the `rememberSaveable` regression (risk 2.1,
which the proposal flags), the `ServerConfig` divergence the saver *introduces* (risk 2.2,
which nobody flagged), and `spin()` itself — 122 lines, two code paths, a four-branch
error taxonomy with load-bearing catch order (`:1496` before `:1505`), the `serverError`
local threaded from `:1404` to `:1458` and `:1498`, and eight writes.

*Runner-up:* step 3 (`MapSurfaceState`) — MapView teardown order (`:643-647`) and the
`FogView` re-add guard (`:657`), both of which fail as "black map" or "leaked GL renderer"
(`02:669`).

### 03 → **Step 7, "move the destination spine"**

It changes state **ownership** and state **lifetime** in one diff, for the four variables
with eleven writers (`03:26-40`), while also deleting a process-global (`:369-414`),
rewriting a second file (`RoutesScreen.kt:202`) and absorbing the convoy vote-resolution
rule (`:842-870`). The proposal calls it "the widest-blast-radius commit in the plan"
(`03:682-683`) and it is right — but it understates it, because ownership and lifetime are
separable and it does not separate them. Split it: (a) ownership only, still
composition-scoped, `SpinResultHolder` retained; (b) lifetime only, as its own commit with
its own hand-walk.

*Runner-up:* step 8 (`spin()` → `viewModelScope`), because it is where the buffered
effect-channel replay (risk 3.1) first becomes observable.

### 04 → **Step 10, `SpinSession` + `SpinResultHolder` move**

Nine pieces of composable state change owner (`04:1060-1063`), `spin()`'s error precedence
and catch order must survive intact (risks 4.6, 4.7), and it is the step with the least
test leverage — its value is orchestration, which needs four stubbed suspend functions to
reach (`04:853-855`). The proposal rates it "high" and says landing steps 0–8 and stopping
would be defensible (`04:1064-1065`). Agreed.

*But the most **under-rated** step in any of the five plans is 04's step-6 follow-up
commit* — moving the Overpass fetch off the fix collector (`:1037`, `:1075`). It is
correctly identified as a bug fix, it is correctly isolated into its own commit, and its
second-order effect on the 3-miss hysteresis at `:1050` is not modelled anywhere (risk
4.2). It looks like the safest kind of change — deleting a stall — and it silently
retunes a constant.

### 05 → **Step 6, `SpinRound`**

The only step in any plan whose failure mode is a distributed one, whose correctness
argument is sixteen lines of prose (`:842-857`), and whose only detector is two devices
plus a server. It also creates a second copy of state owned by `ConvoyLiveClient` (risk
5.2). The proposal says "do it alone, on its own branch, with the convoy path exercised on
two devices" (`05:673`) — that is the right instruction and it should be a merge gate, not
advice.

*Note on step 4:* the proposal rates it as the first behavioural risk and gives a four-item
verification list (`05:661-662`). That list is good but incomplete, because the reducer
sketch itself already contains risk 5.1 — the unification of `spin()`'s camera park with
the grace-period stamp. Step 4 is riskier than its own author thinks, and it is riskier in
a way that a careful reviewer would catch only by reading inventory H8.

---

## Manual verification checklist

**How to use this.** Every item is one observable behaviour with the lines that produce
it. Tier 0 runs on every commit. Tier 1 runs after any commit that touches the composable
body (`:419-1837`). Tier 2 runs after any commit that touches a `lastFix` consumer
(`:487`, `:704`, `:1026`, `:1065`, `:1147`, `:1180`) or the camera. Tier 3 runs before
merging anything in the convoy, navigation-session or relay paths.

**Set this up once, before any refactor starts:**

```
# 1. Make the tests a gate (they are not, today — .github/workflows/build.yml)
#    add to the build job:  ./gradlew :app:testDebugUnitTest :shared:allTests

# 2. Build the replay harness (docs/PLAY_LOCATION_DECLARATION.md:159-178)
cd tools/mocklocation && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/*.apk
adb shell appops set com.jellemax.mocklocation android:mock_location allow

# 3. Push a route and replay it (one "lon lat" pair per line)
adb shell "run-as com.jellemax.mocklocation sh -c 'cat > files/route.txt'" < routes/ii-urban.txt
adb shell am start-foreground-service -n com.jellemax.mocklocation/.MockService \
    --es route /data/data/com.jellemax.mocklocation/files/route.txt --ei intervalMs 1000
adb shell am stopservice -n com.jellemax.mocklocation/.MockService
```

**Check in four canonical routes under `tools/mocklocation/routes/` and record the
current behaviour on each before touching anything.** Point spacing ÷ `intervalMs` sets
the speed, so the routes encode their own speeds:

- **(i) `section.txt`** — a motorway run entering a real trajectcontrole at one end and
  leaving at the other, at ~100 km/h (≈28 m spacing at 1000 ms). Exercises `:1188-1206`,
  `:1208-1213`, `:1217-1221`, and the camera chime at `:1151-1165`.
- **(ii) `urban.txt`** — crosses at least three posted-limit changes at ~50 km/h, including
  one untagged stretch long enough to burn the 3-miss counter (`:1050`).
- **(iii) `offroute.txt`** — starts on a route the app will fetch, then deviates by >60 m
  for >15 s. Exercises arrival (`:1360-1361`) and reroute (`:1372-1373`).
- **(iv) `stopstart.txt`** — crosses 3 m/s in both directions with a long stationary
  segment. Exercises the resume rule (`:706-710`), the 2 m/s bearing hold (`:1239`), the
  1.4 m/s HUD gate (`:1641`), and the section entry's bearing floor (`:1189`).

Also useful, and already documented: `docs/DEBUG_INTENTS.md` for trip-ended notifications
and history seeding without driving.

---

### Tier 0 — every commit (free)

- [ ] `:app:assembleDebug` **and** `:app:assembleRelease` in the devcontainer (R8 catches
      what debug does not). `docker exec -u 1000:1000 …`; never a bare `./gradlew build`.
- [ ] `:app:testDebugUnitTest` and `:shared:allTests` pass.
- [ ] `git show -M -C --stat` on any move commit shows renames, not add+delete (proves no
      reformatting; keeps `git blame -C` working).
- [ ] `grep -c rememberUpdatedState` across the changed files — the count must not drop
      unless the commit message says which of `:937`, `:938`, `:939`, `:1138`, `:1139`,
      `:1140`, `:1173`, `:1250` was removed and why the read is now safe.
- [ ] `grep -rn 'CoroutineScope(' ` over any new non-Compose class — must be zero
      (proposal 04's load-bearing rule, `04:341-343`).
- [ ] `grep -rn 'Dispatchers' shared/src/commonMain` — must stay zero.
- [ ] Diff review: no `LaunchedEffect` key list changed in a commit that also moves the
      effect body.

### Tier 1 — desk, stationary, ~15 minutes

**Cold start and permissions**

- [ ] Fresh install → the permission sweep asks for fine + coarse (+ activity recognition
      on Q+, + notifications on T+) (`:782-801`).
- [ ] Deny location → expand the spin sheet; "Location permission is required" is shown
      (`:778` → `:1771`). It is invisible with the dock collapsed — that is current
      behaviour (inventory H9), not a bug to fix mid-refactor.
- [ ] Grant → the background-location disclosure dialog appears on Q+ (`:763-769`,
      `:1798-1808`). "Allow" raises the system prompt (`:1803`); "Not now" dismisses
      (`:1806`).
- [ ] On grant the camera jumps once to your position, **un-eased and without parking**
      (`:725-726`). Confirm it is a jump, not a glide — that distinguishes `fetchLocation`
      from the follow loop.
- [ ] The tracking foreground-service notification appears (`:762`).

**Map surface**

- [ ] Basemap renders; OSM/OpenFreeMap attribution and logo sit **above** the collapsed
      dock (`:638-639`).
- [ ] Flip Settings → theme, three times. Style reloads, overlays rebuild, the fog scrim is
      still present and still on top, and there is exactly one `FogView`
      (`:652-663`, guard at `:657`).
- [ ] Navigate to Hub and back. No crash; the map re-creates; fog re-attaches. **Confirm
      GPS cadence returns to navigation-grade** — the composition's `onDispose` calls
      `setUiVisible(false)` (`:620-624`) and re-adding the observer must re-deliver
      `ON_START` → `setUiVisible(true)` (`:605`). Watch the speed HUD update rate or
      logcat.
- [ ] Rotate the device. Surviving: `radiusKm`, `minRadiusKm`, `poiKind`, `directionDeg`,
      `settingsCollapsed` (`:449`, `:450`, `:462`, `:463`, `:526`) and
      destination/name/route/candidates via `SpinResultHolder` (`:383`, `:453`, `:467`).
      **Not** surviving today: `navigating`, `navProgress`, `speedCameras`, `sectionAvgKmh`,
      an in-flight `spinJob`. Record what you observe; do not silently change it.

**Camera (stationary parts)**

- [ ] Drag past the touch slop → the camera parks and the follow icon changes to
      `LocationSearching` (`:686`, `:553` → `:1580`, `:2732-2735`).
- [ ] Tap without leaving the slop circle → still following (`:688-689`).
- [ ] Two-finger pinch → parks immediately, no slop test (`:684`).
- [ ] Follow button on→off (`:1587`): the map levels to north-up **exactly once**
      (`:1273-1278`).
- [ ] Follow button off→on (`:1588`): follow returns *and* suspension clears.

**Spin**

- [ ] CAR/MOTO spin → three pins in three distinct colours (`:319`, `:892-894`); the card
      rows carry the matching swatches; the camera fits all three plus your position
      (`:1492-1494`); a haptic buzz fires (`:1491`); the sheet folds to the dock.
- [ ] Tap a pin on the map → commits that candidate; camera fits you + destination
      (`:814`); candidates clear (`:808`).
- [ ] Tap a row in the card → identical result (`:1715-1717`).
- [ ] Reroll (`:1718`) clears and re-spins. Cancel (`:1719-1722`) clears and returns to the
      dock — with no empty card frame during the exit animation (`:1697-1698`).
- [ ] Press spin *while* spinning → the job is cancelled (`:1751`/`:1780`), the spinner
      stops, and **no error is shown** (the `CancellationException` rethrow at `:1505` with
      `finally { spinning = false }` at `:1509-1511`).
- [ ] Round-trip mode → the reach circle is `radiusKm * 250` not `* 1000` (`:882-883`); the
      result has a route and a **null** destination (`:1462-1464`); the camera fits the
      polyline (`:1468`).
- [ ] Point at a dead routing server → the sheet shows "Server route failed (…) —
      approximate loop instead" **and** you still get a usable loop (`:1458-1460`). Both
      halves.
- [ ] Airplane mode, wait 30 s → the timeout message appears and differs between "no
      routing server configured" and the general case (`:1496-1504`).
- [ ] Drag min-distance above radius → it clamps (`:562-563`).
- [ ] Switch travel mode → radius resets to the mode default, destination/name/route/
      candidates all clear (`:1515-1527`).

**Destination sources — each must set the same four fields and park the camera**

- [ ] Long-press the map → "Dropped pin" (`:949-955`). Long-press **while navigating** →
      nothing happens (`:951`).
- [ ] Saved-place chip → destination set, camera animates to zoom 14 over 600 ms
      (`:1680-1681`), camera parked with a fresh grace period (`:1678-1679`).
- [ ] Search: opens with the keyboard up (`:1863`); one character shows filtered recents
      only (`:1872-1875`); two or more shows recents instantly then Photon results after a
      300 ms pause (`:1880-1883`), recents first and deduped (`:1884-1887`), a history icon
      on recents (`:1945`); picking closes the dialog, sets the destination, and animates
      over 800 ms (`:1831-1832`).
- [ ] "Save pin" → the dialog pre-fills the destination name, but **not** when it is
      "Dropped pin" (`:1812`); saving adds a chip (`:1814`).

**Nav-app dispatch**

- [ ] With nothing remembered, "Go" opens the menu (`:2160`, `:2181-2185`).
- [ ] Pick Google Maps → it launches and is remembered (`:2148`); the next tap goes
      straight there (`:2162`).
- [ ] With a round trip active, the menu offers "Google Maps (round trip)" only — Waze and
      "Other app" are hidden (`:2213-2231`) — and a remembered Waze falls back to opening
      the menu (`:2163`).
- [ ] "Navigate in app" is offered only when the routing server is usable **and** there is
      a destination or a loop with instructions (`:1748-1750`, `:1777-1779` — computed
      identically at both sites, inventory H8).

**Trip tracking**

- [ ] The active-trip card appears and its duration counts up at 1 Hz **with no new fixes**
      (`:3107-3111`).
- [ ] "End trip" stops the service (`:1636`); the card and button animate out retaining
      their last values (`:1654-1655`).
- [ ] SpinSheet "Track" starts a trip **unconditionally** (`:1788-1790`), unlike "Go" which
      guards on `stats == null` (`:1755`, `:1784`). Tap Track while a trip is running and
      confirm the current behaviour is preserved.

**Layers / fog**

- [ ] The layers button toggles the panel (`:2742`), and a tap or long-press on the map
      closes it (`:950`, `:958`).
- [ ] The fog switch persists to `Settings` (`:1591`) and survives a Hub round-trip.

### Tier 2 — mock replay (after any camera or `lastFix` change)

Run each route **before** and **after** the commit and compare. Screen-record both.

- [ ] **(iv) stopstart** — pan the map at ~0 km/h, then let the replay accelerate past
      3 m/s: the camera must **not** resume until 8 s after your last touch (`:706-710`).
      Repeat with a spin on screen: it must **never** resume while spinning, while
      candidates are up, or while a convoy offer is open (`:701`).
- [ ] **(iv)** — pan while already above 3 m/s, lift, and confirm the quiet period restarts
      from the lift (`:689`).
- [ ] **(iv)** — below 2 m/s the map must **hold** its bearing rather than spinning on noisy
      GPS heading (`:1239`); the position marker holds the same bearing (`:900`).
- [ ] **(iv)** — the speed dial appears above 1.4 m/s and stays until the eased number
      falls below 2.0 km/h (`:1641`); the number glides rather than stepping once a second
      (`:1251-1262`).
- [ ] **(iv)** — while cruising at a steady speed the camera goes quiet: no per-frame
      `setCamera`. Watch for the fog stopping its redraw (`:945`, epsilon gate
      `:1320-1331`).
- [ ] **(ii) urban** — the posted-limit sign flips at each road change and clears **only
      after three consecutive misses** (`:1045-1054`). Count the fixes between the last
      tagged road and the sign disappearing; it must match before and after (this is
      risk 4.2's detector).
- [ ] **(ii)** — the sign is present while free-driving and is replaced by the route's own
      limit once navigating (`:1024-1025`, `:1644-1645`).
- [ ] **(i) section** — speed-camera markers appear (`:1087-1088`); the chime fires **once**
      per camera, only over limit + 3 km/h, only within the 45° wedge, silent when the
      limit is unknown, and re-arms once the camera is behind you (`:1151-1165`).
- [ ] **(i)** — entering the trajectcontrole in the correct direction starts the Ø chip
      (`:1188-1206`); **entering from the far end on the way out must not** (`:290-299` —
      this is the regression test for a bug that already shipped once).
- [ ] **(i)** — the average is accumulated distance over elapsed time, published only past
      20 m (`:1211-1213`); the entry gate does not end the measurement before 150 m
      (`:1217`); the chip turns red above the section limit (`:3018-3019`); it clears at the
      far end, on overshoot, or after 30 minutes (`:1217-1221`).
- [ ] **(iii) offroute** — arrival fires within 40 m on-route (`:1360-1361`) and stops
      navigation (`:1363`); the route line **stays drawn** afterwards (`:974-976`); the
      driven shading clears (`:977`); the vehicle icon snaps to north-up because
      `camTargetBearing` is nulled (`:973` → `:900`).
- [ ] **(iii)** — deviating >60 m triggers exactly one reroute, then nothing for 15 s
      (`:1372-1373`); a loop (null destination) never reroutes and never arrives
      (`:1360`, `:1370`).
- [ ] **(iii)** — the "Rerouting" state shows in the banner (`:1566`) and clears
      (`:1386`).
- [ ] Fog: the scrim clears along the live trace and around your position (`:928-931`) and
      redraws on manual pan and pinch, not only while following (`:945-946`).

### Tier 3 — irreducible (before merging convoy / relay / session changes)

**Convoy — two devices + a running server**

- [ ] Join → the convoy pill appears (`:1583`); the mic permission is requested at join,
      not at launch (`:747-757`); the PTT button appears only when connected **and**
      `activeConvoyId != null` (`:1605`).
- [ ] Push to talk → red while pressed; the other device shows the talking ring
      (`:1610`); release stops.
- [ ] **Background the app mid-press** (home, recents, or an incoming call). The mic must
      stop (`:612-615`) — `awaitRelease()` (`:2932`) may never fire. Verify with the
      microphone-in-use indicator, not by assumption.
- [ ] Leave the map to Hub mid-press → the dispose branch must also stop it
      (`:620-624`).
- [ ] Share a spin (`:1726-1728`) → **both** devices show the offer's three candidates,
      including the sharer, whose own local rolls are overridden (`:823`).
- [ ] Vote by tapping a pin (`:965`) and by tapping a row (`:1716`) — identical effect.
- [ ] Once every live peer plus the sharer has voted, the sharer broadcasts the leader as
      a one-candidate offer (`:866-868`) and **both devices commit the same destination**
      (`:860-862`) with `route = null` (`:833`).
- [ ] Ties, including "nobody has voted", resolve to the lowest index on both devices
      (`:361-367`).
- [ ] "Go with the lead" (`:1732-1737`) produces the same result as the automatic close.
- [ ] Switch travel mode mid-round → the offer is cleared on both devices (`:1526`).
- [ ] Let one peer go quiet for >20 s → the round still resolves (this is the case the
      comment at `:848-857` exists for).

**Relays**

- [ ] With a watch paired: turn-by-turn appears while navigating (`:1356`) and clears on
      stop (`:978`).
- [ ] With a BLE display: speed only while not navigating (`:1341`), full progress while
      navigating (`:1357`), cleared on stop (`:979`).

**Field (accept a delay before signing off)**

- [ ] Leave the app backgrounded with the map composed for an hour. Battery drain must
      not increase. This is the **only** detector for a service that acquired its own
      `CoroutineScope` (risk 4.4) or a prefetch throttle that lost its centre (risk 1.10).
- [ ] Watch for repeated Overpass hits in logcat over a 30-minute drive — the throttles are
      10 s / 15 s / 120 s (`:1034`, `:1072`, `:279`).

---

## Convergence: what all five agents independently reached for

Five agents, five different mandates, no communication. Where they agree, that agreement
is evidence.

| # | Concrete step | 01 | 02 | 03 | 04 | 05 | Is low risk what makes them converge? |
|---|---|---|---|---|---|---|---|
| A | Move the stateless tail (`:1839-3193`) into same-package topic files, `private` → `internal` | steps 5–11 | steps 0a–0f | step 2 | §6.1 | step 2 | **Yes.** Every one of them describes it as zero-risk and every one puts it first. 01: "provable by inspection". 02: "risk: none". 03: "zero risk". 05: "zero behavioural risk". |
| B | `TravelMode.icon` (`:213-219`) to its own file, still public, still package `ui` | ✓ | ✓ | ✓ | ✓ | ✓ | **Yes**, plus a shared constraint: it has three consumers (`RoutesScreen.kt:303`, `HistoryScreen.kt:317`, `RouteEditorScreen.kt:386`) that must not need an import edit. |
| C | Lift the pure helpers and tuning constants: `smoothBearing` (`:221-230`), `sectionExitGate` (`:290-314`), `leadingSpinIndex` (`:357-367`), `CANDIDATE_COLORS` (`:316-320`), `asRouteCandidates`/`asSpinCandidates` (`:322-355`), `CAM_*`/`SPEED_*`/`SECTION_*` | step 2/3 | file 5/9 | files MapSurface / MapCandidatesCard | §4.1 | step 1 | **Yes** for the move; **no** for the destination — same package (01, 02, 05) vs `:shared` (04) vs mixed (03). That disagreement is a real decision, not a detail. |
| D | Unit-test those helpers with plain JUnit4 | step 4 | listed, not claimed as its own win | step 3 | §8 | test plan | **No — value drives this one.** All five agree the tests are cheap; four of five put them on the critical path. 02 is the honest outlier: "the split makes them easier to find; it is not what makes them testable" (`02:781`). |
| E | **Extract the road-hazard machines** (`:1018-1056`, `:1058-1083`, `:1134-1167`, `:1169-1230`) as separately-addressable units | step 12 (as effects) | step 2, "the most isolated concern" | step 3, plain classes + tests | steps 5–7, plain classes + tests | step 5, `SectionTracker` | **No — and this is the most important row in the table.** Every one of the five rates this medium risk. They converge on it anyway, because all five independently identified it as the highest-value non-mechanical move. Four of five say the same thing about why: it is the most self-contained concern in the file and the one with a documented shipped bug (`:290-299`). |
| F | Delete the car's copies of `smoothBearing` (`car/CarMapRenderer.kt:470`), `CAM_*` (`:53-69`) and `CIRCLE_FIX_POLL_MS` (`:78`) | partial (2 consts + 1 fn) | "enables, does not perform" | step 4 | §9 | steps 1, 7 | **Yes** for the constants; the substantive duplication (arrival/reroute, ambient limit, camera warn) only 03 and 04 actually delete. |
| G | Keep both `withFrameNanos` loops (`:1251-1263`, `:1271-1333`) inside the composition | ✓ | ✓ (`02:171-177`) | ✓ (`03:570-571`) | ✓ ("the *loop* stays on each platform", `04:530-531`) | ✓ (`05:319-325`) | **Neither — it is a hard technical constraint.** `withFrameNanos` needs a `MonotonicFrameClock`, which `viewModelScope` and a plain `CoroutineScope` do not carry. Unanimity here is the compiler speaking through five agents. |
| H | Keep `SpinResultHolder` (`:369-414`) and leave `RoutesScreen.kt:202` compiling unchanged | ✓ | ✓ | **✗ deletes it** | ✓ (moves it) | ✓ | **Yes.** Four of five treat "do not touch another file" as a constraint. 03 deletes it precisely because its whole thesis is a lifetime change — the dissent is principled, not careless. |

**Reading the table.** Rows A, B, C, F and H converge because the work is cheap and
provable — low risk is the cause. Row G converges on a technical fact. **Row E is the
signal.** It is the one place where five independent agents, three of whom were
championing incompatible architectures, all pointed at the same medium-risk block of code
and said "this is where the value is". Rows A–C are what you do because they are free;
row E is what you do because it is right.

---

## Recommended sequence, with stop-points

### Phase 0 — make verification cheap (do this before any refactor commit)

Nothing here touches `MapScreen.kt`.

- **0a.** Add `./gradlew :app:testDebugUnitTest :shared:allTests` to the build job in
  `.github/workflows/build.yml`. One line. Without it, every test written by proposals
  03/04/05 is decorative. *(Cost: minutes. Risk: none.)*
- **0b.** Build `tools/mocklocation` and check in the four canonical routes described in
  the checklist under `tools/mocklocation/routes/`, with a short README. **Record the
  current behaviour on each** — screen capture plus a logcat trace. This baseline is what
  every later A/B compares against, and it is only capturable *before* the first
  behaviour-touching commit. *(Cost: one day, most of it choosing routes that pass a real
  trajectcontrole and a real camera. Risk: none.)*
- **0c.** Promote the pure helpers to `internal` and move them (01 step 2/3 ≡ 05 step 1),
  then write their tests: `sectionExitGate` (`:290-314`), `leadingSpinIndex` (`:357-367`),
  `navAppUsableDirectly` (`:2151-2164`), `smoothBearing` (`:221-230`),
  `asRouteCandidates`/`asSpinCandidates` (`:322-355`). Order matters — the promotion must
  land first, in its own commit. *(Cost: a day. Risk: none.)*

**Phase 0 is not optional in my recommendation.** It is the difference between the
verifiability scores above and those scores minus thirty points.

### Phase 1 — the unanimous mechanical split

01 steps 1, 5–11 ≡ 02 steps 0a–0f ≡ 03 step 2 ≡ 05 step 2. Pick 01's file layout; it is
the most granular and the differences between the five layouts are cosmetic.

- Land it **in one short burst**, not spread across sprints. Fourteen sequential commits
  on one file with feature branches in flight is 01's own con #5 (`01:630-633`) and it is
  correct.
- Every commit: pure cut-and-paste, no reformatting, `git show -M -C --stat` shows
  renames. Comment rewording is a *separate* follow-up commit.
- Run Tier 0 on each; Tier 1 once at the end.
- Watch for risk 1.1 — the two `Place` icons at `:64` and `:76`.

**Stop-point A.** `MapScreen.kt` ≈ 1,565 lines. Zero behavioural risk taken. Every one of
the five proposals is still fully open, and every one of them is now cheaper, because the
subsequent state diffs are reviewable against a 1,565-line file instead of a 3,193-line
one. *If appetite runs out here, this is a legitimate and defensible outcome — but write
down that the state layer is untouched and is the next problem, or the line count will be
recorded as "MapScreen refactored" (01's con #8, `01:644-648`).*

### Phase 2 — the pure-logic extractions

04 steps 2, 4, 8 + 05 step 3. All pure functions, all with tests, all individually
revertable, all deleting car duplication.

- `NavPolicy.decide(...)` from `:1359-1389` + `car/NavScreen.kt:242-252` (the comment
  there literally says "Same arrival/reroute policy as MapScreen.kt's navigating
  LaunchedEffect").
- `GroupSpinRules` from `:322-367`, `:842-870` — the distributed rule with a sixteen-line
  correctness argument and no test.
- `FollowCamera.shouldResume(...)` from `:696-712`.
- `CameraAuthority` + its fourteen tests, **unwired** (05 step 3). This is the cheapest
  possible proof that a pattern works: it compiles, the tests pass, and the app is
  byte-identical.

Verification: Tier 0 + one Tier 1 pass. **Do not wire `CameraAuthority` yet.**

**Stop-point B.** ~170 lines of decisions now have executable specifications. The car has
lost ~45 lines of copy-paste. Still no behaviour touched.

### Phase 3 — the road-hazard machines (the row-E convergence)

04 steps 5–7 ≡ 03 step 3 ≡ 05 step 5. **Cheapest and most isolated first:**

1. `SectionAverageTracker` (`:290-314`, `:1174-1230`). One input, two outputs, eleven test
   names already written in three separate proposals. **Characterisation tests first,
   against the current code, then the move.** Constants copied byte-for-byte. A/B replay
   route (i).
2. `CameraWarner` (`:1134-1167`). Latch semantics; A/B replay route (i).
3. `SpeedLimitService` + `SpeedCameraFeed` (`:1018-1056`, `:1058-1083`). A/B replay route
   (ii), **counting fixes to the sign clearing** (risk 4.2).
4. **Separately, loudly, and last:** the deliberate fix that moves the Overpass fetch off
   the fix collector (`:1037`, `:1075`). Own commit. Own A/B on route (ii). Own line in the
   release notes. If the 3-miss count changes behaviour, retune `3` in that same commit or
   revert.

Decide up front where these land: `:shared/drive/` (04's answer, reaches iOS and the car,
one-way door 4.8a) or `app/…/map/` (05's answer, reaches the car only, no door). Given
that `iosApp/` ships without any of these three features precisely because they are welded
into a composable (`04:920-928`), and given that `Fix` is a two-file move (`04:727`), I
would take `:shared` — but take it **as an explicit decision in phase 3's first commit**,
not by drift.

**Stop-point C — this is where I would stop by default.** `MapScreen.kt` ≈ 1,300–1,400
lines; ~40 tests over the code that produces the field bugs; the car's five substantive
duplications reduced to one or two; every remaining proposal still available. The
cost-to-value curve turns sharply down after here.

### Phase 4 — optional, and **pick exactly one**

Either:

- **02 steps 1–9** (the remaining holders: target, surface, camera, nav, spin, convoy,
  location), or
- **05 variant B steps 4–5** (wire `CameraAuthority`, wire `SectionTracker` — the latter
  already done if phase 3 landed).

**These two must not both be done.** `MapCameraState` (02) and `CameraAuthority` (05) are
competing owners of the same three variables (`:521-523`); running both leaves the camera
with two sources of truth, which is strictly worse than the status quo.

If 02: mind risk 2.2 (the `ServerConfig` divergence) on step 6 and risk 2.3 (teardown
order) on step 3.
If 05: fix risk 5.1 before wiring — decide, in a standalone commit, whether `spin()`
should stamp `lastGestureMs` (`:1403` vs H8), verify it on replay route (iv), and only
then wire the reducer.

### Phase 5 — only with an explicit written decision

**03 steps 5–11.** Preconditions I would insist on:

1. The Hub-round-trip state loss (03's concern 12) is written down as a defect with a
   named owner, not discovered as a side effect.
2. Step 7 is split into ownership-only and lifetime-only commits.
3. The buffered-effect-channel semantics (risk 3.1) are specified: what happens to a
   `FitCamera` emitted while the map is not composed? My answer would be "drop it" — which
   means not a `Channel(BUFFERED)`.
4. Someone commits to finishing it. A half-migration is worse than either endpoint by the
   proposal's own analysis (`03:818-822`).

### Interleaving

- Phase 0 items are independent of each other and of everything else — run them in
  parallel with whatever else is happening.
- Phase 1's file moves are independent of each other. They can be split across people,
  provided they land within days.
- Phase 2's three extractions are independent of each other and can be interleaved with
  the tail of phase 1.
- Phase 3's three machines are independent of each other but **must not** be interleaved
  with phase 4 — mixing an owner change with a machine extraction makes the A/B replay
  uninterpretable.
- The car-side deletions can trail their extraction by one commit; they should never be in
  the same commit (a car regression and a phone regression in one revert is two bisects).

### Never in one commit

| Do not combine | Because |
|---|---|
| A move **and** a visibility change to a symbol whose call site also moves | The compiler stops being a proof of equivalence |
| A state-owner change **and** a lifetime change | 03 step 7. Two independent failure surfaces, one revert |
| An extraction **and** the bug it reveals | 04's Overpass-off-collector; 05's `lastGestureMs` unification. The "fix" hides inside a diff nobody re-reads |
| An effect body move **and** a change to that effect's key list | The key list *is* the behaviour for `:700`, `:1024`, `:1236`, `:1271`, `:1345` |
| `camSuspended` (`:1403`) **and** `lastGestureMs` — the H8 asymmetry | Whichever way you decide it, decide it alone |
| Local spin state **and** `spinOffer`/`spinVotes` ownership | 05 step 6. The convoy path needs its own bisect |
| Any `lastFix` consumer change **and** any other `lastFix` consumer change | Six independent collectors; keep the blast radius to one |
| Any phase-1 move **and** any reformatting | Kills `git log -C`, and the comments are the design record |

---

## What I would refuse to do, and why

**1. Merge the six `lastFix` collectors before phase 3 has landed and been driven.**
(`:704`, `:1026`, `:1065`, `:1147`, `:1180`, plus `:487`.) They start and stop
independently on different keys, and two of them suspend on network I/O inside the
collector (`:1037`, `:1075`) on a *conflating* flow. Serialising them couples a slow
Overpass mirror to the section integrator (`:1208`) and the chime (`:1163`) — a coupling
that does not exist today and that no test in this project can see. 05 correctly marks
`FixRouter` as step 8, optional, and last (`05:679-681`). It should stay there.

**2. Full MVI (05 variant A).** A single `MutableStateFlow<MapState>` collected at the top
of the composable invalidates the whole screen on every GPS fix, over a GL surface with a
fog view riding the camera-move callback (`:945-946`). The mitigations exist but must all
be got right, and getting them wrong is a frame-rate regression on the app's only
performance-sensitive screen. Proposal 05 rejects it too (`05:822-832`); I am recording
the refusal so it does not come back as "we may as well finish it".

**3. 03 step 8 as written** — `spin()` on `viewModelScope` behind a `Channel(BUFFERED)`
effect channel. Not because moving the scope is wrong (it is a defensible decision,
`03:685-691`), but because the *combination* means a `FitCamera` and a `Haptic` emitted
while the user is on another screen are replayed when they come back. The camera jumps to
a spin result from minutes ago. It is silent, it is not in the proposal's list, and the
fix is a design decision (drop, not buffer) that must be made before the code is written.

**4. Unify `myLocation`'s two writers.** `:555-559` accepts a fix only at
`accuracyMeters <= 100f`; `fetchLocation` at `:724` writes whatever FusedLocationProvider
returns, including `lastLocation` (`:722`), ungated. Inventory H10. Eleven readers,
including `spin()` (`:1393`), `startNavigation()` (`:983`) and the fog corridor (`:930`).
Whichever gate you pick, you change what all eleven see. If it is worth changing, change
it in a commit that does nothing else and A/B it on a replay.

**5. Unify the park-and-grace pair.** `camSuspended = true; lastGestureMs = now` appears at
`:810`/`:813`, `:837`/`:838`, `:1677`/`:1679`, `:1829`/`:1830` — and `spin()` at `:1403`
sets **only** `camSuspended`. Inventory H8 calls it "either a latent bug or deliberate;
either way a refactor that 'unifies' them changes behaviour". Proposal 05's own reducer
sketch unifies it (`05:537-538`). Refuse it inside any refactor commit. Decide it
separately, on replay route (iv), with the before/after recorded.

**6. "Fix" `error` (`:460`) during a move.** Twelve writers, one reader at `:1771`.
Permission failures (`:778`) and navigation failures (`:984`, `:991`, `:998`, `:1011`) are
invisible whenever the dock is collapsed. That is a real defect and it should be fixed —
in its own change, with its own design, not as a side effect of someone deciding an
extracted class "should probably surface errors".

**7. Add `navProgress` to the key list at `:1236`, or key `:700` on the collections.**
Both are one-word diffs that look like corrections. `:1243` reads `navProgress` on purpose
without keying on it; `:700` keys on `candidates.isEmpty()` and `spinOffer == null` on
purpose. Inventory H3.

**8. Land any step that touches a `lastFix` consumer without the corresponding replay
A/B.** Including — especially — steps whose author rates them low risk. Risk 4.2 (the
hysteresis retuning hidden inside a stall removal) is the model: the safest-looking change
in the whole set.

**9. Spread phase 1 across sprints alongside feature work.** Fourteen cut-and-paste
commits on one file, conflicting with every in-flight branch that touches the map, and
cut-and-paste conflicts resolve badly and silently. Either land it in a week or do not
start it.

**10. Reformat inside a move commit.** `git blame -C` is the only remaining record of why
`:232-235`, `:290-299`, `:842-857` and `:1265-1269` say what they say, and those comments
are worth more than the code they sit above.

**11. Accept "MapScreen.kt is now N lines" as the success criterion.** All five proposals
warn about this in their own words (01's con #8, 03's "steps 1-4 already deliver most of
that", 04's "anyone measuring success by 'MapScreen is now a reasonable size' will be
disappointed", 05's "step 2 does the size work; MVI does the correctness work"). Five
independent agents flagging the same misreading is not a coincidence — it is what they all
expect to happen.
