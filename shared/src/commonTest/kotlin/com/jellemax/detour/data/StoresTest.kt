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

    // --- FriendsStore.commitIfCurrent / refreshOwn's guard -----------------
    //
    // The identity guard that closes the cross-user leak: reload()'s and
    // refreshOwn()'s commit each capture Auth.sessionEpoch at the start of
    // the action and check it again here, against the epoch read fresh at
    // commit time, before writing. Not reproducible as a concurrency test in
    // this module's test style (no coroutine test dispatcher), so — same as
    // CirclesState.commitIfViewing above it — the commit decision is
    // asserted directly.

    @Test
    fun aReloadWhoseIdentityChangedMidFlightDoesNotCommit() {
        // Auth.clear() ran (sign-out, a 401, a server switch — or a
        // sign-back-in as the very same rider) while this reload's request
        // was in flight. The result it fetched belongs to a session that is
        // no longer current, so it must not overwrite whatever `reset()` (or
        // the new session) left behind.
        val postReset = FriendsState()
        val staleResult = FriendsState(lists = lists(), leaderboard = listOf(stats("ada")))
        assertSame(postReset, postReset.commitIfCurrent(epoch = 1, currentEpoch = 2, result = staleResult))
    }

    @Test
    fun aReloadWhoseIdentityIsUnchangedDoesCommit() {
        val inFlight = FriendsState(busy = true)
        val freshResult = FriendsState(lists = lists(), leaderboard = listOf(stats("ada")))
        assertSame(freshResult, inFlight.commitIfCurrent(epoch = 1, currentEpoch = 1, result = freshResult))
    }

    @Test
    fun refreshOwnForAStaleSessionDoesNotWriteTheOwnRow() {
        // Pins the exact shape refreshOwn's guard uses: `it.copy(own = own)`
        // as the candidate result, discarded the same way a stale reload's
        // result is — see refreshOwn's own doc for why there is no
        // suspension point here for a cancellation to interpose on instead.
        val postReset = FriendsState()
        val staleOwnRow = postReset.copy(own = stats("ada"))
        assertSame(postReset, postReset.commitIfCurrent(epoch = 1, currentEpoch = 2, result = staleOwnRow))
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
    fun aConvoyReloadWhoseIdentityChangedMidFlightDoesNotCommit() {
        val postReset = ConvoysState()
        val staleResult = ConvoysState(convoys = listOf(convoy("c1", "Sunday run")))
        assertSame(postReset, postReset.commitIfCurrent(epoch = 1, currentEpoch = 2, result = staleResult))
    }

    @Test
    fun aConvoyReloadWhoseIdentityIsUnchangedDoesCommit() {
        val inFlight = ConvoysState(busy = true)
        val freshResult = ConvoysState(convoys = listOf(convoy("c1", "Sunday run")))
        assertSame(freshResult, inFlight.commitIfCurrent(epoch = 1, currentEpoch = 1, result = freshResult))
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
    fun aSelectionMadeDuringAnInFlightReloadSurvivesTheReloadsCommit() {
        // Change C's bug: `CirclesScreen.kt`'s `LaunchedEffect(Unit) { reload() }`
        // runs before `LaunchedEffect(openCircleId) { selectOnly(it) }`, so a
        // deep-link selection (a tapped arrival notification) — or an
        // ordinary tap in the list — lands *while* a reload is in flight.
        // `reload()` used to build its `.loaded()` result off a `_state.value`
        // snapshot taken before its network awaits, so that selection got
        // silently reverted to whatever it was when the reload started —
        // `selectOnly`/`selecting()`'s write was there, `loaded()` just never
        // saw it. Composing `starting()` (the reload begins) then `selecting()`
        // (the selection lands mid-flight) then `loaded()` (the reload's
        // result, now applied to the live state per the fix) pins that the
        // selection survives the commit.
        val beforeReload = CirclesState(circles = listOf(circle("c1", "Family")))
        val reloadStarted = beforeReload.starting()
        val selectedMidFlight = reloadStarted.selecting("c1")
        val committed = selectedMidFlight.loaded(listOf(circle("c1", "Family"), circle("c2", "Riders")))
        assertEquals("c1", committed.selectedId)
    }

    @Test
    fun aCircleReloadWhoseIdentityChangedMidFlightDoesNotCommit() {
        val postReset = CirclesState()
        val staleResult = CirclesState(circles = listOf(circle("c1", "Family")))
        assertSame(postReset, postReset.commitIfCurrent(epoch = 1, currentEpoch = 2, result = staleResult))
    }

    @Test
    fun aCircleReloadWhoseIdentityIsUnchangedDoesCommit() {
        val inFlight = CirclesState(busy = true)
        val freshResult = CirclesState(circles = listOf(circle("c1", "Family")))
        assertSame(freshResult, inFlight.commitIfCurrent(epoch = 1, currentEpoch = 1, result = freshResult))
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

    @Test
    fun aDetailResponseForACircleNoLongerBeingViewedIsDiscarded() {
        // Tap one circle, tap another before the first answers: the slower
        // response arrives last and would otherwise write its places under the
        // newer circle's heading. Not reproducible as a concurrency test in
        // this module's test style, so the commit decision is asserted directly.
        val viewingC2 = CirclesState(
            circles = listOf(circle("c1", "Family"), circle("c2", "Riders")),
            selectedId = "c2",
        )
        val staleResult = viewingC2.copy(places = listOf(place("p1", "c1")))
        assertSame(viewingC2, viewingC2.commitIfViewing("c1", staleResult))
    }

    @Test
    fun aDetailResponseForTheCircleStillBeingViewedIsCommitted() {
        val viewingC2 = CirclesState(
            circles = listOf(circle("c2", "Riders")),
            selectedId = "c2",
        )
        val fresh = viewingC2.copy(places = listOf(place("p2", "c2")))
        assertSame(fresh, viewingC2.commitIfViewing("c2", fresh))
    }

    @Test
    fun aDetailResponseForTheSameCircleFromAStaleSessionIsStillDiscarded() {
        // loadDetail's actual guard, composed: commitIfViewing first, then
        // commitIfCurrent — see loadDetail's own doc for why a proxy alone
        // was not enough. The scenario this pins is exactly what
        // commitIfViewing alone cannot catch: a rider signs out and back in
        // as themselves and happens to land on the very same circle id, so
        // the proxy (selectedId == groupId) matches even though the session
        // that started this load is no longer the one the store holds.
        val postReset = CirclesState(circles = listOf(circle("c1", "Family")), selectedId = "c1")
        val staleResult = postReset.copy(places = listOf(place("p1", "c1")))

        // The viewing guard alone would let this through — proving the
        // epoch guard is what actually has to stop it.
        assertSame(staleResult, postReset.commitIfViewing("c1", staleResult))

        val guarded = postReset.commitIfCurrent(
            epoch = 1,
            currentEpoch = 2,
            result = postReset.commitIfViewing("c1", staleResult),
        )
        assertSame(postReset, guarded)
    }

    @Test
    fun aStartedDetailActionDoesNotSetTheListBusyFlag() {
        // Opening a circle or refreshing its detail must not disable
        // Invite, Leave or the sharing switch — those read the list `busy`,
        // not this one.
        val started = CirclesState().detailStarting()
        assertTrue(started.detailBusy)
        assertTrue(!started.busy)
    }

    @Test
    fun aStartedListActionDoesNotSetTheDetailBusyFlag() {
        // The other direction: a list mutation (invite, leave, sharing)
        // must not disable detail-pane controls like unshare.
        val started = CirclesState().starting()
        assertTrue(started.busy)
        assertTrue(!started.detailBusy)
    }

    @Test
    fun goingIdleStopsTheDetailSpinnerWithoutRaisingAnError() {
        // actDetail's null-selectedId branch and loadDetail's cancellation
        // branch both land here instead of detailFailed — neither is a
        // failure, just a busy flag with nothing left to clear it.
        val idle = CirclesState(detailBusy = true, detailError = "stale").detailIdle()
        assertTrue(!idle.detailBusy)
        assertEquals("stale", idle.detailError)
    }

    @Test
    fun reselectingTheSameCircleLeavesPlacesAndEventsInPlace() {
        // Refresh calls select() with the circle already open. The old
        // detail must stay on screen while the refetch is in flight, not
        // flash the empty state.
        val viewing = CirclesState(
            circles = listOf(circle("c1", "Family")),
            selectedId = "c1",
            places = listOf(place("p1", "c1")),
            events = listOf(event("e1")),
        )
        val reselected = viewing.selecting("c1")
        assertEquals(viewing.places, reselected.places)
        assertEquals(viewing.events, reselected.events)
        assertEquals("c1", reselected.selectedId)
    }

    @Test
    fun aDetailFailureSetsDetailErrorAndLeavesTheListErrorAlone() {
        val failed = CirclesState(circles = listOf(circle("c1", "Family")))
            .detailStarting()
            .detailFailed(RuntimeException("no route to host"))
        assertEquals("no route to host", failed.detailError)
        assertNull(failed.error)
        assertTrue(!failed.detailBusy)
    }

    @Test
    fun aListFailureSetsErrorAndLeavesTheDetailErrorAlone() {
        val failed = CirclesState(circles = listOf(circle("c1", "Family")))
            .starting()
            .failed(RuntimeException("403"))
        assertEquals("403", failed.error)
        assertNull(failed.detailError)
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

    // --- cleared() ------------------------------------------------------------
    //
    // reset()'s reducer half. `_state` is `private` in all three stores —
    // asserted here as a pure transition on a state the caller already has,
    // same as every other transition above, rather than by driving the
    // singleton through it directly.

    @Test
    fun clearedFriendsStateIsTheDefault() {
        val dirty = FriendsState(
            lists = lists(),
            leaderboard = listOf(stats("ada")),
            own = stats("me"),
            busy = true,
            error = "stale",
        )
        assertEquals(FriendsState(), dirty.cleared())
    }

    @Test
    fun clearedFriendsStateDropsTheOwnRowEvenThoughLoadedKeepsIt() {
        // `own` is the one field `loaded()` deliberately preserves (see its
        // own doc) — the field `cleared()` is most likely to inherit that
        // habit for by mistake. A sign-out is a different rider, not a
        // failed refresh, and the old rider's own-stats row must not survive
        // to render under the new rider's name in the leaderboard.
        val dirty = FriendsState(own = stats("ada"))
        assertNull(dirty.cleared().own)
    }

    @Test
    fun clearedConvoysStateIsTheDefault() {
        val dirty = ConvoysState(convoys = listOf(convoy("c1", "Sunday run")), busy = true, error = "stale")
        assertEquals(ConvoysState(), dirty.cleared())
    }

    @Test
    fun clearedCirclesStateIsTheDefault() {
        val dirty = CirclesState(
            circles = listOf(circle("c1", "Family")),
            selectedId = "c1",
            places = listOf(place("p1", "c1")),
            events = listOf(event("e1")),
            busy = true,
            error = "stale",
            detailBusy = true,
            detailError = "stale detail",
        )
        assertEquals(CirclesState(), dirty.cleared())
    }
}
