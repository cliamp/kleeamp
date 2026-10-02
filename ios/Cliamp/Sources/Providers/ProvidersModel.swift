import CliampCore
import Foundation
import Synchronization

/// One account's index state, the way Android's `IndexState` reads.
struct SftpScanStatus: Sendable, Equatable {
    var scanning = false
    var text = ""

    static let idle = SftpScanStatus()
}

/// The configured provider accounts, their indexed libraries, and the scans
/// that fill them. One process-wide owner, like the podcast services, so
/// view rebuilds cannot duplicate sessions or scans.
@MainActor
@Observable
final class ProvidersModel {
    private let store: ProviderAccountStore
    private let index = SftpIndexStore.appStore()
    private let pool = SftpSessionPool()

    private(set) var accounts: [ProviderAccount] = []
    /// Bumped whenever an index commit lands, so views re-read the store.
    private(set) var indexVersion = 0
    private(set) var statuses: [String: SftpScanStatus] = [:]
    private var scanning: Set<String> = []

    init(vault: SecretVault = KeychainVault()) {
        store = ProviderAccountStore.appStore(vault: vault)
        accounts = store.all()
    }

    // MARK: accounts

    func spec(for account: ProviderAccount) -> ProviderSpec? {
        ProviderCatalog.byKey(account.providerKey)
    }

    func resolvedValues(for account: ProviderAccount) -> [String: String] {
        guard let spec = spec(for: account) else { return account.values }
        return store.resolvedValues(for: account, spec: spec)
    }

    /// The SSH view of an account with its secrets merged in. The stored
    /// `account.values` never hold the password or key.
    func sshSettings(for account: ProviderAccount) -> SshConfig? {
        guard account.providerKey == "ssh" else { return nil }
        return sshConfig(resolvedValues(for: account))
    }

    func account(id: String) -> ProviderAccount? {
        accounts.first { $0.id == id }
    }

    /// Runs a spec's probe with the form's values. Nothing is saved here.
    func probe(spec: ProviderSpec, values: [String: String]) async -> Result<ProviderIdentity, Error> {
        if let message = spec.extraValidate?(values) {
            return .failure(SshError.validation(message))
        }
        do {
            return .success(try await spec.validate(values))
        } catch {
            return .failure(error)
        }
    }

    @discardableResult
    func saveAccount(
        id: String?,
        providerKey: String,
        label: String,
        values: [String: String]
    ) -> ProviderAccount? {
        guard let spec = ProviderCatalog.byKey(providerKey) else { return nil }
        let account = store.save(
            id: id, providerKey: providerKey, label: label, values: values, spec: spec
        )
        accounts = store.all()
        // An edited account's old session may point at different credentials
        // or a different host.
        if let id {
            Task { await pool.close(accountId: id) }
            statuses[id] = .idle
        }
        return account
    }

    func remove(accountId: String) {
        store.remove(id: accountId)
        accounts = store.all()
        index.forget(accountId: accountId)
        statuses[accountId] = nil
        indexVersion += 1
        Task { await pool.close(accountId: accountId) }
    }

    // MARK: scanning

    func status(accountId: String) -> SftpScanStatus {
        statuses[accountId] ?? .idle
    }

    /// Starts a scan when the account has never been indexed or its folders
    /// changed, and returns without waiting: the browse screen renders what is
    /// already indexed and reloads when the scan lands.
    func ensureIndexed(_ account: ProviderAccount) {
        guard let config = sshSettings(for: account), !config.folders.isEmpty else { return }
        guard index.isStale(accountId: account.id, folders: config.folders) else { return }
        startScan(account)
    }

    /// An explicit rescan, awaited so the caller can report what happened.
    @discardableResult
    func rescan(_ account: ProviderAccount) async -> Result<Int, Error> {
        await runScan(account)
    }

    private func startScan(_ account: ProviderAccount) {
        // The claim lives in `runScan`; taking it here too made the scan skip
        // itself as "already running".
        Task { [weak self] in
            _ = await self?.runScan(account)
        }
    }

