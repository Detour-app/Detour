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
 * [RADIUS_M] is the landing default. Bigger = the service wakes earlier into
 * the drive (less leading trace lost to geofence-callback latency) but fires
 * on a rider who just walks 200 m and back. Tune from a measured wake latency
 * — see the spec's "Radius & wake latency" and the follow-up issue.
 */
object ParkGeofence {

    const val ID = "park"
    const val RADIUS_M = 150f

    /** Shared with [GeofenceWakeReceiver] so one `adb logcat -s ParkGeofence`
     *  carries the whole arm → wake story. */
    const val TAG = "ParkGeofence"

    private const val ACTION = "com.jellemax.detour.GEOFENCE_EXIT"

    fun arm(context: Context, lat: Double, lon: Double) {
        if (!hasLocationPermission(context)) return
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
                    Log.i(TAG, "armed r=${RADIUS_M}m ert=${SystemClock.elapsedRealtime()}" +
                        if (BuildConfig.DEBUG) " at $lat,$lon" else "")
                }
                .addOnFailureListener { Log.w(TAG, "arm failed", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "arm denied", e)
        }
    }

    fun disarm(context: Context) {
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
