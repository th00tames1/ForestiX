// Phase 7 — project backup / restore as a single `.tcproj` file.
//
// ## Why a custom zip
// Forestix ships a sandbox Core Data SQLite store, cover photos, and
// raw scan blobs. A field pilot is a terrible place to learn you
// can't recover a corrupted database, so we give the cruiser a
// one-button backup (shared via iOS share sheet to iCloud / Files /
// email) and a one-tap restore.
//
// ## On-disk layout inside the `.tcproj` (a stored PKZIP — ZipWriter)
//
//   manifest.json                      # {schemaVersion, projectId, exportedAt, appVersion}
//   core-data/TimberCruising.sqlite    # WAL-checkpointed single-file dump
//   photos/<tree-uuid>.jpg             # every Tree.photoPath that exists
//   scans/<tree-uuid>.ply              # every Tree.rawScanPath that exists
//
// ## Restore behaviour
// Conflict on restore: if the backup's projectId already exists in the
// target store, we **generate a fresh UUID** for the imported copy so
// the cruiser can side-by-side the two. Strategy rationale: during a
// pilot, accidentally overwriting field-collected data is the worst
// possible outcome; duplicating a project is merely annoying.

import Foundation
import CoreData
import Common
import Models

public enum BackupError: Error, CustomStringConvertible {
    case projectNotFound(UUID)
    case missingSqlite
    case archiveCorrupt(String)
    case manifestUnsupported(schemaVersion: Int, expected: Int)
    case ioFailed(String)

    public var description: String {
        switch self {
        case .projectNotFound(let id):
            return "Project \(id.uuidString) not found in the store."
        case .missingSqlite:
            return "Backup archive has no core-data/TimberCruising.sqlite file."
        case .archiveCorrupt(let m):
            return "Backup archive is corrupt or unreadable: \(m)"
        case .manifestUnsupported(let got, let want):
            return "Backup schema version \(got) is not supported (this build expects \(want))."
        case .ioFailed(let m):
            return "Filesystem I/O failed: \(m)"
        }
    }
}

public struct BackupManifest: Codable, Sendable, Equatable {
    public var schemaVersion: Int
    public var projectId: UUID
    public var exportedAt: Date
    public var appVersion: String

    public static let currentSchemaVersion = 1
}

public struct BackupResult: Sendable {
    public let archiveURL: URL
    public let byteSize: Int64
    public let manifest: BackupManifest
}

public struct RestoreResult: Sendable {
    public let importedProjectId: UUID          // may differ from manifest if we renamed
    public let manifest: BackupManifest
    public let treeCount: Int
    public let plotCount: Int
}

public enum BackupArchive {

    // MARK: - Export

    /// Build a `.tcproj` archive for the given project. The caller is
    /// responsible for deciding where to put the resulting file; this
    /// API returns the archive bytes + a suggested filename.
    public static func export(
        projectId: UUID,
        stack: CoreDataStack,
        appVersion: String,
        photoLookup: (UUID) -> URL? = { _ in nil },
        scanLookup:  (UUID) -> URL? = { _ in nil },
        at now: Date = Date()
    ) throws -> (suggestedFilename: String, data: Data, manifest: BackupManifest) {
        // 1. Fetch project + tree list (to know which photos/scans to include).
        let projRepo  = CoreDataProjectRepository(stack: stack)
        let treeRepo  = CoreDataTreeRepository(stack: stack)
        let plotRepo  = CoreDataPlotRepository(stack: stack)

        guard let project = try projRepo.read(id: projectId) else {
            throw BackupError.projectNotFound(projectId)
        }
        let plots = try plotRepo.listByProject(projectId)
        var trees: [Tree] = []
        for plot in plots {
            trees.append(contentsOf: try treeRepo.listByPlot(plot.id, includeDeleted: true))
        }

        // 2. Pull a single-file snapshot of the sqlite.
        let sqliteData = try snapshotSQLite(stack: stack)

        // 3. Build manifest.
        let manifest = BackupManifest(
            schemaVersion: BackupManifest.currentSchemaVersion,
            projectId: projectId,
            exportedAt: now,
            appVersion: appVersion)
        let manifestData = try JSONEncoder.iso8601().encode(manifest)

        // 4. Assemble files.
        var files: [(String, Data)] = []
        files.append(("manifest.json", manifestData))
        files.append(("core-data/TimberCruising.sqlite", sqliteData))

        for t in trees {
            if let src = photoLookup(t.id),
               let bytes = try? Data(contentsOf: src) {
                files.append(("photos/\(t.id.uuidString).jpg", bytes))
            }
            if let src = scanLookup(t.id),
               let bytes = try? Data(contentsOf: src) {
                files.append(("scans/\(t.id.uuidString).ply", bytes))
            }
        }

        let archive = ZipWriter.storedArchive(files: files)
        let stamp = ISO8601DateFormatter().string(from: now)
            .replacingOccurrences(of: ":", with: "-")
        let safeName = sanitize(project.name)
        let filename = "\(safeName)_\(stamp).tcproj"
        return (filename, archive, manifest)
    }

