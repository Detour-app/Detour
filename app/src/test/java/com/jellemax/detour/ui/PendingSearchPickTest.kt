package com.jellemax.detour.ui

import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The one-shot contract MapScreen leans on: a pick is published once and the
 * reader that applies it consumes it, so a later return to the map cannot
 * replay a stale destination.
 *
 * The holder is a process-wide singleton, so every test starts from a cleared
 * one — otherwise a leftover pick could make a later assertion pass for the
 * wrong reason depending on the order tests happen to run in.
 */
class PendingSearchPickTest {

    private val rotterdam = GeocodeResult("Rotterdam", LatLon(51.92, 4.48))
    private val utrecht = GeocodeResult("Utrecht", LatLon(52.09, 5.12))

    @Before
    fun reset() = PendingSearchPick.clear()

    @Test
    fun `set publishes the pick`() {
        PendingSearchPick.set(rotterdam)
        assertEquals(rotterdam, PendingSearchPick.result.value)
    }

    @Test
    fun `clear consumes it`() {
        PendingSearchPick.set(rotterdam)
        PendingSearchPick.clear()
        assertNull(PendingSearchPick.result.value)
    }

    @Test
    fun `a second set replaces the first, and one clear leaves nothing behind`() {
        PendingSearchPick.set(rotterdam)
        PendingSearchPick.set(utrecht)
        assertEquals(utrecht, PendingSearchPick.result.value)
        PendingSearchPick.clear()
        assertNull(PendingSearchPick.result.value)
    }
}
