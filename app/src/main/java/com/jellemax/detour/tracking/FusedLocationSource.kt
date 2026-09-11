package com.jellemax.detour.tracking

import android.content.Context
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.tracking.TripTrackingService.LocationMode
import kotlinx.coroutines.tasks.await

/**
 * The [LocationSource] a shipped app runs on: Play Services' fused provider.
 *
 * This is the only file in `app/` that constructs a fused *streaming* client.
 * Everything Play Services about the fix stream lives here — the client, the
 * `LocationCallback`, and the `LocationResult` unwrap — so [TripTrackingService]
 * no longer names any of it.
 *
 * **What it does not own:** the request table. Which `Priority` and interval each
 * [LocationMode] asks for stays in [LocationRequests], where it is unit-tested
 * and where its reasoning is written down. This class is the adapter around it,
 * not a second copy of it.
 *
 * **Six one-shot `lastLocation` reads still live outside this file** —
 * `MapScreen`, `Theme`, `SpinScreen`, `SearchScreen`, `RouteEditorScreen` and
 * `CircleSyncWorker`. They answer "where is the device right now" rather than
 * subscribing to the drive, so they are a different concern from this port; they
 * are also why a replay through [ReplayLocationSource] still shows the *real*
 * position anywhere one of them is the source. See that class's KDoc.
 */
internal class FusedLocationSource(
    private val context: Context,
    private val listener: LocationBatchListener,
    onPermissionRevoked: () -> Unit,
) : LocationSource {

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            listener.onFixes(result.locations.map { it.toLocationFix() })
        }
    }

    private val requests = LocationRequests(
        client = LocationServices.getFusedLocationProviderClient(context),
        callback = callback,
        onPermissionRevoked = onPermissionRevoked,
    )

    override fun ensureFor(mode: LocationMode): Boolean = requests.ensureFor(mode)

    override fun stop() = requests.stop()

    override suspend fun currentLatLon(): LatLon? = fusedCurrentLatLon(context)
}

/**
 * One fresh position from the platform, falling back to the last one it has.
 *
 * Top-level so both the adapter and [LocationSources]'s helper use one
 * implementation — this used to be written out inside `MapScreen.fetchLocation`,
 * and a second copy is what let the map keep asking the platform after the rest
 * of the app had stopped.
 *
 * `getCurrentLocation` before `lastLocation`, preserving the order the map had:
 * a fresh fix is worth waiting for when centring, and the cached one is the
 * fallback rather than the answer. Throws [SecurityException] if the permission
 * has gone; callers handle it as they did before.
 */
internal suspend fun fusedCurrentLatLon(context: Context): LatLon? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        ?: client.lastLocation.await()
    return loc?.let { LatLon(it.latitude, it.longitude) }
}

/**
 * The one place fused's [Location] is read at all (#312) — everything past
 * this point, [ReplayFixGate] included, sees only [LocationFix].
 *
 * [FixOrigin.MOCK_PROVIDER] is fused blending in a real fix from a designated
 * mock-location app (the [ReplayFixGate]'s `isMock` rig, distinct from
 * [ReplayLocationSource]'s own port, which never reaches this function).
 */
private fun Location.toLocationFix(): LocationFix {
    val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }
    return LocationFix(
        lat = latitude,
        lon = longitude,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        accuracyMeters = accuracy,
        timeMs = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        origin = if (mock) FixOrigin.MOCK_PROVIDER else FixOrigin.PLATFORM,
    )
}
