package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.BreakRequest
import com.jellemax.detour.drive.RegroupPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules behind the phone's break pill, regroup banner and host-only pin
 * action (#649): request expiry, who counts as host, and who has reached the
 * meeting point. MapBreakRegroup.kt draws them.
 */
class ConvoyBreakRegroupStateTest {

    private val now = 10_000_000L
    private val host = RiderId("host")
    private val sam = RiderId("sam")
    private val kim = RiderId("kim")
    private val pinAt = LatLon(50.850, 5.690)
    // ~100 m and ~1 km due north of [pinAt].
    private val near = LatLon(50.851, 5.690)
    private val far = LatLon(50.859, 5.690)

    private val convoy = Group(
        id = "c1",
        name = "Sunday",
        kind = "convoy",
        status = "accepted",
        members = listOf(
            GroupMember(host, "hana", "accepted", sharing = true),
            GroupMember(sam, "sam", "accepted", sharing = true),
            GroupMember(kim, "kim", "accepted", sharing = true),
            GroupMember(RiderId("inv"), "ivo", "invited", sharing = false),
        ),
        ownerId = host,
    )

    private fun state(
        convoy: Group? = this.convoy,
        selfId: RiderId = sam,
        breaks: Map<RiderId, BreakRequest> = emptyMap(),
        pin: RegroupPin? = null,
        peers: Map<RiderId, LatLon> = emptyMap(),
        ownLocation: LatLon? = null,
    ) = convoyBreakRegroupStateFrom(
        convoy, selfId, breaks, pin, peers + listOfNotNull(ownLocation?.let { selfId to it }), now,
    )

    private fun request(id: RiderId, ageMs: Long) = id to BreakRequest(id, near, receivedAtMs = now - ageMs)

    @Test
    fun onlyTheOwnerIsHost() {
        assertTrue(state(selfId = host).isHost)
        assertFalse(state(selfId = sam).isHost)
    }

    @Test
    fun unknownOwnerOrNoConvoyIsNotHost() {
        assertFalse(state(convoy = convoy.copy(ownerId = null), selfId = host).isHost)
        assertFalse(state(convoy = null, selfId = host).isHost)
        // A signed-out device has a blank id; a blank owner must not match it.
        assertFalse(state(convoy = convoy.copy(ownerId = RiderId("")), selfId = RiderId("")).isHost)
    }

    @Test
    fun breakRequestsAgeOutNewestFirst() {
        val s = state(breaks = mapOf(
            request(kim, ageMs = 60_000),
            request(host, ageMs = 1_000),
            request(sam, ageMs = BREAK_SHOWN_MS),
        ))
        assertEquals(listOf("hana", "kim"), s.breaks.map { it.name })
    }

    @Test
    fun ownRequestIsMarkedMine() {
        val s = state(breaks = mapOf(request(sam, 0), request(kim, 0)))
        assertEquals(setOf(sam), s.breaks.filter { it.mine }.map { it.riderId }.toSet())
    }

    @Test
    fun unknownSenderFallsBackToSomeone() {
        val s = state(breaks = mapOf(request(RiderId("new"), 0)))
        assertEquals("Someone", s.breaks.single().name)
    }

    @Test
    fun noPinNoBanner() {
        assertNull(state().regroup)
    }

    @Test
    fun arrivalsSplitAcceptedMembersByRadius() {
        val s = state(
            selfId = sam,
            pin = RegroupPin(pinAt, "Fuel", setBy = host),
            peers = mapOf(host to pinAt, kim to far),
            ownLocation = near,
        )
        val banner = assertNotNull(s.regroup)
        assertEquals(listOf("hana", "sam"), banner.arrived)
        // The invited rider is not riding, so is in neither list.
        assertEquals(listOf("kim"), banner.notArrived)
        assertTrue(banner.hereAlready)
        assertEquals("Fuel", banner.label)
    }

    @Test
    fun riderWithNoPositionHasNotArrived() {
        val banner = assertNotNull(state(pin = RegroupPin(pinAt, null, host)).regroup)
        assertEquals(emptyList(), banner.arrived)
        assertEquals(listOf("hana", "sam", "kim"), banner.notArrived)
        assertFalse(banner.hereAlready)
        assertNull(banner.distanceM)
    }

    @Test
    fun distanceIsFromOwnLocation() {
        val banner = assertNotNull(state(pin = RegroupPin(pinAt, null, host), ownLocation = far).regroup)
        val d = assertNotNull(banner.distanceM)
        assertTrue(d in 990.0..1010.0, "was $d")
    }

    // ---- what the car announces (#657) ----

    @Test
    fun `a new request from another rider is announced your own is not`() {
        val current = mapOf(
            sam to BreakRequest(sam, near, now),
            host to BreakRequest(host, far, now - 1),
        )
        assertEquals(listOf(sam), breakRequestsToAnnounce(emptyMap(), current, selfId = host).map { it.riderId })
    }

    @Test
    fun `an already announced request stays quiet asking again is announced again`() {
        val first = BreakRequest(sam, near, now)
        assertTrue(breakRequestsToAnnounce(mapOf(sam to first), mapOf(sam to first), selfId = host).isEmpty())
        val again = BreakRequest(sam, near, now + 60_000)
        assertEquals(listOf(again), breakRequestsToAnnounce(mapOf(sam to first), mapOf(sam to again), selfId = host))
    }

    @Test
    fun `several new requests are announced oldest first`() {
        val current = mapOf(
            sam to BreakRequest(sam, near, now + 2),
            kim to BreakRequest(kim, far, now + 1),
        )
        assertEquals(listOf(kim, sam), breakRequestsToAnnounce(emptyMap(), current, selfId = host).map { it.riderId })
    }

    @Test
    fun `a new pin from the host is announced once`() {
        val pin = RegroupPin(pinAt, "Fuel", setBy = host)
        assertEquals(pin, regroupPinToAnnounce(announced = null, current = pin, selfId = sam))
        assertNull(regroupPinToAnnounce(announced = pin, current = pin, selfId = sam))
    }

    @Test
    fun `a moved pin is announced a cleared one and your own are not`() {
        val pin = RegroupPin(pinAt, null, setBy = host)
        val moved = pin.copy(location = far)
        assertEquals(moved, regroupPinToAnnounce(announced = pin, current = moved, selfId = sam))
        assertNull(regroupPinToAnnounce(announced = pin, current = null, selfId = sam))
        assertNull(regroupPinToAnnounce(announced = null, current = pin, selfId = host))
    }
}
