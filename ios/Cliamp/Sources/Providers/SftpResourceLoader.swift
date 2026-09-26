import AVFoundation
import CliampCore
import Foundation
import UniformTypeIdentifiers

/// Serves `cliamp-sftp://` reads to AVPlayer over an SSH session, so a track
/// on a server plays as a stream with real seeking rather than a download.
///
/// AVPlayer asks for byte ranges; each one opens the remote file at that
/// offset, reads in bounded chunks, and answers. The content information
/// request is filled from `stat`, which is what lets the player show a
/// duration and seek without pulling the whole file.
final class SftpResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    /// Chunk size for each SFTP read; servers cap reads around 32 KiB, and
    /// AVPlayer's ranges are much larger, so this is what keeps a FLAC fed.
    private static let chunk = 128 * 1024

    private let accountId: String
    private let path: String
    private let sessions: @Sendable (String) async -> SshSession?
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(
        accountId: String,
        path: String,
        sessions: @escaping @Sendable (String) async -> SshSession?
    ) {
        self.accountId = accountId
        self.path = path
        self.sessions = sessions
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        let task = Task { [weak self] in
            _ = await self?.serve(loadingRequest)
        }
        lock.withLock { tasks[ObjectIdentifier(loadingRequest)] = task }
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let task = lock.withLock { tasks.removeValue(forKey: ObjectIdentifier(loadingRequest)) }
        task?.cancel()
    }

    private func finish(_ request: AVAssetResourceLoadingRequest) {
        lock.withLock { _ = tasks.removeValue(forKey: ObjectIdentifier(request)) }
        if !request.isFinished {
            request.finishLoading()
        }
    }

    private func serve(_ request: AVAssetResourceLoadingRequest) async {
        guard let session = await sessions(accountId) else {
            if !request.isFinished {
                request.finishLoading(with: SshError.missing("no ssh account \(accountId)"))
            }
            return
        }
        do {
            if let info = request.contentInformationRequest {
                guard let entry = try await session.stat(path), entry.kind == .file else {
                    if !request.isFinished {
                        request.finishLoading(with: SshError.missing(path))
                    }
                    return
                }
                info.contentLength = entry.size
                info.isByteRangeAccessSupported = true
                let ext = (path as NSString).pathExtension
                if let type = UTType(filenameExtension: ext), type.conforms(to: .audio) || type.conforms(to: .movie) {
                    info.contentType = type.identifier
                }
            }
            if let data = request.dataRequest {
                var offset = UInt64(data.requestedOffset)
                // `requestedLength` is Int; to-end-of-resource arrives as
                // Int.max, which simply keeps the loop going until EOF.
                var remaining = Int64(data.requestedLength)
                while remaining > 0 {
                    if request.isCancelled || Task.isCancelled { return }
                    let wanted = UInt32(min(Int64(Self.chunk), remaining))
                    let chunk = try await session.read(path, offset: offset, length: wanted)
                    if chunk.isEmpty { break }
                    data.respond(with: chunk)
                    offset += UInt64(chunk.count)
                    remaining -= Int64(chunk.count)
                }
            }
            finish(request)
        } catch {
            if !request.isFinished {
                request.finishLoading(with: error)
            }
            lock.withLock { _ = tasks.removeValue(forKey: ObjectIdentifier(request)) }
        }
    }
}
