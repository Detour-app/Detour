# Convergence 2 — the car and iOS trajectcontrole readouts

## Status

| | |
|---|---|
| **Detail level** | **Intent + constraints.** The Work items section **requires a rewrite before use** — see the marker below. It cannot be written yet, and not for want of effort: the readouts consume a type that does not exist until stage 3 chooses it |
| **Prerequisite** | Stage 3 (`stage-3-hazard-machines-to-shared.md`, in history at `b7f4c6f`) complete — **this is the convergence axis' one dependency on the structure axis.** Convergence 1 is not a prerequisite |
| **State** | **done 2026-08-15.** `b655528` (car) · `79f20b7` + `e68c815` (iOS), after `0b4cebf` rewrote the Work items against the landed tracker. Register entry 11 is resolved. Verified as far as this host allows and no further: `:app:` unit tests, `:app:assembleDebug`, `:shared:testDebugUnitTest`, `:shared:compileCommonMainKotlinMetadata` and the shared-core preconditions all pass; **nothing compiled the `iosMain` Kotlin or the Swift**, and no replay or device session ran. See § *Done criteria* and [`../DECISION.md`](../DECISION.md). |
| **Preconditions captured** | Re-captured 2026-08-15 against `6dcc779`, executed. The interlock pair now passes — stage 3 landed the tracker. Two of the 2026-08-12 assertions did not survive: one was **wrong when written**, one merely **stale**; § *Preconditions* says which is which |
| **Chain** | [design](00-chain-design.md) · [register](../15-divergence-register.md) · prev: convergence 1 (`convergence-1-cheap-fixes.md`, in history at `b7f4c6f`) · next: convergence 3 (`convergence-3-voice-policy.md`, in history at `b7f4c6f`) |

## Preconditions

`chain-status.sh` does not see this file; paste the fence into a shell. The interlock lines are
the gate, not staleness — re-read [`00-chain-design.md`](00-chain-design.md) § *The staleness
contract* if that distinction is not obvious. Values below are the 2026-08-15 re-capture against
`6dcc779`; the inverted assertions (the ones asserting a gap is still open) are marked, and this
stage's own work is what closes them.

```sh
CAR=app/src/main/java/com/jellemax/detour/car
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

# The interlock: stage 3 landed machine 1, in commonMain, where iOS can reach it.
# Both of these failed by design until stage 3; both pass now.
test -d shared/src/commonMain/kotlin/com/jellemax/detour/drive && echo stage3-done
grep -rl 'object SectionAverageTracker' shared/src/commonMain/kotlin | wc -l   # expect 1

# The car still fetches the section data and throws it away — the three-line half of this
# stage. INVERTED: this stage is what makes the first two stop reporting 0 and 1.
grep -rl '\.sections' $CAR | wc -l                                 # expect 0
grep -c 'fun updateHud(speedKmh: Double, limitKmh: Double?)' $CAR/CarMapRenderer.kt   # expect 1
grep -c 'result.cameras' $CAR/NavScreen.kt                         # expect 1

# iOS has never seen this data at all, even though SpeedCameras is commonMain. INVERTED.
grep -rl 'SpeedCameras' iosApp | wc -l                             # expect 0

# The phone's readout exists to copy, and stage 3 already pointed it at the shared tracker —
# so this spec's third Scope bullet is satisfied before it starts.
grep -c 'SectionAverageChip' app/src/main/java/com/jellemax/detour/ui/MapHud.kt   # expect 2
grep -c 'averageKmh = sectionAvgKmh' $M                            # expect 1
grep -c 'SectionAverageTracker' $M                                 # expect 4

# The iOS FlowWatcher cost this stage pays: one subclass per element type.
grep -c '^class .*Watcher' shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt   # expect 9
```

**What changed in this fence, and why it matters which.** Two of the 2026-08-12 assertions did
not survive, and they failed for opposite reasons:

