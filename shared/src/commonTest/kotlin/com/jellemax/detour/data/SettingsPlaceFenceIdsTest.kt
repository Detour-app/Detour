package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** [encodePlaceFenceIds]/[decodePlaceFenceIds] — the comma-joined round trip
 *  [Settings.registeredPlaceFenceIds]/[Settings.setRegisteredPlaceFenceIds]
 *  (issue #91) use, pulled out standalone for the same reason
 *  `encodeVehicleDevice`/`decodeVehicleDevice` are: `Settings.init()` needs a
 *  real `Prefs` backend this module's tests don't have. */
class SettingsPlaceFenceIdsTest {

    @Test
    fun emptySetRoundTrips() {
        assertEquals(emptySet(), decodePlaceFenceIds(encodePlaceFenceIds(emptySet())))
    }

    @Test
    fun aSetOfIdsRoundTrips() {
        val ids = setOf("c1:1:enter", "c1:1:exit", "c2:9:enter")
        assertEquals(ids, decodePlaceFenceIds(encodePlaceFenceIds(ids)))
    }

    @Test
    fun decodingTheStoredDefaultGivesAnEmptySet() {
        // "" is prefs.string's default when the key was never written.
        assertEquals(emptySet(), decodePlaceFenceIds(""))
    }
}
