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
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.jellemax.maproulette.data.NavEngine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.UUID

val NAV_SERVICE_UUID: UUID = UUID.fromString("b17a0001-9c2e-4b8a-8f21-1f5e2a6d0e01")
val NAV_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0002-9c2e-4b8a-8f21-1f5e2a6d0e01")
val MUSIC_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0003-9c2e-4b8a-8f21-1f5e2a6d0e01")
val TIME_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0004-9c2e-4b8a-8f21-1f5e2a6d0e01")
val ART_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0005-9c2e-4b8a-8f21-1f5e2a6d0e01")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val TIME_RESYNC_MS = 30_000L

// Album art. The display draws the cover at 180x180, so anything larger is
// detail thrown away on the far side of a slow link; quality steps down until
// the JPEG fits the firmware's receive buffer (art_link.h's MAX_JPEG_BYTES).
private const val ART_SIZE_PX = 180
private const val ART_MAX_BYTES = 24 * 1024
private val ART_QUALITY_STEPS = intArrayOf(80, 65, 50, 35)
// Header on every art chunk: [frameId][chunkIndex][chunkCount].
private const val ART_HEADER_BYTES = 3
private const val ART_MAX_CHUNKS = 255
// A chunk that stays unacknowledged this long is treated as lost rather than
// stalling the queue for the rest of the ride.
private const val ART_SEND_TIMEOUT_MS = 2_000L
private const val DEFAULT_ATT_MTU = 23
private const val TAG = "BleNavServer"

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
    private var timeCharacteristic: BluetoothGattCharacteristic? = null
    private var artCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // Time has no natural "changed" event like nav/music do, so it is pushed
    // on a plain interval instead — once on connect (below) so a freshly
    // joined display isn't waiting up to TIME_RESYNC_MS for its first clock,
    // and periodically after that so the offset stays right across a DST
    // change or a timezone crossed mid-ride.
    private val timeTicker = object : Runnable {
        override fun run() {
            sendTime(appContext ?: return)
            mainHandler.postDelayed(this, TIME_RESYNC_MS)
        }
    }
    private var appContext: Context? = null

    // Which characteristics each connected device has subscribed to, tracked
    // separately per UUID rather than one shared set: the firmware central
    // subscribes to nav and music independently, and a device that only wants
    // one should not be notified of the other.
    private val subscriptions = mutableMapOf<BluetoothDevice, MutableSet<UUID>>()

    private var lastSentAt = 0L
    private var lastSign: Int? = null
    private var lastStatsSentAt = 0L

    // Art is the only payload that doesn't fit in one notification, so it is
    // the only one that needs flow control: Android drops notifications queued
    // faster than the stack sends them, and a dropped chunk costs the whole
    // image. Chunks go out one at a time, each waiting for onNotificationSent.
    private val artQueue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var artSendInFlight = false
    private var artFrameId = 0
    // The cover currently playing, already encoded. A display that connects
    // mid-track has missed the send that went with the track change, and
    // waiting for the next song to get artwork looks like a broken feature —
    // so subscribing replays this.
    private var lastArtJpeg: ByteArray? = null
    // Negotiated ATT MTU per device, 23 until the central asks for more.
    private val deviceMtu = mutableMapOf<BluetoothDevice, Int>()

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

        appContext = context.applicationContext

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice, status: Int, newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscriptions.remove(device)
                    deviceMtu.remove(device)
                    artQueue.removeAll { it.first == device }
                    if (artQueue.isEmpty()) artSendInFlight = false
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Notifications only reach devices that have subscribed, which
                    // hasn't happened yet at connect time — but the characteristic
                    // value is also readable, so setting it now means a central
                    // that reads before subscribing still sees a real clock rather
                    // than an empty value.
                    sendTime(context)
                }
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
                            if (charUuid == ART_CHARACTERISTIC_UUID) {
                                lastArtJpeg?.let { queueArt(device, it) }
                                pumpArtQueue()
                            }
                        } else {
                            subscribed.remove(charUuid)
                        }
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                val previous = deviceMtu[device] ?: DEFAULT_ATT_MTU
                deviceMtu[device] = mtu
                // A display can subscribe before it has negotiated an MTU, and
                // at the 23-byte default a cover needs more chunks than the
                // 1-byte chunk counter can address — so that send is skipped
                // and retried here, once there is room to carry it.
                if (mtu > previous && ART_CHARACTERISTIC_UUID in (subscriptions[device] ?: emptySet())) {
                    lastArtJpeg?.let { queueArt(device, it) }
                    pumpArtQueue()
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                mainHandler.removeCallbacks(artSendWatchdog)
                artSendInFlight = false
                pumpArtQueue()
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
        val time = notifiable(TIME_CHARACTERISTIC_UUID)
        val art = notifiable(ART_CHARACTERISTIC_UUID)
        navCharacteristic = nav
        musicCharacteristic = music
        timeCharacteristic = time
        artCharacteristic = art

        val service = BluetoothGattService(NAV_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(nav)
        service.addCharacteristic(music)
        service.addCharacteristic(time)
        service.addCharacteristic(art)
        server.addService(service)

        mainHandler.removeCallbacks(timeTicker)
        mainHandler.post(timeTicker)

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
        mainHandler.removeCallbacks(timeTicker)
        gattServer = null
        advertiser = null
        navCharacteristic = null
        musicCharacteristic = null
        timeCharacteristic = null
        artCharacteristic = null
        appContext = null
        subscriptions.clear()
        mainHandler.removeCallbacks(artSendWatchdog)
        artQueue.clear()
        artSendInFlight = false
        deviceMtu.clear()
        lastArtJpeg = null
        lastSign = null
        lastStatsSentAt = 0L
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
            put("navigating", true)
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

    /**
     * "Just cruising": tracking a trip but no active route. Shares the nav
     * characteristic with [send] rather than getting its own — the display
     * already has one place that shows current speed, the fields that only
     * mean something mid-route (maneuver, distance, ETA) are just zeroed —
     * distinguished by `navigating:false`, which [send] never sets.
     */
    fun sendStats(context: Context, currentSpeedKmh: Double) {
        val characteristic = navCharacteristic ?: return
        if (!hasPermissions(context)) return
        val now = System.currentTimeMillis()
        if (now - lastStatsSentAt < 1000) return
        lastStatsSentAt = now

        val payload = JSONObject().apply {
            put("sign", 0)
            put("roundaboutExit", 0)
            put("street", "")
            put("distanceToTurnMeters", 0.0)
            put("remainingMeters", 0.0)
            put("routeMeters", 0.0)
            put("remainingTimeMs", JSONObject.NULL)
            put("speedKmh", currentSpeedKmh)
            put("speedLimitKmh", JSONObject.NULL)
            put("navigating", false)
        }.toString().toByteArray()

        characteristic.value = payload
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

    /**
     * Pushes cover art for the current track, as a JPEG split across
     * notifications. Called only when the track actually changes — see
     * MediaListenerService — since a cover is three orders of magnitude bigger
     * than the metadata packet it accompanies.
     *
     * The display keeps showing its placeholder if this never arrives, so
     * every failure path here simply returns.
     */
    fun sendArt(context: Context, cover: Bitmap) {
        if (artCharacteristic == null) return
        if (!hasPermissions(context)) return

        val recipients = subscriptions.filter { ART_CHARACTERISTIC_UUID in it.value }.keys
        if (recipients.isEmpty()) return

        val jpeg = encode(cover) ?: return
        lastArtJpeg = jpeg

        for (device in recipients) queueArt(device, jpeg)
        pumpArtQueue()
    }

    /** Splits one JPEG into notification-sized chunks for one display. */
    private fun queueArt(device: BluetoothDevice, jpeg: ByteArray) {
        // 3 bytes of ATT overhead on top of our own header.
        val mtu = deviceMtu[device] ?: DEFAULT_ATT_MTU
        val payloadSize = mtu - 3 - ART_HEADER_BYTES
        if (payloadSize <= 0) return
        val chunkCount = (jpeg.size + payloadSize - 1) / payloadSize
        if (chunkCount > ART_MAX_CHUNKS) {
            // Only reachable at or near the default MTU. onMtuChanged retries.
            Log.i(TAG, "art needs $chunkCount chunks at mtu $mtu; waiting for a bigger one")
            return
        }

        artFrameId = (artFrameId + 1) and 0xFF
        for (index in 0 until chunkCount) {
            val from = index * payloadSize
            val to = minOf(from + payloadSize, jpeg.size)
            val chunk = ByteArray(ART_HEADER_BYTES + (to - from))
            chunk[0] = artFrameId.toByte()
            chunk[1] = index.toByte()
            chunk[2] = chunkCount.toByte()
            jpeg.copyInto(chunk, ART_HEADER_BYTES, from, to)
            artQueue.addLast(device to chunk)
        }
    }

    /** Square-crops, downscales, and compresses to something that fits the link. */
    private fun encode(cover: Bitmap): ByteArray? {
        val side = minOf(cover.width, cover.height)
        if (side <= 0) return null
        // Centre crop first: covers are square in practice, but a letterboxed
        // one would otherwise arrive squashed rather than trimmed.
        val square = Bitmap.createBitmap(
            cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side,
        )
        val scaled = Bitmap.createScaledBitmap(square, ART_SIZE_PX, ART_SIZE_PX, true)

        for (quality in ART_QUALITY_STEPS) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= ART_MAX_BYTES) return bytes
        }
        // Even the lowest quality overflowed the display's buffer — sending it
        // would just be dropped on the other side.
        return null
    }

    private val artSendWatchdog = Runnable {
        // onNotificationSent never came. Whatever image was mid-flight is lost;
        // drop it rather than blocking every later one behind it.
        artQueue.clear()
        artSendInFlight = false
    }

    private fun pumpArtQueue() {
        if (artSendInFlight) return
        val server = gattServer ?: return
        val characteristic = artCharacteristic ?: return
        val (device, chunk) = artQueue.removeFirstOrNull() ?: return

        characteristic.value = chunk
        artSendInFlight = true
        server.notifyCharacteristicChanged(device, characteristic, false)
        mainHandler.postDelayed(artSendWatchdog, ART_SEND_TIMEOUT_MS)
    }

    /** No active media session — the display drops back to its idle state. */
    fun clearMusic(context: Context) {
        val characteristic = musicCharacteristic ?: return
        if (!hasPermissions(context)) return
        lastArtJpeg = null
        characteristic.value = JSONObject().apply { put("stop", true) }.toString().toByteArray()
        notifySubscribers(characteristic)
    }

    /**
     * Wall-clock sync for the display's idle screen. epochMs is UTC; the
     * board has no timezone database of its own, so utcOffsetMin (already
     * DST-adjusted, since it's read fresh each call rather than cached) rides
     * along and the board just adds it.
     */
    private fun sendTime(context: Context) {
        val characteristic = timeCharacteristic ?: return
        if (!hasPermissions(context)) return

        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("epochMs", now)
            put("utcOffsetMin", TimeZone.getDefault().getOffset(now) / 60_000)
        }.toString().toByteArray()

        characteristic.value = payload
        notifySubscribers(characteristic)
    }
}
