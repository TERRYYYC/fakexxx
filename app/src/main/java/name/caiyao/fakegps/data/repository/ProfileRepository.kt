package name.caiyao.fakegps.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileSummary
import kotlinx.coroutines.flow.Flow

class ProfileRepository(
    private val db: AppDatabase,
    private val context: Context? = null,
    private val publishOverride: ((PublishRequest) -> Boolean)? = null,
) {

    data class SaveResult(val id: Long, val published: Boolean)
    data class ImportResult(val imported: Int, val duplicates: Int)
    data class PublishRequest(val profileId: Long?, val clearIfMissing: Boolean)

    private val dao get() = db.profileDao()

    fun observeAll(): Flow<List<ProfileSummary>> = dao.observeAll()

    fun observeEntities(): Flow<List<ProfileEntity>> = dao.observeEntities()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getById(id: Long): ProfileEntity? = dao.getById(id)

    suspend fun save(profile: ProfileEntity): SaveResult {
        val id = if (profile.id == 0L) {
            dao.insert(profile)
        } else {
            dao.update(profile)
            profile.id
        }
        return SaveResult(id, republish(profileId = id))
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
        republish(clearIfMissing = true)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        republish(clearIfMissing = true)
    }

    /**
     * Adds a confirmed archive batch atomically without changing the published hook snapshot.
     *
     * Duplicate detection is deliberately repeated inside the transaction: the database may have
     * changed between file preview and confirmation. Imported ids are always database-generated.
     */
    suspend fun importAll(candidates: List<ProfileEntity>): ImportResult = db.withTransaction {
        val plan = ProfileImportPlanner.plan(dao.getAll(), candidates)
        if (plan.toInsert.isNotEmpty()) dao.insertAll(plan.toInsert)
        ImportResult(imported = plan.toInsert.size, duplicates = plan.duplicates)
    }

    /**
     * Re-publish the effective config to the world-readable prefs the hook reads.
     *
     * This lives in the REPOSITORY, not in a screen: the app has two parallel UIs (legacy
     * Fragments + Compose) and wiring the sync per-screen already caused a real bug — saving a
     * new location from the Compose UI left the hook running on a profile the user had deleted
     * (DB said 50.615936,26.278774 while the hook still read 50.257091,28.688807). Every Compose
     * create/update/delete funnels through here; the legacy editor passes its saved id directly.
     */
    private fun republish(
        profileId: Long? = null,
        clearIfMissing: Boolean = false,
    ): Boolean {
        val request = PublishRequest(profileId, clearIfMissing)
        val publisher = publishOverride ?: context?.let { ctx ->
            { value: PublishRequest ->
                ConfigPrefsSync.sync(ctx, value.profileId, value.clearIfMissing)
            }
        }
            ?: return true
        val published = runCatching { publisher(request) }
            .onFailure { Log.e("ProfileRepository", "config republish failed", it) }
            .getOrDefault(false)
        if (!published) {
            Log.e("ProfileRepository", "config republish returned false")
        }
        return published
    }
}
