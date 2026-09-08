package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.model.plan.LocationTask
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.81 CI-attestation: v9 history keeps NULL serving-cell columns — honest
 * "未捕获" (the observation happened before capture existed) — while v10 writes
 * carry the device-side reading for cross-attestation against the discover
 * `configuredCell*` projection. Additive migration only; no row is touched.
 */
@RunWith(RobolectricTestRunner::class)
class Migration9to10Test {
    private val dbName = "migration-test-v9to10.db"
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

    private fun createCommittedV9() {
        val database = org.json.JSONObject(schema(9).readText()).getJSONObject("database")
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
                "INSERT INTO test_attempts (id, taskId, runSessionId, attemptOrdinal, startedAt, status, latitude, longitude) " +
                    "VALUES (30, 10, 20, 1, 101, 'interrupted', 50.4, 30.5)"
            )
            // A v9 observation row: six serving columns do not exist yet.
            db.execSQL(
                "INSERT INTO durable_observation_records (id, attemptId, phase, leaseId, acceptedIntentHash, coverage, verificationLevel, deliveryMode, isMock, scheduleDecision, effectiveLat, effectiveLng, environmentRevision, environmentFingerprint, observedAtElapsedRealtimeMs, observedAtEpochMs, continuitySinceElapsedRealtimeMs, continuitySinceEpochMs, evidenceRefsJson, evidenceRefs) " +
                    "VALUES (40, 30, 'PRE', 'lease', 'hash', 'FULL', 'SYSTEM_MOCK_INDEPENDENTLY_VERIFIED', 'SYSTEM_MOCK', 1, 'ALLOWED_NOW', 50.4, 30.5, 2, 'fp', 500, 1000, 400, 900, '[]', '')"
            )
            db.version = 9
        }
    }

    @Test fun `v9 observations keep uncaptured serving cells and v10 captures device readings`() = runTest {
        createCommittedV9()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_9_10).allowMainThreadQueries().build()
        try {
            // (a) The migrated v9 row survives with honest "未捕获" columns.
            val migrated = db.durableObservationDao().forAttemptPhase(30L, "PRE")!!
            assertNull(migrated.servingCi)
            assertNull(migrated.servingTac)
            assertNull(migrated.servingPci)
            assertNull(migrated.servingMcc)
            assertNull(migrated.servingMnc)
            assertNull(migrated.servingRsrpDbm)
            assertEquals(10, db.openHelper.readableDatabase.version)

            // (b) A v10 record carries the device-side reading; the attestation
            // query surfaces it for the plan, and skips the uncaptured row.
            db.durableObservationDao().insert(
                com.example.cellrebelauto.model.ledger.DurableObservationRecord(
                    attemptId = 30L, phase = "POST", leaseId = "lease-2",
                    acceptedIntentHash = "hash-2", coverage = "FULL",
                    verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                    deliveryMode = "SYSTEM_MOCK", isMock = true,
                    scheduleDecision = "ALLOWED_NOW", effectiveLat = 50.4, effectiveLng = 30.5,
                    environmentRevision = 3L, environmentFingerprint = "fp-2",
                    observedAtElapsedRealtimeMs = 600L, observedAtEpochMs = 1100L,
                    continuitySinceElapsedRealtimeMs = null, continuitySinceEpochMs = null,
                    evidenceRefsJson = "[]", evidenceRefs = "",
                    servingCi = 289001L, servingTac = 31461, servingPci = 210,
                    servingMcc = "460", servingMnc = "0", servingRsrpDbm = -95,
                )
            )
            val post = db.durableObservationDao().forAttemptPhase(30L, "POST")!!
            assertEquals(289001L, post.servingCi)
            assertEquals(31461, post.servingTac)
            assertEquals(210, post.servingPci)
            assertEquals("460", post.servingMcc)
            assertEquals("0", post.servingMnc)
            assertEquals(-95, post.servingRsrpDbm)

            val attested = db.durableObservationDao().observationsWithServingCellForPlan(1L)
            assertEquals("only captured rows attest", listOf("POST"), attested.map { it.phase })
            assertEquals(289001L, attested.single().servingCi)
        } finally {
            db.close()
        }
    }

    @Test fun `fresh v10 schema validates against the room identity hash`() = runTest {
        // The migration DDL must match the entity DDL exactly (Room validates the
        // schema after migrating) — a fresh v10 open round-trips without error.
        createCommittedV9()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_9_10).allowMainThreadQueries().build()
        try {
            db.durableObservationDao().countForAttempt(30L)
            db.planDao().insertTasks(
                listOf(LocationTask(planId = 1L, csvRow = 2, longitude = 1.0, latitude = 1.0,
                    priority = 2, requiredSuccesses = 1))
            )
        } finally {
            db.close()
        }
    }
}
