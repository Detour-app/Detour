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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/** Side of one coverage cell. Matches [ExploredArea]'s grid: a road driven once
 *  reveals the cell around it, so "explored" means the same thing everywhere.
 *  Not private: callers that turn a cell count into an area (the coverage map's
 *  "x% of N km²" card) need it too. */
const val CELL_METERS = 250.0
private const val METERS_PER_DEG = 111_320.0

/** Two boundary points this close are the same OSM node, shared between ways. */
private const val NODE_EPSILON_DEG = 1e-7

/** Ring points nearer than this to the previous one carry no information at a
 *  250 m grid, and boundaries from OSM are absurdly detailed. */
private const val RING_DECIMATE_METERS = 100.0

/** Refuse to grid a boundary bigger than this many cells (~12,500 km²). */
private const val MAX_CELLS = 200_000

/**
 * An OSM `admin_level=8` boundary — gemeente, commune, Gemeinde, municipality —
 * with the machinery to ask what fraction of it has been driven.
 *
 * The grid is local to each boundary: cell size in degrees is derived from the
 * boundary's own centre latitude, so cells are square-ish in metres and rows and
 * columns form a plain rectangular lattice we can enumerate. [ExploredArea] uses
 * a global grid for a different job (is this point new?) and doesn't need that.
 */
data class Municipality(
    val id: Long,
    val name: String,
    /** Closed rings, outer and inner alike; the closing segment is implied, not
     *  repeated. The even-odd test in [contains] treats them uniformly. */
    val rings: List<List<LatLon>>,
) {
    private val points = rings.flatten()
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }

    private val cellDegLat = CELL_METERS / METERS_PER_DEG
    private val cellDegLon = CELL_METERS /
        (METERS_PER_DEG * cos(toRadians((minLat + maxLat) / 2)))

    /** Every cell whose centre falls inside the boundary. Computed once; this is
     *  the denominator of the coverage percentage. */
    val insideCells: Set<Long> by lazy { gridInterior() }

    fun cellOf(p: LatLon): Long {
        val row = floor((p.lat - minLat) / cellDegLat).toLong()
        val col = floor((p.lon - minLon) / cellDegLon).toLong()
        return row * 1_000_000L + col
    }

    fun boundingBoxContains(p: LatLon): Boolean =
        p.lat in minLat..maxLat && p.lon in minLon..maxLon

    /** Even-odd ray cast, counting crossings of a ray going east from [p]. */
    fun contains(p: LatLon): Boolean {
        if (!boundingBoxContains(p)) return false
        var inside = false
        for (ring in rings) {
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size] // implicit closing segment
                if ((a.lat > p.lat) != (b.lat > p.lat)) {
                    val x = a.lon + (p.lat - a.lat) / (b.lat - a.lat) * (b.lon - a.lon)
                    if (x > p.lon) inside = !inside
                }
            }
        }
        return inside
    }

    private fun gridInterior(): Set<Long> {
        val rows = ceil((maxLat - minLat) / cellDegLat).toInt()
        val cols = ceil((maxLon - minLon) / cellDegLon).toInt()
        if (rows <= 0 || cols <= 0 || rows.toLong() * cols > MAX_CELLS) return emptySet()
        val cells = HashSet<Long>(rows * cols / 2)
        for (row in 0 until rows) {
            val lat = minLat + (row + 0.5) * cellDegLat
            for (col in 0 until cols) {
                val lon = minLon + (col + 0.5) * cellDegLon
                if (contains(LatLon(lat, lon))) cells.add(row * 1_000_000L + col)
            }
        }
        return cells
    }
}

/**
 * The municipalities we have driven into, with their boundaries, cached on disk.
 *
 * Boundaries are discovered lazily: the tracking service asks [needsLookup] for
 * each new trace point and, when the answer is yes, [discoverQuietly] resolves
 * that point to a boundary over Overpass. Driving through a new gemeente costs
 * exactly one query; every later point lands inside a boundary we already have.
 */
object MunicipalityStore {

    private const val FILE_NAME = "municipalities.json"

    @Volatile private var cache: List<Municipality>? = null

