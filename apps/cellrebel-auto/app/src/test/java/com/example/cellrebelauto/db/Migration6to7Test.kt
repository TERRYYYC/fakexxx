package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** v6 → v7 adds P on five recovery tables and S only on the four attempt/lease-owner tables. */
@RunWith(RobolectricTestRunner::class)
class Migration6to7Test {

    private val dbName = "migration-test-v6to7.db"
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    private fun committedSchemaJson(version: Int): File {
        val rel = "schemas/com.example.cellrebelauto.db.AppDatabase/$version.json"
        return sequenceOf(File(rel), File("app/$rel"), File("apps/cellrebel-auto/app/$rel"))
            .firstOrNull { it.exists() }
            ?: error("committed schema $version.json not found from ${File(".").absolutePath}")
    }

    private fun createCommittedV6(file: File) {
        val database = JSONObject(committedSchemaJson(6).readText()).getJSONObject("database")
        assertEquals(6, database.getInt("version"))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", tableName)
                    )
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 6
        }
    }

    @Test
    fun `committed v6 recovery chain survives v7 and all legacy principals stay null`() = runTest {
        createCommittedV6(dbFile)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "INSERT INTO location_plans " +
                    "(id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) " +
                    "VALUES (1, 'legacy.csv', 1000, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO location_tasks " +
                    "(id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) " +
                    "VALUES (2, 1, 1, 30.5, 50.4, 1, 1, 0, 'active')"
            )
            db.execSQL(
                "INSERT INTO run_sessions " +
                    "(id, startedAt, endedAt, status, configSnapshot, totalCycles, planId) " +
                    "VALUES (3, 1100, NULL, 'running', '{}', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO test_attempts " +
                    "(id, taskId, runSessionId, attemptOrdinal, successOrdinal, startedAt, runningObservedAt, endedAt, status, failureReason, webBrowsingScore, videoStreamingScore, latitude, longitude, stageNotes, aplusState, aplusLeaseId, currentExecutionId, aplusAnchorScheduleId, aplusAnchorItemId, aplusAnchorVersion) " +
                    "VALUES (4, 2, 3, 1, NULL, 1200, NULL, NULL, 'starting', NULL, NULL, NULL, 50.4, 30.5, NULL, 'RELEASE_PENDING', 'lease-4', NULL, 'schedule-1', 'item-1', 1)"
            )
            db.execSQL(
                "INSERT INTO operation_receipts " +
                    "(idempotencyKey, requestDigest, resultOutcome, createdAt, leaseId) " +
                    "VALUES ('apply-4', 'digest-4', 'APPLIED', 1300, 'lease-4')"
            )
            db.execSQL(
                "INSERT INTO recovery_checkpoints " +
                    "(attemptId, lastDurableStage, receiptKey, recordedAt) " +
                    "VALUES (4, 'RELEASE_PENDING', 'apply-4', 1400)"
            )
            db.execSQL(
                "INSERT INTO release_receipts " +
                    "(idempotencyKey, leaseId, releaseDigest, resultOutcome, createdAt) " +
                    "VALUES ('release-4', 'lease-4', 'release-digest-4', 'RELEASED', 1500)"
            )
        }

        val room = AppDatabase.buildProductionDatabase(context, dbName)
        try {
            val sql = room.openHelper.readableDatabase
            assertEquals("production ladder must land on v7", 7, sql.version)
            val principalQueries = listOf(
                "SELECT providerApplicationId FROM location_plans WHERE id = 1",
                "SELECT providerApplicationId FROM test_attempts WHERE id = 4",
                "SELECT providerApplicationId FROM operation_receipts WHERE idempotencyKey = 'apply-4'",
                "SELECT providerApplicationId FROM recovery_checkpoints WHERE attemptId = 4",
                "SELECT providerApplicationId FROM release_receipts WHERE idempotencyKey = 'release-4'",
            )
            principalQueries.forEach { query ->
                sql.query(query).use { cursor ->
                    assertEquals(1, cursor.count)
                    cursor.moveToFirst()
                    assertNull("legacy principal must remain SQL NULL: $query", cursor.getString(0))
                }
            }
            val signerOwnerQueries = listOf(
                "SELECT providerSignerDigest FROM test_attempts WHERE id = 4",
                "SELECT providerSignerDigest FROM operation_receipts WHERE idempotencyKey = 'apply-4'",
                "SELECT providerSignerDigest FROM recovery_checkpoints WHERE attemptId = 4",
                "SELECT providerSignerDigest FROM release_receipts WHERE idempotencyKey = 'release-4'",
            )
            signerOwnerQueries.forEach { query ->
                sql.query(query).use { cursor ->
                    assertEquals(1, cursor.count)
                    cursor.moveToFirst()
                    assertNull(
                        "legacy signer ownership must remain SQL NULL and must never be inferred: $query",
                        cursor.getString(0),
                    )
                }
            }
            val signerOwnerTables = setOf(
                "test_attempts",
                "operation_receipts",
                "recovery_checkpoints",
                "release_receipts",
            )
            signerOwnerTables.forEach { table ->
                sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    assertTrue(cursor.moveToFirst())
                    var found = false
                    do {
                        if (cursor.getString(nameIndex) == "providerSignerDigest") {
                            found = true
                            assertEquals("TEXT", cursor.getString(typeIndex))
                            assertEquals("legacy ownership must remain nullable", 0, cursor.getInt(notNullIndex))
                            assertNull("migration must not guess a default signer", cursor.getString(defaultIndex))
                        }
                    } while (cursor.moveToNext())
                    assertTrue("$table must carry signer ownership", found)
                }
            }
            sql.query("PRAGMA table_info(`location_plans`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val columns = buildSet {
                    if (cursor.moveToFirst()) {
                        do add(cursor.getString(nameIndex)) while (cursor.moveToNext())
                    }
                }
                assertFalse(
                    "plan identity is P-only; signer ownership is attempt/lease scoped",
                    "providerSignerDigest" in columns,
                )
            }
            assertEquals(
                "lease-4",
                sql.query("SELECT aplusLeaseId FROM test_attempts WHERE id = 4").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
            assertEquals(
                "the service fail-close transition must own the migrated recovery row",
                1,
                AutomationService.persistUnknownProviderRecovery(1L, PlanRepository(room)),
            )
            assertEquals(
                "RECOVERY_REQUIRED",
                sql.query("SELECT aplusState FROM test_attempts WHERE id = 4").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
            assertEquals(
                "PROVIDER_PRINCIPAL_UNKNOWN",
                sql.query("SELECT failureReason FROM test_attempts WHERE id = 4").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
            assertEquals(
                "paused",
                sql.query("SELECT status FROM run_sessions WHERE id = 3").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `migration source and schema export cannot backfill or move signer ownership onto plans`() {
        val migrationSource = sequenceOf(
            File("src/main/java/com/example/cellrebelauto/db/Migrations.kt"),
            File("app/src/main/java/com/example/cellrebelauto/db/Migrations.kt"),
            File("apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/Migrations.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Migrations.kt not found from ${File(".").absolutePath}")
        val migration5to6 = migrationSource.substringAfter("val MIGRATION_5_6")
            .substringBefore("val MIGRATION_6_7")
        val migration6to7 = migrationSource.substringAfter("val MIGRATION_6_7")
        val signerTables = setOf(
            "test_attempts",
            "operation_receipts",
            "recovery_checkpoints",
            "release_receipts",
        )

        assertFalse("MIGRATION_5_6 is frozen", migration5to6.contains("providerSignerDigest"))
        signerTables.forEach { table ->
            assertTrue(
                "$table gets one nullable TEXT signer column",
                migration6to7.contains(
                    "ALTER TABLE $table ADD COLUMN providerSignerDigest TEXT"
                ),
            )
        }
        assertFalse("signer migration must have no DEFAULT", migration6to7.contains("DEFAULT", ignoreCase = true))
        assertFalse("signer migration must never backfill", migration6to7.contains("UPDATE", ignoreCase = true))
        assertFalse(
            "location_plans stays P-only",
            migration6to7.contains("location_plans ADD COLUMN providerSignerDigest"),
        )

        val schema = JSONObject(committedSchemaJson(7).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val exportedSignerTables = buildSet {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val fields = entity.getJSONArray("fields")
                for (j in 0 until fields.length()) {
                    if (fields.getJSONObject(j).getString("columnName") == "providerSignerDigest") {
                        add(entity.getString("tableName"))
                    }
                }
            }
        }
        assertEquals(signerTables, exportedSignerTables)
    }
}
