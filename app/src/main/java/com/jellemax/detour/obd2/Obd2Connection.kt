package com.jellemax.detour.obd2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.jellemax.detour.drive.Obd2Pids
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A single OBD2 reading. `receivedAtMs` is stamped on arrival, not carried
 *  from the adapter — same convention as `BoardTelemetry.receivedAtMs`, so a
 *  disconnected/stalled adapter reads as stale on the consumer side rather
 *  than freezing on its last number. Each `hasX` is independent: one PID's
 *  response failing this poll cycle doesn't blank the other two. */
data class ObdTelemetry(
    val hasSpeed: Boolean, val speedKmh: Double,
    val hasThrottle: Boolean, val throttlePct: Double,
    val hasRpm: Boolean, val rpmValue: Double,
    val receivedAtMs: Long,
)

enum class Obd2ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/** Why the last connection attempt failed, for the pairing screen's diagnostics
 *  line. [NONE] once a connection succeeds or after a clean [Obd2Connection.disconnect]. */
enum class Obd2Failure { NONE, ADAPTER_UNAVAILABLE, PERMISSION_DENIED, HANDSHAKE_TIMEOUT, NO_DATA, SOCKET_ERROR }

/** Maps the exception the connection loop caught to a [Obd2Failure] category.
 *  Keyed off the message strings the loop actually throws (see [Obd2Connection.handshake]
 *  and the poll watchdog) plus the two non-IOException types the catch was
 *  broadened for. */
internal fun classifyObd2Failure(e: Throwable): Obd2Failure = when {
    e is SecurityException -> Obd2Failure.PERMISSION_DENIED
    e is IllegalArgumentException -> Obd2Failure.ADAPTER_UNAVAILABLE
    e.message?.contains("adapter unavailable", ignoreCase = true) == true -> Obd2Failure.ADAPTER_UNAVAILABLE
    e.message?.contains("handshake", ignoreCase = true) == true -> Obd2Failure.HANDSHAKE_TIMEOUT
    e.message?.contains("unresponsive", ignoreCase = true) == true -> Obd2Failure.NO_DATA
    else -> Obd2Failure.SOCKET_ERROR
}

/**
 * Bluetooth Classic (SPP) connection to a paired ELM327-compatible adapter
 * for maxke24/Detour#62. A process-wide singleton, matching `BleNavServer`'s
 * shape — [connect]/[disconnect] are called from `TripTrackingService`'s
 * Bluetooth-vehicle-detect receiver (Task 4), independent of whether a trip
 * is active, so the pairing screen (Task 6) can also show a live reading
 * without a trip running.
 */
@SuppressLint("MissingPermission") // caller (TripTrackingService) already gates on hasBtPermission()
object Obd2Connection {
    private const val TAG = "Obd2Connection"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val HANDSHAKE_TIMEOUT_MS = 2_000L
    // ATZ triggers a full ELM327 reset; cheap clones commonly take 3-5s to
    // complete it and emit their banner, well past HANDSHAKE_TIMEOUT_MS.
    private const val RESET_TIMEOUT_MS = 5_000L
    private const val POLL_TIMEOUT_MS = 1_000L
    private const val POLL_INTERVAL_MS = 1_000L
    private const val BASE_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 60_000L
    private const val MAX_DOUBLINGS = 5
    // Consecutive poll cycles where all three PIDs went entirely unanswered
    // (see PollResult.answered) before we give up on the connection and fall
    // through to the failure/backoff path.
    private const val MAX_CONSECUTIVE_EMPTY_POLLS = 5
    // Bounds on draining a desynced adapter's already-buffered stale responses
    // back to a clean stream — see [drainStalePrompts].
    private const val DRAIN_TIMEOUT_MS = 200L
    private const val MAX_DRAIN_ITERATIONS = 3
    // Above any real posted speed limit or plausible driving speed. PID 0D is
    // one byte (0..255), so this can't catch every garbled single-byte value —
    // 0xFF (255) is a legal-looking reading and passes through — but it does
    // catch a run-together/garbled multi-token response parsing to something
    // physically impossible. Caps what can become the recorded topSpeedMps the
    // same way MAX_PLAUSIBLE_G/MAX_PLAUSIBLE_LEAN_DEG cap those maxes.
    private const val MAX_PLAUSIBLE_SPEED_KMH = 300.0

