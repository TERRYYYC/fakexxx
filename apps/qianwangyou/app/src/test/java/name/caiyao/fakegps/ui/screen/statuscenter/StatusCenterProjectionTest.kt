package name.caiyao.fakegps.ui.screen.statuscenter

import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.motion.RoutePlayer
import name.caiyao.fakegps.motion.RouteSpec
import name.caiyao.fakegps.motion.RouteWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 状态中心「三灯 + 档案卡 + 待办条 + 运动链入口」的投影真值表（T11b）。
 *
 * 投影是纯函数：UI 的一切显示决策（绿/黄/灰、出现/消失、三态卡）都在这里钉死，
 * ViewModel 只负责喂数据。判定词表遵循 UI-WORKFLOWS-AND-SCREEN-SPECS §M1 + §状态字典：
 * 发布 published=true(绿)/false(黄)/未知(灰)；Vector=模块自检或 publish 新鲜度；
 * Mock=Running+注入坐标；档案卡=无档案/已锚定/发布失败三态。
 */
class StatusCenterProjectionTest {

    // ---- 发布灯：publish_state 回读，2^2 组合穷尽 ---------------------------------

    @Test
    fun `publish lamp is green when payload present and no failure`() {
        val lamp = StatusCenterProjection.publishLamp(
            StatusCenterProjection.PublishReadback(payloadPresent = true, publishFailed = false),
        )
        assertEquals(LampState.GREEN, lamp.state)
        assertEquals("published=true", lamp.detail)
    }

    @Test
    fun `publish lamp is yellow on publication failure even with a payload present`() {
        // 失败时 hook 仍持 last-known-good payload——但回执是失败，必须黄，不许说绿。
        val lamp = StatusCenterProjection.publishLamp(
            StatusCenterProjection.PublishReadback(payloadPresent = true, publishFailed = true),
        )
        assertEquals(LampState.YELLOW, lamp.state)
    }

    @Test
    fun `publish lamp is yellow on failure even without a payload`() {
        val lamp = StatusCenterProjection.publishLamp(
            StatusCenterProjection.PublishReadback(payloadPresent = false, publishFailed = true),
        )
        assertEquals(LampState.YELLOW, lamp.state)
    }

    @Test
    fun `publish lamp is grey when nothing was ever published`() {
        val lamp = StatusCenterProjection.publishLamp(
            StatusCenterProjection.PublishReadback(payloadPresent = false, publishFailed = false),
        )
        assertEquals(LampState.GREY, lamp.state)
    }

    // ---- Vector 灯：自 hook 判定 / publish 新鲜度 ---------------------------------

    private fun vector(
        selfHooked: Boolean = false,
        payloadPresent: Boolean = true,
        publishFailed: Boolean = false,
        publishedAtMs: Long? = 1_000_000L,
    ) = StatusCenterProjection.VectorReadback(
        selfHooked = selfHooked,
        payloadPresent = payloadPresent,
        publishFailed = publishFailed,
        publishedAtMs = publishedAtMs,
    )

    @Test
    fun `vector lamp is yellow on publication failure even when self-hooked`() {
        // 自 hook 证明模块已注入，但 publish 失败 = 镜像没拿到新配置——必须待处理（黄）。
        val lamp = StatusCenterProjection.vectorLamp(
            vector(selfHooked = true, publishFailed = true),
            nowMs = 1_000_000L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.YELLOW, lamp.state)
    }

    @Test
    fun `vector lamp is grey when nothing was ever published`() {
        val lamp = StatusCenterProjection.vectorLamp(
            vector(payloadPresent = false, publishedAtMs = null),
            nowMs = 1_000_000L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.GREY, lamp.state)
    }

    @Test
    fun `vector lamp is green when self-hooked with a healthy fresh publish`() {
        val lamp = StatusCenterProjection.vectorLamp(
            vector(selfHooked = true),
            nowMs = 1_000_000L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.GREEN, lamp.state)
    }

    @Test
    fun `vector lamp is green when publish readback is fresh without self-hook`() {
        val lamp = StatusCenterProjection.vectorLamp(
            vector(selfHooked = false),
            nowMs = 1_300_000L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.GREEN, lamp.state)
    }

