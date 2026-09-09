# Many narrow state holders, not one context object

Written against commit `efa6c9ad` (2026-09-09). Every `file:line` below was read
directly from the tree at that commit; anything not directly read is marked
**unverified**. Where a number in the brief that requested this document, or in
an existing guideline doc, turned out wrong, that is called out explicitly —
treat no document, including this one after its next edit, as ground truth.

## Scope and independence

Not `docs/research/ui-packaging/` (moves files between packages, zero logic
change) and not `docs/research/renderer-layer/` (retained-mode renderers for
`MapOverlays`/`FogView`/`CarMapRenderer`, explicitly excludes `@Composable`).
This is the third, and it is the one the other two are shaped by:
`docs/guidelines/boundaries.md` §8.4 says a file split must stop at seven
threaded parameters or you have found a state-ownership problem, not a
concern boundary. `ui/MapBottomSlot` (48 parameters, corrected below) and
`SpinSheet` (24) are exactly that problem, unresolved, sitting under both
other refactors. This document is the unblock.

## What already exists

The owner's own design — strict data classes, renderers per entity, "many
smaller god classes" rather than one context object — is not a proposal for
new architecture. It is already built, half of it, in
`shared/src/commonMain/kotlin/com/jellemax/detour/presentation/`: **25
files, 1,958 lines** (`wc -l`, confirmed), holding **15** `*State` data
classes and **8** `*Presenter` files, plus pure builder functions beside them.

| File | Lines | Declares | Test |
| --- | --- | --- | --- |
| AvatarInitial.kt | 14 | `avatarInitialOf` | AvatarInitialTest.kt |
| BadgesState.kt / BadgesPresenter.kt | 86 / 47 | `BadgesState`, `BadgeTile`, `BadgeGroup` | BadgesStateTest.kt |
| CircleDetailState.kt / …Presenter.kt | 118 / 33 | `CircleDetailState`, `CircleMemberRow`, `SharedPlaceRow`, `CircleEventRow` | CircleDetailStateTest.kt |
| CirclesListState.kt / …Presenter.kt | 74 / 40 | `CirclesListState`, `CircleRow` | CirclesListStateTest.kt |
| CoverageState.kt / …Presenter.kt | 51 / 55 | `CoverageState`, `CoverageEntryView` | CoverageStateTest.kt |
| DisplayFormat.kt | 179 | shared formatters (`formatFixed`, `formatDistanceKm`, …) | DisplayFormatTest.kt |
| FriendsState.kt / …Presenter.kt | 108 / 38 | `FriendsBoardState`, `LeaderboardRow`, `FriendRequestRow` | FriendsStateTest.kt |
| HomeState.kt | 147 | `HomeBottomCard` (enum) + 7 predicates — see below | HomeStateTest.kt, HomeIdlePredicatesTest.kt, HomeShortcutPlacesTest.kt |
| NavState.kt | 161 | `NavState`, `NavThenPill` | NavStateTest.kt |
| PlacesState.kt / …Presenter.kt | 41 / 40 | `PlacesState`, `PlaceRow` | PlacesStateTest.kt |
| RiderCardState.kt | 69 | `RiderCardState` | RiderCardStateTest.kt |
| RoutesState.kt / …Presenter.kt | 141 / 42 | `RoutesState`, `RouteCard`, `ThumbPoint` | RoutesStateTest.kt |
| ServersSyncState.kt | 107 | `ServersSyncState` | ServersSyncStateTest.kt |
| SettingsHubState.kt | 41 | `SettingsHubState` | SettingsHubStateTest.kt |
| SpinState.kt | 103 | `SpinState`, `SpinCandidateRow`, `spinStateFrom` | SpinStateTest.kt |
| TripHudState.kt | 142 | `SpeedHudState`, `ActiveTripCardState` — see below | SpeedHudStateTest.kt, ActiveTripCardStateTest.kt |
| YouState.kt / YouPresenter.kt | 37 / 44 | `YouState` | YouStateTest.kt |

