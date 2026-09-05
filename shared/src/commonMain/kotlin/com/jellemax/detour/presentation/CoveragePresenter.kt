package com.jellemax.detour.presentation

import com.jellemax.detour.data.CELL_METERS
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.MunicipalityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the coverage screen's chrome state. The MapLibre layers are the
 * screen's own and are not modelled here — they belong to the screen, not
 * this presenter.
 *
 * [refresh] is declared `suspend` but never actually suspends: [Coverage.compute]
 * and [MunicipalityStore.load] are both non-suspending and block on disk.
 * commonMain has no dispatcher to hop off, so the off-main-thread guarantee rests
 * entirely on the CALLER wrapping this in `withContext(Dispatchers.IO)`. Called
 * on the main dispatcher, this janks the UI for a full Coverage walk.
 *
 * Coverage.compute() is called by several screens independently; that is
 * deliberate and free, because Coverage caches its own result (see its doc).
 */
class CoveragePresenter {
    private val _state = MutableStateFlow(CoverageState())
    val state: StateFlow<CoverageState> = _state

    suspend fun refresh(sep: Char = '.') {
        // Entry already carries `name`, so the store is needed only for the
        // denominator — how many municipalities the app knows a boundary for.
        val knownCount = MunicipalityStore.load().size
        val views = Coverage.compute().map { e ->
            CoverageEntryView(
                municipalityId = e.municipalityId,
                name = e.name,
                percentLabel = "${formatFixed(e.percent, 0)}%",
                areaLabel = "${formatFixed(areaKm2(e.totalCells, CELL_METERS), 1, sep)} km²",
                percent = e.percent,
            )
        }
        _state.value = coverageStateFrom(
            entries = views,
            knownMunicipalities = knownCount,
            selectedId = _state.value.selected?.municipalityId,
        )
    }

    /** Tapping a municipality on the map, or tapping away to clear it. */
    fun select(municipalityId: Long?) {
        _state.value = _state.value.copy(
            selected = municipalityId?.let { id ->
                _state.value.entries.firstOrNull { it.municipalityId == id }
            },
        )
    }
}
