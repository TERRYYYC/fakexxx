package com.example.cellrebelauto.recovery

import com.example.cellrebelauto.automation.ProviderPrincipal
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.TestAttempt
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durable principal kill tests. A brand-new coordinator over the same log models process death;
 * changing its scoped executor from production to bench models a compatible build switch.
 */
class DurableProviderPrincipalRecoveryRedTest {

    private val production = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
    private val bench = ContractV1.PROVIDER_APPLICATION_ID_BENCH

    private fun scoped(
        applicationId: String,
        delegate: ExternalApplyExecutor,
    ): ProviderScopedExternalApplyExecutor =
        ProviderScopedExternalApplyExecutor.wrap(applicationId, delegate)

    @Test
    fun `same key and digest cannot replay an apply receipt across provider principals`() {
        val durableLog = FakeDurableRecoveryLog()
        val originalProvider = RecordingExternalApplyExecutor()
        val original = RecoveryCoordinator(scoped(production, originalProvider), durableLog)
        val first = original.dispatchApply(
            41L, testApplyIntent(attemptId = 41L), "apply-41", "digest-41", 1_000L,
        )
        assertEquals("lease-41", first.leaseId)

        // Process dies. The replacement build defaults to bench, but restores the same DB.
        val wrongProvider = RecordingExternalApplyExecutor()
        val restarted = RecoveryCoordinator(scoped(bench, wrongProvider), durableLog)
        val replay = restarted.dispatchApply(
            41L, testApplyIntent(attemptId = 41L), "apply-41", "digest-41", 2_000L,
        )

        assertEquals(
            "same key+digest is not idempotent across provider principals",
            "PROVIDER_PRINCIPAL_CONFLICT",
            replay.outcome,
        )
        assertNull("a cross-principal replay can never recover the old lease", replay.leaseId)
        assertEquals("the wrong provider is never contacted", 0, wrongProvider.invocationCount("apply-41"))
    }

    @Test
    fun `apply preflight rejects a legacy checkpoint before provider RPC`() {
        val durableLog = FakeDurableRecoveryLog()
        durableLog.recordCheckpoint(46L, "LEGACY", null, 1L, providerApplicationId = null)
        val provider = RecordingExternalApplyExecutor()
        val coordinator = RecoveryCoordinator(scoped(production, provider), durableLog)

        val result = coordinator.dispatchApply(
            46L, testApplyIntent(attemptId = 46L), "apply-46", "digest-46", 2L,
        )

        assertEquals("PROVIDER_PRINCIPAL_UNKNOWN", result.outcome)
        assertEquals("legacy checkpoint blocks before apply RPC", 0,
            provider.invocationCount("apply-46"))
    }

    @Test
    fun `reconcile preflight rejects a foreign checkpoint before provider RPC`() {
        val durableLog = FakeDurableRecoveryLog()
        durableLog.recordCheckpoint(47L, "FOREIGN", null, 1L, providerApplicationId = bench)
        val provider = RecordingExternalApplyExecutor()
        val coordinator = RecoveryCoordinator(scoped(production, provider), durableLog)

        val result = coordinator.reconcile(
            47L, testApplyIntent(attemptId = 47L), "apply-47", "digest-47", 2L,
        )

        assertEquals(
            ReconcileResult.ProviderFailure(
                ProviderPrincipalFailureReason.PRINCIPAL_CONFLICT
            ),
            result,
        )
        assertEquals("foreign checkpoint blocks before reconcile RPC", 0,
            provider.invocationCount("apply-47"))
    }

    @Test
    fun `schedule preflight rejects a legacy checkpoint before any acquisition`() {
        val durableLog = FakeDurableRecoveryLog()
        durableLog.seedReceipt(
            "apply-48", "digest-48", "APPLIED", 1L,
            leaseId = "lease-48", providerApplicationId = production,
        )
        durableLog.recordCheckpoint(48L, "LEGACY", "apply-48", 1L,
            providerApplicationId = null)
        var observations = 0
        var revisions = 0
        var quotas = 0
        val coordinator = RecoveryCoordinator(
            scoped(production, RecordingExternalApplyExecutor()),
            durableLog,
            ObserveIntentAcquirer { observations++; true },
            ReceiptRevisionAcquirer { _, _ -> revisions++; true },
            TrustedQuotaAcquirer { quotas++; true },
        )

        assertEquals(
            ScheduleAdvanceState.NOT_ADVANCED,
            coordinator.scheduleAdvanced(48L, "apply-48", 2L),
        )
        assertEquals("principal preflight precedes observe acquisition", 0, observations)
        assertEquals("principal preflight precedes revision acquisition", 0, revisions)
        assertEquals("principal preflight precedes quota/mint acquisition", 0, quotas)
    }

