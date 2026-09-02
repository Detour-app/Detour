# iOS monotonic fix age — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make iOS compute a circle fix's age on the monotonic clock, as `CirclePresence.tick`'s contract requires, so a device clock corrected mid-drive can no longer skip a real arrival or fire a stale one.

**Architecture:** Every fix already funnels through `LocationBroadcast.publish`. Stamp each fix onto the `ProcessInfo.processInfo.systemUptime` timeline there, back-dated by the sub-second delivery lag so the age means "since the fix was taken" as it does on Android. Expose the fix and its age as one `Sample` value so they cannot be read out of step. `CircleSync` reads that instead of subtracting two wall clocks.

**Tech Stack:** Swift 5, SwiftUI, CoreLocation, a Kotlin Multiplatform `DetourShared` framework. Built by `.github/workflows/ios.yml` on a `macos-15` runner.

**Spec:** `docs/superpowers/specs/2026-09-02-ios-monotonic-fix-age-design.md`

**Working directory:** `/home/andre/Projects/Detour/.claude/worktrees/fix-ios-monotonic-fix-age`

---

## Read this before writing code

**This change cannot be compiled on this machine.** The host is Linux; there is no Xcode, no iOS SDK and no CoreLocation. The build happens on a free `macos-15` GitHub runner via `ios.yml`, which is path-gated on `iosApp/**`.

That has one consequence you must act on: **you cannot lean on the compiler.** Read the surrounding code and match it exactly — types, optionality, actor isolation, naming. Do not guess an API. If you are unsure whether something compiles, say so in your report rather than hoping.

Do **not** attempt to run `swiftc`, `xcodebuild`, `gradlew`, or any build command. There is nothing here that can build this, and a failing command proves nothing about the change.

### The three clocks — do not collapse them

`CirclePresence.tick` takes three time parameters with different meanings. Its KDoc (`shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt`, "The three clocks") says they must never collapse into fewer:

| Parameter | Clock | Question it answers | Changing here? |
|---|---|---|---|
| `fixAgeMs` | **monotonic** | how old is this reading | **yes — this is the bug** |
| `fixTimeMs` | wall clock | when was this fix taken (posted to the server) | **no** |
| `nowMs` | wall clock | dwell timing | **no** |

Only `fixAgeMs` changes. Touching the other two is the exact error that KDoc exists to prevent.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `iosApp/Detour/LocationBroadcast.swift` | Modify | Stamp each fix onto the uptime timeline; expose `Sample` |
| `iosApp/Detour/CircleSync.swift` | Modify (`:58-74`) | Read the sample instead of subtracting wall clocks |
| `docs/CIRCLES_AND_CONVOYS.md` | Modify (`:130`) | The client table currently records the defect as permanent |

`TripRecorder.swift` and `LocationProvider.swift` are **not** modified, despite the issue text mentioning them. `TripRecorder.swift:237` already calls `publish(location)` and needs no change.

---

### Task 1: Stamp the fix onto the monotonic clock

**Files:**
- Modify: `iosApp/Detour/LocationBroadcast.swift`

- [ ] **Step 1: Add the `Sample` type and the stamp**

The file currently reads (in full — it is 37 lines):

```swift
import CoreLocation

/// One place fixes are published to, so a second consumer never opens a second
/// GPS listener.
///
/// The trip recorder owns the `CLLocationManager` and feeds this; the convoy
/// client reads it. That is the same arrangement the Android app has, where
/// `ConvoyLiveClient` collects `TripTrackingService.lastFix` rather than
/// registering its own callback — two location listeners on one device is
/// double the battery for the same fixes.
@MainActor
final class LocationBroadcast {

    static let shared = LocationBroadcast()

    private var continuations: [UUID: AsyncStream<CLLocation>.Continuation] = [:]

    private(set) var last: CLLocation?

    func publish(_ fix: CLLocation) {
        last = fix
        for continuation in continuations.values { continuation.yield(fix) }
    }
    …
}
```

Make exactly these changes:

**a.** Add `import Foundation` above `import CoreLocation` (`ProcessInfo` and `Date` come from Foundation; `CoreLocation` re-exports it in practice, but the file should say what it uses).

**b.** Add this nested type inside the class, above `static let shared`:

```swift
    /// A fix together with how old it is, read as one value.
    ///
    /// One value rather than two properties because the age belongs to *this*
    /// `fix` and nothing else: as separate properties the pairing would be a
    /// convention a later edit could break without any signal.
    struct Sample {
        let fix: CLLocation
        /// Milliseconds since the fix was taken, on the monotonic clock.
        /// This is `CirclePresence.tick`'s `fixAgeMs` contract — see "The
        /// three clocks" in its KDoc.
        let ageMs: Int64
    }
```

**c.** Add this stored property next to `last`:

