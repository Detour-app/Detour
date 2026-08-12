---
name: detour-staged-refactor
description: >-
  Run, continue, or design a multi-stage refactor in this repo — a chain of specs under
  docs/refactor/, executed one stage at a time. Use this before writing or executing any plan
  under docs/refactor/*/plans/, before starting a stage, whenever a task mentions a refactor
  stage, a stop-point, a spec chain, preconditions or "is this spec still valid", and before
  restructuring any large file for architectural rather than cosmetic reasons. It carries the
  staleness gate that decides whether a spec may be executed, the rules about what may never
  share a commit, which verification tier a change earns, and the bookkeeping that this
  project skipped twice and had to correct afterwards. Do not start a stage from the roadmap
  alone.
---

# Running a staged refactor in Detour

The live instance is the MapScreen chain under `docs/refactor/mapscreen/`. It is both the
example and the template: `DECISION.md` is the roadmap, `specs/00-chain-design.md` explains
the mechanism, and `specs/stage-{0..4}-*.md` are the executable stages. This skill is the
protocol for running such a chain. It does not restate any stage's content and does not
replace reading the spec for your stage.

## Preconditions

```sh
.claude/skills/detour-staged-refactor/scripts/chain-status.sh [stage] [--chain NAME]
```

With no argument it reports every stage: its Status line as written, then each assertion in
that stage's own Preconditions fence with a `PASS`/`FAIL`/`INFO` verdict and the value the
command actually returned. With a stage number it reports only that stage **and exits
non-zero if its preconditions fail** — that is the gate to run before writing that stage's
plan. Later stages are expected to fail until the stages before them land, which is why the
full report never gates.

If the chain has been completed or abandoned, this skill is archaeology — say so and delete
it rather than executing a protocol for work that is over.

---

## 1. The chain shape

One spec per stage, written ahead of time, each ending in a footer that names its successor.

```
stage-0-verification-baseline.md      make verification cheap; touch nothing else
   ↓
stage-1-mechanical-split.md           pure file moves            → stop-point A
   ↓
stage-2-pure-extractions.md           pure functions + tests     → stop-point B
   ↓
stage-3-hazard-machines-to-shared.md  stateful machines → :shared → stop-point C (default stop)
   ↓
stage-4-state-ownership.md            decision gate, optional
```

Two properties make writing four specs in advance defensible rather than reckless:

1. **Each stage changes the ground the next one stands on**, so no spec may depend on the
   previous stage's line numbers. Detail is graded deliberately — stage 1 was specified at
   symbol-and-line level because it moved code that existed and had been counted; stage 2 is
   specified at *function* level with no line ranges; stage 3 at intent-and-constraints; stage
   4 is a decision gate with no work items at all. A section that must be rewritten later says
   so in place, as a scheduled decision, not as a placeholder.
2. **Every spec opens with executable preconditions** — the subject of §2.

Each spec has the same eight sections: Status · Preconditions · Why this stage · Scope · Out
of scope · Work items · Done criteria and verification · Next stage. **Out of scope is
load-bearing**: it is what stops a stage quietly absorbing the next one. If you find yourself
doing something a later stage owns, you are no longer executing this spec.

## 2. The staleness contract

> Run the stage's Preconditions block **before writing its plan**. If any assertion fails, the
> spec is **stale**. Do not adapt the plan to the drift — re-brainstorm the stage, rewrite the
> spec, then continue.

Run them at two moments (`specs/00-chain-design.md:88-116`):

- **Immediately after the previous stage lands**, while the changes are fresh, recording the
  result in the next spec's Status block.
- **Immediately before writing the next stage's plan**, because time may have passed and other
  work may have landed.

### Why an assertion and not a sentence

"Check that this spec still applies" is unactionable. Every reader decides differently what
"still applies" means, and the reader most likely to skip the check is the one in a hurry.
`grep -c 'leadingSpinIndex' $M # expect 2` either holds or it does not, and the answer is the
same for everyone.

The cost of that precision is that the assertions have to be chosen well: they must fail
**loudly on relevant drift** and **not at all on irrelevant drift**. That is why they count
structural things — symbols, files, effect counts, call sites — rather than raw line totals
wherever possible. A stage that asserts `wc -l == 3193` will cry wolf after the first typo
fix. Stage 2 instead asserts a *range* (`1500 ≤ lines ≤ 1700`) plus five specific greps for
the code it is going to touch.

`chain-status.sh` encodes that distinction: `# expect 2` and `# expect >= 1` are judged
`PASS`/`FAIL`, while `# expect line 213` is reported as `INFO` with the line the symbol is at
now. A drifted line number is information; a changed count is a failure.

Line numbers drifting by a constant is **not** staleness. Stage 1's spec says so explicitly,
and every stage-1 batch after the first re-derived its ranges with `grep -n` against the
current file rather than trusting the plan's numbers — by batch C they had drifted ~700 lines
and the work was still correct. Staleness is a *symbol* that is missing or a *count* that
changed.

### A precondition failure is a hypothesis, not a verdict

This is the part that most needs saying, because this repo's own specs have been wrong three
separate times:

- **A wrong line number.** Stage 1's spec cited declaration lines from a commit that later
  gained lines above them. Expected drift; the spec says how to handle it.
- **An unachievable done-criterion.** Stage 1 originally required `git show -M -C` to report a
  rename. It structurally cannot for a modified source file — no amount of correct execution
  would ever satisfy it. It was replaced (`specs/stage-1-mechanical-split.md:132`), and the
  criterion is *still wrong* in `12-eval-risk-sequencing.md:491` and `:761-762`.
- **A precondition that would have raised a false alarm.** Stage 2 asserted
  `grep -c 'leadingSpinIndex' $M # expect 1`. The true value has always been 2 — two call
  sites — so the very first honest run of that block would have declared a perfectly current
  spec stale and triggered a needless rewrite. It was corrected to 2, and the correction is
  recorded in that spec's Status line.

So when an assertion fails, form two hypotheses and test them:

- **The code moved.** A named symbol is gone, changed shape, or gained a caller → the spec is
  stale. Rewrite it.
- **The assertion was wrong when written.** → fix the assertion, in its own commit, and say
  why in the Status block.

Do not silently ignore a failure, and do not let a miscount cost you a rewrite.

## 3. Where the MapScreen chain actually is

Derive it, do not assume — the Status blocks have drifted before. `chain-status.sh` with no
argument does exactly that derivation: it prints each spec's Status line *and* the live result
of its assertions next to it, so a spec claiming "not started" for work that has landed is
visible in one screen.

From `DECISION.md:9-29` (its Status section, dated 2026-08-12) and confirmed against the tree:

- **Stage 0 — partial.** Task 1 (the CI test gate, now
  `.github/workflows/build.yml:118`), Task 5 (the error snackbar, `MapScreen.kt:164-171`),
  Task 6 (the iOS maneuver arrows) and Task 7 (the shared-core rule in `CONTRIBUTING.md`) have
  landed. **Tasks 2–4 — the replay routes, the behavioural baseline and the
  Overpass-off-collector fix — have not.** They are blocked on device recordings; two of four
  canonical routes exist.
- **Stage 1 — done**, 2026-08-12. `MapScreen.kt` 3204 → 1549 lines across twelve commits into
  eleven same-package files, zero added lines, zero-line diffs at every external call site.
  Stop-point A reached and recorded.
- **Stages 2, 3, 4 — not started.** Stage 2's preconditions all pass against the tree today,
  so it is ready to plan.

**The stage-0 gap matters more than it looks.** Stage 3 requires
`tools/mocklocation/baseline/` to exist, and the baseline is only capturable *before* the
first behaviour-touching commit. Stage 2 is pure extraction and is safe without it; stage 3
is not. Do not start stage 3 by deciding the baseline was optional.

## 4. Never in one commit

Binding on every stage, restated in no spec. The table is `DECISION.md:367-373`, expanded with
reasons at `12-eval-risk-sequencing.md:867-878`. Verified against both:

| Do not combine | Because |
|---|---|
| A move **and** a visibility change to a symbol whose call site also moves | The compiler stops being a proof of equivalence |
| A state-owner change **and** a lifetime change | Two independent failure surfaces sharing one revert |
| An extraction **and** the bug it reveals | The "fix" hides inside a diff nobody re-reads |
| An effect body move **and** a change to that effect's key list | The key list *is* the behaviour — see `detour-compose-state-hazards` §1 |
| `camSuspended` **and** `lastGestureMs` | `spin()` sets only the first; whichever way you decide that asymmetry, decide it alone |
| Local spin state **and** `spinOffer`/`spinVotes` ownership | The convoy path needs its own bisect |
| Any two `lastFix` consumer changes | Six independent collectors; keep the blast radius to one |
| Any move **and** any reformatting | Kills `git log -C`, and the comments are the design record (`CONTRIBUTING.md:177-189`) |

Two rules of the same family, from `12-eval-risk-sequencing.md:861-865`:

- **Car-side deletions trail their extraction by exactly one commit, never share one.** A car
  regression and a phone regression in a single revert is two bisects.
- **Stage 3's machines must not interleave with stage 4's owner changes.** Mixing an owner
  change with a machine extraction makes the A/B replay uninterpretable, and the replay is the
  only real verification this screen has.

The last row of the table is not style policing. The why-comments in these files are the
reason the constants are what they are, and `git blame -C` is what keeps them attributable
across a move. An IDE that reformats on save destroys that silently.

`12-eval-risk-sequencing.md:882-950` lists eleven changes the risk evaluator would refuse
outright. Read it before proposing a "while I'm here" simplification; several of them are
one-word diffs that look like corrections.

## 5. Which verification a change earns

Tiers are defined at `12-eval-risk-sequencing.md:440-700`. Choose by what the change touches,
not by how large or how safe it feels.

| Change | Tier |
|---|---|
| A pure move, no body edited | Tier 0 + the stage's desk checklist |
| Anything inside a composable body | Tier 0 + Tier 1 (desk, stationary, ~15 min) |
| A `lastFix` consumer, or the camera | Tier 2 — mock GPS replay, A/B against the baseline |
| Convoy, navigation session, BLE/Wear relay | Tier 3 — two devices, or a paired watch |

**Tier 0 is free and belongs on every commit:**

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh <base> [changed files...]
./gradlew :app:assembleDebug :app:assembleRelease        # R8 catches what debug does not
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```

The script does the grep half, comparing against `<base>` rather than the working tree,
because every one of these checks is a delta rather than a number: the `rememberUpdatedState`
count must not **drop** without an explanation, a newly added file must not own a
`CoroutineScope`, `shared/src/commonMain` must still contain zero `Dispatchers`, and listener
additions and removals should move together. It defaults to every `.kt` file changed in
`<base>..HEAD`. It does not run Gradle — those two lines are yours to run, in the
devcontainer, and their output is meant to be read.

For a pure move, add the zero-added-lines proof:
`.claude/skills/detour-file-split/scripts/check-no-added-lines.sh <base> <moved-from file>`.

Plus a diff read: no `LaunchedEffect` key list changed in a commit that also moved that
effect's body. `tier0-greps.sh` prints every effect declaration line the range touched so that
read cannot be skipped, but it cannot make the judgement for you.

**Tier 0's rename check is obsolete.** `12-eval-risk-sequencing.md:491` and `:761-762` still
ask `git show -M -C --stat` to report renames on a move commit. It cannot: rename detection
needs a deleted blob and the source file is only ever modified. The zero-added-lines check
above replaces it. Full explanation in `detour-file-split` §6.

Tier 2 is the one people skip. The replay harness exists and is cheap
(`detour-gps-replay`), roughly 70% of this screen's GPS-driven behaviour is reachable from a
desk, and a change that touches a fix consumer is exactly the class where the compiler and the
unit tests prove nothing. If you cannot run it, say the change is unverified — do not
substitute a Tier 1 checklist and call it done.

## 6. The bookkeeping that is easy to skip and expensive to skip

Both of these were skipped in this project and had to be fixed afterwards. They take two
minutes each and they are the difference between a chain someone can pick up and a chain
someone re-derives from scratch.

**Update the stage spec's Status block the moment the stage lands.** Stage 1 finished on
`main` — eleven new files on disk, `MapScreen.kt` at 1549 lines — while its spec still read
`**State** | not started`. Anyone arriving at the chain was told the completed work had not
begun. It now reads `**State** | **done** 2026-08-12 …` with the commit range and the plan
link, which is the shape to copy. Note that
`specs/stage-0-verification-baseline.md:9` **still** says `not started` even though four of
its tasks have landed — the same mistake, still uncorrected, and a live demonstration of how
quickly it happens.

**If you stop at a stop-point, write the stop-point sentence into `DECISION.md`.** Each spec
carries its own text; stage 1's is at `specs/stage-1-mechanical-split.md:149-152`. Without it,
"MapScreen.kt is now 1549 lines" gets filed as "MapScreen refactored" while the state layer —
the actual problem — is untouched. That sentence is now recorded at `DECISION.md:17-20` in
bold: *"The state layer is untouched … This is stop-point A exactly as the spec defined it,
and it must not be recorded as 'MapScreen refactored'."*

**Never accept a line count as the success criterion.** Five independent analysts predicted
that exact misreading before any code moved
(`12-eval-risk-sequencing.md:945-950`). Report what moved, what did not, and what is still
open.

Also worth two minutes: when a stage lands, run the *next* stage's preconditions
(`chain-status.sh <n>`) and record the result in that spec's Status, whether or not you intend
to continue. That is how stage 2's `leadingSpinIndex` miscount was caught before it cost
anyone a rewrite.

## 7. The loop, per stage

```
read the stage's spec → chain-status.sh <n>          (exits non-zero if it is not ready)
    any assertion fails → decide: stale code, or a wrong assertion?
        stale code       → re-brainstorm, rewrite the spec, then continue
        wrong assertion  → fix it in its own commit, note it in Status, continue
    all pass             → superpowers:writing-plans → the plan
plan → superpowers:subagent-driven-development → commits (one work item, one commit)
     → run the verification tier the stage names
     → update this spec's Status; if you stopped at a stop-point, write its sentence
       into DECISION.md
     → run the NEXT stage's preconditions now and record the result there
```

The plan, not the spec, is what gets executed. A plan carries the concrete line ranges, the
commit messages and the per-item notes; the spec carries the intent and the gate. Keep them
separate — that is what lets a spec written weeks ago still be useful after its line numbers
have rotted.

Work items are the unit of delegation: each is written so a subagent handed only that item,
the spec, and the repo can finish it without asking questions. Each names its own commit. No
work item produces two commits and no commit spans two work items. Whether items may run in
parallel is a per-stage property the spec states — stage 1's eleven moves were parallel in
what they added but strictly serial in what they deleted; stage 3 is strictly sequential,
because each machine must be replayed against the baseline before the next one starts.

## 8. A standing warning about this repo's own documents

The specs, plans and evaluations here are load-bearing and get cited by later work, so a wrong
claim propagates. They have already contained a wrong line number, an unachievable
verification criterion, and a precondition that would have raised a false staleness alarm —
and two commits exist purely to undo confidently-wrong assertions written into other docs.

Practical consequences:

- Check a `path:line` citation before repeating it. The MapScreen citations in
  `12-eval-risk-sequencing.md`, `00-inventory.md` and the five proposals were written against
  the 3193-line file and are **all** wrong now. The reasoning survived the split; the numbers
  did not.
- When you copy a fact out of a document into a plan or a skill, **correct or remove it at the
  source**. Do not create a second copy that can drift. The `git show -M -C` criterion is the
  live proof: fixed in the plan and the stage-1 spec, still wrong in two places in
  `12-eval-risk-sequencing.md`.
- State what you observed and name the artifact. A `grep -c` output is a result; "the split
  looks clean" is not.

## Related

- `detour-file-split` — the mechanics of a move: same-package placement, visibility by grep,
  the zero-added-lines proof, imports last.
- `detour-compose-state-hazards` — why an effect's key list, a `rememberUpdatedState` or a
  `lastFix` collector is a behaviour change rather than a refactor.
- `detour-gps-replay` — how to run the Tier 2 A/B without driving.
- `docs/refactor/mapscreen/specs/00-chain-design.md` — the design of the chain itself, if you
  are building a new one for a different file.
