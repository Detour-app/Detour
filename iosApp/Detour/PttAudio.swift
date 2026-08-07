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
    /// enough here and [user] is only used to decide nothing else.
    func play(_ pcm: Data, from user: String) {
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
