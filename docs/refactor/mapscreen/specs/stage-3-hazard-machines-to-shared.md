# Stage 3 — Road-hazard machines into the shared core

## Status

| | |
|---|---|
| **Detail level** | Intent + constraints. **The Work items section requires a rewrite before use** — see below. |
| **Prerequisite** | [Stage 2](stage-2-pure-extractions.md) complete |
| **State** | not started — **blocked on evidence.** The blocking product call (register decision 2) was made 2026-08-12, yes to all three surfaces (`5613e59`); see [Consumed decisions](#consumed-decisions). Route (i) now exists (`5fc8e90`) and the stage-0 baseline landed (`e313a28`), but **the baseline captured no section events**: both Overpass mirrors refused connections throughout that run, so the average-speed chip machine 1 is measured against was never observed. Needs a 17-minute re-run of `trajectcontrole.txt` once Overpass answers. 12 pass, 1 fail on 2026-08-12 |
| **Preconditions captured** | 2026-08-11; re-run 2026-08-12 against `5613e59` — 11 pass, 1 fail (the missing baseline directory). An earlier run read 9 pass with 2 informational: `chain-status.sh` could not judge `# expect 1  (camera-warn latch)`, because it treated the explanatory parenthetical as part of the expected value and silently ungated the assertion. Fixed in the script rather than by rewording the specs, since seven assertions across stages 2 and 3 were affected |
| **Chain** | [design](00-chain-design.md) · [roadmap](../DECISION.md) · [register](../15-divergence-register.md) · prev: [stage 2](stage-2-pure-extractions.md) · next: [stage 4](stage-4-state-ownership.md) · consumed by: [convergence 2](convergence-2-section-readouts.md) |

> **Scheduled rewrite.** This spec fixes the goal, the destination and the constraints, which
> are settled. It deliberately does **not** fix the internals, because how these machines
> should be shaped depends on what stage 2's extractions teach us about pulling decisions out
> of this screen. Rewrite the Work items section via `superpowers:brainstorming` after stage 2
> lands, then write the plan. This marker is a scheduled decision, not an unfinished section.

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
cat tools/mocklocation/baseline/trajectcontrole-*-events.tsv 2>/dev/null \
  | awk -F'\t' 'NR>1 && $5!="0" && $5!="" && $5!="-" {n++} END{print n+0}'   # expect >= 1
```

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
  as two `mutableStateOf`s and passes them as two arguments (`MapScreen.kt:252-253` →
  `ui/MapHud.kt:180-181`); exporting that shape would cost two watcher subclasses and let the two
  values disagree across a recomposition.

**What this stage does not do:** the car and iOS *readouts*. The register is explicit that they
are feature work after the tracker lands —
[`convergence-2-section-readouts.md`](convergence-2-section-readouts.md) owns them, and §C.1's
last line forbids them sharing a commit with the extraction they depend on.

### Entry 1 — the camera chime's ambient fallback: keep it, **but fix §B1 first**

Machine 2 cannot be written without knowing whether it takes one limit or two. The answer is
two: keep the phone's fallback to the ambient sign, because a camera warning that goes silent on
an untagged road is a warning you cannot trust, and the car's omission has no written rationale
(`git blame` in entry 1 shows it was never decided).

**The ordering inverts what a naive reading of "extract the phone's version" would produce.**
`ambientSpeedLimitKmh` is never reset: its producer is gated off while `navigating`
(`MapScreen.kt:733-734`) and the only writers are inside that collector, so the value the chime
reads is frozen at whatever the sign said when navigation began — and it is still stale *after*
navigation ends, because the HUD switches back to it. So the copy being kept is a copy with a
defect, and §B1's fix comes first, in its own commit. `DECISION.md:394-400`: never an extraction
and the bug it reveals. The car already has the reset and says why
(`car/SpinScreen.kt:117-121`), which is why entry 1's answer is *the phone's fallback plus the
car's reset* rather than either copy wholesale.

`README.md:383-385` claims the head unit has *"the same … camera warnings as the phone"*, which
is false today and becomes true when machine 2 lands. That one-line edit belongs to the commit
that makes it true.

### Entry 13 — the `+3.0`, `45.0` and `+5` literals: **both values survive, hoisted**

Nothing to decide: the three `+5`s and both `+3.0`s agree today, re-verified against this tree
(`MapHud.kt:184`, `car/CarMapRenderer.kt:635`, `wear/…/MainActivity.kt:140`; `MapScreen.kt:870`,
`car/NavScreen.kt:414`). `CameraWarner` should own the chime threshold
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
  handed in by the caller. It *does* have a wall clock — `nowMs()` at `Angles.kt:15`, backed by
  `kotlinx.datetime.Clock` — so `System.currentTimeMillis()` has a direct replacement. Inject
  time anyway: these machines are path-dependent over timestamps, and a machine that reads the
  clock itself cannot be tested deterministically. Use `nowMs()` at the call site and pass the
  value in. That constraint is what makes the results testable, so it is a feature of this
  stage, not a tax on it.
- **Do not add an `expect`.** `Platform.kt` has four, and `CONTRIBUTING.md:23-32` states that
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

> **Rewrite this section before use.** After stage 2 lands, run
> `superpowers:brainstorming` for this stage with its extractions in hand, and replace this
> section with concrete work items. The constraints above are inputs to that conversation and
> are not to be revisited; the machine shapes, signatures and commit boundaries are its output.

What the rewrite must produce, per machine: the signature, how time is injected, who drives it
on each surface, the characterisation tests written before the move, the replay route and the
specific quantity being compared against baseline, and the follow-up commit that deletes the
car's copy.

## Done criteria and verification

- [ ] Three machines in `shared/src/commonMain/.../drive/`, with `commonTest` tests.
- [ ] `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` green.
- [ ] `Platform.kt` still has exactly four `expect` declarations.
- [ ] No `Dispatchers.*` anywhere in commonMain, and no machine reads the clock itself.
- [ ] Car copies deleted, each in its own commit, one behind its extraction.
- [ ] Each machine replayed against the stage-0 baseline with the comparison recorded.
- [ ] `MapScreen.kt` roughly 1300-1400 lines.

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
