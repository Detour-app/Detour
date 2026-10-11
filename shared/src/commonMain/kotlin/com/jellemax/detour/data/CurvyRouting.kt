package com.jellemax.detour.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * How far an A→B moto route may go out of its way for bends (#635): the
 * slider's three stops, fast ... balanced ... twisty. Carried on
 * [RoutePreferences.curvy] and sent as custom-model priority rules, so it
 * combines with the avoid options in one request.
 *
 * The rules read GraphHopper's `curvature` encoded value: beeline over road
 * length per edge, stored in 0.05 steps from 0.25 up to 1.0, where 1.0 is dead
 * straight. A graph built without it 400s every such rule, so the preset only
 * reaches a request through [RoutePreferences.supportedBy], the same gate as
 * the avoid options (#587).
 *
 * Multipliers, never above 1: a straight road or a motorway is made more
 * expensive rather than a bend made cheaper, so the fastest route stays the
 * baseline and [TWISTY] is a bounded detour from it, not an unbounded tour.
 */
enum class CurvyPreset {
    /** The plain fastest route: no rules. */
    FAST,

    /** Straight roads and motorways cost a little more. */
    BALANCED,

    /** GraphHopper's own motorcycle.json + curvature.json numbers. */
    TWISTY,
    ;

    /** This preset's priority rules, or an empty array for [FAST]. */
    internal fun priorityRules(): JsonArray = buildJsonArray {
        when (this@CurvyPreset) {
            FAST -> Unit
            BALANCED -> {
                rule("road_class == MOTORWAY || road_class == TRUNK", BALANCED_MOTORWAY)
                rule("curvature >= $STRAIGHT", BALANCED_STRAIGHT)
            }
            TWISTY -> {
                rule("road_class == MOTORWAY || road_class == TRUNK", TWISTY_MOTORWAY)
                rule("curvature >= $STRAIGHT", TWISTY_STRAIGHT)
                // The next step down: a road with a gentle sweep or two, still
                // not what a twisty route is for.
                rule("curvature >= $NEARLY_STRAIGHT && curvature < $STRAIGHT", TWISTY_NEARLY_STRAIGHT)
            }
        }
    }

    private companion object {
        /** Below 1.0 by less than one 0.05 storage step: only the straight edges. */
        const val STRAIGHT = 0.98
        const val NEARLY_STRAIGHT = 0.9

        const val BALANCED_MOTORWAY = 0.5
        const val BALANCED_STRAIGHT = 0.7

        /** motorcycle.json's motorway/trunk multiplier in GraphHopper 11. */
        const val TWISTY_MOTORWAY = 0.1

        /** curvature.json's straight-road multiplier in GraphHopper 11. */
        const val TWISTY_STRAIGHT = 0.4
        const val TWISTY_NEARLY_STRAIGHT = 0.7
    }
}

private fun JsonArrayBuilder.rule(condition: String, multiplyBy: Double) {
    addJsonObject {
        put("if", condition)
        put("multiply_by", multiplyBy)
    }
}

/**
 * What a curvy route costs over the fastest one between the same points, for
 * the "+12 min, +9 km" line before the rider commits. Negative when the curvy
 * route happens to come out shorter or quicker; a caller showing it decides
 * whether that reads as "+0".
 */
data class DetourCost(val extraSeconds: Long, val extraMeters: Double) {

    /** [extraSeconds] in whole minutes, rounded to nearest. */
    val extraMinutes: Long get() = (extraSeconds + SECONDS_PER_MINUTE / 2).floorDiv(SECONDS_PER_MINUTE)

    val extraKm: Double get() = extraMeters / METERS_PER_KM

    companion object {
        private const val SECONDS_PER_MINUTE = 60L
        private const val METERS_PER_KM = 1000.0

        /**
         * [route] against [fastest], or null when either is missing the time
         * or the distance — a cost from half the numbers would be a guess.
         */
        fun between(route: RouteResult, fastest: RouteResult): DetourCost? {
            val time = route.timeMs ?: return null
            val fastestTime = fastest.timeMs ?: return null
            val distance = route.distanceMeters ?: return null
            val fastestDistance = fastest.distanceMeters ?: return null
            return DetourCost(
                extraSeconds = (time - fastestTime) / MILLIS_PER_SECOND,
                extraMeters = distance - fastestDistance,
            )
        }

        private const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * An A→B route with what its preset cost: [detourCost] against the fastest
 * route, null when no curvy preset was in effect or the fastest route couldn't
 * be had; and the spin candidates' [Curviness.forecastScore], null when the
 * route has no instructions to score by.
 */
data class CurvyRoute(
    val route: RouteResult,
    val detourCost: DetourCost?,
    val curviness: Double?,
)
