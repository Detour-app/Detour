package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteStop
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.TravelMode
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

    @Test fun aRoutedRouteAppendsItsDuration() {
        assertEquals(
            "214 km · 5 stops · 25 min",
            routeSubtitle(distanceMeters = 214_000.0, stopCount = 5, timeMs = 25 * 60_000L),
        )
    }

    @Test fun anHourOrMoreSpellsOutHoursAndMinutes() {
        assertEquals(
            "214 km · 5 stops · 1 h 12 min",
            routeSubtitle(distanceMeters = 214_000.0, stopCount = 5, timeMs = 72 * 60_000L),
        )
    }

    @Test fun aMissingDurationLeavesNoStraySeparator() {
        assertEquals(
            "214 km · 5 stops",
            routeSubtitle(distanceMeters = 214_000.0, stopCount = 5, timeMs = null),
        )
    }

    @Test fun aMissingDistanceWithADurationStillComposesCleanly() {
        assertEquals(
            "5 stops · 25 min",
            routeSubtitle(distanceMeters = null, stopCount = 5, timeMs = 25 * 60_000L),
        )
    }

    @Test fun aSubKilometreDistanceReadsInMetresRatherThanRoundingToZeroKilometres() {
        // The old formatDistanceKm did the same under a kilometre; a route
        // this short must never read "0 km".
        assertEquals("850 m · 3 stops", routeSubtitle(distanceMeters = 850.0, stopCount = 3))
    }

    @Test fun exactlyOneKilometreReadsAsAWholeKilometreNotAMetreCount() {
        assertEquals("1 km · 1 stop", routeSubtitle(distanceMeters = 1_000.0, stopCount = 1))
    }

    @Test fun aFractionalKilometreDistanceIsRoundedRatherThanTruncated() {
        // 214.9 km truncated to a Long used to read "214 km"; rounded, it's 215.
        assertEquals("215 km · 5 stops", routeSubtitle(distanceMeters = 214_900.0, stopCount = 5))
    }

    @Test fun aSharedRouteAppendsWhoItCameFrom() {
        assertEquals(
            "214 km · 5 stops · shared by mika",
            routeSubtitle(distanceMeters = 214_000.0, stopCount = 5, sharedBy = "mika"),
        )
    }

    @Test fun aBlankSharedByLeavesNoStraySeparator() {
        // The common case: a route the rider made themselves, not shared.
        assertEquals("214 km · 5 stops", routeSubtitle(distanceMeters = 214_000.0, stopCount = 5, sharedBy = ""))
    }

    @Test fun aSharedRoutedRouteComposesDistanceStopsTimeThenSharedBy() {
        assertEquals(
            "214 km · 5 stops · 25 min · shared by mika",
            routeSubtitle(
                distanceMeters = 214_000.0, stopCount = 5, timeMs = 25 * 60_000L, sharedBy = "mika",
            ),
        )
    }

    @Test fun aStoredRouteBecomesACardCarryingWhoSharedIt() {
        val route = SavedRoute(
            id = 7L,
            name = "Commute scenic",
            createdMs = 0L,
            mode = TravelMode.CAR,
            stops = listOf(RouteStop(LatLon(50.0, 5.0)), RouteStop(LatLon(50.1, 5.1))),
            polyline = listOf(LatLon(50.0, 5.0), LatLon(50.1, 5.1)),
            distanceMeters = 32_000.0,
            timeMs = null,
            sharedBy = "mika",
        )
        val card = routesStateFrom(listOf(route)).single()
        assertEquals(7L, card.id)
        assertEquals("Commute scenic", card.name)
        assertEquals("32 km · 2 stops · shared by mika", card.subtitle)
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
