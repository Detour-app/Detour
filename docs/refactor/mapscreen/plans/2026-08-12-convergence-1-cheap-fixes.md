# Convergence 1 — the three cheap cross-surface fixes: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three cheapest cross-surface divergences the register found — the missing iOS
microphone permission, the head unit's absent off-route indicator, and iOS's `1.0 m/s` "stopped"
threshold — plus the bookkeeping for a fourth that has already landed. Four commits, three
surfaces, no shared symbol between them.

**Shape:** This is not a refactor. Two of the four items change behaviour on purpose (the car
indicator, the iOS threshold), one adds a permission gate that today does not exist, and one is
documentation. Nothing here moves a symbol, so none of the extraction rules apply and none of the
usual proofs (zero-added-lines, `git log -C`) are available or relevant. What *is* binding is the
one-item-one-commit rule and the honesty about verification: three of the four commits cannot be
compiled on this machine.

**Tech stack per item:** Swift 5.9 / iOS 17.0 deployment target (`iosApp/project.yml:17`) ·
XML property list · Kotlin with `androidx.car.app:app:1.7.0` (`app/build.gradle.kts:148`) ·
Markdown.

**Spec:** [`../specs/convergence-1-cheap-fixes.md`](../specs/convergence-1-cheap-fixes.md) — its
Scope, Out of scope and Work items are binding. **Its 13 preconditions were re-run against the
working tree at `5c4b8a3` and all 13 pass**, including the two inverted ones the spec flags as
having come back contradicting the register.

**Register:** [`../15-divergence-register.md`](../15-divergence-register.md) — entry 16 / §B5
(item 1), entry 8 + §C decision 4 (items 2 and 3), entry 5d + §C decision 3 (item 4). This plan
cites those entries; it does not restate their arguments.

**Every line number below was derived with `grep -n` against the tree at `5c4b8a3` on
`refactor/mapscreen-split`, with a clean working tree** (only `.devcontainer/` untracked). Three
documents in this chain have carried wrong citations and register entry 8 was false in the commit
that created it — re-derive before trusting any number here if anything has landed since.

## Global Constraints

- **Commit messages:** Conventional Commits. **No `Co-Authored-By` trailer. No
  `Claude-Session` trailer. No trailers of any kind.**
- **One work item, one commit.** Four work items, four commits. `DECISION.md:394-400` and
  `detour-staged-refactor` §4 are binding; the row that bites here is *"an extraction **and** the
  bug it reveals"*, generalised by the spec's Out of scope section into: item 1 must not carry
  either of the other two §B5 fixes.
- **The other two §B5 fixes are out of scope.** The spec puts them under *Out of scope*
  explicitly: the `sendPttStart()` ordering (`ConvoyBar.swift:71-73`) and the socket-down
  visibility gate (`ConvoyBar.swift:16`). They are **not** planned here, not as extra commits and
  not as part of item 1. Task 1 below carries two greps whose job is to prove both are still
  present and untouched after it lands.
- **§B2 is out of scope too.** Item 4 edits `TripRecorder.swift:280`; `:282` is the missing
  `autoStarted` gate in the same `if`/`else if`. Do not add it. Task 4 carries the grep that
  proves the `else if` is unchanged.
- **Read `detour-staged-refactor` before the first commit** (commit rules §4, verification tiers
  §5, the bookkeeping this project skipped twice §6) and **`detour-shared-core`** before deciding
  that any of these constants should move rather than be duplicated. This plan does not restate
  either. Two conclusions from the second one are load-bearing and are used below: `iosApp/` has
  no test target at all, and `ios.yml` is the only workflow that touches Swift.
- **No new constants, on any surface.** The car reads `NavPolicy.OFF_ROUTE_METERS` — the whole
  point of entry 8 is that this bound is named once (spec § *3. Car*). iOS's `2.0` stays a
  literal, not a `private static let`, because the spec's own done criterion greps for
  `if speed > 2.0` (spec `:172`).
- **No third drawing surface on the car.** Item 3 uses the `NavigationTemplate` path, one of the
  two the spec permits. Not a `CarToast` (`NavScreen.kt:177`, `:422` — transient is the defect),
  not `CarMapRenderer`'s HUD, and not the instrument cluster.
- **Rationale goes next to the code, not only in the message.** `CONTRIBUTING.md:177-189`. Both
  behaviour changes (items 3 and 4) carry their reasoning in a comment as well as in the commit.
- **Gradle only ever runs in the devcontainer.** The host JDK is 26 with no Android SDK; the
  container is `recursing_volhard`, workdir `/workspaces/Detour`, and the user is numeric:

  ```sh
  docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew <tasks>
  ```

  Never a bare `./gradlew build`.

### Sequencing

The spec calls items 1, 3 and 4 *fully parallel* — three surfaces, three files, no shared symbol.
That holds. This plan adds one ordering constraint the spec does not have:

- **Item 2 runs last.** It is the bookkeeping commit, and §D's rule is that a resolved entry is
  *"marked resolved with the commit that resolved it"*. A commit cannot cite its own SHA, so the
  register edits for items 1, 3 and 4 have to be written after those three exist. Item 2 collects
  all of them, plus entry 8's own already-landed half. That still totals four commits, which is
  what the spec's done criteria require.
