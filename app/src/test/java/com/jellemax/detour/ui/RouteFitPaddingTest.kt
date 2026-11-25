package com.jellemax.detour.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFitPaddingTest {
    private val margin = 32f

    @Test
    fun noTagsKeepsTheMarginOnEverySide() {
        assertEquals(RouteFitPadding(sideDp = margin, topDp = margin, bottomDp = margin), routeFitPaddingDp(0f, margin))
    }

    @Test
    fun wideTagWidensBothSidesToHalfItsWidthPlusAGap() {
        // A "very hard" tag is ~100 dp: centred on a point at the edge, half of it
        // used to hang past a 32 dp padding (#1066).
        assertEquals(58f, routeFitPaddingDp(100f, margin).sideDp, 0.001f)
    }

    @Test
    fun narrowTagNeverShrinksTheSidesBelowTheMargin() {
        assertEquals(margin, routeFitPaddingDp(20f, margin).sideDp, 0.001f)
    }

    @Test
    fun topLeavesRoomForAWholeLiftedTagAndBottomStaysAtTheMargin() {
        val padding = routeFitPaddingDp(100f, margin)
        // Lift 6 + tag 24 + ring 2x2 + edge gap 8.
        assertEquals(42f, padding.topDp, 0.001f)
        assertEquals(margin, padding.bottomDp, 0.001f)
    }
}
