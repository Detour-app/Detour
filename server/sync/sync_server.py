#!/usr/bin/env python3
"""Map Roulette sync + social server.

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

Protocol
  GET  /health                                  -> "ok"
  POST /auth/register {username, password, invite?}  -> {token, username}
  POST /auth/login    {username, password}      -> {token, username}
  POST /auth/logout                             -> {} (revokes the bearer token)
  GET  /me                                      -> {username, stats, badges}
  POST /sync {trips, traces, badges, savedPlaces?, stats, shareFog?} -> merged {trips, traces, badges, savedPlaces}
  GET  /friends                                 -> {friends, incoming, outgoing}
  POST /friends/request {username}              -> {status}
  POST /friends/respond {username, accept}      -> {status}
  POST /friends/remove  {username}              -> {}
  GET  /friends/stats                           -> [{username, stats, badges}]
  GET  /friends/fog                             -> {sharing, traces: [line, …]}
  GET  /ha/rides?key=[&limit=]                  -> {rides: [{startMs, maxLeanDeg, …}]}
  GET  /ha/ride.geojson?key=&start=             -> GeoJSON, one Feature per segment
  GET  /ha/ride.html?key=[&start=]              -> Leaflet page, path coloured by lean

Everything except /health, /auth/* and /ha/* needs `Authorization: Bearer
<token>`. The /ha/* endpoints are read-only and take an API key instead
(?key= or X-API-Key), so a Home Assistant config never holds a login token.

Merging is idempotent:
  - trips key on (user, startTimeMs); a re-upload updates the stored copy, so
    an edit like a corrected vehicle mode propagates instead of being ignored;
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
Bind to localhost.

Python 3.8+ stdlib only. DATA_DIR env var sets the storage directory.

CLI:
  python3 sync_server.py                      run the server
  python3 sync_server.py --import-legacy USER import old trips.json/traces.jsonl
  python3 sync_server.py --api-key USER [LABEL]   mint a read-only dashboard key
  python3 sync_server.py --backfill-points USER   re-unpack traces into points
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(__file__), "data"))
DB_FILE = os.path.join(DATA_DIR, "maproulette.db")
LEGACY_TRIPS = os.path.join(DATA_DIR, "trips.json")
LEGACY_TRACES = os.path.join(DATA_DIR, "traces.jsonl")

MAX_BODY = 64 * 1024 * 1024
ITERATIONS = 210_000
TOKEN_BYTES = 32

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.-]{3,24}$")
BADGE_ID_RE = re.compile(r"^[a-z]+_[0-9]+$")
MAX_BADGES = 200

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

# Registration can be closed once your friends have accounts (env), or gated on
# a shared invite code.
REGISTRATION_OPEN = os.environ.get("REGISTRATION_OPEN", "1") != "0"
INVITE_CODE = os.environ.get("INVITE_CODE", "")

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
        CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);
        CREATE INDEX IF NOT EXISTS idx_points_user_t ON track_points(user_id, t_ms);
        """
    )
    # Added after the first release; CREATE TABLE IF NOT EXISTS won't add it to
    # a database that already exists.
    columns = {r["name"] for r in conn.execute("PRAGMA table_info(users)")}
    if "share_fog" not in columns:
        conn.execute("ALTER TABLE users ADD COLUMN share_fog INTEGER NOT NULL DEFAULT 0")
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
        conn.execute(
            "INSERT INTO tokens (token_hash, user_id, created_ms, last_used_ms)"
            " VALUES (?, ?, ?, ?)",
            (token_hash(raw), user_id, now_ms(), now_ms()),
        )
        conn.commit()
    return raw


def authenticate(headers):
    header = headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HttpError(401, "missing bearer token")
    row = db().execute(
        "SELECT u.* FROM tokens t JOIN users u ON u.id = t.user_id"
        " WHERE t.token_hash = ?",
        (token_hash(header[7:].strip()),),
    ).fetchone()
    if row is None:
        raise HttpError(401, "invalid token")
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


