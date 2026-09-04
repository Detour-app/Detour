# Content-free push wake for circle notifications

Date: 2026-09-04
Issue: #142 (follow-up to #90)

## Problem

Circle arrival/departure delivery on Android rides an always-open WebSocket that
`CircleNotifyService` holds. Because that service runs `FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING`,
it must show a permanent, non-dismissible "Watching your circles for arrivals and
departures" notification whenever the user is signed in with one notify-enabled
circle — parked, asleep, on holiday. #90 stopped `TripTrackingService` and its
notification while parked but scoped `CircleNotifyService` out, so #90's AC 1 is
unmet for anyone in a circle.

iOS is not in this situation — `CircleNotifications` shows no persistent
notification and runs no background service. Its problem is the opposite:
**zero background delivery**. A circle arrival only surfaces while the app is
alive (live socket) or on the next foreground catch-up sweep.

## Goal

Replace "hold a socket open while backgrounded" with a **content-free FCM
wake-ping** that makes the device fetch from the API and post the notification
itself.

- Android: delete `CircleNotifyService` — the notification is gone by deletion.
- iOS: gain background delivery it never had, via a Notification Service
  Extension that fetches on wake.

## Decisions

Settled during brainstorming:

| # | Decision |
|---|----------|
| Platforms | Both Android and iOS. |
| Push payload | **Wake-ping only** — no event data in the message. Device wakes, calls `GET /events`, posts from that. Google/Apple see only "something happened for user X". |
| Socket gate | Drop the relay socket **whenever the app is backgrounded** and no convoy is joined. Foreground still uses the socket for instant delivery. |
| Android non-Play | Play Services assumed. FCM is the only Android transport. No UnifiedPush. |
| Per-circle mute | Stays **device-local**. Server pushes to every accepted member's devices; a muted device wakes, checks its local toggle, and silently does nothing. |
| Backend send path | **Approach A** — FCM HTTP v1 only. Android tokens direct; iOS tokens registered with Firebase, which relays to APNs. One integration, one credential type in the backend. |

### Approach A vs alternatives

- **A (chosen)** — FCM HTTP v1 only, Firebase relays to APNs. Least backend code,
  one credential type (a Google service-account JSON), one error model. Costs an
  APNs auth key uploaded to the Firebase console once.
- **B** — FCM for Android, direct APNs (HTTP/2 + JWT) from .NET for iOS. Two
  senders, two credential types, two retry models on a box maintained by one
  person. Only wins if zero Google involvement for iOS delivery is a requirement.
  It is not — the wake-ping is content-free either way.
- **C** — dedicated push microservice. Overkill at this user count and traffic.
  The async seam (§1.4) makes C a later swap, not a rewrite: it becomes
  justified only when many callers push, send volume threatens API latency
  beyond what an in-process queue absorbs, push needs its own deploy cadence,
  delivery guarantees require a durable queue, or the API scales to multiple
  instances needing coordinated rate-limiting. None are close.

## 1. Backend

### 1.1 New domain — `DeviceToken`

`Detour.Domain/Notifications/DeviceToken.cs`

| Field | Notes |
|-------|-------|
| `Id` Guid | |
| `UserId` Guid | FK to `User` |
| `Token` string | FCM registration token. iOS tokens are also FCM tokens — iOS registers through the Firebase SDK, not raw APNs. **Unique.** |
| `Platform` enum | `Android` / `Ios`. Informational; delivery path identical. |
| `CreatedAt`, `LastRefreshedAt` | |

`IDeviceTokenRepository`:

- `UpsertAsync(userId, token, platform)` — on `Token` conflict, reassign `UserId`
  (the install switched accounts) and bump `LastRefreshedAt`.
- `DeleteAsync(token)`
- `DeleteManyAsync(tokens)`
- `GetForUsersAsync(IEnumerable<Guid> userIds) -> List<(Guid userId, string token)>`

One EF migration.

### 1.2 New endpoints — `DevicesController`

Bearer-authed, like the rest of the API.

- `PUT /devices` body `{ token, platform }` → upsert for the current user.
  Idempotent. Client calls on sign-in, app start, and token rotation.
- `DELETE /devices` body `{ token }` → on sign-out. Server also prunes on send
  failure (§1.3).

