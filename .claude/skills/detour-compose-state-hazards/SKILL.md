---
name: detour-compose-state-hazards
description: >-
  Edit or review Compose state machinery in this app without introducing a regression that
  compiles clean and only fails in the field. Read this before changing a LaunchedEffect or
  DisposableEffect key list, before deleting or adding a rememberUpdatedState, before
  touching anything that collects TripTrackingService.lastFix or any other StateFlow, before
  moving state or an effect body out of a composable into a function or class, before adding
  rememberSaveable, and before touching a withFrameNanos loop or the camera follow/park
  state — anywhere under app/src/main/java/com/jellemax/detour/ui/, /car/ or wear/. Read it
  while proposing the edit, not while reviewing it.
---

# Compose state hazards in Detour

Six hazard classes. Each one compiles, passes review, and fails on a motorway. They are not
MapScreen-specific — `MapScreen.kt` just has the densest concentration and is used below as
the worked example. Every citation was checked against the tree; re-check before quoting a
line number, because these files move.

**Nothing here is caught by automation.** CI runs `:app:testDebugUnitTest` and
`:shared:testDebugUnitTest` (`.github/workflows/build.yml:118`). There is no Robolectric, no
`compose-ui-test`, and no `androidTest` source set. The automated gate is the Kotlin compiler
and R8. Everything below ships.

## Preconditions

If these disagree with what the body says, the body is stale. Re-derive before trusting it.

