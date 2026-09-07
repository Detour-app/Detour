package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.jellemax.detour.data.CircleEvents
import com.jellemax.detour.data.GeofenceKind
import com.jellemax.detour.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delivers [PlaceGeofenceGate]'s `DWELL`/`EXIT` transitions the moment GMS
 * fires them (issue #91) — posts straight to [CircleEvents.record], no
 * service start needed since this only needs network, unlike
 * [GeofenceWakeReceiver] which has to stand [TripTrackingService] back up.
 *
 * **Delivery only.** Whether a transition is the first announcement of a real
 * change is [CircleEvents.record]'s decision, not this receiver's: the poll
 * tick is armed for the same places with the same dwell and hysteresis, and
 * this receiver cannot see what that path has already posted. It used to keep
 * its own half of that rule — an `EXIT` was reported only when a `DWELL` had
 * recorded an arrive — which left the arrive side ungated and the two paths
 * announcing every transition twice (#273). One rule, one place, and this
 * receiver just forwards what GMS says happened.
 */
class PlaceGeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // GMS delivers transitions to a cold process (a parked phone, app
        // never opened since boot), where `Application.onCreate` has not
        // touched `Settings` — everything below reaches it, via
        // `CircleEvents.record`'s auth token or the confirmed-inside set,
        // so init first like every other background entry point does.
        Settings.init()
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence event error ${event.errorCode}")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // GMS dropped every fence itself (e.g. a Play services
                // update) — forget what we think is registered so the next
                // tick's sync rebuilds from scratch instead of trusting a
                // stale "already registered" record.
                PlaceGeofenceGate.forgetAllRegistered()
            }
            return
        }
        val kind = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceKind.ARRIVE
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceKind.DEPART
            else -> return
        }
        val tsMs = System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (g in event.triggeringGeofences.orEmpty()) {
                    val (circleId, placeId, fenceKind) = parsePlaceFenceId(g.requestId) ?: continue
                    val expectedKind = if (fenceKind == PlaceFenceKind.ENTER) GeofenceKind.ARRIVE else GeofenceKind.DEPART
                    if (expectedKind != kind) continue // a fence firing its own, unexpected transition — ignore rather than misreport
                    try {
                        val posted = CircleEvents.record(circleId, placeId, kind, tsMs)
                        if (!posted) Log.i(TAG, "$kind for $circleId/$placeId suppressed by the gate")
                    } catch (e: Exception) {
                        Log.w(TAG, "record failed for $circleId/$placeId", e)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PlaceGeofence"
    }
}
