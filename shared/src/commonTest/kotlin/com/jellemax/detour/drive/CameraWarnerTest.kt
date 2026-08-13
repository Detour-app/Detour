package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Characterises [CameraWarner] - the one-chime-per-camera latch and its re-arm
 * rule - transcribed from `ui/MapScreen.kt`'s camera `LaunchedEffect` and
 * `car/NavScreen.kt`'s `checkCameras` before either was repointed, so a repoint
 * that changes behaviour fails here rather than in the field. The two copies
 * agreed on every threshold and on the latch; they differed only in how the
 * limit was resolved and in delivery, both of which stay at the call site.
 *
 * The failure this guards against is a warning that fires every second for the
 * same camera, or one that never re-arms for the next. Neither is visible in a
 * compiler diff and both are only noticeable while riding.
 *
 * **There is no clock in this machine.** The latch is positional -
 * `ahead.at != warnedAt`, cleared when nothing is in range - so there is no
 * cooldown, no timestamp, and nothing here needs an injected time. A reviewer
 * looking for the `nowMs` that [SectionAverageTracker] takes will not find one;
 * injecting nothing is the strongest form of that constraint.
 *
 * **Characterisation, not correctness.** Two branches have no replay coverage:
 * [aNullHeadingWarnsOnDistanceAlone], because the mock provider always derives a
 * bearing, and the wedge boundary, which no recorded trace happens to graze.
 *
 * No Android APIs, no clock, no file access: runs on JVM and Kotlin/Native both.
 */
class CameraWarnerTest {

    private val here = LatLon(50.85, 4.35)

    /** [meters] from [here] along compass [bearingDeg]. `RoadRoulette.offset`
     *  takes radians and projects at a flat 111 320 m/deg, while
     *  `distanceMeters` is a haversine at r = 6 371 km, i.e. 111 194.9 m/deg -
     *  so what `distanceMeters` measures is **0.998876x** this, one part in 890
     *  *short* rather than long. Measured, not derived: 405 m of `offset` due
     *  north of [here] reads 404.545 m and 395 m reads 394.556 m, which is why
     *  the radius fixtures below sit 5 m clear of the 400 m threshold on both
     *  sides. Due north also makes the bearing exact - `offset` and
     *  `RoadRoulette.bearingDeg` share the same flat projection, so a point
     *  placed at bearing `b` reads back as exactly `b`, and that identity is
     *  what makes the wedge boundary test deterministic rather than flaky. */
    private fun cam(meters: Double, bearingDeg: Double) =
        SpeedCameras.Camera(RoadRoulette.offset(here, meters, bearingDeg * PI / 180.0))

    /** Due north, comfortably inside `SpeedCameras.WARN_METERS`
     *  (400.0, `SpeedCameras.kt:53`) - it measures 199.775 m. */
    private val ahead = cam(200.0, 0.0)

    private fun step(
        state: CameraWarner.State = CameraWarner.State(),
        cameras: List<SpeedCameras.Camera> = listOf(ahead),
        headingDeg: Double? = 0.0,
        speedKmh: Double = 130.0,
        limitKmh: Double? = 120.0,
    ) = CameraWarner.onFix(
        state = state, cameras = cameras, at = here,
        headingDeg = headingDeg, speedKmh = speedKmh, limitKmh = limitKmh,
    )

    // ---- the over-limit test ---------------------------------------------

    /** "Silent when the limit is unknown: we can't judge 'too fast'" - the
     *  phone's own comment above the effect. At any speed: an untagged road is
     *  not a licence to chime at everyone. */
    @Test
    fun silentWhenTheLimitIsUnknownAtAnySpeed() {
        assertEquals(CameraWarner.Outcome.Silent, step(limitKmh = null).outcome)
        assertEquals(CameraWarner.Outcome.Silent, step(limitKmh = null, speedKmh = 250.0).outcome)
    }

