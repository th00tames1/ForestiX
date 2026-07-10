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

    override suspend fun create(p: Plot): Plot {
        dao.upsert(PlotMapper.apply(p))
        return p
    }

    override suspend fun read(id: UUID): Plot? =
        dao.byId(id)?.let(PlotMapper::toStruct)

    override suspend fun update(p: Plot): Plot {
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
