// Room DAO layer — the Android analogue of iOS
// Persistence/Repositories/RepositoryHelpers.swift (fetchOne / fetchMany /
// insert / performRead / performWrite). Room's generated code plays the role
// the shared Core Data helpers play on iOS; every query below mirrors an
// NSPredicate + NSSortDescriptor combination in one of the iOS repositories.
//
// Sort orders are byte-for-byte the iOS ones (e.g. projects newest-first,
// plots/trees by number ascending) so list screens render identically.

package com.hcjeong.forestix.data.cruise

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: ProjectEntity)

    @Query("SELECT * FROM ProjectEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): ProjectEntity?

    @Query("DELETE FROM ProjectEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query("SELECT * FROM ProjectEntity ORDER BY createdAt DESC")
    suspend fun list(): List<ProjectEntity>

    @Query("SELECT * FROM ProjectEntity ORDER BY createdAt DESC")
    fun observeList(): Flow<List<ProjectEntity>>
}

@Dao
interface StratumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: StratumEntity)

    @Query("SELECT * FROM StratumEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): StratumEntity?

    @Query("DELETE FROM StratumEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query("SELECT * FROM StratumEntity")
    suspend fun list(): List<StratumEntity>

    @Query("SELECT * FROM StratumEntity WHERE projectId = :projectId")
    suspend fun listByProject(projectId: UUID): List<StratumEntity>

    @Query("SELECT * FROM StratumEntity WHERE projectId = :projectId")
    fun observeByProject(projectId: UUID): Flow<List<StratumEntity>>
}

@Dao
interface CruiseDesignDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: CruiseDesignEntity)

    @Query("SELECT * FROM CruiseDesignEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): CruiseDesignEntity?

    @Query("DELETE FROM CruiseDesignEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query("SELECT * FROM CruiseDesignEntity WHERE projectId = :projectId")
    suspend fun forProject(projectId: UUID): List<CruiseDesignEntity>

    @Query("SELECT * FROM CruiseDesignEntity WHERE projectId = :projectId")
    fun observeForProject(projectId: UUID): Flow<List<CruiseDesignEntity>>
}

@Dao
interface PlannedPlotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PlannedPlotEntity)

    @Query("SELECT * FROM PlannedPlotEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): PlannedPlotEntity?

    @Query("DELETE FROM PlannedPlotEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query("SELECT * FROM PlannedPlotEntity WHERE projectId = :projectId ORDER BY plotNumber ASC")
    suspend fun listByProject(projectId: UUID): List<PlannedPlotEntity>

    @Query("SELECT * FROM PlannedPlotEntity WHERE projectId = :projectId AND visited = 0 AND skipped = 0 ORDER BY plotNumber ASC")
    suspend fun listUnvisited(projectId: UUID): List<PlannedPlotEntity>

    @Query("SELECT * FROM PlannedPlotEntity WHERE projectId = :projectId ORDER BY plotNumber ASC")
    fun observeByProject(projectId: UUID): Flow<List<PlannedPlotEntity>>
}

@Dao
interface PlotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PlotEntity)

    @Query("SELECT * FROM PlotEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): PlotEntity?

    @Query("DELETE FROM PlotEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query("SELECT * FROM PlotEntity WHERE projectId = :projectId ORDER BY plotNumber ASC")
    suspend fun listByProject(projectId: UUID): List<PlotEntity>

    @Query("SELECT * FROM PlotEntity WHERE projectId = :projectId AND closedAt IS NOT NULL ORDER BY plotNumber ASC")
    suspend fun closed(projectId: UUID): List<PlotEntity>

    @Query("SELECT * FROM PlotEntity WHERE projectId = :projectId AND plotNumber = :plotNumber LIMIT 1")
    suspend fun byPlotNumber(projectId: UUID, plotNumber: Int): PlotEntity?

    @Query("SELECT * FROM PlotEntity WHERE projectId = :projectId ORDER BY plotNumber ASC")
    fun observeByProject(projectId: UUID): Flow<List<PlotEntity>>
}

@Dao
interface TreeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: TreeEntity)

    @Query("SELECT * FROM TreeEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): TreeEntity?

    /// Soft delete (§6.2 Tree.deletedAt): stamp deletedAt/updatedAt, keep row.
    @Query("UPDATE TreeEntity SET deletedAt = :date, updatedAt = :date WHERE id = :id")
    suspend fun softDelete(id: UUID, date: Long): Int

    @Query("DELETE FROM TreeEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query(
        "SELECT * FROM TreeEntity WHERE plotId = :plotId " +
            "AND (:includeDeleted = 1 OR deletedAt IS NULL) ORDER BY treeNumber ASC"
    )
    suspend fun listByPlot(plotId: UUID, includeDeleted: Boolean): List<TreeEntity>

    @Query("SELECT * FROM TreeEntity WHERE plotId = :plotId AND deletedAt IS NULL ORDER BY treeNumber ASC")
    fun observeByPlot(plotId: UUID): Flow<List<TreeEntity>>

    @Query(
        "SELECT * FROM TreeEntity WHERE plotId IN " +
            "(SELECT id FROM PlotEntity WHERE projectId = :projectId) " +
            "AND speciesCode = :speciesCode " +
            "AND (:includeDeleted = 1 OR deletedAt IS NULL) ORDER BY createdAt ASC"
    )
    suspend fun bySpeciesInProject(
        projectId: UUID,
        speciesCode: String,
        includeDeleted: Boolean,
    ): List<TreeEntity>

    /// Top-N species codes by live-tree count within a project — mirrors the
    /// iOS in-memory count with the same tie-break (count desc, code asc).
    @Query(
        "SELECT speciesCode FROM TreeEntity WHERE plotId IN " +
            "(SELECT id FROM PlotEntity WHERE projectId = :projectId) " +
            "AND deletedAt IS NULL " +
            "GROUP BY speciesCode ORDER BY COUNT(*) DESC, speciesCode ASC LIMIT :limit"
    )
    suspend fun recentSpeciesCodes(projectId: UUID, limit: Int): List<String>
}

