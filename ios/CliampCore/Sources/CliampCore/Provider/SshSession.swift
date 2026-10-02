@preconcurrency import Citadel
import Crypto
import NIOPosix
@preconcurrency import NIOSSH
import Foundation
import NIOCore
import NIOSSH
import Synchronization

/// Failures a user has to read, phrased the way the wizard shows them.
public enum SshError: Error, LocalizedError, Sendable {
    case hostKeyMismatch(expected: String, actual: String)
    case keyUnreadable(String)
    case refusedCredentials
    case noSftpSubsystem
    case unreachable(String)
    case noMusicFolder
    case notAFolder(String)
    case missing(String)
    /// A directory the walk could not read (permissions, a stale mount):
    /// normal mid-scan, so the scanner skips it instead of failing.
    case directoryUnreadable(String)
    case validation(String)

    public var errorDescription: String? {
        switch self {
        case .hostKeyMismatch:
            "the host key does not match the one saved for this account"
        case .keyUnreadable(let detail):
            "could not read that private key: \(detail)"
        case .refusedCredentials:
            "the server refused those credentials"
        case .noSftpSubsystem:
            "connected, but the server does not offer sftp"
        case .unreachable(let detail):
            "could not reach the host: \(detail)"
        case .noMusicFolder:
            "connected, but found no music folder - give it a path"
        case .notAFolder(let folders):
            "not a folder on the server: \(folders)"
        case .missing(let path):
            "no such file: \(path)"
        case .directoryUnreadable(let message):
            message
        case .validation(let message):
            message
        }
    }
}

/// Trust-on-first-use, pinned afterwards: the first probe records what the
/// host offered and the wizard shows it; every later connection must present
/// the same key or it is refused.
private final class PinnedHostKey: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    private let expected: String
    private let learned = Mutex<String?>(nil)

    init(expected: String) {
        self.expected = expected
    }

    var learnedFingerprint: String? {
        learned.withLock { $0 }
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        var buffer = ByteBufferAllocator().buffer(capacity: 128)
        _ = hostKey.write(to: &buffer)
        let actual = fingerprint(ofPublicKeyBlob: Data(buffer.readableBytesView))
        if expected.isEmpty {
            learned.withLock { $0 = actual }
            validationCompletePromise.succeed(())
        } else if actual == expected {
            validationCompletePromise.succeed(())
        } else {
            validationCompletePromise.fail(SshError.hostKeyMismatch(expected: expected, actual: actual))
        }
    }
}

/// Offers the configured credential set, in order, exactly once each. SSH's
/// `none` method is a real offer (Tailscale's whole login), not a fallback.
private final class AccountAuthentication: NIOSSHClientUserAuthenticationDelegate, @unchecked Sendable {
    private let username: String
    private let offers: [NIOSSHUserAuthenticationOffer.Offer]
    private var index = 0

    init(username: String, offers: [NIOSSHUserAuthenticationOffer.Offer]) {
        self.username = username
        self.offers = offers
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        guard index < offers.count else {
            nextChallengePromise.succeed(nil)
            return
        }
        let offer = offers[index]
        index += 1
        nextChallengePromise.succeed(
            NIOSSHUserAuthenticationOffer(
                username: username, serviceName: "ssh-connection", offer: offer
            )
        )
    }
}

/// A connected transport plus the event loop group its channels run on.
///
/// Two SFTP channels ride one SSH connection: playback reads never get their
/// channel closed under them, while the listing channel is recycled to reclaim
/// the directory handles the SFTP client leaks (it never sends CLOSE for
/// OPENDIR). The group is owned here because Citadel's connect helper leaks
/// the socket when a handshake fails; shutting the group down closes it.
private final class SshTransport: @unchecked Sendable {
    let group: MultiThreadedEventLoopGroup
    let client: SSHClient
    var readSftp: SFTPClient
    var listSftp: SFTPClient
    var listingsSinceRecycle = 0
    var listingsInFlight = 0
    /// True while the listing channel is being replaced; new listings wait.
    var replacing = false

    init(
        group: MultiThreadedEventLoopGroup,
        client: SSHClient,
        readSftp: SFTPClient,
        listSftp: SFTPClient
    ) {
        self.group = group
        self.client = client
        self.readSftp = readSftp
        self.listSftp = listSftp
    }

    func shutdown() async {
        try? await listSftp.close()
        try? await readSftp.close()
        try? await client.close()
        try? await group.shutdownGracefully()
    }
}

