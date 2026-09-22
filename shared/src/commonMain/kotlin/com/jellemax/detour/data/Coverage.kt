package com.jellemax.detour.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
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
import okio.IOException

/** Side of one coverage cell. Matches [ExploredArea]'s grid: a road driven once
 *  reveals the cell around it, so "explored" means the same thing everywhere.
 *  Not private: callers that turn a cell count into an area (the coverage map's
 *  "x% of N km²" card) need it too. */
const val CELL_METERS = 250.0
private const val METERS_PER_DEG = 111_320.0

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
        // Measured here rather than around [insideCells]: that is a `by lazy`, so
        // wrapping the property would time a memo read on every call but the
        // first, and the first is the only one that costs anything.
        val t = Perf.start()
        val rows = ceil((maxLat - minLat) / cellDegLat).toInt()
        val cols = ceil((maxLon - minLon) / cellDegLon).toInt()
        if (rows <= 0 || cols <= 0 || rows.toLong() * cols > MAX_CELLS) {
            Perf.end(t, "Municipality.gridInterior") {
                listOf("ringPoints" to points.size, "cells" to 0)
            }
            return emptySet()
        }
        val cells = HashSet<Long>(rows * cols / 2)
        for (row in 0 until rows) {
            val lat = minLat + (row + 0.5) * cellDegLat
            for (col in 0 until cols) {
                val lon = minLon + (col + 0.5) * cellDegLon
                if (contains(LatLon(lat, lon))) cells.add(row * 1_000_000L + col)
            }
        }
        Perf.end(t, "Municipality.gridInterior") {
            listOf("ringPoints" to points.size, "cells" to cells.size)
        }
        return cells
    }
}

/**
 * The municipalities we have driven into, with their boundaries, cached on disk.
 *
 * Boundaries are discovered lazily: the tracking service asks [needsLookup] for
 * each new trace point and, when the answer is yes, [discoverQuietly] resolves
 * that point to a boundary via this deployment's own `/api/municipality` endpoint. Driving
 * through a new gemeente costs exactly one query; every later point lands inside a boundary we
 * already have.
 */
object MunicipalityStore {

    private const val FILE_NAME = "municipalities.json"

    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    @Volatile internal var cache: List<Municipality>? = null

    /**
     * Points the backend had no admin_level=8 boundary for (sea, or outside our
     * admin-level assumption). Kept per session so we stop asking.
     *
     * Replaced wholesale rather than mutated: the discovery coroutine writes it
     * while the location callback reads it, and swapping an immutable set into
     * a @Volatile field needs no lock on either platform (Kotlin/Native has no
     * ConcurrentHashMap to borrow).
     */
    // internal, not private, so the session-switch test can set it and watch
    // Auth.resetAccountScopedStores clear it again. See that function's doc.
    @Volatile internal var misses: Set<Long> = emptySet()

    /** Serialises the read-modify-write in [discoverQuietly], which `synchronized`
     *  used to do; `synchronized` is JVM-only. */
    private val writeLock = Mutex()

    fun load(): List<Municipality> {
        val t = Perf.start()
        cache?.let {
            Perf.end(t, "MunicipalityStore.load") {
                listOf("boundaries" to it.size, "hit" to 1)
            }
            return it
        }
        val f = accountFile(FILE_NAME)
        val loaded = if (!f.exists()) emptyList() else try {
            jsonArrayOf(f.readText()).objects().mapNotNull { parse(it) }
        } catch (e: Exception) {
            emptyList()
        }
        cache = loaded
        Perf.end(t, "MunicipalityStore.load") {
            listOf("boundaries" to loaded.size, "hit" to 0)
        }
        return loaded
    }

    /** Drops the learned boundaries and the not-found set, both of which are
     *  derived from one rider's traces. */
    fun reset() {
        cache = null
        misses = emptySet()
    }

    /**
     * True when [p] is in no known boundary and hasn't already missed.
     *
     * The only instrumented function on the GPS callback path
     * (`TripTrackingService.kt:1150`, `TripRecorder.swift:367`), and the reason
     * it is instrumented is the second clause: [load] is memoised, so what
     * actually runs per fix is an even-odd ray cast over every ring of every
     * boundary this rider has ever driven into. That grows with how far they
     * have ridden. Aggregated rather than written per call — see
     * `PerfLog.isHot`.
     */
    fun needsLookup(p: LatLon): Boolean {
        val t = Perf.start()
        val needs = missKey(p) !in misses && load().none { it.contains(p) }
        // The memo, not load() — the covariate has to be O(1). Summing ring
        // points per fix would cost more than the call being measured, and the
        // per-boundary ring size is already carried by
        // `Municipality.gridInterior`'s own record.
        Perf.end(t, "MunicipalityStore.needsLookup") { listOf("boundaries" to (cache?.size ?: 0)) }
        return needs
    }

