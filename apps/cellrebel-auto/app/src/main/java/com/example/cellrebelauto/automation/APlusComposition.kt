package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecordedReceipt
import com.example.cellrebelauto.recovery.RecordedReleaseReceipt
import com.example.cellrebelauto.recovery.RecoveryCheckpoint
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer

/**
 * The A+ production composition root (Issue #5 R8/R9, Sol round-7 P1-1 / round-8 P1-1).
 *
 * WHY THIS EXISTS. Sol's round-7 falsification: the engine constructor seams
 * (`recoveryCoordinator` / `completionTrustContextProvider`) defaulted null in production while the
 * RED tests injected real objects directly — so a fully-implemented-but-disconnected attack greened
 * every test while `AutomationService` still passed null and the engine kept walking the legacy path.
 * The fix is ONE composition point: production and tests both wire the A+ seams through here from an
 * [APlusBackend], so there is no hand-wired alternate path a test can take that production does not
 * also take.
 *
 * Round-8 P1-1 tightened this further: `AutomationService` was still hardcoding `backend = null`, so
 * the disconnect survived (tests green, production legacy). [productionBackend] now returns a NON-NULL
 * fail-closed skeleton bundle — the real adapters remain RED skeletons (their GREEN bodies need the
 * frozen contract/schema), but the WIRING is real: production composes the same non-null coordinator +
 * evidence source and the engine enters the A+ path (fail-closed), never the legacy counter path.
 *
 * # A+ 组合根：生产与测试都从这里用同一 APlusBackend 接线；productionBackend 非 null fail-closed（杜绝生产留 null 走 legacy）
 */
object APlusComposition {

    /**
     * Wire the recovery coordinator from a backend's executor + durable log + three constructor-owned
     * schedule-gate acquirers (§8.2 RECOVERING, §5 boundary).
     */
    fun recoveryCoordinator(backend: APlusBackend): RecoveryCoordinator = RecoveryCoordinator(
        backend.executor,
        backend.recoveryLog,
        backend.observeIntent,
        backend.receiptRevision,
        backend.trustedQuota
    )

    /**
     * Wire the A+ evidence source (pre/post observation + classified completion evidence) from the
     * backend. Target coordinates and the locally-recomputed intent hash are NEVER supplied here —
     * they are assembled by the engine from the persisted attempt intent (INV-23).
     */
    fun completionEvidenceSource(backend: APlusBackend): APlusEvidenceSource = backend.evidenceSource

    /**
     * The production backend — a NON-NULL, fail-closed A+ bundle (Sol round-8 P1-1). Every adapter is
     * a RED skeleton that fails closed (null evidence / null receipts / a no-op fail-closed executor);
     * the GREEN bodies land with the frozen contract (#3) + schema. Because it is non-null, the engine
     * enters the A+ path and PAUSES fail-closed instead of walking the legacy counter path — the
     * production/test disconnect is structurally impossible, and the legacy hold-out is dead in
     * production wiring.
     *
     * # 生产 backend（非 null fail-closed 骨架束）：所有 adapter 骨架 fail-closed；生产引擎走 A+ 路径而非 legacy
     */
    fun productionBackend(): APlusBackend = SkeletonBackend

    private object SkeletonBackend : APlusBackend {
        override val executor: ExternalApplyExecutor = SkeletonExecutor
        override val recoveryLog: DurableRecoveryLog = SkeletonRecoveryLog
        override val observeIntent: ObserveIntentAcquirer = ObserveIntentAcquirer { false }
        override val receiptRevision: ReceiptRevisionAcquirer = ReceiptRevisionAcquirer { _, _ -> false }
        override val trustedQuota: TrustedQuotaAcquirer = TrustedQuotaAcquirer { false }
        override val evidenceSource: APlusEvidenceSource = SkeletonEvidenceSource
    }

    /** Fail-closed skeleton executor — drives no provider, reports no effect. */
    private object SkeletonExecutor : ExternalApplyExecutor {
        override fun apply(attemptId: Long, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("SKELETON_FAIL_CLOSED", providerHadAlreadyApplied = false)

        override fun release(
            attemptId: Long,
            idempotencyKey: String,
            leaseId: String,
            releaseDigest: String,
            now: Long
        ): ApplyOutcome = ApplyOutcome("SKELETON_FAIL_CLOSED", providerHadAlreadyApplied = false)
    }

    /** Fail-closed skeleton recovery log — no receipt, no checkpoint, no release receipt. */
    private object SkeletonRecoveryLog : DurableRecoveryLog {
        override fun receiptFor(idempotencyKey: String): RecordedReceipt? = null
        override fun recordReceipt(
            idempotencyKey: String,
            requestDigest: String,
            outcome: String,
            now: Long
        ): RecordedReceipt? = null

        override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? = null
        override fun recordCheckpoint(attemptId: Long, lastDurableStage: String, receiptKey: String?, now: Long) {}
        override fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt? = null
        override fun recordReleaseReceipt(
            idempotencyKey: String,
            leaseId: String,
            releaseDigest: String,
            outcome: String,
            now: Long
        ): RecordedReleaseReceipt? = null
    }

    /** Fail-closed skeleton evidence source — no observation, no completion evidence. */
    private object SkeletonEvidenceSource : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot? = null
        override suspend fun acquirePostObservation(attemptId: Long): ObservationSnapshot? = null
        override suspend fun acquireCompletionEvidence(attemptId: Long): APlusCompletionEvidence? = null
    }
}
