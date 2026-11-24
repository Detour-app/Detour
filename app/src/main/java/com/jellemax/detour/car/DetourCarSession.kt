package com.jellemax.detour.car

import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jellemax.detour.R
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

    /** The root screen, so a search opened by a NAVIGATE intent can still spin from it. */
    private var home: SpinScreen? = null

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
                home = null
            }
        })
        toastConvoyGapAlerts()
        CarBreakRegroup.announce(this, carContext)
        val home = SpinScreen(carContext, map)
        this.home = home
        // Cold start from "Navigate to X": home goes on the stack first and the
        // search is returned, so the host shows the search on top and Back from
        // it lands on home. (Returning home instead puts home above the search.)
        val search = navigateSearch(intent, map, home) ?: return home
        carContext.getCarService(ScreenManager::class.java).push(home)
        return search
    }

    /** "Navigate to X" while the session is already running. */
    override fun onNewIntent(intent: Intent) {
        val map = renderer ?: return
        val home = home ?: return
        navigateSearch(intent, map, home)?.let { carContext.getCarService(ScreenManager::class.java).push(it) }
    }

    /** Head unit flipped day/night: the map style has to follow (the host
     *  only re-themes its own templates). */
    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        renderer?.setDarkTheme(carContext.isDarkMode)
    }

    /** The search a NAVIGATE intent opens, or null when [intent] is not one we should act on. */
    private fun navigateSearch(intent: Intent, map: CarMapRenderer, home: SpinScreen): SearchScreen? {
        val uri = intent.data?.takeIf { intent.action == CarContext.ACTION_NAVIGATE } ?: return null
        if (CarNavHandOff.isEcho(uri)) {
            // Our own hand-off came back: Detour is the car's navigation app, so
            // there is nowhere else to send a route without turn data.
            CarToast.makeText(carContext, R.string.car_handoff_no_other_app, CarToast.LENGTH_LONG).show()
            return null
        }
        return SearchScreen(carContext, map, onSpin = { home.spin() }, geoUri = uri)
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
