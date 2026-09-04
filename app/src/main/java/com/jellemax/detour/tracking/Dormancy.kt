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
 * wins first — a running trip, a joined convoy or a visible map keeps it up
 * whatever [autoDetect] says; then auto-detect being off ends the service in
 * every remaining case (issue #90 AC 2 — "no service and no geofence
 * registered at all"); then a service that has just been woken by the park
 * geofence is given time to see a fix; then a stationary phone with
 * auto-detect on parks behind a geofence; otherwise the rider is moving on
 * foot and the service stays up in IDLE to catch a drive starting.
 *
 * [justWokenByGeofence] is load-bearing, and its position in the `when` is the
 * whole fix. Without it the wake is self-defeating: `GEOFENCE_TRANSITION_EXIT`
 * starts the service, `onStartCommand` reaches its dormancy check
 * synchronously — before `ensureLocationUpdates()`'s first callback can arrive
 * — and [stationary] is still whatever activity recognition last said, which
 * for a rider who has just pulled away is `STILL`. So the service parked again
 * roughly 60 ms after waking, armed a fresh fence 150 m further on, and
 * repeated that the length of the ride. Measured on a 1 km mock route at
 * 45 km/h: two wake/re-park cycles, no trip and no trace recorded at all,
 * on a route that clears the auto-start gate comfortably.
 *
 * [stationary] cannot answer this on its own: it is cleared only by `beginTrip`,
 * by an activity-recognition transition, or by the fast-run escalation inside
 * the location callback — and all three need the service to live long enough to
 * receive a fix, which is exactly what it was not doing.
 *
 * It sits *below* the [autoDetect] branch so AC 2 still holds: with auto-detect
 * off there is nothing to wake for, and a stale wake must still stop bare.
 */
fun dormancyDecision(
    autoDetect: Boolean,
    tripActive: Boolean,
    convoyActive: Boolean,
    uiVisible: Boolean,
    stationary: Boolean,
    justWokenByGeofence: Boolean = false,
): DormancyDecision = when {
    tripActive || convoyActive || uiVisible -> DormancyDecision.STAY_ALIVE
    !autoDetect -> DormancyDecision.STOP_BARE
    justWokenByGeofence -> DormancyDecision.STAY_ALIVE
    stationary -> DormancyDecision.STOP_WITH_GEOFENCE
    else -> DormancyDecision.STAY_ALIVE
}

/** What to do with the park geofence registration — see [geofenceAction]. */
enum class GeofenceAction {
    /** Register the fence at the current position. */
    ARM,
    /** Remove it. */
    DISARM,
    /** It is already in the state this decision wants; issue no GMS call. */
    NONE,
}

/**
 * Reconcile the park geofence against a dormancy decision (issue #146).
 *
 * Exactly one decision wants a fence — [DormancyDecision.STOP_WITH_GEOFENCE],
 * which exists to be woken by it. Everything else wants none: an awake service
 * needs no wake, and `STOP_BARE` has nothing to wake for.
 *
 * [requested] is what *this process* last asked GMS for, not what GMS holds.
 * `null` means it has asked for nothing yet, and is deliberately not the same
 * as `false`: a park geofence outlives the process that registered it, so a
 * fresh instance must issue its first call rather than assume the fence it
 * wants absent is already absent. That is the property the old unconditional
 * `disarm()` at the top of `onStartCommand` provided, kept here without the
 * three-to-four redundant round trips per start it cost.
 */
fun geofenceAction(decision: DormancyDecision, requested: Boolean?): GeofenceAction {
    val wanted = decision == DormancyDecision.STOP_WITH_GEOFENCE
    return when {
        requested == wanted -> GeofenceAction.NONE
        wanted -> GeofenceAction.ARM
        else -> GeofenceAction.DISARM
    }
}
