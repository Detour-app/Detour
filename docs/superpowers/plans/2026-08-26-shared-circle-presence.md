# Shared circle presence and notification policy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the circle presence tick and the arrival-notification policy into `shared/commonMain`, so eight hand-copied constants and two independently-written decision loops become one implementation — leaving each platform only its delivery mechanism and its clocks.

**Architecture:** Two shared objects. `CircleNotifyPolicy` is pure (extracted from Android's already-pure `planCatchUp`). `CirclePresence.tick(...)` is `suspend`, takes the fix and its monotonic age as parameters, and returns the next interval — the platform owns the loop and the coroutine, as in slice B.

**Tech Stack:** Kotlin Multiplatform (`:shared`), kotlinx-coroutines, `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-08-26-shared-circle-presence-design.md`

## Global Constraints

- **All tooling runs inside the devcontainer.** `devcontainer-exec ./gradlew …`. NEVER on the host — JDK 26, no Android SDK. The AVD `detour-api35` runs as `emulator-5554` in container `great_panini`.
- **`commonMain` has no `Dispatchers`, no logger, no `java.*`, no `org.json`, and no monotonic clock.** The platform owns the loop and supplies every clock reading.
- **Three clocks stay distinct, and the signature must keep them so.** `fixAgeMs` is monotonic ("how old is this reading" — Android uses `SystemClock.elapsedRealtime()` deliberately, because a clock corrected mid-drive answers that wrong in whichever direction it moved). The fix's own `timeMs` is wall clock ("when was it taken") and is what gets posted. Dwell timing is wall clock too, and must **not** be derived from fix timestamps — a parked phone stops producing fixes, so dwell timed that way freezes at the moment someone parked and arrival never fires, which is the one thing a circle is for. Never collapse these into one parameter.
- **No ambient clock in any shared decision.** Every time value a test needs to reproduce is a parameter, the shape `GeofenceEvaluator` and `RouteGpx.parseGpx(text, nowMs)` already use.
- **`@Throws(Exception::class)` on every exported suspend function**; without it a Kotlin/Native suspend function propagates only `CancellationException` and everything else **terminates the Swift process**. Canonical explanation in `SyncClient.kt`.
- **Every generic `catch (e: Exception)` preceded by `catch (e: CancellationException) { throw e }`.** House pattern in `SpinPicker.kt`; it has been broken twice on this work.
- **An action never throws for an ordinary failure** — a failed tick returns the next interval and is retried, matching slice B's store contract. A circle is presence, not a live feed.
- **Preserve the asymmetry Android has on purpose:** a failed `Groups.list` does *not* push the cadence to idle. An outage is not evidence that nobody is sharing.
- **Every user-facing string byte-identical.** The wording (`catchUpSummaryText`, `PlaceEvent.notificationText()`) is already shared and is not being touched.
- Swift 5.9 / iOS 17; no `MainActor.assumeIsolated`. `DetourShared.Group` stays qualified. Watchers cancelled in `deinit`.
- **You cannot compile Swift here.** No Xcode, Apple targets `SKIPPED`, and this branch stacks on three unmerged slices whose Swift has never built. Never claim it compiles; read it back and report what you checked.
- **No `Co-Authored-By` and no `Claude-Session` trailer, ever.** Conventional-commits, subjects under ~72 chars.
- **`git status` before your first commit**, and report anything in the tree you did not put there. A stray background task once left a deliberate mutation in this repo, and an agent once died with completed work uncommitted.
- Branch `feat/shared-circle-presence`, spec committed as `b238943`. `versionName` is Task 5's job alone; `versionCode` is CI-stamped and never touched by hand.

---

### Task 1: The notification policy

Pure, extracted from the better copy. Deliverable: shared policy plus tests, with nothing consuming it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleNotifyPolicy.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/data/CircleNotifyPolicyTest.kt`

**Interfaces:**
- Consumes: `PlaceEvent`, `Group`, `Settings.notifyArrivals`.
- Produces, relied on by Tasks 3 and 4: `CircleNotifyPolicy.CatchUpPlan(individual, collapsedCount)`, `CircleNotifyPolicy.planCatchUp(events, myUsername, nowMs, staleAfterMs = STALE_AFTER_MS, cap = NOTIFY_CAP): CatchUpPlan`, `CircleNotifyPolicy.circlesWantingDelivery(circles: List<Group>): Set<String>`, and the constants `STALE_AFTER_MS` (3 h) and `NOTIFY_CAP` (5).

- [ ] **Step 1: Read both existing implementations**

`app/src/main/java/com/jellemax/detour/notif/PlaceNotifications.kt` — `planCatchUp` and the two constants — and `iosApp/Detour/CircleNotifications.swift`'s `runCatchUpSweep`, which hand-rolls the same three filters inline.

They agree on the cap selection by opposite spellings: Android sorts ascending and takes `takeLast(cap)`, iOS sorts descending and takes `.prefix(cap)`, both yielding the newest N. **Confirm that yourself** — if you find a disagreement I missed, report it rather than picking a side.

- [ ] **Step 2: Write the failing tests**

House style: plain `kotlin.test`, a class KDoc saying what contract it covers and why, sentence-shaped camelCase names, private fixture builders, a comment above any assertion whose point is not obvious. Read `shared/src/commonTest/…/RelayProtocolTest.kt` for the register.

Cover:
- the rider's own transitions are excluded (the endpoint returns them by design, but nobody needs telling where they themselves went)
- anything older than `staleAfterMs` is excluded, driven by a passed-in `nowMs`
- at most `cap` individual events, with the remainder in `collapsedCount` rather than dropped
- **newest-first ordering** — this is a deliberate behaviour change from Android's oldest-first, so it needs pinning rather than describing
- the `relevant.size <= cap` boundary in both directions, since that branch decides whether a summary appears at all
- `circlesWantingDelivery`: accepted-only, and the per-circle toggle respected — including that `Settings.notifyArrivals` defaults to **on**, so the filter can never exclude a circle nobody has touched

- [ ] **Step 3: Run to verify failure**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*CircleNotifyPolicyTest*'
```

Expected: FAIL at compilation, `Unresolved reference: CircleNotifyPolicy`.

- [ ] **Step 4: Write the policy**

Move `planCatchUp` from `PlaceNotifications.kt` **with its KDoc** — that comment explains why each filter exists ("a phone back from a week offline must not detonate into fifty pings") and is the reason the code is shaped this way. Change only the ordering, to newest-first, and say so in the doc.

Add `circlesWantingDelivery(circles)`: accepted status and `Settings.notifyArrivals(id)`. Both platforms compute this today — Android in `CircleNotifyService.refreshNotifyCircles`, iOS inline in `runCatchUpSweep`.

- [ ] **Step 5: Verify**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected `BUILD SUCCESSFUL` and every pre-existing test still green (296 before this task).

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleNotifyPolicy.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/CircleNotifyPolicyTest.kt
git commit -m "feat(shared): own the circle catch-up policy in commonMain

The wording was already shared; the policy was not. Two implementations decided
independently which caught-up arrivals are worth raising, with the cap and the
stale window typed out as constants on both sides.

Extracted from Android's copy because it was already a pure planner taking nowMs
as a parameter, where iOS hand-rolled the same three filters inline. The one
behaviour change is ordering: shared raises newest-first, which Android did not,
because the cap exists precisely because a backlog is not worth reading in full."
```

---

### Task 2: The presence tick

Deliverable: one `tick`, tested, with neither platform calling it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/data/CirclePresenceTest.kt`

**Interfaces:**
- Consumes: `SyncClient.configured()`, `Account.signedIn`/`username`, `Groups.list("circle")`, `CircleFixes.postFix`, `CirclePlaces.places`, `CircleEvents.record`, `GeofenceEvaluator`.
- Produces, relied on by Tasks 3 and 4: `CirclePresence.tick(lat, lon, accuracyM, fixTimeMs, fixAgeMs, nowMs): Long` returning the next interval in milliseconds, plus `ACTIVE_INTERVAL_MS` (2 min), `IDLE_INTERVAL_MS` (30 min) and `FIX_TRUST_MS`.

- [ ] **Step 1: Read both loops line by line**

`app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt`'s `circleSyncLoop` (around line 1198) and `iosApp/Detour/CircleSync.swift`. The spec says behaviour does not change, which means reproducing these rather than improving them.

Note especially, because each is a decision with a reason:
- the guards (`SyncClient.configured()`, `Account.signedIn`, a non-null fix) and that a failure `continue`s **without changing the interval**
- `circleEvaluators.keys.retainAll(...)`, which stops a rejoined circle inheriting stale dwell state
- the sharing filter (this device's own member row, `sharing == true`)
- that the fix is posted **before** the trust check, so a stale position still updates "last seen" but does not drive a geofence decision
- `FIX_TRUST_MS`'s value and its comment about `PRIORITY_PASSIVE` in sleep mode

- [ ] **Step 2: Write the failing tests**

`tick` reaches the network through the API objects, so what is testable is every decision it makes. Structure the code so those decisions are reachable — extract them as `internal` pure functions where a test cannot otherwise drive them, the same seam `Auth.tokenFailureMessage` and `CirclesStore.commitIfViewing` already use in this module, and say in your report which you had to extract.

Cover at least:
- the cadence switches to `IDLE_INTERVAL_MS` only when nobody is sharing, and back
- a failed circle list leaves the interval **unchanged** — the deliberate asymmetry
- a fix older than `FIX_TRUST_MS` posts the position but does not evaluate geofences
- evaluator state is dropped for a circle no longer joined
- dwell and staleness are driven by the passed-in `nowMs`/`fixAgeMs`, never an ambient clock — assert with two different values that the outcome moves with the parameter

- [ ] **Step 3: Run to verify failure**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*CirclePresenceTest*'
```

- [ ] **Step 4: Write `CirclePresence`**

`tick` as specified. `@Throws(Exception::class)`. `CancellationException` rethrown ahead of every generic catch. The three constants moved unchanged.

Where the evaluators live is a real decision: Android keeps a per-circle map on the service, iOS on `CircleSync`. Sharing it means the map belongs to `CirclePresence` — which makes it object-level mutable state, so **say in your report how a session change clears it**, and check whether `Auth.sessionEpoch` needs to reach here. Slice C found five separate places where rider-scoped state outlived a sign-out; do not add a sixth.

- [ ] **Step 5: Verify**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/CirclePresenceTest.kt
git commit -m "feat(shared): one circle presence tick for both platforms

The two loops were structurally identical down to their constants - 2 minute
active cadence, 30 minute idle, the same guards, the same evaluator retain.

The platform still owns the loop and every clock: fix age is monotonic and
passed in, because a device clock corrected mid-drive answers 'how old is this
reading' wrong in whichever direction it moved, and dwell is wall clock because
a parked phone stops producing fixes."
```

---

### Task 3: Android

Deliverable: Android runs on both shared pieces; behaviour unchanged except the notification ordering the spec names.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt` — `circleSyncLoop` becomes a delay plus a `tick`
- Modify: `app/src/main/java/com/jellemax/detour/notif/PlaceNotifications.kt` — `planCatchUp` and its two constants deleted
- Modify: `app/src/main/java/com/jellemax/detour/notif/CircleNotifyService.kt` — uses the shared policy
- Modify: `app/src/main/java/com/jellemax/detour/notif/CircleNotifySettings.kt` if it duplicates the toggle read

- [ ] **Step 1: Repoint the presence loop**

Keep the loop, the `delay`, and the monotonic `SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs` fix-age computation on this side — that is the part that cannot move. Delete the body and call `tick`.

- [ ] **Step 2: Repoint the notification policy**

`CircleNotifyService.catchUp` and `refreshNotifyCircles` use `CircleNotifyPolicy`. Delete Android's `planCatchUp`, `CatchUpPlan`, `STALE_AFTER_MS` and `NOTIFY_CAP`. **Keep** `ensureChannel`, `takeOpenCircleId`, `notify`, `notifySummary`, `show` and the `PendingIntent` handling — delivery is platform.

- [ ] **Step 3: Prove nothing else referenced what you deleted**

```bash
grep -rn "planCatchUp\|CatchUpPlan\|STALE_AFTER_MS\|NOTIFY_CAP\|CIRCLE_SYNC_INTERVAL_MS\|CIRCLE_IDLE_INTERVAL_MS" app/ wear/
```

Expected: only the new shared references. `wear/` must show nothing — it does not depend on `:shared`.

- [ ] **Step 4: Build and test**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug
```

Report `TripTrackingService.kt`'s and `PlaceNotifications.kt`'s line counts before and after. If the app test count drops, that is coverage leaving — account for it, and port anything worth keeping into the shared tests rather than losing it. That happened once already on this work.

- [ ] **Step 5: Device pass, and know what is out of reach**

Read `.claude/skills/detour-adb/SKILL.md` first. The installed package is `io.github.maxke24.detour.debug`, **not** the Kotlin package. **Never** `adb uninstall` or `pm clear`.

A geofence transition cannot be produced here — it needs a moving device inside a shared place's radius, two accounts and a reachable server. Earlier work established the signed-in path is unreachable in this environment at all. So verify only: the app builds, installs and runs on `emulator-5554`; navigating does not crash; and `adb logcat` shows no exception naming `CirclePresence`, `CircleNotifyPolicy`, `CircleNotifyService` or `TripTrackingService` across a launch-and-navigate cycle. Capture with `.claude/skills/detour-adb/scripts/capture-state.sh <scratch>/ emulator-5554`.

Say plainly that presence and notification behaviour were not exercised. The shared tests are the coverage of record.

- [ ] **Step 6: Commit**

---

### Task 4: iOS

Deliverable: iOS runs on both shared pieces.

**Files:**
- Modify: `iosApp/Detour/CircleSync.swift` — loop keeps its cadence and clock, body calls `tick`
- Modify: `iosApp/Detour/CircleNotifications.swift` — inline filters replaced by `CircleNotifyPolicy`

- [ ] **Step 1: Repoint both**

`CircleSync`'s loop keeps its own `Task.sleep` and supplies the fix and its age; check what iOS uses for a monotonic age and whether it genuinely is monotonic — if it is wall clock today, that is a divergence from Android worth reporting rather than quietly preserving.

`CircleNotifications.runCatchUpSweep` uses `planCatchUp` and `circlesWantingDelivery`. **Keep** `UNUserNotificationCenter`, the authorization handling, `raise`, `raiseSummary`, and the sweep's scheduling — delivery is platform.

- [ ] **Step 2: Note the ordering change**

iOS already raises newest-first, so the shared ordering matches it and only Android changes. Confirm that rather than assuming it.

- [ ] **Step 3: Verify what can be verified**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest :app:assembleDebug
```

Then read every edited Swift file top to bottom and report per file what you checked: exported Kotlin spellings and argument labels against the Kotlin source, `Int32`/`Int64` and boxed-primitive conversions, `DetourShared.Group` qualification, nothing newer than Swift 5.9 / iOS 17, watchers cancelled in `deinit`.

**Do not claim the Swift compiles.** Four slices of Swift are now unbuilt and the first Xcode run surfaces all of them together.

- [ ] **Step 4: Commit**

---

### Task 5: Version, and the port record

- [ ] **Step 1: Bump**

`app/build.gradle.kts`: `versionName = "1.82.0"` → `"1.83.0"`. Minor — new shared surface, and Android's catch-up notification ordering changes. `versionCode` is CI-stamped.

- [ ] **Step 2: `docs/IOS_PORT.md`**

Add to "Done" that circle presence and the notification policy are shared, and name what is not: delivery on both sides, Android's foreground service, iOS's authorization and sweep scheduling.

- [ ] **Step 3: `docs/CIRCLES_AND_CONVOYS.md`**

Slice C's review found this file still describes a two-client world. Bring its architecture section up to date for both slices — the relay files and these two — or state plainly that it is stale and what it is stale about.

- [ ] **Step 4: Verify and commit**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
```

---

## Self-Review

**Spec coverage.** Policy → Task 1. Presence → Task 2. Android → Task 3. iOS → Task 4. Version and docs → Task 5. The spec's out-of-scope list is enforced by Tasks 3 and 4 naming what stays (channels, foreground service, `UNUserNotificationCenter`, authorization, sweep scheduling). The three-clocks constraint is in Global Constraints and in Task 2's step 1. The ordering behaviour change is pinned by a test in Task 1 and re-confirmed in Task 4.

**Placeholder scan.** No TBDs. Task 1 carries the full interface because it is a move with one deliberate change; Tasks 2-4 name the decisions to reproduce and where to read them, because the authority is the two existing loops and transcribing them here would create a third copy to drift. Every step that needs a value says where to read it.

**Type consistency.** `CircleNotifyPolicy.planCatchUp`/`CatchUpPlan`/`circlesWantingDelivery`/`STALE_AFTER_MS`/`NOTIFY_CAP` and `CirclePresence.tick`/`ACTIVE_INTERVAL_MS`/`IDLE_INTERVAL_MS`/`FIX_TRUST_MS` are spelled identically in every task.

**The risk the executor carries.** `CirclePresence` holds the per-circle evaluator map, which is object-level mutable rider-scoped state — exactly the shape that produced five separate leaks on the previous slice. Task 2 must say how a session change clears it, and if the answer is "it does not", that is a finding, not a detail.
