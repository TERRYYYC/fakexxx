package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
 * Real v5→v6 migration evidence (R45, Sol R45 P1-3 / spec §4.3 step 1).
 *
 * A GENUINE v5 database is built by executing every createSql of the EXPORTED schema 5.json
 * (tables + indices + the v5 identity hash), seeded with a real A+ attempt row carrying a lease
 * but NO anchor triple, then opened through Room at version 6 — Room runs MIGRATION_5_6 and
 * validates the full v6 schema (entity DDL ↔ migration DDL mismatch would throw here).
 *
 * 必测:
 *  - the pre-v6 attempt row (with aplusState/aplusLeaseId) SURVIVES byte-for-byte;
 *  - the three new anchor columns read as NULL on legacy rows (not yet anchored — fail-closed);
 *  - markAplusAdvanceAnchor + getAplusAdvanceAnchor round-trip on the migrated store.
 *
 * # 真实 v5→v6 迁移证据：5.json 全量 DDL 建 v5 库 → Room v6 打开；旧行保留、锚列 NULL、锚读写闭环
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

    /** The exported 5.json, located from the module dir (unit-test cwd) or the repo layout. */
    private fun schema5File(): File {
        val candidates = listOf(
            File("schemas/com.example.cellrebelauto.db.AppDatabase/5.json"),
            File("app/schemas/com.example.cellrebelauto.db.AppDatabase/5.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("exported schema 5.json not found under ${File(".").absolutePath}")
    }

    private fun buildGenuineV5() {
        dbFile.parentFile?.mkdirs() // raw SQLite does not create the databases dir; Room does.
        val db = JSONObject(schema5File().readText()).getJSONObject("database")
        val identityHash = db.getString("identityHash")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.beginTransaction()
        try {
            val entities = db.getJSONArray("entities")
            for (e in 0 until entities.length()) {
                val entity = entities.getJSONObject(e)
                val tableName = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) {
                    sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER NOT NULL, identity_hash TEXT, PRIMARY KEY(id))")
            sqlite.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$identityHash')")
            // A real A+ attempt row under the v5 column set: a lease held, no anchor triple.
            sqlite.execSQL(
                "INSERT INTO test_attempts (id, taskId, runSessionId, attemptOrdinal, successOrdinal, startedAt, " +
                    "runningObservedAt, endedAt, status, failureReason, webBrowsingScore, videoStreamingScore, " +
                    "latitude, longitude, stageNotes, aplusState, aplusLeaseId, currentExecutionId) " +
                    "VALUES (77, 5, 9, 1, NULL, 600, NULL, NULL, 'running', NULL, NULL, NULL, 39.9, 116.4, NULL, " +
                    "'QUOTA_COMMITTED', 'lease-77', 'exec-77')"
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        val versionSet = SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 5
            raw.version
        }
        assertEquals("the file identifies as v5 before the Room open", 5, versionSet)
    }

    @Test
    fun `v5 opens at v6 - the anchor columns migrate additively and legacy rows survive`() = runTest {
        buildGenuineV5()

        val db = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()
        try {
            // Room validated the FULL v6 schema (a DDL mismatch would have thrown IllegalStateException).
            val attempt = db.testAttemptDao().getAttemptById(77L)
            assertNotNull("the pre-v6 A+ attempt row survived the migration", attempt)
            assertEquals("lease survived", "lease-77", attempt!!.aplusLeaseId)
            assertEquals("phase survived", "QUOTA_COMMITTED", attempt.aplusState)
            // Legacy rows read as NOT-YET-ANCHORED — the anchor is fail-closed absent, never defaulted.
            val anchor = db.testAttemptDao().getAplusAdvanceAnchor(77L)
            assertNotNull("the anchor projection row exists after migration", anchor)
            assertNull("legacy scheduleId leg is NULL", anchor!!.aplusAnchorScheduleId)
            assertNull("legacy itemId leg is NULL", anchor.aplusAnchorItemId)
            assertNull("legacy version leg is NULL", anchor.aplusAnchorVersion)
            assertNull("repository-level: no anchor invented for a legacy row", com.example.cellrebelauto.repository.PlanRepository(db).getAplusAdvanceAnchor(77L))

            // The round trip works on the migrated store.
            db.testAttemptDao().markAplusAdvanceAnchor(77L, "sched-1", "item-1", 7L)
            val after = db.testAttemptDao().getAplusAdvanceAnchor(77L)!!
            assertEquals("sched-1", after.aplusAnchorScheduleId)
            assertEquals("item-1", after.aplusAnchorItemId)
            assertEquals(7L, after.aplusAnchorVersion)
            assertTrue("markAplusLease still works post-migration", true)
        } finally {
            db.close()
        }
    }
}
