# Proposal C: React-informed nesting, at full app-module scope

Evidence base: `01-ui-inventory.md`, `02-repo-guidelines.md`, `03-move-cost.md`,
`04-android-compose-precedent.md` and `05-kotlin-package-idiom.md` (all in this
directory, captured against commit `efa6c9ad`, 2026-09-09). Every
count, file:line, and git-blame date below was re-verified directly against the
tree while writing this document — three findings below (§ "The SpeedInfoPill
case," the `lib/` cost table, and the package/directory decoupling in
Sequencing) correct or extend the earlier research rather than merely restate
it, and each says so at the point it matters.


---

## The axis, in one paragraph

A file or declaration's home is decided by two independent questions, asked in
order: **first, by kind** — does it render (`@Composable`, `Modifier`,
`Canvas`, a custom `View`)? does it hold or derive state across recompositions
(a `remember` factory, a state-holder class, a `CompositionLocal`, a
process/session-scoped object read from Compose)? or is it a pure function or
constant with no Compose import and no platform type? — which decides which of
`components/`, `state/`, or `utils/` it lands in; **second, by reach** — is it
called from exactly one `Destination` (or `SettingsSpoke`, or `SettingsSpoke`
cluster), or from two or more? — which decides whether that tier is the
feature-scoped one (`features/<name>/components|state|utils/`) or the global
one (top-level `components|state|utils/`, siblings of `features/`). A screen
composable that is itself the thing a `Destination` routes to always answers
"kind = renders, reach = one" trivially and lands in its own
`features/<name>/components/`, named exactly as it is today. Platform ports
with no `Destination` and nothing to render — `audio/`, `ble/`, `net/`,
`obd2/`, `media/`, `perf/`, `update/`, `auth/`, `notif/` — are neither a
feature nor a component; they answer "kind" and "reach" with "neither," and
that is what routes them to `lib/` instead. A handful of packages answer
"neither" the same way but do not join `lib/` regardless —
`architecture.md`'s own diagram already has a different verdict for them; see
"The `lib/` decision." This test is meant to apply to a new file the same
way on day one of the migration and on the last day of it — nothing about it
depends on how much of the tree has moved yet.

---

## The React-to-Kotlin translation table

| React tier | Kotlin home | Name | Why / why not |
|---|---|---|---|
| `components/` (global) | top-level `com.jellemax.detour.components/`, flat, with one sibling subfolder `components/theme/` | **`components/`** | Translates cleanly. This tier does **not** exclude a component for taking a `data/`-layer type in its signature (see the `TripCardShareDialog` case below) — reach (≥2 destinations) is the only membership test, matching Now in Android's looser `core:ui` ("dependent on the data layer since it renders models," `04-android-compose-precedent.md` §2d) rather than its stricter `core:designsystem`. |
| `features/X/components/` | `com.jellemax.detour.features.<name>.components/` | **`features/<name>/components/`** | Translates cleanly. `components` is an explicit child rather than the feature's package root holding everything undifferentiated. `<name>` is the destination lowercased, concatenated, no separator — see the placement test for the naming rule and its one collision (`map`). |
| `features/X/api/` | `shared/src/commonMain/kotlin/com/jellemax/detour/data/`, one module up | **stays in `shared/`, not created in `app/`** | **Truncated at the presentation boundary — correctly, not as a compromise.** See "Why `features/X/api/` resolves to `shared/`" below. |
| `features/X/hooks/` | `com.jellemax.detour.features.<name>.state/` | **`state/`**, not `hooks/` | Renamed, not moved-as-is. Kotlin already has a name for this — `docs/guidelines/state-holders.md` calls it a "state holder" throughout, never a "hook," and the existing instances in this codebase (`rememberMapPermissions`, `rememberRetainedMap`, `MapScreenState`, `LocalDriveClock`) are `remember` factories, classes and `CompositionLocal`s, not React's closure-and-hook-order machinery. Same job (feature-local derived/retained state), Kotlin's own vocabulary. |
| `features/X/utils/` | `com.jellemax.detour.features.<name>.utils/` | **`utils/`** | Translates cleanly for pure, non-rendering, feature-scoped functions and constants (`MapCameraTuning.kt`'s tuning constants, `SpinShare.kt`'s extension mappers). |
| `routes/` | the existing `com.jellemax.detour.nav/` package, grown | **`nav/`**, not a new `routes/` | Renamed on arrival, and for a specific reason: Detour already has a product feature named "Routes" (`Destination.Routes`, `RoutesScreen.kt` — the saved-routes list), so a literal `routes/` package would collide with `features/routes/` at the string level. `nav/Destination.kt` already holds exactly the "which page, and how you get to it" concern React's `routes/` names; this proposal grows it with `AppRoot` (currently `MainActivity.kt:211-526`) extracted into `nav/AppRoot.kt`, so the whole "page construction and routing between them" tier lives in one place. `MainActivity.kt` itself — the `ComponentActivity` — is not moved: an Android entry point is discovered by the manifest, not by convention, and reducing it to Activity boilerplate that calls `nav.AppRoot()` is the correct shape whether or not this proposal lands. |
| `lib/` | `com.jellemax.detour.lib.<name>/`, one level added in front of exactly the 9 packages `architecture.md:38-46` already labels "platform ports" | **`lib/`** | Translates at a small, precisely bounded cost — this tier already exists, under that exact name, in the architecture guide; see "The `lib/` decision" below. `tracking/`, `map/`, `nav/`, `data/` and `convoy/` are **not** moved under it — see the same section for why each is excluded. |
| (no React equivalent) | `car/` stays exactly where it is: flat, top-level, sibling to `features/`, `components/`, `lib/`, `nav/`, `entry/` — and now known to host two Android surfaces (Auto, Automotive), not one | **`car/`, mostly unchanged** | Does not translate at all — see "`car/` has no React analog" below. Loses exactly one file (`DetourCarAppService.kt`, 13 lines) to `entry/` — see that section. |
| (no React equivalent) | `com.jellemax.detour.entry`, flat, plus a debug-source-set counterpart | **`entry/`** | The owner's own addition, not React's — see "`entry/` — no React analog, and the owner wants it anyway" below. |

