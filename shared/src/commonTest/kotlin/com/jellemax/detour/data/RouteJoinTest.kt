package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers RouteJoin.kt: two legs of a turnaround loop (#889) joined into one route.
 * A shifted index that's off by one points the second leg's turns and speed limits
 * at the wrong road, which no compile catches.
 */
class RouteJoinTest {

    /** Points along latitude [lat], one per 0.001° of longitude from index [from] to [to]. */
    private fun line(lat: Double, from: Int, to: Int): List<LatLon> =
        (if (to >= from) from..to else from downTo to).map { LatLon(lat, 4.0 + it * 0.001) }

    private val turnaround = LatLon(51.0, 4.010)

    private val out = RouteResult(
        polyline = line(51.0, 0, 10), // 11 points, ends on the turnaround
        waypoints = emptyList(),
        distanceMeters = 700.0,
        instructions = listOf(
            NavInstruction("Continue", 400.0, 0, 0, 6),
            NavInstruction("Turn left", 300.0, -2, 6, 10),
            NavInstruction("Arrive at destination", 0.0, 4, 10, 10),
        ),
        timeMs = 60_000,
        speedLimits = listOf(SpeedLimitSegment(0, 6, 50.0), SpeedLimitSegment(6, 10, 80.0)),
    )

    private val back = RouteResult(
        polyline = listOf(turnaround) + line(51.001, 10, 0), // 12 points, starts on the turnaround
        waypoints = emptyList(),
        distanceMeters = 800.0,
        instructions = listOf(
            NavInstruction("Turn right", 500.0, 2, 0, 7),
            NavInstruction("Keep left", 300.0, -7, 7, 11),
            NavInstruction("Arrive at destination", 0.0, 4, 11, 11),
        ),
        timeMs = 70_000,
        speedLimits = listOf(SpeedLimitSegment(0, 4, null), SpeedLimitSegment(4, 11, 70.0)),
    )

    private val joined = RouteJoin.join(out, back, turnaround)

    @Test
    fun sharedVertexIsKeptOnce() {
        assertEquals(out.polyline.size + back.polyline.size - 1, joined.polyline.size)
        assertEquals(out.polyline, joined.polyline.take(out.polyline.size))
        assertEquals(back.polyline, joined.polyline.drop(out.polyline.size - 1))
    }

    @Test
    fun secondLegIndicesPointAtTheSameCoordinates() {
        val shifted = joined.instructions.drop(out.instructions.size)
        assertEquals(back.instructions.size, shifted.size)
        back.instructions.zip(shifted).forEach { (before, after) ->
            assertEquals(before.text, after.text)
            assertEquals(back.polyline[before.startIndex], joined.polyline[after.startIndex])
            assertEquals(back.polyline[before.endIndex], joined.polyline[after.endIndex])
        }
        val limits = joined.speedLimits.drop(out.speedLimits.size)
        back.speedLimits.zip(limits).forEach { (before, after) ->
            assertEquals(before.kmh, after.kmh)
            assertEquals(back.polyline[before.fromIndex], joined.polyline[after.fromIndex])
            assertEquals(back.polyline[before.toIndex], joined.polyline[after.toIndex])
        }
        assertEquals(out.speedLimits, joined.speedLimits.take(out.speedLimits.size))
    }

    @Test
    fun outboundFinishBecomesAViaAtTheJoint() {
        val first = joined.instructions.take(out.instructions.size)
        assertEquals(out.instructions.dropLast(1), first.dropLast(1))
        val via = first.last()
        assertEquals(5, via.sign)
        assertEquals(RouteJoin.VIA_TEXT, via.text)
        assertEquals(0.0, via.distanceMeters)
        assertEquals(turnaround, joined.polyline[via.startIndex])
        assertEquals(via.startIndex, via.endIndex)
        // Only the loop's own end is a finish.
        assertEquals(listOf(joined.instructions.last()), joined.instructions.filter { it.sign == 4 })
        assertEquals(joined.polyline.size - 1, joined.instructions.last().startIndex)
    }

    @Test
    fun distanceAndTimeAreSummed() {
        assertEquals(1500.0, joined.distanceMeters)
        assertEquals(130_000L, joined.timeMs)
    }

    @Test
    fun unknownDistanceOrTimeOnEitherLegIsUnknown() {
        val noDistance = RouteJoin.join(out, back.copy(distanceMeters = null), turnaround)
        assertNull(noDistance.distanceMeters)
        assertEquals(130_000L, noDistance.timeMs)
        val noTime = RouteJoin.join(out.copy(timeMs = null), back, turnaround)
        assertNull(noTime.timeMs)
        assertEquals(1500.0, noTime.distanceMeters)
    }

    @Test
    fun atMostNineWaypointsInRouteOrderWithTheViaAmongThem() {
        val wp = joined.waypoints
        assertTrue(wp.size <= 9, "got ${wp.size}")
        val viaAt = wp.indexOf(turnaround)
        assertTrue(viaAt >= 0, "via missing from $wp")
        // Before the via: outbound points; after it: return points, more of them.
        assertTrue(wp.take(viaAt).all { it in out.polyline })
        assertTrue(wp.drop(viaAt + 1).all { it in back.polyline })
        assertTrue(wp.size - viaAt - 1 > viaAt, "return should carry most waypoints: $wp")
        val order = wp.map { joined.polyline.indexOf(it) }
        assertEquals(order.sorted(), order)
    }
}
