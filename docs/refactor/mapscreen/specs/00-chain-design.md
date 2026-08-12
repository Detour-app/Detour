# Staged spec chain for the MapScreen split — design

This is the design document for *how the work is specified*, not for the work itself.
The work is described in [`../DECISION.md`](../DECISION.md), which is the roadmap this
chain implements. Read that first.

## Why a chain rather than one spec

`MapScreen.kt` is 3193 lines of live, GPS-driven navigation UI. The investigation behind
DECISION.md established two things that rule out a single spec:

1. **Each stage changes the ground the next stage stands on.** Stage 1 moves 1355 lines out
   of the file. Every line number in a stage-2 spec written today is wrong the moment stage 1
   lands. A single spec would be stale before it was half executed.
2. **Later stages depend on evidence that does not exist yet.** Stage 4 asks us to choose
   between two state-ownership patterns. Stages 1–3 will tell us which one the code wants.
   Choosing now would be guessing dressed as planning.

So: one spec per stage, written ahead of time, each one carrying an explicit contract about
how it goes stale and what to do about it.

## The chain

```
structure axis                            convergence axis
MapScreen.kt's shape and state            cross-surface behaviour
(DECISION.md)                             (15-divergence-register.md §C.1)

stage-0-verification-baseline.md          convergence-1-cheap-fixes.md
        ↓ (re-verify, then writing-plans)          ↓
stage-1-mechanical-split.md               convergence-2-section-readouts.md
        ↓                                          ↓
stage-2-pure-extractions.md               convergence-3-voice-policy.md
        ↓
stage-3-hazard-machines-to-shared.md ───→ its SectionAverageTracker is what
        ↓   ← default stop-point              convergence 2 reads — the one
stage-4-state-ownership.md                    edge between the columns
            ← decision gate, optional
```

The single arrow between the columns is the whole interlock: stage 3's
`SectionAverageTracker` is what convergence 2 reads, and stage 3's spec carries a **Consumed
decisions** section naming the five register entries it consumes. There is no other edge, by
design — see the next section.

Each spec ends with a **Next stage** footer that names its successor and instructs the
implementor to re-verify that successor's preconditions and then invoke the
`superpowers:writing-plans` skill to turn it into an implementation plan. The plan, not the
spec, is what `superpowers:subagent-driven-development` executes.

The full loop per stage:

```
read spec → run its preconditions → stale? re-brainstorm the stage
                                  → current? writing-plans → plan
plan → subagent-driven-development → commits → verification checklist
     → update the spec's Status block → move to the next spec's footer
```

## The two axes

The chain started as one column. [`../15-divergence-register.md`](../15-divergence-register.md)
added the second, and the reason it is a second column rather than five more stages is the most
important structural decision in this directory.

**What each axis owns.**

| | Structure axis (`stage-*.md`) | Convergence axis (`convergence-*.md`) |
|---|---|---|
| Roadmap | [`../DECISION.md`](../DECISION.md) | [`../15-divergence-register.md`](../15-divergence-register.md) §C.1 |
| Subject | `MapScreen.kt`'s shape, its state, and which module owns a decision | What each of the four surfaces actually *does*, where they have drifted |
| Success | Behaviour provably unchanged | Behaviour deliberately changed, on a recorded decision |
| Verification | Compiler, unit tests, GPS replay A/B — all reachable from a desk | Head unit, iPhone, two devices, a Mac with Xcode; some of it a real ride |
| Risk | Local, revertible, one language | Cross-language, one-way once iOS consumes it |

**Why they are separate, and not one longer chain.** Three reasons, in the order they matter:

1. **Cadence.** The structure axis is Kotlin the compiler can check and CI already gates. The
   convergence axis includes SwiftUI screens nobody here can build. One linear chain makes the
   structural work hostage to feature work — stage 4 would sit behind an iOS readout and a phone
   audio-focus decision, neither of which has anything to do with who owns `camSuspended`.
2. **The register says so.** Its § *What stage 3 actually consumes* names the sixteen non-stage-3
   entries as *"adjacent work that must not be folded in"*, and warns that four of them are *"each
   larger than all of stage 3."* A single chain is exactly the shape that folds them in.
3. **Different failure modes on a revert.** A structural commit reverts to a known-good tree. A
   convergence commit that changed iOS behaviour reverts across two languages and, if commonMain
   is involved, across a published contract.

