package com.jellemax.detour.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.ui.graphics.vector.ImageVector
import com.jellemax.detour.data.TravelMode

val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.MOTO -> Icons.Outlined.TwoWheeler
        TravelMode.CAR -> Icons.Outlined.DirectionsCar
    }
