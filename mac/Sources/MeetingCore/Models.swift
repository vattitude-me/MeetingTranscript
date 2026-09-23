import Foundation

/// The two files the Mac needs: Parakeet for words, Silero for where speech
/// is. Parakeet is byte-identical to the Android download. The small streaming
/// model Android uses for its live preview is not needed here — the Mac is
/// fast enough to run the accurate model live.
public enum Model: CaseIterable, Sendable {
    case parakeet
    case vad

    static let base = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    public var fileName: String {
        switch self {
        case .parakeet: return "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
        case .vad: return "silero_vad.onnx"
        }
    }

    public var url: URL { URL(string: Model.base + fileName)! }

    /// For progress before the server has said how big the file is.
    public var approxBytes: Int64 {
        switch self {
        case .parakeet: return 487_170_055
        case .vad: return 643_854
        }
    }

    var isArchive: Bool { fileName.hasSuffix(".tar.bz2") }

    /// Where the model ends up: the archive's own top-level folder, so an
    /// archive unpacked by hand with `tar xjf` lands in the same place.
    var installed: URL {
        isArchive
            ? Paths.models.appendingPathComponent(String(fileName.dropLast(".tar.bz2".count)), isDirectory: true)
            : Paths.models.appendingPathComponent(fileName)
    }

    var partFile: URL { Paths.models.appendingPathComponent(fileName + ".part") }
}

public enum ModelFiles {
    public struct Parakeet {
        public let encoder, decoder, joiner, tokens: URL
    }

    /// The bundle ships int8 and sometimes fp32 side by side; take int8, which
    /// is what the Android app runs and what the published accuracy is for.
    public static func parakeet() -> Parakeet? {
        let dir = Model.parakeet.installed
        guard let files = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)?
            .compactMap({ $0 as? URL }) else { return nil }
        func pick(_ part: String) -> URL? {
            let c = files.filter { $0.lastPathComponent.hasPrefix(part) && $0.pathExtension == "onnx" }
            return c.first { $0.lastPathComponent.contains("int8") } ?? c.first
        }
        guard let e = pick("encoder"), let d = pick("decoder"), let j = pick("joiner"),
              let t = files.first(where: { $0.lastPathComponent == "tokens.txt" }) else { return nil }
        return Parakeet(encoder: e, decoder: d, joiner: j, tokens: t)
    }

    public static func vad() -> URL? {
        let f = Model.vad.installed
        return FileManager.default.fileExists(atPath: f.path) ? f : nil
    }

    public static var ready: Bool { parakeet() != nil && vad() != nil }

    public static var missing: [Model] {
        var out: [Model] = []
        if parakeet() == nil { out.append(.parakeet) }
        if vad() == nil { out.append(.vad) }
        return out
    }
}

public struct DownloadProgress: Sendable {
    public var done: Int64
    public var total: Int64
    public var bytesPerSecond: Double
    public var phase: String

    public init(done: Int64, total: Int64, bytesPerSecond: Double, phase: String) {
        self.done = done
        self.total = total
        self.bytesPerSecond = bytesPerSecond
        self.phase = phase
    }

    public var fraction: Double { total > 0 ? min(1, Double(done) / Double(total)) : 0 }
}

/// Downloads into `<name>.part` and resumes with a Range request, so a dropped
/// connection costs the bytes in flight rather than the whole 490 MB.
public final class ModelDownloader: NSObject, URLSessionDataDelegate {
    private var handle: FileHandle?
    private var received: Int64 = 0
    private var expected: Int64 = 0
    private var offset: Int64 = 0
    private var lastTick = Date()
    private var lastBytes: Int64 = 0
    private var speed: Double = 0
    private var progress: ((Int64, Int64, Double) -> Void)?
    private var finished: ((Error?) -> Void)?
    private var session: URLSession?

    public static func downloadMissing(progress: @escaping (DownloadProgress) -> Void) async throws {
        Paths.ensure(Paths.models)
        let missing = ModelFiles.missing
        let grand = missing.reduce(Int64(0)) { $0 + $1.approxBytes }
        var base: Int64 = 0
        for model in missing {
            try await ModelDownloader().fetch(model) { done, total, speed in
                // Report against the sum of approximate sizes, so the bar
                // does not jump back to zero between files.
                progress(DownloadProgress(done: base + done, total: max(grand, base + total),
                                          bytesPerSecond: speed, phase: "Downloading"))
            }
            if model.isArchive {
                progress(DownloadProgress(done: base + model.approxBytes, total: grand,
                                          bytesPerSecond: 0, phase: "Unpacking"))
                try unpack(model)
            } else {
                try FileManager.default.moveItem(at: model.partFile, to: model.installed)
            }
            base += model.approxBytes
        }
    }

    private static func unpack(_ model: Model) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
        p.arguments = ["xjf", model.partFile.path, "-C", Paths.models.path]
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0, ModelFiles.parakeet() != nil else {
            throw SherpaError(message: "The download finished but could not be unpacked. Try again.")
        }
        // The archive has done its job; the unpacked model is what is used.
        try? FileManager.default.removeItem(at: model.partFile)
    }

    private func fetch(_ model: Model, progress: @escaping (Int64, Int64, Double) -> Void) async throws {
        let part = model.partFile
        if !FileManager.default.fileExists(atPath: part.path) {
            FileManager.default.createFile(atPath: part.path, contents: nil)
        }
        offset = (try? part.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        handle = try FileHandle(forWritingTo: part)
        try handle?.seekToEnd()
        self.progress = progress
        var request = URLRequest(url: model.url)
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        self.session = session
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            finished = { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            }
            session.dataTask(with: request).resume()
        }
        session.finishTasksAndInvalidate()
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                           didReceive response: URLResponse,
                           completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let http = response as? HTTPURLResponse
        switch http?.statusCode ?? 0 {
        case 206:
            expected = offset + max(0, response.expectedContentLength)
        case 200:
            // The server ignored the range: start the file over.
            try? handle?.truncate(atOffset: 0)
            offset = 0
            expected = max(0, response.expectedContentLength)
        case 416:
            // Asked for bytes past the end: the file is already complete.
            expected = offset
        default:
            completionHandler(.cancel)
            finish(SherpaError(message: "Download failed (HTTP \(http?.statusCode ?? 0))."))
            return
        }
        received = 0
        completionHandler(http?.statusCode == 416 ? .cancel : .allow)
        if http?.statusCode == 416 { finish(nil) }
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do { try handle?.write(contentsOf: data) } catch {
            dataTask.cancel()
            finish(error)
            return
        }
        received += Int64(data.count)
        let now = Date()
        if now.timeIntervalSince(lastTick) >= 0.5 {
            let inst = Double(received - lastBytes) / now.timeIntervalSince(lastTick)
            speed = speed == 0 ? inst : speed * 0.7 + inst * 0.3
            lastTick = now
            lastBytes = received
            progress?(offset + received, expected, speed)
        }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let e = error as? URLError, e.code == .cancelled { return }
        finish(error)
    }

    private func finish(_ error: Error?) {
        guard let f = finished else { return }
        finished = nil
        try? handle?.synchronize()
        try? handle?.close()
        handle = nil
        if error == nil { progress?(expected, expected, speed) }
        f(error)
    }
}
