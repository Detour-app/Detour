package com.jellemax.detour.perf

import com.jellemax.detour.data.Perf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [PerfLog] and [PerfAggregator] — everything the #84 sink decides
 * before it touches a file, pulled out here because the file half needs a
 * device and this half is where the answers can actually be got wrong.
 *
 * Two of the fifteen instrumented functions fire per GPS fix and per drawn
 * frame. Writing a row each would bury the series in its own noise, so those
 * aggregate — and the aggregation has to keep duration paired with the size it
 * ran over, or it destroys the one thing #84 asks for.
 */
class PerfLogTest {

    private fun sample(
        label: String = "TripStore.save",
        us: Long = 91_234,
        sizes: List<Pair<String, Int>> = listOf("trips" to 61),
    ) = Perf.Sample(label, us, sizes)

    @Test
    fun `a row carries the label, the duration and every covariate`() {
        val row = PerfLog.row(sample(), atMs = 1_700_000_000_000)
        assertEquals(
            """{"t":1700000000000,"l":"TripStore.save","us":91234,"n":{"trips":61}}""",
            row,
        )
    }

    @Test
    fun `a row with two covariates keeps both, in order`() {
        val row = PerfLog.row(
            sample(label = "Coverage.compute", us = 412, sizes = listOf("points" to 4210, "municipalities" to 7)),
            atMs = 1,
        )
        assertEquals(
            """{"t":1,"l":"Coverage.compute","us":412,"n":{"points":4210,"municipalities":7}}""",
            row,
        )
    }

    @Test
    fun `a row with no covariate is still valid json`() {
        assertEquals(
            """{"t":1,"l":"x","us":5,"n":{}}""",
            PerfLog.row(sample(label = "x", us = 5, sizes = emptyList()), atMs = 1),
        )
    }

    @Test
    fun `the per-fix and per-frame targets are the hot ones`() {
        assertTrue(PerfLog.isHot("FogView.onDraw"))
        assertTrue(PerfLog.isHot("MunicipalityStore.needsLookup"))
        assertFalse(PerfLog.isHot("TripStore.save"))
        assertFalse(PerfLog.isHot("Coverage.compute"))
    }

    /** Power-of-two buckets, so a doubling of the data is one bucket step and
     *  the curve survives aggregation instead of collapsing to one number. */
    @Test
    fun `a covariate buckets down to the power of two below it`() {
        assertEquals(0, PerfLog.bucket(0))
        assertEquals(1, PerfLog.bucket(1))
        assertEquals(2, PerfLog.bucket(3))
        assertEquals(1024, PerfLog.bucket(2000))
        assertEquals(2048, PerfLog.bucket(2048))
    }

    @Test
    fun `aggregated samples in one bucket collapse to a count, a total and a max`() {
        val agg = PerfAggregator()
        agg.add(sample(label = "FogView.onDraw", us = 400, sizes = listOf("points" to 1100)))
        agg.add(sample(label = "FogView.onDraw", us = 1200, sizes = listOf("points" to 1900)))
        agg.add(sample(label = "FogView.onDraw", us = 800, sizes = listOf("points" to 1030)))
        assertEquals(
            listOf("""{"t":9,"l":"FogView.onDraw","count":3,"totalUs":2400,"maxUs":1200,"n":{"points":1024}}"""),
            agg.drain(atMs = 9),
        )
    }

    /** The whole point of bucketing rather than averaging: 1k points and 8k
     *  points are the two ends of the curve and must not be folded together. */
    @Test
    fun `samples in different buckets stay separate rows`() {
        val agg = PerfAggregator()
        agg.add(sample(label = "FogView.onDraw", us = 400, sizes = listOf("points" to 1100)))
        agg.add(sample(label = "FogView.onDraw", us = 9000, sizes = listOf("points" to 9000)))
        val rows = agg.drain(atMs = 9)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.contains(""""n":{"points":1024}""") && it.contains(""""count":1""") })
        assertTrue(rows.any { it.contains(""""n":{"points":8192}""") && it.contains(""""maxUs":9000""") })
    }

    @Test
    fun `draining twice does not report the same samples again`() {
        val agg = PerfAggregator()
        agg.add(sample(label = "FogView.onDraw"))
        assertEquals(1, agg.drain(atMs = 1).size)
        assertEquals(emptyList<String>(), agg.drain(atMs = 2))
    }

    /** A ring, not a growing file: this ships in release builds, so it cannot
     *  be allowed to consume storage without bound. */
    @Test
    fun `trimming drops the oldest lines until the cap is met`() {
        val lines = (1..10).map { "line$it" } // 6 bytes + newline each
        val kept = PerfLog.trimmed(lines, maxBytes = 30)
        assertEquals(listOf("line7", "line8", "line9", "line10"), kept.takeLast(4))
        assertTrue(kept.sumOf { it.length + 1 } <= 30)
    }

    @Test
    fun `trimming leaves a file already under the cap alone`() {
        val lines = listOf("a", "b")
        assertEquals(lines, PerfLog.trimmed(lines, maxBytes = 1000))
    }

    /** The flush cadence. Samples are buffered in memory and written by one
     *  background thread, because two of the labels arrive on the GPS callback
     *  and the draw pass and neither may touch a file. */
    @Test
    fun `nothing buffered means no flush`() {
        assertFalse(PerfLog.shouldFlush(nowMs = 100_000, lastFlushMs = 0, buffered = 0))
    }

    @Test
    fun `buffered rows wait for the interval to elapse`() {
        assertFalse(PerfLog.shouldFlush(nowMs = 5_000, lastFlushMs = 0, buffered = 3))
        assertTrue(PerfLog.shouldFlush(nowMs = 10_000, lastFlushMs = 0, buffered = 3))
    }

    /** A burst must not be allowed to grow the buffer without bound just
     *  because the interval has not come round yet. */
    @Test
    fun `a full buffer flushes before the interval`() {
        assertTrue(PerfLog.shouldFlush(nowMs = 1, lastFlushMs = 0, buffered = 500))
    }

    /**
     * Regression: the flush decision counts buffered *rows*, and a hot label
     * never becomes a row. A session that only ever panned the map — fog draws,
     * nothing else — buffered forever and wrote nothing, because rows.size
     * stayed 0 and `shouldFlush` requires something buffered.
     */
    @Test
    fun `pending aggregate buckets count as buffered`() {
        val agg = PerfAggregator()
        assertEquals(0, agg.size)
        agg.add(sample(label = "FogView.onDraw", sizes = listOf("points" to 1100)))
        agg.add(sample(label = "FogView.onDraw", sizes = listOf("points" to 1200)))
        // Same bucket, so one pending row — but not zero, which is the bug.
        assertEquals(1, agg.size)
        assertTrue(PerfLog.shouldFlush(nowMs = 10_000, lastFlushMs = 0, buffered = agg.size))
        agg.drain(atMs = 1)
        assertEquals(0, agg.size)
    }
}
