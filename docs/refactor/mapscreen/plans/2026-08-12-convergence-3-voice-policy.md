# Convergence 3 — the announcement policy into `shared/`, then the phone's voice: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop writing the turn-announcement policy a second and third time. The ladder
(`800/300/80`), `spokenDistance` and the phase latch move into commonMain once, both existing
surfaces are repointed at it, the two entry-12 sub-bugs on iOS are fixed on their own, and then the
phone gets a voice — the register's decision 1, *full parity*. **Ten commits, four surfaces, one
new shared type, one file move, two behaviour changes and one stop-point in the middle.**

**Shape:** Half refactor, half feature, and the seam between them is the stop-point. Items 1–5
change no behaviour on the car and change iOS only at three exact distances; every one of them is
verifiable from this desk. Items 6–9 add a second audio client to the app's most-used surface and
**cannot** be verified here at all — not the speech, not the ducking, not the focus release. Item
10 is documentation.

**Tech stack per item:** Swift 5.9 / iOS 17.0 deployment target (`iosApp/project.yml:17`) ·
Kotlin Multiplatform commonMain + commonTest (`shared/`) · Kotlin with
`androidx.car.app:app:1.7.0` · Jetpack Compose · Markdown.

**Spec:** [`../specs/convergence-3-voice-policy.md`](../specs/convergence-3-voice-policy.md) — its
Scope, Out of scope, *Why this stage* and its rewritten Work items are all binding. **Its 10
preconditions were re-run against the working tree at `92823ed` and all 10 pass**, including the
first, which was written to fail on purpose and now passes because convergence 1's microphone
permission landed in `1f4514a`:

```
mic key 1 · TextToSpeech files 1 · speak( 6 · VOICE_FAR_M 2 · voiceFarM 2 ·
distance <= VOICE_NOW_M 1 · case ..<Self.voiceNowM 1 · voice.stop() 1 ·
'Turn instructions read aloud' 1 · voiceGuidance in Settings.kt 4
```

