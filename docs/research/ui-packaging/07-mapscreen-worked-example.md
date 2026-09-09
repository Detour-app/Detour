# MapScreen, worked: what it does, where the pieces live, and what packaging changes

A concrete answer to "take the map screen and show the rules applied to it."
Read this after `00-summary.md`. Every claim below cites a `file:line` against
commit `efa6c9ad` (2026-09-09) — where the research docs disagreed with the
code, the code wins; the disagreements found are called out inline.

## The one thing to hold onto

**The packaging refactor does not shrink `MapScreen.kt`.** It is 1,353 lines
before this work and 1,353 lines after it, under every one of the three
proposals. It repackages files that a *different*, *already-finished* refactor
chain created. Whether it should get smaller at all is a separate,
state-ownership question — `docs/refactor/mapscreen/DECISION.md` and
`boundaries.md` §8.4 — gated on giving four state machines an owner, not on
where their files live. Packaging is this document's subject; shrinking
`MapScreen.kt` is not in scope and nothing below should read as claiming it is.

---

## 1. What MapScreen is responsible for, today

Reading `MapScreen.kt` top to bottom (`app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`),
these are the concerns actually present. "Extracted" means the composable or
function lives in its own file already; "inline" means the logic is still
written directly in `MapScreen`'s 1,209-line body (`MapScreen.kt:144-1353`).

| Concern | What it does | Status |
|---|---|---|
| Screen-lifetime state | `MapScreenState` — spin result, nav progress, camera authority, tapped rider, dialogs (`MapScreen.kt:175`) | Extracted |
| Saved-across-death radius/direction/collapse | 5 `rememberSaveable` locals kept deliberately outside the state holder (`MapScreen.kt:168-186,272`) | Inline |
| App-session state | `RetainedMap` — the `MapView`, camera targets, hazard-tracker state, survives navigation away (`MapScreen.kt:148,335-338`) | Extracted (`RetainedMap.kt`) |
| Location fetch & permission grant | `fetchLocation()`, `onLocationGranted()`, background-disclosure gating (`MapScreen.kt:455-499`) | Inline |
| Permission launchers | Three `ActivityResultContracts` launchers and their effects (`MapScreen.kt:503-508`) | Extracted (`MapPermissions.kt`) |
| Camera authority (follow/park/gesture) | Touch-listener gesture parking, drive-off resume watch, north-up gate (`MapScreen.kt:379-453`) | Inline (dispatch sites); rule lives in `map/CameraAuthority` |
| Camera easing & position marker | Per-frame `withFrameNanos` loops | Extracted (`MapCamera.kt`) |
| Overlay render (route/candidates/position/icon/colour) | One `LaunchedEffect` pushing state into `MapOverlays` (`MapScreen.kt:589-629`) | Inline (effect); renderer lives in `MapLibreMap.kt` |
| Fog of war | Two effects feeding `FogView` (`MapScreen.kt:635-655`) | Inline (effects); drawing in `FogView.kt` |
| Map gesture listeners | Long-press pin drop, tap-to-pick/rider-card, layers-panel dismiss (`MapScreen.kt:671-729`) | Inline |
| Spoken guidance | `NavVoice`/`NavAnnouncer` lifecycle, `announceAloud()`, mute wiring (`MapScreen.kt:740-776`) | Inline |
| Hazard prefetch | Two Overpass fetches (speed cameras, trajectcontrole sections) | Extracted (`MapHazardPrefetch.kt`) |
| Hazard alerts | Camera chime + running section average, spoken | Extracted (`MapHazardAlerts.kt`) |
| Circle-member markers | Poll + draw for a circle's shared positions | Extracted (`MapCircleMembers.kt`) |
| Convoy / group-spin orchestration | Peer/vote/offer collectors, `commitSpinCandidate`, round-outcome effect (`MapScreen.kt:221-249,553-585`) | Inline |
| Navigation session | Progress, arrival, reroute, external display | Extracted (`MapNavigation.kt`) |
| `startNavigation`/`stopNavigation` | Route fetch-or-reuse, trip start/stop, camera-authority dispatch (`MapScreen.kt:778-836`) | Inline |
| Spin (roulette) & mode switch | `spin()` touches 10 mutable vars, `selectMode()` touches 6 (`MapScreen.kt:937-1025`) | Inline |
| Rider card | Tap-to-inspect a convoy peer or circle member (`MapScreen.kt:1027-1067`) | Extracted (card itself, `RiderCard.kt`); derivation inline |
| Bottom-slot routing | Home/drive/nav/candidates/spin sheet dispatch on one slot | Extracted (`MapBottom.kt`) |
| Top chrome | Follow toggle, layers panel, convoy pill, face-north | Extracted (`MapChrome.kt`) |
| HUD | Speed, posted limit, running average, OBD2-lost label, push-to-talk | Extracted (`MapHud.kt`) |
| Home/drive/nav/spin sheets | The five bottom-slot occupants | Extracted (`HomeSheet.kt`, `RideSheet.kt`, `SpinCards.kt`, `NavigationDock.kt`, `CandidatesCard.kt`) |
| Dialogs | Background-location disclosure, save-pin | Extracted (`MapDialogs.kt`) |
| Scaffold/layout assembly | The `Scaffold`/`Box`/`AnimatedVisibility` stack wiring all of the above together | Inline (`MapScreen.kt:1069-1353`) — this *is* the composable's job |

