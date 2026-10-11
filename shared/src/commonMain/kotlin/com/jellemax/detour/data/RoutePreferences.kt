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
 *
 * [avoidTolls], [avoidFerries] and [avoidUnpaved] read encoded values the
 * server's graph may not have been built with (#586), so they only reach a
 * request through [supportedBy]. [avoidUnpaved] overlaps [avoidSmallRoads]'s
 * track/path rule on purpose: that one reads `road_class` and works on every
 * graph, this one reads `surface` and also catches a gravel road that is
 * classed as a real road.
 *
 * [curvy] is the A→B curviness preset (#635). It reads `curvature`, so it too
 * passes [supportedBy], and it only shapes a `moto` route: [rulesFor] leaves
 * it out of every other profile and round trips never ask for it.
 */
data class RoutePreferences(
    val avoidHighways: Boolean = false,
    val avoidSmallRoads: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val curvy: CurvyPreset = CurvyPreset.FAST,
) {
    /**
     * These preferences with every option the server can't honour switched
     * off. [encodedValues] is what [RoutingSupport] last heard the graph has;
     * null — never asked, or a different server since — drops all four, since
     * a rule on a missing encoded value is an HTTP 400, not a worse route.
     */
    internal fun supportedBy(encodedValues: Set<String>?): RoutePreferences = copy(
        avoidTolls = avoidTolls && RoutingSupport.has(encodedValues, RoutingEncodedValue.TOLL),
        avoidFerries = avoidFerries &&
            RoutingSupport.has(encodedValues, RoutingEncodedValue.ROAD_ENVIRONMENT),
        avoidUnpaved = avoidUnpaved && RoutingSupport.has(encodedValues, RoutingEncodedValue.SURFACE),
        curvy = curvy.takeIf { RoutingSupport.has(encodedValues, RoutingEncodedValue.CURVATURE) }
            ?: CurvyPreset.FAST,
    )

    /**
     * Every priority rule an A→B request on [profile] carries: the avoid
     * options, then [curvy]'s on `moto` only. A car route is the same request
     * it was before curvy routing existed, whatever the preset says.
     */
    internal fun rulesFor(profile: String): JsonArray {
        val avoid = priorityRules()
        if (profile != TravelMode.MOTO.ghProfile) return avoid
        return JsonArray(avoid + curvy.priorityRules())
    }

    /**
     * GraphHopper custom-model priority rules for the avoid options, or an
     * empty array when none is on. [curvy] is not among them; see [rulesFor].
     * Multipliers, never zero: a house sits on a residential street and the destination itself may be down a lane, so
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
        // Same "expensive, never forbidden" rule as above: an island is only
        // reachable by ferry, and a toll bridge may be the only crossing.
        if (avoidTolls) {
            addJsonObject {
                // ALL only: MISSING is every untagged way, and HGV is the
                // lorry-only motorway toll in DE/DK/EE/LT/LV, free for a car or bike.
                put("if", "toll == ALL")
                put("multiply_by", 0.05)
            }
        }
        if (avoidFerries) {
            addJsonObject {
                put("if", "road_environment == FERRY")
                put("multiply_by", 0.05)
            }
        }
        if (avoidUnpaved) {
            // The values moto.json already penalises, plus UNPAVED for a way
            // tagged only as "not paved".
            addJsonObject {
                put("if", "surface == UNPAVED || surface == GRAVEL || surface == DIRT || surface == SAND")
                put("multiply_by", 0.05)
            }
        }
    }
}
