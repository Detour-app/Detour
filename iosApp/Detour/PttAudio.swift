import AVFoundation

/// Push-to-talk capture and playback.
///
/// The wire format is fixed by the other end of the relay: 16 kHz mono signed
/// 16-bit PCM, in chunks of `SAMPLE_RATE / 25` samples (40 ms). An iPhone and
/// an Android phone are in the same convoy, so none of that is ours to choose.
/// AVAudioEngine works in 32-bit float at the hardware's own rate, so capture
/// converts down and playback converts back up.
@MainActor
final class PttAudio {

    static let shared = PttAudio()

    private static let sampleRate = 16_000.0
    private static let chunkSamples = AVAudioFrameCount(16_000 / 25)

    private let engine = AVAudioEngine()
    private var player: AVAudioPlayerNode?
    private var capturing = false

    /// The format the relay speaks. Everything is converted to or from this.
    private var wireFormat: AVAudioFormat {
        AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Self.sampleRate,
            channels: 1,
            interleaved: true)!
    }

    // MARK: Permission

    /// Whether capture may start right now.
    enum CapturePermission {
        /// The microphone is ours; start capturing.
        case granted
        /// The system alert has just gone up. It owns the touch, so this press
        /// is spent answering it and the next one transmits.
        case asking
        /// Refused, and only Settings can undo that.
        case denied
    }

    /// The record permission, asking for it the first time it is needed.
    ///
    /// Asked on the press rather than on convoy connect, which is where Android
    /// asks it (`ui/MapScreen.kt:474-478`, then refuses the press again at
    /// `ui/MapHud.kt:135-140`): a rider who never transmits is never prompted,
    /// and on iOS the alert has to be answered before a `.playAndRecord`
    /// session can be activated at all.
    ///
    /// Nothing may call [startCapture] without coming through here. Activating
    /// that session with no answer on record is the documented condition for
    /// iOS to terminate the app, and every failure path inside [startCapture]
    /// returns silently, so a refusal that reaches it is a refusal nobody sees.
    func capturePermission() -> CapturePermission {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return .granted
        case .undetermined:
            // Fire and forget: the answer arrives on another queue and this
            // press is already lost to the alert. The next press reads the
            // recorded answer instead of asking again.
            AVAudioApplication.requestRecordPermission { _ in }
            return .asking
        case .denied:
            return .denied
        @unknown default:
            return .denied
        }
    }

    // MARK: Capture

    /// Starts capture, handing each 40 ms chunk to [onChunk]. Playback stays
    /// live throughout: `.voiceChat` gives echo cancellation, which is what
    /// keeps a rider's own transmission out of everyone else's speakers — the
    /// counterpart of MODE_IN_COMMUNICATION on Android.
    func startCapture(onChunk: @escaping (Data) -> Void) {
        guard !capturing else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat,
                                    options: [.allowBluetooth, .defaultToSpeaker])
            try session.setActive(true)
        } catch {
            return
        }

        let input = engine.inputNode
        let hardware = input.outputFormat(forBus: 0)
        guard let converter = AVAudioConverter(from: hardware, to: wireFormat) else { return }

        input.installTap(onBus: 0, bufferSize: 1024, format: hardware) { [weak self] buffer, _ in
            guard let self,
                  let out = AVAudioPCMBuffer(
                    pcmFormat: self.wireFormat, frameCapacity: Self.chunkSamples)
            else { return }

            var supplied = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if supplied {
                    status.pointee = .noDataNow
                    return nil
                }
                supplied = true
                status.pointee = .haveData
                return buffer
            }
            guard error == nil, let channel = out.int16ChannelData else { return }
            let bytes = Int(out.frameLength) * MemoryLayout<Int16>.size
            onChunk(Data(bytes: channel[0], count: bytes))
        }

        do {
            try engine.start()
            capturing = true
        } catch {
            input.removeTap(onBus: 0)
        }
    }

    func stopCapture() {
        guard capturing else { return }
        engine.inputNode.removeTap(onBus: 0)
        capturing = false
    }

    // MARK: Playback

    /// Plays one incoming chunk.
    ///
    /// Android keeps an AudioTrack per speaker so two people talking at once
    /// don't interleave into noise. AVAudioEngine mixes for free — every
    /// scheduled buffer goes through the same mixer — so one player node is
    /// enough here and [riderId] is only used to decide nothing else. Now the
    /// sender's account id rather than their handle (#133) — that divergence
    /// from Android predates this change and stays; only the identifier this
    /// deliberately ignored parameter carries has been retyped.
    func play(_ pcm: Data, from riderId: String) {
        let player = ensurePlayer()
        guard let buffer = buffer(from: pcm) else { return }
        player.scheduleBuffer(buffer, completionHandler: nil)
        if !player.isPlaying { player.play() }
    }

    private func ensurePlayer() -> AVAudioPlayerNode {
        if let player { return player }
        let node = AVAudioPlayerNode()
        engine.attach(node)
        // Connect at the wire format and let the engine resample to hardware.
        engine.connect(node, to: engine.mainMixerNode, format: wireFormat)
        if !engine.isRunning { try? engine.start() }
        player = node
        return node
    }

    private func buffer(from pcm: Data) -> AVAudioPCMBuffer? {
        let frames = AVAudioFrameCount(pcm.count / MemoryLayout<Int16>.size)
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: wireFormat, frameCapacity: frames),
              let channel = buffer.int16ChannelData
        else { return nil }
        buffer.frameLength = frames
        pcm.withUnsafeBytes { raw in
            guard let base = raw.bindMemory(to: Int16.self).baseAddress else { return }
            channel[0].update(from: base, count: Int(frames))
        }
        return buffer
    }
}
