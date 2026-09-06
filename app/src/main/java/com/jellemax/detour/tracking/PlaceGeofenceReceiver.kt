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
 * An `EXIT` is only reported as a depart when a `DWELL` already recorded an
 * arrive for that place ([Settings.confirmedInsidePlaceIds]) — the OS pair of
 * fences reproduces `GeofenceEvaluator`'s *radii*, but not the state coupling
 * that makes its 1.3x factor an exit threshold rather than a second radius.
 *
 * A transition delivered here and the same transition caught a moment later
 * by the next poll tick both call [CircleEvents.record] — accepted, not
 * deduped, per the design doc's Data flow section: a cosmetic double
 * notification, not a correctness bug, and not worth adding dedup state for.
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
                    val key = "$circleId:$placeId"
                    if (kind == GeofenceKind.DEPART && key !in Settings.confirmedInsidePlaceIds()) {
                        // A rider driving past 150m from a place with a 100m
                        // radius crosses the 130m exit ring in seconds. The
                        // evaluator produces nothing there — it can only
                        // depart from a dwell-confirmed inside state — so
                        // neither may this, or every drive-past tells the
                        // circle somebody left a place they never reached.
                        Log.i(TAG, "exit for $key with no confirmed arrive — dropped")
                        continue
                    }
                    try {
                        CircleEvents.record(circleId, placeId, kind, tsMs)
                        // Only once the post lands: an arrive that failed to
                        // record must not arm a depart nobody ever saw the
                        // arrive for.
                        val confirmed = Settings.confirmedInsidePlaceIds()
                        Settings.setConfirmedInsidePlaceIds(
                            if (kind == GeofenceKind.ARRIVE) confirmed + key else confirmed - key,
                        )
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
