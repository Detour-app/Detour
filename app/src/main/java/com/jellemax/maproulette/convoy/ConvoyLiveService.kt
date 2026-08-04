package com.jellemax.maproulette.convoy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jellemax.maproulette.BuildConfig
import com.jellemax.maproulette.MainActivity
import com.jellemax.maproulette.audio.PushToTalk
import com.jellemax.maproulette.net.ConvoyLiveClient
import com.jellemax.maproulette.tracking.TripTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps the convoy WebSocket connected and push-to-talk playback running
 * while a convoy is joined — independent of trip tracking, since a convoy
 * can be joined with no trip running. Borrows [TripTrackingService]'s
 * location stream via [TripTrackingService.setConvoyActive] rather than
 * opening a second GPS listener.
 */
class ConvoyLiveService : Service() {

    companion object {
        private const val EXTRA_CONVOY_ID = "convoy_id"
        private const val ACTION_STOP = "com.jellemax.maproulette.CONVOY_STOP"
        private const val CHANNEL_ID = "convoy_live"
        private const val NOTIFICATION_ID = 4

        private fun canStart(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        private fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context, convoyId: Int) {
            if (!canStart(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConvoyLiveService::class.java).putExtra(EXTRA_CONVOY_ID, convoyId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConvoyLiveService::class.java))
        }
    }

    private var scope: CoroutineScope? = null
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // The foreground-service type declared here must never exceed the
        // permissions actually held: since Android 14, starting foreground
        // with a type whose permission is missing throws SecurityException
        // (this crashed the app once before for location, see
        // TripTrackingService.canStart's comment — RECORD_AUDIO gets the
        // same guard here rather than being claimed unconditionally). Mic
        // permission is requested by the caller (FriendsScreen) before this
        // service is ever started, but a user can still deny/revoke it, so
        // this checks the live state rather than trusting that happened.
        val micGranted = hasMicPermission(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    (if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            } else {
                0
            },
        )

        if (BuildConfig.LIVE_URL.isBlank()) {
            // Nothing to connect to. Refusing to proceed here (rather than
            // handing off to ConvoyLiveClient's retry loop) is what stops a
            // misconfigured server from escalating GPS to LIVE cadence and
            // running a foreground service forever with no way to notice.
            stopSelf()
            return START_NOT_STICKY
        }

        val convoyId = intent?.getIntExtra(EXTRA_CONVOY_ID, -1)?.takeIf { it >= 0 }
        if (convoyId != null) {
            // Idempotent: join() no-ops if already on this convoy, and
            // switches cleanly if a different one was passed - so a second
            // start() while already running (switching convoys) works
            // whether or not the one-time setup below has already run.
            ConvoyLiveClient.join(this, convoyId)
            if (scope == null) {
                val newScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                scope = newScope
                TripTrackingService.setConvoyActive(this, true)
                val manager = getSystemService(AudioManager::class.java)
                audioManager = manager
                previousAudioMode = manager.mode
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                requestAudioFocus(manager)
                PushToTalk.startPlayback(newScope)
            }
        }
        // Not START_STICKY: a system-restarted instance with no convoy id in
        // its intent would otherwise sit foreground forever claiming to
        // share location/listen for PTT while actually doing neither.
        return START_NOT_STICKY
    }

    private fun requestAudioFocus(manager: AudioManager) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    override fun onDestroy() {
        TripTrackingService.setConvoyActive(this, false)
        ConvoyLiveClient.leave()
        PushToTalk.stopTalking()
        PushToTalk.stopPlayback()
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioManager?.mode = previousAudioMode
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Convoy", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ConvoyLiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Convoy live")
            .setContentText("Sharing location, listening for push-to-talk")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
}
