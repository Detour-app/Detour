package com.jellemax.detour.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * The rider's "avoid" options for a route, passed as one value from the
 * settings to [RoutingClient] instead of one boolean per option. A new option
 * is a field here and a rule in [priorityRules], not another parameter on
 * every routing call. Read the current settings with
 * [Settings.routePreferences].
 *
 * [avoidHighways] downgrades motorways/trunks (only matters for the car
 * profile; moto never uses them anyway); [avoidSmallRoads] pushes the route
 * onto roads worth driving instead of the nearest lane through a field.
 */
data class RoutePreferences(
    val avoidHighways: Boolean = false,
    val avoidSmallRoads: Boolean = false,
) {
    /**
     * GraphHopper custom-model priority rules for these preferences, or an
     * empty array when none is on. Multipliers, never zero: a house sits on a
     * residential street and the destination itself may be down a lane, so
     * these roads have to stay usable — just expensive enough that a route
     * only takes them when there is no reasonable alternative.
     */
    internal fun priorityRules(): JsonArray = buildJsonArray {
        if (avoidHighways) {
            addJsonObject {
                put("if", "road_class == MOTORWAY || road_class == TRUNK")
                put("multiply_by", 0.05)
            }
        }
        if (avoidSmallRoads) {
            // Belgium's landelijke wegen: narrow, badly surfaced, full of
            // 90° farm-track corners. Tertiary and up are left alone; the
            // unclassified layer is where the misery lives, so it takes the
            // heaviest penalty that still leaves it routable.
            addJsonObject {
                put("if", "road_class == UNCLASSIFIED || road_class == RESIDENTIAL")
                put("multiply_by", 0.2)
            }
            addJsonObject {
                put("if", "road_class == LIVING_STREET || road_class == SERVICE")
                put("multiply_by", 0.1)
            }
            // Unpaved: never worth it on two wheels or four.
            addJsonObject {
                put("if", "road_class == TRACK || road_class == PATH")
                put("multiply_by", 0.02)
            }
        }
    }
}
