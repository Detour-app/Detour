package com.jellemax.detour.data

/** Three road-class buckets for the driving-behavior road-type-mix stat
 *  (maxke24/Detour#61) — coarser than [TravelMode.highwayRegex]'s per-mode
 *  regexes, matched instead against `RoadRoulette.DRIVABLE_HIGHWAYS`'s set. */
enum class HighwayClass {
    MOTORWAY, ARTERIAL, LOCAL;

    companion object {
        fun of(highwayTag: String): HighwayClass? = when (highwayTag) {
            "motorway", "trunk", "motorway_link", "trunk_link" -> MOTORWAY
            "primary", "secondary", "primary_link", "secondary_link" -> ARTERIAL
            "tertiary", "unclassified", "residential", "living_street", "tertiary_link" -> LOCAL
            else -> null
        }
    }
}
