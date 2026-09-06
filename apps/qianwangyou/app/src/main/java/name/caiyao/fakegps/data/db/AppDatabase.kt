package name.caiyao.fakegps.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

@Database(entities = [ProfileEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    companion object {
        private const val DATABASE_NAME = "fakegps.db"
        private const val BACKUP_SUFFIX = ".legacy-v0-backup"
        private const val STAGING_SUFFIX = ".legacy-v0-migrating"
        private const val NEW_V2_COLUMN = "unavailable_fields"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            val appContext = context.applicationContext
            ensureLegacyDatabaseRecovered(appContext)
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildRoomDatabase(appContext, DATABASE_NAME).also { INSTANCE = it }
            }
        }

        /** Test-only lifecycle reset for instrumentation fixtures that replace fakegps.db. */
        fun closeInstanceForTests() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /**
         * Repairs only the pre-Room v0 shape proved by the legacy fixture. The old database is
         * never deleted: rows are copied into a separately validated Room v2 staging database,
         * then the original is retained under [BACKUP_SUFFIX] before the staged database becomes
         * the live file. Re-entry after a process death resumes from those files.
         *
         * Call this before any direct read of fakegps.db; Room callers receive it through
         * [getInstance].
         */
        /**
         * @return true when this call promoted (or resumed promotion of) a legacy database.
         * Callers with their own SQLite handle use this to discard a handle that may point at a
         * file that was just moved to the retained backup.
         */
        fun ensureLegacyDatabaseRecovered(context: Context): Boolean {
            return synchronized(this) {
                val appContext = context.applicationContext
                val live = appContext.getDatabasePath(DATABASE_NAME)
                val backup = appContext.getDatabasePath("$DATABASE_NAME$BACKUP_SUFFIX")
                val staging = appContext.getDatabasePath("$DATABASE_NAME$STAGING_SUFFIX")

                when {
                    live.exists() && isLegacyV0Database(live) -> {
                        check(!backup.exists()) {
                            "Refusing to overwrite preserved legacy database: ${backup.name}"
                        }
                        // A stage beside a still-live source may have been made before a writer
                        // appended more WAL frames. It is not authoritative; rebuild it from the
                        // current source rather than ever promoting a stale snapshot.
                        removeStagingDatabase(staging)
                        try {
                            ensureStagingDatabase(appContext, live, staging)
                            moveDatabaseFiles(live, backup, discardCheckpointedSharedMemory = true)
                            promoteStagingDatabase(staging, live)
                        } catch (failure: Throwable) {
                            removeStagingDatabase(staging)
                            throw failure
                        }
                        true
                    }

                    !live.exists() && backup.exists() -> {
                        ensureStagingDatabase(appContext, backup, staging)
                        promoteStagingDatabase(staging, live)
                        true
                    }

                    else -> false
                }
            }
        }

        private fun buildRoomDatabase(context: Context, databaseName: String): AppDatabase =
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).addMigrations(MIGRATION_1_2).build()

        private fun ensureStagingDatabase(context: Context, legacy: File, staging: File) {
            if (staging.exists()) {
                check(isCompletedRoomV2Database(staging)) {
                    "Legacy recovery staging database is incomplete: ${staging.name}"
                }
                return
            }

            SQLiteDatabase.openDatabase(
                legacy.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { source ->
                check(isLegacyV0Database(source)) {
                    "Legacy recovery source no longer matches the v0 non-Room schema: ${legacy.name}"
                }
                // The source main file is renamed before the staged file is promoted. Do not
                // cross that boundary until every WAL frame is in the main database: a reader
                // holding a WAL snapshot must make recovery fail closed rather than preserve a
                // backup that is missing committed profile rows.
                requireFullyCheckpointed(source, legacy)

                val sourceColumns = tableColumns(source, "temp")
                val sourceCount = tableRowCount(source, "temp")
                val stagedRoom = buildRoomDatabase(context, staging.name)
                try {
                    val target = stagedRoom.openHelper.writableDatabase
                    val targetColumns = tableColumns(target, "temp")
                    val expectedLegacyColumns = targetColumns.filterNot { it == NEW_V2_COLUMN }
                    check(sourceColumns.toSet() == expectedLegacyColumns.toSet() &&
                        sourceColumns.size == expectedLegacyColumns.size) {
                        "Legacy recovery refuses an unrecognized temp schema"
                    }

                    val copiedColumns = expectedLegacyColumns.joinToString(", ") { quoteIdentifier(it) }
                    target.execSQL("ATTACH DATABASE ? AS legacy_source", arrayOf(legacy.absolutePath))
                    try {
                        target.beginTransaction()
                        try {
                            target.execSQL(
                                "INSERT INTO `temp` ($copiedColumns) " +
                                    "SELECT $copiedColumns FROM legacy_source.`temp`",
                            )
                            check(tableRowCount(target, "temp") == sourceCount) {
                                "Legacy recovery copied a different number of profile rows"
                            }
                            target.setTransactionSuccessful()
                        } finally {
                            target.endTransaction()
                        }
                    } finally {
                        target.execSQL("DETACH DATABASE legacy_source")
                    }
                    check(integrityCheck(target) == "ok") {
                        "Legacy recovery staging integrity check failed"
                    }
                    requireFullyCheckpointed(target, staging)
                } finally {
                    stagedRoom.close()
                }
                closeLegacyWal(source, legacy)
            }

            check(isCompletedRoomV2Database(staging)) {
                "Legacy recovery staging database did not reach Room v2"
            }
        }

        private fun isLegacyV0Database(file: File): Boolean = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use(::isLegacyV0Database)

        private fun isLegacyV0Database(database: SQLiteDatabase): Boolean =
            pragmaUserVersion(database) == 0 &&
                hasTable(database, "temp") &&
                !hasTable(database, "room_master_table")

        private fun isCompletedRoomV2Database(file: File): Boolean = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            pragmaUserVersion(database) == 2 &&
                hasTable(database, "temp") &&
                hasTable(database, "room_master_table")
        }

        private fun promoteStagingDatabase(staging: File, live: File) {
            check(!live.exists()) { "Legacy recovery live database unexpectedly exists" }
            check(isCompletedRoomV2Database(staging)) {
                "Legacy recovery refuses to promote an incomplete staging database"
            }
            moveDatabaseFiles(staging, live)
        }

        private fun moveDatabaseFiles(
            source: File,
            destination: File,
            discardCheckpointedSharedMemory: Boolean = false,
        ) {
            check(!destination.exists()) { "Database destination already exists: ${destination.name}" }
            requireNoDatabaseSidecars(source, discardCheckpointedSharedMemory)
            check(source.renameTo(destination)) {
                "Unable to move database ${source.name} to ${destination.name}"
            }
        }

        private fun removeStagingDatabase(staging: File) {
            listOf(staging, *listOf("-journal", "-wal", "-shm").map { File(staging.absolutePath + it) }.toTypedArray())
                .filter(File::exists)
                .forEach { file ->
                    check(file.delete()) { "Unable to discard stale legacy recovery staging file ${file.name}" }
                }
        }

        private fun requireFullyCheckpointed(database: SQLiteDatabase, file: File) {
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst()) { "WAL checkpoint did not return a result" }
                val busy = cursor.getInt(0)
                val logFrames = cursor.getInt(1)
                val checkpointedFrames = cursor.getInt(2)
                check(busy == 0 &&
                    ((logFrames == -1 && checkpointedFrames == -1) ||
                        (logFrames == 0 && checkpointedFrames == 0))) {
                    "Legacy recovery refuses to move ${file.name} while WAL frames are still " +
                        "owned by another reader (busy=$busy, log=$logFrames, checkpointed=$checkpointedFrames)"
                }
            }
        }

        private fun requireFullyCheckpointed(database: SupportSQLiteDatabase, file: File) {
            database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                check(cursor.moveToFirst()) { "WAL checkpoint did not return a result" }
                val busy = cursor.getInt(0)
                val logFrames = cursor.getInt(1)
                val checkpointedFrames = cursor.getInt(2)
                check(busy == 0 &&
                    ((logFrames == -1 && checkpointedFrames == -1) ||
                        (logFrames == 0 && checkpointedFrames == 0))) {
                    "Legacy recovery refuses to move ${file.name} while WAL frames remain " +
                        "(busy=$busy, log=$logFrames, checkpointed=$checkpointedFrames)"
                }
            }
        }

        private fun requireNoDatabaseSidecars(file: File, discardCheckpointedSharedMemory: Boolean) {
            removeInactiveRollbackJournal(file)
            val wal = File(file.absolutePath + "-wal")
            check(!wal.exists()) {
                "Legacy recovery refuses to rename ${file.name} with a residual WAL"
            }
            val shm = File(file.absolutePath + "-shm")
            if (shm.exists()) {
                check(discardCheckpointedSharedMemory) {
                    "Legacy recovery refuses to rename ${file.name} with an unexpected shared-memory sidecar"
                }
                // closeLegacyWal() obtained DELETE mode after the checkpoint: it can only do so
                // after active WAL readers are gone. SHM is an index/cache, never a source of
                // committed rows, so this post-checkpoint residue is safe to discard pre-rename.
                check(shm.delete()) { "Unable to discard checkpointed shared-memory sidecar ${shm.name}" }
            }
        }

        private fun closeLegacyWal(database: SQLiteDatabase, file: File) {
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true)) {
                    "Legacy recovery refuses to move ${file.name} while SQLite cannot leave WAL mode"
                }
            }
        }

        /** A zero-header rollback journal is a clean-close PERSIST/TRUNCATE residue, not data. */
        private fun removeInactiveRollbackJournal(file: File) {
            val journal = File(file.absolutePath + "-journal")
            if (!journal.exists()) return
            val inactive = journal.length() == 0L || RandomAccessFile(journal, "r").use { input ->
                val header = ByteArray(8)
                input.read(header) == header.size && header.all { it == 0.toByte() }
            }
            check(inactive) {
                "Legacy recovery refuses to rename ${file.name} with an active rollback journal"
            }
            check(journal.delete()) { "Unable to remove inactive rollback journal ${journal.name}" }
        }

        private fun tableColumns(database: SQLiteDatabase, table: String): List<String> =
            database.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }

        private fun tableColumns(database: SupportSQLiteDatabase, table: String): List<String> =
            database.query("PRAGMA table_info(${quoteIdentifier(table)})").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }

        private fun tableRowCount(database: SQLiteDatabase, table: String): Long =
            database.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(table)}", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }

        private fun tableRowCount(database: SupportSQLiteDatabase, table: String): Long =
            database.query("SELECT COUNT(*) FROM ${quoteIdentifier(table)}").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }

        private fun pragmaUserVersion(database: SQLiteDatabase): Int =
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

        private fun hasTable(database: SQLiteDatabase, name: String): Boolean =
            database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name),
            ).use { it.moveToFirst() }

        private fun integrityCheck(database: SupportSQLiteDatabase): String =
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }

        private fun quoteIdentifier(identifier: String): String =
            "\"${identifier.replace("\"", "\"\"")}\""

        /**
         * Existing rows keep null, which is exactly the pre-v2 behavior: every null typed column
         * passes the real device value through.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE temp ADD COLUMN unavailable_fields TEXT DEFAULT NULL")
            }
        }
    }
}
