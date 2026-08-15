package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SectionReadingWatcher
import com.jellemax.detour.data.SpeedCameras
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The per-surface holder [SectionAverageTracker]'s KDoc says each consumer has
 * to bring — *"whichever per-surface holder wants one wraps this"* — written
 * once here for iOS.
 *
 * It is Kotlin rather than Swift for a concrete reason and not a stylistic one:
 * [SectionAverageTracker.State] has six constructor parameters, every one of
 * them defaulted, and Kotlin/Native does not export default arguments. Swift
 * cannot write `SectionAverageTracker.State()`. Holding the state on this side
 * of the boundary is what keeps the Swift call site down to handing in a fix.
 *
 * **No scope, and no clock.** [readings] hands out a watcher, which owns the one
 * coroutine involved; [onFix] sets the flow's value on whichever thread called
 * it — SwiftUI's main actor — and takes `nowMs` from the caller, same as the
 * tracker does and for the same reason.
 *
 * Sections are handed in too. `SpeedCameras.near` is a suspend function that
 * Swift can call directly as `async throws`, and the prefetch policy (how near
 * the edge of the fetched area is near enough, how often to retry) is the
 * platform's, exactly as it is on the phone and the head unit.
 */
class SectionAverageHolder {

    private val flow = MutableStateFlow(SectionAverageTracker.Reading(null, null))

    private var state = SectionAverageTracker.State()

    /**
     * A watcher over the readings, for a SwiftUI model to bind to.
     *
     * Handed out rather than exposed as a property because [SectionReadingWatcher]
     * holds a subscription that its owner has to `cancel()`; a caller that
     * received it from a function is likelier to keep it in a stored property,
     * which is the only shape that can be cancelled in a `deinit`.
     */
    fun readings() = SectionReadingWatcher(flow)

    /**
     * One GPS fix, with whatever sections the caller's last completed prefetch
     * returned. [headingDeg] is null when the platform has no usable bearing —
     * CoreLocation reports a negative course for exactly that.
     */
    fun onFix(
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ) {
        state = SectionAverageTracker.onFix(
            state = state,
            sections = sections,
            at = at,
            headingDeg = headingDeg,
            speedMps = speedMps,
            nowMs = nowMs,
        )
        flow.value = state.reading
    }
}
