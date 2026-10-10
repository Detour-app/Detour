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
    @State private var insights: TripNumbers?
    @State private var cardDialogOpen = false
    @State private var cardShareURL: URL?
    @State private var cardShareSheetOpen = false

    var body: some View {
        List {
            Section {
                MapView(center: route.first, destination: destination, route: route,
                        pins: highlights.map { MapPin(coordinate: $0.at, title: "\($0.label) · \($0.value)") })
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

            if !highlights.isEmpty {
                Section("Highlights") {
                    ForEach(highlights, id: \.label) { row($0.label, $0.value) }
                }
            }

            if let insights {
                speedSection(insights)
                if trip.mode.tracksLean, let lean = insights.lean {
                    corneringSection(lean)
                }
                splitsSection(insights)
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
                .accessibilityLabel("Share GPX")
            Button { cardDialogOpen = true } label: { Image(systemName: "photo.badge.arrow.down") }
                .accessibilityLabel("Share trip card")
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
        .task { await loadRoute() }
    }

    private var destination: CLLocationCoordinate2D? {
        guard let lat = trip.destinationLat?.doubleValue,
              let lon = trip.destinationLon?.doubleValue else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    private func loadRoute() async {
        var traced: [TraceStore.TracePoint] = []
        for line in TraceStore.shared.rawLines() {
            guard let parsed = TraceStore.shared.parsePoints(line: line) else { continue }
            traced += parsed.filter {
                $0.timeMs >= trip.startTimeMs && $0.timeMs <= trip.endTimeMs
            }
        }
        route = traced.map { CLLocationCoordinate2D(latitude: $0.at.lat, longitude: $0.at.lon) }
        routeLatLon = traced.map { $0.at }
        // Splits score every split's curviness and the lean summary walks the
        // whole trace — same reason Android builds these off the main thread.
        let points = traced
        insights = await Task.detached { TripNumbers(points: points) }.value
    }

    /// Where the trip's peaks and hardest events happened, from the moments the
    /// recorder pinned live — the decimated trace can't place them. Same picks
    /// and order as Android's `tripHighlights`, less the best stretch, which is
    /// a line rather than a pin. Empty for a trip recorded on iOS until its
    /// recorder writes moments (#625).
    private var highlights: [TripHighlight] {
        let m = trip.moments
        var out: [TripHighlight] = []
        if trip.mode.tracksLean, let lean = m.maxLean, lean.value != 0 {
            out.append(TripHighlight(label: "Deepest lean", value: leanSide(lean.value), at: lean.at))
        }
        let corners = m.events.filter { $0.kind == .hardCornerLean || $0.kind == .hardCornerTurn }
        if let corner = corners.max(by: { abs($0.magnitude) < abs($1.magnitude) }) {
            out.append(TripHighlight(label: "Sharpest corner", value: eventValue(corner), at: corner.at))
        }
        // Braking is negative, so the hardest is the smallest.
        let brakes = m.events.filter { $0.kind == .hardBrake }
        if let brake = brakes.min(by: { $0.magnitude < $1.magnitude }) {
            out.append(TripHighlight(label: "Hardest braking", value: eventValue(brake), at: brake.at))
        }
        if let top = m.topSpeed {
            out.append(TripHighlight(label: "Top speed", value: formatSpeedKmh(top.value), at: top.at))
        }
        return out
    }

    private func speedSection(_ n: TripNumbers) -> some View {
        let moving = n.speedBands.reduce(Int64(0)) { $0 + $1.ms }
        return Section("Speed") {
            if n.movingMs > 0 {
                row("Moving time", formatDurationHistory(n.movingMs))
                row("Moving average", formatSpeedKmh(trip.distanceMeters / (Double(n.movingMs) / 1000)))
            }
            ForEach(n.speedBands.filter { $0.ms > 0 }, id: \.fromKmh) { b in
                shareRow(
                    b.toKmh.map { "\(b.fromKmh)–\($0.intValue) km/h" } ?? "\(b.fromKmh)+ km/h",
                    b.ms, of: moving)
            }
        }
    }

    private func corneringSection(_ lean: LeanSummary) -> some View {
        let leaned = lean.leftMs + lean.rightMs
        return Section("Cornering") {
            row("Deepest left", formatLeanAngle(lean.maxLeftDeg))
            row("Deepest right", formatLeanAngle(lean.maxRightDeg))
            if leaned > 0 {
                shareRow("Leaning left", lean.leftMs, of: leaned)
                shareRow("Leaning right", lean.rightMs, of: leaned)
                ForEach(lean.bands.filter { $0.ms > 0 }, id: \.fromDeg) { b in
                    shareRow(b.toDeg.map { "\(b.fromDeg)–\($0.intValue)°" } ?? "\(b.fromDeg)°+", b.ms, of: leaned)
                }
            }
        }
    }

    /// Untimed legacy traces give every split "0 min · 0 km/h", so those, and a
    /// trip shorter than two splits, show no section at all — as on Android.
    @ViewBuilder
    private func splitsSection(_ n: TripNumbers) -> some View {
        if n.splits.count >= 2, n.splits.contains(where: { $0.durationMs > 0 }) {
            Section("Splits · every \(formatDistanceKm(TripInsights.shared.SPLIT_METERS))") {
                ForEach(n.splits, id: \.index) { s in
                    let range = "\(formatDistanceKm(s.startMeters))–\(formatDistanceKm(s.startMeters + s.distanceMeters))"
                    let parts = [
                        formatDurationHistory(s.durationMs),
                        formatSpeedKmh(s.avgSpeedMps),
                        s.maxLeanDeg.map { formatLeanAngle($0.doubleValue) },
                        "twisty \(Int((s.twistiness * 100).rounded()))%",
                    ].compactMap { $0 }
                    let best = s.index == n.bestSplitIndex
                    LabeledContent(best ? "\(range) · best" : range, value: parts.joined(separator: " · "))
                        .fontWeight(best ? .bold : .regular)
                }
            }
        }
    }

    /// Label, the time, and its share of `total` as a bar.
    private func shareRow(_ label: String, _ ms: Int64, of total: Int64) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label)
                Spacer()
                // formatDurationHistory floors to whole minutes; "0 min" next
                // to a bar reads as nothing, when it was seconds.
                Text(ms > 0 && ms < 60_000 ? "<1 min" : formatDurationHistory(ms))
                    .foregroundStyle(.secondary)
            }
            .font(.subheadline)
            ProgressView(value: total > 0 ? min(1, Double(ms) / Double(total)) : 0)
        }
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

/// One pinned moment of a trip, listed and dropped on the map together.
private struct TripHighlight {
    let label: String
    let value: String
    let at: CLLocationCoordinate2D

    init(label: String, value: String, at: LatLon) {
        self.label = label
        self.value = value
        self.at = CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon)
    }
}

