package com.jellemax.detour.ui

import com.jellemax.detour.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RpmBarTest {

    @Test
    fun carScaleIsEightThousand() {
        assertEquals(8_000, rpmBarScale(TravelMode.CAR))
    }

    @Test
    fun motoScaleIsTwelveThousand() {
        assertEquals(12_000, rpmBarScale(TravelMode.MOTO))
    }

    @Test
    fun fractionIsProportionalWithinScale() {
        assertEquals(0.5f, rpmBarFraction(4_000.0, TravelMode.CAR), 0.001f)
    }

    @Test
    fun fractionClampsToOneAboveScale() {
        assertEquals(1f, rpmBarFraction(15_000.0, TravelMode.CAR), 0.001f)
    }

    @Test
    fun fractionClampsToZeroForNegativeReading() {
        assertEquals(0f, rpmBarFraction(-100.0, TravelMode.CAR), 0.001f)
    }
}
