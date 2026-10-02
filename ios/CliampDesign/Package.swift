// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CliampDesign",
    platforms: [
        .iOS(.v18),
        .macOS(.v15),
    ],
    products: [
        .library(name: "CliampDesign", targets: ["CliampDesign"])
    ],
    targets: [
        .target(
            name: "CliampDesign",
            swiftSettings: [.unsafeFlags(["-warnings-as-errors"])]
        ),
        .testTarget(
            name: "CliampDesignTests",
            dependencies: ["CliampDesign"],
            swiftSettings: [.unsafeFlags(["-warnings-as-errors"])]
        ),
    ]
)
