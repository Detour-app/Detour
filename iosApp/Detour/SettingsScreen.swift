import SwiftUI
import DetourShared

/// Settings, mapped one-for-one onto the Android screen.
///
/// Every value lives in the shared `Settings` object and is persisted through
/// the same keys, so a user's choices mean the same thing on either platform —
/// they just land in NSUserDefaults here rather than SharedPreferences.
struct SettingsScreen: View {

    @StateObject private var model = SettingsModel()
    @State private var serverURL = ""
    // Carried through load/save untouched: this screen has no editors for the
    // per-service addresses yet, and saving defaults over values set
    // elsewhere would silently unconfigure them.
    @State private var apiURL = ""
    @State private var routingURL = ""
    @State private var geocoderURL = ""
    @State private var idpIssuer = ""
    // The address Save refused, shown under the button (#437).
    @State private var invalidAddress: String?

    var body: some View {
        NavigationStack {
            // Split into sections rather than one Form literal: as a single
            // expression this was past what the type-checker will attempt
            // ("unable to type-check in reasonable time").
            Form {
                mapSection
                routingSection
                cameraSection
                tripsSection
                serverSection
                privacySection
                dangerSection
            }
            .navigationTitle("Settings")
            .task { loadServer() }
        }
    }

    private var mapSection: some View {
        Section {
            routeColorPicker
            Toggle("Fog of war", isOn: Binding(
                get: { model.fogEnabled },
                set: { Settings.shared.setFogEnabled(value: $0) }
            ))
            VStack(alignment: .leading) {
                Text("Fog radius: \(Int(model.fogRadius)) m")
                Slider(
                    value: Binding(
                        get: { Double(model.fogRadius) },
                        set: { Settings.shared.setFogRadiusMeters(value: Float($0)) }
                    ),
                    in: 50...500,
                    step: 10)
            }
            VStack(alignment: .leading) {
                Text(String(format: "Default zoom: %.1f", model.defaultZoom))
                Slider(
                    value: Binding(
                        get: { Double(model.defaultZoom) },
                        set: { Settings.shared.setDefaultZoom(value: Float($0)) }
                    ),
                    in: zoomRange,
                    step: 0.5)
            }
        } header: {
            Text("Map")
        } footer: {
            Text("While navigating, the part of the route you have already driven fades to a darker shade of the line colour.")
        }
    }

    /// The route line's colour. A menu rather than a segmented control: eight
    /// entries would be unreadable in a row, and the row label carries the
    /// current choice the way the rest of this Form does.
    private var routeColorPicker: some View {
        Picker("Route line", selection: Binding(
            get: { model.routeColor },
            set: { Settings.shared.setRouteColor(value: $0) }
        )) {
            ForEach(Enums.shared.routeColors, id: \.name) { color in
                Text(RouteColors.shared.label(color: color)).tag(color)
            }
        }
    }

    /// Built separately because `a...b` will not bind across a line break in an
    /// argument list.
    private var zoomRange: ClosedRange<Double> {
        Double(Enums.shared.minZoom)...Double(Enums.shared.maxZoom)
    }

    private var routingSection: some View {
        Section {
            Toggle("Avoid motorways", isOn: Binding(
                get: { model.avoidHighways },
                set: { Settings.shared.setAvoidHighways(value: $0) }
            ))
            Toggle("Avoid small roads", isOn: Binding(
                get: { model.avoidSmallRoads },
                set: { Settings.shared.setAvoidSmallRoads(value: $0) }
            ))
            Toggle("Spoken directions", isOn: Binding(
                get: { model.voiceGuidance },
                set: { Settings.shared.setVoiceGuidance(value: $0) }
            ))
        } header: {
            Text("Routing")
        } footer: {
            Text("Small roads are the narrow rural lanes a router picks because they are short, not because anyone wants to drive them.")
        }
    }

    /// Camera-warning settings (#496); `CameraWarner` in `:shared` reads them.
    private var cameraSection: some View {
        Section {
            Toggle("Warn of cameras ahead", isOn: Binding(
                get: { model.cameraWarnings },
                set: { Settings.shared.setCameraWarnings(value: $0) }
            ))
            if model.cameraWarnings {
                Toggle("Warn even when not speeding", isOn: Binding(
                    get: { model.cameraWarnNotSpeeding },
                    set: { Settings.shared.setCameraWarnNotSpeeding(value: $0) }
                ))
                Toggle("Warn when the limit is unknown", isOn: Binding(
                    get: { model.cameraWarnLimitUnknown },
                    set: { Settings.shared.setCameraWarnLimitUnknown(value: $0) }
                ))
            }
        } header: {
            Text("Camera warnings")
        } footer: {
            Text("Spoken while navigating. A speed camera is otherwise only announced when you are over its known limit; red-light cameras always are.")
        }
    }

    private var tripsSection: some View {
        Section {
            Toggle("Record drives automatically", isOn: Binding(
                get: { model.autoDetect },
                set: { Settings.shared.setAutoDetectDrives(value: $0) }
            ))
        } header: {
            Text("Trips")
        } footer: {
            Text("Needs Always location access. Without it a ride only records while Detour is open.")
        }
    }

