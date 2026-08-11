# Splitting `MapScreen.kt` — findings, options and recommended plan

`app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` is 3193 lines: one 1419-line
composable plus a 1355-line tail of presentational composables and helpers. This document
is the synthesis of a nine-agent investigation into how to split it. It is the decision
record; the underlying reports are kept alongside it so the reasoning stays checkable
against whatever we actually build.

## How this roadmap is executed

The phases below are implemented as a chain of staged specs in
[`specs/`](specs/), one per phase, each ending by pointing at its successor. Start at
[`specs/00-chain-design.md`](specs/00-chain-design.md), which explains how the chain works,
then [`specs/stage-0-verification-baseline.md`](specs/stage-0-verification-baseline.md).

Each spec opens with executable preconditions. If they fail, the spec is stale and gets
rewritten rather than adapted — the stages change the ground the later ones stand on, and
that is expected, not a failure.

## Index of reports

| File | What it is |
|---|---|
| [`00-inventory.md`](00-inventory.md) | Ground-truth inventory: every symbol, state variable, effect and closure, with a coupling matrix. No opinions. |
| [`01-proposal-mechanical-split.md`](01-proposal-mechanical-split.md) | Champions a pure file split with zero architecture change. |
| [`02-proposal-compose-state-holders.md`](02-proposal-compose-state-holders.md) | Champions plain Compose state holders (`rememberXxxState()`). |
| [`03-proposal-viewmodel-uistate.md`](03-proposal-viewmodel-uistate.md) | Champions ViewModel + immutable UiState (MVVM). |
| [`04-proposal-domain-services.md`](04-proposal-domain-services.md) | Champions extracting domain logic down into the KMP `:shared` module. |
| [`05-proposal-mvi-reducer.md`](05-proposal-mvi-reducer.md) | Champions MVI / pure reducers over the screen's implicit state machines. |
| [`06-proposal-hexagonal.md`](06-proposal-hexagonal.md) | Champions hexagonal / ports-and-adapters at whole-repo scale. Added after 01–05 as a late entry. |
| [`10-eval-fact-audit.md`](10-eval-fact-audit.md) | Adversarial audit of every load-bearing factual claim in 01–05. |
| [`11-eval-fit-maintainability.md`](11-eval-fit-maintainability.md) | Judges the five against this codebase's house style and its other surfaces. |
| [`12-eval-risk-sequencing.md`](12-eval-risk-sequencing.md) | Judges regression risk, verifiability and migration ordering; contains the manual test checklist. |
| [`13-surface-independence-audit.md`](13-surface-independence-audit.md) | How much logic the four client surfaces actually share vs duplicate, with per-pair figures. |

Each proposal agent worked independently and argued its assigned pattern. The three
evaluators worked independently of each other and of the proposal authors. Where they
converged without coordination, that is treated as evidence; where they contradict, the
contradiction is resolved explicitly below rather than averaged away.

---

## Corrected ground truth

The audit re-counted everything. Use these numbers, not the ones in the individual
proposals.

- **3193 lines** total: 206 imports, composable body `419–1837` (1419 lines), UI tree
  `1529–1837` (309), presentational tail `1839–3193` (1355).
- Inside the body: **59** `remember*`, **32** `LaunchedEffect`, **4** `DisposableEffect`,
  **21** `collectAsStateWithLifecycle`, 43 `mutableStateOf`, **0** `derivedStateOf` and
  **0** `snapshotFlow`.
- Whole file: **24** private composables (not 27 as 01 and 00 both state), 36 private funs.
- `TripTrackingService.lastFix` has **6 independent subscriptions** in this one file.
- `error` (`MapScreen.kt:460`) has **12 writers and 1 reader** (`:1771`) — permission and
  navigation errors are invisible whenever the spin sheet is collapsed. That is a live
  defect, not a refactor concern.
