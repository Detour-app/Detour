package com.jellemax.detour.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.syncQuietly
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.nav.Destination
import com.jellemax.detour.presentation.formatFixed
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.tracking.DormancyBlocker
import com.jellemax.detour.tracking.dormancyBlocker
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2

/**
 * The title each Settings destination shows in its own top bar.
 *
 * Exhaustive over [Destination.SettingsSpoke], so adding a spoke without giving
 * it a title does not compile. This replaced `SettingsPage`, an enum that carried
 * both the title and a hand-maintained `depth` — the depth is gone with the
 * inference it fed.
 */
// internal, not private: SettingsHub.kt is a second file in this package
// holding the Settings root, and it calls this to title each row.
internal fun spokeTitle(spoke: Destination.SettingsSpoke): String = when (spoke) {
    Destination.SettingsAppearanceMap -> "Appearance & map"
    Destination.SettingsTrackingVehicles -> "Tracking & vehicles"
    Destination.SettingsNavigation -> "Navigation"
    Destination.SettingsFog -> "Fog of war"
    Destination.SettingsDisplaysMedia -> "Displays & media"
    Destination.SettingsServersSync -> "Servers & sync"
    Destination.SettingsObd2 -> "OBD2 adapter"
}

/**
 * The Scaffold every Settings destination shares.
 *
 * One per destination rather than one wrapping an inner animation, which is the
 * whole of the change: the top bar now belongs to the screen and travels with it,
 * the way `HistoryScreen.kt` and `BadgesScreen.kt` have always worked. Settings
 * was the only push in the app where the bar stayed put and the title snapped.
 *
 * A consequence worth naming rather than discovering: `pinnedScrollBehavior` is
 * created per destination now, where one instance used to be shared across the
 * root and all six spokes. Opening a spoke after scrolling another no longer
 * inherits its scrolled container colour. The scroll *position* was already
 * per-spoke — #66 put `rememberScrollState()` inside the animated lambda — so
 * only the bar's own state changes hands.
 */
