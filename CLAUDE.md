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

Whenever you bump `versionName`, add a matching `## <versionName>` section to
`CHANGELOG.md` in the same PR — mandatory, not optional: CI's "Extract
changelog for this release" step (`.github/workflows/build.yml`) fails the
build if the bumped version has no section, and it runs on PRs too, so a
missing entry blocks merge. Skip it only for a no-bump PR
(docs/refactor/chore/test-only).
