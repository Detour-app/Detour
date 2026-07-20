package com.jellemax.maproulette.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.jellemax.maproulette.ble.BleNavServer

/**
 * Reads the phone's active media session (Spotify, whatever else is playing)
 * and relays it to [BleNavServer]'s music characteristic for the external
 * display. There is no direct "give me now-playing" API on Android — reading
 * [MediaSessionManager]'s active sessions requires being a bound, enabled
 * [NotificationListenerService], which is why this is one rather than a plain
 * background service. It never reads notification content.
 *
 * Send calls go through [BleNavServer], which already no-ops when the BLE
 * server isn't running or lacks permission — this service does not duplicate
 * that gating, it just tries unconditionally.
 */
class MediaListenerService : NotificationListenerService() {
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushNow()
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = pushNow()
        override fun onSessionDestroyed() = repickController()
    }

    // Position is a point-in-time snapshot (PlaybackState.position, anchored at
    // getLastPositionUpdateTime), not a stream — nothing calls back as it ticks
    // forward. Re-sending the extrapolated position on an interval is what
    // keeps the display's elapsed time from freezing between real state changes.
    private val ticker = object : Runnable {
        override fun run() {
            pushNow()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { repickController(it) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = manager
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            repickController(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Listener access was revoked between the service binding and this
            // call (e.g. the user just turned it off in Settings).
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        detachController()
        BleNavServer.clearMusic(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        detachController()
    }

    private fun repickController(sessions: List<MediaController>? = null) {
        val list = sessions ?: try {
            sessionManager?.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (e: SecurityException) {
            null
        } ?: emptyList()

        // Several apps can hold a session at once; the one actually playing is
        // the one worth showing. Falling back to the first keeps something on
        // screen (paused) rather than nothing when none are playing.
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()

        if (next?.sessionToken == activeController?.sessionToken) {
            pushNow()
            return
        }

        detachController()
        activeController = next
        next?.registerCallback(controllerCallback, mainHandler)

        if (next != null) {
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        } else {
            BleNavServer.clearMusic(applicationContext)
        }
    }

    private fun detachController() {
        mainHandler.removeCallbacks(ticker)
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    private fun pushNow() {
        val controller = activeController ?: return
        val metadata = controller.metadata
        val state = controller.playbackState

        if (metadata == null || state == null) {
            BleNavServer.clearMusic(applicationContext)
            return
        }

        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val durationMs = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val playing = state.state == PlaybackState.STATE_PLAYING

        // Extrapolate from the anchored snapshot so the display's elapsed time
        // reads correctly between ticks rather than only at the last state change.
        val elapsedSinceUpdate = if (playing) {
            (android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                state.playbackSpeed
        } else 0f
        val positionMs = (state.position + elapsedSinceUpdate).coerceIn(0f, durationMs.toFloat())

        BleNavServer.sendMusic(
            applicationContext,
            title = title,
            artist = artist,
            positionSec = positionMs / 1000.0,
            durationSec = durationMs / 1000.0,
            playing = playing,
        )
    }

    // Notification content is never read; only media-session metadata is used.
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {}
}
