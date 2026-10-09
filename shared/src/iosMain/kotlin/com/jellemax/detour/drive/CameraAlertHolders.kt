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
 * and then forgotten. The [countdown] is the part a view does draw (#550); it
 * is read back after each [onFix], so the caller still owns when it publishes.
 */
class CameraWarnerHolder {

    private var state = CameraWarner.State()

    /** [CameraWarner.Step.countdown] from the last [onFix], flattened: null
     *  until a camera is warned for and again once it is passed or out of reach. */
    var countdown: CameraCountdown? = null
        private set

    /** One GPS fix; see [CameraWarner.onFix] for the parameters' meaning. */
    fun onFix(
        cameras: List<SpeedCameras.Camera>,
        at: LatLon,
        headingDeg: Double?,
        speedKmh: Double,
        limitKmh: Double?,
    ): CameraWarning? {
        val step = CameraWarner.onFix(
            state.copy(options = cameraWarnerOptions()), cameras, at, headingDeg, speedKmh, limitKmh,
        )
        state = step.state
        countdown = step.countdown?.let { CameraCountdown(it.at, it.text, it.distanceMeters) }
        val outcome = step.outcome
        return if (outcome is CameraWarner.Outcome.Warn) CameraWarning(outcome.at, outcome.text) else null
    }
}

/** [CameraWarner.Outcome.Warn], flattened to a plain data class Swift can read
 *  without a sealed-interface cast. */
data class CameraWarning(val at: LatLon, val text: String)

/** [CameraWarner.Countdown], flattened for Swift alongside [CameraWarning]:
 *  the warned camera, its wording, and the straight-line distance left to it. */
data class CameraCountdown(val at: LatLon, val text: String, val distanceMeters: Double)

/**
 * The per-surface holder [CameraPrefetch]'s own KDoc asks each consumer to
 * bring, written once here for iOS for the same reason as [CameraWarnerHolder]:
 * [CameraPrefetch.State]'s one constructor parameter is defaulted, and
 * Kotlin/Native does not export a defaulted constructor at all — `'init()' is
 * unavailable` is what Xcode says for `CameraPrefetch.State()` written
 * directly in Swift. Holding the state here, behind three plain functions,
 * is what lets Swift follow the ordering [CameraPrefetch]'s own KDoc
 * prescribes without ever constructing a [CameraPrefetch.State] itself.
 */
class CameraPrefetchHolder {

    private var state = CameraPrefetch.State()

    fun needsFetch(at: LatLon, nowMs: Long): Boolean = CameraPrefetch.needsFetch(state, at, nowMs)

    /** Stamp the attempt. Call before awaiting the fetch, so a failure is
     *  throttled too — see [CameraPrefetch.fetchStarted]. */
    fun fetchStarted(nowMs: Long) {
        state = CameraPrefetch.fetchStarted(state, nowMs)
    }

    /** Fold a completed attempt in. See [CameraPrefetch.fetched]. */
    fun fetched(result: SpeedCameras.Result?, center: LatLon) {
        state = CameraPrefetch.fetched(state, result, center)
    }
}
