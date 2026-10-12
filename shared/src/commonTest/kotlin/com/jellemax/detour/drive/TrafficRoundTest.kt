package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.HereTrafficFixtures
import com.jellemax.detour.data.HttpStatusException
import com.jellemax.detour.data.LatLon
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers TrafficRound.kt (#645): a round makes exactly what the corridor says
 * is due, folds every answer in, and asks HERE nothing at all while the rider
 * is not navigating — the rider pays for every request.
 */
class TrafficRoundTest {

    /** 1 km north out of central Brussels: one zoom-10 tile. */
    private val route = listOf(LatLon(50.8467, 4.3525), LatLon(50.8557, 4.3525))

    private val flowBody = """{"results":[{"location":{"shape":{"links":[{"points":[
        {"lat":50.8467,"lng":4.3525},{"lat":50.8557,"lng":4.3525}]}]}},
        "currentFlow":{"speed":5.0,"freeFlow":25.0,"jamFactor":9.0,"traversability":"open"}}]}"""

    /** A client that counts what it is asked and answers with [answer]. */
    private class Counting(val answer: (String) -> String) {
        val urls = mutableListOf<String>()
        val client = HereTrafficClient("sentinel", fetch = { url -> urls.add(url); answer(url) })
    }

    private fun counting() = Counting { url -> if ("/flow?" in url) flowBody else HereTrafficFixtures.EMPTY }

    @Test
    fun notNavigatingMakesNoRequest() = runBlocking {
        val here = counting()
        val state = TrafficCorridor.State()
        val after = TrafficRound.run(state, here.client, route, navigating = false, nowMs = { 0L })
        assertEquals(0, here.urls.size, "an idle app must not spend the rider's quota")
        assertEquals(state, after)
    }

    @Test
    fun aRoundAsksBothFeedsForTheTileAheadAndHoldsTheAnswers() = runBlocking {
        val here = counting()
        val after = TrafficRound.run(TrafficCorridor.State(), here.client, route, navigating = true, nowMs = { 1_000L })
        assertEquals(2, here.urls.size)
        assertTrue(here.urls.any { "/flow?" in it } && here.urls.any { "/incidents?" in it })
        assertEquals(1, TrafficCorridor.flowAlong(after, route, 1_000L).size)
    }

    @Test
    fun theNextRoundInsideTheTtlAsksNothing() = runBlocking {
        val here = counting()
        val first = TrafficRound.run(TrafficCorridor.State(), here.client, route, navigating = true, nowMs = { 0L })
        TrafficRound.run(first, here.client, route, navigating = true, nowMs = { 15_000L })
        assertEquals(2, here.urls.size, "a 15 s tick must not re-ask a feed HERE refreshes once a minute")
    }

    @Test
    fun aRefusedKeyStopsEveryLaterRound() = runBlocking {
        val here = Counting { throw HttpStatusException(401, "") }
        val refused = TrafficRound.run(TrafficCorridor.State(), here.client, route, navigating = true, nowMs = { 0L })
        assertTrue(refused.keyRefused)
        val asked = here.urls.size
        TrafficRound.run(refused, here.client, route, navigating = true, nowMs = { 3_600_000L })
        assertEquals(asked, here.urls.size, "a bad key must not be retried")
    }
}
