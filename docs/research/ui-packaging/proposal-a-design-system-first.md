# Proposal A: design-system-first / component-type-first

Evidence base: `01-ui-inventory.md`, `02-repo-guidelines.md`, `03-move-cost.md`,
`04-android-compose-precedent.md`, `05-kotlin-package-idiom.md` (all in this directory,
captured against commit `efa6c9ad`, 2026-09-09). Every count and file:line below was
re-verified against the tree directly while writing this document, not copied without
checking.

This is one candidate axis for organizing `ui/`, not the only one under consideration.
It argues for grouping by **what a declaration renders** (button, card, dialog, row,
theme token) rather than by **where it is used** (a `Destination`). The owner's original
sketch —

```
base/button/{confirm,deny,download,progress}
base/modal/{confirm,acknowledge,info}
base/card/userCard
base/hud/{speed,avg,position,recordingTrip}
```

— is the instinct this proposal takes seriously. It is not the tree this proposal ends
in. Where the two diverge, this document says so and cites why.

---

## The axis, in one paragraph

A file or declaration belongs to the **design system** (`base/`, a new flat package
sibling to `ui/`, `car/`, `map/`) when it renders something whose shape does not depend
on which screen is asking — no `data/` type in its signature, nameable by what it draws
(a card, a dialog, a row) rather than by the screen it first appeared in — **and** it is
already called from genuinely different parts of the app, not just from siblings of one
feature. Everything else — every screen, every single-caller component, and every
component that is reused but only within one feature's own cluster of files — stays
exactly where it is today, in flat `ui/`. This axis only ever moves code *out* of `ui/`;
it never explains where a screen's own files go, because that is a different, unrelated
axis (feature/destination), out of scope for this document.

---

## The placement test

Stop at the first question that answers. This is deliberately modeled on the two-step
test Now in Android's own docs use to separate `core:designsystem` from `core:ui` — "if a
class is needed only by one feature module, it should remain within that module; if not,
it should be placed into an appropriate core module" (`04-android-compose-precedent.md`
§2d) — adapted to Detour's actual shape, where there is no module boundary to lean on,
only a package one.

1. **Is it a route composable, registered against a `Destination` or `SettingsSpoke`
   in `MainActivity.kt`'s `entry<...>` table?** → stays in `ui/`, flat, named for the
   screen. This axis does not touch screens.
2. **Does its signature take a `data/`-layer type** (`Trip`, `LatLon`, `Settings.*`,
   `TravelMode`, a `Store`), **or does its body call into intent-launching / navigation /
   permission-branching logic** (`Context`, `Intent`, `ManagedActivityResultLauncher`)?
   → it is domain-coupled presentation logic, not a design-system primitive, however many
   callers it has. Stays in `ui/`. (This is where Now in Android's `core:ui`, "dependent
   on the data layer since it renders models" — 04 §2d — sits; Detour has no equivalent
   home for that tier yet, so it stays put rather than being invented for one file.)
3. **Is it called from exactly one file today?** → not shared. Stays exactly where it
   is. Do not create a `base/` bucket for a family that has one member — see 01 §B: no
   button family, no HUD family, no chip family, no empty/error-state family exists in
   this codebase today, whatever the names suggest.
4. **Is it called from ≥2 distinct files — and do those files, traced back through
   `nav/Destination.kt`'s registration table, land on ≥2 genuinely different
   destinations, not siblings of one feature (the six `SettingsSpoke`s, or MapScreen's
   own bottom-sheet dispatch)?** → `base/`, filed under the flat file named for its
   category (`Cards.kt`, `Dialogs.kt`, `Rows.kt`, `Theme.kt`). Never a package per
   component, never a package per variant (04 §4-5, 05 §5).

Step 4's destination-trace is the one place this test asks for a second look rather than
a single grep, and it is the direct answer to why some of Table A's "shared" symbols
(01 §A) stay in `ui/` below: a caller count of 2 is necessary but not sufficient, because
the raw count cannot tell a genuine cross-app reuse from two spokes of the same feature
calling their own shared helper.

