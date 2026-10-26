import SwiftUI
import CoreLocation
import DetourShared

/// In-app turn-by-turn.
///
/// All the route-following maths — where you are along the polyline, which
/// maneuver is next, how far is left, what the posted limit is here — is
/// `NavEngine` in `:shared`, the same pure functions the Android car screen
/// calls. This file is the banners, the camera and the voice.
struct NavScreen: View {

    let route: RouteResult
    let destinationName: String?
    let onExit: () -> Void

    @EnvironmentObject private var recorder: TripRecorder
    @StateObject private var model = NavModel()
    @Environment(\.scenePhase) private var scenePhase

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
                driven: drivenPolyline,
                cameras: model.cameras,
                congestion: model.traffic?.runs ?? [],
                incidents: model.traffic?.incidents ?? []
            )
            .ignoresSafeArea()

            VStack(spacing: 10) {
                banner
                if let countdown = model.cameraCountdown {
                    CameraBanner(countdown: countdown)
                }
                if let traffic = model.traffic {
                    TrafficBanner(traffic: traffic)
                }
                ConvoyGapBanner()
                    .padding(.horizontal)
            }

            VStack {
                Spacer()
                footer
            }
        }
        .task { model.start(route: route) }
        // HERE traffic (#654): keyed on the key and on the app being in the
        // foreground, so a new key restarts the loop, and backgrounding the
        // app — screen off, or another app — cancels it: nothing is asked
        // while nobody can see the map. Leaving navigation cancels it too.
        .task(id: TrafficLoopKey(key: model.trafficKey, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            await model.runTraffic(route: route)
        }
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
                // The instruction text beside it already says the turn.
                .accessibilityHidden(true)

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
                if let ms = model.etaProgress?.remainingTimeMs?.int64Value {
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
            .accessibilityLabel("End navigation")
            .buttonStyle(.bordered)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding()
    }
}

/// The camera a warning was spoken for and the distance still left to it,
/// under the turn banner while it is ahead (#550). `CameraWarner` in `:shared`
/// decides when it shows: from the warning until the camera is passed or out
/// of reach, reading `CameraWarner.Step.countdown` (#548).
private struct CameraBanner: View {
    let countdown: CameraCountdown

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "camera.fill")
                .font(.title3)
                .foregroundStyle(.red)
                // The text beside it already says it is a camera.
                .accessibilityHidden(true)
            Text(displayDistance(countdown.distanceMeters))
                .font(.title3.monospacedDigit().weight(.bold))
            Text(countdown.text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
        .accessibilityElement(children: .combine)
    }
}

/// "+7 min traffic" or "Road closed ahead" under the turn banner (#654).
/// Nothing below `TrafficOnRoute.BANNER_MIN_DELAY_MIN` and no closure: a free
/// road says nothing. Which is which is `TrafficOnRoute`'s, in `:shared`.
private struct TrafficBanner: View {
    let traffic: TrafficOnRoute

    var body: some View {
        if traffic.closedAhead || traffic.bannerDelayMinutes != nil {
            HStack(spacing: 10) {
                Image(systemName: traffic.closedAhead ? "nosign" : "exclamationmark.triangle.fill")
                    .font(.title3)
                    .foregroundStyle(traffic.closedAhead ? .red : .orange)
                    // The text beside it already says what it is.
                    .accessibilityHidden(true)
                if traffic.closedAhead {
                    Text("Road closed ahead")
                        .font(.title3.weight(.bold))
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("+\(traffic.bannerDelayMinutes?.intValue ?? 0) min traffic")
                            .font(.title3.monospacedDigit().weight(.bold))
                        Text("on the route ahead")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal)
            .accessibilityElement(children: .combine)
        }
    }
}

/// What restarts the traffic loop: a different key, or the app coming back
/// to the foreground.
private struct TrafficLoopKey: Equatable {
    let key: String
    let active: Bool
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
    /// Speed camera markers, for `NavScreen`'s own map. A separate prefetch
    /// from `MapScreen`'s — see `CameraPrefetchModel`'s doc — since this
    /// screen can outlive the map screen's own polling being paused, or start
    /// from a cold `fullScreenCover` with nothing prefetched yet.
    @Published private(set) var cameras: [SpeedCameras.Camera] = []
    /// The warned camera still ahead, for `CameraBanner`; nil when none is.
    @Published private(set) var cameraCountdown: CameraCountdown?
    /// HERE traffic on the route ahead (#654): what the map colours and the
    /// banner say. Nil with no key, or before the first answer.
    @Published private(set) var traffic: TrafficOnRoute?
    /// The stored HERE key; non-blank means it passed Test key and traffic
    /// is on (`TrafficKeyPresenter`).
    @Published private(set) var trafficKey = ""

