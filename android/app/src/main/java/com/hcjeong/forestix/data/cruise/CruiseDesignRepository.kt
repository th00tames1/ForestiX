// Port of iOS Persistence/Repositories/CruiseDesignRepository.swift.
// Spec §8 + REQ-PRJ-003.

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface CruiseDesignRepository {
    suspend fun create(d: CruiseDesign): CruiseDesign
    suspend fun read(id: UUID): CruiseDesign?
    suspend fun update(d: CruiseDesign): CruiseDesign
    suspend fun delete(id: UUID)
    suspend fun forProject(projectId: UUID): List<CruiseDesign>

    /// Live view of `forProject()` for Compose screens.
    fun observeForProject(projectId: UUID): Flow<List<CruiseDesign>>
}

class RoomCruiseDesignRepository(private val dao: CruiseDesignDao) : CruiseDesignRepository {

    override suspend fun create(d: CruiseDesign): CruiseDesign {
        dao.upsert(CruiseDesignMapper.apply(d))
        return d
    }

    override suspend fun read(id: UUID): CruiseDesign? =
        dao.byId(id)?.let(CruiseDesignMapper::toStruct)

    override suspend fun update(d: CruiseDesign): CruiseDesign {
        dao.byId(d.id) ?: throw CruiseDataError.NotFound(d.id.toString())
        dao.upsert(CruiseDesignMapper.apply(d))
        return d
    }

    override suspend fun delete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun forProject(projectId: UUID): List<CruiseDesign> =
        dao.forProject(projectId).map(CruiseDesignMapper::toStruct)

    override fun observeForProject(projectId: UUID): Flow<List<CruiseDesign>> =
        dao.observeForProject(projectId).map { rows -> rows.map(CruiseDesignMapper::toStruct) }
}
