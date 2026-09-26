import Foundation

/// Per-list member order, matching Android's `PlaylistSort`. Title is the
/// stable tiebreak after the chosen field.
public enum StationSort: String, Sendable, CaseIterable, Codable {
    case title
    case artist
    case album
    case recentlyAdded

    public var label: String {
        switch self {
        case .title: "TITLE"
        case .artist: "ARTIST"
        case .album: "ALBUM"
        case .recentlyAdded: "RECENT"
        }
    }

    /// Android's `sortedStations`: case-insensitive field compare, title last.
    public func apply(_ stations: [Station]) -> [Station] {
        switch self {
        case .title:
            stations.sorted { $0.name.lowercased() < $1.name.lowercased() }
        case .artist:
            stations.sorted {
                let left = ($0.artist.lowercased(), $0.name.lowercased())
                let right = ($1.artist.lowercased(), $1.name.lowercased())
                return left < right
            }
        case .album:
            stations.sorted {
                let left = ($0.album.lowercased(), $0.name.lowercased())
                let right = ($1.album.lowercased(), $1.name.lowercased())
                return left < right
            }
        case .recentlyAdded:
            stations.sorted {
                $0.dateAdded == $1.dateAdded
                    ? $0.name.lowercased() < $1.name.lowercased()
                    : $0.dateAdded > $1.dateAdded
            }
        }
    }
}
