#!/usr/bin/env python3
"""Detour sync + social server.

Stores each user's trip history, fog-of-war traces and badges in SQLite, and
lets users befriend each other and compare aggregate stats.

Privacy rules, each enforced in exactly one place:

  - **Trips are never returned for anyone but their owner.** No endpoint reads
    another user's `trips` rows at all.
  - **Traces leave their owner only through `friend_fog`,** and only when both
    users have set `share_fog`. Sharing is off by default, reciprocal (a user
    who does not share sees nothing), and revocable — clearing the flag stops
    the traces being served from the next request on.
  - Friends otherwise see only the aggregate numbers the owner's app computed
    (total km, top speed, badges, …), via `friend_stats`.
  - Convoys and circles are one entity (`groups`, discriminated by `kind`).
    Live position and push-to-talk audio are relayed only to a group's own
    'accepted' members, and only for as long as a socket is actively joined
    to it - joining requires already being an accepted friend of whoever
    invited you. Push-to-talk is rejected server-side for any group whose
    `kind` isn't 'convoy', no matter what a client asks for.
  - **Circles persist one thing convoys never did:** `member_last_fix` keeps
    the latest position per member, overwritten in place, so a circle's map
    isn't blank until the other person's app happens to be open too. A
    member who pauses sharing (`group_members.sharing = 0`) has their
    `location` frames dropped at the relay - enforced there, not just on
    their own client, so a stale build can't keep broadcasting after they
    believe they've stopped.
  - A route can only be shared with an accepted friend, and unfriending
    someone deletes every route shared between you in either direction — a
    route is places you have been, so losing the friendship takes it back.

Protocol
  GET  /health                                  -> "ok"
  POST /auth/register {username, password, invite?, email?} -> {token, username}
  POST /auth/login    {username, password}      -> {token, username}
  POST /auth/logout                             -> {} (revokes the bearer token)
  POST /auth/forgot   {username|email}          -> {} (always; mails a reset link)
  POST /auth/reset    {token, password}         -> {} (consumes the link's token)
  GET  /me                                      -> {username, stats, badges}
  POST /sync {trips, traces, badges, savedPlaces?, stats, shareFog?, deletedTrips?} -> merged {trips, traces, badges, savedPlaces}
  GET  /friends                                 -> {friends, incoming, outgoing}
  POST /friends/request {username}              -> {status}
  POST /friends/respond {username, accept}      -> {status}
  POST /friends/remove  {username}              -> {}
  GET  /friends/stats                           -> [{username, stats, badges}]
  GET  /friends/fog                             -> {sharing, traces: [line, …]}
  POST /shared-routes/share {to, route}         -> {status} (recipient must be an accepted friend)
  GET  /shared-routes/inbox                     -> {routes: [{id, from, createdMs, route}]}
  POST /shared-routes/delete {id}               -> {} (sender or recipient only)
  POST /circles {name} · POST /convoys {name}   -> {id, name} (creator auto-joins)
  GET  /circles        · GET  /convoys          -> [{id, name, status, members: [{username, status, sharing?}]}]
  POST /circles/{id}/invite {username}          -> {status} (must already be friends)
  POST /convoys/{id}/invite {username}          -> {status} (same handler, must already be friends)
  POST /circles/{id}/respond {accept}           -> {status}
  POST /convoys/{id}/respond {accept}           -> {status}
  POST /circles/{id}/leave · POST /convoys/{id}/leave -> {}
  POST /circles/{id}/sharing {sharing}          -> {sharing} (pause/resume; 404 on a convoy id)
  POST /circles/{id}/fix {lat, lon, accuracyM?, ts} -> {status} (low-cadence position upload, no socket needed)
  GET  /circles/{id}/fixes                      -> {fixes: [{username, lat, lon, accuracyM, ts}]} (accepted + sharing members only)
  POST /circle-places/share {groupId, place}    -> {status} (place is user-owned, shared into the circle, revoked on leave)
  GET  /circle-places?groupId=                  -> {places: [{id, owner, name, radiusM, createdMs, place}]}
  POST /circle-places/delete {id}               -> {} (owner only)
  POST /circles/{id}/events {placeId, kind, ts?} -> {status} (kind: arrive|depart; geofencing runs on-device, this just records + fans out)
  GET  /circles/{id}/events?since=              -> {events: [{id, placeId, username, kind, tsMs}]} (includes the caller's own arrivals)
  GET  /ha/stats?key=                           -> {stats, rideCount, badges, badgeCatalogue}
  GET  /ha/rides?key=[&limit=]                  -> {rides: [{startMs, maxLeanDeg, …}]} (limit <= 500)
  GET  /ha/ride.geojson?key=&start=             -> GeoJSON, one Feature per segment
  GET  /ha/traces?key=[&every=]                 -> {traces: [[[lat, lon], …], …]}, caller's own lines only
  GET  /ha/track?key=[&start=&tol=&max=]        -> one ride, simplified, {…stats, geojson, speed{b0…}, lean{b0…}}
  GET  /ha/coverage?key=[&tol=&max=&cell=]      -> all traces, simplified, {…, geojson, heat{b0…}} (heat = rides per cell)
  GET  /ha/ride.html?key=[&start=]              -> the dashboard (Map/Heat/General/Badges tabs)
  GET  /ha/dashboard.html?key=[&start=]         -> alias for /ha/ride.html
  GET  /admin                                   -> the manager dashboard (Overview/Users/Invites/Server)
  POST /admin/login {username, password}        -> {username, csrf} + session cookie
  POST /admin/logout                            -> {}
  GET  /admin/api/overview                      -> {admin, users: [...], invites: [...], mail, registration}
  POST /admin/api/invite/create {label?, email?, days?, send?} -> {code, mailed}
  POST /admin/api/invite/revoke {code}          -> {}
  POST /admin/api/user/<id>/email    {email}    -> {}
  POST /admin/api/user/<id>/password {password?}-> {password?} (blank = generate one)
  POST /admin/api/user/<id>/reset    {}         -> {mailed, link} (mails the reset deep link)
  POST /admin/api/user/<id>/admin    {admin}    -> {}
  POST /admin/api/user/<id>/revoke   {what}     -> {revoked} (what = tokens|keys)
  POST /admin/api/user/<id>/apikey   {label?}   -> {key} (shown once)
  POST /admin/api/user/<id>/delete   {}         -> {} (user and every row they own)

Everything except /health, /auth/*, /ha/* and /admin/* needs `Authorization:
Bearer <token>`. The /ha/* endpoints are read-only and take an API key instead
(?key= or X-API-Key), so a Home Assistant config never holds a login token.

The manager dashboard is a fourth credential path, separate again: a browser
session cookie held only by users with `is_admin`, signed in with their normal
account password. It can hand out invites, reset passwords and delete accounts,
but it never reads anyone's trips or traces — the privacy rules above are not
relaxed for admins, who see only account metadata and row counts.

Convoy and circle live location + push-to-talk run over a *second* listener,
a WebSocket relay on LIVE_PORT (default 8990) shared by both - see the
"group live relay" section below for its message protocol. A socket may be
joined to several groups at once (a circle running all day plus a convoy for
one ride); every non-join frame after `join` carries a `groupId` naming
which one it's for. It requires the `websockets` package; without that
installed, the REST /circles and /convoys endpoints still work
(create/invite/manage), but the relay itself logs a warning and never
starts, so live location/PTT silently do nothing.

Merging is idempotent:
  - trips key on (user, startTimeMs); a re-upload updates the stored copy, so
    an edit like a corrected vehicle mode propagates instead of being ignored;
  - `deletedTrips` is a list of startTimeMs the client has deleted; those rows
    are removed server-side so the deletion propagates to every device instead
    of the trip resurrecting from the server on the next sync;
  - traces deduplicate on (user, sha256 of the line);
  - badges keep the *earliest* earnedAtMs seen for each id.

Trace lines are `[[lat, lon, tMs, speedKmh, leanDeg], …]`. Lines that arrive
new are also unpacked into track_points, one row per recorded point, which is
what the /ha/* endpoints read. Points are tied to a ride by timestamp: a trace
line carries no trip id, so t_ms between a trip's start and end is the join.
Older two-element points predate this and stay fog-only.

Auth notes
  - Passwords: PBKDF2-HMAC-SHA256, per-user random salt, ITERATIONS rounds.
  - Tokens: 32 random bytes, stored only as a SHA-256 hash. A database leak
    does not hand over live sessions.
  - Comparisons use hmac.compare_digest.
  - Login and register are rate limited per client IP.

This still expects to sit behind the Cloudflare tunnel + Access, exactly as the
old version did. Access is a gate on the hostname; the bearer token is identity.
Bind to localhost — HOST=0.0.0.0 also serves the LAN, which is how Home
Assistant reaches /ha/* without the tunnel. Note that TRUST_CF_HEADER then
believes a LAN client's CF-Connecting-IP too, so the rate limiter can be
side-stepped from inside the network. HOST also decides where the group
live relay binds.

Python 3.8+ stdlib only, except the group live relay which needs the
`websockets` package (optional - see above). DATA_DIR env var sets the
storage directory; LIVE_PORT sets the relay's port (default 8990, same HOST
as the main server).

CLI:
  python3 sync_server.py                      run the server
  python3 sync_server.py --import-legacy USER import old trips.json/traces.jsonl
  python3 sync_server.py --api-key USER [LABEL]   mint a read-only dashboard key
  python3 sync_server.py --backfill-points USER   re-unpack traces into points
  python3 sync_server.py --revoke-keys USER       delete all API keys for a user
  python3 sync_server.py --revoke-tokens USER     sign a user out everywhere
  python3 sync_server.py --make-admin USER        let USER into /admin
  python3 sync_server.py --drop-admin USER        take that away again
  python3 sync_server.py --set-password USER      set a password (prompts)
"""
import asyncio
import getpass
import gzip
import hashlib
import hmac
import io
import json
import math
import os
import re
import secrets
import smtplib
import sqlite3
import ssl
import sys
import threading
import time
from email.message import EmailMessage
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote as urlquote, urlparse

# Optional: only the group live-location/PTT relay needs this. The rest of
# the server (trips, friends, fog, HA endpoints) works with stdlib alone, so
# a homelab that hasn't run `pip install websockets` yet still gets a
# working sync server - just without the live relay.
try:
    import websockets
except ImportError:
    websockets = None

DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(__file__), "data"))
DB_FILE = os.path.join(DATA_DIR, "detour.db")
LEGACY_TRIPS = os.path.join(DATA_DIR, "trips.json")
LEGACY_TRACES = os.path.join(DATA_DIR, "traces.jsonl")

MAX_BODY = 64 * 1024 * 1024
ITERATIONS = 210_000
TOKEN_BYTES = 32

# A token that never expires is a token that stays valid forever once leaked
# (a stolen config export, a lost phone). Idle beyond this many days, it's
# rejected and pruned. last_used_ms writes are throttled to once per interval
# so an active session doesn't cost a write under the lock on every request.
TOKEN_MAX_IDLE_MS = int(os.environ.get("TOKEN_MAX_IDLE_DAYS", "90")) * 86400 * 1000
TOKEN_TOUCH_INTERVAL_MS = 3600 * 1000

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.-]{3,24}$")
BADGE_ID_RE = re.compile(r"^[a-z]+_[0-9]+$")
MAX_BADGES = 200

# A shared route is one JSON blob, stored opaquely like a trip. Cap its
# serialized size so a malicious or buggy client can't use /shared-routes/share to
# stuff an arbitrarily large blob into a friend's inbox — 512 KB is generous
# for a polyline-and-stops route and nowhere near MAX_BODY.
MAX_ROUTE_JSON_BYTES = 512 * 1024
# Newest-first, capped so a friend who shares constantly can't grow another
# user's inbox response without bound.
ROUTES_INBOX_LIMIT = 100
# Caps what a sender can WRITE, not just what the inbox returns: without this,
# ROUTES_INBOX_LIMIT only hides the excess rows while the table itself grows
# without bound, one 512 KB blob at a time. Enforced per (to, from) pair
# rather than per recipient, so one friend who shares constantly can only
# ever push out their *own* older shares — not crowd a quieter friend's
# routes out of your inbox.
MAX_SHARED_ROUTES_PER_PAIR = 50

# A circle place is a name, a point and a radius - far smaller than a route's
# polyline, so it gets a smaller cap than MAX_ROUTE_JSON_BYTES rather than
# reusing it.
MAX_PLACE_JSON_BYTES = 64 * 1024
# Same shape as MAX_SHARED_ROUTES_PER_PAIR: a write cap enforced per (group,
# owner), not just a display cap, so one member can't grow a circle's place
# list without bound.
MAX_CIRCLE_PLACES_PER_OWNER = 50
# Newest-N retention for a circle's arrival/departure feed - the same
# per-relationship-cap idea as MAX_SHARED_ROUTES_PER_PAIR, scoped to the
# group instead of a pair since an event has no "recipient" to key on.
MAX_PLACE_EVENTS_PER_GROUP = 500
# The doc's own open question settles on 10-15 for the family/roommates
# framing; capped before circle fan-out cost (or device geofence budgets)
# becomes a problem worth measuring instead of deciding.
MAX_CIRCLE_MEMBERS = 15

# Only these stat keys are stored, and only as finite numbers. A friend's app
# cannot push arbitrary blobs into a payload other people will read.
STAT_KEYS = (
    "totalDistanceMeters",
    "topSpeedKmh",
    "longestTripMeters",
    "maxLeanDeg",
    "municipalitiesVisited",
    "bestCoveragePercent",
    "tripCount",
)

# Fails closed: someone running this by hand with no env set should not get
# an open /auth/register without asking for it. Open it explicitly with
# REGISTRATION_OPEN=1 (the installer does this for --open-registration), or
# gate it on a shared invite code instead.
REGISTRATION_OPEN = os.environ.get("REGISTRATION_OPEN", "0") != "0"
INVITE_CODE = os.environ.get("INVITE_CODE", "")

# Single-use invites and password-reset links both expire; an invite that sits
# in a mailbox for a year is a way in long after you stopped meaning to offer
# one, and a reset link is a live account takeover for as long as it lasts.
INVITE_DEFAULT_DAYS = int(os.environ.get("INVITE_DEFAULT_DAYS", "14"))
RESET_TTL_MS = int(os.environ.get("RESET_TTL_MINUTES", "60")) * 60 * 1000
ADMIN_SESSION_IDLE_MS = int(os.environ.get("ADMIN_SESSION_HOURS", "12")) * 3600 * 1000
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s.]+\.[^@\s]+$")

