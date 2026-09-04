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
 * it — a published snapshot goes stale and misleads iOS, defeating the whole
 * point of the shared layer. There is deliberately no `state` property here:
 * everything this class could publish, the store already does, and better.
 *
 * One difference from [FriendsPresenter]'s split, though: `FriendsState.lists`
 * is nullable, so the screen can tell "hasn't loaded yet" from "loaded and
 * empty" for free. `CirclesState.circles` has no such signal — it defaults
 * to `emptyList()` — so the screen must check `CirclesState.loadedAtMs`
 * instead (see [CirclesListState]'s consumer in `CirclesScreen.kt` for where
 * that actually matters: an unguarded empty check there shows "No circles
 * yet" for the length of the first load).
 *
 * [refresh] only kicks the *list* load. `CirclesState` also carries a
 * second, independent busy/error pair for the detail pane
 * (`detailBusy`/`detailError`, see CirclesStore.kt:13-19) — opening a
 * circle, sharing a place, all reloaded through `CirclesStore.select`. That
 * pair is wired from the circle-detail screen (see [CircleDetailPresenter]),
 * not this presenter.
 */
class CirclesListPresenter {
    suspend fun refresh() {
        CirclesStore.reloadIfStale()
    }
}
