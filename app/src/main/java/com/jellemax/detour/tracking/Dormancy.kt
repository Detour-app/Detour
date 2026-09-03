package com.jellemax.detour.tracking

/** What [TripTrackingService] should do with itself once nothing needs it
 *  foreground — see [dormancyDecision] and issue #90. */
enum class DormancyDecision {
    /** Keep the foreground service; request location for the resolved mode. */
    STAY_ALIVE,
    /** Register a wake geofence at the parked position, then stop. */
    STOP_WITH_GEOFENCE,
    /** Stop outright: nothing to detect, nothing to wake for. */
    STOP_BARE,
}

/**
 * The stop-path decision. Ordered so that anything actively using the service
 * wins first; then auto-detect being off ends the service unconditionally
 * (issue #90 AC 2 — "no service and no geofence registered at all"); then a
 * stationary phone with auto-detect on parks behind a geofence; otherwise the
 * rider is moving on foot and the service stays up in IDLE to catch a drive
 * starting.
 */
fun dormancyDecision(
    autoDetect: Boolean,
    tripActive: Boolean,
    convoyActive: Boolean,
    uiVisible: Boolean,
    stationary: Boolean,
): DormancyDecision = when {
    tripActive || convoyActive || uiVisible -> DormancyDecision.STAY_ALIVE
    !autoDetect -> DormancyDecision.STOP_BARE
    stationary -> DormancyDecision.STOP_WITH_GEOFENCE
    else -> DormancyDecision.STAY_ALIVE
}
