package com.jellemax.detour

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import com.jellemax.detour.auth.Oidc
import com.jellemax.detour.auth.PendingSignIn
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripStore
import com.jellemax.detour.notif.CircleNotifyService
import com.jellemax.detour.notif.PendingCircleOpen
import com.jellemax.detour.notif.PendingTripOpen
import com.jellemax.detour.notif.PlaceNotifications
import com.jellemax.detour.ui.BadgesScreen
import com.jellemax.detour.ui.CirclesScreen
import com.jellemax.detour.ui.CoverageMapScreen
import com.jellemax.detour.ui.FriendsScreen
import com.jellemax.detour.ui.HistoryScreen
import com.jellemax.detour.ui.HubScreen
import com.jellemax.detour.ui.MapScreen
import com.jellemax.detour.ui.GraphiteDark
import com.jellemax.detour.ui.GraphiteLight
import com.jellemax.detour.ui.RouteEditorScreen
import com.jellemax.detour.ui.RoutesScreen
import com.jellemax.detour.ui.SavedPlacesScreen
import com.jellemax.detour.ui.SettingsScreen
import com.jellemax.detour.ui.TripDetailScreen
import com.jellemax.detour.ui.isAppDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeSignInRedirect(intent)
        PlaceNotifications.takeOpenCircleId(intent)
        PendingTripOpen.take(intent)
        enableEdgeToEdge()
        // A map app is glanced at while driving: keep the screen awake while visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ColdStartTiming.timed("Settings.init") { Settings.init() }
        if (Settings.externalDisplayEnabled.value) BleNavServer.start(this)
        // Cheap, synchronous no-op unless signed in with a server configured
        // (see the function's own doc) - safe to call unconditionally on
        // every app start, same as TripTrackingService.startMonitoring's own
        // call site would be if MapScreen didn't already own that one.
        ColdStartTiming.timed("CircleNotifyService.refresh") { CircleNotifyService.refresh(this) }
        // MapLibre must be initialised before any MapView is created. No API key:
        // OpenFreeMap tiles are keyless, so no token provider is needed.
        ColdStartTiming.timed("MapLibre.getInstance") { MapLibre.getInstance(this) }
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

    // singleTop means the sign-in redirect, which arrives while the app is
    // already open behind the browser, is delivered here rather than to onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSignInRedirect(intent)
        PlaceNotifications.takeOpenCircleId(intent)
        PendingTripOpen.take(intent)
    }

    /**
     * Spends the authorization code from a `detour://auth/callback` redirect.
     *
     * On the activity's own scope rather than a screen's: the redirect can land
     * while the Friends screen is not composed (the browser was in front), and
     * the exchange has to happen once, not once per recomposition. The outcome
     * goes to [PendingSignIn], which is what the screen reads.
     */
    private fun takeSignInRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (!Oidc.isCallback(data)) return
        PendingSignIn.begin()
        lifecycleScope.launch {
            try {
                Oidc.complete(data)
                PendingSignIn.succeed()
            } catch (e: Exception) {
                val reason = e.message ?: "Sign-in failed"
                // A failed sign-in leaves no other trace: there is no crash, the
                // browser has closed, and the screen it used to report to may not
                // be composed (see PendingSignIn). Logged so `adb logcat -s
                // DetourAuth` answers "why" without a rebuild — ASVS 5.0.0
                // V16.3.2 asks for failed authorization attempts to be logged.
                //
                // `reason` and the throwable, never `data`: the redirect URI is
                // the one string here that carries the authorization code, and
                // logging a credential is what V16.2.5 forbids. Oidc.complete's
                // messages are written to be safe to print for the same reason.
                Log.w(TAG, "sign-in redirect did not become a session: $reason", e)
                PendingSignIn.fail(reason)
            }
        }
    }
}

private const val TAG = "DetourAuth"