**The convergence axis is complete, as the register defined it** (2026-08-12). Convergence 3
closed §C.1 item 6 — the last item on the register's order of work — with all four §C decisions
discharged: 1 (full parity, the phone speaks), 2, 3 and 4. §A's four remaining *needs-a-human*
entries — 14 (the watch's discarded instruction text), 18 (the HUD at a standstill), 19 (distance
quantisation) and 21 (catch-up order) — stay **one-line answers in the register** and do **not**
get a fourth spec: each is a product question with no code shape to plan, and the register's own
warning applies to any successor list (*"If this list grows past six, the register has stopped
working."*). What the axis did **not** close is verification: convergence 3's phone-audio items
landed without the device session their plan requires, so entries 12 and 15 are resolved in code
and open on hardware. The structure axis is unaffected and continues from stage 3.

**Where they touch: exactly one place.** Stage 3's **Consumed decisions** section. It records the
five register entries stage 3 consumes and what each does to its scope; the only forward edge is
that convergence 2 cannot start until stage 3 has landed `SectionAverageTracker`, because it
reads it. Nothing else crosses. If you find yourself adding a second cross-reference, one of the
two specs has taken on the other axis' work.

**Deciding which axis a new piece of work belongs to.** In order; the first match wins:

1. **Is one surface simply wrong** — a missing permission, a dropped frame type, a stale value
   nobody resets? Then it belongs to **neither axis.** It is a §B bug: file it, fix it in its own
   commit, in any order. The register's §B list is deliberately not gated behind a precondition,
   because a bug that waits for a stage is a bug that ships.
2. **Would landing it change what a user sees or hears on any surface?** Convergence axis. It
   needs a decision recorded before it needs a plan.
3. **Is the behaviour provably identical afterwards, on every surface?** Structure axis — a move,
   an extraction, an owner change.
4. **Still unclear?** It is probably two changes. Split it, and note that
   `DECISION.md:394-400` already forbids the combination you were about to write.

### Running the convergence axis' preconditions

`chain-status.sh` reports the structure axis only: it globs `specs/stage-*.md`, so the three
convergence specs are invisible to it. That is a known gap, recorded here rather than worked
around, and it has one consequence you must not skip — **paste the convergence spec's
Preconditions fence into a shell yourself.** The assertions are written in the same conventions
the script parses (a bare `VAR=path` line, one command per assertion, `# expect N` or
`# expect >= N` with nothing after the number), so widening the glob is a one-line change if
someone wants the report to cover both axes.

One convention worth knowing before you write a new assertion, in either axis: the script judges
`# expect 1  (some note)` as **INFO, not PASS/FAIL** — a trailing parenthetical makes the
expectation non-numeric. Two of stage 3's assertions and five of stage 2's are ungated for that
reason (counted from a live run, 2026-08-12). A `# expect >= N (note)` survives it, which is why
this is easy to miss. Put the number last, with nothing after it.

## Graded detail — an honest admission

Specs are not equally knowable this far out. Each one declares its detail level in its
header. This applies to both axes: a convergence spec is no more knowable than a stage spec, and
convergence 2 and 3 carry the same scheduled-rewrite markers stages 3 and 4 do.

| Spec | Axis | Detail level | Why |
|---|---|---|---|
| [0](stage-0-verification-baseline.md) | structure | **Full** — file-level work items | Touches CI, tooling and three isolated defects, all verified today |
| [1](stage-1-mechanical-split.md) | structure | **Full** — symbol and line-range level | Pure moves of code that exists and was counted directly |
| [2](stage-2-pure-extractions.md) | structure | **Function level** — named extractions, no line ranges | Line numbers shift under stage 1; the functions do not |
| [3](stage-3-hazard-machines-to-shared.md) | structure | **Intent + constraints** — goals, boundaries, ordering | Destination package settled; internals depend on what stage 2 reveals |
| [4](stage-4-state-ownership.md) | structure | **Decision gate only** — no work items | The choice it makes is not yet decidable |
| [convergence 1](convergence-1-cheap-fixes.md) | convergence | **Full** — three named fixes on three surfaces | Each one is a known file and a known line, verified today |
| [convergence 2](convergence-2-section-readouts.md) | convergence | **Intent + constraints** | Consumes a type stage 3 has not chosen yet |
| [convergence 3](convergence-3-voice-policy.md) | convergence | **Intent + constraints** | The policy's shape is knowable; the phone's audio behaviour is an unanswered product question |

A section that must be rewritten later says so in the spec, in place. That marker is not a
placeholder to be filled in now; it is a scheduled decision.

## Spec anatomy

Every spec on both axes has the same eight sections, in this order. A convergence spec reads
its **Why this stage** argument out of the register instead of DECISION.md, and names the
register entries it discharges; otherwise the anatomy is identical, which is deliberate — a
reader should not have to learn a second document shape to follow the second axis.

1. **Status** — detail level, prerequisite stage, current state (`not started` / `in progress`
   / `done`), and the date its preconditions were captured.
2. **Preconditions** — a block of shell assertions with expected values. This is the
   staleness gate; see below.
3. **Why this stage** — the argument from DECISION.md, in two or three sentences, so the
   implementor need not re-read the whole roadmap.
4. **Scope** — what this stage does.
5. **Out of scope** — what it deliberately does not do, and which stage owns it instead.
   This section is load-bearing: it is what stops a stage from quietly absorbing the next one.
6. **Work items** — each sized to be one subagent's task and one commit, with an explicit
   independence note (parallelisable or strictly sequential).
7. **Done criteria and verification** — what must be true to call the stage finished, and
   which tier of the verification checklist to run.
8. **Next stage** — the linked-list footer.

## The staleness contract

This is the part the roadmap could not provide, and the reason the specs are safe to write
ahead of time.

Each spec's **Preconditions** block is a list of executable checks with values captured when
the spec was written. For example, stage 1 asserts `wc -l < MapScreen.kt` is `3193`. Stage 2
asserts it is between 1500 and 1700 — the range stage 1 is expected to leave behind.

The rule:

> Run the preconditions before writing the stage's plan. If any assertion fails, the spec is
> **stale**. Do not adapt the plan to the drift. Re-run `superpowers:brainstorming` for that
> stage, rewrite the spec, then continue.

Two moments demand this, both named by the user brief:

- **After finishing a stage's implementation** — re-run the *next* stage's preconditions
  immediately, while the changes are fresh, and record the result in that spec's Status block.
- **Immediately before writing the next stage's plan** — run them again. Time may have passed;
  other work may have landed on the branch.

A failing precondition is not a failure of the process. It is the process working: it means
the code moved and the plan would have been written against a fiction.

### Why assertions rather than prose

"Verify the spec still applies" is unactionable — every implementor decides differently what
counts as still applying. `grep -c 'LaunchedEffect(' ... == 35` either holds or it does not.
The cost is that the assertions must be chosen to fail *loudly on relevant drift* and *not at
all on irrelevant drift*, which is why they count structural things (effects, symbols, files)
rather than raw line totals wherever possible.

The contract binds both axes, and the convergence axis needed one extension. Most of its
findings are **absences** — a missing `Info.plist` key, a missing `case "left"`, a value nobody
resets — so most of its assertions are inverted, and an inverted assertion is the kind a reader
"checks" by glancing at a grep that printed nothing, which is also what a mistyped path prints.
The register's §D makes the same argument and proposes
`docs/refactor/mapscreen/scripts/check-divergences.sh` for it: one content-anchored assertion per
entry, which survives line drift where a table of line numbers does not. That script does not
exist yet; until it does, the convergence specs carry their own fences.

**Run every assertion before writing it down.** This chain has now been wrong about a count five
separate times — four in specs (`detour-staged-refactor` §2 lists them) and once in the register,
whose §D asserts an off-route literal that the register's *own commit* had already deleted. Every
one was catchable by a single execution at authoring time.

## Subagent execution model

Work items are the unit of delegation. Each is written so that a subagent handed only that
item, the spec, and the repo can complete it without asking questions.

- **Stage 1** is 11 independent file moves — heavily parallel, and the best case for
  subagent fan-out in the whole chain.
- **Stage 2** is 3 independent extractions — parallel.
- **Stage 3** is strictly sequential. Each machine must land, be replayed against the
  baseline, and be confirmed before the next starts. Parallelising it would make the A/B
  replay uninterpretable, which is the one verification tool this screen has.
- **Stage 0** and **stage 4** are mixed; each item says which it is.
- **Convergence 1** is 3 independent fixes on 3 surfaces — parallel, with one ordering
  constraint that reaches outside the item list: its microphone-permission fix precedes
  convergence 3.
- **Convergence 2 and 3** cannot be delegated yet; their work items are scheduled rewrites.

Every work item names its own commit. No work item produces two commits, and no commit spans
two work items. DECISION.md's "Never in one commit" table is binding on every stage and is
not restated in each spec.

## Stop-points

Stages 1 and 3 end at declared stop-points. Stopping there is a legitimate outcome, not an
abandoned refactor — but only if it is recorded. Each stop-point in the specs carries the
sentence that must be written into DECISION.md's Status if work halts there, so that a line
count never gets filed as "MapScreen refactored" when the state layer is untouched.

## Deviations from the skill defaults, and why

- Specs live in `docs/refactor/mapscreen/specs/` rather than `docs/superpowers/specs/`. The
  chain is meaningless away from DECISION.md, which is its roadmap and sits one directory up.
- The user-review gate between spec and plan is waived by standing instruction: take the
  recommended option and do not block for input. The staleness gate replaces it as the
  mechanism that stops a wrong spec being executed — a weaker check, deliberately chosen, and
  named here so the trade is on the record.

## Next stage

**Structure axis** — start at [`stage-0-verification-baseline.md`](stage-0-verification-baseline.md).

Run its **Preconditions** block first. If it passes, invoke the
`superpowers:writing-plans` skill against that spec to produce the stage-0 implementation
plan, then execute the plan with `superpowers:subagent-driven-development`.

**Convergence axis** — start at [`convergence-1-cheap-fixes.md`](convergence-1-cheap-fixes.md),
same loop. It is the one spec in this directory that is neither blocked nor waiting on a
recording: stages 3 and 4 are blocked on replay route (i) and the stage-0 baseline
(`DECISION.md:29-35`), and convergence 2 waits on stage 3. If you have an hour and no device,
this is the work that exists.

**Neither axis** — the six bugs in the register's §B. They are ungated by design and each is one
commit. Nothing in either chain waits for them, and they should not wait for either chain.
