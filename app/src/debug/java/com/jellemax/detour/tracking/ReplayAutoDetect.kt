package com.jellemax.detour.tracking

import android.util.Log
import com.jellemax.detour.data.Settings

/**
 * Turns auto-detect drives on for the length of a replay, and puts it back.
 *
 * Shared by both rigs, which is why it is not part of [ReplayMode]. A replay is
 * pointless without it — `onIdleLocation` resets the start detector and returns
 * before any speed gate when it is off, so no route however good can start a
 * trip, and the trip half of a measurement silently reports nothing. That is
 * true whether the fixes arrive through the platform's mock provider or through
 * [ReplayLocationSource], so both call this rather than one of them owning it.
 *
 * It was [ReplayMode]'s until #306 added the second rig: the port armed, ran a
 * whole route, delivered every fix and recorded no trip, because the only thing
 * that switched auto-detect on was the mock rig's mode object.
 *
 * The debug install is the only one this can reach, and only while a rig is
 * driving it. [enable] is idempotent; [restore] is a no-op unless [enable]
 * actually changed something, so a rider who had it on keeps it on.
 */
internal object ReplayAutoDetect {

    /** What auto-detect was before [enable] turned it on, or null when it was
     *  already on and there is nothing to put back. */
    @Volatile
    private var was: Boolean? = null

    fun enable() {
        // Called first for the reason BootReceiver and PlaceGeofenceReceiver
        // call it: a broadcast can spawn this process, and reading Settings
        // before init throws.
        Settings.init()
        if (!Settings.autoDetectDrives.value) {
            was = false
            Settings.setAutoDetectDrives(true)
            Log.i(TAG, "auto-detect drives was off; on for this run")
        }
    }

    fun restore() {
        was?.let {
            Settings.init()
            Settings.setAutoDetectDrives(it)
            was = null
            Log.i(TAG, "auto-detect drives restored to $it")
        }
    }

    private const val TAG = "DetourReplayMode"
}
