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

    /** Commanded air-fuel equivalence ratio (lambda) — 1.0 at stoichiometric,
     *  >1 lean. A petrol engine in closed loop commands ~1.0; a diesel at
     *  cruise commands 2.0-2.5, and PID 0144 saturates at ≈2.0. Used only by
     *  the MAF fuel estimate to divide out the lean-burn air the stoichiometric
     *  assumption would otherwise count as fuel. */
    const val PID_EQUIV_RATIO = "0144"

    /** Stoichiometric air-fuel mass ratio and fuel density. Petrol vs diesel —
     *  [fuelRateFromMafLph] picks by [FuelType]. The MAF path is still an
     *  estimate; the commanded-lambda term is what makes a lean diesel land
     *  near its dash figure. */
    private const val STOICH_AFR_PETROL = 14.7
    private const val FUEL_DENSITY_PETROL_G_PER_L = 745.0
    private const val STOICH_AFR_DIESEL = 14.5
    private const val FUEL_DENSITY_DIESEL_G_PER_L = 832.0

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

    /** Two bytes, `(2 / 65536) * (256*A + B)` — dimensionless lambda. */
    fun parseCommandedEquivRatio(dataBytes: List<Int>): Double? {
        val a = dataBytes.getOrNull(0) ?: return null
        val b = dataBytes.getOrNull(1) ?: return null
        return (2.0 / 65536.0) * (256.0 * a + b)
    }

    /** Fuel rate in L/h implied by intake air-mass flow.
     *
     *  `mass air / (AFR_stoich · λ)` is the fuel mass rate — dividing the air
     *  by the *actual* air-fuel ratio (stoichiometric scaled by the commanded
     *  equivalence ratio) rather than the stoichiometric one is what keeps a
     *  lean diesel from reading its excess air as fuel. Then / density / to
     *  the hour, and a per-vehicle [calibrationPct] trims what the model can't
     *  see (injector wear, MAF drift, fuel blend, the residual past PID 0144's
     *  ≈2.0 ceiling). Petrol at λ=1.0, calibration 100 is the old formula
     *  exactly. */
    fun fuelRateFromMafLph(
        mafGramsPerSec: Double,
        fuelType: FuelType = FuelType.PETROL,
        lambda: Double = 1.0,
        calibrationPct: Int = 100,
    ): Double {
        val afr = if (fuelType == FuelType.DIESEL) STOICH_AFR_DIESEL else STOICH_AFR_PETROL
        val density = if (fuelType == FuelType.DIESEL) FUEL_DENSITY_DIESEL_G_PER_L else FUEL_DENSITY_PETROL_G_PER_L
        return mafGramsPerSec / (afr * lambda) / density * 3600.0 * (calibrationPct / 100.0)
    }

    /** RPM above idle that, together with a closed throttle while still rolling,
     *  means the ECU has cut injection. Only used for the MAF estimate — the
     *  direct PID reports its own ~0 in fuel cut. */
    private const val DFCO_MIN_RPM = 1200.0

    /** One cycle's fuel rate and whether it's a MAF-derived estimate. */
    data class FuelReading(val lph: Double, val estimated: Boolean)

    /**
     * This cycle's fuel rate in L/h: the direct PID if it answered, else the MAF
     * estimate, else null.
     *
     * [throttleClosed] is null when the caller only has the absolute-throttle PID
     * (0111), which idles well above 0 and can't tell a closed pedal from an
     * open one — the deceleration-fuel-cut zero is then skipped and the estimate
     * over-reads on a long downhill. It fires only when [throttleClosed] is
     * explicitly true (a 0145 reading near 0) alongside an above-idle RPM and a
     * non-zero speed.
     *
     * [fuelType], [lambda] and [calibrationPct] only steer the MAF estimate —
     * the direct PID already accounts for all of it. [lambda] defaults to 1.0
     * when the adapter doesn't report PID 0144; [calibrationPct] is the
     * per-vehicle trim (100 = untouched).
     */
    fun resolveFuelRate(
        directLph: Double?,
        mafGramsPerSec: Double?,
        throttleClosed: Boolean?,
        rpm: Double?,
        speedKmh: Double?,
        fuelType: FuelType = FuelType.PETROL,
        lambda: Double = 1.0,
        calibrationPct: Int = 100,
    ): FuelReading? {
        if (directLph != null) return FuelReading(directLph, estimated = false)
        if (mafGramsPerSec == null) return null
        val fuelCut = throttleClosed == true &&
            rpm != null && rpm > DFCO_MIN_RPM &&
            speedKmh != null && speedKmh > 0.0
        val lph = if (fuelCut) 0.0
            else fuelRateFromMafLph(mafGramsPerSec, fuelType, lambda, calibrationPct)
        return FuelReading(lph, estimated = true)
    }
}
