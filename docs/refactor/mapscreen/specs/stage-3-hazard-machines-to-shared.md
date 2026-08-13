# Stage 3 — Road-hazard machines into the shared core

## Status

| | |
|---|---|
| **Detail level** | **Full.** The scheduled rewrite of the Work items section was performed 2026-08-13 against `a90c3df`, with stage 2's four extractions in hand; the Scope, Constraints and Consumed decisions sections are unchanged in substance. Ready for `superpowers:writing-plans`. |
| **Prerequisite** | [Stage 2](stage-2-pure-extractions.md) complete |
| **State** | not started — **unblocked 2026-08-13, ready to plan.** The section baseline was re-run against `a90c3df` with Overpass answering (`trajectcontrole-a90c3df-events.tsv`) and captured the machine end to end: chip on at fix 166 (the west gate), converging `Ø 115` → 84 → 78 → `Ø 75` against the route's own 75.4 km/h, cleared at fix 543 by `reachedEnd` with `accMeters` 7 946 m of a 7 950 m span, **re-armed at fix 546** for the second relation, cleared at fix 804 by `overshot`. All four AVG events in 1 765 frames are explained. **maxke24/Detour#22 did not reproduce** — no early clear, and the re-arm worked; since no section code changed between runs the difference is the input, so #22 wants narrowing rather than closing. `out geom` was measured **not** to clip relation members (a node 10 459 m outside the 4 km radius came back), which refutes the mechanism #22 assumed. 13 of 13 preconditions pass. |
| **Preconditions captured** | 2026-08-11; re-run 2026-08-12 against `5613e59` — 11 pass, 1 fail (the missing baseline directory). An earlier run read 9 pass with 2 informational: `chain-status.sh` could not judge `# expect 1  (camera-warn latch)`, because it treated the explanatory parenthetical as part of the expected value and silently ungated the assertion. Fixed in the script rather than by rewording the specs, since seven assertions across stages 2 and 3 were affected |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · [register](../15-divergence-register.md) · prev: [stage 2](stage-2-pure-extractions.md) · next: [stage 4](stage-4-state-ownership.md) · consumed by: [convergence 2](convergence-2-section-readouts.md) |

