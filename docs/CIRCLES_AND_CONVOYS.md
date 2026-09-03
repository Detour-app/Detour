# Circles and convoys: one mechanism, two policies

How the two group features work, as built. Code across the backend and both apps
cites this file **by section number** — `docs/CIRCLES_AND_CONVOYS.md section 6`
and so on — so sections are stable; add at the end rather than renumbering.

Companion documents: the service's own rules are in
[BACKEND_SPEC.md](BACKEND_SPEC.md) (`spec §11` in backend comments is its live
relay section, not §11 here), and the wire vocabulary is mirrored in
`backend/Detour/Detour.Api/Live/LiveFrames.cs`. Where this file and that file
disagree, the code wins and this file is the bug.

---

## 1. The idea

A convoy and a circle are the same thing wearing different policy.

Both are a named group with a membership table, gated on friendship, with a live
position feed. What separates them is not structure but policy: how long the
group lives, whether anything is written down, how often a phone speaks, and
whether voice is allowed at all.

Building them as one entity was a decision, not an accident, and it comes with a
rule that keeps it honest: **if shared code has to ask "is this a convoy?" in
more than about three places, the merge has gone one layer too deep.** Today the
count is two, both in the relay — the voice gate and the destination-vote gate —
with every other difference expressed as data.

## 2. The two, side by side

|  | Convoy | Circle |
|---|---|---|
| For | A ride together | Family, housemates — who is where |
| Lifetime | Dies when the last member leaves | Persists, including while you are alone in it |
| Cadence | Seconds | Minutes |
| Position history | Nothing stored, ever | Latest fix per member, overwritten in place |
| Voice | Allowed by the rules; not carried today (§6.4) | **Never** |
| Shared destination vote | Yes | **No** |
| Sharing switch | Connected means sharing | Opt-in per member, pausable |
| Shared places, arrivals | — | Yes |

**Both are invite-only, and an invite requires an accepted friendship.** That is
what makes membership mean "granted access" rather than "found the room". It is
also why the friends screen and the circles screen are two views of one
relationship: you cannot be in a group with someone you are not friends with, so
un-friending someone is not a partial withdrawal.

Neither ever exposes a member's trips, traces or map. A group shares live
position, and for circles a last-known position and arrival events. That is all.

## 3. The data model

One entity, discriminated by kind, with policy carried as columns rather than as
`if kind ==` branches in the handlers:

| Table | Holds |
|---|---|
| `groups` | id, kind (`convoy` / `circle`), name, owner, created |
| `group_members` | group, member, status (`invited` / `accepted`), joined, `is_sharing` |
| `member_fixes` | one row per (group, member): lat, lon, accuracy, instant. Circles only. |
| `circle_places` | a place shared into a circle by one member: identifier, name, radius |
| `place_events` | arrival/departure records, newest-N per circle |

Two things carry the whole product distinction, and neither is an `if` in a
handler:

- **`GroupKind.DropWhenEmpty`** — the kind is a `SmartEnum` stored by name, and
  it *answers* whether an empty group should go, rather than the leave path
  asking what kind it holds. Empty-group deletion is the single most likely place
  for a shared handler to silently break circles: someone leaves your family
  circle, you are alone in it, and it evaporates. Asking the kind means the leave
  path has one behaviour and cannot forget a case.
- **`is_sharing` on the membership, not on the group.** Pausing is per person per
  circle. On the group it would make one member's pause everybody's.

`member_fixes` is one row per member, overwritten in place. No history table, no
trail — see §8.

Because kinds are stored by name, **reordering the enum members must never
silently remap existing rows** — the same rule as every other `SmartEnum` in the
backend.

## 4. The API surface

Two endpoint namespaces over one implementation, so the API reads by intent and
per-kind rules have an obvious home.

| Path | Notes |
|---|---|
| `POST`/`GET` `/api/convoys` · `/api/circles` | Create, list. Kind comes from the path. |
| `POST /api/groups/{id}/invitations` | Membership *and* friendship required |
| `POST /api/groups/{id}/invitations/respond` | Accept or decline |
| `DELETE /api/groups/{id}/membership` | Leave; drops live sockets and the leaver's places and last fix |
| `PUT /api/circles/{id}/sharing` | Circles only — a convoy id answers 404, not "not applicable" |
| `POST`/`GET` `/api/circles/{id}/positions` | The low-cadence path (§10) |
| `POST`/`GET` `/api/circles/{id}/places` · `DELETE /api/circle-places/{id}` | Owner-only delete |
| `POST`/`GET` `/api/circles/{id}/events` | Arrivals and departures |
| `POST /api/me/fix` | One fix, fanned out to every group the caller shares with |
| `GET /api/live` | The WebSocket upgrade (§6) |

