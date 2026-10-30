import AVFoundation

/// Speaks replies with the iPhone's built-in voices, sentence by sentence as they stream in.
final class Voice: NSObject, AVSpeechSynthesizerDelegate {
    var onStartedTalking: () -> Void = {}
    var onFinishedTalking: () -> Void = {}

    private let synth = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    private var pending = 0
    private var replyFinished = true
    private var sentence = ""

    override init() {
        // best-quality British English voice installed on the phone
        let gb = AVSpeechSynthesisVoice.speechVoices().filter { $0.language == "en-GB" }
        voice = gb.max(by: { $0.quality.rawValue < $1.quality.rawValue }) ?? AVSpeechSynthesisVoice(language: "en-GB")
        super.init()
        synth.delegate = self
        synth.usesApplicationAudioSession = true
    }

    func beginReply() { sentence = ""; replyFinished = false }

    func feed(_ text: String) {
        sentence += text
        while let cut = sentenceEnd(sentence) {
            say(String(sentence[..<cut]))
            sentence = String(sentence[cut...])
        }
    }

    func endReply() {
        say(sentence)
        sentence = ""
        replyFinished = true
        if pending == 0 { onFinishedTalking() }
    }

    func shutUp() {
        synth.stopSpeaking(at: .immediate)
        pending = 0
        sentence = ""
    }

    private func say(_ raw: String) {
        let clean = Voice.cleanForSpeech(raw)
        guard !clean.isEmpty else { return }
        let u = AVSpeechUtterance(string: clean)
        u.voice = voice
        u.pitchMultiplier = 1.15
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        pending += 1
        synth.speak(u)
    }

    private func sentenceEnd(_ s: String) -> String.Index? {
        var prev: Character?
        var count = 0
        for i in s.indices {
            let c = s[i]
            if let p = prev, ".!?\n".contains(p), c.isWhitespace, count > 3 { return i }
            prev = c
            count += 1
        }
        return nil
    }

    static func cleanForSpeech(_ s: String) -> String {
        var out = s.replacingOccurrences(of: "[*#_`~>|]", with: " ", options: .regularExpression)
        out = String(out.unicodeScalars.filter { !($0.properties.isEmojiPresentation || $0.properties.isExtendedPictographic) }
                        .map(Character.init))
        return out.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    // MARK: delegate (called on the main thread)

    func speechSynthesizer(_ s: AVSpeechSynthesizer, didStart u: AVSpeechUtterance) {
        DispatchQueue.main.async { self.onStartedTalking() }
    }

    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) { finishedOne() }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didCancel u: AVSpeechUtterance) { finishedOne() }

    private func finishedOne() {
        DispatchQueue.main.async {
            self.pending = max(0, self.pending - 1)
            if self.pending == 0 && self.replyFinished { self.onFinishedTalking() }
        }
    }
}
