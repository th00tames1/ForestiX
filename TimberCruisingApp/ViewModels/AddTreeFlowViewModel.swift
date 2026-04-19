// Phase 5 §5.2 AddTreeFlow view model. REQ-TAL-001..004, REQ-HGT-007.
//
// Stepper state machine: Species → DBH → Height (optional) → Extras → Review.
// Uses `HeightSubsample.shouldMeasureHeight` to decide whether the Height step
// is required; the user may still skip and accept H–D imputation later at
// plot-close time.
//
// Multistem: after a main stem is saved, the user can stamp additional child
// stems that share the same (x,y) placement but carry their own tree numbers
// and DBH values. Child stems store `parentTreeId = <main stem id>` and
// `isMultistem = true`.
//
// Red-tier DBH/Height triggers a single-shot `redTierWarning` flag that the
// Review step surfaces but does not block saving — per REQ-DBH-006/009 the
// warning is informational.

import Foundation
import Combine
import Models
import Common
import Persistence
import InventoryEngine

@MainActor
public final class AddTreeFlowViewModel: ObservableObject {

    public enum Step: Int, CaseIterable, Sendable {
        case species
        case dbh
        case height
        case extras
        case review
    }

    // Inputs
    public let project: Project
    public let design: CruiseDesign
    public let plot: Plot
    public let existingTrees: [Tree]
    public let speciesByCode: [String: SpeciesConfig]

    // Repo
    private let treeRepo: any TreeRepository

    // Stepper
    @Published public private(set) var currentStep: Step = .species
    @Published public private(set) var history: [Step] = []

    // Species
    @Published public var speciesCode: String = ""
    @Published public private(set) var recentSpeciesCodes: [String] = []

    // DBH
    @Published public var dbhCm: Float = 0
    @Published public var dbhMethod: DBHMethod = .manualCaliper
    @Published public var dbhIsIrregular: Bool = false

    // Height
    @Published public var heightM: Float? = nil
    @Published public var heightMethod: HeightMethod? = nil
    @Published public private(set) var heightRequired: Bool = false

    // Extras
    @Published public var status: TreeStatus = .live
    @Published public var crownClass: String? = nil
    @Published public var damageCodes: [String] = []
    @Published public var notes: String = ""
    @Published public var bearingFromCenterDeg: Float? = nil
    @Published public var distanceFromCenterM: Float? = nil

    // Multistem (after main save; stamp more stems)
    @Published public var isMultistem: Bool = false
    @Published public var parentTreeId: UUID? = nil

    // Confidence + warnings
    @Published public private(set) var dbhConfidence: ConfidenceTier = .green
    @Published public private(set) var heightConfidence: ConfidenceTier? = nil
    @Published public private(set) var redTierWarning: String? = nil

    // Save state
    @Published public private(set) var isSaving: Bool = false
    @Published public private(set) var errorMessage: String? = nil
    @Published public private(set) var savedTree: Tree? = nil

    public init(
        project: Project,
        design: CruiseDesign,
        plot: Plot,
        existingTrees: [Tree],
        speciesByCode: [String: SpeciesConfig],
        treeRepo: any TreeRepository,
        recentSpeciesCodes: [String] = []
    ) {
        self.project = project
        self.design = design
        self.plot = plot
        self.existingTrees = existingTrees
        self.speciesByCode = speciesByCode
        self.treeRepo = treeRepo
        self.recentSpeciesCodes = recentSpeciesCodes
    }

    /// Next tree number = max(live number) + 1.
    public var nextTreeNumber: Int {
        let live = existingTrees.filter { $0.deletedAt == nil }
        return (live.map(\.treeNumber).max() ?? 0) + 1
    }

    // MARK: - Navigation

    public func advance() {
        history.append(currentStep)
        switch currentStep {
        case .species:
            evaluateHeightRequirement()
            currentStep = .dbh
        case .dbh:
            recomputeDbhConfidence()
            currentStep = heightRequired ? .height : .extras
        case .height:
            recomputeHeightConfidence()
            currentStep = .extras
        case .extras:
            computeRedTierWarning()
            currentStep = .review
        case .review:
            break
        }
    }

    public func back() {
        guard let prev = history.popLast() else { return }
        currentStep = prev
    }

    public func canAdvance() -> Bool {
        switch currentStep {
        case .species:
            return speciesByCode[speciesCode] != nil
        case .dbh:
            return dbhCm > 0
        case .height:
            // Height step can be skipped via skipHeight().
            return heightM != nil
        case .extras:
            return true
        case .review:
            return true
        }
    }

    /// User chose to skip measurement; height will be imputed at plot close.
    public func skipHeight() {
        heightM = nil
        heightMethod = nil
        heightConfidence = nil
        history.append(currentStep)
        currentStep = .extras
    }

    // MARK: - Evaluators

    private func evaluateHeightRequirement() {
        heightRequired = HeightSubsample.shouldMeasureHeight(
            rule: design.heightSubsampleRule,
            newTreeNumber: nextTreeNumber,
            newSpeciesCode: speciesCode,
            existingTreesOnPlot: existingTrees)
    }

    private func recomputeDbhConfidence() {
        guard let sp = speciesByCode[speciesCode] else {
            dbhConfidence = .yellow
            return
        }
        let checks: [Check] = [
            check(dbhCm > 0, sev: .reject, reason: "DBH must be positive"),
            check(dbhCm >= sp.expectedDbhMinCm,
                  sev: .warn, reason: "DBH below species minimum"),
            check(dbhCm <= sp.expectedDbhMaxCm,
                  sev: .warn, reason: "DBH above species maximum")
        ]
        dbhConfidence = combineChecks(checks)
    }

