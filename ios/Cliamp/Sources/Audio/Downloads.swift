import CliampCore
import CryptoKit
import Foundation
import Network
import Observation
import os

/// Transient per-URL fetch state; anything permanent lives in `DownloadEntry`.
enum DownloadState: Equatable, Sendable {
    case active(fraction: Double, bytesRead: Int64, totalBytes: Int64)
    case failed(String)

    /// Negative while the server hides its length; the row then reads bytes.
    var indeterminate: Bool {
        if case .active(let fraction, _, _) = self { return fraction < 0 }
        return false
    }
}

/// `38 MB` / `410 KB`, for rows that name a file's weight.
func downloadSizeLabel(_ bytes: Int64) -> String {
    if bytes < 1024 * 1024 {
        return "\(max(1, bytes / 1024)) KB"
    }
    return String(format: "%.0f MB", Double(bytes) / 1_048_576)
}

/// Episode fetcher: plain HTTP bodies into Application Support, an atomic
/// rename at the end. Only finite http(s) tracks are fetchable - live radio
/// has no end to save.
@MainActor
@Observable
final class DownloadManager {
    private(set) var states: [String: DownloadState] = [:]
    private(set) var entries: [String: DownloadEntry] = [:]

    /// One in-flight fetch. The task's identifier travels with every callback
    /// so a late completion from a cancelled task cannot touch its successor.
    private struct ActiveDownload {
        let task: URLSessionDownloadTask
        let station: Station
        let auto: Bool
    }

    private let store: PodcastStore
    private let directory: URL
    private let allowsCellular: @Sendable () -> Bool
    private let session: URLSession
    private let coordinator = DownloadCoordinator()
    private let log = Logger(subsystem: "stream.cliamp.mobile", category: "downloads")
    private let monitor = NWPathMonitor()
    private var active: [String: ActiveDownload] = [:]
    /// Unknown until the first path update; treating that as expensive keeps
    /// a just-launched app from spending cellular before it knows better.
    private var pathIsExpensive = true

    /// Auto-download keeps this many latest episodes per subscribed show.
    private let autoKeep = 3

    init(store: PodcastStore, allowsCellular: @escaping @Sendable () -> Bool) {
        self.store = store
        self.allowsCellular = allowsCellular
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        directory = base.appendingPathComponent("CliampDownloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        session = URLSession(configuration: configuration, delegate: coordinator, delegateQueue: nil)
        sweep()
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in
                self?.pathIsExpensive = path.isExpensive
            }
        }
        monitor.start(queue: DispatchQueue(label: "stream.cliamp.mobile.downloads"))
        coordinator.onProgress = { [weak self] taskID, url, read, total in
            Task { @MainActor [weak self] in
                self?.updateProgress(taskID: taskID, url: url, read: read, total: total)
            }
        }
        coordinator.onFinish = { [weak self] taskID, url, file, failure in
            Task { @MainActor [weak self] in
                self?.finish(taskID: taskID, url: url, file: file, failure: failure)
            }
        }
    }

    /// Absolute path when `url` has a live file on disk, else nil.
    func localPath(url: String) -> String? {
        guard let entry = entries[url], FileManager.default.fileExists(atPath: entry.path) else {
            return nil
        }
        return entry.path
    }

    func isDownloaded(url: String) -> Bool {
        localPath(url: url) != nil
    }

    func state(for url: String) -> DownloadState? {
        states[url]
    }

    func entry(for url: String) -> DownloadEntry? {
        entries[url]
    }

    /// Queue a fetch; a no-op when already held or already running.
    func download(_ station: Station, auto: Bool = false) {
        let url = station.url
        guard !isDownloaded(url: url), active[url] == nil else { return }
        guard station.isTrack, url.hasPrefix("http://") || url.hasPrefix("https://"),
              let taskURL = URL(string: url)
        else {
            states[url] = .failed("not downloadable")
            return
        }
        guard allowsCellular() || !pathIsExpensive else {
            states[url] = .failed("wifi only")
            return
        }
        var request = URLRequest(url: taskURL)
        request.allowsCellularAccess = allowsCellular()
        let destination = fileURL(for: url)
        let task = session.downloadTask(with: request)
        coordinator.register(task: task, url: url, destination: destination)
        active[url] = ActiveDownload(task: task, station: station, auto: auto)
        states[url] = .active(fraction: 0, bytesRead: 0, totalBytes: -1)
        task.resume()
    }

    func cancel(url: String) {
        guard let running = active.removeValue(forKey: url) else { return }
        running.task.cancel()
        states[url] = nil
    }

    /// Forget a fetch: stops it, deletes the file, untracks the URL.
    func remove(url: String) {
        cancel(url: url)
        if let entry = entries[url] {
            try? FileManager.default.removeItem(atPath: entry.path)
        }
        entries.removeValue(forKey: url)
        store.removeDownload(url: url)
    }

    /// Auto-download for one subscribed show: the latest full, unplayed
    /// episodes fetch themselves and older auto fetches are swept. Manual
    /// downloads are never touched. Idempotent, so a feed refresh can re-fire.
    func autoDownload(show: PodcastShow, episodes: [PodcastEpisode], completedUrls: Set<String>) {
        guard UserDefaults.standard.object(forKey: "auto_download") as? Bool == true else { return }
        let fresh = episodes
            .filter { $0.isFull && !$0.audioUrl.isEmpty && !completedUrls.contains($0.audioUrl) }
            .prefix(autoKeep)
        for episode in fresh {
            let station = episode.station(show: show)
            if !isDownloaded(url: station.url), active[station.url] == nil {
                download(station, auto: true)
            }
        }
        let mine = entries.values
            .filter { $0.auto && $0.station.slug == show.id }
            .sorted { $0.downloadedAt > $1.downloadedAt }
        for stale in mine.dropFirst(autoKeep) {
            remove(url: stale.url)
        }
    }

    // MARK: completion

    private func updateProgress(taskID: Int, url: String, read: Int64, total: Int64) {
        // A stale callback from a replaced or cancelled task is dropped.
        guard active[url]?.task.taskIdentifier == taskID else { return }
        let fraction = total > 0 ? min(max(Double(read) / Double(total), 0), 1) : -1
        states[url] = .active(fraction: fraction, bytesRead: read, totalBytes: total)
    }

    private func finish(taskID: Int, url: String, file: URL?, failure: String?) {
        guard let running = active[url], running.task.taskIdentifier == taskID else { return }
        active.removeValue(forKey: url)
        guard let file, failure == nil else {
            if let failure {
                states[url] = .failed(failure)
                log.error("download failed: \(failure, privacy: .public)")
            }
            return
        }
        let entry = DownloadEntry(
            url: url,
            path: file.path,
            bytes: fileSize(file),
            station: running.station,
            downloadedAt: Int64(Date().timeIntervalSince1970 * 1000),
            auto: running.auto
        )
        entries[url] = entry
        store.addDownload(entry)
        states[url] = nil
    }

    // MARK: storage

    /// Startup cleanup: partial files go, entries whose file has vanished are
    /// forgotten, and the store is rewritten only when something changed.
    private func sweep() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "tmp" {
            try? FileManager.default.removeItem(at: file)
        }
        let stored = store.downloads()
        let kept = stored.filter { FileManager.default.fileExists(atPath: $0.value.path) }
        entries = kept
        if kept.count != stored.count {
            store.setDownloads(kept)
        }
    }

    private func fileURL(for url: String) -> URL {
        let raw = url.prefix { $0 != "?" }.split(separator: ".").last.map(String.init) ?? "mp3"
        let ext = (raw.count <= 4 && raw.allSatisfy(\.isLetter)) ? raw : "mp3"
        let digest = Insecure.SHA1.hash(data: Data(url.utf8))
            .map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(digest).\(ext)")
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
    }
}

