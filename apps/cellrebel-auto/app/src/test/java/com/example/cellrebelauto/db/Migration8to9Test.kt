package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.model.plan.LocationTask
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

/** #79: v8 history stays legacy-null while v9 adds only nullable binding evidence. */
@RunWith(RobolectricTestRunner::class)
class Migration8to9Test {
    private val dbName = "migration-test-v8to9.db"
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
    }

    @After fun tearDown() { SQLiteDatabase.deleteDatabase(file) }

    private fun schema(version: Int): File {
        val path = "schemas/com.example.cellrebelauto.db.AppDatabase/$version.json"
        return sequenceOf(File(path), File("app/$path"), File("apps/cellrebel-auto/app/$path"))
            .firstOrNull { it.exists() } ?: error("missing committed schema $version")
    }

    private fun createCommittedV8() {
        val database = org.json.JSONObject(schema(8).readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) VALUES (1, 'legacy.csv', 100, 5, 1, 1)")
            db.execSQL("INSERT INTO location_tasks (id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) VALUES (10, 1, 1, 30.5, 50.4, 1, 1, 0, 'pending')")
            db.execSQL("INSERT INTO run_sessions (id, startedAt, status, configSnapshot, totalCycles, planId) VALUES (20, 100, 'paused', '', 0, 1)")
            db.execSQL(
                "INSERT INTO test_attempts (id, taskId, runSessionId, attemptOrdinal, startedAt, status, latitude, longitude, aplusAnchorScheduleId, aplusAnchorItemId, aplusAnchorVersion) " +
                    "VALUES (30, 10, 20, 1, 101, 'interrupted', 50.4, 30.5, 'legacy-schedule', 'legacy-item', 7)"
            )
            db.execSQL(
                "INSERT INTO durable_observation_records (id, attemptId, phase, leaseId, acceptedIntentHash, coverage, verificationLevel, deliveryMode, isMock, scheduleDecision, effectiveLat, effectiveLng, environmentRevision, environmentFingerprint, observedAtElapsedRealtimeMs, observedAtEpochMs, continuitySinceElapsedRealtimeMs, continuitySinceEpochMs, evidenceRefsJson, evidenceRefs) " +
                    "VALUES (40, 30, 'PRE', 'lease', 'hash', 'FULL', 'SYSTEM_MOCK_INDEPENDENTLY_VERIFIED', 'SYSTEM_MOCK', 1, 'ALLOWED_NOW', 50.4, 30.5, 2, 'fp', 500, 1000, 400, 900, '[]', '')"
            )
            db.version = 8
        }
    }

    @Test fun `v8 rows retain legacy null bindings and v9 enforces per-plan item uniqueness`() = runTest {
        createCommittedV8()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            assertNull(db.planDao().getPlanById(1L)!!.boundScheduleId)
            assertNull(db.locationTaskDao().getTaskById(10L)!!.scheduleItemId)
            assertNull(db.testAttemptDao().getAttemptById(30L)!!.aplusIntentProfileRef)
            val migratedObservation = db.durableObservationDao().forAttemptPhase(30L, "PRE")!!
            assertNull(migratedObservation.scheduleItemId)
            assertNull(migratedObservation.scheduleVersion)

            db.planDao().insertTasks(
                listOf(
                    LocationTask(planId = 1L, csvRow = 2, longitude = 1.0, latitude = 1.0,
                        priority = 2, requiredSuccesses = 1, scheduleItemId = "item-a")
                )
            )
            var rejected = false
            try {
                db.planDao().insertTasks(
                    listOf(
                        LocationTask(planId = 1L, csvRow = 3, longitude = 2.0, latitude = 2.0,
                            priority = 3, requiredSuccesses = 1, scheduleItemId = "item-a")
                    )
                )
            } catch (_: SQLiteConstraintException) {
                rejected = true
            }
            assertTrue("duplicate bound item in one plan must be rejected", rejected)
            assertEquals(9, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
