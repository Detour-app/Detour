package com.jellemax.detour.ble

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
import com.jellemax.detour.data.DisplayPin
import com.jellemax.detour.data.FeatureFlags
import com.jellemax.detour.data.Flag
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SunTimes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

val NAV_SERVICE_UUID: UUID = UUID.fromString("b17a0001-9c2e-4b8a-8f21-1f5e2a6d0e01")
val NAV_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0002-9c2e-4b8a-8f21-1f5e2a6d0e01")
val MUSIC_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0003-9c2e-4b8a-8f21-1f5e2a6d0e01")
val TIME_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0004-9c2e-4b8a-8f21-1f5e2a6d0e01")
val ART_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0005-9c2e-4b8a-8f21-1f5e2a6d0e01")
// Board -> phone, the one write-direction characteristic: speed and lean off
// the board's own GPS and IMU, so TripTrackingService can prefer them over
// the phone's own sensors when they're fresh. See TripTrackingService's
// boardTelemetryFresh()/boardLeanFresh() for the fallback logic.
val TELEMETRY_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0006-9c2e-4b8a-8f21-1f5e2a6d0e01")
// The minimap (#1070): one [Minimap] frame, chunked with art's framing.
val MAP_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17a0007-9c2e-4b8a-8f21-1f5e2a6d0e01")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val TIME_RESYNC_MS = 30_000L

// Album art. The display draws the cover at 180x180, so anything larger is
// detail thrown away on the far side of a slow link; quality steps down until
// the JPEG fits the firmware's receive buffer (art_link.h's MAX_JPEG_BYTES).
private const val ART_SIZE_PX = 180
private const val ART_MAX_BYTES = 24 * 1024
private val ART_QUALITY_STEPS = intArrayOf(80, 65, 50, 35)
// Header on every art and minimap chunk: [frameId][chunkIndex][chunkCount].
private const val CHUNK_HEADER_BYTES = 3
private const val MAX_CHUNKS = 255
// A chunk that stays unacknowledged this long is treated as lost rather than
// stalling the queue for the rest of the ride.
private const val CHUNK_SEND_TIMEOUT_MS = 2_000L
private const val DEFAULT_ATT_MTU = 23
private const val TAG = "BleNavServer"

/** GATT read/write permissions for every characteristic and CCCD on the display link. */
internal data class LinkPermissions(val read: Int, val write: Int)

/**
 * With [encrypted] (#837), only a central bonded with MITM protection (the board's passkey
 * pairing) can read, subscribe or write; Android starts pairing when one that isn't tries.
 * Without, anyone in range can, and only [DisplayPin]'s address pin keeps spoofed telemetry
 * out (#692). The descriptor constants have the same values as the characteristic ones.
 */
internal fun linkPermissions(encrypted: Boolean): LinkPermissions = if (encrypted) {
    LinkPermissions(
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
    )
} else {
    LinkPermissions(BluetoothGattCharacteristic.PERMISSION_READ, BluetoothGattCharacteristic.PERMISSION_WRITE)
}

/**
 * BLE GATT peripheral broadcasting state to an external display (a
 * handlebar-mounted screen): turn-by-turn navigation and, separately,
 * now-playing media info. One service, two characteristics — nav carries the
 * maneuver, the distance to it, the current speed and the speed limit; music
 * mirrors what MediaListenerService reads off the active media session. The central is expected to negotiate a larger MTU before
 * subscribing — neither payload fits in the default 23-byte ATT MTU.
 */
/** One packet off the board's own sensors — see TELEMETRY_CHARACTERISTIC_UUID. */
data class BoardTelemetry(
    val hasSpeed: Boolean,
    val speedKmh: Double,
    val hasLean: Boolean,
    val leanDeg: Double,
    /** Wall clock when this arrived, for the staleness check on the reading side —
     *  not anything the board sent, since BLE write timing already answers "how
     *  fresh is this" better than a timestamp carried through the write itself. */
    val receivedAtMs: Long,
)

