# Circles and convoys: one mechanism, two policies

Design note for [issue #6](https://github.com/maxke24/Detour/issues/6) (Life360-style
circles). Written against `main` @ `4301232`. Line references are to
`server/sync/sync_server.py` at that commit and will drift; the function names will not.

A convoy already *is* a circle. It has a group, a membership table gated on friendship,
and a live position feed — everything a circle needs, running in production today. What
separates the two is not structure but policy: how long the group lives, whether
anything is written down, and how often a phone speaks.

---

## 1. What exists today

Convoys were built for one job: a group of friends on a ride seeing each other on the
map and talking over push-to-talk. The implementation is deliberately thin, and that
thinness is what makes it reusable.

### Server: two tables, five endpoints, one socket

The schema is membership only. Nothing about a convoy's live state touches SQLite:

```sql
-- sync_server.py:376
CREATE TABLE IF NOT EXISTS convoys (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL,
    owner_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS convoy_members (
    convoy_id  INTEGER NOT NULL REFERENCES convoys(id) ON DELETE CASCADE,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status     TEXT NOT NULL CHECK (status IN ('invited', 'accepted')),
    joined_ms  INTEGER NOT NULL,
    PRIMARY KEY (convoy_id, user_id)
);
```

Five HTTP handlers sit on top, all in the 120 lines from `:1333` to `:1453`:
`do_convoy_create`, `do_convoy_invite`, `do_convoy_respond`, `do_convoy_leave`,
`do_convoys`, dispatched through
`CONVOY_ACTION_RE = ^/convoys/(\d+)/(invite|respond|leave)$`. Authorization is one
helper pair — `_convoy_member()` and `is_convoy_member()` at `:1319` — and an invite is
additionally gated on an accepted friendship.

Live state runs beside the HTTP server on its own port (8990 vs 8790), as an asyncio
WebSocket listener in a background thread. Its registry is a single module-level dict:

```python
# sync_server.py:1481 — convoy_id -> {user_id: (username, websocket, token_hash)}
_convoy_sockets = {}
```

The protocol is one JSON object per text frame, after a normal `Authorization: Bearer`
handshake: `join` → `joined`, then `location`, `ptt_start`, `ptt_audio`, `ptt_end`, with
`left` pushed to peers on disconnect.

Guard rails already in place, worth keeping through any merge:

| Guard | Where | What it prevents |
|---|---|---|
| `_valid_location()` | `:1568` | NaN or out-of-range coordinates relayed onward — peers' GeoJSON layers reject NaN and the map dies for everyone |
| `MAX_AUDIO_CHUNK_B64 = 20_000` | `:1495` | Unbounded audio frames from a broken or hostile client |
| `BROADCAST_SEND_TIMEOUT_SEC = 2.0` | `:1498` | One peer on a bad link stalling everyone else's traffic |
| `STALE_SWEEP_INTERVAL_SEC = 15` | `:1491` | Sockets outliving a revoked token when the revocation happened in another process |
| `_convoy_part()` identity check | `:1552` | A slow-closing old socket evicting the reconnect that already replaced it |

### Clients

| File | Role | Size |
|---|---|---|
| `shared/…/data/Convoys.kt` | Membership calls only — create, list, invite, respond, leave | 57 lines |
| `app/…/net/ConvoyLiveClient.kt` | OkHttp WebSocket singleton; StateFlows for peers, talking, connected, activeConvoyId | 335 lines |
| `app/…/convoy/ConvoyLiveService.kt` | Foreground service holding the socket and mic while the screen is off | 191 lines |
| `iosApp/Detour/ConvoyLiveClient.swift` | URLSessionWebSocketTask counterpart | 237 lines |
| `iosApp/Detour/LocationBroadcast.swift` | Pushes the current fix into the socket | 37 lines |
| `iosApp/Detour/ConvoyBar.swift` | The on-map peer strip and PTT button | 84 lines |

### The two invariants that define it

1. **Nothing is persisted.** Per the relay's own comment: a convoy's position and audio
   "exist only as long as the socket does — same spirit as fog: it's a live view between
   consenting members, not a record."
2. **Membership is the only privacy gate.** There is no second check anywhere in the
   relay; if `is_convoy_member` says yes, you receive everything that convoy broadcasts.

---

## 2. What a circle adds

| Capability | Convoy today | Circle needs | Verdict |
|---|---|---|---|
| Group entity | Yes | Same | **Merge** |
| Membership + invite by friend | Yes | Same | **Merge** |
| Authorization gate | `is_convoy_member` | Same, per group | **Merge** |
| Live position transport | WebSocket relay | Same relay, lower cadence | **Merge** |
| Group lifetime | Deleted when empty | Persists while you're alone in it | **Split** |
| Push-to-talk | Yes | **No** | **Split** |
| Sharing state | Connected = sharing | Opt-in, pausable, visible | **Split** |
| Last known position | None stored | Stored, or the map is blank | **New** |
| Shared places | — | Circle-scoped geofences | **New** |
| Arrival notifications | — | Push fan-out | **Blocked** |