**Register:** [`../15-divergence-register.md`](../15-divergence-register.md) — entry 12 (voice:
phone silent, iOS mute doesn't cut), entry 15 (camera warning: chime vs chime+speak+toast), §C
decision 1 (*"full parity — port `NavVoice` to the phone"*), §C.1 item 6, and entry 13 as a
**constraint** rather than an input. This plan cites those entries; it does not restate their
arguments.

**Every line number below was derived with `grep -n` against the tree at `92823ed` on
`refactor/mapscreen-split`**, with a clean working tree (only `.devcontainer/` and a stray
`detour-car-*.gpx` untracked). The spec's own Scope section and the register's entry 12 carry **eight** stale
`car/NavScreen.kt` citations, listed in the spec's *Citations that drifted* — the file grew when
`e6a6bf2` added the off-route indicator. Re-derive before trusting any number here if anything has
landed since.

## Global Constraints

- **Commit messages:** Conventional Commits. **No `Co-Authored-By` trailer. No
  `Claude-Session` trailer. No trailers of any kind.**
- **One work item, one commit.** Ten work items, ten commits. `DECISION.md:394-400` and
  `detour-staged-refactor` §4 are binding. Three rows bite here:
  - *an extraction **and** the bug it reveals* — Task 3 (the extraction) must not carry Task 1's
    boundary fix or Task 2's mute fix, even though the extraction subsumes both;
  - *a move **and** a change* — Task 6 moves `NavVoice` and Task 7 changes it; never together;
  - the **car's repoint trails the extraction by exactly one commit**: Task 3 then Task 4, nothing
    between them.
- **The stop-point is after Task 5.** Record it in the spec's Status. Tasks 6–9 do not start until
  a device session is actually available; a commit that says "verified" without one is the failure
  mode this chain has already had twice (`detour-staged-refactor` §6).
- **Entry 13's constants are not this plan's** (spec D7). `+5` (`MapHud.kt:184`,
  `car/CarMapRenderer.kt:635`, `wear/…/MainActivity.kt:140`), `+3.0` (`MapScreen.kt:870`,
  `car/NavScreen.kt:421`) and the `45.0` wedge (`MapScreen.kt:863`, `car/NavScreen.kt:414`) are
  stage 3's `CameraWarner` or stay in `app/`. **This plan declares none of them and moves none of
  them.** Task 9 carries the greps that prove it.
- **No new `expect`.** `Platform.kt` has four and `CONTRIBUTING.md:23-32` says wanting a fifth
  means the shape is wrong. `NavAnnouncer` takes its inputs as parameters, which is why it is
  callable from `commonTest` with literals and no fake.
- **No clock in the policy** (spec D3). `NavAnnouncer` takes no `nowMs`, because the latch is
  path-dependent over the distance sequence and there is no timestamp in either existing copy.
  Do not add one to satisfy a pattern; the reroute cooldown that *does* need time already lives in
  `NavPolicy` (`app/…/map/NavPolicy.kt:51-57`) and is untouched.
- **No new `iosMain` watcher subclass.** `FlowWatcher.kt` has nine and its doc argues the trade.
  Task 2 reuses `BoolWatcher` via `SettingsFlows.shared.voiceGuidance()` (`FlowWatcher.kt:140`),
  which already exists; `NavAnnouncer` exposes no `StateFlow` at all, so it costs zero.
- **Read `detour-shared-core`** before deciding anything should move rather than be duplicated,
  and **`detour-compose-state-hazards`** before touching any effect in `MapScreen.kt`. Two
  conclusions from each are load-bearing and used below: `app/` ↔ `car/` de-duplication is a plain
  move under `app/` with no `shared/` and no interface; `iosApp/` has no test target at all;
  `MapScreen.kt` holds six independent `lastFix` subscriptions and **no commit may change two of
  them**; and a `DisposableEffect` that acquires must release in `onDispose` in the same commit.
- **Rationale goes next to the code, not only in the message.** `CONTRIBUTING.md:177-189`. Both
  behaviour changes (Tasks 7 and 9) and every silence rule in Task 8 carry their reasoning in a
  comment as well as in the commit.
- **Gradle only ever runs in the devcontainer.** The host JDK is 26 with no Android SDK; the
  container is `recursing_volhard`, workdir `/workspaces/Detour`, user numeric:

  ```sh
  docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew <tasks>
  ```

  Never a bare `./gradlew build`.

### Sequencing

Strictly sequential. Nothing here parallelises usefully, and two of the orderings are load-bearing
rather than stylistic:

1. **Task 1 before Task 5.** The boundary fix is deleted again by the iOS repoint. That is
   deliberate: it makes both surfaces agree *before* Task 3 writes the shared test, so the test is
   characterising agreed behaviour rather than picking a winner, and the entry-12 sub-bug stands on
   its own commit if the extraction is ever abandoned.
2. **Task 3 immediately before Task 4** (`detour-staged-refactor` §4).
3. **Task 6 before Task 7 before Task 8.** A move, then a behaviour change, then a consumer.
4. **Task 8 before Task 9**, and never in the same commit — they touch two different `lastFix`
   consumers (`MapScreen.kt:1054` via `liveFix`, and the raw collector at `:856`).
5. **Task 10 last.** A commit cannot cite its own SHA.

Execution order: **1 → 2 → 3 → 4 → 5 → ⟨stop-point⟩ → 6 → 7 → 8 → 9 → 10.**

## File Structure

Two files created, eight edited, one moved, across ten commits.

| # | File | Change | Compilable here? |
|---|---|---|:-:|
| 1 | `iosApp/Detour/NavScreen.swift` | `..<` → `...` on `:176-178` | **no** |
| 2 | `iosApp/Detour/NavScreen.swift` | `voiceWatch` property, `init`, `deinit` in `NavModel` (`:128-140`) | **no** |
| 3 | `shared/src/commonMain/kotlin/com/jellemax/detour/data/NavAnnouncer.kt` | **new**, ~95 lines | **yes** |
| 3 | `shared/src/commonTest/kotlin/com/jellemax/detour/data/NavAnnouncerTest.kt` | **new**, ~110 lines | **yes** |
| 4 | `app/…/car/NavScreen.kt` | delete `:60-67`, `:126-130`, `:577-583`, `:52`; rewrite `announce()` (`:297-324`); `:261`, `:275-277`; 1 import | **yes** |
| 5 | `iosApp/Detour/NavScreen.swift` | delete `:138-144`, `:203-210`; rewrite `announce` (`:167-195`) and `start` (`:146-151`) | **no** |
| 6 | `app/…/car/NavVoice.kt` → `app/…/audio/NavVoice.kt` | package line + one KDoc paragraph; body byte-identical | **yes** |
| 6 | `app/…/car/NavScreen.kt` | 1 import added | **yes** |
| 7 | `app/…/audio/NavVoice.kt` | `speak()` returns early on a refused focus request (`:116-121`, `:138-143`) | **yes** |
| 8 | `app/…/ui/MapScreen.kt` | 2 imports; ~40 new lines above `:843`; `startNavigation()` (`:691`); nav loop (`:1054-1105`) | **yes** |
| 8 | `app/…/ui/SettingsScreen.kt` | `:307` wording | **yes** |
| 9 | `app/…/ui/MapScreen.kt` | 1 line inside `:871-874` | **yes** |
| 10 | `docs/refactor/mapscreen/15-divergence-register.md` | entries 12 + 15 resolved; §A `:1737`, `:1740`; §D assertions | n/a |
| 10 | `docs/refactor/mapscreen/specs/convergence-3-voice-policy.md` | Status `State` row | n/a |
| 10 | `docs/refactor/mapscreen/specs/00-chain-design.md` | the axis-complete note | n/a |

---

## Task 1: iOS's announce ladder gets inclusive phase boundaries

Entry 12's second sub-bug. Verified at `92823ed`: `grep -c 'case ..<Self.voiceNowM'
iosApp/Detour/NavScreen.swift` is **1** and `grep -c 'distance <= VOICE_NOW_M'
app/…/car/NavScreen.kt` is **1** — `..<` against `<=`. At exactly 800.0, 300.0 or 80.0 m the two
surfaces choose different phases.

The register's recommendation is *"survive — the car's inclusive boundaries"* (entry 12,
**Recommendation**). Swift's inclusive partial range is `...`, not `..<`.

### Step 1.1 — the three arms

- [ ] Replace `iosApp/Detour/NavScreen.swift:174-180` with:

```swift
        let phase: Int
        // Inclusive, matching the car's `distance <= VOICE_NOW_M`
        // (car/NavScreen.kt:305-310). `..<` and `<=` disagree at exactly 800,
        // 300 and 80 m — a measure-zero event on a real GPS stream, and a
        // guaranteed one in any test written against either surface. Register
        // entry 12.
        switch distance {
        case ...Self.voiceNowM: phase = 3
        case ...Self.voiceNearM: phase = 2
        case ...Self.voiceFarM: phase = 1
        default: phase = 0
        }
```

### Step 1.2 — verify

- [ ] Greps:

```sh
N=iosApp/Detour/NavScreen.swift
grep -c 'case ...Self.voiceNowM' $N          # expect 1
grep -c 'case ..<Self.voiceNowM' $N          # expect 0
grep -c 'case ...Self.voiceNearM' $N         # expect 1
grep -c 'case ...Self.voiceFarM' $N          # expect 1
grep -c 'voiceFarM' $N                       # expect 2 — declaration + use, unchanged
```

- [ ] Not compilable here (no Swift toolchain, no macOS, no iOS test target). `ios.yml`'s
      `xcodebuild build` on the open PR is the first thing that type-checks it — see
      **Verification**. A `switch` over `Double` with three `PartialRangeThrough` cases and a
      `default` is exhaustive by construction, which is the strongest honest claim available here.

### Step 1.3 — commit

```
fix(ios): make the announce ladder's phase boundaries inclusive

The car selects a spoken prompt with `distance <= VOICE_NOW_M`; iOS used
`case ..<Self.voiceNowM`. At exactly 800, 300 or 80 metres the two surfaces
picked different phases, so a rider could hear "in 800 metres" on one phone and
nothing on the other.

iOS moves to `...`, the car's inclusive bound, because that is the copy the
register chose to survive. Measure-zero on a real GPS stream; not measure-zero
in a test, which is the reason to settle it before one is written against
either surface.

Register entry 12, second sub-bug. The missing voice.stop() on mute is the
other one and is not in this commit.

Not verified: nothing was built or run. No Swift toolchain and no Mac here, and
iosApp/ has no test target.
```

---

## Task 2: iOS's mute stops the utterance in flight

Entry 12's first sub-bug. Verified at `92823ed`: `grep -c 'voice.stop()'
iosApp/Detour/NavScreen.swift` is **1**, and that one is the `.onDisappear` path
(`:42` → `stop()` at `:153-156`). Nothing reacts to `Settings.voiceGuidance` changing while the
nav cover is up, so muting mid-drive finishes the sentence — where the car cuts it
(`car/NavScreen.kt:479-480`).

**The watcher already exists.** `SettingsFlows.shared.voiceGuidance()` returns a `BoolWatcher`
(`shared/…/iosMain/…/FlowWatcher.kt:140`), so this costs **zero** new watcher subclasses. The
shipped pattern to copy is `iosApp/Detour/SettingsScreen.swift:214` (a `private let` property),
`:228` (`watch` in `init`) and `:237-240` (`cancel()` in `deinit`).

**Cancel in `deinit`, not in `stop()`.** `Watcher.cancel()` cancels the watcher's whole
`CoroutineScope` (`FlowWatcher.kt:39-43`), so a cancelled watcher cannot be re-watched. `NavModel`
is a `@StateObject` on `NavScreen` (`NavScreen.swift:18`) and `stop()` runs on every
`.onDisappear`; cancelling there would leave a second nav session in the same view with a dead
watcher.

### Step 2.1 — hold the watcher

- [ ] In `iosApp/Detour/NavScreen.swift`, after `private var startAnnounced = false` (`:140`):

```swift
    /// Spoken guidance being switched off has to cut the prompt already in
    /// flight, which is what the car does (car/NavScreen.kt:479-480). Without
    /// this, muting mid-drive finishes the sentence — and the toggle is not
    /// reachable from the full-screen nav cover, so the only way to silence it
    /// was to leave navigation. Register entry 12, first sub-bug.
    ///
    /// A property and not a local: `Watcher.watch` holds the subscription for
    /// the object's life and `cancel()` tears down its whole scope, so it is
    /// cancelled in `deinit` and never in `stop()` — `stop()` runs on every
    /// `.onDisappear` and a cancelled watcher cannot be re-watched.
    private let voiceWatch = SettingsFlows.shared.voiceGuidance()
```

### Step 2.2 — react to it

- [ ] Immediately above `func start(route: RouteResult)` (`:146`):

```swift
    init() {
        voiceWatch.watch { [weak self] in
            // `watch` fires once with the current value as well as on every
            // change; stopping a synthesizer that is not speaking is a no-op,
            // so no edge detection is needed here.
            guard self?.voiceWatch.value == false else { return }
            self?.voice.stop()
        }
    }

    deinit {
        voiceWatch.cancel()
    }
```

- [ ] `NavVoice` is `@MainActor` (`NavVoice.swift:14`) and so is `NavModel` (`NavScreen.swift:128`);
      `Watcher.scope` is `CoroutineScope(Dispatchers.Main)` (`FlowWatcher.kt:27`), so the callback
      already arrives on the main queue. **If `ios.yml` rejects the `deinit` for calling into a
      `@MainActor` type**, mirror `SettingsScreen.swift:237-240` exactly — that spelling compiles
      in this repo today — and if it still fails, move the `cancel()` into a
      `nonisolated deinit`. Do not drop the cancel: an uncancelled watcher keeps a
      `Dispatchers.Main` scope alive for the process lifetime.

### Step 2.3 — verify

- [ ] Greps:

```sh
N=iosApp/Detour/NavScreen.swift
grep -c 'voice.stop()' $N                        # expect 2 — the onDisappear one plus this
grep -c 'SettingsFlows.shared.voiceGuidance()' $N # expect 1
grep -c 'voiceWatch.cancel()' $N                 # expect 1
grep -c 'onDisappear { model.stop() }' $N        # expect 1 — the existing path untouched
grep -rc 'class BoolWatcher' shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt
                                                 # expect 1 — no new watcher subclass
```

- [ ] Not compilable here. Same reason as Task 1.

### Step 2.4 — commit

```
fix(ios): stop the spoken prompt when spoken guidance is switched off

NavModel read Settings.voiceGuidance only at the moment it spoke, so flipping
the switch mid-drive let the current sentence finish. The car cuts it
(car/NavScreen.kt:479-480), and on iOS the toggle is not reachable from the
full-screen nav cover at all, so the only way to silence guidance was to leave
navigation.

NavModel now watches the setting through the BoolWatcher that already exists in
FlowWatcher.kt and calls voice.stop() when it reads false. No new watcher
subclass. Cancelled in deinit rather than in stop(), because stop() runs on
every .onDisappear and Watcher.cancel() tears down the scope for good.

Register entry 12, first sub-bug. The <=/..< boundary was the other one and
landed separately.

Not verified: nothing was built or run. No Swift toolchain and no Mac here, and
iosApp/ has no test target.
```

---

## Task 3: `NavAnnouncer` into commonMain, with `commonTest`

Spec item 3 and design decisions D1–D3. **Nothing consumes it in this commit** — that is Tasks 4
and 5, and keeping them separate is what makes each repoint a reviewable no-op.

Verified at `92823ed`: the two copies agree on `800/300/80`
(`car/NavScreen.kt:65-67` ↔ `NavScreen.swift:142-144`), on `spokenDistance`
(`car/NavScreen.kt:577-583` ↔ `NavScreen.swift:203-210`) and on the latch's three fields
(`car/NavScreen.kt:128-130` ↔ `NavScreen.swift:138-140`). After Task 1 they also agree on the
boundary, so this commit characterises agreed behaviour rather than choosing a winner.

**Where it goes, and why not `drive/`.** `shared/src/commonMain/kotlin/com/jellemax/detour/data/`
— all **36** commonMain files live in `data/` (verified). Stage 3 plans a `drive/` package;
creating it from the convergence axis would add a second edge between the two axes where
`00-chain-design.md` § *The two axes* permits exactly one (*"If you find yourself adding a second
cross-reference, one of the two specs has taken on the other axis' work"*). Same package as
`NavEngine.kt` and `RoutingServer.kt` also means `NavInstruction` needs no import. If stage 3 later
creates `drive/`, moving this file there is a free same-module move.

**No constructor parameters, and the thresholds are not read from Swift.** Kotlin/Native drops
default argument values on the way to Objective-C, which is why `GeofenceEvaluator` carries a
`withDefaults()` factory (`CircleEvents.kt:163-169`); `NavAnnouncer()` avoids the wart by having
nothing to default. The `const val`s stay inside the companion and are **never** read from Swift —
`FlowWatcher.kt:180-182` records that a `const val` in an object *"crosses as a static whose
spelling depends on the compiler version"*. If a surface ever needs one, add an `Enums`-style
accessor in `iosMain`; do not guess the mangled name.

### Step 3.1 — the policy

- [ ] Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/NavAnnouncer.kt`:

```kotlin
package com.jellemax.detour.data

/**
 * When a navigation session speaks, and what it says.
 *
 * Written twice before this file existed — `app/…/car/NavScreen.kt` and
 * `iosApp/Detour/NavScreen.swift`, both with the same three thresholds, the
 * same `spokenDistance` and the same three-field latch — and register decision
 * 1 adds the phone as a third consumer. A policy written more than once earns
 * the core (`CONTRIBUTING.md:23-32`), and the point of writing it here is that
 * three surfaces can no longer word the same maneuver differently.
 *
 * **Decision and wording here; delivery per platform.** The same split
 * `CircleEvents.notificationText` makes, for the same reason. Nothing in this
 * file knows about `TextToSpeech`, `AVSpeechSynthesizer`, `ToneGenerator` or a
 * `CarToast`; a core that did could not be shared.
 *
 * **No clock.** The latch is path-dependent over the *distance* sequence, not
 * over time — there is no timestamp in either copy this replaces — so there is
 * nothing to inject and nothing to fake. The one nav rule that does need a
 * clock, the reroute cooldown, is [com.jellemax.detour.map.NavPolicy]'s and
 * takes `nowMs` as a parameter.
 *
 * **Nothing here is derived from [NavInstruction.sign].** The cue is
 * GraphHopper's own `text`, which is already words. The sign-to-glyph table is
 * four per-platform copies (register entry 4) and deliberately stays that way:
 * rendering a maneuver from its sign code here would make this a fifth copy of
 * that table, in prose.
 *
 * One instance per navigation session, fed in the order the fixes arrive —
 * same contract as [GeofenceEvaluator], and the reason this is a class rather
 * than a free function.
 */
class NavAnnouncer {

    companion object {
        // Where the spoken prompts land, in metres before the turn. Three of
        // them: one early enough to change lane on a fast road, one to commit,
        // and one at the turn itself. Each fires at most once per instruction,
        // and a step that starts closer than a threshold simply skips it — in
        // town that usually means only the last two are heard.
        //
        // Not constructor parameters: Kotlin/Native drops default argument
        // values, so a defaulted constructor would need a withDefaults()
        // factory for Swift the way GeofenceEvaluator does. Not read from
        // Swift either — see the file's KDoc.
        const val FAR_METERS = 800.0
        const val NEAR_METERS = 300.0
        const val NOW_METERS = 80.0
    }

    // Which instruction is being announced, and how far through its three
    // prompts we are.
    private var stepKey = Int.MIN_VALUE
    private var phase = 0
    private var startAnnounced = false

    /**
     * Re-arms everything: the next call to [onProgress] announces whatever the
     * route asks for, at whatever distance it is.
     *
     * Called at the start of a session, and after a reroute succeeds —
     * instruction indices belong to the old polyline, so the new line's prompts
     * have to start from scratch. Deliberately *not* called by [rerouting]:
     * the car speaks "Rerouting" before the request and re-arms only if the
     * request comes back (`car/NavScreen.kt:261` versus `:275-277`).
     */
    fun routeChanged() {
        stepKey = Int.MIN_VALUE
        phase = 0
        startAnnounced = false
    }

    /** What to say when a reroute is requested. Here rather than at three call
     *  sites for the same reason as [PlaceEvent.notificationText]: it is text a
     *  user hears, and the surfaces must not word it differently. */
    fun rerouting(): String = "Rerouting"

    /**
     * The words for this fix, or null when nothing is due — which is most
     * fixes. Call once per fix, in order.
     *
     * [distanceMeters] is [NavEngine.Progress.distanceToTurnMeters].
     * [instruction] is [NavEngine.Progress.nextInstruction]; null means there
     * is no maneuver ahead and nothing to say.
     */
    fun onProgress(instruction: NavInstruction?, distanceMeters: Double): String? {
        instruction ?: return null
        if (instruction.startIndex != stepKey) {
            stepKey = instruction.startIndex
            phase = 0
        }
        // Inclusive bounds. iOS used a half-open range until convergence 3 and
        // disagreed with the car at exactly 800, 300 and 80 m; register entry
        // 12 chose the car's.
        val next = when {
            distanceMeters <= NOW_METERS -> 3
            distanceMeters <= NEAR_METERS -> 2
            distanceMeters <= FAR_METERS -> 1
            else -> 0
        }
        val cue = instruction.text.ifBlank { "Continue" }
        // The first prompt of the drive ignores the thresholds: pressing Start
        // and being told nothing for the next 3 km is indistinguishable from
        // voice being broken.
        if (!startAnnounced) {
            startAnnounced = true
            phase = next
            return prompt(next, distanceMeters, cue)
        }
        if (next == 0 || next <= phase) return null
        phase = next
        return prompt(next, distanceMeters, cue)
    }

    /** At the turn itself the distance is noise — "turn right" is the whole
     *  message. Further out it is the useful half. */
    private fun prompt(phase: Int, distanceMeters: Double, cue: String): String =
        if (phase == 3) cue else "In ${spokenDistance(distanceMeters)}, $cue"
}

/** Distance as a driver would say it, for the spoken prompts. Not the same
 *  rounding as a banner or a template uses — those quantise for a glance and
 *  keep their own copies (`car/NavScreen.kt`'s `displayMeters`,
 *  `NavScreen.swift`'s `displayDistance`). `internal` so `commonTest` can
 *  assert the buckets directly; no surface needs it. */
internal fun spokenDistance(meters: Double): String = when {
    meters >= 1500.0 -> "${(meters / 1000.0).roundToInt()} kilometers"
    meters >= 950.0 -> "1 kilometer"
    meters >= 100.0 -> "${(meters / 100.0).roundToInt() * 100} meters"
    else -> "${(meters / 10.0).roundToInt() * 10} meters"
}
```

- [ ] Add `import kotlin.math.roundToInt` at the top, after the `package` line. `kotlin.math` is
      available in commonMain (`detour-shared-core` §4); `java.lang.Math` is not, and would fail
      `compileCommonMainKotlinMetadata` rather than at link time.

### Step 3.2 — the tests

- [ ] Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/NavAnnouncerTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterises the ladder both surfaces already implemented, including the one
 * boundary they disagreed on until convergence 3 made iOS inclusive. Written
 * before either surface was repointed, so a repoint that changes behaviour
 * fails here rather than in the field.
 */
class NavAnnouncerTest {

    private fun turn(text: String = "Turn right", startIndex: Int = 0) = NavInstruction(
        text = text, distanceMeters = 0.0, sign = 2, startIndex = startIndex, endIndex = startIndex + 1,
    )

    @Test
    fun firstPromptIgnoresTheLadder() {
        val a = NavAnnouncer()
        // 3 km out is phase 0. Being told nothing for the next 3 km after
        // pressing Start is indistinguishable from voice being broken.
        assertEquals("In 3 kilometers, Turn right", a.onProgress(turn(), 3000.0))
    }

    @Test
    fun eachPhaseFiresOnceAndOnlyUpward() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)            // burns the start prompt
        assertNull(a.onProgress(turn(), 900.0))
        assertEquals("In 800 meters, Turn right", a.onProgress(turn(), 800.0))
        assertNull(a.onProgress(turn(), 700.0))
        assertEquals("In 300 meters, Turn right", a.onProgress(turn(), 300.0))
        assertNull(a.onProgress(turn(), 100.0))
        assertEquals("Turn right", a.onProgress(turn(), 80.0))
        assertNull(a.onProgress(turn(), 10.0))
    }

    @Test
    fun boundariesAreInclusive() {
        // The case iOS got wrong with `..<`: exactly on a threshold is inside
        // it. Register entry 12.
        for ((distance, expected) in listOf(
            800.0 to "In 800 meters, Turn right",
            300.0 to "In 300 meters, Turn right",
            80.0 to "Turn right",
        )) {
            val a = NavAnnouncer()
            a.onProgress(turn(), 3000.0)
            assertEquals(expected, a.onProgress(turn(), distance), "at $distance m")
        }
    }

    @Test
    fun justOutsideTheFarBoundIsSilent() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)
        assertNull(a.onProgress(turn(), 800.001))
    }

    @Test
    fun aNewInstructionRearmsTheLatch() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)
        assertEquals("Turn right", a.onProgress(turn(startIndex = 0), 80.0))
        // Next step of the route: the phase latch is per instruction, so a
        // fresh startIndex hears all three prompts again.
        assertEquals(
            "In 800 meters, Turn left",
            a.onProgress(turn("Turn left", startIndex = 7), 800.0),
        )
    }

    @Test
    fun routeChangedRearmsTheStartPromptToo() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)
        a.onProgress(turn(), 80.0)
        a.routeChanged()
        // Same instruction, same distance, and it speaks: indices belong to the
        // old polyline, so a reroute starts the prompts from scratch.
        assertEquals("Turn right", a.onProgress(turn(), 80.0))
    }

    @Test
    fun noInstructionIsSilent() {
        assertNull(NavAnnouncer().onProgress(null, 100.0))
    }

    @Test
    fun blankInstructionTextBecomesContinue() {
        assertEquals("Continue", NavAnnouncer().onProgress(turn(text = ""), 50.0))
    }

    @Test
    fun reroutingWording() {
        assertEquals("Rerouting", NavAnnouncer().rerouting())
    }

    @Test
    fun spokenDistanceBuckets() {
        // Characterising, not improving: 1500 m reading "2 kilometers" is the
        // shipped rounding on both surfaces, and register entry 19's distance
        // quantisation is out of scope here.
        assertEquals("2 kilometers", spokenDistance(1500.0))
        assertEquals("3 kilometers", spokenDistance(2600.0))
        assertEquals("1 kilometer", spokenDistance(1499.0))
        assertEquals("1 kilometer", spokenDistance(950.0))
        assertEquals("900 meters", spokenDistance(949.0))
        assertEquals("100 meters", spokenDistance(100.0))
        assertEquals("100 meters", spokenDistance(99.0))
        assertEquals("80 meters", spokenDistance(84.0))
        assertEquals("0 meters", spokenDistance(4.0))
    }
}
```

- [ ] Run them and **read the failures rather than editing the expectations**. Every value above
      was hand-derived from the two existing implementations; a mismatch means either the
      derivation or the port is wrong, and only one of those is fixed by changing a number.

```sh
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :shared:testDebugUnitTest
```

### Step 3.3 — prove no platform type leaked

- [ ] Greps:

```sh
S=shared/src/commonMain/kotlin/com/jellemax/detour/data/NavAnnouncer.kt
grep -c 'TextToSpeech\|AVSpeech\|ToneGenerator\|CarToast\|android\.\|java\.' $S  # expect 0
grep -c 'expect ' $S                                                            # expect 0
grep -c 'nowMs\|Clock' $S                                                       # expect 0
grep -c 'interface' $S                                                          # expect 0
grep -c 'instruction.sign' $S                                                   # expect 0 — entry 4 firewall
grep -rc 'class .*Watcher' shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt
                                                                                # expect 10 — 9 + abstract, unchanged
