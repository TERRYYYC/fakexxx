package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake GPS fail-closed activation tests (AC-B4, INV-10).
 * # Fake GPS 失败即停激活测试：只有 "Stop Fake GPS" 按钮出现才算激活成功
 */
class FakeGpsActivationTest {

    private fun node(text: String? = null, clickable: Boolean = false, children: List<ScreenNode> = emptyList()) =
        ScreenNode(text, null, null, clickable, enabled = true, children = children)

    @Test
    fun `stop button present confirms activation`() {
        // # "Stop Fake GPS" 按钮出现 = 伪造已激活
        val nodes = listOf(
            node(text = "Fake GPS"),
            node(text = "Stop Fake GPS", clickable = true)
        )
        assertTrue(isFakeGpsActivationConfirmed(nodes))
        assertEquals(GpsOutcome.Active, verifyFakeGpsActivation(nodes))
    }

    @Test
    fun `start button still showing means activation unproven and fails closed`() {
        // # 仍显示 "Start Fake GPS" → 激活未被证实 → 类型化失败，绝不继续
        val nodes = listOf(
            node(text = "Fake GPS"),
            node(text = "Start Fake GPS", clickable = true)
        )
        val outcome = verifyFakeGpsActivation(nodes)
        assertTrue(outcome is GpsOutcome.Failed)
        assertEquals(FailureReason.FAKE_GPS_NOT_ACTIVE, (outcome as GpsOutcome.Failed).reason)
    }

    @Test
    fun `empty screen fails closed`() {
        val outcome = verifyFakeGpsActivation(emptyList())
        assertTrue(outcome is GpsOutcome.Failed)
        assertEquals(FailureReason.FAKE_GPS_NOT_ACTIVE, (outcome as GpsOutcome.Failed).reason)
    }

    @Test
    fun `stale old stop button with unconfirmed start sequence is NOT activation`() {
        // # F4 对抗：旧地点的 Stop 按钮残留 + 本次 start 序列未被确认
        // # （旧 Stop 停不掉 / 新 Start 没生效）→ 绝不能把旧地点误判为新地点激活
        val staleStopNodes = listOf(
            node(text = "Fake GPS"),
            node(text = "Stop Fake GPS", clickable = true)
        )

        val outcome = resolveActivationOutcome(
            previousSpoofingStopped = false,
            startSequenceConfirmed = false,
            finalNodes = staleStopNodes
        )
        assertTrue(outcome is GpsOutcome.Failed)
        assertEquals(FailureReason.FAKE_GPS_NOT_ACTIVE, (outcome as GpsOutcome.Failed).reason)
    }

    @Test
    fun `failed stop of previous spoofing fails closed even if stop button persists`() {
        // # F4：stopExistingGpsIfRunning 超时被吞 = 旧 Stop 一直在 → typed failure
        val staleStopNodes = listOf(node(text = "Stop Fake GPS", clickable = true))

        val outcome = resolveActivationOutcome(
            previousSpoofingStopped = false,
            startSequenceConfirmed = true,
            finalNodes = staleStopNodes
        )
        assertTrue(outcome is GpsOutcome.Failed)
        assertEquals(FailureReason.FAKE_GPS_NOT_ACTIVE, (outcome as GpsOutcome.Failed).reason)
    }

    @Test
    fun `active only when this call sequence confirmed and final stop present`() {
        // # 正常路径：旧伪造已停（或本无）+ 本次 start 序列确认 + 终态 Stop 在 → Active
        val stopNodes = listOf(node(text = "Stop Fake GPS", clickable = true))
        assertEquals(
            GpsOutcome.Active,
            resolveActivationOutcome(
                previousSpoofingStopped = true,
                startSequenceConfirmed = true,
                finalNodes = stopNodes
            )
        )
    }
}
