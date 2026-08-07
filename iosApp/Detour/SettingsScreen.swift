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
    @State private var clientId = ""
    @State private var clientSecret = ""
    @State private var savingServer = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Map") {
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
                            in: 50...500, step: 10)
                    }
                    VStack(alignment: .leading) {
                        Text(String(format: "Default zoom: %.1f", model.defaultZoom))
                        Slider(
                            value: Binding(
                                get: { Double(model.defaultZoom) },
                                set: { Settings.shared.setDefaultZoom(value: Float($0)) }
                            ),
                            in: Double(Enums.shared.minZoom)
                                ...Double(Enums.shared.maxZoom),
                            step: 0.5)
                    }
                }

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

                Section {
                    TextField("https://your.server", text: $serverURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("CF-Access-Client-Id", text: $clientId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("CF-Access-Client-Secret", text: $clientSecret)
                    Button(savingServer ? "Saving…" : "Save server") { saveServer() }
                        .disabled(savingServer)
                    if RoutingServer.shared.loadCustom() != nil {
                        Button("Use built-in server", role: .destructive) {
                            RoutingServer.shared.clearCustom()
                            loadServer()
                        }
                    }
                } header: {
                    Text("Own server")
                } footer: {
                    Text("One address reaches routing, search, sync and convoys — the tunnel routes by path. Leave blank to use the built-in defaults.")
                }

                Section {
                    Toggle("Share fog with friends", isOn: Binding(
                        get: { model.shareFog },
                        set: {
                            Settings.shared.setShareFog(value: $0)
                            // Tell the server now: leaving it to the next trip
                            // sync would keep serving traces after the switch
                            // went off.
                            Task { try? await SyncClient.shared.sync() }
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

                Section("Danger") {
                    Button("Erase fog of war", role: .destructive) {
                        TraceStore.shared.clear()
                    }
                }
            }
            .navigationTitle("Settings")
            .task { loadServer() }
        }
    }

    private func loadServer() {
        let config = RoutingServer.shared.load()
        serverURL = config.url
        clientId = config.clientId
        clientSecret = config.clientSecret
    }

    private func saveServer() {
        savingServer = true
        RoutingServer.shared.save(config: ServerConfig(
            url: serverURL,
            clientId: clientId,
            clientSecret: clientSecret,
            enabled: true
        ))
        savingServer = false
    }
}

/// The settings this screen binds to, mirrored out of Kotlin StateFlows.
@MainActor
final class SettingsModel: ObservableObject {

    @Published var fogEnabled = true
    @Published var fogRadius: Float = 200
    @Published var defaultZoom: Float = 16
    @Published var avoidHighways = false
    @Published var avoidSmallRoads = false
    @Published var voiceGuidance = true
    @Published var autoDetect = true
    @Published var shareFog = false
    @Published var publicGeocoderFallback = true

    private let fog = SettingsFlows.shared.fogEnabled()
    private let radius = SettingsFlows.shared.fogRadiusMeters()
    private let zoom = SettingsFlows.shared.defaultZoom()
    private let highways = SettingsFlows.shared.avoidHighways()
    private let smallRoads = SettingsFlows.shared.avoidSmallRoads()
    private let voice = SettingsFlows.shared.voiceGuidance()
    private let auto = SettingsFlows.shared.autoDetectDrives()
    private let fogSharing = SettingsFlows.shared.shareFog()
    private let fallback = SettingsFlows.shared.geocoderPublicFallback()

    init() {
        fog.watch { [weak self] in self?.fogEnabled = self?.fog.value ?? true }
        radius.watch { [weak self] in self?.fogRadius = self?.radius.value ?? 200 }
        zoom.watch { [weak self] in self?.defaultZoom = self?.zoom.value ?? 16 }
        highways.watch { [weak self] in self?.avoidHighways = self?.highways.value ?? false }
        smallRoads.watch { [weak self] in self?.avoidSmallRoads = self?.smallRoads.value ?? false }
        voice.watch { [weak self] in self?.voiceGuidance = self?.voice.value ?? true }
        auto.watch { [weak self] in self?.autoDetect = self?.auto.value ?? true }
        fogSharing.watch { [weak self] in self?.shareFog = self?.fogSharing.value ?? false }
        fallback.watch { [weak self] in
            self?.publicGeocoderFallback = self?.fallback.value ?? true
        }
    }

    deinit {
        [fog, radius, zoom, highways, smallRoads, voice, auto, fogSharing, fallback]
            .forEach { $0.cancel() }
    }
}
