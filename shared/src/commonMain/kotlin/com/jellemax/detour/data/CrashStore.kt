package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path

/** A crash the app caught and hasn't reported or dismissed yet (#811). */
data class PendingCrash(val occurredAt: Long, val stackTrace: String, val appVersion: String)

/**
 * Holds at most one crash between the process that died and the next start,
 * which offers it to the rider (#824). The newest crash wins: a crash loop
 * leaves the last one, which is the one the rider just saw.
 *
 * `pending-crash.json` is a device file at the `files/` root: a crash belongs
 * to the phone, not to whoever is signed in, and the include-only backup
 * allowlists leave the root out.
 */
object CrashStore {

    private const val FILE = "pending-crash.json"

    /**
     * Stores the crash, replacing any earlier one. Synchronous and never
     * throws, because it runs inside an uncaught-exception handler: anything it
     * threw there would replace the crash it is trying to save.
     */
    fun writeBlocking(occurredAt: Long, stackTrace: String, appVersion: String) {
        try {
            write(fileSystem, deviceFile(FILE), PendingCrash(occurredAt, stackTrace, appVersion))
        } catch (e: Throwable) {
            // The process is dying anyway; losing the report is the lesser harm.
        }
    }

    /** The stored crash, or null when there is none or it can't be read. */
    fun pending(): PendingCrash? = read(fileSystem, deviceFile(FILE))

    /** Forgets the stored crash, once it is [SendResult.Sent] or the rider dismissed it. */
    fun clear() {
        deviceFile(FILE).deleteIfExists()
    }

    internal fun write(fs: FileSystem, path: Path, crash: PendingCrash) {
        val body = buildJsonObject {
            put("occurredAt", crash.occurredAt)
            put("stackTrace", takeUtf8Bytes(crash.stackTrace, ProblemReports.STACK_TRACE_MAX_BYTES))
            put("appVersion", crash.appVersion)
        }
        fs.writeAtomically(path, body.toString())
    }

    internal fun read(fs: FileSystem, path: Path): PendingCrash? = runCatching {
        if (!fs.exists(path)) return null
        val o = jsonObjectOf(fs.read(path) { readUtf8() })
        PendingCrash(o.optLong("occurredAt"), o.optString("stackTrace"), o.optString("appVersion"))
            .takeIf { it.occurredAt > 0 && it.stackTrace.isNotEmpty() }
    }.getOrNull()
}
