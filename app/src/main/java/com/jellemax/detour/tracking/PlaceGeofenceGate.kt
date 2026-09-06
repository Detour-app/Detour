package com.jellemax.detour.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.jellemax.detour.data.CirclePresence.GateCandidate
import com.jellemax.detour.data.GeofenceEvaluator
import com.jellemax.detour.data.Settings

/** Which guard a fence stands in for — matches [GeofenceEvaluator]'s two
 *  boundary checks exactly, see [PlaceGeofenceGate]'s class doc. */
enum class PlaceFenceKind { ENTER, EXIT }

/** `"$circleId:$placeId:${kind}"`, lowercase. `circleId` is a server UUID
 *  (see [com.jellemax.detour.data.Group.id]'s doc) so it never contains a
 *  colon, which is what makes splitting back out in [parsePlaceFenceId]
 *  unambiguous without escaping. */
fun placeFenceId(circleId: String, placeId: Long, kind: PlaceFenceKind): String =
    "$circleId:$placeId:${kind.name.lowercase()}"

/** The inverse of [placeFenceId], or null for anything that isn't one of
 *  ours — a defensive parse, since this reads a `Geofence.requestId` GMS
 *  hands back over a `PendingIntent`, not a value this code controls end to
 *  end in every build. */
fun parsePlaceFenceId(id: String): Triple<String, Long, PlaceFenceKind>? {
    val parts = id.split(":")
    if (parts.size < 3) return null
    val kind = when (parts.last()) {
        "enter" -> PlaceFenceKind.ENTER
        "exit" -> PlaceFenceKind.EXIT
        else -> return null
    }
    val placeId = parts[parts.size - 2].toLongOrNull() ?: return null
    val circleId = parts.dropLast(2).joinToString(":")
    if (circleId.isEmpty()) return null
    return Triple(circleId, placeId, kind)
}

/** Two ids per [candidates] entry — the whole target set [PlaceGeofenceGate.sync]
 *  diffs the currently-registered set against. */
internal fun targetFenceIds(candidates: List<GateCandidate>): Set<String> =
    candidates.flatMap {
        listOf(placeFenceId(it.circleId, it.placeId, PlaceFenceKind.ENTER), placeFenceId(it.circleId, it.placeId, PlaceFenceKind.EXIT))
    }.toSet()

/** What one [PlaceGeofenceGate.sync] pass needs to add and remove to make
 *  the registered set match [targetFenceIds] — a pure set difference,
 *  pulled out so it's testable without a real `GeofencingClient`. */
internal data class FenceDiff(val toAdd: Set<String>, val toRemove: Set<String>)

internal fun diffFenceIds(registered: Set<String>, target: Set<String>): FenceDiff =
    FenceDiff(toAdd = target - registered, toRemove = registered - target)

/**
 * Registers/deregisters OS geofences for circle places the rider is
 * currently near (issue #91) — the proximity-gated tier above the existing
 * poll-only `GeofenceEvaluator` correction path, which keeps running
 * unchanged in `CirclePresence.tick`.
 *
 * Two fences per place reproduce [GeofenceEvaluator]'s two boundary guards
 * natively instead of a single symmetric radius, which would re-introduce
 * exactly the boundary flapping the evaluator exists to prevent:
 * - [PlaceFenceKind.ENTER]: radius `radiusM`, transition `DWELL` with
 *   `setLoiteringDelay(GeofenceEvaluator.MIN_DWELL_MS)` — arrive, dwell
 *   built into the API.
 * - [PlaceFenceKind.EXIT]: radius `radiusM * GeofenceEvaluator.EXIT_HYSTERESIS_FACTOR`,
 *   transition `EXIT` — depart, past the hysteresis boundary.
 *
 * Not to be confused with [ParkGeofence] (issue #90) — a single, unrelated,
 * always-EXIT fence around a parked position, with its own receiver and its
 * own `PendingIntent` action so GMS routes the two kinds of transition to
 * the right place.
 */
object PlaceGeofenceGate {

    const val TAG = "PlaceGeofenceGate"
    private const val ACTION = "com.jellemax.detour.PLACE_GEOFENCE_TRANSITION"

    /** Diffs [candidates] against what's currently registered
     *  ([Settings.registeredPlaceFenceIds]) and issues only the
     *  adds/removes that changed. Call after every [CirclePresence.tick] —
     *  see `TripTrackingService.circleSyncLoop` and `CircleSyncWorker.doWork`. */
    fun sync(context: Context, candidates: List<GateCandidate>) {
        if (!hasLocationPermission(context)) return
        val target = targetFenceIds(candidates)
        val registered = Settings.registeredPlaceFenceIds()
        val diff = diffFenceIds(registered, target)
        if (diff.toAdd.isEmpty() && diff.toRemove.isEmpty()) return

        val client = LocationServices.getGeofencingClient(context)
        if (diff.toRemove.isNotEmpty()) {
            client.removeGeofences(diff.toRemove.toList())
            Settings.setRegisteredPlaceFenceIds(registered - diff.toRemove)
        }
        if (diff.toAdd.isEmpty()) return

        val request = buildRequest(candidates, diff.toAdd) ?: return
        try {
            client.addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    Settings.setRegisteredPlaceFenceIds(Settings.registeredPlaceFenceIds() + diff.toAdd)
                    Log.i(TAG, "registered ${diff.toAdd.size} place fence(s)")
                }
                .addOnFailureListener { Log.w(TAG, "add failed", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "add denied", e)
        }
    }

    /** Clears the persisted registered-set without touching `GeofencingClient` —
     *  for when the OS has already dropped every fence itself (a reboot,
     *  `GEOFENCE_NOT_AVAILABLE`) and the next [sync] just needs to treat
     *  every candidate as unregistered. */
    fun forgetAllRegistered() = Settings.setRegisteredPlaceFenceIds(emptySet())

    private fun buildRequest(candidates: List<GateCandidate>, toAdd: Set<String>): GeofencingRequest? {
        val byPlace = candidates.associateBy { it.circleId to it.placeId }
        val geofences = toAdd.mapNotNull { fenceId ->
            val (circleId, placeId, kind) = parsePlaceFenceId(fenceId) ?: return@mapNotNull null
            val c = byPlace[circleId to placeId] ?: return@mapNotNull null
            when (kind) {
                PlaceFenceKind.ENTER -> Geofence.Builder()
                    .setRequestId(fenceId)
                    .setCircularRegion(c.lat, c.lon, c.radiusM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                    .setLoiteringDelay(GeofenceEvaluator.MIN_DWELL_MS.toInt())
                    .build()
                PlaceFenceKind.EXIT -> Geofence.Builder()
                    .setRequestId(fenceId)
                    .setCircularRegion(c.lat, c.lon, (c.radiusM * GeofenceEvaluator.EXIT_HYSTERESIS_FACTOR).toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
        }
        if (geofences.isEmpty()) return null
        return GeofencingRequest.Builder()
            // A place already dwelt-in when its fence registers (app cold
            // start already parked at a shared place) still fires arrive,
            // rather than needing to leave and come back first.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(geofences)
            .build()
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PlaceGeofenceReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
