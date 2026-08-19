package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `bypass` rows M-BP-04, M-BP-05 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/BypassMatrixTest.kt`).
 *
 * M-BP-04: HOOK delivery + isMock=true → TrustPolicy rejects (INV-5, INV-6)
 * M-BP-05: PARTIAL coverage + heartbeat continues → TrustPolicy rejects (INV-8, INV-9)
 *
 * These are bypass scenarios: an attacker tries to get trusted counting through
 * combinations that look almost right. The trust predicate must refuse.
 */
class BypassMatrixTest {

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
     * M-BP-04: Hook delivery returns isMock=true trying to enter trusted counting.
     *
     * "Hook 返回 isMock=true 试图进可信账 → TrustPolicy 拒绝" (INV-5, INV-6).
     *
     * End-to-end: provider configured to return HOOK delivery mode with isMock=true,
     * observation flows to TrustTupleJudge → must be NotCounted with
     * DELIVERY_NOT_SYSTEM_MOCK reason.
     */
    @Test
    fun M_BP_04() {
        // Configure provider to return HOOK delivery mode with isMock=true
        provider.overrideDeliveryModeWire = DeliveryModeV1.HOOK.wire
        provider.overrideIsMock = true

        // Apply
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-bp04",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        val leaseId = applyResult.applyReceipt!!.leaseId
        val intentHash = applyResult.applyReceipt!!.acceptedIntentHash

        // Pre-observation
        clock.advance(1_000L)
        val preObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-pre",
            expectedIntentHash = intentHash,
        ))
        assertEquals(ContractResultKindV1.OBSERVE, preObs.resultKindOrNull())
        val preObservation = preObs.environmentObservation!!

        // Simulate CellRebel completion
        val cellRebelCompletedAt = clock.elapsedRealtimeMs + 5_000L
        clock.advance(6_000L)

        // Post-observation
        val postObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-post",
            expectedIntentHash = intentHash,
        ))
        val postObservation = postObs.environmentObservation!!

        // Consumer-side: build trust tuple from observations
        val evidence = TrustTupleJudge.AttemptEvidence(
            deliveryModeWire = postObservation.deliveryModeWire ?: 0,
            verificationLevelWire = postObservation.verificationLevelWire,
            isMock = postObservation.isMock,
            scheduleDecisionWire = postObservation.scheduleDecisionWire,
            continuityCoverageWire = postObservation.continuityCoverageWire,
            continuitySinceElapsedMs = postObservation.continuitySinceElapsedRealtimeMs,
            preObservedAtElapsedMs = preObservation.observedAtElapsedRealtimeMs,
            postObservedAtElapsedMs = postObservation.observedAtElapsedRealtimeMs,
            cellRebelCompletedAtElapsedMs = cellRebelCompletedAt,
            evidenceRefs = postObservation.evidenceRefs,
        )

        val verdict = TrustTupleJudge.judge(evidence)
        assertTrue(
            "HOOK delivery + isMock=true must be rejected by trust policy",
            verdict is TrustTupleJudge.Verdict.NotCounted,
        )
        assertEquals(
            "rejection reason must be DELIVERY_NOT_SYSTEM_MOCK",
            TrustTupleJudge.Refusal.DELIVERY_NOT_SYSTEM_MOCK,
            (verdict as TrustTupleJudge.Verdict.NotCounted).reason,
        )
    }

    /**
     * M-BP-05: PARTIAL coverage but heartbeat continues → TrustPolicy rejects.
     *
     * "coverage PARTIAL 但心跳持续 → TrustPolicy 拒绝" (INV-8, INV-9).
     *
     * Even if the heartbeat keeps running (isMock=true, everything else looks
     * good), PARTIAL coverage means the continuity window is not proven for the
     * entire observation period. TrustPolicy must reject.
     */
    @Test
    fun M_BP_05() {
        // Configure provider to return PARTIAL coverage
        provider.setContinuity(
            ContinuityCoverageV1.PARTIAL,
            sinceElapsedMs = clock.elapsedRealtimeMs,
            sinceEpochMs = clock.epochMs,
        )

        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-bp05",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        val leaseId = applyResult.applyReceipt!!.leaseId
        val intentHash = applyResult.applyReceipt!!.acceptedIntentHash

        // Pre-observation
        clock.advance(1_000L)
        val preObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-pre-bp05",
            expectedIntentHash = intentHash,
        ))
        val preObservation = preObs.environmentObservation!!

        // CellRebel runs and completes
        val cellRebelCompletedAt = clock.elapsedRealtimeMs + 5_000L
        clock.advance(6_000L)

        // Post-observation
        val postObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-post-bp05",
            expectedIntentHash = intentHash,
        ))
        val postObservation = postObs.environmentObservation!!

        // Consumer-side trust tuple
        val evidence = TrustTupleJudge.AttemptEvidence(
            deliveryModeWire = postObservation.deliveryModeWire ?: DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = postObservation.verificationLevelWire,
            isMock = postObservation.isMock,
            scheduleDecisionWire = postObservation.scheduleDecisionWire,
            continuityCoverageWire = postObservation.continuityCoverageWire,
            continuitySinceElapsedMs = postObservation.continuitySinceElapsedRealtimeMs,
            preObservedAtElapsedMs = preObservation.observedAtElapsedRealtimeMs,
            postObservedAtElapsedMs = postObservation.observedAtElapsedRealtimeMs,
            cellRebelCompletedAtElapsedMs = cellRebelCompletedAt,
            evidenceRefs = postObservation.evidenceRefs,
        )

        val verdict = TrustTupleJudge.judge(evidence)
        assertTrue(
            "PARTIAL coverage must be rejected by trust policy",
            verdict is TrustTupleJudge.Verdict.NotCounted,
        )
        assertEquals(
            "rejection reason must be CONTINUITY_WINDOW_UNPROVEN",
            TrustTupleJudge.Refusal.CONTINUITY_WINDOW_UNPROVEN,
            (verdict as TrustTupleJudge.Verdict.NotCounted).reason,
        )
    }
}
