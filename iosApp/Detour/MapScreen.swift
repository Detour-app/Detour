import SwiftUI
import CoreLocation
import DetourShared

/// The main screen: the map, the spin, and the running trip.
struct MapScreen: View {

    @EnvironmentObject private var recorder: TripRecorder
    @StateObject private var spin = SpinModel()
    @StateObject private var modes = TripModeModel()
    @State private var showSearch = false
    @State private var navigating = false
    /// Last-known position per other member, across every circle you're in.
    /// Kept across polls even when a fetch fails, so a blip doesn't blank the
    /// map — see the `.task(id:)` below.
    @State private var circleFixes: [MemberFix] = []

    /// Circle members post a fix every `CircleSync.syncIntervalSeconds` at
    /// most, so polling faster would just re-fetch the same row — matches
    /// that cadence exactly, same reasoning as Android's `CIRCLE_FIX_POLL_MS`
    /// in MapScreen.kt.
    private static let circleFixPollSeconds = 120

    var body: some View {
        ZStack(alignment: .bottom) {
            MapView(
                center: recorder.lastFix?.coordinate,
                destination: destinationCoordinate,
                route: spin.route,
                circleMembers: circleFixes
            )
            .ignoresSafeArea()

            VStack(spacing: 10) {
                ConvoyBar()
                if let stats = recorder.stats {
                    TripCard(stats: stats) { recorder.endTrip() }
                } else {
                    spinControls
                }
            }
            .padding()
        }
        // Circle member markers: every circle you're in, always — not just
        // whichever one CirclesScreen last had open. A circle is the always-on
        // relationship (docs/CIRCLES_AND_CONVOYS.md section 2); making the map
        // go blank until you walk into another screen and pick one defeats the
        // point of it, and that selection lived in memory, so every app launch
        // lost it. Polled rather than socketed: a circle fix only changes once
        // a minute or so server-side, so polling faster would just repeat the
        // same row. `othersFixes` is the shared chain Android's MapScreen.kt
        // reads too, so the two platforms can't drift apart on which members
        // count — including dropping your own fix, which the server returns
        // like anyone else's and which would otherwise stack a second marker
        // on your own position.
        .task(id: SettingsValues.shared.authUsername) {
            let me = SettingsValues.shared.authUsername
            guard !me.isEmpty else {
                circleFixes = []  // signed out: nothing to ask the server for
                return
            }
            while !Task.isCancelled {
                do {
                    circleFixes = try await CircleFixes.shared.othersFixes(selfUsername: me)
                } catch {
                    // Offline or server down; keep the last known positions
                    // and retry on the next tick.
                }
                try? await Task.sleep(for: .seconds(Self.circleFixPollSeconds))
            }
        }
        .safeAreaInset(edge: .top) { modePicker }
        .fullScreenCover(isPresented: $navigating) {
            if let route = spin.routeResult {
                NavScreen(route: route, destinationName: nil) { navigating = false }
                    .environmentObject(recorder)
            }
        }
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
            ForEach(Enums.shared.travelModes, id: \.name) { mode in
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
                    // Only navigate when the router actually gave us turns;
                    // otherwise this is just a recorded ride toward a pin.
                    if spin.routeResult?.instructions.isEmpty == false {
                        navigating = true
                    }
                } label: {
                    Image(systemName: spin.routeResult?.instructions.isEmpty == false
                          ? "location.north.fill" : "record.circle")
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
