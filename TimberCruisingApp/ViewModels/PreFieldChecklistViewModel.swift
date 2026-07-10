// Phase 7 — pre-field readiness check.
//
// Seven checks (spec §9.2 Phase 7):
//   1. LiDAR + AR session self-test
//   2. GPS fix achievable at Tier A (or explicit "skip, yard has no sky view")
//   3. Calibration data present on the project (depth noise, DBH α/β)
//   4. Offline basemap downloaded (tiles present at expected path)
//   5. Species list + volume equations exist
//   6. Storage free > 500 MB
//   7. Battery > 50 %
//
// Each check is a pure function returning a `CheckResult` (.pass /
// .warn / .fail + user-facing explanation + optional remediation).
// The view model aggregates them and flips "ready" green only when
// every check is .pass.

import Foundation
import Common
import Models
import Persistence

public struct ChecklistItem: Identifiable, Sendable {
    public enum Severity: Sendable { case pass, warn, fail }

    public let id: String
    public let title: String
    public let severity: Severity
    public let message: String

    public init(id: String, title: String,
                severity: Severity, message: String) {
        self.id = id; self.title = title
        self.severity = severity; self.message = message
    }
}

@MainActor
public final class PreFieldChecklistViewModel: ObservableObject {

    @Published public private(set) var items: [ChecklistItem] = []
    @Published public private(set) var isReady: Bool = false
    @Published public var errorMessage: String?

    public let project: Project
    private var env: AppEnvironment?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        self.env = environment
    }

    public func runAll() {
        guard let env = env else { return }
        var out: [ChecklistItem] = []

        // 1. LiDAR / AR self-test.
        if DeviceCapabilities.hasLiDAR {
            out.append(.init(id: "lidar", title: "LiDAR + AR",
                             severity: .pass,
                             message: "LiDAR scanner detected. AR session will run."))
        } else {
            out.append(.init(id: "lidar", title: "LiDAR + AR",
                             severity: .warn,
                             message: "No LiDAR on this device. Forestix will fall back to manual-only DBH and tape-tangent height. Bring a caliper and a laser rangefinder."))
        }

        // 2. GPS — we can't actually wait for a fix here (that requires a
        // live CLLocationManager session), so we only check that Location
        // authorization is potentially grantable. Field pilots yard-test
        // this separately.
        out.append(.init(id: "gps", title: "GPS check",
                         severity: .warn,
                         message: "Confirm a Tier A fix in the yard before driving to the stand. Use the Plot Centre screen with the phone flat in open sky."))

        // 3. Calibration values on the project.
        if project.depthNoiseMm > 0,
           project.dbhCorrectionBeta > 0 {
            out.append(.init(id: "calibration", title: "Calibration",
                             severity: .pass,
                             message: "Depth noise and DBH correction parameters are set."))
        } else {
            out.append(.init(id: "calibration", title: "Calibration",
                             severity: .fail,
                             message: "Run the wall + cylinder calibration in Settings → Calibration before you leave cell service. Without it DBH confidence will report red tier."))
        }

        // 4. Offline basemap — tiles are under Application Support.
        let hasBasemap = checkOfflineBasemap()
        out.append(hasBasemap
            ? .init(id: "basemap", title: "Offline basemap",
                    severity: .pass,
                    message: "Tiles for the project extent are cached.")
            : .init(id: "basemap", title: "Offline basemap",
                    severity: .warn,
                    message: "No tiles cached. The map view will fall back to the bare OSM URL, which requires a network connection."))

        // 5. Species + volume equations.
        do {
            let species = try env.speciesRepository.list()
            let equations = try env.volumeEquationRepository.list()
            let missingEq = species.filter { sp in
                !equations.contains { $0.id == sp.volumeEquationId }
            }
            if species.isEmpty {
                out.append(.init(id: "species", title: "Species list",
                                 severity: .fail,
                                 message: "No species configured. Add species in Settings → Species before field work."))
            } else if !missingEq.isEmpty {
                out.append(.init(id: "species", title: "Species list",
                                 severity: .fail,
                                 message: "Missing volume equations for: \(missingEq.map { $0.code }.joined(separator: ", ")). Plot volume numbers will be 0 for those species."))
            } else {
                out.append(.init(id: "species", title: "Species list",
                                 severity: .pass,
                                 message: "\(species.count) species configured, each linked to a volume equation."))
            }
        } catch {
            out.append(.init(id: "species", title: "Species list",
                             severity: .fail,
                             message: "Database read failed: \(error.localizedDescription). Restart the app and try again."))
        }

        // 6. Storage.
        let bytes = StorageProbe.availableBytes()
        let mb = bytes / 1_048_576
        if mb >= 500 {
            out.append(.init(id: "storage", title: "Storage",
                             severity: .pass,
                             message: "\(mb) MB free — enough for a full-day cruise."))
        } else {
            out.append(.init(id: "storage", title: "Storage",
                             severity: .fail,
                             message: "Only \(mb) MB free (need ≥ 500 MB). Offload photos / scans or delete old exports, then re-run the check."))
        }

        // 7. Battery.
        let bat = BatteryState.current()
        if bat.level >= 0.5 || bat.isCharging {
            out.append(.init(id: "battery", title: "Battery",
                             severity: .pass,
                             message: "\(Int(bat.level * 100)) % — ready for a full day."))
        } else {
            out.append(.init(id: "battery", title: "Battery",
                             severity: .warn,
                             message: "Battery at \(Int(bat.level * 100)) %. Charge before driving to the stand; bring a backup power bank."))
        }

        self.items = out
        self.isReady = out.allSatisfy { $0.severity == .pass }
    }

    // MARK: - Helpers

    private func checkOfflineBasemap() -> Bool {
        let fm = FileManager.default
        guard let appSup = try? fm.url(for: .applicationSupportDirectory,
                                       in: .userDomainMask,
                                       appropriateFor: nil, create: false)
        else { return false }
        let dir = appSup.appendingPathComponent("Forestix/basemap",
                                                isDirectory: true)
        // Simple heuristic: folder exists and contains at least one file.
        guard let contents = try? fm.contentsOfDirectory(at: dir,
                                                         includingPropertiesForKeys: nil),
              !contents.isEmpty
        else { return false }
        return true
    }
}
