package com.jellemax.detour.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * Speed cameras and average-speed sections near you, from OpenStreetMap data
 * served by this deployment's own backend (issue #303).
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
     * Cameras/sections near [center]: the backend's own dataset (issue #303), when this
     * deployment has announced a camera-data endpoint on `/api/capabilities`; a disk cache from
     * an earlier successful fetch when the backend is reachable-in-principle but this call
     * failed. An install that has announced nothing at all returns null — there is no
     * third-party fallback.
     */
    @Throws(Exception::class)
    suspend fun near(
        center: LatLon,
        radiusMeters: Double = PREFETCH_RADIUS_M,
    ): Result? {
        val backendBase = RoutingServer.camerasBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return SpeedCameraStore.load(center, radiusMeters)

        val fromBackend = try {
            nearViaBackend(backendBase, center, radiusMeters)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            // A misbehaving reverse proxy in front of a self-host can answer 200 with an HTML
            // page, which parseToJsonElement rejects before it is even a JSON value — this must
            // fall through to the disk cache same as any other backend failure.
            null
        }
        // An empty answer reads the same as a miss here: the deployed self-host can have data for
        // only part of the world (Luxembourg-only today), and a 200 with an empty `cameras` array
        // is exactly what every rider outside that coverage gets. Caching that emptiness for
        // TTL_MS would wipe every marker they already have and keep them wiped for a day, so it
        // must fall through to the disk cache the same as a failed fetch — and must never itself
        // get written to disk, where it could stomp a previously-cached non-empty tile.
        if (fromBackend != null && isUsable(fromBackend)) {
            SpeedCameraStore.save(center, radiusMeters, fromBackend)
            return fromBackend
        }
        // Backend announced but unreachable (or answered empty) this time: the disk cache
        // survives the outage per issue #303, so a rider on a self-host with a blipped or
        // not-yet-covering camera-data endpoint still sees yesterday's markers.
        return SpeedCameraStore.load(center, radiusMeters)
    }

    /** The bbox fetch against this deployment's own camera-data endpoint (issue #303) — the
     *  wire shape is [Task 3's `CamerasBboxResponse`/`CameraDto`]
     *  (`backend/Detour/Detour.Api/Contracts/CameraContracts.cs`). Null on a malformed-but-parsed
     *  body with no usable `elements` is not a case here — an empty `cameras` array simply
     *  produces an empty [Result], same as Overpass. The parse itself is [parseCamerasResponse],
     *  split out so it is unit-testable without a MockEngine — same reason [Capabilities.parse]
     *  is split from [Capabilities.fetch]. */
    private suspend fun nearViaBackend(base: String, center: LatLon, radiusMeters: Double): Result {
        val bbox = RoadRoulette.bboxDegrees(center, radiusMeters)
        val url = "$base/api/cameras?minLat=${bbox.minLat}&minLon=${bbox.minLon}" +
            "&maxLat=${bbox.maxLat}&maxLon=${bbox.maxLon}"
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
     * Whether [result] is worth caching or returning as final, rather than a miss [near] should
     * fall through past — see its own doc for why an empty backend answer must not be treated as
     * "this region genuinely has no cameras". Split out the same way [parseCamerasResponse] is so
     * it can be tested without a live backend.
     */
    internal fun isUsable(result: Result): Boolean = result.cameras.isNotEmpty() || result.sections.isNotEmpty()
}
