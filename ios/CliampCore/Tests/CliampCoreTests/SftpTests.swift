import Foundation
import Testing

@testable import CliampCore

/// An in-memory remote tree so the walk's rules are verified without a server.
private final class FakeTree: RemoteFileTree, @unchecked Sendable {
    var entries: [String: [RemoteEntry]] = [:]
    var canonical: [String: String] = [:]
    var stats: [String: RemoteEntry] = [:]
    var unreadable: Set<String> = []
    /// Listing these paths throws the given error instead of reading.
    var failures: [String: Error] = [:]
    private let lock = NSLock()
    private(set) var listed: [String] = []

    init(entries: [String: [RemoteEntry]], canonical: [String: String] = [:]) {
        self.entries = entries
        self.canonical = canonical
    }

    func canonicalize(_ path: String) async throws -> String {
        lock.withLock {
            if let mapped = canonical[path] { return mapped }
            return path
        }
    }

    func list(_ path: String) async throws -> [RemoteEntry] {
        enum Outcome {
            case entries([RemoteEntry])
            case failure(Error)
        }
        let outcome: Outcome = lock.withLock {
            listed.append(path)
            if let failure = failures[path] { return .failure(failure) }
            if unreadable.contains(path) { return .failure(SshError.missing(path)) }
            return .entries(entries[path] ?? [])
        }
        switch outcome {
        case .entries(let entries): return entries
        case .failure(let error): throw error
        }
    }

    func stat(_ path: String) async throws -> RemoteEntry? {
        lock.withLock {
            if let known = stats[path] { return known }
            // Derive from the fixtures so tests only need one table.
            if entries[path] != nil { return directory(path) }
            for list in entries.values where list.contains(where: { $0.path == path }) {
                return file(path)
            }
            return nil
        }
    }

    func read(_ path: String, offset: UInt64, length: UInt32) async throws -> Data {
        Data()
    }
}

private func file(_ path: String) -> RemoteEntry {
    RemoteEntry(name: (path as NSString).lastPathComponent, path: path, kind: .file)
}

private func directory(_ path: String) -> RemoteEntry {
    RemoteEntry(name: (path as NSString).lastPathComponent, path: path, kind: .directory)
}

private func collect(_ scan: SftpScan, _ tree: FakeTree) async throws -> [ScannedTrack] {
    let box = TrackBox()
    let scan = SftpScan(folders: scan.folders, onBatch: { tracks in box.append(tracks) },
                        onProgress: { _, _ in })
    _ = try await scan.run(tree)
    return box.tracks
}

private final class TrackBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [ScannedTrack] = []

    func append(_ tracks: [ScannedTrack]) {
        lock.withLock { stored += tracks }
    }

    var tracks: [ScannedTrack] {
        lock.withLock { stored }
    }
}

