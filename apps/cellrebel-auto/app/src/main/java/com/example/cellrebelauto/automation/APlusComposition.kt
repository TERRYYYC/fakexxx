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
     * The SINGLE composition point [AutomationService] uses: a backend → the engine's two A+ seams
     * (coordinator + evidence source). Tests drive through this SAME function (with a FakeBackend or the
     * shipped [productionBackend]), so a Service-disconnect bad impl cannot green while production is
     * wired differently (Sol round-11 P1-1: the Service-used composition oracle).
     */
    fun engineAplusParams(backend: APlusBackend): Pair<RecoveryCoordinator, APlusEvidenceSource> =
        recoveryCoordinator(backend) to completionEvidenceSource(backend)

    /**
     * The production backend (R43 GREEN, Sol GREEN-review P1-1): REAL adapters over the frozen
     * contract v1 —
     *  - executor: [com.example.cellrebelauto.recovery.BinderExternalApplyExecutor] over the
     *    IEnvironmentControlV1 Binder (fail-closed on transport/non-APPLY/unknown-wire);
     *  - recoveryLog: [com.example.cellrebelauto.recovery.RoomDurableRecoveryLog] over the real
     *    Room operation/recovery/release receipt tables (lease persisted atomically with the apply
     *    receipt, P1-5);
     *  - schedule readers: Room-backed trusted-count / receipt-revision / observe projections.
     * BEFORE binding, the binder executor fail-closes every call (`PROVIDER_NOT_BOUND`, no lease),
     * so constructing the backend without a provider present still pauses safely — the fail-closed
     * property of the old skeleton is preserved by construction, not by stubbing.
     *
     * # 生产 backend（GREEN）：冻结契约 Binder executor + Room receipt store；未绑定自然 fail-closed
     */
    fun productionBackend(
        context: android.content.Context,
        db: com.example.cellrebelauto.db.AppDatabase,
        // R43 F1: the SERVICE-LIFECYCLE executor (bound at AutomationService.onServiceConnected)
        // — reused across runs so the provider connection is real, not per-run constructed-and-
        // never-bound. When null (early construction), a fresh executor is created UNBOUND and
        // fail-closes every call (PROVIDER_NOT_BOUND) — still safe, and the Service re-composes
        // with the bound one on the next run.
        serviceLifecycleExecutor: com.example.cellrebelauto.recovery.BinderExternalApplyExecutor? = null
    ): APlusBackend {
        val binderExecutor = serviceLifecycleExecutor
            ?: com.example.cellrebelauto.recovery.BinderExternalApplyExecutor(context)
        val roomLog = com.example.cellrebelauto.recovery.RoomDurableRecoveryLog(
            db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()
        )
        return object : APlusBackend {
            override val executor: ExternalApplyExecutor = binderExecutor
            override val recoveryLog: DurableRecoveryLog = roomLog
            // Schedule-gate readers (§5 boundary): Room-backed projections. The observe/revision
            // readers consult the durable observation/receipt carriers; the quota reader counts
            // trusted entries per task. Each is identity-keyed (R5-F2).
            override val observeIntent: ObserveIntentAcquirer = ObserveIntentAcquirer { attemptId ->
                kotlinx.coroutines.runBlocking {
                    db.durableObservationDao().forAttemptPhase(attemptId, "PRE") != null
                }
            }
            override val receiptRevision: ReceiptRevisionAcquirer = ReceiptRevisionAcquirer { idempotencyKey, _ ->
                kotlinx.coroutines.runBlocking { db.operationReceiptDao().byKey(idempotencyKey) != null }
            }
            override val trustedQuota: TrustedQuotaAcquirer = TrustedQuotaAcquirer { attemptId ->
                kotlinx.coroutines.runBlocking {
                    val attempt = db.testAttemptDao().getAttemptById(attemptId)
                    attempt != null && attempt.endedAt == null &&
                        db.trustedQuotaDao().trustedCountForTask(attempt.taskId) < attempt.let {
                            db.locationTaskDao().getTaskById(it.taskId)?.requiredSuccesses ?: 0
                        }
                }
            }
            override val evidenceSource: APlusEvidenceSource = object : APlusEvidenceSource {
                // The production evidence source reconstructs §6.4 observations from the DURABLE
                // carriers (the live Binder observation is Phase-D wiring exercised against a real
                // provider; recovery reads durability either way).
                override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
                    kotlinx.coroutines.runBlocking {
                        db.durableObservationDao().forAttemptPhase(attemptId, "PRE")?.let { r ->
                            com.example.cellrebelauto.environment.ObservationSnapshot(
                                leaseId = r.leaseId, acceptedIntentHash = r.acceptedIntentHash,
                                coverage = r.coverage, verificationLevel = r.verificationLevel,
                                deliveryMode = r.deliveryMode, isMock = r.isMock,
                                scheduleDecision = r.scheduleDecision,
                                effectiveLat = r.effectiveLat, effectiveLng = r.effectiveLng,
                                environmentRevision = r.environmentRevision,
                                environmentFingerprint = r.environmentFingerprint,
                                observedAtElapsedRealtimeMs = r.observedAtElapsedRealtimeMs,
                                observedAtEpochMs = r.observedAtEpochMs,
                                continuitySinceElapsedRealtimeMs = r.continuitySinceElapsedRealtimeMs,
                                evidenceRefs = if (r.evidenceRefs.isBlank()) emptyList() else r.evidenceRefs.split(";")
                            )
                        }
                    }
                override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
                    kotlinx.coroutines.runBlocking {
                        db.durableObservationDao().forAttemptPhase(attemptId, "POST")?.let { r ->
                            com.example.cellrebelauto.environment.ObservationSnapshot(
                                leaseId = r.leaseId, acceptedIntentHash = r.acceptedIntentHash,
                                coverage = r.coverage, verificationLevel = r.verificationLevel,
                                deliveryMode = r.deliveryMode, isMock = r.isMock,
                                scheduleDecision = r.scheduleDecision,
                                effectiveLat = r.effectiveLat, effectiveLng = r.effectiveLng,
                                environmentRevision = r.environmentRevision,
                                environmentFingerprint = r.environmentFingerprint,
                                observedAtElapsedRealtimeMs = r.observedAtElapsedRealtimeMs,
                                observedAtEpochMs = r.observedAtEpochMs,
                                continuitySinceElapsedRealtimeMs = r.continuitySinceElapsedRealtimeMs,
                                evidenceRefs = if (r.evidenceRefs.isBlank()) emptyList() else r.evidenceRefs.split(";")
                            )
                        }
                    }
                override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? {
                    // Completion evidence for a live attempt is provider-observed (§8.6) — pre-bind
                    // this is unavailable, fail-closed null (the normal path records UNTRUSTED).
                    return null
                }
            }
        }
    }

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
        override fun apply(
            attemptId: Long,
            intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
            idempotencyKey: String,
            requestDigest: String,
            now: Long
        ): ApplyOutcome =
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
            now: Long,
            leaseId: String?,
            operationId: String?,
            acceptedIntentHash: String?,
            appliedAtEpochMs: Long?,
            environmentRevision: Long?,
            verificationLevelWire: Int?
        ): RecordedReceipt? = null

        override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? = null
        override fun recordCheckpoint(attemptId: Long, lastDurableStage: String, receiptKey: String?, now: Long) {}
        override fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt? = null
        override fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt? = null
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
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = null
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = null
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? = null
    }
}
