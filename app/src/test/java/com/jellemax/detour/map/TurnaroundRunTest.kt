package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoutePreferences
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.SpinFailure
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.TurnaroundLoop
import com.jellemax.detour.data.TurnaroundRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pins how [runTurnaroundLoop] maps a built loop or a failure onto the
 *  [SpinOutcome] the map already lands loop spins from (#889). */
class TurnaroundRunTest {

    private val from = LatLon(52.0, 5.0)
    private val pin = LatLon(52.1, 5.2)
    private val route = RouteResult(
        polyline = listOf(from, pin, from),
        waypoints = emptyList(),
        distanceMeters = 40_000.0,
        timeMs = 2_400_000L,
    )

    private val request = TurnaroundRequest(from, pin, TravelMode.CAR.ghProfile, RoutePreferences())

    private fun run(build: suspend (ServerConfig, TurnaroundRequest) -> TurnaroundLoop.Result) = runBlocking {
        runTurnaroundLoop(ServerConfig(), request, build) { "repeats $it%" }
    }

    @Test
    fun theRequestGoesToTheBuildAsAsked() {
        var asked: TurnaroundRequest? = null
        run { _, r -> asked = r; TurnaroundLoop.Result(route, overlap = 0.0) }
        assertSame(request, asked)
    }

    @Test
    fun aLoopThatRepeatsLittleCarriesNoNote() {
        val out = run { _, _ -> TurnaroundLoop.Result(route, overlap = 0.15) }
        out as SpinOutcome.Loop
        assertSame(route, out.route)
        assertNull("15 % is still a different road home", out.warning)
    }

    @Test
    fun aLoopThatRepeatsMoreThan15PercentSaysHowMuch() {
        val out = run { _, _ -> TurnaroundLoop.Result(route, overlap = 0.426) }
        assertEquals("repeats 43%", (out as SpinOutcome.Loop).warning)
    }

    @Test
    fun aSpinFailureReachesTheRiderAsItsOwnSentence() {
        val out = run { _, _ -> throw SpinFailure("No route to the turnaround: no road") }
        assertEquals(SpinOutcome.Failed("No route to the turnaround: no road"), out)
    }

    @Test
    fun aCancelledBuildIsRethrownNotReported() {
        try {
            run { _, _ -> throw CancellationException("rider left") }
            fail("a cancelled build must propagate")
        } catch (e: CancellationException) {
            assertEquals("rider left", e.message)
        }
    }

    @Test
    fun aPlainSpinHasNoPinToLose() {
        assertTrue(loopViaPinKept(through = null, destination = null, turnaround = null))
        assertTrue(loopViaPinKept(through = null, destination = pin, turnaround = null))
    }

    @Test
    fun theDockBuildKeepsItsPinWhileItIsStillTheDestination() {
        assertTrue(loopViaPinKept(through = pin, destination = pin, turnaround = null))
        assertTrue(loopViaPinKept(through = pin, destination = LatLon(pin.lat, pin.lon), turnaround = null))
    }

    @Test
    fun aRespinKeepsItsPinWhileTheLandedLoopStillHoldsIt() {
        // #922: the landed loop cleared the destination; the pin lives in turnaround.
        assertTrue(loopViaPinKept(through = pin, destination = null, turnaround = pin))
    }

    @Test
    fun aPinClearedOrRepickedMeanwhileDropsTheBuild() {
        val other = LatLon(51.9, 4.8)
        assertFalse(loopViaPinKept(through = pin, destination = null, turnaround = null)) // ✕
        assertFalse(loopViaPinKept(through = pin, destination = other, turnaround = null)) // re-picked
        assertFalse(loopViaPinKept(through = pin, destination = other, turnaround = pin))
    }
}