    /**
     * Resolves [p] to its municipality and stores it. The network/parse leg
     * is caught internally (below) and never escapes, but [save] on the
     * write-lock path still can — see [SyncClient.sync]'s doc for why this
     * carries `@Throws(Exception::class)` rather than nothing at all.
     */
    @Throws(Exception::class)
    suspend fun discoverQuietly(p: LatLon) {
        if (!needsLookup(p)) return
        // Captured before the suspension below, and checked again after it.
        // [fetch] resolves a boundary from *this* rider's fix; if the session
        // moves while it is in flight, both writes below would land in the
        // next rider's municipalities.json. `existing` is already re-read
        // inside the lock, which keeps the file consistent — but consistent
        // with the wrong rider's data. Same capture FriendsStore.reload and
        // SyncClient.sync use.
        val epoch = Auth.sessionEpoch.value
        val found = try {
            fetch(p)
        } catch (e: Exception) {
            return // offline or backend down; the next new cell tries again
        }
        if (found == null) {
            if (epoch == Auth.sessionEpoch.value) misses = misses + missKey(p)
            return
        }
        writeLock.withLock {
            // Inside the lock, not before it: the lock is itself a suspension
            // point, so a check outside it can go stale while this call waits
            // its turn behind another discovery.
            if (epoch != Auth.sessionEpoch.value) return
            val existing = load()
            if (existing.any { it.id == found.id }) return
            save(existing + found)
        }
    }

    /**
     * Resolves [p] to its municipality, or null when nothing contains it (a backend call that
     * fails outright throws instead).
     *
     * Backed by this deployment's own `/api/municipality` endpoint (issue #381). No disk-cache
     * layer of its own — [MunicipalityStore] already *is* the disk cache
     * ([needsLookup]/[discoverQuietly] only ever call this once per boundary, not per fix). An
     * install with no announced endpoint returns null.
     */
    private suspend fun fetch(p: LatLon): Municipality? {
        val backendBase = RoutingServer.municipalityBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return null
        return fetchViaBackend(backendBase, p)
    }

    /** The point lookup against this deployment's own municipality-boundary endpoint (issue
     *  #381). Null means the server answered and named no containing boundary. */
    private suspend fun fetchViaBackend(base: String, p: LatLon): Municipality? {
        val url = "$base/api/municipality?lat=${p.lat}&lon=${p.lon}"
        val body = jsonObjectOf(RoadRoulette.rawGet(url, headers = RoutingServer.userAgentHeaders()))
        return parseMunicipalityResponse(body)
    }

    /** [fetchViaBackend]'s pure half. internal, not private, so commonTest can feed it canned
     *  response bodies — the wire shape is `MunicipalityBoundaryDto`/`MunicipalityResponse`
     *  (`backend/Detour/Detour.Api/Contracts/MunicipalityContracts.cs`). */
    internal fun parseMunicipalityResponse(body: JsonObject): Municipality? {
        val m = body.optObject("municipality") ?: return null
        val name = m.optString("name").takeIf { it.isNotBlank() } ?: return null
        val ringsArray = m.optArray("rings") ?: return null
        val rings = ringsArray.arrays()
            .map { ring -> ring.arrays().map { p -> LatLon(p.optDouble(0), p.optDouble(1)) } }
            .filter { it.size >= 3 }
        if (rings.isEmpty()) return null
        return Municipality(m.optLong("id"), name, rings)
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
        accountFile(FILE_NAME).writeText(array.string())
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
        val t = Perf.start()
        val municipalities = MunicipalityStore.load()
        val traceVersion = TraceStore.version.value
        cache?.let { c ->
            if (c.traceVersion == traceVersion && c.municipalities === municipalities) {
                // Recorded as a hit rather than skipped: a cache hit is nearly
                // free, and a series that silently omitted them would make the
                // first call's cost look like the cost of every call.
                Perf.end(t, "Coverage.compute") {
                    listOf("points" to 0, "municipalities" to municipalities.size, "hit" to 1)
                }
                return c.entries
            }
        }

        var pointCount = 0
        val entries = if (municipalities.isEmpty()) emptyList() else {
            val points = TraceStore.loadAll().flatten()
            pointCount = points.size
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
        // Two covariates, not one: this walks trace points *against*
        // municipalities, and a record carrying a single scalar could not say
        // which of the two moved.
        Perf.end(t, "Coverage.compute") {
            listOf("points" to pointCount, "municipalities" to municipalities.size, "hit" to 0)
        }
        return entries
    }
}
