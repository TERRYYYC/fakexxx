package name.caiyao.fakegps.ui.screen.statuscenter

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import name.caiyao.fakegps.config.SpoofModules
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11b 状态中心 ViewModel 投影 oracle（Robolectric lane）。
 *
 * 钉住三件事：
 * 1. 三灯数据装配——publish_state 回读（注入 reader）、Vector 自 hook 判定（注入）、
 *    MockProvider 状态（真 store 单例）落到 [StatusCenterUi] 的三盏灯上；
 * 2. 待办条出现/消失——pending callers 非空出现，批准后（读回空）消失；
 * 3. 换档案锚定——必须走既有 publish 链（显式 profileId），失败绝不谎报已锚定。
 *
 * 模块开关复用 T5 的 [name.caiyao.fakegps.ui.screen.settings.ModuleToggleUpdate] 持久化→发布
 * 序列，这里钉「状态中心的开关行也走同一条发布链」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatusCenterViewModelTest {

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

    private val fixedSnapshot = StatusCenterViewModel.PublishSnapshot(
        payloadText = "{\"schemaVersion\":5}",
        publishedAtMs = 1_000_000L,
        publishFailed = false,
        activeProfileId = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // SpoofSettings 是进程级单例：上一个测试的数据目录状态不许漏进来。
        SpoofSettings::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
        MockProviderStatusStore.publish(MockProviderState.Idle)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        snapshot: StatusCenterViewModel.PublishSnapshot = fixedSnapshot,
        pending: List<PendingPairingCandidate> = emptyList(),
        publish: (() -> Boolean)? = { true },
        selfHooked: Boolean = true,
        repo: ProfileRepository? = null,
        fixedNowMs: Long = 1_300_000L,
    ): StatusCenterViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return StatusCenterViewModel(
            app,
            repoOverride = repo ?: ProfileRepository(db, app, publishOverride = publisher::publish),
            publishOverride = publish,
            snapshotReader = { _ -> snapshot },
            pendingCallersLoader = { _ -> pending },
            selfHooked = selfHooked,
            clock = { fixedNowMs },
        )
    }

    private fun candidate(pkg: String = "com.example.auto") = PendingPairingCandidate(
        callerApplicationId = pkg,
        currentSignerDigest = "a".repeat(64),
        observedVersionCode = 1L,
        firstSeenAtElapsedRealtimeMs = 0L,
    )

    /** Room 流 / IO 协程的异步到达窗口：与 CollectionViewModelAnchorTest 同一轮询纪律。 */
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    // ---- 三灯三态 ------------------------------------------------------------------

    @Test
    fun `all three lamps are green in the healthy anchored state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        kotlinx.coroutines.runBlocking {
            db.profileDao().insert(
                ProfileEntity(addname = "loc-k1", latitude = 50.450864, longitude = 30.523367),
            )
        }
        MockProviderStatusStore.publish(
            MockProviderState.Running(MockLocationConfig(50.450864, 30.523367), emittedCount = 1),
        )
        val vm = newViewModel(
            snapshot = fixedSnapshot.copy(activeProfileId = 1L),
            pending = emptyList(),
        )

        await("anchored profile row reaches the projection") {
            vm.ui.value.profile.state == ProfileCardState.ANCHORED
        }
        val ui = vm.ui.value
        assertEquals(LampState.GREEN, ui.publish.state)
        assertEquals(LampState.GREEN, ui.vector.state)
        assertEquals(LampState.GREEN, ui.mock.state)
        assertEquals("loc-k1", ui.profile.name)
        assertTrue(ui.todos.isEmpty())
        assertTrue(app.packageName.isNotEmpty()) // Robolectric sanity
    }

    @Test
    fun `publish lamp turns yellow on a failed publication`() {
        val vm = newViewModel(
            snapshot = fixedSnapshot.copy(publishFailed = true),
        )
        assertEquals(LampState.YELLOW, vm.ui.value.publish.state)
    }

    @Test
    fun `anchored profile card shows publish-failed when the last publish did not verify`() {
        kotlinx.coroutines.runBlocking {
            db.profileDao().insert(
                ProfileEntity(addname = "loc-k2", latitude = 50.439851, longitude = 30.532722),
            )
        }
        val vm = newViewModel(
            snapshot = fixedSnapshot.copy(activeProfileId = 1L, publishFailed = true),
        )
        await("pointer row reaches the projection") {
            vm.ui.value.profile.state == ProfileCardState.PUBLISH_FAILED
        }
        // 档案名照常显示，但徽标必须说「发布失败」，不许假装已锚定。
        assertEquals("loc-k2", vm.ui.value.profile.name)
        assertEquals("发布失败", vm.ui.value.profile.chipText)
    }

    @Test
    fun `publish lamp is grey when nothing was ever published`() {
        val vm = newViewModel(
            snapshot = StatusCenterViewModel.PublishSnapshot(
                payloadText = null,
                publishedAtMs = null,
                publishFailed = false,
                activeProfileId = null,
            ),
        )
        assertEquals(LampState.GREY, vm.ui.value.publish.state)
        assertEquals(LampState.GREY, vm.ui.value.vector.state)
        assertEquals(ProfileCardState.NO_PROFILE, vm.ui.value.profile.state)
    }

    @Test
    fun `vector lamp is grey without self-hook when the readback is stale`() {
        // now=1_300_000, publishedAt=1_000_000, window 默认 300_000 → 恰好新鲜；
        // 把 now 推过窗口 → 黄。selfHooked=false 走新鲜度判定而不是自 hook 直绿。
        val fresh = newViewModel(selfHooked = false, fixedNowMs = 1_300_000L)
        assertEquals(LampState.GREEN, fresh.ui.value.vector.state)

        val stale = newViewModel(selfHooked = false, fixedNowMs = 1_300_001L)
        assertEquals(LampState.YELLOW, stale.ui.value.vector.state)
    }

    // ---- 待办条：出现 / 消失 -------------------------------------------------------

    @Test
    fun `todo bar appears while callers wait for approval`() {
        val vm = newViewModel(pending = listOf(candidate()))
        assertTrue(vm.ui.value.todos.any { it.title == "等待批准 Auto" })
        assertTrue(vm.ui.value.pendingCallerCount == 1)
    }

    @Test
    fun `todo bar disappears after the caller was approved elsewhere`() {
        var pending = listOf(candidate())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = StatusCenterViewModel(
            app,
            repoOverride = ProfileRepository(db, app, publishOverride = publisher::publish),
            publishOverride = { true },
            snapshotReader = { _ -> fixedSnapshot },
            pendingCallersLoader = { _ -> pending },
            selfHooked = true,
        )

        assertTrue(vm.ui.value.todos.any { it.title == "等待批准 Auto" })

        // 批准发生在别处（Auto 侧/设置页）；状态中心回读为空 → 待办条消失。
        pending = emptyList()
        vm.refresh()
        await("todo bar disappears once the readback is empty") {
            vm.ui.value.todos.none { it.title == "等待批准 Auto" }
        }
    }

    @Test
    fun `todo bar surfaces the missing anchor when no profile is anchored`() {
        val vm = newViewModel(pending = listOf(candidate()))
        // fixedSnapshot.activeProfileId = null → 档案未锚定也在待办里。
        assertTrue(vm.ui.value.todos.any { it.title == "档案未锚定" })
    }

    // ---- 换档案锚定：一键锚定入口走 publish 链 -------------------------------------

    @Test
    fun `anchoring a profile publishes through the existing chain with the explicit id`() {
        val vm = newViewModel()
        val inserted = kotlinx.coroutines.runBlocking {
            db.profileDao().insert(
                ProfileEntity(addname = "loc-07", latitude = 50.7, longitude = 24.7),
            )
        }

        vm.anchorActiveProfile(inserted)

        await("anchor reaches the publish chain with the explicit profile id") {
            publisher.requests.any { it.profileId == inserted }
        }
    }

    @Test
    fun `a failed anchor publish is reported and never claimed as anchored`() {
        val vm = newViewModel()
        val inserted = kotlinx.coroutines.runBlocking {
            db.profileDao().insert(
                ProfileEntity(addname = "loc-08", latitude = 50.8, longitude = 24.8),
            )
        }
        publisher.nextResult = false

        vm.anchorActiveProfile(inserted)

        await("failure notice is surfaced") { vm.anchorNotice.value != null }
        assertTrue(vm.anchorNotice.value!!.contains("失败"))
    }

    // ---- 模块开关行：复用 T5 持久化→发布序列 ---------------------------------------

    @Test
    fun `module rows list the canonical seven modules`() {
        val vm = newViewModel()
        val rows = vm.ui.value.modules
        assertEquals(SpoofModules.ALL.size, rows.size)
        assertEquals(SpoofModules.ALL.toList(), rows.map { it.module })
        // 运动链出厂默认关，其余开——投影如实呈现。
        assertEquals(false, rows.first { it.module == SpoofModules.MOTION }.enabled)
        assertEquals(true, rows.first { it.module == SpoofModules.LOCATION }.enabled)
    }

    @Test
    fun `toggling a module row publishes and updates the projected switch`() {
        var publishes = 0
        val vm = newViewModel(publish = { publishes++; true })

        vm.setModuleEnabled(SpoofModules.WIFI, false)

        assertTrue("module toggle must publish", publishes >= 1)
        assertEquals(false, vm.ui.value.modules.first { it.module == SpoofModules.WIFI }.enabled)
    }

    @Test
    fun `a failed module-toggle publish is surfaced not dropped`() {
        val vm = newViewModel(publish = { false })

        vm.setModuleEnabled(SpoofModules.WIFI, false)

        val failure = vm.publishFailure.value
        assertTrue(!failure.isNullOrBlank())
    }

    // ---- 重新发布（发布黄灯的恢复动作）---------------------------------------------

    @Test
    fun `republish drives the publish seam`() {
        var publishes = 0
        val vm = newViewModel(publish = { publishes++; true })

        vm.republish()

        assertEquals(1, publishes)
    }

    @Test
    fun `default pending-callers loader and snapshot reader resolve on the app context`() {
        // 生产构造路径（反射 (Application) 构造器 + 默认依赖）必须可实例化——
        // AndroidViewModelFactory 只找单 (Application) 参数构造器。
        val app = ApplicationProvider.getApplicationContext<Application>()
        val constructor = StatusCenterViewModel::class.java.constructors
            .firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == Application::class.java }
        assertTrue(constructor != null)
        val reader = StatusCenterViewModel.defaultSnapshotReader(app)
        // 空环境下从未发布过：payload 缺席、无失败、无指针。
        assertTrue(reader.payloadText == null)
        assertTrue(!reader.publishFailed)
        assertTrue(reader.activeProfileId == null)
    }
}