@Suite("provider accounts")
struct ProviderAccountStoreTests {
    private func store() -> (ProviderAccountStore, MemoryVault) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("cliamp-providers-\(UUID().uuidString).json")
        let vault = MemoryVault()
        return (ProviderAccountStore(file: url, vault: vault), vault)
    }

    @Test("secrets go to the vault; the file keeps non-secret fields only")
    func secretSplitting() throws {
        let (store, vault) = store()
        let account = store.save(
            id: nil,
            providerKey: "ssh",
            label: "me@nas",
            values: [
                "host": "nas", "port": "22", "user": "me",
                "_auth": "password", "password": "hunter2", "folders": "/srv/music",
            ],
            spec: ProviderCatalog.ssh
        )
        #expect(vault.stored(accountId: account.id) == ["password": "hunter2"])
        #expect(account.values["password"] == nil)
        #expect(account.values["host"] == "nas")

        // The raw file must not contain the secret.
        let raw = try String(contentsOf: store.fileForTesting, encoding: .utf8)
        #expect(!raw.contains("hunter2"))

        let resolved = store.resolvedValues(for: account, spec: ProviderCatalog.ssh)
        #expect(resolved["password"] == "hunter2")
        #expect(resolved["host"] == "nas")
    }

    @Test("editing keeps the id and replaces the stored values")
    func editing() {
        let (store, vault) = store()
        let account = store.save(
            id: nil, providerKey: "ssh", label: "me@nas",
            values: ["host": "nas", "user": "me", "_auth": "password", "password": "one"],
            spec: ProviderCatalog.ssh
        )
        let edited = store.save(
            id: account.id, providerKey: "ssh", label: "me@nas:2222",
            values: ["host": "nas", "port": "2222", "user": "me", "_auth": "password", "password": "two"],
            spec: ProviderCatalog.ssh
        )
        #expect(edited.id == account.id)
        #expect(store.all().count == 1)
        #expect(vault.stored(accountId: account.id) == ["password": "two"])
    }

    @Test("removing an account deletes its secrets and persists")
    func removal() {
        let (store, vault) = store()
        let account = store.save(
            id: nil, providerKey: "ssh", label: "me@nas",
            values: ["host": "nas", "user": "me", "_auth": "password", "password": "x"],
            spec: ProviderCatalog.ssh
        )
        store.remove(id: account.id)
        #expect(store.all().isEmpty)
        #expect(vault.stored(accountId: account.id).isEmpty)
    }

    @Test("the ssh spec validates its auth branches")
    func specValidation() {
        let spec = ProviderCatalog.ssh
        #expect(spec.missingRequired(["host": "nas", "user": "me", "_auth": "password"])
            .map(\.key) == ["password"])
        #expect(spec.missingRequired(["host": "nas", "user": "me", "_auth": "none"]).isEmpty)
        #expect(spec.visibleFields(["_auth": "key"]).map(\.key)
            == ["host", "port", "user", "key", "passphrase", "folders"])
        let summary = spec.summary([
            "host": "nas", "user": "me", "_auth": "none", "folders": "/srv/music",
        ])
        #expect(summary == "me@nas · /srv/music")
    }
}

@Suite("sftp describe")
struct SftpDescribeTests {
    @Test("a full Artist/Album/Track layout is read from the path")
    func fullLayout() {
        let track = describe(
            root: "/srv/music",
            path: "/srv/music/Boards of Canada/Music Has the Right to Children/01 - Wildlife Analysis.flac",
            size: 40_000, mtime: 12
        )
        #expect(track.title == "Wildlife Analysis")
        #expect(track.artist == "Boards of Canada")
        #expect(track.album == "Music Has the Right to Children")
        #expect(track.track == 1)
        #expect(track.artistKey == "boards of canada")
        #expect(track.albumKey == "/srv/music/Boards of Canada/Music Has the Right to Children")
        #expect(track.ext == "flac")
        #expect(track.year == 0)
        #expect(track.size == 40_000)
        #expect(track.mtime == 12)
    }

    @Test("track number separators all mean the same thing")
    func trackNumbers() {
        #expect(describe(root: "/m", path: "/m/A/07 - Money.mp3").track == 7)
        #expect(describe(root: "/m", path: "/m/A/07. Money.mp3").track == 7)
        #expect(describe(root: "/m", path: "/m/A/07 Money.mp3").track == 7)
        #expect(describe(root: "/m", path: "/m/A/07 - Money.mp3").title == "Money")
        // A title that opens with a year keeps it.
        let party = describe(root: "/m", path: "/m/A/1999 Party.mp3")
        #expect(party.track == 0)
        #expect(party.title == "1999 Party")
    }

    @Test("years leave the album name through all three shapes")
    func years() {
        let leading = describe(root: "/m", path: "/m/1973 - Dark Side/01.mp3")
        #expect(leading.album == "Dark Side")
        #expect(leading.year == 1973)
        #expect(leading.artist == "")

        let trailing = describe(root: "/m", path: "/m/Artist/Dark Side (1973)/01.mp3")
        #expect(trailing.album == "Dark Side")
        #expect(trailing.year == 1973)

        let bracketed = describe(root: "/m", path: "/m/Artist/(1973) Dark Side/01.mp3")
        #expect(bracketed.album == "Dark Side")
        #expect(bracketed.year == 1973)
    }

