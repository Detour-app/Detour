package com.jellemax.detour.tracking

import com.jellemax.detour.tracking.TripTrackingService.LocationMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [currentLocationMode] is the fused-provider appetite decision pulled out of
 * [TripTrackingService] in the same split that produced [LocationRequests].
 * The GMS wiring it feeds — actually issuing/removing a `LocationRequest` — is
 * exercised by a GPS replay, not here.
 */
class LocationRequestsTest {

    @Test fun `a running trip always wins, whatever else is true`() {
        for (probing in listOf(true, false)) {
            for (uiVisible in listOf(true, false)) {
                for (stationary in listOf(true, false)) {
                    assertEquals(
                        LocationMode.TRIP,
                        currentLocationMode(
                            hasActiveTrip = true, probing = probing, uiVisible = uiVisible,
                            convoyActive = false, stationary = stationary,
                        ),
                    )
                }
            }
        }
    }

    @Test fun `an open probe window beats a visible map or a stationary phone`() {
        assertEquals(
            LocationMode.PROBE,
            currentLocationMode(
                hasActiveTrip = false, probing = true, uiVisible = true,
                convoyActive = false, stationary = true,
            ),
        )
    }

    @Test fun `a visible map wins over a stationary phone with no trip or probe`() {
        assertEquals(
            LocationMode.LIVE,
            currentLocationMode(
                hasActiveTrip = false, probing = false, uiVisible = true,
                convoyActive = false, stationary = true,
            ),
        )
    }

    @Test fun `a joined convoy earns the same live cadence as a visible map`() {
        assertEquals(
            LocationMode.LIVE,
            currentLocationMode(
                hasActiveTrip = false, probing = false, uiVisible = false,
                convoyActive = true, stationary = true,
            ),
        )
    }

    @Test fun `stationary with nothing else active sleeps`() {
        assertEquals(
            LocationMode.SLEEP,
            currentLocationMode(
                hasActiveTrip = false, probing = false, uiVisible = false,
                convoyActive = false, stationary = true,
            ),
        )
    }

    @Test fun `moving on foot with nothing else active watches for a drive`() {
        assertEquals(
            LocationMode.IDLE,
            currentLocationMode(
                hasActiveTrip = false, probing = false, uiVisible = false,
                convoyActive = false, stationary = false,
            ),
        )
    }
}
