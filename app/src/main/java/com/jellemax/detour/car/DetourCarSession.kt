package com.jellemax.detour.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jellemax.detour.data.Settings

class DetourCarSession : Session() {

    /**
     * One map for the whole session, not one per screen.
     *
     * The head unit hands out a single surface and keeps handing it to whatever
     * the app has registered, whether or not a route is running — including the
     * narrow panel it draws next to a media app in split screen. A renderer that
     * only existed while [NavScreen] was on top left both the home screen and
     * that panel black, since nothing was registered to draw into the surface.
     */
    private var renderer: CarMapRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        // The head unit can start this process with the phone UI never having
        // run, and the car screens both read (map zoom) and write (voice
        // guidance) settings from the first frame. init is idempotent.
        Settings.init()
        val map = CarMapRenderer(carContext, carContext.isDarkMode())
        renderer = map
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(map)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                map.destroy()
                renderer = null
            }
        })
        return SpinScreen(carContext, map)
    }
}