    private func recomputeHeightConfidence() {
        guard let h = heightM else {
            heightConfidence = nil
            return
        }
        guard let sp = speciesByCode[speciesCode] else {
            heightConfidence = .yellow
            return
        }
        let checks: [Check] = [
            check(h > 0, sev: .reject, reason: "Height must be positive"),
            check(h >= sp.expectedHeightMinM,
                  sev: .warn, reason: "Height below species minimum"),
            check(h <= sp.expectedHeightMaxM,
                  sev: .warn, reason: "Height above species maximum")
        ]
        heightConfidence = combineChecks(checks)
    }

    private func computeRedTierWarning() {
        var parts: [String] = []
        if dbhConfidence == .red {
            parts.append("DBH is red-tier (out of expected range).")
        }
        if heightConfidence == .red {
            parts.append("Height is red-tier (out of expected range).")
        }
        redTierWarning = parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    // MARK: - Save

    public func save() {
        guard !isSaving else { return }
        isSaving = true
        defer { isSaving = false }

        let now = Date()
        let tree = Tree(
            id: UUID(),
            plotId: plot.id,
            treeNumber: nextTreeNumber,
            speciesCode: speciesCode,
            status: status,
            dbhCm: dbhCm,
            dbhMethod: dbhMethod,
            dbhSigmaMm: nil,
            dbhRmseMm: nil,
            dbhCoverageDeg: nil,
            dbhNInliers: nil,
            dbhConfidence: dbhConfidence,
            dbhIsIrregular: dbhIsIrregular,
            heightM: heightM,
            heightMethod: heightMethod,
            heightSource: heightM != nil ? "measured" : nil,
            heightSigmaM: nil,
            heightDHM: nil,
            heightAlphaTopDeg: nil,
            heightAlphaBaseDeg: nil,
            heightConfidence: heightConfidence,
            bearingFromCenterDeg: bearingFromCenterDeg,
            distanceFromCenterM: distanceFromCenterM,
            boundaryCall: nil,
            crownClass: crownClass,
            damageCodes: damageCodes,
            isMultistem: isMultistem,
            parentTreeId: parentTreeId,
            notes: notes,
            photoPath: nil,
            rawScanPath: nil,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil)

        do {
            let saved = try treeRepo.create(tree)
            savedTree = saved
            errorMessage = nil
        } catch {
            errorMessage = "Save failed: \(error.localizedDescription)"
        }
    }

    /// Prepare for a follow-up multistem child. Retains species + placement
    /// from the parent, clears DBH/height/number, flags child as multistem,
    /// and returns to the DBH step.
    public func prepareMultistemChild() {
        guard let parent = savedTree else { return }
        parentTreeId = parent.id
        isMultistem = true
        // Keep: speciesCode, bearingFromCenterDeg, distanceFromCenterM, status
        dbhCm = 0
        dbhIsIrregular = false
        heightM = nil
        heightMethod = nil
        heightConfidence = nil
        redTierWarning = nil
        savedTree = nil
        history.removeAll()
        currentStep = .dbh
    }

    // MARK: - Preview

    public static func preview(
        existingTrees: [Tree] = [],
        speciesByCode: [String: SpeciesConfig] = [:],
        recentSpeciesCodes: [String] = ["DF", "WH", "RC"]
    ) -> AddTreeFlowViewModel {
        let projectId = UUID()
        let project = Project(
            id: projectId, name: "Preview", description: "",
            owner: "preview",
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            units: .metric, breastHeightConvention: .uphill,
            slopeCorrection: false,
            lidarBiasMm: 0, depthNoiseMm: 0,
            dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
            vioDriftFraction: 0.02)
        let design = CruiseDesign(
            id: UUID(), projectId: projectId,
            plotType: .fixedArea, plotAreaAcres: 0.1,
            baf: nil, samplingScheme: .systematicGrid,
            gridSpacingMeters: 50)
        let plot = Plot(
            id: UUID(), projectId: projectId, plannedPlotId: nil,
            plotNumber: 1,
            centerLat: 45.1, centerLon: -122.6,
            positionSource: .gpsAveraged, positionTier: .B,
            gpsNSamples: 30, gpsMedianHAccuracyM: 4.5, gpsSampleStdXyM: 2.8,
            offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
            plotAreaAcres: 0.1,
            startedAt: Date(timeIntervalSince1970: 0),
            closedAt: nil, closedBy: nil,
            notes: "", coverPhotoPath: nil, panoramaPath: nil)
        return AddTreeFlowViewModel(
            project: project, design: design, plot: plot,
            existingTrees: existingTrees,
            speciesByCode: speciesByCode,
            treeRepo: StubAddTreeRepo(),
            recentSpeciesCodes: recentSpeciesCodes)
    }
}

// MARK: - Preview stub

private final class StubAddTreeRepo: TreeRepository {
    func create(_ t: Tree) throws -> Tree { t }
    func read(id: UUID, includeDeleted: Bool) throws -> Tree? { nil }
    func update(_ t: Tree) throws -> Tree { t }
    func delete(id: UUID, at date: Date) throws {}
    func hardDelete(id: UUID) throws {}
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree] { [] }
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree] { [] }
    func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String] { [] }
}
