import CliampCore
import CryptoKit
import Foundation
import ImageIO
import Synchronization
import os
import Synchronization
import UIKit

/// Radio streams carry no cover art, so the next best thing is the station's
/// own branding: the og:image on its homepage, then its apple-touch-icon, then
/// the favicon the directory recorded. cliamp's own channels are deliberately
/// excluded - cliamp.stream's og:image is a 1200x630 marketing screenshot that
/// crops into an unreadable smear, and the generated plate is already square.
///
/// Decoded art lives in memory keyed by station id; the bytes live on disk for
/// a week so a relaunch does not re-fetch. Downloads are capped and decodes are
/// scaled through ImageIO, so a fast-scrolled list never holds full-size images.
/// A tiny counting semaphore for bounding concurrent artwork extraction.
/// Waiting is cancellation-aware, so a scrolled-away row stops queueing work.
actor AsyncSlots {
    private let limit: Int
    private var used = 0

    init(limit: Int) {
        self.limit = limit
    }

    func acquire() async throws {
        while true {
            if used < limit {
                used += 1
                return
            }
            try Task.checkCancellation()
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    func release() {
        used = max(0, used - 1)
    }
}

/// One in-flight extraction, shared by every caller that asks for the same
/// station while it runs. The last caller to leave cancels the work.
private final class Extraction: @unchecked Sendable {
    let id = UUID()
    var task: Task<Data?, Never>!
    private let lock = NSLock()
    private var waiters = 0

    func join() {
        lock.lock()
        waiters += 1
        lock.unlock()
    }

    /// True when no interested caller remains.
    func leave() -> Bool {
        lock.lock()
        waiters -= 1
        let last = waiters <= 0
        lock.unlock()
        return last
    }
}

final class StationArtwork: @unchecked Sendable {
    static let shared = StationArtwork()

    static let log = Logger(subsystem: "stream.cliamp.mobile", category: "artwork")

    private static let maxHTML = 64 * 1024
    private static let maxImage = 4 * 1024 * 1024
    private static let target: CGFloat = 512
    private static let targetSmall: CGFloat = 96
    private static let missRetry: TimeInterval = 60
    private static let diskTTL: TimeInterval = 7 * 24 * 60 * 60
    private static let userAgent = "cliamp-mobile/0.0.1 (+https://cliamp.stream)"

    /// Embedded tag artwork (ID3 APIC, MP4 covr, FLAC picture) for stations
    /// whose cover field is empty. Installed once at launch; accessed under a
    /// lock because artwork requests run from several tasks.
    private let embeddedProvider = Mutex<(@Sendable (Station) async throws -> Data?)?>(nil)

    func installEmbeddedProvider(_ provider: @escaping @Sendable (Station) async throws -> Data?) {
        embeddedProvider.withLock { $0 = provider }
    }

    private var embeddedArtwork: (@Sendable (Station) async throws -> Data?)? {
        embeddedProvider.withLock { $0 }
    }

    /// One extraction per station, shared by every row/player that asks while
    /// it runs, and a small concurrency bound so a fast scroll cannot open a
    /// burst of SFTP reads. Completed entries are dropped: the image caches
    /// hold the decoded picture, and the raw bytes must not pile up.
    private let extractions = Mutex<[String: Extraction]>([:])
    private let extractionSlots = AsyncSlots(limit: 3)

    private enum ExtractionOutcome {
        case flight(Extraction)
        case cached(Data)
    }

    private func embeddedData(for station: Station) async -> Data? {
        guard let provider = embeddedArtwork else { return nil }
        // The cache check and the entry claim share one lock: a caller cannot
        // slip between another extraction's publish and its entry removal.
        // Lookup, join and creation all happen under one lock: a caller can
        // never pick up an entry that the last leaver is about to cancel.
        let outcome = extractions.withLock { tasks -> ExtractionOutcome in
            if let existing = tasks[station.id] {
                existing.join()
                return .flight(existing)
            }
            if let cached = validExtractedData(station.id) {
                return .cached(cached)
            }
            let created = Extraction()
            created.task = Task { () -> Data? in
                do {
                    try await extractionSlots.acquire()
                } catch {
                    return nil
                }
                defer { Task { await extractionSlots.release() } }
                guard !Task.isCancelled else { return nil }
                do {
                    return try await provider(station)
                } catch {
                    return nil
                }
            }
            created.join()
            tasks[station.id] = created
            return .flight(created)
        }

        switch outcome {
        case .cached(let data):
            return data
        case .flight(let entry):
            // One waiter-removal exactly, whether this caller finishes or its
            // task is cancelled; removing the last waiter stops the work.
            let once = Once()
            let data = await withTaskCancellationHandler {
                await entry.task.value
            } onCancel: {
                if once.claim() {
                    self.leave(entry, for: station.id)
                }
            }
            // Publish the reusable result before removing the entry: a caller
            // that arrives in between must find the cache, not start over.
            if let data {
                try? data.write(to: fileURL(station.id), options: .atomic)
                if let small = Self.scaledImage(data: data, target: Self.targetSmall) {
                    smallImages.setObject(small, forKey: station.id as NSString)
                }
                clearMiss(station.id)
            }
            if once.claim() {
                leave(entry, for: station.id)
            }
            return data
        }
    }

    /// Exactly-once gate for the two paths that may remove a waiter.
    private final class Once: @unchecked Sendable {
        private let lock = NSLock()
        private var claimed = false

        func claim() -> Bool {
            lock.withLock {
                guard !claimed else { return false }
                claimed = true
                return true
            }
        }
    }

    /// Drops one waiter; when the last one leaves, the entry is removed under
    /// the same lock so a new caller cannot join a cancelled task.
    private func leave(_ entry: Extraction, for key: String) {
        let cancel = extractions.withLock { tasks -> Bool in
            guard entry.leave() else { return false }
            if tasks[key]?.id == entry.id { tasks[key] = nil }
            return true
        }
        if cancel {
            entry.task.cancel()
        }
    }

    /// Cached extracted bytes, held to the same freshness and decode rules as
    /// the HTTP cache: an expired or undecodable file is fetched again.
    private func validExtractedData(_ key: String) -> Data? {
        let url = fileURL(key)
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attributes[.modificationDate] as? Date,
              Date().timeIntervalSince(modified) < Self.diskTTL,
              let data = try? Data(contentsOf: url),
              !data.isEmpty,
              Self.scaledImage(data: data, target: Self.targetSmall) != nil
        else { return nil }
        return data
    }

    private let images = NSCache<NSString, UIImage>()
    private let smallImages = NSCache<NSString, UIImage>()
    private let misses = Mutex<[String: Date]>([:])

    private func clearMiss(_ key: String) {
        misses.withLock { _ = $0.removeValue(forKey: key) }
    }
    private let resolvedURLs = Mutex<[String: String]>([:])
    private let cacheDirectory: URL

    private init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        cacheDirectory = caches.appendingPathComponent("covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        images.countLimit = 128
        smallImages.countLimit = 384
    }

    /// Synchronous memory peek, so a scrolling list paints cached covers in the
    /// same frame instead of flashing a placeholder.
    func cachedSmall(for station: Station) -> UIImage? {
        smallImages.object(forKey: station.id as NSString)
    }

    func cached(for station: Station) -> UIImage? {
        images.object(forKey: station.id as NSString)
    }

    func smallImage(for station: Station) async -> UIImage? {
        guard station.source != .cliamp else { return nil }
        if let cached = smallImages.object(forKey: station.id as NSString) { return cached }
        if isOut(station.id) { return nil }
        var image = diskImage(key: station.id, target: Self.targetSmall)
        if image == nil {
            image = await cover(for: station, target: Self.targetSmall)
        }
        if Task.isCancelled { return nil }
        if let image {
            smallImages.setObject(image, forKey: station.id as NSString)
        } else {
            // Every source backs off after a miss, so scrolling an
            // artwork-free list does not reparse it on every pass.
            noteMiss(station.id)
        }
        return image
    }

    func image(for station: Station) async -> UIImage? {
        guard station.source != .cliamp else { return nil }
        if let cached = images.object(forKey: station.id as NSString) { return cached }
        if isOut(station.id) { return nil }
        var image = diskImage(key: station.id, target: Self.target)
        if image == nil {
            image = await cover(for: station, target: Self.target)
        }
        // Cancellation is not a failed cover: it must not start a backoff.
        if Task.isCancelled { return nil }
        if let image {
            images.setObject(image, forKey: station.id as NSString)
        } else {
            noteMiss(station.id)
        }
        return image
    }

    /// The station's cover: a known cover URL first (provider/podcast art),
    /// then the scraped og:image, then the favicon the directory recorded. A
    /// discovered URL that refuses to decode is forgotten so the fallback
    /// happens on the spot instead of pinning a dead link.
    private func cover(for station: Station, target: CGFloat) async -> UIImage? {
        if station.cover.hasPrefix("http") {
            return await download(station.cover, saveAs: station.id, target: target)
        }
        // No sidecar and no URL: the art is inside the file's own tags.
        if station.cover.isEmpty, embeddedArtwork != nil {
            let data = await embeddedData(for: station)
            if let data {
                // The disk write and thumbnail cache happen inside
                // `embeddedData`; this only decodes for the requested size.
                if let image = Self.scaledImage(data: data, target: target) {
                    clearMiss(station.id)
                    return image
                }
                Self.log.error(
                    "embedded art decode failed bytes=\(data.count, privacy: .public) id=\(station.id, privacy: .public)"
                )
            }
        }
        // Companion folder art next to an indexed local file (DEC-02).
        if station.cover.hasPrefix("file:"),
           let url = URL(string: station.cover), url.isFileURL {
            return Self.decodeFile(url, target: target)
        }
        guard let url = await imageURL(for: station) else { return nil }
        if let image = await download(url, saveAs: station.id, target: target) {
            return image
        }
        forgetURL(station.id)
        let favicon = station.favicon
        guard favicon.hasPrefix("http"), favicon != url else { return nil }
        return await download(favicon, saveAs: station.id, target: target)
    }

    /// Downscales a local image through ImageIO so a folder cover never
    /// decodes at full size on the main thread.
    private static func decodeFile(_ url: URL, target: CGFloat) -> UIImage? {
        guard FileManager.default.fileExists(atPath: url.path),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil)
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: max(target, target * 2),
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else { return nil }
        return UIImage(cgImage: image)
    }

    private func imageURL(for station: Station) async -> String? {
        if let cached = resolvedURLs.withLock({ $0[station.id] }) {
            return cached
        }
        var candidate: String?
        if station.homepage.hasPrefix("http") {
            candidate = await scrape(station.homepage)
        }
        if candidate == nil, station.favicon.hasPrefix("http") {
            candidate = station.favicon
        }
        if let candidate {
            resolvedURLs.withLock { $0[station.id] = candidate }
        }
        return candidate
    }

    private func forgetURL(_ key: String) {
        _ = resolvedURLs.withLock { $0.removeValue(forKey: key) }
    }

    private func isOut(_ key: String) -> Bool {
        misses.withLock { missed in
            guard let missedAt = missed[key] else { return false }
            return Date().timeIntervalSince(missedAt) < Self.missRetry
        }
    }

    private func noteMiss(_ key: String) {
        misses.withLock { $0[key] = Date() }
    }

    // MARK: network

    // Deliberately not a full HTML parse: read the head, pull the first usable
    // meta tag, stop.
    private static let ogTag = try? NSRegularExpression(
        pattern: "<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image(?::secure_url)?|twitter:image(?::src)?)[\"'][^>]*>",
        options: [.caseInsensitive]
    )
    private static let appleTag = try? NSRegularExpression(
        pattern: "<link[^>]+rel\\s*=\\s*[\"'][^\"']*apple-touch-icon[^\"']*[\"'][^>]*>",
        options: [.caseInsensitive]
    )
    private static let contentAttribute = try? NSRegularExpression(
        pattern: "content\\s*=\\s*[\"']([^\"']+)[\"']", options: [.caseInsensitive]
    )
    private static let hrefAttribute = try? NSRegularExpression(
        pattern: "href\\s*=\\s*[\"']([^\"']+)[\"']", options: [.caseInsensitive]
    )

    private func scrape(_ homepage: String) async -> String? {
        guard let url = URL(string: homepage) else { return nil }
        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 10
        guard let result = await BoundedLoader(limit: Self.maxHTML).load(request),
              (result.response.value(forHTTPHeaderField: "Content-Type") ?? "")
              .localizedCaseInsensitiveContains("html")
        else { return nil }
        let head = String(decoding: result.data, as: UTF8.self)
        let raw = firstCapture(Self.ogTag, in: head, attribute: Self.contentAttribute)
            ?? firstCapture(Self.appleTag, in: head, attribute: Self.hrefAttribute)
        guard let raw else { return nil }
        return absolute(base: homepage, reference: raw)
    }

    private func firstCapture(
        _ pattern: NSRegularExpression?,
        in text: String,
        attribute: NSRegularExpression?
    ) -> String? {
        guard let pattern, let attribute else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern.firstMatch(in: text, range: range),
              let matchRange = Range(match.range, in: text)
        else { return nil }
        let tag = String(text[matchRange])
        let tagRange = NSRange(tag.startIndex..., in: tag)
        guard let attributeMatch = attribute.firstMatch(in: tag, range: tagRange),
              attributeMatch.numberOfRanges > 1,
              let captureRange = Range(attributeMatch.range(at: 1), in: tag)
        else { return nil }
        return String(tag[captureRange])
    }

    private func absolute(base: String, reference: String) -> String? {
        guard let baseURL = URL(string: base) else { return nil }
        let trimmed = reference.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed, relativeTo: baseURL)?.absoluteURL,
              let scheme = url.scheme, scheme.hasPrefix("http")
        else { return nil }
        return url.absoluteString
    }

    private func download(_ urlString: String, saveAs key: String?, target: CGFloat) async -> UIImage? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 15
        // Bounded read: the loader stops at the cap rather than buffering the
        // whole response first.
        guard let result = await BoundedLoader(limit: Self.maxImage).load(request),
              (200..<300).contains(result.response.statusCode),
              result.data.count >= 64
        else { return nil }
        let contentType = (result.response.value(forHTTPHeaderField: "Content-Type") ?? "")
            .split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
        if !contentType.isEmpty, !contentType.hasPrefix("image/"), contentType != "image/x-icon",
           contentType != "image/vnd.microsoft.icon"
        {
            return nil
        }
        if contentType == "image/svg+xml" { return nil }
        guard let image = Self.scaledImage(data: result.data, target: target) else { return nil }
        if let key {
            // Atomic: a reader can never observe a partial write, and two
            // writers racing for the same key leave one complete file.
            try? result.data.write(to: fileURL(key), options: .atomic)
        }
        return image
    }

    // MARK: disk

    private func fileURL(_ key: String) -> URL {
        let name = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return cacheDirectory.appendingPathComponent("\(name).img")
    }

    private func diskImage(key: String, target: CGFloat) -> UIImage? {
        let url = fileURL(key)
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attributes[.modificationDate] as? Date,
              Date().timeIntervalSince(modified) < Self.diskTTL
        else { return nil }
        return Self.scaledImage(url: url, target: target)
    }

    private static func scaledImage(data: Data, target: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return thumbnail(source: source, target: target)
    }

    private static func scaledImage(url: URL, target: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return thumbnail(source: source, target: target)
    }

    private static func thumbnail(source: CGImageSource, target: CGFloat) -> UIImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: target,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}

