package com.jellemax.detour.tracking

import android.content.Context
import com.jellemax.detour.data.LatLon
import kotlinx.coroutines.CoroutineScope

/**
 * The release build's half of `LocationSources`: fixes come from the platform,
 * and there is no other way in.
 *
 * The debug variant compiles `app/src/debug/.../LocationSources.kt` instead,
 * which wraps the same [FusedLocationSource] in a `ReplayLocationSource` that can
 * take over for a run. Split by source set rather than by a `BuildConfig.DEBUG`
 * branch, the same way [ReplayFixGate] and [DriveClocks] are: a shipped app does
 * not contain a replay source it declines to use, it contains no route to one.
 *
 * `app/build.gradle.kts` points `githubRelease` at `src/release/java`, so that
 * variant takes this half too.
 *
 * [scope] is unused here and taken anyway, so both halves have one signature —
 * the replay source needs a scope to pace its emission loop.
 */
internal object LocationSources {

    /** The source the running service is using. Null until [create] has run —
     *  the tracking service is what creates it, and the map can be composed
     *  before that. */
    @Volatile
    var current: LocationSource? = null
        private set

    fun create(
        context: Context,
        listener: LocationBatchListener,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        onPermissionRevoked: () -> Unit,
    ): LocationSource =
        FusedLocationSource(context, listener, onPermissionRevoked).also { current = it }

    /**
     * One-shot position for a caller that has no source of its own — the map
     * centring itself, in practice.
     *
     * Falls back to asking the platform directly when the service has not
     * started yet, which is the same answer the map used to get unconditionally.
     * Throws [SecurityException] if the location permission has gone.
     */
    suspend fun currentLatLon(context: Context): LatLon? =
        current?.currentLatLon() ?: fusedCurrentLatLon(context)
}