```

- [ ] Both surfaces are still on their own copies — this commit consumes nothing:

```sh
grep -c 'VOICE_FAR_M' app/src/main/java/com/jellemax/detour/car/NavScreen.kt   # expect 2
grep -c 'voiceFarM' iosApp/Detour/NavScreen.swift                              # expect 2
grep -rc 'NavAnnouncer' app/src/main/java iosApp/Detour 2>/dev/null | grep -v ':0' | wc -l
                                                                               # expect 0
```

- [ ] Build:

```sh
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :shared:compileCommonMainKotlinMetadata
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :shared:testDebugUnitTest :app:compileDebugKotlin
```

`compileCommonMainKotlinMetadata` is the one that matters here: it type-checks commonMain against
the common intersection (`ios.yml:58-59`), so a `java.*` import fails in seconds instead of during
the Kotlin/Native link. The Kotlin/Native run of the same test source set happens in CI
(`ios.yml:64-68`) and is what proves it behaves the same compiled for the device.

### Step 3.4 — commit

```
feat(shared): move the turn-announcement policy into commonMain

The announce ladder (800/300/80), spokenDistance and the three-field phase
latch were written twice — car/NavScreen.kt and iosApp/Detour/NavScreen.swift —
and register decision 1 adds the phone as a third consumer. A policy written
more than once earns the core, and the argument for moving it now rather than
after is that "after" means three copies.

NavAnnouncer holds the latch, because every rule in the ladder is a rule about
those three fields and a pure function would make each of three surfaces thread
them correctly. GeofenceEvaluator is the same shape for the same reason. It
returns the words, or null when nothing is due: decision and wording in the
core, delivery per platform, following CircleEvents.notificationText.

No clock and no nowMs parameter. The latch is path-dependent over the distance
sequence; neither copy this replaces reads a timestamp, so there is nothing to
inject. Nothing is derived from NavInstruction.sign either — the cue is
GraphHopper's own text. Deriving words from the sign code would make this a
fifth copy of the maneuver table (entry 4), in prose.

No constructor parameters, so Swift needs no withDefaults() factory the way
GeofenceEvaluator does; no StateFlow, so iosMain needs no tenth watcher
subclass; no expect.

Ten commonTest cases, including the 800/300/80 boundary iOS disagreed on until
the previous commit made it inclusive. Nothing consumes this yet — the car and
iOS repoints are the next two commits.

Verified: compileCommonMainKotlinMetadata, :shared:testDebugUnitTest,
:app:compileDebugKotlin, and greps proving no platform type, no expect, no
clock and no sign reference reached commonMain.
```

---

## Task 4: the car announces through `NavAnnouncer`

Trails Task 3 by exactly one commit. Behaviour-preserving by construction: the code in
`NavAnnouncer` is the car's own.

### Step 4.1 — import

- [ ] In `app/src/main/java/com/jellemax/detour/car/NavScreen.kt`, insert before
      `import com.jellemax.detour.data.NavEngine` (`:33`):

```kotlin
import com.jellemax.detour.data.NavAnnouncer
```

- [ ] Delete `import kotlin.math.roundToInt` (`:52`). Its only uses are inside `spokenDistance`
      (`:579`, `:581`, `:582`), which Step 4.4 deletes; `roundToLong` (`:53`) stays — `:547` and
      `displayMeters` still use it.

### Step 4.2 — the constants and the latch fields go

- [ ] Delete `:60-67` — the `// Where the spoken prompts land…` comment and the three
      `private const val VOICE_*` lines. The comment moved into `NavAnnouncer`'s companion verbatim.
