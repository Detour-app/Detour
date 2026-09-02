package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [pickObd2Address] is the connect/disconnect decision for the OBD2 adapter,
 * pulled out of [TripTrackingService] so it is testable without a service or a
 * Bluetooth stack (maxke24/Detour#96, #97). The wiring that gathers its inputs
 * and acts on its result is verified by GPS replay + manual on-device checks.
 */
class ObdConnectionTargetTest {

    @Test
    fun parkedWithAppClosedAndNoTripPollsNothing() {
        assertNull(
            pickObd2Address(
                tripActive = false,
                uiVisible = false,
                tripVehicleObd2Address = "AA:BB",
                connectedObd2Addresses = listOf("AA:BB"),
                configuredObd2Addresses = listOf("AA:BB"),
            ),
        )
    }

    @Test
    fun tripActivePicksTheDrivenVehiclesAdapter() {
        assertEquals(
            "CAR:AD",
            pickObd2Address(
                tripActive = true,
                uiVisible = false,
                tripVehicleObd2Address = "CAR:AD",
                connectedObd2Addresses = listOf("CAR:AD", "BIKE:AD"),
                configuredObd2Addresses = listOf("CAR:AD", "BIKE:AD"),
            ),
        )
    }

    @Test
    fun tripActiveWithNoResolvedAdapterFallsBackToTheSoleConfiguredOne() {
        assertEquals(
            "ONLY:AD",
            pickObd2Address(
                tripActive = true,
                uiVisible = false,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = emptyList(),
                configuredObd2Addresses = listOf("ONLY:AD"),
            ),
        )
    }

    @Test
    fun tripActiveWithNoResolvedAdapterAndTwoConfiguredIsAmbiguousSoNothing() {
        assertNull(
            pickObd2Address(
                tripActive = true,
                uiVisible = false,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = emptyList(),
                configuredObd2Addresses = listOf("A:AD", "B:AD"),
            ),
        )
    }

    @Test
    fun appVisibleNoTripPollsTheConnectedVehiclesAdapter() {
        assertEquals(
            "NEAR:AD",
            pickObd2Address(
                tripActive = false,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = listOf("NEAR:AD"),
                configuredObd2Addresses = listOf("NEAR:AD", "FAR:AD"),
            ),
        )
    }

    @Test
    fun appVisibleNoTripPicksTheFirstConnectedAdapter() {
        assertEquals(
            "A:AD",
            pickObd2Address(
                tripActive = false,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = listOf("A:AD", "B:AD"),
                configuredObd2Addresses = listOf("A:AD", "B:AD"),
            ),
        )
    }

    @Test
    fun appVisibleNoTripNoConnectedVehiclePollsNothing() {
        assertNull(
            pickObd2Address(
                tripActive = false,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = emptyList(),
                configuredObd2Addresses = listOf("FAR:AD"),
            ),
        )
    }

    @Test
    fun tripActiveTakesPriorityOverAConnectedNonDrivenAdapter() {
        // Bike adapter is connected, but the trip resolved to the car (no
        // adapter). Ambiguity rule wins: not the bike's.
        assertNull(
            pickObd2Address(
                tripActive = true,
                uiVisible = true,
                tripVehicleObd2Address = null,
                connectedObd2Addresses = listOf("BIKE:AD"),
                configuredObd2Addresses = listOf("BIKE:AD", "CAR:AD"),
            ),
        )
    }
}
