package com.jellemax.detour.data

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TurnaroundLoop]'s choosing and failure rules against fake routers (#920).
 *
 * The legs are drawn on a local metre grid around [HOME_LAT]/[HOME_LON]: the
 * way out runs 10 km due east to the pin, and each fake return comes back on
 * its own geometry, so the overlap and curviness the loop scores are the real
 * [RouteOverlap] and [Curviness] numbers, not stubs.
 *
 * `runBlocking` rather than `runTest`, for the reason [SingleFlightTest] gives.
 */
class TurnaroundLoopTest {

    private fun at(x: Double, y: Double) = LatLon(
        HOME_LAT + y / M_PER_DEG,
        HOME_LON + x / (M_PER_DEG * cos(HOME_LAT * PI / 180.0)),
    )

    /** Straight legs between the corners (x, y in metres), a point every 100 m. */
    private fun straight(vararg corners: Pair<Double, Double>): List<LatLon> =
        corners.toList().zipWithNext().flatMapIndexed { i, (a, b) ->
            val steps = maxOf(1, (hypot(b.first - a.first, b.second - a.second) / 100).toInt())
            (0..steps).drop(if (i == 0) 0 else 1).map { s ->
                val t = s.toDouble() / steps
                at(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
            }
        }

    /**
     * West along y = [y] from x = [fromX] to 0, wiggling ±30 m with a 300 m
     * wavelength: bends of ~75 m radius, well inside [Curviness]'s 25–300 m window.
     */
    private fun wiggleWest(fromX: Double, y: Double): List<LatLon> {
        val steps = (fromX / 20).toInt()
        return (0..steps).map { s ->
            val x = fromX - s * 20.0
            at(x, y + 30 * sin(2 * PI * x / 300))
        }
    }

    private fun route(polyline: List<LatLon>) =
        RouteResult(polyline = polyline, waypoints = emptyList(), distanceMeters = 1000.0)

    private val home = at(0.0, 0.0)
    private val pin = at(10_000.0, 0.0)
    private val out = route(straight(0.0 to 0.0, 10_000.0 to 0.0))

    /** Same road home: overlap ~1. */
    private val sameRoad = route(straight(10_000.0 to 0.0, 0.0 to 0.0))

    /** Different, straight road home 3 km north: overlap 0, curviness 0. */
    private val straightAway = route(straight(10_000.0 to 0.0, 10_000.0 to 3_000.0, 0.0 to 3_000.0, 0.0 to 0.0))

    /** Different, winding road home 3 km south: overlap 0, curviness high. */
    private val curvyAway = route(
        straight(10_000.0 to 0.0, 10_000.0 to -3_000.0) +
            wiggleWest(10_000.0, -3_000.0).drop(1) +
            straight(0.0 to -3_000.0, 0.0 to 0.0).drop(1),
    )

    /** Half the way out again, then off it: overlap ~0.29. */
    private val halfway = route(straight(10_000.0 to 0.0, 5_000.0 to 0.0, 5_000.0 to 3_000.0, 0.0 to 3_000.0, 0.0 to 0.0))

    /** A fake return per penalty, strongest penalty last, as [TurnaroundLoop] asks for them. */
    private fun backs(weak: suspend () -> RouteResult, mid: suspend () -> RouteResult, strong: suspend () -> RouteResult):
        suspend (PenaltyArea?) -> RouteResult = { area ->
            when (area?.multiplyBy) {
                0.2 -> weak()
                0.1 -> mid()
                0.03 -> strong()
                else -> error("unexpected area $area")
            }
        }

    private val fails: suspend () -> RouteResult = { throw HttpStatusException(503, "") }
    private val hang: suspend () -> RouteResult = { awaitCancellation() }

    @Test
    fun theCurviestReturnUnderFifteenPercentWins() = runBlocking {
        val result = TurnaroundLoop.build(
            serverUsable = true,
            outbound = { out },
            back = backs(weak = { sameRoad }, mid = { straightAway }, strong = { curvyAway }),
        )
        assertEquals(out.polyline + curvyAway.polyline.drop(1), result.route.polyline)
        assertEquals(0.0, result.overlap, 0.01)
    }

    @Test
    fun whenEveryReturnRepeatsTooMuchTheLeastRepeatingOneWinsAndSaysHowMuch() = runBlocking {
        val result = TurnaroundLoop.build(
            serverUsable = true,
            outbound = { out },
            back = backs(weak = { sameRoad }, mid = { halfway }, strong = { sameRoad }),
        )
        assertEquals(out.polyline + halfway.polyline.drop(1), result.route.polyline)
        assertTrue(result.overlap > 0.15 && result.overlap < 0.5, "overlap ${result.overlap}")
    }

    @Test
    fun oneReturnFailingDoesNotFailTheLoop() = runBlocking {
        val result = TurnaroundLoop.build(
            serverUsable = true,
            outbound = { out },
            back = backs(weak = { straightAway }, mid = fails, strong = fails),
        )
        assertEquals(out.polyline + straightAway.polyline.drop(1), result.route.polyline)
    }

    @Test
    fun theLoopStartsAndEndsAtTheStartAndPassesTheSnappedPin() = runBlocking {
        val result = TurnaroundLoop.build(
            serverUsable = true,
            outbound = { out },
            back = backs(weak = { straightAway }, mid = { straightAway }, strong = { straightAway }),
        )
        val line = result.route.polyline
        assertEquals(out.polyline.first(), line.first())
        assertEquals(out.polyline.first(), line.last())
        assertTrue(pin in line)
        assertEquals(home, line.first())
    }

    @Test
    fun theReturnsKeepOffTheWayOutWithThreePenalties() = runBlocking {
        val asked = mutableListOf<PenaltyArea?>()
        TurnaroundLoop.build(
            serverUsable = true,
            outbound = { out },
            back = { area -> asked += area; straightAway },
        )
        assertEquals(listOf(0.2, 0.1, 0.03), asked.map { it?.multiplyBy ?: 0.0 }.sortedDescending())
        assertTrue(asked.all { it != null && it.polygons == PenaltyCorridor.polygons(out.polyline) })
    }

    @Test
    fun aWayOutWithNoLengthAsksForOnePlainReturn() = runBlocking {
        val asked = mutableListOf<PenaltyArea?>()
        TurnaroundLoop.build(
            serverUsable = true,
            outbound = { route(listOf(home, home)) },
            back = { area -> asked += area; route(listOf(home, home)) },
        )
        assertEquals(listOf<PenaltyArea?>(null), asked)
    }

    @Test
    fun noUsableServerFailsWithoutRouting() = runBlocking<Unit> {
        var called = false
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.build(
                serverUsable = false,
                outbound = { called = true; out },
                back = { called = true; out },
            )
        }
        assertFalse(called)
        assertEquals(TurnaroundLoop.NO_SERVER_MESSAGE, e.message)
    }

