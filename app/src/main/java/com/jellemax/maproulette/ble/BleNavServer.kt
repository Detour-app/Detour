package com.jellemax.maproulette.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.jellemax.maproulette.data.NavEngine
import org.json.JSONObject
import java.util.UUID

val NAV_SERVICE_UUID: UUID = UUID.fromString("b17a0001-9c2e-4b8a-8f21-1f5e2a6d0e01")
val NAV_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0002-9c2e-4b8a-8f21-1f5e2a6d0e01")
val MUSIC_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0003-9c2e-4b8a-8f21-1f5e2a6d0e01")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE GATT peripheral broadcasting state to an external display (a
 * handlebar-mounted screen): turn-by-turn navigation and, separately,
 * now-playing media info. One service, two characteristics — nav mirrors
 * NavRelay's Wear OS payload with a few extra fields the bigger screen has
 * room for; music mirrors what MediaListenerService reads off the active
 * media session. The central is expected to negotiate a larger MTU before
 * subscribing — neither payload fits in the default 23-byte ATT MTU.
 */
@SuppressLint("MissingPermission")
object BleNavServer {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var navCharacteristic: BluetoothGattCharacteristic? = null
    private var musicCharacteristic: BluetoothGattCharacteristic? = null

    // Which characteristics each connected device has subscribed to, tracked
    // separately per UUID rather than one shared set: the firmware central
    // subscribes to nav and music independently, and a device that only wants
    // one should not be notified of the other.
    private val subscriptions = mutableMapOf<BluetoothDevice, MutableSet<UUID>>()

    private var lastSentAt = 0L
    private var lastSign: Int? = null

    private fun hasPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(context: Context) {
        if (gattServer != null) return
        if (!hasPermissions(context)) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice, status: Int, newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) subscriptions.remove(device)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    val charUuid = descriptor.characteristic?.uuid
                    if (charUuid != null) {
                        val subscribed = subscriptions.getOrPut(device) { mutableSetOf() }
                        if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                            subscribed.add(charUuid)
                        } else {
                            subscribed.remove(charUuid)
                        }
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    characteristic.value?.drop(offset)?.toByteArray(),
                )
            }
        }

        val server = manager.openGattServer(context, callback) ?: return
        gattServer = server

        fun notifiable(uuid: UUID) = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }

        val nav = notifiable(NAV_CHARACTERISTIC_UUID)
        val music = notifiable(MUSIC_CHARACTERISTIC_UUID)
        navCharacteristic = nav
        musicCharacteristic = music

        val service = BluetoothGattService(NAV_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(nav)
        service.addCharacteristic(music)
        server.addService(service)

        advertiser = adapter.bluetoothLeAdvertiser
        advertiser?.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(NAV_SERVICE_UUID))
                .build(),
            object : AdvertiseCallback() {},
        )
    }

    fun stop(context: Context) {
        if (hasPermissions(context)) {
            advertiser?.stopAdvertising(object : AdvertiseCallback() {})
            gattServer?.close()
        }
        gattServer = null
        advertiser = null
        navCharacteristic = null
        musicCharacteristic = null
        subscriptions.clear()
        lastSign = null
    }

    private fun notifySubscribers(characteristic: BluetoothGattCharacteristic) {
        val server = gattServer ?: return
        subscriptions.forEach { (device, subscribed) ->
            if (characteristic.uuid in subscribed) {
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    fun send(context: Context, progress: NavEngine.Progress, currentSpeedKmh: Double) {
        val characteristic = navCharacteristic ?: return
        if (!hasPermissions(context)) return
        val instruction = progress.nextInstruction
        val now = System.currentTimeMillis()
        // Throttle like NavRelay: only push on a new maneuver, or at most once/second otherwise.
        if (instruction?.sign == lastSign && now - lastSentAt < 1000) return
        lastSentAt = now
        lastSign = instruction?.sign

        val payload = JSONObject().apply {
            put("sign", instruction?.sign ?: 0)
            // 0 when this isn't a roundabout, or when the router didn't say which
            // exit — the display omits the exit number rather than guessing.
            put("roundaboutExit", instruction?.exitNumber ?: 0)
            put("street", instruction?.text ?: "")
            put("distanceToTurnMeters", progress.distanceToTurnMeters)
            put("remainingMeters", progress.remainingMeters)
            // Total route length, so the display can draw progress without having
            // to infer it from the largest remainingMeters it happens to observe.
            put("routeMeters", progress.routeMeters)
            put("remainingTimeMs", progress.remainingTimeMs ?: JSONObject.NULL)
            put("speedKmh", currentSpeedKmh)
            put("speedLimitKmh", progress.speedLimitKmh ?: JSONObject.NULL)
        }.toString().toByteArray()

        characteristic.value = payload
        notifySubscribers(characteristic)
    }

    fun clear(context: Context) {
        val characteristic = navCharacteristic ?: return
        if (!hasPermissions(context)) return
        lastSign = null
        characteristic.value = JSONObject().apply { put("stop", true) }.toString().toByteArray()
        notifySubscribers(characteristic)
    }

    /** Pushes the currently playing track. See [com.jellemax.maproulette.media.MediaListenerService]. */
    fun sendMusic(
        context: Context,
        title: String,
        artist: String,
        positionSec: Double,
        durationSec: Double,
        playing: Boolean,
    ) {
        val characteristic = musicCharacteristic ?: return
        if (!hasPermissions(context)) return

        val payload = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("posSec", positionSec)
            put("durSec", durationSec)
            put("playing", playing)
        }.toString().toByteArray()

        characteristic.value = payload
        notifySubscribers(characteristic)
    }

    /** No active media session — the display drops back to its idle state. */
    fun clearMusic(context: Context) {
        val characteristic = musicCharacteristic ?: return
        if (!hasPermissions(context)) return
        characteristic.value = JSONObject().apply { put("stop", true) }.toString().toByteArray()
        notifySubscribers(characteristic)
    }
}