    @Test("one level under the root reads as Artist - Album when punctuated")
    func artistAlbumLevel() {
        let split = describe(root: "/srv/music", path: "/srv/music/Boards of Canada - Geogaddi/01.mp3")
        #expect(split.artist == "Boards of Canada")
        #expect(split.album == "Geogaddi")
    }

    @Test("only the first separator names the artist")
    func multiSeparatorFolder() {
        let track = describe(root: "/m", path: "/m/Pink Floyd - 1973 - Dark Side/01.mp3")
        #expect(track.artist == "Pink Floyd")
        #expect(track.album == "Dark Side")
        #expect(track.year == 1973)
    }

    @Test("a flat folder gets the folder's name as the album and no artist")
    func flatFolder() {
        let track = describe(root: "/srv/music", path: "/srv/music/loose.mp3")
        #expect(track.artist == "")
        #expect(track.album == "music")
        #expect(track.albumKey == "/srv/music")
    }

    @Test("a file with no extension keeps its whole name as the title")
    func noExtension() {
        let track = describe(root: "/m", path: "/m/A/Song")
        #expect(track.title == "Song")
        #expect(track.ext == "")
    }
}

@Suite("sftp scan")
struct SftpScanTests {
    @Test("walks folders, skipping hidden and non-audio entries")
    func walksTree() async throws {
        let tree = FakeTree(
            entries: [
                "/m": [directory("/m/Album"), file("/m/cover.jpg"), file("/m/.hidden.mp3"), file("/m/loose.mp3")],
                "/m/Album": [file("/m/Album/01 - One.flac"), file("/m/Album/notes.txt")],
            ]
        )
        let tracks = try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        #expect(tracks.map(\.path).sorted() == ["/m/Album/01 - One.flac", "/m/loose.mp3"])
    }

    @Test("an unreadable subdirectory does not stop the rest")
    func unreadableDirectory() async throws {
        let tree = FakeTree(
            entries: [
                "/m": [directory("/m/Locked"), directory("/m/Open")],
                "/m/Locked": [file("/m/Locked/x.mp3")],
                "/m/Open": [file("/m/Open/y.mp3")],
            ]
        )
        tree.unreadable = ["/m/Locked"]
        let tracks = try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        #expect(tracks.map(\.path) == ["/m/Open/y.mp3"])
    }

    @Test("a symlink pointing at an ancestor cannot loop")
    func symlinkLoop() async throws {
        let tree = FakeTree(
            entries: [
                "/m": [directory("/m/Album"), RemoteEntry(name: "loop", path: "/m/loop", kind: .symlink)],
                "/m/Album": [file("/m/Album/a.mp3")],
            ],
            canonical: ["/m/loop": "/m"]
        )
        let tracks = try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        #expect(tracks.map(\.path) == ["/m/Album/a.mp3"])
    }

