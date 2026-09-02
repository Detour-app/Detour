# iOS: measure a circle fix's age on a monotonic clock

Issue: #75. Branch: `fix/ios-monotonic-fix-age`.

## The defect

`CirclePresence.tick`'s contract is explicit that `fixAgeMs` is **monotonic**.
From its KDoc, "The three clocks"
(`shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt:38-44`):

> `fixAgeMs` — **monotonic**, "how old is this reading". `commonMain` has no
> monotonic clock … so this is computed by the platform
> (`SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs` on Android) and
> passed in — a device clock that drifts or is corrected mid-drive would answer
> "how old is this reading" wrong in whichever direction the correction went.

Android satisfies it. iOS does not — `CircleSync.swift:60,74` computes wall clock
minus wall clock:

```swift
let fixTsMs = Int64(fix.timestamp.timeIntervalSince1970 * 1000)  // CLLocation.timestamp
let fixAgeMs = nowMs() - fixTsMs                                  // nowMs() is Date-based
```

The divergence is already flagged in a comment at that site, deferred to this
issue.

### What goes wrong

`fixAgeMs` drives exactly one decision: `CirclePresence.isFixTrusted(fixAgeMs)`,
the `FIX_TRUST_MS` gate of 15 minutes. A fix failing it is still posted as "last
seen" but must not drive a geofence decision.

A clock correction while the app is running breaks the gate in both directions:

- **Forward jump** (device was behind, NTP corrects it): fixes taken before the
  jump look older than they are. A fresh fix is judged untrusted and a real
  arrival at a circle place is silently skipped for that tick.
- **Backward jump**: fixes look newer than they are. The loop samples whatever
  `LocationBroadcast` last held, which can be minutes old, so a genuinely stale
  fix passes the gate and fires an arrival or departure notification to everyone
  in the circle from a position the rider has left.

Nothing bounds this. The gate is a straight comparison against 15 minutes.

## Blast radius, measured

Smaller than the issue assumed. The issue proposes stamping the uptime "where a
fix is received and carry it alongside the `CLLocation`", touching
`LocationProvider.swift`, `TripRecorder.swift`, `LocationBroadcast.swift` "and
every other consumer of that stream". In fact every fix already funnels through
a single publish point:

| Site | Role |
|---|---|
| `TripRecorder.swift:237` | The **only** caller of `LocationBroadcast.publish` |
| `CircleSync.swift:58` | Reads `LocationBroadcast.shared.last`. The only consumer that needs an age. |
| `ConvoyLiveClient.swift:177` | Reads `LocationBroadcast.shared.stream()`. Does not need an age. |

`LocationProvider.swift` does not appear — it is not in this path.

So the stamp belongs in `LocationBroadcast.publish`, which is the one place
every fix passes through. `ConvoyLiveClient` is untouched and the stream stays
`AsyncStream<CLLocation>`.

## Approaches considered

**A — change `LocationBroadcast` to publish a struct carrying the `CLLocation`
and its uptime stamp, and update every consumer.** Roughly what the issue
describes. Rejected: it changes `stream()`'s element type and therefore
`ConvoyLiveClient`, which has no use for the age, for no benefit.

**B — stamp at the single publish point and expose the age there.** Chosen. One
file gains the stamp, one consumer reads it, nothing else changes. A future
consumer of "how old is this reading" gets a correct answer for free, which is
the scope note the issue ends on.

**C — leave the plumbing and clamp the wall-clock age in `CircleSync`.**
Rejected: a clamp bounds the damage of a corrected clock without making the
answer right, and the shared contract asks for monotonic, not bounded.

## The change

### 1. `LocationBroadcast` stamps each fix onto the uptime timeline

`ProcessInfo.processInfo.systemUptime` is the iOS monotonic clock. Note it does
**not** advance while the device is asleep, which is the same property Android's
`SystemClock.elapsedRealtime()` has in reverse (that one does). This is
addressed under "Known limits" below rather than hidden.

The stamp is back-dated by the delivery lag so the age means the same thing it
means on Android — time since the fix was **taken**, not since it was received:

```swift
let uptimeNow = ProcessInfo.processInfo.systemUptime
// Wall clock is read exactly once per fix, across the sub-second gap between
// CoreLocation taking the reading and delivering it. After this line the age
// is monotonic, so a clock correction later in the drive cannot move it.
let deliveryLagSeconds = max(0, Date().timeIntervalSince(fix.timestamp))
lastFixUptime = uptimeNow - deliveryLagSeconds
```

