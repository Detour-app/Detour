import CoreLocation
import DetourShared

/// Speed camera markers, prefetched the same way `SectionAverageModel`
/// prefetches sections. `CameraPrefetch` in `:shared` is the one cadence
/// object for both — its own KDoc says every consumer keeps its own copy of
/// the state, the same way the Android phone (`RetainedMap.cameraPrefetch`)
/// and the head unit (`car/NavScreen.kt`) each hold theirs — so `MapScreen`
/// and `NavScreen` each own a separate instance of this rather than sharing
/// one.
@MainActor
final class CameraPrefetchModel: ObservableObject {

    @Published private(set) var cameras: [SpeedCameras.Camera] = []

    private var state = CameraPrefetch.State()
    private var fetching = false

    func update(with fix: CLLocation) {
        let at = LatLon(lat: fix.coordinate.latitude, lon: fix.coordinate.longitude)
        guard !fetching,
              CameraPrefetch.shared.needsFetch(state: state, at: at, nowMs: nowMs())
        else { return }

        state = CameraPrefetch.shared.fetchStarted(state: state, nowMs: nowMs())
        fetching = true
        Task {
            defer { fetching = false }
            // near() returns nil on any network error, so a failed fetch keeps
            // whatever markers we already have instead of blanking the map.
            let result = try? await SpeedCameras.shared.near(
                center: at, radiusMeters: Enums.shared.cameraPrefetchRadiusMeters)
            state = CameraPrefetch.shared.fetched(state: state, result: result, center: at)
            if let result { cameras = result.cameras }
        }
    }
}
