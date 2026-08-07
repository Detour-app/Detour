import Foundation
import CoreLocation
import CoreMotion
import DetourShared

/// Live numbers for a running trip. Mirrors `TripStats` on the Android side.
struct TripStats: Equatable {
    var startTimeMs: Int64
    /// Fixed when the trip began. Switching mode tabs mid-ride must not change
    /// which stats the running trip is recording, or claim to have recorded.
    var mode: TravelMode
    var durationMs: Int64 = 0
    var distanceMeters: Double = 0
    var currentSpeedMps: Double = 0
    var topSpeedMps: Double = 0
    var currentLeanAngleDeg: Double = 0
    var maxLeanAngleDeg: Double = 0
    var currentGForce: Double = 0
    var maxGForce: Double = 0
}

/// Records a ride: distance, speed, lean, g-force, and the decimated trace the
/// fog of war is drawn from.
///
/// The Android counterpart is a foreground service that scales its location
/// appetite across four modes (sleep/idle/probe/trip) driven by activity
/// recognition. iOS has no foreground service and no equivalent of passive
/// fixes, so the tiering collapses to two things it does have: a distance
/// filter that widens when nothing is happening, and `CMMotionActivityManager`
/// for the automotive hint that opens a probe window. The thresholds below are
/// the Android ones unchanged — they were tuned against real rides, and the
/// signals they read (speed, accuracy, sustained runs) mean the same on both.
///
/// Everything decided here that isn't platform plumbing — where a trace point
/// goes, what a trip is worth saving, which badges that earns — is `:shared`.
@MainActor
final class TripRecorder: NSObject, ObservableObject {

    // MARK: Auto-detection thresholds (identical to the Android service)

    private static let fastSpeedMps = 7.0            // ~25 km/h, no vehicle hint
    private static let probeSpeedMps = 4.0           // ~14 km/h, automotive was seen
    private static let fastFixesToStart = 3
    private static let minFastRunMs: Int64 = 8_000
    private static let minFastRunMeters = 120.0
    private static let maxStartAccuracyM = 25.0
    private static let probeWindowMs: Int64 = 3 * 60_000
    private static let stationaryEndMs: Int64 = 5 * 60_000
    private static let minAutoTripMeters = 500.0
    private static let walkAvgMaxMps = 2.5           // ~9 km/h
    private static let walkMinJudgeMs: Int64 = 90_000
    private static let walkTopMaxMps = 6.0           // ~22 km/h

    private static let maxPlausibleLeanDeg = 65.0
    private static let leanEmaAlpha = 0.3
    private static let maxLeanSlewDeg = 20.0
    private static let minLeanSpeedMps = 3.0         // ~11 km/h
    private static let gEmaAlpha = 0.15
    private static let maxGSlew = 0.5
    private static let maxPlausibleG = 2.0

    /// Trace spacing, and the jump past which a segment is closed rather than
    /// bridged with a straight line through wherever location was off.
    private static let traceSpacingMeters = 25.0
    private static let traceBreakMeters = 500.0
    private static let traceFlushCount = 200

    private static let municipalityCooldownMs: Int64 = 60_000

    // MARK: Published state

    @Published private(set) var stats: TripStats?
    /// The trace being written right now, so the map can draw it live rather
    /// than only once a trip is saved.
    @Published private(set) var liveTrace: [CLLocationCoordinate2D] = []
    @Published private(set) var lastFix: CLLocation?
    /// Badges that crossed their threshold when the last trip was saved.
    @Published var newlyEarned: [BadgeDef] = []

    // MARK: Dependencies

    private let manager = CLLocationManager()
    private let motion = CMMotionManager()
    private let activity = CMMotionActivityManager()

    // MARK: Recording state

    private var tracePoints: [TraceStoreTracePoint] = []
    /// Deepest lean since the last trace point, sign kept: points are 25 m
    /// apart, which is a whole corner at town speed, and the deepest lean
    /// through it is the interesting number.
    private var segmentPeakLeanDeg = 0.0
    private var leanTracked = false
    private var currentLeanDeg = 0.0
    private var maxLeanDeg = 0.0
    /// Seeded at 1.0, not 0: a stationary accelerometer reads gravity, so the
    /// magnitude idles at 1 g. Starting from 0 would put the first real sample
    /// a full 1 g past the slew gate, which would then reject it.
    private var currentG = 1.0
    private var maxG = 1.0

