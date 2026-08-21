package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `version` row M-VS-02 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/VersionMatrixTest.kt`).
 *
 * M-VS-02: "对端返回未知枚举 wire code → fromWire 返回 null → fail-closed，
 * 不得崩在 Binder transaction 内" (INV-3, INV-4).
 *
 * When the provider returns a wire code that the consumer's frozen enum domain
 * doesn't recognize, `fromWire()` returns `null`. The consumer must fail closed
 * (never treat unknown as compatible) and must NOT crash in the Binder
 * transaction. This test verifies that the contract's `fromWire()` methods
 * correctly return null for unknown codes, and that the consumer-side trust
 * predicate rejects tuples with unknown wire codes.
 */
class VersionMatrixTest {

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

    /**
     * M-VS-02: fromWire returns null for unknown wire codes — fail-closed at the
     * enum decode layer. This is the foundation for every consumer-side fail-closed
     * decision: if fromWire returned a value for an unknown code, the consumer
     * would have to guess whether to trust it.
     */
    @Test
    fun M_VS_02_unknownWireCodesReturnNull() {
        // Verification level — unknown code must not decode
        assertNull(
            "VerificationLevelV1.fromWire(999) must return null",
            VerificationLevelV1.fromWire(999),
        )

        // Continuity coverage — unknown code must not decode
        assertNull(
            "ContinuityCoverageV1.fromWire(999) must return null",
            ContinuityCoverageV1.fromWire(999),
        )

        // Delivery mode — unknown code must not decode
        assertNull(
            "DeliveryModeV1.fromWire(999) must return null",
            DeliveryModeV1.fromWire(999),
        )

        // Schedule decision — unknown code must not decode
        assertNull(
            "ScheduleDecisionV1.fromWire(999) must return null",
            ScheduleDecisionV1.fromWire(999),
        )

        // Error code — strict fromWire must return null
        assertNull(
            "ContractErrorCodeV1.fromWire(999) must return null",
            ContractErrorCodeV1.fromWire(999),
        )

        // Error code — fail-closed fromWireOrInternalFailure must return INTERNAL_FAILURE
        assertEquals(
            "ContractErrorCodeV1.fromWireOrInternalFailure(999) must be INTERNAL_FAILURE",
            ContractErrorCodeV1.INTERNAL_FAILURE,
            ContractErrorCodeV1.fromWireOrInternalFailure(999),
        )

        // Result kind — unknown code must not decode
        assertNull(
            "ContractResultKindV1.fromWire(999) must return null",
            ContractResultKindV1.fromWire(999),
        )

        // Advance outcome — unknown code must not decode
        assertNull(
            "AdvanceOutcomeV1.fromWire(999) must return null",
            AdvanceOutcomeV1.fromWire(999),
        )

        // Advance outcome — fail-closed helper must return false for unknown
        assertFalse(
            "AdvanceOutcomeV1.advancedOrFailClosed(999) must be false",
            AdvanceOutcomeV1.advancedOrFailClosed(999),
        )

        // Wire 0 is intentionally unknown and must fail closed (ContractResultKindV1 KDoc)
        assertNull(
            "ContractResultKindV1.fromWire(0) must return null (intentionally unknown)",
            ContractResultKindV1.fromWire(0),
        )
    }

    /**
     * M-VS-02: provider sends unknown wire codes in observation → consumer trust
     * predicate rejects. The TrustTupleJudge's legs that decode via fromWire must
     * refuse when fromWire returns null.
     */
    @Test
    fun M_VS_02_unknownWireInObservation_trustRejects() {
        // Build a tuple with an unknown delivery mode wire code
        val unknownDeliveryTuple = io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.AttemptEvidence(
            deliveryModeWire = 999,  // unknown
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            continuitySinceElapsedMs = 1_000L,
            preObservedAtElapsedMs = 2_000L,
            postObservedAtElapsedMs = 10_000L,
            cellRebelCompletedAtElapsedMs = 9_000L,
            evidenceRefs = listOf("qwy:audit:1"),
        )

        val verdict = io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.judge(unknownDeliveryTuple)
        assertTrue(
            "unknown delivery mode wire → must be NotCounted",
            verdict is io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.Verdict.NotCounted,
        )

        // Build a tuple with an unknown verification level wire code
        val unknownVerificationTuple = io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.AttemptEvidence(
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = 999,  // unknown
            isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            continuitySinceElapsedMs = 1_000L,
            preObservedAtElapsedMs = 2_000L,
            postObservedAtElapsedMs = 10_000L,
            cellRebelCompletedAtElapsedMs = 9_000L,
            evidenceRefs = listOf("qwy:audit:1"),
        )

        val verdict2 = io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.judge(unknownVerificationTuple)
        assertTrue(
            "unknown verification level wire → must be NotCounted",
            verdict2 is io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.Verdict.NotCounted,
        )

        // Unknown continuity coverage wire
        val unknownCoverageTuple = io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.AttemptEvidence(
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            continuityCoverageWire = 999,  // unknown
            continuitySinceElapsedMs = 1_000L,
            preObservedAtElapsedMs = 2_000L,
            postObservedAtElapsedMs = 10_000L,
            cellRebelCompletedAtElapsedMs = 9_000L,
            evidenceRefs = listOf("qwy:audit:1"),
        )

        val verdict3 = io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.judge(unknownCoverageTuple)
        assertTrue(
            "unknown continuity coverage wire → must be NotCounted",
            verdict3 is io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.Verdict.NotCounted,
        )
    }

    /**
     * M-VS-02: provider observation with injected unknown wire codes — the observation
     * is returned normally (no Binder crash), but the consumer must fail-closed on
     * the unknown codes.
     */
    @Test
    fun M_VS_02_providerReturnsUnknownWires_noCrash() {
        provider.injectUnknownWireCodes = true

        // Apply first (with normal wire codes for the apply itself)
        provider.injectUnknownWireCodes = false
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = EnvironmentIntentV1(
                runId = "run-1",
                attemptId = "attempt-1",
                profileRef = "profile:default",
                scheduleRef = "schedule:default",
                requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                notBeforeEpochMs = clock.epochMs,
                deadlineEpochMs = clock.epochMs + 60_000L,
            ),
            idempotencyKey = "apply-vs02",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        val leaseId = applyResult.applyReceipt!!.leaseId
        val intentHash = applyResult.applyReceipt!!.acceptedIntentHash

        // Now enable unknown wires and observe
        provider.injectUnknownWireCodes = true
        val observeResult = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-vs02",
            expectedIntentHash = intentHash,
        ))

        // The observation must be returned (no crash)
        assertEquals(
            "observation must be returned even with unknown wires",
            ContractResultKindV1.OBSERVE,
            observeResult.resultKindOrNull(),
        )

        val obs = observeResult.environmentObservation!!

        // The consumer must be able to detect the unknown wire codes
        assertNull(
            "unknown delivery mode wire must decode to null",
            DeliveryModeV1.fromWire(obs.deliveryModeWire!!),
        )
        assertNull(
            "unknown verification level wire must decode to null",
            VerificationLevelV1.fromWire(obs.verificationLevelWire),
        )
    }
}
