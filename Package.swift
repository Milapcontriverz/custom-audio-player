// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AudioPlayer",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "AudioPlayer",
            targets: ["audioplayerPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0")
    ],
    targets: [
        .target(
            name: "audioplayerPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/audioplayerPlugin"),
        .testTarget(
            name: "audioplayerPluginTests",
            dependencies: ["audioplayerPlugin"],
            path: "ios/Tests/audioplayerPluginTests")
    ]
)