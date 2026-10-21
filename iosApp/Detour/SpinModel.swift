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
        /// A round trip is on the map: `routeResult` holds it and there is no
        /// destination. `warning` is set when it is the approximate fallback
        /// loop and the routing server had already failed.
        case loop(distanceMeters: Double?, timeMs: Int64?, warning: String?)
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

    /// Set while the destination is a place picked from search: where it was
    /// routed from and for which mode, so the curviness slider (#660) can ask
    /// again from the same start. Nil for spins, convoy picks and regroup,
    /// which route fastest and get no slider.
    @Published private(set) var searched: SearchedRoute?
    /// The last `curvyRouteVia` answer for the searched place — `routeResult`
    /// plus its detour cost and twistiness, for the slider's cost line.
    @Published private(set) var searchedCurvy: CurvyRoute?
    /// Bumped on every searched-route request and every destination change,
    /// so an answer that lands after the rider moved the slider again, or
    /// picked something else, is dropped.
    private var searchedRequest = 0

    struct SearchedRoute: Equatable {
        let from: LatLon
        let mode: TravelMode
        var loading: Bool
    }

    /// A destination chosen by hand rather than rolled for. Clears any route
    /// from an earlier spin — that one led somewhere else.
    func setDestination(_ target: LatLon) {
        dropSearched()
        destination = target
        route = []
        routeResult = nil
        candidates = []
        state = .found(lat: target.lat, lon: target.lon, distanceMeters: nil)
    }

    /// Commits one of the three rolls. Its route came back with the candidate,
    /// so unlike a searched-for place this needs no second routing request.
    func choose(_ candidate: RouteCandidate) {
        dropSearched()
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
    /// spin or the regroup point — and routes to it. Every convoy member has to ask for their own: the sharer's polyline
    /// is a line from *their* position, and drawing it here would show a route
    /// starting somewhere you are not.
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
            preferences: SettingsValues.shared.routePreferences,
            heading: nil
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

    /// A place picked from search, routed from `here` with `preferences` —
    /// the A→B ones, avoid options plus the curvy preset — through
    /// `curvyRouteVia`, so the route Start navigates and the slider's cost
    /// line come from the same answer.
    func routeSearched(
        to target: LatLon, from here: LatLon, mode: TravelMode, preferences: RoutePreferences
    ) async {
        setDestination(target)
        searched = SearchedRoute(from: here, mode: mode, loading: false)
        await fetchSearched(preferences: preferences)
    }

    /// Asks for the searched place again after the curvy preset moved, from
    /// where it was first routed. The phone asks its preview from the fix at
    /// the moment the slider moves instead (divergence register entry 27).
    /// The old line is dropped while it's asked for, so Start can't navigate
    /// a route the slider no longer shows.
    func rerouteSearched(preferences: RoutePreferences) async {
        guard searched != nil else { return }
        route = []
        routeResult = nil
        await fetchSearched(preferences: preferences)
    }

    private func fetchSearched(preferences: RoutePreferences) async {
        guard let ask = searched, let target = destination else { return }
        searchedRequest += 1
        let request = searchedRequest
        searchedCurvy = nil
        searched?.loading = true
        defer { if request == searchedRequest { searched?.loading = false } }
        let server = RoutingServer.shared.load()
        guard server.usable else { return }
        let result = try? await RoutingClient.shared.curvyRouteVia(
            config: server,
            points: [ask.from, target],
            profile: ask.mode.ghProfile,
            preferences: preferences,
            heading: nil
        )
        guard let result, request == searchedRequest else { return }
        searchedCurvy = result
        routeResult = result.route
        route = result.route.polyline.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        }
        state = .found(
            lat: target.lat, lon: target.lon,
            distanceMeters: result.route.distanceMeters?.doubleValue)
    }

    private func dropSearched() {
        searchedRequest += 1
        searched = nil
        searchedCurvy = nil
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
    ///
    /// A mode that spins round trips (Moto) gets one loop instead — see
    /// `spinLoop`. `loopMinutes` sizes that loop by riding time; nil sizes it
    /// by `radiusMeters`, which for a loop means its length.
    func spin(from here: CLLocationCoordinate2D, mode: TravelMode, loopMinutes: Float? = nil) async {
        state = .spinning
        dropSearched()
        route = []
        routeResult = nil
        self.destination = nil
        candidates = []

        let center = LatLon(lat: here.latitude, lon: here.longitude)
        if mode.roundTrip {
            await spinLoop(from: center, mode: mode, loopMinutes: loopMinutes)
            return
        }

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

    /// One round trip from `center`, through the shared `LoopSpin` — the same
    /// rolls, curviest/timed pick and backend fallback the Android spin runs,
    /// so the two cannot drift. Its failures arrive as a sentence already.
    private func spinLoop(from center: LatLon, mode: TravelMode, loopMinutes: Float?) async {
        do {
            let loop = try await LoopSpin.shared.spin(
                config: RoutingServer.shared.load(),
                request: LoopRequest(
                    from: center,
                    lengthMeters: radiusMeters,
                    minutes: loopMinutes.map { KotlinFloat(value: $0) },
                    headingDeg: nil,
                    preferences: SettingsValues.shared.routePreferences,
                    highwayRegex: mode.highwayRegex
                )
            )
            routeResult = loop.route
            route = loop.route.polyline.map {
                CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
            }
            state = .loop(
                distanceMeters: loop.route.distanceMeters?.doubleValue,
                timeMs: loop.route.timeMs?.int64Value,
                warning: loop.warning
            )
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}
