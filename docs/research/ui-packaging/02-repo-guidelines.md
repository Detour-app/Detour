# Repo guidelines research — package layout / file placement for `ui/`

**Date:** 2026-09-09
**Commit:** `efa6c9ad`

## What was read, in full

- `docs/guidelines/architecture.md` (all of it, including §1 Module layout, §2 Source sets, §2.1
  the commonMain constraint list, §3 Layer anatomy, §9 Template for new work)
- `docs/guidelines/boundaries.md` (all of it — §8.1 through §8.9)
- `docs/guidelines/conventions.md` (all of it — §7 and §7.1)
- `docs/guidelines/decisions.md` (all of it — §12.1, §12.2, §12.3)
- `docs/guidelines/checklist.md` (all of it — §11)
- `docs/guidelines/state.md` (all of it — §4 and §13)
- `docs/guidelines/state-holders.md` (all of it — §14, §14.1 through §14.7)
- `docs/guidelines/multiplatform.md` (all of it — §5, §5.1, §5.2, §5.3, §6)
- `docs/guidelines/README.md` (all of it — the section index)
- `CONTRIBUTING.md` (all of it)
- `docs/refactor/mapscreen/DECISION.md` (all of it)
- `docs/refactor/mapscreen/15-divergence-register.md` (read in full across two pages, entries 1–8
  plus the file's front matter; the file totals 2554 lines, of which the first 918 were paginated
  to me and the remainder — further register entries — was not required for this question since
  entries 1–8 and the file's framing already established everything relevant to package layout.
  Flagging this rather than silently treating it as fully read.)
- `docs/refactor/mapscreen/specs/00-chain-design.md` (all of it)
- `docs/refactor/mapscreen/specs/convergence-2-section-readouts.md` (all of it)
- `.claude/skills/detour-file-split/SKILL.md` (all of it) — not on the original reading list, but
  it turned out to hold the single most load-bearing rule found, and `boundaries.md` itself
  points at it as the place mechanics of a move live
- Repo-wide greps (patterns and paths recorded verbatim in the Absences section below)
- `find app/src/main/java/com/jellemax/detour -mindepth 1 -type d` and the equivalent under
  `shared/src/commonMain/kotlin/com/jellemax/detour`, to check nesting depth directly rather than
  infer it
- `gh issue view 184` and `gh issue view 183` against `Detour-app/Detour`, to verify state and
  labels directly rather than take a relayed description on trust

---

## Every rule bearing on package layout / file placement / where a shared component lives

### Layer anatomy, §3 — `docs/guidelines/architecture.md:73-107`

```
## 3. Layer anatomy — what each package is for

Detour's layers are packages inside `:shared`, not folders inside a feature.

shared/src/commonMain/kotlin/com/jellemax/detour/
├── data/           models, stores, HTTP clients, persistence, pure rules
├── drive/          stateful machines fed one GPS fix at a time
└── presentation/   *State.kt (display shapes + pure mappers), *Presenter.kt

**`data/`** — 64 top-level `object` singletons and their data classes. A store
owns persisted or fetched state and publishes it. A "rule" file
(`CircleNotifyPolicy`, `HighwayClass`, `SpinPicker`) is pure functions with no
state at all.

**`drive/`** — the machines that consume a fix stream: `SpeedLimitTracker`,
`SectionAverageTracker`, `StopDetector`, `CameraWarner`, `HardEventDetector`,
`RoadTypeTracker`, `TripFixMath`. **This is where a stateful driving machine
belongs — not inside a `@Composable`, and not inside a `Service`.** Every one
of them takes its inputs as arguments and returns a new state; none reads a
clock, a sensor or a network of its own.

**`presentation/`** — two kinds of file:

- `XState.kt` — the display shape (`data class`, all defaults) **plus** the
  pure mapper `xStateFrom(...)`. Fourteen of these exist; copy the nearest one.
- `XPresenter.kt` — a thin class that kicks off loads. It publishes rows of its
  own **only when there is no mutable store underneath it**. When a store
  already publishes state, the screen collects the store and calls the mapper
  on the render path — see `FriendsPresenter`'s KDoc for the full argument.

The mapper is the load-bearing part. It is `fun xStateFrom(raw…): XState`, no
I/O, no clock, callable from `commonTest` with literals. If your new display
logic cannot be written that way, it is not display logic.
```

This is the architecture's explicit, written statement of **layer-by-layer over feature-by-feature**
— but by its own words ("packages inside `:shared`, not folders inside a feature") it is scoped to
`:shared`. It never says the same for `app/…/ui/`.

### Module layout, §1 — `architecture.md:38-46`

```
app/src/main/java/com/jellemax/detour/
├── ui/         Compose phone screens                      (Android only, by nature)
├── car/        Android Auto screens                        (Android only, by nature)
├── tracking/   foreground service, sensors, location plumbing
├── net/ ble/ obd2/ audio/ media/ notif/ auth/ update/ perf/  platform ports
├── map/ nav/   Android-side policy that has not moved yet   ← audit these
└── data/       Android-side data that has not moved yet     ← audit these
```

This is the only place `app/`'s top-level layout is diagrammed anywhere in the guidelines, and
`ui/` is one undivided leaf in it. The diagram does not prescribe, or even discuss, anything below
`ui/`.

### Naming and packages, §7 — `docs/guidelines/conventions.md:5-19`

