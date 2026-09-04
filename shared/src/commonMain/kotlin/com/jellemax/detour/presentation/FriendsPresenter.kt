package com.jellemax.detour.presentation

import com.jellemax.detour.data.Account
import com.jellemax.detour.data.FriendsStore
import com.jellemax.detour.data.RiderRef

/**
 * Kicks off the Friends screen's two loads and nothing else.
 *
 * [FriendsStore] already publishes its own `StateFlow<FriendsState>`
 * (`com.jellemax.detour.data.FriendsState` — not [FriendsBoardState]; see that
 * type's KDoc for why the two are named apart), and `respond`/`refreshOwn`
 * each write straight through it — so the screen collects [FriendsStore.state]
 * directly and calls [friendsBoardStateFrom] on the render path, rather than
 * reading a cached snapshot off this presenter. This is the same split as
 * [PlacesPresenter] and [RoutesPresenter]: a presenter in front of a *mutable*
 * store publishes no rows of its own, because a cached copy goes stale the
 * instant a mutation lands underneath it — the mistake batch 2's final review
 * caught, where a published snapshot actively misled iOS, defeating the whole
 * point of the shared layer. There is deliberately no `state` property here:
 * everything this class could publish, the store already does, and better.
 *
 * [refresh] blocks on disk and CPU: [FriendsStore.refreshOwn] walks every
 * trace and trip on disk (its own doc, FriendsStore.kt:131-144, is explicit
 * about why — `Coverage.compute()` and `BadgeStore.stats`/`refresh` are not
 * `suspend`, because commonMain has no `Dispatchers` to hop off of). Neither
 * that store nor this presenter can switch dispatcher themselves, so the
 * off-main-thread guarantee rests entirely on the CALLER wrapping this call in
 * `withContext(Dispatchers.IO)` on Android, `Task.detached` (or equivalent) on
 * iOS. Called on the main dispatcher, this janks the UI for a full trace/trip
 * walk, same as [CoveragePresenter.refresh].
 */
class FriendsPresenter {
    suspend fun refresh() {
        FriendsStore.reloadIfStale()
        FriendsStore.refreshOwn(RiderRef(Account.riderId.value, Account.username.value))
    }
}
