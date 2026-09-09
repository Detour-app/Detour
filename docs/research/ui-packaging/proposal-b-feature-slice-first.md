# Proposal B: feature-slice-first for `ui/`

Companion to a sibling proposal that organizes `ui/` by component type under a
`base/` library. This proposal takes the opposite axis. Evidence citations are
to `01-ui-inventory.md`, `02-repo-guidelines.md`, `03-move-cost.md`,
`04-android-compose-precedent.md`, `05-kotlin-package-idiom.md` (all in this
directory) unless a repo path is given directly.

## The axis, in one paragraph

The unit of organization is the navigation destination, not the component
type: a file's package is the lowercase name of the single `Destination` (or
`SettingsSpoke`) it renders, declared today at
`app/src/main/java/com/jellemax/detour/nav/Destination.kt:43-149`, and a
declaration lives in that package — in the same file as its caller when it has
exactly one, in a sibling file in the same package when a second file in that
destination needs it — until it is called from a second destination's package,
at which point it moves to one shared package, `com.jellemax.detour.ui.shared`,
kept small on purpose. Nothing else earns a package.

## The placement test

Run this over one declaration at a time. It reuses 01's own method (§ "Method
for call-site counts"), so it produces the same answer 01 already computed for
every symbol in the directory — this is not a new taxonomy layered on top of
the inventory, it is the inventory read as a placement rule.

1. **Is it the top-level composable a `Destination` or `SettingsSpoke` is
   routed to** (checked against `MainActivity.kt`'s `entry<Destination.X>`
   table, the same check 01 §Header used)? → its file defines that
   destination's package. Package name = the destination's name, lowercased
   (`Hub` → `ui.hub`), with one exception: `Destination.Map` gets
   `ui.mapscreen`, not `ui.map`, because `com.jellemax.detour.map` already
   exists as a flat sibling package (pure decisions — `NavPolicy`,
   `FollowCamera`, `CameraAuthority` — created in stage 2 of the MapScreen
   refactor, `02-repo-guidelines.md` "Correction on `map/`"); reusing "map" as
   a second-level segment under `ui/` would read as the same name twice at a
   glance even though the fully-qualified names never collide.
2. **Is it `private`, or does its only caller sit in the same destination's
   package** (by rule 1)? → same package as its caller. If it is declared in
   the same file as an already-placed caller, it moves with that file
   untouched (`boundaries.md` §8.5 already licenses a public composable
   sharing a file with the private helpers only it uses — that permission is
   unaffected by which package the file sits in).
3. **Does it have callers in 2 or more distinct destination packages** (by
   rule 1)? → `ui.shared`. This is the only number in the test, and it is the
   same "distinct calling files" count 01 §A already ran — a file is not
   "shared" for having many call sites, only for having callers in more than
   one destination.
