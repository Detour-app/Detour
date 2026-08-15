package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode

/** Launches navigation via [app] and remembers it as the default for next
 *  time — the single dispatch point behind the dropdown items in
 *  [NavMenuItems] and the direct-tap bypass on [NavButton]/[NavIconButton]. */
private fun launchNav(
    context: Context,
    app: Settings.NavApp,
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
) {
    when (app) {
        Settings.NavApp.IN_APP -> onNavigateInApp()
        Settings.NavApp.GOOGLE_MAPS -> {
            onNavigate()
            // Waze can't take multi-waypoint routes; Google Maps only.
            if (route != null && origin != null) navigateRoundTrip(context, origin, route)
            else destination?.let { navigateGoogleMaps(context, it, mode) }
        }
        Settings.NavApp.WAZE -> { onNavigate(); destination?.let { navigateWaze(context, it) } }
        Settings.NavApp.OTHER -> { onNavigate(); destination?.let { navigateGeo(context, it) } }
        Settings.NavApp.ASK -> return // unreachable — callers only pass a concrete app
    }
    Settings.setPreferredNavApp(app)
}

/** Whether [app] can be launched right now without opening the menu — false
 *  for ASK (nothing remembered yet), and false when a round-trip route is
 *  active but [app] can't take multi-waypoint routes (Waze/"Other app"). */
private fun navAppUsableDirectly(
    app: Settings.NavApp,
    inAppAvailable: Boolean,
    route: List<LatLon>?,
    origin: LatLon?,
): Boolean = when (app) {
    Settings.NavApp.ASK -> false
    Settings.NavApp.IN_APP -> inAppAvailable
    Settings.NavApp.GOOGLE_MAPS -> true
    Settings.NavApp.WAZE, Settings.NavApp.OTHER -> !(route != null && origin != null)
}

/** A tap on [NavButton]/[NavIconButton]: go straight to the remembered app
 *  when it's usable here, otherwise fall back to opening the menu — the
 *  same fallback a long-press always takes. */
private fun handleGoTap(
    context: Context,
    preferred: Settings.NavApp,
    inAppAvailable: Boolean,
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    openMenu: () -> Unit,
) {
    if (navAppUsableDirectly(preferred, inAppAvailable, route, origin)) {
        launchNav(context, preferred, destination, route, origin, mode, onNavigateInApp, onNavigate)
    } else {
        openMenu()
    }
}

/** Shared "Go" menu items — in-app when reachable, otherwise the external-app
 *  chooser. Backs both the full-width [NavButton] and the dock's compact
 *  [NavIconButton] so the routing logic lives in exactly one place. */
@Composable
private fun NavMenuItems(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun pick(app: Settings.NavApp) {
        onDismiss()
        launchNav(context, app, destination, route, origin, mode, onNavigateInApp, onNavigate)
    }
    if (inAppAvailable) {
        DropdownMenuItem(
            text = { Text("Navigate in app") },
            onClick = { pick(Settings.NavApp.IN_APP) },
        )
    }
    if (route != null && origin != null) {
        DropdownMenuItem(
            text = { Text("Google Maps (round trip)") },
            onClick = { pick(Settings.NavApp.GOOGLE_MAPS) },
        )
    } else {
        DropdownMenuItem(
            text = { Text("Google Maps") },
            onClick = { pick(Settings.NavApp.GOOGLE_MAPS) },
        )
        DropdownMenuItem(
            text = { Text("Waze") },
            onClick = { pick(Settings.NavApp.WAZE) },
        )
        DropdownMenuItem(
            text = { Text("Other app") },
            onClick = { pick(Settings.NavApp.OTHER) },
        )
    }
}

/** Compact circular "Go" trigger for the dock — same menu as [NavButton],
 *  just a 40dp icon button instead of a labelled tonal one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavIconButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferred by Settings.preferredNavApp.collectAsStateWithLifecycle()
    val enabled = destination != null || (route != null && origin != null)
    Box(modifier) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        handleGoTap(context, preferred, inAppAvailable, destination, route, origin,
                            mode, onNavigateInApp, onNavigate) { menuOpen = true }
                    },
                    onLongClick = { menuOpen = true },
                ),
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Outlined.Navigation, contentDescription = "Go")
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NavMenuItems(destination, route, origin, mode, inAppAvailable,
                onNavigateInApp, onNavigate) { menuOpen = false }
        }
    }
}

/** "Go" button with a chooser for the navigation app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferred by Settings.preferredNavApp.collectAsStateWithLifecycle()
    val enabled = destination != null || (route != null && origin != null)
    Box(modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        handleGoTap(context, preferred, inAppAvailable, destination, route, origin,
                            mode, onNavigateInApp, onNavigate) { menuOpen = true }
                    },
                    onLongClick = { menuOpen = true },
                ),
            shape = ButtonDefaults.shape,
            color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Navigation, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Go", maxLines = 1)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NavMenuItems(destination, route, origin, mode, inAppAvailable,
                onNavigateInApp, onNavigate) { menuOpen = false }
        }
    }
}

private fun navigateRoundTrip(context: Context, origin: LatLon, waypoints: List<LatLon>) {
    // Directions URL: origin = destination = start, curvy roads as via points.
    // Google Maps supports up to 9 waypoints in this form.
    val wp = waypoints.joinToString("|") { "${it.lat},${it.lon}" }
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${origin.lat},${origin.lon}" +
            "&destination=${origin.lat},${origin.lon}" +
            "&travelmode=driving" +
            "&waypoints=" + Uri.encode(wp)
    )
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

private fun navigateGoogleMaps(context: Context, dest: LatLon, mode: TravelMode) {
    val uri = Uri.parse("google.navigation:q=${dest.lat},${dest.lon}&mode=${mode.gmapsMode}")
    val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        navigateGeo(context, dest)
    }
}

private fun navigateWaze(context: Context, dest: LatLon) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=${dest.lat},${dest.lon}&navigate=yes"))
        )
    } catch (e: ActivityNotFoundException) {
        // Waze not installed: universal link opens install page or web.
        context.startActivity(
            Intent(Intent.ACTION_VIEW,
                Uri.parse("https://waze.com/ul?ll=${dest.lat},${dest.lon}&navigate=yes"))
        )
    }
}

private fun navigateGeo(context: Context, dest: LatLon) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW,
            Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}"))
    )
}
