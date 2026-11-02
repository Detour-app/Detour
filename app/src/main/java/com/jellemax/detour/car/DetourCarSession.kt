package com.jellemax.detour.car

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jellemax.detour.convoy.ConvoyGapAlerts
import com.jellemax.detour.data.Settings
import com.jellemax.detour.tracking.DriveClocks
import kotlinx.coroutines.launch

private const val TAG = "DetourCarSession"

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
        val map = CarMapRenderer(carContext, carContext.isDarkMode(), DriveClocks.current)
        renderer = map
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(map)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                map.destroy()
                renderer = null
            }
        })
        toastConvoyGapAlerts()
        CarBreakRegroup.announce(this, carContext)
        return SpinScreen(carContext, map)
    }

    /**
     * Shows each new convoy gap alert (#655) as a toast, on whichever car
     * screen is up — convoys often ride with no destination, so this belongs
     * to the session rather than to [NavScreen]. The navigation template has no
     * free text slot that stays on screen; a toast is what [NavScreen]'s
     * camera warning already does.
     *
     * The car only shows the line. The phone's map keeps speaking it, and
     * nothing in the app knows a car is connected, so a second voice here
     * would say every alert twice. The text is [ConvoyGapAlerts.spoken]'s
     * English sentence, as the phone speaks it. Collected only while the
     * session is started: the flow doesn't replay, so an alert raised with the
     * car screen stopped is not shown late, which is what a toast wants.
     */
    private fun toastConvoyGapAlerts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConvoyGapAlerts.spoken.collect { line ->
                    runCatching {
                        carContext.getCarService(AppManager::class.java).showToast(line, CarToast.LENGTH_LONG)
                    }.onFailure { Log.w(TAG, "convoy gap toast failed", it) }
                }
            }
        }
    }
}
