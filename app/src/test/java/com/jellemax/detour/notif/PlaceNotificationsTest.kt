package com.jellemax.detour.notif

import com.jellemax.detour.data.PlaceEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [PlaceNotifications.planCatchUp] - the guard against a catch-up
 * batch flooding notifications after a long offline stretch (self events
 * dropped, stale ones dropped, at most [PlaceNotifications.NOTIFY_CAP] shown
 * individually). No Android APIs involved, so no emulator/Robolectric needed
 * - see the doc comment on the function itself for what each rule is for.
 */
class PlaceNotificationsTest {

    private fun event(id: Long, username: String, tsMs: Long, kind: String = "arrive") =
        PlaceEvent(id = id, placeId = 1L, placeName = "Home", username = username, kind = kind, tsMs = tsMs)

    @Test
    fun dropsTheCallersOwnEvents() {
        val plan = PlaceNotifications.planCatchUp(
            events = listOf(event(1, "me", 1_000L), event(2, "alice", 1_000L)),
            myUsername = "me",
            nowMs = 1_000L,
        )
        assertEquals(listOf("alice"), plan.individual.map { it.username })
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun dropsEventsOlderThanTheStaleWindow() {
        val now = 10_000_000L
        val fresh = event(1, "alice", now - 1_000L)
        val stale = event(2, "bob", now - PlaceNotifications.STALE_AFTER_MS - 1L)
        val plan = PlaceNotifications.planCatchUp(listOf(fresh, stale), myUsername = "me", nowMs = now)
        assertEquals(listOf("alice"), plan.individual.map { it.username })
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun showsEveryEventIndividuallyWhenAtOrUnderTheCap() {
        val now = 10_000_000L
        val events = (1..PlaceNotifications.NOTIFY_CAP).map { event(it.toLong(), "user$it", now - it) }
        val plan = PlaceNotifications.planCatchUp(events, myUsername = "me", nowMs = now)
        assertEquals(PlaceNotifications.NOTIFY_CAP, plan.individual.size)
        assertEquals(0, plan.collapsedCount)
    }

    @Test
    fun collapsesEverythingPastTheCapIntoASummaryCount() {
        val now = 10_000_000L
        val total = PlaceNotifications.NOTIFY_CAP + 3
        // Oldest first, one second apart, all comfortably within the stale window.
        val events = (0 until total).map { i -> event(i.toLong(), "user$i", now - (total - i) * 1_000L) }
        val plan = PlaceNotifications.planCatchUp(events, myUsername = "me", nowMs = now)
        assertEquals(PlaceNotifications.NOTIFY_CAP, plan.individual.size)
        assertEquals(3, plan.collapsedCount)
        // The ones actually shown are the most recent, not an arbitrary cut.
        assertEquals("user${total - 1}", plan.individual.last().username)
    }
}
