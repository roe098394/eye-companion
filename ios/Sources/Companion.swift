import AVFoundation
import SwiftUI

/// Wires eyes, ears, brain and voice together.
@MainActor
final class Companion: ObservableObject {
    enum Phase { case setup, asleep, listening, thinking, talking }

    @Published var phase: Phase = .setup
    @Published var caption = ""
    @Published var subCaption = ""
    @Published var showCaptions = UserDefaults.standard.object(forKey: "captions") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showCaptions, forKey: "captions") }
    }

    let animator = EyeAnimator()
    private let brain = Brain(systemPrompt: Companion.systemPrompt)
    private let ears = Ears()
    private let voice = Voice()
    private var setupRunning = false
    private var ready = false

    static let modelURL = URL(string:
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf")!

    static let systemPrompt = """
        You are Pip, a small, cheerful desktop companion who lives in a phone on someone's desk. \
        You only exist as a pair of animated eyes and a voice. Everything you say is spoken aloud, \
        so reply in one or two short, natural sentences. Never use lists, markdown, emoji or stage \
        directions. Be warm, curious and a little playful. If you don't know something, say so \
        briefly. You have no internet access.
        """

    // MARK: setup

    func start(force: Bool = false) {
        if setupRunning || (ready && !force) { return }
        setupRunning = true
        setPhase(.setup)
        Task {
            defer { setupRunning = false }
            let allowed = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
                Ears.requestPermissions { c.resume(returning: $0) }
            }
            guard allowed else {
                caption = "I need microphone and speech permission. Turn them on in Settings → Eye Companion."
                return
            }
            do {
                let path = try await modelPath()
                caption = "Waking up…"
                let ok = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
                    brain.load(path: path) { c.resume(returning: $0) }
                }
                guard ok else { caption = "Couldn't load the brain file. Long-press → Retry setup."; return }
                try configureAudio()
                wireUp()
                try ears.start()
                ready = true
                caption = "Hi! I'm listening."
                setPhase(UserDefaults.standard.bool(forKey: "asleep") ? .asleep : .listening)
            } catch {
                caption = "Setup failed: \(error.localizedDescription). Check Wi-Fi, then long-press → Retry setup."
            }
        }
    }

    /// A .gguf you put in the app's Files folder wins; otherwise download the default model once.
    private func modelPath() async throws -> String {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if let own = try? fm.contentsOfDirectory(at: docs, includingPropertiesForKeys: nil)
            .first(where: { $0.pathExtension == "gguf" }) { return own.path }

        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try fm.createDirectory(at: support, withIntermediateDirectories: true)
        var dest = support.appendingPathComponent("brain.gguf")
        if fm.fileExists(atPath: dest.path) { return dest.path }

        caption = "Downloading my brain (about 800 MB, one time only)…"
        try await Downloader.fetch(Companion.modelURL, to: dest) { [weak self] p in
            Task { @MainActor in self?.caption = "Downloading my brain… \(Int(p * 100))%" }
        }
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? dest.setResourceValues(rv)
        return dest.path
    }

    /// Play through Bluetooth in full (A2DP) quality while recording from the phone's own mic.
    private func configureAudio() throws {
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothA2DP, .defaultToSpeaker])
        try s.setActive(true)
        if let mic = s.availableInputs?.first(where: { $0.portType == .builtInMic }) {
            try s.setPreferredInput(mic)
        }
    }

    private func wireUp() {
        ears.onPartial = { [weak self] text in
            Task { @MainActor in
                guard let self, self.phase == .listening else { return }
                self.animator.mood = text.isEmpty ? .idle : .listen
                if !text.isEmpty { self.caption = text }
            }
        }
        ears.onHeard = { [weak self] text in Task { @MainActor in self?.heard(text) } }
        let animator = self.animator
        ears.onLevel = { l in animator.level = l }
        voice.onStartedTalking = { [weak self] in
            guard let self else { return }
            if self.phase == .thinking { self.setPhase(.talking) }
        }
        voice.onFinishedTalking = { [weak self] in
            guard let self else { return }
            if self.phase == .talking || self.phase == .thinking { self.setPhase(.listening) }
        }
    }

    // MARK: conversation

    private func heard(_ text: String) {
        guard phase == .listening else { return }
        let lower = text.lowercased().trimmingCharacters(in: .punctuationCharacters)
        if ["go to sleep", "goodnight", "good night", "sleep pip"].contains(lower) { goToSleep(); return }
        if ["forget everything", "start over", "new conversation"].contains(lower) {
            brain.forget(); speakLine("Okay, fresh start."); return
        }

        setPhase(.thinking)
        subCaption = "You: \(text)"
        caption = ""
        voice.beginReply()
        brain.ask(text, onPiece: { [weak self] piece in
            Task { @MainActor in
                guard let self else { return }
                self.voice.feed(piece)
                self.caption += piece
            }
        }, done: { [weak self] _ in
            Task { @MainActor in self?.voice.endReply() }
        })
    }

    private func speakLine(_ text: String) {
        setPhase(.thinking)
        caption = text
        voice.beginReply(); voice.feed(text); voice.endReply()
    }

    private func setPhase(_ p: Phase) {
        phase = p
        ears.paused = (p != .listening)
        if p != .talking { animator.level = 0 }
        switch p {
        case .setup: animator.mood = .loading
        case .asleep: animator.mood = .sleep
        case .listening: animator.mood = .idle
        case .thinking: animator.mood = .think
        case .talking: animator.mood = .talk
        }
    }

    // MARK: touch & menu

    func tap() {
        switch phase {
        case .asleep: wakeUp()
        case .talking, .thinking: brain.stop(); voice.shutUp(); setPhase(.listening)
        case .listening: animator.mood = .happy
        case .setup: break
        }
    }

    func doubleTap() { if phase == .listening { goToSleep() } }

    func goToSleep() {
        guard ready else { return }
        brain.stop(); voice.shutUp()
        UserDefaults.standard.set(true, forKey: "asleep")
        caption = ""; subCaption = ""
        setPhase(.asleep)
    }

    func wakeUp() {
        guard ready else { return }
        UserDefaults.standard.set(false, forKey: "asleep")
        setPhase(.listening)
        animator.mood = .happy
    }

    func forget() { brain.forget() }
}
