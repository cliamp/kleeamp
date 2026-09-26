import Foundation

/// One directory entry on a remote host. `path` is absolute and canonical
/// enough for the scan's dedup set; `kind` mirrors what lstat said.
public struct RemoteEntry: Sendable, Hashable {
    public enum Kind: Sendable {
        case directory
        case file
        case symlink
        case other
    }

    public let name: String
    public let path: String
    public let kind: Kind
    public let size: Int64
    /// Seconds since epoch, 0 when the server did not say.
    public let modifiedAt: Int64

    public init(name: String, path: String, kind: Kind, size: Int64 = 0, modifiedAt: Int64 = 0) {
        self.name = name
        self.path = path
        self.kind = kind
        self.size = size
        self.modifiedAt = modifiedAt
    }
}

/// What the SFTP scanner needs from a connection. The Citadel-backed session
/// is one implementation; tests hand in a fake tree so the walk's rules are
/// verified without a server.
public protocol RemoteFileTree: Sendable {
    /// The server's own absolute path for a (possibly relative, possibly
    /// symlinked) path.
    func canonicalize(_ path: String) async throws -> String
    /// The entries inside one directory. `.` and `..` are excluded.
    func list(_ path: String) async throws -> [RemoteEntry]
    /// One path's metadata, or nil when it does not exist.
    func stat(_ path: String) async throws -> RemoteEntry?
    /// A byte range of a file. Empty data means end of file.
    func read(_ path: String, offset: UInt64, length: UInt32) async throws -> Data
}
