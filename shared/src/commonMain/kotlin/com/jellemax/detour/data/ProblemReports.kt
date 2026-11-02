package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.IOException

/** Why a report was sent: the app caught a crash, or the rider tapped "Something's wrong". */
enum class ProblemReportKind(val wire: String) { CRASH("crash"), MANUAL("manual") }

/**
 * One problem report (#811), exactly as it goes on the wire to
 * `POST /api/problem-reports` (#818). The report screen shows this object
 * verbatim, so what the rider reads is what gets sent: build it with
 * [ProblemReports.preview], never by hand.
 */
data class ProblemReport(
    val kind: ProblemReportKind,
    /** Null when the platform never said what it is; such a report is not sent. */
    val platform: DevicePlatform?,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val occurredAt: Long,
    val note: String?,
    val stackTrace: String?,
    val events: List<String>,
) {
    /** The #818 wire format. A null note or stack is left out, not sent as null. */
    fun toJson(platform: DevicePlatform): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        put("platform", platform.wire)
        put("appVersion", appVersion)
        put("osVersion", osVersion)
        put("deviceModel", deviceModel)
        put("occurredAt", occurredAt)
        note?.let { put("note", it) }
        stackTrace?.let { put("stackTrace", it) }
        putJsonArray("events") { events.forEach { add(it) } }
    }
}

/** What happened to a sent report. */
sealed interface SendResult {
    /** The server stored it; [id] is what a rider quotes if they follow up. */
    data class Sent(val id: String) : SendResult

    /** No server is set up, it predates #818 (a self-hoster on an older build),
     *  or this platform never said what it is ([BuildDefaults.configureDevice]). */
    data object Unsupported : SendResult

    /** It did not arrive: offline, a 5xx, a refused session. Worth trying again. */
    data object Failed : SendResult
}

/**
 * Builds and sends problem reports (#811). No UI here: the report screen
 * (#823) and the crash offer (#824) call [preview] and [send].
 *
 * Every field is capped to what the server accepts (`DetourLimits` in the
 * backend), so a long note or a deep stack is cut here instead of the whole
 * report bouncing with a 400 the rider can do nothing about.
 */
object ProblemReports {

    // The server's caps, DetourLimits.ProblemReport* in the backend.
    const val NOTE_MAX_CHARS = 2000
    const val STACK_TRACE_MAX_BYTES = 64 * 1024
    const val EVENTS_MAX_BYTES = 256 * 1024
    const val DEVICE_FIELD_MAX_CHARS = 128

    private const val PATH = "/problem-reports"

    /** What the OS or model reads when the platform didn't push one in: the
     *  server refuses a blank one, and a report beats no report. The platform
     *  has no such fallback, since the server takes only `android` or `ios`. */
    private const val UNKNOWN = "unknown"

    /**
     * Exactly what [send] would post. [events] are the platform's event-log
     * lines (Android: `EventSink.lines()`), oldest first. With a [crash], the
     * report is about that crash: its time, its stack and the version that
     * crashed, which may be older than the one running now.
     */
    fun preview(
        kind: ProblemReportKind,
        note: String?,
        crash: PendingCrash?,
        events: List<String>,
        nowMs: Long = nowMs(),
    ): ProblemReport = ProblemReport(
        kind = kind,
        platform = BuildDefaults.platform,
        appVersion = deviceField(crash?.appVersion ?: BuildDefaults.versionName),
        osVersion = deviceField(BuildDefaults.osVersion),
        deviceModel = deviceField(BuildDefaults.deviceModel),
        occurredAt = crash?.occurredAt ?: nowMs,
        note = note?.trim()?.take(NOTE_MAX_CHARS)?.takeIf { it.isNotEmpty() },
        stackTrace = crash?.stackTrace?.let { takeUtf8Bytes(it, STACK_TRACE_MAX_BYTES) },
        events = newestWithin(events, EVENTS_MAX_BYTES),
    )

    /**
     * Posts [report] to the rider's own server, with their bearer when signed
     * in and none when not: the rider who most needs to report a problem may be
     * the one who can't sign in.
     */
    suspend fun send(report: ProblemReport): SendResult {
        if (SyncClient.url() == null) return SendResult.Unsupported
        return send(report) { body -> Api.request("POST", PATH, body, auth = Auth.signedIn) }
    }

    /** [send] over [post], the seam a test fakes the server through. */
    internal suspend fun send(report: ProblemReport, post: suspend (JsonObject) -> String): SendResult {
        val platform = report.platform ?: return SendResult.Unsupported
        return try {
            val id = jsonObjectOf(post(report.toJson(platform))).optString("id")
            if (id.isEmpty()) SendResult.Failed else SendResult.Sent(id)
        } catch (e: HttpStatusException) {
            if (e.code == 404) SendResult.Unsupported else SendResult.Failed
        } catch (e: IOException) {
            // Offline, a timeout, or a session that ended mid-send (AuthException).
            SendResult.Failed
        } catch (e: IllegalArgumentException) {
            // A 2xx that isn't our JSON: a captive portal or a proxy's page.
            // SerializationException is one, and so is a body that parses as a
            // bare value rather than an object.
            SendResult.Failed
        }
    }

    private fun deviceField(value: String): String =
        value.trim().take(DEVICE_FIELD_MAX_CHARS).ifEmpty { UNKNOWN }

    /** The newest [lines] whose UTF-8 bytes fit in [maxBytes], oldest first. */
    internal fun newestWithin(lines: List<String>, maxBytes: Int): List<String> {
        var bytes = 0
        var first = lines.size
        while (first > 0) {
            val size = lines[first - 1].encodeToByteArray().size
            if (bytes + size > maxBytes) break
            bytes += size
            first--
        }
        return lines.subList(first, lines.size)
    }
}

/**
 * The longest prefix of [text] that is at most [maxBytes] in UTF-8, cut on a
 * character boundary. A stack trace is read top down, so the top is what stays.
 */
internal fun takeUtf8Bytes(text: String, maxBytes: Int): String {
    if (text.encodeToByteArray().size <= maxBytes) return text
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val step = if (text[end].isHighSurrogate() && end + 1 < text.length) 2 else 1
        val size = text.substring(end, end + step).encodeToByteArray().size
        if (bytes + size > maxBytes) break
        bytes += size
        end += step
    }
    return text.substring(0, end)
}
