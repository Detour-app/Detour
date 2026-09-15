package com.jellemax.detour.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * Speed cameras and average-speed sections near you, from OpenStreetMap via
 * Overpass — the only source that is an actual queryable API.
 *
 * In OSM a fixed camera is a node tagged `highway=speed_camera`. A Belgian
 * trajectcontrole (average-speed section) is a `type=enforcement,
 * enforcement=average_speed` relation whose start/end *device* members are
 * themselves such nodes; the relation carries the posted `maxspeed`. A single
 * fixed camera can carry the same `maxspeed` on a `type=enforcement,
 * enforcement=maxspeed` relation instead of on the device node itself. We
 * fetch the individual camera nodes (for the map markers and the over-speed
 * chime), the average-speed relations (whose device coordinates let us tell
 * when you enter and leave a section, so the average can be timed) and the
 * fixed-camera relations (folded back onto their device node, see
 * [foldMaxspeedRelations]) in one request.
 *
 * Same prefetch shape as [RoadRoulette.speedLimitWays]: fetched once for a wide
 * area, refreshed only as you near the edge of what you already have, so there
 * is no network round-trip per fix. [near] returns null on any network error —
 * cameras are an overlay, never something the drive depends on, and a null lets
 * the caller keep the markers it already has instead of flickering them off.
 */
object SpeedCameras {

    /**
     * What a camera enforces. A device can be tagged as both — a red-light
     * camera on a gantry that also carries a `highway=speed_camera` node —
     * which is [COMBINED] rather than two markers for one device.
     */
    enum class CameraKind { SPEED, RED_LIGHT, COMBINED }

    /**
     * One camera to draw on the map.
     *
     * [maxspeedKmh] is the limit tagged on the camera node itself, when it has
     * one. Mappers put it there far more often than on the enforcement relation
     * — both real E40 trajectcontrole relations tag no `maxspeed` at all and
     * carry the 120 on their device nodes — so a section that reads its limit
     * only off the relation gets nothing to judge its average against.
     *
     * [kind] defaults to [CameraKind.SPEED] — a bare `highway=speed_camera`
     * node with no enforcement relation naming it otherwise.
     *
     * [facingDeg] is the compass bearing the camera itself faces (`camera:direction`,
     * falling back to `direction`), when tagged as a plain number — the cardinal
     * spelling (`N`, `SE`, …) some mappers use instead is left unparsed. Null means
     * "untagged", not "faces everywhere": [CameraWarner] keeps its heading wedge
     * as the only test for such a camera.
     */
    data class Camera(
        val at: LatLon,
        val maxspeedKmh: Double? = null,
        val kind: CameraKind = CameraKind.SPEED,
        val facingDeg: Double? = null,
    )

    /**
     * An average-speed section, as the two ends you can pass it through.
     *
     * [endA] and [endB] are the device clusters at either end — one node per
     * carriageway, a few metres apart — and [spanMeters] is the distance
     * between them. Which end is the entry depends on which way you drive, so
     * they are not labelled start/end here. [maxspeedKmh] is the posted limit
     * the average is judged against, from the relation's own `maxspeed` tag or,
     * failing that, from the gantry nodes at its ends.
     */
    data class Section(
        val endA: List<LatLon>,
        val endB: List<LatLon>,
        val spanMeters: Double,
        val maxspeedKmh: Double?,
    )

    data class Result(val cameras: List<Camera>, val sections: List<Section>)

    /** Radius fetched around you. Wide enough that one fetch covers a few
     *  minutes of driving before the edge-of-area refetch kicks in. */
    const val PREFETCH_RADIUS_M = 4000.0

    /** Beyond this a camera isn't worth warning about yet. */
    const val WARN_METERS = 400.0

