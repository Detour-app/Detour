package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripInsightsTest {

    /** 25 m north per point, [secondsPerPoint] apart — the stored trace's own spacing. */
    private fun straight(
        count: Int,
        secondsPerPoint: Long = 1,
        startLat: Double = 50.0,
        startMs: Long = 0,
        lean: (Int) -> Double? = { null },
    ): List<TraceStore.TracePoint> {
        val step = 25.0 / 111_195.0
        return List(count) { i ->
            TraceStore.TracePoint(LatLon(startLat + i * step, 4.0), startMs + i * secondsPerPoint * 1000, 90.0, lean(i))
        }
    }

    @Test
    fun sixtyKilometresSplitsIntoTwoFullSplitsAndATail() {
        val splits = TripInsights.splits(straight(2401))
        assertEquals(listOf(25.0, 25.0, 10.0), splits.map { (it.distanceMeters / 1000).let { km -> kotlin.math.round(km) } })
        // 25 m per second = 90 km/h on every split.
        splits.forEach { assertEquals(25.0, it.avgSpeedMps, 0.2) }
    }

    @Test
    fun aTailUnderATenthOfASplitIsDropped() {
        // 26 km: the last 1 km isn't a split of its own.
        assertEquals(1, TripInsights.splits(straight(1041)).size)
    }

    @Test
    fun aStopIntervalIsNotMovingTime() {
        val moving = straight(11)
        // One 25 m hop that took five minutes: a light, decimated.
        val stopped = moving + straight(2, startLat = moving.last().at.lat, startMs = moving.last().timeMs, secondsPerPoint = 300).drop(1)
        assertEquals(10_000L, TripInsights.movingMs(stopped))
    }

    @Test
    fun speedBandsPutNinetyKmhInTheEightyBand() {
        val bands = TripInsights.speedBands(straight(11))
        assertEquals(10_000L, bands.single { it.fromKmh == 80 }.ms)
        assertEquals(10_000L, bands.sumOf { it.ms })
    }

    @Test
    fun leanSplitsLeftFromRightAndBandsIt() {
        // Alternating 25° right and 35° left.
        val lean = TripInsights.lean(straight(11) { i -> if (i % 2 == 0) 25.0 else -35.0 })!!
        assertEquals(35.0, lean.maxLeftDeg, 1e-9)
        assertEquals(25.0, lean.maxRightDeg, 1e-9)
        assertEquals(5_000L, lean.leftMs)
        assertEquals(5_000L, lean.rightMs)
        assertEquals(5_000L, lean.bands.single { it.fromDeg == 20 }.ms)
        assertEquals(5_000L, lean.bands.single { it.fromDeg == 30 }.ms)
    }

    @Test
    fun noLeanReadingsMeansNoLeanSummary() {
        assertNull(TripInsights.lean(straight(11)))
    }

    @Test
    fun aStraightRideHasNoBestStretch() {
        assertNull(TripInsights.bestStretch(straight(2000)))
        assertNull(TripInsights.bestSplit(TripInsights.splits(straight(2401))))
    }

    /** 5 km straight, 5 km of 50 m-amplitude wiggles every 300 m (bend radius
     *  ~45 m, inside Curviness's 25-300 m band), 5 km straight. */
    private fun straightWigglyStraight(): List<TraceStore.TracePoint> {
        val m = 1 / 111_195.0
        return List(601) { i ->
            val north = i * 25.0
            val east = if (north in 5_000.0..10_000.0) 50 * kotlin.math.sin(2 * kotlin.math.PI * north / 300) else 0.0
            TraceStore.TracePoint(LatLon(50.0 + north * m, 4.0 + east * m / 0.6428), i * 1000L, 90.0, null)
        }
    }

    @Test
    fun theBestStretchIsTheWigglyMiddle() {
        val points = straightWigglyStraight()
        val best = TripInsights.bestStretch(points)!!
        // Points 200..400 are the bends. Every bend is alike, so the window can
        // sit anywhere among them; it must not reach into either straight
        // (window starts step by 4 points, hence the slack).
        assertTrue(best.first >= 196 && best.last <= 404, "best stretch $best")
    }

    @Test
    fun theBestSplitIsTheTwistiestOne() {
        val splits = TripInsights.splits(straightWigglyStraight(), splitMeters = 5_000.0)
        assertEquals(1, TripInsights.bestSplit(splits)!!.index)
    }

    @Test
    fun untimedPointsGiveDistanceButNoTime() {
        val untimed = straight(11).map { it.copy(timeMs = -1) }
        assertEquals(0L, TripInsights.movingMs(untimed))
        assertTrue(TripInsights.speedBands(untimed).all { it.ms == 0L })
    }

    @Test
    fun roadMixNamesTheDominantClassOrTheTopTwo() {
        assertEquals("mostly back roads", TripInsights.roadMixWords(mapOf(HighwayClass.LOCAL to 700.0, HighwayClass.MOTORWAY to 300.0)))
        assertEquals(
            "main roads and motorway",
            TripInsights.roadMixWords(mapOf(HighwayClass.ARTERIAL to 500.0, HighwayClass.MOTORWAY to 400.0, HighwayClass.LOCAL to 100.0)),
        )
        assertNull(TripInsights.roadMixWords(emptyMap()))
    }

    @Test
    fun metersAlongFindsTheNearestStoredPoint() {
        val line = straight(41).map { it.at }
        assertEquals(500.0, TripInsights.metersAlong(line, line[20])!!, 1.0)
        assertNull(TripInsights.metersAlong(emptyList(), line[0]))
    }

    @Test
    fun aJumpPastFiveHundredMetresIsASignalGap() {
        val line = straight(3).map { it.at } + LatLon(50.02, 4.0)
        assertEquals(1, TripInsights.signalGaps(line))
    }

    private fun square(id: Long, name: String, lat: Double) = Municipality(
        id, name, listOf(listOf(LatLon(lat, 3.9), LatLon(lat, 4.1), LatLon(lat + 0.01, 4.1), LatLon(lat + 0.01, 3.9))),
    )

    @Test
    fun placesAreInOrderAndMarkedNewUnlessVisitedBefore() {
        val towns = listOf(square(2, "Beta", 50.01), square(1, "Alpha", 50.0))
        // 2 km north from 50.0 crosses Alpha then Beta.
        val trip = straight(80).map { it.at }
        val earlier = sequenceOf(LatLon(50.005, 4.0)) // inside Alpha
        assertEquals(
            listOf(PlaceVisit("Alpha", isNew = false), PlaceVisit("Beta", isNew = true)),
            TripInsights.places(trip, towns, earlier),
        )
        assertTrue(TripInsights.places(trip, emptyList()).isEmpty())
    }
}
