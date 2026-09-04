package com.jellemax.detour.presentation

import com.jellemax.detour.data.BadgeKind
import com.jellemax.detour.data.BadgeState

/** One badge as the grid renders it. No Android or file types. */
data class BadgeTile(
    val id: String,
    val title: String,
    val thresholdLabel: String,
    val earned: Boolean,
    val progress: Float,
)

/** One section of the badge grid: all the badges of a single kind. */
data class BadgeGroup(
    val kind: BadgeKind,
    val label: String,
    val tiles: List<BadgeTile>,
)

/** Everything the Badges screen renders, already formatted. */
data class BadgesState(
    val loaded: Boolean = false,
    val earnedCount: Int = 0,
    val earnedFractionLabel: String = "—",
    val coverageSummaryLabel: String = "—",
    val coverageFraction: Float = 0f,
    val groups: List<BadgeGroup> = emptyList(),
)

/**
 * Pure map from scored badge states to the display model. Kept separate from
 * [BadgesPresenter] so it is callable from commonTest with literal arguments and
 * no file system — the house rule for testable shared logic.
 *
 * Groups follow [BadgeKind]'s declaration order rather than the input order, so
 * the screen's section order is stable no matter how BadgeStore.ALL is assembled.
 * A kind with no badges is dropped rather than rendered as an empty section.
 */
fun badgesStateFrom(
    states: List<BadgeState>,
    municipalitiesVisited: Int,
    municipalitiesTotal: Int,
): BadgesState {
    val byKind = states.groupBy { it.def.kind }
    val groups = BadgeKind.entries.mapNotNull { kind ->
        val forKind = byKind[kind] ?: return@mapNotNull null
        if (forKind.isEmpty()) return@mapNotNull null
        BadgeGroup(
            kind = kind,
            label = kind.label.uppercase(),
            tiles = forKind.map { s ->
                BadgeTile(
                    id = s.def.id,
                    title = s.def.title,
                    thresholdLabel = thresholdLabel(kind, s.def.threshold),
                    earned = s.earned,
                    progress = s.progress,
                )
            },
        )
    }
    val earned = states.count { it.earned }
    val municipalityNoun = if (municipalitiesTotal == 1) "municipality" else "municipalities"
    return BadgesState(
        loaded = true,
        earnedCount = earned,
        earnedFractionLabel = "$earned / ${states.size}",
        coverageSummaryLabel = "$municipalitiesVisited of $municipalitiesTotal $municipalityNoun",
        coverageFraction =
            if (municipalitiesTotal <= 0) 0f
            else (municipalitiesVisited.toFloat() / municipalitiesTotal).coerceIn(0f, 1f),
        groups = groups,
    )
}

/**
 * A badge's threshold is stored in the kind's own unit (metres, km/h, degrees,
 * count, percent — see BadgeDef). This renders each in the unit a rider reads.
 */
private fun thresholdLabel(kind: BadgeKind, threshold: Double): String = when (kind) {
    BadgeKind.DISTANCE, BadgeKind.TRIP_DISTANCE ->
        "${groupThousands((threshold / 1000.0).toLong())} km"
    BadgeKind.TOP_SPEED -> "${formatFixed(threshold, 0)} km/h"
    BadgeKind.MUNICIPALITY -> "${formatFixed(threshold, 0)} places"
    BadgeKind.COVERAGE -> "${formatFixed(threshold, 0)}%"
}