- External API surface is tiny: only `MapScreen`, `TravelMode.icon` and
  `seedRouteNavigation` are referenced from other files (5 call sites).
  `SpinResult`/`SpinResultHolder` have **zero** external references, and the comment at
  `:374–375` claiming otherwise is stale.
- **`:shared` is a real KMP module**: 43 Kotlin files, 36 in commonMain, **60 `@Test` in
  commonTest**, consumed by `app/` and `iosApp/` (not `wear/` — the brief was wrong there).
  commonMain has no `Dispatchers.*`; it does have a wall clock via `nowMs()`
  (`Angles.kt:15`). Code moved there must be adapted, not copy-pasted.
- **`car/` duplicates ~199 phone-side lines / ~186 car-side lines** across 11 verified
  items, and the copies have drifted.

### Two facts nobody was asked about, both of which change the plan

1. **CI runs no Kotlin test.** `.github/workflows/build.yml` only assembles and bundles.
   The six existing test files are never executed; `ios.yml` runs `:shared` tests but is
   path-gated. Four of the five proposals sell testability into a gate that does not exist.
   One line of YAML fixes it.
2. **A GPS replay harness already exists.** `tools/mocklocation/MockService.kt` replays a
   route as mock fused fixes with real speed and bearing at `accuracy = 4f`, which clears
   every accuracy gate in `MapScreen.kt` (`:556`, `:706`, `:1028`, `:1189`, `:1239`,
   `:1641`). The premise that verifying this screen requires physically driving is roughly
   70% true, not 100%. Nobody is using the harness this way today.

---

## Cross-cutting concerns

All six analysts produced compatible concern lists. Consolidated, with line ranges:

| # | Concern | ~Lines | Where it lives today |
|---|---|---|---|
| 1 | **Destination spine** — `destination`/`destinationName`/`route`/`candidates` | — | Written by **7 identical code paths** across 5 concerns: `choose` (804), `commitSpinCandidate` (828), long-press (949), `spin` (1392), `selectMode` (1515), chip pick (1674), search pick (1824). This is the file's actual backbone. |
| 2 | **Camera authority** — `followMe`/`camSuspended`/`lastGestureMs` | ~200 | 9 write sites incl. a raw Android `OnTouchListener` (669–694); two byte-identical pairs (810–813 vs 837–838); `spin()` at 1403 sets one but not the other. |
| 3 | **Spin / roulette** | ~330 | `spin()` 1392–1513 alone is 122 lines with 8 state writes; re-implements `pickThreeCandidates` that already exists in `:shared`. |
| 4 | **In-app navigation** — progress, arrival, reroute | ~180 | 1345–1390, duplicated in `car/NavScreen.kt:242`. |
| 5 | **Road hazards** — ambient speed limit, camera chime, trajectcontrole average | ~190 | 1018–1230, five closure-local `var`s no test can reach. |
| 6 | **Map SDK glue** — MapView lifecycle, style, overlays, fog, listeners | ~250 | 592–968. |
| 7 | **Convoy / group spin / PTT** | ~150 | 16 lines of prose correctness argument (842–857), zero tests. |
| 8 | **Location & permissions** | ~90 | 714–801. |
| 9 | **External nav-app dispatch** | ~180 | 2126–2232, 3150–3193 — already parameter-clean. |
| 10 | **Presentational tail** | 1355 | 1839–3193. |

---

## Late entry: hexagonal / ports and adapters, and the state of surface sharing

Added after 01–05 were scored, because it reframes the problem: is `MapScreen.kt` a
file-splitting problem, or a symptom of domain logic sitting inside a driving adapter?

### Are the surfaces independently developed?

No — **partially shared, ≈40%**. `shared/` commonMain is 4,921 lines against 12,372 lines
of total client logic (Android non-UI 5,262 + iOS non-UI 2,004 + wear 185 + shared). Against
*all* client code including UI, 18.3%.

Duplication, ≈1,300 lines / ~11% of client logic:

| Pair | Duplicated | Notes |
|---|---|---|
| Phone ↔ Android Auto | ≈199 Kotlin / ≈186 Kotlin | Same Gradle module — a plain move recovers it, no ports needed |
| Phone ↔ iOS | ≈1,150 Kotlin / ≈1,070 Swift | **21% of the iOS app, ~55% of its non-UI code**, hand-translated |
| Phone ↔ Wear | ≈45 of 185 | `wear/build.gradle.kts` has **no `:shared` dependency at all** |
| Car ↔ iOS, Wear ↔ iOS | 0 | No CarPlay, no watchOS |

Worst iOS offenders: `TripRecorder.swift` ≈270 lines with 19 tuning thresholds copied
verbatim from `TripTrackingService.kt:140–201`; `ConvoyLiveClient.swift` ≈300 lines
re-implementing the entire relay protocol, whose own doc comment admits the two copies
"have to agree or a convoy splits across two destinations"; `Format.swift` duplicated 100%,
comments and all; `MapScreen.swift:296–347` is a third copy of `leadingSpinIndex`.

### The repo already committed to this pattern, in writing

`CONTRIBUTING.md:23–32` states the rule verbatim: *"the core is handed things, it never
reaches for them"*, that `Platform.kt` expects only three things and *"wanting to add a
fourth is the signal to push the dependency in from the platform instead"*, and — decisively
— *"New logic goes in `shared/` unless it genuinely cannot; a change that lands only in
`app/` silently makes iOS diverge."* `Platform.kt:5–15` repeats it. There are **4 `expect`
declarations / 28 `actual` members** in the whole module, **zero interfaces in commonMain**,
and 33 `object` singletons.

So hexagonal is not a new proposal here. It is the project's stated architecture, and
`MapScreen.kt` is a 3193-line violation of it.

### The finding that settles the sequencing

**Parity is decided by statefulness, not by domain relevance.** Every feature that reached
iOS has its logic in `shared/` (NavEngine, SpinPicker, Badges, Coverage, GeofenceEvaluator).
Every feature that did not is welded into a composable or an Android Service. The three
road-hazard features are missing on iOS **even though `RoadRoulette.speedLimitWays`,
`snapSpeedLimitKmh` and `SpeedCameras.near` already sit in commonMain, unused** — the
stateful wrapper around them never left `MapScreen.kt`.

That is the whole argument for phase 3 in one sentence, and it was found by looking outside
the file.

### Divergence has already produced a live user-facing bug

The GraphHopper sign→maneuver table exists **four times** and has diverged three ways.
Verified directly:

- `Navigation.kt:57–71` (phone) handles 13 cases including `-98,-8` → U-turn, `±7` → fork.
- `NavScreen.swift:223–236` (iOS) handles 9 and maps **`-3` (sharp left) → `arrow.uturn.left`**,
  so a sharp left is drawn as a U-turn; **`-98`/`-8` (an actual U-turn) falls through to
  `default` → `arrow.up`, drawn as "carry straight on"**; `±7` keep-left/right also fall
  through to straight ahead.

Three wrong maneuver arrows on a live navigation screen, from a table nobody thought was
duplicated. Fix it as its own commit, independent of any refactor.

### Verdict on the pattern itself

Proposal 06 concedes the key point: it **does not beat proposal 04, it supersets it** —
stages 0–4 of its own migration plan *are* 04's plan, same ≈838 lines out. What it adds
beyond 04 is the convoy protocol, the nav vocabulary, the voice policy and trip detection,
plus a *named closed port set* instead of per-service constructor defaults.

As a whole-repo programme: **no.** One maintainer, and `car/` is in the same Gradle module
as `ui/`, so most duplication needs no KMP hop at all. iOS interop is a real tax —
`FlowWatcher.kt` is 196 lines of boilerplate with one Watcher subclass per element type
because Kotlin/Native erases generics, so every new core `StateFlow` costs iOS a subclass.

**Adopted instead: 06's rule, not its full apparatus.**

