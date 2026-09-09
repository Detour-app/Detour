# UI packaging: where the code is, why, and what it could look like

This is the entry point to nine research documents (01-06 plus three
proposals) in this folder, written for anyone who has not read them.
Everything below is either measured directly from the code or cited from
those documents; numbers were re-checked against the code rather than
copied through a chain of summaries, and two places where the research
disagreed with itself or with the code are called out below rather than
smoothed over.

## Why it looks like this

The flat layout is not a decision anyone made. There is no written rule
that says `app/.../ui/` must be one folder of 59 files. Issue **#183** was
filed and closed as a **bug** specifically because a project skill file
claimed a "no subpackages" rule was a settled repo convention, when no such
ruling exists anywhere in the guidelines. The layout is what happens by
default when nobody decides otherwise, and it has grown while the decision
sat open: issue **#184** (open, `p3-later`) recorded `ui/` at 38 files /
13,743 lines when it was filed; it is now **59 files / 18,033 lines** — up
55% in files, 31% in lines, with no placement rule added in between.

Below, each of four areas gets a BEFORE (today) and an AFTER (the shape
proposal C sketches — not a decision, see "Three proposals" below).

## 1. Screens

**Before.** `nav/Destination.kt` declares the app's real structure: 15
navigation destinations (13 when #184 was filed — it grew too) plus a
7-member `SettingsSpoke` family. `ui/` ignores that structure entirely and
is one flat directory of 59 files. Two files are over the repo's 1,000-line
hard limit: `MapScreen.kt` (1,353 lines) and `SettingsScreen.kt` (1,228);
11 of 59 files are over the 500-line soft limit. Some file/symbol names are
actively misleading: `SettingsHub.kt` declares `SettingsScreen`, and the
file named `SettingsScreen.kt` declares `SettingsSpokeScreen`, not
`SettingsScreen`.

```
ui/                              (flat, 59 files)
  MapScreen.kt (1353)  SettingsScreen.kt (1228)  CirclesScreen.kt (896)
  FriendsScreen.kt (883)  HistoryScreen.kt (586)  ... 54 more, same level
```

**After (proposal C's shape).** One package per *feature area* — a set of
destinations whose implementation files overlap. Not one per destination:
there are 22 destinations (15 plus 7 settings spokes) but 14 packages,
because `SettingsScreen.kt` alone serves 8 of them and `CirclesScreen.kt`
serves 2. The group list has to be written down explicitly; it is not
derivable per-file. Each package
holding its own `components/` (renders), `state/` (state holders), and
`utils/` (pure helpers) tiers — populated only where real content exists:

```
features/
  mapscreen/   components/ (21)  state/ (4)  utils/ (2)
  settings/    components/ (7)   state/ (empty — see caveats)  utils/ (empty)
  history/     components/ (1)
  circles/     components/ (1)   <- both Circles routes, one feature
  ... 10 more feature packages, 1-2 files each
```

This does **not** shrink `MapScreen.kt` or fix `MapBottomSlot` — packaging
moves files, it does not remove the reason they're long (see caveats).

## 2. Components

**Before.** Of 128 non-private top-level declarations in `ui/`, only about
**17 are genuinely shared** (called from 2+ distinct files) — everything
else has exactly one caller. The busiest: `ListCard` and `SubScreenTopBar`
at 12 callers each, `glassCardColors`/`Modifier.glassBorder` at 11,
`ConfirmDialog` at 9. Two real shared components are trapped inside screen
files rather than living where a shared component should: `HubRow` inside
`HubScreen.kt`, `SettingsSection` inside `SettingsScreen.kt`.

**After.** A global `components/` tier (9 files, flat) for anything called
from 2+ features, plus a `components/theme/` sibling — and each feature
gets its *own* `components/` for things it alone uses. This is the fix for
the trapped-component problem: `HubRow` and `BackgroundLocationDisclosure`
(currently stuck in `MapDialogs.kt`) get extracted out to the shared tier.

| Global `components/` file | Holds |
|---|---|
| `AppBar.kt` | `SubScreenTopBar` (12 destinations) |
| `Cards.kt` | `ListCard`, `CardDivider`, `SectionLabel` |
| `GlassSurface.kt` | `glassCardColors`, `glassContainerColor`, `Modifier.glassBorder` |
| `ConfirmDialog.kt` | `ConfirmDialog` (9 destinations) |
| `Rows.kt` | `HubRow` (extracted from `HubScreen.kt`) |
| `BackgroundLocationDisclosure.kt` | extracted from `MapDialogs.kt` |
| `Pills.kt`, `TripCardRenderer.kt`, `TravelModeIcon.kt` | the rest |

All three proposals agree on roughly this set — it is the least contested
part of the research.

## 3. Services

**Before.** `app/src/main/AndroidManifest.xml` declares 1 activity
(`MainActivity`), 7 services, and 6 receivers (4 in `main`, 2 in `debug`),
plus 1 content provider — each already living in its own feature package
(`tracking`, `notif`, `update`, `car`, `convoy`, `media`). The useful split
is not "service vs. receiver," it's **thin shell vs. the work itself**: 8
classes whose whole body constructs collaborators and delegates (13 to 137
lines each, ~560 lines total) versus classes that *are* the feature —
`TripTrackingService.kt` at **1,736 lines, the largest file in the repo**,
over the 1,000-line hard limit. Separately, `MainActivity.kt` is 573 lines,
of which roughly 360 (63%) is the navigation host (`AppRoot`, `resetTo`,
`TripDetailEntry`, `RouteEditorEntry`), not entry-point work.

**After.** An `entry/` tier gathers the 8 thin shells — regardless of which
feature or which source set they came from — with 8 manifest edits:

| Class | Lines | From |
|---|---|---|
| `DetourCarAppService` | 13 | `car/` |
| `DetourMessagingService` | 28 | `notif/` |
| `BootReceiver` | 53 | `tracking/` |
| `InstallResultReceiver` | 56 | `update/` |
| `GeofenceWakeReceiver` | 78 | `tracking/` |
| `DebugTripEndedReceiver` | 79 | `app/src/debug` |
| `DebugReplayReceiver` | 117 | `app/src/debug` |
| `PlaceGeofenceReceiver` | 137 | `tracking/` |

`TripTrackingService.kt` and `ConvoyLiveService.kt` stay exactly where they
are — they are the feature, not a shell, and splitting them is a separate,
state-ownership question (see caveats), not a packaging one. `MainActivity`
shrinks to the bare `ComponentActivity` (`onCreate`, cold-start work, OAuth
redirect handling); the nav host is lifted out into `nav/AppRoot.kt`. The
declarations move verbatim, but `MainActivity.kt` itself gets ~360 lines
shorter — this is an extraction, not a file move. See "What actually changes"
below.

## 4. Platform specific

**Before.** `shared/` (KMP `commonMain`) holds business and API logic for
all surfaces; `app/` holds Compose rendering plus Android platform
services. `architecture.md` §1 names nine `app/` packages "platform
ports": `net`, `ble`, `obd2`, `audio`, `media`, `notif`, `auth`, `update`,
`perf`. Android import density confirms most of them really are platform
code — `update` (51 imports / 11 files), `notif` (36/10), `ble` (23/1) —
but also confirms two are misplaced domain logic sitting in `app/` by
mistake: `net/` has **zero** Android imports across both its files, and
`map/` has 2 imports across 10 files and 1,012 lines. `architecture.md`
already flags `map/` and `data/` as "Android-side, not moved yet — audit
these"; this research adds `net/` to that list.

A port here is not one file — it's a **triple**: an interface, a real
adapter, and a simulation/no-op adapter, spanning `src/main`, `src/debug`,
and `src/release`, all sharing one fully-qualified name (e.g.
`tracking.LocationSources` exists twice, identically named, once per
source set; the compiler picks one per build variant). This is the
mechanism that lets GPS replay run **faster than real time**: the real
clock (`ScaledDriveClock`, ships in every build) is selected only in debug
builds; release gets a no-op selector with the same name.

**After.** A `lib/` tier gathers exactly the nine named "platform ports"
(61 import lines, 40 files touched — a bounded, one-time cost) as
`lib/<name>` subpackages. `map/`, `data/`, `tracking/`, and `convoy/` do
**not** join `lib/`, on purpose: the architecture guide already marked them
as transitional, and giving them a permanent-looking new address would
misrepresent that. Because ports are triples, any future rename touching
one **must move all three source sets in the same commit** and be verified
with a release-variant compile — this repo has already been bitten once by
a variant-only miss, caught only by `:app:compileGithubReleaseKotlin` in
CI, after a local `assembleRelease` had already passed.

## Three proposals — no decision made

Three documents in this folder sketch different axes. **None is chosen.**
Issue #184 is where that decision belongs.

| | Axis | Scope | Cost |
|---|---|---|---|
| **A** | design-system-first | `ui/` only | 7 files move to `base/`; no nesting introduced anywhere |
| **B** | feature-slice-first | `ui/` only | one package per feature area under `ui/`, plus a small shared tier |
| **C** | React-informed, two-tier | whole `app/` module | fullest of the three; the shape the owner is leaning toward |

The AFTER sections above use C because it's the most complete, not because
it has been picked.

## What it costs

A full nesting of `ui/` costs roughly **300-370 new import lines** (302 is
the best point estimate after excluding parser artifacts on extension
functions) across the 59 files, and **72 top-level `internal`
declarations** would need their import paths updated at their call sites
(one research document undercounts this at 65; 72 is what both a direct
grep and a second document agree on). There is **zero** build/lint/CI
coupling to the literal `ui` package path — no Proguard rule, no lint
baseline, no CI step references it.

The lever that matters: **Kotlin does not require a file's `package` line
to match its directory**, and nothing in this repo's tooling (detekt only —
no ktlint, no spotless configured) checks that they agree. A `git mv` that
leaves the `package` line untouched changes **zero** import lines anywhere,
because every fully-qualified name stays identical. That turns one big,
all-or-nothing rewrite into many small, independently safe commits — the
directory can move ahead of the package declaration catching up.

## Two cheap wins that need no decision

Both are safe today, independent of which proposal (if any) wins, because
every declaration involved is far under the 7-parameter ownership gate
(`boundaries.md` §8.4) — there's no state-ownership problem to solve first.

1. **Extract the nav host from `MainActivity.kt`.** The owner suspected
   `MainActivity` was cluttered with alarms, geofencing, and debug hooks —
   checking all four manifests shows that's already false; every service
   and receiver already lives in its own feature package. What's actually
   wrong is that ~360 of `MainActivity.kt`'s 573 lines are the navigation
   host (`AppRoot` takes **zero** parameters, `resetTo` 1, `TripDetailEntry`
   2, `RouteEditorEntry` 3). Moving them to `nav/AppRoot.kt` is a pure,
   provably inert move.
2. **The `entry/` tier**, above — 8 files across 5 packages, plus 8
   manifest edits, none of which touches `components/` or `features/`.

## What actually changes, and what this step does not fix

Worth being exact, because "structural change" is easy to over-read.

**No logic changes.** Every declaration that moves is copied verbatim,
KDoc included. Nothing changes what the app does. Three proposals, all the
same on this point.

**But files do change.** Three tiers, and only the first leaves file
contents untouched:

| Tier | What happens | Example |
| --- | --- | --- |
| Pure file move | whole file unchanged; its `package` line and callers' imports change | the 7 shared files → `components/` |
| Extraction | a declaration is cut out of a file that keeps existing, so **two** files change | `HubRow` out of `HubScreen.kt`; the nav host out of `MainActivity.kt` |
| Content decision | files merged or a symbol renamed | merging two dialog files; every proposal marks these optional and separately committed |

Roughly 7-8 files get edited rather than moved. The discipline all three
proposals adopt: extraction and repackaging never share a commit — extract
in place keeping the same package, prove it inert with the zero-added-lines
check from `detour-file-split`, then a separate commit changes the package
line.

**File sizes are not fixed by this work.** After every stage of every
proposal:

    MapScreen.kt              1353 lines   unchanged
    SettingsScreen.kt         1228 lines   unchanged
    TripTrackingService.kt    1736 lines   unchanged, and out of scope
    MainActivity.kt            573 -> ~210 lines  (the one real reduction)

`MapScreen.kt` and `SettingsScreen.kt` stay over the 1000-line hard limit.
That is deliberate, not an oversight: breaking them up is a state-ownership
problem gated by the caveat below, and it is separate, later work. This step
decides where files live. It does not make them smaller.

## Two honest caveats

- **`01-ui-inventory.md` §B contains a confirmed error.** It claims no
  average-speed HUD composable exists and that average speed is only
  voice-announced. That's wrong: `SpeedHud` in `ui/MapHud.kt` renders
  exactly that, landed in commit `461281ae` on 2026-09-07 — two days
  *before* the inventory was captured, so the claim was wrong at capture
  time, not merely stale. The rest of §B's "no X exists" claims (no shared
  chips, no shared empty/error states, no recording indicator) have not
  been re-verified — treat them as unconfirmed.
- **`boundaries.md` §8.4 requires a state owner before extraction.**
  `MapBottomSlot` already takes **46 parameters** (`SpinSheet` 23,
  `SpinDock` 19). Moving these files into any new package changes their
  package line and nothing about those signatures. The parts of `ui/` that
  most look like they need pulling into components — the Map cluster — are
  exactly the parts that must wait for an owner first. Packaging cannot
  shortcut this.

## Where to read more

`01` has the full per-file inventory (with the §B caveat above). `02` has
what the guidelines actually say — and the #183/#184 history. `03` has the
cross-surface reuse and move-cost numbers. `04` and `05` are external
precedent — real Android/Compose codebases, and whether deep nesting is
idiomatic Kotlin (short answer: no, not past one level). `06` is real
findings that fell out of this research but are out of scope (the largest
file in the repo, `car/`'s own factoring, dead code). `proposal-a/b/c` are
the three candidate shapes in full.
