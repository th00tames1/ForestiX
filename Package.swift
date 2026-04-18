// swift-tools-version: 5.9
// TimberCruisingApp — Phase 0 + Phase 1
// Spec: timber_cruising_app_design.md §8 (Module & File Layout), §9.2 Phase 0 & Phase 1
//
// Phase 0: Common, Models, Persistence, InventoryEngine.
// Phase 1: adds Geo, Basemap, Export, UI.
//
// Phase 2+ directories (AR, Positioning, Sensors, and the Phase 2+ screens/
// viewmodels inside Screens/ and ViewModels/) are present as stub files under
// TimberCruisingApp/. Stubs are 2-line comments and compile cleanly into the
// UI target, which is why we do NOT exclude them explicitly — they just carry
// no code until their phase begins.

import PackageDescription

let package = Package(
    name: "TimberCruisingApp",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)   // enables `swift test` on developer macOS hosts
    ],
    products: [
        .library(name: "Common", targets: ["Common"]),
        .library(name: "Models", targets: ["Models"]),
        .library(name: "Persistence", targets: ["Persistence"]),
        .library(name: "InventoryEngine", targets: ["InventoryEngine"]),
        .library(name: "Geo", targets: ["Geo"]),
        .library(name: "Basemap", targets: ["Basemap"]),
        .library(name: "Export", targets: ["Export"]),
        .library(name: "Sensors", targets: ["Sensors"]),
        .library(name: "UI", targets: ["UI"])
    ],
    dependencies: [
        .package(
            url: "https://github.com/pointfreeco/swift-snapshot-testing",
            from: "1.17.0"
        )
    ],
    targets: [
        // MARK: - Phase 0

        .target(
            name: "Common",
            path: "TimberCruisingApp/Common"
        ),
        .target(
            name: "Models",
            dependencies: ["Common"],
            path: "TimberCruisingApp/Models",
            resources: [
                .process("Resources")
            ]
        ),
        .target(
            name: "Persistence",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/Persistence",
            resources: [
                .process("TimberCruising.xcdatamodeld")
            ]
        ),
        .target(
            name: "InventoryEngine",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/InventoryEngine"
        ),

        // MARK: - Phase 1

        .target(
            name: "Geo",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/Geo"
        ),
        .target(
            name: "Basemap",
            dependencies: ["Common", "Geo"],
            path: "TimberCruisingApp/Basemap"
        ),
        .target(
            name: "Export",
            dependencies: ["Common", "Models", "Geo"],
            path: "TimberCruisingApp/Export"
        ),
        // MARK: - Phase 2

        .target(
            name: "Sensors",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/Sensors"
        ),

        .target(
            name: "UI",
            dependencies: [
                "Common", "Models", "Persistence",
                "InventoryEngine", "Geo", "Basemap", "Export", "Sensors"
            ],
            path: "TimberCruisingApp",
            exclude: [
                "Common", "Models", "Persistence", "InventoryEngine",
                "Geo", "Basemap", "Export", "Sensors",
                "AR", "Positioning"
            ],
            sources: ["App", "Screens", "ViewModels"]
        ),

        // MARK: - Tests

        .testTarget(
            name: "InventoryEngineTests",
            dependencies: ["InventoryEngine", "Models", "Common"],
            path: "Tests/InventoryEngineTests"
        ),
        .testTarget(
            name: "GeoTests",
            dependencies: ["Geo", "Models", "Common"],
            path: "Tests/GeoTests"
        ),
        .testTarget(
            name: "BasemapTests",
            dependencies: ["Basemap", "Geo", "Common"],
            path: "Tests/BasemapTests"
        ),
        .testTarget(
            name: "ExportTests",
            dependencies: ["Export", "Models", "Geo", "Common"],
            path: "Tests/ExportTests"
        ),
        .testTarget(
            name: "SensorsTests",
            dependencies: ["Sensors", "Models", "Common"],
            path: "Tests/SensorsTests"
        ),
        .testTarget(
            name: "UISnapshotTests",
            dependencies: [
                "UI",
                .product(name: "SnapshotTesting", package: "swift-snapshot-testing")
            ],
            path: "Tests/UISnapshotTests"
        )
    ]
)