> A policy earns the core when it is written more than once.
> A port earns an interface when it has more than one implementation.

That yields **four ports — Clock, Announcer, Alerter, LiveTransport — not nine**; 06
disqualifies its own RoadData/Router/PlaceSearch/MapRenderer/LocationSource ports by the
same rule. `CircleEvents.kt` (210 lines, decision and wording in the core, delivery per
platform, called from both) is the in-repo template.

One bonus fact this surfaced: `ios.yml:65,68` **already runs `:shared` tests** on JVM and
Kotlin/Native. Code moved into the core is CI-gated today with zero YAML change, while
`build.yml` runs no Kotlin test at all. That is a second, independent reason phase 3 targets
`:shared` rather than `app/…/map/`.

---

## The five patterns, scored

Weighted for this repo (multi-surface KMP, no DI, house style of singletons + StateFlow,
GPS-driven UI that unit tests cannot reach).

| Rank | Pattern | Fit score | Verifiability | Verdict |
|---|---|---|---|---|
| 1 | **04 Domain services → `:shared`** | 4.10 | high ceiling | Best long-term value. Deletes car duplication, reaches iOS, produces real tests in the one source set CI could actually gate. Removes ~838 lines (26%), not the "make it small" answer. |
| 2 | **01 Mechanical split** | 3.50 | highest | Zero behavioural risk, moves 1355–1494 lines, needs no new pattern. Solves file size and nothing else — and is a prerequisite that makes every other option cheaper. |
| 3 | **05 MVI (targeted variant only)** | 2.75 | medium | Strong evidence base — nine implicit state machines, three with prose correctness arguments and no tests. But full MVI is +11% LOC and alien to every other screen. Its author recommends the targeted variant; so do we, and only after 04. |
| 4 | **02 Compose state holders** | 2.50 | medium | Cheapest correct state split, largest headline shrink (→~300 lines). But 6 of 8 holders are untestable, `car/` cannot use any of them, and its own `rememberSaveable` mitigation introduces a second divergent `ServerConfig`. |
| 5 | **03 ViewModel + UiState** | 1.70 | lowest | **Rejected.** `androidx.car.app.Session` is not a `ViewModelStoreOwner` and the car runs in the same process, so anything moved into a VM becomes unreachable from `car/`, the tracking service and the BLE/Wear relays. Its one genuine finding — composition-lifetime state loss — is real and is handled separately below. |

---

## Convergence (independent agreement = extra weight)

Ten recommendations were reached independently by multiple agents. Four-way convergences
are treated as settled.

| Weight | Recommendation | Reached by | Audit |
|---|---|---|---|
| ★★★★ | Move the presentational tail (`1839–3193`) into its own files | 01, 02, 03, 05 | Supported |
| ★★★★ | Give `TravelMode.icon` its own file | 01, 02, 03, 05 | Supported — 3 external consumers |
| ★★★★ | Extract `SpinResult`/`SpinResultHolder`/`seedRouteNavigation` to one file | 01, 02, 03, 05 | Supported; both types can become `private` there |
| ★★★★ | Keep new files in `com.jellemax.detour.ui`, promote `private`→`internal` | 01, 02, 03, 05 | Supported, with in-repo precedent (`HistoryScreen.kt:72,120`) — this is what makes the move zero-risk |
| ★★★★ | Unify camera-easing constants + `smoothBearing`, point `car/` at them | 01, 02, 04, 05 | Supported |
| ★★★ | Extract the three hazard machines as testable plain classes | 03, 04, 05 (+02 as a holder) | Supported — ~190 lines, five unreachable closure vars |
| ★★★ | Point `car/` at whatever that produces | 03, 04, 05 | Supported **but** extract from the car copy, not the phone copy |
| ★★★ | Move the Overpass fetch off the `lastFix` collector | 04, 03, 05 | Supported — **this is a bug fix, not a refactor** |
| ★★★ | Do **not** collapse state into one monolithic `UiState` | 03, 02, 05 | Supported — `displaySpeedKmh` is written every frame |
| ★★ | Hoist `SpeedHud` so `displaySpeedKmh` stops invalidating the map content lambda | 03 explicitly, 01/02/05 as a side effect | Supported — fixed by the composable split alone |

