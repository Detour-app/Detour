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
 * themselves such nodes; the relation carries the posted `maxspeed`. We fetch
 * the individual camera nodes (for the map markers and the over-speed chime)
 * and the enforcement relations (whose device coordinates let us tell when you
 * enter and leave a section, so the average can be timed) in one request.
 *
 * Same prefetch shape as [RoadRoulette.speedLimitWays]: fetched once for a wide
 * area, refreshed only as you near the edge of what you already have, so there
 * is no network round-trip per fix. [near] returns null on any network error —
 * cameras are an overlay, never something the drive depends on, and a null lets
 * the caller keep the markers it already has instead of flickering them off.
 */
object SpeedCameras {

    /**
     * One camera to draw on the map.
     *
     * [maxspeedKmh] is the limit tagged on the camera node itself, when it has
     * one. Mappers put it there far more often than on the enforcement relation
     * — both real E40 trajectcontrole relations tag no `maxspeed` at all and
     * carry the 120 on their device nodes — so a section that reads its limit
     * only off the relation gets nothing to judge its average against.
     */
    data class Camera(val at: LatLon, val maxspeedKmh: Double? = null)

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
     * Null on network error; an empty [Result] means the area really has
     * none. The Overpass fetch's own network/parse failures are caught
     * below and turned into that null, but this still carries
     * `@Throws(Exception::class)` — see [SyncClient.sync]'s doc — because
     * the JSON walk after the fetch (parsing elements into cameras and
     * sections) is not inside that same catch and a malformed-but-still-JSON
     * response could throw out of it.
     */
    @Throws(Exception::class)
    suspend fun near(
        center: LatLon,
        radiusMeters: Double = PREFETCH_RADIUS_M,
    ): Result? {
        val r = radiusMeters.toInt()
        val query = "[out:json][timeout:20];(" +
            "node(around:$r,${center.lat},${center.lon})[\"highway\"=\"speed_camera\"];" +
            "relation(around:$r,${center.lat},${center.lon})[\"enforcement\"=\"average_speed\"];" +
            ");out geom;"
        // A busy Overpass answers 200 with an HTML "runtime error" page, so the
        // parse can fail on a perfectly good HTTP response. Both are the same
        // thing to the caller — no data this time — and letting a JSONException
        // out would kill the collector that drives the prefetch for good.
        val elements = try {
            jsonObjectOf(RoadRoulette.rawQuery(query)).optArray("elements") ?: JsonArrayEmpty
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
        val relations = ArrayList<JsonObject>()
        // Two passes, deliberately: [parseSection] resolves a section's limit off
        // its device nodes when the relation doesn't tag one, and the answer is
        // not ordered, so every node has to be read before the first relation is.
        for (el in elements.objects()) {
            when (el.optString("type")) {
                "node" -> {
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        cameras.add(Camera(LatLon(lat, lon), maxspeedOf(el)))
                    }
                }
                "relation" -> relations.add(el)
            }
        }
        val sections = relations.mapNotNull { parseSection(it, cameras) }
        return Result(cameras, sections)
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

    /** The `maxspeed` tag on an element, in km/h, or null when it has none we
     *  can read. Shared by camera nodes and enforcement relations — the tag is
     *  the same tag and [RoadRoulette.parseMaxSpeed] handles both spellings. */
    private fun maxspeedOf(el: JsonObject): Double? =
        el.optObject("tags")?.optString("maxspeed")
            ?.takeIf { it.isNotBlank() }
            ?.let { RoadRoulette.parseMaxSpeed(it) }

    /** The limit tagged on the section's own gantry nodes, if any of them carry
     *  one. The two ends of a trajectcontrole post the same limit, so the first
     *  one found is the answer rather than something to reconcile. */
    private fun deviceMaxspeed(ends: List<LatLon>, cameras: List<Camera>): Double? =
        cameras.firstOrNull { cam ->
            cam.maxspeedKmh != null &&
                ends.any { RoadRoulette.distanceMeters(it, cam.at) <= SAME_NODE_M }
        }?.maxspeedKmh

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
