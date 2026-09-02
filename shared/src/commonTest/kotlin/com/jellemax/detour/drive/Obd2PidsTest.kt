package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Characterises [Obd2Pids]' SAE J1979 byte decode for maxke24/Detour#62 —
 *  pure math, no I/O, fixtures are the PID spec's own byte tables so no
 *  physical adapter is needed to verify these. */
class Obd2PidsTest {

    // --- Speed (mode 01 PID 0D): one byte, km/h direct -----------------------

    @Test
    fun speedIsTheRawByteInKmh() {
        assertEquals(50.0, Obd2Pids.parseSpeedKmh(listOf(50)))
    }

    @Test
    fun speedOfZeroIsAValidReadingNotAbsence() {
        assertEquals(0.0, Obd2Pids.parseSpeedKmh(listOf(0)))
    }

    @Test
    fun speedAtTheByteCeilingIs255KmH() {
        assertEquals(255.0, Obd2Pids.parseSpeedKmh(listOf(255)))
    }

    @Test
    fun anEmptySpeedResponseIsNull() {
        assertNull(Obd2Pids.parseSpeedKmh(emptyList()))
    }

    // --- Throttle (mode 01 PID 11): one byte, A*100/255 -----------------------

    @Test
    fun throttleAtTheByteCeilingIsFullyOpen() {
        assertEquals(100.0, Obd2Pids.parseThrottlePct(listOf(255)))
    }

    @Test
    fun throttleAtZeroIsFullyClosed() {
        assertEquals(0.0, Obd2Pids.parseThrottlePct(listOf(0)))
    }

    @Test
    fun throttleAtHalfByteIsRoughlyHalfOpen() {
        assertEquals(50.19607843137255, Obd2Pids.parseThrottlePct(listOf(128)))
    }

    @Test
    fun aMissingThrottleResponseIsNull() {
        assertNull(Obd2Pids.parseThrottlePct(emptyList()))
    }

    @Test
    fun relativeThrottleIsRequestedAsPid45AndDecodesLikeAbsolute() {
        assertEquals("0145", Obd2Pids.PID_THROTTLE_REL)
        assertEquals(100.0, Obd2Pids.parseThrottlePct(listOf(255)))
        assertEquals(0.0, Obd2Pids.parseThrottlePct(listOf(0)))
    }

    // --- RPM (mode 01 PID 0C): two bytes, (256*A + B)/4 ------------------------

    @Test
    fun rpmCombinesBothBytes() {
        // (256*0x1A + 0xF8) / 4 = (256*26 + 248) / 4 = 6904/4 = 1726.0
        assertEquals(1726.0, Obd2Pids.parseRpm(listOf(0x1A, 0xF8)))
    }

    @Test
    fun rpmOfZeroBytesIsZeroRpm() {
        assertEquals(0.0, Obd2Pids.parseRpm(listOf(0, 0)))
    }

    @Test
    fun rpmMissingTheSecondByteIsNull() {
        assertNull(Obd2Pids.parseRpm(listOf(0x1A)))
    }

    @Test
    fun rpmWithNoBytesAtAllIsNull() {
        assertNull(Obd2Pids.parseRpm(emptyList()))
    }

    // --- Engine fuel rate (mode 01 PID 5E): two bytes, (256*A + B)/20 L/h -----

    @Test
    fun fuelRateCombinesBothBytesInLitresPerHour() {
        // (256*0x00 + 0x64) / 20 = 100 / 20 = 5.0 L/h
        assertEquals(5.0, Obd2Pids.parseFuelRateLph(listOf(0x00, 0x64)))
        // (256*0x0A + 0x00) / 20 = 2560 / 20 = 128.0 L/h
        assertEquals(128.0, Obd2Pids.parseFuelRateLph(listOf(0x0A, 0x00)))
    }

    @Test
    fun fuelRateOfZeroBytesIsZero() {
        assertEquals(0.0, Obd2Pids.parseFuelRateLph(listOf(0, 0)))
    }

    @Test
    fun fuelRateMissingTheSecondByteIsNull() {
        assertNull(Obd2Pids.parseFuelRateLph(listOf(0x0A)))
    }

    // --- MAF air flow (mode 01 PID 10): two bytes, (256*A + B)/100 g/s --------

    @Test
    fun mafCombinesBothBytesInGramsPerSecond() {
        // (256*0x1A + 0xF8) / 100 = 6904 / 100 = 69.04 g/s
        assertEquals(69.04, Obd2Pids.parseMafGramsPerSec(listOf(0x1A, 0xF8)))
    }

    @Test
    fun mafMissingTheSecondByteIsNull() {
        assertNull(Obd2Pids.parseMafGramsPerSec(listOf(0x1A)))
    }

    // --- Commanded equivalence ratio / lambda (mode 01 PID 44): (2/65536)(256A+B)