```sh
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

Five assertions, `PASS`/`FAIL` per line, non-zero exit if any failed: 9 `rememberUpdatedState`
lines (1 import + 8 uses), 6 `lastFix` subscriptions (5 raw collectors + 1
`collectAsStateWithLifecycle`) and 6 `withFrameNanos` lines in `MapScreen.kt` (1 import + 2
each for the speed and camera loops' `lastNs` seed-and-read + 1 for the position-marker loop,
which needs no `dt` and so seeds no `lastNs`), plus the two inverted ones.

The last two matter as much as the first three: this app uses **no** `derivedStateOf` and
**no** `snapshotFlow` anywhere, and `MainActivity` handles **no** configuration changes
itself. Both are load-bearing below.

---

## 1. An effect's key list is part of its behaviour

A key list decides **when the coroutine is cancelled and restarted**. It does not decide what
the body may read. Snapshot state read from inside a `LaunchedEffect` body or a listener goes
through the `State` delegate on every read and is not cached, so an effect legitimately sees
live values for state it never keyed on. That is a deliberate idiom here, not an oversight.

Verified instances:

| Where | Keys | Reads without keying | Why |
|---|---|---|---|
| `MapScreen.kt:947` | `liveFix, defaultZoom` | `navProgress?.distanceToTurnMeters` (`:955`) | A turn getting closer must not re-aim the camera on its own; it takes effect on the next fix |
| `MapScreen.kt:411` | `camSuspended, spinning, candidates.isEmpty(), spinOffer == null` | `lastGestureMs` (`:418`) | Keys are *derived booleans*: keying on the collections would restart a `lastFix` collector on every convoy vote |
| `MapScreen.kt:1056` | `navigating, liveFix, route` | `destination`, `rerouting`, `lastRerouteMs`, `mode`, `serverConfig` | It **writes** `route` at `:1090` from `scope.launch`, re-keying itself asynchronously on purpose — comment `:1078-1080`: a `LaunchedEffect`-scoped request would be cancelled by the next GPS fix |
| `TripDetailScreen.kt:394` | `scrubbedTo, mapStyle` | — | `scrubbedTo` (`:393`) is `if (replaying) null else rideElapsedMs`: a key deliberately nulled so a value that changes 60×/s does not relaunch a coroutine every frame. Comment `:390-392` |

Three consequences worth holding on to:

- **Adding a key** cancels the coroutine on every change of that value. If the body owns
  accumulators (§3), you have just made them reset.
- **Removing a key** freezes the restart, not the reads. The effect keeps seeing fresh state
  but stops re-running its setup.
- **Equal keys do not restart.** `LaunchedEffect(error)` at `MapScreen.kt:169` shows a
  snackbar; two identical error strings in a row re-key to the same value and raise one
  snackbar, not two. Whenever a key is a value the user can produce twice, decide whether
  that is what you want.

**Check.** For each key you add or remove, answer two questions in the commit message: what
does restarting this coroutine destroy, and what stops updating. A key-list change is never
in the same commit as a move of the effect body — see the `detour-staged-refactor` skill.

## 2. `rememberUpdatedState` exists to defeat stale capture

Eleven uses across three files: `MapScreen.kt:648-650, 849-851, 884, 961`;
`CoverageMapScreen.kt:93`; `TripDetailScreen.kt:357-358`.

The mechanism is worth understanding, because it decides which of them you may remove.

- A local declared `var x by remember { mutableStateOf(…) }` or
  `val x by SomeFlow.collectAsStateWithLifecycle()` is a **delegated** property. Every read
  compiles to a call on the delegate, so a closure that captures it sees live values forever.
- A local computed as a plain expression is a **plain value**, fixed for that composition.
  `MapScreen.kt:534` — `val displayCandidates = spinOffer?.asRouteCandidates() ?: candidates`
  — is exactly this, and it is why `candidatesRef` at `:648` is genuinely load-bearing.
- A **function parameter** is a plain value too.

That third bullet is where the hazard actually gets created. A listener registered once inside
a composable can read `navigating` directly and be correct. Extract that listener into a
top-level `fun installMapListeners(map: MapLibreMap, navigating: Boolean)` and it is frozen at
whatever `navigating` was when the effect ran — and nothing about the change looks wrong.
This is the single most likely way a file split or a state-holder extraction in this repo
introduces a silent bug.

The live example: four map listeners are registered in `LaunchedEffect(mapLibreMap)`
(`MapScreen.kt:651-679`) and read `candidatesRef.value`, `spinOfferRef.value` and
`navigatingRef.value` (`:662`, `:675`, `:676`). `TripDetailScreen.kt:355-358` carries a
comment stating the reason in one line: read via `rememberUpdatedState` "so changing the
multiplier mid-play doesn't need to restart (and re-key) this effect".

**Check.**

- Never delete a `rememberUpdatedState` without saying, in the commit message, which one and
  why the direct read is now safe. Removing one is a behaviour change with no compiler
  signal.
- When a callback body must move behind a function boundary, pass `State<T>` or `() -> T`,
  never `T`.
- Grep guard: the `rememberUpdatedState` count in the changed files must not drop unexplained.

### 2b. Listeners registered once and never removed

`MapScreen.kt:656, 657, 660, 668` add four MapLibre listeners. There is no `removeOn…` call
anywhere under `ui/` except `FogView.map`'s setter (`MapLibreMap.kt:493-497`), which removes
from the old map before adding to the new — that is the correct in-repo pattern to copy.

MapScreen gets away with it for one reason only: `mapLibreMap` has exactly one write site
(`:351`), inside `getMapAsync` inside `DisposableEffect(Unit)` (`:340`), so
`LaunchedEffect(mapLibreMap)` can never re-run with a second non-null map. Give that
`DisposableEffect` a key — theme, style, anything — and `getMapAsync` fires again, the effect
re-runs, and four more listeners stack on the same map. Every camera move then invalidates the
fog view N times.

**Check.** If you add a key to a `DisposableEffect` or make a listener-registering effect
re-runnable, add the matching removal in `onDispose` in the same commit, or do not add the key.

## 3. Coroutine-local accumulators inside an effect

Several effects in this app hold their entire working state in `var`s local to the coroutine.
No test can reach them, no `grep` for a state name finds them, and a reviewer reading a diff
of the key list cannot see them at all.

Verified instances in `MapScreen.kt`:

| Locals | Line | What a silent reset costs |
|---|---|---|
| `center`, `lastFetchMs` | `774-775` | Overpass re-hit on the next fix; extra network, no visible symptom |
| `warnedAt` | `857` | The speed-camera chime re-fires for a camera you already passed, or never re-arms |
| `active`, `exitGate`, `entryMs`, `accMeters`, `last` | `886-890` | An in-progress trajectcontrole measurement is abandoned mid-section: the Ø chip vanishes or shows a wrong average next to a real fine |
| `lastNs` | `963` | The speed easing restarts its clock; one long frame |
| `lat`, `lon`, `bearing`, `zoom`, `appliedLat`… | `992-1002` | The ease restarts from `camTarget` (a visible camera jump), and the epsilon gate stops suppressing redraws — `appliedLat` starts `Double.NaN` as a never-pushed sentinel (`:1000`, tested `:1031`), so a reset means per-frame `setCamera` + fog invalidate returns |

`TripDetailScreen.kt:369` has the same shape (`var lastNs` in the replay frame loop).

Most of these sit in `LaunchedEffect(Unit)` or in a key list that cannot currently change.
**The camera loop is the exception, and it is worth knowing which way round it is:** it is
keyed `LaunchedEffect(cameraActive, haveFix, mapLibreMap)`, and `cameraActive` is
`camAuthority.cameraActive(navigating)`, so it re-keys on every pan, park and resume. Its
accumulators therefore reset routinely rather than never — the ease re-anchors at the current
target, which is what makes a resume snap to you rather than slide. Do not "fix" that by
removing the key.

**Any change that gives one of these effects a key it did not have turns a permanent
accumulator into one that resets** — and the compiler approves.

Compose's frame clock also pauses entirely while the Activity is stopped — measured with a GPS
replay running: 6 camera-loop samples in 5 s foregrounded, 0 in 18 s backgrounded, 5 in 5 s on
return. `camTarget`'s only writer is `LaunchedEffect(liveFix, defaultZoom)`
(`MapScreen.kt:1046, 1048`), and `liveFix` is
`TripTrackingService.lastFix.collectAsStateWithLifecycle()` (`MapScreen.kt:209`) — a
*lifecycle-aware* collector, not a raw one, and it stops below `STARTED`. So while the app is
backgrounded, collection halts, `camTarget` freezes, and — since the frame clock is paused too
— the loop's `lat`/`lon` freeze right alongside it. Nothing is tracking anything while the app
is away.

The jump happens on **resume**, not during the absence: the collector re-subscribes, and the
underlying `StateFlow` conflates, so it delivers only the single latest fix in one step —
`camTarget` snaps the whole distance at once, right as the frame clock restarts and the loop's
frozen `lat`/`lon` see a target that may be hundreds of metres away for the first time. That is
exactly what the snap guard (`MapMotion.shouldSnap`) exists to close, on resume rather than
across the absence.

**Check.** Before changing an effect's keys, read its body and list its locals. If there are
any, the change is behavioural and earns a GPS replay A/B (see `detour-gps-replay`), not a
compile. If you are extracting the machine, extract it as a class with the accumulators as
fields and characterisation tests over them *first* — that is exactly what stage 3 of the
refactor chain is for.

## 4. Several independent collectors on one conflating `StateFlow`

`TripTrackingService.lastFix` is a `StateFlow<Fix?>` (`TripTrackingService.kt:233-234`).
`MapScreen.kt` holds six independent subscriptions on it: `collectAsStateWithLifecycle()` at
`:198`, and raw `.collect` at `:415`, `:737`, `:776`, `:858`, `:891`. They live in effects
with different key lists, so they start and stop independently of each other.

A `StateFlow` **conflates**. Emissions that arrive while a collector is suspended are not
queued — only the latest survives, and it is delivered when the collector comes back. So
awaiting anything slow *inside* the collector lambda drops every value that lands meanwhile,
for that collector only. At 1 Hz fixes and a ten-second Overpass mirror, that is ten lost
fixes.

**The broken pattern**, still live in this repo — `withContext(Dispatchers.IO)` inline in the
collector at `MapScreen.kt:748` (`RoadRoulette.speedLimitWays`) and `:786`
(`SpeedCameras.near`):

```kotlin
TripTrackingService.lastFix.collect { fix ->
    …
    if (needsRefresh) {
        lastFetchMs = now
        val result = withContext(Dispatchers.IO) { SpeedCameras.near(pos) }   // suspends the collector
        …
    }
    // everything below here also stops running while the request is in flight
}
```

**The corrected pattern**, already shipped on the car side —
`car/NavScreen.kt:378-395` (`checkCameras`) and `car/SpinScreen.kt:265-287`
(`updateSpeedLimit`):

```kotlin
if (needsRefresh && cameraFetchJob?.isActive != true) {   // re-entry guard replaces the inline await
    lastCameraFetchMs = now
    cameraFetchJob = lifecycleScope.launch {              // own coroutine; the collector never suspends
        val result = runCatching { withContext(Dispatchers.IO) { SpeedCameras.near(pos) } }
            .onFailure { Log.w(TAG, "camera fetch failed", it) }.getOrNull()
        …
    }
}
```

`NavScreen.kt`'s KDoc (`:365-377`) is the incident report and is worth reading in full: the
collector "is sequential, so awaiting a mirror *here* suspended `onFix` itself — and with it
the camera target, the HUD and the turn card … while every fix that landed meanwhile was
conflated away … a map that stops moving for ten seconds at 100 km/h is not [normal], and that
is what made it look like the map had simply stopped updating." `SpinScreen.kt:254-264` records
the same fix and points back at it.

Two things not to get wrong when porting that fix to the phone:

- **The dropping is currently load-bearing.** The three-consecutive-miss counter at
  `MapScreen.kt:761`, which decides when the posted-limit sign clears, was only ever tuned
  against a stream *with* those fixes missing. Removing the stall retunes it. Land the two as
  separate commits and replay route (ii) across both.
- **Never change two `lastFix` consumers in one commit.** Six independent collectors means
  six independent blast radii; keep a revert down to one.

There is a third option this repo already uses: **sample instead of collect.**
`TripTrackingService.circleSyncLoop` (`:1184-1189`) reads `_lastFix.value` once per tick on
its own timer, which cannot conflate because it never subscribes. The "one collector, two
sinks" rule it cites is stated in `docs/CIRCLES_AND_CONVOYS.md:368-372` — there for battery
cost, but it is the same structural answer.

**Check.** `grep -n 'lastFix' <file>` before and after. If the count of raw `.collect` sites
changed, or an `await`/`withContext` moved inside one, that is a Tier 2 (GPS replay) change.

## 5. `rememberSaveable` versus configuration changes

`MainActivity` declares **no** `android:configChanges` and no `screenOrientation`
(`app/src/main/AndroidManifest.xml:59-79`). So a rotation destroys and recreates the Activity
in the normal Android way:

- `remember` state is **gone**.
- `rememberSaveable` state is **restored** from the saved instance state.

`MapScreen.kt` uses `rememberSaveable` for exactly five values — `radiusKm` (`:152`),
`minRadiusKm` (`:153`), `poiKind` (`:173`), `directionDeg` (`:174`), `settingsCollapsed`
(`:237`) — against dozens of plain `remember` sites. That asymmetry is a decision, not an
accident: those
five are what the user typed or chose, and losing them on a rotate is what a user notices.

Rotation is not the only reset, and this is the part that surprises people:

- `AppRoot` (`MainActivity.kt:118`) swaps screens with a bare `AnimatedContent` (`:172`) and
  **no** `rememberSaveableStateHolder`. Leaving the map for the Hub disposes MapScreen's whole
  composition, so even its `rememberSaveable` values come back at their defaults on return.
- `screen` itself is plain `remember` (`:119`), so a rotation anywhere in the app returns you
  to the map screen.

So the ladder is: plain `remember` survives neither a rotate nor a screen swap;
`rememberSaveable` survives a rotate but not a swap; only a store or a service outlives both
(`Settings`, `SpinResultHolder`, `TripTrackingService`).

**Check.** `rememberSaveable` requires the value to be Bundle-representable or to carry a
`Saver`. The compiler does not check this — you get a runtime crash on rotate. If you add one,
the desk test is: set the value, rotate, confirm it survived; then go to the Hub and back and
confirm you know which answer you expected.

## 6. Per-frame writes to snapshot state

Two frame loops write snapshot state every frame:

- `displaySpeedKmh` (`MapScreen.kt:261`), written at `:970-972` by the `withFrameNanos` loop
  at `:962-975`.
- `rideElapsedMs` (`TripDetailScreen.kt:311`), written at `:376` by the replay loop.

Two mechanics decide what this costs.

**Writing an equal value does not invalidate.** Compose compares before notifying. The easing
loop assigns exactly `target` once the gap falls under `SPEED_EPS_KMH` (`:970-971`), so a
settled speed writes the same double every frame and stops invalidating anything. That is why
the loop is allowed to be unconditional. Keep that property if you touch the easing.

**Where the value is *read* decides how much recomposes.** A snapshot read is attributed to
the nearest enclosing restartable scope. `Box`, `Column` and `Row` are inline and do **not**
create one; a `@Composable` function does, and so does a non-inline composable lambda such as
a `Card`'s or `AnimatedVisibility`'s content, or `Scaffold`'s `content`.

The two in-repo reads sit on opposite sides of that line:

- `TripDetailScreen.kt:501` reads `rideElapsedMs` inside a `Card` content lambda, and the
  screen deliberately does *not* read it at `:393` while playing (`if (replaying) null …`,
  comment `:390-392`). The per-frame invalidation is confined to that card.
- `MapScreen.kt:1353` reads `displaySpeedKmh` in the `Scaffold` content lambda opened at
  `:1253`, reached through only inline `Box`/`Column`/`Row` wrappers. While the number is
  moving, that whole lambda is in the invalidation set each frame.

**Check.** Push a per-frame read into the smallest composable that needs it, or hand it down
as `() -> Double` so the read happens inside the callee. Do not introduce a new per-frame
value into a large lambda body. And do not reach for `derivedStateOf` or `snapshotFlow` as the
fix without saying so explicitly: this app currently contains zero of either, so either is a
new pattern for this codebase (`CONTRIBUTING.md:187-189` asks you to match the surrounding
file).

---

## Tier 0 greps — free, run them on every commit that touches this area

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh <base> [changed files...]
```