### 1.3 `IPushSender` + `FcmPushSender`

`Detour.Api/Notifications/`

```
Task SendWakeAsync(IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
```

- `FcmPushSender` uses the `FirebaseAdmin` NuGet package →
  `FirebaseMessaging.SendEachForMulticastAsync`.
- **Content-free.** `data = { "type": "circle_wake" }`, no `notification` block
  (so Android `onMessageReceived` always runs, even backgrounded). Android
  `Priority = High`.
- iOS via the same call: FCM attaches `content-available: 1` for the background
  pull **plus** a minimal `alert` carrying a generic localized string
  ("New circle activity") so a throttled silent push still surfaces something —
  the Notification Service Extension (§3.2) replaces it.
- `collapseKey` / `apns-collapse-id` = circle id → a burst of arrivals in one
  circle coalesces to one wake.
- Per-token response handling: `UNREGISTERED` / `INVALID_ARGUMENT` → collect
  those tokens → `IDeviceTokenRepository.DeleteManyAsync`.
- `FirebaseApp.Create` once at startup from `Notifications:FirebaseCredentialsPath`
  (a service-account JSON). Registered singleton.

### 1.4 Async seam

`IPushSender` is invoked behind a `PushDispatchQueue` — a bounded
`Channel<PushJob>` drained by one `BackgroundService`. Nothing awaits FCM on the
request path. Queue full → drop + log; a lost wake-ping self-heals (the device
catches up on the next foreground sweep or the next event). This is the seam that
keeps approach C a later swap: replace the queue implementation with
Redis/RabbitMQ + a worker service behind the same interface if it is ever
warranted.

### 1.5 Hook into `RecordEventAsync`

`CircleService.cs:233`, inside the existing `postCommit.Schedule` block:

```csharp
liveRelay.PublishPlaceEvent(recipients, ...);            // unchanged
var offline = recipients.Except(liveRelay.ConnectedUserIds);
pushQueue.Enqueue(new PushJob(offline, collapseKey: groupId.ToString()));
```

Only recipients **not currently holding a socket** are pushed — connected ones
already got the live frame. `ILiveRelay.ConnectedUserIds` already exists. The
recipient set (accepted members minus the mover) is unchanged.

## 2. Android

### 2.1 Delete `CircleNotifyService` entirely

The service, its ongoing notification, its channel, its manifest `<service>`
entry, and the `android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING`
permission. #142's notification is removed by **deletion**, not suppression.
`ConvoyLiveService` is untouched — a joined convoy keeps its own
user-initiated foreground service and notification.

Per the staged-refactor rules, the deletion is its own commit, separate from the
extraction in §2.2.

### 2.2 Extract delivery logic → `CircleDelivery`

Move `refreshNotifyCircles`, `collectLiveEvents`, `collectCatchUpOnReconnect`,
and `catchUp` out of `CircleNotifyService` into `notif/CircleDelivery.kt` — a
plain class (no `Service` base), constructed with an application `Context`.
Two entry points:

- `syncAndCatchUp()` — one-shot: reconcile the notify-circle set, then `catchUp`
  per circle. Reuses `CircleNotifyPolicy`.
- `runLiveWhileForeground(scope)` — holds the relay socket and collects
  `placeEvents` for as long as `scope` is alive.

### 2.3 Foreground = socket, background = push

A `ProcessLifecycleOwner` observer in `DetourApplication`:

- `ON_START` → launch `CircleDelivery.runLiveWhileForeground` on a
  process-scoped job.
- `ON_STOP` → cancel it (the socket closes) + fire one `syncAndCatchUp()` to
  flush anything in flight.

No service, no notification, at any point.

### 2.4 `DetourMessagingService : FirebaseMessagingService`

Manifest entry, `exported="false"`.

- `onMessageReceived` → enqueue an **expedited `CoroutineWorker`** (WorkManager)
  that calls `CircleDelivery.syncAndCatchUp()`. WorkManager rather than a bare
  coroutine so a Doze-woken process about to be killed still gets its expedited
  window and a retry.
- `onNewToken` → `DeviceTokenRegistrar.register(token)`.

### 2.5 `DeviceTokenRegistrar`

`net/DeviceTokenRegistrar.kt`

