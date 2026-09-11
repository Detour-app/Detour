package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