/// The trace-derived figures, computed together off the main thread so
/// opening a trip walks the trace a fixed number of times.
private struct TripNumbers {
    let movingMs: Int64
    let splits: [TripSplit]
    let bestSplitIndex: Int32?
    let speedBands: [SpeedBand]
    let lean: LeanSummary?

    init(points: [TraceStore.TracePoint]) {
        let insights = TripInsights.shared
        movingMs = insights.movingMs(points: points)
        splits = insights.splits(points: points, splitMeters: insights.SPLIT_METERS)
        bestSplitIndex = insights.bestSplit(splits: splits)?.index
        speedBands = insights.speedBands(points: points)
        lean = insights.lean(points: points)
    }
}

/// How far past its detection threshold an event has to go to read as "very".
private let veryHardFactor = 1.5

/// "32° left" / "32° right" for a signed lean (negative is left).
private func leanSide(_ deg: Double) -> String {
    "\(formatLeanAngle(abs(deg))) \(deg < 0 ? "left" : "right")"
}

/// An event's strength in words: m/s² and °/s mean nothing to most riders.
/// A lean angle is the one figure riders already read, so it stays a number.
private func eventValue(_ e: RidingEvent) -> String {
    let detector = HardEventDetector.shared
    let threshold: Double
    switch e.kind {
    case .hardBrake: threshold = detector.HARD_BRAKE_MPS2
    case .hardAccel: threshold = detector.HARD_ACCEL_MPS2
    case .hardCornerTurn: threshold = detector.HARD_CORNER_DEG_PER_SEC
    default: return leanSide(e.magnitude)
    }
    let very = abs(e.magnitude) >= abs(threshold) * veryHardFactor
    if e.kind == .hardCornerTurn { return very ? "very sharp" : "sharp" }
    return very ? "very hard" : "hard"
}
