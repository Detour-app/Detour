package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One answer [RoadRoulette.rawQuery] gave us, and when we asked for it —
 * everything [OverpassCache] needs to decide whether it can still be served.
 */
internal data class Entry(val body: String, val fetchedAtMs: Long)

/**
 * A grid cell side for [cacheKey], reusing [CELL_METERS] from `Coverage.kt`.
 *
 * That grid already exists for the same reason this one does — a fetch
 * centre a few metres from the last one is the same neighbourhood, not a new
 * area worth a fresh Overpass round trip — and 250 m is already sized to be
 * smaller than the smallest prefetch radius here ([RoadRoulette.MAX_SNAP_METERS],
 * 25 m, is the outlier; everything else is 600 m+), so reusing it costs
 * nothing in precision and one fewer constant to keep in sync.
 */
private const val GRID_METERS = CELL_METERS

/**
 * Rounds a fetch centre onto the shared [GRID_METERS] grid and folds it,
 * [tag] and the (int-truncated) [radiusMeters] into one string key. Two
 * fetches whose centres land in the same cell — the common case when a rider
 * is retracing a road, or when a prefetch re-centres a few metres from where
 * it last did — share one cache entry instead of costing a second request.
 *
 * [tag] is a short literal identifying the query *shape* ("cameras",
 * "roads:$highwayRegex", "poi:$kind", …): two queries over the same area with
 * different filters are different data and must not collide.
 *
 * [radiusMeters] is truncated to an int, matching how every call site already
 * truncates it into its own query string (`radiusMeters.toInt()`) — so two
 * calls that would send byte-identical Overpass queries land on the same key,
 * and no more.
 */
internal fun cacheKey(tag: String, center: LatLon, radiusMeters: Double): String {
    val cellLat = (center.lat * METERS_PER_DEG_LAT / GRID_METERS).roundToLong()
    val cellLon = (center.lon * metersPerDegLon(center.lat) / GRID_METERS).roundToLong()
    return "$tag:$cellLat:$cellLon:${radiusMeters.toInt()}"
}

private const val METERS_PER_DEG_LAT = 111_320.0

private fun metersPerDegLon(lat: Double): Double =
    METERS_PER_DEG_LAT * kotlin.math.cos(toRadians(lat)).coerceAtLeast(1e-6)

/** Whether [entry] is still inside [ttlMs] of [nowMs] — the pure half of
 *  [OverpassCache.fetch], split out so it is testable with no filesystem. */
internal fun isFresh(entry: Entry, nowMs: Long, ttlMs: Long): Boolean =
    nowMs - entry.fetchedAtMs in 0L..ttlMs

/**
 * Keeps the [maxEntries] most-recently-fetched of [entries], dropping the
 * rest. A no-op under the limit, so a normal cache never pays a sort.
 */
internal fun evict(entries: Map<String, Entry>, maxEntries: Int): Map<String, Entry> {
    if (entries.size <= maxEntries) return entries
    return entries.entries
        .sortedByDescending { it.value.fetchedAtMs }
        .take(maxEntries)
        .associate { it.key to it.value }
}

/**
 * Disk cache for Overpass answers, shared by every call site that goes
 * through [RoadRoulette.rawQuery] — cameras, roads, speed limits, road types,
 * POIs. Before this, only [MunicipalityStore] cached anything to disk; the
 * other five re-fetched from scratch every session (#323).
 *
 * Persisted to [deviceFile] rather than [accountFile]: OSM geography isn't
 * personal data, the same rationale that would apply to `MunicipalityStore`
 * if it moved (it hasn't — out of scope here) — so one cache is shared by
 * every rider on the device, and a second rider signing in gets the first
 * rider's warm cache for free instead of re-fetching the same roads.
 *
 * In-memory memoisation mirrors [MunicipalityStore.cache]: loaded lazily on
 * first use, kept in sync on every write, so the six-plus callers across a
 * drive don't each re-read and re-parse the file.
 */
object OverpassCache {

    private const val FILE_NAME = "overpass_cache.json"

    /**
     * How long an entry is served without a re-fetch.
     *
     * Long, deliberately: what is cached here — gantry positions, posted
     * limits, road geometry — changes on the order of months in OSM, not
     * minutes. A speed camera relocating or a limit being re-signed is a
     * mapper edit that happens rarely enough that 30 days of staleness is a
     * better trade than paying Overpass again on every replay of the same
     * route. A stale entry still refreshes — [fetch] just doesn't wait on
     * that refresh happening more than once a month.
     */
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Bound on how many entries are kept on disk.
     *
     * A typical Overpass answer here is a few KB (a handful of ways or
     * points with geometry) — occasionally tens of KB for a wide roads
     * fetch — so 4000 entries bounds the file at a low single-digit number
     * of MB, which is the "bounded on disk" the design calls for without a
     * rider ever noticing the ceiling in ordinary use (a season of riding
     * touches a few hundred distinct grid cells, not thousands).
     */
    const val MAX_ENTRIES = 4000

    // internal, not private, so a test that needs to observe the memo can —
    // same reason MunicipalityStore.cache is internal.
    @Volatile internal var cache: MutableMap<String, Entry>? = null

    /**
     * Serves [key] from cache when a fresh entry exists; otherwise runs
     * [request], caches the result and returns it.
     *
     * [request]'s exceptions propagate unchanged — a failure is never
     * cached, so the next call (whether that's a second later or a fresh
     * process) tries the network again rather than replaying a failure for
     * [ttlMs].
     */
    suspend fun fetch(key: String, ttlMs: Long = TTL_MS, request: suspend () -> String): String {
        val now = nowMs()
        load()[key]?.let { entry -> if (isFresh(entry, now, ttlMs)) return entry.body }
        val body = request()
        store(key, Entry(body, now))
        return body
    }

    private fun load(): MutableMap<String, Entry> {
        val t = Perf.start()
        cache?.let {
            Perf.end(t, "OverpassCache.load") { listOf("entries" to it.size, "hit" to 1) }
            return it
        }
        val f = deviceFile(FILE_NAME)
        val loaded: MutableMap<String, Entry> = if (!f.exists()) mutableMapOf() else try {
            parse(jsonObjectOf(f.readText()))
        } catch (e: Exception) {
            mutableMapOf()
        }
        cache = loaded
        Perf.end(t, "OverpassCache.load") { listOf("entries" to loaded.size, "hit" to 0) }
        return loaded
    }

    private fun store(key: String, entry: Entry) {
        val updated = evict((cache ?: load()) + (key to entry), MAX_ENTRIES).toMutableMap()
        cache = updated
        deviceFile(FILE_NAME).writeText(encode(updated).string())
    }

    private fun parse(o: JsonObject): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>(o.size)
        for ((key, value) in o) {
            val entryObj = value as? JsonObject ?: continue
            out[key] = Entry(entryObj.optString("body"), entryObj.optLong("fetchedAtMs"))
        }
        return out
    }

    private fun encode(entries: Map<String, Entry>): JsonObject = buildJsonObject {
        for ((key, entry) in entries) {
            putJsonObject(key) {
                put("body", entry.body)
                put("fetchedAtMs", entry.fetchedAtMs)
            }
        }
    }

    /** Drops the in-memory cache. The file stays — for symmetry with
     *  [MunicipalityStore.reset]; nothing calls this yet. */
    fun reset() {
        cache = null
    }
}
