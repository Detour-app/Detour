import SwiftUI
import CoreLocation
import DetourShared

/// The main screen: the map, the spin, and the running trip.
struct MapScreen: View {

    @EnvironmentObject private var recorder: TripRecorder
    @StateObject private var spin = SpinModel()
    @StateObject private var modes = TripModeModel()

    @State private var showSearch = false

    var body: some View {
        ZStack(alignment: .bottom) {
            MapView(
                center: recorder.lastFix?.coordinate,
                destination: destinationCoordinate,
                route: spin.route
            )
            .ignoresSafeArea()

            VStack(spacing: 10) {
                if let stats = recorder.stats {
                    TripCard(stats: stats) { recorder.endTrip() }
                } else {
                    spinControls
                }
            }
            .padding()
        }
        .safeAreaInset(edge: .top) { modePicker }
        .sheet(isPresented: $showSearch) {
            SearchSheet { result in
                spin.setDestination(result.location)
                showSearch = false
            }
        }
        .alert("Badge earned", isPresented: .constant(!recorder.newlyEarned.isEmpty)) {
            Button("Nice") { recorder.newlyEarned = [] }
        } message: {
            Text(recorder.newlyEarned.map(\.title).joined(separator: "\n"))
        }
    }

    // MARK: Pieces

    private var modePicker: some View {
        Picker("Vehicle", selection: Binding(
            get: { modes.mode },
            set: { Settings.shared.setTripMode(value: $0) }
        )) {
            ForEach(TravelMode.entries, id: \.name) { mode in
                Text(mode.label).tag(mode)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .background(.regularMaterial)
    }

    private var spinControls: some View {
        VStack(spacing: 12) {
            Text(status)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack {
                Text("\(Int(spin.radiusMeters / 1000)) km")
                    .monospacedDigit()
                    .frame(width: 64, alignment: .leading)
                Slider(
                    value: $spin.radiusMeters,
                    // Each mode has its own sensible range — 3 km on foot,
                    // 400 km for a moto round trip — and the shared enum is
                    // where those live.
                    in: Double(modes.mode.minKm * 1000)...Double(modes.mode.maxKm * 1000),
                    step: 1_000)
            }

            HStack(spacing: 10) {
                Button { showSearch = true } label: {
                    Image(systemName: "magnifyingglass")
                        .frame(height: 44)
                        .frame(maxWidth: 56)
                }
                .buttonStyle(.bordered)

                Button {
                    guard let here = recorder.lastFix?.coordinate else { return }
                    Task { await spin.spin(from: here) }
                } label: {
                    Text(spin.state == .spinning ? "Spinning…" : "Spin")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(recorder.lastFix == nil || spin.state == .spinning)

                Button {
                    recorder.startTrip(destination: spin.destination)
                } label: {
                    Image(systemName: "record.circle")
                        .frame(height: 44)
                        .frame(maxWidth: 56)
                }
                .buttonStyle(.bordered)
                .disabled(recorder.lastFix == nil)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private var destinationCoordinate: CLLocationCoordinate2D? {
        spin.destination.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        }
    }

    private var status: String {
        switch spin.state {
        case .idle:
            return recorder.lastFix == nil
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

private struct TripCard: View {
    let stats: TripStats
    let onStop: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                stat(formatDistanceKm(stats.distanceMeters), "Distance")
                stat(formatDuration(stats.durationMs), "Time")
                stat(formatSpeedKmh(stats.currentSpeedMps), "Speed")
            }
            // Lean and g are only shown where the vehicle actually measures
            // them; in a car the lean number is the phone moving in its cradle.
            if stats.mode.tracksLean {
                HStack {
                    stat(formatLeanAngle(stats.currentLeanAngleDeg), "Lean")
                    stat(formatLeanAngle(stats.maxLeanAngleDeg), "Max lean")
                    stat(formatGForce(stats.maxGForce), "Max g")
                }
            }
            Button(role: .destructive, action: onStop) {
                Text("Stop").frame(maxWidth: .infinity).frame(height: 40)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.monospacedDigit().weight(.semibold))
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Type-ahead place search, backed by the same Photon instance Android uses.
private struct SearchSheet: View {
    let onPick: (GeocodeResult) -> Void

    @State private var query = ""
    @State private var results: [GeocodeResult] = []
    @State private var searching = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if !query.isEmpty && results.isEmpty && !searching {
                    Text("Nothing found").foregroundStyle(.secondary)
                }
                ForEach(results, id: \.name) { result in
                    Button(result.name) {
                        RecentSearchStore.shared.save(result: result)
                        onPick(result)
                    }
                }
            }
            .overlay { if searching { ProgressView() } }
            .searchable(text: $query, prompt: "Search a place")
            .navigationTitle("Search")
            .toolbar {
                Button("Cancel") { dismiss() }
            }
            .task { results = RecentSearchStore.shared.load() }
            .task(id: query) {
                guard query.count >= 2 else { return }
                // Debounce: Photon is a type-ahead geocoder, but this is
                // someone's own server on the end of a tunnel.
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                searching = true
                results = (try? await Geocoder.shared.search(
                    query: query, near: nil, limit: 8)) ?? []
                searching = false
            }
        }
    }
}

/// The selected vehicle, which is persisted because the trip recorder reads it
/// too: an auto-detected trip has no other way to know what it is.
@MainActor
final class TripModeModel: ObservableObject {
    @Published var mode: TravelMode = .car

    private let watcher = SettingsFlows.shared.tripMode()

    init() {
        watcher.watch { [weak self] in self?.mode = self?.watcher.value ?? .car }
    }

    deinit { watcher.cancel() }
}