    /**
     * Cameras/sections near [center]: the backend's own dataset first (issue
     * #303), when this deployment has announced a camera-data endpoint on
     * `/api/capabilities`; a disk cache from an earlier successful fetch when
     * the backend is reachable-in-principle but this call failed; Overpass
     * only once both of those come up empty. An install that has announced
     * nothing at all (the ordinary case today) resolves [RoutingServer.camerasBase]
     * to blank and goes straight to Overpass, unchanged from before this path
     * existed.
     */
    @Throws(Exception::class)
    suspend fun near(
        center: LatLon,
        radiusMeters: Double = PREFETCH_RADIUS_M,
    ): Result? {
        val backendBase = RoutingServer.camerasBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return nearViaOverpass(center, radiusMeters)

        val fromBackend = try {
            nearViaBackend(backendBase, center, radiusMeters)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            // Same reasoning as nearViaOverpass's own catch of this: a
            // misbehaving reverse proxy in front of a self-host can answer
            // 200 with an HTML page, which parseToJsonElement rejects before
            // it is even a JSON value — this must fall through to the disk
            // cache/Overpass same as any other backend failure, not escape
            // the fallback chain entirely.
            null
        }
        if (fromBackend != null) {
            SpeedCameraStore.save(center, radiusMeters, fromBackend)
            return fromBackend
        }
        // Backend announced but unreachable this time: the disk cache survives the outage per
        // issue #303; only once that's also empty does this fall back to Overpass, so a rider on a
        // self-host with a blipped camera-data endpoint still sees yesterday's markers instead of
        // suddenly reverting to the public API for one request.
        return SpeedCameraStore.load(center, radiusMeters) ?: nearViaOverpass(center, radiusMeters)
    }

    /** The bbox fetch against this deployment's own camera-data endpoint (issue #303) — the
     *  wire shape is [Task 3's `CamerasBboxResponse`/`CameraDto`]
     *  (`backend/Detour/Detour.Api/Contracts/CameraContracts.cs`). Null on a malformed-but-parsed
     *  body with no usable `elements` is not a case here — an empty `cameras` array simply
     *  produces an empty [Result], same as Overpass. The parse itself is [parseCamerasResponse],
     *  split out so it is unit-testable without a MockEngine — same reason [Capabilities.parse]
     *  is split from [Capabilities.fetch]. */
    private suspend fun nearViaBackend(base: String, center: LatLon, radiusMeters: Double): Result {
        val degLat = radiusMeters / 111_320.0
        val degLon = radiusMeters / (111_320.0 * kotlin.math.cos(center.lat * kotlin.math.PI / 180))
        val url = "$base/api/cameras?minLat=${center.lat - degLat}&minLon=${center.lon - degLon}" +
            "&maxLat=${center.lat + degLat}&maxLon=${center.lon + degLon}"
        val body = jsonObjectOf(RoadRoulette.rawGet(url, headers = RoutingServer.userAgentHeaders()))
        return parseCamerasResponse(body)
    }

    /**
     * The pure half of [nearViaBackend]: turns one `/api/cameras` response body into a [Result].
     *
     * `Section`/`AverageSpeedZone` kinds carry a `polyline` — folded down to the two endpoints
     * [Section] actually needs, with [Section.spanMeters] summed along every leg of the polyline
     * rather than the straight-line endpoint distance, since a real road curves. A polyline with
     * fewer than two points is dropped rather than producing a zero-length section. `RedLight` and
     * `SpeedAndRedLight` map to the matching [CameraKind]; every other kind string (`FixedSpeed`,
     * `MobileHotspot`, and anything this client doesn't yet recognise) falls through to
     * [CameraKind.SPEED] — the same "unknown reads as the safe default" rule
     * `docs/BACKEND_SPEC.md` §15.5 states for the rest of this wire.
     */
    // internal, not private, so commonTest can feed it canned response bodies: nearViaBackend
    // itself needs a live backend, the same reason near()'s other helpers cannot be tested.
    internal fun parseCamerasResponse(body: JsonObject): Result {
        val cameras = ArrayList<Camera>()
        val sections = ArrayList<Section>()
        for (el in (body.optArray("cameras") ?: JsonArrayEmpty).objects()) {
            val lat = el.optDouble("lat", Double.NaN)
            val polyline = el.optArray("polyline")
            val maxspeed = el.optDouble("maxSpeedKmh").takeIf { !it.isNaN() }
            when (el.optString("kind")) {
                "Section", "AverageSpeedZone" -> if (polyline != null && polyline.size >= 2) {
                    val pts = polyline.arrays().map { LatLon(it.optDouble(0), it.optDouble(1)) }
                    val span = pts.zipWithNext { a, b -> RoadRoulette.distanceMeters(a, b) }.sum()
                    sections.add(Section(listOf(pts.first()), listOf(pts.last()), span, maxspeed))
                }
                "RedLight" -> if (!lat.isNaN()) cameras.add(Camera(LatLon(lat, el.optDouble("lon")), maxspeed, CameraKind.RED_LIGHT))
                "SpeedAndRedLight" -> if (!lat.isNaN()) cameras.add(Camera(LatLon(lat, el.optDouble("lon")), maxspeed, CameraKind.COMBINED))
                else -> if (!lat.isNaN()) cameras.add(Camera(LatLon(lat, el.optDouble("lon")), maxspeed, CameraKind.SPEED))
            }
        }
        return Result(cameras, sections)
    }

