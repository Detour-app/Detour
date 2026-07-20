package com.jellemax.maproulette

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.ble.BleNavServer
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.ui.BadgesScreen
import com.jellemax.maproulette.ui.FriendsScreen
import com.jellemax.maproulette.ui.HistoryScreen
import com.jellemax.maproulette.ui.MapScreen
import com.jellemax.maproulette.ui.GraphiteDark
import com.jellemax.maproulette.ui.GraphiteLight
import com.jellemax.maproulette.ui.SavedPlacesScreen
import com.jellemax.maproulette.ui.SettingsScreen
import com.jellemax.maproulette.ui.isAppDarkTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A map app is glanced at while driving: keep the screen awake while visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Settings.init(this)
        if (Settings.externalDisplayEnabled.value) BleNavServer.start(this)
        // MapLibre must be initialised before any MapView is created. No API key:
        // OpenFreeMap tiles are keyless, so no token provider is needed.
        MapLibre.getInstance(this)
        setContent {
            val theme by Settings.theme.collectAsStateWithLifecycle()
            val dark = isAppDarkTheme(theme)
            // The Graphite identity — a fixed amber-on-graphite scheme so the app
            // (and the watch) share one look, instead of the wallpaper's colours.
            MaterialTheme(colorScheme = if (dark) GraphiteDark else GraphiteLight) {
                Surface { AppRoot() }
            }
        }
    }
}

private enum class Screen { MAP, HISTORY, BADGES, FRIENDS, SETTINGS, SAVED }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.MAP) }
    when (screen) {
        Screen.HISTORY -> HistoryScreen(onBack = { screen = Screen.MAP })
        Screen.BADGES -> BadgesScreen(onBack = { screen = Screen.MAP })
        Screen.FRIENDS -> FriendsScreen(onBack = { screen = Screen.MAP })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MAP })
        Screen.SAVED -> SavedPlacesScreen(onBack = { screen = Screen.MAP })
        Screen.MAP -> MapScreen(
            onOpenHistory = { screen = Screen.HISTORY },
            onOpenBadges = { screen = Screen.BADGES },
            onOpenFriends = { screen = Screen.FRIENDS },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenSavedPlaces = { screen = Screen.SAVED },
        )
    }
}
