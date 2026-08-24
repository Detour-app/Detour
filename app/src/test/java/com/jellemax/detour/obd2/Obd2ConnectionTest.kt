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
}