```swift
    /// When the last fix was taken, on the `systemUptime` timeline.
    private var lastFixUptime: TimeInterval?
```

**d.** Replace `publish` with:

```swift
    func publish(_ fix: CLLocation) {
        last = fix

        // Wall clock is read exactly once per fix, across the sub-second gap
        // between CoreLocation taking the reading and delivering it here. Every
        // age computed from this point on is pure arithmetic on `systemUptime`,
        // so a device clock corrected later in the drive cannot move it — which
        // is the whole point, and what `CirclePresence.tick`'s `fixAgeMs`
        // contract asks for. Back-dated by the delivery lag rather than stamped
        // at arrival so the age means "since the fix was taken", matching
        // Android's `elapsedRealtime() - fix.elapsedRealtimeMs`.
        //
        // Clamped at zero because a clock correction landing inside that
        // sub-second window would otherwise stamp the fix in the future.
        let deliveryLagSeconds = max(0, Date().timeIntervalSince(fix.timestamp))
        lastFixUptime = ProcessInfo.processInfo.systemUptime - deliveryLagSeconds

        for continuation in continuations.values { continuation.yield(fix) }
    }
```

**e.** Add this computed property after `publish`:

```swift
    /// The most recent fix and its age, or nil before the first fix arrives.
    ///
    /// Known limit: `systemUptime` does not advance while the device is
    /// suspended, so a fix held across a sleep reports an age smaller than the
    /// truth and can pass a staleness gate it should have failed. That is
    /// narrower than the wall-clock bug this replaced — it needs a resume with
    /// a stale fix still held, is bounded by how long the device slept, and any
    /// new fix corrects it, whereas a clock correction is unbounded and
    /// persists. iOS exposes no public monotonic clock that counts sleep;
    /// tracked as a follow-up issue.
    var lastSample: Sample? {
        guard let last, let lastFixUptime else { return nil }
        let ageSeconds = max(0, ProcessInfo.processInfo.systemUptime - lastFixUptime)
        return Sample(fix: last, ageMs: Int64(ageSeconds * 1000))
    }
```

- [ ] **Step 2: Re-read the file end to end**

You cannot compile. So read the whole file back and check by eye:
- `Sample` is declared inside the `@MainActor final class`, so it is `LocationBroadcast.Sample`.
- `guard let last, let lastFixUptime` uses Swift 5.7+ shorthand optional binding — confirm the rest of the codebase uses that form (grep for `guard let ` in `iosApp/`). If it does not, write `guard let last = last, let lastFixUptime = lastFixUptime`.
- `Int64(ageSeconds * 1000)` — `ageSeconds` is `TimeInterval` (`Double`), so this is a `Double`→`Int64` truncating conversion, which is valid Swift.
- Nothing else in the file was touched.

- [ ] **Step 3: Commit**

```bash
git add iosApp/Detour/LocationBroadcast.swift
git commit -m "Stamp each fix onto the monotonic clock where it is published (#75)"
```

---

### Task 2: `CircleSync` reads the sample

**Files:**
- Modify: `iosApp/Detour/CircleSync.swift`

- [ ] **Step 1: Replace the fix-and-age block**

In `loop()`, this is the current code:

```swift
            try? await Task.sleep(for: .seconds(intervalSeconds))
            guard let fix = LocationBroadcast.shared.last else { continue }

            let fixTsMs = Int64(fix.timestamp.timeIntervalSince1970 * 1000)
            // DIVERGENCE FROM ANDROID, left unfixed in this slice: this is
            // wall clock minus wall clock (`nowMs()` and `fix.timestamp` are
            // both `Date`-based), not monotonic. Android computes
            // `SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs`
            // instead, so a device clock corrected mid-drive can't answer
            // "how old is this reading" wrong in whichever direction the
            // correction went — see `CirclePresence.tick`'s KDoc, "The three
            // clocks". A real fix needs an uptime-stamped fix time, and
            // `CLLocation.timestamp` doesn't offer one; that means stamping
            // `ProcessInfo.processInfo.systemUptime` where a fix is
            // *received* in the location plumbing (`LocationProvider` /
            // `TripRecorder` / `LocationBroadcast`), which is out of scope
            // here — tracked as issue #75.
            let fixAgeMs = nowMs() - fixTsMs
```

Replace all of it with:

```swift
            try? await Task.sleep(for: .seconds(intervalSeconds))
            guard let sample = LocationBroadcast.shared.lastSample else { continue }

            let fix = sample.fix
            // Wall clock, deliberately: `fixTimeMs` answers "when was this fix
            // taken" and is what gets posted to the server as the fix's own
            // timestamp. Only `fixAgeMs` is monotonic — see "The three clocks"
            // in `CirclePresence.tick`'s KDoc, which is explicit that these
            // must never collapse into one value.
            let fixTsMs = Int64(fix.timestamp.timeIntervalSince1970 * 1000)
            // Monotonic, stamped where the fix was published. A device clock
            // corrected mid-drive cannot move this.
            let fixAgeMs = sample.ageMs
```