# Outgoing mail. Unset SMTP_HOST simply means no mail is ever sent: every
# caller falls back to handing the admin the link to pass on by hand, so the
# dashboard is fully usable without an SMTP relay.
SMTP_HOST = os.environ.get("SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("SMTP_PORT", "587"))
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASS = os.environ.get("SMTP_PASS", "")
SMTP_FROM = os.environ.get("SMTP_FROM", SMTP_USER)
SMTP_SECURITY = os.environ.get("SMTP_SECURITY", "starttls").lower()  # starttls|ssl|none
SITE_NAME = os.environ.get("SITE_NAME", "Detour")
# Reset mails link into the app, not into a web page: the server sits behind
# Cloudflare Access, so a browser link would hit the Access login wall, while
# the app already holds the service token. The mail carries the raw code too,
# for a mail client that won't linkify a custom scheme.
APP_SCHEME = os.environ.get("APP_SCHEME", "detour")

# Only trust the Cloudflare header when explicitly deployed behind the tunnel;
# otherwise any client could spoof it and reset the rate limiter per request.
TRUST_CF_HEADER = os.environ.get("TRUST_CF_HEADER", "0") == "1"

# Per-IP rate limit on the auth endpoints.
AUTH_MAX_ATTEMPTS = 10
AUTH_WINDOW_SEC = 300

_local = threading.local()
_attempts = {}
_attempts_lock = threading.Lock()
_write_lock = threading.Lock()


class HttpError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------
# database


def db():
    """One connection per thread; WAL so readers never block the writer."""
    conn = getattr(_local, "conn", None)
    if conn is None:
        conn = sqlite3.connect(DB_FILE, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=30000")
        _local.conn = conn
    return conn


def init_db():
    conn = db()
    # A circle is a convoy that never ends (docs/CIRCLES_AND_CONVOYS.md) - the
    # merge renames rather than standing up a second table, so a phone
    # holding a cached convoy id keeps working across the deploy. This has
    # to run *before* the CREATE TABLE IF NOT EXISTS below: rename first, and
    # the "IF NOT EXISTS" for `groups`/`group_members` sees them already
    # there (under the old column set) and leaves the data alone. SQLite
    # rewrites group_members' own foreign-key text to point at `groups` as
    # part of the first rename, so the second rename is just a name change.
    # RENAME TABLE doesn't touch column names, so convoy_members.convoy_id
    # has to be renamed separately - every query below this point (and every
    # handler in the file) reads `group_id`, not `convoy_id`.
    tables = {r["name"] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "convoys" in tables and "groups" not in tables:
        conn.execute("ALTER TABLE convoys RENAME TO groups")
        conn.execute("ALTER TABLE convoy_members RENAME TO group_members")
        conn.execute("ALTER TABLE group_members RENAME COLUMN convoy_id TO group_id")
        conn.execute("DROP INDEX IF EXISTS idx_convoy_members_user")
        conn.commit()
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS users (
            id         INTEGER PRIMARY KEY,
            username   TEXT NOT NULL UNIQUE COLLATE NOCASE,
            pw_salt    BLOB NOT NULL,
            pw_hash    BLOB NOT NULL,
            iterations INTEGER NOT NULL,
            created_ms INTEGER NOT NULL,
            stats_json TEXT NOT NULL DEFAULT '{}',
            badges_json TEXT NOT NULL DEFAULT '{}',
            share_fog  INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS tokens (
            token_hash   TEXT PRIMARY KEY,
            user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_ms   INTEGER NOT NULL,
            last_used_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS trips (
            user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            start_ms INTEGER NOT NULL,
            json     TEXT NOT NULL,
            PRIMARY KEY (user_id, start_ms)
        );
        CREATE TABLE IF NOT EXISTS traces (
            user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            line_hash TEXT NOT NULL,
            line      TEXT NOT NULL,
            PRIMARY KEY (user_id, line_hash)
        );
        -- Trace lines unpacked into one row per recorded point, so Home
        -- Assistant can ask for a ride's speed and lean without parsing
        -- JSONL. Filled on sync from points that carry a timestamp; older
        -- two-element points have nothing to unpack and stay fog-only.
        CREATE TABLE IF NOT EXISTS track_points (
            user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            t_ms      INTEGER NOT NULL,
            lat       REAL NOT NULL,
            lon       REAL NOT NULL,
            speed_kmh REAL,
            lean_deg  REAL,
            PRIMARY KEY (user_id, t_ms)
        );
        -- Read-only keys for dashboards. Separate from tokens: a key pasted
        -- into a Home Assistant config can only read, and revoking it does
        -- not sign the phone out.
        CREATE TABLE IF NOT EXISTS api_keys (
            key_hash   TEXT PRIMARY KEY,
            user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            label      TEXT NOT NULL,
            created_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS saved_places (
            user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            place_id INTEGER NOT NULL,
            json     TEXT NOT NULL,
            PRIMARY KEY (user_id, place_id)
        );
        -- One row per pair, with low_id < high_id so a pair can never be
        -- represented twice. requested_by says who has to accept.
        CREATE TABLE IF NOT EXISTS friendships (
            low_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            high_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status       TEXT NOT NULL CHECK (status IN ('pending', 'accepted')),
            requested_by INTEGER NOT NULL,
            created_ms   INTEGER NOT NULL,
            PRIMARY KEY (low_id, high_id)
        );
        -- A route shared friend-to-friend. Keyed so re-sharing the same route
        -- (an edited name, a redrawn stop) replaces the earlier copy instead
        -- of piling up duplicates in the recipient's inbox; from_id is part
        -- of the key too, so two different friends sharing a route with the
        -- same client-side id don't collide with each other. route_id is the
        -- sender's own id for the route and is otherwise meaningless here —
        -- the server never looks inside the stored JSON beyond what it needs
        -- to validate and cap it.
        CREATE TABLE IF NOT EXISTS shared_routes (
            id          INTEGER PRIMARY KEY,
            from_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            to_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            route_id    INTEGER NOT NULL,
            name        TEXT NOT NULL,
            json        TEXT NOT NULL,
            created_ms  INTEGER NOT NULL,
            UNIQUE (to_id, from_id, route_id)
        );
        -- A group is the "granted access" gate for live location + PTT, and
        -- a convoy and a circle are the same entity discriminated by `kind`
        -- (see docs/CIRCLES_AND_CONVOYS.md): you can only be invited by an
        -- accepted friend (checked in do_group_invite), and only members
        -- with status='accepted' show up to each other. drop_when_empty is
        -- policy as data rather than an `if kind ==` in the leave path - a
        -- convoy with nobody left is dead weight, a circle is not.
        CREATE TABLE IF NOT EXISTS groups (
            id              INTEGER PRIMARY KEY,
            kind            TEXT NOT NULL DEFAULT 'convoy' CHECK (kind IN ('convoy', 'circle')),
            name            TEXT NOT NULL,
            owner_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_ms      INTEGER NOT NULL,
            drop_when_empty INTEGER NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS group_members (
            group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status     TEXT NOT NULL CHECK (status IN ('invited', 'accepted')),
            joined_ms  INTEGER NOT NULL,
            -- Circles only: the pause switch. Convoy rows leave it 1 and the
            -- relay ignores it for them - pausing is per person per circle,
            -- not per group, which is why it lives on the membership row.
            sharing    INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY (group_id, user_id)
        );
        -- Circles only: the latest fix per member, overwritten in place - no
        -- history, no trail (docs/CIRCLES_AND_CONVOYS.md section 8). A
        -- convoy never writes a row here; its live position stays relay-only
        -- and unpersisted, same as it always was.
        CREATE TABLE IF NOT EXISTS member_last_fix (
            group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            lat        REAL NOT NULL,
            lon        REAL NOT NULL,
            accuracy_m REAL,
            ts_ms      INTEGER NOT NULL,
            PRIMARY KEY (group_id, user_id)
        );
        -- A circle place, on the shared_routes precedent exactly: user-owned,
        -- shared into a group, revoked when the sharing relationship ends
        -- (the owner leaving that circle). Opaque JSON blob, same as
        -- saved_places and shared_routes - the server validates only what it
        -- needs to cap and index it.
        CREATE TABLE IF NOT EXISTS circle_places (
            id         INTEGER PRIMARY KEY,
            group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            owner_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            place_id   INTEGER NOT NULL,
            name       TEXT NOT NULL,
            json       TEXT NOT NULL,
            radius_m   REAL NOT NULL,
            created_ms INTEGER NOT NULL,
            UNIQUE (group_id, owner_id, place_id)
        );
        -- Arrival/departure events. Geofence transitions are evaluated
        -- on-device (docs/CIRCLES_AND_CONVOYS.md section 8) - this table just
        -- stores the result. Fan-out is the live relay's `place_event` frame
        -- (see broadcast_place_event), with this table as the catch-up path
        -- for whoever wasn't connected. Still no FCM/APNs involved.
        CREATE TABLE IF NOT EXISTS place_events (
            id       INTEGER PRIMARY KEY,
            group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            place_id INTEGER NOT NULL,
            user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind     TEXT NOT NULL CHECK (kind IN ('arrive', 'depart')),
            ts_ms    INTEGER NOT NULL
        );
        -- Invites the manager dashboard hands out: one code, one account.
        -- The code is stored in the clear on purpose, unlike every other
        -- credential here. It is a permission to create an account, not access
        -- to one, and the whole point of the invite list is being able to read
        -- a code back out weeks later to re-send it to whoever lost the mail.
        CREATE TABLE IF NOT EXISTS invites (
            code       TEXT PRIMARY KEY,
            label      TEXT NOT NULL DEFAULT '',
            email      TEXT,
            created_ms INTEGER NOT NULL,
            expires_ms INTEGER,
            used_ms    INTEGER,
            used_by    TEXT
        );
        -- Password reset links. Hashed like a token, single use, short lived;
        -- redeeming one signs the account out everywhere, because a reset is
        -- also the answer to "someone else has my phone".
        CREATE TABLE IF NOT EXISTS password_resets (
            token_hash TEXT PRIMARY KEY,
            user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_ms INTEGER NOT NULL,
            expires_ms INTEGER NOT NULL,
            used_ms    INTEGER
        );
        -- Browser sessions for /admin. Separate from tokens so signing out of
        -- the dashboard never touches a phone's sync session, and so a stolen
        -- bearer token cannot be replayed at the admin API.
        CREATE TABLE IF NOT EXISTS admin_sessions (
            session_hash TEXT PRIMARY KEY,
            user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            csrf         TEXT NOT NULL,
            created_ms   INTEGER NOT NULL,
            last_used_ms INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);
        CREATE INDEX IF NOT EXISTS idx_resets_user ON password_resets(user_id);
        CREATE INDEX IF NOT EXISTS idx_points_user_t ON track_points(user_id, t_ms);
        CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id);
        CREATE INDEX IF NOT EXISTS idx_shared_routes_to ON shared_routes(to_id);
        CREATE INDEX IF NOT EXISTS idx_circle_places_group ON circle_places(group_id);
        CREATE INDEX IF NOT EXISTS idx_place_events_group ON place_events(group_id, id);
        """
    )
    # Added after the first release; CREATE TABLE IF NOT EXISTS won't add it to
    # a database that already exists.
    columns = {r["name"] for r in conn.execute("PRAGMA table_info(users)")}
    if "share_fog" not in columns:
        conn.execute("ALTER TABLE users ADD COLUMN share_fog INTEGER NOT NULL DEFAULT 0")
    if "email" not in columns:
        conn.execute("ALTER TABLE users ADD COLUMN email TEXT")
    if "is_admin" not in columns:
        conn.execute("ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0")
    # Same "added after the first release" pattern, for a DB that just went
    # through the convoys -> groups rename above: the renamed tables keep
    # their old column set until these are added.
    group_columns = {r["name"] for r in conn.execute("PRAGMA table_info(groups)")}
    if "kind" not in group_columns:
        conn.execute("ALTER TABLE groups ADD COLUMN kind TEXT NOT NULL DEFAULT 'convoy'")
    if "drop_when_empty" not in group_columns:
        conn.execute("ALTER TABLE groups ADD COLUMN drop_when_empty INTEGER NOT NULL DEFAULT 1")
    member_columns = {r["name"] for r in conn.execute("PRAGMA table_info(group_members)")}
    if "sharing" not in member_columns:
        conn.execute("ALTER TABLE group_members ADD COLUMN sharing INTEGER NOT NULL DEFAULT 1")
    conn.commit()


def now_ms():
    return int(time.time() * 1000)


# --------------------------------------------------------------------------
# auth


def hash_password(password, salt=None, iterations=ITERATIONS):
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return salt, digest, iterations


def token_hash(raw):
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def issue_token(user_id):
    raw = secrets.token_urlsafe(TOKEN_BYTES)
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO tokens (token_hash, user_id, created_ms, last_used_ms)"
                " VALUES (?, ?, ?, ?)",
                (token_hash(raw), user_id, now_ms(), now_ms()),
            )
    return raw


def authenticate(headers):
    header = headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HttpError(401, "missing bearer token")
    thash = token_hash(header[7:].strip())
    row = db().execute(
        "SELECT u.*, t.last_used_ms FROM tokens t JOIN users u ON u.id = t.user_id"
        " WHERE t.token_hash = ?",
        (thash,),
    ).fetchone()
    if row is None:
        raise HttpError(401, "invalid token")
    now = now_ms()
    if now - row["last_used_ms"] > TOKEN_MAX_IDLE_MS:
        raise HttpError(401, "token expired")
    if now - row["last_used_ms"] > TOKEN_TOUCH_INTERVAL_MS:
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                conn.execute(
                    "UPDATE tokens SET last_used_ms = ? WHERE token_hash = ?",
                    (now, thash),
                )
    return row


def rate_limit(ip):
    """Only *failures* count, so a busy honest client is never locked out while
    someone guessing passwords is stopped after AUTH_MAX_ATTEMPTS."""
    cutoff = time.time() - AUTH_WINDOW_SEC
    with _attempts_lock:
        hits = [t for t in _attempts.get(ip, []) if t > cutoff]
        if hits:
            _attempts[ip] = hits
        else:
            _attempts.pop(ip, None)  # keep the table from growing forever
        if len(hits) >= AUTH_MAX_ATTEMPTS:
            raise HttpError(429, "too many attempts; wait a few minutes")


def note_failure(ip):
    with _attempts_lock:
        _attempts.setdefault(ip, []).append(time.time())


def find_user(username):
    return db().execute(
        "SELECT * FROM users WHERE username = ? COLLATE NOCASE", (username,)
    ).fetchone()


def clean_email(raw):
    """Normalise an address, or None for "not set". Deliberately loose: the
    only thing the server does with an address is send to it, so the relay is
    the real validator. This just keeps obvious junk out of the table."""
    email = str(raw or "").strip()
    if not email:
        return None
    if len(email) > 254 or not EMAIL_RE.match(email):
        raise HttpError(400, "that does not look like an email address")
    return email


# --------------------------------------------------------------------------
# mail
#
# Every mail this server sends is a link the recipient asked for (an invite, a
# password reset). Nothing here is required: with SMTP_HOST unset the dashboard
# just shows the admin the code to pass on by hand.


def mail_configured():
    return bool(SMTP_HOST and SMTP_FROM)


def send_mail(to, subject, body):
    """Best effort; True when the relay accepted the message.

    Never raises. A dead relay must not become a 500 — the admin paths fall
    back to showing the link, and the self-service path must answer a stranger
    identically whether or not an address existed to mail.
    """
    if not mail_configured() or not to:
        return False
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = SMTP_FROM
    msg["To"] = to
    # 7bit where the text allows it. The default is quoted-printable, which
    # soft-wraps long lines with a trailing "=" — landing in the middle of a
    # reset link for any client that shows the raw body.
    msg.set_content(body, cte="7bit" if body.isascii() else "quoted-printable")
    try:
        context = ssl.create_default_context()
        if SMTP_SECURITY == "ssl":
            smtp = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=15, context=context)
        else:
            smtp = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15)
        with smtp:
            if SMTP_SECURITY == "starttls":
                smtp.starttls(context=context)
            if SMTP_USER:
                smtp.login(SMTP_USER, SMTP_PASS)
            smtp.send_message(msg)
        return True
    except Exception as e:  # noqa: BLE001 - a relay problem is not a request failure
        print("MAIL to %s failed: %r" % (to, e))
        return False


def reset_link(raw):
    return "%s://reset?token=%s" % (APP_SCHEME, urlquote(raw))


def send_reset_mail(username, email, raw):
    return send_mail(
        email,
        "%s: reset your password" % SITE_NAME,
        "Someone asked to reset the password for %s on %s.\n\n"
        "Open this link on the phone that has the app installed:\n\n"
        "  %s\n\n"
        "If the link is not tappable, open the app, go to Friends > Forgot "
        "password, and paste this code instead:\n\n"
        "  %s\n\n"
        "The link is good for %d minutes and can be used once. Setting a new "
        "password signs the account out on every device.\n\n"
        # Plain ASCII on purpose: one em dash would push the whole body into
        # quoted-printable, and a soft-wrapped "=" inside the link is exactly
        # the kind of thing a picky mail client mangles.
        "If this was not you, ignore this mail. Nothing has changed yet.\n"
        % (username, SITE_NAME, reset_link(raw), raw, RESET_TTL_MS // 60000),
    )


def send_invite_mail(email, code, expires_ms):
    when = (
        "It expires on %s."
        % time.strftime("%d %b %Y", time.localtime(expires_ms / 1000))
        if expires_ms
        else "It does not expire."
    )
    return send_mail(
        email,
        "%s: your invite" % SITE_NAME,
        "You have been invited to %s.\n\n"
        "Install the app, open Friends, and create an account with this invite "
        "code:\n\n"
        "  %s\n\n"
        "%s It works once.\n" % (SITE_NAME, code, when),
    )


# --------------------------------------------------------------------------
# invites


def invite_row(code):
    """The usable invite for a code, or None. Used and expired codes stay in
    the table (the dashboard shows what became of them) but never match here."""
    code = str(code or "").strip()
    if not code:
        return None
    row = db().execute("SELECT * FROM invites WHERE code = ?", (code,)).fetchone()
    if row is None or row["used_ms"] is not None:
        return None
    if row["expires_ms"] is not None and row["expires_ms"] < now_ms():
        return None
    return row


def create_invite(label="", email=None, days=INVITE_DEFAULT_DAYS):
    """Mint a single-use code. days <= 0 means it never expires."""
    code = secrets.token_urlsafe(9)
    expires = now_ms() + int(days) * 86400 * 1000 if days and int(days) > 0 else None
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO invites (code, label, email, created_ms, expires_ms)"
                " VALUES (?, ?, ?, ?, ?)",
                (code, label or "", email, now_ms(), expires),
            )
    return code, expires


def do_register(body, ip):
    rate_limit(ip)
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    code = str(body.get("invite", "")).strip()
    email = clean_email(body.get("email"))
    if not USERNAME_RE.match(username):
        raise HttpError(400, "username must be 3-24 chars: letters, digits, . _ -")
    if not 8 <= len(password) <= 200:
        raise HttpError(400, "password must be 8-200 characters")

    # Three ways in, checked in this order: a single-use invite from the
    # manager dashboard, the shared INVITE_CODE env, or an explicitly open
    # server with no shared code set. The last clause is what keeps the old
    # behaviour: a server with both REGISTRATION_OPEN and INVITE_CODE still
    # demands a code, exactly as it did before invites existed.
    invite = invite_row(code)
    if invite is None and not (INVITE_CODE and hmac.compare_digest(code, INVITE_CODE)):
        if not REGISTRATION_OPEN or INVITE_CODE:
            if code:
                note_failure(ip)  # guessing a code is an attack, not a typo
                raise HttpError(403, "invalid or expired invite code")
            raise HttpError(403, "this server needs an invite code")
    if invite is not None and invite["email"] and not email:
        email = invite["email"]  # the address the invite was addressed to
    if find_user(username):
        raise HttpError(409, "username already taken")

    salt, digest, iterations = hash_password(password)
    with _write_lock:
        conn = db()
        try:
            with conn:  # commits on success, rolls back on exception
                cur = conn.execute(
                    "INSERT INTO users (username, pw_salt, pw_hash, iterations,"
                    " created_ms, email) VALUES (?, ?, ?, ?, ?, ?)",
                    (username, salt, digest, iterations, now_ms(), email),
                )
                user_id = cur.lastrowid
                if invite is not None:
                    # Burn the code in the same transaction that created the
                    # account, so two people racing one invite cannot both win.
                    used = conn.execute(
                        "UPDATE invites SET used_ms = ?, used_by = ?"
                        " WHERE code = ? AND used_ms IS NULL",
                        (now_ms(), username, invite["code"]),
                    ).rowcount
                    if not used:
                        raise HttpError(403, "invalid or expired invite code")
        except sqlite3.IntegrityError:
            raise HttpError(409, "username already taken")
    return {"token": issue_token(user_id), "username": username}


# --------------------------------------------------------------------------
# password reset


def create_reset(user_id):
    """One live link per account: minting a new one drops any earlier unused
    link, so a second "forgot password" tap cannot leave two ways in."""
    raw = secrets.token_urlsafe(TOKEN_BYTES)
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute("DELETE FROM password_resets WHERE user_id = ?", (user_id,))
            conn.execute(
                "INSERT INTO password_resets (token_hash, user_id, created_ms, expires_ms)"
                " VALUES (?, ?, ?, ?)",
                (token_hash(raw), user_id, now_ms(), now_ms() + RESET_TTL_MS),
            )
    return raw


def set_password(user_id, password):
    """Set a password and end every session the account had — bearer tokens and
    the admin cookie both. Whoever knew the old password (or held a stolen
    token) is out from this call on."""
    if not 8 <= len(password) <= 200:
        raise HttpError(400, "password must be 8-200 characters")
    salt, digest, iterations = hash_password(password)
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "UPDATE users SET pw_salt = ?, pw_hash = ?, iterations = ? WHERE id = ?",
                (salt, digest, iterations, user_id),
            )
            conn.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
            conn.execute("DELETE FROM admin_sessions WHERE user_id = ?", (user_id,))
    evict_user_everywhere(user_id)


def do_forgot(body, ip):
    """Always answers {}. Whether an account exists, and whether it has an
    address on file, are not things an unauthenticated caller gets to learn."""
    rate_limit(ip)
    note_failure(ip)  # unconditional: this endpoint sends mail, so cap it hard
    wanted = str(body.get("username", "") or body.get("email", "")).strip()
    if not wanted:
        return {}
    user = find_user(wanted)
    if user is None and EMAIL_RE.match(wanted):
        user = db().execute(
            "SELECT * FROM users WHERE email = ? COLLATE NOCASE", (wanted,)
        ).fetchone()
    if user is not None and user["email"]:
        send_reset_mail(user["username"], user["email"], create_reset(user["id"]))
    return {}


def do_reset(body, ip):
    rate_limit(ip)
    password = str(body.get("password", ""))
    thash = token_hash(str(body.get("token", "")).strip())
    row = db().execute(
        "SELECT * FROM password_resets WHERE token_hash = ?", (thash,)
    ).fetchone()
    if row is None or row["used_ms"] is not None or row["expires_ms"] < now_ms():
        note_failure(ip)
        raise HttpError(400, "that reset link is invalid or has expired")
    set_password(row["user_id"], password)  # also revokes tokens and sessions
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "UPDATE password_resets SET used_ms = ? WHERE token_hash = ?",
                (now_ms(), thash),
            )
    user = db().execute(
        "SELECT username FROM users WHERE id = ?", (row["user_id"],)
    ).fetchone()
    return {"username": user["username"] if user else ""}


def do_login(body, ip):
    rate_limit(ip)
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    user = find_user(username)
    if user is None:
        # Spend the same work as a real check so timing doesn't reveal whether
        # the account exists.
        hash_password(password, salt=b"\x00" * 16)
        note_failure(ip)
        raise HttpError(401, "wrong username or password")
    _, digest, _ = hash_password(password, bytes(user["pw_salt"]), user["iterations"])
    if not hmac.compare_digest(digest, bytes(user["pw_hash"])):
        note_failure(ip)
        raise HttpError(401, "wrong username or password")
    return {"token": issue_token(user["id"]), "username": user["username"]}


def do_logout(user, headers):
    raw = headers.get("Authorization", "")[7:].strip()
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute("DELETE FROM tokens WHERE token_hash = ?", (token_hash(raw),))
    # A revoked token must not keep relaying through an already-open group
    # socket - see evict_user_everywhere in the group live relay section.
    evict_user_everywhere(user["id"])
    return {}


# --------------------------------------------------------------------------
# sync


def clean_stats(raw):
    """Keep only known numeric keys, and only finite ones."""
    out = {}
    if not isinstance(raw, dict):
        return out
    for key in STAT_KEYS:
        value = raw.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        if value != value or value in (float("inf"), float("-inf")):
            continue
        out[key] = value
    return out


def clean_badges(raw):
    out = {}
    if not isinstance(raw, dict):
        return out
    for badge_id, earned in list(raw.items())[:MAX_BADGES]:
        if not BADGE_ID_RE.match(str(badge_id)):
            continue
        if isinstance(earned, bool) or not isinstance(earned, int):
            continue
        out[str(badge_id)] = earned
    return out


def store_points(conn, uid, points):
    """Unpack one trace line's `[lat, lon, tMs, speedKmh, leanDeg]` points.

    Anything shorter is a pre-timestamp point: it still draws fog, but there is
    no instant to hang it on, so it is skipped here rather than stored with a
    made-up time. Bad values are dropped point by point — one broken reading
    must not cost the whole ride.
    """
    rows = []
    for p in points:
        if not isinstance(p, list) or len(p) < 3:
            continue
        try:
            lat, lon, t_ms = float(p[0]), float(p[1]), int(p[2])
            speed = float(p[3]) if len(p) > 3 and p[3] is not None else None
            lean = float(p[4]) if len(p) > 4 and p[4] is not None else None
        except (TypeError, ValueError):
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180 and t_ms > 0):
            continue
        rows.append((uid, t_ms, lat, lon, speed, lean))
    if rows:
        conn.executemany(
            "INSERT OR IGNORE INTO track_points"
            " (user_id, t_ms, lat, lon, speed_kmh, lean_deg) VALUES (?, ?, ?, ?, ?, ?)",
            rows,
        )


def do_sync(user, body):
    uid = user["id"]
    trips_in = body.get("trips") or []
    traces_in = body.get("traces") or []
    places_in = body.get("savedPlaces") or []
    deleted_in = body.get("deletedTrips") or []
    if not isinstance(trips_in, list) or not isinstance(traces_in, list):
        raise HttpError(400, "trips and traces must be arrays")
    if not isinstance(places_in, list):
        raise HttpError(400, "savedPlaces must be an array")
    if not isinstance(deleted_in, list):
        raise HttpError(400, "deletedTrips must be an array")

    # Validate every trip and saved place up front, before writing any of them,
    # so a single malformed entry can't leave a partial import committed — the
    # whole sync succeeds or the whole sync fails.
    for trip in trips_in:
        if not isinstance(trip, dict) or "startTimeMs" not in trip:
            raise HttpError(400, "trip missing startTimeMs")
    for place in places_in:
        if not isinstance(place, dict) or "id" not in place:
            raise HttpError(400, "saved place missing id")

    badges = clean_badges(json.loads(user["badges_json"]))
    for badge_id, earned in clean_badges(body.get("badges")).items():
        # First time earned wins, so a reinstall can't reset the date forward.
        if badge_id not in badges or earned < badges[badge_id]:
            badges[badge_id] = earned

    # Absent means "no update", not "clear". A client that syncs only trips must
    # not blank out the stats its friends are reading.
    stats = (
        clean_stats(body["stats"])
        if "stats" in body
        else json.loads(user["stats_json"])
    )

    # Absent means "leave it alone", so an old client that knows nothing about
    # shared fog can't silently turn a user's sharing off (or on).
    share_fog = user["share_fog"]
    if "shareFog" in body:
        share_fog = 1 if body["shareFog"] else 0

    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            for trip in trips_in:
                # Upsert, not INSERT OR IGNORE: a trip re-uploaded with edited
                # fields (e.g. a corrected vehicle mode) must replace the stored
                # copy, or the stale row would come back in the merge and revert
                # the edit.
                conn.execute(
                    "INSERT INTO trips (user_id, start_ms, json) VALUES (?, ?, ?) "
                    "ON CONFLICT(user_id, start_ms) DO UPDATE SET json = excluded.json",
                    (uid, int(trip["startTimeMs"]), json.dumps(trip)),
                )
            # Deletes run after the upserts so a trip present in both lists ends
            # up deleted, and so the removal propagates instead of the server
            # copy coming back in the merged union on the next sync.
            for start_ms in deleted_in:
                conn.execute(
                    "DELETE FROM trips WHERE user_id = ? AND start_ms = ?",
                    (uid, int(start_ms)),
                )
            for line in traces_in:
                line = str(line).strip()
                if not line:
                    continue
                points = json.loads(line)  # reject broken lines instead of storing them
                cur = conn.execute(
                    "INSERT OR IGNORE INTO traces (user_id, line_hash, line) VALUES (?, ?, ?)",
                    (uid, hashlib.sha256(line.encode()).hexdigest(), line),
                )
                # Every sync re-uploads every line it holds, so unpacking on the
                # IGNORE path would re-parse the whole history each time. Only a
                # line that was actually new here has points worth inserting.
                if cur.rowcount:
                    store_points(conn, uid, points)
            for place in places_in:
                # Upsert by id so a rename replaces the stored copy; the merge
                # below returns the union, which is what restores shortcuts
                # after reinstall.
                conn.execute(
                    "INSERT INTO saved_places (user_id, place_id, json) VALUES (?, ?, ?) "
                    "ON CONFLICT(user_id, place_id) DO UPDATE SET json = excluded.json",
                    (uid, int(place["id"]), json.dumps(place)),
                )
            conn.execute(
                "UPDATE users SET badges_json = ?, stats_json = ?, share_fog = ? WHERE id = ?",
                (json.dumps(badges), json.dumps(stats), share_fog, uid),
            )

    trips = [
        json.loads(r["json"])
        for r in db().execute(
            "SELECT json FROM trips WHERE user_id = ? ORDER BY start_ms DESC", (uid,)
        )
    ]
    traces = [
        r["line"]
        for r in db().execute("SELECT line FROM traces WHERE user_id = ?", (uid,))
    ]
    saved_places = [
        json.loads(r["json"])
        for r in db().execute(
            "SELECT json FROM saved_places WHERE user_id = ? ORDER BY place_id", (uid,)
        )
    ]
    return {"trips": trips, "traces": traces, "badges": badges,
            "savedPlaces": saved_places}


def do_me(user):
    return {
        "username": user["username"],
        "email": user["email"] or "",
        "stats": json.loads(user["stats_json"]),
        "badges": json.loads(user["badges_json"]),
    }


# --------------------------------------------------------------------------
# friends


def pair(a, b):
    return (a, b) if a < b else (b, a)


def friendship(a, b):
    low, high = pair(a, b)
    return db().execute(
        "SELECT * FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
    ).fetchone()


def other_user(body):
    username = str(body.get("username", "")).strip()
    if not USERNAME_RE.match(username):
        raise HttpError(400, "bad username")
    row = find_user(username)
    if row is None:
        raise HttpError(404, "no such user")
    return row


def do_friend_request(user, body):
    target = other_user(body)
    if target["id"] == user["id"]:
        raise HttpError(400, "you are already your own friend")

    existing = friendship(user["id"], target["id"])
    if existing and existing["status"] == "accepted":
        return {"status": "accepted"}
    if existing and existing["status"] == "pending":
        if existing["requested_by"] == user["id"]:
            return {"status": "pending"}
        # They asked us first; asking back is the same as accepting.
        return do_friend_respond(user, {"username": target["username"], "accept": True})

    low, high = pair(user["id"], target["id"])
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO friendships (low_id, high_id, status, requested_by, created_ms)"
                " VALUES (?, ?, 'pending', ?, ?)",
                (low, high, user["id"], now_ms()),
            )
    return {"status": "pending"}


def do_friend_respond(user, body):
    target = other_user(body)
    existing = friendship(user["id"], target["id"])
    if existing is None or existing["status"] != "pending":
        raise HttpError(404, "no pending request from that user")
    if existing["requested_by"] == user["id"]:
        raise HttpError(403, "you sent this request; they must accept it")

    low, high = pair(user["id"], target["id"])
    accept = bool(body.get("accept"))
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            if accept:
                conn.execute(
                    "UPDATE friendships SET status = 'accepted'"
                    " WHERE low_id = ? AND high_id = ?",
                    (low, high),
                )
            else:
                conn.execute(
                    "DELETE FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
                )
    return {"status": "accepted" if accept else "declined"}


def do_friend_remove(user, body):
    target = other_user(body)
    low, high = pair(user["id"], target["id"])
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "DELETE FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
            )
            # A shared route carries places you have been — unfriending takes
            # it back, same as it stops friend_fog and convoy invites. Both
            # directions: routes either of you sent the other are removed,
            # not just the ones you received.
            conn.execute(
                "DELETE FROM shared_routes WHERE (from_id = ? AND to_id = ?)"
                " OR (from_id = ? AND to_id = ?)",
                (user["id"], target["id"], target["id"], user["id"]),
            )
    return {}


def _friend_rows(uid):
    return db().execute(
        "SELECT f.status, f.requested_by, u.id, u.username"
        " FROM friendships f"
        " JOIN users u ON u.id = CASE WHEN f.low_id = ? THEN f.high_id ELSE f.low_id END"
        " WHERE f.low_id = ? OR f.high_id = ?",
        (uid, uid, uid),
    ).fetchall()


def do_friends(user):
    friends, incoming, outgoing = [], [], []
    for row in _friend_rows(user["id"]):
        if row["status"] == "accepted":
            friends.append(row["username"])
        elif row["requested_by"] == user["id"]:
            outgoing.append(row["username"])
        else:
            incoming.append(row["username"])
    return {"friends": friends, "incoming": incoming, "outgoing": outgoing}


def friend_stats(user):
    """The only endpoint that returns another user's data. Aggregates only:
    it reads stats_json and badges_json, and never touches trips or traces."""
    out = []
    for row in _friend_rows(user["id"]):
        if row["status"] != "accepted":
            continue
        friend = db().execute(
            "SELECT username, stats_json, badges_json FROM users WHERE id = ?",
            (row["id"],),
        ).fetchone()
        out.append(
            {
                "username": friend["username"],
                "stats": json.loads(friend["stats_json"]),
                "badges": json.loads(friend["badges_json"]),
            }
        )
    out.sort(key=lambda f: -f["stats"].get("totalDistanceMeters", 0))
    return out


def friend_fog(user):
    """The only endpoint that returns another user's traces.

    Two conditions, both required, both checked here: the caller shares their
    own fog, and so does the friend whose traces are about to be handed over.
    A user who turns sharing off therefore both stops contributing and stops
    receiving, which is what makes the trade legible.

    Lines come back unattributed — the union is a map, not a per-friend history.
    """
    if not user["share_fog"]:
        return {"sharing": False, "traces": []}

    friend_ids = [
        row["id"] for row in _friend_rows(user["id"]) if row["status"] == "accepted"
    ]
    if not friend_ids:
        return {"sharing": True, "traces": []}

    placeholders = ",".join("?" * len(friend_ids))
    rows = db().execute(
        "SELECT t.line FROM traces t JOIN users u ON u.id = t.user_id"
        " WHERE t.user_id IN (%s) AND u.share_fog = 1" % placeholders,
        friend_ids,
    )
    return {"sharing": True, "traces": [r["line"] for r in rows]}


# --------------------------------------------------------------------------
# shared routes
#
# A route is stored exactly like a trip: one opaque JSON blob the server
# never parses beyond the little it needs to validate and cap it. Sharing is
# friend-gated the same way convoy invites are — `friendship()` is the one
# check that matters, and it is re-checked on every share, not just at invite
# time, so a route can't be pushed to someone you unfriended a moment ago.


def do_route_share(user, body):
    """Share one route with an accepted friend.

    Upserts on the (to, from, route_id) unique key, so re-sharing a route you
    already shared — an edited name, a redrawn stop — replaces the earlier
    copy in the recipient's inbox instead of appearing twice.
    """
    # other_user() reads body["username"]; the wire format for this endpoint
    # is {"to": ..., "route": ...}, so translate rather than duplicate the
    # username validation it already does.
    target = other_user({"username": body.get("to")})
    if target["id"] == user["id"]:
        raise HttpError(400, "you cannot share a route with yourself")
    fs = friendship(user["id"], target["id"])
    if fs is None or fs["status"] != "accepted":
        raise HttpError(403, "you can only share routes with friends")

    route = body.get("route")
    if not isinstance(route, dict):
        raise HttpError(400, "route missing or not an object")
    # id is a long millisecond timestamp on the wire; validate the type here
    # rather than letting int() raise ValueError/TypeError for a string or
    # object id, which do_POST would otherwise turn into an opaque 500. The
    # finite check matters too: json.loads happily parses Infinity, -Infinity
    # and NaN (and 1e400 overflows to inf), all of which are instances of
    # float but blow up in int() with OverflowError/ValueError, same 500 class
    # as the string case.
    route_id_raw = route.get("id")
    if (
        not isinstance(route_id_raw, (int, float))
        or isinstance(route_id_raw, bool)
        or not math.isfinite(route_id_raw)
    ):
        raise HttpError(400, "route id must be a number")
    route_id = int(route_id_raw)
    stops = route.get("stops")
    if not isinstance(stops, list) or len(stops) < 2:
        raise HttpError(400, "route needs at least 2 stops")
    raw = json.dumps(route)
    if len(raw.encode("utf-8")) > MAX_ROUTE_JSON_BYTES:
        raise HttpError(413, "route too large")
    name = str(route.get("name") or "").strip()[:200] or "Route"

    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO shared_routes"
                " (from_id, to_id, route_id, name, json, created_ms)"
                " VALUES (?, ?, ?, ?, ?, ?)"
                " ON CONFLICT(to_id, from_id, route_id) DO UPDATE SET"
                " name = excluded.name, json = excluded.json,"
                " created_ms = excluded.created_ms",
                (user["id"], target["id"], route_id, name, raw, now_ms()),
            )
            # Enforce the per-pair cap in the same transaction as the write
            # that could exceed it: keep the newest MAX_SHARED_ROUTES_PER_PAIR
            # rows for this (to, from) pair, drop the rest. This is a write
            # cap, not a display cap — ROUTES_INBOX_LIMIT alone would only
            # hide the excess while the table kept growing.
            conn.execute(
                "DELETE FROM shared_routes WHERE id IN ("
                " SELECT id FROM shared_routes WHERE to_id = ? AND from_id = ?"
                " ORDER BY created_ms DESC, id DESC LIMIT -1 OFFSET ?)",
                (target["id"], user["id"], MAX_SHARED_ROUTES_PER_PAIR),
            )
    return {"status": "shared"}


def do_routes_inbox(user):
    """Routes shared with the caller, newest first, capped at
    ROUTES_INBOX_LIMIT.

    sharedBy is set here from the sender's authenticated username, not read
    out of whatever the sender put in the stored JSON — the same reasoning as
    friend_fog only ever trusting `share_fog` off the row it just read, not
    off anything the other side claims.
    """
    rows = db().execute(
        "SELECT sr.id, sr.json, sr.created_ms, u.username AS from_username"
        " FROM shared_routes sr JOIN users u ON u.id = sr.from_id"
        " WHERE sr.to_id = ? ORDER BY sr.created_ms DESC LIMIT ?",
        (user["id"], ROUTES_INBOX_LIMIT),
    )
    out = []
    for row in rows:
        route = json.loads(row["json"])
        route["sharedBy"] = row["from_username"]
        out.append(
            {
                "id": row["id"],
                "from": row["from_username"],
                "createdMs": row["created_ms"],
                "route": route,
            }
        )
    return {"routes": out}


def do_route_delete(user, body):
    """Deletes a shared_routes row the caller is either side of: the
    recipient dropping it from their inbox, or the sender un-sharing it.

    The WHERE clause is the whole access check — a caller who is neither the
    sender nor the recipient of that row matches nothing and deletes nothing,
    same shape as do_friend_remove needing no separate ownership lookup.
    """
    try:
        # int(float('inf')) raises OverflowError, not ValueError — a bare
        # (TypeError, ValueError) here would let an Infinity/NaN id through
        # to become a 500, same bug as the one just fixed in do_route_share.
        row_id = int(body.get("id"))
    except (TypeError, ValueError, OverflowError):
        raise HttpError(400, "bad id")
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "DELETE FROM shared_routes WHERE id = ? AND (to_id = ? OR from_id = ?)",
                (row_id, user["id"], user["id"]),
            )
    return {}


# --------------------------------------------------------------------------
# groups (convoys and circles: live location + push-to-talk membership)
#
# A convoy and a circle are the same entity, discriminated by `kind`
# (docs/CIRCLES_AND_CONVOYS.md). Membership is the only privacy gate for the
# live WebSocket relay (see the `websockets` listener below): a socket can
# only join a group's broadcast if it authenticates as a user with an
# 'accepted' row for that group_id. Everything below is shared between the
# two kinds except do_group_sharing (pause is a circle concept) and the size
# cap on invite (circles only); a convoy's live position/PTT audio still
# never touches SQLite, and a circle's does only via member_last_fix
# (phase 4, below).


def _group_member(group_id, user_id):
    return db().execute(
        "SELECT * FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, user_id),
    ).fetchone()


def is_group_member(group_id, user_id):
    """Used by the WS join handshake, where a 404 vs 403 distinction isn't
    worth the extra round trip - it just wants a yes/no."""
    row = _group_member(group_id, user_id)
    return row is not None and row["status"] == "accepted"


def _group_row(group_id):
    return db().execute("SELECT * FROM groups WHERE id = ?", (group_id,)).fetchone()


def do_group_create(user, kind, body):
    name = str(body.get("name", "")).strip()
    if not 1 <= len(name) <= 40:
        raise HttpError(400, "name must be 1-40 characters")
    now = now_ms()
    # A convoy with nobody left in it is dead weight; a circle persists while
    # you're alone in it. See the `drop_when_empty` comment on the table.
    drop_when_empty = 0 if kind == "circle" else 1
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            cur = conn.execute(
                "INSERT INTO groups (kind, name, owner_id, created_ms, drop_when_empty)"
                " VALUES (?, ?, ?, ?, ?)",
                (kind, name, user["id"], now, drop_when_empty),
            )
            group_id = cur.lastrowid
            conn.execute(
                "INSERT INTO group_members (group_id, user_id, status, joined_ms)"
                " VALUES (?, ?, 'accepted', ?)",
                (group_id, user["id"], now),
            )
    return {"id": group_id, "name": name}


def do_group_invite(user, group_id, body):
    # Membership checked before anything else exists to distinguish "no such
    # group" from "not a member" - either way the caller gets the same 403,
    # so a random group id can't be used to probe which ids are real.
    membership = _group_member(group_id, user["id"])
    if membership is None or membership["status"] != "accepted":
        raise HttpError(403, "not a member of this group")
    target = other_user(body)
    if target["id"] == user["id"]:
        raise HttpError(400, "you are already in this group")
    # Group membership can only ever come from an existing friendship - this
    # is what makes "granted access" mean something instead of an open room.
    fs = friendship(user["id"], target["id"])
    if fs is None or fs["status"] != "accepted":
        raise HttpError(403, "you can only invite friends")
    existing = _group_member(group_id, target["id"])
    if existing is not None:
        return {"status": existing["status"]}
    group = _group_row(group_id)
    if group is not None and group["kind"] == "circle":
        count = db().execute(
            "SELECT COUNT(*) AS n FROM group_members WHERE group_id = ?", (group_id,)
        ).fetchone()["n"]
        if count >= MAX_CIRCLE_MEMBERS:
            raise HttpError(400, "circle is full")
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO group_members (group_id, user_id, status, joined_ms)"
                " VALUES (?, ?, 'invited', ?)",
                (group_id, target["id"], now_ms()),
            )
    return {"status": "invited"}


def do_group_respond(user, group_id, body):
    membership = _group_member(group_id, user["id"])
    if membership is None or membership["status"] != "invited":
        raise HttpError(404, "no pending invite to that group")
    accept = bool(body.get("accept"))
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            if accept:
                conn.execute(
                    "UPDATE group_members SET status = 'accepted', joined_ms = ?"
                    " WHERE group_id = ? AND user_id = ?",
                    (now_ms(), group_id, user["id"]),
                )
            else:
                conn.execute(
                    "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
                    (group_id, user["id"]),
                )
    return {"status": "accepted" if accept else "declined"}


def do_group_leave(user, group_id, body):
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
                (group_id, user["id"]),
            )
            # Places this member shared into the group go with them - same
            # "revoked when the sharing relationship ends" rule shared_routes
            # applies on unfriending. A convoy never has rows here, so this
            # is a no-op for one.
            conn.execute(
                "DELETE FROM circle_places WHERE group_id = ? AND owner_id = ?",
                (group_id, user["id"]),
            )
            conn.execute(
                "DELETE FROM member_last_fix WHERE group_id = ? AND user_id = ?",
                (group_id, user["id"]),
            )
            # Read drop_when_empty as data, not `kind` - this is the one
            # place a merge bug would silently evaporate someone's circle the
            # moment they're the last one left in it.
            group = conn.execute(
                "SELECT drop_when_empty FROM groups WHERE id = ?", (group_id,)
            ).fetchone()
            if group is not None and group["drop_when_empty"]:
                remaining = conn.execute(
                    "SELECT COUNT(*) AS n FROM group_members WHERE group_id = ?", (group_id,)
                ).fetchone()["n"]
                if remaining == 0:
                    conn.execute("DELETE FROM groups WHERE id = ?", (group_id,))
    # Instantly drops any live socket this user still has open on this
    # group - see evict_group_member in the group live relay section. A
    # no-op (harmlessly) if they were never a member or had no live socket.
    evict_group_member(group_id, user["id"])
    return {}


def do_groups(user, kind):
    rows = db().execute(
        "SELECT g.id, g.name, m.status"
        " FROM group_members m JOIN groups g ON g.id = m.group_id"
        " WHERE m.user_id = ? AND g.kind = ?",
        (user["id"], kind),
    ).fetchall()
    out = []
    for row in rows:
        members = db().execute(
            "SELECT u.username, m.status, m.sharing FROM group_members m"
            " JOIN users u ON u.id = m.user_id WHERE m.group_id = ?",
            (row["id"],),
        ).fetchall()
        member_out = []
        for m in members:
            entry = {"username": m["username"], "status": m["status"]}
            # Sharing is a circle-only concept; a convoy connection *is*
            # sharing, so there's nothing meaningful to show on that screen.
            if kind == "circle":
                entry["sharing"] = bool(m["sharing"])
            member_out.append(entry)
        out.append({
            "id": row["id"],
            "name": row["name"],
            "status": row["status"],
            "members": member_out,
        })
    return out


def do_group_sharing(user, group_id, body):
    """The pause switch - circles only. 404s on a convoy id: a convoy
    connection *is* sharing, so there's nothing to pause, and treating a
    convoy id as "not found" here rather than "not applicable" keeps this
    endpoint from becoming a second way to ask "is this a convoy?"."""
    group = _group_row(group_id)
    if group is None or group["kind"] != "circle":
        raise HttpError(404, "not found")
    membership = _group_member(group_id, user["id"])
    if membership is None or membership["status"] != "accepted":
        raise HttpError(403, "not a member of this circle")
    sharing = 1 if body.get("sharing") else 0
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "UPDATE group_members SET sharing = ? WHERE group_id = ? AND user_id = ?",
                (sharing, group_id, user["id"]),
            )
    return {"sharing": bool(sharing)}


GROUP_ACTION_RE = re.compile(r"^/(?:circles|convoys)/(\d+)/(invite|respond|leave)$")
CIRCLE_POST_RE = re.compile(r"^/circles/(\d+)/(sharing|fix|events)$")
CIRCLE_GET_RE = re.compile(r"^/circles/(\d+)/(fixes|events)$")


def _require_group_membership(group_id, user_id):
    """Gate shared by every circle-only extension below (fix, fixes, places,
    events): same 403 whether the group doesn't exist, is a convoy, or the
    caller just isn't an accepted member of it (docs/CIRCLES_AND_CONVOYS.md
    section 9.4 - "no such group" and "not a member" get the same status so
    ids can't be enumerated). The merged id space is exactly what makes that
    worth preserving here too - do_group_sharing is the one endpoint allowed
    to 404 on a convoy id instead, per the doc's own API table, and it's
    reachable only with an id the caller already holds."""
    group = _group_row(group_id)
    if group is None or group["kind"] != "circle":
        raise HttpError(403, "not a member of this circle")
    membership = _group_member(group_id, user_id)
    if membership is None or membership["status"] != "accepted":
        raise HttpError(403, "not a member of this circle")


def _store_member_fix(group_id, user_id, lat, lon, accuracy_m, ts_ms):
    """One row per member, overwritten in place - no history, no trail (see
    the member_last_fix comment in init_db). Shared by the HTTP /fix upload
    and the relay's own `location` frames, which write here too when the
    target group is a circle."""
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO member_last_fix"
                " (group_id, user_id, lat, lon, accuracy_m, ts_ms)"
                " VALUES (?, ?, ?, ?, ?, ?)"
                " ON CONFLICT(group_id, user_id) DO UPDATE SET"
                " lat = excluded.lat, lon = excluded.lon,"
                " accuracy_m = excluded.accuracy_m, ts_ms = excluded.ts_ms",
                (group_id, user_id, lat, lon, accuracy_m, ts_ms),
            )


def do_circle_fix(user, group_id, body):
    """The low-cadence transport the design doc argues for: circles update on
    the order of minutes, which doesn't justify holding a socket open all
    day the way a convoy's second-by-second feed does."""
    _require_group_membership(group_id, user["id"])
    fix = _valid_fix(body)
    if fix is None:
        raise HttpError(400, "bad location")
    _store_member_fix(
        group_id, user["id"], fix["lat"], fix["lon"], fix["accuracyM"], fix["ts"]
    )
    return {"status": "ok"}


def do_circle_fixes(user, group_id):
    """Latest fix per accepted, currently-sharing member. A paused member's
    last position is excluded here even though the row may still exist -
    the same server-side enforcement of pause as the relay's location drop,
    just for the read path instead of the write path."""
    _require_group_membership(group_id, user["id"])
    rows = db().execute(
        "SELECT u.username, f.lat, f.lon, f.accuracy_m, f.ts_ms"
        " FROM member_last_fix f"
        " JOIN group_members m ON m.group_id = f.group_id AND m.user_id = f.user_id"
        " JOIN users u ON u.id = f.user_id"
        " WHERE f.group_id = ? AND m.status = 'accepted' AND m.sharing = 1",
        (group_id,),
    ).fetchall()
    return {
        "fixes": [
            {
                "username": r["username"],
                "lat": r["lat"],
                "lon": r["lon"],
                "accuracyM": r["accuracy_m"],
                "ts": r["ts_ms"],
            }
            for r in rows
        ]
    }


# --------------------------------------------------------------------------
# circle places
#
# Follows the shared_routes precedent exactly: user-owned, shared into a
# group, revoked when the sharing relationship ends - here, the owner
# leaving that circle (see do_group_leave). Same opaque-JSON-blob shape and
# the same per-relationship write cap idea as shared_routes.


def do_circle_place_share(user, body):
    group_id = _require_int_body(body, "groupId")
    _require_group_membership(group_id, user["id"])

    place = body.get("place")
    if not isinstance(place, dict):
        raise HttpError(400, "place missing or not an object")
    # Same finite-number checks as do_route_share's route id, for the same
    # reason: json.loads accepts Infinity/NaN, and int() on those raises
    # OverflowError/ValueError rather than failing cleanly.
    place_id_raw = place.get("id")
    if (
        not isinstance(place_id_raw, (int, float))
        or isinstance(place_id_raw, bool)
        or not math.isfinite(place_id_raw)
    ):
        raise HttpError(400, "place id must be a number")
    place_id = int(place_id_raw)
    try:
        radius_m = float(place.get("radiusM"))
    except (TypeError, ValueError):
        raise HttpError(400, "place needs a numeric radiusM")
    if not (radius_m == radius_m and 0 < radius_m <= 50_000):
        raise HttpError(400, "radiusM out of range")
    raw = json.dumps(place)
    if len(raw.encode("utf-8")) > MAX_PLACE_JSON_BYTES:
        raise HttpError(413, "place too large")
    name = str(place.get("name") or "").strip()[:200] or "Place"

    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO circle_places"
                " (group_id, owner_id, place_id, name, json, radius_m, created_ms)"
                " VALUES (?, ?, ?, ?, ?, ?, ?)"
                " ON CONFLICT(group_id, owner_id, place_id) DO UPDATE SET"
                " name = excluded.name, json = excluded.json,"
                " radius_m = excluded.radius_m, created_ms = excluded.created_ms",
                (group_id, user["id"], place_id, name, raw, radius_m, now_ms()),
            )
            # Write cap, not just a display cap - keep the newest
            # MAX_CIRCLE_PLACES_PER_OWNER rows for this (group, owner) pair.
            conn.execute(
                "DELETE FROM circle_places WHERE id IN ("
                " SELECT id FROM circle_places WHERE group_id = ? AND owner_id = ?"
                " ORDER BY created_ms DESC, id DESC LIMIT -1 OFFSET ?)",
                (group_id, user["id"], MAX_CIRCLE_PLACES_PER_OWNER),
            )
    return {"status": "shared"}


def do_circle_places(user, params):
    group_id = _required_int_param(params, "groupId")
    membership = _group_member(group_id, user["id"])
    if membership is None or membership["status"] != "accepted":
        raise HttpError(403, "not a member of this circle")
    rows = db().execute(
        "SELECT p.id, p.json, p.name, p.radius_m, p.created_ms, u.username AS owner"
        " FROM circle_places p JOIN users u ON u.id = p.owner_id"
        " WHERE p.group_id = ? ORDER BY p.created_ms DESC",
        (group_id,),
    ).fetchall()
    out = []
    for row in rows:
        place = json.loads(row["json"])
        place["sharedBy"] = row["owner"]
        out.append(
            {
                "id": row["id"],
                "owner": row["owner"],
                "name": row["name"],
                "radiusM": row["radius_m"],
                "createdMs": row["created_ms"],
                "place": place,
            }
        )
    return {"places": out}


def do_circle_place_delete(user, body):
    """Deletes a circle_places row the caller owns - same
    ownership-is-the-access-check shape as do_route_delete, except a circle
    place has exactly one owner rather than two sides."""
    row_id = _require_int_body(body, "id")
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "DELETE FROM circle_places WHERE id = ? AND owner_id = ?",
                (row_id, user["id"]),
            )
    return {}


# --------------------------------------------------------------------------
# circle arrival/departure events
#
# Geofence transitions are evaluated on-device (docs/CIRCLES_AND_CONVOYS.md
# section 8) - this just records the result and fans it out to the rest of
# the circle. No push: phase 7 (FCM/APNs/device tokens) is explicitly
# blocked on an Apple Developer account this project doesn't have.


def _circle_place_name(group_id, place_id):
    """Best-effort name for a place_event's placeId. That id is the
    caller-assigned circle_places.place_id (see CirclePlaces.kt / GeofenceEvaluator),
    not the circle_places row id, so it's only unique per (group, owner) -
    two members could in theory pick the same integer for their own place.
    Picking the newest match keeps this cheap; it's only ever used to word a
    notification, not to identify a row. Empty string if the place was
    deleted out from under a transition already in flight."""
    row = db().execute(
        "SELECT name FROM circle_places WHERE group_id = ? AND place_id = ?"
        " ORDER BY created_ms DESC LIMIT 1",
        (group_id, place_id),
    ).fetchone()
    return row["name"] if row else ""


def do_circle_event_create(user, group_id, body):
    _require_group_membership(group_id, user["id"])
    place_id = _require_int_body(body, "placeId")
    kind = body.get("kind")
    if kind not in ("arrive", "depart"):
        raise HttpError(400, "kind must be arrive or depart")
    try:
        ts = int(body.get("ts", now_ms()))
    except (TypeError, ValueError, OverflowError):
        ts = now_ms()
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO place_events (group_id, place_id, user_id, kind, ts_ms)"
                " VALUES (?, ?, ?, ?, ?)",
                (group_id, place_id, user["id"], kind, ts),
            )
            # Newest-N retention per circle, same write-cap idea as
            # MAX_SHARED_ROUTES_PER_PAIR - keep the recent window, drop the
            # rest, so one chatty member can't grow this table without bound.
            conn.execute(
                "DELETE FROM place_events WHERE id IN ("
                " SELECT id FROM place_events WHERE group_id = ?"
                " ORDER BY ts_ms DESC, id DESC LIMIT -1 OFFSET ?)",
                (group_id, MAX_PLACE_EVENTS_PER_GROUP),
            )
    # Fire the live frame after the commit, not before - a peer must never be
    # able to poll GET /circles/{id}/events and find nothing there yet for a
    # frame it just received. Best effort: see broadcast_place_event.
    broadcast_place_event(
        group_id, place_id, _circle_place_name(group_id, place_id),
        user["username"], kind, ts, exclude_user_id=user["id"],
    )
    return {"status": "recorded"}


def do_circle_events(user, group_id, params):
    """Membership-gated read of a circle's recent arrive/depart events,
    including the caller's own - the design doc makes that a requirement
    rather than a nice-to-have, so there is no `user_id != ?` filter here."""
    _require_group_membership(group_id, user["id"])
    since = _int_param(params, "since", 0, 0, 2**53)
    # placeName comes from a correlated subquery, not a JOIN, because
    # circle_places.place_id is only unique per (group, owner) - see
    # _circle_place_name - and a plain JOIN on (group_id, place_id) could
    # multiply a single event into several rows if two members ever share
    # the same locally-assigned place id.
    rows = db().execute(
        "SELECT e.id, e.place_id, e.kind, e.ts_ms, u.username,"
        " (SELECT name FROM circle_places p WHERE p.group_id = e.group_id"
        "  AND p.place_id = e.place_id ORDER BY p.created_ms DESC LIMIT 1) AS place_name"
        " FROM place_events e JOIN users u ON u.id = e.user_id"
        " WHERE e.group_id = ? AND e.ts_ms > ? ORDER BY e.ts_ms ASC",
        (group_id, since),
    ).fetchall()
    return {
        "events": [
            {
                "id": r["id"],
                "placeId": r["place_id"],
                "placeName": r["place_name"] or "",
                "username": r["username"],
                "kind": r["kind"],
                "tsMs": r["ts_ms"],
            }
            for r in rows
        ]
    }


# --------------------------------------------------------------------------
# group live relay (WebSocket, separate port)
#
# A second listener next to the HTTP server, since a live position feed and
# push-to-talk audio need push, not request/response. Runs its own asyncio
# loop in a background thread; the HTTP server's threads never touch this
# module-level state, so no lock is needed around it.
#
# One socket, many groups (docs/CIRCLES_AND_CONVOYS.md section 6): a user in
# a circle all day who also starts a convoy for a ride needs both live at
# once, so `join` adds a membership rather than replacing the one the socket
# already had. Protocol, one JSON text message per line, after connecting
# with the same `Authorization: Bearer <token>` header the REST API uses:
#   -> {"type": "join", "groupId": N}
#   <- {"type": "joined", "groupId": N, "convoyId": N}  or  {"type": "error", ...}
#   -> {"type": "location", "groupId": N, "lat", "lon", "headingDeg"?, "speedKmh"?, "ts"}
#   <- {"type": "location", "groupId": N, "user": "<username>", ...same fields}
#   -> {"type": "ptt_start", "groupId": N}          <- relayed, convoy groups only
#   -> {"type": "ptt_audio", "groupId": N, "chunk": "<base64 16kHz mono PCM16>"}  <- same
#   -> {"type": "ptt_end", "groupId": N}             <- relayed, convoy groups only
#   -> {"type": "spin_offer", "groupId": N,          <- relayed, convoy groups only
#       "candidates": [{"lat", "lon", "distanceM"?, "durationS"?, "name"?}, 1-3 of them]}
#   -> {"type": "spin_vote", "groupId": N, "index": 0-2}   <- relayed, convoy groups only
# A spin_offer carrying a single candidate means something different to the
# app than one carrying three: three is a sheet to vote on, one is the
# member who opened the round announcing the winner, which every client
# commits on sight. The relay doesn't care - it validates and forwards
# either the same way - but the convention is what keeps a convoy from
# splitting across two destinations, so it is documented here rather than
# only in the client.
#   <- {"type": "left", "groupId": N, "user": "<username>"} (peer disconnected or left)
#   <- {"type": "place_event", "groupId": N, "placeId": N, "placeName": "School",
#       "user": "<username>", "kind": "arrive"|"depart", "tsMs": N}
# place_event is server -> client only: geofence transitions are decided
# on-device and recorded over HTTP (do_circle_event_create), same as before
# this frame existed - a client cannot cause one just by sending it, since
# the dispatch in handle_live_socket below has no branch for that type. It
# exists purely so the rest of the circle hears about a transition without
# waiting for the next poll of GET /circles/{id}/events, so a client can
# raise a local notification the moment it arrives. See
# broadcast_place_event for the best-effort send.
#
# `groupId` is a wire-compatible break from the old single-group protocol,
# tolerated for one release: a frame with no `groupId` (and a `join` using
# the old `convoyId` key) is treated as "my only joined group" - see
# _frame_group_id. The `joined` reply carries both keys with the same value
# so an old client's parser still finds what it expects.
#
# Nothing here is written to SQLite for a convoy - its live position and
# audio exist only as long as the socket does, same spirit as fog: a live
# view between consenting members, not a record. A circle's `location`
# frames are the one exception (see _store_member_fix below): its group_id
# has kind='circle', so every `location` frame also overwrites that
# member's row in member_last_fix.
#
# group_id -> {user_id: (username, websocket, token_hash)}. A socket may now
# appear in several groups' dicts at once - the registry's shape doesn't
# change, only how many buckets a single socket can occupy. token_hash is
# kept per-entry so the staleness sweep below can tell a revoked session
# from a healthy one without re-touching the socket.
_group_sockets = {}

# Set once run_live_server's event loop is running, so HTTP-thread code
# (do_group_leave, do_logout) can reach into this asyncio-only state via
# run_coroutine_threadsafe instead of racing it from another thread.
_live_loop = None

# How often the sweep below re-validates every open socket against the DB -
# the backstop for revocations that don't go through a group endpoint (e.g.
# `--revoke-tokens`, run from a separate process with nothing to signal this
# one directly).
STALE_SWEEP_INTERVAL_SEC = 15
# A 40ms 16kHz mono PCM16 chunk is ~1.7KB base64'd; this just bounds
# worst-case abuse from a broken or malicious client, generously.
MAX_AUDIO_CHUNK_B64 = 20_000
# A send that blocks this long is a peer on a bad connection, not a slow
# network blip - drop it rather than let it stall everyone else's traffic.
BROADCAST_SEND_TIMEOUT_SEC = 2.0
# The candidate sheet only ever shows three; a `spin_offer` claiming more is
# not a bigger spin, it's a malformed or hostile frame.
MAX_SPIN_CANDIDATES = 3
# Long enough for any place name the pickers actually produce; just a cap
# against a client stuffing a spin candidate's name field with garbage.
MAX_SPIN_NAME_LEN = 80


async def _ws_authenticate(websocket):
    headers = getattr(websocket, "request_headers", None)
    if headers is None:
        headers = websocket.request.headers  # websockets >= 13 API
    raw = headers.get("Authorization", "")
    thash = token_hash(raw[7:].strip()) if raw.startswith("Bearer ") else ""
    user = await asyncio.to_thread(authenticate, headers)
    return user, thash


async def _ws_send(websocket, obj):
    try:
        await websocket.send(json.dumps(obj))
    except websockets.ConnectionClosed:
        pass


async def _safe_close(websocket):
    try:
        await websocket.close()
    except websockets.ConnectionClosed:
        pass


async def _group_broadcast(group_id, obj, exclude_user_id):
    peers = _group_sockets.get(group_id)
    if not peers:
        return
    payload = json.dumps(obj)
    dead = []
    for uid, (_uname, ws, _thash) in list(peers.items()):
        if uid == exclude_user_id:
            continue
        try:
            await asyncio.wait_for(ws.send(payload), timeout=BROADCAST_SEND_TIMEOUT_SEC)
        except (websockets.ConnectionClosed, asyncio.TimeoutError):
            dead.append(uid)
    for uid in dead:
        peers.pop(uid, None)


def broadcast_place_event(group_id, place_id, place_name, username, kind, ts_ms, exclude_user_id):
    """Called from an HTTP handler thread (do_circle_event_create) right
    after its INSERT commits - same hop as evict_group_member below, into
    the relay's asyncio loop via run_coroutine_threadsafe, because
    `_group_sockets` and friends are only ever touched from that loop.

    Best effort, like send_mail: `_live_loop` is None whenever the relay
    isn't live (websockets not installed, or this process never got around
    to starting run_live_server), and run_coroutine_threadsafe itself can
    raise if the loop is mid-shutdown. Either way, a peer who doesn't get
    the live frame still sees the event on their next GET
    /circles/{id}/events poll - this must never turn that INSERT's 200 into
    a 500."""
    if _live_loop is None:
        return
    try:
        asyncio.run_coroutine_threadsafe(
            _group_broadcast(
                group_id,
                {
                    "type": "place_event",
                    "groupId": group_id,
                    "placeId": place_id,
                    "placeName": place_name,
                    "user": username,
                    "kind": kind,
                    "tsMs": ts_ms,
                },
                exclude_user_id=exclude_user_id,
            ),
            _live_loop,
        )
    except RuntimeError as e:  # noqa: BLE001 - see send_mail: a dead relay must not become a 500
        print("place_event broadcast failed: %r" % (e,))


def _group_join(group_id, user_id, username, websocket, thash):
    """Registers the socket, returning the websocket it replaced (if any) so
    the caller can close it - a reconnect must not leave the old connection
    both evicted-from-the-registry and still open, receiving forever."""
    peers = _group_sockets.setdefault(group_id, {})
    old = peers.get(user_id)
    peers[user_id] = (username, websocket, thash)
    return old[1] if old is not None else None


def _group_part(group_id, user_id, websocket):
    """Only removes the registry entry if it still points at *this* socket -
    a stale connection's own cleanup must not evict a newer one that already
    replaced it. Without this check, a slow-to-close old socket races a fast
    reconnect and evicts the live one, leaving it open but invisible."""
    peers = _group_sockets.get(group_id)
    if peers is None:
        return False
    entry = peers.get(user_id)
    if entry is None or entry[1] is not websocket:
        return False
    peers.pop(user_id, None)
    if not peers:
        _group_sockets.pop(group_id, None)
    return True


def _valid_latlon(msg):
    """Coerces and range-checks lat/lon; None if either isn't a finite,
    in-range number. Shared by the relay's `location` frames and the
    /circles/{id}/fix upload - relaying or storing NaN/garbage breaks any
    GeoJSON layer that reads it back out, whether that's a peer's live map
    or a circle's stored last-fix."""
    try:
        lat = float(msg.get("lat"))
        lon = float(msg.get("lon"))
    except (TypeError, ValueError):
        return None
    if not (lat == lat and lon == lon and -90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return lat, lon


def _valid_location(msg):
    """Coerces and range-checks an incoming `location` frame; None if it
    isn't usable. Relaying NaN/garbage through crashes every peer's map
    (their GeoJSON layer rejects NaN coordinates) - one broken or malicious
    client must not be able to take down everyone else's."""
    coords = _valid_latlon(msg)
    if coords is None:
        return None
    lat, lon = coords
    heading = msg.get("headingDeg")
    try:
        heading = float(heading) if heading is not None else None
    except (TypeError, ValueError):
        heading = None
    if heading is not None and not (heading == heading and -360 <= heading <= 360):
        heading = None
    speed = msg.get("speedKmh")
    try:
        speed = float(speed) if speed is not None else None
    except (TypeError, ValueError):
        speed = None
    if speed is not None and not (speed == speed and 0 <= speed <= 500):
        speed = None
    try:
        ts = int(msg.get("ts"))
    except (TypeError, ValueError):
        ts = now_ms()
    return {"lat": lat, "lon": lon, "headingDeg": heading, "speedKmh": speed, "ts": ts}


def _valid_fix(body):
    """Same coercion as _valid_location, for the /circles/{id}/fix body: a
    lat/lon plus an accuracy radius instead of heading/speed - the fields
    the low-cadence HTTP transport carries that the socket protocol doesn't
    bother with."""
    coords = _valid_latlon(body)
    if coords is None:
        return None
    lat, lon = coords
    accuracy = body.get("accuracyM")
    try:
        accuracy = float(accuracy) if accuracy is not None else None
    except (TypeError, ValueError):
        accuracy = None
    if accuracy is not None and not (accuracy == accuracy and 0 <= accuracy <= 100_000):
        accuracy = None
    try:
        ts = int(body.get("ts"))
    except (TypeError, ValueError):
        ts = now_ms()
    return {"lat": lat, "lon": lon, "accuracyM": accuracy, "ts": ts}


def _valid_spin_candidate(obj):
    """Coerces and range-checks one `spin_offer` candidate: the same
    lat/lon gate as a location fix, plus the trip numbers the candidate
    sheet shows and an optional place name - a convoy is choosing a real
    destination together, so this must reject the same garbage a location
    fix would rather than relay it onto three peers' maps."""
    if not isinstance(obj, dict):
        return None
    coords = _valid_latlon(obj)
    if coords is None:
        return None
    lat, lon = coords
    out = {"lat": lat, "lon": lon}
    distance = obj.get("distanceM")
    try:
        distance = float(distance) if distance is not None else None
    except (TypeError, ValueError):
        distance = None
    if distance is not None and (distance != distance or not (0 <= distance <= 1_000_000)):
        distance = None
    if distance is not None:
        out["distanceM"] = distance
    duration = obj.get("durationS")
    try:
        duration = float(duration) if duration is not None else None
    except (TypeError, ValueError):
        duration = None
    if duration is not None and (duration != duration or not (0 <= duration <= 100_000)):
        duration = None
    if duration is not None:
        out["durationS"] = duration
    name = obj.get("name")
    if isinstance(name, str) and name.strip():
        out["name"] = name.strip()[:MAX_SPIN_NAME_LEN]
    return out


def _valid_spin_offer(msg):
    """None unless `candidates` is a list of 1-3 valid candidates - the same
    range the app's own candidate sheet ever offers. One bad candidate voids
    the whole frame rather than silently relaying a shorter list than the
    sender thinks it sent."""
    raw = msg.get("candidates")
    if not isinstance(raw, list) or not (1 <= len(raw) <= MAX_SPIN_CANDIDATES):
        return None
    out = [_valid_spin_candidate(c) for c in raw]
    if any(c is None for c in out):
        return None
    return out


def _valid_spin_vote_index(msg):
    """0..2, matching the candidate sheet's slots. Anything else - a stale
    index from a bigger offer, a bad type - is dropped rather than relayed
    as a vote for a candidate that was never on the sheet."""
    try:
        idx = int(msg.get("index"))
    except (TypeError, ValueError):
        return None
    return idx if 0 <= idx < MAX_SPIN_CANDIDATES else None


def _join_check(group_id, user_id):
    """One query for the WS join handshake: whether this user is an accepted
    member, and if so, the group's kind. The kind is cached by the caller
    for the life of the connection (it cannot change for an existing group),
    so gating push-to-talk on kind costs no per-frame DB hit."""
    row = db().execute(
        "SELECT g.kind AS kind, m.status AS status FROM groups g"
        " JOIN group_members m ON m.group_id = g.id"
        " WHERE g.id = ? AND m.user_id = ?",
        (group_id, user_id),
    ).fetchone()
    if row is None or row["status"] != "accepted":
        return None
    return row["kind"]


def _member_sharing(group_id, user_id):
    """Re-read fresh on every `location` frame - a stale build that keeps
    broadcasting after the user believes they paused must not be trusted.
    This is the server-side half of the pause promise; the client-side half
    is just not sending the frame in the first place."""
    row = db().execute(
        "SELECT sharing FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, user_id),
    ).fetchone()
    return row is not None and bool(row["sharing"])


def _frame_group_id(msg, joined):
    """Resolves the target group for a non-join frame. `groupId` is the
    normal case. A frame carrying neither key is an old, pre-merge client -
    tolerated for one release by treating it as "my only joined group", and
    dropped if the socket has joined more than one (an old build cannot
    possibly mean a specific one, and guessing wrong would leak a frame into
    the wrong group)."""
    raw = msg.get("groupId", msg.get("convoyId"))
    if raw is not None:
        try:
            return int(raw)
        except (TypeError, ValueError):
            return None
    if len(joined) == 1:
        return next(iter(joined))
    return None


def _still_registered(group_id, user_id, websocket):
    """True if the registry still holds *this* websocket for (group_id,
    user_id) - the same identity comparison _group_part makes.

    `_evict` deliberately leaves a socket open when the user is still valid
    in another group, but that only updates `_group_sockets`; the socket's
    own local `joined` cache doesn't hear about it. Without this check, a
    frame naming the just-evicted group would keep being relayed off the
    stale cache instead of the registry eviction that already happened -
    for `ptt_*` frames that means someone who was just removed from a
    convoy keeps broadcasting into it as long as the socket stays open for
    an unrelated circle. Checked before every non-join frame, not just
    location - it's an in-memory dict lookup, no DB hit."""
    peers = _group_sockets.get(group_id)
    entry = peers.get(user_id) if peers else None
    return entry is not None and entry[1] is websocket


async def handle_live_socket(websocket):
    try:
        user, thash = await _ws_authenticate(websocket)
    except HttpError as e:
        await websocket.close(code=4401, reason=e.message)
        return

    joined = {}  # group_id -> kind, every group this socket has joined
    try:
        async for raw in websocket:
            if not isinstance(raw, str):
                continue  # no binary frames in this protocol
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            mtype = msg.get("type")

            if mtype == "join":
                raw_id = msg.get("groupId", msg.get("convoyId"))
                try:
                    target_group = int(raw_id)
                except (TypeError, ValueError):
                    await _ws_send(websocket, {"type": "error", "message": "bad groupId"})
                    continue
                kind = await asyncio.to_thread(_join_check, target_group, user["id"])
                if kind is None:
                    await _ws_send(
                        websocket, {"type": "error", "message": "not a member of that group"}
                    )
                    continue
                # Adds to the joined set rather than replacing it - a socket
                # can be in a circle and a convoy at once.
                old_ws = _group_join(target_group, user["id"], user["username"], websocket, thash)
                joined[target_group] = kind
                if old_ws is not None and old_ws is not websocket:
                    # A previous connection for this user was still open (a
                    # reconnect that outran the old socket's close) - kill it
                    # rather than leave a ghost that keeps receiving forever.
                    await _safe_close(old_ws)
                await _ws_send(
                    websocket,
                    {"type": "joined", "groupId": target_group, "convoyId": target_group},
                )

            elif not joined:
                continue  # everything else requires having joined at least one group

            elif mtype == "location":
                gid = _frame_group_id(msg, joined)
                if gid is None or gid not in joined:
                    continue
                if not _still_registered(gid, user["id"], websocket):
                    joined.pop(gid, None)
                    continue
                loc = _valid_location(msg)
                if loc is None:
                    continue
                # Server-side pause: re-read the flag fresh rather than trust
                # whatever the client last told itself.
                if not await asyncio.to_thread(_member_sharing, gid, user["id"]):
                    continue
                await _group_broadcast(
                    gid, dict(loc, type="location", groupId=gid, user=user["username"]),
                    exclude_user_id=user["id"],
                )
                if joined[gid] == "circle":
                    await asyncio.to_thread(
                        _store_member_fix,
                        gid, user["id"], loc["lat"], loc["lon"], None, loc["ts"],
                    )

            elif mtype in ("ptt_start", "ptt_audio", "ptt_end"):
                gid = _frame_group_id(msg, joined)
                if gid is None or gid not in joined:
                    continue
                if not _still_registered(gid, user["id"], websocket):
                    joined.pop(gid, None)
                    continue
                # The single highest-consequence line in this merge: a
                # circle must never gain always-on voice broadcast between
                # people who signed up for a dot on a map. Hiding the button
                # client-side is not the fix - reject it here.
                if joined[gid] != "convoy":
                    continue
                if mtype == "ptt_audio":
                    chunk = msg.get("chunk")
                    if not (isinstance(chunk, str) and 0 < len(chunk) <= MAX_AUDIO_CHUNK_B64):
                        continue
                    await _group_broadcast(
                        gid,
                        {"type": "ptt_audio", "groupId": gid, "user": user["username"], "chunk": chunk},
                        exclude_user_id=user["id"],
                    )
                else:
                    await _group_broadcast(
                        gid, {"type": mtype, "groupId": gid, "user": user["username"]},
                        exclude_user_id=user["id"],
                    )

            elif mtype in ("spin_offer", "spin_vote"):
                gid = _frame_group_id(msg, joined)
                if gid is None or gid not in joined:
                    continue
                if not _still_registered(gid, user["id"], websocket):
                    joined.pop(gid, None)
                    continue
                # Same gate as PTT and for the same reason: a spin vote is a
                # convoy deciding where to ride together, not something a
                # circle - a standing "who's where" map - ever needs.
                if joined[gid] != "convoy":
                    continue
                if mtype == "spin_offer":
                    candidates = _valid_spin_offer(msg)
                    if candidates is None:
                        continue
                    await _group_broadcast(
                        gid,
                        {"type": "spin_offer", "groupId": gid, "user": user["username"],
                         "candidates": candidates},
                        exclude_user_id=user["id"],
                    )
                else:
                    idx = _valid_spin_vote_index(msg)
                    if idx is None:
                        continue
                    await _group_broadcast(
                        gid,
                        {"type": "spin_vote", "groupId": gid, "user": user["username"], "index": idx},
                        exclude_user_id=user["id"],
                    )
    except websockets.ConnectionClosed:
        pass
    finally:
        # Part every group this connection joined, not just one, and tell
        # each of their peers it left.
        for group_id in list(joined.keys()):
            if _group_part(group_id, user["id"], websocket):
                await _group_broadcast(
                    group_id, {"type": "left", "groupId": group_id, "user": user["username"]},
                    exclude_user_id=user["id"],
                )


async def _evict(group_id, user_id):
    peers = _group_sockets.get(group_id)
    entry = peers.get(user_id) if peers else None
    if entry is None:
        return
    username, ws, _thash = entry
    if not _group_part(group_id, user_id, ws):
        return
    await _group_broadcast(
        group_id, {"type": "left", "groupId": group_id, "user": username}, exclude_user_id=user_id
    )
    # Only close the socket once it holds no membership anywhere else - a
    # circle eviction must not kill a connection still legitimately relaying
    # a convoy for the same user. _group_part already dropped group_id's own
    # entry above, so any remaining bucket with this user pointing at the
    # same websocket means it's still valid there.
    still_elsewhere = any(
        (other_peers.get(user_id) or (None, None, None))[1] is ws
        for other_peers in _group_sockets.values()
    )
    if not still_elsewhere:
        await _safe_close(ws)


async def _evict_everywhere(user_id):
    for group_id in list(_group_sockets.keys()):
        await _evict(group_id, user_id)


def evict_group_member(group_id, user_id):
    """Called from an HTTP handler thread (do_group_leave) to instantly drop
    a live socket the moment membership is revoked, instead of waiting for
    the periodic sweep below to notice."""
    if _live_loop is not None:
        asyncio.run_coroutine_threadsafe(_evict(group_id, user_id), _live_loop)


def evict_user_everywhere(user_id):
    """Called from do_logout so revoking your own session takes every live
    group socket down with it immediately, rather than up to
    STALE_SWEEP_INTERVAL_SEC seconds later."""
    if _live_loop is not None:
        asyncio.run_coroutine_threadsafe(_evict_everywhere(user_id), _live_loop)


def _socket_still_valid(group_id, user_id, thash):
    row = db().execute("SELECT 1 FROM tokens WHERE token_hash = ?", (thash,)).fetchone()
    if row is None:
        return False
    return is_group_member(group_id, user_id)


async def _sweep_stale_sockets():
    """Catches what the instant eviction hooks above can't: a token revoked
    from a separate process (`--revoke-tokens`, the lost-phone remedy has no
    way to signal a running server) or membership changing underneath a
    socket some other way. Re-validates every (group, user) membership
    independently - a multi-group socket can be stale in one and fine in the
    other. Runs only in this loop, so no lock is needed for the dict scan."""
    while True:
        await asyncio.sleep(STALE_SWEEP_INTERVAL_SEC)
        for group_id, peers in list(_group_sockets.items()):
            for user_id, (_username, _ws, thash) in list(peers.items()):
                ok = await asyncio.to_thread(_socket_still_valid, group_id, user_id, thash)
                if not ok:
                    await _evict(group_id, user_id)


def run_live_server(host, port):
    if websockets is None:
        print("live group relay disabled: run `pip install websockets` to enable it")
        return

    async def main():
        global _live_loop
        _live_loop = asyncio.get_running_loop()
        asyncio.create_task(_sweep_stale_sockets())
        # 1 MB cap: a PTT chunk is a couple hundred ms of 16kHz mono PCM16,
        # a few KB even base64'd - this just bounds worst-case abuse.
        async with websockets.serve(handle_live_socket, host, port, max_size=1024 * 1024):
            print("detour-live (group relay) on %s:%s" % (host, port))
            await asyncio.Future()  # run forever

    asyncio.run(main())


# --------------------------------------------------------------------------
# home assistant (read-only, API key)


def api_key_user(params):
    """The user behind an API key, from ?key= or the X-API-Key header.

    A dashboard iframe can only carry the key in the URL, which is why the
    query form exists — and why these keys read and nothing else.
    """
    raw = (params.get("key") or [""])[0]
    if not raw:
        raise HttpError(401, "missing api key")
    row = db().execute(
        "SELECT u.* FROM api_keys k JOIN users u ON u.id = k.user_id"
        " WHERE k.key_hash = ?",
        (token_hash(raw),),
    ).fetchone()
    if row is None:
        raise HttpError(401, "invalid api key")
    return row


def next_trip_start_ms(uid, after_ms):
    """The start time of the next trip after after_ms, or None if after_ms's
    trip is the newest one. Used to cap an open ride's fallback window so it
    can't swallow the ride that comes after it."""
    row = db().execute(
        "SELECT MIN(start_ms) AS n FROM trips WHERE user_id = ? AND start_ms > ?",
        (uid, after_ms),
    ).fetchone()
    return row["n"]


def ride_window(uid, start_ms):
    """A trip's (start, end) in ms, for slicing points out of the track."""
    row = db().execute(
        "SELECT json FROM trips WHERE user_id = ? AND start_ms = ?", (uid, start_ms)
    ).fetchone()
    if row is None:
        raise HttpError(404, "no such ride")
    trip = json.loads(row["json"])
    end = int(trip.get("endTimeMs") or 0)
    if end <= start_ms:
        # A trip that never recorded an end still has points; give it the
        # longest plausible ride rather than an empty window — but stop at the
        # next trip's start if there is one, or an unended ride swallows the
        # ride that comes after it (and its lean/speed peaks along with it).
        fallback_end = start_ms + 24 * 3600 * 1000
        next_start = next_trip_start_ms(uid, start_ms)
        end = min(next_start - 1, fallback_end) if next_start else fallback_end
    return trip, end


def ride_points(uid, start_ms, end_ms):
    return [
        dict(t=r["t_ms"], lat=r["lat"], lon=r["lon"],
             speed=r["speed_kmh"], lean=r["lean_deg"])
        for r in db().execute(
            "SELECT t_ms, lat, lon, speed_kmh, lean_deg FROM track_points"
            " WHERE user_id = ? AND t_ms BETWEEN ? AND ? ORDER BY t_ms",
            (uid, start_ms, end_ms),
        )
    ]


def ha_rides(user, params):
    """Rides newest first, with the lean and speed peaks the points actually
    hold. maxLeanDeg is null for a ride recorded before points carried lean —
    honestly unknown, rather than a zero that reads as "never leaned"."""
    uid = user["id"]
    try:
        # 500 comfortably covers the dashboard's "everything" fetch (118 rides
        # in production today) without leaving the cap effectively unbounded.
        limit = min(int((params.get("limit") or ["25"])[0]), 500)
    except ValueError:
        limit = 25
    out = []
    for r in db().execute(
        "SELECT json FROM trips WHERE user_id = ? ORDER BY start_ms DESC LIMIT ?",
        (uid, limit),
    ):
        trip = json.loads(r["json"])
        start = int(trip.get("startTimeMs") or 0)
        if not start:
            continue
        end = int(trip.get("endTimeMs") or 0)
        if not end:
            # Same cap as ride_window: don't let an unended ride's fallback
            # window swallow the ride that comes after it.
            fallback_end = start + 24 * 3600 * 1000
            next_start = next_trip_start_ms(uid, start)
            end = min(next_start - 1, fallback_end) if next_start else fallback_end
        agg = db().execute(
            "SELECT COUNT(*) AS n, MAX(ABS(lean_deg)) AS lean, MAX(speed_kmh) AS speed"
            " FROM track_points WHERE user_id = ? AND t_ms BETWEEN ? AND ?",
            (uid, start, end),
        ).fetchone()
        out.append({
            "startMs": start,
            "endMs": int(trip.get("endTimeMs") or 0),
            "mode": trip.get("mode"),
            "distanceKm": round((trip.get("distanceMeters") or 0) / 1000.0, 2),
            "topSpeedKmh": round((trip.get("topSpeedMps") or 0) * 3.6, 1),
            "maxLeanDeg": round(agg["lean"], 1) if agg["lean"] is not None else None,
            "maxGForce": trip.get("maxGForce"),
            "pointCount": agg["n"],
            "map": "/ha/ride.html?start=%d" % start,
        })
    return {"rides": out}


def ha_traces(user, params):
    """The caller's own trace lines, position-only, for the all-rides heatmap.

    Goes through api_key_user like every other /ha/* endpoint and reads only
    `WHERE user_id = ?` — friend_fog stays the one path to another user's
    traces, and this is not it. Lines predate track_points and carry 2- or
    5-element points ([lat, lon] or [lat, lon, tMs, speedKmh, leanDeg]); either
    way only the first two elements matter for a heatmap.
    """
    uid = user["id"]
    try:
        # Clamp both ends: 0 or negative would divide-by-zero-shaped skip
        # everything, and there is no reason to thin by more than 1 in 50.
        every = max(1, min(int((params.get("every") or ["1"])[0]), 50))
    except ValueError:
        every = 1
    traces = []
    for r in db().execute("SELECT line FROM traces WHERE user_id = ?", (uid,)):
        try:
            points = json.loads(r["line"])
        except (ValueError, TypeError):
            continue  # one corrupt line must not fail the whole heatmap
        if not isinstance(points, list):
            continue
        line = []
        for i, p in enumerate(points):
            if i % every:
                continue
            if not isinstance(p, list) or len(p) < 2:
                continue
            try:
                lat, lon = float(p[0]), float(p[1])
            except (TypeError, ValueError):
                continue
            if -90 <= lat <= 90 and -180 <= lon <= 180:
                line.append([lat, lon])
        if line:
            traces.append(line)
    return {"traces": traces}


# One degree of latitude in metres. Longitude covers less ground the further
# from the equator, by cos(lat); simplify_track corrects for that so a
# tolerance given in metres means the same thing north-south and east-west.
DEG_METRES = 111_320.0


def simplify_track(points, tolerance_m, lat_ref):
    """Douglas-Peucker on [(lat, lon), …] — the points themselves.

    See simplify_indices for why the work happens there; a caller that only
    wants the thinned shape has no use for the indices.
    """
    return [points[i] for i in simplify_indices(points, tolerance_m, lat_ref)]


def simplify_indices(points, tolerance_m, lat_ref):
    """Douglas-Peucker on [(lat, lon), …], tolerance in metres, as indices.

    Indices rather than points because the overlays need to look back at what
    the raw track recorded between two kept points — the speed held over that
    stretch, the deepest lean in it. Dropping a point must not drop the 55°
    corner it was carrying.

    A raw GPS track is a point per second, which is three orders of magnitude
    more detail than a map at road zoom can show. The /ha/* JSON endpoints hand
    the whole thing to a browser that thins it client-side; the entity-attribute
    endpoints below cannot — whatever comes out of here is what Home Assistant
    stores and ships on every dashboard render. Dropping points that sit within
    `tolerance_m` of the line they'd fall on is invisible at map zoom and cuts a
    typical ride by 70-80%.

    Iterative rather than recursive: a 100k-point trace would otherwise be a
    stack overflow waiting for a straight enough road.
    """
    if len(points) < 3:
        return list(range(len(points)))
    # cos() of the track's own latitude, not of the equator: at 51°N a degree of
    # longitude is 63% of a degree of latitude, and treating them as equal would
    # simplify roughly a third harder east-west than north-south.
    lon_scale = math.cos(math.radians(lat_ref)) or 1.0
    tol = tolerance_m / DEG_METRES
    tol_sq = tol * tol
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        ax, ay = points[i][1] * lon_scale, points[i][0]
        bx, by = points[j][1] * lon_scale, points[j][0]
        dx, dy = bx - ax, by - ay
        den = dx * dx + dy * dy
        best, best_sq = -1, 0.0
        for k in range(i + 1, j):
            px, py = points[k][1] * lon_scale, points[k][0]
            if den == 0:
                # Endpoints coincide (a stop with drift); fall back to plain
                # distance from that one spot.
                d_sq = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / den
                t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
                d_sq = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d_sq > best_sq:
                best, best_sq = k, d_sq
        if best > 0 and best_sq > tol_sq:
            keep[best] = True
            stack.append((i, best))
            stack.append((best, j))
    return [i for i, k in enumerate(keep) if k]


def thin_to(points, limit):
    """Hard cap on point count, for when simplification alone isn't enough.

    Simplification is shape-aware but its output size depends on the road: a
    ride through a city keeps far more points than one down a motorway. The cap
    is what keeps a pathological track from landing a megabyte in an entity
    attribute, so it takes an even stride and always keeps the last point.
    """
    if limit <= 0 or len(points) <= limit:
        return points
    step = (len(points) + limit - 1) // limit
    out = points[::step]
    if out[-1] != points[-1]:
        out.append(points[-1])
    return out


# A single ride is read at street zoom, everything else at overview zoom.
TRACK_DECIMALS = 6


def _coord(lat, lon, decimals=5):
    """GeoJSON [lon, lat], rounded to ~1 m by default.

    Full precision is 7 decimals — 11 mm, about six wasted characters per
    coordinate on a payload that has to fit in an entity attribute.

    5 decimals is invisible at the zoom a whole-country coverage map is read
    at. It is not invisible on a single ride: 1 m is 5 px at zoom 19 and 10 px
    at zoom 20, which turns a straight road into a staircase exactly when
    someone zooms in to look at a corner. A ride is a few hundred coordinates,
    so 6 decimals there costs a few hundred bytes and settles it.
    """
    return [round(lon, decimals), round(lat, decimals)]


def _bounds(points):
    """{ne, sw, latitude, longitude} for a list of (lat, lon), or None."""
    if not points:
        return None
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    return {
        "ne": {"latitude": round(max(lats), 5), "longitude": round(max(lons), 5)},
        "sw": {"latitude": round(min(lats), 5), "longitude": round(min(lons), 5)},
        "latitude": round((max(lats) + min(lats)) / 2, 5),
        "longitude": round((max(lons) + min(lons)) / 2, 5),
    }


def _int_param(params, name, default, low, high):
    try:
        value = int((params.get(name) or [str(default)])[0])
    except ValueError:
        raise HttpError(400, "%s must be a number" % name)
    return max(low, min(value, high))


def _required_int_param(params, name):
    """Like _int_param but for an id with no sensible default or range -
    missing or unparseable is just a 400, not a clamp."""
    raw = (params.get(name) or [None])[0]
    if raw is None:
        raise HttpError(400, "%s is required" % name)
    try:
        return int(raw)
    except ValueError:
        raise HttpError(400, "%s must be a number" % name)


def _require_int_body(body, key):
    # int(float('inf')) raises OverflowError, not ValueError - a bare
    # (TypeError, ValueError) here would let an Infinity/NaN value through
    # to become a 500, same bug do_route_share and do_route_delete guard
    # against for route ids.
    try:
        return int(body.get(key))
    except (TypeError, ValueError, OverflowError):
        raise HttpError(400, "bad %s" % key)


# --------------------------------------------------------------------------
# Bucketed overlays for the Home Assistant map card
#
# The bundled Leaflet page colours a track continuously: one Feature per
# segment, each with its own colour in its properties. custom:map-card cannot
# do that — it styles a whole GeoJSON layer at once
# (`style: () => config.getStyle()`), so a per-feature colour is ignored. The
# way to get a coloured line into that card is therefore one *layer* per
# colour: the track is cut into bands, each band comes back as its own
# MultiLineString, and the dashboard declares one geojson layer per band with a
# fixed colour. Consecutive segments in the same band are merged into a single
# line so a band costs about as many coordinates as the stretch it covers.
#
# (upper bound — exclusive, label, colour). The final band has no upper bound.
# The colours are duplicated in dashboards/detour.yaml, which is what the
# card actually paints with; these travel with the legend so the two can be
# checked against each other. Keep them in step.
def _hex(rgb):
    return "#%02x%02x%02x" % tuple(max(0, min(255, int(round(c)))) for c in rgb)


def _ramp(stops, t):
    """Colour at 0..1 along a list of (position, (r, g, b)) stops."""
    t = max(0.0, min(1.0, t))
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]
        p1, c1 = stops[i + 1]
        if t <= p1 or i == len(stops) - 2:
            k = 0.0 if p1 == p0 else (t - p0) / (p1 - p0)
            k = max(0.0, min(1.0, k))
            return _hex([c0[j] + (c1[j] - c0[j]) * k for j in range(3)])
    return _hex(stops[-1][1])


def _stepped_bands(step, top, unit, stops, decimals=0, sep=" "):
    """Bands every `step` up to `top`, then one open-ended band.

    Steps rather than hand-picked breaks because the question these answer is
    "how fast was I here", and a reader converts a colour to a number by
    counting bands. Uneven bands make that arithmetic, and a legend nobody can
    do arithmetic on is decoration.
    """
    edges = []
    value = 0.0
    while value < top - 1e-9:
        edges.append((value, value + step))
        value += step
    bands = []
    for i, (low, high) in enumerate(edges):
        fmt = "%.*f" % (decimals, low), "%.*f" % (decimals, high)
        bands.append((high, "%s–%s%s%s" % (fmt[0], fmt[1], sep, unit),
                      _ramp(stops, i / float(len(edges)))))
    bands.append((None, "%.*f+%s%s" % (decimals, top, sep, unit), _ramp(stops, 1.0)))
    return tuple(bands)


# Pale blue through to near-black navy: monotone in lightness, so the ramp
# still reads as "more" on a phone in sunlight and in greyscale.
SPEED_STOPS = ((0.0, (168, 200, 236)), (0.45, (58, 125, 202)),
               (0.75, (26, 66, 132)), (1.0, (10, 26, 62)))
SPEED_BANDS = _stepped_bands(5.0, 130.0, "km/h", SPEED_STOPS)

# Lean is banded on |lean|, not the signed value the Leaflet page diverges on.
# Direction is still in the ride's own data for anyone who wants
# /ha/ride.geojson. Grey (upright) through amber to deep red: the colour is
# about how far over, and upright should not shout.
LEAN_STOPS = ((0.0, (154, 160, 166)), (0.3, (242, 193, 78)),
              (0.6, (240, 139, 51)), (0.82, (226, 71, 42)), (1.0, (128, 16, 16)))
LEAN_BANDS = _stepped_bands(2.0, 40.0, "°", LEAN_STOPS, sep="")

# Distinct rides through a cell, for the coverage heat map. Bands rather than a
# gradient for the same reason as above, and the ramp runs cool-to-hot so a road
# ridden once still reads as ridden.
#
# The steps double because the counts do: on a real history a fifth of the
# distance sits above 30 rides and the roads out of the front door run to 130,
# so evenly spaced bands would paint the whole home region one flat red.
HEAT_BANDS = (
    (2, "1 ride", "#4a8fe0"),
    (3, "2 rides", "#2ba8a8"),
    (5, "3–4 rides", "#2e9e52"),
    (9, "5–8 rides", "#a7bf1e"),
    (16, "9–15 rides", "#f2b705"),
    (32, "16–31 rides", "#ee6a20"),
    (None, "32+ rides", "#d62222"),
)

# Segments the track carries no reading for — an old ride recorded before the
# app stored lean, a GPS drop-out. Drawn, in grey, rather than left as a gap:
# a hole in the line reads as "didn't go there", which is worse than "don't
# know how fast".
NO_DATA_KEY = "bn"
NO_DATA_LABEL = "no data"
NO_DATA_COLOR = "#6b7060"


def _band_index(value, bands):
    for i, (upper, _label, _color) in enumerate(bands):
        if upper is None or value < upper:
            return i
    return len(bands) - 1


def _band_key(index):
    return "b%d" % index


def _key_for(value, bands):
    return NO_DATA_KEY if value is None else _band_key(_band_index(value, bands))


def _smooth(values, window=5):
    """Rolling median, Nones passed through.

    Raw GPS speed jitters a few km/h point to point, and a band edge turns that
    jitter into colour: a steady 51 km/h crossing 50 back and forth comes out
    as a run of alternating two-colour dashes. Zoomed out, where those runs are
    a pixel or two long, that is what "the overlay is broken" looks like. A
    median over five seconds keeps genuine acceleration — five seconds of it is
    30 km/h on a bike — and drops the noise.
    """
    if window < 3 or len(values) < window:
        return list(values)
    half = window // 2
    out = []
    for i in range(len(values)):
        if values[i] is None:
            out.append(None)
            continue
        near = [v for v in values[max(0, i - half):i + half + 1] if v is not None]
        out.append(sorted(near)[len(near) // 2] if near else None)
    return out


def _banded_layers(pairs, bands, name_fmt, decimals=5, tick_every=None):
    """Cut lines into one MultiLineString per band.

    `pairs` is [(coords, keys), …]: `coords` is a [(lat, lon), …] and `keys`
    holds one band key per *segment*, so it is one shorter. Consecutive
    segments in the same band become one LineString, which is what keeps this
    close in size to the single-line version: a motorway stretch at a steady
    120 is one line, not four hundred.
    """
    all_keys = [NO_DATA_KEY] + [_band_key(i) for i in range(len(bands))]
    parts = {k: [] for k in all_keys}
    metres = {k: 0.0 for k in all_keys}
    for coords, keys in pairs:
        run_key, run = None, []
        for i, key in enumerate(keys):
            if key != run_key:
                if run_key is not None and len(run) >= 2:
                    parts[run_key].append(run)
                run_key, run = key, [coords[i]]
            run.append(coords[i + 1])
            metres[key] += _haversine_m(coords[i], coords[i + 1])
        if run_key is not None and len(run) >= 2:
            parts[run_key].append(run)

    out = {}
    for key in all_keys:
        out[key] = {
            "type": "Feature",
            "geometry": {
                "type": "MultiLineString",
                "coordinates": [[_coord(lat, lon, decimals) for lat, lon in run]
                                for run in parts[key]],
            },
            "properties": {"name": name_fmt % _band_label(key, bands)},
        }
    out["legend"] = [
        {"key": key, "label": _band_label(key, bands),
         "color": _band_color(key, bands), "km": round(metres[key] / 1000.0, 1)}
        # No-data last: it is the footnote, not the bottom of the scale.
        for key in [_band_key(i) for i in range(len(bands))] + [NO_DATA_KEY]
        if metres[key] > 0
    ]
    out["legend_svg"] = _legend_svg(out["legend"], bands, tick_every)
    return out


def _legend_svg(legend, bands, tick_every):
    """The legend as one image: a bar per band, height by distance ridden.

    A list of rows worked at six bands and does not at twenty-seven — nobody
    reads a twenty-seven row table on a phone, and the shape of the ride is the
    interesting part anyway: where the distance actually sat. Bars carry the
    band colour, so the map and the legend are read with the same eye.

    Returned as a data URI because Home Assistant's markdown card strips inline
    `style` — a coloured swatch has to arrive as an image or not at all.
    """
    km_by_key = {row["key"]: row["km"] for row in legend}
    rows = [(_band_key(i), bands[i]) for i in range(len(bands))]
    peak = max([km_by_key.get(k, 0.0) for k, _b in rows] or [0.0])
    if peak <= 0:
        return None
    w, bar_h, foot = 340, 54, 16
    step = w / float(len(rows))
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
             'viewBox="0 0 %d %d">' % (w, bar_h + foot, w, bar_h + foot)]
    for i, (key, band) in enumerate(rows):
        km = km_by_key.get(key, 0.0)
        h = max(1.0, bar_h * (km / peak)) if km > 0 else 1.0
        parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="%s"'
                     ' opacity="%s"/>'
                     % (i * step, bar_h - h, max(1.0, step - 1.0), h, band[2],
                        "1" if km > 0 else "0.25"))
        # Tick labels every `tick_every` units of the banded value, so the axis
        # is read in km/h or degrees rather than in band numbers.
        low = 0.0 if i == 0 else bands[i - 1][0]
        # Skip ticks that would collide with the open-ended band's label.
        if (low is not None and tick_every and abs(low % tick_every) < 1e-9
                and i * step < w - 70):
            parts.append('<text x="%.1f" y="%d" font-family="sans-serif" '
                         'font-size="9" fill="#888">%d</text>'
                         % (i * step, bar_h + 11, int(low)))
    parts.append('<line x1="0" y1="%d" x2="%d" y2="%d" stroke="#888" '
                 'stroke-width="0.5" opacity="0.5"/>' % (bar_h, w, bar_h))
    parts.append('<text x="%d" y="%d" font-family="sans-serif" font-size="9" '
                 'fill="#888" text-anchor="end">%s</text>'
                 % (w, bar_h + 11, bands[-1][1]))
    parts.append('</svg>')
    return "data:image/svg+xml," + urlquote("".join(parts), safe="")


def _flatten_bands(prefix, layers):
    """{b0: Feature, …, legend: […]} -> {speed_b0: Feature, …, speed_legend: […]}.

    Flat because of how custom:map-card reads a layer's data. Its *top-level*
    `geojson:` layers do understand a dotted path, but they are stored keyed by
    entity id, so several layers on one entity silently collapse to whichever
    was configured last — which looks exactly like a broken overlay. The form
    that works is one `entities:` entry per band, and that one reads
    `entity.attributes[attribute]` with no path walking at all. Hence one
    attribute per band.
    """
    return {"%s_%s" % (prefix, key): value for key, value in layers.items()}


def _band_label(key, bands):
    if key == NO_DATA_KEY:
        return NO_DATA_LABEL
    return bands[int(key[1:])][1]


def _band_color(key, bands):
    if key == NO_DATA_KEY:
        return NO_DATA_COLOR
    return bands[int(key[1:])][2]


def _haversine_m(a, b):
    """Metres between two (lat, lon), flat-earth — fine over a GPS segment."""
    lat_scale = DEG_METRES
    lon_scale = DEG_METRES * math.cos(math.radians((a[0] + b[0]) / 2.0))
    return math.hypot((b[0] - a[0]) * lat_scale, (b[1] - a[1]) * lon_scale)


def _visit_counts(lines, cell_m, lat_ref):
    """How many distinct traces pass through each grid cell.

    A heat map wants "how often have I ridden this road", and roads are not
    stored as roads here — they are GPS points that never repeat exactly. So
    the world is cut into ~`cell_m` squares and a road becomes the cells it
    runs through; two rides down the same street hit the same cells even though
    no two fixes match. Counting is per trace, not per point: sitting at a
    traffic light for two minutes must not paint that junction as the most
    ridden place in the country.

    Points are walked, not sampled — at 120 km/h a 1 Hz fix moves 33 m, which
    would leave gaps in a 60 m grid, so each segment is stepped along in
    half-cell strides.
    """
    d_lat = cell_m / DEG_METRES
    d_lon = d_lat / (math.cos(math.radians(lat_ref)) or 1.0)
    # cell -> [last trace seen here, count of distinct traces]. Traces are
    # walked one at a time, so "have I already counted this trace here" is just
    # a comparison against the last one.
    seen = {}

    def mark(idx, lat, lon):
        key = (int(math.floor(lat / d_lat)), int(math.floor(lon / d_lon)))
        hit = seen.get(key)
        if hit is None:
            seen[key] = [idx, 1]
        elif hit[0] != idx:
            hit[0] = idx
            hit[1] += 1

    for idx, line in enumerate(lines):
        for k in range(len(line) - 1):
            a, b = line[k], line[k + 1]
            mark(idx, a[0], a[1])
            steps = int(_haversine_m(a, b) / (cell_m / 2.0))
            for s in range(1, min(steps, 200)):
                t = s / float(steps)
                mark(idx, a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        if line:
            mark(idx, line[-1][0], line[-1][1])
    return seen, d_lat, d_lon


def _visits_at(seen, d_lat, d_lon, lat, lon):
    hit = seen.get((int(math.floor(lat / d_lat)), int(math.floor(lon / d_lon))))
    return hit[1] if hit else 1


def _cells_along(a, b, d_lat, d_lon, step_m=30.0):
    """The grid cells a segment passes through."""
    steps = max(1, int(_haversine_m(a, b) / step_m))
    out = set()
    for i in range(min(steps, 400) + 1):
        t = i / float(steps)
        out.add((int(math.floor((a[0] + (b[0] - a[0]) * t) / d_lat)),
                 int(math.floor((a[1] + (b[1] - a[1]) * t) / d_lon))))
    return out


def _road_runs(lines, seen, d_lat, d_lon):
    """Each stretch of road once, with the rides that came through it.

    Drawing every trace is what the flat coverage overlay does, and at 350
    traces the commute home is 130 lines stacked on the same street: the top
    one wins, the colour underneath is invisible, and the result is a scribble.
    A frequency map wants the opposite — one line per road, coloured by how
    many rides used it — so a segment whose cells are already on the map is
    dropped, and the count comes from the visit grid rather than from how many
    times something was drawn.

    Half a segment's cells being new is enough to keep it: a road rejoined
    part-way through has to be drawn, or the map grows gaps where two rides
    merge.
    """
    drawn = set()
    pairs = []
    for line in lines:
        run, values = [], []
        for k in range(len(line) - 1):
            a, b = line[k], line[k + 1]
            cells = _cells_along(a, b, d_lat, d_lon)
            fresh = sum(1 for c in cells if c not in drawn)
            if fresh < max(1, len(cells) * 0.5):
                # Already on the map: close the run rather than bridge the gap.
                if len(run) >= 2:
                    pairs.append((run, values))
                run, values = [], []
                continue
            drawn.update(cells)
            mid = ((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0)
            if not run:
                run = [a]
            run.append(b)
            values.append(_key_for(max(_visits_at(seen, d_lat, d_lon, p[0], p[1])
                                       for p in (a, mid, b)), HEAT_BANDS))
        if len(run) >= 2:
            pairs.append((run, values))
    return pairs


def _overlay_pairs(coords, values, bands, tolerance, lat_ref, limit,
                   min_run_m=60.0):
    """Colour bands cut where the reading changes, not where the shape does.

    The obvious construction — simplify the track, then reduce each surviving
    segment's readings to one value — is wrong in a way that looks like a bug.
    Douglas-Peucker keeps points where the road *bends*, so a straight two-mile
    dual carriageway survives as a single segment, and on a real ride that one
    segment spans 22 to 126 km/h: 15 km of a 24 km ride sat in segments longer
    than 300 m, 18 km of it in segments whose speed spanned more than 20 km/h.
    Averaging that paints a uniform mid-blue over the whole thing and puts the
    colour changes at bends rather than where the riding changed.

    So the bands come first: every raw point is banded, consecutive points in
    the same band become a run, and only then is each run simplified for size.
    A band boundary now falls exactly where the reading crossed it, and a bend
    inside one band costs nothing.

    Runs shorter than `min_run_m` are folded into their neighbour, into the one
    whose band is nearest so a brief burst of 115 km/h joins the 90-110 stretch
    around it rather than something two bands away. GPS speed wobbles either
    side of a threshold, and without this a steady 51 km/h comes out as a
    dashed line of two colours — which at low zoom, where a 20 m run is
    sub-pixel, is exactly what a broken overlay looks like.

    Runs carry their own key: reading it back out of a per-point array would
    make each fold recolour the *next* run too, since neighbouring runs share
    the boundary point that array is indexed by.
    """
    point_keys = [_key_for(v, bands) for v in _smooth(values)]
    if len(point_keys) < 2:
        return []

    # [start, end, key] over the raw track. Neighbours share their boundary
    # point, so the bands meet instead of leaving a one-segment gap.
    runs = []
    start = 0
    for i in range(1, len(point_keys)):
        if point_keys[i] != point_keys[start]:
            runs.append([start, i, point_keys[start]])
            start = i
    runs.append([start, len(point_keys) - 1, point_keys[start]])

    def run_metres(run):
        return sum(_haversine_m(coords[i], coords[i + 1])
                   for i in range(run[0], run[1]))

    def band_rank(key):
        # NO_DATA sorts far from every band: folding a real reading into "no
        # data" (or the reverse) would be a lie either way, so it only happens
        # when there is no other neighbour.
        return -99 if key == NO_DATA_KEY else int(key[1:])

    # Fold repeatedly: swallowing one run can leave its neighbours adjacent and
    # in the same band, and that pair should merge too.
    while len(runs) > 1:
        lengths = [run_metres(r) for r in runs]
        i = min(range(len(runs)), key=lambda k: lengths[k])
        if lengths[i] >= min_run_m:
            break
        if i == 0:
            target = 1
        elif i == len(runs) - 1:
            target = len(runs) - 2
        else:
            near = abs(band_rank(runs[i - 1][2]) - band_rank(runs[i][2])) - \
                abs(band_rank(runs[i + 1][2]) - band_rank(runs[i][2]))
            if near < 0:
                target = i - 1
            elif near > 0:
                target = i + 1
            else:
                target = i - 1 if lengths[i - 1] >= lengths[i + 1] else i + 1
        lo, hi = min(i, target), max(i, target)
        runs[lo] = [runs[lo][0], runs[hi][1], runs[target][2]]
        del runs[hi]

    pairs = []
    for run_start, run_end, key in runs:
        line = coords[run_start:run_end + 1]
        if len(line) < 2:
            continue
        line = simplify_track(line, tolerance, lat_ref)
        if len(line) < 2:
            line = [coords[run_start], coords[run_end]]
        pairs.append((line, [key] * (len(line) - 1)))

    total = sum(len(line) for line, _k in pairs)
    if limit and total > limit:
        share = limit / float(total)
        pairs = [(thin_to(line, max(2, int(len(line) * share))), keys)
                 for line, keys in pairs]
        pairs = [(line, [keys[0]] * (len(line) - 1))
                 for line, keys in pairs if keys]
    return pairs


# A ride's overlays are one computation shared by many callers: Home Assistant
# holds each colour band in its own entity, and refreshing them after a ride is
# picked means ~50 requests for the same bytes within a second or two. Keyed on
# everything that changes the answer, held briefly — long enough for the burst,
# short enough that a ride that grows mid-sync shows up on the next poll.
_track_cache = {}
_track_cache_lock = threading.Lock()
TRACK_CACHE_SEC = 20


def _cached_track(key, build):
    now = time.time()
    with _track_cache_lock:
        hit = _track_cache.get(key)
        if hit and now - hit[0] < TRACK_CACHE_SEC:
            return hit[1]
    value = build()
    with _track_cache_lock:
        _track_cache[key] = (now, value)
        if len(_track_cache) > 32:
            for stale in [k for k, (t, _v) in _track_cache.items()
                          if now - t > TRACK_CACHE_SEC]:
                _track_cache.pop(stale, None)
    return value


def latest_ride_start(uid):
    row = db().execute(
        "SELECT start_ms FROM trips WHERE user_id = ? ORDER BY start_ms DESC LIMIT 1",
        (uid,),
    ).fetchone()
    return int(row["start_ms"]) if row else None


def ha_track(user, params):
    """One ride as a single simplified LineString, sized for an entity attribute.

    /ha/ride.geojson exists for the bundled Leaflet page: a Feature per segment
    so each can be coloured by the lean recorded there, which costs 74 kB for a
    13 km ride. A native Home Assistant map card colours a whole geometry at
    once, so per-segment features buy nothing and the size is a real cost — the
    attribute is re-sent on every dashboard render and, unless excluded, written
    to the recorder on every poll. One line, simplified, is a few kB.

    No ?start= means the newest ride, so the sensor polling this needs no
    template and no second request to find out what "latest" is.
    """
    uid = user["id"]
    start = _int_param(params, "start", 0, 0, 2 ** 63 - 1)
    tolerance = _int_param(params, "tol", 6, 0, 200)
    limit = _int_param(params, "max", 400, 0, 5000)
    return _cached_track((uid, start, tolerance, limit),
                         lambda: _ha_track(uid, start, tolerance, limit))


def _ha_track(uid, start, tolerance, limit):
    empty = {"type": "FeatureCollection", "features": []}
    if start <= 0:
        start = latest_ride_start(uid)
        if start is None:
            return {"startMs": None, "pointCount": 0, "usedPoints": 0,
                    "geojson": empty}
    trip, end = ride_window(uid, start)
    pts = ride_points(uid, start, end)
    coords = [(p["lat"], p["lon"]) for p in pts]
    bounds = _bounds(coords)
    # Indices, not points: the speed and lean overlays below have to read what
    # the raw track held between each pair of kept points.
    kept = thin_to(
        simplify_indices(coords, tolerance, bounds["latitude"] if bounds else 0.0),
        limit,
    ) if coords else []
    line = [coords[i] for i in kept]
    leans = [abs(p["lean"]) for p in pts if p["lean"] is not None]
    speeds = [p["speed"] for p in pts if p["speed"] is not None]
    distance_km = round((trip.get("distanceMeters") or 0) / 1000.0, 2)
    features = []
    if len(line) >= 2:
        features.append({
            "type": "Feature",
            "geometry": {
                "type": "LineString",
                "coordinates": [_coord(lat, lon, TRACK_DECIMALS) for lat, lon in line],
            },
            # Shown as a tooltip when the line is hovered on the map card.
            "properties": {
                "name": "%s km · %s" % (distance_km, (trip.get("mode") or "ride").title()),
            },
        })
    out = {
        "startMs": start,
        "endMs": end,
        "mode": trip.get("mode"),
        "distanceKm": distance_km,
        "topSpeedKmh": round(max(speeds), 1) if speeds else None,
        "maxLeanDeg": round(max(leans), 1) if leans else None,
        "pointCount": len(pts),
        "usedPoints": len(line),
        "geojson": {"type": "FeatureCollection", "features": features},
    }
    if len(line) >= 2:
        # One layer per colour band, for a card that cannot colour per feature.
        # Built off the raw points, not off `line`: see _overlay_pairs.
        lat_ref = bounds["latitude"] if bounds else 0.0
        # A band change worth drawing scales with the ride: 60 m of 110 km/h
        # matters on a commute and is invisible on a 400 km day out, where
        # keeping it would be several hundred runs and 60 kB of attribute.
        min_run = max(60.0, min(distance_km * 2.0, 800.0))
        out.update(_flatten_bands("speed", _banded_layers(
            _overlay_pairs(coords, [p["speed"] for p in pts],
                           SPEED_BANDS, tolerance, lat_ref, limit, min_run),
            SPEED_BANDS, "%s", TRACK_DECIMALS, 25,
        )))
        out.update(_flatten_bands("lean", _banded_layers(
            _overlay_pairs(coords,
                           [None if p["lean"] is None else abs(p["lean"])
                            for p in pts],
                           LEAN_BANDS, tolerance, lat_ref, limit, min_run),
            LEAN_BANDS, "%s lean", TRACK_DECIMALS, 10,
        )))
    if bounds:
        out.update(bounds)
        out["bounds"] = {"ne": bounds["ne"], "sw": bounds["sw"]}
    return out


def ha_coverage(user, params):
    """Every trace the caller owns as one MultiLineString, aggressively thinned.

    This is the entity-attribute counterpart to /ha/traces, which is 950 kB at
    full detail — fine for a page that fetches once and thins in the browser,
    impossible for something Home Assistant has to hold in state. Simplification
    runs per line so a single long trace can't eat the whole budget, then the
    total is capped: `max` is a budget across all lines, not per line.
    """
    uid = user["id"]
    tolerance = _int_param(params, "tol", 25, 0, 500)
    limit = _int_param(params, "max", 6000, 0, 40000)
    cell_m = _int_param(params, "cell", 60, 10, 1000)
    lines = []
    raw_points = 0
    for r in db().execute("SELECT line FROM traces WHERE user_id = ?", (uid,)):
        try:
            points = json.loads(r["line"])
        except (ValueError, TypeError):
            continue  # one corrupt line must not fail the whole overlay
        if not isinstance(points, list):
            continue
        line = []
        for p in points:
            if not isinstance(p, list) or len(p) < 2:
                continue
            try:
                lat, lon = float(p[0]), float(p[1])
            except (TypeError, ValueError):
                continue
            if -90 <= lat <= 90 and -180 <= lon <= 180:
                line.append((lat, lon))
        if len(line) >= 2:
            raw_points += len(line)
            lines.append(line)
    bounds = _bounds([p for line in lines for p in line])
    lat_ref = bounds["latitude"] if bounds else 0.0
    # Counted on the raw lines, before simplification: thinning a trace moves
    # its points off the road it was on, and a heat map that is a cell out is
    # worse than none.
    seen, d_lat, d_lon = _visit_counts(lines, cell_m, lat_ref)
    lines = [simplify_track(line, tolerance, lat_ref) for line in lines]
    kept = sum(len(line) for line in lines)
    if limit and kept > limit:
        # Spend the budget in proportion to how much of the total each line is,
        # so one long ride doesn't get thinned to nothing next to a short one.
        share = limit / float(kept)
        lines = [thin_to(line, max(2, int(len(line) * share))) for line in lines]
        kept = sum(len(line) for line in lines)
    geometry = [[_coord(lat, lon) for lat, lon in line] for line in lines if len(line) >= 2]
    # Each segment takes the busiest cell it touches — its two ends and its
    # middle. Taking only the midpoint loses junctions, where a long simplified
    # segment ends on a road that has been ridden a hundred times.
    pairs = _road_runs([line for line in lines if len(line) >= 2],
                       seen, d_lat, d_lon)
    out = {
        "lineCount": len(geometry),
        "pointCount": raw_points,
        "usedPoints": kept,
        "cellMetres": cell_m,
        "maxVisits": max((c for _last, c in seen.values()), default=0),
        "geojson": {
            "type": "Feature",
            "geometry": {"type": "MultiLineString", "coordinates": geometry},
            "properties": {"name": "%d rides" % len(geometry)},
        },
    }
    out["roadKm"] = round(
        sum(_haversine_m(run[i], run[i + 1])
            for run, _v in pairs for i in range(len(run) - 1)) / 1000.0, 1)
    out.update(_flatten_bands("heat", _banded_layers(pairs, HEAT_BANDS,
                                                     "ridden %s")))
    if bounds:
        out.update(bounds)
        out["bounds"] = {"ne": bounds["ne"], "sw": bounds["sw"]}
    return out


# Mirrors BadgeStore.ALL in the app (app/.../data/Badges.kt). The app syncs only
# *when* each badge was earned; the definitions are derived state and get
# recomputed from stats — there for the phone's screen, here for the dashboard's.
# Keep the two lists in step when a tier is added.
BADGE_TIERS = (
    ("dist", "Distance", "totalDistanceMeters", (
        (100_000, "First hundred"),
        (500_000, "Getting somewhere"),
        (1_000_000, "Four figures"),
        (5_000_000, "Long hauler"),
        (10_000_000, "Ten thousand"),
        (25_000_000, "Round the world"),
    )),
    ("speed", "Top speed", "topSpeedKmh", (
        (100, "Ton up"),
        (130, "Motorway legal"),
        (160, "Quick"),
        (200, "Double ton"),
        (250, "Terminal velocity"),
    )),
    ("ride", "Single ride", "longestTripMeters", (
        (100_000, "Day out"),
        (250_000, "Proper ride"),
        (500_000, "Iron butt"),
    )),
    ("muni", "Places", "municipalitiesVisited", (
        (3, "Wanderer"),
        (10, "Explorer"),
        (25, "Cartographer"),
        (50, "Conqueror"),
    )),
    ("cover", "Coverage", "bestCoveragePercent", (
        (10, "Local knowledge"),
        (25, "Know the back roads"),
        (50, "Half the town"),
        (100, "Every last street"),
    )),
)


def ha_stats(user, params):
    """Lifetime totals and badges for the dashboard's number cards.

    `stats` is what the phone last synced, with two corrections the server is
    better placed to make: the ride count comes from the trips it actually
    holds, and maxLeanDeg is null — not 0 — when nothing has ever recorded a
    lean, because "never measured" and "rode upright" are different answers.

    `badges` is id -> earned-at ms, the raw synced map. `badgeCatalogue` scores
    every defined badge, earned or not, so a card can show progress towards the
    next one without knowing the tiers itself."""
    uid = user["id"]
    row = db().execute(
        "SELECT stats_json, badges_json FROM users WHERE id = ?", (uid,)
    ).fetchone()
    if row is None:
        raise HttpError(404, "no such user")
    stats = json.loads(row["stats_json"] or "{}")
    earned = json.loads(row["badges_json"] or "{}")

    ride_count = db().execute(
        "SELECT COUNT(*) AS n FROM trips WHERE user_id = ?", (uid,)
    ).fetchone()["n"]
    stats["tripCount"] = ride_count

    # The points table only goes back as far as lean has been stored, and the
    # phone's own figure only counts rides it still holds — so take whichever
    # is deeper, and null when neither has anything.
    point_lean = db().execute(
        "SELECT MAX(ABS(lean_deg)) AS lean FROM track_points WHERE user_id = ?", (uid,)
    ).fetchone()["lean"]
    leans = [v for v in (point_lean, stats.get("maxLeanDeg")) if v]
    stats["maxLeanDeg"] = round(max(leans), 1) if leans else None

    catalogue = []
    for prefix, kind, stat_key, tiers in BADGE_TIERS:
        value = float(stats.get(stat_key) or 0)
        for threshold, title in tiers:
            badge_id = "%s_%d" % (prefix, threshold)
            catalogue.append({
                "id": badge_id,
                "kind": kind,
                "title": title,
                "threshold": threshold,
                "value": round(value, 1),
                "earnedMs": earned.get(badge_id),
                "progressPercent": round(min(value / threshold, 1.0) * 100, 1),
            })

    return {
        "stats": stats,
        "rideCount": ride_count,
        "badgeCount": len(earned),
        "badges": earned,
        "badgeCatalogue": catalogue,
    }


def ha_ride(user, params):
    """One ride as GeoJSON: a Feature per segment, carrying the speed and lean
    recorded at its far end. Per-segment rather than one line, because a line
    can only be one colour — and colouring by lean is the whole point."""
    uid = user["id"]
    # A junk ?start= is a bad request, not a server fault: bare int() raised
    # ValueError out of the handler, which the catch-all turned into a 500.
    try:
        start = int((params.get("start") or ["0"])[0])
    except ValueError:
        raise HttpError(400, "start must be a timestamp in milliseconds")
    trip, end = ride_window(uid, start)
    pts = ride_points(uid, start, end)
    features = []
    for a, b in zip(pts, pts[1:]):
        features.append({
            "type": "Feature",
            "geometry": {
                "type": "LineString",
                "coordinates": [[a["lon"], a["lat"]], [b["lon"], b["lat"]]],
            },
            "properties": {"tMs": b["t"], "speedKmh": b["speed"], "leanDeg": b["lean"]},
        })
    leans = [abs(p["lean"]) for p in pts if p["lean"] is not None]
    speeds = [p["speed"] for p in pts if p["speed"] is not None]
    return {
        "type": "FeatureCollection",
        "features": features,
        "properties": {
            "startMs": start,
            "endMs": end,
            "mode": trip.get("mode"),
            "distanceKm": round((trip.get("distanceMeters") or 0) / 1000.0, 2),
            "topSpeedKmh": round(max(speeds), 1) if speeds else None,
            "maxLeanDeg": round(max(leans), 1) if leans else None,
            "pointCount": len(pts),
        },
    }


# Leaflet comes from a CDN: the dashboard embedding this already needs the
# internet for map tiles, and vendoring a copy here would be a second thing to
# keep patched. leaflet.heat is the one addition over the old single-ride page,
# for the Heat tab's density layer.
#
# %-formatting is gone from this page on purpose: a page this size interpolated
# with % would need every literal % in the CSS/JS doubled, which is a landmine
# (see the old RIDE_HTML for what that looked like). str.replace() on one
# explicit placeholder has no such trap.
DASH_HTML = r"""<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Detour</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
  :root {
    color-scheme: dark;
    --bg: #14170f; --panel: #191c14; --panel-2: #21241a; --border: #3a3d31;
    --text: #ede9db; --muted: #b7af98; --accent: #e8b04b; --accent-ink: #2a2205;
    --neutral: #6b7060; --red: #e2402a; --blue: #2f6fed; --green: #1baf7a;
    --shadow: 0 2px 10px rgba(0,0,0,.45);
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; margin: 0; }
  body {
    display: flex; flex-direction: column; background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
      Helvetica, Arial, sans-serif;
    font-size: 14px;
  }
  button, select, input { font: inherit; color: inherit; }
  a { color: var(--accent); }

  /* -- top bar / tabs ---------------------------------------------------- */
  .topbar { flex: 0 0 auto; display: flex; align-items: center; gap: 10px;
    padding: 8px 10px; background: var(--panel); border-bottom: 1px solid var(--border); }
  .brand { font-weight: 700; font-size: 14px; white-space: nowrap; color: var(--accent); }
  .tabs { display: flex; gap: 2px; overflow-x: auto; flex: 1 1 auto; }
  .tab-btn { flex: 0 0 auto; padding: 9px 14px; border: 1px solid transparent; border-radius: 8px;
    background: transparent; color: var(--muted); cursor: pointer; min-height: 40px; }
  .tab-btn.active { background: var(--panel-2); color: var(--text); border-color: var(--border); }
  .tab-btn:hover { color: var(--text); }

  .tab-panel { display: none; flex: 1 1 auto; min-height: 0; flex-direction: column; }
  .tab-panel.active { display: flex; }

  /* -- generic states ------------------------------------------------------ */
  .banner { margin: 10px; padding: 10px 12px; border-radius: 8px; font-size: 13px; }
  .banner.error { background: rgba(226,64,42,.12); border: 1px solid rgba(226,64,42,.4); color: #f2b3a8; }
  .banner.info { background: rgba(232,176,75,.1); border: 1px solid rgba(232,176,75,.35); color: var(--accent); }
  .empty { margin: auto; padding: 30px 16px; text-align: center; color: var(--muted); }
  .empty b { display: block; color: var(--text); margin-bottom: 4px; font-size: 15px; }
  .spinner { margin: auto; color: var(--muted); padding: 30px; text-align: center; }

  /* -- map tab ------------------------------------------------------------ */
  .map-layout { flex: 1 1 auto; display: flex; min-height: 0; }
  .sidebar { flex: 0 0 280px; display: flex; flex-direction: column; background: var(--panel);
    border-right: 1px solid var(--border); min-height: 0; }
  .sidebar-toggle { display: none; width: 100%; padding: 10px; background: var(--panel);
    border: none; border-bottom: 1px solid var(--border); color: var(--text); text-align: left;
    min-height: 44px; }
  .sidebar-filters { flex: 0 0 auto; display: flex; gap: 6px; padding: 8px; border-bottom: 1px solid var(--border); }
  .sidebar-filters input, .sidebar-filters select { background: var(--bg); border: 1px solid var(--border);
    color: var(--text); border-radius: 6px; padding: 7px 8px; min-height: 36px; }
  .sidebar-filters input { flex: 1 1 auto; min-width: 0; }
  .ride-list { flex: 1 1 auto; overflow-y: auto; }
  .ride-item { display: flex; flex-direction: column; gap: 2px; padding: 9px 12px; cursor: pointer;
    border-bottom: 1px solid rgba(58,61,49,.5); min-height: 44px; justify-content: center; }
  .ride-item:hover { background: var(--panel-2); }
  .ride-item.selected { background: var(--accent); color: var(--accent-ink); }
  .ride-item .line1 { display: flex; justify-content: space-between; font-size: 13px; font-weight: 600; }
  .ride-item .line2 { display: flex; justify-content: space-between; font-size: 12px; color: var(--muted); }
  .ride-item.selected .line2 { color: var(--accent-ink); opacity: .8; }

  .map-main { flex: 1 1 auto; display: flex; flex-direction: column; min-height: 0; min-width: 0; }
  .overlay-bar { flex: 0 0 auto; display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
    padding: 8px 10px; background: var(--panel); border-bottom: 1px solid var(--border); }
  .overlay-bar label { font-size: 12px; color: var(--muted); }
  .overlay-bar select { background: var(--bg); border: 1px solid var(--border); color: var(--text);
    border-radius: 6px; padding: 7px 8px; min-height: 36px; }
  #map { flex: 1 1 auto; min-height: 0; background: #11131a; }

  .legend { background: rgba(25,28,20,.92); color: var(--text); padding: 8px 10px;
    font: 12px system-ui, sans-serif; border-radius: 8px; border: 1px solid var(--border); box-shadow: var(--shadow); }
  .legend b { display: block; margin-bottom: 4px; font-weight: 600; }
  .legend .bar { width: 160px; height: 10px; border-radius: 5px; margin: 4px 0; }
  .legend .ends { display: flex; justify-content: space-between; color: var(--muted); }
  .legend .note { color: var(--muted); font-style: italic; margin-top: 4px; max-width: 180px; }

  .profile-strip { flex: 0 0 auto; height: 110px; background: var(--panel); border-top: 1px solid var(--border); position: relative; }
  .profile-strip svg { width: 100%; height: 100%; display: block; touch-action: none; }
  .profile-tip { position: absolute; pointer-events: none; background: var(--panel-2); border: 1px solid var(--border);
    border-radius: 6px; padding: 4px 7px; font-size: 11px; color: var(--text); white-space: nowrap; box-shadow: var(--shadow); }

  .stat-row { flex: 0 0 auto; display: flex; flex-wrap: wrap; gap: 0; background: var(--panel); border-top: 1px solid var(--border); }
  .stat-row .stat { flex: 1 1 90px; padding: 8px 10px; font-size: 11px; color: var(--muted); border-right: 1px solid var(--border); }
  .stat-row .stat b { display: block; font-size: 15px; font-weight: 600; color: var(--text); margin-top: 2px; }
  .stat-row .stat:last-child { border-right: none; }

  @media (max-width: 820px) {
    .map-layout { flex-direction: column; }
    .sidebar { flex: 0 0 auto; max-height: 0; overflow: hidden; border-right: none; border-bottom: 1px solid var(--border); transition: max-height .2s ease; }
    .sidebar.open { max-height: 50vh; overflow-y: auto; }
    .sidebar-toggle { display: block; }
    .stat-row { overflow-x: auto; }
  }

  /* -- heat tab ------------------------------------------------------------ */
  .heat-bar { flex: 0 0 auto; display: flex; flex-wrap: wrap; align-items: center; gap: 10px;
    padding: 8px 10px; background: var(--panel); border-bottom: 1px solid var(--border); }
  .heat-bar button { background: var(--panel-2); border: 1px solid var(--border); color: var(--text);
    border-radius: 6px; padding: 8px 12px; min-height: 36px; cursor: pointer; }
  .heat-bar button.active { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
  .heat-bar .note { font-size: 12px; color: var(--muted); }
  #heatmap { flex: 1 1 auto; min-height: 0; background: #11131a; }

  /* -- general / badges scroll area ---------------------------------------- */
  .scroll-tab { flex: 1 1 auto; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 18px; }
  .section-title { font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em;
    color: var(--muted); margin: 0 0 8px; }
  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 8px; }
  .stat-card { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; }
  .stat-card .label { font-size: 11px; color: var(--muted); }
  .stat-card .value { font-size: 20px; font-weight: 700; margin-top: 3px; }

  .panel { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 12px; }
  .chart-wrap { overflow-x: auto; }
  .chart-wrap svg { display: block; }
  .axis-label { fill: var(--muted); font-size: 10px; }
  .bar-label { fill: var(--text); font-size: 10px; }

  .records-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 8px; }
  .record-card { background: var(--panel-2); border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px;
    cursor: pointer; text-align: left; color: inherit; min-height: 44px; }
  .record-card:hover { border-color: var(--accent); }
  .record-card .label { font-size: 11px; color: var(--muted); }
  .record-card .value { font-size: 18px; font-weight: 700; color: var(--accent); margin-top: 2px; }
  .record-card .when { font-size: 11px; color: var(--muted); margin-top: 2px; }

  .table-wrap { overflow-x: auto; }
  table { border-collapse: collapse; width: 100%; min-width: 640px; font-size: 12px; }
  th, td { padding: 7px 10px; text-align: left; border-bottom: 1px solid var(--border); white-space: nowrap; }
  th { color: var(--muted); font-weight: 600; cursor: pointer; user-select: none; position: sticky; top: 0; background: var(--panel); }
  th.sorted::after { content: " \25BE"; color: var(--accent); }
  th.sorted.desc::after { content: " \25B4"; }
  tr:hover td { background: var(--panel-2); }

  .cal-grid { display: block; }
  .cal-cell { stroke: var(--border); stroke-width: .5; }

  /* -- badges tab ---------------------------------------------------------- */
  .badge-overall { display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px; }
  .badge-overall b { font-size: 22px; color: var(--accent); }
  .badge-kind { margin-bottom: 6px; }
  .badge-kind h3 { font-size: 13px; margin: 0 0 6px; color: var(--text); }
  .badge-tier { display: flex; align-items: center; gap: 10px; padding: 7px 0; border-bottom: 1px solid rgba(58,61,49,.5); }
  .badge-tier .dot { flex: 0 0 auto; width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center;
    justify-content: center; font-size: 12px; }
  .badge-tier.earned .dot { background: var(--accent); color: var(--accent-ink); }
  .badge-tier.locked .dot { background: var(--panel-2); border: 1px solid var(--border); color: var(--muted); }
  .badge-tier .body { flex: 1 1 auto; min-width: 0; }
  .badge-tier .title { font-size: 13px; font-weight: 600; }
  .badge-tier.locked .title { color: var(--muted); }
  .badge-tier .meta { font-size: 11px; color: var(--muted); }
  .badge-tier .progress { height: 5px; border-radius: 3px; background: var(--panel-2); margin-top: 4px; overflow: hidden; }
  .badge-tier .progress i { display: block; height: 100%; background: var(--accent); }
  .timeline-item { display: flex; gap: 8px; font-size: 12px; padding: 4px 0; color: var(--muted); }
  .timeline-item b { color: var(--text); font-weight: 600; }
</style>

<div class="topbar">
  <div class="brand">Detour</div>
  <div class="tabs" role="tablist">
    <button class="tab-btn" data-tab="map">Map</button>
    <button class="tab-btn" data-tab="heat">Heat</button>
    <button class="tab-btn" data-tab="general">General</button>
    <button class="tab-btn" data-tab="badges">Badges</button>
  </div>
</div>

<section class="tab-panel" id="tab-map">
  <div class="map-layout">
    <aside class="sidebar" id="sidebar">
      <button class="sidebar-toggle" id="sidebarToggle" type="button">Rides &#9662;</button>
      <div class="sidebar-filters">
        <select id="modeFilter"><option value="">All modes</option></select>
        <input id="rideSearch" type="search" placeholder="Search date or mode&hellip;">
      </div>
      <div class="ride-list" id="rideList"><div class="spinner">Loading rides&hellip;</div></div>
    </aside>
    <div class="map-main">
      <div class="overlay-bar">
        <label for="overlaySelect">Colour by</label>
        <select id="overlaySelect">
          <option value="speed">Speed</option>
          <option value="lean">Lean</option>
          <option value="gforce">Cornering g</option>
          <option value="accel">Accel / brake</option>
          <option value="time">Time</option>
        </select>
        <span class="banner error" id="mapError" style="display:none"></span>
      </div>
      <div id="map"></div>
      <div class="profile-strip" id="profileStrip"><svg id="profileSvg"></svg></div>
      <div class="stat-row" id="statRow"></div>
    </div>
  </div>
</section>

<section class="tab-panel" id="tab-heat">
  <div class="heat-bar">
    <button id="heatModeHeat" class="active" type="button">Heat</button>
    <button id="heatModeLines" type="button">Raw fog (lines)</button>
    <button id="heatFit" type="button">Fit to coverage</button>
    <span class="note">Every recorded trace, position only. Rides from before speed/lean were tracked still show up here &mdash; they just have nothing else to offer.</span>
  </div>
  <div id="heatmap"></div>
</section>

<section class="tab-panel" id="tab-general">
  <div class="scroll-tab" id="generalBody"><div class="spinner">Loading&hellip;</div></div>
</section>

<section class="tab-panel" id="tab-badges">
  <div class="scroll-tab" id="badgesBody"><div class="spinner">Loading&hellip;</div></div>
</section>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script src="https://unpkg.com/leaflet.heat@0.2.0/dist/leaflet-heat.js"></script>
<script>
"use strict";
// __START_MS__ is the only thing the server templates into this page — cast
// through int() server-side, so it can never carry markup. Everything else
// (including the API key) is read here, client-side, from the URL the
// browser already has.
const INITIAL_START = __START_MS__;
const API_KEY = new URLSearchParams(location.search).get("key") || "";

/* ------------------------------------------------------------------------ *
 * formatting helpers
 * ------------------------------------------------------------------------ */
function fmtKm(m) { return m == null ? "—" : (m / 1000).toFixed(1) + " km"; }
function fmtSpeed(kmh) { return kmh == null ? "—" : Math.round(kmh) + " km/h"; }
function fmtLean(deg) { return deg == null ? "—" : deg.toFixed(0) + "°"; }
function fmtG(g) { return g == null ? "—" : g.toFixed(2) + "g"; }
function fmtInt(n) { return n == null ? "—" : Math.round(n).toLocaleString(); }
function fmtPct(v) { return v == null ? "—" : Math.round(v) + "%"; }
function fmtDuration(ms) {
  if (ms == null || ms < 0) return "—";
  const m = Math.round(ms / 60000);
  return m < 60 ? m + " min" : Math.floor(m / 60) + "h " + (m % 60) + "m";
}
function fmtDate(ms) { return ms ? new Date(ms).toLocaleDateString([], { dateStyle: "medium" }) : "—"; }
function fmtDateTime(ms) {
  return ms ? new Date(ms).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "—";
}
// `mode` is whatever the app synced: trips are stored as the client sent them
// (do_sync json.dumps'es the whole trip dict), so it is user-controlled text,
// not an enum the server has vetted. The old single-ride page guarded it where
// it embedded the geojson; this page builds HTML strings instead, so every
// interpolation of it goes through esc() and fmtMode never returns raw markup.
function esc(s) {
  return String(s).replace(/[&<>"']/g, c => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function fmtMode(m) { return m ? esc(m.charAt(0) + m.slice(1).toLowerCase()) : "Ride"; }

/* ------------------------------------------------------------------------ *
 * API layer — every call forwards the key read from our own URL, never one
 * templated server-side (see header comment).
 * ------------------------------------------------------------------------ */
class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
async function api(path, params) {
  const url = new URL(path, location.origin);
  url.searchParams.set("key", API_KEY);
  for (const k in (params || {})) url.searchParams.set(k, params[k]);
  let res;
  try {
    res = await fetch(url);
  } catch (e) {
    throw new ApiError(0, "Couldn't reach the server — check your connection.");
  }
  let body = null;
  try { body = await res.json(); } catch (e) { /* non-JSON error page, fall through */ }
  if (!res.ok) {
    if (res.status === 401) throw new ApiError(401, "Check the API key in this page's URL.");
    throw new ApiError(res.status, (body && body.error) || ("request failed (" + res.status + ")"));
  }
  return body;
}
function errorMessage(e) {
  return e instanceof ApiError ? e.message : "Something went wrong.";
}

/* ------------------------------------------------------------------------ *
 * state — fetched once and reused across tabs; geojson cached per ride so
 * flipping the overlay selector never re-fetches.
 * ------------------------------------------------------------------------ */
const state = {
  tab: "map",
  startMs: INITIAL_START || null,
  rides: null,          // array from /ha/rides, newest first
  ridesByStart: new Map(),
  stats: null,
  geojsonCache: new Map(),
  traces: null,
  current: null,        // { geo, verts, cumKm, speedVals, leanVals, gVals, accelVals, timeVals, speedRange }
};
let ridesPromise = null, statsPromise = null, tracesPromise = null;
function loadRides() {
  if (!ridesPromise) ridesPromise = api("/ha/rides", { limit: 500 }).then(d => {
    state.rides = d.rides;
    state.ridesByStart = new Map(d.rides.map(r => [r.startMs, r]));
    return d.rides;
  });
  return ridesPromise;
}
function loadStats() {
  if (!statsPromise) statsPromise = api("/ha/stats").then(d => { state.stats = d; return d; });
  return statsPromise;
}
function loadTraces() {
  if (!tracesPromise) tracesPromise = api("/ha/traces").then(d => { state.traces = d.traces; return d.traces; });
  return tracesPromise;
}
async function loadGeojson(startMs) {
  if (state.geojsonCache.has(startMs)) return state.geojsonCache.get(startMs);
  const geo = await api("/ha/ride.geojson", { start: startMs });
  state.geojsonCache.set(startMs, geo);
  return geo;
}

/* ------------------------------------------------------------------------ *
 * routing — tab in the hash, selected ride in the query string, so a reload
 * lands back where it was without re-templating anything server-side.
 * ------------------------------------------------------------------------ */
function currentUrlState() {
  const tab = (location.hash || "#map").slice(1);
  const start = new URLSearchParams(location.search).get("start");
  return { tab: ["map", "heat", "general", "badges"].includes(tab) ? tab : "map", start: start ? Number(start) : null };
}
function setUrl(tab, startMs) {
  const url = new URL(location.href);
  url.hash = tab;
  if (startMs) url.searchParams.set("start", startMs); else url.searchParams.delete("start");
  history.replaceState(null, "", url);
}

/* ==========================================================================
 * derived metrics — the formulas the task calls "derived": g has no per-point
 * field anywhere upstream, so cornering g and accel/brake g are computed here
 * from consecutive points, and clamped because GPS noise on a short baseline
 * produces the occasional absurd spike.
 * ========================================================================== */
const CORNER_G_CEIL = 1.5;   // sport-riding cornering rarely clears this; treat anything past it as noise
const ACCEL_G_CEIL = 1.0;    // roughly the traction limit for street tires; a bigger reading is a GPS artifact
const EARTH_R = 6371000;
const toRad = d => d * Math.PI / 180;

function haversine(a, b) {
  const dLat = toRad(b.lat - a.lat), dLon = toRad(b.lon - a.lon);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_R * Math.asin(Math.sqrt(s));
}
function toXY(p, refLat) {
  // Local equirectangular projection around a reference latitude, good enough
  // over the tens-of-metres baseline between three consecutive trace points.
  return { x: EARTH_R * toRad(p.lon) * Math.cos(toRad(refLat)), y: EARTH_R * toRad(p.lat) };
}
function buildVerts(geo) {
  const feats = geo.features;
  if (!feats.length) return [];
  const verts = [{
    lat: feats[0].geometry.coordinates[0][1], lon: feats[0].geometry.coordinates[0][0],
    t: geo.properties.startMs, speed: null, lean: null,
  }];
  for (const f of feats) {
    const c = f.geometry.coordinates[1];
    verts.push({ lat: c[1], lon: c[0], t: f.properties.tMs, speed: f.properties.speedKmh, lean: f.properties.leanDeg });
  }
  return verts;
}
function cumulativeKm(verts) {
  const out = [0];
  for (let i = 1; i < verts.length; i++) out.push(out[i - 1] + haversine(verts[i - 1], verts[i]) / 1000);
  return out;
}
function corneringG(verts) {
  // Circumradius r through three consecutive points, then a_lat = v^2 / r.
  // Assigned to the segment centred on the middle point; the first and last
  // segments have no interior neighbour and stay null (edge of the ride, not
  // "zero g").
  const g = new Array(Math.max(verts.length - 1, 0)).fill(null);
  for (let i = 1; i < verts.length - 1; i++) {
    const A = verts[i - 1], B = verts[i], C = verts[i + 1];
    if (B.speed == null) continue;
    const ref = B.lat;
    const a = toXY(A, ref), b = toXY(B, ref), c = toXY(C, ref);
    const area = Math.abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2;
    const ab = Math.hypot(b.x - a.x, b.y - a.y), bc = Math.hypot(c.x - b.x, c.y - b.y), ac = Math.hypot(c.x - a.x, c.y - a.y);
    if (ab < 0.5 || bc < 0.5) continue; // points on top of each other (stopped/dupe) — not a corner, not noise either
    if (area < 0.05) { g[i] = 0; continue; } // collinear: r -> infinity -> no lateral acceleration
    const r = (ab * bc * ac) / (4 * area);
    const v = B.speed / 3.6;
    g[i] = Math.min((v * v / r) / 9.81, CORNER_G_CEIL);
  }
  return g;
}
function accelBrakeG(verts) {
  const out = new Array(Math.max(verts.length - 1, 0)).fill(null);
  for (let i = 0; i < verts.length - 1; i++) {
    const A = verts[i], B = verts[i + 1];
    if (A.speed == null || B.speed == null || A.t == null || B.t == null) continue;
    const dt = (B.t - A.t) / 1000;
    // Guard both ends: a non-positive/duplicate timestamp divides by ~0, and
    // a multi-second gap in recording turns an ordinary speed change into a
    // spike that never really happened at that rate.
    if (dt <= 0.2 || dt > 8) continue;
    const dv = (B.speed - A.speed) / 3.6;
    out[i] = Math.max(-ACCEL_G_CEIL, Math.min(ACCEL_G_CEIL, dv / dt / 9.81));
  }
  return out;
}

/* ------------------------------------------------------------------------ *
 * colour ramps — see the dataviz skill: sequential = one hue, light->dark;
 * diverging = two hues + a neutral grey midpoint. Hues are chosen to match
 * (and stay validated against) the categorical set used for mode split:
 * blue #3987e5 (~214deg), orange #d95926 (~20deg), aqua #199e70 (~162deg);
 * time reuses the app's own accent hue (~40deg) instead of a new one.
 * ------------------------------------------------------------------------ */
const NEUTRAL = "#6b7060"; // "no data for this segment" — same grey everywhere so it always reads the same way
function seqColor(t, hue) {
  t = Math.max(0, Math.min(1, t));
  const l = 82 - t * 60; // 82% (near-surface) -> 22% (near-black), one hue throughout
  return "hsl(" + hue + " 68% " + l + "%)";
}
function seqStops(hue, n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(seqColor(i / (n - 1), hue));
  return out;
}
function mixRgb(a, b, k) { return a.map((v, i) => Math.round(v + (b[i] - v) * k)); }
function divergeColor(t, negRgb, posRgb) {
  t = Math.max(-1, Math.min(1, t));
  const grey = [107, 112, 96];
  const rgb = t < 0 ? mixRgb(grey, negRgb, -t) : mixRgb(grey, posRgb, t);
  return "rgb(" + rgb.join(",") + ")";
}
const LEAN_BLUE = [47, 111, 237], LEAN_RED = [226, 64, 42];
const BRAKE_RED = [226, 64, 42], ACCEL_GREEN = [27, 175, 122];

const OVERLAYS = {
  speed: {
    label: "Speed", kind: "sequential", hue: 214,
    value: (d, i) => d.speedVals[i],
    color: (d, i) => { const v = d.speedVals[i]; if (v == null) return NEUTRAL;
      const { min, max } = d.speedRange; return seqColor(max > min ? (v - min) / (max - min) : 0, 214); },
    tip: (d, i) => { const v = d.speedVals[i]; return v == null ? "speed n/a" : Math.round(v) + " km/h"; },
    legend(d) {
      const { min, max } = d.speedRange;
      return { grad: seqStops(214, 6), left: (min == null ? "—" : Math.round(min) + " km/h"),
        right: (max == null ? "—" : Math.round(max) + " km/h") };
    },
  },
  lean: {
    label: "Lean", kind: "diverging", hue: null,
    value: (d, i) => d.leanVals[i],
    color: (d, i) => { const v = d.leanVals[i]; return v == null ? NEUTRAL : divergeColor(v / 45, LEAN_BLUE, LEAN_RED); },
    tip: (d, i) => { const v = d.leanVals[i]; return v == null ? "lean n/a" : v.toFixed(0) + "° lean"; },
    legend(d) {
      const grad = [LEAN_BLUE, [107, 112, 96], LEAN_RED].map(c => "rgb(" + c.join(",") + ")");
      const note = d.leanVals.every(v => v == null) ? "No lean recorded for this ride — grey throughout." : null;
      return { grad, left: "left 45°", right: "right 45°", note };
    },
  },
  gforce: {
    label: "Cornering g", kind: "sequential", hue: 20,
    value: (d, i) => d.gVals[i],
    color: (d, i) => { const v = d.gVals[i]; return v == null ? NEUTRAL : seqColor(v / CORNER_G_CEIL, 20); },
    tip: (d, i) => { const v = d.gVals[i]; return v == null ? "corner g n/a" : v.toFixed(2) + "g corner (derived)"; },
    legend(d) {
      return { grad: seqStops(20, 6), left: "0g", right: "≥" + CORNER_G_CEIL + "g (clamped)",
        note: "Derived from GPS speed + curvature, not a device sensor — different from the trip's Max g stat below." };
    },
  },
  accel: {
    label: "Accel / brake", kind: "diverging", hue: null,
    value: (d, i) => d.accelVals[i],
    color: (d, i) => { const v = d.accelVals[i]; return v == null ? NEUTRAL : divergeColor(v / ACCEL_G_CEIL, BRAKE_RED, ACCEL_GREEN); },
    tip: (d, i) => { const v = d.accelVals[i]; if (v == null) return "accel n/a";
      return (v < 0 ? "braking " : "accelerating ") + Math.abs(v).toFixed(2) + "g"; },
    legend(d) {
      const grad = [BRAKE_RED, [107, 112, 96], ACCEL_GREEN].map(c => "rgb(" + c.join(",") + ")");
      return { grad, left: "braking " + ACCEL_G_CEIL + "g", right: "accel " + ACCEL_G_CEIL + "g",
        note: "Δspeed / Δt between points — derived, not measured." };
    },
  },
  time: {
    label: "Time", kind: "sequential", hue: 40,
    value: (d, i) => d.timeVals[i],
    color: (d, i) => { const v = d.timeVals[i]; if (v == null) return NEUTRAL;
      const { startMs, endMs } = d.geo.properties; return seqColor(endMs > startMs ? (v - startMs) / (endMs - startMs) : 0, 40); },
    tip: (d, i) => { const v = d.timeVals[i]; return v == null ? "" : fmtDateTime(v); },
    legend(d) { return { grad: seqStops(40, 6), left: "start", right: "finish" }; },
  },
};

/* ==========================================================================
 * Map tab
 * ========================================================================== */
let map = null, rideLayer = null, startMarker = null, endMarker = null, hlMarker = null, legendCtl = null;
let overlayName = "speed";

function ensureMap() {
  if (map) return;
  map = L.map("map", { zoomControl: true });
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap",
  }).addTo(map);
  map.setView([50.85, 4.35], 9);
  legendCtl = L.control({ position: "bottomright" });
  legendCtl.onAdd = () => L.DomUtil.create("div", "legend");
  legendCtl.addTo(map);
}

function renderLegend() {
  const spec = OVERLAYS[overlayName];
  const l = spec.legend(state.current);
  const el = legendCtl.getContainer();
  el.innerHTML = "<b>" + spec.label + "</b>" +
    '<div class="bar" style="background:linear-gradient(90deg,' + l.grad.join(",") + ')"></div>' +
    '<div class="ends"><span>' + l.left + "</span><span>" + l.right + "</span></div>" +
    (l.note ? '<div class="note">' + l.note + "</div>" : "");
}

function clearMapError() { const el = document.getElementById("mapError"); el.style.display = "none"; el.textContent = ""; }
function showMapError(msg) { const el = document.getElementById("mapError"); el.style.display = "inline"; el.textContent = msg; }

function setHighlight(idx) {
  if (!state.current) return;
  const verts = state.current.verts;
  if (idx == null || !verts[idx + 1]) {
    if (hlMarker) { map.removeLayer(hlMarker); hlMarker = null; }
  } else {
    const v = verts[idx + 1];
    if (!hlMarker) {
      hlMarker = L.circleMarker([v.lat, v.lon], { radius: 7, color: "#e8b04b", weight: 2, fillColor: "#e8b04b", fillOpacity: .9 }).addTo(map);
    } else {
      hlMarker.setLatLng([v.lat, v.lon]);
    }
  }
  updateProfileGuide(idx);
}

async function renderStatRow(geo) {
  const p = geo.properties;
  const ride = state.ridesByStart.get(p.startMs);
  const avgSpeed = p.distanceKm && (p.endMs > p.startMs)
    ? p.distanceKm / ((p.endMs - p.startMs) / 3600000) : null;
  const stats = [
    ["Distance", fmtKm((p.distanceKm || 0) * 1000)],
    ["Duration", fmtDuration(p.endMs - p.startMs)],
    ["Avg speed", fmtSpeed(avgSpeed)],
    ["Top speed", fmtSpeed(p.topSpeedKmh)],
    ["Max lean", fmtLean(p.maxLeanDeg)],
    ["Max g (device)", ride && ride.maxGForce != null ? fmtG(ride.maxGForce) : "—"],
    ["Points", fmtInt(p.pointCount)],
  ];
  document.getElementById("statRow").innerHTML = stats.map(([label, value]) =>
    '<div class="stat">' + label + "<b>" + value + "</b></div>").join("");
}

// Clicking a second ride while the first is still loading must not let the
// first one win when it lands: a cached ride resolves instantly, an uncached
// one a network round-trip later, so "last click" and "last to resolve" are
// not the same order. Every load takes a ticket and drops what it drew if a
// newer one has been issued since.
let selectSeq = 0;
async function selectRide(startMs) {
  if (!startMs) {
    ensureMap();
    showEmptyMap("No ride selected.");
    return;
  }
  const seq = ++selectSeq;
  state.startMs = startMs;
  setUrl(state.tab, startMs);
  highlightSidebar(startMs);
  clearMapError();
  ensureMap();
  try {
    const geo = await loadGeojson(startMs);
    if (seq !== selectSeq) return;  // a newer selection has already drawn
    if (!geo.features.length) {
      clearRideLayers();
      showEmptyMap("This ride has no recorded points.");
      state.current = { geo, verts: [], cumKm: [], speedVals: [], leanVals: [], gVals: [], accelVals: [], timeVals: [], speedRange: {} };
      renderStatRow(geo);
      renderProfile();
      return;
    }
    hideEmptyMap();
    // Leaflet hands the style/onEachFeature callbacks the feature, not its
    // position, and looking that up with indexOf() is a linear scan inside a
    // per-feature loop — a few hundred thousand comparisons on an ordinary
    // ride, repeated on every overlay switch. Number them once instead.
    geo.features.forEach((f, i) => { f.properties._i = i; });
    const verts = buildVerts(geo);
    const speedVals = geo.features.map(f => f.properties.speedKmh);
    const leanVals = geo.features.map(f => f.properties.leanDeg);
    const timeVals = geo.features.map(f => f.properties.tMs);
    const speeds = speedVals.filter(v => v != null);
    state.current = {
      geo, verts, cumKm: cumulativeKm(verts),
      speedVals, leanVals, timeVals,
      gVals: corneringG(verts), accelVals: accelBrakeG(verts),
      speedRange: { min: speeds.length ? Math.min(...speeds) : null, max: speeds.length ? Math.max(...speeds) : null },
    };
    drawRide();
    renderStatRow(geo);
    renderProfile();
  } catch (e) {
    if (seq === selectSeq) showMapError(errorMessage(e));
  }
}

function showEmptyMap(msg) {
  let el = document.getElementById("mapEmpty");
  if (!el) {
    el = document.createElement("div");
    el.id = "mapEmpty";
    el.className = "empty";
    el.style.cssText = "position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(20,23,15,.85);z-index:500;";
    document.getElementById("map").style.position = "relative";
    document.getElementById("map").appendChild(el);
  }
  el.innerHTML = "<div><b>Nothing to show</b>" + msg + "</div>";
  el.style.display = "flex";
}
function hideEmptyMap() { const el = document.getElementById("mapEmpty"); if (el) el.style.display = "none"; }

function clearRideLayers() {
  if (rideLayer) { map.removeLayer(rideLayer); rideLayer = null; }
  if (startMarker) { map.removeLayer(startMarker); startMarker = null; }
  if (endMarker) { map.removeLayer(endMarker); endMarker = null; }
  if (hlMarker) { map.removeLayer(hlMarker); hlMarker = null; }
}

function drawRide() {
  clearRideLayers();
  const d = state.current;
  const spec = OVERLAYS[overlayName];
  rideLayer = L.geoJSON(d.geo, {
    style: (f) => ({ color: spec.color(d, f.properties._i), weight: 5, opacity: .92 }),
    onEachFeature: (f, layer) => {
      const i = f.properties._i;
      const p = f.properties;
      layer.bindTooltip(spec.tip(d, i) + " · " + (p.speedKmh == null ? "?" : Math.round(p.speedKmh)) + " km/h");
      layer.on("mouseover", () => setHighlight(i));
    },
  }).addTo(map);
  const verts = d.verts;
  startMarker = L.circleMarker([verts[0].lat, verts[0].lon], { radius: 6, color: "#1baf7a", weight: 2, fillColor: "#1baf7a", fillOpacity: 1 })
    .bindTooltip("Start").addTo(map);
  const last = verts[verts.length - 1];
  endMarker = L.circleMarker([last.lat, last.lon], { radius: 6, color: "#e2402a", weight: 2, fillColor: "#e2402a", fillOpacity: 1 })
    .bindTooltip("End").addTo(map);
  map.fitBounds(rideLayer.getBounds(), { padding: [24, 24] });
  renderLegend();
}

function onOverlayChange() {
  overlayName = document.getElementById("overlaySelect").value;
  if (state.current && state.current.verts.length) { drawRide(); renderProfile(); }
}

/* -- profile strip -------------------------------------------------------- */
function renderProfile() {
  const svg = document.getElementById("profileSvg");
  const d = state.current;
  if (!d || !d.verts.length) { svg.innerHTML = ""; return; }
  const spec = OVERLAYS[overlayName];
  const values = d.geo.features.map((_, i) => spec.value(d, i));
  const cumKm = d.cumKm;
  const w = svg.clientWidth || 600, h = svg.clientHeight || 110;
  const pad = { l: 4, r: 4, t: 8, b: 4 };
  const totalKm = cumKm[cumKm.length - 1] || 1;
  const xAt = km => pad.l + (km / totalKm) * (w - pad.l - pad.r);
  const finite = values.filter(v => v != null);
  let domainMin, domainMax;
  if (spec.kind === "diverging") {
    const ceil = overlayName === "lean" ? 45 : ACCEL_G_CEIL;
    domainMin = -ceil; domainMax = ceil;
  } else if (overlayName === "gforce") {
    domainMin = 0; domainMax = CORNER_G_CEIL;
  } else if (overlayName === "time") {
    domainMin = d.geo.properties.startMs; domainMax = d.geo.properties.endMs || (domainMin + 1);
  } else {
    domainMin = finite.length ? Math.min(...finite) : 0;
    domainMax = finite.length ? Math.max(...finite) : 1;
  }
  const yAt = v => h - pad.b - ((v - domainMin) / ((domainMax - domainMin) || 1)) * (h - pad.t - pad.b);

  let svgParts = "";
  if (spec.kind === "diverging") {
    const zeroY = yAt(0);
    svgParts += '<line x1="' + pad.l + '" y1="' + zeroY + '" x2="' + (w - pad.r) + '" y2="' + zeroY +
      '" stroke="var(--border)" stroke-dasharray="3,3" />';
  }
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (v == null) continue;
    const x1 = xAt(cumKm[i]), x2 = xAt(cumKm[i + 1]);
    const y1 = yAt(v), y2 = yAt(v);
    svgParts += '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 +
      '" stroke="' + spec.color(d, i) + '" stroke-width="2" stroke-linecap="round" data-i="' + i + '" />';
  }
  svg.setAttribute("viewBox", "0 0 " + w + " " + h);
  svg.innerHTML = svgParts + '<g id="profileGuide" style="display:none"><line x1="0" y1="0" x2="0" y2="' + h +
    '" stroke="#e8b04b" stroke-width="1" /></g>';

  svg.onpointermove = (ev) => {
    const rect = svg.getBoundingClientRect();
    const x = (ev.clientX - rect.left) / rect.width * w;
    const km = Math.max(0, Math.min(totalKm, (x - pad.l) / (w - pad.l - pad.r) * totalKm));
    let idx = 0, best = Infinity;
    for (let i = 0; i < cumKm.length; i++) { const dd = Math.abs(cumKm[i] - km); if (dd < best) { best = dd; idx = i; } }
    setHighlight(Math.max(0, Math.min(values.length - 1, idx)));
  };
  svg.onpointerleave = () => setHighlight(null);
}
function updateProfileGuide(idx) {
  const svg = document.getElementById("profileSvg");
  const guide = document.getElementById("profileGuide");
  if (!guide || !state.current) return;
  if (idx == null) { guide.style.display = "none"; return; }
  const d = state.current;
  const w = svg.clientWidth || 600;
  const totalKm = d.cumKm[d.cumKm.length - 1] || 1;
  const km = d.cumKm[idx + 1] != null ? d.cumKm[idx + 1] : 0;
  const x = 4 + (km / totalKm) * (w - 8);
  guide.querySelector("line").setAttribute("x1", x);
  guide.querySelector("line").setAttribute("x2", x);
  guide.style.display = "block";
}

/* -- sidebar / ride list ---------------------------------------------------- */
function highlightSidebar(startMs) {
  document.querySelectorAll(".ride-item").forEach(el => el.classList.toggle("selected", Number(el.dataset.start) === startMs));
}
function renderRideList() {
  const modeSel = document.getElementById("modeFilter");
  const modes = [...new Set(state.rides.map(r => r.mode).filter(Boolean))];
  if (modeSel.options.length <= 1) modes.forEach(m => modeSel.add(new Option(fmtMode(m), m)));

  const q = document.getElementById("rideSearch").value.trim().toLowerCase();
  const modeFilter = modeSel.value;
  const list = document.getElementById("rideList");
  if (!state.rides.length) { list.innerHTML = '<div class="empty"><b>No rides yet</b>Once a ride syncs it will show up here.</div>'; return; }
  const rows = state.rides.filter(r => {
    if (modeFilter && r.mode !== modeFilter) return false;
    if (!q) return true;
    const hay = (fmtDate(r.startMs) + " " + (r.mode || "")).toLowerCase();
    return hay.includes(q);
  });
  if (!rows.length) { list.innerHTML = '<div class="empty">No rides match that filter.</div>'; return; }
  list.innerHTML = rows.map(r => {
    const headline = (r.mode === "MOTO" && r.maxLeanDeg != null) ? r.maxLeanDeg.toFixed(0) + "° lean" : fmtSpeed(r.topSpeedKmh);
    return '<div class="ride-item" data-start="' + r.startMs + '">' +
      '<div class="line1"><span>' + fmtDate(r.startMs) + "</span><span>" + headline + "</span></div>" +
      '<div class="line2"><span>' + fmtMode(r.mode) + " · " + fmtKm((r.distanceKm || 0) * 1000) + "</span><span>" +
      fmtDuration(r.endMs - r.startMs) + "</span></div></div>";
  }).join("");
  list.querySelectorAll(".ride-item").forEach(el => el.addEventListener("click", () => {
    selectRide(Number(el.dataset.start));
    document.getElementById("sidebar").classList.remove("open");
  }));
  highlightSidebar(state.startMs);
}

async function initMapTab() {
  ensureMap();
  try {
    await loadRides();
  } catch (e) {
    document.getElementById("rideList").innerHTML = '<div class="banner error">' + errorMessage(e) + "</div>";
    return;
  }
  renderRideList();
  if (!state.rides.length) { showEmptyMap("No rides yet."); return; }
  const target = state.startMs && state.ridesByStart.has(state.startMs) ? state.startMs : state.rides[0].startMs;
  selectRide(target);
}

/* ==========================================================================
 * Heat tab
 * ========================================================================== */
let heatMap = null, heatLayer = null, lineLayer = null, heatMode = "heat";
function ensureHeatMap() {
  if (heatMap) return;
  heatMap = L.map("heatmap", { zoomControl: true });
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap",
  }).addTo(heatMap);
  heatMap.setView([50.85, 4.35], 9);
}
async function initHeatTab() {
  ensureHeatMap();
  const container = document.getElementById("heatmap");
  try {
    const traces = await loadTraces();
    if (!traces.length) {
      let el = document.getElementById("heatEmpty");
      if (!el) {
        el = document.createElement("div");
        el.id = "heatEmpty"; el.className = "empty";
        el.style.cssText = "position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(20,23,15,.85);z-index:500;";
        container.style.position = "relative";
        container.appendChild(el);
      }
      el.innerHTML = "<div><b>No traces yet</b>Ride with the app syncing and they'll show up here.</div>";
      return;
    }
    const allPoints = [];
    for (const line of traces) for (const p of line) allPoints.push(p);
    heatLayer = L.heatLayer(allPoints, { radius: 14, blur: 18, maxZoom: 15 });
    lineLayer = L.layerGroup(traces.map(line => L.polyline(line, { color: "#e8b04b", weight: 1, opacity: .35 })));
    applyHeatMode();
    heatMap.fitBounds(L.latLngBounds(allPoints), { padding: [20, 20] });
  } catch (e) {
    container.innerHTML = '<div class="banner error">' + errorMessage(e) + "</div>";
  }
}
function applyHeatMode() {
  if (!heatLayer) return;
  if (heatMode === "heat") {
    if (lineLayer && heatMap.hasLayer(lineLayer)) heatMap.removeLayer(lineLayer);
    if (!heatMap.hasLayer(heatLayer)) heatLayer.addTo(heatMap);
  } else {
    if (heatMap.hasLayer(heatLayer)) heatMap.removeLayer(heatLayer);
    if (lineLayer && !heatMap.hasLayer(lineLayer)) lineLayer.addTo(heatMap);
  }
  document.getElementById("heatModeHeat").classList.toggle("active", heatMode === "heat");
  document.getElementById("heatModeLines").classList.toggle("active", heatMode === "lines");
}

/* ==========================================================================
 * General tab — lifetime numbers, all charts hand-rolled inline SVG per the
 * dataviz skill: sequential magnitude for the month bars and calendar,
 * categorical (validated) for the mode split.
 * ========================================================================== */
const MODE_COLORS = { CAR: "#3987e5", MOTO: "#d95926" }; // validated categorical slots 1/2 against this panel's surface
const MODE_FALLBACK = "#199e70"; // slot 3, for anything that isn't CAR/MOTO

function svgEl(tag, attrs) {
  const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const k in attrs) el.setAttribute(k, attrs[k]);
  return el;
}

function renderStatCards(stats) {
  const cards = [
    ["Total distance", fmtKm(stats.stats.totalDistanceMeters)],
    ["Top speed", fmtSpeed(stats.stats.topSpeedKmh)],
    ["Longest ride", fmtKm(stats.stats.longestTripMeters)],
    ["Max lean", fmtLean(stats.stats.maxLeanDeg)],
    ["Municipalities", fmtInt(stats.stats.municipalitiesVisited)],
    ["Best coverage", fmtPct(stats.stats.bestCoveragePercent)],
    ["Rides", fmtInt(stats.rideCount)],
  ];
  return '<div class="card-grid">' + cards.map(([l, v]) =>
    '<div class="stat-card"><div class="label">' + l + '</div><div class="value">' + v + "</div></div>").join("") + "</div>";
}

function monthBarChart(rides) {
  const byMonth = new Map();
  for (const r of rides) {
    const d = new Date(r.startMs);
    const key = d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
    byMonth.set(key, (byMonth.get(key) || 0) + (r.distanceKm || 0));
  }
  const keys = [...byMonth.keys()].sort().slice(-12); // last 12 months with any riding
  if (!keys.length) return '<div class="empty">No rides yet.</div>';
  const max = Math.max(...keys.map(k => byMonth.get(k)));
  const w = Math.max(360, keys.length * 46), h = 140, pad = { l: 6, r: 6, t: 10, b: 22 };
  const bw = (w - pad.l - pad.r) / keys.length;
  let bars = "";
  keys.forEach((k, i) => {
    const v = byMonth.get(k);
    const bh = max > 0 ? (v / max) * (h - pad.t - pad.b) : 0;
    const x = pad.l + i * bw, y = h - pad.b - bh;
    const label = new Date(k + "-02").toLocaleDateString([], { month: "short" });
    bars += '<rect x="' + (x + 3) + '" y="' + y + '" width="' + (bw - 6) + '" height="' + Math.max(bh, 1) +
      '" rx="3" fill="' + seqColor(max > 0 ? v / max : 0, 214) + '"><title>' + k + ": " + v.toFixed(1) + " km</title></rect>";
    bars += '<text class="axis-label" x="' + (x + bw / 2) + '" y="' + (h - 6) + '" text-anchor="middle">' + label + "</text>";
  });
  return '<div class="chart-wrap"><svg viewBox="0 0 ' + w + " " + h + '" width="' + w + '" height="' + h + '">' + bars + "</svg></div>";
}

function modeSplitChart(rides) {
  if (!rides.length) return '<div class="empty">No rides yet.</div>';
  const byMode = new Map();
  for (const r of rides) byMode.set(r.mode || "?", (byMode.get(r.mode || "?") || 0) + 1);
  const total = rides.length;
  const entries = [...byMode.entries()].sort((a, b) => b[1] - a[1]);
  // Flex percentages rather than an SVG stretched with preserveAspectRatio
  // ="none": that scale is non-uniform, so it squashes the rounded corners
  // into ellipses at any width but the viewBox's own.
  let segs = "", legend = "";
  entries.forEach(([mode, count]) => {
    const color = MODE_COLORS[mode] || MODE_FALLBACK;
    const pct = (count / total) * 100;
    segs += '<div style="flex:0 0 ' + pct + '%;background:' + color + '" title="' +
      fmtMode(mode) + ": " + count + '"></div>';
    legend += '<span style="display:inline-flex;align-items:center;gap:5px;margin-right:14px;font-size:12px;color:var(--muted)">' +
      '<span style="width:10px;height:10px;border-radius:2px;background:' + color + ';display:inline-block"></span>' +
      fmtMode(mode) + " " + Math.round(pct) + "%</span>";
  });
  return '<div style="display:flex;gap:2px;height:26px;border-radius:4px;overflow:hidden">' + segs +
    "</div><div style=\"margin-top:8px\">" + legend + "</div>";
}

function recordsSection(rides) {
  if (!rides.length) return '<div class="empty">No rides yet.</div>';
  const by = (key) => rides.reduce((best, r) => (r[key] != null && (!best || r[key] > best[key]) ? r : best), null);
  const recs = [
    ["Fastest", by("topSpeedKmh"), r => fmtSpeed(r.topSpeedKmh)],
    ["Longest", by("distanceKm"), r => fmtKm((r.distanceKm || 0) * 1000)],
    ["Deepest lean", by("maxLeanDeg"), r => fmtLean(r.maxLeanDeg)],
    ["Highest g", by("maxGForce"), r => fmtG(r.maxGForce)],
  ].filter(([, r]) => r);
  return '<div class="records-grid">' + recs.map(([label, r, fmt]) =>
    '<button class="record-card" data-start="' + r.startMs + '"><div class="label">' + label + '</div>' +
    '<div class="value">' + fmt(r) + '</div><div class="when">' + fmtDate(r.startMs) + "</div></button>").join("") + "</div>";
}

let tableSort = { key: "startMs", dir: -1 };
function ridesTable(rides) {
  const cols = [
    ["startMs", "Date", r => fmtDate(r.startMs)],
    ["mode", "Mode", r => fmtMode(r.mode)],
    ["distanceKm", "Distance", r => fmtKm((r.distanceKm || 0) * 1000)],
    ["duration", "Duration", r => fmtDuration(r.endMs - r.startMs)],
    ["topSpeedKmh", "Top speed", r => fmtSpeed(r.topSpeedKmh)],
    ["maxLeanDeg", "Max lean", r => fmtLean(r.maxLeanDeg)],
    ["maxGForce", "Max g", r => fmtG(r.maxGForce)],
    ["pointCount", "Points", r => fmtInt(r.pointCount)],
  ];
  const sortVal = (r) => tableSort.key === "duration" ? (r.endMs - r.startMs) : r[tableSort.key];
  const sorted = [...rides].sort((a, b) => {
    const av = sortVal(a), bv = sortVal(b);
    if (av == null) return 1; if (bv == null) return -1;
    return av < bv ? -tableSort.dir : av > bv ? tableSort.dir : 0;
  });
  const head = cols.map(([key, label]) => '<th data-key="' + key + '" class="' +
    (tableSort.key === key ? "sorted" + (tableSort.dir < 0 ? " desc" : "") : "") + '">' + label + "</th>").join("");
  const body = sorted.map(r => '<tr data-start="' + r.startMs + '">' +
    cols.map(([, , fmt]) => "<td>" + fmt(r) + "</td>").join("") + "</tr>").join("");
  return '<div class="table-wrap"><table><thead><tr>' + head + "</tr></thead><tbody>" + body + "</tbody></table></div>";
}

// Both sides of this chart have to agree on what "a day" is. toISOString()
// answers in UTC, which for anywhere east of Greenwich puts a local-midnight
// cursor on the *previous* date — the grid would sit one day off its own
// tooltips, and today's ride would never appear. Local calendar fields both
// for the ride's day and for the cell's.
function dayKey(d) {
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") +
    "-" + String(d.getDate()).padStart(2, "0");
}
function calendarHeatmap(rides) {
  const byDay = new Map();
  for (const r of rides) {
    const key = dayKey(new Date(r.startMs));
    byDay.set(key, (byDay.get(key) || 0) + (r.distanceKm || 0));
  }
  const days = 371; // 53 full weeks
  const end = new Date(); end.setHours(0, 0, 0, 0);
  const start = new Date(end); start.setDate(start.getDate() - days + 1);
  // align to the start of its week (Monday) so columns line up
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
  const cell = 11, gap = 2;
  // Walk actual calendar days rather than dividing a millisecond span: a DST
  // change makes a week 167 or 169 hours long, and the grid would gain or lose
  // a column twice a year. The width falls out of how many cells were drawn.
  let rects = "", i = 0;
  for (let cursor = new Date(start); cursor <= end; cursor.setDate(cursor.getDate() + 1), i++) {
    const key = dayKey(cursor);
    const km = byDay.get(key) || 0;
    const bucket = km <= 0 ? 0 : km < 20 ? 1 : km < 60 ? 2 : km < 150 ? 3 : 4;
    const fill = bucket === 0 ? "var(--panel-2)" : seqColor(bucket / 4, 214);
    const col = Math.floor(i / 7), row = i % 7;
    rects += '<rect class="cal-cell" x="' + (col * (cell + gap)) + '" y="' + (row * (cell + gap)) +
      '" width="' + cell + '" height="' + cell + '" rx="2" fill="' + fill + '"><title>' + key +
      (km ? ": " + km.toFixed(1) + " km" : ": no ride") + "</title></rect>";
  }
  const w = Math.ceil(i / 7) * (cell + gap), h = 7 * (cell + gap);
  return '<div class="chart-wrap"><svg class="cal-grid" viewBox="0 0 ' + w + " " + h + '" width="100%" height="' +
    (h + 4) + '" preserveAspectRatio="xMinYMin meet">' + rects + "</svg></div>";
}

function renderTableSection(rides) {
  // Re-renders just the table on a sort click, rebinding both the header
  // (for the next sort) and the rows (for row -> Map-tab navigation) —
  // simpler than diffing, and 118 rows is nothing to redraw whole.
  const container = document.getElementById("ridesTableContainer");
  container.innerHTML = ridesTable(rides);
  container.querySelectorAll("th[data-key]").forEach(th => th.addEventListener("click", () => {
    const key = th.dataset.key;
    tableSort.dir = (tableSort.key === key) ? -tableSort.dir : -1;
    tableSort.key = key;
    renderTableSection(rides);
  }));
  container.querySelectorAll("tr[data-start]").forEach(el => el.addEventListener("click", () => goToRide(Number(el.dataset.start))));
}

async function initGeneralTab() {
  const body = document.getElementById("generalBody");
  try {
    const [stats, rides] = await Promise.all([loadStats(), loadRides()]);
    body.innerHTML =
      '<div><div class="section-title">Lifetime</div>' + renderStatCards(stats) + "</div>" +
      '<div><div class="section-title">Distance per month</div><div class="panel">' + monthBarChart(rides) + "</div></div>" +
      '<div><div class="section-title">Mode split</div><div class="panel">' + modeSplitChart(rides) + "</div></div>" +
      '<div><div class="section-title">Records</div>' + recordsSection(rides) + "</div>" +
      '<div><div class="section-title">Riding days</div><div class="panel">' + calendarHeatmap(rides) + "</div></div>" +
      '<div><div class="section-title">All rides</div><div id="ridesTableContainer"></div></div>';
    body.querySelectorAll(".record-card").forEach(el => el.addEventListener("click", () => goToRide(Number(el.dataset.start))));
    renderTableSection(rides);
  } catch (e) {
    body.innerHTML = '<div class="banner error">' + errorMessage(e) + "</div>";
  }
}
function goToRide(startMs) {
  // Set the selection *before* showing the tab: on the first visit showTab
  // runs initMapTab, which picks a ride of its own from state.startMs. Left
  // stale, that fires a second, competing selectRide for the newest ride.
  state.startMs = startMs;
  state.tab = "map"; setUrl("map", startMs);
  showTab("map");
  selectRide(startMs);
}

/* ==========================================================================
 * Badges tab
 * ========================================================================== */
const KIND_FMT = {
  "Distance": v => (v / 1000).toFixed(1) + " km",
  "Top speed": v => Math.round(v) + " km/h",
  "Single ride": v => (v / 1000).toFixed(1) + " km",
  "Places": v => Math.round(v) + " municipalities",
  "Coverage": v => Math.round(v) + "%",
};
function fmtBadgeValue(kind, v) { return (KIND_FMT[kind] || (x => String(x)))(v); }

async function initBadgesTab() {
  const body = document.getElementById("badgesBody");
  try {
    const stats = await loadStats();
    const cat = stats.badgeCatalogue || [];
    const earnedCount = cat.filter(b => b.earnedMs).length;
    const byKind = new Map();
    for (const b of cat) { if (!byKind.has(b.kind)) byKind.set(b.kind, []); byKind.get(b.kind).push(b); }

    let html = '<div class="badge-overall"><b>' + earnedCount + " / " + cat.length + "</b><span>badges earned</span></div>";
    for (const [kind, tiers] of byKind) {
      tiers.sort((a, b) => a.threshold - b.threshold);
      html += '<div class="badge-kind"><h3>' + kind + "</h3>";
      html += tiers.map(t => {
        const earned = !!t.earnedMs;
        return '<div class="badge-tier ' + (earned ? "earned" : "locked") + '">' +
          '<div class="dot">' + (earned ? "✓" : "—") + "</div>" +
          '<div class="body"><div class="title">' + t.title + '</div>' +
          '<div class="meta">' + (earned
            ? "Earned " + fmtDate(t.earnedMs)
            : fmtBadgeValue(kind, t.value) + " / " + fmtBadgeValue(kind, t.threshold)) + "</div>" +
          (earned ? "" : '<div class="progress"><i style="width:' + t.progressPercent + '%"></i></div>') +
          "</div></div>";
      }).join("");
      html += "</div>";
    }
    const timeline = cat.filter(b => b.earnedMs).sort((a, b) => a.earnedMs - b.earnedMs);
    if (timeline.length) {
      html += '<div><div class="section-title">Timeline</div>' +
        timeline.map(b => '<div class="timeline-item"><b>' + fmtDate(b.earnedMs) + "</b> — " + b.title + " (" + b.kind + ")</div>").join("") +
        "</div>";
    }
    body.innerHTML = html;
  } catch (e) {
    body.innerHTML = '<div class="banner error">' + errorMessage(e) + "</div>";
  }
}

/* ==========================================================================
 * tabs / init
 * ========================================================================== */
const TAB_INIT = { map: initMapTab, heat: initHeatTab, general: initGeneralTab, badges: initBadgesTab };
const loadedTabs = new Set();
function showTab(name) {
  state.tab = name;
  document.querySelectorAll(".tab-btn").forEach(b => b.classList.toggle("active", b.dataset.tab === name));
  document.querySelectorAll(".tab-panel").forEach(p => p.classList.toggle("active", p.id === "tab-" + name));
  if (!loadedTabs.has(name)) { loadedTabs.add(name); TAB_INIT[name](); }
  else if (name === "map" && map) map.invalidateSize();
  else if (name === "heat" && heatMap) heatMap.invalidateSize();
  setUrl(name, state.startMs);
}

function init() {
  if (!API_KEY) {
    document.body.innerHTML = '<div class="banner error" style="margin:20px">No API key in the URL. Check the API key ' +
      "in this page's link (it should end with <code>?key=...</code>).</div>";
    return;
  }
  const { tab, start } = currentUrlState();
  state.tab = tab;
  if (start) state.startMs = start;

  document.querySelectorAll(".tab-btn").forEach(b => b.addEventListener("click", () => showTab(b.dataset.tab)));
  window.addEventListener("hashchange", () => { const s = currentUrlState(); showTab(s.tab); });
  document.getElementById("overlaySelect").addEventListener("change", onOverlayChange);
  document.getElementById("modeFilter").addEventListener("change", renderRideList);
  document.getElementById("rideSearch").addEventListener("input", renderRideList);
  document.getElementById("sidebarToggle").addEventListener("click", () => document.getElementById("sidebar").classList.toggle("open"));
  document.getElementById("heatModeHeat").addEventListener("click", () => { heatMode = "heat"; applyHeatMode(); });
  document.getElementById("heatModeLines").addEventListener("click", () => { heatMode = "lines"; applyHeatMode(); });
  document.getElementById("heatFit").addEventListener("click", () => {
    if (heatMap && state.traces && state.traces.length) {
      const pts = [];
      for (const line of state.traces) for (const p of line) pts.push(p);
      heatMap.fitBounds(L.latLngBounds(pts), { padding: [20, 20] });
    }
  });

  showTab(state.tab);
}
init();
</script>
"""


def ha_dashboard_html(params):
    """The dashboard shell: no DB reads here at all. Every number on the page
    comes from a client-side fetch that carries its own key (read from
    location.search, never templated), so the only thing this handler threads
    through is which ride to preselect. Casting through int() is the XSS
    guard — whatever lands in ?start= becomes a plain integer or 0, never
    markup. 0 means "no ride requested"; the page's own JS resolves that to
    the newest ride via /ha/rides, same as the old server-side fallback did.
    """
    try:
        start = int((params.get("start") or ["0"])[0])
    except ValueError:
        start = 0
    return DASH_HTML.replace("__START_MS__", str(start))

# --------------------------------------------------------------------------
# manager dashboard
#
# A fourth credential path, deliberately separate from the other three: a
# browser cookie, held only by users with is_admin, proved with the same
# account password the app uses. It can create and destroy accounts — it
# cannot read anyone's rides. Nothing in this section touches trips, traces
# or track_points beyond counting rows.

ADMIN_COOKIE = "mr_admin"
ADMIN_USER_ACTION_RE = re.compile(r"^/admin/api/user/(\d+)/([a-z]+)$")


def admin_session(headers):
    """The signed-in admin's session row, or 401/403.

    Admin is re-checked on every request rather than trusted from login time,
    so revoking someone's is_admin takes effect on their next click instead of
    whenever their cookie happens to expire.
    """
    morsel = SimpleCookie(headers.get("Cookie", "")).get(ADMIN_COOKIE)
    if morsel is None:
        raise HttpError(401, "sign in to the dashboard")
    shash = token_hash(morsel.value)
    row = db().execute(
        "SELECT s.*, u.username, u.is_admin FROM admin_sessions s"
        " JOIN users u ON u.id = s.user_id WHERE s.session_hash = ?",
        (shash,),
    ).fetchone()
    if row is None:
        raise HttpError(401, "sign in to the dashboard")
    if now_ms() - row["last_used_ms"] > ADMIN_SESSION_IDLE_MS or not row["is_admin"]:
        drop_admin_session(shash)
        raise HttpError(401, "session expired; sign in again")
    if now_ms() - row["last_used_ms"] > TOKEN_TOUCH_INTERVAL_MS:
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                conn.execute(
                    "UPDATE admin_sessions SET last_used_ms = ? WHERE session_hash = ?",
                    (now_ms(), shash),
                )
    return row


def drop_admin_session(session_hash):
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "DELETE FROM admin_sessions WHERE session_hash = ?", (session_hash,)
            )


def admin_write(headers):
    """Guards every mutating admin call.

    SameSite=Strict already stops a cross-site form post from carrying the
    cookie; this header check is the belt to that braces, and costs one line
    in the page's fetch wrapper. A tab left open past a password change fails
    here rather than silently acting as a stale admin.
    """
    row = admin_session(headers)
    sent = headers.get("X-CSRF-Token", "")
    if not sent or not hmac.compare_digest(sent, row["csrf"]):
        raise HttpError(403, "stale dashboard tab — reload the page")
    return row


def do_admin_login(body, ip):
    """Returns (payload, raw_session_cookie). Non-admins get the same answer as
    a wrong password: whether an account can reach the dashboard is not
    something the login form should confirm."""
    rate_limit(ip)
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    user = find_user(username)
    if user is None:
        hash_password(password, salt=b"\x00" * 16)  # same work, no timing tell
        note_failure(ip)
        raise HttpError(401, "wrong username or password")
    _, digest, _ = hash_password(password, bytes(user["pw_salt"]), user["iterations"])
    if not hmac.compare_digest(digest, bytes(user["pw_hash"])) or not user["is_admin"]:
        note_failure(ip)
        raise HttpError(401, "wrong username or password")
    raw = secrets.token_urlsafe(TOKEN_BYTES)
    csrf = secrets.token_urlsafe(16)
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO admin_sessions (session_hash, user_id, csrf, created_ms,"
                " last_used_ms) VALUES (?, ?, ?, ?, ?)",
                (token_hash(raw), user["id"], csrf, now_ms(), now_ms()),
            )
    return {"username": user["username"], "csrf": csrf}, raw


def do_admin_logout(headers):
    morsel = SimpleCookie(headers.get("Cookie", "")).get(ADMIN_COOKIE)
    if morsel is not None:
        drop_admin_session(token_hash(morsel.value))
    return {}


def admin_user(uid):
    row = db().execute("SELECT * FROM users WHERE id = ?", (uid,)).fetchone()
    if row is None:
        raise HttpError(404, "no such user")
    return row


def admin_count(sql, args=()):
    return db().execute(sql, args).fetchone()[0]


def email_taken(email, except_id):
    if not email:
        return False
    row = db().execute(
        "SELECT id FROM users WHERE email = ? COLLATE NOCASE AND id != ?",
        (email, except_id or 0),
    ).fetchone()
    return row is not None


def invite_status(row):
    if row["used_ms"]:
        return "used"
    if row["expires_ms"] is not None and row["expires_ms"] < now_ms():
        return "expired"
    return "live"


def admin_overview(session):
    """Account metadata and row counts only — no trip, trace or place content
    is read here, and there is no endpoint that would let an admin read it."""
    users = []
    for u in db().execute(
        "SELECT u.*,"
        " (SELECT COUNT(*) FROM trips t WHERE t.user_id = u.id) AS trips,"
        " (SELECT COUNT(*) FROM traces r WHERE r.user_id = u.id) AS traces,"
        " (SELECT COUNT(*) FROM tokens k WHERE k.user_id = u.id) AS tokens,"
        " (SELECT COUNT(*) FROM api_keys a WHERE a.user_id = u.id) AS api_keys,"
        " (SELECT MAX(k.last_used_ms) FROM tokens k WHERE k.user_id = u.id) AS last_seen_ms"
        " FROM users u ORDER BY u.username COLLATE NOCASE"
    ):
        stats = json.loads(u["stats_json"] or "{}")
        users.append(
            {
                "id": u["id"],
                "username": u["username"],
                "email": u["email"] or "",
                "isAdmin": bool(u["is_admin"]),
                "isSelf": u["id"] == session["user_id"],
                "shareFog": bool(u["share_fog"]),
                "createdMs": u["created_ms"],
                "lastSeenMs": u["last_seen_ms"] or 0,
                "trips": u["trips"],
                "traces": u["traces"],
                "sessions": u["tokens"],
                "apiKeys": u["api_keys"],
                "distanceKm": round(stats.get("totalDistanceMeters", 0) / 1000.0, 1),
            }
        )
    invites = [
        {
            "code": r["code"],
            "label": r["label"],
            "email": r["email"] or "",
            "createdMs": r["created_ms"],
            "expiresMs": r["expires_ms"] or 0,
            "usedMs": r["used_ms"] or 0,
            "usedBy": r["used_by"] or "",
            "status": invite_status(r),
        }
        for r in db().execute(
            "SELECT * FROM invites ORDER BY created_ms DESC LIMIT 200"
        )
    ]
    return {
        "admin": session["username"],
        # The page keeps the CSRF token in memory only, so a reload onto a live
        # cookie has to get it back from somewhere. A cross-origin page cannot
        # read this response, which is exactly what makes the token worth
        # something.
        "csrf": session["csrf"],
        "users": users,
        "invites": invites,
        "mail": mail_configured(),
        "mailFrom": SMTP_FROM if mail_configured() else "",
        "registration": "open" if REGISTRATION_OPEN and not INVITE_CODE else "invite only",
        "sharedCode": bool(INVITE_CODE),
        "resetMinutes": RESET_TTL_MS // 60000,
    }


def do_admin_invite_create(body):
    email = clean_email(body.get("email"))
    label = str(body.get("label", "")).strip()[:80]
    days = body.get("days", INVITE_DEFAULT_DAYS)
    try:
        days = int(days)
    except (TypeError, ValueError):
        days = INVITE_DEFAULT_DAYS
    code, expires = create_invite(label, email, days)
    mailed = bool(body.get("send")) and send_invite_mail(email, code, expires)
    return {"code": code, "mailed": mailed}


def do_admin_invite_revoke(body):
    code = str(body.get("code", "")).strip()
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            cur = conn.execute("DELETE FROM invites WHERE code = ?", (code,))
    if not cur.rowcount:
        raise HttpError(404, "no such invite")
    return {}


def admin_count_admins():
    return admin_count("SELECT COUNT(*) FROM users WHERE is_admin = 1")


def do_admin_user_action(session, uid, action, body):
    user = admin_user(uid)
    is_self = user["id"] == session["user_id"]

    if action == "email":
        email = clean_email(body.get("email"))
        if email_taken(email, user["id"]):
            raise HttpError(409, "another account already uses that address")
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                conn.execute(
                    "UPDATE users SET email = ? WHERE id = ?", (email, user["id"])
                )
        return {"email": email or ""}

    if action == "password":
        # A blank field means "make one up": handing over a generated password
        # beats an admin inventing a weak one, and it is shown exactly once.
        password = str(body.get("password", "")) or secrets.token_urlsafe(9)
        set_password(user["id"], password)
        return {"password": password}

    if action == "reset":
        if not user["email"]:
            raise HttpError(400, "that account has no email address on file")
        raw = create_reset(user["id"])
        return {
            "mailed": send_reset_mail(user["username"], user["email"], raw),
            "link": reset_link(raw),
        }

    if action == "admin":
        wanted = 1 if body.get("admin") else 0
        # Losing the last admin means the dashboard can only come back through
        # the CLI on the box itself. Refuse rather than explain that later.
        if not wanted and user["is_admin"] and admin_count_admins() <= 1:
            raise HttpError(400, "that is the only admin left")
        if not wanted and is_self:
            raise HttpError(400, "use another admin to take your own access away")
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                conn.execute(
                    "UPDATE users SET is_admin = ? WHERE id = ?", (wanted, user["id"])
                )
                if not wanted:
                    conn.execute(
                        "DELETE FROM admin_sessions WHERE user_id = ?", (user["id"],)
                    )
        return {"isAdmin": bool(wanted)}

    if action == "revoke":
        what = str(body.get("what", "tokens"))
        table = "api_keys" if what == "keys" else "tokens"
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                cur = conn.execute(
                    "DELETE FROM %s WHERE user_id = ?" % table, (user["id"],)
                )
        if table == "tokens":
            evict_user_everywhere(user["id"])
        return {"revoked": cur.rowcount}

    if action == "apikey":
        raw = secrets.token_urlsafe(TOKEN_BYTES)
        label = str(body.get("label", "")).strip()[:60] or "dashboard"
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                conn.execute(
                    "INSERT INTO api_keys (key_hash, user_id, label, created_ms)"
                    " VALUES (?, ?, ?, ?)",
                    (token_hash(raw), user["id"], label, now_ms()),
                )
        return {"key": raw}

    if action == "delete":
        if is_self:
            raise HttpError(400, "you cannot delete the account you signed in with")
        if user["is_admin"] and admin_count_admins() <= 1:
            raise HttpError(400, "that is the only admin left")
        with _write_lock:
            conn = db()
            with conn:  # commits on success, rolls back on exception
                # Every table that holds this user's rows references users(id)
                # ON DELETE CASCADE, and foreign_keys is on for the connection,
                # so this one statement takes the trips, traces, points, places,
                # friendships, group membership, circle places/fixes, keys
                # and sessions with it.
                conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
        evict_user_everywhere(user["id"])
        return {}

    raise HttpError(404, "not found")


ADMIN_HTML = r"""<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Detour — manager</title>
<style>
  /* Dark is the design's home; light is a full second theme rather than an
     inverted afterthought, because half of managing a server happens on a
     laptop in daylight.

     Two text colours, not three: --text for anything you read, --muted for
     anything you glance at. A third tier only made the page harder to scan. */
  :root {
    color-scheme: dark;
    --bg: #12150e; --surface: #191d13; --surface-2: #232819; --raise: #2c3222;
    --border: #363d29; --border-soft: #292f20;
    --text: #f0ecdf; --muted: #b6ae99;
    --accent: #edb851; --accent-ink: #241c02; --accent-soft: rgba(237,184,81,.14);
    --red: #ef6a4e; --red-soft: rgba(239,106,78,.15);
    --green: #43c295; --green-soft: rgba(67,194,149,.15);
    --shadow: 0 12px 34px rgba(0,0,0,.5);
    --radius: 12px;
  }
  @media (prefers-color-scheme: light) {
    :root:not([data-theme="dark"]) { color-scheme: light;
      --bg: #f1eee3; --surface: #fffdf7; --surface-2: #f3f0e4; --raise: #e9e5d4;
      --border: #d8d2bd; --border-soft: #e6e2d2;
      --text: #1f2218; --muted: #5d5a4a;
      --accent: #8a5f08; --accent-ink: #fffaf0; --accent-soft: rgba(138,95,8,.11);
      --red: #a8331b; --red-soft: rgba(168,51,27,.10);
      --green: #106c4e; --green-soft: rgba(16,108,78,.10);
      --shadow: 0 12px 34px rgba(60,54,30,.13);
    }
  }
  :root[data-theme="light"] { color-scheme: light;
    --bg: #f1eee3; --surface: #fffdf7; --surface-2: #f3f0e4; --raise: #e9e5d4;
    --border: #d8d2bd; --border-soft: #e6e2d2;
    --text: #1f2218; --muted: #5d5a4a;
    --accent: #8a5f08; --accent-ink: #fffaf0; --accent-soft: rgba(138,95,8,.11);
    --red: #a8331b; --red-soft: rgba(168,51,27,.10);
    --green: #106c4e; --green-soft: rgba(16,108,78,.10);
    --shadow: 0 12px 34px rgba(60,54,30,.13);
  }

  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text); font-size: 15px; line-height: 1.5;
    /* Helvetica and Arial sit before the generic on purpose. With
       privacy.fingerprintingProtection on, Firefox hides non-standard families
       AND stops honouring the sans-serif generic, falling back to the user's
       default font — which, if they have set that to a monospace, renders this
       page in it: spaced-out digits, doubled word gaps. Arial is on the
       allowlist, so naming it keeps a proportional face in that case, and
       changes nothing anywhere else. */
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter,
      Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  button, select, input { font: inherit; color: inherit; }
  a { color: var(--accent); }
  h1, h2, h3 { margin: 0; font-weight: 650; letter-spacing: -.01em; }
  .muted { color: var(--muted); }
  .small { font-size: 13px; }
  .grow { flex: 1 1 auto; }
  .hidden { display: none !important; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .nowrap { flex-wrap: nowrap; }
  svg.i { width: 17px; height: 17px; flex: none; fill: none; stroke: currentColor;
    stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }

  /* --- controls ------------------------------------------------------- */
  input, select {
    background: var(--bg); border: 1px solid var(--border); color: var(--text);
    border-radius: 9px; padding: 8px 11px; min-height: 40px; min-width: 0;
    transition: border-color .12s, box-shadow .12s;
  }
  input::placeholder { color: var(--muted); opacity: .8; }
  input:focus, select:focus, button:focus-visible {
    outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft);
  }
  button {
    display: inline-flex; align-items: center; gap: 7px; justify-content: center;
    background: var(--surface-2); border: 1px solid var(--border); color: var(--text);
    border-radius: 9px; padding: 8px 13px; min-height: 40px; cursor: pointer;
    font-weight: 550; white-space: nowrap;
    transition: background .12s, border-color .12s, color .12s;
  }
  button:hover:not(:disabled) { background: var(--raise); border-color: var(--muted); }
  button.primary { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
  button.primary:hover:not(:disabled) { filter: brightness(1.07); background: var(--accent);
    border-color: var(--accent); }
  button.ghost { background: transparent; border-color: transparent; color: var(--muted); }
  button.ghost:hover:not(:disabled) { background: var(--surface-2); color: var(--text); }
  button.danger { color: var(--red); }
  button.danger:hover:not(:disabled) { background: var(--red-soft); border-color: var(--red); }
  button.icon { padding: 0; width: 40px; }
  button.tiny { min-height: 32px; padding: 4px 10px; font-size: 13px; border-radius: 8px; }
  button.tiny.icon { width: 32px; }
  button:disabled { opacity: .42; cursor: default; }
  label.check { display: inline-flex; align-items: center; gap: 7px; }
  label.check input { min-height: 0; width: 17px; height: 17px; accent-color: var(--accent); }
  .field { display: flex; flex-direction: column; gap: 6px; }
  .field > span { font-size: 13px; color: var(--muted); font-weight: 550; }

  /* --- shell ---------------------------------------------------------- */
  .topbar {
    position: sticky; top: 0; z-index: 30;
    display: flex; align-items: center; gap: 12px; padding: 0 18px; height: 56px;
    background: var(--surface); border-bottom: 1px solid var(--border);
  }
  .brand { display: flex; align-items: center; gap: 9px; font-weight: 700;
    letter-spacing: -.02em; white-space: nowrap; }
  .brand .dot { width: 26px; height: 26px; border-radius: 8px; display: grid; place-items: center;
    background: var(--accent); color: var(--accent-ink); }
  .brand .dot svg.i { width: 16px; height: 16px; stroke-width: 2.1; }
  .whoami { display: flex; align-items: center; gap: 8px; padding: 4px 6px 4px 5px;
    border: 1px solid var(--border); border-radius: 99px; background: var(--surface-2); }
  .avatar { width: 26px; height: 26px; border-radius: 99px; display: grid; place-items: center;
    background: var(--accent-soft); color: var(--accent); font-size: 13px; font-weight: 700;
    flex: none; }

  /* The sidebar is a rail against the left edge of the window, and only the
     content column is centred and capped. Centring the pair left a wide screen
     with a band of dead space on the left and the app adrift in the middle. */
  .shell { display: grid; grid-template-columns: 210px minmax(0, 1fr); align-items: stretch; }
  .side { position: sticky; top: 56px; align-self: start; padding: 14px 10px;
    display: flex; flex-direction: column; gap: 3px;
    background: var(--surface); border-right: 1px solid var(--border);
    min-height: calc(100dvh - 56px); }
  .navlink {
    display: flex; align-items: center; gap: 10px; padding: 9px 11px; border-radius: 9px;
    color: var(--muted); cursor: pointer; border: 1px solid transparent; font-weight: 550;
    background: transparent; width: 100%; justify-content: flex-start; min-height: 0;
  }
  .navlink:hover { background: var(--surface-2); color: var(--text); }
  .navlink.on { background: var(--accent-soft); color: var(--accent); font-weight: 650; }
  .navlink .count { margin-left: auto; font-size: 13px; color: var(--muted);
    font-variant-numeric: tabular-nums; }
  .navlink.on .count { color: var(--accent); }
  main { padding: 20px 24px 72px; min-width: 0; width: 100%; max-width: 1060px;
    margin: 0 auto; }
  .pagehead { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 16px; }
  .pagehead h1 { font-size: 22px; }
  .pagehead p { margin: 1px 0 0; color: var(--muted); font-size: 13.5px; }

  /* --- cards ---------------------------------------------------------- */
  .card { background: var(--surface); border: 1px solid var(--border);
    border-radius: var(--radius); margin-bottom: 16px; overflow: hidden; }
  .card > header { display: flex; align-items: center; gap: 10px 12px; padding: 12px 16px;
    border-bottom: 1px solid var(--border-soft); flex-wrap: wrap; }
  /* A narrow screen scrolls the table sideways inside its card; the page body
     itself must never scroll horizontally. */
  .tscroll { overflow-x: auto; }
  .card > header h2 { font-size: 15px; }
  .card .pad { padding: 16px; }
  .card > header + .pad { padding-top: 14px; }
  .cards2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(330px, 1fr)); gap: 16px;
    align-items: start; }
  .cards2 .card { margin: 0; }

  .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px;
    margin-bottom: 16px; }
  .tile { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
    padding: 13px 15px; }
  .tile .k { color: var(--muted); font-size: 13px; font-weight: 550; }
  .tile .v { font-size: 27px; font-weight: 680; letter-spacing: -.025em; margin-top: 3px;
    font-variant-numeric: tabular-nums; line-height: 1.15; }
  .tile .v small { font-size: 15px; font-weight: 600; color: var(--muted); margin-left: 2px; }
  .tile .s { color: var(--muted); font-size: 12.5px; margin-top: 3px; }

  /* --- lists & tables --------------------------------------------------- */
  table { width: 100%; border-collapse: collapse; }
  th, td { text-align: left; padding: 11px 14px; vertical-align: middle; }
  tbody td { border-top: 1px solid var(--border-soft); }
  thead th { padding-top: 9px; padding-bottom: 9px; color: var(--muted); font-weight: 600;
    font-size: 13px; white-space: nowrap; border-bottom: 1px solid var(--border-soft); }
  thead th.sortable { cursor: pointer; user-select: none; }
  thead th.sortable:hover { color: var(--text); }
  thead th .arrow { visibility: hidden; margin-left: 2px; }
  thead th.on { color: var(--accent); }
  thead th.on .arrow { visibility: visible; }
  tbody tr.pick { cursor: pointer; }
  tbody tr.pick:hover { background: var(--surface-2); }
  tbody tr.pick:hover .chev { color: var(--accent); transform: translateX(2px); }
  .chev { color: var(--muted); opacity: .7; transition: transform .12s, color .12s; }
  td.num { font-variant-numeric: tabular-nums; white-space: nowrap; }
  td.right, th.right { text-align: right; }
  .search { flex: 0 1 240px; min-width: 130px; min-height: 34px; padding: 5px 11px; }
  .empty { padding: 40px 16px; text-align: center; color: var(--muted); }
  .empty svg.i { width: 28px; height: 28px; opacity: .55; margin-bottom: 8px; }
  .who { display: flex; align-items: center; gap: 10px; flex-wrap: nowrap; }
  .who b { font-weight: 650; }
  .meta { color: var(--muted); font-size: 13px; }

  .pill { display: inline-flex; align-items: center; padding: 1px 9px; border-radius: 99px;
    font-size: 12px; font-weight: 600; border: 1px solid var(--border); color: var(--muted);
    white-space: nowrap; }
  .pill.admin { border-color: var(--accent); color: var(--accent); background: var(--accent-soft); }
  .pill.live, .pill.ok { border-color: var(--green); color: var(--green); background: var(--green-soft); }
  .pill.expired, .pill.bad { border-color: var(--red); color: var(--red); background: var(--red-soft); }
  .pill.used { background: var(--surface-2); }
  .chips { display: flex; gap: 6px; flex-wrap: wrap; }
  .chip { padding: 5px 12px; border-radius: 99px; border: 1px solid var(--border);
    background: transparent; color: var(--muted); font-size: 13px; min-height: 0; }
  .chip.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }

  code, .code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    background: var(--bg); border: 1px solid var(--border); border-radius: 7px;
    padding: 3px 8px; font-size: 13.5px; word-break: break-all; }
  .secret { display: flex; gap: 10px; align-items: center; background: var(--bg);
    border: 1px dashed var(--accent); border-radius: 10px; padding: 12px; }
  .secret .code { border: none; background: transparent; padding: 0; flex: 1 1 auto;
    font-size: 15px; color: var(--accent); }

  /* A note list that reads as prose with a status colour, rather than a column
     of meaningless "note" badges. */
  .notes { display: flex; flex-direction: column; }
  .note { display: flex; gap: 10px; padding: 11px 0; border-top: 1px solid var(--border-soft);
    font-size: 13.5px; }
  .note:first-child { border-top: none; padding-top: 2px; }
  .note .bullet { width: 7px; height: 7px; border-radius: 99px; background: var(--muted);
    flex: none; margin-top: 7px; }
  .note.warn .bullet { background: var(--red); }
  .note.good { color: var(--muted); }
  .note.good .bullet { background: var(--green); }

  .kv { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px 10px; }
  .kv .k { font-size: 12.5px; color: var(--muted); }
  .kv .v { font-size: 15px; font-weight: 650; font-variant-numeric: tabular-nums; }
  .facts { display: flex; flex-direction: column; }
  .fact { display: flex; gap: 12px; align-items: center; padding: 10px 0;
    border-top: 1px solid var(--border-soft); font-size: 13.5px; }
  .fact:first-child { border-top: none; }
  .fact .k { color: var(--muted); flex: 1 1 auto; }

  /* --- drawer --------------------------------------------------------- */
  .scrim { position: fixed; inset: 0; background: rgba(10,12,7,.55); z-index: 40;
    opacity: 0; pointer-events: none; transition: opacity .16s; }
  .scrim.on { opacity: 1; pointer-events: auto; }
  .drawer {
    position: fixed; top: 0; right: 0; bottom: 0; width: min(440px, 100%); z-index: 50;
    background: var(--surface); border-left: 1px solid var(--border); box-shadow: var(--shadow);
    transform: translateX(102%); transition: transform .2s cubic-bezier(.3,.7,.3,1);
    display: flex; flex-direction: column;
  }
  .drawer.on { transform: none; }
  .drawer > header { display: flex; align-items: center; gap: 11px; padding: 14px 16px;
    border-bottom: 1px solid var(--border); }
  .drawer .body { overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 20px; }
  .sect { display: flex; flex-direction: column; gap: 10px; }
  .sect > h3 { font-size: 13px; color: var(--muted); font-weight: 600; }
  .sect.danger { border-top: 1px solid var(--border); padding-top: 16px; }

  /* --- dialogs & toasts ------------------------------------------------ */
  dialog { border: 1px solid var(--border); background: var(--surface); color: var(--text);
    border-radius: var(--radius); padding: 0; width: min(440px, calc(100vw - 32px));
    box-shadow: var(--shadow); }
  dialog::backdrop { background: rgba(10,12,7,.55); }
  dialog .dbody { padding: 20px; display: flex; flex-direction: column; gap: 14px; }
  dialog h2 { font-size: 17px; }
  dialog .dfoot { display: flex; gap: 8px; justify-content: flex-end; padding: 13px 20px;
    border-top: 1px solid var(--border); background: var(--surface-2);
    border-radius: 0 0 var(--radius) var(--radius); }
  #toasts { position: fixed; right: 18px; bottom: 18px; z-index: 60; display: flex;
    flex-direction: column; gap: 8px; align-items: flex-end;
    max-width: min(400px, calc(100vw - 36px)); }
  .toast { display: flex; gap: 10px; align-items: flex-start; background: var(--surface);
    border: 1px solid var(--border); border-left: 3px solid var(--muted); border-radius: 10px;
    padding: 11px 13px; box-shadow: var(--shadow); font-size: 13.5px; animation: pop .18s ease-out; }
  .toast.ok { border-left-color: var(--green); }
  .toast.ok svg.i { color: var(--green); }
  .toast.error { border-left-color: var(--red); }
  .toast.error svg.i { color: var(--red); }
  @keyframes pop { from { opacity: 0; transform: translateY(6px); } }

  /* --- login ---------------------------------------------------------- */
  .login { max-width: 370px; margin: 11vh auto; }
  .login .card { padding: 24px; }
  .login .brand { justify-content: center; font-size: 17px; }
  .login .field { margin-bottom: 12px; }
  .login input { width: 100%; }

  @media (max-width: 900px) {
    .shell { grid-template-columns: minmax(0, 1fr); gap: 0; }
    .side { position: sticky; top: 56px; z-index: 20; flex-direction: row; overflow-x: auto;
      padding: 8px 12px; background: var(--bg); border-right: none; min-height: 0;
      border-bottom: 1px solid var(--border); gap: 6px; scrollbar-width: none; }
    .side::-webkit-scrollbar { display: none; }
    .navlink { width: auto; white-space: nowrap; }
    .navlink .count { margin-left: 5px; }
    main { padding: 16px 12px 72px; }
    #toasts { left: 16px; right: 16px; bottom: 12px; max-width: none; align-items: stretch; }
  }
  @media (max-width: 680px) {
    .t-opt { display: none; }
    .card > header h2 { font-size: 14px; }
    .topbar { padding: 0 12px; gap: 8px; }
    .wordmark, #freshness { display: none; }
    th, td { padding: 11px 10px; }
  }
  @media (max-width: 480px) { .t-opt2 { display: none; } }
</style>

<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>
  <symbol id="i-compass" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="m15.5 8.5-2 5-5 2 2-5z"/></symbol>
  <symbol id="i-grid" viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></symbol>
  <symbol id="i-users" viewBox="0 0 24 24"><path d="M16 19v-1.5A3.5 3.5 0 0 0 12.5 14h-5A3.5 3.5 0 0 0 4 17.5V19"/><circle cx="10" cy="8" r="3.2"/><path d="M20 19v-1.5a3.5 3.5 0 0 0-2.6-3.4M15.4 5.2a3.2 3.2 0 0 1 0 6"/></symbol>
  <symbol id="i-ticket" viewBox="0 0 24 24"><path d="M4 9V7a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2a2.5 2.5 0 0 0 0 5v2a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-2a2.5 2.5 0 0 0 0-5Z"/><path d="M13 6v2M13 11v2M13 16v2"/></symbol>
  <symbol id="i-route" viewBox="0 0 24 24"><circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="6" r="2.5"/><path d="M8.5 18h6a3 3 0 0 0 0-6h-5a3 3 0 0 1 0-6h5"/></symbol>
  <symbol id="i-gauge" viewBox="0 0 24 24"><path d="M4 18a9 9 0 1 1 16 0"/><path d="m12 14 4-4"/></symbol>
  <symbol id="i-pulse" viewBox="0 0 24 24"><path d="M3 12h4l2.5-6 4 12L16 12h5"/></symbol>
  <symbol id="i-check" viewBox="0 0 24 24"><path d="m4 12.5 5 5L20 6.5"/></symbol>
  <symbol id="i-alert" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5M12 16.2v.1"/></symbol>
  <symbol id="i-copy" viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></symbol>
  <symbol id="i-x" viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18"/></symbol>
  <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M20 11a8 8 0 0 0-14-4.5L4 9"/><path d="M4 5v4h4"/><path d="M4 13a8 8 0 0 0 14 4.5L20 15"/><path d="M20 19v-4h-4"/></symbol>
  <symbol id="i-out" viewBox="0 0 24 24"><path d="M14 8V5a1 1 0 0 0-1-1H6a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-3"/><path d="M10 12h10m0 0-3-3m3 3-3 3"/></symbol>
  <symbol id="i-sun" viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4"/></symbol>
  <symbol id="i-moon" viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z"/></symbol>
  <symbol id="i-mail" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3.5 7 8.5 6 8.5-6"/></symbol>
  <symbol id="i-key" viewBox="0 0 24 24"><circle cx="8" cy="12" r="3.5"/><path d="M11.5 12H21m-3 0v3m-3-3v2"/></symbol>
  <symbol id="i-shield" viewBox="0 0 24 24"><path d="M12 3.5 5 6v5.5c0 4 3 7.3 7 9 4-1.7 7-5 7-9V6z"/></symbol>
  <symbol id="i-trash" viewBox="0 0 24 24"><path d="M4 7h16M9.5 7V5h5v2M6.5 7l.8 12a1 1 0 0 0 1 1h7.4a1 1 0 0 0 1-1l.8-12"/></symbol>
  <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></symbol>
  <symbol id="i-lock" viewBox="0 0 24 24"><rect x="4.5" y="10" width="15" height="10" rx="2"/><path d="M8 10V7.5a4 4 0 0 1 8 0V10"/></symbol>
  <symbol id="i-server" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5v.1M7 16.5v.1"/></symbol>
</defs></svg>

<div class="topbar hidden" id="bar">
  <span class="brand"><span class="dot"><svg class="i"><use href="#i-compass"/></svg></span><span class="wordmark">Detour</span></span>
  <span class="pill" id="regpill" title="How new accounts are created"></span>
  <span class="grow"></span>
  <span class="small muted" id="freshness"></span>
  <button class="ghost icon" id="theme" title="Light / dark"><svg class="i"><use href="#i-moon"/></svg></button>
  <button class="ghost icon" id="refresh" title="Refresh (r)"><svg class="i"><use href="#i-refresh"/></svg></button>
  <span class="whoami">
    <span class="avatar" id="initial"></span>
    <span class="small" id="who"></span>
    <button class="ghost icon tiny" id="logout" title="Sign out"><svg class="i"><use href="#i-out"/></svg></button>
  </span>
</div>

<div class="login hidden" id="login">
  <div class="card">
    <div class="brand"><span class="dot"><svg class="i"><use href="#i-compass"/></svg></span>Detour</div>
    <p class="muted small" style="text-align:center;margin:0 0 16px">Manager sign-in</p>
    <label class="field"><span>Username</span>
      <input id="lu" autocomplete="username" autocapitalize="none" spellcheck="false"></label>
    <label class="field"><span>Password</span>
      <input id="lp" type="password" autocomplete="current-password"></label>
    <button class="primary" id="lgo" style="width:100%"><svg class="i"><use href="#i-lock"/></svg>Sign in</button>
    <p class="muted small" style="margin:14px 0 0">Your normal account password. The account needs
      admin rights — grant the first one on the server with
      <code>sync_server.py --make-admin NAME</code>.</p>
  </div>
</div>

<div class="shell hidden" id="app">
  <nav class="side">
    <button class="navlink" data-view="overview"><svg class="i"><use href="#i-grid"/></svg>Overview</button>
    <button class="navlink" data-view="users"><svg class="i"><use href="#i-users"/></svg>Users<span class="count" id="n-users"></span></button>
    <button class="navlink" data-view="invites"><svg class="i"><use href="#i-ticket"/></svg>Invites<span class="count" id="n-invites"></span></button>
    <button class="navlink" data-view="server"><svg class="i"><use href="#i-server"/></svg>Server</button>
  </nav>
  <main id="view"></main>
</div>

<div class="scrim" id="scrim"></div>
<aside class="drawer" id="drawer" aria-label="User details"></aside>
<div id="toasts"></div>
<dialog id="dlg"></dialog>

<script>
const $ = (id) => document.getElementById(id);

// Every cell is built with textContent, never innerHTML: usernames, labels and
// invite notes are user input, and this page is the one place an admin reads
// them all in one list. The only markup built from a string is icon(), whose
// argument is always a literal from this file.
function el(tag, props, ...kids) {
  const node = document.createElement(tag);
  Object.assign(node, props || {});
  for (const kid of kids) {
    if (kid == null || kid === false) continue;
    node.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return node;
}
function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "i");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
}

const state = {
  data: null,       // last /admin/api/overview payload
  view: "overview",
  q: "",
  sort: "username",
  desc: false,
  inviteFilter: "all",
  openUser: null,   // id of the user whose drawer is up
  fetchedAt: 0,
};
let csrf = "";

// --- formatting ---------------------------------------------------------

const DAY = 86400000;
const fmtDate = (ms) => ms ? new Date(ms).toLocaleDateString(undefined,
  { day: "numeric", month: "short", year: "numeric" }) : "never";
const fmtDateTime = (ms) => ms ? new Date(ms).toLocaleString(undefined,
  { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "never";
function fmtAgo(ms) {
  if (!ms) return "never";
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 90) return "just now";
  if (s < 5400) return Math.round(s / 60) + " min ago";
  if (s < 129600) return Math.round(s / 3600) + " h ago";
  if (s < 30 * DAY / 1000) return Math.round(s / 86400) + " days ago";
  return fmtDate(ms);
}
const fmtNum = (n) => n.toLocaleString(undefined, { maximumFractionDigits: 0 });
// "1 session" / "3 sessions" — these labels are read on every button in the
// drawer, and "(s)" reads like a form nobody finished.
const plural = (n, one, many) => fmtNum(n) + " " + (n === 1 ? one : (many || one + "s"));
function fmtKm(km) {
  if (km >= 10000) return fmtNum(Math.round(km));
  return km.toLocaleString(undefined, { maximumFractionDigits: km < 100 ? 1 : 0 });
}

// --- api ----------------------------------------------------------------

async function api(path, body) {
  const res = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: body === undefined ? {} : { "Content-Type": "application/json", "X-CSRF-Token": csrf },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || ("HTTP " + res.status));
  return data;
}
const post = (uid, what, body) => api("/admin/api/user/" + uid + "/" + what, body || {});

// --- toasts -------------------------------------------------------------

function toast(text, kind) {
  const box = el("div", { className: "toast " + (kind || "ok") },
    icon(kind === "error" ? "alert" : "check"), el("span", { className: "grow" }, text));
  $("toasts").append(box);
  setTimeout(() => box.remove(), kind === "error" ? 7000 : 4000);
}

function copyButton(text, label) {
  const b = el("button", { className: "tiny" + (label ? "" : " icon"), title: "Copy" },
    icon("copy"), label || null);
  b.onclick = (e) => {
    e.stopPropagation();
    navigator.clipboard.writeText(text).then(
      () => { b.textContent = ""; b.append(icon("check"), label ? "Copied" : ""); },
      () => toast("Could not reach the clipboard — select and copy by hand.", "error"));
  };
  return b;
}

// --- dialogs ------------------------------------------------------------

// One dialog element, rebuilt per call. Resolves with the field values, or
// null when it is dismissed — so every caller reads as `if (!r) return;`.
function dialog({ title, lede, fields, confirm, danger, body }) {
  const dlg = $("dlg");
  dlg.textContent = "";
  const inputs = {};
  const form = el("form", { method: "dialog" });
  const dbody = el("div", { className: "dbody" }, el("h2", {}, title),
    lede ? el("p", { className: "muted small", style: "margin:0" }, lede) : null);
  if (body) dbody.append(body);
  for (const f of fields || []) {
    const input = el("input", {
      type: f.type || "text", placeholder: f.placeholder || "", value: f.value || "",
      autocomplete: "off", spellcheck: false,
    });
    inputs[f.name] = input;
    dbody.append(el("label", { className: "field" }, el("span", {}, f.label), input));
  }
  const cancel = el("button", { type: "button" }, "Cancel");
  const ok = el("button", { className: danger ? "danger" : "primary" }, confirm || "OK");
  form.append(dbody, el("div", { className: "dfoot" }, cancel, ok));
  dlg.append(form);

  return new Promise((resolve) => {
    let done = false;
    // close() fires onclose, which calls finish again: guard on both sides or
    // the dismiss path bounces between the two forever.
    const finish = (val) => {
      if (done) return;
      done = true;
      resolve(val);
      if (dlg.open) dlg.close();
    };
    cancel.onclick = () => finish(null);
    dlg.onclose = () => finish(null);
    form.onsubmit = (e) => {
      e.preventDefault();
      const out = {};
      for (const k in inputs) out[k] = inputs[k].value;
      finish(out);
    };
    dlg.showModal();
    const first = Object.values(inputs)[0];
    if (first) first.focus(); else ok.focus();
  });
}

const confirmDialog = (title, lede, confirm, danger) =>
  dialog({ title, lede, confirm, danger }).then((r) => r !== null);

// A generated password, an API key and a reset link are all shown exactly
// once. Give them a dialog of their own so they cannot scroll away.
function showSecret(title, lede, secret) {
  const box = el("div", { className: "secret" },
    el("span", { className: "code" }, secret), copyButton(secret, "Copy"));
  return dialog({ title, lede, body: box, confirm: "Done" });
}

// --- login --------------------------------------------------------------

async function signIn() {
  const btn = $("lgo");
  btn.disabled = true;
  try {
    const r = await api("/admin/login", { username: $("lu").value.trim(), password: $("lp").value });
    csrf = r.csrf;
    $("lp").value = "";
    await load();
  } catch (e) {
    toast(e.message, "error");
    $("lp").select();
  }
  btn.disabled = false;
}

// --- data ---------------------------------------------------------------

async function load(silent) {
  try {
    state.data = await api("/admin/api/overview");
    state.fetchedAt = Date.now();
  } catch (e) {
    state.data = null;
    $("app").classList.add("hidden");
    $("bar").classList.add("hidden");
    $("login").classList.remove("hidden");
    closeDrawer();
    if (silent) toast("Signed out — the session expired.", "error");
    $("lu").focus();
    return;
  }
  csrf = state.data.csrf;
  $("login").classList.add("hidden");
  $("app").classList.remove("hidden");
  $("bar").classList.remove("hidden");
  render();
}

const users = () => (state.data ? state.data.users : []);
const invites = () => (state.data ? state.data.invites : []);
const userById = (id) => users().find((u) => u.id === id) || null;
const adminCount = () => users().filter((u) => u.isAdmin).length;

// --- chrome -------------------------------------------------------------

function renderChrome() {
  const d = state.data;
  $("who").textContent = d.admin;
  $("initial").textContent = (d.admin[0] || "?").toUpperCase();
  const open = d.registration === "open";
  const reg = $("regpill");
  reg.textContent = open ? "registration open" : (d.sharedCode ? "shared invite code" : "invite only");
  reg.className = "pill " + (open ? "bad" : "");
  $("n-users").textContent = d.users.length;
  $("n-invites").textContent = d.invites.filter((i) => i.status === "live").length || "";
  for (const b of document.querySelectorAll(".navlink")) {
    b.classList.toggle("on", b.dataset.view === state.view);
  }
  tickFreshness();
}

function tickFreshness() {
  $("freshness").textContent = state.fetchedAt ? "updated " + fmtAgo(state.fetchedAt) : "";
}

function pagehead(title, sub, ...actions) {
  return el("div", { className: "pagehead" },
    el("div", { className: "grow" }, el("h1", {}, title), el("p", {}, sub)),
    ...actions);
}

// --- overview -----------------------------------------------------------

function card(title, iconName, ...tools) {
  const c = el("div", { className: "card" });
  if (title) {
    c.append(el("header", {}, iconName ? icon(iconName) : null,
      el("h2", { className: "grow" }, title), ...tools));
  }
  return c;
}
const pad = (...kids) => el("div", { className: "pad" }, ...kids);

function tile(label, value, unit, sub) {
  return el("div", { className: "tile" },
    el("div", { className: "k" }, label),
    el("div", { className: "v" }, value, unit ? el("small", {}, unit) : null),
    el("div", { className: "s" }, sub));
}

function personLine(u, right) {
  const line = el("div", { className: "row nowrap",
    style: "padding:10px 0;border-top:1px solid var(--border-soft)" },
    el("span", { className: "avatar" }, (u.username[0] || "?").toUpperCase()),
    el("div", { className: "grow", style: "min-width:0" },
      el("div", { className: "row nowrap", style: "gap:7px" }, el("b", {}, u.username),
        u.isAdmin ? el("span", { className: "pill admin" }, "manager") : null),
      el("div", { className: "meta" }, u.trips + " trips · " + fmtKm(u.distanceKm) + " km")),
    el("span", { className: "meta" }, right));
  line.style.cursor = "pointer";
  line.onclick = () => openDrawer(u.id);
  return line;
}

function viewOverview() {
  const us = users(), inv = invites();
  const trips = us.reduce((a, u) => a + u.trips, 0);
  const km = us.reduce((a, u) => a + u.distanceKm, 0);
  const active = us.filter((u) => u.lastSeenMs > Date.now() - 7 * DAY).length;
  const live = inv.filter((i) => i.status === "live").length;
  const noEmail = us.filter((u) => !u.email).length;

  const root = el("div", {},
    pagehead("Overview", "Everything this server is holding, at a glance."));

  root.append(el("div", { className: "tiles" },
    tile("Accounts", fmtNum(us.length), null, adminCount() + " with manager rights"),
    tile("Active", fmtNum(active), null, "synced this week"),
    tile("Trips", fmtNum(trips), null,
      fmtNum(us.reduce((a, u) => a + u.traces, 0)) + " traces stored"),
    tile("Distance", fmtKm(km), " km",
      us.length ? fmtKm(km / us.length) + " km per account" : "nothing driven yet"),
    tile("Live invites", fmtNum(live), null,
      inv.filter((i) => i.status === "used").length + " already claimed")));

  // Recently active, which is the one list an admin actually scans daily.
  const recent = us.slice().sort((a, b) => b.lastSeenMs - a.lastSeenMs).slice(0, 6);
  const recentCard = card("Recently active", "pulse");
  const recentBody = pad();
  if (!recent.length) recentBody.append(el("p", { className: "muted", style: "margin:0" }, "No accounts yet."));
  for (const u of recent) recentBody.append(personLine(u, fmtAgo(u.lastSeenMs)));
  if (recent.length) recentBody.firstChild.style.borderTop = "none";
  recentCard.append(recentBody);

  // Things worth doing something about, worst first. Silent when there is
  // nothing to say — a permanent panel of "all good" is just noise.
  const flags = [];
  if (state.data.registration === "open") flags.push(["Registration is open: anyone who can reach this server can create an account.", "warn"]);
  if (!state.data.mail) flags.push(["No SMTP relay configured, so invites and reset links can only be passed on by hand.", "warn"]);
  if (adminCount() === 1) flags.push(["One manager account. If it loses its password, only the server CLI can let you back in.", ""]);
  if (noEmail) flags.push([noEmail + " account" + (noEmail > 1 ? "s have" : " has") + " no email on file and cannot be mailed a reset link.", ""]);
  const expired = inv.filter((i) => i.status === "expired").length;
  if (expired) flags.push([expired + " invite" + (expired > 1 ? "s" : "") + " expired unused.", ""]);

  const notes = card("Worth knowing", "shield");
  const noteList = el("div", { className: "notes" });
  if (!flags.length) {
    noteList.append(el("div", { className: "note good" }, el("span", { className: "bullet" }),
      el("span", {}, "Nothing needs attention: mail works, registration is closed and every "
        + "account has an address on file.")));
  }
  for (const [text, kind] of flags) {
    noteList.append(el("div", { className: "note " + kind },
      el("span", { className: "bullet" }), el("span", {}, text)));
  }
  notes.append(pad(noteList));

  // A compact echo of the Server tab, so the right column carries its weight
  // and the two settings that decide who gets in are visible from the start.
  const setup = card("Setup", "server");
  const jump = el("button", { className: "tiny" }, "Server settings");
  jump.onclick = () => go("server");
  setup.append(pad(el("div", { className: "facts" },
    el("div", { className: "fact" }, el("span", { className: "k" }, "Registration"),
      el("span", { className: "pill " + (state.data.registration === "open" ? "bad" : "ok") },
        state.data.registration)),
    el("div", { className: "fact" }, el("span", { className: "k" }, "Mail"),
      el("span", { className: "pill " + (state.data.mail ? "ok" : "bad") },
        state.data.mail ? "configured" : "off")),
    el("div", { className: "fact" }, el("span", { className: "k" }, "Managers"),
      el("span", {}, String(adminCount())))),
    el("div", { className: "row", style: "margin-top:12px;justify-content:flex-end" }, jump)));

  root.append(el("div", { className: "cards2" }, recentCard,
    el("div", {}, notes, setup)));
  return root;
}

// --- users --------------------------------------------------------------

const SORTS = {
  username: (a, b) => a.username.localeCompare(b.username),
  lastSeenMs: (a, b) => a.lastSeenMs - b.lastSeenMs,
  createdMs: (a, b) => a.createdMs - b.createdMs,
  trips: (a, b) => a.trips - b.trips,
  distanceKm: (a, b) => a.distanceKm - b.distanceKm,
};

function sortHeader(label, key, extra) {
  const th = el("th", { className: "sortable " + (extra || "") + (state.sort === key ? " on" : "") },
    label, el("span", { className: "arrow" }, state.desc ? " ↓" : " ↑"));
  th.onclick = () => {
    if (state.sort === key) state.desc = !state.desc;
    else { state.sort = key; state.desc = key !== "username"; }
    render();
  };
  return th;
}

function viewUsers() {
  const q = state.q.toLowerCase();
  const list = users()
    .filter((u) => !q || u.username.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))
    .sort(SORTS[state.sort]);
  if (state.desc) list.reverse();

  // The search box lives in the card header, right above the rows it filters,
  // rather than floating off in the page title.
  const search = el("input", { placeholder: "Search name or email", value: state.q, type: "search",
    id: "usearch", className: "search" });
  // Re-rendering replaces this very input, so the caret is put back afterwards.
  search.oninput = () => {
    state.q = search.value;
    render(true);
    const again = $("usearch");
    if (again) { again.focus(); again.setSelectionRange(again.value.length, again.value.length); }
  };

  const root = el("div", {}, pagehead("Users", plural(users().length, "account") + " on this server"));
  const c = card(state.q ? list.length + " of " + users().length : "All accounts", "users", search);

  if (!list.length) {
    c.append(el("div", { className: "empty" }, icon("users"),
      el("div", {}, state.q ? "No account matches “" + state.q + "”." : "No accounts yet.")));
  } else {
    const tbody = el("tbody", {});
    for (const u of list) tbody.append(userRow(u));
    c.append(el("div", { className: "tscroll" }, el("table", {},
      el("thead", {}, el("tr", {},
        sortHeader("User", "username"),
        el("th", { className: "t-opt" }, "Email"),
        sortHeader("Trips", "trips", "right t-opt"),
        sortHeader("Distance", "distanceKm", "right t-opt2"),
        sortHeader("Last sync", "lastSeenMs", "right"),
        el("th", { style: "width:24px" }, ""))),
      tbody)));
  }
  root.append(c);
  return root;
}

function userRow(u) {
  const tr = el("tr", { className: "pick" },
    el("td", {},
      el("div", { className: "who" },
        el("span", { className: "avatar" }, (u.username[0] || "?").toUpperCase()),
        el("div", { style: "min-width:0" },
          el("div", { className: "row nowrap", style: "gap:7px" }, el("b", {}, u.username),
            u.isAdmin ? el("span", { className: "pill admin" }, "manager") : null,
            u.isSelf ? el("span", { className: "pill" }, "you") : null),
          el("div", { className: "meta t-opt" }, "joined " + fmtDate(u.createdMs))))),
    el("td", { className: "t-opt meta" }, u.email || "—"),
    el("td", { className: "num right t-opt" }, fmtNum(u.trips)),
    el("td", { className: "num right t-opt2" }, fmtKm(u.distanceKm) + " km"),
    el("td", { className: "num right meta" }, fmtAgo(u.lastSeenMs)),
    el("td", { className: "right" }, el("span", { className: "chev" }, "›")));
  tr.onclick = () => openDrawer(u.id);
  return tr;
}

// --- user drawer --------------------------------------------------------

function openDrawer(id) {
  state.openUser = id;
  renderDrawer();
  $("drawer").classList.add("on");
  $("scrim").classList.add("on");
}
function closeDrawer() {
  state.openUser = null;
  $("drawer").classList.remove("on");
  $("scrim").classList.remove("on");
}

// Wraps a click handler so the button disables itself for the round trip and
// every failure lands in a toast instead of the console.
function action(label, iconName, fn, cls) {
  const b = el("button", { className: cls || "" }, iconName ? icon(iconName) : null, label);
  b.onclick = async () => {
    b.disabled = true;
    try { await fn(); } catch (e) { toast(e.message, "error"); }
    b.disabled = false;
  };
  return b;
}

function renderDrawer() {
  const u = userById(state.openUser);
  const drawer = $("drawer");
  drawer.textContent = "";
  if (!u) { closeDrawer(); return; }

  const close = el("button", { className: "ghost icon", title: "Close" }, icon("x"));
  close.onclick = closeDrawer;
  drawer.append(el("header", {},
    el("span", { className: "avatar", style: "width:34px;height:34px;font-size:15px" },
      (u.username[0] || "?").toUpperCase()),
    el("div", { className: "grow", style: "min-width:0" },
      el("div", { style: "font-size:17px;font-weight:650" }, u.username),
      el("div", { className: "row", style: "gap:5px" },
        u.isAdmin ? el("span", { className: "pill admin" }, "manager") : null,
        u.isSelf ? el("span", { className: "pill" }, "you") : null,
        el("span", { className: "pill " + (u.shareFog ? "ok" : "") },
          u.shareFog ? "shares fog" : "fog private"))),
    close));

  const body = el("div", { className: "body" });
  const stat = (k, v) => el("div", {}, el("div", { className: "k" }, k), el("div", { className: "v" }, v));
  body.append(el("div", { className: "kv" },
    stat("Trips", fmtNum(u.trips)),
    stat("Distance", fmtKm(u.distanceKm) + " km"),
    stat("Traces", fmtNum(u.traces)),
    stat("Sessions", fmtNum(u.sessions)),
    stat("API keys", fmtNum(u.apiKeys)),
    stat("Joined", fmtDate(u.createdMs))));
  body.append(el("div", { className: "meta", style: "margin-top:-10px" },
    "Last sync " + fmtDateTime(u.lastSeenMs)));

  // Email
  const emailInput = el("input", { value: u.email, placeholder: "no address on file", type: "email",
    className: "grow", autocapitalize: "none", spellcheck: false });
  body.append(el("div", { className: "sect" }, el("h3", {}, "Email"),
    el("div", { className: "row", style: "flex-wrap:nowrap" }, emailInput,
      action("Save", null, async () => {
        await post(u.id, "email", { email: emailInput.value.trim() });
        toast("Email saved for " + u.username);
        await load(true);
      }, "primary"))));

  // Password
  const resetBtn = action("Email a reset link", "mail", async () => {
    const r = await post(u.id, "reset");
    if (r.mailed) toast("Reset link mailed to " + u.email + ", valid " + state.data.resetMinutes + " min.");
    else await showSecret("Reset link", "Not mailed. Send this to " + u.username + " yourself — it is valid for "
      + state.data.resetMinutes + " minutes.", r.link);
  });
  resetBtn.disabled = !u.email;
  resetBtn.title = u.email ? "" : "This account has no email address on file.";
  body.append(el("div", { className: "sect" }, el("h3", {}, "Password"),
    el("div", { className: "row" },
      action("Set a password", "lock", async () => {
        const r = await dialog({
          title: "Set a password for " + u.username,
          lede: "Leave it blank and the server generates a strong one. Either way every "
            + "signed-in device of theirs is signed out.",
          fields: [{ name: "password", label: "New password (blank = generate)", type: "text",
            placeholder: "at least 8 characters" }],
          confirm: "Set password",
        });
        if (!r) return;
        if (r.password && r.password.length < 8) { toast("Passwords are at least 8 characters.", "error"); return; }
        const out = await post(u.id, "password", { password: r.password });
        await showSecret("New password for " + u.username,
          "Shown once. They are signed out everywhere and will need this to sign back in.", out.password);
        await load(true);
      }),
      resetBtn)));

  // Access
  const onlyAdmin = u.isAdmin && adminCount() <= 1;
  const adminBtn = action(u.isAdmin ? "Revoke manager rights" : "Make manager", "shield", async () => {
    if (u.isAdmin && !await confirmDialog("Revoke manager rights?",
      u.username + " keeps their account and rides, but loses this dashboard.", "Revoke", true)) return;
    await post(u.id, "admin", { admin: !u.isAdmin });
    toast(u.isAdmin ? "Manager rights revoked from " + u.username : u.username + " is now a manager");
    await load(true);
  });
  adminBtn.disabled = u.isAdmin && (u.isSelf || onlyAdmin);
  adminBtn.title = u.isSelf ? "Use another manager account to take your own access away."
    : (onlyAdmin ? "The only manager left." : "");

  const signOut = action(u.sessions ? "Sign out " + plural(u.sessions, "session") : "No sessions",
    "out", async () => {
    if (!await confirmDialog("Sign out every device?",
      u.username + " will have to sign in again on each phone.", "Sign out", true)) return;
    const r = await post(u.id, "revoke", { what: "tokens" });
    toast("Revoked " + plural(r.revoked, "session") + " for " + u.username);
    await load(true);
  });
  signOut.disabled = !u.sessions;

  const revokeKeys = action(u.apiKeys ? "Revoke " + plural(u.apiKeys, "API key") : "No API keys",
    "key", async () => {
    if (!await confirmDialog("Revoke every API key?",
      "Any dashboard or Home Assistant card using one of " + u.username + "'s keys stops working.", "Revoke", true)) return;
    const r = await post(u.id, "revoke", { what: "keys" });
    toast("Revoked " + plural(r.revoked, "key") + " for " + u.username);
    await load(true);
  });
  revokeKeys.disabled = !u.apiKeys;

  body.append(el("div", { className: "sect" }, el("h3", {}, "Access"),
    el("div", { className: "row" }, adminBtn, signOut, revokeKeys,
      action("New read-only key", "plus", async () => {
        const r = await dialog({
          title: "New read-only API key",
          lede: "For a dashboard or a Home Assistant card. It can read " + u.username
            + "'s rides and nothing else, and it is shown once.",
          fields: [{ name: "label", label: "Label", value: "dashboard" }],
          confirm: "Create key",
        });
        if (!r) return;
        const out = await post(u.id, "apikey", { label: r.label });
        await showSecret("API key for " + u.username, "Copy it now — it is never shown again.", out.key);
        await load(true);
      }))));

  // Danger
  const del = action("Delete account", "trash", async () => {
    const r = await dialog({
      title: "Delete " + u.username + "?",
      lede: "This removes the account and every trip, trace, place and key it owns. "
        + "It cannot be undone. Type the username to confirm.",
      fields: [{ name: "name", label: "Username", placeholder: u.username }],
      confirm: "Delete for good", danger: true,
    });
    if (!r) return;
    if (r.name !== u.username) { toast("That is not the username — nothing was deleted.", "error"); return; }
    await post(u.id, "delete");
    toast("Deleted " + u.username);
    closeDrawer();
    await load(true);
  }, "danger");
  del.disabled = u.isSelf || onlyAdmin;
  del.title = u.isSelf ? "You cannot delete the account you signed in with."
    : (onlyAdmin ? "The only manager left." : "");
  body.append(el("div", { className: "sect danger" }, el("h3", {}, "Danger zone"), del));

  drawer.append(body);
}

// --- invites ------------------------------------------------------------

function viewInvites() {
  const root = el("div", {},
    pagehead("Invites", "One code, one account. Codes are single use."));

  // Every control gets a label above it, so the row shares one baseline
  // instead of one field sitting lower than its neighbours.
  const label = el("input", { placeholder: "a note to yourself" });
  const email = el("input", { placeholder: "optional", type: "email",
    autocapitalize: "none", spellcheck: false });
  const days = el("input", { type: "number", min: "0", value: "14" });
  const send = el("input", { type: "checkbox", disabled: !state.data.mail });

  const make = action("Generate code", "plus", async () => {
    const r = await api("/admin/api/invite/create", {
      label: label.value.trim(), email: email.value.trim(),
      days: Number(days.value || 0), send: send.checked,
    });
    await showSecret("Invite code",
      r.mailed ? "Mailed to " + email.value.trim() + ". Here it is as well." : "Pass this on yourself.",
      r.code);
    label.value = ""; email.value = "";
    await load(true);
  }, "primary");

  const form = el("div", { style: "display:grid;gap:12px;"
    + "grid-template-columns:minmax(150px,1.6fr) minmax(140px,1.3fr) 110px;align-items:end" },
    el("label", { className: "field" }, el("span", {}, "Who is it for"), label),
    el("label", { className: "field" }, el("span", {}, "Email"), email),
    el("label", { className: "field" }, el("span", {}, "Expires in (days)"), days));

  const inviteCard = card("Invite someone", "ticket");
  inviteCard.append(pad(form,
    el("div", { className: "row", style: "margin-top:14px" },
      el("label", { className: "check" }, send,
        el("span", { className: state.data.mail ? "" : "muted" }, "mail it to them")),
      el("span", { className: "grow" }), make),
    el("p", { className: "muted small", style: "margin:12px 0 0" }, state.data.mail
      ? "Mail goes out as " + state.data.mailFrom + ". Reset links last " + state.data.resetMinutes + " minutes."
      : "No SMTP relay configured (SMTP_HOST is unset), so nothing is mailed — codes are shown here to pass on yourself.")));
  root.append(inviteCard);

  const counts = { all: invites().length, live: 0, used: 0, expired: 0 };
  for (const i of invites()) counts[i.status]++;
  const chips = el("div", { className: "chips" });
  for (const key of ["all", "live", "used", "expired"]) {
    const c = el("button", { className: "chip" + (state.inviteFilter === key ? " on" : "") },
      key + " " + counts[key]);
    c.onclick = () => { state.inviteFilter = key; render(); };
    chips.append(c);
  }

  const list = invites().filter((i) => state.inviteFilter === "all" || i.status === state.inviteFilter);
  const c = card("Codes", null, chips);
  if (!list.length) {
    c.append(el("div", { className: "empty" }, icon("ticket"),
      el("div", {}, counts.all ? "No " + state.inviteFilter + " invites." : "No invites yet.")));
  } else {
    const tbody = el("tbody", {});
    for (const inv of list) tbody.append(inviteRow(inv));
    c.append(el("div", { className: "tscroll" }, el("table", {},
      el("thead", {}, el("tr", {},
        el("th", {}, "Code"), el("th", { className: "t-opt" }, "For"),
        el("th", {}, "Status"), el("th", { style: "width:24px" }, ""))),
      tbody)));
  }
  root.append(c);
  return root;
}

function inviteRow(inv) {
  const revoke = action("", "trash", async () => {
    if (!await confirmDialog("Revoke this invite?",
      inv.status === "live" ? "The code stops working immediately." : "Removes it from the list.",
      "Revoke", true)) return;
    await api("/admin/api/invite/revoke", { code: inv.code });
    toast("Invite revoked");
    await load(true);
  }, "tiny icon danger");
  revoke.title = "Revoke";

  const sub = inv.status === "used"
    ? "claimed by " + inv.usedBy + " on " + fmtDate(inv.usedMs)
    : (inv.expiresMs ? (inv.status === "expired" ? "expired " : "expires ") + fmtDate(inv.expiresMs)
      : "never expires");

  return el("tr", {},
    el("td", {}, el("div", { className: "row nowrap", style: "gap:6px" },
      el("span", { className: "code" }, inv.code), copyButton(inv.code))),
    el("td", { className: "t-opt" },
      el("div", {}, inv.label || el("span", { className: "muted" }, "—")),
      inv.email ? el("div", { className: "meta" }, inv.email) : null),
    el("td", {},
      el("div", { className: "row nowrap", style: "gap:8px" },
        el("span", { className: "pill " + inv.status }, inv.status),
        el("span", { className: "meta" }, sub))),
    el("td", { className: "right" }, revoke));
}

// --- server -------------------------------------------------------------

function viewServer() {
  const d = state.data;
  const fact = (k, v, pill) => el("div", { className: "fact" },
    el("span", { className: "k" }, k),
    pill ? el("span", { className: "pill " + pill }, v) : el("span", {}, v));

  const root = el("div", {},
    pagehead("Server", "How this instance is configured. Change these with environment variables and a restart."));

  const open = d.registration === "open";
  const accounts = card("Accounts", "shield");
  accounts.append(pad(el("div", { className: "facts" },
    fact("Registration", d.registration, open ? "bad" : "ok"),
    fact("Shared invite code", d.sharedCode ? "set (INVITE_CODE)" : "not set", d.sharedCode ? "" : "ok"),
    fact("Managers", String(adminCount())),
    fact("Reset link lifetime", d.resetMinutes + " minutes")),
    el("p", { className: "muted small", style: "margin:12px 0 0" },
      open ? "REGISTRATION_OPEN is on: anyone who can reach this server can create an account. Turn it off once your accounts exist."
           : "New accounts need an invite code from this dashboard.")));

  const mail = card("Mail", "mail");
  mail.append(pad(el("div", { className: "facts" },
    fact("SMTP relay", d.mail ? "configured" : "off", d.mail ? "ok" : "bad"),
    fact("Sends as", d.mailFrom || "—")),
    el("p", { className: "muted small", style: "margin:12px 0 0" }, d.mail
      ? "Invites and password resets can be mailed straight from here."
      : "Set SMTP_HOST, SMTP_USER, SMTP_PASS and SMTP_FROM to mail invites and reset links. Without it everything still works — codes and links are shown on screen to pass on yourself.")));

  root.append(el("div", { className: "cards2" }, accounts, mail));

  const privacy = card("What this dashboard can see", "lock");
  privacy.append(pad(el("p", { className: "muted", style: "margin:0" },
    "Account metadata and row counts only. There is no endpoint here that reads a trip, "
    + "a trace or a place — a manager can delete a user's data but never look at it. "
    + "Sessions here are a separate cookie from the phones' sync tokens, and idle out on their own.")));
  root.append(privacy);
  return root;
}

// --- router -------------------------------------------------------------

const VIEWS = { overview: viewOverview, users: viewUsers, invites: viewInvites, server: viewServer };

function render(keepScroll) {
  if (!state.data) return;
  renderChrome();
  const main = $("view");
  const y = keepScroll ? window.scrollY : 0;
  main.textContent = "";
  main.append((VIEWS[state.view] || viewOverview)());
  if (keepScroll) window.scrollTo(0, y);
  if (state.openUser !== null) renderDrawer();
}

function go(view) {
  if (!VIEWS[view]) view = "overview";
  state.view = view;
  if (location.hash.slice(1) !== view) location.hash = view;
  render();
}

for (const b of document.querySelectorAll(".navlink")) {
  b.onclick = () => go(b.dataset.view);
}
window.addEventListener("hashchange", () => go(location.hash.slice(1) || "overview"));

// --- theme --------------------------------------------------------------

function applyTheme(mode) {
  if (mode) document.documentElement.setAttribute("data-theme", mode);
  else document.documentElement.removeAttribute("data-theme");
  const dark = mode ? mode === "dark"
    : !window.matchMedia("(prefers-color-scheme: light)").matches;
  const btn = $("theme");
  btn.textContent = "";
  btn.append(icon(dark ? "moon" : "sun"));
}
try { applyTheme(localStorage.getItem("mr-theme")); } catch (e) { applyTheme(null); }
$("theme").onclick = () => {
  const dark = document.documentElement.getAttribute("data-theme") === "dark"
    || (!document.documentElement.hasAttribute("data-theme")
        && !window.matchMedia("(prefers-color-scheme: light)").matches);
  const next = dark ? "light" : "dark";
  applyTheme(next);
  try { localStorage.setItem("mr-theme", next); } catch (e) { /* private mode */ }
};

// --- wiring -------------------------------------------------------------

$("lgo").onclick = signIn;
for (const id of ["lu", "lp"]) {
  $(id).addEventListener("keydown", (e) => { if (e.key === "Enter") signIn(); });
}
$("logout").onclick = async () => {
  try { await api("/admin/logout", {}); } catch (e) { /* the cookie is going either way */ }
  csrf = "";
  location.reload();
};
$("refresh").onclick = () => load(true).then(() => toast("Refreshed"));
$("scrim").onclick = closeDrawer;
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && state.openUser !== null) closeDrawer();
  if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || $("dlg").open) return;
  if (e.key === "r") $("refresh").click();
  if (e.key === "/" && state.view === "users") { e.preventDefault(); const s = $("usearch"); if (s) s.focus(); }
});

// A quiet poll keeps the numbers honest on a dashboard left open all day, but
// never while a drawer or dialog is up: re-rendering underneath a half-typed
// email would throw the edit away.
setInterval(() => {
  if (!state.data || state.openUser !== null || $("dlg").open || document.hidden) return;
  load(true);
}, 60000);
setInterval(tickFreshness, 20000);

// A live cookie from an earlier visit skips the login form; the overview call
// hands the CSRF token back, so the page is immediately usable. No cookie means
// that call 401s and the login card comes up instead.
state.view = location.hash.slice(1) || "overview";
if (!VIEWS[state.view]) state.view = "overview";
load();
</script>
"""


HA_GET = {
    "/ha/stats": ha_stats,
    "/ha/rides": ha_rides,
    "/ha/ride.geojson": ha_ride,
    "/ha/traces": ha_traces,
    "/ha/track": ha_track,
    "/ha/coverage": ha_coverage,
}


# --------------------------------------------------------------------------
# http


AUTHED_GET = {
    "/me": do_me,
    "/friends": do_friends,
    "/friends/stats": friend_stats,
    "/friends/fog": friend_fog,
    "/convoys": lambda user: do_groups(user, "convoy"),
    "/circles": lambda user: do_groups(user, "circle"),
    # /shared-routes/, not /routes/: a tunnel that fronts both this server and
    # a GraphHopper on one hostname matches its /route rule as a prefix, and
    # swallows every /routes/* request before it reaches here. Same reasoning
    # keeps every new circle path under /circles or /circle-places.
    "/shared-routes/inbox": do_routes_inbox,
}


def redact(text):
    """Strip API keys out of anything on its way to a log.

    The access log has always done this; the error path used to print
    `self.path` raw, which put a live dashboard key in the journal on nothing
    worse than a malformed query string.
    """
    return re.sub(r"key=[^&\s]+", "key=REDACTED", text)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def client_ip(self):
        # Behind the Cloudflare tunnel the socket peer is always localhost, so
        # the real client IP has to come from the header instead — but only
        # trust it when TRUST_CF_HEADER says the server is actually deployed
        # that way; otherwise anyone can spoof the header.
        if TRUST_CF_HEADER:
            return self.headers.get("CF-Connecting-IP") or self.address_string()
        return self.address_string()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/health":
            self._reply(200, b"ok", "text/plain")
            return
        try:
            params = parse_qs(parsed.query)
            if path.startswith("/ha/"):
                self._ha(path, params)
                return
            if path in ("/admin", "/admin/"):
                # The shell is public; every number on it comes from the
                # authenticated overview call below, which is what the cookie
                # actually gates.
                #
                # no-store because the page ships inside this file: after a
                # deploy the browser must fetch the new one, and a stale copy
                # looks exactly like a deploy that silently did nothing.
                self._reply(
                    200,
                    ADMIN_HTML.encode(),
                    "text/html; charset=utf-8",
                    [("Cache-Control", "no-store, must-revalidate")],
                )
                return
            if path == "/admin/api/overview":
                self._json(200, admin_overview(admin_session(self.headers)))
                return
            handler = AUTHED_GET.get(path)
            if handler is not None:
                self._json(200, handler(authenticate(self.headers)))
                return
            if path == "/circle-places":
                self._json(200, do_circle_places(authenticate(self.headers), params))
                return
            match = CIRCLE_GET_RE.match(path)
            if match is None:
                raise HttpError(404, "not found")
            group_id, action = int(match.group(1)), match.group(2)
            user = authenticate(self.headers)
            if action == "fixes":
                self._json(200, do_circle_fixes(user, group_id))
            else:
                self._json(200, do_circle_events(user, group_id, params))
        except HttpError as e:
            self._json(e.code, {"error": e.message})
        except Exception as e:  # noqa: BLE001 - never leak a stack trace
            self._json(500, {"error": "internal error"})
            print("ERROR %s: %r" % (redact(self.path), e))

    def do_POST(self):
        try:
            body = self._body()
            if self.path == "/auth/register":
                self._json(200, do_register(body, self.client_ip()))
            elif self.path == "/auth/login":
                self._json(200, do_login(body, self.client_ip()))
            elif self.path == "/auth/logout":
                self._json(200, do_logout(authenticate(self.headers), self.headers))
            elif self.path == "/auth/forgot":
                self._json(200, do_forgot(body, self.client_ip()))
            elif self.path == "/auth/reset":
                self._json(200, do_reset(body, self.client_ip()))
            elif self.path.startswith("/admin/"):
                self._admin(body)
            elif self.path == "/sync":
                self._json(200, do_sync(authenticate(self.headers), body))
            elif self.path == "/friends/request":
                self._json(200, do_friend_request(authenticate(self.headers), body))
            elif self.path == "/friends/respond":
                self._json(200, do_friend_respond(authenticate(self.headers), body))
            elif self.path == "/friends/remove":
                self._json(200, do_friend_remove(authenticate(self.headers), body))
            elif self.path == "/shared-routes/share":
                self._json(200, do_route_share(authenticate(self.headers), body))
            elif self.path == "/shared-routes/delete":
                self._json(200, do_route_delete(authenticate(self.headers), body))
            elif self.path == "/convoys":
                self._json(200, do_group_create(authenticate(self.headers), "convoy", body))
            elif self.path == "/circles":
                self._json(200, do_group_create(authenticate(self.headers), "circle", body))
            elif self.path == "/circle-places/share":
                self._json(200, do_circle_place_share(authenticate(self.headers), body))
            elif self.path == "/circle-places/delete":
                self._json(200, do_circle_place_delete(authenticate(self.headers), body))
            else:
                match = GROUP_ACTION_RE.match(self.path)
                if match is not None:
                    group_id, action = int(match.group(1)), match.group(2)
                    user = authenticate(self.headers)
                    if action == "invite":
                        self._json(200, do_group_invite(user, group_id, body))
                    elif action == "respond":
                        self._json(200, do_group_respond(user, group_id, body))
                    else:
                        self._json(200, do_group_leave(user, group_id, body))
                else:
                    match = CIRCLE_POST_RE.match(self.path)
                    if match is None:
                        raise HttpError(404, "not found")
                    group_id, action = int(match.group(1)), match.group(2)
                    user = authenticate(self.headers)
                    if action == "sharing":
                        self._json(200, do_group_sharing(user, group_id, body))
                    elif action == "fix":
                        self._json(200, do_circle_fix(user, group_id, body))
                    else:
                        self._json(200, do_circle_event_create(user, group_id, body))
        except HttpError as e:
            self._json(e.code, {"error": e.message})
        except (ValueError, KeyError, TypeError) as e:
            self._json(400, {"error": "bad request: %s" % e})
        except Exception as e:  # noqa: BLE001
            self._json(500, {"error": "internal error"})
            print("ERROR %s: %r" % (redact(self.path), e))

    def _ha(self, path, params):
        # The header form exists for REST sensors, which can send one; iframes
        # can't, so ?key= has to work too.
        header_key = self.headers.get("X-API-Key")
        if header_key and "key" not in params:
            params = dict(params, key=[header_key])
        # Still gates the dashboard itself — the page just reads the key from
        # its own URL client-side instead of having it templated back in.
        user = api_key_user(params)
        if path in ("/ha/ride.html", "/ha/dashboard.html"):
            self._reply(200, ha_dashboard_html(params).encode(), "text/html")
            return
        handler = HA_GET.get(path)
        if handler is None:
            raise HttpError(404, "not found")
        self._json(200, handler(user, params))

    def _admin_cookie(self, raw, max_age):
        """Secure only when the request really came in over https.

        The tunnel terminates TLS and forwards plain http with
        X-Forwarded-Proto set, so that header — not the socket — is what says
        how the browser is talking. Marking the cookie Secure unconditionally
        would have the browser silently drop it when the dashboard is opened
        over the LAN address (HOST=0.0.0.0, the same path Home Assistant uses
        for /ha/*), leaving a login form that succeeds and then loops.
        SameSite=Strict is the CSRF defence either way, with admin_write()
        behind it.
        """
        proto = self.headers.get("X-Forwarded-Proto", "").lower()
        secure = "; Secure" if proto == "https" else ""
        return "%s=%s; Path=/admin; HttpOnly%s; SameSite=Strict; Max-Age=%d" % (
            ADMIN_COOKIE, raw, secure, max_age,
        )

    def _admin(self, body):
        path = self.path
        if path == "/admin/login":
            payload, raw = do_admin_login(body, self.client_ip())
            self._json(
                200, payload,
                [("Set-Cookie", self._admin_cookie(raw, ADMIN_SESSION_IDLE_MS // 1000))],
            )
            return
        if path == "/admin/logout":
            self._json(
                200, do_admin_logout(self.headers),
                [("Set-Cookie", self._admin_cookie("", 0))],
            )
            return
        session = admin_write(self.headers)
        if path == "/admin/api/invite/create":
            self._json(200, do_admin_invite_create(body))
            return
        if path == "/admin/api/invite/revoke":
            self._json(200, do_admin_invite_revoke(body))
            return
        match = ADMIN_USER_ACTION_RE.match(path)
        if match is None:
            raise HttpError(404, "not found")
        self._json(
            200, do_admin_user_action(session, int(match.group(1)), match.group(2), body)
        )

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        if length > MAX_BODY:
            raise HttpError(413, "body too large")
        raw = self.rfile.read(length)
        # The app gzips every sync upload now (traces.jsonl is 1 MB+ and
        # re-sent whole each time); there is no third-party client to stay
        # compatible with, so this is unconditional on the client side.
        if self.headers.get("Content-Encoding", "").lower() == "gzip":
            # Bound the *decompressed* size as well: MAX_BODY caps what travels
            # the wire, but a gzip bomb expands far past that in memory, and
            # this path is reachable before auth.
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read(MAX_BODY + 1)
            if len(raw) > MAX_BODY:
                raise HttpError(413, "body too large")
        return json.loads(raw)

    def _json(self, code, payload, headers=()):
        self._reply(code, json.dumps(payload).encode(), "application/json", headers)

    def _reply(self, code, body, content_type, headers=()):
        # Compress replies the client says it can decode. Small bodies (most
        # /health, /friends/* replies) aren't worth the CPU; sync's full trip
        # + trace history is, by a lot — JSON compresses roughly 10:1.
        gzipped = len(body) > 1024 and "gzip" in self.headers.get("Accept-Encoding", "")
        if gzipped:
            body = gzip.compress(body)
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        for name, value in headers:
            self.send_header(name, value)
        if gzipped:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Dashboard URLs carry the API key in the query string; a log file is
        # not a place to keep credentials.
        print("%s %s" % (self.address_string(), redact(fmt % args)))


# --------------------------------------------------------------------------
# cli


def mint_api_key(username, label="home-assistant"):
    """Issue a read-only key for a dashboard. Printed once; only its hash is
    stored, exactly like a login token."""
    user = find_user(username)
    if user is None:
        print("No such user: %s" % username)
        return 1
    raw = secrets.token_urlsafe(TOKEN_BYTES)
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "INSERT INTO api_keys (key_hash, user_id, label, created_ms)"
                " VALUES (?, ?, ?, ?)",
                (token_hash(raw), user["id"], label or "home-assistant", now_ms()),
            )
    print("API key for %s (%s):\n%s" % (username, label, raw))
    print("Store it now — it is not recoverable.")
    return 0


def revoke_keys(username):
    """Delete every dashboard API key for a user. Anything embedding an old
    key (a Home Assistant config, a stray browser tab) starts getting 401
    on its next request."""
    user = find_user(username)
    if user is None:
        print("No such user: %s" % username)
        return 1
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            cur = conn.execute("DELETE FROM api_keys WHERE user_id = ?", (user["id"],))
    print("Revoked %d api key(s) for %s." % (cur.rowcount, username))
    return 0


def revoke_tokens(username):
    """Delete every bearer token for a user — signs them out of every device
    at once. The right response to a leaked token when the user can't wait
    out TOKEN_MAX_IDLE_MS."""
    user = find_user(username)
    if user is None:
        print("No such user: %s" % username)
        return 1
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            cur = conn.execute("DELETE FROM tokens WHERE user_id = ?", (user["id"],))
    print("Revoked %d token(s) for %s." % (cur.rowcount, username))
    return 0


def set_admin(username, wanted):
    """Grant or take away access to /admin. The way the first admin is made:
    the dashboard cannot promote anyone until someone can sign into it."""
    user = find_user(username)
    if user is None:
        print("No such user: %s" % username)
        return 1
    if not wanted and admin_count_admins() <= 1 and user["is_admin"]:
        print("%s is the only admin; promote someone else first." % username)
        return 1
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            conn.execute(
                "UPDATE users SET is_admin = ? WHERE id = ?",
                (1 if wanted else 0, user["id"]),
            )
            if not wanted:
                conn.execute(
                    "DELETE FROM admin_sessions WHERE user_id = ?", (user["id"],)
                )
    print("%s %s admin." % (user["username"], "is now" if wanted else "is no longer"))
    return 0


def set_user_password(username):
    """Set a password from the box itself.

    The dashboard can already do this, which covers every case but one: the
    only admin has forgotten their own password, so nobody can reach the
    dashboard to fix it. Prompted rather than taken as an argument, so the new
    password never lands in shell history or another user's `ps` output.
    """
    user = find_user(username)
    if user is None:
        print("No such user: %s" % username)
        return 1
    password = getpass.getpass("New password for %s (blank to generate one): " % username)
    if not password:
        password = secrets.token_urlsafe(9)
        print("Generated password: %s" % password)
    elif password != getpass.getpass("Repeat it: "):
        print("Those did not match; nothing changed.")
        return 1
    try:
        set_password(user["id"], password)
    except HttpError as e:
        print(e.message)
        return 1
    print(
        "Password set for %s. Every device it was signed in on has been signed "
        "out — sign in again in the app." % user["username"]
    )
    return 0


def backfill_points(username):
    """Re-unpack every stored trace line into track_points.

    Sync only unpacks lines it has just inserted, so this is the way back if the
    table is ever cleared or a line landed before points were a thing.
    """
    user = find_user(username)
    if user is None:
        print("No such user: %s" % username)
        return 1
    uid = user["id"]
    lines = 0
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            for row in conn.execute("SELECT line FROM traces WHERE user_id = ?", (uid,)):
                try:
                    store_points(conn, uid, json.loads(row["line"]))
                except (ValueError, TypeError):
                    continue
                lines += 1
    total = db().execute(
        "SELECT COUNT(*) AS n FROM track_points WHERE user_id = ?", (uid,)
    ).fetchone()["n"]
    print("Scanned %d trace lines; %s now holds %d points." % (lines, username, total))
    return 0


def import_legacy(username):
    """Move the pre-auth trips.json / traces.jsonl into a user's rows."""
    user = find_user(username)
    if user is None:
        print("No such user %r. Register in the app first." % username)
        return 1
    uid = user["id"]
    conn = db()
    trips = traces = 0
    if os.path.exists(LEGACY_TRIPS):
        with open(LEGACY_TRIPS, encoding="utf-8") as f:
            for trip in json.load(f):
                conn.execute(
                    "INSERT OR IGNORE INTO trips (user_id, start_ms, json) VALUES (?, ?, ?)",
                    (uid, int(trip["startTimeMs"]), json.dumps(trip)),
                )
                trips += 1
    if os.path.exists(LEGACY_TRACES):
        with open(LEGACY_TRACES, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                json.loads(line)
                conn.execute(
                    "INSERT OR IGNORE INTO traces (user_id, line_hash, line) VALUES (?, ?, ?)",
                    (uid, hashlib.sha256(line.encode()).hexdigest(), line),
                )
                traces += 1
    conn.commit()
    print("Imported %d trips and %d trace lines into %s." % (trips, traces, username))
    print("Old files left in place; delete them once the app has synced.")
    return 0


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    init_db()

    if len(sys.argv) > 2 and sys.argv[1] == "--import-legacy":
        raise SystemExit(import_legacy(sys.argv[2]))

    if len(sys.argv) > 2 and sys.argv[1] == "--api-key":
        raise SystemExit(mint_api_key(sys.argv[2], *sys.argv[3:4]))

    if len(sys.argv) > 2 and sys.argv[1] == "--backfill-points":
        raise SystemExit(backfill_points(sys.argv[2]))

    if len(sys.argv) > 2 and sys.argv[1] == "--revoke-keys":
        raise SystemExit(revoke_keys(sys.argv[2]))

    if len(sys.argv) > 2 and sys.argv[1] == "--revoke-tokens":
        raise SystemExit(revoke_tokens(sys.argv[2]))

    if len(sys.argv) > 2 and sys.argv[1] == "--make-admin":
        raise SystemExit(set_admin(sys.argv[2], True))

    if len(sys.argv) > 2 and sys.argv[1] == "--drop-admin":
        raise SystemExit(set_admin(sys.argv[2], False))

    if len(sys.argv) > 2 and sys.argv[1] == "--set-password":
        raise SystemExit(set_user_password(sys.argv[2]))

    # Tokens idle past TOKEN_MAX_IDLE_MS are already rejected by authenticate();
    # this just stops them accumulating in the table forever. Dead reset links
    # and admin sessions go the same way.
    with _write_lock:
        conn = db()
        with conn:  # commits on success, rolls back on exception
            pruned = conn.execute(
                "DELETE FROM tokens WHERE last_used_ms < ?", (now_ms() - TOKEN_MAX_IDLE_MS,)
            ).rowcount
            conn.execute("DELETE FROM password_resets WHERE expires_ms < ?", (now_ms(),))
            conn.execute(
                "DELETE FROM admin_sessions WHERE last_used_ms < ?",
                (now_ms() - ADMIN_SESSION_IDLE_MS,),
            )
    if pruned:
        print("pruned %d idle token(s)" % pruned)

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8790"))
    live_port = int(os.environ.get("LIVE_PORT", "8990"))
    print("detour-sync on %s:%s, db at %s" % (host, port, DB_FILE))
    print("registration: %s" % ("open" if REGISTRATION_OPEN else "closed"))
    print("mail: %s" % ("via %s as %s" % (SMTP_HOST, SMTP_FROM) if mail_configured() else "off"))
    admins = admin_count_admins()
    print(
        "manager dashboard: /admin (%s)"
        % ("%d admin(s)" % admins if admins else "no admins yet — run --make-admin USER")
    )
    # An empty database with registration closed and no invite code is a
    # server nobody, including its owner, can sign into. Say so loudly rather
    # than let that be a silent dead end.
    if not REGISTRATION_OPEN and not INVITE_CODE:
        user_count = db().execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
        if user_count == 0:
            print("*** no users exist yet, and registration is closed. ***")
            print("*** set REGISTRATION_OPEN=1 or INVITE_CODE=... to create the first account. ***")
    threading.Thread(target=run_live_server, args=(host, live_port), daemon=True).start()
    ThreadingHTTPServer((host, port), Handler).serve_forever()
