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

## Triage

Every open issue carries **exactly one** priority label. The label answers one
question — *what does it cost to leave this alone?* — and nothing else.

| Label | Means | Read it as |
| --- | --- | --- |
| `p0-now` | A rider is being harmed on a shipped build, silently or unavoidably | Drop what you're doing |
| `p1-next` | A shipped feature doesn't work, or this is blocking other work | Next thing you pick up |
| `p2-soon` | A real cost that isn't being paid yet | Schedule it |
| `p3-later` | Wanted, and free to defer | When there's room |

Two more, added on top of the priority rather than instead of it:

- `blocked` — something else has to land first. **Blocked is not a priority.**
  Label what the issue is worth, then add this. A `p0-now blocked` is a
  reason to go unblock it, not a reason to demote it.
- `needs-triage` — no priority decided yet. Every new issue starts here.

### Deciding

Ask these in order and stop at the first yes. First match wins; don't average
them.

1. **Is a rider on a shipped build being harmed right now, in a way they can't
   see or can't avoid?** → `p0-now`
   Wrong information at a decision point, data loss or corruption, a crash,
   a credential or location exposure, battery or network drain they'd never
   attribute to us. The test for "can't see" is: *would a rider know to
   complain?* If the failure is silent, they wouldn't, and silence is what
   makes it a p0 rather than a p1.

2. **Is a shipped feature not doing what it says — or is this blocking other
   work?** → `p1-next`
   A visible failure with a workaround. A feature that's present but broken.
   A tooling or verification gate other issues are queued behind: that gate
   inherits the priority of the most urgent thing waiting on it.

3. **Is there a real cost that isn't being paid yet?** → `p2-soon`
   Security or dependency debt on a surface not yet exposed. Shipped code
   nobody has watched run. A correctness bug in a path riders don't reach
   today. A quality gap in a feature that otherwise works.

4. **Otherwise** → `p3-later`
   New features, design decisions, refactors, polish, docs.

### Type

A second, independent axis — a `bug` can be `p3-later` and an `enhancement` can
be `p1-next`. Pick it with one question: **what commit type closes this?** The
label set is the commit vocabulary this repo already uses, so the answer is
already decided by the time you write the PR title.

| Closing commit | Label | Bumps `versionName`? |
| --- | --- | --- |
| `fix:` | `bug` | patch |
| `feat:` | `enhancement` | minor |
| `docs:` | `documentation` | no |
| `refactor:` / `chore:` / test-only | `chore` | no |

`chore` covers the work that changes no behaviour and bumps nothing:
dependency and config migrations, harness and tooling, running a verification
nobody has run, taking a measurement, and refactors that exist to make
something testable. That row of the Versioning table below has always existed;
until recently it had no label, which is why a sixth of the tracker carried no
type at all.

`question` is orthogonal to all of these and stacks on top — an issue that has
to be *decided* before it can be built.

Two traps:

- **Type by the change, not by the complaint.** "This is slow" closed by a
  measurement is a `chore`; the same complaint closed by rewriting the loop is
  a `bug`. If you don't yet know which, that is what `needs-triage` is for.
- **A defect in the harness is still a `bug`.** It is not rider-facing, and the
  priority label is what says so — don't downgrade the type to make it look
  less alarming.

### The mistakes this replaces

The old `low prio` / `medium prio` pair had no high end, so nothing could be
marked urgent, and half the tracker went unlabelled. These are the specific
ways the labels drifted from the code — worth reading before you set one:

- **Judge the cost of leaving it alone, not the quality of the write-up.** A
  long, careful, well-argued enhancement is still an enhancement. A two-line
  bug report can be a `p0-now`. This is the failure mode here, by a distance:
  effort spent writing the issue kept reading as severity.
- **Re-read the comments before trusting a label.** Evidence posted after
  filing routinely invalidates the original assessment — a bug that hardware
  turns out not to reproduce isn't a bug any more, whatever the label says.
