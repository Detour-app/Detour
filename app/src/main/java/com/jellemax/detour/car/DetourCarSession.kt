package com.jellemax.detour.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class DetourCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SpinScreen(carContext)
}
