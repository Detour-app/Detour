package com.jellemax.detour.obd2

import com.jellemax.detour.drive.Obd2Pids
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [Obd2Connection]'s pure string/stream logic ([Obd2Connection.pollPid],
 * [Obd2Connection.readUntilPrompt], [Obd2Connection.handshake]) against plain
 * [ByteArrayInputStream]/[ByteArrayOutputStream] doubles — no real Bluetooth
 * socket, no Robolectric or instrumented source set needed, matching the
 * quirks a real ELM327 clone actually hits: echo not disabled, "NO DATA",
 * and a desynced response stream.
 */
class Obd2ConnectionTest {

    private fun streamOf(text: String): ByteArrayInputStream =
        ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII))

    private fun writtenCommands(output: ByteArrayOutputStream): String =
        String(output.toByteArray(), Charsets.US_ASCII)

    @Test
    fun pollPidHappyPathParsesSpeedAndWritesTheCommand() {
        val input = streamOf("41 0D 32\r\r>")
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertEquals(listOf(0x32), result.bytes)
        assertTrue(result.answered)
        assertEquals("010D\r", writtenCommands(output))
    }

    @Test
    fun pollPidReturnsNullBytesOnHeaderMismatchWithoutThrowing() {
        // Response for PID 0C (RPM) when we asked for PID 0D (speed) - a
        // desynced clone answering the previous cycle's command late.
        val input = streamOf("41 0C 1A F8\r\r>")
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertNull(result.bytes)
        assertTrue(result.answered)
    }

    @Test
    fun pollPidHandlesNoDataWithoutThrowingAndCountsAsAnswered() {
        // A real, valid ELM327 answer meaning "this vehicle doesn't support
        // this PID" - must not throw, and (per Fix 5) must be distinguishable
        // from a genuine timeout via `answered`.
        val input = streamOf("NO DATA\r\r>")
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertNull(result.bytes)
        assertTrue(result.answered)
    }

    @Test
    fun pollPidReportsUnansweredOnAGenuineTimeout() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertNull(result.bytes)
        assertFalse(result.answered)
    }

    @Test
    fun readUntilPromptReturnsTheBufferedTextWhenTheTerminatorArrivesInTime() {
        val input = streamOf("41 0D 32\r\r>")

        val text = Obd2Connection.readUntilPrompt(input, 500L)

        assertEquals("41 0D 32\r\r", text)
    }

    @Test
    fun readUntilPromptReturnsNullWhenNothingArrivesBeforeTheTimeout() {
        val input = ByteArrayInputStream(ByteArray(0))

        val text = Obd2Connection.readUntilPrompt(input, 80L)

        assertNull(text)
    }

    @Test
    fun readUntilPromptReturnsNullWhenTextArrivesButThePromptNeverDoes() {
        // A slow or echoing clone that emits a banner/partial frame but no '>'
        // terminator before the deadline. Returning that partial text would let
        // handshake() carry on and every poll read one prompt behind for the
        // rest of the session; it must read as a timeout instead.
        val input = streamOf("ELM327 v1.5\r")

        val text = Obd2Connection.readUntilPrompt(input, 80L)

        assertNull(text)
    }

    @Test
    fun handshakeHappyPathSendsAllThreeCommandsInOrder() {
        val input = streamOf("ELM327 v1.5\r\r>OK\r\r>OK\r\r>")
        val output = ByteArrayOutputStream()

        Obd2Connection.handshake(input, output)

        assertEquals("ATZ\rATE0\rATSP0\r", writtenCommands(output))
    }

    // Times out on the ATE0 step (HANDSHAKE_TIMEOUT_MS, 2s) rather than ATZ
    // (RESET_TIMEOUT_MS, 5s) - still exercises the real production timeout ->
    // IOException path without paying the slower constant's wall-clock cost.
    @Test(expected = IOException::class)
    fun handshakeThrowsWhenAStepTimesOut() {
        val input = streamOf("ELM327 v1.5\r\r>") // answers ATZ, then goes silent
        val output = ByteArrayOutputStream()

        Obd2Connection.handshake(input, output)
    }

    /** A stream that exposes [first] immediately but only starts exposing
     *  [second] once [release] is called - models a response that genuinely
     *  hasn't arrived over the wire yet, so it can't be swept up by
     *  [Obd2Connection.pollPid]'s drain within the same call that's clearing
     *  an already-buffered stale response. */
    private class GatedInputStream(
        private val first: ByteArray,
        private val second: ByteArray,
    ) : InputStream() {
        private var pos = 0
        private var released = false

        fun release() {
            released = true
        }

        private fun visible(): ByteArray = if (released) first + second else first

        override fun available(): Int = visible().size - pos

        override fun read(): Int {
            val data = visible()
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }
    }

    /** Serves each response only after the previous one is fully read AND the
     *  stream has reported empty at least once — models ELM327 responses arriving
     *  over the wire one at a time, so [Obd2Connection.pollPid]'s drainStalePrompts
     *  (which bails the instant available() is 0) cannot sweep up a response that
     *  "hasn't arrived yet". */
    private class SequentialResponseStream(responses: List<String>) : InputStream() {
        private val chunks = ArrayDeque(responses.map { it.toByteArray(Charsets.US_ASCII) })
        private var current: ByteArray = chunks.removeFirstOrNull() ?: ByteArray(0)
        private var pos = 0
        private var sawEmpty = false

        override fun available(): Int {
            if (pos < current.size) return current.size - pos
            if (sawEmpty && chunks.isNotEmpty()) {
                current = chunks.removeFirst()
                pos = 0
                sawEmpty = false
                return current.size
            }
            sawEmpty = true
            return 0
        }

        override fun read(): Int {
            if (pos >= current.size && available() <= 0) return -1
            return current[pos++].toInt() and 0xFF
        }
    }

    @Test
    fun drainLetsASubsequentPollForTheCorrectPidSucceedAfterAStaleMismatch() {
        val stale = "41 0C 1A F8\r\r>" // leftover RPM response from an earlier timed-out cycle
        val correct = "41 0D 32\r\r>" // this cycle's real speed response
        val input = GatedInputStream(
            stale.toByteArray(Charsets.US_ASCII),
            correct.toByteArray(Charsets.US_ASCII),
        )
        val output = ByteArrayOutputStream()

        val first = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)
        assertNull(first.bytes)
        assertTrue(first.answered)

        // The correct response "arrives" only now - a real socket would not
        // have delivered it in time for the drain above to see it either.
        input.release()
        val second = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertEquals(listOf(0x32), second.bytes)
        assertTrue(second.answered)
    }

    @Test
    fun classifyMapsEachRealFailurePathToItsCategory() {
        assertEquals(
            Obd2Failure.PERMISSION_DENIED,
            classifyObd2Failure(SecurityException("need BLUETOOTH_CONNECT")),
        )
        assertEquals(
            Obd2Failure.ADAPTER_UNAVAILABLE,
            classifyObd2Failure(IllegalArgumentException("00:11 is not a valid Bluetooth address")),
        )
        assertEquals(
            Obd2Failure.ADAPTER_UNAVAILABLE,
            classifyObd2Failure(IOException("Bluetooth adapter unavailable")),
        )
        assertEquals(
            Obd2Failure.HANDSHAKE_TIMEOUT,
            classifyObd2Failure(IOException("Handshake timed out waiting for ATZ response")),
        )
        assertEquals(
            Obd2Failure.NO_DATA,
            classifyObd2Failure(IOException("Adapter unresponsive: 5 consecutive empty poll cycles")),
        )
        assertEquals(
            Obd2Failure.SOCKET_ERROR,
            classifyObd2Failure(IOException("read failed, socket might closed")),
        )
    }

    @Test
    fun pollPidParsesThroughAnEchoedRequestPrefix() {
        // Clone that ignored ATE0: the response leads with the echoed command
        // before the real "41 0D ..".
        val input = streamOf("010D\r41 0D 32\r\r>")
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertEquals(listOf(0x32), result.bytes)
        assertTrue(result.answered)
    }

    @Test
    fun pollPidParsesThroughACanHeaderPrefix() {
        // Adapter left in headers-on mode: "7E8 03 41 0D 32" — CAN address and
        // length bytes ahead of the 41 0D pair.
        val input = streamOf("7E8 03 41 0D 32\r\r>")
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertEquals(listOf(0x32), result.bytes)
    }

    @Test
    fun pollPidTreatsAnElmBusErrorAsUnanswered() {
        // "UNABLE TO CONNECT" means the adapter can't reach the ECU — unlike
        // "NO DATA" it never recovers, so it must count as an unanswered poll
        // and let the empty-poll watchdog fail the connection.
        val input = streamOf("UNABLE TO CONNECT\r\r>")
        val output = ByteArrayOutputStream()

        val result = Obd2Connection.pollPid(input, output, Obd2Pids.PID_SPEED)

        assertNull(result.bytes)
        assertFalse(result.answered)
    }

    // --- probePidCycle: probe-and-latch state machine (#103) ------------------

    private val PROBING = Obd2Connection.PidProbe.Probing()

    @Test
    fun probeLatchesToPrimaryOnADataFrame() {
        val input = streamOf("41 5E 00 40\r\r>") // a 015E fuel-rate frame
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(), PROBING,
            primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Latched(Obd2Pids.PID_FUEL_RATE), cycle.state)
        assertEquals(listOf(0x00, 0x40), cycle.result?.bytes)
    }

    @Test
    fun probeFallsBackToSecondaryWhenPrimaryAnswersUnsupported() {
        // Primary (015E) answers "NO DATA"; the fallback (0110) frame arrives
        // only after that poll returns, so the same cycle re-polls and latches it.
        val input = SequentialResponseStream(listOf("NO DATA\r\r>", "41 10 1A F0\r\r>"))
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(), PROBING,
            primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Latched(Obd2Pids.PID_MAF), cycle.state)
        assertEquals(listOf(0x1A, 0xF0), cycle.result?.bytes)
    }

    @Test
    fun probeGoesUnsupportedWhenBothPidsAnswerUnsupported() {
        val input = SequentialResponseStream(listOf("NO DATA\r\r>", "NO DATA\r\r>"))
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(), PROBING,
            primary = Obd2Pids.PID_THROTTLE_REL, fallback = Obd2Pids.PID_THROTTLE,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Unsupported, cycle.state)
        assertNull(cycle.result)
    }

    @Test
    fun probeGoesUnsupportedWhenPrimaryUnsupportedAndNoFallback() {
        // lambda (0144) has no alternative PID — fallback is null.
        val input = streamOf("NO DATA\r\r>")
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(), PROBING,
            primary = "0144", fallback = null,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Unsupported, cycle.state)
    }

    @Test
    fun probeKeepsProbingOnABareTimeoutWithinBudget() {
        val input = ByteArrayInputStream(ByteArray(0)) // primary times out
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(), Obd2Connection.PidProbe.Probing(cycles = 1),
            primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Probing(cycles = 2), cycle.state)
        assertNull(cycle.result)
    }

    @Test
    fun probeForcesResolutionWhenTheCycleBudgetIsSpent() {
        // Budget spent and primary still just times out: force the fallback, and
        // with the fallback also silent, give up rather than probe 015E forever.
        val input = ByteArrayInputStream(ByteArray(0))
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(),
            Obd2Connection.PidProbe.Probing(cycles = Obd2Connection.PID_PROBE_MAX_CYCLES - 1),
            primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Unsupported, cycle.state)
    }

    @Test
    fun probeInLatchedStateJustPollsTheLatchedPidEveryCycle() {
        val input = streamOf("41 10 1A F0\r\r>")
        val cycle = Obd2Connection.probePidCycle(
            input, ByteArrayOutputStream(),
            Obd2Connection.PidProbe.Latched(Obd2Pids.PID_MAF),
            primary = Obd2Pids.PID_FUEL_RATE, fallback = Obd2Pids.PID_MAF,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Latched(Obd2Pids.PID_MAF), cycle.state)
        assertEquals(listOf(0x1A, 0xF0), cycle.result?.bytes)
    }

    @Test
    fun probeDoesNotLatchThrottleUnsupportedOnATransientTimeout() {
        // Early cycle, primary (0145) just times out: the slot must keep probing,
        // not conclude the pedal PID is unsupported.
        val cycle = Obd2Connection.probePidCycle(
            ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(),
            Obd2Connection.PidProbe.Probing(cycles = 0),
            primary = Obd2Pids.PID_THROTTLE_REL, fallback = Obd2Pids.PID_THROTTLE,
            maxCycles = Obd2Connection.PID_PROBE_MAX_CYCLES,
        )
        assertEquals(Obd2Connection.PidProbe.Probing(cycles = 1), cycle.state)
    }
}