    @Test
    fun aFailedWayOutSaysWhyInTheRidersWords() = runBlocking<Unit> {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.build(
                serverUsable = true,
                outbound = { throw NoRouteException("Connection between locations not found") },
                back = { error("no return expected") },
            )
        }
        assertEquals("No route to the turnaround: no road connects those points. Try moving them.", e.message)
    }

    @Test
    fun everyReturnFailingFailsTheLoop() = runBlocking<Unit> {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.build(
                serverUsable = true,
                outbound = { out },
                back = backs(fails, fails, fails),
            )
        }
        assertEquals("No route back from the turnaround: the server hit a problem. Try again later.", e.message)
    }

    @Test
    fun runningOutOfTimeWithNothingInHandSaysTheServerIsSlowNotThatAFallbackFailed() = runBlocking<Unit> {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.build(serverUsable = true, outbound = hang, back = { error("no return expected") }, timeoutMs = 50)
        }
        assertEquals("The routing server is too slow or unreachable — try again", e.message)
    }

    @Test
    fun runningOutOfTimeWithNoReturnLandedSaysTheServerIsSlow() = runBlocking<Unit> {
        val e = assertFailsWith<SpinFailure> {
            TurnaroundLoop.build(serverUsable = true, outbound = { out }, back = backs(hang, hang, hang), timeoutMs = 50)
        }
        assertEquals("The routing server is too slow or unreachable — try again", e.message)
    }

    @Test
    fun runningOutOfTimeWithOneReturnLandedRidesThatOne() = runBlocking {
        val result = TurnaroundLoop.build(
            serverUsable = true,
            outbound = { out },
            back = backs(weak = hang, mid = { halfway }, strong = hang),
            timeoutMs = 200,
        )
        assertEquals(out.polyline + halfway.polyline.drop(1), result.route.polyline)
        assertTrue(result.overlap > 0.15)
    }

    @Test
    fun theRiderCancellingIsNotReportedAsAFailure() = runBlocking<Unit> {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) {
                TurnaroundLoop.build(serverUsable = true, outbound = { out }, back = backs(hang, hang, hang), timeoutMs = 5_000)
            }
        }
    }

    @Test
    fun aCancelledReturnPropagatesRatherThanCountingAsAFailedOne() = runBlocking<Unit> {
        val cancelled = kotlinx.coroutines.CancellationException("rider left")
        val e = assertFailsWith<kotlinx.coroutines.CancellationException> {
            TurnaroundLoop.build(
                serverUsable = true,
                outbound = { out },
                back = backs(weak = { throw cancelled }, mid = fails, strong = fails),
            )
        }
        assertEquals("rider left", e.message)
    }

    private companion object {
        const val HOME_LAT = 50.85
        const val HOME_LON = 5.69
        const val M_PER_DEG = 111_320.0
    }
}