def do_register(body, ip):
    if not REGISTRATION_OPEN:
        raise HttpError(403, "registration is closed")
    rate_limit(ip)
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    if not USERNAME_RE.match(username):
        raise HttpError(400, "username must be 3-24 chars: letters, digits, . _ -")
    if not 8 <= len(password) <= 200:
        raise HttpError(400, "password must be 8-200 characters")
    if INVITE_CODE and not hmac.compare_digest(str(body.get("invite", "")), INVITE_CODE):
        note_failure(ip)  # guessing the invite code is an attack, not a typo
        raise HttpError(403, "invalid invite code")
    if find_user(username):
        raise HttpError(409, "username already taken")

    salt, digest, iterations = hash_password(password)
    with _write_lock:
        conn = db()
        try:
            cur = conn.execute(
                "INSERT INTO users (username, pw_salt, pw_hash, iterations, created_ms)"
                " VALUES (?, ?, ?, ?, ?)",
                (username, salt, digest, iterations, now_ms()),
            )
        except sqlite3.IntegrityError:
            raise HttpError(409, "username already taken")
        conn.commit()
        user_id = cur.lastrowid
    return {"token": issue_token(user_id), "username": username}


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
        conn.execute("DELETE FROM tokens WHERE token_hash = ?", (token_hash(raw),))
        conn.commit()
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
    if not isinstance(trips_in, list) or not isinstance(traces_in, list):
        raise HttpError(400, "trips and traces must be arrays")
    if not isinstance(places_in, list):
        raise HttpError(400, "savedPlaces must be an array")

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
        for trip in trips_in:
            if not isinstance(trip, dict) or "startTimeMs" not in trip:
                raise HttpError(400, "trip missing startTimeMs")
            # Upsert, not INSERT OR IGNORE: a trip re-uploaded with edited fields
            # (e.g. a corrected vehicle mode) must replace the stored copy, or the
            # stale row would come back in the merge and revert the edit.
            conn.execute(
                "INSERT INTO trips (user_id, start_ms, json) VALUES (?, ?, ?) "
                "ON CONFLICT(user_id, start_ms) DO UPDATE SET json = excluded.json",
                (uid, int(trip["startTimeMs"]), json.dumps(trip)),
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
            if not isinstance(place, dict) or "id" not in place:
                raise HttpError(400, "saved place missing id")
            # Upsert by id so a rename replaces the stored copy; the merge below
            # returns the union, which is what restores shortcuts after reinstall.
            conn.execute(
                "INSERT INTO saved_places (user_id, place_id, json) VALUES (?, ?, ?) "
                "ON CONFLICT(user_id, place_id) DO UPDATE SET json = excluded.json",
                (uid, int(place["id"]), json.dumps(place)),
            )
        conn.execute(
            "UPDATE users SET badges_json = ?, stats_json = ?, share_fog = ? WHERE id = ?",
            (json.dumps(badges), json.dumps(stats), share_fog, uid),
        )
        conn.commit()

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
        conn.execute(
            "INSERT INTO friendships (low_id, high_id, status, requested_by, created_ms)"
            " VALUES (?, ?, 'pending', ?, ?)",
            (low, high, user["id"], now_ms()),
        )
        conn.commit()
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
        conn.commit()
    return {"status": "accepted" if accept else "declined"}


def do_friend_remove(user, body):
    target = other_user(body)
    low, high = pair(user["id"], target["id"])
    with _write_lock:
        conn = db()
        conn.execute(
            "DELETE FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
        )
        conn.commit()
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
        # longest plausible ride rather than an empty window.
        end = start_ms + 24 * 3600 * 1000
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
        limit = min(int((params.get("limit") or ["25"])[0]), 200)
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
        end = int(trip.get("endTimeMs") or 0) or start + 24 * 3600 * 1000
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


def ha_ride(user, params):
    """One ride as GeoJSON: a Feature per segment, carrying the speed and lean
    recorded at its far end. Per-segment rather than one line, because a line
    can only be one colour — and colouring by lean is the whole point."""
    uid = user["id"]
    start = int((params.get("start") or ["0"])[0])
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
    return {
        "type": "FeatureCollection",
        "features": features,
        "properties": {
            "startMs": start,
            "mode": trip.get("mode"),
            "distanceKm": round((trip.get("distanceMeters") or 0) / 1000.0, 2),
            "maxLeanDeg": round(max(leans), 1) if leans else None,
            "pointCount": len(pts),
        },
    }


# Leaflet comes from a CDN: the dashboard embedding this already needs the
# internet for map tiles, and vendoring a copy here would be a second thing to
# keep patched.
RIDE_HTML = """<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ride %(start)s</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
  html, body, #map { height: 100%%; margin: 0; background: #11131a; }
  .legend { background: rgba(20,22,30,.85); color: #eee; padding: 8px 10px;
            font: 12px system-ui, sans-serif; border-radius: 6px; }
  .legend b { display: block; margin-bottom: 4px; font-weight: 600; }
  .bar { width: 160px; height: 10px; border-radius: 5px; margin: 4px 0;
         background: linear-gradient(90deg,#2f6fed,#5b8def,#9aa4b2,#ef8a5b,#e2402a); }
  .ends { display: flex; justify-content: space-between; }
</style>
<div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const data = %(geojson)s;
const MAX_LEAN = 45;
// Diverging: blue leaning left, red leaning right, grey upright. Segments with
// no lean recorded (a car, or a ride from before lean was stored) stay grey.
function colour(lean) {
  if (lean === null || lean === undefined) return '#9aa4b2';
  const t = Math.max(-1, Math.min(1, lean / MAX_LEAN));
  const mix = (a, b, k) => a.map((v, i) => Math.round(v + (b[i] - v) * k));
  const grey = [154, 164, 178];
  const rgb = t < 0 ? mix(grey, [47, 111, 237], -t) : mix(grey, [226, 64, 42], t);
  return 'rgb(' + rgb.join(',') + ')';
}
const map = L.map('map', { zoomControl: true });
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; OpenStreetMap'
}).addTo(map);
const layer = L.geoJSON(data, {
  style: f => ({ color: colour(f.properties.leanDeg), weight: 6, opacity: 0.95 }),
  onEachFeature: (f, l) => {
    const p = f.properties;
    l.bindTooltip(
      (p.leanDeg === null ? 'lean n/a' : p.leanDeg.toFixed(0) + '\\u00b0 lean') +
      ' \\u00b7 ' + (p.speedKmh === null ? '?' : p.speedKmh.toFixed(0)) + ' km/h');
  }
}).addTo(map);
if (data.features.length) map.fitBounds(layer.getBounds(), { padding: [20, 20] });
else map.setView([50.85, 4.35], 9);
const legend = L.control({ position: 'bottomright' });
legend.onAdd = () => {
  const d = L.DomUtil.create('div', 'legend');
  const max = data.properties.maxLeanDeg;
  d.innerHTML = '<b>Lean' + (max === null ? '' : ' \\u00b7 max ' + max + '\\u00b0') +
    '</b><div class="bar"></div><div class="ends"><span>left ' + MAX_LEAN +
    '\\u00b0</span><span>right ' + MAX_LEAN + '\\u00b0</span></div>';
  return d;
};
legend.addTo(map);
</script>
"""


def ha_ride_html(user, params):
    start = int((params.get("start") or ["0"])[0])
    if not start:
        # No ride named: the newest one is what a dashboard card wants.
        row = db().execute(
            "SELECT start_ms FROM trips WHERE user_id = ? ORDER BY start_ms DESC LIMIT 1",
            (user["id"],),
        ).fetchone()
        if row is None:
            raise HttpError(404, "no rides")
        start = row["start_ms"]
        params = dict(params, start=[str(start)])
    geo = ha_ride(user, params)
    return RIDE_HTML % {"start": start, "geojson": json.dumps(geo)}


HA_GET = {
    "/ha/rides": ha_rides,
    "/ha/ride.geojson": ha_ride,
}


# --------------------------------------------------------------------------
# http


AUTHED_GET = {
    "/me": do_me,
    "/friends": do_friends,
    "/friends/stats": friend_stats,
    "/friends/fog": friend_fog,
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def client_ip(self):
        # Behind the Cloudflare tunnel the socket peer is always localhost.
        return self.headers.get("CF-Connecting-IP") or self.address_string()

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
            handler = AUTHED_GET.get(path)
            if handler is None:
                raise HttpError(404, "not found")
            self._json(200, handler(authenticate(self.headers)))
        except HttpError as e:
            self._json(e.code, {"error": e.message})
        except Exception as e:  # noqa: BLE001 - never leak a stack trace
            self._json(500, {"error": "internal error"})
            print("ERROR %s: %r" % (self.path, e))

    def do_POST(self):
        try:
            body = self._body()
            if self.path == "/auth/register":
                self._json(200, do_register(body, self.client_ip()))
            elif self.path == "/auth/login":
                self._json(200, do_login(body, self.client_ip()))
            elif self.path == "/auth/logout":
                self._json(200, do_logout(authenticate(self.headers), self.headers))
            elif self.path == "/sync":
                self._json(200, do_sync(authenticate(self.headers), body))
            elif self.path == "/friends/request":
                self._json(200, do_friend_request(authenticate(self.headers), body))
            elif self.path == "/friends/respond":
                self._json(200, do_friend_respond(authenticate(self.headers), body))
            elif self.path == "/friends/remove":
                self._json(200, do_friend_remove(authenticate(self.headers), body))
            else:
                raise HttpError(404, "not found")
        except HttpError as e:
            self._json(e.code, {"error": e.message})
        except (ValueError, KeyError, TypeError) as e:
            self._json(400, {"error": "bad request: %s" % e})
        except Exception as e:  # noqa: BLE001
            self._json(500, {"error": "internal error"})
            print("ERROR %s: %r" % (self.path, e))

    def _ha(self, path, params):
        # The header form exists for REST sensors, which can send one; iframes
        # can't, so ?key= has to work too.
        header_key = self.headers.get("X-API-Key")
        if header_key and "key" not in params:
            params = dict(params, key=[header_key])
        user = api_key_user(params)
        if path == "/ha/ride.html":
            self._reply(200, ha_ride_html(user, params).encode(), "text/html")
            return
        handler = HA_GET.get(path)
        if handler is None:
            raise HttpError(404, "not found")
        self._json(200, handler(user, params))

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        if length > MAX_BODY:
            raise HttpError(413, "body too large")
        return json.loads(self.rfile.read(length))

    def _json(self, code, payload):
        self._reply(code, json.dumps(payload).encode(), "application/json")

    def _reply(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Dashboard URLs carry the API key in the query string; a log file is
        # not a place to keep credentials.
        line = re.sub(r"key=[^&\s]+", "key=REDACTED", fmt % args)
        print("%s %s" % (self.address_string(), line))


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
        conn.execute(
            "INSERT INTO api_keys (key_hash, user_id, label, created_ms)"
            " VALUES (?, ?, ?, ?)",
            (token_hash(raw), user["id"], label or "home-assistant", now_ms()),
        )
        conn.commit()
    print("API key for %s (%s):\n%s" % (username, label, raw))
    print("Store it now — it is not recoverable.")
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
        for row in conn.execute("SELECT line FROM traces WHERE user_id = ?", (uid,)):
            try:
                store_points(conn, uid, json.loads(row["line"]))
            except (ValueError, TypeError):
                continue
            lines += 1
        conn.commit()
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

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8790"))
    print("maproulette-sync on %s:%s, db at %s" % (host, port, DB_FILE))
    print("registration: %s" % ("open" if REGISTRATION_OPEN else "closed"))
    ThreadingHTTPServer((host, port), Handler).serve_forever()
