package com.jellemax.detour.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the shared half of the #819 event log: the line format, the
 * coordinate scrub, the detail cap, the 24 h / byte-cap trim, and that an
 * uninstalled sink records nothing.
 */
class EventLogTest {

    private val now = 1_790_000_000_000L
    private val hour = 60 * 60 * 1000L

    @AfterTest
    fun clearSink() {
        EventLog.sink = null
    }

    @Test
    fun scrubHidesCoordinatesAndKeepsSpeeds() {
        assertEquals("#,#", EventLog.scrub("51.21934,4.40243"))
        assertEquals("speed=87 km/h", EventLog.scrub("speed=87 km/h"))
        assertEquals("at -# acc=12.5", EventLog.scrub("at -3.12345 acc=12.5"))
    }

    @Test
    fun lineIsIsoUtcKindDetail() {
        assertEquals(
            "2026-09-21T14:13:20Z SYNC_ERROR sync HTTP 502",
            EventLog.line(now, EventKind.SYNC_ERROR, "sync HTTP 502"),
        )
        assertEquals("2026-09-21T14:13:20Z APP_START", EventLog.line(now, EventKind.APP_START, ""))
    }

    @Test
    fun detailIsCappedAndKeptOnOneLine() {
        val line = EventLog.line(now, EventKind.TRIP_STATE, "x".repeat(500) + "\nmore")
        val detail = line.substringAfter("TRIP_STATE ")
        assertEquals(EventLog.DETAIL_MAX_CHARS, detail.length)
        assertTrue('\n' !in EventLog.line(now, EventKind.TRIP_STATE, "a\r\nb"))
    }

    @Test
    fun scrubRunsBeforeTheCapSoACutCannotLeakDigits() {
        // The coordinate straddles the cap: capped first, "51.219" would survive.
        val detail = "y".repeat(EventLog.DETAIL_MAX_CHARS - 6) + "51.21934"
        val line = EventLog.line(now, EventKind.GEOFENCE, detail)
        assertTrue("51.2" !in line, line)
    }

    @Test
    fun recordWithNoSinkIsANoOp() {
        EventLog.sink = null
        EventLog.record(EventKind.CRASH, "boom")
        val got = ArrayList<String>()
        EventLog.sink = { got += it }
        EventLog.record(EventKind.CRASH, "boom")
        assertEquals(1, got.size)
        assertTrue(got.single().endsWith(" CRASH boom"))
    }

    @Test
    fun trimDropsLinesOlderThanADay() {
        val old = EventLog.line(now - 25 * hour, EventKind.APP_START, "old")
        val fresh = EventLog.line(now - 1 * hour, EventKind.APP_START, "fresh")
        assertEquals(listOf(fresh), EventLog.trim(listOf(old, fresh), now))
    }

    @Test
    fun trimKeepsTheNewestLinesUnderTheByteCap() {
        val lines = (0 until 5000).map { EventLog.line(now - 5000 + it, EventKind.TRANSITION, "n=$it " + "z".repeat(80)) }
        val kept = EventLog.trim(lines, now)
        assertTrue(kept.sumOf { it.encodeToByteArray().size + 1 } <= EventLog.MAX_BYTES)
        assertEquals(lines.last(), kept.last())
        assertEquals(lines.subList(lines.size - kept.size, lines.size), kept)
        assertTrue(kept.size < lines.size)
    }

    @Test
    fun trimDropsATornLine() {
        val ok = EventLog.line(now, EventKind.APP_START, "")
        assertEquals(listOf(ok), EventLog.trim(listOf("2026-09-2", ok), now))
    }

    @Test
    fun syncErrorDetailIsStatusOrClassNeverMessage() {
        assertEquals("sync HTTP 503", SyncClient.syncErrorDetail(HttpStatusException(503, "", "https://x/sync?a=1")))
        assertEquals("sync AuthException", SyncClient.syncErrorDetail(AuthException("https://x?token=1")))
    }
}
