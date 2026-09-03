package com.jellemax.detour.notif

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CirclePresence
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * The parked half of circle presence (issue #90). While a trip, convoy or the
 * map keeps [com.jellemax.detour.tracking.TripTrackingService] alive, that
 * service's own 2-minute `circleSyncLoop` posts this device's fix and runs
 * [CirclePresence.tick]. The moment it stops (parked, app backgrounded), this
 * worker takes over at WorkManager's 15-minute floor — a coarser "last seen"
 * cadence, which is within a Life360-style circle's tolerance for a phone that
 * is not moving.
 *
 * Overlap with the service's loop at drive start/end is harmless: a duplicate
 * post of a near-identical position, which the server resolves to the latest.
 */
class CircleSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Settings.init()
        if (!Account.signedIn || !SyncClient.configured()) return Result.success()

        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val loc = try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: client.lastLocation.await()
        } catch (e: SecurityException) {
            return Result.success()   // location permission gone; nothing to do
        } ?: return Result.success()

        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        return try {
            CirclePresence.tick(
                loc.latitude, loc.longitude, loc.accuracy.toDouble(),
                loc.time, ageMs, System.currentTimeMillis(),
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "circle-sync"

        /** Idempotent — [ExistingPeriodicWorkPolicy.KEEP] means repeated calls
         *  (app start, boot) don't reset the period. Cheap when signed out:
         *  [doWork] returns immediately. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CircleSyncWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