    @Test
    fun `release preflight rejects a legacy apply receipt before provider RPC`() {
        val durableLog = FakeDurableRecoveryLog()
        durableLog.seedReceipt(
            "auto-aplus-apply-49", "digest-49", "APPLIED", 1L,
            leaseId = "lease-49", providerApplicationId = null,
        )
        val provider = RecordingExternalApplyExecutor()
        val coordinator = RecoveryCoordinator(scoped(production, provider), durableLog)

        val result = coordinator.releaseLease(
            49L, "auto-aplus-release-49", "lease-49", "release-digest-49", 2L,
        )

        assertNull(result)
        assertEquals("legacy apply ownership blocks before release RPC", 0,
            provider.releaseInvocationCount("auto-aplus-release-49"))
    }

    @Test
    fun `release receipt from the original principal cannot close a build-switched recovery`() {
        val durableLog = FakeDurableRecoveryLog()
        durableLog.seedReceipt(
            APlusOperationIdentity.applyIdempotencyKey(42L),
            "digest-42",
            "APPLIED",
            500L,
            leaseId = "lease-42",
            providerApplicationId = production,
        )
        val originalProvider = RecordingExternalApplyExecutor()
        val first = RecoveryCoordinator(scoped(production, originalProvider), durableLog).releaseLease(
            attemptId = 42L,
            idempotencyKey = "release-42",
            leaseId = "lease-42",
            releaseDigest = "release-digest-42",
            now = 1_000L,
        )
        assertEquals("RELEASED", first?.resultOutcome)

        val wrongProvider = RecordingExternalApplyExecutor()
        val replay = RecoveryCoordinator(scoped(bench, wrongProvider), durableLog).releaseLease(
            attemptId = 42L,
            idempotencyKey = "release-42",
            leaseId = "lease-42",
            releaseDigest = "release-digest-42",
            now = 2_000L,
        )

        assertNull("a release proof is scoped by (principal, leaseId)", replay)
        assertEquals(
            "a conflicting historical receipt blocks; it never triggers sibling cleanup",
            0,
            wrongProvider.releaseInvocationCount("release-42"),
        )
    }

    @Test
    fun `process restart replays and releases through the frozen original principal`() {
        assertEquals(
            "the debug test process models a build whose default target differs from the plan",
            bench,
            ProviderPrincipal.selected,
        )
        val durableLog = FakeDurableRecoveryLog()
        val firstProvider = RecordingExternalApplyExecutor()
        val applyKey = APlusOperationIdentity.applyIdempotencyKey(45L)
        RecoveryCoordinator(scoped(production, firstProvider), durableLog).dispatchApply(
            45L, testApplyIntent(attemptId = 45L), applyKey, "digest-45", 1_000L,
        )

        // Process death creates a new executor. The persisted plan principal selects production,
        // not the restarted debug build's bench default.
        val restoredProvider = RecordingExternalApplyExecutor()
        val restored = RecoveryCoordinator(scoped(production, restoredProvider), durableLog)
        val replay = restored.dispatchApply(
            45L, testApplyIntent(attemptId = 45L), applyKey, "digest-45", 2_000L,
        )
        assertEquals("lease-45", replay.leaseId)
        assertEquals("durable apply replays without a second effect", 0, restoredProvider.effectCount(45L))

        val released = restored.releaseLease(
            attemptId = 45L,
            idempotencyKey = "release-45",
            leaseId = "lease-45",
            releaseDigest = "release-digest-45",
            now = 3_000L,
        )
        assertEquals(production, released?.providerApplicationId)
        assertEquals(
            "release is sent once through the reconstructed original-principal executor",
            1,
            restoredProvider.releaseInvocationCount("release-45"),
        )
    }