- [ ] Delete `:126-130` — the `// Voice bookkeeping…` comment and `voiceStepKey`, `voicePhase`,
      `startAnnounced`.
- [ ] Add the announcer beside the voice it drives, immediately after
      `private val voice = NavVoice(carContext)` (`:106`):

```kotlin
    /** The ladder, the latch and the wording: `:shared`'s, so the head unit,
     *  the phone and iOS cannot word the same maneuver differently. One per
     *  session — it holds per-instruction state. */
    private val announcer = NavAnnouncer()
```

### Step 4.3 — `announce()` becomes a delivery site

- [ ] Replace `:297-324` (the KDoc and the whole body of `announce`) with:

```kotlin
    /** Speaks whatever [NavAnnouncer] says is due for this fix. The decision
     *  and the words are the core's; this screen only decides that speech is
     *  how the head unit delivers them. */
    private fun announce(p: NavEngine.Progress) {
        announcer.onProgress(p.nextInstruction, p.distanceToTurnMeters)?.let { speak(it) }
    }
```

`speak()` (`:293-295`) is unchanged and keeps the `Settings.voiceGuidance` gate.

- [ ] Replace `speak("Rerouting")` (`:261`) with:

```kotlin
                speak(announcer.rerouting())
```

- [ ] Replace `:275-277` — the three re-arm assignments inside the reroute success path — with:

```kotlin
                        // Instruction indices belong to the old polyline; start the
                        // prompts for the new one from scratch, "Rerouting" followed
                        // by what the new line asks for next.
                        announcer.routeChanged()
```

Keep the existing comment; only the three assignments are replaced. `templateKey = null` on the
next line stays — it is the template's re-arm, not the voice's.

### Step 4.4 — `spokenDistance` goes

- [ ] Delete `:577-583` — the `/** Distance as a driver would say it… */` KDoc and the whole
      `private fun spokenDistance`. `displayMeters` (`:571-575`) stays; it is the template's
      rounding and has nothing to do with speech.

### Step 4.5 — verify

- [ ] Greps:

```sh
CAR=app/src/main/java/com/jellemax/detour/car
grep -c 'VOICE_FAR_M\|VOICE_NEAR_M\|VOICE_NOW_M' $CAR/NavScreen.kt   # expect 0
grep -c 'voiceStepKey\|voicePhase\|startAnnounced' $CAR/NavScreen.kt # expect 0
grep -c 'spokenDistance' $CAR/NavScreen.kt                           # expect 0
grep -c 'displayMeters' $CAR/NavScreen.kt                            # expect 3 — unchanged
grep -c 'NavAnnouncer' $CAR/NavScreen.kt                             # expect 2 — import + field
grep -c 'announcer.routeChanged()' $CAR/NavScreen.kt                 # expect 1
grep -c 'speak(' $CAR/NavScreen.kt                                   # expect 6 — unchanged
grep -c 'roundToInt' $CAR/NavScreen.kt                               # expect 0
grep -c 'roundToLong' $CAR/NavScreen.kt                              # expect 4
grep -c '+ 3.0' $CAR/NavScreen.kt                                    # expect 1 — entry 13 untouched
grep -c '45.0' $CAR/NavScreen.kt                                     # expect 1 — entry 13 untouched
grep -c '"Off route"' $CAR/NavScreen.kt                              # expect 1 — e6a6bf2 untouched
```

- [ ] Tier 0, in the devcontainer:

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh 92823ed \
    app/src/main/java/com/jellemax/detour/car/NavScreen.kt
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:compileDebugKotlin
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:assembleDebug :app:assembleRelease
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```

`assembleRelease` is not redundant: R8 catches what a debug build does not
(`detour-staged-refactor` §5).

- [ ] **No new unit test on the car side, and that is not an oversight.** What was extracted is
      now covered by `NavAnnouncerTest`; what remains in `NavScreen.kt` is a one-line delivery call
      needing a `CarContext` and a `Screen`, and this repo has no Robolectric and no `androidTest`
      source set. Say this in the commit rather than implying coverage.

### Step 4.6 — commit

```
refactor(car): announce through the shared NavAnnouncer

Deletes the car's copy of the ladder: VOICE_FAR_M/NEAR_M/NOW_M, the three latch
fields, announce()'s body and spokenDistance. The screen keeps the delivery
decision — speech, gated on Settings.voiceGuidance — and nothing else.

Behaviour-preserving by construction: NavAnnouncer is this code, moved. The
extraction landed in the previous commit and is not folded in here.

roundToInt's import goes with spokenDistance, its only user. displayMeters
stays: that is the template's rounding, not the voice's.

Verified: tier0-greps, compileDebugKotlin, assembleDebug, assembleRelease,
:app: and :shared: unit tests, and greps proving the entry-13 literals (+3.0,
45.0) and e6a6bf2's off-route indicator are untouched. No new unit test — the
extracted logic is covered by NavAnnouncerTest and what is left needs a
CarContext, which nothing here can build.

Not verified: how it sounds. That needs a head unit or a DHU with audio.
```

---

## Task 5: iOS announces through `NavAnnouncer`

The mirror of Task 4. **This commit changes iOS behaviour at exactly three distances** — Task 1's
inclusive boundary arriving through the core — and nowhere else.

### Step 5.1 — hold an announcer instead of a latch

- [ ] In `iosApp/Detour/NavScreen.swift`, replace `:136-144` (the `// Voice bookkeeping` comment,
      `voiceStepKey`, `voicePhase`, `startAnnounced` and the three `voiceFarM/NearM/NowM` statics)
      with:

```swift
    /// The ladder, the latch and the wording: `:shared`'s, so this app, the
    /// phone and the head unit cannot word the same maneuver differently.
    /// One per session — it holds per-instruction state.
    private let announcer = NavAnnouncer()
```

`voiceWatch` (added in Task 2) sits above this and is unchanged.

### Step 5.2 — `start` and `announce`

- [ ] Replace `start(route:)` (`:146-151`) with:

```swift
    func start(route: RouteResult) {
        self.route = route
        announcer.routeChanged()
    }
```

- [ ] Replace `announce(_:)` (`:166-195`) — the doc comment and the whole body — with:

```swift
    /// Says whatever `NavAnnouncer` says is due for this fix. The decision and
    /// the words are the core's; this model only decides that speech is how
    /// iOS delivers them.
    private func announce(_ p: NavEngine.Progress) {
        if let text = announcer.onProgress(
            instruction: p.nextInstruction, distanceMeters: p.distanceToTurnMeters
        ) {
            say(text)
        }
    }
```

`say(_:)` (`:197-199`) is unchanged and keeps the `SettingsValues.shared.voiceGuidance` gate.

### Step 5.3 — `spokenDistance` goes

- [ ] Delete the file-level `private func spokenDistance` and its doc comment (`:202-210`).
      **`displayDistance` (`:212-218`) stays** — it is the banner's rounding, called from the
      banner, and has nothing to do with speech.

### Step 5.4 — verify

- [ ] Greps:

```sh
N=iosApp/Detour/NavScreen.swift
grep -c 'voiceFarM\|voiceNearM\|voiceNowM' $N   # expect 0
grep -c 'voiceStepKey\|voicePhase\|startAnnounced' $N  # expect 0
grep -c 'spokenDistance' $N                     # expect 0
grep -c 'displayDistance' $N                    # expect 2 — declaration + banner use
grep -c 'NavAnnouncer()' $N                     # expect 1
grep -c 'announcer.routeChanged()' $N           # expect 1
grep -c 'voice.stop()' $N                       # expect 2 — Task 2's fix survives
grep -c 'maneuverIcon' $N                       # expect 2 — entry 4 untouched
```

- [ ] Not compilable here. **The Swift-side spelling of the Kotlin API is the one real risk in
      this task and `ios.yml` is the arbiter.** `fun onProgress(instruction: NavInstruction?,
      distanceMeters: Double): String?` should reach Swift as
      `onProgress(instruction:distanceMeters:)` returning `String?`, and `NavAnnouncer()` should
      be constructible because it has no constructor parameters. If the generated header disagrees,
      **read it** rather than guessing:

```sh
# On the CI runner or a Mac, after packForXcode:
grep -A3 'onProgress' shared/build/xcode-frameworks/**/DetourShared.framework/Headers/DetourShared.h
```

      Do not work around a naming surprise by adding an `iosMain` shim without saying so in the
      commit — the whole point of the extraction is that there is one implementation.

### Step 5.5 — commit

```
refactor(ios): announce through the shared NavAnnouncer

Deletes iOS's copy of the ladder: voiceFarM/NearM/NowM, the three latch fields,
announce()'s body and spokenDistance. NavModel keeps the delivery decision —
AVSpeechSynthesizer, gated on Settings.voiceGuidance — and nothing else.

This changes iOS behaviour at exactly three distances and nowhere else: the
core's bounds are inclusive, which is what the previous iOS commit already
applied locally and what register entry 12 chose. At 800.0, 300.0 or 80.0 m
exactly, iOS now picks the same phase the head unit does.

displayDistance stays: that is the banner's rounding, not the voice's. The
maneuver icon table is untouched — entry 4 is a separate divergence and the core
derives nothing from NavInstruction.sign on purpose.

Not verified: nothing was built or run. No Swift toolchain and no Mac here, and
iosApp/ has no test target. The Kotlin/Native spelling of onProgress is
type-checked for the first time by ios.yml on the pull request.
```

---

> ## Stop-point
>
> - [ ] Everything above is verifiable without hardware. Everything below is not.
> - [ ] Record the stop-point in `specs/convergence-3-voice-policy.md`'s Status: **items 1–5 done**,
>       with their SHAs, and **items 6–9 not started, waiting on a device session.**
> - [ ] Do **not** write a stop-point sentence into `DECISION.md`. That file is the structure axis'
>       roadmap; the convergence axis' is the register's §C.1 (`00-chain-design.md` § *The two
>       axes*), and writing into the wrong one misreports the chain.
> - [ ] Push, so `ios.yml` type-checks Tasks 1, 2 and 5 on the open PR before anyone starts Task 6.
>       Three uncompiled Swift commits is the maximum worth carrying.

---

## Task 6: `NavVoice` moves out of `car/`

Spec D4. Verified at `92823ed`: `car/NavVoice.kt` imports `android.content.Context`,
`android.media.{AudioAttributes,AudioFocusRequest,AudioManager}`, `android.os.{Handler,Looper}`,
`android.speech.tts.{TextToSpeech,UtteranceProgressListener}` and `java.util.Locale` — and
`grep -c 'androidx.car' app/src/main/java/com/jellemax/detour/car/NavVoice.kt` is **0**. It is in
`car/` by history, not by dependency.

`app/` and `car/` are the same Gradle module and the same package root, so this is a plain move
under `app/` — not a `shared/` move and not an interface (`detour-shared-core` §1). Destination is
`app/…/audio/`, which already holds the app's other audio client, `PushToTalk.kt`.

**The package changes, so the move is not free.** One import in `car/NavScreen.kt` is the whole
cost. One doc paragraph also changes, because the current one says *"for the car screen"* and
Task 8 makes that false; the class **body** is byte-identical and Step 6.3 proves it.

### Step 6.1 — move the file

