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
