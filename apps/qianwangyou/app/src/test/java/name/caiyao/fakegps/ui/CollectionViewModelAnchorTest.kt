package name.caiyao.fakegps.ui

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.ui.screen.collection.CollectionViewModel
import name.caiyao.fakegps.ui.screen.collection.ProfileImportUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * P0.1-3 锚定链路 oracle（Robolectric lane）：
 * 导入完成 → Success 投影携带「首行档案名 + 首条插入 id」→ 一键锚定
 * （导入完成对话框按钮 / 列表长按菜单共用 [CollectionViewModel.setActiveProfile]）
 * → 既有 publish 链被以「显式 profileId」调用 —— 已验证发布时该 id 被
 * ConfigPublicationContract.onVerifiedPublish 持久化为 activeProfileId
 * （持久化规则由 config/PublicationStateMachineTest 单独钉死）。
 *
 * 实测痛点：导入 51 档案后不设 activeProfileId，要滚 15+ 次进编辑页点保存 FAB 才能锚定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CollectionViewModelAnchorTest {

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

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    private fun registerCsv(uri: Uri, csv: String) {
        Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Application>().contentResolver,
        ).registerInputStream(uri, csv.byteInputStream())
    }

    @Test
    fun `import success projects the first inserted profile and anchors it through the publish chain`() {
        val vm = newViewModel()
        val uri = Uri.parse("content://test/profiles.csv")
        registerCsv(
            uri,
            "addname,latitude\n" +
                "loc-01,50.10\n" +
                "loc-02,50.20\n",
        )

        vm.previewImport(uri)
        await("preview reaches the confirm dialog") {
            vm.importState.value is ProfileImportUiState.Preview
        }
        vm.confirmImport()

        await("import succeeds") { vm.importState.value is ProfileImportUiState.Success }
        val success = vm.importState.value as ProfileImportUiState.Success
        assertEquals("loc-01", success.firstRowName)
        assertNotNull(success.firstInsertedId)

        // 一键锚定（导入完成对话框的「将 loc-01 设为生效档案」按钮）：
        // 必须走既有 publish 链，且携带显式 profileId。
        vm.setActiveProfile(success.firstInsertedId!!)
        await("anchor republishes with the explicit profile id") {
            publisher.requests.any { it.profileId == success.firstInsertedId }
        }
        assertTrue(publisher.requests.last().clearIfMissing == false)
    }

    @Test
    fun `long-press anchor publishes the tapped profile id`() {
        val vm = newViewModel()
        val inserted = kotlinx.coroutines.runBlocking {
            db.profileDao().insert(
                ProfileEntity(addname = "loc-07", latitude = 50.7, longitude = 24.7),
            )
        }

        vm.setActiveProfile(inserted)

        await("long-press anchor reaches the publish chain") {
            publisher.requests.any { it.profileId == inserted }
        }
    }

    @Test
    fun `a failed publish is reported and never claimed as anchored`() {
        val vm = newViewModel()
        val inserted = kotlinx.coroutines.runBlocking {
            db.profileDao().insert(
                ProfileEntity(addname = "loc-08", latitude = 50.8, longitude = 24.8),
            )
        }
        publisher.nextResult = false

        vm.setActiveProfile(inserted)
        await("publish attempt reaches the chain") { publisher.requests.isNotEmpty() }
        await("failure notice is surfaced") { vm.activationNotice.value != null }
        assertTrue(vm.activationNotice.value!!.contains("发布失败"))
    }
}