    private func runScan(_ account: ProviderAccount) async -> Result<Int, Error> {
        guard let config = sshSettings(for: account) else {
            return .failure(SshError.missing("not an ssh account"))
        }
        guard !config.folders.isEmpty else {
            return .failure(SshError.noMusicFolder)
        }
        if scanning.contains(account.id) {
            // A scan is already walking; its result will land on its own.
            return .success(index.tracks(accountId: account.id).count)
        }
        scanning.insert(account.id)
        defer { scanning.remove(account.id) }
        statuses[account.id] = SftpScanStatus(scanning: true, text: "scanning…")

        let session = await pool.session(accountId: account.id, config: config)
        let box = TrackCollector()
        let throttle = Throttle()
        let scan = SftpScan(
            folders: config.folders,
            onBatch: { tracks in box.append(accountId: account.id, tracks) },
            onProgress: { found, where_ in
                // Throttled: a walk visits directories far faster than a
                // screen can usefully repaint.
                guard throttle.ready() else { return }
                let leaf = (where_ as NSString).lastPathComponent
                Task { @MainActor [weak self] in
                    self?.statuses[account.id] = SftpScanStatus(
                        scanning: true, text: "scanning · \(found) tracks · \(leaf)"
                    )
                }
            }
        )
        do {
            let count = try await scan.run(session)
            // Cancellation or a newer scan must not overwrite the index with
            // a partial walk.
            try Task.checkCancellation()
            index.commit(
                accountId: account.id,
                folders: config.folders,
                scannedAt: SharedClock.nowMs(),
                tracks: box.tracks
            )
            statuses[account.id] = SftpScanStatus(scanning: false, text: "\(count) tracks indexed")
            indexVersion += 1
            return .success(count)
        } catch is CancellationError {
            statuses[account.id] = SftpScanStatus(scanning: false, text: "scan cancelled")
            return .failure(CancellationError())
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            statuses[account.id] = SftpScanStatus(scanning: false, text: message)
            return .failure(error)
        }
    }

    // MARK: browsing

    func tracks(accountId: String) -> [SftpTrack] {
        index.tracks(accountId: accountId)
    }

    func albums(accountId: String, style: String) -> [SftpAlbum] {
        index.albums(accountId: accountId, style: style)
    }

    func artists(accountId: String) -> [SftpArtist] {
        index.artists(accountId: accountId)
    }

    func artistAlbums(accountId: String, artistKey: String) -> [SftpAlbum] {
        index.artistAlbums(accountId: accountId, artistKey: artistKey)
    }

    func albumTracks(accountId: String, albumKey: String) -> [SftpTrack] {
        index.albumTracks(accountId: accountId, albumKey: albumKey)
    }

    func hasIndex(accountId: String) -> Bool {
        index.index(accountId: accountId) != nil
    }

    // MARK: playback

    /// The session a resource loader reads through, built from the account's
    /// saved values at the moment it is needed.
    func session(for account: ProviderAccount) async -> SshSession? {
        guard let config = sshSettings(for: account) else { return nil }
        return await pool.session(accountId: account.id, config: config)
    }

    func session(forAccountId id: String) async -> SshSession? {
        guard let account = account(id: id) else { return nil }
        return await session(for: account)
    }
}

/// One process-wide owner for the provider accounts, index and sessions, so
/// the player and the library panes share them.
@MainActor
final class ProviderServices {
    static let shared = ProviderServices()

    let model: ProvidersModel

    private init() {
        model = ProvidersModel()
    }
}

/// One `SshSession` per account, kept open the way Android's `SshPool` leases
/// connections, so playback and a scan share a transport.
actor SftpSessionPool {
    private var sessions: [String: SshSession] = [:]

    func session(accountId: String, config: SshConfig) -> SshSession {
        if let existing = sessions[accountId] { return existing }
        let session = SshSession(config: config)
        sessions[accountId] = session
        return session
    }

    func close(accountId: String) async {
        guard let session = sessions.removeValue(forKey: accountId) else { return }
        await session.close()
    }
}

/// Thread-safe accumulator for scan batches.
private final class TrackCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [SftpTrack] = []

    func append(accountId: String, _ tracks: [ScannedTrack]) {
        lock.withLock {
            stored += tracks.map { SftpTrack(accountId: accountId, scanned: $0) }
        }
    }

    var tracks: [SftpTrack] {
        lock.withLock { stored }
    }
}

/// At most one progress report per 400 ms, read from any context.
private final class Throttle: @unchecked Sendable {
    private let mutex = Mutex<Int64>(0)
    private let intervalMs: Int64 = 400

    func ready() -> Bool {
        let now = SharedClock.nowMs()
        return mutex.withLock { last in
            guard now - last > intervalMs else { return false }
            last = now
            return true
        }
    }
}

enum SharedClock {
    static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