20 test files under `shared/src/commonTest/.../presentation/` (confirmed by
listing the directory), one per production file plus the three-way split
under `HomeState.kt` noted below.

**Leaves already consume the holder, not the fields:** `SpeedHud(state:
SpeedHudState, …)` (`ui/MapHud.kt:121`), `TripStatsRows(stats: TripStats,
state: ActiveTripCardState)` (`ui/RideSheet.kt:383`), `rows:
List<SpinCandidateRow>` in `CandidatesCard` (`ui/CandidatesCard.kt:51`), and
`HomeBottomCard` dispatched in `ui/MapBottom.kt` (its whole `when` at
`:170-223`) and read again at `ui/MapScreen.kt:549`.

This is the reframe: the tier is not missing. It is finished at both ends —
pure, tested holders in `commonMain`; leaf composables that take a holder,
not eleven values — and skipped in the middle, which the diagnosis below
covers.

### The builder call counts — corrected

The brief that requested this document estimated these; three of six were
wrong. Re-run these to check them again:
`grep -rn "<name>(" app/src/main shared/src` (excluding `fun <name>(`).

| Builder | Claimed | Actual | Detail |
| --- | --- | --- | --- |
| `speedHudStateFrom` | 2 callers | **1** | `MapScreen.kt:1126` only |
| `spinStateFrom` | 3 | **3**, confirmed | `SpinCards.kt:247`, `SpinCards.kt:282`, `MapBottom.kt:205` |
| `homeBottomCard` | 3 | **1** | `MapScreen.kt:532` only — the other two hits were a doc comment in `NavigationDock.kt:53` and one in `SpinResultHolder.kt:75`, not calls |
| `activeTripCardStateFrom` | (none given) | **1** | `RideSheet.kt:364` |
| `reachMeters` | 5, incl. `car/CarMapRenderer.kt` | **1** | `MapScreen.kt:596` only. `CarMapRenderer.kt:457`, `RouteEditorScreen.kt:233` and `TripDetailScreen.kt:297` all pass a literal `reachMeters = null` — that is the *same-named parameter* of `MapOverlays.render` (`ui/MapLibreMap.kt:486`), an unrelated function. None of them call the shared builder. |
| `pushToTalkShown` | 1 | **1**, confirmed | `MapScreen.kt:1194` |

This matters beyond bookkeeping: **`reachMeters` is correctly *placed* for
cross-surface reuse (pure, no Android types) but is not actually reused
today.** `car/` imports nothing from `presentation` at all (`grep -rln
"import com.jellemax.detour.presentation" app/src/main/java/.../car/` —
empty), and nothing in `iosApp/` references `SpeedHudState`,
`ActiveTripCardState`, `SpinState`, `reachMeters` or `NavState` by name — iOS
keeps its own `SectionAverageChip` (`iosApp/Detour/SectionAverage.swift:13`)
for the one surface that overlaps. §5's placement rule (below) says where a
holder *should* live; it does not by itself make another surface adopt it.

## The three misnamed files, corrected

The brief guessed three filenames were wrong. Two are; one already matches.

**`TripHudState.kt` — misnamed, and worse: it should be two files.**
Declares `SpeedHudState` (`:9`) and `ActiveTripCardState` (`:33`) plus their
builders (`:75`, `:122`) — there is no `TripHudState` type, confirmed. Its
own doc comment (`:59-63`) already argues these are separate concerns: "the
two surfaces share nothing: different inputs, no common output, and
different lifetimes — the card keeps rendering the *exiting* trip's retained
stats for a few frames after the trip ends, while [the dial] has to be the
speed right now." That is §8.6's rule verbatim — "state that changes on
different clocks... two concerns" — being violated by the file that
articulates the rule. **Split, don't just rename:** `SpeedHudState.kt` and
`ActiveTripCardState.kt`, matching the two test files that already exist
one-to-one (`SpeedHudStateTest.kt`, `ActiveTripCardStateTest.kt`).