    /**
     * Null on network error; an empty [Result] means the area really has
     * none. The Overpass fetch's own network/parse failures are caught
     * below and turned into that null, but this still carries
     * `@Throws(Exception::class)` — see [SyncClient.sync]'s doc — because
     * the JSON walk after the fetch (parsing elements into cameras and
     * sections) is not inside that same catch and a malformed-but-still-JSON
     * response could throw out of it.
     *
     * Overpass-only. [near] is the entry point every caller should use — it
     * tries the backend's own `/api/cameras` first (when the deployment
     * announces one, issue #303) and only reaches here once that path, and
     * the disk cache behind it, both come up empty.
     */
    @Throws(Exception::class)
    suspend fun nearViaOverpass(
        center: LatLon,
        radiusMeters: Double = PREFETCH_RADIUS_M,
    ): Result? {
        val r = radiusMeters.toInt()
        // The whole budget per mirror, not a slice, for the reason
        // [RoadRoulette.overpassWays] gives: this query is heavy — two
        // `around` searches over a 4 km radius, one of them for relations with
        // `out geom` — and a slice that expires mid-answer is not a fast
        // failure, it is a wrong one. `near` returning null leaves
        // [speedSections] empty, and an empty section list cannot arm a
        // measurement at all, so the readout simply never appears.
        //
        // Measured on 2026-09-09 against both configured mirrors: this exact
        // query answered in **9.5 s** and **28 s** on `kumi.systems` and was
        // refused outright by `overpass-api.de`, against a 6 s slice — so no
        // trajectcontrole could arm on that network for as long as the load
        // lasted, while the *lighter* speed-limit query kept succeeding and the
        // sign stayed on screen. Earlier the same evening the same query
        // answered in 0.44 s, which is what makes this intermittent and is why
        // maxke24/Detour#22 reads as "sometimes it triggers".
        val query = "[out:json][timeout:${RoadRoulette.QUERY_BUDGET_MS / 1000}];(" +
            "node(around:$r,${center.lat},${center.lon})[\"highway\"=\"speed_camera\"];" +
            "relation(around:$r,${center.lat},${center.lon})[\"enforcement\"=\"average_speed\"];" +
            "relation(around:$r,${center.lat},${center.lon})[\"enforcement\"=\"traffic_signals\"];" +
            "relation(around:$r,${center.lat},${center.lon})[\"enforcement\"=\"maxspeed\"];" +
            ");out geom;"
        // A busy Overpass answers 200 with an HTML "runtime error" page, so the
        // parse can fail on a perfectly good HTTP response. Both are the same
        // thing to the caller — no data this time — and letting a JSONException
        // out would kill the collector that drives the prefetch for good.
        val elements = try {
            val key = cacheKey("cameras", center, radiusMeters)
            jsonObjectOf(
                OverpassCache.fetch(key) { RoadRoulette.rawQuery(query, timeoutMs = RoadRoulette.QUERY_BUDGET_MS) },
            ).optArray("elements") ?: JsonArrayEmpty
        } catch (e: IOException) {
            return null
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            // parseToJsonElement rejects the HTML error page before it is even
            // a JSON value, which surfaces here rather than as Serialization.
            return null
        }
        val cameras = ArrayList<Camera>()
        val avgSpeedRelations = ArrayList<JsonObject>()
        val redLightRelations = ArrayList<JsonObject>()
        val maxspeedRelations = ArrayList<JsonObject>()
        // Two passes, deliberately: [parseSection], [foldMaxspeedRelations] and
        // [withRedLightKind] all resolve a relation against the device nodes,
        // and the answer is not ordered, so every node has to be read before
        // the first relation is.
        for (el in elements.objects()) {
            when (el.optString("type")) {
                "node" -> {
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        cameras.add(Camera(LatLon(lat, lon), maxspeedOf(el), facingDeg = facingDegOf(el)))
                    }
                }
                "relation" -> when (el.optObject("tags")?.optString("enforcement")) {
                    "average_speed" -> avgSpeedRelations.add(el)
                    "traffic_signals" -> redLightRelations.add(el)
                    "maxspeed" -> maxspeedRelations.add(el)
                }
            }
        }
        val sections = avgSpeedRelations.mapNotNull { parseSection(it, cameras) }
        // Limits before kinds: [foldMaxspeedRelations] writes onto the camera
        // nodes the answer actually carried, and [withRedLightKind] may append
        // devices those relations never named. Folding second would mean a
        // relation-only red-light marker could pick up a neighbouring gantry's
        // posted limit, which is not its to claim.
        foldMaxspeedRelations(maxspeedRelations, cameras)
        return Result(withRedLightKind(cameras, redLightRelations), sections)
    }

    /**
     * The two ends of the section, from the relation's node members, which
     * `out geom` prints inline with their coordinates.
     *
     * Roles are no help: real relations carry `from`/`to`/`device` in any
     * combination (some have two `from` nodes and no `to`), and a `force` node
     * can sit mid-section. Geometry is unambiguous instead — the two nodes
     * furthest apart are the ends, every other node belongs to whichever of
     * those it is next to, and anything in between is dropped. Treating a
     * mid-section node as an end used to stop the measurement short of the
     * real one.
     */
    // internal, not private, so commonTest can feed it a relation literal:
    // [near] is the only caller and it cannot be tested without Overpass.
    internal fun parseSection(
        relation: JsonObject,
        cameras: List<Camera> = emptyList(),
    ): Section? {
        val members = relation.optArray("members") ?: return null
        val nodes = ArrayList<LatLon>()
        for (m in members.objects()) {
            if (m.optString("type") != "node") continue
            val lat = m.optDouble("lat", Double.NaN)
            val lon = m.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) nodes.add(LatLon(lat, lon))
        }
        if (nodes.size < 2) return null
        var a = nodes[0]
        var b = nodes[1]
        var span = 0.0
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val d = RoadRoulette.distanceMeters(nodes[i], nodes[j])
            if (d > span) { span = d; a = nodes[i]; b = nodes[j] }
        }
        if (span < MIN_SPAN_M) return null
        val endA = nodes.filter { RoadRoulette.distanceMeters(it, a) <= END_CLUSTER_M }
        val endB = nodes.filter { RoadRoulette.distanceMeters(it, b) <= END_CLUSTER_M }
        // The relation's own tag first, then the gantry nodes'. Neither real E40
        // relation tags one, so relation-only is what left the running average
        // with nothing to judge against on the road it was developed on.
        val maxspeed = maxspeedOf(relation) ?: deviceMaxspeed(endA + endB, cameras)
        return Section(endA, endB, span, maxspeed)
    }

    /**
     * Folds `traffic_signals` enforcement relations onto [cameras]: a device
     * member within [SAME_NODE_M] of an existing camera upgrades its kind
     * ([CameraKind.SPEED] to [CameraKind.COMBINED]); one with no match at all
     * gets its own [CameraKind.RED_LIGHT] marker. A device matched by more than
     * one relation, or by more than one member of the same relation, still
     * produces exactly one marker — the same resolution [parseSection] already
     * does for average-speed devices, reused rather than reinvented.
     */
    // internal, not private, so commonTest can feed it relation literals:
    // [near] is the only caller and it cannot be tested without Overpass.
    internal fun withRedLightKind(
        cameras: List<Camera>,
        redLightRelations: List<JsonObject>,
    ): List<Camera> {
        val result = cameras.toMutableList()
        val addedRedLights = ArrayList<LatLon>()
        for (relation in redLightRelations) {
            for (m in (relation.optArray("members") ?: continue).objects()) {
                if (m.optString("type") != "node") continue
                val lat = m.optDouble("lat", Double.NaN)
                val lon = m.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val at = LatLon(lat, lon)
                val existing = result.indexOfFirst {
                    RoadRoulette.distanceMeters(it.at, at) <= SAME_NODE_M
                }
                if (existing >= 0) {
                    if (result[existing].kind == CameraKind.SPEED) {
                        result[existing] = result[existing].copy(kind = CameraKind.COMBINED)
                    }
                } else if (addedRedLights.none { RoadRoulette.distanceMeters(it, at) <= SAME_NODE_M }) {
                    addedRedLights.add(at)
                    result.add(Camera(at, kind = CameraKind.RED_LIGHT))
                }
            }
        }
        return result
    }

    /** The `maxspeed` tag on an element, in km/h, or null when it has none we
     *  can read. Shared by camera nodes and enforcement relations — the tag is
     *  the same tag and [RoadRoulette.parseMaxSpeed] handles both spellings. */
    private fun maxspeedOf(el: JsonObject): Double? =
        el.optObject("tags")?.optString("maxspeed")
            ?.takeIf { it.isNotBlank() }
            ?.let { RoadRoulette.parseMaxSpeed(it) }

    /** The compass bearing a camera node faces, `camera:direction` falling back
     *  to `direction` — see [Camera.facingDeg] for why cardinal spellings are
     *  skipped rather than parsed. */
    private fun facingDegOf(el: JsonObject): Double? {
        val tags = el.optObject("tags") ?: return null
        return (tags.optString("camera:direction") ?: tags.optString("direction"))
            ?.takeIf { it.isNotBlank() }
            ?.toDoubleOrNull()
    }

    /** The limit tagged on the section's own gantry nodes, if any of them carry
     *  one. The two ends of a trajectcontrole post the same limit, so the first
     *  one found is the answer rather than something to reconcile. */
    private fun deviceMaxspeed(ends: List<LatLon>, cameras: List<Camera>): Double? =
        cameras.firstOrNull { cam ->
            cam.maxspeedKmh != null &&
                ends.any { RoadRoulette.distanceMeters(it, cam.at) <= SAME_NODE_M }
        }?.maxspeedKmh

    /**
     * The reverse of [deviceMaxspeed]: a fixed camera's limit is often tagged on
     * its `enforcement=maxspeed` *relation* rather than on the device node
     * itself. For each such relation with a `maxspeed` tag, fold it onto every
     * [cameras] entry that sits within [SAME_NODE_M] of one of the relation's
     * node members and doesn't already have its own tag - the node's own tag,
     * when present, is the more specific source and wins.
     */
    // internal, not private, so commonTest can feed it a relation literal - see
    // [parseSection]'s comment for why [near] itself cannot be tested.
    internal fun foldMaxspeedRelations(relations: List<JsonObject>, cameras: MutableList<Camera>) {
        for (relation in relations) {
            val maxspeed = maxspeedOf(relation) ?: continue
            val members = relation.optArray("members") ?: continue
            val deviceNodes = members.objects().mapNotNull { m ->
                if (m.optString("type") != "node") return@mapNotNull null
                val lat = m.optDouble("lat", Double.NaN)
                val lon = m.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) null else LatLon(lat, lon)
            }
            for (i in cameras.indices) {
                val cam = cameras[i]
                if (cam.maxspeedKmh == null &&
                    deviceNodes.any { RoadRoulette.distanceMeters(it, cam.at) <= SAME_NODE_M }
                ) {
                    cameras[i] = cam.copy(maxspeedKmh = maxspeed)
                }
            }
        }
    }

    /** A relation member and the node element it refers to are the same OSM node
     *  printed twice, so this only has to absorb float formatting — not a
     *  neighbouring camera, which at a gantry can be 14 m away. */
    private const val SAME_NODE_M = 5.0

    /** How far from the outermost node another node still counts as the same
     *  end of the section — the per-carriageway pairs sit metres apart. */
    private const val END_CLUSTER_M = 120.0

    /** Shorter than this and the relation is mis-mapped, not a section. */
    private const val MIN_SPAN_M = 200.0
}