A group id that does not exist and a group you are not in produce the **same**
answer, so ids cannot be enumerated.

> **Path naming, learned the hard way.** Do not name anything under `/route…`.
> A public hostname sharing a tunnel with GraphHopper matches `/route` as a
> *prefix*, so `/routes/*` never reaches this service — it answers 404 from
> GraphHopper while the identical path returns 401 correctly on localhost. That
> is why route sharing lives at `/shared-routes/*`. Verify every new endpoint
> through the public hostname, not just the box.

## 5. The clients

| File | Role |
|---|---|
| `shared/…/data/Groups.kt` | Membership calls for both kinds, taking the kind as a parameter |
| `shared/…/data/CircleFixes.kt` | The low-cadence position path |
| `shared/…/data/CircleEvents.kt` | Arrival/departure feed and the on-device geofence evaluator |
| `shared/…/data/CirclePresence.kt` | The presence tick both platforms run: the guards, the sharing filter, the evaluator lifecycle, the trust check and the cadence. Takes its three clocks as parameters — the file's own KDoc, "The three clocks", says why they must never collapse into fewer. |
| `shared/…/data/CircleNotifyPolicy.kt` | Which circles want delivery, and which caught-up arrivals are worth raising (the cap, the stale window, and newest-first *selection*, which both platforms then deliver in reverse — see its KDoc). Decisions only; the wording and the delivery are elsewhere. |
| `shared/…/drive/RelayProtocol.kt` | The wire codec — decodes the nine inbound frame types, builds the seven outbound ones. Pure, no socket, no state. |
| `shared/…/drive/ConvoyRelay.kt` | The relay's state machine — peers, push-to-talk membership, the spin vote, connect/backoff/reconnect — behind a `RelaySocket` seam. One implementation both platforms run, not two hand-rolled copies. |
| `shared/…/drive/RelaySocket.kt` | The seam: open/receive/send/close, with URL and bearer resolution left to whoever implements it |
| `app/…/net/OkHttpRelaySocket.kt` | Android's `RelaySocket`, over OkHttp's `WebSocket` |
| `app/…/net/ConvoyLiveClient.kt` | Android glue around `ConvoyRelay`: the `Features.liveRelay`/no-server guards, run-loop wiring, location forwarding |
| `app/…/convoy/ConvoyLiveService.kt` | Foreground service holding the socket while the screen is off |
| `app/…/tracking/TripTrackingService.kt` | Drives the circle tick — one collector, two sinks (§10). Owns the loop and the monotonic fix age; the decisions are `CirclePresence`'s. |
| `iosApp/Detour/UrlSessionRelaySocket.swift` | iOS's `RelaySocket`, over `URLSessionWebSocketTask` |
| `iosApp/Detour/ConvoyLiveClient.swift` | iOS glue around `ConvoyRelay`, the same shape as the Android object above, `ObservableObject`-published for SwiftUI |
| `iosApp/Detour/CircleSync.swift` | The same, on iOS. Its fix age is monotonic: `CLLocation` carries no uptime-stamped time, so `LocationBroadcast` stamps each fix against `ProcessInfo.systemUptime` on receipt, back-dated by the delivery lag. One residual gap versus Android — `systemUptime` does not advance across device sleep, so a fix held over a suspend reads younger than it is. |
| `app/…/notif/CircleNotifyService.kt`, `PlaceNotifications.kt` | Android's notification delivery: the foreground service, the channel, the `PendingIntent`. Policy comes from `CircleNotifyPolicy`. |
| `iosApp/Detour/CircleNotifications.swift` | iOS's delivery: `UNUserNotificationCenter`, authorization, the foreground catch-up sweep. Same policy source. |

The two UIs stay entirely separate. A circle screen and a convoy screen have
almost nothing visually in common, and merging them would be the one merge with
no payoff.

## 6. The live relay, on the wire

One WebSocket at `/api/live`, authenticated by the same rider token as every
REST call — an ordinary endpoint on the ordinary port, not a second listener.

### 6.1 One socket, many groups

A rider in a circle all day who also starts a convoy for a ride needs both at
once, so **joining adds a membership rather than replacing it**:

```
socket ──joined = {circle 2, convoy 7}──┬──► convoy 7    seconds
rider 3                                 │
                                        └──► circle 2    minutes
```