    private var previousLocation: CLLocation?
    private var destination: LatLon?
    private var startedAutomatically = false

    private var fastFixes = 0
    private var fastRunStartMs: Int64?
    private var fastRunMeters = 0.0
    private var probeUntilMs: Int64?
    private var movingSinceMs: Int64?
    private var lastMunicipalityLookupMs: Int64 = 0

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .automotiveNavigation
    }

    var isRecording: Bool { stats != nil }

    // MARK: Lifecycle

    /// Idle watching: enough to extend the fog trace and notice a drive start,
    /// without the battery cost of navigation-grade fixes.
    func startWatching() {
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 20
        manager.startUpdatingLocation()
        startActivityUpdates()
    }

    func startTrip(destination: LatLon? = nil, automatic: Bool = false) {
        guard stats == nil else { return }
        self.destination = destination
        self.startedAutomatically = automatic

        stats = TripStats(startTimeMs: nowMs(), mode: resolvedMode())
        previousLocation = nil
        maxLeanDeg = 0
        maxG = 1.0
        segmentPeakLeanDeg = 0

        // Navigation-grade fixes, and the background mode that keeps them
        // coming with the screen off — the closest thing iOS has to Android's
        // location foreground service.
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.requestAlwaysAuthorization()
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()

        startMotionSensors(mode: stats!.mode)
    }

    func endTrip() {
        guard let finished = stats else { return }
        stopMotionSensors()
        flushTrace()

        let elapsed = nowMs() - finished.startTimeMs
        // An auto-detected trip that never went anywhere was a false positive:
        // a bus ride past the house, or a loose fix drifting indoors.
        let worthKeeping = !startedAutomatically
            || finished.distanceMeters >= Self.minAutoTripMeters

        stats = nil
        destination = nil
        manager.allowsBackgroundLocationUpdates = false
        startWatching()

        guard worthKeeping else { return }

        let trip = Trip(
            startTimeMs: finished.startTimeMs,
            endTimeMs: finished.startTimeMs + elapsed,
            distanceMeters: finished.distanceMeters,
            topSpeedMps: finished.topSpeedMps,
            maxLeanAngleDeg: finished.maxLeanAngleDeg,
            maxGForce: finished.maxGForce,
            destinationLat: destination.map { KotlinDouble(value: $0.lat) },
            destinationLon: destination.map { KotlinDouble(value: $0.lon) },
            mode: finished.mode
        )
        TripStore.shared.save(trip: trip)

        Task {
            // Coverage and badges both read the whole trip/trace history, so
            // they run after the save, not during it.
            let coverage = Coverage.shared.compute()
            let result = BadgeStore.shared.refresh(stats: BadgeStore.shared.stats(coverage: coverage))
            self.newlyEarned = result.newlyEarned
            try? await SyncClient.shared.sync()
        }
    }

    // MARK: Location

    private func onLocation(_ location: CLLocation) {
        // CoreLocation reports -1 for an unknown speed; Android reports 0.
        let speed = max(0, location.speed)
        lastFix = location
        LocationBroadcast.shared.publish(location)

        if stats == nil {
            onIdleLocation(location, speed: speed)
        } else {
            onTripLocation(location, speed: speed)
        }
        previousLocation = location
    }

    /// Not recording: extend the explored trace, watch for a drive starting.
    private func onIdleLocation(_ location: CLLocation, speed: Double) {
        if location.horizontalAccuracy <= 50 {
            addTracePoint(location, speedMps: speed)
        }
        guard SettingsValues.shared.autoDetectDrives else {
            resetStartDetector()
            return
        }
        // A loose fix can drift 100 m in a minute while the phone sits indoors,
        // which reads as a comfortable 6 km/h — or, over one bad jump, as 25.
        guard location.horizontalAccuracy <= Self.maxStartAccuracyM else {
            resetStartDetector()
            return
        }

        let probing = probeUntilMs.map { nowMs() < $0 } ?? false
        let threshold = probing ? Self.probeSpeedMps : Self.fastSpeedMps

        guard speed >= threshold else {
            resetStartDetector()
            return
        }

        fastFixes += 1
        if fastRunStartMs == nil { fastRunStartMs = nowMs() }
        if let previous = previousLocation {
            fastRunMeters += location.distance(from: previous)
        }

        // Never one signal: a run has to be long enough in both time and
        // distance, from fixes tight enough to believe.
        let runMs = nowMs() - (fastRunStartMs ?? nowMs())
        if fastFixes >= Self.fastFixesToStart,
           runMs >= Self.minFastRunMs,
           fastRunMeters >= Self.minFastRunMeters {
            resetStartDetector()
            startTrip(automatic: true)
        }
    }

    private func onTripLocation(_ location: CLLocation, speed: Double) {
        guard var current = stats else { return }

        if let previous = previousLocation {
            let step = location.distance(from: previous)
            // Discard the teleports a bad fix produces; 1 km between
            // consecutive fixes is not a vehicle, it is the radio.
            if step < Self.traceBreakMeters * 2 {
                current.distanceMeters += step
            }
        }
        current.durationMs = nowMs() - current.startTimeMs
        current.currentSpeedMps = speed
        current.topSpeedMps = max(current.topSpeedMps, speed)
        current.currentLeanAngleDeg = currentLeanDeg
        current.maxLeanAngleDeg = maxLeanDeg
        current.currentGForce = currentG
        current.maxGForce = maxG
        stats = current

        addTracePoint(location, speedMps: speed)

        // Stopped for long enough that the ride is over, not a traffic light.
        if speed > 1.0 {
            movingSinceMs = nowMs()
        } else if let since = movingSinceMs, nowMs() - since > Self.stationaryEndMs {
            endTrip()
        }
    }

    private func resetStartDetector() {
        fastFixes = 0
        fastRunStartMs = nil
        fastRunMeters = 0
    }

    // MARK: Trace

    /// Fog-of-war trace, decimated to ~25 m spacing, carrying what the ride was
    /// doing at each point as well as where it was.
    private func addTracePoint(_ location: CLLocation, speedMps: Double) {
        let p = LatLon(lat: location.coordinate.latitude, lon: location.coordinate.longitude)

        if let last = tracePoints.last?.at {
            let gap = RoadRoulette.shared.distanceMeters(a: last, b: p)
            if gap < Self.traceSpacingMeters { return }
            // Big jump (location off for a while): close this segment first.
            if gap > Self.traceBreakMeters { flushTrace() }
        }

        tracePoints.append(TraceStoreTracePoint(
            at: p,
            timeMs: Int64(location.timestamp.timeIntervalSince1970 * 1000),
            speedKmh: speedMps * 3.6,
            leanDeg: leanTracked ? KotlinDouble(value: segmentPeakLeanDeg) : nil
        ))
        segmentPeakLeanDeg = 0

        if tracePoints.count >= Self.traceFlushCount { flushTrace(keepLast: true) }
        liveTrace = tracePoints.map {
            CLLocationCoordinate2D(latitude: $0.at.lat, longitude: $0.at.lon)
        }
        maybeDiscoverMunicipality(p)
    }

    private func flushTrace(keepLast: Bool = false) {
        guard !tracePoints.isEmpty else { return }
        TraceStore.shared.append(trace: tracePoints)
        let last = tracePoints.last
        tracePoints.removeAll()
        if keepLast, let last { tracePoints.append(last) }
        liveTrace = tracePoints.map {
            CLLocationCoordinate2D(latitude: $0.at.lat, longitude: $0.at.lon)
        }
    }

    /// One Overpass query per new gemeente, never per fix: a whole ride through
    /// familiar territory makes zero network requests.
    private func maybeDiscoverMunicipality(_ p: LatLon) {
        let now = nowMs()
        guard now - lastMunicipalityLookupMs >= Self.municipalityCooldownMs else { return }
        guard MunicipalityStore.shared.needsLookup(p: p) else { return }
        lastMunicipalityLookupMs = now
        Task { await MunicipalityStore.shared.discoverQuietly(p: p) }
    }

    // MARK: Motion

    /// Lean and g-force, the numbers that only mean anything on a bike with the
    /// phone mounted upright. `CMDeviceMotion` gives attitude already fused, so
    /// there is no rotation matrix to assemble the way SensorManager needs.
    private func startMotionSensors(mode: TravelMode) {
        // The mode itself says whether roll is worth recording — a car's
        // number is the phone sliding in its cradle, and a bicycle has no
        // engine to produce cornering g worth the sensor.
        leanTracked = mode.tracksLean
        guard mode.tracksMotion, motion.isDeviceMotionAvailable else { return }

        motion.deviceMotionUpdateInterval = 0.2
        motion.startDeviceMotionUpdates(to: .main) { [weak self] data, _ in
            guard let self, let data else { return }
            self.onDeviceMotion(data)
        }
    }

    private func stopMotionSensors() {
        motion.stopDeviceMotionUpdates()
        leanTracked = false
    }

    private func onDeviceMotion(_ data: CMDeviceMotion) {
        // Roll is the mount's tilt off vertical; the user's own calibration
        // offset (bike upright, engine off) comes out of it.
        let offset = Double(SettingsValues.shared.leanOffsetDeg)
        let rawLean = data.attitude.roll * 180 / .pi - offset
        guard abs(rawLean) <= Self.maxPlausibleLeanDeg else { return }
        // Parked and being picked up is not leaning.
        guard (stats?.currentSpeedMps ?? 0) >= Self.minLeanSpeedMps else { return }

        // Slew gate first, EMA second: the EMA only damps a spike, it does not
        // reject one, and a phone knocked off its mount is a spike.
        if abs(rawLean - currentLeanDeg) <= Self.maxLeanSlewDeg {
            currentLeanDeg += Self.leanEmaAlpha * (rawLean - currentLeanDeg)
            if abs(currentLeanDeg) > abs(maxLeanDeg) { maxLeanDeg = currentLeanDeg }
            if abs(currentLeanDeg) > abs(segmentPeakLeanDeg) {
                segmentPeakLeanDeg = currentLeanDeg
            }
        }

        let a = data.userAcceleration
        // userAcceleration excludes gravity, which the Android magnitude
        // includes — add it back so the two platforms record the same number.
        let rawG = sqrt(a.x * a.x + a.y * a.y + a.z * a.z) + 1.0
        if abs(rawG - currentG) <= Self.maxGSlew {
            currentG += Self.gEmaAlpha * (rawG - currentG)
            if currentG <= Self.maxPlausibleG { maxG = max(maxG, currentG) }
        }
    }

    /// The automotive hint that opens a probe window. Android gets this from
    /// activity-recognition transitions; iOS pushes activity updates instead,
    /// so the window is opened on each automotive sample rather than on an
    /// enter/exit pair.
    private func startActivityUpdates() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        activity.startActivityUpdates(to: .main) { [weak self] activity in
            guard let self, let activity, activity.automotive, activity.confidence != .low
            else { return }
            self.probeUntilMs = nowMs() + Self.probeWindowMs
        }
    }

    // MARK: Mode

    /// Which vehicle this is. A walk gives itself away by pace, whatever tab
    /// the user left selected.
    private func resolvedMode() -> TravelMode {
        if let s = stats, s.durationMs > Self.walkMinJudgeMs {
            let avg = s.durationMs > 0 ? s.distanceMeters / (Double(s.durationMs) / 1000) : 0
            if avg < Self.walkAvgMaxMps && s.topSpeedMps < Self.walkTopMaxMps {
                return .walk
            }
        }
        return SettingsValues.shared.tripMode
    }
}

extension TripRecorder: CLLocationManagerDelegate {

    nonisolated func locationManager(
        _ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]
    ) {
        Task { @MainActor in
            for location in locations { self.onLocation(location) }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient indoors; the next fix supersedes it.
    }
}

/// `System.currentTimeMillis()`, which the shared core's timestamps are in.
func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
