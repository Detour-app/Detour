import SwiftUI
import DetourShared

/// Convoy gap alerts on iOS (#656): judges the joined convoy once a second
/// and publishes the banner's lines, speaking each new alert.
///
/// The judging is `convoyGapTick` in `:shared` through `ConvoyGapHolder`, the
/// same call the phone's `ConvoyGapAlerts.kt` makes, so who rides, who leads,
/// when a rider is behind and the spoken sentence cannot differ between the
/// two. What is here is the platform's half: the timer, the reads, the
/// banner and the voice.
///
/// Runs for the life of the app, started from `RootView`, so a rider falling
/// behind while the map is not on screen is still judged. Only while a convoy
/// is joined *and* the socket is up: `activeConvoyId` survives a dropped
/// socket, and with no frames arriving every other rider would read as a lost
/// signal. Every change of either starts over from nothing, as on the phone —
/// read once a tick here rather than observed, so a drop shorter than a tick
/// is not seen as one. That, the English-only banner and this file's own
/// voice differ from the phone on purpose: docs/refactor/mapscreen/
/// 15-divergence-register.md, entry 28.
@MainActor
final class ConvoyGapAlerts: ObservableObject {

    static let shared = ConvoyGapAlerts()

    /// One line per rider with an alert; empty with no convoy or none.
    @Published private(set) var lines: [ConvoyGapLine] = []

    /// This device's route while it navigates, set by `NavModel` for the
    /// length of a nav session. The host's route is not on the wire; with
    /// none, nobody can be behind, though stopped and lost signal still work.
    var route: [LatLon] = []

    private let holder = ConvoyGapHolder()
    /// Its own synthesizer, not `NavModel`'s: this outlives a nav session.
    private let voice = NavVoice()
    /// The convoy being judged, or nil while none is joined or the socket is
    /// down — what a change of restarts from.
    private var judging: String?
    private var loop: Task<Void, Never>?

    private init() {}

    func start() {
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(nanoseconds: UInt64(ConvoyGapFeed.shared.TICK_MS) * 1_000_000)
            }
        }
    }

    private func tick() {
        let live = ConvoyLiveClient.shared
        let convoyId = live.connected ? live.activeConvoyId : nil
        if convoyId != judging {
            judging = convoyId
            holder.reset()
            lines = []
        }
        guard let convoyId else { return }
        let own = LocationBroadcast.shared.last
        let step = holder.tick(
            convoyId: convoyId,
            ownFix: own.map { LatLon(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) },
            ownSpeedKmh: max(0, own?.speed ?? 0) * 3.6,
            ownFixMs: Int64((own?.timestamp.timeIntervalSince1970 ?? 0) * 1000),
            peers: Array(live.peers.values),
            route: route
        )
        lines = step.lines
        if let utterance = step.utterance { say(utterance, talking: live.talking) }
    }

    /// Spoken guidance off keeps these quiet too, and a rider who is
    /// transmitting is not talked over — the line is dropped, and the banner
    /// still shows it.
    private func say(_ text: String, talking: Set<String>) {
        guard SettingsValues.shared.voiceGuidance, talking.isEmpty else { return }
        voice.speak(text)
    }
}

/// One line per convoy rider with an alert, red like the phone's
/// error-coloured card: a rider missing from the group is the thing to look
/// at. Draws nothing while there is none.
struct ConvoyGapBanner: View {

    @ObservedObject private var alerts = ConvoyGapAlerts.shared

    var body: some View {
        if !alerts.lines.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(alerts.lines, id: \.self) { line in
                    Text(ConvoyGapLineKt.convoyGapSentence(kind: line.kind, name: line.name))
                        .font(.headline)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .foregroundStyle(.white)
            .background(Color.red, in: RoundedRectangle(cornerRadius: 16))
        }
    }
}
