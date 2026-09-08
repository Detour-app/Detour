package com.jellemax.detour.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jellemax.detour.tracking.ReplayClock

/**
 * Tells the app how much faster than real time `tools/mocklocation` is
 * replaying, so [ReplayClock] can time the drive rather than the replay.
 *
 * Debug source set only: neither this class nor the manifest entry that
 * registers it exists in a release build, so there is no route to
 * [ReplayClock.setScale] in a shipped app at all — belt as well as the braces
 * of `setScale`'s own `BuildConfig.DEBUG` check.
 *
 * Out of band, and it has to be. The factor was first stamped onto every mocked
 * `Location` in `MockService`, which is the obvious place for it, and it does
 * not survive: Detour reads Play Services' fused provider, and fused delivered
 * every one of those fixes with `extras=null` on an Android 15 emulator. Only
 * the standard `Location` fields cross that boundary.
 *
 * `start-replay.sh` sends this before starting the service and `stop-replay.sh`
 * sends `1` after, so a replay cannot leave the clock scaled. By hand:
 *
 * ```
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugReplayClockReceiver \
 *     --ei speedup 5
 *
 * # back to real time
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugReplayClockReceiver \
 *     --ei speedup 1
 * ```
 *
 * The scale lives in the app's process, so a process death mid-replay drops
 * back to 1x rather than carrying on scaled — the trip's duration is then wrong
 * for the remainder, which is visible in the trip card rather than silent.
 */
class DebugReplayClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val speedup = intent.getIntExtra("speedup", 1)
        ReplayClock.setScale(speedup)
        Log.i(TAG, "asked for ${speedup}x, clock is now ${ReplayClock.scale()}x")
    }

    private companion object {
        const val TAG = "DebugReplayClock"
    }
}
