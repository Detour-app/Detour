package com.jellemax.detour.notif

import com.jellemax.detour.data.CircleNotifyPolicy
import com.jellemax.detour.data.CircleNotifyPolicy.CatchUpPlan
import com.jellemax.detour.data.PlaceEvent
import com.jellemax.detour.data.RiderId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the order a catch-up batch is *delivered* in - the half of
 * notification policy that stays platform-side, and the half the shade's
 * reading order actually depends on. [PlaceNotifications.show] sets no
 * `setWhen`, no group and no sort key, so the tray ranks by post time and
 * whatever was posted last sits on top; `CircleNotifyService.catchUp`
 * therefore iterates `plan.individual.asReversed()`. What is pinned here is
 * that composition - [CircleNotifyPolicy.planCatchUp]'s newest-first
 * selection, then the reversal - putting the newest arrival on top.
 *
 * What it cannot reach is the call site itself: `catchUp` is a private
 * suspend method on a `Service` that needs a `Context` and the network, and
 * this repo runs no Robolectric. So a change to *which* expression that one
 * line iterates is caught by review; a change to either half of the
 * expression is caught here. iOS has no test target at all
 * (`iosApp/project.yml` defines none), so its matching `reversed()` is
 * pinned by the comment above it and nothing else.
 */
class CircleNotifyDeliveryOrderTest {

    private fun event(riderId: String, tsMs: Long) = PlaceEvent(
        id = "e-$riderId", placeId = 1L, placeName = "Home",
        riderId = RiderId(riderId), kind = "arrive", tsMs = tsMs,
    )

    /** Exactly what `CircleNotifyService.catchUp` iterates over. */
    private fun deliveredOrder(plan: CatchUpPlan): List<RiderId> =
        plan.individual.asReversed().map { it.riderId }

    /** Cap+3 arrivals, one second apart, oldest first - a backlog big enough
     *  that the cap bites, so the selection and the delivery order are two
     *  visibly different things. */
    private fun backlog(now: Long): List<PlaceEvent> {
        val total = CircleNotifyPolicy.NOTIFY_CAP + 3
        return (0 until total).map { i -> event("user$i", now - (total - i) * 1_000L) }
    }

    @Test
    fun theNewestArrivalIsPostedLastSoItRanksOnTopOfTheTray() {
        val now = 10_000_000L
        val total = CircleNotifyPolicy.NOTIFY_CAP + 3
        val plan = CircleNotifyPolicy.planCatchUp(backlog(now), myId = RiderId("me"), nowMs = now)

        // Ascending by timestamp: oldest posted first, newest last. This is
        // what Android posted before the policy moved to shared/ (a sortedBy
        // + takeLast preserves ascending order), so the tray's behaviour is
        // unchanged - the reversal is what keeps it that way now that the
        // plan itself arrives newest-first.
        assertEquals(
            (total - CircleNotifyPolicy.NOTIFY_CAP until total).map { RiderId("user$it") },
            deliveredOrder(plan),
        )
        assertEquals(RiderId("user${total - 1}"), deliveredOrder(plan).last())
    }

    @Test
    fun deliveryOrderComesFromTheTimestampsNotFromWhateverOrderTheServerReturned() {
        // GET /circles/{id}/events hands back whatever order it chose, and
        // nothing between there and the tray sorts it except planCatchUp -
        // so a shuffled feed must still be delivered oldest-first, and the
        // same five must survive the cap.
        val now = 10_000_000L
        val shuffled = backlog(now).shuffled()
        val plan = CircleNotifyPolicy.planCatchUp(shuffled, myId = RiderId("me"), nowMs = now)

        val total = CircleNotifyPolicy.NOTIFY_CAP + 3
        assertEquals(
            (total - CircleNotifyPolicy.NOTIFY_CAP until total).map { RiderId("user$it") },
            deliveredOrder(plan),
        )
        assertEquals(3, plan.collapsedCount)
    }
}