    @Test
    fun `cross-principal receipt cannot mint an advance checkpoint`() {
        val durableLog = FakeDurableRecoveryLog()
        val originalProvider = RecordingExternalApplyExecutor()
        RecoveryCoordinator(scoped(production, originalProvider), durableLog).dispatchApply(
            43L, testApplyIntent(attemptId = 43L), "apply-43", "digest-43", 1_000L,
        )

        var observed = 0
        var revisions = 0
        var quotaChecks = 0
        val switched = RecoveryCoordinator(
            scoped(bench, RecordingExternalApplyExecutor()),
            durableLog,
            ObserveIntentAcquirer { observed++; true },
            ReceiptRevisionAcquirer { _, _ -> revisions++; true },
            TrustedQuotaAcquirer { quotaChecks++; true },
        )

        assertEquals(
            ScheduleAdvanceState.NOT_ADVANCED,
            switched.scheduleAdvanced(43L, "apply-43", 2_000L),
        )
        assertEquals("principal conflict stops before observation", 0, observed)
        assertEquals("principal conflict stops before revision acquisition", 0, revisions)
        assertEquals("principal conflict stops before quota/mint acquisition", 0, quotaChecks)
        assertNull("no cross-principal checkpoint is minted", durableLog.checkpointFor(43L))
    }

    @Test
    fun `unknown scoped identity performs zero provider calls`() {
        val provider = RecordingExternalApplyExecutor()
        var observeChecks = 0
        var revisionChecks = 0
        var quotaChecks = 0
        val unknown = object : ProviderScopedExternalApplyExecutor,
            ExternalApplyExecutor by provider {
            override val targetApplicationId: String = "unknown.provider"
        }
        val coordinator = RecoveryCoordinator(
            unknown,
            FakeDurableRecoveryLog(),
            ObserveIntentAcquirer { observeChecks++; true },
            ReceiptRevisionAcquirer { _, _ -> revisionChecks++; true },
            TrustedQuotaAcquirer { quotaChecks++; true },
        )

        val result = coordinator.dispatchApply(
            44L, testApplyIntent(attemptId = 44L), "apply-44", "digest-44", 1_000L,
        )

        assertEquals("PROVIDER_PRINCIPAL_UNKNOWN", result.outcome)
        assertNull(result.leaseId)
        assertEquals("unknown identity is rejected before RPC", 0, provider.invocationCount("apply-44"))
        assertEquals("unknown identity is rejected before discover", 0, provider.discoverCalls)
        assertEquals(
            ReconcileResult.ProviderFailure(
                ProviderPrincipalFailureReason.PRINCIPAL_UNKNOWN
            ),
            coordinator.reconcile(
                44L, testApplyIntent(attemptId = 44L), "apply-44", "digest-44", 2_000L,
            ),
        )
        assertNull(
            coordinator.releaseLease(
                44L, "release-44", "lease-44", "release-digest-44", 3_000L,
            )
        )
        assertEquals(
            ScheduleAdvanceState.NOT_ADVANCED,
            coordinator.scheduleAdvanced(44L, "apply-44", 4_000L),
        )
        assertEquals("unknown identity performs zero apply RPC", 0, provider.effectCount(44L))
        assertEquals("unknown identity performs zero release RPC", 0,
            provider.releaseInvocationCount("release-44"))
        assertEquals("unknown identity performs zero observation acquisition", 0, observeChecks)
        assertEquals("unknown identity performs zero revision acquisition", 0, revisionChecks)
        assertEquals("unknown identity performs zero quota/mint acquisition", 0, quotaChecks)
    }

    @Test
    fun `v7 durable recovery boundary contains provider identity on all five owners and proofs`() {
        val durableTypes = listOf(
            LocationPlan::class.java,
            TestAttempt::class.java,
            OperationReceiptRow::class.java,
            RecoveryCheckpointRow::class.java,
            ReleaseReceiptRow::class.java,
        )

        durableTypes.forEach { type ->
            assertTrue(
                "${type.simpleName} must persist providerApplicationId",
                type.declaredFields.any { it.name == "providerApplicationId" },
            )
        }
    }
}