The `CirclePresence.shared.tick(...)` call below is unchanged — it still passes `fixTsMs`, `fixAgeMs` and `nowMs()` exactly as before.

- [ ] **Step 2: Update the class doc**

The class KDoc for `CircleSync` currently ends:

```
/// `commonMain` cannot have: the `while`/`Task.sleep` itself, the guard that
/// a fix actually exists to share, and the fix's age — see the divergence
/// noted at its computation below.
```

The divergence is gone, so change the trailing clause to:

```
/// `commonMain` cannot have: the `while`/`Task.sleep` itself, the guard that
/// a fix actually exists to share, and the fix's age, which `LocationBroadcast`
/// stamps on the monotonic clock.
```

- [ ] **Step 3: Confirm nothing else referenced the removed name**

```bash
grep -rn "LocationBroadcast.shared.last\b" iosApp/
grep -rn "DIVERGENCE" iosApp/
```

Expected: the first returns nothing (only `lastSample` is used now, and `stream()` uses the private-ish `last` internally within `LocationBroadcast` itself). The second returns nothing. If either returns a hit you did not expect, report it rather than editing blindly.

Note `last` itself is **not** removed — `stream()` uses it to seed a new subscriber.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Detour/CircleSync.swift
git commit -m "Use the monotonic fix age for the circle trust gate (#75)"
```

---

### Task 3: Correct the documentation

**Files:**
- Modify: `docs/CIRCLES_AND_CONVOYS.md:130`

- [ ] **Step 1: Rewrite the client table row**

Line 130 currently reads:

```markdown
| `iosApp/Detour/CircleSync.swift` | The same, on iOS. Note its fix age is wall clock, not monotonic — `CLLocation` carries no uptime-stamped time, so it cannot answer "how old is this reading" as safely as Android can. |
```

That becomes false with this change. Replace with:

```markdown
| `iosApp/Detour/CircleSync.swift` | The same, on iOS. Its fix age is monotonic: `CLLocation` carries no uptime-stamped time, so `LocationBroadcast` stamps each fix against `ProcessInfo.systemUptime` on receipt, back-dated by the delivery lag. One residual gap versus Android — `systemUptime` does not advance across device sleep, so a fix held over a suspend reads younger than it is. |
```

Keep the table's existing column structure and pipe alignment.

- [ ] **Step 2: Commit**

```bash
git add docs/CIRCLES_AND_CONVOYS.md
git commit -m "Record that the iOS fix age is now monotonic (#75)"
```

---

### Task 4: Verification and honest reporting

There is nothing to run. Verify by reading.

- [ ] **Step 1: Read the full diff**

```bash
git diff origin/main
```

Check every one of these and state each explicitly in your report:

1. `fixTimeMs` still comes from `fix.timestamp` (wall clock) — **unchanged**.
2. `nowMs()` is still passed as `nowMs` — **unchanged**.
3. `fixAgeMs` now comes from `sample.ageMs` and nothing else.
4. Both clamps are present: `max(0, …)` on the delivery lag in `publish`, and `max(0, …)` on the age in `lastSample`.
5. `TripRecorder.swift` and `LocationProvider.swift` show no diff.
6. `shared/` shows no diff.
7. The `DIVERGENCE FROM ANDROID` comment is gone and the class doc no longer points at it.

- [ ] **Step 2: Sanity-check the arithmetic in prose**

Write out, in your report, what `lastSample.ageMs` evaluates to for a fix taken 3 seconds before it was delivered and read 60 seconds after delivery. State the expected number and show the arithmetic. (It should be about 63000.)

Then state what happens to that number if the wall clock jumps forward by an hour immediately after `publish` returns. (It should be unchanged — that is the bug being fixed.)

- [ ] **Step 3: Report**

Report DONE / DONE_WITH_CONCERNS / BLOCKED, the diff stat, the seven checks above, the arithmetic, and — importantly — **any line you are not confident compiles**, since nothing here can prove it does.

---

## Notes for the implementer

- **Do not run any build command.** Nothing on this host can build iOS. A failure would be meaningless and a "success" impossible.
- **Do not touch `fixTimeMs` or `nowMs`.** Only `fixAgeMs` is wrong.
- **Do not modify `TripRecorder.swift` or `LocationProvider.swift`**, despite the issue text naming them. `publish(location)` is already called correctly.
- **Do not remove `last` from `LocationBroadcast`** — `stream()` seeds new subscribers from it.
- **Do not add a Swift test target.** There is none, and adding one is a separate piece of work named in the spec's follow-ups.
- Do not bump `versionName`; that versions the Android app and this change is iOS-only.
