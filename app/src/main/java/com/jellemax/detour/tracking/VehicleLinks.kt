package com.jellemax.detour.tracking

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.drive.FuelType
import com.jellemax.detour.obd2.Obd2Connection

/**
 * Bluetooth vehicle auto-detect and OBD2 link reconciliation, split out of
 * [TripTrackingService]: which mapped device is connected decides how the
 * running trip is tagged, and which OBD2 adapter [Obd2Connection] should be
 * dialled.
 *
 * This class owns none of the service's trip/UI state — [uiVisible] and
 * [currentTripMode] read it fresh from the service on every call, and
 * [onModeChanged] is how a resolved mode change reaches back into the
 * service to actually retag the running trip (update the stats, restart
 * motion sensors, refresh the notification), since that mutation stays the
 * service's own.
 */
class VehicleLinks(
    private val context: Context,
    private val modePriority: List<TravelMode>,
    private val uiVisible: () -> Boolean,
    private val currentTripMode: () -> TravelMode?,
    private val onModeChanged: (TravelMode) -> Unit,
) {

    // Mapped Classic devices (Cardo, car infotainment) pick the trip mode,
    // falling back to the default when none is connected. Addresses of
    // currently-connected mapped devices.
    private val connectedVehicles = LinkedHashSet<String>()
    private var registered = false

    /** Set in [stop] before teardown so a late [reconcileObd2Connections]
     *  (reached through the service's own endTrip, which can still be
     *  running as the service dies) can't re-dial an adapter into a
     *  connection nothing is left to own. */
    @Volatile private var destroyed = false

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceFrom(intent) ?: return
            val address = try { device.address } catch (e: SecurityException) { return } ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (Settings.vehicleDevices.value.containsKey(address)) {
                        connectedVehicles.remove(address) // move to newest
                        connectedVehicles.add(address)
                        refreshTripMode()
                    }
                    reconcileObd2Connections()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (connectedVehicles.remove(address)) refreshTripMode()
                    reconcileObd2Connections()
                }
            }
        }
    }

    /** Turning the adapter off drops every link without an ACL_DISCONNECTED per
     *  device, so without this the car stays "connected" for the rest of the
     *  service's life and the next ride is logged as a drive. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (connectedVehicles.isNotEmpty()) {
                        connectedVehicles.clear()
                        refreshTripMode()
                    }
                    Obd2Connection.disconnect()
                }
                BluetoothAdapter.STATE_ON -> {
                    seedConnectedVehicles()
                    // STATE_OFF called Obd2Connection.disconnect(); nothing
                    // re-dials a phone-initiated SPP link on its own. Reconcile
                    // picks it back up if a trip or the UI still wants it.
                    reconcileObd2Connections()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** True when we're allowed to touch bonded devices/connection state. Below
     *  API 31 the normal BLUETOOTH permission is granted at install. */
    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Register the connect/disconnect watcher once, and seed it with whatever
     *  is already connected (so it works if the app opens mid-drive). No-op
     *  until permission is granted; retried on the next service command. */
    fun start() {
        if (registered || !hasBtPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, btReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            context,
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        seedConnectedVehicles()
        reconcileObd2Connections()
    }

    /** Unregister the watcher and drop any OBD2 link. Called from
     *  [TripTrackingService.onDestroy] before the rest of its teardown, so
     *  nothing after it can resurrect a link with the service gone. */
    fun stop() {
        destroyed = true
        if (registered) {
            runCatching { context.unregisterReceiver(btReceiver) }
            runCatching { context.unregisterReceiver(btStateReceiver) }
            registered = false
        }
        Obd2Connection.disconnect()
    }

    /** Which OBD2 adapter [Obd2Connection] should be on, or null to stay
     *  disconnected. See [pickObd2Address] for the rules. */
    private fun desiredObd2Address(): String? {
        val map = Settings.vehicleDevices.value
        val tripVehicle = resolvedVehicle()
        return pickObd2Address(
            tripActive = currentTripMode() != null,
            uiVisible = uiVisible(),
            tripVehicleResolved = tripVehicle != null,
            tripVehicleObd2Address = tripVehicle?.obd2Address,
            connectedObd2Addresses = connectedVehicles.mapNotNull { map[it]?.obd2Address },
            configuredObd2Addresses = map.values.mapNotNull { it.obd2Address }.distinct(),
        )
    }

    /** Bring [Obd2Connection] in line with [desiredObd2Address]: drop a link to
     *  the wrong adapter (or any link at all when none is wanted), open one to
     *  the right adapter when idle. Called from every edge that can change the
     *  answer — trip start/stop, UI visibility, a Bluetooth
     *  connect/disconnect/toggle, and a Settings change. Replaces the old
     *  unconditional dial-every-configured-adapter seed: a parked adapter is no
     *  longer retried around the clock (#96), and only the vehicle being driven
     *  is ever dialled, so an absent adapter can't block a present one (#97). */
    fun reconcileObd2Connections() {
        if (destroyed) return
        val target = desiredObd2Address()
        if (Obd2Connection.linkedAddress.value.let { it != null && it != target }) {
            Obd2Connection.disconnect()
        }
        if (target != null && Obd2Connection.linkedAddress.value == null) {
            val v = Settings.vehicleDevices.value.values.firstOrNull { it.obd2Address == target }
            Obd2Connection.connect(
                context.applicationContext, target,
                fuelType = v?.fuelType ?: FuelType.PETROL,
                calibrationPct = v?.fuelCalibrationPct ?: 100,
            )
        }
    }

    /**
     * Ask the headset/A2DP profiles which mapped devices are connected right
     * now, since ACL broadcasts only fire on change, not for existing links.
     *
     * The answer replaces what we believed rather than adding to it: a missed
     * disconnect (adapter reset, device out of range, service asleep) otherwise
     * pins the trip to a vehicle that was left behind hours ago. Both profiles
     * are asked before we commit, so the two callbacks can't erase each other.
     */
    fun seedConnectedVehicles() {
        val map = Settings.vehicleDevices.value
        if (map.isEmpty() || !hasBtPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val profiles = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        val found = LinkedHashSet<String>()
        var pending = profiles.size
        // Runs once the last profile has answered (or failed to).
        val commit = {
            if (connectedVehicles != found) {
                connectedVehicles.clear()
                connectedVehicles.addAll(found)
                refreshTripMode()
            }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.connectedDevices.forEach { d ->
                        if (map.containsKey(d.address)) found.add(d.address)
                    }
                } catch (e: SecurityException) {
                    // permission revoked between the check and here; ignore
                } finally {
                    adapter.closeProfileProxy(profile, proxy)
                }
                if (--pending == 0) commit()
            }
            /** A profile the phone doesn't support never calls back connected. */
            override fun onServiceDisconnected(profile: Int) {
                if (--pending == 0) commit()
            }
        }
        profiles.forEach {
            if (!adapter.getProfileProxy(context, listener, it)) pending--
        }
        if (pending == 0) commit()
    }

    /** The connected mapped vehicle that classifies the trip. The heaviest
     *  mode wins (see [modePriority]), not the last to connect: the helmet
     *  intercom and the car radio can both be up while the bike sits in the
     *  garage. Null when no mapped device is connected. */
    fun resolvedVehicle(): Settings.VehicleDevice? {
        val map = Settings.vehicleDevices.value
        return connectedVehicles.mapNotNull { map[it] }
            .maxByOrNull { modePriority.indexOf(it.mode) }
    }

    /** What the running trip is logged as — the resolved vehicle's mode
     *  (Cardo → moto, infotainment → car), else the spin tab's mode. The tab
     *  itself is never changed here: classification is the trip's, not the
     *  UI's. Whether a trip is worth keeping at all is decided by the
     *  service's own endTrip. */
    fun resolvedMode(): TravelMode =
        resolvedVehicle()?.mode ?: Settings.tripMode.value

    /** Retag the running trip if its mode should change (a mapped device
     *  connected or left), via [onModeChanged] — which restarts motion
     *  sensors to match. */
    fun refreshTripMode() {
        val mode = resolvedMode()
        val runningMode = currentTripMode()
        if (runningMode != null && runningMode != mode) {
            onModeChanged(mode)
        }
        reconcileObd2Connections()
    }
}
