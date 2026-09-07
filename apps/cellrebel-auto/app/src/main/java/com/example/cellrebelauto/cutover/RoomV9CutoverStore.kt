package com.example.cellrebelauto.cutover

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.cellrebelauto.db.AppDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Schema-9-only Room adapter for the application-id cutover archive. */
class RoomV9CutoverStore(
    private val database: AppDatabase
) : CutoverRoomGenerationPort, CutoverRoomSnapshotPort {
    override suspend fun schemaPolicy(): CutoverArchivePolicy {
        val schema = inspectAndVerifySchema(database.openHelper.readableDatabase)
        return CutoverArchivePolicy(
            schemaVersion = SCHEMA_VERSION,
            requiredTableSchemaDigests = schema.tableDigests,
            historicalOnlyTables = setOf(PAIRING_TABLE)
        )
    }

    override suspend fun captureTables(): List<CutoverTableSection> = database.withTransaction {
        val sql = database.openHelper.writableDatabase
        val schema = inspectAndVerifySchema(sql)
        captureTables(sql, schema, historicalizePairing = true)
    }

    override suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState =
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            val schema = inspectAndVerifySchema(sql)
            validateArchiveTables(archive, schema)
            val empty = RESTORE_ORDER.all { rowCount(sql, it) == 0L }
            val captured = captureTables(sql, schema, historicalizePairing = false)
            CutoverGenerationState(
                isEmpty = empty,
                matchesArchive = captured == archive.tables.sortedBy { it.name }
            )
        }

    override suspend fun restore(archive: CutoverArchiveV2) {
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            val schema = inspectAndVerifySchema(sql)
            validateArchiveTables(archive, schema)
            check(RESTORE_ORDER.all { rowCount(sql, it) == 0L }) { "target Room is not empty" }
            val sections = archive.tables.associateBy { it.name }
            RESTORE_ORDER.forEach { tableName ->
                restoreTable(sql, schema.tables.getValue(tableName), sections.getValue(tableName))
            }
        }
    }

    override suspend fun clear() {
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            inspectAndVerifySchema(sql)
            RESTORE_ORDER.asReversed().forEach { tableName ->
                sql.execSQL("DELETE FROM ${quoteIdentifier(tableName)}")
            }
            if (tableExists(sql, "sqlite_sequence")) {
                RESTORE_ORDER.forEach { tableName ->
                    sql.compileStatement("DELETE FROM sqlite_sequence WHERE name = ?").use { statement ->
                        statement.bindString(1, tableName)
                        statement.executeUpdateDelete()
                    }
                }
            }
        }
    }

    private fun inspectAndVerifySchema(sql: SupportSQLiteDatabase): RoomSchema {
        val tableNames = mutableListOf<String>()
        sql.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata') " +
                "ORDER BY name"
        ).use { cursor ->
            while (cursor.moveToNext()) tableNames += cursor.getString(0)
        }
        check(tableNames == EXPECTED_TABLES) {
            "Room v9 schema mismatch: expected tables=$EXPECTED_TABLES actual=$tableNames"
        }

        val tables = tableNames.associateWith { inspectTable(sql, it) }
        val tableDigests = tables.mapValues { (_, table) -> sha256(table.descriptor.toByteArray(Charsets.UTF_8)) }
            .toSortedMap()
        val overall = buildString {
            tableDigests.forEach { (name, digest) -> append(name).append('=').append(digest).append('\n') }
        }.toByteArray(Charsets.UTF_8).let(::sha256)
        check(overall == EXPECTED_SCHEMA_DIGEST) {
            "Room v9 schema mismatch: expected=$EXPECTED_SCHEMA_DIGEST actual=$overall"
        }
        return RoomSchema(tables, tableDigests)
    }

    private fun inspectTable(sql: SupportSQLiteDatabase, tableName: String): TableMeta {
        val columns = mutableListOf<ColumnMeta>()
        val descriptor = StringBuilder()
        sql.query("PRAGMA table_info(${quoteIdentifier(tableName)})").use { cursor ->
            while (cursor.moveToNext()) {
                val column = ColumnMeta(
                    cid = cursor.getInt(cursor.getColumnIndexOrThrow("cid")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    declaredType = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                    defaultValue = cursor.stringOrNull("dflt_value"),
                    primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                )
                requireSafeIdentifier(column.name)
                columns += column
                descriptor.append("column|").append(column.cid).append('|').append(column.name)
                    .append('|').append(column.declaredType.uppercase()).append('|').append(column.notNull)
                    .append('|').append(column.defaultValue ?: "<null>").append('|')
                    .append(column.primaryKeyPosition).append('\n')
            }
        }
        check(columns.isNotEmpty()) { "Room v9 schema mismatch: $tableName has no columns" }
        check(columns.any { it.primaryKeyPosition > 0 }) {
            "Room v9 schema mismatch: $tableName has no primary key"
        }

        val foreignKeys = mutableListOf<String>()
        sql.query("PRAGMA foreign_key_list(${quoteIdentifier(tableName)})").use { cursor ->
            while (cursor.moveToNext()) {
                foreignKeys += listOf(
                    cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("seq")),
                    cursor.getString(cursor.getColumnIndexOrThrow("table")),
                    cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    cursor.getString(cursor.getColumnIndexOrThrow("to")),
                    cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                    cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                    cursor.getString(cursor.getColumnIndexOrThrow("match"))
                ).joinToString("|")
            }
        }
        foreignKeys.sorted().forEach { descriptor.append("foreign|").append(it).append('\n') }

        val indexes = mutableListOf<String>()
        sql.query("PRAGMA index_list(${quoteIdentifier(tableName)})").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                requireSafeIdentifier(name)
                val indexColumns = mutableListOf<String>()
                sql.query("PRAGMA index_info(${quoteIdentifier(name)})").use { info ->
                    while (info.moveToNext()) {
                        indexColumns += "${info.getInt(info.getColumnIndexOrThrow("seqno"))}:" +
                            info.getString(info.getColumnIndexOrThrow("name"))
                    }
                }
                indexes += listOf(
                    name,
                    cursor.getInt(cursor.getColumnIndexOrThrow("unique")),
                    cursor.getString(cursor.getColumnIndexOrThrow("origin")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("partial")),
                    indexColumns.joinToString(",")
                ).joinToString("|")
            }
        }
        indexes.sorted().forEach { descriptor.append("index|").append(it).append('\n') }
        return TableMeta(tableName, columns.sortedBy { it.cid }, descriptor.toString())
    }

    private fun captureTables(
        sql: SupportSQLiteDatabase,
        schema: RoomSchema,
        historicalizePairing: Boolean
    ): List<CutoverTableSection> =
        EXPECTED_TABLES.map { tableName ->
            val meta = schema.tables.getValue(tableName)
            val rows = mutableListOf<CutoverRowPayload>()
            sql.query("SELECT * FROM ${quoteIdentifier(tableName)}").use { cursor ->
                val cursorIndices = meta.columns.map { cursor.getColumnIndexOrThrow(it.name) }
                val primaryIndices = meta.columns.withIndex()
                    .filter { it.value.primaryKeyPosition > 0 }
                    .sortedBy { it.value.primaryKeyPosition }
                    .map { it.index }
                while (cursor.moveToNext()) {
                    val values = cursorIndices.mapIndexed { index, cursorIndex ->
                        val override = if (historicalizePairing) {
                            historicalPairingOverride(tableName, meta, cursor, index)
                        } else {
                            null
                        }
                        readCell(cursor, cursorIndex, override)
                    }
                    val rowBytes = encodeCells(values)
                    val keyBytes = encodeCells(primaryIndices.map(values::get))
                    rows += CutoverRowPayload(base64Url(keyBytes), base64Url(rowBytes))
                }
            }
            val sortedRows = rows.sortedBy { it.orderKeyBase64Url }
            check(sortedRows.zipWithNext().none { (left, right) ->
                left.orderKeyBase64Url == right.orderKeyBase64Url
            }) { "duplicate primary key in $tableName" }
            CutoverTableSection(
                name = tableName,
                schemaDigest = schema.tableDigests.getValue(tableName),
                restorationMode = if (tableName == PAIRING_TABLE) {
                    CutoverRestorationMode.HISTORICAL_ONLY
                } else {
                    CutoverRestorationMode.EXACT
                },
                rows = sortedRows
            )
        }

    private fun historicalPairingOverride(
        tableName: String,
        meta: TableMeta,
        cursor: Cursor,
        columnIndex: Int
    ): Long? {
        if (tableName != PAIRING_TABLE || meta.columns[columnIndex].name != "revokedAt") return null
        val revokedIndex = cursor.getColumnIndexOrThrow("revokedAt")
        if (!cursor.isNull(revokedIndex)) return null
        return cursor.getLong(cursor.getColumnIndexOrThrow("approvedAt"))
    }

    private fun restoreTable(
        sql: SupportSQLiteDatabase,
        meta: TableMeta,
        section: CutoverTableSection
    ) {
        val columnSql = meta.columns.joinToString(",") { quoteIdentifier(it.name) }
        val placeholders = List(meta.columns.size) { "?" }.joinToString(",")
        val insert = "INSERT INTO ${quoteIdentifier(meta.name)} ($columnSql) VALUES ($placeholders)"
        val primaryIndices = meta.columns.withIndex()
            .filter { it.value.primaryKeyPosition > 0 }
            .sortedBy { it.value.primaryKeyPosition }
            .map { it.index }
        sql.compileStatement(insert).use { statement ->
            section.rows.forEach { row ->
                val values = decodeCells(decodeBase64Url(row.canonicalRowBase64Url), meta.columns.size)
                values.forEachIndexed { index, value ->
                    validateCell(meta.name, meta.columns[index], value)
                }
                val expectedOrderKey = base64Url(encodeCells(primaryIndices.map(values::get)))
                require(row.orderKeyBase64Url == expectedOrderKey) {
                    "row order key mismatch for ${meta.name}"
                }
                statement.clearBindings()
                values.forEachIndexed { index, value -> value.bind(statement, index + 1) }
                statement.executeInsert()
            }
        }
    }

    private fun validateCell(tableName: String, column: ColumnMeta, value: SqlCell) {
        if (value == SqlCell.Null) {
            require(!column.notNull && column.primaryKeyPosition == 0) {
                "null row value for $tableName.${column.name}"
            }
            return
        }
        val matches = when (column.affinity) {
            SqlAffinity.INTEGER -> value is SqlCell.IntegerValue
            SqlAffinity.REAL -> value is SqlCell.RealValue
            SqlAffinity.TEXT -> value is SqlCell.TextValue
            SqlAffinity.BLOB -> value is SqlCell.BlobValue
        }
        require(matches) { "row type mismatch for $tableName.${column.name}" }
    }

    private fun validateArchiveTables(archive: CutoverArchiveV2, schema: RoomSchema) {
        check(archive.schemaVersion == SCHEMA_VERSION) { "archive Room schema version mismatch" }
        check(archive.tables.map { it.name }.sorted() == EXPECTED_TABLES) { "archive table census mismatch" }
        archive.tables.forEach { table ->
            check(table.schemaDigest == schema.tableDigests.getValue(table.name)) {
                "archive schema digest mismatch for ${table.name}"
            }
            val expectedMode = if (table.name == PAIRING_TABLE) {
                CutoverRestorationMode.HISTORICAL_ONLY
            } else {
                CutoverRestorationMode.EXACT
            }
            check(table.restorationMode == expectedMode) { "archive restoration mode mismatch for ${table.name}" }
        }
    }

    private fun readCell(cursor: Cursor, index: Int, overrideLong: Long?): SqlCell {
        if (overrideLong != null) return SqlCell.IntegerValue(overrideLong)
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> SqlCell.Null
            Cursor.FIELD_TYPE_INTEGER -> SqlCell.IntegerValue(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> SqlCell.RealValue(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> SqlCell.TextValue(cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> SqlCell.BlobValue(cursor.getBlob(index))
            else -> error("unsupported SQLite cell type")
        }
    }

    private fun encodeCells(values: List<SqlCell>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(values.size)
            values.forEach { value -> value.writeTo(output) }
        }
        bytes.toByteArray()
    }

    private fun decodeCells(bytes: ByteArray, expectedCount: Int): List<SqlCell> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()
            require(count == expectedCount) { "row column count mismatch" }
            val values = List(count) { SqlCell.readFrom(input, bytes.size) }
            require(input.read() == -1) { "trailing row bytes" }
            values
        }

    private fun rowCount(sql: SupportSQLiteDatabase, tableName: String): Long =
        sql.query("SELECT COUNT(*) FROM ${quoteIdentifier(tableName)}").use { cursor ->
            check(cursor.moveToFirst()) { "missing row count for $tableName" }
            cursor.getLong(0)
        }

    private data class RoomSchema(
        val tables: Map<String, TableMeta>,
        val tableDigests: Map<String, String>
    )

    private data class TableMeta(
        val name: String,
        val columns: List<ColumnMeta>,
        val descriptor: String
    )

    private data class ColumnMeta(
        val cid: Int,
        val name: String,
        val declaredType: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int
    ) {
        val affinity: SqlAffinity
            get() {
                val type = declaredType.uppercase()
                return when {
                    "INT" in type -> SqlAffinity.INTEGER
                    "CHAR" in type || "CLOB" in type || "TEXT" in type -> SqlAffinity.TEXT
                    "REAL" in type || "FLOA" in type || "DOUB" in type -> SqlAffinity.REAL
                    else -> SqlAffinity.BLOB
                }
            }
    }

    private enum class SqlAffinity {
        INTEGER,
        REAL,
        TEXT,
        BLOB
    }

    private sealed interface SqlCell {
        fun writeTo(output: DataOutputStream)
        fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int)

        data object Null : SqlCell {
            override fun writeTo(output: DataOutputStream) {
                output.writeByte(TAG_NULL)
                output.writeInt(0)
            }

            override fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int) {
                statement.bindNull(index)
            }
        }

        data class IntegerValue(val value: Long) : SqlCell {
            override fun writeTo(output: DataOutputStream) {
                output.writeByte(TAG_INTEGER)
                output.writeInt(Long.SIZE_BYTES)
                output.writeLong(value)
            }

            override fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int) {
                statement.bindLong(index, value)
            }
        }

        data class RealValue(val value: Double) : SqlCell {
            override fun writeTo(output: DataOutputStream) {
                output.writeByte(TAG_REAL)
                output.writeInt(Long.SIZE_BYTES)
                output.writeLong(value.toRawBits())
            }

            override fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int) {
                statement.bindDouble(index, value)
            }
        }

        data class TextValue(val value: String) : SqlCell {
            override fun writeTo(output: DataOutputStream) {
                val bytes = strictUtf8(value)
                output.writeByte(TAG_TEXT)
                output.writeInt(bytes.size)
                output.write(bytes)
            }

            override fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int) {
                statement.bindString(index, value)
            }
        }

        data class BlobValue(val value: ByteArray) : SqlCell {
            override fun writeTo(output: DataOutputStream) {
                output.writeByte(TAG_BLOB)
                output.writeInt(value.size)
                output.write(value)
            }

            override fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int) {
                statement.bindBlob(index, value)
            }

            override fun equals(other: Any?): Boolean = other is BlobValue && value.contentEquals(other.value)
            override fun hashCode(): Int = value.contentHashCode()
        }

        companion object {
            fun readFrom(input: DataInputStream, totalBytes: Int): SqlCell {
                val tag = input.readUnsignedByte()
                val length = input.readInt()
                require(length >= 0 && length <= totalBytes) { "invalid row cell length" }
                return when (tag) {
                    TAG_NULL -> {
                        require(length == 0) { "invalid null cell length" }
                        Null
                    }
                    TAG_INTEGER -> {
                        require(length == Long.SIZE_BYTES) { "invalid integer cell length" }
                        IntegerValue(input.readLong())
                    }
                    TAG_REAL -> {
                        require(length == Long.SIZE_BYTES) { "invalid real cell length" }
                        RealValue(Double.fromBits(input.readLong()))
                    }
                    TAG_TEXT -> TextValue(strictUtf8(input.readExact(length)))
                    TAG_BLOB -> BlobValue(input.readExact(length))
                    else -> throw IllegalArgumentException("unknown row cell tag")
                }
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 9
        const val PAIRING_TABLE = "provider_pairing_records"
        const val EXPECTED_SCHEMA_DIGEST =
            "sha256:e63a8f65f60daba78216b989ddc82b3919e583641e1457f3b5061b8729b09d4c"
        const val TAG_NULL = 0
        const val TAG_INTEGER = 1
        const val TAG_REAL = 2
        const val TAG_TEXT = 3
        const val TAG_BLOB = 4

        val EXPECTED_TABLES = listOf(
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

        val RESTORE_ORDER = listOf(
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
            PAIRING_TABLE,
            "recovery_checkpoints",
            "release_receipts",
            "run_sessions",
            "test_attempts",
            "test_results",
            "trusted_quota_entries",
            "unverified_attempt_records"
        )

        fun sha256(bytes: ByteArray): String = "sha256:" +
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun decodeBase64Url(value: String): ByteArray {
            require(value.isNotEmpty() && !value.contains('=')) { "invalid row Base64URL" }
            val decoded = try {
                Base64.getUrlDecoder().decode(value)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("invalid row Base64URL", failure)
            }
            require(base64Url(decoded) == value) { "non-canonical row Base64URL" }
            return decoded
        }

        fun strictUtf8(value: String): ByteArray {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val encoded = encoder.encode(CharBuffer.wrap(value))
            return ByteArray(encoded.remaining()).also(encoded::get)
        }

        fun strictUtf8(value: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(value)).toString()
        }

        fun quoteIdentifier(value: String): String {
            requireSafeIdentifier(value)
            return "`$value`"
        }

        fun requireSafeIdentifier(value: String) {
            require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "unsafe SQLite identifier" }
        }

        fun Cursor.stringOrNull(column: String): String? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getString(index)
        }

        fun tableExists(sql: SupportSQLiteDatabase, tableName: String): Boolean =
            sql.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(tableName)
            ).use { it.moveToFirst() }

        fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)
    }
}