    /**
     * Points Overpass had no admin_level=8 boundary for (sea, or outside our
     * admin-level assumption). Kept per session so we stop asking.
     *
     * Replaced wholesale rather than mutated: the discovery coroutine writes it
     * while the location callback reads it, and swapping an immutable set into
     * a @Volatile field needs no lock on either platform (Kotlin/Native has no
     * ConcurrentHashMap to borrow).
     */
    @Volatile private var misses: Set<Long> = emptySet()

    /** Serialises the read-modify-write in [discoverQuietly], which `synchronized`
     *  used to do; `synchronized` is JVM-only. */
    private val writeLock = Mutex()

    fun load(): List<Municipality> {
        cache?.let { return it }
        val f = appFile(FILE_NAME)
        val loaded = if (!f.exists()) emptyList() else try {
            jsonArrayOf(f.readText()).objects().mapNotNull { parse(it) }
        } catch (e: Exception) {
            emptyList()
        }
        cache = loaded
        return loaded
    }

    /** True when [p] is in no known boundary and hasn't already missed. */
    fun needsLookup(p: LatLon): Boolean =
        missKey(p) !in misses && load().none { it.contains(p) }

    /**
     * Resolves [p] to its municipality and stores it. The network/parse leg
     * is caught internally (below) and never escapes, but [save] on the
     * write-lock path still can — see [SyncClient.sync]'s doc for why this
     * carries `@Throws(Exception::class)` rather than nothing at all.
     */
    @Throws(Exception::class)
    suspend fun discoverQuietly(p: LatLon) {
        if (!needsLookup(p)) return
        val found = try {
            fetch(p)
        } catch (e: Exception) {
            return // offline or Overpass down; the next new cell tries again
        }
        if (found == null) {
            misses = misses + missKey(p)
            return
        }
        writeLock.withLock {
            val existing = load()
            if (existing.any { it.id == found.id }) return
            save(existing + found)
        }
    }

    private suspend fun fetch(p: LatLon): Municipality? {
        val query = "[out:json][timeout:25];" +
            "is_in(${p.lat},${p.lon})->.a;" +
            "relation(pivot.a)[\"boundary\"=\"administrative\"][\"admin_level\"=\"8\"];" +
            "out geom;"
        val elements = jsonObjectOf(RoadRoulette.rawQuery(query)).optArray("elements")
            ?: return null
        val el = elements.optObject(0) ?: return null
        val name = el.optObject("tags")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: return null

        // Relation members are the boundary's ways, each an open polyline. Only
        // once chained end-to-end do they form the rings a ray cast needs.
        // Inner rings (enclaves — a neighbouring town wholly surrounded by this
        // one) are kept: an even-odd ray cast subtracts them for free, which is
        // exactly right, and dropping them would inflate the denominator.
        // Non-geometry members (admin_centre nodes, label nodes) fall out on type.
        val members = el.optArray("members") ?: return null
        val ways = ArrayList<List<LatLon>>()
        for (m in members.objects()) {
            if (m.optString("type") != "way") continue
            if (m.optString("role").let { it != "outer" && it != "inner" && it.isNotEmpty() }) continue
            val geometry = m.optArray("geometry") ?: continue
            val way = geometry.objects().map { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
            if (way.size >= 2) ways.add(way)
        }
        val rings = assembleRings(ways).map { decimate(it) }.filter { it.size >= 3 }
        if (rings.isEmpty()) return null
        return Municipality(el.optLong("id"), name, rings)
    }

    /** Chains open ways into closed rings by matching their endpoints. */
    private fun assembleRings(ways: List<List<LatLon>>): List<List<LatLon>> {
        val remaining = ways.toMutableList()
        val rings = ArrayList<List<LatLon>>()
        while (remaining.isNotEmpty()) {
            val ring = ArrayList(remaining.removeAt(0))
            while (!same(ring.first(), ring.last())) {
                val next = remaining.indexOfFirst {
                    same(it.first(), ring.last()) || same(it.last(), ring.last())
                }
                if (next < 0) break // boundary has a gap; the ray cast closes it
                val way = remaining.removeAt(next)
                val ordered = if (same(way.first(), ring.last())) way else way.reversed()
                ring.addAll(ordered.drop(1))
            }
            if (same(ring.first(), ring.last())) ring.removeAt(ring.size - 1)
            if (ring.size >= 3) rings.add(ring)
        }
        return rings
    }

    private fun same(a: LatLon, b: LatLon): Boolean =
        kotlin.math.abs(a.lat - b.lat) < NODE_EPSILON_DEG &&
            kotlin.math.abs(a.lon - b.lon) < NODE_EPSILON_DEG

    private fun decimate(ring: List<LatLon>): List<LatLon> {
        val out = ArrayList<LatLon>(ring.size / 4)
        for (p in ring) {
            val last = out.lastOrNull()
            if (last == null || RoadRoulette.distanceMeters(last, p) >= RING_DECIMATE_METERS) {
                out.add(p)
            }
        }
        return out
    }

    /** ~2 km bucket: one failed lookup shouldn't silence the next town over. */
    private fun missKey(p: LatLon): Long =
        floor(p.lat * 50).toLong() * 100_000L + floor(p.lon * 50).toLong()

    private fun parse(o: JsonObject): Municipality? {
        val ringsArray = o.optArray("rings") ?: return null
        val rings = ringsArray.arrays().map { ring ->
            ring.arrays().map { p -> LatLon(p.optDouble(0), p.optDouble(1)) }
        }.filter { it.size >= 3 }
        if (rings.isEmpty()) return null
        return Municipality(o.optLong("id"), o.optString("name"), rings)
    }

    private fun save(all: List<Municipality>) {
        val array = buildJsonArray {
            for (m in all) addJsonObject {
                put("id", m.id)
                put("name", m.name)
                putJsonArray("rings") {
                    for (ring in m.rings) addJsonArray {
                        for (p in ring) addJsonArray { add(p.lat); add(p.lon) }
                    }
                }
            }
        }
        appFile(FILE_NAME).writeText(array.string())
        cache = all
    }
}

/** How much of each municipality we've driven, from the fog-of-war traces. */
object Coverage {

