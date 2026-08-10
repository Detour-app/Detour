#!/usr/bin/env python3
"""Tests for sync_server.py's group (convoy + circle) membership gate and
live relay - the part docs/CIRCLES_AND_CONVOYS.md calls "load-bearing" and
says "deserves direct tests, which it does not currently have" (section 9).

stdlib unittest only, no pytest. Each test points sync_server.DB_FILE at a
fresh temp SQLite file and resets the module's thread-local connection, so
nothing here ever touches a real deployment's data directory. Run with:

    python3 -m unittest discover -s server/sync -v

The relay tests use a small fake websocket (async send/close, records every
frame sent to it) instead of opening a real socket - see FakeWebSocket
below. Peers other than the socket under test are registered directly via
_group_join rather than by running a second handle_live_socket coroutine,
which sidesteps needing two coroutines to interleave deterministically and
still exercises the real broadcast/gating code the peer would receive
through.
"""
import asyncio
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sync_server as S  # noqa: E402


class FakeWebSocket:
    """Stands in for a `websockets` connection: async send/close, and a
    record of every frame actually sent to it. `incoming` is the list of
    text frames handle_live_socket's `async for raw in websocket` will
    receive, in order; the loop ends (and `finally` runs) once it's
    exhausted, the same way a real socket ending in EOF would."""

    def __init__(self, token, incoming=None):
        self.request_headers = {"Authorization": "Bearer %s" % token}
        self._incoming = list(incoming or [])
        self.sent = []
        self.closed = False
        self.close_code = None

    def __aiter__(self):
        return self

    async def __anext__(self):
        if not self._incoming:
            raise StopAsyncIteration
        return self._incoming.pop(0)

    async def send(self, payload):
        if self.closed:
            raise S.websockets.ConnectionClosed(None, None)
        self.sent.append(payload)

    async def close(self, code=None, reason=None):
        self.closed = True
        self.close_code = code

    def sent_types(self):
        return [json.loads(f)["type"] for f in self.sent]


class QueueWebSocket(FakeWebSocket):
    """Like FakeWebSocket, but frames arrive one at a time through an
    asyncio.Queue instead of being front-loaded - so a test can run
    handle_live_socket as a background task and interleave other coroutines
    (e.g. an eviction "from another device") between frames, rather than
    only being able to script a fixed conversation in advance."""

    def __init__(self, token):
        super().__init__(token, incoming=None)
        self._queue = asyncio.Queue()

    async def __anext__(self):
        item = await self._queue.get()
        if item is None:
            raise StopAsyncIteration
        return item

    async def push(self, obj):
        await self._queue.put(json.dumps(obj))

    async def end(self):
        await self._queue.put(None)


async def _wait_for_sent_count(ws, n, timeout=2.0):
    """Polls until `ws` has sent at least `n` frames. Frames on a given
    websocket are always produced by handle_live_socket's single async-for
    loop in the order the corresponding inbound frames were queued, so
    waiting for the Nth outbound frame is a reliable way to know every
    inbound frame up to and including whichever one produced it has already
    finished processing - including frames that intentionally produce no
    output, as long as a later one does."""
    loop = asyncio.get_event_loop()
    start = loop.time()
    while len(ws.sent) < n:
        if loop.time() - start > timeout:
            raise AssertionError(
                "timed out waiting for %d sent frames, got %d: %r" % (n, len(ws.sent), ws.sent)
            )
        await asyncio.sleep(0.005)