- **Item 1 is a prerequisite of [convergence 3](../specs/convergence-3-voice-policy.md)**, not of
  anything in this plan.

Execution order: **1 → 3 → 4 → 2.**

## File Structure

Nothing new is created. Seven files are edited across four commits.

| # | File | Change | Compilable here? |
|---|---|---|:-:|
| 1 | `iosApp/Detour/Info.plist` | `NSMicrophoneUsageDescription` key + string, inserted after `NSMotionUsageDescription` (`:37-38`) | parseable, not compilable |
| 1 | `iosApp/Detour/PttAudio.swift` | new `CapturePermission` enum and `capturePermission()`, inserted above `// MARK: Capture` (`:31`) | **no** |
| 1 | `iosApp/Detour/ConvoyBar.swift` | `micDenied` state (after `:13`), mic glyph (`:50`), permission switch in `startTalking()` (`:69-76`) | **no** |
| 3 | `app/src/main/java/com/jellemax/detour/car/NavScreen.kt` | 3 imports; `offRoute()` helper; `travelEstimate()` gains a defaulted third parameter (`:489-492`); `refreshTemplate` key (`:361-367`); `onGetTemplate` call site (`:452`) | **yes** |
| 4 | `iosApp/Detour/TripRecorder.swift` | `:280` `1.0` → `2.0`, plus the rationale above `:279` | **no** |
| 2 | `docs/refactor/mapscreen/15-divergence-register.md` | entries 8 / 16 / 5d marked resolved; §A rows `:1705`, `:1707`, `:1723`; §B5 `:1795-1801`; two §D assertions `:1968-1969` and `:1971-1973` | n/a |
| 2 | `docs/refactor/mapscreen/specs/convergence-1-cheap-fixes.md` | Status block `:9` | n/a |
| 2 | `docs/refactor/mapscreen/specs/convergence-2-section-readouts.md` | Status: the next spec's precondition result, per `detour-staged-refactor` §6 | n/a |

---

## Task 1: iOS declares and requests the microphone permission

Register entry 16 / §B5. Verified at `5c4b8a3`: `grep -c 'NSMicrophoneUsageDescription'
iosApp/Detour/Info.plist` is **0**, `grep -rl 'requestRecordPermission\|AVAudioApplication'
iosApp/ | wc -l` is **0**, and `PttAudio.swift:41` activates a `.playAndRecord` session.

One detail worth having before the first edit, because it settles whether this is an oversight or
a decision: `Info.plist:45-46` already declares the `audio` background mode with the comment
*"Convoy push-to-talk capture and spoken turn instructions."* The feature's background mode was
declared and its usage description was not. Nobody weighed this.

**The severity stays UNVERIFIED.** Register entry 16 says the crash-versus-silent-failure question
must be confirmed on a device, and building the iOS app locally needs a Mac with Xcode 16
(`CONTRIBUTING.md:9`). Write the commit message against what is verified — the key is absent and
no request is made, so iOS push-to-talk cannot legitimately capture audio — and not against a
termination nobody has reproduced.

### Step 1.1 — declare the usage description

- [ ] In `iosApp/Detour/Info.plist`, insert after the `NSMotionUsageDescription` string on `:38`
      and before the blank line preceding `UIBackgroundModes` on `:40`:

```xml
    <!-- Convoy push-to-talk. The microphone is only ever opened while the mic
         button is held, but PttAudio activates a .playAndRecord session to do
         it, and iOS refuses that session — and terminates the app for
         attempting it — with no usage description declared. The `audio`
         background mode below has named this feature since it shipped; this
         key is the half that was missing. -->
    <key>NSMicrophoneUsageDescription</key>
    <string>Detour uses the microphone for convoy push-to-talk, so you can talk to the riders you are with without taking your gloves off.</string>
```

- [ ] Confirm the file still parses and the key is present *and non-empty* — an empty usage string
      is as fatal on iOS as a missing one, and `plutil` does not exist on Linux:

```sh
python3 - <<'PY'
import plistlib
with open('iosApp/Detour/Info.plist', 'rb') as f:
    d = plistlib.load(f)
k = 'NSMicrophoneUsageDescription'
assert k in d, 'key missing'
assert d[k].strip(), 'key present but empty'
print('OK:', d[k])
PY
xmllint --noout iosApp/Detour/Info.plist && echo "well-formed"
```

### Step 1.2 — a permission check in `PttAudio`

The gate belongs here, not in `ConvoyBar`, because the reason it exists is a property of
`startCapture`: the session `catch` at `PttAudio.swift:44-45`, the converter `guard` at `:50` and
the `engine.start()` `catch` at `:77-78` all return silently. A refusal has to be caught before
capture, not discovered inside it.

- [ ] In `iosApp/Detour/PttAudio.swift`, insert immediately above `// MARK: Capture` (`:31`):

