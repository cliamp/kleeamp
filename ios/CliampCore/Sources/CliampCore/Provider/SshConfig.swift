import Crypto
import Foundation

public let sshDefaultPort = 22

/// How to reach one SSH host, read out of a provider account's saved fields.
/// A direct port of Android's `SshConfig`.
public struct SshConfig: Sendable, Hashable {
    public enum Auth: String, Sendable {
        case password
        case key
        case none

        /// `tailscale` is the Android picker's "none" option: nio-ssh cannot
        /// use the tailnet identity, so Tailscale accounts are expected to
        /// have a reachable host and no credentials.
        public var label: String {
            switch self {
            case .password: "password"
            case .key: "key"
            case .none: "tailscale"
            }
        }
    }

    public var host: String
    public var port: Int
    public var user: String
    public var auth: Auth
    public var password: String
    public var privateKey: String
    public var passphrase: String
    /// `SHA256:...` of the host key, pinned at the first successful probe.
    public var fingerprint: String
    public var folders: [String]

    public init(
        host: String,
        port: Int = sshDefaultPort,
        user: String,
        auth: Auth = .password,
        password: String = "",
        privateKey: String = "",
        passphrase: String = "",
        fingerprint: String = "",
        folders: [String] = []
    ) {
        self.host = host
        self.port = port
        self.user = user
        self.auth = auth
        self.password = password
        self.privateKey = privateKey
        self.passphrase = passphrase
        self.fingerprint = fingerprint
        self.folders = folders
    }

    /// `user@host` or `user@host:port`, the account's display name.
    public var endpoint: String {
        port == sshDefaultPort ? "\(user)@\(host)" : "\(user)@\(host):\(port)"
    }
}

public func sshConfig(_ values: [String: String]) -> SshConfig {
    let host = (values["host"] ?? "").trimmingCharacters(in: .whitespaces)
    let port = Int((values["port"] ?? "").trimmingCharacters(in: .whitespaces)) ?? sshDefaultPort
    return SshConfig(
        host: host,
        port: port,
        user: (values["user"] ?? "").trimmingCharacters(in: .whitespaces),
        auth: SshConfig.Auth(rawValue: values["_auth"] ?? "password") ?? .password,
        password: values["password"] ?? "",
        privateKey: values["key"] ?? "",
        passphrase: values["passphrase"] ?? "",
        fingerprint: pinnedFingerprint(stored: values["fingerprint"] ?? "", host: host, port: port),
        folders: parseFolders(values["folders"] ?? "")
    )
}

/// Cross-field rules for the SSH spec, run before connecting.
public func sshExtraValidate(_ values: [String: String]) -> String? {
    let config = sshConfig(values)
    if config.host.isEmpty { return "host is required" }
    if config.user.isEmpty { return "username is required" }
    switch config.auth {
    case .password:
        if config.password.isEmpty { return "password is required" }
    case .key:
        if config.privateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "private key is required"
        }
    case .none:
        break
    }
    return nil
}

/// The stored fingerprint carries the host it was seen on — `nas:22 SHA256:…`
/// — and only counts for that one. Repointing an account at a different
/// machine goes back to trusting on first use instead of failing against the
/// old host's key.
public func pinnedFingerprint(stored: String, host: String, port: Int) -> String {
    let trimmed = stored.trimmingCharacters(in: .whitespaces)
    let seenOn = trimmed.split(separator: " ", maxSplits: 1).first.map(String.init) ?? ""
    let key = trimmed.split(separator: " ", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
    return seenOn == "\(host):\(port)" && !key.isEmpty ? key : ""
}

/// How a freshly pinned key is written back into the account's fields.
public func storedFingerprint(config: SshConfig, fingerprint: String) -> String {
    "\(config.host):\(config.port) \(fingerprint)"
}

/// One folder per line is what the field asks for, but commas are what people
/// type, so both are accepted. Trailing slashes are dropped so `/srv/music`
/// and `/srv/music/` index to the same paths rather than to two libraries.
public func parseFolders(_ raw: String) -> [String] {
    var seen = Set<String>()
    var folders: [String] = []
    for piece in raw.split(whereSeparator: { $0 == "\n" || $0 == "," }) {
        var folder = piece.trimmingCharacters(in: .whitespaces)
        guard !folder.isEmpty else { continue }
        if folder.count > 1, folder.hasSuffix("/") {
            folder = String(folder.dropLast())
        }
        if seen.insert(folder).inserted {
            folders.append(folder)
        }
    }
    return folders
}

/// OpenSSH's own fingerprint format — `SHA256:` plus the unpadded base64 of the
/// SHA-256 of the key's wire blob — so what the wizard shows can be compared
/// against `ssh-keygen -lf` on the server without any conversion.
public func fingerprint(ofPublicKeyBlob blob: Data) -> String {
    let digest = SHA256.hash(data: blob)
    let base64 = Data(digest).base64EncodedString()
    return "SHA256:" + base64.replacingOccurrences(of: "=", with: "")
}
