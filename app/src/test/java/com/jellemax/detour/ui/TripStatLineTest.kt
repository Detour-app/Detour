package com.jellemax.detour.ui

import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.Trip
import org.junit.Assert.assertEquals
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
        // 240 mL over 4 km sampled = 6.0 L/100km, and 4 km covers the 5 km trip.
        val line = tripBehaviorLine(trip(DrivingStats(fuelMilliliters = 240, fuelSampledMeters = 4_000)))!!
        assertTrue(line.contains("6.0 L/100km"))
        assertFalse(line.contains("~")) // direct PID reading, not flagged
    }

    @Test
    fun anEstimatedFuelEconomyIsPrefixedWithATilde() {
        val line = tripBehaviorLine(
            trip(DrivingStats(fuelMilliliters = 240, fuelSampledMeters = 4_000, fuelEstimated = true)),
        )!!
        assertTrue(line.contains("~6.0 L/100km"))
    }

    @Test
    fun fuelEconomyIsComputedOverTheSampledDistanceNotTheWholeTrip() {
        // 240 mL over 4 km sampled → 6.0, even though the trip is 5 km.
        assertEquals(6.0, tripFuelEconomyLper100Km(
            trip(DrivingStats(fuelMilliliters = 240, fuelSampledMeters = 4_000)),
        )!!, 1e-9)
    }

    @Test
    fun fuelEconomyIsOmittedWhenTheAdapterCoveredTooLittleOfTheTrip() {
        // 1 km sampled of a 5 km trip — a partial measurement divided by the
        // whole trip would read as an impossibly good number.
        assertNull(tripFuelEconomyLper100Km(
            trip(DrivingStats(fuelMilliliters = 300, fuelSampledMeters = 1_000)),
        ))
    }

    @Test
    fun fuelEconomyIsOmittedForATripTooShortToBeMeaningful() {
        assertNull(tripFuelEconomyLper100Km(
            trip(DrivingStats(fuelMilliliters = 50, fuelSampledMeters = 100), distanceMeters = 200.0),
        ))
    }

    @Test
    fun fuelEconomyIsOmittedWhenNoFuelWasRecorded() {
        assertNull(tripFuelEconomyLper100Km(trip(DrivingStats(fuelMilliliters = 0))))
    }
}
