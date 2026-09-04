package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * commonMain has no NumberFormat and no String.format, so these two helpers are
 * the only number formatting the presentation layer gets. They must read the same
 * on JVM and Kotlin/Native, which means no locale input of any kind.
 */
class DisplayFormatTest {

    @Test fun thousandsAreSeparatedByAPlainAsciiSpace() {
        assertEquals("12 480", groupThousands(12_480))
        assertEquals("1 000 000", groupThousands(1_000_000))
    }

    @Test fun shortNumbersAreNotGrouped() {
        assertEquals("0", groupThousands(0))
        assertEquals("947", groupThousands(947))
    }

    @Test fun groupingStartsAtFourDigits() {
        assertEquals("1 000", groupThousands(1_000))
    }

    @Test fun negativeNumbersKeepTheSignAttachedToTheFirstDigit() {
        // The sign must not be counted as a digit position: "-123", never "- 123".
        assertEquals("-123", groupThousands(-123))
        assertEquals("-1 234", groupThousands(-1_234))
    }

    @Test fun fixedDecimalsRoundHalfAwayFromZero() {
        assertEquals("1.3", formatFixed(1.25, 1))
        assertEquals("1.2", formatFixed(1.24, 1))
    }

    @Test fun fixedDecimalsPadShortFractions() {
        assertEquals("5.00", formatFixed(5.0, 2))
        assertEquals("0.50", formatFixed(0.5, 2))
    }

    @Test fun zeroDecimalsDropsThePointEntirely() {
        assertEquals("38", formatFixed(37.6, 0))
    }

    @Test fun negativeValuesKeepTheirSign() {
        assertEquals("-2.5", formatFixed(-2.5, 1))
    }

    @Test fun aValueRoundingToZeroIsNotSignedNegativeZero() {
        assertEquals("0.0", formatFixed(-0.01, 1))
    }

    @Test fun durationsUnderAnHourShowMinutesOnly() {
        assertEquals("25 min", formatDurationHistory(25 * 60_000L))
    }

    @Test fun durationsUnderAMinuteShowZeroMinutes() {
        assertEquals("0 min", formatDurationHistory(30_000L))
    }

    @Test fun anExactHourShowsZeroMinutesAfterTheHour() {
        assertEquals("1 h 0 min", formatDurationHistory(60 * 60_000L))
    }

    @Test fun durationsOverAnHourShowHoursAndMinutes() {
        assertEquals("1 h 12 min", formatDurationHistory(72 * 60_000L))
    }

    @Test fun zeroDurationShowsZeroMinutes() {
        assertEquals("0 min", formatDurationHistory(0L))
    }
}