    @Test
    fun `vector lamp freshness boundary is inclusive of the window`() {
        // now - publishedAt == freshWindow 恰好算新鲜（绿）；再多 1ms 判过期（黄）。
        val atBoundary = StatusCenterProjection.vectorLamp(
            vector(publishedAtMs = 1_000_000L),
            nowMs = 1_300_000L,
            freshWindowMs = 300_000L,
        )
        val pastBoundary = StatusCenterProjection.vectorLamp(
            vector(publishedAtMs = 1_000_000L),
            nowMs = 1_300_001L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.GREEN, atBoundary.state)
        assertEquals(LampState.YELLOW, pastBoundary.state)
    }

    @Test
    fun `vector lamp is yellow when the readback went stale`() {
        val lamp = StatusCenterProjection.vectorLamp(
            vector(publishedAtMs = 1_000_000L),
            nowMs = 2_000_000L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.YELLOW, lamp.state)
    }

    @Test
    fun `vector lamp is grey when payload present but no publish timestamp exists`() {
        val lamp = StatusCenterProjection.vectorLamp(
            vector(publishedAtMs = null),
            nowMs = 1_000_000L,
            freshWindowMs = 300_000L,
        )
        assertEquals(LampState.GREY, lamp.state)
    }

    // ---- Mock 灯：MockProvider 状态 + 注入坐标 ------------------------------------

    private fun runningConfig() = MockLocationConfig(50.450864, 30.523367)

    @Test
    fun `mock lamp is green while running and carries the injected coordinate`() {
        val lamp = StatusCenterProjection.mockLamp(
            MockProviderState.Running(runningConfig(), emittedCount = 1),
        )
        assertEquals(LampState.GREEN, lamp.state)
        assertTrue(lamp.detail.contains("50.450864"))
        assertTrue(lamp.detail.contains("30.523367"))
        assertTrue(lamp.detail.contains("Running"))
    }

    @Test
    fun `mock lamp is yellow while starting or stopping`() {
        assertEquals(
            LampState.YELLOW,
            StatusCenterProjection.mockLamp(
                MockProviderState.Starting(runningConfig()),
            ).state,
        )
        assertEquals(
            LampState.YELLOW,
            StatusCenterProjection.mockLamp(MockProviderState.Stopping).state,
        )
    }

    @Test
    fun `mock lamp is grey when idle`() {
        val lamp = StatusCenterProjection.mockLamp(MockProviderState.Idle)
        assertEquals(LampState.GREY, lamp.state)
    }

    @Test
    fun `mock lamp is yellow on failure and carries the failure message`() {
        val lamp = StatusCenterProjection.mockLamp(
            MockProviderState.Failed("provider crashed"),
        )
        assertEquals(LampState.YELLOW, lamp.state)
        assertTrue(lamp.detail.contains("provider crashed"))
    }

    @Test
    fun `mock lamp stays green for a route session that already completed`() {
        val lamp = StatusCenterProjection.mockLamp(
            MockProviderState.Running(runningConfig(), emittedCount = 9, routeCompleted = true),
        )
        assertEquals(LampState.GREEN, lamp.state)
    }

    // ---- 生效档案卡三态：无档案 / 已锚定 / 发布失败 --------------------------------

    @Test
    fun `profile card shows no-profile state without an anchored profile`() {
        val card = StatusCenterProjection.profileCard(
            hasActiveProfile = false,
            name = null,
            latitude = null,
            longitude = null,
            delivery = LocationDeliveryMode.HOOK,
            publishFailed = false,
        )
        assertEquals(ProfileCardState.NO_PROFILE, card.state)
        assertEquals("无档案", card.chipText)
        assertNull(card.name)
        assertNull(card.coordinate)
    }

    @Test
    fun `profile card shows anchored state with name coordinate and delivery`() {
        val card = StatusCenterProjection.profileCard(
            hasActiveProfile = true,
            name = "loc-k1",
            latitude = 50.450864,
            longitude = 30.523367,
            delivery = LocationDeliveryMode.SYSTEM_MOCK,
            publishFailed = false,
        )
        assertEquals(ProfileCardState.ANCHORED, card.state)
        assertEquals("已锚定", card.chipText)
        assertEquals("loc-k1", card.name)
        assertEquals("50.450864, 30.523367", card.coordinate)
        assertEquals("系统 Mock", card.deliveryLabel)
    }