`max(0, …)` matters: a clock correction landing inside that sub-second window
would otherwise produce a negative lag and a fix stamped in the future.

The age is then pure arithmetic on the monotonic clock:

```swift
Int64(max(0, (ProcessInfo.processInfo.systemUptime - lastFixUptime) * 1000))
```

### 2. The fix and its age are read as one value

`CircleSync` needs the `CLLocation` and the age of *that same* `CLLocation`.
Exposing them as two independent properties would make the pairing a convention
that a later edit can break silently, so `LocationBroadcast` exposes a single
`Sample` value holding both. `LocationBroadcast` is `@MainActor`, so the pair is
already consistent; making it structural means it stays that way.

`last` remains, because `stream()` uses it to seed a new subscriber.

### 3. `CircleSync` reads the sample

```swift
guard let sample = LocationBroadcast.shared.lastSample else { continue }
```

and passes `sample.ageMs` as `fixAgeMs`. `fixTimeMs` and `nowMs` stay wall
clock — that is what their contract asks for, and changing them would be the
error the KDoc's "three clocks" section exists to prevent.

The `DIVERGENCE FROM ANDROID` comment at the computation site is deleted, since
it stops being true. It is replaced by a short note on what the age now measures.

### 4. Documentation

`docs/CIRCLES_AND_CONVOYS.md:130` currently reads:

> Note its fix age is wall clock, not monotonic — `CLLocation` carries no
> uptime-stamped time, so it cannot answer "how old is this reading" as safely
> as Android can.

That becomes false with this change and must be rewritten to describe the
uptime stamp and the sleep caveat.

## Known limits, stated rather than buried

`ProcessInfo.processInfo.systemUptime` does not advance while the device is
suspended. So a phone asleep for an hour, waking with a fix that is genuinely an
hour old, computes an age smaller than the truth and may treat that fix as
trusted when the 15-minute gate should have rejected it.

This is a real gap and it is narrower than the bug being fixed:

- It needs the app to be resumed with a stale fix still in `LocationBroadcast`,
  whereas the current bug needs only an NTP correction.
- The failure it produces is bounded by how long the device slept, and any new
  fix immediately corrects it, whereas a wall-clock jump is unbounded and
  persists.
- The alternative (`CLLocation.timestamp` against wall clock) is exactly what is
  being removed.

The genuinely correct clock is `CLOCK_MONOTONIC_RAW` via
`clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)`, which also excludes sleep, or
`CLOCK_BOOTTIME`-equivalent behaviour, which iOS does not expose through a
public Foundation API. Chasing that is a larger change than this issue, and the
sleep case is worth its own issue rather than a silent partial fix here.

**This must be recorded in the code comment and in
`docs/CIRCLES_AND_CONVOYS.md`, and a follow-up issue filed.** A limit that is
written down is a known trade-off; one that is not is the next bug report.

## Verification

Honestly stated, because it is weaker than the other two fixes in this batch:

- **There is no Swift test target in this repository.** `iosApp/` contains no
  tests and `.github/workflows/ios.yml` runs none — its Kotlin test steps cover
  `shared/`, which this change does not touch. So there is no unit test to add
  without first adding a test target to `project.yml` and a step to the
  workflow, which is a larger change than the fix and does not belong here.
- Verification is therefore: the iOS build on the free `macos-15` runner
  (`ios.yml` is path-gated on `iosApp/**`, which this touches), plus review of
  the arithmetic.
- `CONTRIBUTING.md:108` documents exactly this loop — "Without a Mac, push the
  branch and let the *iOS* workflow build it".

Adding an iOS test target is worth doing and is worth its own issue. It is named
in "Follow-ups" rather than smuggled into a bug fix.

## Follow-ups to file

1. `systemUptime` does not advance across device sleep, so a fix held across a
   suspend is judged newer than it is.
2. `iosApp/` has no test target, so arithmetic like this cannot be unit-tested
   on the platform that owns it.

## Not in scope

- Android's side, which already satisfies the contract.
- `fixTimeMs` and `nowMs`, which are correctly wall clock.
- `LocationProvider.swift` and `TripRecorder.swift` beyond the single publish
  call, which need no change.

## Versioning

`versionName` in `app/build.gradle.kts` versions the **Android** app. This change
is entirely under `iosApp/` and `docs/`, so no bump. (Note: whichever of the
parallel branches in this batch lands first takes `1.95.1`; this one takes
nothing.)
