import Foundation

/// cliamp's own stations. The twelve below are the offline seed - the same
/// list `cliamp` ships in main.go - and they are replaced at launch by
/// whatever `streams.m3u` currently advertises, so a new channel appears
/// without a client release.
public enum CliampRadio {
    public static let base = "https://radio.cliamp.stream"
    public static let playlistURL = "\(base)/streams.m3u"

    private static func seed(_ slug: String, _ name: String) -> Station {
        Station(
            id: "cliamp:\(slug)",
            name: name,
            url: "\(base)/\(slug)/stream",
            source: .cliamp,
            slug: slug,
            codec: "MP3"
        )
    }

    public static let builtin: [Station] = [
        seed("lofi", "Lofi"),
        seed("synthwave", "Synthwave"),
        seed("edm", "EDM"),
        seed("omarchy", "Omarchy"),
        seed("ncs", "NCS"),
        seed("ncs-house", "NCS House"),
        seed("ncs-dubstep", "NCS Dubstep"),
        seed("ncs-dnb", "NCS Drum & Bass"),
        seed("ncs-trap", "NCS Trap"),
        seed("ncs-phonk", "NCS Phonk"),
        seed("ncs-pop", "NCS Pop"),
        seed("ncs-chill", "NCS Chill"),
    ]

    /// Parse `#EXTINF:-1,Name` / url pairs out of the live m3u.
    public static func parseM3u(_ body: String) -> [Station] {
        var out: [Station] = []
        var pending: String?
        for raw in body.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line == "#EXTM3U" { continue }
            if line.hasPrefix("#EXTINF") {
                let name = line.split(separator: ",", maxSplits: 1, omittingEmptySubsequences: false)
                    .last.map(String.init) ?? ""
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                pending = trimmed.isEmpty ? nil : trimmed
            } else if line.hasPrefix("#") {
                continue
            } else {
                var trimmed = line
                while trimmed.hasSuffix("/") { trimmed.removeLast() }
                let beforeStream = trimmed.range(of: "/stream", options: .backwards)
                    .map { String(trimmed[..<$0.lowerBound]) } ?? trimmed
                let afterSlash = beforeStream.split(separator: "/").last.map(String.init) ?? ""
                let slug = afterSlash.isEmpty
                    ? (line.split(separator: "/").last.map(String.init) ?? "")
                    : afterSlash
                let identity = slug.isEmpty ? line : slug
                out.append(
                    Station(
                        id: "cliamp:\(identity)",
                        name: pending ?? identity.replacingOccurrences(of: "-", with: " "),
                        url: line,
                        source: .cliamp,
                        slug: slug,
                        codec: "MP3"
                    )
                )
                pending = nil
            }
        }
        return out
    }

    public static func fetchStations() async -> [Station] {
        let parsed = (try? await HTTP.text(playlistURL)).map(parseM3u) ?? []
        return parsed.isEmpty ? builtin : parsed
    }
}

public enum HTTP {
    public static func text(_ url: String) async throws -> String {
        guard let url = URL(string: url) else { throw URLError(.badURL) }
        let (data, _) = try await URLSession.shared.data(from: url)
        return String(decoding: data, as: UTF8.self)
    }
}