    private val _telemetry = MutableStateFlow<ObdTelemetry?>(null)
    val telemetry: StateFlow<ObdTelemetry?> = _telemetry

    private val _connectionState = MutableStateFlow(Obd2ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<Obd2ConnectionState> = _connectionState

    private val _lastFailure = MutableStateFlow(Obd2Failure.NONE)
    val lastFailure: StateFlow<Obd2Failure> = _lastFailure

    /** When the adapter last answered at least one PID. Unlike [telemetry] this
     *  is NOT cleared on a drop — "last data 14s ago" is exactly what the
     *  diagnostics line and the HUD's signal-lost check want to show. */
    private val _lastDataAtMs = MutableStateFlow<Long?>(null)
    val lastDataAtMs: StateFlow<Long?> = _lastDataAtMs

    /** The address the running loop is connecting to, or null when idle. Lets
     *  the pairing screen's "Retry now" target the right adapter and attribute
     *  the shown status. Does NOT fix the singleton's two-adapters-in-range
     *  ambiguity — it only exposes which one won. */
    private val _linkedAddress = MutableStateFlow<String?>(null)
    val linkedAddress: StateFlow<String?> = _linkedAddress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /** The socket currently owned by the running connection loop, if any.
     *  [disconnect] closes it directly rather than only cancelling [job]:
     *  `BluetoothSocket.connect()`/`InputStream.read()`/`OutputStream.write()`
     *  are blocking calls, not suspension points, so cooperative cancellation
     *  alone can leave the loop stuck on the adapter for its full timeout (or,
     *  for a bare `connect()`, the OS's own multi-second SPP timeout) before
     *  it ever notices. Closing the socket from the caller's thread is the
     *  standard way to unblock a pending `BluetoothSocket` call immediately. */
    @Volatile private var activeSocket: BluetoothSocket? = null

    /** Same doubling-backoff shape as `com.jellemax.detour.drive.backoffDelayMs`
     *  (that function is `internal` to `shared`'s `drive` package, not visible
     *  here) — a persistently unresponsive or incompatible clone must not
     *  retry in a tight loop. */
    private fun retryDelayMs(failures: Int): Long {
        if (failures <= 0) return BASE_RETRY_MS
        val doubled = BASE_RETRY_MS shl minOf(failures, MAX_DOUBLINGS)
        return minOf(doubled, MAX_RETRY_MS)
    }

    // Synchronized so two concurrent connect() calls (or a connect() racing a
    // disconnect()) can't both pass the isActive check and each launch their
    // own loop — `job` only ever holds the most recent one, so a second loop
    // launched underneath it would never get cancelled by disconnect() and
    // would leak its socket and coroutine.
    @Synchronized
    fun connect(context: Context, address: String) {
        if (job?.isActive == true) return
        _linkedAddress.value = address
        job = scope.launch { runConnectionLoop(context, address) }
    }

    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = null
        _connectionState.value = Obd2ConnectionState.DISCONNECTED
        _telemetry.value = null
        _linkedAddress.value = null
        // A deliberate stop isn't a failure to report.
        _lastFailure.value = Obd2Failure.NONE
    }

