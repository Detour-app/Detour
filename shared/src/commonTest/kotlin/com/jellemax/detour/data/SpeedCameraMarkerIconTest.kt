package com.jellemax.detour.data

import com.jellemax.detour.data.SpeedCameras.CameraKind
import com.jellemax.detour.data.SpeedCameras.MarkerIcon
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SpeedCameras.markerIcon] — issue #404: spot, section and red-light cameras must draw as
 * three different map icons, and a section's entry/exit devices are plain [CameraKind.SPEED]
 * nodes, so the section icon is decided by distance to a section end.
 */
class SpeedCameraMarkerIconTest {

    // A ~5.5 km section along a meridian; 0.0001° of latitude is ~11 m.
    private val entry = LatLon(50.8000, 4.3500)
    private val exit = LatLon(50.8500, 4.3500)
    private val section = SpeedCameras.Section(listOf(entry), listOf(exit), 5560.0, 120.0)

    private fun camera(at: LatLon, kind: CameraKind = CameraKind.SPEED) = SpeedCameras.Camera(at, kind = kind)

    @Test
    fun aSpeedCameraOnASectionEntryOrExitDrawsAsSection() {
        assertEquals(MarkerIcon.SECTION, SpeedCameras.markerIcon(camera(entry), listOf(section)),
            "#404: the entry device must use the section icon")
        assertEquals(MarkerIcon.SECTION, SpeedCameras.markerIcon(camera(exit), listOf(section)),
            "#404: the exit device must use the section icon")
    }

    @Test
    fun theOtherCarriagewaysDeviceAFewMetresOffTheEndStillDrawsAsSection() {
        // ~33 m north of the entry point: inside SECTION_GATE_METERS (60 m).
        val sideBySide = LatLon(50.8003, 4.3500)
        assertEquals(MarkerIcon.SECTION, SpeedCameras.markerIcon(camera(sideBySide), listOf(section)))
    }

    @Test
    fun aSpeedCameraOutsideEverySectionDrawsAsSpot() {
        // ~110 m past the entry, and midway along the section: neither is a section device.
        assertEquals(MarkerIcon.SPOT, SpeedCameras.markerIcon(camera(LatLon(50.8010, 4.3500)), listOf(section)),
            "#404: a camera outside any section must not use the section icon")
        assertEquals(MarkerIcon.SPOT, SpeedCameras.markerIcon(camera(LatLon(50.8250, 4.3500)), listOf(section)))
        assertEquals(MarkerIcon.SPOT, SpeedCameras.markerIcon(camera(entry), emptyList()))
    }

    @Test
    fun redLightAndCombinedDrawAsRedLightEvenAtASectionEnd() {
        assertEquals(MarkerIcon.RED_LIGHT, SpeedCameras.markerIcon(camera(entry, CameraKind.RED_LIGHT), listOf(section)))
        assertEquals(MarkerIcon.RED_LIGHT, SpeedCameras.markerIcon(camera(entry, CameraKind.COMBINED), listOf(section)))
        assertEquals(MarkerIcon.RED_LIGHT,
            SpeedCameras.markerIcon(camera(LatLon(51.0, 4.0), CameraKind.RED_LIGHT), emptyList()))
    }
}