- `scripts/check-secret-fields.sh` — fails if a raw `OutlinedTextField`, `TextField` or
  `BasicTextField` is given a secret-ish label (§7). Runs in CI, before the unit tests.

One script, in the skill that defines the tiers, so there is no second copy to drift. It
compares against `<base>` rather than reading the working tree, because every check here is a
*delta*: the `rememberUpdatedState` count must not **drop** (§2), a newly added file must not
own a `CoroutineScope`, `shared/src/commonMain` must still contain zero `Dispatchers`, and
listener additions and removals should move together (§2b). It also prints every
`LaunchedEffect`/`DisposableEffect` declaration line the range touched, so a key-list change
cannot pass unread — a key list is behaviour (§1), and an effect whose body holds
coroutine-local accumulators (§3) loses them on every restart.

Then, in the devcontainer: `./gradlew :app:assembleDebug :app:assembleRelease` (R8 catches
what debug does not) and `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`. The
script deliberately does not run those — they take minutes and their output is meant to be
read.

None of that proves behaviour. For anything in §1, §3 or §4, the only real evidence is a
before/after GPS replay of the same route — see the `detour-gps-replay` skill. Report the two
observations and the route file, not "behaviour looked unchanged".

## 7. Credential fields need `keyboardOptions`, not just a mask

