package name.caiyao.fakegps.data.db

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import name.caiyao.fakegps.motion.RoutePayload
import name.caiyao.fakegps.motion.RouteWaypoint
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * P3.1 schema migration v2→v3 (`route_waypoints_json`), on the JVM under Robolectric.
 *
 * 升级不毁既有单点档案 is the invariant: every populated v2 column must survive the migration
 * and the new column must read NULL (= single-point semantics) for old rows.
 *
 * The fixture rebuilds the v2 database from the EXPORTED Room schema JSON
 * (`app/schemas/.../2.json`): exact CREATE TABLE plus the identity hash Room wrote at v2 time —
 * the very bytes a production v2 device carries. Opening it through Room with
 * [AppDatabase.MIGRATION_2_3] exercises the real upgrade path (version bump → migration →
 * identity re-stamp), not a test double of it.
 */
@RunWith(RobolectricTestRunner::class)
class RouteSchemaMigrationTest {

    private val databaseName = "route-migration-2-3.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        AppDatabase.closeInstanceForTests()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `migration preserves populated single-point rows and adds a null route column`() {
        createV2Database(databaseName)
        prePopulateV2Row(databaseName)

        val room = openThroughRoom(databaseName)
        try {
            val columns = room.openHelper.readableDatabase.query("PRAGMA table_info(temp)")
                .use { cursor ->
                    buildList {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
            assertTrue(
                "route_waypoints_json must exist after migration",
                columns.contains("route_waypoints_json"),
            )
            assertEquals("schema must be at version 3", 3, room.openHelper.readableDatabase.version)

            room.openHelper.readableDatabase
                .query("SELECT * FROM temp WHERE id = 41").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(
                        39.908722,
                        cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                        0.0,
                    )
                    assertEquals(
                        116.397499,
                        cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                        0.0,
                    )
                    assertEquals(460, cursor.getInt(cursor.getColumnIndexOrThrow("mcc")))
                    assertEquals(-95, cursor.getInt(cursor.getColumnIndexOrThrow("lte_rsrp")))
                    assertEquals(
                        "Migration Carrier",
                        cursor.getString(cursor.getColumnIndexOrThrow("operator_name")),
                    )
                    assertEquals(
                        "[\"mcc\"]",
                        cursor.getString(cursor.getColumnIndexOrThrow("unavailable_fields")),
                    )
                    assertNull(
                        "existing single-point rows keep a NULL route column",
                        cursor.getString(cursor.getColumnIndexOrThrow("route_waypoints_json")),
                    )
                }

            // The migrated database must round-trip a route payload through the new column.
            val waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4600, 30.5400, speedMps = 8.0),
            )
            room.openHelper.writableDatabase.execSQL(
                "INSERT INTO temp (id, latitude, longitude, route_waypoints_json) VALUES " +
                    "(42, 50.4501, 30.5234, ?)",
                arrayOf(RoutePayload.encodeWaypoints(waypoints)),
            )
            room.openHelper.readableDatabase
                .query("SELECT route_waypoints_json FROM temp WHERE id = 42").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(waypoints, RoutePayload.parseWaypoints(cursor.getString(0)))
                }

            // The Room DAO (the app's real read path) sees both rows, oldest first.
            val rows = kotlinx.coroutines.runBlocking { room.profileDao().getAll() }
            assertEquals(listOf(41L, 42L), rows.map { it.id })
        } finally {
            room.close()
            AppDatabase.closeInstanceForTests()
        }
    }

    /** Rebuilds the exact v2 shape from the exported schema JSON (DDL + identity hash). */
    private fun createV2Database(name: String) {
        val schema = exportedSchema(2)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(schema.createSql)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, '${schema.identityHash}')",
            )
            db.version = 2
        }
    }

    private fun prePopulateV2Row(name: String) {
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "INSERT INTO temp (id, latitude, longitude, speed, bearing, accuracy, " +
                    "mcc, mnc, tac, ci, lte_rsrp, operator_name, operator_numeric, " +
                    "neighbor_cells_json, unavailable_fields) VALUES " +
                    "(41, 39.908722, 116.397499, 5.5, 90.0, 3.0, 460, 0, 26999, 98102001, -95, " +
                    "'Migration Carrier', '46000', '[]', '[\"mcc\"]')",
            )
        }
    }

    private fun openThroughRoom(name: String): AppDatabase {
        AppDatabase.closeInstanceForTests()
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase } // force the open + migration now
    }

    private data class ExportedSchema(val createSql: String, val identityHash: String)

    private fun exportedSchema(version: Int): ExportedSchema {
        val fileName = "app/schemas/${AppDatabase::class.java.canonicalName}/$version.json"
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, fileName) }
            .firstOrNull { it.isFile }
            ?: error("exported Room schema not found: $fileName")
        val root = JSONObject(file.readText())
        val entity = root.getJSONObject("database").getJSONArray("entities").getJSONObject(0)
        return ExportedSchema(
            createSql = entity.getString("createSql").replace("\${TABLE_NAME}", "temp"),
            identityHash = root.getJSONObject("database").getString("identityHash"),
        )
    }
}
