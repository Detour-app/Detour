package com.jellemax.detour.tracking

import com.jellemax.detour.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TripStartDetector] is the auto-start bar pulled out of [TripTrackingService]
 * (#307): three fixes above the speed gate, sustained for
 * [TripTrackingService.MIN_FAST_RUN_MS] and
 * [TripTrackingService.MIN_FAST_RUN_METERS].
 *
 * Worth testing here rather than by driving, because this is the bar a replay
 * route has to be *built* to clear — `gpx2route.py` reports whether a converted
 * route contains a run long enough, against these same numbers, and
 * `detour-gps-replay` quotes them. A change to either would silently invalidate
 * every fixture in `tools/mocklocation/routes/`.
 *
 * No clock: every timestamp the detector reads is the fix's own, which is what
 * makes a batched burst of idle fixes work — they all arrive at one instant but
 * describe minutes of driving.
 */
class TripStartDetectorTest {

    /** ~1.11 km of northward travel per 0.01 degrees, so distances here are
     *  easy to reason about without reproducing the haversine. */
    private fun north(meters: Double) = LatLon(50.0 + meters / 111_320.0, 4.0)

    private val fast = TripTrackingService.FAST_SPEED_MPS      // 7.0
    private val probe = TripTrackingService.PROBE_SPEED_MPS    // 4.0
    private val minRunMs = TripTrackingService.MIN_FAST_RUN_MS // 8_000
    private val minMeters = TripTrackingService.MIN_FAST_RUN_METERS // 120.0

    @Test fun `three fast fixes over eight seconds and 120 metres start a trip`() {
        val d = TripStartDetector()
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, fast, north(0.0), 0L, probing = false),
        )
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, fast, north(100.0), 4_000L, probing = false),
        )
        val third = d.onFix(4f, fast, north(200.0), minRunMs, probing = false)
        assertTrue("expected Start, got $third", third is TripStartDetector.Decision.Start)
        third as TripStartDetector.Decision.Start
        // Backdated to the run's first fix, not to the one that proved it.
        assertEquals(0L, third.startTimeMs)
        assertTrue(third.distanceMeters >= minMeters)
    }

    @Test fun `a run that is long enough but too short in ground does not start`() {
        val d = TripStartDetector()
        // Three fixes, well over eight seconds, but only ~50 m covered.
        d.onFix(4f, fast, north(0.0), 0L, probing = false)
        d.onFix(4f, fast, north(25.0), 10_000L, probing = false)
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, fast, north(50.0), 20_000L, probing = false),
        )
    }

    @Test fun `a run that covers the ground too quickly does not start`() {
        val d = TripStartDetector()
        // 200 m in 4 s clears the distance bar but not MIN_FAST_RUN_MS.
        d.onFix(4f, fast, north(0.0), 0L, probing = false)
        d.onFix(4f, fast, north(100.0), 2_000L, probing = false)
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, fast, north(200.0), 4_000L, probing = false),
        )
    }

    @Test fun `one slow fix breaks the run and it must start over`() {
        val d = TripStartDetector()
        d.onFix(4f, fast, north(0.0), 0L, probing = false)
        d.onFix(4f, fast, north(100.0), 4_000L, probing = false)
        // A red light, a tunnel, or one of the real fixes fused blends into a
        // mock replay (#47) — the run is gone.
        assertEquals(
            TripStartDetector.Decision.Idle,
            d.onFix(4f, 1.0, north(120.0), 6_000L, probing = false),
        )
        // The next fast fix is fix one of a new run, not fix three of the old.
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, fast, north(200.0), minRunMs, probing = false),
        )
    }

    @Test fun `a loose fix breaks the run`() {
        val d = TripStartDetector()
        d.onFix(4f, fast, north(0.0), 0L, probing = false)
        // A phone drifting indoors can read as a comfortable 25 km/h.
        assertEquals(
            TripStartDetector.Decision.Idle,
            d.onFix(TripTrackingService.MAX_START_ACCURACY_M + 1f, fast, north(100.0), 4_000L, probing = false),
        )
    }

    @Test fun `the probe window lowers the speed gate but not the rest of the bar`() {
        val d = TripStartDetector()
        // 4.0 m/s is under the 7.0 gate and over the 4.0 probe gate.
        assertEquals(
            TripStartDetector.Decision.Idle,
            d.onFix(4f, probe, north(0.0), 0L, probing = false),
        )
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, probe, north(0.0), 0L, probing = true),
        )
        d.onFix(4f, probe, north(100.0), 4_000L, probing = true)
        val third = d.onFix(4f, probe, north(200.0), minRunMs, probing = true)
        assertTrue("expected Start, got $third", third is TripStartDetector.Decision.Start)
    }

    @Test fun `reset abandons the run in progress`() {
        val d = TripStartDetector()
        d.onFix(4f, fast, north(0.0), 0L, probing = false)
        d.onFix(4f, fast, north(100.0), 4_000L, probing = false)
        d.reset()
        // What would have been the third fix is the first of a fresh run.
        assertEquals(
            TripStartDetector.Decision.Building,
            d.onFix(4f, fast, north(200.0), minRunMs, probing = false),
        )
    }

    @Test fun `a batched burst of idle fixes still starts a trip`() {
        // The reason the detector reads the fix's timestamp and never a clock:
        // in IDLE the requests are batched, so a drive's worth of fixes can be
        // delivered in one callback at one wall instant. Judged on wall time
        // the run would never reach eight seconds; judged on GPS time it does.
        val d = TripStartDetector()
        d.onFix(4f, fast, north(0.0), 1_000L, probing = false)
        d.onFix(4f, fast, north(100.0), 6_000L, probing = false)
        val third = d.onFix(4f, fast, north(200.0), 11_000L, probing = false)
        assertTrue("expected Start, got $third", third is TripStartDetector.Decision.Start)
        assertEquals(1_000L, (third as TripStartDetector.Decision.Start).startTimeMs)
    }
}