```
## 7. Naming and file conventions

| Rule | Detour form |
| --- | --- |
| File name | PascalCase, named for its main type: `SpeedLimitTracker.kt` |
| One top-level type per file | kept, except for a state file's row types next to its state class |
| Package is the namespace | no barrels, no re-export files |
| Public surface | `internal` controls it; module-scoped, and `:shared` is a real module boundary |
| Mappers | extension or `xStateFrom(...)` free function, never `mapXToY(x)` |
| DTOs | plain names — `SendMessageRequest`, not `…Input` |
| State | `XState` / `XUiState` suffix; all fields defaulted |
| Constants | `SCREAMING_SNAKE_CASE`, **named, never an inline literal** if a second surface has the same number |
| Implementations | `Impl` suffix only for the single obvious implementation |
| Packages | `com.jellemax.detour.{data,drive,presentation}` in `shared`, `com.jellemax.detour.{ui,car,tracking,…}` in `app` |
```

The final row is the only enumeration of `app`'s packages in the naming conventions, and it lists
`ui` as one atomic name among siblings (`car`, `tracking`, …) with no sub-packages named or implied.
"Package is the namespace — no barrels, no re-export files" also bears directly on any `base/`
package: it rules out a package whose job is only to re-export or funnel other packages' symbols.

### §7.1 Comments — `conventions.md:23-31`

```
### 7.1 Comments

Why-not-what (`CONTRIBUTING.md` § "Code style"). The house style is a KDoc that
explains a decision — why this shape, why not the obvious one, which bug it
prevents. A comment that says *"same fix as X"* or *"identical to the Android
service"* is a **promise, not an enforcement mechanism**; when you write one,
you are recording a known divergence risk, and it belongs in
`docs/refactor/mapscreen/15-divergence-register.md` too.
```

Not a placement rule directly, but bears on any move: a comment claiming "same as the phone's
button" when extracting a `base/button/confirm` would need registering, not just writing.

### Boundaries §8.1 What a concern is — `docs/guidelines/boundaries.md:10-30`

```
### 8.1 What a concern is

A concern is one thing that has:

1. **one lifetime** — process, app session, screen, or frame;
2. **one reason to change** — a single product decision touches all of it;
3. **one owner** — exactly one thing writes its state.

Two pieces of code that differ on **any** of the three are two concerns, however
adjacent they look. Two that agree on all three are one concern, however long
they are.

Lifetime is the one people miss, and it is the one this codebase has been
bitten by. The four that exist here:

| Lifetime | Owner | Example |
| --- | --- | --- |
| Process | `object` singleton in `data/` | `Settings`, `FriendsStore` |
| App session | `RetainedMap`, held by `AppRoot` | the `MapView`, camera targets, hazard tracker state |
| Screen | a composable's `remember` | sheet open/closed, text field contents |
| Frame | inside a `withFrameNanos` loop | eased camera position |
```

### §8.2 The four tests, in order — `boundaries.md:32-50`

```
### 8.2 The four tests, in order

Stop at the first that answers.

**1. Different lifetime?** → different owner, different file. Non-negotiable.
Screen-scoped state that must survive navigation is the bug `RetainedMap` was
invented to fix; do not create a second one by hand.

**2. Different reason to change?** → different file. The test is a sentence: *"if
the product changes X, do both halves change?"* Route colour and fog radius both
change when someone redesigns the map's look — same concern. Route colour and
the auto-stop threshold do not — two concerns.

**3. Does a second surface need it?** → `shared/`, per §2 and §3. Phone ↔ car is
a plain file under `app/`, not a trip through `:shared`.

**4. Would extracting it need more than seven parameters?** → **stop.** See
§8.4. This is the gate, and it is the one that is skipped.
```

### §8.3 Split now — hard signals — `boundaries.md:51-66`

```
### 8.3 Split now — hard signals

Any of these means the file has more than one concern in it:

- **Two state machines in one file.** Two sets of accumulating `var`s stepped by
  two different triggers.
- **A fetch inside a renderer.** HTTP, disk or a DAO call inside a `@Composable`.
- **Two lifetimes in one scope.** A `remember` next to something that must
  outlive the screen.
- **A `when` table that a second surface also has.** §7's constant rule.
- **A file over 1,000 lines**, or a function over 100. These are the hard limits;
  500 and 50 are the targets.
- **A file whose name needs "and" to describe it.**

The size limits are the weakest signal on that list. A 900-line file with one
concern is healthier than a 300-line file with three.
```

### §8.4 The ownership gate — FULL QUOTE — `boundaries.md:68-96`

```
### 8.4 The ownership gate — do not split past it

**If pulling a piece out requires threading more than seven parameters into it,
you have not found a concern boundary. You have found a state-ownership
problem, and splitting anyway makes it worse.**

Hoisting does not remove coupling. It writes the coupling down as a function
signature, where it is now permanent, has to be maintained by hand, and shows up
in every call site.

The worked failure is in this repo. `ui/MapBottom.kt`'s `MapBottomSlot` takes
**46 parameters**. The split that produced it was real — the composables did move
into their own files — but the state stayed in `MapScreen`, so every value and
every callback had to be passed. Sort those 46 and 31 of them belong to one of
four state machines that have no owner.

So the order is fixed:

```
find the owner  →  give the state to it  →  the file boundary falls out
```

Not the reverse. When the owner exists, the extracted component takes the owner
and two or three display values, and the 46-parameter signature never gets
written.

If you cannot give it an owner today, **leave the file long** and say why in a
comment. A long file with a known reason is cheaper than a wide signature with
none.
```

### §8.5 What may share a file — `boundaries.md:98-111`

```
### 8.5 What may share a file

One concern, one file — but a concern is bigger than a type. These belong
together:

- **A state class, its row types, and its pure mapper.** The house pattern:
  `presentation/FriendsState.kt` holds `LeaderboardRow`, `FriendRequestRow`,
  `FriendsBoardState` and `friendsBoardStateFrom`. They change together, always.
- **A public composable and the private ones only it uses.** `MapChrome.kt`'s
  `ConvoyPill` and `GlassRailButton` have no other caller.
- **A machine's `State` class, its `onFix`, and its tuning constants.** The
  `drive/` pattern — `SpeedLimitTracker`, `StopDetector`, `CameraWarner`.
- **An enum and its classifier.** `HighwayClass` and `HighwayClass.of()`.
- **A test class and its private fixture builder.** §10.
```

