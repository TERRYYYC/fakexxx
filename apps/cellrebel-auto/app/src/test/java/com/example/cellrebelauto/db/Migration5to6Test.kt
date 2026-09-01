package com.example.cellrebelauto.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F-19: the v5 → v6 no-op bump must PRESERVE data for the healthy entry path.
 *
 * "Healthy v5" is built from the COMMITTED `schemas/**/5.json` bytes themselves — every
 * `createSql` / `setupQueries` statement executed verbatim — so the fixture is the genuine
 * committed v5 (identity hash `0d083aef…`) by construction, not a hand transcription.
 * (MigrationTestHelper is deliberately not used: AGP does not merge test-sourceSet assets for
 * Robolectric unit tests reliably; reading the committed JSON from the repo is plumbing-free
 * and byte-identical.)
 *
 * Schema validity of the no-op migration is enforced by Room itself: the production open path
 * runs post-migration validation against the generated v6 expectations — if v6 deviated from v5
 * table-for-table, this test would fail with "Migration didn't properly handle".
 *
 * # F-19：健康 v5（从已提交 5.json 字节直接建库）→ v6 必须保数据；
 * # no-op 迁移的 schema 正确性由 Room 开库后校验承担。破坏面只许吃漂移库，不许吃健康库。
 */
@RunWith(RobolectricTestRunner::class)
class Migration5to6Test {

    private val dbName = "migration-test-v5to6.db"
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    /** Locates the committed schema JSON regardless of the gradle working directory. */
    private fun committedSchemaJson(version: Int): File {
        val rel = "schemas/com.example.cellrebelauto.db.AppDatabase/$version.json"
        return sequenceOf(File(rel), File("app/$rel"), File("apps/cellrebel-auto/app/$rel"))
            .firstOrNull { it.exists() }
            ?: error("committed schema $version.json not found from ${File(".").absolutePath}")
    }

    /**
     * Executes the committed 5.json verbatim: every entity `createSql`, every index `createSql`,
     * then the exported `setupQueries` (which write `room_master_table` with the committed
     * identity hash), then `user_version = 5`.
     * # 逐字执行已提交 5.json：建表、建索引、setupQueries 写入健康 identity hash
     */
    private fun createHealthyCommittedV5(file: File) {
        val database = JSONObject(committedSchemaJson(5).readText()).getJSONObject("database")
        assertEquals(5, database.getInt("version"))
        assertEquals(
            "committed 5.json must be the healthy hash this fixture claims to build",
            AppDatabase.V5_HEALTHY_IDENTITY_HASH,
            database.getString("identityHash")
        )
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
            for (i in 0 until setup.length()) {
                db.execSQL(setup.getString(i))
            }
            db.version = 5
        }
    }

    /**
     * Healthy committed v5 + marker row → production open path (quarantine + ladder + destructive
     * fallback): the quarantine must recognize the healthy hash and stand down; MIGRATION_5_6
     * runs as a no-op before the explicit v6→v7 principal migration; the data survives; the
     * file lands on version 7. This is the dispatch requirement that plan B's destructive parts
     * must not eat healthy databases.
     * # 健康 v5 走生产路径：隔离区放行、no-op 5→6 与 nullable-principal 6→7 过校验，数据存活。
     */
    @Test
    fun `healthy committed v5 through production path keeps data and lands on v7`() {
        createHealthyCommittedV5(dbFile)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(
                "INSERT INTO run_sessions (startedAt, endedAt, status, configSnapshot, totalCycles) " +
                    "VALUES (4000, 5000, 'completed', 'healthy-v5-marker', 2)"
            )
        }

        val db = AppDatabase.buildProductionDatabase(context, dbName)
        try {
            val sessions = db.openHelper.readableDatabase
                .query("SELECT configSnapshot FROM run_sessions").use { c ->
                    generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
                }
            assertEquals(listOf("healthy-v5-marker"), sessions)
            assertEquals(7, db.openHelper.readableDatabase.version)

            // Production opens at v7, so Room publishes the committed v7 identity after the
            // principal columns are added. The separate test below still proves v5→v6 is no-op.
            val hash = db.openHelper.readableDatabase
                .query("SELECT identity_hash FROM room_master_table LIMIT 1")
                .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val v7 = JSONObject(committedSchemaJson(7).readText()).getJSONObject("database")
            assertEquals(v7.getString("identityHash"), hash)
        } finally {
            db.close()
        }
    }

    /**
     * Cross-check on the committed artifacts themselves: 6.json must carry the SAME identity hash
     * and table set as 5.json — the no-op bump is an exported, reviewable fact.
     * # 提交产物互证：6.json 与 5.json 同 hash 同表集
     */
    @Test
    fun `committed 6json is table-for-table identical to committed 5json`() {
        val v5 = JSONObject(committedSchemaJson(5).readText()).getJSONObject("database")
        val v6 = JSONObject(committedSchemaJson(6).readText()).getJSONObject("database")
        assertEquals(6, v6.getInt("version"))
        assertEquals(v5.getString("identityHash"), v6.getString("identityHash"))

        fun tableNames(db: JSONObject): List<String> {
            val entities = db.getJSONArray("entities")
            return (0 until entities.length()).map {
                entities.getJSONObject(it).getString("tableName")
            }.sorted()
        }
        assertEquals(tableNames(v5), tableNames(v6))
        assertTrue("v6 must keep all sixteen v5 tables", tableNames(v6).size == 16)
    }
}
