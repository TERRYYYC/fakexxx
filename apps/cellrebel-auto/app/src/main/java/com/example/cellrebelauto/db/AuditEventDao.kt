package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.audit.AutoAuditEvent

/**
 * DAO for `auto_audit_events` — append-only audit stream (§7.1). No update/delete.
 * # 只追加审计流 DAO：无 update/delete
 */
@Dao
interface AuditEventDao {

    @Insert
    suspend fun insert(event: AutoAuditEvent): Long

    @Query("SELECT * FROM auto_audit_events ORDER BY seq ASC")
    suspend fun all(): List<AutoAuditEvent>

    @Query("SELECT * FROM auto_audit_events WHERE attemptId = :attemptId ORDER BY seq ASC")
    suspend fun forAttempt(attemptId: Long): List<AutoAuditEvent>

    @Query("SELECT COUNT(*) FROM auto_audit_events")
    suspend fun count(): Int
}
