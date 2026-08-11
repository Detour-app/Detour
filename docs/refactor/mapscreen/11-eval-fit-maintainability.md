# Codebase-fit evaluation of the five MapScreen split proposals

Judged on one question: **which of these leaves *this* repo — five surfaces, two
CI workflows, five test files, one maintainer's comment discipline — better off
in two years?** Not which is architecturally correct in the abstract.

Everything below was checked against the code and the build config, not against
the proposals' own claims. Where a proposal's self-assessment is right I say so;
where the repo contradicts it I say that too.

---

## The existing house style, stated explicitly (with citations)

### 1. State ownership: composition-local `var`, over process-scoped `object` + `StateFlow`. Nothing else.

There are **zero** ViewModels, zero `UiState` types, zero sealed state
hierarchies, zero DI containers and zero state-holder classes in `app/`,
`wear/` or `shared/`. Verified by grep across all three source sets.

Every screen is the same shape:

- `CirclesScreen.kt:95-107` — `rememberCoroutineScope`, `LocalContext.current`,
  one `collectAsStateWithLifecycle` off `Account.username`, then six
  `var … by remember { mutableStateOf(…) }`.
- `SettingsScreen.kt:121-132` — `var page by remember` plus twenty-one
  `collectAsStateWithLifecycle` reads off `Settings`.
- `FriendsScreen.kt:84`, `RoutesScreen.kt:142`, `HistoryScreen.kt:173`,
  `TripDetailScreen.kt:211`, `RouteEditorScreen.kt:91` — identical.

Persistent state lives in `object` singletons exposing `StateFlow`, in
`:shared`: `Settings.kt:14`, `TraceStore.kt:25`, `SavedPlaces.kt:22`,
`Groups.kt:29`, `Account` (`Social.kt:31`), `RouteStore` (`Routes.kt:104`), and
about thirty more. `TripTrackingService` and `ConvoyLiveClient` are the two
Android-side equivalents. **The singleton layer is the app's real architecture**,
and it is the only layer that a foreground service, an `androidx.car.app.Session`
and a Compose screen can all reach in the same process.

`MapScreen.kt` is not an outlier in *kind* — it is `CirclesScreen.kt:98-107`
scaled 60×. That matters: the file is big because a screen grew, not because
someone chose a bad pattern.

### 2. File granularity: one public screen per file; private sections in the same file; cross-screen primitives in tiny sibling files.

The `ui/` package is 19 files. Two kinds:

- **Screen files.** One `public @Composable fun XxxScreen(…)` at the top, then
  every helper `private` in the same file. `SettingsScreen.kt` is the reference
  case for a *large* screen: 1,199 lines, one public composable, fourteen
  `private fun *Section(…)` composables (`SettingsScreen.kt:230`, `:258`,
  `:293`, `:384`, `:449`, `:524`, `:553`, `:628`, `:686`, `:750`, `:822`,
  `:879`, `:1023`, `:1114`) dispatched from a `when (page)` at
  `SettingsScreen.kt:153-224`. **A 1,199-line screen file with fourteen private
  sections is already the sanctioned house answer to "this screen got big."**
- **Primitive files.** `Format.kt` (46), `GlassSurface.kt`, `AppBar.kt`,
  `Theme.kt`, `GraphiteTheme.kt` (88), `Navigation.kt` (273),
  `MapLibreMap.kt` (764). These exist because two or more screens need them.
  `Navigation.kt:75`/`:125`/`:156`/`:253` are four public composables consumed
  by `MapScreen.kt` and nothing else yet; `MapLibreMap.kt:113`/`:467`/`:481` are
  consumed by `MapScreen.kt` *and* `car/CarMapRenderer.kt:27-29`.

So the repo already has, and already validates, the "cut a topic out into a
same-package sibling file" move. It is not a new idea here.

### 3. Naming and visibility: `private` by default, `internal` only when a sibling or a test needs it — and that trick is already used for testing.

`HistoryScreen.kt:71` declares `internal data class TraceSegment` and
`HistoryScreen.kt:120` declares `internal fun matchTripPoints(…)`, purely so
`app/src/test/java/com/jellemax/detour/ui/TripTraceMatchingTest.kt` can reach
them. That test's own KDoc says why: *"No Android APIs involved, so no
emulator/Robolectric needed."* Any proposal that promotes `private` → `internal`
to enable a test is repeating an established move, not inventing one.

### 4. Comment style: rationale, mandated in writing, with a preservation clause.

`CONTRIBUTING.md:154-166`:

> Comments in this repo explain **why**, not what … This is a deliberate house
> style, not incidental … When you touch existing code, keep the comments that
> are still true — don't delete or "clean up" a why-comment just because the
> line next to it changed, unless the reasoning itself is now wrong.
>
> Beyond that: match whatever the surrounding file already does (naming,
> structure, how state is held) rather than introducing a new pattern for one
> change.

Those are two written directives, and the second one is aimed squarely at this
exercise. It is the reason "fit" is not a soft criterion in this repo — it is a
documented rule with a stated rationale.

The comments are not decorative. Sampling MapScreen.kt:

- `:232-288` — six constant blocks, each with the physical reasoning
  (`:240-247` explains why the speedometer eases per frame and not per fix;
  `:262-267` explains why `CURVY_CANDIDATES` is 3 and where the gain flattens;
  `:270-274` explains what the quiet period buys you at 80 km/h).
- `:290-299` — `sectionExitGate`'s KDoc **records a shipped bug**: matching a
  gate without a heading test "used to start a measurement as you left a
  section, which is what put an average on screen after the trajectcontrole
  instead of during it."
- `:842-857` — sixteen lines arguing why the group-spin vote is resolved by the
  sharer rather than tallied per device, ending: *"Splitting a convoy across two
  destinations is the exact failure this feature exists to prevent."*
- `:665-668` — why the camera park uses a raw `OnTouchListener` and not a
  camera-move listener.
- `:1018-1023`, `:1058-1061` — why speed limits and cameras are prefetched in a
  radius and snapped locally rather than queried per fix.
- `:1232-1235` — why the per-fix effect moves only *targets*: "the old code drove
  `animateTo()` from an effect keyed on `liveFix`, so every fix cancelled the
  previous 350 ms flight partway through and the map lurched."
- `:1265-1270`, `:1285-1288`, `:1311-1316` — why the frame loop is free with the
  screen off, and what the epsilon skip buys.

Roughly a quarter of the file is this. `MapScreen.kt`'s comments *are* the
design docs for behaviour that has no test.

### 5. Test posture: five test files, and — the part nobody checked — CI runs only three of them.

Complete inventory:

| File | Source set | Run by CI? |
|---|---|---|
| `shared/src/commonTest/.../GroupsTest.kt` (338) | commonTest | **yes**, `ios.yml:65,68` |
| `shared/src/commonTest/.../ParsingTest.kt` (286) | commonTest | **yes** |
| `shared/src/commonTest/.../RoutesTest.kt` (215) | commonTest | **yes** |
| `shared/src/androidUnitTest/.../RouteStoreLoadOrderTest.kt` | androidUnitTest | yes (JVM leg) |
| `app/src/test/.../PlaceNotificationsTest.kt` | app unit test | **no** |
| `app/src/test/.../TripTraceMatchingTest.kt` (88) | app unit test | **no** |

`.github/workflows/build.yml` triggers on every push and PR and runs
`./gradlew :app:assembleRelease :app:bundleRelease …` (`build.yml:123`) — it
never invokes `:app:testDebugUnitTest`. `.github/workflows/ios.yml` runs
`:shared:testDebugUnitTest` and `:shared:iosSimulatorArm64Test`
(`ios.yml:65,68`) but is path-gated to `shared/**` and `iosApp/**`
(`ios.yml:11-19`). `CONTRIBUTING.md:139-147` describes exactly this and does not
mention `app/src/test` at all.

**Consequence, and it reorders several arguments below: a test placed in
`app/src/test` is documentation, not a gate. A test placed in
`shared/src/commonTest` is a gate, on two runtimes.** Dependencies confirm the
rest: `app/build.gradle.kts:163` is `junit:junit:4.13.2` and nothing else — no
Robolectric, no `compose-ui-test`, no instrumented tests anywhere.

