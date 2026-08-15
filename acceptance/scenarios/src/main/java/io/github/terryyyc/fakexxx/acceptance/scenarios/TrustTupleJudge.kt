package io.github.terryyyc.fakexxx.acceptance.scenarios

/**
 * The harness's INDEPENDENT consumer-side trust predicate (§10 `tuple` class,
 * M-TU-01..07; spec anchors: "可信计数只接受 SYSTEM_MOCK_INDEPENDENTLY_VERIFIED"
 * plus INV-27's fail-closed rule and the continuity/observation-window legs).
 *
 * Implemented from the frozen contract text alone — deliberately NOT shared
 * with CellRebel Auto's production counting logic. If this judge and the
 * product disagree on the same tuple, that is a contract-ambiguity finding.
 *
 * Fail-closed is the ONLY default: an attempt counts exactly when every leg
 * proves it may; anything unknown, missing, contradictory or out-of-window is
 * NotCounted with a typed reason.
 */
object TrustTupleJudge {

    /** Typed refusal reasons — scenarios assert the REASON, not just "not counted". */
    enum class Refusal {
        DELIVERY_NOT_SYSTEM_MOCK,          // M-TU-01: HOOK (or unknown) delivery can never carry VERIFIED
        NOT_OBSERVED_AS_MOCK,              // M-TU-02: isMock false/null contradicts VERIFIED
        SCHEDULE_NOT_ALLOWED,              // M-TU-03: DENIED/WAIT_UNTIL forbids counting
        CONTINUITY_WINDOW_UNPROVEN,        // M-TU-04: FULL + continuitySince=null is self-contradictory
        CONTINUITY_STARTED_AFTER_PRE,      // M-TU-05: window does not cover the pre observation
        POST_OBSERVATION_TOO_EARLY,        // M-TU-06: post observed before CellRebel completion
        NO_REVIEWABLE_EVIDENCE,            // M-TU-07: VERIFIED with empty evidenceRefs
        VERIFICATION_NOT_INDEPENDENT,      // catch-all fail-closed: level != SYSTEM_MOCK_INDEPENDENTLY_VERIFIED
    }

    sealed class Verdict {
        data object Counted : Verdict()
        data class NotCounted(val reason: Refusal) : Verdict()
    }

    /**
     * One attempt's complete evidence tuple as the CONSUMER sees it through the
     * public v1 surface (wire ints deliberately — decoding through the frozen
     * enums, including unknown-value fail-closed, is part of what is judged).
     */
    data class AttemptEvidence(
        val deliveryModeWire: Int,
        val verificationLevelWire: Int,
        val isMock: Boolean?,
        val scheduleDecisionWire: Int,
        val continuityCoverageWire: Int,
        val continuitySinceElapsedMs: Long?,
        val preObservedAtElapsedMs: Long,
        val postObservedAtElapsedMs: Long,
        val cellRebelCompletedAtElapsedMs: Long,
        val evidenceRefs: List<String>,
    )

    /**
     * Leg order is FIXED (declaration order below) so a tuple violating several
     * legs refuses deterministically — the M-AD-13 lesson applied to counting:
     * an order left to the implementation makes refusal reasons non-portable.
     * Unknown wire values fail closed on the leg that read them (the frozen
     * enums' fromWire returns null for unknown codes — a newer peer is not a
     * license to count).
     */
    fun judge(evidence: AttemptEvidence): Verdict {
        // Leg 1 — delivery (M-TU-01): only SYSTEM_MOCK delivery can ever count.
        if (io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.fromWire(evidence.deliveryModeWire)
            != io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK
        ) return Verdict.NotCounted(Refusal.DELIVERY_NOT_SYSTEM_MOCK)

        // Leg 2 — verification level: only SYSTEM_MOCK_INDEPENDENTLY_VERIFIED counts.
        if (io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.fromWire(evidence.verificationLevelWire)
            != io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED
        ) return Verdict.NotCounted(Refusal.VERIFICATION_NOT_INDEPENDENT)

        // Leg 3 — observed as mock (M-TU-02): false AND null both refuse.
        if (evidence.isMock != true) return Verdict.NotCounted(Refusal.NOT_OBSERVED_AS_MOCK)

        // Leg 4 — schedule decision (M-TU-03): only ALLOWED_NOW permits counting.
        if (io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.fromWire(evidence.scheduleDecisionWire)
            != io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.ALLOWED_NOW
        ) return Verdict.NotCounted(Refusal.SCHEDULE_NOT_ALLOWED)

        // Leg 5 — continuity window exists (M-TU-04): FULL claims a window;
        // a FULL/absent-window tuple is self-contradictory. Non-FULL coverage
        // is the same refusal: no proven window, nothing to count under.
        if (io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.fromWire(evidence.continuityCoverageWire)
            != io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL
        ) return Verdict.NotCounted(Refusal.CONTINUITY_WINDOW_UNPROVEN)
        val since = evidence.continuitySinceElapsedMs
            ?: return Verdict.NotCounted(Refusal.CONTINUITY_WINDOW_UNPROVEN)

        // Leg 6 — window covers the PRE observation (M-TU-05).
        if (since > evidence.preObservedAtElapsedMs) {
            return Verdict.NotCounted(Refusal.CONTINUITY_STARTED_AFTER_PRE)
        }

        // Leg 7 — post observation really is POST (M-TU-06).
        if (evidence.postObservedAtElapsedMs < evidence.cellRebelCompletedAtElapsedMs) {
            return Verdict.NotCounted(Refusal.POST_OBSERVATION_TOO_EARLY)
        }

        // Leg 8 — reviewable evidence exists (M-TU-07).
        if (evidence.evidenceRefs.isEmpty()) {
            return Verdict.NotCounted(Refusal.NO_REVIEWABLE_EVIDENCE)
        }

        return Verdict.Counted
    }
}
