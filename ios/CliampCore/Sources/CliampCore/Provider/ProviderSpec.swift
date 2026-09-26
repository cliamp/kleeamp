import Foundation

/// A declarative description of what a provider needs before it can be used.
/// Ported from Android's `ProviderSpec`: the wizard renders whatever the spec
/// lists, so adding a provider is a data change plus a client.
public struct ProviderSpec: Sendable {
    public let key: String
    public let name: String
    /// Pre-form blurb: what it is and where the docs are.
    public let intro: [String]
    public let fields: [FieldSpec]
    public let picker: PickerSpec?
    /// Probes the live server. Nothing is saved until this succeeds.
    public let validate: @Sendable ([String: String]) async throws -> ProviderIdentity
    /// Cross-field rules, run before `validate`; returns an error or nil.
    public let extraValidate: (@Sendable ([String: String]) -> String?)?
    /// The line under a configured account's name in the providers list.
    public let summary: @Sendable ([String: String]) -> String

    public init(
        key: String,
        name: String,
        intro: [String],
        fields: [FieldSpec],
        picker: PickerSpec? = nil,
        validate: @escaping @Sendable ([String: String]) async throws -> ProviderIdentity,
        extraValidate: (@Sendable ([String: String]) -> String?)? = nil,
        summary: @escaping @Sendable ([String: String]) -> String = { $0["url"] ?? "" }
    ) {
        self.key = key
        self.name = name
        self.intro = intro
        self.fields = fields
        self.picker = picker
        self.validate = validate
        self.extraValidate = extraValidate
        self.summary = summary
    }

    /// Fields currently applicable, honouring every `FieldSpec.onlyIf`.
    public func visibleFields(_ values: [String: String]) -> [FieldSpec] {
        fields.filter { $0.onlyIf?(values) ?? true }
    }

    public func missingRequired(_ values: [String: String]) -> [FieldSpec] {
        visibleFields(values).filter { field in
            field.required && (values[field.key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        }
    }
}

/// What a successful probe learned: the account's stable name, the server
/// detail line, and any fields the probe filled in (the SSH fingerprint and
/// the music folders it discovered).
public struct ProviderIdentity: Sendable, Equatable {
    public var name: String
    public var detail: String
    public var values: [String: String]

    public init(name: String, detail: String = "", values: [String: String] = [:]) {
        self.name = name
        self.detail = detail
        self.values = values
    }
}

public struct FieldSpec: Sendable, Identifiable {
    public enum Keyboard: String, Sendable {
        case text
        case url
        case number
    }

    public let key: String
    public let label: String
    public let help: String
    public let required: Bool
    public let secret: Bool
    public let `default`: String
    public let keyboard: Keyboard
    /// Rows for fields whose value is not one line (folders, private key).
    public let lines: Int
    /// Hides the field unless the predicate holds — Jellyfin's either/or auth.
    public let onlyIf: (@Sendable ([String: String]) -> Bool)?

    public var id: String { key }

    public init(
        key: String,
        label: String,
        help: String = "",
        required: Bool = true,
        secret: Bool = false,
        default defaultValue: String = "",
        keyboard: Keyboard = .text,
        lines: Int = 1,
        onlyIf: (@Sendable ([String: String]) -> Bool)? = nil
    ) {
        self.key = key
        self.label = label
        self.help = help
        self.required = required
        self.secret = secret
        self.default = defaultValue
        self.keyboard = keyboard
        self.lines = lines
        self.onlyIf = onlyIf
    }
}

/// A fixed set of choices, rendered as chips rather than a text field.
public struct PickerSpec: Sendable {
    public let key: String
    public let label: String
    public let options: [PickerOption]
    public let `default`: String

    public init(key: String, label: String, options: [PickerOption], default defaultValue: String) {
        self.key = key
        self.label = label
        self.options = options
        self.default = defaultValue
    }
}

public struct PickerOption: Sendable, Hashable, Identifiable {
    public let value: String
    public let label: String

    public var id: String { value }

    public init(value: String, label: String) {
        self.value = value
        self.label = label
    }
}

/// A configured provider: which spec, plus the values the user supplied.
public struct ProviderAccount: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var providerKey: String
    public var label: String
    public var values: [String: String]

    public init(id: String, providerKey: String, label: String, values: [String: String]) {
        self.id = id
        self.providerKey = providerKey
        self.label = label
        self.values = values
    }

    public var url: String { values["url"] ?? "" }

    /// The SSH view of this account, or nil for other provider kinds.
    public var ssh: SshConfig? {
        providerKey == "ssh" ? sshConfig(values) : nil
    }
}

/// Every provider the app can add. Adding one is a data change here plus a
/// client; the wizard and the list screen need no edits. Only providers with
/// a working client are listed — the rest land as their clients do.
public enum ProviderCatalog {
    public static let ssh = ProviderSpec(
        key: "ssh",
        name: "SSH / SFTP",
        intro: [
            "any box you can ssh into: a nas, a pi, a seedbox, a desktop.",
            "tailscale ssh needs no credentials - the tailnet is the login.",
            "name the music folders; tracks stream over sftp, nothing is downloaded.",
        ],
        fields: [
            FieldSpec(
                key: "host",
                label: "Host",
                help: "nas.local, 10.0.0.4, or a tailscale name",
                keyboard: .url
            ),
            FieldSpec(
                key: "port",
                label: "Port",
                help: "22",
                required: false,
                default: "22",
                keyboard: .number
            ),
            FieldSpec(key: "user", label: "Username"),
            FieldSpec(
                key: "password",
                label: "Password",
                secret: true,
                onlyIf: { ($0["_auth"] ?? "password") == "password" }
            ),
            FieldSpec(
                key: "key",
                label: "Private Key",
                help: "paste the whole -----BEGIN ... PRIVATE KEY----- block",
                secret: true,
                lines: 4,
                onlyIf: { $0["_auth"] == "key" }
            ),
            FieldSpec(
                key: "passphrase",
                label: "Key Passphrase",
                required: false,
                secret: true,
                onlyIf: { $0["_auth"] == "key" }
            ),
            FieldSpec(
                key: "folders",
                label: "Music Folders",
                help: "one per line - leave empty and the probe goes looking",
                required: false,
                lines: 3
            ),
        ],
        picker: PickerSpec(
            key: "_auth",
            label: "sign in with",
            options: [
                PickerOption(value: "password", label: "Password"),
                PickerOption(value: "key", label: "Private Key"),
                PickerOption(value: "none", label: "Tailscale"),
            ],
            default: "password"
        ),
        validate: { values in
            try await SshProbe.probe(values)
        },
        extraValidate: { values in
            sshExtraValidate(values)
        },
        summary: { values in
            let config = sshConfig(values)
            let folders = config.folders.count
            return [
                config.endpoint,
                folders == 0 ? "" : (folders == 1 ? config.folders[0] : "\(folders) folders"),
            ]
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
        }
    )

    /// Only providers with a working client: everything else appears once its
    /// client does.
    public static let all: [ProviderSpec] = [ssh]

    public static func byKey(_ key: String) -> ProviderSpec? {
        all.first { $0.key == key }
    }
}
