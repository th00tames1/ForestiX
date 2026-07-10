// Port of iOS Persistence/Repositories/ProjectRepository.swift.
// Spec §8 + REQ-PRJ-001 (CRUD persists across app restarts).
//
// The iOS protocol's throwing sync functions become suspend functions; the
// Room implementation (`RoomProjectRepository`) plays the role of
// `CoreDataProjectRepository`. `observeList()` is the Compose-side extra —
// a cold Flow the project list screen collects like an @FetchRequest.

package com.hcjeong.forestix.data.cruise

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface ProjectRepository {
    suspend fun create(project: Project): Project
    suspend fun read(id: UUID): Project?
    suspend fun update(project: Project): Project
    suspend fun delete(id: UUID)
    suspend fun list(): List<Project>

    /// Live view of `list()` (newest-first) for Compose screens.
    fun observeList(): Flow<List<Project>>
}

class RoomProjectRepository(private val dao: ProjectDao) : ProjectRepository {

    override suspend fun create(project: Project): Project {
        dao.upsert(ProjectMapper.apply(project))
        return project
    }

    override suspend fun read(id: UUID): Project? =
        dao.byId(id)?.let(ProjectMapper::toStruct)

    override suspend fun update(project: Project): Project {
        dao.byId(project.id) ?: throw CruiseDataError.NotFound(project.id.toString())
        dao.upsert(ProjectMapper.apply(project))
        return project
    }

    override suspend fun delete(id: UUID) {
        if (dao.deleteById(id) == 0) throw CruiseDataError.NotFound(id.toString())
    }

    override suspend fun list(): List<Project> =
        dao.list().map(ProjectMapper::toStruct)

    override fun observeList(): Flow<List<Project>> =
        dao.observeList().map { rows -> rows.map(ProjectMapper::toStruct) }
}
