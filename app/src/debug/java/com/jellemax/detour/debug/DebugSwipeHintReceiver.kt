package com.jellemax.detour.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jellemax.detour.data.Settings

/**
 * Switches which mode-swipe hint the spin dock plays, and re-arms it.
 *
 * The hint ships in two variants at once because this app has no analytics,
 * no telemetry and no remote config: a measured A/B is not available, so the
 * two are compared by hand. Once one wins, this receiver, its manifest entry,
 * `Settings.swipeHintVariant` and the losing animation all get deleted.
 *
 * Debug source set only: this class does not exist in a release build, and
 * neither does the manifest entry that registers it (app/src/debug/AndroidManifest.xml).
 *
 * ```
 * # play the arrows variant next time
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
 *     --es variant arrows
 *
 * # back to the nudge
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
 *     --es variant nudge
 *
 * # re-arm: zero the swipe counter so the hint fires again
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
 *     --ez reset true
 * ```
 *
 * The reset arm is not a convenience. The hint retires permanently after three
 * successful swipes, so without it there is exactly one chance to judge each
 * variant on a given install. The alternative is editing shared_prefs by hand,
 * which has already corrupted a settings file once: a sed sent through
 * `adb shell` had its quotes re-parsed on the device, wrote malformed XML, and
 * the app discarded the whole file rather than fail to parse it.
 *
 * Leaving and re-entering the map screen is still required after a broadcast:
 * the hint fires once per map visit.
 */
class DebugSwipeHintReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Every entry point into this process initialises Settings itself -
        // MainActivity, TripTrackingService, CircleNotifyService and
        // DetourCarSession all do. A broadcast is another one: Android starts
        // the process and runs Application.onCreate before delivering, but no
        // Activity, so without this the first setter would hit
        // `error("Settings.init() not called")`. init() early-returns when the
        // store is already open, so this costs nothing when the app is running
        // - which is the case it would otherwise be tested in.
        Settings.init()

        // Both are in-memory writes plus a SharedPreferences put, so unlike
        // DebugTripEndedReceiver this needs no goAsync().
        intent.getStringExtra(EXTRA_VARIANT)?.let { variant ->
            Settings.setSwipeHintVariant(variant)
            Log.i(TAG, "hint variant set to '$variant'")
        }
        if (intent.getBooleanExtra(EXTRA_RESET, false)) {
            Settings.setModeSwipesUsed(0L)
            Log.i(TAG, "swipe counter reset; the hint will fire on the next map visit")
        }
    }

    private companion object {
        const val TAG = "DebugSwipeHint"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_RESET = "reset"
    }
}
