package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
