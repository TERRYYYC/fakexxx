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
 * Real v4→v5 migration evidence (Issue #5 Task 4, INV-24, M-MG-01/03).
 *
 * A hand-built genuine v4 file database — including a task with non-zero `completedSuccesses` and
 * an active/completed plan — is opened through Room at version 5 with MIGRATION_4_5. We then assert
 * the "必测" list from spec line 2898:
 *  - legacy progress is preserved as LEGACY_UNVERIFIED (visible, not trusted);
 *  - `trusted_quota_entries` is empty (trusted quota starts from 0);
 *  - `LocationTask.completed` projection is false (no trusted entries ⇒ not complete);
 *  - `provider_pairing_records` is created and EMPTY (upgrade mints no trusted provider);
 *  - all historical plan/task/attempt/result rows survive.
 *
 * The schema-validity (entity DDL ↔ migration DDL match) is itself verified by Room opening the
 * migrated DB without `IllegalStateException` under exportSchema=true.
 *
 * # 真实 v4→v5 迁移证据：手工 v4 库 → Room v5 + MIGRATION_4_5；旧进度=LEGACY_UNVERIFIED，可信从 0，provider 表空
 */
@RunWith(RobolectricTestRunner::class)
class Migration4to5Test {

    private val dbName = "migration-test-v4to5.db"
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
     * Builds a genuine v4 database (legacy + plan tables + stageNotes), with one task that has
     * completedSuccesses = 2 and status = 'completed' against requiredSuccesses = 3 — so legacy
     * progress is non-zero and the task is NOT actually complete under A+ evidence.
     * # 手工构建真正的 v4 库：含非零 completedSuccesses 的 active/completed plan
     */
    private fun createV4Database() {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 4) {
            override fun onCreate(db: SQLiteDatabase) {
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_test_results_runSessionId` " +
                        "ON `test_results`(`runSessionId`)"
                )
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
                        "`completedSuccesses` INTEGER NOT NULL DEFAULT 0, " +
                        "`status` TEXT NOT NULL DEFAULT 'pending', " +
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
                        // v4 (F003) column:
                        "`stageNotes` TEXT, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`runSessionId`) REFERENCES `run_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_attempts_taskId` ON `test_attempts`(`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_attempts_runSessionId` ON `test_attempts`(`runSessionId`)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.apply {
            execSQL("INSERT INTO run_sessions (startedAt, endedAt, status, configSnapshot, totalCycles, planId) VALUES (1000, NULL, 'running', 'plan:1', 0, 1)")
            execSQL("INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) VALUES (1, 'sites.csv', 900, 60, 1, 3)")
            // Legacy task: 2 unverified successes against a quota of 3.
            execSQL("INSERT INTO location_tasks (id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) VALUES (1, 1, 1, 116.4, 39.9, 1, 3, 2, 'completed')")
            execSQL(
                "INSERT INTO test_attempts (taskId, runSessionId, attemptOrdinal, successOrdinal, startedAt, runningObservedAt, endedAt, status, failureReason, webBrowsingScore, videoStreamingScore, latitude, longitude, stageNotes) " +
                    "VALUES (1, 1, 1, 1, 1100, 1150, 1200, 'succeeded', NULL, 8.0, 7.0, 39.9, 116.4, NULL)"
            )
            close()
        }
        helper.close()
    }

    private fun openRoomDb(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // 本测试聚焦 4→5 的数据语义；当前 DB 版本 v6，故补 5→6（R9 aplusState/lease + unverified 表）。
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `v4 to v5 migration preserves legacy data and snapshots legacy progress as unverified`() = runTest {
        createV4Database()

        // Opening at v5 runs MIGRATION_4_5 + full schema validation (throws on mismatch).
        val db = openRoomDb()

        // (1) Historical plan/task/attempt survive untouched.
        val task = db.locationTaskDao().getTaskById(1L)!!
        assertEquals(3, task.requiredSuccesses)
        // The v4 columns are left in place as frozen display values.
        assertEquals(2, task.completedSuccesses)
        assertEquals("completed", task.status)
        val attempts = db.testAttemptDao().getAttemptsForTask(1L)
        assertEquals(1, attempts.size)
        assertEquals("succeeded", attempts[0].status)

        // (2) Legacy progress snapshotted as LEGACY_UNVERIFIED (M-MG-01).
        val snapshot = db.legacyCompletionDao().forTask(1L)
        assertNotNull("legacy snapshot must exist after migration", snapshot)
        assertEquals(2, snapshot!!.legacyCompletedSuccesses)
        assertEquals("completed", snapshot.legacyStatus)
        assertEquals(4, snapshot.migratedFromSchemaVersion)
        assertTrue("migratedAt must be set", snapshot.migratedAt > 0)

        // (3) Trusted quota starts from 0 — migration NEVER mints TrustedQuotaEntry (INV-05/06).
        assertEquals(0, db.trustedQuotaDao().trustedCountForTask(1L))
        assertEquals(0, db.trustedQuotaDao().countAll())

        // (4) Completion projection is false: 0 trusted < requiredSuccesses 3, even though the
        //     legacy snapshot says 2. LegacyCompletionSnapshot must NOT participate (§7.3).
        val trustedCount = db.trustedQuotaDao().trustedCountForTask(1L)
        assertTrue(
            "task must NOT be complete from legacy counts alone (trusted=$trustedCount < required=3)",
            trustedCount < task.requiredSuccesses
        )

        // (5) ProviderPairingRecord created and EMPTY — upgrade mints no trusted provider (§6.5.3).
        assertEquals(0, db.providerPairingDao().count())
        assertNull(db.providerPairingDao().activeFor("any.application.id"))

        // (6) The new audit table exists and is empty.
        assertEquals(0, db.auditEventDao().count())

        db.close()
    }

    @Test
    fun `v4 database without explicit migration is not silently destroyed`() = runTest {
        createV4Database()

        // Without MIGRATION_4_5 registered, Room at v5 must NOT open a v4 file by destructive
        // fallback — it must throw (INV-24). We assert the absence of a destructive path: opening
        // with NO migration throws rather than wiping operator data.
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
        assertTrue("opening a v4 file at v5 without migration must fail, not destroy", threw)

        // The v4 file itself is intact (we did not register destructive fallback).
        // Re-opening WITH the migration still works and preserves data.
        val db = openRoomDb()
        assertNotNull(db.locationTaskDao().getTaskById(1L))
        db.close()
    }
}