    /// Convenience: export + write to disk atomically. Returns the URL.
    public static func exportToDisk(
        projectId: UUID,
        stack: CoreDataStack,
        appVersion: String,
        directory: URL,
        photoLookup: (UUID) -> URL? = { _ in nil },
        scanLookup:  (UUID) -> URL? = { _ in nil },
        at now: Date = Date()
    ) throws -> BackupResult {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let (name, data, manifest) = try Self.export(
            projectId: projectId, stack: stack,
            appVersion: appVersion,
            photoLookup: photoLookup, scanLookup: scanLookup,
            at: now)
        let url = directory.appendingPathComponent(name)
        do { try data.write(to: url, options: .atomic) }
        catch { throw BackupError.ioFailed(String(describing: error)) }
        return BackupResult(archiveURL: url,
                            byteSize: Int64(data.count),
                            manifest: manifest)
    }

    // MARK: - Import / restore

    /// Reads a `.tcproj` from disk, parses the manifest, and inserts the
    /// contained project (and descendants) into the target store.
    ///
    /// If the backup's projectId already exists, a **fresh UUID** is
    /// assigned to the imported copy and every foreign-key referencing
    /// the old id is rewritten. Photos and scans are written back into
    /// the target sandbox at `<Documents>/Attachments/<tree-uuid>.{jpg,ply}`.
    public static func restore(
        from archiveURL: URL,
        into targetStack: CoreDataStack,
        attachmentsDirectory: URL
    ) throws -> RestoreResult {
        let data: Data
        do { data = try Data(contentsOf: archiveURL) }
        catch { throw BackupError.ioFailed(String(describing: error)) }
        return try restore(
            from: data,
            into: targetStack,
            attachmentsDirectory: attachmentsDirectory)
    }

