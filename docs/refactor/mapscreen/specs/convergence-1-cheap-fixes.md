# Convergence 1 — the three cheap cross-surface fixes

## Status

| | |
|---|---|
| **Detail level** | **Full** — three named fixes on three surfaces, each verified against the tree today |
| **Prerequisite** | None. This is the first spec on the convergence axis and depends on no stage of the structure axis |
| **State** | not started · **one of its four work items has already landed** — see item 2 |
| **Preconditions captured** | 2026-08-12 against `20aa813`. Every assertion below was executed before it was written down; two of them came back with a value that contradicted what the register says (items 2 and the PTT gate count) and the expectations here are the measured values, not the quoted ones |
| **Chain** | [design](00-chain-design.md) · [register](../15-divergence-register.md) · prev: none · next: [convergence 2](convergence-2-section-readouts.md) |

## Preconditions

Run before writing this stage's plan. A mismatch means the spec is stale — re-brainstorm, do
not adapt. `chain-status.sh` does **not** see this file (it globs `stage-*.md`), so paste the
fence into a shell yourself; see [`00-chain-design.md`](00-chain-design.md) § *Running the
convergence axis' preconditions*.

```sh
M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt
CAR=app/src/main/java/com/jellemax/detour/car

# Item 1 — iOS still activates a record session with no permission declared and none requested.
grep -c 'NSMicrophoneUsageDescription' iosApp/Detour/Info.plist   # expect 0
grep -c 'playAndRecord' iosApp/Detour/PttAudio.swift              # expect 1
grep -rl 'requestRecordPermission\|AVAudioApplication' iosApp/ | wc -l   # expect 0

# The two same-area §B bugs this stage must not silently absorb: the talking frame still
# precedes capture, and the button is still live while the socket is down.
grep -A1 'ConvoyLiveClient.shared.sendPttStart()' iosApp/Detour/ConvoyBar.swift | grep -c 'PttAudio.shared.startCapture'   # expect 1
grep -c 'if live.activeConvoyId != nil' iosApp/Detour/ConvoyBar.swift   # expect 1
grep -c 'convoyConnected && activeConvoyId != null' $M            # expect 2

# Item 2 — the off-route constant. This is the assertion that came back inverted:
# the deduplication has ALREADY landed, so the register's entry 8 is stale.
grep -c 'NavPolicy.OFF_ROUTE_METERS' $M                           # expect 1
grep -c 'offRouteMeters ?: 0.0) > 60' $M                          # expect 0

# Item 3 — the phone has the indicator, the head unit has nothing persistent.
grep -c '"Off route"' app/src/main/java/com/jellemax/detour/ui/Navigation.kt   # expect 1
grep -rl 'Off route' $CAR | wc -l                                 # expect 0
grep -c 'speak("Rerouting")' $CAR/NavScreen.kt                    # expect 1

# Item 4 — the two stationary thresholds still disagree.
grep -c 'if speed > 1.0' iosApp/Detour/TripRecorder.swift          # expect 1
grep -c 'if (speed > 2.0) lastMovingMs = now' app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt   # expect 1
```

## Why this stage

[`../15-divergence-register.md`](../15-divergence-register.md) §C.1 ordered the work its four
decisions produced. Its first three items are these, and they share one property that matters
more than their size: **none of them needs anything the structure axis is blocked on.** Stage 3
cannot start until replay route (i) exists and `tools/mocklocation/baseline/` is recorded
(`DECISION.md:29-35`). These three need neither, so they are what the convergence axis can do
while that recording does not exist.

They are also ordered for a reason, not just cheap. §C.1 puts the iOS microphone permission
first because decision 1 — the phone gets a voice — adds a **second audio client** to an app
whose first one grabs `.playAndRecord` without declaring a microphone usage description. Fixing
that after adding the second client means debugging two audio paths at once. So item 1 is a
prerequisite of [`convergence-3-voice-policy.md`](convergence-3-voice-policy.md), which is the
only ordering constraint inside this axis.

