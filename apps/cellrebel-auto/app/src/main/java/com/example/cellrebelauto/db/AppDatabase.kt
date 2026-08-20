package com.example.cellrebelauto.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.TestResult
import com.example.cellrebelauto.model.audit.AutoAuditEvent
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.LegacyCompletionSnapshot
import com.example.cellrebelauto.model.plan.ProviderPairingRecord
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
import com.example.cellrebelauto.model.ledger.DurableCompletionReceipt
import com.example.cellrebelauto.recovery.OperationReceiptRow
import com.example.cellrebelauto.recovery.RecoveryCheckpointRow
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import com.example.cellrebelauto.recovery.OperationReceiptDao
import com.example.cellrebelauto.recovery.RecoveryCheckpointRoomDao
import com.example.cellrebelauto.recovery.ReleaseReceiptDao

/**
 * Room database singleton, version 5 (Issue #5: trusted ledger + A+ execution tables).
 *
 * v5 introduces the trusted-ledger / execution / audit / legacy-snapshot / provider-pairing tables
 * (MIGRATION_4_5). `cellrebel_executions` is born in v5 carrying its FULL §7.1 / §8.6 completion-
 * evidence field set — digest + 3 elapsed clocks + baseline/marker/RUNNING-duration/both scores/
 * per-round timestamps — because these are the durable evidence a trusted-quota mint binds to, so
 * they belong in the v5 CREATE TABLE, NOT a later ALTER (R5-F5 / INV-24: this task's schema end-state
 * is exactly version 5). The schema JSON is exported for version control; `fallbackToDestructiveMigration`
 * is intentionally never used — see MIGRATION_4_5.
 *
 * # Room 数据库单例，版本 5（可信账本 + A+ 执行表）；cellrebel_executions 建表即带 §7.1 全证据列；exportSchema=true；禁用 destructive fallback
 */
@Database(
    entities = [
        TestResult::class,
        RunSession::class,
        LocationPlan::class,
        LocationTask::class,
        TestAttempt::class,
        TrustedQuotaEntry::class,
        CellRebelExecution::class,
        AutoAuditEvent::class,
        LegacyCompletionSnapshot::class,
        ProviderPairingRecord::class,
        UnverifiedAttemptRecord::class,
        DurableObservationRecord::class,
        DurableCompletionReceipt::class,
        OperationReceiptRow::class,
        RecoveryCheckpointRow::class,
        ReleaseReceiptRow::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun testResultDao(): TestResultDao
    abstract fun runSessionDao(): RunSessionDao
    abstract fun planDao(): PlanDao
    abstract fun locationTaskDao(): LocationTaskDao
    abstract fun testAttemptDao(): TestAttemptDao

    // Issue #5 Task 4 — trusted ledger & friends.
    abstract fun trustedQuotaDao(): TrustedQuotaDao
    abstract fun attemptExecutionDao(): AttemptExecutionDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun legacyCompletionDao(): LegacyCompletionDao
    abstract fun providerPairingDao(): ProviderPairingDao
    abstract fun unverifiedAttemptRecordDao(): UnverifiedAttemptRecordDao
    abstract fun durableObservationDao(): DurableObservationDao
    abstract fun durableCompletionReceiptDao(): DurableCompletionReceiptDao
    abstract fun operationReceiptDao(): OperationReceiptDao
    abstract fun recoveryCheckpointRoomDao(): RecoveryCheckpointRoomDao
    abstract fun releaseReceiptDao(): ReleaseReceiptDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cellrebel_auto.db"
                )
                    // # 非破坏性迁移：保留历史数据（INV-24：禁用 destructive fallback）
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