---

## A simplified, abstract example

Invented names, small enough to hold in one head. `StatusPill` starts as a private helper
inside one screen, a second screen copies it by hand, and a third caller is what tips it
into `base/`.

**Before** — flat `ui/`, both files `package com.jellemax.detour.ui`:

```kotlin
// ui/ScreenX.kt
package com.jellemax.detour.ui

internal enum class Tone { GOOD, WARN }

@Composable
internal fun StatusPill(label: String, tone: Tone) {
    Surface(shape = CircleShape, color = tone.color()) { Text(label) }
}

@Composable
fun ScreenX(...) { ... StatusPill("Live", Tone.GOOD) ... }
```

```kotlin
// ui/ScreenY.kt
package com.jellemax.detour.ui

@Composable
fun ScreenY(...) {
    ...
    StatusPill("Paused", Tone.WARN)   // no import — same package, resolves for free
    ...
}
```

A third screen, `ScreenZ`, starts calling it too. Three distinct callers, no `data/`
type in the signature, nameable by shape ("a status pill") rather than by either
screen — placement test step 4 says move it.

**After:**

```kotlin
// base/Pills.kt
package com.jellemax.detour.base

internal enum class Tone { GOOD, WARN }   // moved too — see below

@Composable
internal fun StatusPill(label: String, tone: Tone) {
    Surface(shape = CircleShape, color = tone.color()) { Text(label) }
}
```

```kotlin
// ui/ScreenX.kt
package com.jellemax.detour.ui
import com.jellemax.detour.base.StatusPill   // NEW — same-package resolution is gone
import com.jellemax.detour.base.Tone         // NEW

@Composable
fun ScreenX(...) { ... StatusPill("Live", Tone.GOOD) ... }
```

```kotlin
// ui/ScreenY.kt
package com.jellemax.detour.ui
import com.jellemax.detour.base.StatusPill   // NEW
import com.jellemax.detour.base.Tone         // NEW

@Composable
fun ScreenY(...) { ... StatusPill("Paused", Tone.WARN) ... }
```

**The symbol two files share (`Tone`) is the trap.** `StatusPill`'s signature depends on
`Tone`. Leaving `Tone` behind in `ui/` while moving `StatusPill` to `base/` would make the
design-system package import a domain-flavoured enum back out of the screen layer —
a dependency arrow pointing the wrong way, invisible until someone asks "why does
`base/` import from `ui/`?" The fix is to move `Tone` with it, which is why *both* new
`import` lines appear in `ScreenX.kt`/`ScreenY.kt` above, not one. A mechanical, symbol-
by-symbol move that ignores a shared type is how a design-system package quietly grows a
dependency on the feature layer it was supposed to sit underneath.

---

## The real example, worked end to end

Three genuine cases, in increasing order of how much judgment the placement test needs.

### 1. `Cards.kt` — the clean case

`app/src/main/java/com/jellemax/detour/ui/Cards.kt` (75 lines) declares `ListCard`
(Cards.kt:27, 12 callers), `CardDivider` (Cards.kt:53, 8 callers), `SectionLabel`
(Cards.kt:66, 4 callers) — see 01 §A for the full caller lists. None takes a `data/`
type. The callers span Social, Settings-hub, Routes, Badges, Saved-places, Profile,
Search, Friends, Circles — genuinely different destinations, not one feature's spokes.
Placement test: step 2 passes (no data type), step 4 passes (≥2 files, ≥2 destinations).
The whole file moves, unchanged internally — it is already exactly the shape §8.5 asks
for, "one concern, one file," three declarations that change together.

Before, `SocialScreen.kt` calls `ListCard` and `CardDivider` with no import at all
(SocialScreen.kt:131-139, confirmed by reading the file's own import block,
SocialScreen.kt:1-19 — no `ListCard`/`CardDivider` import exists, because same-package
resolution needs none):

