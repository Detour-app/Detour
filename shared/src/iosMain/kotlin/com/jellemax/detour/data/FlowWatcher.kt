package com.jellemax.detour.data

import com.jellemax.detour.drive.FriendPosition
import com.jellemax.detour.drive.GroupSpin
import com.jellemax.detour.drive.IncomingAudioChunk
import com.jellemax.detour.drive.SectionAverageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lets Swift observe a Kotlin [StateFlow].
 *
 * Two problems, one solution. Collecting a flow at all needs a coroutine, which
 * Swift cannot start; and Kotlin/Native erases a generic's type argument on the
 * way to Objective-C, so `StateFlow<Boolean>.value` would reach Swift as a
 * boxed `KotlinBoolean` and a `StateFlow<List<SavedPlace>>` as an untyped
 * array.
 *
 * So the callback carries **no payload** and the value is read back off a
 * concretely-typed property instead. `Boolean` stays `Bool`, `Float` stays
 * `Float`, `List<SavedPlace>` stays `[SavedPlace]`, and no SwiftUI screen
 * unboxes anything. One subclass per type is more lines here in exchange for
 * removing a cast from every binding in the app.
 */
abstract class Watcher {

    protected val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    /** Runs [onChange] now and on every later emission. A second call replaces
     *  the first subscription. */
    fun watch(onChange: () -> Unit) {
        job?.cancel()
        job = scope.launch { collect(onChange) }
    }

    protected abstract suspend fun collect(onChange: () -> Unit)

    /** Must be called when the observing view goes away. */
    fun cancel() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}