Consequences worth knowing before touching it:

- Each (group, member) pairing is validated independently. A connection can go
  stale in one group and stay valid in another, and is closed only when it holds
  no valid membership anywhere.
- A second connection for the same rider in the same group closes the first,
  rather than leaving a ghost that keeps receiving forever.
- Leaving a group drops that membership instantly; a periodic sweep re-validates
  every open connection, so a revocation from elsewhere lands within seconds.

### 6.2 Frames, client to server

Every frame is one JSON object with a `type`. A malformed frame is dropped
rather than closing an otherwise fine connection, and a client that floods has
frames dropped silently — telling it which ones it lost would be a second
channel to flood.

| `type` | Keys | Rules |
|---|---|---|
| `join` | `groupId` | Must be an accepted membership. Refusal comes back as `error`, worded the same whether the group is missing or you are not in it. |
| `location` | `lat`, `lon`, optional `accuracyM`, `headingDeg`, `speedKmh`, `ts` | **No `groupId`.** One fix is one fix; fanning it out to every group the sender shares with is the relay's job, not the phone's. Dropped entirely if the sender has paused sharing. In a circle it also overwrites the sender's stored last fix. |
| `spin_offer` | `groupId`, `candidates[]` of `{lat, lon, distanceM?, durationS?, name?}` | **Convoy only.** 1–3 candidates. One invalid candidate voids the whole frame — a rider must never vote on a sheet missing an option their peers can see. |
| `spin_vote` | `groupId`, `index` | **Convoy only.** Index 0–2; anything else is dropped rather than relayed as a vote for a candidate nobody offered. |

Voice frames (`ptt_start`, `ptt_audio`, `ptt_end`) are accepted off the wire and
dropped, exactly as an unknown type is — see §6.4.

None of these frames name the sender at all — the socket is already
authenticated to one rider, so there is nothing to carry. The account id only
shows up going the other way, in §6.3.

### 6.3 Frames, server to client

| `type` | Keys |
|---|---|
| `joined` | `groupId` |
| `error` | `message` — the client closes on this rather than sitting connected but never joined |
| `positions` | `peers[]` of `{u, lat, lon, h?, s?, ts, ttl}` |
| `left` | `user` |
| `spin_offer` / `spin_vote` | the client's frame plus `groupId` and `user` |
| `place_event` | `groupId`, `user`, `placeId`, `placeName`, `kind`, `ts` |

Position keys are abbreviated and nothing else is: a position goes out several
times a minute per peer, multiplied by peers × riders, while every other frame
is rare enough that clarity is free.

**`u` on a peer inside `positions`, and `user` on `left`, `spin_offer`,
`spin_vote` and `place_event`, all carry an account id — not a handle.** The
keys did not change when this landed; only the type behind them did (`u` was a
username string, and is now a `Guid`). `positions` in particular carries no
handle anywhere in the frame: a peer's display label comes from the group's
membership, which the client already holds from having joined it, so it is not
worth repeating on the one frame that goes out several times a minute per
peer.

**`ttl` is per peer, not a client-side constant.** A convoy rider and a circle
member arrive on the same stream at wildly different cadences — 20 seconds for a
fix that came over a socket, 300 for one posted over HTTP — and a single
hardcoded staleness window either flickers circle members off the map between
updates or leaves a dropped convoy rider frozen on it.

**`place_event` is server-originated only.** A client cannot cause one by
sending it, which is why there is no inbound counterpart. It is also why there is
no push notification anywhere in this project: arrivals reach an open app through
this frame and a closed one by polling the feed, and adding real push would mean
device tokens, a vendor service and, for iOS, a paid developer account.

### 6.4 Voice

Push-to-talk is **not carried today**. The rules for it stand — convoy only,
rejected for circles, one chunk bounded in size — but the relay drops the frames.

What comes back will be Opus over binary frames. Raw 16 kHz PCM base64'd into
JSON cost roughly 40 KB/s per talker per listener, which is what made it worth
deferring rather than porting. Both apps read this from one shared feature flag,
so neither can quietly disagree with the other about what works.

### 6.5 The gate that matters most

**Voice and destination votes are gated on kind, server-side, per frame.**
Hiding a button in the UI is not the fix: if `ptt_*` or `spin_*` were relayed for
any group a socket has joined, every circle would silently gain always-on voice
between people who signed up for a dot on a map. The check is re-run per frame
rather than cached from join time — stricter, and it costs nothing.

