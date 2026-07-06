// Port of iOS Persistence/Repositories/SpeciesConfigRepository.swift.
// Spec §8 + REQ-PRJ-005.

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SpeciesConfigRepository {
    suspend fun create(s: SpeciesConfig): SpeciesConfig
    suspend fun read(code: String): SpeciesConfig?
    suspend fun update(s: SpeciesConfig): SpeciesConfig
    suspend fun delete(code: String)
    suspend fun list(): List<SpeciesConfig>

    /// Live view of `list()` (code ascending) for Compose screens.
    fun observeList(): Flow<List<SpeciesConfig>>
}

class RoomSpeciesConfigRepository(private val dao: SpeciesConfigDao) : SpeciesConfigRepository {

    override suspend fun create(s: SpeciesConfig): SpeciesConfig {
        dao.upsert(SpeciesConfigMapper.apply(s))
        return s
    }

    override suspend fun read(code: String): SpeciesConfig? =
        dao.byCode(code)?.let(SpeciesConfigMapper::toStruct)

    override suspend fun update(s: SpeciesConfig): SpeciesConfig {
        dao.byCode(s.code) ?: throw CruiseDataError.NotFound(s.code)
        dao.upsert(SpeciesConfigMapper.apply(s))
        return s
    }

    override suspend fun delete(code: String) {
        if (dao.deleteByCode(code) == 0) throw CruiseDataError.NotFound(code)
    }

    override suspend fun list(): List<SpeciesConfig> =
        dao.list().map(SpeciesConfigMapper::toStruct)

    override fun observeList(): Flow<List<SpeciesConfig>> =
        dao.observeList().map { rows -> rows.map(SpeciesConfigMapper::toStruct) }
}