## Scope

Four work items across three surfaces, from the register's §B and §C:

1. iOS declares and requests the microphone permission (§B5 / entry 16).
2. The off-route literal becomes `NavPolicy.OFF_ROUTE_METERS` (entry 8) — **already landed**.
3. The head unit gains a persistent off-route indicator (decision 4).
4. iOS's "stopped" threshold becomes `2.0 m/s` (decision 3 / entry 5d).

## Out of scope

- **The other two §B5 fixes** — the `sendPttStart()` ordering and the socket-down visibility
  gate (`ConvoyBar.swift`). They are bugs, they are in the same file as item 1, and they are
  therefore the easiest thing in this chain to accidentally fold into one commit.
  `DECISION.md:394-400` forbids exactly that. File them; fix them whenever; never in item 1's
  commit.
- **§B2, iOS auto-ending manually started trips.** Item 4 changes `TripRecorder.swift:280`; §B2
  is the `else if` two lines below it, in the same statement. The threshold is a decided
  behaviour change with a rationale; the missing `autoStarted` gate is a bug. Two commits, and
  the bug does not need this spec at all.
- **The rest of entry 5.** Decision 3 settled one constant. 5a, 5b, 5c, 5e and 5f are ~270
  Swift lines and need their own chain — the register says so at entry 5, *"Blocks stage 3: no"*.
- **Anything in `MapScreen.kt`'s structure or state.** That is the other axis.

## Work items

Independence: **1, 3 and 4 are fully parallel** — three surfaces, three files, no shared
symbol. Only item 1's relationship to convergence 3 is ordered.

### 1. iOS: declare and request the microphone permission

Register: entry 16, §B5. `iosApp/Detour/Info.plist` has no `NSMicrophoneUsageDescription`
(verified, count 0) and nothing in `iosApp/` ever requests record permission (verified, zero
files match), while `PttAudio.swift:41` activates a `.playAndRecord` session.

Add the key, and request the permission before capture rather than at connect time — Android
pre-requests on convoy connect (`MapScreen.kt:474`), but `startCapture` swallows every failure it
can have: the session `catch` at `PttAudio.swift:44-45`, the converter `guard` at `:50`, and the
`engine.start()` `catch` at `:77-78` that removes its own tap and tells nobody. A denied
permission must therefore be handled *before* capture rather than discovered inside it.

**The severity is UNVERIFIED and stays that way here.** The register says the crash-versus-silent-failure
question must be confirmed on a device (`15-divergence-register.md` entry 16), and building the
iOS app at all needs a Mac with Xcode 16 (`CONTRIBUTING.md:9`). Write the commit message
against what *is* verified — the key is absent and no request is made, so iOS PTT cannot
legitimately capture audio — and not against a crash nobody has reproduced.

One commit. Mark entry 16 **resolved** in the register per its §D rule.

### 2. The off-route constant — already done, do not redo it

`grep -c 'offRouteMeters ?: 0.0) > 60'` is **0** and
`grep -c 'NavPolicy.OFF_ROUTE_METERS'` is **1**: `MapScreen.kt:1426-1427` already reads

```kotlin
offRoute = (navProgress?.offRouteMeters ?: 0.0) >
    NavPolicy.OFF_ROUTE_METERS,
```

It landed inside `1c7f827` — the commit that *added the register* — and its message says so in
its last paragraph. Two consequences, both worth knowing before you plan anything:

- **The register's entry 8 is stale in its own first commit.** Its prose still says *"A bare
  `60`"*, and its §D sample script still asserts that literal exists with `# expect 1`. That
  assertion was false the moment it was committed, which makes it the fifth of the kind
  `detour-staged-refactor` §2 warns about — *"wrong on the day it was written"*.
- **This work item is a bookkeeping item, not a code item.** Mark entry 8's constant half
  **resolved** with `1c7f827`, and correct the §D assertion to `# expect 0`.