The most telling convergence is the road-hazard extraction: five agents championing
mutually incompatible architectures all rated it medium-risk and all put it on the critical
path anyway. That is convergence driven by value, not by it being cheap.

---

## Contradictions, resolved

1. **Where do the hazard machines live?** Four answers were given for the same ~190 lines.
   **Resolution: `:shared` commonMain.** It is the only destination reachable by `car/`
   *and* `iosApp/`, and commonTest is the only test source set CI can realistically gate.
   Cost, stated openly: `Dispatchers.IO` has no commonMain equivalent, so I/O must be handed
   in by the caller. Time does have one — `nowMs()` at `Angles.kt:15`, backed by
   `kotlinx.datetime.Clock` — but these machines should still take a timestamp parameter rather
   than read it, because they are path-dependent over time and otherwise cannot be tested
   deterministically. Either way this is a rewrite, not a move, which is also what makes the
   results testable.
2. **Package layout.** **Resolution: flat `com.jellemax.detour.ui` for everything that
   stays in the app module.** It makes every phase-1 move a pure cut-and-paste with zero
   import edits. Decide this now; changing it later re-touches every moved file.
3. **Extract from the phone or the car?** **Resolution: from the car.** The car copies are
   strictly better on the two items that have drifted — the fetch is already off the fix
   collector and there is a re-entry guard. Extracting the phone version would port a known
   stall into shared code and then need it fixed again on top.
4. **Does composition lifetime need a ViewModel?** The defect is real: `AppRoot` swaps
   screens with a bare `AnimatedContent`, so Map→Hub→Map disposes the whole composition,
   resets every `rememberSaveable`, and re-runs `SyncClient.sync()` and the Overpass
   prefetch. **Resolution: log it as its own defect.** `rememberSaveableStateHolder()` in
   `AppRoot` is ~4 lines and fixes one of its five symptoms; the rest are a separate
   decision that does not need to ride inside this refactor.
5. **Is `SearchDialog` presentation?** No — `:1865–1894` is a 300 ms debounce plus
   `Geocoder`/`RecentSearchStore` I/O. It still moves in phase 1; it is just not "zero
   logic", and its tail should not be counted as such.
6. **LOC growth.** Every proposal grows repo-wide LOC (+6% to +15%) because of duplicated
   import blocks. That is a price, not a dispute. The headline "MapScreen.kt is now N lines"
   is measuring four different amounts of work — see the audit's relocation-volume table
   (04: 838, 03: 1300, 01: 1494, 05: 2790, 02: 2900).

---

## Recommended plan

Ordered by value delivered per unit of regression risk. Stop-points are legitimate places
to stop.

### Phase 0 — make verification cheap (nothing touches `MapScreen.kt`)

- Add `./gradlew :app:testDebugUnitTest :shared:allTests` to `build.yml`. One line.
  Without it every test written later is decorative.
- Wire up `tools/mocklocation` with four checked-in canonical routes (a trajectcontrole, a
  road with changing urban limits, an off-route deviation, a stop-start city loop) and
  **record current behaviour on each**. That baseline is only capturable before the first
  behaviour-touching commit.
- Fix the three standalone defects, each in its own commit, before any split:
  - the Overpass `withContext` inside the `lastFix` collectors (`:1037`, `:1075`), using
    the car's `Job` + `isActive` pattern;
  - `error` having 12 writers and 1 reader, so permission failures are silently swallowed;
  - the iOS maneuver table (`NavScreen.swift:223–236`) drawing sharp-left as a U-turn, an
    actual U-turn as straight ahead, and keep-left/right as straight ahead. Unrelated to
    this refactor, user-visible today, and the reason the sign table should end up in the
    core afterwards.

