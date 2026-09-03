package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        ParkGeofence.disarm(context)
        try {
            TripTrackingService.startMonitoring(context)
        } catch (e: Exception) {
            // Background-start still refused (permission revoked while parked);
            // tracking resumes next time the app is opened.
            Log.w("GeofenceWake", "could not start tracker", e)
        }
    }
}