    @Test
    fun equivRatioOfStoichiometricIsOne() {
        // λ = 1.0 ⇔ (256A+B) = 32768 = 0x8000 ⇔ A=0x80, B=0x00
        assertEquals(1.0, Obd2Pids.parseCommandedEquivRatio(listOf(0x80, 0x00))!!, 1e-9)
    }

    @Test
    fun equivRatioOfADieselCruiseIsWellAboveOne() {
        // A lean diesel cruise commands λ ≈ 2.0; PID 44 saturates near there.
        // (256*0xFF + 0xFF) * 2 / 65536 = 65535 * 2 / 65536 ≈ 1.99997
        assertEquals(2.0, Obd2Pids.parseCommandedEquivRatio(listOf(0xFF, 0xFF))!!, 1e-3)
    }

    @Test
    fun equivRatioMissingTheSecondByteIsNull() {
        assertNull(Obd2Pids.parseCommandedEquivRatio(listOf(0x80)))
    }

    @Test
    fun equivRatioWithNoBytesIsNull() {
        assertNull(Obd2Pids.parseCommandedEquivRatio(emptyList()))
    }

    @Test
    fun theEquivRatioPidIsRequestedAs0144() {
        assertEquals("0144", Obd2Pids.PID_EQUIV_RATIO)
    }

    // --- Fuel rate derived from MAF (petrol, stoichiometric) -----------------

    @Test
    fun fuelFromMafConvertsAirMassToLitresPerHour() {
        // maf / AFR(14.7) / density(745 g/L) * 3600 s/h
        // 10 g/s -> 10 / 14.7 / 745 * 3600 = 3.2872... L/h
        assertEquals(3.2872, Obd2Pids.fuelRateFromMafLph(10.0), 1e-4)
    }

    @Test
    fun fuelFromMafAtIdleAirflowIsUnderOnePointFiveLitresPerHour() {
        // ~2.5 g/s is a typical warm petrol idle; a plausible fuel rate is well
        // under 1.5 L/h, so a decode error of 10x or a unit slip is caught.
        assertTrue(Obd2Pids.fuelRateFromMafLph(2.5) in 0.5..1.5)
    }

    @Test
    fun fuelFromZeroAirflowIsZero() {
        assertEquals(0.0, Obd2Pids.fuelRateFromMafLph(0.0))
    }

    // --- resolveFuelRate: source selection + deceleration fuel cut ----------

    @Test
    fun fuelRateUsesTheDirectPidWhenPresentAndIsNotFlaggedEstimated() {
        val r = Obd2Pids.resolveFuelRate(
            directLph = 6.4, mafGramsPerSec = 30.0, throttleClosed = false, rpm = 2000.0, speedKmh = 60.0,
        )!!
        assertEquals(6.4, r.lph, 0.0)
        assertEquals(false, r.estimated)
    }

    @Test
    fun fuelRateFallsBackToMafAndIsFlaggedEstimated() {
        val r = Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = 10.0, throttleClosed = false, rpm = 2000.0, speedKmh = 60.0,
        )!!
        assertEquals(Obd2Pids.fuelRateFromMafLph(10.0), r.lph, 1e-9)
        assertTrue(r.estimated)
    }

    @Test
    fun fuelRateIsNullWhenNeitherSourceIsAvailable() {
        assertNull(Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = null, throttleClosed = true, rpm = 2000.0, speedKmh = 60.0,
        ))
    }

    @Test
    fun mafEstimateIsZeroedUnderDecelerationFuelCut() {
        // Closed pedal, engine spinning above idle, still rolling — the ECU has
        // cut injection, so the MAF-implied rate is a lie.
        val r = Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = 8.0, throttleClosed = true, rpm = 2500.0, speedKmh = 40.0,
        )!!
        assertEquals(0.0, r.lph, 0.0)
    }

    @Test
    fun fuelCutIsNotAppliedWhenTheThrottleSignalIsUnknown() {
        // throttleClosed == null means only the absolute-throttle PID (0111) was
        // available, which can't tell a closed pedal from an open one — don't
        // guess a fuel cut.
        val r = Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = 8.0, throttleClosed = null, rpm = 2500.0, speedKmh = 40.0,
        )!!
        assertTrue(r.lph > 0.0)
    }

    @Test
    fun fuelCutDoesNotZeroTheDirectPidReading() {
        val r = Obd2Pids.resolveFuelRate(
            directLph = 0.3, mafGramsPerSec = 8.0, throttleClosed = true, rpm = 2500.0, speedKmh = 40.0,
        )!!
        assertEquals(0.3, r.lph, 0.0)
    }

    @Test
    fun aClosedThrottleAtIdleWhileStoppedIsNotAFuelCut() {
        val r = Obd2Pids.resolveFuelRate(
            directLph = null, mafGramsPerSec = 2.5, throttleClosed = true, rpm = 800.0, speedKmh = 0.0,
        )!!
        assertTrue(r.lph > 0.0)
    }
}
