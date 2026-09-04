package com.jellemax.detour.notif

import android.content.Context
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CircleEvents
import com.jellemax.detour.data.CircleNotifyPolicy
import com.jellemax.detour.data.Groups
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor

/**
 * Turns unseen circle place-events into notifications. For each circle that
 * wants delivery, fetch everything since the last one already shown, run the
 * shared [CircleNotifyPolicy] to decide which survive, post them, and advance
 * the last-seen marker.
 *
 * Extracted from [CircleNotifyService] so the two ways a catch-up is triggered —
 * the relay reconnect it drives, and the push wake [DetourMessagingService]
 * drives — run the *same* code. Events key on [RiderId]; the handle to draw
 * beside one is resolved by [resolveHandle] from the circle's members, which the
 * two callers source differently ([CircleNotifyService] from its already-fetched
 * list, the push sweep from the list it fetches here).
 */
object CircleCatchUp {

    /** Catch up every circle that currently wants delivery. This is what a push
     *  wake-ping runs: the ping is content-free, so the device decides for itself
     *  which circles to sweep (`docs/PUSH.md` §2). */
    suspend fun sweep(context: Context) {
        val circles = runCatching { Groups.list("circle") }.getOrNull() ?: return
        for (id in CircleNotifyPolicy.circlesWantingDelivery(circles)) {
            val members = circles.firstOrNull { it.id == id }?.members.orEmpty()
            catchUp(context, id) { riderId -> members.handleFor(riderId) }
        }
    }

    suspend fun catchUp(context: Context, circleId: String, resolveHandle: (RiderId) -> String) {
        try {
            val since = CircleEvents.lastSeenEventTsMs(circleId)
            val events = CircleEvents.events(circleId, since)
            if (events.isEmpty()) return
            val plan = CircleNotifyPolicy.planCatchUp(events, Account.riderId.value, System.currentTimeMillis())
            // Reversed: the plan is newest-first (that is how it picks which five
            // survive), but this tray has no sort key, so it ranks by post time and
            // the last one posted sits on top — see planCatchUp's doc.
            plan.individual.asReversed().forEach {
                PlaceNotifications.notify(context, circleId, it, resolveHandle(it.riderId))
            }
            if (plan.collapsedCount > 0) PlaceNotifications.notifySummary(context, circleId, plan.collapsedCount)
            // Advance past everything returned, not just what got shown — a self- or
            // stale transition still must not be re-fetched and re-considered next time.
            CircleEvents.setLastSeenEventTsMs(circleId, events.maxOf { it.tsMs })
        } catch (e: Exception) {
            // Offline or a server hiccup; retried on the next reconnect or wake.
        }
    }
}
