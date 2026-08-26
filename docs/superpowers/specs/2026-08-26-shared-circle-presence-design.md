# Shared circle presence and notification policy

Slice D of four, and the last. Slice A made the account-gated features reachable on iOS; B moved
their request/response bookkeeping into shared stores; C shared the convoy relay. This slice takes
what is left: the circle presence loop, and the policy half of arrival notifications.

## What is duplicated, measured

| | Android | iOS |
|---|---|---|
| Presence loop | ~60 lines inside `tracking/TripTrackingService.kt` (`circleSyncLoop`) | `CircleSync.swift`, 135 |
| Notification | `notif/CircleNotifyService.kt` 235 + `CircleNotifySettings.kt` 38 + `PlaceNotifications.kt` 137 | `CircleNotifications.swift` 249 |

The presence loops are the tightest duplication in the project — same structure, same guards, same
constants, arrived at independently:

| | Value | Android | iOS |
|---|---|---|---|
| Active cadence | 2 min | `CIRCLE_SYNC_INTERVAL_MS` | `syncIntervalSeconds` |
| Idle cadence | 30 min | `CIRCLE_IDLE_INTERVAL_MS` | `idleIntervalSeconds` |
| Catch-up cap | 5 | `PlaceNotifications.NOTIFY_CAP` | `catchUpCap` |
| Stale window | 3 h | `PlaceNotifications.STALE_AFTER_MS` | `catchUpMaxAgeMs` |

Eight hand-copied numbers across two languages, and every one of them currently agrees. That is
the argument for moving them: they agree *today*.

## What is already shared, and what that tells us

Some of this was extracted before and it is worth being precise, because it changes the size of
the job:

- `CircleFixes`, `CirclePlaces`, `CircleEvents` (including `GeofenceEvaluator`) — the API layer.
- `catchUpSummaryText` and `PlaceEvent.notificationText()` — the notification *wording*, already
  shared and already imported by both platforms.
- `Settings.notifyArrivals(circleId)` and `Settings.lastSeenEventTsMs(circleId)` — the per-circle
  toggle and the dedup watermark.

So the wording moved but the *policy* did not, which is the gap this slice closes.

## The two implementations agree, and one of them is better factored

Checked before designing, rather than assumed:

**They agree on the cap selection**, by different spellings. Android sorts ascending and takes
`takeLast(cap)`; iOS sorts descending and takes `.prefix(cap)`. Both yield the newest N, and iOS's
comment cites `PlaceNotifications.planCatchUp` by name as the thing it is matching. No drift.

**Android's copy is the better one.** `planCatchUp(events, myUsername, nowMs, staleAfterMs, cap)`
is already a pure planner returning `CatchUpPlan(individual, collapsedCount)`, with `nowMs` as a
parameter and no ambient clock. iOS hand-rolls the same three filters inline inside its sweep. So
this extracts from Android — the rule `detour-shared-core` §6 states, and the reason it states it.

**One real difference falls out of the comparison**: they raise notifications in opposite orders.
Android's `takeLast` preserves ascending order, so it posts oldest-first; iOS posts newest-first.
The tray reads differently on each platform. The shared version has to pick one, and this spec
picks **newest-first**: the cap exists because a backlog is not worth reading in full, so the item
most worth seeing should not be buried under four older ones. That makes Android's ordering change,
which is a deliberate behaviour change and belongs in the PR description rather than passing as a
refactor.

## The one thing that cannot be shared, and why it shapes the interface

Android measures fix age monotonically and says why in its own comment:

```kotlin
val fixAgeMs = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs
```

> Monotonic, not wall clock: this asks how old the fix is, and a device clock that drifts or is
> corrected mid-drive would answer it wrong in whichever direction the correction went. The stamp
> posted below is the opposite question and stays on `location.time`.

`commonMain` has no monotonic clock and `Platform.kt`'s three-concern ceiling forbids adding one.
So **fix age is pushed in as a parameter**, like every other platform value in this core. The two
clocks stay distinct in the shared signature too: `fixAgeMs` (monotonic, "how old is this reading")
and the fix's own `timeMs` (wall clock, "when was it taken", which is what gets posted).

Dwell timing is the third clock and also a parameter — Android's comment explains that one as well:
a phone standing still stops producing fixes, so timing dwell by fix timestamps would freeze the
clock at exactly the moment someone parked, and arrival would never fire, which is the one thing a
circle is for.

## Scope

In scope:

- `shared/…/data/CirclePresence.kt` — one `suspend fun tick(...)` doing what both loops do: the
  signed-in/configured guards, listing circles, retaining evaluator state for circles still joined,
  the sharing filter, posting the fix, the trust check against `fixAgeMs`, running
  `GeofenceEvaluator` and recording transitions, and returning the next interval.
