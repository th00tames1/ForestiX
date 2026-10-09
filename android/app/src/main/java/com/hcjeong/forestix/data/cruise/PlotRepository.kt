// Port of iOS Persistence/Repositories/PlotRepository.swift.
// Spec §8 + REQ-CTR-005, REQ-AGG-001..003.

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface PlotRepository {
    suspend fun create(p: Plot): Plot
    suspend fun read(id: UUID): Plot?
    suspend fun update(p: Plot): Plot
    suspend fun delete(id: UUID)
    suspend fun listByProject(projectId: UUID): List<Plot>
    suspend fun closed(projectId: UUID): List<Plot>
    suspend fun byPlotNumber(projectId: UUID, plotNumber: Int): Plot?

    /// Live view of `listByProject()` for Compose screens.
    fun observeByProject(projectId: UUID): Flow<List<Plot>>
}

class RoomPlotRepository(private val dao: PlotDao) : PlotRepository {

    /// Refuse a site description the model says cannot exist, BEFORE it
    /// reaches the store. The sheet that types these numbers checks the same
    /// rule and keeps Save off, but the sheet is only today's caller — a
    /// restore, an importer or a future screen writing an aspect of 400° must
    /// hit the same wall, because nothing downstream re-reads the range and a
    /// stored 400° is wrong in every export from then on.
    private fun validate(p: Plot) {
        p.siteDescriptionRejection?.let { throw CruiseDataError.InvalidValue(it) }
    }

    override suspend fun create(p: Plot): Plot {
        validate(p)
        dao.upsert(PlotMapper.apply(p))
        return p
    }

    override suspend fun read(id: UUID): Plot? =
        dao.byId(id)?.let(PlotMapper::toStruct)

    override suspend fun update(p: Plot): Plot {
        validate(p)
        dao.byId(p.id) ?: throw CruiseDataError.NotFound(p.id.toString())
        dao.upsert(PlotMapper.apply(p))
        return p
    }

    override suspend fun delete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun listByProject(projectId: UUID): List<Plot> =
        dao.listByProject(projectId).map(PlotMapper::toStruct)

    override suspend fun closed(projectId: UUID): List<Plot> =
        dao.closed(projectId).map(PlotMapper::toStruct)

    override suspend fun byPlotNumber(projectId: UUID, plotNumber: Int): Plot? =
        dao.byPlotNumber(projectId, plotNumber)?.let(PlotMapper::toStruct)

    override fun observeByProject(projectId: UUID): Flow<List<Plot>> =
        dao.observeByProject(projectId).map { rows -> rows.map(PlotMapper::toStruct) }
}
