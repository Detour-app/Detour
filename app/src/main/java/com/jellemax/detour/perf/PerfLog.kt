package com.jellemax.detour.perf

import com.jellemax.detour.data.Perf
import java.time.Instant

/**
 * How a [Perf.Sample] becomes a line, and which samples are too frequent to get
 * a line of their own. #84.
 *
 * Pure, and separate from [PerfSink] for the reason every other policy object in
 * `app/` is (see `ModeSwipePolicy`): the file half needs a device, and this half
 * is where the answers can be wrong. Rows are hand-built rather than run through
 * `org.json`, whose android.jar stub throws in a unit test.
 */
object PerfLog {

    /**
     * Labels that fire per GPS fix or per drawn frame. A line each would bury
     * the series under its own volume — and cost more than the call being
     * measured — so these go through [PerfAggregator] instead.
     */
    private val HOT = setOf(
        "MunicipalityStore.needsLookup", // TripTrackingService.kt:1150, per fix
        "FogView.onDraw",                // per frame while the map is panning
    )

    fun isHot(label: String): Boolean = label in HOT

    /** How long buffered rows may wait before being written. */
    const val FLUSH_INTERVAL_MS = 10_000L

    /** Rows buffered before the interval is overridden, so a burst cannot grow
     *  the buffer without bound. */
    const val FLUSH_ROWS = 400

    /**
     * Whether the writer thread should be woken.
     *
     * Samples are buffered rather than written where they are recorded: the two
     * hot labels arrive on the GPS callback and on the draw pass, and neither
     * may touch a file. So the cadence is a decision, and it lives here where it
     * can be tested.
     */
    fun shouldFlush(nowMs: Long, lastFlushMs: Long, buffered: Int): Boolean =
        buffered > 0 && (buffered >= FLUSH_ROWS || nowMs - lastFlushMs >= FLUSH_INTERVAL_MS)

    /**
     * The power of two at or below [value].
     *
     * Aggregation has to keep duration paired with the size it ran over, or it
     * throws away the covariate the whole issue is about. Bucketing by doubling
     * is the coarsest grouping that still shows a curve: each step is twice the
     * data, so a superlinear term shows as the per-call cost climbing faster
     * than the bucket label does.
     */
    fun bucket(value: Int): Int {
        if (value <= 0) return 0
        var b = 1
        while (b <= value / 2) b *= 2
        return b
    }

    /**
     * One measured call: `{"t":…,"v":…,"l":…,"us":…,"n":{…}}`.
     *
     * Both the instant and the build are on every row, not in a header: the ring
     * drops the oldest lines, so a header would be the first thing trimmed away
     * and every surviving row would be undated and unattributed. The build
     * matters because the series exists to be read across versions, and a number
     * from 1.89.0 beside one from 1.91.0 means nothing without knowing which is
     * which.
     */
    fun row(sample: Perf.Sample, atMs: Long, version: String): String =
        """{"t":"${instant(atMs)}","v":"$version",""" +
            """"l":"${sample.label}","us":${sample.durationUs},"n":${sizes(sample.sizes)}}"""

    /**
     * ISO-8601 UTC, which sorts lexicographically as well as epoch millis did
     * and can be read by a person, which epoch millis cannot. `Instant.toString`
     * drops a `.000` fraction, so a whole second renders as `…:20Z`.
     */
    private fun instant(atMs: Long): String = Instant.ofEpochMilli(atMs).toString()

    /**
     * A bucket's worth of hot calls. `n` holds the bucket floor rather than a
     * real size, and `count` is what distinguishes an aggregated row from a
     * [row] when reading the file back.
     */
    internal fun aggregateRow(key: Key, agg: Agg, atMs: Long, version: String): String =
        """{"t":"${instant(atMs)}","v":"$version","l":"${key.label}","count":${agg.count},""" +
            """"totalUs":${agg.totalUs},"maxUs":${agg.maxUs},"n":${sizes(key.buckets)}}"""

    private fun sizes(pairs: List<Pair<String, Int>>): String =
        pairs.joinToString(separator = ",", prefix = "{", postfix = "}") { """"${it.first}":${it.second}""" }

    /**
     * The last lines of [lines] that fit in [maxBytes], counting one byte per
     * character plus a newline.
     *
     * A ring rather than a growing file: this ships in release builds, and a
     * diagnostic that fills a rider's storage is worse than no diagnostic.
     */
    fun trimmed(lines: List<String>, maxBytes: Int): List<String> {
        var bytes = 0
        var from = lines.size
        for (i in lines.indices.reversed()) {
            val size = lines[i].length + 1
            if (bytes + size > maxBytes) break
            bytes += size
            from = i
        }
        return if (from == 0) lines else lines.subList(from, lines.size)
    }

    /** A hot label at one covariate bucket — the grain aggregation happens at. */
    internal data class Key(val label: String, val buckets: List<Pair<String, Int>>)

    internal class Agg(var count: Int = 0, var totalUs: Long = 0, var maxUs: Long = 0)
}

/**
 * Collects the hot labels between flushes.
 *
 * Not thread-safe by itself — [PerfSink] holds the lock, because the samples
 * arrive from the draw pass and the GPS callback on different threads.
 */
class PerfAggregator {

    private val buckets = LinkedHashMap<PerfLog.Key, PerfLog.Agg>()

    /** Rows this would produce if drained now. The flush decision counts
     *  buffered rows, and a hot sample never becomes one on its own — without
     *  this, a session that only panned the map buffered forever. */
    val size: Int get() = buckets.size

    fun add(sample: Perf.Sample) {
        val key = PerfLog.Key(
            sample.label,
            sample.sizes.map { it.first to PerfLog.bucket(it.second) },
        )
        val agg = buckets.getOrPut(key) { PerfLog.Agg() }
        agg.count++
        agg.totalUs += sample.durationUs
        if (sample.durationUs > agg.maxUs) agg.maxUs = sample.durationUs
    }

    /** Everything collected since the last drain, as rows. Empties the buckets. */
    fun drain(atMs: Long, version: String): List<String> {
        if (buckets.isEmpty()) return emptyList()
        val rows = buckets.map { (key, agg) -> PerfLog.aggregateRow(key, agg, atMs, version) }
        buckets.clear()
        return rows
    }
}
