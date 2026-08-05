package com.jellemax.detour.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationServices
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SunTimes
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/** Resolves the theme preference to dark yes/no; app and map tiles share it. */
@Composable
fun isAppDarkTheme(theme: Settings.Theme): Boolean = when (theme) {
    Settings.Theme.SYSTEM -> isSystemInDarkTheme()
    Settings.Theme.LIGHT -> false
    Settings.Theme.DARK -> true
    Settings.Theme.AUTO -> isNightNow()
}

/**
 * Day/night at the device's last known location, re-checked every minute so
 * the theme flips at sunrise/sunset while the app is open. Falls back to a
 * clock-based guess until a location is available.
 */
@Composable
private fun isNightNow(): Boolean {
    val context = LocalContext.current
    var night by remember {
        mutableStateOf(SunTimes.isNightFallback(System.currentTimeMillis()))
    }
    LaunchedEffect(Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        while (true) {
            val now = System.currentTimeMillis()
            val loc = try {
                client.lastLocation.await()
            } catch (e: Exception) {
                null // no permission or no fix yet
            }
            night = if (loc != null) {
                SunTimes.isNight(loc.latitude, loc.longitude, now)
            } else {
                SunTimes.isNightFallback(now)
            }
            delay(60_000)
        }
    }
    return night
}
