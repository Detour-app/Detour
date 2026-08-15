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

```
read spec → run its preconditions → stale? re-brainstorm the spec
                                  → current? writing-plans → plan
plan → subagent-driven-development → commits → verification
     → update the spec's Status block → record the outcome in ../DECISION.md
```

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
establish which.** This chain produced wrong assertions repeatedly — a count that had always
been 2 written as 1, a column index that silently moved between schema versions and made a gate
pass for the wrong reason, an `internal` visibility that could not cross a module boundary, and
a verification method that could not observe the thing it claimed to measure. In every case the
assertion was wrong, not the code.

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
