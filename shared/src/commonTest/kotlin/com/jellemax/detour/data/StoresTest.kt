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
    fun aStartedConvoyActionIsBusyAndHasNoStaleErrorOnIt() {
        val before = ConvoysState(error = "the last attempt failed")
        val started = before.starting()
        assertTrue(started.busy)
        assertNull(started.error)
    }

    @Test
    fun aFailedConvoyActionKeepsTheConvoyList() {
        val loaded = ConvoysState(convoys = listOf(convoy("c1", "Sunday run")))
        val failed = loaded.starting().failed(RuntimeException("500"))
        assertEquals(loaded.convoys, failed.convoys)
        assertEquals("500", failed.error)
        assertTrue(!failed.busy)
    }

    @Test
    fun aSuccessfulConvoyLoadClearsBusyAndError() {
        val loaded = ConvoysState(error = "stale")
            .starting()
            .loaded(listOf(convoy("c1", "Sunday run")))
        assertTrue(!loaded.busy)
        assertNull(loaded.error)
        assertEquals(1, loaded.convoys.size)
    }

    @Test
    fun anActionsTwoOutcomesAreTheReducerHalfOfItsReturnValue() {
        // An action never throws for an ordinary failure any more — it
        // reports through `error` and returns null/false instead, so a
        // caller branches on the return value rather than racing to read
        // `state` back after an `await`. `act()`'s failure half is exactly
        // `starting().failed(e)` below, which is what makes it return null.
        // Its success half also calls through to `reload()` — a real
        // network round trip — so it isn't reachable here; `loaded()`
        // stands in for the transition that lets it return the value.
        val failure = ConvoysState().starting().failed(RuntimeException("no route to host"))
        assertEquals("no route to host", failure.error)
        val success = ConvoysState(error = "stale").starting().loaded(emptyList())
        assertNull(success.error)
    }

    private fun convoy(id: String, name: String) = Group(
        id = id, name = name, kind = "convoy", status = "accepted", members = emptyList(),
    )

    // --- CirclesStore -----------------------------------------------------

    private fun circle(id: String, name: String) = Group(
        id = id, name = name, kind = "circle", status = "accepted", members = emptyList(),
    )

    @Test
    fun selectingACircleThatIsNoLongerInTheListClearsTheSelection() {
        // A circle can vanish between the list load and the tap — someone
        // removed you, or you left it on another device. A detail pane
        // pointed at nothing is worse than no detail pane.
        val state = CirclesState(circles = listOf(circle("c1", "Family")), selectedId = "c1")
        val afterReload = state.loaded(listOf(circle("c2", "Riders")))
        assertNull(afterReload.selectedId)
    }

    @Test
    fun aStillPresentSelectionSurvivesAReload() {
        val state = CirclesState(circles = listOf(circle("c1", "Family")), selectedId = "c1")
        val afterReload = state.loaded(listOf(circle("c1", "Family"), circle("c2", "Riders")))
        assertEquals("c1", afterReload.selectedId)
    }

    @Test
    fun clearingTheSelectionDropsThePreviousCirclesPlacesAndEvents() {
        // Otherwise the next circle opened shows the last one's places for as
        // long as its own load takes — someone else's addresses under the
        // wrong heading.
        val viewing = CirclesState(
            circles = listOf(circle("c1", "Family")),
            selectedId = "c1",
            places = listOf(place("p1", "c1")),
            events = listOf(event("e1")),
        )
        val cleared = viewing.selecting(null)
        assertTrue(cleared.places.isEmpty())
        assertTrue(cleared.events.isEmpty())
        assertNull(cleared.selectedId)
    }

    @Test
    fun switchingCirclesDropsThePreviousCirclesDetail() {
        val viewing = CirclesState(
            circles = listOf(circle("c1", "Family"), circle("c2", "Riders")),
            selectedId = "c1",
            places = listOf(place("p1", "c1")),
        )
        val switched = viewing.selecting("c2")
        assertEquals("c2", switched.selectedId)
        assertTrue(switched.places.isEmpty())
    }

    @Test
    fun aFailedCircleActionKeepsTheCircleList() {
        val loaded = CirclesState(circles = listOf(circle("c1", "Family")))
        val failed = loaded.starting().failed(RuntimeException("403"))
        assertEquals(loaded.circles, failed.circles)
        assertEquals("403", failed.error)
        assertTrue(!failed.busy)
    }

    private fun place(serverId: String, groupId: String) = CirclePlace(
        serverId = serverId,
        groupId = groupId,
        owner = "ada",
        radiusM = 150.0,
        createdMs = 1_700_000_000_000L,
        place = SavedPlace(id = 1L, name = "Home", location = LatLon(51.0, 4.0)),
    )

    private fun event(id: String) = PlaceEvent(
        id = id,
        placeId = 1L,
        placeName = "Home",
        username = "ada",
        kind = "arrive",
        tsMs = 1_700_000_000_000L,
    )
}
