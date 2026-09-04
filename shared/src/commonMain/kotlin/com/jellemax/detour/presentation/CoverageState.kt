package com.jellemax.detour.presentation

/** One municipality as the coverage sheet reads it, already formatted. */
data class CoverageEntryView(
    val municipalityId: Long,
    val name: String,
    val percentLabel: String,
    val areaLabel: String,
    val percent: Double,
    val totalCells: Int,
)

/** Everything the coverage screen's chrome renders. The map is MapLibre's. */
data class CoverageState(
    val loaded: Boolean = false,
    val exploredCount: Int = 0,
    val exploredLabel: String = "0",
    val fullyCoveredCount: Int = 0,
    val summarySuffix: String = "",
    val entries: List<CoverageEntryView> = emptyList(),
    val selected: CoverageEntryView? = null,
)

/**
 * Area of a municipality's covered cells, in km². Each cell is a
 * [cellMeters]-square, so the count times the cell area, over a million.
 * Pure and passed the cell size rather than reading the constant, so the
 * arithmetic is testable with literal values.
 */
fun areaKm2(totalCells: Int, cellMeters: Double): Double =
    totalCells * cellMeters * cellMeters / 1_000_000.0

/**
 * Pure map from per-municipality coverage to the sheet's display model.
 *
 * "Explored" means any coverage at all — a municipality sitting at 0% is one the
 * app knows a boundary for, not one the rider has been to, and counting it would
 * overstate the summary.
 */
fun coverageStateFrom(
    entries: List<CoverageEntryView>,
    knownMunicipalities: Int,
    selectedId: Long?,
): CoverageState {
    val explored = entries.count { it.percent > 0.0 }
    return CoverageState(
        loaded = true,
        exploredCount = explored,
        exploredLabel = explored.toString(),
        fullyCoveredCount = entries.count { it.percent >= 100.0 },
        summarySuffix = "of $knownMunicipalities municipalities explored",
        entries = entries,
        selected = selectedId?.let { id -> entries.firstOrNull { it.municipalityId == id } },
    )
}
