package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `recovery` rows M-RC-02, M-RC-04 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/RecoveryMatrixTest.kt`).
 *
 * M-RC-02: schedule boundary crossing during CellRebel run (INV-8, INV-17)
 * M-RC-04: provider release can only partially clean up (INV-14, INV-21)
 */
class RecoveryMatrixTest {

    private lateinit var clock: FakeProviderClock
    private lateinit var provider: FakeQwyProvider
    private val caller = FakeCallerIdentity("com.test.auto", "sha256:abc123")

    @Before
    fun setUp() {
        clock = FakeProviderClock()
        provider = FakeQwyProvider(clock)
        provider.addPairing(caller)
        provider.setSchedule("sched-1", listOf(
            FakeQwyProvider.ScheduleItem("item-1"),
            FakeQwyProvider.ScheduleItem("item-2"),
        ))
    }

    private fun intent() = EnvironmentIntentV1(
        runId = "run-1",
        attemptId = "attempt-1",
        profileRef = "profile:default",
        scheduleRef = "schedule:default",
        requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs = clock.epochMs,
        deadlineEpochMs = clock.epochMs + 120_000L,
    )

    /**
     * M-RC-02: schedule changes during CellRebel run → revision changes,
     * observation is unverified.
     *
     * "schedule 在 CellRebel 运行中跨边界 → revision 变化；未验证、release、
     * 暂停/等下窗" (INV-8, INV-17).
     *
     * Scenario: apply → observe pre → schedule boundary (revision bumps) →
     * observe post → post-observation shows different revision than pre →
     * consumer must NOT count this attempt.
     */
    @Test
    fun M_RC_02() {
        // Apply
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-rc02",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())
        val leaseId = applyResult.applyReceipt!!.leaseId
        val intentHash = applyResult.applyReceipt!!.acceptedIntentHash

        // Pre-observation
        clock.advance(1_000L)
        val preObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-pre-rc02",
            expectedIntentHash = intentHash,
        ))
        assertEquals(ContractResultKindV1.OBSERVE, preObs.resultKindOrNull())
        val preRevision = preObs.environmentObservation!!.environmentRevision

        // Schedule boundary crossing: the schedule changes during CellRebel run
        clock.advance(5_000L)
        provider.bumpEnvironmentRevision()

        // Post-observation
        clock.advance(1_000L)
        val postObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-post-rc02",
            expectedIntentHash = intentHash,
        ))
        assertEquals(ContractResultKindV1.OBSERVE, postObs.resultKindOrNull())
        val postRevision = postObs.environmentObservation!!.environmentRevision

        // Revision must have changed
        assertNotEquals(
            "environment revision must change when schedule crosses boundary",
            preRevision,
            postRevision,
        )

        // Consumer-side: pre and post revisions don't match → attempt is unverified.
        // The consumer MUST compare pre.environmentRevision == post.environmentRevision
        // as part of the environment stability check (INV-08). A mismatch means
        // something changed between pre and post → not counted.
        assertTrue(
            "post revision must be strictly greater than pre",
            postRevision > preRevision,
        )
    }

    /**
     * M-RC-04: release can only partially clean up → plan pauses, manual recovery.
     *
     * "qwy release 只能部分清理 → plan 暂停，显示人工恢复" (INV-14, INV-21).
     *
     * When the provider cannot prove cleanup completed, the release receipt
     * carries releaseComplete=false and the consumer must pause the plan and
     * surface manual recovery. The lease enters RELEASE_INCOMPLETE.
     */
    @Test
    fun M_RC_04() {
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-rc04",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())
        val leaseId = applyResult.applyReceipt!!.leaseId

        // Configure partial cleanup
        provider.overrideReleaseComplete = false
        provider.overrideResidualReasons = listOf(
            ContractErrorCodeV1.RELEASE_INCOMPLETE.wire,
        )

        // Release
        val releaseResult = provider.release(caller, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-partial",
            idempotencyKey = "release-partial",
        ))

        assertEquals(
            "release must succeed (return receipt, not error)",
            ContractResultKindV1.RELEASE,
            releaseResult.resultKindOrNull(),
        )

        val receipt = releaseResult.releaseReceipt!!
        assertFalse(
            "releaseComplete must be false for partial cleanup",
            receipt.releaseComplete,
        )
        assertTrue(
            "residual reasons must contain RELEASE_INCOMPLETE",
            receipt.residualReasonWires.contains(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire),
        )

        // Lease should be in RELEASE_INCOMPLETE state
        val lease = provider.currentLeaseSnapshot()
        assertEquals(
            "lease must be RELEASE_INCOMPLETE after partial cleanup",
            FakeQwyProvider.LeaseState.RELEASE_INCOMPLETE,
            lease!!.state,
        )

        // RELEASE_INCOMPLETE blocks new apply (§8.4)
        val newApplyResult = provider.apply(caller, ApplyRequestV1(
            intent = EnvironmentIntentV1(
                runId = "run-2",
                attemptId = "attempt-2",
                profileRef = "profile:default",
                scheduleRef = "schedule:default",
                requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                notBeforeEpochMs = clock.epochMs,
                deadlineEpochMs = clock.epochMs + 60_000L,
            ),
            idempotencyKey = "apply-blocked-by-incomplete",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(
            "RELEASE_INCOMPLETE must block new apply",
            ContractResultKindV1.ERROR,
            newApplyResult.resultKindOrNull(),
        )
        assertEquals(
            ContractErrorCodeV1.LEASE_CONFLICT,
            newApplyResult.errorCodeOrInternalFailure(),
        )
    }
}
