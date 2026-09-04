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

This document covers the transport that replaces both: a content-free push
wake-ping, sent to each phone through its own platform cloud. The backend half
ships first and dark — no client registers a token yet — so nothing in the app
changes until the Android and iOS stages land, and `CircleNotifyService` is not
removed until then.

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

The backend talks to **both, natively** — one gateway per cloud, each mapping 1:1
to the API its vendor documents:

- **Android → FCM HTTP v1.** Mint an OAuth2 bearer from a Google service-account
  key, `POST` to `messages:send`.
- **iOS → APNs directly.** Sign an ES256 provider JWT from an Apple `.p8` auth
  key, `POST` to `/3/device/{token}` over HTTP/2.

Both share the same shape — a key, a cached bearer, a per-token POST, a
dead-token list to prune — which is the interface `IPushGateway`; the two clouds
are its two implementations. There is **no Firebase relay and no `FirebaseAdmin`
SDK**: the backend never routes an iOS push through Google. That keeps each path
readable against its own vendor's docs (an iOS failure is Apple's real error, not
Google's translation of it), lets the two fail independently, and makes the
per-token classification unit-testable — a plain HTTP response, not an SDK type
with internal constructors.

## 2. The wake-ping carries nothing

The message is a wake signal, not a notification. On Android it contains:

```
data: { "type": "circle_wake" }
```

and no `notification` block, so `onMessageReceived` runs for every wake-ping,
foreground or background, rather than the system drawing a notification from the
payload. The device is expected to call `GET /api/circles/{id}/events?since=…`,
decide from the feed what to say, and post the notification itself.

Google and Apple therefore see only "something happened for user X" — no circle
name, no place, no other member, no event kind.

`collapse_key` (FCM) and `apns-collapse-id` (APNs) are both set to the circle id,
so a burst of arrivals in one circle coalesces into a single wake rather than a
stack of them.

### The iOS alert body is a placeholder

iOS throttles pure silent pushes, so the APNs message carries a minimal visible
`alert` with `content-available: 1` and `mutable-content: 1`. The alert body is a
fixed English constant, `"New circle activity"`, hard-coded in `ApnsGateway`. It
is never localised there — the client owns copy. The Notification Service
Extension (§7) rewrites it after it has fetched the real event; the constant is
only what survives if that fetch does not finish in time.

## 3. Token lifecycle

One row per install, in `DeviceToken` (`Detour.Domain/Notifications/`), unique
on `Token`.

| Call | When the client makes it |
|---|---|
| `PUT /api/devices` body `{ token, platform }` | Sign-in, app start, and whenever the push service rotates the token. Idempotent. |
| `DELETE /api/devices` body `{ token }` | Sign-out. Quiet 204 even for a token that was never registered. |

Both are bearer-authed like the rest of the API. `platform` is `android` or
`ios`, and it is **not** informational: it decides which cloud the token is sent
through. An Android token is an FCM registration token; an iOS token is a raw
APNs device token. Registering with the wrong platform sends the token to the
wrong cloud, which rejects it.

`PUT` is an upsert keyed on the token. If the token is already registered to a
different rider — the same install signed into a new account — the row is
reassigned to the caller rather than duplicated. An install has exactly one
token; a token has exactly one owner.

The server also prunes tokens on its own. When a cloud reports a token
permanently dead — FCM `UNREGISTERED` / `INVALID_ARGUMENT`, APNs `410
Unregistered` / `400 BadDeviceToken` — the dispatcher deletes that row
(`IDeviceTokenRepository.DeleteByTokensAsync`). A transient failure (auth, quota,
transport, `5xx`) prunes nothing, because the tokens may be fine.

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

`ILiveRelay.ConnectedUserIds` is process-local: with more than one API instance
a member socketed to another instance looks offline here and gets a redundant
wake-ping. Harmless — the device dedupes on `lastSeenEventTsMs` and the collapse
key coalesces the duplicate.

From the queue:

```
IPushQueue (bounded Channel)
  └─ PushDispatchWorker (BackgroundService, drains one job at a time)
       └─ PushDispatcher
            ├─ IDeviceTokenRepository.GetForUsersAsync(offline)  → (token, platform) rows
            ├─ group by platform → the IPushGateway whose Platform matches
            │     ├─ Android → FcmGateway.SendWakeAsync(tokens, circleId)
            │     └─ iOS     → ApnsGateway.SendWakeAsync(tokens, circleId)
            └─ prune every token the gateways reported dead
```

