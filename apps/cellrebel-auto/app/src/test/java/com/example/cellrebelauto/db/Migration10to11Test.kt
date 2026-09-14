package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
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
 * #179 design-plan test 4: MIGRATION_10_11 is additive-only — every v10 row survives with
 * `aplusPlanEpoch = NULL`, which is exactly the legacy discriminator (a pre-epoch attempt
 * recomputes the EXACT old idempotency-key literal; the column is never backfilled, because
 * backfilling would rewrite the key under live receipts).
 *
 * # v10→v11 迁移：存量 attempt 原样保留且 epoch=null；旧格式收据不受扰
 */
@RunWith(RobolectricTestRunner::class)
class Migration10to11Test {
    private val dbName = "migration-test-v10to11.db"
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

    private fun createCommittedV10() {
        val database = org.json.JSONObject(schema(10).readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) VALUES (1, 'pre-epoch.csv', 1726200000000, 5, 1, 1)")
            db.execSQL("INSERT INTO location_tasks (id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) VALUES (10, 1, 1, 30.5, 50.4, 1, 1, 0, 'active')")
            db.execSQL("INSERT INTO run_sessions (id, startedAt, status, configSnapshot, totalCycles, planId) VALUES (20, 100, 'paused', '', 0, 1)")
            // A v10 attempt admitted BEFORE the epoch column existed: the new column cannot be
            // present in the v10 CREATE TABLE, so the insert exercises the real v10 shape.
            db.execSQL(
                "INSERT INTO test_attempts (id, taskId, runSessionId, attemptOrdinal, startedAt, status, latitude, longitude, aplusState, aplusLeaseId) " +
                    "VALUES (30, 10, 20, 1, 101, 'running', 50.4, 30.5, 'APPLY_PENDING', 'lease-30')"
            )
            // A pre-epoch apply receipt under the LEGACY key — it must survive untouched and
            // stay findable by the null-epoch recompute after the migration.
            db.execSQL(
                "INSERT INTO operation_receipts (idempotencyKey, requestDigest, resultOutcome, createdAt, leaseId, operationId) " +
                    "VALUES ('${APlusOperationIdentity.applyIdempotencyKey(30L, null)}', 'digest-30', 'APPLIED', 105, 'lease-30', 'op-30')"
            )
            db.version = 10
        }
    }

    @Test fun `v10 rows survive with null epoch and legacy receipts stay addressable`() = runTest {
        createCommittedV10()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12).allowMainThreadQueries().build()
        try {
            assertEquals(12, db.openHelper.readableDatabase.version)

            // (a) The migrated attempt is byte-preserved except for the new NULL column.
            val attempt = db.testAttemptDao().getAttemptById(30L)!!
            assertEquals(10L, attempt.taskId)
            assertEquals(20L, attempt.runSessionId)
            assertEquals("APPLY_PENDING", attempt.aplusState)
            assertEquals("lease-30", attempt.aplusLeaseId)
            assertNull("pre-epoch rows keep the legacy-key discriminator", attempt.aplusPlanEpoch)

            // (b) The legacy receipt survives AND is still addressed by the null-epoch recompute
            //     — the mid-upgrade replay hits the ORIGINAL receipt, never a conflict.
            val legacyKey = APlusOperationIdentity.applyIdempotencyKey(30L, null)
            val receipt = db.operationReceiptDao().byKey(legacyKey)!!
            assertEquals("op-30", receipt.operationId)
            assertEquals("lease-30", receipt.leaseId)
        } finally {
            db.close()
        }
    }

    @Test fun `fresh v11 schema validates against the room identity hash`() = runTest {
        // The migration DDL must match the entity DDL exactly (Room validates the schema after
        // migrating) — a fresh v11 open round-trips without error.
        createCommittedV10()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12).allowMainThreadQueries().build()
        try {
            db.testAttemptDao().countAttemptsForTask(10L)
        } finally {
            db.close()
        }
    }
}