### The blocked row, restated

There is no push infrastructure in this project. No FCM, no APNs, no Firebase
dependency, no device-token table. Every notification today is a local foreground-service
notification. Arrival push additionally needs an Apple Developer account and a signing
identity — the iOS workflow builds with `CODE_SIGNING_ALLOWED=NO`. Treat issue #6's work
area 4 as gated on a purchase, not on engineering time.

---

## 3. The merged data model

One entity, discriminated by kind. The `kind` column carries the product distinction;
every policy difference hangs off it or off a column beside it.

```sql
CREATE TABLE IF NOT EXISTS groups (
    id           INTEGER PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('convoy', 'circle')),
    name         TEXT NOT NULL,
    owner_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_ms   INTEGER NOT NULL,
    -- policy as data, not as an `if kind ==` in the leave path:
    -- a convoy with nobody left is dead weight, a circle is not.
    drop_when_empty INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status     TEXT NOT NULL CHECK (status IN ('invited', 'accepted')),
    joined_ms  INTEGER NOT NULL,
    -- circles only: the pause switch. Convoy rows leave it 1 and ignore it.
    sharing    INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (group_id, user_id)
);
```

Two design notes on that shape:

- **`drop_when_empty` rather than a `kind` branch.** The empty-group deletion in
  `do_convoy_leave` (`:1405`) is the single most likely place for a merge to silently
  break circles — someone leaves your family circle, you are alone in it, and it
  evaporates. Making it a column means the shared leave handler never needs to know what
  kind of group it holds.
- **`sharing` lives on the membership, not the group.** Pausing is per person per
  circle. Putting it on the group would make one member's pause everybody's.

### Shared places, when they come

Existing `saved_places` is `(user_id, place_id, json)` with the client's JSON stored
opaquely and round-tripped through `/sync`. Circle places should follow the precedent set
by shared routes: **user-owned, shared into a group, revoked when the sharing
relationship ends**. That answers the issue's open question about ownership without
inventing a second model, and it makes the delete semantics obvious when a creator
leaves.

---

## 4. Migration

The tables are live with real data, and clients cache convoy ids locally, so ids must
survive. SQLite does this without moving a row:

```sql
ALTER TABLE convoys        RENAME TO groups;
ALTER TABLE convoy_members RENAME TO group_members;
ALTER TABLE groups        ADD COLUMN kind TEXT NOT NULL DEFAULT 'convoy';
ALTER TABLE groups        ADD COLUMN drop_when_empty INTEGER NOT NULL DEFAULT 1;
ALTER TABLE group_members ADD COLUMN sharing INTEGER NOT NULL DEFAULT 1;
```

Notes for whoever runs it:

- `init_db()` already carries "added after the first release" column migrations, so this
  fits the existing pattern rather than needing a new mechanism.
- SQLite rewrites foreign-key references in dependent tables on `RENAME TO` under modern
  defaults; verify with `PRAGMA foreign_key_check` on a copy before touching production.
- The convoy id space is unchanged, so a phone holding `activeConvoyId = 7` keeps
  working across the deploy without a re-login.
- The cheaper alternative — keep the table names, add `kind` — also works and is lower
  risk, but leaves circles living permanently in a table called `convoys`. Worth the
  rename while there are two users on the server; not worth it later.

---

## 5. API and client surface

Keep **two endpoint namespaces over one implementation**. Shared handlers, distinct
paths, so the API reads by intent and per-kind rules have an obvious home:

| Path | Handler | Kind-specific behaviour |
|---|---|---|
| `POST /circles` · `POST /convoys` | `do_group_create(kind, …)` | Sets `kind`, `drop_when_empty` |
| `GET /circles` · `GET /convoys` | `do_groups(kind, …)` | `WHERE kind = ?` |
| `POST /{ns}/{id}/invite` | `do_group_invite` | None — friendship gate is identical |
| `POST /{ns}/{id}/respond` | `do_group_respond` | None |
| `POST /{ns}/{id}/leave` | `do_group_leave` | Reads `drop_when_empty`; evicts live sockets either way |
| `POST /circles/{id}/sharing` | `do_group_sharing` | Circles only — 404 on a convoy id |

> **Path naming, learned the hard way.** Do not name anything under `/route…`. The public
> sync hostname shares its tunnel with GraphHopper and the router's `/route` ingress rule
> matches as a *prefix*, so `/routes/*` never reaches the sync server — it answers 404
> from GraphHopper while the identical path returns 401 correctly on localhost. That is
> why route sharing lives at `/shared-routes/*`. Verify every new endpoint through the
> public hostname, not just the box.

