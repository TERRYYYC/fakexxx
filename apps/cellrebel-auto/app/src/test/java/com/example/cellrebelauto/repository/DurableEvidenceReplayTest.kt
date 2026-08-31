package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.OperationReceiptRow
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Immutable durable-carrier replay contract: the same owner payload is idempotent, while a replay
 * that changes immutable evidence fails closed and preserves the first committed row.
 */
@RunWith(RobolectricTestRunner::class)
class DurableEvidenceReplayTest {

    private val providerSigner =
        "sha256:ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun observation(
        lat: Double? = 50.4501,
        lng: Double? = 30.5234
    ) = ObservationSnapshot(
        leaseId = "lease-77",
        acceptedIntentHash = "intent-77",
        coverage = "FULL",
        verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
        deliveryMode = "SYSTEM_MOCK",
        isMock = true,
        scheduleDecision = "ALLOWED_NOW",
        effectiveLat = lat,
        effectiveLng = lng,
        environmentRevision = 7L,
        environmentFingerprint = "fp-77",
        observedAtElapsedRealtimeMs = 1_000L,
        observedAtEpochMs = 2_000L,
        continuitySinceEpochMs = 400L,
        continuitySinceElapsedRealtimeMs = 500L,
        evidenceRefs = listOf("qwy:store:77")
    )

    private fun rawRecord(
        attemptId: Long,
        snapshot: ObservationSnapshot = observation(),
        continuitySinceEpochMs: Long? = snapshot.continuitySinceEpochMs,
        evidenceRefsJson: String = org.json.JSONArray(snapshot.evidenceRefs).toString(),
        legacyEvidenceRefs: String = snapshot.evidenceRefs.joinToString(";")
    ) = DurableObservationRecord(
        attemptId = attemptId,
        phase = "PRE",
        leaseId = snapshot.leaseId,
        acceptedIntentHash = snapshot.acceptedIntentHash,
        coverage = snapshot.coverage,
        verificationLevel = snapshot.verificationLevel,
        deliveryMode = snapshot.deliveryMode,
        isMock = snapshot.isMock,
        scheduleDecision = snapshot.scheduleDecision,
        effectiveLat = snapshot.effectiveLat,
        effectiveLng = snapshot.effectiveLng,
        environmentRevision = snapshot.environmentRevision,
        environmentFingerprint = snapshot.environmentFingerprint,
        observedAtElapsedRealtimeMs = snapshot.observedAtElapsedRealtimeMs,
        observedAtEpochMs = snapshot.observedAtEpochMs,
        continuitySinceElapsedRealtimeMs = snapshot.continuitySinceElapsedRealtimeMs,
        continuitySinceEpochMs = continuitySinceEpochMs,
        evidenceRefsJson = evidenceRefsJson,
        evidenceRefs = legacyEvidenceRefs
    )

    private fun execution(
        executionId: String,
        attemptId: Long
    ) = CellRebelExecution(
        executionId = executionId,
        attemptId = attemptId,
        completionEvidenceWire = 1,
        evidencePayloadDigest = "payload-77",
        startedAt = 2_000L,
        classifiedAt = 13_000L,
        startedAtElapsed = 2_000L,
        runningConfirmedAtElapsed = 2_100L,
        completedAtElapsed = 13_000L,
        baselineRunningState = "IDLE",
        runningMarkerText = "RUNNING",
        runningDurationMs = 10_900L,
        webBrowsingScore = 8.0,
        videoStreamingScore = 7.0,
        roundTimestampsElapsed = "2000;13000"
    )

    private fun trustedContext(execution: CellRebelExecution) = CompletionTrustContext(
        execution = execution,
        completionEvidenceWire = execution.completionEvidenceWire,
        applyReceiptIntentHash = "intent-77",
        locallyRecomputedIntentHash = "intent-77",
        applyReceiptLease = "lease-77",
        preObservation = observation().copy(observedAtElapsedRealtimeMs = 1_000L),
        postObservation = observation().copy(observedAtElapsedRealtimeMs = 14_000L)
    )

    private fun untrustedContext(execution: CellRebelExecution): CompletionTrustContext =
        trustedContext(execution).let { trusted ->
            trusted.copy(
                applyReceiptIntentHash = "intent-other",
                preObservation = trusted.preObservation.copy(acceptedIntentHash = "intent-other"),
                postObservation = trusted.postObservation.copy(acceptedIntentHash = "intent-other")
            )
        }

    private suspend fun seedPostPendingAttempt(
        attemptId: Long = 77L,
        currentExecutionId: String? = "exec-$attemptId",
        executionOwnerAttemptId: Long? = attemptId,
        providerApplicationId: String? = null,
        providerSignerDigest: String? = null,
    ) {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "bundle.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = providerApplicationId,
            )
        )
        db.planDao().insertTasks(
            listOf(
                LocationTask(
                    id = 42L,
                    planId = planId,
                    csvRow = 1,
                    longitude = 30.5234,
                    latitude = 50.4501,
                    priority = 1,
                    requiredSuccesses = 1
                )
            )
        )
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 1_000L, planId = planId, status = "running")
        )
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId,
                taskId = 42L,
                runSessionId = sessionId,
                attemptOrdinal = 1,
                successOrdinal = null,
                startedAt = 1_000L,
                runningObservedAt = null,
                endedAt = null,
                status = "running",
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 50.4501,
                longitude = 30.5234,
                aplusState = "POST_OBSERVE_PENDING",
                aplusLeaseId = "lease-77",
                providerApplicationId = providerApplicationId,
                providerSignerDigest = providerSignerDigest,
            )
        )
        if (currentExecutionId != null) {
            db.testAttemptDao().markCurrentExecutionId(attemptId, currentExecutionId)
            if (executionOwnerAttemptId != null) {
                db.attemptExecutionDao().insert(execution(currentExecutionId, executionOwnerAttemptId))
            }
        }
    }

    private suspend fun decisionBundleFailure(attemptId: Long = 77L): Throwable? = runCatching {
        repo.persistDecisionBundleAndEnterDeciding(
            attemptId = attemptId,
            postObservation = observation(),
            completionEvidenceWire = 1,
            acceptedIntentHash = "intent-77",
            leaseId = "lease-77"
        )
    }.exceptionOrNull()

    private suspend fun assertDecisionBundleRolledBack(attemptId: Long = 77L) {
        assertNull(
            "POST must roll back when the decision owner invariant fails",
            db.durableObservationDao().forAttemptPhase(attemptId, "POST")
        )
        assertNull(
            "completion receipt must roll back when the decision owner invariant fails",
            db.durableCompletionReceiptDao().forAttempt(attemptId)
        )
        assertEquals(
            "owner phase must remain POST_OBSERVE_PENDING",
            "POST_OBSERVE_PENDING",
            db.testAttemptDao().getAttemptById(attemptId)!!.aplusState
        )
    }

    private suspend fun enterDecidingWithDurableBundle(ctx: CompletionTrustContext) {
        repo.persistObservation(ctx.execution.attemptId, "PRE", ctx.preObservation)
        repo.persistDecisionBundleAndEnterDeciding(
            attemptId = ctx.execution.attemptId,
            postObservation = ctx.postObservation,
            completionEvidenceWire = ctx.completionEvidenceWire,
            acceptedIntentHash = ctx.applyReceiptIntentHash,
            leaseId = ctx.applyReceiptLease
        )
    }

    private suspend fun insertSiblingAttempt(
        attemptId: Long,
        attemptOrdinal: Int,
        currentExecutionId: String
    ) {
        val anchor = db.testAttemptDao().getAttemptById(77L)
            ?: error("test setup requires the anchor attempt")
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId,
                taskId = anchor.taskId,
                runSessionId = anchor.runSessionId,
                attemptOrdinal = attemptOrdinal,
                successOrdinal = null,
                startedAt = 1_000L,
                runningObservedAt = null,
                endedAt = null,
                status = "running",
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 50.4501,
                longitude = 30.5234,
                aplusState = "DECIDING",
                aplusLeaseId = "lease-77",
                currentExecutionId = currentExecutionId
            )
        )
    }

    @Test
    fun `identical observation replay is a no-op but an immutable mismatch fails closed`() = runTest {
        repo.persistObservation(77L, "PRE", observation())
        repo.persistObservation(77L, "PRE", observation())

        assertEquals(1, db.durableObservationDao().countForAttempt(77L))
        val mismatch = runCatching {
            repo.persistObservation(77L, "PRE", observation(lat = 51.0))
        }.exceptionOrNull()
        assertTrue("a conflicting replay must fail closed", mismatch is IllegalStateException)
        assertEquals("the first immutable carrier wins", 50.4501, repo.getObservation(77L, "PRE")!!.effectiveLat!!, 0.0)
    }

    @Test
    fun `signed zero is the only coordinate normalization and is replay-stable both ways`() = runTest {
        val negativeFirst = repo.persistObservation(77L, "PRE", observation(lat = -0.0, lng = -0.0))
        assertEquals("latitude -0 must use SQLite's canonical +0 representation",
            0.0.toRawBits(), negativeFirst.effectiveLat!!.toRawBits())
        assertEquals("longitude -0 must use SQLite's canonical +0 representation",
            0.0.toRawBits(), negativeFirst.effectiveLng!!.toRawBits())
        repo.persistObservation(77L, "PRE", observation(lat = 0.0, lng = 0.0))

        val positiveFirst = repo.persistObservation(78L, "POST", observation(lat = 0.0, lng = 0.0))
        repo.persistObservation(78L, "POST", observation(lat = -0.0, lng = -0.0))
        assertEquals(0.0.toRawBits(), positiveFirst.effectiveLat!!.toRawBits())
        assertEquals(0.0.toRawBits(), positiveFirst.effectiveLng!!.toRawBits())
        assertEquals(1, db.durableObservationDao().countForAttempt(77L))
        assertEquals(1, db.durableObservationDao().countForAttempt(78L))
    }

    @Test
    fun `invalid coordinates are rejected before Room and never collapse into the same replay payload`() = runTest {
        val invalidValues = listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 91.0)
        invalidValues.forEachIndexed { index, invalid ->
            val attemptId = 200L + index
            val failure = runCatching {
                repo.persistObservation(attemptId, "PRE", observation(lat = invalid))
            }.exceptionOrNull()
            assertTrue("invalid latitude $invalid must fail before storage",
                failure is IllegalArgumentException)
            assertNull("invalid latitude $invalid must not create a durable row",
                db.durableObservationDao().forAttemptPhase(attemptId, "PRE"))
        }

        listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 181.0).forEachIndexed { index, invalid ->
            val attemptId = 300L + index
            val failure = runCatching {
                repo.persistObservation(attemptId, "POST", observation(lng = invalid))
            }.exceptionOrNull()
            assertTrue("invalid longitude $invalid must fail before storage",
                failure is IllegalArgumentException)
            assertNull("invalid longitude $invalid must not create a durable row",
                db.durableObservationDao().forAttemptPhase(attemptId, "POST"))
        }
    }

    @Test
    fun `phase-bound observation for a missing attempt rejects and rolls back the orphan carrier`() = runTest {
        val missingAttemptId = 999L

        val failure = runCatching {
            repo.persistObservationAndMarkAplusState(
                attemptId = missingAttemptId,
                phase = "PRE",
                snapshot = observation(),
                aplusState = "PRE_OBSERVED"
            )
        }.exceptionOrNull()
        val orphan = db.durableObservationDao().forAttemptPhase(missingAttemptId, "PRE")

        assertTrue(
            "a phase-bound carrier requires an existing attempt owner and must roll back as a unit; " +
                "failure=${failure?.javaClass?.simpleName} orphan=$orphan",
            failure is IllegalStateException && orphan == null
        )
    }

    @Test
    fun `phase-bound observation from the wrong current phase rejects and rolls back both writes`() = runTest {
        seedPostPendingAttempt()

        val failure = runCatching {
            repo.persistObservationAndMarkAplusState(
                attemptId = 77L,
                phase = "PRE",
                snapshot = observation(),
                aplusState = "PRE_OBSERVED"
            )
        }.exceptionOrNull()
        val carrier = db.durableObservationDao().forAttemptPhase(77L, "PRE")
        val ownerPhase = db.testAttemptDao().getAttemptById(77L)!!.aplusState

        assertTrue(
            "PRE may only publish from its expected owner phase; failure=" +
                "${failure?.javaClass?.simpleName} carrier=$carrier ownerPhase=$ownerPhase",
            failure is IllegalStateException && carrier == null && ownerPhase == "POST_OBSERVE_PENDING"
        )
    }

    @Test
    fun `raw durable audit columns participate in immutable replay comparison`() = runTest {
        val mutations = listOf(
            "continuitySinceEpochMs" to rawRecord(400L, continuitySinceEpochMs = 999L),
            "evidenceRefsJson" to rawRecord(401L, evidenceRefsJson = "[ \"qwy:store:77\" ]"),
            "legacy evidenceRefs" to rawRecord(402L, legacyEvidenceRefs = "qwy:legacy:other")
        )

        mutations.forEach { (field, seeded) ->
            db.durableObservationDao().insertIfAbsent(seeded)
            val failure = runCatching {
                repo.persistObservation(seeded.attemptId, "PRE", observation())
            }.exceptionOrNull()
            assertTrue("$field mismatch must fail closed", failure is IllegalStateException)
            assertEquals("$field mismatch must preserve the first raw row", seeded.copy(id = 0),
                db.durableObservationDao().forAttemptPhase(seeded.attemptId, "PRE")!!.copy(id = 0))
        }
    }

    @Test
    fun `decision bundle without a current execution owner fails and rolls back`() = runTest {
        seedPostPendingAttempt(currentExecutionId = null, executionOwnerAttemptId = null)

        val failure = decisionBundleFailure()

        assertTrue("DECIDING requires a persisted currentExecutionId", failure is IllegalStateException)
        assertDecisionBundleRolledBack()
    }

    @Test
    fun `decision bundle with a missing current execution row fails and rolls back`() = runTest {
        seedPostPendingAttempt(currentExecutionId = "exec-missing", executionOwnerAttemptId = null)

        val failure = decisionBundleFailure()

        assertTrue("DECIDING requires the current execution row", failure is IllegalStateException)
        assertDecisionBundleRolledBack()
    }

    @Test
    fun `decision bundle with a foreign attempt execution owner fails and rolls back`() = runTest {
        seedPostPendingAttempt(currentExecutionId = "exec-foreign", executionOwnerAttemptId = 88L)

        val failure = decisionBundleFailure()

        assertTrue("DECIDING cannot consume an execution owned by another attempt", failure is IllegalStateException)
        assertDecisionBundleRolledBack()
    }

    @Test
    fun `decision bundle without durable PRE fails and rolls back POST receipt and state`() = runTest {
        seedPostPendingAttempt()

        val failure = decisionBundleFailure()

        assertTrue("DECIDING requires the same attempt's durable PRE", failure is IllegalStateException)
        assertDecisionBundleRolledBack()
    }

    @Test
    fun `decision bundle publishes POST receipt and DECIDING atomically`() = runTest {
        seedPostPendingAttempt()
        repo.persistObservation(77L, "PRE", observation())

        val (post, receipt) = repo.persistDecisionBundleAndEnterDeciding(
            attemptId = 77L,
            postObservation = observation(),
            completionEvidenceWire = 1,
            acceptedIntentHash = "intent-77",
            leaseId = "lease-77"
        )

        assertEquals(observation(), post)
        assertEquals(1, receipt.completionEvidenceWire)
        assertEquals("DECIDING", db.testAttemptDao().getAttemptById(77L)!!.aplusState)
        assertEquals(2, db.durableObservationDao().countForAttempt(77L))
        assertEquals("intent-77", db.durableCompletionReceiptDao().forAttempt(77L)!!.acceptedIntentHash)
    }

    @Test
    fun `ignored PRE owner publication rolls back the durable carrier`() = runTest {
        seedPostPendingAttempt()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE test_attempts SET aplusState = 'ENV_APPLIED' WHERE id = 77"
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER ignore_pre_owner_publication
            BEFORE UPDATE OF aplusState ON test_attempts
            WHEN OLD.id = 77 AND NEW.aplusState = 'PRE_OBSERVED'
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent()
        )

        val failure = runCatching {
            repo.persistObservationAndMarkAplusState(
                attemptId = 77L,
                phase = "PRE",
                snapshot = observation(),
                aplusState = "PRE_OBSERVED"
            )
        }.exceptionOrNull()

        assertTrue("a zero-row owner publication must abort the transaction", failure is IllegalStateException)
        assertNull("the PRE carrier must roll back with its ignored owner update",
            db.durableObservationDao().forAttemptPhase(77L, "PRE"))
        assertEquals("ENV_APPLIED", db.testAttemptDao().getAttemptById(77L)!!.aplusState)
    }

    @Test
    fun `ignored DECIDING owner publication rolls back POST and completion receipt`() = runTest {
        seedPostPendingAttempt()
        repo.persistObservation(77L, "PRE", observation())
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER ignore_deciding_owner_publication
            BEFORE UPDATE OF aplusState ON test_attempts
            WHEN OLD.id = 77 AND NEW.aplusState = 'DECIDING'
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent()
        )

        val failure = decisionBundleFailure()

        assertTrue("a zero-row DECIDING publication must abort the transaction", failure is IllegalStateException)
        assertDecisionBundleRolledBack()
    }

    @Test
    fun `ignored failed terminal projection rolls back CLOSED owner state`() = runTest {
        seedPostPendingAttempt()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE test_attempts SET aplusState = 'RELEASED' WHERE id = 77"
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER ignore_failed_terminal_projection
            BEFORE UPDATE OF status ON test_attempts
            WHEN OLD.id = 77 AND NEW.status = 'failed'
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent()
        )

        val failure = runCatching {
            repo.closeAplusFailure(77L, "UNTRUSTED", 9_000L)
        }.exceptionOrNull()
        val owner = db.testAttemptDao().getAttemptById(77L)!!

        assertTrue("an ignored terminal row update must fail closed", failure is IllegalStateException)
        assertEquals("the CLOSED half must roll back", "RELEASED", owner.aplusState)
        assertEquals("the status half must remain non-terminal", "running", owner.status)
        assertNull(owner.endedAt)
    }

    @Test
    fun `decision bundle rejects a noncanonical raw PRE carrier`() = runTest {
        seedPostPendingAttempt()
        db.durableObservationDao().insertIfAbsent(
            rawRecord(77L, evidenceRefsJson = "[ \"qwy:store:77\" ]")
        )

        val failure = decisionBundleFailure()

        assertTrue("raw PRE provenance must be validated before DECIDING", failure is IllegalStateException)
        assertDecisionBundleRolledBack()
    }

    @Test
    fun `trusted decision rejects evidence lease that differs from the attempt owner lease`() = runTest {
        val ownedExecution = execution("exec-77", 77L)
        val evidenceObservation = observation().copy(leaseId = "lease-evidence")
        val mismatched = trustedContext(ownedExecution).copy(
            applyReceiptLease = "lease-evidence",
            preObservation = evidenceObservation.copy(observedAtElapsedRealtimeMs = 1_000L),
            postObservation = evidenceObservation.copy(observedAtElapsedRealtimeMs = 14_000L)
        )
        seedPostPendingAttempt()

        val result = runCatching {
            enterDecidingWithDurableBundle(mismatched)
            repo.recordTrustedCompletion(mismatched, commitClockMs = 99_000L)
        }

        assertEquals(
            "the durable evidence lease must bind to TestAttempt.aplusLeaseId",
            TrustDecision.FAIL,
            result.getOrNull()
        )
        assertNull("lease split-brain must never mint trusted quota", db.trustedQuotaDao().getByAttempt(77L))
        assertTrue(
            "lease split-brain must leave a durable negative decision carrier",
            db.unverifiedAttemptRecordDao().getByAttempt(77L) != null
        )
    }

    @Test
    fun `DECIDING rejects a legacy operation receipt before mint`() = runTest {
        val providerApplicationId = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        val ownedExecution = execution("exec-77", 77L)
        val ctx = trustedContext(ownedExecution)
        com.example.cellrebelauto.environment.ProviderTrustStore(db.providerPairingDao()).approve(
            providerApplicationId,
            providerSigner,
            versionCode = 1,
            approvedAt = 1L,
        )
        seedPostPendingAttempt(
            providerApplicationId = providerApplicationId,
            providerSignerDigest = providerSigner,
        )
        enterDecidingWithDurableBundle(ctx)
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(77L),
                requestDigest = "intent-77",
                resultOutcome = "APPLIED",
                createdAt = 1_000L,
                leaseId = "lease-77",
                providerApplicationId = null,
            )
        )

        val failure = runCatching {
            repo.recordTrustedCompletionForProvider(
                ctx,
                commitClockMs = 99_000L,
                providerApplicationId = providerApplicationId,
                providerSignerDigest = providerSigner,
            )
        }.exceptionOrNull()

        assertTrue(
            "attempt and operation receipt principals must join before DECIDING can mint",
            failure is IllegalStateException &&
                failure.message.orEmpty().startsWith("PROVIDER_PRINCIPAL_"),
        )
        assertNull("legacy receipt principal can never mint trusted quota",
            db.trustedQuotaDao().getByAttempt(77L))
    }

    @Test
    fun `production DECIDING rejects a missing attempt before persisting an orphan execution`() = runTest {
        val missingAttemptId = 404L
        val orphan = execution("exec-missing", missingAttemptId)

        val failure = runCatching {
            repo.recordTrustedCompletionForProvider(
                trustedContext(orphan),
                commitClockMs = 99_000L,
                providerApplicationId = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                providerSignerDigest = providerSigner,
            )
        }.exceptionOrNull()

        assertTrue(
            "a production decision without its durable attempt owner must fail as unknown",
            failure is IllegalStateException &&
                failure.message.orEmpty().startsWith("PROVIDER_PRINCIPAL_UNKNOWN:"),
        )
        assertNull(
            "principal validation must happen before any orphan execution is persisted",
            db.attemptExecutionDao().byExecutionId(orphan.executionId),
        )
    }

    private suspend fun assertSignerReceiptRejectedBeforeDecisionMutation(
        recordedSigner: String?,
        expectedReason: String,
    ) {
        val providerApplicationId = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        com.example.cellrebelauto.environment.ProviderTrustStore(db.providerPairingDao()).approve(
            providerApplicationId,
            providerSigner,
            versionCode = 1,
            approvedAt = 1L,
        )
        seedPostPendingAttempt(
            currentExecutionId = null,
            executionOwnerAttemptId = null,
            providerApplicationId = providerApplicationId,
            providerSignerDigest = providerSigner,
        )
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(77L),
                requestDigest = "intent-77",
                resultOutcome = "APPLIED",
                createdAt = 1_000L,
                leaseId = "lease-77",
                providerApplicationId = providerApplicationId,
                providerSignerDigest = recordedSigner,
            )
        )
        val candidate = execution("exec-signer-boundary", 77L)

        val failure = runCatching {
            repo.recordTrustedCompletionForProvider(
                trustedContext(candidate),
                commitClockMs = 99_000L,
                providerApplicationId = providerApplicationId,
                providerSignerDigest = providerSigner,
            )
        }.exceptionOrNull()

        assertTrue(
            failure is IllegalStateException &&
                failure.message.orEmpty().startsWith("$expectedReason:"),
        )
        assertNull(db.attemptExecutionDao().byExecutionId(candidate.executionId))
        assertNull(db.trustedQuotaDao().getByAttempt(77L))
        assertNull(db.unverifiedAttemptRecordDao().getByAttempt(77L))
    }

    @Test
    fun `DECIDING rejects null signer operation proof before carrier or mint`() = runTest {
        assertSignerReceiptRejectedBeforeDecisionMutation(
            recordedSigner = null,
            expectedReason = "PROVIDER_SIGNER_OWNER_UNKNOWN",
        )
    }

    @Test
    fun `DECIDING rejects foreign signer operation proof before carrier or mint`() = runTest {
        assertSignerReceiptRejectedBeforeDecisionMutation(
            recordedSigner =
                "sha256:3b20b06be2531a128426fcf6d873eb2ce27f086b7a0e6ef0f20586076e5f3cd3",
            expectedReason = "PROVIDER_SIGNER_OWNER_CONFLICT",
        )
    }

    @Test
    fun `DECIDING re-evaluation rejects a raw PRE carrier that bypassed direct replay`() = runTest {
        val ownedExecution = execution("exec-77", 77L)
        val ctx = trustedContext(ownedExecution)
        seedPostPendingAttempt()
        db.durableObservationDao().insertIfAbsent(
            rawRecord(77L, snapshot = ctx.preObservation, evidenceRefsJson = "[ \"qwy:store:77\" ]")
        )
        repo.persistObservation(77L, "POST", ctx.postObservation)
        repo.persistCompletionReceipt(
            77L,
            ctx.completionEvidenceWire,
            ctx.applyReceiptIntentHash,
            ctx.applyReceiptLease
        )
        db.testAttemptDao().markAplusState(77L, "DECIDING")

        val failure = runCatching {
            repo.recordTrustedCompletion(ctx, commitClockMs = 99_000L)
        }.exceptionOrNull()

        assertTrue("recovery-time decision must revalidate raw PRE provenance", failure is IllegalStateException)
        assertNull(db.trustedQuotaDao().getByAttempt(77L))
        assertNull(db.unverifiedAttemptRecordDao().getByAttempt(77L))
    }

    @Test
    fun `decision bundle conflict rolls back POST and leaves owner POST pending`() = runTest {
        seedPostPendingAttempt()
        repo.persistObservation(77L, "PRE", observation())
        repo.persistCompletionReceipt(77L, 1, "intent-first", "lease-77")

        val failure = runCatching {
            repo.persistDecisionBundleAndEnterDeciding(
                attemptId = 77L,
                postObservation = observation(),
                completionEvidenceWire = 1,
                acceptedIntentHash = "intent-conflict",
                leaseId = "lease-77"
            )
        }.exceptionOrNull()

        assertTrue("receipt conflict must fail the whole decision bundle", failure is IllegalStateException)
        assertNull("POST insert must roll back with the receipt conflict",
            db.durableObservationDao().forAttemptPhase(77L, "POST"))
        assertEquals("POST_OBSERVE_PENDING", db.testAttemptDao().getAttemptById(77L)!!.aplusState)
        assertEquals("the first immutable receipt must survive", "intent-first",
            db.durableCompletionReceiptDao().forAttempt(77L)!!.acceptedIntentHash)
    }

    @Test
    fun `every observation payload field participates in immutable replay comparison`() = runTest {
        val original = observation()
        val mutations: List<Pair<String, (ObservationSnapshot) -> ObservationSnapshot>> = listOf(
            "leaseId" to { it.copy(leaseId = "lease-other") },
            "acceptedIntentHash" to { it.copy(acceptedIntentHash = "intent-other") },
            "coverage" to { it.copy(coverage = "PARTIAL") },
            "verificationLevel" to { it.copy(verificationLevel = "HOOK_VERIFIED") },
            "deliveryMode" to { it.copy(deliveryMode = "HOOK") },
            "isMock" to { it.copy(isMock = false) },
            "scheduleDecision" to { it.copy(scheduleDecision = "DENIED") },
            "effectiveLat" to { it.copy(effectiveLat = 51.0) },
            "effectiveLng" to { it.copy(effectiveLng = 31.0) },
            "environmentRevision" to { it.copy(environmentRevision = 8L) },
            "environmentFingerprint" to { it.copy(environmentFingerprint = "fp-other") },
            "observedAtElapsedRealtimeMs" to { it.copy(observedAtElapsedRealtimeMs = 1_001L) },
            "observedAtEpochMs" to { it.copy(observedAtEpochMs = 2_001L) },
            "continuitySinceEpochMs" to { it.copy(continuitySinceEpochMs = 401L) },
            "continuitySinceElapsedRealtimeMs" to { it.copy(continuitySinceElapsedRealtimeMs = 501L) },
            "evidenceRefs" to { it.copy(evidenceRefs = listOf("qwy:store:other")) }
        )

        mutations.forEachIndexed { index, (field, mutate) ->
            val attemptId = 100L + index
            val winner = repo.persistObservation(attemptId, "PRE", original)
            val mismatch = runCatching {
                repo.persistObservation(attemptId, "PRE", mutate(original))
            }.exceptionOrNull()
            assertTrue("$field must be immutable", mismatch is IllegalStateException)
            assertEquals("$field mismatch must preserve the first complete payload",
                winner, repo.getObservationSnapshot(attemptId, "PRE"))
        }
    }

    @Test
    fun `identical completion receipt replay is a no-op but an immutable mismatch fails closed`() = runTest {
        repo.persistCompletionReceipt(77L, 1, "intent-77", "lease-77")
        repo.persistCompletionReceipt(77L, 1, "intent-77", "lease-77")

        val mutations = listOf(
            Triple("wire", 2, "intent-77" to "lease-77"),
            Triple("acceptedIntentHash", 1, "intent-other" to "lease-77"),
            Triple("leaseId", 1, "intent-77" to "lease-other")
        )
        mutations.forEachIndexed { index, (field, wire, payload) ->
            val attemptId = 77L + index
            if (index > 0) repo.persistCompletionReceipt(attemptId, 1, "intent-77", "lease-77")
            val mismatch = runCatching {
                repo.persistCompletionReceipt(attemptId, wire, payload.first, payload.second)
            }.exceptionOrNull()
            assertTrue("completion $field must be immutable", mismatch is IllegalStateException)
            val durable = repo.getCompletionReceipt(attemptId)!!
            assertEquals("$field mismatch must preserve the first wire", 1, durable.completionEvidenceWire)
            assertEquals("$field mismatch must preserve the first hash", "intent-77", durable.acceptedIntentHash)
            assertEquals("$field mismatch must preserve the first lease", "lease-77", durable.leaseId)
        }
    }

    @Test
    fun `persistExecutionEvidence rejects every immutable payload conflict for the same executionId`() = runTest {
        val mutations: List<Pair<String, (CellRebelExecution) -> CellRebelExecution>> = listOf(
            "attemptId" to { it.copy(attemptId = 88L) },
            "completionEvidenceWire" to { it.copy(completionEvidenceWire = 2) },
            "evidencePayloadDigest" to { it.copy(evidencePayloadDigest = "payload-other") },
            "startedAt and startedAtElapsed" to { it.copy(startedAtElapsed = 2_001L) },
            "runningConfirmedAtElapsed" to { it.copy(runningConfirmedAtElapsed = 2_101L) },
            "classifiedAt and completedAtElapsed" to { it.copy(completedAtElapsed = 13_001L) },
            "baselineRunningState" to { it.copy(baselineRunningState = "PRE_EXISTING") },
            "runningMarkerText" to { it.copy(runningMarkerText = "RUNNING_OTHER") },
            "runningDurationMs" to { it.copy(runningDurationMs = 10_901L) },
            "webBrowsingScore" to { it.copy(webBrowsingScore = 8.5) },
            "videoStreamingScore" to { it.copy(videoStreamingScore = 7.5) },
            "roundTimestampsElapsed" to { it.copy(roundTimestampsElapsed = "2000;9000;13000") }
        )
        val silentlyAccepted = mutableListOf<String>()
        val changedWinners = mutableListOf<String>()

        mutations.forEachIndexed { index, (field, mutate) ->
            val winner = execution("persist-conflict-$index", 77L)
            db.attemptExecutionDao().insert(winner)
            val replay = mutate(winner)

            val failure = runCatching {
                repo.persistExecutionEvidence(
                    executionId = replay.executionId,
                    attemptId = replay.attemptId,
                    completionEvidenceWire = replay.completionEvidenceWire,
                    evidencePayloadDigest = replay.evidencePayloadDigest,
                    startedAtElapsed = replay.startedAtElapsed,
                    runningConfirmedAtElapsed = replay.runningConfirmedAtElapsed,
                    completedAtElapsed = replay.completedAtElapsed,
                    baselineRunningState = replay.baselineRunningState,
                    runningMarkerText = replay.runningMarkerText,
                    runningDurationMs = replay.runningDurationMs,
                    webBrowsingScore = replay.webBrowsingScore,
                    videoStreamingScore = replay.videoStreamingScore,
                    roundTimestampsElapsed = replay.roundTimestampsElapsed
                )
            }.exceptionOrNull()
            if (failure !is IllegalStateException) silentlyAccepted += field
            if (db.attemptExecutionDao().byExecutionId(winner.executionId)!!.copy(id = 0) != winner) {
                changedWinners += field
            }
        }

        assertTrue(
            "same-key execution replay must fail closed for every field; " +
                "silentlyAccepted=$silentlyAccepted changedWinners=$changedWinners",
            silentlyAccepted.isEmpty() && changedWinners.isEmpty()
        )
    }

    @Test
    fun `recordTrustedCompletion rejects every execution conflict and never mints from the live loser`() = runTest {
        seedPostPendingAttempt(currentExecutionId = null, executionOwnerAttemptId = null)
        val mutations: List<Pair<String, (CellRebelExecution) -> CellRebelExecution>> = listOf(
            "attemptId" to { it.copy(attemptId = it.attemptId + 10_000L) },
            "completionEvidenceWire" to { it.copy(completionEvidenceWire = 2) },
            "evidencePayloadDigest" to { it.copy(evidencePayloadDigest = "payload-other") },
            "startedAt" to { it.copy(startedAt = 2_001L) },
            "classifiedAt" to { it.copy(classifiedAt = 13_001L) },
            "startedAtElapsed" to { it.copy(startedAtElapsed = 2_001L) },
            "runningConfirmedAtElapsed" to { it.copy(runningConfirmedAtElapsed = 2_101L) },
            "completedAtElapsed" to { it.copy(completedAtElapsed = 13_001L) },
            "baselineRunningState" to { it.copy(baselineRunningState = "PRE_EXISTING") },
            "runningMarkerText" to { it.copy(runningMarkerText = "RUNNING_OTHER") },
            "runningDurationMs" to { it.copy(runningDurationMs = 10_901L) },
            "webBrowsingScore" to { it.copy(webBrowsingScore = 8.5) },
            "videoStreamingScore" to { it.copy(videoStreamingScore = 7.5) },
            "roundTimestampsElapsed" to { it.copy(roundTimestampsElapsed = "2000;9000;13000") }
        )
        val silentlyAccepted = mutableListOf<String>()
        val mintedFromLiveLoser = mutableListOf<String>()
        val changedWinners = mutableListOf<String>()

        mutations.forEachIndexed { index, (field, mutate) ->
            val attemptId = 500L + index
            val executionId = "trusted-conflict-$index"
            insertSiblingAttempt(attemptId, index + 2, executionId)
            val winner = execution(executionId, attemptId)
            db.attemptExecutionDao().insert(winner)
            val liveLoser = mutate(winner)

            val failure = runCatching {
                repo.recordTrustedCompletion(trustedContext(liveLoser), commitClockMs = 99_999L + index)
            }.exceptionOrNull()
            if (failure !is IllegalStateException) silentlyAccepted += field
            if (db.trustedQuotaDao().getByAttempt(attemptId) != null) mintedFromLiveLoser += field
            if (db.attemptExecutionDao().byExecutionId(executionId)!!.copy(id = 0) != winner) {
                changedWinners += field
            }
        }

        assertTrue(
            "trusted completion must evaluate only an exact durable execution winner; " +
                "silentlyAccepted=$silentlyAccepted minted=$mintedFromLiveLoser " +
                "changedWinners=$changedWinners",
            silentlyAccepted.isEmpty() && mintedFromLiveLoser.isEmpty() && changedWinners.isEmpty()
        )
    }

    @Test
    fun `recordTrustedCompletion cannot bypass the durable decision bundle from POST pending`() = runTest {
        val ownedExecution = execution("exec-77", 77L)
        val liveContext = trustedContext(ownedExecution)
        seedPostPendingAttempt()
        repo.persistObservation(77L, "PRE", liveContext.preObservation)

        assertEquals("POST_OBSERVE_PENDING", db.testAttemptDao().getAttemptById(77L)!!.aplusState)
        assertNull(db.durableObservationDao().forAttemptPhase(77L, "POST"))
        assertNull(db.durableCompletionReceiptDao().forAttempt(77L))

        val outcome = runCatching {
            repo.recordTrustedCompletion(liveContext, commitClockMs = 99_000L)
        }
        val failure = outcome.exceptionOrNull()
        val trusted = db.trustedQuotaDao().getByAttempt(77L)
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(77L)
        val ownerPhase = db.testAttemptDao().getAttemptById(77L)!!.aplusState

        assertTrue(
            "only a DECIDING owner with an exact durable PRE, POST, and completion receipt may " +
                "decide; result=${outcome.getOrNull()} failure=${failure?.javaClass?.simpleName} " +
                "trusted=$trusted unverified=$unverified ownerPhase=$ownerPhase",
            failure is IllegalStateException && trusted == null && unverified == null &&
                ownerPhase == "POST_OBSERVE_PENDING"
        )
    }

    @Test
    fun `PASS decision rejects a pre-existing same-attempt unverified carrier and never mints`() = runTest {
        val execution = execution("exec-77", 77L)
        val ctx = trustedContext(execution)
        seedPostPendingAttempt()
        enterDecidingWithDurableBundle(ctx)
        val seededId = db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = execution.evidencePayloadDigest
            )
        )
        val firstCarrier = UnverifiedAttemptRecord(
            id = seededId,
            attemptId = 77L,
            reason = "UNTRUSTED",
            evidenceDigest = execution.evidencePayloadDigest
        )

        val failure = runCatching {
            repo.recordTrustedCompletion(ctx, commitClockMs = 99_000L)
        }.exceptionOrNull()

        assertTrue("a PASS verdict cannot coexist with the opposite carrier", failure is IllegalStateException)
        assertNull("carrier conflict must never mint trusted quota", db.trustedQuotaDao().getByAttempt(77L))
        assertEquals("the first unverified carrier must remain unchanged", firstCarrier,
            db.unverifiedAttemptRecordDao().getByAttempt(77L))
    }

    @Test
    fun `FAIL decision rejects a pre-existing same-attempt trusted carrier and never writes unverified`() = runTest {
        val execution = execution("exec-77", 77L)
        val ctx = untrustedContext(execution)
        seedPostPendingAttempt()
        enterDecidingWithDurableBundle(ctx)
        val seededId = db.trustedQuotaDao().insert(
            TrustedQuotaEntry(
                attemptId = 77L,
                taskId = 42L,
                evidenceDigest = execution.evidencePayloadDigest,
                committedAt = 98_000L
            )
        )
        val firstCarrier = TrustedQuotaEntry(
            id = seededId,
            attemptId = 77L,
            taskId = 42L,
            evidenceDigest = execution.evidencePayloadDigest,
            committedAt = 98_000L
        )

        val failure = runCatching {
            repo.recordTrustedCompletion(ctx, commitClockMs = 99_000L)
        }.exceptionOrNull()

        assertTrue("a FAIL verdict cannot coexist with the opposite carrier", failure is IllegalStateException)
        assertNull("carrier conflict must never write an unverified row",
            db.unverifiedAttemptRecordDao().getByAttempt(77L))
        assertEquals("the first trusted carrier must remain unchanged", firstCarrier,
            db.trustedQuotaDao().getByAttempt(77L))
    }

    @Test
    fun `unverified replay requires exact readback and preserves the first carrier on conflicts`() = runTest {
        seedPostPendingAttempt()
        val firstExecution = execution("exec-77", 77L)
        val firstContext = untrustedContext(firstExecution)
        enterDecidingWithDurableBundle(firstContext)

        assertEquals(TrustDecision.FAIL,
            repo.recordTrustedCompletion(firstContext, commitClockMs = 99_000L))
        val exactWinner = db.unverifiedAttemptRecordDao().getByAttempt(77L)!!
        assertEquals("an exact same-verdict replay must remain idempotent", TrustDecision.FAIL,
            repo.recordTrustedCompletion(firstContext, commitClockMs = 99_001L))
        assertEquals("exact replay must preserve the complete first row", exactWinner,
            db.unverifiedAttemptRecordDao().getByAttempt(77L))

        val conflictSeeds = listOf(
            "evidenceDigest" to UnverifiedAttemptRecord(
                attemptId = 78L,
                reason = "UNTRUSTED",
                evidenceDigest = "payload-first"
            ),
            "reason" to UnverifiedAttemptRecord(
                attemptId = 79L,
                reason = "POLICY_REJECTED_FIRST",
                evidenceDigest = firstExecution.evidencePayloadDigest
            )
        )
        conflictSeeds.forEachIndexed { index, (field, seed) ->
            val executionId = "exec-${seed.attemptId}"
            insertSiblingAttempt(seed.attemptId, index + 2, executionId)
            val siblingExecution = execution(executionId, seed.attemptId)
            db.attemptExecutionDao().insert(siblingExecution)
            val siblingContext = untrustedContext(siblingExecution)
            enterDecidingWithDurableBundle(siblingContext)
            val seededId = db.unverifiedAttemptRecordDao().insert(seed)
            val firstCarrier = seed.copy(id = seededId)

            val failure = runCatching {
                repo.recordTrustedCompletion(siblingContext, commitClockMs = 99_100L + index)
            }.exceptionOrNull()

            assertTrue("unverified $field conflict must fail closed", failure is IllegalStateException)
            assertEquals("unverified $field conflict must preserve the complete first row", firstCarrier,
                db.unverifiedAttemptRecordDao().getByAttempt(seed.attemptId))
            assertNull("unverified replay must never create an opposite trusted carrier",
                db.trustedQuotaDao().getByAttempt(seed.attemptId))
        }
    }
}
