package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.ConvoyGapDetector.Fix
import com.jellemax.detour.drive.ConvoyGapFeed
import com.jellemax.detour.drive.FriendPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConvoyGapTickTest {

    private val t0 = 1_700_000_000_000L
    private val host = RiderId("host")
    private val sam = RiderId("sam")
    private val kim = RiderId("kim")
    private val me = RiderId("me")
    private val at = LatLon(50.0, 4.0)
    private val members = listOf(host, sam, kim, me).map { GroupMember(it, it.value, "accepted", true) }
    private val convoy = Group("c1", "Sunday", "convoy", "accepted", members, ownerId = host)

    private fun peer(id: RiderId, tsMs: Long) =
        FriendPosition(id, at.lat, at.lon, headingDeg = null, speedKmh = 30.0, tsMs = tsMs, expiresAtMs = tsMs + 60_000L)

    /** Ticks once a second for [seconds], sam and kim silent after their first frame. */
    private fun runSilent(seconds: Int, convoy: Group?): List<ConvoyGapTick> {
        var state = ConvoyGapFeed.State()
        return (0 until seconds).map { s ->
            val now = t0 + s * 1_000L
            val tick = convoyGapTick(
                state, convoy, ConvoyGapSelf(me, Fix(me, at, 30.0, now)),
                listOf(peer(host, now), peer(sam, t0), peer(kim, t0)), emptyList(), now,
            )
            state = tick.state
            tick
        }
    }

    @Test
    fun `riders lost in the same tick are spoken as one utterance and both bannered`() {
        val ticks = runSilent(60, convoy)
        val spoken = ticks.mapNotNull { it.utterance }
        assertEquals(listOf("Lost sam's signal. Lost kim's signal"), spoken)
        assertEquals(
            listOf(
                ConvoyGapLine(sam, "sam", ConvoyGapKind.SIGNAL_LOST),
                ConvoyGapLine(kim, "kim", ConvoyGapKind.SIGNAL_LOST),
            ),
            ticks.last().lines,
        )
    }

    @Test
    fun `an unknown convoy gives no lines and leaves the state alone`() {
        val state = ConvoyGapFeed.State()
        val tick = convoyGapTick(state, null, ConvoyGapSelf(me, null), emptyList(), emptyList(), t0)
        assertEquals(ConvoyGapTick(state, emptyList(), null), tick)
    }

    @Test
    fun `a convoy whose host is unknown gets no alerts`() {
        val ticks = runSilent(60, convoy.copy(ownerId = null))
        assertEquals(emptyList(), ticks.last().lines)
        assertNull(ticks.firstNotNullOfOrNull { it.utterance })
    }
}