- [ ] `git mv app/src/main/java/com/jellemax/detour/car/NavVoice.kt
      app/src/main/java/com/jellemax/detour/audio/NavVoice.kt`
- [ ] Change the `package` line (`:1`) to:

```kotlin
package com.jellemax.detour.audio
```

- [ ] Replace the class KDoc's first paragraph (`:16-18`) — everything from
      `Spoken turn instructions for the car screen.` down to the blank line before
      `Android Auto has no voice API of its own` — with:

```kotlin
/**
 * Spoken turn instructions, for any surface that navigates.
 *
 * Lived in `car/` until convergence 3, which is where the only voice in the app
 * used to be. It never depended on a single `androidx.car` type, and the phone
 * needs exactly the same audio bargain, so it moved here beside [PushToTalk] —
 * the app's other audio client — rather than being written a second time.
 *
```

Keep the rest of the KDoc verbatim: the `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` paragraph is as
true on a phone with a headset as on a head unit, and the focus paragraph is the contract Task 7
sharpens.

### Step 6.2 — the one import

- [ ] In `app/src/main/java/com/jellemax/detour/car/NavScreen.kt`, insert before
      `import com.jellemax.detour.data.LatLon` (`:32`):

```kotlin
import com.jellemax.detour.audio.NavVoice
```

### Step 6.3 — prove the body did not change

- [ ] The declaration lines are identical apart from the package:

```sh
git show HEAD:app/src/main/java/com/jellemax/detour/car/NavVoice.kt \
  | sed -n '2,$p' | grep -v '^ \*\|^/\*\*\|^ \*/' > /tmp/navvoice-before.txt
sed -n '2,$p' app/src/main/java/com/jellemax/detour/audio/NavVoice.kt \
  | grep -v '^ \*\|^/\*\*\|^ \*/' > /tmp/navvoice-after.txt
diff /tmp/navvoice-before.txt /tmp/navvoice-after.txt && echo "body identical"
```

- [ ] Git sees a rename, not a delete and an add:

```sh
git diff --cached -M --stat   # expect a single R### line for NavVoice.kt
```

- [ ] Greps:

```sh
grep -c 'package com.jellemax.detour.audio' app/src/main/java/com/jellemax/detour/audio/NavVoice.kt  # expect 1
grep -rl 'TextToSpeech' app/src/main/java/com/jellemax/detour | wc -l   # expect 1 — the moved file
grep -c 'androidx.car' app/src/main/java/com/jellemax/detour/audio/NavVoice.kt  # expect 0
grep -c 'import com.jellemax.detour.audio.NavVoice' app/src/main/java/com/jellemax/detour/car/NavScreen.kt  # expect 1
ls app/src/main/java/com/jellemax/detour/car/NavVoice.kt 2>&1  # expect: No such file
```

- [ ] Build:

```sh
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:compileDebugKotlin :app:assembleDebug :app:assembleRelease
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:testDebugUnitTest
```

### Step 6.4 — commit

```
refactor(app): move NavVoice out of car/ into audio/

NavVoice was in car/ because that was the only surface with a voice. It never
imported a single androidx.car type, and the phone needs exactly the same audio
bargain — transient-may-duck focus per prompt, navigation-guidance usage — so
it moves next to PushToTalk, the app's other audio client, rather than being
written a second time. app/ and car/ are the same module, so this is a plain
move and needs no shared/ and no interface.

The class body is byte-identical; the diff is the package line, one import in
car/NavScreen.kt, and the doc paragraph that said "for the car screen", which
the phone commit two ahead makes false.

Verified: git sees one rename, the declaration lines diff clean against HEAD,
compileDebugKotlin, assembleDebug, assembleRelease, :app: unit tests.
```

---

## Task 7: `NavVoice` stays quiet when the focus request is refused

Spec D5, silence 2. **A behaviour change on the car as well as the phone**, which is why it is its
own commit and not folded into Task 8.

Verified at `92823ed`: `requestFocus()` (`:138-143`) records the result in `holdingFocus` and
`speak()` (`:116-121`) then speaks regardless. `abandonFocus()` (`:145-149`) returns early when
`holdingFocus` is false, so today a refused request means the app talks over whoever refused it and
never abandons anything.

**What refuses.** `AUDIOFOCUS_REQUEST_FAILED` on a `GAIN_TRANSIENT_MAY_DUCK` request means another
client holds focus and will not share it: telephony during a call, a voice assistant, another
turn-by-turn app. Talking over any of those three is wrong on a head unit too.

**What this is not.** It is not a convoy check — `ConvoyLiveService` takes
`AUDIOFOCUS_GAIN_TRANSIENT` (`convoy/ConvoyLiveService.kt:172-183`) and registers no
`OnAudioFocusChangeListener`, so a later `MAY_DUCK` request is *granted* and the convoy keeps
playing at full volume regardless. That case is Task 8's, gated at the phone's call site, because it
is a property of the phone's own feature set and not of the audio API.

### Step 7.1 — return the grant

- [ ] In `app/src/main/java/com/jellemax/detour/audio/NavVoice.kt`, replace `requestFocus()`
      (`:138-143`) with:

```kotlin
    /** True when the focus is ours. False means another client holds it and
     *  will not share — telephony during a call, a voice assistant, another
     *  turn-by-turn app. */
    private fun requestFocus(): Boolean {
        if (holdingFocus) return true
        holdingFocus = runCatching {
            audioManager?.requestAudioFocus(focusRequest)
        }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return holdingFocus
    }
```

### Step 7.2 — honour it

- [ ] Replace the body of `speak(text)` (`:110-121`) with:

```kotlin
        if (text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            pending = text
            return
        }
        // A refused request means somebody else owns the output and said no to
        // sharing it: a phone call, an assistant, another navigation app.
        // Speaking anyway put a guidance prompt on top of a conversation — and
        // because abandonFocus() no-ops when the request failed, nothing was
        // ever handed back either. A missed prompt is the better of the two.
        if (!requestFocus()) {
            Log.w(TAG, "audio focus refused; guidance prompt dropped")
            return
        }
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrDefault(TextToSpeech.ERROR)
        // No utterance means no onDone, so nothing would hand the focus back.
        if (result != TextToSpeech.SUCCESS) abandonFocus()
```

- [ ] Add the logging import and tag. `android.util.Log` after `android.speech.tts.*` — insert
      after `:10`:

```kotlin
import android.util.Log
```

      and beside the existing `UTTERANCE_ID` constant (`:13`):

```kotlin
private const val TAG = "DetourNavVoice"
```

      The log line is deliberate: it is the only way a device session can tell *refused* from
      *no TTS engine installed*, and those two look identical from the outside.

### Step 7.3 — verify

- [ ] Greps:

```sh
V=app/src/main/java/com/jellemax/detour/audio/NavVoice.kt
grep -c 'if (!requestFocus()) {' $V                      # expect 1
grep -c 'private fun requestFocus(): Boolean' $V         # expect 1
grep -c 'AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK' $V          # expect 1 — still ducking, not pausing
grep -c 'audio focus refused' $V                         # expect 1
grep -c 'ConvoyLive\|activeConvoyId' $V                  # expect 0 — the convoy gate is not here
```

- [ ] Build (both variants; R8 sees the new branch):

```sh
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:compileDebugKotlin :app:assembleDebug :app:assembleRelease
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:testDebugUnitTest
```

- [ ] **No unit test.** `NavVoice` needs a `Context`, an `AudioManager` and a `TextToSpeech`
      engine; there is no Robolectric and no `androidTest` source set here. This is the change in
      the plan with the widest gap between "compiles" and "verified", and the device session in
      **Needs a human** item 3 is the only thing that closes it.

### Step 7.4 — commit

```
fix(app): don't speak when the audio focus request is refused

NavVoice recorded the result of requestAudioFocus in holdingFocus and then
spoke regardless. A refused GAIN_TRANSIENT_MAY_DUCK request means another
client holds the output and will not share it — telephony during a call, a
voice assistant, another turn-by-turn app — so the prompt went out on top of a
conversation. And because abandonFocus() returns early when holdingFocus is
false, nothing was handed back afterwards either.

A missed prompt is better than talking over a phone call, so a refused request
now drops the utterance and logs it. The log line is the point: from outside,
"focus refused" and "no TTS engine installed" look identical.

This changes the head unit's behaviour too, on purpose — the reasoning is not
phone-specific — which is why it is its own commit ahead of the phone feature
rather than part of it.

Not a convoy check. ConvoyLiveService takes GAIN_TRANSIENT without a focus-change
listener, so a later MAY_DUCK request is granted and this branch never fires for
it; that case is gated at the phone's call site in the next commits.

Verified: compileDebugKotlin, assembleDebug, assembleRelease, :app: unit tests.
Not verified: what a device actually does. No Robolectric and no androidTest
source set here, so nothing exercises TextToSpeech or AudioManager.
```

---

## Task 8: spoken turn instructions on the phone

The feature. Register decision 1, entry 12's main half. Verified at `92823ed`:
`grep -rl 'TextToSpeech' app/src/main/java/com/jellemax/detour | wc -l` is **1** and that one file
is `audio/NavVoice.kt` after Task 6 — the phone still says nothing.

**Where the machinery goes, and why there.** Immediately **above** `fun stopNavigation()`
(`MapScreen.kt:679`), not next to the nav loop at `:1054`. Kotlin resolves local declarations in
order and this block has four callers spread through the composable: `stopNavigation()` (`:679`),
the camera collector (`:856`, Task 9), the nav loop (`:1054`) and `startNavigation()` (`:691`).
`:679` is the only point above all four, it is composable scope (the preceding
`LaunchedEffect(mapLibreMap)` closes at `:677`), and `context` is already in scope from `:129`.

**Which effects are touched, and which are not.** The nav loop at `:1054` reads `liveFix`, the
`collectAsStateWithLifecycle()` state at `:201`. It adds **no** `lastFix` subscription: the file's
six raw-and-lifecycle collectors (`:201`, `:423`, `:735`, `:774`, `:856`, `:889`) stay six, and
this commit changes exactly one of them (`detour-compose-state-hazards` §4). Task 9 changes a
second, in its own commit.

**A consequence to record rather than fix** (spec D6). Because `liveFix` is lifecycle-aware, the
nav loop stops re-running below `STARTED` — so **the phone announces turns only while the app is
foregrounded.** The camera collector at `:856` is a plain `LaunchedEffect(Unit)` and is not
lifecycle-aware, so Task 9's hazard cue *will* speak with the screen off. That asymmetry is
deliberate: making turn prompts survive a dark screen means moving the announcer into
`TripTrackingService`, which is a state-ownership change and stage 4's subject. Do not add a
seventh `lastFix` collector to paper over it.

### Step 8.1 — imports

- [ ] In `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`, insert before
      `import com.jellemax.detour.audio.PushToTalk` (`:72`):

```kotlin
import com.jellemax.detour.audio.NavVoice
```

- [ ] And before `import com.jellemax.detour.data.NavEngine` (`:81`):

```kotlin
import com.jellemax.detour.data.NavAnnouncer
```

`ConvoyLiveClient` (`:73`), `Settings` (`:91`), `DisposableEffect` (`:42`) and `remember` (`:50`)
are already imported.

### Step 8.2 — the voice, the announcer, and the three silences

