package com.example.cellrebelauto.recovery

import androidx.room.withTransaction
import com.example.cellrebelauto.automation.ProviderPrincipal
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderSignerDigest
import kotlinx.coroutines.runBlocking

/**
 * Read-only resolver for the durable provider ownership boundary. It never selects or stores a
 * provider identity; the only identity input is the already-scoped executor target. The Room
 * implementation joins that target to the canonical plan, attempt, and proof rows before any
 * external journey action is allowed.
 */
internal sealed interface DurableProviderPrincipalPreflight {
    fun planFailure(planId: Long, executorApplicationId: String): String?

    fun attemptFailure(
        attemptId: Long,
        executorApplicationId: String,
        expectedLeaseId: String? = null,
        requireApplyReceipt: Boolean = false,
        applyReceiptKey: String = APlusOperationIdentity.applyIdempotencyKey(attemptId),
    ): String?

    companion object {
        /** Explicit compatibility seam for coordinator-only unit tests; production factory rejects it. */
        val TEST_ONLY_UNCHECKED: DurableProviderPrincipalPreflight =
            TestOnlyUncheckedProviderPrincipalPreflight
    }
}

private object TestOnlyUncheckedProviderPrincipalPreflight : DurableProviderPrincipalPreflight {
    override fun planFailure(planId: Long, executorApplicationId: String): String? = null

    override fun attemptFailure(
        attemptId: Long,
        executorApplicationId: String,
        expectedLeaseId: String?,
        requireApplyReceipt: Boolean,
        applyReceiptKey: String,
    ): String? = null
}

/** Production blocking adapter over the same short Room transactions used by the recovery log. */
internal class RoomDurableProviderPrincipalPreflight(
    private val db: AppDatabase,
    expectedProviderSignerDigest: String,
) : DurableProviderPrincipalPreflight {
    val providerSignerDigest: String =
        ProviderSignerDigest.requireCanonical(expectedProviderSignerDigest)

    override fun planFailure(planId: Long, executorApplicationId: String): String? = runBlocking {
        db.withTransaction {
            planProviderPrincipalFailureInTransaction(db, planId, executorApplicationId)
        }
    }

    override fun attemptFailure(
        attemptId: Long,
        executorApplicationId: String,
        expectedLeaseId: String?,
        requireApplyReceipt: Boolean,
        applyReceiptKey: String,
    ): String? = runBlocking {
        db.withTransaction {
            attemptProviderPrincipalFailureInTransaction(
                db = db,
                attemptId = attemptId,
                executorApplicationId = executorApplicationId,
                expectedLeaseId = expectedLeaseId,
                requireApplyReceipt = requireApplyReceipt,
                applyReceiptKey = applyReceiptKey,
                executorSignerDigest = providerSignerDigest,
            )
        }
    }
}

/** Explicit application-id-only Room fixture; production factory never accepts this type. */
internal class TestOnlyRoomDurableProviderPrincipalPreflight(
    private val db: AppDatabase,
) : DurableProviderPrincipalPreflight {
    override fun planFailure(planId: Long, executorApplicationId: String): String? = runBlocking {
        db.withTransaction {
            planProviderPrincipalFailureInTransaction(db, planId, executorApplicationId)
        }
    }

    override fun attemptFailure(
        attemptId: Long,
        executorApplicationId: String,
        expectedLeaseId: String?,
        requireApplyReceipt: Boolean,
        applyReceiptKey: String,
    ): String? = runBlocking {
        db.withTransaction {
            attemptProviderPrincipalFailureInTransaction(
                db = db,
                attemptId = attemptId,
                executorApplicationId = executorApplicationId,
                expectedLeaseId = expectedLeaseId,
                requireApplyReceipt = requireApplyReceipt,
                applyReceiptKey = applyReceiptKey,
                executorSignerDigest = null,
            )
        }
    }
}

internal suspend fun planProviderPrincipalFailureInTransaction(
    db: AppDatabase,
    planId: Long,
    executorApplicationId: String,
): String? {
    if (!ProviderPrincipal.isKnownApplicationId(executorApplicationId)) {
        return "PROVIDER_PRINCIPAL_UNKNOWN"
    }
    val plan = db.planDao().getPlanById(planId) ?: return "PROVIDER_PRINCIPAL_UNKNOWN"
    val planApplicationId = plan.providerApplicationId
    if (!ProviderPrincipal.isKnownApplicationId(planApplicationId)) {
        return "PROVIDER_PRINCIPAL_UNKNOWN"
    }
    return if (planApplicationId == executorApplicationId) null else "PROVIDER_PRINCIPAL_CONFLICT"
}

/** Classifies signer ownership across the complete, deduplicated release-row set. */
private fun releaseReceiptSignerFailure(
    releaseRows: List<ReleaseReceiptRow>,
    expectedSignerDigest: String?,
): String? {
    val expectedSigner = expectedSignerDigest ?: return null // Explicit test-only P seam.
    val recordedSigners = releaseRows.map {
        ProviderSignerDigest.normalizeOrNull(it.providerSignerDigest)
    }
    if (recordedSigners.any { it == null }) {
        return PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE
    }
    if (recordedSigners.any { it != expectedSigner }) {
        return PROVIDER_SIGNER_OWNER_CONFLICT_FAILURE
    }
    return null
}

/**
 * One-transaction owner/proof join. Existing target-P proof rows can never vote a null/foreign
 * plan or attempt owner back into validity, and caller-supplied non-canonical operation keys are
 * rejected before they can alias another attempt's proof.
 */
