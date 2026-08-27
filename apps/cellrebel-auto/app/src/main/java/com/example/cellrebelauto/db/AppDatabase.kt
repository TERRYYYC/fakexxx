package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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
 * Room database singleton, version 6 (F-19; table-for-table identical to v5's committed end-state).
 *
 * v5 introduced the trusted-ledger / execution / audit / legacy-snapshot / provider-pairing tables
 * (MIGRATION_4_5). `cellrebel_executions` is born in v5 carrying its FULL §7.1 / §8.6 completion-
 * evidence field set — digest + 3 elapsed clocks + baseline/marker/RUNNING-duration/both scores/
 * per-round timestamps — because these are the durable evidence a trusted-quota mint binds to, so
 * they belong in the v5 CREATE TABLE, NOT a later ALTER (R5-F5 / INV-24: Issue #5's schema
 * end-state was frozen at version 5).
 *
 * v6 (F-19, 2026-08-27) changes NO table: it exists because a ZY22 device carries a database from
 * an uncommitted ~8/01 build — `user_version = 5` with identity hash
 * `dea7bb1231570ea9fab363e19fc3c9b3` (an abandoned schema branch: five old tables plus eight
 * orphaned GPS columns), while committed v5 expects `0d083aef0412f6d2ad3bbce31bf37f98`. Both sides
 * claim v5, Room only compares identity hashes at equal versions and cannot migrate inside one
 * version, so the app could never open again (frozen crash: g2-auto-crash-triage-20260827).
 * The version bump re-opens a migration window; recovery itself is the v5-drift quarantine +
 * MIGRATION_5_6 no-op + destructive fallback, see [buildProductionDatabase].
 *
 * INV-24 chronicle — DO NOT silently re-freeze or delete: INV-24 (spec §invariants, AC-14) bans
 * destructive fallback so operator data survives upgrades. The operator EXPLICITLY exempted this
 * invariant on 2026-08-27T09:46Z, scoped to Auto only (`com.example.cellrebelauto`, dev-phase app,
 * versionCode=1, stale 8/01 dev rows, sealed in triage raw/). The exemption does NOT extend to the
 * qianwangyou production app (#46 / F-10 — real operator data, separate ruling). The exemption is
 * registered in the spec's INV-24 / AC-14 ledger rows and in docs/features/2026-08-27-f19-*.md.
 *
 * # Room 数据库单例，版本 6（F-19：表结构与 v5 提交终态逐表相同）。v6 只为重开迁移窗口——
 * # 设备存在未提交 8/01 分支的漂移 v5 库，同版本号下 Room 无迁移路径。恢复机构 = v5 漂移隔离区 +
 * # no-op MIGRATION_5_6 + destructive fallback。INV-24 由 operator 2026-08-27T09:46Z 裁定豁免，
 * # 范围仅限 Auto 开发期 app 本次事故，不外溢千网游生产包（#46 / F-10）。
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
    version = 6,
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

        /**
         * The one healthy committed v5 identity hash — `5.json`'s `identityHash`, frozen forever.
         * A `user_version = 5` file bearing ANY other hash is an uncommitted-build drift variant
         * (the ZY22 incident: `dea7bb1231570ea9fab363e19fc3c9b3`) that Room can neither open nor
         * migrate. This constant never needs maintenance: v6+ files skip the quarantine entirely.
         * # 唯一健康的 v5 identity hash（= 5.json，永久冻结）；v6 起的库根本不进隔离区
         */
        internal const val V5_HEALTHY_IDENTITY_HASH = "0d083aef0412f6d2ad3bbce31bf37f98"

        /**
         * F-19 v5-drift quarantine: if the on-disk database claims `user_version = 5` but carries
         * a non-healthy identity hash, it is an uncommitted-build drift variant with no possible
         * migration path (Room cannot migrate within one version). Under the operator's
         * 2026-08-27T09:46Z plan-B ruling (INV-24 exemption, Auto dev app only) the stale file is
         * deleted so Room rebuilds it fresh at the current version.
         *
         * Deliberately narrow: absent file, non-v5 version, healthy hash, or ANY probe failure
         * (missing `room_master_table`, unreadable file) → returns false and touches nothing —
         * v2–v4 devices keep their migration ladder, healthy v5 keeps its data, half-broken files
         * fail loudly in Room instead of being silently destroyed.
         *
         * @return true iff the drifted file (and its -wal/-shm siblings) was deleted.
         * # v5 漂移隔离区：只认「user_version=5 且 hash≠健康值」；探测失败一律不删，删除面收窄
         */
        internal fun quarantineDriftedV5Database(context: Context, dbName: String): Boolean {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists()) return false
            val probe = try {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            } catch (e: Exception) {
                return false // unreadable → leave to Room's own loud failure, never delete blind
            }
            val driftedHash: String? = try {
                if (probe.version != 5) {
                    null
                } else {
                    probe.rawQuery(
                        "SELECT identity_hash FROM room_master_table LIMIT 1", null
                    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?.takeIf { it != V5_HEALTHY_IDENTITY_HASH }
                }
            } catch (e: Exception) {
                null // no room_master_table / probe error → not our case, leave to Room
            } finally {
                probe.close()
            }
            if (driftedHash == null) return false
            Log.w(
                "AppDatabase",
                "F-19 quarantine: deleting drifted v5 database (identity_hash=$driftedHash, " +
                    "expected $V5_HEALTHY_IDENTITY_HASH); rebuilding fresh at current version " +
                    "per operator 2026-08-27 plan-B ruling (INV-24 exemption, Auto only)"
            )
            return SQLiteDatabase.deleteDatabase(dbFile)
        }

        /**
         * Production open path (extracted from [getInstance] so tests can exercise the exact
         * production configuration against fixture files without the singleton).
         *
         * # INV-24 chronicle (2026-08-27) — REWRITTEN, not deleted. Original intent (Issue #5):
         * # 非破坏性迁移：保留历史数据（INV-24：禁用 destructive fallback）。
         * # That ban still governs the qianwangyou production app (#46 / F-10). For Auto it was
         * # EXPLICITLY exempted by the operator on 2026-08-27T09:46Z, scoped to this dev-phase app
         * # (versionCode=1) whose only at-risk rows are stale 8/01 dev data (sealed in
         * # g2-auto-crash-triage-20260827/raw/). Mechanism, in firing order:
         * #   1. v5-drift quarantine (above) — the ZY22 incident path: drifted v5 → delete → fresh v6;
         * #   2. explicit migration ladder 2→3→4→5→6 — v2–v4 devices and healthy v5 KEEP their data
         * #      (MIGRATION_5_6 is a documented no-op; fallback does NOT fire when a path exists);
         * #   3. fallbackToDestructiveMigration — belt for v1/unknown versions with no path.
         */
        internal fun buildProductionDatabase(context: Context, dbName: String): AppDatabase {
            quarantineDriftedV5Database(context, dbName)
            return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildProductionDatabase(context.applicationContext, "cellrebel_auto.db")
                    .also { INSTANCE = it }
            }
        }
    }
}
