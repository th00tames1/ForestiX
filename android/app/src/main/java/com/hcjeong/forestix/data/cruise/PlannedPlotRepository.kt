// Port of iOS Persistence/Repositories/PlannedPlotRepository.swift.
// Spec §8 + REQ-PRJ-004, REQ-NAV-001 (visited vs remaining).

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface PlannedPlotRepository {
    suspend fun create(p: PlannedPlot): PlannedPlot
    suspend fun read(id: UUID): PlannedPlot?
    suspend fun update(p: PlannedPlot): PlannedPlot
    suspend fun delete(id: UUID)
    suspend fun listByProject(projectId: UUID): List<PlannedPlot>
    suspend fun listUnvisited(projectId: UUID): List<PlannedPlot>

    /// Live view of `listByProject()` for Compose screens.
    fun observeByProject(projectId: UUID): Flow<List<PlannedPlot>>
}

class RoomPlannedPlotRepository(private val dao: PlannedPlotDao) : PlannedPlotRepository {

    override suspend fun create(p: PlannedPlot): PlannedPlot {
        dao.upsert(PlannedPlotMapper.apply(p))
        return p
    }

    override suspend fun read(id: UUID): PlannedPlot? =
        dao.byId(id)?.let(PlannedPlotMapper::toStruct)

    override suspend fun update(p: PlannedPlot): PlannedPlot {
        dao.byId(p.id) ?: throw CruiseDataError.NotFound(p.id.toString())
        dao.upsert(PlannedPlotMapper.apply(p))
        return p
    }

    override suspend fun delete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun listByProject(projectId: UUID): List<PlannedPlot> =
        dao.listByProject(projectId).map(PlannedPlotMapper::toStruct)

    override suspend fun listUnvisited(projectId: UUID): List<PlannedPlot> =
        dao.listUnvisited(projectId).map(PlannedPlotMapper::toStruct)

    override fun observeByProject(projectId: UUID): Flow<List<PlannedPlot>> =
        dao.observeByProject(projectId).map { rows -> rows.map(PlannedPlotMapper::toStruct) }
}
