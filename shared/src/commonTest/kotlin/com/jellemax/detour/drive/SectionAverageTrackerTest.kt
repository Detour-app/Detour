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
 * Characterises [SectionAverageTracker] - the trajectcontrole entry/exit gate and
 * the running average - transcribed from `ui/MapScreen.kt`'s section
 * `LaunchedEffect` before it was repointed, so a repoint that changes behaviour
 * fails here rather than in the field.
 *
 * **Characterisation, not correctness.** maxke24/Detour#22 (the average vanishing
 * a few hundred metres in and never re-arming) is still undiagnosed, and these
 * tests deliberately encode no cause: the parser theory is refuted
 * (`data/ParsingTest.kt`'s `SpeedCameraSectionTest`), and the shape below is
 * what makes the suppression observable at all - every transition that can null
 * [SectionAverageTracker.Reading] is now one step of one fix, so a test can drive
 * the recorded sequence offline and watch which one fires.
 *
 * Three of those transitions have **no replay coverage and never will from this
 * route**: `reachedEnd` at a far gantry entered from the near end (this route's
 * exit gate *is* the next section's entry), `timedOut`, and the red over-limit
 * chip. See [limitIsCarriedStraightThroughFromTheSection] for why the posted
 * limit is unit-tested only.
 *
 * No Android APIs, no clock, no file access: runs on JVM and Kotlin/Native both.
 */
class SectionAverageTrackerTest {

    /** Epoch millis. Realistic, because the machine subtracts timestamps and a
     *  zero base would hide a sign error. */
    private val t0 = 1_700_000_000_000L

    // The real E40 gantry clusters the recorded baseline drives through:
    // relation 15682532's west and east `device` nodes, from
    // tools/mocklocation/routes/README.md:71. One node per end here; the
    // relation has one per carriageway a few metres apart, which the machine
    // treats identically because `atGate` is an `any`.
    private val westGate = LatLon(50.86929, 4.49257)
    private val eastGate = LatLon(50.86183, 4.60503)

    /** Compass bearing west gate -> east gate, 96.0deg. The reverse is 276.0deg;
     *  both are `RoadRoulette.bearingDeg` of the two coordinates above. */
    private val eastward = 96.0

    /** [meters] from [from] along compass [bearingDeg]. `RoadRoulette.offset`
     *  takes radians and projects at a flat 111 320 m/deg, while `distanceMeters`
     *  is a haversine at r = 6 371 km, i.e. 111 194.9 m/deg - so what
     *  `distanceMeters` measures is **0.998876x** this, one part in 890 *short*
     *  rather than long, which is why every distance fixture below keeps a metre
     *  of margin on the near side of its threshold. Measured, not derived: a
     *  1 000 m `offset` at 96deg from [westGate] reads 998.886 m, the extra
     *  0.00001 coming from `offset` dividing by cos(centre lat) where the
     *  haversine uses both endpoints' cosines. */
    private fun at(from: LatLon, meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(from, meters, bearingDeg * PI / 180.0)

    /** [spanMeters] defaults to what the relation reports, 7 950 m. The two node
     *  coordinates are 7 936 m apart by `distanceMeters`; both figures are real
     *  and the machine only reads `spanMeters` for the overshoot bound. */
    private fun section(
        maxspeedKmh: Double? = null,
        endA: List<LatLon> = listOf(westGate),
        endB: List<LatLon> = listOf(eastGate),
        spanMeters: Double = 7_950.0,
    ) = SpeedCameras.Section(
        endA = endA, endB = endB, spanMeters = spanMeters, maxspeedKmh = maxspeedKmh,
    )

    /** A 200 m section - `SpeedCameras.MIN_SPAN_M` exactly - running due north.
     *  Short enough that the far gate is reachable on the fix after entering,
     *  which is the only geometry in which the 150 m floor is observable. */
    private val shortEntry = LatLon(50.0, 4.0)
    private val shortExit = at(shortEntry, 200.0, 0.0)
    private fun shortSection(maxspeedKmh: Double? = null) = section(
        maxspeedKmh = maxspeedKmh,
        endA = listOf(shortEntry),
        endB = listOf(shortExit),
        spanMeters = 200.0,
    )

    private fun armAt(
        pos: LatLon,
        headingDeg: Double,
        sections: List<SpeedCameras.Section>,
        nowMs: Long = t0,
    ) = SectionAverageTracker.onFix(
        state = SectionAverageTracker.State(),
        sections = sections,
        at = pos,
        headingDeg = headingDeg,
        speedMps = 33.0,
        nowMs = nowMs,
    )

    // ---- arming ----------------------------------------------------------

    /** The returned `exitGate` is the *far* end, never the one just passed. That
     *  is the whole reason the gate has a heading test: you pass a device node on
     *  the way out too, and matching on proximity alone used to start a
     *  measurement as you left a section. */
    @Test
    fun armsOnEnteringOneEndWhileTheFarEndIsInsideTheWedge() {
        val st = armAt(westGate, eastward, listOf(section()))
        assertEquals(listOf(eastGate), st.exitGate)
        assertNotNull(st.active)
        assertEquals(t0, st.entryMs)
        assertEquals(0.0, st.accMeters, 1e-9)
        assertEquals(westGate, st.last)
        // No average yet: nothing has accumulated.
        assertNull(st.reading.averageKmh)
    }

    /** Below the floor the bearing is noise, so a stopped phone cannot
     *  heading-test its way into a section however good the geometry is. The
     *  boundary, stated: exactly [SectionAverageTracker.ARM_MIN_MPS] does **not**
     *  arm, because the test is `takeIf { speedMps > … }`. Note this is the
     *  opposite boundary to [SpeedLimitTracker.MIN_MPS], whose call sites spell
     *  it `if (speedMps < MIN_MPS) return` - same literal 2.0, opposite edge, and
     *  neither is a typo to be tidied. */
    @Test
    fun doesNotArmAtOrBelowTheBearingNoiseFloor() {
        val stopped = SectionAverageTracker.onFix(
            SectionAverageTracker.State(), listOf(section()),
            at = westGate, headingDeg = eastward,
            speedMps = SectionAverageTracker.ARM_MIN_MPS, nowMs = t0,
        )
        assertNull(stopped.active)
        assertEquals(SectionAverageTracker.State(), stopped)

        val creeping = SectionAverageTracker.onFix(
            SectionAverageTracker.State(), listOf(section()),
            at = westGate, headingDeg = eastward,
            speedMps = SectionAverageTracker.ARM_MIN_MPS + 0.01, nowMs = t0,
        )
        assertNotNull(creeping.active)
    }

    @Test
    fun doesNotArmWithANullHeading() {
        val st = SectionAverageTracker.onFix(
            SectionAverageTracker.State(), listOf(section()),
            at = westGate, headingDeg = null, speedMps = 33.0, nowMs = t0,
        )
        assertEquals(SectionAverageTracker.State(), st)
    }

    /** Nearest match, not the first in the list: the two directions of one
     *  trajectcontrole are separate relations sharing a location, and a short
     *  section can sit inside a longer one. This is the 15682532 / 15685856 case
     *  `tools/mocklocation/routes/README.md` documents. The far candidate is put
     *  first so a `firstOrNull` regression fails here. */
    @Test
    fun nearestMatchWinsWhenTwoSectionsShareAGateLocation() {
        val nearer = section()
        val fartherEntry = at(westGate, 40.0, eastward) // still inside the 60 m gate
        val farther = section(
            endA = listOf(fartherEntry),
            endB = listOf(at(eastGate, 500.0, eastward)),
            spanMeters = 8_400.0,
        )
        val st = armAt(westGate, eastward, listOf(farther, nearer))
        assertSame(nearer, st.active)
        assertEquals(listOf(eastGate), st.exitGate)
    }

    /** A wrong-direction transit still arms, and this test says so in its name
     *  rather than asserting a refusal the code has never had.
     *  `routes/README.md`'s own measurement: at
     *  `public-trajectcontrole-reverse.txt`'s entry gate the far end bears 276deg
     *  against a heading of 284deg - 8deg off, deep inside the 75deg wedge. Pin the
     *  behaviour, not the wish. */
    @Test
    fun aWrongDirectionTransitStillArms() {
        val st = armAt(eastGate, 284.0, listOf(section()))
        assertEquals(listOf(westGate), st.exitGate)
    }

    // ---- the running average ---------------------------------------------

    /** No average until [SectionAverageTracker.MIN_ACC_METERS_FOR_AVERAGE] has
     *  accumulated - 20 m, one fix at motorway speed. Then it is
     *  `(accMeters / 1000) / elapsedHours`, exactly. */
    @Test
    fun publishesNoAverageUntilTwentyMetresHaveAccumulated() {
        var st = armAt(westGate, eastward, listOf(section()))
        val p1 = at(westGate, 15.0, eastward)
        st = SectionAverageTracker.onFix(st, listOf(section()), p1, eastward, 33.0, t0 + 1_000L)
        // ~15.0 m accumulated, under the floor.
        assertNull(st.reading.averageKmh)

        val p2 = at(westGate, 30.0, eastward)
        st = SectionAverageTracker.onFix(st, listOf(section()), p2, eastward, 33.0, t0 + 2_000L)
        assertNotNull(st.reading.averageKmh)
    }

    @Test
    fun theAverageIsAccumulatedDistanceOverElapsedTime() {
        var st = armAt(westGate, eastward, listOf(section()))
        val p1 = at(westGate, 1_000.0, eastward)
        st = SectionAverageTracker.onFix(st, listOf(section()), p1, eastward, 33.0, t0 + 36_000L)
        val acc = RoadRoulette.distanceMeters(westGate, p1)
        assertEquals(acc, st.accMeters, 1e-9)
        assertEquals((acc / 1000.0) / (36_000L / 3_600_000.0), st.reading.averageKmh!!, 1e-9)
        // And the concrete number, so a rewritten formula fails too. 1 000 m of
        // `offset` measures 998.89 m of `distanceMeters` - see [at] for the sign
        // of that - so the number is 99.89 and not 100.0; the 0.05 tolerance is
        // rounding room on the last digit, not slack in the rule.
        assertEquals(99.89, st.reading.averageKmh!!, 0.05)
    }

    /** A timestamp that has not advanced publishes nothing rather than dividing
     *  by zero: `elapsedHours > 0` guards it. Two fixes can share a millisecond
     *  once time is injected, which is exactly what makes this reachable. */
    @Test
    fun aZeroElapsedFixPublishesNoAverage() {
        var st = armAt(westGate, eastward, listOf(section()))
        st = SectionAverageTracker.onFix(
            st, listOf(section()), at(westGate, 300.0, eastward), eastward, 33.0, t0,
        )
        assertNull(st.reading.averageKmh)
    }

    /**
     * The recorded transit, reproduced offline. From
     * `tools/mocklocation/baseline/trajectcontrole-a90c3df-events.tsv`: `AVG-ON`
     * at fix 166 (t = 162.669 s), `AVG-CLEARED` at fix 543 (t = 546.927 s), and
     * `cum_m` 3 664 -> 11 610, i.e. **7 946 m over 384.258 s**.
     *
     * The chip's last on-screen read was `Ø 75`; the arithmetic gives **74.44**.
     * The gap is not a defect and not slack: `cum_m` is the route file's own
     * distance while `accMeters` integrates the GPS fixes the app received, and
     * the screenshot is one frame earlier than the clear. The number worth
     * pinning is the arithmetic over the measured pair.
     */
    @Test
    fun reproducesTheRecordedBaselineAverage() {
        val mid = at(westGate, 4_000.0, eastward) // nowhere near either gate
        val armed = SectionAverageTracker.State(
            active = section(),
            exitGate = listOf(eastGate),
            entryMs = 162_669L,
            accMeters = 7_946.0,
            last = mid,
            reading = SectionAverageTracker.Reading(null, null),
        )
        // `last == at`, so this fix adds no distance and the state is read as
        // recorded rather than extrapolated.
        val st = SectionAverageTracker.onFix(
            armed, emptyList(), at = mid, headingDeg = eastward, speedMps = 10.7,
            nowMs = 546_927L,
        )
        assertEquals(74.44373, st.reading.averageKmh!!, 1e-5)
        assertNotNull(st.active) // 7 946 m is well inside the 11 530 m overshoot bound
    }

    // ---- ending the measurement -------------------------------------------

    /** Only the end we drove in towards ends it, and not before 150 m. On a 200 m
     *  section the exit gate is reachable on the fix after entering, which is the
     *  only geometry in which the floor is observable at all - hence the short
     *  fixture rather than the E40 one. 145 m along is 55 m from the exit node,
     *  inside the 60 m gate, and still does not exit. */
    @Test
    fun theHundredAndFiftyMetreFloorStopsAnImmediateExit() {
        var st = armAt(shortEntry, 0.0, listOf(shortSection()))
        st = SectionAverageTracker.onFix(
            st, listOf(shortSection()), at(shortEntry, 145.0, 0.0), 0.0, 20.0, t0 + 8_000L,
        )
        assertNotNull(st.active)
        assertTrue(st.accMeters < SectionAverageTracker.MIN_ACC_METERS_BEFORE_EXIT)

        st = SectionAverageTracker.onFix(
            st, listOf(shortSection()), at(shortEntry, 160.0, 0.0), 0.0, 20.0, t0 + 9_000L,
        )
        assertNull(st.active)
    }

    /** On exit **both** halves of the reading go null in the same step. A section
     *  limit surviving its own section is a sign judging you against a road you
     *  have left. */
    @Test
    fun exitingNullsBothHalvesOfTheReadingInOneStep() {
        val tagged = shortSection(maxspeedKmh = 120.0)
        var st = armAt(shortEntry, 0.0, listOf(tagged))
        assertEquals(120.0, st.reading.limitKmh!!, 1e-9)
        // 180 m: over the 150 m floor and 20 m from the exit node, so this one
        // fix both publishes an average and then ends the measurement.
        st = SectionAverageTracker.onFix(
            st, listOf(tagged), at(shortEntry, 180.0, 0.0), 0.0, 20.0, t0 + 10_000L,
        )
        assertNull(st.active)
        assertEquals(emptyList<LatLon>(), st.exitGate)
        assertNull(st.last)
        assertNull(st.reading.averageKmh)
        assertNull(st.reading.limitKmh)
        // accMeters is deliberately *not* zeroed on exit - the inline version did
        // not zero it either, and the arming branch overwrites it. Characterised,
        // not tidied.
        assertTrue(st.accMeters > 0.0)
    }

    /** Overshoot ends it at `spanMeters * 1.4 + 400`: 680 m on the 200 m section.
     *  The fix is placed 700 m out, 500 m past the exit node and therefore well
     *  outside its gate, so the clear is by overshoot alone and not by
     *  `reachedEnd`. */
    @Test
    fun overshootEndsIt() {
        var st = armAt(shortEntry, 0.0, listOf(shortSection()))
        st = SectionAverageTracker.onFix(
            st, listOf(shortSection()), at(shortEntry, 700.0, 0.0), 0.0, 20.0, t0 + 40_000L,
        )
        assertNull(st.active)
    }

    /** The timeout ends it at 30 minutes, and the boundary is stated: exactly
     *  [SectionAverageTracker.TIMEOUT_MS] does **not** end it, because the test is
     *  `>`. Unreachable by any replay - no fixture sits in a section for half an
     *  hour - so this unit test is its only coverage. The fix is kept 50 m along
     *  so neither other clause can fire. */
    @Test
    fun theTimeoutEndsItAndExactlyTheTimeoutDoesNot() {
        val armed = armAt(shortEntry, 0.0, listOf(shortSection()))
        val onTheBound = SectionAverageTracker.onFix(
            armed, listOf(shortSection()), at(shortEntry, 50.0, 0.0), 0.0, 20.0,
            nowMs = t0 + SectionAverageTracker.TIMEOUT_MS,
        )
        assertNotNull(onTheBound.active)

        val pastIt = SectionAverageTracker.onFix(
            armed, listOf(shortSection()), at(shortEntry, 50.0, 0.0), 0.0, 20.0,
            nowMs = t0 + SectionAverageTracker.TIMEOUT_MS + 1,
        )
        assertNull(pastIt.active)
    }

    /**
     * Re-arms into a second section whose entry gate is the first one's exit node
     * - the back-to-back transition at 6.36 km that
     * `tools/mocklocation/routes/README.md` said had never been observed working
     * and that `trajectcontrole-a90c3df-events.tsv` then recorded: `AVG-CLEARED`
     * at fix 543, `AVG-ON` again at fix **546**.
     *
     * The earliest possible re-arm is the fix *after* the clearing one: on the
     * clearing fix the machine is in its advance branch and the arming branch does
     * not run. Three fixes in the recording, one here - both consistent with that.
     */
    @Test
    fun reArmsAcrossASharedGantryOnTheFollowingFix() {
        val shared = at(westGate, 6_360.0, eastward)
        val first = section(endA = listOf(westGate), endB = listOf(shared), spanMeters = 6_360.0)
        val second = section(endA = listOf(shared), endB = listOf(eastGate), spanMeters = 1_590.0)
        val sections = listOf(first, second)

        var st = armAt(westGate, eastward, sections)
        assertSame(first, st.active)
        st = SectionAverageTracker.onFix(
            st, sections, at(westGate, 3_000.0, eastward), eastward, 33.0, t0 + 100_000L,
        )
        assertSame(first, st.active)
        st = SectionAverageTracker.onFix(st, sections, shared, eastward, 33.0, t0 + 220_000L)
        assertNull(st.active) // reachedEnd at the shared node

        st = SectionAverageTracker.onFix(
            st, sections, at(shared, 20.0, eastward), eastward, 33.0, t0 + 221_000L,
        )
        assertSame(second, st.active)
        assertEquals(listOf(eastGate), st.exitGate)
        // The first section is not re-entered from here: its far end (the west
        // gate) is 180deg off the heading, outside the 75deg wedge.
    }

    // ---- the posted limit -------------------------------------------------

    /**
     * `Reading.limitKmh` is `Section.maxspeedKmh`, carried straight through.
     *
     * **This test is the only coverage of the posted-limit half that exists or
     * can exist from the current fixtures.** Neither E40 relation tags `maxspeed`
     * on the relation - only the `device` nodes do, and
     * `SpeedCameras.parseSection` (`SpeedCameras.kt:109`) reads the relation's
     * tags - so `Section.maxspeedKmh` is null for every replay of every
     * trajectcontrole fixture, and no replay can exercise the over/under-limit
     * comparison or the red chip. Relation **16251379** is the tagged alternative
     * (`17-public-trace-datasets.md` §3.3) if a fixture is ever wanted; that is a
     * new fixture *and* a new baseline, and not this stage's work.
     */
    @Test
    fun limitIsCarriedStraightThroughFromTheSection() {
        val tagged = armAt(westGate, eastward, listOf(section(maxspeedKmh = 120.0)))
        assertEquals(120.0, tagged.reading.limitKmh!!, 1e-9)

        val untagged = armAt(westGate, eastward, listOf(section(maxspeedKmh = null)))
        assertNull(untagged.reading.limitKmh)
    }
}
