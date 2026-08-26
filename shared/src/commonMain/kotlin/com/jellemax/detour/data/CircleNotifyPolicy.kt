package com.jellemax.detour.data

/**
 * Decides which caught-up circle arrivals are worth raising as a
 * notification, and which circles currently want delivery at all. The
 * wording of a notification was already shared ([PlaceEvent.notificationText],
 * [catchUpSummaryText] above) - this is the policy half, which Android and
 * iOS previously decided independently. Extracted from Android's
 * `PlaceNotifications.planCatchUp`, which was already a pure planner taking
 * `nowMs` as a parameter; iOS's `CircleNotifications.runCatchUpSweep`
 * hand-rolled the same three filters inline.
 */
object CircleNotifyPolicy {

    /** A caught-up transition older than this is stale history, not news -
     *  an "earlier today" scale, well past the couple of minutes circles
     *  normally sync on. */
    const val STALE_AFTER_MS = 3 * 60 * 60_000L

    /** Individual pings one catch-up batch may raise before the rest
     *  collapse into a single summary notification instead. */
    const val NOTIFY_CAP = 5

    data class CatchUpPlan(val individual: List<PlaceEvent>, val collapsedCount: Int)

    /**
     * Turns raw catch-up events into what's worth surfacing: never the
     * caller's own transitions (`GET /circles/{id}/events` returns those by
     * design - see [CircleEvents]'s doc - but a user does not need telling
     * where they themselves went), nothing older than [staleAfterMs], and at
     * most [cap] individual notifications - a phone back from a week
     * offline must not detonate into fifty pings. Anything past the cap is
     * dropped from [CatchUpPlan.individual] but still counted in
     * [CatchUpPlan.collapsedCount] rather than vanishing without a trace.
     *
     * [CatchUpPlan.individual] is newest-first: when only a handful can be
     * shown, the most recent arrivals are the ones still worth knowing
     * about right now. This is the one deliberate behaviour change from the
     * Android copy this was extracted from, not a transcription - that copy
     * sorted ascending and kept the tail (`takeLast`), which preserves
     * ascending order and so posted oldest-first. The cap exists precisely
     * because a backlog is not worth reading in full, so the item most
     * worth seeing should not sit under four older ones.
     */
    fun planCatchUp(
        events: List<PlaceEvent>,
        myUsername: String,
        nowMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
        cap: Int = NOTIFY_CAP,
    ): CatchUpPlan {
        val relevant = events
            .filter { it.username != myUsername }
            .filter { nowMs - it.tsMs <= staleAfterMs }
            .sortedByDescending { it.tsMs }
        if (relevant.size <= cap) return CatchUpPlan(relevant, 0)
        return CatchUpPlan(relevant.take(cap), relevant.size - cap)
    }

    /**
     * Circles that currently want catch-up/live delivery: accepted
     * membership, plus this device's own per-circle toggle. Both platforms
     * compute this today - Android in `CircleNotifyService.refreshNotifyCircles`,
     * iOS inline in `runCatchUpSweep`.
     *
     * [notifyArrivals] defaults to [Settings.notifyArrivals], which itself
     * defaults to **on** for a key nobody has ever written - see its own doc.
     * That means this filter can never exclude a circle nobody has touched;
     * only an explicit opt-out does. Taken as a parameter, rather than this
     * function calling [Settings] directly, so it stays callable from
     * `commonTest` with a literal lambda - [Settings.notifyArrivals] reads a
     * platform key-value store that needs `Settings.init()` (and, on
     * Android, a real `Context`) to have run first, neither of which a unit
     * test can provide.
     */
    fun circlesWantingDelivery(
        circles: List<Group>,
        notifyArrivals: (String) -> Boolean = Settings::notifyArrivals,
    ): Set<String> =
        circles.filter { it.status == "accepted" && notifyArrivals(it.id) }
            .map { it.id }
            .toSet()
}