This is directly relevant to a proposed `base/` package: it licenses a public component sharing a
file with the private helpers only it uses, which argues *against* over-splitting a single
component (e.g. a confirm button) into many tiny files, even while it says nothing against the
package boundary itself.

### §8.6 What must never share a file — `boundaries.md:113-122`

```
### 8.6 What must never share a file

- **Two state machines.** Even small ones. Each gets its own `State` and its own
  test.
- **State that changes on different clocks.** Per-GPS-fix and per-tap are two
  concerns; putting them together means every fix invalidates a tap's state.
- **A platform binding and a pure rule.** The rule cannot be tested from
  `commonTest` once it shares a file with a `Context`.
- **Something a second surface needs, next to something it does not.** The
  half that could be shared is now stuck behind the half that cannot.
```

### §8.7 Where each piece goes once split — `boundaries.md:124-141`

```
### 8.7 Where each piece goes once split

Sort every line into one bucket. That *is* the split:

| Bucket | Contains | Home |
| --- | --- | --- |
| 1. Renders | `@Composable`, `Modifier`, layout, `Canvas` | `app/…/ui/`, `app/…/car/`, `iosApp/` |
| 2. Holds or derives state, orchestrates | accumulating state, effect bodies, `launch` | `shared/…/drive/` (per-fix machines) or `shared/…/presentation/` |
| 3. Fetches or persists | HTTP, okio, DAO | `shared/…/data/` |
| 4. Pure model, mapper, rule | data classes, `…StateFrom`, validation, classification | `shared/…/data/` or `shared/…/presentation/` |

**Only bucket 1 may stay out of `commonMain`.** A line doing two at once — a
fetch inside a composable, cache surgery inside a component — is exactly the
coupling the split exists to break.

Platform bindings that cannot move (`Context`, `MapLibreMap`, `MotionEvent`,
`withFrameNanos`, `SensorManager`) stay in bucket 1 by necessity, not by
category. Name the blocker in a comment, per §2.
```

### §8.8 Three worked examples from this repo — FULL QUOTE, including the MapScreen.kt diagnosis — `boundaries.md:143-176`

```
### 8.8 Three worked examples from this repo

They look like the same problem — a file over the limit — and they need three
different fixes.

**`ui/SettingsScreen.kt` — 1,417 lines. Mechanical.**
Twenty independent `@Composable` sections, all bucket 1, no shared mutable
state, one lifetime. Its one piece of real logic already left
(`Dormancy.dormancyBlocker()`, with its own test). Every test in §8.2 says split;
the gate in §8.4 passes because each section takes two or three parameters.
→ Same-package sibling files, one per section. Tier 0, no design decision.

**`tracking/TripTrackingService.kt` — 1,712 lines. Ownership is clear.**
Two concerns in one file: Android service plumbing (bucket 1, must stay) and
~650 lines of numbers-in/numbers-out classification (bucket 2, belongs in
`drive/`). The owner for the second half already exists as a pattern — six
sibling machines in `shared/…/drive/` do exactly this.
→ Extract the machine. Medium difficulty, Tier 2 verification.

**`ui/MapScreen.kt` — 1,894 lines, one 1,752-line function. Ownership is missing.**
Fourteen concerns across three lifetimes. Splitting it by §8.7's buckets alone
produces `MapBottomSlot`'s 46 parameters again, four more times, because §8.4's
gate fails everywhere: `spin()` touches ten mutable variables, `startNavigation()`
six, and `selectMode()` writes six on one tap.
→ **Do not split first.** Give the concerns an owner — `RetainedMap` is already
that owner for the camera and the hazard trackers and should grow — and the file
boundaries then fall out for free.

**And the files that should stay long:** `car/CarMapRenderer.kt` (889) and
`ui/MapLibreMap.kt` (846) are over the target and are correct as they are. Each
is one concern — drawing against a platform surface — with one lifetime and one
reason to change. Splitting them would interleave draw order across files and
buy nothing.
```

### §8.9 Checklist — `boundaries.md:177-191`

```
### 8.9 Checklist

Before creating the first new file:

- [ ] I can name the concern in one noun phrase, with no "and".
- [ ] Everything moving has the same lifetime.
- [ ] Everything moving changes for the same reason.
- [ ] The extracted piece needs ≤7 parameters (§8.4). If not, I am fixing the
      owner first.
- [ ] Exactly one thing writes the state that moved.
- [ ] Nothing in bucket 2, 3 or 4 is staying in `app/` without a named blocker.
- [ ] If two copies existed, I diffed them and extracted from the better one
      (§6 of `detour-shared-core`), not the nearer one.
- [ ] The new file's name is the type it contains.
- [ ] I read `detour-file-split` before moving the first line.
```

### The UI layer, §5 — `docs/guidelines/multiplatform.md:5-21`

```
## 5. The UI layer

Detour is "native UI per platform" for every screen — three UIs, one core.
There is no shared-Compose option to choose, so the per-feature strategy
question does not arise. What does arise is **how much logic each UI is
allowed to hold**, and the answer is: only what it draws.

app/…/ui/XScreen.kt   collects store StateFlows, calls xStateFrom(...), draws
app/…/car/XScreen.kt  same core, Car App Library templates
iosApp/XScreen.swift  same core, observes a Watcher, draws SwiftUI

A screen may: collect flows, hold ephemeral UI state, call store suspend
actions from `rememberCoroutineScope()`, and render. A screen may not: contain
a threshold constant that another surface also needs, own a state machine, or
be the only place a rule exists.
```

