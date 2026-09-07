package com.jellemax.detour.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Update sits under Hub, on the Settings row that owns the whole update flow (#277). */
class UpdateNavTest {

    @Test fun landsOnSettingsWithTheMapAndHubBehindIt() {
        assertEquals(
            listOf(Destination.Map, Destination.Hub, Destination.Settings),
            updateNotificationStack(),
        )
    }
}
