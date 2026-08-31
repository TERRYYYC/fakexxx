package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.DurableProviderPrincipalPreflight
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ProviderExecutorAcquisition
import com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecordedReceipt
import com.example.cellrebelauto.recovery.RecordedReleaseReceipt
import com.example.cellrebelauto.recovery.RecoveryCheckpoint
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.RoomDurableProviderPrincipalPreflight
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.recovery.TestOnlyRoomDurableProviderPrincipalPreflight
import com.example.cellrebelauto.recovery.bindRegistryIssuedProductionExecutor
import com.example.cellrebelauto.recovery.requireReadyRegistryIssuedExecutor
import com.example.cellrebelauto.recovery.requireReadyRegistryIssuedSignerDigest

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
    internal fun recoveryCoordinator(backend: APlusBackend): RecoveryCoordinator = RecoveryCoordinator(
        backend.executor,
        backend.recoveryLog,
        backend.observeIntent,
        backend.receiptRevision,
        backend.trustedQuota,
        backend.providerPrincipalPreflight,
        backend.productionProviderAcquisition,
        backend.providerSignerDigest,
    )

    /**
     * Wire the A+ evidence source (pre/post observation + classified completion evidence) from the
     * backend. Qianwangyou exclusively owns target coordinates and distance validation (KB-8);
     * Auto receives only provider-reported effective coordinates for structural/audit checks. The
     * locally-recomputed intent hash is assembled by the engine from persisted owner identity (INV-23).
     */
    internal fun completionEvidenceSource(backend: APlusBackend): APlusEvidenceSource = backend.evidenceSource

    /**
     * The SINGLE composition point [AutomationService] uses: a backend → the engine's two A+ seams
     * (coordinator + evidence source). Tests drive through this SAME function (with a FakeBackend or the
     * shipped [productionBackend]), so a Service-disconnect bad impl cannot green while production is
     * wired differently (Sol round-11 P1-1: the Service-used composition oracle).
     */
    internal fun engineAplusParams(backend: APlusBackend): Pair<RecoveryCoordinator, APlusEvidenceSource> =
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
    internal fun productionBackend(
        context: android.content.Context,
        db: com.example.cellrebelauto.db.AppDatabase,
        providerAcquisition: ProviderExecutorAcquisition,
        // R44 F1: resolves the provider's CURRENT signer for the §6.5.3 gate. The default is the
        // PackageManager resolver; tests inject a fake.
        providerSignerDigest: (applicationId: String) -> String? = {
            com.example.cellrebelauto.environment.ProviderTrustGate.packageManagerSignerDigest(
                context.packageManager, it
            )
        },
        // R45 (Sol R45 P1-1): the attempt validity window width — the SAME config value the engine
        // uses to build the apply intent (startedAt → startedAt + testTimeoutMs). The evidence
        // source must recompute the intent from the IDENTICAL durable inputs; before this seam the
        // production observe recomputed with (plan.importedAt → Long.MAX_VALUE), so the real Binder
        // validator rejected every production observation (engine apply digest ≠ observe digest).
        attemptValidityTimeoutMs: Long,
    ): APlusBackend = buildBackend(
        context = context,
        db = db,
        providerExecutor = providerAcquisition.requireReadyRegistryIssuedExecutor(),
        productionProviderAcquisition = providerAcquisition,
        expectedProviderSignerDigest =
            providerAcquisition.requireReadyRegistryIssuedSignerDigest(),
        providerSignerDigest = providerSignerDigest,
        attemptValidityTimeoutMs = attemptValidityTimeoutMs,
    )

    /**
     * Explicit fixture seam for production-adapter tests. A claimed target can exercise trust/Room
     * behavior, but carries no registry provenance and is always rejected by productionEngine.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun testOnlyBackend(
        context: android.content.Context,
        db: com.example.cellrebelauto.db.AppDatabase,
        providerExecutor: ProviderScopedExternalApplyExecutor,
        providerSignerDigest: (applicationId: String) -> String? = {
            com.example.cellrebelauto.environment.ProviderTrustGate.packageManagerSignerDigest(
                context.packageManager, it
            )
        },
        attemptValidityTimeoutMs: Long,
    ): APlusBackend = buildBackend(
        context = context,
        db = db,
        providerExecutor = providerExecutor,
        productionProviderAcquisition = null,
        expectedProviderSignerDigest = null,
        providerSignerDigest = providerSignerDigest,
        attemptValidityTimeoutMs = attemptValidityTimeoutMs,
    )

    private fun buildBackend(
        context: android.content.Context,
        db: com.example.cellrebelauto.db.AppDatabase,
        providerExecutor: ProviderScopedExternalApplyExecutor,
        productionProviderAcquisition: ProviderExecutorAcquisition?,
        expectedProviderSignerDigest: String?,
        providerSignerDigest: (applicationId: String) -> String?,
        attemptValidityTimeoutMs: Long,
    ): APlusBackend {
        val providerApplicationId =
            ProviderPrincipal.requireKnownApplicationId(
                providerExecutor.targetApplicationId
            )
        val rawExecutor: com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor =
            providerExecutor
        // R45 (Sol R45 P1-2): the §6.5.3 reverse-authorization gate guards the ENTIRE journey
        // surface — discover/preflight/apply/observe/completeAndAdvance/release — not just the
        // post-apply observation. Before this decorator the gate ran only inside the evidence
        // source's observeLive, so an unapproved provider could pass preflight and APPLY (change
        // the device environment) before any trust check executed. Every method fail-closes the
        // same way the underlying executor does (null / no-lease ApplyOutcome) when the current
        // signer is not an operator-approved active principal.
        val trustGate = com.example.cellrebelauto.environment.ProviderTrustGate(
            com.example.cellrebelauto.environment.ProviderTrustStore(db.providerPairingDao()),
            providerSignerDigest
        )
        val trustGatedExecutor: com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor =
            object : com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor {
            override val targetApplicationId: String = providerApplicationId

            private fun trusted(): Boolean =
                kotlinx.coroutines.runBlocking {
                    expectedProviderSignerDigest?.let {
                        trustGate.isCurrentSignerTrusted(providerApplicationId, it)
                    } ?: trustGate.isCurrentSignerTrusted(providerApplicationId)
                }

            override fun apply(attemptId: Long, intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): com.example.cellrebelauto.recovery.ApplyOutcome =
                if (trusted()) rawExecutor.apply(attemptId, intent, idempotencyKey, requestDigest, now)
                else com.example.cellrebelauto.recovery.ApplyOutcome("PROVIDER_SIGNER_UNTRUSTED", providerHadAlreadyApplied = false)

            // Current trust is only a Boolean; it cannot distinguish an operator revoke from a
            // package signer rotation. Sending an old lease to an unapproved replacement signer
            // would accept cleanup proof from the wrong principal. Until durable signer/revocation
            // provenance exists, every untrusted release is therefore manual-recovery fail-closed.
            override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): com.example.cellrebelauto.recovery.ApplyOutcome =
                if (trusted()) rawExecutor.release(attemptId, idempotencyKey, leaseId, releaseDigest, now)
                else com.example.cellrebelauto.recovery.ApplyOutcome("PROVIDER_SIGNER_UNTRUSTED", providerHadAlreadyApplied = false)

            override fun discover(): io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1? =
                if (trusted()) rawExecutor.discover() else null

            override fun preflight(intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1? =
                if (trusted()) rawExecutor.preflight(intent, idempotencyKey, requestDigest) else null

            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1? =
                if (trusted()) rawExecutor.observe(leaseId, operationId, expectedIntentHash) else null

            override fun completeAndAdvance(request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1, expectedIntentHash: String): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1? =
                if (trusted()) rawExecutor.completeAndAdvance(request, expectedIntentHash) else null
        }
        val binderExecutor = productionProviderAcquisition
            ?.bindRegistryIssuedProductionExecutor(trustGatedExecutor)
            ?: trustGatedExecutor
        val roomLog = com.example.cellrebelauto.recovery.RoomDurableRecoveryLog(
            db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao(),
            expectedProviderSignerDigest,
        )
        val durablePrincipalPreflight: DurableProviderPrincipalPreflight =
            expectedProviderSignerDigest?.let {
                RoomDurableProviderPrincipalPreflight(db, it)
            } ?: TestOnlyRoomDurableProviderPrincipalPreflight(db)
        // Single storage authority for immutable PRE/POST carriers. The source reads durable-first
        // and otherwise returns a live candidate; AutomationEngine alone commits carrier + owner
        // phase (or the complete POST decision bundle) through PlanRepository.
        val durableEvidenceRepository = com.example.cellrebelauto.repository.PlanRepository(db)
        return object : APlusBackend {
            override val executor: ExternalApplyExecutor = binderExecutor
            override val recoveryLog: DurableRecoveryLog = roomLog
            override val productionProviderAcquisition: ProviderExecutorAcquisition? =
                productionProviderAcquisition
            override val providerSignerDigest: String? = expectedProviderSignerDigest
            override val providerPrincipalPreflight: DurableProviderPrincipalPreflight =
                durablePrincipalPreflight
            // Schedule-gate readers (§5 boundary): Room-backed projections. The observe/revision
            // readers consult the durable observation/receipt carriers; the quota reader counts
            // trusted entries per task. Each is identity-keyed (R5-F2).
            override val observeIntent: ObserveIntentAcquirer = ObserveIntentAcquirer { attemptId ->
                kotlinx.coroutines.runBlocking {
                    db.durableObservationDao().forAttemptPhase(attemptId, "PRE") != null
                }
            }
            override val receiptRevision: ReceiptRevisionAcquirer = ReceiptRevisionAcquirer { idempotencyKey, _ ->
                kotlinx.coroutines.runBlocking {
                    db.operationReceiptDao().byKey(idempotencyKey)?.providerApplicationId ==
                        providerApplicationId &&
                        (expectedProviderSignerDigest == null ||
                            db.operationReceiptDao().byKey(idempotencyKey)?.providerSignerDigest ==
                            expectedProviderSignerDigest)
                }
            }
            override val trustedQuota: TrustedQuotaAcquirer = TrustedQuotaAcquirer { attemptId ->
                kotlinx.coroutines.runBlocking {
                    val attempt = db.testAttemptDao().getAttemptById(attemptId)
                    attempt != null &&
                        attempt.providerApplicationId == providerApplicationId &&
                        (expectedProviderSignerDigest == null ||
                            attempt.providerSignerDigest == expectedProviderSignerDigest) &&
                        attempt.endedAt == null &&
                        db.trustedQuotaDao().trustedCountForTask(attempt.taskId) < attempt.let {
                            db.locationTaskDao().getTaskById(it.taskId)?.requiredSuccesses ?: 0
                        }
                }
            }
            override val evidenceSource: APlusEvidenceSource = object : APlusEvidenceSource {
                // R44 (Sol GREEN-review-3 F1): the production evidence source is a LIVE Binder
                // collector with durable replay. A fresh attempt has NO durable observation, so
                // executor.observe() supplies the live snapshot; AutomationEngine then commits the
                // carrier and §8.1 owner phase in ONE PlanRepository transaction. The §6.5.3
                // reverse-authorization gate runs FIRST: an untrusted provider's artifacts never
                // enter the trust path.
                val trustGate = com.example.cellrebelauto.environment.ProviderTrustGate(
                    com.example.cellrebelauto.environment.ProviderTrustStore(db.providerPairingDao()),
                    providerSignerDigest
                )

                suspend fun observeLive(attemptId: Long, runSessionId: Long):
                    com.example.cellrebelauto.environment.ObservationSnapshot? {
                    if (durablePrincipalPreflight.attemptFailure(
                            attemptId = attemptId,
                            executorApplicationId = providerApplicationId,
                            requireApplyReceipt = true,
                        ) != null
                    ) return null
                    // §6.5.3 gate FIRST: untrusted current signer ⇒ no artifacts enter the trust path.
                    val trusted = expectedProviderSignerDigest?.let {
                        trustGate.isCurrentSignerTrusted(providerApplicationId, it)
                    } ?: trustGate.isCurrentSignerTrusted(providerApplicationId)
                    if (!trusted) return null
                    // The observe tuple: lease (durable receipt first, attempt owner fallback),
                    // operationId (durable receipt), expected hash (owner recompute).
                    val receipt = db.operationReceiptDao().byKey(
                        com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(attemptId)
                    )
                    if (receipt?.providerApplicationId != providerApplicationId ||
                        (expectedProviderSignerDigest != null &&
                            receipt.providerSignerDigest != expectedProviderSignerDigest)
                    ) return null
                    val leaseId = receipt?.leaseId ?: db.testAttemptDao().getAplusLeaseId(attemptId) ?: return null
                    val operationId = receipt?.operationId ?: return null
                    // The owner recompute needs the full durable identity: attempt → task → plan.
                    val attempt = db.testAttemptDao().getAttemptById(attemptId) ?: return null
                    if (attempt.providerApplicationId != providerApplicationId ||
                        (expectedProviderSignerDigest != null &&
                            attempt.providerSignerDigest != expectedProviderSignerDigest)
                    ) return null
                    val task = db.locationTaskDao().getTaskById(attempt.taskId) ?: return null
                    val plan = db.planDao().getPlanById(task.planId) ?: return null
                    if (plan.providerApplicationId != providerApplicationId) return null
                    // R45 (Sol R45 P1-1): the intent window MUST be the SAME durable inputs the
                    // engine used for the apply — (attempt.startedAt → startedAt + testTimeoutMs).
                    // The previous recompute used (plan.importedAt → Long.MAX_VALUE), so the digest
                    // NEVER attested the intent Auto actually digested: a real Binder provider's
                    // validator rejects the observation (hash mismatch) and every production A+
                    // attempt paused at pre-observe. The recovery path already recomputed with the
                    // attempt window (INV-23); the live path now does too.
                    // F12: scheduleRef comes from the persisted anchor, not task.id.
                    val anchorScheduleRef = attempt.aplusAnchorScheduleId ?: return null
                    val expectedHash = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
                        .requestDigest(
                            com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.intent(
                                runSessionId, attemptId, plan.id, anchorScheduleRef,
                                notBeforeEpochMs = attempt.startedAt,
                                deadlineEpochMs = attempt.startedAt + attemptValidityTimeoutMs
                            )
                        )
                    val wire = binderExecutor.observe(leaseId, operationId, expectedHash) ?: return null
                    return com.example.cellrebelauto.environment.ObservationWireAdapter.toSnapshot(wire)
                }

                override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
                    kotlinx.coroutines.runBlocking {
                        // Durable first (crash replay); otherwise return a live candidate to the
                        // engine-owned carrier+phase transaction.
                        durableEvidenceRepository.getObservationSnapshot(attemptId, "PRE")
                            ?: observeLive(attemptId, runSessionId)
                    }
                override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
                    kotlinx.coroutines.runBlocking {
                        durableEvidenceRepository.getObservationSnapshot(attemptId, "POST")
                            ?: observeLive(attemptId, runSessionId)
                    }
                override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? {
                    // R44 F1: completion evidence is ASSEMBLED from the durable carriers the normal
                    // path persisted at their phase boundaries — the owner execution row (written at
                    // COMPLETION_OBSERVED) + the verbatim apply receipt. A missing carrier = fail-closed.
                    if (durablePrincipalPreflight.attemptFailure(
                            attemptId = attemptId,
                            executorApplicationId = providerApplicationId,
                            requireApplyReceipt = true,
                        ) != null
                    ) return null
                    return kotlinx.coroutines.runBlocking {
                        val trusted = expectedProviderSignerDigest?.let {
                            trustGate.isCurrentSignerTrusted(providerApplicationId, it)
                        } ?: trustGate.isCurrentSignerTrusted(providerApplicationId)
                        if (!trusted) return@runBlocking null
                        val ownerExecId = db.testAttemptDao().getCurrentExecutionId(attemptId) ?: return@runBlocking null
                        val exec = db.attemptExecutionDao().byExecutionId(ownerExecId) ?: return@runBlocking null
                        if (exec.attemptId != attemptId) return@runBlocking null
                        val receipt = db.operationReceiptDao().byKey(
                            com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(attemptId)
                        ) ?: return@runBlocking null
                        val attempt = db.testAttemptDao().getAttemptById(attemptId)
                            ?: return@runBlocking null
                        if (attempt.providerApplicationId != providerApplicationId ||
                            receipt.providerApplicationId != providerApplicationId ||
                            (expectedProviderSignerDigest != null &&
                                (attempt.providerSignerDigest != expectedProviderSignerDigest ||
                                    receipt.providerSignerDigest != expectedProviderSignerDigest))
                        ) return@runBlocking null
                        APlusCompletionEvidence(
                            execution = exec,
                            completionEvidenceWire = exec.completionEvidenceWire,
                            applyReceiptIntentHash = receipt.acceptedIntentHash ?: return@runBlocking null,
                            applyReceiptLease = receipt.leaseId ?: return@runBlocking null
                        )
                    }
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

        // R44 (Sol GREEN-review-3 F1): the journey surface fail-closes too.
        override fun discover(): io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1? = null
        override fun preflight(
            intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
            idempotencyKey: String,
            requestDigest: String
        ): io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1? = null
        override fun observe(
            leaseId: String,
            operationId: String,
            expectedIntentHash: String
        ): io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1? = null
        override fun completeAndAdvance(
            request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
            expectedIntentHash: String
        ): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1? = null
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
            verificationLevelWire: Int?,
            providerApplicationId: String?,
        ): RecordedReceipt? = null

        override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? = null
        override fun recordCheckpoint(
            attemptId: Long,
            lastDurableStage: String,
            receiptKey: String?,
            now: Long,
            providerApplicationId: String?,
        ): RecoveryCheckpoint? = null
        override fun releaseReceiptFor(
            leaseId: String,
            providerApplicationId: String?,
        ): RecordedReleaseReceipt? = null
        override fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt? = null
        override fun recordReleaseReceipt(
            idempotencyKey: String,
            leaseId: String,
            releaseDigest: String,
            outcome: String,
            now: Long,
            providerApplicationId: String?,
        ): RecordedReleaseReceipt? = null
    }

    /** Fail-closed skeleton evidence source — no observation, no completion evidence. */
    private object SkeletonEvidenceSource : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = null
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = null
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? = null
    }
}
