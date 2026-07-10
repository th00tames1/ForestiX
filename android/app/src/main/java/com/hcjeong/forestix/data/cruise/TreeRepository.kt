// Port of iOS Persistence/Repositories/TreeRepository.swift.
// Spec §8 + REQ-TAL-001..006. Soft-delete (§6.2 Tree.deletedAt) supported:
//   - `delete(id, at)` sets `deletedAt` without removing the row.
//   - `hardDelete(id)` removes the row permanently (admin-only).
//   - list/query methods take `includeDeleted` (default false).
//
// The iOS protocol-extension convenience overloads (read/delete/listByPlot
// without the extra argument) become Kotlin default parameter values.

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface TreeRepository {
    suspend fun create(t: Tree): Tree
    suspend fun read(id: UUID, includeDeleted: Boolean = false): Tree?
    suspend fun update(t: Tree): Tree

    /// Soft delete: sets `deletedAt` (and `updatedAt`) to the given
    /// epoch-millis instant (default now).
    suspend fun delete(id: UUID, at: Long = System.currentTimeMillis())
    suspend fun hardDelete(id: UUID)
    suspend fun listByPlot(plotId: UUID, includeDeleted: Boolean = false): List<Tree>
    suspend fun bySpeciesInProject(
        projectId: UUID,
        speciesCode: String,
        includeDeleted: Boolean = false,
    ): List<Tree>

    /// Top-N species codes by live-tree count within a project, ordered by
    /// frequency descending. Used by the AddTreeFlow species quick-tap grid.
    /// Soft-deleted trees are excluded.
    suspend fun recentSpeciesCodes(projectId: UUID, limit: Int): List<String>

    /// Live view of `listByPlot(plotId, includeDeleted = false)` for Compose.
    fun observeByPlot(plotId: UUID): Flow<List<Tree>>
}

class RoomTreeRepository(private val dao: TreeDao) : TreeRepository {

    override suspend fun create(t: Tree): Tree {
        dao.upsert(TreeMapper.apply(t))
        return t
    }

    override suspend fun read(id: UUID, includeDeleted: Boolean): Tree? {
        val e = dao.byId(id) ?: return null
        if (!includeDeleted && e.deletedAt != null) return null
        return TreeMapper.toStruct(e)
    }

    override suspend fun update(t: Tree): Tree {
        dao.byId(t.id) ?: throw CruiseDataError.NotFound(t.id.toString())
        dao.upsert(TreeMapper.apply(t))
        return t
    }

    override suspend fun delete(id: UUID, at: Long) {
        if (dao.softDelete(id, at) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun hardDelete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun listByPlot(plotId: UUID, includeDeleted: Boolean): List<Tree> =
        dao.listByPlot(plotId, includeDeleted).map(TreeMapper::toStruct)

    override suspend fun bySpeciesInProject(
        projectId: UUID,
        speciesCode: String,
        includeDeleted: Boolean,
    ): List<Tree> =
        dao.bySpeciesInProject(projectId, speciesCode, includeDeleted)
            .map(TreeMapper::toStruct)

    override suspend fun recentSpeciesCodes(projectId: UUID, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        return dao.recentSpeciesCodes(projectId, limit)
    }

    override fun observeByPlot(plotId: UUID): Flow<List<Tree>> =
        dao.observeByPlot(plotId).map { rows -> rows.map(TreeMapper::toStruct) }
}