    data class Entry(
        val name: String,
        val exploredCells: Int,
        val totalCells: Int,
        /** Ties this entry back to its [Municipality] so a caller (the coverage
         *  map) can look up the boundary rings without recomputing coverage. */
        val municipalityId: Long,
    ) {
        val percent: Double
            get() = if (totalCells == 0) 0.0 else 100.0 * exploredCells / totalCells
    }

    // Hub, Badges, the coverage map and Friends each land on this independently
    // as you navigate between them; cached here so the second and later calls
    // are free instead of repeating the full trace walk. Keyed on TraceStore's
    // own version counter, plus the municipality list's identity (it's only
    // ever replaced wholesale, in MunicipalityStore.save) since municipalities
    // have no version counter of their own. Held as one reference, not three
    // separate @Volatile fields, so a concurrent reader (there are six callers
    // across screens, sync and badge checks) can never observe matching keys
    // paired with a stale/previous result.
    private class Cache(val traceVersion: Int, val municipalities: List<Municipality>, val entries: List<Entry>)
    @Volatile private var cache: Cache? = null

    /** Walks every trace point once per municipality it could belong to. Cheap
     *  enough for a screen open or a trip end; not for a GPS callback. */
    fun compute(): List<Entry> {
        val municipalities = MunicipalityStore.load()
        val traceVersion = TraceStore.version.value
        cache?.let { c ->
            if (c.traceVersion == traceVersion && c.municipalities === municipalities) return c.entries
        }

        val entries = if (municipalities.isEmpty()) emptyList() else {
            val points = TraceStore.loadAll().flatten()
            municipalities.map { m ->
                val explored = HashSet<Long>()
                for (p in points) {
                    if (!m.boundingBoxContains(p)) continue
                    val cell = m.cellOf(p)
                    if (cell in m.insideCells) explored.add(cell)
                }
                Entry(m.name, explored.size, m.insideCells.size, m.id)
            }.filter { it.totalCells > 0 }.sortedByDescending { it.percent }
        }

        cache = Cache(traceVersion, municipalities, entries)
        return entries
    }
}
