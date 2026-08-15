package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.ui.CAM_SNAP_METERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [MapMotion] — where the vehicle is *now* given a fix that is already old, and
 * when the camera should be pushed at all. Pure arithmetic and pure predicates, so no
 * Android APIs and no emulator; the frame loop that calls them is checked by a GPS
 * replay instead, because nothing in this module can assert on a composable.
 */
class MapMotionTest {

    private val brussels = LatLon(50.8503, 4.3517)
    private val t0 = 1_700_000_000_000L

    /** 100 km/h due north. */
    private val fast = 27.78

    @Test
    fun `a fix with no age and no lead is its own prediction`() {
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0, leadSeconds = 0.0)
        assertEquals(brussels.lat, p.lat, 1e-9)
        assertEquals(brussels.lon, p.lon, 1e-9)
    }

    @Test
    fun `prediction advances along the bearing by speed times elapsed`() {
        // 1.0 s old, no lead: 27.78 m due north.
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 + 1000, leadSeconds = 0.0)
        assertEquals(27.78, RoadRoulette.distanceMeters(brussels, p), 0.5)
        assertTrue("due north must increase latitude", p.lat > brussels.lat)
        assertEquals("due north must not move longitude", brussels.lon, p.lon, 1e-9)
    }

    @Test
    fun `the lead adds to the age rather than replacing it`() {
        // 0.5 s old + 0.35 s lead = 0.85 s of travel.
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 + 500, leadSeconds = 0.35)
        assertEquals(fast * 0.85, RoadRoulette.distanceMeters(brussels, p), 0.5)
    }

    @Test
    fun `bearing 90 moves east`() {
        val p = MapMotion.predict(brussels, 90f, fast, t0, t0 + 1000, leadSeconds = 0.0)
        assertTrue("due east must increase longitude", p.lon > brussels.lon)
        assertEquals("due east must not move latitude", brussels.lat, p.lat, 1e-9)
    }

    @Test
    fun `a stale fix is clamped rather than extrapolated without bound`() {
        // 60 s old. Unclamped that is 1.6 km; clamped it is 1.5 s of travel.
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 + 60_000, leadSeconds = 0.0)
        assertEquals(fast * 1.5, RoadRoulette.distanceMeters(brussels, p), 0.5)
    }

    @Test
    fun `a clock running behind the fix does not predict backwards`() {
        val p = MapMotion.predict(brussels, 0f, fast, t0, t0 - 5_000, leadSeconds = 0.0)
        assertEquals(brussels.lat, p.lat, 1e-9)
        assertEquals(brussels.lon, p.lon, 1e-9)
    }

    @Test
    fun `below two metres per second the reported bearing is noise, so nothing is predicted`() {
        val p = MapMotion.predict(brussels, 0f, 1.9, t0, t0 + 1000, leadSeconds = 0.35)
        assertSame(brussels, p)
    }

    @Test
    fun `without a bearing there is no direction to predict along`() {
        val p = MapMotion.predict(brussels, null, fast, t0, t0 + 1000, leadSeconds = 0.35)
        assertSame(brussels, p)
    }

    @Test
    fun `a jump beyond the snap threshold is not continuous motion`() {
        val far = RoadRoulette.offset(brussels, CAM_SNAP_METERS + 50.0, 0.0)
        assertTrue(MapMotion.shouldSnap(brussels, far))
    }

    @Test
    fun `a jump inside the snap threshold is eased, not teleported`() {
        val near = RoadRoulette.offset(brussels, CAM_SNAP_METERS - 50.0, 0.0)
        assertFalse(MapMotion.shouldSnap(brussels, near))
    }

    @Test
    fun `one frame of motorway travel never trips the snap`() {
        // 120 km/h against the loop's 0.1 s dt clamp is 3.3 m.
        val oneFrame = RoadRoulette.offset(brussels, 3.34, 0.0)
        assertFalse(MapMotion.shouldSnap(brussels, oneFrame))
    }

    @Test
    fun `a camera that has never been pushed always pushes`() {
        assertTrue(MapMotion.shouldPush(
            camLat = 0.0, camLon = 0.0, camZoom = 0.0, camBearing = 0f,
            tgtLat = 0.0, tgtLon = 0.0, tgtZoom = 0.0, tgtBearing = 0f,
            targetMoved = false, neverPushed = true))
    }

    @Test
    fun `a converged camera with a still target does no work`() {
        assertFalse(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 90f,
            tgtLat = brussels.lat, tgtLon = brussels.lon, tgtZoom = 16.0, tgtBearing = 90f,
            targetMoved = false, neverPushed = false))
    }

    @Test
    fun `a moving target pushes even when the camera has caught up to it`() {
        // This is the whole point: the old gate compared the frame's step against the
        // last pushed value, so a slow camera looked settled and stepped instead of
        // gliding. Same position, but the target moved this frame.
        assertTrue(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 90f,
            tgtLat = brussels.lat, tgtLon = brussels.lon, tgtZoom = 16.0, tgtBearing = 90f,
            targetMoved = true, neverPushed = false))
    }

    @Test
    fun `an unconverged camera pushes even when the target is still`() {
        assertTrue(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 90f,
            tgtLat = brussels.lat + 0.001, tgtLon = brussels.lon, tgtZoom = 16.0,
            tgtBearing = 90f, targetMoved = false, neverPushed = false))
    }

    @Test
    fun `bearing convergence wraps across north`() {
        // 359.95 vs 0.05 is 0.1 apart, not 359.9 — inside CAM_BEARING_EPS_DEG.
        assertFalse(MapMotion.shouldPush(
            camLat = brussels.lat, camLon = brussels.lon, camZoom = 16.0, camBearing = 359.95f,
            tgtLat = brussels.lat, tgtLon = brussels.lon, tgtZoom = 16.0, tgtBearing = 0.05f,
            targetMoved = false, neverPushed = false))
    }
}
