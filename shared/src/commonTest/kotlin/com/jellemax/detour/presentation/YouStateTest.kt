package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure mapping from RiderStats + counts to the You screen's display values.
 * Formatting is locale-independent by construction (see the space-grouping test),
 * because commonMain has no NumberFormat and the values must read the same on JVM
 * and Kotlin/Native.
 */
class YouStateTest {

    private fun state(
        distanceMeters: Double = 12_480_000.0,
        tripCount: Int = 214,
        placesCount: Int = 87,
        badgesEarned: Int = 23,
        badgesTotal: Int = 61,
        username: String = "Kasper",
        signedIn: Boolean = true,
    ) = youStateFrom(
        username = username,
        signedIn = signedIn,
        totalDistanceMeters = distanceMeters,
        tripCount = tripCount,
        placesCount = placesCount,
        badgesEarned = badgesEarned,
        badgesTotal = badgesTotal,
    )

    @Test fun kilometresAreWholeNumbersGroupedWithASpace() {
        // 12 480 000 m -> "12 480" km. Space, not comma: the prototype groups
        // with a space and commonMain has no locale-aware formatter anyway.
        assertEquals("12 480", state().kilometresLabel)
    }

    @Test fun smallDistancesAreNotGrouped() {
        assertEquals("947", state(distanceMeters = 947_400.0).kilometresLabel)
    }

    @Test fun negativeDistancesKeepTheSignBeforeTheFirstDigit() {
        assertEquals("-123", state(distanceMeters = -123_000.0).kilometresLabel)
    }

    @Test fun negativeDistancesGroupThousandsAfterTheSign() {
        assertEquals("-1 234", state(distanceMeters = -1_234_000.0).kilometresLabel)
    }

    @Test fun ridesPlacesAndBadgesArePassedThroughAsCounts() {
        val s = state()
        assertEquals(214, s.rides)
        assertEquals(87, s.places)
        assertEquals(23, s.badgesEarned)
    }

    @Test fun badgeFractionReadsEarnedOverTotal() {
        assertEquals("23 / 61", state().badgeFractionLabel)
    }

    @Test fun signedOutHidesTheProfileCardAndInitial() {
        val s = state(signedIn = false)
        assertEquals(false, s.signedIn)
    }

    @Test fun avatarInitialIsTheFirstLetterUppercased() {
        assertEquals("K", state(username = "kasper").avatarInitial)
    }

    @Test fun blankUsernameFallsBackToQuestionMark() {
        assertEquals("?", state(username = "   ").avatarInitial)
    }
}
