import Foundation

/// Where a provider account's secrets live. Android keeps them in encrypted
/// preferences; iOS implements this over the Keychain, and tests use an
/// in-memory vault.
public protocol SecretVault: Sendable {
    func setSecret(_ value: String, accountId: String, field: String)
    func secret(accountId: String, field: String) -> String?
    func deleteSecrets(accountId: String)
}

/// An in-memory vault, for tests and previews.
public final class MemoryVault: SecretVault, @unchecked Sendable {
    private let lock = NSLock()
    private var secrets: [String: [String: String]] = [:]

    public init() {}

    public func setSecret(_ value: String, accountId: String, field: String) {
        lock.withLock { secrets[accountId, default: [:]][field] = value }
    }

    public func secret(accountId: String, field: String) -> String? {
        lock.withLock { secrets[accountId]?[field] }
    }

    public func deleteSecrets(accountId: String) {
        lock.withLock { _ = secrets.removeValue(forKey: accountId) }
    }

    /// Test helper: what is stored for an account.
    public func stored(accountId: String) -> [String: String] {
        lock.withLock { secrets[accountId] ?? [:] }
    }
}

/// The configured provider accounts, persisted as JSON. Fields the spec marks
/// secret are stripped before writing and kept in the vault instead, so the
/// file (and any backup of it) never holds a password, token or private key.
public final class ProviderAccountStore: @unchecked Sendable {
    private struct Payload: Codable {
        var accounts: [ProviderAccount]
    }

    private let file: URL
    private let vault: SecretVault
    private let lock = NSLock()
    private var accounts: [ProviderAccount]

    public init(file: URL, vault: SecretVault) {
        self.file = file
        self.vault = vault
        let payload = (try? Data(contentsOf: file))
            .flatMap { try? JSONDecoder().decode(Payload.self, from: $0) }
        self.accounts = payload?.accounts ?? []
    }

    public static func appStore(vault: SecretVault) -> ProviderAccountStore {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let directory = base.appendingPathComponent("CliampLibrary", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return ProviderAccountStore(
            file: directory.appendingPathComponent("providers.json"), vault: vault
        )
    }

    /// The JSON file's location, so a test can prove secrets never land in it.
    public var fileForTesting: URL { file }

    public func all() -> [ProviderAccount] {
        lock.withLock { accounts }
    }

    public func account(id: String) -> ProviderAccount? {
        lock.withLock { accounts.first { $0.id == id } }
    }

    /// The values a client needs: the stored non-secret fields plus whatever
    /// the vault holds for this account.
    public func resolvedValues(for account: ProviderAccount, spec: ProviderSpec) -> [String: String] {
        var values = account.values
        for field in spec.fields where field.secret {
            if let secret = vault.secret(accountId: account.id, field: field.key) {
                values[field.key] = secret
            }
        }
        return values
    }

    /// Saves a form's values: secrets go to the vault, everything else to the
    /// account record. A new account (id nil) gets a fresh id.
    @discardableResult
    public func save(
        id: String?,
        providerKey: String,
        label: String,
        values: [String: String],
        spec: ProviderSpec
    ) -> ProviderAccount {
        let accountId = id ?? UUID().uuidString
        var stored: [String: String] = [:]
        for (key, value) in values {
            if spec.fields.first(where: { $0.key == key })?.secret == true {
                vault.setSecret(value, accountId: accountId, field: key)
            } else {
                stored[key] = value
            }
        }
        let account = ProviderAccount(
            id: accountId, providerKey: providerKey, label: label, values: stored
        )
        lock.withLock {
            if let index = accounts.firstIndex(where: { $0.id == accountId }) {
                accounts[index] = account
            } else {
                accounts.append(account)
            }
            saveLocked()
        }
        return account
    }

    /// Removing an account takes its secrets with it.
    public func remove(id: String) {
        vault.deleteSecrets(accountId: id)
        lock.withLock {
            accounts.removeAll { $0.id == id }
            saveLocked()
        }
    }

    private func saveLocked() {
        guard let data = try? JSONEncoder().encode(Payload(accounts: accounts)) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