### 3. Car: a persistent off-route indicator

Decision 4, entry 8's second half. Today `car/NavScreen.kt:258` speaks `"Rerouting"` once and
nothing persists; `grep -rl 'Off route' car/` is empty. The phone's version is
`NavigationBottomBar`'s `offRoute` flag rendered as the string at `Navigation.kt:195`, so there
is a precedent to match rather than a design to invent.

Two constraints on the design, both from what is already in the file:

- **Not a toast.** `NavScreen.kt:175` and `:422` already show `CarToast`s, and a toast is
  transient — the defect being fixed is precisely that a driver who missed a one-shot cue has no
  way to tell. The two candidate homes are the `RoutingInfo`/`NavigationTemplate` path
  (`:430-452`) and the renderer's own HUD (`CarMapRenderer`, anchored bottom-right by design at
  `:502-504`). Pick one in the plan; do not add a third drawing surface.
- **No new constant.** Read `NavPolicy.OFF_ROUTE_METERS`. The whole point of entry 8 is that
  this bound is named once.

One commit, and it does not share one with item 2 — mechanical deduplication and a change to
what a driver sees are the two halves decision 4 explicitly split.

### 4. iOS: "stopped" becomes `2.0 m/s`

Decision 3, entry 5d's threshold half. `TripRecorder.swift:280` `if speed > 1.0` becomes `2.0`,
matching `TripTrackingService.kt:1069`.

This is a **behaviour change on iOS**, not a deduplication, and the register is emphatic that it
gets its own commit with its rationale in the message: the failure modes are asymmetric — ending
a trip late adds harmless idle time, ending it early truncates a ride and loses data that cannot
be recovered. Write that reasoning next to the constant as well as in the message. It is the
thing neither surface had, and a bare `2.0` invites the next person to re-derive it or flip it
back.

## Done criteria and verification

- [ ] `NSMicrophoneUsageDescription` present in `iosApp/Detour/Info.plist` and a record-permission
      request exists on the press path.
- [ ] The head unit shows a persistent off-route state driven by `NavPolicy.OFF_ROUTE_METERS`.
- [ ] `grep -c 'if speed > 2.0' iosApp/Detour/TripRecorder.swift` is 1 with the rationale
      comment beside it.
- [ ] Register entries 16, 8 and 5d marked **resolved** with their commits, per §D's rule. The
      register's §D assertion for entry 8 corrected to `# expect 0`.
- [ ] Four commits, none combining two items, and none combining item 1 with the other two §B5
      fixes.

Verification tier, per surface and honestly:

- **Car indicator** — Tier 0 (`tier0-greps.sh`, `:app:assembleDebug`, `:app:assembleRelease`)
  plus a Desktop Head Unit run driving off a route. It touches a nav-session screen, so it does
  not qualify for a desk-only checklist.
- **The two iOS items** — the verification tiers in `detour-staged-refactor` §5 are Kotlin-side
  and neither item is Kotlin. Both need a Mac with Xcode 16 to build (`CONTRIBUTING.md:9`) and a
  device to see the permission prompt. **If that has not happened, record them as unverified**
  rather than substituting a code read. Do not claim a prompt you have not seen.
- **None of the three** needs replay route (i), the stage-0 baseline, a GPS replay or two
  devices. That is what makes this spec runnable while stage 3 is blocked, and it is why §C.1
  put these first.

## Next stage

→ [`convergence-2-section-readouts.md`](convergence-2-section-readouts.md)

**Before writing its plan:** run its Preconditions block. It will fail until stage 3 lands
`SectionAverageTracker`, and that failure is the interlock working, not staleness — convergence 2
is the one spec on this axis that waits on the structure axis. If stage 3 is still blocked on
route (i), skip to [`convergence-3-voice-policy.md`](convergence-3-voice-policy.md), whose only
prerequisite is item 1 of this spec.