    /// `progress` with the traffic delay added to the time left, which is
    /// what the footer's time shows — `TrafficHolder.withDelay`.
    var etaProgress: NavEngine.Progress? {
        progress.map { trafficHolder.withDelay(progress: $0, traffic: traffic) }
    }

    private var route: RouteResult?
    private let voice = NavVoice()
    private let cameraAlerts = CameraPrefetchModel()
    private let cameraWarner = CameraWarnerHolder()
    /// The HERE corridor for this nav session: per-tile cache, TTLs, backoff.
    private let trafficHolder = TrafficHolder()
    /// Same lifetime rule as `voiceWatch` below: cancelled in `deinit` only.
    private let trafficKeyWatch = SettingsFlows.shared.hereTrafficKey()

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
        trafficKeyWatch.watch { [weak self] in
            guard let self else { return }
            self.trafficKey = self.trafficKeyWatch.value
        }
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
        trafficKeyWatch.cancel()
    }

    func start(route: RouteResult) {
        self.route = route
        announcer.routeChanged()
        ConvoyGapAlerts.shared.route = route.polyline
    }

    func stop() {
        voice.stop()
        route = nil
        ConvoyGapAlerts.shared.route = []
        cameraCountdown = nil
        traffic = nil
    }

    /// The traffic loop: one `TrafficHolder.tick` every
    /// `TrafficRound.TICK_MS` until the task is cancelled. Not how often HERE
    /// is asked — that is `TrafficCorridor`'s TTLs in `:shared` — but how soon
    /// the delay follows the rider down the route. No key, no requests, and
    /// the layer is cleared.
    func runTraffic(route: RouteResult) async {
        guard !trafficKey.isEmpty else {
            traffic = nil
            return
        }
        while !Task.isCancelled {
            let next = try? await trafficHolder.tick(key: trafficKey, route: route, progress: progress)
            if Task.isCancelled { return }
            traffic = next
            try? await Task.sleep(nanoseconds: UInt64(TrafficRound.shared.TICK_MS) * 1_000_000)
        }
    }

    func update(with fix: CLLocation) {
        guard let route else { return }
        let here = LatLon(lat: fix.coordinate.latitude, lon: fix.coordinate.longitude)
        guard let p = NavEngine.shared.progress(route: route, pos: here) else { return }
        progress = p
        announce(p)
        warnOfCameras(at: here, fix: fix, limitKmh: p.speedLimitKmh)
    }

    /// The enforcement-camera warning. `CameraWarner` in `:shared` decides
    /// whether one is worth interrupting for and words it; this speaks that
    /// wording once, through the same `NavVoice` a turn instruction uses, and
    /// publishes the countdown `CameraBanner` draws on every fix after it.
    ///
    /// The posted limit judged is the route's own (`p.speedLimitKmh`) rather
    /// than an ambient sign — this surface has no ambient sign to fall back
    /// on yet, the same reasoning the head unit's `car/NavScreen.kt` gives
    /// for passing its route limit alone.
    private func warnOfCameras(at here: LatLon, fix: CLLocation, limitKmh: KotlinDouble?) {
        cameraAlerts.update(with: fix)
        cameras = cameraAlerts.cameras
        let warning = cameraWarner.onFix(
            cameras: cameras,
            at: here,
            // CoreLocation reports a negative course when it has no usable
            // bearing, same convention `SectionAverageModel` follows.
            headingDeg: fix.course >= 0 ? KotlinDouble(value: fix.course) : nil,
            speedKmh: max(0, fix.speed) * 3.6,
            limitKmh: limitKmh
        )
        cameraCountdown = cameraWarner.countdown
        if let warning { say(warning.text) }
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
