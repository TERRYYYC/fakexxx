package com.example.cellrebelauto.automation.aplus

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Single A+ template: terminal bypass + lease gate + no-silent-TOFU (Issue #5 Task 4, area 5).
 *
 * AREA 5a (RED, INV-22/28): [AttemptGuard.canBeginApply] must reject a terminal (CLOSED) attempt
 * and any state while the prior lease is not released. The skeleton returns true unconditionally,
 * so both assertions FAIL until GREEN. [isTerminal] is correct trivially (enum membership).
 *
 * AREA 5b (RED, INV-22/§6.5.3): [ProviderTrustStore] exposes exactly 3 methods so no code path can
 * silently mint a trusted provider. An unseen signer stops at NOT_PAIRED; only operator [approve]
 * creates an active record, and [revoke] is a state transition (sets revokedAt), not a delete. The
 * approve/revoke skeletons are no-ops, so those assertions FAIL until GREEN.
 *
 * # 单一 A+ 模板：终态/lease 门（RED）+ 无 silent TOFU（approve/revoke RED，三方法面冻结）
 */
@RunWith(RobolectricTestRunner::class)
class APlusTemplateRedTest {

    private val testProviderSigner =
        "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    // ---- AREA 5a: terminal bypass / lease gate (AttemptGuard) ----

    @Test
    fun `a terminal attempt cannot begin a new apply`() {
        val guard = AttemptGuard()
        // RED (INV-22): a CLOSED attempt is terminal and must never begin a new apply. Skeleton
        // returns true → this fails until GREEN.
        assertFalse(
            "terminal (CLOSED) attempt must not begin an apply",
            guard.canBeginApply(AttemptState.CLOSED, leaseReleased = true)
        )
    }

    @Test
    fun `a non-released lease blocks beginning an apply`() {
        val guard = AttemptGuard()
        // RED (INV-28): beginning an apply while the prior lease is not released would let two
        // attempts race on the same slot. Skeleton returns true → fails until GREEN.
        assertFalse(
            "apply must not begin while the prior lease is not released",
            guard.canBeginApply(AttemptState.CELLREBEL_RUNNING, leaseReleased = false)
        )
    }

    @Test
    fun `a fresh attempt whose prior lease is not released cannot begin an apply`() {
        val guard = AttemptGuard()
        // Polarity gap (INV-28): even a CREATED attempt must not begin while the prior lease is not
        // released — otherwise two attempts could race on the same slot. Skeleton returns true →
        // fails until GREEN, and anchors that GREEN's lease gate covers the fresh-attempt case too.
        assertFalse(
            "a CREATED attempt must not begin an apply while the prior lease is not released",
            guard.canBeginApply(AttemptState.CREATED, leaseReleased = false)
        )
    }

    @Test
    fun `canBeginApply is allowed on a fresh attempt with a released lease`() {
        val guard = AttemptGuard()
        // The legitimate path. RED note: skeleton returns true here too, so this passes now; once
        // GREEN tightens the guard this must STILL pass (CREATED + leaseReleased = the one allowed
        // entry). It anchors that GREEN does not over-block the happy path.
        assertTrue(
            "a CREATED attempt with a released lease may begin an apply",
            guard.canBeginApply(AttemptState.CREATED, leaseReleased = true)
        )
    }

    @Test
    fun `isTerminal recognizes CLOSED and only CLOSED`() {
        val guard = AttemptGuard()
        assertTrue(guard.isTerminal(AttemptState.CLOSED))
        // Every non-CLOSED state is non-terminal (a closed attempt cannot be revived; a new run
        // creates a new attempt).
        for (state in AttemptState.entries) {
            if (state == AttemptState.CLOSED) continue
            assertFalse("$state must not be terminal", guard.isTerminal(state))
        }
    }

    // ---- AREA 5b: no silent TOFU (ProviderTrustStore) ----

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

    @Test
    fun `an unseen provider is not trusted`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        // GREEN-from-skeleton: an unseen applicationId has no active pairing — no silent TOFU.
        assertNull(store.findActive("com.cellrebel.app", testProviderSigner))
        assertEquals(0, db.providerPairingDao().count())
    }

    @Test
    fun `operator approval makes a provider active`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        val approved = store.approve("com.cellrebel.app", signerDigest = testProviderSigner, versionCode = 10, approvedAt = 1000L)
        // RED: skeleton.approve returns null and findActive stays null → both fail until GREEN.
        assertNotNull("approve must persist and return the active record", approved)
        val active = store.findActive("com.cellrebel.app", testProviderSigner)
        assertNotNull("an approved provider must be findActive", active)
        assertEquals(testProviderSigner, active?.currentSignerDigest)
        assertEquals(10, active?.approvedVersionCode)
    }

    @Test
    fun `revocation is a state transition not a delete`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        store.approve("com.cellrebel.app", testProviderSigner, 10, 1000L)
        val revoked = store.revoke("com.cellrebel.app", testProviderSigner, revokedAt = 2000L)
        // RED: skeleton.revoke returns false (no-op). GREEN must set revokedAt and stop findActive.
        assertTrue("revoke must return true on an active record", revoked)
        assertNull("a revoked provider must not be findActive", store.findActive("com.cellrebel.app", testProviderSigner))
        // The row is retained (revokedAt set), not deleted — revocation provenance is preserved
        // (§6.5.3: revocation is a state transition, never a hard delete).
        assertTrue(
            "the revoked row must still exist (state transition, not delete)",
            db.providerPairingDao().byApplicationId("com.cellrebel.app").isNotEmpty()
        )
    }

    // ---- AREA 5c: sealed APlusRunTemplate + typed-step-sequence oracle (Sol round-4 Finding 4, Gap B) ----
    //
    // Sol's round-4 standard (§11.2 F4): the UNIQUE typed step sequence (TRUSTED_SYSTEM_MOCK_BATCH_V1)
    // must be driven through the REAL [AttemptTransitions] state machine — NOT enum-presence checks. A bad
    // impl that asserts a hardcoded mapping or merely checks that enum values are present MUST still leave
    // the integration assertion RED. The oracle COMPUTES the walk by calling [AttemptTransitions.next]
    // (the independent state machine) — it never reads a template-authored "finalState", so a template
    // that precomputes CLOSED cannot fool it. The RED lives in [AttemptTransitions.next] (a no-op
    // skeleton); the template itself ([APlusRunTemplate]) is pure frozen data (§2.2 "冻结").

    @Test
    fun `APlusRunTemplate is sealed to a single frozen instance - TRUSTED_SYSTEM_MOCK_BATCH_V1`() {
        // §2.2/§3.1: A+ is ONE frozen template; no open extension (§2.3 non-goal: no plugin DAG/scripting).
        // The `sealed` modifier (compile-time) guarantees no instance exists outside the template file;
        // this pins the known set at runtime. ANCHOR (structural; passes under the skeleton) — the
        // template file is already sealed with exactly one instance.
        assertEquals(
            "the A+ template set must be exactly [TRUSTED_SYSTEM_MOCK_BATCH_V1]",
            listOf(APlusRunTemplate.TRUSTED_SYSTEM_MOCK_BATCH_V1),
            APlusRunTemplate.ALL
        )
        assertEquals("A+ has exactly one frozen template", 1, APlusRunTemplate.ALL.size)
    }

    @Test
    fun `the frozen typed-step sequence is the single canonical discover-to-release order`() {
        // §3.1 step list, frozen. Only one sequence exists; its order is not a plugin point. ANCHOR (the
        // skeleton already carries the frozen §3.1 order — this pins it so GREEN cannot silently reorder).
        assertEquals(
            "the frozen §3.1 typed-step order: discover → preflight → apply → observe(pre) → CellRebel → " +
                "observe(post) → decide → count → release",
            listOf("DISCOVER", "PREFLIGHT", "APPLY", "OBSERVE_PRE", "CELLREBEL", "OBSERVE_POST", "DECIDE", "COUNT", "RELEASE"),
            APlusTypedStep.entries.map { it.name }
        )
        assertEquals(
            "TRUSTED_SYSTEM_MOCK_BATCH_V1 must carry the full frozen typed-step sequence",
            APlusTypedStep.entries,
            APlusRunTemplate.TRUSTED_SYSTEM_MOCK_BATCH_V1.typedSteps
        )
    }

    @Test
    fun `the frozen canonical event sequence driven through the real state machine walks CREATED to CLOSED`() {
        // THE integration oracle (Sol's standard). Drive the template's frozen §8.1 happy-path event
        // sequence through the REAL [AttemptTransitions.next] — the walk is computed HERE, independently
        // of any template-authored result — and assert it lands on CLOSED.
        // RED under the skeleton: [AttemptTransitions.next] returns `current`, so the walk never leaves
        // CREATED ⇒ the final state is CREATED, not CLOSED. GREEN must implement the full §8.1 table for
        // the chain to traverse end-to-end. A bad impl that asserts a mapping or precomputes CLOSED
        // inside the template cannot fool this — it does not read any template-authored field.
        val walked = APlusRunTemplate.TRUSTED_SYSTEM_MOCK_BATCH_V1.canonicalEventSequence
            .fold(AttemptState.CREATED) { state, event -> AttemptTransitions.next(state, event) }
        assertEquals(
            "the frozen canonical event sequence driven through the real state machine must reach CLOSED " +
                "(got $walked — the skeleton no-op leaves the walk at CREATED)",
            AttemptState.CLOSED,
            walked
        )
    }

    @Test
    fun `a sequence with no RELEASE_RECEIPT never reaches CLOSED - there is no silent close`() {
        // GREEN-validity ANCHOR (passes under the skeleton). The canonical path MINUS its terminal
        // RELEASE_RECEIPT must NOT reach CLOSED — an attempt that never receives a release receipt cannot
        // close (no silent terminalization; §8.1: only RELEASE_PENDING + RELEASE_RECEIPT ⇒ CLOSED). Under
        // the skeleton the walk stays CREATED (≠ CLOSED); under a correct GREEN table it ends at
        // RELEASE_PENDING (≠ CLOSED). Either way this holds; it binds GREEN to honor the release receipt.
        val noRelease = APlusRunTemplate.CANONICAL_HAPPY_PATH.dropLast(1) // drop the terminal RELEASE_RECEIPT
        val walked = noRelease.fold(AttemptState.CREATED) { s, e -> AttemptTransitions.next(s, e) }
        assertTrue(
            "a sequence with no RELEASE_RECEIPT must not reach CLOSED (no silent close); got $walked",
            walked != AttemptState.CLOSED
        )
    }
}
