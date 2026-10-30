import Foundation

/// Downloads a large file with progress, retrying a few times on failure.
enum Downloader {
    static func fetch(_ url: URL, to dest: URL, progress: @escaping (Double) -> Void) async throws {
        var lastError: Error?
        for attempt in 1...5 {
            do {
                try await once(url, to: dest, progress: progress)
                return
            } catch {
                lastError = error
                try? await Task.sleep(nanoseconds: UInt64(3_000_000_000 * attempt))
            }
        }
        throw lastError ?? URLError(.unknown)
    }

    private static func once(_ url: URL, to dest: URL, progress: @escaping (Double) -> Void) async throws {
        var observation: NSKeyValueObservation?
        defer { observation?.invalidate() }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            let task = URLSession.shared.downloadTask(with: url) { tmp, response, error in
                if let error { c.resume(throwing: error); return }
                guard let tmp, let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    c.resume(throwing: URLError(.badServerResponse)); return
                }
                do {
                    let fm = FileManager.default
                    if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
                    try fm.moveItem(at: tmp, to: dest)   // must happen before this handler returns
                    c.resume()
                } catch { c.resume(throwing: error) }
            }
            var last = Date.distantPast
            observation = task.progress.observe(\.fractionCompleted) { p, _ in
                if Date().timeIntervalSince(last) > 0.25 { last = Date(); progress(p.fractionCompleted) }
            }
            task.resume()
        }
    }
}
