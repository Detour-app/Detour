package com.jellemax.detour.map

import com.jellemax.detour.data.LatLonBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the phone map last showed, so the map's download action can keep that area
 * offline (#510) without holding the map itself. Written by the retained map's
 * camera-idle listener; null until the map has drawn once.
 */
object MapViewport {
    private val _box = MutableStateFlow<LatLonBox?>(null)
    val box: StateFlow<LatLonBox?> = _box.asStateFlow()

    fun update(box: LatLonBox) {
        _box.value = box
    }
}
