import Foundation
import llama

/// The offline language model (llama.cpp, running on the iPhone's GPU) plus a short rolling memory.
final class Brain {
    static let contextSize: UInt32 = 2048
    static let maxReplyTokens = 160
    static let maxTurns = 10

    private let queue = DispatchQueue(label: "brain")
    private var model: OpaquePointer?
    private var ctx: OpaquePointer?
    private var vocab: OpaquePointer?
    private var sampler: UnsafeMutablePointer<llama_sampler>?
    private var template = "chatml"
    private var cached: [llama_token] = []
    private var history: [(role: String, text: String)] = []
    private let systemPrompt: String
    private var stopRequested = false
    private static var backendReady = false

    init(systemPrompt: String) { self.systemPrompt = systemPrompt }

    func load(path: String, done: @escaping (Bool) -> Void) {
        queue.async {
            if !Brain.backendReady { llama_backend_init(); Brain.backendReady = true }
            var mp = llama_model_default_params()
            #if targetEnvironment(simulator)
            mp.n_gpu_layers = 0
            #else
            mp.n_gpu_layers = 99
            #endif
            guard let m = llama_model_load_from_file(path, mp) else { done(false); return }

            var cp = llama_context_default_params()
            cp.n_ctx = Brain.contextSize
            cp.n_batch = 256
            let threads = Int32(max(2, min(4, ProcessInfo.processInfo.activeProcessorCount - 2)))
            cp.n_threads = threads
            cp.n_threads_batch = threads
            guard let c = llama_init_from_model(m, cp) else { llama_model_free(m); done(false); return }

            self.model = m
            self.ctx = c
            self.vocab = llama_model_get_vocab(m)
            if let t = llama_model_chat_template(m, nil) { self.template = String(cString: t) }

            let s = llama_sampler_chain_init(llama_sampler_chain_default_params())
            llama_sampler_chain_add(s, llama_sampler_init_penalties(llama_vocab_n_tokens(self.vocab), 64, 1.1, 0, 0))
            llama_sampler_chain_add(s, llama_sampler_init_top_p(0.9, 1))
            llama_sampler_chain_add(s, llama_sampler_init_min_p(0.05, 1))
            llama_sampler_chain_add(s, llama_sampler_init_temp(0.7))
            llama_sampler_chain_add(s, llama_sampler_init_dist(UInt32.random(in: 0...UInt32.max)))
            self.sampler = s
            done(true)
        }
    }

    func stop() { stopRequested = true }

    func forget() { queue.async { self.history.removeAll() } }

    /// Streams pieces of the reply to onPiece, then calls done with the full reply.
    func ask(_ userText: String, onPiece: @escaping (String) -> Void, done: @escaping (String) -> Void) {
        queue.async {
            self.stopRequested = false
            self.history.append(("user", userText))
            self.trimHistory()
            let reply = self.generate(onPiece: onPiece)
            let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { self.history.removeLast() } else { self.history.append(("assistant", text)) }
            done(text)
        }
    }

    // MARK: - llama.cpp

    private func generate(onPiece: (String) -> Void) -> String {
        guard let ctx, let vocab, let sampler else { return "" }

        // 1. Format with the model's own chat template.
        let roles = ["system"] + history.map { $0.role }
        let texts = [systemPrompt] + history.map { $0.text }
        var cStrings: [UnsafeMutablePointer<CChar>] = []
        defer { cStrings.forEach { free($0) } }
        var msgs: [llama_chat_message] = []
        for (r, t) in zip(roles, texts) {
            let rp = strdup(r)!, tp = strdup(t)!
            cStrings += [rp, tp]
            msgs.append(llama_chat_message(role: UnsafePointer(rp), content: UnsafePointer(tp)))
        }
        var buf = [CChar](repeating: 0, count: 8192)
        var len = llama_chat_apply_template(template, msgs, msgs.count, true, &buf, Int32(buf.count))
        if len > Int32(buf.count) {
            buf = [CChar](repeating: 0, count: Int(len) + 1)
            len = llama_chat_apply_template(template, msgs, msgs.count, true, &buf, Int32(buf.count))
        }
        guard len > 0 else { return "" }
        let prompt = String(decoding: buf[0..<Int(len)].map { UInt8(bitPattern: $0) }, as: UTF8.self)

        // 2. Tokenize.
        let utf8Count = Int32(prompt.utf8.count)
        let n = -llama_tokenize(vocab, prompt, utf8Count, nil, 0, true, true)
        guard n > 0 else { return "" }
        var tokens = [llama_token](repeating: 0, count: Int(n))
        guard llama_tokenize(vocab, prompt, utf8Count, &tokens, n, true, true) >= 0 else { return "" }
        guard tokens.count + Brain.maxReplyTokens < Int(llama_n_ctx(ctx)) else { return "" }

        // 3. Reuse the matching part of the KV cache.
        var keep = 0
        while keep < cached.count && keep < tokens.count && cached[keep] == tokens[keep] { keep += 1 }
        if keep == tokens.count && keep > 0 { keep -= 1 }
        let mem = llama_get_memory(ctx)
        if !llama_memory_seq_rm(mem, 0, llama_pos(keep), -1) {
            llama_memory_clear(mem, true)
            keep = 0
        }
        guard decode(&tokens, from: keep) else {
            llama_memory_clear(mem, true); cached = []; return ""
        }
        cached = tokens

        // 4. Generate.
        var reply = ""
        var pending: [UInt8] = []
        var piece = [CChar](repeating: 0, count: 256)
        for _ in 0..<Brain.maxReplyTokens {
            if stopRequested { break }
            var tok = llama_sampler_sample(sampler, ctx, -1)
            if llama_vocab_is_eog(vocab, tok) { break }
            let pl = llama_token_to_piece(vocab, tok, &piece, Int32(piece.count), 0, false)
            if pl > 0 { pending += piece[0..<Int(pl)].map { UInt8(bitPattern: $0) } }
            let ok = Brain.completeUTF8Prefix(pending)
            if ok > 0 {
                let s = String(decoding: pending[0..<ok], as: UTF8.self)
                pending.removeFirst(ok)
                reply += s
                onPiece(s)
            }
            cached.append(tok)
            let rc = withUnsafeMutablePointer(to: &tok) { llama_decode(ctx, llama_batch_get_one($0, 1)) }
            if rc != 0 { break }
        }
        llama_sampler_reset(sampler)
        return reply
    }

    private func decode(_ tokens: inout [llama_token], from start: Int) -> Bool {
        guard let ctx else { return false }
        let batch = 256
        var i = start
        while i < tokens.count {
            let n = min(batch, tokens.count - i)
            let rc = tokens.withUnsafeMutableBufferPointer { p in
                llama_decode(ctx, llama_batch_get_one(p.baseAddress! + i, Int32(n)))
            }
            if rc != 0 { return false }
            i += n
        }
        return true
    }

    private static func completeUTF8Prefix(_ b: [UInt8]) -> Int {
        var pos = 0, good = 0
        while pos < b.count {
            let c = b[pos]
            let len = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1
            if pos + len > b.count { break }
            pos += len
            good = pos
        }
        return good
    }

    private func trimHistory() {
        while history.count > Brain.maxTurns { history.removeFirst() }
        while let f = history.first, f.role != "user" { history.removeFirst() }
        while history.count > 1 && history.reduce(0, { $0 + $1.text.count }) > Int(Brain.contextSize) * 2 {
            history.removeFirst()
            while let f = history.first, f.role != "user" { history.removeFirst() }
        }
    }
}
