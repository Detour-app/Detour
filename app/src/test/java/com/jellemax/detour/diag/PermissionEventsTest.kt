package com.jellemax.detour.diag

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The detail line #822's PERMISSION events carry. */
class PermissionEventsTest {

    @Test
    fun namesEveryTrackerPermissionOnTiramisu() {
        val held = setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        assertEquals(
            "fine=granted coarse=granted bg=denied ar=granted notif=denied",
            permissionDetail(33) { it in held },
        )
    }

    @Test
    fun leavesOutPermissionsTheSdkDoesNotHave() {
        // Pie: no background location, activity recognition or notification permission.
        assertEquals(
            "fine=denied coarse=granted",
            permissionDetail(28) { it == Manifest.permission.ACCESS_COARSE_LOCATION },
        )
    }

    @Test
    fun qAddsBackgroundAndActivityRecognitionButNotNotifications() {
        assertEquals("fine=granted coarse=granted bg=granted ar=granted", permissionDetail(29) { true })
    }
}
