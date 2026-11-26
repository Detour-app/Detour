package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers ViaSampler.kt: the Google Maps handoff vias (#1079). Google snaps a bare
 * via with no direction of travel, so one at a junction or on a motorway can send
 * the rider through the next interchange.
 */
class ViaSamplerTest {

    /** Due east along latitude 51, one vertex per 0.0001° of longitude (~7 m). */
    private fun east(fromStep: Int, toStep: Int): List<LatLon> =
        (fromStep..toStep).map { LatLon(51.0, 4.0 + it * 0.0001) }

    /** Dense vertices around a junction, then one long straight: index sampling lands on the cluster. */
    private val clustered = east(0, 100) + LatLon(51.0, 4.2)

    private fun alongMeters(line: List<LatLon>, p: LatLon): Double {
        val start = line.first()
        return RoadRoulette.distanceMeters(start, LatLon(start.lat, p.lon))
    }

    private val clusteredLength = alongMeters(clustered, clustered.last())

    @Test
    fun viasAreSpacedByDistanceNotVertexIndex() {
        val vias = ViaSampler.sample(clustered, 4)
        assertEquals(4, vias.size)
        vias.forEachIndexed { i, p ->
            val want = (i + 1) * clusteredLength / 5
            assertEquals(want, alongMeters(clustered, p), 1.0, "via $i")
        }
    }

    @Test
    fun viasKeepClearOfEveryInstructionStart() {
        val line = east(0, 1000) // ~7 km of vertices 7 m apart
        val turns = listOf(0, 200, 400, 600, 800, 1000)
        val vias = ViaSampler.sample(line, 4, turnIndices = turns)
        assertEquals(4, vias.size)
        for (via in vias) for (t in turns) {
            val gap = kotlin.math.abs(alongMeters(line, via) - alongMeters(line, line[t]))
            assertTrue(gap >= ViaSampler.TURN_CLEARANCE_M - 0.5, "via $via is ${gap}m from turn at $t")
        }
    }

    @Test
    fun viasStayOffMotorwaySpansAndKeepRouteOrder() {
        val line = east(0, 1000)
        val motorway = 300..700
        val vias = ViaSampler.sample(line, 4, avoidSpans = listOf(motorway))
        val from = alongMeters(line, line[motorway.first])
        val to = alongMeters(line, line[motorway.last])
        val at = vias.map { alongMeters(line, it) }
        assertTrue(at.none { it > from + 0.5 && it < to - 0.5 }, "a via on the motorway: $at")
        assertEquals(at.sorted(), at)
    }

    @Test
    fun aSlotWithNoClearSpotIsDroppedRatherThanPushedOntoTheMotorway() {
        val line = east(0, 1000)
        val vias = ViaSampler.sample(line, 4, avoidSpans = listOf(150..850))
        assertTrue(vias.size in 1..3, "got $vias")
    }

    @Test
    fun anAllMotorwayRouteStillGetsItsVias() {
        val line = east(0, 1000)
        assertEquals(4, ViaSampler.sample(line, 4, avoidSpans = listOf(0..1000)).size)
    }

    @Test
    fun noInteriorNoVias() {
        assertEquals(emptyList(), ViaSampler.sample(east(0, 1), 8))
        assertEquals(emptyList(), ViaSampler.sample(List(5) { LatLon(51.0, 4.0) }, 8))
    }

    @Test
    fun parsedRoutesTakeTheirViasClearOfMotorwayClassSpans() {
        val coords = east(0, 1000).joinToString(",") { "[${it.lon},${it.lat}]" }
        val route = RoutingClient.parseRoute(
            """
            {"paths":[{
              "points":{"type":"LineString","coordinates":[$coords]},
              "instructions":[
                {"text":"Continue","distance":7000,"sign":0,"interval":[0,1000]},
                {"text":"Arrive","distance":0,"sign":4,"interval":[1000,1000]}
              ],
              "details":{"road_class":[[0,200,"secondary"],[200,800,"motorway"],[800,1000,"tertiary"]]}
            }]}
            """.trimIndent(),
        )
        val line = route.polyline
        val from = alongMeters(line, line[200])
        val to = alongMeters(line, line[800])
        val at = route.waypoints.map { alongMeters(line, it) }
        assertTrue(at.isNotEmpty())
        assertTrue(at.none { it > from + 0.5 && it < to - 0.5 }, "a via on the motorway: $at")
    }
}
