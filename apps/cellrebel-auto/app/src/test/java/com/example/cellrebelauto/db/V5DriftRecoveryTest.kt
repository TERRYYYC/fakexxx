package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression evidence for the Auto main-launch P0 (2026-08-27 operator ruling, plan B).
 *
 * A device installed from an UNCOMMITTED ~8/01 build carries `cellrebel_auto.db` with
 * `user_version = 5` and `room_master_table = 42|dea7bb1231570ea9fab363e19fc3c9b3`, while every
 * committed v5 build expects identity hash `0d083aef0412f6d2ad3bbce31bf37f98`. Both sides claim
 * version 5, so Room never runs a migration and throws
 * `IllegalStateException: Room cannot verify the data integrity` on first DB touch — the app can
 * never start on that device again (frozen crash: g2-auto-crash-triage-20260827/probe-d3).
 *
 * The fixture below replicates the sealed device DB at the schema level
 * (triage probe-c7/c8/c9 + `.schema` of the sealed query copy): the five old entities — including
 * the eight orphaned GPS-verification columns on `test_attempts` that no committed schema ever
 * had — plus the drifted master row.
 *
 * RED (pre-fix, main): `getInstance` → first query crashes with the exact device exception.
 * GREEN (post-fix): the v5-drift quarantine detects hash != healthy-v5, deletes the stale dev DB,
 * and Room rebuilds at v6 — app opens, drifted data gone (operator-sanctioned, INV-24 exemption
 * scoped to Auto; see AppDatabase INV-24 chronicle).
 *
 * # Auto 启动闪退回归证据：复刻设备漂移 v5 库（8/01 未提交构建产物）。修复前 getInstance 必崩；
 * # 修复后隔离区删除漂移库并以 v6 重建（operator 2026-08-27 裁定 B，INV-24 范围内豁免）。
 */
@RunWith(RobolectricTestRunner::class)
class V5DriftRecoveryTest {

