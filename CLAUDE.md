# Detour

## Versioning

Before creating a commit that will land on `main`, check whether `versionName`
in `app/build.gradle.kts` needs a semver bump — don't wait to be asked.

- Fix, no behaviour/API break -> bump patch (`1.76.0` -> `1.76.1`)
- New feature, backward compatible -> bump minor (`1.76.1` -> `1.77.0`)
- Breaking change (data format, wire protocol, min OS) -> bump major
- Docs, refactor, chore, test-only -> no bump

Mixed feature+fix in one PR bumps for the higher of the two. Full rationale
and the release-tag mechanics this feeds are in `CONTRIBUTING.md`'s
"Versioning" section — read it if a case doesn't fit the table above.

`versionCode` is separate and CI-stamped from the run number; never bump it
by hand.

## Triaging issues

Every open issue carries exactly one priority label: `p0-now`, `p1-next`,
`p2-soon` or `p3-later`. The label answers one question — what does it cost to
leave this alone? — decided by a first-match-wins ladder:

- Rider harmed on a shipped build, silently or unavoidably -> `p0-now`
- Shipped feature doesn't work, or this blocks other work -> `p1-next`
- A real cost not being paid yet -> `p2-soon`
- Everything else -> `p3-later`

`blocked` and `needs-triage` go on top of a priority, never instead of one.

Type is a separate axis, picked by asking what commit type closes the issue:
`fix:` -> `bug`, `feat:` -> `enhancement`, `docs:` -> `documentation`,
`refactor:`/`chore:`/test-only -> `chore`. So a dependency bump, a harness fix,
an unrun verification and a measurement are all `chore`, whatever their
priority. A defect in the test harness is still a `bug` — the priority label is
what says it isn't rider-facing.

Three rules worth stating here because all three were violated across the
tracker: judge the cost of leaving it alone, **not** how well the issue is
written; re-read the comments before trusting a label, because later evidence
often invalidates it; and split an epic whose parts don't share a priority into
GitHub sub-issues rather than a checklist body — one issue holds one priority,
and a hand-maintained checklist of what has shipped goes stale within a few
merges. The full ladder, the failure modes and who may change a label
are in `CONTRIBUTING.md`'s "Triage" section.

## Reviewing Kotlin

Every review in this repo — human or agent, a full PR or one changed file —
checks the diff against `docs/guidelines/`. The checklist is
`docs/guidelines/checklist.md`; a failure there is a finding, not a preference.

Route by what changed, and read the section before citing it:

| The diff touches | Read |
| --- | --- |
| a new file, or a file being split | `boundaries.md` (§8) — especially §8.4, the seven-parameter gate |
| state, a store, a presenter, a `remember` | `state.md` (§4) |
| `shared/`, `expect`/`actual`, an interface, a `StateFlow` | `multiplatform.md` (§5, §6) |
| where a file lives, or a new package | `architecture.md` (§1-3) |
| names, constants, comments | `conventions.md` (§7) |
| any shared logic | `testing.md` (§10) |

Cite the section number in the finding (`§8.4`), so the reader can check the
rule rather than take the finding on trust. Section numbers are stable across
files. If the guideline is wrong for the case in front of you, say so and why —
a documented exception is fine, a silent one is rot.
