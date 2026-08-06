package com.jellemax.detour.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.jellemax.detour.data.Settings

class DetourCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // The head unit can start this process with the phone UI never having
        // run, and the car screens both read (map zoom) and write (voice
        // guidance) settings from the first frame. init is idempotent.
        Settings.init(carContext)
        return SpinScreen(carContext)
    }
}
