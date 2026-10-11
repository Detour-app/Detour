package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient.Criticality
import com.jellemax.detour.data.HereTrafficClient.Fetch
import com.jellemax.detour.data.HereTrafficClient.FlowSegment
import com.jellemax.detour.data.HereTrafficClient.Incident
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.drive.TrafficCorridor.Feed
import com.jellemax.detour.drive.TrafficCorridor.Request
import com.jellemax.detour.drive.TrafficCorridor.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers TrafficCorridor.kt (#643): which HERE requests are due along the
 * route, the per-tile cache that keeps a rider's key from being spent twice a
 * minute, and the backoff that must never become a retry loop.
 */
class TrafficCorridorTest {

    /** Brussels → Leuven along the E40, about 22 km: crosses a tile edge. */
    private val route = listOf(
        LatLon(50.8467, 4.3525),
        LatLon(50.8600, 4.4500),
        LatLon(50.8790, 4.7005),
    )

    private val flowSegment = FlowSegment(listOf(route[0], route[1]), 5.0, 25.0, 6.0, closed = false)

    private fun incident(id: String) =
        Incident(id, "accident", Criticality.MAJOR, route[0], listOf(route[0]), false, "", null, null)

    /** Every due request made at [nowMs] and answered with [answer]. */
    private fun refresh(state: State, nowMs: Long, answer: Fetch<Nothing>? = null): State {
        val due = TrafficCorridor.due(state, route, navigating = true, nowMs)
        var st = TrafficCorridor.started(state, due, nowMs)
        for (r in due) {
            st = when (r.feed) {
                Feed.FLOW -> TrafficCorridor.flowAnswered(st, r.tile, answer ?: Fetch.Ok(listOf(flowSegment)), nowMs)
                Feed.INCIDENTS -> TrafficCorridor.incidentsAnswered(st, r.tile, answer ?: Fetch.Ok(listOf(incident("a"))), nowMs)
            }
        }
        return st
    }

    @Test
    fun notNavigatingAsksForNothing() {
        assertEquals(emptyList(), TrafficCorridor.due(State(), route, navigating = false, nowMs = 0L))
    }

    @Test
    fun theTilesAheadFollowTheRouteAndStopAtTheLookahead() {
        val tiles = TrafficCorridor.tilesAhead(route)
        assertEquals(TrafficCorridor.tileOf(route[0]), tiles.first())
        assertTrue(tiles.size in 2..TrafficCorridor.MAX_TILES, "22 km east crosses at least one zoom-10 edge: $tiles")
    }

    @Test
    fun noTileIsAskedForPastTheLookahead() {
        // 2° east along 50° N is about 143 km.
        val long = listOf(LatLon(50.0, 4.0), LatLon(50.0, 6.0))
        val lookaheadDeg = TrafficCorridor.LOOKAHEAD_M / (111_320.0 * kotlin.math.cos(50.0 * kotlin.math.PI / 180))
        val tiles = TrafficCorridor.tilesAhead(long)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.box().west <= 4.0 + lookaheadDeg }, "a tile starts past the lookahead: $tiles")
    }

    @Test
    fun aTilesBoxContainsThePointsThatFallInIt() {
        val box = TrafficCorridor.tileOf(route[0]).box()
        assertTrue(route[0].lat in box.south..box.north && route[0].lon in box.west..box.east, "$box")
        assertTrue(box.east - box.west < 1.0 && box.north - box.south < 1.0, "HERE refuses a box over a degree")
    }

    @Test
    fun aCachedTileIsNotAskedForAgainWithinItsTtl() {
        val st = refresh(State(), nowMs = 0L)
        val tiles = TrafficCorridor.tilesAhead(route)
        assertEquals(emptyList(), TrafficCorridor.due(st, route, true, nowMs = 59_000L), "cache hit within the minute")
        assertEquals(
            tiles.map { Request(it, Feed.FLOW) },
            TrafficCorridor.due(st, route, true, nowMs = TrafficCorridor.FLOW_TTL_MS),
            "flow is due after a minute, incidents not yet",
        )
        assertEquals(
            tiles.size * 2,
            TrafficCorridor.due(st, route, true, nowMs = TrafficCorridor.INCIDENTS_TTL_MS).size,
        )
    }

    @Test
    fun heldAnswersAreServedUntilTheyAreTooOld() {
        val st = refresh(State(), nowMs = 0L)
        assertTrue(TrafficCorridor.flowAlong(st, route, nowMs = 1_000L).isNotEmpty())
        assertEquals(
            listOf("a"),
            TrafficCorridor.incidentsAlong(st, route, nowMs = 1_000L).map { it.id },
            "an incident both tiles returned is listed once",
        )
        assertEquals(emptyList(), TrafficCorridor.flowAlong(st, route, nowMs = TrafficCorridor.MAX_AGE_MS + 1))
    }

    @Test
    fun aRefusedKeyStopsEveryRequestForGood() {
        val st = refresh(State(), nowMs = 0L, Fetch.BadKey)
        assertTrue(st.keyRefused)
        for (later in listOf(TrafficCorridor.FLOW_TTL_MS, 3_600_000L, 86_400_000L)) {
            assertEquals(emptyList(), TrafficCorridor.due(st, route, true, later), "401 must not be retried (at $later ms)")
        }
    }

    @Test
    fun aSpentQuotaWaitsLongAndLongerEachTime() {
        val first = refresh(State(), nowMs = 0L, Fetch.Quota)
        assertEquals(TrafficCorridor.QUOTA_BACKOFF_MS, first.quotaUntilMs, "one 429 round is one step, not one per tile")
        assertEquals(emptyList(), TrafficCorridor.due(first, route, true, TrafficCorridor.QUOTA_BACKOFF_MS - 1))
        val second = refresh(first, nowMs = TrafficCorridor.QUOTA_BACKOFF_MS, Fetch.Quota)
        assertTrue(second.quotaUntilMs - TrafficCorridor.QUOTA_BACKOFF_MS >= 2 * TrafficCorridor.QUOTA_BACKOFF_MS)
        assertEquals(TrafficCorridor.QUOTA_MAX_BACKOFF_MS, TrafficCorridor.quotaDelayMs(hits = 50))
    }

    @Test
    fun failuresBackOffAndOneAnswerResetsTheCadence() {
        val failed = refresh(State(), nowMs = 0L, Fetch.Failed)
        assertEquals(1, failed.failures, "one outage across a round of requests is one failure")
        val wait = TrafficCorridor.retryDelayMs(failed.failures)
        assertTrue(wait > TrafficCorridor.FLOW_TTL_MS, "backed off beyond the plain TTL")
        assertEquals(emptyList(), TrafficCorridor.due(failed, route, true, wait - 1))
        val recovered = refresh(failed, nowMs = wait)
        assertEquals(0, recovered.failures)
        assertEquals(TrafficCorridor.MAX_BACKOFF_MS, TrafficCorridor.retryDelayMs(failures = 50))
    }

    @Test
    fun tilesLeftBehindAreDroppedOnceTooOldToServe() {
        val held = refresh(State(), nowMs = 0L)
        val later = TrafficCorridor.started(held, emptyList(), nowMs = TrafficCorridor.MAX_AGE_MS + 1)
        assertTrue(later.flow.isEmpty() && later.incidents.isEmpty() && later.attemptedMs.isEmpty(), "$later")
    }

    @Test
    fun aFailedRefreshKeepsTheRecentAnswer() {
        val held = refresh(State(), nowMs = 0L)
        val afterBlip = refresh(held, nowMs = TrafficCorridor.INCIDENTS_TTL_MS, Fetch.Failed)
        assertTrue(TrafficCorridor.flowAlong(afterBlip, route, TrafficCorridor.INCIDENTS_TTL_MS).isNotEmpty())
    }
}
