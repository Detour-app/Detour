package com.jellemax.detour.tracking

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.tracking.TripTrackingService.LocationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The debug build's [LocationSource]: a route read from this app's *own*
 * `filesDir`, emitted straight into the fix pipeline (#306).
 *
 * No second app, no mock-location designation, no `appops`, and no blending —
 * the real position is not filtered out, it is never produced. See
 * [LocationSource]'s KDoc for the four platform costs this avoids, all of them
 * measured.
 *
 * **It delegates when it is not armed.** A debug build always has this as its
 * source; with no replay running every call forwards to [fused], so an ordinary
 * debug install behaves exactly as it did. That is also what makes the rig
 * selectable per run rather than per build, which #306 asks for: [arm] switches
 * this run to the port, [disarm] gives it back.
 *
 * **Pacing, and why the speed stays honest.** One line of the route is one
 * *nominal* interval, so speed is the gap to the next point divided by
 * [intervalMs] — never by the wall time actually slept. At 5x the loop sleeps
 * `intervalMs / 5` and still reports the speed the recorded drive was done at,
 * exactly as `MockService` does; [arm] moves the drive clock by the same factor
 * so the app's own dwells and durations stay in the drive's time (#307). The two
 * halves have to agree, which is why one call sets both.
 *
 * **There is no delivery ceiling here.** Fused thinned the mock rig to 5-9 fixes
 * a second whatever the factor, so 5x was the fastest lossless replay. Nothing
 * between this loop and [TripTrackingService.onLocation] rate-limits, so the
 * factor is bounded by [ScaledClock.MAX_SCALE] and by how fast the app can
 * actually consume a fix.
 *
 * **What this rig cannot see, and the mock provider still can:** provider
 * selection, the accuracy gate on a real fix, `elapsedRealtimeNanos` freshness
 * as the platform stamps it, and fused's own blending and thinning. Two of the
 * defects behind #306 lived in exactly that leg. Keep using the mock rig for it.
 *
 * **A replay through this port does not move the six one-shot `lastLocation`
 * readers** — `MapScreen`, `Theme`, `SpinScreen`, `SearchScreen`,
 * `RouteEditorScreen` and `CircleSyncWorker` all still ask fused where the
 * device is, and fused will answer with the real position. The fix *stream* is
 * replayed; "where am I right now" is not. Under the mock-provider rig those
 * agree, because the mock reaches fused itself. This is the one behaviour the
 * port has that the older rig does not.
 */
internal class ReplayLocationSource(
    private val context: Context,
    private val listener: LocationBatchListener,
    private val clock: ScaledDriveClock,
    private val fused: LocationSource,
    /** The service's scope. Not created here — a non-Compose class in this repo
     *  is handed a scope rather than owning one. */
    private val scope: CoroutineScope,
) : LocationSource {

    @Volatile
    private var run: Job? = null

    /** The point most recently handed to the app, so a one-shot
     *  [currentLatLon] answers with the replay's position rather than the
     *  device's. Null until the first fix of a run. */
    @Volatile
    private var lastEmitted: LatLon? = null

    /** True while a replay is driving the app instead of the platform. */
    val armed: Boolean get() = run?.isActive == true

    /**
     * Start replaying [routeFile] from this app's `filesDir`.
     *
     * Pushed there with
     * `adb shell "run-as io.github.maxke24.detour.debug sh -c 'cat > files/<name>'"` —
     * this app's own directory, not the harness's, which is the whole point.
     */
    fun arm(routeFile: String, intervalMs: Long, speedup: Int) {
        disarm()
        ReplayFixGate.reset()
        val points = readRoute(routeFile)
        if (points.size < 2) {
            Log.e(TAG, "$routeFile: need at least 2 usable points, got ${points.size}")
            return
        }
        // Both halves of the compression, set together: the pacing below and the
        // app's own clock. Setting one without the other is the lie #307 fixed.
        val factor = speedup.coerceIn(1, ScaledClock.MAX_SCALE)
        clock.setScale(factor)
        // Without this the port delivers a whole route and records no trip:
        // onIdleLocation returns before any speed gate when auto-detect is off.
        // Shared with the mock rig rather than reimplemented — see the object.
        ReplayAutoDetect.enable()
        // The platform request is dropped for the duration: nothing else should
        // be feeding the pipeline while the port is.
        fused.stop()
        Log.i(TAG, "port replay: ${points.size} points, ${intervalMs}ms, ${factor}x")
        // Main, and this is not incidental: fused delivers on
        // Looper.getMainLooper(), and onLocation's field writes are documented as
        // safe because they all happen on that one thread. The service's own
        // scope is Dispatchers.IO, so emitting on it unqualified would put the
        // whole ingest path on a second thread and race the OBD speed refresh.
        run = scope.launch(Dispatchers.Main.immediate) { emit(points, intervalMs, factor) }
    }

    /** Stop replaying and hand the app back to the platform. */
    fun disarm() {
        run?.cancel()
        run = null
        lastEmitted = null
        clock.setScale(1)
        ReplayAutoDetect.restore()
    }

    private suspend fun emit(points: List<LatLon>, intervalMs: Long, factor: Int) {
        val stepMs = (intervalMs / factor).coerceAtLeast(1L)
        val nominalSec = intervalMs / 1000.0
        for (i in points.indices) {
            val here = points[i]
            // The last point has no successor, so it reports speed 0 — a
            // stopped vehicle, which is what the end of a route is.
            val next = points[(i + 1).coerceAtMost(points.lastIndex)]
            val meters = RoadRoulette.distanceMeters(here, next)
            lastEmitted = here
            listener.onFixes(listOf(fixAt(here, meters / nominalSec, next)))
            delay(stepMs)
        }
        // pushed vs delivered, the quantity #306 is measured by. Written where
        // a script can read it, the same file and format the mock rig uses, so
        // the ramp in detour-gps-replay reads both rigs the same way.
        val line = "pushed=${points.size} delivered=${ReplayFixGate.accepted}"
        runCatching { File(context.filesDir, RUN_FILE).writeText(line + "\n") }
            .onFailure { Log.w(TAG, "could not write $RUN_FILE", it) }
        Log.i(TAG, "port replay finished at point ${points.size}/${points.size}, $line")
        // A route that runs to its end leaves the app exactly as a disarm would,
        // so a finished run cannot strand a scaled clock or a flipped setting.
        clock.setScale(1)
        ReplayAutoDetect.restore()
    }

    private fun fixAt(at: LatLon, speedMps: Double, next: LatLon): Location =
        Location(PROVIDER).apply {
            latitude = at.lat
            longitude = at.lon
            // The same fixed accuracy the mock harness reports, and for the same
            // reason: comfortably inside MAX_START_ACCURACY_M (25 m), so a
            // replay never accidentally tests the degraded-accuracy paths. Those
            // still need a real device in a bad spot.
            accuracy = 4f
            speed = speedMps.toFloat()
            if (at != next) bearing = RoadRoulette.bearingDeg(at, next).toFloat()
            // Wall time, deliberately: this is the provider's own stamp, and
            // DriveClock.fixTimeMs is what decides whether the drive's clock
            // replaces it. A source that pre-scaled this would scale it twice.
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    /** One `lon lat` pair per line — longitude first, matching the harness's
     *  format so the same route files work on both rigs. Unparseable lines are
     *  skipped, as they are there. */
    private fun readRoute(name: String): List<LatLon> {
        val f = File(context.filesDir, name)
        if (!f.exists()) {
            Log.e(TAG, "no route at ${f.absolutePath}")
            return emptyList()
        }
        return f.readLines().mapNotNull { line ->
            val parts = line.trim().split(SEPARATOR)
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            LatLon(lat, lon)
        }
    }

    override fun ensureFor(mode: LocationMode): Boolean =
        // While armed the cadence is the route's, so there is nothing to
        // re-request and nothing for the notification to refresh.
        if (armed) false else fused.ensureFor(mode)

    override fun stop() {
        disarm()
        fused.stop()
    }

    /**
     * The replay's own position while armed, and the platform's otherwise.
     *
     * This is what keeps `MapScreen.fetchLocation` from yanking the camera to
     * where the phone physically is in the middle of a run. Asking the platform
     * here was correct under the mock-provider rig, because the mock reaches
     * fused; through this port the platform has never heard of the route.
     */
    override suspend fun currentLatLon(): LatLon? =
        if (armed) lastEmitted else fused.currentLatLon()

    private companion object {
        const val PROVIDER = "detour-replay"
        const val RUN_FILE = "replay-run.txt"
        const val TAG = "DetourPortReplay"
        val SEPARATOR = Regex("[ ,\t]+")
    }
}
