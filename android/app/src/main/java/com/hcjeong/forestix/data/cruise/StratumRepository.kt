// Port of iOS Persistence/Repositories/StratumRepository.swift.
// Spec §8 + REQ-PRJ-002 (strata per project).

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface StratumRepository {
    suspend fun create(s: Stratum): Stratum
    suspend fun read(id: UUID): Stratum?
    suspend fun update(s: Stratum): Stratum
    suspend fun delete(id: UUID)
    suspend fun list(): List<Stratum>
    suspend fun listByProject(projectId: UUID): List<Stratum>

    /// Live view of `listByProject()` for Compose screens.
    fun observeByProject(projectId: UUID): Flow<List<Stratum>>
}

class RoomStratumRepository(private val dao: StratumDao) : StratumRepository {

    override suspend fun create(s: Stratum): Stratum {
        dao.upsert(StratumMapper.apply(s))
        return s
    }

    override suspend fun read(id: UUID): Stratum? =
        dao.byId(id)?.let(StratumMapper::toStruct)

    override suspend fun update(s: Stratum): Stratum {
        dao.byId(s.id) ?: throw CruiseDataError.NotFound(s.id.toString())
        dao.upsert(StratumMapper.apply(s))
        return s
    }

    override suspend fun delete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun list(): List<Stratum> =
        dao.list().map(StratumMapper::toStruct)

    override suspend fun listByProject(projectId: UUID): List<Stratum> =
        dao.listByProject(projectId).map(StratumMapper::toStruct)

    override fun observeByProject(projectId: UUID): Flow<List<Stratum>> =
        dao.observeByProject(projectId).map { rows -> rows.map(StratumMapper::toStruct) }
}
