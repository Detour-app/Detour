package com.jellemax.detour.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * No lean-angle badges: [Trip.maxLeanAngleDeg] is only meaningful when the phone
 * is mounted upright on a handlebar, and a ride with it in a pocket would award
 * the top tier for nothing. The number is still recorded and shown per trip.
 */
enum class BadgeKind(val label: String) {
    DISTANCE("Distance"),
    TOP_SPEED("Top speed"),
    TRIP_DISTANCE("Single ride"),
    MUNICIPALITY("Places"),
    COVERAGE("Coverage"),
}

/** [threshold] is in the kind's own unit: metres, km/h, degrees, count, percent. */
data class BadgeDef(
    val id: String,
    val kind: BadgeKind,
    val title: String,
    val threshold: Double,
)

data class BadgeState(
    val def: BadgeDef,
    /** Current value in the same unit as [BadgeDef.threshold]. */
    val value: Double,
    val earnedAtMs: Long?,
) {
    val earned: Boolean get() = earnedAtMs != null
    val progress: Float
        get() = if (def.threshold <= 0) 1f
        else (value / def.threshold).coerceIn(0.0, 1.0).toFloat()
}

/** Everything the badges are scored against, and — in phase 3 — everything a
 *  friend is allowed to see. Traces and trip routes are deliberately absent. */
data class RiderStats(
    val totalDistanceMeters: Double = 0.0,
    val topSpeedKmh: Double = 0.0,
    val longestTripMeters: Double = 0.0,
    val maxLeanDeg: Double = 0.0,
    val municipalitiesVisited: Int = 0,
    val bestCoveragePercent: Double = 0.0,
    val tripCount: Int = 0,
)

/**
 * Badge definitions, evaluation, and the earned-at timestamps.
 *
 * Badges are derived state — every value is recomputed from [TripStore] and
 * [Coverage] — except *when* each was first earned, which only the moment of
 * earning knows. That is the one thing on disk, and the one thing phase 3 has
 * to sync.
 */
object BadgeStore {

    private const val FILE_NAME = "badges.json"

    private fun tiers(
        kind: BadgeKind,
        prefix: String,
        vararg tiers: Pair<Double, String>,
    ): List<BadgeDef> = tiers.map { (threshold, title) ->
        BadgeDef("${prefix}_${threshold.toInt()}", kind, title, threshold)
    }

    val ALL: List<BadgeDef> =
        tiers(
            BadgeKind.DISTANCE, "dist",
            100_000.0 to "First hundred",
            500_000.0 to "Getting somewhere",
            1_000_000.0 to "Four figures",
            5_000_000.0 to "Long hauler",
            10_000_000.0 to "Ten thousand",
            25_000_000.0 to "Round the world",
        ) + tiers(
            BadgeKind.TOP_SPEED, "speed",
            100.0 to "Ton up",
            130.0 to "Motorway legal",
            160.0 to "Quick",
            200.0 to "Double ton",
            250.0 to "Terminal velocity",
        ) + tiers(
            BadgeKind.TRIP_DISTANCE, "ride",
            100_000.0 to "Day out",
            250_000.0 to "Proper ride",
            500_000.0 to "Iron butt",
        ) + tiers(
            BadgeKind.MUNICIPALITY, "muni",
            3.0 to "Wanderer",
            10.0 to "Explorer",
            25.0 to "Cartographer",
            50.0 to "Conqueror",
        ) + tiers(
            BadgeKind.COVERAGE, "cover",
            10.0 to "Local knowledge",
            25.0 to "Know the back roads",
            50.0 to "Half the town",
            100.0 to "Every last street",
        )

    fun stats(coverage: List<Coverage.Entry>): RiderStats {
        val trips = TripStore.load()
        return RiderStats(
            totalDistanceMeters = trips.sumOf { it.distanceMeters },
            topSpeedKmh = (trips.maxOfOrNull { it.topSpeedMps } ?: 0.0) * 3.6,
            longestTripMeters = trips.maxOfOrNull { it.distanceMeters } ?: 0.0,
            maxLeanDeg = trips.maxOfOrNull { it.maxLeanAngleDeg } ?: 0.0,
            municipalitiesVisited = coverage.count { it.exploredCells > 0 },
            bestCoveragePercent = coverage.maxOfOrNull { it.percent } ?: 0.0,
            tripCount = trips.size,
        )
    }

    private fun valueOf(def: BadgeDef, stats: RiderStats): Double = when (def.kind) {
        BadgeKind.DISTANCE -> stats.totalDistanceMeters
        BadgeKind.TOP_SPEED -> stats.topSpeedKmh
        BadgeKind.TRIP_DISTANCE -> stats.longestTripMeters
        BadgeKind.MUNICIPALITY -> stats.municipalitiesVisited.toDouble()
        BadgeKind.COVERAGE -> stats.bestCoveragePercent
    }

    data class Result(val states: List<BadgeState>, val newlyEarned: List<BadgeDef>)

    /**
     * Scores every badge against [stats], stamps any that just crossed their
     * threshold, and reports those so the caller can celebrate them. Safe to
     * call repeatedly: a badge keeps the timestamp it was first earned at.
     */
    fun refresh(stats: RiderStats): Result {
        val earned = load().toMutableMap()
        val now = nowMs()
        val newlyEarned = ArrayList<BadgeDef>()

        val states = ALL.map { def ->
            val value = valueOf(def, stats)
            if (def.id !in earned && value >= def.threshold) {
                earned[def.id] = now
                newlyEarned.add(def)
            }
            BadgeState(def, value, earned[def.id])
        }
        if (newlyEarned.isNotEmpty()) save(earned)
        return Result(states, newlyEarned)
    }

    private fun load(): Map<String, Long> {
        val f = appFile(FILE_NAME)
        if (!f.exists()) return emptyMap()
        return try {
            jsonObjectOf(f.readText()).mapValues { (_, v) -> v.toString().trim('"').toLong() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun save(earned: Map<String, Long>) {
        val o = buildJsonObject { for ((id, at) in earned) put(id, at) }
        appFile(FILE_NAME).writeText(o.string())
    }

    /** Raw stored JSON, for server sync. */
    fun rawJson(): String {
        val f = appFile(FILE_NAME)
        return if (f.exists()) f.readText() else "{}"
    }

    /** Overwrite with the merged map from the sync server. The server keeps the
     *  earliest earnedAtMs per badge, so this only ever moves dates backwards. */
    fun replaceRaw(json: String) {
        jsonObjectOf(json) // validate before overwriting
        appFile(FILE_NAME).writeText(json)
    }
}
