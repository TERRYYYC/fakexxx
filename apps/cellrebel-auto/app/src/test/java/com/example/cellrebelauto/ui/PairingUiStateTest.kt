package com.example.cellrebelauto.ui

import com.example.cellrebelauto.recovery.ProviderPrincipalFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43 (spec Task 6 RED / Sol GREEN-review-2 F5): the SEVEN on-site states — 未配对 / provider
 * 待批准 / 不兼容 / 可信 / 未验证 / recovery-required / release-incomplete — each projecting a
 * CONCRETE recovery action (never a bare code), from DURABLE owner state only.
 *
 * # 七态投影测试：每态一个具体恢复动作；全部由持久 owner 状态派生
 */
class PairingUiStateTest {

    @Test
    fun `1 unpaired - no provider record`() {
        val s = PairingUiState.project(hasProviderRecord = false, providerActive = false)
        assertTrue(s is PairingUiState.NotPaired)
        assertTrue("concrete action names pairing", s.recoveryAction.contains("配对"))
    }

    @Test
    fun `2 pending operator approval - record exists but not active`() {
        val s = PairingUiState.project(hasProviderRecord = true, providerActive = false)
        assertTrue(s is PairingUiState.PendingOperatorApproval)
        assertTrue("concrete action names the approval gate", s.recoveryAction.contains("批准"))
    }

    @Test
    fun `3 incompatible - paired but compatibility rejected`() {
        val s = PairingUiState.project(
            hasProviderRecord = true, providerActive = true,
            incompatible = true, incompatibleDetail = "contract v1 mismatch: peer protocol 2"
        )
        assertTrue(s is PairingUiState.Incompatible)
        assertEquals("the diagnosis is shown", "contract v1 mismatch: peer protocol 2", (s as PairingUiState.Incompatible).detail)
        assertTrue("concrete action names upgrade", s.recoveryAction.contains("升级"))
    }

    @Test
    fun `4 trusted - paired, active, compatible, nothing pending`() {
        val s = PairingUiState.project(hasProviderRecord = true, providerActive = true)
        assertTrue(s is PairingUiState.Trusted)
        assertEquals("trusted state has no recovery action", "", s.recoveryAction)
    }

    @Test
    fun `5 unverified completion - trust predicate failed for a real attempt`() {
        val s = PairingUiState.project(
            hasProviderRecord = true, providerActive = true,
            hasUnverifiedRecord = true
        )
        assertTrue(s is PairingUiState.UnverifiedCompletion)
        assertTrue("concrete action explains no quota + retry", s.recoveryAction.contains("可信配额"))
    }

    @Test
    fun `6 recovery required - crashed attempt in RECOVERY_REQUIRED`() {
        val s = PairingUiState.project(
            hasProviderRecord = true, providerActive = true,
            crashedAplusState = "RECOVERY_REQUIRED"
        )
        assertTrue(s is PairingUiState.RecoveryRequired)
        assertTrue("concrete action explains the paused plan", s.recoveryAction.contains("恢复"))
    }

    @Test
    fun `7 release incomplete - crashed attempt with an unproven lease cleanup`() {
        val s = PairingUiState.project(
            hasProviderRecord = true, providerActive = true,
            crashedAplusState = "RELEASE_PENDING"
        )
        assertTrue(s is PairingUiState.ReleaseIncomplete)
        assertTrue("concrete action names manual lease confirmation (INV-21)", s.recoveryAction.contains("租约"))
    }

    @Test
    fun `rotated signer release failure remains manual recovery when pairing state changes`() {
        val s = PairingUiState.project(
            hasProviderRecord = true,
            providerActive = false,
            crashedAplusState = "RECOVERY_REQUIRED",
            crashedProviderFailure = ProviderPrincipalFailureReason.SIGNER_UNTRUSTED,
        )

        assertTrue("the outstanding old-signer lease outranks approval UI", s is PairingUiState.ReleaseIncomplete)
        assertTrue("the action must require manual lease recovery", s.recoveryAction.contains("手动"))
    }

    @Test
    fun `pre-guard APPLY_PENDING owner mismatch outranks replacement pairing`() {
        val s = PairingUiState.project(
            hasProviderRecord = true,
            providerActive = false,
            crashedAplusState = "APPLY_PENDING",
        )

        assertTrue(
            "a crashed durable owner that is not the current exact principal needs manual recovery",
            s is PairingUiState.RecoveryRequired,
        )
    }

    @Test
    fun `recovery-required and release-incomplete outrank unverified (active incident first)`() {
        // An in-flight incident is actionable NOW; a recorded unverified is history.
        val s = PairingUiState.project(
            hasProviderRecord = true, providerActive = true,
            crashedAplusState = "RECOVERY_REQUIRED", hasUnverifiedRecord = true
        )
        assertTrue(s is PairingUiState.RecoveryRequired)
    }

    @Test
    fun `crashedPhaseOf derives the §8-1 owner phase from a non-terminal A+ attempt only`() {
        val attempt = com.example.cellrebelauto.model.plan.TestAttempt(
            id = 1, taskId = 1, runSessionId = 1, attemptOrdinal = 1,
            successOrdinal = null, startedAt = 0, runningObservedAt = null, endedAt = null,
            status = "starting", failureReason = null,
            webBrowsingScore = null, videoStreamingScore = null,
            latitude = 0.0, longitude = 0.0,
            aplusState = "RELEASE_PENDING"
        )
        assertEquals("RELEASE_PENDING", PairingUiState.crashedPhaseOf(attempt))
        assertEquals(
            "a TERMINAL attempt carries no in-flight incident",
            null,
            PairingUiState.crashedPhaseOf(attempt.copy(status = "succeeded"))
        )
        assertEquals(
            "a non-A+ attempt carries no A+ incident",
            null,
            PairingUiState.crashedPhaseOf(attempt.copy(aplusState = null))
        )
    }
}
