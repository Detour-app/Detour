package com.jellemax.detour.perf

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.Gpx
import com.jellemax.detour.data.Perf
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.prefs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The Android half of #84: keeps [Perf]'s samples as a series on this device.
 *
 * Every decision worth getting right — which labels aggregate, how a covariate
 * buckets, the row format, the flush cadence, the ring cap — is in [PerfLog],
 * which is tested. What is left here is the file and the thread.
 *
 * **Device-local, and deliberately not account-scoped.** A series is only
 * readable as a curve if every point on it came from the same device and the
 * same storage; mixing devices makes the numbers relative to nothing. So it
 * never goes into the `/sync` payload, it sits at the `filesDir` root rather
 * than under `accounts/<key>/` (an account switch would otherwise rotate the
 * series out from under itself mid-curve), and both backup manifests exclude
 * it so a restore cannot land one device's series on another.
 *
 * **Readable on release only through [writeForShare].** `run-as` refuses a
 * non-debuggable package, `adbd` refuses `adb root` on a production build, and
 * since Android 12 `adb backup` carries no app data for a non-debuggable app —
 * so on a release install this file is unreachable over adb, and a seam with no
 * export would be write-only. The share sheet is the same route the GPX export
 * already uses (`Gpx.writeForShare`).
 */
object PerfSink {

    private const val FILE_NAME = "perf.jsonl"

    /** Ring cap. Roughly a fortnight of screen-open rows plus aggregated
     *  buckets; small enough that a rider never notices it. */
    private const val MAX_BYTES = 512 * 1024

    // One thread, so writes are serialised without a lock on the file and never
    // land on the caller's — samples arrive from the GPS callback, the draw
    // pass, the sync scope and whichever screen is open.
    private val writer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "perf-sink").apply { isDaemon = true }
    }

    // The periodic tick, and why there is one: [accept] can only ever wake the
    // writer when a *later* sample arrives, so a workload that goes quiet — the
    // rider stops navigating, or the app is killed — left its last buffered rows
    // on the floor. Observed on a device: a map pan aggregated its buckets and
    // nothing wrote them until something else was recorded. The tick makes the
    // tail land within one interval whatever happens next.
    private var tick: ScheduledFuture<*>? = null

    private val lock = Any()
    private val fileLock = Any()
    private val aggregator = PerfAggregator()
    private val rows = ArrayList<String>()
    private var lastFlushMs = 0L
    private var file: File? = null

    /**
     * Installs the sink if the rider has the setting on.
     *
     * Reads the key straight out of the preference bag rather than through
     * `Settings.perfTracing`, because this is called from
     * `Application.onCreate` and `Settings.init()` has not run yet — its flow
     * would still hold the default. `Application.onCreate` is the one place
     * ahead of all four entry points that call `Settings.init()`, which is what
     * keeps this from being written out four times.
     */
    fun installIfEnabled(context: Context) {
        if (prefs(Settings.PREFS_NAME).bool(Settings.PERF_TRACING_KEY, false)) install(context)
    }

    /** Flips the setting and starts or stops recording to match. */
    fun setEnabled(context: Context, on: Boolean) {
        Settings.setPerfTracing(on)
        if (on) install(context) else uninstall()
    }

    /** Starts recording into `filesDir/perf.jsonl`. Idempotent. */
    fun install(context: Context) {
        synchronized(lock) {
            file = File(context.filesDir, FILE_NAME)
            lastFlushMs = System.currentTimeMillis()
            if (tick == null) {
                tick = writer.scheduleWithFixedDelay(
                    ::flush,
                    PerfLog.FLUSH_INTERVAL_MS,
                    PerfLog.FLUSH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
        Perf.sink = ::accept
    }

    /** Stops recording and writes whatever is still buffered. */
    fun uninstall() {
        Perf.sink = null
        synchronized(lock) {
            tick?.cancel(false)
            tick = null
        }
        writer.execute { flush() }
    }

    /**
     * Buffers one sample. Runs on whatever thread recorded it, so it does no
     * I/O: a hot label folds into its covariate bucket, everything else becomes
     * a row, and the writer is woken only when [PerfLog.shouldFlush] says so.
     */
    private fun accept(sample: Perf.Sample) {
        val now = System.currentTimeMillis()
        val due: Boolean
        synchronized(lock) {
            if (PerfLog.isHot(sample.label)) aggregator.add(sample)
            else rows.add(PerfLog.row(sample, now))
            due = PerfLog.shouldFlush(now, lastFlushMs, rows.size + aggregator.size)
            if (due) lastFlushMs = now
        }
        if (due) writer.execute { flush() }
    }

    private fun flush() {
        val target = file ?: return
        val now = System.currentTimeMillis()
        val batch: List<String>
        synchronized(lock) {
            batch = rows + aggregator.drain(now)
            rows.clear()
        }
        if (batch.isEmpty()) return
        try {
            // fileLock, not lock: recording must never wait on a write, but the
            // two writers must not interleave — [writeForShare] flushes on the
            // caller's thread so an export carries the rows the rider just
            // generated, and a half-written line is a corrupt series.
            synchronized(fileLock) {
                target.appendText(batch.joinToString(separator = "\n", postfix = "\n"))
                trim(target)
            }
        } catch (e: Exception) {
            // A diagnostic that crashes the app it is diagnosing is worse than
            // no diagnostic. Reported, not rethrown, and not retried: the next
            // flush carries the next batch either way.
            Log.w("PerfSink", "could not write $FILE_NAME", e)
        }
        if (BuildConfig.DEBUG) for (row in batch) Log.d("Perf", row)
    }

    /** Drops the oldest rows once the file passes [MAX_BYTES]. Only reads the
     *  file back on the flush that actually crosses the cap. */
    private fun trim(target: File) {
        if (target.length() <= MAX_BYTES) return
        val kept = PerfLog.trimmed(target.readLines(), MAX_BYTES)
        target.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
    }

    /**
     * Copies the series into the shared cache and returns a `content://` Uri for
     * it, for the Settings export. Null when nothing has been recorded.
     *
     * Flushed first, on this thread: the rows a rider is exporting are usually
     * the ones from the screen they just came off.
     */
    fun writeForShare(context: Context): Uri? {
        flush()
        val source = file ?: File(context.filesDir, FILE_NAME)
        if (!source.exists() || source.length() == 0L) return null
        val dir = File(context.cacheDir, Gpx.SHARE_DIR).apply { mkdirs() }
        val copy = File(dir, "detour-perf.jsonl")
        synchronized(fileLock) { source.copyTo(copy, overwrite = true) }
        return FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", copy)
    }
}
