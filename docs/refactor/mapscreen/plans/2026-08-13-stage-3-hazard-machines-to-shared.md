# Stage 3 — Road-Hazard Machines into the Shared Core: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking. **This stage is strictly sequential** — see
> [Sequencing](#sequencing). Do not fan the three machines out in parallel.

**Goal:** Move the three stateful road-hazard machines out of `MapScreen.kt`'s coroutine-local
`var`s and into `shared/src/commonMain/kotlin/com/jellemax/detour/drive/`, each as a step
function over an immutable `State`, each with `kotlin.test` tests in `commonTest`, and delete
the car's two duplicates one commit behind their extraction. Behaviour is unchanged. What is
gained: the code that produces this screen's field bugs comes under the only CI gate that runs
`:shared` tests on both the JVM and Kotlin/Native, iOS becomes able to consume three features
it has never had, and five `var`s no test could reach become values a test can drive offline.

**Architecture:** `object` in commonMain. Values in, one value out. No `Dispatchers.*`, no
`CoroutineScope`, no `StateFlow`, no clock of its own, no `expect`, no interface for
abstraction's sake. `State` is an immutable `data class` with a default constructor; the caller
holds one and replaces it. Timestamps arrive as `nowMs: Long` from the Android caller's own
`System.currentTimeMillis()`. I/O stays at the call site: machine 3 is four functions precisely
because the Overpass fetch cannot travel into commonMain.

**Tech Stack:** Kotlin 2.0.20 multiplatform (`build.gradle.kts:5`), `kotlin("test")` in
`commonTest` (`shared/build.gradle.kts`, `commonTest.dependencies`). No JUnit4, no mocking
library, no coroutine test dispatcher.

**Spec:** [`../specs/stage-3-hazard-machines-to-shared.md`](../specs/stage-3-hazard-machines-to-shared.md)
— its Scope, Constraints, Consumed decisions, Ordering, Out of scope and Work items are
binding. Its Work items section was rewritten 2026-08-13 against `a90c3df` and is complete;
this plan turns it into steps and **does not redesign it**. Five places where it is internally
inconsistent or where a citation is stale are listed in
[Corrections to the spec](#corrections-to-the-spec) and resolved there, not silently.

**Base commit: `93515cc`** on `refactor/mapscreen-split`
(*"test(tools): capture the section baseline, and refute #22 on this route"*).

> ### Every `MapScreen.kt` line number below is against the `93515cc` **blob**, and is already stale
>
> `MapScreen.kt` is **1658 lines at `93515cc`**. While this plan was being written, stage 0's
> task **0d** — moving the Overpass fetch off the fix collector — was landing in the working
> tree from a concurrent session: `git diff --stat` went from empty to `+54/-11` on that one
> file inside ten minutes, taking it to 1701 lines. The numbers here were derived with
> `grep -n` against `git show 93515cc:…`, which is a stable blob, and they are correct *for
> that blob*. They are **not** correct for the tree you will open.
>
> **[Step 0](#step-0-re-derive-before-anything-else) is mandatory and is not a commit.** It
> re-derives all three ranges. Machine 3 in particular edits the *same effect* 0d rewrote, so
> its transcription source must be re-read rather than taken from this document
> (`detour-staged-refactor` §2: line drift is not staleness, but transcribing from a drifted
> range is a defect).

---

## Global Constraints

- **Commit messages:** Conventional Commits. **No `Co-Authored-By` trailer. No
  `Claude-Session` trailer. No trailers of any kind.** The spec's Commit sequence table gives
  the eight subjects verbatim; use them.
- **One work item, one commit. Eight work items, eight commits.** No commit spans two items and
  no item produces two commits. The test commit and the move commit for one machine are two
  items on purpose (see [Test-first ordering](#test-first-ordering-and-why-the-tests-commit-alone)).
- **Strictly sequential.** Each machine lands, is replayed against the baseline, and the
  comparison is recorded, *before the next machine's test commit is written*. Two machines in
  flight makes the A/B uninterpretable and the replay is the only real verification this code
  has (`detour-staged-refactor` §4, last two rules).
- **Gradle commands are written plainly:** `./gradlew :shared:testDebugUnitTest`,
  `./gradlew :shared:compileCommonMainKotlinMetadata`, `./gradlew :app:compileDebugKotlin`,
  `./gradlew :app:assembleDebug :app:assembleRelease`,
  `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`.
- **Characterisation tests first, then the move.** Transcribe the inline behaviour into the
  test, watch it fail to compile, write the machine, watch it pass. A test written after the
  machine characterises the machine, not the screen.
- **Constants are copied byte for byte**, comments **moved** and not copied
  (`CONTRIBUTING.md:177-189` — the rationale comments are the design record, and a second copy
  drifts). Where a comment moves, the call site gets a one-line pointer.
- **Read `detour-shared-core` before the first commit and `detour-compose-state-hazards` before
  touching any call site.** This plan does not restate them. Three rules from the second are
  load-bearing and called out where they bite: an effect's key list is behaviour, a
  `rememberUpdatedState` snapshot is behaviour, and reads inside a `LaunchedEffect` body are
  live snapshot reads.
- **No effect key list changes in this stage.** `LaunchedEffect(Unit)` stays `Unit`;
  `LaunchedEffect(navigating)` stays `navigating`. `detour-staged-refactor` §4: an effect body
  move and a change to that effect's key list may never share a commit — and here the body move
  *is* the commit, so the key list does not change at all.
- **No `rememberUpdatedState` is deleted.** All four survive:
  `speedCamerasRef`, `ambientLimitRef`, `navProgressRef` (`:917-919`) and `speedSectionsRef`
  (`:974`). `tier0-greps.sh` asserts the count does not drop.
- **Do not add an `expect`.** `Platform.kt` has four (`:25`, `:41`, `:44`, `:47`) and
  `CONTRIBUTING.md:26-32` says wanting a fifth means pushing the dependency in from the
  platform instead. Nothing here needs one.
- **No `Dispatchers.*` in commonMain, ever.** The fetch stays at the call site. Verified zero
  today by `.claude/skills/detour-shared-core/scripts/check-preconditions.sh`.
- **No machine reads a clock.** `nowMs()` (`shared/…/data/Angles.kt:16`) is **`internal` to
  `:shared`**, so `app/` cannot call it. Android callers keep `System.currentTimeMillis()`
  exactly where they have it today. Do not widen `nowMs()`; `detour-shared-core` §4 says that
  is a change needing its own justification, and this stage does not need it.
- **The machines are `public`, not `internal`** — see
  [Correction 1](#correction-1-the-specs-signatures-say-internal-and-internal-cannot-work).
- **Every new file is `package com.jellemax.detour.drive`.** Not `data`. The path is asserted by
  a downstream interlock (`convergence-2-section-readouts.md`'s Preconditions:
  `test -d shared/src/commonMain/kotlin/com/jellemax/detour/drive` and
  `grep -rl 'SectionAverageTracker' shared/src/commonMain/kotlin | wc -l` → 1). Renaming either
  the directory or a type silently ungates it.
- **Nothing in `MapScreen.kt`'s state layer beyond the seven declarations named in
  [File Structure](#file-structure).** No other `remember` changes, no `rememberSaveable`
  added, no camera state touched. `camSuspended` and `lastGestureMs` are not in this stage's
  blast radius at all.
- **Out of scope by name** (spec's Out of scope): the car and iOS section *readouts*
  (convergence 2), the phone's `NavVoice` policy (convergence 3), the convoy protocol, trip
  auto-detection, the nav vocabulary, `:shared` as a `wear/` dependency, and anything in the
  state layer (stage 4). The spec's Risks section warns that all of it will look easy once
  three machines are in the core, and that each item is larger than all of stage 3.

### The test idiom

Match the four existing `commonTest` files —
`shared/src/commonTest/kotlin/com/jellemax/detour/data/{GroupsTest,ParsingTest,RoutesTest,NavAnnouncerTest}.kt`.
Read `NavAnnouncerTest.kt` and `RoutesTest.kt` before writing the first test; they are the two
closest in shape. `detour-shared-core` §8 has the full list. What bites here:

- **Plain `kotlin.test`**: `import kotlin.test.Test`, `assertEquals`, `assertTrue`,
  `assertFalse`, `assertNull`, `assertNotNull`. **Not JUnit4** — `commonTest` compiles for
  Kotlin/Native, where `org.junit` does not exist. This is the one idiom that changes from
  stage 2.
- **A class per subject, named after it**, with a KDoc saying what contract it covers and what
  failure it guards against. `NavAnnouncerTest`'s KDoc is the model: *"Characterises the ladder
  both surfaces already implemented … Written before either surface was repointed, so a repoint
  that changes behaviour fails here rather than in the field."*
- **`private fun` / `private val` fixture builders at the top of the class**, realistic values,
  `.copy(…)` per test. Not a `@Before` field.
- **Test names are full sentences in camelCase** stating the property, not the method.
- **Time is an argument, never ambient.** No test calls a clock.
- **Doubles are compared with `absoluteTolerance`**, never bare `assertEquals`. `1e-9` where
  exactness is the point, `1e-6` for coordinates, and a *stated* wider tolerance where the
  geometry helpers disagree with each other (see the note in 3a Step 1).
- **A comment above the awkward assertion explaining why it is the assertion**, and
  **regression/characterisation tests carry the observed symptom** — here, the fix indices from
  `tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv`.
- **No file access.** These run on Kotlin/Native; anything needing `expect val fileSystem`
  belongs in `androidUnitTest`. Nothing here needs it.

Two public helpers make every fixture in this stage buildable with literals and no fake, which
is `detour-shared-core` §3's test for whether the shape is right:

| Helper | Where | Note |
|---|---|---|
| `RoadRoulette.offset(center, distanceMeters, bearingRad)` | `RoadRoulette.kt:105-111` | **radians**, and it uses a flat 111 320 m/degree projection |
| `RoadRoulette.distanceMeters(a, b)` | `RoadRoulette.kt:424-432` | haversine over r = 6 371 000 m |

They disagree by a factor of 1.001125 (111 320 vs 6 371 000·π/180 = 111 194.9), so
`offset(p, 1000.0, …)` measures **998.886 m** by `distanceMeters` — the ratio is 0.998886, **not** 1.0011. This was inverted when the plan was written and caught by machine 1, whose derived assertions passed while the literal did not; machines 2 and 3 inherit the same helper, so use 0.998886. Every distance fixture below
therefore keeps a ≥1 m margin from its threshold, and where a boundary must be hit exactly the
test uses an **angle** instead — `offset` and `RoadRoulette.bearingDeg` share the same flat
projection, so a point placed at bearing `θ` reads back as exactly `θ`
(`offset`: `dLon = d·sin θ/(111320·cos lat)`; `bearingDeg`: `dLon·cos lat = d·sin θ/111320`;
`atan2(sin θ, cos θ) = θ`). That identity is what makes the wedge boundary tests deterministic
rather than flaky.

### Test-first ordering, and why the tests commit alone

Per machine: **failing test → run it → implement → run it → commit the tests → commit the
move.** Two commits, in that order, and the tests commit **first and alone**, touching
`commonTest` only.

That is different from stage 2, where each unit landed with its tests in one commit. The reason
is the spec's, and it is the point of the whole stage: these are characterisation tests over
behaviour that is **suspected of carrying a live defect** (maxke24/Detour#22). A tests-only
commit that compiles and passes against a machine written in the *next* commit is a commit
whose diff is purely "what the screen does today, as executable prose". If the extraction later
turns out to have changed something, `git show` on the test commit is the specification to diff
against, with nothing else in it.

Mechanically this means the test commit cannot compile on its own — the machine does not exist
yet. **That is expected and it is why the two commits are adjacent and never pushed
separately.** State it in the commit body:

```
test(drive): characterise the section average tracker

Transcribed from ui/MapScreen.kt's section LaunchedEffect. Compiles
against drive/SectionAverageTracker.kt, which lands in the next commit —
these two are adjacent on purpose so the transcription is reviewable
without the extraction in the same diff.
```

### Sequencing

**Strictly sequential.** Nothing here may be parallelised, and the order is the spec's —
cheapest and most isolated first.

```
Step 0  re-derive every range                         (no commit)
  ↓
3a   test(drive): characterise the section average tracker      commit 1
3a   refactor(drive): move the section average tracker          commit 2
  ↓  REPLAY A/B — route (i), record the comparison, confirm     ← gate
3b   test(drive): characterise the camera warner                commit 3
3b   refactor(drive): move the camera warner                    commit 4
3b′  refactor(car): use the shared CameraWarner                 commit 5
  ↓  REPLAY A/B — route (i), record the comparison, confirm     ← gate
3c   test(drive): characterise the speed limit tracker          commit 6
3c   refactor(drive): move the speed limit tracker              commit 7
3c′  refactor(car): use the shared SpeedLimitTracker            commit 8
  ↓  REPLAY A/B — routes (i) and (ii), record, confirm          ← gate
```

Three orderings are not negotiable:

- **Commits 5 and 8 trail their extraction by exactly one, and never share one with it.** A car
  regression and a phone regression in a single revert is two bisects
  (`detour-staged-refactor` §4; `12-eval-risk-sequencing.md:861-865`).
- **The replay gate is between machines, not after all three.** Machines 1, 2 and 3 are three
  of the six `TripTrackingService.lastFix` consumers, and §4's *"any two `lastFix` consumer
  changes"* row forbids combining them. The gate is what keeps the blast radius to one.
- **3a has no `3a′`.** The car has no section code — `grep -rn 'Section\|sectionAvg\|speedSections\|\.sections' app/src/main/java/com/jellemax/detour/car/` returns **zero hits**, verified at
  `93515cc`. Do not invent a deletion to satisfy the pattern; the car *gaining* a readout is
  convergence 2's item and §C.1 forbids it sharing a commit with this extraction.

## File Structure

| # | Commit | File | Contents | Call sites changed |
|---|---|---|---|---|
| 1 | 1 | `shared/src/commonTest/kotlin/com/jellemax/detour/drive/SectionAverageTrackerTest.kt` | 12 tests | — |
| 2 | 2 | `shared/src/commonMain/kotlin/com/jellemax/detour/drive/SectionAverageTracker.kt` | `Reading`, `State`, `onFix`, `sectionExitGate`, 8 constants | `ui/MapScreen.kt:970-1031`, `ui/MapCameraTuning.kt:67-100` (deleted) |
| 3 | 3 | `shared/src/commonTest/kotlin/com/jellemax/detour/drive/CameraWarnerTest.kt` | 11 tests | — |
| 4 | 4 | `shared/src/commonMain/kotlin/com/jellemax/detour/drive/CameraWarner.kt` | `State`, `Outcome`, `Step`, `onFix`, 2 constants | `ui/MapScreen.kt:913-968` |
| 5 | 5 | — | — | `car/NavScreen.kt:380-399`, `:127` |
| 6 | 6 | `shared/src/commonTest/kotlin/com/jellemax/detour/drive/SpeedLimitTrackerTest.kt` | 11 tests | — |
| 7 | 7 | `shared/src/commonMain/kotlin/com/jellemax/detour/drive/SpeedLimitTracker.kt` | `State`, `needsWays`, `fetchStarted`, `withWays`, `onFix`, `reset`, 4 constants | `ui/MapScreen.kt:785-835`, `:244-250` |
| 8 | 8 | — | — | `car/SpinScreen.kt:52-61`, `:94-98`, `:117-121`, `:150`, `:265-296` |

`shared/src/commonMain/kotlin/com/jellemax/detour/drive/` does **not** exist yet — the only
directory under `com/jellemax/detour/` in commonMain is `data/`, verified at `93515cc`. Commit
2 creates it. Nothing that is not a drive-time decision machine may accumulate in it.

**Seven `MapScreen.kt` declarations are removed across the stage, and no others:**

| At `93515cc` | Declaration | Fate |
|---|---|---|
| `:245-247` | `var speedLimitWays` | deleted — `SpeedLimitTracker.State.ways` |
| `:248` | `var speedLimitWaysCenter` | deleted — `State.waysCenter` |
| `:249` | `var speedLimitFetchMs` | deleted — `State.lastFetchMs` |
| `:250` | `var speedLimitMisses` | deleted — `State.misses` |
| — | — | **added:** `var limitState by remember { mutableStateOf(SpeedLimitTracker.State()) }` |
| `:244` | `var ambientSpeedLimitKmh` | **kept** — read at `:918` and `:1465`; see 3c Step 5 |
| `:255-256` | `var sectionAvgKmh`, `var sectionLimitKmh` | **kept, both** — collapsing them is stage 4's |
| `:251-252` | `var speedCameras`, `var speedSections` | **kept** — the fetch effect at `:841-862` owns them and is not this stage's |

## Step 0: re-derive before anything else

**Not a commit. Do it once, at the start, and again before machine 3.**

- [ ] **0.1 — Confirm the base and whether 0d has landed**

```bash
git log --oneline -5
git status --short
wc -l app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
```

`MapScreen.kt` is **1658** lines at `93515cc`. If it is larger, 0d (or something else) has
landed on top. **Do not proceed with an unclean working tree**: 0d must be committed before
commit 1, or the extraction and the fetch move share a diff.

- [ ] **0.2 — Re-derive the three ranges**

```bash
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
grep -n 'ambientSpeedLimitKmh\|speedLimitWays\|speedLimitWaysCenter\|speedLimitFetchMs\|speedLimitMisses' $M
grep -n 'var warnedAt\|speedCamerasRef\|ambientLimitRef\|navProgressRef\|Speed camera ahead' $M
grep -n 'speedSectionsRef\|var active: SpeedCameras.Section\|sectionAvgKmh\|sectionLimitKmh' $M
grep -n 'sectionExitGate\|SECTION_GATE_METERS\|SECTION_WEDGE_DEG' app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt
```

Expected symbol set at `93515cc`, as the anchor to compare against:

| Symbol | `93515cc` |
|---|---|
| ambient-limit effect | `:785-790` comment, `:791` `LaunchedEffect(navigating)`, `:792-801` reset comment, `:802-803` reset, `:804` gate, `:805-834` collector, `:835` close |
| camera/section Overpass fetch (**not this stage's**) | `:837-862` |
| camera-warn effect | `:913-916` comment, `:917-919` three `rememberUpdatedState`, `:920-923` `toneGen` + `DisposableEffect`, `:924` `LaunchedEffect(Unit)`, `:925` `var warnedAt`, `:926-967` collector, `:968` close |
| section effect | `:970-973` comment, `:974` `speedSectionsRef`, `:975` `LaunchedEffect(Unit)`, `:976-980` five `var`s, `:981-1030` collector, `:1031` close |
| `announceAloud` | `:711` |
| `SpeedHud` call | `:1466-1467` (`averageKmh`, `averageLimitKmh`) |
| `sectionExitGate` + constants | `MapCameraTuning.kt:67-100`, file is 100 lines |

**Staleness, not drift**: a range that moved by a constant is fine, re-derive and continue. A
*symbol* that is gone or has changed shape means the spec is stale — stop and follow
`detour-staged-refactor` §2 rather than adapting.

- [ ] **0.3 — Re-run the spec's preconditions, with the corrected last assertion**

```bash
.claude/skills/detour-staged-refactor/scripts/chain-status.sh 3
.claude/skills/detour-shared-core/scripts/check-preconditions.sh
```

Then, replacing the spec's own baseline assertion, which no longer measures what it says
(see [Correction 4](#correction-4-the-baseline-precondition-counts-the-wrong-column)):

```bash
# The section machine was observed end to end. Count AVG events, not event rows.
grep -c 'AVG-ON' tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv   # expect 2
grep -c 'AVG-CLEARED' tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv # expect 2
```

- [ ] **0.4 — Read the four inputs, in this order**

1. `.claude/skills/detour-shared-core/SKILL.md` §§1-4, 7, 8.
2. `.claude/skills/detour-staged-refactor/SKILL.md` §4 and §5.
3. `.claude/skills/detour-compose-state-hazards/SKILL.md` §1.
4. `shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleEvents.kt` — the in-repo
   precedent the spec's Constraints section requires: decision and wording in the core,
   delivery per platform.

---

## Task 3a: `SectionAverageTracker` — the trajectcontrole average

**Commits 1 and 2.** First because it has one input stream, one output value and no I/O of its
own: the Overpass fetch that fills `speedSections` is a *different* effect (`:837-862`) and
stays exactly where it is.

**Files:**
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/SectionAverageTrackerTest.kt`
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/SectionAverageTracker.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (`:970-1031`)
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt` (delete `:67-100`)

**Interfaces:**
- Produces `object SectionAverageTracker` with
  `fun onFix(state: State, sections: List<SpeedCameras.Section>, at: LatLon, headingDeg: Double?, speedMps: Double, nowMs: Long): State`
  and a nested `data class Reading(averageKmh, limitKmh)`.
- Consumes `SpeedCameras.Section` (`SpeedCameras.kt:39-44`), `LatLon` (`RoadRoulette.kt:15`),
  `RoadRoulette.distanceMeters` (`:424`), `RoadRoulette.withinWedge` (`:101`). All in
  commonMain already, all public, no Android types anywhere near them.
- **Exposes no `StateFlow`.** "No service owns a `CoroutineScope`" applies to flows too, and a
  machine that owns a flow owns a subscription. The iOS `FlowWatcher` cost is therefore not
  paid in this stage at all; when convergence 2 pays it, `Reading` being **one** type makes it
  **one** new subclass rather than two (`shared/src/iosMain/.../FlowWatcher.kt` has nine today).

- [ ] **Step 1: Write the failing test first**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/SectionAverageTrackerTest.kt`:

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterises [SectionAverageTracker] - the trajectcontrole entry/exit gate and
 * the running average - transcribed from `ui/MapScreen.kt`'s section
 * `LaunchedEffect` before it was repointed, so a repoint that changes behaviour
 * fails here rather than in the field.
 *
 * **Characterisation, not correctness.** maxke24/Detour#22 (the average vanishing
 * a few hundred metres in and never re-arming) is still undiagnosed, and these
 * tests deliberately encode no cause: the parser theory is refuted
 * (`data/ParsingTest.kt`'s `SpeedCameraSectionTest`), and the shape below is
 * what makes the suppression observable at all - every transition that can null
 * [SectionAverageTracker.Reading] is now one step of one fix, so a test can drive
 * the recorded sequence offline and watch which one fires.
 *
 * Three of those transitions have **no replay coverage and never will from this
 * route**: `reachedEnd` at a far gantry entered from the near end (this route's
 * exit gate *is* the next section's entry), `timedOut`, and the red over-limit
 * chip. See [limitIsCarriedStraightThroughFromTheSection] for why the posted
 * limit is unit-tested only.
 *
 * No Android APIs, no clock, no file access: runs on JVM and Kotlin/Native both.
 */
class SectionAverageTrackerTest {

    /** Epoch millis. Realistic, because the machine subtracts timestamps and a
     *  zero base would hide a sign error. */
    private val t0 = 1_700_000_000_000L

    // The real E40 gantry clusters the recorded baseline drives through:
    // relation 15682532's west and east `device` nodes, from
    // tools/mocklocation/routes/README.md:71. One node per end here; the
    // relation has one per carriageway a few metres apart, which the machine
    // treats identically because `atGate` is an `any`.
    private val westGate = LatLon(50.86929, 4.49257)
    private val eastGate = LatLon(50.86183, 4.60503)

    /** Compass bearing west gate -> east gate, 96.0deg. The reverse is 276.0deg;
     *  both are `RoadRoulette.bearingDeg` of the two coordinates above. */
    private val eastward = 96.0

    /** [meters] from [from] along compass [bearingDeg]. `RoadRoulette.offset`
     *  takes radians and projects at a flat 111 320 m/deg, so what
     *  `distanceMeters` (haversine, r = 6 371 km) then measures is 1.001125x
     *  this - which is why every distance fixture keeps a metre of margin. */
    private fun at(from: LatLon, meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(from, meters, bearingDeg * PI / 180.0)

    /** [spanMeters] defaults to what the relation reports, 7 950 m. The two node
     *  coordinates are 7 936 m apart by `distanceMeters`; both figures are real
     *  and the machine only reads `spanMeters` for the overshoot bound. */
    private fun section(
        maxspeedKmh: Double? = null,
        endA: List<LatLon> = listOf(westGate),
        endB: List<LatLon> = listOf(eastGate),
        spanMeters: Double = 7_950.0,
    ) = SpeedCameras.Section(
        endA = endA, endB = endB, spanMeters = spanMeters, maxspeedKmh = maxspeedKmh,
    )

    /** A 200 m section - `SpeedCameras.MIN_SPAN_M` exactly - running due north.
     *  Short enough that the far gate is reachable on the fix after entering,
     *  which is the only geometry in which the 150 m floor is observable. */
    private val shortEntry = LatLon(50.0, 4.0)
    private val shortExit = at(shortEntry, 200.0, 0.0)
    private fun shortSection(maxspeedKmh: Double? = null) = section(
        maxspeedKmh = maxspeedKmh,
        endA = listOf(shortEntry),
        endB = listOf(shortExit),
        spanMeters = 200.0,
    )

    private fun armAt(
        pos: LatLon,
        headingDeg: Double,
        sections: List<SpeedCameras.Section>,
        nowMs: Long = t0,
    ) = SectionAverageTracker.onFix(
        state = SectionAverageTracker.State(),
        sections = sections,
        at = pos,
        headingDeg = headingDeg,
        speedMps = 33.0,
        nowMs = nowMs,
    )

    // ---- arming ----------------------------------------------------------

    /** The returned `exitGate` is the *far* end, never the one just passed. That
     *  is the whole reason the gate has a heading test: you pass a device node on
     *  the way out too, and matching on proximity alone used to start a
     *  measurement as you left a section. */
    @Test
    fun armsOnEnteringOneEndWhileTheFarEndIsInsideTheWedge() {
        val st = armAt(westGate, eastward, listOf(section()))
        assertEquals(listOf(eastGate), st.exitGate)
        assertNotNull(st.active)
        assertEquals(t0, st.entryMs)
        assertEquals(0.0, st.accMeters, 1e-9)
        assertEquals(westGate, st.last)
        // No average yet: nothing has accumulated.
        assertNull(st.reading.averageKmh)
    }

    /** Below the floor the bearing is noise, so a stopped phone cannot
     *  heading-test its way into a section however good the geometry is. The
     *  boundary, stated: exactly [SectionAverageTracker.ARM_MIN_MPS] does **not**
     *  arm, because the test is `takeIf { speedMps > … }`. Note this is the
     *  opposite boundary to [SpeedLimitTracker.MIN_MPS], whose call sites spell
     *  it `if (speedMps < MIN_MPS) return` - same literal 2.0, opposite edge, and
     *  neither is a typo to be tidied. */
    @Test
    fun doesNotArmAtOrBelowTheBearingNoiseFloor() {
        val stopped = SectionAverageTracker.onFix(
            SectionAverageTracker.State(), listOf(section()),
            at = westGate, headingDeg = eastward,
            speedMps = SectionAverageTracker.ARM_MIN_MPS, nowMs = t0,
        )
        assertNull(stopped.active)
        assertEquals(SectionAverageTracker.State(), stopped)

        val creeping = SectionAverageTracker.onFix(
            SectionAverageTracker.State(), listOf(section()),
            at = westGate, headingDeg = eastward,
            speedMps = SectionAverageTracker.ARM_MIN_MPS + 0.01, nowMs = t0,
        )
        assertNotNull(creeping.active)
    }

    @Test
    fun doesNotArmWithANullHeading() {
        val st = SectionAverageTracker.onFix(
            SectionAverageTracker.State(), listOf(section()),
            at = westGate, headingDeg = null, speedMps = 33.0, nowMs = t0,
        )
        assertEquals(SectionAverageTracker.State(), st)
    }

    /** Nearest match, not the first in the list: the two directions of one
     *  trajectcontrole are separate relations sharing a location, and a short
     *  section can sit inside a longer one. This is the 15682532 / 15685856 case
     *  `tools/mocklocation/routes/README.md` documents. The far candidate is put
     *  first so a `firstOrNull` regression fails here. */
    @Test
    fun nearestMatchWinsWhenTwoSectionsShareAGateLocation() {
        val nearer = section()
        val fartherEntry = at(westGate, 40.0, eastward) // still inside the 60 m gate
        val farther = section(
            endA = listOf(fartherEntry),
            endB = listOf(at(eastGate, 500.0, eastward)),
            spanMeters = 8_400.0,
        )
        val st = armAt(westGate, eastward, listOf(farther, nearer))
        assertSame(nearer, st.active)
        assertEquals(listOf(eastGate), st.exitGate)
    }

    /** A wrong-direction transit still arms, and this test says so in its name
     *  rather than asserting a refusal the code has never had.
     *  `routes/README.md`'s own measurement: at
     *  `public-trajectcontrole-reverse.txt`'s entry gate the far end bears 276deg
     *  against a heading of 284deg - 8deg off, deep inside the 75deg wedge. Pin the
     *  behaviour, not the wish. */
    @Test
    fun aWrongDirectionTransitStillArms() {
        val st = armAt(eastGate, 284.0, listOf(section()))
        assertEquals(listOf(westGate), st.exitGate)
    }

    // ---- the running average ---------------------------------------------

    /** No average until [SectionAverageTracker.MIN_ACC_METERS_FOR_AVERAGE] has
     *  accumulated - 20 m, one fix at motorway speed. Then it is
     *  `(accMeters / 1000) / elapsedHours`, exactly. */
    @Test
    fun publishesNoAverageUntilTwentyMetresHaveAccumulated() {
        var st = armAt(westGate, eastward, listOf(section()))
        val p1 = at(westGate, 15.0, eastward)
        st = SectionAverageTracker.onFix(st, listOf(section()), p1, eastward, 33.0, t0 + 1_000L)
        // ~15.0 m accumulated, under the floor.
        assertNull(st.reading.averageKmh)

        val p2 = at(westGate, 30.0, eastward)
        st = SectionAverageTracker.onFix(st, listOf(section()), p2, eastward, 33.0, t0 + 2_000L)
        assertNotNull(st.reading.averageKmh)
    }

    @Test
    fun theAverageIsAccumulatedDistanceOverElapsedTime() {
        var st = armAt(westGate, eastward, listOf(section()))
        val p1 = at(westGate, 1_000.0, eastward)
        st = SectionAverageTracker.onFix(st, listOf(section()), p1, eastward, 33.0, t0 + 36_000L)
        val acc = RoadRoulette.distanceMeters(westGate, p1)
        assertEquals(acc, st.accMeters, 1e-9)
        assertEquals((acc / 1000.0) / (36_000L / 3_600_000.0), st.reading.averageKmh!!, 1e-9)
        // And the concrete number, so a rewritten formula fails too. 1 000 m of
        // `offset` measures 1 001.1 m of `distanceMeters`, which is the whole of
        // the 0.05 tolerance - not slack in the rule.
        assertEquals(99.889, st.reading.averageKmh!!, 0.05)
    }

    /** A timestamp that has not advanced publishes nothing rather than dividing
     *  by zero: `elapsedHours > 0` guards it. Two fixes can share a millisecond
     *  once time is injected, which is exactly what makes this reachable. */
    @Test
    fun aZeroElapsedFixPublishesNoAverage() {
        var st = armAt(westGate, eastward, listOf(section()))
        st = SectionAverageTracker.onFix(
            st, listOf(section()), at(westGate, 300.0, eastward), eastward, 33.0, t0,
        )
        assertNull(st.reading.averageKmh)
    }

    /**
     * The recorded transit, reproduced offline. From
     * `tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv`: `AVG-ON`
     * at fix 166 (t = 162.669 s), `AVG-CLEARED` at fix 543 (t = 546.927 s), and
     * `cum_m` 3 664 -> 11 610, i.e. **7 946 m over 384.258 s**.
     *
     * The chip's last on-screen read was `Ø 75`; the arithmetic gives **74.44**.
     * The gap is not a defect and not slack: `cum_m` is the route file's own
     * distance while `accMeters` integrates the GPS fixes the app received, and
     * the screenshot is one frame earlier than the clear. The number worth
     * pinning is the arithmetic over the measured pair.
     */
    @Test
    fun reproducesTheRecordedBaselineAverage() {
        val mid = at(westGate, 4_000.0, eastward) // nowhere near either gate
        val armed = SectionAverageTracker.State(
            active = section(),
            exitGate = listOf(eastGate),
            entryMs = 162_669L,
            accMeters = 7_946.0,
            last = mid,
            reading = SectionAverageTracker.Reading(null, null),
        )
        // `last == at`, so this fix adds no distance and the state is read as
        // recorded rather than extrapolated.
        val st = SectionAverageTracker.onFix(
            armed, emptyList(), at = mid, headingDeg = eastward, speedMps = 10.7,
            nowMs = 546_927L,
        )
        assertEquals(74.44373, st.reading.averageKmh!!, 1e-5)
        assertNotNull(st.active) // 7 946 m is well inside the 11 530 m overshoot bound
    }

    // ---- ending the measurement -------------------------------------------

    /** Only the end we drove in towards ends it, and not before 150 m. On a 200 m
     *  section the exit gate is reachable on the fix after entering, which is the
     *  only geometry in which the floor is observable at all - hence the short
     *  fixture rather than the E40 one. 145 m along is 55 m from the exit node,
     *  inside the 60 m gate, and still does not exit. */
    @Test
    fun theHundredAndFiftyMetreFloorStopsAnImmediateExit() {
        var st = armAt(shortEntry, 0.0, listOf(shortSection()))
        st = SectionAverageTracker.onFix(
            st, listOf(shortSection()), at(shortEntry, 145.0, 0.0), 0.0, 20.0, t0 + 8_000L,
        )
        assertNotNull(st.active)
        assertTrue(st.accMeters < SectionAverageTracker.MIN_ACC_METERS_BEFORE_EXIT)

        st = SectionAverageTracker.onFix(
            st, listOf(shortSection()), at(shortEntry, 160.0, 0.0), 0.0, 20.0, t0 + 9_000L,
        )
        assertNull(st.active)
    }

    /** On exit **both** halves of the reading go null in the same step. A section
     *  limit surviving its own section is a sign judging you against a road you
     *  have left. */
    @Test
    fun exitingNullsBothHalvesOfTheReadingInOneStep() {
        val tagged = shortSection(maxspeedKmh = 120.0)
        var st = armAt(shortEntry, 0.0, listOf(tagged))
        assertEquals(120.0, st.reading.limitKmh!!, 1e-9)
        // 180 m: over the 150 m floor and 20 m from the exit node, so this one
        // fix both publishes an average and then ends the measurement.
        st = SectionAverageTracker.onFix(
            st, listOf(tagged), at(shortEntry, 180.0, 0.0), 0.0, 20.0, t0 + 10_000L,
        )
        assertNull(st.active)
        assertEquals(emptyList<LatLon>(), st.exitGate)
        assertNull(st.last)
        assertNull(st.reading.averageKmh)
        assertNull(st.reading.limitKmh)
        // accMeters is deliberately *not* zeroed on exit - the inline version did
        // not zero it either, and the arming branch overwrites it. Characterised,
        // not tidied.
        assertTrue(st.accMeters > 0.0)
    }

    /** Overshoot ends it at `spanMeters * 1.4 + 400`: 680 m on the 200 m section.
     *  The fix is placed 700 m out, 500 m past the exit node and therefore well
     *  outside its gate, so the clear is by overshoot alone and not by
     *  `reachedEnd`. */
    @Test
    fun overshootEndsIt() {
        var st = armAt(shortEntry, 0.0, listOf(shortSection()))
        st = SectionAverageTracker.onFix(
            st, listOf(shortSection()), at(shortEntry, 700.0, 0.0), 0.0, 20.0, t0 + 40_000L,
        )
        assertNull(st.active)
    }

    /** The timeout ends it at 30 minutes, and the boundary is stated: exactly
     *  [SectionAverageTracker.TIMEOUT_MS] does **not** end it, because the test is
     *  `>`. Unreachable by any replay - no fixture sits in a section for half an
     *  hour - so this unit test is its only coverage. The fix is kept 50 m along
     *  so neither other clause can fire. */
    @Test
    fun theTimeoutEndsItAndExactlyTheTimeoutDoesNot() {
        val armed = armAt(shortEntry, 0.0, listOf(shortSection()))
        val onTheBound = SectionAverageTracker.onFix(
            armed, listOf(shortSection()), at(shortEntry, 50.0, 0.0), 0.0, 20.0,
            nowMs = t0 + SectionAverageTracker.TIMEOUT_MS,
        )
        assertNotNull(onTheBound.active)

        val pastIt = SectionAverageTracker.onFix(
            armed, listOf(shortSection()), at(shortEntry, 50.0, 0.0), 0.0, 20.0,
            nowMs = t0 + SectionAverageTracker.TIMEOUT_MS + 1,
        )
        assertNull(pastIt.active)
    }

    /**
     * Re-arms into a second section whose entry gate is the first one's exit node
     * - the back-to-back transition at 6.36 km that
     * `tools/mocklocation/routes/README.md` said had never been observed working
     * and that `trajectcontrole-a90c3df-events.tsv` then recorded: `AVG-CLEARED`
     * at fix 543, `AVG-ON` again at fix **546**.
     *
     * The earliest possible re-arm is the fix *after* the clearing one: on the
     * clearing fix the machine is in its advance branch and the arming branch does
     * not run. Three fixes in the recording, one here - both consistent with that.
     */
    @Test
    fun reArmsAcrossASharedGantryOnTheFollowingFix() {
        val shared = at(westGate, 6_360.0, eastward)
        val first = section(endA = listOf(westGate), endB = listOf(shared), spanMeters = 6_360.0)
        val second = section(endA = listOf(shared), endB = listOf(eastGate), spanMeters = 1_590.0)
        val sections = listOf(first, second)

        var st = armAt(westGate, eastward, sections)
        assertSame(first, st.active)
        st = SectionAverageTracker.onFix(
            st, sections, at(westGate, 3_000.0, eastward), eastward, 33.0, t0 + 100_000L,
        )
        assertSame(first, st.active)
        st = SectionAverageTracker.onFix(st, sections, shared, eastward, 33.0, t0 + 220_000L)
        assertNull(st.active) // reachedEnd at the shared node

        st = SectionAverageTracker.onFix(
            st, sections, at(shared, 20.0, eastward), eastward, 33.0, t0 + 221_000L,
        )
        assertSame(second, st.active)
        assertEquals(listOf(eastGate), st.exitGate)
        // The first section is not re-entered from here: its far end (the west
        // gate) is 180deg off the heading, outside the 75deg wedge.
    }

    // ---- the posted limit -------------------------------------------------

    /**
     * `Reading.limitKmh` is `Section.maxspeedKmh`, carried straight through.
     *
     * **This test is the only coverage of the posted-limit half that exists or
     * can exist from the current fixtures.** Neither E40 relation tags `maxspeed`
     * on the relation - only the `device` nodes do, and
     * `SpeedCameras.parseSection` (`SpeedCameras.kt:109`) reads the relation's
     * tags - so `Section.maxspeedKmh` is null for every replay of every
     * trajectcontrole fixture, and no replay can exercise the over/under-limit
     * comparison or the red chip. Relation **16251379** is the tagged alternative
     * (`17-public-trace-datasets.md` §3.3) if a fixture is ever wanted; that is a
     * new fixture *and* a new baseline, and not this stage's work.
     */
    @Test
    fun limitIsCarriedStraightThroughFromTheSection() {
        val tagged = armAt(westGate, eastward, listOf(section(maxspeedKmh = 120.0)))
        assertEquals(120.0, tagged.reading.limitKmh!!, 1e-9)

        val untagged = armAt(westGate, eastward, listOf(section(maxspeedKmh = null)))
        assertNull(untagged.reading.limitKmh)
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :shared:testDebugUnitTest --tests 'com.jellemax.detour.drive.SectionAverageTrackerTest'
```
Expected: a compilation failure naming `SectionAverageTracker` (unresolved reference). That is
the test running before the code exists. Anything else — a pass, a different error, "no tests
found" — means the file landed in the wrong source set. It belongs in
`shared/src/commonTest/kotlin/…`, not `androidUnitTest`.

- [ ] **Step 3: Create `SectionAverageTracker.kt`**

The two named constants and `sectionExitGate` are **moved** from `MapCameraTuning.kt:67-100`
with their comments, byte for byte. The five inline literals get names for the first time and
must not get new values; each already carries a comment at `MapScreen.kt`, and the comment
moves rather than being restated.

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras

/**
 * Average speed through a trajectcontrole. Enter at one end heading for the
 * other, then integrate GPS distance over elapsed time until we pass that far
 * end (or overshoot / time out). The average is what the section actually
 * measures, so it's the number worth seeing while inside one.
 *
 * A step function over an immutable [State]: the caller holds one, hands it and
 * a fix in, and replaces it with what comes back. No clock of its own - the
 * caller passes [onFix]'s `nowMs`, because a machine that is path-dependent over
 * timestamps and reads the clock itself has no reproducible test. (`nowMs()` in
 * `data/Angles.kt` is `internal` to `:shared` and so is not callable from
 * `app/`; the Android call sites use `System.currentTimeMillis()` as they always
 * have.)
 *
 * **No `StateFlow` here, deliberately.** A machine that owns a flow owns a
 * subscription and a scope. Whichever per-surface holder wants one wraps this;
 * [Reading] being one type is what makes that one iOS `FlowWatcher` subclass
 * rather than two.
 */
object SectionAverageTracker {

    // How close to a section's device node counts as passing it, for entering and
    // leaving a trajectcontrole average-speed measurement.
    const val SECTION_GATE_METERS = 60.0

    // How far off your heading the far end of a section may lie and still count as
    // driving into it. Wide, because a long section can curve away — it only has to
    // separate "the other end is ahead of me" from "behind me, I'm on my way out".
    const val SECTION_WEDGE_DEG = 75.0

    /** Below this the bearing is noise, so a stopped phone can't heading-test its
     *  way into a section. */
    const val ARM_MIN_MPS = 2.0

    /** Nothing is published until this much has accumulated - one fix at
     *  motorway speed - or the first average would be a division by a
     *  rounding error. */
    const val MIN_ACC_METERS_FOR_AVERAGE = 20.0

    /** Keeps the gate we entered through from counting as the exit on the fix
     *  right after entering. */
    const val MIN_ACC_METERS_BEFORE_EXIT = 150.0

    /** Overshoot bound: `spanMeters * OVERSHOOT_FACTOR + OVERSHOOT_SLACK_METERS`.
     *  Missing the exit gantry entirely - a lane change past it, a lost fix -
     *  must not leave an average on screen for the rest of the drive. */
    const val OVERSHOOT_FACTOR = 1.4
    const val OVERSHOOT_SLACK_METERS = 400.0

    /** Last resort: half an hour in one section is a stop, not a transit. */
    const val TIMEOUT_MS = 30 * 60_000L

    /** The average and the limit it is judged against, as one value, so the two
     *  cannot disagree across a recomposition and so exporting them costs one
     *  iOS watcher subclass rather than two. */
    data class Reading(val averageKmh: Double?, val limitKmh: Double?)

    data class State(
        val active: SpeedCameras.Section? = null,
        val exitGate: List<LatLon> = emptyList(),
        val entryMs: Long = 0L,
        val accMeters: Double = 0.0,
        val last: LatLon? = null,
        val reading: Reading = Reading(null, null),
    )

    /**
     * One GPS fix. Returns the next state; [State.reading] is what a readout
     * shows, both halves null when not inside a section.
     *
     * [sections] is whatever the caller's Overpass prefetch currently holds -
     * this machine never fetches. [headingDeg] and [speedMps] are only read
     * while unarmed, which is where the bearing test lives.
     */
    fun onFix(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): State {
        val current = state.active ?: return arm(state, sections, at, headingDeg, speedMps, nowMs)
        return advance(state, current, at, nowMs)
    }

    private fun arm(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): State {
        val heading = headingDeg?.takeIf { speedMps > ARM_MIN_MPS } ?: return state
        // Nearest match, not the first: the two directions of one
        // trajectcontrole are separate relations sharing a location, and
        // a short section can sit inside a longer one.
        val entered = sections
            .mapNotNull { s -> sectionExitGate(s, at, heading)?.let { s to it } }
            .minByOrNull { (s, _) ->
                (s.endA + s.endB).minOf { RoadRoulette.distanceMeters(at, it) }
            } ?: return state
        return state.copy(
            active = entered.first,
            exitGate = entered.second,
            entryMs = nowMs,
            accMeters = 0.0,
            last = at,
            reading = Reading(null, entered.first.maxspeedKmh),
        )
    }

    private fun advance(
        state: State,
        current: SpeedCameras.Section,
        at: LatLon,
        nowMs: Long,
    ): State {
        val accMeters = state.accMeters +
            (state.last?.let { RoadRoulette.distanceMeters(it, at) } ?: 0.0)
        val elapsedHours = (nowMs - state.entryMs) / 3_600_000.0
        val reading =
            if (elapsedHours > 0 && accMeters > MIN_ACC_METERS_FOR_AVERAGE) {
                state.reading.copy(averageKmh = (accMeters / 1000.0) / elapsedHours)
            } else {
                state.reading
            }
        // Only the end we drove in towards ends the measurement. The
        // 150 m floor keeps the gate we entered through from counting as
        // the exit on the fix right after entering.
        val reachedEnd = accMeters > MIN_ACC_METERS_BEFORE_EXIT &&
            state.exitGate.any { RoadRoulette.distanceMeters(at, it) < SECTION_GATE_METERS }
        val overshot = accMeters > current.spanMeters * OVERSHOOT_FACTOR + OVERSHOOT_SLACK_METERS
        val timedOut = nowMs - state.entryMs > TIMEOUT_MS
        return if (reachedEnd || overshot || timedOut) {
            // accMeters is carried, not zeroed: the inline version did not zero
            // it either, and arming overwrites it.
            state.copy(
                active = null,
                exitGate = emptyList(),
                accMeters = accMeters,
                last = null,
                reading = Reading(null, null),
            )
        } else {
            state.copy(accMeters = accMeters, last = at, reading = reading)
        }
    }

    /**
     * The far end of [section], if this fix is entering it: within the gate of one
     * end and heading towards the other. Null otherwise.
     *
     * The heading test is what makes the gate mean "driving the section". Passing a
     * device node says nothing on its own — you pass one on the way *out* too, and
     * on every side street that crosses one — and matching on that alone used to
     * start a measurement as you left a section, which is what put an average on
     * screen after the trajectcontrole instead of during it.
     */
    internal fun sectionExitGate(
        section: SpeedCameras.Section,
        pos: LatLon,
        headingDeg: Double,
    ): List<LatLon>? {
        fun atGate(end: List<LatLon>) =
            end.any { RoadRoulette.distanceMeters(pos, it) < SECTION_GATE_METERS }
        fun ahead(end: List<LatLon>) =
            end.any { RoadRoulette.withinWedge(pos, it, headingDeg, SECTION_WEDGE_DEG) }
        return when {
            atGate(section.endA) && ahead(section.endB) -> section.endB
            atGate(section.endB) && ahead(section.endA) -> section.endA
            else -> null
        }
    }
}
```

Four points that are the whole review of this file:

1. **The constant names keep their `SECTION_` prefix.** `SectionAverageTracker.SECTION_GATE_METERS`
   stutters, and renaming would be free (both references are inside code this commit moves or
   deletes) — and it is still not done, because "constants copied byte for byte" is the
   instruction and an unchanged name is what makes `git log -C` and `git blame -C` carry the
   comment's attribution across the module boundary.
2. **`sectionExitGate` is `internal`, the object and everything else is public.** `internal`
   inside `:shared` reaches `commonTest` (proven in-tree: `spokenDistance` at
   `NavAnnouncer.kt:129` is `internal` and `NavAnnouncerTest.kt:106` calls it), so its own tests
   work, while `app/` cannot call it — which is correct, because nothing in `app/` should.
3. **The exit branch keeps `accMeters` and nulls `last`**, matching `MapScreen.kt:1023-1027`
   exactly. Zeroing `accMeters` would be invisible in a diff and is not what the screen does.
4. **`reading` is computed before the exit test and discarded by it**, matching the inline
   order at `:1012-1027`: the screen writes `sectionAvgKmh` and then nulls it in the same
   collector body. There is no suspension point between the two writes, so no recomposition can
   observe the intermediate value — the net step is identical.

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :shared:testDebugUnitTest --tests 'com.jellemax.detour.drive.SectionAverageTrackerTest'
```
Expected: `BUILD SUCCESSFUL`, 12 tests. Run the metadata compile too, not just the test —
`commonMain` compiles happily against the Android target with a stray `java.*` import and fails
only on the iOS targets (`detour-shared-core` §7).

If `theHundredAndFiftyMetreFloorStopsAnImmediateExit` fails, a comparison was widened or the
floor was dropped: fix the code, not the test. If `nearestMatchWinsWhenTwoSectionsShareAGateLocation`
fails, `minByOrNull` became `firstOrNull`.

- [ ] **Step 5: Commit the tests, alone**

```bash
git status --short   # expected: one new file under shared/src/commonTest/.../drive/
git add shared/src/commonTest/kotlin/com/jellemax/detour/drive/SectionAverageTrackerTest.kt
git commit
```
Subject: `test(drive): characterise the section average tracker`. Body as in
[Test-first ordering](#test-first-ordering-and-why-the-tests-commit-alone). **No trailers.**

> **Commit 1 does not compile on its own, by design.** The machine lands in commit 2. Do not
> "fix" it by folding the two together; the reviewable artifact is the transcription with
> nothing else in the diff.

- [ ] **Step 6: Rewrite the phone call site**

Re-derive the range first (Step 0.2). At `93515cc` it is `MapScreen.kt:970-1031`. Replace the
whole block — the four-line comment, `speedSectionsRef`, the effect and its five `var`s — with:

```kotlin
    // Average speed through a trajectcontrole: SectionAverageTracker's call now
    // (shared/…/drive/), where the gate rules, the eight thresholds and the
    // reasoning behind each live with their tests.
    val speedSectionsRef = rememberUpdatedState(speedSections)
    LaunchedEffect(Unit) {
        var st = SectionAverageTracker.State()
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            st = SectionAverageTracker.onFix(
                state = st,
                sections = speedSectionsRef.value,
                at = LatLon(fix.lat, fix.lon),
                headingDeg = fix.bearingDeg?.toDouble(),
                speedMps = fix.speedMps,
                nowMs = System.currentTimeMillis(),
            )
            // Two states, one assignment source: they can no longer disagree
            // across a recomposition. Collapsing them into one is stage 4's.
            sectionAvgKmh = st.reading.averageKmh
            sectionLimitKmh = st.reading.limitKmh
        }
    }
```

Add `import com.jellemax.detour.drive.SectionAverageTracker`. `sectionAvgKmh` and
`sectionLimitKmh` (`:255-256`) keep their `remember { mutableStateOf(...) }` declarations
unchanged, and `MapHud`'s call at `:1466-1467` keeps a zero-line diff.

Five equivalence points, because they are what a reviewer has to check:

1. **`st` is a coroutine-local `var`, not a `remember`.** The effect's key is `Unit`, so it
   never restarts and the local's lifetime is identical to the five `var`s it replaces. Adding
   a `remember` here would add a snapshot write per fix that the screen does not have today.
   (Machine 3 is the opposite case — see 3c Step 5.)
2. **The effect key list and the `rememberUpdatedState` are untouched.** `LaunchedEffect(Unit)`
   stays `Unit`; `speedSectionsRef` stays a live snapshot read of `speedSections`, read once
   per fix as before.
3. **The two state writes now happen on every fix, including skipped ones.** Today a fix that
   cannot arm hits `return@collect` and writes nothing. Now it writes an unchanged
   `st.reading`, i.e. assigns the same value. `mutableStateOf` uses structural equality by
   default, so an equal write does not invalidate and no recomposition is added. Same for an
   armed fix under the 20 m floor.
4. **`System.currentTimeMillis()` is read at the same point in the body** it was read at
   `:984`, before any branch. The machine gets the identical value.
5. **`fix.bearingDeg?.toDouble()` moves out of the arm branch into the argument list.** It is a
   pure read of an already-delivered `Fix`, with no suspension between, and the `speedMps > 2.0`
   gate that used to be fused to it via `takeIf` now lives inside `arm`, tested by
   `doesNotArmAtOrBelowTheBearingNoiseFloor`.

- [ ] **Step 7: Delete `sectionExitGate` and its constants from `MapCameraTuning.kt`**

Delete `MapCameraTuning.kt:67-100` — the two comment/constant pairs and the function with its
KDoc, 34 lines, taking the file from 100 to 66. `CAM_RESUME_SPEED_MPS`, `CAM_RESUME_QUIET_MS`,
`CIRCLE_FIX_POLL_MS` and `smoothBearing` stay. Confirm nothing else referenced the three:

```bash
grep -rn 'sectionExitGate\|SECTION_GATE_METERS\|SECTION_WEDGE_DEG' app/ wear/ iosApp/ tools/ --include='*.kt' --include='*.swift'
```
Expected: **no hits in `app/`**. At `93515cc` the only references were `MapCameraTuning.kt:92`,
`:94` (inside the moved function) and `MapScreen.kt:995`, `:1019` (inside the moved effect).
Hits in `app/build/outputs/mapping/` are stale R8 artifacts, not references.

- [ ] **Step 8: Compile, test, commit**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh 93515cc
git diff --stat
```
Expected: exactly three files — `drive/SectionAverageTracker.kt` (new),
`ui/MapScreen.kt`, `ui/MapCameraTuning.kt`. **No file under `car/` may appear.** If one does,
split the commit.

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/SectionAverageTracker.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/MapCameraTuning.kt
git commit -m "refactor(drive): move the section average tracker to shared"
```

- [ ] **Step 9: REPLAY GATE — route (i), and record the comparison**

**Do not start 3b until this is done and written down.** Follow `detour-gps-replay`'s A/B
protocol; the capture recipe is `tools/mocklocation/baseline/README.md:90-105`.

**Fixture: `tools/mocklocation/routes/public-trajectcontrole.txt`**, and **record the A side
yourself** — there is no baseline for it. The public file is preferred here per the repo's
public-data rule and because its expected average is an *input* rather than a measurement:
`routes/README.md:71` puts the west gate at **line 170**, the east at **line 457**, behind a
4.72 km / 170 s lead-in that exists so the Overpass prefetch can land before the gate, and a
correct average settles at **100.0 km/h**.

The three quantities, named before looking:

| Quantity | Expected |
|---|---|
| Fix index of the first `AVG-ON` | at or just after the west gate, line 170 |
| Fix index of `AVG-CLEARED` | at or just after the east gate, line 457 |
| Settled `averageKmh` | **100.0 km/h** |

Anything else is a defect, not a baseline. Then cross-check against the run that *does* have a
baseline, `trajectcontrole-a90c3df-events.tsv` on the personal `trajectcontrole.txt`, whose
four AVG events are all explained:

| Fix | Event | Read | Why |
|---|---|---|---|
| **166** | `AVG-ON` | `Ø 115` | first fix inside the west gate's 60 m radius; zero latency, the first prefetch already held the section |
| **543** | `AVG-CLEARED` | last read `Ø 75` | `reachedEnd`; `accMeters` **7 946 m** of a **7 950 m** span |
| **546** | `AVG-ON` | `Ø 38` | re-armed into relation 15685856 over the shared node |
| **804** | `AVG-CLEARED` | — | `overshot` |

Convergence on the first transit was `Ø 115` → 84 → 78 → **`Ø 75`** against the route's own
75.4 km/h. Four AVG events in 1 765 frames, and **all four must still be four**, at the same
indices ±3 fixes (the replay's own timing jitter, quantified at
`baseline/README.md:220-228`).

**What this replay cannot reach, and must not be claimed:** the posted-limit comparison and the
red chip (neither E40 relation tags `maxspeed`, so `Reading.limitKmh` is null for the whole
transit), `timedOut`, and `reachedEnd` at a far gantry entered from the near end — on this
route the exit gate *is* the next section's entry. Those three are covered by
`SectionAverageTrackerTest` only, and saying so is part of reporting the stage.

**Write the comparison into `tools/mocklocation/baseline/`** as
`public-trajectcontrole-<sha>-events.tsv` plus a paragraph in that directory's `README.md`. A
replay whose A side you cannot reproduce is worth nothing.

---

## Task 3b: `CameraWarner` — the one-chime-per-camera latch

**Commits 3, 4 and 5.** Second because the latch is the whole machine and there is very little
else in it.

**Files:**
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/CameraWarnerTest.kt`
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/CameraWarner.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (`:913-968`)
- Modify (commit 5 only): `app/src/main/java/com/jellemax/detour/car/NavScreen.kt`
  (`:380-399`, `:127`)

> ### No `nowMs`, and that is a finding rather than an omission
>
> The latch is `ahead.at != warnedAt` — a **position** compared against a position
> (`MapScreen.kt:946`, `car/NavScreen.kt:390`), cleared when no camera is in range (`:936`,
> `:385`). Nothing in this machine measures an interval, so there is no timestamp to inject and
> no cooldown to test. A reviewer looking for the injected clock will not find one; this is
> why. Injecting nothing is the strongest form of the "inject time" constraint, and noticing
> that is part of the work.

> ### Do not schedule §B1's fix — it has already landed
>
> The spec's Consumed decisions section says *"§B1's fix comes first, in its own commit"* and
> cites `ambientSpeedLimitKmh` as never reset. It **is** reset: `MapScreen.kt:791-803`, a
> `LaunchedEffect(navigating)` clearing both the limit and the misses counter, with a comment at
> `:799` crediting `car/SpinScreen.kt:117-121` as the source. It landed in **`bac833a`** and
> `15-divergence-register.md:1867` records it `RESOLVED`. The spec's own Work items section
> carries this correction. Entry 1's substance is unaffected: the phone's fallback is what
> survives, and the car still has no ambient sign to fall back on.

- [ ] **Step 1: Write the failing test first**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/CameraWarnerTest.kt`:

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Characterises [CameraWarner] - the one-chime-per-camera latch and its re-arm
 * rule - transcribed from `ui/MapScreen.kt`'s camera `LaunchedEffect` and
 * `car/NavScreen.kt`'s `checkCameras` before either was repointed. The two copies
 * agreed on every threshold and on the latch; they differed only in how the limit
 * was resolved and in delivery, both of which stay at the call site.
 *
 * The failure this guards against is a warning that fires every second for the
 * same camera, or one that never re-arms for the next. Neither is visible in a
 * compiler diff and both are only noticeable while riding.
 *
 * **There is no clock in this machine.** The latch is positional -
 * `ahead.at != warnedAt`, cleared when nothing is in range - so there is no
 * cooldown, no timestamp, and nothing here needs an injected time.
 *
 * No Android APIs: runs on JVM and Kotlin/Native both.
 */
class CameraWarnerTest {

    private val here = LatLon(50.85, 4.35)

    /** [meters] from [here] along compass [bearingDeg]. `RoadRoulette.offset`
     *  takes radians; a point placed at bearing `b` reads back as exactly `b`,
     *  because `offset` and `RoadRoulette.bearingDeg` share the same flat
     *  projection - which is what makes the wedge boundary test below exact
     *  rather than flaky. */
    private fun cam(meters: Double, bearingDeg: Double) =
        SpeedCameras.Camera(RoadRoulette.offset(here, meters, bearingDeg * PI / 180.0))

    /** Due north, comfortably inside `SpeedCameras.WARN_METERS` (400.0). */
    private val ahead = cam(200.0, 0.0)

    private fun step(
        state: CameraWarner.State = CameraWarner.State(),
        cameras: List<SpeedCameras.Camera> = listOf(ahead),
        headingDeg: Double? = 0.0,
        speedKmh: Double = 130.0,
        limitKmh: Double? = 120.0,
    ) = CameraWarner.onFix(
        state = state, cameras = cameras, at = here,
        headingDeg = headingDeg, speedKmh = speedKmh, limitKmh = limitKmh,
    )

    /** "Silent when the limit is unknown: we can't judge 'too fast'"
     *  (`MapScreen.kt:916`). At any speed - an untagged road is not a licence to
     *  chime at everyone. */
    @Test
    fun silentWhenTheLimitIsUnknownAtAnySpeed() {
        assertEquals(CameraWarner.Outcome.Silent, step(limitKmh = null).outcome)
        assertEquals(CameraWarner.Outcome.Silent, step(limitKmh = null, speedKmh = 250.0).outcome)
    }

    @Test
    fun silentAtOrUnderTheLimitAndWarnsOncePastTheMargin() {
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 100.0).outcome)
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 120.0).outcome)
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(speedKmh = 120.0 + CameraWarner.OVER_LIMIT_KMH + 0.01).outcome,
        )
    }

    /** The boundary, stated: exactly `limit + OVER_LIMIT_KMH` does **not** warn,
     *  because the test is `>`. */
    @Test
    fun exactlyTheOverLimitMarginDoesNotWarn() {
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(speedKmh = 120.0 + CameraWarner.OVER_LIMIT_KMH).outcome,
        )
    }

    /** One warning per camera. The state carries the latch, so a second fix at
     *  the same camera - still too fast - is silent and changes nothing. */
    @Test
    fun oneWarningPerCamera() {
        val first = step()
        assertEquals(CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"), first.outcome)
        assertEquals(ahead.at, first.state.warnedAt)

        val second = step(state = first.state)
        assertEquals(CameraWarner.Outcome.Silent, second.outcome)
        assertEquals(first.state, second.state)
    }

    /** Re-arms once the camera leaves range: nothing in range clears the latch,
     *  so the next camera chimes. This is what makes the latch per-camera rather
     *  than a permanent mute. */
    @Test
    fun theLatchClearsWhenNothingIsInRange() {
        val latched = step().state
        val empty = step(state = latched, cameras = emptyList())
        assertEquals(CameraWarner.Outcome.Silent, empty.outcome)
        assertNull(empty.state.warnedAt)
    }

    /** Beyond `SpeedCameras.WARN_METERS` (400.0, `SpeedCameras.kt:53`) nothing is
     *  a candidate. 405 m of `offset` measures 405.5 m, 395 m measures 395.4 m -
     *  the margin is the two helpers' 1.001125 projection mismatch, and the
     *  comparison itself is `<=`. */
    @Test
    fun beyondTheWarnRadiusNothingIsACandidate() {
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(cam(405.0, 0.0))).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Warn(cam(395.0, 0.0).at, "Speed camera ahead"),
            step(cameras = listOf(cam(395.0, 0.0))).outcome,
        )
    }

    /** A camera behind you is not ahead of you. The boundary, stated: exactly
     *  [CameraWarner.AHEAD_WEDGE_DEG] **is** inside the wedge, because
     *  `RoadRoulette.withinWedge` compares `<=`. Exact rather than approximate:
     *  the camera is placed due north, whose bearing reads back as exactly 0.0,
     *  so the difference against a heading of 45.0 is exactly 45.0. */
    @Test
    fun theWedgeRejectsACameraBehindYouAndIncludesItsOwnBoundary() {
        assertEquals(CameraWarner.Outcome.Silent, step(headingDeg = 180.0).outcome)
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(headingDeg = CameraWarner.AHEAD_WEDGE_DEG).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(headingDeg = CameraWarner.AHEAD_WEDGE_DEG + 0.1).outcome,
        )
    }

    /** A null heading skips the wedge entirely and warns on distance alone
     *  (`MapScreen.kt:932-933`) - the stopped-phone case, and the one branch no
     *  replay reaches because the mock provider always derives a bearing. */
    @Test
    fun aNullHeadingWarnsOnDistanceAlone() {
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(headingDeg = null).outcome,
        )
        // Even one directly behind: with no heading there is no "behind".
        val behind = cam(200.0, 180.0)
        assertEquals(
            CameraWarner.Outcome.Warn(behind.at, "Speed camera ahead"),
            step(cameras = listOf(behind), headingDeg = null).outcome,
        )
    }

    /** Nearest camera wins when two are in range (`MapScreen.kt:934`), whatever
     *  order the prefetch returned them in. */
    @Test
    fun theNearestCameraInRangeWins() {
        val near = cam(120.0, 0.0)
        val far = cam(380.0, 0.0)
        assertEquals(
            CameraWarner.Outcome.Warn(near.at, "Speed camera ahead"),
            step(cameras = listOf(far, near)).outcome,
        )
    }

    /** A *different*, nearer camera appearing while still latched on the first
     *  one does warn: the latch is per-camera, not a global mute. This is the
     *  case a naive "warn once" would silently break. */
    @Test
    fun aNearerSecondCameraWarnsWhileStillLatchedOnTheFirst() {
        val far = cam(380.0, 0.0)
        val near = cam(120.0, 0.0)
        val latched = step(cameras = listOf(far)).state
        assertEquals(far.at, latched.warnedAt)

        val next = step(state = latched, cameras = listOf(far, near))
        assertEquals(CameraWarner.Outcome.Warn(near.at, "Speed camera ahead"), next.outcome)
        assertEquals(near.at, next.state.warnedAt)
    }

    /** Being in range but not too fast does **not** clear the latch: only nothing
     *  in range does. Pinned because "clear it whenever we don't warn" is the
     *  tempting simplification, and it would re-chime for the same camera as soon
     *  as you crept back over the limit. */
    @Test
    fun slowingDownInRangeDoesNotClearTheLatch() {
        val latched = step().state
        val slowed = step(state = latched, speedKmh = 100.0)
        assertEquals(CameraWarner.Outcome.Silent, slowed.outcome)
        assertSame(latched.warnedAt, slowed.state.warnedAt)
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :shared:testDebugUnitTest --tests 'com.jellemax.detour.drive.CameraWarnerTest'
```
Expected: unresolved reference `CameraWarner`.

- [ ] **Step 3: Create `CameraWarner.kt`**

Both literals are **hoisted, not re-declared**: `AHEAD_WEDGE_DEG` replaces the `45.0` at
`MapScreen.kt:933` and `car/NavScreen.kt:382`; `OVER_LIMIT_KMH` replaces the `+ 3.0` at
`MapScreen.kt:945` and `car/NavScreen.kt:389`. Entry 13 exists to prevent a third copy inside
the machine while two call-site literals survive.

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras

/**
 * Whether a speed camera ahead is worth interrupting for, and the wording if it
 * is. One chime per camera: [State.warnedAt] holds the camera last sounded for
 * and clears once nothing is in range, re-arming for the next.
 *
 * **Decision and wording here, delivery per platform** - the `CircleEvents.kt`
 * shape. This machine knows nothing about tones, speech or toasts, which is why
 * it returns a [Step] rather than taking a callback: the phone chimes and speaks,
 * the head unit chimes, speaks and shows a car toast, and iOS will do whatever
 * iOS does, without any of that leaking into a shared decision.
 *
 * **No clock.** The latch is positional, not temporal: there is no cooldown, and
 * nothing here measures an interval.
 *
 * The posted limit is resolved by the caller, deliberately. The phone passes
 * `navProgress?.speedLimitKmh ?: ambientSpeedLimitKmh` because it has an ambient
 * sign to fall back on; the head unit passes the route's limit alone because it
 * does not. "Does this surface have an ambient sign" is a per-surface fact, and
 * keeping it at the call site is what stops it becoming a branch in here.
 */
object CameraWarner {

    /** How far off the heading a camera may lie and still count as ahead. */
    const val AHEAD_WEDGE_DEG = 45.0

    /** Over the posted limit by this much before a camera is worth interrupting
     *  for. Under it you are not the driver the camera is about to photograph. */
    const val OVER_LIMIT_KMH = 3.0

    /** [warnedAt] is the camera last sounded for, or null when nothing is in
     *  range. A position, not a timestamp - see the class KDoc. */
    data class State(val warnedAt: LatLon? = null)

    sealed interface Outcome {
        data object Silent : Outcome

        /** [text] is the wording. Delivery — tone, speech, toast — is the caller's. */
        data class Warn(val at: LatLon, val text: String) : Outcome
    }

    data class Step(val state: State, val outcome: Outcome)

    /**
     * One GPS fix. [cameras] is whatever the caller's prefetch holds; [speedKmh]
     * and [limitKmh] are both km/h, and a null [limitKmh] means the limit here is
     * unknown - we can't judge "too fast", so nothing is worth interrupting for.
     */
    fun onFix(
        state: State,
        cameras: List<SpeedCameras.Camera>,
        at: LatLon,
        headingDeg: Double?,
        speedKmh: Double,
        limitKmh: Double?,
    ): Step {
        val ahead = cameras.filter { cam ->
            RoadRoulette.distanceMeters(at, cam.at) <= SpeedCameras.WARN_METERS &&
                (headingDeg == null ||
                    RoadRoulette.withinWedge(at, cam.at, headingDeg, AHEAD_WEDGE_DEG))
        }.minByOrNull { RoadRoulette.distanceMeters(at, it.at) }
            // Nothing in range clears the latch, which is what re-arms it for the
            // next camera. Being in range and *not* too fast does not.
            ?: return Step(State(warnedAt = null), Outcome.Silent)

        val tooFast = limitKmh != null && speedKmh > limitKmh + OVER_LIMIT_KMH
        if (!tooFast || ahead.at == state.warnedAt) return Step(state, Outcome.Silent)
        return Step(State(warnedAt = ahead.at), Outcome.Warn(ahead.at, WARNING_TEXT))
    }

    /** The wording, declared once for every surface. The phone's own comment said
     *  this literal was waiting for this machine to own it. */
    private const val WARNING_TEXT = "Speed camera ahead"
}
```

Equivalence: `filter { … } .minByOrNull { … }`, the `<=` on `WARN_METERS`, the `45.0` wedge, the
`>` on `limit + 3.0` and the `ahead.at != warnedAt` latch are byte-for-byte the phone's
(`:930-946`) and the car's (`:380-390`). The only restructuring is `if (ahead == null) { clear;
return }` becoming an `?: return`, and the positive `if (tooFast && changed)` becoming an early
`return` on its negation — both mechanical.

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :shared:testDebugUnitTest --tests 'com.jellemax.detour.drive.CameraWarnerTest'
```
Expected: `BUILD SUCCESSFUL`, 11 tests.

- [ ] **Step 5: Commit the tests, alone**

```bash
git add shared/src/commonTest/kotlin/com/jellemax/detour/drive/CameraWarnerTest.kt
git commit -m "test(drive): characterise the camera warner"
```

- [ ] **Step 6: Rewrite the phone call site**

Re-derive the range. At `93515cc` it is `MapScreen.kt:913-968`. The three
`rememberUpdatedState` snapshots (`:917-919`), the `toneGen` (`:920-922`) and the
`DisposableEffect` (`:923`) **all stay exactly as they are**. Replace the four-line comment at
`:913-916` and the effect body at `:924-968` with:

```kotlin
    // Chime when a camera lies ahead, close, and we're over the posted limit —
    // the one case worth interrupting for. The rule, the latch and the wording
    // are CameraWarner's (shared/…/drive/); what to do about a warning is ours.
    val speedCamerasRef = rememberUpdatedState(speedCameras)
    val ambientLimitRef = rememberUpdatedState(ambientSpeedLimitKmh)
    val navProgressRef = rememberUpdatedState(navProgress)
    val toneGen = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { toneGen?.release() } }
    LaunchedEffect(Unit) {
        var st = CameraWarner.State()
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            // The ambient sign is the free-drive source. While navigating, the
            // route's own posted limit is the authority and the ambient tracker
            // is stopped — and cleared — so a route segment with no maxspeed
            // judges you against nothing instead of against the sign from
            // wherever you set off.
            val step = CameraWarner.onFix(
                state = st,
                cameras = speedCamerasRef.value,
                at = LatLon(fix.lat, fix.lon),
                headingDeg = fix.bearingDeg?.toDouble(),
                speedKmh = fix.speedMps * 3.6,
                limitKmh = navProgressRef.value?.speedLimitKmh ?: ambientLimitRef.value,
            )
            st = step.state
            when (val outcome = step.outcome) {
                is CameraWarner.Outcome.Warn -> {
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                    // A TONE_PROP_BEEP2 on the notification stream is inaudible on
                    // a bar mount with earplugs in and wind noise — which is this
                    // app's primary configuration. The head unit has spoken this
                    // since it shipped and its comment says why
                    // (car/NavScreen.kt:392-394). Register entry 15.
                    //
                    // No toast: the car's stands in for a visual the head unit has
                    // no room for, and the phone's map already draws the camera
                    // marker. The snackbarHostState this screen already owns is the
                    // error channel; routing a routine hazard through it would
                    // teach the rider to ignore errors.
                    announceAloud(outcome.text)
                }
                CameraWarner.Outcome.Silent -> {}
            }
        }
    }
```

Add `import com.jellemax.detour.drive.CameraWarner`.

Comment bookkeeping, since this is the one call site where a comment splits in two:

- `:960-963` (*"The wording is a literal here and not in `:shared` because stage 3's
  `CameraWarner` is where the warning decision and its text belong"*) is **resolved and
  deleted**. Its content is now `CameraWarner.WARNING_TEXT`'s KDoc. Do not leave a stale copy.
- `:948-958` (why speech and not just a tone, why no toast) is **delivery**, stays here
  verbatim, and moves inside the `Warn` arm.
- `:939-943` (why the ambient fallback and how navigating changes the authority) explains the
  argument being passed, so it moves to just above the `onFix` call.
- `:913-916`'s latch explanation **moves** into `CameraWarner`'s KDoc; the call site keeps the
  three-line pointer above.

Equivalence: `fix.speedMps * 3.6` is computed at the call site exactly as at `:945`, so the
comparison arithmetic is unchanged. `announceAloud` (`:711`) is untouched. The effect key list
is untouched. No `rememberUpdatedState` is added or removed.

- [ ] **Step 7: Compile, test, commit — car untouched**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
git diff --stat
```
Expected: exactly two files — `drive/CameraWarner.kt` (new) and `ui/MapScreen.kt`.
**`car/NavScreen.kt` must not appear.** If it does, split the commit.

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/CameraWarner.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(drive): move the camera warner to shared"
```

---

### Task 3b′: delete the car's copy of the warner

**Commit 5, one commit behind 3b, never sharing one with it.**

**Files:** `app/src/main/java/com/jellemax/detour/car/NavScreen.kt` only.

- [ ] **Step 1: Replace the warning half of `checkCameras`**

At `93515cc`, `checkCameras` is `NavScreen.kt:361-400`: the KDoc at `:350-360`, the prefetch
half at `:362-379`, the warning half at `:380-399`. **Replace `:380-399` only.** The prefetch
half is *not* touched — it is the better copy and it is machine 3's family, not machine 2's.

```kotlin
        val step = CameraWarner.onFix(
            state = warnerState,
            cameras = speedCameras,
            at = pos,
            headingDeg = headingDeg,
            speedKmh = currentSpeedKmh,
            // No ambient sign on this screen to fall back on: NavScreen has no
            // speed-limit tracker of its own, so an untagged route segment judges
            // you against nothing. The phone falls back to its ambient sign.
            limitKmh = progress?.speedLimitKmh,
        )
        warnerState = step.state
        when (val outcome = step.outcome) {
            is CameraWarner.Outcome.Warn -> {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                // The toast is on the car screen and the tone is on the phone's
                // notification stream; only the spoken one reaches a driver who is
                // looking at the road with the radio on.
                speak(outcome.text)
                carContext.getCarService(AppManager::class.java)
                    .showToast(outcome.text, CarToast.LENGTH_SHORT)
            }
            CameraWarner.Outcome.Silent -> {}
        }
```

Add `import com.jellemax.detour.drive.CameraWarner`.

- [ ] **Step 2: Replace the latch field**

`NavScreen.kt:127` — `private var warnedCameraAt: LatLon? = null` — becomes:

```kotlin
    /** The one-chime-per-camera latch, CameraWarner's. */
    private var warnerState = CameraWarner.State()
```

Nothing else on this screen referenced it: at `93515cc` the only uses were `:385`, `:390` and
`:398`, all inside the block replaced above.

```bash
grep -rn 'warnedCameraAt' app/
```
Expected: no hits.

- [ ] **Step 3: Confirm both literals are gone from both surfaces**

```bash
grep -rn 'withinWedge(.*45\.0\|+ 3\.0' app/src/main/java/com/jellemax/detour/ui app/src/main/java/com/jellemax/detour/car
```
Expected: no hits for these two uses (entry 13's acceptance check). **The HUD's `+ 5` is not
this machine's and must still be there** — `MapHud.kt:184`, `car/CarMapRenderer.kt:635` and
`wear/…/MainActivity.kt:140`, all three agreeing. `wear/` does not depend on `:shared`
(`detour-shared-core` §1), so that literal cannot go to commonMain without stranding the
watch's copy. It stays in `app/`. Do not touch it.

- [ ] **Step 4: Compile, test, commit**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
git diff --stat   # expected: car/NavScreen.kt only
git add app/src/main/java/com/jellemax/detour/car/NavScreen.kt
git commit -m "refactor(car): use the shared CameraWarner instead of a second copy"
```

**`README.md:383-385` is not edited here**, and not in commit 8 either — see
[Correction 3](#correction-3-commit-8-cannot-give-the-car-its-ambient-fallback).

- [ ] **Step 5: REPLAY GATE — route (i), and record the comparison**

**Do not start 3c until this is done and written down.**

**Fixture: the personal `tools/mocklocation/routes/trajectcontrole.txt`** — and this is a
deliberate exception to preferring public data, stated so nobody "corrects" it.
`routes/README.md` measures **5** `highway=speed_camera` nodes inside `WARN_METERS` of that
line and reports the equivalent column for the `public-` files as *"not reported rather than
guessed"*, because Overpass 504'd when it was written. A camera-warning A/B against a fixture
whose camera count is unknown compares nothing.

The quantity: **the number of `"Speed camera ahead"` announcements and the fix index of each**,
against the stage-0 baseline. Read them out of the logcat capture rather than the screenshots —
the chime has no on-screen state.

If Overpass is answering when this item starts, run `routes/README.md`'s stated bbox for
`public-trajectcontrole` (4.4286,50.8587 → 4.6050,50.8693) to establish its camera count, and
switch to the public file. Record which one you used.

**One branch no replay reaches:** `aNullHeadingWarnsOnDistanceAlone`. The mock provider always
derives a bearing, so the stopped-phone path is covered by that unit test only.

---

## Task 3c: `SpeedLimitTracker` — prefetch, snap and the 3-miss clear

**Commits 6, 7 and 8.** Last, because it is the only one of the three whose I/O has to be split
out, and the only one carrying a known tuning trap.

**Files:**
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/SpeedLimitTrackerTest.kt`
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/SpeedLimitTracker.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` (`:785-835`, `:244-250`)
- Modify (commit 8 only): `app/src/main/java/com/jellemax/detour/car/SpinScreen.kt`

**Extract from the car's copy** (`SpinScreen.kt:265-296`), per the spec's Constraints section
and `detour-shared-core` §6, with the phone's (`MapScreen.kt:791-835`) **diffed** against it
rather than transcribed. The car's is strictly better: named constants, and the fetch already
off the collector.

> ### Step 0 again, and harder, for this item
>
> Stage 0's task **0d** — moving the phone's Overpass fetch off the fix collector — was being
> written in a concurrent session while this plan was being written, and it edits **exactly the
> effect this item edits**. Before writing a line of the call site:
>
> ```bash
> git log --oneline -5 -- app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
> grep -n 'LaunchedEffect(navigating)' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
> sed -n '<start>,<end>p' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
> ```
>
> **Transcribe from what is there, not from this document.** Two things specifically:
>
> 1. **Whether the fetch is still `withContext(Dispatchers.IO) { … }` inline** (as at
>    `93515cc:816`) or is now a `scope.launch { … }` job. Whichever it is, **keep it verbatim**
>    — only the state reads and writes around it change in this commit.
> 2. **Whether the phone has gained an in-flight guard.** If 0d added one, keep it. If it did
>    not, **3c must not add one**. `needsWays` takes no guard and never will: the car's
>    `limitFetchJob?.isActive != true` is a `Job`, an Android coroutine handle, and it cannot
>    cross into commonMain. Extracting *the throttle* and leaving *the in-flight guard* at the
>    call site is exactly what "I/O must be handed in" implies.
>
> **And the hysteresis trap, restated as an instruction.** The 3-miss clear rule was only ever
> tuned against a fix stream that *had* the drops the inline `withContext` causes. The measured
> baseline is **3 fixes ≈ 3.1 s** from last successful snap to sign clear, seen twice
> independently (`stop-start` 470→473, `urban-limits` run 2 609→612;
> `baseline/README.md:273-281`), and that figure is **0d's** acceptance criterion, not this
> item's. So:
>
> - If 0d has landed, **A/B machine 3 against 0d's own post-change capture**, not against
>   `…-09fddde-…`. Comparing a post-0d extraction to a pre-0d baseline mixes two changes into
>   one number.
> - Do not retune the `3` here under any circumstances. Extract, compare fixes-to-clear, and if
>   it moved, that is 0d's finding to act on with the machine already under test.

> ### Entry 18 is a constraint on `State`, and the shape enforces it
>
> `limitKmh` answers *does a posted limit exist here*. There is no `visible` field, no
> `shouldShowSign`, and nothing about a standstill. The phone fades its HUD at rest and the head
> unit does not; both are defensible and neither is this machine's call. If a later change wants
> a sign to disappear, it changes the readout, not this tracker. A tracker that starts emitting
> "no limit" in order to make a sign vanish has decided entry 18 by accident, inside a refactor.

- [ ] **Step 1: Write the failing test first**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/drive/SpeedLimitTrackerTest.kt`:

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterises [SpeedLimitTracker] - the ambient speed-limit sign's prefetch
 * throttle, its local snap and its three-miss clear hysteresis - transcribed from
 * `car/SpinScreen.kt`'s `updateSpeedLimit`, which is the better of the two copies
 * (named constants, fetch off the collector: `detour-shared-core` §6), with the
 * phone's `ui/MapScreen.kt` version diffed against it.
 *
 * Four functions rather than one because the fetch cannot come along: commonMain
 * has no `Dispatchers.*`, and a suspending `onFix` taking a fetcher lambda would
 * be commonMain's first interface wearing a function type. So "should I fetch",
 * "I have started fetching", "here is what came back" and "here is a fix" are
 * four calls, and the ordering between the first two is load-bearing - see
 * [theThrottleIsStampedOnAttemptNotOnCompletion].
 *
 * The failure this guards against is a sign that flickers off on one untagged
 * fix, or one that never clears when the limit really ended. Both are only
 * visible while riding.
 *
 * No Android APIs and no clock: runs on JVM and Kotlin/Native both.
 */
class SpeedLimitTrackerTest {

    private val t0 = 1_700_000_000_000L
    private val here = LatLon(50.85, 4.35)

    private fun at(from: LatLon, meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(from, meters, bearingDeg * PI / 180.0)

    /** A north-south way running through [here], so `distanceToSegmentMeters` is
     *  ~0 and it aligns with a heading of 0deg. */
    private val alongWay = RoadRoulette.SpeedLimitWay(
        kmh = 70.0,
        points = listOf(at(here, 400.0, 180.0), at(here, 400.0, 0.0)),
    )

    /** An east-west way 5 m north of [here]: nearer than [alongWay] but 90deg off a
     *  heading of 0deg, so `snapSpeedLimitKmh`'s alignment test must reject it. */
    private val crossingWay = RoadRoulette.SpeedLimitWay(
        kmh = 30.0,
        points = listOf(
            at(at(here, 5.0, 0.0), 400.0, 270.0),
            at(at(here, 5.0, 0.0), 400.0, 90.0),
        ),
    )

    /** Held ways, an established sign, nothing pending. */
    private fun holding() = SpeedLimitTracker.State(
        ways = listOf(alongWay),
        waysCenter = here,
        lastFetchMs = t0,
        misses = 0,
        limitKmh = 70.0,
    )

    /** Far enough from [alongWay] that the snap finds nothing within
     *  `MAX_SNAP_METERS` (25 m) - a miss, with the held set still held. */
    private val offTheWay = at(here, 5_000.0, 90.0)

    private fun fix(state: SpeedLimitTracker.State, at: LatLon, speedMps: Double = 20.0) =
        SpeedLimitTracker.onFix(state, at = at, headingDeg = 0.0, speedMps = speedMps)

    // ---- the snap and the hysteresis --------------------------------------

    @Test
    fun aSuccessfulSnapSetsTheLimitAndZeroesTheMisses() {
        val st = fix(holding().copy(limitKmh = null, misses = 2), here)
        assertEquals(70.0, st.limitKmh!!, 1e-9)
        assertEquals(0, st.misses)
    }

    /**
     * One miss keeps the sign, two keep it, **the third clears it** - and the
     * count is asserted, not just the end state. "A few misses in a row means the
     * limit really ended (or the road isn't tagged), not a one-fix gap."
     */
    @Test
    fun theThirdConsecutiveMissClearsTheSign() {
        var st = fix(holding(), offTheWay)
        assertEquals(1, st.misses)
        assertEquals(70.0, st.limitKmh!!, 1e-9)

        st = fix(st, offTheWay)
        assertEquals(2, st.misses)
        assertEquals(70.0, st.limitKmh!!, 1e-9)

        st = fix(st, offTheWay)
        assertEquals(SpeedLimitTracker.MISSES_TO_CLEAR, st.misses)
        assertNull(st.limitKmh)
    }

    @Test
    fun aFreshSnapAfterAClearReEstablishesTheSign() {
        var st = holding()
        repeat(3) { st = fix(st, offTheWay) }
        assertNull(st.limitKmh)

        st = fix(st, here)
        assertEquals(70.0, st.limitKmh!!, 1e-9)
        assertEquals(0, st.misses)
    }

    /**
     * Below [SpeedLimitTracker.MIN_MPS] the fix is skipped entirely and does
     * **not** count as a miss, so a long wait at a light cannot clear the sign.
     * Both copies return before the snap (`MapScreen.kt:807`,
     * `SpinScreen.kt:266`), and this is the single easiest thing to get wrong in
     * the move, because an early `return@collect` becomes an early `return state`
     * and it is invisible in a diff.
     *
     * The boundary, stated: exactly `MIN_MPS` is **not** skipped, because both
     * call sites spell it `if (speedMps < MIN_MPS) return`. Note that
     * [SectionAverageTracker.ARM_MIN_MPS] is the same literal 2.0 on the
     * *opposite* edge (`takeIf { speedMps > … }`). Neither is a typo to tidy.
     */
    @Test
    fun aFixBelowTheSpeedFloorIsSkippedAndIsNotAMiss() {
        val held = holding()
        val stopped = fix(held, offTheWay, speedMps = SpeedLimitTracker.MIN_MPS - 0.01)
        assertEquals(held, stopped)

        val crawling = fix(held, offTheWay, speedMps = SpeedLimitTracker.MIN_MPS)
        assertEquals(1, crawling.misses)
    }

    /** The snap is delegated to `RoadRoulette.snapSpeedLimitKmh`
     *  (`RoadRoulette.kt:295`), not reimplemented: a way aligned with the heading
     *  wins over a nearer crossing one, which is most of why the sign stopped
     *  showing the cross street's limit. */
    @Test
    fun theAlignedWayBeatsANearerCrossingOne() {
        val st = fix(holding().copy(ways = listOf(crossingWay, alongWay)), here)
        assertEquals(70.0, st.limitKmh!!, 1e-9)
    }

    // ---- the prefetch throttle -------------------------------------------

    /** `needsWays` is false inside `SPEED_PREFETCH_RADIUS_M - FETCH_MARGIN_M`
     *  (1500.0 - 500.0, `RoadRoulette.kt:255`) of what we hold, and true outside
     *  it. The comparison is `>`; the fixtures sit 100 m either side of 1 000 m
     *  because `offset` and `distanceMeters` disagree by 1.001125x. */
    @Test
    fun needsWaysOnlyOnceYouNearTheEdgeOfWhatYouHold() {
        val held = holding().copy(lastFetchMs = 0L)
        assertFalse(SpeedLimitTracker.needsWays(held, at(here, 900.0, 90.0), t0))
        assertTrue(SpeedLimitTracker.needsWays(held, at(here, 1_100.0, 90.0), t0))
    }

    /** False inside [SpeedLimitTracker.FETCH_THROTTLE_MS] of the last attempt
     *  however far you have moved. The boundary, stated: exactly the throttle does
     *  **not** fetch, because the test is `>`. */
    @Test
    fun theThrottleHoldsHoweverFarYouHaveMoved() {
        val held = holding()
        val faraway = at(here, 5_000.0, 90.0)
        assertFalse(SpeedLimitTracker.needsWays(held, faraway, t0 + 1))
        assertFalse(
            SpeedLimitTracker.needsWays(held, faraway, t0 + SpeedLimitTracker.FETCH_THROTTLE_MS),
        )
        assertTrue(
            SpeedLimitTracker.needsWays(held, faraway, t0 + SpeedLimitTracker.FETCH_THROTTLE_MS + 1),
        )
    }

    /** True on a virgin state: a null `waysCenter` must mean "no area held", not
     *  "distance zero". Both copies spell that `?: Double.MAX_VALUE`
     *  (`MapScreen.kt:809-810`, `SpinScreen.kt:267-268`). `nowMs` is epoch millis
     *  at every call site, so the zero `lastFetchMs` is never inside the
     *  throttle - the same shape as stage 2's
     *  `theFirstRerouteOfASessionIsNotBlocked`. */
    @Test
    fun aVirginStateNeedsWaysWhereverItIs() {
        assertTrue(SpeedLimitTracker.needsWays(SpeedLimitTracker.State(), here, t0))
    }

    /**
     * The stamp goes on the *attempt*, not the completion. Both copies stamp
     * before awaiting the network (`MapScreen.kt:815`, `SpinScreen.kt:276`) so a
     * failing mirror is throttled like a succeeding one - the car says so at
     * `:274-275`. Folding the stamp into `needsWays` would make a query function
     * mutate; folding it into `withWays` would stamp on completion and turn a
     * 30-second timeout into a 30-second-plus-ten gap.
     */
    @Test
    fun theThrottleIsStampedOnAttemptNotOnCompletion() {
        val virgin = SpeedLimitTracker.State()
        val started = SpeedLimitTracker.fetchStarted(virgin, t0)
        assertEquals(t0, started.lastFetchMs)
        // Nothing else moved: the stamp is not a place to sneak a clear into.
        assertEquals(virgin.copy(lastFetchMs = t0), started)
        // And a failed fetch is throttled exactly like a successful one.
        assertFalse(SpeedLimitTracker.needsWays(started, at(here, 9_000.0, 90.0), t0 + 5_000L))
    }

    /** An empty result is a network blip: keep what we hold rather than
     *  flickering the sign off, and do **not** move the centre - moving it would
     *  claim we hold an area we do not. */
    @Test
    fun anEmptyFetchResultIsANoOpOnBothWaysAndCentre() {
        val held = holding()
        val after = SpeedLimitTracker.withWays(held, emptyList(), at(here, 2_000.0, 90.0))
        assertSame(held.ways, after.ways)
        assertEquals(here, after.waysCenter)
        assertEquals(held, after)
    }

    @Test
    fun aNonEmptyFetchResultReplacesBothWaysAndCentre() {
        val moved = at(here, 2_000.0, 90.0)
        val after = SpeedLimitTracker.withWays(
            SpeedLimitTracker.State(), listOf(crossingWay), moved,
        )
        assertEquals(listOf(crossingWay), after.ways)
        assertEquals(moved, after.waysCenter)
    }

    // ---- crossing the navigation boundary ---------------------------------

    /**
     * `reset` clears the sign and the miss counter and **keeps the held area** -
     * `ways`, `waysCenter` and `lastFetchMs`. Two of five fields cleared, three
     * kept. Crossing into or out of navigation makes the derived *sign* stale, not
     * the prefetched geometry, and re-clearing `lastFetchMs` would let a
     * navigation toggle punch through the throttle.
     *
     * `bac833a` reset exactly these two on the phone, and this test is what stops
     * the extraction resetting five.
     */
    @Test
    fun resetClearsTheSignAndTheMissesAndKeepsTheHeldArea() {
        val st = SpeedLimitTracker.reset(holding().copy(misses = 2))
        assertNull(st.limitKmh)
        assertEquals(0, st.misses)
        assertEquals(listOf(alongWay), st.ways)
        assertEquals(here, st.waysCenter)
        assertEquals(t0, st.lastFetchMs)
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
./gradlew :shared:testDebugUnitTest --tests 'com.jellemax.detour.drive.SpeedLimitTrackerTest'
```
Expected: unresolved reference `SpeedLimitTracker`.

- [ ] **Step 3: Create `SpeedLimitTracker.kt`**

```kotlin
package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette

/**
 * The ambient speed-limit sign while just driving: one Overpass fetch covers a
 * wide circle, then every fix snaps locally against that set, so the sign flips
 * the instant you cross onto a new road instead of lagging a throttled
 * round-trip behind you. The fetch refreshes only as you near the edge of what
 * you hold, throttled on failure too so a network blip doesn't hammer the
 * mirrors.
 *
 * **Four functions, because the fetch cannot come along.** commonMain has no
 * `Dispatchers.*`, so the caller owns the I/O and the ordering is:
 *
 * ```
 * if (needsWays(st, pos, now)) {
 *     st = fetchStarted(st, now)          // stamp before awaiting anything
 *     <platform coroutine> { st = withWays(st, RoadRoulette.speedLimitWays(pos), pos) }
 * }
 * st = onFix(st, pos, heading, speedMps)
 * ```
 *
 * The in-flight guard stays at the call site: the head unit's is a `Job`, an
 * Android coroutine handle, which cannot cross into common code. The throttle is
 * shared; the guard is not.
 *
 * **This machine decides whether a posted limit *exists*, never whether a sign is
 * *shown*.** There is no `visible` field and nothing about a standstill: the
 * phone fades its HUD at rest, the head unit does not, and both are defensible.
 * A readout makes that call (register entry 18).
 */
object SpeedLimitTracker {

    /** Below this the heading is noise and you are probably parked, so the snap —
     *  which leans on heading to reject the cross street — is skipped. */
    const val MIN_MPS = 2.0

    /** Refetch once you are within this much of the edge of the area you hold. */
    const val FETCH_MARGIN_M = 500.0

    /** Minimum gap between fetch *attempts*. Stamped before the request, so a
     *  failing mirror is throttled like a succeeding one. */
    const val FETCH_THROTTLE_MS = 10_000L

    /** Misses in a row before the sign is cleared. One gap is an untagged
     *  stretch; three is the limit really having ended. */
    const val MISSES_TO_CLEAR = 3

    data class State(
        val ways: List<RoadRoulette.SpeedLimitWay> = emptyList(),
        val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L,
        val misses: Int = 0,
        val limitKmh: Double? = null,
    )

    /**
     * Whether this fix is far enough from [State.waysCenter] and late enough past
     * [State.lastFetchMs] to be worth a prefetch. **The fetch is the caller's**,
     * and so is any in-flight guard.
     *
     * A null [State.waysCenter] means no area held, not distance zero.
     */
    fun needsWays(state: State, at: LatLon, nowMs: Long): Boolean {
        val fromCenter = state.waysCenter?.let { RoadRoulette.distanceMeters(it, at) }
            ?: Double.MAX_VALUE
        return fromCenter > RoadRoulette.SPEED_PREFETCH_RADIUS_M - FETCH_MARGIN_M &&
            nowMs - state.lastFetchMs > FETCH_THROTTLE_MS
    }

    /** Stamp the attempt. Called *before* the fetch, so a failure is throttled
     *  too. Nothing else changes. */
    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    /** Fold a completed prefetch in. An empty [ways] is a network blip: keep what
     *  we hold rather than flickering the sign off, and leave [State.waysCenter]
     *  where it was, since we do not hold [center]. */
    fun withWays(
        state: State,
        ways: List<RoadRoulette.SpeedLimitWay>,
        center: LatLon,
    ): State =
        if (ways.isEmpty()) state else state.copy(ways = ways, waysCenter = center)

    /**
     * The per-fix snap and the three-miss clear hysteresis. Clock-free.
     *
     * Below [MIN_MPS] the fix is skipped and does **not** count as a miss, so a
     * long wait at a light cannot clear the sign.
     *
     * [headingDeg] lets the snap reject the cross street and the frontage road,
     * which is most of why the sign used to show nonsense.
     */
    fun onFix(state: State, at: LatLon, headingDeg: Double?, speedMps: Double): State {
        if (speedMps < MIN_MPS) return state
        val snapped = RoadRoulette.snapSpeedLimitKmh(at, headingDeg, state.ways)
        if (snapped != null) return state.copy(limitKmh = snapped, misses = 0)
        val misses = state.misses + 1
        // A few misses in a row means the limit really ended (or the road
        // isn't tagged), not a one-fix gap — only then clear the sign.
        return if (misses >= MISSES_TO_CLEAR) {
            state.copy(misses = misses, limitKmh = null)
        } else {
            state.copy(misses = misses)
        }
    }

    /**
     * Crossing into or out of navigation. Clears the sign *and* the miss counter,
     * and keeps the held area — the geometry is still valid, only the derived
     * sign is stale, and re-clearing the throttle would let a navigation toggle
     * punch straight through it.
     *
     * The phone's producer doesn't run while navigating, so without this the
     * value would be the limit from wherever the route began and would survive
     * the whole session — and the trip after it. Stale in both directions: the
     * camera chime falls back to it while navigating, and the HUD switches back
     * to it on the way out. The head unit has done this since it shipped
     * (`car/SpinScreen.kt`'s `onStart`). The misses counter goes with it, or the
     * first miss after the switch would clear a sign that was already cleared.
     */
    fun reset(state: State): State = state.copy(misses = 0, limitKmh = null)
}
```

One equivalence subtlety worth naming: both copies spell the miss branch
`else if (++misses >= MISSES_TO_CLEAR) { limit = null }`, so the counter **keeps incrementing
past 3** on a fourth and fifth consecutive miss and the sign stays null. The version above does
the same — `misses = state.misses + 1` unconditionally, then clears at or past the threshold.
Capping it at 3 would look like tidying and would change nothing observable *today*, which is
exactly why it must not be done in this commit.

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :shared:testDebugUnitTest --tests 'com.jellemax.detour.drive.SpeedLimitTrackerTest'
```
Expected: `BUILD SUCCESSFUL`, 11 tests.

- [ ] **Step 5: Commit the tests, alone**

```bash
git add shared/src/commonTest/kotlin/com/jellemax/detour/drive/SpeedLimitTrackerTest.kt
git commit -m "test(drive): characterise the speed limit tracker"
```

- [ ] **Step 6: Rewrite the phone call site**

Re-derive first — this is the effect 0d rewrote. At `93515cc` the range is `:785-835`, and the
four state declarations are `:245-250`.

**Replace `:245-250`** (`speedLimitWays`, `speedLimitWaysCenter`, `speedLimitFetchMs`,
`speedLimitMisses`) with one holder, and **keep `:244`'s `ambientSpeedLimitKmh`** as it is:

```kotlin
    var ambientSpeedLimitKmh by remember { mutableStateOf<Double?>(null) }
    // The prefetched way set, the throttle, the miss counter and the snapped
    // value: SpeedLimitTracker's, in shared/…/drive/. ambientSpeedLimitKmh stays
    // its own state because the camera chime snapshots it (:918 below) and the
    // HUD reads it; collapsing the two is stage 4's call, not this stage's.
    var limitState by remember { mutableStateOf(SpeedLimitTracker.State()) }
```

Then the effect. This is the **pre-0d** shape; if 0d moved the fetch into a `scope.launch`,
keep 0d's structure and change only the marked lines:

```kotlin
    // Ambient speed-limit sign while just driving (not navigating). The whole
    // policy — prefetch throttle, local snap, three-miss clear — is
    // SpeedLimitTracker's, in shared/…/drive/, shared with the head unit.
    LaunchedEffect(navigating) {
        // Crossing into or out of navigation invalidates whatever sign we hold;
        // reset() says why, and keeps the prefetched area.
        limitState = SpeedLimitTracker.reset(limitState)
        ambientSpeedLimitKmh = null
        if (navigating) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (fix.speedMps < SpeedLimitTracker.MIN_MPS) return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            if (SpeedLimitTracker.needsWays(limitState, pos, now)) {
                limitState = SpeedLimitTracker.fetchStarted(limitState, now)
                // <<< 0d owns this block's shape. Keep whatever is there.
                val ways = withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                limitState = SpeedLimitTracker.withWays(limitState, ways, pos)
                // >>>
            }
            limitState = SpeedLimitTracker.onFix(
                state = limitState,
                at = pos,
                headingDeg = fix.bearingDeg?.toDouble(),
                speedMps = fix.speedMps,
            )
            ambientSpeedLimitKmh = limitState.limitKmh
        }
    }
```

Add `import com.jellemax.detour.drive.SpeedLimitTracker`.

Six equivalence points:

1. **The effect key stays `navigating`** and `if (navigating) return@LaunchedEffect` stays where
   it was (`:804`), after the reset. The reset therefore still runs on **both** edges of the
   navigation boundary, which is what `bac833a` fixed.
2. **`limitState` is a `remember`, not a coroutine-local.** This is the opposite choice from
   machines 1 and 2, and the reason is the key list: this effect restarts whenever `navigating`
   flips, and a coroutine-local would silently discard the prefetched way set every time
   navigation started or ended. That would be a behaviour change hidden inside a refactor —
   exactly the class of defect this stage exists to make visible.
3. **The snapshot-write cadence does not change.** All four replaced declarations were already
   `mutableStateOf`, and `speedLimitMisses` was already written on every fix. `mutableStateOf`
   compares structurally, so an unchanged `State` does not invalidate.
4. **The `MIN_MPS` gate stays at the call site**, verbatim from `:807`, now spelled with the
   hoisted constant. `onFix` guards again internally (that is what
   `aFixBelowTheSpeedFloorIsSkippedAndIsNotAMiss` asserts) — but the call-site return is what
   preserves "no prefetch while stopped", which the internal guard alone would not.
5. **`now` is read once per fix, before the throttle test**, as at `:811`.
6. **`ambientSpeedLimitKmh` is written after `onFix` and only there.** The reset above is the
   only other writer, same as today.

- [ ] **Step 7: Compile, test, commit — car untouched**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
grep -rn 'speedLimitWays\|speedLimitWaysCenter\|speedLimitFetchMs\|speedLimitMisses' app/src/main/java/com/jellemax/detour/ui/
git diff --stat
```
Expected from the grep: **no hits** except `RoadRoulette.speedLimitWays(pos)`, the shared
function. Expected from `git diff --stat`: exactly two files — `drive/SpeedLimitTracker.kt`
(new) and `ui/MapScreen.kt`. **`car/SpinScreen.kt` must not appear.**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/SpeedLimitTracker.kt \
        app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
git commit -m "refactor(drive): move the speed limit tracker to shared"
```

---

### Task 3c′: delete the car's copy of the tracker

**Commit 8, one commit behind 3c.**

**Files:** `app/src/main/java/com/jellemax/detour/car/SpinScreen.kt` only. **Not
`car/NavScreen.kt`, and not `README.md`** — see
[Correction 3](#correction-3-commit-8-cannot-give-the-car-its-ambient-fallback).

- [ ] **Step 1: Replace the four constants and five fields**

Delete `SpinScreen.kt:49-61` (the comment block, `LIMIT_FETCH_MARGIN_M`,
`LIMIT_FETCH_THROTTLE_MS`, and the two KDoc'd constants `LIMIT_MIN_MPS` and
`LIMIT_MISSES_TO_CLEAR`). Their comments are already in `SpeedLimitTracker`'s KDoc from commit 7
— do not leave a second copy behind.

Replace `SpinScreen.kt:94-98` (`limitWays`, `limitWaysCenter`, `lastLimitFetchMs`,
`limitMisses`, `ambientLimitKmh`) with:

```kotlin
    // Ambient speed limit, for the HUD ring while no route is running.
    private var limitState = SpeedLimitTracker.State()
```

`limitFetchJob` (`:99-100`) **stays**, with its KDoc: it is the in-flight guard, it is a `Job`,
and it cannot cross into commonMain.

- [ ] **Step 2: Repoint `updateSpeedLimit`**

Replace `SpinScreen.kt:265-296`, keeping the KDoc above it at `:250-264` (it is the design
record for why the fetch is off the collector — *"Same fix as [NavScreen.checkCameras]"* — and
it still describes this code):

```kotlin
    private fun updateSpeedLimit(pos: LatLon, bearingDeg: Float?, speedMps: Double) {
        if (speedMps < SpeedLimitTracker.MIN_MPS) return
        val now = System.currentTimeMillis()
        if (SpeedLimitTracker.needsWays(limitState, pos, now) &&
            limitFetchJob?.isActive != true
        ) {
            // Throttled on failure too: an empty result is a network blip, and
            // hammering the Overpass mirrors from a moving car fixes nothing.
            limitState = SpeedLimitTracker.fetchStarted(limitState, now)
            limitFetchJob = lifecycleScope.launch {
                val ways = runCatching {
                    withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                }.onFailure { Log.w(TAG, "speed limit lookup failed", it) }
                    .getOrDefault(emptyList())
                limitState = SpeedLimitTracker.withWays(limitState, ways, pos)
            }
        }
        limitState = SpeedLimitTracker.onFix(limitState, pos, bearingDeg?.toDouble(), speedMps)
    }
```

Add `import com.jellemax.detour.drive.SpeedLimitTracker`.

Two structural notes:

- The throttle test and the in-flight guard were one `&&` chain at `:270-272`; they still are,
  with the first two conjuncts now inside `needsWays`. The guard's position in the chain is
  unchanged, so short-circuit behaviour is unchanged.
- `Log.w` stays inside the caller's `runCatching`. commonMain has **zero** logging
  (`detour-shared-core` §4), so a machine that swallowed the exception would swallow the log
  line with it.

- [ ] **Step 3: Repoint the reset and the HUD read**

`SpinScreen.kt:117-121` — the `onStart` reset — becomes a call, **keeping its comment**, which
is the design record the phone's fix was taken from:

```kotlin
                // Coming back from a drive: the last ambient sign is from
                // wherever you set off, so show nothing until the next fix
                // snaps rather than a stale limit from another town.
                limitState = SpeedLimitTracker.reset(limitState)
```

`SpinScreen.kt:150` — `renderer.updateHud(fix.speedMps * 3.6, ambientLimitKmh)` — becomes:

```kotlin
                    renderer.updateHud(fix.speedMps * 3.6, limitState.limitKmh)
```

**This is the one behaviour difference in this commit and it must be named**: `reset` keeps
`lastFetchMs`, where the old `onStart` cleared only `ambientLimitKmh` and `limitMisses` and
also kept it. Identical. But `reset` does **not** clear `limitWays`/`limitWaysCenter` either,
and neither did `onStart`. So this is a zero-behaviour-change repoint — confirm by reading the
old `:117-121` against the new call before committing, rather than assuming it.

- [ ] **Step 4: Confirm the copy is gone**

```bash
grep -rn 'LIMIT_FETCH_MARGIN_M\|LIMIT_FETCH_THROTTLE_MS\|LIMIT_MIN_MPS\|LIMIT_MISSES_TO_CLEAR' app/
grep -rn 'limitWays\|limitWaysCenter\|lastLimitFetchMs\|limitMisses\|ambientLimitKmh' app/
grep -rn 'snapSpeedLimitKmh' app/
```
Expected: no hits for the first two. For the third: **no hits in `app/` at all** — after
commits 7 and 8, the only caller of `RoadRoulette.snapSpeedLimitKmh` in the repo is
`SpeedLimitTracker.onFix`. That is the strongest single check that the duplication is actually
gone rather than moved.

- [ ] **Step 5: Compile, test, commit**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
git diff --stat   # expected: car/SpinScreen.kt only
git add app/src/main/java/com/jellemax/detour/car/SpinScreen.kt
git commit -m "refactor(car): use the shared SpeedLimitTracker instead of a second copy"
```

- [ ] **Step 6: REPLAY GATE — routes (i) and (ii), and record the comparison**

Two fixtures, because **neither one alone can measure both quantities**, and the spec's single
choice of route (ii) cannot measure the more important one — see
[Correction 5](#correction-5-route-ii-has-no-sign-baseline).

| Fixture | Quantity | Baseline |
|---|---|---|
| `trajectcontrole.txt` | **count of distinct posted-limit values displayed** | **six** — 30, 50, 70, 90, 100, 120, from `trajectcontrole-a90c3df.tsv`'s distinct `sign_ink`; the only run whose mirror never failed (`baseline/README.md:739`) |
| `urban-limits.txt` | **fixes from the last successful snap to the sign clearing** | **3 fixes ≈ 3.1 s**, at fix 609→612 (`baseline/README.md:737`) |

Cross-check the same 3-fix latency on `stop-start.txt` (fix 470→473) if a third run is cheap;
it is the independent confirmation of the same number.

**Against baseline, not against screenshots** (the spec's Risks section). And **if 0d landed
before this item, A/B against 0d's own post-change capture**, not against `…-09fddde-…`.

Two things this replay cannot establish, which must be said rather than glossed:

- `urban-limits.txt`'s urban half has **no sign baseline at all**: Overpass stopped answering
  3.5 minutes into the only healthy run, the held set outlasted its radius, and the app *"never
  displayed any value other than 120"* (`baseline/README.md:305-314`). Its value here is the one
  measured clear latency, nothing more.
- Nothing in any fixture exercises `reset` across a navigation boundary, because no replay
  starts navigation. `resetClearsTheSignAndTheMissesAndKeepsTheHeldArea` is its only coverage.

---

## Done Criteria

- [ ] **Three machines in `shared/src/commonMain/kotlin/com/jellemax/detour/drive/`**, with
      **34 tests** across three `commonTest` files (12 + 11 + 11).
- [ ] **Eight commits, in the spec's order**, with the spec's subjects. One work item each.
      **No trailers of any kind.** Verify:
      `git log --format='%s%n%b' 93515cc..HEAD | grep -in 'co-authored\|claude-session'` → no
      output.
- [ ] `./gradlew :shared:compileCommonMainKotlinMetadata` green — the check that catches `java.*`
      leaking into commonMain, and it is path-gated in CI so run it locally
      (`detour-shared-core` §7).
- [ ] `./gradlew :shared:testDebugUnitTest` green, and `./gradlew :shared:iosSimulatorArm64Test`
      green **if a macOS runner is available**. `ios.yml:64-68` runs both — the JVM pass in
      seconds, the Native pass to prove it behaves the same compiled for the device — and
      `ios.yml` is path-gated on `shared/**`, so this stage triggers it on every commit.
      **`build.yml:118` also runs `:shared:testDebugUnitTest` on every PR, un-path-gated.**
      Between them, `commonTest` is the best-protected test source set in the repo, which is
      most of why this stage is worth doing. If the Native pass cannot be run locally, say so
      when reporting the stage and let CI be the first Native run — do not claim it passed.
- [ ] `./gradlew :app:testDebugUnitTest` green — stage 2's 42 tests under
      `com.jellemax.detour.map` still pass untouched.
- [ ] `./gradlew :app:assembleDebug :app:assembleRelease` succeeds. R8 catches what debug does
      not.
- [ ] **`Platform.kt` still has exactly four `expect` declarations**, and commonMain still has
      **zero `Dispatchers`**:
      `.claude/skills/detour-shared-core/scripts/check-preconditions.sh`.
      **Expect one `FAIL` after commit 4** — the zero-interfaces assertion, on `CameraWarner`'s
      `sealed interface Outcome`. That is the assertion being over-broad, not the stage being
      wrong; see [Correction 2](#correction-2-camerawarners-sealed-interface-trips-a-repo-wide-assertion).
      Every other assertion must pass.
- [ ] **No machine reads a clock.**
      `grep -rn 'nowMs()\|Clock\.' shared/src/commonMain/kotlin/com/jellemax/detour/drive/` →
      no hits. `nowMs` appears only as a *parameter name*.
- [ ] **No `CoroutineScope`, no `StateFlow`, no `suspend`, no interface for abstraction** in
      `drive/`:
      `grep -rn 'CoroutineScope\|StateFlow\|suspend fun' shared/src/commonMain/kotlin/com/jellemax/detour/drive/`
      → no hits.
- [ ] **Car copies deleted, each in its own commit, one behind its extraction — two of them,
      not three.** `CameraWarner` (commit 5) and `SpeedLimitTracker` (commit 8) had car copies;
      `SectionAverageTracker` did not, because the car discards `result.sections`.
      `grep -rn 'Section\|sectionAvg\|speedSections\|\.sections' app/src/main/java/com/jellemax/detour/car/`
      → still zero hits, as at `93515cc`.
- [ ] **`RoadRoulette.snapSpeedLimitKmh` has exactly one caller in the repo**, and it is
      `SpeedLimitTracker.onFix`. Two call sites in `app/` before, one in `shared/` after.
- [ ] **The duplication is gone, not moved.** `grep -c 'warnedCameraAt' car/NavScreen.kt` → 0;
      `grep -c 'LIMIT_MISSES_TO_CLEAR' car/SpinScreen.kt` → 0.
- [ ] **Each machine replayed against the baseline with the comparison recorded** in
      `tools/mocklocation/baseline/`. Three gates, three write-ups. A gate that was skipped is
      reported as skipped.
- [ ] **No effect key list changed and no `rememberUpdatedState` was deleted.**
      `.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh 93515cc` prints every effect
      declaration the range touched — read them, then confirm:
      `git diff 93515cc..HEAD -- app/src/main/java/com/jellemax/detour/ui/MapScreen.kt | grep '^[+-].*LaunchedEffect('`
      shows `LaunchedEffect(Unit)` twice and `LaunchedEffect(navigating)` once on each side,
      with identical key lists.
- [ ] **`MapScreen.kt` roughly 1500–1560 lines.**
      **This is the corrected figure and the spec says so in place**: the old 1300–1400 was
      written when the file was 1549 lines and it is **1658** at `93515cc`. The three effect
      bodies are 45 + 45 + 57 = 147 lines (`:791-835`, `:924-968`, `:975-1031`), plus ~14 lines
      of comment and state/snapshot declarations, against roughly 40 lines of replacement call
      site and one new `remember` — a net reduction near 120, not 250. `sectionExitGate` comes
      out of `MapCameraTuning.kt` (100 → 66 lines), not this file. **If the plan hits 1300–1400
      it removed something this stage did not authorise** — check for state-layer changes, which
      are stage 4's. Add whatever 0d's own net delta was before judging the number.
- [ ] **Never accept the line count as the success criterion** (`detour-staged-refactor` §6).
      Report what moved, what did not, and what is still open.

## Verification

**Tier: full manual checklist plus per-machine replay A/B** (`detour-staged-refactor` §5). This
is the stage the baseline was recorded for, and all three machines are `lastFix` consumers,
which is Tier 2 by the table. Tier 0 on every commit:

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh 93515cc
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :shared:compileCommonMainKotlinMetadata
```

**Desk checklist**, on the debug build after commit 8, stationary: the map loads and follows;
the speed HUD shows a number; drive off (or replay) and confirm the ambient limit sign appears;
start navigation and confirm the sign switches to the route's limit and the average chip is
absent; end navigation and confirm the sign returns rather than showing the limit from where the
route began (that is `bac833a`'s fix, and commit 7 is the one that could break it). Then the car
surface: open the app on the head unit with no route, confirm the HUD ring shows a limit; start
a navigation session, confirm the turn card, the camera markers and the voice; confirm a camera
warning still speaks and toasts once, not once per second.

**Three replay A/Bs, one per machine, at the gates above.** Follow `detour-gps-replay`'s
protocol and name the quantity before looking. Summarised:

| Gate | Fixture | Quantity | Expected |
|---|---|---|---|
| after commit 2 | `public-trajectcontrole.txt` (A recorded fresh) | first/last `AVG` fix index, settled average | gate at line 170 / line 457, **100.0 km/h** |
| after commit 2 | `trajectcontrole.txt` | AVG event count and indices | **4 events**: on 166, off 543, on 546, off 804, ±3 fixes |
| after commit 5 | `trajectcontrole.txt` | `"Speed camera ahead"` count and fix indices | unchanged against the stage-0 baseline |
| after commit 8 | `trajectcontrole.txt` | distinct posted-limit values displayed | **six**: 30, 50, 70, 90, 100, 120 |
| after commit 8 | `urban-limits.txt` | fixes from last snap to sign clear | **3 fixes ≈ 3.1 s** (fix 609→612) |

**Three coverage gaps that are unit-tested only and must be reported as such**, not folded into
"replayed and verified":

1. **The posted-limit comparison and the red chip.** Neither E40 relation tags `maxspeed` —
   only the `device` nodes do, and `SpeedCameras.parseSection` (`SpeedCameras.kt:109`) reads the
   relation's tags — so `Section.maxspeedKmh` is null for **every** replay of **every**
   trajectcontrole fixture, personal or public. This is what the OSM data says; it is not a
   fixture defect to be fixed by picking a better route. `limitIsCarriedStraightThroughFromTheSection`
   is the replacement for the replay, not a redundant extra. Relation **16251379** is the tagged
   alternative (`17-public-trace-datasets.md` §3.3) — a new fixture *and* a new baseline, and
   not this stage's work.
2. **`timedOut`, and `reachedEnd` at a far gantry entered from the near end.** No fixture sits
   in a section for 30 minutes, and on this route the exit gate *is* the next section's entry.
   `theTimeoutEndsItAndExactlyTheTimeoutDoesNot` and
   `theHundredAndFiftyMetreFloorStopsAnImmediateExit` are their only coverage.
3. **`CameraWarner`'s null-heading branch and `SpeedLimitTracker.reset`.** The mock provider
   always derives a bearing, and no replay starts navigation.

**On maxke24/Detour#22.** These commits deliberately encode **no cause**. The parser theory is
refuted (`5eb03bb`, `SpeedCameraSectionTest` at
`shared/src/commonTest/kotlin/com/jellemax/detour/data/ParsingTest.kt:145`), and the
2026-08-13 replay **did not reproduce** the defect on this route — no early clear, and the
re-arm worked (fix 546). Since no section code changed between runs, the difference is the
input, so **#22 wants narrowing, not closing**. What this stage buys is that `State` is now a
value: whichever transition fires on a run that *does* reproduce it can be driven offline by a
test. If none of them reproduces it, the cause is outside the machine — the collector
restarting, the `rememberUpdatedState` snapshot, or `speedSections` being emptied — and that is
stage 4's territory. Narrowing it is a result. Either way: **if a diagnosis arrives, its fix is
a commit behind the extraction, never inside it** (`DECISION.md:411`).

## Corrections to the spec

Found while deriving this plan, all measured against `93515cc`. Recorded here because the
spec's own Status table says citations in these documents have been wrong before, and because
leaving a known-wrong claim in place is worse than it never having been there. **Correct them
at the source** (`detour-staged-refactor` §8) — in the spec and in the skills named, each in its
own bookkeeping commit *after* the stage, never folded into one of the eight.

### Correction 1: the spec's signatures say `internal`, and `internal` cannot work

All three of the spec's Work-items code blocks declare `internal object`. **That would make the
machines uncallable from `app/`.** `internal` in Kotlin is module-visible, `:shared` and `:app`
are separate Gradle modules (`app/build.gradle.kts:151`), and this is the exact fact the spec
itself establishes two sections earlier about `nowMs()`: *"`nowMs()` is **`internal`** …, so it
is visible inside `:shared` and nowhere else. `app/` cannot call it."* The same sentence applies
verbatim to an `internal object SectionAverageTracker`, and it also means **nothing would be
exported to the iOS framework**, which is the whole stated point of the stage.

Every shared type `app/` uses today is public — `RoadRoulette`, `SpeedCameras`, `NavEngine`,
`NavAnnouncer`. **This plan writes the three machines as public `object`s** and keeps `internal`
for exactly one member, `SectionAverageTracker.sectionExitGate`, which only `commonTest` needs.
The spec's `internal` is a copy of stage 2's idiom (correct there — `app/` and `car/` are one
module and `internal` reaches `app/src/test`) carried across a module boundary where it does not
hold.

### Correction 2: `CameraWarner`'s `sealed interface` trips a repo-wide assertion

`.claude/skills/detour-shared-core/scripts/check-preconditions.sh` asserts *"commonMain has
ZERO interfaces — 33 object singletons is the house pattern"*, implemented as
`grep -rl 'interface ' shared/src/commonMain` returning empty. Today it passes: the only match
in commonMain is the word "interface" inside a `Platform.kt` comment. **`sealed interface
Outcome` will make that assertion FAIL from commit 4 onward.**

The assertion's *intent* is not violated. It exists to stop a one-implementation DI interface
(§2 test 2: *"One implementation behind an interface is indirection, not a boundary"*), and a
sealed return hierarchy is the opposite thing — it is stage 2's own established idiom for this
exact shape (`NavPolicy.Decision`, `SpinRoundOutcome`), and the spec mandates
`Outcome.Warn(at, text)` explicitly. So this is `detour-staged-refactor` §2's second hypothesis:
**the assertion was over-broad when written.** Keep the sealed interface; fix the assertion in
its own commit after the stage, narrowing it to exclude `sealed interface`, and note the change
in the skill's §4 row and §2 test 2. Do not "fix" it by rewriting the machine, and do not let
the stage land with a silently red precondition script.

### Correction 3: commit 8 cannot give the car its ambient fallback

The spec's 3c′ prose says commit 8 *"is also the commit that gives the car its ambient
fallback, which is what makes `README.md:383-385` true, so the one-line README edit lands
here."* **It cannot.** The car's camera warning lives in `car/NavScreen.kt`, and `NavScreen` has
**no ambient speed-limit state of any kind** — verified: its fields are `progress`,
`currentSpeedKmh`, `speedCameras`, `camerasCenter`, `lastCameraFetchMs`, `warnedCameraAt`,
`cameraFetchJob` (`:108-130`), and its only limit source is `progress?.speedLimitKmh` (`:388`).
The tracker being extracted lives in `car/SpinScreen.kt`, a **different screen**. Giving
`NavScreen` the fallback means giving it a `SpeedLimitTracker` of its own: new state, a second
Overpass query type on the navigation hot path, and a new network/battery cost — a **feature
addition**, not a deletion.

The spec's own Commit sequence table already agrees: commit 8's Touches column reads
`car/SpinScreen.kt, README.md` and **does not list `car/NavScreen.kt`**. The table is the
binding half.

So: **commit 8 touches `car/SpinScreen.kt` only.** The car does not gain the ambient fallback in
this stage, and therefore **`README.md:383-385` is not edited** — it claims the head unit has
*"the same … camera warnings as the phone"*, which stays false until the car gains the fallback,
and editing it now would make the README wrong in a commit whose message says it is fixing that.
The car's fallback is feature work for the convergence axis; note it, do not start it. Commit
5's call site carries a comment naming the gap, which is `detour-shared-core` §2 rule 4.

### Correction 4: the baseline precondition counts the wrong column

The spec's Preconditions block strengthens `test -d` with:

```sh
cat tools/mocklocation/baseline/trajectcontrole-*-events.tsv \
  | awk -F'\t' 'NR>1 && $5!="0" && $5!="" && $5!="-" {n++} END{print n+0}'   # expect >= 1
```

That was written against the **old** events-TSV schema, in which field 5 was the `avg` flag
(`trajectcontrole-09fddde-events.tsv` header: `t_s fix sign ink avg hud route_kmh cum_m
lat,lon event`). The `a90c3df` capture uses a **new** schema in which field 5 is `event` and the
chip is field 6, `chip_fill` (header: `fix elapsed_s cum_m route_kmh event chip_fill chip_err
sign_red dial_ink dial_red avg_box`). So the assertion now counts *every event row of any kind*
— `START`, `HUD-ON`, `SIGN-ON` — and would pass on a capture where the chip never armed. It
passes today for the right reason by accident.

The assertion that measures what the sentence claims:

```sh
grep -c 'AVG-ON' tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv      # expect 2
grep -c 'AVG-CLEARED' tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv # expect 2
```

Both return 2 at `93515cc`. Fix it in the spec in its own commit, and note in the Status block
that the events-TSV schema changed between the `09fddde` and `a90c3df` captures — a fact worth
recording once, because every other `awk -F'\t' '$N'` over these files has the same hazard.

### Correction 5: route (ii) has no sign baseline

3c's Replay section says *"Route (ii) — `urban-limits.txt`, which is the only route with a
baseline **and** a measured posted-limit ladder"*. The baseline README says the opposite:
`urban-limits` run 2 held **120 and only 120** for 590 fixes and then went out for good when
Overpass stopped answering 3.5 minutes in, and *"the urban half of `urban-limits` therefore has
**no sign baseline**: no posted-limit change, cross street or frontage road was ever exercised,
and the app never displayed any value other than 120"* (`baseline/README.md:305-314`). Run 1 saw
no sign at all. The measured ladder — **six values, 30/50/70/90/100/120** — is on
**`trajectcontrole.txt` at `a90c3df`**, *"the only run whose mirror never failed"*
(`README.md:739`).

`urban-limits` does carry one thing worth A/Bing: the 3-fix clear at fix 609→612, independently
confirming `stop-start`'s 470→473. So this plan runs **both** fixtures at the commit-8 gate,
each for the quantity it can actually measure. Correct the spec's Replay paragraph rather than
picking one.

### Stale citations, corrected in passing

| Claim as written | Where it actually is, at `93515cc` |
|---|---|
| `detour-shared-core` §1: `app/` depends on `:shared` at `app/build.gradle.kts:135` | **`:151`** |
| `detour-shared-core` §6: the phone's two prefetch machines at `MapScreen.kt:735-767` and `:773-794` | **`:791-835`** and `:841-862`. Its **car** citations (`SpinScreen.kt:265-296`, `:272`, `:52-61`, `NavScreen.kt:378-417`, `:383`, `:56-57`) are correct except that the car's camera block is `:361-400` and its two constants are `:58-59` |
| `detour-shared-core` §1 note: `MapScreen.kt` *"is now 1,549 lines"* | **1658** at `93515cc`, and moving under a concurrent 0d |
| spec 3a: *"the section effect at `MapScreen.kt:974-1031`"* / *"the effect at `:975`"* | correct; the comment above it starts at `:970`, which is what this plan's ranges include |
| spec 3b: *"`car/NavScreen.kt:380-399`"* | correct. `checkCameras` itself is `:361-400`, the prefetch half `:362-379` |

Verified correct and worth not re-checking: `ios.yml:59`, `:64-68`; `build.yml:118`;
`README.md:383-385`; `car/SpinScreen.kt:117-121`, `:150`, `:265-296`; `MapHud.kt:180-181`,
`:184`; `Angles.kt:16` (`internal fun nowMs`); `Platform.kt` four `expect`s; `SpeedCameras.kt:53`
(`WARN_METERS = 400.0`), `:109`, `:139`; `RoadRoulette.kt:15`, `:101`, `:251`, `:255`, `:295`,
`:424`; the west/east gantry coordinates and line numbers at `routes/README.md:71`; every AVG
fix index in `baseline/README.md:487-496` and `:743-745`.

## Stop-point C — the default stop

**Stop here unless there is a specific reason not to.** When the last commit lands, record in
[`../DECISION.md`](../DECISION.md):

> Stage 3 complete. The three road-hazard machines — the trajectcontrole average, the
> one-chime-per-camera latch and the ambient speed-limit tracker — are in
> `shared/src/commonMain/.../drive/` with 34 `commonTest` tests, behind the one CI gate that
> runs on both the JVM and Kotlin/Native, and the car's two duplicates are gone. **The state
> layer is still untouched**: `MapScreen.kt` keeps every other `remember` and every effect key
> list, and the two section `mutableStateOf`s are still two. iOS *can* now consume all three and
> **does not yet** — the readouts are convergence 2's. The car did **not** gain its ambient
> camera-warning fallback (`NavScreen` has no tracker; that is feature work, see the plan's
> Correction 3), so `README.md:383-385` is still false. maxke24/Detour#22 is **not fixed and not
> diagnosed**; it did not reproduce on this route at `a90c3df`, and what this stage bought is
> that the suppression is now drivable offline. This is stop-point C exactly as the spec defined
> it, and it must not be recorded as "MapScreen refactored".

Then update `specs/stage-3-hazard-machines-to-shared.md`'s Status block to `**done**` with the
date and the commit range, apply the five corrections above at their sources in their own
commits, and run stage 4's preconditions and record the result in its Status block, whether or
not you intend to continue (`detour-staged-refactor` §6).

## Next

Stage 4 is a **decision gate, not a work plan**, and it has no work items by design: the choice
— Compose state holders versus a targeted reducer — is not decidable until this stage has
finished, and stage 2's deliberately unwired `CameraAuthority` exists so that the choice can be
made against real code rather than two proposals. Run its Preconditions, then invoke
`superpowers:brainstorming` against
[`../specs/stage-4-state-ownership.md`](../specs/stage-4-state-ownership.md).

**"Should we even do stage 4" is a valid outcome of that brainstorm**, and stop-point C is what
makes "no" a defensible answer.

## Observations for later, deliberately out of scope here

Found while deriving this plan. None belongs in a stage-3 commit.

1. **The two `2.0` m/s floors have opposite boundary semantics.** `SectionAverageTracker`'s arm
   gate is `takeIf { speedMps > 2.0 }`; `SpeedLimitTracker`'s is `if (speedMps < 2.0) return`.
   Same literal, opposite edge, three surfaces. Both are characterised as-is here. Whether they
   should be one constant is a convergence question.
2. **`MapCameraTuning.kt` drops to 66 lines** after commit 2 and holds four unrelated things:
   `smoothBearing`, two camera-resume constants and a poll interval. `smoothBearing` is also
   duplicated at `car/CarMapRenderer.kt:470` (`detour-shared-core` §5) and is a pure function of
   its arguments — a commonMain candidate, and not this stage's.
3. **`SpeedLimitTracker.State.misses` grows without bound** past `MISSES_TO_CLEAR` on a long
   untagged stretch. Harmless (`Int` over a drive) and characterised as-is, because capping it
   would look like tidying.
4. **`NavAnnouncer` is a mutable stateful class in commonMain** (`NavAnnouncer.kt:36`, public,
   one instance per session), which is a second viable shape for a stateful machine and the one
   this stage did *not* choose. Worth naming when convergence 2 designs its per-surface holder:
   the value-returning `object` is what makes these three testable without an instance, but the
   precedent for the other shape is in-tree and works.
