package com.jellemax.detour.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.MainActivity
import com.jellemax.detour.data.UpdateClient
import com.jellemax.detour.notif.PendingUpdateOpen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Carries an update transfer, and only it, for as long as the transfer takes.
 *
 * Before #277 the download ran in `HubScreen`'s `rememberCoroutineScope()`, so
 * walking from the Hub to the map cancelled it mid-stream and left
 * [UpdateState] on [UpdateStatus.Downloading] with nothing left alive to move
 * it off — the exact failure [UpdateState] is a process-scoped object to
 * prevent. A composition is the wrong lifetime for a 46 MB transfer
 * (`boundaries.md` §8.1); a started service is the only shape that outlives
 * every screen *and* is allowed to hold the network open with the app in the
 * background.
 *
 * `dataSync`, not `remoteMessaging`: unlike
 * [CircleNotifyService][com.jellemax.detour.notif.CircleNotifyService], which
 * documents why it avoids the type, this service is a bounded transfer that
 * ends of its own accord, so Android 15's 6-hour dataSync execution cap is
 * describing it accurately rather than cutting it off.
 *
 * Retry policy lives here because [UpdateDownloader.download] is deliberately
 * one attempt: [MAX_ATTEMPTS] tries with [BACKOFF_MS] doubling between them,
 * and only [UpdateDownloader.Outcome.Interrupted] is retried — a
 * [UpdateDownloader.Outcome.Refused] is the server's or the manifest's
 * considered no. The partial and its sidecar survive every failure and every
 * cancel, so the attempt after a drop resumes rather than restarts.
 */
class UpdateDownloadService : Service() {

    /** `SupervisorJob` so a failed transfer does not poison the scope the
     *  install branch also launches on. Cancelled in [onDestroy]; every
     *  terminal [UpdateState] write happens before the `stopSelf()` that
     *  leads there, so none of them can be lost to that cancellation. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null

    private val throttle = ProgressThrottle()

    private val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CANCEL -> cancelDownload()
            ACTION_INSTALL -> install()
            // A start with no action we recognise, or a null intent. Nothing to
            // do — but stopping is conditional, because a stray command must
            // not tear a running transfer down. Not START_STICKY, so no
            // system-restarted null intent ever reaches here in practice.
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    /**
     * The last line against the failure this service exists to fix. Every
     * ordinary ending — [finish], [cancelDownload] — has already written a
     * terminal [UpdateState] before the `stopSelf()` that leads here, so the
     * cast below finds nothing to do. It only bites when something else took
     * the service down mid-transfer, and then leaving the status on
     * [UpdateStatus.Downloading] would strand the row on a progress bar with
     * nothing alive to move it — which is #277 all over again. [UpdateStatus.Failed]
     * offers a retry, and the partial on disk means that retry resumes.
     */
    override fun onDestroy() {
        scope.cancel()
        (UpdateState.status.value as? UpdateStatus.Downloading)?.let {
            UpdateState.set(UpdateStatus.Failed(it.update))
        }
        super.onDestroy()
    }

    /**
     * Begins, or re-affirms, the transfer of whatever [UpdateState] is offering.
     *
     * `startForeground` comes before the coroutine and before every state
     * write, because [Companion.start] reached here through
     * `startForegroundService` and the system kills an app whose service is
     * told a foreground start is coming and then neither goes foreground nor
     * stops — the same five-second contract
     * [TripTrackingService][com.jellemax.detour.tracking.TripTrackingService.onStartCommand]
     * documents, and the same `runCatching`-then-stand-down answer, since from
     * Android 12 the call itself throws when the app is in the background.
     * Everything slow is on the other side of it.
     *
     * A second Download tap while one is running must not open a second socket
     * onto the same `.part` file, so the already-active job returns early — but
     * only *after* the foreground call, so the second command's own contract is
     * satisfied too. The notification it re-posts carries the fraction the
     * transfer is actually at rather than resetting the bar to indeterminate.
     */
    private fun start() {
        createChannel()
        val update = UpdateState.current() ?: run {
            stopSelf()
            return
        }
        val running = UpdateState.status.value as? UpdateStatus.Downloading
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                PROGRESS_ID,
                progressNotification(update, running?.fraction ?: INDETERMINATE),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return
        }
        if (job?.isActive == true) return