Adopt the rule from proposal 06 as the standing test for everything below — it is already
the project's documented architecture (`CONTRIBUTING.md:23–32`), just unenforced:

> A policy earns the core when it is written more than once.
> A port earns an interface when it has more than one implementation.

### Phase 1 — the unanimous mechanical split → **stop-point A (~1565 lines)**

Move the tail, the pure helpers, the constants, `TravelMode.icon`, and the spin-result
holder into ~11 same-package files. `private`→`internal`, no reformatting, no comment
rewording in the same commit (`git log -C` is how the design record stays traceable). Land
it in one short burst, not spread across sprints. **Zero behavioural risk, and it makes
every later state diff reviewable against 1565 lines instead of 3193.**

If we stop here, write down explicitly that the state layer is untouched — otherwise the
line count gets recorded as "MapScreen refactored" and the real problem gets buried.

### Phase 2 — pure-logic extractions with tests → **stop-point B**

`NavPolicy` (arrival/reroute, `:1359–1389` + `car/NavScreen.kt:242`), `GroupSpinRules`
(`:322–367`, `:842–870` — the 16-line prose correctness argument with no test),
`FollowCamera.shouldResume` (`:696–712`). All pure, all individually revertable, all
deleting car duplication.

### Phase 3 — the road-hazard machines into `:shared` → **stop-point C (default stop)**

Cheapest and most isolated first: `SectionAverageTracker`, then `CameraWarner`, then the
speed-limit and camera feeds. **Characterisation tests against current behaviour first,
then the move**, constants copied byte-for-byte, A/B replay per machine. Then delete the
car copies — always one commit behind the extraction, never in the same one.

Watch the trap the risk evaluator found: moving the Overpass fetch off the collector
retunes the 3-miss hysteresis at `:1050`, because that constant was only ever tuned against
a stream *with* those dropped fixes. It is the safest-looking change in the whole set.

At stop-point C: `MapScreen.kt` ≈ 1300–1400 lines, ~40 tests over the code that actually
produces field bugs, car duplication down to one or two items, and every remaining option
still open. The cost-to-value curve turns sharply down after here.

### Phase 4 — optional, and pick exactly one

Either 02's remaining state holders **or** 05's targeted `CameraAuthority` reducer.
**Never both** — `MapCameraState` and `CameraAuthority` are competing owners of the same
three variables (`:521–523`), and running both leaves the camera with two sources of truth,
which is strictly worse than today.

### Not doing

- **ViewModel + UiState (03).** The car surface, the tracking service and the BLE/Wear
  relays all live in the same process and none of them is a `ViewModelStoreOwner`.
  Migrating state into a VM makes it unreachable from three of the app's four surfaces.
- **Full MVI (05 variant A).** +11% LOC, ~430 lines of new declarations, alien to every
  other screen in the app, and worse indirection when debugging a field-reported GPS bug.

### Never in one commit

A move *and* a visibility change to a symbol whose call site also moves · a state-owner
change *and* a lifetime change · an extraction *and* the bug it reveals · an effect body
move *and* a change to that effect's key list (the key list **is** the behaviour at `:700`,
`:1024`, `:1236`, `:1271`, `:1345`) · `camSuspended` *and* `lastGestureMs` at `:1403` ·
any two `lastFix` consumer changes · any move *and* any reformatting.

The manual verification checklist to run after each step is in
[`12-eval-risk-sequencing.md`](12-eval-risk-sequencing.md).

---

## One user-visible decision hiding in here

`README.md:383–385` claims the car has "the same camera warnings as the phone". It does
not: `MapScreen.kt:1160` falls back to the ambient speed limit, `car/NavScreen.kt:405` does
not. De-duplicating those two forces a decision about which behaviour is correct. That
decision must be its own commit with its own release note — it must not ride inside a
refactor.
