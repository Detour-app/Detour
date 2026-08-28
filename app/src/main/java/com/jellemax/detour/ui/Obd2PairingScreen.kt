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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Settings
import com.jellemax.detour.obd2.Obd2Connection
import com.jellemax.detour.obd2.Obd2ConnectionState

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
    val tachOnHud by Settings.obd2TachOnHud.collectAsStateWithLifecycle()

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
        // Only worth offering once an adapter is paired — with none, the tach
        // could never draw.
        if (mapping.values.any { it.obd2Address != null }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show RPM bar on the HUD", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A thin engine-rev bar under the speed dial while driving, when " +
                            "the adapter is feeding RPM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = tachOnHud,
                    onCheckedChange = { Settings.setObd2TachOnHud(it) },
                )
            }
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
                                Obd2Connection.connect(context.applicationContext, device.address)
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
