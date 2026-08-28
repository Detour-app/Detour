package com.jellemax.detour.ui

import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.Trip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatLineTest {

    private fun trip(drivingStats: DrivingStats = DrivingStats()) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_600_000L,
        distanceMeters = 5_000.0,
        topSpeedMps = 30.0,
        destinationLat = null,
        destinationLon = null,
        drivingStats = drivingStats,
    )

    @Test
    fun aTripWithNoHardEventsOmitsTheHardEventSegment() {
        val line = tripStatLine(trip())
        assertFalse(line.contains("hard"))
    }

    @Test
    fun hardEventCountsAppearWhenNonZero() {
        val line = tripStatLine(trip(DrivingStats(hardBrakeCount = 2, hardCornerCount = 1)))
        assertTrue(line.contains("2 hard brakes"))
        assertTrue(line.contains("1 hard corner"))
    }

    @Test
    fun stopsAppearWhenNonZero() {
        val line = tripStatLine(trip(DrivingStats(stopCount = 3)))
        assertTrue(line.contains("3 stops"))
    }

    @Test
    fun obd2ShareAppearsWhenNonZero() {
        val line = tripStatLine(trip(DrivingStats(obd2SpeedPct = 93.7)))
        assertTrue(line.contains("OBD2 94%"))
    }

    @Test
    fun obd2ShareIsOmittedWhenZero() {
        val line = tripStatLine(trip())
        assertFalse(line.contains("OBD2"))
    }

    @Test
    fun obd2ShareThatRoundsToZeroRendersAsLessThanOnePercent() {
        val line = tripStatLine(trip(DrivingStats(obd2SpeedPct = 0.2)))
        assertTrue(line.contains("OBD2 <1%"))
    }
}
