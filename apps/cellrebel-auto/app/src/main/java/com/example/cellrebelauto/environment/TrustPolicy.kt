package com.example.cellrebelauto.environment

/**
 * Decides whether an observed CellRebel completion may mint a
 * [com.example.cellrebelauto.model.ledger.TrustedQuotaEntry] (§8.1 DECIDING → QUOTA_COMMITTED).
 *
 * Per §8.6 / §9 only VERIFIED_NEW_COMPLETION (wire 1), with pre + post observation bound to the
 * SAME lease (INV-07), three-way intent hash agreement (INV-23), structurally valid provider
 * effective coordinates, and mode/isMock/coverage/timing cross-consistent with the verification level (INV-27),
 * may PASS. Every other combination FAILs — wires 2-5 never produce trusted quota (INV-11).
 *
 * GREEN (contract v1 frozen, `feat/pr-2-contract-v1@635a73a8`): the full §6.4 predicate. Both
 * observations are validated INDEPENDENTLY and symmetrically — PRE-only or POST-only validation is
 * a false oracle (F1c). KB-8 assigns target-coordinate ownership and the distance comparison
 * exclusively to Qianwangyou; Auto validates only the returned coordinates' structure. The RUN-phase floor
 * (completedAt − runningConfirmedAt ≥ [MIN_RUNNING_EVIDENCE_MS]) rejects sub-10s runs (Sol round-3
 * Finding 1). Continuity comparisons use ONLY `*ElapsedRealtimeMs` fields — the epoch fields are
 * human-readable audit and never enter a predicate (§6.4.2).
 *
 * # 信任策略 GREEN：完整 §6.4 谓词；pre/post 对称独立校验；坐标仅作结构校验；仅 monotonic 时钟可比
 */
class TrustPolicy {

    fun evaluate(context: CompletionTrustContext): TrustDecision {
        // §8.6.2: only wire 1 (VERIFIED_NEW_COMPLETION) may ever mint (INV-11). The classified
        // EXECUTION row must agree — a receipt claiming wire 1 while the execution carries 2-5
        // (or vice versa) is a disagreement and fails closed (Sol R39 wire-disagreement closure).
        if (context.completionEvidenceWire != WIRE_VERIFIED_NEW_COMPLETION) return TrustDecision.FAIL
        if (context.execution.completionEvidenceWire != WIRE_VERIFIED_NEW_COMPLETION) return TrustDecision.FAIL

        // INV-23 three-way intent: receipt hash == locally recomputed hash (each observation's
        // acceptedIntentHash == receipt is checked per-observation below).
        if (context.applyReceiptIntentHash != context.locallyRecomputedIntentHash) return TrustDecision.FAIL

        val pre = context.preObservation
        val post = context.postObservation

        // Each observation is validated INDEPENDENTLY and symmetrically (F1c: a PRE-only impl is a
        // false oracle; every per-observation discriminator applies to BOTH).
        for (obs in listOf(pre, post)) {
            if (!observationIsValid(obs, context)) return TrustDecision.FAIL
        }

        // Cross-observation consistency (§6.4): revision / fingerprint / continuitySince must be EQUAL.
        if (pre.environmentRevision != post.environmentRevision) return TrustDecision.FAIL
        if (pre.environmentFingerprint != post.environmentFingerprint) return TrustDecision.FAIL
        if (pre.continuitySinceElapsedRealtimeMs != post.continuitySinceElapsedRealtimeMs) return TrustDecision.FAIL

        val exec = context.execution

        // Continuity window must start at or before the pre observation (§6.4.1: FULL coverage claims
        // a window that did not cover the test fail; both sides equal is checked above).
        val continuitySince = pre.continuitySinceElapsedRealtimeMs
        if (continuitySince == null || continuitySince > pre.observedAtElapsedRealtimeMs) return TrustDecision.FAIL

        // §6.4.2 monotonic bracketing (elapsedRealtime is the ONLY comparable clock):
        // pre.observedAt < execution.startedAtElapsed, and execution.completedAtElapsed < post.observedAt.
        if (pre.observedAtElapsedRealtimeMs >= exec.startedAtElapsed) return TrustDecision.FAIL
        if (post.observedAtElapsedRealtimeMs <= exec.completedAtElapsed) return TrustDecision.FAIL

        // §6.4.2 RUN-phase floor: completedAt − runningConfirmedAt ≥ MIN_RUNNING_EVIDENCE_MS —
        // a sub-10s run is NOT a trusted completion (Sol round-3 Finding 1).
        if (exec.completedAtElapsed - exec.runningConfirmedAtElapsed < MIN_RUNNING_EVIDENCE_MS) return TrustDecision.FAIL

        return TrustDecision.PASS
    }

    /** Per-observation §6.4 predicate: every discriminator, both phases (F1c symmetric). */
    private fun observationIsValid(obs: ObservationSnapshot, context: CompletionTrustContext): Boolean {
        // §6.4.1 per-field wire values — exact match, never ordinal/`>=` comparison (INV-05).
        if (obs.coverage != COVERAGE_FULL) return false
        if (obs.verificationLevel != LEVEL_SYSTEM_MOCK_INDEPENDENTLY_VERIFIED) return false
        if (obs.deliveryMode != DELIVERY_SYSTEM_MOCK) return false
        if (obs.isMock != true) return false // null ("unknown") cannot stand for "verified"
        if (obs.scheduleDecision != SCHEDULE_ALLOWED_NOW) return false
        if (obs.evidenceRefs.isEmpty()) return false // empty + VERIFIED ⇒ unverifiable
        if (obs.effectiveLat == null || obs.effectiveLng == null) return false

        // INV-07: each observation is bound to the RECEIPT's lease — not merely to each other
        // (R6-F1 un-bound-lease false oracle).
        if (obs.leaseId != context.applyReceiptLease) return false

        // INV-23: each observation's intent hash matches the receipt (both sides, R6-F1 POST residual).
        if (obs.acceptedIntentHash != context.applyReceiptIntentHash) return false

        // §6.4.1 / KB-8: provider coordinates are a structural predicate on the Auto side. Non-finite
        // or out-of-range values contradict the claimed verified observation and fail closed. The
        // target and distance comparison remain exclusively inside Qianwangyou.
        if (!isFiniteGeo(obs.effectiveLat!!, obs.effectiveLng!!)) return false

        return true
    }

    /** Finite AND within valid geographic ranges (lat ∈ [-90,90], lng ∈ [-180,180]) — Sol GREEN-review P1-3. */
    private fun isFiniteGeo(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    companion object {
        /** §8.6.2 VERIFIED_NEW_COMPLETION wire code — the only wire that may mint. */
        const val WIRE_VERIFIED_NEW_COMPLETION = 1

        /** §6.4.2 RUN-phase evidence floor: completedAt − runningConfirmedAt must be at least this. */
        const val MIN_RUNNING_EVIDENCE_MS = 10_000L

        // §6.4.1 frozen wire-value strings (contract enum names; the adapter maps wire Int → name).
        const val COVERAGE_FULL = "FULL"
        const val LEVEL_SYSTEM_MOCK_INDEPENDENTLY_VERIFIED = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED"
        const val DELIVERY_SYSTEM_MOCK = "SYSTEM_MOCK"
        const val SCHEDULE_ALLOWED_NOW = "ALLOWED_NOW"
    }
}

enum class TrustDecision { PASS, FAIL }