> **Scheduled rewrite — done 2026-08-13.** This spec fixed the goal, the destination and the
> constraints, and deliberately left the internals open, because how these machines should be
> shaped depends on what stage 2's extractions teach about pulling decisions out of this screen.
> Stage 2 landed 2026-08-12; the [Work items](#work-items) section is now the rewrite, and it
> opens with what stage 2 taught. The Scope, Constraints and Consumed decisions sections were
> inputs to it and were **not** reopened — line-number corrections aside, listed in
> [What the rewrite corrected](#what-the-rewrite-corrected).

## Preconditions

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# Stage 2 landed.
test -d app/src/main/java/com/jellemax/detour/map && echo stage2-done
test -f app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt && echo tests-exist

# The three machines are still inline, still stateful, still keeping their state in
# coroutine-local vars that no test can reach.
grep -c 'var warnedAt' $M                        # expect 1  (camera-warn latch)
grep -c 'var active: SpeedCameras.Section' $M    # expect 1  (section tracker)
grep -c 'speedLimitMisses' $M                    # expect >= 2 (ambient-limit hysteresis)

# The core already holds the stateless half of all three, unused by iOS.
grep -c 'fun speedLimitWays' shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt   # expect 1
grep -c 'fun snapSpeedLimitKmh' shared/src/commonMain/kotlin/com/jellemax/detour/data/RoadRoulette.kt # expect 1
grep -c 'fun near' shared/src/commonMain/kotlin/com/jellemax/detour/data/SpeedCameras.kt              # expect 1

# commonMain's constraints are unchanged.
grep -rc 'Dispatchers\.' shared/src/commonMain/kotlin/ | grep -v ':0' | wc -l   # expect 0
grep -c 'fun nowMs' shared/src/commonMain/kotlin/com/jellemax/detour/data/Angles.kt  # expect 1
grep -c '^expect ' shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt      # expect 4

# The baseline recordings from stage 0 exist AND captured section events.
#
# `test -d` was the original assertion and it was too weak: the directory landed
# in e313a28 with two of three routes recorded cleanly, so the fence went green
# while the one thing machine 1 is measured against — the average-speed chip
# appearing, settling and clearing — had never been observed. Both Overpass
# mirrors were refusing connections during that run, so no section data reached
# the app at all. Count the frames where the chip was present instead.
test -d tools/mocklocation/baseline && echo baseline-present
grep -ch 'AVG-ON' tools/mocklocation/baseline/trajectcontrole-*-events.tsv 2>/dev/null \
  | awk '{n+=$1} END{print n+0}'   # expect >= 1
```

The first version of this assertion counted "field 5 is non-empty and non-zero", which was the
`avg` column in the `09fddde` schema and became the `event` column in `a90c3df`'s. It therefore
counted every event row — 25 of them — and went green on a run whose section events were the
one thing being checked for. A gate that passes for the wrong reason is worse than no gate, so
it now matches on the `AVG-ON` marker itself, which names the transition rather than a column
position. Corrected 2026-08-13, found while writing this stage's plan.

## Why this stage

The surface-independence audit found that **parity is decided by statefulness, not domain
relevance**. Every feature that reached iOS has its logic in `shared/`. Every feature that did
not is welded into a composable or an Android Service.

The three road-hazard features are the proof: `RoadRoulette.speedLimitWays`,
`RoadRoulette.snapSpeedLimitKmh` and `SpeedCameras.near` **already sit in commonMain, unused**.
Only the stateful wrapper around them — the prefetch throttle, the 3-miss clear hysteresis,
the one-chime-per-camera latch, the section entry/exit tracker — never left `MapScreen.kt`.
That wrapper is roughly 190 lines holding five coroutine-local `var`s that no test can reach.

Moving it is what gives iOS three features it does not have, gives the car a 3-line addition
instead of a 63-line copy, and puts the code that produces the field bugs under the one CI
gate that already runs (`ios.yml:64-68` runs `:shared` tests on both JVM and Kotlin/Native).

## Scope

Three stateful machines move into `shared/src/commonMain/.../drive/`, with tests in
`commonTest`:

1. **`SectionAverageTracker`** — trajectcontrole entry/exit gating and running average.
   Includes `sectionExitGate`, which stage 1 moved to `MapCameraTuning.kt`.
2. **`CameraWarner`** — the one-chime-per-camera latch and its re-arm rule.
3. **`SpeedLimitTracker`** — prefetch throttling, local snapping, and the 3-miss clear
   hysteresis.

Then the car's copies are deleted and pointed at the core, one commit behind each extraction.

## Blocker: the early clear is still undiagnosed, and it is not the parser

The section baseline (`e313a28`, re-run `056227d`) found that the average-speed readout clears
a few hundred metres into a section rather than at the exit gantry. maxke24/Detour#22 read that
as `SpeedCameras.parseSection` treating a clipped Overpass node set as complete — a section
longer than `PREFETCH_RADIUS_M` yielding a bogus short section whose far end sits just past the
entry. **That mechanism is refuted for these two relations** (`5eb03bb`,
`SpeedCameraSectionTest` in `shared/src/commonTest/.../ParsingTest.kt`): clipped to the entry
end, both are *rejected*, because their end clusters are 22 m and 14 m across against a 200 m
`MIN_SPAN_M`. Clipping loses a section; it does not shorten one. So there is no parser fix to
make first, and #22 needs correcting rather than closing.

Two further facts from the same tree, neither of which needs Overpass:

- The prefetch attempts on a 15 s throttle, so at ~1.02 s/fix they land every ~14.7 fixes, and
  the one that produced the entry must be at fix ≤ 72. Every such centre leaves the far gantry
  **outside** the 4 km circle — 6 321 m at fix 0, 4 304 m at fix 59, and 4 000 m at fix 68 in the
  latest case that fits the cadence. The chip still appeared at fix 73, which needs a parse with
  both ends, so `out geom` almost certainly returns relation member nodes beyond the `around`
  radius — i.e. the clipping #22 assumes may not happen at all.
- Under no-clipping, `15682532` was complete in the fix-88 answer and the readout **should** have
  re-armed at the shared gantry at fix 218. It did not. So one unexplained suppression of section
  state accounts for both the clear at fix 81 and the missing re-arm — and neither the parser nor
  `sectionExitGate` can produce it.

**This still changes what machine 1's characterisation tests are for.** Written against current
behaviour they would encode the early clear as intended, which is exactly the trap
characterisation testing sets: it preserves whatever is there, bug included. So:

1. Diagnose the termination first, in its own commit — on the tracker's state, not the parser.
   `sectionAvgKmh`'s three writers are already enumerated in the baseline README; what has not
   been checked is whether the collector itself survives, i.e. whether `TripTrackingService.lastFix`
   re-emits into a fresh `LaunchedEffect(Unit)` body, or whether `active` is reset by something
   outside the two branches that write it.
2. Re-run `trajectcontrole.txt` and confirm the readout survives to the exit gantry and re-arms
   across the shared node at 6.36 km — the transition the route was built for and which has still
   never been observed working.
3. Only then write machine 1's characterisation tests, against corrected behaviour.

Replaying `near()`'s own query against a reachable mirror is still the one measurement that would
settle what `speedSections` held; Overpass has been refusing this IP since the run.

## Consumed decisions

[`../15-divergence-register.md`](../15-divergence-register.md) enumerates 22 cross-surface
divergences. **Five of them are inputs to this stage, one more is a constraint on it, and the
other sixteen are not this stage's business** — that split is the register's own, in its
§ *What stage 3 actually consumes*, and the sixteen are described there as *"adjacent work that
must not be folded in"*. They now live on the convergence axis
([`00-chain-design.md`](00-chain-design.md) § *The two axes*); this section is the only edge
between the two, and it points inward. Nothing here schedules work outside this stage.

Read the entries in the register rather than here. This section records what each one *does to
this stage's scope*, which is the part a plan needs.

### Entry 11 — the trajectcontrole average: **DECIDED, all three surfaces**

The decision (register §C, decision 2, taken 2026-08-12) settles two things this spec previously
left open.

- **The destination is beyond argument.** `SectionAverageTracker` goes to `shared/` commonMain
  because iOS cannot consume anything in `app/`. The Constraints section below already said
  commonMain; this removes the last reason anyone might reopen it.
- **The output is a public contract from day one**, for three consumers rather than one phone
  readout. Two consequences for machine 1's design: choose the `StateFlow` element type against
  the iOS `FlowWatcher` cost — **one subclass per element type**, nine of them today
  (`shared/src/iosMain/.../FlowWatcher.kt`, whose doc comment argues the trade) — and **expose
  the average and its posted limit as one value, not two flows.** The phone currently holds them
  as two `mutableStateOf`s and passes them as two arguments (`MapScreen.kt:255-256` →
  `ui/MapHud.kt:180-181`); exporting that shape would cost two watcher subclasses and let the two
  values disagree across a recomposition.

**What this stage does not do:** the car and iOS *readouts*. The register is explicit that they
are feature work after the tracker lands —
[`convergence-2-section-readouts.md`](convergence-2-section-readouts.md) owns them, and §C.1's
last line forbids them sharing a commit with the extraction they depend on.

### Entry 1 — the camera chime's ambient fallback: keep it (§B1 **already fixed**, `bac833a`)

Machine 2 cannot be written without knowing whether it takes one limit or two. The answer is
two: keep the phone's fallback to the ambient sign, because a camera warning that goes silent on
an untagged road is a warning you cannot trust, and the car's omission has no written rationale
(`git blame` in entry 1 shows it was never decided).

**The ordering inverted what a naive reading of "extract the phone's version" would produce, and
that ordering has since been satisfied.** `ambientSpeedLimitKmh` *was* never reset: its producer
is gated off while `navigating` and the only writers are inside that collector, so the value the
chime read was frozen at whatever the sign said when navigation began — and still stale *after*
navigation ended, because the HUD switches back to it. So the copy being kept was a copy with a
defect, and §B1's fix had to come first, in its own commit. `DECISION.md:411`: never an
extraction and the bug it reveals. The car already had the reset and says why
(`car/SpinScreen.kt:117-121`), which is why entry 1's answer is *the phone's fallback plus the
car's reset* rather than either copy wholesale.

> **§B1 is fixed — `bac833a`.** The reset is `MapScreen.kt:791-803`, crediting
> `car/SpinScreen.kt:117-121` in its own comment at `:799`, and
> `../15-divergence-register.md:1867` records it `RESOLVED`. **Do not schedule that commit**; see
> [3b](#3b--camerawarner). Entry 1's substance is unaffected: the phone's fallback is still what
> survives, and the car still has no ambient sign to fall back on until 3c′.

`README.md:383-385` claims the head unit has *"the same … camera warnings as the phone"*, which
is false today and becomes true when machine 2 lands. That one-line edit belongs to the commit
that makes it true.

### Entry 13 — the `+3.0`, `45.0` and `+5` literals: **both values survive, hoisted**

Nothing to decide: the three `+5`s and both `+3.0`s agree today, re-verified against this tree
(`MapHud.kt:184`, `car/CarMapRenderer.kt:635`, `wear/…/MainActivity.kt:140`; `MapScreen.kt:945`,
`car/NavScreen.kt:389`). `CameraWarner` should own the chime threshold
(`+3.0`) and the camera-ahead wedge (`45.0`) — one copy each in `MapScreen.kt` and
`car/NavScreen.kt` today, verified. **Hoisted, not re-declared:** a third copy inside the machine
while two literals remain at the call sites is the state entry 13 exists to prevent. The HUD's
`+5` is *not* machine 2's — it cannot usefully go to `shared/` while `wear/` is excluded from it,
so it stays in `app/` with the watch's copy commented.

### Entry 15 — chime, or chime + speech + toast: **resolved by the `CircleEvents.kt` shape**

Already answered by the constraint this spec carries: decision and wording in the core, delivery
per platform. `CameraWarner` emits a **warning decision** — that a camera warrants a warning, and
its text — and each surface decides how to deliver it. **`CameraWarner` must not know about
tones, speech or toasts.** That is what turns "should the phone speak?" from a blocker into a
later one-line delivery choice, and it is why decision 1's phone `NavVoice` is
[`convergence-3-voice-policy.md`](convergence-3-voice-policy.md)'s problem, not this stage's.

### Entry 2 — the Overpass fetch comes off the fix collector **before** machine 3

Ordering, not a choice. The car's structure wins with no trade-off — a conflating `StateFlow`
plus a suspending collector is a stall with no upside, and both surfaces already agree on every
threshold. That work is stage 0 item **0d**
([`stage-0-verification-baseline.md`](stage-0-verification-baseline.md)), deferred because the
retune check needs replay route (ii).

`SpeedLimitTracker` touches the same stream a second time, so **if 0d has not landed when machine
3 starts, this stage inherits the hysteresis trap along with the machine**: the 3-miss clear
rule was only ever tuned against a fix stream that *had* those drops. Compare fixes-to-clear
against the baseline; do not assume.

### Entry 18 — a constraint, not a decision

`SpeedLimitTracker` decides *whether a limit value exists*. It must **not** decide whether a sign
is *shown* — the phone fades its HUD at a standstill, the head unit does not, and both are
defensible. If the tracker starts emitting "no limit" in order to make a sign disappear, entry 18
has been decided by accident, in a refactor, which is exactly the failure mode the register was
written to prevent.

### What is *not* consumed here

The remaining sixteen entries, four of which are out of scope by name below: the convoy protocol
(6), trip auto-detection (5), the nav vocabulary (4, 19) and the voice policy (12, 15's delivery
half). Two more are stage-2 leftovers rather than stage-3 inputs — entry 8 (the off-route
literal, whose constant half has **already landed** in `7d57087`) and entry 9 (the inline
three-candidate roll). This stage's risk section warns that all of it *"will look easy"* once
three machines are in the core, and that they are *"each larger than all of stage 3."*

## Constraints — these are settled and not up for rediscovery

- **Destination is `:shared` commonMain.** Not `app/…/map/`. It is the only destination
  reachable by the car *and* iOS, and the only test source set CI gates today. This was
  decided against the alternative in DECISION.md; do not relitigate it in the plan.
- **This is a rewrite, not a move.** commonMain has no `Dispatchers.*` at all, so I/O must be
  handed in by the caller. It *does* have a wall clock — `nowMs()` at `Angles.kt:16`, backed by
  `kotlinx.datetime.Clock` — so `System.currentTimeMillis()` has a direct replacement. Inject
  time anyway: these machines are path-dependent over timestamps, and a machine that reads the
  clock itself cannot be tested deterministically. Use `nowMs()` at the call site and pass the
  value in. That constraint is what makes the results testable, so it is a feature of this
  stage, not a tax on it. (`nowMs()` is **`internal`** to `:shared`, so the "at the call site"
  half cannot be taken literally from `app/`; see
  [How time is injected](#how-time-is-injected--and-why-the-constraints-own-instruction-cannot-be-followed-literally).)
- **Do not add an `expect`.** `Platform.kt` has four, and `CONTRIBUTING.md:26-32` states that
  wanting a fifth is the signal to push the dependency in from the platform instead. If a
  machine seems to need one, the shape is wrong.
- **Extract from the car's copy, not the phone's, wherever they have diverged.** The car
  version is strictly better on the prefetch path: the fetch is already off the fix collector
  and there is a re-entry guard. Extracting the phone version would port a stall into shared
  code. (Stage 0d fixes the phone side, so by the time this runs they should agree — verify
  that they do before choosing a source.)
- **Shape follows `CircleEvents.kt`**, the in-repo precedent: decision and wording in the
  core, delivery per platform, called from both sides. Read it before designing anything.
- **No service owns a `CoroutineScope`.** Callers drive them. A machine that starts its own
  polling will keep an Overpass request alive with the screen off, and that failure is only
  detectable in the field, days later.

## Ordering — strictly sequential

Unlike stages 1 and 2, **this stage must not be parallelised.** Each machine lands, is
replayed against the stage-0 baseline, and is confirmed before the next begins. Two machines
in flight at once makes the A/B replay uninterpretable, and the replay is the only real
verification this code has.

Order, cheapest and most isolated first:

1. `SectionAverageTracker` — one input, two outputs, the clearest boundary.
2. `CameraWarner` — latch semantics.
3. `SpeedLimitTracker` — the most entangled, and the one with the known hysteresis trap.

For each: **characterisation tests against the current behaviour first, then the move.**
Constants copied byte-for-byte. Replay route (i) for machines 1 and 2, route (ii) for 3.

## Out of scope

- Anything in `MapScreen.kt`'s state layer. Stage 4 owns that.
- The convoy protocol, trip auto-detection, the nav vocabulary and the voice policy. All four
  are duplicated across Kotlin and Swift and all four qualify under the 0g rule — but they are
  a separate programme, not this stage. Note them; do not start them.
- Adding `:shared` as a dependency of `wear/`. It is currently absent and should stay absent:
  it would pull ktor, okio, serialization and datetime into a 185-line watch APK to dedupe a
  16-line `when`.

## Work items

**Rewritten 2026-08-13 against `a90c3df`, replacing the scheduled-rewrite marker.** Every
`file:NNN` below was derived with `grep -n` against that tree, in which `MapScreen.kt` is
**1658 lines** — not the 1549 stage 2 left, so any citation carried over from stage 2's spec or
plan is wrong by roughly a hundred lines and several of this spec's own were (see
[What the rewrite corrected](#what-the-rewrite-corrected)).

### What stage 2 taught, and what it did not

Stage 2's four units (`app/src/main/java/com/jellemax/detour/map/`) settled a shape that
transfers, and one that does not.

**Transfers.** `internal object`, values in, one value out, constants as `const val` on the
object with the rationale in KDoc, and the call site reduced to *read state → call → write
state*. `NavPolicy.kt:50-70` is the reference: five parameters, one sealed `Decision`, and a
KDoc that says why the clock is a parameter. `commonMain` has **zero interfaces and 33 `object`
singletons** (`detour-shared-core` §2 test 2), so the object shape is also the house pattern at
the destination; nothing here needs an interface, a factory or an `expect`.

**Does not transfer: these three are not pure.** Stage 2's units are functions of their
arguments. All three of these carry state across fixes — five coroutine-local `var`s that no
test can reach today. So the shape is a step function, not a predicate:

```
fun onFix(state: State, …inputs…, nowMs: Long): State
```

`State` is an immutable `data class` with a default constructor, the caller holds one and
replaces it. This is the `CameraAuthority.reduce(state, action)` shape from 2d
(`CameraAuthority.kt:79-90`) applied to a fix stream rather than a gesture stream — and 2d is
the item stage 2 wrote *unwired on purpose*, so its reducer idiom arrives here already tested
and with nothing depending on it.

**One more thing stage 2 got right and this stage must copy: two functions rather than one
where the call site asks two questions.** `FollowCamera` is split into `shouldWatch` and
`shouldResume` (`FollowCamera.kt:23-36`) because collapsing them would change *when* the
blocker flags are read. Machine 3 splits for the harder version of the same reason: its I/O
cannot come with it (no `Dispatchers.*` in commonMain), so "should I fetch", "here is what I
fetched" and "here is a fix" are three calls, not one suspending call taking a fetcher lambda.
A fetcher lambda is an interface wearing a function type, and commonMain has none.

### Where they go, and what the interlock asserts

`shared/src/commonMain/kotlin/com/jellemax/detour/drive/` — a new package, **not** under
`data/`, and the path is not free to change: `convergence-2-section-readouts.md`'s
Preconditions block asserts `test -d shared/src/commonMain/kotlin/com/jellemax/detour/drive`
and `grep -rl 'SectionAverageTracker' shared/src/commonMain/kotlin | wc -l` → 1. Renaming
either the directory or the type silently ungates that interlock.

Tests go in `shared/src/commonTest/kotlin/com/jellemax/detour/drive/`, in
**`kotlin.test`** — not JUnit4. That is the one idiom that changes from stage 2: `commonTest`
runs on Kotlin/Native too, where JUnit does not exist. Read `detour-shared-core` §8 for the
rest of the house style; the two rules that bite here are *time and randomness are arguments,
never ambient* and *doubles are compared with `absoluteTolerance`*, because every assertion
below is about a km/h figure.

### How time is injected — and why the constraint's own instruction cannot be followed literally

The Constraints section says *"Use `nowMs()` at the call site and pass the value in."* Half of
that is impossible and it is worth writing down before someone spends an afternoon on it:
**`nowMs()` is `internal`** (`Angles.kt:16`), so it is visible inside `:shared` and nowhere
else. `app/` cannot call it, which is why every Android call site here already reads
`System.currentTimeMillis()` (`MapScreen.kt:811`, `:849`, `:984`; `car/SpinScreen.kt:269`,
`car/NavScreen.kt:363`).

So the resolution, which honours the constraint's *intent* exactly:

- **The machines take `nowMs: Long`.** None of them reads a clock. That is the settled part and
  it is what makes the tests deterministic.
- **Android callers keep `System.currentTimeMillis()`.** No change, no widening of `nowMs()`.
- **A commonMain or iOS-side caller may use `nowMs()`** once one exists — convergence 2's
  concern, not this stage's. If widening `nowMs()` to public is ever needed, `detour-shared-core`
  §4 is explicit that it *"is a change that needs its own justification"*: its own commit, not a
  line in one of these three.
- **Machine 2 takes no timestamp at all.** Its latch is positional, not temporal (see 3b).
  Injecting nothing is the strongest form of the constraint, and noticing that is part of the
  work.

### 3a — `SectionAverageTracker`

Extracted from `MapScreen.kt:974-1031` (the `speedSectionsRef` snapshot plus the
`LaunchedEffect(Unit)` that owns `active`, `exitGate`, `entryMs`, `accMeters` and `last`),
together with `sectionExitGate` and its two constants from `MapCameraTuning.kt:69,74,86-100`.
First because it has one input stream, one output value and no I/O of its own — the Overpass
fetch that fills `speedSections` is a *different* effect (`MapScreen.kt:841-862`) and stays
where it is.

**Signature.** One file, `drive/SectionAverageTracker.kt`:

```kotlin
internal object SectionAverageTracker {

    /** The average and the limit it is judged against, as one value. */
    data class Reading(val averageKmh: Double?, val limitKmh: Double?)

    data class State(
        val active: SpeedCameras.Section? = null,
        val exitGate: List<LatLon> = emptyList(),
        val entryMs: Long = 0L,
        val accMeters: Double = 0.0,
        val last: LatLon? = null,
        val reading: Reading = Reading(null, null),
    )

    fun onFix(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): State
}
```

**`Reading` is one value because decision 2 says so, and it also settles the `FlowWatcher`
question the Consumed decisions section raises.** The machine itself exposes **no
`StateFlow`** — "no service owns a `CoroutineScope`" applies to flows too, and a machine that
owns a flow owns a subscription. So the iOS watcher cost is not paid here at all; it is paid
once, by whichever per-surface holder convergence 2 writes, and it is **one** new subclass
rather than two because `Reading` is one type. `FlowWatcher.kt` has nine subclasses today and
none of them is a `Reading` watcher, so the cost is one — which is the number decision 2 was
choosing between and two-flows would have doubled.

`Reading` is nested rather than top-level so `SectionAverageTracker.Reading` reads as what it
is at the Swift call site, where the enclosing object's name is the only context a reader gets.

**Constants, copied byte for byte, named where they are inline today.** `SECTION_GATE_METERS`
60.0 and `SECTION_WEDGE_DEG` 75.0 move from `MapCameraTuning.kt:69,74` with their comments.
Five literals inside the effect get names for the first time and must not get new values:
the 2.0 m/s bearing-noise floor (`:990`), the 20.0 m minimum before an average is published at
all (`:1012`), the 150.0 m floor that stops the entry gate counting as the exit (`:1018`), the
`spanMeters * 1.4 + 400.0` overshoot bound (`:1020`) and the 30-minute timeout (`:1021`). Each
already carries a comment explaining it; move the comment, do not restate it.

**Who drives it.** Phone: the effect at `MapScreen.kt:975` keeps its `LaunchedEffect(Unit)`,
its `rememberUpdatedState` snapshot at `:974` and its key list, and its body collapses to
`st = SectionAverageTracker.onFix(st, …)` followed by writing `sectionAvgKmh` and
`sectionLimitKmh` (`:255-256`) from `st.reading`. **The two `mutableStateOf`s stay two `remember`s
for now** — collapsing them into one is a state-ownership change and stage 4 owns that; what
this stage guarantees is that they can no longer disagree, because both are assigned from one
`Reading` in one statement. Car: **nothing.** iOS: **nothing.**

**There is no car copy of this machine, and the spec's Scope over-promises when it says the
car's copies are deleted after each extraction.** Verified:
`grep -rn 'Section\|sectionAvg\|speedSections' app/src/main/java/com/jellemax/detour/car/`
returns **zero hits**, and `convergence-2-section-readouts.md`'s Preconditions assert exactly
that (`grep -rl '\.sections' $CAR | wc -l` → 0). The car discards `result.sections` today; it
does not reimplement them. So **3a has no `3a′`.** Its follow-up is the car *gaining* a readout,
which is convergence 2's item and which §C.1's last line forbids sharing a commit with this
extraction. Do not invent a deletion to satisfy the pattern.

**Characterisation tests, before the move**, in `SectionAverageTrackerTest`. Write them against
the current inline behaviour by transcribing it, run them against the extracted machine, and
expect them to pass unchanged — a failure means the transcription changed something.

1. Arms on entering one end while the far end is inside the wedge; the returned `exitGate` is
   the *far* end, never the one just passed.
2. Does not arm below 2.0 m/s however good the geometry is — the bearing is noise there
   (`:987-990`).
3. Does not arm with a null heading.
4. Nearest match wins when two sections share a gate location, not the first in the list
   (`:991-998`) — this is the `15682532` / `15685856` case `routes/README.md` documents.
5. No average until 20 m has accumulated; then `averageKmh` is
   `(accMeters / 1000) / elapsedHours` to `absoluteTolerance = 1e-9`.
6. Only the far gate ends it: a fix back at the entry gate on the very next fix does **not**
   exit, because of the 150 m floor.
7. Overshoot ends it at `spanMeters * 1.4 + 400.0`; the timeout ends it at 30 min.
8. On exit, **both** halves of `Reading` go null in the same step (`:1026-1027`).
9. Re-arms immediately into a second section whose gate is the first one's exit node — the
   back-to-back transition at 6.36 km that `routes/README.md` says *"has still never been
   observed working."*
10. **A wrong-direction transit still arms**, and the test says so in its name.
    `routes/README.md`'s own measurement is that at `public-trajectcontrole-reverse.txt`'s entry
    gate the far end bears 276° against a heading of 284° — 8° off, deep inside the 75° wedge.
    Pin the behaviour, not the wish; a test asserting a refusal would fail and would be wrong.
11. **`Reading.limitKmh` is carried straight through from `Section.maxspeedKmh`** — one test with
    a hand-built `Section(maxspeedKmh = 120.0)` and one with `maxspeedKmh = null`. This is the
    only place the posted-limit half is exercised at all; see
    [the replay cannot reach the limit comparison](#the-replay-cannot-reach-the-limit-comparison).

**Replay and the compared quantity.** Route (i). Use **`public-trajectcontrole.txt`**, not the
personal file, and re-record the baseline for it first: as of 2026-08-13 the fixture carries a
**4.72 km / 170 s lead-in** west of the west gantry precisely so the Overpass prefetch can land
before the gate (the earlier revision put the gate at line 0 and could not arm at all —
`routes/README.md` § *The lead-in*). The quantity compared is **the count of fixes on which the
section chip is present, the fix index of the first and last such fix, and the settled
`averageKmh`** — the same column the Preconditions block already counts
(`trajectcontrole-*-events.tsv`, field 5). Two of those three are now *asserted* rather than
observed, which is what makes this fixture worth the re-record: the west gate is at **line 170**,
the east at **line 457**, and a correct average settles at **100.0 km/h**. Anything else is a
defect, not a baseline.

### 3b — `CameraWarner`

Extracted from `MapScreen.kt:917-968` and `car/NavScreen.kt:380-399`. Second because the latch
is the whole machine and there is very little else in it.

**Signature.** `drive/CameraWarner.kt`:

```kotlin
internal object CameraWarner {

    /** How far off the heading a camera may lie and still count as ahead. */
    const val AHEAD_WEDGE_DEG = 45.0

    /** Over the posted limit by this much before a camera is worth interrupting for. */
    const val OVER_LIMIT_KMH = 3.0

    data class State(val warnedAt: LatLon? = null)

    sealed interface Outcome {
        data object Silent : Outcome
        /** [text] is the wording. Delivery — tone, speech, toast — is the caller's. */
        data class Warn(val at: LatLon, val text: String) : Outcome
    }

    data class Step(val state: State, val outcome: Outcome)

    fun onFix(
        state: State,
        cameras: List<SpeedCameras.Camera>,
        at: LatLon,
        headingDeg: Double?,
        speedKmh: Double,
        limitKmh: Double?,
    ): Step
}
```

**No `nowMs`, and that is a finding rather than an omission.** The latch is
`ahead.at != warnedAt` (`MapScreen.kt:946`, `car/NavScreen.kt:390`) — a *position* compared
against a position, cleared when no camera is in range (`:936`, `:385`). Nothing in this machine
measures an interval, so there is no timestamp to inject and no cooldown to test. A reviewer
looking for the injected clock will not find one; this paragraph is why.

**`Outcome.Warn` carries the wording and nothing about delivery** — entry 15, and the phone's
own comment at `MapScreen.kt:960-963` already says the literal is waiting for this machine to
declare it. Callers keep their delivery verbatim: the phone's `toneGen.startTone` + 
`announceAloud` (`:947`, `:964`, with `announceAloud` defined at `:711`), the car's tone +
`speak` + `showToast` (`:391-397`). **`CameraWarner` must not know about tones, speech or
toasts**, so `Step` is returned rather than a callback being passed in.

**Both literals are hoisted, not re-declared.** `AHEAD_WEDGE_DEG` replaces the `45.0` at
`MapScreen.kt:933` and `car/NavScreen.kt:382`; `OVER_LIMIT_KMH` replaces the `+ 3.0` at
`MapScreen.kt:945` and `car/NavScreen.kt:389`. Entry 13 exists to prevent a third copy inside
the machine while the two call-site literals survive, so the acceptance check is
`grep -rn '45\.0\|+ 3\.0' app/src/main/java/com/jellemax/detour/{ui,car}/` returning nothing
for these two uses. **The HUD's `+5` is not this machine's** — `MapHud.kt:184`,
`car/CarMapRenderer.kt:635` and `wear/…/MainActivity.kt:140`, all three agreeing, and `wear/`
does not depend on `:shared` at all (`detour-shared-core` §1), so it cannot go to commonMain
without stranding the watch's copy. It stays in `app/`.

**Who drives it.** Phone: the `LaunchedEffect(Unit)` at `:924` keeps its three
`rememberUpdatedState` snapshots (`:917-919`) and its key list; the body becomes one `onFix`
call and a `when` on `Step.outcome`. Car: `checkCameras` at `NavScreen.kt:361` keeps the
prefetch half (`:362-379`) and replaces the warning half. iOS: **nothing in this stage** — the
machine is callable from Swift the moment it lands, but no readout or audio path is this stage's
work.

**The limit is resolved by the caller, and that is what settles entry 1.** The machine takes one
`limitKmh: Double?`. The phone passes `navProgress?.speedLimitKmh ?: ambientSpeedLimitKmh`
(`:944`); the car passes `progress?.speedLimitKmh` alone (`:388`) and gains the fallback when it
gains machine 3, not before. Keeping the resolution at the call site is what makes "does this
surface have an ambient sign to fall back on" a per-surface fact instead of a branch inside
shared code.

> **Correction to the Consumed decisions section, entry 1: §B1 is already fixed.** That section
> says *"§B1's fix comes first, in its own commit"* and cites `ambientSpeedLimitKmh` as never
> reset. It **is** reset — `MapScreen.kt:791-803`, a `LaunchedEffect(navigating)` that clears
> both the limit and the misses counter, with a comment at `:799` crediting
> `car/SpinScreen.kt:117-121` as the source. It landed in **`bac833a`**, and
> `15-divergence-register.md:1867` records it as `RESOLVED`. **Do not schedule that commit.**
> Entry 1's *substance* is unaffected: the phone's fallback is still what survives, and the car
> still has no ambient sign to fall back on.

**Characterisation tests, before the move**, in `CameraWarnerTest`:

1. Silent when `limitKmh` is null, at any speed — *"we can't judge too fast"* (`:916`).
2. Silent when at or under the limit; warns once past `limit + OVER_LIMIT_KMH`.
3. **The boundary, stated:** exactly `limit + 3.0` does **not** warn, because the test is `>`.
4. One warning per camera: a second fix at the same camera, still too fast, returns `Silent`
   with the state unchanged.
5. Re-arms after the camera leaves range — `Silent`, and `state.warnedAt` back to null.
6. Beyond `SpeedCameras.WARN_METERS` (400.0, `SpeedCameras.kt:53`) nothing is a candidate.
7. A camera behind you is rejected by the wedge; the boundary at exactly `AHEAD_WEDGE_DEG`
   resolves one way and the test names which.
8. **A null heading skips the wedge entirely and warns on distance alone** (`:932-933`) — the
   stopped-phone case, and the one branch a replay never reaches because `MockService` always
   derives a bearing.
9. Nearest camera wins when two are in range (`:934`).
10. A *different*, nearer camera appearing while still latched on the first one **does** warn —
    the latch is per-camera, not a global mute.

**Replay and the compared quantity.** Route (i), but **the personal `trajectcontrole.txt`**, not
the public file, and this is a deliberate exception to preferring public data:
`routes/README.md` measures **5** `highway=speed_camera` nodes inside `WARN_METERS` of that
line and reports the equivalent column for the `public-` files as *"not reported rather than
guessed"* because Overpass 504'd. A camera-warning A/B against a fixture whose camera count is
unknown compares nothing. The quantity is **the number of `"Speed camera ahead"` announcements
and the fix index of each**, against the stage-0 baseline. If Overpass answers before this item
starts, run `routes/README.md`'s stated bbox for `public-trajectcontrole`
(4.4286,50.8587 → 4.6050,50.8693) and switch.

**3b′ — delete the car's copy, one commit behind.** `car/NavScreen.kt:380-399` goes; the
`warnedCameraAt` field at `:127` becomes a `CameraWarner.State`. The prefetch half above it
(`:362-379`) is **not** touched — it is the better copy and it is machine 3's business, not
machine 2's. `README.md:383-385` claims the head unit has *"the same … camera warnings as the
phone"*, which becomes true only once the car also has the ambient fallback, i.e. after 3c′ —
so that one-line edit belongs to **3c′**, not here.

### 3c — `SpeedLimitTracker`

Extracted from `car/SpinScreen.kt:265-296` — **the car's copy, per the Constraints section and
`detour-shared-core` §6** — with the phone's `MapScreen.kt:791-835` diffed against it rather
than transcribed. Last, because it is the only one of the three whose I/O has to be split out,
and the only one carrying a known tuning trap.

**Signature.** `drive/SpeedLimitTracker.kt`. Four functions, because commonMain cannot do the
fetch:

```kotlin
internal object SpeedLimitTracker {

    const val MIN_MPS = 2.0                 // car: LIMIT_MIN_MPS  (SpinScreen.kt:57)
    const val FETCH_MARGIN_M = 500.0        // car: LIMIT_FETCH_MARGIN_M    (:52)
    const val FETCH_THROTTLE_MS = 10_000L   // car: LIMIT_FETCH_THROTTLE_MS (:53)
    const val MISSES_TO_CLEAR = 3           // car: LIMIT_MISSES_TO_CLEAR   (:61)

    data class State(
        val ways: List<RoadRoulette.SpeedLimitWay> = emptyList(),
        val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L,
        val misses: Int = 0,
        val limitKmh: Double? = null,
    )

    /** Whether this fix is far enough from [State.waysCenter] and late enough past
     *  [State.lastFetchMs] to be worth a prefetch. The fetch is the caller's. */
    fun needsWays(state: State, at: LatLon, nowMs: Long): Boolean

    /** Stamp the attempt. Called *before* the fetch, so a failure is throttled too. */
    fun fetchStarted(state: State, nowMs: Long): State

    /** Fold a completed prefetch in. An empty [ways] is a network blip: keep what
     *  we hold rather than flickering the sign off. */
    fun withWays(state: State, ways: List<RoadRoulette.SpeedLimitWay>, center: LatLon): State

    /** The per-fix snap and the three-miss clear hysteresis. Clock-free. */
    fun onFix(state: State, at: LatLon, headingDeg: Double?, speedMps: Double): State

    /** Crossing into or out of navigation. Clears the sign *and* the miss counter. */
    fun reset(state: State): State
}
```

**Why `fetchStarted` is separate from `needsWays`.** Both copies stamp the throttle *before*
awaiting the network (`SpinScreen.kt:277`, `MapScreen.kt:815`) so a failing mirror is throttled
like a succeeding one — the car says so at `:275-276`. Folding the stamp into `needsWays` would
make a query function mutate; folding it into `withWays` would stamp on *completion* and turn a
30-second timeout into a 30-second-plus-10 gap. Three calls preserve the ordering the comment
already argues for.

**`needsWays` takes no re-entry guard, and the caller keeps its own.** The car's
`limitFetchJob?.isActive != true` (`SpinScreen.kt:272`) is a `Job` — an Android coroutine
handle — and it cannot cross into commonMain. Extracting *the throttle* into shared code and
leaving *the in-flight guard* at the call site is the split the constraint's "I/O must be handed
in" actually implies. The phone has no such guard today; giving it one is stage 0d's item, not
this one, and 3c must not quietly add it (see the trap below).

**Entry 18 is a constraint on `State`, and the shape enforces it.** `limitKmh` answers *does a
posted limit exist here*. There is no `visible` field, no `shouldShowSign`, and nothing about a
standstill. The phone fades its HUD at rest and the head unit does not; both are defensible and
neither is this machine's call. If a later change wants a sign to disappear, it changes the
readout, not this tracker.

**Who drives it.** Phone: `MapScreen.kt:791-835`. The `LaunchedEffect(navigating)` key stays, and
so does `if (navigating) return@LaunchedEffect` at `:804`; the pre-collect clearing at `:802-803`
becomes `st = SpeedLimitTracker.reset(st)` and the collector body becomes
`needsWays` → `withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }` → `withWays`
→ `onFix`, writing `ambientSpeedLimitKmh` (`:244`) from `st.limitKmh`. `speedLimitWays`,
`speedLimitWaysCenter` and `speedLimitMisses` (`:245-250`) are deleted, since the machine holds
them. Car: `updateSpeedLimit` at `SpinScreen.kt:265` the same way, keeping its
`lifecycleScope.launch` and its `limitFetchJob` guard. iOS: **nothing in this stage.**

> **The hysteresis trap, restated as an instruction.** Entry 2 warns that the three-miss rule
> was tuned against a fix stream that *had* the drops the phone's inline `withContext` causes.
> **Verified 2026-08-13: stage 0d has not landed** — `MapScreen.kt:816` still awaits Overpass
> inside the `lastFix.collect` block. So this item runs with the trap live. Do not "fix" it
> here: moving the phone's fetch off the collector changes how many fixes reach `onFix`, which
> changes fixes-to-clear, in the same commit as the extraction. Extract with the stall in place,
> compare fixes-to-clear against baseline, and let 0d change it afterwards with the machine
> already under test.

**Characterisation tests, before the move**, in `SpeedLimitTrackerTest`:

1. A successful snap sets `limitKmh` and zeroes `misses`.
2. One miss keeps the sign; two keep it; **the third clears it** — and the test asserts the
   count, not just the end state.
3. After a clear, a fresh snap re-establishes the sign and `misses` is back to zero.
4. **Below `MIN_MPS` the fix is skipped entirely and does not count as a miss.** Both copies
   return before the snap (`MapScreen.kt:807`, `SpinScreen.kt:266`), so a long wait at a light
   must not clear the sign. This is the single easiest thing to get wrong in the move, because
   an early `return@collect` becomes an early `return state` and it is invisible in a diff.
5. `needsWays` is false inside `SPEED_PREFETCH_RADIUS_M - FETCH_MARGIN_M` of `waysCenter`
   (1500.0 − 500.0, `RoadRoulette.kt:255`), and true outside it.
6. `needsWays` is false inside `FETCH_THROTTLE_MS` of `lastFetchMs` however far you have moved;
   the boundary at exactly the throttle resolves one way and the test names which.
7. `needsWays` is true on a virgin `State` — `waysCenter` null must mean "no area held", not
   "distance zero". Both copies spell this `?: Double.MAX_VALUE` (`:809`, `:268`).
8. `withWays` with an empty list is a no-op on `ways` **and** on `waysCenter`.
9. `reset` clears `limitKmh` and `misses` and **keeps `ways` and `waysCenter`** — the held area
   is still valid across a navigation boundary; only the derived sign is stale. `bac833a` reset
   exactly two of the five fields and this test is what stops the extraction resetting five.
10. The snap is delegated, not reimplemented: one test that a `SpeedLimitWay` aligned with the
    heading wins over a nearer crossing one, asserting `RoadRoulette.snapSpeedLimitKmh`
    (`RoadRoulette.kt:295`) is still doing the work.

**Replay and the compared quantity.** Route (ii) — **`urban-limits.txt`**, which is the only
route with a baseline *and* a measured posted-limit ladder. `public-stop-start.txt` is the
public candidate and is **not** a substitute yet: `routes/README.md` marks its eight `maxspeed`
values as *"carried from the research note and not re-verified here"*, and separately notes it
auto-ends a trip mid-file, so a baseline copied across would be wrong. The quantity is
**fixes-to-sign-clear at each limit boundary, and the count of distinct limit values the run
reports** — against baseline, **not against screenshots** (this stage's own Risks section).

**3c′ — delete the car's copy, one commit behind.** `car/SpinScreen.kt:265-296`, the four
constants at `:52-61` and the two fields at `:97-98`. The reset at `:117-121` stays as a call to
`SpeedLimitTracker.reset`, keeping its comment — it is the copy the phone's fix was taken from
and the comment is the design record. The car's `updateHud` call at `:150` reads
`state.limitKmh`. **This is also the commit that gives the car its ambient fallback**, which is
what makes `README.md:383-385` true, so the one-line README edit lands here.

### The replay cannot reach the limit comparison

**OSM relation `15682532` carries no `maxspeed` tag.** Only its two `device` nodes do — both
`maxspeed=120` — and `SpeedCameras.parseSection` (`SpeedCameras.kt:109`) reads `maxspeed` from
the *relation's* tags only. So `Section.maxspeedKmh` is **null** for that relation, which means
`Reading.limitKmh` is null for the whole transit, and **no replay of any fixture of this section
can exercise the over/under-posted-limit comparison.** This is equally true of the personal
`trajectcontrole.txt` and of both public files; `routes/README.md` records it twice and it is not
a fixture defect to be fixed by picking a better route — it is what the OSM data says.

Consequences for this stage, stated so nobody looks for the missing baseline column:

- The A/B for 3a compares **the running average and its lifetime**, never the limit half. A
  baseline row showing a null section limit is correct.
- Test 11 of `SectionAverageTrackerTest` is therefore the **only** coverage of
  `Reading.limitKmh`, and it is a unit test with a hand-built `Section`. It is not optional and
  it is not redundant with the replay; it is the replacement for it.
- The same applies to `CameraWarner`'s `limit + 3.0` comparison whenever the limit comes from a
  section rather than from `navProgress` or the ambient sign. Tests 2 and 3 of `CameraWarnerTest`
  carry it; the replay carries the ambient path only.
- If a section-with-`maxspeed` fixture is ever wanted, `17-public-trace-datasets.md` §3.3 lists
  relation **16251379** as *unnamed, `maxspeed=120`* on the relation itself. Generating a second
  synthetic route over it is a §7.1 re-run — but it is a **new fixture and a new baseline**, so
  it is not this stage's work.

### Issue #22: extract for testability, do not extract a theory

maxke24/Detour#22 — the average-speed readout vanishing a few hundred metres in and never
re-arming — is the defect machine 1 is suspected of carrying, and **these work items
deliberately encode no cause.** The parser theory is refuted (`5eb03bb`,
`SpeedCameraSectionTest` at `shared/src/commonTest/kotlin/com/jellemax/detour/data/ParsingTest.kt:145`),
and the Blocker section above lists two further facts that fit no single mechanism. Assuming a
cause now would produce characterisation tests that bake in the wrong thing in either direction:
encode the clear as intended and the bug is preserved, encode a fix and the tests assert
behaviour the code has never had.

What the shape above buys instead, which is the whole point of doing it in this order:

- **`State` becomes a value, so the suppression becomes observable.** Tests 6 through 9 of
  `SectionAverageTrackerTest` pin *every* transition that can null `reading` — exit, overshoot,
  timeout — as a step function of one fix. Whichever one fires at fix 81 on the recorded run,
  a test can drive the same sequence offline and see it.
- **It also isolates what the machine cannot cause.** If none of those transitions reproduces
  the clear, the cause is outside the machine — the collector restarting, the
  `rememberUpdatedState` snapshot, or `speedSections` being emptied — and that is stage 4's
  territory, not a tracker bug. Narrowing that is a result.
- **The concurrent replay may hand over the diagnosis.** A run of the lead-in fixture with
  Overpass answering settles what `speedSections` actually held at fix 73 and fix 88, which is
  the measurement the Blocker section says has never been taken. **If it lands before 3a starts,
  read it first** — a confirmed cause turns test 6-9 from a net into an assertion. If it does
  not, 3a proceeds anyway: extracting a machine you cannot yet diagnose is exactly how you get
  able to diagnose it.

Either way the ordering from the Blocker section holds: **diagnose in its own commit, on the
tracker's state, not the parser** — and if the diagnosis arrives after the extraction, its fix
is a commit behind the extraction, never inside it (`DECISION.md:411`: never *"an extraction
*and* the bug it reveals"*).

### Commit sequence

Strictly sequential, each replayed and confirmed before the next begins. Eight commits:

| # | Commit | Touches |
|---|---|---|
| 1 | `test(drive): characterise the section average tracker` | `commonTest` only — tests against the transcribed inline behaviour |
| 2 | `refactor(drive): move the section average tracker to shared` | `drive/SectionAverageTracker.kt`, `ui/MapScreen.kt`, `ui/MapCameraTuning.kt` |
| 3 | `test(drive): characterise the camera warner` | `commonTest` only |
| 4 | `refactor(drive): move the camera warner to shared` | `drive/CameraWarner.kt`, `ui/MapScreen.kt` |
| 5 | `refactor(car): use the shared CameraWarner instead of a second copy` | `car/NavScreen.kt` only |
| 6 | `test(drive): characterise the speed limit tracker` | `commonTest` only |
| 7 | `refactor(drive): move the speed limit tracker to shared` | `drive/SpeedLimitTracker.kt`, `ui/MapScreen.kt` |
| 8 | `refactor(car): use the shared SpeedLimitTracker instead of a second copy` | `car/SpinScreen.kt`, `README.md` |

There is no commit 2′: 3a has no car copy to delete (see 3a). Commits 5 and 8 trail their
extraction by exactly one, so a car regression and a phone regression never share a bisect.
Conventional Commits, **no trailers of any kind**.

### What the rewrite corrected

Line-number and status errors found while writing this section, all measured against `a90c3df`.
Listed because this spec's Status table says citations have been wrong before, and because
leaving a known-wrong number in place is worse than the number never having been there.

| Claim as written | Where it actually is |
|---|---|
| `nowMs()` at `Angles.kt:15` (Constraints) | `Angles.kt:16` — and it is **`internal`**, so `app/` cannot call it |
| the two section `mutableStateOf`s at `MapScreen.kt:252-253` (entry 11) | `:255-256`; `:252` is `speedSections` and `:253` is a comment. `MapHud.kt:180-181` is correct |
| `ambientSpeedLimitKmh`'s producer gated at `MapScreen.kt:733-734` (entry 1) | the `LaunchedEffect(navigating)` is `:791`, the gate `:804`; `:733-734` is `navVoice.stop()` |
| the `+3.0` at `MapScreen.kt:870` and `car/NavScreen.kt:414` (entry 13) | `MapScreen.kt:945` and `car/NavScreen.kt:389`; the `45.0`s are `:933` and `:382` |
| `CONTRIBUTING.md:23-32` on the `expect` ceiling (Constraints) | `:26-32`. Note that CONTRIBUTING's own text says *"only three things"* and *"a fourth"* while `Platform.kt` has **four** `expect`s — so the spec's "a fifth" is the right operational reading and CONTRIBUTING is the stale document |
| `DECISION.md:394-400` for *never an extraction and the bug it reveals* (entry 1) | `DECISION.md:411`; `:394-400` is phase 4's pick-exactly-one |
| entry 1: *"§B1's fix comes first, in its own commit"* | **already done** — `bac833a`, `MapScreen.kt:791-803`, recorded `RESOLVED` at `15-divergence-register.md:1867` |
| Scope: *"the car's copies are deleted … one commit behind each extraction"* | true for machines 2 and 3, **false for machine 1** — the car has no section code at all |
| `detour-shared-core` §6's phone citations (`MapScreen.kt:735-767`, `:773-794`) | pre-stage-2 numbers; now `:791-835` and `:841-862`. Its **car** citations are still correct |
| Done criteria: *"`MapScreen.kt` roughly 1300-1400 lines"* | **1500-1560.** The file is 1658 at `a90c3df`, not the 1549 that figure assumed, and the three machines are ~168 lines against ~36 of replacement call site. Corrected in place with the arithmetic |

Verified correct and worth not re-checking: `ios.yml:64-68`, `README.md:383-385`,
`car/SpinScreen.kt:117-121`, `MapHud.kt:184`, `car/CarMapRenderer.kt:635`,
`wear/…/MainActivity.kt:140`, `5eb03bb` (the task brief's `ce67a77` is the **pre-rewrite** SHA
of the same commit and lives only on `backup/pre-rewrite-20260813`), and every assertion in the
Preconditions block above.

## Done criteria and verification

- [ ] Three machines in `shared/src/commonMain/.../drive/`, with `commonTest` tests.
- [ ] `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` green.
- [ ] `Platform.kt` still has exactly four `expect` declarations.
- [ ] No `Dispatchers.*` anywhere in commonMain, and no machine reads the clock itself.
- [ ] Car copies deleted, each in its own commit, one behind its extraction — **two of them, not
      three.** `CameraWarner` and `SpeedLimitTracker` have car copies; `SectionAverageTracker`
      does not (the car discards `result.sections`). See [3a](#3a--sectionaveragetracker).
- [ ] Each machine replayed against the stage-0 baseline with the comparison recorded.
- [ ] `MapScreen.kt` roughly **1500-1560** lines. **Re-derived 2026-08-13 and this is a
      correction: the figure was 1300-1400.** That was written when the file was 1549 lines; it is
      **1658** at `a90c3df`. The three effect bodies are 45 + 52 + 58 = 155 lines
      (`:791-835`, `:924-968`, `:975-1031`), plus ~13 lines of state and snapshot declarations
      (`:244-250`, `:255-256`, `:917-919`, `:974`), against roughly 36 lines of replacement call
      site — a net reduction near 130, not 250. `sectionExitGate` comes out of
      `MapCameraTuning.kt`, not this file. **If the plan hits 1300-1400 it removed something this
      stage did not authorise**; check for state-layer changes, which are stage 4's.

Verification tier: **full manual checklist plus per-machine replay A/B.** This is the stage
the baseline was recorded for.

## Stop-point C — the default stop

**Stop here unless there is a specific reason not to.** The cost-to-value curve turns sharply
down after this point.

At this stop-point: the code that produces field bugs has tests; iOS can gain three features
it has never had; the car's substantive duplication is down to one or two items; the file is
under 1400 lines; and every remaining option is still open.

If halting here, record in DECISION.md that stage 4 was considered and deferred, and why.

## Risks

- **The hysteresis trap, again.** Stage 0d already touched the fix stream feeding the ambient
  limit. Machine 3 touches it a second time. Compare fix-counts-to-sign-clear against
  baseline, not screenshots.
- **Path dependence.** These machines are not pure; the same fix sequence in a different order
  gives a different result. Characterisation tests capture behaviour, not correctness — a test
  that passes proves you preserved what was there, including any bug.
- **Scope pull.** Once three machines are in the core, the convoy protocol and trip detection
  will look easy. They are each larger than all of stage 3. They are out of scope by name.
- **One-way door.** Code in commonMain is consumed by iOS; backing it out later is a
  cross-language revert. This is the first genuinely hard-to-reverse stage in the chain, which
  is another reason it is sequential.

## Next stage

→ [`stage-4-state-ownership.md`](stage-4-state-ownership.md)

**Before writing stage 4's plan:**

1. Run stage 4's **Preconditions** block.
2. Stage 4 is a **decision gate**, not a work plan. It has no work items by design: the choice
   it makes — Compose state holders versus a targeted reducer — is not decidable until this
   stage has finished, and stage 2's unwired `CameraAuthority` exists precisely so that the
   choice can be made against real code rather than two proposals.
3. Invoke `superpowers:brainstorming` against
   [`stage-4-state-ownership.md`](stage-4-state-ownership.md) to make the decision and produce
   its work items, then `superpowers:writing-plans`, then
   `superpowers:subagent-driven-development`.

Answering "should we even do stage 4" is a valid outcome of that brainstorm, and stop-point C
above is what makes "no" a defensible answer.
