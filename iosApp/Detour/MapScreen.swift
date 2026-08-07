import SwiftUI
import CoreLocation

/// The first screen: the map, a radius slider, and Spin.
///
/// Deliberately the smallest surface that exercises the whole chain —
/// CoreLocation into the shared core, the core out to the routing server, the
/// result back onto MapLibre. The rest of the Android app's screens (history,
/// badges, friends, saved places, settings) still have to be written; this one
/// is what proves the port works end to end.
struct MapScreen: View {

    @StateObject private var location = LocationProvider()
    @StateObject private var spin = SpinModel()

    var body: some View {
        ZStack(alignment: .bottom) {
            MapView(
                center: location.last?.coordinate,
                destination: destinationCoordinate,
                route: spin.route
            )
            .ignoresSafeArea()

            controls
        }
        .task {
            location.requestWhenInUse()
        }
    }

    private var destinationCoordinate: CLLocationCoordinate2D? {
        if case let .found(lat, lon, _) = spin.state {
            return CLLocationCoordinate2D(latitude: lat, longitude: lon)
        }
        return nil
    }

    private var controls: some View {
        VStack(spacing: 12) {
            Text(status)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack {
                Text("\(Int(spin.radiusMeters / 1000)) km")
                    .monospacedDigit()
                    .frame(width: 64, alignment: .leading)
                Slider(value: $spin.radiusMeters, in: 2_000...100_000, step: 1_000)
            }

            Button {
                guard let here = location.last?.coordinate else { return }
                Task { await spin.spin(from: here) }
            } label: {
                Text(spin.state == .spinning ? "Spinning…" : "Spin")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
            .disabled(location.last == nil || spin.state == .spinning)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
        .padding()
    }

    private var status: String {
        switch spin.state {
        case .idle:
            return location.last == nil
                ? "Waiting for a location fix…"
                : "Pick a radius and spin."
        case .spinning:
            return "Finding a road…"
        case let .found(_, _, distance):
            guard let distance else { return "Found a road." }
            return String(format: "%.1f km by road", distance / 1000)
        case let .failed(message):
            return message
        }
    }
}
