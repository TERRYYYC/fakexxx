package com.example.cellrebelauto.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.TestResult
import com.example.cellrebelauto.model.plan.AdvanceRecord
import com.example.cellrebelauto.model.plan.AutoAuditEvent
import com.example.cellrebelauto.model.plan.CellRebelExecution
import com.example.cellrebelauto.model.plan.LegacyCompletionSnapshot
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.ProviderPairingRecord
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.model.plan.TrustedQuotaEntry

/**
 * Room database singleton.
 * # Room 数据库单例，版本 5（Issue #19 全部五张 v5 A+ 表 + advance_records）
 * # Task 4: TrustedQuotaEntry, CellRebelExecution, AutoAuditEvent,
 * #         LegacyCompletionSnapshot, ProviderPairingRecord
 */
@Database(
    entities = [
        TestResult::class, RunSession::class, LocationPlan::class,
        LocationTask::class, TestAttempt::class, AdvanceRecord::class,
        TrustedQuotaEntry::class, CellRebelExecution::class,
        AutoAuditEvent::class, LegacyCompletionSnapshot::class,
        ProviderPairingRecord::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun testResultDao(): TestResultDao
    abstract fun runSessionDao(): RunSessionDao
    abstract fun planDao(): PlanDao
    abstract fun locationTaskDao(): LocationTaskDao
    abstract fun testAttemptDao(): TestAttemptDao
    abstract fun advanceRecordDao(): AdvanceRecordDao
    abstract fun trustedQuotaDao(): TrustedQuotaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 -> v3: run_sessions gains planId; new plan/task/attempt tables.
         * # v2 到 v3：run_sessions 增加 planId；新增计划/任务/尝试三张表
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_sessions ADD COLUMN planId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceFileName` TEXT NOT NULL, " +
                        "`importedAt` INTEGER NOT NULL, " +
                        "`globalBufferSeconds` INTEGER NOT NULL, " +
                        "`totalRows` INTEGER NOT NULL, " +
                        "`totalRequiredSuccesses` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_tasks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` INTEGER NOT NULL, " +
                        "`csvRow` INTEGER NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`requiredSuccesses` INTEGER NOT NULL, " +
                        "`completedSuccesses` INTEGER NOT NULL DEFAULT 0, " +
                        "`status` TEXT NOT NULL DEFAULT 'pending', " +
                        "FOREIGN KEY(`planId`) REFERENCES `location_plans`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_tasks_planId` ON `location_tasks`(`planId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `test_attempts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`runSessionId` INTEGER NOT NULL, " +
                        "`attemptOrdinal` INTEGER NOT NULL, " +
                        "`successOrdinal` INTEGER, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`runningObservedAt` INTEGER, " +
                        "`endedAt` INTEGER, " +
                        "`status` TEXT NOT NULL, " +
                        "`failureReason` TEXT, " +
                        "`webBrowsingScore` REAL, " +
                        "`videoStreamingScore` REAL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`runSessionId`) REFERENCES `run_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_attempts_taskId` ON `test_attempts`(`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_attempts_runSessionId` ON `test_attempts`(`runSessionId`)")
            }
        }

        /**
         * v3 -> v4 (F003): test_attempts gains stageNotes (audit skip marks).
         * Additive only — no data touched.
         * # v3 到 v4（F003）：test_attempts 增加 stageNotes（跳过审计标记），纯增量
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE test_attempts ADD COLUMN stageNotes TEXT")
            }
        }

        /**
         * v4 -> v5 (Issue #19): all five canonical A+ tables (Task 4) +
         * advance_records + legacy data snapshot.
         *
         * Tables created:
         *   1. advance_records — §8.1 durable advance state
         *   2. trusted_quota_entries — §7.3 insert-only quota ledger
         *   3. cellrebel_executions — §8.6 execution evidence
         *   4. auto_audit_events — append-only audit trail
         *   5. legacy_completion_snapshots — v4 progress snapshot (LEGACY_UNVERIFIED)
         *   6. provider_pairing_records — §6.5.3 provider allowlist
         *
         * Legacy data migration: existing completedSuccesses/status from
         * location_tasks are snapshotted into legacy_completion_snapshots.
         * These are LEGACY_UNVERIFIED — they do NOT generate TrustedQuotaEntry
         * rows and do NOT participate in the §7.3 completed projection.
         * Trusted quota starts at zero for all tasks.
         *
         * # v4 到 v5（Issue #19）：全部五张 A+ 表 + advance + 旧进度快照
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // # 1. advance_records: durable advance protocol state (§8.1)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `advance_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, " +
                        "`scheduleId` TEXT NOT NULL, " +
                        "`currentItemId` TEXT NOT NULL, " +
                        "`scheduleVersion` INTEGER NOT NULL, " +
                        "`ledgerRef` TEXT NOT NULL, " +
                        "`verifiedAtElapsedRealtimeMs` INTEGER NOT NULL, " +
                        "`leaseId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`receiptDigest` TEXT, " +
                        "`reason` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_advance_records_taskId` ON `advance_records`(`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_advance_records_state` ON `advance_records`(`state`)")

                // # 2. trusted_quota_entries: insert-only quota ledger (§7.3, M-AD-14)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trusted_quota_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`attemptId` INTEGER NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`evidenceDigest` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`attemptId`) REFERENCES `test_attempts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_quota_entries_attemptId` ON `trusted_quota_entries`(`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trusted_quota_entries_taskId` ON `trusted_quota_entries`(`taskId`)")

                // # 3. cellrebel_executions: §8.6 execution evidence
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cellrebel_executions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`attemptId` INTEGER NOT NULL, " +
                        "`executionId` TEXT NOT NULL, " +
                        "`completionVerdict` TEXT NOT NULL, " +
                        "`startedAtElapsed` INTEGER NOT NULL, " +
                        "`endedAtElapsed` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`attemptId`) REFERENCES `test_attempts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cellrebel_executions_attemptId` ON `cellrebel_executions`(`attemptId`)")

                // # 4. auto_audit_events: append-only audit trail
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_audit_events` (" +
                        "`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`correlationSessionId` INTEGER, " +
                        "`correlationAttemptId` INTEGER, " +
                        "`correlationAdvanceRecordId` INTEGER, " +
                        "`event` TEXT NOT NULL, " +
                        "`payloadDigest` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )

                // # 5. legacy_completion_snapshots: v4 progress (LEGACY_UNVERIFIED)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `legacy_completion_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`legacyCompletedSuccesses` INTEGER NOT NULL, " +
                        "`legacyStatus` TEXT NOT NULL, " +
                        "`migratedFromSchemaVersion` INTEGER NOT NULL, " +
                        "`migratedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_legacy_completion_snapshots_taskId` ON `legacy_completion_snapshots`(`taskId`)")

                // # 6. provider_pairing_records: §6.5.3 provider allowlist
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `provider_pairing_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`applicationId` TEXT NOT NULL, " +
                        "`currentSignerDigest` TEXT NOT NULL, " +
                        "`approvedVersionCode` INTEGER NOT NULL, " +
                        "`approvedAt` INTEGER NOT NULL, " +
                        "`revokedAt` INTEGER)"
                )

                // # Legacy data snapshot: preserve existing progress as
                // # LEGACY_UNVERIFIED. Trusted quota starts at zero.
                // # §7.3: LegacyCompletionSnapshot does NOT participate
                // # in the completed projection.
                db.execSQL(
                    "INSERT INTO `legacy_completion_snapshots` " +
                        "(`taskId`, `legacyCompletedSuccesses`, `legacyStatus`, " +
                        " `migratedFromSchemaVersion`, `migratedAt`) " +
                        "SELECT `id`, `completedSuccesses`, `status`, 4, " +
                        "strftime('%s','now') * 1000 FROM `location_tasks` " +
                        "WHERE `completedSuccesses` > 0"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cellrebel_auto.db"
                )
                    // # 非破坏性迁移：保留历史数据
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
