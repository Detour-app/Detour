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
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE GATT peripheral broadcasting turn-by-turn state to an external display
 * (a handlebar-mounted screen), the same role NavRelay plays for the Wear OS
 * watch, but over BLE and with a few extra fields the bigger screen has room
 * for (road name, remaining distance/ETA). The central is expected to
 * negotiate a larger MTU before subscribing — the JSON payload here doesn't
 * fit in the default 23-byte ATT MTU.
 */
@SuppressLint("MissingPermission")
object BleNavServer {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var navCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()

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
                if (newState == BluetoothProfile.STATE_DISCONNECTED) subscribers.remove(device)
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
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        subscribers.add(device)
                    } else {
                        subscribers.remove(device)
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

        val characteristic = BluetoothGattCharacteristic(
            NAV_CHARACTERISTIC_UUID,
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
        navCharacteristic = characteristic

        val service = BluetoothGattService(NAV_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
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
        subscribers.clear()
        lastSign = null
    }

    fun send(context: Context, progress: NavEngine.Progress, currentSpeedKmh: Double) {
        val server = gattServer ?: return
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
            put("street", instruction?.text ?: "")
            put("distanceToTurnMeters", progress.distanceToTurnMeters)
            put("remainingMeters", progress.remainingMeters)
            put("remainingTimeMs", progress.remainingTimeMs ?: JSONObject.NULL)
            put("speedKmh", currentSpeedKmh)
            put("speedLimitKmh", progress.speedLimitKmh ?: JSONObject.NULL)
        }.toString().toByteArray()

        characteristic.value = payload
        subscribers.forEach { device -> server.notifyCharacteristicChanged(device, characteristic, false) }
    }

    fun clear(context: Context) {
        val server = gattServer ?: return
        val characteristic = navCharacteristic ?: return
        if (!hasPermissions(context)) return
        lastSign = null
        characteristic.value = JSONObject().apply { put("stop", true) }.toString().toByteArray()
        subscribers.forEach { device -> server.notifyCharacteristicChanged(device, characteristic, false) }
    }
}
