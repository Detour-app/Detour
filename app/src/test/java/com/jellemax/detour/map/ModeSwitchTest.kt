package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the rule `MapScreen.selectMode` used to state as six assignments in a
 * row: what a change of travel mode invalidates.
 *
 * The interesting cases are the two that six assignments could not express —
 * the no-op when the mode has not actually changed, and the convoy round that
 * must be cleared only when there is one.
 */
class ModeSwitchTest {

    @Test
    fun selectingTheModeYouAreAlreadyInChangesNothing() {
        // The guard `selectMode` opened with. Without it, tapping the mode you
        // are already in wipes the spin you just did.
        assertNull(modeSwitch(TravelMode.MOTO, TravelMode.MOTO, hasSpinOffer = false))
        assertNull(modeSwitch(TravelMode.CAR, TravelMode.CAR, hasSpinOffer = true))
    }

    @Test
    fun aRealSwitchClearsTheSpinsModeSpecificParts() {
        // The loop route and the candidate spread were planned on one mode's
        // profile; the radius bounds are per-mode. A concrete destination is
        // not here — it survives, because the navigation dock (#254) is where
        // a rider switches Moto/Car for a place they have already picked.
        val s = modeSwitch(TravelMode.MOTO, TravelMode.CAR, hasSpinOffer = false)!!
        assertNull(s.route)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun theRadiusResetsToTheModesOwnDefaultAndTheFloorDrops() {
        for (to in TravelMode.entries) {
            val from = TravelMode.entries.first { it != to }
            val s = modeSwitch(from, to, hasSpinOffer = false)!!
            // Per-mode, which is why the radius cannot simply be carried over:
            // a moto default is not a car default.
            assertEquals(to.defaultKm, s.radiusKm, 1e-6f)
            assertEquals(0f, s.minRadiusKm, 1e-6f)
        }
    }

    @Test
    fun aConvoyRoundIsClearedOnlyWhenOneIsOpen() {
        // A spin's candidates are mode-specific, so switching away must not
        // leave a stale vote round on everyone else's screen — but with no
        // round open there is nothing to clear, and the caller should not
        // broadcast a needless clear.
        assertTrue(modeSwitch(TravelMode.MOTO, TravelMode.CAR, hasSpinOffer = true)!!.clearSpinOffer)
        assertFalse(modeSwitch(TravelMode.MOTO, TravelMode.CAR, hasSpinOffer = false)!!.clearSpinOffer)
    }
}
