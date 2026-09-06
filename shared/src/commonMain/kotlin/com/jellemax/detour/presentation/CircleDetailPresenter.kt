package com.jellemax.detour.presentation

import com.jellemax.detour.data.CirclesStore

/**
 * Kicks off the circle-detail pane's one load and nothing else.
 *
 * [CirclesStore] already publishes its own `StateFlow<CirclesState>`
 * (`com.jellemax.detour.data.CirclesState` — not [CircleDetailState]; see
 * that type's KDoc for why the two are named apart), and every detail
 * mutation this pane can make — share/unshare a place, invite, leave, toggle
 * sharing — reloads it directly. So the screen collects [CirclesStore.state]
 * and calls [circleDetailStateFrom] on the render path, rather than reading
 * a cached snapshot off this presenter. Same split as [CirclesListPresenter]
 * — see its KDoc for the full reasoning: a presenter in front of a *mutable*
 * store publishes no rows of its own, because a cached copy goes stale the
 * instant a mutation lands underneath it. There is deliberately no `state`
 * property here: everything this class could publish, the store already
 * does, and better.
 *
 * [open] is the same pairing `CircleDetailScreen`'s own effect already did
 * on entry: `selectOnly`, which only records which circle is open, then
 * `reloadIfStale`, which re-fetches the circle *list* if it's gone stale —
 * never plain `select`, which would also kick a places/events fetch here on
 * top of the one the detail section's own effect already triggers, doubling
 * that request the moment both mounted together.
 */
class CircleDetailPresenter {
    suspend fun open(circleId: String) {
        CirclesStore.selectOnly(circleId)
        CirclesStore.reloadIfStale()
    }
}