`PasswordVisualTransformation` only changes what is *drawn*. Compose infers nothing about
the IME from it, so a field with a mask and no `keyboardOptions` still ships as
`KeyboardType.Text` with autocorrect on: predictive text runs over the value and it can
land in the keyboard's personalised learning dictionary, which several third-party IMEs
sync off-device. This is what #7 was.

Do not hand-roll it. Use, from `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt`:

- **`SecretTextField`** — masked, `KeyboardType.Password`, a reveal toggle that re-hides on
  focus loss, and autofill. It has no `visualTransformation` parameter on purpose: no
  argument produces an unmasked field.
- **`CredentialTextField`** — not masked, for a client id or a server URL, but with
  autocorrect off and a caller-chosen keyboard type.

Two things that are easy to get backwards:

- **Auto-hide keys on `hasFocus`, not `isFocused`.** The reveal button is an `IconButton`
  inside the field's own focus subtree, so tapping it moves focus off the text field.
  Keyed on `isFocused`, the secret re-hides on the very tap meant to reveal it.
- **Do not suppress autofill.** The instinct to opt a credential field out of everything is
  right for autocorrect and capitalisation and wrong here: ASVS 5.0.0 V6.2.7 (L1) requires
  that paste and external password managers work, and suppressing autofill is precisely
  what breaks them.

`scripts/check-secret-fields.sh` fails if a raw `OutlinedTextField`, `TextField` or
`BasicTextField` is given a secret-ish label. It runs in CI, before the unit tests.

## Related

- `detour-file-split` — moving these symbols between files without changing them.
- `detour-staged-refactor` — which of these changes may share a commit, and which verification
  tier each earns.
- `detour-gps-replay` — how to actually observe §1, §3 and §4 at a desk.
- `docs/refactor/mapscreen/12-eval-risk-sequencing.md:48-159` — the longer form of this
  analysis, written against `MapScreen.kt` at 3193 lines. Its line citations predate the
  stage-1 split and are all wrong now; the reasoning is not.
