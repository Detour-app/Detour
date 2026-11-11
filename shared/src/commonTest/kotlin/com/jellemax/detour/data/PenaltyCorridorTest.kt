package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [PenaltyCorridor.polygons] (#918): the band a turnaround loop's return keeps off. */
class PenaltyCorridorTest {

    private val start = LatLon(50.88, 4.70)

    /** Ray casting in a flat projection around the ring's first point. */
    private fun inside(p: LatLon, ring: List<LatLon>): Boolean {
        val o = ring.first()
        val k = cos(o.lat * PI / 180.0)
        fun x(q: LatLon) = (q.lon - o.lon) * k
        fun y(q: LatLon) = q.lat - o.lat
        var hit = false
        for (i in 0 until ring.size - 1) {
            val a = ring[i]
            val b = ring[i + 1]
            if ((y(a) > y(p)) != (y(b) > y(p))) {
                val xCross = x(a) + (y(p) - y(a)) / (y(b) - y(a)) * (x(b) - x(a))
                if (x(p) < xCross) hit = !hit
            }
        }
        return hit
    }

    private fun covered(p: LatLon, quads: List<List<LatLon>>) = quads.any { inside(p, it) }

    @Test
    fun aStraightLineIsOneQuadFortyMetresEitherSide() {
        val end = RoadRoulette.offset(start, 10_000.0, PI / 2) // 10 km east
        val line = (0..100).map { RoadRoulette.offset(start, it * 100.0, PI / 2) }
        val quads = PenaltyCorridor.polygons(line)
        assertEquals(1, quads.size)
        val ring = quads.single()
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        // Each corner is 40 m off the line (north or south) and 40 m past an end.
        ring.dropLast(1).forEach {
            assertEquals(40.0, RoadRoulette.distanceMeters(LatLon(start.lat, it.lon), it), 0.5)
        }
        assertEquals(40.0, RoadRoulette.distanceMeters(LatLon(start.lat, ring.minOf { it.lon }), start), 0.5)
        assertEquals(40.0, RoadRoulette.distanceMeters(end, LatLon(end.lat, ring.maxOf { it.lon })), 0.5)
        val midway = RoadRoulette.offset(start, 5_000.0, PI / 2)
        assertTrue(covered(RoadRoulette.offset(midway, 39.0, 0.0), quads))
        assertFalse(covered(RoadRoulette.offset(midway, 41.0, 0.0), quads))
    }

    @Test
    fun aLongWigglyLineStaysUnderTheCapAndCovered() {
        // 2000 vertices, 50 m apart, zig-zagging 30 m so nothing simplifies away cheaply.
        val line = (0 until 2000).map { i ->
            val along = RoadRoulette.offset(start, i * 50.0, PI / 4)
            RoadRoulette.offset(along, if (i % 2 == 0) 30.0 else 0.0, 3 * PI / 4)
        }
        val quads = PenaltyCorridor.polygons(line)
        assertTrue(quads.size <= 150, "${quads.size} quads")
        assertTrue(quads.isNotEmpty())
        line.forEach { assertTrue(covered(it, quads), "vertex $it outside") }
    }

    @Test
    fun fewerThanTwoPointsIsEmpty() {
        assertEquals(emptyList(), PenaltyCorridor.polygons(emptyList()))
        assertEquals(emptyList(), PenaltyCorridor.polygons(listOf(start)))
        assertEquals(emptyList(), PenaltyCorridor.polygons(listOf(start, start, start)))
    }

    @Test
    fun hairpinsKeepEveryVertexInside() {
        // A mountain road: switchbacks of 400 m legs joined by tight 15 m-radius hairpins.
        val line = ArrayList<LatLon>()
        var p = start
        for (leg in 0 until 12) {
            val heading = if (leg % 2 == 0) 0.0 else PI
            repeat(40) { p = RoadRoulette.offset(p, 10.0, heading); line.add(p) }
            val turnRight = leg % 2 == 0
            for (step in 1..12) {
                val a = heading + (if (turnRight) 1 else -1) * PI * step / 12
                p = RoadRoulette.offset(p, 15.0 * PI / 12, a)
                line.add(p)
            }
        }
        val quads = PenaltyCorridor.polygons(line)
        assertTrue(quads.size in 1..150)
        line.forEach { assertTrue(covered(it, quads), "vertex $it outside") }
        quads.forEach { assertEquals(it.first(), it.last()) }
    }

    @Test
    fun aTightCapWidensQuadsAndStillCovers() {
        val line = (0 until 500).map { i ->
            RoadRoulette.offset(RoadRoulette.offset(start, i * 20.0, 0.0), 25.0 * sin(i / 3.0), PI / 2)
        }
        val quads = PenaltyCorridor.polygons(line, maxSegments = 5)
        assertTrue(quads.size <= 5, "${quads.size} quads")
        line.forEach { assertTrue(covered(it, quads), "vertex $it outside") }
    }
}
