import Foundation
import CoreLocation
import DetourShared

/// Drives one spin, start to finish, against the shared Kotlin core.
///
/// Everything decision-shaped here — where the destination may land, how the
/// route is asked for, what counts as explored — lives in `:shared` and is the
/// same code the Android app runs. This type only moves values between
/// CoreLocation, that core, and SwiftUI.
///
/// Kotlin's `suspend` functions arrive in Swift as `async throws`, so the
/// background-dispatcher juggling the Android side needed is simply absent.
@MainActor
final class SpinModel: ObservableObject {

    enum State: Equatable {
        case idle
        case spinning
        /// Three rolls are on the map, none picked yet.
        case choosing
        case found(lat: Double, lon: Double, distanceMeters: Double?)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    /// Route geometry for the map overlay, as plain coordinates.
    @Published private(set) var route: [CLLocationCoordinate2D] = []
    /// Where we are headed: a spin result, or a place picked from search.
    @Published private(set) var destination: LatLon?
    /// The full routing result, kept because navigation needs the turn
    /// instructions and the speed limits, not only the line on the map.
    @Published private(set) var routeResult: RouteResult?
    /// The three rolls awaiting a pick. Empty except between a spin landing and
    /// one of them being chosen — `destination` stays nil for exactly that
    /// window, so the map has candidates to draw rather than a destination.
    @Published private(set) var candidates: [RouteCandidate] = []

    /// Radius in metres, matching the Android app's slider range.
    @Published var radiusMeters: Double = 25_000

    /// A destination chosen by hand rather than rolled for. Clears any route
    /// from an earlier spin — that one led somewhere else.
    func setDestination(_ target: LatLon) {
        destination = target
        route = []
        routeResult = nil
        candidates = []
        state = .found(lat: target.lat, lon: target.lon, distanceMeters: nil)
    }

    /// Commits one of the three rolls. Its route came back with the candidate,
    /// so unlike a searched-for place this needs no second routing request.
    func choose(_ candidate: RouteCandidate) {
        candidates = []
        destination = candidate.destination
        routeResult = candidate.route
        route = candidate.route?.polyline.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        } ?? []
        state = .found(
            lat: candidate.destination.lat,
            lon: candidate.destination.lon,
            distanceMeters: candidate.route?.distanceMeters?.doubleValue
        )
    }

    /// Takes a destination that arrived without a route — a convoy's committed
    /// spin — and routes to it. Every member has to ask for their own: the
    /// sharer's polyline is a line from *their* position, and drawing it here
    /// would show a route starting somewhere you are not.
    func setDestinationRouting(
        to target: LatLon, from here: CLLocationCoordinate2D, mode: TravelMode
    ) async {
        setDestination(target)
        let server = RoutingServer.shared.load()
        guard server.usable else { return }
        let result = try? await RoutingClient.shared.route(
            config: server,
            from: LatLon(lat: here.latitude, lon: here.longitude),
            to: target,
            profile: mode.ghProfile,
            avoidHighways: SettingsValues.shared.avoidHighways,
            avoidSmallRoads: SettingsValues.shared.avoidSmallRoads
        )
        // Compared field-wise rather than by identity: another commit or a
        // search may have moved the destination while this was in flight, and
        // that one's route is the one to keep.
        guard let result,
              destination?.lat == target.lat, destination?.lon == target.lon else { return }
        routeResult = result
        route = result.polyline.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        }
        state = .found(
            lat: target.lat, lon: target.lon,
            distanceMeters: result.distanceMeters?.doubleValue)
    }

    /// Drops the rolls without picking one, leaving the map as it was.
    func clearCandidates() {
        candidates = []
        if state == .choosing { state = .idle }
    }

    /// Rolls three candidates and leaves them for the rider to pick between,
    /// the same as the Android spin — the concurrency, the per-roll fallback
    /// from the routing server to Overpass, and the "one bad roll doesn't sink
    /// the spin" rule all live in `pickThreeCandidates` in `:shared`, so the
    /// two platforms cannot drift on any of it.
    ///
    /// `mode` comes from the caller because the picker needs it for both the
    /// GraphHopper profile and which roads a draw may land on. This used to be
    /// hardcoded to moto here, which meant the vehicle picker above the map
    /// changed the radius range and nothing else.
    func spin(from here: CLLocationCoordinate2D, mode: TravelMode) async {
        state = .spinning
        route = []
        routeResult = nil
        self.destination = nil
        candidates = []

        let center = LatLon(lat: here.latitude, lon: here.longitude)

        do {
            // Prefer roads the fog of war has not uncovered yet, exactly as the
            // Android spin does — the same ExploredArea, over the same traces.
            let explored = ExploredArea.Companion.shared.load()
            candidates = try await SpinPickerKt.pickThreeCandidates(
                config: RoutingServer.shared.load(),
                loc: center,
                radiusMeters: radiusMeters,
                minRadiusMeters: 0,
                mode: mode,
                poiKind: .road,
                bearing: nil,
                explored: explored
            )
            state = .choosing
        } catch {
            // The core throws IOException for "no roads here" and "server said
            // no" alike; its message is already written for a person to read.
            state = .failed(error.localizedDescription)
        }
    }
}
