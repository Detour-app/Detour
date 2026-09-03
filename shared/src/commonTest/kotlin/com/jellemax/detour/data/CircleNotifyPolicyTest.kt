package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers CircleNotifyPolicy.kt: which caught-up circle arrivals are worth
 * raising as a notification, and which circles currently want delivery at
 * all. Ported from two independent copies that only ever agreed by
 * construction - Android's `PlaceNotifications.planCatchUp` (already a pure
 * planner) and iOS's `CircleNotifications.runCatchUpSweep`, which hand-rolled
 * the same three filters inline - so this is the first place either is
 * actually proven to behave as documented, the same role RelayProtocolTest
 * plays for the relay wire format.
 */
class CircleNotifyPolicyTest {

    // --- fixtures -------------------------------------------------------------

    private val me = RiderId("me")

    private fun event(username: String, tsMs: Long, kind: String = "arrive", placeName: String = "Home") =
        PlaceEvent(id = "e-$username-$tsMs", placeId = 1L, placeName = placeName, riderId = RiderId(username), kind = kind, tsMs = tsMs)

    private fun group(id: String, status: String = "accepted") =
        Group(id = id, name = id, kind = "circle", status = status, members = emptyList())

    // --- planCatchUp: which events survive at all ------------------------------

    @Test
    fun theRidersOwnTransitionsAreExcluded() {
        // GET /circles/{id}/events returns the caller's own arrivals by
        // design (CircleEvents' own doc) - nobody needs telling where they
        // themselves went.
        val plan = CircleNotifyPolicy.planCatchUp(
            events = listOf(event("me", 1_000L), event("alice", 1_000L)),
            myId = me,
            nowMs = 1_000L,
        )
        assertEquals(listOf("alice"), plan.individual.map { it.riderId.value })
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun anythingOlderThanStaleAfterMsIsExcluded() {
        val now = 10_000_000L
        val fresh = event("alice", now - 1_000L)
        val stale = event("bob", now - CircleNotifyPolicy.STALE_AFTER_MS - 1L)
        val plan = CircleNotifyPolicy.planCatchUp(listOf(fresh, stale), myId = me, nowMs = now)
        assertEquals(listOf("alice"), plan.individual.map { it.riderId.value })
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun anEventExactlyAtTheStaleWindowIsStillFresh() {
        // "older than staleAfterMs", not "at least staleAfterMs old" - the
        // boundary sample itself must survive.
        val now = 10_000_000L
        val atTheEdge = event("alice", now - CircleNotifyPolicy.STALE_AFTER_MS)
        val plan = CircleNotifyPolicy.planCatchUp(listOf(atTheEdge), myId = me, nowMs = now)
        assertEquals(listOf("alice"), plan.individual.map { it.riderId.value })
    }

    // --- planCatchUp: the cap boundary, both directions ------------------------

    @Test
    fun everyEventIsShownIndividuallyAtExactlyTheCap() {
        val now = 10_000_000L
        val events = (1..CircleNotifyPolicy.NOTIFY_CAP).map { event("user$it", now - it) }
        val plan = CircleNotifyPolicy.planCatchUp(events, myId = me, nowMs = now)
        // At the cap exactly: no summary notification at all.
        assertEquals(CircleNotifyPolicy.NOTIFY_CAP, plan.individual.size)
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun oneEventPastTheCapCollapsesIntoASummaryOfOne() {
        val now = 10_000_000L
        val total = CircleNotifyPolicy.NOTIFY_CAP + 1
        val events = (0 until total).map { i -> event("user$i", now - (total - i) * 1_000L) }
        val plan = CircleNotifyPolicy.planCatchUp(events, myId = me, nowMs = now)
        // One past the cap: a summary notification now exists, for exactly one event.
        assertEquals(CircleNotifyPolicy.NOTIFY_CAP, plan.individual.size)
        assertEquals(1, plan.collapsedCount)
    }

    // --- planCatchUp: newest-first selection, and what the cap collapsed -------

    @Test
    fun theKeptIndividualsAreNewestFirst() {
        // Newest-first is the *selection* order: when only five of eight can
        // be shown, these are the five worth showing. It is deliberately not
        // the delivery order - both platforms iterate this in reverse, since
        // neither tray sorts what it is given (see planCatchUp's own doc).
        // Pinned here rather than only described in a comment, so a later
        // edit that flips the selection fails a test instead of just
        // contradicting a doc - and silently reverses both trays, since both
        // reverse whatever this hands them.
        val now = 10_000_000L
        val total = CircleNotifyPolicy.NOTIFY_CAP + 3
        val events = (0 until total).map { i -> event("user$i", now - (total - i) * 1_000L) }
        val plan = CircleNotifyPolicy.planCatchUp(events, myId = me, nowMs = now)

        // user(total-1) has the largest tsMs - the single most recent arrival.
        val expected = (total - 1 downTo total - CircleNotifyPolicy.NOTIFY_CAP).map { "user$it" }
        assertEquals(expected, plan.individual.map { it.riderId.value })
        // Three past the cap, not one: without a second value here the whole
        // `relevant.size - cap` arithmetic passes with the literal 1, which
        // the cap+1 case above cannot tell apart. This assertion came over
        // from the deleted PlaceNotificationsTest, which is where it was
        // lost in the move.
        assertEquals(3, plan.collapsedCount)
    }

    // --- circlesWantingDelivery -------------------------------------------------

    @Test
    fun onlyAcceptedCirclesWantDelivery() {
        val circles = listOf(group("invited-1", status = "invited"), group("accepted-1", status = "accepted"))
        val ids = CircleNotifyPolicy.circlesWantingDelivery(circles) { true }
        assertEquals(setOf("accepted-1"), ids)
    }

    @Test
    fun aCircleWithTheToggleOffIsExcludedEvenThoughAccepted() {
        val circles = listOf(group("muted"), group("loud"))
        val ids = CircleNotifyPolicy.circlesWantingDelivery(circles) { id -> id != "muted" }
        assertEquals(setOf("loud"), ids)
    }

    @Test
    fun aCircleNobodyHasEverToggledStillWantsDelivery() {
        // Settings.notifyArrivals(id) - the real predicate this parameter
        // defaults to - reads a key that defaults to true when nobody has
        // written it, so a circle nobody has touched the switch for behaves
        // exactly like one explicitly turned on. Modelled here by a
        // predicate that, like the real one, says yes to every id it wasn't
        // told to say no to: the filter can never exclude a circle by
        // omission, only by an explicit opt-out.
        val circles = listOf(group("never-touched"), group("explicitly-muted"))
        val ids = CircleNotifyPolicy.circlesWantingDelivery(circles) { id -> id != "explicitly-muted" }
        assertEquals(setOf("never-touched"), ids)
    }
}