    private var serverSection: some View {
        Section {
            TextField("https://your.server", text: $serverURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            TextField("Sign-in realm (deprecated)", text: $idpIssuer)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            Button("Save server") { saveServer() }
            if let invalidAddress {
                Text("“\(invalidAddress)” is not a server address — use http:// or https:// and a host name.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
            if RoutingServer.shared.loadCustom() != nil {
                Button("Use built-in server", role: .destructive) {
                    RoutingServer.shared.clearCustom()
                    loadServer()
                }
            }
        } header: {
            Text("Own server")
        } footer: {
            Text("""
                One address reaches routing, search, sync and convoys — the tunnel \
                routes by path. Leave blank to use the built-in defaults. The realm \
                field is deprecated: newer servers tell the app which realm to use, \
                so leave it empty unless your server has not been updated. Anything \
                typed there still wins over what the server says.
                """)
        }
    }

    private var privacySection: some View {
        Section {
            Toggle("Share fog with friends", isOn: Binding(
                get: { model.shareFog },
                set: { value in
                    Settings.shared.setShareFog(value: value)
                    // Tell the server now: leaving it to the next trip sync
                    // would keep serving traces after the switch went off.
                    Task { _ = try? await SyncClient.shared.sync() }
                }
            ))
            Toggle("Public search fallback", isOn: Binding(
                get: { model.publicGeocoderFallback },
                set: { Settings.shared.setGeocoderPublicFallback(value: $0) }
            ))
        } header: {
            Text("Privacy")
        } footer: {
            Text("The fallback sends your query and approximate location to komoot's public Photon when your own geocoder is unreachable.")
        }
    }

    private var dangerSection: some View {
        Section("Danger") {
            Button("Erase fog of war", role: .destructive) {
                TraceStore.shared.clear()
            }
        }
    }

    private func loadServer() {
        let config = RoutingServer.shared.load()
        serverURL = config.url
        apiURL = config.apiUrl
        routingURL = config.routingUrl
        geocoderURL = config.geocoderUrl
        idpIssuer = config.idpIssuer
    }

    private func saveServer() {
        let config = ServerConfig(
            url: serverURL,
            apiUrl: apiURL,
            routingUrl: routingURL,
            geocoderUrl: geocoderURL,
            idpIssuer: idpIssuer,
            enabled: true
        )
        // Same shared rule Android's Save applies, so "not a url" is refused, not stored.
        invalidAddress = config.invalidAddress
        if invalidAddress != nil { return }
        RoutingServer.shared.save(config: config)
    }
}

/// The settings this screen binds to, mirrored out of Kotlin StateFlows.
@MainActor
final class SettingsModel: ObservableObject {

    @Published var fogEnabled = true
    @Published var routeColor: Settings.RouteColor = Enums.shared.defaultRouteColor
    @Published var fogRadius: Float = 200
    @Published var defaultZoom: Float = 16
    @Published var avoidHighways = false
    @Published var avoidSmallRoads = false
    @Published var voiceGuidance = true
    @Published var cameraWarnings = true
    @Published var cameraWarnNotSpeeding = false
    @Published var cameraWarnLimitUnknown = false
    @Published var autoDetect = true
    @Published var shareFog = false
    @Published var publicGeocoderFallback = true

    private let fog = SettingsFlows.shared.fogEnabled()
    private let lineColor = SettingsFlows.shared.routeColor()
    private let radius = SettingsFlows.shared.fogRadiusMeters()
    private let zoom = SettingsFlows.shared.defaultZoom()
    private let highways = SettingsFlows.shared.avoidHighways()
    private let smallRoads = SettingsFlows.shared.avoidSmallRoads()
    private let voice = SettingsFlows.shared.voiceGuidance()
    private let cameras = SettingsFlows.shared.cameraWarnings()
    private let camerasNotSpeeding = SettingsFlows.shared.cameraWarnNotSpeeding()
    private let camerasLimitUnknown = SettingsFlows.shared.cameraWarnLimitUnknown()
    private let auto = SettingsFlows.shared.autoDetectDrives()
    private let fogSharing = SettingsFlows.shared.shareFog()
    private let fallback = SettingsFlows.shared.geocoderPublicFallback()

    init() {
        fog.watch { [weak self] in self?.fogEnabled = self?.fog.value ?? true }
        lineColor.watch { [weak self] in
            self?.routeColor = self?.lineColor.value ?? Enums.shared.defaultRouteColor
        }
        radius.watch { [weak self] in self?.fogRadius = self?.radius.value ?? 200 }
        zoom.watch { [weak self] in self?.defaultZoom = self?.zoom.value ?? 16 }
        highways.watch { [weak self] in self?.avoidHighways = self?.highways.value ?? false }
        smallRoads.watch { [weak self] in self?.avoidSmallRoads = self?.smallRoads.value ?? false }
        voice.watch { [weak self] in self?.voiceGuidance = self?.voice.value ?? true }
        cameras.watch { [weak self] in self?.cameraWarnings = self?.cameras.value ?? true }
        camerasNotSpeeding.watch { [weak self] in
            self?.cameraWarnNotSpeeding = self?.camerasNotSpeeding.value ?? false
        }
        camerasLimitUnknown.watch { [weak self] in
            self?.cameraWarnLimitUnknown = self?.camerasLimitUnknown.value ?? false
        }
        auto.watch { [weak self] in self?.autoDetect = self?.auto.value ?? true }
        fogSharing.watch { [weak self] in self?.shareFog = self?.fogSharing.value ?? false }
        fallback.watch { [weak self] in
            self?.publicGeocoderFallback = self?.fallback.value ?? true
        }
    }

    deinit {
        [fog, lineColor, radius, zoom, highways, smallRoads, voice, cameras, camerasNotSpeeding,
         camerasLimitUnknown, auto, fogSharing, fallback]
            .forEach { $0.cancel() }
    }
}