### State-holders §14.1 — the rule — `docs/guidelines/state-holders.md:15-33`

```
### 14.1 The rule

> **Group state before you pass it.** A composable takes the data it needs, and
> related values travel together in one holder. If a signature is growing past
> about seven parameters, the caller is missing a state holder — add the
> holder, do not make the passing implicit.

Parameter drilling in Compose is the same problem React has with props, and
Compose answers it differently on purpose. React's usual escape hatch is
Context; Compose's own guidance says **do not** use `CompositionLocal` for
this. From Android's documentation:

> Avoid using it for passing specific objects like ViewModels, as this violates
> the pattern of state flowing down and events flowing up, which reduces
> reusability and testability.

So the fix is never "stop passing things". It is "have something worth
passing".
```

### State-holders §14.5 — why this repo needs the rule — `state-holders.md:70-102`

```
### 14.5 Why this repo needs the rule written down

`app/…/ui/SpinCards.kt` defines `SpinSheet` with **23 parameters**.
`app/…/ui/SpinDock.kt` defines `SpinDock` with **19**. Both are called from
exactly one place — `ui/MapScreen.kt` — and they are the same feature in two
states, so most of those parameters are the same values passed twice:

mode, radiusKm, onRadiusChange, minRadiusKm, onMinRadiusChange,
poiKind, onPoiKindChange, directionDeg, onDirectionChange,
spinning, error, route, destination, destinationName, origin, …

The hoisting is textbook-correct, which is what makes it instructive. Their
caller holds ~30 loose `var … by remember` in one composable scope, so there is
no object to pass, and a parameter list is the faithful mirror of that. The
signature is not the defect. It is the defect printed in the type system.

Those first ten belong to one concept — a spin. Given a holder for it, both
signatures lose ten parameters and gain one, and the two composables stop
having to agree by hand about what a spin consists of.

The same gap has been worked around three other ways already, which is how you
know it is structural rather than local:

| Workaround | What it is really doing |
| --- | --- |
| `ui/RetainedMap.kt` | keeping state alive across navigation — an app-scoped owner, hand-built |
| `ui/SpinResultHolder.kt` | keeping state alive across activity recreation — the same, globally |
| `Auth.resetAccountScopedStores()` | clearing state that has no lifecycle to clear it |
| `SpinSheet`/`SpinDock`'s parameter lists | passing state that was never grouped |

Four workarounds, one missing layer.
```

### detour-file-split SKILL.md §1 — the same-package rule and its self-disclaimer — FULL QUOTE — `.claude/skills/detour-file-split/SKILL.md:58-83`

This is the most directly load-bearing text found on the package-layout question, and it is a
self-disclaimer, not a settled rule:

```
## 1. Same package, always — for a mechanical split

A mechanical split (this skill's kind of move) keeps every new file on the **same `package`
declaration as the file it came from**. For the `ui` package that is
`package com.jellemax.detour.ui`. No new subpackage, no `ui.map`, no `ui.settings`.

This is what makes the move free. Kotlin resolves same-package top-level declarations without
an import, so:

- no import is added to the new file for the symbols it left behind,
- no import is added to `MapScreen.kt` for the symbols that left,
- and **no external call site changes at all**. `RoutesScreen.kt`, `HistoryScreen.kt`,
  `RouteEditorScreen.kt` and everything under `car/` ended stage 1 with literally zero-line
  diffs, which is the strongest single piece of evidence that nothing moved semantically.

This rule is scoped to *this operation*: a mechanical split must not also repackage, because
the zero-added-lines proof above and the zero-diff external call sites depend on staying
same-package. It comes from stage 1 of the MapScreen refactor
(`git show b7f4c6f:docs/refactor/mapscreen/specs/stage-1-mechanical-split.md:68`), which
adopted it so that split's diff stayed provably inert — not from any repo-wide ruling on
package layout. `docs/refactor/mapscreen/DECISION.md` decides no such thing, and stage 2 of
that same chain created `app/src/main/java/com/jellemax/detour/map/`, a new subpackage in the
app module — proof the flat layout was never settled as a general rule.

Whether `ui/` should stay flat long-term is a separate, open question — see #184. Doing a
mechanical split under this skill does not require an answer to it.

If you are moving into `shared/`, this rule does not apply — that is a rewrite, not a move,
and belongs to `detour-shared-core`.
```

**Correction on `map/`, verified directly rather than assumed:** the SKILL.md text above calls
`app/…/map/` "a new subpackage in the app module," and I initially relayed it that way. That
wording is imprecise. `find app/src/main/java/com/jellemax/detour -mindepth 1 -type d` (run fresh
for this file) lists every directory directly under `com/jellemax/detour/`:

```
app/src/main/java/com/jellemax/detour/nav
app/src/main/java/com/jellemax/detour/convoy
app/src/main/java/com/jellemax/detour/perf
app/src/main/java/com/jellemax/detour/ble
app/src/main/java/com/jellemax/detour/audio
app/src/main/java/com/jellemax/detour/update
app/src/main/java/com/jellemax/detour/car
app/src/main/java/com/jellemax/detour/tracking
app/src/main/java/com/jellemax/detour/data
app/src/main/java/com/jellemax/detour/net
app/src/main/java/com/jellemax/detour/notif
app/src/main/java/com/jellemax/detour/map
app/src/main/java/com/jellemax/detour/auth
app/src/main/java/com/jellemax/detour/obd2
app/src/main/java/com/jellemax/detour/ui
app/src/main/java/com/jellemax/detour/media
```