`boundaries.md` §8.8 counts this as **fourteen concerns across three
lifetimes** (process, app-session, screen). The table above is finer-grained
than that count but agrees with it: MapScreen is not one concern pretending to
be many files, it is genuinely many concerns, most of which already have their
own file — the exceptions are the ones with no state owner (see §4c).

## 2. Where each concern lives now

| File | Lines | Owns |
|---|---:|---|
| `MapScreen.kt` | 1353 | Orchestration, four un-owned state machines (spin, nav start/stop, mode switch, gesture dispatch) |
| `MapScreenState.kt` | 116 | Screen-lifetime state: spin result, nav progress, camera authority, chrome flags, error |
| `RetainedMap.kt` | 268 | Activity-lifetime state: `MapView`, camera targets, speed/limit/section trackers |
| `DriveClockLocal.kt` | 52 | Process-lifetime `CompositionLocal`: the one drive clock, read by 5 composables without a parameter |
| `SpinResultHolder.kt` | 99 | Process-lifetime `MutableStateFlow`: spin/destination survive Activity recreation |
| `MapPermissions.kt` | 105 | Permission launchers + effects |
| `MapCamera.kt` | 456 | Per-frame camera-ease and position-marker loops |
| `MapCameraTuning.kt` | 125 | Pure tuning constants/math, also used by `car/CarMapRenderer.kt` |
| `MapHazardPrefetch.kt` | 184 | Overpass prefetch for cameras/sections |
| `MapHazardAlerts.kt` | 141 | Camera chime + section-average readout |
| `MapCircleMembers.kt` | 62 | Circle-member marker poll/draw |
| `MapNavigation.kt` | 141 | Progress/arrival/reroute/external display |
| `MapLibreMap.kt` | 622 | `MapOverlays` — the renderer, shared with `car/` |
| `FogView.kt` | 614 | Custom `View`, canvas fog-of-war |
| `MapChrome.kt` | 200 | Top rail: follow, layers, convoy pill |
| `MapBottom.kt` | 275 | `MapBottomSlot` — the bottom-slot router |
| `MapHud.kt` | 219 | Speed/limit/average HUD, OBD2 label, push-to-talk |
| `HomeSheet.kt` | 422 | Resting bottom sheet |
| `RideSheet.kt` | 457 | Drive/nav sheets, `GoTarget`/`WhereTo`/`SheetToggle` holders |
| `SpinCards.kt` | 362 | Spin sheet, result callout |
| `SpinShare.kt` | 48 | Convoy candidate mappers |
| `CandidatesCard.kt` | 182 | Candidate list card |
| `NavigationDock.kt` | 166 | Navigate-to-destination dock |
| `NavAppLaunch.kt` | 248 | External nav-app launch (Maps/Waze/geo), `NavButton` |
| `Navigation.kt` | 266 | Nav banner, progress track, speed-limit sign |
| `RiderCard.kt` | 95 | Tapped-rider info card |
| `SearchIsland.kt` | 370 | Destination search, shared by home + drive sheets |
| `MapDialogs.kt` | 118 | Background-location disclosure (also used by `SettingsScreen.kt`), save-pin dialog |

