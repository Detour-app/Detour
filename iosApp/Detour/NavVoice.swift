import AVFoundation

/// Spoken turn instructions.
///
/// The Android side takes transient-may-duck audio focus per prompt so the
/// radio keeps playing quietly underneath. `.duckOthers` with `.voicePrompt`
/// is the same bargain on iOS, and `.spokenAudio` is what tells CarPlay and a
/// connected headset to treat this as guidance rather than media — the closest
/// equivalent of USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.
///
/// The session is deactivated when the utterance ends rather than held for the
/// whole drive, so music comes back up between prompts instead of staying
/// ducked from the first turn to the last.
final class NavVoice: NSObject {

    private let synthesizer = AVSpeechSynthesizer()

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(_ text: String) {
        guard !text.isEmpty else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback, mode: .voicePrompt, options: [.duckOthers, .mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // No session, no prompt. Guidance going silent must never take the
            // navigation down with it.
            return
        }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

extension NavVoice: AVSpeechSynthesizerDelegate {
    func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance
    ) {
        try? AVAudioSession.sharedInstance().setActive(
            false, options: .notifyOthersOnDeactivation)
    }
}