`map/` sits at exactly the same depth as `ui/`, `car/`, `data/`, `net/`, etc. — a **flat sibling**
of `ui/` under `com.jellemax.detour`, not a subpackage nested underneath `ui/` or anything else.
The same check against `shared/src/commonMain/kotlin/com/jellemax/detour` shows `drive/`,
`presentation/`, `data/` at the same single depth. **No package in this repo is nested at any
depth beyond one level under `com.jellemax.detour`.** So stage 2 of the MapScreen refactor is
precedent for *adding a new top-level package* alongside `ui/`, not precedent for *nesting*
anything under `ui/` or under any other existing package. The proposal under review (a
`Screen > Component/Functionality/Utility` layout nested inside `ui/`, plus a nested `base/`
package with its own subpackages like `base/button/confirm`) would be the first nesting of any
kind anywhere in this codebase, on either module.

---

## The state of `docs/refactor/mapscreen/`

### Stages landed — `docs/refactor/mapscreen/DECISION.md:1-59`

```
`MapScreen.kt` was 3204 lines: one 1419-line composable holding 59 `remember` declarations,
35 effects, six independent collectors on one `StateFlow`, and eight closures over mutable
state that no test could reach. None of it was tested.

It is now **1263 lines**, and the reduction is the least interesting part.

| | Before | After |
|---|---|---|
| `MapScreen.kt` | 3204 lines | 1263 |
| `ui/` files | 1 monolith | 55 |
| Decisions with tests | 0 | 7 units, 166 tests |
| Road-hazard features reachable by iOS | none | all three |
| `car/` duplication | arrival/reroute, camera-warn, speed-limit | none |

**The iOS result is the one worth remembering.** ... An audit of the
four surfaces found that parity is decided by statefulness, not by domain
relevance: every feature that reached iOS had its logic in `shared/`, and every one that did not
was welded into a composable or a Service.

## What each stage did

1. **Mechanical split** — 1650 lines of presentational composables and pure helpers into eleven
   same-package files. Zero lines were added to `MapScreen.kt` across all eleven moves, and the
   three external call sites have zero-line diffs.
2. **Pure decisions** → `com.jellemax.detour.map`: `NavPolicy`, `GroupSpinRules`,
   `FollowCamera`, `CameraAuthority`. The car's duplicate arrival/reroute policy deleted.
   (`GroupSpinRules` itself did not last here ... the shared-convoy-relay branch deleted it once
   both platforms were repointed at `shared/…/drive/ConvoyRelay.kt`, the only implementation left.)
3. **Road-hazard machines** → `shared/…/drive/`: `SectionAverageTracker`, `CameraWarner`,
   `SpeedLimitTracker`, with `commonTest` coverage that `ios.yml` gates on JVM and
   Kotlin/Native. Rewrites rather than moves — commonMain has no `Dispatchers.*`, so I/O is
   handed in and timestamps are injected.
4. **One owner for the camera** — `followMe`, `camSuspended` and `lastGestureMs`, written from
   ten sites, became one `CameraAuthority.State`.
5. **Convergence 2 — the section readouts** (`b655528`, `79f20b7`, `e68c815`). ... All three
   surfaces now read the same tracker. Register entry 11 resolved.
6. **State ownership** (#228, the stage 4 gate taken). `MapScreenState` gives the screen's
   twenty remembered vars one owner, which is what let the rest move: the permission plumbing,
   the circle-member markers, the hazard alerts, the two Overpass prefetches, the three
   per-frame loops and the navigation session each went to a file of their own, and the
   launch sync, sign-in feedback and saved-places warm-up went to `AppRoot`, the component
   that is actually always composed. Four decisions that were unreachable from a test
   (`ModeSwitch`, `NavStart`, `SpinRun`, `PermissionPolicy`) are pure functions with tests.
   `MapScreen.kt` 2041 → 1263 lines. The register's `$M` fences that the split moved are
   re-pointed in §D of the register.
```

### What DECISION.md says is left — `DECISION.md:61-106`

```
## What is not verified

- **Convergence 2's readouts were never run.** ... nothing in this repo can [compile the iosMain
  Kotlin or the Swift]. The car half compiles and assembles but has had no replay and no head
  unit.
- **No GPS replay ran for stages 3 or 4.** ... That leaves the posted-limit ladder, the 3-fix
  clear latency and the camera chime unmeasured. Stage 4 was desk-checked on device instead;
  stage 3 was not.
- **The camera chime is now observable but has still never been observed.**
- **`GroupSpinRules` was extracted and tested but its call site was unchanged** — true at the
  time, no longer. The shared-convoy-relay branch ported the rule ... but not the verification
  gap underneath it: a convoy vote still needs two devices transmitting to each other to see it
  resolve for real, which no host here has.
- **The phone-audio convergence items landed without their device session**, so register
  entries 12 and 15 are resolved in code and open on hardware.
- **Stage 4 costs recomposition during a drag.** ... Unmeasured rather than cleared.
  `derivedStateOf` around the two reads is the fix if it shows.

## What is left

- **[The divergence register](15-divergence-register.md).** Its §A entries needing a product
  answer — the watch's discarded instruction text, the HUD at a standstill, distance
  quantisation, catch-up order — and its remaining §B bugs.
- **maxke24/Detour#21** (map choppy while driving) is untouched and unblocked. ...
- **maxke24/Detour#22** should be narrowed rather than closed. ...

## How the remaining work runs

[`specs/00-chain-design.md`](specs/00-chain-design.md) explains the spec chain and its
staleness contract. `.claude/skills/detour-staged-refactor/` carries the procedure, and its
`chain-status.sh` reports where things stand.
```

