import SwiftUI
import DetourShared

/// The card's fixed pixel size — same 1080x1350 as Android, so a trip shared
/// from either platform produces a card a reader would call the same design.
private let cardWidth: CGFloat = 1080
private let cardHeight: CGFloat = 1350

/// Renders [cardData] to a PNG-ready UIImage using ImageRenderer, SwiftUI's
/// counterpart to Android's GraphicsLayer capture — same technique, same
/// output size, so the two renderers stay easy to compare side by side.
///
/// [darkTheme] picks the card's background explicitly rather than leaving it to
/// SwiftUI's `colorScheme` environment: `ImageRenderer`'s content is rendered
/// off-screen, disconnected from any real window/trait collection, so a
/// dynamic system color like `.systemBackground` would resolve against
/// whatever `colorScheme` defaults to off-screen (light) rather than the
/// user's actual setting. `routeColorHex` already avoids this same trap by
/// being resolved by the caller and passed in as a plain value instead of
/// read from environment — this does the same for the background.
@MainActor
func renderTripCardImage(cardData: CardData, routeColorHex: String, darkTheme: Bool) -> UIImage? {
    let renderer = ImageRenderer(content:
        TripCardContent(cardData: cardData, routeColorHex: routeColorHex, darkTheme: darkTheme)
            .frame(width: cardWidth, height: cardHeight)
    )
    renderer.scale = 1 // fixed pixel size, not device-scaled — matches Android's density(1f).
    return renderer.uiImage
}

/// Writes the card into the same temp directory TripDetailScreen's GPX
/// export already uses for its ShareLink item.
func writeTripCardForShare(trip: Trip, image: UIImage) -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("detour-card-\(trip.startTimeMs).png")
    if let data = image.pngData() {
        try? data.write(to: url, options: .atomic)
    }
    return url
}

private struct TripCardContent: View {
    let cardData: CardData
    let routeColorHex: String
    let darkTheme: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !cardData.points.isEmpty {
                GeometryReader { geo in
                    Path { path in
                        for (i, p) in cardData.points.enumerated() {
                            let point = CGPoint(x: CGFloat(p.x) * geo.size.width, y: CGFloat(p.y) * geo.size.height)
                            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
                        }
                    }
                    .stroke(Color(hex: routeColorHex), style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    if let d = cardData.destination {
                        Circle()
                            .fill(Color(hex: routeColorHex))
                            .frame(width: 28, height: 28)
                            .position(x: CGFloat(d.x) * geo.size.width, y: CGFloat(d.y) * geo.size.height)
                    }
                }
                .frame(maxHeight: .infinity)
            }
            Text("\(cardData.trip.mode.label) · \(formatDate(cardData.trip.startTimeMs))")
                .font(.title)
            VStack(alignment: .leading, spacing: 6) {
                statRow("Distance", formatDistanceKm(cardData.trip.distanceMeters))
                statRow("Duration", formatDurationHistory(cardData.trip.durationMs))
                statRow("Avg speed", formatSpeedKmh(cardData.trip.avgSpeedMps))
                statRow("Top speed", formatSpeedKmh(cardData.trip.topSpeedMps))
                if let lean = cardData.peakLeanDeg?.doubleValue {
                    statRow("Peak lean", formatLeanAngle(lean))
                }
                if let g = cardData.peakGForce?.doubleValue {
                    statRow("Peak g", formatGForce(g))
                }
            }
        }
        .padding(48)
        .frame(width: cardWidth, height: cardHeight, alignment: .topLeading)
        // Explicit, not `Color(.systemBackground)`: see renderTripCardImage's
        // doc comment. #0B1220 is RouteColors.kt's DRIVEN_TOWARDS_DARK — the
        // same dark casing tone this app already uses elsewhere for "dark
        // background under the night basemap" — reused here rather than
        // inventing a new dark tone for this one screen.
        .background(darkTheme ? Color(hex: "#0B1220") : Color.white)
    }

    private func statRow(_ label: String, _ value: String) -> some View {
        Text("\(label): \(value)").font(.body)
    }
}

private extension Color {
    /// `#RRGGBB` → SwiftUI Color. RouteColors (shared) only ever hands back
    /// this exact format, so no alpha channel or shorthand form to handle.
    init(hex: String) {
        var s = hex; if s.hasPrefix("#") { s.removeFirst() }
        let v = UInt64(s, radix: 16) ?? 0
        self.init(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255
        )
    }
}