- [ ] Insert immediately above `fun stopNavigation()` (`:679`):

```kotlin
    // ---- spoken guidance ---------------------------------------------------
    //
    // The phone was the only navigating surface with no voice: the head unit and
    // iOS have spoken turns since they shipped, while Settings.voiceGuidance had
    // three consumers and two voices. Register decision 1, full parity.
    //
    // Declared up here rather than beside the nav loop because four call sites
    // below need it — stopNavigation, startNavigation, the camera collector and
    // the nav loop — and Kotlin resolves local declarations in order.
    val navVoice = remember { NavVoice(context) }
    DisposableEffect(Unit) {
        onDispose {
            // Not stop(): the engine connection and any held focus request
            // outlive the composition otherwise. The car does the same in its
            // onDestroy (car/NavScreen.kt:199-202).
            navVoice.shutdown()
        }
    }
    val announcer = remember { NavAnnouncer() }

    // Muting has to cut the sentence already in flight, which is what the car's
    // speaker button does (car/NavScreen.kt:479-480). A raw collect and not
    // collectAsStateWithLifecycle: a mute has to land while the app is in the
    // background, which is exactly where the lifecycle-aware copy stops
    // updating.
    LaunchedEffect(Unit) {
        Settings.voiceGuidance.collect { on -> if (!on) navVoice.stop() }
    }

    fun announceAloud(text: String) {
        // Read off the StateFlows rather than the composed state: the camera
        // warning's collector runs while the app is backgrounded, and the
        // composed copies do not update there.
        if (!Settings.voiceGuidance.value) return
        // A live convoy owns the output. ConvoyLiveService takes
        // AUDIOFOCUS_GAIN_TRANSIENT for the whole convoy and registers no
        // focus-change listener (convoy/ConvoyLiveService.kt:172-183), and puts
        // the device into MODE_IN_COMMUNICATION routed to the speaker (:129,
        // :149-161) — so a guidance prompt would not duck anything, it would
        // talk over the riders you are talking to, through a route nobody has
        // measured. activeConvoyId is the closest observable to "the service is
        // running"; FriendsScreen.kt:681 records that the two are not exactly
        // the same thing.
        if (ConvoyLiveClient.activeConvoyId.value != null) return
        navVoice.speak(text)
    }
```

### Step 8.3 — announce from the nav loop

- [ ] In the navigating `LaunchedEffect` (`:1054-1105`), after
      `BleNavServer.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)` (`:1066`):

```kotlin
        // Same policy the head unit and iOS read, so the three surfaces cannot
        // word one maneuver three ways.
        announcer.onProgress(progress.nextInstruction, progress.distanceToTurnMeters)
            ?.let { announceAloud(it) }
```

- [ ] In the `NavPolicy.Decision.Reroute` branch, replace `lastRerouteMs = now` (`:1089`) with:

```kotlin
                lastRerouteMs = now
                announceAloud(announcer.rerouting())
```

- [ ] In the same branch's `scope.launch { try { … } }`, immediately after the
      `route = withContext(Dispatchers.IO) { … }` assignment closes (`:1095`):

```kotlin
                        // Instruction indices belong to the old polyline; start
                        // the new line's prompts from scratch.
                        announcer.routeChanged()
```

- [ ] In `startNavigation()` (`:691-725`), after `error = null` (`:700`):

```kotlin
        // A fresh session hears its first turn immediately, whatever the
        // distance — the same rule the car has, and the reason it exists is
        // that silence after pressing Start is indistinguishable from a broken
        // voice.
        announcer.routeChanged()
```

- [ ] In `stopNavigation()` (`:679-689`), after `navProgress = null` (`:681`):

```kotlin
        // Arrival, or the Exit button. Either way stop mid-sentence rather than
        // finishing a prompt for a turn that no longer matters.
        navVoice.stop()
```

Every line number in this step is the **pre-edit** one. Step 8.2 inserts ~40 lines at `:679`, so
`stopNavigation` and everything below it shifts by that much — locate each site by its code, not by
its line, and let the compiler settle the declaration order rather than reading for it.

### Step 8.4 — the settings description stops being false

- [ ] Replace `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:307-308`:

```kotlin
                    "Turn instructions read aloud while navigating, here and on " +
                        "the car screen. Mutable mid-drive from the speaker button there.",
```

The old text said *"on the car screen"*, which was honest until this commit and false after it.
Spec Scope puts this edit in **this** commit, not a later cleanup, for the reason the register
makes about `README.md:383-385` in entry 1.

### Step 8.5 — verify

- [ ] Greps:

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
grep -c 'NavVoice' $M                                # expect 2 — import + remember
grep -c 'NavAnnouncer' $M                            # expect 2
grep -c 'announceAloud' $M                           # expect 4 — decl + turn + reroute + stop? see below
grep -c 'navVoice.shutdown()' $M                     # expect 1
grep -c 'navVoice.stop()' $M                         # expect 2 — the mute collector and stopNavigation
grep -c 'activeConvoyId.value != null' $M            # expect 1
grep -c 'lastFix' $M                                 # expect 6 — unchanged
grep -c 'rememberUpdatedState' $M                    # expect 9 — must not drop (§2 of the hazards skill)
grep -c '+ 3.0' $M                                   # expect 1 — entry 13 untouched
grep -c '45.0' $M                                    # expect 1 — entry 13 untouched
grep -c 'toneGen?.startTone' $M                      # expect 1 — Task 9 has not run yet
grep -c 'on the car screen' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt  # expect 1
```

  **That last expectation was `0` as written and it was wrong** — see the corrected Done Criterion.
  `:431` (the position marker: *"on the phone map and on the car screen"*) is a different setting
  and is still true after this stage. Only `:307` changes; the surviving hit is `:431`.

  `announceAloud`'s count depends on how many call sites land; assert the *declaration* plus the
  turn prompt plus the reroute cue as a minimum of 3, and read the diff for the rest rather than
  pinning a number this plan cannot know in advance.

- [ ] Tier 0 plus both variants:

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh <task-7-sha> \
    app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:compileDebugKotlin :app:assembleDebug :app:assembleRelease
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```

- [ ] **Replay, at a desk** (`detour-gps-replay`). Route (ii) drives the phone through the mock
      provider and *will* produce turn announcements, so the ladder firing at the right distances is
      checkable here — read the `DetourNavVoice` log and the TTS engine's own logging with
      `adb logcat`. What replay **cannot** answer is anything about sound: whether the voice is
      audible, whether music ducks, whether focus comes back. Those are **Needs a human**.

### Step 8.6 — commit

```
feat(app): spoken turn instructions on the phone

Settings.voiceGuidance had three consumers and two voices: the head unit and
iOS spoke, and the phone — the app's primary configuration, on a bar mount,
with earplugs in — said nothing while showing a switch that read as if it
should. Register decision 1, full parity.

The policy is :shared's NavAnnouncer, so the three surfaces cannot word one
maneuver three ways, and delivery is NavVoice, moved out of car/ two commits
ago. Three silences, each with its reasoning beside it: the setting off (and
turning it off cuts the sentence in flight, as the car's speaker button does),
the audio focus request refused (previous commit), and a live convoy —
ConvoyLiveService holds GAIN_TRANSIENT and puts the device into
MODE_IN_COMMUNICATION for the whole convoy, so a prompt would not duck the
convoy, it would talk over it.

shutdown() in onDispose, not stop(): the engine connection and any held focus
request outlive the composition otherwise.

Known limitation, recorded rather than papered over: liveFix is
collectAsStateWithLifecycle, so turn prompts stop while the app is backgrounded.
Making them survive a dark screen means moving the announcer into
TripTrackingService, which is a state-ownership change and not this commit's.
No seventh lastFix collector was added to fake it.

SettingsScreen's description said "on the car screen", which this commit makes
false, so it changes here rather than in a later cleanup.

Verified: tier0-greps, compileDebugKotlin, assembleDebug, assembleRelease,
:app: and :shared: unit tests, six lastFix collectors and nine
rememberUpdatedState unchanged, entry 13's +3.0 and 45.0 untouched.

Not verified: any of the audio. Nothing here can hear whether the voice is
audible, whether music ducks, or whether focus is released. See the plan's
"Needs a human".
```

---

## Task 9: the phone speaks the speed-camera warning

Entry 15's other half. Its own commit and not Task 8's, because it changes the **second** of two
`lastFix` consumers and `detour-compose-state-hazards` §4 forbids changing two in one commit.

