import CliampDesign

enum AppTab: String, CaseIterable, Identifiable {
    case stations = "STATIONS"
    case pods = "PODCASTS"
    case library = "LIBRARY"

    var id: String { rawValue }

    var icon: CliampVector {
        switch self {
        case .stations: CliampIcons.stationsTab
        case .pods: CliampIcons.podsTab
        case .library: CliampIcons.libTab
        }
    }
}