`MapScreenState` and `RetainedMap` are the two concrete answers to "what
about state": one screen-scoped, one Activity-scoped, per `boundaries.md`
§8.1's lifetime test. `DriveClockLocal` and `SpinResultHolder` are the other
two lifetimes this cluster actually needs (process-scoped, one `remember`,
one `CompositionLocal`, one `StateFlow`) — four different owners for four
different lifetimes, not one.

## 3. What the previous refactor already did

`docs/refactor/mapscreen/DECISION.md` records six stages that took
`MapScreen.kt` from **3,204 lines** (one 1,419-line function, 59 `remember`s,
35 effects, zero tests) down to **1,263 lines**, and `ui/` from **1 monolith
file to 55**. Stage 6 introduced `MapScreenState` itself. Along the way four
pure decisions (`ModeSwitch`, `NavStart`, `SpinRun`, `PermissionPolicy`) and
three road-hazard machines moved to `shared/`, gaining 166 tests and — the
result DECISION.md calls "the one worth remembering" — iOS parity on features
that were previously welded into a composable.

Two things are worth being precise about, because the numbers keep moving:

- `MapScreen.kt` is 1,353 lines today, 90 more than DECISION.md's post-chain
  1,263 — feature work landed on top of the chain's endpoint (push-to-talk,
  OBD2 signal-lost label, convoy spin voting) since it closed.
- `boundaries.md` §8.4 and `state-holders.md` §14.5 both cite `MapBottomSlot`
  at **46 parameters** and a `SpinDock` at **19**. Neither is current:
  `MapBottomSlot` is now **48** (§4c below), and `SpinDock.kt` no longer
  exists — it was renamed to `NavigationDock` and, in the same stage, cut to
  **5** parameters by introducing the `GoTarget` holder (`RideSheet.kt:85-93`).
  That is a real, already-applied instance of §8.4's own prescribed fix,
  inside this exact cluster — proof the rule works, and a guideline citation
  that is stale in the *optimistic* direction for once.

The 26 sibling files in the table above exist because of this chain, not by
accident. The packaging refactor's job is to decide where those 26 files (27
with `MapScreen.kt`) *live* — nothing about what they *are*.

## 4. What the packaging refactor does to this cluster, concretely

### Before → after tree

```
# today — flat, 59 files, one of many mixed into the same directory
ui/
  MapScreen.kt (1353)  MapScreenState.kt (116)  RetainedMap.kt (268)
  MapCamera.kt (456)   MapCameraTuning.kt (125)  MapBottom.kt (275)
  MapChrome.kt (200)   MapHud.kt (219)   MapDialogs.kt (118)
  MapHazardAlerts.kt (141)  MapHazardPrefetch.kt (184)
  MapCircleMembers.kt (62)  MapNavigation.kt (141)  MapPermissions.kt (105)
  MapLibreMap.kt (622)  FogView.kt (614)  HomeSheet.kt (422)
  RideSheet.kt (457)  SpinCards.kt (362)  SpinShare.kt (48)
  SpinResultHolder.kt (99)  CandidatesCard.kt (182)  NavigationDock.kt (166)
  NavAppLaunch.kt (248)  Navigation.kt (266)  RiderCard.kt (95)
  DriveClockLocal.kt (52)  SearchIsland.kt (370)
  ... 32 unrelated files (SettingsScreen.kt, FriendsScreen.kt, ...)

# proposal C — 27 files land in one feature package, nothing else moves in
features/mapscreen/
  components/  (21: MapScreen, MapBottom, MapCamera, MapChrome,
                MapCircleMembers, MapDialogs [minus BackgroundLocationDisclosure],
                MapHazardAlerts, MapHazardPrefetch, MapHud, MapLibreMap,
                MapNavigation, HomeSheet, RideSheet, SpinCards, CandidatesCard,
                NavigationDock, NavAppLaunch, Navigation, RiderCard,
                SearchIsland, FogView)
  state/       (4: MapScreenState, MapPermissions, DriveClockLocal, RetainedMap)
  utils/       (2: MapCameraTuning, SpinShare)
components/
  BackgroundLocationDisclosure.kt   <- extracted out, used by 2 features
state/
  SpinResultHolder.kt               <- NOT here; see correction below
```

