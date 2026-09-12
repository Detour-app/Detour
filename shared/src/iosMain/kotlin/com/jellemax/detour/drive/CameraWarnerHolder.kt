package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SpeedCameras

/**
 * The per-surface holder [CameraWarner]'s KDoc asks each consumer to bring,
 * written once here for iOS — same reasoning as [SectionAverageHolder].
 *
 * [CameraWarner.State] has a defaulted constructor parameter, which
 * Kotlin/Native does not export, so Swift cannot write `CameraWarner.State()`
 * itself; and [CameraWarner.Outcome] is a sealed interface, which crosses to
 * Swift as a type it cannot `switch` over cleanly. Holding the state and
 * flattening the outcome to a nullable [CameraWarning] here keeps both off
 * the Swift side of the boundary.
 *
 * Returned rather than published through a flow: unlike [SectionAverageHolder]'s
 * reading, a warning is not something a view binds to and redraws for — it is
 * a one-shot instruction for whoever called [onFix] to speak, consumed once
 * and then forgotten.
 */
class CameraWarnerHolder {

    private var state = CameraWarner.State()

    /** One GPS fix; see [CameraWarner.onFix] for the parameters' meaning. */
    fun onFix(
        cameras: List<SpeedCameras.Camera>,
        at: LatLon,
        headingDeg: Double?,
        speedKmh: Double,
        limitKmh: Double?,
    ): CameraWarning? {
        val step = CameraWarner.onFix(state, cameras, at, headingDeg, speedKmh, limitKmh)
        state = step.state
        val outcome = step.outcome
        return if (outcome is CameraWarner.Outcome.Warn) CameraWarning(outcome.at, outcome.text) else null
    }
}

/** [CameraWarner.Outcome.Warn], flattened to a plain data class Swift can read
 *  without a sealed-interface cast. */
data class CameraWarning(val at: LatLon, val text: String)
