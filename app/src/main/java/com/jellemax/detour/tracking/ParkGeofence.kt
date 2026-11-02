package com.jellemax.detour.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.EventKind
import com.jellemax.detour.data.EventLog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * The one OS geofence Detour registers (issue #90): a circle around the
 * position where the rider parked, transition EXIT only, no expiry. Its whole
 * job is to let [TripTrackingService] stop itself while parked and still be
 * woken by the system when the rider rides away — a foreground-service start
 * from [GeofenceWakeReceiver] is on Android's background-start exemption list
 * precisely for a geofencing transition.
 *
 * Not to be confused with circle arrive/depart, which is still evaluated
 * on-device against fixes that already arrive (`GeofenceEvaluator` in
 * `shared/`) and goes nowhere near this API.
 *
 * [RADIUS_M] trades leading trace against false wakes. The EXIT can only fire
 * once the rider is outside the circle, so a geofence-woken trip loses the
 * radius plus however far the rider gets before recording starts:
 * smaller = less trace lost, but more parks where walking away from the
 * vehicle wakes the service. Measured for #140 over ~105 real departures
 * after this fence shipped: 17% looked geofence-woken (first trace point past
 * the fence; the rest were already awake), and those ran a median ~100 m past
 * the edge (about 10 s at 37 km/h). That overrun — callback latency, first
 * fix and start confirmation — does not shrink with the radius. Going from
 * 150 m to 100 m moved only about 2–3% of parks into "walked far enough to
 * wake it"; about a quarter of parks already walk past 200 m.
 * 100 m is also the smallest radius Google's geofencing guidance recommends.
 */
object ParkGeofence {

    const val ID = "park"
    const val RADIUS_M = 100f

    /** Shared with [GeofenceWakeReceiver] so one `adb logcat -s ParkGeofence`
     *  carries the whole arm → wake story. */
    const val TAG = "ParkGeofence"

    private const val ACTION = "com.jellemax.detour.GEOFENCE_EXIT"

    fun arm(context: Context, lat: Double, lon: Double) {
        if (!hasLocationPermission(context)) {
            EventLog.record(EventKind.GEOFENCE, "arm skipped: no location permission")
            return
        }
        val geofence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(lat, lon, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER_* — arming while already inside the circle
            // must not fire an immediate spurious wake.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    // The only external evidence this fence exists. GMS geofences
                    // are not in `dumpsys location` (that section is the platform
                    // GeofenceManager, which the GMS API does not use), so without
                    // this line a parked service is indistinguishable from one that
                    // stopped and armed nothing. Pairs with GeofenceWakeReceiver's
                    // delivery log to give #140 its wake latency.
                    EventLog.record(EventKind.GEOFENCE, "armed r=${RADIUS_M.toInt()}m")
                    Log.i(
                        TAG,
                        "armed r=${RADIUS_M}m ert=${SystemClock.elapsedRealtime()}" +
                            if (BuildConfig.DEBUG) " at $lat,$lon" else "",
                    )
                }
                .addOnFailureListener {
                    EventLog.record(EventKind.GEOFENCE, "arm failed: ${it.javaClass.simpleName}")
                    Log.w(TAG, "arm failed", it)
                }
        } catch (e: SecurityException) {
            EventLog.record(EventKind.GEOFENCE, "arm denied")
            Log.w(TAG, "arm denied", e)
        }
    }

    fun disarm(context: Context) {
        EventLog.record(EventKind.GEOFENCE, "remove")
        LocationServices.getGeofencingClient(context)
            .removeGeofences(listOf(ID))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceWakeReceiver::class.java).setAction(ACTION)
        // Geofencing requires a mutable PendingIntent (the system writes the
        // transition result into it).
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
