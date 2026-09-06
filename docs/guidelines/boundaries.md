*Part of the [Detour Kotlin guidelines](README.md). Section numbers are stable across files — a reference like §8.4 always means §8.4, wherever it lives.*

## 8. File and concern boundaries

When does a file split, and where does the line fall? This section is the
decision. The *mechanics* of a move — same-package placement, the visibility
grep, the zero-added-lines proof — are in the `detour-file-split` skill, which
deliberately does not answer "should I".

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

### 8.6 What must never share a file

- **Two state machines.** Even small ones. Each gets its own `State` and its own
  test.
- **State that changes on different clocks.** Per-GPS-fix and per-tap are two
  concerns; putting them together means every fix invalidates a tap's state.
- **A platform binding and a pure rule.** The rule cannot be tested from
  `commonTest` once it shares a file with a `Context`.
- **Something a second surface needs, next to something it does not.** The
  half that could be shared is now stuck behind the half that cannot.

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
