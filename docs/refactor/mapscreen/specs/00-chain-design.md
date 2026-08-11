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
stage-0-verification-baseline.md      ← START HERE
        ↓ (re-verify, then writing-plans)
stage-1-mechanical-split.md
        ↓
stage-2-pure-extractions.md
        ↓
stage-3-hazard-machines-to-shared.md  ← default stop-point
        ↓
stage-4-state-ownership.md            ← decision gate, optional
```

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

## Graded detail — an honest admission

Specs are not equally knowable this far out. Each one declares its detail level in its
header:

| Stage | Detail level | Why |
|---|---|---|
| 0 | **Full** — file-level work items | Touches CI, tooling and three isolated defects, all verified today |
| 1 | **Full** — symbol and line-range level | Pure moves of code that exists and was counted directly |
| 2 | **Function level** — named extractions, no line ranges | Line numbers shift under stage 1; the functions do not |
| 3 | **Intent + constraints** — goals, boundaries, ordering | Destination package settled; internals depend on what stage 2 reveals |
| 4 | **Decision gate only** — no work items | The choice it makes is not yet decidable |

A section that must be rewritten later says so in the spec, in place. That marker is not a
placeholder to be filled in now; it is a scheduled decision.

## Spec anatomy

Every stage spec has the same eight sections, in this order:

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

Start at [`stage-0-verification-baseline.md`](stage-0-verification-baseline.md).

Run its **Preconditions** block first. If it passes, invoke the
`superpowers:writing-plans` skill against that spec to produce the stage-0 implementation
plan, then execute the plan with `superpowers:subagent-driven-development`.