    private suspend fun runConnectionLoop(context: Context, address: String) {
        var failures = 0
        while (coroutineContext.isActive) {
            _connectionState.value = Obd2ConnectionState.CONNECTING
            var socket: BluetoothSocket? = null
            try {
                val device = context.getSystemService(BluetoothManager::class.java)
                    ?.adapter?.getRemoteDevice(address)
                    ?: throw IOException("Bluetooth adapter unavailable")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                activeSocket = socket
                socket.connect()
                val input = socket.inputStream
                val output = socket.outputStream
                handshake(input, output)
                // disconnect() may have force-closed the socket (and cancelled this
                // job) while handshake() was blocked in its own blocking I/O; don't
                // publish CONNECTED (or start polling) over a disconnect that already
                // published DISCONNECTED — see [activeSocket] and [disconnect].
                if (!coroutineContext.isActive) return
                _connectionState.value = Obd2ConnectionState.CONNECTED
                _lastFailure.value = Obd2Failure.NONE
                failures = 0
                pollLoop(input, output)
            } catch (e: CancellationException) {
                // Let cancellation propagate — it's not a connection failure, and
                // swallowing it here would keep this SupervisorJob-launched loop
                // alive (and retrying) past the point disconnect()/scope teardown
                // meant to stop it.
                throw e
            } catch (e: Exception) {
                // Broadened beyond IOException: getRemoteDevice() throws
                // IllegalArgumentException on a malformed (e.g. lowercase) MAC
                // address, and a Bluetooth permission revoked mid-connection
                // throws SecurityException from createRfcommSocketToServiceRecord
                // or socket.connect(). Neither is an IOException, and since this
                // coroutine runs under a SupervisorJob, leaving either uncaught
                // would reach the thread's default handler and could crash the
                // process instead of falling through to the backoff/retry path.
                failures++
                Log.w(TAG, "OBD2 connection attempt failed", e)
                // Same race as above: a disconnect()-triggered close() surfaces here
                // as an IOException. If we've already been cancelled, disconnect()
                // owns the terminal state — don't clobber DISCONNECTED with FAILED.
                if (coroutineContext.isActive) {
                    _connectionState.value = Obd2ConnectionState.FAILED
                    _lastFailure.value = classifyObd2Failure(e)
                    _telemetry.value = null
                }
            } finally {
                runCatching { socket?.close() }
                if (activeSocket === socket) activeSocket = null
            }
            if (!coroutineContext.isActive) return
            delay(retryDelayMs(failures))
        }
    }

    /** Runs the ATZ/ATE0/ATSP0 handshake. [readUntilPrompt] signals a timeout
     *  by returning null rather than throwing, so each call's result must be
     *  checked explicitly here — a silently-discarded null would otherwise
     *  let a non-responding adapter reach CONNECTED and poll forever. */
    internal fun handshake(input: InputStream, output: OutputStream) {
        sendCommand(output, "ATZ")
        readUntilPrompt(input, RESET_TIMEOUT_MS)
            ?: throw IOException("Handshake timed out waiting for ATZ response")
        sendCommand(output, "ATE0")
        readUntilPrompt(input, HANDSHAKE_TIMEOUT_MS)
            ?: throw IOException("Handshake timed out waiting for ATE0 response")
        sendCommand(output, "ATSP0")
        readUntilPrompt(input, HANDSHAKE_TIMEOUT_MS)
            ?: throw IOException("Handshake timed out waiting for ATSP0 response")
    }

    /** Result of one PID poll: [bytes] is the parsed data bytes (null if the
     *  response wasn't a valid frame for the requested PID — a header mismatch,
     *  garbage, or a real-but-non-frame answer like "NO DATA"). [answered] is
     *  true whenever the adapter produced ANY `>`-terminated response at all —
     *  even an unparseable one — as opposed to a genuine read timeout. Only a
     *  cycle where every PID's [answered] is false means the adapter has gone
     *  silent; a cycle full of "NO DATA" answers means it's alive and correctly
     *  reporting unsupported PIDs. */
    internal data class PollResult(val bytes: List<Int>?, val answered: Boolean)

