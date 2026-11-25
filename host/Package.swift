// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "Depth3DHost",
    defaultLocalization: "zh-Hans",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "Depth3DHostCore",
            targets: ["Depth3DHostCore"]
        ),
        .executable(
            name: "Depth3DHost",
            targets: ["Depth3DHost"]
        ),
        .executable(
            name: "depth3d-child-guard",
            targets: ["Depth3DChildGuard"]
        )
    ],
    targets: [
        .target(
            name: "Depth3DHostCore",
            path: "Sources/Depth3DHostCore",
            resources: [
                .process("Resources/Localizable.xcstrings")
            ],
            linkerSettings: [
                .linkedFramework("CoreAudio"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("ApplicationServices"),
                .linkedFramework("Security"),
                .linkedFramework("Network"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("ServiceManagement"),
                .linkedFramework("ScreenCaptureKit")
            ]
        ),
        .executableTarget(
            name: "Depth3DChildGuard",
            path: "Sources/Depth3DChildGuard"
        ),
        .executableTarget(
            name: "Depth3DHost",
            dependencies: ["Depth3DHostCore"],
            path: "Sources/Depth3DHost",
            exclude: [
                "Resources/Info.plist",
                "Resources/Assets.xcassets"
            ],
            resources: [
                .copy("Resources/THIRD_PARTY_NOTICES.md")
            ]
        ),
        .testTarget(
            name: "Depth3DHostTests",
            dependencies: ["Depth3DHostCore"],
            path: "Tests/Depth3DHostTests"
        )
    ]
)
