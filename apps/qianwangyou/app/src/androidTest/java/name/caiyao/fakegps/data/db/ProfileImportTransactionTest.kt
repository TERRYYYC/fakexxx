package name.caiyao.fakegps.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import name.caiyao.fakegps.data.repository.ProfileRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileImportTransactionTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importAll_preservesExistingRows_skipsDuplicates_andDoesNotPublish() = runBlocking {
        val dao = db.profileDao()
        val existingId = dao.insert(ProfileEntity(addname = "existing", tac = 1))
        val publishes = AtomicInteger()
        val repo = ProfileRepository(db, publishOverride = { publishes.incrementAndGet(); true })

        val result = repo.importAll(
            listOf(
                ProfileEntity(id = 88, addname = "existing", tac = 1),
                ProfileEntity(id = 89, addname = "new", tac = 2),
            ),
        )

        assertEquals(1, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(0, publishes.get())
        assertEquals(
            listOf(
                ProfileEntity(id = existingId, addname = "existing", tac = 1),
                ProfileEntity(id = existingId + 1, addname = "new", tac = 2),
            ),
            dao.getAll(),
        )
    }

    @Test
    fun importAll_rollsBackEveryRowWhenOneInsertFails() = runBlocking {
        val dao = db.profileDao()
        val originalId = dao.insert(ProfileEntity(addname = "original", tac = 1))
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_bad_import
            BEFORE INSERT ON temp
            WHEN NEW.addname = 'explode'
            BEGIN SELECT RAISE(ABORT, 'forced import failure'); END
            """.trimIndent(),
        )
        val repo = ProfileRepository(db)

        assertThrows(Exception::class.java) {
            runBlocking {
                repo.importAll(
                    listOf(
                        ProfileEntity(addname = "would-have-inserted", tac = 2),
                        ProfileEntity(addname = "explode", tac = 3),
                    ),
                )
            }
        }

        assertEquals(listOf(ProfileEntity(id = originalId, addname = "original", tac = 1)), dao.getAll())
    }

    @Test
    fun savePublishesTheExactRowThatWasSaved() = runBlocking {
        var request: ProfileRepository.PublishRequest? = null
        val repo = ProfileRepository(db, publishOverride = { value ->
            request = value
            true
        })
        db.profileDao().insert(ProfileEntity(addname = "older", latitude = 1.0))

        val result = repo.save(ProfileEntity(addname = "selected", latitude = 22.5461))

        assertEquals(ProfileRepository.PublishRequest(result.id, clearIfMissing = false), request)
        assertEquals(true, result.published)
    }

    @Test
    fun deleteAllowsTheMissingActiveRowToClearPublication() = runBlocking {
        var request: ProfileRepository.PublishRequest? = null
        val repo = ProfileRepository(db, publishOverride = { value ->
            request = value
            true
        })
        val id = db.profileDao().insert(ProfileEntity(addname = "selected", latitude = 22.5461))

        repo.deleteById(id)

        assertEquals(ProfileRepository.PublishRequest(profileId = null, clearIfMissing = true), request)
    }
}
