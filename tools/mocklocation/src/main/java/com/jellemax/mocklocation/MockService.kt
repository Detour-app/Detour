package com.jellemax.mocklocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Replays a route as mock fixes so a drive can be recorded without driving.
 *
 * Play Services' fused provider only honours mocks that come from the app the
 * system has designated, and only when the Location carries a plausible
 * elapsedRealtimeNanos — which is why feeding `cmd location` from adb never
 * reached Detour. Designate this app with:
 *
 *     adb shell appops set com.jellemax.mocklocation android:mock_location allow
 *
 * then push a route (one "lon lat" pair per line) and start it:
 *
 *     adb push route.txt /sdcard/Download/route.txt
 *     adb shell am start-foreground-service \
 *         -n com.jellemax.mocklocation/.MockService \
 *         --es route /sdcard/Download/route.txt --ei intervalMs 1000
 *     adb shell am stopservice -n com.jellemax.mocklocation/.MockService
 */
class MockService : Service() {

    // Network and passive too, not just gps/fused: fused blends whatever is
    // enabled, so leaving a real provider live makes it alternate between the
    // mock route and the phone's actual position — which reads as a device
    // teleporting hundreds of kilometres between fixes.
    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.FUSED_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())

        val path = intent?.getStringExtra("route") ?: run {
            Log.e(TAG, "no --es route <file>")
            stopSelf(); return START_NOT_STICKY
        }
        val intervalMs = intent.getIntExtra("intervalMs", 1000).toLong()
        val points = readRoute(File(path))
        if (points.size < 2) {
            Log.e(TAG, "route needs at least 2 points, got ${points.size}")
            stopSelf(); return START_NOT_STICKY
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        providers.forEach { p ->
            runCatching { lm.removeTestProvider(p) }
            runCatching {
                lm.addTestProvider(
                    p, false, false, false, false, true, true, true,
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE,
                )
                lm.setTestProviderEnabled(p, true)
            }.onFailure { Log.e(TAG, "addTestProvider($p) failed: $it") }
        }

        running = true
        thread(name = "mock-replay") {
            var i = 0
            while (running && i < points.size) {
                val here = points[i]
                val next = points[(i + 1).coerceAtMost(points.size - 1)]
                val speed = (distanceMeters(here, next) / (intervalMs / 1000.0)).toFloat()
                providers.forEach { p -> push(lm, p, here, bearing(here, next), speed) }
                i++
                Thread.sleep(intervalMs)
            }
            Log.i(TAG, "replay finished at point $i/${points.size}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        providers.forEach { runCatching { lm.removeTestProvider(it) } }
        super.onDestroy()
    }

    /** Both timestamps matter: fused drops a mock whose elapsedRealtimeNanos is
     *  missing or stale, which is the trap the adb shell route falls into. */
    private fun push(lm: LocationManager, provider: String, p: Pair<Double, Double>,
                     bearingDeg: Float, speedMps: Float) {
        val loc = Location(provider).apply {
            latitude = p.first
            longitude = p.second
            altitude = 10.0
            accuracy = 4f
            bearing = bearingDeg
            speed = speedMps
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 4f
                speedAccuracyMetersPerSecond = 1f
                bearingAccuracyDegrees = 5f
            }
        }
        runCatching { lm.setTestProviderLocation(provider, loc) }
            .onFailure { Log.e(TAG, "setTestProviderLocation($provider): $it") }
    }

    private fun readRoute(f: File): List<Pair<Double, Double>> =
        runCatching {
            f.readLines().mapNotNull { line ->
                val parts = line.trim().split(Regex("[ ,\t]+"))
                if (parts.size < 2) return@mapNotNull null
                val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                lat to lon
            }
        }.getOrElse {
            Log.e(TAG, "cannot read $f: $it"); emptyList()
        }

    private fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = FloatArray(1)
        Location.distanceBetween(a.first, a.second, b.first, b.second, r)
        return r[0].toDouble()
    }

    private fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
        val la1 = Math.toRadians(a.first); val la2 = Math.toRadians(b.first)
        val dLon = Math.toRadians(b.second - a.second)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mock location", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Replaying a mock route")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "MockLocation"
        const val CHANNEL = "mock"
    }
}
