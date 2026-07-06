// Port of iOS Persistence/Repositories/HeightDiameterFitRepository.swift.
// Spec §8 + §7.4 (per project, per species). Rolling updates on plot close.
//
// The iOS protocol-extension `recomputeForSpecies` becomes a Kotlin
// extension function on the interface so every implementation inherits it,
// exactly like a Swift protocol extension.

package com.hcjeong.forestix.data.cruise

import com.hcjeong.forestix.inventory.HDModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface HeightDiameterFitRepository {
    suspend fun create(f: HeightDiameterFit): HeightDiameterFit
    suspend fun read(id: UUID): HeightDiameterFit?
    suspend fun update(f: HeightDiameterFit): HeightDiameterFit
    suspend fun delete(id: UUID)
    suspend fun forProjectAndSpecies(projectId: UUID, speciesCode: String): HeightDiameterFit?
    suspend fun listByProject(projectId: UUID): List<HeightDiameterFit>

    /// Live view of `listByProject()` for Compose screens.
    fun observeByProject(projectId: UUID): Flow<List<HeightDiameterFit>>
}

/// §7.4 rolling update. Loads the existing fit for (project, species)
/// if one exists, refits Näslund H–D using HDModel.update with the
/// supplied observations as warm-start data, and either creates or
/// updates the persisted row. Returns the persisted HeightDiameterFit.
///
/// Callers (e.g. PlotClose) pass *all* measured (dbh, height) pairs
/// for the species, not a delta — the fit is always over the full
/// accumulated dataset.
///
/// Throws HDModel.FitError when the observations cannot support a fit.
suspend fun HeightDiameterFitRepository.recomputeForSpecies(
    projectId: UUID,
    speciesCode: String,
    observations: List<HDModel.Observation>,
    minN: Int = 8,
    now: Long = System.currentTimeMillis(),
): HeightDiameterFit {
    val prev = forProjectAndSpecies(projectId = projectId, speciesCode = speciesCode)
    val warm: HDModel.Fit? = prev?.let { fit ->
        HDModel.Fit.fromCoefficients(fit.coefficients, nObs = fit.nObs, rmse = fit.rmse)
    }
    val newFit = HDModel.update(
        previous = warm,
        observations = observations,
        minN = minN)
    return if (prev != null) {
        update(HeightDiameterFit(
            id = prev.id,
            projectId = projectId,
            speciesCode = speciesCode,
            modelForm = "naslund",
            coefficients = newFit.coefficients,
            nObs = newFit.nObs,
            rmse = newFit.rmse,
            updatedAt = now))
    } else {
        create(HeightDiameterFit(
            id = UUID.randomUUID(),
            projectId = projectId,
            speciesCode = speciesCode,
            modelForm = "naslund",
            coefficients = newFit.coefficients,
            nObs = newFit.nObs,
            rmse = newFit.rmse,
            updatedAt = now))
    }
}

class RoomHeightDiameterFitRepository(
    private val dao: HeightDiameterFitDao,
) : HeightDiameterFitRepository {

    override suspend fun create(f: HeightDiameterFit): HeightDiameterFit {
        dao.upsert(HeightDiameterFitMapper.apply(f))
        return f
    }

    override suspend fun read(id: UUID): HeightDiameterFit? =
        dao.byId(id)?.let(HeightDiameterFitMapper::toStruct)

    override suspend fun update(f: HeightDiameterFit): HeightDiameterFit {
        dao.byId(f.id) ?: throw CruiseDataError.NotFound(f.id.toString())
        dao.upsert(HeightDiameterFitMapper.apply(f))
        return f
    }

    override suspend fun delete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun forProjectAndSpecies(
        projectId: UUID,
        speciesCode: String,
    ): HeightDiameterFit? =
        dao.forProjectAndSpecies(projectId, speciesCode)
            ?.let(HeightDiameterFitMapper::toStruct)

    override suspend fun listByProject(projectId: UUID): List<HeightDiameterFit> =
        dao.listByProject(projectId).map(HeightDiameterFitMapper::toStruct)

    override fun observeByProject(projectId: UUID): Flow<List<HeightDiameterFit>> =
        dao.observeByProject(projectId)
            .map { rows -> rows.map(HeightDiameterFitMapper::toStruct) }
}
