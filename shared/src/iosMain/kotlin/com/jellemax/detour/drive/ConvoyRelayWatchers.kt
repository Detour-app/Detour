package com.jellemax.detour.drive

import com.jellemax.detour.data.AudioChunkWatcher
import com.jellemax.detour.data.BoolWatcher
import com.jellemax.detour.data.FriendPositionsWatcher
import com.jellemax.detour.data.GroupSpinWatcher
import com.jellemax.detour.data.OptionalStringWatcher
import com.jellemax.detour.data.PlaceEventWatcher
import com.jellemax.detour.data.SpinVotesWatcher
import com.jellemax.detour.data.StringSetWatcher

/**
 * Typed Swift-facing watchers over one [ConvoyRelay]'s flows - the iosMain
 * counterpart of [SectionAverageHolder.readings], and for the identical
 * reason stated there: every `Watcher` subclass's constructor is `internal`
 * (see `FlowWatcher.kt`'s own doc), so Swift cannot wrap a flow itself, and
 * unlike `Settings`/`Auth`/the `*Store`s that back `FlowWatcher.kt`'s other
 * factory objects, [ConvoyRelay] has no commonMain singleton to read from -
 * it is constructed by Swift (`ConvoyLiveClient.swift`), one per app process,
 * per [ConvoyRelay]'s own class doc. So this takes the instance in rather
 * than reaching for one, the same relationship [SectionAverageHolder] has to
 * [SectionAverageTracker].
 *
 * One method per flow, matching how `app/.../net/ConvoyLiveClient.kt`
 * exposes each of [ConvoyRelay]'s flows individually (`val peers:
 * StateFlow<...> get() = relay.peers`, etc.) rather than combined into one
 * state object the way `FriendsStateWatcher`/`ConvoysStateWatcher` wrap a
 * whole store - there is no existing combined state type for a convoy to
 * reuse, and inventing one would mean touching commonMain for a shape only
 * this constructor needs.
 */
class ConvoyRelayWatchers(private val relay: ConvoyRelay) {
    fun connected() = BoolWatcher(relay.connected)
    fun peers() = FriendPositionsWatcher(relay.peers)
    fun talking() = StringSetWatcher(relay.talking)
    fun spinOffer() = GroupSpinWatcher(relay.spinOffer)
    fun spinVotes() = SpinVotesWatcher(relay.spinVotes)
    fun lastError() = OptionalStringWatcher(relay.lastError)
    fun audioChunks() = AudioChunkWatcher(relay.audioChunks)
    fun placeEvents() = PlaceEventWatcher(relay.placeEvents)
}
