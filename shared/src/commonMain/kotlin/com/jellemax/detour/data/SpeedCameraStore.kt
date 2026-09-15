package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.Volatile

/** One cached bbox fetch: the box itself (rounded so nearby prefetches share a cache entry),
 *  when it was fetched, and the result. */
private data class CachedTile(val key: String, val fetchedAtMs: Long, val result: SpeedCameras.Result)

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
     *  disk fallback for when the network fetch itself fails, not the primary freshness control. */
    private const val TTL_MS = 24L * 60 * 60 * 1000

    /** Rounds to ~0.01° (~1 km) so a slightly different prefetch centre still hits the same
     *  cached tile — the same reasoning [MunicipalityStore]'s own miss bucket uses. */
    private fun key(center: LatLon, radiusMeters: Double): String =
        "${(center.lat * 100).toInt()}:${(center.lon * 100).toInt()}:${radiusMeters.toInt()}"

    @Volatile private var cache: List<CachedTile>? = null

    private fun loadAll(): List<CachedTile> {
        cache?.let { return it }
        val f = accountFile(FILE_NAME)
        val loaded = if (!f.exists()) emptyList() else try {
            jsonArrayOf(f.readText()).objects().mapNotNull { parseTile(it) }
        } catch (e: Exception) {
            emptyList()
        }
        cache = loaded
        return loaded
    }

    fun load(center: LatLon, radiusMeters: Double): SpeedCameras.Result? {
        val k = key(center, radiusMeters)
        val tile = loadAll().firstOrNull { it.key == k } ?: return null
        if (nowMs() - tile.fetchedAtMs > TTL_MS) return null
        return tile.result
    }

    fun save(center: LatLon, radiusMeters: Double, result: SpeedCameras.Result) {
        val k = key(center, radiusMeters)
        val next = loadAll().filterNot { it.key == k } + CachedTile(k, nowMs(), result)
        accountFile(FILE_NAME).writeText(serialise(next))
        cache = next
    }

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

    private fun serialise(tiles: List<CachedTile>): String = buildJsonArray {
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