/// A one-shot bounded fetch: reads through a delegate and stops at the cap,
/// instead of buffering whatever the server sends and checking afterwards.
private final class BoundedLoader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let limit: Int
    private let lock = NSLock()
    private var data = Data()
    private var response: HTTPURLResponse?
    private var continuation: CheckedContinuation<Data?, Never>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var finished = false

    init(limit: Int) {
        self.limit = limit
    }

    func load(_ request: URLRequest) async -> (data: Data, response: HTTPURLResponse)? {
        let data = await withCheckedContinuation { (continuation: CheckedContinuation<Data?, Never>) in
            let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
            let task = session.dataTask(with: request)
            lock.withLock {
                self.continuation = continuation
                self.session = session
                self.task = task
            }
            task.resume()
        }
        let http: HTTPURLResponse? = lock.withLock { self.response }
        guard let data, let http else { return nil }
        return (data, http)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.withLock {
            self.response = response as? HTTPURLResponse
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let overflow = lock.withLock {
            let overflow = data.count > limit - self.data.count
            if !overflow {
                self.data.append(data)
            }
            return overflow
        }
        if overflow {
            dataTask.cancel()
            finish(nil)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let data = lock.withLock { error == nil ? self.data : nil }
        finish(data)
    }

    private func finish(_ data: Data?) {
        let (continuation, session): (CheckedContinuation<Data?, Never>?, URLSession?) = lock.withLock {
            guard !finished else { return (nil, nil) }
            finished = true
            let continuation = self.continuation
            self.continuation = nil
            let session = self.session
            self.session = nil
            return (continuation, session)
        }
        session?.invalidateAndCancel()
        continuation?.resume(returning: data)
    }
}
