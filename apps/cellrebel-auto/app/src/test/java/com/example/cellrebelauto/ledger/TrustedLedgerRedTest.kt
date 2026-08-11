package com.example.cellrebelauto.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.environment.TrustPolicy
import com.example.cellrebelauto.model.execution.CellRebelCompletionEvidenceV1
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
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
 * uniqueness/append-only assertions pass now — they validate that the schema-level invariant is
 * actually live in the migrated database, independent of any application code.
 *
 * AREA 4 (RED, INV-07/11/23/27): [TrustPolicy] gates whether a classified execution may mint a
 * [TrustedQuotaEntry]. The skeleton always returns FAIL; the test asserting a VERIFIED execution
 * must PASS therefore FAILS until GREEN. Only the decision TYPE is frozen pre-freeze.
 *
 * # 可信账本唯一/只插不改（schema 已生效，GREEN-from-schema）+ 观察-判定归因（RED）
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

    // ---- AREA 1: uniqueness / append-only (GREEN-from-schema — validates the UNIQUE index is live) ----

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

    // ---- AREA 4: pre/post-observe attribution via TrustPolicy (RED) ----

    private fun execution(wire: Int): CellRebelExecution = CellRebelExecution(
        executionId = "exec-$wire",
        attemptId = 10L,
        completionEvidenceWire = wire,
        evidencePayloadDigest = "payload-digest",
        startedAt = 1000L,
        classifiedAt = 1100L
    )

    @Test
    fun `TrustPolicy admits a verified completion to PASS`() {
        // RED (INV-11/27): only a VERIFIED_NEW_COMPLETION execution — one whose pre + post
        // observations are bound to the same lease — may mint trusted quota. The skeleton returns
        // FAIL unconditionally, so this assertion FAILS until GREEN.
        val policy = TrustPolicy()
        val verified = execution(CellRebelCompletionEvidenceV1.VERIFIED_NEW_COMPLETION.wire)
        assertEquals(
            "a VERIFIED_NEW_COMPLETION execution must be admitted (PASS)",
            TrustDecision.PASS,
            policy.evaluate(verified)
        )
    }

    @Test
    fun `TrustPolicy rejects every non-verified evidence wire`() {
        // Documents the negative side of the gate. Passes now (skeleton returns FAIL for all) and
        // remains valid GREEN: wires 2-5 never produce trusted quota (INV-11).
        val policy = TrustPolicy()
        for (wire in listOf(2, 3, 4, 5)) {
            assertEquals(
                "wire $wire must not be admitted",
                TrustDecision.FAIL,
                policy.evaluate(execution(wire))
            )
        }
    }
}
