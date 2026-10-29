package com.jellemax.detour.diag

import com.jellemax.detour.data.EventKind
import com.jellemax.detour.data.EventLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Covers the file half of the #819 event log: lines recorded from several
 * threads at once all land, whole and unmixed.
 */
class EventWriterTest {

    @Test
    fun concurrentRecordsFromFourThreadsLoseNoLines() {
        val file = File.createTempFile("events", ".log").apply { deleteOnExit() }
        val writer = EventWriter(file)
        writer.clear()
        EventLog.sink = writer::accept
        val perThread = 500
        val start = CountDownLatch(1)
        val threads = (0 until 4).map { t ->
            thread {
                start.await()
                repeat(perThread) { i -> EventLog.record(EventKind.TRIP_STATE, "t=$t i=$i") }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        EventLog.sink = null
        writer.flushBlocking()

        val lines = writer.lines()
        assertEquals(4 * perThread, lines.size)
        for (t in 0 until 4) {
            val mine = lines.map { it.substringAfter("TRIP_STATE ") }
                .filter { it.startsWith("t=$t ") }
                .map { it.substringAfter("i=").toInt() }
            assertEquals((0 until perThread).toList(), mine)
        }
    }
}
