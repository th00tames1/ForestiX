// Spec §8 + §7.4 (per project, per species). Rolling updates on plot close.

import Foundation
import CoreData
import Models
import InventoryEngine

public protocol HeightDiameterFitRepository {
    func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit
    func read(id: UUID) throws -> HeightDiameterFit?
    func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit
    func delete(id: UUID) throws
    func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit?
    func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit]
}

public extension HeightDiameterFitRepository {

    /// §7.4 rolling update. Loads the existing fit for (project, species)
    /// if one exists, refits Näslund H–D using HDModel.update with the
    /// supplied observations as warm-start data, and either creates or
    /// updates the persisted row. Returns the persisted HeightDiameterFit.
    ///
    /// Callers (e.g. PlotClose) pass *all* measured (dbh, height) pairs
    /// for the species, not a delta — the fit is always over the full
    /// accumulated dataset.
    @discardableResult
    func recomputeForSpecies(
        projectId: UUID,
        speciesCode: String,
        observations: [(dbhCm: Float, heightM: Float)],
        minN: Int = 8,
        now: Date = Date()
    ) throws -> HeightDiameterFit {
        let prev = try forProjectAndSpecies(
            projectId: projectId, speciesCode: speciesCode)
        let warm: HDModel.Fit? = prev.flatMap { fit in
            HDModel.Fit.fromCoefficients(
                fit.coefficients, nObs: fit.nObs, rmse: fit.rmse)
        }
        let newFit = try HDModel.update(
            previous: warm,
            observations: observations,
            minN: minN)
        if let existing = prev {
            let updated = HeightDiameterFit(
                id: existing.id,
                projectId: projectId,
                speciesCode: speciesCode,
                modelForm: "naslund",
                coefficients: newFit.coefficients,
                nObs: newFit.nObs,
                rmse: newFit.rmse,
                updatedAt: now)
            return try update(updated)
        } else {
            let fresh = HeightDiameterFit(
                id: UUID(),
                projectId: projectId,
                speciesCode: speciesCode,
                modelForm: "naslund",
                coefficients: newFit.coefficients,
                nObs: newFit.nObs,
                rmse: newFit.rmse,
                updatedAt: now)
            return try create(fresh)
        }
    }
}

public final class CoreDataHeightDiameterFitRepository: HeightDiameterFitRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit {
        try performWrite(stack: stack) { ctx in
            let e = try insert(HeightDiameterFitEntity.self,
                               entityName: "HeightDiameterFitEntity", in: ctx)
            HeightDiameterFitMapper.apply(f, to: e)
            return f
        }
    }

    public func read(id: UUID) throws -> HeightDiameterFit? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(HeightDiameterFitEntity.self,
                                       entityName: "HeightDiameterFitEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return HeightDiameterFitMapper.toStruct(e)
        }
    }

    public func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(HeightDiameterFitEntity.self,
                                       entityName: "HeightDiameterFitEntity",
                                       keyPath: "id", value: f.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: f.id.uuidString) }
            HeightDiameterFitMapper.apply(f, to: e)
            return f
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(HeightDiameterFitEntity.self,
                                       entityName: "HeightDiameterFitEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit? {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@ AND speciesCode == %@",
                                   projectId as CVarArg, speciesCode)
            let req = NSFetchRequest<HeightDiameterFitEntity>(entityName: "HeightDiameterFitEntity")
            req.predicate = pred
            req.sortDescriptors = [NSSortDescriptor(key: "updatedAt", ascending: false)]
            req.fetchLimit = 1
            guard let e = try ctx.fetch(req).first else { return nil }
            return HeightDiameterFitMapper.toStruct(e)
        }
    }

    public func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            let sort = [NSSortDescriptor(key: "speciesCode", ascending: true)]
            return try fetchMany(HeightDiameterFitEntity.self,
                                 entityName: "HeightDiameterFitEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map(HeightDiameterFitMapper.toStruct)
        }
    }
}