### What the chain design specifies — `docs/refactor/mapscreen/specs/00-chain-design.md`, in full

```
# How a spec in this directory works

The MapScreen refactor ran as a chain of staged specs, each gating the next on executable
preconditions. Four structure stages and three convergence specs were implemented and removed;
[`convergence-2-section-readouts.md`](convergence-2-section-readouts.md), the last one, is kept
in the tree because it is the only spec whose Work items were written *after* execution rather
than before, and its Preconditions block is the chain's clearest worked example of the
wrong-versus-stale distinction below. This file explains the machinery it ran on.

The implemented specs are in git history at **`b7f4c6f`** —
`git show b7f4c6f:docs/refactor/mapscreen/specs/` lists them — and they are worth reading as
worked examples before running the last one.

## The loop

read spec → run its preconditions → stale? re-brainstorm the spec
                                  → current? writing-plans → plan
plan → subagent-driven-development → commits → verification
     → update the spec's Status block → record the outcome in ../DECISION.md

The plan, not the spec, is what gets executed. A spec fixes the goal and the constraints; the
plan turns them into ordered, individually committable steps.

## The staleness contract

Every spec opens with a **Preconditions** block: shell assertions with values captured when it
was written.

> Run the preconditions before writing the plan. If any assertion fails, the spec is **stale**.
> Do not adapt the plan to the drift — rewrite the spec, then continue.

Two moments demand it: immediately after finishing the previous spec, and again immediately
before writing the next plan.

**A failing precondition is a hypothesis about either the code or the spec, and you must
establish which.** ...

So: **run an assertion before committing it.** Writing one from the shape of the data rather
than from its output is how all four got in.

An inverted assertion is legitimate — a spec whose job is to close a gap asserts the gap is
*still open*, so it correctly fails once the work lands. Say so in the spec when you write one,
or a later reader will read success as drift.

## Which verification a change earns

| Change | Earns |
|---|---|
| A pure move, provable by diff | compiler + the zero-added-lines check |
| A pure function extracted | unit tests, in `commonTest` if it reaches `shared/` |
| Anything reading a GPS fix | a replay A/B against a recorded baseline |
| Anything a user sees move | a desk check on a device |

`.claude/skills/detour-gps-replay/` covers the replay protocol, and
`.claude/skills/detour-staged-refactor/` carries the procedure and `chain-status.sh`.

Name the quantity **before** the run, and compare that number on both sides. "Behaviour looked
unchanged" is not a result; "before 34.2 km / 78 points, after 34.2 km / 78 points, same route
file" is.

## Never in one commit

A move *and* a visibility change to a symbol whose call site also moves · a state-owner change
*and* a lifetime change · an extraction *and* the bug it reveals · an effect body move *and* a
change to that effect's key list — the key list **is** the behaviour · any two changes to
consumers of the same `StateFlow` · any move *and* any reformatting.

## Bookkeeping that is cheap to skip and expensive to skip

Update a spec's Status the day its work lands, and record the outcome in
[`../DECISION.md`](../DECISION.md). This chain skipped it twice and had to reconstruct state
from `git log` both times — once after a dispatch was briefed against a 35-commit-stale HEAD.
```

**Finding: this file never specifies a target folder structure.** Its whole subject is a
procedure — how a spec is written, staled, planned and executed — not a shape for the tree. The
word "folder" does not occur in it, and no target directory layout for `ui/` or anywhere else is
named anywhere in this file.

### My finding, stated directly: the chain never specifies a folder structure

