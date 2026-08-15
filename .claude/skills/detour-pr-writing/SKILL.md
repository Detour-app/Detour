---
name: detour-pr-writing
description: >-
  Write the description for a pull request in this repo, or rewrite one that has grown
  bloated. Use this before running `gh pr create` or `gh pr edit --body`, whenever a task
  mentions opening a PR, writing a PR body or summarising a branch for review, and whenever
  you have just finished a piece of work and are about to describe it. It carries the shape a
  description takes here — lead with measured before/after, state what changed, keep the
  known limits, point at the follow-up issues — and the list of things that read as filler to
  a reviewer and must be cut. Read it before writing the body, not while trimming one that is
  already too long.
---

# Writing a PR description in this repo

A PR description is read once, by someone deciding whether to merge. It answers four
questions and stops:

1. What was broken?
2. What does the evidence say changed?
3. What did you change?
4. What is still broken, and where is it tracked?

Everything else is the author talking to themselves.

## Preconditions

```sh
.claude/skills/detour-pr-writing/scripts/check-pr-body.sh <body.md>
```

Flags process narration, self-congratulation and hedging, checks for a before/after table
and issue references, and prints the word count. It is a linter, not a judge — a flagged
line may still be right. Read what it says and decide.

## The shape

```markdown
Closes #NN.

[One or two sentences: what was broken, and what this does about it.]

## Before / after

[Table of measured values. See below.]

## What changed

[Numbered or bulleted. One per real change. Say why where the why is not obvious.]

## Known limits          ← keep this. It is not padding.

[What is not proven, what it cost, what is untested.]

## Follow-ups

- #NN — one line each.
```

Sections earn their place. A one-line fix does not need five headings; drop the ones with
nothing to say. But do not drop *Known limits* just because the news is good — see below.

## Lead with measured before/after

This is the part a reviewer actually uses, and the part most likely to be missing. Put real
numbers in a table, from the same conditions on both sides, and say what those conditions
were.

```markdown
Same route (`urban-limits.txt`), 1000 ms interval, same device, both runs through one script
so the filtering matches.

| speed | push % before | push % after |
|---|---|---|
| 0–15 km/h | 57 % | **100 %** |
| 60+ | 81 % | **100 %** |
```

**"Measured" does not have to mean a benchmark.** It means a fact someone else could check.
For a security or correctness fix the equivalent is often a value read off the running
system:

```markdown
| field | inputType before | inputType after |
|---|---|---|
| Client Secret | `0x1` — autocorrected | **`0x81`** — `+ TYPE_TEXT_VARIATION_PASSWORD` |
```

If there is genuinely nothing measurable — a rename, a doc change — say what you did and skip
the section rather than inventing a metric.

## Keep the known limits

The temptation is to cut this section because it makes the work look weaker. Keep it. A
reviewer who finds an unstated limit themselves stops trusting the rest of the description,
and the cost of them finding it is much higher than the cost of you writing it.

Worth stating:

- **A claim the evidence does not actually support.** "Cause 3 is implemented, derived and
  unit-tested — but not measured. The counter recorded camera-to-raw-fix distance, and
  prediction deliberately puts the camera ahead of the raw fix, so that number rises by
  design."
- **What it cost.** "~91 → ~78 fps on a 120 Hz panel. Variance improved, but this is one
  device; a 60 Hz phone is untested."
- **A property the change retires.** "`CAM_POS_EPS_DEG` and its siblings are now a
  standstill-only optimisation."
- **Something a future editor would otherwise get wrong.** "`capitalization = None` is a
  no-op — carried as documented intent, please don't 'fix' it later thinking it does
  something."

That last kind is worth its weight. It is the difference between a comment and a trap.

## Reference the issues you filed while working

Work turns up problems that are out of scope. File them, then point at them from the PR with
one line each saying *why they are not in this PR*:

```markdown
- #37 — `CarMapRenderer` has the same four defects; needs a head unit to verify.
- #39 — fix age uses wall-clock time; the replay harness can't surface skew.
```

Two things this gets right. The reviewer sees the gap was noticed rather than missed. And
"needs a head unit" is a *reason*, where "deferred" is an excuse.

Check the auto-close linkage before you call it done. Only the issue this PR actually
resolves should be in `closingIssuesReferences` — a follow-up that auto-closes on merge is
worse than one never filed:

```sh
gh pr view <N> --repo <repo> --json closingIssuesReferences \
  -q '[.closingIssuesReferences[].number] | join(", ")'
```

Use `Closes #NN` for the one; reference the rest as bare `#NN`.

## Cut process narration

The single biggest source of bloat. Nobody is reviewing your process — they are reviewing the
diff. Cut:

| Cut | Why |
|---|---|
| What an earlier draft claimed, and how it was wrong | Only the shipped state is under review |
| That a review caught something, and what you then fixed | The fix is in the diff; the review is not being merged |
| Measuring, then re-measuring, and why | Report the number that describes HEAD |
| "For the first time in this area…", "worth pausing on", "the headline is" | Telling the reader what to be impressed by |
| A discovery written as a story | State the finding; drop the arc |
| Hedging — "arguably", "it could be said", "I believe" | Either it is true and you say so, or it is a known limit |

The test: **if this sentence were deleted, would a reviewer make a different decision?** If
not, delete it.

A finding that came out of the work can still be worth one line — but as a *fact about the
code*, not a story about you finding it. "Compose's frame clock pauses below `STARTED`
(measured: 6 loop samples in 5 s foregrounded, 0 in 18 s backgrounded)" earns its place
because it justifies the guard. "During review it emerged that my earlier explanation was
wrong, and I corrected it in two places" does not.

## Worked example

Same change, both written honestly. The first is what to avoid.

**Before — 180 words, says little:**

> This PR represents a significant improvement to the map rendering pipeline. After extensive
> investigation, I discovered that there were actually three separate stacked causes, which
> was surprising. The headline result — and this is worth pausing on — is that the camera now
> updates on every frame. I initially believed the issue was the easing constant, but after
> measuring I found this was not the case, and I corrected my approach. The measurement was
> re-run after review feedback to confirm the numbers still held. It is arguably one of the
> more impactful changes to this area.

**After — 60 words, says more:**

> The map stepped instead of gliding while driving. Three causes, all three fixed.
>
> | speed | push % before | push % after |
> |---|---|---|
> | 0–15 km/h | 57 % | **100 %** |
> | 60+ | 81 % | **100 %** |
>
> Marker source writes: ~1/sec → 70.5/sec.
>
> Lowering the ease constant can't fix this — a first-order lag chasing a ramp settles `v·τ`
> behind it regardless, which is why the target now leads by τ.

The second is shorter *and* denser. Cutting narration is what makes room for the algebra,
and the algebra is the part a reviewer can check.

## Length

There is no target — **density is the test, not word count.** A one-line fix gets three
sentences; a security change with four reviewer notes, three known limits and four
out-of-scope items is legitimately longer and is not padded.

The checker warns past ~600 words. Treat that as a prompt to re-read against the cut table,
not a limit: if every line is a fact a reviewer could act on, it is finished. If you cannot
find anything to cut, the warning has done its job and you ignore it.

## Related

- `detour-staged-refactor` — what may share a commit, which verification tier a change earns.
  A PR body is downstream of those decisions; if the verification was thin, say so under
  *Known limits* rather than writing around it.
