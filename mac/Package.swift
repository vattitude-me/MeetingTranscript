// swift-tools-version: 5.9
import PackageDescription

// The product is "Meeting Transcript"; the target is one word because Swift
// identifiers cannot hold a space. The Android app made the same split for the
// same reason — users read the name, the toolchain reads the identifier.
let package = Package(
    name: "MeetingTranscript",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "MeetingTranscript", path: "Sources/MeetingTranscript")
    ]
)
