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

    /** Engine fuel rate — a direct ECU reading in L/h. Not universal: many
     *  pre-2016 vehicles report nothing for it, which is why [PID_MAF] is the
     *  fallback. */
    const val PID_FUEL_RATE = "015E"

    /** Mass air flow — grams of intake air per second. Near-universal on OBD-II
     *  petrol vehicles; [fuelRateFromMafLph] turns it into a fuel rate under the
     *  stoichiometric assumption. */
    const val PID_MAF = "0110"

    /** Stoichiometric air-fuel mass ratio for petrol, and petrol density. Used
     *  only by [fuelRateFromMafLph] — the MAF path is an estimate; a diesel or a
     *  car running rich/lean will read off. */
    private const val STOICH_AFR_PETROL = 14.7
    private const val FUEL_DENSITY_G_PER_L = 745.0

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

    /** Two bytes, `(256*A + B) / 20` — litres per hour. */
    fun parseFuelRateLph(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (256.0 * a + b) / 20.0
    }

    /** Two bytes, `(256*A + B) / 100` — grams of air per second. */
    fun parseMafGramsPerSec(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (256.0 * a + b) / 100.0
    }

    /** Fuel rate in L/h implied by an intake air-mass flow, assuming the engine
     *  burns petrol at the stoichiometric ratio. `mass air / AFR` is the fuel
     *  mass rate; dividing by density and scaling to the hour gives volume. */
    fun fuelRateFromMafLph(mafGramsPerSec: Double): Double =
        mafGramsPerSec / STOICH_AFR_PETROL / FUEL_DENSITY_G_PER_L * 3600.0
}