**Correction to the task's own premise.** "26 sibling files + `MapScreen.kt`
= 27" does not hold file-for-file. Proposal C's actual 27-file list
(`proposal-c-react-informed-nesting.md:979-994`) swaps two files relative to
what a naive reading of the sibling-file list suggests: `SpinResultHolder.kt`
is **not** one of the 27 — it moves to a separate top-level `state/` package
because `RoutesScreen.kt` (a different feature) also reads it
(`SpinResultHolder.kt:… seedRouteNavigation`, called from `RoutesScreen.kt`).
`NavAppLaunch.kt` (248 lines, `NavButton`/`NavMenuItems`, used only from
`SpinCards.kt`, `RideSheet.kt`, `NavigationDock.kt` — all mapscreen files)
takes its place. The cluster is 26 files besides `MapScreen.kt`, plus
`MapScreen.kt` itself — 27 in total.

### 4a. A pure file move

`MapLibreMap.kt` (622 lines: `MapOverlays`, `NamedFriendPosition`,
`PositionMarker`, `openFreeMapStyleUrl`, `setCamera`) is used by `MapScreen.kt`
and, genuinely, by `car/CarMapRenderer.kt`. Nothing in it depends on anything
else moving. The whole file is untouched except its package line:

```kotlin
// before: app/src/main/java/com/jellemax/detour/ui/MapLibreMap.kt
package com.jellemax.detour.ui

// after: app/src/main/java/com/jellemax/detour/features/mapscreen/components/MapLibreMap.kt
package com.jellemax.detour.features.mapscreen.components
```

The one real call site outside the cluster gains real import lines
(`car/CarMapRenderer.kt:40-44` today reads `com.jellemax.detour.ui.MapOverlays`,
`.NamedFriendPosition`, `.PositionMarker`, `.openFreeMapStyleUrl`,
`.setCamera` — after the move, each becomes
`com.jellemax.detour.features.mapscreen.components.<Name>`). Zero lines
change inside either file's body.

### 4b. An extraction

`BackgroundLocationDisclosure` (`MapDialogs.kt:28-53`) has two real callers:
`MapDialogs.kt:97` (its own file, inside `MapScreenDialogs`) and
`SettingsScreen.kt:393`. Today both are in package `com.jellemax.detour.ui`,
so `SettingsScreen.kt` needs no import at all — same-package visibility is
carrying the sharing invisibly. Two files change:

```kotlin
// MapDialogs.kt loses the declaration, keeps the caller:
package com.jellemax.detour.ui  // -> com.jellemax.detour.features.mapscreen.components
import com.jellemax.detour.components.BackgroundLocationDisclosure  // new

// SettingsScreen.kt (-> features/settings/components/) needs the same new import,
// where today it needed none:
import com.jellemax.detour.components.BackgroundLocationDisclosure
```

A third file is created — `components/BackgroundLocationDisclosure.kt` — with
the function's body copied verbatim. This is the mechanism `01-ui-inventory.md`
already flags as a "trapped shared component": it looks shared today only
because grep for its name still finds one hit per caller; nothing marks that
it crosses a screen boundary until the package line is forced to say so.

### 4c. The blocked case

