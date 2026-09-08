package com.jellemax.detour.tracking

import android.content.Context
import com.jellemax.detour.data.LatLon
import kotlinx.coroutines.CoroutineScope

/**
 * The debug build's half of `LocationSources`: the platform source, wrapped in a
 * [ReplayLocationSource] that can take the run over and hand it back.
 *
 * The release variant compiles `app/src/release/.../LocationSources.kt`, which
 * returns [FusedLocationSource] bare. Same source-set split as [ReplayFixGate]
 * and [DriveClocks].
 *
 * **A debug build with no replay armed behaves exactly as before.** The wrapper
 * forwards every call to the fused source until something calls
 * [ReplayLocationSource.arm].
 *
 * [replay] is how `DebugReplayReceiver` reaches the live source to arm it. It is
 * set by [create], which the service calls once, so it is null only before the
 * tracking service has ever started — the receiver reports that rather than
 * silently doing nothing.
 */
internal object LocationSources {

    /** The wrapper handed to the running service, for the debug receiver to arm.
     *  Null until [create] has run. */
    @Volatile
    var replay: ReplayLocationSource? = null
        private set

    /** The source the running service is using — the wrapper, here. Null until
     *  [create] has run; the map can be composed before the service starts. */
    @Volatile
    var current: LocationSource? = null
        private set

    fun create(
        context: Context,
        listener: LocationBatchListener,
        scope: CoroutineScope,
        onPermissionRevoked: () -> Unit,
    ): LocationSource {
        val fused = FusedLocationSource(context, listener, onPermissionRevoked)
        return ReplayLocationSource(
            context = context,
            listener = listener,
            clock = DriveClocks.current,
            fused = fused,
            scope = scope,
        ).also {
            replay = it
            current = it
        }
    }

    /**
     * One-shot position for a caller that has no source of its own.
     *
     * While a port replay is armed this answers with the route's position, which
     * is the whole reason `MapScreen.fetchLocation` goes through here instead of
     * asking fused: the platform has never heard of the route, so it would answer
     * with where the phone physically is and the map would jump there mid-run.
     */
    suspend fun currentLatLon(context: Context): LatLon? =
        current?.currentLatLon() ?: fusedCurrentLatLon(context)
}
