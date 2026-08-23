package com.jellemax.detour.drive

/**
 * SAE J1979 mode-01 PID byte decoding for maxke24/Detour#62 — pure math, no
 * I/O, no ELM327 text-protocol handling (that's [Obd2Connection]'s job on the
 * app side; this only sees the decoded data bytes of an already-parsed
 * response). Every function returns null on a short/empty [dataBytes] rather
 * than throwing — a malformed or truncated adapter response is normal
 * (cheap-clone firmware quality varies), not exceptional.
 */
object Obd2Pids {
    /** Mode 01, PID 0D — vehicle speed. Request string sent verbatim to the
     *  adapter (`ATE0`'d, so no echo to strip from the request itself). */
    const val PID_SPEED = "010D"
    const val PID_THROTTLE = "0111"
    const val PID_RPM = "010C"

    /** One byte, km/h direct. 0 is a valid reading (stopped), not absence —
     *  absence is an empty [dataBytes], not any particular byte value. */
    fun parseSpeedKmh(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.toDouble()

    /** One byte, `A * 100 / 255` — the byte's full 0..255 range maps onto 0..100%. */
    fun parseThrottlePct(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Two bytes, `(256*A + B) / 4` — quarter-RPM resolution. */
    fun parseRpm(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (256.0 * a + b) / 4.0
    }
}
