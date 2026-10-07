package com.jellemax.detour.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
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

    // Which vehicle's adapter is waiting on a confirmed "Forget", by the
    // vehicle's own device address.
    var forgetting by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.settings_obd2_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasPerm) {
            OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text(stringResource(R.string.settings_allow_bluetooth))
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
            stringResource(
                R.string.settings_obd2_link,
                stringResource(
                    when (connectionState) {
                        Obd2ConnectionState.DISCONNECTED -> R.string.settings_obd2_state_disconnected
                        Obd2ConnectionState.CONNECTING -> R.string.settings_obd2_state_connecting
                        Obd2ConnectionState.CONNECTED -> R.string.settings_obd2_state_connected
                        Obd2ConnectionState.FAILED -> R.string.settings_obd2_state_failed
                    },
                ),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (connectionState == Obd2ConnectionState.CONNECTED) {
            telemetry?.let { t ->
                Text(
                    listOfNotNull(
                        if (t.hasSpeed) stringResource(R.string.settings_obd2_speed, t.speedKmh.toInt()) else null,
                        if (t.hasThrottle) {
                            stringResource(R.string.settings_obd2_throttle, t.throttlePct.toInt())
                        } else {
                            null
                        },
                        if (t.hasRpm) stringResource(R.string.settings_obd2_rpm, t.rpmValue.toInt()) else null,
                    ).joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (connectionState == Obd2ConnectionState.FAILED) {
            obd2FailureText(lastFailure)?.let { reason ->
                Text(stringResource(reason), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        lastDataAtMs?.let { at ->
            val secs = ((nowMs - at) / 1_000L).coerceAtLeast(0)
            Text(
                if (secs < 60) {
                    stringResource(R.string.settings_obd2_last_data_s, secs)
                } else {
                    stringResource(R.string.settings_obd2_last_data_m, secs / 60)
                },
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
            }) { Text(stringResource(R.string.settings_obd2_retry)) }
        }
        // Every address already spoken for — as some vehicle's own auto-detect
        // device, or as any vehicle's paired OBD2 adapter — is off-limits here.
        // Otherwise picking one (e.g. another vehicle's Cardo/infotainment unit)
        // wires an OBD2 connection loop onto a device that's also driving trip
        // auto-detection for its real vehicle.
        val taken = mapping.keys + mapping.values.mapNotNull { it.obd2Address }
        mapping.values.sortedBy { it.displayName }.forEach { vehicle ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(vehicle.displayName, style = MaterialTheme.typography.bodyLarge)
                val pairedName = vehicle.obd2Address?.let { addr ->
                    bonded.firstOrNull { it.address == addr }
                        ?.let { runCatching { it.name }.getOrNull() } ?: addr
                }
                if (pairedName == null) {
                    val unassigned = bonded.filter { it.address !in taken }
                    if (unassigned.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_obd2_no_other),
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
                            }) { Text(stringResource(R.string.settings_obd2_use, name)) }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.settings_obd2_adapter, pairedName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Outlined, not filled: unpairing was the loudest button on
                        // this screen while every constructive action beside it was
                        // outlined.
                        OutlinedButton(onClick = { forgetting = vehicle.address }) {
                            Text(stringResource(R.string.settings_obd2_forget))
                        }
                    }
                    if (forgetting == vehicle.address) {
                        ConfirmDialog(
                            title = stringResource(R.string.settings_obd2_forget_title, pairedName),
                            text = stringResource(R.string.settings_obd2_forget_text, vehicle.displayName),
                            confirmLabel = stringResource(R.string.settings_obd2_forget),
                            onConfirm = {
                                Settings.setObd2Address(vehicle.address, null)
                                // Otherwise a connection to a now-unpaired device lingers
                                // until the next ACL event or service restart.
                                Obd2Connection.disconnect()
                            },
                            onDismiss = { forgetting = null },
                        )
                    }
                    // Fuel type + calibration only matter for the MAF estimate,
                    // and only once an adapter is paired.
                    val fuelTypes = FuelType.entries
                    ChoiceRow(
                        options = fuelTypes.map { ft ->
                            stringResource(
                                when (ft) {
                                    FuelType.PETROL -> R.string.settings_obd2_fuel_petrol
                                    FuelType.DIESEL -> R.string.settings_obd2_fuel_diesel
                                },
                            )
                        },
                        selectedIndex = fuelTypes.indexOf(vehicle.fuelType),
                        onSelect = { index ->
                            val ft = fuelTypes[index]
                            Settings.setFuelType(vehicle.address, ft)
                            vehicle.obd2Address?.let { addr ->
                                Obd2Connection.disconnect()
                                Obd2Connection.connect(
                                    context.applicationContext, addr,
                                    fuelType = ft, calibrationPct = vehicle.fuelCalibrationPct,
                                )
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_obd2_calibration, vehicle.fuelCalibrationPct),
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
                        stringResource(R.string.settings_obd2_calibration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (mapping.isEmpty()) {
            Text(
                stringResource(R.string.settings_obd2_add_vehicle_first),
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

/** Plain-words reason for a FAILED link, as a string resource. Null for
 *  [Obd2Failure.NONE] — nothing to show. */
@StringRes
internal fun obd2FailureText(failure: Obd2Failure): Int? = when (failure) {
    Obd2Failure.NONE -> null
    Obd2Failure.ADAPTER_UNAVAILABLE -> R.string.settings_obd2_fail_unavailable
    Obd2Failure.PERMISSION_DENIED -> R.string.settings_obd2_fail_permission
    Obd2Failure.HANDSHAKE_TIMEOUT -> R.string.settings_obd2_fail_handshake
    Obd2Failure.NO_DATA -> R.string.settings_obd2_fail_no_data
    Obd2Failure.SOCKET_ERROR -> R.string.settings_obd2_fail_socket
}