**`HomeState.kt` — misnamed, and the test suite already shows the split.**
Declares no `HomeState`: an enum `HomeBottomCard` (`:8`) plus seven pure
predicates — `homeBottomCard` (`:34`), `displayCandidates` (`:59`),
`inAppNavAvailable` (`:69`), `reachMeters` (`:87`), `obd2FedThisTrip`
(`:107`), `pushToTalkShown` (`:119`), `homeShortcutPlaces` (`:143`) —
confirmed exactly. Three test files already cover one production file
(`HomeStateTest.kt`, `HomeIdlePredicatesTest.kt`,
`HomeShortcutPlacesTest.kt`), which is the split the file itself hasn't
caught up to: `HomeBottomCard.kt` (the enum, `homeBottomCard`,
`displayCandidates`, `inAppNavAvailable` — what occupies the slot),
`HomeIdlePredicates.kt` (`reachMeters`, `obd2FedThisTrip`,
`pushToTalkShown` — what the idle screen shows), `HomeShortcutPlaces.kt`
(`homeShortcutPlaces`).

**`SpinState.kt` — correctly named.** Declares `SpinState` at `:39`,
confirmed. No rename needed; listed here only because the brief asked to
verify all three, and this is the one that wasn't broken.

## The diagnosis: an unconverted dispatcher, not a missing design

`ui/MapBottom.kt`'s `MapBottomSlot` (`:56-101`) takes **48 parameters**,
counted directly off the signature (`awk` over the declaration, 48 lines with
a `:`). `docs/guidelines/boundaries.md:79` and `ui/MapScreenState.kt:25` both
cite **46** — both have drifted by two params since they were written; 48 is
current as of `efa6c9ad`. `SpinSheet` (`ui/SpinCards.kt:107-131`) has 24
parameters including `modifier`, 23 without — `docs/guidelines/state-holders.md:72`
cites "23", which is consistent if it is excluding `modifier` by the same
convention; not an error, just an unstated one.

`MapBottomSlot` is a `when` dispatcher over `HomeBottomCard` — six
occupants, each needing a different subset of the 48. Classified against
what already has a holder, what doesn't yet, and what is genuinely per-call:

| Group | Params | Count | Holder |
| --- | --- | --- | --- |
| Already a holder type, passed as-is | `rideToggle`, `bottomCard`, `navState` | 3 | `SheetToggle`, `HomeBottomCard`, `NavState` — all exist |
| Shaped like an existing holder, arriving loose | `destination`, `destinationName`, `route`, `myLocation`, `mode`, `onNavigateInApp`, `onNavigate` | 7 | `GoTarget` (`ui/RideSheet.kt:85`) — already built *inside* `MapBottomSlot` (`:132-140`) from these seven, then thrown away and rebuilt again as raw params for `SpinSheet` |
| Shaped like an existing holder, arriving loose | `username`, `onOpenHub`, `searchOpen`, `onSearchOpenChange`, `onPickDestination` | 5 | `WhereTo` (`ui/RideSheet.kt:75`) — same story, built inline at `:141-146` for `DriveSheet` only, passed loose again to `HomeSheet` at `:246-250` |
| No holder yet — spin controls | `onSelectMode`, `radiusKm`, `onRadiusChange`, `minRadiusKm`, `onMinRadiusChange`, `poiKind`, `onPoiKindChange`, `directionDeg`, `onDirectionChange`, `spinning`, `onSpin`, `onCollapse`, `onTrack` | 13 | new — `SpinControls` |
| No holder yet — candidates panel | `displayCandidates`, `convoyVotes`, `activeConvoyMembers`, `onPickCandidate`, `onReroll`, `onCancelCandidates`, `onShare`, `onGoWithLead` | 8 | new — `CandidatesPanel` |
| Genuinely per-call, stays a parameter | `stats`, `onEndTrip`, `savedPlaces`, `serverConfig`, `onPickPlace`, `onSavePin`, `onOpenRoutes`, `onOpenSocial`, `onExitNavigation`, `error`, `onExpand`, `onClearDestination` | 12 | — |

