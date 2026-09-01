package com.jellemax.detour.tracking

import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.obd2.ObdTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [obdSpeedMpsFrom] is the single OBD2-speed decision shared by the display
 * speed chain and the per-trip attribution counter — pulled out of the service
 * so both read one snapshot per fix instead of each re-sampling
 * [com.jellemax.detour.obd2.Obd2Connection.telemetry] and risking disagreement.
 */
class ObdSpeedResolutionTest {

    private fun telemetry(speedKmh: Double, hasSpeed: Boolean = true) = ObdTelemetry(
        hasSpeed = hasSpeed, speedKmh = speedKmh,
        hasThrottle = false, throttlePct = 0.0,
        hasRpm = false, rpmValue = 0.0,
        receivedAtMs = 0L,
    )

    @Test
    fun noSnapshotMeansNoObdSpeed() {
        assertNull(obdSpeedMpsFrom(null, gpsSpeedMps = 10.0, mode = TravelMode.CAR))
    }

    @Test
    fun aSnapshotWithoutASpeedFieldMeansNoObdSpeed() {
        assertNull(obdSpeedMpsFrom(telemetry(0.0, hasSpeed = false), gpsSpeedMps = 0.0, mode = TravelMode.CAR))
    }

    @Test
    fun aRealReadingIsConvertedFromKmhToMps() {
        assertEquals(10.0, obdSpeedMpsFrom(telemetry(36.0), gpsSpeedMps = 0.0, mode = TravelMode.CAR)!!, 1e-9)
    }

    @Test
    fun aNearZeroReadingIsRejectedWhenGpsShowsClearMotion() {
        // An always-hot dongle in a parked car reports 0 km/h; GPS says otherwise.
        assertNull(obdSpeedMpsFrom(telemetry(0.5), gpsSpeedMps = 20.0, mode = TravelMode.CAR))
    }

    @Test
    fun aNearZeroReadingIsKeptWhenGpsAlsoSaysStopped() {
        assertEquals(0.0, obdSpeedMpsFrom(telemetry(0.5), gpsSpeedMps = 0.0, mode = TravelMode.CAR)!!, 0.2)
    }
}
