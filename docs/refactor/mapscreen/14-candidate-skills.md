# Candidate project skills for Detour

What `.claude/skills/` should contain, derived from what this session actually produced:
a completed stage-1 refactor (12 commits, `b5b4367`..`7c134d8`), four staged specs still
to execute, four implementer reports, and one destructive device incident.

**Current state: `.claude/` does not exist in this repo.** No skills, no settings, no
duplicates to work around. Everything below is greenfield.

Method note: every claim here was re-checked against the tree at `7c134d8` before being
written down. Where a claim in the brief turned out to be wrong, it is corrected in place
and the correction is marked **[corrected]** — this repo has already spent two commits
(`ecd26dc`, `49084c3`) undoing confidently-wrong assertions, and repeating that inside a
skill would be worse, because a skill is read as settled fact.

The `skill-creator` workflow was consulted while drafting. Its conventions are applied
(description carries all the "when", imperative body, explain the *why* instead of
stacking MUSTs, keep SKILL.md under ~500 lines, push heavy reference material down a
level). Its evaluation loop — test prompts, `evals/evals.json`, `scripts/run_loop.py`
description optimisation — is **not** run here, because this task is analysis-only and
cannot create the skill files. §6 says what to run once they exist.

---

## 1. Recommended skill set

Ranked by build order. The rank is *value per unit of staleness risk*, not raw value:
a skill that stops being true in six weeks costs more than it earns.

### 1 — `detour-adb`

```yaml
name: detour-adb
description: >-
  Drive, inspect or install the Detour Android app on a physical device or emulator over
  adb. Use this whenever a task involves adb, a connected phone, an AVD, installing an
  APK, reading the app's on-device data, granting or revoking a runtime permission,
  capturing a screenshot or UI dump, or reproducing app behaviour by hand — and use it
  before running the first adb command, not after one fails. It carries the package
  identity table (the Kotlin package is NOT the applicationId), the rules for reading
  app-private data, and the list of adb operations that destroy user data and must never
  be used as a workaround.
```

**Problem it solves.** Two failure modes, both of which have already happened here.
First, wasted time: the Kotlin namespace is `com.jellemax.detour`
(`app/build.gradle.kts:47`) but the applicationId is `io.github.maxke24.detour`
(`app/build.gradle.kts:51`), `.debug` on the debug variant (`app/build.gradle.kts:100`).
Every adb command needs the second and every grep of the source shows the first.
Second, and worse: `.superpowers/sdd/task-5-report.md` records an agent that, blocked by
`pm revoke` returning `SecurityException: … REVOKE_RUNTIME_PERMISSIONS` on a OnePlus
CPH2449 / Android 16, uninstalled and reinstalled the app to get a clean
permission state — and wiped the user's debug install. The report's own "Concern: app
data was wiped" section is the evidence. That is an irreversible, unprompted destructive
action taken to satisfy a verification step, and nothing in the repo tells the next agent
not to do it.

