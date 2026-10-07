package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            fuelMilliliters = 742_000L, fuelSampledMeters = 12_000L, fuelEstimated = true,
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
    fun aFileThatIsNotATripArrayThrowsRatherThanReadingAsEmpty() {
        // What loadStrict passes on and load swallows: the history screen
        // shows "could not read" for the first, and "no trips yet" only for
        // a genuinely empty array.
        assertFailsWith<Exception> { TripStore.decodeAll("{ truncated") }
        assertEquals(emptyList(), TripStore.decodeAll("[]"))
    }

    @Test
    fun roadTypeMetersOnlyKeepsClassesActuallyPresent() {
        val stats = DrivingStats(roadTypeMeters = mapOf(HighwayClass.ARTERIAL to 1_000.0))
        val decoded = TripStore.decodeTrip(TripStore.encode(trip(stats)))
        assertEquals(mapOf(HighwayClass.ARTERIAL to 1_000.0), decoded.drivingStats.roadTypeMeters)
        assertTrue(HighwayClass.MOTORWAY !in decoded.drivingStats.roadTypeMeters)
    }

    @Test
    fun anEventKindThisBuildDoesNotKnowIsDroppedWithoutLosingTheTrip() {
        // A newer build may add a kind; an older one reading the synced file
        // must keep the trip and every event it can name.
        val moments = decodeTripMoments(jsonObjectOf("""
            {"events":[
              {"kind":"HARD_WHEELIE","lat":50.8,"lon":3.2,"timeMs":1,"magnitude":9.0},
              {"kind":"HARD_ACCEL","lat":50.8,"lon":3.2,"timeMs":2,"magnitude":3.1}]}
        """.trimIndent()))
        assertEquals(listOf(RidingEventKind.HARD_ACCEL), moments.events.map { it.kind })
        assertEquals(null, moments.topSpeed)
    }

    @Test
    fun aTripSavedBeforeMomentsExistedDecodesWithNoPins() {
        val oldTripJson = """
            {"startTimeMs":1700000000000,"endTimeMs":1700000060000,
             "distanceMeters":1200.0,"topSpeedMps":30.0,"mode":"CAR"}
        """.trimIndent()
        assertEquals(TripMoments(), TripStore.decodeTrip(jsonObjectOf(oldTripJson)).moments)
    }

    // --- mergeServerTrips: the /sync merge's mode overrides (#486) -----------

    private fun serverTrips(mode: String, editedAtMs: Long) =
        jsonArrayOf("""[{"startTimeMs":1000,"mode":"$mode","editedAtMs":$editedAtMs}]""")

    @Test
    fun anOverrideOlderThanTheServersEditGivesWayToIt() {
        // Device A set MOTO at 9000; device B set SCOOTER at 10000 and the
        // server kept B's. Re-applying MOTO under B's stamp re-uploads it as a
        // tie the server accepts, and the mode flips back on B (#486).
        val overrides = mutableMapOf(1000L to "MOTO")

        val merged = TripStore.mergeServerTrips(
            serverTrips("SCOOTER", 10_000), emptySet(), overrides, mapOf(1000L to 9_000L),
        )

        val trip = merged.objects().single()
        assertEquals("SCOOTER", trip.optString("mode"), "the other device's newer edit must stand")
        assertEquals(10_000L, trip.optLong("editedAtMs"))
        assertEquals(emptyMap(), overrides, "a superseded override must clear, or it re-applies next sync")
    }

    @Test
    fun anOverrideNewerThanTheServerCopyKeepsItsOwnStamp() {
        // The server hasn't taken this device's edit yet: keep the mode and the
        // stamp it was made at, so the upload beats the server's older copy.
        val overrides = mutableMapOf(1000L to "MOTO")

        val merged = TripStore.mergeServerTrips(
            serverTrips("SCOOTER", 5_000), emptySet(), overrides, mapOf(1000L to 9_000L),
        )

        val trip = merged.objects().single()
        assertEquals("MOTO", trip.optString("mode"), "the unsynced local edit must not be reverted")
        assertEquals(9_000L, trip.optLong("editedAtMs"), "the upload must carry the local edit's stamp")
        assertEquals(mapOf(1000L to "MOTO"), overrides)
    }

    @Test
    fun anOverrideClearsOnceTheServerEchoesItsMode() {
        val overrides = mutableMapOf(1000L to "MOTO")

        val merged = TripStore.mergeServerTrips(
            serverTrips("MOTO", 9_000), emptySet(), overrides, mapOf(1000L to 9_000L),
        )

        assertEquals("MOTO", merged.objects().single().optString("mode"))
        assertEquals(emptyMap(), overrides)
    }

    @Test
    fun aTombstonedTripIsDroppedFromTheMerge() {
        val merged = TripStore.mergeServerTrips(serverTrips("CAR", 0), setOf(1000L), mutableMapOf(), emptyMap())

        assertEquals(0, merged.size)
    }
}
