package com.jellemax.detour.tracking

import com.jellemax.detour.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The test #312 asked for and #306 could not get: a fake [LocationSource]
 * built under plain JUnit4, handing the ingest path a literal fix. Possible
 * only because [LocationFix] carries none of `android.location.Location` —
 * the android.jar stub that throws on construction outside
 * Robolectric/androidTest, neither of which this repo has.
 */
class LocationSourceFakeTest {

    private class FakeLocationSource(
        private val listener: LocationBatchListener,
    ) : LocationSource {
        override fun ensureFor(mode: TripTrackingService.LocationMode) = true
        override fun stop() = Unit
        override suspend fun currentLatLon(): LatLon? = null
        fun push(fix: LocationFix) = listener.onFixes(listOf(fix))
    }

    private val fix = LocationFix(
        lat = 50.85,
        lon = 4.35,
        speedMps = 12f,
        bearingDeg = 90f,
        accuracyMeters = 5f,
        timeMs = 1_000L,
        elapsedRealtimeNanos = 2_000_000_000L,
        origin = FixOrigin.PLATFORM,
    )

    @Test
    fun `a fake source hands the listener the exact fix it was pushed`() {
        var received: LocationFix? = null
        FakeLocationSource(LocationBatchListener { received = it.single() }).push(fix)
        assertEquals(fix, received)
    }

    @Test
    fun `a batch preserves order and carries every fix`() {
        val received = mutableListOf<LocationFix>()
        val listener = LocationBatchListener { received.addAll(it) }
        val second = fix.copy(lat = 50.86, timeMs = 2_000L)
        listener.onFixes(listOf(fix, second))
        assertEquals(listOf(fix, second), received)
    }
}
