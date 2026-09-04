package com.jellemax.detour.presentation

import com.jellemax.detour.data.BadgeDef
import com.jellemax.detour.data.BadgeKind
import com.jellemax.detour.data.BadgeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure mapping from earned badge states to the Badges screen's display model:
 * one group per kind, in kind order, each tile carrying its own already-formatted
 * threshold label. Formatting is locale-independent by construction (Task 1).
 */
class BadgesStateTest {

    private fun def(kind: BadgeKind, title: String, threshold: Double) =
        BadgeDef(id = "${kind.name}_${threshold.toInt()}", kind = kind, title = title, threshold = threshold)

    private fun state(def: BadgeDef, value: Double, earnedAtMs: Long?) =
        BadgeState(def = def, value = value, earnedAtMs = earnedAtMs)

    private val distance100km = def(BadgeKind.DISTANCE, "First hundred", 100_000.0)
    private val distance1000km = def(BadgeKind.DISTANCE, "Four figures", 1_000_000.0)
    private val speed100 = def(BadgeKind.TOP_SPEED, "Ton up", 100.0)
    private val coverage50 = def(BadgeKind.COVERAGE, "Half the town", 50.0)

    private fun mapped(
        states: List<BadgeState> = listOf(
            state(distance100km, 250_000.0, 1_700_000_000_000L),
            state(distance1000km, 250_000.0, null),
            state(speed100, 118.0, 1_700_000_000_000L),
            state(coverage50, 22.0, null),
        ),
        municipalitiesVisited: Int = 38,
        municipalitiesTotal: Int = 312,
    ) = badgesStateFrom(
        states = states,
        municipalitiesVisited = municipalitiesVisited,
        municipalitiesTotal = municipalitiesTotal,
    )

    @Test fun earnedFractionCountsEarnedOverTotal() {
        assertEquals("2 / 4", mapped().earnedFractionLabel)
    }

    @Test fun badgesAreGroupedByKindInDeclarationOrder() {
        val kinds = mapped().groups.map { it.kind }
        assertEquals(listOf(BadgeKind.DISTANCE, BadgeKind.TOP_SPEED, BadgeKind.COVERAGE), kinds)
    }

    @Test fun aGroupCarriesItsKindLabelUppercasedForTheSectionHeader() {
        assertEquals("DISTANCE", mapped().groups.first().label)
    }

    @Test fun kindsWithNoBadgesAreOmittedEntirelyRatherThanShownEmpty() {
        val onlySpeed = mapped(states = listOf(state(speed100, 118.0, null)))
        assertEquals(listOf(BadgeKind.TOP_SPEED), onlySpeed.groups.map { it.kind })
    }

    @Test fun distanceThresholdsRenderAsGroupedKilometres() {
        val tiles = mapped().groups.first { it.kind == BadgeKind.DISTANCE }.tiles
        assertEquals(listOf("100 km", "1 000 km"), tiles.map { it.thresholdLabel })
    }

    @Test fun speedThresholdsRenderAsKilometresPerHour() {
        val tile = mapped().groups.first { it.kind == BadgeKind.TOP_SPEED }.tiles.single()
        assertEquals("100 km/h", tile.thresholdLabel)
    }

    @Test fun coverageThresholdsRenderAsAPercentage() {
        val tile = mapped().groups.first { it.kind == BadgeKind.COVERAGE }.tiles.single()
        assertEquals("50%", tile.thresholdLabel)
    }

    @Test fun municipalityThresholdsRenderAsAPlainCount() {
        val muni = def(BadgeKind.MUNICIPALITY, "Explorer", 10.0)
        val tile = mapped(states = listOf(state(muni, 4.0, null)))
            .groups.single().tiles.single()
        assertEquals("10 places", tile.thresholdLabel)
    }

    @Test fun aTileCarriesTheBadgesRealTitleNotAnInventedTierName() {
        val tile = mapped().groups.first { it.kind == BadgeKind.DISTANCE }.tiles.first()
        assertEquals("First hundred", tile.title)
    }

    @Test fun aTileReportsWhetherItIsEarned() {
        val tiles = mapped().groups.first { it.kind == BadgeKind.DISTANCE }.tiles
        assertEquals(listOf(true, false), tiles.map { it.earned })
    }

    @Test fun coverageSummaryReadsVisitedOverTotalWithItsPercent() {
        val s = mapped()
        assertEquals("38 of 312 municipalities", s.coverageSummaryLabel)
        assertTrue(s.coverageFraction in 0.12f..0.13f)
    }

    @Test fun coverageFractionIsZeroWhenNoMunicipalitiesAreKnownRatherThanDividingByZero() {
        assertEquals(0f, mapped(municipalitiesTotal = 0).coverageFraction)
    }

    @Test fun coverageSummaryUsesSingularMunicipalityWhenOnlyOneIsKnown() {
        assertEquals(
            "1 of 1 municipality",
            mapped(municipalitiesVisited = 1, municipalitiesTotal = 1).coverageSummaryLabel,
        )
    }
}
