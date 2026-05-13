// Phase 7 — backup + restore view model.
//
// Wraps `Persistence.BackupArchive` so the SettingsScreen can offer a
// per-project backup ("Back up all projects") and a file-picker driven
// restore. `.tcproj` files are written to Documents/Backups/ and then
// handed to the iOS share sheet.

import Foundation
import Models
import Persistence
import Common

@MainActor
public final class BackupViewModel: ObservableObject {

    public struct BackupArtefact: Identifiable {
        public var id: URL { url }
        public let url: URL
        public let byteSize: Int64
        public let manifest: BackupManifest
    }

    @Published public private(set) var recentBackups: [BackupArtefact] = []
    @Published public var errorMessage: String?
    @Published public var shareURL: URL?
    @Published public var restoreSummary: String?
    @Published public var isBackingUp: Bool = false

    private var env: AppEnvironment?

    public init() {}
    public func configure(with environment: AppEnvironment) { self.env = environment }

    // MARK: - Backup

    public func backupAllProjects() {
        guard let env = env, let stack = env.coreDataStack,
              !isBackingUp else { return }
        isBackingUp = true
        defer { isBackingUp = false }

        do {
            let projects = try env.projectRepository.list()
            guard !projects.isEmpty else {
                errorMessage = "No projects to back up. Create a project first, then try again."
                return
            }
            let dir = try FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true)
                .appendingPathComponent("Backups", isDirectory: true)
            for p in projects {
                let result = try BackupArchive.exportToDisk(
                    projectId: p.id,
                    stack: stack,
                    appVersion: Self.appVersionString(),
                    directory: dir)
                ForestixLogger.log(.backupCreated(
                    projectId: p.id, bytes: result.byteSize))
                recentBackups.insert(
                    BackupArtefact(url: result.archiveURL,
                                   byteSize: result.byteSize,
                                   manifest: result.manifest),
                    at: 0)
            }
            shareURL = dir
        } catch {
            errorMessage = "Backup failed: \(error.localizedDescription). Free up some storage, then try again."
        }
    }

    public func backup(project: Project) {
        guard let env = env, let stack = env.coreDataStack,
              !isBackingUp else { return }
        isBackingUp = true
        defer { isBackingUp = false }
        do {
            let dir = try FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true)
                .appendingPathComponent("Backups", isDirectory: true)
            let result = try BackupArchive.exportToDisk(
                projectId: project.id,
                stack: stack,
                appVersion: Self.appVersionString(),
                directory: dir)
            ForestixLogger.log(.backupCreated(
                projectId: project.id, bytes: result.byteSize))
            recentBackups.insert(
                BackupArtefact(url: result.archiveURL,
                               byteSize: result.byteSize,
                               manifest: result.manifest),
                at: 0)
            shareURL = result.archiveURL
        } catch {
            errorMessage = "Backup failed: \(error.localizedDescription). Free up some storage, then try again."
        }
    }

    // MARK: - Restore

    public func restore(from url: URL) {
        guard let env = env, let stack = env.coreDataStack else { return }
        do {
            let dir = try FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true)
                .appendingPathComponent("Attachments", isDirectory: true)
            let result = try BackupArchive.restore(
                from: url,
                into: stack,
                attachmentsDirectory: dir)
            ForestixLogger.log(.backupRestored(
                projectId: result.importedProjectId,
                fromPath: url.path))
            restoreSummary = "Restored project \(result.importedProjectId.uuidString.prefix(8))… — \(result.plotCount) plots, \(result.treeCount) trees."
        } catch {
            errorMessage = "Restore failed: \(error.localizedDescription). Check the file is a valid .tcproj, then try again."
        }
    }

    // MARK: - Helpers

    private static func appVersionString() -> String {
        #if canImport(UIKit)
        if let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String {
            return v
        }
        #endif
        return "phase7-dev"
    }
}