```kotlin
// app/src/main/java/com/jellemax/detour/ui/MapBottom.kt:56
internal fun BoxScope.MapBottomSlot(
    stats: TripStats?, onEndTrip: () -> Unit, rideToggle: SheetToggle,
    savedPlaces: List<SavedPlace>, destination: LatLon?, destinationName: String?,
    route: RouteResult?, myLocation: LatLon?, serverConfig: ServerConfig,
    username: String, onOpenHub: () -> Unit, searchOpen: Boolean,
    /* ...36 more... */
    onClearDestination: () -> Unit,
)
```

Counted directly against the file today: **48 parameters** — two more than
the 46 `boundaries.md` §8.4 cites, because the file has kept growing since
that count was taken. `SpinSheet` (`SpinCards.kt:107`) takes **24**
(`mode` through `modifier`), matching the "23-24" the task brief expected.

§8.4's gate: *"if pulling a piece out requires threading more than seven
parameters, you have not found a concern boundary — you have found a
state-ownership problem."* Moving `MapBottom.kt` and `SpinCards.kt` into
`features/mapscreen/components/` changes their package line and **nothing**
about these signatures — packaging is not able to shortcut this, and no
proposal claims otherwise. What would have to happen first: the ~10
`rememberSaveable`/derived locals in `MapScreen.kt` that `MapBottomSlot`
currently receives one-by-one (`radiusKm`, `minRadiusKm`, `poiKind`,
`directionDeg`, `mode`, `spinning`, `error`, plus the spin-related callbacks)
need an owner — the same fix `§4.2`'s `GoTarget` already gave the 14 values
`NavigationDock` used to take one-by-one. Until that owner exists, per §8.4:
*"leave the file long and say why in a comment."*

## 5. What changes and what does not

| | Package line | File contents | Size |
|---|---|---|---|
| `MapLibreMap.kt`, `MapCamera.kt`, `MapChrome.kt`, `MapHud.kt`, `MapCircleMembers.kt`, `MapHazardAlerts.kt`, `MapHazardPrefetch.kt`, `MapNavigation.kt`, `HomeSheet.kt`, `RideSheet.kt`, `SpinCards.kt`, `CandidatesCard.kt`, `NavigationDock.kt`, `NavAppLaunch.kt`, `Navigation.kt`, `RiderCard.kt`, `SearchIsland.kt`, `FogView.kt`, `MapScreen.kt` | Changes | Unchanged | Unchanged |
| `MapScreenState.kt`, `MapPermissions.kt`, `DriveClockLocal.kt`, `RetainedMap.kt`, `MapCameraTuning.kt`, `SpinShare.kt` | Changes | Unchanged | Unchanged |
| `MapDialogs.kt` | Changes | Loses `BackgroundLocationDisclosure` (26 lines) | Shrinks by ~26 |
| `SettingsScreen.kt` | Changes (different feature package) | Gains 1 import | +1 |
| `components/BackgroundLocationDisclosure.kt` | New file | New (copied verbatim) | +26, new |
| `SpinResultHolder.kt` | Changes (`ui` → top-level `state`) | Unchanged | Unchanged |
| `car/CarMapRenderer.kt` | Unchanged | Gains ~7 import lines | +7 |
| Every other file in the app not named above | Unchanged | Unchanged | Unchanged |

**`MapScreen.kt`: 1,353 lines before, 1,353 lines after.** No file in this
cluster is smaller or larger because of the packaging move itself, except the
one deliberate extraction in the row above. That extraction is optional and
separately committed under every proposal, same as the discipline
`detour-file-split` requires: extraction and repackaging never share a commit.

## 6. What is still wrong afterwards

- **`MapScreen.kt` is still 1,353 lines**, still over the 1,000-line hard
  limit in `boundaries.md` §8.3, and still one composable holding four
  un-owned state machines (`spin()`, `startNavigation`/`stopNavigation`,
  `selectMode()`, the map gesture-listener dispatch). Packaging does not
  touch any of them.
