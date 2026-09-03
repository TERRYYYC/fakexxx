package com.example.cellrebelauto.environment

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * R43 (Sol GREEN-review P1-1): the frozen-contract adapter oracles.
 *
 * 1. Wire mapping: every frozen enum wire code maps to the exact §6.4 name TrustPolicy compares;
 *    UNKNOWN codes map to sentinels the policy rejects (M-VS-02 fail-closed, never silent trust).
 * 2. Canonical digest: Auto's intent digest delegates to the frozen [CanonicalIntentDigestV1] —
 *    the digest of the same intent fields is stable and sensitive to every field.
 *
 * # 契约适配器 oracle：wire→name 精确映射 + 未知 wire fail-closed + canonical digest 委托冻结算法
 */
class ObservationWireAdapterTest {

    private fun wireObservation(
        coverageWire: Int = ContinuityCoverageV1.FULL.wire,
        verificationWire: Int = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        deliveryWire: Int? = DeliveryModeV1.SYSTEM_MOCK.wire,
        scheduleWire: Int = ScheduleDecisionV1.ALLOWED_NOW.wire
    ) = EnvironmentObservationV1(
        leaseId = "lease-1",
        acceptedIntentHash = "intent-h",
        observedAtEpochMs = 900L,
        observedAtElapsedRealtimeMs = 1000L,
        environmentRevision = 7L,
        environmentFingerprint = "fp-1",
        continuityCoverageWire = coverageWire,
        continuitySinceEpochMs = 800L,
        continuitySinceElapsedRealtimeMs = 500L,
        deliveryModeWire = deliveryWire,
        verificationLevelWire = verificationWire,
        effectiveLatitude = 39.9,
        effectiveLongitude = 116.4,
        isMock = true,
        scheduleDecisionWire = scheduleWire,
        evidenceRefs = listOf("qwy:store:abc"),
        scheduleItemId = "item-1",
        scheduleVersion = 1L
    )

    @Test
    fun `frozen wire codes map to the exact section 6_4 names the TrustPolicy compares`() {
        val snapshot = ObservationWireAdapter.toSnapshot(wireObservation())
        assertEquals("FULL", snapshot.coverage)
        assertEquals("SYSTEM_MOCK_INDEPENDENTLY_VERIFIED", snapshot.verificationLevel)
        assertEquals("SYSTEM_MOCK", snapshot.deliveryMode)
        assertEquals("ALLOWED_NOW", snapshot.scheduleDecision)
        assertEquals("lease-1", snapshot.leaseId)
        assertEquals("intent-h", snapshot.acceptedIntentHash)
        assertEquals(listOf("qwy:store:abc"), snapshot.evidenceRefs)
        assertEquals(7L, snapshot.environmentRevision)
        assertEquals("audit continuity wall clock must round-trip verbatim", 800L, snapshot.continuitySinceEpochMs)
        assertEquals(500L, snapshot.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `an unknown coverage wire maps to a sentinel the TrustPolicy rejects (M-VS-02 fail-closed)`() {
        val snapshot = ObservationWireAdapter.toSnapshot(wireObservation(coverageWire = 99))
        assertEquals("UNKNOWN_COVERAGE", snapshot.coverage)
        assertEquals(
            "an unknown-wire snapshot must FAIL the trust policy (never a silent upgrade)",
            TrustDecision.FAIL,
            TrustPolicy().evaluate(trustContextFor(snapshot))
        )
    }

    @Test
    fun `an unknown verification wire maps to a sentinel the TrustPolicy rejects`() {
        val snapshot = ObservationWireAdapter.toSnapshot(wireObservation(verificationWire = 99))
        assertEquals("UNKNOWN_VERIFICATION", snapshot.verificationLevel)
        assertEquals(TrustDecision.FAIL, TrustPolicy().evaluate(trustContextFor(snapshot)))
    }

    @Test
    fun `an unknown delivery wire maps to a sentinel the TrustPolicy rejects`() {
        val snapshot = ObservationWireAdapter.toSnapshot(wireObservation(deliveryWire = 99))
        assertEquals("UNKNOWN_DELIVERY", snapshot.deliveryMode)
        assertEquals(TrustDecision.FAIL, TrustPolicy().evaluate(trustContextFor(snapshot)))
    }

    @Test
    fun `a null delivery wire (provider cannot determine) fails closed`() {
        val snapshot = ObservationWireAdapter.toSnapshot(wireObservation(deliveryWire = null))
        assertEquals("UNKNOWN_DELIVERY", snapshot.deliveryMode)
        assertEquals(TrustDecision.FAIL, TrustPolicy().evaluate(trustContextFor(snapshot)))
    }

    @Test
    fun `an unknown schedule wire maps to a sentinel the TrustPolicy rejects`() {
        val snapshot = ObservationWireAdapter.toSnapshot(wireObservation(scheduleWire = 99))
        assertEquals("UNKNOWN_SCHEDULE", snapshot.scheduleDecision)
        assertEquals(TrustDecision.FAIL, TrustPolicy().evaluate(trustContextFor(snapshot)))
    }

    // ---- canonical digest delegation (the frozen algorithm, not Auto's v0 placeholder) ----

    @Test
    fun `the canonical digest is delegated to the frozen CanonicalIntentDigestV1 and is field-sensitive`() {
        val intent = EnvironmentIntentV1(
            runId = "run-1", attemptId = "77",
            profileRef = "profile-a", scheduleRef = "sched-b",
            requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = 1000L, deadlineEpochMs = 9000L
        )
        val d1 = io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1.compute(intent)
        val d2 = io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1.compute(intent)
        assertEquals("the digest is deterministic", d1, d2)
        // Every field is sensitivity-tested: changing ANY field changes the digest.
        val variants = listOf(
            intent.copy(runId = "run-2"),
            intent.copy(attemptId = "78"),
            intent.copy(profileRef = "profile-z"),
            intent.copy(scheduleRef = "sched-z"),
            intent.copy(notBeforeEpochMs = 1001L),
            intent.copy(deadlineEpochMs = 9001L)
        )
        for (v in variants) {
            assertNotEquals(
                "changing ${if (v.runId != intent.runId) "runId" else if (v.attemptId != intent.attemptId) "attemptId" else "a field"} must change the digest",
                d1,
                io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1.compute(v)
            )
        }
    }

    /** A minimal context around the given PRE snapshot so the policy can be driven on the sentinel. */
    private fun trustContextFor(pre: ObservationSnapshot): CompletionTrustContext {
        val exec = com.example.cellrebelauto.model.execution.CellRebelExecution(
            executionId = "exec-1", attemptId = 1L,
            completionEvidenceWire = 1, evidencePayloadDigest = "d",
            startedAt = 1000L, classifiedAt = 1100L,
            startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L, completedAtElapsed = 13000L
        )
        return CompletionTrustContext(
            execution = exec,
            completionEvidenceWire = 1,
            applyReceiptIntentHash = pre.acceptedIntentHash,
            locallyRecomputedIntentHash = pre.acceptedIntentHash,
            applyReceiptLease = pre.leaseId,
            preObservation = pre,
            postObservation = pre.copy(observedAtElapsedRealtimeMs = 14000L)
        )
    }
}
