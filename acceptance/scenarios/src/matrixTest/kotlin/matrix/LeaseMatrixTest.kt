package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `lease` row M-LS-08 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/LeaseMatrixTest.kt`).
 *
 * M-LS-08: "Auto 撤销 provider 后 in-flight lease → Auto 仍被授权，正常 release
 * 收敛；不得走 qwy 自清理路径" (INV-2, INV-28).
 *
 * This tests the CONSUMER-side scenario: Auto revokes its trust in the provider,
 * but an in-flight lease already exists. Auto must still be able to release the
 * lease normally through the v1 contract (it's still authorized on the provider
 * side — the revocation is Auto's local decision). The provider must NOT
 * self-clean (that's the qwy-side revocation path, which is M-PA-09 territory).
 */
class LeaseMatrixTest {

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
     * M-LS-08: Auto revokes provider locally, in-flight lease still releases
     * normally through the contract.
     *
     * The key insight: Auto's revocation of the PROVIDER is a local consumer-side
     * decision. The provider (qwy) still has Auto in its pairing store. So when
     * Auto calls release(), the provider must accept it normally.
     *
     * This is different from M-PA-09 where the PROVIDER revokes the CALLER.
     */
    @Test
    fun M_LS_08() {
        // Auto acquires a lease
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-ls08",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())
        val leaseId = applyResult.applyReceipt!!.leaseId

        // Auto revokes the provider in its local trust store.
        // From the PROVIDER's perspective, nothing changes — the caller (Auto)
        // is still authorized. This is an Auto-side decision.
        // (In a real system, Auto would stop starting NEW intents but would
        // still release existing leases for convergence.)

        // Auto releases the lease — must succeed normally
        val releaseResult = provider.release(caller, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-ls08",
            idempotencyKey = "release-ls08",
        ))

        assertEquals(
            "release must succeed (Auto is still authorized on the provider side)",
            ContractResultKindV1.RELEASE,
            releaseResult.resultKindOrNull(),
        )

        val receipt = releaseResult.releaseReceipt!!
        assertTrue(
            "release must be complete (normal convergence, not self-cleanup)",
            receipt.releaseComplete,
        )

        // Lease must be RELEASED
        val lease = provider.currentLeaseSnapshot()
        assertEquals(
            FakeQwyProvider.LeaseState.RELEASED,
            lease!!.state,
        )
    }

    /**
     * M-LS-08 complement: after Auto revokes provider, it must NOT start new
     * intents. But the provider doesn't enforce this — it's an Auto-side
     * decision. The provider still accepts apply() from a paired caller.
     */
    @Test
    fun M_LS_08_providerStillAcceptsNewApply() {
        // Acquire and release a lease
        val firstApply = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-first-ls08",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        provider.release(caller, ReleaseRequestV1(
            leaseId = firstApply.applyReceipt!!.leaseId,
            operationId = "op-release-first-ls08",
            idempotencyKey = "release-first-ls08",
        ))

        // Auto revokes provider locally (no provider-side effect).
        // Provider still accepts new apply:
        val secondApply = provider.apply(caller, ApplyRequestV1(
            intent = EnvironmentIntentV1(
                runId = "run-2",
                attemptId = "attempt-2",
                profileRef = "profile:default",
                scheduleRef = "schedule:default",
                requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                notBeforeEpochMs = clock.epochMs,
                deadlineEpochMs = clock.epochMs + 60_000L,
            ),
            idempotencyKey = "apply-second-ls08",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(
            "provider still accepts new apply from authorized caller",
            ContractResultKindV1.APPLY,
            secondApply.resultKindOrNull(),
        )
    }
}