On the client, `Convoys.kt` (57 lines) becomes a `Groups` object taking a kind, and the
two UIs stay entirely separate — a circle screen and a convoy screen have almost nothing
visually in common, and merging them would be the one merge with no payoff.

---

## 6. The relay: the one risky edit

Everything above is additive. This part is surgery on shipped, working code that carries
the privacy gate.

The relay is **one scope per socket, by design**. From `handle_live_socket` (`:1602`): a
local `convoy_id` variable tracks the socket's single membership, and joining a second
convoy parts the first. A user who is in a circle continuously *and* starts a convoy for
a ride needs both at once — which that model cannot express.

```
TODAY                                  PROPOSED

socket ──convoy_id = 7──► convoy 7     socket ──groups = {7, 2}──┬──► convoy 7
user 3                    peers dict   user 3                    │    seconds · PTT
                                                                 │
                          circle 2                               └──► circle 2
                          unreachable                                 minutes · no audio
   joining parts the other                 one connection, two cadences
```

The registry keeps its shape — `group_id → {user_id: (username, socket, token_hash)}`.
What changes is that a socket may appear in several of its buckets at once.

### Functions touched

| Function | Line | Change |
|---|---|---|
| `handle_live_socket` | 1602 | Local `convoy_id` becomes a `set` of joined ids; `join` adds rather than replaces; every message routes to the group named in the frame, so `location` / `ptt_*` need a `groupId` field |
| `_convoy_join` | 1542 | Unchanged in shape; still returns the socket it replaced, still per group |
| `_convoy_part` | 1552 | Unchanged — the identity check that stops a slow close evicting a fast reconnect must survive verbatim |
| `_convoy_broadcast` | 1525 | Unchanged |
| socket teardown (`finally`) | 1680 | Parts every joined group, not one, and broadcasts `left` to each |
| `_evict` / `_evict_everywhere` | 1686 | Must not close a socket still legitimately in another group — evict per group, close only when the set empties |
| staleness sweep | — | Re-validates each membership of each socket instead of one |

Roughly 50–70 lines across those. The protocol gains a `groupId` on every non-join frame,
which is a wire-compatible break: an old client sending `location` without one must be
treated as "my only joined group" for one release, or old builds stop showing up on
peers' maps mid-ride.

> **Gate push-to-talk on kind, server-side.** If `ptt_start` / `ptt_audio` / `ptt_end`
> are relayed for any group the socket has joined, every circle silently gains always-on
> voice broadcast between people who signed up for a dot on a map. Hiding the button in
> the UI is not the fix — reject the frame on the server when the target group's kind is
> not `convoy`. This is the single highest-consequence line in the whole merge.

---

## 7. Policy split, line by line

| Dimension | Convoy | Circle | Expressed as |
|---|---|---|---|
| Lifetime | Dies when empty | Survives | `groups.drop_when_empty` |
| Position retention | Nothing written | Latest fix only | New table, written only for `kind='circle'` |
| Cadence | Seconds | Minutes, adaptive | Client policy, no server code |
| Audio | Yes | Never | Server-side kind check in the relay |
| Sharing default | Connected = sharing | Opt-in, pausable | `group_members.sharing` |
| Membership feel | Per ride, invite each time | Long-lived | UI only |
| Place events | — | Arrival / departure | Separate subsystem, circle-only |

The rule that keeps this honest: **if shared code has to ask "is this a convoy?" in more
than about three places, the merge has gone one layer too deep** and the right answer is
two implementations over one schema. Right now the count is exactly one — the PTT gate —
with everything else expressed as a column.

---

## 8. Persistence: the real fork in the road

This is the decision issue #6 defers and shouldn't. A circle whose positions live only in
open sockets shows you nothing until the other person opens the app — which is not the
product. So circles need at minimum:

```sql
CREATE TABLE IF NOT EXISTS member_last_fix (
    group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lat        REAL NOT NULL,
    lon        REAL NOT NULL,
    accuracy_m REAL,
    ts_ms      INTEGER NOT NULL,
    PRIMARY KEY (group_id, user_id)     -- one row per member: latest only, no history
);
```

One row per member, overwritten in place. No history table, no trail. That keeps the
change as small as it can be while still shipping a working feature, and it preserves the
project's current posture: trips are never returned to anyone but their owner, traces stay
private, and the server observes nothing it doesn't have to.

Note what this changes anyway: **the server stops being purely a relay and becomes an
observer**, for circles only. That is defensible — opt-in, pausable, latest-fix-only —
but it deserves a paragraph in the issue rather than a bullet, because it changes what
Detour *is*.

The same reasoning settles the geofence question the issue leaves open: evaluating
transitions on-device keeps the stream off the server entirely, so it is both the cheaper
and the more consistent choice, and it should be decided before any of this is built
rather than "once measured".

---

## 9. Security review of the merge