    /** Production database name — this test intentionally exercises the real getInstance path. */
    private val prodDbName = "cellrebel_auto.db"
    private lateinit var context: Context
    private lateinit var prodDbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prodDbFile = context.getDatabasePath(prodDbName)
        prodDbFile.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(prodDbFile)
    }

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(prodDbFile)
    }

    /**
     * Replicates the sealed ZY22 device database (g2-auto-crash-triage-20260827):
     * user_version=5, identity hash dea7bb12…, five old entities only — `test_attempts` carries
     * the eight orphaned GPS-verification columns of the abandoned 8/01 branch; none of the
     * eleven A+ v5 tables exist.
     * # 逐字复刻封存设备库：user_version=5 + 漂移 identity hash + 8/01 分支特有孤儿列
     */
    private fun createDeviceReplicaDriftedV5(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.use {
            it.execSQL(
                "CREATE TABLE `test_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`runSessionId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`webBrowsingScore` REAL NOT NULL, `videoStreamingScore` REAL NOT NULL, " +
                    "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                    "`cycleIndex` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "FOREIGN KEY(`runSessionId`) REFERENCES `run_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            it.execSQL(
                "CREATE INDEX `index_test_results_runSessionId` ON `test_results` (`runSessionId`)"
            )
            it.execSQL(
                "CREATE TABLE `run_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `status` TEXT NOT NULL, " +
                    "`configSnapshot` TEXT NOT NULL, `totalCycles` INTEGER NOT NULL, `planId` INTEGER)"
            )
            it.execSQL(
                "CREATE TABLE `location_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sourceFileName` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, " +
                    "`globalBufferSeconds` INTEGER NOT NULL, `totalRows` INTEGER NOT NULL, " +
                    "`totalRequiredSuccesses` INTEGER NOT NULL)"
            )
            it.execSQL(
                "CREATE TABLE `location_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`planId` INTEGER NOT NULL, `csvRow` INTEGER NOT NULL, `longitude` REAL NOT NULL, " +
                    "`latitude` REAL NOT NULL, `priority` INTEGER NOT NULL, " +
                    "`requiredSuccesses` INTEGER NOT NULL, `completedSuccesses` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "FOREIGN KEY(`planId`) REFERENCES `location_plans`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            it.execSQL("CREATE INDEX `index_location_tasks_planId` ON `location_tasks` (`planId`)")
            it.execSQL(
                "CREATE TABLE `test_attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`taskId` INTEGER NOT NULL, `runSessionId` INTEGER NOT NULL, " +
                    "`attemptOrdinal` INTEGER NOT NULL, `successOrdinal` INTEGER, " +
                    "`startedAt` INTEGER NOT NULL, `runningObservedAt` INTEGER, `endedAt` INTEGER, " +
                    "`status` TEXT NOT NULL, `failureReason` TEXT, `webBrowsingScore` REAL, " +
                    "`videoStreamingScore` REAL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                    "`stageNotes` TEXT, " +
                    // The abandoned 8/01 branch's GPS-verification columns — present on the device,
                    // absent from every committed schema. This is what makes the drift a *branch*,
                    // not a subset. # 8/01 分支孤儿列：设备有、任何已提交 schema 都没有
                    "`actualLatitude` REAL, `actualLongitude` REAL, `locationErrorMeters` REAL, " +
                    "`fixIsMock` INTEGER, `fixAt` INTEGER, `verifiedAt` INTEGER, " +
                    "`fixAccuracyMeters` REAL, `toleranceMetersUsed` REAL, " +
                    "FOREIGN KEY(`taskId`) REFERENCES `location_tasks`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`runSessionId`) REFERENCES `run_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            it.execSQL("CREATE INDEX `index_test_attempts_taskId` ON `test_attempts` (`taskId`)")
            it.execSQL(
                "CREATE INDEX `index_test_attempts_runSessionId` ON `test_attempts` (`runSessionId`)"
            )
            // Stale 8/01-era operator dev data (device really carries rows; presence matters for
            // the rebuild assertion). # 设备真实带数据，重建断言需要非空起点
            it.execSQL(
                "INSERT INTO run_sessions (startedAt, endedAt, status, configSnapshot, totalCycles) " +
                    "VALUES (1000, 2000, 'completed', 'drift-marker-config', 3)"
            )
            it.execSQL(
                "INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, " +
                    "totalRows, totalRequiredSuccesses) VALUES (1, 'aug01.csv', 900, 60, 1, 1)"
            )
            // Room master table exactly as frozen by probe-c7: 42|dea7bb1231570ea9fab363e19fc3c9b3.
            it.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            it.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) " +
                    "VALUES (42, 'dea7bb1231570ea9fab363e19fc3c9b3')"
            )
            it.version = 5 // probe-c8: PRAGMA user_version = 5
        }
    }

    /**
     * THE P0 itself. Pre-fix this fails with the exact device crash
     * (`Room cannot verify the data integrity … Expected identity hash: 0d083aef… found: dea7bb12…`).
     * Post-fix the production open path must quarantine the drifted file and rebuild at the
     * current version — the app starts, the stale 8/01 data is gone, the A+ tables are usable.
     * # P0 本体：修复前 = 设备同款崩溃；修复后 = 隔离重建、可正常启动
     */
    @Test
    fun `drifted v5 device replica opens through production path and is rebuilt instead of crashing`() =
        runTest {
            createDeviceReplicaDriftedV5(prodDbFile)

            // Production entry point — the same call MainActivity's stack performs.
            val db = AppDatabase.getInstance(context)
            try {
                // First real DB touch: pre-fix this is the crash site.
                val latest = db.runSessionDao().getLatest()

                // Rebuilt: the stale 8/01 rows are gone (operator-sanctioned data clear).
                assertNull("drifted DB must be rebuilt empty, not carried over", latest)

                // The rebuilt DB is a genuine current-version database: A+ tables exist and work.
                assertEquals(0, db.trustedQuotaDao().countAll())

                val version = db.openHelper.readableDatabase.version
                assertEquals("rebuilt database must be at the current schema version", 6, version)

                // And the drifted master row is gone for good.
                val hash = db.openHelper.readableDatabase
                    .query("SELECT identity_hash FROM room_master_table LIMIT 1")
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
                assertFalse(
                    "drifted identity hash must not survive the rebuild",
                    hash == "dea7bb1231570ea9fab363e19fc3c9b3"
                )
            } finally {
                db.close()
            }
        }
}
