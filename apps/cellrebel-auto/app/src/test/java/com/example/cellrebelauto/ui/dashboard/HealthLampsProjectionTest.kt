package com.example.cellrebelauto.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 P1.1 — the three health lamps' three-state projection oracle.
 *
 * ① Accessibility Bound  ② QWY provider reachable / publish state
 * ③ Vector chain freshness (QWY publish_at vs now).
 * Each lamp is GREEN / YELLOW / GREY, and GREY ALWAYS carries a reason —
 * "取不到" must be visible as an explained grey, never as a blank.
 *
 * Killing mutations:
 *  - a lamp that cannot go grey fails the unknown-input tests;
 *  - a grey without an explanation fails the detail assertions;
 *  - a freshness boundary flip (fresh vs stale) fails the 24 h boundary test.
 *
 * # 健康三灯投影 oracle：绿/黄/灰三态，灰灯必须带说明文字
 */
class HealthLampsProjectionTest {

    private val nowMs = 1_700_000_000_000L
    private val hour = 3_600_000L

    // ---- ① accessibility -------------------------------------------------------

    @Test
    fun `accessibility is green when the service is bound`() {
        val lamp = HealthLampsProjection.accessibility(serviceConnected = true, enabled = true)
        assertEquals(LampState.GREEN, lamp.state)
    }

    @Test
    fun `accessibility is yellow when enabled but unbound - with a recovery hint`() {
        val lamp = HealthLampsProjection.accessibility(serviceConnected = false, enabled = true)
        assertEquals(LampState.YELLOW, lamp.state)
        assertTrue(lamp.detail.isNotBlank())
    }

    @Test
    fun `accessibility is yellow when the switch is off - pointing at system settings`() {
        val lamp = HealthLampsProjection.accessibility(serviceConnected = false, enabled = false)
        assertEquals(LampState.YELLOW, lamp.state)
        assertTrue(lamp.detail.contains("设置") || lamp.detail.contains("无障碍"))
    }

    @Test
    fun `accessibility is a explained grey when enablement is unknown`() {
        val lamp = HealthLampsProjection.accessibility(serviceConnected = false, enabled = null)
        assertEquals(LampState.GREY, lamp.state)
        assertTrue("grey must explain itself", lamp.detail.isNotBlank())
    }

    // ---- ② QWY provider ----------------------------------------------------------

    @Test
    fun `provider is green when the discover handshake is connected`() {
        val lamp = HealthLampsProjection.provider(
            HealthLampsProjection.ProviderHandshake(exhausted = false, profileCount = 5)
        )
        assertEquals(LampState.GREEN, lamp.state)
    }

    @Test
    fun `provider is yellow when reachable but the schedule is exhausted`() {
        val lamp = HealthLampsProjection.provider(
            HealthLampsProjection.ProviderHandshake(exhausted = true, profileCount = 5)
        )
        assertEquals(LampState.YELLOW, lamp.state)
        assertTrue(lamp.detail.contains("耗尽"))
    }

    @Test
    fun `provider is a explained grey when the channel is unreachable`() {
        val lamp = HealthLampsProjection.provider(null)
        assertEquals(LampState.GREY, lamp.state)
        assertTrue("grey must explain itself", lamp.detail.isNotBlank())
    }

    // ---- ③ vector chain freshness -------------------------------------------------

    @Test
    fun `vector is green when the publish timestamp is fresh`() {
        val lamp = HealthLampsProjection.vector(publishedAtMs = nowMs - 2 * hour, nowMs = nowMs)
        assertEquals(LampState.GREEN, lamp.state)
    }

    @Test
    fun `vector is yellow when the publish is stale beyond the freshness window`() {
        val lamp = HealthLampsProjection.vector(publishedAtMs = nowMs - 25 * hour, nowMs = nowMs)
        assertEquals(LampState.YELLOW, lamp.state)
    }

    @Test
    fun `vector freshness boundary at exactly the window is stale`() {
        val lamp = HealthLampsProjection.vector(
            publishedAtMs = nowMs - HealthLampsProjection.FRESH_MS, nowMs = nowMs
        )
        assertEquals(LampState.YELLOW, lamp.state)
    }

    @Test
    fun `vector is a explained grey when the publish timestamp is unreadable`() {
        val lamp = HealthLampsProjection.vector(publishedAtMs = null, nowMs = nowMs)
        assertEquals(LampState.GREY, lamp.state)
        assertTrue(
            "grey must name why the timestamp is missing",
            lamp.detail.contains("不可读") || lamp.detail.contains("取不到") ||
                lamp.detail.contains("无法")
        )
    }

    @Test
    fun `a future publish timestamp never crashes the projection`() {
        val lamp = HealthLampsProjection.vector(publishedAtMs = nowMs + hour, nowMs = nowMs)
        assertEquals(LampState.GREEN, lamp.state)
    }
}