class DBFixtureMixin:
    """Fresh temp SQLite file per test, with the module's thread-local
    connection reset so a stale connection from a previous test's file can
    never leak in - db() would otherwise happily keep using whatever
    connection this thread opened last."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="sync_server_test_")
        S.DATA_DIR = self.tmpdir
        S.DB_FILE = os.path.join(self.tmpdir, "test.db")
        if hasattr(S._local, "conn"):
            del S._local.conn
        S.init_db()
        # Relay registry/loop state is module-global; start every test clean
        # regardless of what a previous test (or a previous run_live_server)
        # left behind.
        S._group_sockets.clear()
        S._live_loop = None

    def tearDown(self):
        if hasattr(S._local, "conn"):
            try:
                S._local.conn.close()
            finally:
                del S._local.conn
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    # -- fixtures -----------------------------------------------------

    def make_user(self, username):
        salt, digest, iters = S.hash_password("correct horse battery staple")
        with S._write_lock:
            conn = S.db()
            with conn:
                conn.execute(
                    "INSERT INTO users (username, pw_salt, pw_hash, iterations, created_ms)"
                    " VALUES (?, ?, ?, ?, ?)",
                    (username, salt, digest, iters, S.now_ms()),
                )
        return S.db().execute(
            "SELECT * FROM users WHERE username = ?", (username,)
        ).fetchone()

    def befriend(self, a, b):
        low, high = S.pair(a["id"], b["id"])
        with S._write_lock:
            conn = S.db()
            with conn:
                conn.execute(
                    "INSERT INTO friendships (low_id, high_id, status, requested_by, created_ms)"
                    " VALUES (?, ?, 'accepted', ?, ?)",
                    (low, high, a["id"], S.now_ms()),
                )

    def make_group(self, owner, kind="convoy", name="Test Group"):
        return S.do_group_create(owner, kind, {"name": name})["id"]

    def add_member(self, group_id, user_id, status="accepted"):
        with S._write_lock:
            conn = S.db()
            with conn:
                conn.execute(
                    "INSERT INTO group_members (group_id, user_id, status, joined_ms)"
                    " VALUES (?, ?, ?, ?)",
                    (group_id, user_id, status, S.now_ms()),
                )


class SyncTestCase(DBFixtureMixin, unittest.TestCase):
    pass


class AsyncTestCase(DBFixtureMixin, unittest.IsolatedAsyncioTestCase):
    pass


# --------------------------------------------------------------------------
# membership gating - is_group_member / _group_member


class MembershipGatingTests(SyncTestCase):
    def test_accepted_member_is_a_member(self):
        alice = self.make_user("alice")
        gid = self.make_group(alice)
        self.assertTrue(S.is_group_member(gid, alice["id"]))

    def test_invited_but_not_accepted_is_not_a_member(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        gid = self.make_group(alice)
        self.add_member(gid, bob["id"], status="invited")
        self.assertFalse(S.is_group_member(gid, bob["id"]))

    def test_non_member_is_not_a_member(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        gid = self.make_group(alice)
        self.assertFalse(S.is_group_member(gid, bob["id"]))

    def test_nonexistent_group_is_not_a_member(self):
        alice = self.make_user("alice")
        self.assertFalse(S.is_group_member(999999, alice["id"]))


# --------------------------------------------------------------------------
# reconnect identity check - _group_part must not evict a socket that
# already replaced it


class ReconnectIdentityTests(SyncTestCase):
    def test_stale_socket_cleanup_does_not_evict_the_reconnect(self):
        gid, uid = 1, 42
        old_ws, new_ws = object(), object()
        S._group_join(gid, uid, "alice", old_ws, "hash-old")
        # A reconnect arrives and registers before the old socket notices
        # it's dead.
        S._group_join(gid, uid, "alice", new_ws, "hash-new")
        # The old socket's own (slow) teardown runs after the fact.
        parted = S._group_part(gid, uid, old_ws)
        self.assertFalse(parted, "the stale socket's cleanup must not report success")
        self.assertIs(S._group_sockets[gid][uid][1], new_ws)

    def test_the_current_socket_can_still_part_itself(self):
        gid, uid = 2, 7
        ws = object()
        S._group_join(gid, uid, "bob", ws, "hash")
        self.assertTrue(S._group_part(gid, uid, ws))
        self.assertNotIn(gid, S._group_sockets)


# --------------------------------------------------------------------------
# eviction - leave drops the socket; a multi-group socket keeps the
# membership(s) it wasn't evicted from


class EvictionTests(AsyncTestCase):
    async def test_leave_drops_the_socket(self):
        gid, uid = 3, 1
        ws = FakeWebSocket("n/a")
        S._group_join(gid, uid, "alice", ws, "hash")
        await S._evict(gid, uid)
        self.assertNotIn(gid, S._group_sockets)
        self.assertTrue(ws.closed)

    async def test_evict_on_one_group_does_not_close_a_socket_valid_in_another(self):
        circle_id, convoy_id, uid = 10, 11, 1
        ws = FakeWebSocket("n/a")
        S._group_join(circle_id, uid, "alice", ws, "hash")
        S._group_join(convoy_id, uid, "alice", ws, "hash")

        await S._evict(circle_id, uid)
        self.assertNotIn(circle_id, S._group_sockets)
        self.assertIn(convoy_id, S._group_sockets)
        self.assertFalse(ws.closed, "socket is still legitimately joined to the convoy")

        await S._evict(convoy_id, uid)
        self.assertNotIn(convoy_id, S._group_sockets)
        self.assertTrue(ws.closed, "no membership left anywhere - now it should close")

    async def test_evict_everywhere_closes_once_all_memberships_are_gone(self):
        uid = 5
        ws = FakeWebSocket("n/a")
        S._group_join(20, uid, "alice", ws, "hash")
        S._group_join(21, uid, "alice", ws, "hash")
        S._group_join(22, uid, "alice", ws, "hash")
        await S._evict_everywhere(uid)
        self.assertEqual(S._group_sockets, {})
        self.assertTrue(ws.closed)


# --------------------------------------------------------------------------
# group lifecycle - drop_when_empty as data, not a `kind` branch (docs'
# own "High" severity risk: a circle must not evaporate when its last
# member leaves)


class GroupLifecycleTests(SyncTestCase):
    def test_circle_survives_its_last_member_leaving(self):
        alice = self.make_user("alice")
        gid = self.make_group(alice, kind="circle")
        S.do_group_leave(alice, gid, {})
        self.assertIsNotNone(S._group_row(gid))

    def test_convoy_is_dropped_when_its_last_member_leaves(self):
        alice = self.make_user("alice")
        gid = self.make_group(alice, kind="convoy")
        S.do_group_leave(alice, gid, {})
        self.assertIsNone(S._group_row(gid))

    def test_circle_invite_is_capped_at_max_circle_members(self):
        alice = self.make_user("alice")
        gid = self.make_group(alice, kind="circle")
        # Fill the circle up to the cap (alice counts as one member already).
        for i in range(S.MAX_CIRCLE_MEMBERS - 1):
            u = self.make_user("member%d" % i)
            self.befriend(alice, u)
            S.do_group_invite(alice, gid, {"username": u["username"]})
        extra = self.make_user("one_too_many")
        self.befriend(alice, extra)
        with self.assertRaises(S.HttpError) as ctx:
            S.do_group_invite(alice, gid, {"username": extra["username"]})
        self.assertEqual(ctx.exception.code, 400)

    def test_group_sharing_404s_on_a_convoy_id(self):
        alice = self.make_user("alice")
        gid = self.make_group(alice, kind="convoy")
        with self.assertRaises(S.HttpError) as ctx:
            S.do_group_sharing(alice, gid, {"sharing": False})
        self.assertEqual(ctx.exception.code, 404)

    def test_group_sharing_works_on_a_circle_id(self):
        alice = self.make_user("alice")
        gid = self.make_group(alice, kind="circle")
        result = S.do_group_sharing(alice, gid, {"sharing": False})
        self.assertEqual(result, {"sharing": False})
        row = S._group_member(gid, alice["id"])
        self.assertEqual(row["sharing"], 0)


# --------------------------------------------------------------------------
# anti-probing (docs/CIRCLES_AND_CONVOYS.md section 9.4) - the circle-only
# extensions gated by _require_group_membership must not let an
# authenticated stranger tell "no such group" from "that's a convoy" from
# "you're just not in it". do_group_sharing is the one deliberate exception
# (see test_group_sharing_404s_on_a_convoy_id above) - it's only reachable
# with an id the caller already holds, and the doc's own API table
# specifies its 404.


class AntiProbingTests(SyncTestCase):
    def test_missing_group_and_a_real_convoy_id_get_the_same_status(self):
        alice = self.make_user("alice")
        convoy_id = self.make_group(alice, kind="convoy")
        with self.assertRaises(S.HttpError) as missing:
            S._require_group_membership(999999, alice["id"])
        with self.assertRaises(S.HttpError) as convoy:
            S._require_group_membership(convoy_id, alice["id"])
        self.assertEqual(missing.exception.code, 403)
        self.assertEqual(convoy.exception.code, 403)
        self.assertEqual(missing.exception.message, convoy.exception.message)

    def test_non_member_of_a_real_circle_gets_the_same_status_too(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        with self.assertRaises(S.HttpError) as ctx:
            S._require_group_membership(circle_id, bob["id"])
        self.assertEqual(ctx.exception.code, 403)


# --------------------------------------------------------------------------
# wire compatibility - a frame with no groupId (or a join with the old
# convoyId key) means "my only joined group" for one release


class WireCompatTests(unittest.TestCase):
    def test_no_group_id_resolves_to_the_only_joined_group(self):
        self.assertEqual(S._frame_group_id({"type": "location"}, {5: "circle"}), 5)

    def test_no_group_id_with_multiple_joined_groups_is_dropped(self):
        self.assertIsNone(S._frame_group_id({"type": "location"}, {5: "circle", 6: "convoy"}))

    def test_explicit_group_id_wins_even_with_one_joined_group(self):
        self.assertEqual(S._frame_group_id({"groupId": 9}, {5: "circle"}), 9)

    def test_old_convoy_id_key_is_still_understood(self):
        self.assertEqual(S._frame_group_id({"convoyId": 9}, {}), 9)


# --------------------------------------------------------------------------
# PTT gate - the doc's "single highest-consequence line in the whole merge":
# reject ptt_* server-side for any group whose kind isn't 'convoy'


class PttGateTests(AsyncTestCase):
    async def test_ptt_is_rejected_for_a_circle(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        self.add_member(circle_id, bob["id"])
        # bob is a peer who must never receive PTT audio meant for a circle.
        bob_ws = FakeWebSocket("n/a")
        S._group_join(circle_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": circle_id}),
            json.dumps({"type": "ptt_start", "groupId": circle_id}),
            json.dumps({"type": "ptt_audio", "groupId": circle_id, "chunk": "aGVsbG8="}),
            json.dumps({"type": "ptt_end", "groupId": circle_id}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        # "left" is expected - alice's connection ends and parts the circle.
        # No ptt_* frame of any kind is what's actually under test.
        self.assertEqual(
            [t for t in bob_ws.sent_types() if t.startswith("ptt_")], [],
            "no PTT frame of any kind may reach a circle peer",
        )

    async def test_ptt_is_allowed_for_a_convoy(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        convoy_id = self.make_group(alice, kind="convoy")
        self.add_member(convoy_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(convoy_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": convoy_id}),
            json.dumps({"type": "ptt_start", "groupId": convoy_id}),
            json.dumps({"type": "ptt_audio", "groupId": convoy_id, "chunk": "aGVsbG8="}),
            json.dumps({"type": "ptt_end", "groupId": convoy_id}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertEqual(
            [t for t in bob_ws.sent_types() if t.startswith("ptt_")],
            ["ptt_start", "ptt_audio", "ptt_end"],
            "a convoy is exactly the case PTT exists for",
        )


# --------------------------------------------------------------------------
# spin gate - a convoy votes on a spun destination; same kind check as PTT,
# same reason: a circle is a standing "who's where" map, not a group ride
# choosing where to go.


class SpinGateTests(AsyncTestCase):
    async def test_spin_offer_and_vote_are_relayed_for_a_convoy(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        convoy_id = self.make_group(alice, kind="convoy")
        self.add_member(convoy_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(convoy_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": convoy_id}),
            json.dumps({
                "type": "spin_offer", "groupId": convoy_id,
                "candidates": [
                    {"lat": 1.0, "lon": 2.0, "distanceM": 500.0, "durationS": 60.0, "name": "Oak St"},
                    {"lat": 1.1, "lon": 2.1},
                ],
            }),
            json.dumps({"type": "spin_vote", "groupId": convoy_id, "index": 1}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertEqual(
            [t for t in bob_ws.sent_types() if t.startswith("spin_")],
            ["spin_offer", "spin_vote"],
            "a convoy is exactly the case a group spin exists for",
        )
        offer = json.loads(bob_ws.sent[0])
        self.assertEqual(offer["user"], "alice")
        self.assertEqual(len(offer["candidates"]), 2)
        self.assertEqual(offer["candidates"][0]["name"], "Oak St")
        vote = json.loads(bob_ws.sent[1])
        self.assertEqual(
            vote, {"type": "spin_vote", "groupId": convoy_id, "user": "alice", "index": 1}
        )

    async def test_spin_is_rejected_for_a_circle(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        self.add_member(circle_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(circle_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": circle_id}),
            json.dumps({
                "type": "spin_offer", "groupId": circle_id,
                "candidates": [{"lat": 1.0, "lon": 2.0}],
            }),
            json.dumps({"type": "spin_vote", "groupId": circle_id, "index": 0}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertEqual(
            [t for t in bob_ws.sent_types() if t.startswith("spin_")], [],
            "a circle is a standing map, not a vote - no spin frame may reach it",
        )

    async def test_spin_offer_for_a_group_never_joined_is_dropped(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        convoy_id = self.make_group(alice, kind="convoy")
        other_convoy_id = self.make_group(bob, kind="convoy")
        self.add_member(convoy_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(other_convoy_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": convoy_id}),
            # alice is not a member of other_convoy_id and never joined it -
            # naming it explicitly must not relay into it anyway.
            json.dumps({
                "type": "spin_offer", "groupId": other_convoy_id,
                "candidates": [{"lat": 1.0, "lon": 2.0}],
            }),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertEqual(
            [t for t in bob_ws.sent_types() if t.startswith("spin_")], [],
            "a group this socket never joined must not receive a relayed spin frame",
        )

    async def test_invalid_spin_payloads_are_dropped(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        convoy_id = self.make_group(alice, kind="convoy")
        self.add_member(convoy_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(convoy_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": convoy_id}),
            # Four candidates - one more than the sheet ever offers.
            json.dumps({
                "type": "spin_offer", "groupId": convoy_id,
                "candidates": [{"lat": 1.0, "lon": 2.0}] * 4,
            }),
            # Out-of-range latitude.
            json.dumps({
                "type": "spin_offer", "groupId": convoy_id,
                "candidates": [{"lat": 999.0, "lon": 2.0}],
            }),
            # Vote index outside the sheet's 0..2 slots.
            json.dumps({"type": "spin_vote", "groupId": convoy_id, "index": 3}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertEqual(
            [t for t in bob_ws.sent_types() if t.startswith("spin_")], [],
            "a malformed spin frame must be dropped, not relayed",
        )


# --------------------------------------------------------------------------
# stale `joined` entries - eviction from one of a multi-group socket's
# groups must be authoritative for every frame type, not just `location`.
# `_evict` leaves the socket open when the user is still valid elsewhere,
# but handle_live_socket's local `joined` cache doesn't hear about it on its
# own; _still_registered is the fix, checked before relaying any non-join
# frame.


class StillRegisteredTests(unittest.TestCase):
    """Unit-level coverage of the primitive itself, independent of the full
    socket flow below."""

    def setUp(self):
        S._group_sockets.clear()

    def test_true_while_the_registry_still_matches(self):
        ws = object()
        S._group_join(1, 2, "alice", ws, "hash")
        self.assertTrue(S._still_registered(1, 2, ws))

    def test_false_once_evicted(self):
        ws = object()
        S._group_join(1, 2, "alice", ws, "hash")
        S._group_part(1, 2, ws)
        self.assertFalse(S._still_registered(1, 2, ws))

    def test_false_when_never_joined(self):
        self.assertFalse(S._still_registered(1, 2, object()))


class StaleJoinedEntryTests(AsyncTestCase):
    async def test_ptt_after_eviction_from_one_of_two_groups_relays_to_nobody(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        convoy_id = self.make_group(alice, kind="convoy")
        self.add_member(circle_id, bob["id"])
        self.add_member(convoy_id, bob["id"])
        # bob is a peer in *both* groups, so any leak of the evicted
        # membership shows up directly on his socket.
        bob_ws = FakeWebSocket("n/a")
        S._group_join(circle_id, bob["id"], "bob", bob_ws, "bobhash")
        S._group_join(convoy_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        alice_ws = QueueWebSocket(token)
        task = asyncio.create_task(S.handle_live_socket(alice_ws))
        try:
            await alice_ws.push({"type": "join", "groupId": circle_id})
            await alice_ws.push({"type": "join", "groupId": convoy_id})
            await _wait_for_sent_count(alice_ws, 2)  # both "joined" replies back

            # "Another device" leaves the convoy - the real path is
            # do_group_leave -> evict_group_member -> _evict. The socket
            # must stay open (it's still valid in the circle) but the
            # convoy membership must stop being live on it.
            await S._evict(convoy_id, alice["id"])
            # bob is also a peer in convoy_id, so its bucket still exists -
            # what must be gone is *alice's* entry in it.
            self.assertNotIn(alice["id"], S._group_sockets.get(convoy_id, {}))
            self.assertIn(alice["id"], S._group_sockets.get(circle_id, {}))
            self.assertFalse(alice_ws.closed, "still valid in the circle - must stay open")

            # Alice's client doesn't know it was evicted and keeps sending
            # PTT for the convoy group id it thinks it's still joined to.
            await alice_ws.push({"type": "ptt_start", "groupId": convoy_id})
            await alice_ws.push({"type": "ptt_audio", "groupId": convoy_id, "chunk": "aGVsbG8="})
            await alice_ws.push({"type": "ptt_end", "groupId": convoy_id})
            # A harmless rejoin of the circle as a synchronization marker:
            # once its "joined" reply lands, the three PTT frames pushed
            # just before it are guaranteed to have already been processed,
            # since one socket's frames are handled strictly in order.
            await alice_ws.push({"type": "join", "groupId": circle_id})
            await _wait_for_sent_count(alice_ws, 3)

            # "left" (from _evict's own broadcast) is expected; no ptt_*
            # frame of any kind is what's actually under test.
            self.assertEqual(
                [t for t in bob_ws.sent_types() if t.startswith("ptt_")], [],
                "a socket evicted from a group must not keep relaying PTT into it, "
                "even while it stays open for another group",
            )
        finally:
            await alice_ws.end()
            await task


# --------------------------------------------------------------------------
# server-side pause - a paused member's `location` frames are dropped at
# the relay, not merely stopped on their own client


class PauseTests(AsyncTestCase):
    async def test_paused_members_location_is_dropped_and_not_stored(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        self.add_member(circle_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(circle_id, bob["id"], "bob", bob_ws, "bobhash")

        S.do_group_sharing(alice, circle_id, {"sharing": False})

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": circle_id}),
            json.dumps({"type": "location", "groupId": circle_id, "lat": 1.0, "lon": 2.0, "ts": 111}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertNotIn(
            "location", bob_ws.sent_types(), "a paused member's location must not reach peers"
        )
        row = S.db().execute(
            "SELECT 1 FROM member_last_fix WHERE group_id = ? AND user_id = ?",
            (circle_id, alice["id"]),
        ).fetchone()
        self.assertIsNone(row, "a dropped frame must not update member_last_fix either")

    async def test_sharing_members_location_is_relayed_and_stored(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        self.add_member(circle_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(circle_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": circle_id}),
            json.dumps({"type": "location", "groupId": circle_id, "lat": 1.0, "lon": 2.0, "ts": 111}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertIn("location", bob_ws.sent_types())
        row = S.db().execute(
            "SELECT lat, lon FROM member_last_fix WHERE group_id = ? AND user_id = ?",
            (circle_id, alice["id"]),
        ).fetchone()
        self.assertIsNotNone(row, "a circle's location frame must persist to member_last_fix")
        self.assertEqual(row["lat"], 1.0)
        self.assertEqual(row["lon"], 2.0)

    async def test_convoy_location_is_relayed_but_never_stored(self):
        """Convoy rows default sharing=1 and the relay ignores the column for
        them either way - the invariant that matters here is that a convoy's
        position still never touches SQLite."""
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        convoy_id = self.make_group(alice, kind="convoy")
        self.add_member(convoy_id, bob["id"])
        bob_ws = FakeWebSocket("n/a")
        S._group_join(convoy_id, bob["id"], "bob", bob_ws, "bobhash")

        token = S.issue_token(alice["id"])
        frames = [
            json.dumps({"type": "join", "groupId": convoy_id}),
            json.dumps({"type": "location", "groupId": convoy_id, "lat": 1.0, "lon": 2.0, "ts": 111}),
        ]
        alice_ws = FakeWebSocket(token, frames)
        await S.handle_live_socket(alice_ws)

        self.assertIn("location", bob_ws.sent_types())
        row = S.db().execute(
            "SELECT 1 FROM member_last_fix WHERE group_id = ? AND user_id = ?",
            (convoy_id, alice["id"]),
        ).fetchone()
        self.assertIsNone(row, "a convoy's position must never be persisted")


# --------------------------------------------------------------------------
# circle place_event fan-out - the live relay frame + the HTTP catch-up path
# must word a notification identically (docs' requirement, see
# broadcast_place_event and do_circle_events)


class PlaceEventHttpTests(SyncTestCase):
    def test_events_include_place_name_from_circle_places(self):
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        self.add_member(circle_id, bob["id"])
        S.do_circle_place_share(
            alice,
            {"groupId": circle_id, "place": {"id": 3, "name": "Home", "lat": 1.0, "lon": 2.0, "radiusM": 50}},
        )
        S.do_circle_event_create(bob, circle_id, {"placeId": 3, "kind": "depart", "ts": 999})

        out = S.do_circle_events(alice, circle_id, {})
        self.assertEqual(len(out["events"]), 1)
        ev = out["events"][0]
        self.assertEqual(ev["placeName"], "Home")
        self.assertEqual(ev["username"], "bob")
        self.assertEqual(ev["kind"], "depart")
        self.assertEqual(ev["placeId"], 3)

    def test_events_place_name_is_empty_once_the_place_row_is_gone(self):
        """A place shared, then unshared, then a stale transition for it
        still arrives - do_circle_event_create doesn't validate placeId
        against circle_places at all, so this is a real path, not a
        hypothetical."""
        alice = self.make_user("alice")
        circle_id = self.make_group(alice, kind="circle")
        S.do_circle_event_create(alice, circle_id, {"placeId": 999, "kind": "arrive", "ts": 1})

        out = S.do_circle_events(alice, circle_id, {})
        self.assertEqual(out["events"][0]["placeName"], "")

    def test_event_create_succeeds_with_no_live_relay(self):
        """`_live_loop` is None by default (DBFixtureMixin.setUp) - the
        common case for a process that never started run_live_server, or
        where `websockets` isn't installed. Must not turn into a 500."""
        alice = self.make_user("alice")
        circle_id = self.make_group(alice, kind="circle")
        result = S.do_circle_event_create(alice, circle_id, {"placeId": 1, "kind": "arrive", "ts": 1})
        self.assertEqual(result, {"status": "recorded"})

    def test_event_create_survives_a_dead_relay_loop(self):
        """A closed loop makes run_coroutine_threadsafe raise RuntimeError
        synchronously (unlike a merely-empty registry, which _group_broadcast
        already no-ops on) - broadcast_place_event's try/except is the thing
        actually under test here."""
        alice = self.make_user("alice")
        circle_id = self.make_group(alice, kind="circle")
        dead_loop = asyncio.new_event_loop()
        dead_loop.close()
        S._live_loop = dead_loop
        result = S.do_circle_event_create(alice, circle_id, {"placeId": 1, "kind": "arrive", "ts": 1})
        self.assertEqual(result, {"status": "recorded"})