Not every tier in the owner's sketch is React-specific, and the table above
is honest about which is which. `features/` plus a thin, separately-scoped
shared `components/` is not a React idiosyncrasy — Google's own
modularization guidance converges on the identical shape independently:
"feature modules correspond to screens or navigation destinations," plus a
"UI module" for a shared "widget collection," with the only stated
constraint being that the shared module's public surface stay minimal
(`04-android-compose-precedent.md` §1d, §2d — Now in Android's
`core:designsystem`/`core:ui` split predates and does not cite React at all).
So `features/` + `components/` is this proposal's strongest tier precisely
because two unrelated ecosystems arrived at it separately. `hooks/` (renamed
to `state/`, Kotlin's own vocabulary — see the table), PascalCase directory
segments (illegal by Kotlin/Android's own casing rule, see the placement
test), and one-composable-per-file (see "The coupled file-granularity
decision" below — Kotlin's own conventions license, and this codebase
already practices, several declarations per file) are the parts of React's
shape that are genuinely React-specific and do not survive translation as
written. `features/X/api/` is a fourth: not a gap to fill, but a place the
KMP architecture has already, deliberately, made a different and better call
than React's own.

### Why `features/X/api/` resolves to `shared/`

The repo owner's own answer to this question, stated plainly rather than
inferred: business logic and API logic live in `shared/` on purpose, so
iOS, Android, Auto and Automotive can share them, and each platform keeps
only its own rendering code plus its own platform services. That is a
decision already made, not a gap this document needs to fill — React's
feature slice is truncated at the presentation boundary here, and the
truncation is the architecture working as intended, not a compromise.

In React, a feature folder's `api/` holds that feature's own data-fetching
calls — a full vertical slice, data access included. Detour's data access
lives in `shared/src/commonMain/kotlin/com/jellemax/detour/data/` on purpose:
`docs/guidelines/architecture.md:53-56` calls `data/` "64 top-level `object`
singletons" that "own persisted or fetched state and publish it," and
`.claude/skills/detour-shared-core/SKILL.md`'s whole subject is that this is
how the phone, Android Auto and iOS stay on one implementation instead of
three. `docs/guidelines/multiplatform.md` (§5) states the rule for what a
screen may do with that layer directly: "collect flows, hold ephemeral UI
state, call store suspend actions... A screen may not: contain a threshold
constant that another surface also needs, own a state machine, or be the only
place a rule exists."

The supporting evidence for *why* a per-feature `api/` folder under `app/`
would be a worse answer than the one already chosen — offered here because
the owner's resolution deserves the reasoning behind it stated, not just
adopted:

1. **Duplicate `shared/`.** Re-declaring a thin per-feature wrapper around
   `Trips`, `Routes`, `FriendsStore` etc. inside `features/history/api/` would
   create a second name for something `shared/…/data/` already names once,
   for every platform. That is exactly the drift `detour-shared-core` exists
   to prevent, and it would make Android Auto and iOS's actual data layer
   *harder* to find, not easier — a reader would have two places to check and
   no way to know which one is real without reading both.
2. **Stand empty.** Confirmed today: `grep -c "^import com\.jellemax\.detour\.ui" app/src/main/java/com/jellemax/detour/ui/*.kt` returns 169 direct
   `import com.jellemax.detour.data.*` sites inside `ui/` alone (per
   `02-repo-guidelines.md`'s citation of issue #184) — every one of those is a
   screen composable calling a store directly, with nothing feature-scoped in
   between. Fourteen empty `features/X/api/` folders are worse than fourteen
   absent ones: an empty folder reads as "nothing goes here," which is false —
   plenty goes into that concern, it just isn't scoped by feature. A folder
   that always contains nothing is a lie about where data access happens, not
   a placeholder for it.

**Resolution:** this tier is not created. When someone goes looking for "the
API calls this feature makes," the answer is: read the `import
com.jellemax.detour.data.*` block at the top of the feature's own screen file,
or open the named `shared/…/data/<Store>.kt` file directly — there is no
third place to check, and this proposal does not invent one. If a real
per-platform seam is ever needed inside `shared/` that is organized by
feature rather than by data-kind, that is a `shared/` restructuring question
for `detour-shared-core` to answer on its own terms, not something this
packaging proposal can decide by omission.

### The `lib/` decision

This tier does not need to be invented — it is already named.
`docs/guidelines/architecture.md:38-46` diagrams `app/`'s top-level layout and
labels one line of it explicitly:

```
├── net/ ble/ obd2/ audio/ media/ notif/ auth/ update/ perf/  platform ports
```

Nine packages, called "platform ports" by the architecture guide itself. This
proposal's `lib/` is that line, given a real directory. The same diagram, two
lines down, marks a different set with a different verdict:

```
├── map/ nav/   Android-side policy that has not moved yet   ← audit these
└── data/       Android-side data that has not moved yet     ← audit these
```

`tracking/` gets a third, separate description in the same diagram
("foreground service, sensors, location plumbing") — the guide itself never
calls it a "platform port." So there are three groups here, not one, and
they get three different treatments:

1. **The nine named "platform ports"** (`net`, `ble`, `obd2`, `audio`,
   `media`, `notif`, `auth`, `update`, `perf`) → `lib/`. This is recognizing
   an existing category, not drawing a new boundary.
2. **`map/` and `data/`**, flagged by the same document as content that has
   not finished migrating — `map/`'s pure decisions
   (`NavPolicy`, `FollowCamera`, `CameraAuthority`) and `data/`'s Android-side
   stores are both candidates the architecture guide already wants audited
   toward `shared/…/data/` and `shared/…/drive/`. Giving either one a new,
   more-permanent-looking address under `lib/` sends the wrong signal about
   code the guide already wants to see shrink or move — so neither joins
   `lib/`. `nav/` is the third member of that flagged line, but it does not
   need auditing toward `shared/` under this proposal's own scope — see the
   translation table's `routes/` row, which repurposes `nav/` as the routes
   tier's home rather than leaving it as unaudited cruft.
3. **`tracking/`** — 20 files, the foreground-service/sensor layer, never
   called a "platform port" by the guide itself, and the single largest
   `app/`-side package outside `ui/`. Grouping it under `lib/` would be this
   document inventing a category the architecture guide did not draw;
   leaving it as its own top-level package matches what the guide already
   says about it.

Measured directly (import lines matching
`^import com\.jellemax\.detour\.<pkg>\.` across `app/src/main`,
`app/src/test`, `app/src/androidTest`), for the nine packages that actually
move:

| Package | Import lines | Files touched |
|---|---|---|
| `notif` | 16 | 8 |
| `obd2` | 9 | 5 |
| `update` | 9 | 4 |
| `ble` | 8 | 7 |
| `audio` | 6 | 5 |
| `net` | 6 | 6 |
| `auth` | 5 | 3 |
| `perf` | 2 | 2 |
| `media` | 0 | 0 |
| **Total** | **61** | 40 (distinct) |

Confirmed separately: `shared/src/` and `iosApp/` import zero of these
app-side packages — nothing here crosses the KMP module boundary, so this
whole decision is contained inside `:app`. `map/` (42 lines/8 files),
`tracking/` (40 lines/26 files), `convoy/` (2 lines/2 files — not named in
the architecture diagram at all, likely added after it was last updated, the
same drift `01-ui-inventory.md` found for `ui/`'s own file count against
issue #184) and `data/` (414 lines/114 files) all stay flat, top-level
siblings of `lib/`, `features/`, `components/`, `nav/`, and `car/` — unchanged,
unmoved, at their current addresses. **Verdict: `lib/` holds exactly the nine
packages the architecture guide already calls platform ports, at a
61-import-line, 9-package, one-time cost. Nothing else joins it** — not
because grouping the rest would be expensive (it would not; `map/`'s own cost
is smaller than several of the nine that do move), but because the
architecture guide has already made a call about what those four are for,
and gathering them under a new parent would paper over that call rather than
honor it. This is the point at which this proposal's scope is narrower than
the owner's sketch implied, and correctly so.

This buys **zero encapsulation**, and this document does not claim otherwise:
`internal` is module-scoped, not package-scoped (`05-kotlin-package-idiom.md`
§5), so every `internal` declaration in, say, `lib/notif/` is exactly as
visible to `car/` and `app/src/test` nested one segment deeper as it is
today. Nothing here is a Gradle-module boundary. If real
encapsulation for one of these packages is ever wanted, that is a
Gradle-module extraction — a new `build.gradle.kts`, an explicit
`api`/`implementation` dependency edge from `:app`, and (per
`04-android-compose-precedent.md` §6's flagged-unverified but plausible
`sharedLogic`/`sharedUI` split) likely a decision about whether the module a
future `car/`-only or `data/`-only target depends on may touch Compose at
all. That is a separate, much larger decision. Nothing in this proposal
requires it, and nothing here prices it further.

**These nine packages are Android-only, not KMP glue, and it is worth being
precise about that distinction.** `grep -rln "^actual " app/src/main`
returns **zero** files — there is no `actual` declaration anywhere in
`app/src/main`. The `expect` declarations these packages might be assumed to
satisfy (`prefs`, `securePrefs`, `appFilesDir`, `fileSystem`,
`PlatformLock`, `systemDecimalSeparator` — all in
`shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt:48-95`)
are satisfied in `shared/src/androidMain/kotlin/com/jellemax/detour/data/Platform.android.kt`,
a different module entirely. So `lib/`'s nine packages are not the Android
half of an `expect`/`actual` pair — they are ordinary Android services
(`ble/`, `audio/`, `obd2/` talk to hardware; `notif/`, `update/` talk to
Play/FCM/the OS) that `shared/` never references and never will through
`expect`/`actual`, because that mechanism is already fully spent elsewhere
in the tree (§5.1's six declarations, one file).

**A second measurement — Android/AndroidX import density per package — is
useful here only as a diagnostic, and this document does not use it to
decide `lib/` membership.** Measured (imports matching
`^import (android|androidx)\.`, per package, across `app/src/main`):

| Package | Android imports | Files | LoC |
|---|---|---|---|
| `update` | 51 | 11 | 1834 |
| `notif` | 36 | 10 | 817 |
| `ble` | 23 | 1 | 586 |
| `audio` | 16 | 2 | 332 |
| `media` | 7 | 1 | 182 |
| `obd2` | 5 | 1 | 563 |
| `auth` | 3 | 2 | 188 |
| `perf` | 4 | 2 | 346 |
| `net` | **0** | 2 | 442 |
| `map` | 2 | 10 | 1012 |
| `nav` | 2 | 2 | 281 |

Seven of the nine named platform ports sit at real, if uneven, Android
density (21-36 lines per import at the dense end; `obd2` is the sparsest at
113 lines per import, and is flagged below rather than asserted about).
`net/` is the outlier worth naming: **zero** Android imports across both its
files, and one of them (`OkHttpRelaySocket.kt`) implements `RelaySocket` —
one of `shared/`'s own three rationed interfaces (`multiplatform.md` §5.2) —
while the other (`ConvoyLiveClient.kt`) imports almost exclusively from
`com.jellemax.detour.data`/`drive`. That is exactly the profile of domain
logic sitting in `app/` rather than a platform port, and it corroborates
`architecture.md`'s own flag on `map/` (2 imports across 1,012 lines) with a
second, independently-measured example the diagram itself does not name.
**This document does not act on it** — moving `net/`'s content toward
`shared/` (it cannot go verbatim; `shared/`'s own HTTP layer is Ktor, not
OkHttp, so this would be a rewrite, not a move) is a `detour-shared-core`
content decision, not a packaging one. `net/` stays flat, top-level,
unmoved, flagged alongside `map/` and `data/` — the density measurement adds
one more name to that flagged list, it does not add a member to `lib/`.

**Density must never be read as a rule for splitting a port across tiers,
and the reason is structural, not a style preference — see the next
section.**

### Ports are triples, not packages

A "platform port" in this codebase is not one file, and grouping by Android-
import density would cut it in the wrong place. It is three coordinated
declarations sharing one fully-qualified name, one per build variant:

- **The port interface**, near-zero Android density by construction — a
  plain Kotlin contract. `tracking/LocationSource.kt:45`
  (`internal interface LocationSource`, plus `LocationBatchListener` at
  `:95`); `tracking/DriveClock.kt:43` (`interface DriveClock`).
- **The real adapter**, Android-heavy, ships in every variant.
  `tracking/FusedLocationSource.kt` (75 lines, Play Services, implements
  `LocationSource`); `tracking/ScaledDriveClock.kt` (159 lines, implements
  `DriveClock`) — note this one lives in `src/main`, not `src/debug`: the
  ability to run the drive clock faster than real time ships in every
  build, release included; only *which* clock gets selected is
  variant-gated, not the capability itself.
- **The simulation adapter and its selector**, in
  `app/src/debug/java/com/jellemax/detour/tracking/`:
  `LocationSources.kt` (67 lines, `object LocationSources` — its `create()`
  wraps the real `FusedLocationSource` inside `ReplayLocationSource`, 214
  lines, the actual replay logic), `DriveClocks.kt` (32 lines,
  `object DriveClocks`), plus `ReplayAutoDetect.kt`, `ReplayFixGate.kt`,
  `ReplayMode.kt`.
- **The no-op counterpart**, in
  `app/src/release/java/com/jellemax/detour/tracking/`: `LocationSources.kt`
  (50 lines), `DriveClocks.kt` (24 lines), `ReplayFixGate.kt` (27 lines) —
  same package, same object name, a shipped-build stand-in with nothing to
  simulate.

`app/build.gradle.kts:190-201`'s own comment names the exact failure mode
this shape produces when a rename misses a leg, verbatim: *"`initWith`
above copies a build type's settings, not its sources. Both derived types
therefore see neither `src/debug/` nor `src/release/`, which is invisible
until something exists once per variant — ReplayFixGate does (the real
filter for a replay rig, a no-op for a shipped app), and
`:app:compileGithubReleaseKotlin` was the first thing to say so, in CI,
after `:app:assembleRelease` had passed locally."* Wiring, confirmed at
`app/build.gradle.kts:202-203`: `getByName("githubRelease").java.srcDir("src/release/java")`,
`getByName("automotive").java.srcDir("src/debug/java")`.

**This is why density cannot be `lib/`'s membership test.** Grouping by
Android-import count alone would place `LocationSource.kt`/`DriveClock.kt`
(near-zero, pure interfaces) in one tier, `FusedLocationSource.kt`/
`ScaledDriveClock.kt` (Android-heavy) in another, and the entire
debug/release selector-and-fake-and-no-op set in a third that a per-package
grep over `app/src/main` alone never even sees — `app/src/debug` and
`app/src/release` are separate source roots the density table above never
walked. A port is organized as a unit — every leg inside whichever package
already owns it (`tracking/`, in both examples here) — never split across
`lib/` and somewhere else by an import count. Density stays a diagnostic
for spotting misplaced domain logic (`net/`, `map/`), and is explicitly not
a rule for deciding where a port's legs live.

**`ui/DriveClockLocal.kt` is a port's injection point, not a port leg, and
it sits inside the UI tier on purpose.** It is 52 lines, a
`staticCompositionLocalOf<DriveClock>` whose default reads
`DriveClocks.current` (`DriveClockLocal.kt:52`) — the already
variant-selected clock. Under this proposal it moves with the rest of
`features/mapscreen/state/`, exactly as this document already places it (its
own KDoc names only Map-cluster consumers: `MapCameraLoops`,
`MapPositionMarker`, `MapNavigationSession`, `MapHazardAlerts`,
`rememberActiveTripCardState`). The port itself — `DriveClock` the
interface, `ScaledDriveClock` the real adapter, `DriveClocks` the
debug/release selector pair — stays in `tracking/`, untouched, because
`tracking/` does not move under this proposal at all.

### Source-set atomicity: the constraint a package rename has to respect

Because a port's legs share one package across three source sets, **any
future package rename touching `tracking/`, or any other package that turns
out to have a `src/debug`/`src/release` counterpart, must move every source
set's files together, in the same commit — never staggered.** A `package`
line changed in `src/main/.../tracking/Whatever.kt` but not yet in
`src/debug/.../tracking/Whatever.kt` leaves the debug/automotive variant
resolving the old package while the main-sourced sibling now resolves the
new one; each file's own `package` line stays internally consistent, so this
compiles fine in isolation and fails only where the moved package's own
consumers (or the compiler's variant-specific source merge) expect the
sibling that did not move. The repo has already been bitten by exactly this
class of gap, in an adjacent form: the `app/build.gradle.kts:190-201`
comment quoted above records `:app:compileGithubReleaseKotlin` — not
`:app:assembleRelease`, which had already passed locally — as the task that
first caught a variant-only omission.

**This proposal is not exposed to that risk today, because no package it
moves has a `src/debug`/`src/release` counterpart** — `find app/src/debug
app/src/release -type d` shows the only two packages with any presence in
either source set are `tracking/` (the port-triple files above) and
`debug/` itself, and this proposal leaves `tracking/` flat and unmoved (see
"The `lib/` decision") and moves only two files out of `debug/`, neither of
which has a `src/release` counterpart to keep in step (`app/src/release`
holds no `debug/` subdirectory at all). **The corollary strengthens this
proposal's own mechanism rather than merely avoiding a risk it doesn't
carry**: Stage 2's `git mv`-with-unchanged-`package`-line approach (see
Sequencing) is the *correct* mechanism for a variant-gated codebase in
general, not only a convenience — every fully-qualified name stays
identical in all three source sets through a pure directory move, so `main`,
`debug`, and `release` keep resolving exactly as they do today regardless of
which of them a given file happens to live in. The risk lives entirely in
the *package-alignment* stages, and the rule for any future proposal that
does touch a source-set-parallel package is unconditional: edit `src/main`,
`src/debug`, and `src/release` together, as one commit, and verify with a
release-variant compile — see Verification.

### `car/` has no React analog

Android Auto renders through the Car App Library's template system, not
Compose (`docs/guidelines/decisions.md` §12.2, point 1: "Android Auto cannot
render Compose... the car screens stay hand-written either way"). React has
no concept of "a second rendering runtime for a subset of the same
application's features" — its entire vocabulary assumes one render target.

**And `car/` is smaller than it looks, in the direction that matters here:
one code tree, two hosts, not two code trees.** `app/build.gradle.kts:172-180`
declares `automotive` as a build **type** (not a product flavor —
deliberately, per that same block's own comment: "a flavor dimension renames
every existing variant task... A build type only adds `assembleAutomotive`
and leaves the existing names alone"), which drives the same `car/` package
through `androidx.car.app:app-automotive`'s `CarAppActivity` instead of the
Android Auto host — projected on a head unit via Auto, on-device via AAOS.
Confirmed: `app/src/automotive/AndroidManifest.xml`'s only additional
activity is `androidx.car.app.activity.CarAppActivity` (androidx's own class,
not app code), and the manifest's `automotive_app_desc` resource
(`app/src/main/AndroidManifest.xml:57`) is the same descriptor both hosts
read. So there are **three rendering trees** in this codebase — `ui/`
(Compose, phone), `car/` (Car App Library templates, hosted by either Auto or
Automotive), `iosApp/` (SwiftUI) — not four, and `car/`'s relationship to
`features/` is the same either way it is hosted.

Forcing `car/` into `features/` would misrepresent it as a feature when it is
a second UI surface that cuts across several features (Map, driving alerts,
search, navigation) using an entirely different template API. **`car/`'s own
organization does not move.** Five of its six files stay exactly where they
are: `app/src/main/java/com/jellemax/detour/car/`, flat, a sibling of
`features/`, `components/`, `lib/`, and `nav/` at the top of the tree — the
same peer relationship it has with `ui/` today. The sixth,
`DetourCarAppService.kt` (13 lines), leaves for a different, narrower reason
than anything argued above — see "`entry/`" — and its departure is not a
restructuring of `car/`, only the removal of the one file in it that is not
Auto/Automotive-specific rendering code at all.

The 8 symbols it imports from `ui/` today (`02-repo-guidelines.md` §2, full
enumeration) move with their declaring files under this proposal, so `car/`'s
two consuming files get their import paths edited, nothing else:

| Symbol | Old import | New import |
|---|---|---|
| `MAX_FRAME_WALL_S`, `driveFrameDelta` | `com.jellemax.detour.ui.MapCameraTuning` | `com.jellemax.detour.features.mapscreen.utils.MapCameraTuning` |
| `MapOverlays`, `NamedFriendPosition`, `PositionMarker`, `openFreeMapStyleUrl`, `setCamera` | `com.jellemax.detour.ui.MapLibreMap` | `com.jellemax.detour.features.mapscreen.components.MapLibreMap` |
| `formatDistanceKm` | `com.jellemax.detour.ui.Format` | `com.jellemax.detour.utils.Format` |

Confirmed at `car/CarMapRenderer.kt:31-32,40-44` and `car/SpinScreen.kt:40` —
8 import lines, 2 files, exactly as `02-repo-guidelines.md` §2 measured it.
Nothing about visibility changes (`internal` is still module-wide), and
nothing about `car/`'s own template code changes at all.

### `entry/` — no React analog, and the owner wants it anyway

React has nothing resembling an Android `Service` or `BroadcastReceiver` —
the platform's own entry points are outside the tier system entirely. The
owner wants them gathered into one place regardless, because an entry point
is its own concern by `boundaries.md` §8.1's own test: it has one lifetime
(a single platform callback — `onReceive`, `onCreate`), one reason to change
(what triggers it changing, not the feature's own rules), and it owns
nothing past constructing collaborators and delegating. `architecture.md`
§3's own words license grouping by that kind of concern across features
rather than inside one: "Detour's layers are packages inside `:shared`, not
folders inside a feature" — the same principle, applied here to a layer
`shared/` cannot hold, since `Service`/`BroadcastReceiver`/`CarAppService`
are Android framework types with no commonMain existence.

**The cut is not "receiver vs. service," it is thin-shell vs. the feature
itself**, and the measurement makes that precise rather than a judgment
call. Eight classes construct, check a condition, and delegate — nothing
more:

| Class | Lines | Package today |
|---|---|---|
| `DetourCarAppService` | 13 | `car/` |
| `DetourMessagingService` | 28 | `notif/` |
| `BootReceiver` | 53 | `tracking/` |
| `InstallResultReceiver` | 56 | `update/` |
| `GeofenceWakeReceiver` | 78 | `tracking/` |
| `DebugTripEndedReceiver` | 79 | `debug/`, in `app/src/debug/` |
| `DebugReplayReceiver` | 117 | `debug/`, in `app/src/debug/` |
| `PlaceGeofenceReceiver` | 137 | `tracking/` |

~560 lines across 8 classes, none over 137 lines. Five other classes, each
reachable from the same manifests, are not shells — they *are* the feature
their entry point delegates to, and stay exactly where they are:
`tracking/TripTrackingService.kt` (1,736 lines — over the 1,000-line hard
limit, the largest file in the repo), `update/UpdateDownloadService.kt`
(490), `notif/CircleNotifyService.kt` (298), `convoy/ConvoyLiveService.kt`
(237), `media/MediaListenerService.kt` (182). The line-count gap (13-137 vs.
182-1,736) is not the rule itself, only its symptom — the rule is
`boundaries.md` §8.1 read directly: does this class have its own lifetime,
its own reason to change, and does it own anything beyond the call it
delegates? `DetourCarAppService` at 13 lines answers no three times.
`TripTrackingService` at 1,736 answers yes three times — it is not an entry
point that grew long, it is the tracking feature, entered through one.

**Membership rule, decidable per class:** a class extending
`android.app.Service`, `BroadcastReceiver`, or the Car App Library's
`CarAppService`, whose body's only work is constructing its collaborators,
checking a precondition, and calling into an existing owner elsewhere in the
tree — with no state of its own that outlives one callback — belongs in
`entry/`. A class of the same Android base type that itself holds
feature state across callbacks, or contains the feature's own rules, stays
in its feature's package. This does not require a line-count threshold to
apply, though the measured set above happens to sort cleanly on one.

**Confirmed safe to move as a normal, single-source-set relocation:** none
of the six `app/src/main`-resident thin shells (`DetourCarAppService`,
`DetourMessagingService`, `BootReceiver`, `InstallResultReceiver`,
`GeofenceWakeReceiver`, `PlaceGeofenceReceiver`) has a counterpart file in
`app/src/debug` or `app/src/release` — those two source sets hold parallel
files only for `tracking/`'s location and clock ports (see "Ports are
triples, not packages" below) and for the `debug/` package itself, and none
of those parallel files share a name with any of the six. So moving these
six is a plain package change, once per file, with no cross-source-set
coordination required.

**The `app/src/debug` wrinkle, covered rather than glossed over.**
`DebugTripEndedReceiver.kt` and `DebugReplayReceiver.kt` sit in their own
`com.jellemax.detour.debug` package today, physically inside `app/src/debug/`
— a separate source root Gradle layers onto `src/main` only for build types
that include it (`debug` itself, and `automotive` via the explicit
`srcDir("src/debug/java")` at `app/build.gradle.kts:203`). Today, a reader
who sees `com.jellemax.detour.debug.DebugTripEndedReceiver` learns two facts
from the package name alone: it is an entry point, and it never ships to
Play. Moving it to `com.jellemax.detour.entry` (still physically inside
`app/src/debug/java/`) keeps the second fact machine-enforced — the file
still only compiles into `debug` and `automotive`, exactly as before — but
the package name alone no longer announces it; a reader has to also notice
the file's path. **This is a real, small information loss, named here
rather than assumed away**, traded for a consistent rule ("every thin entry
point lives in `entry/`, regardless of which feature or which source set it
delegates from"). Both `entry/` files land at
`app/src/debug/java/com/jellemax/detour/entry/`, same package as their six
`app/src/main`-resident siblings, and the manifest entries that register
them (`app/src/debug/AndroidManifest.xml:26,40`, `android:name` fully
qualified) get their path text edited the same way the six main-set entries
do (`app/src/main/AndroidManifest.xml:135,146,177,185,189,193`) — 8 manifest
edits total, across 2 manifest files, alongside the 8 file moves.

---

## The placement test

Run per file, not per declaration, unless a file's own declarations already
fail `boundaries.md` §8.1-8.3's tests for staying together (different
lifetime, different reason to change) — in which case that split is its own,
separate decision, gated on §8.4 exactly as it is today, and this test is
applied to the pieces that result. Do not fragment a file across
`components/`/`state/`/`utils/` merely to satisfy this taxonomy; §8.5
("a public composable and the private helpers only it uses") licenses a file
mixing kinds, and this test defers to that license.

1. **Is this file's dominant declaration the composable a `Destination` or
   `SettingsSpoke` is routed to** (checked against `nav/AppRoot.kt`'s entry
   table — today `MainActivity.kt`'s `entry<Destination.X>` block)? → it goes
   to a `features/` package, named for that destination (see naming, below).
   Stop here for a screen file — it always lands in that feature's
   `components/`.
2. **Otherwise, what does the file's dominant declaration do?**
   - Renders (`@Composable`, `Modifier`, `Canvas`, a custom Android `View`) →
     `.../components/`.
   - Holds or derives state across recompositions — a `remember` factory, a
     state-holder `class`, a `CompositionLocal`, a process/session-scoped
     object consumed from Compose → `.../state/`.
   - Pure function or constant, no Compose import, no platform type →
     `.../utils/`.
3. **Is it called from ≥2 distinct destinations** (the same "distinct calling
   files, traced to distinct `Destination`s" count `01-ui-inventory.md` §A
   already computes)? → promote to the
   top-level tier of the same name (`components/`, `state/`, or `utils/`,
   sibling of `features/`). A caller count of 2 within one feature's own
   spokes or bottom-sheet cluster does **not** count — trace through
   `nav/Destination.kt`.
4. **Not a feature and not a component at all** — a platform port with no
   `Destination` and nothing rendered, and specifically one of the 9 packages
   `architecture.md` itself already calls a platform port (`audio/`, `ble/`,
   `net/`, `obd2/`, `media/`, `perf/`, `update/`, `auth/`, `notif/`) →
   `lib/<name>/`, unchanged internally. `tracking/`, `map/`, `data/` and
   `convoy/` answer "not a feature, not a component" the same way but do
   **not** move under `lib/` — see "The `lib/` decision" for why each of the
   four stays a flat, top-level sibling instead.

**Naming.** Package segments are lowercase, words concatenated, no
underscore, no camelCase — `developer.android.com/kotlin/style-guide` marks
`com.example.deepSpace` "WRONG!" in a worked example, and CLAUDE.md's own
conventions carry the same rule. `CoverageMap` → `coveragemap`, `SavedPlaces`
→ `savedplaces`, `RouteEditor` → `routeeditor`, `TripDetail` → `tripdetail` —
never `coverageMap`/`coverage_map`. PascalCase directories (`Map/`, `Social/`
in the owner's own sketch) are a React/JS convention and do not survive
translation for the same reason. **One collision exists and needs a
deliberate resolution:**
`Destination.Map` would naturally lowercase to `map`, which collides with the
already-existing `com.jellemax.detour.map` (the pure map-decision package,
staying flat and top-level under this proposal — see "The `lib/` decision").
The feature package is named `features/mapscreen/` instead — distinct at a
glance from `map/`.

Two people applying this test to the same new file get the same answer
because every step resolves to a lookup (is it a route? does it import
`androidx.compose.*`? how many distinct destinations call it?) rather than a
judgment call — with one honest exception: step 3's "which destinations are genuinely different" trace
needs a look at `nav/Destination.kt`, not a bare grep count, and a threshold
of exactly 2 is a line drawn by convention (matching `01-ui-inventory.md`'s
own SHARED definition), not derived from anything sharper.

### The coupled file-granularity decision

React nests deeply because it is one component per file — that constraint is
what makes a folder-per-concept tree pay for itself, since a folder is the
only grouping unit a `.tsx` file's own convention leaves available. Kotlin
does not share that constraint: `kotlinlang.org/docs/coding-conventions.html`
(quoted in full in `05-kotlin-package-idiom.md` §1) explicitly encourages
"placing multiple declarations... in the same Kotlin source file... as long
as these declarations are closely related," and this codebase already
practices it at scale — **59 files hold 128 non-private top-level
declarations today** (`03-move-cost.md` §5), an average of better than two
per file, and the placement test above leans on that fact directly: it runs
per file, and explicitly declines to fragment a file across
`components/`/`state/`/`utils/` to chase a one-declaration-per-folder ideal.

**This proposal does not adopt one-composable-per-file, and says so
plainly.** `MapChrome.kt` keeps `MapTopChrome` beside its private
`ConvoyPill` and `GlassRailButton`; `RideSheet.kt` keeps `DriveSheet` and
`NavSheet` beside the internal-only `rememberActiveTripCardState` and
`TripStatsRows` that serve them; `TripCardRenderer.kt` keeps its one shared
export beside the ~14 private renderer composables that only it calls.
Every one of these stays one file, moving as a unit, under this proposal
exactly as it does today.

**Be honest about the consequence: nesting pays off far more if a codebase
does adopt one-declaration-per-file, and far less if it does not — and this
document does not pretend otherwise.** A `features/mapscreen/components/`
folder holding 21 multi-declaration files reads very differently from the
same folder in a codebase where each of those files held exactly one
composable — the nesting still sorts 21 files by kind correctly, but it does
not deliver React's implicit promise that opening the folder shows you every
component's own file. `01-ui-inventory.md`'s own 128-declarations-in-59-files
ratio is why this proposal's tiers are named for *kind* (renders / holds
state / pure function) rather than for individual component identity the
way the owner's `cards/`, `buttons/` sketch implies — a scheme that tried to
give every declaration its own directory on top of this ratio would be
exactly the per-variant over-fragmentation `05-kotlin-package-idiom.md` §2
and §6 already diagnose, multiplied by however many of the 128 declarations
share a file today.

---

## A simplified, abstract example

Toy names, small enough to hold in one head. `StatusPill` starts inside one
feature, a second feature starts calling it, and that call is what promotes
it out of the feature tier into the global one.

**Before** — `StatusPill` lives inside Alpha's own component tier, called
only from `AlphaScreen`:

```kotlin
// features/alpha/components/AlphaScreen.kt
package com.example.app.features.alpha.components

@Composable
fun AlphaScreen() {
    StatusPill(label = "Live", tone = Tone.GOOD)
}

@Composable
internal fun StatusPill(label: String, tone: Tone) {
    Surface(shape = CircleShape, color = tone.color()) { Text(label) }
}

internal enum class Tone { GOOD, WARN }
```

Nothing calls it from Beta yet, so no import exists anywhere for it — it is
`internal` to the module either way, but its only *caller* is same-file, and
the placement test's step 3 has not fired.

A second feature, Beta, starts rendering the same status shape:

```kotlin
// features/beta/components/BetaScreen.kt
package com.example.app.features.beta.components

@Composable
fun BetaScreen() {
    // does not compile yet — StatusPill is not visible from another package
}
```

**The promotion, as a diff.** Two distinct destinations now want it — step 3
fires — so `StatusPill` and the `Tone` it depends on move to the global tier:

```diff
--- a/features/alpha/components/AlphaScreen.kt
+++ b/features/alpha/components/AlphaScreen.kt
@@
 package com.example.app.features.alpha.components
 
+import com.example.app.components.StatusPill
+import com.example.app.components.Tone
+
 @Composable
 fun AlphaScreen() {
     StatusPill(label = "Live", tone = Tone.GOOD)
 }
-
-@Composable
-internal fun StatusPill(label: String, tone: Tone) {
-    Surface(shape = CircleShape, color = tone.color()) { Text(label) }
-}
-
-internal enum class Tone { GOOD, WARN }
```

```kotlin
// components/Pills.kt  (new file)
package com.example.app.components

@Composable
internal fun StatusPill(label: String, tone: Tone) {
    Surface(shape = CircleShape, color = tone.color()) { Text(label) }
}

internal enum class Tone { GOOD, WARN }
```

```kotlin
// features/beta/components/BetaScreen.kt
package com.example.app.features.beta.components

import com.example.app.components.StatusPill   // NEW — an import that
import com.example.app.components.Tone         // did not exist before

@Composable
fun BetaScreen() {
    StatusPill(label = "Paused", tone = Tone.WARN)
}
```

Three things worth noticing, because each recurs in the real example below:

- **The symbol `Tone` had to move with `StatusPill`, not stay behind.**
  Leaving it in `features/alpha/` would make the global `components/` package
  import a type back out of a feature — a dependency arrow pointing the wrong
  way, invisible until someone asks why `components/` depends on
  `features/alpha/`.
- **The file that used to own the symbol now needs an import it never
  needed before** — `AlphaScreen.kt` gains two `import` lines for a symbol
  that did not move logically, only across a package line drawn between its
  old home and its new one.
- **`StatusPill`'s own body did not change one character.** Only its
  `package` line and which file it lives in changed.

---

## The real example, worked end to end

### 1. The SpeedInfoPill case — mostly a rename, not new work

The owner's own example was "a pill exporting current speed, average speed,
road speed sign." `01-ui-inventory.md` §B, read at face value, says this is
partly new: `SpeedHud` exists (single caller, `MapScreen`), "no average-speed
HUD composable exists anywhere in ui/... average lives in
`tracking/SectionAverageLog.kt` and is voice-announced, not drawn."

**That finding is stale, and direct verification of the current tree shows
why.** `git log --oneline -- app/src/main/java/com/jellemax/detour/ui/MapHud.kt`
shows commit `461281ae` ("feat(hud): say the section average as a number and
'avg' (#237)", 2026-09-07) as the most recent commit to touch the
average-rendering block before this research — two days *before* the
research's own capture date and commit (2026-09-09, `efa6c9ad`). Reading that
commit's own diff shows it is a *wording* fix, not the feature's origin — it
replaces an existing "Ø 82" / "avg km/h" rendering (already drawn, already
double-printing its unit, per the commit's own description) with a plain
number and "avg" — so the average was being drawn in `ui/MapHud.kt` well
before 09-07, not freshly added by it; 09-07 is only the last point before
capture at which the drawn average is independently confirmed present and
under active maintenance. `01-ui-inventory.md`'s own method (§Header item 1)
restricted its "average" search to declaration lines (`fun |class |object`);
the average-speed rendering is a property read (`state.averageText`,
`MapHud.kt:165`) inside an *existing* composable, not a new declaration, so
it never matched that grep. **This is a correction to `01-ui-inventory.md`
itself, not just an update since it was written** — its claim was already
false at the moment of its own capture, on its own commit; flagged here
because that inventory is the evidence base other readers, including the
sibling proposals, take on trust. Reading the file directly settles it:

`SpeedHud` (`app/src/main/java/com/jellemax/detour/ui/MapHud.kt:121-205`)
already draws all three of the owner's parts, in one card:

- current speed — `state.speedText`, `MapHud.kt:139`
- the running average, when a trajectcontrole section is active —
  `state.averageText?.let { ... }`, `MapHud.kt:165-192`
- the posted-limit sign — `SpeedLimitSign(it, size = ISLAND_WIDTH)`,
  `MapHud.kt:196-202`, calling the composable declared at `Navigation.kt:246`

All three numbers come from one already-unified type,
`SpeedHudState` (`shared/src/commonMain/kotlin/com/jellemax/detour/presentation/TripHudState.kt:9-24`),
built by `speedHudStateFrom` and consumed at exactly one call site,
`MapScreen.kt:1126`. `MapHazardAlerts.kt:107-140` still tracks the average
through `SectionAverageTracker.step` and still logs it through
`SectionAverageLog.log` (confirmed: its own KDoc, `MapHazardAlerts.kt:18-20`,
now says "the two hazard readouts that speak up: the speed-camera chime, and
the average-speed... section" — but its code, read line by line, only calls
`announce(...)` for the camera-warning branch, `MapHazardAlerts.kt:100`; the
average-speed effect updates `retained.sectionState` and logs, and never
calls `announce`). So: the average is tracked in `shared/`, logged in
`tracking/`, and **drawn** in `ui/MapHud.kt` — not voice-only, contra the
research doc's reading of `SectionAverageLog.kt`'s KDoc, which describes that
file's own purpose (a diagnostic log line) rather than the whole feature's
current behaviour.

**What this means for placement:** `SpeedInfoPill` is not new work. It is
`SpeedHud`, already assembled, with a name the owner's sketch happens not to
share. Under this proposal:

```kotlin
// features/mapscreen/components/MapHud.kt — package line changes, body does not
package com.jellemax.detour.features.mapscreen.components

// SpeedHud(state: SpeedHudState, modifier: Modifier = Modifier) — unchanged,
// still calls SpeedLimitSign from the same file it always has (Navigation.kt,
// also moving to features/mapscreen/components/), no new import needed for it
```

Renaming `SpeedHud` to `SpeedInfoPill` is available but is a separate,
optional, cosmetic decision — `.claude/skills/detour-file-split/SKILL.md`
§4's rule ("a rename never rides inside a move") applies here exactly as it
does to a same-package split: rename in its own commit, after the move has
already landed and proven inert, if the team wants the sketch's literal
vocabulary. Nothing about the packaging axis requires it.

### 2. The Settings cluster — a full feature slice, and where its `hooks/` tier is empty

`Destination.Settings` plus its 7-member `SettingsSpoke` sealed interface
(`nav/Destination.kt:106-148`, verified directly: `SettingsAppearanceMap`,
`SettingsTrackingVehicles`, `SettingsNavigation`, `SettingsFog`,
`SettingsDisplaysMedia`, `SettingsServersSync`, `SettingsObd2`) are rendered
by `SettingsHub.kt` (the hub, `fun SettingsScreen` at `SettingsHub.kt:45`) and
`SettingsScreen.kt` (`SettingsSpokeScreen`, the 6-branch dispatch,
`SettingsScreen.kt:173-204`), plus `SettingsServers.kt`, `Obd2PairingScreen.kt`,
`SettingsDiagnostics.kt`, `SecureFields.kt`, `UpdateProgressButton.kt`. Every
external caller of every file in this list is another file in the same
cluster or `nav/AppRoot.kt`'s `entry<Destination.SettingsXxx>` registrations —
one destination-family, one feature package, treating all 7 spokes as one
package rather than nesting a
second level per spoke (which would be the same per-variant mistake
`05-kotlin-package-idiom.md` §5-6 diagnoses against `base/button/confirm/`).

```
features/settings/
  components/
    SettingsHub.kt
    SettingsScreen.kt
    SettingsServers.kt
    SettingsDiagnostics.kt
    Obd2PairingScreen.kt
    SecureFields.kt
    UpdateProgressButton.kt
  state/     <- does not exist. See below.
  utils/     <- does not exist. See below.
```

**This is where constraint 3 has to be confronted directly, not deferred to
"what this axis is bad at."** Every one of these 7 files reads a `data/`
store or holds ephemeral UI state inline inside its own composable body —
`SettingsScreen.kt` alone declares `AppearanceSection`, `TrackingSection`,
`NavigationSection`, `MapIconSection`, `RouteColorSection`, `MapSection`,
`FogSection`, `ExternalDisplaySection`, `NowPlayingSection`, `VehicleSection`,
`LeanCalibrationSection` — eleven private section composables, each reading
its slice of `Settings` directly rather than through a `remember` factory
that could be pulled into a `state/` file. There is no `rememberXxx` function
anywhere in this cluster (confirmed: `grep -n "^internal fun remember\|^fun remember" app/src/main/java/com/jellemax/detour/ui/Settings*.kt app/src/main/java/com/jellemax/detour/ui/Obd2PairingScreen.kt app/src/main/java/com/jellemax/detour/ui/SecureFields.kt app/src/main/java/com/jellemax/detour/ui/UpdateProgressButton.kt`
returns no hits). So `features/settings/state/` is created empty on day one,
and stays empty until someone extracts a real state holder out of one of
these composables — which is legitimate future work, but is a state-extraction
refactor, not a packaging move, and this proposal does not perform it any
more than a file split does (`.claude/skills/detour-file-split/SKILL.md` §9:
"a smaller file is not a refactored screen").

`features/settings/utils/` is populated by exactly one thing worth
extracting: `spokeTitle` (`SettingsScreen.kt:108`, a pure `when` over
`Destination.SettingsSpoke` with no Compose import) — small enough that
leaving it in `SettingsScreen.kt` per §8.5 rather than forcing a one-symbol
file is the more defensible call; this proposal leaves it in place and notes
the option rather than manufacturing a one-file `utils/` folder to satisfy
the taxonomy.

This packaging move does not fix the file/symbol mismatch `01-ui-inventory.md`
Anomaly 4 already found — `SettingsHub.kt` declares `fun SettingsScreen`, and
the file named `SettingsScreen.kt` declares `SettingsSpokeScreen`, not
`SettingsScreen`. Moving both into the same `features/settings/components/`
folder makes the mismatch easier to spot (one folder to scan instead of 59
files) but does not resolve it — a rename is available as a zero-risk,
separate commit.

Blast radius: `nav/AppRoot.kt`'s 8 `entry<Destination.SettingsXxx>`
registrations get their import paths updated
(`com.jellemax.detour.ui.SettingsScreen` →
`com.jellemax.detour.features.settings.components.SettingsScreen`, etc.), and
nothing else — no other feature or `lib/` file imports a Settings symbol
today (confirmed against `01-ui-inventory.md` and `02-repo-guidelines.md`'s
complete cross-reference tables, neither of which names a Settings file as a
dependency of anything outside the cluster).

### 3. The Map cluster — confronting the ownership gate directly

`Destination.Map` (`MapScreen.kt`, routed at `nav/AppRoot.kt`) anchors a
27-file cluster — 26 files besides `MapScreen.kt`, plus `MapScreen.kt`
itself — here split three ways by kind:

- **`features/mapscreen/components/`** (21 files) — `MapScreen.kt`,
  `MapBottom.kt`, `MapCamera.kt` (minus its tuning constants),
  `MapChrome.kt`, `MapCircleMembers.kt`, `MapDialogs.kt` (minus
  `BackgroundLocationDisclosure`), `MapHazardAlerts.kt`, `MapHazardPrefetch.kt`,
  `MapHud.kt`, `MapLibreMap.kt`, `MapNavigation.kt`, `HomeSheet.kt`,
  `RideSheet.kt`, `SpinCards.kt`, `CandidatesCard.kt`, `NavigationDock.kt`,
  `NavAppLaunch.kt`, `Navigation.kt`, `RiderCard.kt`, `SearchIsland.kt`,
  `FogView.kt` (a custom `View`, still "renders" — `boundaries.md` §8.7
  bucket 1 explicitly covers non-Compose drawing surfaces).
- **`features/mapscreen/state/`** (4 files) — `MapScreenState.kt`,
  `MapPermissions.kt`, `DriveClockLocal.kt`, `RetainedMap.kt`. This is the one
  feature whose `state/` tier is populated from day one, because it is the
  one place this codebase already builds real state holders — exactly the
  pattern `state-holders.md` §14.5 wants generalized.
- **`features/mapscreen/utils/`** (2 files) — `MapCameraTuning.kt`,
  `SpinShare.kt`.

**Feature slicing does not exempt this cluster from `boundaries.md` §8.4, and
does not fix it.** `MapBottomSlot` (`MapBottom.kt:56`) still takes **46
parameters**; `SpinSheet` (`SpinCards.kt:107`) still takes **23**;
`state-holders.md` §14.5's `SpinDock` (**19**) is the same story. Every one
of those parameters is a value that should belong to a state holder that does
not exist yet — moving `MapBottom.kt` and `SpinCards.kt` into
`features/mapscreen/components/` changes their package line and nothing
about their signatures. §8.4's order — "find the owner, give the state to
it, the file boundary falls out" — is not something a packaging axis can
shortcut. **What this proposal can do today: place these 27 files exactly
where §8.4 already finds them, with the same wide signatures they have now.
What it cannot do: shrink `MapBottomSlot` to something a features-folder
would make look reasonable.** That is `detour-staged-refactor` work, gated
on `docs/refactor/mapscreen/DECISION.md`'s open items, and a precondition for
splitting *inside* `features/mapscreen/components/` any further than this
proposal already does — not a packaging decision.

`MapCameraTuning.kt`'s 8 `internal` constants and `driveFrameDelta`, consumed
by `car/CarMapRenderer.kt:31-32`, `map/FollowCamera.kt`, `map/MapMotion.kt`,
`map/SpinRun.kt` (the flat, unmoved, top-level pure-decision package — see
"The `lib/` decision"), and 3 files under `app/src/test/.../map/`, each get
one import-path edit — the same 7-file, 8-line blast radius
`02-repo-guidelines.md` §2-3 already measured, unaffected by which package
`MapCameraTuning` itself moves to.

---

## The full target tree

Every one of the 138 `.kt` files currently under
`app/src/main/java/com/jellemax/detour/` is placed below, plus the 2 files
`entry/` draws in from `app/src/debug/java/com/jellemax/detour/debug/`.
Three content extractions (`HubRow` out of `HubScreen.kt`,
`BackgroundLocationDisclosure` out of `MapDialogs.kt`, the trip-formatting
trio out of `HistoryScreen.kt`) plus one new file
(`nav/AppRoot.kt`, extracted from `MainActivity.kt`) bring the `app/src/main`
total to 142; the 2 debug-sourced files bring the grand total to 144.

### `com.jellemax.detour` (root) — 3 files, unchanged

`MainActivity.kt` (trimmed to the `ComponentActivity` shell — `onCreate`,
`setContent { nav.AppRoot(...) }` — losing `AppRoot`, `resetTo`,
`TripDetailEntry`, `RouteEditorEntry`, `MainActivity.kt:210-573`, ~363 lines,
to `nav/AppRoot.kt`), `ColdStartTiming.kt`, `DetourApplication.kt`.

### `com.jellemax.detour.nav` — 3 files

`Destination.kt` (unchanged), `NavActions.kt` (unchanged), `AppRoot.kt` (new
— `AppRoot`, `resetTo`, `TripDetailEntry`, `RouteEditorEntry`, extracted
verbatim from `MainActivity.kt:210-573`).

### `com.jellemax.detour.components` — 9 files, flat

| File | Contents | From |
|---|---|---|
| `AppBar.kt` | `SubScreenTopBar` (12 destinations) | `ui/AppBar.kt`, unchanged |
| `Cards.kt` | `ListCard`, `CardDivider`, `SectionLabel` | `ui/Cards.kt`, unchanged |
| `GlassSurface.kt` | `glassCardColors`, `glassContainerColor`, `Modifier.glassBorder` | `ui/GlassSurface.kt`, unchanged |
| `ConfirmDialog.kt` | `ConfirmDialog` (9 destinations) | `ui/ConfirmDialog.kt`, unchanged |
| `BackgroundLocationDisclosure.kt` | `BackgroundLocationDisclosure` | extracted from `ui/MapDialogs.kt:28-56` |
| `Pills.kt` | `ChoiceRowMetrics`, `choiceRowMetrics`, `ChoiceItem`, `ChoiceRow` | `ui/Pills.kt`, unchanged |
| `Rows.kt` | `HubRow` | extracted from `ui/HubScreen.kt:407-` |
| `TripCardRenderer.kt` | `TripCardShareDialog` (history + tripdetail) plus its private renderer helpers and internal-only `remember*` plumbing, riding along per §8.5 | `ui/TripCardRenderer.kt`, unchanged |
| `TravelModeIcon.kt` | `TravelMode.icon` (history, mapscreen, routeeditor) | `ui/TravelModeIcon.kt`, unchanged |

### `com.jellemax.detour.components.theme` — 2 files

`GraphiteTheme.kt` (`GraphiteDark`, `GraphiteLight`), `Theme.kt`
(`isAppDarkTheme`, `isNightNow`) — both unchanged, grouped one level deeper
than the rest of `components/`, mirroring Now in Android's own
`designsystem/theme/` sibling package (`04-android-compose-precedent.md` §2b).

### `com.jellemax.detour.utils` — 2 files

`Format.kt` (unchanged — history, tripdetail, routeeditor, mapscreen, plus
`car/`), `TripFormat.kt` (extracted from `ui/HistoryScreen.kt`:
`tripStatLine`, `tripBehaviorLine`, `tripFuelEconomyLper100Km`,
`loadTripTrace`, `loadTripPoints`, `matchTripPoints`, `readTraceSegments`,
`TraceSegment` — called from history, tripdetail, and `tracking/TripSession.kt`).

### `com.jellemax.detour.state` — 1 file

`SpinResultHolder.kt` (unchanged — read/written by `features/mapscreen` and
`features/routes`'s `seedRouteNavigation` call).

### `com.jellemax.detour.features.mapscreen` — 27 files

As enumerated in the real example above: 21 in `components/`, 4 in `state/`,
2 in `utils/`.

### `com.jellemax.detour.features.history` — 1 file

`components/HistoryScreen.kt`, trimmed of the `utils/TripFormat.kt`
extraction; keeps `NoTripsYet`, `HistoryLoadFailed`, `TripCard`,
`TraceThumbnail`, `matchThumbnails`, `monthKey`, `monthFormat` — all private,
single-caller inside this file.

### `com.jellemax.detour.features.tripdetail` — 1 file

`components/TripDetailScreen.kt`, unchanged.

### `com.jellemax.detour.features.badges` — 1 file

`components/BadgesScreen.kt`, unchanged (keeps its own private `SectionLabel`
shadow — `01-ui-inventory.md` Anomaly 1 — a content decision this proposal
flags but does not fix).

### `com.jellemax.detour.features.coveragemap` — 1 file

`components/CoverageMapScreen.kt`, unchanged.

### `com.jellemax.detour.features.social` — 1 file

`components/SocialScreen.kt`, unchanged.

### `com.jellemax.detour.features.profile` — 1 file

`components/ProfileScreen.kt`, unchanged.

### `com.jellemax.detour.features.friends` — 2 files

`components/FriendsScreen.kt`, `components/DisabledFeature.kt` (single
caller, `FriendsScreen.kt:702`).

### `com.jellemax.detour.features.circles` — 1 file

`components/CirclesScreen.kt` (both `CirclesScreen` and `CircleDetailScreen`
stay in one file, one feature package — no rule forces the split).

### `com.jellemax.detour.features.savedplaces` — 1 file

`components/SavedPlacesScreen.kt`, unchanged.

### `com.jellemax.detour.features.routes` — 1 file

`components/RoutesScreen.kt`, unchanged.

### `com.jellemax.detour.features.routeeditor` — 1 file

`components/RouteEditorScreen.kt`, unchanged.

### `com.jellemax.detour.features.hub` — 2 files

`components/HubScreen.kt` (trimmed of the `Rows.kt` extraction; keeps
`YouProfileCard`, `YouGuestCard`, `YouStatsRow`, `StatCell`, all private,
same-file per §8.5), `components/UpdateBanner.kt` (single caller,
`HubScreen.kt:134`).

### `com.jellemax.detour.features.settings` — 7 files

As enumerated above, all in `components/`; `state/` and `utils/` created
empty (see the Settings case study).

### `com.jellemax.detour.entry` — 8 files, new tier

`app/src/main/java/com/jellemax/detour/entry/`: `DetourCarAppService.kt`
(from `car/`), `DetourMessagingService.kt` (from `notif/`), `BootReceiver.kt`,
`GeofenceWakeReceiver.kt`, `PlaceGeofenceReceiver.kt` (all three from
`tracking/`), `InstallResultReceiver.kt` (from `update/`) — 6 files.
`app/src/debug/java/com/jellemax/detour/entry/`: `DebugTripEndedReceiver.kt`,
`DebugReplayReceiver.kt` (both from `debug/`) — 2 files, same package as the
six above, physically in the debug source root. 8 manifest `android:name`
edits accompany the 8 file moves (see "`entry/` — no React analog...").

### `com.jellemax.detour.car` — 5 files, unchanged

`CarMapRenderer.kt`, `DetourCarSession.kt`, `NavScreen.kt`,
`SearchScreen.kt`, `SpinScreen.kt` — no move, only the 8 import-path edits
enumerated above. `DetourCarAppService.kt` left for `entry/`.

### Four packages that stay flat, top-level, and unmoved — 33 files

Each flagged by `architecture.md:38-46` itself as transitional, or (for
`convoy/`) simply never named by that diagram at all — see "The `lib/`
decision" for why none of these four joins `lib/`:

| Package | Files | Status |
|---|---|---|
| `com.jellemax.detour.data` | 5 | flagged "Android-side data that has not moved yet" |
| `com.jellemax.detour.map` | 10 | flagged "Android-side policy that has not moved yet" (the pure map-decision package — `NavPolicy`, `FollowCamera`, `CameraAuthority` and siblings — distinct from `features/mapscreen/`) |
| `com.jellemax.detour.tracking` | 17 | its own category in the architecture diagram ("foreground service, sensors, location plumbing"), never called a platform port; holds the `LocationSource`/`DriveClock` port triples untouched (see "Ports are triples, not packages"). Was 20; loses `BootReceiver.kt`, `GeofenceWakeReceiver.kt`, `PlaceGeofenceReceiver.kt` to `entry/` |
| `com.jellemax.detour.convoy` | 1 | absent from the architecture diagram entirely; `ConvoyLiveService.kt` is the convoy feature's own live-location logic, not a thin shell — it stays for the same reason `TripTrackingService.kt` does |

Not separately placed, and not double-counted in the total below:
`app/src/debug/java/com/jellemax/detour/tracking/` (6 files —
`LocationSources.kt`, `DriveClocks.kt`, `ReplayAutoDetect.kt`,
`ReplayFixGate.kt`, `ReplayLocationSource.kt`, `ReplayMode.kt`) and
`app/src/release/java/com/jellemax/detour/tracking/` (3 files —
`LocationSources.kt`, `DriveClocks.kt`, `ReplayFixGate.kt`) are variant-only
counterparts of the port-triple pattern inside `tracking/`, which does not
move under this proposal — see "Ports are triples, not packages."

### `com.jellemax.detour.lib` — 30 files, 9 subpackages, each unchanged internally

Exactly the packages `architecture.md:38-46` calls "platform ports":
`audio` (2), `ble` (1), `notif` (9, was 10 — loses `DetourMessagingService.kt`
to `entry/`), `net` (2), `obd2` (1), `media` (1), `perf` (2), `update` (10,
was 11 — loses `InstallResultReceiver.kt` to `entry/`), `auth` (2).

**Total: 3 (root) + 3 (`nav/`) + 9+2 (`components/`) + 2 (`utils/`) + 1
(`state/`) + 8 (`entry/`) + 27 (`features/mapscreen`) + 21 (the other 13
feature packages) + 5 (`car/`) + 33 (the four flat, unmoved packages) + 30
(`lib/`) = 144**, against 138 in `app/src/main` today. The count is +6 over
the 138 figure used everywhere else in this document: +4 from the three
content extractions plus `nav/AppRoot.kt` (as before), and +2 because
`entry/` is the first tier in this document to reach outside
`app/src/main` at all — `DebugTripEndedReceiver.kt` and
`DebugReplayReceiver.kt` live in `app/src/debug`, never counted in the
138-file `app/src/main` figure this document otherwise uses throughout.

---

## Scope: what this document changes, and what it deliberately does not

Settled by the owner, stated here rather than left to be inferred from what
the rest of the document happens to touch: this proposal's scope is the
`ui/` restructure (`components/`, `features/`, `state/`, `utils/`) plus two
axis-independent extractions — the `routes/` host out of `MainActivity.kt`
into `nav/AppRoot.kt`, and the `entry/` tier. Everything else this document
had occasion to measure while establishing those three stays exactly where
it is, named here so it reads as a decision and not an omission:

- **Not `car/`'s own organization.** Five of its six files are untouched by
  this proposal; the sixth (`DetourCarAppService.kt`) leaves only because it
  qualifies for `entry/` by the same rule every other thin shell does, not
  because `car/` itself is being restructured. `car/`'s remaining five files
  keep their current flat shape, their own template-API concerns, and their
  own future roadmap, all outside this document.
- **Not `tracking/`'s internals.** `TripTrackingService.kt` (1,736 lines,
  over the hard limit) and the `LocationSource`/`DriveClock` port triples
  stay exactly as they are — the only files this proposal removes from
  `tracking/` are the three thin entry-point shells
  (`BootReceiver.kt`, `GeofenceWakeReceiver.kt`, `PlaceGeofenceReceiver.kt`),
  for the same reason `DetourCarAppService.kt` leaves `car/`. Whether
  `TripTrackingService.kt`'s own ~650 lines of classification logic should
  move toward `shared/…/drive/` (`boundaries.md` §8.8 already diagnoses this
  exact file as "ownership is clear... extract the machine") is
  `detour-staged-refactor` territory, not a packaging decision, and this
  document does not propose it.
- **Not `shared/drive/`, or anything inside `:shared`.** Every question this
  document raises about content that arguably belongs in `shared/` —
  `map/`'s pure decisions, `data/`'s Android-side stores, `net/`'s
  interface-implementing client — is flagged and left there, explicitly a
  `detour-shared-core` content question. This document only ever decides
  where a file sits inside `:app`; it never proposes moving a line of logic
  across the module boundary.

Sequencing below reflects this: it stages the `ui/` restructure, the
`routes/` extraction, and the `entry/` tier, and nothing else.

## Sequencing

The repo owner's stated objection is specific: *"a partial refactor cannot
fit this design without taking everything with me."* Because this proposal's
scope is the whole `app/` module rather than one leaf, it is the one most
exposed to that objection, and it needs a direct answer, not a schedule that
quietly assumes a single atomic cutover.

### The two cheapest wins first, and both are independent of everything else in this document

The owner raised two separate wants about entry points, and they resolve
differently.

**First, a suspicion that turned out wrong.** The owner suspected
`MainActivity.kt` was crowded with other Android entry points — alarms,
geofencing, debug hooks — that should have their own homes. **Direct
verification of all four manifests shows that suspicion is wrong, and
entry-point separation from the Activity is already complete.**
`app/src/main/AndroidManifest.xml` declares exactly **one** `<activity>`
(`.MainActivity`), 7 `<service>` entries each already in its own feature
package (`.tracking.TripTrackingService`, `.convoy.ConvoyLiveService`,
`.notif.CircleNotifyService`, `.notif.DetourMessagingService`,
`.update.UpdateDownloadService`, `.car.DetourCarAppService`,
`.media.MediaListenerService`), and 4 `<receiver>` entries, also each in its
own package (`.tracking.BootReceiver`, `.tracking.GeofenceWakeReceiver`,
`.tracking.PlaceGeofenceReceiver`, `.update.InstallResultReceiver`).
`app/src/debug/AndroidManifest.xml` adds two receivers already namespaced
under their own `debug` package. `app/src/automotive/AndroidManifest.xml`'s
only additional activity is androidx's own `CarAppActivity`, not app code.
Nothing here needs pulling out of `MainActivity.kt` — it was never there.

**What is actually wrong with `MainActivity.kt` (573 lines) is that it holds
the navigation host, not a crowd of entry points.** Measured directly: lines
93-207 are the `ComponentActivity` itself — `onCreate`'s cold-start
orchestration (stale-notification dismiss, keep-screen-on, monitoring start,
push refresh, capability probe, MapLibre init, theme decision) and the
OAuth-redirect handling (`onNewIntent`, `onStart`, `takeSignInRedirect`) —
genuinely entry-point work, and it stays. Lines 211-526 are
`private fun AppRoot()`, the nav host; 527-545 is
`NavBackStack<NavKey>.resetTo`; 547-564 and 566-573 are `TripDetailEntry` and
`RouteEditorEntry`. **Roughly 360 of 573 lines — about 63% — is navigation
sitting inside the entry point,** and that is this proposal's `routes/` tier
translated: `nav/`, grown to hold it. This is cheap for a reason that
matters for §8.4: **`AppRoot` takes zero parameters.** `resetTo` takes 1,
`TripDetailEntry` takes 2, `RouteEditorEntry` takes 3 — every one of the
four is far under the seven-parameter gate, so extracting all four is a
pure move with no state-ownership problem to solve first, unlike
`MapBottomSlot`'s 46.

**Second, a want that turned out correct and separately actionable.** The
owner wants entry points gathered into one place across features, on the
grounds that constructing objects, checking a precondition, and delegating
is a different concern from the feature logic being delegated to — the
`entry/` tier detailed above. This is a real, distinct restructuring from
the `routes/` extraction — it touches 8 files across 5 packages
(`car/`, `notif/`, `tracking/`×3, `update/`, `debug/`×2), not one file in
one Activity — but it shares the same two properties that make it safe to
schedule early: every one of the 8 classes is a pure move (constructing a
`Service`/`BroadcastReceiver`/`CarAppService` subclass, not a parameter list,
so §8.4 does not apply to it at all), and it commits the repo to nothing
about `components/` or `features/` either.

**Both extractions are Stage 1, run in either order or together, ahead of
the directory move below, precisely because neither depends on it or on
each other.**

### The rest: an incremental directory move, decoupled from package alignment

**The answer rests on a fact worth stating precisely, because it changes
what "partial" costs.** Kotlin does not require a file's `package`
declaration to match its directory — unlike Java, where the two are
mechanically tied. Verified in this repo's own tooling: `build.gradle.kts:13`
configures only `io.gitlab.arturbosch.detekt` (no ktlint, no spotless — no
other static-analysis plugin is applied anywhere in the build); `config/detekt/detekt.yml`'s
`naming:` block (`:47-54`) overrides only `FunctionNaming`, and neither
`PackageNaming` nor `MatchingDeclarationName` is overridden, so both run at
their defaults, and neither one inspects a file's directory against its
`package` line (`05-kotlin-package-idiom.md` §3, confirmed against the rule
source directly); `.github/workflows/build.yml:146-152` runs `./gradlew :app:detekt`
gated on `config/detekt/baseline-app.xml`, and nothing else in CI touches
package/directory correspondence. **Nothing in this repo's build or CI would
fail on a mismatch.** Separately, every one of the 370 `.kt` files under
`app/src` and `shared/src` today has a package declaration matching its
directory exactly, zero mismatches — so this is a *permitted but unestablished*
state, not a precedent either way, and Android Studio's own "package
directive does not match file location" inspection will flag it as a
warning. Treat any such mismatch as a deliberate, temporary, en-route state,
and say so in the file — never as an endpoint.

That decoupling is what makes an incremental migration real rather than
aspirational:

- **A pure directory move, package line untouched.** `git mv` a
  file into its final nested path; its `package` declaration does not
  change. Gradle's Kotlin source sets scan `app/src/main/java` recursively
  by file extension, not by directory depth matching package structure
  (confirmed: `app/build.gradle.kts`'s only `sourceSets {` block, line 202,
  configures release/debug variant roots, not a package-string filter or a
  depth restriction — `03-move-cost.md` §6). This means the *filesystem tree*
  can look exactly like the target tree above — every file sitting in
  `features/mapscreen/components/MapBottom.kt` on disk — while every
  `package com.jellemax.detour.ui` line in it is still unchanged, still
  resolving same-package to every other unmoved `ui/` file with zero import
  edits anywhere. This is not a trick; it is the direct consequence of
  Kotlin's directory recommendation being a recommendation
  (`kotlinlang.org/docs/coding-conventions.html`, "Directory structure," per
  `05-kotlin-package-idiom.md` §1). It rebases clean against every one of the
  5 of 9 unmerged local branches that touch `ui/` (`02-repo-guidelines.md`,
  citing issue #184), because nothing any of those branches imports has
  changed.
- **Package alignment, paid per feature or per `lib/` package, in
  isolation, afterward.** Once a file sits in its final directory, updating
  its `package` line (and every dependent file's import path) is a bounded,
  single-feature or single-`lib/`-package cost, exactly the kind
  `03-move-cost.md` §5 and the `lib/` table above already measure. A team can
  stop between any two of these and the tree is still buildable, still
  correctly laid out on disk, and still has exactly one open kind of
  inconsistency (some directories hold files whose `package` line has not
  caught up yet) rather than an unfinished mixture of old-flat and
  new-nested placement rules.

**The one irreducible commit in the `components`/`features` half of this
plan.** The two component tiers are genuinely coupled, and this is the part
of the owner's intuition this document does not argue against:
`SubScreenTopBar` (`components/AppBar.kt`, moving from `ui/AppBar.kt:25`)
has 12 callers spanning 12 different destinations, so the global
`components/` tier has to exist — as a real package, with real files in it —
before the first `features/X/components/` package can unambiguously place a
file that calls it. This proposal's version is the 9 (+2 `theme/`) files
enumerated above. **That
commit is small, but it is not optional, and it is not divisible further** —
a feature package created before `components/` exists has no answer to
"where does `SubScreenTopBar` live" other than a forward reference to a
folder that doesn't exist yet. Nothing about `lib/` or the `routes/`
extraction shares this constraint — both are independent of `components/`
and of each other.

Ordered stages, each independently landable and revertable:

| Stage | Moves | Kind | Import cost | Method |
|---|---|---|---|---|
| 0 | Write the axis down (this document, or its adopted successor) | decision | 0 | — |
| 1a | `nav/AppRoot.kt` extraction from `MainActivity.kt:211-573` (`AppRoot`, `resetTo`, `TripDetailEntry`, `RouteEditorEntry`) | content extraction, zero-parameter/low-parameter, no §8.4 gate to clear | `MainActivity.kt` gains 1 import (`nav.AppRoot`); `AppRoot`'s own body already imports every screen it calls by full path (`MainActivity.kt:68-87`, since `MainActivity` and `ui/` were already different packages) — those import *paths* get edited later, as each screen's own package moves, not newly added now | direct read of `MainActivity.kt:93-573` |
| 1b | `entry/` tier: 8 files, `car/`+`notif/`+`tracking/`(×3)+`update/`+`debug/`(×2) → `com.jellemax.detour.entry` (6 in `app/src/main`, 2 in `app/src/debug`), plus 8 manifest `android:name` edits | content relocation, zero §8.4 gate (no parameter list on any of the 8) | 8 manifest edits (2 files: `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`); zero cross-source-set risk — none of the 8 has a `src/debug`/`src/release` counterpart sharing its name (see "Source-set atomicity") | direct read of both manifests |
| 2 | `git mv` the rest of the tree (132 files — 138 minus the 6 `entry/` files already relocated in Stage 1b) into their target directories, **package lines untouched** | pure directory move | 0 | per the decoupling above |
| 3 | `components/` + `components/theme/` (11 files) — package line change + import edits at every one of their combined ~82 caller-file counts (`01-ui-inventory.md` §A: 12+8+11+3+9+7+5+3+6+12 for `AppBar`/`Cards`(×3 symbols)/`GlassSurface`(×3)/`ConfirmDialog`/`Pills`/`TravelModeIcon`/`Theme`, deduplicated by calling file) | package alignment | ~75-85 import lines, method: `01-ui-inventory.md` §A's own per-symbol caller-file counts, deduplicated | reused, not re-derived |
| 4 | 3 content extractions (`Rows.kt`, `BackgroundLocationDisclosure.kt`, `TripFormat.kt` in `utils/`) — each a same-package mechanical split first (`detour-file-split` §1-8, zero-added-lines proof), *then* its own package-alignment commit | content extraction + package alignment | 5 (Rows) + 2 (BackgroundLocationDisclosure) + 4 (TripFormat, including `tracking/TripSession.kt`) = 11 import lines | `detour-file-split` zero-added-lines check, then the repackage check below |
| 5 | The 13 single-destination feature packages (`history`, `tripdetail`, `badges`, `coveragemap`, `social`, `profile`, `friends`, `circles`, `savedplaces`, `routes`, `routeeditor`, `hub`, `settings`) | package alignment | `nav/AppRoot.kt`'s per-screen import path edits only — no cross-feature caller exists for any of these (confirmed against `01`/`03`'s complete caller tables) | direct |
| 6 | `features/mapscreen` (27 files) | package alignment | `nav/AppRoot.kt` (1 path), `car/` (8 lines, 2 files), `map/` (3 files — flat, unmoved, top-level), `app/src/test/.../map/` (3 files) — the same 7-file, 8-line set §2-3 already measured, unaffected by which package `MapCameraTuning` itself moves to | reused from `02-repo-guidelines.md` §2-3 |
| 7 | `lib/` — exactly the 9 named "platform ports" (61 import lines, 40 files; `tracking/`, `map/`, `data/`, `convoy/` excluded — see "The `lib/` decision") | package alignment | 61 import lines | measured directly above |
| 8 | Import cleanup sweep — one commit, repo-wide, removing orphaned imports left by every prior stage, reusing `detour-file-split`'s 4 named traps (`getValue`/`setValue` delegates, two imports sharing a simple name, an ordinary-English-word collision, a KDoc `[link]`) | cleanup | — | `.claude/skills/detour-file-split/SKILL.md` §7 |

Stage 1 delivers the owner's own stated want (an entry point that is just an
entry point) on its own, immediately, independent of every later decision.
Stage 2 costs nothing and can land as soon as Stage 1 is settled. Stages 3
through 7 can land in any order relative to each other (none depends on
another completing first, since Stage 2 already put every file in its
target directory) and can each be reverted independently by
reverting that stage's package-line-and-import commit — the directory move
underneath is inert either way. **The real cost of a genuinely partial
migration is not correctness — every stage above compiles green on its own —
it is that a half-migrated tree has no single answer to "which convention
applies to this file" until Stage 8 closes it out.** That argues for moving
through stages 3-8 promptly once Stages 1 and 2 land, not for treating
Stage 2's zero cost as license to stop there indefinitely.

Total import-line estimate, method stated per row: ~1 (Stage 1a, `AppRoot`
self-import) + 8 manifest edits, not import lines (Stage 1b, `entry/`) + 0
(Stage 2, directory move) + ~75-85 (`components/`) + 11 (content
extractions) + 0 (single-destination features) + 8 (mapscreen's
outside-`ui/` consumers) + 61 (`lib/`, narrowed to the 9 named platform
ports) ≈ **155-165 import lines, plus 8 manifest edits**, smaller than the ~300-370
estimate for a full `ui/` nesting in `03-move-cost.md` §5, for two independent
reasons: this proposal's `components/` tier is narrower than a single
undifferentiated shared package would be, because its kind-based split keeps
`utils/` and `state/` separately counted,
and because several cross-references that a single-tier count treats as
cross-package are intra-mapscreen under this proposal's finer feature
boundary; and this
proposal's `lib/` cost is a deliberately narrow 61 lines (9 packages), not
the 559-line, 13-package figure a literal reading of the owner's sketch
would have produced — the difference is entirely `data/`, `map/`,
`tracking/` and `convoy/` staying put, per "The `lib/` decision." `MapScreen.kt`'s
34 commits in 60 days and the 5 of 9 unmerged branches touching `ui/`
(`02-repo-guidelines.md`) are the reason Stage 2's zero-cost property
matters most for exactly this file: it is nested by Stage 2 with a
completely unchanged package line, so none of those branches sees a single
conflicting line until whoever owns Stage 6 chooses to run it.

---

## What this axis is bad at

**Most feature `state/` folders are created empty, and stay empty.** Of the
14 feature packages, exactly one (`mapscreen`) has a populated `state/` tier
on day one. The other 13 have no `rememberXxx` factory, no state-holder
class, no `CompositionLocal` to place there — Settings, History, TripDetail,
Friends, Circles and the rest all hold their state as `var … by remember`
inline inside the route composable itself, exactly the gap
`state-holders.md` §14.5 and issue #184 (quoted in `02-repo-guidelines.md`)
both name: "Screens *are* the state holders, calling repositories inline." A
`features/settings/state/` folder that exists, is empty, and stays empty
until someone does real extraction work is a smaller version of the exact
problem this document refused to accept for `features/X/api/` — the
difference is only one of degree, not of kind, and it should be named as
such rather than quietly tolerated because `state/` has better Kotlin
precedent than `api/` does. **This packaging move does not create a single
new state holder.** It gives the thirteen a folder to receive one, the day
someone extracts it, and no sooner.

**A reader loses screen-locality for the shared tiers**, here paid twice, once for
`components/` and once for `utils/`. Reading
`HubScreen.kt` top to bottom today shows `HubRow` right there; after this
move, understanding what `HubScreen.kt` renders costs a trip to
`components/Rows.kt`, and understanding `HistoryScreen.kt` costs a second
trip to `utils/TripFormat.kt`. Two shared tiers instead of one is two
places a reader has to know to check, not one.

**A single-file feature gets three empty folders it will never fill.**
`features/badges/`, `features/coveragemap/`, `features/social/`,
`features/profile/`, `features/savedplaces/`, `features/routes/`,
`features/routeeditor/` are each exactly one screen file today, with no
state holder and no feature-scoped pure utility. Under this proposal each of
these gets a `components/` folder holding its one file, and `state/`/`utils/`
folders that are never created because nothing qualifies for them — which is
the correct outcome (an empty folder is not created just to exist), but it
means seven of fourteen features are, in practice, one file inside one
subfolder inside one named package: three nesting levels
(`com.jellemax.detour.features.<name>.components`) to reach a single
1xx-to-6xx-line file that used to be one hop from `com.jellemax.detour.ui`.
This is the single-file-package problem, one level deeper here, because this axis adds a `components/`
segment even a one-file feature must have.

**`internal` buys none of the isolation "two-tier component library"
implies**, restated because it is easy to forget once two tiers exist to
compare instead of one: `internal` is module-scoped (`05-kotlin-package-idiom.md`
§5), so a component sitting in `features/mapscreen/components/` is exactly as
visible to `car/`, `lib/`, and every other feature as one sitting in the
global `components/` — nesting changes nothing about who *can* reach it, only
about where a reader expects to find it and whether an import line is
required to do so.

**`Navigation.kt`'s holding-pen shape survives unexamined**: `NavigationBanner`,
`RouteProgressTrack`, `SpeedLimitSign` (`01-ui-inventory.md` Anomaly 5) each
have exactly one caller, and each caller is a different file, but all three
callers sit inside `features/mapscreen/`, so this axis's step 3 never fires
on any of them and the file moves whole, three unrelated single-caller
declarations still sharing one file for no reason but a shared topic name.

**This axis says nothing about `MapScreen.kt`'s size or state ownership**, as
the Map case study above already states directly rather than deferring here:
27 files land in `features/mapscreen/`, and the 1,353-line file at the center
of that package (`01-ui-inventory.md` §30) is exactly as long after the move
as before it. `MapBottomSlot`'s 46 parameters and `SpinSheet`'s 23 are
unchanged by a packaging decision and were never going to be.

**No precedent exists in this repo for nesting at all, in either direction.**
`03-move-cost.md` §7 and `05-kotlin-package-idiom.md` §7 both confirm: every
package in this codebase, in every source set, sits at exactly one level
under `com.jellemax.detour`, and `shared/…/data`, at 64 files, is larger than
`ui/` and has stayed flat. That absence is not a rule against nesting — no
written guideline requires flatness, and issue #183 was closed specifically
because a skill wrongly presented flatness as a settled decision — but it
also means this proposal has no in-repo success story to point to, only the
external precedent in `04-android-compose-precedent.md` (Now in Android's
single `scrollbar/` exception, DuckDuckGo's one level of component-type
folders) for the idea that *some* nesting is survivable, and zero precedent
anywhere, in this repo or the five external ones surveyed, for nesting three
segments deep (`features.<name>.components`) as a routine, repeated shape
across fourteen packages the way this proposal does.

---

## Verification

Reused directly from `.claude/skills/detour-file-split/SKILL.md`, in two
forms, plus a third for the directory-only stage this proposal's sequencing
introduces:

1. **Stage 2 (pure directory move, package line untouched):** the diff for
   each moved file must show a path change and **zero** content changes —
   `git diff <base>..HEAD -- <old-path> <new-path>` (or the equivalent after
   `git mv`) should show a rename with no hunk. No other file's diff should
   exist at all for this stage, because no `package` line and no import
   changed anywhere in the tree. `./gradlew :app:compileDebugKotlin` green is
   a sanity check, not a meaningful one — this stage cannot fail it.
2. **Stages 3, 5, 6, 7 (package alignment only):** the moved file's diff must
   show exactly one changed line pair (`package …` old vs. new) and zero
   other lines; every dependent file's diff must be confined to its import
   block, matching `^[+-]import `. `./gradlew :app:compileDebugKotlin` green after every commit,
   not just the last.
3. **Stage 1a (`nav/AppRoot.kt` extraction) and Stage 4 (the three content
   extractions):** run `detour-file-split`'s procedure exactly as documented
   — visibility by grep (`symbol-visibility.sh`), the zero-added-lines check
   (`check-no-added-lines.sh`) against the source file, imports left alone
   until their own final commit — as a same-package split first, landed and
   verified; only then, for Stage 4's three extracted files, does the new
   file's package line change, as a second, separate commit, following
   form 2 above. Stage 1a has no second commit — `nav/AppRoot.kt` is already
   in `nav/`, the tier it belongs to, the moment it is extracted.
4. **Stage 1b (`entry/`):** confirm, per file, that the class's body is
   still exactly what it was before the move — construction, a precondition
   check, and a delegating call, nothing more (the same membership test that
   put it there). Confirm the 8 manifest `android:name` edits by diffing
   both manifests: each changed line should be exactly one `android:name`
   value, nothing else on the line or around it. Then **compile every
   variant that references a moved class, not only debug** —
   `./gradlew :app:compileDebugKotlin :app:compileGithubReleaseKotlin
   :app:compileAutomotiveKotlin` — because `app/build.gradle.kts:190-201`'s
   own comment records `:app:compileGithubReleaseKotlin`, not
   `:app:assembleRelease`, as the task that first caught a variant-only
   omission in this exact codebase; a debug-only compile pass would not have
   caught it then and would not catch its equivalent here. None of the 8
   moved classes has a `src/debug`/`src/release` counterpart (see "Source-set
   atomicity"), so this is confirming an absence of risk, not chasing a
   known one — but it is the one stage in this document that touches files
   reachable from more than one build type's manifest, so it is the one
   stage that earns the check.
5. **No visibility change is needed anywhere in this proposal.** Every
   package created stays inside `:app`, so `internal` still means
   module-wide exactly as it does today. If a moved symbol's `internal`
   modifier needs to change during any stage, something was misclassified —
   confirm by *not* touching any `internal`/`private` modifier during a move.
6. **Test-source-set consumers** (`TripStatLineTest.kt`,
   `TripTraceMatchingTest.kt`, `MapMotionTest.kt`, `FollowCameraTest.kt`,
   `CameraAuthorityTest.kt`) get one new import line each and must show zero
   assertion changes; `./gradlew :app:testDebugUnitTest` passing with the
   same test names and the same pass count, before and after, is the check.
7. **Nothing in this proposal reads a GPS fix or moves a state owner**, so no
   stage earns a replay A/B (`docs/refactor/mapscreen/specs/00-chain-design.md`'s
   verification table) beyond what its underlying content already required —
   a stage that moves `MapCamera.kt` moves the file, not the fix-reading
   logic inside it.
8. **What this proposal does not verify, and says so:** whether the
   discoverability this axis buys is worth the depth it costs is not
   measurable before the fact, any more than it was for A or B; the two
   conditions `05-kotlin-package-idiom.md` §6 gives for finer packaging
   paying off (team/codebase scale, genuine near-term module extraction) are
   judgment calls about the future this repo's history cannot settle today.

Report, per stage: the exact `git diff --stat` (should list only the files
that stage's own table row names), the per-file import-line count against
the Sequencing table, and — for Stage 2 specifically — confirmation that
**no** file outside the moved one appears in the diff at all. "The tree
looks like the target" is not a result; the diff showing only the moves that
stage claims is.
