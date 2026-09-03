import SwiftUI
import DetourShared

/// The active convoy's membership, resolved from `ConvoysStore` — positions
/// and votes carry an id and no handle now (#133), so this is what turns one
/// back into the name to draw. Mirrors Android's `activeConvoyMembers` in
/// MapScreen.kt (`convoysState.convoys.firstOrNull { it.id == activeConvoyId
/// }?.members.orEmpty()`). One instance shared between `MapScreen` and
/// `ConvoyBar` (passed in, not re-created) so the two don't each hold their
/// own redundant subscription to the same store.
@MainActor
final class ActiveConvoyMembersModel: ObservableObject {
    @Published private(set) var convoys: [DetourShared.Group] = []

    private let watcher = FeatureFlows.shared.convoys()

    init() {
        convoys = watcher.value.convoys
        watcher.watch { [weak self] in self?.convoys = self?.watcher.value.convoys ?? [] }
    }

    deinit { watcher.cancel() }

    /// The members of [convoyId], or empty for `nil` (no convoy joined) or an
    /// id this device's own convoy list doesn't (yet) know.
    func members(of convoyId: String?) -> [GroupMember] {
        guard let convoyId else { return [] }
        return convoys.first { $0.id == convoyId }?.members ?? []
    }
}

/// The live convoy strip: who is on the map right now, who is talking, and the
/// press-and-hold that transmits.
///
/// Shown only while a convoy is joined. Joining and leaving are membership
/// operations and live on the Friends screen with the rest of them; this is the
/// part you need while riding.
struct ConvoyBar: View {

    @ObservedObject private var live = ConvoyLiveClient.shared
    @ObservedObject var members: ActiveConvoyMembersModel
    @State private var transmitting = false

    /// Set when the microphone has been refused, so the button can say so
    /// rather than doing nothing. State and not a computed property: the press
    /// is the only moment the answer is read, and `recordPermission` publishes
    /// nothing to observe.
    @State private var micDenied = false

    var body: some View {
        if live.activeConvoyId != nil {
            HStack(spacing: 12) {
                Circle()
                    .fill(live.connected ? .green : .orange)
                    .frame(width: 8, height: 8)

                if live.peers.isEmpty {
                    Text(live.connected ? "Nobody else here yet" : "Connecting…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            // `live.peers`' keys are the peer's account id, not
                            // their handle (#133) — FriendPosition itself
                            // carries no handle at all any more, so the name
                            // to draw comes from convoy membership instead,
                            // one id lookup at a time. Sorted by the resolved
                            // handle, matching what used to sort by
                            // `.username` directly.
                            ForEach(live.peers.keys.sorted(by: { handle(for: $0) < handle(for: $1) }),
                                    id: \.self) { riderId in
                                Text(handle(for: riderId))
                                    .font(.caption)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(
                                        Capsule().fill(
                                            live.talking.contains(riderId)
                                                ? Color.accentColor.opacity(0.3)
                                                : Color.secondary.opacity(0.15)))
                            }
                        }
                    }
                }

                Spacer(minLength: 0)

                // Press and hold, exactly like the hardware button it stands in
                // for: releasing must end the transmission even if the finger
                // slides off the button first.
                Image(systemName: micGlyph)
                    .font(.title3)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(transmitting
                                              ? Color.accentColor
                                              : Color.secondary.opacity(0.15)))
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { _ in startTalking() }
                            .onEnded { _ in stopTalking() }
                    )
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.regularMaterial, in: Capsule())
            .onDisappear { stopTalking() }
        }
    }

    private var micGlyph: String {
        if micDenied { return "mic.slash" }
        return transmitting ? "mic.fill" : "mic"
    }

    /// The handle to draw for a peer id, from the active convoy's own
    /// membership — `GroupsKt.handleFor` is `List<GroupMember>.handleFor`
    /// (Groups.kt), an extension on a generic stdlib collection rather than on
    /// one of this module's own classes, so Kotlin/Native's Objective-C
    /// export lands it on the file's `...Kt` facade instead of as a member —
    /// same shape as `CircleEventsKt.placeEventFromRelayFrame` elsewhere in
    /// this app. `""` for an id membership doesn't (yet) know, same as
    /// everywhere else `handleFor` is used.
    private func handle(for riderId: String) -> String {
        GroupsKt.handleFor(members.members(of: live.activeConvoyId), riderId: RiderId(value: riderId))
    }

    private func startTalking() {
        guard !transmitting else { return }
        // The microphone is asked for here, on the press — see
        // PttAudio.capturePermission(). A press spent on the alert, or refused
        // outright, must not open a transmission: every peer would light a
        // "talking" badge for audio that is never coming.
        switch PttAudio.shared.capturePermission() {
        case .granted:
            micDenied = false
        case .asking:
            micDenied = false
            return
        case .denied:
            micDenied = true
            return
        }
        transmitting = true
        ConvoyLiveClient.shared.sendPttStart()
        PttAudio.shared.startCapture { chunk in
            ConvoyLiveClient.shared.sendAudioChunk(chunk)
        }
    }

    private func stopTalking() {
        guard transmitting else { return }
        transmitting = false
        PttAudio.shared.stopCapture()
        ConvoyLiveClient.shared.sendPttEnd()
    }
}
