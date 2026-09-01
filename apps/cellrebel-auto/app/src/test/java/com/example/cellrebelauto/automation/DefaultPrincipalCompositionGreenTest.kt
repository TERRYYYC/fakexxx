package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GREEN (Terra PR-#65 review P2): the unified selected-principal composition oracle.
 *
 * The cited oracles proved the principal seam only through INJECTED identities:
 * [ProductionEvidenceSourceOracleTest] injects the production principal end to
 * end; [ProviderPrincipalRoutingRedTest] rejects an explicit trust/Binder fork;
 * [EngineTrustedPathRedTest]'s default-backend test is deliberately unbound and
 * asserts a pause. This test exercises the selected-principal adapter used by
 * unit fixtures, while production recovery consumes a registry-issued capability
 * whose P/S identity is read from durable state.
 *
 * The fixture uses the build-selected principal (= bench in this debug test
 * build), a bench pairing with a trusted signer, and an explicitly scoped
 * selected-target executor accepted by the fork guard.
 *
 * KILLING MUTATIONS:
 *  - hardcode `PROVIDER_APPLICATION_ID_PRODUCTION` at the APlusComposition trust/
 *    observe gates → this test FAILS (the bench pairing is never consulted,
 *    trusted observe returns null).
 *  - hardcode `PROVIDER_APPLICATION_ID_BENCH` there instead → this test stays
 *    green but [ProductionEvidenceSourceOracleTest] FAILS (its injected
 *    production pairing stops being consulted). The pair closes both directions.
 *  - let release bypass signer ownership → the post-rotation fail-closed
 *    assertion FAILS.
 *
 * The release-leg routing truth (release variant selects production) is carried
 * by `scripts/check-principal-routing.sh` in the release CI lane — CI runs debug
 * unit tests and only assembles release, so no JVM test can observe it.
 *
 * # 统一 selected principal 组合 oracle：显式 scoped fixture，trusted observe + rotation 后 fail-closed
 */
@RunWith(RobolectricTestRunner::class)
class DefaultPrincipalCompositionGreenTest {

    private lateinit var db: AppDatabase
    private var signerTrusted: Boolean = true
    private val observeCalls = mutableListOf<Triple<String, String, String>>()
    private var observeResult: EnvironmentObservationV1? = null

    /** The fake journey executor: records observe calls, returns the programmed observation. */
    private val fakeExecutor = object : ExternalApplyExecutor {
        override fun apply(
            attemptId: Long,
            intent: EnvironmentIntentV1,
            idempotencyKey: String,
            requestDigest: String,
            now: Long
        ): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-77", operationId = "op-77",
                acceptedIntentHash = expectedHash(), appliedAtEpochMs = 1000L,
                environmentRevision = 7L,
                verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire)
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("RELEASED", false)
        override fun discover(): CapabilitySnapshotV1? = null
        override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? = null
        override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
            observeCalls += Triple(leaseId, operationId, expectedIntentHash)
            return observeResult
        }
        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? = null
    }

    private var seededPlanId: Long = 0L
    private val attemptTimeoutMs = 90_000L

    private fun expectedHash(): String = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
        .requestDigest(
            com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.intent(
                5L, 77L, seededPlanId, "qwy-default-schedule", 600L, 600L + attemptTimeoutMs
            )
        )

    private fun signerDigest(): (String) -> String? = {
        if (signerTrusted) TRUSTED_SIGNER else ROTATED_SIGNER
    }

    /**
     * Test-only selected-principal adapter. Production uses a registry-issued
     * acquisition whose durable P/S is verified before the first provider action.
     */
    private fun defaultBackend() = APlusComposition.testOnlyBackend(
        context = ApplicationProvider.getApplicationContext(),
        db = db,
        providerExecutor = ProviderScopedExternalApplyExecutor.wrap(
            ProviderPrincipal.selected,
            fakeExecutor,
        ),
        providerSignerDigest = signerDigest(),
        attemptValidityTimeoutMs = attemptTimeoutMs,
    )

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        // Same durable fixture shape as ProductionEvidenceSourceOracleTest (plan →
        // task → session → attempt 77 with the apply receipt carrying lease +
        // operationId + intent hash), EXCEPT the approved pairing binds the
        // BUILD-SELECTED principal — never an injected identity.
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "p.csv",
                importedAt = 1000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = ProviderPrincipal.selected,
            ),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        seededPlanId = planId
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = 77L, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = "ENV_APPLIED", aplusLeaseId = "lease-77", currentExecutionId = "exec-77",
                aplusAnchorScheduleId = "qwy-default-schedule",
                providerApplicationId = ProviderPrincipal.selected,
                providerSignerDigest = TRUSTED_SIGNER,
            )
        )
        // Approve the trusted signer UNDER the BUILD-SELECTED principal. The default
        // composition must consult exactly this pairing; a hardcoded production (or
        // bench) identity at the gate finds no approved pairing and fail-closes.
        val approvedId = db.providerPairingDao().insert(
            com.example.cellrebelauto.model.plan.ProviderPairingRecord(
                applicationId = ProviderPrincipal.selected,
                currentSignerDigest = TRUSTED_SIGNER,
                approvedAt = 1000L, revokedAt = null, approvedVersionCode = 1
            )
        )
        org.junit.Assert.assertTrue(approvedId > 0)
        db.operationReceiptDao().insertIfAbsent(
            com.example.cellrebelauto.recovery.OperationReceiptRow(
                idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(77L),
                requestDigest = expectedHash(), resultOutcome = "APPLIED", createdAt = 1000L,
                leaseId = "lease-77", operationId = "op-77", acceptedIntentHash = expectedHash(),
                appliedAtEpochMs = 1000L, environmentRevision = 7L,
                verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                providerApplicationId = ProviderPrincipal.selected,
                providerSignerDigest = TRUSTED_SIGNER,
            )
        )
        observeResult = EnvironmentObservationV1(
            leaseId = "lease-77", acceptedIntentHash = expectedHash(),
            observedAtEpochMs = 900L, observedAtElapsedRealtimeMs = 1000L,
            environmentRevision = 7L, environmentFingerprint = "fp-1",
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = 800L, continuitySinceElapsedRealtimeMs = 500L,
            deliveryModeWire = io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = 39.9, effectiveLongitude = 116.4, isMock = true,
            scheduleDecisionWire = io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = listOf("qwy:store:abc"), scheduleItemId = "task-42", scheduleVersion = 1L
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the selected composition accepts the selected binder target`() {
        // Pin what "selected" means in this debug test build: the single selection
        // IS the bench principal (release selects production — proven by the
        // release-lane routing guard, not observable from debug unit tests).
        assertEquals(
            "this oracle is only meaningful while the debug test build selects bench",
            io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_BENCH,
            ProviderPrincipal.selected
        )
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val selectedBinder = BinderExternalApplyExecutor(app)
        assertEquals(
            "the Binder leg's own default carries the single selection",
            ProviderPrincipal.selected,
            selectedBinder.targetApplicationId
        )
        val backend = APlusComposition.testOnlyBackend(
            context = app,
            db = db,
            providerExecutor = ProviderScopedExternalApplyExecutor.wrap(
                ProviderPrincipal.selected,
                selectedBinder,
            ),
            providerSignerDigest = signerDigest(),
            attemptValidityTimeoutMs = attemptTimeoutMs,
        )
        assertEquals(
            ProviderPrincipal.selected,
            (backend.executor as ProviderScopedExternalApplyExecutor).targetApplicationId,
        )
    }

    @Test
    fun `selected principal walks trusted observe then rotation fails closed`() = runBlocking {
        // ---- trusted window: new work passes, trusted observe SUCCEEDS ----
        val backend = defaultBackend()
        val probeIntent = io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1(
            runId = "5", attemptId = "77", profileRef = "p", scheduleRef = "s",
            requiredVerificationWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = 0L, deadlineEpochMs = 1L
        )
        assertEquals(
            "trusted signer ⇒ apply passes through on the selected principal (killing mutation: hardcoded production ⇒ PROVIDER_SIGNER_UNTRUSTED)",
            "APPLIED",
            backend.executor.apply(77L, probeIntent, "key-default-apply", "digest", 0L).outcome
        )
        val pre = backend.evidenceSource.acquirePreObservation(77L, 5L)
        assertNotNull(
            "trusted observe SUCCEEDS through the selected principal (killing mutation: hardcoded production gate ⇒ null)",
            pre
        )
        assertEquals("exactly one observe call", 1, observeCalls.size)
        assertEquals("the observe tuple binds the durable receipt lease", "lease-77", observeCalls[0].first)
        assertEquals("the expected hash is the owner recompute", expectedHash(), observeCalls[0].third)

        // ---- rotation window (signer moved away from the approved pairing) ----
        signerTrusted = false
        assertNull(
            "post-rotation observe fail-closes on the selected principal",
            backend.evidenceSource.acquirePostObservation(77L, 5L)
        )
        assertEquals("no observe call was consumed post-rotation", 1, observeCalls.size)
        assertEquals(
            "post-rotation NEW trusted work stays gated",
            "PROVIDER_SIGNER_UNTRUSTED",
            backend.executor.apply(77L, probeIntent, "key-default-apply-2", "digest", 0L).outcome
        )
        assertEquals(
            "post-rotation release must fail closed until durable signer ownership is resolved",
            "PROVIDER_SIGNER_UNTRUSTED",
            backend.executor.release(77L, "rel-key-77", "lease-77", "rel-digest", 0L).outcome
        )
    }

    private companion object {
        const val TRUSTED_SIGNER =
            "sha256:a9a089195c68d2adeee23beaa2c3a93b1d4cdf09046e7a9e520b3b166dff3e6a"
        const val ROTATED_SIGNER =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
