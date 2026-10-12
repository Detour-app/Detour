package com.jellemax.detour.drive

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.ConvoyGapDetector.Event
import com.jellemax.detour.drive.ConvoyGapDetector.Fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Characterises [ConvoyGapFeed]: frames are judged on the receiving clock,
 *  only once, only from members, and never about this device itself. */
class ConvoyGapFeedTest {

    private val t0 = 1_700_000_000_000L
    private val host = RiderId("host")
    private val sam = RiderId("sam")
    private val me = RiderId("me")
    private val stranger = RiderId("stranger")
    private val roster = ConvoyGapFeed.Roster(setOf(host, sam, me), hostId = host, selfId = me)
    private val at = LatLon(50.0, 4.0)

    /** Steps once a second for [seconds], handing in whatever [frames] returns
     *  for that second, and returns every event with the second it fired. */
    private fun run(
        seconds: Int,
        rosterAt: (Int) -> ConvoyGapFeed.Roster = { roster },
        frames: (Int) -> List<Fix>,
    ): Pair<ConvoyGapFeed.State, List<Pair<Int, Event>>> {
        var state = ConvoyGapFeed.State()
        val events = mutableListOf<Pair<Int, Event>>()
        for (second in 0 until seconds) {
            val step = ConvoyGapFeed.step(state, frames(second), rosterAt(second), emptyList(), t0 + second * 1_000L)
            state = step.state
            step.events.forEach { events += second to it }
        }
        return state to events
    }

    @Test
    fun `a sender clock far behind ours does not read as a lost signal`() {
        // Sam's phone is ten minutes slow; his frames still arrive every second.
        val skew = 10 * 60_000L
        val (_, events) = run(60) { s -> listOf(Fix(sam, at, 30.0, t0 - skew + s * 1_000L)) }
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a frame seen again is not restamped so a quiet rider is lost once`() {
        // Sam sends for 5 s, then the relay keeps handing back his last frame.
        val (_, events) = run(60) { s -> listOf(Fix(sam, at, 30.0, t0 + minOf(s, 4) * 1_000L)) }
        assertEquals(listOf(34 to Event.SignalLost(sam)), events)
    }

    @Test
    fun `an older frame than the last one seen is dropped`() {
        val frames = { s: Int ->
            // From second 5 on, alongside the fresh frame, a stale one keeps
            // arriving; it must neither revive nor confuse anything.
            listOf(Fix(sam, at, 30.0, t0 + s * 1_000L)) +
                if (s >= 5) listOf(Fix(sam, at, 0.0, t0)) else emptyList()
        }
        val (state, events) = run(40, frames = frames)
        assertEquals(emptyList(), events)
        assertEquals(t0 + 39_000L, state.lastSenderTsMs[sam])
    }

    @Test
    fun `frames from outside the roster are ignored`() {
        val (state, events) = run(60) { listOf(Fix(stranger, at, 0.0, t0)) }
        assertEquals(emptyList(), events)
        assertTrue(stranger !in state.detector.riders)
        assertTrue(stranger !in state.lastSenderTsMs)
    }

    @Test
    fun `a rider who leaves is dropped and not reported lost`() {
        val withoutSam = roster.copy(members = setOf(host, me))
        val (state, events) = run(60, rosterAt = { s -> if (s < 5) roster else withoutSam }) { s ->
            if (s < 5) listOf(Fix(sam, at, 30.0, t0 + s * 1_000L)) else emptyList()
        }
        assertEquals(emptyList(), events)
        assertTrue(sam !in state.detector.riders)
    }

    @Test
    fun `alerts about this device are not raised but alerts about others are`() {
        // The host rides on; Sam and I both stop at second 0 and stay stopped.
        val (_, events) = run(30) { s ->
            listOf(
                Fix(host, LatLon(50.0, 4.0 + s * 0.0001), 30.0, t0 + s * 1_000L),
                Fix(sam, at, 0.0, t0 + s * 1_000L),
                Fix(me, at, 0.0, t0 + s * 1_000L),
            )
        }
        assertEquals(listOf<Pair<Int, Event>>(20 to Event.Stopped(sam)), events)
    }

    private fun convoy(ownerId: RiderId?) = Group(
        id = "c1",
        name = "Sunday loop",
        kind = "convoy",
        status = "accepted",
        members = listOf(
            GroupMember(host, "host", "accepted", sharing = true),
            GroupMember(sam, "sam", "accepted", sharing = true),
            GroupMember(me, "me", "accepted", sharing = true),
            GroupMember(stranger, "stranger", "invited", sharing = false),
        ),
        ownerId = ownerId,
    )

    @Test
    fun `the roster is the accepted members led by the host`() {
        assertEquals(
            ConvoyGapFeed.Roster(setOf(host, sam, me), hostId = host, selfId = me),
            ConvoyGapFeed.Roster.of(convoy(ownerId = host), selfId = me),
        )
    }

    @Test
    fun `a convoy whose host is unknown has no roster`() {
        assertNull(ConvoyGapFeed.Roster.of(convoy(ownerId = null), selfId = me))
    }
}
