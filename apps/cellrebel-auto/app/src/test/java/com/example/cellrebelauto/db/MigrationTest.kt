package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Real migration + persistence evidence (AC-A4, F10):
 * a hand-built v2 file database is opened through Room with MIGRATION_2_3,
 * and an imported plan's progress survives close/reopen.
 * # 真实迁移与持久化证据：手工构建 v2 文件库 → Room + MIGRATION_2_3 打开校验；
 * # 导入的计划进度经 close/reopen 存活
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val dbName = "migration-test.db"
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
     * Builds a genuine v2 database (legacy schema only, no planId, no plan tables).
     * # 手工构建真正的 v2 库（旧 schema：无 planId、无计划三表）
     */
    private fun createV2Database() {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `run_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                        "`status` TEXT NOT NULL, `configSnapshot` TEXT NOT NULL, " +
                        "`totalCycles` INTEGER NOT NULL)"
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
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO run_sessions (startedAt, endedAt, status, configSnapshot, totalCycles) " +
                    "VALUES (1000, 2000, 'completed', 'legacy-config', 5)"
            )
            execSQL(
                "INSERT INTO test_results (runSessionId, timestamp, webBrowsingScore, " +
                    "videoStreamingScore, latitude, longitude, cycleIndex, status) " +
                    "VALUES (1, 1500, 8.5, 7.5, 39.9, 116.4, 1, 'ok')"
            )
            close()
        }
        helper.close()
    }

    // # 迁移链必须完整到当前 DB 版本(v5)：v2/v3 文件经 2→3→4→5 全链打开 + schema 校验
    private fun openRoomDb(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, MIGRATION_4_5)
            .build()

    /**
     * Builds a genuine v3 database (v2 + plan tables, WITHOUT stageNotes).
     * # 手工构建真正的 v3 库（v2 全部 + 计划三表，无 stageNotes 列）
     */
    private fun createV3Database() {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                // # v2 两张旧表
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
                // # v3 三张计划表（test_attempts 无 stageNotes）
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
            execSQL("INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) VALUES (1, 'sites.csv', 900, 60, 1, 1)")
            execSQL("INSERT INTO location_tasks (id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) VALUES (1, 1, 1, 116.4, 39.9, 1, 1, 1, 'completed')")
            execSQL(
                "INSERT INTO test_attempts (taskId, runSessionId, attemptOrdinal, successOrdinal, startedAt, runningObservedAt, endedAt, status, failureReason, webBrowsingScore, videoStreamingScore, latitude, longitude) " +
                    "VALUES (1, 1, 1, 1, 1100, 1150, 1200, 'succeeded', NULL, 8.0, 7.0, 39.9, 116.4)"
            )
            close()
        }
        helper.close()
    }

    @Test
    fun `migration 3 to 4 adds stageNotes and preserves attempt data`() = runTest {
        createV3Database()

        // # Room v4 打开：跑 MIGRATION_3_4 + schema 校验（不通过会抛异常）
        val db = openRoomDb()

        // # v3 数据完整保留，stageNotes 迁移后为 null
        val attempts = db.testAttemptDao().getAttemptsForTask(1L)
        assertEquals(1, attempts.size)
        assertEquals("succeeded", attempts[0].status)
        assertEquals(1, attempts[0].successOrdinal)
        assertNull(attempts[0].stageNotes)
        assertEquals(8.0, attempts[0].webBrowsingScore!!, 0.001)

        // # 新列可写（F003 审计标记）
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = 2000L, planId = 1L)
        )
        val newAttemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = 1L, runSessionId = sessionId, attemptOrdinal = 2,
                successOrdinal = null, startedAt = 2100L, runningObservedAt = null,
                endedAt = 2200L, status = "failed", failureReason = "CELLREBEL_TIMEOUT",
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4, stageNotes = "gps_skipped"
            )
        )
        val written = db.testAttemptDao().getAttemptsForTask(1L)
            .first { it.id == newAttemptId }
        assertEquals("gps_skipped", written.stageNotes)

        db.close()
    }

    @Test
    fun `migration 2 to 3 preserves legacy data and creates plan tables`() = runTest {
        createV2Database()

        // # Room 打开即跑 MIGRATION_2_3 + 全量 schema 校验（不通过会抛异常）
        val db = openRoomDb()

        // # 旧数据保留：legacy session 与 result 都在，planId 迁移后为 null
        val session = db.runSessionDao().getLatest()
        assertEquals("legacy-config", session!!.configSnapshot)
        assertEquals(5, session.totalCycles)
        assertNull(session.planId)
        val results = db.testResultDao().getAllResultsForExport()
        assertEquals(1, results.size)
        assertEquals(8.5, results[0].webBrowsingScore, 0.001)

        // # 新表存在且可写（计划/任务/尝试）
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 3000L,
                globalBufferSeconds = 60, totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 1
                )
            )
        )
        assertTrue(planId > 0)
        assertEquals(1, db.locationTaskDao().getTasksForPlan(planId).size)

        db.close()
    }

    @Test
    fun `imported plan progress survives close and reopen`() = runTest {
        // # 第一次打开：导入计划并写入一次已验证成功
        var db = openRoomDb()
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 3000L,
                globalBufferSeconds = 60, totalRows = 1, totalRequiredSuccesses = 2
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 2
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 3100L, planId = planId))
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 3200L, runningObservedAt = 3250L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )
        com.example.cellrebelauto.repository.PlanRepository(db).finalizeAttemptSuccess(
            attemptId = attemptId, taskId = taskId, expectedCompletedSuccesses = 0,
            runningObservedAt = 3250L, endedAt = 3300L, webScore = 8.0, videoScore = 7.0
        )
        db.close()

        // # 重新打开同一文件库：计划、进度、尝试行全部存活
        db = openRoomDb()
        val plan = db.planDao().getPlanById(planId)!!
        assertEquals("sites.csv", plan.sourceFileName)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).first()
        assertEquals("succeeded", attempt.status)
        assertEquals(1, attempt.successOrdinal)
        assertEquals(8.0, attempt.webBrowsingScore!!, 0.001)
        db.close()
    }
}
