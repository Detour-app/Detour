package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SpeedCameras.withRedLightKind] - folding `traffic_signals` enforcement
 * relations onto the plain camera list, per maxke24/Detour#317. Literal
 * relation JSON, same shape `parseSection` is already tested against: `near`
 * is the only caller and it cannot be exercised without Overpass.
 */
class SpeedCamerasTest {

    private fun relation(vararg nodes: Pair<Double, Double>): kotlinx.serialization.json.JsonObject {
        val members = nodes.joinToString(",") { (lat, lon) ->
            """{"type":"node","lat":$lat,"lon":$lon}"""
        }
        return jsonObjectOf("""{"type":"relation","members":[$members]}""")
    }

    /** No matching camera node: the relation's own device geometry is the only
     *  source of the marker, per the issue's "tagging is inconsistent" note -
     *  a relation with usable geometry and no device node must still produce
     *  one. */
    @Test
    fun aDeviceWithNoMatchingNodeGetsItsOwnRedLightMarker() {
        val result = SpeedCameras.withRedLightKind(
            cameras = emptyList(),
            redLightRelations = listOf(relation(50.85 to 4.35)),
        )
        assertEquals(1, result.size)
        assertEquals(SpeedCameras.CameraKind.RED_LIGHT, result[0].kind)
        assertEquals(LatLon(50.85, 4.35), result[0].at)
    }

    /** The common case: the relation's device member is the same node the
     *  `highway=speed_camera` query already returned. It upgrades in place
     *  rather than adding a second marker. */
    @Test
    fun aDeviceMatchingAnExistingSpeedCameraBecomesCombined() {
        val speedCam = SpeedCameras.Camera(LatLon(50.85, 4.35), maxspeedKmh = 50.0)
        val result = SpeedCameras.withRedLightKind(
            cameras = listOf(speedCam),
            redLightRelations = listOf(relation(50.85 to 4.35)),
        )
        assertEquals(1, result.size)
        assertEquals(SpeedCameras.CameraKind.COMBINED, result[0].kind)
        // The speed-camera fields survive the upgrade - only kind changes.
        assertEquals(50.0, result[0].maxspeedKmh)
    }

    /** A camera the query returned but no relation names stays SPEED,
     *  unaffected - the common case, and #317's own acceptance bar. */
    @Test
    fun anUnrelatedSpeedCameraIsUnaffected() {
        val speedCam = SpeedCameras.Camera(LatLon(50.85, 4.35))
        val result = SpeedCameras.withRedLightKind(
            cameras = listOf(speedCam),
            redLightRelations = listOf(relation(51.0 to 4.5)),
        )
        assertEquals(SpeedCameras.CameraKind.SPEED, result.first { it.at == speedCam.at }.kind)
        assertEquals(2, result.size)
    }

    /** Two relations (or two members of one relation) naming the same device
     *  still produce exactly one marker - #317's dedup acceptance criterion. */
    @Test
    fun theSameDeviceNamedByTwoRelationsProducesOneMarker() {
        val result = SpeedCameras.withRedLightKind(
            cameras = emptyList(),
            redLightRelations = listOf(relation(50.85 to 4.35), relation(50.85 to 4.35)),
        )
        assertEquals(1, result.size)
        assertEquals(SpeedCameras.CameraKind.RED_LIGHT, result[0].kind)
    }

    /** An already-COMBINED camera (from an earlier relation) isn't reset back
     *  to RED_LIGHT by a later relation matching it again. */
    @Test
    fun anAlreadyCombinedCameraStaysCombined() {
        val speedCam = SpeedCameras.Camera(LatLon(50.85, 4.35), maxspeedKmh = 50.0)
        val result = SpeedCameras.withRedLightKind(
            cameras = listOf(speedCam),
            redLightRelations = listOf(relation(50.85 to 4.35), relation(50.85 to 4.35)),
        )
        assertEquals(1, result.size)
        assertEquals(SpeedCameras.CameraKind.COMBINED, result[0].kind)
    }

    @Test
    fun noRedLightRelationsLeavesCamerasUntouched() {
        val speedCam = SpeedCameras.Camera(LatLon(50.85, 4.35))
        val result = SpeedCameras.withRedLightKind(cameras = listOf(speedCam), redLightRelations = emptyList())
        assertEquals(listOf(speedCam), result)
    }

    // --- parseCamerasResponse (issue #303 backend bbox response) --------------
    //
    // The wire shape is Task 3's CamerasBboxResponse/CameraDto
    // (backend/Detour/Detour.Api/Contracts/CameraContracts.cs): camelCase JSON,
    // `kind` one of the backend's CameraKind enum names. `near`/`nearViaBackend`
    // cannot be exercised here (need a live backend, same reason `near` via
    // Overpass can't be either) - this is the pure half that can.

