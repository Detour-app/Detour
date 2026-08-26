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

    private fun event(username: String, tsMs: Long, kind: String = "arrive", placeName: String = "Home") =
        PlaceEvent(id = "e-$username-$tsMs", placeId = 1L, placeName = placeName, username = username, kind = kind, tsMs = tsMs)

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
            myUsername = "me",
            nowMs = 1_000L,
        )
        assertEquals(listOf("alice"), plan.individual.map { it.username })
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun anythingOlderThanStaleAfterMsIsExcluded() {
        val now = 10_000_000L
        val fresh = event("alice", now - 1_000L)
        val stale = event("bob", now - CircleNotifyPolicy.STALE_AFTER_MS - 1L)
        val plan = CircleNotifyPolicy.planCatchUp(listOf(fresh, stale), myUsername = "me", nowMs = now)
        assertEquals(listOf("alice"), plan.individual.map { it.username })
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun anEventExactlyAtTheStaleWindowIsStillFresh() {
        // "older than staleAfterMs", not "at least staleAfterMs old" - the
        // boundary sample itself must survive.
        val now = 10_000_000L
        val atTheEdge = event("alice", now - CircleNotifyPolicy.STALE_AFTER_MS)
        val plan = CircleNotifyPolicy.planCatchUp(listOf(atTheEdge), myUsername = "me", nowMs = now)
        assertEquals(listOf("alice"), plan.individual.map { it.username })
    }

    // --- planCatchUp: the cap boundary, both directions ------------------------

    @Test
    fun everyEventIsShownIndividuallyAtExactlyTheCap() {
        val now = 10_000_000L
        val events = (1..CircleNotifyPolicy.NOTIFY_CAP).map { event("user$it", now - it) }
        val plan = CircleNotifyPolicy.planCatchUp(events, myUsername = "me", nowMs = now)
        // At the cap exactly: no summary notification at all.
        assertEquals(CircleNotifyPolicy.NOTIFY_CAP, plan.individual.size)
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun oneEventPastTheCapCollapsesIntoASummaryOfOne() {
        val now = 10_000_000L
        val total = CircleNotifyPolicy.NOTIFY_CAP + 1
        val events = (0 until total).map { i -> event("user$i", now - (total - i) * 1_000L) }
        val plan = CircleNotifyPolicy.planCatchUp(events, myUsername = "me", nowMs = now)
        // One past the cap: a summary notification now exists, for exactly one event.
        assertEquals(CircleNotifyPolicy.NOTIFY_CAP, plan.individual.size)
        assertEquals(1, plan.collapsedCount)
    }

    // --- planCatchUp: newest-first, the one deliberate behaviour change --------

    @Test
    fun theKeptIndividualsAreNewestFirst() {
        // Android's own copy of this planner posted oldest-first: an
        // ascending sortedBy + takeLast keeps the retained events in their
        // original (ascending) order. Shared raises newest-first instead -
        // the cap exists precisely because a backlog isn't worth reading in
        // full, so the item most worth seeing should not sit under four
        // older ones. Pinned here rather than only described in a comment,
        // so a later edit that reverts to Android's order fails a test
        // instead of just contradicting a doc.
        val now = 10_000_000L
        val total = CircleNotifyPolicy.NOTIFY_CAP + 3
        val events = (0 until total).map { i -> event("user$i", now - (total - i) * 1_000L) }
        val plan = CircleNotifyPolicy.planCatchUp(events, myUsername = "me", nowMs = now)

        // user(total-1) has the largest tsMs - the single most recent arrival.
        val expected = (total - 1 downTo total - CircleNotifyPolicy.NOTIFY_CAP).map { "user$it" }
        assertEquals(expected, plan.individual.map { it.username })
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
