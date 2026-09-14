package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #198: the lease keep-alive seam binding. Hook 投递模式（引擎 apply 驱动）下
 * QWY 无前台服务 → PowerKeeper 冻结进程 → lease release 事务黑洞。修复 = handler
 * 在 lease 状态每次落定后，把"设备上是否存在阻塞 lease"这一个 bit 交给
 * [LeaseKeepAliveSignal]（生产 impl 拉起/放下轻量 FGS [HookKeepAliveService]）。
 *
 * These lanes pin the CONTRACT side of that seam on the JVM:
 *  - apply（含幂等 replay）→ engaged；release 收敛 → disengaged
 *  - RELEASE_INCOMPLETE / REVOKED 仍阻塞（INV-28）→ 信号保持 engaged
 *  - provider 自清理收敛 REVOKED → disengaged
 *  - 进程重启（unclean，ACTIVE → RELEASE_INCOMPLETE）→ owner start 重新 engaged
 *  - observe 轮询重新上报当前压力（FGS 中途死亡的自愈路径）
 *  - 红线：seam 抛异常只降级为 diagnostics，绝不改变合同结果
 *  - 默认（null seam）= pre-#198 行为，一切照旧
 */
class HookKeepAliveLeaseBindingTest {

    private class RecordingKeepAlive : LeaseKeepAliveSignal {
        val events = mutableListOf<Boolean>()
        override fun onLeasePressure(hasBlockingLease: Boolean) {
            events += hasBlockingLease
        }
    }

    /** A seam that misbehaves exactly like broken Android wiring would. */
    private class ThrowingKeepAlive : LeaseKeepAliveSignal {
        var calls = 0
        override fun onLeasePressure(hasBlockingLease: Boolean) {
            calls += 1
            throw IllegalStateException("FGS start not allowed (simulated)")
        }
    }

    @Test
    fun `apply engages and a converged release disengages`() {
        val recorder = RecordingKeepAlive()
        val h = ProviderHarness.create(keepAlive = recorder)
        h.pair()

        val receipt = h.apply()

        assertEquals(
            "a granted lease blocks the device (INV-28) → keep-alive engaged",
            listOf(true),
            recorder.events,
        )

        h.release(receipt.leaseId)

        assertEquals(
            "the last blocking lease converged → keep-alive disengaged",
            listOf(true, false),
            recorder.events,
        )
    }

    @Test
    fun `idempotent apply replay keeps the signal engaged without new state`() {
        val recorder = RecordingKeepAlive()
        val h = ProviderHarness.create(keepAlive = recorder)
        h.pair()

        val receipt = h.apply(key = "apl-replay")
        val replayed = h.apply(key = "apl-replay") // same key + same digest → replay

        assertEquals("replay must not mint a second lease", receipt.leaseId, replayed.leaseId)
        assertEquals(
            "replay is state-neutral but recomputed pressure stays engaged",
            listOf(true, true),
            recorder.events,
        )
    }

    @Test
    fun `release incomplete keeps the lease blocking so the signal stays engaged`() {
        val recorder = RecordingKeepAlive()
        val h = ProviderHarness.create(keepAlive = recorder)
        h.pair()
        val receipt = h.apply()
        recorder.events.clear()

        h.env.cleanupOutcome = CleanupOutcome.Incomplete(emptyList())
        val release = h.release(receipt.leaseId)
        h.env.cleanupOutcome = CleanupOutcome.Complete

        assertTrue("scenario requires an incomplete release", !release.releaseComplete)
        assertEquals(
            "RELEASE_INCOMPLETE keeps blocking (INV-21) → keep-alive stays engaged",
            listOf(true),
            recorder.events,
        )
    }

    @Test
    fun `revoked lease stays engaged until provider cleanup converges it`() {
        val recorder = RecordingKeepAlive()
        val h = ProviderHarness.create(keepAlive = recorder)
        h.pair()
        h.apply()
        recorder.events.clear()

        h.handler.onCallerRevoked(ProviderHarness.AUTO_PKG, ProviderHarness.AUTO_SIGNER)

        assertEquals(
            "REVOKED keeps blocking until converged (INV-28) → engaged",
            listOf(true),
            recorder.events,
        )

        h.handler.runRevokedLeaseCleanup()

        assertEquals(
            "provider self-cleanup converged the revoked lease → disengaged",
            listOf(true, false),
            recorder.events,
        )
    }

    @Test
    fun `owner start with a surviving blocking lease re-engages`() {
        val recorder = RecordingKeepAlive()
        val h = ProviderHarness.create(keepAlive = recorder)
        h.pair()
        h.apply()
        recorder.events.clear()

        // Unclean owner restart: ACTIVE → RELEASE_INCOMPLETE (M-LS-07), still blocking.
        h.restart(cleanlinessProvable = false)

        assertEquals(
            "the restarted owner signals the surviving lease pressure at start",
            listOf(true),
            recorder.events,
        )
    }

    @Test
    fun `observe re-signals pressure so a dead keep-alive self-heals before release`() {
        val recorder = RecordingKeepAlive()
        val h = ProviderHarness.create(keepAlive = recorder)
        h.pair()
        val receipt = h.apply()
        recorder.events.clear()

        h.handler.observe(
            ProviderHarness.AUTO_UID,
            ObserveRequestV1(
                leaseId = receipt.leaseId,
                operationId = "kao-observe",
                expectedIntentHash = receipt.acceptedIntentHash,
            ),
        )

        assertEquals(
            "the verification poll re-reports lease pressure (re-engage path)",
            listOf(true),
            recorder.events,
        )
    }

    @Test
    fun `a throwing seam never changes contract results`() {
        val throwing = ThrowingKeepAlive()
        val h = ProviderHarness.create(keepAlive = throwing)
        h.diagnostics.clear()
        h.pair()

        val receipt = h.apply()
        assertTrue("apply must succeed regardless of the keep-alive misbehaving", receipt.leaseId.isNotBlank())
        assertTrue("the signal fired", throwing.calls >= 1)

        h.release(receipt.leaseId) // must not throw either

        assertTrue(
            "the failure is degraded to a diagnostics line",
            h.diagnostics.lines.any { it.contains("#198 lease keep-alive signal failed") },
        )
    }

    @Test
    fun `null seam keeps the pre-198 behavior`() {
        val h = ProviderHarness.create() // keepAlive = null — the default wiring
        h.pair()

        // Neither apply nor release (complete or incomplete) may touch the seam.
        val receipt = h.apply()
        val release = h.release(receipt.leaseId)

        assertTrue(release.releaseComplete)
    }
}