A platform with no configured gateway (e.g. APNs credentials not yet placed) is
logged and skipped, not fatal — the other platform's devices still wake.

Nothing on the request path awaits a cloud. The queue is a `Channel<PushJob>`
bounded at `Notifications:QueueCapacity` (default 1024) with `FullMode.Wait`, so
when it is full `TryEnqueue` returns `false` and the job is dropped — not
blocked, not silently accepted. A dropped wake-ping is not an error: the device
reconciles on its next foreground catch-up sweep. The same is true of a job the
worker fails to dispatch — it logs and moves to the next one.

This queue is the seam that keeps a dedicated push microservice a later swap
rather than a rewrite: replace the `Channel` with Redis or RabbitMQ behind
`IPushQueue` if it is ever warranted. It is not close.

## 5. Configuration

Android (FCM) and iOS (APNs) are configured independently. Either can be unset;
that platform's gateway then logs one warning at startup and no-ops, while the
other keeps working. Both unset is the correct state for a deployment that has
not been handed credentials — the rest of the app is unaffected, circles still
deliver over the socket and on the foreground sweep exactly as before.

| Key | Notes |
|---|---|
| `Notifications:FirebaseCredentialsPath` | Absolute path to the Google service-account JSON. The project id is read out of it. Unset ⇒ Android sends no-op. Set but unreadable / malformed ⇒ throws at startup and fails the deploy. |
| `Notifications:ApnsKeyPath` | Absolute path to the APNs auth key (`.p8`, an EC P-256 private key). Unset (or any of the three ids below unset) ⇒ iOS sends no-op. Set but unreadable ⇒ throws at startup. |
| `Notifications:ApnsKeyId` | The Key ID of the `.p8` (Apple Developer → Keys). |
| `Notifications:ApnsTeamId` | The Apple Developer Team ID. |
| `Notifications:ApnsTopic` | The app's bundle id, sent as `apns-topic`. |
| `Notifications:ApnsUseSandbox` | `true` to target Apple's sandbox host (development APNs tokens). Default `false` (production). |
| `Notifications:QueueCapacity` | Bounded queue size. Default 1024. |

Environment-variable form, as everywhere else in the backend, doubles the
separator: `Notifications__ApnsKeyPath`. See
[`backend/INSTALL.md`](../backend/INSTALL.md).

## 6. Cloud setup

The backend needs one credential per platform. Neither touches the container
image build; both live on the box (or its secret store).

**Android — a Firebase project** with the Cloud Messaging API (v1) enabled and
the Android app registered for `io.github.maxke24.detour` and its two suffixed
applicationIds, `io.github.maxke24.detour.debug` and
`io.github.maxke24.detour.automotive`. Take a service-account key (Project
Settings → Service accounts) and place it at `Notifications:FirebaseCredentialsPath`.
That is all Android needs — no APNs key is uploaded to the Firebase console,
because Firebase never relays iOS here.

**iOS — an APNs auth key.** In the Apple Developer account create a Keys entry
with the Apple Push Notifications service enabled, download its `.p8`, and note
the Key ID and your Team ID. Place the `.p8` at `Notifications:ApnsKeyPath` and
set `ApnsKeyId`, `ApnsTeamId`, and `ApnsTopic` (the bundle id). One key signs for
every environment; `ApnsUseSandbox` picks the host, not the key.

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
physical devices and the live FCM and APNs infrastructure. Everything else is
unit-tested, and going native (rather than relaying through `FirebaseAdmin`) is
what makes the last piece testable at all:

- the queue's drop-when-full,
- the dispatcher routing each platform's tokens to its own gateway and pruning
  the dead ones from both,
- **each gateway's per-token classification** — which HTTP status prunes and
  which is transient — driven by a stub `HttpMessageHandler`. Under the old
  FCM-relay design this path could not be tested, because `BatchResponse` /
  `SendResponse` have internal constructors and a fake response could not be
  built. A plain HTTP response can.
- `CachedJwt`'s refresh boundary (via an injected clock), and that `ApnsGateway`
  signs a real ES256 JWT from a generated key.

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
- **The backend does not evaluate geofences.** Arrivals are decided on the device
  (§8 of CIRCLES_AND_CONVOYS.md); the backend only couriers the wake.
