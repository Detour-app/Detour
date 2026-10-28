package com.jellemax.detour.data

import kotlinx.coroutines.runBlocking
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The #821 client half of problem reports: the #818 wire shape, what a preview holds,
 *  the pending crash file, and how a server's answer maps to a [SendResult]. */
class ProblemReportsTest {

    @BeforeTest
    fun device() {
        BuildDefaults.configure(versionName = "3.91.0")
        BuildDefaults.configureDevice(
            platform = DevicePlatform.ANDROID,
            osVersion = "Android 15 (API 35)",
            deviceModel = "samsung SM-S928B",
        )
    }

    @AfterTest
    fun reset() {
        BuildDefaults.configure()
        BuildDefaults.configureDevice()
    }

    private val crash = PendingCrash(
        occurredAt = 1_791_100_800_000,
        stackTrace = "java.lang.IllegalStateException\n\tat com.jellemax.detour.Foo.bar(Foo.kt:12)",
        appVersion = "3.90.0",
    )

    @Test
    fun aReportSerialisesToTheServerContract() {
        val report = ProblemReports.preview(ProblemReportKind.CRASH, "it froze", crash, listOf("a", "b"))

        assertEquals(
            """{"kind":"crash","platform":"android","appVersion":"3.90.0","osVersion":"Android 15 (API 35)",""" +
                """"deviceModel":"samsung SM-S928B","occurredAt":1791100800000,"note":"it froze",""" +
                """"stackTrace":"java.lang.IllegalStateException\n\tat com.jellemax.detour.Foo.bar(Foo.kt:12)",""" +
                """"events":["a","b"]}""",
            report.toJson(DevicePlatform.ANDROID).toString(),
        )
    }

    @Test
    fun aManualReportWithNoNoteLeavesNoteAndStackOut() {
        val report = ProblemReports.preview(ProblemReportKind.MANUAL, "   ", null, emptyList(), nowMs = 42)

        assertEquals(
            """{"kind":"manual","platform":"android","appVersion":"3.91.0","osVersion":"Android 15 (API 35)",""" +
                """"deviceModel":"samsung SM-S928B","occurredAt":42,"events":[]}""",
            report.toJson(DevicePlatform.ANDROID).toString(),
        )
    }

    @Test
    fun thePreviewCarriesTheEventLinesAndTheNote() {
        val events = listOf(
            "2026-10-28T08:00:00Z SERVICE_START monitor",
            "2026-10-28T08:01:00Z TRIP_STATE begin",
        )

        val report = ProblemReports.preview(ProblemReportKind.MANUAL, " map went blank ", null, events, nowMs = 7)

        assertEquals(events, report.events)
        assertEquals("map went blank", report.note)
        assertEquals(7, report.occurredAt)
        assertNull(report.stackTrace)
    }

    @Test
    fun fieldsAreCutToWhatTheServerAccepts() {
        val events = List(300) { "x".repeat(1023) } // 300 KiB, over the 256 KiB cap
        val report = ProblemReports.preview(
            ProblemReportKind.CRASH,
            note = "n".repeat(3000),
            crash = crash.copy(stackTrace = "é".repeat(40_000)), // 80,000 UTF-8 bytes
            events = events,
        )

        assertEquals(ProblemReports.NOTE_MAX_CHARS, report.note?.length)
        assertEquals(ProblemReports.STACK_TRACE_MAX_BYTES, report.stackTrace?.encodeToByteArray()?.size)
        assertEquals(256, report.events.size)
        assertEquals(events.takeLast(256), report.events)
    }

    @Test
    fun anUnconfiguredDeviceReadsUnknownButANamelessPlatformIsNotSent() = runBlocking {
        reset()

        val report = ProblemReports.preview(ProblemReportKind.MANUAL, null, null, emptyList(), nowMs = 1)
        var posted = false

        assertEquals("unknown", report.osVersion)
        assertEquals("unknown", report.deviceModel)
        assertEquals("0", report.appVersion)
        assertNull(report.platform)
        // The server takes only android or ios, so a guess would be refused
        // with a 400 on every try; not posting at all says so honestly.
        assertEquals(SendResult.Unsupported, ProblemReports.send(report) { posted = true; """{"id":"x"}""" })
        assertEquals(false, posted)
    }

    @Test
    fun theCrashFileRoundTripsAndKeepsOnlyTheNewest() {
        val fs = FakeFileSystem()
        val path = "/files/pending-crash.json".toPath()
        fs.createDirectories(path.parent!!)

        assertNull(CrashStore.read(fs, path))
        CrashStore.write(fs, path, crash)
        assertEquals(crash, CrashStore.read(fs, path))

        val newer = crash.copy(occurredAt = crash.occurredAt + 1, stackTrace = "java.lang.OutOfMemoryError")
        CrashStore.write(fs, path, newer)
        assertEquals(newer, CrashStore.read(fs, path))
    }

    @Test
    fun anUnreadableCrashFileReadsAsNoCrash() {
        val fs = FakeFileSystem()
        val path = "/pending-crash.json".toPath()
        fs.write(path) { writeUtf8("{\"occurredAt\":") } // torn by the dying process

        assertNull(CrashStore.read(fs, path))
    }

    @Test
    fun eachServerAnswerMapsToItsResult() = runBlocking {
        val report = ProblemReports.preview(ProblemReportKind.MANUAL, null, null, emptyList(), nowMs = 1)
        val id = "0b9e1c4a-5d3f-4c2a-9a51-0e7f8f6d2b11"

        assertEquals(SendResult.Sent(id), ProblemReports.send(report) { """{"id":"$id"}""" })
        assertEquals(
            SendResult.Unsupported,
            ProblemReports.send(report) { throw HttpStatusException(404, "") },
        )
        assertEquals(SendResult.Failed, ProblemReports.send(report) { throw HttpStatusException(500, "") })
        assertEquals(SendResult.Failed, ProblemReports.send(report) { throw IOException("offline") })
        assertEquals(SendResult.Failed, ProblemReports.send(report) { "<html>captive portal</html>" })
    }

    @Test
    fun theReportIsWhatGoesOnTheWire() = runBlocking {
        val report = ProblemReports.preview(ProblemReportKind.MANUAL, "hi", null, listOf("e"), nowMs = 1)
        var posted: String? = null

        ProblemReports.send(report) { body ->
            posted = body.toString()
            """{"id":"x"}"""
        }

        assertEquals(report.toJson(DevicePlatform.ANDROID).toString(), posted)
    }
}
