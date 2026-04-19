// SPM on macOS `.process()` doesn't always compile `.xcdatamodeld → .momd`,
// so integration tests invoke `momc` at setUp to produce a `.mom` file in a
// temp dir, then hand the resulting NSManagedObjectModel to CoreDataStack.

import Foundation
import CoreData
import XCTest
@testable import Persistence

enum TestModelLoader {

    /// Compile the repo's xcdatamodeld with momc and return the model.
    static func loadTimberCruisingModel() throws -> NSManagedObjectModel {
        let modelSrcPath = try locateModelSource()
        let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("TimberCruisingModel-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: tmpDir, withIntermediateDirectories: true)
        let outURL = tmpDir.appendingPathComponent("TimberCruising.mom")

        let momc = URL(fileURLWithPath: "/usr/bin/xcrun")
        let proc = Process()
        proc.executableURL = momc
        proc.arguments = ["momc", modelSrcPath.path, outURL.path]
        let errPipe = Pipe()
        proc.standardError = errPipe
        try proc.run()
        proc.waitUntilExit()
        guard proc.terminationStatus == 0 else {
            let err = String(data: errPipe.fileHandleForReading.readDataToEndOfFile(),
                             encoding: .utf8) ?? "<no output>"
            throw NSError(
                domain: "TestModelLoader", code: Int(proc.terminationStatus),
                userInfo: [NSLocalizedDescriptionKey: "momc failed: \(err)"])
        }
        guard let model = NSManagedObjectModel(contentsOf: outURL) else {
            throw NSError(
                domain: "TestModelLoader", code: -1,
                userInfo: [NSLocalizedDescriptionKey:
                            "NSManagedObjectModel(contentsOf: \(outURL)) returned nil"])
        }
        return model
    }

    private static func locateModelSource() throws -> URL {
        let thisFile = URL(fileURLWithPath: #file)
        // Tests/PersistenceIntegrationTests/TestModelLoader.swift → repo root two dirs up.
        let repoRoot = thisFile
            .deletingLastPathComponent()   // PersistenceIntegrationTests
            .deletingLastPathComponent()   // Tests
            .deletingLastPathComponent()   // repo root
        let candidate = repoRoot
            .appendingPathComponent("TimberCruisingApp/Persistence/TimberCruising.xcdatamodeld")
        guard FileManager.default.fileExists(atPath: candidate.path) else {
            throw NSError(
                domain: "TestModelLoader", code: -2,
                userInfo: [NSLocalizedDescriptionKey:
                            "xcdatamodeld not found at \(candidate.path)"])
        }
        return candidate
    }
}
