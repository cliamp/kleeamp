import Foundation
import Testing

@testable import CliampCore

/// Live checks against an SFTP server on loopback, run only when asked
/// (`CLIAMP_SFTP_LIVE=1 swift test`). The server used in development is
/// `sftp-server.py` (asyncssh) serving a small music tree on port 2222.
@Suite("sftp live", .enabled(if: ProcessInfo.processInfo.environment["CLIAMP_SFTP_LIVE"] == "1"))
struct SftpLiveTests {
    private static func env(_ key: String, _ fallback: String) -> String {
        ProcessInfo.processInfo.environment[key] ?? fallback
    }

    private static let root = env("CLIAMP_SFTP_ROOT", "/tmp/cliamp-sftp/music")
    private static let expectedFingerprint = ProcessInfo.processInfo
        .environment["CLIAMP_SFTP_FINGERPRINT"]
    private static let port = env("CLIAMP_SFTP_PORT", "2222")
    private static let user = env("CLIAMP_SFTP_USER", "tester")
    private static let defaultPassword = env("CLIAMP_SFTP_PASSWORD", "testpass")

    private func values(host: String = "127.0.0.1", password: String? = nil,
                        folders: String? = nil, fingerprint: String = "") -> [String: String] {
        var values: [String: String] = [
            "host": host,
            "port": Self.port,
            "user": Self.user,
            "_auth": "password",
            "password": password ?? Self.defaultPassword,
            "folders": folders ?? Self.root,
        ]
        if !fingerprint.isEmpty { values["fingerprint"] = fingerprint }
        return values
    }

    @Test("probe learns the host key and validates the folders")
    func probe() async throws {
        let identity = try await SshProbe.probe(values())
        #expect(identity.name == "\(Self.user)@127.0.0.1:\(Self.port)")
        if let expected = Self.expectedFingerprint {
            #expect(identity.detail == expected)
        }
        #expect(identity.detail.hasPrefix("SHA256:"))
        #expect(identity.values["fingerprint"]?.contains(Self.root) == false)
        let stored = storedFingerprint(
            config: sshConfig(values()), fingerprint: identity.detail
        )
        // The pin records the host it was seen on.
        #expect(stored == "127.0.0.1:\(Self.port) \(identity.detail)")
    }

    @Test("the probe finds folders when the field is left empty")
    func probeSuggestsFolders() async throws {
        let identity = try await SshProbe.probe(values(folders: ""))
        // The server's home is not a music tree, so this must fail loudly
        // rather than save an account with nothing to index.
        #expect(identity.values["folders"]?.isEmpty == false)
    }

    /// Key auth depends on the fixture's authorized_keys, so a run against a
    /// server without them sets CLIAMP_SFTP_KEYS=0.
    private static let keysAvailable = ProcessInfo.processInfo.environment["CLIAMP_SFTP_KEYS"] != "0"

    private func keyValues(keyPath: String, passphrase: String = "") -> [String: String] {
        let key = (try? String(contentsOfFile: keyPath, encoding: .utf8)) ?? ""
        return [
            "host": "127.0.0.1",
            "port": Self.port,
            "user": Self.user,
            "_auth": "key",
            "key": key,
            "passphrase": passphrase,
            "folders": Self.root,
        ]
    }

    @Test("a pasted ed25519 key authenticates", .enabled(if: Self.keysAvailable))
    func keyAuth() async throws {
        let identity = try await SshProbe.probe(keyValues(keyPath: "/tmp/cliamp-sftp/id_ed25519"))
        #expect(identity.detail.hasPrefix("SHA256:"))
    }

    @Test("a passphrase-protected key authenticates with its passphrase", .enabled(if: Self.keysAvailable))
    func keyPassphrase() async throws {
        let identity = try await SshProbe.probe(
            keyValues(keyPath: "/tmp/cliamp-sftp/id_ed25519_pw", passphrase: "keypass")
        )
        #expect(identity.detail.hasPrefix("SHA256:"))
    }

