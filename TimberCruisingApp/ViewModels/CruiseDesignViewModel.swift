// Spec §3.1 REQ-PRJ-003/004. Configure cruise design (plot type, plot size
// or BAF, sampling scheme, grid spacing) and generate PlannedPlots via the
// Geo SamplingGenerator. Persists the design record and replaces all
// previously-generated PlannedPlots for this project.

import Foundation
import Models
import Persistence
import Geo

@MainActor
public final class CruiseDesignViewModel: ObservableObject {

    // MARK: - Editable form state

    @Published public var plotType: PlotType = .fixedArea
    @Published public var plotAreaAcresString: String = "0.1"
    @Published public var bafString: String = "20"
    @Published public var samplingScheme: SamplingScheme = .systematicGrid
    @Published public var gridSpacingMetersString: String = "150"
    @Published public var nPerStratumString: String = "10"
    @Published public var seedString: String = "1"

    // MARK: - Feedback

    @Published public private(set) var plannedCount: Int = 0
    @Published public private(set) var availableSpecies: [SpeciesConfig] = []
    @Published public private(set) var volumeEquationsById: [String: VolumeEquation] = [:]
    @Published public var errorMessage: String?
    @Published public var toastMessage: String?

    public let project: Project

    private var stratumRepository: (any StratumRepository)?
    private var plannedPlotRepository: (any PlannedPlotRepository)?
    private var designRepository: (any CruiseDesignRepository)?
    private var speciesRepository: (any SpeciesConfigRepository)?
    private var volumeEquationRepository: (any VolumeEquationRepository)?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        if stratumRepository == nil { stratumRepository = environment.stratumRepository }
        if plannedPlotRepository == nil { plannedPlotRepository = environment.plannedPlotRepository }
        if designRepository == nil { designRepository = environment.cruiseDesignRepository }
        if speciesRepository == nil { speciesRepository = environment.speciesRepository }
        if volumeEquationRepository == nil { volumeEquationRepository = environment.volumeEquationRepository }
    }

    // MARK: - Validation

    public var isValid: Bool { validationMessage == nil }

    public var validationMessage: String? {
        if plotType == .fixedArea {
            guard let area = Double(plotAreaAcresString), area > 0 else {
                return "Plot area must be a positive number of acres."
            }
            _ = area
        } else {
            guard let baf = Double(bafString), baf > 0 else {
                return "BAF must be a positive number."
            }
            _ = baf
        }
        switch samplingScheme {
        case .systematicGrid:
            guard let spacing = Double(gridSpacingMetersString), spacing > 0 else {
                return "Grid spacing must be a positive number of metres."
            }
            _ = spacing
        case .stratifiedRandom:
            guard let n = Int(nPerStratumString), n > 0 else {
                return "Plots per stratum must be a positive integer."
            }
            _ = n
        case .manual:
            return nil
        }
        return nil
    }

    // MARK: - Actions

    public func generatePlannedPlots() {
        guard isValid else {
            errorMessage = validationMessage
            return
        }
        guard let stratumRepo = stratumRepository,
              let plotRepo = plannedPlotRepository,
              let designRepo = designRepository
        else { return }

        do {
            let strata = try stratumRepo.listByProject(project.id)
            guard !strata.isEmpty else {
                errorMessage = "Add at least one stratum before generating plots."
                return
            }

            let inputs: [SamplingGenerator.StratumInput] = try strata.map { s in
                let rings = try parseRings(from: s.polygonGeoJSON)
                return .init(stratumId: s.id, rings: rings)
            }

            let seed = UInt64(seedString) ?? 1
            let options = SamplingGenerator.GenerationOptions(
                projectId: project.id,
                scheme: samplingScheme,
                gridSpacingMeters: samplingScheme == .systematicGrid
                    ? Double(gridSpacingMetersString) : nil,
                nPerStratum: samplingScheme == .stratifiedRandom
                    ? Int(nPerStratumString) : nil,
                seed: seed
            )
            let plots = try SamplingGenerator.generate(strata: inputs, options: options)

            // Replace existing planned plots for this project.
            let existing = try plotRepo.listByProject(project.id)
            for p in existing { try plotRepo.delete(id: p.id) }
            for plot in plots { _ = try plotRepo.create(plot) }

            // Persist the design record (one per project).
            let existingDesigns = try designRepo.forProject(project.id)
            let design = CruiseDesign(
                id: existingDesigns.first?.id ?? UUID(),
                projectId: project.id,
                plotType: plotType,
                plotAreaAcres: plotType == .fixedArea
                    ? Float(plotAreaAcresString) : nil,
                baf: plotType == .variableRadius
                    ? Float(bafString) : nil,
                samplingScheme: samplingScheme,
                gridSpacingMeters: samplingScheme == .systematicGrid
                    ? Float(gridSpacingMetersString) : nil
            )
            if existingDesigns.first != nil {
                _ = try designRepo.update(design)
            } else {
                _ = try designRepo.create(design)
            }

            plannedCount = plots.count
            toastMessage = "Generated \(plots.count) planned plot\(plots.count == 1 ? "" : "s")."
        } catch {
            errorMessage = "Plot generation failed: \(error)"
        }
    }

    public func refresh() {
        guard let plotRepo = plannedPlotRepository,
              let designRepo = designRepository
        else { return }
        do {
            plannedCount = try plotRepo.listByProject(project.id).count
            if let existing = try designRepo.forProject(project.id).first {
                plotType = existing.plotType
                if let a = existing.plotAreaAcres { plotAreaAcresString = "\(a)" }
                if let b = existing.baf { bafString = "\(b)" }
                samplingScheme = existing.samplingScheme
                if let g = existing.gridSpacingMeters { gridSpacingMetersString = "\(g)" }
            }
            if let speciesRepo = speciesRepository {
                availableSpecies = try speciesRepo.list()
                    .sorted { $0.commonName < $1.commonName }
            }
            if let volRepo = volumeEquationRepository {
                let eqs = try volRepo.list()
                volumeEquationsById = Dictionary(
                    uniqueKeysWithValues: eqs.map { ($0.id, $0) })
            }
        } catch {
            errorMessage = "Failed to load cruise design: \(error)"
        }
    }

    // MARK: - GeoJSON → rings

    private func parseRings(from geojson: String) throws -> [[CoordinateConversions.LatLon]] {
        guard let data = geojson.data(using: .utf8),
              let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            throw NSError(domain: "CruiseDesign", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid stored polygon JSON"])
        }
        guard obj["type"] as? String == "Polygon",
              let coords = obj["coordinates"] as? [[[Double]]]
        else {
            throw NSError(domain: "CruiseDesign", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "Stored geometry is not a Polygon"])
        }
        return coords.map { ring in
            ring.compactMap { pair in
                guard pair.count >= 2 else { return nil }
                return CoordinateConversions.LatLon(latitude: pair[1], longitude: pair[0])
            }
        }
    }
}
