import SwiftUI
import CoreLocation
import DetourShared

/// Build or edit a saved multi-stop route: tap the map to add stops, reorder
/// or drop them, then route through the self-hosted server once there are
/// enough to route between.
///
/// Mirrors `SpinModel`'s shape (load config, call into `RoutingServer`,
/// surface the thrown message) but keeps the state inline rather than in its
/// own `ObservableObject` — there is only one screen that ever touches it,
/// unlike the spin flow which `MapScreen` and `NavScreen` both read from.
struct RouteEditorScreen: View {

    let existing: SavedRoute?

    @Environment(\.dismiss) private var dismiss

    @State private var routeId: Int64
    @State private var name: String
    @State private var mode: TravelMode
    @State private var stops: [EditorStop]
    @State private var polyline: [LatLon]
    @State private var distanceMeters: Double?
    @State private var timeMs: Int64?

    @State private var routing = false
    @State private var routeError: String?

    @State private var fillMinutes: Double = 60
    @State private var filling = false
    @State private var fillError: String?

    init(existing: SavedRoute?) {
        self.existing = existing
        _routeId = State(initialValue: existing?.id ?? nowMs())
        _name = State(initialValue: existing?.name ?? "")
        _mode = State(initialValue: existing?.mode ?? .car)
        _stops = State(initialValue: (existing?.stops ?? []).map { EditorStop(stop: $0) })
        _polyline = State(initialValue: existing?.polyline ?? [])
        _distanceMeters = State(initialValue: existing?.distanceMeters?.doubleValue)
        _timeMs = State(initialValue: existing?.timeMs?.int64Value)
    }

    var body: some View {
        List {
            Section {
                MapView(
                    center: stops.first?.coordinate,
                    destination: nil,
                    route: polyline.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) },
                    stops: stops.map(\.coordinate),
                    onTap: { appendStop(at: $0) }
                )
                .frame(height: 260)
                .listRowInsets(EdgeInsets())
            }

            Section("Name") {
                TextField("Route name", text: $name)
            }

            Section("Vehicle") {
                Picker("Vehicle", selection: $mode) {
                    ForEach(Enums.shared.travelModes, id: \.name) { m in
                        Text(m.label).tag(m)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section("Stops") {
                if stops.isEmpty {
                    Text("Tap the map to add a stop.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(stops) { stop in
                        Text(stop.stop.name.isEmpty
                             ? String(format: "%.4f, %.4f", stop.stop.at.lat, stop.stop.at.lon)
                             : stop.stop.name)
                    }
                    .onDelete { stops.remove(atOffsets: $0) }
                    .onMove { stops.move(fromOffsets: $0, toOffset: $1) }
                }
            }

            Section {
                routingStatus
            }

            fillSection
        }
        .navigationTitle(existing == nil ? "New route" : "Edit route")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Pushed (editing an existing route) already has the automatic
            // back button in this slot; presented modally (a brand new
            // route) there is no chrome at all until this Cancel is added.
            ToolbarItem(placement: .navigationBarLeading) {
                if existing == nil {
                    Button("Cancel") { dismiss() }
                }
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                EditButton() // drag handles for reordering stops.
                Button("Save") { save() }
                    .disabled(stops.count < 2)
            }
        }
        .task(id: routeKey) { await refreshRoute() }
    }

    // MARK: Pieces

    @ViewBuilder
    private var routingStatus: some View {
        if routing {
            HStack {
                ProgressView()
                Text("Routing…")
            }
            .foregroundStyle(.secondary)
        } else if let routeError {
            Text(routeError).foregroundStyle(.red)
        } else if let distanceMeters {
            HStack(spacing: 16) {
                Label(formatDistanceKm(distanceMeters), systemImage: "signpost.right.and.left")
                if let timeMs {
                    Label(formatDurationHistory(timeMs), systemImage: "clock")
                }
            }
            .foregroundStyle(.secondary)
        } else if stops.count < 2 {
            Text("Add at least two stops to route between them.")
                .foregroundStyle(.secondary)
        }
    }

