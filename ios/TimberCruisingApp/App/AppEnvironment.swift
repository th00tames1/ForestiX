// Spec §8 (App/AppEnvironment) — dependency-injection container wired from
// the @main app entry. Holds repositories + user-scoped settings so every
// screen can reach shared services through a single `@EnvironmentObject`
// without passing individual handles down the view tree.
//
// Phase 1 only surfaces the project/design repositories used by the Plan
// CRUD screens. Phase 2+ will extend this with sensor/AR services.

import Foundation
import Models
import Persistence

@MainActor
public final class AppEnvironment: ObservableObject {

    public let projectRepository: any ProjectRepository
    public let stratumRepository: any StratumRepository
    public let cruiseDesignRepository: any CruiseDesignRepository
    public let plannedPlotRepository: any PlannedPlotRepository
    public let plotRepository: any PlotRepository
    public let treeRepository: any TreeRepository
    public let speciesRepository: any SpeciesConfigRepository
    public let volumeEquationRepository: any VolumeEquationRepository
    public let hdFitRepository: any HeightDiameterFitRepository
    public let settings: AppSettings
    /// Local, project-less DBH / Height log surfaced on the Quick Measure
    /// home. Independent of Core Data — see QuickMeasureHistory.swift.
    public let quickMeasureHistory: QuickMeasureHistory

    /// Raw Core Data stack — exposed for Phase 7 backup/restore, which
    /// needs to migrate the persistent store file as a whole unit.
    /// Regular feature code should talk to the repositories above; only
    /// infrastructure (backup, reset, schema-migration) should touch
    /// the stack directly.
    public let coreDataStack: CoreDataStack?

    public init(
        projectRepository: any ProjectRepository,
        stratumRepository: any StratumRepository,
        cruiseDesignRepository: any CruiseDesignRepository,
        plannedPlotRepository: any PlannedPlotRepository,
        plotRepository: any PlotRepository,
        treeRepository: any TreeRepository,
        speciesRepository: any SpeciesConfigRepository,
        volumeEquationRepository: any VolumeEquationRepository,
        hdFitRepository: any HeightDiameterFitRepository,
        settings: AppSettings,
        quickMeasureHistory: QuickMeasureHistory? = nil,
        coreDataStack: CoreDataStack? = nil
    ) {
        self.projectRepository = projectRepository
        self.stratumRepository = stratumRepository
        self.cruiseDesignRepository = cruiseDesignRepository
        self.plannedPlotRepository = plannedPlotRepository
        self.plotRepository = plotRepository
        self.treeRepository = treeRepository
        self.speciesRepository = speciesRepository
        self.volumeEquationRepository = volumeEquationRepository
        self.hdFitRepository = hdFitRepository
        self.settings = settings
        self.quickMeasureHistory = quickMeasureHistory ?? QuickMeasureHistory()
        self.coreDataStack = coreDataStack
    }

    /// Wrap a shared Core Data stack with its default repositories.
    public convenience init(
        stack: CoreDataStack,
        settings: AppSettings,
        quickMeasureHistory: QuickMeasureHistory? = nil
    ) {
        self.init(
            projectRepository: CoreDataProjectRepository(stack: stack),
            stratumRepository: CoreDataStratumRepository(stack: stack),
            cruiseDesignRepository: CoreDataCruiseDesignRepository(stack: stack),
            plannedPlotRepository: CoreDataPlannedPlotRepository(stack: stack),
            plotRepository: CoreDataPlotRepository(stack: stack),
            treeRepository: CoreDataTreeRepository(stack: stack),
            speciesRepository: CoreDataSpeciesConfigRepository(stack: stack),
            volumeEquationRepository: CoreDataVolumeEquationRepository(stack: stack),
            hdFitRepository: CoreDataHeightDiameterFitRepository(stack: stack),
            settings: settings,
            quickMeasureHistory: quickMeasureHistory,
            coreDataStack: stack
        )
    }

    /// Production factory. Loads the on-disk SQLite store at its default URL
    /// and seeds the PNW species + volume-equation starter set on first
    /// launch (idempotent — won't overwrite the cruiser's edits later).
    public static func live() throws -> AppEnvironment {
        let stack = try CoreDataStack()
        let env = AppEnvironment(stack: stack, settings: AppSettings.live())
        do {
            _ = try SeedDataLoader.bootstrapIfNeeded(
                speciesRepo: env.speciesRepository,
                volRepo: env.volumeEquationRepository)
        } catch {
            // Surface but don't crash — the cruiser can manually add
            // species via Settings (Phase 7.2.x). Log so the analytics
            // export will contain the failure.
            print("⚠️ Seed bootstrap failed: \(error)")
        }
        // Housekeeping on every launch: delete Quick Measure CSV
        // exports older than a week so Documents/ doesn't silently
        // accumulate months of stale files.
        QuickMeasureHistory.sweepOldExports()
        return env
    }

    /// SwiftUI-preview / snapshot-test factory. Spins up an in-memory store
    /// so nothing persists between invocations.
    public static func preview() -> AppEnvironment {
        do {
            let stack = try CoreDataStack(configuration: .inMemory)
            return AppEnvironment(
                stack: stack,
                settings: AppSettings.ephemeral(),
                quickMeasureHistory: QuickMeasureHistory.ephemeral())
        } catch {
            fatalError("AppEnvironment.preview(): in-memory stack failed: \(error)")
        }
    }
}
