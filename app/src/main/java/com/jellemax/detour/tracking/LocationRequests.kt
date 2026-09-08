package com.jellemax.detour.tracking

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.jellemax.detour.tracking.TripTrackingService.LocationMode

/**
 * Which [LocationMode] the service should be in right now. Pulled out of
 * [TripTrackingService] as a pure function of the state that used to be field
 * reads — [LocationMode] itself has to stay nested on the service (shared
 * tuning stays in one place), so this can't move any further out than the app:
 * `:shared` can't see an app-module type without either duplicating the enum
 * there or laundering this function's return type through a translation layer,
 * neither of which is a real simplification. Kept here, next to the requests
 * it decides between, and unit-tested directly (see LocationRequestsTest) the
 * same way [dormancyDecision] is — this repo has no androidTest, but a pure
 * function with no Android dependency needs none.
 *
 *  - [LocationMode.TRIP]: a trip is running - always wins.
 *  - [LocationMode.PROBE]: activity recognition just reported IN_VEHICLE, or a
 *    fast fix opened a speed-only probe; see [TripTrackingService]'s own KDoc.
 *  - [LocationMode.LIVE]: someone is watching (map open or a joined convoy)
 *    wants a live cadence even over a phone activity recognition still thinks
 *    is sitting still.
 *  - [LocationMode.SLEEP]: activity recognition says STILL, nothing else asks.
 *  - [LocationMode.IDLE]: the default - moving around on foot, watching for a
 *    drive to start.
 */
internal fun currentLocationMode(
    hasActiveTrip: Boolean,
    probing: Boolean,
    uiVisible: Boolean,
    convoyActive: Boolean,
    stationary: Boolean,
): LocationMode = when {
    hasActiveTrip -> LocationMode.TRIP
    probing -> LocationMode.PROBE
    uiVisible || convoyActive -> LocationMode.LIVE
    stationary -> LocationMode.SLEEP
    else -> LocationMode.IDLE
}

/**
 * What to ask the fused location provider for, and holding the one active
 * request against redundant re-requests. Pulled out of [TripTrackingService]
 * (issue split, phase 7); the mode decision itself stays a step further out —
 * see [currentLocationMode] — since this class only knows how to act on a
 * mode, not how to pick one.
 *
 * [onPermissionRevoked] fires from [ensureFor] exactly where the service used
 * to catch its own `SecurityException` inline: location permission pulled out
 * from under an already-running service. The notification clear and
 * `stopSelf()` that follow need the live `Service`, which this class
 * deliberately doesn't hold.
 */
internal class LocationRequests(
    private val client: FusedLocationProviderClient,
    private val callback: LocationCallback,
    private val onPermissionRevoked: () -> Unit,
) {
    /** Which mode the active request was made for; null = none yet. */
    private var activeMode: LocationMode? = null

    private fun locationRequest(mode: LocationMode): LocationRequest = when (mode) {
        // Passive costs no radio time of its own: we only see fixes some other
        // app already paid for. Enough to notice a drive if STILL-exit is late.
        LocationMode.SLEEP ->
            LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000L)
                .setMinUpdateDistanceMeters(100f)
                .build()
        // Still batched, but a burst held for a minute meant a drive that began
        // 60 s ago was invisible to the start detector for 60 s. IDLE only runs
        // while you're actually moving around on foot (STILL parks us in SLEEP),
        // so the shorter window costs little and is what the detector reacts to.
        LocationMode.IDLE ->
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
                .setMinUpdateDistanceMeters(30f)
                .setMaxUpdateDelayMillis(20_000L)
                .setWaitForAccurateLocation(false)
                .build()
        // Same appetite as a trip: the map is open, the screen is on, and the
        // radio is the small cost next to the display.
        LocationMode.LIVE, LocationMode.TRIP ->
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                // GNSS tops out around 1 Hz, but fused will hand over anything
                // faster it has (sensor-fused, another app's request) instead of
                // holding it back to the nominal interval.
                .setMinUpdateIntervalMillis(200L)
                .setWaitForAccurateLocation(false)
                .build()
        LocationMode.PROBE ->
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000L).build()
    }

    /** (Re)request location updates matching [mode], if it differs from the
     *  active request. Returns whether a new request actually went out, so
     *  the caller knows whether anything (e.g. the notification) needs
     *  refreshing along with it. */
    fun ensureFor(mode: LocationMode): Boolean {
        if (activeMode == mode) return false
        client.removeLocationUpdates(callback)
        return try {
            client.requestLocationUpdates(locationRequest(mode), callback, Looper.getMainLooper())
            activeMode = mode
            true
        } catch (e: SecurityException) {
            onPermissionRevoked()
            false
        }
    }

    /** Drop the active request entirely, e.g. the service is stopping. */
    fun stop() {
        client.removeLocationUpdates(callback)
        activeMode = null
    }

}
