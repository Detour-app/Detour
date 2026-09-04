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

    @Test fun `a geofence wake outranks a stale STILL so the wake is not undone`() {
        // The regression this exists for: GEOFENCE_TRANSITION_EXIT starts the
        // service, onStartCommand reaches the dormancy check before the first
        // fix arrives, and activity recognition still says STILL because the
        // rider only just pulled away. Without this branch the service parked
        // ~60 ms after waking and re-armed 150 m on, the whole ride, recording
        // nothing.
        assertEquals(
            DormancyDecision.STAY_ALIVE,
            dormancyDecision(
                autoDetect = true, tripActive = false, convoyActive = false,
                uiVisible = false, stationary = true, justWokenByGeofence = true,
            ),
        )
    }

    @Test fun `auto-detect off still stops bare even on a geofence wake`() {
        // AC 2 outranks the grace: with nothing to detect there is nothing to
        // wake for, so a wake that arrives after the setting was turned off
        // must still leave no service and no geofence.
        assertEquals(
            DormancyDecision.STOP_BARE,
            dormancyDecision(
                autoDetect = false, tripActive = false, convoyActive = false,
                uiVisible = false, stationary = true, justWokenByGeofence = true,
            ),
        )
    }

    @Test fun `once the wake grace lapses a still-stationary phone parks again`() {
        // A false wake — walked past 150 m and came back — costs one grace
        // window, not an always-on service.
        assertEquals(
            DormancyDecision.STOP_WITH_GEOFENCE,
            dormancyDecision(
                autoDetect = true, tripActive = false, convoyActive = false,
                uiVisible = false, stationary = true, justWokenByGeofence = false,
            ),
        )
    }
}

/**
 * [geofenceAction] reconciles the park geofence against a dormancy decision
 * (issue #146): before it, `onStartCommand` disarmed unconditionally and each
 * of the two-to-four dormancy evaluations a single start could run reissued the
 * arm, so one wake produced eight `ParkGeofence` log lines and that many GMS
 * round trips for one circle at one position.
 *
 * The service-side half — the coalescing post, and the fact that every caller
 * runs on the main thread — is verified by a GPS replay and `adb logcat -s
 * ParkGeofence`, not here.
 */
class GeofenceActionTest {

    @Test fun `parking behind a geofence arms one`() {
        assertEquals(
            GeofenceAction.ARM,
            geofenceAction(DormancyDecision.STOP_WITH_GEOFENCE, requested = false),
        )
    }

    @Test fun `an awake service takes the fence down`() {
        assertEquals(
            GeofenceAction.DISARM,
            geofenceAction(DormancyDecision.STAY_ALIVE, requested = true),
        )
    }

    @Test fun `stopping bare takes the fence down - AC 2 leaves nothing armed`() {
        assertEquals(
            GeofenceAction.DISARM,
            geofenceAction(DormancyDecision.STOP_BARE, requested = true),
        )
    }

    @Test fun `a decision that changes nothing issues no call`() {
        // The whole point: repeat evaluations within one service start are free.
        assertEquals(
            GeofenceAction.NONE,
            geofenceAction(DormancyDecision.STOP_WITH_GEOFENCE, requested = true),
        )
        for (decision in listOf(DormancyDecision.STAY_ALIVE, DormancyDecision.STOP_BARE)) {
            assertEquals(
                GeofenceAction.NONE,
                geofenceAction(decision, requested = false),
            )
        }
    }

    @Test fun `a fresh instance always issues its first call`() {
        // requested = null is not the same as false. A park geofence outlives
        // the process that registered it, so an instance that has asked for
        // nothing yet cannot assume the fence it wants absent is absent — this
        // is what the old unconditional disarm() in onStartCommand provided.
        assertEquals(
            GeofenceAction.DISARM,
            geofenceAction(DormancyDecision.STAY_ALIVE, requested = null),
        )
        assertEquals(
            GeofenceAction.DISARM,
            geofenceAction(DormancyDecision.STOP_BARE, requested = null),
        )
        assertEquals(
            GeofenceAction.ARM,
            geofenceAction(DormancyDecision.STOP_WITH_GEOFENCE, requested = null),
        )
    }
}
