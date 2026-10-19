package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * **Mostly characterisation.** maxke24/Detour#22 (the average vanishing a few
 * hundred metres in and never re-arming) was undiagnosed when these were
 * written, and most of them still deliberately encode no cause: the parser
 * theory was refuted (formerly `data/ParsingTest.kt`'s `SpeedCameraSectionTest`,
 * removed with the Overpass parse path it tested against), and the
 * shape below is what makes the suppression observable at all - every transition
 * that can null [SectionAverageTracker.Reading] is one step of one fix, so a test
 * can drive the recorded sequence offline and watch which one fires.
 *
 * Driving it is what settled it, and the answer was that **none** of them fires:
 * see [aRebuiltStateLosesTheMeasurementAndCannotResumeMidSection], which is the
 * one test here that does encode a cause. The clear was the state being rebuilt
 * between fixes rather than any exit this machine can take.
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

    // ---- the reported transition ------------------------------------------

    /**
     * Every exit reports the clause that caused it, and the distance that
     * decides `REACHED_END` comes out with it.
     *
     * The reason is the whole point of `step`: an early clear used to leave no
     * record of which clause fired, so #22 was argued from the outside for a
     * month. The three fixtures are the same ones
     * [theHundredAndFiftyMetreFloorStopsAnImmediateExit], [overshootEndsIt] and
     * [theTimeoutEndsItAndExactlyTheTimeoutDoesNot] already pin, so a reason that
     * disagreed with the state change would fail both here and there.
     */
    @Test
    fun eachExitReportsWhichClauseEndedIt() {
        val armed = armAt(shortEntry, 0.0, listOf(shortSection()))

        // 180 m: over the 150 m floor and 20 m from the exit node.
        val end = SectionAverageTracker.step(
            armed, listOf(shortSection()), at(shortEntry, 180.0, 0.0), 0.0, 20.0, t0 + 10_000L,
        )
        val cleared = end.outcome as SectionAverageTracker.Outcome.Cleared
        assertEquals(SectionAverageTracker.ExitReason.REACHED_END, cleared.reason)
        assertTrue(cleared.nearestExitGateMeters < SectionAverageTracker.SECTION_GATE_METERS)
        assertEquals(180.0, cleared.accMeters, 1.0)
        assertEquals(10_000L, cleared.elapsedMs)

        // 700 m: 500 m past the exit node, so well outside its gate.
        val over = SectionAverageTracker.step(
            armed, listOf(shortSection()), at(shortEntry, 700.0, 0.0), 0.0, 20.0, t0 + 40_000L,
        )
        val overshot = over.outcome as SectionAverageTracker.Outcome.Cleared
        assertEquals(SectionAverageTracker.ExitReason.OVERSHOT, overshot.reason)
        // The field that convicts #22: an overshoot exit whose gate is nowhere near.
        assertTrue(overshot.nearestExitGateMeters > SectionAverageTracker.SECTION_GATE_METERS)

        val late = SectionAverageTracker.step(
            armed, listOf(shortSection()), at(shortEntry, 50.0, 0.0), 0.0, 20.0,
            nowMs = t0 + SectionAverageTracker.TIMEOUT_MS + 1,
        )
        assertEquals(
            SectionAverageTracker.ExitReason.TIMED_OUT,
            (late.outcome as SectionAverageTracker.Outcome.Cleared).reason,
        )
    }

    /**
     * Arming reports the section it chose, how far its far end is, and how many
     * it could have chosen.
     *
     * `candidates` is the field that answers the question #22 could not:
     * whether a *short* relation sharing this gantry was preferred to the long
     * one the rider is driving. Both E40 relations meet at the Bertem node, so
     * two candidates at one gate is a real configuration, not a contrived one.
     */
    @Test
    fun armingReportsTheChosenSectionItsFarEndAndHowManyItCouldHavePicked() {
        val nearer = section()
        val farther = section(endA = listOf(at(westGate, 40.0, eastward)), endB = listOf(eastGate))
        val step = SectionAverageTracker.step(
            SectionAverageTracker.State(), listOf(farther, nearer),
            at = westGate, headingDeg = eastward, speedMps = 33.0, nowMs = t0,
        )
        val armed = step.outcome as SectionAverageTracker.Outcome.Armed
        assertSame(nearer, armed.section)
        assertEquals(2, armed.candidates)
        assertEquals(westGate, armed.at)
        // The far end, not the one just passed - so a short exitGate against a
        // long span reads as the wrong section having been picked.
        assertEquals(RoadRoulette.distanceMeters(westGate, eastGate), armed.exitGateMeters, 1.0)
    }

    /** A fix that neither arms nor ends reports [Silent], and a fix that cannot
     *  arm leaves the state untouched - so a log driven off outcomes prints one
     *  line per transition rather than one per fix. */
    @Test
    fun anOrdinaryFixIsSilent() {
        val sections = listOf(section())
        val armed = armAt(westGate, eastward, sections)
        val mid = SectionAverageTracker.step(
            armed, sections, at(westGate, 1_000.0, eastward), eastward, 33.0, t0 + 36_000L,
        )
        assertEquals(SectionAverageTracker.Outcome.Silent, mid.outcome)

        val nowhere = SectionAverageTracker.step(
            SectionAverageTracker.State(), sections,
            at = at(westGate, 4_000.0, eastward), headingDeg = eastward, speedMps = 33.0, nowMs = t0,
        )
        assertEquals(SectionAverageTracker.Outcome.Silent, nowhere.outcome)
        assertEquals(SectionAverageTracker.State(), nowhere.state)
    }

    /** [SectionAverageTracker.onFix] is [SectionAverageTracker.step]'s state and
     *  nothing else, on the same inputs. The two must not drift: every test above
     *  this section drives `onFix`, and would stop covering the real machine if
     *  it grew a path of its own. */
    @Test
    fun onFixIsExactlyStepsState() {
        val sections = listOf(section())
        val args = listOf(westGate, at(westGate, 3_000.0, eastward), eastGate)
        var viaOnFix = SectionAverageTracker.State()
        var viaStep = SectionAverageTracker.State()
        for ((i, p) in args.withIndex()) {
            val ms = t0 + i * 100_000L
            viaOnFix = SectionAverageTracker.onFix(viaOnFix, sections, p, eastward, 33.0, ms)
            viaStep = SectionAverageTracker.step(viaStep, sections, p, eastward, 33.0, ms).state
            assertEquals(viaOnFix, viaStep)
        }
    }

    // ---- resuming versus rebuilding the holder ----------------------------

    /** When a fix [meters] along at 33 m/s lands, so a transit reads as one. */
    private fun msAt(meters: Double) = t0 + (meters / 33.0 * 1000.0).toLong()

    /**
     * The mechanism behind maxke24/Detour#22, made executable.
     *
     * The issue reported the average vanishing a few hundred metres into a
     * 3 852 m section and never re-arming, and its own arithmetic ruled out all
     * three exit conditions: `reachedEnd` needed a gate node **3 529 m** away,
     * `overshot`'s floor is **680 m** for *any* span at or above
     * `SpeedCameras.MIN_SPAN_M`, and `timedOut` needs half an hour against nine
     * elapsed seconds. Re-checked here against the real relation geometry from
     * `api.openstreetmap.org` - `15685856` spans Bertem to Leuven at 3 854 m -
     * and it holds. **Nothing this machine can do clears a reading 306 m in**,
     * so the clear was never an exit. It was the state being *rebuilt* rather
     * than carried across a composition.
     *
     * Rebuilt, the measurement is unrecoverable, and that is the report's second
     * symptom rather than a separate defect: arming only ever fires within
     * [SectionAverageTracker.SECTION_GATE_METERS] of an end, and a vehicle
     * mid-section is past the entry gate and not yet at the far one. The far
     * gantry does not rescue it either - arriving there, the only end left to
     * head towards is the one behind you, outside
     * [SectionAverageTracker.SECTION_WEDGE_DEG].
     *
     * `ui/RetainedMap.kt:130-143` is the fix (#86) and states this symptom in
     * prose. This is the executable form, because the holder that KDoc describes
     * is an Android class commonTest cannot reach - so nothing currently fails
     * if a later change stops carrying the state.
     */
    @Test
    fun aRebuiltStateLosesTheMeasurementAndCannotResumeMidSection() {
        val sections = listOf(section())
        var st = armAt(westGate, eastward, sections)
        st = SectionAverageTracker.onFix(
            st, sections, at(westGate, 3_000.0, eastward), eastward, 33.0, msAt(3_000.0),
        )
        assertNotNull(st.active)
        assertNotNull(st.reading.averageKmh)

        // Exactly what a rebuilt holder hands the next fix: the default State.
        var rebuilt = SectionAverageTracker.State()
        for (m in listOf(3_100.0, 4_000.0, 5_000.0, 6_000.0, 7_000.0)) {
            rebuilt = SectionAverageTracker.onFix(
                rebuilt, sections, at(westGate, m, eastward), eastward, 33.0, msAt(m),
            )
            assertNull(rebuilt.active)
            assertNull(rebuilt.reading.averageKmh)
        }
        // The far gantry, reached with nothing armed. Still nothing: the entry it
        // would need is 180deg behind.
        rebuilt = SectionAverageTracker.onFix(
            rebuilt, sections, eastGate, eastward, 33.0, msAt(7_936.0),
        )
        assertNull(rebuilt.active)
        assertNull(rebuilt.reading.averageKmh)
    }

    /**
     * The control for [aRebuiltStateLosesTheMeasurementAndCannotResumeMidSection]:
     * the identical fix sequence, with the state carried, measures the whole
     * section and clears at the far gantry by `reachedEnd`.
     *
     * Carrying it across the boundary is all `ui/RetainedMap.kt` does, and the pair
     * is what makes that load-bearing rather than tidy.
     */
    @Test
    fun aCarriedStateMeasuresTheWholeSectionAndClearsAtTheFarGantry() {
        val sections = listOf(section())
        var st = armAt(westGate, eastward, sections)
        for (m in listOf(3_000.0, 3_100.0, 4_000.0, 5_000.0, 6_000.0, 7_000.0)) {
            st = SectionAverageTracker.onFix(
                st, sections, at(westGate, m, eastward), eastward, 33.0, msAt(m),
            )
            assertNotNull(st.active)
        }
        val done = SectionAverageTracker.onFix(
            st, sections, eastGate, eastward, 33.0, msAt(7_936.0),
        )
        assertNull(done.active) // reachedEnd, not overshot: the bound is 11 530 m
        assertTrue(done.accMeters > 7_900.0)
        assertNull(done.reading.averageKmh)
    }

    // ---- the posted limit -------------------------------------------------

    /**
     * `Reading.limitKmh` is `Section.maxspeedKmh`, carried straight through.
     *
     * **This test is the only coverage of the posted-limit half that exists or
     * can exist from the current fixtures.** Neither E40 relation tags `maxspeed`
     * on the section itself in the backend's dataset, only the device nodes do -
     * so `Section.maxspeedKmh` is null for every replay of every trajectcontrole
     * fixture, and no replay can exercise the over/under-limit comparison or the
     * red chip. Relation **16251379** is the tagged alternative
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

    // ---- a road crossing the section (#740) ---------------------------------

    /** The E40 section with its road as the line: west gate, a vertex 300 m off
     *  the chord at its middle, east gate - so the line is a road and not just
     *  the two device nodes. */
    private val bend = at(at(westGate, 3_968.0, eastward), 300.0, eastward - 90.0)
    private fun roadSection() = section().copy(line = listOf(westGate, bend, eastGate))

    /** A bridge over the motorway at the west gantry, 56deg off the section's
     *  axis. The far end is inside the 75deg wedge, so the gate and the wedge
     *  alone take it for an entry - which is the arming #740 reported. */
    private val bridge = eastward - 56.0

    @Test
    fun aRoadCrossingAtTheGantryDoesNotArmASectionWithARoadLine() {
        assertNotNull(SectionAverageTracker.sectionExitGate(roadSection(), westGate, bridge))
        val st = armAt(westGate, bridge, listOf(roadSection()))
        assertNull(st.active)
        assertNull(st.reading.limitKmh)
    }

    /** The rider actually driving the section still arms with a road line, and
     *  following that line gantry to gantry is not mistaken for leaving it. */
    @Test
    fun followingTheRoadLineArmsAndReachesTheFarEnd() {
        val sections = listOf(roadSection())
        val firstLeg = RoadRoulette.bearingDeg(westGate, bend)
        var st = armAt(westGate, firstLeg, sections)
        assertNotNull(st.active)
        val path = (1..39).map { at(westGate, it * 100.0, firstLeg) } + bend +
            (1..40).map { at(bend, it * 100.0, RoadRoulette.bearingDeg(bend, eastGate)) }
        var outcome: SectionAverageTracker.Outcome = SectionAverageTracker.Outcome.Silent
        for ((i, p) in path.withIndex()) {
            val step = SectionAverageTracker.step(st, sections, p, null, 33.0, t0 + (i + 1) * 3_000L)
            st = step.state
            outcome = step.outcome
            if (outcome is SectionAverageTracker.Outcome.Cleared) break
        }
        assertEquals(
            SectionAverageTracker.ExitReason.REACHED_END,
            (outcome as SectionAverageTracker.Outcome.Cleared).reason,
        )
    }

    /** Taking an exit off the section clears it once the rider has stayed
     *  [SectionAverageTracker.OFF_ROAD_METERS] off the road line for
     *  [SectionAverageTracker.LEFT_ROAD_HOLD_METERS] over
     *  [SectionAverageTracker.LEFT_ROAD_MIN_FIXES] fixes - not after the 11.5 km
     *  overshoot bound. */
    @Test
    fun leavingTheRoadLineClearsWellBeforeTheOvershootBound() {
        val sections = listOf(roadSection())
        val firstLeg = RoadRoulette.bearingDeg(westGate, bend)
        val armed = armAt(westGate, firstLeg, sections)
        val exit = at(westGate, 1_000.0, firstLeg)
        val onRoad = SectionAverageTracker.step(armed, sections, exit, null, 33.0, t0 + 30_000L)
        assertEquals(SectionAverageTracker.Outcome.Silent, onRoad.outcome)
        val firstOff = SectionAverageTracker.step(
            onRoad.state, sections, at(exit, 200.0, firstLeg - 90.0), null, 33.0, t0 + 37_000L,
        )
        assertEquals(
            SectionAverageTracker.Outcome.Silent, firstOff.outcome,
            "one fix off the road line must not clear on its own",
        )
        val secondOff = SectionAverageTracker.step(
            firstOff.state, sections, at(exit, 350.0, firstLeg - 90.0), null, 33.0, t0 + 42_000L,
        )
        assertEquals(
            SectionAverageTracker.Outcome.Silent, secondOff.outcome,
            "two fixes off the road line must not clear on their own",
        )
        val stillOff = SectionAverageTracker.step(
            secondOff.state, sections, at(exit, 500.0, firstLeg - 90.0), null, 33.0, t0 + 46_000L,
        )
        val cleared = assertIs<SectionAverageTracker.Outcome.Cleared>(
            stillOff.outcome, "a third fix 300 m further off the road line should have cleared",
        )
        assertEquals(SectionAverageTracker.ExitReason.LEFT_ROAD, cleared.reason)
        assertNull(stillOff.state.reading.limitKmh, "a cleared section still shows its limit")
    }

    /** One fix scattered off the road line - a tunnel exit, an urban canyon -
     *  followed by fixes back on it keeps the measurement: it can't re-arm
     *  mid-section, so a clear here would lose the average for the whole of it. */
    @Test
    fun oneOutlierOffTheRoadLineDoesNotClear() {
        val sections = listOf(roadSection())
        val firstLeg = RoadRoulette.bearingDeg(westGate, bend)
        var st = armAt(westGate, firstLeg, sections)
        val outlier = at(at(westGate, 1_000.0, firstLeg), 400.0, firstLeg - 90.0)
        val path = (1..9).map { at(westGate, it * 100.0, firstLeg) } + outlier +
            (11..20).map { at(westGate, it * 100.0, firstLeg) }
        for ((i, p) in path.withIndex()) {
            val step = SectionAverageTracker.step(st, sections, p, null, 33.0, t0 + (i + 1) * 3_000L)
            st = step.state
            assertEquals(SectionAverageTracker.Outcome.Silent, step.outcome, "fix $i cleared the section")
        }
        assertNotNull(st.active, "the measurement was lost to one outlier")
        assertNull(st.offRoad, "back on the road line resets the off-road run")
    }

    /** One outlier ahead toward the far end and off to the side - the jump into
     *  it and back out of it each well over 200 m - keeps the measurement. It
     *  is not a rider (too far too fast), so it neither starts an off-road run
     *  nor moves the closest the far end has been, and the true fixes after it
     *  are not taken for driving away. */
    @Test
    fun anOutlierAheadAndAsideDoesNotClear() {
        for (sections in listOf(listOf(roadSection()), listOf(section()))) {
            val firstLeg = if (sections[0].line.isEmpty()) eastward else RoadRoulette.bearingDeg(westGate, bend)
            var st = armAt(westGate, firstLeg, sections)
            val outlier = at(at(westGate, 2_500.0, firstLeg), 300.0, firstLeg - 90.0)
            val path = (1..9).map { at(westGate, it * 100.0, firstLeg) } + outlier +
                (10..30).map { at(westGate, it * 100.0, firstLeg) }
            for ((i, p) in path.withIndex()) {
                val step = SectionAverageTracker.step(st, sections, p, null, 33.0, t0 + (i + 1) * 3_000L)
                st = step.state
                assertEquals(
                    SectionAverageTracker.Outcome.Silent, step.outcome,
                    "fix $i cleared the section (line: ${sections[0].line.isNotEmpty()})",
                )
            }
            assertNotNull(st.active, "the measurement was lost to one outlier ahead")
            assertNull(st.offRoad, "the outlier started an off-road run")
        }
    }

    /** Two outliers in a row off the road line, far apart from each other, are
     *  still under [SectionAverageTracker.LEFT_ROAD_MIN_FIXES]: the distance
     *  between them alone would cover the hold. */
    @Test
    fun twoOutliersOffTheRoadLineDoNotClear() {
        val sections = listOf(roadSection())
        val firstLeg = RoadRoulette.bearingDeg(westGate, bend)
        var st = armAt(westGate, firstLeg, sections)
        val path = (1..9).map { at(westGate, it * 100.0, firstLeg) } +
            at(at(westGate, 1_000.0, firstLeg), 200.0, firstLeg - 90.0) +
            at(at(westGate, 1_100.0, firstLeg), 200.0, firstLeg + 90.0) +
            (12..20).map { at(westGate, it * 100.0, firstLeg) }
        for ((i, p) in path.withIndex()) {
            val step = SectionAverageTracker.step(st, sections, p, null, 33.0, t0 + (i + 1) * 10_000L)
            st = step.state
            assertEquals(SectionAverageTracker.Outcome.Silent, step.outcome, "fix $i cleared the section")
        }
        assertNotNull(st.active, "the measurement was lost to two outliers")
    }

    /** With no road line - the relation names only its device nodes - the bridge
     *  still arms, and the readout clears once the rider has driven
     *  [SectionAverageTracker.RECEDE_METERS] further from the far end than the
     *  closest they got, which on this crossing is ~6.5 km rather than the
     *  overshoot bound's 11.5 km. */
    @Test
    fun withoutARoadLineDrivingAwayFromTheFarEndClearsIt() {
        val sections = listOf(section())
        var st = armAt(westGate, bridge, sections)
        assertNotNull(st.active)
        var cleared: SectionAverageTracker.Outcome.Cleared? = null
        var travelled = 0.0
        while (cleared == null && travelled < 11_000.0) {
            travelled += 100.0
            val step = SectionAverageTracker.step(
                st, sections, at(westGate, travelled, bridge), null, 33.0, t0 + (travelled * 30).toLong(),
            )
            st = step.state
            cleared = step.outcome as? SectionAverageTracker.Outcome.Cleared
        }
        assertEquals(SectionAverageTracker.ExitReason.LEFT_ROAD, cleared?.reason)
        assertTrue(travelled < 7_000.0, "cleared after $travelled m")
    }

    /** A line that is only the device nodes of each end is not a road, so it
     *  neither blocks arming nor clears a measurement for being off it. */
    @Test
    fun aLineOfOnlyDeviceNodesIsNotARoadLine() {
        val nodes = section().copy(line = listOf(westGate, at(westGate, 20.0, 0.0), eastGate))
        assertNull(SectionAverageTracker.roadLine(nodes))
        assertNotNull(SectionAverageTracker.roadLine(roadSection()))
        assertNotNull(armAt(westGate, bridge, listOf(nodes)).active)
    }
}