    /// Stretch the route to a riding time: the rider's stops stay, the
    /// shared `RouteFill` inserts detours between them — the same engine the
    /// Android editor calls.
    private var fillSection: some View {
        Section {
            HStack {
                Text("Riding time")
                Spacer()
                Text(formatDurationHistory(LoopDuration.shared.targetMs(minutes: Float(fillMinutes))))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            Slider(
                value: $fillMinutes,
                in: Double(LoopDuration.shared.MIN_MINUTES)...Double(LoopDuration.shared.MAX_MINUTES),
                step: Double(LoopDuration.shared.STEP_MINUTES))
            HStack {
                Button {
                    Task { await fill() }
                } label: {
                    if filling {
                        ProgressView()
                    } else {
                        Text(hasFill ? "Fill again" : "Fill route")
                    }
                }
                .disabled(stops.isEmpty || filling)
                if hasFill {
                    Spacer()
                    Button("Remove fill", role: .destructive) {
                        stops.removeAll { RouteFill.shared.isFill(stop: $0.stop) }
                        fillError = nil
                    }
                    .disabled(filling)
                }
            }
            // Two buttons in one List row: without this, a tap anywhere in the
            // row fires both.
            .buttonStyle(.borderless)
            if let fillError {
                Text(fillError).foregroundStyle(.red)
            }
        } header: {
            Text("Fill to riding time")
        } footer: {
            Text("Your stops stay put; the rest of the time is filled with detours between them. "
                 + "One stop fills a loop from it and back.")
        }
    }

    private var hasFill: Bool {
        stops.contains { RouteFill.shared.isFill(stop: $0.stop) }
    }

    // MARK: Actions

    private func fill() async {
        filling = true
        fillError = nil
        // A fill takes a few round trips to the server; if the rider edits the
        // stops meanwhile, their edit wins.
        let key = routeKey
        do {
            let filled = try await RouteFill.shared.fillRouted(
                config: RoutingServer.shared.load(),
                stops: stops.map(\.stop),
                minutes: Float(fillMinutes),
                profile: mode.ghProfile,
                avoidHighways: SettingsValues.shared.avoidHighways,
                avoidSmallRoads: SettingsValues.shared.avoidSmallRoads
            )
            if routeKey == key {
                stops = filled.stops.map { EditorStop(stop: $0) }
            }
        } catch {
            // Kotlin/Native hands the thrown Kotlin object over under this key.
            if let tooLong = (error as NSError).userInfo["KotlinException"] as? StopsTooLong {
                fillError = "Your stops alone take \(formatDurationHistory(tooLong.stopsMs)) — pick a longer time"
            } else {
                fillError = "Could not fill the route: \(error.localizedDescription)"
            }
        }
        filling = false
    }

    private func appendStop(at coordinate: CLLocationCoordinate2D) {
        let stop = RouteStop(at: LatLon(lat: coordinate.latitude, lon: coordinate.longitude), name: "")
        stops.append(EditorStop(stop: stop))
    }

    /// Re-fetches whenever the stops or the vehicle change. `RouteStop` isn't
    /// `Hashable` on the Swift side (Kotlin data classes don't bridge that
    /// conformance for free), so the `.task(id:)` key is a plain string built
    /// from the coordinates rather than the stops array itself.
    private var routeKey: String {
        stops.map { "\($0.stop.at.lat),\($0.stop.at.lon)" }.joined(separator: "|") + "#" + mode.name
    }

    private func refreshRoute() async {
        guard stops.count >= 2 else {
            polyline = []
            distanceMeters = nil
            timeMs = nil
            routeError = nil
            return
        }
        // Debounced: a burst of taps placing several stops in a row would
        // otherwise fire one routing request per tap.
        try? await Task.sleep(for: .milliseconds(400))
        guard !Task.isCancelled else { return }

        routing = true
        routeError = nil
        let config = RoutingServer.shared.load()
        do {
            let result = try await RoutingClient.shared.routeVia(
                config: config,
                points: stops.map(\.stop.at),
                profile: mode.ghProfile,
                avoidHighways: SettingsValues.shared.avoidHighways,
                avoidSmallRoads: SettingsValues.shared.avoidSmallRoads,
                heading: nil
            )
            polyline = result.polyline
            distanceMeters = result.distanceMeters?.doubleValue
            timeMs = result.timeMs?.int64Value
        } catch {
            routeError = error.localizedDescription
        }
        routing = false
    }

    private func save() {
        let cleaned = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let route = SavedRoute(
            id: routeId,
            name: cleaned.isEmpty ? "Route" : cleaned,
            createdMs: existing?.createdMs ?? routeId,
            mode: mode,
            stops: stops.map(\.stop),
            polyline: polyline,
            distanceMeters: distanceMeters.map { KotlinDouble(double: $0) },
            // `long:` is C long, which Swift sees as Int; `value:` is the
            // Int64 overload, matching the KotlinDouble(value:) use elsewhere.
            timeMs: timeMs.map { KotlinLong(value: $0) },
            sharedBy: existing?.sharedBy ?? ""
        )
        RouteStore.shared.save(route: route)
        // RoutesScreen observes RouteStore's StateFlow, so it picks this up
        // on its own — nothing to tell it here.
        dismiss()
    }
}

/// `RouteStop` has no stable identity of its own — two stops can share a
/// coordinate — so the editable list wraps each one with a UUID for
/// `ForEach`/`onMove`/`onDelete`, unwrapped back to `[RouteStop]` on save.
private struct EditorStop: Identifiable {
    let id = UUID()
    var stop: RouteStop

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: stop.at.lat, longitude: stop.at.lon)
    }
}
