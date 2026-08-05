package com.jellemax.maproulette

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.ble.BleNavServer
import com.jellemax.maproulette.data.PendingReset
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.Trip
import com.jellemax.maproulette.ui.BadgesScreen
import com.jellemax.maproulette.ui.FriendsScreen
import com.jellemax.maproulette.ui.HistoryScreen
import com.jellemax.maproulette.ui.HubScreen
import com.jellemax.maproulette.ui.MapScreen
import com.jellemax.maproulette.ui.GraphiteDark
import com.jellemax.maproulette.ui.GraphiteLight
import com.jellemax.maproulette.ui.SavedPlacesScreen
import com.jellemax.maproulette.ui.SettingsScreen
import com.jellemax.maproulette.ui.TripDetailScreen
import com.jellemax.maproulette.ui.isAppDarkTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeResetLink(intent)
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
            // Status bar icons need to read against the map behind them: dark
            // icons over the light theme's pale map, light icons over the dark
            // theme's near-black one. Keyed on the same day/night decision the
            // app theme itself just made, so the two can never disagree.
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
            }
            // The Graphite identity — a fixed amber-on-graphite scheme so the app
            // (and the watch) share one look, instead of the wallpaper's colours.
            MaterialTheme(colorScheme = if (dark) GraphiteDark else GraphiteLight) {
                Surface { AppRoot() }
            }
        }
    }

    // singleTop means a reset link tapped while the app is already open is
    // delivered here instead of through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeResetLink(intent)
    }

    /** Parks the code from a `maproulette://reset?token=…` link for the
     *  Friends screen, which AppRoot then brings to the front. */
    private fun takeResetLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "maproulette" && data.host == "reset") {
            PendingReset.offer(data.getQueryParameter("token").orEmpty())
        }
    }
}

private enum class Screen { MAP, HUB, HISTORY, TRIP_DETAIL, BADGES, FRIENDS, SETTINGS, SAVED }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.MAP) }
    // The trip a TRIP_DETAIL screen is showing — set on the way in from
    // History, left stale (but unread) once we've navigated away from it.
    var detailTrip by remember { mutableStateOf<Trip?>(null) }
    // A reset link opens the screen that can spend it, wherever the app was.
    val resetToken by PendingReset.token.collectAsStateWithLifecycle()
    LaunchedEffect(resetToken) {
        if (resetToken.isNotBlank()) screen = Screen.FRIENDS
    }
    // System back from any sub-screen returns to the map instead of exiting the
    // app — only enabled off the map, so back on the map itself still falls
    // through to the default (exit) behaviour. The five destinations off Hub
    // step back to Hub, not all the way to the map, so back always undoes one
    // level of the push it followed to get here — except TRIP_DETAIL, which is
    // pushed from History rather than Hub, so it steps back to History instead.
    BackHandler(enabled = screen != Screen.MAP) {
        screen = when (screen) {
            Screen.HUB -> Screen.MAP
            Screen.TRIP_DETAIL -> Screen.HISTORY
            else -> Screen.HUB
        }
    }
    // Sub-screens slide in over the map from the right and slide back out the
    // same way, so opening/closing feels like a push/pop instead of a hard swap.
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState == Screen.MAP) {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            } else {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            }
        },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.HUB -> HubScreen(
                onBack = { screen = Screen.MAP },
                onOpenHistory = { screen = Screen.HISTORY },
                onOpenBadges = { screen = Screen.BADGES },
                onOpenFriends = { screen = Screen.FRIENDS },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenSavedPlaces = { screen = Screen.SAVED },
            )
            Screen.HISTORY -> HistoryScreen(
                onBack = { screen = Screen.HUB },
                onOpenTrip = { trip -> detailTrip = trip; screen = Screen.TRIP_DETAIL },
            )
            Screen.TRIP_DETAIL -> detailTrip?.let { trip ->
                TripDetailScreen(trip = trip, onBack = { screen = Screen.HISTORY })
            }
            Screen.BADGES -> BadgesScreen(onBack = { screen = Screen.HUB })
            Screen.FRIENDS -> FriendsScreen(onBack = { screen = Screen.HUB })
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HUB })
            Screen.SAVED -> SavedPlacesScreen(onBack = { screen = Screen.HUB })
            Screen.MAP -> MapScreen(onOpenHub = { screen = Screen.HUB })
        }
    }
}
