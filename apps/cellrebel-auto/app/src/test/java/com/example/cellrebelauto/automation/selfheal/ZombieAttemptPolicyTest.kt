package com.example.cellrebelauto.automation.selfheal

import com.example.cellrebelauto.automation.aplus.AttemptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #138 — pure policy tests for the zombie attempt SAFE finalization.
 * # 僵尸 attempt 安全终结：纯策略测试（阈值/阶段守卫/超龄判定）
 */
class ZombieAttemptPolicyTest {

    // ==================== stall threshold ====================

    @Test
    fun `stall threshold floors at 5 minutes for small test timeouts`() {
        assertEquals(300_000L, ZombieAttemptPolicy.stallThresholdMs(90_000L))
        assertEquals(300_000L, ZombieAttemptPolicy.stallThresholdMs(1_000L))
        assertEquals(300_000L, ZombieAttemptPolicy.stallThresholdMs(0L))
    }

    @Test
    fun `stall threshold grows to 2x test timeout past the floor`() {
        assertEquals(400_000L, ZombieAttemptPolicy.stallThresholdMs(200_000L))
        assertEquals(600_000L, ZombieAttemptPolicy.stallThresholdMs(300_000L))
    }

    // ==================== phase guard ====================

    @Test
    fun `only APPLY_PENDING is a finalizable phase`() {
        assertTrue(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.APPLY_PENDING.name))
        // CREATED never dispatched anything — its recovery re-admits through a fresh preflight.
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.CREATED.name))
        // Every lease-holding or post-effect phase must release-converge, never be blind-interrupted.
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.ENV_APPLIED.name))
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.CELLREBEL_RUNNING.name))
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.RELEASE_PENDING.name))
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.RECOVERY_REQUIRED.name))
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(AttemptState.CLOSED.name))
        // Legacy / pre-admission rows belong to the INV-9 generic sweep, not this policy.
        assertFalse(ZombieAttemptPolicy.isFinalizablePhase(null))
    }

    // ==================== staleness ====================

    @Test
    fun `an attempt at exactly the threshold is stale`() {
        // 90s timeout → 5 min floor; startedAt + 300_000 == now → stale.
        assertTrue(ZombieAttemptPolicy.isStale(startedAt = 0L, testTimeoutMs = 90_000L, nowMs = 300_000L))
    }

    @Test
    fun `an attempt inside the threshold is NOT stale — recovery retry chances are preserved`() {
        assertFalse(ZombieAttemptPolicy.isStale(startedAt = 0L, testTimeoutMs = 90_000L, nowMs = 299_999L))
        assertFalse(ZombieAttemptPolicy.isStale(startedAt = -60_000L, testTimeoutMs = 90_000L, nowMs = 0L))
    }

    @Test
    fun `stale judgment scales with a large test timeout`() {
        // 300s timeout → threshold 600s; 500s old is still inside the window margin.
        assertFalse(ZombieAttemptPolicy.isStale(startedAt = 0L, testTimeoutMs = 300_000L, nowMs = 500_000L))
        assertTrue(ZombieAttemptPolicy.isStale(startedAt = 0L, testTimeoutMs = 300_000L, nowMs = 600_000L))
    }
}
