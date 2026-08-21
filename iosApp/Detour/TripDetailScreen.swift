import SwiftUI
import CoreLocation
import DetourShared

/// One ride: its numbers, and the piece of trace it drew.
///
/// A trace line carries no trip id, so the route is recovered the way the
/// Android detail screen recovers it — by timestamp. `TraceStore.parsePoints`
/// keeps the tail that the fog-of-war reader throws away, and that tail is what
/// ties a point to the trip that was running at that instant.
struct TripDetailScreen: View {

    let trip: Trip

    @State private var route: [CLLocationCoordinate2D] = []
    @State private var routeLatLon: [LatLon] = []
    @State private var cardDialogOpen = false
    @State private var cardShareURL: URL?
    @State private var cardShareSheetOpen = false

    var body: some View {
        List {
            Section {
                MapView(center: route.first, destination: destination, route: route)
                    .frame(height: 240)
                    .listRowInsets(EdgeInsets())
            }

            Section("Ride") {
                row("Distance", formatDistanceKm(trip.distanceMeters))
                row("Duration", formatDurationHistory(trip.durationMs))
                row("Top speed", formatSpeedKmh(trip.topSpeedMps))
                row("Average", formatSpeedKmh(trip.avgSpeedMps))
                row("Vehicle", trip.mode.label)
            }

            // Only shown where the vehicle actually measures them: in a car the
            // lean number is the phone sliding around in its cradle.
            if trip.mode.tracksLean && trip.maxLeanAngleDeg > 0 {
                Section("Handling") {
                    row("Max lean", formatLeanAngle(trip.maxLeanAngleDeg))
                    if trip.mode.tracksGForce && trip.maxGForce > 1 {
                        row("Max g", formatGForce(trip.maxGForce))
                    }
                }
            }

            Section("When") {
                row("Started", formatDate(trip.startTimeMs))
                row("Ended", formatTimeOfDay(trip.endTimeMs))
            }
        }
        .navigationTitle(formatDate(trip.startTimeMs))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ShareLink(item: gpxURL()) { Image(systemName: "square.and.arrow.up") }
            Button { cardDialogOpen = true } label: { Image(systemName: "photo.badge.arrow.down") }
        }
        .confirmationDialog("Share trip card", isPresented: $cardDialogOpen, titleVisibility: .visible) {
            Button("Share trimmed (recommended)") { shareTripCard(full: false) }
            Button("Share full route") { shareTripCard(full: true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Endpoints are trimmed by default to avoid sharing your driveway.")
        }
        .sheet(isPresented: $cardShareSheetOpen) {
            if let url = cardShareURL {
                ActivityView(activityItems: [url])
            }
        }
        .task { loadRoute() }
    }

    private var destination: CLLocationCoordinate2D? {
        guard let lat = trip.destinationLat?.doubleValue,
              let lon = trip.destinationLon?.doubleValue else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    private func loadRoute() {
        var points: [CLLocationCoordinate2D] = []
        var latLons: [LatLon] = []
        for line in TraceStore.shared.rawLines() {
            guard let parsed = TraceStore.shared.parsePoints(line: line) else { continue }
            let inside = parsed.filter {
                $0.timeMs >= trip.startTimeMs && $0.timeMs <= trip.endTimeMs
            }
            points += inside.map {
                CLLocationCoordinate2D(latitude: $0.at.lat, longitude: $0.at.lon)
            }
            latLons += inside.map { $0.at }
        }
        route = points
        routeLatLon = latLons
    }

    /// Renders and shares the trip card PNG. `darkTheme: false` (hardcoded)
    /// matches `MapView.swift:66,68` — iOS route/card theming isn't
    /// dark-mode-aware yet, and this keeps the card consistent with the live
    /// map rather than introducing a new light/dark split between the two.
    private func shareTripCard(full: Bool) {
        let cardData = TripCardGeometry.shared.build(trip: trip, points: routeLatLon, full: full)
        let routeColorHex = RouteColors.shared.hex(
            color: SettingsValues.shared.routeColor, darkTheme: false)
        guard let image = renderTripCardImage(
            cardData: cardData, routeColorHex: routeColorHex, darkTheme: false, trimmed: !full
        ) else { return }
        cardShareURL = writeTripCardForShare(trip: trip, image: image)
        cardShareSheetOpen = true
    }

    /// GPX 1.1 — the one track format every mapping tool reads. Written to the
    /// temp directory for the share sheet only; the trace store itself never
    /// leaves the app.
    private func gpxURL() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("detour-\(trip.startTimeMs).gpx")
        try? buildGpx().write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private func buildGpx() -> String {
        let iso = ISO8601DateFormatter()
        iso.timeZone = TimeZone(identifier: "UTC")
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Detour" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>\(formatDate(trip.startTimeMs))</name><trkseg>
        """
        for line in TraceStore.shared.rawLines() {
            guard let parsed = TraceStore.shared.parsePoints(line: line) else { continue }
            for p in parsed where p.timeMs >= trip.startTimeMs && p.timeMs <= trip.endTimeMs {
                let when = iso.string(from: Date(timeIntervalSince1970: Double(p.timeMs) / 1000))
                xml += "\n    <trkpt lat=\"\(p.at.lat)\" lon=\"\(p.at.lon)\"><time>\(when)</time></trkpt>"
            }
        }
        xml += "\n  </trkseg></trk>\n</gpx>\n"
        return xml
    }

    private func row(_ label: String, _ value: String) -> some View {
        LabeledContent(label, value: value)
    }
}