- `register()` — `FirebaseMessaging.getInstance().token` →
  `PUT /devices { token, platform: "android" }` via the existing authed HTTP
  client. Called on app start (if signed in), on sign-in success, and from
  `onNewToken`.
- `unregister()` — `DELETE /devices { token }`, then
  `FirebaseMessaging.deleteToken()`. Called on sign-out.

### 2.6 Gradle

- `com.google.gms.google-services` plugin.
- `firebase-bom` + `firebase-messaging`.
- `google-services.json` in `app/` — gitignored, committed as
  `google-services.json.example`, the real file a CI secret.
- `firebase-analytics` explicitly excluded (it is pulled transitively otherwise).

### 2.7 Battery caveat

A high-priority data FCM message wakes the app from Doze — **but not** when the
app is in the `RESTRICTED` App Standby bucket or `isBackgroundRestricted()`.
Detect this (`UsageStatsManager.getAppStandbyBucket()`,
`ActivityManager.isBackgroundRestricted()`) and surface it as a row in circle
settings, reusing the "background delivery is off" mechanism from #145. This is
a dependency on #145's UI, not a blocker for this work.

## 3. iOS

### 3.1 APNs registration

- `UIApplication.shared.registerForRemoteNotifications()` after notification
  authorization is granted — hook the existing `requestAuthorizationIfNeeded`
  success path in `CircleNotifications`.
- `AppDelegate.didRegisterForRemoteNotificationsWithDeviceToken` →
  `Messaging.messaging().apnsToken = deviceToken`, then `Messaging.messaging().token`
  → `PUT /devices { token, platform: "ios" }`.
- `MessagingDelegate.didReceiveRegistrationToken` → re-`PUT` on rotation.
- `FirebaseMessaging` added via SPM (that product only).

### 3.2 Notification Service Extension — new target `DetourNotificationService`

- Receives the FCM alert push (`mutable-content: 1`). ~30 s budget.
- Reads the auth token and `lastSeenEventTsMs` from a shared **App Group**
  container `group.io.github.maxke24.detour` — a new entitlement on the app and
  the extension. `Settings` and `CircleEvents` in `:shared` must use the App
  Group `UserDefaults` suite on iOS rather than `.standard`, so the app and the
  extension agree (a small `:shared` change — inject the suite name on iOS).
- Fetches `GET /events` per notify-circle since `lastSeen`, builds the body with
  the same `PlaceEvent.notificationText()` used today, replaces
  `bestAttemptContent.body`, advances `lastSeen`.
- Fetch fails or times out → the generic "New circle activity" body from the
  push survives. Acceptable degradation.

### 3.3 Foreground path unchanged

`CircleNotifications.runCatchUpSweep` plus the live socket while the app is
active stay exactly as they are.

### 3.4 Prerequisites (logistics, not code)

- **Apple Developer Program membership — $99/yr.** Blocker for the iOS half
  only; the Android half ships without it.
- APNs auth key (`.p8`) created in the Apple Developer portal, uploaded to the
  Firebase console.
- Xcode capabilities: Push Notifications, Background Modes (remote
  notifications), App Groups — on the app **and** the extension target.

## 4. Cross-cutting

### 4.1 Firebase project

One new Firebase project for Detour, Cloud Messaging API (v1) enabled. The
service-account key goes onto CT125 at the path named by
`Notifications:FirebaseCredentialsPath`, wired into `docker-compose.lan.yml`'s
env on the box (a server-only file — see the self-host topology note). The GHCR
image build needs only the NuGet restore, no secret.

### 4.2 Docs

- `docs/CIRCLES_AND_CONVOYS.md` — the coverage/cadence table gains a
  "backgrounded" column (was: nothing → now: FCM wake-ping).
- New `docs/PUSH.md` — the transport, the token lifecycle, the wake-ping
  contract, the Firebase project pointer, and the iOS manual-test steps.
- `PLAY_LOCATION_DECLARATION.md` — unaffected (no new location use).

### 4.3 Privacy / store

The FCM registration token is a device identifier. Play Data Safety and the App
Store privacy label are updated: "Device or other IDs", collected, not shared,
for app functionality. The wake-ping carries no user content.

### 4.4 Versioning

