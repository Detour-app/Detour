package com.jellemax.detour.ui

import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.Trip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatLineTest {

    private fun trip(drivingStats: DrivingStats = DrivingStats(), distanceMeters: Double = 5_000.0) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_600_000L,
        distanceMeters = distanceMeters,
        topSpeedMps = 30.0,
        destinationLat = null,
        destinationLon = null,
        drivingStats = drivingStats,
    )

    @Test
    fun statLineIsTheCoreNumbersOnly() {
        // Driving-behaviour counts moved to tripBehaviorLine so the maxLines = 1
        // history row isn't ellipsing them — the core line never carries them.
        val line = tripStatLine(trip(DrivingStats(hardBrakeCount = 2, stopCount = 3, obd2SpeedPct = 90.0)))
        assertFalse(line.contains("hard"))
        assertFalse(line.contains("stop"))
        assertFalse(line.contains("OBD2"))
        assertTrue(line.contains("avg"))
        assertTrue(line.contains("top"))
    }

    @Test
    fun behaviorLineIsNullWhenNothingWasRecorded() {
        assertNull(tripBehaviorLine(trip()))
    }

    @Test
    fun behaviorLineCarriesHardEventCountsAndStops() {
        val line = tripBehaviorLine(trip(DrivingStats(hardBrakeCount = 2, hardCornerCount = 1, stopCount = 3)))!!
        assertTrue(line.contains("2 hard brakes"))
        assertTrue(line.contains("1 hard corner"))
        assertTrue(line.contains("3 stops"))
    }

    @Test
    fun behaviorLineShowsObd2Share() {
        assertTrue(tripBehaviorLine(trip(DrivingStats(obd2SpeedPct = 93.7)))!!.contains("OBD2 94%"))
    }

    @Test
    fun behaviorLineRendersASubPercentObd2ShareAsLessThanOne() {
        assertTrue(tripBehaviorLine(trip(DrivingStats(obd2SpeedPct = 0.2)))!!.contains("OBD2 <1%"))
    }

    @Test
    fun behaviorLineShowsFuelEconomyInLitresPer100km() {
        // 300 mL over 5 km = 6.0 L/100km
        val line = tripBehaviorLine(trip(DrivingStats(fuelMilliliters = 300)))!!
        assertTrue(line.contains("6.0 L/100km"))
        assertFalse(line.contains("~")) // direct PID reading, not flagged
    }

    @Test
    fun anEstimatedFuelEconomyIsPrefixedWithATilde() {
        val line = tripBehaviorLine(trip(DrivingStats(fuelMilliliters = 300, fuelEstimated = true)))!!
        assertTrue(line.contains("~6.0 L/100km"))
    }

    @Test
    fun fuelEconomyIsOmittedForATripTooShortToBeMeaningful() {
        // L/100km over 200 m is noise; the token is gated on distance.
        assertNull(tripBehaviorLine(trip(DrivingStats(fuelMilliliters = 50), distanceMeters = 200.0)))
    }

    @Test
    fun fuelEconomyIsOmittedWhenNoFuelWasRecorded() {
        assertNull(tripBehaviorLine(trip(DrivingStats(fuelMilliliters = 0))))
    }
}