- **An epic takes the priority of its lowest-priority contents,** because one
  issue can only hold one priority. So an epic whose parts don't share a
  priority isn't an issue — it's a parent. Split it into GitHub sub-issues and
  let each carry its own labels; keep the parent for the context and the open
  questions they share. Urgent work hidden inside an umbrella reads as an
  enhancement and gets skipped, and #17 sat at `p3-later` over two `p1` bugs
  for exactly this reason.
- **A checklist in an issue body is a claim about code, and it rots.** #17's
  was re-verified in full against a named commit, and three of its items were
  wrong again a handful of merges later — one whole work area had shipped and
  was still listed as open. Sub-issues close themselves; a checkbox has to be
  re-earned by hand every time someone reads it. Prefer the one that maintains
  itself, and where you do cite code, name the commit you checked against.
- **"Nobody has complained" is not evidence of low priority** when the failure
  is silent. That's the definition of a p0, not an argument against one.
- **Re-triage on evidence, not on age.** An issue doesn't become less important
  by sitting there, and it doesn't become more important either.

### Who sets it

Whoever files the issue picks a priority, or leaves `needs-triage` if they
genuinely don't know. Anyone can change one — leave a comment saying what
changed your mind, so the next reader gets the evidence and not just the new
label. A priority that moves without a reason attached is how the last set
stopped meaning anything.

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

`versionCode` is derived from `versionName` in `app/build.gradle.kts` —
`major*10000 + minor*100 + patch` — so it's never edited by hand and a code
always maps back to the name it came from. Bumping `versionName` bumps
`versionCode` for free; there's nothing else to touch.

The mock-location harness (`tools/mocklocation/build.gradle.kts`) versions
independently — this rule is about `app/build.gradle.kts`, the app people
actually install.

### Release notes

Each GitHub release's notes are GitHub's own auto-generated "What's Changed"
list (`generate_release_notes: true` in `build.yml`), grouped by label via
`.github/release.yml`, and shown to a rider **verbatim** — merged PR titles,
not hand-written prose (#295). That's a deliberate, written-down choice, not
an oversight: a hand-maintained changelog is a second thing to remember on
every PR, on top of the version bump above, and this repo's PR titles already
follow `type(scope): summary (#NNN)`, so the generated list reads reasonably
well without a human rewriting it. Revisit this if that stops being true.

**The release body is that list and nothing else — `build.yml` sets no `body:`,
and adding one has a cost that is not obvious.** The body has two audiences: a
person reading the releases page in a browser, and a rider tapping "What's new"
in Settings, which renders the same text inside the app
(`UpdateCheck.parseRelease` takes `body` entire). Anything written here is shown
to both, and text aimed at the first reads as noise to the second — "Download
the `.apk` below" points at a "below" that does not exist in the app, which is
already doing the download itself.

A standing preamble used to live there — version/versionCode/sha, sideload
instructions, and a "no server configured" paragraph. #295 required it be kept;
#360 retired it, because on v2.31.0 it was 552 of 805 characters and 15 of 21
lines *above* the changes, and because `README.md`'s own "no account and no
server" paragraph covers the setup half better and where people look for it.

So: put installation and first-run guidance in `README.md`, and leave the
release body to say what changed. If you do need a `body:` again, read #360
first — the app-side half of the problem is why it was removed.

The app renders that body as Markdown rather than showing its source (#357), so
`##` headings and `*` bullets become typography and a `[text](url)` link becomes
a link. Two properties hold and are worth not breaking:

- **Links are live, HTML is not.** A tap opens the platform browser through
  `LocalUriHandler`; there is no WebView and no markup path. Live links are
  defensible only because `BuildConfig.UPDATE_REPO` is baked at build time from
  `github.repository` — the body comes from the repo that compiled the APK, which
  the app already trusts to hand it a binary it installs. A runtime-configurable
  update source would change that and this decision with it.
- **Images never load.** No `imageTransformer` is passed and no coil artifact is
  on the classpath, so a Markdown image cannot fire a request when the expander
  opens. That is an absence, not a setting — adding the artifact would silently
  re-enable it.

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