### 6. The unit of work is a cross-surface feature, not a file.

`MapScreen.kt` has **14 commits** in its entire history. Of the eight most recent:

| Commit | Files | Also touched |
|---|---|---|
| `2b2540a` feat(circles) | 30 | `shared/data`, `iosApp/`, `net`, `tracking`, docs |
| `edf2fd2` feat(map): pick your own marker | 14 | `shared/data`, `car/`, drawables |
| `6817aa1` feat(circles): draw every circle | 3 | `ui/` only |
| `8f59d61` feat(car): peers + circle members | 8 | `shared/data`, `car/` |
| `0265d20` feat(convoy): group spin | 12 | `shared/data`, `net`, `convoy`, `server/sync` |
| `d1f4375` feat(map): fade the driven route | 15 | `shared/data`, `car/`, `iosApp/`, README |
| `6a69bc5` feat(circles): arrival notifications | 21 | `shared/data`, `net`, `tracking`, `notif`, `iosApp/` |
| `07fe490` fix(map): layers button | 1 | `ui/` only |

Six of eight also touched `shared/…/data`; four also touched `car/`; three also
touched `iosApp/`. And `3038aae feat(ios): roll three candidates, and vote on a
convoy's` (497 lines) is the *iOS port of a phone feature*, landed separately.
**The typical map change is not a MapScreen change. It is a shared/data change
plus a MapScreen change plus a car and/or iOS change.**

`CONTRIBUTING.md:31-32` states the governing rule: *"New logic goes in `shared/`
unless it genuinely cannot — a change that lands only in `app/` silently makes
iOS diverge."* `MapScreen.kt` currently holds ~900 lines of logic that landed
only in `app/`. It is the largest standing violation of the repo's own rule.

### 7. Design-doc workflow: specs cite `MapScreen.kt:NNNN` by line.

`docs/superpowers/specs/` holds three specs dated 2026-08-11, one per recent
change. `2026-08-11-map-layers-panel-toggle-design.md:18,22,26,38,40,42,46,76,78,106`
cites `MapScreen.kt:2731`, `:2708`, `:2723`, `:2256`, `:3082`, `:2848`. This is
how changes get designed here. It has consequences (hazard F-A below).

---

## Scoring rubric

Seven dimensions, weighted for this repo specifically. Scores 1–5.

| # | Dimension | Weight | Why this weight *here* |
|---|---|---:|---|
| D1 | **Multi-surface leverage** | 25% | `CONTRIBUTING.md:31-32` and `docs/IOS_PORT.md:17-21` make "logic goes to `:shared`" a written rule with a named failure mode. Six of the last eight MapScreen commits also touched `shared/data`; four touched `car/`; three touched `iosApp/`. This is the only dimension the repo's own docs treat as non-negotiable, and the only one where a wrong choice compounds across four other surfaces. Highest weight. |
| D2 | **House-style conformance** | 20% | `CONTRIBUTING.md:164-166` is a written instruction not to introduce a new pattern for one change. Violating it is violating a documented convention, not offending taste. |
| D3 | **Regression risk under this repo's actual verification** | 15% | Five test files, three of them CI-gated and all three in `:shared`; no Robolectric (`app/build.gradle.kts:163`); no instrumented tests. Behaviour in this file is verified by riding a motorbike. Risk is not theoretical and cannot be bought down with test infrastructure that does not exist. |
| D4 | **Comment preservation** | 15% | `CONTRIBUTING.md:154-162`, plus the fact that ~25% of `MapScreen.kt` is rationale that substitutes for the tests the repo does not have. Orphaning a comment here destroys the only record of a decision. |
| D5 | **Longevity against the actual roadmap** | 10% | Judged against `FUTURE.md:26-28`, `docs/IOS_PORT.md:85-102`, `docs/ANDROID_AUTO.md:83-89` — not hypotheticals. |
| D6 | **Onboarding & review** | 10% | Real but recoverable; a contributor can grep. Weighted below correctness. |
| D7 | **Consistency cost / rollout realism** | 5% | Low weight *because* it is nearly binary — see hazard F-H: for three of the five proposals the honest rollout answer is "never," which the score captures without needing much weight. |

Weights sum to 100. Two dimensions the proposals argue about a lot are
deliberately **absent**: raw line count (the repo already tolerates 1,333- and
1,199-line files: `TripTrackingService.kt`, `SettingsScreen.kt`) and
recomposition performance (all five agree the real costs are the GL redraw at
`MapScreen.kt:945-946` and the GeoJSON re-serialisation in
`MapOverlays.render`, and that none of them change either).

---

## Per-proposal assessment

### Proposal 1 — Mechanical file split

