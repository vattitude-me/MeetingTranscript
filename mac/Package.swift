// swift-tools-version: 5.9
import Foundation
import PackageDescription

// The product is "Meeting Transcript"; the targets are one word because Swift
// identifiers cannot hold a space. The Android app made the same split for the
// same reason — users read the name, the toolchain reads the identifier.

// sherpa-onnx is linked statically from vendor/, which scripts/setup.sh fills
// with the same release the Android app uses. Absolute paths, because SwiftPM
// passes linker flags through verbatim and does not promise which directory
// the linker runs in.
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let sherpaVersion = "1.13.8"
let vendorLib = "\(root)/vendor/sherpa-onnx-v\(sherpaVersion)-osx-arm64-static-no-tts-lib/lib"

let sherpaLibs = [
    "sherpa-onnx-c-api", "sherpa-onnx-core", "sherpa-onnx-fstfar", "sherpa-onnx-fst",
    "sherpa-onnx-kaldifst-core", "kaldi-decoder-core", "kaldi-native-fbank-core",
    "kissfft-float", "ssentencepiece_core", "onnxruntime",
]

let package = Package(
    name: "MeetingTranscript",
    platforms: [.macOS(.v14)],
    targets: [
        .systemLibrary(name: "CSherpaOnnx", path: "Sources/CSherpaOnnx"),
        .target(
            name: "MeetingCore",
            dependencies: ["CSherpaOnnx"],
            path: "Sources/MeetingCore",
            linkerSettings: [
                .unsafeFlags(["-L\(vendorLib)"] + sherpaLibs.map { "-l\($0)" }),
                .linkedLibrary("c++"),
                .linkedLibrary("sqlite3"),
                .linkedFramework("Foundation"),
                .linkedFramework("Accelerate"),
            ]
        ),
        .executableTarget(
            name: "MeetingTranscript",
            dependencies: ["MeetingCore"],
            path: "Sources/App",
            linkerSettings: [
                // An Info.plist inside the binary, so that a bare `swift run`
                // still carries the microphone usage string macOS insists on.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist", "-Xlinker", "\(root)/Resources/Info.plist",
                ]),
            ]
        ),
        // The first proof that system audio can be taken at all. Kept runnable
        // as a diagnostic: `swift run CaptureSpike 10` writes raw segments.
        .executableTarget(
            name: "CaptureSpike",
            path: "Sources/MeetingTranscript"
        ),
        // Unit checks: `swift run CoreChecks`. An executable rather than a
        // test target because `swift test` needs Xcode's XCTest.
        .executableTarget(
            name: "CoreChecks",
            dependencies: ["MeetingCore"],
            path: "Tests/MeetingCoreTests",
            swiftSettings: [.unsafeFlags(["-enable-testing"])]
        ),
    ]
)