/// One SSH connection's SFTP session. Actor-owned because Citadel's client
/// types are not Sendable; every call serializes here, which is also what a
/// single SFTP channel wants.
public actor SshSession: RemoteFileTree {
    private let config: SshConfig
    private var transport: SshTransport?
    /// The connection being established, shared by concurrent first callers so
    /// actor reentrancy cannot open (and then lose) several transports.
    private var connecting: Task<SshTransport, Error>?
    /// Bumped by every close, so a connection that lands after shutdown is
    /// discarded instead of resurrecting the session.
    private var generation = 0
    /// Directory listings leak their remote handles in the SFTP client we
    /// depend on (Citadel never sends CLOSE for OPENDIR), so the channel is
    /// recycled periodically: closing it reclaims every leaked handle at once.
    private var listingsSinceRecycle = 0

    /// The fingerprint this session pinned or confirmed, once connected.
    public private(set) var fingerprint: String?
    /// How many times the listing channel has been replaced to reclaim leaked
    /// directory handles; tests assert the recycling actually runs.
    public private(set) var listingRecycles = 0

    public init(config: SshConfig) {
        self.config = config
    }

    // MARK: connecting

    /// The transport, connecting if needed. `read` operations take the read
    /// channel; listing takes the recycled one.
    private func channel() async throws -> SshTransport {
        if let transport { return transport }
        if let connecting {
            let established = try await connecting.value
            _ = try await publish(established, generation: generation)
            return established
        }
        let config = self.config
        let validator = PinnedHostKey(expected: config.fingerprint)
        let generation = self.generation
        let task = Task<SshTransport, Error>.detached {
            try await Self.connect(config: config, validator: validator)
        }
        connecting = task
        do {
            let established = try await task.value
            _ = try await publish(established, generation: generation)
            fingerprint = config.fingerprint.isEmpty
                ? validator.learnedFingerprint
                : config.fingerprint
            return established
        } catch {
            connecting = nil
            if let error = error as? SshError { throw error }
            throw Self.failure(error)
        }
    }

    /// Takes ownership of a freshly established connection, or shuts it down
    /// when the session was closed (or replaced) while it was connecting.
    private func publish(_ established: SshTransport, generation establishedGeneration: Int) async throws -> SshTransport {
        guard establishedGeneration == generation, !Task.isCancelled else {
            await established.shutdown()
            throw CancellationError()
        }
        transport = established
        connecting = nil
        return established
    }

    private static func connect(config: SshConfig, validator: PinnedHostKey) async throws -> SshTransport {
        let offers = try offers(for: config)
        let auth = AccountAuthentication(username: config.user, offers: offers)
        // A dedicated group per session: Citadel's connect helper never closes
        // a channel whose handshake failed, so the group is what guarantees
        // those sockets cannot outlive the attempt.
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        var settings = SSHClientSettings(
            host: config.host,
            port: config.port,
            authenticationMethod: { SSHAuthenticationMethod.custom(auth) },
            hostKeyValidator: .custom(validator)
        )
        settings.group = group
        do {
            let client = try await SSHClient.connect(to: settings)
            try Task.checkCancellation()
            let readSftp = try await client.openSFTP()
            let listSftp = try await client.openSFTP()
            try Task.checkCancellation()
            return SshTransport(
                group: group, client: client, readSftp: readSftp, listSftp: listSftp
            )
        } catch let error as SshError {
            try? await group.shutdownGracefully()
            throw error
        } catch is InvalidHostKey {
            try? await group.shutdownGracefully()
            throw SshError.hostKeyMismatch(expected: config.fingerprint, actual: "")
        } catch is CancellationError {
            try? await group.shutdownGracefully()
            throw CancellationError()
        } catch {
            try? await group.shutdownGracefully()
            throw failure(error)
        }
    }

    /// Recycles the SFTP channel every so many listings, reclaiming the
    /// directory handles the dependency leaks.
    /// Replaces the listing channel once it has served enough listings,
    /// reclaiming every leaked directory handle at once. Only runs with no
    /// listing in flight; reads live on their own channel, so playback never
    /// sees a closed channel.
    private func recycleListingsIfNeeded(_ transport: SshTransport) async {
        guard transport.listingsSinceRecycle >= 200, transport.listingsInFlight == 0 else {
            return
        }
        transport.listingsSinceRecycle = 0
        transport.replacing = true
        listingRecycles += 1
        defer { transport.replacing = false }
        try? await transport.listSftp.close()
        do {
            transport.listSftp = try await transport.client.openSFTP()
        } catch {
            // A channel that cannot be reopened is worse than a stale one:
            // drop the whole connection so the next call reconnects.
            self.transport = nil
            await transport.shutdown()
        }
    }

    /// Turns Citadel's transport errors into the wizard's wording.
    private static func failure(_ error: Error) -> Error {
        let raw = String(describing: error).lowercased()
        if raw.contains("host key") || raw.contains("invalidhostkey") {
            return SshError.hostKeyMismatch(expected: "", actual: "")
        }
        if raw.contains("auth") || raw.contains("exhausted") {
            return SshError.refusedCredentials
        }
        if raw.contains("subsystem") {
            return SshError.noSftpSubsystem
        }
        if raw.contains("connection refused") || raw.contains("connect") || raw.contains("timeout") {
            return SshError.unreachable(String(describing: error))
        }
        return error
    }

    /// Builds the offer for the configured auth path. A key is parsed here,
    /// before any socket is opened, so a malformed key or wrong passphrase
    /// reports itself instead of masquerading as bad credentials.
    private static func offers(for config: SshConfig) throws -> [NIOSSHUserAuthenticationOffer.Offer] {
        switch config.auth {
        case .password:
            return [.password(.init(password: config.password))]
        case .none:
            // Tailscale (or any tailnet login) has no credential to send:
            // SSH's "none" method is the whole offer.
            return [.none]
        case .key:
            let passphrase = config.passphrase.isEmpty ? nil : Data(config.passphrase.utf8)
            let key = config.privateKey.trimmingCharacters(in: .whitespacesAndNewlines)
            if let ed25519 = try? Curve25519.Signing.PrivateKey(sshEd25519: key, decryptionKey: passphrase) {
                return [.privateKey(.init(privateKey: .init(ed25519Key: ed25519)))]
            }
            // RSA is refused deliberately: the SFTP stack we build on signs
            // ssh-rsa (SHA-1) only, which modern OpenSSH servers reject, so
            // offering it would fail with a misleading credential error.
            // Android's sshj does support RSA; recorded in DEC-05.
            throw SshError.keyUnreadable(
                "only ed25519 keys are supported so far (ssh-keygen -t ed25519); "
                    + "the key is malformed, or its passphrase is wrong"
            )
        }
    }

    public func close() async {
        generation += 1
        connecting?.cancel()
        connecting = nil
        let transport = self.transport
        self.transport = nil
        await transport?.shutdown()
    }

    // MARK: RemoteFileTree

    public func canonicalize(_ path: String) async throws -> String {
        let transport = try await channel()
        do {
            return try await transport.readSftp.getRealPath(atPath: path)
        } catch {
            throw Self.failure(error)
        }
    }

    public func list(_ path: String) async throws -> [RemoteEntry] {
        let transport = try await channel()
        await waitForReplacement(transport)
        transport.listingsInFlight += 1
        let names: [SFTPMessage.Name]
        do {
            names = try await transport.listSftp.listDirectory(atPath: path)
        } catch {
            transport.listingsInFlight -= 1
            if error is CancellationError { throw CancellationError() }
            // A refused or vanished directory arrives as a STATUS response to
            // our request: a per-directory problem the scan skips. Protocol
            // and connection statuses are not that, and must propagate.
            if let status = error as? SFTPMessage.Status, Self.isRecoverable(status) {
                throw SshError.directoryUnreadable(
                    status.message.isEmpty ? "cannot list \(path)" : status.message
                )
            }
            throw Self.failure(error)
        }
        transport.listingsInFlight -= 1
        transport.listingsSinceRecycle += 1
        await recycleListingsIfNeeded(transport)

        var entries: [RemoteEntry] = []
        // One NAME response can hold several entries (asyncssh batches them),
        // so every component counts, not just the message's last.
        for name in names {
            for component in name.components {
                let filename = component.filename
                guard filename != ".", filename != "..", !filename.isEmpty else { continue }
                entries.append(Self.entry(
                    path: path,
                    name: filename,
                    attributes: component.attributes,
                    longname: component.longname
                ))
            }
        }
        return entries
    }

    /// Directory failures worth skipping mid-walk; anything else (bad
    /// message, connection lost, unknown codes) fails the scan instead of
    /// silently producing a partial index.
    static func isRecoverable(_ status: SFTPMessage.Status) -> Bool {
        switch status.errorCode {
        case .noSuchFile, .permissionDenied, .unsupportedOperation:
            return true
        default:
            return false
        }
    }

    /// Listings wait out a channel replacement rather than failing on it.
    private func waitForReplacement(_ transport: SshTransport) async {
        while transport.replacing {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    public func stat(_ path: String) async throws -> RemoteEntry? {
        let transport = try await channel()
        do {
            let attributes = try await transport.readSftp.getAttributes(at: path)
            // `stat` already has the full path: it must not be joined again.
            let modified = attributes.accessModificationTime?.modificationTime
                .timeIntervalSince1970 ?? 0
            return RemoteEntry(
                name: (path as NSString).lastPathComponent,
                path: path,
                kind: Self.kind(of: attributes, longname: ""),
                size: Int64(attributes.size ?? 0),
                modifiedAt: Int64(max(0, modified))
            )
        } catch {
            return nil
        }
    }

    /// Reads one range, chunked to the 32 KiB SFTP servers actually honour.
    /// The player asks for larger ranges than a single READ allows.
    public func read(_ path: String, offset: UInt64, length: UInt32) async throws -> Data {
        let transport = try await channel()
        do {
            return try await transport.readSftp.withFile(filePath: path, flags: [.read]) { file in
                var data = Data()
                var position = offset
                var remaining = Int64(length)
                let chunk: UInt32 = 32 * 1024
                while remaining > 0 {
                    let wanted = UInt32(min(Int64(chunk), remaining))
                    let buffer = try await file.read(from: position, length: wanted)
                    let bytes = buffer.readableBytes
                    if bytes == 0 { break }
                    data.append(contentsOf: buffer.readableBytesView)
                    position += UInt64(bytes)
                    remaining -= Int64(bytes)
                }
                return data
            }
        } catch {
            throw Self.failure(error)
        }
    }

    private static func entry(
        path: String,
        name: String,
        attributes: SFTPFileAttributes,
        longname: String = ""
    ) -> RemoteEntry {
        let kind = kind(of: attributes, longname: longname)
        let modified = attributes.accessModificationTime?.modificationTime
            .timeIntervalSince1970 ?? 0
        return RemoteEntry(
            name: name,
            path: path.hasSuffix("/") ? path + name : path + "/" + name,
            kind: kind,
            size: Int64(attributes.size ?? 0),
            modifiedAt: Int64(max(0, modified))
        )
    }

    /// The SFTP type bits when the server sent them, else the leading letter
    /// of the `ls -l` line — asyncssh's readdir omits permissions for many
    /// entries, and without this every subfolder reads as "other".
    static func kind(of attributes: SFTPFileAttributes, longname: String) -> RemoteEntry.Kind {
        if let mode = attributes.permissions {
            switch mode & 0o170000 {
            case 0o040000: return .directory
            case 0o100000: return .file
            case 0o120000: return .symlink
            default: break
            }
        }
        switch longname.first {
        case "d": return .directory
        case "l": return .symlink
        case "-": return .file
        default: return .other
        }
    }
}

/// Connects, verifies the folders and comes back with what the wizard needs to
/// save: the host key it now trusts and the folders it will index. Nothing is
/// written until this succeeds — a wrong path or a refused key would otherwise
/// surface as an empty library with nothing to point at.
public enum SshProbe {
    public static func probe(_ values: [String: String]) async throws -> ProviderIdentity {
        let config = sshConfig(values)
        let session = SshSession(config: config)
        defer { Task { await session.close() } }
        return try await probe(config: config, session: session)
    }

    static func probe(config: SshConfig, session: SshSession) async throws -> ProviderIdentity {
        // Touch the session so a bad key or credential fails here rather than
        // on the first scan.
        _ = try await session.canonicalize(".")

        let folders: [String]
        if config.folders.isEmpty {
            folders = await suggestMusicFolders(session)
            guard !folders.isEmpty else { throw SshError.noMusicFolder }
        } else {
            folders = config.folders
        }
        var missing: [String] = []
        for folder in folders where await !isDirectory(session, path: folder) {
            missing.append(folder)
        }
        guard missing.isEmpty else { throw SshError.notAFolder(missing.joined(separator: ", ")) }

        let pinned = await session.fingerprint ?? config.fingerprint
        return ProviderIdentity(
            name: config.endpoint,
            detail: pinned,
            values: [
                "fingerprint": storedFingerprint(config: config, fingerprint: pinned),
                "folders": folders.joined(separator: "\n"),
            ]
        )
    }
}
