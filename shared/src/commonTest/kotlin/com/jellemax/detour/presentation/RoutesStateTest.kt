package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure half of the Routes screen: how a route card reads, and how a route's
 * polyline is normalised into the little preview sketch. File import/export and
 * the Maps hand-off stay Android-side and are not modelled here.
 */
class RoutesStateTest {

    @Test fun aSubtitleReadsDistanceThenStopCount() {
        assertEquals(
            "214 km · 5 stops",
            routeSubtitle(distanceMeters = 214_000.0, stopCount = 5),
        )
    }

    @Test fun aSingleStopIsNotPluralised() {
        assertEquals("12 km · 1 stop", routeSubtitle(distanceMeters = 12_000.0, stopCount = 1))
    }

    @Test fun longDistancesAreThousandsGrouped() {
        assertEquals("1 240 km · 3 stops", routeSubtitle(distanceMeters = 1_240_000.0, stopCount = 3))
    }

    @Test fun anUnroutedRouteShowsOnlyItsStopCount() {
        // distanceMeters is null until the route has been routed at least once.
        assertEquals("3 stops", routeSubtitle(distanceMeters = null, stopCount = 3))
    }

    @Test fun thumbnailPointsSpanTheBoxMinusItsPadding() {
        val line = listOf(LatLon(50.0, 5.0), LatLon(51.0, 6.0))
        val pts = thumbnailPoints(line, width = 100.0, height = 50.0, padding = 10.0)
        assertEquals(2, pts.size)
        assertEquals(10.0, pts.minOf { it.x }, absoluteTolerance = 1e-9)
        assertEquals(90.0, pts.maxOf { it.x }, absoluteTolerance = 1e-9)
        assertEquals(10.0, pts.minOf { it.y }, absoluteTolerance = 1e-9)
        assertEquals(40.0, pts.maxOf { it.y }, absoluteTolerance = 1e-9)
    }

    @Test fun latitudeIsFlippedSoNorthIsUp() {
        // Screen y grows downward; the northern point must land at the smaller y.
        val pts = thumbnailPoints(
            listOf(LatLon(50.0, 5.0), LatLon(51.0, 6.0)),
            width = 100.0, height = 50.0, padding = 0.0,
        )
        assertTrue(pts[1].y < pts[0].y)
    }

    @Test fun aDegeneratePolylineCollapsesToTheBoxCentreRatherThanDividingByZero() {
        val pts = thumbnailPoints(
            listOf(LatLon(50.0, 5.0), LatLon(50.0, 5.0)),
            width = 100.0, height = 50.0, padding = 10.0,
        )
        assertEquals(50.0, pts[0].x, absoluteTolerance = 1e-9)
        assertEquals(25.0, pts[0].y, absoluteTolerance = 1e-9)
    }

    @Test fun anEmptyPolylineProducesNoPoints() {
        assertTrue(thumbnailPoints(emptyList(), 100.0, 50.0, 10.0).isEmpty())
    }
}
