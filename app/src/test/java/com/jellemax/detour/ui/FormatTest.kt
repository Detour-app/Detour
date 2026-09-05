package com.jellemax.detour.ui

import com.jellemax.detour.data.Settings
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins `Format.kt`'s current output.
 *
 * The two decimal-bearing readouts (`formatDistanceKm`, `formatGForce`) delegate
 * to commonMain, which has no `Locale` at all — the separator reaches it as an
 * argument, resolved here at the render path from `Settings.decimalSeparator`.
 * So each is checked under both a period-decimal locale (US) and a comma-decimal
 * one (nl-BE), and must now *differ* between them: a Belgian rider reads
 * "1,2 km", not "1.2 km".
 *
 * Settings.init() is never called here and does not need to be: the SYSTEM
 * default is the flow's initial value and resolves straight off the JVM locale,
 * so no Prefs backend is involved.
 */
class FormatTest {

    private lateinit var originalLocale: Locale

    @Before
    fun fixDefaultLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalLocale)
    }

    // --- formatDistanceKm ----------------------------------------------------

    @Test
    fun distanceUnderAKilometreHasNoDecimalSoBothLocalesAgree() {
        assertEquals("850 m", formatDistanceKm(850.0))
        Locale.setDefault(Locale("nl", "BE"))
        assertEquals("850 m", formatDistanceKm(850.0))
    }

    @Test
    fun distanceJustUnderAKilometreRoundsUpToTheNextWholeMetre() {
        // 999.6 m is still "< 1000" so it takes the metre branch, but "%.0f"
        // rounds it to display as "1000 m" - a metre away from "1.0 km".
        assertEquals("1000 m", formatDistanceKm(999.6))
    }

    @Test
    fun distanceAtOrAboveAKilometreUsesAPeriodDecimalInUs() {
        assertEquals("1.0 km", formatDistanceKm(1000.0))
        assertEquals("9.0 km", formatDistanceKm(9000.0))
        assertEquals("12.4 km", formatDistanceKm(12400.0))
    }

    @Test
    fun distanceAtOrAboveAKilometreUsesACommaDecimalInNlBe() {
        // SYSTEM (the default) resolves off the platform, so this follows the
        // JVM locale the same way the device locale drives it on a phone.
        Locale.setDefault(Locale("nl", "BE"))
        assertEquals("1,0 km", formatDistanceKm(1000.0))
        assertEquals("9,0 km", formatDistanceKm(9000.0))
        assertEquals("12,4 km", formatDistanceKm(12400.0))
    }

    @Test
    fun anExplicitPointOverridesACommaLocale() {
        Locale.setDefault(Locale("nl", "BE"))
        val point = Settings.decimalSeparatorChar(Settings.DecimalSeparator.POINT)
        assertEquals('.', point)
        assertEquals("12.4 km", formatDistanceKm(12400.0, point))
        assertEquals("1.3 g", formatGForce(1.3, point))
    }

    @Test
    fun anExplicitCommaOverridesAPeriodLocale() {
        val comma = Settings.decimalSeparatorChar(Settings.DecimalSeparator.COMMA)
        assertEquals(',', comma)
        assertEquals("12,4 km", formatDistanceKm(12400.0, comma))
        assertEquals("1,3 g", formatGForce(1.3, comma))
    }

    @Test
    fun systemResolvesFromThePlatformLocale() {
        assertEquals('.', Settings.decimalSeparatorChar(Settings.DecimalSeparator.SYSTEM))
        Locale.setDefault(Locale("nl", "BE"))
        assertEquals(',', Settings.decimalSeparatorChar(Settings.DecimalSeparator.SYSTEM))
    }

    // --- formatGForce ----------------------------------------------------

    @Test
    fun gForceUsesAPeriodDecimalInUs() {
        assertEquals("1.3 g", formatGForce(1.3))
    }

    @Test
    fun gForceUsesACommaDecimalInNlBe() {
        Locale.setDefault(Locale("nl", "BE"))
        assertEquals("1,3 g", formatGForce(1.3))
    }

    // --- formatSpeedKmh ----------------------------------------------------
    // "%.0f" leaves no decimal separator to vary by locale.

    @Test
    fun speedRoundsMetresPerSecondToWholeKmh() {
        assertEquals("0 km/h", formatSpeedKmh(0.0))
        assertEquals("36 km/h", formatSpeedKmh(10.0))
        assertEquals("50 km/h", formatSpeedKmh(13.9))
    }

    // --- formatLeanAngle ----------------------------------------------------
    // "%.0f" leaves no decimal separator to vary by locale.

    @Test
    fun leanAngleRoundsToWholeDegreesWithADegreeSign() {
        assertEquals("12°", formatLeanAngle(12.3))
        assertEquals("46°", formatLeanAngle(45.7))
        assertEquals("-30°", formatLeanAngle(-30.2))
    }

    // --- formatDuration ----------------------------------------------------
    // "%d" digits don't vary between these two locales either.

    @Test
    fun durationUnderAnHourIsMinutesColonSeconds() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:05", formatDuration(5_000L))
        assertEquals("1:05", formatDuration(65_000L))
        assertEquals("7:19", formatDuration(439_000L))
    }

    @Test
    fun durationAtOrOverAnHourIsHoursColonMinutesColonSeconds() {
        assertEquals("1:00:00", formatDuration(3_600_000L))
        assertEquals("1:12:05", formatDuration(4_325_000L))
        assertEquals("1:59:59", formatDuration(7_199_000L))
    }
}
