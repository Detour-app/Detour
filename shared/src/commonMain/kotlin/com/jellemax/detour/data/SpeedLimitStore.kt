package com.jellemax.detour.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.Volatile

/** One cached bbox fetch: the box itself (rounded so nearby prefetches share a cache entry),
 *  when it was fetched, and the result.
 *
 *  internal, not private, so commonTest can feed/inspect it directly — same reason
 *  [CachedTile] is: [SpeedLimitStore.load]/[SpeedLimitStore.save] need a real account directory
 *  and cannot be exercised here. */
internal data class CachedSpeedLimitTile(val key: String, val fetchedAtMs: Long, val ways: List<RoadRoulette.SpeedLimitWay>)

/**
 * Disk cache for backend-sourced speed-limit-way data, so the ambient sign survives a process
 * restart and a backend outage — see issue #379. Mirrors [SpeedCameraStore]'s shape exactly,
 * one JSON file and an in-memory `@Volatile` cache in front of it, keyed by a rounded bbox
 * rather than [RoadRoulette.speedLimitWays]'s own prefetch tile.
 */
object SpeedLimitStore {

    private const val FILE_NAME = "speed_limit_ways.json"

    /** Same reasoning and value as [SpeedCameraStore.TTL_MS]: a loose disk fallback for when the
     *  network fetch itself fails, not the primary freshness control. */
    internal const val TTL_MS = 24L * 60 * 60 * 1000

    /** Same rounding as [SpeedCameraStore]'s own key — ~0.01° (~1 km) — so a slightly different
     *  prefetch centre still hits the same cached tile. */
    private fun key(center: LatLon, radiusMeters: Double): String =
        "${(center.lat * 100).toInt()}:${(center.lon * 100).toInt()}:${radiusMeters.toInt()}"

    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    @Volatile internal var cache: List<CachedSpeedLimitTile>? = null

    /** Serialises [save]'s read-modify-write — same reason and shape as
     *  [SpeedCameraStore.writeLock]: [RoadRoulette.speedLimitWays] can be driven concurrently by
     *  the phone screen and the car surface at once. */
    private val writeLock = Mutex()

    private fun loadAll(): List<CachedSpeedLimitTile> {
        cache?.let { return it }
        val f = accountFile(FILE_NAME)
        val loaded = if (!f.exists()) emptyList() else parseAll(f.readText())
        cache = loaded
        return loaded
    }

    /** [loadAll]'s pure half: raw file text to tiles, with anything unparseable read back as no
     *  tiles at all rather than crashing — a corrupt `speed_limit_ways.json` must not take the
     *  ambient sign down with it. */
    internal fun parseAll(text: String): List<CachedSpeedLimitTile> = try {
        jsonArrayOf(text).objects().mapNotNull { parseTile(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun load(center: LatLon, radiusMeters: Double): List<RoadRoulette.SpeedLimitWay>? {
        val k = key(center, radiusMeters)
        val tile = loadAll().firstOrNull { it.key == k } ?: return null
        return tile.ways.takeIf { fresh(tile, nowMs()) }
    }

    /** Whether [tile] is still within [TTL_MS] of [nowMs] — same check [SpeedCameraStore.fresh]
     *  applies to a disk hit, split out for the same reason (#367). */
    internal fun fresh(tile: CachedSpeedLimitTile, nowMs: Long): Boolean = nowMs - tile.fetchedAtMs <= TTL_MS

    /** `suspend`, taking [writeLock], for the same reason [SpeedCameraStore.save] is. */
    suspend fun save(center: LatLon, radiusMeters: Double, ways: List<RoadRoulette.SpeedLimitWay>): Unit = writeLock.withLock {
        val tile = CachedSpeedLimitTile(key(center, radiusMeters), nowMs(), ways)
        val next = withTile(loadAll(), tile, nowMs())
        accountFile(FILE_NAME).writeText(serialise(next))
        cache = next
    }

    /** [save]'s pure half: the tile list once [tile] lands — same replace-or-append-then-prune
     *  shape as [SpeedCameraStore.withTile], for the same reason. */
    internal fun withTile(existing: List<CachedSpeedLimitTile>, tile: CachedSpeedLimitTile, nowMs: Long): List<CachedSpeedLimitTile> =
        existing.filterNot { it.key == tile.key || nowMs - it.fetchedAtMs > TTL_MS } + tile

    fun reset() {
        cache = null
    }

    private fun parseTile(o: JsonObject): CachedSpeedLimitTile? {
        val key = o.optString("key").takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = o.optLong("fetchedAtMs", 0L)
        val ways = (o.optArray("ways") ?: return null).objects().mapNotNull {
            val kmh = it.optDouble("kmh").takeIf { v -> !v.isNaN() } ?: return@mapNotNull null
            val points = (it.optArray("points") ?: JsonArrayEmpty).arrays().map { p -> LatLon(p.optDouble(0), p.optDouble(1)) }
            if (points.size < 2) null else RoadRoulette.SpeedLimitWay(kmh, points)
        }
        return CachedSpeedLimitTile(key, fetchedAt, ways)
    }

    /** internal, not private, so the disk-cache tests can round-trip a tile through this and
     *  [parseAll] without a real account directory. */
    internal fun serialise(tiles: List<CachedSpeedLimitTile>): String = buildJsonArray {
        for (t in tiles) addJsonObject {
            put("key", t.key)
            put("fetchedAtMs", t.fetchedAtMs)
            putJsonArray("ways") {
                for (w in t.ways) addJsonObject {
                    put("kmh", w.kmh)
                    putJsonArray("points") { for (p in w.points) addJsonArray { add(p.lat); add(p.lon) } }
                }
            }
        }
    }.string()
}
