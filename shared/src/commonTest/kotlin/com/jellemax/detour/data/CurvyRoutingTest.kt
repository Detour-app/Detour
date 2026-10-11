package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The curvy A→B request (#651) through [routeViaRequest], the pure half of
 * [RoutingClient.routeVia] — `Http` has no injection seam and this source set
 * no MockEngine, so the request is asserted as built rather than as sent.
 * Rules are literal JSON, so a changed multiplier shows up here rather than as
 * a different route.
 */
class CurvyRoutingTest {

    private val base = "https://gh.example"
    private val points = listOf(LatLon(50.88, 4.70), LatLon(50.80, 4.90))
    private val moto = TravelMode.MOTO.ghProfile
    private val car = TravelMode.CAR.ghProfile

    private val plainGet = "$base/route?profile=moto&point=50.88,4.7&point=50.8,4.9" +
        "&points_encoded=false&details=max_speed&details=roundabout"

    private fun motoBody(priority: String) =
        """{"profile":"moto","points":[[4.7,50.88],[4.9,50.8]],"points_encoded":false,""" +
            """"details":["max_speed","roundabout"],"ch.disable":true,""" +
            """"custom_model":{"priority":[$priority]}}"""

    private val motorwayBalanced = """{"if":"road_class == MOTORWAY || road_class == TRUNK","multiply_by":0.5}"""
    private val straightBalanced = """{"if":"curvature >= 0.98","multiply_by":0.7}"""
    private val motorwayTwisty = """{"if":"road_class == MOTORWAY || road_class == TRUNK","multiply_by":0.1}"""
    private val straightTwisty = """{"if":"curvature >= 0.98","multiply_by":0.4}"""
    private val nearlyStraightTwisty =
        """{"if":"curvature >= 0.9 && curvature < 0.98","multiply_by":0.7}"""

    private fun request(preferences: RoutePreferences, profile: String = moto) =
        routeViaRequest(base, points, profile, preferences, heading = null)

    @Test
    fun fastIsThePlainGetItWasBefore() {
        assertEquals(RouteRequest(plainGet, null), request(RoutePreferences(curvy = CurvyPreset.FAST)))
    }

    @Test
    fun balancedPostsItsTwoRules() {
        assertEquals(
            RouteRequest("$base/route", motoBody("$motorwayBalanced,$straightBalanced")),
            request(RoutePreferences(curvy = CurvyPreset.BALANCED)),
        )
    }

    @Test
    fun twistyPostsItsThreeRules() {
        assertEquals(
            RouteRequest("$base/route", motoBody("$motorwayTwisty,$straightTwisty,$nearlyStraightTwisty")),
            request(RoutePreferences(curvy = CurvyPreset.TWISTY)),
        )
    }

    @Test
    fun aPresetFollowsTheAvoidRules() {
        val tolls = """{"if":"toll == ALL","multiply_by":0.05}"""
        assertEquals(
            RouteRequest("$base/route", motoBody("$tolls,$motorwayBalanced,$straightBalanced")),
            request(RoutePreferences(avoidTolls = true, curvy = CurvyPreset.BALANCED)),
        )
    }

    @Test
    fun aCarRouteIgnoresThePreset() {
        assertEquals(
            request(RoutePreferences(), profile = car),
            request(RoutePreferences(curvy = CurvyPreset.TWISTY), profile = car),
        )
    }

    @Test
    fun aRoundTripNeverCarriesThePreset() {
        assertEquals("[]", RoutePreferences(curvy = CurvyPreset.TWISTY).priorityRules().toString())
    }

    @Test
    fun aGraphWithoutCurvatureFallsBackToFast() {
        val twisty = RoutePreferences(avoidSmallRoads = true, curvy = CurvyPreset.TWISTY)
        assertEquals(CurvyPreset.FAST, twisty.supportedBy(setOf("road_class", "toll")).curvy)
        assertEquals(CurvyPreset.FAST, twisty.supportedBy(null).curvy)
        assertEquals(twisty, twisty.supportedBy(setOf("curvature")))
    }

    private fun route(timeMs: Long?, distanceMeters: Double?) = RouteResult(
        polyline = points,
        waypoints = emptyList(),
        distanceMeters = distanceMeters,
        timeMs = timeMs,
    )

    @Test
    fun detourCostIsTheDifferenceToTheFastest() {
        val cost = DetourCost.between(
            route(timeMs = 3_420_000, distanceMeters = 61_500.0),
            route(timeMs = 2_700_000, distanceMeters = 52_500.0),
        )
        assertEquals(DetourCost(extraSeconds = 720, extraMeters = 9_000.0), cost)
        assertEquals(12, cost?.extraMinutes)
        assertEquals(9.0, cost?.extraKm)
    }

    @Test
    fun extraMinutesRoundToNearest() {
        assertEquals(1, DetourCost(extraSeconds = 89, extraMeters = 0.0).extraMinutes)
        assertEquals(2, DetourCost(extraSeconds = 90, extraMeters = 0.0).extraMinutes)
        assertEquals(0, DetourCost(extraSeconds = 29, extraMeters = 0.0).extraMinutes)
    }

    @Test
    fun aQuickerCurvyRouteCostsLessThanNothing() {
        val cost = DetourCost.between(
            route(timeMs = 2_640_000, distanceMeters = 50_000.0),
            route(timeMs = 2_700_000, distanceMeters = 52_500.0),
        )
        assertEquals(DetourCost(extraSeconds = -60, extraMeters = -2_500.0), cost)
    }

    @Test
    fun aMissingTimeOrDistanceGivesNoCost() {
        val full = route(timeMs = 2_700_000, distanceMeters = 52_500.0)
        assertNull(DetourCost.between(route(timeMs = null, distanceMeters = 61_500.0), full))
        assertNull(DetourCost.between(full, route(timeMs = 2_700_000, distanceMeters = null)))
    }
}
