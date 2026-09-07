package com.example.cellrebelauto.cutover

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomV9CutoverStoreTest {
    private val databases = mutableListOf<AppDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(AppDatabase::close)
    }

    @Test
    fun schemaPolicyIsTheExactRoomV9TableCensus() = runTest {
        val store = RoomV9CutoverStore(database())

        val policy = store.schemaPolicy()

        assertEquals(9, policy.schemaVersion)
        assertEquals(EXPECTED_TABLES, policy.requiredTableSchemaDigests.keys)
        assertTrue(policy.requiredTableSchemaDigests.values.all { it.matches(Regex("sha256:[0-9a-f]{64}")) })
        assertEquals(setOf("provider_pairing_records"), policy.historicalOnlyTables)
    }

    @Test
    fun canonicalRowsRestoreInForeignKeyOrderAndActivePairingBecomesHistory() = runTest {
        val source = database()
        val target = database()
        seedSource(source)
        val sourceStore = RoomV9CutoverStore(source)
        val targetStore = RoomV9CutoverStore(target)
        val archive = archive(sourceStore.captureTables())

        assertEquals(CutoverGenerationState.EMPTY, targetStore.classify(archive))
        targetStore.restore(archive)

        assertEquals(CutoverGenerationState.EXACT, targetStore.classify(archive))
        assertEquals(1L, count(target, "location_plans"))
        assertEquals(1L, count(target, "location_tasks"))
        assertEquals(1L, count(target, "run_sessions"))
        assertEquals(1L, count(target, "test_results"))
        assertEquals(1L, count(target, "provider_pairing_records"))
        assertEquals(
            null,
            target.providerPairingDao().activeFor("name.caiyao.fakegps", "signer-a")
        )
        val pairing = target.providerPairingDao().all().single()
        assertEquals(pairing.approvedAt, pairing.revokedAt)

        targetStore.clear()
        assertEquals(CutoverGenerationState.EMPTY, targetStore.classify(archive))
        assertTrue(EXPECTED_TABLES.all { count(target, it) == 0L })
    }

    @Test
    fun nonEmptyDifferentTargetClassifiesMismatchAndRestoreRefusesOverwrite() = runTest {
        val source = database()
        val target = database()
        seedSource(source)
        target.openHelper.writableDatabase.execSQL(
            "INSERT INTO location_plans " +
                "(id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) " +
                "VALUES (99, 'target.csv', 99, 9, 0, 0)"
        )
        val archive = archive(RoomV9CutoverStore(source).captureTables())
        val targetStore = RoomV9CutoverStore(target)

        assertEquals(CutoverGenerationState.MISMATCH, targetStore.classify(archive))
        val failure = runCatching { targetStore.restore(archive) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("target Room is not empty"))
        assertEquals(99L, target.planDao().getPlanById(99L)?.id)
    }

    @Test
    fun runtimeSchemaDriftRejectsBeforeCaptureOrClear() = runTest {
        val db = database()
        db.openHelper.writableDatabase.execSQL("ALTER TABLE location_plans ADD COLUMN rogue TEXT")
        val store = RoomV9CutoverStore(db)

        val captureFailure = runCatching { store.captureTables() }.exceptionOrNull()
        val clearFailure = runCatching { store.clear() }.exceptionOrNull()

        assertTrue(captureFailure is IllegalStateException)
        assertTrue(captureFailure?.message.orEmpty().contains("Room v9 schema mismatch"))
        assertTrue(clearFailure is IllegalStateException)
        assertFalse(clearFailure?.message.orEmpty().contains("no such table"))
    }

    @Test
    fun restoreRejectsOrderKeyThatDoesNotDescribePayloadPrimaryKey() = runTest {
        val source = database()
        val target = database()
        seedSource(source)
        val archive = archive(RoomV9CutoverStore(source).captureTables())
        val changed = archive.copy(
            tables = archive.tables.map { table ->
                if (table.name != "location_plans") table else table.copy(
                    rows = table.rows.mapIndexed { index, row ->
                        if (index == 0) row.copy(orderKeyBase64Url = "AQ") else row
                    }
                )
            }
        )

        val failure = runCatching { RoomV9CutoverStore(target).restore(changed) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("row order key mismatch"))
        assertTrue(EXPECTED_TABLES.all { count(target, it) == 0L })
    }

    @Test
    fun restoreRejectsCellTypeThatDoesNotMatchSchemaAffinity() = runTest {
        val source = database()
        val target = database()
        seedSource(source)
        val archive = archive(RoomV9CutoverStore(source).captureTables())
        val changed = archive.copy(
            tables = archive.tables.map { table ->
                if (table.name != "location_plans") table else table.copy(
                    rows = table.rows.mapIndexed { index, row ->
                        if (index != 0) row else {
                            val key = decode(row.orderKeyBase64Url).also { it[4] = 2 }
                            val payload = decode(row.canonicalRowBase64Url).also { it[4] = 2 }
                            row.copy(orderKeyBase64Url = encode(key), canonicalRowBase64Url = encode(payload))
                        }
                    }
                )
            }
        )

        val failure = runCatching { RoomV9CutoverStore(target).restore(changed) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("row type mismatch for location_plans.id"))
        assertTrue(EXPECTED_TABLES.all { count(target, it) == 0L })
    }

    @Test
    fun targetReadbackCannotHistoricalizeAnActivePairingBeforeComparison() = runTest {
        val source = database()
        val target = database()
        seedSource(source)
        val archive = archive(RoomV9CutoverStore(source).captureTables())
        val targetStore = RoomV9CutoverStore(target)
        targetStore.restore(archive)
        target.openHelper.writableDatabase.execSQL(
            "UPDATE provider_pairing_records SET revokedAt = NULL"
        )

        assertTrue(
            target.providerPairingDao().activeFor("name.caiyao.fakegps", "signer-a") != null
        )
        assertEquals(CutoverGenerationState.MISMATCH, targetStore.classify(archive))
    }

    @Test
    fun emptyRoomArchiveIsBothAnEmptyTargetAndAnExactGeneration() = runTest {
        val sourceStore = RoomV9CutoverStore(database())
        val targetStore = RoomV9CutoverStore(database())
        val archive = archive(sourceStore.captureTables())

        val before = targetStore.classify(archive)
        assertTrue(before.isEmpty)
        assertTrue(before.matchesArchive)
        targetStore.restore(archive)
        val after = targetStore.classify(archive)
        assertTrue(after.isEmpty)
        assertTrue(after.matchesArchive)
    }

    private fun database(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
            .also { databases += it }
    }

    private fun seedSource(db: AppDatabase) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "INSERT INTO location_plans " +
                "(id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) " +
                "VALUES (1, 'source.csv', 10, 5, 1, 1)"
        )
        sql.execSQL(
            "INSERT INTO location_tasks " +
                "(id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) " +
                "VALUES (2, 1, 1, 30.5, 50.4, 1, 1, 0, 'pending')"
        )
        sql.execSQL(
            "INSERT INTO run_sessions (id, startedAt, status, configSnapshot, totalCycles, planId) " +
                "VALUES (3, 11, 'paused', '', 0, 1)"
        )
        sql.execSQL(
            "INSERT INTO test_results " +
                "(id, runSessionId, timestamp, webBrowsingScore, videoStreamingScore, latitude, longitude, cycleIndex, status) " +
                "VALUES (4, 3, 12, 1.5, 2.5, 50.4, 30.5, 1, 'completed')"
        )
        sql.execSQL(
            "INSERT INTO provider_pairing_records " +
                "(id, applicationId, currentSignerDigest, approvedAt, revokedAt, approvedVersionCode) " +
                "VALUES (5, 'name.caiyao.fakegps', 'signer-a', 13, NULL, 1)"
        )
    }

    private fun archive(tables: List<CutoverTableSection>) = CutoverArchiveV2(
        sourcePackage = "com.example.cellrebelauto",
        captureId = "capture-room",
        schemaVersion = 9,
        tables = tables,
        preferences = emptyList()
    )

    private fun count(db: AppDatabase, table: String): Long =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private companion object {
        val EXPECTED_TABLES = sortedSetOf(
            "advance_receipts",
            "advance_replay_carriers",
            "auto_audit_events",
            "cellrebel_executions",
            "durable_completion_receipts",
            "durable_observation_records",
            "legacy_completion_snapshots",
            "location_plans",
            "location_tasks",
            "operation_receipts",
            "provider_pairing_records",
            "recovery_checkpoints",
            "release_receipts",
            "run_sessions",
            "test_attempts",
            "test_results",
            "trusted_quota_entries",
            "unverified_attempt_records"
        )
    }
}
