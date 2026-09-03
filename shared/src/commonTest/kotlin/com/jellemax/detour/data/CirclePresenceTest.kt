package com.jellemax.detour.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers CirclePresence.kt: circles' second sink on whatever fix a
 * platform's own location collector just produced, previously duplicated
 * bit for bit between `TripTrackingService.circleSyncLoop` (Android) and
 * `CircleSync.loop` (iOS) — same constants, same guards, same evaluator
 * retain.
 *
 * `tick` itself reaches the network through `Groups`, `CircleFixes`,
 * `CirclePlaces` and `CircleEvents`, none of which have a test seam (`Http`
 * is a concrete Ktor client, same as StoresTest's own note), so what's
 * tested here is every *decision* `tick` makes, pulled out as `internal`
 * pure functions it delegates to: [CirclePresence.planTick] (cadence, and
 * the failed-fetch asymmetry), [CirclePresence.sharingCircles] (the
 * membership filter), [CirclePresence.retainJoinedCircles] (evaluator
 * cleanup), [CirclePresence.isFixTrusted] (the staleness gate) and
 * [CirclePresence.evaluateGeofences]/[CirclePresence.sessionChanged] (which
 * clock and which epoch actually drive an outcome) — plus
 * [CirclePresence.discardEvaluatorsIfSessionChanged], the wiring that reads
 * the real epoch and acts on that rule, which is the whole reason this file
 * exists: a slice built to stop rider state leaking across a sign-out has to
 * prove the discard actually happens, not only that the rule computes.
 */
class CirclePresenceTest {

    // --- fixtures ---------------------------------------------------------

    // The id is deliberately not the handle: sharingCircles keys off id, and a
    // fixture that built both from the same string could not tell that
    // comparison apart from the old, buggy one keyed on username - see #133.
    private fun member(username: String, sharing: Boolean, status: String = "accepted") =
        GroupMember(id = RiderId("$username-id"), username = username, status = status, sharing = sharing)

    private val meId = RiderId("me-id")

    private fun circle(id: String, vararg members: GroupMember) =
        Group(id = id, name = id, kind = "circle", status = "accepted", members = members.toList())

    private fun place(id: Long, lat: Double, lon: Double, radiusM: Double = 50.0) = CirclePlace(
        serverId = "share-$id",
        groupId = "c",
        ownerId = RiderId("someone"),
        radiusM = radiusM,
        createdMs = 0L,
        place = SavedPlace(id = id, name = "Place $id", location = LatLon(lat, lon)),
    )

    // --- planTick: cadence switches only on a successful fetch -------------

    @Test
    fun cadenceGoesIdleTheMomentNobodyHereIsSharing() {
        val circles = listOf(circle("c1", member("me", sharing = false)))
        val plan = CirclePresence.planTick(CirclePresence.ACTIVE_INTERVAL_MS, circles, meId)
        assertEquals(CirclePresence.IDLE_INTERVAL_MS, plan.intervalMs)
        assertTrue(plan.sharing.isEmpty())
    }

    @Test
    fun cadenceComesBackActiveTheMomentSomebodyHereSharesAgain() {
        val circles = listOf(circle("c1", member("me", sharing = true)))
        val plan = CirclePresence.planTick(CirclePresence.IDLE_INTERVAL_MS, circles, meId)
        assertEquals(CirclePresence.ACTIVE_INTERVAL_MS, plan.intervalMs)
        assertEquals(circles, plan.sharing)
    }

    @Test
    fun aFailedCircleListLeavesTheIntervalUnchangedWhicheverItWas() {
        // The deliberate asymmetry: an outage is not evidence that nobody is
        // sharing, so a failed fetch (circles == null, standing in for
        // Groups.list throwing) must not relax the cadence the way an
        // honest "nobody's sharing" answer does. Proven with two different
        // "previous" values, not just one, so this can't pass by coincidence
        // of always returning the same hardcoded interval.
        val fromActive = CirclePresence.planTick(CirclePresence.ACTIVE_INTERVAL_MS, circles = null, myId = meId)
        val fromIdle = CirclePresence.planTick(CirclePresence.IDLE_INTERVAL_MS, circles = null, myId = meId)
        assertEquals(CirclePresence.ACTIVE_INTERVAL_MS, fromActive.intervalMs)
        assertEquals(CirclePresence.IDLE_INTERVAL_MS, fromIdle.intervalMs)
        // Nothing to post to on a failed fetch either.
        assertTrue(fromActive.sharing.isEmpty())
    }

    @Test
    fun anEmptyCircleListIsAlsoIdleNotAFailure() {
        // circles == emptyList() (genuinely in no circles) is not the same
        // case as circles == null (the fetch failed) - both end up idle,
        // but for different reasons, and only the second leaves the
        // interval where it was regardless of what that was.
        val plan = CirclePresence.planTick(CirclePresence.ACTIVE_INTERVAL_MS, circles = emptyList(), myId = meId)
        assertEquals(CirclePresence.IDLE_INTERVAL_MS, plan.intervalMs)
    }

    // --- sharingCircles: this device's own row, not anyone else's ----------

    @Test
    fun onlyThisDevicesOwnMemberRowCounts() {
        // Someone else in the circle sharing does not make this device
        // "sharing" - the cadence and the post loop both key off "me".
        val circles = listOf(circle("c1", member("me", sharing = false), member("alice", sharing = true)))
        assertTrue(CirclePresence.sharingCircles(circles, meId).isEmpty())
    }

    @Test
    fun aCircleThisDeviceIsNotAMemberOfAtAllDoesNotCount() {
        val circles = listOf(circle("c1", member("alice", sharing = true)))
        assertTrue(CirclePresence.sharingCircles(circles, meId).isEmpty())
    }

    @Test
    fun sharingCirclesKeepsExactlyTheOnesWithSharingOn() {
        val on = circle("on", member("me", sharing = true))
        val off = circle("off", member("me", sharing = false))
        assertEquals(listOf(on), CirclePresence.sharingCircles(listOf(on, off), meId))
    }

    @Test
    fun a_member_whose_handle_casing_differs_is_still_recognised_as_self() {
        // The server stores the handle in a citext column and only renames on a
        // case-insensitive difference, so its stored spelling can differ from the
        // token's. This test is the reason the comparison moved to an id: it was
        // unrepresentable before, because both sides were the same string by
        // construction.
        val circles = listOf(
            circle("c1", GroupMember(id = meId, username = "Andre", status = "accepted", sharing = true)),
        )

        assertEquals(circles, CirclePresence.sharingCircles(circles, myId = meId))
    }

    // --- retainJoinedCircles: evaluator cleanup on a left/rejoined circle --

    @Test
    fun evaluatorStateIsDroppedForACircleNoLongerJoined() {
        val stillJoined = GeofenceEvaluator.withDefaults()
        val left = GeofenceEvaluator.withDefaults()
        // Seed dwell state on the one about to be dropped, so this proves
        // more than "the key disappeared" - a rejoin under the same id
        // later would otherwise resume mid-dwell instead of starting fresh.
        left.evaluate(lat = 1.0, lon = 1.0, tsMs = 0L, places = listOf(place(1, 1.0, 1.0)))

        val retained = CirclePresence.retainJoinedCircles(
            evaluators = mapOf("keep" to stillJoined, "left" to left),
            circleIds = setOf("keep"),
        )

        assertEquals(setOf("keep"), retained.keys)
        assertTrue(retained.containsValue(stillJoined))
    }

    @Test
    fun retainJoinedCirclesIsANoOpWhenEverythingIsStillJoined() {
        val evaluators = mapOf("a" to GeofenceEvaluator.withDefaults(), "b" to GeofenceEvaluator.withDefaults())
        assertEquals(evaluators, CirclePresence.retainJoinedCircles(evaluators, setOf("a", "b")))
    }

    // --- isFixTrusted: the FIX_TRUST_MS boundary ----------------------------

    @Test
    fun aFixExactlyAtFixTrustMsIsStillTrusted() {
        // "older than trustMs", not "at least trustMs old" - the boundary
        // sample itself must still drive a geofence decision.
        assertTrue(CirclePresence.isFixTrusted(CirclePresence.FIX_TRUST_MS))
    }

    @Test
    fun aFixOneMillisecondOverFixTrustMsIsNotTrusted() {
        assertFalse(CirclePresence.isFixTrusted(CirclePresence.FIX_TRUST_MS + 1))
    }

    @Test
    fun trustMovesWithFixAgeMsAcrossTwoDistinctValues() {
        // Same call, two different fixAgeMs values, two different answers -
        // an ambient/frozen clock could not produce this.
        assertTrue(CirclePresence.isFixTrusted(1_000L))
        assertFalse(CirclePresence.isFixTrusted(CirclePresence.FIX_TRUST_MS * 10))
    }

    // --- evaluateGeofences: dwell is driven by the passed-in nowMs ---------

    @Test
    fun dwellDoesNotFireBeforeItsOwnMinimumHasElapsed() {
        val evaluator = GeofenceEvaluator.withDefaults()
        val places = listOf(place(1, lat = 10.0, lon = 10.0, radiusM = 100.0))
        // First fix inside the radius, at nowMs = 0: only starts the dwell
        // clock, nothing fires yet.
        val first = CirclePresence.evaluateGeofences(evaluator, 10.0, 10.0, nowMs = 0L, places = places)
        assertTrue(first.isEmpty())
        // Still inside, but only 1ms later - nowhere near the evaluator's
        // own minimum dwell - must still not have arrived.
        val secondEarly = CirclePresence.evaluateGeofences(evaluator, 10.0, 10.0, nowMs = 1L, places = places)
        assertTrue(secondEarly.isEmpty())
    }

    @Test
    fun dwellFiresOnceNowMsHasAdvancedPastTheMinimum() {
        // Same evaluator, same position - the only thing that changes
        // between "no arrival yet" and "arrival" in this test is nowMs,
        // which pins that dwell is driven by the parameter this function
        // takes, not by any ambient clock (there isn't one in commonMain to
        // begin with).
        val evaluator = GeofenceEvaluator.withDefaults()
        val places = listOf(place(1, lat = 10.0, lon = 10.0, radiusM = 100.0))
        CirclePresence.evaluateGeofences(evaluator, 10.0, 10.0, nowMs = 0L, places = places)
        val later = CirclePresence.evaluateGeofences(evaluator, 10.0, 10.0, nowMs = 60_000L, places = places)
        assertEquals(listOf(GeofenceKind.ARRIVE), later.map { it.kind })
        // The transition is stamped with the nowMs it fired at, not fix time.
        assertEquals(60_000L, later.single().tsMs)
    }

    // --- sessionChanged: never the first tick, never a mere reconnect ------

    @Test
    fun theFirstEverTickIsNeverTreatedAsASessionChange() {
        assertFalse(CirclePresence.sessionChanged(previousEpoch = null, currentEpoch = 0))
    }

    @Test
    fun anUnmovedEpochIsAReconnectNotASessionChange() {
        assertFalse(CirclePresence.sessionChanged(previousEpoch = 3, currentEpoch = 3))
    }

    @Test
    fun anAdvancedEpochIsASessionChange() {
        // Sign-out, a 401, or a fresh sign-in as someone else all bump
        // Auth.sessionEpoch the same way - this only needs to see the
        // number move, not which of those it was.
        assertTrue(CirclePresence.sessionChanged(previousEpoch = 3, currentEpoch = 4))
    }

    // --- discardEvaluatorsIfSessionChanged: the wiring, not just the rule --
    //
    // sessionChanged above is the decision; this is the half that reads the
    // real Auth.sessionEpoch and acts on it, and until these two tests
    // existed, negating its `if` broke nothing. Driven by setting
    // lastSeenEpoch by hand rather than by making Auth.sessionEpoch actually
    // move - the same shortcut ConvoyRelayTest takes with membershipEpoch,
    // for the same reason: bumping the real epoch means Auth.store/Auth.clear,
    // which write Settings, which this module's tests stay isolated from.

    @AfterTest
    fun leaveTheSessionStateAsAColdStartFindsIt() {
        // CirclePresence is an object, so both fields outlive the test that
        // set them - and a leftover lastSeenEpoch would make the *next*
        // test's session change look like a first-ever tick.
        CirclePresence.evaluators = emptyMap()
        CirclePresence.lastSeenEpoch = null
    }

    @Test
    fun aSessionChangeDiscardsTheDwellStateEveryCircleHadAccumulated() {
        val evaluator = GeofenceEvaluator.withDefaults()
        // Mid-dwell inside a place: the exact state that must not be waiting
        // for whoever signs in next, since resuming it would fire an arrival
        // the new rider never made.
        evaluator.evaluate(lat = 10.0, lon = 10.0, tsMs = 0L, places = listOf(place(1, 10.0, 10.0, radiusM = 100.0)))
        CirclePresence.evaluators = mapOf("c1" to evaluator)
        CirclePresence.lastSeenEpoch = Auth.sessionEpoch.value - 1

        CirclePresence.discardEvaluatorsIfSessionChanged()

        assertTrue(CirclePresence.evaluators.isEmpty())
        assertEquals(Auth.sessionEpoch.value, CirclePresence.lastSeenEpoch)
    }

    @Test
    fun anUnmovedEpochKeepsTheDwellStateSoAnArrivalCanStillFire() {
        // The other half, and why this checks the epoch instead of clearing
        // on every tick: dwell accumulates across ticks, so a tick that
        // discarded it unconditionally could never reach the minimum and
        // "arrive" would never fire at all.
        val evaluator = GeofenceEvaluator.withDefaults()
        CirclePresence.evaluators = mapOf("c1" to evaluator)
        CirclePresence.lastSeenEpoch = Auth.sessionEpoch.value

        CirclePresence.discardEvaluatorsIfSessionChanged()

        assertEquals(mapOf("c1" to evaluator), CirclePresence.evaluators)
    }
}
