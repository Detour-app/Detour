package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.roundToInt

/**
 * What the rider card (issue #156) shows for a tapped convoy peer or circle
 * member: only fields the app already holds. Pure — see [riderCardStateFrom].
 *
 * A convoy peer carries speed, heading and a per-peer expiry; a circle member
 * carries none of those, so those three are nullable and a circle member's card
 * is just handle + age + distance.
 */
data class RiderCardState(
    /** The rider's handle, the same one drawn under the marker. */
    val title: String,
    /** "52 km/h", or null with no speed to show or a stale fix — a stale peer's
     *  last speed is not its current one. */
    val speedText: String?,
    /** Eight-point compass heading, "NE", or null with no heading or a stale
     *  fix — same reasoning as [speedText]. */
    val headingText: String?,
    /** Coarse fix age, "just now" / "3m ago". Always shown. */
    val ageText: String,
    /** Straight-line distance from own position, "1.2 km", or null when this
     *  device has no fix of its own yet. */
    val distanceText: String?,
    /** The fix is past its expiry deadline — the card leads with age, not a
     *  speed presented as current. */
    val stale: Boolean,
    /** The vehicle this rider opted to share (#158), or null — most peers,
     *  who never opted in, or a circle member, which never carries one. */
    val vehicleName: String? = null,
)

/**
 * Pure map to [RiderCardState]. No I/O, callable with literals — [nowMs] is a
 * plain argument, [relativeAge]'s own contract, and this never reads a clock.
 *
 * [expiresAtMs] null (a circle member) is never stale; only a convoy peer past
 * its deadline is.
 */
fun riderCardStateFrom(
    handle: String,
    riderLocation: LatLon,
    headingDeg: Double?,
    speedKmh: Double?,
    fixTsMs: Long,
    expiresAtMs: Long?,
    ownLocation: LatLon?,
    nowMs: Long,
    sep: Char = '.',
    vehicleName: String? = null,
): RiderCardState {
    val stale = expiresAtMs != null && nowMs > expiresAtMs
    return RiderCardState(
        title = handle,
        speedText = if (stale || speedKmh == null) null else "${formatFixed(speedKmh, 0)} km/h",
        headingText = if (stale || headingDeg == null) null else compassPoint(headingDeg),
        ageText = relativeAge(fixTsMs, nowMs),
        distanceText = ownLocation?.let {
            formatDistanceKm(RoadRoulette.distanceMeters(it, riderLocation), sep)
        },
        stale = stale,
        // A stale fix's vehicle is not shown either - same reasoning as
        // speedText/headingText above, it's a property of a fix that is no
        // longer current.
        vehicleName = vehicleName?.takeIf { !stale },
    )
}

private val COMPASS_POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** Degrees (0 = north, clockwise) to the nearest of eight compass points. */
internal fun compassPoint(deg: Double): String =
    COMPASS_POINTS[(((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8]