- `shared/…/data/CircleNotifyPolicy.kt` — `planCatchUp` moved from Android verbatim except for the
  ordering decision above, plus `circlesWantingDelivery(circles)` (accepted, and `notifyArrivals`
  on), and the two constants.
- Both platforms repointed. Android's `circleSyncLoop` becomes a `delay` plus a `tick` call;
  `CircleSync.swift` likewise. `PlaceNotifications.planCatchUp` and its constants are deleted;
  `CircleNotifications.swift`'s inline filters are deleted.
- First tests over any of this.

Out of scope, deliberately:

- **Notification delivery.** Android's `NotificationChannel`, its foreground service, its
  `PendingIntent`; iOS's `UNUserNotificationCenter`, its authorization state and its
  `runCatchUpSweep` scheduling. These share nothing and each is the platform's own.
- **`CircleNotifyService`'s lifecycle** — when to go foreground, when to stand down, the 5-minute
  circle-list refresh. It is a `Service`.
- **The relay socket membership** (`setNotifyingCircles`) — slice C owns that; this slice only
  decides *which* circles want delivery and hands the set over.
- **`TripTrackingService`'s own location tiering.** The presence loop reads `_lastFix`; how that
  fix got there is not this slice's business.
- **Issue #74's `place_event` parse mismatch.** Filed, pre-existing, and a different contract
  problem — the live push never reaching the client is why the catch-up sweep is currently the only
  path that works, which is worth knowing while reading this code but is not fixed here.

## Error handling

Both loops swallow per-tick failures and retry on the next tick, deliberately — a circle is
Life360-style presence, not a live feed, and one failed poll is not worth surfacing. `tick()` keeps
that: it returns the next interval and never throws for an ordinary failure, matching the contract
slice B established for the stores. `CancellationException` propagates, rethrown ahead of every
generic catch as everywhere else in this core.

One asymmetry stays and is worth naming: Android's loop `continue`s past a failed `Groups.list`
without changing the interval, so a server outage does not push it to the idle cadence. That is
correct — an outage is not evidence that nobody is sharing — and the shared version reproduces it
rather than "simplifying" it.

## Tests

`commonTest`, plain `kotlin.test`. Both halves are pure or parameter-driven, so this is the first
coverage either has had:

- `planCatchUp`: own transitions excluded; anything past the stale window excluded; at most `cap`
  individual events with the remainder counted rather than dropped; **newest-first ordering**, which
  is the behaviour change and so needs pinning rather than describing.
- The empty-and-under-cap boundaries, since `relevant.size <= cap` is the branch that decides
  whether a summary appears at all.
- `circlesWantingDelivery`: accepted-only, and the per-circle toggle respected — including that its
  default is **on**, which matters because it means the filter can never exclude a circle nobody has
  touched.
- `tick`: the cadence switches to idle only when nobody is sharing; a fix older than the trust
  window posts the position but does **not** drive a geofence decision; evaluator state is dropped
  for a circle no longer joined, so rejoining under the same id does not inherit stale dwell; and a
  failed circle list leaves the interval alone.
- Dwell and expiry are driven by passed-in `nowMs`/`fixAgeMs`, never an ambient clock, so every one
  of those is assertable.

The API objects reach the network, so `tick`'s I/O is not exercised here — what is testable is
every decision it makes, which is the part that was duplicated.

## Verification, and its limits

- `commonTest` via `:shared:testDebugUnitTest`, plus `:shared:compileCommonMainKotlinMetadata`.
- Android on the container AVD as far as a signed-out device allows, which slice B established is
  not far: the presence loop needs a session and a reachable server, and this environment has
  neither.
- **iOS unverified, as in slices A, B and C.** No Xcode; the Apple targets are `SKIPPED`. This
  branch stacks on three unmerged slices whose Swift has never compiled, so the first real build
  surfaces all four together.
- **A geofence transition cannot be produced here at all.** It needs a moving device inside a
  shared place's radius, two accounts, and a reachable server. The `detour-gps-replay` skill exists
  for exactly this and is the honest route to verifying it — outside this environment.

## Follow-ups this creates

1. `TripTrackingService.kt` is 1352 lines and this removes ~60 of them. Whether the rest wants
   splitting is a measurement to take afterwards, not a claim to make now.
2. Issue #74 (`place_event` never parses) gates whether the live push half of notifications works
   at all; the catch-up policy this slice shares is currently the only path that fires.
3. Slice C's follow-up about confirming Darwin WebSocket support still stands.