    private suspend fun pollLoop(input: InputStream, output: OutputStream) {
        // Counts consecutive cycles where all three PIDs went entirely unanswered
        // (genuine timeouts, not "NO DATA" or other unparseable-but-real answers).
        // Catches an adapter that's gone silent (out of range, powered off) —
        // NOT a permanently-desynced-but-still-answering one, which always
        // reports answered=true and so never trips this; drainStalePrompts is
        // the primary recovery for that case instead. A vehicle that genuinely
        // answers "NO DATA" to every PID (e.g. parked, ignition off) must NOT
        // trip this either — the adapter is proven alive that cycle.
        var consecutiveEmptyPolls = 0
        while (coroutineContext.isActive) {
            val speedResult = pollPid(input, output, Obd2Pids.PID_SPEED)
            val throttleResult = pollPid(input, output, Obd2Pids.PID_THROTTLE)
            val rpmResult = pollPid(input, output, Obd2Pids.PID_RPM)
            val speed = speedResult.bytes?.let { Obd2Pids.parseSpeedKmh(it) }
                // A single garbled byte can decode to a plausible-looking but
                // impossible value (e.g. 0xFF -> 255 km/h); reject it the same
                // as any other unparseable reading rather than let it become
                // the trip's recorded topSpeedMps.
                ?.takeIf { it <= MAX_PLAUSIBLE_SPEED_KMH }
            val throttle = throttleResult.bytes?.let { Obd2Pids.parseThrottlePct(it) }
            val rpm = rpmResult.bytes?.let { Obd2Pids.parseRpm(it) }
            if (!speedResult.answered && !throttleResult.answered && !rpmResult.answered) {
                consecutiveEmptyPolls++
                if (consecutiveEmptyPolls >= MAX_CONSECUTIVE_EMPTY_POLLS) {
                    throw IOException(
                        "Adapter unresponsive: $MAX_CONSECUTIVE_EMPTY_POLLS consecutive empty poll cycles"
                    )
                }
            } else {
                consecutiveEmptyPolls = 0
                _lastDataAtMs.value = System.currentTimeMillis()
            }
            _telemetry.value = ObdTelemetry(
                hasSpeed = speed != null, speedKmh = speed ?: 0.0,
                hasThrottle = throttle != null, throttlePct = throttle ?: 0.0,
                hasRpm = rpm != null, rpmValue = rpm ?: 0.0,
                receivedAtMs = System.currentTimeMillis(),
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Sends [pid] and reads the response. [PollResult.bytes] is the data bytes
     *  with the `41 <pid>` echo header stripped — null on a malformed response
     *  or a header that doesn't match the PID just requested (a desynced clone
     *  answering the previous command late, or a real "NO DATA" answer).
     *  [PollResult.answered] is true whenever the adapter produced any
     *  `>`-terminated response at all, regardless of whether it parsed. */
    internal fun pollPid(input: InputStream, output: OutputStream, pid: String): PollResult {
        sendCommand(output, pid)
        val raw = readUntilPrompt(input, POLL_TIMEOUT_MS) ?: return PollResult(bytes = null, answered = false)
        val tokens = raw.trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull(16) }
        val modeByte = ("4" + pid[1]).toIntOrNull(16) // request "010D" -> response mode byte 0x41
        val pidByte = pid.substring(2).toIntOrNull(16)
        if (tokens.size < 2 || tokens[0] != modeByte || tokens[1] != pidByte) {
            // This chunk didn't answer the PID we just asked for — almost certainly
            // a previous cycle's late response that was still sitting in the
            // socket's read buffer after a prior timeout, with our actual answer
            // queued behind it, or a real "NO DATA" answer. Drain any further
            // already-buffered chunks now so the next request starts from a clean
            // stream instead of perpetually reading one cycle behind (which would
            // reject every response forever).
            drainStalePrompts(input)
            return PollResult(bytes = null, answered = true)
        }
        return PollResult(bytes = tokens.drop(2), answered = true)
    }

    /** Discards any additional `>`-terminated chunks already sitting in the
     *  socket's read buffer, bounded so a persistently chatty or garbled
     *  adapter can't stall a poll cycle indefinitely. See [pollPid]. */
    private fun drainStalePrompts(input: InputStream) {
        repeat(MAX_DRAIN_ITERATIONS) {
            if (input.available() <= 0) return
            if (readUntilPrompt(input, DRAIN_TIMEOUT_MS) == null) return
        }
    }

    private fun sendCommand(output: OutputStream, command: String) {
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Reads bytes until the `>` prompt ELM327 terminates every response
     *  with, or [timeoutMs] elapses — never a newline, which some firmwares
     *  omit. Null on timeout with nothing usable read. */
    internal fun readUntilPrompt(input: InputStream, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == '>') return buffer.toString()
                buffer.append(c)
            } else {
                Thread.sleep(20)
            }
        }
        return buffer.toString().takeIf { it.isNotBlank() }
    }
}