3 + 7 + 5 + 13 + 8 + 12 = 48. **15 of the 48 already have a home** (three
already typed as holders, twelve more shaped exactly like `GoTarget` and
`WhereTo` but not passed as one). **21 need two new holders written once.**
**12 are genuinely one-off** — `error`, for instance, is one screen-wide
string with too many independent writers to be "grouped" honestly.

Collapsing the first two groups into the holders that already exist, and
writing the two new ones, takes `MapBottomSlot` from 48 to `3 + 1(go) +
1(whereTo) + 1(spinControls) + 1(candidatesPanel) + 12 = 19`. Still above
§14.1's threshold of seven — because `MapBottomSlot` is a six-way dispatcher
and inherently needs the union of what every branch reads. Getting under
seven means either accepting a dispatcher is a different kind of function
than the ownership gate was written for, or applying §14.4 (a slot) so the
*caller* picks the branch and `MapBottomSlot` degenerates to the
`AnimatedContent` wrapper plus insets. That is a bigger, optional move —
flagged in "what this is bad at", not required for this proposal to land.

## A simplified example first

Toy composable, same shape as `MapBottomSlot`, small enough to hold in one
piece:

```kotlin
// Before: 11 parameters for what is really three concepts plus one flag.
@Composable
fun OrderPanel(
    customerName: String, customerEmail: String, customerPhone: String,
    shipStreet: String, shipCity: String, shipZip: String,
    subtotal: Double, tax: Double, total: Double,
    loading: Boolean, onSubmit: () -> Unit,
) { /* … */ }
```

Group by what changes together, not by type:

```kotlin
data class Customer(val name: String, val email: String, val phone: String)
data class ShippingAddress(val street: String, val city: String, val zip: String)
data class OrderTotals(val subtotal: Double, val tax: Double, val total: Double)

@Composable
fun OrderPanel(
    customer: Customer,
    shipping: ShippingAddress,
    totals: OrderTotals,
    loading: Boolean,
    onSubmit: () -> Unit,
) { /* … */ }
```

