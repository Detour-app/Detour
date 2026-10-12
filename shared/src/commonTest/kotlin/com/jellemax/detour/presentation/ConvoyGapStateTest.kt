package com.jellemax.detour.presentation

import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.ConvoyGapDetector
import com.jellemax.detour.drive.ConvoyGapDetector.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConvoyGapStateTest {

    private val ann = RiderId("ann")
    private val bob = RiderId("bob")
    private val cat = RiderId("cat")
    private val me = RiderId("me")
    private val members = listOf(ann, bob, cat, me).map { GroupMember(it, it.value.uppercase(), "accepted", true) }

    private fun rider(behind: Boolean = false, stopped: Boolean = false, signalLost: Boolean = false) =
        ConvoyGapDetector.Rider(
            last = ConvoyGapDetector.Fix(ann, LatLon(0.0, 0.0), 0.0, 0L),
            behind = behind, stopped = stopped, signalLost = signalLost,
        )

    @Test
    fun `one line per alerting rider with the worst alert winning in member order and never me`() {
        val riders = mapOf(
            cat to rider(behind = true, stopped = true),
            ann to rider(behind = true, signalLost = true),
            bob to rider(),
            me to rider(stopped = true),
        )
        assertEquals(
            listOf(
                ConvoyGapLine(ann, "ANN", ConvoyGapKind.SIGNAL_LOST),
                ConvoyGapLine(cat, "CAT", ConvoyGapKind.STOPPED),
            ),
            convoyGapLinesFrom(riders, members, me),
        )
    }

    @Test
    fun `a rider the member list cannot name yet gets no line`() {
        assertEquals(emptyList(), convoyGapLinesFrom(mapOf(RiderId("new") to rider(behind = true)), members, me))
    }

    @Test
    fun `alerts are spoken by name and rejoining is not spoken`() {
        assertEquals("BOB fell behind", convoyGapSpokenLine(Event.FellBehind(bob), members))
        assertEquals("BOB stopped", convoyGapSpokenLine(Event.Stopped(bob), members))
        assertEquals("Lost BOB's signal", convoyGapSpokenLine(Event.SignalLost(bob), members))
        assertNull(convoyGapSpokenLine(Event.Rejoined(bob), members))
    }
}
