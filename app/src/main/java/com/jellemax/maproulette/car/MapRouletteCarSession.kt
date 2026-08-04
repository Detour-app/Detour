package com.jellemax.maproulette.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class MapRouletteCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SpinScreen(carContext)
}
