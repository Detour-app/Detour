package com.jellemax.detour

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jellemax.detour.nav.Destination
import com.jellemax.detour.nav.circleNotificationStack
import com.jellemax.detour.nav.pop
import com.jellemax.detour.nav.push
import com.jellemax.detour.nav.returnToMap
import com.jellemax.detour.nav.tripNotificationStack
import com.jellemax.detour.data.Oidc
import com.jellemax.detour.auth.PendingSignIn
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.Auth
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripStore
import com.jellemax.detour.data.UpdateClient
import com.jellemax.detour.notif.CircleNotifyService
import com.jellemax.detour.notif.PendingCircleOpen
import com.jellemax.detour.notif.PendingTripOpen
import com.jellemax.detour.notif.PlaceNotifications
import com.jellemax.detour.update.UpdateDownloader
import com.jellemax.detour.update.UpdateNotification
import com.jellemax.detour.update.UpdateState
import com.jellemax.detour.update.UpdateStatus
import com.jellemax.detour.ui.BadgesScreen
import com.jellemax.detour.ui.CircleDetailScreen
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
import com.jellemax.detour.ui.SettingsSpokeScreen
import com.jellemax.detour.ui.TripDetailScreen
import com.jellemax.detour.ui.isAppDarkTheme
import com.jellemax.detour.ui.rememberRetainedMap
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
     * onStart, not onResume. Returning from the install sheet, the unknown-
     * sources settings screen or a browser all fire onResume, and re-entering
     * the check on the way back from the thing the check just started is how a
     * state machine chases its own tail. The hourly throttle would mask it.
     */
    override fun onStart() {
        super.onStart()
        checkForUpdate()
    }

    private fun checkForUpdate() {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - Settings.lastUpdateCheckMs() < 60 * 60 * 1000L) return
        // Stamped before the request: a device with no connectivity would
        // otherwise retry on every foreground.
        Settings.setLastUpdateCheckMs(now)
        lifecycleScope.launch(Dispatchers.IO) {
            val update = runCatching {
                UpdateClient.newerThan(repo, BuildConfig.VERSION_NAME)
            }.getOrNull()
            // Silent on failure. This is a background courtesy; a rider mid-ride
            // is never told the update check could not reach GitHub.
            // Never prune while a download is running. prune deletes by name;
            // the downloader holds the file open, and unlinking an open file
            // succeeds silently on Linux — the download then "completes",
            // verify() passes on the in-memory digest, and the app reports
            // Downloaded for a path that no longer exists. Needs a slow
            // download alive past the hourly mark plus a failing check to
            // coincide, which is rare and entirely silent when it happens.
            if (UpdateState.status.value is UpdateStatus.Downloading) return@launch
            if (update == null) {
                UpdateDownloader.prune(this@MainActivity, keep = null)
                return@launch
            }
            UpdateDownloader.prune(this@MainActivity, keep = update.asset)
            if (UpdateState.current()?.version != update.version) {
                UpdateState.set(UpdateStatus.Available(update))
            }
            UpdateNotification.notifyOnce(this@MainActivity, update.version)
        }
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
        val data = intent?.data?.toString() ?: return
        if (!Oidc.isCallback(data)) return
        PendingSignIn.begin()
        lifecycleScope.launch {
            try {
                Oidc.complete(data)
                // Read after complete(), never before: the handle is decoded out
                // of the access token by Auth.store(), so it does not exist until
                // the exchange has happened.
                PendingSignIn.succeed(Auth.username.value)
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

@Composable
private fun AppRoot() {
    // Created here, above the navigation swap, so it outlives MapScreen's
    // composition — that is the whole point of it. AppRoot is composed for the
    // Activity's life, which is the correct scope for a MapView's Context. See
    // RetainedMap.
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val retainedMap = rememberRetainedMap(darkTheme = isAppDarkTheme(themePref))

    // The back stack the app owns, rooted at the map. This replaced `screen` — a
    // single value that could not say which way the rider moved, because
    // SETTINGS -> HUB and HUB -> SETTINGS are the same pair of values in the
    // opposite order. A list knows: it either grew or it shrank.
    //
    // rememberNavBackStack, not remember: the stack goes into saved state, so it
    // survives a rotation and a process death. `screen` was a plain `remember`,
    // which is why a rotation anywhere in the app used to return the rider to the
    // map.
    val backStack = rememberNavBackStack(Destination.Map)

    // A tapped arrival/departure notification opens straight to that circle,
    // wherever the app was.
    //
    // Replaces the stack rather than pushing, and that preserves today's
    // behaviour rather than changing it: with a single `screen` value, back from
    // Circles ran the `else -> Screen.HUB` branch regardless of where the rider
    // had been, so the implicit chain was already Map -> Hub -> Circles. A bare
    // push would be the change, sending back to whatever the notification
    // interrupted.
    val openCircleId by PendingCircleOpen.circleId.collectAsStateWithLifecycle()
    LaunchedEffect(openCircleId) {
        val id = openCircleId ?: return@LaunchedEffect
        // Lands on the circle itself now that CircleDetail is a destination,
        // where before it could only reach the Circles screen and hand it the id
        // to select. Back walks Circles -> Hub -> Map, the chain the old
        // `else -> Screen.HUB` branch produced implicitly.
        backStack.resetTo(circleNotificationStack(id))
        // Clearing is what lets a second tap navigate again. CirclesScreen used
        // to do this once it had consumed the id; the destination carries it now.
        PendingCircleOpen.clear()
    }

    // A tapped trip-ended notification opens that trip, not just the app.
    val openTripStartMs by PendingTripOpen.startTimeMs.collectAsStateWithLifecycle()
    LaunchedEffect(openTripStartMs) {
        val start = openTripStartMs ?: return@LaunchedEffect
        // load() reads and parses a file, so it stays off the main thread — same
        // reasoning as HistoryScreen's own load. Existence is settled here rather
        // than inside the TripDetail entry because a trip that is gone must not
        // land on a blank detail screen: deleted, or dropped by a /sync merge
        // before the tap, and the history list is the honest fallback. Same
        // choice the old effect made.
        val exists = withContext(Dispatchers.IO) {
            TripStore.load().any { it.startTimeMs == start }
        }
        backStack.resetTo(tripNotificationStack(if (exists) start else null))
        // Clearing is what lets a second tap navigate again.
        PendingTripOpen.clear()
    }

    // Sub-screens slide in over the map from the right and slide back out the
    // same way, so opening and closing feel like a push and a pop.
    //
    // Which of the two it is no longer comes from a depth table. NavDisplay takes
    // the two specs separately because it knows whether the list grew or shrank,
    // and predictivePopTransitionSpec drives the system back gesture, which this
    // app did not animate at all before — the manifest has carried
    // android:enableOnBackInvokedCallback="true" since before this change
    // (AndroidManifest.xml:50), so the gesture was opted into with nothing
    // drawing it.
    //
    // The two animation bodies are the ones ui/PushPopContent shipped, moved
    // verbatim. Only the choice between them changed hands.
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.pop() },
        transitionSpec = {
            (slideInHorizontally { it } + fadeIn()) togetherWith
                (slideOutHorizontally { -it / 4 } + fadeOut())
        },
        popTransitionSpec = {
            // What we are returning to eases in from the left while the screen
            // being left slides off to the right, the way it came in.
            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { it } + fadeOut())
        },
        predictivePopTransitionSpec = {
            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { it } + fadeOut())
        },
        entryProvider = entryProvider {
            entry<Destination.Map> {
                MapScreen(
                    onOpenHub = { backStack.push(Destination.Hub) },
                    retained = retainedMap,
                )
            }
            entry<Destination.Hub> {
                HubScreen(
                    onBack = { backStack.pop() },
                    onOpenHistory = { backStack.push(Destination.History) },
                    onOpenBadges = { backStack.push(Destination.Badges) },
                    onOpenFriends = { backStack.push(Destination.Friends) },
                    onOpenCircles = { backStack.push(Destination.Circles) },
                    onOpenSettings = { backStack.push(Destination.Settings) },
                    onOpenSavedPlaces = { backStack.push(Destination.SavedPlaces) },
                    onOpenRoutes = { backStack.push(Destination.Routes) },
                )
            }
            entry<Destination.History> {
                HistoryScreen(
                    onBack = { backStack.pop() },
                    onOpenTrip = { trip ->
                        backStack.push(Destination.TripDetail(trip.startTimeMs))
                    },
                )
            }
            entry<Destination.TripDetail> { key ->
                TripDetailEntry(startTimeMs = key.startTimeMs, onBack = { backStack.pop() })
            }
            entry<Destination.Badges> {
                BadgesScreen(
                    onBack = { backStack.pop() },
                    onOpenCoverageMap = { backStack.push(Destination.CoverageMap) },
                )
            }
            entry<Destination.CoverageMap> {
                CoverageMapScreen(onBack = { backStack.pop() })
            }
            entry<Destination.Friends> { FriendsScreen(onBack = { backStack.pop() }) }
            entry<Destination.Circles> {
                CirclesScreen(
                    onBack = { backStack.pop() },
                    onOpenCircle = { id -> backStack.push(Destination.CircleDetail(id)) },
                )
            }
            entry<Destination.CircleDetail> { key ->
                CircleDetailScreen(circleId = key.circleId, onBack = { backStack.pop() })
            }
            entry<Destination.Settings> {
                SettingsScreen(
                    onBack = { backStack.pop() },
                    onOpenSpoke = { spoke -> backStack.push(spoke) },
                )
            }
            // Six entries rather than one, because entryProvider dispatches on the
            // concrete key type. Each renders through the same SettingsSpokeScreen,
            // whose `when` is exhaustive over Destination.SettingsSpoke.
            entry<Destination.SettingsAppearanceMap> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SettingsTrackingVehicles> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SettingsNavigation> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SettingsFog> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SettingsDisplaysMedia> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SettingsServersSync> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SettingsObd2> { key ->
                SettingsSpokeScreen(key, onBack = { backStack.pop() })
            }
            entry<Destination.SavedPlaces> { SavedPlacesScreen(onBack = { backStack.pop() }) }
            entry<Destination.Routes> {
                RoutesScreen(
                    onBack = { backStack.pop() },
                    onCreateNew = { backStack.push(Destination.RouteEditor(null)) },
                    onEdit = { route -> backStack.push(Destination.RouteEditor(route.id)) },
                    // Not a pop: Routes sits two deep, so popping once would land
                    // on Hub. See NavActions.returnToMap.
                    onNavigate = { backStack.returnToMap() },
                )
            }
            entry<Destination.RouteEditor> { key ->
                RouteEditorEntry(
                    routeId = key.routeId,
                    onBack = { backStack.pop() },
                    onSaved = { backStack.pop() },
                )
            }
        },
    )
}

