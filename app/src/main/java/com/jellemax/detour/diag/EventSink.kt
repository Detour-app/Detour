package com.jellemax.detour.diag

import android.content.Context
import android.util.Log
import com.jellemax.detour.data.EventLog
import java.io.File
import java.util.concurrent.Executors

/**
 * The Android half of [EventLog] (#819): keeps its lines in `filesDir/events.log`.
 *
 * **Device-local, and deliberately not account-scoped**, for the same reason as
 * `PerfSink`: the log describes this device, not a rider, so it sits at the
 * `filesDir` root rather than under `accounts/<key>/`, survives sign-out, and is
 * never in the `/sync` payload. The backup manifests are include-only
 * allowlists, so not being named there is what keeps it out of a backup.
 *
 * Installed unconditionally in `DetourApplication.onCreate`, ahead of every
 * entry point. Installing touches no disk; the first write does.
 */
object EventSink {

    private const val FILE_NAME = "events.log"

    @Volatile
    private var writer: EventWriter? = null

    /** Starts recording [EventLog] into `filesDir/events.log`. Idempotent. */
    fun install(context: Context) {
        if (writer != null) return
        val w = EventWriter(File(context.filesDir, FILE_NAME))
        writer = w
        EventLog.sink = w::accept
    }

    /** Writes anything still queued on the calling thread, for the crash
     *  handler, which can't wait for the writer thread to get to it. */
    fun flushBlocking() {
        writer?.flushBlocking()
    }

    /** Every kept line, oldest first, for a problem report. Empty before
     *  [install] or before anything was recorded. */
    fun lines(): List<String> = writer?.lines().orEmpty()
}

/**
 * The file and the thread behind [EventSink], apart from the `Context` so a JVM
 * test can drive it against a temporary file.
 *
 * One thread, so lines from the service, the GPS callback, sync and the crash
 * handler never interleave and none of them waits on the disk. Every write
 * takes [fileLock] and drains [pending] inside it, so a [flushBlocking] on
 * another thread keeps the order lines were recorded in.
 */
internal class EventWriter(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pending = ArrayList<String>()
    private val fileLock = Any()

    // False until the first write has trimmed: a log left by an earlier
    // process may hold lines past EventLog.KEEP_MS that no cap would catch.
    private var trimmed = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-sink").apply { isDaemon = true }
    }

    /** Queues [line] and wakes the writer. Runs on the recording thread, so it
     *  does no I/O. */
    fun accept(line: String) {
        synchronized(pending) { pending.add(line) }
        executor.execute(::drain)
    }

    fun flushBlocking() = drain()

    fun lines(): List<String> {
        drain()
        return synchronized(fileLock) { if (file.exists()) file.readLines() else emptyList() }
    }

    /** Empties the log, for tests only. */
    fun clear() {
        synchronized(fileLock) {
            synchronized(pending) { pending.clear() }
            file.delete()
            trimmed = false
        }
    }

    private fun drain() {
        try {
            synchronized(fileLock) {
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (batch.isEmpty()) return
                file.appendText(batch.joinToString(separator = "\n", postfix = "\n"))
                if (!trimmed || file.length() > EventLog.MAX_BYTES) {
                    trimmed = true
                    val kept = EventLog.trim(file.readLines(), now())
                    file.writeText(if (kept.isEmpty()) "" else kept.joinToString(separator = "\n", postfix = "\n"))
                }
            }
        } catch (e: Exception) {
            // A diagnostic that crashes the app it is diagnosing is worse than
            // no diagnostic. The batch is lost; the next one is written anyway.
            Log.w("EventSink", "could not write ${file.name}", e)
        }
    }
}
