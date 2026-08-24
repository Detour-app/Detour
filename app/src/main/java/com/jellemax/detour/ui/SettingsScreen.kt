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
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.syncQuietly
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.ConfigFile
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2

/** The six spokes off the Settings root. Internal to this screen — not new
 *  MainActivity screens — so the same push/pop feel as Hub-and-back applies
 *  without adding another layer to the app-wide Screen enum. */
private enum class SettingsPage(val title: String) {
    ROOT("Settings"),
    APPEARANCE_MAP("Appearance & map"),
    TRACKING_VEHICLES("Tracking & vehicles"),
    NAVIGATION("Navigation"),
    FOG("Fog of war"),
    DISPLAYS_MEDIA("Displays & media"),
    SERVERS_SYNC("Servers & sync"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    // Back from a spoke returns to the root; only off the root does it fall
    // through to leaving the screen entirely (same shape as Hub vs. MAP).
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    val theme by Settings.theme.collectAsStateWithLifecycle()
    val autoDetect by Settings.autoDetectDrives.collectAsStateWithLifecycle()
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()
    val externalDisplayEnabled by Settings.externalDisplayEnabled.collectAsStateWithLifecycle()
    val authUsername by Settings.authUsername.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar(
                page.title,
                onBack = { if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT },
                scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (page == SettingsPage.ROOT) 10.dp else 16.dp),
        ) {
            when (page) {
                SettingsPage.ROOT -> {
                    HubRow(
                        icon = Icons.Outlined.Brightness6,
                        title = SettingsPage.APPEARANCE_MAP.title,
                        subtitle = theme.name.lowercase().replaceFirstChar { it.uppercase() } + " theme",
                        onClick = { page = SettingsPage.APPEARANCE_MAP },
                    )
                    HubRow(
                        icon = Icons.Outlined.DirectionsCar,
                        title = SettingsPage.TRACKING_VEHICLES.title,
                        subtitle = "Auto-detect drives: " + (if (autoDetect) "on" else "off"),
                        onClick = { page = SettingsPage.TRACKING_VEHICLES },
                    )
                    HubRow(
                        icon = Icons.Outlined.Navigation,
                        title = SettingsPage.NAVIGATION.title,
                        subtitle = "Avoid highways: " + (if (avoidHighways) "on" else "off"),
                        onClick = { page = SettingsPage.NAVIGATION },
                    )
                    HubRow(
                        icon = Icons.Outlined.VisibilityOff,
                        title = SettingsPage.FOG.title,
                        subtitle = "${fogRadius.toInt()} m reveal radius",
                        onClick = { page = SettingsPage.FOG },
                    )
                    HubRow(
                        icon = Icons.Outlined.Tv,
                        title = SettingsPage.DISPLAYS_MEDIA.title,
                        subtitle = "External display: " + (if (externalDisplayEnabled) "on" else "off"),
                        onClick = { page = SettingsPage.DISPLAYS_MEDIA },
                    )
                    HubRow(
                        icon = Icons.Outlined.Cloud,
                        title = SettingsPage.SERVERS_SYNC.title,
                        subtitle = if (authUsername.isBlank()) "Not signed in"
                            else "Signed in as $authUsername",
                        onClick = { page = SettingsPage.SERVERS_SYNC },
                    )
                    Text(
                        "Detour ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                SettingsPage.APPEARANCE_MAP -> {
                    AppearanceSection(theme)
                    MapIconSection()
                    RouteColorSection(theme)
                    MapSection()
                }
                SettingsPage.TRACKING_VEHICLES -> {
                    TrackingSection(autoDetect, context)
                    VehicleSection()
                    LeanCalibrationSection()
                }
                SettingsPage.NAVIGATION -> NavigationSection()
                SettingsPage.FOG -> FogSection(context)
                SettingsPage.DISPLAYS_MEDIA -> {
                    ExternalDisplaySection()
                    NowPlayingSection()
                }
                SettingsPage.SERVERS_SYNC -> {
                    ServerSection()
                    SyncSection()
                    ConfigFileSection()
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(theme: Settings.Theme) {
    SettingsSection("Appearance") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Settings.Theme.entries.forEachIndexed { index, t ->
                SegmentedButton(
                    selected = theme == t,
                    onClick = { Settings.setTheme(t) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index, count = Settings.Theme.entries.size,
                    ),
                    label = {
                        Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                    },
                )
            }
        }
        if (theme == Settings.Theme.AUTO) {
            Text(
                "Light by day, dark by night — follows sunrise and " +
                    "sunset at your location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                "%.1f".format(defaultZoom),
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
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset explored area?") },
            text = { Text("All fog-of-war progress will be permanently deleted. Saved trips are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    TraceStore.clear()
                    confirmReset = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/** Backup sync with the owner's server (see backend/README.md). */
@Composable
private fun SyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val signedInAs by Settings.authUsername.collectAsStateWithLifecycle()

    SettingsSection("Backup sync") {
        Text(
            "Trips, explored area and badges are merged with your server after " +
                "every trip and on app start, so a reinstall restores everything. " +
                "Uses the Server URL and Cloudflare Access credentials under " +
                "Server settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (signedInAs.isBlank()) "Not signed in — open Friends to create an account."
            else "Signed in as $signedInAs",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !syncing && SyncClient.configured() && signedInAs.isNotBlank(),
                onClick = {
                    syncing = true
                    status = "Syncing…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                val r = SyncClient.sync()
                                "Synced: ${r.trips} trips, ${r.traces} trace segments, " +
                                    "${r.badges} badges"
                            } catch (e: Exception) {
                                "Sync failed: ${e.message}"
                            }
                        }
                        syncing = false
                    }
                },
            ) { Text("Sync now") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Server settings to and from a file the user keeps outside the app.
 * Preferences die with an uninstall and the baked-in defaults only exist in
 * APKs built from a local.properties; this is what makes a reinstall a two-tap
 * restore instead of retyping a URL and two Cloudflare secrets.
 */
@Composable
private fun ConfigFileSection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigFile.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            ConfigFile.export(context, uri)
            "Config exported"
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            ConfigFile.import(context, uri)
            "Config imported — restart the app to use the new servers"
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }

    SettingsSection("Server config file") {
        Text(
            "Save the server URL, its Cloudflare credentials and your " +
                "sign-in to a file. After a reinstall, import it instead of " +
                "typing everything again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The file contains your sign-in token. Keep it somewhere private — " +
                "anyone holding it is signed in as you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                status = null
                exportLauncher.launch(ConfigFile.SUGGESTED_NAME)
            }) { Text("Export config") }
            TextButton(onClick = {
                status = null
                // Some file pickers hide application/json; */* keeps the file reachable.
                importLauncher.launch(arrayOf(ConfigFile.MIME_TYPE, "*/*"))
            }) { Text("Import config") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Broadcasts turn-by-turn state over BLE for an external display (e.g. a
 * handlebar-mounted screen), mirroring the Wear OS relay but over Bluetooth
 * Low Energy instead of the Wearable Message API. Needs BLUETOOTH_CONNECT
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
            val devices = mapping.values.filter { it.mode == mode }.sortedBy { it.name }
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
                    // Entries migrated from the old format stored the address as
                    // the name; resolve the real name from the paired list.
                    val display = if (d.name != d.address) d.name
                        else bonded.firstOrNull { it.address == d.address }
                            ?.let { runCatching { it.name }.getOrNull() } ?: d.address
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(display, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                Settings.removeVehicleDevice(d.address)
                                TripTrackingService.refresh(context)
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove ${d.name}",
                                Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    addTarget?.let { mode ->
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
                                    .clickable {
                                        Settings.addVehicleDevice(address, name, mode)
                                        TripTrackingService.refresh(context)
                                        addTarget = null
                                    }
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
                "Calibrated: offset %.1f°".format(avg)
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
            "Current offset: %.1f°".format(offsetDeg),
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

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/**
 * Settings for a custom GraphHopper server. Built-in defaults are never
 * displayed: empty fields mean the built-in server is used.
 */
@Composable
private fun ServerSection() {
    val context = LocalContext.current
    val custom = remember { RoutingServer.loadCustom() }
    val builtInAvailable = remember { RoutingServer.bakedDefaults().usable }
    var url by remember { mutableStateOf(custom?.url ?: "") }
    var apiUrl by remember { mutableStateOf(custom?.apiUrl ?: "") }
    var routingUrl by remember { mutableStateOf(custom?.routingUrl ?: "") }
    var geocoderUrl by remember { mutableStateOf(custom?.geocoderUrl ?: "") }
    var idpIssuer by remember { mutableStateOf(custom?.idpIssuer ?: "") }
    var clientId by remember { mutableStateOf(custom?.clientId ?: "") }
    var clientSecret by remember { mutableStateOf(custom?.clientSecret ?: "") }
    // Only the general address is shown by default: a rider on a one-hostname
    // deployment never needs the rest, and four more URL boxes read as four more
    // things that must be filled in. Opens already expanded when any of them is
    // set, so a split deployment does not look unconfigured on the way back in.
    var showPerService by remember {
        mutableStateOf(
            listOf(apiUrl, routingUrl, geocoderUrl).any { it.isNotBlank() },
        )
    }
    val geocoderPublicFallback by Settings.geocoderPublicFallback.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf(false) }

    SettingsSection("Server") {
        Text(
            when {
                custom != null -> "Custom server: ${custom.url}"
                builtInAvailable -> "Using built-in server"
                else -> "Public servers only"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Optional: one self-hosted address for routing, search, sync " +
                "and the convoy live relay (see the one-hostname layout in " +
                "one-hostname layout). Leave empty to use the built-in " +
                "routing/search servers, with sync and live off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CredentialTextField(
            value = url, onValueChange = { url = it; saved = false },
            label = "Server URL",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://…",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = idpIssuer, onValueChange = { idpIssuer = it; saved = false },
            label = "Sign-in realm URL",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://idp.example.com/realms/detour",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Your identity provider's realm, which issues the tokens the API " +
                "trusts. It has no default from the server address above — a realm " +
                "URL is never the same host — so signing in stays off until it is " +
                "filled in. Changing it signs this device out: tokens from one " +
                "realm mean nothing to another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showPerService = !showPerService }) {
            Text(if (showPerService) "Hide per-service addresses" else "Different address per service")
        }
        if (showPerService) {
            Text(
                "For a deployment split across hostnames. Anything left empty " +
                    "uses the server address above. Routing and search cannot " +
                    "share one host with sync, because the API answers /api/trips " +
                    "and the search server answers /api/ — so one address cannot " +
                    "serve both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CredentialTextField(
                value = apiUrl, onValueChange = { apiUrl = it; saved = false },
                label = "Sync & social API (optional)",
                keyboardType = KeyboardType.Uri,
                placeholder = "https://api.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
            CredentialTextField(
                value = routingUrl, onValueChange = { routingUrl = it; saved = false },
                label = "Routing server (optional)",
                keyboardType = KeyboardType.Uri,
                placeholder = "https://route.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
            CredentialTextField(
                value = geocoderUrl, onValueChange = { geocoderUrl = it; saved = false },
                label = "Search server (optional)",
                keyboardType = KeyboardType.Uri,
                placeholder = "https://search.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CredentialTextField(
            value = clientId, onValueChange = { clientId = it; saved = false },
            label = "CF Access Client Id (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        SecretTextField(
            value = clientSecret, onValueChange = { clientSecret = it; saved = false },
            label = "CF Access Client Secret (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Fall back to public search", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "If your search server is unreachable, retry via the public " +
                        "Photon instance (komoot.io) — sends the query and your " +
                        "approximate location off your own hardware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = geocoderPublicFallback,
                onCheckedChange = { Settings.setGeocoderPublicFallback(it) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                // Named arguments, not positional: ServerConfig's address fields
                // are all String and sit next to each other, so a positional call
                // that drifts out of order still compiles and quietly saves the
                // client id as an API address.
                val addresses = listOf(url, apiUrl, routingUrl, geocoderUrl, idpIssuer)
                if (addresses.all { it.isBlank() }) {
                    RoutingServer.clearCustom()
                } else {
                    RoutingServer.save(
                        ServerConfig(
                            url = url,
                            apiUrl = apiUrl,
                            routingUrl = routingUrl,
                            geocoderUrl = geocoderUrl,
                            idpIssuer = idpIssuer,
                            clientId = clientId,
                            clientSecret = clientSecret,
                            enabled = true,
                        ),
                    )
                }
                saved = true
            }) { Text(if (saved) "Saved ✓" else "Save server") }
            if (custom != null) {
                TextButton(onClick = {
                    RoutingServer.clearCustom()
                    url = ""; apiUrl = ""; routingUrl = ""; geocoderUrl = ""
                    idpIssuer = ""; clientId = ""; clientSecret = ""
                    saved = true
                }) { Text("Remove custom server") }
            }
        }
    }
}
