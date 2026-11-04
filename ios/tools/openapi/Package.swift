// swift-tools-version:5.10
// Code generation tools for the iOS app (see ios/tools/generate-api.sh). Not part of the app build.
import PackageDescription

let package = Package(
    name: "openapi-tools",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-openapi-generator", from: "1.10.0"),
        .package(url: "https://github.com/jpsim/Yams", from: "6.0.0"),
    ],
    targets: [
        .executableTarget(name: "specprep", dependencies: ["Yams"]),
    ]
)
