// swift-tools-version: 5.9
// TimberCruisingApp — Phase 0 Foundations
// Spec: timber_cruising_app_design.md §8 (Module & File Layout), §9.2 Phase 0
//
// Phase 0 modules only (Common, Models, Persistence, InventoryEngine).
// Phase 1+ module directories (Screens, ViewModels, Sensors, Positioning, AR,
// Geo, Export, Basemap, App) are present as stub files under TimberCruisingApp/
// but are not compiled by SPM until their phase begins.

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
        .library(name: "InventoryEngine", targets: ["InventoryEngine"])
    ],
    targets: [
        .target(
            name: "Common",
            path: "TimberCruisingApp/Common"
        ),
        .target(
            name: "Models",
            dependencies: ["Common"],
            path: "TimberCruisingApp/Models"
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
        .testTarget(
            name: "InventoryEngineTests",
            dependencies: ["InventoryEngine", "Models", "Common"],
            path: "Tests/InventoryEngineTests"
        )
    ]
)
