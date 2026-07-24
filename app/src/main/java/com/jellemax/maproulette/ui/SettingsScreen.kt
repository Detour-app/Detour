package com.jellemax.maproulette.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.BuildConfig
import com.jellemax.maproulette.ble.BleNavServer
import com.jellemax.maproulette.data.TravelMode
import com.jellemax.maproulette.data.ConfigFile
import com.jellemax.maproulette.data.RoutingServer
import com.jellemax.maproulette.data.ServerConfig
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TraceStore
import com.jellemax.maproulette.tracking.TripTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val theme by Settings.theme.collectAsStateWithLifecycle()
    val autoDetect by Settings.autoDetectDrives.collectAsStateWithLifecycle()
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val avoidSmallRoads by Settings.avoidSmallRoads.collectAsStateWithLifecycle()
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()
    val shareFog by Settings.shareFog.collectAsStateWithLifecycle()
    val defaultZoom by Settings.defaultZoom.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                    )
                }
            }

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

            VehicleSection()

            LeanCalibrationSection()

            SettingsSection("Navigation") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Avoid highways", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "In-app navigation skips motorways (car mode; " +
                                "moto and bike never use them)",
                            style = MaterialTheme.typography.bodySmall,
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
                        )
                    }
                    Switch(
                        checked = avoidSmallRoads,
                        onCheckedChange = { Settings.setAvoidSmallRoads(it) },
                    )
                }
            }

            ExternalDisplaySection()
            NowPlayingSection()

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
                )
            }

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
                        )
                    }
                    Switch(
                        checked = shareFog,
                        onCheckedChange = {
                            Settings.setShareFog(it)
                            // Tell the server now: leaving it to the next trip sync
                            // would keep serving traces after the switch went off.
                            SyncClient.syncQuietly(context)
                        },
                    )
                }
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset explored area", color = MaterialTheme.colorScheme.error)
                }
            }

            ServerSection()

            SyncSection()

            ConfigFileSection()

            Text(
                "Map Roulette v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset explored area?") },
            text = { Text("All fog-of-war progress will be permanently deleted. Saved trips are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    TraceStore.clear(context)
                    confirmReset = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/** Backup sync with the owner's server (see server/SYNC_SETUP_GUIDE.md). */
@Composable
private fun SyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncUrl by Settings.syncUrl.collectAsStateWithLifecycle()
    var urlField by remember { mutableStateOf(syncUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val signedInAs by Settings.authUsername.collectAsStateWithLifecycle()

    SettingsSection("Backup sync") {
        Text(
            "Trips, explored area and badges are merged with your server after " +
                "every trip and on app start, so a reinstall restores everything. " +
                "Uses the routing server's Cloudflare Access credentials.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (signedInAs.isBlank()) "Not signed in — open Friends to create an account."
            else "Signed in as $signedInAs",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = urlField, onValueChange = { urlField = it },
            label = { Text("Sync server URL") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                Settings.setSyncUrl(urlField)
                status = "Saved"
            }) { Text("Save") }
            TextButton(
                enabled = !syncing && SyncClient.configured && signedInAs.isNotBlank(),
                onClick = {
                    syncing = true
                    status = "Syncing…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                val r = SyncClient.sync(context)
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
            "Save the routing server, its Cloudflare credentials, the sync " +
                "server and your sign-in to a file. After a reinstall, import " +
                "it instead of typing everything again.",
            style = MaterialTheme.typography.bodySmall,
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
 * external display, sourced from [com.jellemax.maproulette.media.MediaListenerService].
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
        )
    }
}

/**
 * Map paired Bluetooth (Classic) devices to a vehicle. When one connects, the
 * tracking service logs the trip under that vehicle — a Cardo for the moto, the
 * car's infotainment for driving, walking earbuds for a walk. No scanning, so
 * it needs BLUETOOTH_CONNECT but never location.
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
                "under that vehicle automatically — and a walking device (or no " +
                "connection at a walking pace) logs as a walk.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!hasPerm) {
            Text(
                "Grant Bluetooth access to add your paired devices.",
                style = MaterialTheme.typography.bodySmall,
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
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
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
                            Icon(Icons.Default.Close, contentDescription = "Remove ${d.name}",
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
    val custom = remember { RoutingServer.loadCustom(context) }
    val builtInAvailable = remember { RoutingServer.bakedDefaults().usable }
    var url by remember { mutableStateOf(custom?.url ?: "") }
    var clientId by remember { mutableStateOf(custom?.clientId ?: "") }
    var clientSecret by remember { mutableStateOf(custom?.clientSecret ?: "") }
    var geocoderUrl by remember { mutableStateOf(Settings.geocoderUrl.value) }
    var saved by remember { mutableStateOf(false) }

    SettingsSection("Routing server") {
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
            "Optional: your own GraphHopper server for spins and round " +
                "trips. Leave empty to use the built-in one. Falls back " +
                "to public servers when unreachable.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it; saved = false },
            label = { Text("Server URL") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientId, onValueChange = { clientId = it; saved = false },
            label = { Text("CF Access Client Id (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret, onValueChange = { clientSecret = it; saved = false },
            label = { Text("CF Access Client Secret (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Optional: your own Photon geocoder for address/place search. " +
                "Leave empty to use the public one. Reuses the Cloudflare " +
                "Access credentials above.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = geocoderUrl, onValueChange = { geocoderUrl = it; saved = false },
            label = { Text("Search server URL (optional)") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (url.isBlank()) {
                    RoutingServer.clearCustom(context)
                } else {
                    RoutingServer.save(
                        context, ServerConfig(url, clientId, clientSecret, enabled = true))
                }
                Settings.setGeocoderUrl(geocoderUrl)
                saved = true
            }) { Text(if (saved) "Saved ✓" else "Save server") }
            if (custom != null) {
                TextButton(onClick = {
                    RoutingServer.clearCustom(context)
                    Settings.setGeocoderUrl("")
                    url = ""; clientId = ""; clientSecret = ""; geocoderUrl = ""
                    saved = true
                }) { Text("Remove custom server") }
            }
        }
    }
}