// internal, not private: SettingsHub.kt is a second file in this package
// holding the Settings root, and it shares this scaffold rather than
// duplicating it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    spacing: Dp,
    // Hoisted, not just remembered here, so a spoke can scroll itself: the
    // Servers & sync status rows jump to the section they summarise, and that
    // needs the same ScrollState the column is scrolling.
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar(title, onBack, scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * One spoke, with its own top bar.
 *
 * The `when` is exhaustive over [Destination.SettingsSpoke] rather than over the
 * whole app's destinations, which is what the sealed sub-interface buys: a new
 * spoke fails to compile here until it is rendered.
 */
@Composable
fun SettingsSpokeScreen(spoke: Destination.SettingsSpoke, onBack: () -> Unit) {
    val context = LocalContext.current
    val theme by Settings.theme.collectAsStateWithLifecycle()
    val decimalSeparator by Settings.decimalSeparator.collectAsStateWithLifecycle()
    val autoDetect by Settings.autoDetectDrives.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    SettingsScaffold(spokeTitle(spoke), onBack, spacing = 16.dp, scrollState = scrollState) {
        when (spoke) {
            Destination.SettingsAppearanceMap -> {
                AppearanceSection(theme, decimalSeparator)
                MapIconSection()
                RouteColorSection(theme)
                MapSection()
            }
            Destination.SettingsTrackingVehicles -> {
                TrackingSection(autoDetect, context)
                VehicleSection()
                LeanCalibrationSection()
            }
            Destination.SettingsNavigation -> NavigationSection()
            Destination.SettingsFog -> FogSection(context)
            Destination.SettingsDisplaysMedia -> {
                ExternalDisplaySection()
                NowPlayingSection()
            }
            Destination.SettingsServersSync -> {
                ServersSyncSpoke(scrollState)
                DiagnosticsSection()
            }
            Destination.SettingsObd2 -> Obd2PairingScreen()
        }
    }
}

@Composable
private fun AppearanceSection(theme: Settings.Theme, separator: Settings.DecimalSeparator) {
    SettingsSection("Appearance") {
        val themes = Settings.Theme.entries
        ChoiceRow(
            options = themes.map { t -> t.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            selectedIndex = themes.indexOf(theme),
            onSelect = { Settings.setTheme(themes[it]) },
        )
        if (theme == Settings.Theme.AUTO) {
            Text(
                "Light by day, dark by night — follows sunrise and " +
                    "sunset at your location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Decimal separator", style = MaterialTheme.typography.bodyLarge)
        val separators = Settings.DecimalSeparator.entries
        ChoiceRow(
            options = separators.map { d ->
                when (d) {
                    Settings.DecimalSeparator.SYSTEM -> "System"
                    Settings.DecimalSeparator.POINT -> "1.2"
                    Settings.DecimalSeparator.COMMA -> "1,2"
                }
            },
            selectedIndex = separators.indexOf(separator),
            onSelect = { Settings.setDecimalSeparator(separators[it]) },
        )
        Text(
            "How readouts with a decimal — distances, g, fuel economy, " +
                "mount offset, map zoom — are written. Speeds round to whole " +
                "km/h, so they never show one either way. Map coordinates " +
                "always use a point, so a latitude/longitude pair stays " +
                "readable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackingSection(autoDetect: Boolean, context: Context) {
    SettingsSection("Tracking") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-detect drives", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Start a trip automatically when driving is detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoDetect,
                onCheckedChange = {
                    Settings.setAutoDetectDrives(it)
                    TripTrackingService.refresh(context)
                },
            )
        }
        ParkedDormancyNotice(autoDetect, context)
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** The live [dormancyBlocker] for this device, read fresh — permissions change
 *  outside the app, so this is never cached beyond the next ON_RESUME. */
private fun currentBlocker(context: Context, autoDetect: Boolean): DormancyBlocker =
    dormancyBlocker(
        autoDetect = autoDetect,
        // Pre-Q neither permission exists as a runtime grant, so neither can block.
        hasActivityRecognition = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACTIVITY_RECOGNITION),
        hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    )

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Says why the tracker is still running — and its notification still showing —
 * while the rider is parked, and offers the one grant that would stop it.
 * Issue #145.
 *
 * Nothing said this before: both permissions are optional, both fallbacks stay
 * always-on deliberately, and the rider had no way to connect the notification
 * they are looking at to a choice they made in a system dialog. For them the
 * complaint #90 set out to fix was still live, and now also invisible.
 */
@Composable
private fun ParkedDormancyNotice(autoDetect: Boolean, context: Context) {
    var blocker by remember { mutableStateOf(currentBlocker(context, autoDetect)) }
    var showBgDisclosure by remember { mutableStateOf(false) }

    // A function parameter is a plain value, frozen for the composition that
    // read it, and the observer below outlives several of those. Read the
    // toggle through this so a flip is seen without re-registering the
    // observer — see the compose-state-hazards skill, section 2.
    val autoDetectNow by rememberUpdatedState(autoDetect)

    LaunchedEffect(autoDetect) { blocker = currentBlocker(context, autoDetect) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        blocker = currentBlocker(context, autoDetectNow)
        // Denied twice, or a permission the system never prompts for: the
        // system dialog will not appear again, so the only route left is the
        // app's own settings page.
        if (blocker != DormancyBlocker.NONE) openAppSettings(context)
    }

    // Permissions are granted outside this screen — in the system dialog, or in
    // the app settings page we send the rider to — so the only reliable moment
    // to re-read them is coming back to the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                blocker = currentBlocker(context, autoDetectNow)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (blocker == DormancyBlocker.NONE) return

    val explanation = when (blocker) {
        DormancyBlocker.ACTIVITY_RECOGNITION ->
            "Detour's tracker stays on, with its notification showing, even when " +
                "you're parked. It needs physical activity access to tell that " +
                "you've stopped, so it can switch itself off until you ride again."
        DormancyBlocker.BACKGROUND_LOCATION ->
            "Detour's tracker stays on, with its notification showing, even when " +
                "you're parked. Set location access to \"Allow all the time\" and it " +
                "can switch itself off while parked, then wake when you ride away."
        DormancyBlocker.NONE -> return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                when (blocker) {
                    DormancyBlocker.ACTIVITY_RECOGNITION ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    // Play policy requires the prominent disclosure to be shown
                    // and accepted before the rider reaches the system screen.
                    DormancyBlocker.BACKGROUND_LOCATION -> showBgDisclosure = true
                    DormancyBlocker.NONE -> Unit
                }
            },
        ) {
            Text(
                when (blocker) {
                    DormancyBlocker.ACTIVITY_RECOGNITION -> "Allow physical activity"
                    else -> "Change location access"
                }
            )
        }
    }

    if (showBgDisclosure) {
        BackgroundLocationDisclosure(
            onAllow = {
                showBgDisclosure = false
                // From Android 11 the system raises no dialog for background
                // location at all — requestPermissions returns denied without
                // showing anything — so the app settings page is the only route
                // to "Allow all the time".
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openAppSettings(context)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onDismiss = { showBgDisclosure = false },
        )
    }
}

private fun navAppLabel(app: Settings.NavApp): String = when (app) {
    Settings.NavApp.ASK -> "Ask each time"
    Settings.NavApp.IN_APP -> "Navigate in app"
    Settings.NavApp.GOOGLE_MAPS -> "Google Maps"
    Settings.NavApp.WAZE -> "Waze"
    Settings.NavApp.OTHER -> "Other app"
}

@Composable
private fun NavigationSection() {
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val avoidSmallRoads by Settings.avoidSmallRoads.collectAsStateWithLifecycle()
    val preferredNavApp by Settings.preferredNavApp.collectAsStateWithLifecycle()
    val voiceGuidance by Settings.voiceGuidance.collectAsStateWithLifecycle()
    SettingsSection("Navigation") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Spoken guidance", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Turn instructions read aloud while navigating, here and on " +
                        "the car screen. Mutable mid-drive from the speaker button there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = voiceGuidance,
                onCheckedChange = { Settings.setVoiceGuidance(it) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Remembered nav app", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Go currently launches: ${navAppLabel(preferredNavApp)}. " +
                        "Long-press Go to change it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preferredNavApp != Settings.NavApp.ASK) {
                TextButton(onClick = { Settings.setPreferredNavApp(Settings.NavApp.ASK) }) {
                    Text("Reset")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Avoid highways", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "In-app navigation skips motorways (car mode; " +
                        "moto never uses them)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = avoidHighways,
                onCheckedChange = { Settings.setAvoidHighways(it) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Avoid small roads", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Prefer real roads over narrow rural lanes, " +
                        "service roads and unpaved tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = avoidSmallRoads,
                onCheckedChange = { Settings.setAvoidSmallRoads(it) },
            )
        }
    }
}

/** Waze-style picker for the marker drawn at your own position. A horizontal
 *  strip rather than a grid: this sits inside a vertically scrolling page,
 *  where a lazy grid has to be given a fixed height before it will lay out at
 *  all. */
@Composable
private fun MapIconSection() {
    val mapIcon by Settings.mapIcon.collectAsStateWithLifecycle()
    SettingsSection("Your marker") {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Settings.MapIcon.entries.forEach { icon ->
                val selected = icon == mapIcon
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .width(86.dp)
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            shape,
                        )
                        .clickable { Settings.setMapIcon(icon) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Image(
                        painterResource(mapIconDrawable(icon)),
                        contentDescription = mapIconLabel(icon),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        mapIconLabel(icon),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            "Drawn where you are, on the phone map and on the car screen. " +
                "Vehicles turn to face the way you're heading.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Route line colour. Same horizontal strip as [MapIconSection], and for the
 *  same reason — a lazy grid inside this scrolling page needs a fixed height
 *  before it will lay out at all.
 *
 *  Each swatch is drawn in two halves, driven on the left and ahead on the
 *  right, because that is the pair the map actually uses: picking a colour
 *  also picks what the road behind you fades to, and the two are resolved
 *  against the current basemap (see [RouteColors]), so THEME previews as the
 *  amber or the blue it will really be. */
@Composable
private fun RouteColorSection(theme: Settings.Theme) {
    val routeColor by Settings.routeColor.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(theme)
    SettingsSection("Route line") {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Settings.RouteColor.entries.forEach { color ->
                val selected = color == routeColor
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .width(78.dp)
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            shape,
                        )
                        .clickable { Settings.setRouteColor(color) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp)),
                    ) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(hexColor(RouteColors.drivenHex(color, darkTheme))),
                        )
                        Spacer(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(hexColor(RouteColors.hex(color, darkTheme))),
                        )
                    }
                    Text(
                        RouteColors.label(color),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            "The line drawn to your destination, on the phone map and on the " +
                "car screen. While navigating, the part you have already driven " +
                "fades to the darker shade.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `#RRGGBB` from [RouteColors] as a Compose colour. */
private fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

@Composable
private fun MapSection() {
    val defaultZoom by Settings.defaultZoom.collectAsStateWithLifecycle()
    SettingsSection("Map") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Default zoom", style = MaterialTheme.typography.bodyLarge)
            Text(
                formatFixed(defaultZoom.toDouble(), 1, Settings.decimalSeparatorChar()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = defaultZoom,
            onValueChange = { Settings.setDefaultZoom(it) },
            valueRange = Settings.DEFAULT_ZOOM_MIN..Settings.DEFAULT_ZOOM_MAX,
        )
        Text(
            "Where the map sits while following you. It zooms out up to " +
                "two levels at speed and back in near a turn.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FogSection(context: Context) {
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()
    val shareFog by Settings.shareFog.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    SettingsSection("Fog of war") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Reveal radius", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${fogRadius.toInt()} m",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = fogRadius,
            onValueChange = { Settings.setFogRadiusMeters(it) },
            valueRange = 100f..500f,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share fog with friends", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Uncover the map together: your accepted friends see the " +
                        "roads you have driven, and you see theirs. Only friends " +
                        "who share back can see yours. Off, nobody sees either.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = shareFog,
                onCheckedChange = {
                    Settings.setShareFog(it)
                    // Tell the server now: leaving it to the next trip sync
                    // would keep serving traces after the switch went off.
                    SyncClient.syncQuietly()
                },
            )
        }
    }

    // Danger action at the bottom of its own spoke, same as before — just no
    // longer sharing a card with the rest of the fog settings.
    TextButton(onClick = { confirmReset = true }) {
        Text("Reset explored area", color = MaterialTheme.colorScheme.error)
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset explored area?",
            text = "All fog-of-war progress will be permanently deleted. Saved trips are kept.",
            confirmLabel = "Reset",
            onConfirm = { TraceStore.clear() },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * Broadcasts turn-by-turn state over BLE for an external display (e.g. a
 * handlebar-mounted screen). Needs BLUETOOTH_CONNECT
 * (Android 12+ split BLUETOOTH into scoped runtime permissions) and
 * BLUETOOTH_ADVERTISE to advertise the phone as a connectable peripheral.
 */
@Composable
private fun ExternalDisplaySection() {
    val context = LocalContext.current
    val enabled by Settings.externalDisplayEnabled.collectAsStateWithLifecycle()
    var hasPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_ADVERTISE,
                    ) == PackageManager.PERMISSION_GRANTED),
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasPerm = results.values.all { it }
        if (hasPerm) {
            Settings.setExternalDisplayEnabled(true)
            BleNavServer.start(context)
        }
    }

    SettingsSection("External display") {
        Text(
            "Broadcast turn-by-turn over Bluetooth Low Energy for a handlebar-mounted " +
                "screen — turn, distance, speed, speed limit, road name, and remaining " +
                "distance/ETA.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPerm) {
            TextButton(onClick = {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    ),
                )
            }) { Text("Allow Bluetooth") }
            return@SettingsSection
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Broadcast to external display", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    Settings.setExternalDisplayEnabled(it)
                    if (it) BleNavServer.start(context) else BleNavServer.stop(context)
                },
            )
        }
    }
}

