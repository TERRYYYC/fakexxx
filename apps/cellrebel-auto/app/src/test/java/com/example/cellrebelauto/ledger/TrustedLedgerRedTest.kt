package com.example.cellrebelauto.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.environment.TrustPolicy
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.execution.CellRebelCompletionEvidenceV1
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trusted ledger at-most-once + pre/post-observe attribution (Issue #5 Task 4, areas 1, 4 & 5).
 *
 * AREA 1 (GREEN-from-schema, INV-10): `UNIQUE(attemptId)` is enforced by the schema, so these
 * uniqueness/append-only assertions pass now — including under concurrent insertion — validating
 * that the schema-level invariant is actually live in the migrated database, independent of any
 * application code.
 *
 * AREA 4 (RED, §6.4/§6.4.1, INV-06/07/11/23/27): [TrustPolicy.evaluate] gates whether a classified
 * execution may mint a [TrustedQuotaEntry]. The canonical POSITIVE tuple is §6.4 lines 1493-1530 —
 * `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED + deliveryMode=SYSTEM_MOCK + isMock=true + scheduleDecision=
 * ALLOWED_NOW + coverage=FULL + revision/fingerprint pre==post + continuitySince pre==post!=null &
 * <= pre.observedAt + observedAtElapsed brackets the execution window + evidenceRefs non-empty +
 * three-way intent hash + effective coord within 1.0 m`. Only ONE positive case may PASS (RED until
 * GREEN). EVERY §6.4 predicate field then has a dedicated "invert this field ⇒ FAIL" negative (the
 * §6.4.1 矛盾 tuples plus per-field inversions), so a partial GREEN that forgets any field fails its
 * negative. This is what defeats Sol's round-2 counterexample (an impl that accepted the wrong
 * `gps + isMock=false` tuple greened all 16): the positive is now `isMock=true`, and there is an
 * explicit `isMock=false ⇒ FAIL` negative, so that impl fails both. Only the full §6.4 predicate
 * satisfies every assertion. Only the decision TYPE is frozen pre-freeze.
 *
 * AREA 5 (RED, §11.2 F1 / §11.4, round-4 standard): drives the REAL production entrypoint
 * [com.example.cellrebelauto.repository.PlanRepository.recordTrustedCompletion] against the in-memory
 * Room DB and asserts DURABLE EFFECTS observable through the real DAOs (not an isolated helper). Today
 * NO production path persists a CellRebelExecution or mints trusted quota — the skeleton (1) persists
 * digest + the three §6.4.2 clocks, DROPPING the §7.1 evidence detail, and (2) evaluates [TrustPolicy]
 * but MINTS NOTHING even on PASS. So a §6.4-positive completion read back through the entrypoint shows
 * the §7.1 fields NULL and zero TrustedQuotaEntry (RED). GREEN copies the §7.1 detail into the row and
 * inserts exactly one entry (same transaction) on PASS. R5-F1 (§11.7): the mint test additionally
 * READS BACK that entry and asserts it binds the SEEDED attempt→task identity (taskId/attemptId/
 * evidenceDigest/committedAt), not a constant — so Sol's round-4 `taskId = 1L` combined attack stays
 * RED. A §6.4-failing completion mints nothing and reports FAIL (the negative, which passes under the
 * skeleton too). This is the §11.1 legacy hold-out: a digest-only / false-oracle impl cannot green
 * while the A+ evidence chain is lost.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md
 *
 * # 可信账本唯一/只插不改/并发（schema 已生效）+ §6.4 双极性信任判定（RED，逐字段反 false-oracle）
 */