    /// In-memory variant. Used by tests to round-trip without touching
    /// disk; also used by the `exportToDisk → restore` sequence.
    public static func restore(
        from archiveData: Data,
        into targetStack: CoreDataStack,
        attachmentsDirectory: URL
    ) throws -> RestoreResult {

        let entries: [String: Data]
        do { entries = try ZipReader.readStoredEntries(archiveData) }
        catch { throw BackupError.archiveCorrupt(String(describing: error)) }

        guard let manifestData = entries["manifest.json"] else {
            throw BackupError.archiveCorrupt("missing manifest.json")
        }
        let decoder = JSONDecoder.iso8601()
        let manifest: BackupManifest
        do { manifest = try decoder.decode(BackupManifest.self, from: manifestData) }
        catch { throw BackupError.archiveCorrupt("manifest decode: \(error)") }

        guard manifest.schemaVersion == BackupManifest.currentSchemaVersion else {
            throw BackupError.manifestUnsupported(
                schemaVersion: manifest.schemaVersion,
                expected: BackupManifest.currentSchemaVersion)
        }

        guard let sqliteBlob = entries["core-data/TimberCruising.sqlite"] else {
            throw BackupError.missingSqlite
        }

        // Write the sqlite to a temp location, open a *source* CoreDataStack
        // pointing at it, and copy domain objects via the repos into the
        // target. This avoids fragile sqlite-level merges.
        let fm = FileManager.default
        let tmpDir = fm.temporaryDirectory
            .appendingPathComponent("tcproj-restore-\(UUID().uuidString)",
                                    isDirectory: true)
        try fm.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        let sqliteURL = tmpDir.appendingPathComponent("imported.sqlite")
        try sqliteBlob.write(to: sqliteURL)

        // Open the source stack using the same NSManagedObjectModel as the
        // target stack. Core Data requires the same model to mount a store
        // produced by that model.
        let srcStack: CoreDataStack
        do {
            srcStack = try CoreDataStack(
                configuration: .sqlite(url: sqliteURL),
                model: targetStack.container.managedObjectModel)
        } catch {
            throw BackupError.archiveCorrupt(
                "sqlite open failed: \(error.localizedDescription)")
        }

        // Decide UUID rewrite if there is a collision in the target.
        let targetProjectRepo = CoreDataProjectRepository(stack: targetStack)
        let existing = try targetProjectRepo.list()
        let collides = existing.contains { $0.id == manifest.projectId }
        let idMapping: UUIDMapping = collides
            ? .rewrite(original: manifest.projectId, new: UUID())
            : .keep

        // Copy.
        let srcProjectRepo = CoreDataProjectRepository(stack: srcStack)
        let srcDesignRepo  = CoreDataCruiseDesignRepository(stack: srcStack)
        let srcStratumRepo = CoreDataStratumRepository(stack: srcStack)
        let srcPlannedRepo = CoreDataPlannedPlotRepository(stack: srcStack)
        let srcPlotRepo    = CoreDataPlotRepository(stack: srcStack)
        let srcTreeRepo    = CoreDataTreeRepository(stack: srcStack)
        let srcSpeciesRepo = CoreDataSpeciesConfigRepository(stack: srcStack)
        let srcVolRepo     = CoreDataVolumeEquationRepository(stack: srcStack)
        let srcHDFitRepo   = CoreDataHeightDiameterFitRepository(stack: srcStack)

        guard let srcProject = try srcProjectRepo.read(id: manifest.projectId) else {
            throw BackupError.projectNotFound(manifest.projectId)
        }

        let dstDesignRepo  = CoreDataCruiseDesignRepository(stack: targetStack)
        let dstStratumRepo = CoreDataStratumRepository(stack: targetStack)
        let dstPlannedRepo = CoreDataPlannedPlotRepository(stack: targetStack)
        let dstPlotRepo    = CoreDataPlotRepository(stack: targetStack)
        let dstTreeRepo    = CoreDataTreeRepository(stack: targetStack)
        let dstSpeciesRepo = CoreDataSpeciesConfigRepository(stack: targetStack)
        let dstVolRepo     = CoreDataVolumeEquationRepository(stack: targetStack)
        let dstHDFitRepo   = CoreDataHeightDiameterFitRepository(stack: targetStack)

        let finalProjectId = idMapping.mappedId(manifest.projectId)

        let newProj = Project(
            id: finalProjectId,
            name: srcProject.name,
            description: srcProject.description,
            owner: srcProject.owner,
            createdAt: srcProject.createdAt,
            updatedAt: srcProject.updatedAt,
            units: srcProject.units,
            breastHeightConvention: srcProject.breastHeightConvention,
            slopeCorrection: srcProject.slopeCorrection,
            lidarBiasMm: srcProject.lidarBiasMm,
            depthNoiseMm: srcProject.depthNoiseMm,
            dbhCorrectionAlpha: srcProject.dbhCorrectionAlpha,
            dbhCorrectionBeta: srcProject.dbhCorrectionBeta,
            vioDriftFraction: srcProject.vioDriftFraction)
        _ = try targetProjectRepo.create(newProj)

        // Strata + planned + designs.
        let srcStrata = try srcStratumRepo.listByProject(manifest.projectId)
        var stratumIdMap: [UUID: UUID] = [:]
        for s in srcStrata {
            let newId = idMapping.isRewrite ? UUID() : s.id
            stratumIdMap[s.id] = newId
            var copy = s
            copy = Stratum(id: newId, projectId: finalProjectId,
                           name: s.name, areaAcres: s.areaAcres,
                           polygonGeoJSON: s.polygonGeoJSON)
            _ = try dstStratumRepo.create(copy)
        }

        for d in try srcDesignRepo.forProject(manifest.projectId) {
            let newId = idMapping.isRewrite ? UUID() : d.id
            let copy = CruiseDesign(
                id: newId, projectId: finalProjectId,
                plotType: d.plotType, plotAreaAcres: d.plotAreaAcres,
                baf: d.baf, samplingScheme: d.samplingScheme,
                gridSpacingMeters: d.gridSpacingMeters,
                heightSubsampleRule: d.heightSubsampleRule)
            _ = try dstDesignRepo.create(copy)
        }

        var plannedIdMap: [UUID: UUID] = [:]
        for p in try srcPlannedRepo.listByProject(manifest.projectId) {
            let newId = idMapping.isRewrite ? UUID() : p.id
            plannedIdMap[p.id] = newId
            let copy = PlannedPlot(
                id: newId, projectId: finalProjectId,
                stratumId: p.stratumId.map { stratumIdMap[$0] ?? $0 },
                plotNumber: p.plotNumber,
                plannedLat: p.plannedLat, plannedLon: p.plannedLon,
                visited: p.visited)
            _ = try dstPlannedRepo.create(copy)
        }

        // Plots + trees + attachments.
        var plotIdMap: [UUID: UUID] = [:]
        var plotCount = 0, treeCount = 0
        for p in try srcPlotRepo.listByProject(manifest.projectId) {
            let newPlotId = idMapping.isRewrite ? UUID() : p.id
            plotIdMap[p.id] = newPlotId
            var copy = p
            copy = Plot(
                id: newPlotId,
                projectId: finalProjectId,
                plannedPlotId: p.plannedPlotId.map { plannedIdMap[$0] ?? $0 },
                plotNumber: p.plotNumber,
                centerLat: p.centerLat, centerLon: p.centerLon,
                positionSource: p.positionSource,
                positionTier: p.positionTier,
                gpsNSamples: p.gpsNSamples,
                gpsMedianHAccuracyM: p.gpsMedianHAccuracyM,
                gpsSampleStdXyM: p.gpsSampleStdXyM,
                offsetWalkM: p.offsetWalkM,
                slopeDeg: p.slopeDeg, aspectDeg: p.aspectDeg,
                plotAreaAcres: p.plotAreaAcres,
                startedAt: p.startedAt,
                closedAt: p.closedAt, closedBy: p.closedBy,
                notes: p.notes,
                coverPhotoPath: p.coverPhotoPath,
                panoramaPath: p.panoramaPath)
            _ = try dstPlotRepo.create(copy)
            plotCount += 1

            for t in try srcTreeRepo.listByPlot(p.id, includeDeleted: true) {
                let newTreeId = idMapping.isRewrite ? UUID() : t.id
                let rehomed = Self.rebuildTree(t,
                                               newId: newTreeId,
                                               newPlotId: newPlotId)
                let withAttachments = writePhotoOrScan(
                    tree: rehomed,
                    originalId: t.id, newId: newTreeId,
                    entries: entries,
                    attachmentsDirectory: attachmentsDirectory)
                _ = try dstTreeRepo.create(withAttachments)
                treeCount += 1
            }
        }

        // Species, volume equations, HD fits (global — species/vol are keyed
        // by code/id, not projectId, so only write those that don't already
        // exist in the target).
        let existingSpecies = Set(try dstSpeciesRepo.list().map { $0.code })
        for sp in try srcSpeciesRepo.list() where !existingSpecies.contains(sp.code) {
            _ = try dstSpeciesRepo.create(sp)
        }
        let existingVol = Set(try dstVolRepo.list().map { $0.id })
        for v in try srcVolRepo.list() where !existingVol.contains(v.id) {
            _ = try dstVolRepo.create(v)
        }
        for fit in try srcHDFitRepo.listByProject(manifest.projectId) {
            var copy = fit
            copy = HeightDiameterFit(
                id: UUID(), projectId: finalProjectId,
                speciesCode: fit.speciesCode, modelForm: fit.modelForm,
                coefficients: fit.coefficients,
                nObs: fit.nObs, rmse: fit.rmse,
                updatedAt: fit.updatedAt)
            _ = try dstHDFitRepo.create(copy)
        }

        try? fm.removeItem(at: tmpDir)

        return RestoreResult(
            importedProjectId: finalProjectId,
            manifest: manifest,
            treeCount: treeCount,
            plotCount: plotCount)
    }