/**
 * Relays now-playing (title, artist, position, playback state) to the same
 * external display, sourced from [com.jellemax.detour.media.MediaListenerService].
 *
 * Unlike Bluetooth, this can't be requested as a runtime permission dialog —
 * "notification access" is an app-ops grant the user has to flip in system
 * Settings themselves. [NotificationManagerCompat.getEnabledListenerPackages]
 * is the only way to check whether it's already on; there's no callback for
 * when it changes, so the check re-runs on every recomposition after
 * returning from Settings ([ON_RESUME]).
 */
@Composable
private fun NowPlayingSection() {
    val context = LocalContext.current
    var hasAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName),
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSection("Now playing on external display") {
        Text(
            "Relay title, artist, and playback position from whatever's playing " +
                "(Spotify, etc.) to the handlebar display. Reads media sessions only, " +
                "never notification content — Android requires notification access to " +
                "do either, so the permission name is broader than what's actually used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasAccess) {
            TextButton(onClick = {
                // Fully qualified: this file already imports the app's own
                // Settings object, which would otherwise shadow the platform one.
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                )
            }) { Text("Allow notification access") }
            return@SettingsSection
        }
        Text(
            "Enabled. Also turn on \"Broadcast to external display\" above — music " +
                "shares that Bluetooth connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Map paired Bluetooth (Classic) devices to a vehicle. When one connects, the
 * tracking service logs the trip under that vehicle — a Cardo for the moto,
 * the car's infotainment for driving. No scanning, so it needs
 * BLUETOOTH_CONNECT but never location.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleSection() {
    val context = LocalContext.current
    val mapping by Settings.vehicleDevices.collectAsStateWithLifecycle()
    var hasPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPerm = granted
        if (granted) TripTrackingService.refresh(context)
    }
    val bonded = remember(hasPerm) {
        if (!hasPerm) emptyList()
        else try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.bondedDevices
                ?.sortedBy { runCatching { it.name }.getOrNull() ?: it.address }
                ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    // Which mode's "add device" picker is open, if any.
    var addTarget by remember { mutableStateOf<TravelMode?>(null) }
    // Which device's removal is waiting to be confirmed, by address.
    var removing by remember { mutableStateOf<String?>(null) }
    // Which vehicle's rename dialog is open, by address.
    var renaming by remember { mutableStateOf<String?>(null) }
    // A device picked from the "add" list, waiting for the rider to name it, by address.
    var naming by remember { mutableStateOf<String?>(null) }

    SettingsSection("Vehicles") {
        Text(
            "Add a Bluetooth device to a vehicle. When it's connected, trips log " +
                "under that vehicle automatically. With nothing connected, a trip " +
                "that never picks up real driving pace is dropped rather than saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPerm) {
            Text(
                "Grant Bluetooth access to add your paired devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }) { Text("Allow Bluetooth") }
            return@SettingsSection
        }
        TravelMode.entries.forEach { mode ->
            val devices = mapping.values.filter { it.mode == mode }.sortedBy { it.displayName }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mode.label, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold)
                TextButton(onClick = { addTarget = mode }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add device")
                }
            }
            if (devices.isEmpty()) {
                Text("No devices", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                devices.forEach { d ->
                    // The Bluetooth device's own name: for a pre-label entry
                    // migrated from the old format the address was stored as the
                    // name, so resolve the real one from the paired list. Shown
                    // as the sub-line whenever the rider has set a custom name.
                    val deviceName = if (d.name != d.address) d.name
                        else bonded.firstOrNull { it.address == d.address }
                            ?.let { runCatching { it.name }.getOrNull() } ?: d.address
                    val display = d.label?.takeIf { it.isNotBlank() } ?: deviceName
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { renaming = d.address },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(display, style = MaterialTheme.typography.bodyMedium)
                            if (d.label != null) {
                                Text(deviceName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(
                            onClick = { renaming = d.address },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rename $display",
                                Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { removing = d.address },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove $display",
                                Modifier.size(18.dp))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Share \"$display\" with your convoy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = d.shareName,
                            onCheckedChange = { Settings.setVehicleShareName(d.address, it) },
                        )
                    }
                    if (removing == d.address) {
                        ConfirmDialog(
                            title = "Remove $display?",
                            text = "Trips stop logging as ${mode.label} when this device " +
                                "connects. You can add it back from the paired list.",
                            confirmLabel = "Remove",
                            onConfirm = {
                                Settings.removeVehicleDevice(d.address)
                                TripTrackingService.refresh(context)
                            },
                            onDismiss = { removing = null },
                        )
                    }
                    if (renaming == d.address) {
                        VehicleNameDialog(
                            title = "Rename vehicle",
                            initial = display,
                            confirmLabel = "Save",
                            onConfirm = {
                                // Equal to the device name = no custom name; store
                                // blank so the entry falls back and the sub-line hides.
                                Settings.setVehicleLabel(
                                    d.address,
                                    it.takeIf { s -> s.trim() != deviceName } ?: "",
                                )
                                renaming = null
                            },
                            onDismiss = { renaming = null },
                        )
                    }
                }
            }
        }
    }

    addTarget?.let { mode ->
        val pending = naming
        if (pending != null) {
            val deviceName = bonded.firstOrNull { it.address == pending }
                ?.let { runCatching { it.name }.getOrNull() } ?: pending
            VehicleNameDialog(
                title = "Name this ${mode.label}",
                initial = deviceName,
                confirmLabel = "Add",
                onConfirm = {
                    Settings.addVehicleDevice(
                        pending, deviceName, mode,
                        label = it.takeIf { s -> s.trim() != deviceName },
                    )
                    TripTrackingService.refresh(context)
                    naming = null
                    addTarget = null
                },
                onDismiss = { naming = null },
            )
        } else {
            val unassigned = bonded.filter { !mapping.containsKey(it.address) }
            AlertDialog(
                onDismissRequest = { addTarget = null },
                title = { Text("Add a ${mode.label} device") },
                text = {
                    if (unassigned.isEmpty()) {
                        Text("No unassigned paired devices. Pair the device in Android's " +
                            "Bluetooth settings first, or remove it from another vehicle.")
                    } else {
                        Column {
                            unassigned.forEach { device ->
                                val address = device.address
                                val name = runCatching { device.name }.getOrNull() ?: address
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { naming = address }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { addTarget = null }) { Text("Close") }
                },
            )
        }
    }
}

/** A single-field dialog for naming or renaming a vehicle. Blank is allowed
 *  and clears the name back to the Bluetooth device's own; the field is capped
 *  at [Settings.VEHICLE_LABEL_MAX]. */
@Composable
private fun VehicleNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Settings.VEHICLE_LABEL_MAX) name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Shown in your history and to your convoy. Leave blank to use the " +
                        "device name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Corrects for a handlebar mount that isn't perfectly plumb with the bike:
 * left uncalibrated, that tilt adds a constant offset to every lean reading
 * (a rider going dead straight would see a nonzero lean). Sampled with the
 * bike upright and the engine off — it's a fixed mechanical misalignment
 * between phone and bike, not something that needs capturing on the move.
 */
@Composable
private fun LeanCalibrationSection() {
    val context = LocalContext.current
    val offsetDeg by Settings.leanOffsetDeg.collectAsStateWithLifecycle()
    var calibrating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    if (calibrating) {
        LaunchedEffect(Unit) {
            val sensorManager = context.getSystemService(SensorManager::class.java)
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sensor == null) {
                status = "No rotation sensor on this phone"
                calibrating = false
                return@LaunchedEffect
            }
            val samples = mutableListOf<Double>()
            val rotationMatrix = FloatArray(9)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    // Same formula as TripTrackingService's sensorListener —
                    // raw, uncorrected angle; that's what we're solving for.
                    val upX = -rotationMatrix[6]
                    val upY = rotationMatrix[7]
                    samples += Math.toDegrees(atan2(upX, upY).toDouble())
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            delay(2000)
            sensorManager.unregisterListener(listener)
            status = if (samples.isNotEmpty()) {
                val avg = samples.average()
                Settings.setLeanOffsetDeg(avg.toFloat())
                "Calibrated: offset ${formatFixed(avg, 1, Settings.decimalSeparatorChar())}°"
            } else {
                "No readings — try again"
            }
            calibrating = false
        }
    }

    SettingsSection("Vehicle mounting") {
        Text(
            "Corrects for a mount that isn't perfectly upright on the " +
                "handlebar, so straight-line riding reads as 0° lean. " +
                "Sit the bike upright on its wheels, engine off, phone " +
                "in its normal mount, then calibrate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Current offset: ${formatFixed(offsetDeg.toDouble(), 1, Settings.decimalSeparatorChar())}°",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !calibrating,
                onClick = { status = null; calibrating = true },
            ) { Text(if (calibrating) "Calibrating…" else "Calibrate") }
            if (offsetDeg != 0f) {
                TextButton(onClick = {
                    Settings.setLeanOffsetDeg(0f)
                    status = null
                }) { Text("Reset") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** internal, not private: SettingsDiagnostics.kt is a second file in this
 *  package holding a section, because this one is already past the 1000-line
 *  limit. Duplicating the card there would let the two drift.
 *
 *  The header sits above the card, not inside it, so a settings group reads the
 *  same as the You screen's RIDES group — one design, one definition. The title
 *  is uppercased here rather than at all 16 call sites. */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title.uppercase())
        ListCard {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}
