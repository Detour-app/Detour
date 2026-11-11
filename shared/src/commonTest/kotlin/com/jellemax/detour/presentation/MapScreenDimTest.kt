package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [mapScreenDimmed]: when the off-route map drops to [MAP_DIM_BRIGHTNESS] (#902). */
class MapScreenDimTest {

    private fun dimmed(
        enabled: Boolean = true,
        navigating: Boolean = false,
        dialogOpen: Boolean = false,
        ms: Long = MAP_DIM_AFTER_MS,
    ) = mapScreenDimmed(enabled, navigating, dialogOpen, ms)

    @Test fun idleOffRouteMapDimsAtTheThreshold() {
        assertTrue(dimmed(ms = MAP_DIM_AFTER_MS))
        assertTrue(dimmed(ms = MAP_DIM_AFTER_MS + 60_000))
    }

    @Test fun underTheThresholdDoesNotDim() {
        assertFalse(dimmed(ms = 0))
        assertFalse(dimmed(ms = MAP_DIM_AFTER_MS - 1))
    }

    @Test fun navigatingNeverDims() {
        assertFalse(dimmed(navigating = true, ms = Long.MAX_VALUE))
    }

    @Test fun anOpenDialogNeverDims() {
        assertFalse(dimmed(dialogOpen = true, ms = Long.MAX_VALUE))
    }

    @Test fun settingOffNeverDims() {
        assertFalse(dimmed(enabled = false, ms = Long.MAX_VALUE))
    }
}