## 7. Policy split, line by line

| Dimension | Convoy | Circle | Expressed as |
|---|---|---|---|
| Lifetime | Dies when empty | Survives | `GroupKind.DropWhenEmpty` |
| Position retention | Nothing written | Latest fix only | `member_fixes`, written for circles only |
| Cadence | ~2 s | ~2 min | Client policy, no server code |
| Voice | Convoy-only rule | Never | Server-side kind check in the relay |
| Destination vote | Yes | Never | Server-side kind check in the relay |
| Sharing default | Connected = sharing | Opt-in, pausable | `group_members.is_sharing` |
| Size cap | None | 15 | Enforced on invite |
| Place events | — | Arrival / departure | Separate tables, circle-only |

Two of those rows are code that asks about kind — both in the relay, both the
gate in §6.5. Everything else is data.

## 8. What is persisted, and what is not

A circle whose positions live only in open sockets shows you nothing until the
other person opens the app — which is not the product. So a circle stores one row
per member: the latest fix, overwritten in place. No history, no trail. It exists
only for circles you joined, only while that circle's sharing switch is on, and
leaving deletes it.

That is a real change in what this service *is*: **for circles, it stops being
purely a relay and becomes an observer.** Defensible — opt-in, pausable,
latest-fix-only, deleted on leave — but it is the one place the server keeps
somebody's position, and it should stay the only one.

**Geofences are evaluated on the device.** A circle place has a radius; whether
you crossed it is decided on your own phone, from fixes that already arrive, and
only the resulting arrival or departure is posted. The stream of fixes behind it
never leaves the device, which makes this both the cheaper option and the one
consistent with everything else here. It also keeps the feature off the OS's
per-app region-monitoring budget.

Pausing is enforced **server-side as well as on the device**. Trusting the client
would mean a stale build keeps broadcasting after the user believes they stopped.

## 9. Security properties to preserve

1. **Membership is the only gate.** One function stands between an authenticated
   stranger and your live position, and it now protects two features — good for
   auditability, unforgiving of a mistake.
2. **Join re-checks, every time.** A circle connection can be open for days;
   being removed has to take effect without waiting for a reconnect.
3. **Eviction paths multiply.** Leaving, being removed, a session ending, and an
   out-of-band revocation all have to drop the right *memberships* without
   killing a connection still valid elsewhere.
4. **Existence and permission answer alike**, so ids cannot be enumerated.
5. **Pause is server-enforced** (§8).
6. **Voice and votes are gated on kind, server-side** (§6.5).

## 10. Battery and network

**One collector, two sinks.** The failure mode to design against is two
independent location subscriptions — the convoy's and the circle's — running
together during a ride and doubling the cost of the app's most expensive feature.
There is one location stream; convoys and circles are both sinks on it, and the
highest active cadence wins. Neither platform opens a second subscription. Circle arrive/depart uses no OS
geofence — it is on-device `GeofenceEvaluator` arithmetic. (Android separately
registers one unrelated geofence for parked-state service dormancy, issue #90;
it plays no part in circle presence.)

Cadences, and why:

| Tick | Interval | Why |
|---|---|---|
| Convoy position | ~2 s | It is a live ride feed; anything slower reads as a frozen map |
| Circle position + geofence check, tracker up | 2 min | Presence, not a trail. "Last seen" stays current without a cost anyone notices, and the tick is a second sink on a location stream a trip, a joined convoy or a foregrounded map already pays for |
| Circle position + geofence check, parked | 15 min | Not a battery choice — WorkManager's minimum period. Since #90 `TripTrackingService` stops while parked, so `CircleSyncWorker` carries the tick, and 15 min is the shortest period the platform allows for periodic work |
| Circle tick with no circle to share with | 30 min, tracker up | What a rider who never touches the feature pays, and the delay before their first circle starts working. Parked, they get the 15-minute floor instead: `tick()` returns this longer interval but `CircleSyncWorker`'s period is fixed, so the backoff has nothing to act on |

Transport follows from that: a circle at minute cadence does not justify holding
a socket open all day, so the low-cadence path is a plain `POST` of the latest
fix (§4), and the socket is for when a screen is actually watching. It also
degrades gracefully when the phone has no connectivity.

## 11. What is not built

- **Push notifications.** No device tokens, no vendor push service (§6.3).
- **Voice** (§6.4).
- **Convoy or circle history.** Nothing is written down for a convoy at all, and
  a circle keeps one row per member with no trail behind it. That is a decision,
  not a gap to fill.
