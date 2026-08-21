package com.jellemax.detour.data

/**
 * Travel modes with their own radius range, the OSM highway classes that
 * make sense as a destination for that mode, and the GraphHopper profile
 * used for routing on the own server.
 */
enum class TravelMode(
    val label: String,
    val minKm: Float,
    val maxKm: Float,
    val defaultKm: Float,
    val highwayRegex: String,
    /** Google Maps navigation mode: d=drive. Both remaining modes route by
     *  car; kept as a field rather than inlined because [gmapsDirectionsTravelMode]
     *  and `navigateGoogleMaps` read it per-mode. */
    val gmapsMode: String,
    /** Profile name on the self-hosted GraphHopper server. */
    val ghProfile: String,
    /** Spin produces a loop of waypoints instead of a single destination. */
    val roundTrip: Boolean = false,
    /** Roll angle from a rigid mount. Only a leaning vehicle has one worth
     *  recording; in a car the number is the phone sliding in its cradle. */
    val tracksLean: Boolean = false,
    /** Cornering and braking g. Meaningful under an engine, noise on a bicycle. */
    val tracksGForce: Boolean = false,
) {
    MOTO(
        // For round trips the slider means total trip length, not radius.
        label = "Moto",
        minKm = 30f, maxKm = 400f, defaultKm = 120f,
        // Curvy riding roads live on the rural network; skip motorways/residential.
        highwayRegex = "^(primary|secondary|tertiary|unclassified)$",
        gmapsMode = "d",
        ghProfile = "moto",
        roundTrip = true,
        tracksLean = true,
        tracksGForce = true,
    ),
    CAR(
        label = "Car",
        minKm = 5f, maxKm = 100f, defaultKm = 25f,
        highwayRegex = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$",
        gmapsMode = "d",
        ghProfile = "car",
        tracksGForce = true,
    );

    /** Any motion sensor at all: lean, g-force, or both. */
    val tracksMotion: Boolean get() = tracksLean || tracksGForce

    companion object {
        /** Tolerant of unknown names: trips saved by an older build, or a
         *  preference written before a mode was renamed. */
        fun of(name: String?): TravelMode =
            entries.firstOrNull { it.name == name } ?: CAR
    }
}
