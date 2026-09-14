package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * #190 design test: MIGRATION_11_12 is additive-only — every v11 task row survives with
 * `expectedCi = NULL`, which is exactly the honest-absence semantics (the row predates the
 * CSV ci column / a ci-less import): the UI shows the measured cell with NO match verdict,
 * never a guessed one. The column is display-only (never read by TrustPolicy/quota).
 *
 * # v11→v12 迁移：存量 task 原样保留且 expectedCi=null；展示层把 null 当"无期望"处理
 */
@RunWith(RobolectricTestRunner::class)
class Migration11to12Test {
    private val dbName = "migration-test-v11to12.db"
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

    private fun createCommittedV11() {
        val database = org.json.JSONObject(schema(11).readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) VALUES (1, 'legacy-4col.csv', 1726200000000, 5, 1, 1)")
            // A v11 task imported from a 4-column CSV: the expectedCi column cannot be
            // present in the v11 CREATE TABLE, so this insert exercises the real v11 shape.
            db.execSQL(
                "INSERT INTO location_tasks (id, planId, csvRow, longitude, latitude, priority, requiredSuccesses, completedSuccesses, status) " +
                    "VALUES (10, 1, 1, 29.9243986, 49.8714584, 3, 3, 0, 'pending')"
            )
            db.version = 11
        }
    }

    @Test fun `v11 rows survive with null expectedCi`() = runTest {
        createCommittedV11()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_11_12).allowMainThreadQueries().build()
        try {
            assertEquals(12, db.openHelper.readableDatabase.version)

            // The migrated task is byte-preserved except for the new NULL column —
            // the "无期望" discriminator the verification UI keys on.
            val task = db.locationTaskDao().getTaskById(10L)!!
            assertEquals(1L, task.planId)
            assertEquals(1, task.csvRow)
            assertEquals(29.9243986, task.longitude, 1e-12)
            assertEquals(49.8714584, task.latitude, 1e-12)
            assertEquals(3, task.priority)
            assertEquals(3, task.requiredSuccesses)
            assertNull("pre-ci rows keep the honest-absence semantics", task.expectedCi)
        } finally {
            db.close()
        }
    }

    @Test fun `fresh v12 schema validates against the room identity hash`() = runTest {
        // The migration DDL must match the entity DDL exactly (Room validates the schema
        // after migrating) — a fresh v12 open round-trips without error.
        createCommittedV11()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_11_12).allowMainThreadQueries().build()
        try {
            db.locationTaskDao().getTasksForPlan(1L)
        } finally {
            db.close()
        }
    }
}
