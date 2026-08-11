package name.caiyao.fakegps.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val databaseName = "migration-1-2.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

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
}