/// URLSession delegate for the download tasks: it owns the mapping from task
/// to URL and moves each finished temp file into Application Support before
/// reporting completion. Kept out of `DownloadManager` because URLSession
/// holds the delegate for its lifetime.
private final class DownloadCoordinator: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private struct TaskInfo: Sendable {
        let url: String
        let destination: URL
    }

    private let lock = NSLock()
    private var infos: [Int: TaskInfo] = [:]
    /// The HTTP status once a file is safely in place, 0 when the move failed.
    private var outcomes: [Int: Int] = [:]

    nonisolated(unsafe) var onProgress: (@Sendable (Int, String, Int64, Int64) -> Void)?
    nonisolated(unsafe) var onFinish: (@Sendable (Int, String, URL?, String?) -> Void)?

    func register(task: URLSessionDownloadTask, url: String, destination: URL) {
        lock.withLock { infos[task.taskIdentifier] = TaskInfo(url: url, destination: destination) }
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let info = lock.withLock { infos[downloadTask.taskIdentifier] }
        guard let info else { return }
        onProgress?(downloadTask.taskIdentifier, info.url, totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        let info = lock.withLock { infos[downloadTask.taskIdentifier] }
        guard let info else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            lock.withLock { outcomes[downloadTask.taskIdentifier] = 0 }
            return
        }
        do {
            try? FileManager.default.removeItem(at: info.destination)
            try FileManager.default.moveItem(at: location, to: info.destination)
            lock.withLock { outcomes[downloadTask.taskIdentifier] = 1 }
        } catch {
            lock.withLock { outcomes[downloadTask.taskIdentifier] = 0 }
        }
    }

    func urlSession(
        _ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?
    ) {
        let info = lock.withLock { infos.removeValue(forKey: task.taskIdentifier) }
        let moved = lock.withLock { outcomes.removeValue(forKey: task.taskIdentifier) } == 1
        guard let info else { return }
        if let error {
            if (error as? URLError)?.code == .cancelled {
                onFinish?(task.taskIdentifier, info.url, nil, nil)
            } else {
                onFinish?(task.taskIdentifier, info.url, nil, error.localizedDescription.lowercased())
            }
            return
        }
        if moved {
            onFinish?(task.taskIdentifier, info.url, info.destination, nil)
        } else {
            let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
            onFinish?(task.taskIdentifier, info.url, nil, status > 0 ? "http \(status)" : "download failed")
        }
    }
}