    @Test("a symlinked folder is followed through stat")
    func symlinkedFolder() async throws {
        let tree = FakeTree(
            entries: [
                "/m": [RemoteEntry(name: "Albums", path: "/m/Albums", kind: .symlink)],
                "/m/Albums": [file("/m/Albums/a.mp3")],
            ],
            canonical: ["/m/Albums": "/m/Albums"]
        )
        tree.stats["/m/Albums"] = directory("/m/Albums")
        let tree2 = tree
        let tracks = try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree2)
        #expect(tracks.map(\.path) == ["/m/Albums/a.mp3"])
    }

    @Test("the same folder scanned twice is only walked once")
    func duplicateRoots() async throws {
        let tree = FakeTree(entries: ["/m": [file("/m/a.mp3")]], canonical: ["/m": "/m"])
        let tracks = try await collect(SftpScan(folders: ["/m", "/m/"], onBatch: { _ in }), tree)
        #expect(tracks.count == 1)
    }

    @Test("batches arrive as the walk finds tracks")
    func batching() async throws {
        var entries: [RemoteEntry] = []
        for index in 0..<600 {
            entries.append(file("/m/f\(index).mp3"))
        }
        let tree = FakeTree(entries: ["/m": entries])
        let box = TrackBox()
        let scan = SftpScan(folders: ["/m"], onBatch: { box.append($0) })
        let total = try await scan.run(tree)
        #expect(total == 600)
        #expect(box.tracks.count == 600)
    }

    @Test("a refused credential fails the scan instead of reporting zero")
    func refusesToSucceedOnAuthFailure() async {
        let tree = FakeTree(entries: ["/m": [file("/m/a.mp3")]])
        tree.failures["/m"] = SshError.refusedCredentials
        await #expect(throws: SshError.self) {
            try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        }
    }

    @Test("a fatal failure on a later root still fails the whole scan")
    func fatalAfterSuccess() async {
        let tree = FakeTree(entries: [
            "/good": [file("/good/a.mp3")],
            "/bad": [file("/bad/b.mp3")],
        ])
        tree.failures["/bad"] = SshError.refusedCredentials
        await #expect(throws: SshError.self) {
            try await collect(SftpScan(folders: ["/good", "/bad"], onBatch: { _ in }), tree)
        }
    }

    @Test("cancellation propagates out of the walk")
    func cancellationPropagates() async {
        let tree = FakeTree(entries: ["/m": [file("/m/a.mp3")]])
        tree.failures["/m"] = CancellationError()
        await #expect(throws: CancellationError.self) {
            try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        }
    }

    private struct UnknownError: Error {}

    @Test("an unclassified error at any depth fails the scan")
    func unknownErrorsFail() async {
        let tree = FakeTree(entries: [
            "/m": [directory("/m/Album")],
            "/m/Album": [file("/m/Album/a.mp3")],
        ])
        tree.failures["/m/Album"] = UnknownError()
        await #expect(throws: UnknownError.self) {
            try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        }
    }

    @Test("a missing subdirectory is still skipped, not fatal")
    func missingSubdirectoryRecovers() async throws {
        let tree = FakeTree(entries: [
            "/m": [directory("/m/Gone"), directory("/m/Here")],
            "/m/Here": [file("/m/Here/a.mp3")],
        ])
        tree.failures["/m/Gone"] = SshError.directoryUnreadable("/m/Gone")
        let tracks = try await collect(SftpScan(folders: ["/m"], onBatch: { _ in }), tree)
        #expect(tracks.map(\.path) == ["/m/Here/a.mp3"])
    }

    @Test("suggested folders only include places that hold audio")
    func folderSuggestions() async throws {
        let tree = FakeTree(
            entries: [
                "/": [directory("/home"), directory("/srv")],
                "/home": [directory("/home/music")],
                "/home/music": [file("/home/music/a.flac")],
                "/srv": [directory("/srv/music")],
                "/srv/music": [],
            ],
            canonical: [".": "/home"]
        )
        let folders = await suggestMusicFolders(tree)
        #expect(folders == ["/home/music"])
    }

    @Test("a folder holding only hidden audio is not suggested")
    func hiddenFoldersNotSuggested() async {
        let tree = FakeTree(entries: [
            "/": [directory("/home")],
            "/home": [directory("/home/.secret")],
            "/home/.secret": [file("/home/.secret/a.mp3")],
        ], canonical: [".": "/home"])
        #expect(await suggestMusicFolders(tree) == [])
    }

    @Test("newest albums tie-break by name ascending")
    func newestTieBreak() {
        let store = SftpIndexStore(file: FileManager.default.temporaryDirectory
            .appendingPathComponent("cliamp-sftp-\(UUID().uuidString).json"))
        let tracks = [
            SftpTrack(accountId: "acc", scanned: describe(root: "/m", path: "/m/Zeta/a.mp3", mtime: 10)),
            SftpTrack(accountId: "acc", scanned: describe(root: "/m", path: "/m/Alpha/a.mp3", mtime: 10)),
        ]
        store.commit(accountId: "acc", folders: ["/m"], scannedAt: 1, tracks: tracks)
        #expect(store.albums(accountId: "acc", style: "newest").map(\.name) == ["Alpha", "Zeta"])
    }
}

