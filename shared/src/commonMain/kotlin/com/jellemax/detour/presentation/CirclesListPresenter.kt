package com.jellemax.detour.presentation

import com.jellemax.detour.data.CirclesStore

/**
 * Kicks off the Circles list screen's one load and nothing else.
 *
 * [CirclesStore] already publishes its own `StateFlow<CirclesState>`
 * (`com.jellemax.detour.data.CirclesState` — not [CirclesListState]; see that
 * type's KDoc for why the two are named apart), and every list mutation —
 * create/invite/respond/leave/setSharing — reloads it directly. So the
 * screen collects [CirclesStore.state] and calls [circlesListStateFrom] on
 * the render path, rather than reading a cached snapshot off this presenter.
 * Same split as [FriendsPresenter]/[PlacesPresenter]/[RoutesPresenter]: a
 * presenter in front of a *mutable* store publishes no rows of its own,
 * because a cached copy goes stale the instant a mutation lands underneath
 * it — the mistake batch 2's final review caught, where a published
 * snapshot actively misled iOS, defeating the whole point of the shared
 * layer. There is deliberately no `state` property here: everything this
 * class could publish, the store already does, and better.
 *
 * [refresh] only kicks the *list* load. `CirclesState` also carries a
 * second, independent busy/error pair for the detail pane
 * (`detailBusy`/`detailError`, see CirclesStore.kt:13-19) — opening a
 * circle, sharing a place, all reloaded through `CirclesStore.select`. That
 * pair is a later task's concern, wired from the circle-detail screen, not
 * this presenter.
 */
class CirclesListPresenter {
    suspend fun refresh() {
        CirclesStore.reloadIfStale()
    }
}
