package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #176 方案 A+C（运行期地址读回验证与自动跟随）的策略单测。
 *
 * 门只做三件事：比较三层读回（系统 mock / hook 载荷坐标 / 载荷 addname）与当前
 * 日程项期望值；错则触发一次完整重投递（修复阶梯第 1 级）；修复后仍错则给出
 * mismatch——绝不把"部分成功"当成功（fail-closed）。
 *
 * 设备实证背景（ZY22JHW9M4，2026-09-11/12）：appops 重置后 addTestProvider 抛
 * SecurityException 穿 binder；Vector 半套注入时载荷静默不更新；#175 载荷钉死在
 * 生效档案。三者都是"provider 声称正确、设备事实错误"——引擎坐标断言查
 * provider 侧记录，全绿。
 */
class DeliveryReadbackGateTest {

    private val logs = mutableListOf<String>()
    private val gate = DeliveryReadbackGate(log = { logs.add(it) })

    /** loc-01 / loc-02 的真实坐标（与设备档案一致）。 */
    private val loc01 = 49.6857758 to 30.4731424
    private val loc02 = 49.941005 to 30.1944833

    private fun at(coords: Pair<Double, Double>, addname: String?): DeliveryReadbackGate.Readback =
        DeliveryReadbackGate.Readback(
            mockLatitude = coords.first,
            mockLongitude = coords.second,
            payloadLatitude = coords.first,
            payloadLongitude = coords.second,
            payloadAddname = addname,
        )

    private fun unreadableMock(coords: Pair<Double, Double>, addname: String?) =
        at(coords, addname).copy(mockLatitude = null, mockLongitude = null)

    private fun unreadablePayload(coords: Pair<Double, Double>, addname: String?) =
        DeliveryReadbackGate.Readback(
            mockLatitude = coords.first,
            mockLongitude = coords.second,
            payloadLatitude = null,
            payloadLongitude = null,
            payloadAddname = null,
        )

    @Test
    fun `all layers match - verified without repair`() {
        var repairs = 0
        val outcome = gate.enforce(
            expectedLatitude = loc01.first,
            expectedLongitude = loc01.second,
            expectedAddname = "loc-01",
            initialReadback = at(loc01, "loc-01"),
            repairAndReadback = { repairs++; at(loc01, "loc-01") },
        )
        assertEquals(DeliveryReadbackGate.Outcome.Verified, outcome)
        assertEquals(0, repairs)
    }

    @Test
    fun `mock layer stale - repair makes address follow - verified`() {
        // 场景：test provider 掉线后残留旧位置（#176 已知触发面），重投递后跟随
        var repairs = 0
        val outcome = gate.enforce(
            expectedLatitude = loc02.first,
            expectedLongitude = loc02.second,
            expectedAddname = "loc-02",
            initialReadback = at(loc01, "loc-02"), // mock+payload 都还在上一任务坐标
            repairAndReadback = { repairs++; at(loc02, "loc-02") },
        )
        assertEquals(DeliveryReadbackGate.Outcome.Verified, outcome)
        assertEquals(1, repairs)
    }

    @Test
    fun `payload pinned to old profile - repair cannot fix - mismatch fail-closed`() {
        // #175 回归护栏（投递层）：载荷钉死在旧档案，修复一次后仍不跟随 → 拒绝
        val outcome = gate.enforce(
            expectedLatitude = loc02.first,
            expectedLongitude = loc02.second,
            expectedAddname = "loc-02",
            initialReadback = at(loc02, "loc-01"), // 坐标已跟随、addname 钉死旧档案
            repairAndReadback = { at(loc02, "loc-01") },
        )
        assertTrue(outcome is DeliveryReadbackGate.Outcome.Mismatch)
        val detail = (outcome as DeliveryReadbackGate.Outcome.Mismatch).detail
        assertTrue("detail should name the wrong payload addname: $detail", "loc-01" in detail)
        assertTrue("detail should state repair was attempted: $detail", "afterRepair" in detail)
    }

    @Test
    fun `mock coords unreadable from background - accepted with explicit downgrade note`() {
        // 后台 provider 进程常态（FINE_LOCATION 默认 foreground 模式，2026-09-12 设备实证）：
        // mock 坐标读不到 ≠ 错——投递阶段无异常即注册+注入成立；降级必须写进日志，不冒充已验。
        val outcome = gate.enforce(
            expectedLatitude = loc02.first,
            expectedLongitude = loc02.second,
            expectedAddname = "loc-02",
            initialReadback = unreadableMock(loc02, "loc-02"),
            repairAndReadback = { unreadableMock(loc02, "loc-02") },
        )
        assertEquals(DeliveryReadbackGate.Outcome.Verified, outcome)
        assertTrue(logs.any { it.contains("coords-unverifiable") })
    }

    @Test
    fun `payload unreadable - mismatch fail-closed`() {
        // Vector 半套注入形态：载荷读不回来 = hook 还在用 last-known-good，必须拒绝
        val outcome = gate.enforce(
            expectedLatitude = loc02.first,
            expectedLongitude = loc02.second,
            expectedAddname = "loc-02",
            initialReadback = unreadablePayload(loc02, "loc-02"),
            repairAndReadback = { unreadablePayload(loc02, "loc-02") },
        )
        assertTrue(outcome is DeliveryReadbackGate.Outcome.Mismatch)
    }

    @Test
    fun `coordinate float round-trip within tolerance - verified`() {
        // 档案→JSON 载荷→读回的浮点往返（49.732763 vs 49.73276311）不得误报
        val expected = 49.732763 to 29.6652631
        val roundTrip = 49.73276311 to 29.66526319
        val outcome = gate.enforce(
            expectedLatitude = expected.first,
            expectedLongitude = expected.second,
            expectedAddname = "loc-03",
            initialReadback = at(roundTrip, "loc-03"),
            repairAndReadback = { at(roundTrip, "loc-03") },
        )
        assertEquals(DeliveryReadbackGate.Outcome.Verified, outcome)
    }

    @Test
    fun `null addname in profile and payload - verified`() {
        // 档案 addname 为 NULL → 载荷按"只写非空列"规则同样没有 addname，应视为一致
        val outcome = gate.enforce(
            expectedLatitude = loc02.first,
            expectedLongitude = loc02.second,
            expectedAddname = null,
            initialReadback = at(loc02, null),
            repairAndReadback = { at(loc02, null) },
        )
        assertEquals(DeliveryReadbackGate.Outcome.Verified, outcome)
    }

    @Test
    fun `every mismatch and repair is logged for field diagnosis`() {
        gate.enforce(
            expectedLatitude = loc02.first,
            expectedLongitude = loc02.second,
            expectedAddname = "loc-02",
            initialReadback = at(loc01, "loc-01"),
            repairAndReadback = { at(loc01, "loc-01") },
        )
        assertTrue(logs.any { it.contains("loc-01") })
        assertTrue(logs.any { it.contains("repair", ignoreCase = true) })
    }
}
