// swift-tools-version: 5.9
import PackageDescription

// Everything the app and the share extension have in common, with no user interface: the Link client, finding the car,
// reading what was shared, MapKit search, the repair client, the signing facts. It also builds on macOS, so `swift test`
// holds the logic to its fixtures in about a minute, without a simulator.
let package = Package(
    name: "AMRIKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "AMRIKit", targets: ["AMRIKit"])],
    targets: [
        .target(name: "AMRIKit", path: "Sources/AMRIKit"),
        .testTarget(name: "AMRIKitTests", dependencies: ["AMRIKit"], path: "Tests/AMRIKitTests"),
    ]
)