```kotlin
// ui/SocialScreen.kt, before — package com.jellemax.detour.ui
ListCard {
    HubRow(icon = Icons.Rounded.Group, title = "Friends", onClick = onOpenFriends,
        trailingText = "leaderboard", paintCard = false)
    CardDivider()
    HubRow(icon = Icons.Rounded.ShareLocation, title = "Circles", onClick = onOpenCircles,
        trailingText = "$circleCount sharing", paintCard = false)
}
```

After the move (`Cards.kt` → `base/Cards.kt`, `package com.jellemax.detour.ui` →
`package com.jellemax.detour.base`), the call site's body is byte-for-byte identical;
only the import block gains two lines:

```kotlin
// ui/SocialScreen.kt, after
import com.jellemax.detour.base.CardDivider
import com.jellemax.detour.base.ListCard
...
ListCard {
    HubRow(...)   // HubRow itself also moves — see case 3 below
    CardDivider()
    HubRow(...)
}
```

### 2. `GlassSurface.kt` — the extension-function case

`GlassSurface.kt` (44 lines) declares `glassCardColors()` (11 callers), `glassContainerColor()`
(3 callers), and the extension `Modifier.glassBorder(shape)` (11 callers) — GlassSurface.kt:26,34,40.
All three are pure `Color`/`Shape` computation, no `data/` type anywhere. Callers span
CandidatesCard, MapHud, Navigation, CoverageMapScreen, RideSheet, SpinCards, RiderCard,
NavigationDock, TripDetailScreen, SearchIsland, MapChrome — a real cross-destination
spread, not one feature.

`MapChrome.kt:112,114` today, no import (confirmed: MapChrome.kt:1-20 carries no
`glassBorder`/`glassCardColors` import):

```kotlin
// ui/MapChrome.kt, before
Card(
    modifier = Modifier.glassBorder(MaterialTheme.shapes.large),
    shape = MaterialTheme.shapes.large,
    colors = glassCardColors(),
    ...
)
```

After (`GlassSurface.kt` → `base/GlassSurface.kt`):

```kotlin
// ui/MapChrome.kt, after
import com.jellemax.detour.base.glassBorder
import com.jellemax.detour.base.glassCardColors
...
Card(
    modifier = Modifier.glassBorder(MaterialTheme.shapes.large),  // call site: unchanged
    shape = MaterialTheme.shapes.large,
    colors = glassCardColors(),
    ...
)
```

The extension function's receiver-dot call syntax (`Modifier.glassBorder(...)`) does not
change at the call site at all — only the import needed to resolve it. This is the same
"extensions need a second grep pass" fact 01 §Method item 3 and the file-split skill's
"three ways the grep lies" §5.1 both document for the *visibility* question; here it
shows up as the *import* question instead, same underlying mechanic.

### 3. `HubRow` — the "shared component trapped inside a screen file" case

`HubRow` (HubScreen.kt:407) is declared inside `HubScreen.kt`, the **You** tab's route
composable — not a component file. Its own KDoc (HubScreen.kt:392-405) already frames it
as shared: *"Used to share a look with the Settings root's rows... every existing call
site (Hub, Settings)..."* Its signature (`icon: ImageVector, title: String, onClick,
subtitle, trailingText, trailingCount, modifier, paintCard`) carries no `data/` type.
Callers: SocialScreen.kt:132/140, SettingsHub.kt (8 sites), SettingsServers.kt (3 sites),
CirclesScreen.kt:421 — plus 5 internal uses inside HubScreen.kt itself
(HubScreen.kt:147/154/161/168/182). That is 5 distinct files across 5 different
destinations (Hub, Social, Settings-hub, Settings-servers-spoke, Circles) — placement
test step 4 passes cleanly, this is genuinely cross-feature, not one feature's spokes.

The one thing this case adds over Cards.kt/GlassSurface.kt: **the file that originally
owned the symbol also needs a new import**, because the declaration is leaving its own
file:

```kotlin
// ui/HubScreen.kt, before — HubRow declared and called in the same file, same package,
// zero imports needed either way
fun HubScreen(...) {
    ...
    HubRow(icon = ..., title = "Friends & Convoys", onClick = ...)   // :147, internal call
    ...
}
@Composable
fun HubRow(icon: ImageVector, title: String, onClick: () -> Unit, ...) { ... }   // :407
```

```kotlin
// ui/HubScreen.kt, after — HubRow moved out to base/Rows.kt
package com.jellemax.detour.ui
import com.jellemax.detour.base.HubRow   // NEW — even the origin file needs this now

