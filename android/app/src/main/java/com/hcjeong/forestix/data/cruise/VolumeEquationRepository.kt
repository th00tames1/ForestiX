// Port of iOS Persistence/Repositories/VolumeEquationRepository.swift.
// Spec §8 (persisted VolumeEquation record) + §7.7.

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface VolumeEquationRepository {
    suspend fun create(v: VolumeEquation): VolumeEquation
    suspend fun read(id: String): VolumeEquation?
    suspend fun update(v: VolumeEquation): VolumeEquation
    suspend fun delete(id: String)
    suspend fun list(): List<VolumeEquation>

    /// Live view of `list()` (id ascending) for Compose screens.
    fun observeList(): Flow<List<VolumeEquation>>
}

class RoomVolumeEquationRepository(private val dao: VolumeEquationDao) : VolumeEquationRepository {

    override suspend fun create(v: VolumeEquation): VolumeEquation {
        dao.upsert(VolumeEquationMapper.apply(v))
        return v
    }

    override suspend fun read(id: String): VolumeEquation? =
        dao.byId(id)?.let(VolumeEquationMapper::toStruct)

    override suspend fun update(v: VolumeEquation): VolumeEquation {
        dao.byId(v.id) ?: throw CruiseDataError.NotFound(v.id)
        dao.upsert(VolumeEquationMapper.apply(v))
        return v
    }

    override suspend fun delete(id: String) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id)
    }

    override suspend fun list(): List<VolumeEquation> =
        dao.list().map(VolumeEquationMapper::toStruct)

    override fun observeList(): Flow<List<VolumeEquation>> =
        dao.observeList().map { rows -> rows.map(VolumeEquationMapper::toStruct) }
}
