package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
     *  (400.0, the floor of `CameraWarner.warnMeters`) - it measures 199.775 m. */
    private val ahead = cam(200.0, 0.0)

    private fun step(
        state: CameraWarner.State = CameraWarner.State(),
        cameras: List<SpeedCameras.Camera> = listOf(ahead),
        headingDeg: Double? = 0.0,
        speedKmh: Double = 130.0,
        limitKmh: Double? = 120.0,
        options: CameraWarner.Options = CameraWarner.Options(),
    ) = CameraWarner.onFix(
        state = state.copy(options = options), cameras = cameras, at = here,
        headingDeg = headingDeg, speedKmh = speedKmh, limitKmh = limitKmh,
    )

    // ---- the rider's settings (#496) ---------------------------------------

    private val speeding = "Speed camera ahead"

    /** Off silences every kind, speeding or not, and leaves no latch behind:
     *  switching back on mid-approach warns for the camera still ahead. */
    @Test
    fun switchedOffSilencesEveryKindAndClearsTheLatch() {
        val off = CameraWarner.Options(enabled = false)
        val redLight = ahead.copy(kind = SpeedCameras.CameraKind.RED_LIGHT)
        val warned = CameraWarner.State(warnedAt = ahead.at)
        val muted = step(state = warned, speedKmh = 200.0, options = off)
        assertEquals(CameraWarner.Outcome.Silent, muted.outcome)
        assertNull(muted.state.warnedAt)
        assertEquals(CameraWarner.Outcome.Silent, step(cameras = listOf(redLight), options = off).outcome)
        assertEquals(CameraWarner.Outcome.Warn(ahead.at, speeding), step(state = muted.state).outcome)
    }

    @Test
    fun whenNotSpeedingWarnsAtOrUnderAKnownLimit() {
        val opts = CameraWarner.Options(whenNotSpeeding = true)
        assertEquals(CameraWarner.Outcome.Warn(ahead.at, speeding), step(speedKmh = 50.0, options = opts).outcome)
        // Not a licence for an unknown limit: that is the other switch.
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 50.0, limitKmh = null, options = opts).outcome)
    }

    @Test
    fun whenLimitUnknownWarnsWithNoLimit() {
        val opts = CameraWarner.Options(whenLimitUnknown = true)
        assertEquals(CameraWarner.Outcome.Warn(ahead.at, speeding), step(limitKmh = null, options = opts).outcome)
        // A known limit is still judged: under it stays silent.
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 100.0, options = opts).outcome)
    }

    /** The defaults are the rule as it stood before the settings existed. */
    @Test
    fun defaultOptionsKeepTodaysBehaviour() {
        assertEquals(CameraWarner.Options(true, whenNotSpeeding = false, whenLimitUnknown = false), CameraWarner.Options())
    }

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

    // ---- the camera's own tagged limit (#319) ------------------------------

    /** Where the camera tags its own limit, that one is judged, not the
     *  caller's - "it is the limit actually being enforced" (issue #319). */
    @Test
    fun theCamerasOwnTaggedLimitWinsOverTheCallersWhenBothExist() {
        val taggedAhead = SpeedCameras.Camera(ahead.at, maxspeedKmh = 90.0)
        assertEquals(
            CameraWarner.Outcome.Warn(taggedAhead.at, "Speed camera ahead"),
            step(cameras = listOf(taggedAhead), speedKmh = 95.0, limitKmh = 120.0).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(taggedAhead), speedKmh = 92.0, limitKmh = 120.0).outcome,
        )
    }

    /** A camera's own limit warns even where the caller has none at all - a
     *  road with no route or ambient limit is no longer silent by default. */
    @Test
    fun theCamerasOwnTaggedLimitWarnsWhenTheCallerHasNone() {
        val taggedAhead = SpeedCameras.Camera(ahead.at, maxspeedKmh = 70.0)
        assertEquals(
            CameraWarner.Outcome.Warn(taggedAhead.at, "Speed camera ahead"),
            step(cameras = listOf(taggedAhead), speedKmh = 80.0, limitKmh = null).outcome,
        )
    }

    /** An untagged camera behaves exactly as before: the caller's limit alone. */
    @Test
    fun anUntaggedCameraStillFallsBackToTheCallersLimit() {
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 120.0).outcome)
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(speedKmh = 120.0 + CameraWarner.OVER_LIMIT_KMH + 0.01).outcome,
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

    /** Beyond `SpeedCameras.WARN_METERS` (400.0) nothing is a candidate at a
     *  speed whose 15 s reach falls under that floor - 60 km/h covers 250 m - and
     *  the comparison itself is `<=`. 405 m of `offset` measures 404.545 m and
     *  395 m measures 394.556 m - see [cam] for the sign of that mismatch - so
     *  both fixtures clear the threshold by ~5 m rather than riding it. */
    @Test
    fun beyondTheWarnRadiusNothingIsACandidate() {
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(cam(405.0, 0.0)), speedKmh = 60.0, limitKmh = 50.0).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Warn(cam(395.0, 0.0).at, "Speed camera ahead"),
            step(cameras = listOf(cam(395.0, 0.0)), speedKmh = 60.0, limitKmh = 50.0).outcome,
        )
    }

    /** 15 s of travel, floored at 400 m (#497): 50 km/h would reach 208 m and
     *  90 km/h 375 m, so both sit on the floor; 130 km/h reaches 541.7 m. */
    @Test
    fun theWarnDistanceIsFifteenSecondsOfTravelFlooredAt400m() {
        assertEquals(400.0, CameraWarner.warnMeters(50.0))
        assertEquals(400.0, CameraWarner.warnMeters(90.0))
        assertEquals(541.667, CameraWarner.warnMeters(130.0), 0.001)
        assertEquals(400.0, CameraWarner.warnMeters(0.0))
    }

    /** At 130 km/h a camera 520 m out (measures ~519.4 m) is inside the
     *  541.7 m reach and warns; at 100 km/h over a 90 limit the reach is
     *  416.7 m and the same camera stays silent - so the scaled distance, not
     *  the floor, is what `onFix` filters on. */
    @Test
    fun onFixFiltersOnTheSpeedScaledDistance() {
        val far = cam(520.0, 0.0)
        assertEquals(
            CameraWarner.Outcome.Warn(far.at, "Speed camera ahead"),
            step(cameras = listOf(far), speedKmh = 130.0).outcome,
        )
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(far), speedKmh = 100.0, limitKmh = 90.0).outcome,
        )
    }

    /** Braking after the warning shrinks the reach faster than the distance:
     *  warned at ~529 m doing 130 km/h, then 120 m on at 58 km/h the camera is
     *  ~410 m out against a 400 m reach. It must stay latched - a red-light
     *  camera chimes at any speed, so clearing here would chime it twice. */
    @Test
    fun brakingAfterTheWarningDoesNotChimeTheSameCameraAgain() {
        val redLight = cam(530.0, 0.0).copy(kind = SpeedCameras.CameraKind.RED_LIGHT)
        fun fix(state: CameraWarner.State, northM: Double, speedKmh: Double) = CameraWarner.onFix(
            state = state, cameras = listOf(redLight), at = RoadRoulette.offset(here, northM, 0.0),
            headingDeg = 0.0, speedKmh = speedKmh, limitKmh = 120.0,
        )
        val warned = fix(CameraWarner.State(), 0.0, 130.0)
        assertEquals(CameraWarner.Outcome.Warn(redLight.at, "Red light camera ahead"), warned.outcome)

        val braking = fix(warned.state, 120.0, 58.0)
        assertEquals(CameraWarner.Outcome.Silent, braking.outcome)
        assertEquals(redLight.at, braking.state.warnedAt)

        val closer = fix(braking.state, 140.0, 55.0)
        assertEquals(CameraWarner.Outcome.Silent, closer.outcome)
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

    // ---- camera kind (#317) ------------------------------------------------

    /** A red-light camera doesn't measure speed - it warns at or under the
     *  limit, exactly the case a speed camera stays silent for. */
    @Test
    fun aRedLightCameraWarnsAtOrUnderTheLimit() {
        val redLight = ahead.copy(kind = SpeedCameras.CameraKind.RED_LIGHT)
        assertEquals(
            CameraWarner.Outcome.Warn(redLight.at, "Red light camera ahead"),
            step(cameras = listOf(redLight), speedKmh = 100.0).outcome,
        )
    }

    /** Same rule holds with no limit known at all - a plain speed camera would
     *  stay silent (see [silentWhenTheLimitIsUnknownAtAnySpeed]); a red-light
     *  camera has nothing to do with the limit in the first place. */
    @Test
    fun aRedLightCameraWarnsEvenWithNoLimitKnown() {
        val redLight = ahead.copy(kind = SpeedCameras.CameraKind.RED_LIGHT)
        assertEquals(
            CameraWarner.Outcome.Warn(redLight.at, "Red light camera ahead"),
            step(cameras = listOf(redLight), limitKmh = null).outcome,
        )
    }

    /** A device that's both kinds warns like a red-light camera - worth
     *  announcing regardless of speed - with wording naming both. */
    @Test
    fun aCombinedCameraWarnsAtOrUnderTheLimitWithBothNamed() {
        val combined = ahead.copy(kind = SpeedCameras.CameraKind.COMBINED)
        assertEquals(
            CameraWarner.Outcome.Warn(combined.at, "Speed and red light camera ahead"),
            step(cameras = listOf(combined), speedKmh = 100.0).outcome,
        )
    }

    /** The latch is still per-camera-position, not per-kind: a second fix at
     *  the same red-light camera stays silent. */
    @Test
    fun aRedLightCameraStillLatchesOncePerPosition() {
        val redLight = ahead.copy(kind = SpeedCameras.CameraKind.RED_LIGHT)
        val first = step(cameras = listOf(redLight), speedKmh = 100.0)
        assertEquals(redLight.at, first.state.warnedAt)
        val second = step(state = first.state, cameras = listOf(redLight), speedKmh = 100.0)
        assertEquals(CameraWarner.Outcome.Silent, second.outcome)
    }

    /** A plain speed camera's own behaviour is unaffected by the kind branch -
     *  every existing case above already pins this; this one names it. */
    @Test
    fun aPlainSpeedCameraIsUnaffectedByKind() {
        assertEquals(SpeedCameras.CameraKind.SPEED, ahead.kind)
        assertEquals(CameraWarner.Outcome.Silent, step(speedKmh = 100.0).outcome)
    }

    // ---- camera:direction (#318) -------------------------------------------

    /** Camera due north of you, facing due south - the tag OSM mappers give a
     *  camera that watches northbound traffic i.e. traffic heading the same way
     *  you are. It faces you: it warns. */
    @Test
    fun aCameraFacingYouStillWarns() {
        val facingYou = SpeedCameras.Camera(ahead.at, facingDeg = 180.0)
        assertEquals(
            CameraWarner.Outcome.Warn(facingYou.at, "Speed camera ahead"),
            step(cameras = listOf(facingYou)).outcome,
        )
    }

    /** Same camera, but tagged facing the same way you're driving - it watches
     *  the opposite carriageway, not you. Ahead-of-you by the wedge alone, but
     *  not pointed at you, so silent - the false warning issue #318 reports. */
    @Test
    fun aCameraOnTheOppositeCarriagewayDoesNotWarn() {
        val facingAway = SpeedCameras.Camera(ahead.at, facingDeg = 0.0)
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(facingAway)).outcome,
        )
    }

    /** An untagged camera behaves exactly as before the tag was read at all -
     *  the wedge alone decides. */
    @Test
    fun anUntaggedCameraIgnoresFacingEntirely() {
        assertEquals(
            CameraWarner.Outcome.Warn(ahead.at, "Speed camera ahead"),
            step(cameras = listOf(ahead)).outcome,
        )
    }

    /** The boundary [CameraWarner.CAMERA_FACING_TOLERANCE_DEG] names is
     *  inclusive: exactly that far off dead-opposite still faces you, a tenth
     *  of a degree past it does not. Pinned because a road bends by roughly
     *  this much under a gantry, so the constant is a real tuning decision
     *  rather than a round number. */
    @Test
    fun theFacingToleranceBoundaryIsInclusive() {
        val facingYou = SpeedCameras.Camera(
            ahead.at,
            facingDeg = 180.0 + CameraWarner.CAMERA_FACING_TOLERANCE_DEG,
        )
        assertEquals(
            CameraWarner.Outcome.Warn(facingYou.at, "Speed camera ahead"),
            step(cameras = listOf(facingYou)).outcome,
        )
        val justPast = SpeedCameras.Camera(
            ahead.at,
            facingDeg = 180.0 + CameraWarner.CAMERA_FACING_TOLERANCE_DEG + 0.1,
        )
        assertEquals(
            CameraWarner.Outcome.Silent,
            step(cameras = listOf(justPast)).outcome,
        )
    }

    // ---- the countdown (#548) ----------------------------------------------

    /** A drive due north past a camera 300 m ahead, at 100 km/h under a 50
     *  limit: one fix per 50 m. [northM] is how far the rider has come. */
    private val countdownCam = cam(300.0, 0.0)

    private fun drive(state: CameraWarner.State, northM: Double) = CameraWarner.onFix(
        state = state, cameras = listOf(countdownCam), at = RoadRoulette.offset(here, northM, 0.0),
        headingDeg = 0.0, speedKmh = 100.0, limitKmh = 50.0,
    )

    /** The countdown shrinks fix by fix while the warning itself fires once:
     *  a banner needs the distance every fix, the chime and speech only the
     *  first. Without it #549/#550 have nothing to count down from. */
    @Test
    fun theCountdownDecreasesFixByFixWhileTheWarningFiresOnce() {
        var state = CameraWarner.State()
        val distances = mutableListOf<Double>()
        var warnings = 0
        for (northM in listOf(0.0, 50.0, 100.0, 150.0, 200.0, 250.0)) {
            val step = drive(state, northM)
            state = step.state
            if (step.outcome is CameraWarner.Outcome.Warn) warnings++
            val countdown = assertNotNull(step.countdown, "no countdown ${300 - northM} m before the camera")
            assertEquals(countdownCam.at, countdown.at)
            assertEquals("Speed camera ahead", countdown.text)
            distances += countdown.distanceMeters
        }
        assertEquals(1, warnings, "the warning must fire once per camera, not once per fix")
        assertTrue(distances.zipWithNext().all { (a, b) -> b < a }, "countdown must shrink every fix: $distances")
        // Straight-line metres, within the 1-in-890 offset/haversine skew.
        assertEquals(300.0, distances.first(), 1.0)
        assertEquals(50.0, distances.last(), 1.0)
    }

    /** Past the camera it leaves the wedge: the countdown ends and the latch
     *  clears, so the next camera warns afresh. */
    @Test
    fun theCountdownEndsOnceTheCameraIsPassed() {
        val near = drive(drive(CameraWarner.State(), 0.0).state, 280.0)
        assertNotNull(near.countdown)
        val passed = drive(near.state, 320.0)
        assertNull(passed.countdown)
        assertEquals(CameraWarner.Outcome.Silent, passed.outcome)
        assertNull(passed.state.warnedAt)
    }

    /** No warning, no countdown: a camera the rule stays silent for (here at
     *  the limit) must not show a banner either. */
    @Test
    fun noCountdownForACameraThatWasNotWorthWarning() {
        assertNull(step(speedKmh = 100.0).countdown)
    }

    /** Slowing under the limit after the warning keeps the countdown - the
     *  rider did what it asked - and switching warnings off ends it. */
    @Test
    fun slowingDownKeepsTheCountdownAndSwitchingOffEndsIt() {
        val warned = step()
        assertNotNull(step(state = warned.state, speedKmh = 100.0).countdown)
        assertNull(step(state = warned.state, options = CameraWarner.Options(enabled = false)).countdown)
    }

    /** A nearer camera that is not worth warning for doesn't hide the
     *  countdown to the one that was: it follows the warned camera, not the
     *  nearest. */
    @Test
    fun theCountdownFollowsTheWarnedCameraNotTheNearest() {
        val redLight = cam(380.0, 0.0).copy(kind = SpeedCameras.CameraKind.RED_LIGHT)
        val warned = step(cameras = listOf(redLight), speedKmh = 50.0)
        assertEquals(redLight.at, warned.state.warnedAt)
        val nearSpeedCam = cam(120.0, 0.0)
        val next = step(state = warned.state, cameras = listOf(redLight, nearSpeedCam), speedKmh = 50.0)
        assertEquals(CameraWarner.Outcome.Silent, next.outcome)
        assertEquals(redLight.at, next.countdown?.at)
        assertEquals("Red light camera ahead", next.countdown?.text)
        assertEquals(SpeedCameras.CameraKind.RED_LIGHT, next.countdown?.kind)
    }
}
