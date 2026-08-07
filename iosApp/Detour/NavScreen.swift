import SwiftUI
import CoreLocation
import DetourShared

/// In-app turn-by-turn.
///
/// All the route-following maths — where you are along the polyline, which
/// maneuver is next, how far is left, what the posted limit is here — is
/// `NavEngine` in `:shared`, the same pure functions the Android car screen
/// calls. This file is the banner, the camera and the voice.
struct NavScreen: View {

    let route: RouteResult
    let destinationName: String?
    let onExit: () -> Void

    @EnvironmentObject private var recorder: TripRecorder
    @StateObject private var model = NavModel()

    var body: some View {
        ZStack(alignment: .top) {
            MapView(
                center: recorder.lastFix?.coordinate,
                destination: route.polyline.last.map {
                    CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
                },
                route: route.polyline.map {
                    CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
                }
            )
            .ignoresSafeArea()

            banner

            VStack {
                Spacer()
                footer
            }
        }
        .task { model.start(route: route) }
        .onDisappear { model.stop() }
        .onChange(of: recorder.lastFix) { _, fix in
            guard let fix else { return }
            model.update(with: fix)
        }
    }

    private var banner: some View {
        HStack(spacing: 14) {
            Image(systemName: maneuverIcon(model.progress?.nextInstruction))
                .font(.system(size: 34, weight: .semibold))
                .frame(width: 48)

            VStack(alignment: .leading, spacing: 2) {
                Text(model.progress.map { displayDistance($0.distanceToTurnMeters) } ?? "—")
                    .font(.title2.monospacedDigit().weight(.bold))
                Text(model.progress?.nextInstruction?.text ?? "Continue")
                    .font(.subheadline)
                    .lineLimit(2)
                // The "then…" pill: what comes after the maneuver being shown.
                if let next = model.progress?.nextNextInstruction {
                    Text("then \(next.text)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
    }

    private var footer: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.progress.map { formatDistanceKm($0.remainingMeters) } ?? "—")
                    .font(.headline.monospacedDigit())
                if let ms = model.progress?.remainingTimeMs?.int64Value {
                    Text(formatDurationHistory(ms))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if let limit = model.progress?.speedLimitKmh?.doubleValue {
                SpeedLimitSign(kmh: limit)
            }
            Button(role: .destructive, action: onExit) {
                Image(systemName: "xmark").frame(width: 44, height: 44)
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding()
    }
}

/// The European round white-on-red plate, which is what the posted limits from
/// OSM mean here.
private struct SpeedLimitSign: View {
    let kmh: Double

    var body: some View {
        Text("\(Int(kmh))")
            .font(.headline.monospacedDigit().weight(.bold))
            .foregroundStyle(.black)
            .frame(width: 44, height: 44)
            .background(Circle().fill(.white))
            .overlay(Circle().stroke(.red, lineWidth: 5))
    }
}

@MainActor
final class NavModel: ObservableObject {

    @Published private(set) var progress: NavEngine.Progress?

    private var route: RouteResult?
    private let voice = NavVoice()

    // Voice bookkeeping: which instruction is being announced, and how close it
    // was when we last said something about it.
    private var voiceStepKey: Int32 = -1
    private var voicePhase = 0
    private var startAnnounced = false

    private static let voiceFarM = 800.0
    private static let voiceNearM = 300.0
    private static let voiceNowM = 80.0

    func start(route: RouteResult) {
        self.route = route
        startAnnounced = false
        voiceStepKey = -1
        voicePhase = 0
    }

    func stop() {
        voice.stop()
        route = nil
    }

    func update(with fix: CLLocation) {
        guard let route else { return }
        let here = LatLon(lat: fix.coordinate.latitude, lon: fix.coordinate.longitude)
        guard let p = NavEngine.shared.progress(route: route, pos: here) else { return }
        progress = p
        announce(p)
    }

    /// Announces the upcoming maneuver as it comes up, once per threshold.
    private func announce(_ p: NavEngine.Progress) {
        guard let instruction = p.nextInstruction else { return }
        if instruction.startIndex != voiceStepKey {
            voiceStepKey = instruction.startIndex
            voicePhase = 0
        }
        let distance = p.distanceToTurnMeters
        let phase: Int
        switch distance {
        case ..<Self.voiceNowM: phase = 3
        case ..<Self.voiceNearM: phase = 2
        case ..<Self.voiceFarM: phase = 1
        default: phase = 0
        }
        let cue = instruction.text.isEmpty ? "Continue" : instruction.text

        // The first prompt of the drive ignores the thresholds: pressing Start
        // and being told nothing for the next 3 km is indistinguishable from
        // voice being broken.
        if !startAnnounced {
            startAnnounced = true
            voicePhase = phase
            say(phase == 3 ? cue : "In \(spokenDistance(distance)), \(cue)")
            return
        }
        guard phase != 0, phase > voicePhase else { return }
        voicePhase = phase
        say(phase == 3 ? cue : "In \(spokenDistance(distance)), \(cue)")
    }

    private func say(_ text: String) {
        if SettingsValues.shared.voiceGuidance { voice.speak(text) }
    }
}

/// Distance as a driver would say it, for the spoken prompts.
private func spokenDistance(_ meters: Double) -> String {
    switch meters {
    case 1500...: return "\(Int((meters / 1000).rounded())) kilometers"
    case 950...: return "1 kilometer"
    case 100...: return "\(Int((meters / 100).rounded()) * 100) meters"
    default: return "\(Int((meters / 10).rounded()) * 10) meters"
    }
}

/// Distance as the banner shows it — to 10 m up close, 100 m further out.
private func displayDistance(_ meters: Double) -> String {
    let safe = meters.isNaN ? 0 : max(0, meters)
    if safe >= 1000 { return String(format: "%.1f km", safe / 1000) }
    if safe >= 100 { return "\(Int((safe / 100).rounded()) * 100) m" }
    return "\(Int((safe / 10).rounded()) * 10) m"
}

/// GraphHopper sign codes: -3…3 are the turns, 0 straight, 4 finish,
/// 6 roundabout. Anything unrecognised falls back to "carry on", which is the
/// safe thing to draw when we do not know.
private func maneuverIcon(_ instruction: NavInstruction?) -> String {
    guard let instruction else { return "arrow.up" }
    switch instruction.sign {
    case -3: return "arrow.uturn.left"
    case -2: return "arrow.turn.up.left"
    case -1: return "arrow.up.left"
    case 1: return "arrow.up.right"
    case 2: return "arrow.turn.up.right"
    case 3: return "arrow.uturn.right"
    case 4, 5: return "flag.checkered"
    case 6: return "arrow.triangle.turn.up.right.circle"
    default: return "arrow.up"
    }
}
