package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `release` row M-RL-01 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/ReleaseMatrixTest.kt`).
 *
 * M-RL-01: "foreign/stale leaseId → 不清理环境，typed error" (INV-14).
 *
 * A release with a leaseId that does not belong to the caller (foreign) or
 * that has already been released (stale) must return a typed error and must
 * NOT clean up the environment. The environment revision must not change.
 */
class ReleaseMatrixTest {

    private lateinit var clock: FakeProviderClock
    private lateinit var provider: FakeQwyProvider
    private val callerA = FakeCallerIdentity("com.test.auto", "sha256:auto")
    private val callerB = FakeCallerIdentity("com.test.other", "sha256:other")

    @Before
    fun setUp() {
        clock = FakeProviderClock()
        provider = FakeQwyProvider(clock)
        provider.addPairing(callerA)
        provider.addPairing(callerB)
        provider.setSchedule("sched-1", listOf(
            FakeQwyProvider.ScheduleItem("item-1"),
        ))
    }

    private fun intent() = EnvironmentIntentV1(
        runId = "run-1",
        attemptId = "attempt-1",
        profileRef = "profile:default",
        scheduleRef = "schedule:default",
        requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs = clock.epochMs,
        deadlineEpochMs = clock.epochMs + 60_000L,
    )

    /**
     * M-RL-01 case 1: foreign leaseId — callerB tries to release callerA's lease.
     */
    @Test
    fun M_RL_01_foreignLeaseId() {
        // CallerA acquires a lease
        val applyResult = provider.apply(callerA, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-1",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        val leaseId = applyResult.applyReceipt!!.leaseId

        // Record revision BEFORE the foreign release attempt
        val revisionBefore = provider.discover().capabilitySnapshot!!.environmentRevision

        // CallerB tries to release callerA's lease → must fail
        val releaseResult = provider.release(callerB, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-foreign",
            idempotencyKey = "release-foreign",
        ))

        assertEquals(
            "foreign release must be ERROR",
            ContractResultKindV1.ERROR,
            releaseResult.resultKindOrNull(),
        )
        assertEquals(
            "error code must be STALE_LEASE (not any other code)",
            ContractErrorCodeV1.STALE_LEASE,
            releaseResult.errorCodeOrInternalFailure(),
        )

        // Environment revision must NOT have changed
        val revisionAfter = provider.discover().capabilitySnapshot!!.environmentRevision
        assertEquals(
            "environment must not be cleaned up on foreign release",
            revisionBefore,
            revisionAfter,
        )
    }

    /**
     * M-RL-01 case 2: stale leaseId — a made-up leaseId that doesn't exist.
     */
    @Test
    fun M_RL_01_staleLeaseId() {
        val revisionBefore = provider.discover().capabilitySnapshot!!.environmentRevision

        val releaseResult = provider.release(callerA, ReleaseRequestV1(
            leaseId = "lease-does-not-exist",
            operationId = "op-release-stale",
            idempotencyKey = "release-stale",
        ))

        assertEquals(ContractResultKindV1.ERROR, releaseResult.resultKindOrNull())
        assertEquals(
            ContractErrorCodeV1.STALE_LEASE,
            releaseResult.errorCodeOrInternalFailure(),
        )

        val revisionAfter = provider.discover().capabilitySnapshot!!.environmentRevision
        assertEquals(
            "environment must not change on stale release",
            revisionBefore,
            revisionAfter,
        )
    }

    /**
     * M-RL-01 case 3: already-released leaseId.
     */
    @Test
    fun M_RL_01_alreadyReleasedLeaseId() {
        // Acquire and release
        val applyResult = provider.apply(callerA, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-3",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        val leaseId = applyResult.applyReceipt!!.leaseId

        provider.release(callerA, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-first",
            idempotencyKey = "release-first",
        ))

        val revisionBefore = provider.discover().capabilitySnapshot!!.environmentRevision

        // Try to release again with a DIFFERENT idempotencyKey
        val secondRelease = provider.release(callerA, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-second",
            idempotencyKey = "release-second",
        ))

        assertEquals(ContractResultKindV1.ERROR, secondRelease.resultKindOrNull())
        assertEquals(
            ContractErrorCodeV1.STALE_LEASE,
            secondRelease.errorCodeOrInternalFailure(),
        )

        val revisionAfter = provider.discover().capabilitySnapshot!!.environmentRevision
        assertEquals(revisionBefore, revisionAfter)
    }
}
