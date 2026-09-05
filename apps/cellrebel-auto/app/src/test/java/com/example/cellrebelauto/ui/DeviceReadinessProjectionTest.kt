package com.example.cellrebelauto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9 RED: OEM accessibility-switch fragility. On the Moto/Android 15 bench device,
 * `settings put secure enabled_accessibility_services` rolls back unconditionally and
 * force-stop / install -r clear the accessibility enablement — so AutomationService is off and
 * the Plan surface shows a bare " Service OFF" with the Start button grey and NO explanation.
 *
 * These tests pin the device-readiness PURE projection: enabled-service list → readable status
 * line. The UI renders this; it never decides it.
 *
 * Killing mutation: reverting statusLine to the bare " Service OFF" (today's behavior) fails
 * the disabled-service tests below.
 *
 * # 设备就绪投影 oracle：无障碍服务列表 → 可读状态行（OEM 无障碍开关脆弱仅做可见性）
 */
class DeviceReadinessProjectionTest {

    private val ownFlat = DeviceReadinessProjection.ownFlatComponent("com.example.cellrebelauto")

    @Test
    fun `own service present in the enabled list projects accessibility enabled`() {
        val enabled = setOf(
            "com.other.app/com.other.app.SomeService",
            ownFlat,
        )
        assertTrue(
            DeviceReadinessProjection.accessibilityEnabled(enabled, ownFlat),
        )
    }

    @Test
    fun `own service missing from the enabled list projects accessibility disabled`() {
        val enabled = setOf(
            "com.other.app/com.other.app.SomeService",
            "com.example.cellrebelauto.glmbench/com.example.cellrebelauto.automation.AutomationService",
        )
        assertFalse(
            DeviceReadinessProjection.accessibilityEnabled(enabled, ownFlat),
        )
    }

    @Test
    fun `flat component name is the full pkg-slash-cls form`() {
        assertEquals(
            "com.example.cellrebelauto/com.example.cellrebelauto.automation.AutomationService",
            DeviceReadinessProjection.ownFlatComponent("com.example.cellrebelauto"),
        )
        assertEquals(
            "com.example.cellrebelauto.glmbench/com.example.cellrebelauto.automation.AutomationService",
            DeviceReadinessProjection.ownFlatComponent("com.example.cellrebelauto.glmbench"),
        )
    }

    @Test
    fun `service connected shows no status line`() {
        assertNull(
            DeviceReadinessProjection.statusLine(
                serviceConnected = true,
                accessibilityEnabled = false,
                appDisplayName = "CellRebel Auto",
            ),
        )
    }

    @Test
    fun `service off with accessibility disabled names the exact settings path and this build's app`() {
        val line = DeviceReadinessProjection.statusLine(
            serviceConnected = false,
            accessibilityEnabled = false,
            appDisplayName = "CellRebel Auto·GLM测试",
        )

        assertTrue(line!!.contains("无障碍服务未启用"))
        assertTrue(line.contains("设置 → 无障碍"))
        // The per-build display name (lane builds differ) — not a hardcoded generic label.
        assertTrue(line.contains("CellRebel Auto·GLM测试"))
        // The operator must be told WHY this can happen again (OEM fragility, not user error).
        assertTrue(line.contains("重新打开"))
    }

    @Test
    fun `service off but accessibility enabled points at reconnect instead of enable`() {
        val line = DeviceReadinessProjection.statusLine(
            serviceConnected = false,
            accessibilityEnabled = true,
            appDisplayName = "CellRebel Auto",
        )

        assertTrue(line!!.contains("已启用"))
        assertTrue(line.contains("未连接"))
        assertFalse(line.contains("未启用"))
    }

    @Test
    fun `unknown accessibility state stays silent rather than guessing`() {
        assertNull(
            DeviceReadinessProjection.statusLine(
                serviceConnected = false,
                accessibilityEnabled = null,
                appDisplayName = "CellRebel Auto",
            ),
        )
    }
}