    @Test
    fun aSectionKindWithARealPolylineBecomesASectionSpanningItsLength() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"1","kind":"Section","lat":null,"lon":null,
                "polyline":[[50.85,4.35],[50.86,4.35],[50.87,4.35]],
                "maxSpeedKmh":120,"roadRef":"E40"}]}""",
        )
        val result = SpeedCameras.parseCamerasResponse(body)
        assertEquals(0, result.cameras.size)
        assertEquals(1, result.sections.size)
        val section = result.sections[0]
        assertEquals(listOf(LatLon(50.85, 4.35)), section.endA)
        assertEquals(listOf(LatLon(50.87, 4.35)), section.endB)
        assertEquals(120.0, section.maxspeedKmh)
        // Summed along both legs of the polyline, not the straight-line
        // endpoint distance - a real road curves between the two ends.
        val leg1 = RoadRoulette.distanceMeters(LatLon(50.85, 4.35), LatLon(50.86, 4.35))
        val leg2 = RoadRoulette.distanceMeters(LatLon(50.86, 4.35), LatLon(50.87, 4.35))
        assertEquals(leg1 + leg2, section.spanMeters, 0.001)
    }

    @Test
    fun aRedLightKindBecomesARedLightCamera() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"2","kind":"RedLight","lat":50.85,"lon":4.35,
                "polyline":null,"maxSpeedKmh":null,"roadRef":null}]}""",
        )
        val result = SpeedCameras.parseCamerasResponse(body)
        assertEquals(1, result.cameras.size)
        assertEquals(0, result.sections.size)
        assertEquals(SpeedCameras.CameraKind.RED_LIGHT, result.cameras[0].kind)
        assertEquals(LatLon(50.85, 4.35), result.cameras[0].at)
        assertEquals(null, result.cameras[0].maxspeedKmh)
    }

    @Test
    fun aSpeedAndRedLightKindBecomesACombinedCamera() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"3","kind":"SpeedAndRedLight","lat":50.9,"lon":4.4,
                "polyline":null,"maxSpeedKmh":50,"roadRef":null}]}""",
        )
        val result = SpeedCameras.parseCamerasResponse(body)
        assertEquals(1, result.cameras.size)
        assertEquals(SpeedCameras.CameraKind.COMBINED, result.cameras[0].kind)
        assertEquals(50.0, result.cameras[0].maxspeedKmh)
    }

    /** `FixedSpeed`/`MobileHotspot`, and anything this client doesn't yet
     *  recognise, all fall through to [SpeedCameras.CameraKind.SPEED] - the
     *  "unknown reads as the safe default" rule `docs/BACKEND_SPEC.md` §15.5
     *  states for the rest of this wire. */
    @Test
    fun anUnrecognisedKindFallsThroughToPlainSpeed() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"4","kind":"FixedSpeed","lat":50.8,"lon":4.3,
                "polyline":null,"maxSpeedKmh":90,"roadRef":null},
                {"id":"5","kind":"SomeFutureKindThisClientDoesNotKnowYet","lat":50.81,"lon":4.31,
                "polyline":null,"maxSpeedKmh":null,"roadRef":null}]}""",
        )
        val result = SpeedCameras.parseCamerasResponse(body)
        assertEquals(2, result.cameras.size)
        assertEquals(SpeedCameras.CameraKind.SPEED, result.cameras[0].kind)
        assertEquals(SpeedCameras.CameraKind.SPEED, result.cameras[1].kind)
    }

    @Test
    fun anEmptyCamerasArrayProducesAnEmptyResult() {
        val body = jsonObjectOf("""{"cameras":[]}""")
        val result = SpeedCameras.parseCamerasResponse(body)
        assertEquals(0, result.cameras.size)
        assertEquals(0, result.sections.size)
    }

    /** A `Section`/`AverageSpeedZone` with fewer than two polyline points is
     *  dropped rather than producing a zero-length section. */
    @Test
    fun aSectionWithTooShortAPolylineIsDropped() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"6","kind":"AverageSpeedZone","lat":null,"lon":null,
                "polyline":[[50.85,4.35]],"maxSpeedKmh":100,"roadRef":null}]}""",
        )
        val result = SpeedCameras.parseCamerasResponse(body)
        assertEquals(0, result.cameras.size)
        assertEquals(0, result.sections.size)
    }

    // --- isUsable (near()'s empty-answer-is-a-miss guard) ----------------------
    //
    // `near` itself needs a live backend and can't be exercised here (same reason as
    // `nearViaBackend`), but the decision it makes off a fetched Result is this pure
    // function - split out the same way parseCamerasResponse is, for the same reason.

    /** A 200 with an empty `cameras` array - the deployed self-host outside its covered
     *  region, per issue #303 - must not be treated as "this area really has no cameras":
     *  `near` has to fall through to the disk cache/Overpass instead of caching it. */
    @Test
    fun anEmptyBackendResultIsNotUsable() {
        val result = SpeedCameras.parseCamerasResponse(jsonObjectOf("""{"cameras":[]}"""))
        assertFalse(SpeedCameras.isUsable(result))
    }

    @Test
    fun aBackendResultWithACameraIsUsable() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"7","kind":"RedLight","lat":50.85,"lon":4.35,
                "polyline":null,"maxSpeedKmh":null,"roadRef":null}]}""",
        )
        assertTrue(SpeedCameras.isUsable(SpeedCameras.parseCamerasResponse(body)))
    }

    /** A response with only a Section/AverageSpeedZone entry (no point cameras at all) is
     *  still usable - the guard must not key on `cameras` alone once the two are folded
     *  apart into [SpeedCameras.Result.cameras]/[SpeedCameras.Result.sections]. */
    @Test
    fun aBackendResultWithOnlyASectionIsUsable() {
        val body = jsonObjectOf(
            """{"cameras":[{"id":"8","kind":"Section","lat":null,"lon":null,
                "polyline":[[50.85,4.35],[50.86,4.35]],"maxSpeedKmh":120,"roadRef":null}]}""",
        )
        assertTrue(SpeedCameras.isUsable(SpeedCameras.parseCamerasResponse(body)))
    }
}