        // A "ready" or "failed" breadcrumb from the previous attempt is now a
        // lie; the transfer it described has been superseded by this one.
        notifications.cancel(TERMINAL_ID)
        throttle.reset()
        UpdateState.set(UpdateStatus.Downloading(update, INDETERMINATE))
        job = scope.launch { transfer(update) }
    }

    /**
     * The retry loop. One [UpdateDownloader.download] call is one attempt, and
     * an attempt that comes back [UpdateDownloader.Outcome.Interrupted] left
     * bytes on disk the next one resumes from, so the backoff is buying the
     * network time rather than repeating work.
     *
     * A finished artefact is recorded even when the transfer was cancelled on
     * its way past the post — see the `Done` branch. The other two outcomes go
     * behind `ensureActive()`, because [cancelDownload] has by then put
     * [UpdateState] back to [UpdateStatus.Available] and an outcome arriving
     * afterwards must not overwrite what the rider asked for with `Failed`.
     */
    private suspend fun transfer(update: UpdateClient.PendingUpdate) {
        val self = currentCoroutineContext().job
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) delay(BACKOFF_MS shl (attempt - 2))
            val outcome = UpdateDownloader.download(this, update) { fraction ->
                publishProgress(self, update, fraction)
            }
            // Deliberately ahead of the cancellation check. A `Done` is a
            // verified APK already renamed into place, and a cancel that lands
            // during that last verify cannot un-rename it. Leaving the status
            // on `Available` with a complete file sitting under the name the
            // next attempt wants would make that attempt fail its own rename
            // and report `Failed` for a download that had in fact succeeded.
            if (outcome is UpdateDownloader.Outcome.Done) {
                return finish(
                    UpdateStatus.Downloaded(update, outcome.file.path),
                    readyNotification(update),
                )
            }
            currentCoroutineContext().ensureActive()
            if (outcome is UpdateDownloader.Outcome.Refused) {
                return finish(UpdateStatus.Failed(update), failedNotification(update))
            }
            if (outcome is UpdateDownloader.Outcome.Interrupted) {
                Log.w(TAG, "attempt $attempt of $MAX_ATTEMPTS stopped at ${outcome.bytes} bytes")
            }
        }
        finish(UpdateStatus.Failed(update), failedNotification(update))
    }

    /**
     * [UpdateState] on every chunk, the notification only when there is
     * something new to see. `download` calls back per 64 KiB, which over 46 MB
     * is some seven hundred `notify()` binder round trips for a bar that moves
     * in whole percent — [ProgressThrottle] decides which of them are worth
     * making.
     *
     * **This callback is also how Cancel actually stops the transfer.**
     * [UpdateDownloader.download] blocks in a read loop with no suspension
     * point in it, so cancelling the coroutine does not interrupt the socket
     * and the loop would otherwise run the whole artefact to completion in the
     * background and publish an APK nobody asked for. Throwing out of here is
     * the interruption: `attempt()` treats every exception as retryable and
     * unwinds to [UpdateDownloader.Outcome.Interrupted] with the partial and
     * its sidecar left on disk — which is exactly the resume point a cancel is
     * meant to leave behind. Worst case the transfer overruns the cancel by one
     * 64 KiB chunk.
     *
     * [self] is the transfer's own job rather than the field, so a callback
     * still in flight from a cancelled attempt cannot write over the state
     * [cancelDownload] just set.
     */
    private fun publishProgress(self: Job, update: UpdateClient.PendingUpdate, fraction: Float) {
        self.ensureActive()
        UpdateState.set(UpdateStatus.Downloading(update, fraction))
        if (throttle.shouldPost(SystemClock.elapsedRealtime(), fraction)) {
            notifications.notify(PROGRESS_ID, progressNotification(update, fraction))
        }
    }

    /**
     * Lands the transfer: state first, then the ongoing notification is
     * dropped, then the breadcrumb that replaces it, then the service goes.
     *
     * [terminal] is posted under [TERMINAL_ID] rather than reusing
     * [PROGRESS_ID]: `stopForeground(STOP_FOREGROUND_REMOVE)` cancels the
     * foreground notification asynchronously, and a post to the same id
     * immediately behind it races that cancellation. A different id cannot be
     * swallowed by it, and outlives `stopSelf()` the way any ordinary
     * notification does.
     */
    private fun finish(status: UpdateStatus, terminal: Notification) {
        UpdateState.set(status)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.notify(TERMINAL_ID, terminal)
        stopSelf()
    }

    /**
     * The rider stopped it, so this is [UpdateStatus.Available] and not
     * [UpdateStatus.Failed]: nothing went wrong, and the partial still on disk
     * means the next Download resumes from here rather than starting over —
     * invisibly, since the UI has no state for "half a file".
     *
     * Only a transfer actually in flight is reverted. A Cancel tapped on a
     * notification that the system kept after the download finished would
     * otherwise walk a perfectly good [UpdateStatus.Downloaded] backwards.
     */
    private fun cancelDownload() {
        job?.cancel()
        job = null
        (UpdateState.status.value as? UpdateStatus.Downloading)?.let {
            UpdateState.set(UpdateStatus.Available(it.update))
        }
        // Removes PROGRESS_ID as well as clearing the foreground state; a
        // separate cancel() for the same id would be saying it twice.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The two branches `HubScreen` has today, unchanged in which one runs: no
     * "Install unknown apps" consent means the settings screen that grants it,
     * otherwise the install sheet.
     *
     * The sheet's own branch is on [scope] because
     * [UpdateInstaller.install] copies the whole APK into a `PackageInstaller`
     * session, and `onStartCommand` runs on the main thread. `stopSelf()` is
     * the last thing the coroutine does, so [onDestroy]'s `scope.cancel()`
     * cannot cut the copy short.
     *
     * Known limit, only observable on a device: tapped from the notification
     * with no activity of ours on screen, `requestPermission`'s `startActivity`
     * is subject to the background-activity-start rules and may not surface.
     * The install sheet itself does not depend on it — `PackageInstaller`
     * raises its own notification when it cannot show the dialogue.
     */
    private fun install() {
        val ready = UpdateState.status.value as? UpdateStatus.Downloaded ?: run {
            stopIfIdle()
            return
        }
        if (!UpdateInstaller.canInstall(this)) {
            UpdateInstaller.requestPermission(this)
            stopIfIdle()
            return
        }
        scope.launch {
            UpdateInstaller.install(this@UpdateDownloadService, File(ready.path))
            stopIfIdle()
        }
    }

    /** `stopSelf()` unless a transfer is running, which nothing that arrives
     *  here is entitled to end — Cancel is, and says so by name. */
    private fun stopIfIdle() {
        if (job?.isActive != true) stopSelf()
    }

    /** Shared with [UpdateNotification], which posts the one-per-version
     *  breadcrumb on the same channel and creates it the same way. The two must
     *  agree: a second channel for the same subject would give the rider two
     *  switches for one thing, and whichever ran first would win the name. */
    private fun createChannel() {
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun progressNotification(update: UpdateClient.PendingUpdate, fraction: Float): Notification {
        val indeterminate = fraction < 0f
        val percent = if (indeterminate) 0 else (fraction * PERCENT).toInt().coerceIn(0, PERCENT)
        return builder()
            .setContentTitle("Downloading Detour ${update.version}")
            // An indeterminate sweep, not a made-up number: a negative fraction
            // is UpdateDownloader saying the artefact's length is unknown.
            .setContentText(if (indeterminate) "Downloading…" else "$percent%")
            .setProgress(PERCENT, percent, indeterminate)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                serviceIntent(ACTION_CANCEL, RC_CANCEL),
            )
            .build()
    }

    private fun readyNotification(update: UpdateClient.PendingUpdate): Notification =
        builder()
            .setContentTitle("Detour ${update.version} is ready")
            .setContentText("Tap Install to finish updating.")
            .setAutoCancel(true)
            .addAction(android.R.drawable.stat_sys_download_done, "Install", serviceIntent(ACTION_INSTALL, RC_INSTALL))
            .build()

    private fun failedNotification(update: UpdateClient.PendingUpdate): Notification =
        builder()
            .setContentTitle("Detour ${update.version} didn't download")
            .setContentText("Open Detour to try again.")
            .setAutoCancel(true)
            .build()

    /** Everything the three notifications share, including the tap target: the
     *  Settings row that owns this whole flow, reached through
     *  [PendingUpdateOpen.EXTRA_OPEN_UPDATE_SETTINGS] exactly as
     *  [UpdateNotification]'s breadcrumb does. */
    private fun builder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    RC_OPEN,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(PendingUpdateOpen.EXTRA_OPEN_UPDATE_SETTINGS, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    /** A notification action back into this service. Its own request code per
     *  action, because `PendingIntent` identity ignores extras and would
     *  otherwise be decided by the action string alone. */
    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, UpdateDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "DetourUpdate"

        private const val ACTION_START = "com.jellemax.detour.UPDATE_DOWNLOAD"
        private const val ACTION_CANCEL = "com.jellemax.detour.UPDATE_CANCEL"
        private const val ACTION_INSTALL = "com.jellemax.detour.UPDATE_INSTALL"

        private const val CHANNEL_ID = "updates"

        /** Distinct from [UpdateNotification]'s 4201: that one is the
         *  once-per-version breadcrumb and this is the live transfer, and a
         *  shared id would have each silently erase the other. */
        private const val PROGRESS_ID = 4202
        private const val TERMINAL_ID = 4203

        private const val RC_OPEN = 4210
        private const val RC_CANCEL = 4211
        private const val RC_INSTALL = 4212

        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_MS = 2_000L
        private const val PERCENT = 100

        /** [UpdateDownloader]'s "no idea how long this is". */
        private const val INDETERMINATE = -1f

        /**
         * Starts, or re-affirms, the transfer of whatever [UpdateState] holds.
         *
         * `startForegroundService`, because this is the one action that ends in
         * a foreground service. Called from the Settings row, so the app is on
         * screen and the Android 12 background-start refusal does not apply —
         * and [start] stands down rather than crashing if it ever does.
         */
        fun start(context: Context) {
            // On this side of startForegroundService(), not in onStartCommand(),
            // for the reason TripTrackingService.canStart spells out: once the
            // system has been told a foreground service is coming, a service
            // that quietly stands down is as fatal as one that never goes
            // foreground. [start] re-checks anyway, since the state could
            // change under a slow start, and stops rather than stands down.
            if (UpdateState.current() == null) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, UpdateDownloadService::class.java).setAction(ACTION_START),
            )
        }

        /**
         * Stops a running transfer, keeping the partial for a later resume.
         *
         * Plain `startService`, unlike [start], and this is load-bearing:
         * neither this nor [install] goes foreground, so telling the system a
         * foreground start is coming would leave the service started and never
         * foregrounded — which is a crash. A background start is not a concern
         * for either: the service is already foreground while a transfer is
         * running, the Settings row is on screen when the UI calls them, and a
         * `PendingIntent` executed from a notification is temporarily
         * allowlisted for exactly this.
         */
        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        /** Hands the downloaded APK to the installer, or asks for the consent
         *  that lets it. See [cancel] for why this is not a foreground start. */
        fun install(context: Context) = send(context, ACTION_INSTALL)

        private fun send(context: Context, action: String) {
            context.startService(
                Intent(context, UpdateDownloadService::class.java).setAction(action),
            )
        }
    }
}

/**
 * Decides which progress callbacks are worth a `notify()`.
 *
 * Post when the whole-percent figure changed, or when [minIntervalMs] has gone
 * by without one — the second clause is what keeps an indeterminate transfer
 * (no percentage to change) from going silent, and what keeps a very large
 * artefact's slow percent from looking stalled.
 *
 * A plain class with no Android in it, so it has a unit test. The rest of the
 * service is `android.app.Service` and this module's tests deliberately touch
 * no Android API — see [UpdateDownloader.log] for the same rule met a different
 * way.
 */
internal class ProgressThrottle(private val minIntervalMs: Long = 500L) {

    private var lastMs = 0L
    private var lastPercent = Int.MIN_VALUE

    fun reset() {
        lastMs = 0L
        lastPercent = Int.MIN_VALUE
    }

    /** [fraction] is 0f..1f, or negative for "length unknown", which is its own
     *  bucket rather than a percentage. */
    fun shouldPost(nowMs: Long, fraction: Float): Boolean {
        val percent = if (fraction < 0f) -1 else (fraction * 100).toInt()
        if (percent == lastPercent && nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        lastPercent = percent
        return true
    }
}
