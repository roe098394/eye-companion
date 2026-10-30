import AVFoundation
import Speech

/// On-device speech recognition from the iPhone's OWN microphone.
/// Utterances end after a short pause, then the text is handed to onHeard.
final class Ears {
    var onPartial: (String) -> Void = { _ in }
    var onHeard: (String) -> Void = { _ in }
    var onLevel: (Double) -> Void = { _ in }

    var paused = true {
        didSet { DispatchQueue.main.async { self.paused ? self.cancelUtterance() : self.beginUtterance() } }
    }

    private let engine = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-GB")) ?? SFSpeechRecognizer()!
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var lastText = ""
    private var delivered = false

    static func requestPermissions(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            AVAudioApplication.requestRecordPermission { mic in
                DispatchQueue.main.async { done(status == .authorized && mic) }
            }
        }
    }

    func start() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            guard let self else { return }
            self.request?.append(buffer)
            if let ch = buffer.floatChannelData?[0] {
                let n = Int(buffer.frameLength)
                var sum: Float = 0
                for i in 0..<n { sum += ch[i] * ch[i] }
                let rms = n > 0 ? sqrt(sum / Float(n)) : 0
                self.onLevel(self.paused ? 0 : min(1, Double(rms) * 12))
            }
        }
        engine.prepare()
        try engine.start()
    }

    func stopAll() {
        cancelUtterance()
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }

    private func beginUtterance() {
        guard !paused, task == nil else { return }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.addsPunctuation = true
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        request = req
        lastText = ""
        delivered = false
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async { self?.handle(result: result, error: error) }
        }
    }

    private func handle(result: SFSpeechRecognitionResult?, error: Error?) {
        if let result {
            let text = result.bestTranscription.formattedString
            if text != lastText {
                lastText = text
                onPartial(text)
                restartSilenceTimer()
            }
            if result.isFinal { finish() }
        } else if error != nil {
            finish()
        }
    }

    private func restartSilenceTimer() {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: 1.3, repeats: false) { [weak self] _ in
            self?.request?.endAudio()   // triggers a final result
        }
    }

    private func finish() {
        guard task != nil else { return }
        silenceTimer?.invalidate()
        let text = lastText.trimmingCharacters(in: .whitespacesAndNewlines)
        task?.cancel(); task = nil; request = nil
        if !delivered, Ears.isRealSpeech(text) {
            delivered = true
            onHeard(text)
        }
        // keep listening unless the companion paused us in onHeard
        if !paused {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { self.beginUtterance() }
        }
    }

    private func cancelUtterance() {
        silenceTimer?.invalidate()
        task?.cancel(); task = nil
        request?.endAudio(); request = nil
        lastText = ""
    }

    private static let noise: Set<String> = ["the", "a", "huh", "uh", "um", "hmm", "oh", "ah", "and", "but", "i", "it", "yeah"]

    static func isRealSpeech(_ text: String) -> Bool {
        let words = text.lowercased().trimmingCharacters(in: .punctuationCharacters).split(separator: " ")
        if words.isEmpty { return false }
        return !(words.count == 1 && noise.contains(String(words[0])))
    }
}
