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
 * AREA 4 (RED, INV-07/11/23/27): [TrustPolicy.evaluate] gates whether a classified execution may
 * mint a [TrustedQuotaEntry]. TRUSTWORTHY RED: the context carries EVERY discriminator the
 * invariants require, and tests assert BOTH polarities — a valid bundle must PASS (RED until GREEN)
 * and inverting any single discriminator must FAIL. A no-semantic "false oracle" policy cannot
 * pass: `if wire==1 PASS` fails the wire=1 must-fail cases (mismatched hash, wrong lease, null
 * coords, mock, mode mismatch, un-bracketed window); `always PASS` fails the wire=2 case. Only the
 * full predicate satisfies every assertion. Only the decision TYPE is frozen pre-freeze.
 *
 * # 可信账本唯一/只插不改/并发（schema 已生效）+ 观察-判定归因（RED，双极性反 false-oracle）
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

    // ---- AREA 4: pre/post-observe attribution via TrustPolicy (RED — both-polarity oracle) ----

    private fun execution(wire: Int): CellRebelExecution = CellRebelExecution(
        executionId = "exec-$wire",
        attemptId = 10L,
        completionEvidenceWire = wire,
        evidencePayloadDigest = "payload-digest",
        startedAt = 1000L,
        classifiedAt = 1100L
    )

    private fun validPre() = ObservationSnapshot(
        leaseId = "L1", acceptedIntentHash = "intent-h", observedAt = 900L,
        mode = "gps", isMock = false, effectiveLat = 40.0, effectiveLng = -74.0
    )

    private fun validPost() = ObservationSnapshot(
        leaseId = "L1", acceptedIntentHash = "intent-h", observedAt = 1200L,
        mode = "gps", isMock = false, effectiveLat = 40.0, effectiveLng = -74.0
    )

    /** A fully-valid trust bundle: wire 1, three-way intent match, same lease, coords in tolerance,
     *  non-mock, mode matches verification level, observations bracket the execution window. */
    private fun validContext(wire: Int = CellRebelCompletionEvidenceV1.VERIFIED_NEW_COMPLETION.wire): CompletionTrustContext =
        CompletionTrustContext(
            execution = execution(wire),
            completionEvidenceWire = wire,
            applyReceiptIntentHash = "intent-h",
            locallyRecomputedIntentHash = "intent-h",
            targetLat = 40.0,
            targetLng = -74.0,
            locationToleranceMeters = 50.0,
            verificationLevel = "gps",
            coverage = "full",
            preObservation = validPre(),
            postObservation = validPost()
        )

    @Test
    fun `TrustPolicy admits a fully-verified completion to PASS`() {
        // RED (INV-11/23/27): only a fully-valid bundle may PASS. Skeleton returns FAIL → fails until GREEN.
        assertEquals(
            "a fully-verified completion must be admitted (PASS)",
            TrustDecision.PASS,
            TrustPolicy().evaluate(validContext())
        )
    }

    // --- Each discriminator inverted ⇒ FAIL. A "wire==1 ⇒ PASS" false oracle fails these. ---

    @Test
    fun `TrustPolicy rejects a pre-existing-run evidence wire`() {
        // §8.6.2 wire 2 (PRE_EXISTING_RUN): the observed run belongs to a prior attempt — never trusted.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext(wire = 2))
        )
    }

    @Test
    fun `TrustPolicy rejects every non-verified evidence wire`() {
        val policy = TrustPolicy()
        for (wire in listOf(3, 4, 5)) {
            assertEquals("wire $wire must not be admitted", TrustDecision.FAIL, policy.evaluate(validContext(wire)))
        }
    }

    @Test
    fun `TrustPolicy rejects when the apply-receipt intent hash disagrees`() {
        // INV-23 three-way binding: receipt hash must match locally recomputed AND the observations.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(applyReceiptIntentHash = "other"))
        )
    }

    @Test
    fun `TrustPolicy rejects when the locally-recomputed intent hash disagrees`() {
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(locallyRecomputedIntentHash = "other"))
        )
    }

    @Test
    fun `TrustPolicy rejects an observation whose intent hash disagrees with the receipt`() {
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(preObservation = validPre().copy(acceptedIntentHash = "other")))
        )
    }

    @Test
    fun `TrustPolicy rejects pre and post observations bound to different leases`() {
        // INV-07: pre and post observations must share the same lease.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(postObservation = validPost().copy(leaseId = "L2")))
        )
    }

    @Test
    fun `TrustPolicy rejects a null effective coordinate`() {
        // INV-23: effective coordinates must be present (non-null) to be trusted.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(
                validContext().copy(preObservation = validPre().copy(effectiveLat = null, effectiveLng = null))
            )
        )
    }

    @Test
    fun `TrustPolicy rejects coordinates outside the trusted tolerance`() {
        // INV-23: ~1.1 km latitude delta is far outside the 50 m tolerance.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(preObservation = validPre().copy(effectiveLat = 40.01)))
        )
    }

    @Test
    fun `TrustPolicy rejects a mocked observation under gps verification`() {
        // INV-27: a mock location is never cross-consistent with a real gps verification level.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(postObservation = validPost().copy(isMock = true)))
        )
    }

    @Test
    fun `TrustPolicy rejects an observation mode that does not match the verification level`() {
        // INV-27: observation mode must cross-match the required verification level.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(postObservation = validPost().copy(mode = "network")))
        )
    }

    @Test
    fun `TrustPolicy rejects a post observation that does not bracket the execution window`() {
        // INV-27: post-observation at t=800 is before execution startedAt=1000 → does not bracket.
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(validContext().copy(postObservation = validPost().copy(observedAt = 800L)))
        )
    }
}
