import Foundation
import Common
import Models
import Sensors
import Persistence

/// UI adapter only. Original application snapshots accompany the common index;
/// the exporter never rewrites a result or attempts a legacy heuristic join.
@MainActor
enum FieldStudyExportApplication {
    static func build(environment: AppEnvironment) async throws -> FieldStudyExportArtifact {
        await PendingWorkRegistry.rawCapture.waitUntilIdle()
        await environment.fieldStudy.awaitPendingWrites()
        let archive = environment.fieldStudy.archive
        let entries = environment.quickMeasureHistory.entries,plots = environment.quickMeasureHistory.plots
        let journal = environment.quickMeasureHistory.fieldStudyJournalURL
        let log = ResearchLog.shared.fileURL
        let photos = FileManager.default.urls(for: .documentDirectory,in: .userDomainMask)[0].appendingPathComponent("measure-photos",isDirectory: true)
        let projects = environment.projectRepository,plotRepo = environment.plotRepository,trees = environment.treeRepository
        let species = environment.speciesCatalog.records
        return try await Task.detached(priority: .utility) {
            var inventory: [Tree] = []
            for project in try projects.list() {
                for plot in try plotRepo.listByProject(project.id) { inventory += try trees.listCurrentByPlot(plot.id,includeDeleted: true) }
            }
            var results: [FieldExportApplicationResult] = entries.compactMap { e in
                guard let kind = FieldStudyKind(rawValue: e.kind.rawValue) else { return nil }
                return .init(id: e.id.uuidString,store: "quick_history",kind: kind,value: e.value,measurementId: e.measurementId)
            }
            for tree in inventory {
                results.append(.init(id: tree.id.uuidString,store: "cruise",kind: .dbh,value: Double(tree.dbhCm),measurementId: tree.dbhMeasurementId,deleted: tree.deletedAt != nil))
                if let h = tree.heightM { results.append(.init(id: tree.id.uuidString,store: "cruise",kind: .height,value: Double(h),measurementId: tree.heightMeasurementId,deleted: tree.deletedAt != nil)) }
            }
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            let payloads: [(String,Data)] = [("quick-history-ios.json",try encoder.encode(entries)),("quick-plots-ios.json",try encoder.encode(plots)),("inventory-trees-ios.json",try encoder.encode(inventory)),("species-catalog.json",try encoder.encode(species))]
            var files: [(String,URL)] = []
            for (name,url) in [("quick-history-original-ios.jsonl",journal),("research-log-original.csv",Optional(log))] {
                if let url,FileManager.default.fileExists(atPath: url.path) { files.append((name,url)) }
            }
            return try FieldStudyExport.build(archive: archive,rawRoot: RawCaptureStore.rootDirectory,results: results,
                applicationPayloads: payloads,outputDirectory: FileManager.default.temporaryDirectory.appendingPathComponent("FieldStudyExports",isDirectory: true),applicationFiles: files,
                applicationDirectories: [("measure-photos",photos)])
        }.value
    }
}
