package com.jellemax.detour.tracking

import android.util.Log

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
 * The one setting it does write is auto-detect drives, through [ReplayAutoDetect]
 * — which lives apart from this object because the port rig (#306) needs the same
 * thing without this one's reject-everything-but-mock behaviour.
 */
object ReplayMode {

    @Volatile
    private var replaying = false


    /** True while a replay owns this app's idea of where it is. */
    val active: Boolean get() = replaying

    fun enter() {
        replaying = true
        ReplayAutoDetect.enable()
        Log.i(TAG, "mock-only: real positions will be rejected until told otherwise")
    }

    fun exit() {
        replaying = false
        ReplayAutoDetect.restore()
        Log.i(TAG, "mock-only off: back to the world")
    }

    private const val TAG = "DetourReplayMode"
}