1. **The gate is load-bearing.** `is_convoy_member` is the *only* thing standing between
   an authenticated stranger and your live position. Merging means one function protects
   two features — good for auditability, unforgiving of a mistake. It deserves direct
   tests, which it does not currently have.
2. **Join must re-check, every time.** Today's handler checks membership on each `join`
   frame rather than caching it. Keep that: a circle connection can be open for days, and
   being removed from a circle has to take effect without waiting for a reconnect.
3. **Eviction paths multiply.** Leaving, being removed, logging out, and
   `--revoke-tokens` from a separate process all have to drop the right sockets — and
   with multi-group sockets, drop the right *memberships* without killing a connection
   that is still valid elsewhere.
4. **Probing.** The existing handlers deliberately return the same 403 for "no such
   convoy" and "not a member" so ids can't be enumerated. Preserve that in the shared
   handler; a merged id space makes enumeration marginally more attractive.
5. **Pause must be enforced server-side.** A member who paused sharing should have their
   `location` frames dropped at the relay, not merely stop being sent by their own client.
   Trusting the client here means a stale build keeps broadcasting after the user believes
   they stopped.

---

## 10. Battery and network

Android is already most of the way there: `ACCESS_BACKGROUND_LOCATION`,
`ACTIVITY_RECOGNITION`, and an always-on `TripTrackingService` with automatic drive
detection. iOS has `UIBackgroundModes: location` for trip recording. Neither platform
needs new background machinery for circles — they need a second consumer of fixes that
already arrive.

**One collector, two sinks.** The failure mode to design against is two independent
location subscriptions — convoy's and circle's — running together during a ride and
doubling the battery cost of the feature that is already the app's most expensive. One
collector, fanning out to whichever sinks are active, with the highest active cadence
winning.

The issue's cadence proposals (adaptive interval, drop sub-threshold movement, batch and
flush) are sound and largely client-side. The one worth resolving early is transport:
circles at minute cadence do not justify holding a WebSocket open all day. A cheap `POST`
of the latest fix, with the socket used only when a screen is actually watching the
circle, is likely both simpler and cheaper — and it degrades gracefully when the phone has
no connectivity.

---

## 11. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| PTT leaks into circles | Critical | Server-side kind check on all three PTT frame types; test that asserts rejection |
| Regression in convoy live location, a shipped feature | High | Tests on the relay's reconnect/eviction semantics *before* the multi-group change |
| Circle deleted when its last member leaves | High | `drop_when_empty` as data, not an `if` |
| Old clients drop off peers' maps after the protocol gains `groupId` | Medium | One release of "no groupId = my only group" tolerance |
| Rename migration corrupts foreign keys | Medium | Rehearse on a copy; `PRAGMA foreign_key_check`; the backup script already exists |
| Double battery drain from two collectors | Medium | Single collector with fan-out |

---

## 12. Rollout

Ordered so the risky step happens after there are tests, and so something useful ships
before the blocked part is unblocked.

| Phase | Contents | Estimate |
|---|---|---|
| 0 · Safety net | Tests for membership gating, reconnect, eviction on the relay as it stands | 1 day |
| 1 · Schema merge | Rename, `kind`, `drop_when_empty`, `sharing`; shared handlers behind both namespaces; convoy behaviour unchanged | 1–2 days |
| 2 · Circles, no live data | Create/invite/leave, both UIs, membership visible — a circle that exists but shows nothing yet | 2–3 days |
| 3 · Relay multi-group | The socket change, `groupId` on frames, PTT gate, server-side pause | 2–3 days |
| 4 · Last fix + map | `member_last_fix`, low-cadence upload, circle map view | 3–4 days |
| 5 · Shared places | Circle-scoped places on the shared-routes precedent | 3–4 days |
| 6 · Arrival events | On-device geofence eval, events posted and shown in-app — *no push* | 4–5 days |
| 7 · Push fan-out | FCM + APNs + token registry | **Blocked** |

Phases 0–6 deliver the whole Life360 shape minus the notification arriving while the app
is closed, and none of them need an Apple Developer account. That is the version worth
building first.

---

## 13. Open questions for the issue

- **Is a circle a convoy that never ends, in the user's mind too?** If yes, one screen
  with two modes may beat two screens — worth a UI sketch before phase 2.
- **Do places belong to a circle or to a user?** Shared routes already answered the
  analogous question: user-owned, shared, revoked with the relationship. Following it
  saves a design round.
- **Circle size cap.** Needed before fan-out cost matters; 10–15 matches the
  family/roommates framing and keeps device geofence budgets irrelevant.
- **Dwell and hysteresis defaults** — radius bounds, minimum dwell — belong on the place
  row, and should be decided with one real GPS trace rather than by intuition.
- **Does the moving member see their own arrival was broadcast?** The issue raises it; it
  should be a requirement, not a consideration.
