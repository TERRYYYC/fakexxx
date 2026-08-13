package com.example.cellrebelauto.automation.aplus

import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer

/**
 * The contract-/GREEN-bound seams the A+ lifecycle needs from outside Auto's own state (Issue #5
 * R8, Sol round-7 P1-1). Bundled so the composition root
 * ([com.example.cellrebelauto.automation.APlusComposition]) wires the SAME bundle into the engine in
 * production and in tests — one single composition point, no hand-wired alternate path.
 *
 * Everything in here is contract-bound (provider RPC, live observation/revision) or GREEN-bound
 * (the Room receipt binding); Auto never invents their contents locally. Pre-freeze, production
 * ships `backend = null` (pure legacy engine behavior); tests inject fakes through the composition
 * root.
 *
 * # A+ 后端 seam 束：contract/GREEN 绑定的外部依赖单一组合点；pre-freeze 生产 = null（纯 legacy）
 */
interface APlusBackend {
    /** The external provider operation call (apply + release), idempotent at the provider (§6.3.4). */
    val executor: ExternalApplyExecutor

    /** Auto-local durable receipt/checkpoint store (§7.1 RecoveryCheckpoint owner; Room binding is GREEN). */
    val recoveryLog: DurableRecoveryLog

    val observeIntent: ObserveIntentAcquirer
    val receiptRevision: ReceiptRevisionAcquirer
    val trustedQuota: TrustedQuotaAcquirer

    /** Observation / classification acquisition (§6.4 evidence). */
    val evidenceSource: APlusEvidenceSource
}

/**
 * What the backend can honestly supply for one attempt: pre/post observations and the classified
 * completion evidence + apply-receipt fields (contract DTO projections). It deliberately CANNOT
 * supply the target coordinates or the locally-recomputed intent hash — those are assembled from the
 * PERSISTED attempt intent (INV-23; Sol round-7 P1-2: a caller-self-consistent context would mint
 * evidence for the wrong address).
 *
 * # A+ 证据获取 seam：只供观察/分类/回执字段；目标坐标与本地重算 hash 永远来自持久 intent
 */
interface APlusEvidenceSource {
    /** The §6.4 pre-observation for [attemptId], or null when observation is unavailable. */
    suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot?

    /** The §6.4 post-observation for [attemptId], or null when observation is unavailable. */
    suspend fun acquirePostObservation(attemptId: Long): ObservationSnapshot?

    /** The classified completion evidence + apply-receipt fields for [attemptId] (§8.6/§6.3). */
    suspend fun acquireCompletionEvidence(attemptId: Long): APlusCompletionEvidence?
}

/**
 * Backend-supplied completion artifacts (§8.6 classified execution detail + ApplyReceiptV1
 * projections). Contains NO target coordinates and NO locally-recomputed intent hash by design.
 */
data class APlusCompletionEvidence(
    val execution: CellRebelExecution,
    val completionEvidenceWire: Int,
    val applyReceiptIntentHash: String,
    val applyReceiptLease: String
)
