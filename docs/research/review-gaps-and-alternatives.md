# Independent review: gaps, errors and unconsidered options

Reviewed 2026-09-09 against `HEAD` of `docs/ui-packaging-research` (`11759171`), code at
`efa6c9ad`. Every claim below was re-measured from the tree or from git. Scope: the nine
documents in `docs/research/ui-packaging/`, the two sibling proposals, read against
`docs/guidelines/`, `CONTRIBUTING.md`, `CLAUDE.md`, `docs/refactor/mapscreen/DECISION.md` and #184.

## Verdict

The research is careful about mechanism and careless about premise. Everything about *how* a
move would work is solid and I found no error in it: the package/directory decoupling,
source-set atomicity, the port-triple analysis, `internal`-is-module-scoped, the honest "what
this axis is bad at" sections. The renderer proposal is the best of the three — narrow,
correctly self-limiting at Stage 1. The state-holder proposal is right about the diagnosis and
has a shipped proof in the tree (`NavigationDock` went 19 → 5 parameters via `GoTarget`,
`NavigationDock.kt:57-63`). The packaging case is weak. Proposal C's distinctive feature — a
three-segment `features.<name>.components` tier across fourteen packages — is refuted by two
documents in its own evidence base, which it cites around rather than engages, and by the
repo's co-change history, which nobody measured. Direction: the **state-holder work and the
global `components/` tier are right and should proceed**; the `features/` tier should not, on
the evidence assembled. Two things nobody checked: #184's own stated blocker ("a redesign is
in flight") was resolved three days before capture, and the move as specified turns CI red.

## Gaps

### 1. The repo's co-change history refutes the `features/` axis, and nobody measured it

The proposals argue placement from *static* structure — call-site counts, destination
membership. The test that matters for a directory layout is dynamic: **do commits stay inside
the proposed partition?** I transcribed proposal C's complete file→package assignment ("The
full target tree", `proposal-c-react-informed-nesting.md:895-1090` — 18 packages, all 59
files) and replayed all 382 non-merge commits against it.

Of the 103 commits touching **more than one** `ui/` file — the only ones a layout can help or
hurt:

| | commits | share |
| --- | --- | --- |
| inside one proposed package | 32 | 31% |
| across 2+ proposed packages | 71 | **69%** |
| …of the 32, inside `features/mapscreen/` alone | 28 | — |
| feature-local, outside the mapscreen bucket | **4** | 4% |