@Dao
interface SpeciesConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SpeciesConfigEntity)

    @Query("SELECT * FROM SpeciesConfigEntity WHERE code = :code LIMIT 1")
    suspend fun byCode(code: String): SpeciesConfigEntity?

    @Query("DELETE FROM SpeciesConfigEntity WHERE code = :code")
    suspend fun deleteByCode(code: String): Int

    @Query("SELECT * FROM SpeciesConfigEntity ORDER BY code ASC")
    suspend fun list(): List<SpeciesConfigEntity>

    @Query("SELECT * FROM SpeciesConfigEntity ORDER BY code ASC")
    fun observeList(): Flow<List<SpeciesConfigEntity>>
}

@Dao
interface VolumeEquationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: VolumeEquationEntity)

    @Query("SELECT * FROM VolumeEquationEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): VolumeEquationEntity?

    @Query("DELETE FROM VolumeEquationEntity WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM VolumeEquationEntity ORDER BY id ASC")
    suspend fun list(): List<VolumeEquationEntity>

    @Query("SELECT * FROM VolumeEquationEntity ORDER BY id ASC")
    fun observeList(): Flow<List<VolumeEquationEntity>>
}

@Dao
interface HeightDiameterFitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: HeightDiameterFitEntity)

    @Query("SELECT * FROM HeightDiameterFitEntity WHERE id = :id LIMIT 1")
    suspend fun byId(id: UUID): HeightDiameterFitEntity?

    @Query("DELETE FROM HeightDiameterFitEntity WHERE id = :id")
    suspend fun deleteById(id: UUID): Int

    @Query(
        "SELECT * FROM HeightDiameterFitEntity WHERE projectId = :projectId " +
            "AND speciesCode = :speciesCode ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun forProjectAndSpecies(projectId: UUID, speciesCode: String): HeightDiameterFitEntity?

    @Query("SELECT * FROM HeightDiameterFitEntity WHERE projectId = :projectId ORDER BY speciesCode ASC")
    suspend fun listByProject(projectId: UUID): List<HeightDiameterFitEntity>

    @Query("SELECT * FROM HeightDiameterFitEntity WHERE projectId = :projectId ORDER BY speciesCode ASC")
    fun observeByProject(projectId: UUID): Flow<List<HeightDiameterFitEntity>>
}
