package com.example.cellrebelauto.automation.aplus

import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.DurableProviderPrincipalPreflight
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ProviderExecutorAcquisition
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
internal interface APlusBackend {
    /** The external provider operation call (apply + release), idempotent at the provider (§6.3.4). */
    val executor: ExternalApplyExecutor

    /** Auto-local durable receipt/checkpoint store (§7.1 RecoveryCheckpoint owner; Room binding is GREEN). */
    val recoveryLog: DurableRecoveryLog

    /** Durable plan/attempt/proof resolver. Production must override the test-only default. */
    val providerPrincipalPreflight: DurableProviderPrincipalPreflight
        get() = DurableProviderPrincipalPreflight.TEST_ONLY_UNCHECKED

    /** Non-null only for the registry-issued, exact-bound production composition. */
    val productionProviderAcquisition: ProviderExecutorAcquisition?
        get() = null

    /** Immutable attempt/lease signer owner; null only for explicit non-production fixtures. */
    val providerSignerDigest: String?
        get() = null

    val observeIntent: ObserveIntentAcquirer
    val receiptRevision: ReceiptRevisionAcquirer
    val trustedQuota: TrustedQuotaAcquirer

    /** Observation / classification acquisition (§6.4 evidence). */
    val evidenceSource: APlusEvidenceSource
}

/**
 * What the backend can honestly supply for one attempt: pre/post observations and the classified
 * completion evidence + apply-receipt fields (contract DTO projections). Qianwangyou exclusively
 * owns target coordinates and distance validation (KB-8); Auto sees only the provider-reported
 * effective coordinates carried by each observation. The backend cannot supply Auto's independently
 * recomputed intent hash — that is assembled from persisted owner identity (INV-23).
 *
 * # A+ 证据获取 seam：千网游独占目标坐标；Auto 只做 provider 生效坐标的结构/审计校验，并从持久 owner 身份重算 hash
 */
interface APlusEvidenceSource {
    /**
     * The §6.4 pre-observation for [attemptId], or null when observation is unavailable. A non-null
     * result is either a durable replay or a live candidate. AutomationEngine owns the transaction
     * that commits the immutable carrier together with its owner phase.
     * R43 GREEN: [runSessionId] is the attempt's REAL owner session — the source recomputes the
     * INV-23 intent hash from the same owner identity the engine uses, so the three-way digest can
     * actually agree.
     */
    suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot?

    /**
     * The §6.4 post-observation for [attemptId], or null when unavailable. As with PRE, a non-null
     * value may be a durable replay or a live candidate for the engine-owned decision transaction.
     */
    suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot?

    /** The classified completion evidence + apply-receipt fields for [attemptId] (§8.6/§6.3). */
    suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence?
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