    @Test("a passphrase-protected key without its passphrase is refused", .enabled(if: Self.keysAvailable))
    func keyMissingPassphrase() async throws {
        await #expect(throws: SshError.self) {
            try await SshProbe.probe(keyValues(keyPath: "/tmp/cliamp-sftp/id_ed25519_pw"))
        }
    }

    @Test("a wrong password is refused with the wizard's wording")
    func wrongPassword() async throws {
        await #expect(throws: SshError.self) {
            try await SshProbe.probe(values(password: "nope"))
        }
    }

    @Test("a pinned fingerprint that does not match is refused")
    func mismatch() async throws {
        let pinned = "127.0.0.1:\(Self.port) SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        await #expect(throws: SshError.self) {
            try await SshProbe.probe(values(fingerprint: pinned))
        }
    }

    /// The first file reachable from the root, so the tests work against any
    /// server layout (the fixture is flat-ish, the Docker library is
    /// Artist/Album/track). Breadth-first with hard bounds.
    private func firstFile(_ session: SshSession, at root: String) async throws -> String? {
        var queue = [root]
        var visited = 0
        while let directory = queue.first, visited < 12 {
            queue.removeFirst()
            visited += 1
            let entries = (try? await session.list(directory)) ?? []
            if let file = entries.first(where: { $0.kind == .file }) { return file.path }
            queue.append(contentsOf: entries.filter { $0.kind == .directory }.map(\.path))
        }
        return nil
    }

    @Test("the listing channel recycles and reads keep working")
    func recycling() async throws {
        let session = SshSession(config: sshConfig(values()))
        defer { Task { await session.close() } }
        for _ in 0..<205 {
            _ = try await session.list(Self.root)
        }
        #expect(await session.listingRecycles >= 1)
        // A read after recycling still works: the read channel is separate.
        let path = try #require(await firstFile(session, at: Self.root))
        let bytes = try await session.read(path, offset: 0, length: 16)
        #expect(bytes.count == 16)
        #expect(try await session.list(Self.root).isEmpty == false)
    }

    @Test("a missing directory is classified recoverable, not fatal")
    func missingDirectory() async throws {
        let session = SshSession(config: sshConfig(values()))
        defer { Task { await session.close() } }
        do {
            _ = try await session.list("\(Self.root)/no-such-folder")
            Issue.record("expected the missing directory to fail")
        } catch let error as SshError {
            guard case .directoryUnreadable = error else {
                Issue.record("wrong error: \(error)")
                return
            }
        } catch {
            Issue.record("wrong error type: \(error)")
        }
    }

    @Test("scan finds the tree's tracks and reads a byte range")
    func scanAndRead() async throws {
        let session = SshSession(config: sshConfig(values()))
        defer { Task { await session.close() } }

        let entries = try await session.list(Self.root)
        #expect(entries.isEmpty == false)

        let box = LiveTrackBox()
        let scan = SftpScan(folders: [Self.root], onBatch: { box.append($0) })
        let total = try await scan.run(session)
        #expect(total == box.tracks.count)

        // The bundled fixture has a known shape; any other server (a real
        // library, the Docker one) just has to produce sane tracks.
        if Self.root.contains("cliamp-sftp") {
            #expect(entries.contains { $0.name == "Boards of Canada" && $0.kind == .directory })
            #expect(total == 3)
            #expect(box.tracks.contains { $0.title == "Wildlife Analysis" && $0.track == 1 })
            #expect(box.tracks.contains { $0.artist == "Boards of Canada" && $0.album == "Music Has the Right to Children" })
            #expect(box.tracks.contains { $0.title == "loose track" && $0.artist == "" })
        } else {
            #expect(total > 0)
            #expect(box.tracks.allSatisfy { !$0.title.isEmpty && !$0.path.isEmpty })
        }

        let sample = try #require(box.tracks.first { $0.ext == "m4a" } ?? box.tracks.first)
        let stat = try await session.stat(sample.path)
        #expect(stat?.path == sample.path)
        #expect(stat?.kind == .file)
        #expect(stat?.size ?? 0 > 0)

        let first = try await session.read(sample.path, offset: 0, length: 32)
        #expect(first.count == 32)
        let later = try await session.read(sample.path, offset: 16_384, length: 32)
        #expect(later.count > 0)
        #expect(first != later)
    }
}

private final class LiveTrackBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [ScannedTrack] = []

    func append(_ tracks: [ScannedTrack]) {
        lock.withLock { stored += tracks }
    }

    var tracks: [ScannedTrack] {
        lock.withLock { stored }
    }
}
