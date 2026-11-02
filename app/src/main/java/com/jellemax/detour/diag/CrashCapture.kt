package com.jellemax.detour.diag

import android.os.Process
import com.jellemax.detour.data.CrashStore
import com.jellemax.detour.data.EventKind
import com.jellemax.detour.data.EventLog
import kotlin.system.exitProcess

/**
 * Catches an uncaught exception on any thread (#824) and keeps it in
 * [CrashStore] for the next start to offer or send. Nothing here touches the
 * network: the process is dying, and the next start is the one that sends.
 *
 * It always hands on to the handler it replaced, so the system's crash dialog
 * and the process death behave exactly as before.
 */
object CrashCapture {

    @Volatile
    private var installed = false

    /** Installs the handler ahead of the current one. Idempotent. Call after
     *  [EventSink.install], so the CRASH line has somewhere to go. */
    fun install(versionName: String) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record(e, versionName)
            if (previous != null) {
                previous.uncaughtException(thread, e)
            } else {
                // What the platform's own handler would have done.
                Process.killProcess(Process.myPid())
                exitProcess(EXIT_CRASHED)
            }
        }
    }

    // Throwable, not Exception: an OutOfMemoryError while saving must not
    // replace the crash being saved, and the handler must still chain.
    @Suppress("TooGenericExceptionCaught")
    private fun record(e: Throwable, versionName: String) {
        try {
            CrashStore.writeBlocking(System.currentTimeMillis(), e.stackTraceToString(), versionName)
            EventLog.record(EventKind.CRASH, e.javaClass.name)
            // The writer thread won't get to the CRASH line before the process dies.
            EventSink.flushBlocking()
        } catch (t: Throwable) {
            // Losing the report is the lesser harm.
        }
    }

    private const val EXIT_CRASHED = 10
}