/**
 * Replaces the whole stack in one snapshot.
 *
 * Two statements — clear then addAll — would let a reader observe an empty back
 * stack, which NavDisplay has no entry to render. `withMutableSnapshot` makes the
 * pair atomic.
 */
private fun NavBackStack<NavKey>.resetTo(entries: List<Destination>) {
    Snapshot.withMutableSnapshot {
        clear()
        addAll(entries)
    }
}

/**
 * [TripDetailScreen] reached from a key rather than from a passed `Trip`.
 *
 * The old code held the trip in an `AppRoot` local, set on the way in from
 * History and left stale once navigated away. A key has to survive being written
 * to saved state and read back after the process died, and `Trip` has no id field
 * — trips are keyed by `startTimeMs` (`TripStore.kt`) — so the screen loads its
 * own subject.
 *
 * `TripStore.load()` reads and parses a file, so it stays off the main thread.
 * Null renders nothing, which is what `detailTrip?.let { … }` did before.
 */
@Composable
private fun TripDetailEntry(startTimeMs: Long, onBack: () -> Unit) {
    var trip by remember(startTimeMs) { mutableStateOf<Trip?>(null) }
    LaunchedEffect(startTimeMs) {
        trip = withContext(Dispatchers.IO) {
            TripStore.load().find { it.startTimeMs == startTimeMs }
        }
    }
    trip?.let { TripDetailScreen(trip = it, onBack = onBack) }
}

/**
 * [RouteEditorScreen] reached from a route id rather than from a passed
 * `SavedRoute?`.
 *
 * No IO here, unlike [TripDetailEntry]: `Routes.routes` is already a `StateFlow`
 * of the loaded list, so the subject is a lookup. A null [routeId] means a new
 * route, the same convention the old `editingRoute` local used.
 */
@Composable
private fun RouteEditorEntry(routeId: Long?, onBack: () -> Unit, onSaved: () -> Unit) {
    val routes by RouteStore.routes.collectAsStateWithLifecycle()
    RouteEditorScreen(
        editing = routeId?.let { id -> routes.find { it.id == id } },
        onBack = onBack,
        onSaved = onSaved,
    )
}
