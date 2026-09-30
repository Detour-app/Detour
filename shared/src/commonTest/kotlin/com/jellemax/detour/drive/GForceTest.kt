package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals

class GForceTest {

    private val g = GForce.STANDARD_GRAVITY_MPS2

    @Test
    fun aPhoneAtRestReadsZeroNotOneG() {
        // The bug this replaced: the raw accelerometer read 1 g lying still.
        assertEquals(0.0, GForce.horizontal(0.0, 0.0, 0.0, 0.0, 0.0, g), 1e-9)
    }

    @Test
    fun braking_countsInFull() {
        // Phone upright in a holder: gravity along -y, braking along z.
        assertEquals(0.8, GForce.horizontal(0.0, 0.0, 0.8 * g, 0.0, g, 0.0), 1e-9)
    }

    @Test
    fun aBumpDoesNotCount() {
        // Pure vertical jolt, along gravity: the road, not the vehicle.
        assertEquals(0.0, GForce.horizontal(0.0, 0.5 * g, 0.0, 0.0, g, 0.0), 1e-9)
    }

    @Test
    fun onlyTheHorizontalPartOfAMixedLoadCounts() {
        // 0.6 g cornering (x) plus a 0.8 g bump (y, along gravity), phone upright.
        assertEquals(0.6, GForce.horizontal(0.6 * g, 0.8 * g, 0.0, 0.0, g, 0.0), 1e-9)
    }

    @Test
    fun aTiltedPhoneStillFindsHorizontal() {
        // Phone tilted 45° back: gravity splits over y and z.
        val s = g / kotlin.math.sqrt(2.0)
        // 0.5 g forward in the world = perpendicular to gravity in the phone's frame.
        val a = 0.5 * g / kotlin.math.sqrt(2.0)
        assertEquals(0.5, GForce.horizontal(0.0, a, -a, 0.0, s, s), 1e-9)
    }

    @Test
    fun withoutGravityYetTheWholeMagnitudeIsUsed() {
        assertEquals(0.5, GForce.horizontal(0.3 * g, 0.4 * g, 0.0, 0.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun theFrameUsesTheLatestGravity() {
        val frame = GravityFrame()
        val gf = g.toFloat()
        // No gravity yet: all of it counts.
        assertEquals(0.5, frame.horizontalG(0f, 0.5f * gf, 0f), 1e-6)
        frame.set(0f, gf, 0f)
        // Now y is down, so the same sample is a bump.
        assertEquals(0.0, frame.horizontalG(0f, 0.5f * gf, 0f), 1e-6)
    }
}