@Suite("ssh probe preflight")
struct SshProbePreflightTests {
    @Test("a malformed key is refused before any socket is opened")
    func malformedKey() async {
        let values: [String: String] = [
            "host": "127.0.0.1", "port": "9", "user": "tester",
            "_auth": "key", "key": "-----BEGIN OPENSSH PRIVATE KEY-----\nnot really\n",
            "folders": "/m",
        ]
        do {
            _ = try await SshProbe.probe(values)
            Issue.record("expected the malformed key to fail")
        } catch let error as SshError {
            guard case .keyUnreadable = error else {
                Issue.record("wrong error: \(error)")
                return
            }
        } catch {
            Issue.record("wrong error type: \(error)")
        }
    }
}

@Suite("ssh config")
struct SshConfigTests {
    @Test("folders accept newlines and commas, drop trailing slashes")
    func folders() {
        #expect(parseFolders("/srv/music\n/mnt/media") == ["/srv/music", "/mnt/media"])
        #expect(parseFolders("/srv/music/, /mnt/media ,") == ["/srv/music", "/mnt/media"])
        #expect(parseFolders("/srv/music\n/srv/music") == ["/srv/music"])
        #expect(parseFolders("") == [])
    }

    @Test("a fingerprint only counts for the host it was seen on")
    func pinning() {
        #expect(pinnedFingerprint(stored: "nas:22 SHA256:abc", host: "nas", port: 22) == "SHA256:abc")
        // Repointing at another machine goes back to trust on first use.
        #expect(pinnedFingerprint(stored: "nas:22 SHA256:abc", host: "other", port: 22) == "")
        #expect(pinnedFingerprint(stored: "nas:2222 SHA256:abc", host: "nas", port: 22) == "")
        #expect(pinnedFingerprint(stored: "", host: "nas", port: 22) == "")
        let config = SshConfig(host: "nas", port: 22, user: "me")
        #expect(storedFingerprint(config: config, fingerprint: "SHA256:abc") == "nas:22 SHA256:abc")
    }

    @Test("fingerprints match OpenSSH's own format")
    func fingerprintFormat() {
        let value = fingerprint(ofPublicKeyBlob: Data("cliamp-host-key".utf8))
        #expect(value == "SHA256:TjHPtCHdqqV3rOJWSuMrHjzurHcJVv0vk5aGX2gkSV8")
    }

    @Test("config reads the picker, port default and endpoint")
    func config() {
        let config = sshConfig([
            "host": "nas.local ", "port": "", "user": "me",
            "_auth": "none", "folders": "/srv/music",
        ])
        #expect(config.host == "nas.local")
        #expect(config.port == 22)
        #expect(config.auth == .none)
        #expect(config.endpoint == "me@nas.local")
        #expect(sshConfig(["host": "nas", "user": "me", "port": "2222"]).endpoint == "me@nas:2222")
    }

    @Test("extra validation names the missing piece per auth path")
    func validation() {
        #expect(sshExtraValidate([:]) == "host is required")
        #expect(sshExtraValidate(["host": "nas"]) == "username is required")
        #expect(sshExtraValidate(["host": "nas", "user": "me"]) == "password is required")
        #expect(sshExtraValidate(["host": "nas", "user": "me", "password": "x"]) == nil)
        #expect(sshExtraValidate(["host": "nas", "user": "me", "_auth": "key"]) == "private key is required")
        #expect(sshExtraValidate(["host": "nas", "user": "me", "_auth": "key", "key": "-----BEGIN"]) == nil)
        #expect(sshExtraValidate(["host": "nas", "user": "me", "_auth": "none"]) == nil)
    }
}

