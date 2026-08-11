# Detour Backend — Functional Specification

Language-agnostic inventory of everything the current backend does. It describes
**behaviour and rules**, not implementation. Anything in here must still be true
after the rewrite unless a decision is recorded to change it.

Scope: the *sync + social service* — the single service the Detour app
authenticates against. Two adjacent services (a routing engine and a geocoder)
are off-the-shelf products the app calls directly; see
[§14 Out of scope](#14-out-of-scope).

---

## 1. Actors and credential types

Four independent credential paths exist. They never substitute for one another.

| Actor | Credential | Can do | Cannot do |
|---|---|---|---|
| **App user** | Bearer session token issued at login | Everything under their own account, plus friend/group interactions | Read another user's trips, ever |
| **Dashboard reader** | Read-only API key (query parameter or header) | Read *only its own owner's* rides, traces, stats, badges | Write anything; read anyone else's data |
| **Live client** | Same bearer session token, over a persistent duplex connection | Relay live position, voice, destination votes within groups it belongs to | Anything outside its accepted group memberships |
| **Administrator** | Browser session, established with the user's normal account password, only for accounts flagged as administrators | Manage accounts, invites, credentials | Read anyone's trips, traces, places or routes |

An account may hold several credentials at once (a phone session, a tablet
session, two dashboard keys, an admin browser session). Revoking one class must
not disturb the others, **except**: setting a password revokes every session and
every admin session for that account.

## 2. Privacy invariants

These are the product's promises. Each is enforced in exactly one place, and a
rewrite must keep that property.

1. **Trip records are never returned to anyone but their owner.** No capability
   in the system reads another user's trips.
2. **Traces (the fog-of-war lines) leave their owner through exactly one
   capability**, and only when *both* parties have opted into fog sharing.
   Sharing is off by default, reciprocal, and revocable — clearing it stops
   traces being served from the next request onwards.
3. **Friends otherwise see only aggregate numbers** the owner's own app computed
   (total distance, top speed, badges, …).
4. **Live location and voice are relayed only to a group's accepted members**,
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
9. **Administrators see account metadata and row counts only.** The privacy
   rules are not relaxed for them; there is deliberately no capability that
   would let an administrator read anyone's rides.
10. **Credentials are never logged.** Anything resembling an API key is redacted
    on its way to a log, on both the access and the error path.

## 3. Domain concepts

| Concept | Meaning |
|---|---|
| **User** | An account: name, optional email address, credentials, a fog-sharing preference, an administrator flag, plus the latest aggregate stats and badge map their app uploaded. |
| **Trip** | One recorded journey, identified by its start instant. Stored opaquely — the service does not interpret its contents beyond the start instant and, for read-only dashboards, a handful of well-known fields (end instant, mode, distance, top speed, peak g-force). |
| **Trace** | One fog-of-war line: an ordered list of recorded points. Deduplicated by content, so re-uploading the same line is a no-op. |
| **Track point** | A single recorded position with an instant and, optionally, speed and lean angle. Derived from trace lines that carry timestamps; the unit the dashboards read. Points are associated with a trip *by instant*, not by an identifier. |
| **Saved place** | A user's own shortcut (home, work, a favourite viewpoint). Opaque, keyed by a client-assigned identifier. |
| **Badge** | An achievement identifier plus the instant it was first earned. |
| **Friendship** | A symmetric relationship between two users, either pending (one side must accept) or accepted. |
| **Shared route** | A planned route one user sent to a friend. Opaque, replaces an earlier copy of the same route. |
| **Group** | One entity with two *kinds*: a **convoy** (a live ride together, ephemeral) and a **circle** (a standing "who's where" map, persistent). Membership is the access gate for all live features. |
| **Circle place** | A named point with a radius, owned by one member, shared into one circle. |
| **Arrival/departure event** | A record that a member entered or left a circle place. Detected on the device; the service only records and fans out. |
| **Invite** | A single-use code that permits creating one account. |
| **API key** | A read-only credential for a dashboard. |

## 4. Accounts and registration

### 4.1 Registration

- Requires a name and a password; an email address is optional.
- Name rules: 3–24 characters, letters, digits, dot, underscore, hyphen.
  Names are compared case-insensitively and must be unique.
- Password rules: 8–200 characters.
- Email, if given, must look like an address and be at most 254 characters.
- **Three ways in**, checked in this order:
  1. a single-use invite code issued by an administrator;
  2. a shared invite code configured for the whole server;
  3. an explicitly open server that has *no* shared code configured.
- **Registration fails closed.** A server with no configuration at all refuses
  registration rather than defaulting to open. A server that has both "open" and
  a shared code still demands the code.
- A single-use invite is consumed in the same transaction that creates the
  account, so two people racing one invite cannot both win.
- If the invite was addressed to an email and the registrant supplied none, the
  invite's address becomes the account's address.
- Registration succeeds with an active session for the new account.
- Submitting a *wrong* code counts as an attack against the rate limiter;
  submitting *no* code does not.

### 4.2 Sign in / sign out

- Sign in takes name and password; it returns an active session.
- A failed sign-in must cost the same time as a successful one whether or not
  the account exists (no timing oracle on account existence).
- Sign out revokes only the session that was used, and immediately drops any
  live connections that session had open.

### 4.3 Sessions

- A session is idle-expiring: unused beyond a configured horizon (default 90
  days), it is rejected.
- Last-use is refreshed at most once per hour so an active session does not cost
  a write on every request.
- Only a non-reversible representation of the token is stored, so a database
  leak does not hand over live sessions.

### 4.4 Password reset (self-service)

- "Forgot password" accepts either a name or an email address and **always
  answers success**. Whether an account exists, and whether it has an address on
  file, must not be learnable by an unauthenticated caller.
- A reset link is emailed only when the account exists *and* has an address.
- Exactly **one live reset link per account**: minting a new one invalidates any
  earlier unused link.
- Reset links are single-use and short-lived (default 60 minutes).
- The link targets the mobile app, not a web page, and the mail also carries the
  raw code for mail clients that will not linkify a custom scheme.
- Redeeming a reset **signs the account out everywhere** — a reset is also the
  answer to "someone else has my phone".

### 4.5 Own profile

A user can read their own name, email address, aggregate stats and badge map.

## 5. Device synchronisation

One capability performs a **bidirectional merge** of everything the device
holds, and returns the merged result. It is idempotent: syncing twice with the
same input yields the same state.

Accepted from the device (all optional):

| Payload | Merge rule |
|---|---|
| Trips | Keyed on (owner, start instant). A re-upload **replaces** the stored copy, so an edit — a corrected vehicle mode — propagates rather than being ignored. |
| Deleted trips | A list of start instants the device has deleted. Applied **after** the upserts, so a trip present in both lists ends up deleted and the deletion propagates to every other device instead of the trip resurrecting. |
| Traces | Deduplicated on content. A line that is genuinely new is additionally unpacked into track points; a line already held is not re-unpacked (every sync re-sends the whole history). |
| Saved places | Keyed on the client-assigned identifier; a rename replaces the stored copy. |
| Badges | The **earliest** earned instant wins, so a reinstall cannot move a date forward. |
| Aggregate stats | Absent means "no update", not "clear" — a client that syncs only trips must not blank the numbers its friends read. |
| Fog-sharing preference | Absent means "leave it alone", so an older client cannot silently flip the setting. |

Validation:

- Every trip and saved place is validated **before anything is written**: one
  malformed entry fails the whole sync rather than leaving a partial import.
- Aggregate stats are filtered to a **known key list** and to finite numbers
  only, so nothing arbitrary can be pushed into a payload other people read.
  The known keys are: total distance, top speed, longest single trip, maximum
  lean angle, municipalities visited, best coverage percentage, trip count.
- Badge identifiers must match a fixed pattern (a lowercase word, an underscore,
  a number) and are capped at 200 per account.
- A trace point contributes a track point only if it carries at least a
  position and an instant. Older two-element points still draw fog but have no
  instant to hang on, and are skipped rather than stored with a made-up time.
  Individual bad readings are dropped point by point — one broken sample must
  not cost the whole ride.

Returned: the merged union of trips (newest first), traces, badges and saved
places.

## 6. Friends

- **Request** by name. Requesting someone who already requested you is treated
  as accepting them. Requesting an existing friend is a no-op. Self-friending is
  refused.
- **Respond** accepts or declines a *pending incoming* request. The requester
  cannot accept their own request.
- **Remove** ends the friendship and, in the same operation, deletes every route
  shared between the two people **in either direction**.
- **List** returns three sets: accepted friends, incoming requests, outgoing
  requests.
- **Friend stats** returns, for accepted friends only, their name, aggregate
  stats and badge map — sorted by total distance, descending. This is the only
  capability that returns another user's data at all, and it reads nothing but
  those aggregates.

## 7. Fog sharing

- A single per-user preference, off by default.
- **Reading shared fog requires the caller to be sharing too.** A user who turns
  sharing off both stops contributing and stops receiving — that reciprocity is
  what makes the trade legible.
- Returns the union of accepted friends' trace lines, **unattributed** — it is a
  map, not a per-friend history — filtered at read time to friends who are
  currently sharing, so revoking takes effect on the next request.

## 8. Shared routes

- **Share** sends one route to one accepted friend. The friendship is re-checked
  on every share, not just once, so a route cannot be pushed to someone
  unfriended a moment ago.
- Self-sharing is refused.
- A route must be an object with a numeric, finite identifier and **at least two
  stops**, and must serialise to at most 512 KB.
- Re-sharing the same route replaces the recipient's earlier copy rather than
  duplicating it. The key includes the sender, so two friends sharing routes
  that happen to carry the same client-side identifier do not collide.
- A **write cap of 50 routes per (recipient, sender) pair** is enforced at share
  time, oldest dropped. Scoping it per pair rather than per recipient means one
  chatty friend can only ever push out their *own* older shares, never crowd a
  quieter friend's routes out of your inbox.
- **Inbox** returns routes addressed to the caller, newest first, capped at 100.
  The sender's name is set from the authenticated sender, never read out of the
  stored payload.
- **Delete** removes one shared route if the caller is *either* side of it — the
  recipient dropping it, or the sender un-sharing it.

## 9. Groups: convoys and circles

Convoys and circles are the same entity distinguished by kind. Shared behaviour:

- **Create** with a name of 1–40 characters; the creator joins automatically as
  an accepted member.
- **Invite** requires the caller to be an accepted member *and* an accepted
  friend of the invitee. This is what makes group membership mean "granted
  access" rather than an open room. Inviting an existing member returns that
  member's current status.
- **Respond** accepts or declines a pending invitation.
- **Leave** removes the membership, deletes the leaver's shared places and last
  known position for that group, and drops any live connection they still hold
  on it, immediately.
- **List** returns the caller's groups of one kind, each with its members and
  their statuses.
- A group id that does not exist and a group the caller is not a member of must
  produce the **same** answer, so identifiers cannot be enumerated.

Differences:

| | Convoy | Circle |
|---|---|---|
| Lifetime | Deleted when the last member leaves | Persists while one member remains, and while alone |
| Size cap | None | 15 members |
| Pause switch | Not applicable | Per member, per circle |
| Position persistence | Never stored; live only | Latest fix per member kept, overwritten in place — no history, no trail |
| Voice (push-to-talk) | Allowed | **Rejected** |
| Destination voting | Allowed | **Rejected** |

### 9.1 Circle pause

- A member can pause and resume sharing within one circle. It is per person per
  circle, not per group.
- Pausing is enforced on **both** paths: live frames are dropped at the relay,
  and the paused member is excluded from position reads even though their last
  fix row may still exist.
- Asking to pause a *convoy* is answered as "not found" rather than "not
  applicable", so the capability cannot be used as a second way to ask what kind
  a group is.

### 9.2 Circle position, low cadence

- A member can upload a single position with an accuracy radius and an instant,
  without holding a live connection open. Circles update on the order of
  minutes; that does not justify an all-day socket.
- Reading returns the latest position of every accepted **and currently
  sharing** member.
- Positions must be finite and in range; garbage is refused rather than stored,
  because a stored non-number breaks every map that later reads it.

## 10. Circle places and presence events

### 10.1 Places

- A member shares a place into a circle they belong to. The place is
  user-owned, opaque apart from an identifier, a name and a radius.
- Radius must be a number greater than 0 and at most 50 km.
- Serialised size at most 64 KB; name truncated to 200 characters, defaulting to
  a placeholder when blank.
- Re-sharing the same place replaces the earlier copy.
- **Write cap of 50 places per (circle, owner)**, oldest dropped.
- Listing a circle's places is membership-gated and returns every member's
  places with their owner's name attached.
- Only the owner can delete a place. Leaving the circle deletes all of theirs.

### 10.2 Arrival and departure

- Geofence transitions are decided **on the device**. The service records the
  result and fans it out; it never evaluates geofences itself.
- An event is an arrival or a departure, for one place, at one instant.
- **Retention of the newest 500 events per circle**, so one chatty member cannot
  grow the feed without bound.
- Reading returns a circle's recent events since a caller-supplied instant,
  **including the caller's own** — that is a requirement, not an oversight.
- A best-effort place name is attached for wording notifications. Place
  identifiers are assigned by clients and are only unique per (circle, owner),
  so the name lookup takes the most recent match and never multiplies one event
  into several.
- A live frame is emitted to the rest of the circle **after** the record is
  durable, so a peer that reacts to the frame by re-reading the feed can never
  find nothing there.
- There is deliberately **no push-notification integration** — no device tokens,
  no vendor push service. Catch-up is by polling the feed.

## 11. Live relay

A second, persistent, duplex channel alongside the request/response API,
authenticated with the same bearer session token.

- **One connection, many groups.** Someone in a circle all day who also starts a
  convoy for a ride needs both live at once, so joining *adds* a membership
  rather than replacing it. Every frame after the join names which group it is
  for.
- Joining requires an accepted membership of that group. The group's kind is
  resolved once at join time and cached for the life of the connection.
- A second connection for the same user in the same group closes the first,
  rather than leaving a ghost that keeps receiving forever.

Client-to-server message types:

| Type | Groups | Rules |
|---|---|---|
| Join | any | Must be an accepted member |
| Position | any | Position must be finite and in range; heading and speed are optional and silently dropped if out of range; relayed to every other member. In a **circle** it also overwrites the sender's stored last fix. Dropped entirely if the sender has paused. |
| Voice start / audio / end | **convoy only** | One audio chunk is bounded in size. Rejected outright for circles. |
| Destination offer | **convoy only** | 1–3 candidates, each with a valid position and optional distance, duration and name (bounded). One invalid candidate voids the whole frame rather than silently relaying a shorter list. |
| Destination vote | **convoy only** | Index 0–2. Anything else is dropped rather than relayed as a vote for a candidate that was never offered. |

A **destination offer carrying three candidates means "vote on these"; one
candidate means "this won"**. The relay treats both identically; the convention
lives in the clients but must be documented, because it is what keeps a convoy
from splitting across two destinations.

Server-to-client message types: join acknowledgement, relayed position, relayed
voice, relayed offers and votes, **peer left**, and **presence event** (see
§10.2). Presence events are server-originated only — a client cannot cause one
by sending it.

Revocation must be prompt and must survive process boundaries:

- Leaving a group drops that connection's membership instantly.
- Signing out drops every live connection that session holds, instantly.
- A periodic sweep re-validates every open connection against stored state, so a
  session revoked by an out-of-band operation (an administrator, a command-line
  tool in another process) is also disconnected within seconds.
- Each (group, member) pairing is validated independently: a connection can go
  stale in one group and stay valid in another. A connection is only closed once
  it holds no valid membership anywhere.
- A membership evicted from one group must not keep relaying through a
  connection that is still legitimately open for a different group.

Degradation: if the live channel cannot start, everything else — group
creation, invitations, membership management, low-cadence circle positions,
the presence feed — must keep working. Only live position and voice go away.

## 12. Read-only dashboard API

A separate read surface, authenticated by API key rather than session, intended
for a home-automation dashboard. The key can be supplied as a header (for
polling sensors) **or** as a query parameter (an embedded frame cannot send a
header). Every capability here reads only the key owner's own data.

| Capability | Returns |
|---|---|
| Stats | Lifetime aggregates, corrected by the service where it knows better; ride count; badge map; and a **badge catalogue** scoring every defined badge, earned or not, so a card can show progress toward the next tier without knowing the tiers. |
| Rides | Rides newest first (cap 500), each with start and end, mode, distance, top speed, peak lean, peak g-force and point count. |
| Ride geometry, detailed | One ride as a geographic feature per segment, each carrying the speed and lean recorded there — a single line can only be one colour, and colouring by lean is the point. |
| Ride geometry, compact | One ride as a single simplified line plus per-band overlay layers, sized to fit in a dashboard entity attribute. No ride selected means the newest ride, so a polling sensor needs no second request to discover "latest". |
| Traces | The caller's own trace lines, positions only, optionally thinned, for an all-rides heatmap. |
| Coverage | Every trace as one aggressively-thinned geometry plus a per-cell visit heat banding. Simplification runs per line so one long trace cannot eat the whole budget; the point budget is then a **total across all lines**, not per line. |
| Dashboard page | A self-contained page with map, heat, general and badge views. |

Behavioural rules:

- **Corrections the service is better placed to make**: ride count comes from
  the trips actually held, not the device's figure; and peak lean is reported as
  *unknown* rather than zero when nothing has ever recorded a lean, because
  "never measured" and "rode upright" are different answers. Where both the
  device figure and the recorded points have a value, the deeper one wins.
- **Ride windows.** A trip that never recorded an end still has points. It is
  given the longest plausible window (24 hours) but is cut short at the start of
  the next trip, so an unended ride cannot swallow the ride after it — and its
  speed and lean peaks with it.
- Simplification is tolerance-based in metres and corrects for longitude
  covering less ground away from the equator, so a tolerance means the same
  thing north–south and east–west.
- Dropping a point during simplification must not drop the extreme value it was
  carrying — the overlays look back at what the raw track held between two kept
  points.
- Results are briefly cached (tens of seconds) because a dashboard re-renders
  far more often than a ride changes.
- Every numeric parameter is clamped to a sane range; a junk parameter is a
  client error, never a server fault.

### 12.1 Badge catalogue

The tiers are product content and must survive the rewrite:

| Family | Measured on | Tiers |
|---|---|---|
| Distance | Total distance | 100 km, 500 km, 1 000 km, 5 000 km, 10 000 km, 25 000 km |
| Top speed | Top speed | 100, 130, 160, 200, 250 km/h |
| Single ride | Longest single trip | 100 km, 250 km, 500 km |
| Places | Municipalities visited | 3, 10, 25, 50 |
| Coverage | Best coverage percentage | 10, 25, 50, 100 |

Each catalogue entry reports its threshold, the user's current value, when it
was earned (if it was), and progress toward it.

## 13. Administration

A browser-based management surface, gated by an administrator flag on a normal
account and signed into with that account's normal password.

- Non-administrators receive **the same answer as a wrong password**: whether an
  account can reach the dashboard is not something the sign-in form confirms.
- Administrator status is re-checked on **every** request, so revoking it takes
  effect on the next click rather than whenever a session happens to expire.
- Sessions idle-expire (default 12 hours) and are separate from app sessions in
  both directions.
- Every mutating action requires an anti-forgery token in addition to the
  session cookie. A tab left open past a password change fails rather than
  silently acting as a stale administrator.
- The transport-security marking of the session cookie follows how the browser
  actually reached the service, so opening the dashboard over a plain LAN
  address does not produce a sign-in that succeeds and then loops.

Capabilities:

| Action | Rules |
|---|---|
| Overview | Per account: name, email, administrator flag, fog-sharing flag, created and last-seen instants, and **counts** of trips, traces, sessions and API keys, plus total distance. Invite list with status (live / used / expired). Server facts: whether mail is configured, the registration mode, the reset-link lifetime. **No trip, trace, place or route content is readable here, and no capability exists that would allow it.** |
| Create invite | Optional label, optional address, optional lifetime in days (zero or less means it never expires). Optionally emails it. |
| Revoke invite | By code. |
| Set an account's email | Refused if another account already uses that address. |
| Set an account's password | A blank value means "generate one", shown exactly once. Setting it signs that account out everywhere. |
| Send a reset link | Refused when the account has no address. Returns whether mail was sent **and** the link itself, so an administrator can pass it on by hand when mail is not configured. |
| Grant/revoke administrator | Refused if it would remove the last administrator. Refused if an administrator tries to remove their own access — another administrator must do it. Revoking also ends that account's dashboard sessions. |
| Revoke credentials | All sessions, or all API keys, for one account. Revoking sessions also drops that account's live connections. |
| Issue an API key | Optional label; shown exactly once. |
| Delete an account | Refused for the signed-in administrator's own account, and refused if it is the last administrator. Deletes the account **and every row it owns**: trips, traces, points, places, friendships, group memberships, circle places and fixes, keys, sessions. |

Invite codes are the one credential deliberately stored in readable form: an
invite is permission to *create* an account, not access to one, and the whole
point of the list is being able to read a code back weeks later and re-send it.

## 14. Out of scope

Two adjacent self-hosted services are installed alongside but are **not part of
this backend** and are not being rewritten. The app calls them directly.

| Service | Function |
|---|---|
| Routing engine | Generates curvy motorcycle round trips, plus car and bike routes, from an offline map extract. |
| Geocoder | Address and place search, self-hosted so it is fast, private and not rate-limited. |

Also deliberately absent today, and to be treated as a **new** decision rather
than a port: mobile push notifications (no device tokens, no vendor push
service anywhere in the system).

## 15. Cross-cutting behaviour

### 15.1 Transport and payloads

- Requests may be compressed; responses are compressed when the client says it
  can decode them and the payload is worth it (the full trip-and-trace history
  compresses roughly ten to one).
- Request bodies are bounded, and **the decompressed size is bounded too** — a
  compression bomb expands far past the wire limit, and that path is reachable
  before authentication.

### 15.2 Rate limiting

- Sign-in, registration, reset request and reset redemption are rate limited per
  client address: at most 10 **failures** in a 5-minute window.
- Only failures count, so a busy honest client is never locked out while someone
  guessing passwords is stopped.
- The "forgot password" path counts unconditionally, because it sends mail.
- The client address is taken from a proxy header **only when the deployment is
  explicitly configured to trust it**; otherwise anyone could reset the limiter
  per request by spoofing the header.

### 15.3 Errors

- Errors carry a status and a human-readable message; internal faults never leak
  a stack trace or internals to the caller.
- A malformed request is a client error, not a server fault. In particular,
  non-finite numbers (infinity, not-a-number) survive JSON parsing and must be
  rejected explicitly at every numeric boundary rather than blowing up later.
- Existence and permission are deliberately conflated where enumeration would
  otherwise be possible (group identifiers, account existence at sign-in,
  administrator eligibility).

### 15.4 Mail

- Entirely optional. With no relay configured, no mail is ever sent and every
  caller falls back to showing an administrator the link or code to pass on by
  hand.
- A dead relay must never become a server error, and must never let a stranger
  distinguish "no such account" from "mail failed".

### 15.5 Operational actions

Available today outside the API, and needed in some form after the rewrite:

- Issue a read-only API key for an account.
- Revoke all API keys for an account.
- Revoke all sessions for an account ("lost phone").
- Grant or revoke administrator access.
- Set an account's password.
- Re-derive track points from stored traces (a backfill after a schema change).
- Import legacy trip and trace files into an account.

Two of these — revoking sessions and revoking keys — must take effect on
already-open live connections even when performed from a **separate process**
that has no way to signal the running service directly.

## 16. Limits and defaults

| Setting | Default |
|---|---|
| Session idle expiry | 90 days |
| Session last-use write throttle | 1 hour |
| Reset link lifetime | 60 minutes |
| Invite lifetime | 14 days (0 or less = never) |
| Admin session idle expiry | 12 hours |
| Auth failures per address per 5 minutes | 10 |
| Request body | 64 MB, compressed and decompressed |
| Name | 3–24 characters |
| Password | 8–200 characters |
| Email | ≤ 254 characters |
| Group name | 1–40 characters |
| Circle members | 15 |
| Badges per account | 200 |
| Shared route payload | 512 KB |
| Shared routes per (recipient, sender) | 50 |
| Route inbox page | 100 newest |
| Route stops | ≥ 2 |
| Circle place payload | 64 KB |
| Circle place radius | > 0 m and ≤ 50 000 m |
| Circle places per (circle, owner) | 50 |
| Presence events per circle | 500 newest |
| Voice chunk | bounded (≈20 000 encoded characters) |
| Destination candidates per offer | 1–3 |
| Dashboard: rides per request | ≤ 500 |
| Dashboard: trace thinning | 1 in 1 … 1 in 50 |
| Dashboard: ride simplification tolerance | 6 m (0–200) |
| Dashboard: ride point budget | 400 (0–5 000) |
| Dashboard: coverage tolerance | 25 m (0–500) |
| Dashboard: coverage point budget | 6 000 (0–40 000) |
| Dashboard: coverage heat cell | 60 m (10–1 000) |
| Dashboard: result cache | ~20 seconds |

## 17. Known characteristics to reconsider during the rewrite

Recorded as facts about the current system, not as recommendations.

1. **Passwords, sessions, resets, invites and administrator sessions are all
   home-grown.** The rewrite's stated goal is to move identity to a dedicated
   identity provider. That removes §4.2–§4.4, most of §4.1, and the
   credential-management half of §13 — but the *rules* above (fail-closed
   registration, uniform answers, single live reset link, reset revokes
   everything) must be re-expressed as identity-provider configuration, not
   dropped.
2. **API keys and administrator sessions are separate credential systems** that
   also need a home in the new model.
3. **Fog sharing, group membership and friendship are authorisation decisions**
   expressed today as ad-hoc checks. They are domain state, not identity, and
   stay in the application regardless of the identity provider.
4. **The service holds one large opaque blob per trip, route and place.** That is
   a deliberate privacy property (the service cannot read what it does not
   parse), and worth preserving explicitly rather than losing to a
   fully-normalised schema.
5. **Trips and track points are joined by instant, not by identifier**, because
   a trace line carries no trip reference. A rewrite that introduces a real
   foreign key must handle the historical data that has none.
6. **Deletion is user-driven and propagates through the sync merge.** There is
   no server-side retention policy on trips or traces.
7. **The dashboard page and the administration page are served by the backend**
   as self-contained documents. Whether that stays a backend concern is a
   decision, not a given.
