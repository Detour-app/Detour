package com.jellemax.detour.presentation

import com.jellemax.detour.data.Watcher
import kotlinx.coroutines.flow.StateFlow

/**
 * The HERE key section's [TrafficKeyPresenter] for iOS Settings (#654).
 *
 * A holder rather than the presenter itself for
 * [com.jellemax.detour.drive.CameraPrefetchHolder]'s reason: the presenter's
 * constructor parameters are all defaulted (they are commonTest's seams), and
 * Kotlin/Native exports no defaulted constructor, so Swift could not write
 * `TrafficKeyPresenter()`. Its status is a `StateFlow<TrafficKeyStatus>`,
 * which Swift reads through [status].
 *
 * Lives as long as the Settings screen, unlike the nav session's
 * [TrafficHolder].
 */
class TrafficKeyHolder {

    private val presenter = TrafficKeyPresenter()

    /** The line under the field. Cancel it when the screen goes away. */
    fun status() = TrafficKeyStatusWatcher(presenter.status)

    /** See [TrafficKeyPresenter.test]. */
    suspend fun test(key: String) = presenter.test(key)

    /** See [TrafficKeyPresenter.edited]. */
    fun edited() = presenter.edited()

    /** See [TrafficKeyPresenter.remove]. */
    fun remove() = presenter.remove()
}

/** The HERE key section's status line (#654); see [TrafficKeyHolder]. */
class TrafficKeyStatusWatcher internal constructor(
    private val flow: StateFlow<TrafficKeyStatus>,
) : Watcher() {
    var value: TrafficKeyStatus = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}
