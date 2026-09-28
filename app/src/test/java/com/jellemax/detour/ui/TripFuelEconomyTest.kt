package com.jellemax.detour.ui

import com.jellemax.detour.data.DrivingStats
import com.jellemax.detour.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFuelEconomyTest {

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
