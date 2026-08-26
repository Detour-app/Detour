package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the state transitions in FriendsStore.kt, ConvoysStore.kt and
 * CirclesStore.kt — the load/busy/error bookkeeping that used to be written
 * once per platform inside a composable and a Swift ObservableObject.
 *
 * These test the reducer, not the network: `Http` is a concrete Ktor client
 * with no seam, so what is testable here is exactly the part that was
 * duplicated. The properties below are the ones a screen visibly gets wrong
 * when they are missing — a spinner that never stops, and a list that blanks
 * itself because a refresh failed.
 */
class StoresTest {

    private fun lists() = FriendLists(
        friends = listOf("ada", "grace"),
        incoming = listOf("linus"),
        outgoing = emptyList(),
    )

    private fun stats(name: String) = FriendStats(
        username = name,
        stats = RiderStats(
            totalDistanceMeters = 1_234.0,
            topSpeedKmh = 98.0,
            longestTripMeters = 800.0,
            maxLeanDeg = 32.0,
            municipalitiesVisited = 3,
            bestCoveragePercent = 12.5,
            tripCount = 7,
        ),
        badgeIds = listOf("first-ride"),
    )

    // --- FriendsStore -----------------------------------------------------

    @Test
    fun aStartedActionIsBusyAndHasNoStaleErrorOnIt() {
        val before = FriendsState(error = "the last attempt failed")
        val started = before.starting()
        assertTrue(started.busy)
        // A new attempt must not show the previous attempt's error underneath
        // its own spinner.
        assertNull(started.error)
    }

    @Test
    fun aFailedActionKeepsTheDataItAlreadyHad() {
        val loaded = FriendsState(lists = lists(), leaderboard = listOf(stats("ada")))
        val failed = loaded.starting().failed(RuntimeException("no route to host"))
        // The whole point: an error banner over the last-known-good list, not
        // an empty screen. A refresh that fails must not destroy what is on
        // screen.
        assertEquals(loaded.lists, failed.lists)
        assertEquals(loaded.leaderboard, failed.leaderboard)
        assertEquals("no route to host", failed.error)
    }

    @Test
    fun busyClearsOnTheFailurePathToo() {
        // The bug both platforms' hand-rolled `act` helpers were written to
        // avoid, and which neither of them had a test for: a spinner that
        // never stops because only the success path cleared it.
        val failed = FriendsState().starting().failed(RuntimeException("nope"))
        assertTrue(!failed.busy)
    }

    @Test
    fun anExceptionWithNoMessageStillSaysSomething() {
        val failed = FriendsState().starting().failed(RuntimeException())
        assertEquals(FriendsStore.FALLBACK_ERROR, failed.error)
    }

    @Test
    fun aSuccessfulLoadClearsBusyAndError() {
        val loaded = FriendsState(error = "stale")
            .starting()
            .loaded(lists(), listOf(stats("ada")))
        assertTrue(!loaded.busy)
        assertNull(loaded.error)
        assertEquals(2, loaded.lists!!.friends.size)
    }

    @Test
    fun reloadingDoesNotDiscardAnOwnRowAlreadyComputed() {
        // `own` comes from Coverage.compute(), which reads every trace on
        // disk. A list reload after every mutation must not throw it away and
        // make the leaderboard's "me" row flicker.
        val withOwn = FriendsState(own = stats("me"))
        val reloaded = withOwn.starting().loaded(lists(), listOf(stats("ada")))
        assertSame(withOwn.own, reloaded.own)
    }

    // --- ConvoysStore -----------------------------------------------------

    @Test
    fun aFailedConvoyActionKeepsTheConvoyList() {
        val loaded = ConvoysState(convoys = listOf(convoy("c1", "Sunday run")))
        val failed = loaded.starting().failed(RuntimeException("500"))
        assertEquals(loaded.convoys, failed.convoys)
        assertEquals("500", failed.error)
        assertTrue(!failed.busy)
    }

    private fun convoy(id: String, name: String) = Group(
        id = id, name = name, kind = "convoy", status = "accepted", members = emptyList(),
    )
}
