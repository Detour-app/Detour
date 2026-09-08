package com.jellemax.detour.tracking

import android.util.Log
import com.jellemax.detour.data.Settings

/**
 * Whether the app is currently being driven by a replay rather than by the world.
 *
 * A mode the rig switches on, not a state the app infers. The version before this
 * inferred it — trust the real position until a mock fix shows up, distrust it for
 * a few seconds afterwards — and inference has a hole at exactly the wrong moment:
 * every process starts out trusting everything, so the first real fixes of every
 * run were accepted, which is the position marker blipping between the route and
 * the desk that this was supposed to stop. There is no amount of tuning that fixes
 * a heuristic asked to guess something the rig already knows.
 *
 * So `start-replay.sh` says so on the way in and `stop-replay.sh` says so on the
 * way out, through `DebugReplayReceiver`. While it is on, [ReplayFixGate] accepts
 * mock fixes and nothing else — no distance test, no timeout, no grace period.
 *
 * Debug source set only, like the gate it governs. Nothing in a release build can
 * reach it, and there is no release counterpart because nothing there refers to it:
 * the release [ReplayFixGate] is a function returning true.
 *
 * Process-scoped on purpose, and worth knowing: a debug process that dies mid-run
 * comes back trusting the world again, so a replay that outlives a crash starts
 * blending real fixes back in. The alternative is persisting a test-rig flag into
 * the rider's settings, which is worse. `start-replay.sh` re-arms it on every run.
 *
 * The one setting it does write is auto-detect drives, and only because a replay is
 * pointless without it: `onIdleLocation` resets the start detector and returns
 * before any speed gate when it is off, so no route however good can start a trip,
 * and the whole trip half of a measurement silently reports nothing. [enter] turns
 * it on, [exit] puts it back to whatever it was. The debug install is the only one
 * this can reach, and only while a rig is driving it.
 */
object ReplayMode {

    @Volatile
    private var replaying = false

    /** What auto-detect was before [enter] turned it on, or null when it was
     *  already on and there is nothing to put back. */
    @Volatile
    private var autoDetectWas: Boolean? = null

    /** True while a replay owns this app's idea of where it is. */
    val active: Boolean get() = replaying

    fun enter() {
        replaying = true
        // Idempotent, and called first for the reason BootReceiver and
        // PlaceGeofenceReceiver call it: a broadcast can spawn this process, and
        // reading Settings before init throws.
        Settings.init()
        if (!Settings.autoDetectDrives.value) {
            autoDetectWas = false
            Settings.setAutoDetectDrives(true)
            Log.i(TAG, "auto-detect drives was off; on for this run")
        }
        Log.i(TAG, "mock-only: real positions will be rejected until told otherwise")
    }

    fun exit() {
        replaying = false
        autoDetectWas?.let {
            Settings.init()
            Settings.setAutoDetectDrives(it)
            autoDetectWas = null
            Log.i(TAG, "auto-detect drives restored to $it")
        }
        Log.i(TAG, "mock-only off: back to the world")
    }

    private const val TAG = "DetourReplayMode"
}