Built once, at the screen that owns the order form; read at the leaf that
draws each block. **Why three holders and not one `OrderState` bag:** per
`.claude/skills/detour-compose-state-hazards/SKILL.md` §6, "a snapshot read
is attributed to the nearest enclosing restartable scope" — the composable
function or non-inline lambda that performs the read, not the object itself.
If `OrderPanel` read one `OrderState(customer, shipping, totals, loading)`,
then a rider correcting a typo in `shipping.zip` recomposes `OrderPanel`'s
whole body — including whatever reads `totals` and `customer` — because the
read of the fat object is one snapshot read in one scope. Three narrow
holders put three separate reads in play; a component that only reads
`totals` never recomposes when `shipping` changes, because it holds no
reference to the field that changed. The parameter count is the visible
symptom; the recomposition scope is the actual reason this repo's own
guidance (§14.1: "related values travel together in one holder", not "every
value travels together in one holder") lands on many small holders.

## The real proposal, worked

### `MapBottomSlot`: two new holders, three reused

```kotlin
/** Everything the spin sheet's dials need. Built once by the caller from the
 *  five rememberSaveable values plus the two derived flags; SpinSheet stops
 *  taking them loose. */
internal data class SpinControls(
    val mode: TravelMode,
    val onSelectMode: (TravelMode) -> Unit,
    val radiusKm: Float, val onRadiusChange: (Float) -> Unit,
    val minRadiusKm: Float, val onMinRadiusChange: (Float) -> Unit,
    val poiKind: PoiKind, val onPoiKindChange: (PoiKind) -> Unit,
    val directionDeg: Float?, val onDirectionChange: (Float?) -> Unit,
    val spinning: Boolean,
    val onSpin: () -> Unit, val onCollapse: () -> Unit, val onTrack: () -> Unit,
)

/** The candidates card and its convoy-vote variant. */
internal data class CandidatesPanel(
    val candidates: List<RouteCandidate>,
    val convoyVotes: Map<RiderId, Int>?,
    val members: List<GroupMember>,
    val onPick: (Int, RouteCandidate) -> Unit,
    val onReroll: () -> Unit, val onCancel: () -> Unit,
    val onShare: (() -> Unit)?, val onGoWithLead: (() -> Unit)?,
)
```

`GoTarget`, `WhereTo` and `SheetToggle` need no changes — `MapBottomSlot`
takes them as parameters instead of the seven and five loose values it
currently reconstructs them from, and stops rebuilding a second `GoTarget`
worth of values (`onNavigateInApp`, `onNavigate`) to hand to `SpinSheet`
separately. `SpinSheet` and `NavigationDock` both take `go: GoTarget`; the
in-app-availability flag moves with it as `GoTarget.inAppAvailable`, already
a field, so `serverConfig` need not travel into `MapBottomSlot` at all —
the caller computes `inAppNavAvailable(...)` (already a pure function,
`HomeState.kt:69`) once, same as it computes `bottomCard` once.

Where it's built: `MapScreen.kt`, next to where `s.destination`, `radiusKm`
etc. already live — the same place `goTarget` is built today, just moved up
one level and given to the two new holders as well. Where it's read: each
`when` branch in `MapBottomSlot` takes only its own holder(s) plus its two or
three display values — exactly §8.4's "the extracted component takes the
owner and two or three display values."

### The four state machines, each to a named owner

`boundaries.md` §8.8 measured the reach: `spin()` touches ten mutable
variables, `startNavigation()` six, `selectMode()` writes six on one tap.
The pure decision logic behind all four already exists, tested, in
`app/.../map/`: `ModeSwitch.kt` (`ModeSwitchTest.kt`), `NavStart.kt`
(`NavStartTest.kt`), `SpinRun.kt` (`SpinRunTest.kt`) — and `CameraAuthority.kt`
(`CameraAuthorityTest.kt`) is further along than the other three: it is
already a reducer over a `State` class (`CameraAuthority.State`,
`CameraAuthority.reduce`), which is the exact shape the other three should
copy, not a new pattern to invent.

| Function | Writes today (`s.` / `retained.`) | Target owner |
| --- | --- | --- |
| `stopNavigation()` (`:778`) | `s.navigating`, `s.navProgress`, `s.route`; `retained.camTargetBearing`, `retained.snappedAt`; `mapOverlays.setDrivenFraction`, `BleNavServer.clear` | `NavigationSession.stop()` (new, screen-`remember`-scoped) for the `s.` fields; `RetainedMap` already owns the camera fields — call `retained.clearSnap()` (new method there); the two platform calls stay as caller-level side effects |
| `startNavigation()` (`:802`) | `s.camAuthority`, `s.navigating`, `s.route`, `s.rerouting`, `s.error` | `s.camAuthority` already goes through `CameraAuthority.reduce` — keep it. The rest to the same `NavigationSession`, built by calling the existing pure `navStart()` and applying its result |
| `spin()` (`:937`) | `s.spinJob`, `s.spinning`, `s.error`, `s.camAuthority`, `s.route`, `s.destination`, `s.destinationName`, `s.candidates` | `SpinSession` (new) for everything but `camAuthority`, which again stays on `CameraAuthority.reduce`. `SpinSession.run()` wraps the existing `runSpin()` call |
| `selectMode(m)` (`:1009`) | `radiusKm`, `minRadiusKm` (composable-local `rememberSaveable`), `s.route`, `s.candidates`, `Settings.setTripMode`, `ConvoyLiveClient.clearSpinOffer()` | **Already the least broken of the four** — the hard part (what a mode change invalidates) is `modeSwitch()`, pure and tested. What's left is applying one result to five different owners: a process store, two rotation-surviving locals, and two screen-scoped fields. That spread is real, not accidental — `radiusKm`/`minRadiusKm` must stay `rememberSaveable` in the composable (`.claude/skills/detour-compose-state-hazards/SKILL.md` §5: moving them into a plain-`remember` holder silently drops rotation survival) — so `selectMode` becomes a thin function that calls `modeSwitch()` and writes each result field to its own owner, not one method on one holder |

### §14.5's placement rule, applied to each new holder

`GoTarget`, `WhereTo`, `SpinControls`, `CandidatesPanel` all carry Compose
lambdas (`() -> Unit`) as fields — none can move to `shared/`, full stop, by
§8.7's own rule ("bucket 1 may stay out of `commonMain`"). They stay in
`app/.../ui/`, same as `GoTarget` and `WhereTo` do today. `NavigationSession`
and `SpinSession` are less clear-cut: their *fields* (route, destination,
candidates, error) are plain data, shareable in principle, but their
*methods* call Android-only things (`TripTrackingService.start`,
`scope.launch`, `haptics.performHapticFeedback`) directly. Per §5's rule
("a holder that is pure data plus pure derivation belongs in
`shared/presentation/`... a holder that touches Android types cannot"),
these stay in `app/` — the split is not "screen state is always local", it's
"a holder with an Android-touching method is local by necessity", the same
reasoning `boundaries.md` §8.7 gives for `Context`/`MapLibreMap`/
`withFrameNanos`.

## Sequencing

Ordered so the earliest stages are pure additions — a new type in
`app/.../ui/` or `shared/presentation/` with a test, consumed nowhere yet —
and free to revert independently:

1. **Rename/split the two misnamed files.** Mechanical: `TripHudState.kt` →
   `SpeedHudState.kt` + `ActiveTripCardState.kt`; `HomeState.kt` →
   `HomeBottomCard.kt` + `HomeIdlePredicates.kt` + `HomeShortcutPlaces.kt`.
   Same package, same tests move with the code — see `detour-file-split`.
   Zero behaviour change, compiler-verified.
2. **Write `SpinControls` and `CandidatesPanel`** as new files in
   `app/.../ui/`, each with the fields above and nothing else. Not consumed
   yet. Pure addition.
3. **Wire `MapBottomSlot` to take the five holders instead of the 40 loose
   values they replace**, keeping the 12 genuinely per-call params as
   parameters. This does change `MapBottomSlot`'s signature and every call
   site inside it, but not any effect, key list, or write site — a
   mechanical regrouping per §14.6 step 2, revertable by reverting the file.
4. **`CameraAuthority`-shaped holders for `NavigationSession` and
   `SpinSession`.** This is the behaviour-risking stage: it moves where
   `s.navigating`, `s.route`, `s.candidates` etc. are written from, even
   though the values and the order of writes are meant to be identical.
   Land `NavigationSession` and `SpinSession` separately — two commits, two
   A/B replays (below) — not as one "state machines" commit.
5. **`selectMode()`'s multi-owner writes stay last and stay a thin
   function**, not a holder method — there is no single owner to give it
   without also solving the `rememberSaveable`-vs-`remember` lifetime split,
   which is out of scope here (§12.3, per `state-holders.md:186`, is
   explicitly still open).

Stages 1-2 are pure additions: land them without a version bump per
`CLAUDE.md`'s "refactor... no bump" rule. Stages 3-5 change behaviour-facing
code paths and warrant the minor bump the same rule reserves for
backward-compatible features, even though nothing user-visible is added.

## What this is bad at

Argue against it, honestly:

- **More types to name.** Five new holders (`SpinControls`,
  `CandidatesPanel`, `NavigationSession`, `SpinSession`, plus whatever
  `RetainedMap.clearSnap()` needs) for one file. A reader who used to scan
  one 48-parameter signature and see everything now has to open five
  additional files to know what a branch touches.
- **The seven-parameter gate doesn't fully close on a dispatcher.**
  `MapBottomSlot` lands at ~19 parameters after every holder above is
  applied, not seven — because it is one function serving six mutually
  exclusive occupants. The honest fix for that specific residue is §14.4 (a
  slot, so the caller picks the branch and this function stops needing the
  union of all six) — a bigger, separately-decided move, not bundled here.
- **Holder proliferation is a judgment call, not a formula.** `state-holders.md`
  says as much (§14.7, "what tooling cannot enforce"): whether `SpinControls`
  is one real concept or "everything SpinSheet happens to read" is a review
  question, not something detekt's `LongParameterList` can settle. Two
  reviewers could reasonably draw the `SpinControls`/`CandidatesPanel`
  boundary differently — e.g. `onTrack` is a NavApp launch concern, not a
  spin concern, and arguably belongs with `GoTarget` instead.
- **A holder built one level up and never touched again reads like
  ceremony.** `SheetToggle`, `HomeBottomCard`, `NavState` are already this —
  one field's worth of behaviour wrapped in a type (§14.2: "three or more
  values that change together"). The two new ones here (13 and 8 fields) are
  comfortably past that bar; nobody should mistake this for "wrap everything."
- **§14.7's own baseline count is stale.** `state-holders.md:159` cites 18
  `LongParameterList` baseline entries; `config/detekt/baseline-app.xml` has
  **17** now (`grep -c`). One violation was fixed and the doc wasn't updated —
  the same drift found in `boundaries.md`'s "46". Re-run the grep before
  trusting either number, including this one, next time.

## Verification

This restructures where state lives, not just which package a file sits in.
Unlike the packaging moves, **the compiler cannot prove this inert.** A
signature can typecheck while an effect's key list, a `rememberSaveable`
lifetime, or a `derivedStateOf` boundary silently changes underneath it —
exactly the failure class `.claude/skills/detour-compose-state-hazards/SKILL.md`
exists to catch, and exactly why §3 of that skill says a state-holder
extraction earns a GPS replay, not a compile.

What a reviewer should demand, by stage:

- **Stages 1-3 (renames, new unconsumed holders, `MapBottomSlot` regrouping):**
  a green `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` and a
  diff review confirming no `LaunchedEffect`/`DisposableEffect` key list
  changed and no `remember` became a class field or vice versa. `detour-file-split`'s
  same-package, zero-added-lines proof applies to stage 1 directly.
- **Every new pure builder or holder-construction function** gets a
  `commonTest`/`app/src/test` test in the shape of `SpinStateTest.kt` — one
  class per subject, a `private fun` fixture builder, full-sentence test
  names, no mocking (`testing.md` §10). `SpinControls`/`CandidatesPanel` are
  plain data classes with no logic of their own and don't need one; the
  functions that build them from `s`'s fields do.
- **Stage 4 (`NavigationSession`, `SpinSession`)** is where behaviour can
  actually drift, because it moves write sites for `s.route`, `s.candidates`,
  `s.navigating` etc. Demand the `.claude/skills/detour-gps-replay/SKILL.md`
  A/B protocol: same route file, same `intervalMs`, before/after. Name the
  quantity before looking — for a navigation-session change that's the
  recorded trip's `distanceMeters`/`topSpeedMps` plus whether navigation
  starts and stops on the same fixes as before; for a spin-session change,
  candidate count and content from an identical `SpinParams` seed. "Behaviour
  looked unchanged" is explicitly not acceptable per that skill; a reviewer
  should ask for the two numbers and the route file, not a screenshot.
- **Every stage** re-runs `detour-compose-state-hazards`'s precondition
  script and its own checks: the `rememberUpdatedState` count must not drop
  unexplained (§2), a `DisposableEffect`'s listener registration and removal
  must move together if its key list changes (§2b), and any coroutine-local
  accumulator being extracted into a holder needs a characterisation test
  *first*, not after (§3).
