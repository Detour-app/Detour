package com.jellemax.detour.update

import com.jellemax.detour.data.UpdateClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app currently knows about a newer release.
 *
 * An object rather than screen state on purpose. Since #82 the Hub keeps its
 * saved state but its composition is still disposed when the rider goes to the
 * map, and a download held in a `remember` would restart from zero because
 * somebody glanced at the map. Same reasoning, and the same shape, as
 * SpinResultHolder.
 */
sealed interface UpdateStatus {
    /** Nothing known, or nothing newer. */
    data object None : UpdateStatus
    data class Available(val update: UpdateClient.PendingUpdate) : UpdateStatus
    data class Downloading(val update: UpdateClient.PendingUpdate, val fraction: Float) : UpdateStatus
    data class Downloaded(val update: UpdateClient.PendingUpdate, val path: String) : UpdateStatus
    /** The download failed or the file did not verify. The banner offers a retry. */
    data class Failed(val update: UpdateClient.PendingUpdate) : UpdateStatus
}

object UpdateState {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.None)
    val status: StateFlow<UpdateStatus> = _status

    fun set(status: UpdateStatus) {
        _status.value = status
    }

    /** The update currently on offer, whatever phase it is in. */
    fun current(): UpdateClient.PendingUpdate? = when (val s = _status.value) {
        is UpdateStatus.Available -> s.update
        is UpdateStatus.Downloading -> s.update
        is UpdateStatus.Downloaded -> s.update
        is UpdateStatus.Failed -> s.update
        UpdateStatus.None -> null
    }
}
