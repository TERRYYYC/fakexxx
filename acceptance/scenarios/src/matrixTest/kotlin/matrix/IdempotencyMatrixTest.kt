package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `idempotency` row M-ID-01 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/IdempotencyMatrixTest.kt`).
 *
 * M-ID-01: "同 idempotencyKey 异 payload → IDEMPOTENCY_CONFLICT（不得复用
 * LEASE_CONFLICT）" (INV-13).
 *
 * The provider must distinguish "replayed same request" (returns original receipt)
 * from "same key, different payload" (rejects with IDEMPOTENCY_CONFLICT, wire 12).
 * Using LEASE_CONFLICT (wire 7) would conflate "another lease blocks you" with
 * "your key collides", making the consumer's recovery branch non-portable.
 */
class IdempotencyMatrixTest {

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
        ))
    }

    private fun intent(
        runId: String = "run-1",
        attemptId: String = "attempt-1",
    ) = EnvironmentIntentV1(
        runId = runId,
        attemptId = attemptId,
        profileRef = "profile:default",
        scheduleRef = "schedule:default",
        requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs = clock.epochMs,
        deadlineEpochMs = clock.epochMs + 60_000L,
    )

    /**
     * M-ID-01: same idempotencyKey with a DIFFERENT payload must fail with
     * IDEMPOTENCY_CONFLICT (wire 12), NOT LEASE_CONFLICT (wire 7).
     */
    @Test
    fun M_ID_01() {
        // First apply succeeds
        val first = provider.apply(caller, ApplyRequestV1(
            intent = intent(attemptId = "attempt-1"),
            idempotencyKey = "key-alpha",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(
            "first apply should succeed",
            ContractResultKindV1.APPLY,
            first.resultKindOrNull(),
        )

        // Release the lease so there's no LEASE_CONFLICT masking
        val leaseId = first.applyReceipt!!.leaseId
        provider.release(caller, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-1",
            idempotencyKey = "release-key-1",
        ))

        // Same idempotencyKey, DIFFERENT payload (different attemptId → different
        // intent hash → different payload digest)
        val conflict = provider.apply(caller, ApplyRequestV1(
            intent = intent(attemptId = "attempt-2-different"),
            idempotencyKey = "key-alpha",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))

        assertEquals(
            "same key + different payload must be ERROR",
            ContractResultKindV1.ERROR,
            conflict.resultKindOrNull(),
        )
        assertEquals(
            "error code must be IDEMPOTENCY_CONFLICT (12), NOT LEASE_CONFLICT (7)",
            ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
            conflict.errorCodeOrInternalFailure(),
        )
    }

    /**
     * Baseline: same idempotencyKey with the SAME payload replays the original receipt.
     */
    @Test
    fun M_ID_01_baseline_samePayload_replays() {
        val first = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "key-beta",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, first.resultKindOrNull())

        // Same key, same payload → replay
        val replay = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "key-beta",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, replay.resultKindOrNull())
        assertEquals(
            "replay must return same leaseId",
            first.applyReceipt!!.leaseId,
            replay.applyReceipt!!.leaseId,
        )
    }
}