The most frequent co-change pair in the repo is `MapScreen.kt ↔ SettingsScreen.kt`, 25 commits
— the two files proposal C separates maximally. The widest commits are consistency sweeps:
`3feb4547` touches 34 `ui/` files across 17 proposed packages; `5939a7d8` ("confirm the ten
destructive actions that ran on one tap") 9 files across 8.

`features/mapscreen/` is 27 of 59 files. It is the flat directory under a new name — 07 says
so itself, and better: "for this specific cluster, `features/mapscreen/` is not partitioning
anything" (`07-mapscreen-worked-example.md:299-303`). Strip it out and the `features/` tier
earns 4 commits out of 103.

The same history endorses the *other* tier. Four recurring defects — `5939a7d8` (#231),
`49b680e1` (#232, five swallowed failures), `ee7076f6` (#239, "there is no shared list-padding
constant or empty-state composable to hang these on"), `94368402` ("10 of 23 were animating
the wrong way") — are all cross-screen inconsistency with no shared component to anchor it,
and all four were fixed by consolidating into one shared file. **The defect record argues for
`components/` and against `features/`.** 00-summary calls `components/` "the least contested
part of the research"; the history says it is the only supported part.

### 2. Two documents in the corpus refute proposal C's shape, and it cites around both

- `04-android-compose-precedent.md:232-247`, the Now in Android feature-module listing:
  "Screen, ViewModel and UI-state file all sit **flat together in one `impl` package** — only
  `navigation/` gets its own subpackage. The same flat pattern repeats across
  bookmarks/interests/search/topic." NIA is the precedent proposal C leans on hardest, and
  NIA's feature package is *flat*. Proposal C cites 04 §1d, §2b, §2d, §6 — never §2e, the one
  section describing the inside of a feature package.
- `05-kotlin-package-idiom.md:460-464`, the verdict: "**One level of category nesting**, not
  one package per component and never one package per component variant." `05:439-448` names
  the two conditions under which finer packaging pays off and concludes flatly: "**Neither
  condition is met** by a single-team Compose UI component library." Proposal C cites 05 §1,
  §2, §3, §5, §6, §7 — never §8. Where it reaches §6 (`proposal-c:1505`) it reframes that
  stated negative as "judgment calls about the future this repo's history cannot settle
  today."

Proposal C concedes there is "zero precedent anywhere, in this repo or the five external ones
surveyed, for nesting three segments deep" (`proposal-c:1430-1434`) — but books it as a cost,
not a refutation. There is a structural reason for the miss: 04 and 05 were commissioned to
test a *retired* proposal's shape (`base/button/confirm/`), and their verdicts were never
re-run against `features/<name>/components/`. §2e says they would fail. **The distinctive
feature of the surviving proposal has never been tested against precedent at all.**

The in-repo counter-example is `car/`: six files, 2,233 lines, **zero subdirectories**,
already separating the three roles the proposals want folders for — entry
(`DetourCarAppService.kt` 13, `DetourCarSession.kt` 41), feature screens (`SpinScreen`,
`SearchScreen`, `NavScreen`), renderer (`CarMapRenderer.kt`). It does it by naming, in the
same Gradle module. No document treats it as evidence either way.

### 3. The move breaks CI, breaks two skill gates, and contradicts a binding guideline

00-summary's claim of "**zero** build/lint/CI coupling to the literal `ui` package path" is
false. The Gradle/AGP half holds — no baseline profile exists anywhere, no `lint.xml`, no
manifest entry naming a `ui` class, `app/proguard-rules.pro` is 10 lines with one `-dontwarn`
— but the coupling is in a third place nobody searched.

**a. CI goes red, with a false security finding.** `.github/workflows/build.yml:143-144` runs
`.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh`, which hardcodes
at `:34` `SELF="app/src/main/java/com/jellemax/detour/ui/SecureFields.kt"` and skips it at
`:40`, because the guard component necessarily wraps a raw `OutlinedTextField`. Proposal C
moves that file to `features/settings/components/`. Running the script's own `awk` on it
without the exclusion returns two hits (`SecureFields.kt:130`, `:172`) ⇒ `:60-76` prints "A
raw text field is collecting something secret-ish", cites ASVS V6.2.6/V6.2.7, CWE-549 and issue #7,
and exits 1. The build fails before detekt and before the tests, with a
security accusation against the component that prevents that bug class. It is green today
(`exit=0`). Worse, `build.yml:138-142` says this grep exists *because* "there is no
Robolectric, no compose-ui-test and no androidTest source set here" — it is **the only
automated check in the repo that touches `ui/` at all**, and the obvious way to green a red
build is to delete it.

**b. Two skill precondition gates.**
`detour-compose-state-hazards/scripts/check-preconditions.sh:31-54` builds its file list from
`UI=app/src/main/java/com/jellemax/detour/ui`; its `count()` is `cat $2 2>/dev/null | grep
-c`, so after a move `cat` fails *silently*, three assertions become 0, and it exits 1 saying
"The body of SKILL.md is stale for at least one hazard class" — misdiagnosing its own failure,
and inviting the next agent to rewrite correct documentation. It passes today (5/5).
`detour-file-split/scripts/check-preconditions.sh` is **already failing**: `FAIL no ktlint /
spotless / detekt configured (expected "", got "build.gradle.kts")` / `Stop and rewrite the
skill.` / `exit=1` — detekt is configured at `build.gradle.kts:13,23-37`. All three proposals
verify with this skill's zero-added-lines proof, and proposal C read that same detekt config
(`proposal-c:1241-1248`) for its decoupling argument without noticing it invalidates the skill
it depends on. detekt is a linter, not a formatter, so the *substance* of "never reformat
inside a move" survives; the contract does not, and per gap 7 that proof is the only safety
net the move has.

**c. A binding guideline mandates the flat layout.** CLAUDE.md routes "where a file lives, or
a new package" to `architecture.md` §1-3 and says "a failure there is a finding, not a
preference." `architecture.md:121`, in "§9. Template for new work", prescribes
`app/src/main/java/com/jellemax/detour/ui/<Thing>Screen.kt`. `detour-file-split/SKILL.md:62`
says it more bluntly ("No new subpackage, no `ui.map`, no `ui.settings`") — 03 notices that
sentence (`03-move-cost.md:472`). Every proposal contradicts both; none schedules amending
either. Post-#183 the skill scopes its rule to a mechanical split (`SKILL.md:71-83`);
`architecture.md:121` carries no such scoping and is the one CLAUDE.md makes non-negotiable.

**d. Compliance and spec artefacts.** `docs/PLAY_LOCATION_DECLARATION.md:31` puts the
background-location disclosure in `ui/MapScreen.kt`; it is at `ui/MapDialogs.kt:28` and
`MapScreen.kt` never names it — a Play compliance document *already* wrong, whose symbol
proposal C moves again. `convergence-2-section-readouts.md:23,41` and
`15-divergence-register.md:55,2440-2441` are live specs carrying `ui/` paths, and per the
staged-refactor staleness gate a path change invalidates an unexecuted spec — so the move
silently blocks the in-flight MapScreen chain.

### 4. #184's own blocker is already resolved, and the sequencing objection is the wrong one
 #184:
"**A redesign is in flight**, so this is explicitly *not* a request to
move files now." Nobody established what it was. It is the five-batch graphite screen redesign
— epic **#188**, delivered by **PR #189** on branch `feat/graphite-screen-redesign`. #189's
first commit is authored `2026-09-04T14:27:37Z`, ~8 hours *before* #184 was filed; it merged
`2026-09-06T16:13:09Z` (100 commits, +11,499/−3,928). #188 closed on merge, citing #184 by
number: "#184 (how `ui/` is packaged, which this work makes more pressing rather than
resolves)". Corroborated by `6bcd263e`/`441f8320` (#229, "refresh the baseline for the
redesign merge") and `94b0a977` (#252, "set the release version to 2.7.0 for the post-redesign
work"). The branch is gone; 22 of 24 `post-redesign`-labelled issues are closed; there is no
open or draft design PR and no design branch; `git log --all -i --grep="material 3"` and
`--grep=expressive` return nothing.

The redesign merged **two days before the research was captured**, and #184 has not been
edited since (0 comments) — so anyone reading it today is waiting on something that shipped.
That is the single most actionable fact in this review.

The sequencing risk the proposals *do* model — merge conflicts — is measurably small. I
replayed the move in a scratch worktree: all 59 files `git mv`'d into proposal C's 18
packages, then all 14 `ui/`-touching local branches merged in, against a control merging the
same 14 into untouched `main`.

| | conflicted files, summed over 14 branches |
| --- | --- |
| control (merge into `main`) | 71 |
| pure `git mv` | 75 (+4) |
| `git mv` + package lines + imports | 77 (**+6**) |

Every incremental conflict is a branch adding a file at the old `ui/` path; the 71-file
baseline is dominated by `app/build.gradle.kts`, conflicting on 13 of 14 branches because
every PR bumps `versionName`. **"Conflicts hard" is not the objection.** The real one is
cadence against the repo's own staleness contract: `versionName` went 2.7.0 → **2.20.0 in two
days** (13 releases), 46 commits touched `ui/` in the five days after #184 was filed, and
`00-chain-design.md:31-33` is unconditional — "If any assertion fails, the spec is **stale**.
Do not adapt the plan to the drift — rewrite the spec." Every count in proposal C is a
precondition-shaped assertion. At this cadence the eight-stage plan spends more time being
rewritten than executed, by the repo's own rule.

Nobody priced the release mechanism either. `build.yml:226-230`: "Pushing twice without
bumping `versionName`… replaces that version's release rather than creating a second one,"
while `CONTRIBUTING.md:55` says a refactor gets **no bump** — so an eight-stage refactor
overwrites the standing GitHub release and its signed APK eight times.
`proposal-narrow-holders.md:344-345` prescribes exactly that; proposal C never mentions
versioning.

### 5. `state.md` §13 is a blessed fourth refactor that none of the three reconciles with

`state.md:60-151` designs a `ScreenModel` layer: pure derivation in `shared/presentation/`,
one `androidx` `ViewModel` shell per screen in `app/ui/ScreenModelHost.kt`, a `FlowWatcher`
tax of 8-14 new iOS classes, and an explicit behaviour change (`WhileSubscribed(5_000)`). It
is "designed, not implemented" and its executable spec
(`docs/refactor/screenmodel/specs/stage-1-screen-model-seam.md`) **does not exist on disk** —
02 caught that (`02-repo-guidelines.md:895-899`) and nobody carried the consequence forward.

- Proposal C's thirteen empty `features/<name>/state/` folders are the slot §13 fills — but
  §13 fills it in `shared/presentation/` plus one host file in `app/`, and
  `architecture.md:117` already names `shared/…/presentation/<Thing>State.kt` as the
  sanctioned home. If §13 lands, twelve of thirteen stay empty permanently.
- `proposal-narrow-holders.md` proposes `NavigationSession` and `SpinSession` as
  screen-`remember`-scoped classes in `app/…/ui/` without citing §13 once, while §13 says "Do
  not write new presenters against §13 before the seam exists."
- `detekt.yml:164-167` disables `ViewModelInjection` noting "Turn this on with the
  screen-model migration, not before" — the build already knows §13 is pending.

Compounding it: **`shared/presentation/` is de facto Android-only.** 68 imports of
`com.jellemax.detour.presentation` across 24 `app/` files (23 under `ui/`); **zero** in
`iosApp/`, checked symbol by symbol across `DisplayFormat`, `AvatarInitial`, `HomeState`,
`TripHudState`, `SpinState`, `NavState`, `BadgesState`, `RoutesState`. The state-holder
proposal notices the narrow version ("`car/` imports nothing from `presentation`") but not
that the whole 25-file, 1,958-line layer has one consumer surface. Adding an *`app/`-side*
holder layer on top of a nominally-shared layer iOS never adopted deepens the asymmetry
`state.md:53-55` calls this repo's actual failure mode. **The claimed ordering — state-holders
unblocks packaging and rendering — is a three-way ordering of a four-way problem.**

### 6. `com.jellemax.detour.data` is a split package across two Gradle modules

`app/…/data/` holds 5 files; `shared/src/commonMain/…/data/` holds 64. Same fully-qualified
package, two modules, and it is the only split package in the repo. Consequence nobody drew:
the metric the whole state-ownership diagnosis rests on — "N direct `import
com.jellemax.detour.data.*` sites inside `ui/`" — **cannot distinguish a screen collecting a
`shared/` store (correct, per `multiplatform.md:18-21`) from a screen reaching into app-side
`data/` (the actual smell).** It conflates the architecture working with the architecture
failing. Secondarily, `internal` means two different things behind one package name — the
confusion proposal C's module-scoping section exists to prevent, in the one place it bites.
Un-splitting it is cheap. Proposal C declines to touch `data/` because `architecture.md:45`
flags it for audit — sound for *moving content*, not for *removing a name collision*.

### 7. Testing: the test tree is missing from the plan, and no number can hold anyone

`app/src/test/…/ui/` holds 6 test files, 707 lines, 51 tests, against 18,033 lines of `ui/` —
**~4%**, all pure functions, none a composable; only 3 of 59 files have a name-matched test.
Proposal C's "full target tree" accounts for 144 files and places **none of the six** — and
under it their subjects land in four different packages (`components/Pills.kt`,
`utils/Format.kt`, `state/SpinResultHolder.kt`, `utils/TripFormat.kt`,
`features/settings/components/SecureFields.kt`), so either the test tree fragments four ways
or the production mirror breaks. Verification item 6's "one new import line each" understates
that.

There are **no Compose UI tests and no way to write one** — no `compose-ui-test`, no
Robolectric, no Espresso, and `app/src/androidTest` does not exist
(`app/build.gradle.kts:270-278` declares junit4 and mockwebserver and nothing else) — and **no
coverage tooling at all**: no jacoco, no kover, nothing in any workflow. The "80% coverage on
new code" target appears nowhere in `CONTRIBUTING.md`, `docs/guidelines/testing.md`,
`CLAUDE.md` or `.claude/`; it comes from an external plugin skill
(`c7-coding-standards/…/maintainable-coding/SKILL.md:118`, also the source of the
500/1000-line and 5/7-parameter thresholds the proposals cite). So no proposal improves
testability, none could be held to a coverage number, and — with gap 3b — **byte-identity of
moves is the only safety net available, and the skill defining that proof is in a stop
state.** The honest answer nobody wrote: the one-ViewModel count is a symptom of §13 being
unimplemented, and §13, not packaging or narrow holders, is what would make screens testable.

### 8. Cross-surface parity: the symmetry that exists, and that proposal C breaks

`iosApp/Detour/` is 30 Swift files, ~6,900 lines, **flat**, with a test target and real CI
(`ios.yml:152-168` runs `xcodebuild test`). Twelve of its filenames are identical to files in
`app/…/ui/` (`MapScreen`, `SettingsScreen`, `FriendsScreen`, `CirclesScreen`, `HistoryScreen`,
`TripDetailScreen`, `RoutesScreen`, `RouteEditorScreen`, `SavedPlacesScreen`, `BadgesScreen`,
`TripCardRenderer`, `Format`). Two flat, name-for-name parallel UI directories is a working
parity mechanism: a reader maps `ui/MapScreen.kt` ↔ `Detour/MapScreen.swift` with no lookup,
and divergence is visible by `ls`. Cross-surface commits are routine (#283, #256, #187, #237, #299
each land on both surfaces in one commit), iOS is actively developed (43 commits in 180
days, latest 2026-09-08), and `detour-shared-core` exists to stop these surfaces drifting.
Proposal C nests Android three segments deep and mentions iOS only to confirm it imports none
of the nine `lib/` packages. **Nobody proposed the symmetric option: restructure both surfaces
the same way, or neither.**

For `car/` the proposals get the top-level call right — leave it flat — but the destination
for its 8 borrowed `ui/` imports is wrong. None of the 8 is a composable: `MapOverlays`,
`PositionMarker`, `setCamera`, `openFreeMapStyleUrl` are MapLibre plumbing,
`MAX_FRAME_WALL_S`/`driveFrameDelta` tuning constants, `formatDistanceKm` a formatter.
Proposal C sends them to `features.mapscreen.{components,utils}` — asserting that code a head
unit renders with is a component of the *phone's* MapScreen. `map/` already holds their
siblings, and `car/CarMapRenderer.kt:64-66` documents consuming `CAM_BEARING_TAU` from
`map/BearingMath.kt:38-43` precisely so "a retune can't split the camera from the marker." No
proposal identifies `map/`. Relatedly, `CarMapRenderer.kt:84-87` claims `CIRCLE_FIX_POLL_MS`
is "Read from there rather than retyped"; `:88` is a `private const val`, not an import — an
untrue parity comment that `conventions.md:26-29` says belongs in the divergence register.

### 9. The metrics are proxies, they are not reproducible, and the headline one is misattributed
 #184's
state-density figure is "304
`remember`/`mutableStateOf`/`collectAsState` sites." Today, same directory, three defensible
commands give **409** (`-o`, word boundaries), **291** (line-matches) and **686** (`-o`, no
word boundaries) — a 2.4× spread with 304 inside it. The number cannot support a trend claim.
The corpus knows this in principle (06's preamble: "the commands are given so they can be
re-taken rather than trusted") and argues from the numbers anyway. 06 §2 likewise calls `car/`
"the worst-factored package in the repo" on mean lines per file — a metric that improves
whenever you split a file in half.

The growth statistic is worse than imprecise. 00-summary: `ui/` "has grown while the decision
sat open… up 55% in files, 31% in lines, with no placement rule added in between."

| date | `ui/` .kt files | lines | `MapScreen.kt` |
| --- | --- | --- | --- |
| 2026-09-04 (#184 filed) | 38 | 13,696 | 1,982 |
| 2026-09-09 (`efa6c9ad`) | 59 | 18,033 | **1,353** |

The file count rose 55% in five days while the biggest file in the directory *shrank 32%*. Of
the 22 files added, 7 came from `4817260a` ("give MapScreen's state an owner, then split its
concerns out", −1,059 lines in `MapScreen.kt`), 7 from `3feb4547` ("rebuild every screen onto
shared pure mappers"), and `ConfirmDialog.kt` from `5939a7d8`'s shared-component extraction.
**Fifteen of the 22 are the output of deliberate decomposition — the repo already doing the
work**, which `DECISION.md:12` records from the other side ("`ui/` files | 1 monolith | 55").
So "the flat layout is not a decision anyone made" is too strong: same-package placement *was*
the decision, taken eleven times in stage 1 of the MapScreen chain for a stated mechanical
reason — and a file-count rise driven by successful decomposition is a strange thing to cite
as decay.

Nor is there review evidence to fall back on: **102 of 143 merged PRs have zero comments and
zero reviews; the repo has 3 inline review comments in total, all on backend C# in PR #11.** A
grep over all 143 PR bodies, 38 review bodies and 61 comments (9,230 lines) for
organisation-pain vocabulary returns 5 hits, all referring to #184 and #183 themselves, none a
complaint. The proposals argue from file counts because that is the only evidence there is —
and they should say so.

## Errors found

| Claim | Where | Correct value |
| --- | --- | --- |
| "**zero** build/lint/CI coupling to the literal `ui` package path… no CI step references it" | `00-summary.md`; `03-move-cost.md:406-450` | False. `build.yml:143-144` runs a script hardcoding `…/ui/SecureFields.kt` at `check-secret-fields.sh:34`; moving that file fails the build with a false security finding (gap 3a). The Gradle/proguard/manifest half does hold. |
| `MapBottomSlot` takes **46** parameters | `00-summary.md:260`; `proposal-c:920`; `boundaries.md:79` | **48** (`MapBottom.kt:57-104`). 07 and the state-holder proposal say 48; the summary and proposal C propagated the stale figure. |
| `SpinDock` has 19 parameters | `00-summary.md:261`; `proposal-c:922`; `state-holders.md:73,100`; `detekt.yml:17-18` | **`SpinDock` does not exist** — deleted in `3feb4547` (2026-09-06). Successor `NavigationDock` takes **5** (`NavigationDock.kt:57-63`). 07 caught this at `:119-120`. |
| "169 direct `import com.jellemax.detour.data.*` sites inside `ui/`", "Confirmed today" | `proposal-c:139-141` | **201**. And the command quoted alongside it — `grep -c "^import com\.jellemax\.detour\.ui" …/ui/*.kt` — returns **0**; it does not measure what the sentence claims. |
| `app/src/androidTest/` "was also searched… confirmed empty result set for all 117 candidate names" | `01-ui-inventory.md:25`, `:403`, `:710`; scope at `proposal-c:180` | **`app/src/androidTest` does not exist.** A run would have printed "No such file or directory". The renderer proposal gets it right (`proposal-entity-renderers.md:292`). |
| "There is no ktlint or detekt in this build." | `conventions.md:20` | detekt 1.23.7 + `io.nlopez.compose.rules:detekt:0.4.22` (`build.gradle.kts:13,23-37`), run in CI at `build.yml:152`. CLAUDE.md routes name/constant reviews here. |
| "The allowlist is **empty**. The app defines no `CompositionLocal` today" | `state-holders.md:146`; same at `detekt.yml:98-100` | `LocalDriveClock` is allowlisted at `detekt.yml:111`, declared at `ui/DriveClockLocal.kt:52`. `detekt.yml` contradicts itself 11 lines apart. |
| `baseline-app.xml` has 136 entries; `LongParameterList` 18, `LongMethod` 17 | `state-holders.md:154,159-160` | **130**, **17**, **16**. The state-holder proposal caught 18→17; nobody caught 136→130 or 17→16. |
| "`iosApp/` has no test target at all" | `testing.md:7`; `DECISION.md:66` | `iosApp/DetourTests/LocationBroadcastTests.swift`; `ios.yml:152-168` runs `xcodebuild test`; added by `987e93db` (#124/#138). Weakens the argument it supports. |
| `docs/refactor/screenmodel/specs/stage-1-screen-model-seam.md` is "the executable spec" | `state.md:63` | Does not exist. 02 noticed; the proposals did not carry it forward. |
| `SettingsScreen.kt` 1,417; `MapScreen.kt` 1,894 with a 1,752-line function; `TripTrackingService.kt` 1,712; `CarMapRenderer.kt` 889; `MapLibreMap.kt` 846 | `boundaries.md:148,155,162,171-172` | 1,228 · 1,353 (one top-level function, `MapScreen.kt:144`) · 1,736 · 946 · 622. Every worked example in §8.8 is stale. |
| Disclosure "implemented as `BackgroundLocationDisclosure` in `ui/MapScreen.kt`" | `docs/PLAY_LOCATION_DECLARATION.md:31` | It is at `ui/MapDialogs.kt:28`; `MapScreen.kt` never names it. A Play compliance artefact already pointing at the wrong file. |
| "38 files / 13,743 lines", "1,984", "34 commits in 60 days" | #184 | Accurate to 0.34% at filing (38 / 13,696 / 1,982 / 34). But the path only existed from 2026-08-05 and the repo is 65 days old, so "34 in 60 days" is 34 in 30 days; `--follow` gives 88 then, 105 now. The 60d/180d framing carries no information here. |
| "five of nine unmerged local branches touch `ui/`" | `02-repo-guidelines.md`; `proposal-c:1275`; #184 | Unverifiable — branch inventory is not in git history. Today: **21 non-main branches, 14 touch `ui/`**. Two reflog reconstructions give 12/20 and 3/4. The "37 commits ahead" half is exact (`feat/rider-id-identity`). |

**What this implies.** The pattern is not random. In four cases (48 params, `SpinDock`,
`androidTest`, the `data` import count) a *sibling document in the same folder* had the right
answer and the summary or proposal C carried the wrong one — while `00-summary.md` promises
"numbers were re-checked against the code rather than copied through a chain of summaries" and
`proposal-c` promises "Every count, file:line, and git-blame date below was re-verified
directly against the tree." The summary also omits 07's verdict, the most damaging finding in
the corpus. Practical rule: **the corpus is trustworthy as an index and untrustworthy as a
citation** — and that goes double for `docs/guidelines/`, stale in seven places above and
outright false in two files CLAUDE.md routes reviewers to.

## Broader options nobody considered

**A. Fix the guidelines before touching the code.** `docs/guidelines/` is what CLAUDE.md makes
every review cite by section number, and it currently tells a reviewer that detekt does not
exist, that the app defines no `CompositionLocal`, that `iosApp/` has no tests, and that
§8.8's worked examples have line counts none has had for days. A review citing a stale section
is a *wrong* review, and CLAUDE.md says "a failure there is a finding, not a preference." Half
a day, larger effect on review quality than any file move, mentioned by no proposal — and it
removes the corpus's own biggest error source, since four of its stale numbers are inherited
from these files.

**B. `components/` and nothing else.** One level, ~11 files, sibling of a still-flat `ui/`.
This is what the co-change data endorses (gap 1), what the four consolidation defects call
for, what 04's and 05's verdicts permit exactly ("one level of category nesting"), what NIA
does, what `car/` demonstrates in-repo, and what 00-summary already calls the least contested
part of the research. ~75-85 import lines, one commit, revertable, and it does not move
`SecureFields.kt`, so CI stays green. It fixes the two trapped-component defects (`HubRow` in
`HubScreen.kt`, `BackgroundLocationDisclosure` in `MapDialogs.kt`) and gives the next
`ConfirmDialog`-shaped fix somewhere to land, while committing nothing about `features/`.
**This is the strongest option on the table and it is buried inside a proposal whose
distinctive feature fails.**

**C. Fix the named defects and stop.** `06-out-of-scope-findings.md:7` lists seven concrete
`ui/` defects, each independently fixable, none needing a packaging decision: dead
`SecretTextField`, the `SectionLabel` shadow at `BadgesScreen.kt:179`, the
`SettingsHub`/`SettingsScreen` name swap, `Obd2PairingScreen` misnamed as a route,
`Navigation.kt` as a holding pen, the trip-formatting utilities inside `HistoryScreen.kt`, the
hand-rolled `* 3.6` in `car/`. Add option A and the untrue `CIRCLE_FIX_POLL_MS` comment. A day
of work removing every specific, demonstrable harm anyone has actually named. The residual
claim — that 59 flat files are hard to navigate — has, per gap 9, no evidentiary support in
this repo whatsoever.

**D. A written placement rule with no move at all.** Literally what #184's own "done"
definition asks for: a decided axis written in `CONTRIBUTING.md`, a project skill carrying the
placement test, and a stated position on whether the layout is applied now, lazily, or held as
a target. Proposal C's placement test serves, minus the `components/state/utils` sub-tier.
Naming carries most of the value a directory would — `Map*`/`Spin*`/`Ride*` already groups the
map cluster on an `ls`, `car/` proves the point at 2,233 lines, and
`SettingsHub.kt`/`SettingsScreen.kt` is confusing because the *names* are wrong, which a
rename fixes and a directory does not. Zero import lines, zero conflicts, zero release
clobbers, no CI break, no `git log --follow` loss. Forecloses nothing.

**E. Un-split `com.jellemax.detour.data`** — five files; see gap 6.

**F. Do nothing, on purpose, and say why.** The honest case for leaving #184 at `p3-later`:
the codebase is 65 days old and shipping ~6 releases a day, the pain is unevidenced, the
layout is the residue of a *successful* refactor, and the same files are moving under active
decomposition — so any layout decided now is decided against a directory that will be
materially different in a fortnight. The counter-case is real, but it needs A and B, not
`features/`. **No document argues F, and it deserved a paragraph.**

## What I would do

1. **Fix `docs/guidelines/` and the error table above.** Half a day, highest ratio of effect
   to risk, because it is what every review and every agent cites. Include
   `detekt.yml:98-100`'s self-contradiction, `detour-file-split`'s red precondition gate, and
   `docs/PLAY_LOCATION_DECLARATION.md:31`.
2. **Record on #184 that the redesign has landed** (#188/#189, merged 2026-09-06) so its own
   stated blocker stops blocking it, refresh its stale counts, and re-triage from there.
3. **Land the global `components/` tier** (option B) as one commit — with a patch version bump
   so it does not clobber the release, `HubRow` and `BackgroundLocationDisclosure` extracted
   in separate prior commits per `detour-file-split`'s discipline, and `:app:detekt` in the
   verification, which no proposal currently runs.
4. **Write the placement rule** (option D), amending `architecture.md:121` and
   `detour-file-split/SKILL.md:62` in the same change, and stating explicitly "no `features/`
   tier, and here is why", citing gap 1's numbers so the decision is checkable rather than a
   preference.
5. **Do not create `features/`.** Revisit only if someone re-runs the partition test and it
   comes back the other way, or if a Gradle-module extraction is actually intended — 05 §6's
   condition 2, still unmet.
6. **Take the two axis-independent extractions** — `nav/AppRoot.kt` out of `MainActivity.kt`
   (~360 of 573 lines; `AppRoot` takes zero parameters) and the eight-file `entry/` tier. Both
   correct on their own merits, neither depends on a packaging decision, neither moves
   `SecureFields.kt`. These are the proposals' best concrete contributions.
7. **Move `car/`'s six borrowed map symbols to `map/`**, not to a `features/` package —
   `map/BearingMath.kt` already holds their siblings for a stated reason, and it removes six
   of the eight `car/`→`ui/` imports.
8. **Sequence the state work §13 → narrow holders → renderer**, not state-holders first, and
   resolve §13's status before writing `NavigationSession`: write the missing spec, or amend
   `state.md` §13 to retire it. Until then a new app-side session holder is uncommitted work
   of unknown direction, on top of a `presentation/` layer iOS never adopted. The renderer
   proposal's Stage 1 is independent of all of it.
9. **Fix the named defects** (option C) as files are touched. `SecretTextField` is dead code
   and can go today.

The one thing I would not do is treat "the folder has no shape a newcomer can infer" as
established. It may well be true. Nothing in this repo's history demonstrates it, and three
documents were written as though it had been.

## What I could not determine

- **Whether the layout actually costs anything.** No review record exists to mine. **Settle it
  by** giving two agents, or a new contributor, the same scripted task ("add a confirmation
  dialog to X", "find where the speed HUD is assembled") against the current tree and against
  a `components/`-only tree, and measuring time to first correct file. Cheap, and the only
  direct evidence anyone could gather.
- **Whether the redesign has a tail.** #189 merged 2026-09-06, #252 closed the stack, no
  design branch or draft PR exists, 22 of 24 `post-redesign` issues are closed — but `ui/`
  still gained 21 files that week, so I cannot separate "redesign done" from "redesign done,
  follow-up UI work continuous." **Settle it with** a close-out note on #184.
- **Whether `Style`/`GeoJsonSource` are fakeable in a JVM unit test** — the hinge of the
  renderer proposal's testability question, which it marks unverified. No `androidTest`, no
  Robolectric, so my prior is no. **Settle it with** one throwaway test constructing a
  `GeoJsonSource` under `:app:testDebugUnitTest`.
- **Whether §13 is still intended.** `state.md` says "designed, not implemented," the spec is
  absent, `detekt.yml:164-167` waits on it. Live, parked or abandoned changes the ordering of
  all three proposals. **Settle it with** a Status block on `state.md` §13, as the refactor
  chain's specs carry.
- **Whether the iOS/Android flat-directory parity is deliberate.** The 12 name-for-name
  matches look designed; nothing documents it. **Settle it with** a line in `architecture.md`
  §1 claiming the symmetry or disclaiming it.
