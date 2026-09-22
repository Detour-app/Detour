package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.accountFile
import com.jellemax.detour.data.arrays
import com.jellemax.detour.data.exists
import com.jellemax.detour.data.jsonArrayOf
import com.jellemax.detour.data.nowMs
import com.jellemax.detour.data.objects
import com.jellemax.detour.data.optArray
import com.jellemax.detour.data.optDouble
import com.jellemax.detour.data.optLong
import com.jellemax.detour.data.optString
import com.jellemax.detour.data.readText
import com.jellemax.detour.data.string
import com.jellemax.detour.data.writeText
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

/** One cached bbox fetch — same shape as `CachedSpeedLimitTile`, for the same reason.
 *  internal, not private, so commonTest can feed/inspect it directly. */
internal data class CachedRoadTypeTile(
    val key: String,
    val fetchedAtMs: Long,
    val ways: List<RoadTypeTracker.ClassifiedWay>,
)

/**
 * Disk cache for backend-sourced drivable-road data, so the road-type mix survives a process
 * restart and a backend outage — see issue #380. Mirrors `SpeedLimitStore`'s shape exactly, one
 * JSON file and an in-memory `@Volatile` cache in front of it, keyed by a rounded bbox rather
 * than `RoadTypeTracker`'s own prefetch tile.
 */
object RoadTypeStore {

    private const val FILE_NAME = "road_type_ways.json"

    /** Same reasoning and value as `SpeedCameraStore.TTL_MS`: a loose disk fallback for when the
     *  network fetch itself fails, not the primary freshness control. */
    internal const val TTL_MS = 24L * 60 * 60 * 1000

    /** Same rounding as `SpeedLimitStore`'s own key — ~0.01° (~1 km) — so a slightly different
     *  prefetch centre still hits the same cached tile. */
    private fun key(center: LatLon, radiusMeters: Double): String =
        "${(center.lat * 100).toInt()}:${(center.lon * 100).toInt()}:${radiusMeters.toInt()}"

    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    @Volatile internal var cache: List<CachedRoadTypeTile>? = null

    /** Serialises [save]'s read-modify-write — same reason and shape as
     *  `SpeedLimitStore.writeLock`. */
    private val writeLock = Mutex()

    private fun loadAll(): List<CachedRoadTypeTile> {
        cache?.let { return it }
        val f = accountFile(FILE_NAME)
        val loaded = if (!f.exists()) emptyList() else parseAll(f.readText())
        cache = loaded
        return loaded
    }

    /** [loadAll]'s pure half: raw file text to tiles, with anything unparseable read back as no
     *  tiles at all rather than crashing — a corrupt `road_type_ways.json` must not take the
     *  road-type mix down with it. */
    internal fun parseAll(text: String): List<CachedRoadTypeTile> = try {
        jsonArrayOf(text).objects().mapNotNull { parseTile(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun load(center: LatLon, radiusMeters: Double): List<RoadTypeTracker.ClassifiedWay>? {
        val k = key(center, radiusMeters)
        val tile = loadAll().firstOrNull { it.key == k } ?: return null
        return tile.ways.takeIf { fresh(tile, nowMs()) }
    }

    /** Whether [tile] is still within [TTL_MS] of [nowMs] — same check `SpeedLimitStore.fresh`
     *  applies to a disk hit. */
    internal fun fresh(tile: CachedRoadTypeTile, nowMs: Long): Boolean = nowMs - tile.fetchedAtMs <= TTL_MS

    /** `suspend`, taking [writeLock], for the same reason `SpeedLimitStore.save` is. */
    suspend fun save(center: LatLon, radiusMeters: Double, ways: List<RoadTypeTracker.ClassifiedWay>): Unit = writeLock.withLock {
        val tile = CachedRoadTypeTile(key(center, radiusMeters), nowMs(), ways)
        val next = withTile(loadAll(), tile, nowMs())
        accountFile(FILE_NAME).writeText(serialise(next))
        cache = next
    }

    /** [save]'s pure half: the tile list once [tile] lands — same replace-or-append-then-prune
     *  shape as `SpeedLimitStore.withTile`. */
    internal fun withTile(existing: List<CachedRoadTypeTile>, tile: CachedRoadTypeTile, nowMs: Long): List<CachedRoadTypeTile> =
        existing.filterNot { it.key == tile.key || nowMs - it.fetchedAtMs > TTL_MS } + tile

    fun reset() {
        cache = null
    }

    private fun parseTile(o: JsonObject): CachedRoadTypeTile? {
        val key = o.optString("key").takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = o.optLong("fetchedAtMs", 0L)
        val ways = (o.optArray("ways") ?: return null).objects().mapNotNull {
            val clsName = it.optString("cls").takeIf { c -> c.isNotBlank() } ?: return@mapNotNull null
            val cls = try {
                HighwayClass.valueOf(clsName)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            val points = (it.optArray("points") ?: return@mapNotNull null).arrays().map { p -> LatLon(p.optDouble(0), p.optDouble(1)) }
            if (points.size < 2) null else RoadTypeTracker.ClassifiedWay(cls, points)
        }
        return CachedRoadTypeTile(key, fetchedAt, ways)
    }

    /** internal, not private, so the disk-cache tests can round-trip a tile through this and
     *  [parseAll] without a real account directory. */
    internal fun serialise(tiles: List<CachedRoadTypeTile>): String = buildJsonArray {
        for (t in tiles) addJsonObject {
            put("key", t.key)
            put("fetchedAtMs", t.fetchedAtMs)
            putJsonArray("ways") {
                for (w in t.ways) addJsonObject {
                    put("cls", w.highwayClass.name)
                    putJsonArray("points") { for (p in w.points) addJsonArray { add(p.lat); add(p.lon) } }
                }
            }
        }
    }.string()
}
