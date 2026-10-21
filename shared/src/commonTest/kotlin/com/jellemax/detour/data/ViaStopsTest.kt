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

    /** Rides [line]'s fixes one by one from a fresh start, as the map does. */
    private fun rideTo(line: List<LatLon>, vararg fixes: LatLon): ViaStops.Ride =
        fixes.fold(null as ViaStops.Ride?) { ride, fix -> ViaStops.follow(ride, line, fix, OFF_M) }!!

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

        val before = rideTo(line, *fixes.take(40).toTypedArray()) // up to 5.0975
        assertEquals(listOf(farm, work), ViaStops.ahead(listOf(farm, work), before))

        val past = ViaStops.follow(before, line, LatLon(52.0, 5.102), OFF_M)
        assertEquals(listOf(work), ViaStops.ahead(listOf(farm, work), past))
        assertTrue(RoadRoulette.distanceMeters(LatLon(52.0, 5.102), farm) > 300.0)
    }

    /** Home → store → home on the same road ends where it starts. The first
     *  fix is as near the line's end as its start; it must not pass the store. */
    @Test fun aRoundTripsFirstFixDoesNotPassItsStop() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)

        assertEquals(listOf(store), ViaStops.ahead(listOf(store), rideTo(line, home)))
    }

    @Test fun aRoundTripsStopIsPassedOnTheWayBack() {
        val line = road(5.0, 5.1) + road(5.1, 5.0).drop(1)
        val out = (0..40).map { LatLon(52.0, 5.0 + it * 0.0025) }
        val back = (1..10).map { LatLon(52.0, 5.1 - it * 0.0025) }

        val ride = rideTo(line, *(out + back).toTypedArray())
        assertEquals(emptyList(), ViaStops.ahead(listOf(store), ride))
    }

    @Test fun aStopNotYetPassedStaysAheadHoweverFarTheRiderStrays() {
        val line = road(5.0, 5.3)
        val lost = LatLon(52.5, 6.0)
        assertEquals(
            listOf(store, bakery),
            ViaStops.ahead(listOf(store, bakery), rideTo(line, home, lost)),
            "a reroute off the line must still go through every stop not passed",
        )
    }

    /** A gap in fixes (a tunnel, the app in the background) lands the rider
     *  past the window; the rest of the line is searched, so the stops they
     *  rode past while unseen are passed. */
    @Test fun aGapInFixesPassesTheStopsRiddenPastUnseen() {
        val line = road(5.0, 5.3)
        assertEquals(listOf(work), ViaStops.ahead(listOf(store, bakery, work), rideTo(line, home, LatLon(52.0, 5.25))))
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
        val farOnOld = rideTo(old, home, LatLon(52.0, 5.25))

        val ride = ViaStops.follow(farOnOld, rerouted, LatLon(52.0, 5.2), OFF_M)
        assertEquals(0.0, ride.along.meters, 1.0)
    }

    private companion object {
        const val OFF_M = 60.0
    }
}
