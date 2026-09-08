package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure mapping behind the rider card (issue #156): what a tap on a convoy
 * peer or circle member shows, built only from data the app already holds. See
 * RiderCard.kt for the composable and MapScreen's onClick for what feeds it.
 */
class RiderCardStateTest {

    private val now = 1_000_000L
    private val here = LatLon(50.850, 5.690)
    // ~1 km due north of [here].
    private val oneKmNorth = LatLon(50.859, 5.690)

    private fun state(
        handle: String = "alex",
        riderLocation: LatLon = oneKmNorth,
        headingDeg: Double? = 45.0,
        speedKmh: Double? = 52.4,
        fixTsMs: Long = now,
        expiresAtMs: Long? = now + 20_000,
        ownLocation: LatLon? = here,
    ) = riderCardStateFrom(
        handle = handle,
        riderLocation = riderLocation,
        headingDeg = headingDeg,
        speedKmh = speedKmh,
        fixTsMs = fixTsMs,
        expiresAtMs = expiresAtMs,
        ownLocation = ownLocation,
        nowMs = now,
    )

    @Test fun liveConvoyPeerShowsSpeedHeadingAndDistance() {
        val s = state()
        assertFalse(s.stale)
        assertEquals("52 km/h", s.speedText)
        assertEquals("NE", s.headingText)
        assertEquals("just now", s.ageText)
        assertEquals("1.0 km", s.distanceText)
        assertEquals("alex", s.title)
    }

    @Test fun stalePeerHidesSpeedAndHeadingButKeepsAge() {
        val s = state(fixTsMs = now - 240_000, expiresAtMs = now - 10_000)
        assertTrue(s.stale)
        assertNull(s.speedText)
        assertNull(s.headingText)
        assertEquals("4m ago", s.ageText)
    }

    @Test fun circleMemberWithNoSpeedHeadingOrExpiryIsNotStale() {
        val s = state(headingDeg = null, speedKmh = null, expiresAtMs = null, fixTsMs = now - 180_000)
        assertFalse(s.stale)
        assertNull(s.speedText)
        assertNull(s.headingText)
        assertEquals("3m ago", s.ageText)
    }

    @Test fun noOwnLocationHidesDistance() {
        assertNull(state(ownLocation = null).distanceText)
    }

    @Test fun compassPointWrapsAtNorth() {
        assertEquals("N", compassPoint(0.0))
        assertEquals("N", compassPoint(350.0))
        assertEquals("E", compassPoint(90.0))
        assertEquals("SW", compassPoint(225.0))
    }
}
