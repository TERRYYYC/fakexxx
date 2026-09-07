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

/** #97: v6 history survives the additive archive-link migration unchanged. */
@RunWith(RobolectricTestRunner::class)
class Migration6to7Test {
    private val dbName = "migration-test-v6to7.db"
    private lateinit var context: Context
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
    }

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(file)
    }

    private fun schema(version: Int): File {
        val path = "schemas/com.example.cellrebelauto.db.AppDatabase/$version.json"
        return sequenceOf(File(path), File("app/$path"), File("apps/cellrebel-auto/app/$path"))
            .firstOrNull { it.exists() } ?: error("missing committed schema $version")
    }

    private fun createCommittedV6() {
        val database = org.json.JSONObject(schema(6).readText()).getJSONObject("database")
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
            db.execSQL(
                "INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, totalRows, totalRequiredSuccesses) " +
                    "VALUES (1, 'old.csv', 100, 5, 1, 1)"
            )
            db.version = 6
        }
    }

    @Test
    fun `v6 plan is retained and starts active after v7 archive-link migration`() = runTest {
        createCommittedV6()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        try {
            val plan = db.planDao().getPlanById(1L)!!
            assertEquals("old.csv", plan.sourceFileName)
            assertNull(plan.supersededAt)
            assertNull(plan.supersededByPlanId)
            assertEquals(9, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
