package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `pairing` rows M-PA-05, M-PA-09, M-PA-12 (lane `sol-blackbox`, §10.1
 * frozen entry `acceptance/scenarios/src/matrixTest/kotlin/matrix/PairingMatrixTest.kt`).
 *
 * M-PA-05: wrong signer → fail-closed (INV-2)
 * M-PA-09: revoked caller continues calling → typed failure (INV-2, INV-14)
 * M-PA-12: same signer + new versionCode → keeps pairing (INV-2, INV-3, INV-19)
 */
class PairingMatrixTest {

    private lateinit var clock: FakeProviderClock
    private lateinit var provider: FakeQwyProvider

    @Before
    fun setUp() {
        clock = FakeProviderClock()
        provider = FakeQwyProvider(clock)
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
     * M-PA-05: wrong signer → fail-closed.
     *
     * "真千网游未安装，同包名替代实现应答 bind → Auto 反向校验当前 signer 失败 →
     * fail-closed，不进入 CellRebel" (INV-2).
     *
     * The provider-side: a caller with the right applicationId but wrong signer
     * digest must be rejected with CALLER_NOT_ALLOWED.
     */
    @Test
    fun M_PA_05() {
        val trustedCaller = FakeCallerIdentity("com.test.auto", "sha256:genuine")
        val impostorCaller = FakeCallerIdentity("com.test.auto", "sha256:impostor")

        // Pair the genuine signer
        provider.addPairing(trustedCaller)

        // Impostor tries to call with the same applicationId but wrong signer
        val result = provider.apply(impostorCaller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-impostor",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))

        assertEquals(ContractResultKindV1.ERROR, result.resultKindOrNull())
        assertEquals(
            "wrong signer must get CALLER_NOT_ALLOWED (not NOT_PAIRED)",
            ContractErrorCodeV1.CALLER_NOT_ALLOWED,
            result.errorCodeOrInternalFailure(),
        )
    }

    /**
     * M-PA-05 complement: unpaired applicationId → NOT_PAIRED (distinct from
     * CALLER_NOT_ALLOWED). This proves the two codes are correctly separated.
     */
    @Test
    fun M_PA_05_unpaired_isNotPaired() {
        val unknownCaller = FakeCallerIdentity("com.test.unknown", "sha256:unknown")

        val result = provider.apply(unknownCaller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-unknown",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))

        assertEquals(ContractResultKindV1.ERROR, result.resultKindOrNull())
        assertEquals(
            "unpaired applicationId must get NOT_PAIRED",
            ContractErrorCodeV1.NOT_PAIRED,
            result.errorCodeOrInternalFailure(),
        )
    }

    /**
     * M-PA-09: revoked caller continues calling → typed failure.
     *
     * "千网游撤销 caller 后 Auto 继续调用 → 立即 typed 失败，active lease 进入
     * release/recovery" (INV-2, INV-14).
     */
    @Test
    fun M_PA_09() {
        val caller = FakeCallerIdentity("com.test.auto", "sha256:abc123")
        provider.addPairing(caller)

        // Acquire a lease
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-pa09",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())
        val leaseId = applyResult.applyReceipt!!.leaseId

        // Revoke the caller
        provider.revokeCaller("com.test.auto")

        // Verify: active lease should now be in REVOKED state
        val lease = provider.currentLeaseSnapshot()
        assertNotNull("lease must still exist", lease)
        assertEquals(
            "active lease must move to REVOKED on caller revocation",
            FakeQwyProvider.LeaseState.REVOKED,
            lease!!.state,
        )

        // Caller tries to call again → must fail immediately
        val observeResult = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-revoked",
            expectedIntentHash = applyResult.applyReceipt!!.acceptedIntentHash,
        ))

        assertEquals(ContractResultKindV1.ERROR, observeResult.resultKindOrNull())
        assertEquals(
            "revoked caller must get CALLER_NOT_ALLOWED",
            ContractErrorCodeV1.CALLER_NOT_ALLOWED,
            observeResult.errorCodeOrInternalFailure(),
        )

        // Also verify release after revocation fails
        val releaseResult = provider.release(caller, ReleaseRequestV1(
            leaseId = leaseId,
            operationId = "op-release-revoked",
            idempotencyKey = "release-revoked",
        ))

        assertEquals(ContractResultKindV1.ERROR, releaseResult.resultKindOrNull())
        assertEquals(
            "revoked caller cannot release",
            ContractErrorCodeV1.CALLER_NOT_ALLOWED,
            releaseResult.errorCodeOrInternalFailure(),
        )
    }

    /**
     * M-PA-12: same signer + new versionCode → keeps pairing.
     *
     * "同 signer + 新 versionCode（任一侧正常升级）→ 保持配对，由 protocol handshake
     * 决定兼容；不得要求重新配对" (INV-2, INV-3, INV-19).
     *
     * §6.5.4 frozen: versionCode does not participate in identity comparison.
     */
    @Test
    fun M_PA_12() {
        val callerV1 = FakeCallerIdentity("com.test.auto", "sha256:abc123", versionCode = 100)
        val callerV2 = FakeCallerIdentity("com.test.auto", "sha256:abc123", versionCode = 200)

        // Pair with versionCode 100
        provider.addPairing(callerV1)

        // Call with versionCode 100 succeeds
        val result1 = provider.apply(callerV1, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-v100",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, result1.resultKindOrNull())

        // Release the lease
        provider.release(callerV1, ReleaseRequestV1(
            leaseId = result1.applyReceipt!!.leaseId,
            operationId = "op-release-v100",
            idempotencyKey = "release-v100",
        ))

        // Call with versionCode 200 (same signer) must also succeed — no re-pairing
        val result2 = provider.apply(callerV2, ApplyRequestV1(
            intent = EnvironmentIntentV1(
                runId = "run-2",
                attemptId = "attempt-2",
                profileRef = "profile:default",
                scheduleRef = "schedule:default",
                requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                notBeforeEpochMs = clock.epochMs,
                deadlineEpochMs = clock.epochMs + 60_000L,
            ),
            idempotencyKey = "apply-v200",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))

        assertEquals(
            "same signer + new versionCode must succeed without re-pairing",
            ContractResultKindV1.APPLY,
            result2.resultKindOrNull(),
        )
    }
}
