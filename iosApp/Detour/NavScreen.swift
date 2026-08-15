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
                },
                driven: drivenPolyline
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

    /// The part of the route already behind us, cut where the rider actually
    /// is rather than at the last vertex passed. `NavEngine.prefix` is the same
    /// shared function the Android map fades its driven part with, so the two
    /// platforms cut the line in the same place.
    private var drivenPolyline: [CLLocationCoordinate2D] {
        guard let fraction = model.progress?.drivenFraction, fraction > 0 else { return [] }
        return NavEngine.shared.prefix(line: route.polyline, fraction: fraction).map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
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

    /// Spoken guidance being switched off has to cut the prompt already in
    /// flight, which is what the car does (car/NavScreen.kt:479-480). Without
    /// this, muting mid-drive finishes the sentence — and the toggle is not
    /// reachable from the full-screen nav cover, so the only way to silence it
    /// was to leave navigation. Register entry 12, first sub-bug.
    ///
    /// A property and not a local: `Watcher.watch` holds the subscription for
    /// the object's life and `cancel()` tears down its whole scope, so it is
    /// cancelled in `deinit` and never in `stop()` — `stop()` runs on every
    /// `.onDisappear` and a cancelled watcher cannot be re-watched.
    private let voiceWatch = SettingsFlows.shared.voiceGuidance()

    /// The ladder, the latch and the wording: `:shared`'s, so this app, the
    /// phone and the head unit cannot word the same maneuver differently.
    /// One per session — it holds per-instruction state.
    private let announcer = NavAnnouncer()

    init() {
        voiceWatch.watch { [weak self] in
            // `watch` fires once with the current value as well as on every
            // change; stopping a synthesizer that is not speaking is a no-op,
            // so no edge detection is needed here.
            guard self?.voiceWatch.value == false else { return }
            self?.voice.stop()
        }
    }

    deinit {
        voiceWatch.cancel()
    }

    func start(route: RouteResult) {
        self.route = route
        announcer.routeChanged()
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

    /// Says whatever `NavAnnouncer` says is due for this fix. The decision and
    /// the words are the core's; this model only decides that speech is how
    /// iOS delivers them.
    private func announce(_ p: NavEngine.Progress) {
        if let text = announcer.onProgress(
            instruction: p.nextInstruction, distanceMeters: p.distanceToTurnMeters
        ) {
            say(text)
        }
    }

    private func say(_ text: String) {
        if SettingsValues.shared.voiceGuidance { voice.speak(text) }
    }
}

/// Distance as the banner shows it — to 10 m up close, 100 m further out.
private func displayDistance(_ meters: Double) -> String {
    let safe = meters.isNaN ? 0 : max(0, meters)
    if safe >= 1000 { return String(format: "%.1f km", safe / 1000) }
    if safe >= 100 { return "\(Int((safe / 100).rounded()) * 100) m" }
    return "\(Int((safe / 10).rounded()) * 10) m"
}

/// GraphHopper sign codes. The full set, not the -3…3 the doc comment on
/// RouteInstruction used to imply: ±7 are the motorway keep-left/keep-right
/// forks and -98/±8 are U-turns, and every one of them used to fall through to
/// "carry on" here — while a sharp turn drew as a U-turn. Two glyph
/// collapses remain, and both are direction-preserving so are merely
/// cosmetic, unlike drawing a sharp left as a U-turn: SF Symbols has no
/// distinct sharp-turn glyph, so a sharp turn (±3) draws the same arrow as a
/// normal turn (±2); and no direction-distinct fork glyph pair could be
/// confirmed available at this deployment target (iOS 17 / SF Symbols 4), so
/// keep-left/keep-right (±7) fall back to the slight-left/slight-right
/// arrows (±1) — a keep-left fork renders like a gentle left rather than a
/// fork, but it still points left, not right.
private func maneuverIcon(_ instruction: NavInstruction?) -> String {
    guard let instruction else { return "arrow.up" }
    switch instruction.sign {
    case -98, -8: return "arrow.uturn.left"
    case 8: return "arrow.uturn.right"
    case -7: return "arrow.up.left"
    case 7: return "arrow.up.right"
    case -3: return "arrow.turn.up.left"
    case -2: return "arrow.turn.up.left"
    case -1: return "arrow.up.left"
    case 1: return "arrow.up.right"
    case 2: return "arrow.turn.up.right"
    case 3: return "arrow.turn.up.right"
    case 4, 5: return "flag.checkered"
    case 6: return "arrow.triangle.turn.up.right.circle"
    default: return "arrow.up"
    }
}
