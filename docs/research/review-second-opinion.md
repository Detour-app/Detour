# Second-opinion review: the three refactor proposals

Independent second review of `docs/research/ui-packaging/`,
`docs/research/state-holders/` and `docs/research/renderer-layer/`. Written
2026-09-09 against `efa6c9ad`. Every number below I measured or reproduced
myself; a document citation attributes a claim, never supports one. Five
parallel lenses were dispatched; where two disagreed I arbitrated against the
code or the primary source. `review-gaps-and-alternatives.md` was read only
after I locked my own findings.

---

## Verdict

The corpus's measurements are excellent and its central mechanism claim is
false. The per-file inventory, the import-cost script, the z-order table and the
port-triple analysis all survived checking — I could not break the primary
tables. But the lever that makes proposal C affordable, that a `git mv` can
leave the `package` line behind so one big rewrite becomes many small safe
commits, does not hold in this build. A second, unrelated coupling turns the
build red on the very stage the summary calls free; I reproduced it. And the
plan's centre, the `features/` tier, is refuted by the repo's own commit history
and by proposal C's appendix, which concedes 13 of 14 packages gain an empty
tier, 7 become one file three segments deep, and no precedent exists anywhere
for the shape — then proposes it anyway.

Direction: **the state-holder diagnosis is right; `components/` is right in
substance and mis-argued in the summary; `entry/` and the `nav/AppRoot.kt`
extraction are right but not free; `features/` should not be built; `lib/` is
the worst ratio in the corpus.** The renderer proposal is best-argued and least
urgent — it overstates all three targets, and an open crash sits in the code it
would restructure.

---

## Highest-value findings

### 1. The sequencing lever does not exist: detekt fails a package/directory mismatch by default

`00-summary.md:189-195` states the claim the whole plan is built on:

> **Kotlin does not require a file's `package` line to match its directory**, and
> nothing in this repo's tooling (detekt only […]) checks that they agree. […]
> That turns one big, all-or-nothing rewrite into many small, independently safe
> commits — the directory can move ahead of the package declaration catching up.

Something does check. detekt's `InvalidPackageDeclaration` (alias
`PackageDirectoryMismatch`) reports "when the file location does not match the
declared package", is **active by default since v1.21.0**, and needs no type
resolution. Two independent sources: the pinned default config at
`raw.githubusercontent.com/detekt/detekt/v1.23.7/…/default-detekt-config.yml`
(`active: true, rootPackage: '', requireRootInDeclaration: false`) and
`detekt.dev/docs/rules/naming/` ("Active by Default: Yes — Since v1.21.0").

All four repo-side conditions hold:

| Condition | Verified |
| --- | --- |
| `buildUponDefaultConfig = true` | `build.gradle.kts:29` |
| no override anywhere | `detekt.yml`'s `naming:` block overrides only `FunctionNaming`; zero hits for the rule or its alias in `config/`, `.github/`, either `build.gradle.kts` |
| zero baseline entries | `grep -c InvalidPackageDeclaration config/detekt/baseline-app.xml` → **0** |
| why zero: nothing mismatches today | all 370 `.kt` files under `app/src` + `shared/src`: **0 mismatches** |
| runs in the task CI invokes | no type resolution needed; CI runs `./gradlew :app:detekt` (`build.yml:152`) |

So a directory move leaving the `package` line untouched produces one
unbaselined violation per moved file and fails CI. **The "directory moves ahead,
package line catches up" strategy is unavailable.** Each package must move its
directory, its `package` lines and every importing call site in one commit,
restoring proposal C's ~302 import lines as an atomic per-package cost —
precisely the shape the sequencing section was built to avoid. Proposal C read
this same detekt config (`:1241-1248`) to make the decoupling argument and did
not check the rule list. The move stays possible; it becomes two or three big
commits, and a half-finished move stops being a safe resting state.

**Arbitration note.** Two subagents contradicted each other — one asserted
`active: true`, the other reported a prior of `active: false` and honestly
flagged it unproven. I could not run `:app:detekt` either (the Gradle module
cache is empty), so I settled it from the primary sources rather than from either
report. My evidence is documentary, not executed.

### 2. A CI step hard-codes a `ui/` path, and the stage the summary calls free turns the build red. Reproduced.

