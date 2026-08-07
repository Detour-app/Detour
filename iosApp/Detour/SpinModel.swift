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
        case found(lat: Double, lon: Double, distanceMeters: Double?)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    /// Route geometry for the map overlay, as plain coordinates.
    @Published private(set) var route: [CLLocationCoordinate2D] = []

    /// Radius in metres, matching the Android app's slider range.
    @Published var radiusMeters: Double = 25_000

    func spin(from here: CLLocationCoordinate2D) async {
        state = .spinning
        route = []

        let center = LatLon(lat: here.latitude, lon: here.longitude)
        let server = RoutingServer.shared.load()

        do {
            // Prefer roads the fog of war has not uncovered yet, exactly as the
            // Android spin does — the same ExploredArea, over the same traces.
            let explored = ExploredArea.Companion.shared.load()

            let destination: LatLon
            if server.usable {
                destination = try await RoutingServer.shared.randomRoadDestination(
                    config: server,
                    center: center,
                    radiusMeters: radiusMeters,
                    bearingDeg: nil,
                    explored: explored,
                    profile: "moto",
                    minRadiusMeters: 0
                )
            } else {
                // No routing server configured: Overpass picks the road itself.
                destination = try await RoadRoulette.shared.randomRoadPoint(
                    center: center,
                    radiusMeters: radiusMeters,
                    highwayRegex: "motorway|trunk|primary|secondary|tertiary|unclassified|residential",
                    bearingDeg: nil,
                    explored: explored,
                    minRadiusMeters: 0
                )
            }

            var distance: Double?
            if server.usable {
                let result = try await RoutingServer.shared.route(
                    config: server,
                    from: center,
                    to: destination,
                    profile: "moto",
                    avoidHighways: Settings.shared.avoidHighways.value as? Bool ?? false,
                    avoidSmallRoads: Settings.shared.avoidSmallRoads.value as? Bool ?? false
                )
                route = result.polyline.map {
                    CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
                }
                distance = result.distanceMeters?.doubleValue
            }

            state = .found(
                lat: destination.lat, lon: destination.lon, distanceMeters: distance)
        } catch {
            // The core throws IOException for "no roads here" and "server said
            // no" alike; its message is already written for a person to read.
            state = .failed(error.localizedDescription)
        }
    }
}
