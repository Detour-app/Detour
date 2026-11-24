package com.jellemax.detour.car

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.car.app.CarContext
import com.jellemax.detour.data.LatLon

/**
 * Hands a destination with no turn data to the car's navigation app, and
 * spots the hand-off coming straight back. Detour itself answers the
 * NAVIGATE intent, so when it is the head unit's navigation app the host
 * delivers our own hand-off to [DetourCarSession.onNewIntent]; routing it
 * again would fail the same way and hand off again.
 */
internal object CarNavHandOff {
    /** Long enough for the host to bounce the intent back, short enough that a rider's own
     *  "Navigate to" the same place a minute later is still taken. */
    private const val ECHO_WINDOW_MS = 10_000L

    private var lastUri: String? = null
    private var lastAtMs = 0L

    fun start(carContext: CarContext, dest: LatLon) {
        val uri = "geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}"
        lastUri = uri
        lastAtMs = SystemClock.elapsedRealtime()
        carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)))
    }

    /** True when [uri] is the hand-off this process just sent, delivered back to us. */
    fun isEcho(uri: Uri): Boolean =
        uri.toString() == lastUri && SystemClock.elapsedRealtime() - lastAtMs < ECHO_WINDOW_MS
}