4. **Is its only non-`MainActivity` consumer outside `ui/` entirely** — `car/`,
   `app/…/map/`, `tracking/`, or `app/src/test`? → rule 3 does not fire on
   that consumer (it isn't a `ui/` destination). The declaration stays in
   whichever destination package rule 1/2 already gave it; the outside
   consumer's import line is updated to the new path and nothing else about
   it changes. `internal` visibility is module-scoped, not package-scoped
   (`03-move-cost.md` §4.2, `05-kotlin-package-idiom.md` §5), so nesting
   changes zero visibility outcomes for these consumers — only the import
   path.

Two people running this over the same symbol get the same answer because
every step resolves to a count or a lookup already on record in 01, not a
judgment call — except rule 3's boundary case, addressed honestly in "What
this axis is bad at" below.

Applied mechanically to 01 §A's own table, this test reproduces almost exactly
the set of files 01 already labeled SHARED COMPONENT by call-count — `Cards.kt`,
`AppBar.kt`, `GlassSurface.kt`, `ConfirmDialog.kt`, `Theme.kt`, `Pills.kt`,
`TravelModeIcon.kt`, `Format.kt` — which is the check that this placement test
is decidable rather than a rebranding of the same 59 files.

## A simplified, abstract example

Invented names, a toy two-destination app, to make the mechanics unmistakable
before any real code.

**Before** — flat, one package:

```
ui/
  AlphaScreen.kt   // fun AlphaScreen(), routed at Destination.Alpha
  BetaScreen.kt    // fun BetaScreen(),  routed at Destination.Beta
  Badge.kt         // fun WidgetBadge(count: Int) — called from both screens
```

```kotlin
// Badge.kt
package com.example.app.ui

fun WidgetBadge(count: Int) { /* draws a small pill */ }
```

```kotlin
// AlphaScreen.kt
package com.example.app.ui

private fun alphaLabel(count: Int) = "Alpha: $count"

fun AlphaScreen() {
    WidgetBadge(count = 3)   // no import — same package
}
```

```kotlin
// BetaScreen.kt
package com.example.app.ui

fun BetaScreen() {
    WidgetBadge(count = 7)   // no import — same package
}
```

`WidgetBadge` has 2 distinct callers, `AlphaScreen.kt` and `BetaScreen.kt` —
two different destinations by rule 1. `alphaLabel` has exactly one caller,
inside its own file — rule 2.

**After** — one package per destination, one shared package:

```
ui/
  alpha/AlphaScreen.kt
  beta/BetaScreen.kt
  shared/Badge.kt
```

```kotlin
// alpha/AlphaScreen.kt
package com.example.app.ui.alpha

import com.example.app.ui.shared.WidgetBadge   // new — did not exist before

private fun alphaLabel(count: Int) = "Alpha: $count"   // moved untouched, still private, still same-file

fun AlphaScreen() {
    WidgetBadge(count = 3)
}
```

```kotlin
// beta/BetaScreen.kt
package com.example.app.ui.beta

import com.example.app.ui.shared.WidgetBadge   // new — did not exist before

fun BetaScreen() {
    WidgetBadge(count = 7)
}
```

```kotlin
// shared/Badge.kt
package com.example.app.ui.shared

fun WidgetBadge(count: Int) { /* unchanged */ }
```

Three things to notice, each of which recurs in the real examples below:

- `alphaLabel` needed zero changes beyond travelling with its file — private,
  single-caller declarations are free under this axis, exactly as they are
  under the flat one.
- Both `AlphaScreen.kt` and `BetaScreen.kt` gained an import line neither
  needed before, for a symbol that did not move logically, only across a
  package line drawn between them.
- `WidgetBadge` itself did not change one character of its body. Only its
  `package` line changed, because rule 3 fired the moment a second destination
  needed it.

## The real examples, worked end to end

### 1. The Settings cluster

`Destination.Settings` plus the 7-member `SettingsSpoke` sealed interface
(`nav/Destination.kt:106-148`) are rendered by `SettingsHub.kt` (the hub) and
`SettingsScreen.kt` (`SettingsSpokeScreen`, the 6-branch `when` dispatching to
each spoke's section — `SettingsScreen.kt:173-204`), plus two files that are
spoke content but read as their own screens: `SettingsServers.kt`
(`ServersSyncSpoke`, single caller `SettingsScreen.kt:200`) and
`Obd2PairingScreen.kt` — which, per 01 Anomaly 3, is **not** a route despite
its name; its only caller is `SettingsScreen.kt:203`
(`Destination.SettingsObd2 -> Obd2PairingScreen()`), the same structural role
as the private, unexported `NavigationSection()`/`FogSection()` branches
beside it. `SettingsDiagnostics.kt`, `SecureFields.kt`, and
`UpdateProgressButton.kt` (single caller `SettingsHub.kt:167`) complete the
cluster. Every one of these files' only external caller is another file in
this same cluster or `MainActivity.kt`'s 7 `entry<Destination.SettingsXxx>`
registrations (`MainActivity.kt:451-479`) — one destination-family, by rule 1,
so the whole cluster becomes one package, `com.jellemax.detour.ui.settings`,
with **no internal split** despite covering 7 distinct `SettingsSpoke` values:
nesting a second level (`ui.settings.fog`, `ui.settings.obd2`, …) would be the
same per-variant mistake `05-kotlin-package-idiom.md` §5-6 documents against
the sibling proposal's `base/button/confirm/` — one level of feature nesting,
not two, and no precedent anywhere in this repo or the five external repos
04 surveyed goes past one level either.

This packaging move does not fix 01 Anomaly 4 — the file named
`SettingsScreen.kt` declares `SettingsSpokeScreen`, not `SettingsScreen`, and
the actual `SettingsScreen` composable lives in `SettingsHub.kt:45`. Moving
both files into the same `ui.settings/` folder makes the mismatch more visible
(a reader opens one folder instead of scanning 59 files) but does not resolve
it; a rename (`SettingsHub.kt` keeps `SettingsScreen`; `SettingsScreen.kt` →
`SettingsSpokeScreen.kt`) is a separate, optional, zero-risk fix that could
ride in the same commit as the repackage since neither the rename nor the
package-line change alters a single line of logic — but it is a judgment call
this axis does not force.

Before, in `SettingsServers.kt:1`:

```kotlin
package com.jellemax.detour.ui
```

After:

```kotlin
package com.jellemax.detour.ui.settings
```

`MainActivity.kt:451-479`'s 7 `entry<Destination.SettingsXxx>` registrations
and its `Destination.Settings` registration import `SettingsScreen` (from
`SettingsHub.kt`) and `SettingsSpokeScreen` (from `SettingsScreen.kt`) today
under `com.jellemax.detour.ui.*`; each import line becomes
`com.jellemax.detour.ui.settings.SettingsScreen` /
`...settings.SettingsSpokeScreen`. That is the entire external blast radius —
no other `ui/` file, `car/` file, or test imports anything from this cluster
(confirmed against 01's per-file evidence and 03 §2-3's complete outside-`ui/`
consumer lists, neither of which names any Settings file).

### 2. History and TripDetail — the cluster that forces a real split

`HistoryScreen.kt` renders `Destination.History` (`MainActivity.kt:395-401`)
but also declares `tripStatLine` (`HistoryScreen.kt:502`), `tripBehaviorLine`
(`:518`), `tripFuelEconomyLper100Km` (`:539`), `loadTripTrace` (`:171`),
`loadTripPoints` (`:177`), `matchTripPoints` (`:132`, `internal`), and
`readTraceSegments` (`:85`, `private`) — a trip-formatting utility module
riding inside a screen file (01 Anomaly 6). Three of those —
`tripStatLine`/`tripBehaviorLine`/`tripFuelEconomyLper100Km` — are called from
`TripDetailScreen.kt:521,531,606`, and two more —
`loadTripPoints`/`loadTripTrace` — from `TripDetailScreen.kt:223` and from
`tracking/TripSession.kt:16` (03 §3.1, outside `ui/` entirely). `matchTripPoints`
and `readTraceSegments` have exactly one caller each today: `readTraceSegments`
is called only by `matchThumbnails` (`HistoryScreen.kt:149`, itself
`private`, single-caller: `HistoryScreen`'s own composable) and by
`loadTripPoints`; `matchTripPoints` is called by `loadTripPoints` and by
`matchThumbnails`. Two unit tests, `TripStatLineTest.kt` and
`TripTraceMatchingTest.kt`, call `tripStatLine`/`tripBehaviorLine`/
`tripFuelEconomyLper100Km` and `matchTripPoints` directly (01 Part D).

By the placement test: `HistoryScreen` (the composable) → `ui.history`, single
destination. `tripStatLine`, `tripBehaviorLine`, `tripFuelEconomyLper100Km`,
`loadTripTrace`, `loadTripPoints`, `matchTripPoints`, `readTraceSegments`, and
the `TraceSegment` data class they share → called from `ui.history` **and**
`ui.tripdetail` — 2 distinct destinations — rule 3, `ui.shared`. `NoTripsYet`,
`HistoryLoadFailed`, `TripCard`, `TraceThumbnail`, `matchThumbnails`, `monthKey`,
`monthFormat` stay in `ui.history` — every one is `private` with its only
caller inside `HistoryScreen.kt` itself.

This is a genuine content split, not a pure repackage — the same-package
mechanical-split procedure in `.claude/skills/detour-file-split/SKILL.md` §1-8
runs first (extract to a new file, same package, prove it with the
zero-added-lines check), and only then does the new file's package line
change. Two effects worth naming exactly because they contradict "nothing
changes but the package line":

- `HistoryScreen.kt` itself needs a new import it never needed before, because
  its own private `matchThumbnails` still calls `readTraceSegments` and
  `matchTripPoints`, which just left the file:

  ```kotlin
  // ui/history/HistoryScreen.kt, after
  package com.jellemax.detour.ui.history

  import com.jellemax.detour.ui.shared.TraceSegment
  import com.jellemax.detour.ui.shared.matchTripPoints
  import com.jellemax.detour.ui.shared.readTraceSegments
  ```

  The file the logic came from is not exempt from the import cost the toy
  example showed — it pays it too, the moment its own leftover code depends on
  what left.

- `TripDetailScreen.kt`'s real call site, `TripDetailScreen.kt:521`
  (`else tripStatLine(trip)`) and `:223` (`loadTripPoints(trip)`), needs one
  new import line and zero call-site changes:

  ```kotlin
  // ui/tripdetail/TripDetailScreen.kt, after
  package com.jellemax.detour.ui.tripdetail

  import com.jellemax.detour.ui.shared.loadTripPoints
  import com.jellemax.detour.ui.shared.tripBehaviorLine
  import com.jellemax.detour.ui.shared.tripFuelEconomyLper100Km
  import com.jellemax.detour.ui.shared.tripStatLine
  // ... existing imports unchanged
  ```

  `TripDetailScreen.kt:521,531,606` themselves — `tripStatLine(trip)`,
  `tripBehaviorLine(trip)?.let { ... }`,
  `tripFuelEconomyLper100Km(trip) != null` — are untouched text.

- `TripStatLineTest.kt` and `TripTraceMatchingTest.kt`
  (`app/src/test/java/com/jellemax/detour/ui/`) currently resolve
  `tripStatLine`/`matchTripPoints` same-package, with no import at all (both
  files' full content starts `package com.jellemax.detour.ui` then straight to
  `import com.jellemax.detour.data.*`). After the move both need one new
  import each — `import com.jellemax.detour.ui.shared.tripStatLine` /
  `...matchTripPoints` — while every assertion in both files stays byte-for-byte
  identical, because `internal` visibility crosses this package boundary for
  free (03 §4.2's finding that the `test` source set already has friend access
  to `main`'s `internal` declarations, independent of which package they sit
  in).
- `tracking/TripSession.kt:16`'s import of `loadTripPoints` (currently
  `com.jellemax.detour.ui.HistoryScreen.kt`'s symbol, imported cross-package
  already since `tracking/` is a different package than `ui/`) becomes
  `import com.jellemax.detour.ui.shared.loadTripPoints` — one line, in a file
  this proposal does not otherwise touch.

### 3. The MapScreen cluster — confronting the ownership gate directly

`Destination.Map` is rendered by `MapScreen.kt` (`MainActivity.kt:372-373`),
but 25 other files exist only to be called from files in this same cluster:
`MapBottom.kt`, `MapCamera.kt`, `MapCameraTuning.kt`, `MapChrome.kt`,
`MapCircleMembers.kt`, `MapDialogs.kt`, `MapHazardAlerts.kt`,
`MapHazardPrefetch.kt`, `MapHud.kt`, `MapLibreMap.kt`, `MapNavigation.kt`,
`MapPermissions.kt`, `MapScreenState.kt`, `HomeSheet.kt`, `RideSheet.kt`,
`SpinCards.kt`, `SpinShare.kt`, `CandidatesCard.kt`, `NavigationDock.kt`,
`NavAppLaunch.kt`, `Navigation.kt`, `RiderCard.kt`, `DriveClockLocal.kt`,
`SearchIsland.kt`, `RetainedMap.kt`, `FogView.kt`. By rule 1/2, all 26 files —
one destination, whatever the internal caller graph among them — become one
package, `ui.mapscreen`. This is the cluster where the axis has to say
something honest rather than convenient, because it is also where
`boundaries.md` §8.4's ownership gate lives: `MapBottomSlot`
(`MapBottom.kt:56`) takes **46 parameters**, `SpinSheet`
(`SpinCards.kt:107`) **23**, and `RideSheet.kt`'s `DriveSheet`/`NavSheet` carry
a comparable share of the same signature bloat — all because the state that
should own those values is still ~30 loose `var … by remember` declarations
inside `MapScreen.kt` itself (`state-holders.md` §14.5, quoted in
`02-repo-guidelines.md`).

**Feature slicing does not exempt this cluster from that gate, and does not
fix it.** Moving all 26 files into `ui.mapscreen/` is a pure package-line
change — none of them gains or loses a parameter, because the packaging axis
only decides which folder a file sits in, not who owns its state. The 46- and
23-parameter signatures move into the new package exactly as wide as they are
today. Any further split inside `ui.mapscreen/` — say, pulling `SpinSheet`'s
UI out from under `SpinCards.kt` into its own file because it "belongs" to a
narrower concern — still has to pass §8.4 first: find the owner (a `SpinState`
holder, per `state-holders.md`'s own prescription), give it the state, and let
the file boundary fall out. That work is `detour-staged-refactor` territory,
gated on `docs/refactor/mapscreen/DECISION.md`'s open items, and is a
precondition for splitting *inside* `ui.mapscreen/` further — not for this
proposal's move, which touches no parameter list.

The one place this cluster forces an outside-`ui/` import fix is
`MapCameraTuning.kt`'s 8 `internal` constants and `driveFrameDelta`, consumed
today by `car/CarMapRenderer.kt:31-32`, `app/…/map/FollowCamera.kt:3-4`,
`app/…/map/MapMotion.kt:5-7`, `app/…/map/SpinRun.kt:15`, and 3 files in
`app/src/test/java/com/jellemax/detour/map/` (03 §2-3). Each of those 7 files
gets one import-path edit — `com.jellemax.detour.ui.MapCameraTuning`'s members
become `com.jellemax.detour.ui.mapscreen`'s — and nothing else, per placement
rule 4: these are cross-module consumers, not a second `ui/` destination, so
they don't pull the constants into `ui.shared`; they just follow the move.
Likewise `car/CarMapRenderer.kt:40-44`'s imports of `MapOverlays`,
`NamedFriendPosition`, `PositionMarker`, `openFreeMapStyleUrl`, `setCamera`
(from `MapLibreMap.kt`, → `ui.mapscreen`) and `car/SpinScreen.kt:40`'s import
of `formatDistanceKm` (from `Format.kt`, → `ui.shared`, since `Format.kt` has
callers in `ui.history`, `ui.tripdetail`, `ui.routeeditor`, and `ui.mapscreen`
besides `car/`) — 8 import lines total across 2 files, matching 03 §2's
complete enumeration exactly.

## The full target tree

`ui.shared` first (the deliberately small cross-destination tier), then the
14 destination packages, in `Destination.kt`'s declaration order. Every one of
the 59 files in `01-ui-inventory.md` appears exactly once as a *source* row;
3 rows produce 2 output files (a mechanical split precedes the repackage, per
example 2 above), so the tree holds 62 files.

### `com.jellemax.detour.ui.shared` (14 files)

| File | From | Why shared (rule 3 — 2+ destination callers) |
|---|---|---|
| `AppBar.kt` | unchanged | `SubScreenTopBar`, 12 destinations |
| `Cards.kt` | unchanged | `ListCard` (12), `CardDivider` (8), `SectionLabel` (4) |
| `ConfirmDialog.kt` | unchanged | 9 destinations |
| `GlassSurface.kt` | unchanged | map, coveragemap, tripdetail (3 destinations) |
| `GraphiteTheme.kt` | unchanged | no destination owns app-shell theme tokens; default home |
| `Theme.kt` | unchanged | `isAppDarkTheme`, 5+ destinations plus MainActivity |
| `Format.kt` | unchanged | history, tripdetail, routeeditor, mapscreen, plus `car/` |
| `TravelModeIcon.kt` | unchanged | history, mapscreen, routeeditor |
| `Pills.kt` | unchanged | `ChoiceRow`: mapscreen, settings, shared (TripCardRenderer) |
| `TripCardRenderer.kt` | unchanged | `TripCardShareDialog`: history, tripdetail |
| `HubRow.kt` | extracted from `HubScreen.kt:407-` | called from social, settings, circles, plus `HubScreen.kt` itself |
| `BackgroundLocationDisclosure.kt` | extracted from `MapDialogs.kt:28-56` | called from settings (`SettingsScreen.kt:393`) and mapscreen (`MapDialogs.kt:97`) |
| `TripFormat.kt` | extracted from `HistoryScreen.kt` (`:85,132,149,171,177,502,518,539` + `TraceSegment`) | called from history and tripdetail, plus `tracking/TripSession.kt` |
| `SpinResultHolder.kt` | unchanged | read/written by mapscreen (`MapScreen.kt`, `MapScreenState.kt`) and routes (`RoutesScreen.kt`'s `seedRouteNavigation` call) |

### `com.jellemax.detour.ui.mapscreen` (26 files) — `Destination.Map`

`MapScreen.kt`, `MapBottom.kt`, `MapCamera.kt`, `MapCameraTuning.kt`,
`MapChrome.kt`, `MapCircleMembers.kt`, `MapDialogs.kt` (minus the extracted
`BackgroundLocationDisclosure`), `MapHazardAlerts.kt`, `MapHazardPrefetch.kt`,
`MapHud.kt`, `MapLibreMap.kt`, `MapNavigation.kt`, `MapPermissions.kt`,
`MapScreenState.kt`, `HomeSheet.kt`, `RideSheet.kt`, `SpinCards.kt`,
`SpinShare.kt`, `CandidatesCard.kt`, `NavigationDock.kt`, `NavAppLaunch.kt`,
`Navigation.kt`, `RiderCard.kt`, `DriveClockLocal.kt`, `SearchIsland.kt`,
`RetainedMap.kt`, `FogView.kt`.

`Navigation.kt` is worth a note: 01 Anomaly 5 calls it a "holding pen" because
`NavigationBanner`, `RouteProgressTrack`, `SpeedLimitSign` each have exactly
one caller, and each caller is a *different* file (`MapScreen.kt`,
`RideSheet.kt`, `MapHud.kt`). Feature slicing does not force a fix here,
because all three callers are inside the same destination — the placement
test only asks how many *destinations* call a symbol, not how many files, so
this file moves to `ui.mapscreen` whole, anomaly intact. That is a real limit
of this axis, not an oversight; see "What this axis is bad at."

### `com.jellemax.detour.ui.history` (1 file) — `Destination.History`

`HistoryScreen.kt`, trimmed of the `TripFormat.kt` extraction above; keeps
`NoTripsYet`, `HistoryLoadFailed`, `TripCard`, `TraceThumbnail`,
`matchThumbnails`, `monthKey`, `monthFormat` — all private, single-caller
inside this file.

### `com.jellemax.detour.ui.tripdetail` (1 file) — `Destination.TripDetail`

`TripDetailScreen.kt`.

### `com.jellemax.detour.ui.badges` (1 file) — `Destination.Badges`

`BadgesScreen.kt` (keeps its own `SectionLabel` shadow — 01 Anomaly 1; the
placement test does not touch it, since fixing a same-name shadow is a content
decision, not a placement one — flagged, not silently carried forward).

### `com.jellemax.detour.ui.coveragemap` (1 file) — `Destination.CoverageMap`

`CoverageMapScreen.kt`.

### `com.jellemax.detour.ui.social` (1 file) — `Destination.Social`

`SocialScreen.kt`.

### `com.jellemax.detour.ui.profile` (1 file) — `Destination.Profile`

`ProfileScreen.kt`.

### `com.jellemax.detour.ui.friends` (2 files) — `Destination.Friends`

`FriendsScreen.kt`, `DisabledFeature.kt` (single caller `FriendsScreen.kt:702`).

### `com.jellemax.detour.ui.circles` (1 file) — `Destination.Circles` + `Destination.CircleDetail`

`CirclesScreen.kt` (both routed composables live in this one file today; the
placement test doesn't split them further — same destination family, same
file, no rule fires).

### `com.jellemax.detour.ui.savedplaces` (1 file) — `Destination.SavedPlaces`

`SavedPlacesScreen.kt`.

### `com.jellemax.detour.ui.routes` (1 file) — `Destination.Routes`

`RoutesScreen.kt`.

### `com.jellemax.detour.ui.routeeditor` (1 file) — `Destination.RouteEditor`

`RouteEditorScreen.kt`.

### `com.jellemax.detour.ui.hub` (2 files) — `Destination.Hub`

`HubScreen.kt` (trimmed of the `HubRow` extraction above; keeps
`YouProfileCard`, `YouGuestCard`, `YouStatsRow`, `StatCell`, all private,
same-file per `boundaries.md` §8.5), `UpdateBanner.kt` (single caller
`HubScreen.kt:134`).

### `com.jellemax.detour.ui.settings` (7 files) — `Destination.Settings` + 7 `SettingsSpoke`s

`SettingsHub.kt`, `SettingsScreen.kt`, `SettingsServers.kt`,
`SettingsDiagnostics.kt`, `Obd2PairingScreen.kt`, `SecureFields.kt`
(`SecretTextField` inside it has zero callers anywhere — 01 Anomaly 2 — and
should be deleted, not migrated; that is a content decision this proposal
flags but does not itself make), `UpdateProgressButton.kt`.

**Total: 14 files in `ui.shared` + 26 + 1+1+1+1+1+1+2+1+1+1+1+2+7 = 48 across
14 destination packages = 62 files**, against 59 today (3 files split in two
by the content extractions in examples 2 and 3's sibling, `HubScreen.kt` and
`MapDialogs.kt`).

## Sequencing

Ordered so each stage is independently landable and revertable; costs are
measured against the same repo facts 02/03 already gathered, not estimated
fresh.

1. **Write the decision down.** No file moves. Answers issue #184's own "what
   done looks like" item 1 — a decided axis, in `CONTRIBUTING.md`'s Layout
   section, per #184's own suggestion. Zero cost, unblocks everything else.
2. **Pure single-destination repackage, no shared exports.** `BadgesScreen.kt`,
   `CoverageMapScreen.kt`, `SocialScreen.kt`, `ProfileScreen.kt`,
   `SavedPlacesScreen.kt`, `RoutesScreen.kt`, `RouteEditorScreen.kt`,
   `CirclesScreen.kt`, `FriendsScreen.kt` + `DisabledFeature.kt` — 10 files,
   each with zero external `ui/` callers today (01's own per-file evidence).
   One changed line per file (the `package` declaration) plus one import-path
   edit in `MainActivity.kt` per screen (`MainActivity.kt:68-87`'s existing
   per-symbol imports). Rebase-clean: nothing else in the tree references
   these files by package.
3. **The Settings cluster.** 7 files, one package, per example 1. External
   blast radius: `MainActivity.kt`'s 8 `entry<>` registrations
   (`:451-479`) and nothing else, since no other `ui/` file imports a Settings
   symbol (01, 03 confirm no cross-references either direction). Pure move.
4. **The mapscreen cluster.** 26 files, one package, per example 3. Larger
   blast radius but every touch is import-path-only: `MainActivity.kt:372-373`,
   2 `car/` files (8 import lines, 03 §2), 4 `app/…/map/` files (03 §3.1), 3
   `app/src/test/…/map/` files (03 §3.2). No parameter list, no `remember`,
   no effect body changes — the state layer is explicitly untouched (see
   example 3). This is the highest-churn file in the repo — `MapScreen.kt` had
   34 commits in the last 60 days, and 5 of the 9 unmerged local branches
   touch `ui/` (issue #184's own body, quoted in `02-repo-guidelines.md`) — so
   land it in a quiet window and coordinate with those branches' owners
   first; a branch that adds a new file under the old flat `ui/` package
   mid-flight will need its own file's package line fixed on rebase, which is
   a 1-line conflict, not a logic conflict, but it is a conflict every one of
   those 5 branches will see.
5. **The three content extractions.** `HubRow.kt` out of `HubScreen.kt`,
   `BackgroundLocationDisclosure.kt` out of `MapDialogs.kt`, `TripFormat.kt`
   out of `HistoryScreen.kt` — each as a same-package mechanical split first
   (`detour-file-split` SKILL.md §1-8, zero-added-lines proof), landed and
   verified, *then* a separate commit repackaging just the new file into
   `ui.shared`. Two commits per extraction, never one, matching
   `00-chain-design.md`'s "never in one commit" spirit even though it does not
   name repackaging explicitly. This is the one stage that is not a pure
   move — it earns unit-test-level verification (§ below), not just the
   compiler.
6. **History and TripDetail repackage.** Once stage 5 has moved the shared
   trio out, `HistoryScreen.kt` and `TripDetailScreen.kt` become 1-file, pure,
   single-destination repackages exactly like stage 2's group.
7. **Hub repackage.** Same shape, after stage 5's `HubRow` extraction —
   `HubScreen.kt` + `UpdateBanner.kt`.
8. **Import cleanup sweep.** One commit, repo-wide, removing now-orphaned
   imports left by every prior stage — reuse `detour-file-split`'s 4 traps
   (`getValue`/`setValue` delegate imports with no textual call site, two
   imports sharing a simple name, a symbol name that's also an ordinary
   English word in a comment, a KDoc `[link]`), since every stage above
   produces exactly this kind of orphaned-import residue.

Stages 2, 3, 6, 7 are provably inert by the same zero-added-lines-adjacent
check described below. Stage 4 is provably inert per file but touches the
most files. Stage 5 is the only stage that changes what a symbol's file
contains, and is gated on its own mechanical-split proof before it is allowed
to also change that file's package.

## What this axis is bad at

- **The rule-3 threshold is a real judgment call at exactly 2.**
  `BackgroundLocationDisclosure` has exactly 2 callers, in 2 different
  destinations (`MapDialogs.kt:97` and `SettingsScreen.kt:393`) — barely over
  the line. A reasonable team could instead decide a symbol needs 3+
  destination callers before it's worth a new shared file, and simply
  duplicate it once at 2. This proposal's placement test does not have a
  principled answer for exactly where that line sits; it takes 01's own
  "2 or more distinct calling files" definition of SHARED as the threshold
  because that is the definition already measured, not because 2 is
  self-evidently the right number.
- **Discoverability of a shared primitive gets worse, not better, for a
  newcomer skimming one destination's folder.** Today, a rider working in
  `CirclesScreen.kt` can `ls ui/` and see `ListCard` sitting right there. After
  this move, `ListCard` is one package-hop away in `ui.shared/Cards.kt`, and
  nothing in `ui.circles/CirclesScreen.kt` announces that a shared tier exists
  besides the import line itself. The flat layout's only virtue — everything
  in one listing — is real, and this axis spends it deliberately in exchange
  for a smaller per-destination surface.
- **Duplication across destinations is not prevented, only made one import
  away instead of zero.** 01 Anomaly 1 — `BadgesScreen.kt`'s private
  `SectionLabel` silently shadowing `Cards.kt`'s shared one, because both are
  visible same-package with no import required — happens *today*, under the
  flat layout, precisely because nothing forces a look at what already
  exists. Splitting `ui.shared` into its own package does not fix this; if
  anything, a future author reaching for a `SectionLabel`-shaped need inside
  `ui.badges/` now has to know to look in a different package at all, and
  Kotlin's same-file-priority resolution rule cannot even warn them the way it
  silently didn't warn anyone before.
- **This axis says nothing about `MapScreen.kt`'s own size or state
  ownership**, and does not shrink it. 26 files land in one package; the
  1,353-line file at the center of that package (01 §30) is exactly as long
  after this move as before it, and `MapBottomSlot`'s 46 parameters are
  exactly as wide. Anyone expecting a packaging decision to also produce a
  smaller `MapScreen.kt` will be disappointed — that is `detour-staged-refactor`
  work, gated on `docs/refactor/mapscreen/DECISION.md`, not a file-placement
  question.
- **`Navigation.kt`'s holding-pen shape (01 Anomaly 5) survives this move
  unexamined**, as noted in the target tree — three unrelated single-caller
  declarations, each with a different caller, land in `ui.mapscreen/` as one
  file because all three callers share a destination. A type-first axis would
  at least force the question of whether `NavigationBanner`, `RouteProgressTrack`,
  and `SpeedLimitSign` belong in the same file at all; this axis has no reason
  to ask, because the answer to "which destination" is the same for all three.
- **`ui.settings/` at 7 files covering 7 `SettingsSpoke`s plus the hub is
  coarser than the `Destination` partition technically allows.** A
  finer-grained reading of rule 1 could give each spoke its own package
  (`ui.settings.fog`, `ui.settings.obd2`, …); this proposal deliberately
  declines, because that is a second level of nesting with no precedent
  anywhere in this repo (`03-move-cost.md` §7) and the same over-fragmentation
  `05-kotlin-package-idiom.md` §2 and §6 diagnose in the sibling proposal's
  per-variant `base/button/{confirm,deny}` packages. Treating all 7 spokes as
  one destination-family, one package, is a judgment call this document is
  making explicitly rather than letting the test make it by default.

## Verification

`.claude/skills/detour-file-split/SKILL.md` carries this repo's proof-of-inertness
procedure; it was built for a same-package mechanical split, and this proposal
needs two variants of it, corresponding to the two kinds of stage above:

**For a pure repackage (stages 2, 3, 4, 6, 7 — package line only, no content
move):** the SKILL.md's own zero-added-lines check
(`check-no-added-lines.sh <base> <file>`, `git diff | grep -c '^+[^+]'`) does
not directly apply, because the `package` declaration line itself is a
guaranteed one-line diff in the moved file. The adapted check: the moved
file's diff must show exactly one changed line pair (old `package …` /
new `package …`) and **zero** other added or removed lines; every file that
merely imports the mover must show a diff confined to its import block —
concretely, every changed line in a dependent file must match
`^[+-]import ` (a script analogous to SKILL.md's, run per dependent file, not
just per moved file). `./gradlew :app:compileDebugKotlin` green after every
commit is the same non-negotiable floor SKILL.md §8 already sets.

**For a content extraction (stage 5):** this *is* a SKILL.md mechanical split,
run exactly as documented — visibility by grep
(`symbol-visibility.sh <Symbol> <new-file>`), the zero-added-lines check
against the source file, imports left alone until their own final commit —
followed by the repackage variant above, as a second, separate commit, once
the extraction has already proven inert on its own.

**Test-source-set consumers** (`TripStatLineTest.kt`, `TripTraceMatchingTest.kt`,
`MapMotionTest.kt`, `FollowCameraTest.kt`, `CameraAuthorityTest.kt` — 03 §3.2)
get one new import line each and must show **zero** assertion changes;
`./gradlew :app:testDebugUnitTest` passing with the same test names and the
same pass count, before and after, is the check — not "tests are green," which
would also be true of a test that silently stopped testing anything.

**Nothing in this proposal reads a GPS fix or moves a state owner**, so no
stage earns a replay A/B (`00-chain-design.md`'s "which verification a change
earns" table) beyond what its underlying content already required before the
move — a stage that touches `MapCamera.kt` moves the file, not the fix-reading
logic inside it, so the obligation is unchanged, not newly incurred or newly
discharged.

**What this proposal does not verify, and says so:** whether the resulting
package boundaries reduce merge conflicts or review load in practice is not
measurable before the fact; 05 §6's own two conditions for finer packaging
paying off (team/codebase scale, or genuine near-term module extraction) are
judgment calls about the future, not facts this repo's history can settle
today one way or the other.