    // MARK: - Helpers

    private static func writePhotoOrScan(
        tree t: Tree,
        originalId: UUID, newId: UUID,
        entries: [String: Data],
        attachmentsDirectory dir: URL
    ) -> Tree {
        var updated = t    // t already has the new id/plotId assigned

        let fm = FileManager.default
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        if let bytes = entries["photos/\(originalId.uuidString).jpg"] {
            let dst = dir.appendingPathComponent("\(newId.uuidString).jpg")
            if (try? bytes.write(to: dst, options: .atomic)) != nil {
                updated.photoPath = dst.path
            }
        }
        if let bytes = entries["scans/\(originalId.uuidString).ply"] {
            let dst = dir.appendingPathComponent("\(newId.uuidString).ply")
            if (try? bytes.write(to: dst, options: .atomic)) != nil {
                updated.rawScanPath = dst.path
            }
        }
        return updated
    }

    /// Tree's `id` and `plotId` are `let` properties, so a straight copy
    /// can't reassign them — we rebuild the value through the public init.
    private static func rebuildTree(_ t: Tree,
                                    newId: UUID, newPlotId: UUID) -> Tree {
        Tree(
            id: newId, plotId: newPlotId,
            treeNumber: t.treeNumber, speciesCode: t.speciesCode,
            status: t.status,
            dbhCm: t.dbhCm, dbhMethod: t.dbhMethod,
            dbhSigmaMm: t.dbhSigmaMm, dbhRmseMm: t.dbhRmseMm,
            dbhCoverageDeg: t.dbhCoverageDeg, dbhNInliers: t.dbhNInliers,
            dbhConfidence: t.dbhConfidence, dbhIsIrregular: t.dbhIsIrregular,
            heightM: t.heightM, heightMethod: t.heightMethod,
            heightSource: t.heightSource,
            heightSigmaM: t.heightSigmaM, heightDHM: t.heightDHM,
            heightAlphaTopDeg: t.heightAlphaTopDeg,
            heightAlphaBaseDeg: t.heightAlphaBaseDeg,
            heightConfidence: t.heightConfidence,
            bearingFromCenterDeg: t.bearingFromCenterDeg,
            distanceFromCenterM: t.distanceFromCenterM,
            boundaryCall: t.boundaryCall,
            crownClass: t.crownClass, damageCodes: t.damageCodes,
            isMultistem: t.isMultistem, parentTreeId: t.parentTreeId,
            notes: t.notes,
            photoPath: t.photoPath, rawScanPath: t.rawScanPath,
            createdAt: t.createdAt, updatedAt: t.updatedAt,
            deletedAt: t.deletedAt)
    }