    @Test
    fun silentAtOrUnderTheLimitAndWarnsOncePastTheMargin() {
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 100.0).outcome)
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 120.0).outcome)
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(speedKmh = 120.0 + CameraWarner.OVER_LIMIT_KMH + 0.01).outcome,
        )
    }

    /** The boundary, stated: exactly `limit + OVER_LIMIT_KMH` does **not** warn,
     *  because the test is `>`. */
    @Test
    fun exactlyTheOverLimitMarginDoesNotWarn() {
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(speedKmh = 120.0 + CameraWarner.OVER_LIMIT_KMH).outcome,
        )
    }

    // ---- the latch --------------------------------------------------------

    /** One warning per camera. The state carries the latch, so a second fix at
     *  the same camera - still too fast - is silent and changes nothing. */
    @Test
    fun oneWarningPerCamera() {
        val first = step()
        assertEquals(CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"), first.outcome)
        assertEquals(ahead.at, first.state.warnedAt)

        val second = step(state = first.state)
        assertEquals(CameraWarner.Outcome.Silent, second.outcome)
        assertEquals(first.state, second.state)
    }

    /** Re-arms once the camera leaves range: nothing in range clears the latch,
     *  so the next camera chimes. This is what makes the latch per-camera rather
     *  than a permanent mute. */
    @Test
    fun theLatchClearsWhenNothingIsInRange() {
        val latched = step().state
        val empty = step(state = latched, cameras = emptyList())
        assertEquals(CameraWarner.Outcome.Silent, empty.outcome)
        assertNull(empty.state.warnedAt)
    }

    /** A *different*, nearer camera appearing while still latched on the first
     *  one does warn: the latch is per-camera, not a global mute. This is the
     *  case a naive "warn once" would silently break. */
    @Test
    fun aNearerSecondCameraWarnsWhileStillLatchedOnTheFirst() {
        val far = cam(380.0, 0.0)
        val near = cam(120.0, 0.0)
        val latched = step(cameras = listOf(far)).state
        assertEquals(far.at, latched.warnedAt)

        val next = step(state = latched, cameras = listOf(far, near))
        assertEquals(CameraWarner.Outcome.Warn(near.at, "Speed camera ahead"), next.outcome)
        assertEquals(near.at, next.state.warnedAt)
    }

    /** Being in range but not too fast does **not** clear the latch: only nothing
     *  in range does. Pinned because "clear it whenever we don't warn" is the
     *  tempting simplification, and it would re-chime for the same camera as soon
     *  as you crept back over the limit. */
    @Test
    fun slowingDownInRangeDoesNotClearTheLatch() {
        val latched = step().state
        val slowed = step(state = latched, speedKmh = 100.0)
        assertEquals(CameraWarner.Outcome.Silent, slowed.outcome)
        assertSame(latched.warnedAt, slowed.state.warnedAt)
    }

    // ---- what counts as a candidate ---------------------------------------

    /** Beyond `SpeedCameras.WARN_METERS` (400.0, `SpeedCameras.kt:53`) nothing is
     *  a candidate, and the comparison itself is `<=`. 405 m of `offset` measures
     *  404.545 m and 395 m measures 394.556 m - see [cam] for the sign of that
     *  mismatch - so both fixtures clear the threshold by ~5 m rather than
     *  riding it. */
    @Test
    fun beyondTheWarnRadiusNothingIsACandidate() {
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(cam(405.0, 0.0))).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Warn(cam(395.0, 0.0).at, "Speed camera ahead"),
            step(cameras = listOf(cam(395.0, 0.0))).outcome,
        )
    }

    /** A camera behind you is not ahead of you. The boundary, stated: exactly
     *  [CameraWarner.AHEAD_WEDGE_DEG] **is** inside the wedge, because
     *  `RoadRoulette.withinWedge` compares `<=`. Exact rather than approximate:
     *  the camera is placed due north, whose bearing reads back as exactly 0.0,
     *  so the difference against a heading of 45.0 is exactly 45.0. */
    @Test
    fun theWedgeRejectsACameraBehindYouAndIncludesItsOwnBoundary() {
        assertEquals(CameraWarner.Outcome.Silent, step(headingDeg = 180.0).outcome)
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(headingDeg = CameraWarner.AHEAD_WEDGE_DEG).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(headingDeg = CameraWarner.AHEAD_WEDGE_DEG + 0.1).outcome,
        )
    }

    /** A null heading skips the wedge entirely and warns on distance alone - the
     *  stopped-phone case, and the one branch no replay reaches because the mock
     *  provider always derives a bearing. */
    @Test
    fun aNullHeadingWarnsOnDistanceAlone() {
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(headingDeg = null).outcome,
        )
        // Even one directly behind: with no heading there is no "behind".
        val behind = cam(200.0, 180.0)
        assertEquals(
            CameraWarner.Outcome.Warn(behind.at, "Speed camera ahead"),
            step(cameras = listOf(behind), headingDeg = null).outcome,
        )
    }

    /** Nearest camera wins when two are in range, whatever order the prefetch
     *  returned them in - the far one is put first so a `firstOrNull` regression
     *  fails here. */
    @Test
    fun theNearestCameraInRangeWins() {
        val near = cam(120.0, 0.0)
        val far = cam(380.0, 0.0)
        assertEquals(
            CameraWarner.Outcome.Warn(near.at, "Speed camera ahead"),
            step(cameras = listOf(far, near)).outcome,
        )
    }
}
