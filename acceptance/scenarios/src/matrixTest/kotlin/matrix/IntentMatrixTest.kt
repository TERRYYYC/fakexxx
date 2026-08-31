package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.PI

/**
 * §10 `intent` rows M-IN-01, M-IN-02, M-IN-03 (lane `sol-blackbox`, §10.1
 * frozen entry `acceptance/scenarios/src/matrixTest/kotlin/matrix/IntentMatrixTest.kt`).
 *
 * M-IN-01: apply partially effective → not trusted (INV-23)
 * M-IN-02: lease reuse with changed intent → ENVIRONMENT_DRIFT (INV-23)
 * M-IN-03: observation with null coordinates → not counted (INV-23)
 */
class IntentMatrixTest {

    private lateinit var clock: FakeProviderClock
    private lateinit var provider: FakeQwyProvider
    private val caller = FakeCallerIdentity("com.test.auto", "sha256:abc123")

    @Before
    fun setUp() {
        clock = FakeProviderClock()
        provider = FakeQwyProvider(clock)
        provider.addPairing(caller)
        provider.setSchedule("sched-1", listOf(
            FakeQwyProvider.ScheduleItem(
                itemId = "item-1",
                latitude = 31.2304,
                longitude = 121.4737,
            ),
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
     * M-IN-01: apply partially effective, coordinates stuck at wrong address.
     *
     * "apply 部分生效，有效坐标停在上一地址 → 千网游侧检出并拒绝把该 observation
     * 报成可信证据（KB-8 = 千网游是唯一同时持有目标与生效坐标的一方）；Auto 侧的检出面
     * 只剩身份腿（acceptedIntentHash / scheduleItemId / scheduleVersion）→ 未验证，
     * 不计数。不得把本行读作 Auto 侧的距离校验" (INV-23).
     *
     * The provider (fake-qwy) is the sole coordinate authority (KB-8). When the
     * environment is only partially applied, the observation's coordinates reflect
     * the actual state (wrong location). The provider MUST detect the mismatch
     * between effective and target coordinates and downgrade verificationLevelWire
     * — it must NOT report SYSTEM_MOCK_INDEPENDENTLY_VERIFIED for a partially
     * applied environment.
     *
     * End-to-end: provider downgrades → TrustTupleJudge rejects via Leg 2
     * (VERIFICATION_NOT_INDEPENDENT). Same pattern as M-BP-04.
     */
    @Test
    fun M_IN_01() {
        // Keep the mismatch deliberately close: 2 m is outside the frozen 1 m
        // contract tolerance but inside the fake's former 0.0001 degree
        // (roughly 11 m) box. A far-away (0,0) fixture could not distinguish the
        // contract from that over-wide approximation.
        val twoMetersNorth = 2.0 / 6_371_008.8 * 180.0 / PI
        provider.overrideCoordinates = Pair(31.2304 + twoMetersNorth, 121.4737)

        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-in01",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())

        val leaseId = applyResult.applyReceipt!!.leaseId
        val intentHash = applyResult.applyReceipt!!.acceptedIntentHash

        // Pre-observation (coordinates wrong → provider must downgrade verification)
        clock.advance(1_000L)
        val preObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-pre-in01",
            expectedIntentHash = intentHash,
        ))
        assertEquals(ContractResultKindV1.OBSERVE, preObs.resultKindOrNull())
        val preObservation = preObs.environmentObservation!!

        // Provider-side assertion: verification level MUST be downgraded
        assertNotEquals(
            "provider must NOT report INDEPENDENTLY_VERIFIED for mismatched coordinates",
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            preObservation.verificationLevelWire,
        )

        // Simulate CellRebel completion
        val cellRebelCompletedAt = clock.elapsedRealtimeMs + 5_000L
        clock.advance(6_000L)

        // Post-observation
        val postObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-post-in01",
            expectedIntentHash = intentHash,
        ))
        val postObservation = postObs.environmentObservation!!

