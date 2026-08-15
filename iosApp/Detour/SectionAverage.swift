import SwiftUI
import CoreLocation
import DetourShared

/// Running average speed through a trajectcontrole (an average-speed section),
/// which is the number the gantry pair is actually measuring — your
/// instantaneous speed between two of them decides nothing.
///
/// The rule is `SectionAverageTracker` in `:shared`, the same step function the
/// phone map and the Android Auto nav screen call, so the three surfaces cannot
/// disagree about where a section starts, when it ends, or what the average is.
/// This file is the prefetch, the model and the chip.
struct SectionAverageChip: View {

    let averageKmh: Double
    let limitKmh: Double?

    /// Over the *section's* posted limit, not the road's: a trajectcontrole
    /// carries its own maxspeed and it is often lower than the signs inside it.
    private var over: Bool {
        guard let limitKmh else { return false }
        return averageKmh > limitKmh
    }

    var body: some View {
        VStack(spacing: 2) {
            // Ø and the 72pt disc match the phone's SectionAverageChip
            // (app/.../ui/MapHud.kt), so the same reading looks the same on
            // both phones in the same cradle.
            Text(String(format: "Ø %.0f", averageKmh))
                .font(.title2.monospacedDigit().weight(.bold))
            Text("avg km/h")
                .font(.caption2)
        }
        .foregroundStyle(over ? Color.white : Color.primary)
        .frame(width: 72, height: 72)
        .background(over ? Color.red : Color.teal.opacity(0.35), in: Circle())
    }
}

/// Drives the shared tracker from the fixes `TripRecorder` is already
/// publishing, and keeps the section data it needs prefetched.
///
/// It observes `TripRecorder.lastFix` rather than opening a second
/// `CLLocationManager`: iOS coalesces two clients into one anyway, and a
/// readout that only works while the recorder happens to be running is not the
/// behaviour the phone has.
@MainActor
final class SectionAverageModel: ObservableObject {

    /// The average and the limit it is judged against, as the one value the
    /// tracker publishes them in — two `@Published` numbers could be read a
    /// frame apart and disagree, which is the shape register decision 2
    /// specifically asked us not to export.
    @Published private(set) var reading: SectionAverageTracker.Reading?

    /// Nil outside a section, and also inside one until enough distance has
    /// accumulated for an average to mean anything — the tracker publishes the
    /// section's limit first and the average a fix or two later.
    var averageKmh: Double? { reading?.averageKmh?.doubleValue }
    var limitKmh: Double? { reading?.limitKmh?.doubleValue }

    /// Refetch once we are within this of the edge of the area we hold, and
    /// never more often than the throttle. The head unit's numbers
    /// (`CAMERA_FETCH_MARGIN_M`, `CAMERA_FETCH_THROTTLE_MS`), because it is the
    /// copy of this prefetch that has a written rationale.
    private static let fetchMarginMeters = 1000.0
    private static let fetchThrottleMs: Int64 = 15_000

    private let core: SectionAverageHolder
    private let watcher: SectionReadingWatcher

    private var sections: [SpeedCameras.Section] = []
    private var sectionsCenter: CLLocation?
    private var lastFetchMs: Int64 = 0
    private var fetching = false

    init() {
        let core = SectionAverageHolder()
        self.core = core
        self.watcher = core.readings()
        // `watch` fires once with the current value as well as on every change.
        watcher.watch { [weak self] in self?.reading = self?.watcher.value }
    }

    /// A watcher holds its subscription for the object's life and `cancel()`
    /// tears down its whole scope, so this is the only place it may happen —
    /// same reasoning as `NavModel.voiceWatch`.
    deinit { watcher.cancel() }

    func update(with fix: CLLocation) {
        prefetch(around: fix)
        core.onFix(
            sections: sections,
            at: LatLon(lat: fix.coordinate.latitude, lon: fix.coordinate.longitude),
            // CoreLocation reports a negative course when it has no usable
            // bearing; the tracker wants nil for that, and would otherwise
            // heading-test a section entry against -1°.
            headingDeg: fix.course >= 0 ? KotlinDouble(value: fix.course) : nil,
            // And -1 for an unknown speed, which the recorder clamps the same way.
            speedMps: max(0, fix.speed),
            nowMs: nowMs()
        )
    }

    /// Keeps the section set current.
    ///
    /// Its own `Task` with an in-flight guard rather than awaited inline: this
    /// is the fix path, and `car/NavScreen.kt`'s `checkCameras` records what
    /// awaiting a slow Overpass mirror here does — a mirror having a slow ten
    /// seconds is normal, a map and a readout that stop for ten seconds at
    /// 100 km/h are not.
    private func prefetch(around fix: CLLocation) {
        let radius = Enums.shared.cameraPrefetchRadiusMeters
        let fromCenter = sectionsCenter.map { fix.distance(from: $0) } ?? .greatestFiniteMagnitude
        guard fromCenter > radius - Self.fetchMarginMeters,
              nowMs() - lastFetchMs > Self.fetchThrottleMs,
              !fetching
        else { return }

        lastFetchMs = nowMs()
        fetching = true
        Task {
            defer { fetching = false }
            // near() returns nil on any network error rather than throwing, so
            // a failed fetch keeps the sections we already hold instead of
            // blanking the readout mid-section.
            guard let result = try? await SpeedCameras.shared.near(
                center: LatLon(lat: fix.coordinate.latitude, lon: fix.coordinate.longitude),
                radiusMeters: radius
            ) else { return }
            sections = result.sections
            sectionsCenter = fix
        }
    }
}
