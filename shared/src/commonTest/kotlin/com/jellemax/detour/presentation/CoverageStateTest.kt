package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure mapping behind the coverage screen's bottom sheet: how many
 * municipalities are explored, and how one selected municipality reads.
 * The map itself is MapLibre's and is not modelled here.
 */
class CoverageStateTest {

    private fun view(id: Long, name: String, percent: Double, cells: Int) =
        CoverageEntryView(
            municipalityId = id,
            name = name,
            percentLabel = "",
            areaLabel = "",
            percent = percent,
            totalCells = cells,
        )

    private fun mapped(
        entries: List<CoverageEntryView> = listOf(
            view(1L, "Maastricht", 76.0, 4_000),
            view(2L, "Vaals", 100.0, 900),
            view(3L, "Gulpen-Wittem", 0.0, 1_200),
        ),
        knownTotal: Int = 312,
        selected: Long? = null,
    ) = coverageStateFrom(entries = entries, knownMunicipalities = knownTotal, selectedId = selected)

    @Test fun exploredCountsOnlyMunicipalitiesWithAnyCoverage() {
        // 0% is "known about", not "explored" — the summary must not count it.
        assertEquals(2, mapped().exploredCount)
    }

    @Test fun summaryReadsExploredOfKnown() {
        assertEquals("of 312 municipalities explored", mapped().summarySuffix)
        assertEquals("2", mapped().exploredLabel)
    }

    @Test fun summaryUsesSingularMunicipalityWhenOnlyOneIsKnown() {
        // The rider's device knows exactly one boundary — "of 1 municipalities"
        // is wrong English and was the bug on the old screen.
        assertEquals("of 1 municipality explored", mapped(knownTotal = 1).summarySuffix)
    }

    @Test fun fullyCoveredCountsOnlyThoseAtOneHundredPercent() {
        assertEquals(1, mapped().fullyCoveredCount)
    }

    @Test fun nothingIsSelectedByDefault() {
        assertNull(mapped().selected)
    }

    @Test fun selectingAMunicipalityExposesItsFormattedReadout() {
        val s = mapped(selected = 1L)
        assertEquals("Maastricht", s.selected?.name)
    }

    @Test fun selectingAnUnknownIdSelectsNothingRatherThanThrowing() {
        assertNull(mapped(selected = 999L).selected)
    }

    @Test fun areaIsCellCountTimesCellAreaInSquareKilometres() {
        // 4000 cells of 50 m -> 4000 * 2500 m2 = 10 000 000 m2 = 10 km2.
        // 50.0 is a test literal on purpose, NOT the app's CELL_METERS (250.0):
        // the point of taking the cell size as an argument is that the arithmetic
        // is checkable without the production constant.
        assertEquals(10.0, areaKm2(totalCells = 4_000, cellMeters = 50.0), absoluteTolerance = 1e-9)
    }

    @Test fun areaIsZeroForNoCells() {
        assertEquals(0.0, areaKm2(totalCells = 0, cellMeters = 50.0), absoluteTolerance = 1e-9)
    }

    @Test fun anEmptyCoverageListStillProducesAUsableState() {
        val s = mapped(entries = emptyList())
        assertEquals(0, s.exploredCount)
        assertEquals("0", s.exploredLabel)
    }
}
