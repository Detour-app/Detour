package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [TripTraces] - how a trip's recorded route is reassembled out of the
 * trace store. The tracker splits one ride across several stored lines (every
 * 200 points, on a big GPS gap, on a STILL transition), and the line that
 * opens a trip also carries whatever idle points were still in the buffer when
 * it began. Matching a trip to a single nearest line therefore truncated every
 * ride past ~5 km and could drop its opening stretch entirely, which is what
 * these tests pin down. Moved from app/ with the matching itself (#669), so
 * iOS runs them too.
 */
class TripTracesTest {

    private fun trip(startMs: Long, endMs: Long) = Trip(
        startTimeMs = startMs,
        endTimeMs = endMs,
        distanceMeters = 0.0,
        topSpeedMps = 0.0,
        destinationLat = null,
        destinationLon = null,
    )

    private fun point(lat: Double, timeMs: Long) =
        TraceStore.TracePoint(at = LatLon(lat, 4.9), timeMs = timeMs, speedKmh = 50.0, leanDeg = null)

    /** Points a minute apart, walking north so each one is distinguishable. */
    private fun segment(firstMs: Long, count: Int, startLat: Double = 52.0): TraceSegment {
        val points = (0 until count).map { i -> point(startLat + i * 0.001, firstMs + i * 60_000L) }
        return TraceSegment(points, points.first().timeMs, points.last().timeMs)
    }

    @Test
    fun stitchesEveryLineTheRideWasSplitAcross() {
        val a = segment(1_000_000, 3)
        val b = segment(1_180_000, 3, startLat = 52.003)
        val c = segment(1_360_000, 3, startLat = 52.006)
        val points = TripTraces.matchTripPoints(listOf(a, b, c), trip(1_000_000, 1_480_000))
        assertEquals(9, points.size)
    }

    /** The whole point of matching by overlap: a line whose own start predates
     *  the trip still holds the trip's first points, and only those survive. */
    @Test
    fun keepsTheOpeningLineButTrimsThePreTripIdlePoints() {
        // Idle points from 5 minutes before the trip share the opening line.
        val opening = segment(1_000_000, 15)
        val points = TripTraces.matchTripPoints(listOf(opening), trip(1_300_000, 1_840_000))
        assertEquals(1_300_000L, points.first().timeMs)
        assertEquals(10, points.size)
    }

    /** The tracker's startup lag: a point a few seconds either side of the
     *  trip's window is the trip's, one past the 10 s slack is not. */
    @Test
    fun keepsPointsWithinTenSecondsOfTheTripWindowAndNoFurther() {
        val line = listOf(
            point(52.000, 989_999),
            point(52.001, 990_000),
            point(52.002, 1_100_000),
            point(52.003, 1_210_000),
            point(52.004, 1_210_001),
        )
        val points = TripTraces.matchTripPoints(TripTraces.segmentsOf(listOf(line)), trip(1_000_000, 1_200_000))
        assertEquals(listOf(990_000L, 1_100_000L, 1_210_000L), points.map { it.timeMs })
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
        val points = TripTraces.matchTripPoints(listOf(a, b), trip(1_000_000, 1_300_000))
        assertEquals(5, points.size)
        assertEquals(1, points.count { it.timeMs == seam.timeMs }, "seam point repeated in the stitched trace")
    }

    @Test
    fun ignoresLinesFromOutsideTheTrip() {
        val earlier = segment(1_000_000, 3)
        val theTrip = segment(5_000_000, 3, startLat = 53.0)
        val later = segment(9_000_000, 3, startLat = 54.0)
        val points = TripTraces.matchTripPoints(listOf(earlier, theTrip, later), trip(5_000_000, 5_120_000))
        assertEquals(3, points.size)
        assertEquals(53.0, points.first().at.lat, absoluteTolerance = 1e-9)
    }

    /** A line written before points carried a time can't be matched to any
     *  trip, so it is not a segment; untimed points inside a timed line don't
     *  stretch its window. */
    @Test
    fun segmentWindowSpansOnlyTimedPoints() {
        val untimed = listOf(point(52.0, -1), point(52.001, -1))
        val mixed = listOf(point(52.0, -1), point(52.001, 2_000), point(52.002, 1_000))
        val segments = TripTraces.segmentsOf(listOf(untimed, mixed))
        assertEquals(1, segments.size)
        assertEquals(1_000L to 2_000L, segments.single().startMs to segments.single().endMs)
    }

    private fun town(id: Long, name: String, lat: Double) = Municipality(
        id, name, listOf(listOf(LatLon(lat, 4.8), LatLon(lat, 5.0), LatLon(lat + 0.0015, 5.0), LatLon(lat + 0.0015, 4.8))),
    )

    /** The places iOS was missing (#669): a matched trip yields the towns it
     *  entered in order, and a trip with no trace yields nothing at all. */
    @Test
    fun matchListsTheTownsEachTracedTripEntered() {
        val ride = trip(1_000_000, 1_120_000)
        val untraced = trip(9_000_000, 9_100_000)
        val towns = listOf(town(2, "Beta", 52.0015), town(1, "Alpha", 52.0))
        val matched = TripTraces.match(listOf(segment(1_000_000, 3)), listOf(ride, untraced), towns)
        assertEquals(listOf(ride to listOf("Alpha", "Beta")), matched.visits.map { (t, v) -> t to v.map { it.name } })
        assertEquals(setOf(ride.startTimeMs), matched.thumbnails.keys)
        assertTrue(untraced.startTimeMs !in matched.thumbnails, "untraced trip got a thumbnail")
        // One colour step per drawn hop of the thumbnail (#834).
        assertEquals(matched.thumbnails.getValue(ride.startTimeMs).size - 1, matched.speeds.getValue(ride.startTimeMs).size)
    }
}
