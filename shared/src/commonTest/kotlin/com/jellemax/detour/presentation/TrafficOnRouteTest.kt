package com.jellemax.detour.presentation

import com.jellemax.detour.data.HereTrafficClient.Criticality
import com.jellemax.detour.data.HereTrafficClient.Fetch
import com.jellemax.detour.data.HereTrafficClient.FlowSegment
import com.jellemax.detour.data.HereTrafficClient.Incident
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.drive.TrafficCorridor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers TrafficOnRoute.kt (#645): which stretches of the route ahead get a
 * congestion colour, which incidents count as on the route, and the delay the
 * banner and the ETA carry.
 */
class TrafficOnRouteTest {

    /** 1.5 km due north as three 500 m legs, central Brussels. */
    private val route = listOf(
        LatLon(50.8467, 4.3525),
        LatLon(50.8512, 4.3525),
        LatLon(50.8557, 4.3525),
        LatLon(50.8602, 4.3525),
    )
    private val tile = TrafficCorridor.tileOf(route.first())
    private val now = 10_000L

    private fun flow(from: Int, to: Int, jam: Double, speed: Double = 25.0, closed: Boolean = false) =
        FlowSegment(
            shape = route.subList(from, to + 1).map { LatLon(it.lat, it.lon + 0.0001) },
            speedMps = speed,
            freeFlowMps = 25.0,
            jamFactor = jam,
            closed = closed,
        )

    private fun incident(id: String, at: LatLon) =
        Incident(id, "accident", Criticality.MAJOR, at, listOf(at), false, "", null, null)

    private fun held(flow: List<FlowSegment>, incidents: List<Incident> = emptyList()): TrafficCorridor.State {
        val st = TrafficCorridor.flowAnswered(TrafficCorridor.State(), tile, Fetch.Ok(flow), now)
        return TrafficCorridor.incidentsAnswered(st, tile, Fetch.Ok(incidents), now)
    }

    @Test
    fun nothingHeldIsNoTrafficAndNoBanner() {
        val t = trafficOnRoute(TrafficCorridor.State(), route, now)
        assertEquals(emptyList(), t.runs)
        assertEquals(emptyList(), t.incidents)
        assertEquals(0.0, t.delaySeconds, absoluteTolerance = 1e-9)
        assertNull(t.bannerDelayMinutes)
    }

    @Test
    fun freeFlowDrawsNothingOverTheRoute() {
        val t = trafficOnRoute(held(listOf(flow(0, 3, jam = 1.0))), route, now)
        assertEquals(emptyList(), t.runs, "free flow is the route's own colour")
    }

    @Test
    fun touchingLegsAtOneLevelAreOneRunAndAFreeLegSplitsThem() {
        val t = trafficOnRoute(
            held(listOf(flow(0, 1, jam = 9.0, speed = 3.0), flow(1, 2, jam = 1.0), flow(2, 3, jam = 9.0, speed = 3.0))),
            route, now,
        )
        assertEquals(listOf(Congestion.QUEUING, Congestion.QUEUING), t.runs.map { it.congestion })
        assertEquals(listOf(route[0], route[1]), t.runs[0].points)
        assertEquals(listOf(route[2], route[3]), t.runs[1].points)

        val merged = trafficOnRoute(held(listOf(flow(0, 3, jam = 5.0, speed = 10.0))), route, now)
        assertEquals(1, merged.runs.size)
        assertEquals(Congestion.SLOW, merged.runs.single().congestion)
        assertEquals(route, merged.runs.single().points)
    }

    @Test
    fun aClosedStretchIsClosedAhead() {
        val t = trafficOnRoute(held(listOf(flow(1, 2, jam = 10.0, speed = 0.0, closed = true))), route, now)
        assertEquals(Congestion.CLOSED, t.runs.single().congestion)
        assertTrue(t.closedAhead)
    }

    @Test
    fun aJamPutsItsDelayOnTheBannerInWholeMinutes() {
        // 1.5 km at 3 m/s instead of 25: 500 s - 60 s = 440 s, about 7 min.
        val t = trafficOnRoute(held(listOf(flow(0, 3, jam = 9.0, speed = 3.0))), route, now)
        assertEquals(7, t.bannerDelayMinutes)
    }

    @Test
    fun underAMinuteOfDelayIsNoBanner() {
        // 1.5 km at 20 m/s instead of 25: 75 s - 60 s = 15 s.
        val t = trafficOnRoute(held(listOf(flow(0, 3, jam = 2.0, speed = 20.0))), route, now)
        assertTrue(t.delaySeconds > 0.0)
        assertNull(t.bannerDelayMinutes)
    }

    @Test
    fun onlyIncidentsOnTheRouteAreShown() {
        val onIt = incident("on", LatLon(50.8530, 4.3527))
        val offIt = incident("off", LatLon(50.8530, 4.3700)) // ~1.2 km east
        val t = trafficOnRoute(held(emptyList(), listOf(onIt, offIt)), route, now)
        assertEquals(listOf("on"), t.incidents.map { it.id })
    }

    @Test
    fun theDelayIsAddedToTheTimeLeft() {
        val progress = progress(remainingTimeMs = 600_000L)
        val t = TrafficOnRoute(emptyList(), emptyList(), delaySeconds = 120.0, closedAhead = false)
        assertEquals(720_000L, progress.withTrafficDelay(t).remainingTimeMs)
        assertSame(progress, progress.withTrafficDelay(null), "no traffic leaves the router's time alone")
    }

    @Test
    fun routeAheadIsTheLineFromTheRiderOn() {
        assertEquals(emptyList(), routeAhead(null, null), "no route, nothing to ask about")
        assertEquals(route, routeAhead(route, null), "before the first fix, all of it")
        // A third driven: 1,000 m left of 1,500, so the cut lands on route[1].
        val ahead = routeAhead(route, progress(remainingTimeMs = 0L, remainingMeters = 1_000.0))
        assertEquals(route.drop(1), ahead.map { LatLon(round5(it.lat), round5(it.lon)) })
    }

    private fun round5(v: Double) = kotlin.math.round(v * 1e5) / 1e5

    private fun progress(remainingTimeMs: Long, remainingMeters: Double = 1_500.0) = NavEngine.Progress(
        offRouteMeters = 0.0,
        snappedAt = route.first(),
        segmentBearingDeg = 0.0,
        nextInstruction = null,
        distanceToTurnMeters = 0.0,
        remainingMeters = remainingMeters,
        routeMeters = 1_500.0,
        remainingTimeMs = remainingTimeMs,
        speedLimitKmh = null,
    )
}
