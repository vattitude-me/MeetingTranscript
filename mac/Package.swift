// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ScribeCapture",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "ScribeCapture", path: "Sources/ScribeCapture")
    ]
)
