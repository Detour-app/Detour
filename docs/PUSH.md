# Push: the circle wake-ping

How a backgrounded phone finds out about a circle arrival.

Circles already deliver arrivals two ways: over the live relay socket while the
app is foregrounded (§6.3 of [CIRCLES_AND_CONVOYS.md](CIRCLES_AND_CONVOYS.md)),
and on a catch-up sweep the next time the app is opened. Between those two there
is a hole. On Android it is filled by `CircleNotifyService`, a foreground
service that holds the socket open all day — which means a permanent "Watching
your circles for arrivals and departures" notification for anyone signed in with
one notify-enabled circle (issue #142). On iOS it is not filled at all: a circle
arrival does not surface until the app is next opened.

This document covers the transport that replaces both: a content-free FCM
wake-ping. The backend half ships first and dark — no client registers a token
yet — so nothing in the app changes until the Android and iOS stages land, and
`CircleNotifyService` is not removed until then. The design and rollout are in
[the spec](superpowers/specs/2026-09-04-circle-push-wake-design.md).

---

## 1. Why a courier is needed at all

Once the OS moves an app to the background it is on a clock. The process is
frozen, then suspended, and its open sockets are torn down. A backgrounded app
cannot hold a connection waiting for the server to say something happened,
because after a few minutes there is no connection and no running code to hold
it.

The platform push channels are the exception. FCM on Android and APNs on iOS
run in a system process that is always up, and a message delivered to them wakes
the target app for a short, OS-granted window even when it is suspended. They are
the only channels that still reach a frozen app.

The backend talks to exactly one of them. Android tokens go to FCM directly.
iOS tokens are also FCM tokens — an iOS app registers for push through the
Firebase SDK, not raw APNs — and Firebase relays those messages to APNs on the
backend's behalf. One integration, one credential type (a Google
service-account JSON), one error model. The APNs auth key that lets Firebase do
the relay is uploaded to the Firebase console once and never touches the
backend.

## 2. The wake-ping carries nothing

The message is a wake signal, not a notification. It contains:

```
data: { "type": "circle_wake" }
```

and no `notification` block. On Android that means `onMessageReceived` runs for
every wake-ping, foreground or background, rather than the system drawing a
notification from the payload. The device is expected to call
`GET /api/circles/{id}/events?since=…`, decide from the feed what to say, and
post the notification itself.

Google and Apple therefore see only "something happened for user X" — no circle
name, no place, no other member, no event kind.

`collapseKey` (Android) and `apns-collapse-id` (iOS) are both set to the circle
id, so a burst of arrivals in one circle coalesces into a single wake rather
than a stack of them.

### The iOS alert body is a placeholder

iOS throttles pure silent pushes, so the message to an iOS token also carries a
minimal visible `alert` with `content-available: 1` and `mutable-content: 1`.
The alert body is a fixed English constant, `"New circle activity"`, hard-coded
in `FcmGateway`. It is never localised there — the client owns copy. The
Notification Service Extension (§7) rewrites it after it has fetched the real
event; the constant is only what survives if that fetch does not finish in time.

## 3. Token lifecycle

One row per install, in `DeviceToken` (`Detour.Domain/Notifications/`), unique
on `Token`.

| Call | When the client makes it |
|---|---|
| `PUT /api/devices` body `{ token, platform }` | Sign-in, app start, and whenever the push service rotates the token. Idempotent. |
| `DELETE /api/devices` body `{ token }` | Sign-out. Quiet 204 even for a token that was never registered. |

Both are bearer-authed like the rest of the API. `platform` is `android` or
`ios` and is informational only — the delivery path is identical.

`PUT` is an upsert keyed on the token. If the token is already registered to a
different rider — the same install signed into a new account — the row is
reassigned to the caller rather than duplicated. An install has exactly one
token; a token has exactly one owner.

The server also prunes tokens on its own. When FCM reports `UNREGISTERED` or
`INVALID_ARGUMENT` for a token in a send, the dispatcher deletes that row
(`IDeviceTokenRepository.DeleteByTokensAsync`). A whole-batch failure — auth,
quota, transport — prunes nothing, because the tokens may be fine.

## 4. The server path

Delivery is fanned out from `CircleService.RecordEventAsync`, in the existing
post-commit block — scheduled only once the `place_events` row is durable, so a
peer that reacts by re-reading the feed can never find nothing there, and a
transaction that rolls back never announces an arrival that did not happen.

```
RecordEventAsync
  └─ post-commit:
       liveRelay.PublishPlaceEvent(recipients, …)          ← connected members, instant
       offline = recipients − liveRelay.ConnectedUserIds
       pushQueue.TryEnqueue(new PushJob(offline, circleId)) ← everyone else
```