    /// Copy the live sqlite store into a temp file (Core Data checkpoints
    /// the WAL automatically during `replacePersistentStore`) and return
    /// its bytes. Unlike `migratePersistentStore`, this API copies — the
    /// original store stays mounted so the app keeps running.
    private static func snapshotSQLite(stack: CoreDataStack) throws -> Data {
        let coordinator = stack.container.persistentStoreCoordinator
        guard let store = coordinator.persistentStores.first,
              let url = store.url else {
            throw BackupError.missingSqlite
        }

        let fm = FileManager.default
        let tmp = fm.temporaryDirectory
            .appendingPathComponent("tcproj-snap-\(UUID().uuidString).sqlite")
        defer {
            try? fm.removeItem(at: tmp)
            try? fm.removeItem(at: URL(fileURLWithPath: tmp.path + "-wal"))
            try? fm.removeItem(at: URL(fileURLWithPath: tmp.path + "-shm"))
        }

        // Flush any pending writes from the view context.
        if stack.container.viewContext.hasChanges {
            try? stack.container.viewContext.save()
        }

        do {
            try coordinator.replacePersistentStore(
                at: tmp,
                destinationOptions: nil,
                withPersistentStoreFrom: url,
                sourceOptions: [NSSQLitePragmasOption: ["journal_mode": "WAL"]],
                type: .sqlite)
        } catch {
            throw BackupError.ioFailed(
                "persistent store copy failed: \(error.localizedDescription)")
        }
        return try Data(contentsOf: tmp)
    }

