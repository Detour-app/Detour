package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TripCardTest {

    private fun trip(mode: TravelMode) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_000_000L + 3_600_000L,
        distanceMeters = 20_000.0,
        topSpeedMps = 30.0,
        maxLeanAngleDeg = 42.0,
        maxGForce = 0.8,
        destinationLat = 51.0,
        destinationLon = 3.4,
        mode = mode,
    )

    // A straight line running due north for 2000 m at roughly 22.24 m per
    // degree-thousandth of latitude near this longitude — long enough that
    // trimming 500 m off each end leaves a clearly shorter middle section,
    // short enough the test stays fast.
    private fun straightLinePoints(count: Int = 41): List<LatLon> =
        (0 until count).map { LatLon(50.8 + it * 0.00045, 3.2) }

    // An "L"-shaped route with real extent on *both* axes, but lopsided —
    // much more north-south distance than east-west. Unlike straightLinePoints
    // (constant longitude) this can't be centered "by accident": it exercises
    // the actual (lon, lat) -> (x, y) mapping on both axes at once.
    private fun lShapedPoints(): List<LatLon> {
        val east = (0 until 20).map { LatLon(50.8, 3.2 + it * 0.0002) }
        val north = (1..20).map { LatLon(50.8 + it * 0.00045, east.last().lon) }
        return east + north
    }

    @Test
    fun emptyPointsProduceEmptyCardWithoutThrowing() {
        val card = TripCardGeometry.build(trip(TravelMode.CAR), emptyList())
        assertTrue(card.points.isEmpty())
    }

    @Test
    fun trimmedByDefaultRemovesPointsNearBothEnds() {
        val full = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints(), full = true)
        val trimmed = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints(), full = false)
        assertTrue(trimmed.points.size < full.points.size)
    }

    @Test
    fun fullSkipsTrimming() {
        val points = straightLinePoints()
        val full = TripCardGeometry.build(trip(TravelMode.CAR), points, full = true)
        // Every input point maps to a normalized output point when nothing is trimmed.
        assertEquals(points.size, full.points.size)
    }

    @Test
    fun normalizedPointsStayWithinUnitBox() {
        val card = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints(), full = true)
        for (p in card.points) {
            assertTrue(p.x in 0f..1f)
            assertTrue(p.y in 0f..1f)
        }
    }

    @Test
    fun normalizedBoundingBoxIsCenteredNotEdgeAnchored() {
        // normalizedPointsStayWithinUnitBox (above) only asserts points fall
        // in [0,1] — a route anchored to a box edge on its shorter axis
        // still satisfies that, so it can't catch the "not centered" bug.
        // This test checks where the content's bounding box actually sits:
        // both axes' content should be centered in the 0..1 box, i.e. the
        // bbox center should be close to (0.5, 0.5), not sitting on an edge.
        val card = TripCardGeometry.build(trip(TravelMode.CAR), lShapedPoints(), full = true)
        val minX = card.points.minOf { it.x }
        val maxX = card.points.maxOf { it.x }
        val minY = card.points.minOf { it.y }
        val maxY = card.points.maxOf { it.y }
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        assertTrue(centerX in 0.49f..0.51f, "expected centerX close to 0.5, was $centerX")
        assertTrue(centerY in 0.49f..0.51f, "expected centerY close to 0.5, was $centerY")
    }

    @Test
    fun destinationTrimmedAwayWhenInsideTheTrimmedSpan() {
        // The destination in `trip()` (51.0, 3.4) is nowhere near the drawn
        // line, so it never collides with the trim window in these fixtures —
        // this test only pins the "present when not trimmed" half; the
        // "trimmed away" half is exercised by construction: a destination
        // equal to one of the trimmed endpoints must come back null.
        val points = straightLinePoints()
        val nearStart = points.first()
        val cardWithDestAtStart = TripCardGeometry.build(
            trip(TravelMode.CAR).copy(destinationLat = nearStart.lat, destinationLon = nearStart.lon),
            points,
            full = false,
        )
        assertNull(cardWithDestAtStart.destination)
    }

    @Test
    fun destinationPresentWhenFull() {
        val points = straightLinePoints()
        val nearStart = points.first()
        val card = TripCardGeometry.build(
            trip(TravelMode.CAR).copy(destinationLat = nearStart.lat, destinationLon = nearStart.lon),
            points,
            full = true,
        )
        assertNotNull(card.destination)
    }

    @Test
    fun motoTripExposesBothPeakLeanAndPeakG() {
        val card = TripCardGeometry.build(trip(TravelMode.MOTO), straightLinePoints())
        assertEquals(42.0, card.peakLeanDeg)
        assertEquals(0.8, card.peakGForce)
    }

    @Test
    fun carTripExposesOnlyPeakG() {
        val card = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints())
        assertNull(card.peakLeanDeg)
        assertEquals(0.8, card.peakGForce)
    }

    @Test
    fun destinationOutsideBboxIsClampedNotTrimmed() {
        // Place destination far beyond the end of the trace line, ensuring it
        // falls outside the natural bounding box of the polyline.
        val points = straightLinePoints()
        val farBeyond = LatLon(50.8 + 41 * 0.00045 + 0.05, 3.2) // way past the last point
        val card = TripCardGeometry.build(
            trip(TravelMode.CAR).copy(destinationLat = farBeyond.lat, destinationLon = farBeyond.lon),
            points,
            full = true,
        )
        // Destination is present (not trimmed away) but must stay within [0, 1].
        assertNotNull(card.destination)
        assertTrue(card.destination.x in 0f..1f)
        assertTrue(card.destination.y in 0f..1f)
    }
}