Verified at `92823ed`: the phone's warning block is `MapScreen.kt:871-874` — a `toneGen` call and a
latch assignment, nothing else — while the car speaks and toasts at `:427-430` under a comment
arguing exactly why (*"only the spoken one reaches a driver who is looking at the road with the
radio on"*).

**Chime and speak; no toast.** The car's toast substitutes for a visual the head unit lacks. The
phone's map already draws the camera marker (`:796-798`), so the signal is on screen already.
Note that a snackbar would be *cheap* — `MapScreen.kt` already hosts one (`:171`, `:1255`) and
uses it for `error` (`:173`) — so this is a choice, not a cost: that host is the error channel, and
routing a routine hazard cue through it would train the rider to ignore errors.

**The threshold interlock** (spec D7). The cue goes **inside** the existing
`if (tooFast && ahead.at != warnedAt)` block. `+3.0` (`:870`) and the `45.0` wedge (`:863`) are
stage 3's `CameraWarner` and are not read, not moved and not re-declared here. Step 9.2's greps are
the interlock.

**The wording.** If stage 3 has landed and `CameraWarner` already owns the warning text, take it
from there and declare nothing. If it has not — which is the case as of `92823ed` — leave the
literal at the delivery site with a comment naming `CameraWarner` as its home, exactly as the
spec's Out of scope section instructs for the delivery-site question.

### Step 9.1 — the cue

- [ ] Replace `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:871-874` with:

```kotlin
            if (tooFast && ahead.at != warnedAt) {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                // A TONE_PROP_BEEP2 on the notification stream is inaudible on
                // a bar mount with earplugs in and wind noise — which is this
                // app's primary configuration. The head unit has spoken this
                // since it shipped and its comment says why
                // (car/NavScreen.kt:424-426). Register entry 15.
                //
                // No toast: the car's stands in for a visual the head unit has
                // no room for, and the phone's map already draws the camera
                // marker. The snackbar host two hundred lines up is the error
                // channel; routing a routine hazard through it would teach the
                // rider to ignore errors.
                //
                // The wording is a literal here and not in :shared because
                // stage 3's CameraWarner is where the warning decision and its
                // text belong; whichever of the two lands first declares it,
                // and neither writes a second copy.
                announceAloud("Speed camera ahead")
                warnedAt = ahead.at
            }
```

### Step 9.2 — the interlock, and everything this must not touch

- [ ] Entry 13's thresholds are untouched on both surfaces:

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
CAR=app/src/main/java/com/jellemax/detour/car
grep -c 'fix.speedMps \* 3.6 > limit + 3.0' $M              # expect 1
grep -c 'withinWedge(pos, cam.at, heading, 45.0)' $M        # expect 1
grep -c 'currentSpeedKmh > limit + 3.0' $CAR/NavScreen.kt   # expect 1
grep -c '45.0' $CAR/NavScreen.kt                            # expect 1
grep -c 'limitKmh + 5' app/src/main/java/com/jellemax/detour/ui/MapHud.kt  # expect 1
grep -rc 'CameraWarner' shared/src/commonMain app/src/main/java 2>/dev/null | grep -v ':0' | wc -l
                                                            # expect 0 — stage 3 has not landed
```

  Any of those moving means this commit absorbed entry 13, which belongs to stage 3.

- [ ] The delivery site itself:

```sh
grep -c 'announceAloud("Speed camera ahead")' $M   # expect 1
grep -c 'toneGen?.startTone' $M                    # expect 1 — the chime stays
grep -c 'showSnackbar' $M                          # expect 1 — the error channel, unchanged
grep -c 'Snackbar' $M                              # expect 5 — no new visual
grep -c 'lastFix' $M                               # expect 6 — unchanged
```

- [ ] Build and replay:

```sh
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:compileDebugKotlin :app:assembleDebug :app:assembleRelease
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
    ./gradlew :app:testDebugUnitTest
```

- [ ] Replay needs a route that passes a real camera while over the limit, which route (ii) was cut
      for the speed-limit machine rather than for this. If no existing route triggers the warning,
      **say so** and leave it to the device session rather than claiming a replay pass; the latch
      itself is unchanged code and the only new behaviour is one extra call inside it.

### Step 9.3 — commit

```
feat(app): speak the speed-camera warning on the phone

The head unit chimes, speaks and toasts; the phone chimed. A TONE_PROP_BEEP2 on
the notification stream is inaudible on a bar mount with earplugs in and wind
noise, which is this app's primary configuration — and the car's own comment
says exactly that. Register entry 15, decided as full parity.

Chime and speak, no toast: the car's toast stands in for a visual the head unit
has no room for, and the phone's map already draws the camera marker.

Nothing about *when* to warn changed. The cue goes inside the existing
one-chime-per-camera latch; +3.0 and the 45.0 wedge are entry 13's and belong to
stage 3's CameraWarner, and this commit neither reads nor moves them — greps in
the plan prove it. The warning text stays a literal at the delivery site with a
comment naming CameraWarner as its home, so whichever of the two lands first
declares it and neither writes a second copy.

Its own commit and not part of the turn-announcement one: they are two different
lastFix consumers, and changing two in a commit means one revert cannot separate
them.

Verified: compileDebugKotlin, assembleDebug, assembleRelease, :app: unit tests,
six lastFix collectors unchanged, entry 13's five literals unchanged.

Not verified: whether it is audible over wind, and whether ducking a rider's
music for a camera warning is the right trade. That was the argument against
decision 1 and only a ride settles it.
```

---

## Task 10: the register bookkeeping (runs last)

**No code.** §D's rule: a resolved entry is *"marked resolved with the commit that resolved it"*
and *which way it went*. A commit cannot cite its own SHA, so this collects Tasks 1–9.

### Step 10.1 — entry 12

- [ ] After entry 12's `**Recommendation: needs-a-human on whether the phone speaks…**` paragraph
      (`15-divergence-register.md:1144-1148`), add:

```markdown
**RESOLVED — `<task-3-sha>` (policy), `<task-4-sha>` (car), `<task-5-sha>` (iOS),
`<task-8-sha>` (phone), in favour of decision 1's full parity.** The ladder,
`spokenDistance` and the phase latch are now `NavAnnouncer` in
`shared/…/data/NavAnnouncer.kt` with ten `commonTest` cases, and all three navigating
surfaces call it. The two sub-bugs went the way this entry recommended: the boundary is
inclusive, the car's (`<task-1-sha>`), and iOS's mute cuts the utterance in flight
(`<task-2-sha>`).

**Two things this entry did not know.** First, the announcement policy needs **no clock** — the
latch is path-dependent over the distance sequence, not over time, and neither copy read a
timestamp. Second, the phone's second audio client is not the user's music but Detour's own
convoy service, which holds `AUDIOFOCUS_GAIN_TRANSIENT` and forces `MODE_IN_COMMUNICATION` for
the life of a convoy (`convoy/ConvoyLiveService.kt:129,172-183`). The phone therefore does not
speak while a convoy is live, which is a stricter rule than this entry's *"needs `NavVoice`'s
audio-focus handling ported"* implied.

**Still open, and recorded rather than fixed:** the phone announces turns only while the app is
foregrounded, because `liveFix` is `collectAsStateWithLifecycle` (`MapScreen.kt:201`). Moving the
announcer into `TripTrackingService` is a state-ownership change and belongs to stage 4.
```

### Step 10.2 — entry 15

- [ ] After entry 15's `**Recommendation: needs-a-human.**` paragraph (`:1305-1307`), add:

```markdown
**RESOLVED — `<task-9-sha>`, decision 1's full parity.** The phone now chimes *and* speaks.
No toast: the car's stands in for a visual the head unit has no room for, and the phone's map
already draws the camera marker. Nothing about *when* to warn moved — `+3.0` and the `45.0`
wedge are entry 13's and stay stage 3's `CameraWarner`, and the warning wording is still a
literal at the delivery site so whichever of the two lands first declares it.

The **UNVERIFIED** half of this entry stands: whether a spoken warning is audible over wind, and
whether ducking a rider's music for a camera the app may be wrong about is the right trade, was
the argument against decision 1 and is not settled by these commits.
```

### Step 10.3 — the §A rows

- [ ] `:1737` (entry 15) and `:1740` (entry 12): mark them resolved in the Verdict column with the
      SHAs, leaving entry 12's foregrounded-only limitation visible. Entry 13's row (`:1754`) is
      **not** touched — nothing in this stage resolved it.

### Step 10.4 — the §D assertions this stage inverts

- [ ] Entry 12 has no §D assertion today (the fence at `:1979-2016` covers entries 1, 3, 4, 6a, 16,
      8 and 10). Add three, in the fence's existing style — two of them inverted, which is the kind
      §D says earns a script:

```sh
# Entry 12 — RESOLVED. The policy exists once and all three surfaces call it.
check 'the announce ladder lives only in :shared' 1 \
    "$(grep -rlc 'FAR_METERS = 800.0' shared/src/commonMain | wc -l)"
check 'no surface kept its own ladder' 0 \
    "$(grep -c 'VOICE_FAR_M\|voiceFarM' $CAR/NavScreen.kt iosApp/Detour/NavScreen.swift | grep -c ':[1-9]')"
# Entry 15 — RESOLVED. Inverted on purpose: 0 means the phone went quiet again.
check 'the phone speaks the camera warning' 1 \
    "$(grep -c 'announceAloud("Speed camera ahead")' "$M")"
```

- [ ] Update the sentence at `:1975-1978` that dates the fence's expectations to `7c96bee` — three
      of its assertions are now measured against this stage instead.

### Step 10.5 — the Status blocks and the axis note

- [ ] `specs/convergence-3-voice-policy.md`'s Status `State` row: **done**, the date, the nine
      code SHAs and a link to this plan. If any of Tasks 6–9 was skipped at the stop-point, write
      **partially done** with the item named — `detour-staged-refactor` §6, which this project got
      wrong twice in both directions.
- [ ] `specs/00-chain-design.md`: add the note that the convergence axis is complete as the
      register defined it, with §A's four small entries (14, 18, 19, 21) left as one-line answers
      in the register rather than a fourth spec. The spec's *Next stage* section asks for exactly
      this and no more.
- [ ] Do **not** add a stop-point sentence to `DECISION.md` — that is the structure axis' roadmap.
      The convergence axis' roadmap is the register's §C.1, whose item 6 this closes.

### Step 10.6 — commit

```
docs(refactor): resolve register entries 12 and 15

Entry 12 resolved as decision 1's full parity: NavAnnouncer in commonMain with
ten commonTest cases, the car and iOS repointed, the phone given a voice. Its
two sub-bugs went the way it recommended — inclusive boundaries and a mute that
cuts the utterance in flight.

Entry 15 resolved the same way: the phone chimes and speaks, and no toast,
because its map already draws the camera.

Records two things neither entry knew. The announcement policy needs no clock —
the latch is path-dependent over distance, not time, and the spec's own rewrite
marker asked for a nowMs parameter that nothing would read. And the phone's
second audio client is not the user's music but Detour's convoy service, which
holds GAIN_TRANSIENT and forces MODE_IN_COMMUNICATION for the whole convoy; the
phone therefore stays silent on a convoy, which is stricter than entry 12
implied.

Leaves one thing open and named: the phone announces turns only while
foregrounded, because liveFix is collectAsStateWithLifecycle. That is a
state-ownership change and belongs to stage 4.

Adds three §D assertions for entries 12 and 15, two of them inverted, and drops
the claim that the whole fence was measured at 7c96bee. Entry 13's row is not
touched — nothing here resolved it.

Closes §C.1 item 6, the last item on the register's order of work. §A's four
small entries (14, 18, 19, 21) stay one-line answers in the register rather
than a fourth spec.
```

---

## Done Criteria

- [ ] `NavAnnouncer` exists once, in `shared/src/commonMain/…/data/`, with `commonTest` coverage
      including the 800/300/80 boundary, and **all three** navigating surfaces call it. `grep -c
      'TextToSpeech\|AVSpeech\|ToneGenerator\|CarToast\|android\.\|java\.'` over the new file is 0.
- [ ] `grep -c 'VOICE_FAR_M' app/…/car/NavScreen.kt` is 0 and `grep -c 'voiceFarM'
      iosApp/Detour/NavScreen.swift` is 0.
- [ ] The phone announces turns and the camera warning, honours `Settings.voiceGuidance` including
      mid-drive, and stays silent on refused focus and on a live convoy.
- [x] ~~`grep -c 'on the car screen' app/…/ui/SettingsScreen.kt` is 0.~~ **Corrected 2026-08-12
      while executing item 8: this criterion was wrong and driving it to 0 would have deleted a
      true sentence.** There are **two** occurrences and only one belongs to item 8:
      `:307` — *"Turn instructions read aloud on the car screen"*, the voice setting's description,
      which item 8 makes false; and `:431` — *"Drawn where you are, on the phone map and on the car
      screen"*, the position-marker setting, which is true before and after this stage and is
      untouched. The criterion is: **`:307` no longer says the setting is car-only, and
      `grep -c 'on the car screen'` is 1, not 0** — the surviving hit being `:431`. Both lines were
      read before either was edited.
- [ ] `grep -c 'voice.stop()' iosApp/Detour/NavScreen.swift` is 2 and
      `grep -c 'case ..<Self.voiceNowM'` is 0.
- [ ] **Entry 13 untouched:** `+3.0` still 1 in `MapScreen.kt` and 1 in `car/NavScreen.kt`, `45.0`
      still 1 in each, `+5` still 1 in each of `MapHud.kt`, `car/CarMapRenderer.kt` and
      `wear/…/MainActivity.kt`. Nothing in this stage declared or moved one of them.
- [ ] `grep -c 'lastFix' app/…/ui/MapScreen.kt` is 6 and `grep -c 'rememberUpdatedState'` is 9 —
      neither dropped (`detour-compose-state-hazards` §2, §4).
- [ ] `FlowWatcher.kt` still has nine concrete watchers. No tenth was added.
- [ ] Register entries 12 and 15 marked **resolved** with their commits and which way each went;
      three §D assertions added; `00-chain-design.md` records the axis as complete.
- [ ] **Exactly ten commits**, none combining two items, none combining the extraction with either
      iOS sub-bug fix, none combining the `NavVoice` move with the focus change, and none combining
      Tasks 8 and 9.

## Verification

Per work item, and per what this machine can actually do.

| # | Surface | Verifiable here | Needs a Mac | Needs a device / a person |
|---|---|---|:-:|:-:|
| 1 | iOS | 5 greps | compile | — (behaviour is measure-zero) |
| 2 | iOS | 5 greps | compile | mute mid-drive |
| 3 | `shared/` | `compileCommonMainKotlinMetadata`, 10 `commonTest` cases on JVM, 6 greps | — | — |
| 4 | Android Auto | tier0-greps, compile, both assembles, unit tests, 12 greps | — | DHU with audio |
| 5 | iOS | 8 greps | compile | a nav session |
| 6 | phone + car | rename + body diff, compile, both assembles | — | — |
| 7 | phone + car | compile, both assembles, 5 greps | — | **yes** — a call, an assistant |
| 8 | phone | tier0-greps, compile, both assembles, unit tests, replay route (ii) for the ladder, 11 greps | — | **yes** — all the audio |
| 9 | phone | compile, both assembles, 10 greps | — | **yes** — a camera, over the limit |
| 10 | docs | the spec's whole precondition fence, re-run | — | — |

**Where CI gates what.** `ios.yml` runs `:shared:compileCommonMainKotlinMetadata` (`:57-59`),
`:shared:testDebugUnitTest` (`:64-65`) and `:shared:iosSimulatorArm64Test` (`:67-68`), so Task 3's
tests run on **both** JVM and Kotlin/Native — the Native pass is what proves the policy behaves the
same compiled for the device, and it is the only place Task 5's Kotlin/Native API spelling is
checked at all. Its `push:` trigger is `branches: [main, ios]` (`:9-10`), so a push to
`refactor/mapscreen-split` does **not** fire it; the `pull_request:` trigger is path-gated on
`shared/**` / `iosApp/**` and not branch-gated (`:17-20`). **PR #1 is open on `Yimura/Detour` for
this branch and `ios.yml` has run on it three times, all green** — verified with
`gh run list --workflow ios.yml`. It is a draft PR and the workflow has no draft gate, so pushing is
enough. `build.yml` is not path-gated and runs `:app:testDebugUnitTest :shared:testDebugUnitTest`
on every pull request (`:117-118`).

**What CI does not do.** `ios.yml:187-200` boots a simulator, pre-grants **location only** (`:197`)
and screenshots the first screen. A green run means the Swift compiles and the shared tests pass.
**Nothing anywhere in this repository can verify that speech sounds right, that ducking behaves,
or that audio focus is released.** There is no Robolectric, no `androidTest` source set, no iOS
test target, and no audio assertion of any kind. Every claim about how this *sounds* comes from the
**Needs a human** section below or it does not exist.

**What replay can and cannot do.** `detour-gps-replay` drives the Android app through the mock
location provider, so route (ii) will make the phone reach the announcement thresholds and Task 8's
ladder firing is checkable at a desk from `adb logcat`. Replay cannot judge audibility, ducking,
focus release, or routing to a headset. It also has no path into `iosApp/`, so Tasks 1, 2 and 5 have
no desk-runnable behavioural check at all.

**Tiers.** `detour-staged-refactor` §5 puts a navigation session at **Tier 3**. Tasks 4, 8 and 9
are navigation sessions with audio, so none of them qualifies for a desk-only checklist no matter
how many greps pass. Task 3 is the one genuinely cheap item and is also the one most likely to be
assumed rather than tested — run the tests, read the failures.

### Greps in this plan that count this plan's own comments

Recorded 2026-08-12 while executing items 6–9, and left in rather than silently edited, because the
pattern repeats and the next plan will do it again: **four `grep -c … # expect 0` checks are
defeated by the very comment text the plan dictates two steps earlier.** The intent of each holds;
the number does not.

| Step | Grep | Expected | Actual | Why |
|---|---|---|:-:|---|
| 6.3 | `androidx.car` in `audio/NavVoice.kt` | 0 | **1** | Step 6.1's new KDoc says *"never depended on a single `androidx.car` type"*. `grep -c '^import androidx.car'` is 0, which is the real check. |
| 7.3 | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | 1 | **2** | Pre-existing, not caused by this plan: a KDoc `[AudioManager.…]` link plus the code. It was 2 at `92823ed` too. |
| 9.2 | `CameraWarner` in `shared/commonMain` + `app/` | 0 | **1** | Step 9.1's dictated comment names `CameraWarner` as the wording's home. Stage 3 genuinely has not landed. |
| 8.5 / Done | `on the car screen` in `SettingsScreen.kt` | 0 | **1** | Two occurrences, one true — corrected above. |

Batch 1 hit the same class twice in Step 3.3 (`TextToSpeech\|…\|java\.` and `nowMs\|Clock`, both
matched by `NavAnnouncer.kt`'s KDoc). **Write the assertion against code, not against text:** anchor
on `^import`, or strip comments first. One stale citation, too — Step 9.1's comment pointed at
`car/NavScreen.kt:424-426` for the car's speak-the-camera argument; item 4 (`1e3cab3`) deleted ~30
lines from that file, so it is `:392-394` and the landed comment says so.

## Needs a human

Six things, none optional if the corresponding commit is to be called verified. Until each is done
the commit stands as **unverified** in the Status block — do not substitute a code read
(`detour-staged-refactor` §5).

1. **iPhone, Task 2 — the mute.** Start navigation, wait for a long prompt, and switch spoken
   guidance off from Settings while it is speaking. The sentence must cut, not finish. Then switch
   it back on and confirm the next prompt arrives.
2. **iPhone, Task 5 — a nav session.** Drive or walk a short route and confirm the three prompts
   still arrive at roughly 800 / 300 / 80 m and read the same words as before. This is the only
   check that the Kotlin/Native call actually returns what the Swift expects at runtime, as opposed
   to type-checking.
3. **Android phone, Task 7 — the refused focus request.** Start navigation, then take a phone call,
   and confirm no guidance prompt lands on top of the call. `adb logcat -s DetourNavVoice` should
   show *"audio focus refused; guidance prompt dropped"*. **This is the check with the widest gap
   between "compiles" and "works"**, and it is also the one that could silently break guidance if
   the device refuses focus more often than expected — so confirm prompts resume after the call.
4. **Android phone, Task 8 — the ducking.** Play music, start navigation, and confirm: the prompt is
   audible over it, the music ducks rather than pausing, and it comes back up **between** prompts
   rather than staying ducked for the whole drive. Then confirm the same over a Bluetooth helmet
   intercom, which routes differently. This is the objection the register raised against decision 1
   and nothing short of listening answers it.
5. **Android phone, Task 8 — the convoy silence and the focus release.** Join a convoy with a second
   peer, start navigation, and confirm no guidance prompt is spoken while the convoy is live and
   that push-to-talk is unaffected. Then leave the convoy and confirm prompts resume. Separately:
   navigate, exit, and confirm music returns to full volume — a leaked focus request shows up as
   permanently quiet music and nothing in this repo can detect it.
6. **Android phone, Task 9 — the camera warning.** Ride past a mapped speed camera at more than
   3 km/h over the posted limit and confirm the chime **and** the spoken cue, once, and that it
   re-arms for the next camera. The judgement to make while there: whether ducking music for this
   is worth it. If it is not, the honest outcome is to narrow the warning, not to delete the
   speech — and that is a register decision, not a plan edit.

**Head unit or DHU, Task 4** is a seventh if a unit is available: confirm the car's prompts are
unchanged after the repoint. It is the lowest-risk item here (the extracted code is the car's own,
under test) which is exactly why it is easiest to skip and worth naming.

### Added 2026-08-12, while executing items 6–10

**Items 6–9 landed without the device session the stop-point demands.** That was a deliberate
call, recorded in the spec's Status as *shipped, not verified* rather than papered over — but it
means the six checks above are not a formality, they are the whole of items 7, 8 and 9's
verification. Three more came out of writing the code, and none of them existed when the list was
first drafted:

8. **Android phone, Task 8 — the Hub round trip tears the engine down and rebuilds it.**
   `navVoice` is `remember`ed in `MapScreen`'s composition and `AppRoot` swaps screens with a bare
   `AnimatedContent` and no `rememberSaveableStateHolder`
   (`detour-compose-state-hazards` §5), so leaving the map for the Hub disposes the composition and
   runs `onDispose { navVoice.shutdown() }` — a full `TextToSpeech.shutdown()`, not a pause. Coming
   back constructs a second engine. **Confirm that guidance still speaks after a Hub visit, and
   that it speaks after several**, because an OEM engine that does not survive repeated
   create/shutdown cycles fails silently and looks exactly like the setting being off. Note while
   there that `navigating` is plain `remember` (`MapScreen.kt:230`), so the Hub visit already ends
   the nav session — that part is pre-existing and stage 4's, not this stage's.
9. **Android phone, Task 8 — the focus release on a screen swap, not just on Exit.**
   `stopNavigation()` is *not* called when the composition is disposed; the only thing that hands
   the focus back on that path is `onDispose`. Navigate with music playing, go to the Hub **without**
   pressing Exit, and confirm the music returns to full volume. This is the one leak path the Exit
   button does not cover and it is invisible from here.
10. **Android phone, Task 8 — `activeConvoyId` is a proxy, and the plan says so in a comment.**
    `FriendsScreen.kt:678-683` records that `activeConvoyId` and *"`ConvoyLiveService` is actually
    running"* are not exactly the same thing. Check both edges: a convoy joined but the live
    service stopped (*does the phone speak again?* — it should), and the service running with
    `activeConvoyId` momentarily null during connect/reconnect (*does one prompt get through?*).
    If the proxy is wrong in the second direction, the fix is a new observable on
    `ConvoyLiveClient`, which is a §B-bug-shaped change and not a plan edit.

**Nothing in items 6–9 was heard.** The honest summary of this batch: the Kotlin compiles, R8 is
happy, both unit suites pass, CI is green, and every grep in the plan that is about *code* holds.
Not one claim about *sound* — audible, ducked, released, silent-on-convoy — has any evidence behind
it, and none is obtainable in this repository.

## Next

None. This closes §C.1 item 6, the last item on the register's order of work, and with it the
convergence axis as the register defined it. §A's four remaining *needs-a-human* entries — 14
(the watch's discarded instruction text), 18 (the HUD at a standstill), 19 (distance quantisation)
and 21 (catch-up order) — are four one-line answers and belong **in the register**, not in a fourth
spec. The register's own warning applies to any successor: *"If this list grows past six, the
register has stopped working."*

The structure axis is unaffected and continues from
[`../specs/stage-3-hazard-machines-to-shared.md`](../specs/stage-3-hazard-machines-to-shared.md),
still blocked on a `trajectcontrole.txt` re-run once Overpass answers. If `CameraWarner` lands after
this stage, its one interaction with this work is the `"Speed camera ahead"` wording: it consumes
the literal at `MapScreen.kt`'s delivery site rather than declaring a second copy (Task 9).
