package name.caiyao.fakegps.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import name.caiyao.fakegps.data.AppInfoProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val databaseName = "migration-1-2.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun removeLegacyRecoveryFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.closeInstanceForTests()
        context.deleteDatabase("fakegps.db")
        context.deleteDatabase("fakegps.db.legacy-v0-backup")
        context.deleteDatabase("fakegps.db.legacy-v0-migrating")
    }

    @Test
    fun migrationPreservesPopulatedV1RowAndAddsNullUnavailableMetadata() {
        helper.createDatabase(databaseName, 1).use { db ->
            val values = ContentValues().apply {
                put("id", 41L)
                put("latitude", 39.908722)
                put("longitude", 116.397499)
                put("mcc", 460)
                put("mnc", 0)
                put("tac", 26999)
                put("lte_rsrp", -95)
                put("operator_name", "Migration Carrier")
            }
            db.insert("temp", SQLiteDatabase.CONFLICT_FAIL, values)
        }

        helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { db ->
            db.query("SELECT * FROM temp WHERE id = 41").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(39.908722, cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")), 0.0)
                assertEquals(116.397499, cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")), 0.0)
                assertEquals(460, cursor.getInt(cursor.getColumnIndexOrThrow("mcc")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("mnc")))
                assertEquals(26999, cursor.getInt(cursor.getColumnIndexOrThrow("tac")))
                assertEquals(-95, cursor.getInt(cursor.getColumnIndexOrThrow("lte_rsrp")))
                assertEquals("Migration Carrier", cursor.getString(cursor.getColumnIndexOrThrow("operator_name")))
                assertNull(cursor.getString(cursor.getColumnIndexOrThrow("unavailable_fields")))
            }
        }
    }

    /**
     * The shipped pre-Room database has user_version=0 and no room_master_table. Android routes
     * that shape through SQLiteOpenHelper.onCreate, so a Room Migration(0, N) cannot repair it.
     * Keep this fixture hand-built: MigrationTestHelper only creates Room-versioned databases.
     */
    @Test
    fun legacyVersionZeroDatabaseIsRecoveredWithoutDroppingProfileRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fakegps.db"
        createLegacyV0Fixture(context, databaseName)

        val migrated = AppDatabase.getInstance(context)
        assertTrue(context.getDatabasePath("$databaseName.legacy-v0-backup").exists())
        migrated.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        assertAllLegacyRowsPreserved(context, databaseName)
        migrated.openHelper.readableDatabase.query("SELECT unavailable_fields FROM temp").use { cursor ->
            while (cursor.moveToNext()) assertNull(cursor.getString(0))
        }
    }

    @Test
    fun legacyRecoveryFailsClosedWhenAnotherReaderOwnsWalFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fakegps.db"
        createLegacyV0Fixture(context, databaseName, rows = 1)
        val databaseFile = context.getDatabasePath(databaseName)

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { }
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { reader ->
                reader.rawQuery("SELECT COUNT(*) FROM temp", null).use { snapshot ->
                    assertTrue(snapshot.moveToFirst())
                    assertEquals(1, snapshot.getInt(0))
                    // Keep this pre-write snapshot open. Its end mark makes the writer's new
                    // frame unsafe to checkpoint or to carry across a rename.
                    writer.insertOrThrow("temp", null, legacyRowValues(writer, 99))
                    try {
                        AppDatabase.ensureLegacyDatabaseRecovered(context)
                        fail("Recovery must not move a database with WAL frames held by a reader")
                    } catch (expected: RuntimeException) {
                        // The assertion below is the contract: the recovery may fail at the
                        // checkpoint gate or the subsequent sidecar gate, but it must leave the
                        // source untouched in either case.
                    }
                }
            }
        }

        assertTrue(databaseFile.exists())
        assertFalse(context.getDatabasePath("$databaseName.legacy-v0-backup").exists())
        assertFalse(context.getDatabasePath("$databaseName.legacy-v0-migrating").exists())
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { unchanged ->
            unchanged.rawQuery("SELECT COUNT(*) FROM temp", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    @Test
    fun restartAfterLegacyWasMovedPromotesValidatedStagingWithoutRecopying() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fakegps.db"
        createLegacyV0Fixture(context, databaseName)
        val live = context.getDatabasePath(databaseName)
        val backup = context.getDatabasePath("$databaseName.legacy-v0-backup")
        val stagingName = "$databaseName.legacy-v0-migrating"

        // This is the durable state after the first rename and before process death. The stage is
        // made through Room's real v2 schema rather than a hand-written approximation.
        val staged = Room.databaseBuilder(context, AppDatabase::class.java, stagingName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            staged.openHelper.writableDatabase.execSQL(
                "ATTACH DATABASE ? AS legacy_source",
                arrayOf(live.absolutePath),
            )
            try {
                val columns = legacyColumns(live)
                val quoted = columns.joinToString(", ") { quote(it) }
                staged.openHelper.writableDatabase.execSQL(
                    "INSERT INTO temp ($quoted) SELECT $quoted FROM legacy_source.temp",
                )
            } finally {
                staged.openHelper.writableDatabase.execSQL("DETACH DATABASE legacy_source")
            }
        } finally {
            staged.close()
        }
        assertTrue(live.renameTo(backup))

        assertTrue(AppDatabase.ensureLegacyDatabaseRecovered(context))
        assertTrue(live.exists())
        assertTrue(backup.exists())
        assertFalse(context.getDatabasePath(stagingName).exists())
        assertAllLegacyRowsPreserved(context, databaseName)
        AppDatabase.closeInstanceForTests()
        assertFalse(AppDatabase.ensureLegacyDatabaseRecovered(context))
    }

    @Test
    fun providerRecoversLegacyDatabaseBeforeOpeningItsRawReadHandle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        createLegacyV0Fixture(context, "fakegps.db")

        context.contentResolver.query(AppInfoProvider.APP_CONTENT_URI, arrayOf("id"), null, null, "id").use { cursor ->
            assertEquals(3, cursor?.count)
        }
        assertAllLegacyRowsPreserved(context, "fakegps.db")
    }

    @Test
    fun legacyWithCheckpointedSharedMemoryResidueIsRecovered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fakegps.db"
        createLegacyV0Fixture(context, databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        val staleShm = java.io.File(databaseFile.path + "-shm")
        val capturedSharedMemory = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { legacy ->
            assertTrue(legacy.enableWriteAheadLogging())
            legacy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { }
            assertTrue(staleShm.exists())
            staleShm.readBytes()
        }
        // This mirrors the sealed fixture shape: an empty WAL plus a real 32 KiB shared-memory
        // index from a database that has already checkpointed all profile frames.
        staleShm.writeBytes(capturedSharedMemory)
        java.io.File(databaseFile.path + "-wal").outputStream().use { }
        assertTrue(staleShm.exists())
        assertEquals(32 * 1024, staleShm.length())

        AppDatabase.getInstance(context)

        assertFalse(staleShm.exists())
        assertAllLegacyRowsPreserved(context, databaseName)
    }

    private fun createLegacyV0Fixture(context: android.content.Context, databaseName: String, rows: Int = 3) {
        AppDatabase.closeInstanceForTests()
        context.deleteDatabase(databaseName)
        context.deleteDatabase("$databaseName.legacy-v0-backup")
        context.deleteDatabase("$databaseName.legacy-v0-migrating")
        val databaseFile = context.getDatabasePath(databaseName)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { legacy ->
            legacy.execSQL(legacyTempCreateSql(InstrumentationRegistry.getInstrumentation().context))
            repeat(rows) { index ->
                legacy.insertWithOnConflict(
                    "temp",
                    null,
                    legacyRowValues(legacy, index + 1),
                    SQLiteDatabase.CONFLICT_FAIL,
                )
            }
            legacy.execSQL("PRAGMA user_version = 0")
        }
    }

    /** Gives every legacy column a distinct non-null value, so value loss cannot hide as NULL. */
    private fun legacyRowValues(database: SQLiteDatabase, row: Int): ContentValues = ContentValues().also { values ->
        database.rawQuery("PRAGMA table_info(temp)", null).use { columns ->
            val nameIndex = columns.getColumnIndexOrThrow("name")
            val typeIndex = columns.getColumnIndexOrThrow("type")
            while (columns.moveToNext()) {
                val name = columns.getString(nameIndex)
                val type = columns.getString(typeIndex).uppercase()
                when {
                    name == "id" -> values.put(name, row.toLong())
                    type.contains("INT") -> values.put(name, row * 1000 + name.length)
                    type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") ->
                        values.put(name, row + name.length / 100.0)
                    else -> values.put(name, "$name-value-$row")
                }
            }
        }
    }

    private fun legacyColumns(legacyFile: java.io.File): List<String> =
        SQLiteDatabase.openDatabase(legacyFile.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
            legacy.rawQuery("PRAGMA table_info(temp)", null).use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }

    private fun assertAllLegacyRowsPreserved(context: android.content.Context, databaseName: String) {
        val live = context.getDatabasePath(databaseName)
        val backup = context.getDatabasePath("$databaseName.legacy-v0-backup")
        val columns = legacyColumns(backup).joinToString(", ") { quote(it) }
        SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { migrated ->
            migrated.execSQL("ATTACH DATABASE ? AS legacy_backup", arrayOf(backup.absolutePath))
            try {
                assertEquals(3, countRows(migrated, "temp"))
                assertEquals(3, countRows(migrated, "legacy_backup.temp"))
                assertEquals(0, countRows(migrated, "SELECT $columns FROM temp EXCEPT SELECT $columns FROM legacy_backup.temp"))
                assertEquals(0, countRows(migrated, "SELECT $columns FROM legacy_backup.temp EXCEPT SELECT $columns FROM temp"))
            } finally {
                migrated.execSQL("DETACH DATABASE legacy_backup")
            }
        }
    }

    private fun countRows(database: SQLiteDatabase, tableOrQuery: String): Int =
        database.rawQuery(
            if (tableOrQuery.startsWith("SELECT ")) "SELECT COUNT(*) FROM ($tableOrQuery)" else "SELECT COUNT(*) FROM $tableOrQuery",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private fun legacyTempCreateSql(context: android.content.Context): String {
        val schema = context.assets.open("name.caiyao.fakegps.data.db.AppDatabase/1.json")
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        return schema.getJSONObject("database")
            .getJSONArray("entities")
            .getJSONObject(0)
            .getString("createSql")
            .replace("`${'$'}{TABLE_NAME}`", "`temp`")
            .replace("INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", "INTEGER PRIMARY KEY AUTOINCREMENT")
    }
}
