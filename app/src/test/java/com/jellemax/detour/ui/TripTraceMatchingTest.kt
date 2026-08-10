package com.jellemax.detour.ui

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [matchTripPoints] - how a trip's recorded route is reassembled out
 * of the trace store. The tracker splits one ride across several stored
 * lines (every 200 points, on a big GPS gap, on a STILL transition), and the
 * line that opens a trip also carries whatever idle points were still in the
 * buffer when it began. Matching a trip to a single nearest line therefore
 * truncated every ride past ~5 km and could drop its opening stretch
 * entirely, which is what these tests pin down. No Android APIs involved, so
 * no emulator/Robolectric needed.
 */
class TripTraceMatchingTest {

    private fun trip(startMs: Long, endMs: Long) = Trip(
        startTimeMs = startMs,
        endTimeMs = endMs,
        distanceMeters = 0.0,
        topSpeedMps = 0.0,
        destinationLat = null,
        destinationLon = null,
    )

    /** Points a minute apart, walking north so each one is distinguishable. */
    private fun segment(firstMs: Long, count: Int, startLat: Double = 52.0): TraceSegment {
        val points = (0 until count).map { i ->
            TraceStore.TracePoint(
                at = LatLon(startLat + i * 0.001, 4.9),
                timeMs = firstMs + i * 60_000L,
                speedKmh = 50.0,
                leanDeg = null,
            )
        }
        return TraceSegment(points, points.first().timeMs, points.last().timeMs)
    }

    @Test
    fun stitchesEveryLineTheRideWasSplitAcross() {
        val a = segment(1_000_000, 3)
        val b = segment(1_180_000, 3, startLat = 52.003)
        val c = segment(1_360_000, 3, startLat = 52.006)
        val points = matchTripPoints(listOf(a, b, c), trip(1_000_000, 1_480_000))
        assertEquals(9, points.size)
    }

    /** The whole point of matching by overlap: a line whose own start predates
     *  the trip still holds the trip's first points, and only those survive. */
    @Test
    fun keepsTheOpeningLineButTrimsThePreTripIdlePoints() {
        // Idle points from 5 minutes before the trip share the opening line.
        val opening = segment(1_000_000, 15)
        val points = matchTripPoints(listOf(opening), trip(1_300_000, 1_840_000))
        assertEquals(1_300_000L, points.first().timeMs)
        assertEquals(10, points.size)
    }

    /** flushTrace(keepLast = true) repeats the boundary point as the first
     *  point of the next line; the reassembled trace must not contain it twice. */
    @Test
    fun dropsTheDuplicatedPointAtALineSeam() {
        val a = segment(1_000_000, 3)
        val seam = a.points.last()
        val b = TraceSegment(
            listOf(seam) + segment(1_180_000, 2, startLat = 52.003).points,
            seam.timeMs,
            1_240_000,
        )
        val points = matchTripPoints(listOf(a, b), trip(1_000_000, 1_300_000))
        assertEquals(5, points.size)
        assertEquals(1, points.count { it.timeMs == seam.timeMs })
    }

    @Test
    fun ignoresLinesFromOutsideTheTrip() {
        val earlier = segment(1_000_000, 3)
        val theTrip = segment(5_000_000, 3, startLat = 53.0)
        val later = segment(9_000_000, 3, startLat = 54.0)
        val points = matchTripPoints(listOf(earlier, theTrip, later), trip(5_000_000, 5_120_000))
        assertEquals(3, points.size)
        assertEquals(53.0, points.first().at.lat, 1e-9)
    }
}
