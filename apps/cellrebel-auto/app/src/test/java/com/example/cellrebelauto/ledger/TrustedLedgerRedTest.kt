package com.example.cellrebelauto.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.environment.TrustPolicy
import com.example.cellrebelauto.model.execution.CellRebelCompletionEvidenceV1
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trusted ledger at-most-once + pre/post-observe attribution (Issue #5 Task 4, areas 1 & 4).
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
        targetLat = TARGET_LAT,
        targetLng = TARGET_LNG,
        locationToleranceMeters = TOLERANCE_M,
        preObservation = validPre(),
        postObservation = validPost()
    )

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
}
