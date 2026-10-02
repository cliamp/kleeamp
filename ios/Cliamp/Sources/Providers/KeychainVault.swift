import CliampCore
import Foundation
import os
import Security

/// Provider secrets in the Keychain: one generic-password item per account
/// holding a JSON dictionary of its secret fields. Nothing else in the app
/// stores a password, token or private key.
final class KeychainVault: SecretVault, @unchecked Sendable {
    private let service: String

    init(service: String = "stream.cliamp.mobile.providers") {
        self.service = service
    }

    func setSecret(_ value: String, accountId: String, field: String) {
        var secrets = secrets(accountId: accountId)
        secrets[field] = value
        write(secrets, accountId: accountId)
    }

    func secret(accountId: String, field: String) -> String? {
        secrets(accountId: accountId)[field]
    }

    func deleteSecrets(accountId: String) {
        SecItemDelete(baseQuery(accountId: accountId) as CFDictionary)
    }

    // MARK: storage

    private func baseQuery(accountId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountId,
        ]
    }

    private func secrets(accountId: String) -> [String: String] {
        var query = baseQuery(accountId: accountId)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else {
            if status != errSecItemNotFound {
                Self.log.error("keychain read failed status=\(status, privacy: .public)")
            }
            return [:]
        }
        guard let data = result as? Data,
              let decoded = try? JSONDecoder().decode([String: String].self, from: data)
        else { return [:] }
        return decoded
    }

    private static let log = Logger(subsystem: "stream.cliamp.mobile", category: "keychain")

    private func write(_ secrets: [String: String], accountId: String) {
        guard let data = try? JSONEncoder().encode(secrets) else { return }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // Available after the first unlock, like the rest of the app's
            // playback state; never synced to iCloud.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var add = baseQuery(accountId: accountId)
        add.merge(attributes) { _, new in new }
        var status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            status = SecItemUpdate(baseQuery(accountId: accountId) as CFDictionary, attributes as CFDictionary)
        }
        if status != errSecSuccess {
            Self.log.error("keychain write failed status=\(status, privacy: .public)")
        }
    }
}
