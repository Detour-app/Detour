package com.jellemax.detour.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a start does with the crash the last process left behind (#824). */
enum class CrashOnStart {
    /** No crash is waiting. */
    NOTHING,

    /** The rider opted in: send it without asking. */
    SEND,

    /** The rider hasn't opted in: ask whether to review and send it. */
    OFFER,
}

/**
 * The start-up half of crash reporting (#824): the platform's crash handler
 * leaves a [PendingCrash] in [CrashStore], and the next start decides here what
 * becomes of it.
 */
object CrashStartup {

    // One send at a time: an activity finished and relaunched in the same
    // process can start a second send while the first is still in flight, and
    // two would file the crash twice.
    private val sending = Mutex()

    /** Send when [sendCrashReports] is on, offer when it is off, and nothing
     *  without a [hasPending] crash. */
    fun decide(hasPending: Boolean, sendCrashReports: Boolean): CrashOnStart = when {
        !hasPending -> CrashOnStart.NOTHING
        sendCrashReports -> CrashOnStart.SEND
        else -> CrashOnStart.OFFER
    }

    /**
     * Whether the crash is forgotten after an automatic send: yes once the
     * server has it, and yes when the server can't take reports at all, since
     * retrying every start would never succeed. Kept on [SendResult.Failed],
     * so the next start tries again.
     */
    fun clearAfter(result: SendResult): Boolean = result !is SendResult.Failed

    /**
     * Sends the pending crash, if there still is one, with the platform's
     * event-log [events] read when the send starts. Null when there was nothing
     * to send. Re-reads [CrashStore] under the lock, so a second caller after a
     * successful send finds nothing. Does file IO; call off the main thread.
     */
    suspend fun sendPending(events: () -> List<String>): SendResult? = sending.withLock {
        val crash = CrashStore.pending() ?: return null
        val result = ProblemReports.send(ProblemReports.preview(ProblemReportKind.CRASH, null, crash, events()))
        if (clearAfter(result)) CrashStore.clear()
        result
    }
}
