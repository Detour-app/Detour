package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.TravelMode

/**
 * Compass names for the eight 45°-wide bearing buckets a spin's direction
 * picker offers, starting from north. Public so `SpinCards.kt`'s picker can
 * point at this list instead of keeping its own copy.
 */
val DIRECTION_NAMES = listOf(
    "North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west",
)

/** One candidate row in the spin result sheet: a rolled destination's name
 *  plus its already-formatted distance and, when the route reported one, its
 *  duration. [durationText] is null exactly when `CandidatesCard`'s duration
 *  chip today is absent - a route with no `timeMs` (straight-line-only draw). */
data class SpinCandidateRow(
    val name: String,
    val distanceText: String,
    val durationText: String?,
)

/**
 * The spin sheet and result card's display state: what `SpinSheet`
 * (`SpinCards.kt`) and `CandidatesCard` (`CandidatesCard.kt`) render today,
 * pulled out of their inline string-building so it is testable without
 * Compose.
 *
 * Only what a renderer actually reads. The mode label and the direction name
 * were fields here too, until the redesign gave the sheet a mode row that
 * reads `TravelMode.label` straight off the enum and a direction picker that
 * highlights one of [DIRECTION_NAMES] by index - neither of which needs a
 * mapper. A field no renderer reads is a claim about the UI that nothing
 * checks, so both went with their consumers.
 */
data class SpinState(
    val radiusText: String,
    val candidates: List<SpinCandidateRow>,
)

/**
 * `ModeCell`'s inline `"${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else
 * radiusKm.toInt()} km"`, ported as-is: below a mode's own `maxKm` cutoff a
 * radius reads to one decimal place (via [formatFixed], since `commonMain`
 * has no `String.format`); at or above it, a whole number - truncated by
 * `toInt()`, not rounded, exactly as the original does.
 *
 * `internal` rather than folded into [spinStateFrom] so a test can drive the
 * decimal branch directly: no [TravelMode] entry today sets `maxKm` at or
 * under 10 (CAR's is 100, MOTO's 400), so that branch is unreachable through
 * [spinStateFrom] with real modes - almost certainly a vestige of a removed
 * on-foot/bike mode that once used it.
 */
internal fun radiusText(maxKm: Float, radiusKm: Float, sep: Char = '.'): String =
    "${
        if (maxKm <= 10f) formatFixed(radiusKm.toDouble(), 1, sep)
        else radiusKm.toInt().toString()
    } km"

/**
 * `CandidatesCard`'s per-row `Column` and duration `Surface`, ported as one
 * row: `c.name ?: "Option ${index + 1}"` for the label, the route distance
 * falling back to the straight-line draw with the same "~ straight-line " /
 * "via road " prefix, and `"%.0f min".format(timeMs / 60_000.0)` - present
 * only when the candidate has a routed `timeMs` at all, same as the chip
 * `c.route?.timeMs?.let { ... }` guards today.
 */
internal fun candidateRow(
    index: Int,
    candidate: RouteCandidate,
    sep: Char = '.',
): SpinCandidateRow {
    val distanceMeters = candidate.route?.distanceMeters ?: candidate.straightLineMeters
    val prefix = if (candidate.route?.distanceMeters == null) "~ straight-line " else "via road "
    return SpinCandidateRow(
        name = candidate.name ?: "Option ${index + 1}",
        distanceText = prefix + formatDistanceKm(distanceMeters, sep),
        durationText = candidate.route?.timeMs?.let { "${formatFixed(it / 60_000.0, 0)} min" },
    )
}

/**
 * Pure map from a spin's mode/radius/candidates to [SpinState] - the radius
 * readout and the candidate rows the spin sheet and result card render today.
 * Does not own `spin()`'s orchestration, the convoy vote plumbing, or anything
 * touching `camAuthority`; those stay in `MapScreen.kt`.
 *
 * [mode] is still taken whole rather than as its `maxKm`: the cutoff that
 * decides whether a radius reads to one decimal place belongs to the mode, and
 * a caller passing the number itself would be free to pass a different mode's.
 */
fun spinStateFrom(
    mode: TravelMode,
    radiusKm: Float,
    candidates: List<RouteCandidate>,
    sep: Char = '.',
): SpinState = SpinState(
    radiusText = radiusText(mode.maxKm, radiusKm, sep),
    candidates = candidates.mapIndexed { i, c -> candidateRow(i, c, sep) },
)
