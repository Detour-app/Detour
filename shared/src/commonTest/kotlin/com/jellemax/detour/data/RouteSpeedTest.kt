package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [RouteSpeed] — the colour steps the Logbook draws a route in (#834).
 * Speed has to come from each hop's own distance and time, because the trace
 * is decimated to 25 m: a stop is one long hop, never a run of slow points.
 */
class RouteSpeedTest {

    /** ~25 m north per hop, the trace's own spacing; [hopMs] is each hop's time. */
    private fun route(vararg hopMs: Long): List<TraceStore.TracePoint> {
        var t = 1_000_000L
        val times = listOf(t) + hopMs.map { t += it; t }
        return times.mapIndexed { i, ms ->
            TraceStore.TracePoint(LatLon(51.0 + i * 0.000225, 3.0), ms, speedKmh = 50.0, leanDeg = null)
        }
    }

    @Test
    fun aStopIsTheSlowestStepAndTheFastestStretchTheTop() {
        // Thirty 25 m hops at 90 km/h, twenty at 18, one over 3 minutes (a
        // light), thirty at 90 again. Stored speedKmh is 50 on every point.
        val hops = List(30) { 1_000L } + List(20) { 5_000L } + 180_000L + List(30) { 1_000L }
        val steps = RouteSpeed.buckets(route(*hops.toLongArray()))!!
        assertEquals(81, steps.size)
        assertEquals(RouteSpeed.BUCKETS - 1, steps[15])
        assertEquals(0, steps[40])
        assertEquals(0, steps[50])
        // The stop doesn't slow the stretch after it.
        assertEquals(RouteSpeed.BUCKETS - 1, steps[52])
        assertEquals(RouteSpeed.BUCKETS - 1, steps[65])
    }

    @Test
    fun oneHopsTimingJitterDoesNotFleckTheLine() {
        // 25 m every 1.5 s, fixes landing on whole seconds: hops alternate 1 s and 2 s.
        val steps = RouteSpeed.buckets(route(*LongArray(40) { if (it % 2 == 0) 1_000L else 2_000L }))!!
        assertEquals(1, steps.drop(8).dropLast(8).toSet().size, "steps $steps")
    }

    @Test
    fun anEvenPaceIsTheMiddleStepNotARainbow() {
        val steps = RouteSpeed.buckets(route(2_000, 2_000, 2_010, 1_990, 2_000))!!
        assertEquals(List(5) { RouteSpeed.BUCKETS / 2 }, steps)
    }

    @Test
    fun aRouteWithoutTimesHasNoSteps() {
        // Points written before the trace carried a time read back as -1.
        val untimed = route(2_000, 2_000, 2_000).mapIndexed { i, p -> if (i == 1) p.copy(timeMs = -1) else p }
        assertNull(RouteSpeed.buckets(untimed))
        assertNull(RouteSpeed.buckets(route()))
    }

    @Test
    fun aHopWithNoTimeReadsAsItsWindow() {
        // The keepLast seam can repeat a timestamp: that hop has no speed of its
        // own, so it takes the riding either side of it within the window — here
        // the moving hops after the stop, not the stop itself.
        val steps = RouteSpeed.buckets(route(180_000, 0, 1_000, 2_000, 2_000))!!
        assertEquals(0, steps[0])
        assertEquals(steps[2], steps[1])
    }

    @Test
    fun runsCoverEveryHopOnceAndShareTheirEnds() {
        val runs = RouteSpeed.runs(listOf(0, 0, 3, 3, 3, 1))
        assertEquals(
            listOf(RouteSpeed.Run(0, 0, 2), RouteSpeed.Run(3, 2, 5), RouteSpeed.Run(1, 5, 6)),
            runs,
        )
        assertEquals(emptyList(), RouteSpeed.runs(emptyList()))
    }
}
