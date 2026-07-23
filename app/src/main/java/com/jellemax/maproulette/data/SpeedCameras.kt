package com.jellemax.maproulette.data

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

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

    /** One camera to draw on the map. */
    data class Camera(val at: LatLon)

    /**
     * An average-speed section, as the two ends you can pass it through.
     *
     * [endA] and [endB] are the device clusters at either end — one node per
     * carriageway, a few metres apart — and [spanMeters] is the distance
     * between them. Which end is the entry depends on which way you drive, so
     * they are not labelled start/end here. [maxspeedKmh] is the posted limit
     * the average is judged against, when the relation tags one.
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

    /** Null on network error; an empty [Result] means the area really has none. */
    fun near(
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
            JSONObject(RoadRoulette.rawQuery(query)).getJSONArray("elements")
        } catch (e: IOException) {
            return null
        } catch (e: JSONException) {
            return null
        }
        val cameras = ArrayList<Camera>()
        val sections = ArrayList<Section>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "node" -> {
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) cameras.add(Camera(LatLon(lat, lon)))
                }
                "relation" -> parseSection(el)?.let { sections.add(it) }
            }
        }
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
    private fun parseSection(relation: JSONObject): Section? {
        val members = relation.optJSONArray("members") ?: return null
        val nodes = ArrayList<LatLon>()
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
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
        val maxspeed = relation.optJSONObject("tags")?.optString("maxspeed")
            ?.takeIf { it.isNotBlank() }?.let { RoadRoulette.parseMaxSpeed(it) }
        return Section(endA, endB, span, maxspeed)
    }

    /** How far from the outermost node another node still counts as the same
     *  end of the section — the per-carriageway pairs sit metres apart. */
    private const val END_CLUSTER_M = 120.0

    /** Shorter than this and the relation is mis-mapped, not a section. */
    private const val MIN_SPAN_M = 200.0
}
