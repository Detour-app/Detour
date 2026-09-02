package com.jellemax.detour.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Settings
import com.jellemax.detour.drive.FuelType
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.Obd2ConnectionState
import com.jellemax.detour.obd2.Obd2Failure
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.delay

/**
 * Pair a bonded Bluetooth device as a vehicle's OBD2 adapter, and show a live
 * connection state + reading — the only on-screen way to confirm "this
 * adapter actually works" without a road test (maxke24/Detour#62). A
 * dedicated page rather than folded into [VehicleSection] since a vehicle's
 * OBD2 adapter is a distinct device from its auto-detect [Settings.VehicleDevice.address].
 */
@Composable
fun Obd2PairingScreen() {
    val context = LocalContext.current
    val mapping by Settings.vehicleDevices.collectAsStateWithLifecycle()
    val connectionState by Obd2Connection.connectionState.collectAsStateWithLifecycle()
    val telemetry by Obd2Connection.telemetry.collectAsStateWithLifecycle()
    val lastFailure by Obd2Connection.lastFailure.collectAsStateWithLifecycle()
    val lastDataAtMs by Obd2Connection.lastDataAtMs.collectAsStateWithLifecycle()
    val linkedAddress by Obd2Connection.linkedAddress.collectAsStateWithLifecycle()

    // 1s tick so "last data Ns ago" keeps counting up after the adapter drops
    // (telemetry stops emitting then, so nothing else would recompose this).
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // The service only holds the OBD2 link during a trip or with the map up
    // (see reconcileObd2Connections); this screen is neither, so open the
    // readout link ourselves while it is on screen. `readoutAddress` is the
    // same adapter the "Retry now" button targets. On exit, hand back to the
    // service's reconciler; only disconnect directly when nothing wants it.
    val readoutAddress = mapping.values.firstNotNullOfOrNull { it.obd2Address }
    DisposableEffect(readoutAddress) {
        if (readoutAddress != null && Obd2Connection.linkedAddress.value == null) {
            val v = mapping.values.firstOrNull { it.obd2Address == readoutAddress }
            Obd2Connection.connect(
                context.applicationContext, readoutAddress,
                fuelType = v?.fuelType ?: FuelType.PETROL,
                calibrationPct = v?.fuelCalibrationPct ?: 100,
            )
        }
        onDispose {
            // Hand back to the service's reconciler (ACTION_REFRESH ->
            // reconcileObd2Connections): it keeps the link if a trip or the
            // map still wants it, switches it, or drops it — correct whatever
            // address the assign/forget buttons last left linked. Only when
            // the service wants nothing do we tear down here directly.
            if (TripTrackingService.obdWantedByService()) {
                TripTrackingService.refresh(context)
            } else {
                Obd2Connection.disconnect()
            }
        }
    }

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
    ) { granted -> hasPerm = granted }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Pair a vehicle's OBD2 adapter (a Bluetooth ELM327 dongle plugged into the " +
                "port) for accurate speed instead of GPS. Not a score to chase, not new " +
                "history — just a more accurate speed reading while it's connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPerm) {
            OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Allow Bluetooth")
            }
            return@Column
        }
        val bonded = remember(hasPerm) {
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?.bondedDevices
                    ?.sortedBy { runCatching { it.name }.getOrNull() ?: it.address }
                    ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }
        }
        // Obd2Connection tracks a single process-wide link with no per-vehicle
        // identity, so its status/telemetry can't honestly be attributed to any
        // one vehicle below — render it once, here, rather than duplicating an
        // identical (and for all-but-one vehicle, wrong) readout per row.
        Text(
            "Adapter link: " +
                connectionState.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (connectionState == Obd2ConnectionState.CONNECTED) {
            telemetry?.let { t ->
                Text(
                    buildString {
                        if (t.hasSpeed) append("Speed: ${t.speedKmh.toInt()} km/h  ")
                        if (t.hasThrottle) append("Throttle: ${t.throttlePct.toInt()}%  ")
                        if (t.hasRpm) append("RPM: ${t.rpmValue.toInt()}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (connectionState == Obd2ConnectionState.FAILED) {
            obd2FailureText(lastFailure)?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        lastDataAtMs?.let { at ->
            val secs = ((nowMs - at) / 1_000L).coerceAtLeast(0)
            Text(
                if (secs < 60) "Last data: ${secs}s ago" else "Last data: ${secs / 60}m ago",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val retryAddress = linkedAddress ?: mapping.values.firstNotNullOfOrNull { it.obd2Address }
        if (retryAddress != null &&
            (connectionState == Obd2ConnectionState.FAILED ||
                connectionState == Obd2ConnectionState.DISCONNECTED)
        ) {
            OutlinedButton(onClick = {
                Obd2Connection.disconnect()
                val v = mapping.values.firstOrNull { it.obd2Address == retryAddress }
                Obd2Connection.connect(
                    context.applicationContext, retryAddress,
                    fuelType = v?.fuelType ?: FuelType.PETROL,
                    calibrationPct = v?.fuelCalibrationPct ?: 100,
                )
            }) { Text("Retry now") }
        }
        // Every address already spoken for — as some vehicle's own auto-detect
        // device, or as any vehicle's paired OBD2 adapter — is off-limits here.
        // Otherwise picking one (e.g. another vehicle's Cardo/infotainment unit)
        // wires an OBD2 connection loop onto a device that's also driving trip
        // auto-detection for its real vehicle.
        val taken = mapping.keys + mapping.values.mapNotNull { it.obd2Address }
        mapping.values.sortedBy { it.name }.forEach { vehicle ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(vehicle.name, style = MaterialTheme.typography.bodyLarge)
                val pairedName = vehicle.obd2Address?.let { addr ->
                    bonded.firstOrNull { it.address == addr }
                        ?.let { runCatching { it.name }.getOrNull() } ?: addr
                }
                if (pairedName == null) {
                    val unassigned = bonded.filter { it.address !in taken }
                    if (unassigned.isEmpty()) {
                        Text(
                            "No other paired devices to use as an OBD2 adapter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        unassigned.forEach { device ->
                            val name = runCatching { device.name }.getOrNull() ?: device.address
                            OutlinedButton(onClick = {
                                Settings.setObd2Address(vehicle.address, device.address)
                                // Obd2Connection.connect() no-ops while a job is already
                                // active, so a second pairing while a prior device's
                                // connection loop is still running would otherwise
                                // silently do nothing. Force a fresh attempt for the
                                // newly-selected device.
                                Obd2Connection.disconnect()
                                Obd2Connection.connect(
                                    context.applicationContext, device.address,
                                    fuelType = vehicle.fuelType,
                                    calibrationPct = vehicle.fuelCalibrationPct,
                                )
                            }) { Text("Use $name") }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Adapter: $pairedName", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            Settings.setObd2Address(vehicle.address, null)
                            // Otherwise a connection to a now-unpaired device lingers
                            // until the next ACL event or service restart.
                            Obd2Connection.disconnect()
                        }) {
                            Text("Forget")
                        }
                    }
                    // Fuel type + calibration only matter for the MAF estimate,
                    // and only once an adapter is paired.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FuelType.entries.forEachIndexed { index, ft ->
                            SegmentedButton(
                                selected = vehicle.fuelType == ft,
                                onClick = {
                                    Settings.setFuelType(vehicle.address, ft)
                                    vehicle.obd2Address?.let { addr ->
                                        Obd2Connection.disconnect()
                                        Obd2Connection.connect(
                                            context.applicationContext, addr,
                                            fuelType = ft, calibrationPct = vehicle.fuelCalibrationPct,
                                        )
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, FuelType.entries.size),
                                label = { Text(ft.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Calibration: ${vehicle.fuelCalibrationPct}%",
                            style = MaterialTheme.typography.bodyMedium)
                        Row {
                            IconButton(
                                enabled = vehicle.fuelCalibrationPct > Settings.FUEL_CALIBRATION_MIN,
                                onClick = { adjustCalibration(vehicle, -1, context) },
                            ) { Text("−") }
                            IconButton(
                                enabled = vehicle.fuelCalibrationPct < Settings.FUEL_CALIBRATION_MAX,
                                onClick = { adjustCalibration(vehicle, +1, context) },
                            ) { Text("+") }
                        }
                    }
                    Text(
                        "If the trip fuel figure reads high or low against your car's own display, nudge this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (mapping.isEmpty()) {
            Text(
                "Add a vehicle under Tracking & vehicles first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun adjustCalibration(vehicle: Settings.VehicleDevice, delta: Int, context: android.content.Context) {
    val next = (vehicle.fuelCalibrationPct + delta)
        .coerceIn(Settings.FUEL_CALIBRATION_MIN, Settings.FUEL_CALIBRATION_MAX)
    if (next == vehicle.fuelCalibrationPct) return
    Settings.setFuelCalibrationPct(vehicle.address, next)
    vehicle.obd2Address?.let { addr ->
        Obd2Connection.disconnect()
        Obd2Connection.connect(context.applicationContext, addr, vehicle.fuelType, next)
    }
}

/** Plain-words reason for a FAILED link. Null for [Obd2Failure.NONE] — nothing
 *  to show. */
internal fun obd2FailureText(failure: Obd2Failure): String? = when (failure) {
    Obd2Failure.NONE -> null
    Obd2Failure.ADAPTER_UNAVAILABLE -> "Couldn't reach the adapter — check it's plugged in and Bluetooth is on."
    Obd2Failure.PERMISSION_DENIED -> "Bluetooth permission was denied."
    Obd2Failure.HANDSHAKE_TIMEOUT -> "The adapter didn't answer the handshake — a cheap clone may need a re-plug."
    Obd2Failure.NO_DATA -> "Connected, but the vehicle sent no data — try again with the ignition on."
    Obd2Failure.SOCKET_ERROR -> "Bluetooth connection error — moving out of range or the adapter reset."
}