`recipients` is the accepted members of the circle minus the mover. Members
currently holding a relay socket already got the live `place_event` frame, so
only the rest are pushed. A socket the relay has not yet noticed is dead just
means a redundant wake-ping, which the device dedupes on `lastSeenEventTsMs`.

From the queue:

```
IPushQueue (bounded Channel)
  └─ PushDispatchWorker (BackgroundService, drains one job at a time)
       └─ PushDispatcher
            ├─ IDeviceTokenRepository.GetForUsersAsync(offline)  → tokens
            ├─ IFcmGateway.SendWakeAsync(tokens, circleId)
            └─ prune tokens FCM reported UNREGISTERED / INVALID_ARGUMENT
```

Nothing on the request path awaits FCM. The queue is a `Channel<PushJob>`
bounded at `Notifications:QueueCapacity` (default 1024) with `FullMode.Wait`, so
when it is full `TryEnqueue` returns `false` and the job is dropped — not
blocked, not silently accepted. A dropped wake-ping is not an error: the device
reconciles on its next foreground catch-up sweep. The same is true of a job the
worker fails to dispatch — it logs and moves to the next one.

This queue is the seam that keeps a dedicated push service (spec approach C) a
later swap rather than a rewrite: replace the `Channel` with Redis or RabbitMQ
behind `IPushQueue` if it is ever warranted. It is not close.

## 5. Configuration

| Key | Notes |
|---|---|
| `Notifications:FirebaseCredentialsPath` | Absolute path to the Firebase service-account JSON. Unset ⇒ the gateway logs one warning at startup and every send no-ops. |
| `Notifications:QueueCapacity` | Bounded queue size. Default 1024. |

An unset `FirebaseCredentialsPath` is the correct state for any deployment that
has not been handed a key — including this one until the key is placed on the
box. The rest of the app is unaffected: circle arrivals still deliver over the
socket and on the foreground sweep, exactly as before this transport existed.

Environment-variable form, as everywhere else in the backend:
`Notifications__FirebaseCredentialsPath`. See
[`backend/INSTALL.md`](../backend/INSTALL.md).

## 6. The Firebase project

One project, id `detour-1229f`, with the Cloud Messaging API (v1) enabled. The
setup it needs, per the spec's human-only steps:

- Android and iOS apps registered in it for `io.github.maxke24.detour` and its
  two suffixed applicationIds, `io.github.maxke24.detour.debug` and
  `io.github.maxke24.detour.automotive`.
- An APNs auth key (`.p8`, plus its Key ID and Team ID) uploaded to the console
  under Cloud Messaging → Apple app configuration — that is what lets Firebase
  relay to APNs. Gates the iOS stage only.
- A service-account key (Project Settings → Service accounts) placed at
  `Notifications:FirebaseCredentialsPath` on the box. That is all the backend
  needs; the container image build does not need it.

## 7. The iOS Notification Service Extension

Landing in the iOS stage, not this one. When the extension receives a wake-ping
(`mutable-content: 1`) it has roughly 30 seconds to:

1. read the auth token and last-seen event timestamp from the shared App Group
   container,
2. fetch `GET /events` per notify-circle since that timestamp,
3. rewrite the notification body from the real event, using the same
   `PlaceEvent.notificationText()` the app uses, and advance the last-seen
   timestamp.

If the fetch fails or times out, the placeholder `"New circle activity"` body
from §2 is what the user sees. That is the accepted degradation, not a bug.

## 8. Testing

There is no automated end-to-end test for the real courier — it needs two
physical devices and the live FCM and APNs infrastructure. The unit tests cover
the pieces around it (the gateway's stale-token pruning and per-token error
split, the queue's drop-when-full, and that `RecordEventAsync` enqueues a push
only for non-connected recipients).

The manual E2E:

1. Two devices, one circle, notify toggle on for both.
2. Fully background the receiver — swipe it away.
3. On the sender, cross a circle-place geofence — physically, or by replaying a
   route through `tools/mocklocation` so the geofence evaluator fires at a desk.
4. The receiver posts the arrival notification within a few seconds, without
   being opened.

For iOS specifically, repeat with the sender's fetch made to fail (airplane
mode on the receiver after the wake arrives, or a bad API host) and confirm the
generic `"New circle activity"` body still appears.

## 9. What this deliberately does not do

- **No payload in the push.** The message is a wake signal; the device fetches
  the event. This is a privacy decision, not a size one — see §2.
- **No server-side per-circle mute.** The server pushes to every accepted
  member's devices. A device that has the circle muted locally wakes, checks its
  own toggle, and stays silent. A muted device still spends its wake budget; a
  follow-up issue if that proves to matter in practice.
- **No UnifiedPush or other non-Play Android path.** Play Services and FCM are
  assumed. FCM is the only Android transport.
- **The backend does not evaluate geofences or talk to APNs directly.** Arrivals
  are decided on the device (§8 of CIRCLES_AND_CONVOYS.md); APNs is reached only
  through Firebase.