    private static func sanitize(_ s: String) -> String {
        let allowed = CharacterSet.alphanumerics
            .union(CharacterSet(charactersIn: "-_"))
        var out = ""
        for scalar in s.unicodeScalars {
            if allowed.contains(scalar) { out.unicodeScalars.append(scalar) }
            else { out.append("-") }
        }
        return out.isEmpty ? "project" : out
    }
}

// MARK: - UUID mapping

private enum UUIDMapping {
    case keep
    case rewrite(original: UUID, new: UUID)

    var isRewrite: Bool {
        if case .rewrite = self { return true }
        return false
    }

    func mappedId(_ id: UUID) -> UUID {
        switch self {
        case .keep: return id
        case .rewrite(let orig, let new):
            return id == orig ? new : id
        }
    }
}

// MARK: - JSON / ZIP helpers

private extension JSONEncoder {
    static func iso8601() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.sortedKeys]
        return e
    }
}

private extension JSONDecoder {
    static func iso8601() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }
}

/// Minimal mirror of ShapefileExporterTests.ZipReader — we can't import
/// that from a test file, so we keep a Persistence-local copy here.
/// Stored (method 0) ZIPs only; sufficient for `.tcproj` which we also
/// write with ZipWriter.storedArchive.
enum ZipReaderError: Error { case malformed(String) }

enum ZipReader {
    static func readStoredEntries(_ data: Data) throws -> [String: Data] {
        var out: [String: Data] = [:]
        var i = 0
        while i + 30 <= data.count {
            let sig = data.readLE32UInt(at: i)
            if sig == 0x04034b50 {
                let method = Int(data.readLE16UInt(at: i + 8))
                let compSize = Int(data.readLE32UInt(at: i + 18))
                let nameLen = Int(data.readLE16UInt(at: i + 26))
                let extraLen = Int(data.readLE16UInt(at: i + 28))
                let nameStart = i + 30
                let dataStart = nameStart + nameLen + extraLen
                guard method == 0 else {
                    throw ZipReaderError.malformed("only stored method supported")
                }
                let name = String(data: data[nameStart..<nameStart + nameLen],
                                  encoding: .utf8) ?? ""
                let payload = data[dataStart..<dataStart + compSize]
                out[name] = Data(payload)
                i = dataStart + compSize
                continue
            }
            break
        }
        return out
    }
}

private extension Data {
    func readLE32UInt(at i: Int) -> UInt32 {
        UInt32(self[i]) |
        (UInt32(self[i + 1]) << 8) |
        (UInt32(self[i + 2]) << 16) |
        (UInt32(self[i + 3]) << 24)
    }
    func readLE16UInt(at i: Int) -> UInt16 {
        UInt16(self[i]) | (UInt16(self[i + 1]) << 8)
    }
}