private enum class Screen {
    MAP, HUB, HISTORY, TRIP_DETAIL, BADGES, FRIENDS, CIRCLES, SETTINGS, SAVED, ROUTES,
    ROUTE_EDITOR, COVERAGE_MAP,
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.MAP) }
    // The trip a TRIP_DETAIL screen is showing — set on the way in from
    // History, left stale (but unread) once we've navigated away from it.
    var detailTrip by remember { mutableStateOf<Trip?>(null) }
    // The route a ROUTE_EDITOR screen is showing — null means "new route",
    // same left-stale-once-navigated-away convention as detailTrip.
    var editingRoute by remember { mutableStateOf<SavedRoute?>(null) }
    // A tapped arrival/departure notification opens straight to that circle,
    // wherever the app was.
    val openCircleId by PendingCircleOpen.circleId.collectAsStateWithLifecycle()
    LaunchedEffect(openCircleId) {
        if (openCircleId != null) screen = Screen.CIRCLES
    }
    // A tapped trip-ended notification opens that trip, not just the app.
    val openTripStartMs by PendingTripOpen.startTimeMs.collectAsStateWithLifecycle()
    LaunchedEffect(openTripStartMs) {
        val start = openTripStartMs ?: return@LaunchedEffect
        // load() reads and parses a file, so it stays off the main thread —
        // same reasoning as HistoryScreen's own load.
        val trip = withContext(Dispatchers.IO) { TripStore.load().find { it.startTimeMs == start } }
        // Assigned before the screen switch: TRIP_DETAIL renders nothing at all
        // when detailTrip is null.
        detailTrip = trip
        // Deleted, or dropped by a /sync merge before the tap — the history list
        // is the honest fallback, rather than a blank detail screen.
        screen = if (trip != null) Screen.TRIP_DETAIL else Screen.HISTORY
        // Clearing is what lets a second tap navigate again.
        PendingTripOpen.clear()
    }
    // System back from any sub-screen returns to the map instead of exiting the
    // app — only enabled off the map, so back on the map itself still falls
    // through to the default (exit) behaviour. The destinations off Hub step
    // back to Hub, not all the way to the map, so back always undoes one level
    // of the push it followed to get here — except TRIP_DETAIL, which is pushed
    // from History rather than Hub, so it steps back to History instead,
    // ROUTE_EDITOR, which is pushed from ROUTES and steps back there, and
    // COVERAGE_MAP, which is pushed from Badges and steps back there.
    BackHandler(enabled = screen != Screen.MAP) {
        screen = when (screen) {
            Screen.HUB -> Screen.MAP
            Screen.TRIP_DETAIL -> Screen.HISTORY
            Screen.ROUTE_EDITOR -> Screen.ROUTES
            Screen.COVERAGE_MAP -> Screen.BADGES
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
                onOpenCircles = { screen = Screen.CIRCLES },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenSavedPlaces = { screen = Screen.SAVED },
                onOpenRoutes = { screen = Screen.ROUTES },
            )
            Screen.HISTORY -> HistoryScreen(
                onBack = { screen = Screen.HUB },
                onOpenTrip = { trip -> detailTrip = trip; screen = Screen.TRIP_DETAIL },
            )
            Screen.TRIP_DETAIL -> detailTrip?.let { trip ->
                TripDetailScreen(trip = trip, onBack = { screen = Screen.HISTORY })
            }
            Screen.BADGES -> BadgesScreen(
                onBack = { screen = Screen.HUB },
                onOpenCoverageMap = { screen = Screen.COVERAGE_MAP },
            )
            Screen.COVERAGE_MAP -> CoverageMapScreen(onBack = { screen = Screen.BADGES })
            Screen.FRIENDS -> FriendsScreen(onBack = { screen = Screen.HUB })
            Screen.CIRCLES -> CirclesScreen(onBack = { screen = Screen.HUB }, openCircleId = openCircleId)
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HUB })
            Screen.SAVED -> SavedPlacesScreen(onBack = { screen = Screen.HUB })
            Screen.ROUTES -> RoutesScreen(
                onBack = { screen = Screen.HUB },
                onCreateNew = { editingRoute = null; screen = Screen.ROUTE_EDITOR },
                onEdit = { route -> editingRoute = route; screen = Screen.ROUTE_EDITOR },
                onNavigate = { screen = Screen.MAP },
            )
            Screen.ROUTE_EDITOR -> RouteEditorScreen(
                editing = editingRoute,
                onBack = { screen = Screen.ROUTES },
                onSaved = { screen = Screen.ROUTES },
            )
            Screen.MAP -> MapScreen(onOpenHub = { screen = Screen.HUB })
        }
    }
}