        // Consumer-side end-to-end: TrustTupleJudge must reject (same as M-BP-04)
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
            "partially applied coordinates must be rejected by trust policy",
            verdict is TrustTupleJudge.Verdict.NotCounted,
        )
        assertEquals(
            "rejection reason must be VERIFICATION_NOT_INDEPENDENT (provider downgraded)",
            TrustTupleJudge.Refusal.VERIFICATION_NOT_INDEPENDENT,
            (verdict as TrustTupleJudge.Verdict.NotCounted).reason,
        )
    }

    /**
     * M-IN-02: lease reuse but intent has changed → ENVIRONMENT_DRIFT.
     *
     * "lease 复用但意图已切换，observation 仍返回旧 intent hash →
     * ENVIRONMENT_DRIFT，不计数" (INV-23).
     *
     * When the consumer calls observe() with an expectedIntentHash that doesn't
     * match the lease's acceptedIntentHash, the provider must return
     * ENVIRONMENT_DRIFT (wire 9).
     */
    @Test
    fun M_IN_02() {
        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-in02",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())

        val leaseId = applyResult.applyReceipt!!.leaseId
        // Use a DIFFERENT intent hash than what was accepted
        val wrongIntentHash = "sha256-wrong-intent-hash-does-not-match"

        val observeResult = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-drift",
            expectedIntentHash = wrongIntentHash,
        ))

        assertEquals(ContractResultKindV1.ERROR, observeResult.resultKindOrNull())
        assertEquals(
            "mismatched intent hash must return ENVIRONMENT_DRIFT (9)",
            ContractErrorCodeV1.ENVIRONMENT_DRIFT,
            observeResult.errorCodeOrInternalFailure(),
        )
    }

    /**
     * M-IN-03: observation with null effective coordinates → not counted.
     *
     * "observation 的 effectiveLat/Lng 为 null → 不计数（不得因'其他条件都过'放行）"
     * (INV-23).
     *
     * The provider can produce an observation with null coordinates (e.g., when the
     * environment could not resolve the target). The provider MUST detect that it
     * cannot confirm coordinate delivery and downgrade verificationLevelWire.
     * The consumer must NOT count this attempt regardless of other conditions.
     *
     * End-to-end: provider downgrades → TrustTupleJudge rejects via Leg 2
     * (VERIFICATION_NOT_INDEPENDENT). Same pattern as M-BP-04 / M-IN-01.
     */
    @Test
    fun M_IN_03() {
        // Force null coordinates — provider cannot confirm delivery
        provider.overrideCoordinates = Pair(null, null)

        val applyResult = provider.apply(caller, ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "apply-in03",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))
        assertEquals(ContractResultKindV1.APPLY, applyResult.resultKindOrNull())

        val leaseId = applyResult.applyReceipt!!.leaseId
        val intentHash = applyResult.applyReceipt!!.acceptedIntentHash

        // Pre-observation (null coords → provider must downgrade verification)
        clock.advance(1_000L)
        val preObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-pre-in03",
            expectedIntentHash = intentHash,
        ))
        assertEquals(ContractResultKindV1.OBSERVE, preObs.resultKindOrNull())
        val preObservation = preObs.environmentObservation!!

        // Coordinates must be null
        assertNull("effectiveLatitude must be null", preObservation.effectiveLatitude)
        assertNull("effectiveLongitude must be null", preObservation.effectiveLongitude)

        // Provider-side assertion: verification level MUST be downgraded
        assertNotEquals(
            "provider must NOT report INDEPENDENTLY_VERIFIED for null coordinates",
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            preObservation.verificationLevelWire,
        )

        // Simulate CellRebel completion
        val cellRebelCompletedAt = clock.elapsedRealtimeMs + 5_000L
        clock.advance(6_000L)

        // Post-observation
        val postObs = provider.observe(caller, ObserveRequestV1(
            leaseId = leaseId,
            operationId = "op-observe-post-in03",
            expectedIntentHash = intentHash,
        ))
        val postObservation = postObs.environmentObservation!!

        // Consumer-side end-to-end: TrustTupleJudge must reject
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
            "null coordinates must be rejected by trust policy",
            verdict is TrustTupleJudge.Verdict.NotCounted,
        )
        assertEquals(
            "rejection reason must be VERIFICATION_NOT_INDEPENDENT (provider downgraded)",
            TrustTupleJudge.Refusal.VERIFICATION_NOT_INDEPENDENT,
            (verdict as TrustTupleJudge.Verdict.NotCounted).reason,
        )
    }
}
