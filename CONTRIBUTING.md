# Contributing

How to *land* a change: branches, pull requests, versioning, documentation and
style. How to **build and run** any of it — prerequisites, repository layout,
the local stack, tests — is [docs/DEVELOPERS.md](docs/DEVELOPERS.md), and the
shape of the system is the [README](README.md).

## Branches

Three, and only three:

| Branch | For |
| --- | --- |
| `main` | Trunk. Everything lands here. |
| `android` | Android-only work, branched from `main`. |
| `ios` | iOS-only work, branched from `main`. |

`android` and `ios` merge **back into** `main`; work that touches `shared/`
belongs on `main` directly, since it affects both. Short-lived topic branches
are fine — delete them once they're merged rather than leaving them on origin.

## Pull requests

- **One topic per PR.** A security fix and a UI tweak are two PRs, even if
  they're both small.
- **Kotlin changes are reviewed against [docs/guidelines/](docs/guidelines/README.md).**
  `docs/guidelines/checklist.md` is the list; cite the section number when you
  raise something, and say why when you're deliberately breaking a rule.
- **The build must pass.** CI builds the release APK and bundle on every
  change — a red build blocks review, don't ask for an
  exception. A push to `main` additionally signs them, publishes a GitHub
  release, and uploads to Play's internal track (see
  [docs/RELEASING.md](docs/RELEASING.md)); PRs stop at the build.
- **Touching `shared/` or `iosApp/` also runs the iOS workflow**, which
  type-checks `commonMain`, runs the shared tests on both the JVM and
  Kotlin/Native, and builds and boots the app in a simulator. It has to be
  green too.
- If you touched the backend, both test suites have to pass, and a schema
  change needs the migration committed alongside it.
- If you touched security- or privacy-relevant code (BLE, backup rules,
  credential storage, the keychain), say so explicitly — those get a closer
  look.

## Versioning

`versionName` in `app/build.gradle.kts` is semver — `MAJOR.MINOR.PATCH` — and the
bump is tied to the commit type already used across this repo's history
(`feat:`, `fix:`, `chore:`, ...):

| Change | Bump | Example |
| --- | --- | --- |
| Bug fix, no API/behaviour break | Patch | `1.76.0` -> `1.76.1` |
| New feature, backward compatible | Minor | `1.76.1` -> `1.77.0` |
| Breaking change (data format, wire protocol, min OS) | Major | `1.77.0` -> `2.0.0` |
| Docs, refactor, chore, test-only | No bump | — |

A PR that mixes a feature and a fix bumps for the higher of the two (minor),
same as it always has here.

**This isn't cosmetic — `versionName` is the git tag and the GitHub release
name** (`.github/workflows/build.yml`). Pushing to `main` twice without
bumping it overwrites that version's release instead of creating a new one.
Bump it in the same commit/PR that lands the change, not as an afterthought.

`versionCode` is unrelated and untouched by this: CI stamps it from the run
number on every build (`VERSION_CODE` in `build.yml`), so it always increases
regardless of what `versionName` says. Only `versionName` is yours to bump.

The mock-location harness (`tools/mocklocation/build.gradle.kts`) versions
independently — this rule is about `app/build.gradle.kts`, the app people
actually install.

## Documentation

[`docs/`](docs/README.md) has an index; start there rather than guessing at
filenames.

Two documents are cited **by section number** from code comments, so their
numbering is load-bearing — append rather than renumber:

| Document | Cited as | Covers |
| --- | --- | --- |
| [docs/BACKEND_SPEC.md](docs/BACKEND_SPEC.md) | `spec §11` (backend) | What the service must do |
| [docs/CIRCLES_AND_CONVOYS.md](docs/CIRCLES_AND_CONVOYS.md) | `docs/CIRCLES_AND_CONVOYS.md section 6` (apps) | Groups, and the live relay's wire format |

Change behaviour that either one describes, and the document is part of the
change — a spec that has quietly drifted is worse than none, because the next
person checks their work against it.

## Gating on server capabilities

A self-hosted deployment updates on its own schedule, so a feature that needs
something new from the server has three states to handle, not one: the server
has it, the server does not have it yet, or the server is unreachable. Query
`RoutingServer.hasFeature(ServerFeature.X)` rather than hand-rolling
`RoutingServer.knownServerFeatures()?.contains(...) == true` at each call site
— that was tried once (`CircleNotifyService.pushCovers`) and is exactly the
copy-or-diverge choice a second feature would otherwise face.

`hasFeature` always answers with a plain `Boolean`: unknown (never probed, or
the last probe failed) reads as false, the same as "the server said no". What
a caller does with that false is not this function's decision — record it at
the call site, since the right answer differs per feature:

- **Hide or disable the control.** The default for something new the rider
  would otherwise be able to trigger against a server that cannot serve it.
- **Keep the old behaviour running.** What `pushCovers` does: an unprobed
  server must not stand down the always-on relay socket on a guess, so
  "cannot confirm push" and "confirmed no push" both keep it running.
- **Degrade to an older path.** For a feature that replaces something rather
  than adding it.

Add the feature string to `ServerFeature` (`shared/.../data/Capabilities.kt`)
as a plain `val`, not `const` — see that file's own comment on why. It must
match the server's `CapabilitiesResponse` spelling exactly; renaming one side
without the other breaks the pair silently, since unknown features are
ignored rather than rejected.

## Code style

Comments in this repo explain **why**, not what — a line like
`// retry once, the board occasionally drops the first write after reconnect`
is worth its keep; `// increment the counter` is not. This is a deliberate
house style, not incidental: when you add code, add the reasoning behind it,
not a restatement of it. When you touch existing code, keep the comments
that are still true — don't delete or "clean up" a why-comment just because
the line next to it changed, unless the reasoning itself is now wrong.

Beyond that: match whatever the surrounding file already does (naming,
structure, how state is held) rather than introducing a new pattern for one
change.