New feature, backward compatible → **minor bump: `versionName` 1.98.0 → 1.99.0**
(`app/build.gradle.kts:80`). The wire is additive (new endpoints, new
`data.type` value); nothing breaks.

### 4.5 Testing

- **Backend** — `FcmPushSender` against a fake `FirebaseMessaging` (stale-token
  pruning path, per-token error split). `PushDispatchQueue` drop-when-full.
  `RecordEventAsync` asserts a push is enqueued only for non-connected
  recipients. Existing service-test harness.
- **Android** — `CircleDelivery.syncAndCatchUp` seam test (the policy itself is
  already covered by `CircleNotifyPolicyTest` — keep it). `DeviceTokenRegistrar`
  against a fake HTTP client. `DetourMessagingService` enqueues the worker.
- **iOS** — no test target exists (`iosApp/project.yml` defines none). The
  extension gets manual steps in `docs/PUSH.md`.
- **E2E (manual, documented)** — two devices, one circle, background the
  receiver, sender crosses a geofence → receiver is notified within N seconds.

### 4.6 Rollout order (also the implementation-plan stages)

1. **Backend** — token registry + `IPushSender` + `FcmPushSender` + queue +
   `RecordEventAsync` hook. Ships dark (no clients register yet).
2. **Android** — `DeviceTokenRegistrar` + `DetourMessagingService` +
   `CircleDelivery` extraction + delete `CircleNotifyService` + the lifecycle
   observer.
3. **iOS** — APNs registration + App Group + Notification Service Extension.

Stages 2 and 3 are independent once stage 1 lands. Each is its own PR.

### 4.7 Human-only steps

Code, migrations, gradle/SPM/`project.yml` edits, `.example` credential stubs,
unit tests, and docs are all in scope for implementation. These are not:

**Unblocks stage 1:**

1. Create a Firebase project for Detour; enable Cloud Messaging API (v1).
2. Generate a service-account key (Project Settings → Service accounts). Provide
   the JSON.

**Unblocks stage 2:**

3. Register the Android app in the Firebase project for bundle IDs
   `io.github.maxke24.detour`, `io.github.maxke24.detour.debug`,
   `io.github.maxke24.detour.automotive`. Download `google-services.json`.
4. Decide CI-secret vs env-var wiring for `google-services.json`; provide the
   file.

**Unblocks stage 3:**

5. Buy / renew the Apple Developer Program membership ($99/yr).
6. Create an APNs auth key (`.p8`) in the Apple Developer portal. Note Key ID +
   Team ID.
7. Upload the `.p8` (+ Key ID + Team ID) to the Firebase console → Cloud
   Messaging → Apple app configuration.
8. Register the iOS app in Firebase (`io.github.maxke24.detour`); download
   `GoogleService-Info.plist`.
9. In the Apple Developer portal: enable Push Notifications on the App ID; create
   App Group `group.io.github.maxke24.detour`; attach it to the app App ID and a
   new extension App ID.
10. In Xcode: add the Notification Service Extension target (the `project.yml`
    diff is in scope; running xcodegen and confirming signing is not), enable
    capabilities on both targets.

**Before deploy:**

11. Place the service-account JSON on CT125; add
    `Notifications__FirebaseCredentialsPath` + mount to `docker-compose.lan.yml`
    on the box.
12. Rebuild and redeploy `detour-api:local` on CT125 after the backend PR merges
    — no auto-deploy (the stale-image trap).

**Before ship:**

13. Update the Play Data Safety form (Device IDs collected).
14. Update the App Store privacy nutrition label.
15. Run the manual 2-device E2E.
16. Run the iOS Notification Service Extension manual test.

Critical path: step 2 unblocks everything. Steps 5–9 run in parallel and gate
iOS only.

## 5. Not in scope

- No UnifiedPush / non-Play Android path (decision Q4).
- No server-side mute — a muted device still wakes, then stays silent (decision
  Q5). A follow-up issue if wake noise proves real in practice.
- No payload in the push message (decision Q2).
- Convoy delivery is untouched — it is foreground-only already.
- Does not fix #144 (dormancy vs. stale activity-recognition registration) or
  #146 (redundant dormancy evaluations). Reuses #145's UI mechanism for the
  restricted-bucket warning (§2.7).