@SuppressLint("MissingPermission")
object BleNavServer {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var navCharacteristic: BluetoothGattCharacteristic? = null
    private var musicCharacteristic: BluetoothGattCharacteristic? = null
    private var timeCharacteristic: BluetoothGattCharacteristic? = null
    private var artCharacteristic: BluetoothGattCharacteristic? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var mapCharacteristic: BluetoothGattCharacteristic? = null

    private val _boardTelemetry = MutableStateFlow<BoardTelemetry?>(null)
    val boardTelemetry: StateFlow<BoardTelemetry?> = _boardTelemetry

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

    // Art and the minimap are the payloads that don't fit in one notification,
    // so they are the ones that need flow control: Android drops notifications
    // queued faster than the stack sends them, and a dropped chunk costs the
    // whole frame. Chunks go out one at a time, each waiting for onNotificationSent.
    private class Chunk(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        val bytes: ByteArray,
    )
    private val chunkQueue = ArrayDeque<Chunk>()
    private var chunkSendInFlight = false
    private var chunkFrameId = 0
    // The cover currently playing, already encoded. A display that connects
    // mid-track has missed the send that went with the track change, and
    // waiting for the next song to get artwork looks like a broken feature —
    // so subscribing replays this.
    private var lastArtJpeg: ByteArray? = null
    // Same replay for the minimap, so a display that subscribes while stopped isn't blank.
    private var lastMap: ByteArray? = null
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
                    chunkQueue.removeAll { it.device == device }
                    if (chunkQueue.isEmpty()) chunkSendInFlight = false
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
                            if (charUuid == NAV_CHARACTERISTIC_UUID) DisplayPin.onNavSubscribe(device.address)
                            if (charUuid == ART_CHARACTERISTIC_UUID) {
                                lastArtJpeg?.let { queueArt(device, it) }
                                pumpChunks()
                            }
                            if (charUuid == MAP_CHARACTERISTIC_UUID) {
                                lastMap?.let { queueMap(device, it) }
                                pumpChunks()
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
                    pumpChunks()
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                mainHandler.removeCallbacks(chunkSendWatchdog)
                chunkSendInFlight = false
                pumpChunks()
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

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                // Only the remembered display's writes reach the trip (#692).
                if (characteristic.uuid == TELEMETRY_CHARACTERISTIC_UUID &&
                    DisplayPin.acceptsTelemetry(device.address)
                ) {
                    parseTelemetry(value)?.let { _boardTelemetry.value = it }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        val server = manager.openGattServer(context, callback) ?: return
        gattServer = server

        // Read once per start: flipping the flag takes effect when the display is next switched on.
        val perms = linkPermissions(FeatureFlags.isOn(Flag.BLE_ENCRYPTED_LINK))
        fun notifiable(uuid: UUID) = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            perms.read,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD_UUID, perms.read or perms.write))
        }

        val nav = notifiable(NAV_CHARACTERISTIC_UUID)
        val music = notifiable(MUSIC_CHARACTERISTIC_UUID)
        val time = notifiable(TIME_CHARACTERISTIC_UUID)
        val art = notifiable(ART_CHARACTERISTIC_UUID)
        val map = notifiable(MAP_CHARACTERISTIC_UUID)
        val telemetry = BluetoothGattCharacteristic(
            TELEMETRY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            perms.write,
        )
        navCharacteristic = nav
        musicCharacteristic = music
        timeCharacteristic = time
        artCharacteristic = art
        mapCharacteristic = map
        telemetryCharacteristic = telemetry

        val service = BluetoothGattService(NAV_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(nav)
        service.addCharacteristic(music)
        service.addCharacteristic(time)
        service.addCharacteristic(art)
        service.addCharacteristic(telemetry)
        service.addCharacteristic(map)
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

    /** Called when the rider switches the display off, so it also forgets
     *  which board was the display ([DisplayPin]). */
    fun stop(context: Context) {
        DisplayPin.forget()
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
        mapCharacteristic = null
        lastMap = null
        telemetryCharacteristic = null
        appContext = null
        subscriptions.clear()
        mainHandler.removeCallbacks(chunkSendWatchdog)
        chunkQueue.clear()
        chunkSendInFlight = false
        deviceMtu.clear()
        lastArtJpeg = null
        lastSign = null
        lastStatsSentAt = 0L
    }

    /** Malformed JSON (a write mid-reconnect, a firmware bug) drops the packet
     *  rather than publishing garbage — the next write 250ms later corrects it. */
    private fun parseTelemetry(value: ByteArray): BoardTelemetry? = try {
        val json = JSONObject(String(value, Charsets.UTF_8))
        val hasSpeed = json.optBoolean("hasSpeed", false)
        val speedKmh = json.optDouble("speedKmh", 0.0)
        val hasLean = json.optBoolean("hasLean", false)
        val leanDeg = json.optDouble("leanDeg", 0.0)
        // An out-of-range value here (NaN/Infinity, or a wildly implausible
        // reading) means the transport or firmware glitched, not that the
        // board really saw it — the packet is dropped whole rather than
        // clamped, since a clamped garbage value would still be recorded
        // downstream as a real reading.
        if (hasSpeed && (!speedKmh.isFinite() || speedKmh !in 0.0..350.0)) {
            null
        } else if (hasLean && (!leanDeg.isFinite() || abs(leanDeg) > 70.0)) {
            null
        } else {
            BoardTelemetry(
                hasSpeed = hasSpeed,
                speedKmh = speedKmh,
                hasLean = hasLean,
                leanDeg = leanDeg,
                receivedAtMs = System.currentTimeMillis(),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "bad telemetry payload: ${e.message}")
        null
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
        // Throttle: only push on a new maneuver, or at most once/second otherwise.
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
            // Roundabout exit heading change, the number RoundaboutGlyph draws
            // the phone's exit spur from, so the display's exit points the same
            // way instead of guessing from the exit number. Whole degrees: the
            // extra digits only cost MTU. Null off a roundabout.
            put("exitTurnDeg", instruction?.roundaboutTurnDeg?.roundToInt() ?: JSONObject.NULL)
            putThenChip(progress)
            put("navigating", true)
        }.toString().toByteArray()

        characteristic.value = payload
        notifySubscribers(characteristic)
    }

    /**
     * The maneuver after this one, for the display's "then" chip — the phone's
     * thenPill. All null past the last turn; the display hides the chip on a
     * null or missing thenSign. thenExitTurnDeg is [send]'s exitTurnDeg for
     * that maneuver.
     */
    private fun JSONObject.putThenChip(progress: NavEngine.Progress) {
        val thenDistance = progress.distanceToNextNextMeters
        val thenSign = progress.nextNextInstruction?.sign?.takeIf { thenDistance != null }
        put("thenSign", thenSign ?: JSONObject.NULL)
        put("thenDistanceMeters", thenSign?.let { thenDistance } ?: JSONObject.NULL)
        val thenTurnDeg = thenSign?.let { progress.nextNextInstruction?.roundaboutTurnDeg }
        put("thenExitTurnDeg", thenTurnDeg?.roundToInt() ?: JSONObject.NULL)
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
     *
     * [speedLimitKmh] is the ambient limit the map's own sign shows off-route,
     * so the phone and the display never disagree; null hides the display's sign.
     */
    fun sendStats(context: Context, currentSpeedKmh: Double, speedLimitKmh: Double?) {
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
            put("speedLimitKmh", speedLimitKmh ?: JSONObject.NULL)
            put("navigating", false)
        }.toString().toByteArray()

        characteristic.value = payload
        notifySubscribers(characteristic)
    }

    /** Pushes the currently playing track. See [com.jellemax.detour.media.MediaListenerService]. */
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
        pumpChunks()
    }

    /** Splits one JPEG into notification-sized chunks for one display. */
    private fun queueArt(device: BluetoothDevice, jpeg: ByteArray) {
        queueChunks(device, artCharacteristic ?: return, jpeg)
    }

    /**
     * Pushes one minimap frame ([Minimap.encode]) to every display subscribed to it. A frame
     * still queued for a display is dropped first: only the newest one is worth sending, and
     * the board starts over on the next chunk 0.
     */
    fun sendMap(context: Context, frame: ByteArray) {
        val characteristic = mapCharacteristic ?: return
        if (!hasPermissions(context)) return
        lastMap = frame
        val recipients = subscriptions.filter { MAP_CHARACTERISTIC_UUID in it.value }.keys
        if (recipients.isEmpty()) return
        chunkQueue.removeAll { it.characteristic === characteristic }
        for (device in recipients) queueMap(device, frame)
        pumpChunks()
    }

    private fun queueMap(device: BluetoothDevice, frame: ByteArray) {
        queueChunks(device, mapCharacteristic ?: return, frame)
    }

    private fun queueChunks(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        // 3 bytes of ATT overhead on top of our own header.
        val mtu = deviceMtu[device] ?: DEFAULT_ATT_MTU
        val payloadSize = mtu - 3 - CHUNK_HEADER_BYTES
        if (payloadSize <= 0) return
        val chunkCount = (bytes.size + payloadSize - 1) / payloadSize
        if (chunkCount > MAX_CHUNKS) {
            // Only reachable at or near the default MTU. onMtuChanged retries.
            Log.i(TAG, "${characteristic.uuid} needs $chunkCount chunks at mtu $mtu; waiting for a bigger one")
            return
        }

        chunkFrameId = (chunkFrameId + 1) and 0xFF
        for (index in 0 until chunkCount) {
            val from = index * payloadSize
            val to = minOf(from + payloadSize, bytes.size)
            val chunk = ByteArray(CHUNK_HEADER_BYTES + (to - from))
            chunk[0] = chunkFrameId.toByte()
            chunk[1] = index.toByte()
            chunk[2] = chunkCount.toByte()
            bytes.copyInto(chunk, CHUNK_HEADER_BYTES, from, to)
            chunkQueue.addLast(Chunk(device, characteristic, chunk))
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

    private val chunkSendWatchdog = Runnable {
        // onNotificationSent never came. Whatever image was mid-flight is lost;
        // drop it rather than blocking every later one behind it.
        chunkQueue.clear()
        chunkSendInFlight = false
    }

    private fun pumpChunks() {
        if (chunkSendInFlight) return
        val server = gattServer ?: return
        val next = chunkQueue.removeFirstOrNull() ?: return

        next.characteristic.value = next.bytes
        chunkSendInFlight = true
        server.notifyCharacteristicChanged(next.device, next.characteristic, false)
        mainHandler.postDelayed(chunkSendWatchdog, CHUNK_SEND_TIMEOUT_MS)
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
            put("light", !displayDark(context, now))
        }.toString().toByteArray()

        characteristic.value = payload
        notifySubscribers(characteristic)
    }

    /**
     * The app theme preference resolved to dark yes/no, without Compose, so the
     * display follows the same day/night call the app makes. AUTO uses the last
     * known location, else the clock guess, like the app's own isNightNow().
     */
    @Suppress("MissingPermission")
    private fun displayDark(context: Context, now: Long): Boolean = when (Settings.theme.value) {
        Settings.Theme.LIGHT -> false
        Settings.Theme.DARK -> true
        Settings.Theme.SYSTEM ->
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        Settings.Theme.AUTO -> {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = try {
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            } catch (e: Exception) {
                null
            }
            if (loc != null) SunTimes.isNight(loc.latitude, loc.longitude, now)
            else SunTimes.isNightFallback(now)
        }
    }
}
