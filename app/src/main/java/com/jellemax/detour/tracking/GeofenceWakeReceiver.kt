package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Wakes [TripTrackingService] when the rider leaves the position they parked
 * at (issue #90). Starting a foreground service from here is allowed from the
 * background: a geofencing transition is on Android's FGS background-start
 * exemption list
 * (developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
 */
class GeofenceWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("GeofenceWake", "geofence event error ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        logDelivery(event)
        ParkGeofence.disarm(context)
        try {
            TripTrackingService.startMonitoring(context)
        } catch (e: Exception) {
            // Background-start still refused (permission revoked while parked);
            // tracking resumes next time the app is opened.
            Log.w("GeofenceWake", "could not start tracker", e)
        }
    }

    /**
     * The measurement #140 needs, and the reason it is here rather than in the
     * issue's instructions: nothing else records that a wake happened, so a real
     * drive would otherwise produce a trip with no evidence of which path started
     * it and no timestamp to measure leading-trace loss against.
     *
     * `batchedMs` is the interesting one — the gap between the fix that actually
     * crossed the fence and this callback landing. That is the OS batching delay
     * the radius exists to absorb, measured rather than estimated. `speed` gives
     * the pull-away speed to multiply it by.
     */
    private fun logDelivery(event: GeofencingEvent) {
        val loc = event.triggeringLocation
        val batchedMs = loc?.let {
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L
        }
        Log.i(
            ParkGeofence.TAG,
            "EXIT delivered ert=${SystemClock.elapsedRealtime()} wall=${System.currentTimeMillis()} " +
                "batchedMs=$batchedMs speed=${loc?.speed}m/s acc=${loc?.accuracy}m",
        )
    }
}
