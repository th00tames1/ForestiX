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
    public let settings: AppSettings

    public init(
        projectRepository: any ProjectRepository,
        stratumRepository: any StratumRepository,
        cruiseDesignRepository: any CruiseDesignRepository,
        plannedPlotRepository: any PlannedPlotRepository,
        settings: AppSettings
    ) {
        self.projectRepository = projectRepository
        self.stratumRepository = stratumRepository
        self.cruiseDesignRepository = cruiseDesignRepository
        self.plannedPlotRepository = plannedPlotRepository
        self.settings = settings
    }

    /// Wrap a shared Core Data stack with its default repositories.
    public convenience init(stack: CoreDataStack, settings: AppSettings) {
        self.init(
            projectRepository: CoreDataProjectRepository(stack: stack),
            stratumRepository: CoreDataStratumRepository(stack: stack),
            cruiseDesignRepository: CoreDataCruiseDesignRepository(stack: stack),
            plannedPlotRepository: CoreDataPlannedPlotRepository(stack: stack),
            settings: settings
        )
    }

    /// Production factory. Loads the on-disk SQLite store at its default URL.
    public static func live() throws -> AppEnvironment {
        let stack = try CoreDataStack()
        return AppEnvironment(stack: stack, settings: AppSettings.live())
    }

    /// SwiftUI-preview / snapshot-test factory. Spins up an in-memory store
    /// so nothing persists between invocations.
    public static func preview() -> AppEnvironment {
        do {
            let stack = try CoreDataStack(configuration: .inMemory)
            return AppEnvironment(stack: stack, settings: AppSettings.ephemeral())
        } catch {
            fatalError("AppEnvironment.preview(): in-memory stack failed: \(error)")
        }
    }
}
