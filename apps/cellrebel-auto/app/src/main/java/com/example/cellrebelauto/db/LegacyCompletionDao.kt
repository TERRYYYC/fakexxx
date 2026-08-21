package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.plan.LegacyCompletionSnapshot

/**
 * DAO for `legacy_completion_snapshots` (§7.1). Written exactly once during v4→v5 migration,
 * read-only thereafter. There is intentionally no update or delete method.
 * # 历史完成快照 DAO：迁移时写一次，只读；无 update/delete
 */
@Dao
interface LegacyCompletionDao {

    /** Insert-only. Used by MIGRATION_4_5 (one row per pre-existing task). */
    @Insert
    suspend fun insert(snapshot: LegacyCompletionSnapshot): Long

    /** Synchronous variant for migration verification (migration runs on a raw DB handle). */
    @Insert
    fun insertBlocking(snapshot: LegacyCompletionSnapshot): Long

    @Query("SELECT * FROM legacy_completion_snapshots WHERE taskId = :taskId LIMIT 1")
    suspend fun forTask(taskId: Long): LegacyCompletionSnapshot?

    @Query("SELECT * FROM legacy_completion_snapshots ORDER BY taskId ASC")
    suspend fun all(): List<LegacyCompletionSnapshot>

    @Query("SELECT COUNT(*) FROM legacy_completion_snapshots")
    suspend fun count(): Int
}
