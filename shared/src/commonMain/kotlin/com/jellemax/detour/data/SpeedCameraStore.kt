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
 *  internal, not private, so commonTest can feed/inspect it directly — see [parseSection]'s
 *  comment for why: [SpeedCameraStore.load]/[SpeedCameraStore.save] need a real account
 *  directory and cannot be exercised here, so the pure functions around this type are what
 *  the disk-cache tests actually drive. */
internal data class CachedTile(val key: String, val fetchedAtMs: Long, val result: SpeedCameras.Result)

/**
 * Disk cache for backend-sourced camera/section data, so markers survive a process restart and a
 * backend outage — see issue #303. Mirrors [MunicipalityStore]'s shape: one JSON file, an
 * in-memory `@Volatile` cache in front of it, [load]/[save]/[reset]. Keyed by a rounded bbox
 * rather than a boundary id, since the thing being cached is [SpeedCameras.near]'s prefetch tile
 * rather than an administrative area.
 */
object SpeedCameraStore {

    private const val FILE_NAME = "speed_cameras.json"

    /** Cached tiles older than this are treated as a miss, so a stale outage-era cache doesn't
     *  silently keep serving cameras a source has since retired. Loose on purpose: this is a
     *  disk fallback for when the network fetch itself fails, not the primary freshness control.
     *
     *  internal, not private, so the disk-cache tests can compute a fixture tile's age against
     *  the same constant [save] and [load] actually use. */
    internal const val TTL_MS = 24L * 60 * 60 * 1000

    /** Rounds to ~0.01° (~1 km) so a slightly different prefetch centre still hits the same
     *  cached tile — the same reasoning [MunicipalityStore]'s own miss bucket uses. */
    private fun key(center: LatLon, radiusMeters: Double): String =
        "${(center.lat * 100).toInt()}:${(center.lon * 100).toInt()}:${radiusMeters.toInt()}"

    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    @Volatile internal var cache: List<CachedTile>? = null

    /** Serialises [save]'s read-modify-write, the same reason and shape as
     *  [MunicipalityStore.writeLock]: [SpeedCameras.near] can be driven concurrently by
     *  `com.jellemax.detour.ui.MapHazardPrefetch` (phone screen on) and the car surface's
     *  `NavScreen` (Android Auto connected) at once, and an interleaved read-modify-write there
     *  would drop whichever tile lost the race rather than keeping both. */
    private val writeLock = Mutex()

    private fun loadAll(): List<CachedTile> {
        cache?.let { return it }
        val f = accountFile(FILE_NAME)
        val loaded = if (!f.exists()) emptyList() else parseAll(f.readText())
        cache = loaded
        return loaded
    }

    /** [loadAll]'s pure half: raw file text to tiles, with anything unparseable read back as no
     *  tiles at all rather than crashing — a corrupt `speed_cameras.json` must not take camera
     *  markers down with it. Split out so it is testable without a real account directory, the
     *  same reason [SpeedCameras.parseCamerasResponse] is split from `nearViaBackend`. */
    internal fun parseAll(text: String): List<CachedTile> = try {
        jsonArrayOf(text).objects().mapNotNull { parseTile(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun load(center: LatLon, radiusMeters: Double): SpeedCameras.Result? {
        val k = key(center, radiusMeters)
        val tile = loadAll().firstOrNull { it.key == k } ?: return null
        return tile.result.takeIf { nowMs() - tile.fetchedAtMs <= TTL_MS }
    }

    /** `suspend`, taking [writeLock], because [save] is only ever called from [SpeedCameras.near]
     *  — already `suspend` — and two concurrent prefetches (see [writeLock]'s doc) must not
     *  interleave their read-modify-write of the tile list. */
    suspend fun save(center: LatLon, radiusMeters: Double, result: SpeedCameras.Result): Unit = writeLock.withLock {
        val tile = CachedTile(key(center, radiusMeters), nowMs(), result)
        val next = withTile(loadAll(), tile, nowMs())
        accountFile(FILE_NAME).writeText(serialise(next))
        cache = next
    }

    /** [save]'s pure half: the tile list once [tile] lands — any existing tile at the same key
     *  replaced rather than duplicated, and every tile older than [TTL_MS] pruned so a long
     *  drive's repeated refetches (roughly every 3 km, per `CameraPrefetch`'s edge-of-area
     *  margin, in `com.jellemax.detour.drive`) don't grow this file forever. Split out for the
     *  same reason [parseAll] is. */
    internal fun withTile(existing: List<CachedTile>, tile: CachedTile, nowMs: Long): List<CachedTile> =
        existing.filterNot { it.key == tile.key || nowMs - it.fetchedAtMs > TTL_MS } + tile

    fun reset() {
        cache = null
    }

    private fun parseTile(o: JsonObject): CachedTile? {
        val key = o.optString("key").takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = o.optLong("fetchedAtMs", 0L)
        val cameras = (o.optArray("cameras") ?: return null).objects().map {
            SpeedCameras.Camera(
                LatLon(it.optDouble("lat"), it.optDouble("lon")),
                it.optDouble("maxspeedKmh").takeIf { v -> !v.isNaN() },
                SpeedCameras.CameraKind.valueOf(it.optString("kind")),
                it.optDouble("facingDeg").takeIf { v -> !v.isNaN() },
            )
        }
        val sections = (o.optArray("sections") ?: return null).objects().map {
            SpeedCameras.Section(
                (it.optArray("endA") ?: JsonArrayEmpty).arrays().map { p -> LatLon(p.optDouble(0), p.optDouble(1)) },
                (it.optArray("endB") ?: JsonArrayEmpty).arrays().map { p -> LatLon(p.optDouble(0), p.optDouble(1)) },
                it.optDouble("spanMeters"),
                it.optDouble("maxspeedKmh").takeIf { v -> !v.isNaN() },
            )
        }
        return CachedTile(key, fetchedAt, SpeedCameras.Result(cameras, sections))
    }

    /** internal, not private, so the disk-cache tests can round-trip a tile through this and
     *  [parseAll] without a real account directory. */
    internal fun serialise(tiles: List<CachedTile>): String = buildJsonArray {
        for (t in tiles) addJsonObject {
            put("key", t.key)
            put("fetchedAtMs", t.fetchedAtMs)
            putJsonArray("cameras") {
                for (c in t.result.cameras) addJsonObject {
                    put("lat", c.at.lat); put("lon", c.at.lon)
                    c.maxspeedKmh?.let { put("maxspeedKmh", it) }
                    put("kind", c.kind.name)
                    c.facingDeg?.let { put("facingDeg", it) }
                }
            }
            putJsonArray("sections") {
                for (s in t.result.sections) addJsonObject {
                    putJsonArray("endA") { for (p in s.endA) addJsonArray { add(p.lat); add(p.lon) } }
                    putJsonArray("endB") { for (p in s.endB) addJsonArray { add(p.lat); add(p.lon) } }
                    put("spanMeters", s.spanMeters)
                    s.maxspeedKmh?.let { put("maxspeedKmh", it) }
                }
            }
        }
    }.string()
}
