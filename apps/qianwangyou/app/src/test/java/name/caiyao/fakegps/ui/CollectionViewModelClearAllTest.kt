package name.caiyao.fakegps.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.ui.screen.collection.CollectionViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #129 收藏页「清空」两步确认（真机数据丢失事故的 UX 修复）：
 * 收藏页「清空」与「导入 CSV/Excel」相邻，一次误触曾把 3 个档案（含生效指针）全清且不可恢复。
 * 现在清空必须经过 request →（UI 确认对话框）→ confirm 两步：请求只挂起、取消无副作用、
 * 未请求的 confirm 被拦截为 no-op，唯一的删库执行点在确认。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CollectionViewModelClearAllTest {

    private class RecordingPublisher {
        val requests = mutableListOf<ProfileRepository.PublishRequest>()
        var nextResult: Boolean = true
        fun publish(request: ProfileRepository.PublishRequest): Boolean {
            requests += request
            return nextResult
        }
    }

    private lateinit var db: AppDatabase
    private val publisher = RecordingPublisher()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): CollectionViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ProfileRepository(db, app, publishOverride = publisher::publish)
        return CollectionViewModel(app, repoOverride = repo)
    }

    private fun insertProfiles(count: Int) = runBlocking {
        repeat(count) { i ->
            db.profileDao().insert(
                ProfileEntity(addname = "loc-$i", latitude = 50.0 + i, longitude = 24.0 + i),
            )
        }
    }

    private fun profileCount(): Int = runBlocking { db.profileDao().getAll().size }

    @Test
    fun `requesting clear alone opens the pending state and deletes nothing`() {
        val vm = newViewModel()
        insertProfiles(2)

        vm.requestClearAll()

        assertTrue("request must open the pending-clear state", vm.pendingClearAll.value)
        assertEquals("request alone must not touch the database", 2, profileCount())
    }

    @Test
    fun `confirm without a prior request is intercepted and deletes nothing`() {
        val vm = newViewModel()
        insertProfiles(3)

        vm.confirmClearAll()

        assertEquals("unconfirmed clear must be a no-op", 3, profileCount())
        assertFalse(vm.pendingClearAll.value)
    }

    @Test
    fun `confirming the request clears every profile and republishes with clearIfMissing`() {
        val vm = newViewModel()
        insertProfiles(2)
        vm.requestClearAll()

        vm.confirmClearAll()

        assertEquals("confirmed clear removes every profile", 0, profileCount())
        assertTrue(
            "clear must still walk the publish chain so the hook stops serving deleted profiles",
            publisher.requests.last().clearIfMissing,
        )
    }

    @Test
    fun `cancelling the request deletes nothing and closes the pending state`() {
        val vm = newViewModel()
        insertProfiles(2)
        vm.requestClearAll()

        vm.cancelClearAll()

        assertFalse("cancel must close the dialog state", vm.pendingClearAll.value)
        assertEquals("cancel must have no side effects", 2, profileCount())
    }
}