- **`MapBottomSlot` still takes 48 parameters, `SpinSheet` 24.** Both are
  exactly as wide after the move as before it. The fix is `docs/refactor/`
  work gated on an owner, not a packaging decision — §4c.
- **A terminology correction first, because it changes what "grouping helps"
  even means.** `nav/Destination.kt`'s own KDoc defines a destination as "every
  place the phone UI can be, as a key on an owned back stack" — one back-stack
  key. There are 22: 15 `Destination` entries plus 7 `SettingsSpoke`s (verified
  directly against the file; issue #184's "13 destinations" is stale). But
  proposal C groups these into **14 feature packages**, not 22, and the ratio
  runs both ways: `features.settings` holds **8 destinations** (`Settings`
  plus all 7 spokes) in 7 files, every one of the 8 rendered by one composable,
  `SettingsSpokeScreen` (`SettingsScreen.kt:173`); `features.circles` holds
  **2 destinations** (`Circles`, `CircleDetail`) in 1 file
  (`CirclesScreen.kt`); the other 11 packages hold 1 destination each. So the
  organising unit is not "a destination's package" — it is a **feature area**:
  a set of destinations whose implementation files already overlap enough that
  splitting them would cut through a shared file. Two consequences follow, and
  a reader deciding whether this axis is worth doing should have both. First,
  the placement test as usually stated — "used by one destination, put it in
  that destination's package" — is undecidable for `SettingsScreen.kt`, which
  serves eight; it only becomes answerable once the feature-area groups are
  written down explicitly, which is a judgment call, not a derivation. Second,
  there is a mild circularity in that judgment call: `Circles`/`CircleDetail`
  get grouped *because* one file already serves both, so the partition is
  partly derived from the current file layout — the thing being reorganised.
  Two people could reasonably draw `settings` as one package or as eight.
- **MapScreen is the sharpest illustration of that, and it argues against the
  grouping for this one cluster.** `Map` is a single destination — one
  back-stack key, `Destination.Map` — and every one of the 27 files in
  `features/mapscreen/` already serves only that one key. There is no second
  destination here to fold in, unlike Settings (8 destinations, 1 file) or
  Circles (2 destinations, 1 file). So for this specific cluster,
  `features/mapscreen/` is not partitioning anything: nothing in it was mixed
  with another destination's files before the move, and nothing is separated
  from anything else by the move. Compare it with `features/settings/`
  (proposal C, same document): Settings' 7 files split cleanly into a
  populated `components/` and two *empty* `state/`/`utils/` tiers, because
  Settings has no state holder to place — every section reads a store
  directly. Map's `state/` tier is the one populated on day one, precisely
  because nearly the entire 27-file cluster already has real, named state
  holders (`MapScreenState`, `RetainedMap`, `DriveClockLocal`,
  `MapPermissions`) sitting right next to the composables that use them —
  which the cluster's naming (everything already prefixed
  `Map…`/`Spin…`/`Ride…`) and its call-site pattern (near-total
  single-caller-from-`MapScreen.kt`) already say without a directory saying
  it again. The honest answer to "does grouping help here": **less than
  anywhere else in the app** — Settings' empty tiers expose a real gap (no state
  holders exist there yet); Map's populated tiers mostly relabel work already
  done. The clearest win in this cluster (§4b, the trapped
  `BackgroundLocationDisclosure`) is not about the map at all — it's about a
  Settings caller finally getting a real import instead of a same-package
  accident.
- **Two research documents were stale, in the codebase's favour and against
  it.** `01-ui-inventory.md` claims no average-speed HUD composable exists;
  `SpeedHud` (`MapHud.kt:121-205`) already renders it, landed two days before
  the inventory was captured. `boundaries.md`/`state-holders.md` cite
  `MapBottomSlot` at 46 parameters and a `SpinDock` at 19; the file is now at
  48, and `SpinDock` no longer exists — renamed to `NavigationDock` and cut to
  5 parameters via the `GoTarget` holder, which is the fix §8.4 asks for,
  already shipped, in this exact cluster.
