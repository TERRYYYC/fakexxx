package name.caiyao.fakegps.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileSummary

class ProfileRepository(
    private val db: AppDatabase,
    private val context: Context? = null,
    private val publishOverride: ((PublishRequest) -> Boolean)? = null,
) {

    data class SaveResult(val id: Long, val published: Boolean)

    /**
     * [firstInsertedId] carries the FIRST inserted row of the batch (file order), so the
     * import-finished dialog can offer the one-tap anchor without a second query. Null when
     * everything was already present (all duplicates).
     */
    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val firstInsertedId: Long? = null,
    )
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
        val insertedIds = if (plan.toInsert.isNotEmpty()) dao.insertAll(plan.toInsert) else emptyList()
        ImportResult(
            imported = insertedIds.size,
            duplicates = plan.duplicates,
            firstInsertedId = insertedIds.firstOrNull(),
        )
    }

    /**
     * P0.1-3 一键锚定：把显式选中的档案发布为生效配置（republish 的 explicit-id 形态）。
     * 已验证发布时 ConfigPrefsSync 会把该 id 作为 durable activeProfileId 持久化
     * （ConfigPublicationContract.onVerifiedPublish，持久化规则由 PublicationStateMachineTest 钉死），
     * 这正是「导入→锚定」从滚 15+ 屏进编辑页点保存变成一次点击的机制。
     */
    suspend fun setActiveProfile(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        republish(profileId = profileId)
    }

    /**
     * P3.1 运动链（来源 a）：attach/detach an independent route (parsed by
     * [name.caiyao.fakegps.motion.RouteCsvParser]) to a profile, then republish so the motion
     * chain sees the new route on the next session. Null json = detach (back to single point).
     * Returns false when the profile id does not exist.
     */
    suspend fun attachRoute(profileId: Long, routeWaypointsJson: String?): Boolean =
        withContext(Dispatchers.IO) {
            val existing = dao.getById(profileId) ?: return@withContext false
            dao.update(existing.copy(routeWaypointsJson = routeWaypointsJson))
            republish(profileId = profileId)
            true
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
