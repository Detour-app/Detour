package com.jellemax.detour.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TripStore
import com.jellemax.detour.notif.TripEndedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Raises the real trip-ended notification on demand, so its tap behaviour can
 * be tested without driving far enough to trip auto-detection.
 *
 * Debug source set only: this class does not exist in a release build, and
 * neither does the manifest entry that registers it (app/src/debug/AndroidManifest.xml).
 *
 * ```
 * # newest trip in history — tap should open its detail screen
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver
 *
 * # a specific trip
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver \
 *     --el start_ms 1754899200000
 *
 * # a trip that does not exist — tap should land on History, not a blank detail
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugTripEndedReceiver \
 *     --el start_ms 1
 * ```
 *
 * Broadcasting twice with different `start_ms` and tapping only after the
 * second is the FLAG_UPDATE_CURRENT case: it must open the second trip, not
 * the first. That is the failure a single-trip test cannot see.
 */
class DebugTripEndedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.getLongExtra(EXTRA_START_MS, -1L).takeIf { it > 0 }
        // load() reads and parses a file, so it does not belong on the main
        // thread onReceive runs on; goAsync keeps the process alive for it.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // accountDir() refuses to resolve a path before the account
                // migration has run, so every process-starting component has
                // to call this first. The documented way to use this receiver
                // — `am force-stop` then `am broadcast`, in the KDoc above and
                // in docs/DEBUG_INTENTS.md — starts a cold process, and
                // without this the check throws out of an unsupervised
                // coroutine and takes the process with it.
                //
                // Here rather than at the top of onReceive: securePrefs() is
                // Keystore-backed and measured at 1.6-1.8s on a Galaxy S928B,
                // and onReceive runs on the main thread — which is the whole
                // reason goAsync() is above. What matters is only that it
                // precedes the first store call, and it does.
                Settings.init()
                val startTimeMs = explicit
                    ?: TripStore.load().maxByOrNull { it.startTimeMs }?.startTimeMs
                    // No trips and no explicit id: use a timestamp no trip can
                    // have, which exercises the missing-trip fallback rather
                    // than silently doing nothing.
                    ?: System.currentTimeMillis()
                Log.i(TAG, "raising trip-ended notification for startTimeMs=$startTimeMs")
                TripEndedNotification.show(context, startTimeMs)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_START_MS = "start_ms"
        const val TAG = "DebugTripEnded"
    }
}
