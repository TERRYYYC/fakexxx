package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.environment.ProviderTrustRejections
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #10 RED: the engine's discover-failure pause must name the REAL cause.
 *
 * Device truth: after a revoke mis-touch the trust-gated discover returned null and the engine
 * logged only "provider discover failed or protocol incompatible (v1 required)" — the operator
 * had no way to reach "the principal was revoked". The gate records its latest rejection
 * ([ProviderTrustRejections]); the discover pause message must fold that record in, and must
 * NOT invent a gate cause when none was recorded.
 *
 * # discover 暂停文案 oracle：有 gate 拒绝记录 → 并入 typed 原因；无记录 → 保持原文案
 */
@RunWith(RobolectricTestRunner::class)
class EngineDiscoverPauseReasonTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db)
        ProviderTrustRejections.reset()
    }

    @After
    fun tearDown() {
        db.close()
        ProviderTrustRejections.reset()
    }

    private val runner = object : CellRebelRunner {
        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onStartInteraction: suspend () -> Unit,
            onRunningObserved: suspend (Long) -> Unit,
        ): AttemptOutcome = AttemptOutcome.Success(
            webScore = 8.0, videoScore = 7.0,
            runningObservedAt = 0L, startedAt = startedAt, endedAt = startedAt + 1,
        )
    }

    private val gps = object : GpsLocationSetter {
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome = GpsOutcome.Active
    }

    private class NullEvidence : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = null
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = null
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? = null
    }

    private fun backend(executor: ExternalApplyExecutor): APlusBackend = object : APlusBackend {
        override val executor: ExternalApplyExecutor = executor
        override val recoveryLog: DurableRecoveryLog = FakeDurableRecoveryLog()
        override val observeIntent: ObserveIntentAcquirer = ObserveIntentAcquirer { false }
        override val receiptRevision: ReceiptRevisionAcquirer = ReceiptRevisionAcquirer { _, _ -> false }
        override val trustedQuota: TrustedQuotaAcquirer = TrustedQuotaAcquirer { false }
        override val evidenceSource: APlusEvidenceSource = NullEvidence()
    }

    private suspend fun seedPlan(): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "issue10.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1,
            ),
        )
        db.planDao().insertTasks(
            listOf(
                LocationTask(
                    id = 7L, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 1,
                ),
            ),
        )
        return planId
    }

    private suspend fun engineWithDiscoverFailure(planId: Long): AutomationEngine {
        val executor = RecordingExternalApplyExecutor().apply { discoverFixture = null }
        val (coordinator, evidence) = APlusComposition.engineAplusParams(backend(executor))
        return AutomationEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = runner,
            gpsSetter = gps,
            bufferGate = BufferGate(0) { 1000L },
            testTimeoutMs = 90_000L,
            gpsSettleMs = 0L,
            nowMs = { 1000L },
            delayMs = { },
            recoveryCoordinator = coordinator,
            completionEvidenceSource = evidence,
        )
    }

    @Test
    fun `discover failure folds the gate's recorded rejection into the pause message`() = runTest {
        val planId = seedPlan()
        ProviderTrustRejections.record(
            applicationId = "name.caiyao.fakegps.bench",
            signerDigest = "sha256:rotated",
            because = "signer not an approved active principal",
        )

        val engine = engineWithDiscoverFailure(planId)
        engine.run()

        assertEquals(AutomationState.PAUSED, engine.state.value)
        val pauseLine = engine.logs.value.last { it.contains("ERROR: provider discover failed") }
        assertTrue("still names the discover failure: $pauseLine", pauseLine.contains("provider discover failed"))
        assertTrue("names the gate: $pauseLine", pauseLine.contains("trust gate"))
        assertTrue("names the provider: $pauseLine", pauseLine.contains("name.caiyao.fakegps.bench"))
        assertTrue(
            "names the typed cause: $pauseLine",
            pauseLine.contains("signer not an approved active principal"),
        )
    }

    @Test
    fun `discover failure without a recorded rejection keeps the legacy bare message`() = runTest {
        val planId = seedPlan()

        val engine = engineWithDiscoverFailure(planId)
        engine.run()

        assertEquals(AutomationState.PAUSED, engine.state.value)
        val pauseLine = engine.logs.value.last { it.contains("ERROR: provider discover failed") }
        assertTrue(pauseLine.contains("provider discover failed"))
        assertFalse("no gate cause is invented: $pauseLine", pauseLine.contains("trust gate"))
    }
}
