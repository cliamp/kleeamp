// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CliampCore",
    platforms: [
        .iOS(.v18),
        .macOS(.v15),
    ],
    products: [
        .library(name: "CliampCore", targets: ["CliampCore"])
    ],
    dependencies: [
        // SSH/SFTP transport for the SFTP provider (PRV-11/12): NIOSSH under
        // the hood, with the SFTP client, OpenSSH key parsing and host-key
        // validation the provider needs. MIT licensed.
        .package(url: "https://github.com/orlandos-nl/Citadel.git", from: "0.12.1")
    ],
    targets: [
        .target(
            name: "CliampCore",
            dependencies: [
                .product(name: "Citadel", package: "Citadel")
            ],
            swiftSettings: [.unsafeFlags(["-warnings-as-errors"])]
        ),
        .testTarget(
            name: "CliampCoreTests",
            dependencies: ["CliampCore"],
            swiftSettings: [.unsafeFlags(["-warnings-as-errors"])]
        ),
    ]
)
