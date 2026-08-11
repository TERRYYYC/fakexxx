package com.example.cellrebelauto.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 → v5 (Issue #5 Task 4): introduces the trusted-ledger / execution / audit / legacy-snapshot /
 * provider-pairing tables, and snapshots pre-A+ progress into `legacy_completion_snapshots`.
 *
 * Non-destructive (INV-24): no existing table is rebuilt and no row is lost. The v4 columns
 * `location_tasks.completedSuccesses` and `location_tasks.status` are LEFT IN PLACE as frozen
 * display values; their authoritative legacy copy is written to `legacy_completion_snapshots`
 * with semantics LEGACY_UNVERIFIED. Trusted quota starts from 0 per task — the migration never
 * mints a TrustedQuotaEntry (those old counts carry no A+ evidence chain; INV-05/06, M-MG-01).
 * `provider_pairing_records` is created EMPTY — an upgrade must not produce a trusted provider,
 * which would bypass §6.5.3 operator approval (M-MG-01).
 *
 * # v4→v5 迁移：新增五表，旧进度快照为 LEGACY_UNVERIFIED，可信配额从 0 起，provider 表初始为空
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val migratedAt = System.currentTimeMillis()

        // 1. trusted_quota_entries — UNIQUE(attemptId), insert-only ledger.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trusted_quota_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `taskId` INTEGER NOT NULL,
                `evidenceDigest` TEXT NOT NULL,
                `committedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_quota_entries_attemptId` " +
                "ON `trusted_quota_entries`(`attemptId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trusted_quota_entries_taskId` " +
                "ON `trusted_quota_entries`(`taskId`)"
        )

        // 2. cellrebel_executions — observed external executions (§8.6).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cellrebel_executions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `executionId` TEXT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `completionEvidenceWire` INTEGER NOT NULL,
                `evidencePayloadDigest` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `classifiedAt` INTEGER,
                `startedAtElapsed` INTEGER NOT NULL,
                `runningConfirmedAtElapsed` INTEGER NOT NULL,
                `completedAtElapsed` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // §6.4.2 clock discipline: the three *Elapsed columns are the ONLY mutually comparable
        // timestamps (SystemClock.elapsedRealtime). Added in R3-1 alongside the entity fields;
        // a fresh v5 table has no rows, so INTEGER NOT NULL needs no default. Without these the
        // entity DDL (5.json) would not match the migration DDL and Room schema validation throws.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_cellrebel_executions_attemptId` " +
                "ON `cellrebel_executions`(`attemptId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_cellrebel_executions_executionId` " +
                "ON `cellrebel_executions`(`executionId`)"
        )

        // 3. auto_audit_events — append-only audit stream.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_audit_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `seq` INTEGER NOT NULL,
                `attemptId` INTEGER,
                `correlationRef` TEXT,
                `eventType` TEXT NOT NULL,
                `payloadDigest` TEXT NOT NULL,
                `recordedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_audit_events_attemptId` " +
                "ON `auto_audit_events`(`attemptId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_audit_events_seq` " +
                "ON `auto_audit_events`(`seq`)"
        )

        // 4. legacy_completion_snapshots — write-once, taskId PK.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `legacy_completion_snapshots` (
                `taskId` INTEGER PRIMARY KEY NOT NULL,
                `legacyCompletedSuccesses` INTEGER NOT NULL,
                `legacyStatus` TEXT NOT NULL,
                `migratedFromSchemaVersion` INTEGER NOT NULL,
                `migratedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 5. provider_pairing_records — UNIQUE(applicationId).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_pairing_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `applicationId` TEXT NOT NULL,
                `currentSignerDigest` TEXT NOT NULL,
                `approvedAt` INTEGER NOT NULL,
                `revokedAt` INTEGER,
                `approvedVersionCode` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_provider_pairing_records_applicationId` " +
                "ON `provider_pairing_records`(`applicationId`)"
        )

        // Snapshot pre-A+ progress as LEGACY_UNVERIFIED. Trusted quota stays empty (INV-05/06).
        // location_tasks columns are left in place as frozen display values.
        db.execSQL(
            """
            INSERT INTO legacy_completion_snapshots
                (taskId, legacyCompletedSuccesses, legacyStatus, migratedFromSchemaVersion, migratedAt)
            SELECT id, completedSuccesses, status, 4, $migratedAt FROM location_tasks
            """.trimIndent()
        )
    }
}
