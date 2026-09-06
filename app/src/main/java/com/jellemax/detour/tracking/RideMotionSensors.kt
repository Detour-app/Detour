package com.jellemax.detour.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The phone's own lean sensor: reads the rotation-vector sensor, smooths it,
 * and arbitrates against the board's own lean telemetry when that's fresher
 * (see [freshBoardLeanDeg]) — plus the deepest-lean-since-last-trace-point
 * bookkeeping [TripTrackingService.addTracePoint] reads through
 * [peakLeanSinceLastTracePoint].
 *
 * Every accepted candidate reading goes to [onLean], at the same two trigger
 * points as before this moved out of the service: a fresh phone sample when
 * the board isn't supplying one, or a throttled poll of the board when it is.
 * What happens to that value from there — the speed gate below which "lean"
 * is just steering-head rake, the trip's own max lean, the
 * [com.jellemax.detour.drive.HardEventDetector] cornering latch — needs
 * running-trip state this class has no business holding, so it stays on
 * [TripTrackingService.recordLean]. [onLean] is the wiring out to that.
 * [notePeak] is the one piece of wiring back, for the peak this class hands
 * to [addTracePoint] — recordLean calls it only with readings that already
 * passed its own speed gate, so a phone rocking on its mount while parked
 * can't show up as a corner in the trace.
 *
 * G-force is a different sensor (accelerometer) feeding a different set of
 * trip stats — [TripTrackingService] still registers and reads that one
 * itself.
 */
class RideMotionSensors(
    context: Context,
    private val leanEmaAlpha: Double,
    private val maxLeanSlewDeg: Double,
    private val sensorEmitIntervalMs: Long,
    private val boardTelemetryStaleMs: Long,
    private val onLean: (Double) -> Unit,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationMatrix = FloatArray(9)

    private var currentLeanDeg = 0.0
    private var lastEmitMs = 0L
    private var mode: TravelMode? = null
    /** Mount-to-bike misalignment, subtracted from every raw lean reading;
     *  see [Settings.leanOffsetDeg]. Cached at trip start — it only changes
     *  from the settings screen, never mid-trip. */
    private var leanOffsetDeg = 0.0
    /** Deepest lean since the last trace point, sign kept; see
     *  [peakLeanSinceLastTracePoint]. */
    @Volatile private var segmentPeakLeanDeg = 0.0
    /** Whether this vehicle's lean is being measured at all — a car's points
     *  record no lean rather than a misleading zero. */
    @Volatile private var leanTracked = false

    /**
     * Lean angle assumes the phone is mounted upright facing forward, e.g. a
     * handlebar mount — a phone in a pocket will read garbage.
     *
     * Lean is *not* [SensorManager.getOrientation]'s roll. That roll is only
     * defined for a phone lying flattish: a phone standing upright sits exactly
     * on its gimbal-lock singularity (pitch -90°), where roll degenerates and
     * reads ±180° regardless of how the bike is leaning — which is why every
     * ride recorded a max lean of about 180°.
     *
     * The gravity direction has no such singularity. The rotation matrix's
     * third row is world-up expressed in device axes, so the angle between it
     * and the device's own up axis, about the axis out of the screen, is the
     * lean: 0 with the phone upright, positive leaning right. A mount tilted
     * back towards the rider only moves gravity along that third axis, so it
     * does not bias the reading.
     */
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            // Third row of the rotation matrix = world up in device axes.
            // Negated x so a lean to the right reads positive: tipping
            // right moves gravity towards the device's -x side.
            val upX = -rotationMatrix[6]
            val upY = rotationMatrix[7]
            val rawLeanDeg = Math.toDegrees(atan2(upX, upY).toDouble()) - leanOffsetDeg
            // Drop single-sample fusion glitches before they ever reach the
            // EMA — see maxLeanSlewDeg. The EMA below only damps a glitch's
            // contribution, it can't remove it outright.
            if (abs(rawLeanDeg - currentLeanDeg) <= maxLeanSlewDeg) {
                currentLeanDeg += leanEmaAlpha * (rawLeanDeg - currentLeanDeg)
                // Only this sensor's own reading feeds onLean while the board
                // isn't supplying a fresher one — see the throttled poll below.
                if (freshBoardLeanDeg() == null) onLean(currentLeanDeg)
            }
            // The board updates at 4 Hz (see boardTelemetryStaleMs), so
            // polling it this often is a fine match — throttled independently
            // of the phone's own ~60 Hz rotation-vector samples above.
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitMs < sensorEmitIntervalMs) return
            lastEmitMs = now
            freshBoardLeanDeg()?.let(onLean)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Board lean is only trusted for a vehicle whose mode tracks lean at all
     *  — the same rule [start] applies to the phone's own sensor, so a car
     *  trip with a board still connected doesn't suddenly grow one. */
    private fun freshBoardLeanDeg(): Double? {
        if (mode?.tracksLean != true) return null
        val telemetry = BleNavServer.boardTelemetry.value ?: return null
        val age = System.currentTimeMillis() - telemetry.receivedAtMs
        if (age !in 0..boardTelemetryStaleMs) return null
        return if (telemetry.hasLean) telemetry.leanDeg else null
    }

    /** Registers the rotation-vector sensor only for a vehicle mode with a
     *  meaningful lean reading, so a car trip never records a lean angle. */
    fun start(mode: TravelMode) {
        this.mode = mode
        currentLeanDeg = 0.0
        leanTracked = false
        if (!mode.tracksLean) return
        // SENSOR_DELAY_UI (~60ms) resolves a lean just as well as
        // SENSOR_DELAY_GAME (~20ms) and wakes the CPU a third as often.
        leanOffsetDeg = Settings.leanOffsetDeg.value.toDouble()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            leanTracked = true
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        leanTracked = false
        segmentPeakLeanDeg = 0.0
    }

    /** Read by [TripTrackingService.addTracePoint] and stored on every trace
     *  point — null for a vehicle that doesn't track lean at all, rather than
     *  a misleading zero. */
    fun peakLeanSinceLastTracePoint(): Double? = if (leanTracked) segmentPeakLeanDeg else null

    fun resetPeakLean() {
        segmentPeakLeanDeg = 0.0
    }

    /** See the class doc — [TripTrackingService.recordLean] calls this with
     *  a reading only once it's past that function's own speed gate. */
    fun notePeak(deg: Double) {
        if (abs(deg) > abs(segmentPeakLeanDeg)) segmentPeakLeanDeg = deg
    }
}
