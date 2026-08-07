package com.jellemax.detour.data

import kotlin.math.PI

/**
 * java.lang.Math's two degree/radian converters, which the geo math here used
 * throughout and which have no common-Kotlin equivalent. Kept as free
 * functions in this package so the call sites read as they did.
 */

internal fun toRadians(degrees: Double): Double = degrees * PI / 180.0

internal fun toDegrees(radians: Double): Double = radians * 180.0 / PI

/** System.currentTimeMillis(), which common Kotlin has no equivalent for. */
internal fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
