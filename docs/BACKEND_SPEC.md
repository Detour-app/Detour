# Detour backend — behavioural specification

What the sync and social service does, described as **behaviour and rules**
rather than code. This is the document to read before changing the backend, and
the one to check a change against afterwards; backend comments cite its sections
by number (`spec §11`), so **section numbers are stable** — add at the end,
don't renumber.

Scope: the single service the Detour app authenticates against. Identity lives
in Keycloak and is described here only where the service depends on it. Routing
and geocoding are separate off-the-shelf products the app calls directly — see
[§14](#14-out-of-scope).

> This file replaced a pre-rewrite specification of the Python sync server it
> grew out of. Where the two differ, the differences are collected in
> [§17](#17-what-changed-when-identity-moved-to-keycloak).

---

## 1. Actors and credential types

Three credential paths. They never substitute for one another.

| Actor | Credential | Can do | Cannot do |
|---|---|---|---|
| **Rider** | Access token minted by the realm, bearer on every request | Everything under their own account, plus friend and group interactions | Read another rider's trips, ever |
| **Dashboard reader** | Read-only API key, as a header or a query parameter | Read *only its own owner's* rides, traces, stats, badges | Write anything; read anyone else's data |
| **Administrator** | An ordinary rider token carrying the `detour-admin` realm role | Account metadata, row counts, deleting an account, revoking its dashboard keys | Read anyone's trips, traces, places or routes |

The live relay is not a fourth credential: it authenticates with the same rider
token over a WebSocket upgrade.

An account may hold several at once — a phone session, two dashboard keys.
Revoking one class does not disturb the others. Sessions and passwords belong to
the realm; dashboard keys belong to this service.

## 2. Privacy invariants

These are the product's promises. Each is enforced in exactly one place, and a
change must keep that property.

1. **Trip records are never returned to anyone but their owner.** No capability
   in the system reads another rider's trips.
2. **Traces (the fog-of-war lines) leave their owner through exactly one
   capability**, and only when *both* parties have opted into fog sharing.
   Sharing is off by default, reciprocal, and revocable — clearing it stops
   traces being served from the next request onwards.
3. **Friends otherwise see only aggregate numbers** the owner's own app computed
   (total distance, top speed, badges, …).
4. **Live position and voice are relayed only to a group's accepted members**,
   and only while a connection is actively joined to that group.
5. **Voice broadcast is rejected for any group that is not a convoy**, server
   side, regardless of what a client asks for.
6. **A member who pauses sharing has their live position dropped at the relay**,
   not merely suppressed on their own device, so an outdated client build cannot
   keep broadcasting after the user believes they stopped.
7. **A route can only be shared with an accepted friend**, and ending a
   friendship deletes every route shared between those two people in either
   direction.
8. **A place shared into a circle is revoked when its owner leaves that circle.**
9. **Administrators see account metadata and row counts only.** The rules are not
   relaxed for them, and the guarantee is that no such capability exists —
   not that a permission withholds it.
10. **Credentials are never logged.** Anything resembling a key or a token is
    redacted on its way to a log, on both the access and the error path.

## 3. Domain concepts

| Concept | Meaning |
|---|---|
| **User** | A local account keyed on the realm's subject identifier: handle, optional address, a fog-sharing preference, plus the latest aggregate stats and badge map their app uploaded. Created the first time a token for an unseen subject arrives. |
| **Trip** | One recorded journey, identified by its start instant. Stored opaquely — the service does not interpret its contents beyond the start instant and, for dashboards, a handful of well-known fields (end instant, mode, distance, top speed, peak g-force). |
| **Trace** | One fog-of-war line: an ordered list of recorded points. Deduplicated by content, so re-uploading the same line is a no-op. |
| **Track point** | A single recorded position with an instant and, optionally, speed and lean angle. Unpacked from trace lines that carry timestamps; the unit dashboards read. Associated with a trip *by instant*, not by an identifier. |
| **Saved place** | A rider's own shortcut (home, work, a favourite viewpoint). Opaque, keyed by a client-assigned identifier. |
| **Badge** | An achievement identifier plus the instant it was first earned. |
| **Friendship** | A symmetric relationship between two riders, either pending (one side must accept) or accepted. |
| **Shared route** | A planned route one rider sent to a friend. Opaque, replaces an earlier copy of the same route. |
| **Group** | One entity with two *kinds*: a **convoy** (a live ride together, ephemeral) and a **circle** (a standing "who's where" map, persistent). Membership is the access gate for every live feature. |
| **Circle place** | A named point with a radius, owned by one member, shared into one circle. |
| **Arrival/departure event** | A record that a member entered or left a circle place. Detected on the device; the service records and fans out. |
| **API key** | A read-only credential for a dashboard. The one credential this service issues. |

## 4. Accounts

### 4.1 Where identity lives

Registration, sign-in, sign-out, password reset, lockout and who may register
are **the realm's**, not this service's. There is no sign-in endpoint here, no
password anywhere in this schema, and no invite-code system. The service only
ever sees a token the realm minted.

[`docker/dev/config/keycloak/REALM.md`](../docker/dev/config/keycloak/REALM.md)
records how the realm is configured and which of the old server's rules each
setting carries forward.

### 4.2 Token requirements

A token is accepted when it:

- names the configured issuer **exactly** (`Idp:Authority`, not a prefix match);
- carries the configured audience (`detour-api`);
- carries a subject, and a `preferred_username` matching
  `^[A-Za-z0-9_.-]{3,24}$`.

The handle requirement is load-bearing rather than cosmetic: it is what other
riders search for when adding a friend, and a realm that permits an email
address as a username produces accounts this service refuses to provision.

### 4.3 Provisioning on first sight

The first request bearing a token for an unseen subject **creates** the local
account. There is nothing to call and nothing to confirm; a rider who signs in
on a new deployment simply exists there. Handle and address are refreshed from
the token on later requests, so a change made in the realm propagates without a
second mechanism.

Deleting the account here and removing the rider from the realm are two separate
acts. Doing only the first leaves them able to sign in and start again; doing
only the second leaves their data here.

### 4.4 Own profile

A rider can read their own handle, address, fog-sharing preference, aggregate
stats and badge map, and can set the fog-sharing preference.

## 5. Device synchronisation

One capability performs a **bidirectional merge** of everything the device
holds and returns the merged result. It is idempotent: syncing twice with the
same input yields the same state.

Accepted from the device (all optional):

| Payload | Merge rule |
|---|---|
| Trips | Keyed on (owner, start instant). A re-upload **replaces** the stored copy, so an edit — a corrected vehicle mode — propagates rather than being ignored. |
| Deleted trips | Start instants the device has deleted. Applied **after** the upserts, so a trip in both lists ends up deleted and the deletion propagates to every other device instead of the trip resurrecting. |
| Traces | Deduplicated on content. A genuinely new line is additionally unpacked into track points; a line already held is not re-unpacked, because every sync re-sends the whole history. |
| Saved places | Keyed on the client-assigned identifier; a rename replaces the stored copy. |
| Badges | The **earliest** earned instant wins, so a reinstall cannot move a date forward. |
| Aggregate stats | Absent means "no update", not "clear" — a client that syncs only trips must not blank the numbers its friends read. |
| Fog-sharing preference | Absent means "leave it alone", so an older client cannot silently flip the setting. |

Validation:

- Every trip and saved place is validated **before anything is written**: one
  malformed entry fails the whole sync rather than leaving a partial import.
- Aggregate stats are filtered to a known key list and to finite numbers only,
  so nothing arbitrary can be pushed into a payload other people read. The keys
  are: total distance, top speed, longest single trip, maximum lean angle,
  municipalities visited, best coverage percentage, trip count.
- Badge identifiers must match a fixed pattern (a lowercase family, an
  underscore, the tier threshold — `dist_100000`) and are capped per account.
- A trace point contributes a track point only if it carries at least a position
  and an instant. Older two-element points still draw fog but have no instant to
  hang on, and are skipped rather than stored with a made-up time. Individual bad
  readings are dropped point by point — one broken sample must not cost the whole
  ride.

Returned: the merged union of trips (newest first), traces, badges and saved
places.

## 6. Friends

- **Request** by handle. Requesting someone who already requested you accepts
  them. Requesting an existing friend is a no-op. Self-friending is refused.
- **Respond** accepts or declines a *pending incoming* request. The requester
  cannot accept their own.
- **Remove** ends the friendship and, in the same operation, deletes every route
  shared between the two people **in either direction**.
- **List** returns three sets: accepted friends, incoming requests, outgoing
  requests.
- **Friend stats** returns, for accepted friends only, their handle, aggregate
  stats and badge map, sorted by total distance descending. This is the only
  capability that returns another rider's data at all, and it reads nothing but
  those aggregates.

## 7. Fog sharing

- A single per-rider preference, off by default.
- **Reading shared fog requires the caller to be sharing too.** Turning sharing
  off both stops contributing and stops receiving — that reciprocity is what
  makes the trade legible.
- Returns the union of accepted friends' trace lines, **unattributed** (it is a
  map, not a per-friend history), filtered at read time to friends who are
  currently sharing, so revoking takes effect on the next request.

## 8. Shared routes

- **Share** sends one route to one accepted friend. The friendship is re-checked
  on every share, so a route cannot be pushed to someone unfriended a moment ago.
- Self-sharing is refused.
- A route must be an object with a numeric, finite identifier and **at least two
  stops**, and must serialise within the payload cap.
- Re-sharing the same route replaces the recipient's earlier copy rather than
  duplicating it. The key includes the sender, so two friends sharing routes that
  happen to carry the same client-side identifier do not collide.
- A **write cap per (recipient, sender) pair** is enforced at share time, oldest
  dropped. Scoping it per pair rather than per recipient means one chatty friend
  can only ever push out their *own* older shares, never crowd a quieter friend's
  routes out of your inbox.
- **Inbox** returns routes addressed to the caller, newest first, capped. The
  sender's handle is set from the authenticated sender, never read out of the
  stored payload.
- **Delete** removes one shared route if the caller is *either* side of it — the
  recipient dropping it, or the sender un-sharing it.

## 9. Groups: convoys and circles

Convoys and circles are the same entity distinguished by kind. Shared behaviour:

- **Create** with a name within the length bounds; the creator joins
  automatically as an accepted member.
- **Invite** requires the caller to be an accepted member *and* an accepted
  friend of the invitee. This is what makes membership mean "granted access"
  rather than an open room. Inviting an existing member returns that member's
  current status.
- **Respond** accepts or declines a pending invitation.
- **Leave** removes the membership, deletes the leaver's shared places and last
  known position for that group, and drops any live connection they still hold on
  it, immediately.
- **List** returns the caller's groups of one kind, each with its members and
  their statuses.
- A group id that does not exist and a group the caller is not a member of
  produce the **same** answer, so identifiers cannot be enumerated.

Differences:

| | Convoy | Circle |
|---|---|---|
| Lifetime | Deleted when the last member leaves | Persists while one member remains, and while alone |
| Size cap | None | 15 members |
| Pause switch | Not applicable | Per member, per circle |
| Position persistence | Never stored; live only | Latest fix per member, overwritten in place — no history, no trail |
| Voice (push-to-talk) | Allowed by the rules; **not carried today**, see [§11.3](#113-voice) | **Rejected** |
| Destination voting | Allowed | **Rejected** |

### 9.1 Circle pause

- A member can pause and resume sharing within one circle. Per person, per
  circle.
- Pausing is enforced on **both** paths: live frames are dropped at the relay,
  and the paused member is excluded from position reads even though their last
  fix row may still exist.
- Asking to pause a *convoy* is answered as "not found" rather than "not
  applicable", so the capability cannot be used as a second way to ask what kind
  a group is.

### 9.2 Circle position, low cadence

- A member can upload a single position with an accuracy radius and an instant
  without holding a live connection open. Circles update on the order of minutes;
  that does not justify an all-day socket.
- The same fix is relayed to every circle **and convoy** the caller shares with
  someone, and stored only for circles they are currently sharing with. A convoy
  never stores one.
- Reading returns the latest position of every accepted **and currently sharing**
  member.
- Positions must be finite and in range; garbage is refused rather than stored,
  because a stored non-number breaks every map that later reads it.

## 10. Circle places and presence events

### 10.1 Places

- A member shares a place into a circle they belong to. The place is
  rider-owned, opaque apart from an identifier, a name and a radius.
- Radius must be greater than 0 and at most 50 km; payload and name are bounded.
- Re-sharing the same place replaces the earlier copy.
- **Write cap per (circle, owner)**, oldest dropped.
- Listing a circle's places is membership-gated and returns every member's places
  with their owner's handle attached.
- Only the owner can delete a place. Leaving the circle deletes all of theirs.

### 10.2 Arrival and departure

- Geofence transitions are decided **on the device**. The service records the
  result and fans it out; it never evaluates geofences itself.
- An event is an arrival or a departure, for one place, at one instant.
- **Newest-N retention per circle**, so one chatty member cannot grow the feed
  without bound.
- Reading returns a circle's recent events since a caller-supplied instant,
  **including the caller's own** — that is a requirement, not an oversight.
- A best-effort place name is attached for wording notifications. Place
  identifiers are client-assigned and only unique per (circle, owner), so the
  lookup takes the most recent match and never multiplies one event into several.
- A live frame is emitted to the rest of the circle **after** the record is
  durable, so a peer that reacts by re-reading the feed can never find nothing
  there.
- There is deliberately **no push-notification integration** — no device tokens,
  no vendor push service. Catch-up is by polling the feed.

## 11. Live relay

A persistent duplex channel alongside the request/response API, at `/api/live`,
authenticated with the same rider token on the upgrade request. It is an
ordinary endpoint on the ordinary port, not a second listener.

### 11.1 Connection model

- **One connection, many groups.** Someone in a circle all day who also starts a
  convoy for a ride needs both live at once, so joining *adds* a membership
  rather than replacing it.
- Joining requires an accepted membership of that group, re-checked on every join
  frame rather than cached from an earlier one.
- A second connection for the same rider in the same group closes the first,
  rather than leaving a ghost that keeps receiving forever.
- **A group's kind is re-checked per frame** for anything kind-restricted, not
  resolved once at join time. Stricter than caching it, and it costs nothing.

### 11.2 Frames

Client to server:

| Type | Groups | Rules |
|---|---|---|
| `join` | any | Must be an accepted member. Names the group. |
| `location` | any | Position must be finite and in range; heading and speed are optional and silently dropped if out of range. Relayed to every other member of every group the sender shares with them. In a **circle** it also overwrites the sender's stored last fix. Dropped entirely if the sender has paused. Deliberately carries **no** group id — one fix is one fix, and fanning it out per group is the relay's job, not the phone's. |
| `spin_offer` | **convoy only** | 1–3 candidates, each with a valid position and optional distance, duration and name. One invalid candidate voids the whole frame rather than silently relaying a shorter list. |
| `spin_vote` | **convoy only** | Index 0–2. Anything else is dropped rather than relayed as a vote for a candidate that was never offered. |

Server to client: `joined`, `error`, `positions`, `left`, `place_event`.
`place_event` is server-originated only — a client cannot cause one by sending
it, which is why there is no inbound counterpart.

The wire format of each frame, key by key, is documented with the feature it
serves in
[CIRCLES_AND_CONVOYS.md §6](CIRCLES_AND_CONVOYS.md#6-the-live-relay-on-the-wire).
That table and `LiveFrames.cs` are the two halves of one contract.

A **`spin_offer` carrying three candidates means "vote on these"; one candidate
means "this won"**. The relay treats both identically and holds no round state;
the convention lives in the clients, and the single-candidate closing frame is
what stops a convoy splitting across two destinations.

### 11.3 Voice

Push-to-talk is part of the rules above — convoy only, rejected for circles,
one chunk bounded in size — but **the relay does not carry it today**. Voice
frames are accepted off the wire and dropped, the same as any unknown type, so a
client that still sends them stays connected and everything else keeps working.

What returns will be Opus over binary frames: raw PCM base64'd into JSON cost
roughly 40 KB/s per talker per listener, which is what made it worth deferring
rather than porting. Clients read this state from a shared feature flag so the
two apps cannot disagree about it.

### 11.4 Revocation

Prompt, and surviving process boundaries:

- Leaving a group drops that connection's membership instantly.
- A periodic sweep re-validates every open connection against stored state, so a
  membership revoked out of band is disconnected within seconds.
- Each (group, member) pairing is validated independently: a connection can go
  stale in one group and stay valid in another, and is closed only once it holds
  no valid membership anywhere.

Degradation: if the live channel cannot start, everything else — group creation,
invitations, membership management, low-cadence circle positions, the presence
feed — keeps working. Only live position goes away.

## 12. Read-only dashboard API

A separate read surface, authenticated by API key rather than by a rider token,
intended for a home-automation dashboard. The key may be a header (for polling
sensors) **or** a query parameter (an embedded frame cannot send a header). Every
capability reads only the key owner's own data.

| Capability | Returns |
|---|---|
| Stats | Lifetime aggregates, corrected where the service knows better; ride count; badge map; and a **badge catalogue** scoring every defined badge, earned or not, so a card can show progress toward the next tier without knowing the tiers. |
| Rides | Rides newest first, each with start and end, mode, distance, top speed, peak lean, peak g-force and point count. |
| Ride geometry | One ride as geographic features — a segment per band, each carrying the speed and lean recorded there, because a single line can only be one colour and colouring by lean is the point. No ride named means the newest ride, so a polling sensor needs no second request to discover "latest". |
| Traces | The caller's own trace lines, positions only, optionally thinned, for an all-rides heatmap. |
| Coverage | Every trace as one aggressively-thinned geometry plus a per-cell visit heat banding. Simplification runs per line so one long trace cannot eat the whole budget; the point budget is a **total across all lines**, not per line. |

Behavioural rules:

- **Corrections the service is better placed to make**: ride count comes from the
  trips actually held, not the device's figure; peak lean is reported as
  *unknown* rather than zero when nothing has ever recorded a lean, because
  "never measured" and "rode upright" are different answers. Where both the
  device figure and the recorded points have a value, the deeper one wins.
- **Ride windows.** A trip that never recorded an end still has points. It is
  given the longest plausible window (24 hours) but cut short at the start of the
  next trip, so an unended ride cannot swallow the ride after it — and its speed
  and lean peaks with it.
- Simplification is tolerance-based in metres and corrects for longitude covering
  less ground away from the equator, so a tolerance means the same thing
  north–south and east–west.
- Dropping a point during simplification must not drop the extreme value it was
  carrying — the overlays look back at what the raw track held between two kept
  points.
- Results are briefly cached, because a dashboard re-renders far more often than
  a ride changes.
- Every numeric parameter is clamped to a sane range. A junk parameter is a
  client error, never a server fault, and never a refusal: a dashboard URL gets
  typed by hand into a config file.

**The service renders no pages.** Geometry is served as JSON and drawing it is
the dashboard's job — see
[`server/homeassistant/README.md`](../server/homeassistant/README.md).

### 12.1 Badge catalogue

The tiers are product content:

| Family | Measured on | Tiers |
|---|---|---|
| Distance | Total distance | 100 km, 500 km, 1 000 km, 5 000 km, 10 000 km, 25 000 km |
| Top speed | Top speed | 100, 130, 160, 200, 250 km/h |
| Single ride | Longest single trip | 100 km, 250 km, 500 km |
| Places | Municipalities visited | 3, 10, 25, 50 |
| Coverage | Best coverage percentage | 10, 25, 50, 100 |

Each entry reports its threshold, the rider's current value, when it was earned
(if it was), and progress toward it.

## 13. Administration

Gated on the `detour-admin` realm role, carried by an ordinary rider token.
There is no separate admin credential, no admin session, and no browser
dashboard: creating accounts, resetting passwords, granting the role and locking
an account out are realm operations, done in the Keycloak console against the
realm's own audit trail.

What remains is what the realm cannot know — how much a rider stored here, and
removing it:

| Action | Rules |
|---|---|
| Overview | Per account: handle, address, administrator flag, fog-sharing flag, created and last-seen instants, **counts** of trips, traces, badges and dashboard keys, and total distance. No trip, trace, place or route content is readable, and no capability exists that would make it readable. |
| Delete an account | Refused for the caller's own account. Deletes the account **and every row it owns**: trips, traces, points, shortcuts, badges, friendships, group memberships, shared routes, circle places and keys. Removing the rider from the realm is a separate act. |
| Revoke dashboard keys | All API keys for one account — the lost-device remedy for the one credential the realm does not issue. Does not touch the rider's session, which is the realm's to end. |

Role changes take effect on the next token the realm issues, which is the
15-minute access-token lifetime rather than the next click.

## 14. Out of scope

Two adjacent self-hosted services are installed alongside but are **not part of
this backend**. The app calls them directly.

| Service | Function |
|---|---|
| Routing engine (GraphHopper) | Curvy motorcycle round trips, plus car and bike routes, from an offline map extract |
| Geocoder (Photon) | Address and place search, self-hosted so it is fast, private and not rate-limited |

Also deliberately absent, and a **new** decision rather than a port if it is ever
added: mobile push notifications. No device tokens, no vendor push service
anywhere in the system.

Deferred rather than dropped: **voice** ([§11.3](#113-voice)), **background
jobs** (every retention cap is enforced at write time, where the row that would
exceed it is created, so there is nothing for a sweep to do), and an **audit
trail** — the obvious next security addition.

## 15. Cross-cutting behaviour

### 15.1 Transport and payloads

- Requests may be compressed; responses are compressed when the client says it
  can decode them and the payload is worth it (a full trip-and-trace history
  compresses roughly ten to one).
- Request bodies are bounded, and **the decompressed size is bounded too** — a
  compression bomb is small enough to pass the first check, which is why the
  second exists.

### 15.2 Rate limiting

Token buckets, chained so one caller cannot exhaust everyone else's budget:

| Tier | Keyed on | Why it is separate |
|---|---|---|
| Per address | Client IP | The cheap first gate, and the only gate anonymous traffic meets |
| Per rider | Authenticated account | One rider's runaway client does not throttle the rest |
| Per dashboard key | The key itself | Smaller than a rider's: otherwise a runaway dashboard poll throttles the owner's own phone and starves their other keys |
| Anonymous | Client IP, on endpoints reachable without a token | Deliberately tiny — the old server capped auth attempts at 10 per 5 minutes per address, and the realm's own brute-force detection is the other half of that promise |

The client address is taken from a proxy header **only when the deployment is
explicitly configured to trust one**; otherwise anyone could reset the limiter
per request by spoofing it. See
[`backend/INSTALL.md`](../backend/INSTALL.md#behind-a-reverse-proxy).

### 15.3 Errors

- Failures are values, not exceptions: domain methods return a result carrying a
  validation key, and the global handler renders it as a localised 400.
- Internal faults never leak a stack trace or internals to the caller.
- A malformed request is a client error, not a server fault. In particular,
  non-finite numbers (infinity, not-a-number) survive JSON parsing and are
  rejected explicitly at every numeric boundary rather than blowing up later.
- Existence and permission are deliberately conflated where enumeration would
  otherwise be possible (group identifiers especially).

### 15.4 Health

`/api/health` is unauthenticated on purpose, so an orchestrator can probe it, and
answers with a per-dependency breakdown: Postgres is critical, Redis is
degraded-only.

## 16. Limits and defaults

| Setting | Default |
|---|---|
| Access token lifetime | 15 minutes (realm) |
| Session idle / max | 90 days (realm) |
| Handle | 3–24 characters, `^[A-Za-z0-9_.-]{3,24}$` |
| Email | ≤ 254 characters |
| Request body | 64 MB, compressed and decompressed |
| Group name | 1–40 characters |
| Circle members | 15 |
| Badges per account | 200 |
| Badge id | ≤ 40 characters, `^[a-z]+_[0-9]+$` |
| Shared route payload | 512 KB |
| Shared routes per (recipient, sender) | 50 |
| Route inbox page | 100 newest |
| Route stops | ≥ 2 |
| Circle place payload | 64 KB |
| Circle place radius | > 0 m and ≤ 50 000 m |
| Circle places per (circle, owner) | 50 |
| Presence events per circle | 500 newest |
| Voice chunk | ≈20 000 base64 characters |
| Destination candidates per offer | 1–3 |
| Destination name | ≤ 80 characters |
| Dashboard: rides per request | ≤ 500 |
| Dashboard: trace thinning | 1 in 1 … 1 in 50 |
| Cache duration | 120 s, fail-safe 600 s |

Every cap in the second half of that table lives in `DetourLimits` — one file,
because each exists so that one client cannot grow another's data without bound.
Changing one is a product decision, and the entity that enforces it, the column
sized for it and the test that pins it all read from there.

## 17. What changed when identity moved to Keycloak

Recorded because the app, the docs and half the deployment advice predate it.

1. **Passwords, sessions, resets, invites and admin sessions are gone from this
   service.** The rules they encoded did not disappear — fail-closed
   registration, uniform answers, a single live reset link, a reset that revokes
   everything — they are realm configuration now, and
   [REALM.md](../docker/dev/config/keycloak/REALM.md) is where each one is
   accounted for.
2. **Dashboard API keys stayed**, because nothing in the realm issues a
   read-only, owner-scoped credential for a home dashboard.
3. **Administration shrank to metadata and deletion** ([§13](#13-administration)).
4. **The live relay became an ordinary endpoint** under `/api/live` instead of a
   second listener on its own port — which removed the separate hostname and
   access rule it used to need.
5. **Fog sharing, group membership and friendship stayed here.** They are
   authorisation decisions but they are domain state, not identity, and they
   belong in the application regardless of who issues tokens.
6. **One large opaque blob per trip, route and place** survived the port
   deliberately: the service cannot read what it does not parse.
7. **Trips and track points are still joined by instant, not by identifier**,
   because a trace line carries no trip reference.
8. **Deletion is still user-driven** and propagates through the sync merge.
   There is no server-side retention policy on trips or traces.
9. **There is no importer for the old `detour.db`**, and passwords cannot be
   carried across at all — the realm never saw the old hashes.