Across `DECISION.md`, `00-chain-design.md`, and `convergence-2-section-readouts.md` — the entire
surviving spec chain, since the implemented specs 1 through "convergence 1" and stage 3's own spec
are only in git history at `b7f4c6f` and not on disk — no target directory tree for `ui/` is ever
proposed. Every stage that has landed did one of three things: (a) a same-package mechanical split
(stage 1 — new files, same `package com.jellemax.detour.ui`, no folders), (b) moving pure
logic/decision code to a **new flat sibling package**, `app/…/map/` (stage 2), or into
`shared/…/drive/` (stage 3), or (c) consolidating scattered `remember` state into one state holder,
`MapScreenState` (stage 6), which changes ownership, not package shape. None of the six stages
reorganizes `ui/` into a `Screen/Component/Functionality/Utility` taxonomy, and none proposes or
mentions a `base/` component library. `detour-file-split`'s own text is explicit that the question
this refactor's mechanics were built for ("should `ui/` stay flat") is separate from, and untouched
by, everything the chain has actually done (see the SKILL.md quote above, and issue #184 below).

---

## `decisions.md` §12 — full contents, and the explicit absence

`docs/guidelines/decisions.md` contains exactly one numbered section, §12, "Why not Compose
Multiplatform, Koin, or `ViewModel`?", with three subsections:

```
## 12. Why not Compose Multiplatform, Koin, or `ViewModel`?

Recorded so it is not re-litigated every six months. Two of these are settled;
one is open and §13 is its answer.

### 12.1 Koin — settled, do not add it

There is nothing to inject. `commonMain` is 64 `object` singletons and exactly
three interfaces, each with more than one real implementation. Testability here
comes from a stronger property than DI: **functions take their inputs**, so
`commonTest` calls them with literals and no fake at all —
`RouteGpx.parseGpx(text, nowMs = 999L)` needs no container.

A DI framework would add a graph to keep coherent across three surfaces and buy
nothing the parameter rule does not already give. Where a seam is genuinely
needed, use a default-argument constructor parameter (§13.3).

### 12.2 Compose Multiplatform — settled for now

Three things block it, in descending order of weight:

1. **Android Auto cannot render Compose.** `app/…/car/` uses the Car App
   Library's template system. CMP would collapse two of three UIs, not three —
   the car screens stay hand-written either way.
2. **The map is the app, and it is two different native SDKs.**
   `ui/MapLibreMap.kt` is ~846 lines of MapLibre *Android* bindings; iOS uses
   MapLibre iOS. CMP has no map component, so the largest screen would be
   `UIKitView`-wrapped native regardless — sharing the chrome around the part
   that actually matters.
3. **The iOS framework is deliberately small and static** (`isStatic = true`,
   one framework, no dylib copy phase). CMP changes that calculus.

The realistic ceiling on a CMP migration is "share the settings, list and detail
screens". Revisit only if Android Auto is dropped, or if the map moves behind a
shared abstraction.

### 12.3 `ViewModel` — this one is open, and §13 answers it

`androidx.lifecycle.ViewModel` **is** multiplatform, so "KMP forbids it" is not
the reason. `ui/RetainedMap.kt:45-46` argues only that `MapView` is the wrong
thing to put inside one — an argument against one use, not against the pattern.

What is used instead is process-global `object` stores. That works, and it suits
iOS (SwiftUI cannot consume `viewModelScope`; it observes a `Watcher`). But the
cost is real and visible in the tree:

- **Lifetime is hand-rolled.** `Auth.clear()` → `resetAccountScopedStores()`
  exists to do what a scoped owner would do for free, and every new store must
  remember to enlist.
- **Test seams are cut into production visibility.** `data/Routes.kt:113` and
  `data/SavedPlaces.kt:32` expose `internal val _routes` / `_places` — a literal
  breach of §4.1 — so a session-switch test can assert the reset mutated state.
- **Start-up ordering leaks into the platform surface.** `expect class
  PlatformLock` was added as a new `Platform.kt` concern because
  `CredentialMigration.migrateOnce` must *finish* before `Settings.init()` reads
  the secure store.

None of that is fatal. It is, however, the one of the three where the cost shows
up as extra machinery rather than as an avoided dependency.
```

**Explicit absence:** these three subsections are the entirety of `decisions.md`. There is no
fourth entry, and none of the three addresses package layout, file nesting, or a shared
design-system/base-component package. The nearest thing to a decision anywhere in the guidelines
on flat-vs-nested packaging is the negative one in `detour-file-split` SKILL.md, already quoted
above: `docs/refactor/mapscreen/DECISION.md` decides no such thing.

---

## Issue #184 and #183 — verified directly with `gh issue view`, not taken on relay

```
gh issue view 184 -R Detour-app/Detour --json number,title,state,labels,body
```

Result: **#184 is OPEN**, labels `documentation`, `enhancement`, `p3-later`. Title:

> "Decide and document how ui/ is packaged: 38 files and 13.7k lines sit flat at one level with no
> placement rule"

Full body, verbatim:

```
`app/src/main/java/com/jellemax/detour/ui/` holds **38 `.kt` files and 13,743 lines at one flat level**, with no subpackages. There is no written rule for what belongs where, so every new screen, dialog or shared composable is placed by whoever writes it, and the folder has no shape a newcomer or an agent can infer.

This needs a decision and a discussion, not a patch. Filing it so the thinking is tracked rather than repeated.

## The question that has to be answered first

What is the separation axis? Candidates that came up and are not interchangeable: pages/destinations, component type, concern, user flow, base theme constants. A layout that mixes them is what the folder already effectively has.

The Android reference answer is one axis: **feature = navigation destination**, with exactly one type-based exception for shared code, split by a testable rule.

- [Guide to Android app modularization patterns](https://developer.android.com/topic/modularization/patterns) — feature modules correspond to screens or navigation destinations, hold their own state holder, depend on data modules, and never depend on each other.
- [Now in Android — modularization](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md) — `core:designsystem` is theme, icons and Material 3 primitives; `core:ui` is composite components that reference data-layer models. The distinction is whether the component knows about a data model.

Applied here, the placement test for any new file would be:

1. References a data model? No → design system.
2. Yes, used by two or more destinations? → shared component.
3. Yes, used by one destination? → that destination's package.

## What makes this non-trivial here

- **The feature partition already exists but is unused by the layout.** `app/src/main/java/com/jellemax/detour/nav/Destination.kt` declares 13 destinations plus 7 `SettingsSpoke`s. `ui/` does not reflect it.
- **There is no presentation layer to put in the feature packages.** `grep -rl ViewModel` over `app/src/main/java/` returns one file, `ui/RetainedMap.kt`. Meanwhile `ui/` carries 304 `remember`/`mutableStateOf`/`collectAsState` sites and **169 direct `import com.jellemax.detour.data.*`** statements. Screens *are* the state holders, calling repositories inline. So the NIA-shaped layout has a slot the codebase cannot currently fill, and "where does state live" has to be answered as part of this, or deferred deliberately.
- **The largest files are single composables.** `ui/MapScreen.kt` is 1,984 lines and one top-level `@Composable`; `ui/SettingsScreen.kt` is 1,491. Both exceed the 1,000-line hard limit. `MapScreen.kt` has grown since the refactor chain closed — `detour-file-split`'s table records it at 1,549. Splitting `MapScreen` is a state-hoisting problem, not a file-move problem (`docs/refactor/mapscreen/DECISION.md`, "What is left").
- **Sequencing matters and cuts both ways.** A pure package move is renames, which rebase cleanly; decomposition is a content rewrite of the highest-churn file in the repo (`ui/MapScreen.kt`, 34 commits in the last 60 days) and conflicts hard. Five of the nine unmerged local branches touch `ui/`, one of them 37 commits ahead.
- **A redesign is in flight**, so this is explicitly *not* a request to move files now.

## Related

`detour-file-split`'s §1 currently asserts the opposite of any subpackage layout, citing a `DECISION.md` ruling that does not exist — filed separately as #183. That has to be corrected regardless of how this issue lands, because it presents an open question as closed.

## What "done" looks like

Not a folder tree, at least not first:

1. A decided separation axis, written where humans read it — `CONTRIBUTING.md`'s "Layout" section already holds this kind of rule in this repo's voice ("A policy earns the core when it is written more than once").
2. A project skill (`detour-ui-structure` or similar) giving agents the placement test, the `Destination` partition, and an explicit statement of the state-holder gap, so a new screen is written the same way twice.
3. A stated position on whether the layout is applied now, applied incrementally as files are touched, or left as a target — and if it is a target, the naming/scoping convention that keeps the eventual move mechanical.

The move itself, if it happens, is mechanical and cheap. The decision is the expensive part.
```

```
gh issue view 183 -R Detour-app/Detour --json number,title,state,labels
```

Result: **#183 is CLOSED**, labels `bug`, `documentation`. Title:

> "detour-file-split cites a DECISION.md ruling that does not exist, presenting a stage-1 tactic as a settled repo-wide package rule"

This confirms, from the tracker itself rather than from the SKILL.md text alone, that the
"flat is not a rule" position is not just self-reported by the skill file — it was raised, argued
and closed as its own issue.

**`ui/` has grown since #184 was filed.** #184's own body states 38 files / 13,743 lines. Verified
against the tree at commit `efa6c9ad`:

```
find app/src/main/java/com/jellemax/detour/ui -maxdepth 1 -type f -name "*.kt" | wc -l
→ 59

wc -l app/src/main/java/com/jellemax/detour/ui/*.kt | tail -1
→ 18033 total
```

So `ui/` is now **59 files and 18,033 lines**, up from the 38 files / 13,743 lines recorded when
#184 was filed — growth of 21 files and roughly 4,300 lines while the placement question has sat
open and deferred (`p3-later`).

---

## Absences — what was searched, and with what patterns, to establish each negative

**Claim: no guideline mentions a `base/` design-system package, `Screen > Component/Functionality/
Utility`, or any component-type taxonomy under `ui/`.**

```
grep -rniE "base/|design.system|component librar|package structure|subpackage|sub-package|feature-by-feature|package-by-feature|layer-by-layer|flat package" \
  docs/ .claude/skills/detour-file-split/ .claude/skills/detour-staged-refactor/
```

Run over: `docs/` (all of it, recursive) and the two named skill directories. Hits returned were
only the two already-quoted `detour-file-split/SKILL.md` lines about `subpackage` (the `no new
subpackage, no ui.map, no ui.settings` line and the stage-2 `map/` line) and unrelated matches
inside `docs/superpowers/plans/2026-09-03-rider-id-identity.md` (a `.NET` repository path
containing the substring `Repositories`, nothing to do with UI packaging). No hit for `base/`,
`design system`, `component librar[y]`, `package structure`, `feature-by-feature`,
`package-by-feature`, or `flat package` anywhere in `docs/` or the two skill directories.

**Claim: `decisions.md` records no standing decision on flat vs. nested packages or a
design-system package.**

Established by reading `docs/guidelines/decisions.md` in full (58 lines, §12.1–12.3, quoted whole
above) — there is no fourth subsection and no other numbered section in the file.

**Claim: no other `docs/refactor/` subtree exists besides `mapscreen/`.**

```
find docs/refactor -type f | sort
```

Returned exactly four files, all under `docs/refactor/mapscreen/`:
`docs/refactor/mapscreen/15-divergence-register.md`,
`docs/refactor/mapscreen/DECISION.md`,
`docs/refactor/mapscreen/specs/00-chain-design.md`,
`docs/refactor/mapscreen/specs/convergence-2-section-readouts.md`.
No `docs/refactor/screenmodel/`, `docs/refactor/tripdetection/`, or any other program directory
exists on disk (the screen-model layer is referenced from `state.md §13` as
`docs/refactor/screenmodel/specs/stage-1-screen-model-seam.md`, but that path does not exist in
the tree — `state.md:63` itself frames it as "designed, not implemented" and the executable spec
as a future artifact, not a claim that the file is on disk today).

**Claim: no package in this repo is nested at any depth.**

```
find app/src/main/java/com/jellemax/detour -mindepth 1 -type d
find shared/src/commonMain/kotlin/com/jellemax/detour -mindepth 1 -type d
```

Both listings (reproduced in full above, under the `detour-file-split` section) show every
directory sitting at exactly one level under `com/jellemax/detour/` — `ui`, `car`, `map`, `nav`,
`data`, `tracking`, `net`, `ble`, `obd2`, `audio`, `update`, `perf`, `auth`, `notif`, `convoy`,
`media` in `app/`; `data`, `drive`, `presentation` in `shared/`. No second-level directory exists
under any of them.

**Claim: `architecture.md`'s layer-by-layer statement is scoped to `:shared` and does not extend
to `app/…/ui/`.**

Established by reading `architecture.md` §1 and §3 in full (quoted above): §3 opens with "Detour's
layers are packages inside `:shared`, not folders inside a feature" and every example under it
(`data/`, `drive/`, `presentation/`) is a `shared/src/commonMain/kotlin/com/jellemax/detour/…`
path. §1's `app/` diagram is the only other place package layout is shown, and it does not
subdivide `ui/`.

**Claim: `README.md` (the guidelines index) contains no additional package-layout rule beyond the
section index.**

Established by reading `docs/guidelines/README.md` in full (55 lines) — it is a section-index
table (§0 through §13, mapping to the files that carry them) plus the "Detour differs from stock
KMP" substitution table; it contains no independent rule of its own.
