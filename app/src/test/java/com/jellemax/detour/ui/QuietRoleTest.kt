package com.jellemax.detour.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietRoleTest {
    @Test
    fun aLayerIsEitherKeptOrHiddenNotBoth() {
        assertEquals(emptySet<String>(), QUIET_KEPT.keys.intersect(QUIET_HIDDEN.toSet()))
    }

    @Test
    fun theOnlyLabelsKeptAreTownNames() {
        val keptLabels = QUIET_KEPT.filterKeys { it.startsWith("label_") || it.startsWith("place_") }
        assertEquals(setOf(QuietRole.Town), keptLabels.values.toSet())
        assertTrue("label_village" in QUIET_HIDDEN && "place_country_major" in QUIET_HIDDEN)
    }

    @Test
    fun lightRoadsAreWhiteAndEveryRoleHasBothVariants() {
        assertEquals("#FFFFFF", QuietRole.Road.color(dark = false))
        val hex = Regex("#[0-9A-F]{6}")
        for (role in QuietRole.entries) {
            assertTrue(role.color(dark = false).matches(hex))
            assertTrue(role.color(dark = true).matches(hex))
            assertTrue("$role differs by theme", role.color(dark = false) != role.color(dark = true))
        }
    }
}