internal suspend fun attemptProviderPrincipalFailureInTransaction(
    db: AppDatabase,
    attemptId: Long,
    executorApplicationId: String,
    expectedLeaseId: String?,
    requireApplyReceipt: Boolean,
    applyReceiptKey: String,
    executorSignerDigest: String?,
): String? {
    if (!ProviderPrincipal.isKnownApplicationId(executorApplicationId)) {
        return "PROVIDER_PRINCIPAL_UNKNOWN"
    }
    val canonicalApplyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    if (applyReceiptKey != canonicalApplyKey) return "PROVIDER_PRINCIPAL_CONFLICT"

    val attempt = db.testAttemptDao().getAttemptById(attemptId)
        ?: return "PROVIDER_PRINCIPAL_UNKNOWN"
    val attemptApplicationId = attempt.providerApplicationId
    if (!ProviderPrincipal.isKnownApplicationId(attemptApplicationId)) {
        return "PROVIDER_PRINCIPAL_UNKNOWN"
    }
    if (attemptApplicationId != executorApplicationId) return "PROVIDER_PRINCIPAL_CONFLICT"

    val expectedSigner = executorSignerDigest?.let {
        ProviderSignerDigest.normalizeOrNull(it)
            ?: return PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE
    }
    fun signerFailure(recordedSignerDigest: String?): String? {
        val expected = expectedSigner ?: return null // Explicit test-only P seam.
        val recorded = ProviderSignerDigest.normalizeOrNull(recordedSignerDigest)
            ?: return PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE
        return if (recorded == expected) null else PROVIDER_SIGNER_OWNER_CONFLICT_FAILURE
    }
    signerFailure(attempt.providerSignerDigest)?.let { return it }

    val task = db.locationTaskDao().getTaskById(attempt.taskId)
        ?: return "PROVIDER_PRINCIPAL_UNKNOWN"
    planProviderPrincipalFailureInTransaction(
        db,
        task.planId,
        executorApplicationId,
    )?.let { return it }

    if (expectedSigner != null &&
        db.providerPairingDao().activeFor(executorApplicationId, expectedSigner) == null
    ) {
        return PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE
    }

    val durableLeaseId = attempt.aplusLeaseId
    if (expectedLeaseId != null && durableLeaseId != expectedLeaseId) {
        return "PROVIDER_PRINCIPAL_CONFLICT"
    }
    val ownerLease = expectedLeaseId ?: durableLeaseId
    val isLaterPhase = attempt.aplusState !in setOf(null, "CREATED", "APPLY_PENDING")
    if (isLaterPhase && durableLeaseId == null) return "PROVIDER_PRINCIPAL_UNKNOWN"
    val laterPhaseRequiresReceipt = durableLeaseId != null || isLaterPhase
    val applyReceipt = db.operationReceiptDao().byKey(canonicalApplyKey)
    if (applyReceipt == null) {
        if (requireApplyReceipt || laterPhaseRequiresReceipt) {
            return "PROVIDER_PRINCIPAL_UNKNOWN"
        }
    } else {
        if (!ProviderPrincipal.isKnownApplicationId(applyReceipt.providerApplicationId)) {
            return "PROVIDER_PRINCIPAL_UNKNOWN"
        }
        if (applyReceipt.providerApplicationId != executorApplicationId) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
        signerFailure(applyReceipt.providerSignerDigest)?.let { return it }
        if (ownerLease != null && applyReceipt.leaseId != ownerLease) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
    }

    db.recoveryCheckpointRoomDao().byAttempt(attemptId)?.let { checkpoint ->
        if (!ProviderPrincipal.isKnownApplicationId(checkpoint.providerApplicationId)) {
            return "PROVIDER_PRINCIPAL_UNKNOWN"
        }
        if (checkpoint.providerApplicationId != executorApplicationId) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
        signerFailure(checkpoint.providerSignerDigest)?.let { return it }
    }

    val canonicalReleaseKey = APlusOperationIdentity.releaseIdempotencyKey(attemptId)
    val releaseReceipt = db.releaseReceiptDao().byKey(canonicalReleaseKey)
    val sameOwnerLease = if (ownerLease == null) {
        emptyList()
    } else {
        db.releaseReceiptDao().allByLease(ownerLease, executorApplicationId)
    }
    val canonicalSameProvider = releaseReceipt?.takeIf {
        it.providerApplicationId == executorApplicationId
    }
    val completeOwnerReleaseRows =
        (sameOwnerLease + listOfNotNull(canonicalSameProvider)).distinctBy { it.idempotencyKey }
    releaseReceiptSignerFailure(completeOwnerReleaseRows, expectedSigner)?.let { return it }

    releaseReceipt?.let {
        if (!ProviderPrincipal.isKnownApplicationId(releaseReceipt.providerApplicationId)) {
            return "PROVIDER_PRINCIPAL_UNKNOWN"
        }
        if (releaseReceipt.providerApplicationId != executorApplicationId) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
        if (ownerLease == null || releaseReceipt.leaseId != ownerLease) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
        if (releaseReceipt.releaseDigest != APlusOperationIdentity.releaseDigest(ownerLease) ||
            releaseReceipt.resultOutcome != "RELEASED"
        ) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
    }
    if (ownerLease != null) {
        // The historical schema keys releases by operation key, while ownership is (P, leaseId).
        // Inspect both indexes inside this same transaction: a wrong-key row for the same owner
        // lease is a conflict even when the canonical-key lookup above is empty.
        if (completeOwnerReleaseRows.any {
                it.idempotencyKey != canonicalReleaseKey ||
                    it.leaseId != ownerLease ||
                    it.releaseDigest != APlusOperationIdentity.releaseDigest(ownerLease) ||
                    it.resultOutcome != "RELEASED"
            } ||
            sameOwnerLease.size > 1 ||
            (releaseReceipt != null && completeOwnerReleaseRows.singleOrNull() != releaseReceipt)
        ) {
            return "PROVIDER_PRINCIPAL_CONFLICT"
        }
    }
    return null
}
