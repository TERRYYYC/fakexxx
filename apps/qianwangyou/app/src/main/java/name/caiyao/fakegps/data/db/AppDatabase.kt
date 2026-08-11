package name.caiyao.fakegps.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fakegps.db",
                ).addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }

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
