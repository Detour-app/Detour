package com.jellemax.detour.presentation

import kotlinx.datetime.TimeZone
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

    // relativeAge buckets, ported verbatim from the old CirclesScreen.kt private fun:
    // minutes < 1 -> "just now"; minutes < 60 -> "${minutes}m ago";
    // minutes < 1440 -> "${minutes/60}h ago"; else -> "${minutes/1440}d ago".
    // A future tsMs (clock skew between rider and server) clamps to 0 minutes,
    // i.e. "just now" — same as the original's coerceAtLeast(0), kept deliberately:
    // it already avoids the "-3m ago" absurdity, so no behavior change is needed.

    @Test fun underAMinuteReadsJustNow() {
        assertEquals("just now", relativeAge(tsMs = 1_000L, nowMs = 1_000L))
        assertEquals("just now", relativeAge(tsMs = 0L, nowMs = 59_999L))
    }

    @Test fun oneWholeMinuteIsTheFirstMinuteBucket() {
        assertEquals("1m ago", relativeAge(tsMs = 0L, nowMs = 60_000L))
    }

    @Test fun wholeMinutesUnderAnHourShowMinutes() {
        assertEquals("45m ago", relativeAge(tsMs = 0L, nowMs = 45 * 60_000L))
    }

    @Test fun fiftyNineMinutesIsTheLastMinuteBucket() {
        assertEquals("59m ago", relativeAge(tsMs = 0L, nowMs = 59 * 60_000L))
    }

    @Test fun sixtyMinutesRollsOverToOneHour() {
        assertEquals("1h ago", relativeAge(tsMs = 0L, nowMs = 60 * 60_000L))
    }

    @Test fun wholeHoursUnderADayShowHours() {
        assertEquals("5h ago", relativeAge(tsMs = 0L, nowMs = 5 * 60 * 60_000L))
    }

    @Test fun twentyThreeHoursIsTheLastHourBucket() {
        assertEquals("23h ago", relativeAge(tsMs = 0L, nowMs = 23 * 60 * 60_000L))
    }

    @Test fun twentyFourHoursRollsOverToOneDay() {
        assertEquals("1d ago", relativeAge(tsMs = 0L, nowMs = 24 * 60 * 60_000L))
    }

    @Test fun wholeDaysShowDays() {
        assertEquals("3d ago", relativeAge(tsMs = 0L, nowMs = 3L * 24 * 60 * 60_000L))
    }

    @Test fun aTimestampInTheFutureReadsJustNow() {
        // Clock skew between a rider's phone and the server is real. Rather than
        // a negative age ("-3m ago"), any future tsMs clamps to "just now" —
        // the same outcome an ordinary few-second skew should produce, so a
        // large skew degrades to the same harmless string instead of a
        // confusing negative duration.
        assertEquals("just now", relativeAge(tsMs = 61_000L, nowMs = 0L))
        assertEquals("just now", relativeAge(tsMs = 999_999_999L, nowMs = 0L))
    }

    // formatDistanceKm: the "%.0f m" / "%.1f km" split at the 1000 m boundary,
    // ported from app/.../ui/Format.kt for the nav display to use.

    @Test fun distanceUnderAKilometreReadsInWholeMetres() {
        assertEquals("850 m", formatDistanceKm(850.0))
    }

    @Test fun distanceAtOrAboveAKilometreReadsInKilometresToOneDecimal() {
        assertEquals("1.2 km", formatDistanceKm(1_200.0))
        assertEquals("1.0 km", formatDistanceKm(1_000.0))
    }

    // formatEta: kotlinx-datetime's substitute for SimpleDateFormat("HH:mm").
    // zone is pinned to UTC so the assertions don't depend on the machine
    // running them.

    @Test fun etaIsZeroPaddedAtMidnight() {
        assertEquals("00:00", formatEta(epochMs = 0L, zone = TimeZone.UTC))
    }

    @Test fun etaZeroPadsASingleDigitHour() {
        assertEquals("09:05", formatEta(epochMs = (9 * 3_600 + 5 * 60) * 1_000L,
            zone = TimeZone.UTC))
    }

    @Test fun etaReadsTheLastMinuteOfTheDay() {
        assertEquals("23:59", formatEta(epochMs = (23 * 3_600 + 59 * 60) * 1_000L,
            zone = TimeZone.UTC))
    }
}
