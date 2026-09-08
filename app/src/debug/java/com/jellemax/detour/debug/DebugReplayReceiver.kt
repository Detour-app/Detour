package com.jellemax.detour.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jellemax.detour.tracking.ReplayClock
import com.jellemax.detour.tracking.ReplayFixGate
import com.jellemax.detour.tracking.ReplayMode
import java.io.File

/**
 * The rig's way into the app: how fast a replay is running, and whether the app
 * should believe anything but the replay.
 *
 * Debug source set only. Neither this class nor the manifest entry registering it
 * exists in a release build, so there is no route to [ReplayMode] or to
 * `ReplayClock.setScale` in a shipped app — belt as well as the braces of
 * `setScale`'s own `BuildConfig.DEBUG` check.
 *
 * Out of band, and it has to be: the factor was first stamped onto every mocked
 * `Location` in `MockService`, and Play Services' fused provider delivered every
 * one of those fixes with `extras=null`. Only the standard `Location` fields cross
 * that boundary — which is also why the gate reads `isMock` rather than something
 * the harness attached.
 *
 * `start-replay.sh` sends both extras on the way in and `stop-replay.sh` sends
 * their defaults on the way out, so a replay cannot leave the app scaled or deaf
 * to the world. By hand:
 *
 * ```
 * # enter mock-only mode at 5x
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugReplayReceiver \
 *     --ez mock_only true --ei speedup 5
 *
 * # back to normal
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugReplayReceiver \
 *     --ez mock_only false --ei speedup 1
 * ```
 *
 * The two extras are independent: sending only `speedup` re-paces a run without
 * touching the mode, which is what `replay-speed.sh` does mid-replay.
 */
class DebugReplayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra(EXTRA_MOCK_ONLY)) {
            if (intent.getBooleanExtra(EXTRA_MOCK_ONLY, false)) {
                // Reset on the way in rather than on the way out: the count
                // belongs to the run that is starting, and a run that ended
                // badly should leave its number readable afterwards.
                ReplayFixGate.reset()
                ReplayMode.enter()
            } else {
                ReplayMode.exit()
                report(context)
            }
        }
        if (intent.hasExtra(EXTRA_SPEEDUP)) {
            val speedup = intent.getIntExtra(EXTRA_SPEEDUP, 1)
            ReplayClock.setScale(speedup)
            Log.i(TAG, "asked for ${speedup}x, clock is now ${ReplayClock.scale()}x")
        }
    }

    /**
     * Writes the run's counters where a script can read them.
     *
     * A file rather than a log line, and that is not a preference: logcat over
     * Wi-Fi adb dropped this line on three runs out of three while the same
     * broadcast plainly worked, which makes a ramp look like it measured
     * nothing. `files/replay-run.txt` is read back with
     * `run-as io.github.maxke24.detour.debug cat files/replay-run.txt` and
     * survives until the next run overwrites it.
     */
    private fun report(context: Context) {
        val line = "delivered=${ReplayFixGate.accepted} rejected=${ReplayFixGate.rejected}"
        runCatching { File(context.filesDir, RUN_FILE).writeText(line + "\n") }
            .onFailure { Log.w(TAG, "could not write $RUN_FILE", it) }
        Log.i(TAG, "run $line")
    }

    private companion object {
        const val RUN_FILE = "replay-run.txt"
        const val EXTRA_MOCK_ONLY = "mock_only"
        const val EXTRA_SPEEDUP = "speedup"
        const val TAG = "DebugReplay"
    }
}