    @Test
    fun `profile card shows publish-failed state when the publish failed`() {
        val card = StatusCenterProjection.profileCard(
            hasActiveProfile = true,
            name = "loc-k1",
            latitude = 50.450864,
            longitude = 30.523367,
            delivery = LocationDeliveryMode.HOOK,
            publishFailed = true,
        )
        assertEquals(ProfileCardState.PUBLISH_FAILED, card.state)
        assertEquals("发布失败", card.chipText)
        assertEquals("Hook", card.deliveryLabel)
    }

    // ---- 待办条：出现 / 消失 -------------------------------------------------------

    @Test
    fun `todo bar appears when pending callers exist`() {
        val todos = StatusCenterProjection.todoItems(
            pendingCallerCount = 1,
            anchoredProfileMissing = false,
        )
        assertEquals(1, todos.size)
        assertEquals("等待批准 Auto", todos[0].title)
        assertTrue(todos[0].detail.contains("1"))
    }

    @Test
    fun `todo bar disappears when the caller was approved`() {
        val before = StatusCenterProjection.todoItems(1, anchoredProfileMissing = false)
        val after = StatusCenterProjection.todoItems(0, anchoredProfileMissing = false)
        assertTrue(before.isNotEmpty())
        assertTrue(after.isEmpty())
    }

    @Test
    fun `todo bar surfaces a missing anchor`() {
        val todos = StatusCenterProjection.todoItems(
            pendingCallerCount = 0,
            anchoredProfileMissing = true,
        )
        assertEquals("档案未锚定", todos.single().title)
    }

    @Test
    fun `todo bar lists pending callers before the anchor item`() {
        val todos = StatusCenterProjection.todoItems(2, anchoredProfileMissing = true)
        assertEquals(2, todos.size)
        assertEquals("等待批准 Auto", todos[0].title)
        assertEquals("档案未锚定", todos[1].title)
    }

    // ---- 运动链入口：关（隐藏）/ 播放 / 完成 --------------------------------------

    private fun routeSpec() = RouteSpec(
        waypoints = listOf(
            RouteWaypoint(50.4501, 30.5234),
            RouteWaypoint(50.4510, 30.5245),
        ),
    )

    @Test
    fun `motion entry is hidden without a running mock session`() {
        assertNull(StatusCenterProjection.motionEntry(MockProviderState.Idle))
        assertNull(
            StatusCenterProjection.motionEntry(
                MockProviderState.Starting(runningConfig()),
            ),
        )
    }

    @Test
    fun `motion entry is hidden for a static-point session`() {
        // System Mock 开着但没挂路线（静态点位）→ 运动链入口不出现。
        assertNull(
            StatusCenterProjection.motionEntry(
                MockProviderState.Running(runningConfig(), emittedCount = 1),
            ),
        )
    }

    @Test
    fun `motion entry shows playing state with emitted points over waypoints`() {
        val entry = StatusCenterProjection.motionEntry(
            MockProviderState.Running(
                runningConfig(),
                emittedCount = 5,
                routePlayer = RoutePlayer(routeSpec()),
                routeKey = "v1;",
            ),
        )
        assertTrue(entry != null)
        assertTrue(entry!!.playing)
        assertTrue(entry.label.contains("播放中"))
        assertTrue(entry.detail.contains("5"))
        assertTrue(entry.detail.contains("2"))
    }

    @Test
    fun `motion entry shows completed state once the route finished`() {
        val entry = StatusCenterProjection.motionEntry(
            MockProviderState.Running(
                runningConfig(),
                emittedCount = 30,
                routePlayer = RoutePlayer(routeSpec()),
                routeKey = "v1;",
                routeCompleted = true,
            ),
        )
        assertTrue(entry != null)
        assertTrue(!entry!!.playing)
        assertTrue(entry.label.contains("已完成"))
    }
}