`00-summary.md:185-187` and `03-move-cost.md:437-444`: "**zero**
build/lint/CI coupling to the literal `ui` package path… **Result: zero.**"
`03-move-cost.md:447-450` then rejects the right answer: `.claude` skill files
"are **not executed by the build**."

One is. `build.yml:144` runs
`.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh`,
which hard-codes at `:34`
`SELF="app/src/main/java/com/jellemax/detour/ui/SecureFields.kt"` — the single
file exempted from a regex that fails the build when a raw text field looks like
it collects a secret. `proposal-c:848` moves `SecureFields.kt` to
`features/settings/components/`. I cloned the repo to scratch, ran the gate
clean (exit 0), performed that exact `git mv`, and re-ran it:

```
A raw text field is collecting something secret-ish:
…/features/settings/components/SecureFields.kt:130
…/features/settings/components/SecureFields.kt:172
exit=1
```

The exemption stops matching, so the component that *implements* the rule trips
it. The gate exists because of a shipped credential leak (`build.yml:139-143`,
issue #7). The fix is one line, but it must land before or with the move — and
proposal C's verification rule for that stage (`:1449-1456`) requires that
"**No** other file's diff should exist at all", which the fix violates.

Two more scripts hard-code the same directory without being CI-wired:
`detour-compose-state-hazards/scripts/check-preconditions.sh:31,54` (four of its
five checks flip to 0 after the move, and its failure text misdiagnoses that as
the skill's prose being stale) and
`detour-file-split/scripts/check-preconditions.sh:33`
(`WORKED=…/ui/SpinShare.kt`, which proposal C moves) — the gate of the skill
that produced the results in finding 4, already failing today on an unrelated
assertion.

### 3. The `lib/` stage breaks the manifest silently, and Android lint never runs

`app/src/main/AndroidManifest.xml` names 13 components by **dot-relative** class,
resolved against `namespace = "com.jellemax.detour"` (`app/build.gradle.kts:77`).
Proposal C budgets Stage 1b (`entry/`) at "8 manifest `android:name` edits"
(`:1312`) with a verification step — correct. Stage 7 (`lib/`) is budgeted at
"61 import lines" (`:1318`) with **no manifest edits**. Moving `notif/`,
`update/` and `media/` under `lib/` breaks three entries `entry/` does not
cover: `.notif.CircleNotifyService`, `.update.UpdateDownloadService`,
`.media.MediaListenerService`.

Three edits is trivial; the failure mode is not. CI runs exactly three Gradle
commands — `:app:detekt` (`build.yml:152`), the two unit-test tasks (`:160`),
`assembleGithubRelease`/`bundleRelease` (`:175`). **There is no `lint` task and
no `lint {}` block in `app/build.gradle.kts`**, and lint's `MissingClass` is the
only checker for a stale `android:name`. There are also **zero instrumented and
zero Compose tests** — `app/src/androidTest` does not exist, and
`build.yml:139-141` says so. A missed entry compiles, assembles, installs, and
throws `ClassNotFoundException` only when Android instantiates the component: on
the next FCM push, mid-self-update, on notification-listener bind.

`media/` has **zero** inbound Kotlin imports — its only reference is the
manifest string — so by proposal C's own thin-shell logic it belongs in
`entry/`, not among the nine `lib/` ports.

### 4. Every named defect is shrinking fast under the current regime

Measured at `5df52069` (2026-09-04, the day #184 was filed) against `efa6c9ad`:

| File | At #184 | HEAD | Δ |
| --- | --- | --- | --- |
| `ui/MapScreen.kt` | 1,982 | 1,353 | **−31.7%** |
| `ui/SettingsScreen.kt` | 1,456 | 1,228 | −15.7% |
| `tracking/TripTrackingService.kt` | 2,156 | 1,736 | −19.5% |
| `ui/` mean file size | 359 | 306 | −15% |

`00-summary.md:19-21` presents the same five days as decay — "up 55% in files,
31% in lines, with no placement rule added in between." Files grew faster than
lines, so mean file size *fell*, and 15 of the 22 new files are output of two
improvement commits plus one shared-component extraction: `4817260a` ("give
MapScreen's state an owner, then split its concerns out"), `3feb4547` ("rebuild
every screen onto shared pure mappers"), and `5939a7d8`, which added
`ConfirmDialog.kt` — exactly what the `components/` tier wants, achieved without
it. The directory grew because it was being fixed, by the skill whose gate
finding 2 breaks. All three proposals change none of these numbers
(`00-summary.md:239-247`).

### 5. The proposed partition matches neither axis of how work happens

**Across the module boundary (mine).** Of 106 commits touching `app/.../ui/`,
**84 (79%) also touch Kotlin outside `ui/`**; 22 are confined to it. Six-month
churn leaders: `ui/MapScreen.kt` (50), `tracking/TripTrackingService.kt` (31),
`MainActivity.kt` (25), `ui/SettingsScreen.kt` (24),
`shared/.../data/Settings.kt` (22), `car/CarMapRenderer.kt` (21) — four of six
outside `ui/`. The recurring unit of work is a vertical slice `ui/` +
`tracking/` + `shared/data/`, and proposal C explicitly refuses to put the data
layer in a feature package (`:78-132`) — right on Kotlin grounds, and also what
makes the tier address the wrong seam.

**Within `ui/` (three independent replays).** Against proposal C's own
file→package map, of commits touching 2+ `ui/` files, **61-69% would be split
across 2+ proposed packages**, and the single-package wins concentrate in
`features/mapscreen` (21 of 25; 28 of 32 in another replay). **13 of the 14
proposed feature packages have never once contained a co-change.** Only 26% of
within-commit file pairs land in the same proposed package. My own first cut
gave a weaker 39% because I failed to exclude single-file commits; cite the
filtered figures, not mine. Widest real commits: `5939a7d8` spans 8 proposed
packages, `3feb4547` spans 17.

**And statically the tier collapses the same way.** From proposal C's target
tree (`:1004-1071`): `features/mapscreen` 27 files, `features/settings` 7, **the
other 12 feature packages 14 files combined**, 14 in the shared tiers. Twelve
directories for fourteen files; seven features are one file each, reached at
`com.jellemax.detour.features.<name>.components.<File>.kt` — three new segments
to reach what is one hop from `ui/` today. And `features/mapscreen/` is the flat
directory renamed, as `07-mapscreen-worked-example.md:299-303` says itself: "for
this specific cluster, `features/mapscreen/` is not partitioning anything."
`proposal-c:1360-1440` concedes all of this and calls the single-file case "the
correct outcome". It is not an outcome; it is the whole result for 12 of 14.

### 6. The headline argument for `components/` is wrong arithmetic, and the tier already exists

`00-summary.md:67-68`: "Of 128 non-private top-level declarations in `ui/`, only
about **17 are genuinely shared** (called from 2+ distinct files)." By that
criterion it is about **30**. The 17 counts shared *components* —
`01-ui-inventory.md`'s Table A, which deliberately excludes the utility and
map-primitive buckets — while the 128 denominator comes from
`03-move-cost.md`'s script, which includes them. I spot-checked four of the
fifteen in the gap: `formatDistanceKm` (`ui/Format.kt:42`, 6 other `ui/`
callers), `openFreeMapStyleUrl` (`ui/MapLibreMap.kt:41`, 5), `cameraForPoints`
(4), `formatSpeedKmh` (`ui/Format.kt:30`, 3). Neither source is wrong; the
summary is wrong for dividing one by the other. "Only 13% shared" is really ~23%.

Separately, eight of the nine files the summary tabulates as the *new* global
`components/` tier already exist in `ui/` under exactly those names — `AppBar.kt`
(45 lines), `Cards.kt` (75), `GlassSurface.kt` (44), `ConfirmDialog.kt` (39),
`Pills.kt` (213), `TripCardRenderer.kt` (771), `TravelModeIcon.kt` (13),
`Theme.kt` (55). Proposal C is explicit and correct ("`ui/AppBar.kt`,
unchanged", `:974`); `00-summary.md:82-90` prints the table without that column,
so a summary-only reader over-reads the tier as new work.

The recommendation survives, narrower: the library is already factored and
named; what is missing is that a reader cannot tell which nine of 59 filenames
are shared. One observed casualty — `BadgesScreen.kt:179` declares a `private
fun SectionLabel` shadowing the shared `Cards.kt:66` one at `:100`, so the
screen looks like a consumer and silently keeps a divergent copy.
`01-ui-inventory.md:708` diagnosed that correctly before I did.

### 7. `com.jellemax.detour.data` is split across two modules, making `lib/`'s exclusion number uninterpretable

`app/.../data/` (5 files, 295 lines) and `shared/commonMain/.../data/` (64 files,
12,121 lines) declare the **same package**, so `import
com.jellemax.detour.data.X` does not say which module X comes from.

Proposal C's `lib/`-decision table cites `data/` at "414 lines / 114 files" of
inbound imports (`:196-200`) as part of the case for excluding it. That grep is
over the ambiguous FQN and is overwhelmingly counting `shared/`. Imports
resolving to `app/data`'s five actual declarations total **9** — `ConfigFile` 1,
`Gpx` 2, `RouteFiles` 1, `TripCardFile` 1, `syncQuietly` 4. The blast radius is
off by ~46×. The exclusion may still be right (four of the five are hard-blocked
on `Context`/`Uri`/`FileProvider`), but the number offered as evidence measures a
different package.

Two verified facts show the misplacement problem is real and not where the
proposals look. **`commonMain` KDoc-links into `:app` twice, and neither link
resolves** — `data/NavAnnouncer.kt:23` → `[com.jellemax.detour.map.NavPolicy]`,
`data/Settings.kt:214` → `[…tracking.TripTrackingService]`; `map/NavPolicy.kt`
(118 lines, pure, tested, and iOS has no arrival or reroute logic at all) is on a
direct path to a third copy. **The leak runs both ways**: `data/UpdateCheck.kt:42`
declares `PLATFORM_ANDROID_PHONE` and `:100` `conventionalPhoneAsset(v) =
"detour-$v.apk"` — an APK filename in `commonMain`, consumed there at
`UpdateClient.kt:58,61`, on a path iOS can never take.

The generalisation: **neither proposal's placement test distinguishes "pure"
from "shareable."** `map/NavPolicy.kt` is pure and belongs in `:shared`;
`tracking/Dormancy.kt` is equally pure and belongs in `:app`, its vocabulary
being Android foreground-service lifecycle. A test routing on "no Compose
import, no platform type → `utils/`" gets one of those wrong.

### 8. The repo already executed a whole-tree package move, and the corpus never found it

Commit **`a8093b80`** (2026-08-05, "refactor: rename the app to Detour") renamed
`com.jellemax.maproulette` → `com.jellemax.detour` across **83 files**: 469
insertions, 469 deletions, of which **169 are import lines** and 57 are
`package` lines. The string `maproulette` appears **zero** times in
`docs/research/`.

`proposal-c:1425` says "No precedent exists in this repo for nesting at all, in
either direction" — true about nesting, but the *cost* question never needed
estimating. This commit corroborates the estimate: 169 imports over 83 files is
2.0/file, against the 5.1/file the ~302 over 59 projects for `ui/` — necessarily
higher, because nesting splits one package into many and intra-package
references that needed no import now need one. **The estimate is sound and now
has an anchor.** The same `git log --diff-filter=R` shows the `app/.../data/` →
`shared/commonMain/` extraction recorded as renames (R069-R100), and testing
confirms `git blame` follows a pure rename and a rename-plus-package-line change
with no flags. It is *extractions* where default blame goes flat and only `git
blame -C` recovers history — and proposal C runs four.

### 9. The renderer proposal overstates all three targets, and there is an open crash in that code

`proposal-entity-renderers.md:15-16` scopes the work as "`MapOverlays` (622
lines) … `ui/FogView.kt` (614) and `car/CarMapRenderer.kt` (946)". Those are
*file* lengths:

| Class | Extent | Class lines | Proposal says |
| --- | --- | --- | --- |
| `MapOverlays` | `ui/MapLibreMap.kt:123-533` | **411** | 622 (+51%) |
| `FogView` | `ui/FogView.kt:43-614` | **572** | 614 (+7%) |
| `CarMapRenderer` | `car/CarMapRenderer.kt:112-700` | **589** | 946 (+61%) |

`CarMapRenderer.kt` holds a second class at `:738-946`, which the proposal
identifies at `:252` — then still headlines 946. None of this touches its
reasoning: the six retained fields, the twelve-row z-order table and "this is
one entity, not three" all held up. It changes priority — and **#301 is open**
("Map render thread SIGSEGVs when fixes arrive faster than ~1 Hz, killing the
trip in progress", `bug`, `p3-later`) in exactly this code, while
`ui/FogView.kt` has been touched once since creation.

### 10. The repo's own hard limits are unenforced, and `shared/` has no static analysis

`boundaries.md:61`: "A file over 1,000 lines, or a function over 100. These are
the hard limits." The function limit is enforced (`LongMethod` 100,
`detekt.yml:30-31`). The **file** limit is enforced nowhere: `detekt.yml`'s
`complexity` block sets `LongParameterList` 7, `LongMethod` 100, `LargeClass`
500, `NestedBlockDepth` 4, `CyclomaticComplexMethod` 15, and detekt ships no
file-length rule at all. `LargeClass` measures a class body, and `MapScreen.kt`
contains no class, so it is structurally exempt. Worse, `detekt.yml:43-45`
disables `TooManyFunctions` on the stated grounds that "the file-length and
function-length rules already cover the real risk here" — a rule that does not
exist. And detekt applies to **`:app` only** (`build.gradle.kts:16-24`), so
`shared/` has zero static analysis while holding `drive/ConvoyRelay.kt` at
**1,204 lines**, over the hard limit, plus `data/Settings.kt` (753),
`data/Auth.kt` (660), `data/RoadRoulette.kt` (550).

A file-length check, `:shared:detekt` and Android lint enforce what the repo
already wrote down, cost no import churn and break no branch; the mechanism
already exists, since `check-secret-fields.sh` is a ~70-line bash script wired
in as a named CI step. No document in the corpus proposes any of it. Related:
**detekt cannot catch the misleading-filename defect** — `MatchingDeclarationName`
fires only on a single top-level class/object/interface, not a function, so
`SettingsHub.kt:45` declaring `fun SettingsScreen` and `SettingsScreen.kt:173`
declaring `fun SettingsSpokeScreen` go unflagged. Of 16 non-private `*Screen`
composables, exactly **two** are real offenders.

---

## Errors found

Only what I verified myself.

| Claim | Where asserted | Correct value |
| --- | --- | --- |
| "nothing in this repo's tooling checks that [package and directory] agree" | `00-summary.md:189-195`, `05-kotlin-package-idiom.md` §3 | **False.** `InvalidPackageDeclaration` is active by default since detekt v1.21.0, not overridden, zero baseline entries. Finding 1. |
| "zero build/lint/CI coupling … **Result: zero**"; `.claude` scripts "are not executed by the build" | `00-summary.md:185-187`, `03-move-cost.md:437-450` | **False.** `build.yml:144` executes `check-secret-fields.sh`, hard-coding `ui/SecureFields.kt` at `:34`. Reproduced as exit 1. |
| `lib/` costs 61 import lines over "40 files" | `proposal-c:194,1318`, `00-summary.md:155` | 61 lines exact; **29** distinct files (40 double-counts files importing from two ports). Plus **3 unbudgeted manifest edits**. Stated scope names `app/src/androidTest`, which does not exist. |
| `data/` inbound imports "414 lines / 114 files" | `proposal-c:196-200` | Measures the *shared* half of a split package. `app/data`'s real inbound total is **9** import lines. |
| "only about **17** of 128 declarations are genuinely shared" | `00-summary.md:67-68`, `proposal-c:605` | ~**30**. Two correct measurements from different documents divided by each other. |
| `MapOverlays` 622 lines; `FogView` 614; `CarMapRenderer` 946 | `proposal-entity-renderers.md:15-16` | File lengths, not class lengths: **411** / **572** / **589**. |
| "72 top-level `internal` declarations inside `ui/` **are already read across package boundaries** today" | `05-kotlin-package-idiom.md:455` | **8** are; 72 is how many *exist*, and the symbols that do cross are **public**. §5's conclusion — `internal` is module-scoped, so packages buy no encapsulation — is independently true and survives. |
| `MapBottomSlot` takes 46 parameters | `boundaries.md:79,81,91,164`, `ui/MapScreenState.kt:25`, `00-summary.md:260`, `proposal-c:920,1216,1422` | **48** (`ui/MapBottom.kt:56-105`). `07:118` says 48. Also found by the other reviewer. |
| `SpinDock` has 19 parameters | `00-summary.md:261`, `proposal-c:922`, `state-holders.md:73`, `detekt.yml:17-18` | **`SpinDock` does not exist**; renamed `NavigationDock`, cut to 5 via `GoTarget`. `07:120` caught it. Also found by the other reviewer. |
| Average speed "is voice-announced … rather than drawn as a HUD widget"; "no 'recording indicator' composable exists" | `01-ui-inventory.md` §B | `ui/MapHud.kt:167` renders `state.averageText` with an independent over-limit accent at `:168-175`; `SpeedHudState.averageText` documented at `TripHudState.kt:20-23`. `ui/RideSheet.kt:114` renders `subline = "… · recording"`. |
| "`UpdateProgressButton` is the only custom progress composable in the directory" | `01-ui-inventory.md` §B | `RouteProgressTrack` (`ui/Navigation.kt:214`, `internal`, `Canvas`-drawn) is one; the conclusion survives, both being single-caller. Also: `ui/MapLibreMap.kt` is **622** lines, not `boundaries.md:172`'s 846; and `00-summary.md:47`'s "`SettingsScreen.kt` alone serves 8" is **7**. |

### The mechanism, which matters more than the count

§B states its method: it verified "no average-speed HUD composable exists" with
a grep **restricted to declaration lines**. The composable is named `SpeedHud`.
A declaration-name grep for a *concept* only finds code whose author chose that
word, so the method cannot fail safely. Both §B claims produced that way are
wrong; every §B claim produced by *caller counting* is sound, because `private`
guarantees no external caller by construction. Findings 1 and 2 share the shape:
the tooling search read what config files *contain* and never followed what they
*execute* or which defaults they inherit.

**Reliability is degraded by construct, not at random.** Primary counts are
essentially perfect — 59/59 per-file line counts byte-identical to `wc -l`,
265/265 declaration citations resolving to the right symbol, the import-cost
script reproducing exactly (128 symbols, 107 cross-referenced, 366 raw, 64
artifacts, 302). Multi-line range citations fail at roughly a third, four
pointing at unrelated code. Derived and *joined* numbers are where every serious
error lives — and `00-summary.md` is the document that joins figures across
sources. **Trust it as an index; re-derive anything you quote out of it.** That
is backwards for the file written "for anyone who has not read them."

### Three claims of mine that did not survive my own check

1. **"The formatters are triplicated across `app/`, `shared/` and `iosApp/`."** A
   name-collision grep showed `formatDistanceKm`, `formatDurationHistory` and
   `formatGForce` declared in `ui/Format.kt`, `presentation/DisplayFormat.kt`
   and `iosApp/Detour/Format.swift`. Reading the code killed it:
   `ui/Format.kt:7-11` imports the shared versions under aliases and all three
   **delegate** (`:17`, `:28`, `:42-43`, `:61-62`) — the decimal separator is
   resolved at the render path via `Settings.decimalSeparatorChar()` and passed
   into the pure shared formatter, and `DisplayFormat.kt:112-114` documents it
   from the other end. Only iOS re-implements, in a 59-line file.
2. **"`ui/TravelModeIcon.kt` is dead code."** It declares `val TravelMode.icon`
   (`:9`) with three live callers (`ui/RouteEditorScreen.kt:418`,
   `ui/HistoryScreen.kt:398`, `ui/HomeSheet.kt:286`). Extension members are
   invisible to symbol-name grep — which is why proposal C's "302 after
   excluding parser artifacts on extension functions" caveat is well founded,
   and why every caller-count table including `glassBorder` is a floor.
3. **"The `SectionLabel` shadow is a finding nobody named."** It is
   `01-ui-inventory.md:708`, diagnosed better than I diagnosed it.

Three name-shaped searches, three wrong conclusions, all caught by reading the
code. Two subagents also produced claims that did not survive — one asserting
`PLATFORM_ANDROID_PHONE` has no production callers (`UpdateClient.kt:58` uses
it), and the `InvalidPackageDeclaration` prior — and both flagged their own
uncertainty, which made arbitration possible.

---

## Where I diverge from the other reviewer

`review-gaps-and-alternatives.md` (572 lines) and I converge on the verdict —
`components/` yes, `features/` no, state work first — and independently on three
findings: the co-change refutation, the detekt-baseline signature coupling, and
the growth statistic being misattributed. Convergence from different methods is
the useful signal; treat those three as settled.

**Their within-`ui/` number is better than mine and I defer.** They filtered to
commits touching more than one `ui/` file and got 69%; two further replays got
63% and 61%. My unfiltered 39% was diluted. My distinct contribution there is
orthogonal: 79% of `ui/`-touching commits leave `ui/` entirely.

**Findings they do not have:** the `InvalidPackageDeclaration` default (finding
1, which invalidates the whole proposal's sequencing strategy), the CI gate that
breaks, the manifest-plus-no-lint silent risk, `a8093b80`, the renderer classes'
real sizes, the "17 vs 30" arithmetic, the absent file-length rule, and
`shared/` being outside detekt. They found the split `data` package; I add that
proposal C's `lib/` decision rests on a number that split package makes
uninterpretable.

**Findings I do not have:** the `versionName`-versus-release conflict
(`build.yml:226-230` against `CONTRIBUTING.md:55` — an eight-stage refactor
overwrites the standing release eight times), `state.md` §13 as an unreconciled
fourth refactor whose executable spec is absent from disk, the missing test
source set in the target tree, and the iOS/Android name-for-name flat parity
proposal C would break. I checked the last and it holds: `iosApp/Detour` is
flat, 30 Swift files, 12 filenames matching `ui/` exactly.

**Genuine disagreement.** They rank "fix `docs/guidelines/`" first; I would put
**enforcement first** and guidelines second. Their leverage argument is right,
and a stale guideline misleads a reader who can then check the code. An absent
check misleads nobody and catches nothing: it lets a `ClassNotFoundException`
ship, and it is what turns the first packaging commit red. Milder disagreement
on `components/`: they would land it as one commit; I would write the rule and
the nine-file list into `CONTRIBUTING.md` and move nothing, per finding 6 — the
move buys `ls`, the list buys the same knowledge for zero churn, and after
finding 1 the move is no longer splittable into safe steps.

I also part company with my own null-case lens, which found by simulation that
branch overlap is a non-issue: of ~21 branches ahead of `main` touching `ui/`,
all but one are squash-merge artifacts, and the one live draft PR (#288) merged
onto a fully repackaged tree with zero conflicts. Unreproduced by me, but
consistent with `gh pr list --state open` returning one row — and it removes an
objection I had been prepared to make.

---

## What I would do

1. **Settle finding 1 with one command in the devcontainer** — mismatch a
   package line, run `:app:detekt`. Every sequencing decision turns on it.
2. **Fix `check-secret-fields.sh:34` and its stale remediation text; add
   `:app:lintGithubRelease`, a file-length check and `:shared:detekt`.** Half a
   day. Nothing may move before the first. Closes the only finding that can ship
   a crash and makes three of four motivating defects loud.
3. **Fix #271 (`p0-now`) and #301** — the latter is in the code proposal 3
   restructures.
4. **Two `git mv`s for the filename defect**: `SettingsHub.kt` →
   `SettingsScreen.kt`, `SettingsScreen.kt` → `SettingsSpokeScreen.kt`. Same
   package, so zero import changes; nine baseline IDs regenerate. The one named
   defect with a complete, cheap, no-trade-off fix.
5. **Take the two extractions, each its own PR, with a refreshed baseline and
   `:app:detekt` in the verification list**: `nav/AppRoot.kt` out of
   `MainActivity.kt` (~360 of 573 lines; §8.4 passes with room — `AppRoot` 0
   parameters, `resetTo` 1, `TripDetailEntry` 2, `RouteEditorEntry` 3) and the
   eight-file `entry/` tier. Neither is free: both invalidate baseline entries,
   and PR #224 records what unrefreshed drift costs — `:app:detekt` red on
   `main`, taking #221 and #222 down with it.
6. **Write the placement rule; move nothing.** The nine shared files by name,
   the `entry/` membership test, the "pure vs shareable" distinction from
   finding 7, and an explicit "no `features/` tier, and here is why" citing the
   co-change numbers. Consider asserting it in a Konsist test rather than a
   directory tree — `app/src/test/` already runs in CI, so the rule becomes
   enforceable on new code without touching 59 files. That is what #184 asks
   for: "Not a folder tree, at least not first."
7. **Fix the guidelines and the corpus's errors** — `boundaries.md:79` (46→48),
   `:172` (846→622) and the rest of stale §8.8, `state-holders.md:72-73`,
   `ui/MapScreenState.kt:25`, `conventions.md:20` ("no detekt in this build"),
   `detekt.yml:43-45`'s non-existent file-length rule. Add the §B method note;
   record on #184 that its blocker has landed.
8. **Do not build `features/`**, for the reasons in finding 5 plus, after
   finding 1, the absence of any safe intermediate state. Revisit only if a
   Gradle module extraction is actually intended — and note the only split the
   dependency graph permits leaves `ui/` flat in a module by itself.
9. **Do the state-holder work, narrowly.** The only proposal aimed at a defect
   the guidelines name, with infrastructure in place
   (`shared/.../presentation/`: 25 files, 1,958 lines, 20 test files; leaves
   already take holders — `SpeedHud(state: SpeedHudState)`, `ui/MapHud.kt:121`).
   Cheapest slice: pass the shipped `GoTarget` plus two new holders, 48 → ~19,
   refreshing the baseline every commit. Also price the keyed slot — `sheet:
   @Composable (HomeBottomCard) -> Unit` invoked with the animating card, which
   the `AnimatedContent` at `MapBottom.kt:147-158` requires — which reaches ~4
   parameters with no new types at ~90 lines added to an already-oversized
   `MapScreen.kt`. The corpus prices only one of the two. Defer the
   behaviour-changing session objects and settle `state.md` §13 first.
10. **`lib/` last or never**, and **renderer: one extraction, later** — pull the
    twelve-row `init` ordering (`ui/MapLibreMap.kt:133-283`) into an explicit
    named list a reviewer can diff against the proposal's own table.

---

## What remains undetermined

- **Whether `InvalidPackageDeclaration` actually fires here.** Two primary
  sources say it is on by default and nothing here turns it off, but the Gradle
  module cache is empty and I could not execute `:app:detekt`. *Settled by:* the
  one-command test in item 1. Highest open question in the review.
- **Whether the flat layout costs anything at all.** No tracker complaint, no
  defect attributable to it; the closest artifact is the `SectionLabel` shadow,
  which nobody filed, and one lens found ~102 of 143 merged PRs have zero
  reviews and three inline comments repo-wide. *Settled by:* two agents, one
  scripted task ("find where the speed HUD is assembled"), current tree versus a
  `components/`-only tree, time to first correct file. Cheap, and the only direct
  evidence obtainable. Run it before spending a week on directories.
- **Whether `:shared:detekt` is a one-line change.** `build.gradle.kts:16-18`
  says detekt 1.23 "needs its KMP source sets pointed at explicitly, which is
  its own change". Not attempted.
- **The real commit count and calendar cost.** One lens re-derived proposal C's
  stage table at 51-59 commits and 8-12 working days against its implied 9 rows,
  with the unbudgeted skill-citation sweep the largest missing item. Unverified
  by me, and it predates finding 1, which merges many small commits into a few
  large ones. *Settled by:* doing one package end to end and counting.
- **The full skill blast radius.** Three scripts hard-code `ui/` paths and one is
  a CI gate; one lens counted ~90 (skill, path) citation pairs across eight
  skills, four already dangling, plus a silent skill-*triggering* failure
  (`detour-compose-state-hazards`'s frontmatter scopes itself to `…/ui/`). I
  verified the CI-wired one and two others, not all eight. *Settled by:* running
  every `scripts/check-preconditions.sh` before and after a simulated move and
  diffing — cheap, and it should gate any packaging commit.

**Provenance.** Findings 4, 6, 10 and parts of 5, 7 and 9 began as subagent
reports; every number was re-run by me. Finding 1 was arbitrated between two
conflicting subagents against two primary sources plus four repo-side checks of
my own. Finding 2 was reproduced end to end in a throwaway clone. Findings 3, 8,
the renderer class sizes and the three retractions are mine.
