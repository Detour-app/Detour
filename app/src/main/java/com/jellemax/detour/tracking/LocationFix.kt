package com.jellemax.detour.tracking

/**
 * Which pipeline produced a [LocationFix]: the platform's own answer, a real
 * fix arriving while a mock-location provider is designated alongside it
 * ([ReplayFixGate]'s `isMock` check used to read this straight off
 * `android.location.Location`), or [ReplayLocationSource]'s in-app port
 * (`location.provider == "detour-replay"`, before this type existed).
 * [FusedLocationSource] is the only place [MOCK_PROVIDER] gets decided —
 * [ReplayLocationSource] never routes through it, so its fixes are always
 * [REPLAY_PORT].
 */
internal enum class FixOrigin { PLATFORM, MOCK_PROVIDER, REPLAY_PORT }

/**
 * What a [LocationSource] hands the ingest path: a plain copy of the handful
 * of fields `TripTrackingService` actually reads off `android.location.Location`.
 *
 * Exists so a fake [LocationSource] can be built for a test (#312) —
 * `android.location.Location` is the android.jar stub under plain JUnit4 (no
 * Robolectric, no `androidTest` in this repo), so constructing one throws, and
 * with the port's currency being that type, nothing could hand it a fix to test
 * with. [FusedLocationSource] and [ReplayLocationSource] each build one at their
 * own boundary; nothing past that boundary names `android.location.Location`
 * again.
 */
internal data class LocationFix(
    val lat: Double,
    val lon: Double,
    /** Null when the source had none to report ([android.location.Location.hasSpeed]),
     *  not a real zero-speed measurement. */
    val speedMps: Float?,
    /** Null when the source had none to report ([android.location.Location.hasBearing]). */
    val bearingDeg: Float?,
    val accuracyMeters: Float,
    /** Provider wall-clock UTC ([android.location.Location.getTime]). */
    val timeMs: Long,
    /** Monotonic, on [android.os.SystemClock.elapsedRealtime]'s basis. */
    val elapsedRealtimeNanos: Long,
    val origin: FixOrigin,
)