@RunWith(RobolectricTestRunner::class)
class TrustedLedgerRedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- AREA 1: uniqueness / append-only / concurrency (GREEN-from-schema — UNIQUE index is live) ----

    @Test
    fun `a second trusted entry for the same attemptId is rejected`() = runTest {
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(attemptId = 10L, taskId = 1L, evidenceDigest = "d1", committedAt = 1000L)
        )
        // At-most-once: UNIQUE(attemptId) must reject a second insert for the same attempt (INV-10).
        var threw = false
        try {
            db.trustedQuotaDao().insert(
                TrustedQuotaEntry(attemptId = 10L, taskId = 1L, evidenceDigest = "d2", committedAt = 2000L)
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("second trusted entry for the same attemptId must be rejected (at-most-once)", threw)
        // The original entry survived; no partial/second row.
        assertEquals(1, db.trustedQuotaDao().countAll())
        assertEquals("d1", db.trustedQuotaDao().getByAttempt(10L)!!.evidenceDigest)
    }

    @Test
    fun `trusted ledger is append-only across distinct attempts`() = runTest {
        // Three distinct attempts each mint one entry; the trusted count is a pure projection.
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 10L, taskId = 1L, evidenceDigest = "a", committedAt = 1L))
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 11L, taskId = 1L, evidenceDigest = "b", committedAt = 2L))
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 12L, taskId = 1L, evidenceDigest = "c", committedAt = 3L))
        assertEquals(3, db.trustedQuotaDao().trustedCountForTask(1L))
        // Each attempt's evidence is individually retrievable (no overwrite).
        assertEquals("a", db.trustedQuotaDao().getByAttempt(10L)!!.evidenceDigest)
        assertEquals("c", db.trustedQuotaDao().getByAttempt(12L)!!.evidenceDigest)
    }

    @Test
    fun `concurrent inserts of the same attemptId leave exactly one trusted entry`() = runTest {
        // Two concurrent insertions race on UNIQUE(attemptId); exactly one wins, the ledger holds
        // one row. Final count is deterministic regardless of interleaving (INV-10 at-most-once).
        val outcomes = coroutineScope {
            listOf(
                async { tryInsert(20L, "x") },
                async { tryInsert(20L, "y") }
            ).awaitAll()
        }
        assertEquals("exactly one of the racing inserts must succeed", 1, outcomes.count { it })
        assertEquals(1, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `concurrent inserts of distinct attemptIds all persist with no cross-contamination`() = runTest {
        val outcomes = coroutineScope {
            (30..34).map { id -> async { tryInsert(id.toLong(), "d$id") } }.awaitAll()
        }
        assertTrue("all distinct attempts must persist concurrently", outcomes.all { it })
        assertEquals(5, db.trustedQuotaDao().countAll())
    }

    private suspend fun tryInsert(attemptId: Long, digest: String): Boolean = try {
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(attemptId = attemptId, taskId = 1L, evidenceDigest = digest, committedAt = 1L)
        )
        true
    } catch (e: Exception) {
        false
    }

    // ---- AREA 4: §6.4 trust predicate via TrustPolicy (RED — both-polarity, every field discriminated) ----
    //
    // Frozen §6.4 positive values:
    private val WIRE_VERIFIED = CellRebelCompletionEvidenceV1.VERIFIED_NEW_COMPLETION.wire // 1
    private val LEVEL_SYSTEM_MOCK_VERIFIED = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED"
    private val DELIVERY_SYSTEM_MOCK = "SYSTEM_MOCK"
    private val COVERAGE_FULL = "FULL"
    private val SCHEDULE_ALLOWED_NOW = "ALLOWED_NOW"
    private val INTENT_HASH = "intent-h"
    private val LEASE = "L1"
    private val REVISION = 7L
    private val FINGERPRINT = "fp-1"
    private val TARGET_LAT = 40.0
    private val TARGET_LNG = -74.0
    private val TOLERANCE_M = 1.0 // §6.4.2 TRUSTED_LOCATION_TOLERANCE_METERS (frozen)
    // §6.4.2 monotonic execution window (elapsedRealtime; epoch audit fields are NOT predicates).
    // The RUN phase (completedAt − runningConfirmedAt) must be ≥ §6.4's 10000 ms floor — a sub-10 s run
    // is NOT a trusted completion (Sol round-3 Finding 1). 13000 − 2100 = 10900 ms ≥ 10000.
    private val EXEC_STARTED_AT_ELAPSED = 2000L
    private val EXEC_RUNNING_CONFIRMED_AT_ELAPSED = 2100L
    private val EXEC_COMPLETED_AT_ELAPSED = 13000L
    private val PRE_OBSERVED_AT_ELAPSED = 1000L    // < EXEC_STARTED_AT_ELAPSED
    private val POST_OBSERVED_AT_ELAPSED = 14000L  // > EXEC_COMPLETED_AT_ELAPSED (brackets the run)
    private val CONTINUITY_SINCE_ELAPSED = 500L    // <= PRE_OBSERVED_AT_ELAPSED; pre==post

    private fun execution(wire: Int): CellRebelExecution = CellRebelExecution(
        executionId = "exec-$wire",
        attemptId = 10L,
        completionEvidenceWire = wire,
        evidencePayloadDigest = "payload-digest",
        startedAt = 1000L,          // epoch, audit only
        classifiedAt = 1100L,       // epoch, audit only
        startedAtElapsed = EXEC_STARTED_AT_ELAPSED,
        runningConfirmedAtElapsed = EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
        completedAtElapsed = EXEC_COMPLETED_AT_ELAPSED
    )

    /** A fully §6.4-valid pre observation. isMock=TRUE is the trusted path (not false). */
    private fun validPre(): ObservationSnapshot = ObservationSnapshot(
        leaseId = LEASE,
        acceptedIntentHash = INTENT_HASH,
        coverage = COVERAGE_FULL,
        verificationLevel = LEVEL_SYSTEM_MOCK_VERIFIED,
        deliveryMode = DELIVERY_SYSTEM_MOCK,
        isMock = true,
        scheduleDecision = SCHEDULE_ALLOWED_NOW,
        effectiveLat = TARGET_LAT,
        effectiveLng = TARGET_LNG,
        environmentRevision = REVISION,
        environmentFingerprint = FINGERPRINT,
        observedAtElapsedRealtimeMs = PRE_OBSERVED_AT_ELAPSED,
        observedAtEpochMs = 900L, // audit only, never compared
        continuitySinceElapsedRealtimeMs = CONTINUITY_SINCE_ELAPSED,
        evidenceRefs = listOf("qwy:store:abc")
    )

    /** A fully §6.4-valid post observation; revision/fingerprint/continuity match [validPre]. */
    private fun validPost(): ObservationSnapshot = validPre().copy(
        observedAtElapsedRealtimeMs = POST_OBSERVED_AT_ELAPSED,
        observedAtEpochMs = 6500L
    )

    /** The canonical §6.4 positive bundle — the ONLY input that may PASS. */
    private fun validContext(wire: Int = WIRE_VERIFIED): CompletionTrustContext = CompletionTrustContext(
        execution = execution(wire),
        completionEvidenceWire = wire,
        applyReceiptIntentHash = INTENT_HASH,
        locallyRecomputedIntentHash = INTENT_HASH,
        applyReceiptLease = LEASE,
        targetLat = TARGET_LAT,
        targetLng = TARGET_LNG,
        locationToleranceMeters = TOLERANCE_M,
        preObservation = validPre(),
        postObservation = validPost()
    )

    /**
     * The canonical execution row carrying the FULL §7.1 / §8.6 evidence detail (baseline state /
     * marker text / RUNNING duration / both scores / per-round timestamps) — what a GREEN
     * [PlanRepository.recordTrustedCompletion] must persist verbatim. Built atop [execution] so the
     * digest + three §6.4.2 clocks are identical to every AREA 4 case.
     */
    private fun fullEvidenceExecution(wire: Int): CellRebelExecution = execution(wire).copy(
        baselineRunningState = "IDLE",
        runningMarkerText = "RUNNING",
        runningDurationMs = EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED, // ≥ 10000 ms floor
        webBrowsingScore = 8.0,
        videoStreamingScore = 7.0,
        roundTimestampsElapsed = "${EXEC_STARTED_AT_ELAPSED};${EXEC_COMPLETED_AT_ELAPSED}"
    )

    /** The canonical §6.4-positive bundle with the FULL §7.1 evidence detail attached (AREA 5 driver). */
    private fun fullContext(wire: Int = WIRE_VERIFIED): CompletionTrustContext =
        validContext(wire).copy(execution = fullEvidenceExecution(wire))

    // ---- AREA 5 identity-binding seed (R5-F1, §11.7) ----
    //
    // Sol's round-4 combined attack greened the round-4 "countAll()==1" mint assertion by minting
    // TrustedQuotaEntry(taskId = 1L, …) — a CONSTANT identity. The round-5 mint test defeats that by
    // seeding a GENUINE plan→task→session→attempt aggregate with explicit ids (≠ 1L, ≠ the AREA-4
    // helper defaults), driving the production entrypoint, then reading the minted entry back and
    // asserting it binds the SEEDED task/attempt/digest/commit-clock — every field a constant-identity
    // bad impl gets wrong. Room honours an explicit non-zero @PrimaryKey(autoGenerate) id; the helper
    // validates each seed via read-back so a setup failure is loud, not a silent false GREEN.
    private val SEEDED_TASK_ID = 42L    // ≠ 1L — the constant Sol's round-4 attack hardcoded
    private val SEEDED_ATTEMPT_ID = 77L // ≠ the AREA-4 execution() default (10L)
    private val DISTINCTIVE_DIGEST = "sha256:r5f1-identity:7a3b9e" // ≠ "payload-digest"; defeats a fixed literal

    private class SeededIds(val taskId: Long, val attemptId: Long)

    /** Seeds a real plan→task→session→attempt aggregate with explicit, non-default ids (R5-F1). */
    private suspend fun seedR5F1Aggregate(): SeededIds {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "r5f1.csv", importedAt = 0L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1
            )
        )
        db.planDao().insertTasks(
            listOf(
                LocationTask(
                    id = SEEDED_TASK_ID, planId = planId, csvRow = 1,
                    longitude = TARGET_LNG, latitude = TARGET_LAT,
                    priority = 1, requiredSuccesses = 1
                )
            )
        )
        val seededTask = db.locationTaskDao().getTaskById(SEEDED_TASK_ID)
            ?: error("R5-F1 setup: Room did not honour the explicit task id $SEEDED_TASK_ID")
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 0L, planId = planId))
        db.testAttemptDao().insert(
            TestAttempt(
                id = SEEDED_ATTEMPT_ID, taskId = seededTask.id, runSessionId = sessionId,
                attemptOrdinal = 1, successOrdinal = null, startedAt = 0L,
                runningObservedAt = null, endedAt = null, status = "running",
                failureReason = null, webBrowsingScore = null, videoStreamingScore = null,
                latitude = TARGET_LAT, longitude = TARGET_LNG
            )
        )
        val seededAttempt = db.testAttemptDao().getAttemptsForTask(seededTask.id)
            .firstOrNull { it.id == SEEDED_ATTEMPT_ID }
            ?: error("R5-F1 setup: Room did not honour the explicit attempt id $SEEDED_ATTEMPT_ID")
        return SeededIds(taskId = seededTask.id, attemptId = seededAttempt.id)
    }

    private fun fail(ctx: CompletionTrustContext) =
        assertEquals(TrustDecision.FAIL, TrustPolicy().evaluate(ctx))

    // === The ONE positive (RED under the FAIL skeleton; only the full §6.4 predicate passes it) ===

    @Test
    fun `a fully-verified SYSTEM_MOCK completion passes`() {
        // §6.4 positive tuple. Skeleton returns FAIL → RED until GREEN implements the full predicate.
        assertEquals(
            "the canonical §6.4 positive bundle (SYSTEM_MOCK + isMock=true + ALLOWED_NOW + FULL + " +
                "bracketed + continuous + three-way intent + coord within 1.0 m) must PASS",
            TrustDecision.PASS,
            TrustPolicy().evaluate(validContext())
        )
    }

    // === §6.4.1 矛盾 tuples — each a distinct must-FAIL negative ===

    @Test
    fun `HOOK deliveryMode masquerading as independent verification fails`() =
        // §6.4.1: HOOK + SYSTEM_MOCK_INDEPENDENTLY_VERIFIED ⇒ fail (Hook masquerades as verify, INV-06).
        fail(validContext().copy(preObservation = validPre().copy(deliveryMode = "HOOK")))

    @Test
    fun `isMock false under a verified level fails`() =
        // §6.4.1: isMock=false + VERIFIED ⇒ fail (environment not actually mocking). This was the
        // round-2 INVERTED "positive"; it is a negative, and it is what defeats that counterexample.
        fail(validContext().copy(preObservation = validPre().copy(isMock = false)))

    @Test
    fun `isMock null under a verified level fails`() =
        // §6.4.1: isMock=null + VERIFIED ⇒ fail ("unknown" cannot stand for "verified").
        fail(validContext().copy(preObservation = validPre().copy(isMock = null)))

    @Test
    fun `scheduleDecision DENIED under a verified level fails`() =
        // §6.4.1: schedule disallows running, yet counted ⇒ fail.
        fail(validContext().copy(preObservation = validPre().copy(scheduleDecision = "DENIED")))

    @Test
    fun `scheduleDecision WAIT_UNTIL under a verified level fails`() =
        fail(validContext().copy(postObservation = validPost().copy(scheduleDecision = "WAIT_UNTIL")))

    @Test
    fun `coverage FULL but continuitySince null fails`() =
        // §6.4.1: claims FULL coverage but gives no continuity start ⇒ fail.
        fail(validContext().copy(preObservation = validPre().copy(continuitySinceElapsedRealtimeMs = null)))

    @Test
    fun `continuitySince after the pre observation fails`() =
        // §6.4.1: continuity window starts after pre-observe ⇒ did not cover the test. Both sides
        // equal (per §6.4) but later than PRE_OBSERVED_AT_ELAPSED.
        fail(
            validContext().copy(
                preObservation = validPre().copy(continuitySinceElapsedRealtimeMs = 1500L),
                postObservation = validPost().copy(continuitySinceElapsedRealtimeMs = 1500L)
            )
        )

    @Test
    fun `a post observation before completion fails to bracket`() =
        // §6.4.1: post.observedAt < CellRebel completion ⇒ not a post-observation.
        fail(validContext().copy(postObservation = validPost().copy(observedAtElapsedRealtimeMs = 4000L)))

    @Test
    fun `empty evidenceRefs under a verified level fails`() =
        // §6.4.1: empty evidenceRefs + VERIFIED ⇒ unverifiable "verified".
        fail(validContext().copy(preObservation = validPre().copy(evidenceRefs = emptyList())))

    // === §8.6.2 wire inversions (only wire 1 may PASS) ===

    @Test
    fun `pre-existing-run wire 2 fails`() = fail(validContext(wire = 2))

    @Test
    fun `weak-running-evidence running-too-short and no-completion-evidence wires 3 to 5 fail`() {
        for (wire in listOf(3, 4, 5)) {
            assertEquals("wire $wire must not be admitted", TrustDecision.FAIL, TrustPolicy().evaluate(validContext(wire)))
        }
    }

    // === Three-way intent binding (INV-23) inversions ===

    @Test
    fun `apply-receipt intent hash disagreeing with the observations fails`() =
        fail(validContext().copy(applyReceiptIntentHash = "other"))

    @Test
    fun `locally-recomputed intent hash disagreeing with the receipt fails`() =
        fail(validContext().copy(locallyRecomputedIntentHash = "other"))

    @Test
    fun `an observation intent hash disagreeing with the receipt fails`() =
        fail(validContext().copy(preObservation = validPre().copy(acceptedIntentHash = "other")))

    // === Lease / coordinate (INV-07/23) inversions ===

    @Test
    fun `pre and post observations bound to different leases fail`() =
        fail(validContext().copy(postObservation = validPost().copy(leaseId = "L2")))

    @Test
    fun `a null effective coordinate fails`() =
        fail(validContext().copy(preObservation = validPre().copy(effectiveLat = null, effectiveLng = null)))

    @Test
    fun `a coordinate outside the 1_0 m tolerance fails`() =
        // 0.001 deg latitude ≈ 111 m ≫ 1.0 m tolerance (§6.4.2).
        fail(validContext().copy(preObservation = validPre().copy(effectiveLat = 40.001)))

    // === Per-observation field inversions (every remaining §6.4 predicate field) ===

    @Test
    fun `coverage below FULL fails`() =
        fail(validContext().copy(preObservation = validPre().copy(coverage = "PARTIAL")))

    @Test
    fun `verificationLevel below SYSTEM_MOCK_INDEPENDENTLY_VERIFIED fails`() =
        fail(validContext().copy(preObservation = validPre().copy(verificationLevel = "HOOK")))

    @Test
    fun `deliveryMode other than SYSTEM_MOCK fails`() =
        fail(validContext().copy(postObservation = validPost().copy(deliveryMode = "REAL")))

    @Test
    fun `environmentRevision differing across observations fails`() =
        fail(validContext().copy(postObservation = validPost().copy(environmentRevision = REVISION + 1)))

    @Test
    fun `environmentFingerprint differing across observations fails`() =
        fail(validContext().copy(postObservation = validPost().copy(environmentFingerprint = "fp-2")))

    @Test
    fun `a pre observation not before execution start fails to bracket`() =
        // pre.observedAt > execution.startedAtElapsed ⇒ does not precede the run.
        fail(validContext().copy(preObservation = validPre().copy(observedAtElapsedRealtimeMs = 2500L)))

    // === Symmetric POST-observation inversions (F1c — the predicate must validate POST too, not just PRE) ===
    //
    // §6.4 binds pre AND post to the same lease and requires BOTH to carry every discriminator. The PRE
    // inversions above are necessary but NOT sufficient: a partial GREEN that validates only the pre
    // observation would PASS the positive (whose post is also valid) and FAIL every PRE negative, greening
    // the suite while leaving the post observation unvalidated. Each test below inverts exactly ONE field
    // on the POST observation (pre stays canonical) and asserts FAIL — so a PRE-only impl fails it.

    @Test
    fun `post isMock false under a verified level fails`() =
        fail(validContext().copy(postObservation = validPost().copy(isMock = false)))

    @Test
    fun `post isMock null under a verified level fails`() =
        fail(validContext().copy(postObservation = validPost().copy(isMock = null)))

    @Test
    fun `post coverage below FULL fails`() =
        fail(validContext().copy(postObservation = validPost().copy(coverage = "PARTIAL")))

    @Test
    fun `post verificationLevel below SYSTEM_MOCK_INDEPENDENTLY_VERIFIED fails`() =
        fail(validContext().copy(postObservation = validPost().copy(verificationLevel = "HOOK")))

    @Test
    fun `post continuitySince null fails`() =
        // post claims FULL coverage but drops the continuity start ⇒ coverage unproven at POST.
        fail(validContext().copy(postObservation = validPost().copy(continuitySinceElapsedRealtimeMs = null)))

    @Test
    fun `post empty evidenceRefs fails`() =
        fail(validContext().copy(postObservation = validPost().copy(evidenceRefs = emptyList())))

    @Test
    fun `a post coordinate outside the 1_0 m tolerance fails`() =
        // Same ~111 m displacement as the PRE coordinate negative, applied to post.
        fail(validContext().copy(postObservation = validPost().copy(effectiveLat = 40.001)))

    // === R6-F1（§11.7）: closes Sol's round-5 F1 residuals — caller-tolerance false-oracle + un-bound lease + POST intent ===
    //
    // Sol's round-5 verdict: R5-F1 was still greenable via (a) the predicate using the CALLER-provided
    // [CompletionTrustContext.locationToleranceMeters] as the pass threshold (grounding §11.7 line 528 — a
    // caller injects its own loose bound); (b) observations bound to EACH OTHER but not to the receipt's
    // lease (no applyReceiptLease field, so a `pre.leaseId == post.leaseId` oracle greened it); and
    // (c) only the PRE observation's intent hash checked against the receipt. Each negative below defeats
    // one residual. They all PASS under the FAIL skeleton (trivially) but stay RED under the F1 combined
    // attack (full §6.4 predicate + caller-tolerance + wrong-lease-acceptance) — verified in the R6-C self-gate.

    @Test
    fun `R6-F1 a caller-injected loose tolerance does NOT override the frozen 1_0 m bound`() =
        // §11.7 caller-tolerance false-oracle: caller passes locationToleranceMeters = 50.0 m and a PRE coord
        // ~20 m off intent (≫ frozen 1.0 m, ≪ 50.0 m). GREEN MUST gate on the FROZEN
        // TRUSTED_LOCATION_TOLERANCE_METERS (1.0 m), not the caller field ⇒ 20 m > 1.0 m ⇒ FAIL. A bad impl
        // that does `haversine <= context.locationToleranceMeters` sees 20 m < 50.0 m ⇒ PASS ⇒ fails this.
        fail(
            validContext().copy(
                locationToleranceMeters = 50.0,
                preObservation = validPre().copy(effectiveLat = TARGET_LAT + 0.00018) // ≈ 20 m off
            )
        )

    @Test
    fun `R6-F1 a POST observation intent hash disagreeing with the receipt fails`() =
        // §11.7 POST-intent residual: the PRE observation's intent matches the receipt, but the POST
        // observation's acceptedIntentHash differs ⇒ FAIL. A partial GREEN that validates only the PRE
        // observation's intent (pre==receipt) PASSes this wrong tuple ⇒ fails this negative.
        fail(validContext().copy(postObservation = validPost().copy(acceptedIntentHash = "other")))

    @Test
    fun `R6-F1 observations matching each other but NOT the receipt lease fail`() =
        // §11.7 un-bound-lease residual: pre.leaseId == post.leaseId == LEASE ("L1"), but the receipt's
        // applyReceiptLease is "L-OTHER". GREEN MUST bind each observation to the RECEIPT's lease (INV-07),
        // not merely to each other ⇒ FAIL. A `pre.leaseId == post.leaseId` oracle PASSes ⇒ fails this.
        fail(validContext().copy(applyReceiptLease = "L-OTHER"))

    @Test
    fun `R6-F1 a PRE observation lease disagreeing with the receipt lease fails`() =
        // §11.7: pre.leaseId != applyReceiptLease (post still matches the receipt) ⇒ FAIL — pins the PRE
        // side to the receipt lease explicitly (the existing `different leases` test flips POST; this flips
        // PRE against the receipt, closing the symmetric direction of the binding).
        fail(validContext().copy(preObservation = validPre().copy(leaseId = "L-pre-other")))

    // ---- AREA 5: production persist+mint entrypoint via PlanRepository (RED — §11.2 F1 / §11.4) ----
    //
    // Drives the REAL production entrypoint [PlanRepository.recordTrustedCompletion] against the
    // in-memory Room DB and asserts DURABLE EFFECTS through the real DAOs (§11.1 round-4 standard).
    // The skeleton (1) persists digest+3clocks DROPPING the §7.1 detail and (2) mints nothing — so a
    // §6.4-positive completion read back shows the §7.1 fields NULL and zero TrustedQuotaEntry (RED).
    // GREEN copies the §7.1 detail into the row and mints exactly one entry on PASS; R5-F1 (§11.7)
    // additionally reads that entry back and asserts it binds the SEEDED attempt→task identity (not a
    // constant), so Sol's round-4 `taskId = 1L` combined attack stays RED.

    @Test
    fun `recordTrustedCompletion persists the full section 7_1 evidence detail through the production entrypoint`() = runTest {
        val repo = PlanRepository(db)
        repo.recordTrustedCompletion(fullContext())

        val row = db.attemptExecutionDao().byExecutionId("exec-$WIRE_VERIFIED")
        assertNotNull("execution row must be persisted through the production entrypoint", row)
        val exec = row!!
        // §7.1 detail must survive the entrypoint (GREEN copies it; skeleton drops it ⇒ null ⇒ RED).
        // assertNotNull precedes every `!!` unwrap so the skeleton fails an AssertionError, never a NPE
        // (round-4 standard: 0 errors).
        assertEquals("baseline state must be persisted", "IDLE", exec.baselineRunningState)
        assertEquals("running marker must be persisted", "RUNNING", exec.runningMarkerText)
        assertNotNull("RUNNING duration must be persisted", exec.runningDurationMs)
        assertEquals(
            "RUNNING duration (≥ §6.4.2 floor)",
            EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
            exec.runningDurationMs!!
        )
        assertNotNull("web score must be persisted", exec.webBrowsingScore)
        assertEquals("web score", 8.0, exec.webBrowsingScore!!, 0.001)
        assertNotNull("video score must be persisted", exec.videoStreamingScore)
        assertEquals("video score", 7.0, exec.videoStreamingScore!!, 0.001)
        assertEquals(
            "per-round timestamps",
            "${EXEC_STARTED_AT_ELAPSED};${EXEC_COMPLETED_AT_ELAPSED}",
            exec.roundTimestampsElapsed
        )
    }

    @Test
    fun `recordTrustedCompletion mints one TrustedQuotaEntry bound to the seeded attempt-task identity not a constant`() = runTest {
        // R5-F1 (§11.7): the minted entry must bind the REAL seeded attempt→task aggregate identity,
        // not a hardcoded constant. Sol's round-4 combined attack greened the round-4 "countAll()==1"
        // assertion by minting TrustedQuotaEntry(taskId = 1L, …) — a constant. This test seeds a
        // genuine LocationTask (id ≠ 1L) + TestAttempt, drives the production entrypoint, then READS
        // BACK the minted entry and asserts every identity field binds the seeded aggregate. A
        // constant-taskId / constant-attemptId / constant-digest / constant-clock impl fails ≥ 1.
        val repo = PlanRepository(db)
        val aggregate = seedR5F1Aggregate()
        val ctx = fullContext().copy(
            execution = fullEvidenceExecution(WIRE_VERIFIED).copy(
                attemptId = aggregate.attemptId,
                evidencePayloadDigest = DISTINCTIVE_DIGEST
            )
        )

        val decision = repo.recordTrustedCompletion(ctx)
        assertEquals("a §6.4-positive completion must report PASS", TrustDecision.PASS, decision)

        val minted = db.trustedQuotaDao().getByAttempt(aggregate.attemptId)
        assertNotNull(
            "a §6.4-positive completion must mint exactly one entry for the seeded attempt (skeleton mints none ⇒ RED)",
            minted
        )
        val entry = minted!!
        // taskId must bind the REAL seeded task (42L) — NOT a hardcoded 1L (Sol's round-4 attack).
        assertEquals(
            "minted taskId must bind the seeded attempt's task, not a constant",
            aggregate.taskId, entry.taskId
        )
        // attemptId must bind the REAL seeded attempt — not a constant.
        assertEquals(
            "minted attemptId must bind the seeded attempt, not a constant",
            aggregate.attemptId, entry.attemptId
        )
        // evidenceDigest must bind the execution's evidence payload — not a fixed literal.
        assertEquals(
            "minted evidenceDigest must bind the execution evidence, not a constant",
            DISTINCTIVE_DIGEST, entry.evidenceDigest
        )
        // committedAt must be the COMMIT time (>= completion clock), not a constant and not < completion.
        // Sol R35 P2-3: TrustedQuotaEntry.committedAt docs say "commit timestamp"; binding it == to the
        // evidence completion clock is reverse-defined. The commit happens at or after completion.
        assertTrue(
            "minted committedAt must be the commit time (>= completedAtElapsed $EXEC_COMPLETED_AT_ELAPSED), not a constant",
            entry.committedAt >= EXEC_COMPLETED_AT_ELAPSED
        )
        assertEquals(
            "exactly one TrustedQuotaEntry through the entrypoint",
            1, db.trustedQuotaDao().countAll()
        )
    }

    @Test
    fun `recordTrustedCompletion mints nothing and reports FAIL when the section 6_4 predicate fails`() = runTest {
        val repo = PlanRepository(db)
        // §6.4.1 contradiction: HOOK deliveryMode masquerading as independently verified ⇒ FAIL (INV-06).
        val negative = fullContext().copy(preObservation = validPre().copy(deliveryMode = "HOOK"))
        val decision = repo.recordTrustedCompletion(negative)

        assertEquals(TrustDecision.FAIL, decision)
        assertEquals(
            "a §6.4-failing completion must mint no TrustedQuotaEntry",
            0,
            db.trustedQuotaDao().countAll()
        )
    }

    @Test
    fun `recordTrustedCompletion mints nothing when the three-way intent hash mismatches`() = runTest {
        // R5-F1 (§11.7): an intent mismatch (INV-23) is a trust failure; the entrypoint must mint
        // NOTHING and report FAIL — it cannot mint-then-ignore. This is a GUARDRAIL: it is green under
        // the skeleton (which never mints) AND under a correct GREEN (mint is gated on PASS); it goes
        // RED only under a "mint regardless of decision" bad impl, closing that attack surface for the
        // round-5 self-gate's combined-attack run.
        val repo = PlanRepository(db)
        val intentMismatch = fullContext().copy(applyReceiptIntentHash = "mismatched-receipt-intent")
        val decision = repo.recordTrustedCompletion(intentMismatch)

        assertEquals(TrustDecision.FAIL, decision)
        assertEquals(
            "an intent-mismatch completion must mint no TrustedQuotaEntry",
            0, db.trustedQuotaDao().countAll()
        )
    }
}
