package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dormancyDecision] is the stop-path decision pulled out of
 * [TripTrackingService] (issue #90): given what is currently active, does the
 * always-on tracker stay foreground, stop and leave an OS geofence to wake it,
 * or stop outright with nothing armed. The wiring that feeds it live state and
 * acts on the result — arming the geofence, calling stopSelf — is verified by
 * `adb shell dumpsys activity services` and a GPS replay, not here.
 */
class DormancyTest {

    @Test fun `a running trip always stays alive`() {
        for (autoDetect in listOf(true, false)) {
            assertEquals(
                DormancyDecision.STAY_ALIVE,
                dormancyDecision(autoDetect, tripActive = true, convoyActive = false, uiVisible = false, stationary = true),
            )
        }
    }

    @Test fun `a joined convoy stays alive even parked with the map closed`() {
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(autoDetect = true, tripActive = false, convoyActive = true, uiVisible = false, stationary = true),
        )
    }

    @Test fun `a visible map stays alive even with auto-detect off`() {
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(autoDetect = false, tripActive = false, convoyActive = false, uiVisible = true, stationary = true),
        )
    }

    @Test fun `auto-detect off and nothing active stops bare - no geofence`() {
        // AC 2: "no service and no geofence registered at all". Off beats
        // stationary: with nothing to detect, IDLE-mode watching is pointless.
        for (stationary in listOf(true, false)) {
            assertEquals(
                DormancyDecision.STOP_BARE,
                dormancyDecision(autoDetect = false, tripActive = false, convoyActive = false, uiVisible = false, stationary = stationary),
            )
        }
    }

    @Test fun `auto-detect on, stationary, nothing active - stop and arm the geofence`() {
        assertEquals(
            DormancyDecision.STOP_WITH_GEOFENCE,
            dormancyDecision(autoDetect = true, tripActive = false, convoyActive = false, uiVisible = false, stationary = true),
        )
    }

    @Test fun `auto-detect on but still moving on foot stays alive to watch for a drive`() {
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(autoDetect = true, tripActive = false, convoyActive = false, uiVisible = false, stationary = false),
        )
    }
}