@Suite("sftp library")
struct SftpLibraryTests {
    private func store() -> SftpIndexStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("cliamp-sftp-\(UUID().uuidString).json")
        return SftpIndexStore(file: url)
    }

    private func track(_ path: String, track: Int = 0, album: String = "Album",
                       artist: String = "Artist", albumKey: String? = nil,
                       mtime: Int64 = 0) -> SftpTrack {
        SftpTrack(
            accountId: "acc",
            scanned: describe(root: "/m", path: path, mtime: mtime)
        )
    }

    @Test("a changed folder list marks the index stale")
    func staleness() {
        let store = store()
        #expect(store.isStale(accountId: "acc", folders: ["/m"]))
        store.commit(accountId: "acc", folders: ["/m"], scannedAt: 1, tracks: [])
        #expect(!store.isStale(accountId: "acc", folders: ["/m"]))
        #expect(store.isStale(accountId: "acc", folders: ["/m", "/n"]))
        store.forget(accountId: "acc")
        #expect(store.isStale(accountId: "acc", folders: ["/m"]))
    }

    @Test("albums, artists and tracks group and order like Android's queries")
    func grouping() {
        let store = store()
        let tracks = [
            track("/m/Artist/Album/02 - Two.flac"),
            track("/m/Artist/Album/01 - One.flac"),
            track("/m/Other/Album Two/01.mp3"),
        ]
        store.commit(accountId: "acc", folders: ["/m"], scannedAt: 1, tracks: tracks)
        #expect(store.albums(accountId: "acc", style: "az").map(\.name) == ["Album", "Album Two"])
        #expect(store.artists(accountId: "acc").map(\.name) == ["Artist", "Other"])
        #expect(store.albumTracks(accountId: "acc", albumKey: "/m/Artist/Album").map(\.title) == ["One", "Two"])
        #expect(store.albums(accountId: "acc", style: "az").first?.songCount == 2)
    }

    @Test("newest sorts by the most recent file in the album")
    func newestOrder() {
        let store = store()
        let tracks = [
            track("/m/A/old.mp3", mtime: 10),
            track("/m/B/new.mp3", mtime: 20),
        ]
        store.commit(accountId: "acc", folders: ["/m"], scannedAt: 1, tracks: tracks)
        #expect(store.albums(accountId: "acc", style: "newest").map(\.name) == ["B", "A"])
    }

    @Test("a commit prunes what the scan no longer found, and survives a reopen")
    func persistence() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("cliamp-sftp-\(UUID().uuidString).json")
        let store = SftpIndexStore(file: url)
        store.commit(accountId: "acc", folders: ["/m"], scannedAt: 1, tracks: [track("/m/a.mp3")])
        store.commit(accountId: "acc", folders: ["/m"], scannedAt: 2, tracks: [track("/m/b.mp3")])
        #expect(store.tracks(accountId: "acc").map(\.path) == ["/m/b.mp3"])

        let reopened = SftpIndexStore(file: url)
        #expect(reopened.tracks(accountId: "acc").map(\.path) == ["/m/b.mp3"])
        #expect(reopened.index(accountId: "acc")?.scannedAt == 2)
    }

    @Test("a track's station carries the account and the encoded remote path")
    func stations() {
        let track = track("/m/Night Drive/01 Midnight City.m4a")
        let station = track.station
        #expect(station.id == "prov:acc:/m/Night Drive/01 Midnight City.m4a")
        #expect(station.source == .provider)
        #expect(station.name == "Midnight City")
        #expect(station.url.hasPrefix("cliamp-sftp://acc/"))
        let parsed = SftpURI.parse(station.url)
        #expect(parsed?.accountId == "acc")
        #expect(parsed?.path == "/m/Night Drive/01 Midnight City.m4a")
    }

    @Test("uri parsing rejects other schemes and missing paths")
    func uriParsing() {
        #expect(SftpURI.parse("https://example.com/a") == nil)
        #expect(SftpURI.parse("cliamp-sftp://acc") == nil)
        #expect(SftpURI.parse(SftpURI.url(accountId: "a", path: "/x#y z.mp3"))?.path == "/x#y z.mp3")
    }
}