**D1 Multi-surface leverage — 1/5.** The proposal's own verdict is correct and
admirably blunt: *"No benefit whatsoever, and this is the proposal's clearest
structural failure"* (`01:714`). It deduplicates `smoothBearing` and six camera
constants against `car/CarMapRenderer.kt:53-69,470-475` and leaves the five
substantive duplications standing: the ambient-limit snap
(`MapScreen.kt:1024-1056` ≈ `car/SpinScreen.kt:265-296`), the camera warning
(`:1145-1167` ≈ `car/NavScreen.kt:396-417`), the camera prefetch
(`:1062-1083` ≈ `car/NavScreen.kt:378-392`), the arrival/reroute policy
(`:1345-1390` ≈ `car/NavScreen.kt:242-277`, whose own comment says *"Same
arrival/reroute policy as MapScreen.kt's navigating LaunchedEffect"*), and the
circle poll (`:1106-1119` ≈ `car/CarMapRenderer.kt:139-150`). Nothing reaches
`iosApp/` — `ui/` is not on any iOS path. Nothing reaches `wear/`.

**D2 House style — 5/5.** This is not "close to" the house style; it *is* the
house style, twice over. Same-package sibling files: `Navigation.kt`,
`MapLibreMap.kt`, `Format.kt`, `GlassSurface.kt`, `AppBar.kt`, `Theme.kt`.
`private` → `internal` for a test: `HistoryScreen.kt:71,120` plus
`app/src/test/.../TripTraceMatchingTest.kt`. Zero new concepts. `01:57-60`
commits explicitly to keeping every `remember`, every `by` delegate and every
local function.

**D3 Regression risk — 5/5 for phases 1–2, 3/5 for phase 3.** Phases 1–2 are
provable by inspection: bytes moved, one keyword changed, zero cross-file diff
(`RoutesScreen.kt:202`, `:303`, `HistoryScreen.kt:317`, `RouteEditorScreen.kt:386`,
`MainActivity.kt:223` all resolve unqualified in the same package). On a file
containing a per-frame loop, a 1 Hz GPS pipeline and a distributed consensus
rule, with no CI-gated test in `app/` at all, that guarantee is worth more here
than anywhere else. Phase 3 is a different animal, and `01:634-638` names the
reason honestly: three extractions (`669-694`, `940-968`, the
`LaunchedEffect(Unit)` collectors) depend on `rememberUpdatedState` back-refs
(`:937-939`, `:1138-1140`, `:1173`, `:1250`) and "can be shipped broken and pass
every check the repo has." Given hazard F-D, that is literally true.

**D4 Comment preservation — 5/5. Best of the five, and it isn't close.** Rule 5
(`01:66-73`) makes verbatim comment migration a stated constraint, and the
mechanics support it: every move is a contiguous block, so `MapScreen.kt:232-288`
travels with its six rationale paragraphs into `MapCameraTuning.kt`;
`:290-299`'s shipped-bug KDoc travels with `sectionExitGate`; `:316-320`,
`:322-328`, `:345-346`, `:357-360`, `:369-375`, `:387-399` travel with
`SpinShare.kt`. The one comment it proposes to *rewrite* rather than move
(`:374-375`) is the one the inventory proves is already stale — RoutesScreen
never touches the holder, only `seedRouteNavigation`. That is exactly the
`CONTRIBUTING.md:158-162` carve-out ("unless the reasoning itself is now wrong")
applied correctly.

**D5 Longevity — 2/5.** Test it against the real roadmap. `FUTURE.md:27` — voice
guidance on the phone: `car/NavVoice.kt` exists and `car/NavScreen.kt:287-313`
has the three-phase announce; the phone version lands in the nav loop at
`MapScreen.kt:1345-1390`, which this proposal explicitly does not touch
(`01:301-318` — the eight closures stay). `FUTURE.md:28` — GPX import: lands in
`shared/…/data/RouteGpx.kt` and `RoutesScreen.kt`, unaffected either way.
`FUTURE.md:26` — min-distance refinement: `minRadiusKm` (`:450`) and the slider
at `:1764-1765` stay in a 1,565-line composable. `docs/IOS_PORT.md:85-89` —
watchOS: needs nav-session state reachable outside a composable; unaffected.
Two of three plausible next features land right back in the part this proposal
declines to move. `01:645-648` predicts this failure mode itself.

**D6 Onboarding & review — 3/5.** Excellent for UI questions ("where is the spin
sheet laid out" → `SpinCards.kt`, 310 lines). Poor for the three questions
posed: after phases 1–2 the speed-camera chime is still at `MapScreen.kt:1145`,
the follow-park rule still at `:669-712`, and the convoy vote is *split* —
`leadingSpinIndex` in `SpinShare.kt`, the resolution effect still at `:858-870`,
the vote dispatch still at `:965` and `:1716`. Phase 3 fixes the first two and
`01:329-336` declines the third. Review-diff quality genuinely improves for the
easy changes and, as `01:758-761` concedes, not at all for the risky ones.

**D7 Consistency cost — 5/5.** Zero. It creates no second architecture. The
rollout question is trivially answerable: `SettingsScreen.kt`'s fourteen
sections are already this pattern applied in-file (`SettingsScreen.kt:229-1114`);
promoting them to sibling files is the same commit shape.

**Weighted: 3.50.**

---

### Proposal 2 — Compose state holders

**D1 Multi-surface leverage — 1/5, with a small negative.** `02:842-843` calls
this "the weakest part of this proposal" and it is right for a structural reason:
`remember` requires a composition, so `car/CarMapRenderer.kt` (a
`SurfaceCallback` renderer) and `car/SpinScreen.kt` (an `androidx.car.app.Screen`)
can never call `rememberMapCameraState`. `withFrameNanos` is unavailable on the
car by design — `docs/ANDROID_AUTO.md:113-116` explains that the `VirtualDisplay`
keeps rendering with the phone screen off and vsync callbacks do not fire, which
is why `car/CarMapRenderer.kt:61` uses a 33 ms timer. And `mutableStateOf` is
meaningless to iOS. `02:869-871` notes the negative honestly: after the split the
phone's ambient-limit copy lives in `ui/MapRoadHazardState.kt` and the car's in
`car/SpinScreen.kt`, one directory further apart than today. Drift gets *easier*.

**D2 House style — 2/5.** Closer than 3 or 5, further than 1. In its favour: no
dependency, no DI, plain Kotlin classes — `02:691-693` is right that
`class MapCameraState` is the same *kind* of object as `object Settings`. Against
it: the repo's classes are process-scoped singletons that every surface reaches;
these are composition-scoped holders that only one surface can reach, and
`@Stable`, `listSaver` and the `remember`-factory idiom appear nowhere in the
tree. Eight new classes plus eight factory functions plus a saver is a
convention, and `02:756-758` concedes nothing enforces it.

**D3 Regression risk — 4/5.** The strongest safety claim of the ambitious
proposals, and it holds: `rememberCoroutineScope()` has exactly the lifetime the
32 existing `LaunchedEffect`s have (`02:155-161`), and the design keeps the
effect *keys* in the factory rather than moving restart logic into the class
(`02:146-153`) — which correctly preserves the load-bearing re-key semantics at
`MapScreen.kt:700`, `:1024`, `:1271`. Deductions: the `rememberSaveable`
regression (`02:720-726`) is silent and `MainActivity` has no `configChanges`, so
it is live; and the constructor stale-capture footgun (`02:759-762`) compiles
fine when wrong.

**D4 Comment preservation — 3/5.** Mixed, and the failure mode is specific. Most
comments travel with their code. But this proposal reorganises by *concern*, and
several of MapScreen's best comments are about **adjacency**, which concern-based
gathering destroys:

- `MapScreen.kt:494-496` — *"Moved up from the marker-drawing section below: the
  group-spin commit rule (see `commitSpinCandidate`) also needs to know who's
  currently live, not just the map overlay."* Once `convoyPeers` is a parameter
  of `rememberMapConvoySpinState`, that sentence describes a file layout that no
  longer exists. Deleting it loses the *reason*; keeping it is confusing.
- `MapScreen.kt:1091-1093` — *"same reasoning as the camera markers above"* — a
  back-reference that breaks when the two effects land in different files
  (`MapSurfaceState.kt` vs `MapConvoySpinState.kt`).
- `MapScreen.kt:1247-1250` — *"Keyed on nothing: it runs for as long as the map is
  composed"* — still true, but now one indirection from the loop it describes.

Roughly five to eight comments become adjacency-orphans. `02:884-886` notices the
`git log -C` risk but not the comment-semantics risk.

**D5 Longevity — 3/5.** `02:708-710` is the accurate framing: it creates the
seams a later extraction needs. But the seams are Compose-shaped, so when the
next feature has to exist on the car (as four of the last eight map features
did) the holder is not the reusable thing — `02:862-867` says so: *"It makes the
extraction obvious; it does not perform it. That is step 10, and it is honestly a
different proposal wearing this one as a prerequisite."* Correct, and it is
proposal 4.

**D6 Onboarding & review — 4/5. The best of the five on the three posed
questions.** "Speed camera chime" → `MapRoadHazardState.kt` (255). "Map stops
following when I pan" → `MapCameraState.kt` (290). "Convoy vote" →
`MapConvoySpinState.kt` (145). Three questions, three file names, no grep. That
is a genuine, durable improvement and the strongest thing this proposal has.

**D7 Consistency cost — 2/5.** The rollout answer is *no*, and for a reason the
proposal does not reach: `SettingsScreen.kt` has almost nothing to hold. Its
state is `var page` (`:122`) plus twenty-one `Settings` flow reads (`:127-132`),
and its sections write `Settings` directly (`SettingsScreen.kt:236`). A
`SettingsScreen` state holder would be pure pass-through. Same for
`FriendsScreen.kt:84` and `CirclesScreen.kt:95-107`. So the pattern lands on one
screen permanently. See hazard F-H.

**Weighted: 2.50.**

---

### Proposal 3 — ViewModel + UiState

**D1 Multi-surface leverage — 1/5, and it is the only proposal that scores
*negative* here in substance.** `03:778-795` states the problem precisely and
then proceeds anyway: `androidx.car.app.Session` (`car/DetourCarSession.kt:11`) is
a `LifecycleOwner`, not a `ViewModelStoreOwner`, and runs in the same process
(no `android:process` in the manifest). `TripTrackingService`, `wear/NavRelay.kt`
and `ble/BleNavServer.kt` have the same problem. Today all of them reach app
state through singletons — `car/SpinScreen.kt:131` reads
`TripTrackingService.lastFix`, `:137` reads `Settings.defaultZoom`,
`car/DetourCarSession.kt:27` calls `Settings.init()`. **Any state that moves from
a singleton into a ViewModel becomes unreachable from four surfaces
simultaneously.** The proposal's own mitigation — leave the nineteen singleton
flows in the composable (`03:205-214`) — is what produces its acknowledged
pass-through problem (`03:756-772`). The two are the same defect seen from two
sides, as `03:793-795` says. And nothing can go to `:shared`: a ViewModel is
`androidx.lifecycle`, Android-only, so `iosApp/` gains nothing, ever.

**D2 House style — 1/5.** `03:319-324` names it: *"this is the project's first
architectural framework dependency in the UI layer, and it establishes a
convention that the other 18 screens will be expected to follow … Adopting it for
one screen and not the rest is worse than either extreme."* Against
`CONTRIBUTING.md:164-166` this is the maximum possible violation: a new pattern,
for one change, plus two new dependencies (`03:295-296`) and a third for tests
(`03:297`).

**D3 Regression risk — 2/5.** The lifetime change is not a side effect, it is the
point (`03:216-229`: Activity-scoped, surviving the Hub round-trip), and
`03:807-816` lists the consequences: `spin()` on `viewModelScope` outlives
navigating away; a stale `error` string (`:460`) survives the round-trip; eight
`withContext(Dispatchers.IO)` sites (`:570`, `:583`, `:1005`, `:1037`, `:1075`,
`:1113`, `:1379`, `:1407`) must survive the move or an Overpass call lands on the
main thread. Plus `03:817-822`: *"This plan must be finished or reverted, not
parked."* In a repo where the only verification is riding, an eleven-step
all-or-nothing migration is the worst available risk shape.

**D4 Comment preservation — 2/5. Worst of the five.** The UiState/event model
does not move code, it *rewrites* it. `03:802-803`: ~24 `MapEvent` types replace
~24 lambdas that read `onRadiusChange = { radiusKm = it }` (`:1763`).
`03:799-801`: ten `MapEffect` types replace ten one-line `Context` calls — the
proposal's own example is `TripTrackingService.stop(context)` at `:1636`
becoming "an event → a `when` branch in the VM → a channel send → a `when`
branch in the composable → the same call. Four hops for one line." A comment
attached to a one-line call has no obvious home across four hops.

Specifically at risk: `MapScreen.kt:1232-1235` (why the fix effect only moves
targets) attaches to a `LaunchedEffect` that becomes a VM method plus an effect
hop; `:1367-1369` (why the reroute uses `scope.launch` and not the effect scope)
describes a coroutine-scope choice that `viewModelScope` erases;
`:606-611` (the belt-and-braces `PushToTalk.stopTalking()` on `ON_STOP`, because
"a stuck-open mic is the worst failure mode here") attaches to a
`DisposableEffect` that `03:239-240` keeps in the composable while its trigger
logic moves. `CONTRIBUTING.md:158-162` says keep comments "unless the reasoning
itself is now wrong" — after an event/effect rewrite you frequently cannot tell,
which is the worst state for a preservation rule to be in.

**D5 Longevity — 3/5.** It has the single best *diagnosis* in the whole set:
concern 12, the Hub round-trip (`03:709-716`) — every time the user opens the Hub
and comes back, the trajectcontrole average resets, the Overpass camera prefetch
re-fires, `SyncClient.sync()` re-runs, the permission sweep re-runs, and
`radiusKm`/`poiKind`/`directionDeg` revert. No other proposal names this, and it
is a real user-visible bug. But the prescription is aimed away from the roadmap:
`FUTURE.md:27` (phone voice guidance) and `docs/IOS_PORT.md` parity both need
logic *out* of `app/`, and this moves it further in.

**D6 Onboarding & review — 3/5.** Split verdict, and the question as posed is
about "a future contributor" *to this repo*. An Android generalist arriving cold
scores this 5. Someone who has read `CirclesScreen.kt`, `SettingsScreen.kt`,
`FriendsScreen.kt`, `RoutesScreen.kt` and `HistoryScreen.kt` — all identical, all
`remember` + singleton — scores it 2, because they now have to learn that one
screen out of nineteen is different. `03:938-941` concedes steps 6–11 are "the
hardest kind of review."

**D7 Consistency cost — 1/5.** Worst possible. Not merely "two architectures" but
*permanently* two, because — per D7 on proposal 2 — the other big screens have no
screen state for a ViewModel to own. A `SettingsViewModel` over
`SettingsScreen.kt:127-132`'s twenty-one `Settings` flows would be exactly the
pass-through anti-pattern `03:756-767` names. So the realistic rollout is one
screen, forever, in a codebase whose written rule (`CONTRIBUTING.md:164-166`) is
"don't introduce a new pattern for one change."

**Weighted: 1.70.**

---

### Proposal 4 — Domain-service extraction into `:shared`

**D1 Multi-surface leverage — 5/5. The only proposal that scores here at all.**

It is the direct application of `CONTRIBUTING.md:31-32` and
`docs/IOS_PORT.md:17-21` to the largest standing violation of that rule. Receipts,
all verified:

- **`car/` deletions**, ~155 lines: `CarMapRenderer.kt:53-69,398-415,468-475`
  (camera tuning + ease + `smoothBearing`), `:74-78,139-151` (circle poll, whose
  comment at `:77` already says *"see MapScreen's `CIRCLE_FIX_POLL_MS`"*),
  `NavScreen.kt:131-137,378-392,396-410,242-252`, `SpinScreen.kt:93-99,264-295`.
- **`car/` capability gains** (`04:901-917`), which is the sharper argument:
  `SectionAverageTracker` does not exist on the car at all — `car/NavScreen.kt:391`
  reads `result.cameras` from `SpeedCameras.near` and *discards*
  `result.sections`. The car is not missing trajectcontrole because nobody wanted
  it; it is missing it because the state machine is welded to a Compose function
  at `MapScreen.kt:1174-1230`.
- **`iosApp/` gains.** I verified the claim:
  `grep -rln "SpeedCamera|snapSpeedLimit|SectionAverage|FogView|trajectcontrole" iosApp/`
  returns nothing. The SwiftUI app ships without the ambient limit sign, camera
  warnings and trajectcontrole — the three features whose only implementation is
  inside MapScreen's composable body. The precedent is proven:
  `iosApp/Detour/SpinModel.swift:129` already calls
  `SpinPickerKt.pickThreeCandidates` out of `commonMain`.
- **`wear/`.** Not claimed by the proposal, but it follows: inventory §7.11 found
  that "wear and BLE guidance go dead whenever navigation is driven from the head
  unit," because `NavRelay.send`/`BleNavServer.send` live inside MapScreen's nav
  loop (`:1356-1357`) with the non-navigating half in a separately-keyed effect
  (`:1338-1342`). A `NavSession` outside the composition is the only structure in
  any of the five proposals that makes that fixable.

**D2 House style — 4/5.** `:shared` + `commonTest` is the repo's proven move, done
once already at scale (`abcea2b refactor(android): run the Android app on
:shared`, `bbc3704 Merge ios-port`), and `04:949-951` frames it correctly:
*"keep doing the thing that worked, not adopt an architecture."* Two real
departures cost it a point. First, `04:289-307` deliberately breaks the
`object` + `StateFlow` convention in favour of instantiable `class`es — the
reasoning (injected clock, two surfaces legitimately disagreeing) is sound but it
is a documented departure. Second, the load-bearing rule *"no service owns a
`CoroutineScope`"* (`04:341-343`) is a convention written down nowhere except the
proposal, and `04:1118-1121` admits it needs a package KDoc "or it will decay."
`ConvoyLiveClient.kt:311` is the counter-example already in the tree.

**D3 Regression risk — 2/5. The sharpest cost, and the proposal is honest about
it** (`04:965-995`). The four extracted machines are path-dependent, and their
entire working state is coroutine-local, not Compose state (inventory H4):
`center`/`lastFetchMs` at `:1063-1064`, `warnedAt` at `:1146`,
`active`/`exitGate`/`entryMs`/`accMeters`/`last` at `:1175-1179`,
`lat`/`lon`/`bearing`/`zoom`/`applied*` at `:1281-1293`. Correctness is a
property of the *sequence* of fixes. The section machine's exit rule
(`:1217-1220`) has three conditions plus a 150 m floor whose only purpose is to
stop the entry gate counting as the exit — and `:294-298` records that getting
this wrong shipped once already. With no CI-gated `app/` test, no Robolectric and
no instrumented tests, the only pre-merge verification is a drive.

The counterweight, which the weighting does not capture and I want to state
separately: **this is the only proposal whose end state is CI-gated tests on
those machines.** Per hazard F-D, `commonTest` runs on two runtimes on every
`shared/**` change; `app/src/test` runs never. So P4 converts a one-time
migration risk into permanent enforced coverage, while P1/P2/P3/P5's tests
convert it into permanent unenforced documentation.

**D4 Comment preservation — 4/5.** Good, structurally. Each extraction is a
contiguous block moving to a file named after it, so `:1018-1023` becomes
`SpeedLimitService.kt`'s KDoc, `:1134-1137` becomes `CameraWarner.kt`'s,
`:1169-1172` + `:290-299` become `SectionAverageTracker.kt`'s, and `:262-267`
(`CURVY_CANDIDATES`) travels into `SpinSession.kt`. A 145-line file whose header
comment is the paragraph that used to be buried at line 1169 is strictly better
than what exists.

Two deductions. (a) Comments that explain a *phone-specific* choice get
generalised into shared code and silently stop being true — the sharpest case is
`MapScreen.kt:1160`, `navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value`,
versus the car's progress-only rule at `car/NavScreen.kt:405`. Unifying those
into one `CameraWarner` is a user-visible behaviour change wearing a refactor's
clothes (hazard F-B). (b) Comments about the *Compose/MapLibre pairing* must stay
in `app/` while their subject leaves: `:895-899` ("Marker updates per fix; the
eased camera glides the map under it") explains a relationship between the
overlay effect and the frame loop that a `CameraEase` in `:shared` no longer
contains.

**D5 Longevity — 5/5. Best of the five, tested against the actual documents.**

- `FUTURE.md:27`, "Voice guidance — turn-by-turn is silent on both phone and car
  screen." The car already has `car/NavVoice.kt` (150) and the three-phase
  announce at `car/NavScreen.kt:287-313`, whose thresholds
  (`VOICE_FAR_M`/`VOICE_NEAR_M`/`VOICE_NOW_M`, `:64-66`) are the announce
  *decision*. With `NavPolicy`/`NavSession` in `:shared`, the phone consumes the
  same decision and only supplies the TTS; without it, the phone gets a third
  copy of the same rule alongside the existing two. This is the single clearest
  test of the five proposals and only P4 passes it.
- `FUTURE.md:26`, "Avoid destinations too close to start." Lands in
  `SpinSession`/`SpinPicker` in `:shared` — and `car/SpinScreen.kt:332` currently
  passes `0.0` for min radius because the feature is welded to the phone. P4 is
  the only proposal that makes the car and iOS inherit it.
- `docs/IOS_PORT.md:85-89`, watchOS app: *"Small, but nothing reuses from
  `wear/`."* Whatever an eventual watchOS app consumes has to be a
  Kotlin/Native-reachable nav session. P4 builds exactly that; the other four
  build Compose- or Android-only structures.
- `docs/ANDROID_AUTO.md:83-89`, the voice/`AudioAttributes` discussion, and
  `:91-102`, "Maneuvers throw" — both describe car-side *rendering* of decisions
  that P4 centralises.

**D6 Onboarding & review — 4/5.** "Speed camera chime" → `CameraWarner.kt`,
85 lines, with `CameraWarnerTest.kt` next to it (`04:777`). "Convoy vote" →
`GroupSpinRules.kt`, 110 lines, with a test (`04:832`). Both are dramatic
improvements. "Map stops following when I pan" is the weak one and P4 makes it
*worse*: the touch listener and its explanatory comment (`:665-694`) stay in
`app/` while the resume policy goes to `shared/…/drive/FollowCamera.kt`
(`04:382`) — one question, two modules (hazard F-E). `04:1109-1121` concedes the
UI half is untouched, so "where is the spin sheet laid out" still means scrolling
a 2,355-line file — which is precisely why P4 needs P1 in front of it.

**D7 Consistency cost — 5/5. Zero, and uniquely so.** The rollout question is
inverted: this is not a UI pattern, so it never has to be applied to
`SettingsScreen.kt` or `FriendsScreen.kt` — those screens have no drive-time
state machines. No second architecture is created anywhere. `shared/…/data/` is
38 files; `shared/…/drive/` is a sibling package of the same kind.

**Weighted: 4.10.**

---

### Proposal 5 — MVI / reducer (assessed as variant B, which is what it recommends)

Variant A is dismissed by its own author (`05:989-998`) and I agree; scoring it
would be scoring a strawman. Variant B = three machines (`CameraAuthority`,
`SpinRound`, `SectionTracker`) plus `MapTuning.kt`, in
`app/…/map/`, after a composable split.

**D1 Multi-surface leverage — 3/5.** Better than 1, 2 and 3; deliberately one
module short of 4. `05:346-347`: *"All in `:app`, which is what makes `car/`
reuse free."* True — `car/` is the same Gradle module — and step 1 deletes
`CarMapRenderer.kt:53-69,470-475` and `NavScreen.kt:397-415` (`05:900-908`). But
`05:910-914` concedes the reuse is narrower than it looks: `CameraAuthority`
(SM1) is *not* reusable by the car, because there is no touch input on an
Android Auto surface, and `SpinRound` (SM3) is not either, because
`car/SpinScreen.kt` has no convoy vote. So of the three machines, one and a half
reach `car/`. And `05:916-920` rules out iOS: `:shared` would require promoting
`Fix` (`tracking/TripTrackingService.kt:93-100`) first, and
`iosApp/Detour/SpinModel.swift` is a 146-line hand-written Swift duplicate of
SM2 that would have to be rewritten. Choosing `app/` over `:shared` for machines
that are entirely Android-free is the one decision in this proposal I think is
plainly wrong for this repo — see the convergence section.

**D2 House style — 2/5.** `05:856-866` concedes it directly, citing
`CirclesScreen.kt:98-107`, `FriendsScreen.kt:387-392`,
`SettingsScreen.kt:122-132`: *"A contributor who has read the rest of this
codebase will meet a genuinely foreign pattern."* Variant B earns 2 rather than 1
because three small classes named after things (`CameraAuthority.kt`,
`SpinRound.kt`, `SectionTracker.kt`) are much less alien than a `Store`/`Reducer`
framework, and because `iosApp/Detour/SpinModel.swift:17-24` already models the
spin as an enum state machine — the *product* is not innocent of the idea, only
the Kotlin.

**D3 Regression risk — 3/5.** Targeted, so most of the file is untouched; and
`05:319-325` gets the single most important call right (see convergence #8) by
keeping both frame loops entirely outside the reducer. Deduction: step 6
(`SpinRound`) touches the convoy vote, and `05:868-872` names the stakes
correctly — a regression there splits a convoy across two destinations, which is
the exact failure `MapScreen.kt:856-857` says the feature exists to prevent, and
is testable only with two devices and a running server.

**D4 Comment preservation — 3/5.** Genuinely the *best* answer for two specific
comments and a poor one for a different class. Best: `MapScreen.kt:842-857`'s
sixteen-line consensus argument and `:294-298`'s shipped-bug record become a
class KDoc **plus a test that enforces the claim** — `05:698-782` sketches
`SpinRoundTest` and `SectionTrackerTest`. Turning a prose correctness argument
into CI is the highest form of comment preservation available. Poor: the
comments that describe *Compose effect-key mechanics* have no home in a reducer,
because the reducer deletes the mechanism. `:696-699` (why the resume effect keys
on `candidates.isEmpty()` and `spinOffer == null` rather than the collections),
`:1232-1235` (why the fix effect moves only targets), and inventory H3's
deliberate stale read at `:1243` all describe things a reducer does not have.
Their reasoning is not wrong — the mechanism is simply gone, which
`CONTRIBUTING.md:158-162` does not cover.

**D5 Longevity — 3/5.** Good for "why did the camera/spin/section do that,"
which is a real recurring class of question here. Neutral on the roadmap: none
of `FUTURE.md:26-28` is served, and `docs/IOS_PORT.md` parity is explicitly
deferred (`05:916-920`).

**D6 Onboarding & review — 3/5.** `05:933-943` is the fairest self-assessment in
the whole set: variant B "hands them three files of 95-190 lines, each named
after a thing they already understand … each with a test file next to it that
reads like documentation. That is a materially easier ask." True. Against it,
`05:928-930`: adding one field to a machine becomes a multi-file diff, and
`05:816-819` prices the ongoing tax — "adding 'remember the last spin's POI kind'
is one `var` plus one write" today. On a hobby-scale, effectively
single-maintainer repo, that friction is charged on every future feature.

**D7 Consistency cost — 2/5.** Same structural answer as P2 and P3: it would
never be rolled out to `SettingsScreen.kt` or `FriendsScreen.kt`, and
`05:630-631` says so outright. Scores 2 rather than 1 because the blast radius is
three small files rather than a screen-wide rewrite, so the inconsistency is
contained and cheap to delete later.

**Weighted: 2.75.**

---

## Ranked table

| Rank | Proposal | D1 Surfaces (25) | D2 House style (20) | D3 Risk (15) | D4 Comments (15) | D5 Longevity (10) | D6 Onboarding (10) | D7 Consistency (5) | **Score** |
|---:|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|---:|
| 1 | **04 — Domain services → `:shared`** | 5 | 4 | 2 | 4 | 5 | 4 | 5 | **4.10** |
| 2 | **01 — Mechanical file split** | 1 | 5 | 5 | 5 | 2 | 3 | 5 | **3.50** |
| 3 | **05 — MVI reducer (variant B)** | 3 | 2 | 3 | 3 | 3 | 3 | 2 | **2.75** |
| 4 | **02 — Compose state holders** | 1 | 2 | 4 | 3 | 3 | 4 | 2 | **2.50** |
| 5 | **03 — ViewModel + UiState** | 1 | 1 | 2 | 2 | 3 | 3 | 1 | **1.70** |

Read the gaps, not the order. 04 and 01 are not competing — they score on
disjoint dimensions (04: 5/1/2 on surfaces/risk; 01: 1/5/5) and are the two
halves of one plan. 05 variant B is a subset of 04 at a worse address. 02 is 01
plus holders whose main benefit 04 delivers more thoroughly. 03 is the only one
that scores badly on a dimension *its own text* identifies as decisive.

---

## Convergence: recommendations appearing independently in two or more proposals

Twelve. My verdict on each.

**1. Move the presentational tail (`MapScreen.kt:1839-3193` plus the pill row at
`:2057-2121`) into same-package sibling files — five of five.**
`01:139-216`; `02` files 11–16; `03` files `MapChrome`/`MapSpinCards`/`MapDialogs`/…;
`04:689-718`; `05` step 2 (`ui/map/*`). **ENDORSE unreservedly.** No proposal
disputes it, all four ambitious ones require it, and it is the repo's own idiom.

**2. Every proposal independently concludes that the mechanical split should land
first and delivers most of the size win on its own — five of five.**
`01:829-835`, `02:951-958`, `03:979-982`, `04:1188-1191`, `05:1000-1003`. This is
the most striking agreement in the set: **three authors pitching a more ambitious
pattern each concede in their own verdict that the boring split should ship
first.** **ENDORSE.** It is the strongest single signal available and it should
determine sequencing.

**3. `val TravelMode.icon` gets its own file, staying `public` in package
`com.jellemax.detour.ui` — four of five** (`01`, `02` #16, `03:365-368`, `05`).
**ENDORSE.** `RoutesScreen.kt:303`, `HistoryScreen.kt:317` and
`RouteEditorScreen.kt:386` resolve it unqualified; any subpackage move breaks
three files for nothing.

**4. Keep every new UI file in package `com.jellemax.detour.ui` so cross-file
call sites need zero import churn — three of five** (`01:45-49`, `02:194-199`,
`03:333-340`; `05:346-347` dissents with `ui/map/` + `map/`). **ENDORSE the
majority.** Zero-diff at `RoutesScreen.kt:202`, `:303`, `HistoryScreen.kt:317`,
`RouteEditorScreen.kt:386`, `MainActivity.kt:223` is worth more than directory
tidiness, and it is what makes each move provable by inspection. `ui/` going
from 19 to ~32 files is a real cost (`02:752-755`) and a `Map*` prefix is a
weaker aid than a package — but it is the cheaper problem.

**5. Promote the six pure helpers to `internal` and unit-test them — five of
five.** `smoothBearing` (`:224-230`), `sectionExitGate` (`:300-314`),
`leadingSpinIndex` (`:361-367`), `GroupSpin.asRouteCandidates` (`:329-343`),
`List<RouteCandidate>.asSpinCandidates` (`:347-355`), `navAppUsableDirectly`
(`:2154-2164`). **ENDORSE the extraction; REJECT the placement all of them
propose.** Four of five put the tests in `app/src/test`. Per hazard F-D, CI never
runs that source set. These six functions are Android-free —
`RoadRoulette.distanceMeters`, `RoadRoulette.withinWedge`, `LatLon` and
`SpeedCameras.Section` are all `commonMain` — so they belong in `:shared` with
their tests in `commonTest`, where `ios.yml:65,68` runs them on two runtimes.

**6. Extract the trajectcontrole section machine (`:1174-1230` + `:290-314`) as a
plain, clock-injectable, testable class — four of five.** `02` step 10,
`03` `nav/SectionAverage.kt`, `04` `SectionAverageTracker`, `05` SM5
`SectionTracker`. Every one of the four cites the shipped bug at `:294-298`
independently. **ENDORSE — strongest convergence in the set, and the single
highest-value extraction available.** Four exit conditions (`:1217-1221`), an
integrator, a 150 m floor, all state coroutine-local and unobservable, verified
today only by driving through a trajectcontrole.

**7. Extract the camera-warning latch (`:1145-1167`) as a small two-state class
and delete `car/NavScreen.kt:397-415` — three of five** (`03`, `04`, `05:609-611`).
**ENDORSE.** Note `05:609-611` describes it as "MVI-shaped without being MVI,"
which is the same object `04` calls `CameraWarner` — the two proposals agree on
the thing and differ only on the address.

**8. Extract the ambient speed-limit snap with its 3-miss hysteresis
(`:1024-1056`) and delete `car/SpinScreen.kt:264-295` — three of five**
(`02`, `03`, `04`). **ENDORSE.** `car/SpinScreen.kt:48-51` already says in a
comment that it is "same policy as the phone map's (MapScreen.kt)" — a
copy documented as a copy is a defect with a note attached.

**9. Do NOT route the two per-frame loops (`:1251-1263`, `:1265-1333`) through
any new abstraction — three of five, reached independently** (`02:824-826`,
`03:885-894`, `05:319-325`). **ENDORSE emphatically.** These three proposals have
nothing else in common, and each concluded on its own that per-frame state
allocation over a GL surface with a fog view riding the camera-move callback
(`:945-946`) is unacceptable. When three otherwise-disagreeing designs carve out
the same 80 lines, that is a hard constraint, and any implementation should treat
it as one.

**10. Do NOT build one monolithic `MapUiState`/`MapState` — two of five**
(`03:885-894`, `05:821-832`), both from authors pitching state-centralisation.
`03` gives the two concrete reasons: `displaySpeedKmh` (`:550`) is written every
frame at `:1259`, and `camTarget`/`camTargetBearing`/`camTargetZoom` (`:547-549`)
are read only from inside a coroutine and today cost zero recompositions.
**ENDORSE.**

**11. Deduplicate the camera tuning constants and `smoothBearing` against
`car/CarMapRenderer.kt:53-69,470-475` — three of five** (`01` `MapCameraTuning.kt`,
`04` `CameraEase.kt` in `:shared`, `05` step 1 `map/MapTuning.kt`).
**ENDORSE the deduplication; take `04`'s address.** `01`'s and `05`'s placements
reach `car/` only; `04`'s also reaches `iosApp/`, and the constants are pure
numbers with no Android dependency.

**12. `SpinResultHolder`'s justification comment (`:374-375`) is stale — noted by
`01:133-136`, confirmed by the inventory (`RoutesScreen.kt` calls
`seedRouteNavigation`, never the holder); `03:370-372` proposes deleting the
holder entirely, `05:339-341` keeps it.** **ENDORSE fixing the comment; REJECT
deleting the holder.** It is the one mechanism in the file that survives activity
recreation (`:369-373`) and the only channel `RoutesScreen.kt:202` has. Deleting
it is `03`'s change, and it depends on the Activity-scoped ViewModel that this
evaluation declines.

---

## Where the proposals are complementary rather than competing

They are not five options. They are one plan in four stages plus one dead end.
The ordering matters and is not arbitrary.

**Stage 0 — the mechanical split. (`01` phases 1–2 ≡ `02` steps 0a–0f ≡ `03`
steps 1–2 ≡ `05` step 2.)**
Move `:1839-3193` and `:2057-2121` and `:210-219` into same-package siblings.
Zero behaviour change, zero cross-file diff, `MapScreen.kt` 3193 → ~1,565.

*Why first:* it is the only stage with no correctness argument to have, it is
the prerequisite every other proposal names, and it shrinks the diff every later
stage has to be reviewed against. `04` in isolation leaves `MapScreen.kt` at
~2,355 (`04:412`) purely because it does not touch UI; run stage 0 first and
`04`'s residual is ~1,000–1,100. Both authors say this in their own words
(`01:606-610`; `04:1174-1178`).

*Also do it as one short burst, not spread over sprints* (`01:630-633`), and
close the open specs in `docs/superpowers/specs/` first (hazard F-A).

**Stage 1 — pure helpers and tuning constants, to `:shared`. (`01` step 2 ∪
`05` step 1 ∪ `04`'s `CameraEase`/`MapTuning`.)**
The three proposals name the same symbols; take `04`'s address. Delete
`car/CarMapRenderer.kt:53-69`, `:470-475`, `:78`. Write the six tests from
convergence #5 in `commonTest`, not `app/src/test`.

*This is where `01` and `05` are the same commit at different addresses, and the
address is the whole difference.*

**Stage 2 — the four sensing machines, to `shared/…/drive`. (`04` steps 4–8,
with `05` variant B's test discipline.)**
`SectionAverageTracker`, `CameraWarner`, `SpeedLimitService`, `SpeedCameraFeed`,
`NavPolicy`. Delete `car/NavScreen.kt:131-137,378-392,396-410,242-252` and
`car/SpinScreen.kt:93-99,264-295`.

*`04` and `05` variant B are not alternatives here — they are the same three
extractions.* `05:609-611`'s "pure two-state class with a test" for SM6 *is*
`04`'s `CameraWarner`; `05`'s `SectionTracker` *is* `04`'s
`SectionAverageTracker`. `04` contributes the address (`:shared`, hence iOS and
CI-gated tests); `05` contributes the shape — `now` arrives as a field on the
action rather than being read inside (`05:287-289`), which is what makes the 8 s
quiet period at `:707`, the 15 s cooldown at `:1373` and the 30-minute timeout at
`:1220` testable without sleeping. **Take both.** Write characterisation tests
against a synthetic fix transcript *before* moving each machine
(`04:986-991`) — and accept `04:993-995`'s caveat that a characterisation test
locks in current bugs along with current behaviour.

**Stage 3 — decide later, with evidence. (`04` steps 9–10, or `05`'s
`SpinRound`/`CameraAuthority`, or `02`'s residual holders.)**
`NavSession` and `SpinSession` move the ownership of nine state values
(`destination`, `destinationName`, `route`, `candidates`, `navigating`,
`navProgress`, `rerouting`, `spinning`, `spinJob`) and carry most of the
remaining risk with the least test leverage (`04:1058-1065`). `05`'s `SpinRound`
touches the same convoy path (`05:868-872`). **Do not do both.** Do neither until
stages 0–2 have shipped and been ridden.

*`02`'s unique contribution belongs here and only here:* its observation
(`02:698-700`) that seven of the eight `rememberUpdatedState` back-refs
(`:937`, `:939`, `:1138-1140`, `:1173`, `:1250`) exist purely because a
long-lived callback closes over composition-local state. Stage 2 removes five of
them by moving the state out of the composition entirely; the remaining two
(`:937`, `:939`, the map-listener refs) are what a residual holder would address.
That is a small, well-scoped stage-3 question, not a reason to build eight
holders now.

**The dead end: `03`'s ViewModel.** Its *diagnosis* is the best in the set and
should be kept: concern 12, the Hub round-trip, is a real user-visible bug
(`03:709-716`) that nobody else names. Its *prescription* moves state away from
the singleton layer — the one mechanism `car/`, `TripTrackingService`,
`wear/NavRelay.kt` and `ble/BleNavServer.kt` all reach (`03:778-795`). Fix
concern 12 the repo's own way: promote the handful of values that must survive
the round-trip into the existing singleton layer, the same way `SpinResultHolder`
(`:369-385`) already does for the spin result. That is a ten-line change in the
house idiom, not an eleven-step framework migration.

---

## Fit hazards nobody flagged

**F-A. Every open design spec is invalidated by the first commit, and specs are
how this repo works.**
The inventory lists the stale-citation problem as documentation hygiene
(§6.2). It is more than that: `docs/superpowers/specs/` contains three specs
dated 2026-08-11, one per recent change, and
`2026-08-11-map-layers-panel-toggle-design.md:18,22,26,38,40,42,46` cites
`MapScreen.kt:2731`, `:2708`, `:2723`, `:2256`, `:3082` by line. Stage 0 moves
every one of those. Nobody proposed a rule. Mitigation is cheap: land stage 0
with no open spec, and switch future specs to citing symbol names
(`MapTopChrome`'s layers panel) rather than line numbers — which is strictly
better anyway.

**F-B. `README.md:383-385` makes a user-facing claim that the deduplication would
quietly change.**
It says Android Auto gets *"the same map, speed readout and camera warnings as
the phone."* That is currently true by copy-paste and false in one detail:
`MapScreen.kt:1160` falls back to the ambient limit
(`navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value`) while
`car/NavScreen.kt:405` uses `progress?.speedLimitKmh` only — so a **car camera
warning cannot fire while free-driving**, and a phone one can. Any single
`CameraWarner` has to pick one behaviour. Whichever it picks is a user-visible
change to one of the two surfaces, hiding inside a commit labelled `refactor`.
This must be its own commit with its own decision, not a side effect of stage 2.
`04` proposes the merge and does not notice the divergence; the inventory found
it (§7.6) and did not connect it to the README.

**F-C. `internal` stops working at the module boundary, and `public` in
`commonMain` is ABI.**
All five proposals lean on `private` → `internal`. Inside `app/` that is free and
precedented (`HistoryScreen.kt:71,120`). But `internal` is *module*-scoped, and
`:shared` is a different module — so every symbol `04` (and stage 1–2 above)
moves to `commonMain` and needs from `app/` must be `public`. Kotlin `public` in
`commonMain` is exported into the Objective-C framework header that
`iosApp/Detour/` consumes (this is how `SpinPickerKt.pickThreeCandidates` reaches
`SpinModel.swift:129`). **`SectionAverageTracker`, `CameraWarner` and
`SpeedLimitService` therefore become part of the iOS framework's public API
surface on the day they land**, before any Swift code uses them. That is
acceptable and probably desirable, but it is a wider commitment than "change a
keyword" and nobody says so.

**F-D. CI does not run `app/src/test`, so four of the five proposals' tests are
unenforced.**
`build.yml` runs on every push and PR and executes only
`:app:assembleRelease :app:bundleRelease` (`build.yml:123`). `ios.yml` executes
`:shared:testDebugUnitTest` and `:shared:iosSimulatorArm64Test` (`:65,68`) but is
path-gated to `shared/**` and `iosApp/**` (`:11-19`). `CONTRIBUTING.md:139-147`
describes exactly this and never mentions `app/src/test`. So
`app/src/test/.../TripTraceMatchingTest.kt` and `.../PlaceNotificationsTest.kt`
have never been run by CI.

Every proposal reasons about testability as though placement is neutral. It is
not. `01`'s six pure functions in `app/src/test/java/com/jellemax/detour/ui/`,
`05` variant B's three files in `app/src/test/java/com/jellemax/detour/map/`,
`03`'s ViewModel tests, and `02`'s holder tests are all **documentation that
compiles**, and will silently rot on the first change that breaks them. `04`'s
`commonTest` files (`04:758-831`) are the only ones behind a gate — and behind
*two* runtimes. This is the single largest correction the repo makes to the
proposals' own arguments, and it moves `04` up and `05` variant B down. (The
alternative fix — add `:app:testDebugUnitTest` to `build.yml` — is two lines and
should probably happen regardless, but it is not what any proposal assumed.)

**F-E. Two of the three onboarding questions are answered by a *comment*, and one
split severs it across a module boundary.**
"The map stops following when I pan" is not answered by a symbol; it is answered
by `MapScreen.kt:665-668`: *"A camera-move listener can't be used for this: the
frame loop moves the camera every frame, so it would fire constantly and couldn't
tell us from the user."* That comment sits on the `DisposableEffect(mapView)`
touch listener, which is Android `View` code and must stay in `app/`. `04:382`
puts the *resume policy* (`:269-275`, `:696-712`) in
`shared/…/drive/FollowCamera.kt`. One question, one explanation, two modules.
`04:1017-1025` notices that `choose()` splits in two; it does not notice that a
comment does. The fix is small — restate the pairing in both places — but it has
to be a rule, because `CONTRIBUTING.md:158-162` protects comments from deletion
and says nothing about comments being *separated from what they explain*.

**F-F. `data class Fix` is on the wire to two other devices.**
`04` step 0 moves `Fix` (`tracking/TripTrackingService.kt:93-100`) into `:shared`
and calls it "a two-file change, not the ten I first assumed" (`04:1029-1032`).
By symbol reference, correct. By blast radius, not: `Fix` is the input type of
every extracted machine, and the same drive-time data flows to `wear/` via
`wear/NavRelay.kt` and to a handlebar display via `ble/BleNavServer.kt`, while
`iosApp/Detour/TripRecorder.swift` maintains its own parallel notion. Promoting
it is right — but it should be verified against those three consumers before it
is called cheap, not after.

**F-G. `wear/` appears in none of the five proposals, and it has the one defect
worth designing for.**
Inventory §7.11 found it: *"wear and BLE guidance go dead whenever navigation is
driven from the head unit."* The cause is structural — `NavRelay.send` and
`BleNavServer.send` live inside MapScreen's nav loop (`:1356-1357`), and the
not-navigating half lives in a separately-keyed effect at `:1338-1342`, so both
halves of one BLE characteristic are welded to one composable. No proposal
treats this as a requirement. It should be the **acceptance test** for whichever
nav extraction eventually lands: *does starting navigation from
`car/NavScreen.kt` light up the watch?* If the answer is still no, the extraction
did not go far enough.

**F-H. For three of the five, "would this be rolled out to the other screens?" is
permanently *no* — and not for reasons of will.**
Proposals 2, 3 and 5 are each judged partly on whether their pattern would spread
to `SettingsScreen.kt` (1,199) and `FriendsScreen.kt` (939). `03:824-829` frames
this as adoption risk. The real answer is stronger: **those screens have no
screen state for the pattern to own.** `SettingsScreen.kt:121-132` is `var page`
plus twenty-one `collectAsStateWithLifecycle` reads off `Settings`, and its
fourteen sections write `Settings` directly (`SettingsScreen.kt:236`).
`FriendsScreen.kt:84` and `CirclesScreen.kt:95-107` are the same shape at smaller
scale. A ViewModel, a state holder or a reducer over any of them would be 100%
pass-through — precisely the anti-pattern `03:756-767` names.

So the rollout is not "deferred," it is *impossible on the merits*, and the
inconsistency those three proposals create is permanent by construction. That
converts a soft cost into a hard one and is the main reason D7 scores them 1–2
despite its low weight. It also explains something the proposals treat as
accidental: this codebase has no ViewModels not because nobody got around to it,
but because the singleton-plus-`remember` shape genuinely fits eighteen of its
nineteen screens.

---

## My recommendation, and what I would refuse to do

### Do, in this order

**1. Stage 0 — the mechanical split (`01` phases 1–2), as one short burst.**
Unconditional. Every one of the five proposals asks for it in its own verdict.
Zero behaviour risk, zero cross-file diff, `MapScreen.kt` 3193 → ~1,565.
Precondition: close the open specs in `docs/superpowers/specs/` first (F-A).
Record the outcome honestly, in `01:833-835`'s words: *"MapScreen's presentational
layer separated; its state layer is unchanged and is the next problem."*

**2. Add `:app:testDebugUnitTest` to `build.yml`.** Two lines. Independent of
everything else, and F-D means every other testability claim in this document
depends on it.

**3. Stage 1 — pure helpers and camera tuning into `:shared`**, with the six
convergence-#5 tests in `commonTest`. Delete `car/CarMapRenderer.kt:53-69`,
`:470-475`, `:78`.

**4. Stage 2 — the four sensing machines into `shared/…/drive`** (`04` steps
4–8), one commit each, characterisation tests written *before* each move, using
`05` variant B's injected-clock shape. Order by risk: `SpeedLimitService`,
`SpeedCameraFeed`, `CameraWarner`, then `SectionAverageTracker` last (it is the
one with a shipped-bug history at `:294-298`). Delete the car duplicates as each
lands. Enforce `04:341-343` — no extracted service owns a `CoroutineScope` — with
a CI grep, because `ConvoyLiveClient.kt:311` is a counter-example already in the
tree and conventions with counter-examples decay.

**5. Then stop and ride it.** Decide about `NavSession`/`SpinSession` afterwards,
with `F-G`'s wear/BLE question as the acceptance criterion.

### Refuse

**Refuse `03`'s ViewModel, in full.** Not because ViewModels are wrong — because
in *this* process, `car/DetourCarSession.kt:11`, `TripTrackingService`,
`wear/NavRelay.kt` and `ble/BleNavServer.kt` all reach app state through
singletons, and a `ViewModelStore` reaches none of them (`03:778-795`). It is the
first framework dependency in the UI layer, for one screen out of nineteen, with
no possible rollout (F-H), against a written instruction not to do exactly that
(`CONTRIBUTING.md:164-166`). Keep its diagnosis of the Hub round-trip; fix that
in the singleton layer where `SpinResultHolder` already lives.

**Refuse `05` variant A.** Its own author does (`05:989-998`). 430 lines of
`State`/`Action`/`Effect` ceremony plus a recomposition-granularity problem on
the app's only 60 fps screen, to fix three machines out of nine.

**Refuse `02`'s eight holders as a stage of their own.** Not because the design is
bad — it is the most careful of the ambitious three on lifetimes
(`02:146-176`) — but because stage 2 removes most of what they would buy (five of
the seven `rememberUpdatedState` refs, the concern-locality, the seams) while
also reaching `car/` and `iosApp/`, which holders structurally cannot. Revisit
the residual as a small stage-3 question.

**Refuse `01` phase 3's two worst extractions specifically:**
`MapOverlayRenderEffect` (15 parameters, `01:639-641`) and `CameraTargetEffect`
(whose "null-bearing-means-keep" contract `01:642-643` concedes is a regression in
expressiveness). `01:825-827` is right that phase 3 without line-by-line
`rememberUpdatedState` review is net-negative; given F-D, "the tests will catch
it" is not available.

**Refuse to bundle any of these four behaviour decisions into a refactor commit.**
Each is a real decision with a user-visible outcome, and each is currently
protected by a comment or by nothing at all:

- `MapScreen.kt:1160` vs `car/NavScreen.kt:405` — whether a camera warning fires
  while free-driving. Unifying the warner forces this choice (F-B), and
  `README.md:383-385` currently asserts they are the same.
- `MapScreen.kt:1403` — `spin()` sets `camSuspended` but not `lastGestureMs`,
  unlike the four other park sites (`:810/813`, `:837/838`, `:1677/1679`,
  `:1829/1830`). Inventory H8 flags it as "either a latent bug or deliberate."
  Any refactor that "unifies" the park helper decides it silently.
- `MapScreen.kt:555-559` vs `:724` — `myLocation` has two writers with different
  accuracy gates (≤100 m from `liveFix`, none from `fetchLocation`). Inventory
  H10. Merging the paths changes what `spin`, `startNavigation` and the fog
  corridor see.
- `MapScreen.kt:460` — `error` has twelve writers and one reader
  (`SpinSheet(error = error)` at `:1771`), so permission and navigation failures
  are invisible whenever the sheet is collapsed. Inventory H9 is right that
  "fixing" this during a refactor is a behaviour change, not a refactor.

**And refuse to let any of this delete or orphan a why-comment.** The rule is
`CONTRIBUTING.md:158-162`, and the three cases to watch are the adjacency
comments (`MapScreen.kt:494-496`, `:1091-1093`), the Compose-mechanism comments
that survive their mechanism (`:696-699`, `:1232-1235`, `:1247-1250`), and the
one split by a module boundary (`:665-668`, F-E). On a file whose behaviour has
no CI-gated test anywhere, those paragraphs are the specification.