```swift
    // MARK: Permission

    /// Whether capture may start right now.
    enum CapturePermission {
        /// The microphone is ours; start capturing.
        case granted
        /// The system alert has just gone up. It owns the touch, so this press
        /// is spent answering it and the next one transmits.
        case asking
        /// Refused, and only Settings can undo that.
        case denied
    }

    /// The record permission, asking for it the first time it is needed.
    ///
    /// Asked on the press rather than on convoy connect, which is where Android
    /// asks it (`ui/MapScreen.kt:474-478`, then refuses the press again at
    /// `ui/MapHud.kt:135-140`): a rider who never transmits is never prompted,
    /// and on iOS the alert has to be answered before a `.playAndRecord`
    /// session can be activated at all.
    ///
    /// Nothing may call [startCapture] without coming through here. Activating
    /// that session with no answer on record is the documented condition for
    /// iOS to terminate the app, and every failure path inside [startCapture]
    /// returns silently, so a refusal that reaches it is a refusal nobody sees.
    func capturePermission() -> CapturePermission {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return .granted
        case .undetermined:
            // Fire and forget: the answer arrives on another queue and this
            // press is already lost to the alert. The next press reads the
            // recorded answer instead of asking again.
            AVAudioApplication.requestRecordPermission { _ in }
            return .asking
        case .denied:
            return .denied
        @unknown default:
            return .denied
        }
    }
```

- [ ] No import is needed: `AVAudioApplication` is in `AVFoundation`, already imported at `:1`.

**On the API spelling.** `AVAudioApplication` is iOS 17+, and the deployment target is
`iOS: "17.0"` (`iosApp/project.yml:12-17`), so it is available; `AVAudioSession.recordPermission`
and `AVAudioSession.requestRecordPermission(_:)` are the pre-17 spelling and are deprecated as of
17. **This cannot be type-checked on this machine** (Step 1.5). If `ios.yml` rejects the modern
spelling, the deprecated pair is the fallback and still functions — do not silently drop the gate.

### Step 1.3 — gate the press, and say so on the button

- [ ] In `iosApp/Detour/ConvoyBar.swift`, after `@State private var transmitting = false` (`:13`):

```swift
    /// Set when the microphone has been refused, so the button can say so
    /// rather than doing nothing. State and not a computed property: the press
    /// is the only moment the answer is read, and `recordPermission` publishes
    /// nothing to observe.
    @State private var micDenied = false
```

- [ ] Replace the glyph expression on `:50`:

```swift
                Image(systemName: micGlyph)
```

- [ ] Add the glyph, immediately above `private func startTalking()` (`:69`):

```swift
    private var micGlyph: String {
        if micDenied { return "mic.slash" }
        return transmitting ? "mic.fill" : "mic"
    }
```

- [ ] Replace `startTalking()` (`:69-76`) with:

```swift
    private func startTalking() {
        guard !transmitting else { return }
        // The microphone is asked for here, on the press — see
        // PttAudio.capturePermission(). A press spent on the alert, or refused
        // outright, must not open a transmission: every peer would light a
        // "talking" badge for audio that is never coming.
        switch PttAudio.shared.capturePermission() {
        case .granted:
            micDenied = false
        case .asking:
            micDenied = false
            return
        case .denied:
            micDenied = true
            return
        }
        transmitting = true
        ConvoyLiveClient.shared.sendPttStart()
        PttAudio.shared.startCapture { chunk in
            ConvoyLiveClient.shared.sendAudioChunk(chunk)
        }
    }
```

