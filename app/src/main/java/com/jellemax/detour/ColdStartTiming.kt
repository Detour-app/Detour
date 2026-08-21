package com.jellemax.detour

import android.os.SystemClock
import android.util.Log

/**
 * Cold-start diagnostics for the suspects issue #54 named but couldn't pin —
 * initSharedCore, BuildDefaults.configure, Settings.init,
 * CircleNotifyService.refresh, MapLibre.getInstance, and the MapView/style
 * init the issue's own notes flagged as the likeliest single contributor.
 * Debug-only: BuildConfig.DEBUG is a compile-time constant, so this and its
 * call sites are dead code in a release build — the same build the issue
 * says can't be profiled over adb anyway, since it isn't debuggable.
 *
 * `Log.d`, not `android.os.Trace` sections: a systrace/Perfetto capture
 * needs a session set up around the cold launch, and logcat needs nothing
 * but `adb shell am start -W` and a grep. Cheaper to read here, and the
 * numbers are the same either way.
 */
object ColdStartTiming {
    @PublishedApi internal val appCreateStartMs = SystemClock.elapsedRealtime()

    fun mark(label: String) {
        if (BuildConfig.DEBUG) {
            val now = SystemClock.elapsedRealtime()
            Log.d("ColdStart", "$label (t+${now - appCreateStartMs}ms)")
        }
    }

    inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        val result = block()
        if (BuildConfig.DEBUG) {
            val now = SystemClock.elapsedRealtime()
            Log.d("ColdStart", "$label: ${now - t0}ms (t+${now - appCreateStartMs}ms)")
        }
        return result
    }
}
