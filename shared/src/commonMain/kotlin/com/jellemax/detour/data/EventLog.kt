package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * What [EventLog] records. A closed set on purpose: a problem report (#811) is
 * read against these kinds, and a free-form tag would let a call site invent
 * one that nothing downstream knows how to read.
 */
enum class EventKind {
    SERVICE_START, SERVICE_STOP, TRIP_STATE, PERMISSION, TRANSITION,
    GEOFENCE, DORMANCY, SYNC_ERROR, CRASH, APP_START,
}

/**
 * A device-local log of what the app did, so a problem report (#811) can say
 * what led up to it: a service start storm (#490), a refused geofence (#491), a
 * sync that kept failing. One line per event: `<iso-utc> <KIND> <detail>`.
 *
 * Same seam as [Perf]: `commonMain` has no file I/O, so this only builds the
 * line and hands it to [sink], which the platform installs
 * (`app/.../diag/EventSink.kt` on Android). With no sink installed, [record]
 * is a volatile read and a branch, which is what makes it safe to call from
 * the GPS path and the service lifecycle in a release build.
 *
 * **No coordinates, no account data.** A detail is a state, a status code or a
 * speed, never a position or a URL with a query string. [scrub] is the second
 * guard behind that rule, not the first. Because nothing here belongs to an
 * account, the log is kept across sign-out and lives outside `accounts/`.
 */
object EventLog {

    /** Longest detail kept, after [scrub]. A line is a breadcrumb, not a dump. */
    const val DETAIL_MAX_CHARS = 120

    /** How far back [trim] keeps lines: long enough to cover the ride a report
     *  is about, short enough that the log never becomes a travel diary. */
    const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** Size cap [trim] holds the log under, newest lines first. */
    const val MAX_BYTES = 256 * 1024

    // A decimal with four or more fractional digits: 51.2193 is ~10 m of
    // latitude, finer than any speed, distance or ratio this log carries.
    private val COORDINATE_LIKE = Regex("""\d+\.\d{4,}""")
    private val LINE_BREAK = Regex("[\r\n]+")

    /**
     * Where lines go. Null, the default and iOS's state for now, means nothing
     * is recorded.
     *
     * `@Volatile` because it is installed on the main thread and read from the
     * service, the GPS callback, the sync scope and the crash handler.
     */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** Records one event. [detail] is scrubbed and capped before it is kept. */
    fun record(kind: EventKind, detail: String = "") {
        val s = sink ?: return
        s(line(nowMs(), kind, detail))
    }

    /** The line [record] hands the sink at [atMs]. */
    internal fun line(atMs: Long, kind: EventKind, detail: String): String {
        // Scrubbed before it is capped, so a cut can't leave the first digits
        // of a coordinate that the scrub would no longer recognise. Line breaks
        // become spaces: the file is one event per line.
        val kept = scrub(detail).replace(LINE_BREAK, " ").take(DETAIL_MAX_CHARS)
        val head = "${Instant.fromEpochMilliseconds(atMs)} ${kind.name}"
        return if (kept.isBlank()) head else "$head $kept"
    }

    /** Replaces every coordinate-shaped number in [text] with `#`. Integers and
     *  short decimals (speeds, distances, status codes) pass through. */
    fun scrub(text: String): String = text.replace(COORDINATE_LIKE, "#")

    /**
     * The lines worth keeping at [nowMs]: the ones from the last [KEEP_MS],
     * then as many of the newest as fit in [MAX_BYTES]. A line whose timestamp
     * doesn't parse (a write torn by a crash) is dropped. Order is kept.
     */
    fun trim(lines: List<String>, nowMs: Long): List<String> {
        val since = nowMs - KEEP_MS
        val recent = lines.filter { (timeOf(it) ?: return@filter false) >= since }
        var bytes = 0
        var first = recent.size
        while (first > 0) {
            // +1 for the newline the file puts after every line.
            val size = recent[first - 1].encodeToByteArray().size + 1
            if (bytes + size > MAX_BYTES) break
            bytes += size
            first--
        }
        return recent.subList(first, recent.size)
    }

    private fun timeOf(line: String): Long? {
        val stamp = line.substringBefore(' ')
        return runCatching { Instant.parse(stamp).toEpochMilliseconds() }.getOrNull()
    }
}
