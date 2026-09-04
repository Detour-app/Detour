package com.jellemax.detour.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.MainActivity
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CircleEvents
import com.jellemax.detour.data.CircleNotifyPolicy
import com.jellemax.detour.data.Features
import com.jellemax.detour.data.Group
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.net.ConvoyLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds the relay socket open for circle arrival/departure notifications,
 * independent of trip tracking and of any convoy - see the phase 2 design
 * note this was built against: a trip-tracking service that only runs
 * during a ride can't deliver an arrival at 3pm on a Tuesday, and
 * notifications must keep working for a user who denies location outright
 * (they're not sharing anything here, only listening for what other members'
 * devices already decided). [TripTrackingService][com.jellemax.detour.tracking.TripTrackingService]
 * was deliberately not extended for this reason - its own foreground start is
 * gated on holding a location permission (`canStart`), which has nothing to
 * do with whether this feature should run.
 *
 * Membership in [ConvoyLiveClient]'s socket is additive and reconnect-safe
 * (see [ConvoyLiveClient.setNotifyCircles]), so this service's only real job
 * is deciding *which* circles currently want live delivery and turning what
 * arrives into notifications - see [refreshNotifyCircles], [collectLiveEvents]
 * and [collectCatchUpOnReconnect].
 */
class CircleNotifyService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 5
        private const val CHANNEL_ID = "circle_notify_service"

        /** How often the circle list (and this device's own per-circle
         *  toggle) is re-polled in the background, to pick up an invite
         *  accepted or a switch flipped without this service being the one
         *  that flipped it. Minutes, not seconds - same reasoning as
         *  [com.jellemax.detour.data.CirclePresence.ACTIVE_INTERVAL_MS]: a
         *  circle is Life360-style presence, not a live feed, and this loop only
         *  exists to correct drift, not to carry the actual notifications
         *  (those ride the socket [collectLiveEvents] holds open). */
        private const val CIRCLE_LIST_REFRESH_MS = 5 * 60_000L

        /** Starts (or nudges) the service if signed in and a server is
         *  configured - the two local, synchronous checks that are worth
         *  doing before ever going foreground. Whether any circle actually
         *  wants notifications is a network question the service answers
         *  for itself in [refreshNotifyCircles], stopping right back down
         *  if the answer is no - same "start, then stand down" shape
         *  ConvoyLiveService already uses for its own misconfigured-server
         *  case. Call after any change that could affect the answer: app
         *  start, boot, a per-circle toggle, accepting/leaving a circle. */
        fun refresh(context: Context) {
            // Arrivals arrive over the relay, or over the catch-up its reconnect
            // triggers. With no relay there is nothing for this service to do,
            // and a foreground notification for it would be a lie.
            if (!Features.liveRelay) return
            if (!Account.signedIn || !SyncClient.configured()) return
            ContextCompat.startForegroundService(context, Intent(context, CircleNotifyService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopsStarted = false

    /** The circle list as of the last [refreshNotifyCircles] or
     *  [collectCatchUpOnReconnect] fetch - the same call this service already
     *  makes to decide which circles want delivery, kept around so
     *  [displayNameFor] has membership to resolve a rider id against without
     *  a second network round trip per notification. Stale between fetches
     *  in exactly the way [CirclesStore][com.jellemax.detour.data.CirclesStore]'s
     *  own membership is between its reloads - a rider who joined since the
     *  last fetch draws a blank handle until the next one, never a crash.
     *  `@Volatile`: written from whichever `Dispatchers.IO` thread the
     *  refresh/reconnect coroutines happen to land on, read from
     *  [displayNameFor] on another - same cross-thread visibility reason
     *  [ConvoyLiveClient]'s `runJob` carries the same annotation. */
    @Volatile private var circles: List<Group> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Settings.init()
        createChannel()
        PlaceNotifications.ensureChannel(this) // the arrival/departure pings' own channel, separate from this ongoing one
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // remoteMessaging, not dataSync: this service's whole job is
                // holding a connection open to receive messages whenever
                // they arrive, which is exactly what that type exists for -
                // dataSync carries a 6-hour/24h execution cap from Android
                // 15 that would silently cut an "always-on" service off.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            },
        )

        // React immediately to whatever just changed - refresh() is called
        // precisely because a toggle flipped or a circle was accepted, and
        // that shouldn't have to wait for periodicRefreshLoop's next tick.
        serviceScope.launch { refreshNotifyCircles() }
        if (!loopsStarted) {
            loopsStarted = true
            serviceScope.launch { periodicRefreshLoop() }
            serviceScope.launch { collectLiveEvents() }
            serviceScope.launch { collectCatchUpOnReconnect() }
        }
        // Idempotent either way (refreshNotifyCircles recomputes everything
        // from scratch, nothing here reads intent extras), so a bare restart
        // after the system kills this process is safe to let happen.
        return START_STICKY
    }

    /** Recomputes which circles this device should be joined to for
     *  notifications - [CircleNotifyPolicy.circlesWantingDelivery]'s call,
     *  accepted membership plus this device's own toggle (see
     *  [CircleNotifySettings]) - and hands the result to [ConvoyLiveClient].
     *  Stands the service down if nothing (or nobody signed in) qualifies. */
    private suspend fun refreshNotifyCircles() {
        if (!Account.signedIn || !SyncClient.configured()) {
            stopSelf()
            return
        }
        val fetched = try {
            Groups.list("circle")
        } catch (e: Exception) {
            return // offline or server hiccup; whatever's already joined stays joined, retried next tick
        }
        circles = fetched
        val ids = CircleNotifyPolicy.circlesWantingDelivery(fetched)
        if (ids.isEmpty()) {
            stopSelf()
            return
        }
        ConvoyLiveClient.setNotifyCircles(ids)
    }

    private suspend fun periodicRefreshLoop() {
        while (true) {
            delay(CIRCLE_LIST_REFRESH_MS)
            refreshNotifyCircles()
        }
    }

    /** Live delivery: a `place_event` frame the moment it arrives. */
    private suspend fun collectLiveEvents() {
        ConvoyLiveClient.placeEvents.collect { relay ->
            if (!CircleNotifySettings.notifyEnabled(relay.groupId)) return@collect
            // Defensive only - the server already excludes the mover from
            // its own broadcast; this just
            // means a bug on that side can never surface as self-spam here.
            if (relay.event.riderId == Account.riderId.value) return@collect
            PlaceNotifications.notify(this, relay.groupId, relay.event, displayNameFor(relay.groupId, relay.event.riderId))
            CircleEvents.setLastSeenEventTsMs(relay.groupId, relay.event.tsMs)
        }
    }

    /** Catch-up: whatever happened while this device wasn't actually
     *  connected, run the moment the socket (re)confirms a join - covers
     *  both a cold service start and every reconnect after a network blip,
     *  which a one-shot "on start" check alone would miss. */
    private suspend fun collectCatchUpOnReconnect() {
        ConvoyLiveClient.connected.collect { connected ->
            if (!connected) return@collect
            val fetched = try {
                // Same rule as [refreshNotifyCircles] and as iOS's own sweep,
                // so it is asked once rather than spelled out per caller -
                // this filter was inline here until the policy moved to
                // shared/, and two copies of "which circles want delivery"
                // is how the two platforms drifted in the first place.
                Groups.list("circle")
            } catch (e: Exception) {
                return@collect
            }
            circles = fetched
            val ids = CircleNotifyPolicy.circlesWantingDelivery(fetched)
            for (id in ids) catchUp(id)
        }
    }

    /** The handle to draw beside [riderId] in [groupId]'s notifications, from
     *  [circles] - the same list this service already fetches to decide
     *  which circles want delivery. Blank for an id that list doesn't know
     *  (a member who joined since the last fetch), same as
     *  [com.jellemax.detour.data.handleFor] everywhere else in the app: a
     *  placeholder to draw, not a reason to fail the notification. */
    private fun displayNameFor(groupId: String, riderId: RiderId): String =
        circles.firstOrNull { it.id == groupId }?.members.orEmpty().handleFor(riderId)

    // The catch-up body is shared with the push wake path ([CircleCatchUp]) so the
    // two never drift; the handle lookup stays here because it reads this service's
    // already-fetched [circles].
    private suspend fun catchUp(circleId: String) =
        CircleCatchUp.catchUp(this, circleId) { riderId -> displayNameFor(circleId, riderId) }

    override fun onDestroy() {
        ConvoyLiveClient.setNotifyCircles(emptySet())
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Circle notifications running", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // Deliberately not location-flavoured wording (docs/PLAY_LOCATION_DECLARATION.md):
            // this service never reads or shares this device's own position,
            // only listens for transitions other members' devices already
            // decided from theirs.
            .setContentText("Watching your circles for arrivals and departures")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
