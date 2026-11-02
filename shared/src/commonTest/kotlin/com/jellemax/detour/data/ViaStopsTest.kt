package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ViaStops]: which stops a planned route is ridden through in-app (#736),
 * and which of them are still ahead as the rider passes them — by progress
 * along the line past each stop's projection, per the owner's decision.
 */
class ViaStopsTest {

    private val home = LatLon(52.0, 5.0)
    private val store = LatLon(52.0, 5.1)
    private val bakery = LatLon(52.0, 5.2)
    private val work = LatLon(52.0, 5.3)

    private fun planned(vararg stops: LatLon): SavedRoute = SavedRoute(
        id = 1L,
        name = "Errands",
        createdMs = 0L,
        mode = TravelMode.MOTO,
        stops = stops.map { RouteStop(it) },
        polyline = emptyList(),
        distanceMeters = null,
        timeMs = null,
    )

    /** A road due east along 52°N, a point every 0.005° (~340 m). */
    private fun road(fromLon: Double, toLon: Double): List<LatLon> {
        val steps = kotlin.math.round(kotlin.math.abs(toLon - fromLon) / 0.005).toInt()
        return (0..steps).map { LatLon(52.0, fromLon + (toLon - fromLon) * it / steps) }
    }

    /** Rides [line], fetched through [vias], fix by fix from a fresh start,
     *  as the map does. */
    private fun rideTo(line: List<LatLon>, vias: List<LatLon>, vararg fixes: LatLon): ViaStops.Ride =
        fixes.fold(null as ViaStops.Ride?) { ride, fix -> ViaStops.follow(ride, line, vias, fix, OFF_M) }!!

    @Test fun theMiddleStopsAreTheVias() {
        assertEquals(listOf(store, bakery), ViaStops.of(planned(home, store, bakery, work)))
    }

    @Test fun aTwoStopRouteHasNoVias() {
        assertEquals(emptyList(), ViaStops.of(planned(home, work)))
    }

    @Test fun theLineStartsAtTheRiderNotAtTheRoutesFirstStop() {
        val rider = LatLon(51.9, 4.9)
        assertEquals(
            listOf(rider, store, work),
            ViaStops.points(rider, ViaStops.of(planned(home, store, work)), work),
            "Go must route from where the rider is, through the store, to work",
        )
    }

    /** The owner's case: a stop off the road is never within any radius of
     *  the rider, but riding past where it projects onto the line passes it. */
    @Test fun anOffRoadStopIsPassedOnceTheRiderIsPastItsProjection() {
        val line = road(5.0, 5.3)
        val farm = LatLon(52.003, 5.1) // ~330 m north of the road
        val fixes = (0..40).map { LatLon(52.0, 5.0 + it * 0.0025) } // up to 5.1

        val before = rideTo(line, listOf(farm, work), *fixes.take(40).toTypedArray()) // up to 5.0975
        assertEquals(listOf(farm, work), ViaStops.ahead(before))

        val past = ViaStops.follow(before, line, listOf(farm, work), LatLon(52.0, 5.102), OFF_M)
        assertEquals(listOf(work), ViaStops.ahead(past))
        assertTrue(RoadRoulette.distanceMeters(LatLon(52.0, 5.102), farm) > 300.0)
    }

    /** Home → store → home on the same road ends where it starts. The first
     *  fix is as near the line's end as its start; it must not pass the store. */
    @Test fun aRoundTripsFirstFixDoesNotPassItsStop() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)

        assertEquals(listOf(store), ViaStops.ahead(rideTo(line, listOf(store), home)))
    }

    @Test fun aRoundTripsStopIsPassedOnTheWayBack() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)
        val out = (0..40).map { LatLon(52.0, 5.0 + it * 0.0025) }
        val back = (1..10).map { LatLon(52.0, 5.1 - it * 0.0025) }

        val ride = rideTo(line, listOf(store), *(out + back).toTypedArray())
        assertEquals(emptyList(), ViaStops.ahead(ride))
    }

    @Test fun aStopNotYetPassedStaysAheadHoweverFarTheRiderStrays() {
        val line = road(5.0, 5.3)
        val lost = LatLon(52.5, 6.0)
        assertEquals(
            listOf(store, bakery),
            ViaStops.ahead(rideTo(line, listOf(store, bakery), home, lost)),
            "a reroute off the line must still go through every stop not passed",
        )
    }

    /** A gap in fixes (a tunnel, the app in the background) lands the rider
     *  past the window; the rest of the line is searched, so the stops they
     *  rode past while unseen are passed. */
    @Test fun aGapInFixesPassesTheStopsRiddenPastUnseen() {
        val line = road(5.0, 5.3)
        val vias = listOf(store, bakery, work)
        assertEquals(listOf(work), ViaStops.ahead(rideTo(line, vias, home, LatLon(52.0, 5.25))))
    }

    /** A stop on a road the line rides twice lands on the leg after the stop
     *  before it, not on the first leg it happens to be near. */
    @Test fun aLaterStopOnARoadRiddenTwiceProjectsOntoTheLaterLeg() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)
        val corner = LatLon(52.0, 5.05)

        val (tip, back) = ViaStops.projectedMeters(line, listOf(store, corner))
        assertTrue(back > tip, "the corner after the store is on the way back: $tip, $back")
    }

    @Test fun aNewLineStartsOverFromItsFirstPoint() {
        val old = road(5.0, 5.3)
        val rerouted = road(5.2, 5.3)
        val farOnOld = rideTo(old, listOf(store, work), home, LatLon(52.0, 5.25))

        val ride = ViaStops.follow(farOnOld, rerouted, listOf(work), LatLon(52.0, 5.2), OFF_M)
        assertEquals(0.0, ride.along.meters, 1.0)
        assertEquals(listOf(work), ride.vias, "a new line is ridden through the stops it was fetched through")
    }

    /** Out to the store, back past the corner, home. Once the store is passed
     *  the corner is the only stop left; it must still be the one on the way
     *  back, not the outbound corner the rider is already past. */
    @Test fun aStopLeftAfterOneIsPassedKeepsItsProjectionOnTheLaterLeg() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)
        val corner = LatLon(52.0, 5.05)
        val vias = listOf(store, corner)
        val out = (0..40).map { LatLon(52.0, 5.0 + it * 0.0025) } // to the store
        val back = (1..16).map { LatLon(52.0, 5.1 - it * 0.0025) } // down to 5.06

        var ride = rideTo(line, vias, *(out + back.first()).toTypedArray())
        assertEquals(listOf(corner), ViaStops.ahead(ride))
        // The map hands over what is left; on the same line the ride keeps its own list.
        ride = back.drop(1).fold(ride) { r, fix -> ViaStops.follow(r, line, listOf(corner), fix, OFF_M) }
        assertEquals(listOf(corner), ViaStops.ahead(ride), "at 5.06 on the way back the corner is still ahead")

        ride = (17..24).map { LatLon(52.0, 5.1 - it * 0.0025) } // down to 5.04
            .fold(ride) { r, fix -> ViaStops.follow(r, line, listOf(corner), fix, OFF_M) }
        assertEquals(emptyList(), ViaStops.ahead(ride))
    }

    /** Home → store → home: on the way back the global snap ties to the
     *  outbound leg, which never gets near the end. Placed by the forward
     *  ride, the remaining distance falls to the door and the trip arrives. */
    @Test fun aRoundTripsRemainingDistanceFallsOnTheWayBack() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)
        val route = RouteResult(polyline = line, waypoints = emptyList(), distanceMeters = null)
        val out = (0..40).map { LatLon(52.0, 5.0 + it * 0.0025) }
        val back = (1..40).map { LatLon(52.0, 5.1 - it * 0.0025) } // to the door
        val ride = rideTo(line, listOf(store), *(out + back).toTypedArray())

        val forward = NavEngine.progressAlong(route, ride.along, offRouteMeters = 0.0)!!
        assertTrue(forward.remainingMeters < 40.0, "remaining ${forward.remainingMeters} m at the door")
        val global = NavEngine.progress(route, home)!!
        assertTrue(global.remainingMeters > 6000.0, "the global snap's answer, the bug: ${global.remainingMeters}")
    }

    private companion object {
        const val OFF_M = 60.0
    }
}
