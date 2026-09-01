package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterises [TripStore]'s encode/decode round trip for [DrivingStats] —
 *  pure JSON building, no file access, so these run in commonTest per
 *  detour-shared-core §8 (file I/O needs androidUnitTest instead). */
class TripStoreTest {

    private fun trip(drivingStats: DrivingStats = DrivingStats()) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_060_000L,
        distanceMeters = 1200.0,
        topSpeedMps = 30.0,
        destinationLat = null,
        destinationLon = null,
        mode = TravelMode.CAR,
        drivingStats = drivingStats,
    )

    @Test
    fun drivingStatsRoundTripsThroughEncodeAndDecode() {
        val stats = DrivingStats(
            hardBrakeCount = 2, hardAccelCount = 1, hardCornerCount = 3,
            secondsOverLimit = 45, pctOverLimit = 12.5,
            roadTypeMeters = mapOf(HighwayClass.MOTORWAY to 500.0, HighwayClass.LOCAL to 300.0),
            twistinessScore = 0.42, stopCount = 1, idleMs = 90_000L,
            obd2SpeedPct = 87.5,
            maxRpm = 6400.0, maxThrottlePct = 98.0, pctWideOpenThrottle = 12.0, avgRpm = 2850.0,
        )
        val decoded = TripStore.decodeTrip(TripStore.encode(trip(stats)))
        assertEquals(stats, decoded.drivingStats)
    }

    @Test
    fun aTripSavedBeforeDrivingStatsExistedDecodesWithAllZeroDefaults() {
        // Simulates an old trips.json entry: no "drivingStats" key at all.
        val oldTripJson = """
            {"startTimeMs":1700000000000,"endTimeMs":1700000060000,
             "distanceMeters":1200.0,"topSpeedMps":30.0,"mode":"CAR"}
        """.trimIndent()
        val decoded = TripStore.decodeTrip(jsonObjectOf(oldTripJson))
        assertEquals(DrivingStats(), decoded.drivingStats)
    }

    @Test
    fun roadTypeMetersOnlyKeepsClassesActuallyPresent() {
        val stats = DrivingStats(roadTypeMeters = mapOf(HighwayClass.ARTERIAL to 1_000.0))
        val decoded = TripStore.decodeTrip(TripStore.encode(trip(stats)))
        assertEquals(mapOf(HighwayClass.ARTERIAL to 1_000.0), decoded.drivingStats.roadTypeMeters)
        assertTrue(HighwayClass.MOTORWAY !in decoded.drivingStats.roadTypeMeters)
    }
}