- `grep -rl 'SectionAverageTracker' shared/src/commonMain/kotlin` **expected 1, returns 2** —
  and the assertion is **wrong**, not the code. It was written as a prediction of stage 3's
  output before stage 3 existed, and stage 3 landed a second commonMain file that merely *names*
  the tracker: `drive/CameraWarner.kt:20` contrasts itself with it ("**No clock**, unlike
  [SectionAverageTracker]"). A file-list grep counts mentions, not definitions. Corrected above
  to `object SectionAverageTracker`, which is 1 and stays 1.
- `grep -c 'sectionAvgKmh' $M` **expected 5, returns 3** — and here the assertion was right when
  captured and is now **stale**. Stage 3 replaced the phone's inline machine with a call to the
  shared tracker (`MapScreen.kt:1018-1039`), which removed two uses of the name. That is drift in
  exactly the direction stage 3 intended, so the replacement asserts what the spec actually cares
  about: that the phone reads the shared tracker.

The distinction is the one [`00-chain-design.md`](00-chain-design.md) § *The staleness contract*
insists on, and this chain has got it wrong before. Line citations elsewhere in this spec and in
[`../15-divergence-register.md`](../15-divergence-register.md) §11 have drifted too — the car's
fetch is at `car/NavScreen.kt:371-379` now, not `:396-401` — but line drift is not staleness.

## Why this stage

Register decision 2 ([`../15-divergence-register.md`](../15-divergence-register.md) §C, entry
11) is **all three surfaces get the trajectcontrole average**. That decision does two things,
and only one of them belongs to stage 3.

What belongs to stage 3 is the *destination and the shape*: `SectionAverageTracker` goes to
commonMain because iOS cannot consume anything in `app/`, and its output is a public contract
for three consumers rather than a phone detail. That is recorded in stage 3's **Consumed
decisions** section and is not this spec's business.

What belongs here is the *readouts*, and the register is explicit that they are feature work
after the extraction, not part of it: *"The car is cheap: it already fetches the section data and
discards it. iOS is the real work — SwiftUI readout plus the watcher — and is feature work after
stage 3 lands the tracker, not part of it."* Nothing in items 4–6 of §C.1 may share a commit
with the extraction it depends on.

The asymmetry is the point of giving this its own spec. The car half is three lines. The iOS
half is a SwiftUI readout on a surface with no `SpeedCameras` caller today, a `FlowWatcher`
subclass if the element type is new, and a verification story that needs a Mac. Folding that into
stage 3 would make the structure axis wait on Xcode.

## Scope

- **Car**: keep `result.sections` at `car/NavScreen.kt:396-401` instead of discarding it, drive
  the shared tracker with the same fix stream the camera warner uses, and surface the average in
  the car HUD.
- **iOS**: consume the tracker from SwiftUI and show the average during a section, matching what
  `SectionAverageChip` does on the phone (`ui/MapHud.kt:234-250`) — a running average that turns
  red once it exceeds the section limit.
- **Phone**: point the existing readout at the shared tracker if stage 3 has not already done so,
  and nothing else. The phone's behaviour is the reference and must not change here.

## Out of scope

- **The extraction itself.** Stage 3, machine 1.
- **Entry 18 — whether a readout is visible at a standstill.** The register records this as a
  constraint on the tracker, not a decision for it: the tracker emits a value, each surface
  decides whether to show it. If this stage makes the tracker emit "no section" in order to hide
  a chip, it has made entry 18's decision by accident. Whether the car's HUD fades like the
  phone's is a separate one-line product answer where *"leave both"* is defensible.

  > **Entry 18 has since been resolved** — RULING D5-2 in
  > `docs/refactor/mapscreen/15-divergence-register.md` § 18: the phone draws the HUD
  > unconditionally, as the car does, so *"leave both"* is no longer the open answer. The
  > constraint on this stage is unchanged — the tracker must still not decide visibility.
- **The car HUD's other four readouts.** Entry 11 records the real concern — the head unit is
  already at speed, posted limit, ETA card and action strip, and a fifth readout at arm's length
  may be too much. Decision 2 settled *that the car gets the average*; it did not redesign the
  HUD. Layout is in scope for this stage's own brainstorm; re-opening adoption is not.
- **Wear.** It has no `:shared` dependency by design (stage 3 § Out of scope) and draws no
  section UI.

## Work items

> **Rewritten 2026-08-15** against the landed `SectionAverageTracker`
> (`shared/src/commonMain/kotlin/com/jellemax/detour/drive/SectionAverageTracker.kt`). The
> marker this replaces was a scheduled decision, not an unfinished section: what follows could
> not be written before stage 3 chose the output type, and now it can.

### What stage 3 actually produced, which decides the rest

```kotlin
object SectionAverageTracker {
    data class Reading(val averageKmh: Double?, val limitKmh: Double?)
    data class State(active, exitGate, entryMs, accMeters, last, reading: Reading)
    fun onFix(state, sections, at, headingDeg, speedMps, nowMs): State
}
```

Three properties of that shape govern every item below.

- **It is a step function over an immutable `State`, with no `StateFlow` and no scope.** Its
  KDoc says so and says why: *"Whichever per-surface holder wants one wraps this."* So each
  surface supplies its own holder, and none of them is in `commonMain`.
- **`Reading` is one type carrying both numbers**, which is decision 2's shape requirement
  discharged: iOS pays for exactly **one** new `FlowWatcher` subclass, not two.
- **`State` has six constructor parameters, every one of them defaulted.** Kotlin/Native does
  not export default arguments, so `SectionAverageTracker.State()` is not callable from Swift.
  That is not a defect to fix in the tracker — it is the reason iOS's holder is Kotlin.

### 1. Car — keep the sections, drive the tracker, draw the disc

`app/…/car/NavScreen.kt` and `app/…/car/CarMapRenderer.kt`. One commit.

- **Who drives it:** `NavScreen.onFix`, the same `TripTrackingService.lastFix` collector that
  already drives the camera warner. `nowMs` is `System.currentTimeMillis()`, matching the
  phone's call site — `nowMs()` in `data/Angles.kt` is `internal` to `:shared`.
- **Where the sections come from:** `checkCameras`' existing Overpass prefetch, which already
  has the throttle, the margin and the in-flight guard the register calls the better copy. It
  gains one line, `speedSections = result.sections`.
- **Ordering:** the tracker steps in `onFix` *before* `renderer.updateHud`, not inside
  `checkCameras`, so the HUD shows this fix's reading rather than the previous one. The section
  list being one fix stale costs nothing — it is a 4 km prefetch.
- **Which drawing surface:** the renderer's HUD, not `RoutingInfo`/`NavigationTemplate`.
  `updateHud` grows a third parameter carrying the whole `Reading`, defaulted to the
  "not in a section" pair so free drive (`SpinScreen`, which prefetches posted limits but never
  the enforcement relations) keeps its two-argument call.
- **Layout:** a third disc, inboard of the posted-limit sign, so right-to-left the head unit
  reads speed · limit · average — the same order as the phone's `SpeedHud` row. Drawn at 0.9×
  the other two, the phone chip's 72/80 ratio, because it is the number you check rather than
  the one you drive by. Entry 11's real concern is a fifth readout at arm's length; this is the
  layout answer to it, and the register's *"Layout is in scope"* is what permits it.
- **Silent.** No chime, no spoken prompt, no toast. The camera warner announces because a
  camera is an event; an average is a state, and a head unit that reads a running number aloud
  is unusable. Free drive does not get it at all.

### 2. iOS core seam — the holder and the tenth watcher

`shared/src/iosMain/`. One commit, and the only half of the iOS work any CI compiles.

- `drive/SectionAverageHolder.kt` — a `class SectionAverageHolder` owning the `State` and a
  `MutableStateFlow<Reading>`, with `onFix(sections, at, headingDeg, speedMps, nowMs)` and
  `readings()`. It exists because Swift cannot construct `State`, and it owns no scope: the flow's
  value is set on whichever thread called `onFix`, which is SwiftUI's main actor.
- `data/FlowWatcher.kt` — `SectionReadingWatcher`, the **tenth** subclass, over
  `StateFlow<SectionAverageTracker.Reading>`. It goes in `FlowWatcher.kt` rather than beside the
  holder because that file is where the count is discoverable, which is what
  `.claude/skills/detour-shared-core/SKILL.md` §4 documents.
- `Enums` gains `cameraPrefetchRadiusMeters`. Swift has to pass `radiusMeters` explicitly (no
  exported defaults) *and* test its distance from the last fetch centre against the same number;
  `Enums`' own KDoc is the house answer to a `const val` whose exported spelling is
  compiler-version-dependent.

### 3. iOS readout — the prefetch, the model and the chip

`iosApp/Detour/SectionAverage.swift` (new) and `iosApp/Detour/MapScreen.swift`. One commit.

- **Who drives the tracker:** `SectionAverageModel`, a `@MainActor ObservableObject` fed from
  `MapScreen`'s `.onChange(of: recorder.lastFix)`. `TripRecorder` owns the `CLLocationManager`
  and is already the `@EnvironmentObject` on this screen; the model observes its fixes rather
  than opening a second location client.
- **The prefetch is Swift's**, calling `SpeedCameras.near` — commonMain, a suspend function, so
  `async throws` in Swift. Own `Task` with an in-flight guard, a 1 km margin and a 15 s throttle:
  the structure `car/NavScreen.kt:350-362` documents, for the reason it documents.
- **Where the readout sits:** the iOS map screen, trailing, above `ConvoyBar` and the trip card.
  The phone puts the chip in `SpeedHud` on the map screen and shows it whether or not navigation
  is running; the iOS map screen is that surface. It does **not** go in `NavScreen.swift`, which
  is a `fullScreenCover` and would hide the readout during free drive — the case a
  trajectcontrole is most likely to catch.
- **What it draws:** `SectionAverageChip`, the same 72 pt disc as the phone's, `Ø 98` over
  `avg km/h`, red once the average exceeds the section limit. Both numbers come off one
  `Reading`, so they cannot disagree between frames.
- **Not touched:** iOS gets no speed HUD and no posted-limit sign on the map screen out of this.
  Only the average.

### 4. Verification per surface

Named in [Done criteria](#done-criteria-and-verification) below and, for what could not be run,
in the report this stage writes.

### 5. Commit boundaries

Four commits, in order: this rewrite · car · iOS core seam · iOS readout, then the bookkeeping
one. Car and iOS never share a commit, per §C.1; the iOS halves are split because the Kotlin one
is the only one CI can compile and separating it makes the unverifiable Swift diff reviewable on
its own. None of them shares a commit with stage 3's extraction, which landed earlier.

## Done criteria and verification

- [x] The car keeps `result.sections`, drives the shared tracker, and shows the average.
- [x] iOS shows the average during a section, red over the section limit. *Written, not run.*
- [x] `grep -rl 'SpeedCameras' iosApp | wc -l` is no longer 0 — it is 1.
- [x] No new `expect` declaration in `Platform.kt`, and no `Dispatchers.*` in commonMain — the
      shared-core precondition script reports 7/7 after this stage, and the constraint held
      without argument: the holder iOS needed went in `iosMain`, and the fixes and the section
      data are handed in from the platform, which is what the rule asks for.
- [x] Entry 11 marked **resolved** in the register with the commits and which way it went.

Verification, as run:

| Check | Result |
|---|---|
| `:app:testDebugUnitTest`, `:shared:testDebugUnitTest` | pass |
| `:app:compileDebugKotlin`, `:app:assembleDebug` | pass |
| `:shared:compileCommonMainKotlinMetadata` | pass |
| `check-preconditions.sh` | 7/7 |
| `:shared:iosSimulatorArm64Test` | **SKIPPED**, not passed — see below |
| Replay A/B, device desk check, Swift build | **not run** |

**Three things this stage did not verify, and they should not be read as one.**

1. **The `iosMain` Kotlin was never compiled.** Kotlin/Native cannot target Apple platforms from
   Linux, so `:shared:iosSimulatorArm64Test` and even `:shared:compileIosMainKotlinMetadata`
   report `SKIPPED` — a green Gradle run here says nothing about `SectionAverageHolder` or
   `SectionReadingWatcher`. CI's `ios.yml` Native leg is the first thing that builds them, and it
   is path-gated on `shared/**`, which this stage touched.
2. **The Swift was never compiled either, and nothing in this repo can.** `iosApp/` has no test
   target (`project.yml`), and `ios.yml` type-checks `commonMain` and runs `commonTest` — which
   covers the tracker underneath the readout and nothing about the readout. A green iOS workflow
   does not cover this feature; the simulator boot step is the closest thing to a check, and it
   only proves the app launches.
3. **No replay, no device.** The spec's original plan was Tier 2 against the stage-0 baseline
   "which by then exists (stage 3 could not have landed otherwise)". **That premise is false** —
   `../DECISION.md` § *What is not verified* records that stage 3 landed without a replay, both
   Overpass mirrors having refused the host, and no baseline was ever recorded. Route (i), the
   one that enters and exits a gantry, still does not exist. So the car readout is a `lastFix`
   consumer that has earned a Tier 2 verification and has not had one.

## Next stage

→ `convergence-3-voice-policy.md` (`convergence-3-voice-policy.md`, in history at `b7f4c6f`)

Its preconditions do **not** depend on this spec or on stage 3 — only on convergence 1's
microphone-permission item. If this spec is blocked, convergence 3 is still runnable.