class BoolWatcher internal constructor(private val flow: StateFlow<Boolean>) : Watcher() {
    var value: Boolean = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class FloatWatcher internal constructor(private val flow: StateFlow<Float>) : Watcher() {
    var value: Float = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class IntWatcher internal constructor(private val flow: StateFlow<Int>) : Watcher() {
    var value: Int = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class StringWatcher internal constructor(private val flow: StateFlow<String>) : Watcher() {
    var value: String = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

/** This device's own account id — see [Auth.resolveRiderId]. Not a
 *  [StringWatcher] wrapping [Settings.authRiderId] directly: that flow is
 *  typed [RiderId], and a value class buys nothing at the Swift boundary —
 *  Swift gets no compile-time check either way — while Kotlin/Native's
 *  Objective-C export can silently drop a declaration whose signature uses an
 *  inline value class. So this unwraps to the [String] Swift actually gets,
 *  same shape as [StringWatcher]. */
class RiderIdWatcher internal constructor(
    private val flow: StateFlow<RiderId>,
) : Watcher() {
    var value: String = flow.value.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it.value; onChange() }
}

class TravelModeWatcher internal constructor(
    private val flow: StateFlow<TravelMode>,
) : Watcher() {
    var value: TravelMode = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class RouteColorWatcher internal constructor(
    private val flow: StateFlow<Settings.RouteColor>,
) : Watcher() {
    var value: Settings.RouteColor = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class SavedPlacesWatcher internal constructor(
    private val flow: StateFlow<List<SavedPlace>>,
) : Watcher() {
    var value: List<SavedPlace> = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class SavedRoutesWatcher internal constructor(
    private val flow: StateFlow<List<SavedRoute>>,
) : Watcher() {
    var value: List<SavedRoute> = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class TracesWatcher internal constructor(
    private val flow: StateFlow<List<List<LatLon>>>,
) : Watcher() {
    var value: List<List<LatLon>> = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

/**
 * The trajectcontrole average and the posted limit it is judged against.
 *
 * One subclass and not two because [SectionAverageTracker.Reading] carries both
 * numbers — which is the whole reason the register's decision 2 asked for them
 * as one value. Handed out by `SectionAverageHolder.readings()`, not by a
 * factory object here: unlike a setting, there is no single flow to observe,
 * only whatever the screen's own holder is stepping.
 */
class SectionReadingWatcher internal constructor(
    private val flow: StateFlow<SectionAverageTracker.Reading>,
) : Watcher() {
    var value: SectionAverageTracker.Reading = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class FriendsStateWatcher internal constructor(
    private val flow: StateFlow<FriendsState>,
) : Watcher() {
    var value: FriendsState = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class ConvoysStateWatcher internal constructor(
    private val flow: StateFlow<ConvoysState>,
) : Watcher() {
    var value: ConvoysState = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

class CirclesStateWatcher internal constructor(
    private val flow: StateFlow<CirclesState>,
) : Watcher() {
    var value: CirclesState = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

// --- ConvoyRelay's own flows ---------------------------------------------
//
// One subclass per element type, same rule as everything above - added for
// `iosApp/Detour/ConvoyLiveClient.swift`'s move onto the shared
// `com.jellemax.detour.drive.ConvoyRelay`. Handed out by
// `com.jellemax.detour.drive.ConvoyRelayWatchers`, not by a factory object
// here: `ConvoyRelay` is constructed by Swift (there is no commonMain
// singleton the way `Settings`/`Auth`/the `*Store`s are), so the wrapping
// class has to live next to `ConvoyRelay` itself rather than here - see its
// own doc.

/** [ConvoyRelay.peers] keys on [RiderId] now (#133); Swift still gets a
 *  `[String: FriendPosition]`, same reasoning as [RiderIdWatcher] above -
 *  the value class checks nothing Swift can also check, and a signature
 *  that used it could vanish from the generated header instead of erroring. */
class FriendPositionsWatcher internal constructor(
    private val flow: StateFlow<Map<RiderId, FriendPosition>>,
) : Watcher() {
    var value: Map<String, FriendPosition> = flow.value.mapKeys { it.key.value }
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it.mapKeys { e -> e.key.value }; onChange() }
}

/** Backs [ConvoyRelay.talking] - a [RiderId] set since #133, unwrapped to
 *  `Set<String>` for Swift for the same reason as [FriendPositionsWatcher]
 *  just above. */
class StringSetWatcher internal constructor(
    private val flow: StateFlow<Set<RiderId>>,
) : Watcher() {
    var value: Set<String> = flow.value.map { it.value }.toSet()
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it.map { r -> r.value }.toSet(); onChange() }
}

class GroupSpinWatcher internal constructor(
    private val flow: StateFlow<GroupSpin?>,
) : Watcher() {
    var value: GroupSpin? = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

/** Backs [ConvoyRelay.spinVotes] - a [RiderId]-keyed tally since #133,
 *  unwrapped to `Map<String, Int>` for Swift for the same reason as
 *  [FriendPositionsWatcher] above. */
class SpinVotesWatcher internal constructor(
    private val flow: StateFlow<Map<RiderId, Int>>,
) : Watcher() {
    var value: Map<String, Int> = flow.value.mapKeys { it.key.value }
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it.mapKeys { e -> e.key.value }; onChange() }
}

class OptionalStringWatcher internal constructor(
    private val flow: StateFlow<String?>,
) : Watcher() {
    var value: String? = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

/** Bridges a hot event stream rather than a value to read back at any
 *  moment - [Watcher.collect] only ever needed a [kotlinx.coroutines.flow.Flow],
 *  and a [SharedFlow] is one, so the same shape as every watcher above
 *  serves here too. [value] starts `null` rather than off [flow]'s own
 *  value - a `SharedFlow` has none - since nothing has "just happened" yet
 *  at construction time. */
class AudioChunkWatcher internal constructor(
    private val flow: SharedFlow<IncomingAudioChunk>,
) : Watcher() {
    var value: IncomingAudioChunk? = null
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

/** Same shape as [AudioChunkWatcher] - see its doc - for a `place_event`
 *  arriving on the relay rather than a `ptt_audio` chunk. */
class PlaceEventWatcher internal constructor(
    private val flow: SharedFlow<RelayPlaceEvent>,
) : Watcher() {
    var value: RelayPlaceEvent? = null
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}

/** The settings a SwiftUI screen binds to. */
object SettingsFlows {
    fun tripMode() = TravelModeWatcher(Settings.tripMode)
    fun autoDetectDrives() = BoolWatcher(Settings.autoDetectDrives)
    fun fogEnabled() = BoolWatcher(Settings.fogEnabled)
    fun fogRadiusMeters() = FloatWatcher(Settings.fogRadiusMeters)
    fun defaultZoom() = FloatWatcher(Settings.defaultZoom)
    fun avoidHighways() = BoolWatcher(Settings.avoidHighways)
    fun avoidSmallRoads() = BoolWatcher(Settings.avoidSmallRoads)
    fun shareFog() = BoolWatcher(Settings.shareFog)
    fun geocoderPublicFallback() = BoolWatcher(Settings.geocoderPublicFallback)
    fun voiceGuidance() = BoolWatcher(Settings.voiceGuidance)
    fun routeColor() = RouteColorWatcher(Settings.routeColor)
    fun authUsername() = StringWatcher(Settings.authUsername)
    fun authRiderId() = RiderIdWatcher(Settings.authRiderId)

    /** The session, for the one thing iOS asks of it: whether there is one.
     *  Follows the refresh token rather than the access token, because that is
     *  what survives between requests — see [Auth]. */
    fun authToken() = StringWatcher(Settings.refreshToken)
}

/**
 * The session's own lifecycle signal — not a per-screen setting, so not part
 * of [SettingsFlows]. [Auth.sessionEpoch] is `internal`, but that only scopes
 * it to this module (`shared`); wrapping it in an [IntWatcher] here exports
 * nothing wider than the watcher classes above already do. Ties platform
 * code that outlives [Auth.clear]'s own reach — anything outside
 * `commonMain` — to the session rather than to any one button, the way
 * `ConvoyLiveClient.swift`'s session watcher does.
 */
object AuthFlows {
    fun sessionEpoch() = IntWatcher(Auth.sessionEpoch)
}

/** Stores whose changes a screen needs to react to. */
object StoreFlows {
    fun savedPlaces() = SavedPlacesWatcher(SavedPlaces.places)
    fun routes() = SavedRoutesWatcher(RouteStore.routes)
    fun traceVersion() = IntWatcher(TraceStore.version)
    fun friendFog() = TracesWatcher(FriendFog.traces)
    fun pendingResetToken() = StringWatcher(PendingReset.token)
}

/**
 * The feature stores a SwiftUI screen binds to.
 *
 * One watcher per store rather than one per field: each distinct element type
 * costs a subclass above (see this file's header), and a coarse state object
 * keeps that at three classes instead of a dozen.
 */
object FeatureFlows {
    fun friends() = FriendsStateWatcher(FriendsStore.state)
    fun convoys() = ConvoysStateWatcher(ConvoysStore.state)
    fun circles() = CirclesStateWatcher(CirclesStore.state)
}

/**
 * One-shot typed reads of the same settings, for the code paths that sample a
 * value rather than observe it (the trip recorder, mostly).
 */
object SettingsValues {
    val tripMode: TravelMode get() = Settings.tripMode.value
    val autoDetectDrives: Boolean get() = Settings.autoDetectDrives.value
    val avoidHighways: Boolean get() = Settings.avoidHighways.value
    val avoidSmallRoads: Boolean get() = Settings.avoidSmallRoads.value
    val voiceGuidance: Boolean get() = Settings.voiceGuidance.value
    val routeColor: Settings.RouteColor get() = Settings.routeColor.value
    val shareFog: Boolean get() = Settings.shareFog.value
    val fogEnabled: Boolean get() = Settings.fogEnabled.value
    val fogRadiusMeters: Float get() = Settings.fogRadiusMeters.value
    val defaultZoom: Float get() = Settings.defaultZoom.value
    val leanOffsetDeg: Float get() = Settings.leanOffsetDeg.value
    val authToken: String get() = Settings.refreshToken.value
    val authUsername: String get() = Settings.authUsername.value
    // Swift gets the primitive deliberately — see [RiderIdWatcher]'s doc:
    // [RiderId]'s compile-time safety has no Swift-side counterpart to pay
    // for it, and an inline value class can be dropped silently from the
    // generated Objective-C header.
    val authRiderId: String get() = Settings.authRiderId.value.value
}

// --- Model properties that hand Swift the id as a string -----------------
//
// Same reasoning as `RiderIdWatcher`/`SettingsValues.authRiderId` above, just
// for a value read once off a model instead of collected off a flow:
// Kotlin/Native's Objective-C export does not give `RiderId` a Swift-visible
// type at all — a property typed `RiderId` arrives in Swift erased to `Any`,
// with no `.value` to call on it and no way to spell `RiderId` there to cast
// it back. A *parameter* typed `RiderId` is a different story — Kotlin
// already lowers a value-class parameter to its underlying representation at
// the ABI boundary, so `GroupsKt.handleFor(riderId:)`,
// `ConvoyRelay.sendSpinVote(myId:)` and the rest already take the plain
// `String` a caller has on hand; passing one of these values straight
// through needs no accessor and no `RiderId(value:)` wrapper, which is
// itself unconstructible from Swift for the same reason its properties are
// unreadable. Only *reading* the id back off one of these five model types
// needs the unwrap below.

/** See this file's "Model properties..." section above. */
val GroupMember.riderIdValue: String get() = id.value

/** See this file's "Model properties..." section above. */
val RiderRef.idValue: String get() = id.value

/** See this file's "Model properties..." section above. */
val CirclePlace.ownerIdValue: String get() = ownerId.value

/** See this file's "Model properties..." section above. */
val PlaceEvent.riderIdValue: String get() = riderId.value

/** See this file's "Model properties..." section above. */
val IncomingAudioChunk.riderIdValue: String get() = riderId.value

/**
 * Values that exist in Kotlin but not in the Objective-C header.
 *
 * `enum.entries` has no ObjC representation at all — Kotlin/Native exports the
 * entries as individual properties and nothing that enumerates them — and a
 * `const val` inside an object crosses as a static whose spelling depends on
 * the compiler version. Both are one-liners here rather than a guess at the
 * generated name in every SwiftUI file that needs to build a Picker.
 */
object Enums {
    val travelModes: List<TravelMode> = TravelMode.entries.toList()
    val badgeKinds: List<BadgeKind> = BadgeKind.entries.toList()
    val routeColors: List<Settings.RouteColor> = RouteColors.all

    /** Spelling an enum *entry* in Swift means trusting Kotlin/Native's name
     *  mangling for it; naming the one default here does not. */
    val defaultRouteColor: Settings.RouteColor = Settings.RouteColor.THEME

    val minZoom: Float = Settings.DEFAULT_ZOOM_MIN
    val maxZoom: Float = Settings.DEFAULT_ZOOM_MAX
    val defaultFogRadius: Float = Settings.FOG_RADIUS_DEFAULT

    /** The radius [SpeedCameras.near] prefetches. Swift needs it twice — the
     *  call takes it explicitly, since exported functions carry no default
     *  arguments, and "have I driven near the edge of what I hold" has to be
     *  measured against the same number the fetch used. */
    val cameraPrefetchRadiusMeters: Double = SpeedCameras.PREFETCH_RADIUS_M

    /** [CircleNotifyPolicy.planCatchUp]'s two policy numbers, for the same
     *  reason as [cameraPrefetchRadiusMeters]: they are `const val`s inside an
     *  object, whose exported spelling is not stable, and Swift has to pass
     *  them explicitly because an exported function carries no default
     *  arguments. Read from here rather than retyped in Swift — they were two
     *  of the ten hand-copied constants (five values, two languages) the
     *  shared policy exists to make one. */
    val circleCatchUpCap: Int = CircleNotifyPolicy.NOTIFY_CAP
    val circleStaleAfterMs: Long = CircleNotifyPolicy.STALE_AFTER_MS

    /** The cadence `CircleSync.loop` seeds its first sleep with — the last of
     *  those ten. Only the first: every sleep after is whatever
     *  [CirclePresence.tick] returned, so this is the one place the 2-minute
     *  number could quietly drift from the shared one if Swift kept its own
     *  copy. */
    val circleActiveIntervalMs: Long = CirclePresence.ACTIVE_INTERVAL_MS

    /** How many random bytes `SignIn.swift` must draw for [Oidc.begin]. Named
     *  here for the same reason the rest of this object exists: a `const val`
     *  in an object has no stable exported spelling. */
    val oidcEntropyBytes: Int = Oidc.ENTROPY_BYTES
}