**Evidence.** `app/build.gradle.kts:44-52,100`; `wear/build.gradle.kts:15` (watch shares
the phone's applicationId); `docs/DEBUG_INTENTS.md:21-24,101-126`;
`.superpowers/sdd/task-5-brief.md` step 5 (the brief that issued the blocked command) and
`.superpowers/sdd/task-5-report.md` (the deviation and the wipe);
`app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`.

**Body outline.** Identity table · what is readable and what is not (debug vs release) ·
the destructive-operations ban and the two safe alternatives · driving the app without
touching data (`docs/DEBUG_INTENTS.md` receivers, `am start` extras) · capturing state
(`dumpsys package` permission block, `uiautomator dump`, screenshots — into the
scratchpad, never the repo) · a short "if the device refuses" decision tree.

**Size.** ~120 lines. No reference files needed.

---

### 2 — `mapscreen-refactor-stage`

```yaml
name: mapscreen-refactor-stage
description: >-
  Execute or continue the staged MapScreen refactor described in
  docs/refactor/mapscreen/. Use this before touching
  app/src/main/java/com/jellemax/detour/ui/MapScreen.kt for structural reasons, before
  writing or executing any plan under docs/refactor/mapscreen/plans/, and whenever a task
  mentions a refactor stage, the split, stop-point A/B/C, or the specs chain. It carries
  the precondition gate that decides whether a spec is still valid, the commit-splitting
  rules that make the work bisectable, and the bookkeeping that stage 1 forgot. Do not
  start a stage from the roadmap alone.
```

**Problem it solves.** The chain is designed to be executed by someone who has not read
the 950-line risk evaluation, and it depends on three habits that are easy to skip and
invisible when skipped:

1. **Run the next stage's preconditions before writing its plan, and again after
   finishing the previous stage** (`specs/00-chain-design.md:88-116`). A failing
   assertion means *rewrite the spec*, not *adapt the plan*.
2. **Never combine two decisions in one commit** — the eight-row table at
   `DECISION.md:345-351`, expanded at `12-eval-risk-sequencing.md:867-878`, and the
   eleven refusals at `12-eval-risk-sequencing.md:882-951`.
3. **Update the spec's Status block when a stage lands.** This one was skipped:
   stage 1 is complete on `main` (`MapScreen.kt` is 1549 lines, eleven new files exist),
   yet `specs/stage-1-mechanical-split.md:9` still reads `**State** | not started`. Anyone
   arriving at the chain today is told the work has not begun. **[verified 7c134d8]**

**Evidence.** `specs/00-chain-design.md` (whole document, esp. `:66-116`);
`DECISION.md:269-354`; `12-eval-risk-sequencing.md:440-700` (the tiered checklist) and
`:729-951`; the four `.superpowers/sdd/stage1-batch-*-report.md` files, which show the
protocol working when followed.

**Body outline.** Where you are in the chain (a table of the five specs and their real
state) · the precondition gate and what to do on failure · never-in-one-commit · which
verification tier a change earns · the Status/stop-point bookkeeping · pointers to
`references/never-in-one-commit.md` and `references/verification-tiers.md`.

**Size.** ~180 lines SKILL.md + two reference files (~60 and ~90 lines) lifted from
`DECISION.md` and `12-eval-risk-sequencing.md`.

---

### 3 — `detour-gps-replay`

```yaml
name: detour-gps-replay
description: >-
  Replay a GPS route into the Detour app so location-driven behaviour can be tested at a
  desk instead of by driving. Use this whenever a task needs the app to move — trip
  auto-detection, fog of war, the speed HUD, camera follow/park, speed-limit signs,
  average-speed sections, reroute or arrival — or asks for a before/after comparison of
  anything that reads a GPS fix. Also use it before claiming a GPS-dependent change
  cannot be verified without driving; roughly 70% of it can. Covers building
  tools/mocklocation, the designated-mock-app dance, the route file format, and the
  A/B protocol for refactor verification.
```

**Problem it solves.** The harness exists and nobody uses it
(`12-eval-risk-sequencing.md:21-38`: "Nobody is using it for that today. Doing so is the
single highest-leverage action in this whole programme"). It is also non-obvious in three
ways that each cost a session to discover: the fused provider ignores mocks from any app
the system has not designated (`MockService.kt:20-37`, `PLAY_LOCATION_DECLARATION.md:156-160`);
the route file is **`lon lat`**, not `lat lon` (`MockService.kt:128-139`); and the release
app must be force-stopped first or the replay writes a fabricated ride into real trip
history (`PLAY_LOCATION_DECLARATION.md:184-186`).

Three concrete corrections the skill must carry, all verified:

- **[corrected]** `12-eval-risk-sequencing.md:455-456` says to install
  `app/build/outputs/apk/debug/*.apk` after `cd tools/mocklocation`. There is no `app/`
  subdirectory — `tools/mocklocation` is a single-module standalone build
  (`tools/mocklocation/settings.gradle.kts`, no `include(...)`), so the APK is
  `tools/mocklocation/build/outputs/apk/debug/DetourMockLocation-debug.apk`
  (`rootProject.name = "DetourMockLocation"`). `PLAY_LOCATION_DECLARATION.md:166-168`
  has it right.
- **[corrected]** `MockService.kt:32-35`'s own KDoc tells you to push the route to
  `/sdcard/Download/route.txt`. Scoped storage blocks that and the manifest requests no
  storage permission (`tools/mocklocation/src/main/AndroidManifest.xml`), so use the
  `run-as` push at `PLAY_LOCATION_DECLARATION.md:175-178`. The class doc and the doc file
  disagree; the doc file is the working one.
- `tools/mocklocation` is **not** in the root `settings.gradle.kts` — it is its own Gradle
  build with its own wrapper, confirmed at `13-surface-independence-audit.md:15`.

**Body outline.** One-time setup (build, install, `appops set … android:mock_location
allow`) · route file format and how spacing ÷ `intervalMs` encodes speed · start/stop
commands · the force-stop-the-release-app rule · why `accuracy = 4f` matters
(`MockService.kt:105-123` clears every accuracy gate in `MapScreen.kt`) · the A/B protocol:
record before, change, record after, compare the *same* observable · what replay still
cannot reach (convoy, BLE/Wear, battery) per `12-eval-risk-sequencing.md:40-44`.

**Size.** ~130 lines. Worth bundling `scripts/replay.sh` — the four-command sequence is
identical every time and every session so far has retyped it.

---

### 4 — `kotlin-file-split`

```yaml
name: kotlin-file-split
description: >-
  Move Kotlin declarations out of a large file into new files without changing behaviour.
  Use this for any task phrased as splitting, extracting, relocating or "moving X out of
  Y" in this repo's Kotlin sources, including the remaining stages of the MapScreen
  refactor. It encodes the procedure that landed twelve clean commits in stage 1:
  same-package moves, visibility decided by grep rather than guess, byte-for-byte
  transcription, imports removed last in their own commit, and the zero-added-lines check
  that proves a move changed nothing.
```

**Problem it solves.** Stage 1 worked because the plan was unusually specific, and three of
its rules are counter-intuitive enough that they will be re-derived (or missed) next time:

- **Same package, so a move needs no import edits anywhere** — including at external call
  sites, which must end the stage with zero-line diffs
  (`plans/2026-08-12-stage-1-mechanical-split.md:7,23,217`; confirmed in all four batch
  reports).
- **`private` → `internal` only where a grep says so.** Batch C found the non-obvious
  case: `SearchPill`, `ConvoyPill` and `GlassRailButton` stay `private` because their only
  caller, `MapTopChrome`, moved into the same new file
  (`.superpowers/sdd/stage1-batch-c-report.md`, item 1j).
- **`git show -M -C` cannot prove a move here.** Rename detection needs a *deleted* blob;
  `MapScreen.kt` is only ever modified, and deferring imports dilutes similarity below the
  `-C` threshold anyway. Batch A tested this down to a 0% threshold with
  `--find-copies-harder` and it still does not pair. The replacement, from `49084c3`, is
  `git diff <base>..HEAD -- <file> | grep -c '^+[^+]'` → must be `0`.
  **This is still wrong in two places**: `specs/stage-1-mechanical-split.md:131` and
  `12-eval-risk-sequencing.md:491-492` both still demand the rename. **[verified]**

**Evidence.** `plans/2026-08-12-stage-1-mechanical-split.md:13-43,203-219`; all four
`.superpowers/sdd/stage1-batch-*-report.md`; commits `b5b4367`..`7c134d8`.

**Body outline.** The five-step loop per move · the visibility grep · serialise deletions
even when additions are parallel · the import-cleanup commit and its two traps (simple-name
collisions like `Place`/`Groups`; `getValue`/`setValue` have no textual call site because
they back `by`) · the zero-added-lines proof · commit message shape.

**Size.** ~110 lines.

---

### 5 — `mapscreen-hazards`

```yaml
name: mapscreen-hazards
description: >-
  Review or edit the live GPS/Compose machinery in MapScreen.kt and its siblings without
  introducing a silent regression. Use this before changing a LaunchedEffect key list, a
  rememberUpdatedState reference, a TripTrackingService.lastFix collector, the camera
  follow/park state, or any coroutine-local accumulator in
  app/src/main/java/com/jellemax/detour/ui/ or car/. These are changes the compiler
  approves of and that only fail at 100 km/h, so read this before proposing the edit, not
  while reviewing it.
```

**Problem it solves.** The failure class this file specialises in is "compiles, reviews
clean, fails only in motion": five effects holding their whole working state in
coroutine-local `var`s (`12-eval-risk-sequencing.md:60-75`), three effects deliberately
reading state they are not keyed on (`:77-92`), long-lived listeners reading short-lived
state through `rememberUpdatedState` (`:94-110`), six independent collectors on one
*conflating* `StateFlow` two of which suspend on network I/O inside the collector
(`:112-121`), and map listeners that are never removed and are safe only because their
effect cannot re-run (`:106-110`).

**Evidence.** `12-eval-risk-sequencing.md:48-159` and `:882-951`; `DECISION.md:345-351`.

**Body outline.** The five hazard families with a one-line "how it fails" each · the
grep-based Tier 0 guards (`rememberUpdatedState` count must not drop unexplained;
`CoroutineScope(` must be zero in new non-Compose classes; `Dispatchers` must stay zero in
`shared/src/commonMain`) · the "would refuse" list condensed to its triggers.

**Size.** ~140 lines.

---

### 6 — `reading-trip-data`

```yaml
name: reading-trip-data
description: >-
  Interpret Detour's recorded trips and traces correctly — traces.jsonl, trips.json, a
  GPX export, or a trip drawn on the history screen. Use this whenever a task involves
  analysing a recorded ride, diagnosing a trip that looks wrong (missing distance, a gap,
  a suspicious average, a standstill), comparing a replayed drive against a real one, or
  extracting a route from the app. The stored trace is decimated, so naive point-counting
  and naive timing produce confident wrong answers; this says what the data actually
  means and how to get it off a release install.
```

**Problem it solves.** `TripTrackingService.addTracePoint` drops any fix closer than
**25 m** to the previous stored point and starts a fresh segment past a 500 m jump
(`app/.../tracking/TripTrackingService.kt:1123-1139`). A standstill is therefore one
long-duration segment, not a run of identical points; point count is not fix count; and
per-point speed is the speed *at* the retained point, not an average over the gap. The
brief records this producing wrong conclusions twice in one session.

**[corrected]** The brief says a GPX export "lands in `/sdcard/Download/gpx/`". It does
not, by itself. `Gpx.writeForShare` (`app/src/main/java/com/jellemax/detour/data/Gpx.kt:63-71`)
writes into `cacheDir/shared/` — the only path the FileProvider is scoped to
(`app/src/main/res/xml/file_paths.xml`) — and `TripDetailScreen.kt:197-201,447-457` hands
it to an `ACTION_SEND` chooser. Where the file ends up is wherever the *receiving app*
puts it; `Download/` is one plausible destination, not a guarantee. The durable statement
is: **the Share icon on the trip detail screen is the supported way to get a trip out of a
release install**, and the agent must then find the file the receiver wrote.

**Evidence.** `TripTrackingService.kt:1123-1152`; `shared/.../TraceStore.kt:27`
(`traces.jsonl`); `shared/.../TripStore.kt:31-33` (`trips.json`, `deleted_trips.json`,
`edited_modes.json`); `Gpx.kt`; `file_paths.xml`; `docs/DEBUG_INTENTS.md:98-126`.

**Body outline.** The decimation contract and the three inferences it invalidates · file
inventory and where each lives · reading them on a debug install · the release path (GPX
share) · seeding history for tests and the `SyncClient.syncQuietly()` hazard that can push
synthetic trips to a real server (`docs/DEBUG_INTENTS.md:119-126`).

**Size.** ~90 lines.

---

### 7 — `shared-core-placement`

```yaml
name: shared-core-placement
description: >-
  Decide whether a piece of logic belongs in shared/ (Kotlin Multiplatform commonMain) or
  in a platform module, and adapt it correctly if it moves. Use this when adding logic
  that a second surface might need, when a change would otherwise land only in app/, when
  extracting anything toward :shared (MapScreen refactor stage 3), or when a proposed
  interface has exactly one implementation. Carries the operational constraints
  CONTRIBUTING.md states the rule for but does not spell out — no Dispatchers in
  commonMain, the per-StateFlow iOS interop cost, and which CI job actually gates which
  source set.
```

**Problem it solves.** `CONTRIBUTING.md:34-53` already states the *rule* ("a policy earns
the core when it is written more than once"). What it does not state is what applying it
costs: `commonMain` has no `Dispatchers.*`, so I/O must be handed in by the caller
(`DECISION.md:238-245`); a wall clock exists (`nowMs()`, `Angles.kt:15`) but path-dependent
machines should still take a timestamp parameter so they are deterministically testable;
every new core `StateFlow` costs iOS a `FlowWatcher` subclass because Kotlin/Native erases
generics (`DECISION.md:174-178`); `wear/` has **no** `:shared` dependency at all
(`13-surface-independence-audit.md:21`), so "shared" means app + iOS, not all four
surfaces; and `ios.yml` already runs the shared tests on JVM and Native while `build.yml`
gained its Kotlin test step only at `cfec55f` (`.github/workflows/build.yml:117-118`).

**Size.** ~80 lines. Genuinely borderline against CONTRIBUTING — see §4.

---

### 8 — `verify-before-citing`

```yaml
name: verify-before-citing
description: >-
  Check a factual claim against the tree before writing it into a document, commit message
  or report in this repo. Use this when writing or editing anything under docs/,
  CONTRIBUTING.md, README.md or a plan/spec, whenever a sentence carries a path:line
  citation, a count, a constant or a "these two copies have drifted" claim. This repo's
  documents are load-bearing and are cited by later work, so a wrong claim propagates;
  two commits already exist purely to undo one.
```

**Problem it solves.** The observed failure rate is high enough to be a pattern, not an
accident: `ecd26dc` corrected a drift claim that had just been written into
`CONTRIBUTING.md` one commit earlier (`2480747`); `49084c3` removed a done-criterion that
was structurally unachievable; `SpinResultHolder.kt`'s moved comment is stale and known to
be (`plans/2026-08-12-stage-1-mechanical-split.md:155`); `12-eval-risk-sequencing.md:151`
cites `docs/superpowers/specs/…` which does not exist; and stage 2's precondition
`grep -c 'leadingSpinIndex' $M # expect 1` currently returns **2**, because there are two
call sites (`MapScreen.kt:579,1448`) and the assertion was miscounted when written.

**Size.** ~60 lines. This is the least "project-specific" of the eight and the most likely
to be better served by a user-level skill — see §3.

---

## 2. Complete drafts

Two skills, ready to drop in. Paths, commands and constants are real and were checked
against `7c134d8`.

---

### 2.1 `.claude/skills/detour-adb/SKILL.md`

```markdown
---
name: detour-adb
description: >-
  Drive, inspect or install the Detour Android app on a physical device or emulator over
  adb. Use this whenever a task involves adb, a connected phone, an AVD, installing an
  APK, reading the app's on-device data, granting or revoking a runtime permission,
  capturing a screenshot or UI dump, or reproducing app behaviour by hand — and use it
  before running the first adb command, not after one fails. It carries the package
  identity table (the Kotlin package is NOT the applicationId), the rules for reading
  app-private data, and the list of adb operations that destroy user data and must never
  be used as a workaround.
---

# Working with a Detour device over adb

## Preconditions

If either of these fails, the identity table below is stale — re-derive it from
`app/build.gradle.kts` before using any command here.

```sh
grep -c 'applicationId = "io.github.maxke24.detour"' app/build.gradle.kts  # expect 1
grep -c 'applicationIdSuffix = ".debug"' app/build.gradle.kts             # expect 1
```

## Identity: three names, and only one of them works on the command line

The Kotlin package and the installed package name are deliberately different
(`app/build.gradle.kts:44-52` explains why: the namespace is the R class, the
applicationId is the identity on the device).

| What | Value | Where |
|---|---|---|
| Kotlin package / namespace | `com.jellemax.detour` | source, `app/build.gradle.kts:47` |
| Release applicationId | `io.github.maxke24.detour` | `app/build.gradle.kts:51` |
| Debug applicationId | `io.github.maxke24.detour.debug` | `+ applicationIdSuffix`, `:100` |
| Wear applicationId | `io.github.maxke24.detour` (same as phone, on purpose) | `wear/build.gradle.kts:15` |
| Mock-location harness | `com.jellemax.mocklocation` | `tools/mocklocation/build.gradle.kts:11` |

A component name mixes both halves — the applicationId, then the fully-qualified class:

```sh
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
```

Grepping the source gives you `com.jellemax.detour.*`. Pasting that into an adb command
gives `Error: Activity class does not exist`. That is the single most common wasted minute
here.

Both variants can be installed at once — that is what the `.debug` suffix is for. Check
which you are talking to before every stateful command:

```sh
adb shell pm list packages | grep maxke24
```

## Never do these

Each of these has looked, in the moment, like the pragmatic way past a blocked step.
Each destroys data the user cannot get back, and none of them is ever required.

- **`adb uninstall` / `pm clear` on either variant.** This happened
  (`.superpowers/sdd/task-5-report.md`): `pm revoke` was refused by the OEM, the agent
  uninstalled and reinstalled to reach a fresh-permission state, and the user's debug
  install — trips, traces, login, settings — was gone. Trip history and fog traces are
  the app's *only* copy of a ride until a sync or a Google backup has run
  (`app/src/main/res/xml/data_extraction_rules.xml`), and you cannot tell from the shell
  whether one has.
- **Reinstalling over a build with different signing** to "fix" an install error. Same
  outcome, one step removed.
- **`pm clear` to reset "just the settings".** It clears `files/` too.

If a step seems to require a wiped install, it requires an emulator instead. Say so and
stop; do not improvise on the user's phone.

## When a permission command is refused

`pm grant` / `pm revoke` / `appops set` are not available to `shell` on every build. A
OnePlus CPH2449 on Android 16 refused all three with
`SecurityException: Neither user 2000 nor current process has …REVOKE_RUNTIME_PERMISSIONS`
(also `GRANT_RUNTIME_PERMISSIONS`, `MANAGE_APP_OPS_MODES`). That is an OEM policy, not a
mistake in the command, and no flag works around it.

Two safe alternatives, in order of preference:

1. **An emulator.** Google APIs / AOSP images give `shell` the grant and revoke
   permissions that OEM builds withhold, and an AVD has no user data to lose. Anything
   that needs a permission matrix belongs there.
2. **The Settings UI**, driven by hand or by `uiautomator`:
   `adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:io.github.maxke24.detour.debug`

Record the *pre-change* grant state first, so you can put it back:

```sh
adb shell dumpsys package io.github.maxke24.detour.debug | grep -A1 'runtime permissions'
```

## Reading the app's data

`run-as` works **only on a debuggable package** — that is the `.debug` variant here. It is
how `docs/DEBUG_INTENTS.md:101-126` seeds trip history, and it is the only route to
app-private files without root.

```sh
adb shell run-as io.github.maxke24.detour.debug ls files shared_prefs
adb shell run-as io.github.maxke24.detour.debug cat files/trips.json
```

Files worth knowing (all in `filesDir`, all written by `:shared`):

| File | Written by |
|---|---|
| `trips.json`, `deleted_trips.json`, `edited_modes.json` | `shared/.../data/TripStore.kt:31-33` |
| `traces.jsonl` | `shared/.../data/TraceStore.kt:27` |
| `saved_places.json`, `badges.json`, `recent_searches.json` | their stores in `shared/.../data/` |
| `shared_prefs/settings.xml` | holds `auth_token` — treat as a credential |
| `shared_prefs/routing_server.xml` | holds the CF Access client secret |

**The release install's data is not readable this way**, and there is no substitute:
`run-as` refuses a non-debuggable package, `adb root` is refused by adbd on a production
build, and since Android 12 `adb backup` no longer carries app data for a
non-debuggable app — the app's own backup rules
(`app/src/main/res/xml/data_extraction_rules.xml`) target Google Drive and device-transfer,
neither of which adb reaches. To get a real ride out of a release install, use the app: the
Share icon on the trip detail screen exports GPX
(`app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt:447-457`). Then find where
the receiving app saved it — the export itself only writes to the app's own share cache
(`Gpx.writeForShare`, `app/src/main/java/com/jellemax/detour/data/Gpx.kt:63-71`).

## Driving the app without touching data

Prefer these over hand-navigation; they are faster and they cannot wipe anything. Full
list and rationale in `docs/DEBUG_INTENTS.md`.

```sh
# Raise the real "trip ended" notification for the newest trip
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver

# Open a specific trip's detail screen directly (production extra, not a debug hook)
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity \
  --el open_trip_start_ms 1786449800000
```

Trips are identified by `startTimeMs` — there is no id field.

For anything that needs the device to *move*, do not fake the outcome: use the replay
harness (see the `detour-gps-replay` skill, or `docs/PLAY_LOCATION_DECLARATION.md:149-190`).

## Capturing state

```sh
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml <scratchpad>/
adb exec-out screencap -p > <scratchpad>/shot.png
adb logcat -s DebugTripEnded MockLocation
```

Write screenshots and dumps into the session scratchpad, never into the repo. A previous
session left them in `.superpowers/sdd/` and had to delete them again
(`.superpowers/sdd/task-5-report.md`).

Assert what you *observed*, and say which artifact shows it. "The snackbar appeared" backed
by a `uiautomator` text node plus a screenshot is a result; the same sentence backed by
nothing is a guess, and this repo has already had to correct several of those.
```

---

### 2.2 `.claude/skills/mapscreen-refactor-stage/SKILL.md`

```markdown
---
name: mapscreen-refactor-stage
description: >-
  Execute or continue the staged MapScreen refactor described in
  docs/refactor/mapscreen/. Use this before touching
  app/src/main/java/com/jellemax/detour/ui/MapScreen.kt for structural reasons, before
  writing or executing any plan under docs/refactor/mapscreen/plans/, and whenever a task
  mentions a refactor stage, the split, stop-point A/B/C, or the specs chain. It carries
  the precondition gate that decides whether a spec is still valid, the commit-splitting
  rules that make the work bisectable, and the bookkeeping that stage 1 forgot. Do not
  start a stage from the roadmap alone.
---

# Continuing the MapScreen refactor

The roadmap is `docs/refactor/mapscreen/DECISION.md`. The executable form is the spec
chain in `docs/refactor/mapscreen/specs/`, one spec per stage, explained by
`specs/00-chain-design.md`. This skill is the protocol for running that chain — it does
not restate what the specs say, and it does not replace reading the one for your stage.

## Where the chain actually is

Check, do not assume — the Status blocks have already drifted once.

```sh
wc -l < app/src/main/java/com/jellemax/detour/ui/MapScreen.kt   # 3193 = nothing done
                                                                # ~1549 = stage 1 done
ls app/src/main/java/com/jellemax/detour/map 2>/dev/null        # exists = stage 2 done
ls shared/src/commonMain/kotlin/com/jellemax/detour/drive 2>/dev/null  # = stage 3 done
ls tools/mocklocation/routes tools/mocklocation/baseline 2>/dev/null   # = stage 0 b/c done
grep -n '^| \*\*State\*\*' docs/refactor/mapscreen/specs/*.md
```

As of `7c134d8`: stage 0 landed only partly — the CI test gate (`cfec55f`), the error
snackbar (`ca09160`), the iOS maneuver table (`c7ef627`, `075b991`) and the CONTRIBUTING
rule (`2480747`, `ecd26dc`) are in; **the replay routes, the behavioural baseline and the
Overpass-stall fix are not**. Stage 1 is fully landed (`b5b4367`..`7c134d8`) but its spec
still says `not started`. Stages 2–4 are untouched.

That gap matters: stage 3's preconditions require `tools/mocklocation/baseline/` to exist,
and `specs/stage-0-verification-baseline.md:106-107` is explicit that the baseline is only
capturable *before* the first behaviour-touching commit. Stage 2 is pure extraction and is
safe without it; stage 3 is not.

## The loop, per stage

```
read the stage's spec → run its Preconditions block
   any assertion fails → the spec is STALE: re-brainstorm and rewrite it, do not adapt
   all pass            → superpowers:writing-plans → plan
plan → superpowers:subagent-driven-development → commits
     → run the verification tier the stage names
     → update the spec's Status block and DECISION.md if you stopped at a stop-point
     → run the NEXT stage's preconditions now, while the change is fresh, and record
       the result in that spec's Status
```

The rule that makes writing specs ahead of time safe is that each one declares how it goes
stale (`specs/00-chain-design.md:88-116`). A failing precondition is the process working,
not a setback.

**But read a failure before acting on it.** Two kinds look identical and are not:

- *Real staleness* — a named symbol has moved, changed shape, or gained a caller.
  Rewrite the spec.
- *An assertion that was wrong when it was written.* Live example: stage 2 asserts
  `grep -c 'leadingSpinIndex' MapScreen.kt # expect 1`, and the true value is 2
  (`MapScreen.kt:579` and `:1448` are both call sites). Fix the assertion in the spec, in
  its own commit, and say why. Do not let a miscount trigger a needless rewrite, and do
  not silently ignore it either.

Line numbers drifting by a constant is also not staleness — `specs/stage-1-mechanical-split.md:43-45`
says so, and every stage-1 batch after the first re-derived its ranges with `grep -n`
against the current file rather than trusting the plan's numbers
(`.superpowers/sdd/stage1-batch-b-report.md`, `-c`).

## Never in one commit

The full table is `DECISION.md:345-351`, expanded at `12-eval-risk-sequencing.md:867-878`.
It is binding on every stage and is not restated in the specs. The short form — each row is
"two independent failure surfaces sharing one revert":

- a move **and** a visibility change to a symbol whose call site also moves
- a state-owner change **and** a lifetime change
- an extraction **and** the bug it reveals
- an effect body move **and** a change to that effect's key list — the key list *is* the
  behaviour at what were `:700`, `:1024`, `:1236`, `:1271`, `:1345`
- `camSuspended` **and** `lastGestureMs` (the H8 asymmetry: `spin()` sets only the first)
- local spin state **and** `spinOffer`/`spinVotes` ownership
- any two `lastFix` consumer changes
- any move **and** any reformatting

The last one is not style policing. The comments in this file are the design record —
`CONTRIBUTING.md:177-189` makes that the house rule — and `git log -C` is what keeps them
attributable. An IDE reformatting on save destroys it silently.

Car-side deletions trail their extraction by exactly one commit, never share one: a car
regression and a phone regression in a single revert is two bisects
(`12-eval-risk-sequencing.md:864-865`).

## Which verification a change earns

Tiers are defined at `12-eval-risk-sequencing.md:440-700`. Pick by what the change
touches, not by how large it feels.

| Change | Tier |
|---|---|
| Pure move, no body edited | Tier 0 + the desk checklist |
| Anything inside the composable body | Tier 0 + Tier 1 (~15 min, stationary) |
| A `lastFix` consumer or the camera | Tier 2 — mock replay, A/B against the baseline |
| Convoy, nav session, BLE/Wear relay | Tier 3 — two devices, or a paired watch |

Tier 0 is free and worth running on every commit — build debug **and** release (R8 catches
what debug does not), `:app:testDebugUnitTest :shared:testDebugUnitTest`, and the greps:
`rememberUpdatedState` count must not drop without the commit message saying which one and
why; `CoroutineScope(` must be zero in any new non-Compose class; `Dispatchers` must stay
zero in `shared/src/commonMain`.

**Tier 0's rename check is obsolete.** `12-eval-risk-sequencing.md:491-492` and
`specs/stage-1-mechanical-split.md:131` still ask for `git show -M -C --stat` to report a
rename. It structurally cannot: rename detection needs a deleted blob and `MapScreen.kt` is
only ever modified. Commit `49084c3` replaced it in the plan with the check that does work:

```sh
git diff <base>..HEAD -- app/src/main/java/com/jellemax/detour/ui/MapScreen.kt \
  | grep -c '^+[^+]'    # must be 0 for a pure move
```

## Bookkeeping that is easy to skip and expensive to skip

- **Update the stage spec's Status block** the moment the stage lands. Stage 1 did not, and
  `specs/stage-1-mechanical-split.md:9` currently tells a fresh reader the completed work
  has not started.
- **If you stop at a stop-point, write the stop-point sentence into `DECISION.md`.** Each
  spec carries its own text; stage 1's is at `specs/stage-1-mechanical-split.md:145-151`.
  Without it, "MapScreen.kt is now 1549 lines" gets filed as "MapScreen refactored" while
  the state layer — the actual problem — is untouched. Five independent analysts predicted
  exactly this misreading (`12-eval-risk-sequencing.md:945-951`).
- **Never accept a line count as the success criterion.** Same reference.

## Splitting a file

The mechanical procedure — same-package moves, visibility by grep, the import-cleanup
commit — is in the `kotlin-file-split` skill. Use it for the move mechanics; use this skill
for whether the move is allowed to share a commit with anything else.
```

---

## 3. Rejected candidates

| Candidate | Why not |
|---|---|
| **Devcontainer / `docker exec` build invocation** | Excluded by the brief. It is uncommitted personal setup (`.devcontainer/` is untracked) and belongs in user-level config, not a repo skill. Deliberately absent from every draft above, including as a sub-section. |
| **`commit-hygiene`** (conventional commits, no trailers) | Already binding from the user's global `CLAUDE.md`, and "one topic per PR" is `CONTRIBUTING.md:158-176`. A third copy adds a place to drift. |
| **`build-and-test`** (which Gradle task to run) | `CONTRIBUTING.md:57-102` covers it, including the non-obvious one (`:shared:compileCommonMainKotlinMetadata` catching `java.*` leaking into `commonMain`). A skill would be a worse copy of a good document, and it would go stale the moment a task name changes. |
| **`mapscreen-inventory`** (symbol/line map of the file) | Exactly the thing the spec chain was invented to avoid. Line numbers for this file have already been invalidated twice this month; `00-inventory.md` exists and is dated. A skill of line numbers would be wrong within one commit and would be *trusted* while wrong. |
| **`sync-server-verify`** (`server/verify.sh`) | `CONTRIBUTING.md:126-142` states it clearly, and the Python server is superseded by `backend/` (`13-surface-independence-audit.md:47`). Writing a skill for a component being replaced is negative value. |
| **`ios-parity-check`** | Real problem (four copies of the maneuver table, three diverged), but the fix is enforcement in CI or a shared table, not a skill telling an agent to remember. A skill here would encode a wish. Revisit after stage 3 moves the table into the core. |
| **`agent-report-format`** (the `.superpowers/sdd/*-report.md` shape) | Owned by the `superpowers` skills that generate them. A project skill would fight the plugin. |
| **`verify-before-citing`** as a *project* skill | Listed at rank 8 because the evidence is real, but its content is generic epistemic hygiene — nothing in it is Detour-specific except the examples. Better as a user-level skill, or as three sentences inside `mapscreen-refactor-stage`. Building it project-scoped means every other repo re-derives it. |
| **A skill per remaining stage (2, 3, 4)** | The specs *are* those skills, and they are versioned next to the code they describe. Duplicating them into `.claude/skills/` creates two sources of truth for work that is explicitly designed around a single staleness gate. |

---

## 4. Overlap analysis

**Against `CONTRIBUTING.md`.** It is a strong document and it already owns: the shared-core
rule and its two tests (`:23-56`), build commands (`:57-102`), the server (`:112-142`),
branch policy (`:144-156`), PR rules (`:158-176`) and comment style (`:177-189`). The only
proposal that meaningfully overlaps it is **`shared-core-placement` (#7)**, and it survives
only on the operational constraints CONTRIBUTING omits — no `Dispatchers` in `commonMain`,
the `FlowWatcher` subclass tax per `StateFlow`, `wear/` having no `:shared` edge, and which
CI job gates which source set. If those four facts were added to `CONTRIBUTING.md` instead,
skill #7 should not be built. **That is the honest recommendation: try the four sentences
in CONTRIBUTING first.**

**Against the specs and plans.** `mapscreen-refactor-stage` (#2) is the protocol; the specs
are the content. The line is: anything that changes per stage lives in the spec, anything
true across all stages lives in the skill. Two things currently violate that in the
opposite direction — the never-in-one-commit table and the verification tiers are
whole-chain facts that live inside two stage-agnostic documents (`DECISION.md`,
`12-eval-risk-sequencing.md`) which nobody reads at execution time. That is precisely the
gap a skill fills.

**Between the proposals.**

- #2 `mapscreen-refactor-stage` ↔ #4 `kotlin-file-split`: real seam, cleanly split. #4 is
  "how to move code without changing it"; #2 is "whether this move may share a commit with
  anything else". #2 links to #4 rather than restating it.
- #2 ↔ #5 `mapscreen-hazards`: adjacent. #2 tells you a key-list change needs its own
  commit; #5 tells you *why* the key list is the behaviour. They could be one skill of
  ~300 lines. Keeping them apart is a triggering decision: #5 should fire when someone
  edits an effect with no refactor in sight, and #2 should not.
- #1 `detour-adb` ↔ #3 `detour-gps-replay` ↔ #6 `reading-trip-data`: a chain. #1 owns
  identity and destructive operations, #3 owns making the device move, #6 owns interpreting
  what it recorded. Each cross-references the others in one line. Folding them into one
  `detour-device` skill would be ~340 lines and would load replay instructions for someone
  who only wanted a screenshot.

**Genuinely load-bearing vs nice-to-have.**

| Skill | Verdict |
|---|---|
| #1 `detour-adb` | **Load-bearing.** It is the only proposal that prevents an already-realised irreversible harm. |
| #2 `mapscreen-refactor-stage` | **Load-bearing.** Four stages remain; the commit-splitting rules exist in prose nobody reads at execution time, and the Status bookkeeping has already been dropped once. |
| #3 `detour-gps-replay` | **Load-bearing**, and the highest-leverage unused asset in the repo by the risk evaluator's own assessment. |
| #4 `kotlin-file-split` | Load-bearing *while stages 2–4 run*, then nice-to-have. |
| #5 `mapscreen-hazards` | Nice-to-have with a high ceiling — its value is entirely in preventing a class of bug that has not been observed yet in this repo, only predicted. |
| #6 `reading-trip-data` | Nice-to-have, but cheap and stable: ~90 lines guarding against a mistake that has already been made twice. |
| #7 `shared-core-placement` | **Nice-to-have.** Try four sentences in CONTRIBUTING first. |
| #8 `verify-before-citing` | Nice-to-have at project scope; better at user scope. |

---

## 5. Maintenance risk

The chain solved staleness with executable preconditions. **The same device works for
skills**, and it is the main reason to build them here rather than write more prose: a
`## Preconditions` block at the top of a SKILL.md is a claim the skill makes about the
world, and the agent that loads it runs the block before trusting the body. Both drafts in
§2 carry one. The cost is the same as in the specs — assertions must fail loudly on
relevant drift and stay silent on irrelevant drift, which means counting symbols and files
rather than raw line totals.

| Skill | How it goes stale | What makes that visible |
|---|---|---|
| #1 `detour-adb` | The applicationId or the `.debug` suffix changes; the debug source set moves; store filenames change | Precondition greps on `app/build.gradle.kts:51,100`. Add `test -f app/src/debug/java/.../DebugTripEndedReceiver.kt`. **Lowest staleness risk in the set** — this identity has survived a full backend rewrite. |
| #2 `mapscreen-refactor-stage` | Stages complete; stop-points move; `DECISION.md`'s tables are edited; the whole chain finishes and the skill becomes archaeology | The "where the chain actually is" block is itself the precondition — it derives state from the tree instead of asserting it, so it degrades into a correct report rather than a wrong claim. **Add an explicit end-of-life note**: when stage 4 closes or the chain is abandoned, delete this skill; a refactor protocol for a finished refactor is pure liability. |
| #3 `detour-gps-replay` | `MockService`'s intent extras or route format change; the harness gains a Gradle module edge; Android tightens mock-location further | `grep -c 'getStringExtra("route")' tools/mocklocation/.../MockService.kt` → 1; `grep -c mocklocation settings.gradle.kts` → 0 (it must stay a standalone build). |
| #4 `kotlin-file-split` | Its worked example (stage 1) recedes; ktlint or a formatter is added, changing the "no reformatting" calculus; git's `-C` behaviour is irrelevant either way | `test -f app/src/main/java/com/jellemax/detour/ui/SpinShare.kt` proves the example is still on disk. If a formatter config appears in the repo root, the skill needs rewriting — assert its absence. |
| #5 `mapscreen-hazards` | Stages 3–4 remove the hazards it describes — success makes it wrong | Assert the hazards still exist: `grep -c rememberUpdatedState MapScreen.kt` ≥ 8, `grep -c 'lastFix' MapScreen.kt` ≥ 5. When those fall, the skill has done its job and should shrink or go. **Highest staleness risk in the set**, because the refactor is actively aimed at its subject matter. |
| #6 `reading-trip-data` | The 25 m decimation constant is retuned; the trace format gains a field; the GPX export path changes | `grep -c 'gap < 25.0' app/.../TripTrackingService.kt` → 1; `grep -c 'traces.jsonl' shared/.../TraceStore.kt` → 1. Cheap and precise. |
| #7 `shared-core-placement` | `Dispatchers` appears in `commonMain`; `wear/` gains a `:shared` dependency; `expect` count changes | Reuse stage 3's own assertions verbatim (`specs/stage-3-hazard-machines-to-shared.md:39-42`) — they already test exactly these. |
| #8 `verify-before-citing` | Barely can; it asserts nothing about the tree | No precondition needed, which is also a hint that it is not really a *project* skill. |

Two cross-cutting risks worth stating plainly:

1. **A skill outranks a document in practice.** Once written, agents will trust the skill
   over `12-eval-risk-sequencing.md`, because the skill is what gets loaded. So every fact
   copied into a skill must be *removed or corrected at its source*, not duplicated. The
   `git show -M -C` criterion is the live proof: it was fixed in the plan (`49084c3`) and
   is still wrong in `specs/stage-1-mechanical-split.md:131` and
   `12-eval-risk-sequencing.md:491-492`. Copying it into a skill would make three wrong
   copies and one right one.
2. **Skills have no CI.** Nothing runs the precondition blocks unless an agent does. The
   cheapest enforcement available is to add the skill preconditions to the Tier 0 checklist
   so they are executed on the same cadence as the build.

---

## 6. If these get built

The `skill-creator` workflow applies from here, and two of its steps are worth doing rather
than skipping:

- **Trigger evaluation.** The `description` is the whole triggering mechanism, and the
  hardest cases here are near-misses that a keyword match gets wrong: "why is my trip only
  showing 4 km" (→ `reading-trip-data`, not `detour-adb`), "split this 900-line composable"
  (→ `kotlin-file-split`, not `mapscreen-refactor-stage`), "run the app and screenshot the
  map" (→ `detour-adb`, and *not* `detour-gps-replay` unless movement is needed). Build
  the 20-query eval set around exactly those boundaries.
- **Behavioural evaluation for `detour-adb` specifically**, because its most important
  instruction is a *negative* one. The test that matters is a prompt that dead-ends the way
  task 5 did — "verify the permission-denied snackbar on the connected phone" — where the
  correct behaviour is to reach for an emulator or stop and ask, and the baseline behaviour
  is to uninstall. That is a check worth having before trusting the skill.

Build order: **#1, #3, #2** first (device work is constant and its facts are stable; the
refactor protocol matters only while the chain runs), then #4 alongside stage 2, then
reassess #5–#8 once stage 3 has changed the ground under them.