The `mic.slash` glyph is in scope and the other two §B5 fixes are not, because they are three
different conditions: a refused permission (here), a start that failed after the frame went out
(`startCapture`'s silent returns), and a closed socket (`live.connected`). The first is the one
this commit gates, and a press that silently does nothing forever is the same defect the register
filed.

### Step 1.4 — prove the two out-of-scope bugs are still there

- [ ] `sendPttStart()` still immediately precedes `startCapture` — the ordering bug (entry 16,
      second bullet) is untouched:

```sh
grep -A1 'ConvoyLiveClient.shared.sendPttStart()' iosApp/Detour/ConvoyBar.swift \
  | grep -c 'PttAudio.shared.startCapture'          # expect 1
```

- [ ] The socket-down visibility gate is untouched:

```sh
grep -c 'if live.activeConvoyId != nil' iosApp/Detour/ConvoyBar.swift   # expect 1
grep -c 'live.connected' iosApp/Detour/ConvoyBar.swift                  # expect 2 — :19 and :23
```

Both `expect` values are the spec's own precondition values, unchanged. If either moves, this
commit absorbed a fix it was told not to.

- [ ] The gate is actually wired:

```sh
grep -c 'PttAudio.shared.capturePermission()' iosApp/Detour/ConvoyBar.swift  # expect 1
grep -rl 'AVAudioApplication' iosApp/ | wc -l                                # expect 1
```

### Step 1.5 — commit, and be exact about what is verified

- [ ] Commit:

```
fix(ios): declare and request the microphone permission for push-to-talk

iosApp declared no NSMicrophoneUsageDescription while PttAudio activated a
.playAndRecord session, and nothing in iosApp/ ever requested the record
permission. Android pre-requests on convoy connect and refuses the press
without it; iOS asked for nothing, so push-to-talk could not legitimately
capture audio.

Adds the usage description, and asks on the press rather than on connect: a
rider who never transmits is never prompted, and startCapture swallows every
failure it can have, so a refusal has to be caught before capture instead of
discovered inside it. A refused microphone now shows mic.slash instead of a
button that does nothing.

The `audio` background mode has named this feature since it shipped; the usage
description is the half that was missing.

Not verified: the app has not been built or run. There is no Swift toolchain
and no Mac here, and iosApp/ has no test target. Whether the previous state
terminated the app or failed silently is still unconfirmed and is deliberately
not claimed either way.

Register entry 16 / §B5, first of three. The sendPttStart() ordering and the
socket-down visibility gate are untouched and stay open.
```

---

## Task 2: the register bookkeeping (runs last)

Spec item 2, plus the resolutions for items 1, 3 and 4. **No code.** Entry 8's constant half
already landed and this task records that; the spec is right that it is a bookkeeping item, not a
code item.

Verified at `5c4b8a3`:

```sh
grep -c 'NavPolicy.OFF_ROUTE_METERS' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt  # 1
grep -c 'offRouteMeters ?: 0.0) > 60' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt  # 0
```

`MapScreen.kt:1424-1428` reads:

```kotlin
                        BottomCard.NAV -> NavigationBottomBar(
                            progress = navProgress,
                            offRoute = (navProgress?.offRouteMeters ?: 0.0) >
                                NavPolicy.OFF_ROUTE_METERS,
                            onExit = { stopNavigation() },
                        )
```

`git show 7d57087 -- .../ui/MapScreen.kt` confirms it: that commit — `docs(refactor): register
every cross-surface divergence before stage 3`, the commit that *added* the register — changed
exactly that one expression, and its message's last paragraph says so. **So the register's entry 8
asserted a literal that its own commit had just deleted.** Do not re-fix it; record it.

### Step 2.1 — entry 8, the constant half

- [ ] Replace `15-divergence-register.md:815-821` (the `But the banner did not come along.`
      paragraph and the `kotlin` fence quoting the bare `60`) with:

```markdown
But the banner did not come along until `7d57087` folded it in. `app/…/ui/MapScreen.kt:1426-1427`
now reads:

```kotlin
offRoute = (navProgress?.offRouteMeters ?: 0.0) >
    NavPolicy.OFF_ROUTE_METERS,
```

**RESOLVED (constant half) — `7d57087`, in favour of `NavPolicy.OFF_ROUTE_METERS`.** The bare `60`
this entry was written against was already gone when the entry was committed: `7d57087` is the
commit that added this register and it carried the one-line change in its own diff. The prose here
and the §D assertion below both claimed the literal still existed and were false the day they were
written — corrected in place rather than deleted, so the shape of the mistake stays on record.
```

### Step 2.2 — entry 8, the car half

- [ ] After the `**Recommendation: survive …**` paragraph at `:843-844`, add:

```markdown
**RESOLVED (car indicator) — `<task-3-sha>`, decision 4 answered yes.** The head unit's
destination card now turns red and reads "Off route" while `p.offRouteMeters` exceeds
`NavPolicy.OFF_ROUTE_METERS`. Two commits as decision 4 required, and the mechanical half was not
one of them because it had already landed.
```

### Step 2.3 — entry 16 and §B5

- [ ] After entry 16's `**Recommendation: bug — three separate fixes** (§B5)…` paragraph
      (`:1367-1368`), add:

```markdown
**RESOLVED (permission only) — `<task-1-sha>`.** `NSMicrophoneUsageDescription` is declared and
the press path asks for the record permission before capture. **Still open:** the `sendPttStart()`
ordering and the socket-down visibility gate, both deliberately left out of that commit. The
severity of the original state — termination versus silent failure — is still **UNVERIFIED** and
was not confirmed by that commit either: nothing was built or run.
```

- [ ] In §B5 (`:1795-1801`), change *"Three fixes, three commits"* to record that the permission
      landed in `<task-1-sha>` and that two of the three remain open.

### Step 2.4 — entry 5d

- [ ] After entry 5's `**Recommendation: survive — Android, on 5a/5b/5c/5e/5f. …**` paragraph
      (`:577-580`), add:

```markdown
**RESOLVED (5d threshold) — `<task-4-sha>`, §C decision 3, in favour of Android's `2.0 m/s`.**
The rationale is now written beside the constant in `iosApp/Detour/TripRecorder.swift`, which is
what neither surface had. **Still open:** 5d's `autoStarted` gate (§B2), and all of 5a, 5b, 5c, 5e
and 5f.
```

### Step 2.5 — the §D assertions, both of which this stage inverts

This is the step that keeps the §D script from being wrong on the day it is written, which is the
mistake §D itself exists to prevent.

- [ ] `:1968-1969` — after Task 1 the key exists, so an `# expect 0` assertion is now false:

```sh
check 'iOS declares a microphone usage description' 1 \
    "$(grep -c 'NSMicrophoneUsageDescription' iosApp/Detour/Info.plist)"
```

- [ ] `:1971-1973` — the entry-8 assertion, whose expectation the spec already corrected to 0:

```sh
# Entry 8 — RESOLVED by 7d57087. Inverted on purpose: 1 means the literal came back.
check 'the 60 literal is gone' 0 \
    "$(grep -c 'offRouteMeters ?: 0.0) > 60' "$M")"
```

- [ ] Add an assertion for the car half, since the register now claims it:

```sh
check 'the head unit has an off-route indicator' 1 \
    "$(grep -c '"Off route"' $CAR/NavScreen.kt)"
```

- [ ] Update the sentence at `:1942-1943` that says every assertion *"was run against `a0f7f42`
      and produces the count shown"* — it is no longer true of the three assertions above.

### Step 2.6 — §A rows and the Status blocks

- [ ] `:1705` (entry 16), `:1707` (entry 5), `:1723` (entry 8): mark the resolved halves in the
      Verdict column, leaving the open halves visible.
- [ ] `specs/convergence-1-cheap-fixes.md:9`: replace the Status `State` row with **done**, the
      date, the four SHAs and a link to this plan. `detour-staged-refactor` §6 — this project got
      it wrong twice in both directions, so do not write "done" if any item was skipped; write
      **partially done** with the item named.
- [ ] Run [convergence 2](../specs/convergence-2-section-readouts.md)'s Preconditions and record
      the result in **that** spec's Status. It is expected to fail until stage 3 lands
      `SectionAverageTracker`; that failure is the interlock working, not staleness, and recording
      it is exactly the two-minute step §6 says gets skipped.
- [ ] Do **not** write a stop-point sentence into `DECISION.md`. This spec defines no stop-point —
      the convergence axis has none — and inventing one would misreport the chain.

### Step 2.7 — commit

- [ ] Re-run the spec's whole precondition fence. Several assertions now come back inverted; that
      is the expected outcome of this stage and each inversion should match a resolution recorded
      above. Any assertion that is *unchanged* where a task claims to have changed it is a failed
      task, not a stale assertion.
- [ ] Commit:

```
docs(refactor): resolve the four convergence-1 register entries

Entry 8's constant half needed no code: 7d57087, the commit that added the
register, already replaced MapScreen's bare 60 with NavPolicy.OFF_ROUTE_METERS
and said so in its message. Entry 8's prose and its §D assertion both claimed
the literal still existed, so that entry was false in its own first commit.
Recorded rather than quietly corrected.

Marks entry 16's permission half, entry 8's car half and entry 5d's threshold
half resolved with the commits that did them, per §D. Leaves the sendPttStart()
ordering, the socket-down gate, §B2's autoStarted gate and 5a/5b/5c/5e/5f open
and named.

Fixes the two §D assertions this stage inverts, adds one for the car indicator,
and drops the claim that every assertion in that fence was measured green.
```

---

## Task 3: a persistent off-route indicator on the head unit

Register entry 8's second half, §C decision 4 (*"DECIDED: yes, add the indicator"*). Verified at
`5c4b8a3`: `grep -rl 'Off route' app/src/main/java/com/jellemax/detour/car | wc -l` is **0**, and
`grep -c 'speak("Rerouting")' .../car/NavScreen.kt` is **1** (`NavScreen.kt:258`) — one spoken
cue, nothing persistent.

**Where it goes, and why that one.** The `NavigationTemplate` path (`onGetTemplate`, `:429-453`),
via the destination card's `TravelEstimate`. The renderer's HUD is the other candidate the spec
permits, and it loses: it would mean a new drawn element on a `Canvas` composited onto a
`VirtualDisplay` (`CarMapRenderer.kt:506-600`), untestable and unreviewable without a head unit,
where the template path is three edits and one already-present builder.

**The API level matters and was measured, not assumed.** `TravelEstimate.Builder.setTripText` is
`@RequiresCarApi(5)` — read out of
`androidx.car.app/app/1.7.0/…/app-1.7.0.aar` with `javap -v`, `value=5` — while
`AndroidManifest.xml:56-57` declares `minCarApiLevel` **1**. `setRemainingDistanceColor` and
`setRemainingTimeColor` carry no `RequiresCarApi` at all and are safe at level 1. So the words are
guarded and the colour is not, and on an old head unit the colour is the whole indicator. The
guard idiom already exists in this package at `SpinScreen.kt:224`
(`carContext.carAppApiLevel < CarAppApiLevels.LEVEL_7`); match it.

`RequiresCarApi` is enforced by lint (`UnsafeCarApiCall`), not by `kotlinc`, and no workflow in
`.github/workflows/` runs lint. The compiler will not catch a missing guard — which is why the
level was read from the AAR.

### Step 3.1 — imports

- [ ] In `app/src/main/java/com/jellemax/detour/car/NavScreen.kt`, add three imports in
      alphabetical position. `CarColor` goes before `CarIcon` (`:12`) and `CarText` after it, so
      `:10-14` becomes:

```kotlin
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
```

- [ ] And between `androidx.car.app.navigation.model.Trip` (`:23`) and
      `androidx.core.graphics.drawable.IconCompat` (`:24`):

```kotlin
import androidx.car.app.versioning.CarAppApiLevels
```

### Step 3.2 — the predicate, reading the one named bound

- [ ] Insert immediately above `private fun travelEstimate(` (`:489`):

```kotlin
    /** Off the drawn line far enough that [NavPolicy] would ask for a fresh
     *  route. The same bound the phone's nav bar reads
     *  (`ui/MapScreen.kt:1426-1427`) and the same one [NavPolicy.decide]
     *  reroutes on, so what the driver is told cannot disagree with what the
     *  policy decided. Entry 8 of the divergence register is precisely that
     *  this bound is named once. */
    private fun offRoute(p: NavEngine.Progress): Boolean =
        p.offRouteMeters > NavPolicy.OFF_ROUTE_METERS
```

`NavPolicy` is already imported (`:40`).

### Step 3.3 — the indicator itself

`travelEstimate()` has **four** call sites: `:340`, `:342` and `:346` inside `pushTrip`, and
`:452` inside `onGetTemplate`. The first three feed `NavigationManager.updateTrip`, which draws on
the instrument cluster — a fourth surface, deliberately left alone. A defaulted parameter keeps
those three at a zero-line diff.

- [ ] Replace `:489-492` with:

```kotlin
    /**
     * [offRoute] defaults to false so [pushTrip]'s three call sites keep a
     * zero-line diff: those estimates go to the instrument cluster through
     * [NavigationManager.updateTrip], which is a fourth drawing surface and not
     * part of this change.
     */
    private fun travelEstimate(
        meters: Double,
        seconds: Long,
        offRoute: Boolean = false,
    ): TravelEstimate {
        val builder = TravelEstimate
            .Builder(carDistance(meters), ZonedDateTime.now().plusSeconds(seconds))
            .setRemainingTimeSeconds(seconds)
        if (offRoute) {
            // Two signals, because the words need a newer host than the colour
            // does: setTripText is @RequiresCarApi(5) while
            // AndroidManifest.xml:56-57 declares minCarApiLevel 1, so on an
            // older head unit the red readouts *are* the indicator. Colouring
            // both matches the phone, which turns the same string
            // error-coloured (`ui/Navigation.kt:195-200`).
            //
            // Persistent and not a toast on purpose: the defect being fixed is
            // that the one spoken "Rerouting" at :258 leaves a driver who
            // missed it with no way to tell.
            builder.setRemainingDistanceColor(CarColor.RED)
            builder.setRemainingTimeColor(CarColor.RED)
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
                builder.setTripText(CarText.create("Off route"))
            }
        }
        return builder.build()
    }
```

- [ ] Replace `onGetTemplate`'s call at `:452`:

```kotlin
        builder.setDestinationTravelEstimate(
            travelEstimate(p.remainingMeters, remainingSec, offRoute = offRoute(p)))
```

### Step 3.4 — make the template actually redraw

`refreshTemplate` returns early on an unchanged key (`:368`), and none of the five values in that
key is the off-route state. While moving, `displayMeters(p.remainingMeters)` changes anyway and
the redraw happens by accident; stopped just off the line — a wrong turn into a car park, a
driveway, a queue — none of the five moves and the indicator never appears. This is the one
correctness step in the task.

- [ ] Replace `:361-367` (the `buildString` block) with:

```kotlin
        val key = buildString {
            append(p.nextInstruction?.startIndex).append('|')
            append(p.nextInstruction?.text).append('|')
            append(displayMeters(p.distanceToTurnMeters)).append('|')
            append(displayMeters(p.remainingMeters)).append('|')
            append((p.remainingTimeMs ?: 0L) / 60_000).append('|')
            // Part of the key, not just of the template: leaving the route has
            // to redraw even when nothing else moved, and a car stopped just
            // off the line moves none of the five values above.
            append(offRoute(p))
        }
```

### Step 3.5 — verify what can be verified

- [ ] Greps:

```sh
CAR=app/src/main/java/com/jellemax/detour/car
grep -c '"Off route"' $CAR/NavScreen.kt                        # expect 1
grep -c 'NavPolicy.OFF_ROUTE_METERS' $CAR/NavScreen.kt         # expect 1
grep -c 'CarToast' $CAR/NavScreen.kt                           # expect 3 — unchanged
grep -c 'Off route' $CAR/CarMapRenderer.kt                     # expect 0 — no new surface
grep -c 'CarAppApiLevels.LEVEL_5' $CAR/NavScreen.kt             # expect 1
```

- [ ] Tier 0, in the devcontainer:

```sh
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh 5c4b8a3 \
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

- [ ] **No unit test is added, and that is not an oversight.** `travelEstimate` needs a
      `CarContext` and `onGetTemplate` needs a `Screen`; this repo has no Robolectric and no
      `androidTest` source set, and adding either for this is out of proportion. The extracted
      predicate `offRoute()` is a single comparison against a constant that
      `app/src/test/java/com/jellemax/detour/map/NavPolicyTest.kt` already covers on both sides of
      the bound. Say this in the commit rather than implying coverage.

### Step 3.6 — commit

```
feat(car): show a persistent off-route state on the nav template

The head unit spoke "Rerouting" once and showed nothing after that, so a
driver who missed the announcement had no way to tell they were off route. The
phone has shown this since it shipped. Register decision 4, answered yes.

The destination card's remaining distance and ETA turn red, and on car API 5
or newer the trip text reads "Off route". Not a toast: transient is the defect.
No new constant — the bound is NavPolicy.OFF_ROUTE_METERS, which is what entry
8 is about. setTripText is @RequiresCarApi(5) against minCarApiLevel 1, so the
words are guarded and the colour is not; on an older host the colour is the
whole signal.

refreshTemplate's key gains the flag. Without it a car stopped just off the
line moves none of the five existing key components and the indicator would
never appear.

pushTrip's three call sites are unchanged: those estimates go to the instrument
cluster, which this does not touch.

Verified: tier0-greps, compileDebugKotlin, assembleDebug, assembleRelease,
:app: and :shared: unit tests. Not verified: how it looks on a head unit. No
unit test — the builders need a CarContext and there is no Robolectric here.
```

---

## Task 4: iOS's "stopped" threshold becomes `2.0 m/s`

§C decision 3, entry 5d's threshold half. Verified at `5c4b8a3`:
`grep -c 'if speed > 1.0' iosApp/Detour/TripRecorder.swift` is **1** (`:280`), and
`grep -c 'if (speed > 2.0) lastMovingMs = now'
app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` is **1** (`:1069`).

This is a **behaviour change on iOS**, and the register is emphatic that it gets its own commit
with the rationale in the message *and* beside the constant. Two constraints:

- **The `else if` on `:282` is not touched.** That is §B2 — the missing `autoStarted` gate — and
  it is a different defect with a different fix.
- **`2.0` stays a literal.** `TripRecorder` names nineteen other constants as
  `private static let` (`:41-68`) and naming this one would fit that style, but the spec's done
  criterion is `grep -c 'if speed > 2.0'` = 1 (spec `:172`), and Android's counterpart is inline
  too. Matching the surfaces beats matching the file.

### Step 4.1 — the change and its reasoning

- [ ] Replace `iosApp/Detour/TripRecorder.swift:279-284` with:

```swift
        // Stopped for long enough that the ride is over, not a traffic light.
        //
        // 2.0 m/s (~7 km/h), matching Android's
        // `tracking/TripTrackingService.kt:1069`, and deliberately not the 1.0
        // this used to be. The two failure modes are not symmetric: ending a
        // trip late adds harmless idle time to a recording, while ending it
        // early truncates a ride and loses data that cannot be recovered. When
        // in doubt, keep recording. So walking pace counts as moving —
        // pushing the bike into the garage, or crawling in queue traffic,
        // keeps the trip alive.
        //
        // Decided in docs/refactor/mapscreen/15-divergence-register.md §C.3.
        if speed > 2.0 {
            movingSinceMs = nowMs()
        } else if let since = movingSinceMs, nowMs() - since > Self.stationaryEndMs {
            endTrip()
        }
```

### Step 4.2 — verify

- [ ] Greps:

```sh
T=iosApp/Detour/TripRecorder.swift
grep -c 'if speed > 2.0' $T                                     # expect 1
grep -c 'if speed > 1.0' $T                                     # expect 0
grep -c 'else if let since = movingSinceMs' $T                  # expect 1 — §B2 untouched
grep -c 'autoStarted' $T                                        # expect 0 — §B2 untouched
grep -c 'stationaryEndMs' $T                                    # expect 2 — :48 and the else-if
```

- [ ] Not compilable here. A one-token change to a numeric literal inside an existing `if` cannot
      break the parse, which is the strongest honest claim available; the type-check still happens
      in `ios.yml` (see Verification).

### Step 4.3 — commit

```
fix(ios): raise the stopped threshold to 2.0 m/s to match Android

TripRecorder ended a trip after five stationary minutes below 1.0 m/s;
TripTrackingService.kt:1069 uses 2.0. Walking pace counted as moving on
Android and as stopped on iOS, so pushing a bike or crawling in queue traffic
kept an Android trip alive and let an iOS one time out.

iOS moves to 2.0. The rationale is the part neither surface had written down:
the failure modes are asymmetric. Ending a trip late adds harmless idle time
to a recording; ending it early truncates a ride and loses data that cannot be
recovered. When in doubt, keep recording. That reasoning is now in a comment
beside the constant, so the next person does not re-derive it or flip it back.

Register §C decision 3 / entry 5d, threshold half only. 5d's autoStarted gate
is §B2 and stays open; the else-if two lines below is unchanged.

Not verified: nothing was built or run. No Swift toolchain and no Mac here, and
iosApp/ has no test target.
```

---

## Done Criteria

- [ ] `NSMicrophoneUsageDescription` is present and non-empty in `iosApp/Detour/Info.plist`, and a
      record-permission request exists on the press path.
- [ ] The head unit shows a persistent off-route state driven by `NavPolicy.OFF_ROUTE_METERS`, and
      `refreshTemplate`'s key includes it.
- [ ] `grep -c 'if speed > 2.0' iosApp/Detour/TripRecorder.swift` is 1, with the rationale beside
      it.
- [ ] Register entries 16, 8 and 5d marked **resolved** with their commits per §D; the §D
      assertions this stage inverts are corrected — **both** of them, `:1968-1969` as well as
      `:1971-1973`.
- [ ] **Exactly four commits**, none combining two items, none combining item 1 with either of the
      other two §B5 fixes, and none combining item 4 with §B2.
- [ ] The three deliberately-untouched bugs still grep as present: `sendPttStart()` before
      `startCapture`, `if live.activeConvoyId != nil`, and `else if let since = movingSinceMs`
      with no `autoStarted`.
- [ ] `specs/convergence-1-cheap-fixes.md:9` Status updated, and convergence 2's preconditions run
      and recorded in *its* Status.

## Verification

Stated per work item, and per what this machine can actually do. `detour-staged-refactor` §5's
tiers are Kotlin-side; three of these four commits are not Kotlin, and one is Markdown.

| # | Surface | Verifiable here | Needs a Mac | Needs a device |
|---|---|---|:-:|:-:|
| 1 | iOS | plist parses + key non-empty (`plistlib`, `xmllint`); 6 greps | compile | prompt, transmit |
| 2 | docs | the spec's whole precondition fence, re-run | — | — |
| 3 | Android Auto | tier0-greps, `compileDebugKotlin`, `assembleDebug`, `assembleRelease`, `:app:` + `:shared:` unit tests, 5 greps | — | DHU |
| 4 | iOS | 5 greps | compile | a ride |

**What cannot be checked here, precisely.** There is no Swift toolchain and no macOS on this
machine, so items 1 and 4 cannot be compiled, let alone run. `iosApp/` has **no test target** in
`project.yml` — Swift logic in this repo is untested by construction, so there is no test to add
either. `tier0-greps.sh` filters `*.kt` (`tier0-greps.sh:47`) and prints *"no .kt files changed"*
for both iOS commits: **do not record that as a pass.**

**When CI will type-check the Swift, and when it will not.** `ios.yml`'s `push:` trigger is
`branches: [main, ios]` (`ios.yml:9-10`) — the path filter narrows that, it does not widen it. A
push to `refactor/mapscreen-split` **will not** build `iosApp/`. What does fire is the
`pull_request:` trigger, which is path-gated on `shared/**` / `iosApp/**` and not branch-gated
(`:17-20`), or `workflow_dispatch` (`:21`). So: open or refresh the PR, or dispatch the workflow
by hand. Its `xcodebuild build` step (`:121-133`) is the first thing anywhere that type-checks
these two commits, and the arguments in Step 1.2 about `AVAudioApplication` stand or fall there.

`build.yml` is **not** path-gated and runs on every pull request, and it runs
`:app:testDebugUnitTest :shared:testDebugUnitTest` before assembling (`build.yml:117-118`), so the
car commit is gated there as well as locally.

**The CI simulator screenshot does not exercise any of this.** `ios.yml:187-200` boots a
simulator, pre-grants **location only** (`:197`) and screenshots the first screen. Push-to-talk
needs a joined convoy and a second peer; the trip threshold needs movement. A green `ios.yml` run
means the Swift compiles, and nothing more than that.

**Replay is not available for the iOS items.** `detour-gps-replay` drives the *Android* app
through the mock location provider. There is no equivalent path into `iosApp/` here, so item 4's
behaviour change cannot be A/B'd at a desk the way an Android fix consumer could be.

**The car indicator's tier.** `detour-staged-refactor` §5 puts a navigation session at **Tier 3**;
the spec asks for Tier 0 plus a Desktop Head Unit run. Both agree it does not qualify for a
desk-only checklist. Tier 0 is done above; the DHU run is below.

**None of the four** needs replay route (i), the stage-0 baseline, or two phones. That is what
makes this spec runnable while stage 3 is blocked.

## Needs a device

Four things, none of them optional if the corresponding commit is to be called verified. Until
each is done, the commit stands as **unverified** in the Status block — do not substitute a code
read (`detour-staged-refactor` §5).

1. **iPhone, item 1 — the prompt.** Join a convoy, hold the mic button. The system alert should
   appear with the string from Step 1.1. Grant it, hold again, and confirm a peer hears audio.
   Then refuse it in Settings and confirm the button shows `mic.slash` rather than doing nothing.
   Needs a second peer, so it is a two-device check.
2. **iPhone, item 1 — the severity, still open.** Register entry 16's UNVERIFIED claim is about
   the *pre-fix* state: whether activating `.playAndRecord` with no usage description terminated
   the app or failed silently. Reproducing it means running the parent commit. Worth doing once,
   to settle what the entry says; not required to accept the fix.
3. **Head unit or Desktop Head Unit, item 3.** Start navigation, deviate more than 60 m, and
   confirm the destination card's distance and ETA turn red and the trip text reads "Off route";
   confirm it clears on returning to the line. A DHU reports a modern car API level, so this
   exercises the `setTripText` branch — the **level-1 colour-only path is not reachable at a
   desk** and stays untested by anything.
4. **iPhone, item 4 — a real ride.** Record a trip, then move at 1–2 m/s for over five minutes
   (walk the bike, or a long queue) and confirm the trip does not end. The counterpart to check at
   the same time is that a genuine park still ends it after five minutes.

## Next

→ [`../specs/convergence-2-section-readouts.md`](../specs/convergence-2-section-readouts.md), whose
preconditions are expected to fail until stage 3 lands `SectionAverageTracker` — record that
result rather than treating it as staleness (Step 2.6). If stage 3 is still blocked on replay
route (i), the runnable one is
[`../specs/convergence-3-voice-policy.md`](../specs/convergence-3-voice-policy.md), whose only
prerequisite is Task 1 of this plan.