class PlaceEventBroadcastTests(AsyncTestCase):
    async def test_event_create_broadcasts_to_peers_excluding_the_sender(self):
        S._live_loop = asyncio.get_running_loop()
        alice = self.make_user("alice")
        bob = self.make_user("bob")
        circle_id = self.make_group(alice, kind="circle")
        self.add_member(circle_id, bob["id"])
        alice_ws = FakeWebSocket("n/a")
        bob_ws = FakeWebSocket("n/a")
        S._group_join(circle_id, alice["id"], "alice", alice_ws, "ahash")
        S._group_join(circle_id, bob["id"], "bob", bob_ws, "bhash")
        S.do_circle_place_share(
            alice,
            {"groupId": circle_id, "place": {"id": 7, "name": "School", "lat": 1.0, "lon": 2.0, "radiusM": 100}},
        )

        S.do_circle_event_create(alice, circle_id, {"placeId": 7, "kind": "arrive", "ts": 555})
        await _wait_for_sent_count(bob_ws, 1)

        self.assertEqual(
            alice_ws.sent, [], "the mover's own socket must not get its own event back"
        )
        frame = json.loads(bob_ws.sent[0])
        self.assertEqual(
            frame,
            {
                "type": "place_event",
                "groupId": circle_id,
                "placeId": 7,
                "placeName": "School",
                "user": "alice",
                "kind": "arrive",
                "tsMs": 555,
            },
        )


if __name__ == "__main__":
    unittest.main()
