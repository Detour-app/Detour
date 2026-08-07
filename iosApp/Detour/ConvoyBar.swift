import SwiftUI
import DetourShared

/// The live convoy strip: who is on the map right now, who is talking, and the
/// press-and-hold that transmits.
///
/// Shown only while a convoy is joined. Joining and leaving are membership
/// operations and live on the Friends screen with the rest of them; this is the
/// part you need while riding.
struct ConvoyBar: View {

    @ObservedObject private var live = ConvoyLiveClient.shared
    @State private var transmitting = false

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
                            ForEach(live.peers.values.sorted(by: { $0.username < $1.username }),
                                    id: \.username) { peer in
                                Text(peer.username)
                                    .font(.caption)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(
                                        Capsule().fill(
                                            live.talking.contains(peer.username)
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
                Image(systemName: transmitting ? "mic.fill" : "mic")
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

    private func startTalking() {
        guard !transmitting else { return }
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