fun HubScreen(...) {
    ...
    HubRow(icon = ..., title = "Friends & Convoys", onClick = ...)   // call site: unchanged
    ...
}
```

`HubScreen.kt` itself is untouched otherwise — it stays a screen file, in `ui/`, over the
route composable that is the whole reason it exists. Only the one declaration that never
belonged to it leaves.

---

## The full target tree

**Headline: 7 of 59 files move wholesale, 2 more lose exactly one declaration each, 50
are untouched.** `base/` ends this proposal with 8 files.

### New: `com.jellemax.detour.base` (flat, no subpackages — see "What this axis is bad
at" and 05 §8 for why not one level further)

| File | Contents | Moved from |
|---|---|---|
| `base/AppBar.kt` | `SubScreenTopBar` | `ui/AppBar.kt` (whole file, unchanged) |
| `base/Cards.kt` | `ListCard`, `CardDivider`, `SectionLabel` | `ui/Cards.kt` (whole file, unchanged) |
| `base/GlassSurface.kt` | `glassCardColors`, `glassContainerColor`, `Modifier.glassBorder` | `ui/GlassSurface.kt` (whole file, unchanged) |
| `base/Dialogs.kt` | `ConfirmDialog`, `BackgroundLocationDisclosure` | `ui/ConfirmDialog.kt` (whole file) + one declaration extracted from `ui/MapDialogs.kt` — merged in a **separate, later commit**, see Sequencing |
| `base/Pills.kt` | `ChoiceRowMetrics`, `choiceRowMetrics`, `ChoiceItem`, `ChoiceRow` | `ui/Pills.kt` (whole file, unchanged) |
| `base/Rows.kt` | `HubRow` | one declaration extracted from `ui/HubScreen.kt` |
| `base/Theme.kt` | `isAppDarkTheme`, `isNightNow` (private) | `ui/Theme.kt` (whole file, unchanged) |
| `base/GraphiteTheme.kt` | `GraphiteDark`, `GraphiteLight` | `ui/GraphiteTheme.kt` (whole file, unchanged) |

`Format.kt` is deliberately **not** in this list despite 7 cross-destination callers
(Theme.kt:19's sibling case) — see "What this axis is bad at": it is a pure formatter,
not a rendered component, and this axis is scoped to component type, not to "anything
reused." Moving it would be scope creep into a by-layer axis this document does not
argue for.

### `com.jellemax.detour.ui` — unchanged (51 files; screens, 46 of them exactly as today)

Every file below stays at its current path, current package, current content, **except**
the two marked "− 1 declaration."

| File | LoC | 01 bucket | Disposition |
|---|---|---|---|
| BadgesScreen.kt | 234 | Screen | unchanged |
| CandidatesCard.kt | 182 | Screen-local | unchanged |
| CirclesScreen.kt | 896 | Screen | unchanged |
| CoverageMapScreen.kt | 431 | Screen | unchanged |
| DisabledFeature.kt | 48 | Screen-local | unchanged |
| DriveClockLocal.kt | 52 | State holder | unchanged |
| FogView.kt | 614 | Other (custom View) | unchanged |
| Format.kt | 69 | Utility | unchanged — pure formatter, out of this axis's scope (see above) |
| FriendsScreen.kt | 883 | Screen | unchanged |
| HistoryScreen.kt | 586 | Screen + utility hybrid (01 Anomaly 6) | unchanged — its own anomaly, belongs to a by-layer axis, not this one |
| HomeSheet.kt | 422 | Screen-local; `DragHandle` shared (2 files) | unchanged — `DragHandle`'s two callers (HomeSheet.kt, RideSheet.kt) are both MapScreen's own bottom-sheet cluster; fails step 4's destination check |
| HubScreen.kt | 486 (−~14) | Screen | **HubRow extracted**, everything else unchanged |
| MapBottom.kt | 275 | Screen-local router | unchanged |
| MapCamera.kt | 456 | Screen-local | unchanged |
| MapCameraTuning.kt | 125 | Utility (tuning constants) | unchanged |
| MapChrome.kt | 200 | Screen-local | unchanged |
| MapCircleMembers.kt | 62 | Screen-local | unchanged |
| MapDialogs.kt | 118 (−~29) | Mixed | **`BackgroundLocationDisclosure` extracted**; `SavePinDialog`/`MapScreenDialogs` stay (screen-local) |
| MapHazardAlerts.kt | 141 | Screen-local | unchanged |
| MapHazardPrefetch.kt | 184 | Screen-local | unchanged |
| MapHud.kt | 219 | Screen-local HUD cluster | unchanged — see "bad at" / owner's sketch below |
| MapLibreMap.kt | 622 | Other (map infra) | unchanged |
| MapNavigation.kt | 141 | Screen-local | unchanged |
| MapPermissions.kt | 105 | State holder | unchanged |
| MapScreen.kt | 1353 | Screen | unchanged — see §8.4, not touched by this proposal at all |
| MapScreenState.kt | 116 | State holder | unchanged |
| NavAppLaunch.kt | 248 | Mixed | unchanged — `NavButton` fails step 2 (takes `LatLon`/`TravelMode`/`Settings.NavApp`, calls intent-launching helpers); see "bad at" |
| Navigation.kt | 266 | Holding pen (01 Anomaly 5) | unchanged — each export is individually single-caller, never reaches step 3's ≥2-caller bar |
| NavigationDock.kt | 166 | Screen-local | unchanged |
| Obd2PairingScreen.kt | 331 | Screen-local (despite name) | unchanged |
| ProfileScreen.kt | 172 | Screen | unchanged |
| RetainedMap.kt | 268 | State holder | unchanged |
| RiderCard.kt | 95 | Screen-local | unchanged — see "bad at": the closest thing to the owner's `userCard` |
| RideSheet.kt | 457 | Screen-local | unchanged |
| RouteEditorScreen.kt | 473 | Screen | unchanged |
| RoutesScreen.kt | 585 | Screen | unchanged |
| SavedPlacesScreen.kt | 414 | Screen | unchanged |
| SearchIsland.kt | 370 | Shared (2 files) | unchanged — both callers (HomeSheet.kt, RideSheet.kt) are MapScreen's own bottom-sheet cluster; fails step 4 |
| SecureFields.kt | 181 | Mixed | unchanged — `SecretTextField` is dead code (01 Anomaly 2), not this proposal's job to remove |
| SettingsDiagnostics.kt | 113 | Screen-local | unchanged |
| SettingsHub.kt | 193 | Screen | unchanged |
| SettingsScreen.kt | 1228 | Screen + 2 exports | unchanged — `SettingsScaffold`/`SettingsSection` stay; see "bad at" |
| SettingsServers.kt | 537 | Screen-local | unchanged |
| SocialScreen.kt | 150 | Screen | unchanged |
| SpinCards.kt | 362 | Mixed | unchanged — `ResultCallout`'s 2 callers (RideSheet.kt, NavigationDock.kt) are both MapScreen's bottom-sheet cluster; fails step 4 |
| SpinResultHolder.kt | 99 | State holder | unchanged |
| SpinShare.kt | 48 | Utility | unchanged |
| TravelModeIcon.kt | 13 | Utility | unchanged — extension property on a `data/` enum, fails step 2 |
| TripCardRenderer.kt | 771 | Mixed | unchanged — `TripCardShareDialog` takes `Trip`/`LatLon`, fails step 2 |
| TripDetailScreen.kt | 669 | Screen | unchanged |
| UpdateBanner.kt | 80 | Screen-local | unchanged |
| UpdateProgressButton.kt | 165 | Screen-local | unchanged — see "bad at": the owner's `base/button/progress` sketch, single-caller today |

(AppBar.kt, Cards.kt, ConfirmDialog.kt, GlassSurface.kt, GraphiteTheme.kt, Pills.kt,
Theme.kt — the 7 that move wholesale — are listed in the `base/` table above, not
repeated here. 8 + 51 = 59.)

---

## Sequencing

Every stage below is a pure move: no declaration touched requires an owner created
first, because every declaration in scope already clears §8.4's seven-parameter gate
(most take 0-4 parameters) — that gate is exactly why `MapBottomSlot` (46 params,
MapBottom.kt:56), `SpinSheet` (23-24 params, SpinCards.kt:107) and the rest of the
owner's `base/hud/`-shaped ambition are **not** in this plan at all. §8.4 says find the
owner before splitting; no owner exists yet for MapScreen's HUD/sheet cluster, so
nothing there moves. This is the direct, deliberate answer to "must respect §8.4's
sequencing or justify departing" — this proposal departs from the owner's sketch instead
of departing from §8.4.

None of the stages touch `MapScreen.kt` or `SettingsScreen.kt` — the two files over the
1,000-line hard limit, and (per 02/03) two of the highest-churn files in the repo:
`MapScreen.kt` had 34 commits in 60 days, and 5 of 9 unmerged local branches touch `ui/`.
That is a deliberate property of this sequencing, not an accident.

**Because this is a repackage, not a same-package split, the file-split skill's
"same-package, always" rule (§1) does not hold** — that rule is explicitly scoped to a
move that stays in `com.jellemax.detour.ui` (`.claude/skills/detour-file-split/SKILL.md:58-83`).
Every stage below pays the import cost that rule exists to avoid, and says how much.

| Stage | Moves | Files needing a new import | Cost | Revertable? |
|---|---|---|---|---|
| 1 | `AppBar.kt`, `Cards.kt`, `GlassSurface.kt`, `ConfirmDialog.kt`, `Pills.kt`, `Theme.kt`, `GraphiteTheme.kt` → `base/`, verbatim | ~25-30 distinct files (12+8+4+11+3+11+9+5+7 = 82 caller-file counts from 01 §A, before dedup for files calling 2+ symbols from the same new file) | ~80 new `import` lines, 0 lines of logic changed | Yes — revert is "move the 7 files back," same cost in reverse |
| 2 | `HubRow` extracted from `HubScreen.kt` → `base/Rows.kt` | HubScreen.kt (self-import) + SocialScreen.kt, SettingsHub.kt, SettingsServers.kt, CirclesScreen.kt | 5 new import lines | Yes |
| 3a | `BackgroundLocationDisclosure` extracted from `MapDialogs.kt` → its own new file `base/BackgroundLocationDisclosure.kt` (pure move, no judgment) | MapDialogs.kt (self-import) + SettingsScreen.kt | 2 new import lines | Yes |
| 3b | **Content decision, not a move**: merge `base/BackgroundLocationDisclosure.kt` into `base/ConfirmDialog.kt`, renaming the result `base/Dialogs.kt` | the 2 files from 3a, plus `ConfirmDialog.kt`'s 9 callers if `ConfirmDialog` itself is renamed at the same time (it should not be — keep the symbol name, only the file gains a second occupant) | 1 file rename + re-import at 2 call sites; **do this in its own commit, per §4 of the file-split skill — a rename never rides inside a move** | Yes, and safe to skip entirely — leaving two single-declaration files is also correct |

Every stage's diff is bounded to: (a) the moved declaration, copied byte-for-byte
including its KDoc, and (b) added `import` lines at calling files, nothing else. No
stage reformats, renames a parameter, or touches a call site's arguments.

**Explicitly declined, recorded so nobody "completes the pattern" later:**
`SettingsScaffold`, `SettingsSection`, `DragHandle`, `ResultCallout`, `NavButton`,
`TravelMode.icon`, `TripCardShareDialog`, `RiderCard`, `UpdateProgressButton`, the whole
`MapHud.kt` cluster. Each is discussed in "What this axis is bad at" or the tree above.
None of them move under this proposal.

---

## What this axis is bad at

**It cannot tell "shared across the app" from "shared within one feature's own spokes."**
The mechanical test (≥2 calling files) alone would move `SettingsScaffold`
(SettingsScreen.kt:138, called by SettingsHub.kt + itself) and `SettingsSection`
(SettingsScreen.kt:1212, called by SettingsDiagnostics.kt + SettingsServers.kt + itself)
into `base/` — but every one of those callers is a Settings spoke. The same is true of
`DragHandle` (HomeSheet.kt:210, called by RideSheet.kt) and `ResultCallout`
(SpinCards.kt:63, called by RideSheet.kt + NavigationDock.kt) — both callers in each case
are MapScreen's own bottom-sheet cluster. Getting this right needs a second, non-grep
step — tracing each caller back through `Destination.kt` — which is exactly what step 4
of the placement test asks for, but it means the test is not a single mechanical grep the
way step 1-3 are. Two people can disagree here in a way they cannot on caller count
alone.

**`internal` buys none of the isolation the "component library" framing implies.**
Kotlin's `internal` is module-scoped, not package-scoped (05 §5, confirmed against this
repo's own tree in 03 §4.2: 72 top-level `internal` declarations in `ui/` today, at least
8 of them already read from `map/` and `car/` — different packages, same module,
completely legal). Moving `ConfirmDialog` from `ui/` to `base/` does not make it any more
protected than leaving it in `ui/` — anything in `:app` that could reach it before can
reach it after. The entire payoff of this axis is **discoverability and duplicate
prevention** ("where would the shared card be? there's a `base/Cards.kt`"), not safety.
If the goal were encapsulation, only a real Gradle module boundary delivers that (05 §6),
and nothing here proposes one.

**A reader loses screen-locality.** Today, reading `HubScreen.kt` top to bottom shows
`HubRow` right there, five call sites and the declaration in one file. After the move,
understanding what `HubScreen.kt` renders means visiting `base/Rows.kt` too. This is the
standard package-by-layer cost (05 §6, quoting Sandi Metz via phauer: "I felt like I had
to understand everything in order to help with anything") — inverted here: understanding
one *screen* now costs a trip to a second *package*, which package-by-feature exists
specifically to avoid.

**Most of the owner's sketch describes families that do not exist yet, and building
their folders anyway is the anti-pattern Kotlin's own conventions warn against.**
`base/hud/{speed,avg,position,recordingTrip}`: `SpeedHud`, `PushToTalkButton`,
`Obd2SignalLostLabel` (MapHud.kt) and `MapPositionMarker` (MapCamera.kt) are each
single-caller from `MapScreen.kt` — no average-speed HUD composable exists anywhere
(01 §B, confirmed by a declaration-line grep for "average"/"recording" returning zero
hits), and no recording-indicator composable exists at all. `base/button/{confirm,deny,
download,progress}`: the one real shared button, `NavButton`, fails step 2 outright — it
takes `LatLon`/`TravelMode`/`Settings.NavApp` and dispatches through private
intent-launching helpers (`launchNav`, `navigateGoogleMaps`, NavAppLaunch.kt:44-243) that
cannot be pulled apart from it without threading those same values back in. The
"progress" variant the owner named exists — `UpdateProgressButton.kt` — but has exactly
one caller (SettingsHub.kt:167) and stays put by step 3. `base/card/userCard`: no
`userCard` symbol exists anywhere in the codebase; the nearest thing, `RiderCard.kt`, is
single-caller (MapScreen.kt:1343) and stays put too. Creating `base/button/`,
`base/hud/`, or `base/card/userCard` today would be creating packages "just to hold" a
category with zero or one real member — precisely what kotlinlang.org's own conventions
warn against (05 §1: *"Avoid creating files just to hold all extensions of some
class"*, generalized here to packages) — and it is why this proposal's `base/` has 8
files, not the owner's 9 leaf directories.

**A clean design-system name sometimes cannot be had for free.** `SettingsScaffold` and
`SettingsSection` are structurally generic — a titled scaffold with a back button and a
scrolling column; a labelled card — but are named for Settings because nobody has needed
a second, differently-named consumer yet. Renaming them to something feature-neutral the
day a second real consumer shows up is a legitimate future move, but it is explicitly a
**content decision**, not a mechanical one (§4 of the file-split skill: never rename
inside a move), so it cannot ride inside this proposal's stages — it would need its own
commit, justified by an actual second consumer, not by taste.

---

## Verification

The file-split skill's proof-of-inertness (`.claude/skills/detour-file-split/SKILL.md`
§§4-8) was built for a same-package move, where "zero added lines to the source file"
is the whole proof. A repackage cannot produce a zero-added-lines diff — every calling
file gains at least one real `import` line — so the adapted version of the same
discipline, reusing its parts rather than inventing new ones, is:

1. **The moved declaration's body is byte-for-byte identical**, KDoc and annotations
   included (§4) — diff the new file's declaration against `git show <base>:<old-path>`
   and confirm zero differences outside the `package` line.
2. **Every calling file's diff is exactly N added `import` lines, N being the number of
   distinct symbols it calls from the moved file, and nothing else** — the repackage
   analogue of §6's "zero added lines" check. `git diff <base>..HEAD -- <calling-file>`
   should show only `+import ...` lines; any other `+`/`-` in that file means something
   rode along that should not have.
3. **No visibility change is needed.** `base/` is still inside the `:app` Gradle module,
   so `internal` still means module-wide exactly as it does today (03 §4.2) — unlike a
   move into `shared/`, which is a different module and a different skill
   (`detour-shared-core`). Confirm this by *not* touching any `internal`/`private`
   modifier during the move; if one needs to change, something was misclassified.
4. **One move, one commit; imports last, in their own commit** (§2, §7) — a stage's
   move commit touches exactly the old file (or the two files for an extraction) and the
   new file; the import fix-ups at calling files land as a following, separate commit
   (or one per file, if serialized against other in-flight work on the same file).
5. **`./gradlew :app:compileDebugKotlin` green after every commit, not just the last**
   (§8) — unchanged from the skill.
6. This work sits at the "pure move, provable by diff" tier, not the "anything a user
   sees move" tier that would demand a device check (`docs/refactor/mapscreen/specs/
   00-chain-design.md`'s verification table, quoted in full in 02) — but because every
   symbol here is a rendered Compose component, not inert logic, a quick screenshot
   comparison of each touched screen (Social, Settings hub, Settings-servers spoke,
   Circles, Map's background-location dialog, Settings' tracking section) before and
   after Stage 1 is cheap insurance against a byte-for-byte copy that nonetheless
   resolved a `MaterialTheme.colorScheme.*` token differently in its new package — which
   the compiler cannot catch and the import-line check does not look for.

Report, per stage: the exact `git diff --stat` (should list exactly the files the stage
touches), the per-file import-line count against the table in Sequencing, and
confirmation that no non-import line changed in any calling file. "The move looked clean"
is not a result; the diff showing only added `import` lines is.
