package com.jellemax.detour.drive

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The load a vehicle puts on its rider, in g: braking, accelerating and
 * cornering all push sideways or fore-aft, so only the part of the
 * gravity-free acceleration that lies in the horizontal plane counts. The
 * vertical part is the road — bumps, potholes, a kerb — and counting it made
 * a speed bump read like a hard brake.
 *
 * Inputs are the phone's own axes: a gravity-free acceleration (Android's
 * linear-acceleration sensor) and the gravity vector (its gravity sensor),
 * both in m/s². Before the first gravity reading there is no "down" yet, and
 * the whole magnitude is used.
 */
object GForce {
    const val STANDARD_GRAVITY_MPS2 = 9.80665

    fun horizontal(ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): Double {
        val total2 = ax * ax + ay * ay + az * az
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        if (gNorm < 1e-6) return sqrt(total2) / STANDARD_GRAVITY_MPS2
        val vertical = (ax * gx + ay * gy + az * gz) / gNorm
        return sqrt(max(total2 - vertical * vertical, 0.0)) / STANDARD_GRAVITY_MPS2
    }
}

/**
 * The latest "down" from the gravity sensor, turning each linear-acceleration
 * sample into [GForce.horizontal]. Zero until the first gravity reading.
 */
// ponytail: a phone with no linear-acceleration sensor records no g at all
// rather than falling back to the gravity-laden raw accelerometer; a low-pass
// gravity estimate over the raw one if such phones turn up.
class GravityFrame {
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0

    fun set(x: Float, y: Float, z: Float) {
        gx = x.toDouble(); gy = y.toDouble(); gz = z.toDouble()
    }

    fun horizontalG(ax: Float, ay: Float, az: Float): Double =
        GForce.horizontal(ax.toDouble(), ay.toDouble(), az.toDouble(), gx, gy, gz)
}
