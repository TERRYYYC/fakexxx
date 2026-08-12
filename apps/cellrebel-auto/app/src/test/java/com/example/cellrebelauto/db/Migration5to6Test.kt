package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Real v5→v6 migration evidence (Issue #5 R4-F1, INV-24).
 *
 * A hand-built genuine v5 file database — including a pre-existing `cellrebel_executions` row and a
 * pre-existing `trusted_quota_entries` row — is opened through Room at version 6 with MIGRATION_5_6.
 * v6 only ADDS six nullable §7.1 / §8.6 completion-evidence columns to `cellrebel_executions`
 * (baseline / marker / RUNNING duration / both scores / per-round timestamps) via
 * `ALTER TABLE … ADD COLUMN`. We assert the "必测" guarantees for an additive migration:
 *  - the seeded execution row SURVIVES with every v5 column intact and the six new columns NULL
 *    (non-destructive: ADD COLUMN of a nullable column never rebuilds or loses a row);
 *  - the trusted count is UNCHANGED — a migration NEVER mints a trusted quota (INV-05/06);
 *  - Room opens the migrated DB at v6 without `IllegalStateException` (entity-DDL ≡ post-migration
 *    DDL, including the six new columns) — this is the structural schema-validity check.
 *
 * Mirrors [Migration4to5Test]'s real-fixture technique (spec §6 #5 / line 2898).
 *
 * # 真实 v5→v6 迁移证据：手工 v5 库 → Room v6 + MIGRATION_5_6；旧执行行存活且新列可空，可信计数不变
 */
@RunWith(RobolectricTestRunner::class)
class Migration5to6Test {

    private val dbName = "migration-test-v5to6.db"
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(dbName)
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    /**
     * Builds a genuine v5 database: the full v5 schema (v4 tables + the five trusted-ledger tables
     * created by MIGRATION_4_5) plus every v5 index, then seeds one `cellrebel_executions` row and
     * one `trusted_quota_entries` row so the migration's preservation / no-mint guarantees are
     * observable. DDL mirrors the checked-in `5.json` exactly.
     * # 手工构建真正的 v5 库：完整 v5 schema（v4 表 + MIGRATION_4_5 的五张新表）+ 全部 v5 索引
     */
    private fun createV5Database() {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 5) {
            override fun onCreate(db: SQLiteDatabase) {
                // ---- v4 tables (unchanged through v5) ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `run_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                        "`status` TEXT NOT NULL, `configSnapshot` TEXT NOT NULL, " +
                        "`totalCycles` INTEGER NOT NULL, `planId` INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `test_results` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runSessionId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`webBrowsingScore` REAL NOT NULL, `videoStreamingScore` REAL NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                        "`cycleIndex` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "FOREIGN KEY(`runSessionId`) REFERENCES `run_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_results_runSessionId` ON `test_results`(`runSessionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceFileName` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, " +
                        "`globalBufferSeconds` INTEGER NOT NULL, `totalRows` INTEGER NOT NULL, " +
                        "`totalRequiredSuccesses` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_tasks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` INTEGER NOT NULL, `csvRow` INTEGER NOT NULL, " +
                        "`longitude` REAL NOT NULL, `latitude` REAL NOT NULL, " +
                        "`priority` INTEGER NOT NULL, `requiredSuccesses` INTEGER NOT NULL, " +
                        "`completedSuccesses` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "FOREIGN KEY(`planId`) REFERENCES `location_plans`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_tasks_planId` ON `location_tasks`(`planId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `test_attempts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, `runSessionId` INTEGER NOT NULL, " +
                        "`attemptOrdinal` INTEGER NOT NULL, `successOrdinal` INTEGER, " +
                        "`startedAt` INTEGER NOT NULL, `runningObservedAt` INTEGER, " +
                        "`endedAt` INTEGER, `status` TEXT NOT NULL, `failureReason` TEXT, " +
                        "`webBrowsingScore` REAL, `videoStreamingScore` REAL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                        "`stageNotes` TEXT, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`runSessionId`) REFERENCES `run_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_attempts_taskId` ON `test_attempts`(`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_attempts_runSessionId` ON `test_attempts`(`runSessionId`)")

                // ---- five trusted-ledger tables added by MIGRATION_4_5 (the v5 surface) ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trusted_quota_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`attemptId` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, " +
                        "`evidenceDigest` TEXT NOT NULL, `committedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_quota_entries_attemptId` ON `trusted_quota_entries`(`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trusted_quota_entries_taskId` ON `trusted_quota_entries`(`taskId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cellrebel_executions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`executionId` TEXT NOT NULL, `attemptId` INTEGER NOT NULL, " +
                        "`completionEvidenceWire` INTEGER NOT NULL, " +
                        "`evidencePayloadDigest` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `classifiedAt` INTEGER, " +
                        "`startedAtElapsed` INTEGER NOT NULL, " +
                        "`runningConfirmedAtElapsed` INTEGER NOT NULL, " +
                        "`completedAtElapsed` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cellrebel_executions_attemptId` ON `cellrebel_executions`(`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cellrebel_executions_executionId` ON `cellrebel_executions`(`executionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_audit_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`seq` INTEGER NOT NULL, `attemptId` INTEGER, `correlationRef` TEXT, " +
                        "`eventType` TEXT NOT NULL, `payloadDigest` TEXT NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_auto_audit_events_attemptId` ON `auto_audit_events`(`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_auto_audit_events_seq` ON `auto_audit_events`(`seq`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `legacy_completion_snapshots` (" +
                        "`taskId` INTEGER NOT NULL, `legacyCompletedSuccesses` INTEGER NOT NULL, " +
                        "`legacyStatus` TEXT NOT NULL, `migratedFromSchemaVersion` INTEGER NOT NULL, " +
                        "`migratedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `provider_pairing_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`applicationId` TEXT NOT NULL, `currentSignerDigest` TEXT NOT NULL, " +
                        "`approvedAt` INTEGER NOT NULL, `revokedAt` INTEGER, " +
                        "`approvedVersionCode` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_provider_pairing_records_applicationId` ON `provider_pairing_records`(`applicationId`)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.apply {
            // One execution row carrying ONLY the v5 columns — after 5→6 the six new evidence columns
            // must be NULL on this row (non-destructive ADD COLUMN).
            execSQL(
                "INSERT INTO cellrebel_executions (" +
                    "executionId, attemptId, completionEvidenceWire, evidencePayloadDigest, " +
                    "startedAt, classifiedAt, startedAtElapsed, runningConfirmedAtElapsed, completedAtElapsed" +
                    ") VALUES ('exec-1', 10, 1, 'pd-1', 1000, 1100, 2000, 2100, 13000)"
            )
            // One pre-existing trusted entry — 5→6 must NOT mint or remove trusted quota (INV-05/06).
            execSQL(
                "INSERT INTO trusted_quota_entries (attemptId, taskId, evidenceDigest, committedAt) " +
                    "VALUES (99, 1, 'seed-digest', 5000)"
            )
            close()
        }
        helper.close()
    }

    private fun openRoomDb(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `v5 to v6 migration preserves execution data, adds nullable evidence columns, and mints no trusted quota`() = runTest {
        createV5Database()

        // Opening at v6 runs MIGRATION_5_6 + full schema validation (throws on mismatch, incl. if any
        // of the six new columns were missing or mistyped).
        val db = openRoomDb()

        // (1) The seeded execution row survives with every v5 column intact.
        val row = db.attemptExecutionDao().byExecutionId("exec-1")
        assertNotNull("seeded execution must survive the migration", row)
        val exec = row!!
        assertEquals("exec-1", exec.executionId)
        assertEquals(10L, exec.attemptId)
        assertEquals(1, exec.completionEvidenceWire)
        assertEquals("pd-1", exec.evidencePayloadDigest)
        assertEquals(2000L, exec.startedAtElapsed)
        assertEquals(2100L, exec.runningConfirmedAtElapsed)
        assertEquals(13000L, exec.completedAtElapsed)

        // (2) The six §7.1 / §8.6 evidence columns are PRESENT and NULL on the pre-v6 row — ADD COLUMN
        //     of a nullable column never back-fills or rebuilds. (GREEN populates these from the
        //     evidence detail; a v6 row written by the skeleton entrypoint also leaves them null.)
        assertNull("baseline state nullable", exec.baselineRunningState)
        assertNull("running marker nullable", exec.runningMarkerText)
        assertNull("running duration nullable", exec.runningDurationMs)
        assertNull("web score nullable", exec.webBrowsingScore)
        assertNull("video score nullable", exec.videoStreamingScore)
        assertNull("round timestamps nullable", exec.roundTimestampsElapsed)

        // (3) A migration NEVER mints or removes a trusted quota (INV-05/06): the seeded entry is
        //     still the only one, unchanged by 5→6.
        assertEquals(1, db.trustedQuotaDao().countAll())
        assertEquals(1, db.trustedQuotaDao().trustedCountForTask(1L))
        assertEquals("seed-digest", db.trustedQuotaDao().getByAttempt(99L)!!.evidenceDigest)

        db.close()
    }

    @Test
    fun `v5 database without explicit migration is not silently destroyed`() = runTest {
        createV5Database()

        // Without MIGRATION_5_6 registered, Room at v6 must NOT open a v5 file by destructive
        // fallback — it must throw (INV-24). Opening with NO migration must fail, not wipe data.
        var threw = false
        val noMigrationDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // Intentionally NO addMigrations and NO fallbackToDestructiveMigration.
            .allowMainThreadQueries()
            .build()
        try {
            noMigrationDb.openHelper.writableDatabase // forces open → should throw
        } catch (e: Exception) {
            threw = true
        } finally {
            noMigrationDb.close()
        }
        assertTrue("opening a v5 file at v6 without migration must fail, not destroy", threw)

        // The v5 file is intact (no destructive fallback). Re-opening WITH the migration still works
        // and preserves the seeded execution row.
        val db = openRoomDb()
        assertNotNull(db.attemptExecutionDao().byExecutionId("exec-1"))
        db.close()
    }
}
