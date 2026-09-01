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

    /** Absolute throttle position. On drive-by-wire cars this tracks the throttle
     *  *plate*, not the pedal — it idles well above 0 (a pinned ~85% is common)
     *  and barely moves with the accelerator. [PID_THROTTLE_REL] is the pedal
     *  signal; this is the fallback for vehicles that don't report 0145. */
    const val PID_THROTTLE = "0111"

    /** Relative throttle position — 0% at a closed pedal, rising with the
     *  accelerator, on the same `A * 100 / 255` scale as [PID_THROTTLE].
     *  Preferred over [PID_THROTTLE]; not universal on pre-2008 vehicles. */
    const val PID_THROTTLE_REL = "0145"
    const val PID_RPM = "010C"

    /** One byte, km/h direct. 0 is a valid reading (stopped), not absence —
     *  absence is an empty [dataBytes], not any particular byte value. */
    fun parseSpeedKmh(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.toDouble()

    /** One byte, `A * 100 / 255` — the byte's full 0..255 range maps onto 0..100%.
     *  Same decode for [PID_THROTTLE] and [PID_THROTTLE_REL]. */
    fun parseThrottlePct(dataBytes: List<Int>): Double? =
        dataBytes.getOrNull(0)?.let { it * 100.0 / 255.0 }

    /** Two bytes, `(256*A + B) / 4` — quarter-RPM resolution. */
    fun parseRpm(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (256.0 * a + b) / 4.0
    }
}
