package com.jellemax.detour.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

class TravelModeWatcher internal constructor(
    private val flow: StateFlow<TravelMode>,
) : Watcher() {
    var value: TravelMode = flow.value
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
    fun authUsername() = StringWatcher(Settings.authUsername)
    fun authToken() = StringWatcher(Settings.authToken)
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
 * One-shot typed reads of the same settings, for the code paths that sample a
 * value rather than observe it (the trip recorder, mostly).
 */
object SettingsValues {
    val tripMode: TravelMode get() = Settings.tripMode.value
    val autoDetectDrives: Boolean get() = Settings.autoDetectDrives.value
    val avoidHighways: Boolean get() = Settings.avoidHighways.value
    val avoidSmallRoads: Boolean get() = Settings.avoidSmallRoads.value
    val voiceGuidance: Boolean get() = Settings.voiceGuidance.value
    val shareFog: Boolean get() = Settings.shareFog.value
    val fogEnabled: Boolean get() = Settings.fogEnabled.value
    val fogRadiusMeters: Float get() = Settings.fogRadiusMeters.value
    val defaultZoom: Float get() = Settings.defaultZoom.value
    val leanOffsetDeg: Float get() = Settings.leanOffsetDeg.value
    val authToken: String get() = Settings.authToken.value
    val authUsername: String get() = Settings.authUsername.value
}

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

    val minZoom: Float = Settings.DEFAULT_ZOOM_MIN
    val maxZoom: Float = Settings.DEFAULT_ZOOM_MAX
    val defaultFogRadius: Float = Settings.FOG_RADIUS_DEFAULT
}
