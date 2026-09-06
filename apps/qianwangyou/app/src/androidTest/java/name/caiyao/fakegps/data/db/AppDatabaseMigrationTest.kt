package name.caiyao.fakegps.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

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
        val databaseFile = context.getDatabasePath(databaseName)

        // Other instrumentation classes use the app singleton; discard that process-local cache
        // before replacing the on-disk fixture below.
        AppDatabase.closeInstanceForTests()
        context.deleteDatabase(databaseName)
        context.deleteDatabase("$databaseName.legacy-v0-backup")
        context.deleteDatabase("$databaseName.legacy-v0-migrating")

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { legacy ->
            legacy.execSQL(legacyTempCreateSql(InstrumentationRegistry.getInstrumentation().context))
            legacy.insertWithOnConflict(
                "temp",
                null,
                ContentValues().apply {
                    put("id", 71L)
                    put("addname", "Legacy Beijing")
                    put("latitude", 39.908722)
                    put("longitude", 116.397499)
                    put("mcc", 460)
                    put("lte_rsrp", -95)
                },
                SQLiteDatabase.CONFLICT_FAIL,
            )
            legacy.execSQL("PRAGMA user_version = 0")
        }

        val migrated = AppDatabase.getInstance(context)
        runBlocking {
            val profile = migrated.profileDao().getById(71L)
            assertEquals("Legacy Beijing", profile?.addname)
            assertEquals(39.908722, profile?.latitude ?: Double.NaN, 0.0)
            assertEquals(116.397499, profile?.longitude ?: Double.NaN, 0.0)
            assertEquals(460, profile?.mcc)
            assertEquals(-95, profile?.lteRsrp)
            assertNull(profile?.unavailableFields)
        }

        assertTrue(context.getDatabasePath("$databaseName.legacy-v0-backup").exists())
        migrated.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

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
